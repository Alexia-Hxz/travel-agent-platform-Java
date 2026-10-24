import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { usePlanningHandoff } from '@/composables/planningHandoff'
import type { SelectedRecommendation } from '@/types/agent'
import TripOverviewSummary from './TripOverviewSummary.vue'

const trip = {
  id: 7,
  title: '上海国庆游',
  destination: '上海',
  start_date: '2026-10-01',
  end_date: '2026-10-03',
  budget: 3200,
  adult_male_count: 0,
  adult_female_count: 0,
  child_male_count: 0,
  child_female_count: 0,
  created_at: '2026-09-05T10:00:00',
}

const places = [
  {
    id: 12,
    trip_id: 7,
    name: '上海迪士尼乐园',
    type: 'attraction' as const,
    address: '浦东新区川沙新镇',
    latitude: null,
    longitude: null,
    image_url: null,
    rating: 4.8,
    price_level: 4,
    opening_hours: '08:30-21:00',
    source_url: null,
  },
]

const itineraryItems = [
  {
    id: 20,
    trip_id: 7,
    date: '2026-10-01',
    start_time: '09:00:00',
    end_time: '11:00:00',
    place_id: 12,
    note: '第一站',
    sort_order: 0,
    place: places[0],
  },
]

function planResponse() {
  return {
    trip_id: 7,
    summary: '围绕迪士尼安排轻松的一天。',
    final_summary: '10月1日 09:00 入园，20:00 在园内用餐后结束游玩。',
    recommendations: [],
    transport_advice: null,
    daily_plan: [{ date: '2026-10-01', items: [{ place_name: '上海迪士尼乐园', start_time: '09:00:00', end_time: '20:00:00', note: '根据开放时间游玩' }] }],
    budget: { total_estimate: 2800, trip_budget: 3200, remaining_budget: 400, currency: 'CNY', note: '预算充足。' },
    conflicts: [],
    sources: [],
    events: [],
    handoff: {
      selected_recommendations: [] as SelectedRecommendation[],
      selected_total: 0,
      has_adjustment_summary: false,
      saved_place_count: 1,
      saved_place_names: ['上海迪士尼乐园'],
    },
  }
}

