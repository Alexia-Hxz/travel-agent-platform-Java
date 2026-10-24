import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { defineComponent, h, nextTick, ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { usePlanningHandoff } from '@/composables/planningHandoff'
import TripPlanningAgent from './TripPlanningAgent.vue'

describe('TripPlanningAgent', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    usePlanningHandoff(7).clear()
  })

  /**
   * Make one card give up on its own photo.
   *
   * One failure is deliberately no longer enough. A real plan asks the browser for ~34
   * pictures at once, so a single timeout is as likely to mean "the host is slow right now"
   * as "this link is dead"; the card now retries once (with a cache-busting suffix) before
   * retiring the photo and falling back to the bundled placeholder. Two failures is what
   * "the photo is really gone" looks like.
   */
  async function killPhoto(wrapper: ReturnType<typeof mount>, alt: string): Promise<void> {
    await wrapper.find(`img[alt="${alt}"]`).trigger('error')
    await wrapper.find(`img[alt="${alt}"]`).trigger('error')
  }

  it('names the live search honestly instead of the provider behind it', async () => {
    /* The checkbox used to read "使用 Tavily 联网搜索" while the search came from the model's
     * own live search, and the metered provider it named has since been removed entirely.
     * A control must not name a service the request never reaches. */
    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('联网搜索最新资料')
    expect(wrapper.text()).not.toContain('Tavily')
  })

  it('renders final planning results without exposing raw source details', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: '这是一份综合后的最终旅行建议。',
            recommendations: [],
            transport_advice: {
              origin: '初心启程酒店（浦东新区界龙大道266号）',
              destination: '迪士尼',
              recommendation: '优先乘坐公共交通。',
            },
            daily_plan: [
              {
                date: '2026-10-01',
                items: [{ place_name: 'Agent Museum', start_time: '09:00:00', end_time: '11:00:00', note: 'Morning visit', image_url: 'https://images.example.com/museum.jpg' }],
              },
            ],
            budget: { total_estimate: 600, trip_budget: 2000, remaining_budget: 1400, currency: 'CNY', note: 'Estimate only', breakdown: [{ category: 'hotel', name: 'Hotel', amount: 600 }], daily_estimate: 300, itemized_summary: 'Hotel（600元） = 600元' },
            conflicts: [{ date: '2026-10-01', first_place: 'Museum', second_place: 'Tower', message: 'Times overlap' }],
            sources: [{ source_type: 'tavily', title: 'Tavily guide', url: 'https://example.com/museum', snippet: 'Central Road' }],
            events: [{ tool: 'conflict_checker', status: 'completed', message: 'No conflicts' }],
            weather: { days: [{ date: '2026-10-01', condition: '适合户外活动', indoor_priority: false }] },
            budget_summary: '住宿约 600 CNY',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('textarea[aria-label="Planning request"]').setValue('Plan a relaxed day')
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Agent Museum')
    expect(wrapper.text()).toContain('600 CNY')
    expect(wrapper.text()).toContain('Hotel（600元） = 600元')
    expect(wrapper.text()).toContain('预算简表')
    expect(wrapper.text()).toContain('Times overlap')
    // The raw "Agent 执行记录 / Agent activity" trace was removed: it exposed internal
    // tool names to the traveller and told them nothing about their trip.
    expect(wrapper.text()).not.toContain('conflict_checker')
    expect(wrapper.text()).not.toContain('Agent 执行记录')
    // The suggested-days list is text only: photos belong to the recommendation
    // cards, so a daily-plan item contributes a time, a name and a short note.
    expect(wrapper.find('img[alt="Agent Museum"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Morning visit')
    expect(wrapper.text()).toContain('09:00 - 11:00')
    expect(wrapper.text()).toContain('适合户外活动')
    expect(wrapper.text()).toContain('初心启程酒店')
    expect(wrapper.text()).toContain('优先乘坐公共交通。')
    expect(wrapper.text()).toContain('最终旅行总结')
    expect(wrapper.text()).toContain('这是一份综合后的最终旅行建议。')
    expect(wrapper.text()).not.toContain('Tavily guide')
    expect(wrapper.find('a[aria-label="Open planning source"]').exists()).toBe(false)
  })

  it('keeps a good photo that is still below the fold instead of retiring it', async () => {
    // The measured regression: these images are `loading="lazy"`, so Chrome has not
    // started fetching a card that is below the viewport. The 12s timer used to count
    // that as a failure and permanently swap in the placeholder — 8 of 11 real cards
    // lost a working photo this way. A never-fetched off-screen image is not a failure.
    vi.useFakeTimers()
    try {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              trip_id: 7,
              summary: 'Plan ready',
              final_summary: 'Summary',
              recommendations: [],
              place_recommendations: [
                {
                  name: '金陵饭店',
                  category: 'hotel',
                  reason: '位置便利',
                  estimated_cost: 1600,
                  image_url: 'https://cache.marriott.com/hotel.jpg',
                  fallback_image_url: '/static/place_images/hotel.svg',
                },
              ],
              transport_advice: null,
              daily_plan: [],
              budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '', breakdown: [] },
              conflicts: [],
              sources: [],
              events: [],
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      )

      const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
      await wrapper.get('form.planning-form').trigger('submit')
      await flushPromises()

      // Put the card far below a 905px viewport, as it is in a long plan.
      const element = wrapper.get('img[alt="金陵饭店"]').element as HTMLImageElement
      element.getBoundingClientRect = () =>
        ({ top: 1906, bottom: 2106, left: 0, right: 300, width: 300, height: 200, x: 0, y: 1906, toJSON: () => ({}) }) as DOMRect

      vi.advanceTimersByTime(13000)
      await flushPromises()

      expect(wrapper.get('img[alt="金陵饭店"]').attributes('src')).toBe('https://cache.marriott.com/hotel.jpg')
    } finally {
      vi.useRealTimers()
    }
  })

  it('still replaces a photo that fails above the fold', async () => {
    vi.useFakeTimers()
    try {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              trip_id: 7,
              summary: 'Plan ready',
              final_summary: 'Summary',
              recommendations: [],
              place_recommendations: [
                {
                  name: '新荣记',
                  category: 'restaurant',
                  reason: '本地菜',
                  estimated_cost: 160,
                  image_url: 'https://cdn.example.com/hang.jpg',
                  fallback_image_url: '/static/place_images/restaurant.svg',
                },
              ],
              transport_advice: null,
              daily_plan: [],
              budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '', breakdown: [] },
              conflicts: [],
              sources: [],
              events: [],
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      )

      const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
      await wrapper.get('form.planning-form').trigger('submit')
      await flushPromises()

      // Visible but never loads: the first timeout buys the photo one retry, and only a
      // second one hands the card over to the placeholder.
      const element = wrapper.get('img[alt="新荣记"]').element as HTMLImageElement
      element.getBoundingClientRect = () =>
        ({ top: 120, bottom: 320, left: 0, right: 300, width: 300, height: 200, x: 0, y: 120, toJSON: () => ({}) }) as DOMRect

      vi.advanceTimersByTime(13000)
      await flushPromises()
      expect(wrapper.get('img[alt="新荣记"]').attributes('src')).toBe(
        'https://cdn.example.com/hang.jpg?_retry=1',
      )

      vi.advanceTimersByTime(13000)
      await flushPromises()

      expect(wrapper.get('img[alt="新荣记"]').attributes('src')).toBe(
        'http://127.0.0.1:8001/static/place_images/restaurant.svg',
      )
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops re-checking a below-the-fold photo after a bounded number of deferrals', async () => {
    vi.useFakeTimers()
    try {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              trip_id: 7,
              summary: 'Plan ready',
              final_summary: 'Summary',
              recommendations: [],
              place_recommendations: [
                {
                  name: '金陵饭店',
                  category: 'hotel',
                  reason: '位置便利',
                  estimated_cost: 1600,
                  image_url: 'https://cache.marriott.com/hotel.jpg',
                  fallback_image_url: '/static/place_images/hotel.svg',
                },
              ],
              transport_advice: null,
              daily_plan: [],
              budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '', breakdown: [] },
              conflicts: [],
              sources: [],
              events: [],
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      )

      const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
      await wrapper.get('form.planning-form').trigger('submit')
      await flushPromises()

      const element = wrapper.get('img[alt="金陵饭店"]').element as HTMLImageElement
      element.getBoundingClientRect = () =>
        ({ top: 9000, bottom: 9200, left: 0, right: 300, width: 300, height: 200, x: 0, y: 9000, toJSON: () => ({}) }) as DOMRect

      // 26 windows x 12s is past the deferral bound, so it finally gives up on the first
      // attempt and retries; the bound is paid once more before the placeholder appears.
      vi.advanceTimersByTime(12000 * 26)
      await flushPromises()
      expect(wrapper.get('img[alt="金陵饭店"]').attributes('src')).toBe(
        'https://cache.marriott.com/hotel.jpg?_retry=1',
      )

      vi.advanceTimersByTime(12000 * 26)
      await flushPromises()

      expect(wrapper.get('img[alt="金陵饭店"]').attributes('src')).toBe(
        'http://127.0.0.1:8001/static/place_images/hotel.svg',
      )
    } finally {
      vi.useRealTimers()
    }
  })

  it('replaces a photo that never loads with the placeholder after a timeout', async () => {
    vi.useFakeTimers()
    try {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              trip_id: 7,
              summary: 'Plan ready',
              final_summary: 'Summary',
              recommendations: [],
              place_recommendations: [
                {
                  name: '新荣记',
                  category: 'restaurant',
                  reason: '本地菜',
                  estimated_cost: 160,
                  // A CDN that hangs: no load, no error event ever fires.
                  image_url: 'https://dynamic-media-cdn.tripadvisor.com/hang.jpg',
                  fallback_image_url: '/static/place_images/restaurant.svg',
                },
              ],
              transport_advice: null,
              daily_plan: [],
              budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '', breakdown: [] },
              conflicts: [],
              sources: [],
              events: [],
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      )

      const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
      await wrapper.get('form.planning-form').trigger('submit')
      await flushPromises()

      const image = () => wrapper.get('img[alt="新荣记"]').attributes('src')
      expect(image()).toBe('https://dynamic-media-cdn.tripadvisor.com/hang.jpg')

      // Nothing loaded within the window, so the photo is retried once, and only a second
      // empty window lets the bundled placeholder take over.
      vi.advanceTimersByTime(13000)
      await flushPromises()
      expect(image()).toBe('https://dynamic-media-cdn.tripadvisor.com/hang.jpg?_retry=1')

      vi.advanceTimersByTime(13000)
      await flushPromises()
      expect(image()).toBe('http://127.0.0.1:8001/static/place_images/restaurant.svg')
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps a photo that loaded before the timeout', async () => {
    vi.useFakeTimers()
    try {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              trip_id: 7,
              summary: 'Plan ready',
              final_summary: 'Summary',
              recommendations: [],
              place_recommendations: [
                {
                  name: '外滩',
                  category: 'attraction',
                  reason: '江景',
                  estimated_cost: 0,
                  image_url: 'https://images.example.com/bund.jpg',
                  fallback_image_url: '/static/place_images/attraction.svg',
                },
              ],
              transport_advice: null,
              daily_plan: [
                {
                  date: '2026-10-01',
                  items: [
                    {
                      place_name: '外滩',
                      start_time: '09:00:00',
                      end_time: '11:00:00',
                      note: 'Morning',
                      image_url: 'https://images.example.com/bund.jpg',
                      fallback_image_url: '/static/place_images/attraction.svg',
                    },
                  ],
                },
              ],
              budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '', breakdown: [] },
              conflicts: [],
              sources: [],
              events: [],
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      )

      const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
      await wrapper.get('form.planning-form').trigger('submit')
      await flushPromises()

      // Both the recommendation card and the daily item report a successful load.
      await wrapper.get('img[alt="外滩"]').trigger('load')
      const dailyImage = wrapper.findAll('img[alt="外滩"]')
      for (const image of dailyImage) {
        await image.trigger('load')
      }

      vi.advanceTimersByTime(20000)
      await flushPromises()

      const sources = wrapper.findAll('img[alt="外滩"]').map((image) => image.attributes('src'))
      expect(sources.length).toBeGreaterThan(0)
      expect(sources.every((src) => src === 'https://images.example.com/bund.jpg')).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('reports a gateway timeout as a timeout, not as a stopped backend', async () => {
    // A slow generation cut off by the proxy is not a stopped service.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 504 })))

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('生成规划用时过长')
    expect(wrapper.text()).not.toContain('请确认后端服务已启动')
  })

  it('reports an unreachable backend when the request never leaves the browser', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('无法连接后端服务')
  })

  it('shows the status code when the backend rejects the request', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 500 })))

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('HTTP 500')
  })

  it('shows the weather explanation when the forecast is unavailable', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '', breakdown: [] },
            conflicts: [],
            sources: [],
            events: [],
            weather: {
              status: 'unavailable',
              days: [],
              message: '出行日期（2026-10-01）距今天还有 20 天，超出天气服务能提供的未来 16 天预报范围。',
            },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('出行日期天气')
    expect(wrapper.text()).toContain('超出天气服务能提供的未来 16 天预报范围')
    expect(wrapper.text()).not.toContain('HTTPError')
  })

  it('emits a single plan item as an adjustment request', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            transport_advice: null,
            daily_plan: [
              {
                date: '2026-10-02',
                items: [{ place_name: 'Agent Museum', start_time: '14:00:00', end_time: '15:30:00', note: '重点参观' }],
              },
            ],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200 },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    await wrapper.get('button[aria-label="Adjust Agent Museum"] ').trigger('click')

    expect(wrapper.emitted('adjustment-requested')?.[0]?.[0]).toContain('2026-10-02 14:00-15:30')
    expect(wrapper.emitted('adjustment-requested')?.[0]?.[0]).toContain('Agent Museum')
  })

  it('shows only the final summary and hides AI recommendation details and selection controls', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [
              { source_type: 'tavily', title: '夜间烟花秀', url: 'https://example.com/fireworks', snippet: '20:00 开始，建议提前入场。' },
              { source_type: 'local', title: '园区特色饮品', url: null, snippet: '可在主题餐厅购买。' },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [{ source_type: 'tavily', title: 'Hidden source', url: 'https://example.com', snippet: 'Hidden' }],
            events: [],
          }),
          { status: 200 },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('最终旅行总结')
    expect(wrapper.text()).toContain('Summary')
    expect(wrapper.text()).not.toContain('AI 推荐项目')
    expect(wrapper.text()).not.toContain('夜间烟花秀')
    expect(wrapper.text()).not.toContain('园区特色饮品')
    expect(wrapper.text()).not.toContain('Hidden source')
    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.find('input[aria-label^="Select AI recommendation"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="Adjust selected recommendations"]').exists()).toBe(false)
  })

  it('shows a clear weather fallback message when forecast data is unavailable', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({
          trip_id: 7,
          summary: 'Plan ready',
          final_summary: 'Summary',
          recommendations: [],
          transport_advice: null,
          daily_plan: [],
          budget: { total_estimate: 100, trip_budget: 8000, remaining_budget: 7900, currency: 'CNY', note: 'Estimate', breakdown: [], daily_estimate: 100 },
          conflicts: [],
          sources: [],
          events: [],
          weather: { status: 'fallback', message: '天气预报暂时不可用：远期日期超出预报范围', days: [] },
        }), { status: 200 }),
    )
    )
    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('天气预报暂时不可用')
  })

  it('shows every recommended place with its own picture grouped by category', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [
              { source_type: 'tavily', title: 'Hidden headline', url: 'https://example.com/hidden', snippet: 'Hidden' },
            ],
            place_recommendations: [
              { name: '金陵饭店', category: 'hotel', reason: '位置便利', price_hint: '约600元', unit_price: 600, price_unit: 'night', quantity: 1, quantity_unit: '晚', quantity_max: 4, cost_source: '按检索到的价格估算', estimated_cost: 600, cost_explanation: '约 600 元/晚 × 1 晚 = 600 元（按检索到的价格估算）', image_url: '/static/place_images/hotel.svg' },
              { name: '南京大牌檔', category: 'restaurant', reason: '本地菜', price_hint: '人均80元', unit_price: 80, price_unit: 'person_meal', quantity: 1, quantity_unit: '次', quantity_max: 5, cost_source: '按检索到的价格估算', estimated_cost: 160, cost_explanation: '约 80 元/人/餐 × 2 人 × 1 次 = 160 元（按检索到的价格估算）', image_url: 'https://images.example.com/food.jpg', meal_slot: 'dinner' },
              { name: '李记汤包店', category: 'restaurant', reason: '早餐老店', price_hint: '人均20元', unit_price: 20, price_unit: 'person_meal', quantity: 1, quantity_unit: '次', quantity_max: 5, cost_source: '按检索到的价格估算', estimated_cost: 40, cost_explanation: '约 20 元/人/餐 × 2 人 × 1 次 = 40 元（按检索到的价格估算）', image_url: 'https://images.example.com/bao.jpg', meal_slot: 'breakfast' },
              { name: '夫子庙', category: 'attraction', reason: '适合夜游', price_hint: null, unit_price: 100, price_unit: 'ticket', estimated_cost: 200, cost_explanation: '约 100 元/人 × 2 人 = 200 元（按中档默认价估算）', image_url: '/static/place_images/attraction.svg' },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
            traveler_profile: { total_people: 2 },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('推荐项目')
    expect(wrapper.text()).toContain('酒店 / Hotels')
    // Breakfast is listed apart from lunch and dinner, so a day of eating is obvious.
    expect(wrapper.text()).toContain('早餐 / Breakfast')
    expect(wrapper.text()).toContain('中晚餐 / Lunch & dinner')
    expect(wrapper.text()).toContain('景点 / Attractions')
    expect(wrapper.text()).toContain('李记汤包店')
    expect(wrapper.text()).toContain('金陵饭店')
    // A hotel is quoted for ONE night: a trip may move between hotels, and the card's
    // "住几晚" control is what turns it into several.
    expect(wrapper.text()).toContain('约 600 CNY')
    expect(wrapper.text()).toContain('约 600 元/晚 × 1 晚 = 600 元（按检索到的价格估算）')
    expect(wrapper.text()).not.toContain('Hidden headline')
    expect(wrapper.find('a').exists()).toBe(false)

    expect(wrapper.find('img[alt="金陵饭店"]').attributes('src')).toBe(
      'http://127.0.0.1:8001/static/place_images/hotel.svg',
    )
    expect(wrapper.find('img[alt="南京大牌檔"]').attributes('src')).toBe('https://images.example.com/food.jpg')
    expect(wrapper.find('img[alt="夫子庙"]').attributes('src')).toBe(
      'http://127.0.0.1:8001/static/place_images/attraction.svg',
    )

    await wrapper.find('img[alt="夫子庙"]').trigger('click')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
  })

  it('reveals photos when a newly generated recommendation list is longer than six places', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      trip_id: 7,
      summary: 'Plan ready',
      place_recommendations: Array.from({ length: 7 }, (_, index) => ({
        name: `景点${index + 1}`,
        category: 'attraction',
        image_url: '/static/place_images/cache/photo.jpg',
      })),
      daily_plan: [], budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
      conflicts: [], sources: [], events: [],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })

    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.recommendation-panel .panel-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.recommendation-panel .panel-body').attributes('style') ?? '').not.toContain('display: none')
    expect(wrapper.get('img[alt="景点1"]').attributes('src')).toBe('http://127.0.0.1:8001/static/place_images/cache/photo.jpg')
  })

  it('keeps an unrecognised restaurant in the lunch-and-dinner group', async () => {
    // A restaurant with no meal_slot (or "any") must not land in the breakfast group.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              { name: '某清真菜馆', category: 'restaurant', reason: '正餐', estimated_cost: 200, meal_slot: 'any' },
              { name: '未标注早餐店', category: 'restaurant', reason: '正餐', estimated_cost: 200 },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('中晚餐 / Lunch & dinner')
    expect(wrapper.text()).not.toContain('早餐 / Breakfast')
  })

  it('labels a recommendation that exceeds the budget without hiding it', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '南京豪华酒店',
                category: 'hotel',
                reason: '位置好',
                unit_price: 1200,
                price_unit: 'night',
                estimated_cost: 4800,
                cost_explanation: '约 1200 元/晚 × 4 晚 = 4800 元（按检索到的价格估算）',
                over_budget: true,
                budget_limit: 800,
                budget_note: '超出预算参考（住宿约 800 元/晚 以内更合适）',
              },
              {
                name: '合适酒店',
                category: 'hotel',
                reason: '性价比高',
                unit_price: 400,
                price_unit: 'night',
                estimated_cost: 1600,
                cost_explanation: '约 400 元/晚 × 4 晚 = 1600 元（按检索到的价格估算）',
                over_budget: false,
                budget_note: '',
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: 8000, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // Both cards are still offered — the over-budget one is labelled, never removed.
    expect(wrapper.text()).toContain('南京豪华酒店')
    expect(wrapper.text()).toContain('合适酒店')
    expect(wrapper.text()).toContain('超预算 / Over budget')
    expect(wrapper.text()).toContain('超出预算参考（住宿约 800 元/晚 以内更合适）')
    expect(wrapper.text()).toContain('其中 1 项超出预算参考')
    expect(wrapper.findAll('.over-budget-badge')).toHaveLength(1)
  })

  it('explains a day that is short of candidates instead of repeating a place', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [],
            transport_advice: null,
            daily_plan: [
              {
                date: '2026-10-01',
                items: [
                  { place_name: '玄武湖景区', start_time: '09:30:00', end_time: '12:00:00', note: '上午主要游览' },
                ],
                shortage: '推荐项目不够分配，本日缺少午餐、晚餐；请再多生成一次规划建议补充候选。',
              },
              { date: '2026-10-02', items: [], shortage: '推荐项目不够分配，本日缺少早餐、景点、午餐、晚餐；请再多生成一次规划建议补充候选。' },
            ],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    const notes = wrapper.findAll('.plan-shortage')
    expect(notes).toHaveLength(2)
    expect(notes[0].text()).toContain('缺少午餐、晚餐')
    // A day that could not be filled is still listed rather than dropped.
    expect(wrapper.text()).toContain('2026-10-02')
  })

  it('renders no shortage note when every day is covered', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [],
            transport_advice: null,
            daily_plan: [
              {
                date: '2026-10-01',
                items: [
                  { place_name: '玄武湖景区', start_time: '09:30:00', end_time: '12:00:00', note: '上午主要游览' },
                ],
                shortage: null,
              },
            ],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.plan-shortage').exists()).toBe(false)
  })

  it('credits the photographer and links to Pexels when a card uses their photo', async () => {
    // Using the Pexels API requires crediting Pexels and the photographer wherever the
    // photo is shown, so both the per-card line and the section link must be present.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '夫子庙',
                category: 'attraction',
                reason: '适合夜游',
                estimated_cost: 0,
                image_url: 'https://images.pexels.com/photos/1/a.jpeg',
                fallback_image_url: '/static/place_images/attraction.svg',
                image_credit: 'Photo by Zhang Wei on Pexels',
                image_credit_url: 'https://www.pexels.com/photo/temple-1/',
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    const credit = wrapper.get('.recommendation-credit a')
    expect(credit.text()).toBe('Photo by Zhang Wei on Pexels')
    expect(credit.attributes('href')).toBe('https://www.pexels.com/photo/temple-1/')
    // The section-level link to Pexels itself is required as well as the photographer,
    // and it must say these are library photos rather than pictures of the place: a
    // search for one restaurant returned a street in a different city entirely.
    const attribution = wrapper.get('.recommendation-attribution')
    expect(attribution.find('a').text()).toBe('Photos provided by Pexels')
    expect(attribution.find('a').attributes('href')).toBe('https://www.pexels.com')
    expect(attribution.text()).toContain('不一定是该地点的实景照片')
  })

  it('shows no Pexels attribution when every photo comes from elsewhere', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '夫子庙',
                category: 'attraction',
                reason: '适合夜游',
                estimated_cost: 0,
                image_url: 'https://cdn.example.com/real.jpg',
                fallback_image_url: '/static/place_images/attraction.svg',
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.recommendation-attribution').exists()).toBe(false)
    expect(wrapper.find('.recommendation-credit').exists()).toBe(false)
  })

  it('resolves a relative recommendation image against the API origin but shows no image for a daily item', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '无图博物馆',
                category: 'attraction',
                reason: '适合夜游',
                estimated_cost: 0,
                image_url: '/static/place_images/attraction.svg',
                fallback_image_url: '/static/place_images/attraction.svg',
              },
            ],
            transport_advice: null,
            daily_plan: [
              {
                date: '2026-10-01',
                items: [
                  {
                    place_name: '无图博物馆',
                    start_time: '09:00:00',
                    end_time: '11:00:00',
                    note: '上午主要游览',
                    image_url: '/static/place_images/attraction.svg',
                  },
                ],
              },
            ],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // The recommendation card resolves the relative path against the API origin.
    expect(wrapper.find('.recommendation-image[alt="无图博物馆"]').attributes('src')).toBe(
      'http://127.0.0.1:8001/static/place_images/attraction.svg',
    )
    // The suggested-days entry carries the same place but no photo, only text.
    const dailyItem = wrapper.get('.plan-item')
    expect(dailyItem.find('img').exists()).toBe(false)
    expect(dailyItem.text()).toContain('无图博物馆')
    expect(dailyItem.text()).toContain('上午主要游览')
  })

  it('adds selected recommendation costs to the total and hands them to the adjustment', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              { name: '金陵饭店', category: 'hotel', reason: '位置便利', estimated_cost: 1800, cost_explanation: '约 600 元/晚 × 3 晚 = 1800 元', image_url: '/static/place_images/hotel.svg', fallback_image_url: '/static/place_images/hotel.svg' },
              { name: '夫子庙', category: 'attraction', reason: '适合夜游', estimated_cost: 200, cost_explanation: '约 100 元/人 × 2 人 = 200 元', image_url: '/static/place_images/attraction.svg', fallback_image_url: '/static/place_images/attraction.svg' },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 1000, trip_budget: 5000, remaining_budget: 4000, currency: 'CNY', note: 'Estimate only', breakdown: [] },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('合计约 1000 CNY')
    expect(wrapper.text()).toContain('已收藏地点基础预算：1000 CNY')
    expect(wrapper.text()).toContain('剩余预算 / Remaining：4000 CNY')

    await wrapper.get('input[aria-label="Select 金陵饭店"]').setValue(true)
    await flushPromises()

    expect(wrapper.text()).toContain('合计约 2800 CNY')
    expect(wrapper.text()).toContain('待加入的已选推荐：+1800 CNY（1 项）')
    expect(wrapper.text()).toContain('剩余预算 / Remaining：2200 CNY')

    const adjustmentButton = wrapper.get('button[aria-label="Hand selected places to the conversational adjustment"]')
    await adjustmentButton.trigger('click')
    const emitted = wrapper.emitted('adjustment-requested')
    expect(emitted).toBeTruthy()
    expect(String(emitted?.[0]?.[0])).toContain('金陵饭店')
    expect(String(emitted?.[0]?.[0])).not.toContain('夫子庙')
    expect(String(emitted?.[0]?.[0])).toContain('1800')
  })

  it('swaps a failed recommendation photo for the bundled placeholder', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '外滩',
                category: 'attraction',
                reason: '夜景',
                price_hint: null,
                image_url: 'https://images.example.com/dead.jpg',
                fallback_image_url: '/static/place_images/attraction.svg',
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('img[alt="外滩"]').attributes('src')).toBe('https://images.example.com/dead.jpg')

    // One failure retries the same photo; the second retires it for the placeholder.
    await wrapper.find('img[alt="外滩"]').trigger('error')
    expect(wrapper.find('img[alt="外滩"]').attributes('src')).toBe('https://images.example.com/dead.jpg?_retry=1')

    await wrapper.find('img[alt="外滩"]').trigger('error')

    expect(wrapper.find('img[alt="外滩"]').attributes('src')).toBe(
      'http://127.0.0.1:8001/static/place_images/attraction.svg',
    )
  })

  it('keeps the picture on every card when one shared placeholder fails', async () => {
    // The regression: two cards share the attraction placeholder. One placeholder
    // failure used to disable that URL for the whole page, which removed the <img>
    // element from every other card relying on it.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '外滩',
                category: 'attraction',
                reason: '夜景',
                image_url: 'https://images.example.com/dead-a.jpg',
                fallback_image_url: '/static/place_images/attraction.svg',
              },
              {
                name: '豫园',
                category: 'attraction',
                reason: '园林',
                image_url: 'https://images.example.com/dead-b.jpg',
                fallback_image_url: '/static/place_images/attraction.svg',
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // Both provider photos die, so both cards fall back to the same placeholder URL.
    await killPhoto(wrapper, '外滩')
    await killPhoto(wrapper, '豫园')

    expect(wrapper.find('img[alt="外滩"]').attributes('src')).toContain('place_images/attraction.svg')
    expect(wrapper.find('img[alt="豫园"]').attributes('src')).toContain('place_images/attraction.svg')

    // Now that shared placeholder itself fails once. Every card must still render
    // a picture, and the placeholder must be retried rather than abandoned.
    await wrapper.find('img[alt="外滩"]').trigger('error')

    expect(wrapper.find('img[alt="外滩"]').exists()).toBe(true)
    expect(wrapper.find('img[alt="豫园"]').exists()).toBe(true)
    expect(wrapper.find('img[alt="外滩"]').attributes('src')).toContain('_retry=1')
    expect(wrapper.find('img[alt="豫园"]').attributes('src')).toContain('place_images/attraction.svg')
  })

  it('gives a re-planned card a fresh chance at its new photo', async () => {
    // The reported bug: re-plan, and the attraction pictures were "gone again".
    //
    // Failures are remembered per card, keyed by the place name, and nothing cleared them
    // when a new plan replaced the old one. So a card that had failed once stayed failed
    // for the rest of the session and the next plan rendered its placeholder immediately,
    // without ever asking for the brand-new photo URL the response carried -- which looked
    // exactly like the backend had stopped sending pictures.
    const planWith = (imageUrl: string) =>
      new Response(
        JSON.stringify({
          trip_id: 7,
          summary: 'Plan ready',
          final_summary: 'Summary',
          recommendations: [],
          place_recommendations: [
            {
              name: '中山陵',
              category: 'attraction',
              reason: '登高',
              image_url: imageUrl,
              fallback_image_url: '/static/place_images/attraction.svg',
            },
          ],
          transport_advice: null,
          daily_plan: [],
          budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
          conflicts: [],
          sources: [],
          events: [],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      )

    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(planWith('https://images.example.com/first.jpg'))
        .mockResolvedValueOnce(planWith('https://images.example.com/second.jpg')),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })

    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()
    await killPhoto(wrapper, '中山陵')
    expect(wrapper.find('img[alt="中山陵"]').attributes('src')).toContain('place_images/attraction.svg')

    // Re-plan. The same place comes back with a different, working photo.
    await wrapper.get('button[aria-label="重新生成规划建议"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('img[alt="中山陵"]').attributes('src')).toBe(
      'https://images.example.com/second.jpg',
    )
  })

  it('does not let one card losing its photo retire another card’s photo', async () => {
    // Failures used to be remembered per image URL, so one throttled request blanked every
    // card showing that photo. Measured on a real plan: 38 recommendations shared only 17
    // photos, one of them on 9 cards, which made whole groups -- hotels among them -- look
    // like the backend had sent no pictures at all.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '夫子庙',
                category: 'attraction',
                reason: '夜景',
                image_url: 'https://images.example.com/shared.jpg',
                fallback_image_url: '/static/place_images/attraction.svg',
              },
              {
                name: '江南贡院',
                category: 'attraction',
                reason: '科举',
                image_url: 'https://images.example.com/own.jpg',
                fallback_image_url: '/static/place_images/attraction.svg',
              },
              {
                name: '南京涵碧楼酒店',
                category: 'hotel',
                reason: '江景',
                image_url: 'https://images.example.com/hotel.jpg',
                fallback_image_url: '/static/place_images/hotel.svg',
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // Only the card that failed falls back; its neighbours keep their own photos, even
    // though they share nothing and the placeholder they would fall back to is shared.
    await killPhoto(wrapper, '夫子庙')

    expect(wrapper.find('img[alt="夫子庙"]').attributes('src')).toContain('place_images/attraction.svg')
    expect(wrapper.find('img[alt="江南贡院"]').attributes('src')).toBe('https://images.example.com/own.jpg')
    expect(wrapper.find('img[alt="南京涵碧楼酒店"]').attributes('src')).toBe(
      'https://images.example.com/hotel.jpg',
    )
  })

  it('shows a hotel card with its rating and price instead of a picture', async () => {
    // A stock photo of a different hotel is not information, so hotels carry no picture at
    // all and the two facts that do help lead the card.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '南京涵碧楼酒店',
                category: 'hotel',
                reason: '江景',
                estimated_cost: 1860,
                unit_price: 620,
                price_unit: 'night',
                cost_explanation: '620 × 3 晚 = 1860 CNY（按检索到的价格估算）',
                rating: 4.7,
                image_url: null,
                fallback_image_url: null,
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // No picture at all for this card...
    expect(wrapper.findAll('img[alt="南京涵碧楼酒店"]')).toHaveLength(0)
    expect(wrapper.findAll('.recommendation-card img')).toHaveLength(0)
    // ...but the rating is shown, and the nightly price is on the card.
    expect(wrapper.get('.recommendation-rating').text()).toContain('4.7')
    expect(wrapper.get('.recommendation-rating').text()).toContain('参考评分')
    expect(wrapper.get('.recommendation-card').text()).toContain('620')
  })

  it('names the signature dish on a food card', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '马祥兴菜馆',
                category: 'restaurant',
                meal_slot: 'dinner',
                reason: '百年老店',
                estimated_cost: 120,
                signature_dish: '松鼠鳜鱼',
                image_url: 'https://images.example.com/dish.jpg',
                fallback_image_url: '/static/place_images/restaurant.svg',
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.recommendation-dish').text()).toContain('松鼠鳜鱼')
    // The picture on a food card is the dish, so its alt text says so rather than naming
    // the shop -- which is what the card used to show.
    expect(wrapper.get('img[alt="马祥兴菜馆 的招牌菜 松鼠鳜鱼"]')).toBeTruthy()
  })

  it('never adds reference accommodation and meals to the exact total', async () => {
    // The reported double-count: the baseline charged a guessed room and guessed meals, and
    // ticking a hotel then added its real price on top of the guess. The exact sum stays exact,
    // and the old fixed reference list (300 a night / 80 a person a day) is gone from the panel:
    // it is replaced by the category allocation, which is derived from the trip's own budget.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '南京金鹰国际酒店',
                category: 'hotel',
                reason: '位置好',
                unit_price: 620,
                price_unit: 'night',
                quantity: 1,
                quantity_unit: '晚',
                quantity_max: 4,
                cost_source: '按检索到的价格估算',
                estimated_cost: 620,
                image_url: null,
                fallback_image_url: null,
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: {
              total_estimate: 300,
              trip_budget: 8000,
              remaining_budget: 7700,
              currency: 'CNY',
              note: '住宿与餐饮不预设基础估算，只按你已勾选或已收藏的具体项目计入总额；下方参考金额未计入。',
              breakdown: [{ category: 'transport', name: '市内交通基础估算', amount: 300 }],
              daily_estimate: 60,
              excluded_allowances: [
                { category: 'hotel', name: '住宿参考（未计入总额）', amount: 1200 },
                { category: 'restaurant', name: '餐饮参考（未计入总额）', amount: 800 },
              ],
            },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // Nothing ticked: the exact total is the transport baseline plus nothing, NOT 300 + 1200 + 800.
    expect(wrapper.text()).toContain('已选与已收藏项目的实际估算（真实单价相加，不是上面的分配建议）')
    expect(wrapper.text()).toContain('基础预算（住宿与餐饮按已选项目计入）：300 CNY')
    expect(wrapper.text()).toContain('剩余预算 / Remaining：7700 CNY')
    // The fixed reference figures are gone; the panel points at the editor for the real estimate.
    expect(wrapper.find('.budget-reference').exists()).toBe(false)
    expect(wrapper.text()).toContain('把项目安排进日程之后的真实估算，请看「编辑每日行程」面板里的预算估算。')

    // Ticking the hotel adds its real price to the transport baseline, once -- and once
    // means ONE night, because a trip may change hotels.
    await wrapper.get('input[aria-label="Select 南京金鹰国际酒店"]').setValue(true)
    await flushPromises()
    expect(wrapper.text()).toContain('待加入的已选推荐：+620')
    expect(wrapper.text()).toContain('约 620 元/晚 × 1 晚 = 620 元')
  })

  it('leads the planning budget panel with the AI category allocation, not a pre-selection sum', async () => {
    // The reported problem: with nothing chosen the panel showed a total that was really just the
    // transport baseline, so it answered no useful question. It now shows what the traveller's own
    // budget should buy, by category, and says which figure is the estimate.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [],
            transport_advice: null,
            daily_plan: [],
            budget: {
              total_estimate: 300,
              trip_budget: 30000,
              remaining_budget: 29700,
              currency: 'CNY',
              note: '只按已勾选或已收藏的具体项目计入总额。',
              breakdown: [{ category: 'transport', name: '市内交通基础估算', amount: 300 }],
              daily_estimate: 60,
              excluded_allowances: [],
            },
            budget_plan: {
              status: 'estimated',
              currency: 'CNY',
              trip_budget: 30000,
              planned_total: 24000,
              planned_ratio: 0.8,
              planned_ratio_percent: 80,
              reserve_amount: 6000,
              source: 'model',
              days: 6,
              nights: 5,
              people: 2,
              categories: [
                { category: 'hotel', label: '酒店 / 住宿', amount: 11000, share: 0.4583, share_percent: 46, unit: '约 2200 CNY/晚 × 5 晚' },
                { category: 'restaurant', label: '美食 / 餐饮', amount: 6200, share: 0.2583, share_percent: 26, unit: '约 517 CNY/人/天 × 2 人 × 6 天' },
                { category: 'attraction', label: '景点门票', amount: 3600, share: 0.15, share_percent: 15, unit: '约 1800 CNY/人 × 2 人（全程门票）' },
                { category: 'transport', label: '市内交通', amount: 2000, share: 0.0833, share_percent: 8, unit: '约 333 CNY/天 × 6 天' },
                { category: 'other', label: '其他 / 购物机动', amount: 1200, share: 0.05, share_percent: 5, unit: '含购物、临时打车与机动支出' },
              ],
              note: '按你填写的总预算 30000 CNY 估算：计划花掉约 24000 CNY（80%），预留约 6000 CNY（20%）给购物与临时支出。',
              summary: '酒店约 11000 CNY，美食约 6200 CNY；合计约 24000 CNY（按总预算估算）',
            },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // The headline is the estimate, and it says what it is derived from.
    expect(wrapper.text()).toContain('计划花掉约 24000 CNY')
    expect(wrapper.text()).toContain('按你填写的总预算 30000 CNY 估算，占')
    expect(wrapper.text()).toContain('预留约 6000')

    // Every category the traveller asked about, with its amount and its per-night/per-person
    // reading, so the number can be sanity-checked rather than trusted.
    const table = wrapper.get('.allocation-table').text()
    expect(table).toContain('酒店 / 住宿')
    expect(table).toContain('11000 CNY')
    expect(table).toContain('46%')
    expect(table).toContain('约 2200 CNY/晚 × 5 晚')
    expect(table).toContain('美食 / 餐饮')
    expect(table).toContain('6200 CNY')
    expect(table).toContain('景点门票')
    expect(table).toContain('3600 CNY')
    expect(table).toContain('合计')
    expect(table).toContain('24000 CNY')
    expect(table).toContain('由 AI 按目的地行情估算')

    // The exact sums are still there, and they are labelled as a different question. This payload
    // has no excluded allowances, so the baseline is described as the saved-place budget.
    expect(wrapper.text()).toContain('已收藏地点基础预算：300 CNY')
    expect(wrapper.text()).toContain('已选与已收藏项目的实际估算（真实单价相加，不是上面的分配建议）')
    // The two figures are different on purpose and must not be conflated: the panel no longer
    // headlines the pre-selection sum.
    expect(wrapper.text()).not.toContain('合计约 300 CNY')
  })

  it('asks for a budget when the trip has none, instead of inventing an allocation', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '', breakdown: [] },
            budget_plan: {
              status: 'unavailable',
              currency: 'CNY',
              trip_budget: null,
              planned_total: 0,
              categories: [],
              note: '这个行程还没有填写总预算，所以暂时无法按预算给出分配建议。',
              summary: '',
            },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('这个行程还没有填写总预算')
    expect(wrapper.find('.allocation-table').exists()).toBe(false)
    // With no estimate to lead with, the panel falls back to the exact sum it always showed.
    expect(wrapper.text()).toContain('合计约 0 CNY')
  })

  it('charges a restaurant for the meals actually eaten there, not for every day of the trip', async () => {
    // The reported bug: one restaurant was charged unit_price × days × people, i.e. as if
    // every meal of the whole trip were eaten at that one place. Ticking a restaurant is
    // one meal, and the 吃几次 control is what makes it two or three.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '南京大牌檔',
                category: 'restaurant',
                reason: '本地菜',
                unit_price: 80,
                price_unit: 'person_meal',
                quantity: 1,
                quantity_unit: '次',
                quantity_max: 5,
                cost_source: '按检索到的价格估算',
                estimated_cost: 160,
                signature_dish: '盐水鸭',
                image_url: null,
                fallback_image_url: null,
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: {
              total_estimate: 300,
              trip_budget: 8000,
              remaining_budget: 7700,
              currency: 'CNY',
              note: 'Estimate',
              breakdown: [{ category: 'transport', name: '市内交通基础估算', amount: 300 }],
              daily_estimate: 60,
            },
            conflicts: [],
            sources: [],
            events: [],
            // Two travellers: one meal for the party is 2 × 80.
            traveler_profile: { total_people: 2 },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // One meal for two people -- NOT 80 × 5 days × 2 people = 800.
    expect(wrapper.text()).toContain('约 160 CNY')
    expect(wrapper.text()).toContain('约 80 元/人/餐 × 2 人 × 1 次 = 160 元')

    await wrapper.get('input[aria-label="Select 南京大牌檔"]').setValue(true)
    await flushPromises()
    expect(wrapper.text()).toContain('合计约 460 CNY')

    // Eating there three times is the traveller's call, and the total follows.
    await wrapper.get('input[aria-label="次 南京大牌檔"]').setValue('3')
    await flushPromises()
    expect(wrapper.text()).toContain('约 80 元/人/餐 × 2 人 × 3 次 = 480 元')
    expect(wrapper.text()).toContain('合计约 780 CNY')
    expect(wrapper.text()).toContain('待加入的已选推荐：+480 CNY（1 项）')
  })

  it('gives a hotel one night by default and follows the night count into the total', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '金陵饭店',
                category: 'hotel',
                reason: '位置便利',
                unit_price: 620,
                price_unit: 'night',
                quantity: 1,
                quantity_unit: '晚',
                quantity_max: 4,
                cost_source: '按检索到的价格估算',
                estimated_cost: 620,
                rating: 4.7,
                image_url: null,
                fallback_image_url: null,
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: {
              total_estimate: 300,
              trip_budget: 8000,
              remaining_budget: 7700,
              currency: 'CNY',
              note: 'Estimate',
              breakdown: [{ category: 'transport', name: '市内交通基础估算', amount: 300 }],
              daily_estimate: 60,
            },
            conflicts: [],
            sources: [],
            events: [],
            traveler_profile: { total_people: 2 },
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    await wrapper.get('input[aria-label="Select 金陵饭店"]').setValue(true)
    await flushPromises()
    expect(wrapper.text()).toContain('合计约 920 CNY')

    await wrapper.get('input[aria-label="晚 金陵饭店"]').setValue('3')
    await flushPromises()
    expect(wrapper.text()).toContain('约 620 元/晚 × 3 晚 = 1860 元')
    expect(wrapper.text()).toContain('合计约 2160 CNY')

    // The count is clamped to the trip's nights, and never below one.
    await wrapper.get('input[aria-label="晚 金陵饭店"]').setValue('99')
    await flushPromises()
    expect(wrapper.text()).toContain('约 620 元/晚 × 4 晚 = 2480 元')
    await wrapper.get('input[aria-label="晚 金陵饭店"]').setValue('0')
    await flushPromises()
    expect(wrapper.text()).toContain('约 620 元/晚 × 1 晚 = 620 元')
  })

  it('never renders a recommendation card without a picture', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              // The backend could not find a photo, so it sent the placeholder as
              // the image itself. The card must still show that placeholder.
              { name: '奶奶的味道', category: 'restaurant', reason: '本帮菜', image_url: '/static/place_images/restaurant.svg', fallback_image_url: '/static/place_images/restaurant.svg' },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    const image = wrapper.find('img[alt="奶奶的味道"]')
    expect(image.exists()).toBe(true)
    expect(image.attributes('src')).toBe('http://127.0.0.1:8001/static/place_images/restaurant.svg')

    // Even after repeated failures the element stays in the DOM with a source.
    await image.trigger('error')
    await wrapper.find('img[alt="奶奶的味道"]').trigger('error')
    const recovered = wrapper.find('img[alt="奶奶的味道"]')
    expect(recovered.exists()).toBe(true)
    expect(recovered.attributes('src')).toBeTruthy()
  })

  it('does not charge a saved place twice in the running total', async () => {
    // '金陵饭店' is already in 收藏地点, so its 1800 is inside the saved-place base
    // budget; ticking it again must not add another 1800 on top.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              { name: '金陵饭店', category: 'hotel', reason: '位置便利', estimated_cost: 1800, image_url: 'https://images.example.com/hotel.jpg', fallback_image_url: '/static/place_images/hotel.svg' },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 1800, trip_budget: 5000, remaining_budget: 3200, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7, destination: '南京', savedPlaceNames: ['金陵饭店'] },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // The card is marked as already saved.
    expect(wrapper.text()).toContain('已在收藏地点')

    await wrapper.get('input[aria-label="Select 金陵饭店"]').setValue(true)
    await flushPromises()

    // 1800 (saved-place base) + 0, not 3600.
    expect(wrapper.text()).toContain('合计约 1800 CNY')
    expect(wrapper.text()).toContain('已加入收藏地点的已选推荐：1800 CNY')
    expect(wrapper.text()).toContain('剩余预算 / Remaining：3200 CNY')
  })

  it('saves the ticked recommendations as real saved places', async () => {
    const fetchMock = vi.fn()
      // 1. the plan itself
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              { name: '金陵饭店', category: 'hotel', reason: '位置便利', estimated_cost: 1800, latitude: 32.0416, longitude: 118.7785, image_url: 'https://images.example.com/hotel.jpg', fallback_image_url: '/static/place_images/hotel.svg' },
              { name: '夫子庙', category: 'attraction', reason: '夜游', estimated_cost: 200, image_url: '/static/place_images/attraction.svg', fallback_image_url: '/static/place_images/attraction.svg' },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      // 2. existing places, so the already-saved one is recognised
      .mockResolvedValueOnce(
        new Response(JSON.stringify([{ id: 1, trip_id: 7, name: '夫子庙', type: 'attraction', address: '南京', latitude: null, longitude: null, image_url: null, rating: null, price_level: null, opening_hours: null, source_url: null }]), { status: 200 }),
      )
      // 3. the one new place
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ id: 2, trip_id: 7, name: '金陵饭店', type: 'hotel', address: '南京 · 地址待确认', latitude: null, longitude: null, image_url: 'https://images.example.com/hotel.jpg', rating: null, price_level: null, opening_hours: null, source_url: null }), { status: 201 }),
      )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7, destination: '南京' },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // Tick both cards, then save them.
    await wrapper.get('input[aria-label="Select 金陵饭店"]').setValue(true)
    await wrapper.get('input[aria-label="Select 夫子庙"]').setValue(true)
    await wrapper.get('button[aria-label="Save selected places to my collection"]').trigger('click')
    await flushPromises()

    // Only the place that did not exist yet is posted, and the bundled placeholder
    // is dropped because the API requires an absolute image URL.
    const created = fetchMock.mock.calls.filter((call) => String(call[0]).includes('/places') && call[1]?.method === 'POST')
    expect(created).toHaveLength(1)
    expect(JSON.parse(created[0][1].body)).toMatchObject({
      name: '金陵饭店',
      type: 'hotel',
      address: '南京 · 地址待确认',
      image_url: 'https://images.example.com/hotel.jpg',
      // The planner's rough position is what lets the saved place show up on the workspace
      // map; `placePayloadFor` used to hard-code null here, so every planned stop was
      // invisible on the map even when the plan knew where it was.
      latitude: 32.0416,
      longitude: 118.7785,
    })
    expect(wrapper.text()).toContain('已把 1 个推荐项目加入收藏地点')
    expect(wrapper.text()).toContain('1 个已存在已跳过')
    expect(wrapper.emitted('places-updated')).toBeTruthy()
  })

  it('keeps the suggested day when a ticked recommendation becomes a saved place', async () => {
    const fetchMock = vi
      .fn()
      // 1. the plan: the hotel is suggested for the third day
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              { name: '金陵饭店', category: 'hotel', reason: '位置便利', estimated_cost: 1800, image_url: 'https://images.example.com/hotel.jpg' },
            ],
            transport_advice: null,
            daily_plan: [
              {
                date: '2026-10-01',
                items: [{ place_name: '夫子庙', start_time: '09:00:00', end_time: '11:00:00', note: '上午' }],
              },
              {
                date: '2026-10-03',
                items: [{ place_name: '金陵饭店', start_time: '13:00:00', end_time: '14:00:00', note: '入住' }],
              },
            ],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      // 2. no saved places yet
      .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
      // 3. the created place
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ id: 2, trip_id: 7, name: '金陵饭店', type: 'hotel', address: '南京 · 地址待确认', latitude: null, longitude: null, image_url: null, rating: null, price_level: null, opening_hours: null, source_url: null }), { status: 201 }),
      )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7, destination: '南京' },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // The card says which day it is suggested for.
    expect(wrapper.text()).toContain('建议安排在 2026-10-03')

    await wrapper.get('input[aria-label="Select 金陵饭店"]').setValue(true)
    await wrapper.get('button[aria-label="Save selected places to my collection"]').trigger('click')
    await flushPromises()

    const created = fetchMock.mock.calls.filter((call) => String(call[0]).includes('/places') && call[1]?.method === 'POST')
    expect(created).toHaveLength(1)
    // Without this the day was lost and 一键排入行程 put the hotel on day one.
    expect(JSON.parse(created[0][1].body)).toMatchObject({
      name: '金陵饭店',
      preferred_date: '2026-10-03',
    })
  })

  it('sends no suggested day for a recommendation the plan never scheduled', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              { name: '金陵饭店', category: 'hotel', reason: '位置便利', estimated_cost: 1800 },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      )
      .mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ id: 2, trip_id: 7, name: '金陵饭店', type: 'hotel', address: '南京 · 地址待确认', latitude: null, longitude: null, image_url: null, rating: null, price_level: null, opening_hours: null, source_url: null }), { status: 201 }),
      )
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7, destination: '南京' },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    await wrapper.get('input[aria-label="Select 金陵饭店"]').setValue(true)
    await wrapper.get('button[aria-label="Save selected places to my collection"]').trigger('click')
    await flushPromises()

    const created = fetchMock.mock.calls.filter((call) => String(call[0]).includes('/places') && call[1]?.method === 'POST')
    expect(JSON.parse(created[0][1].body).preferred_date).toBeNull()
  })

  it('publishes the ticked recommendations for the summary panel', async () => {    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              { name: '金陵饭店', category: 'hotel', reason: '位置便利', estimated_cost: 1800, image_url: 'https://images.example.com/hotel.jpg' },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    await wrapper.get('input[aria-label="Select 金陵饭店"]').setValue(true)
    await flushPromises()

    const { handoff } = usePlanningHandoff(7)
    expect(handoff.value.selectedRecommendations).toEqual([
      { name: '金陵饭店', category: 'hotel', estimated_cost: 1800, reason: '位置便利' },
    ])
  })

  it('does not spin the parent page when a recommendation is ticked', async () => {
    // The reported bug: ticking one item froze the whole page until the browser ran out of
    // memory. The cycle was real and closed through the shared handoff store:
    //
    //   tick -> selection changes -> the handoff watcher writes the store ->
    //   the parent re-renders (its step bar reads that store) ->
    //   `saved-place-names` is a freshly mapped array -> the child's selection computed
    //   re-evaluates to a NEW array with the SAME contents -> the watcher fires again.
    //
    // Both halves matter. The child watched an array and Vue compares arrays by identity,
    // so it could not tell "nothing changed" from "here is a new array"; and the parent
    // rebuilt the prop on every one of its own renders, so every write came straight back.
    // This test mirrors TripDetail's wiring, including reading the store INSIDE the render
    // the way its step-bar template does -- hoist that read out and the loop disappears,
    // which is exactly why the bug survived review.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '夫子庙',
                category: 'attraction',
                reason: '夜景',
                estimated_cost: 100,
                image_url: 'https://images.example.com/a.jpg',
                fallback_image_url: '/static/place_images/attraction.svg',
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    let parentRenders = 0
    const savedNames = ref<string[]>([])
    const Parent = defineComponent({
      setup() {
        const { handoff } = usePlanningHandoff(7)
        return () => {
          parentRenders += 1
          // A hard stop instead of a hung test process if the loop ever returns.
          if (parentRenders > 100) throw new Error('runaway re-render loop')
          const planGenerated = handoff.value.planGenerated
          return h(TripPlanningAgent, {
            tripId: 7,
            // A freshly built array on every parent render -- what TripDetail.vue used to
            // pass inline, and the shape any parent may pass. The fix must hold on its own
            // rather than depending on every parent remembering to hand down a stable value.
            savedPlaceNames: savedNames.value.map((name) => name),
            planGenerated,
          })
        }
      },
    })

    const wrapper = mount(Parent, { global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()
    const afterPlan = parentRenders

    await wrapper.get('input[aria-label="Select 夫子庙"]').setValue(true)
    await flushPromises()
    await nextTick()
    await flushPromises()

    // The tick still reaches the summary panel...
    expect(usePlanningHandoff(7).handoff.value.selectedRecommendations.map((item) => item.name)).toEqual(['夫子庙'])
    // ...without the page rendering itself to death.
    expect(parentRenders).toBeLessThanOrEqual(afterPlan + 3)
  })

  it('publishes the plan recommendations so the itinerary editor can offer them', async () => {
    // The editor used to know nothing about what the planning agent had proposed, so the
    // only way to reuse a recommendation was to type its name into the instruction box.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '金陵饭店',
                category: 'hotel',
                reason: '位置便利',
                estimated_cost: 620,
                unit_price: 620,
                quantity: 1,
                quantity_unit: '晚',
                image_url: null,
                fallback_image_url: null,
              },
              {
                name: '南京大牌檔',
                category: 'restaurant',
                reason: '本地菜',
                estimated_cost: 240,
                unit_price: 120,
                quantity: 2,
                quantity_unit: '次',
                image_url: null,
                fallback_image_url: null,
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    const published = usePlanningHandoff(7).handoff.value.planRecommendations
    expect(published.map((item) => item.name)).toEqual(['金陵饭店', '南京大牌檔'])
    // The price and the count travel with the name, which is what lets the editor add one
    // at the same money the card was showing.
    expect(published[1]).toMatchObject({ category: 'restaurant', unit_price: 120, quantity: 2, quantity_unit: '次' })
  })

  /**
   * Writing the suggested days into 每日行程 in one click.
   *
   * The plan recommends a day-by-day arrangement, but turning it into real itinerary items used to
   * mean adding every row by hand (or paying for a conversational round-trip). These tests pin the
   * three things that make a bulk write safe: the recommended times travel with the row, a
   * suggestion that is not saved yet is saved with the same payload the collection button writes,
   * and a second click does not duplicate the day.
   */
  const SUGGESTED_PLAN = {
    trip_id: 7,
    summary: 'Plan ready',
    final_summary: 'Summary',
    recommendations: [],
    place_recommendations: [
      {
        name: '金陵饭店',
        category: 'hotel',
        reason: '位置便利',
        estimated_cost: 1800,
        unit_price: 620,
        quantity: 1,
        quantity_unit: '晚',
        image_url: 'https://images.example.com/hotel.jpg',
        fallback_image_url: '/static/place_images/hotel.svg',
      },
      {
        name: '南京大牌檔',
        category: 'restaurant',
        reason: '本地菜',
        estimated_cost: 240,
        unit_price: 120,
        quantity: 2,
        quantity_unit: '次',
        image_url: null,
        fallback_image_url: '/static/place_images/restaurant.svg',
      },
    ],
    transport_advice: null,
    daily_plan: [
      {
        date: '2026-10-01',
        shortage: null,
        items: [
          { place_name: '金陵饭店', start_time: '14:00', end_time: '15:30', note: '入住放行李' },
          { place_name: '南京大牌檔', start_time: '18:30', end_time: '20:00', note: '晚餐' },
        ],
      },
      {
        date: '2026-10-02',
        shortage: null,
        items: [{ place_name: '南京大牌檔', start_time: '12:00', end_time: '13:30', note: '午餐' }],
      },
    ],
    budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
    conflicts: [],
    sources: [],
    events: [],
  }

  function suggestedPlanResponse(): Response {
    return new Response(JSON.stringify(SUGGESTED_PLAN), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    })
  }

  function savedPlace(id: number, name: string, type: string): Record<string, unknown> {
    return {
      id,
      trip_id: 7,
      name,
      type,
      address: '南京 · 地址待确认',
      latitude: null,
      longitude: null,
      image_url: null,
      rating: null,
      price_level: null,
      opening_hours: null,
      source_url: null,
    }
  }

  /**
   * A fetch stub that answers by path instead of by call order.
   *
   * The write makes a place lookup, an itinerary lookup and then a create per row, interleaved --
   * `mockResolvedValueOnce` in a fixed order handed the itinerary POST a place payload and the
   * assertion then compared the wrong objects. Answering by path keeps the fixture honest.
   */
  function writeRouter(options: {
    places?: Record<string, unknown>[]
    items?: Record<string, unknown>[]
  } = {}) {
    const places = [...(options.places ?? [])]
    const items = [...(options.items ?? [])]
    let nextPlaceId = 500
    let nextItemId = 900
    return vi.fn(async (url: string, init?: RequestInit) => {
      const path = String(url)
      const method = init?.method ?? 'GET'
      if (path.includes('/itinerary-items')) {
        if (method === 'POST') {
          const body = JSON.parse(String(init?.body))
          items.push({ id: nextItemId++, trip_id: 7, ...body })
          return new Response(JSON.stringify({ id: nextItemId - 1 }), { status: 201 })
        }
        return new Response(JSON.stringify(items), { status: 200 })
      }
      if (path.includes('/places')) {
        if (method === 'POST') {
          const body = JSON.parse(String(init?.body))
          const existing = places.find((place) => place.name === body.name)
          if (existing) return new Response(JSON.stringify(existing), { status: 201 })
          const created = savedPlace(nextPlaceId++, body.name, body.type)
          places.push(created)
          return new Response(JSON.stringify(created), { status: 201 })
        }
        return new Response(JSON.stringify(places), { status: 200 })
      }
      return suggestedPlanResponse()
    })
  }

  it('offers a tick per suggested item and writes the ticked ones with the recommended times', async () => {
    const fetchMock = writeRouter()
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7, destination: '南京' },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    // Everything writeable starts ticked, so one click is enough for the common case.
    expect(wrapper.get('input[aria-label="Select suggested 金陵饭店 on 2026-10-01"]').element).toBeTruthy()
    expect(wrapper.text()).toContain('已选 3 项，可写入 3 项')

    await wrapper.get('button[aria-label="Add selected suggestions to the daily itinerary"]').trigger('click')
    await flushPromises()

    const createdPlaces = fetchMock.mock.calls
      .filter((call) => String(call[0]).includes('/places') && call[1]?.method === 'POST')
      .map((call) => JSON.parse(String(call[1]?.body)))
    // One place per distinct suggestion, written with what the collection button would have saved.
    expect(createdPlaces).toHaveLength(2)
    expect(createdPlaces[0]).toMatchObject({ name: '金陵饭店', type: 'hotel', estimated_cost: 620, preferred_date: '2026-10-01' })
    expect(createdPlaces[1]).toMatchObject({ name: '南京大牌檔', type: 'restaurant', estimated_cost: 240 })

    const items = fetchMock.mock.calls
      .filter((call) => String(call[0]).includes('/itinerary-items') && call[1]?.method === 'POST')
      .map((call) => JSON.parse(String(call[1]?.body)))
    expect(items).toHaveLength(3)
    // The recommended times are used verbatim, and the same restaurant may be written on two
    // days -- that is the plan's own suggestion, not a duplicate.
    expect(items[0]).toMatchObject({ date: '2026-10-01', start_time: '14:00', end_time: '15:30', place_id: 500, note: '入住放行李' })
    expect(items[1]).toMatchObject({ date: '2026-10-01', start_time: '18:30', end_time: '20:00', place_id: 501 })
    expect(items[2]).toMatchObject({ date: '2026-10-02', start_time: '12:00', end_time: '13:30', place_id: 501 })
    expect(wrapper.text()).toContain('已把 3 项加入每日行程')
    expect(wrapper.emitted('places-updated')).toBeTruthy()
  })

  it('skips what that day already has instead of writing a second copy', async () => {
    // Both places are saved already, and the first day's two rows are already on the calendar.
    const fetchMock = writeRouter({
      places: [savedPlace(11, '金陵饭店', 'hotel'), savedPlace(12, '南京大牌檔', 'restaurant')],
      items: [
        { id: 1, trip_id: 7, date: '2026-10-01', start_time: '14:00:00', end_time: '15:30:00', place_id: 11, note: null, sort_order: 0 },
        { id: 2, trip_id: 7, date: '2026-10-01', start_time: '18:30:00', end_time: '20:00:00', place_id: 12, note: null, sort_order: 1 },
      ],
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7, destination: '南京' },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()
    await wrapper.get('button[aria-label="Add selected suggestions to the daily itinerary"]').trigger('click')
    await flushPromises()

    // Nothing new to save, and only the second day's lunch is written.
    expect(fetchMock.mock.calls.filter((call) => String(call[0]).includes('/places') && call[1]?.method === 'POST')).toHaveLength(0)
    const items = fetchMock.mock.calls.filter((call) => String(call[0]).includes('/itinerary-items') && call[1]?.method === 'POST')
    expect(items).toHaveLength(1)
    expect(JSON.parse(String(items[0]?.[1]?.body))).toMatchObject({ date: '2026-10-02', place_id: 12 })
    expect(wrapper.text()).toContain('2 项这一天已经排过，已跳过')
  })

  /**
   * The price tier and the "this number was corrected" note.
   *
   * The traveller asked for food that follows their budget AND is a mix -- cheap places for every
   * day plus the expensive one that is worth it -- and complained that a Japanese sushi
   * restaurant was priced like a noodle lunch. Both facts have to be visible on the card, or the
   * budget looks wrong for reasons the reader cannot see.
   */
  it('labels the price tier and says when a price was raised to the local floor', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            trip_id: 7,
            summary: 'Plan ready',
            final_summary: 'Summary',
            recommendations: [],
            place_recommendations: [
              {
                name: '一兰拉面（新宿本店）',
                category: 'restaurant',
                reason: '深夜也开',
                estimated_cost: 58,
                unit_price: 58,
                price_unit: 'person_meal',
                price_tier: 'cheap',
                price_tier_label: '平价',
                price_adjusted: false,
                cost_source: '按检索到的价格估算',
                image_url: null,
                fallback_image_url: null,
              },
              {
                name: '大和寿司（本店）',
                category: 'restaurant',
                reason: '招牌握寿司',
                estimated_cost: 144,
                unit_price: 144,
                price_unit: 'person_meal',
                price_tier: 'mid',
                price_tier_label: '中档',
                price_adjusted: true,
                cost_source: '资料价低于当地同类常见人均，已按常见人均下限 144 元估计',
                image_url: null,
                fallback_image_url: null,
              },
              {
                name: '伊豆荣（浅草店）',
                category: 'restaurant',
                reason: '鳗鱼饭老店',
                estimated_cost: 384,
                unit_price: 384,
                price_unit: 'person_meal',
                price_tier: 'splurge',
                price_tier_label: '值得多花钱',
                price_adjusted: false,
                over_budget: false,
                budget_note: '比人均参考（166 元）高一些，属于按你的预算留出的“值得多花钱”那一档',
                image_url: null,
                fallback_image_url: null,
              },
            ],
            transport_advice: null,
            daily_plan: [],
            budget: { total_estimate: 0, trip_budget: null, remaining_budget: null, currency: 'CNY', note: '' },
            conflicts: [],
            sources: [],
            events: [],
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripPlanningAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('平价')
    expect(text).toContain('中档')
    expect(text).toContain('值得多花钱')
    // The corrected price explains itself on the card instead of looking like a quoted price.
    expect(text).toContain('资料上的价格低于当地同类店的常见人均，已按当地下限估算，供预算参考')
    expect(text).toContain('按常见人均下限 144 元估计')
    // A splurge tier is not "over budget", so its own note is shown instead of the warning.
    expect(text).toContain('属于按你的预算留出的“值得多花钱”那一档')
    expect(wrapper.findAll('.price-tier-splurge')).toHaveLength(1)
    expect(wrapper.findAll('.price-tier-cheap')).toHaveLength(1)
    expect(wrapper.findAll('.over-budget-badge')).toHaveLength(0)
    expect(wrapper.findAll('.recommendation-price-note')).toHaveLength(1)
  })

  it('writes nothing when the user unticks everything', async () => {    const fetchMock = writeRouter()
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(TripPlanningAgent, {
      props: { tripId: 7, destination: '南京' },
      global: { plugins: [ElementPlus] },
    })
    await wrapper.get('form.planning-form').trigger('submit')
    await flushPromises()

    await wrapper.get('input[aria-label="Select all suggested items"]').setValue(false)
    await flushPromises()
    expect(wrapper.text()).toContain('已选 0 项，可写入 0 项')
    // The button is disabled rather than silently doing nothing.
    const button = wrapper.get('button[aria-label="Add selected suggestions to the daily itinerary"]')
    expect(button.attributes('disabled')).toBeDefined()
    expect(fetchMock.mock.calls.filter((call) => call[1]?.method === 'POST')).toHaveLength(1)
  })
})
