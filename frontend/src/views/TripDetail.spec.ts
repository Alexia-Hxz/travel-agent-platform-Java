import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { usePlanningHandoff } from '@/composables/planningHandoff'
import { forgetPanelPreferences } from '@/composables/panelState'
import TripDetail from './TripDetail.vue'

// JSDOM has no layout for Popper; stub only the drawer rather than Teleport,
// so real dropdowns keep stable trigger refs. Match the drawer's lazy/persistent content.
const DrawerStub = defineComponent({
  props: { modelValue: Boolean },
  setup(props) {
    const mounted = ref(props.modelValue)
    watch(() => props.modelValue, value => { if (value) mounted.value = true })
    return { mounted }
  },
  template: '<section v-if="mounted" v-show="modelValue"><slot /></section>',
})

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

const savedPlace = {
  id: 12,
  trip_id: 7,
  name: '上海博物馆',
  type: 'attraction',
  address: '人民大道201号',
  latitude: null,
  longitude: null,
  image_url: null,
  rating: 4.8,
  price_level: 2,
  opening_hours: '09:00-17:00',
  source_url: null,
}

const secondPlace = {
  ...savedPlace,
  id: 13,
  name: '外滩',
  address: '中山东一路',
}

const scheduledItem = {
  id: 21,
  trip_id: 7,
  date: '2026-10-02',
  start_time: '09:00:00',
  end_time: '10:00:00',
  place_id: 12,
  note: '第一站',
  sort_order: 0,
  place: { id: 12, name: '上海博物馆', type: 'attraction', address: '人民大道201号' },
}

/**
 * The page loads places, itinerary items and files at once, so responses
 * are routed by URL and method. Sequential `mockResolvedValueOnce` cannot be used:
 * the order is not guaranteed, and a single Response body can only be read once.
 */
function routedFetch(routes: Array<[string, string, unknown]>): ReturnType<typeof vi.fn> {
  return vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = (init?.method ?? 'GET').toUpperCase()
    for (const [fragment, expectedMethod, payload] of routes) {
      if (url.includes(fragment) && method === expectedMethod) {
        const body = typeof payload === 'function' ? (payload as (init?: RequestInit) => unknown)(init) : payload
        return Promise.resolve(
          new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }),
        )
      }
    }
    return Promise.resolve(
      new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    )
  })
}

