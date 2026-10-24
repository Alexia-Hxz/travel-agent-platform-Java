import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TripAdjustmentAgent from '@/views/TripAdjustmentAgent.vue'
import TripMap from './TripMap.vue'
import ItineraryTimeline from './ItineraryTimeline.vue'
import PlaceThumbnail from './PlaceThumbnail.vue'
import type { AdjustmentDraft } from '@/types/adjustment'
import type { ItineraryItem, Place } from '@/types/itinerary'
import { approximateCoordinates, distanceKm, hasCoordinates } from '@/utils/workspace'

const place: Place = { id: 1, trip_id: 1, name: '西湖', type: 'attraction', address: '杭州西湖', latitude: 30.25, longitude: 120.14, image_url: null, rating: null, price_level: null, opening_hours: null, source_url: null, estimated_cost: 200 }
const item: ItineraryItem = { id: 1, trip_id: 1, place_id: 1, place, date: '2026-10-02', start_time: '09:00:00', end_time: '11:00:00', sort_order: 0, note: null }
const draft: AdjustmentDraft = { id: 2, trip_id: 1, instruction: '下午轻松一点', status: 'pending', summary: '减少一个地点，留出休息时间。', operations: [{ kind: 'remove_item', item_id: 1, place_id: null, place: null, date: null, start_time: null, end_time: null, note: null, sort_order: 0 }], budget: { total_estimate: 60, trip_budget: 3200 }, daily_plan: [], final_summary: '调整后的行程', conflicts: [], events: [], sources: [] }

