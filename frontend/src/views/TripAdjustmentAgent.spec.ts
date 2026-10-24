import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import TripAdjustmentAgent from './TripAdjustmentAgent.vue'
import { usePlanningHandoff } from '@/composables/planningHandoff'
import type { ItineraryItem, Place } from '@/types/itinerary'

const draft = {
  id: 17,
  trip_id: 7,
  instruction: '2026-10-02 14:00-16:00 安排 Agent Museum',
  status: 'pending',
  operations: [
    {
      kind: 'add_item',
      item_id: null,
      place_id: 12,
      place: null,
      date: '2026-10-02',
      start_time: '14:00:00',
      end_time: '16:00:00',
      note: '由行程调整 Agent 建议',
      sort_order: 0,
    },
  ],
  summary: '草案已生成',
  sources: [],
  events: [{ tool: 'adjustment_parser', status: 'fallback', message: '已使用本地规则。' }],
  budget: { total_estimate: 600, currency: 'CNY' },
  conflicts: [],
}

/** A trip with two scheduled items, one of which is a priced restaurant. */
function itineraryItems(): ItineraryItem[] {
  const place = (overrides: Partial<Place>): Place => ({
    id: 1,
    trip_id: 7,
    name: '南京博物院',
    type: 'attraction',
    address: '中山东路',
    latitude: null,
    longitude: null,
    image_url: null,
    rating: null,
    price_level: 1,
    opening_hours: null,
    source_url: null,
    estimated_cost: null,
    quantity: null,
    ...overrides,
  })
  return [
    {
      id: 41,
      trip_id: 7,
      date: '2026-10-01',
      start_time: '09:00:00',
      end_time: '11:00:00',
      place_id: 1,
      note: null,
      sort_order: 0,
      place: place({}),
    },
    {
      id: 42,
      trip_id: 7,
      date: '2026-10-01',
      start_time: '12:00:00',
      end_time: '13:00:00',
      place_id: 2,
      note: null,
      sort_order: 1,
      place: place({
        id: 2,
        name: '南京大牌檔',
        type: 'restaurant',
        price_level: null,
        estimated_cost: 480,
        quantity: 2,
      }),
    },
  ]
}

const EDITOR_PROPS = {
  tripId: 7,
  itineraryItems: itineraryItems(),
  startDate: '2026-10-01',
  endDate: '2026-10-03',
  destination: '南京',
}