describe('TripDetail', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  beforeEach(() => {
    window.sessionStorage.clear()
    // Panel fold choices are remembered, so one test must not start with another's layout.
    forgetPanelPreferences()
    usePlanningHandoff(7).clear()
  })

  it('shows saved places and the daily itinerary returned by the API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn()
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify([
              {
                id: 12,
                trip_id: 7,
                name: '上海博物馆',
                type: 'attraction',
                address: '人民大道201号',
                latitude: 31.2304,
                longitude: 121.4737,
                image_url: null,
                rating: 4.8,
                price_level: 2,
                opening_hours: '09:00-17:00',
                source_url: null,
              },
            ]),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        )
        .mockResolvedValueOnce(
          new Response(
            JSON.stringify([
              {
                id: 20,
                trip_id: 7,
                date: '2026-10-01',
                start_time: '09:00:00',
                end_time: '11:00:00',
                place_id: 12,
                note: '第一站',
                sort_order: 0,
                place: { id: 12, name: '上海博物馆', type: 'attraction', address: '人民大道201号' },
              },
            ]),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
    )

    const wrapper = mount(TripDetail, {
      props: { trip },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('上海博物馆')
    expect(wrapper.text()).toContain('第一站')
    expect(wrapper.text()).toContain('09:00')
    await wrapper.vm.openSection('summary')
    await flushPromises()
    expect(wrapper.text()).toContain('行程介绍与总结')
  })

  it('survives ticking a recommended item on the real page', async () => {
    // The reported bug, on the exact path the user took: this page reads the shared handoff
    // store for its step bar, and the planning agent inside it writes that store when a card
    // is ticked. One tick used to send the two into a render loop that ended with the browser
    // reporting "Out of Memory"; this spec never ticked a card, which is why it stayed green.
    vi.stubGlobal(
      'fetch',
      routedFetch([
        ['/places', 'GET', []],
        ['/itinerary', 'GET', []],
        [
          '/plan',
          'POST',
          {
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '外滩',
                category: 'attraction',
                reason: '夜景',
                estimated_cost: 0,
                image_url: 'https://images.example.com/bund.jpg',
                fallback_image_url: '/static/place_images/attraction.svg',
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          },
        ],
      ]),
    )

    const wrapper = mount(TripDetail, {
      props: { trip },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } },
    })
    await flushPromises()

    await wrapper.get('.recommendation-link').trigger('click')
    await flushPromises()
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    await wrapper.get('input[aria-label="Select 外滩"]').setValue(true)
    await flushPromises()

    // The tick is recorded and the page is still standing.
    expect(usePlanningHandoff(7).handoff.value.selectedRecommendations.map((item) => item.name)).toEqual(['外滩'])
    expect(wrapper.text()).toContain('外滩')
  })

  it('clears every saved place in one press so planning can start over', async () => {
    // 清空收藏 exists because a second planning round kept coming back looking like the
    // first: the saved places are what the planner reads. Clearing them one by one is not
    // the same thing -- a failure halfway leaves a half-cleared list behind.
    const fetchMock = routedFetch([
      ['/places', 'DELETE', { removed: 2 }],
      ['/places', 'GET', [savedPlace, secondPlace]],
      ['/itinerary', 'GET', [scheduledItem]],
    ])
    vi.stubGlobal('fetch', fetchMock)
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)

    const wrapper = mount(TripDetail, {
      props: { trip },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('上海博物馆')
    expect(wrapper.text()).toContain('外滩')

    await wrapper.vm.openSection('places')
    await flushPromises()
    await wrapper.get('button[aria-label="清空收藏地点"]').trigger('click')
    await flushPromises()

    // The dialog says what else goes with the places before anything is deleted.
    expect(confirm).toHaveBeenCalledWith(
      expect.stringContaining('其中 1 个行程项也会一并删除'),
      '清空全部收藏地点？',
      expect.anything(),
    )
    const methods = fetchMock.mock.calls.map(([, init]) => ((init as RequestInit | undefined)?.method ?? 'GET').toUpperCase())
    expect(methods).toContain('DELETE')
    expect(wrapper.text()).not.toContain('上海博物馆')
    expect(wrapper.text()).toContain('已清空 2 个收藏地点')
  })

  it('keeps the saved places when the clear is cancelled', async () => {
    const fetchMock = routedFetch([
      ['/places', 'DELETE', { removed: 2 }],
      ['/places', 'GET', [savedPlace]],
    ])
    vi.stubGlobal('fetch', fetchMock)
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue(new Error('cancel'))

    const wrapper = mount(TripDetail, {
      props: { trip },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } },
    })
    await flushPromises()

    await wrapper.vm.openSection('places')
    await flushPromises()
    await wrapper.get('button[aria-label="清空收藏地点"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    const methods = fetchMock.mock.calls.map(([, init]) => ((init as RequestInit | undefined)?.method ?? 'GET').toUpperCase())
    expect(methods).not.toContain('DELETE')
    expect(wrapper.text()).toContain('上海博物馆')
  })

  it('offers the clear button only when something is saved', async () => {
    vi.stubGlobal('fetch', routedFetch([['/places', 'GET', []]]))

    const wrapper = mount(TripDetail, {
      props: { trip },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } },
    })
    await flushPromises()

    // A disabled button still has to exist, so the user can see why it does nothing yet.
    await wrapper.vm.openSection('places')
    await flushPromises()
    const button = wrapper.get('button[aria-label="清空收藏地点"]')
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('opens auxiliary workflows on demand while keeping the timeline mounted', async () => {
    const fetchMock = routedFetch([['/places', 'GET', [savedPlace]], ['/itinerary', 'GET', [scheduledItem]], ['/files', 'GET', []]])
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(TripDetail, { props: { trip }, global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } } })
    await flushPromises()
    expect(wrapper.find('.saved-places-view').exists()).toBe(false)
    await wrapper.vm.openSection('places')
    await flushPromises()
    expect(wrapper.get('.saved-places-view').isVisible()).toBe(true)
    expect(wrapper.find('.timeline-pane').exists()).toBe(true)
    await wrapper.vm.openSection('planning')
    await flushPromises()
    expect(wrapper.get('.saved-places-view').attributes('style')).toContain('display: none')
    expect(wrapper.get('.planning-section').attributes('style') ?? '').not.toContain('display: none')
    // Switching workspace tools does not reload the trip or run a model.
    expect(fetchMock.mock.calls.filter(([,init]) => (init?.method ?? 'GET') !== 'GET')).toHaveLength(0)
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/places'))).toHaveLength(1)
    wrapper.unmount()
  })

  it('keeps long collections in the drawer and shows only the selected day', async () => {
    const manyPlaces = Array.from({ length: 8 }, (_, i) => ({ ...savedPlace, id: 100 + i, name: `地点 ${i + 1}` }))
    const manyItems = manyPlaces.map((place, i) => ({ ...scheduledItem, id: 200 + i, place_id: place.id, place }))
    const fetchMock = routedFetch([['/places', 'GET', manyPlaces], ['/itinerary', 'GET', manyItems]])
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(TripDetail, { props: { trip }, global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } } })
    await flushPromises()
    expect(wrapper.findAll('.itinerary-row')).toHaveLength(0)
    await wrapper.get('#day-2026-10-02').trigger('click')
    expect(wrapper.findAll('.itinerary-row')).toHaveLength(8)
    expect(wrapper.find('.saved-places-view').exists()).toBe(false)
    const requests = fetchMock.mock.calls.length
    await wrapper.vm.openSection('places')
    await flushPromises()
    expect(wrapper.findAll('.saved-place-row')).toHaveLength(8)
    expect(wrapper.get('button[aria-label="清空收藏地点"]').isVisible()).toBe(true)
    expect(fetchMock.mock.calls.length).toBe(requests)
    wrapper.unmount()
  })

  it('shows a short daily itinerary directly and keeps saved-place previews accessible', async () => {
    vi.stubGlobal('fetch', routedFetch([['/places', 'GET', [savedPlace]], ['/itinerary', 'GET', [scheduledItem]]]))
    const wrapper = mount(TripDetail, { props: { trip }, global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } } })
    await flushPromises()
    await wrapper.get('#day-2026-10-02').trigger('click')
    expect(wrapper.findAll('.itinerary-row')).toHaveLength(1)
    expect(wrapper.get('.itinerary-row').text()).toContain('上海博物馆')
    expect(wrapper.get('.utility-places').text()).toContain('上海博物馆')
    wrapper.unmount()
  })

  it('guides an empty trip toward discovery before scheduling or adjusting', async () => {
    vi.stubGlobal('fetch', routedFetch([]))
    const wrapper = mount(TripDetail, { props: { trip }, global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } } })
    await flushPromises()
    expect(wrapper.text()).toContain('让 AI 帮我规划')
    expect(wrapper.text()).toContain('调整会先生成草案')
    expect(wrapper.get('button[aria-label="Auto-schedule my saved places"]').attributes('disabled')).toBeDefined()
    await wrapper.get('.timeline-empty .el-button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.planning-form').isVisible()).toBe(true)
    wrapper.unmount()
  })

  it('reflects saved content and preserves handoff progress in the new workspace', async () => {
    const handoff = usePlanningHandoff(7)
    handoff.markPlanGenerated()
    handoff.markSummaryGenerated()
    vi.stubGlobal('fetch', routedFetch([['/places', 'GET', [savedPlace]]]))
    const wrapper = mount(TripDetail, { props: { trip }, global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } } })
    await flushPromises()
    expect(wrapper.get('.utility-places').text()).toContain('上海博物馆')
    expect(wrapper.get('.utility-label small').text()).toBe('1')
    await wrapper.vm.openSection('summary')
    await flushPromises()
    expect(wrapper.get('.overview-section').text()).toContain('收藏地点 1 个')
    expect(handoff.handoff.value.planGenerated).toBe(true)
    expect(handoff.handoff.value.summaryGenerated).toBe(true)
    wrapper.unmount()
  })

  it('previews the suggested schedule and only writes it after confirmation', async () => {
    const fetchMock = routedFetch([
      [
        '/itinerary-items/auto-schedule',
        'POST',
        {
          trip_id: 7,
          proposed: [
            {
              place_id: 12,
              place_name: '上海博物馆',
              date: '2026-10-01',
              start_time: '09:00:00',
              end_time: '11:00:00',
              note: '按开放时间 09:00-17:00 自动安排',
            },
          ],
          skipped: [],
          already_scheduled: [],
          message: '已为 1 个收藏地点找到合适的时段。',
        },
      ],
      [
        '/itinerary-items',
        'POST',
        {
          id: 30,
          trip_id: 7,
          date: '2026-10-01',
          start_time: '09:00:00',
          end_time: '11:00:00',
          place_id: 12,
          note: '按开放时间 09:00-17:00 自动安排',
          sort_order: 0,
          place: savedPlace,
        },
      ],
      ['/places', 'GET', [savedPlace]],
    ])
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(TripDetail, {
      props: { trip },
      global: { plugins: [ElementPlus], stubs: { ElDrawer: DrawerStub } },
    })
    await flushPromises()

    await wrapper.get('button[aria-label="Auto-schedule my saved places"]').trigger('click')
    await flushPromises()

    // The proposal is shown, and nothing has been created yet.
    expect(wrapper.text()).toContain('建议的行程安排')
    expect(wrapper.text()).toContain('按开放时间 09:00-17:00 自动安排')
    const createdBeforeConfirm = fetchMock.mock.calls.filter(
      (call) =>
        String(call[0]).includes('/itinerary-items') &&
        !String(call[0]).includes('auto-schedule') &&
        call[1]?.method === 'POST',
    )
    expect(createdBeforeConfirm).toHaveLength(0)

    await wrapper.get('button[aria-label="Confirm the suggested schedule"]').trigger('click')
    await flushPromises()

    const created = fetchMock.mock.calls.filter(
      (call) =>
        String(call[0]).includes('/itinerary-items') &&
        !String(call[0]).includes('auto-schedule') &&
        call[1]?.method === 'POST',
    )
    expect(created).toHaveLength(1)
    expect(JSON.parse(created[0][1].body)).toMatchObject({
      date: '2026-10-01',
      start_time: '09:00:00',
      end_time: '11:00:00',
      place_id: 12,
    })
    expect(wrapper.text()).toContain('已把 1 个收藏地点排进行程')
  })
})