afterEach(() => vi.unstubAllGlobals())
describe('Workspace interactions', () => {
  it('creates a compact adjustment preview without writing until Apply is pressed', async () => {
    const fetcher = vi.fn((input: RequestInfo | URL) => Promise.resolve(new Response(JSON.stringify(String(input).endsWith('/apply') ? { status: 'applied', id: 2, itinerary_item_ids: [] } : draft), { status: 200 })))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(TripAdjustmentAgent, { props: { compact: true, tripId: 1, itineraryItems: [item], places: [place], startDate: '2026-10-02', endDate: '2026-10-02', originalBudget: 3200, people: 1 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('.copilot-composer textarea').setValue('下午轻松一点')
    await wrapper.get('.copilot-composer').trigger('submit')
    await flushPromises()
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.adjustment-preview').text()).toContain('预计节省 ¥200')
    expect(wrapper.get('.budget-impact').text()).toContain('调整前 ¥260')
    await wrapper.get('.preview-actions .el-button--primary').trigger('click')
    await flushPromises()
    expect(fetcher.mock.calls[1][0]).toContain('/adjustments/2/apply')
    expect(wrapper.emitted('itinerary-updated')).toHaveLength(1)
    expect(wrapper.find('.preview-actions').exists()).toBe(false)
    wrapper.unmount()
  })

  it('rejects a draft without emitting itinerary or budget writes', async () => {
    const fetcher = vi.fn((input: RequestInfo | URL) => Promise.resolve(new Response(JSON.stringify(String(input).endsWith('/reject') ? { ...draft, status: 'rejected' } : draft), { status: 200 })))
    vi.stubGlobal('fetch', fetcher)
    const wrapper = mount(TripAdjustmentAgent, { props: { compact: true, tripId: 1, itineraryItems: [item] }, global: { plugins: [ElementPlus] } })
    await wrapper.get('.copilot-composer textarea').setValue('下午轻松一点')
    await wrapper.get('.copilot-composer').trigger('submit')
    await flushPromises()
    await wrapper.get('.reject-draft').trigger('click')
    await flushPromises()
    expect(fetcher.mock.calls[1][0]).toContain('/reject')
    expect(wrapper.emitted('itinerary-updated')).toBeUndefined()
    expect(wrapper.emitted('budget-updated')).toBeUndefined()
    expect(wrapper.text()).toContain('已丢弃')
    wrapper.unmount()
  })

  it('does not join a route across a place that lacks coordinates', () => {
    const missing = { ...item, id: 2, place: { ...place, latitude: null } }
    const end = { ...item, id: 3, place: { ...place, longitude: 120.17 } }
    const wrapper = mount(TripMap, { props: { items: [item, missing, end], selectedId: null, destination: '杭州' }, global: { plugins: [ElementPlus] } })
    expect(wrapper.findAll('.map-waypoint')).toHaveLength(2)
    expect(wrapper.findAll('polyline')).toHaveLength(0)
    expect(wrapper.text()).toContain('1 处缺少坐标')
    expect(wrapper.text()).not.toContain('公里')
    wrapper.unmount()
  })

  it('keeps coordinates on map selection and time ordering connected to the timeline', async () => {
    const wrapper = mount(ItineraryTimeline, { props: { items: [item], day: item.date, selectedId: null, hasPlaces: true }, global: { plugins: [ElementPlus] } })
    await wrapper.get('.itinerary-stop').trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual([item])
    wrapper.unmount()
    const map = mount(TripMap, { props: { items: [item], selectedId: item.id, destination: '杭州' }, global: { plugins: [ElementPlus] } })
    expect(map.get('.map-waypoint').classes()).toContain('active')
    await map.get('.map-waypoint').trigger('click')
    expect(map.emitted('select')?.[0]).toEqual([item])
    map.unmount()
  })

  it('shows a local icon fallback after a photo fails without requesting the failed photo repeatedly', async () => {
    const wrapper = mount(PlaceThumbnail, { props: { src: 'https://example.com/broken.jpg', name: '西湖' }, global: { plugins: [ElementPlus] } })
    await wrapper.get('img').trigger('error')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toContain('暂无照片')
    wrapper.unmount()
  })

  it('rejects missing and invalid coordinates instead of inventing a distance', () => {
    expect(hasCoordinates({ latitude: null, longitude: null })).toBe(false)
    expect(hasCoordinates({ latitude: NaN, longitude: 120 })).toBe(false)
    expect(hasCoordinates({ latitude: 30, longitude: 181 })).toBe(false)
    expect(distanceKm(place, { ...place, latitude: null })).toBeNull()
    expect(distanceKm(place, place)).toBe(0)
  })

  it('keeps the planner position only when the map could actually draw it', () => {
    // Saving a position the map rejects would produce a place that is on the calendar but
    // invisible on the map, so the two must agree on what a usable pair is.
    expect(approximateCoordinates({ latitude: 31.2397, longitude: 121.49 })).toEqual({ latitude: 31.2397, longitude: 121.49 })
    expect(approximateCoordinates({ latitude: null, longitude: 121.49 })).toEqual({ latitude: null, longitude: null })
    expect(approximateCoordinates({ latitude: 31.2397, longitude: null })).toEqual({ latitude: null, longitude: null })
    expect(approximateCoordinates({ latitude: 91, longitude: 121.49 })).toEqual({ latitude: null, longitude: null })
    expect(approximateCoordinates({ latitude: 31.2397, longitude: 181 })).toEqual({ latitude: null, longitude: null })
    expect(approximateCoordinates({})).toEqual({ latitude: null, longitude: null })
  })

  it('draws the day as a self-contained sketch instead of depending on a tile server', () => {
    // The base map used to be OpenStreetMap tiles, and from this network not one tile could be
    // fetched: the panel sat on "正在加载地图…" and then asked the traveller to retry forever.
    // The route sketch needs no external host, so nothing here may load an image again.
    const wrapper = mount(TripMap, { props: { items: [item], selectedId: null, destination: '杭州' }, global: { plugins: [ElementPlus] } })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('正在加载地图')
    expect(wrapper.text()).not.toContain('底图')
    expect(wrapper.get('.map-waypoint').text()).toContain('西湖')
    wrapper.unmount()
  })

  it('offers both AMap and Baidu for a stop, each with its own coordinate order', () => {
    // Both providers are reachable from this network; AMap draws foreign labels in the local
    // language and Baidu labels more of them in Chinese, so the traveller picks. The numbers
    // are in a different order for each, which is exactly the mistake worth locking down:
    // AMap wants longitude,latitude and Baidu wants latitude,longitude.
    const wrapper = mount(TripMap, { props: { items: [item], selectedId: item.id, destination: '杭州' }, global: { plugins: [ElementPlus] } })
    const links = wrapper.findAll('.map-selection a')
    expect(links).toHaveLength(2)
    const amap = links[0].attributes('href') ?? ''
    const baidu = links[1].attributes('href') ?? ''
    expect(amap).toContain('uri.amap.com/marker?position=120.14,30.25')
    expect(amap).toContain('coordinate=wgs84')
    expect(baidu).toContain('api.map.baidu.com/marker?location=30.25,120.14')
    expect(baidu).toContain('coord_type=wgs84')
    // output=html is what opens the web page rather than a deep link into the app.
    expect(baidu).toContain('output=html')
    // Both carry our own Chinese name for the stop, not a translated one.
    expect(amap).toContain('name=%E8%A5%BF%E6%B9%96')
    expect(baidu).toContain('title=%E8%A5%BF%E6%B9%96')
    expect(links[0].text()).toContain('高德')
    expect(links[1].text()).toContain('百度')
    expect(wrapper.html()).not.toContain('openstreetmap')
    wrapper.unmount()
  })
})