describe('TripAdjustmentAgent', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    usePlanningHandoff(7).clear()
  })

  it('shows a draft and emits itinerary-updated only after applying it', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response(JSON.stringify(draft), { status: 201 }))
        .mockResolvedValueOnce(
          new Response(JSON.stringify({ id: 17, status: 'applied', itinerary_item_ids: [33] }), { status: 200 }),
        ),
    )
    const wrapper = mount(TripAdjustmentAgent, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('textarea[aria-label="行程调整指令"]').setValue(draft.instruction)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('新增行程')
    expect(wrapper.emitted('itinerary-updated')).toBeUndefined()

    await wrapper.get('button[aria-label="Apply selected recommendations"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('itinerary-updated')).toHaveLength(1)
    expect(wrapper.text()).toContain('已应用')
  })

  it('keeps the itinerary unchanged when a draft is rejected', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response(JSON.stringify(draft), { status: 201 }))
        .mockResolvedValueOnce(new Response(JSON.stringify({ ...draft, status: 'rejected' }), { status: 200 })),
    )
    const wrapper = mount(TripAdjustmentAgent, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('textarea[aria-label="行程调整指令"]').setValue(draft.instruction)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('button[aria-label="拒绝调整草案"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('已拒绝')
    expect(wrapper.emitted('itinerary-updated')).toBeUndefined()
  })

  it('names the live search honestly instead of the provider behind it', async () => {
    /* The checkbox used to read "使用 Tavily 联网搜索" while the search actually came from the
     * model's own live search, and that provider has since been removed from the project. */
    const wrapper = mount(TripAdjustmentAgent, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })

    expect(wrapper.text()).toContain('使用联网搜索')
    expect(wrapper.text()).not.toContain('Tavily')
  })

  it('hides raw source details and applies only selected recommendations', async () => {
    const draftWithSource = {
      ...draft,
      sources: [{ source_type: 'tavily', title: 'Adjustment guide', url: 'https://example.com/adjust', snippet: 'Useful tip' }],
    }
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response(JSON.stringify(draftWithSource), { status: 201 }))
        .mockResolvedValueOnce(new Response(JSON.stringify({ id: 17, status: 'applied', itinerary_item_ids: [33] }), { status: 200 })),
    )
    const wrapper = mount(TripAdjustmentAgent, {
      props: { tripId: 7 },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('textarea[aria-label="行程调整指令"]').setValue(draft.instruction)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const recommendation = wrapper.get('input[aria-label="Select recommendation 0"]')
    await recommendation.setValue(true)
    await wrapper.get('button[aria-label="Apply selected recommendations"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('信息来源')
    expect(wrapper.text()).not.toContain('Adjustment guide')
    expect(vi.mocked(fetch)).toHaveBeenLastCalledWith(
      expect.stringContaining('/trips/7/adjustments/17/apply'),
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ operation_indexes: [0] }) }),
    )
  })

  it('keeps replacement operations selected as one atomic choice', async () => {
    const replacementDraft = {
      ...draft,
      operations: [
        { ...draft.operations[0], kind: 'remove_item', item_id: 44, place_id: null, place: null, date: null, start_time: null, end_time: null, note: null },
        { ...draft.operations[0], kind: 'add_place_and_item', place_id: null, note: '替换为新的推荐项目，保留原时间段' },
      ],
    }
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(new Response(JSON.stringify(replacementDraft), { status: 201 }))
        .mockResolvedValueOnce(new Response(JSON.stringify({ id: 17, status: 'applied', itinerary_item_ids: [33] }), { status: 200 })),
    )
    const wrapper = mount(TripAdjustmentAgent, { props: { tripId: 7 }, global: { plugins: [ElementPlus] } })

    await wrapper.get('textarea[aria-label="行程调整指令"]').setValue(replacementDraft.instruction)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('input[aria-label="Select recommendation 0"]').setValue(false)
    await wrapper.get('input[aria-label="Select recommendation 0"]').setValue(true)
    await wrapper.get('button[aria-label="Apply selected recommendations"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(fetch)).toHaveBeenLastCalledWith(
      expect.stringContaining('/trips/7/adjustments/17/apply'),
      expect.objectContaining({ body: JSON.stringify({ operation_indexes: [0, 1] }) }),
    )
  })
})

/**
 * The round-17 rework: the panel is a real editor, and the budget follows the edits.
 *
 * The user's complaint was that the feature "一点都不完整" and that its budget was wrong --
 * it was estimated from the saved-place collection, so deleting a scheduled item barely
 * moved it. These tests pin both halves.
 */