describe('TripOverviewSummary', () => {
  afterEach(() => vi.restoreAllMocks())

  beforeEach(() => {
    window.sessionStorage.clear()
    usePlanningHandoff(7).clear()
  })

  it('shows trip context including destination, dates, budget, places and itinerary', () => {
    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places, itineraryItems },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('行程介绍与总结')
    expect(wrapper.text()).toContain('上海')
    expect(wrapper.text()).toContain('2026-10-01 至 2026-10-03')
    expect(wrapper.text()).toContain('3200')
    expect(wrapper.text()).toContain('上海迪士尼乐园')
    expect(wrapper.text()).toContain('收藏地点 1 个')
    expect(wrapper.text()).toContain('每日行程 1 项')
  })

  it('generates a summary with the planning agent', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(new Response(JSON.stringify(planResponse()), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places, itineraryItems },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('button[aria-label="生成行程介绍与总结"]').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      'http://127.0.0.1:8001/api/v1/trips/7/plan-jobs',
      expect.objectContaining({ method: 'POST' }),
    )
    expect(wrapper.text()).toContain('10月1日 09:00 入园')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows what the planning agent and the adjustment produced', async () => {
    const handoff = usePlanningHandoff(7)
    handoff.setSelectedRecommendations([
      { name: '金陵饭店', category: 'hotel', estimated_cost: 1800, reason: '位置便利' },
      { name: '夫子庙', category: 'attraction', estimated_cost: 200, reason: '适合夜游' },
    ])
    handoff.setAdjustmentSummary('第二天改为上午夫子庙、下午休息。')

    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places, itineraryItems },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('将纳入总结的资料')
    expect(wrapper.text()).toContain('金陵饭店')
    expect(wrapper.text()).toContain('夫子庙')
    expect(wrapper.text()).toContain('1800')
    expect(wrapper.text()).toContain('第二天改为上午夫子庙、下午休息。')
  })

  it('sends the handed-over material with the summary request', async () => {
    const handoff = usePlanningHandoff(7)
    handoff.setSelectedRecommendations([{ name: '金陵饭店', category: 'hotel', estimated_cost: 1800, reason: '位置便利' }])
    handoff.setAdjustmentSummary('第二天改为上午夫子庙。')

    const fetchMock = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(planResponse()), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places, itineraryItems },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('button[aria-label="生成行程介绍与总结"]').trigger('click')
    await flushPromises()

    const body = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(body.selected_recommendations).toEqual([
      { name: '金陵饭店', category: 'hotel', estimated_cost: 1800, reason: '位置便利' },
    ])
    expect(body.adjustment_summary).toBe('第二天改为上午夫子庙。')
  })

  it('lists every saved place with its detail so the user can see what is included', async () => {
    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places, itineraryItems },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('你收藏的 1 个地点（全部纳入总结）')
    expect(wrapper.text()).toContain('上海迪士尼乐园')
    // Type, address and opening hours all travel to the summary, so they are shown.
    expect(wrapper.text()).toContain('景点')
    expect(wrapper.text()).toContain('浦东新区川沙新镇')
    expect(wrapper.text()).toContain('08:30-21:00')
  })

  it('confirms which material the backend actually folded into the summary', async () => {
    const handoff = usePlanningHandoff(7)
    handoff.setSelectedRecommendations([{ name: '金陵饭店', category: 'hotel', estimated_cost: 1800, reason: '位置便利' }])
    handoff.setAdjustmentSummary('第二天改为上午夫子庙。')

    const response = planResponse()
    response.handoff = {
      selected_recommendations: [{ name: '金陵饭店', category: 'hotel', estimated_cost: 1800, reason: '位置便利' }],
      selected_total: 1800,
      has_adjustment_summary: true,
      saved_place_count: 1,
      saved_place_names: ['上海迪士尼乐园'],
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(response), { status: 200 })))

    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places, itineraryItems },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('button[aria-label="生成行程介绍与总结"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已确认纳入：收藏地点 1 个')
    expect(wrapper.text()).toContain('已选推荐 1 个')
    expect(wrapper.text()).toContain('对话式调整结论')
  })

  it('warns that the summary is out of date once the collection changes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(planResponse()), { status: 200 })))

    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places, itineraryItems },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('button[aria-label="生成行程介绍与总结"]').trigger('click')
    await flushPromises()

    // Fresh summary: no warning.
    expect(wrapper.text()).not.toContain('可能已经不一致')

    // A new saved place arrives after the summary was generated.
    await wrapper.setProps({
      places: [
        ...places,
        {
          id: 13,
          trip_id: 7,
          name: '外滩',
          type: 'attraction' as const,
          address: '黄浦区中山东一路',
          latitude: null,
          longitude: null,
          image_url: null,
          rating: 4.6,
          price_level: 1,
          opening_hours: null,
          source_url: null,
        },
      ],
    })
    await flushPromises()

    expect(wrapper.text()).toContain('可能已经不一致')
    expect(wrapper.text()).toContain('新增 1 个收藏地点')
    expect(wrapper.find('button[aria-label="Regenerate the summary"]').exists()).toBe(true)
  })

  it('marks the summary stale when the itinerary changes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(planResponse()), { status: 200 })))

    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places, itineraryItems },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('button[aria-label="生成行程介绍与总结"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('可能已经不一致')

    // The auto-schedule feature added an item: the summary no longer matches.
    await wrapper.setProps({
      itineraryItems: [
        ...itineraryItems,
        {
          id: 21,
          trip_id: 7,
          date: '2026-10-02',
          start_time: '09:00:00',
          end_time: '11:00:00',
          place_id: 12,
          note: '自动安排',
          sort_order: 1,
          place: places[0],
        },
      ],
    })
    await flushPromises()

    expect(wrapper.text()).toContain('新增 1 个行程项')
  })

  it('tells the user when there is nothing handed over yet', () => {
    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places: [], itineraryItems: [] },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('还没有可纳入的资料')
  })

  it('shows a category placeholder rather than an empty box when a saved place has no picture', () => {
    // The saved attraction in the real database has no image_url at all. The page used to
    // render a dashed empty box for it, which reads as a broken card.
    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places, itineraryItems },
      global: { plugins: [ElementPlus] },
    })

    const image = wrapper.get('img.collection-thumb')
    expect(image.attributes('src')).toContain('/static/place_images/attraction.svg')
    expect(wrapper.find('.collection-thumb-empty').exists()).toBe(false)
  })

  it('swaps a dead picture for the placeholder instead of showing a broken icon', async () => {
    // The seeded hotel carries https://example.com/hotel_image.jpg, which answers HTTP 404.
    const deadHotel = {
      id: 99,
      trip_id: 7,
      name: '初心启程酒店',
      type: 'hotel' as const,
      address: '上海国际旅游度假区',
      latitude: null,
      longitude: null,
      image_url: 'https://example.com/hotel_image.jpg',
      rating: 4.2,
      price_level: 3,
      opening_hours: null,
      source_url: null,
    }
    const wrapper = mount(TripOverviewSummary, {
      props: { trip, places: [deadHotel], itineraryItems: [] },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.get('img.collection-thumb').attributes('src')).toBe('https://example.com/hotel_image.jpg')

    await wrapper.get('img.collection-thumb').trigger('error')

    expect(wrapper.get('img.collection-thumb').attributes('src')).toContain('/static/place_images/hotel.svg')
  })
})