describe('TripAdjustmentAgent daily-itinerary editing', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    usePlanningHandoff(7).clear()
  })

  it('deleting an item takes its money out of the estimate immediately', async () => {
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    // transport 60 x 3 days = 180, 南京博物院 (level 1 attraction) = 100,
    // 南京大牌檔 (quoted, 2 meals) = 480  ->  760
    expect(wrapper.text()).toContain('760 CNY')
    expect(wrapper.text()).toContain('南京大牌檔')

    // The second delete button belongs to 南京大牌檔.
    const deleteButtons = wrapper.findAll('button[aria-label="删除这个行程项"]')
    await deleteButtons[1].trigger('click')
    await flushPromises()

    // The row stays visible so it can be restored, but its 480 is already gone.
    expect(wrapper.text()).toContain('280 CNY')
    expect(wrapper.text()).toContain('南京大牌檔')
    expect(wrapper.findAll('.day-item.is-removed')).toHaveLength(1)
    // Nothing has been written yet.
    expect(wrapper.emitted('itinerary-updated')).toBeUndefined()

    // Restoring it puts the money back.
    await wrapper.get('button[aria-label="恢复这个行程项"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('760 CNY')
  })

  it('turns the calendar edits into operations instead of asking the model', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(draft), { status: 201 })),
    )
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    await wrapper.findAll('button[aria-label="删除这个行程项"]')[1].trigger('click')
    await wrapper.get('input[aria-label="调整 南京博物院 的开始时间"]').setValue('10:00')
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const body = JSON.parse(vi.mocked(fetch).mock.calls[0][1]?.body as string)
    expect(body.operations).toEqual([
      expect.objectContaining({ kind: 'remove_item', item_id: 42 }),
      expect.objectContaining({ kind: 'update_item', item_id: 41, start_time: '10:00:00', date: '2026-10-01' }),
    ])
    // The instruction is derived from the edits, so an empty text box is not a blocker.
    expect(body.instruction).toContain('删除 1 个行程项')
    expect(body.instruction).toContain('移动或改时间 1 项')
  })

  it('accepts a place the user typed, at the price they typed', async () => {    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(draft), { status: 201 })),
    )
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    await wrapper.get('button[aria-label="新增项目"]').trigger('click')
    await wrapper.get('input[aria-label="新增项目名称"]').setValue('我自己找的鸭血粉丝汤')
    await wrapper.get('select[aria-label="新增项目类型"]').setValue('restaurant')
    await wrapper.get('input[aria-label="新增项目单价"]').setValue('48')
    await wrapper.get('input[aria-label="新增项目次数"]').setValue('2')
    await wrapper.get('button[aria-label="确认新增项目"]').trigger('click')
    await flushPromises()

    // 48 per meal x 2 meals = 96, on top of the existing 760.
    expect(wrapper.text()).toContain('我自己找的鸭血粉丝汤')
    expect(wrapper.text()).toContain('856 CNY')

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const body = JSON.parse(vi.mocked(fetch).mock.calls[0][1]?.body as string)
    expect(body.operations).toHaveLength(1)
    expect(body.operations[0]).toMatchObject({
      kind: 'add_place_and_item',
      place: expect.objectContaining({
        name: '我自己找的鸭血粉丝汤',
        type: 'restaurant',
        estimated_cost: 96,
        quantity: 2,
      }),
    })
  })

  it('shows a new project inside the day it belongs to, with its own controls', async () => {
    // A pending addition used to appear as one small line above the budget estimate whose
    // only control was 移除, so a project could be described but never placed. It now sits
    // in its day card with the date, the times, the price and the count all editable.
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    await wrapper.get('button[aria-label="新增项目"]').trigger('click')
    await wrapper.get('input[aria-label="新增项目名称"]').setValue('老门东夜市')
    await wrapper.get('select[aria-label="新增项目类型"]').setValue('restaurant')
    await wrapper.get('select[aria-label="新增项目日期"]').setValue('2026-10-02')
    await wrapper.get('button[aria-label="确认新增项目"]').trigger('click')
    await flushPromises()

    // It renders as a day item of 2026-10-02, not as a stranded row.
    const added = wrapper.findAll('.day-item.is-added')
    expect(added).toHaveLength(1)
    expect(added[0].text()).toContain('老门东夜市')
    expect(added[0].text()).toContain('新增，待保存')

    const day = wrapper.findAll('.day-card').find((card) => card.text().includes('2026-10-02'))
    expect(day?.findAll('.day-item.is-added')).toHaveLength(1)
    // The day counter includes the pending item, so the card does not read as empty.
    expect(day?.text()).toContain('1 项')

    // Every field the user might want to correct is editable in place.
    await added[0].get('input[aria-label="老门东夜市 的单价"]').setValue('45')
    await added[0].get('input[aria-label="老门东夜市 的次数"]').setValue('2')
    await added[0].get('input[aria-label="老门东夜市 的开始时间"]').setValue('19:00')
    await flushPromises()

    // 45 x 2 = 90 on top of the existing 760, and the correction survives re-rendering.
    expect(wrapper.text()).toContain('850 CNY')
    expect(wrapper.get('input[aria-label="老门东夜市 的开始时间"]').element).toHaveProperty('value', '19:00')
    // Still nothing written: it is a pending change until it is saved.
    expect(wrapper.emitted('itinerary-updated')).toBeUndefined()
  })

  it('saves the pending additions into the daily itinerary in one press', async () => {
    // 保存到每日行程 is the direct path the user asked for: the addition is written the way
    // the validated draft-and-apply route writes it, without a detour through the model.
    const saved = {
      ...draft,
      operations: [
        {
          kind: 'add_place_and_item',
          item_id: null,
          place_id: 33,
          place: null,
          date: '2026-10-02',
          start_time: '19:00:00',
          end_time: '21:00:00',
          note: null,
          sort_order: 3,
          place_payload: { name: '老门东夜市', type: 'restaurant', estimated_cost: 90, quantity: 2 },
        },
      ],
      budget: { total_estimate: 850, trip_budget: 4000, currency: 'CNY' },
    }
    vi.stubGlobal(
      'fetch',
      vi.fn()
        .mockResolvedValueOnce(new Response(JSON.stringify(saved), { status: 201 }))
        .mockResolvedValueOnce(new Response(JSON.stringify({ status: 'applied' }), { status: 200 })),
    )
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    await wrapper.get('button[aria-label="新增项目"]').trigger('click')
    await wrapper.get('input[aria-label="新增项目名称"]').setValue('老门东夜市')
    await wrapper.get('select[aria-label="新增项目类型"]').setValue('restaurant')
    await wrapper.get('select[aria-label="新增项目日期"]').setValue('2026-10-02')
    await wrapper.get('input[aria-label="新增项目单价"]').setValue('45')
    await wrapper.get('input[aria-label="新增项目次数"]').setValue('2')
    await wrapper.get('button[aria-label="确认新增项目"]').trigger('click')
    await flushPromises()

    await wrapper.get('button[aria-label="保存到每日行程"]').trigger('click')
    await flushPromises()

    const createCall = vi.mocked(fetch).mock.calls[0]
    expect(String(createCall[0])).toContain('/adjustments')
    const body = JSON.parse(createCall[1]?.body as string)
    expect(body.operations).toEqual([
      expect.objectContaining({
        kind: 'add_place_and_item',
        date: '2026-10-02',
        place: expect.objectContaining({ name: '老门东夜市', estimated_cost: 90, quantity: 2 }),
      }),
    ])
    // Nothing to ask the model: the edits are the instruction.
    expect(body.structured_only).toBe(true)

    // The draft is applied, so the calendar and the budget can reload.
    expect(String(vi.mocked(fetch).mock.calls[1][0])).toContain('/adjustments/17/apply')
    expect(wrapper.text()).toContain('已保存到每日行程')
    expect(wrapper.emitted('itinerary-updated')).toHaveLength(1)
    expect(wrapper.emitted('budget-updated')?.[0]).toEqual([4000])
    // The pending row is gone: it is a saved item now.
    expect(wrapper.findAll('.day-item.is-added')).toHaveLength(0)
    // Nothing pending is left over for a later submit.
    expect(wrapper.get('button[aria-label="保存到每日行程"]').attributes('disabled')).toBeDefined()
  })

  it('will not save before anything has been changed', async () => {
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    // A delete counts as an edit, so the button comes alive as soon as there is something
    // to save.
    expect(wrapper.get('button[aria-label="保存到每日行程"]').attributes('disabled')).toBeDefined()
    await wrapper.findAll('button[aria-label="删除这个行程项"]')[0].trigger('click')
    await flushPromises()
    expect(wrapper.get('button[aria-label="保存到每日行程"]').attributes('disabled')).toBeUndefined()
  })

  it('offers the planning agent recommendations with the prices they were shown at', async () => {
    usePlanningHandoff(7).setPlanRecommendations([
      {
        name: '金陵饭店',
        category: 'hotel',
        reason: '位置便利',
        estimated_cost: 620,
        unit_price: 620,
        quantity: 1,
        quantity_unit: '晚',
      },
    ])
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    await wrapper.get('button[aria-label="新增项目"]').trigger('click')
    await wrapper.get('button[aria-label="选择推荐项目 金陵饭店"]').trigger('click')
    await flushPromises()

    expect((wrapper.get('input[aria-label="新增项目名称"]').element as HTMLInputElement).value).toBe('金陵饭店')
    expect(wrapper.text()).toContain('620')
  })

  it('would not add a recommendation that is already on the calendar', async () => {
    usePlanningHandoff(7).setPlanRecommendations([
      { name: '南京博物院', category: 'attraction' },
      { name: '夫子庙', category: 'attraction' },
    ])
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    await wrapper.get('button[aria-label="新增项目"]').trigger('click')

    expect(wrapper.find('button[aria-label="选择推荐项目 南京博物院"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label="选择推荐项目 夫子庙"]').exists()).toBe(true)
  })

  it('sends the price the user corrected in the preview, not the drafted one', async () => {
    const pricedDraft = {
      ...draft,
      operations: [
        {
          kind: 'add_place_and_item',
          item_id: null,
          place_id: null,
          place: {
            name: '自助晚餐',
            type: 'restaurant',
            address: '南京 · 地址待确认',
            latitude: null,
            longitude: null,
            image_url: null,
            rating: null,
            price_level: null,
            opening_hours: null,
            source_url: null,
            note: null,
            estimated_cost: 300,
            quantity: 1,
          },
          date: '2026-10-02',
          start_time: '18:00:00',
          end_time: '19:30:00',
          note: null,
          sort_order: 0,
        },
      ],
      budget: {
        total_estimate: 480,
        currency: 'CNY',
        breakdown: [
          { category: 'transport', name: '市内交通基础估算', amount: 180, source: 'baseline', unit_price: 180, quantity: 1 },
          {
            category: 'restaurant',
            name: '自助晚餐',
            amount: 300,
            unit_price: 300,
            quantity: 1,
            quantity_unit: '次',
            source: 'quoted',
            operation_index: 0,
          },
        ],
      },
    }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(pricedDraft), { status: 201 })).mockResolvedValueOnce(
        new Response(JSON.stringify({ id: 17, status: 'applied', itinerary_item_ids: [33] }), { status: 200 }),
      ),
    )
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    await wrapper.get('textarea[aria-label="行程调整指令"]').setValue('加一个自助晚餐')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    await wrapper.get('input[aria-label="自助晚餐 单价"]').setValue('188')
    await flushPromises()
    // The visible total follows the corrected price before anything is applied.
    expect(wrapper.text()).toContain('368')

    await wrapper.get('button[aria-label="Apply selected recommendations"]').trigger('click')
    await flushPromises()

    const applyBody = JSON.parse(vi.mocked(fetch).mock.calls[1][1]?.body as string)
    expect(applyBody.operation_indexes).toBeUndefined()
    expect(applyBody.operations[0].place.estimated_cost).toBe(188)
  })

  it('keeps a timeline row picture filled when the photo it was given is dead', async () => {
    const drafted = {
      ...draft,
      daily_plan: [
        {
          date: '2026-10-01',
          items: [
            {
              item_id: 41,
              place_id: 12,
              start_time: '09:00:00',
              end_time: '11:00:00',
              place_name: '南京博物院',
              image_url: 'https://example.com/gone.jpg',
              type: 'attraction',
              changed: 'kept',
            },
          ],
        },
      ],
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(drafted), { status: 201 })))
    const wrapper = mount(TripAdjustmentAgent, { props: EDITOR_PROPS, global: { plugins: [ElementPlus] } })

    await wrapper.get('textarea[aria-label="行程调整指令"]').setValue('看看时间线')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.day-plan-image').attributes('src')).toBe('https://example.com/gone.jpg')

    await wrapper.get('.day-plan-image').trigger('error')

    // The row used to lose its picture entirely, which reads as a rendering bug.
    expect(wrapper.get('.day-plan-image').attributes('src')).toContain('/static/place_images/attraction.svg')
  })

  it('keeps a saved place price correctable after it was saved', async () => {
    const place: Place = {
      id: 9,
      trip_id: 7,
      name: '金陵饭店',
      type: 'hotel',
      address: '新街口',
      latitude: null,
      longitude: null,
      image_url: null,
      rating: null,
      price_level: null,
      opening_hours: null,
      source_url: null,
      estimated_cost: 620,
      quantity: 1,
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(place), { status: 200 })))
    const wrapper = mount(TripAdjustmentAgent, {
      props: { ...EDITOR_PROPS, places: [place] },
      global: { plugins: [ElementPlus] },
    })

    await wrapper.get('input[aria-label="金陵饭店 的价格"]').setValue('780')
    await wrapper.get('button[aria-label="保存 金陵饭店 的价格"]').trigger('click')
    await flushPromises()

    expect(vi.mocked(fetch)).toHaveBeenLastCalledWith(
      expect.stringContaining('/trips/7/places/9'),
      expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ estimated_cost: 780 }) }),
    )
  })
})
