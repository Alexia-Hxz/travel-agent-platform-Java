import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, describe, expect, it, vi } from 'vitest'

import TripDashboard from './TripDashboard.vue'

describe('TripDashboard', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loads and displays the travel projects returned by the API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify([
            {
              id: 7,
              title: '上海国庆游',
              destination: '上海',
              start_date: '2026-10-01',
              end_date: '2026-10-03',
              budget: 3200,
              created_at: '2026-09-05T10:00:00',
            },
          ]),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const wrapper = mount(TripDashboard, {
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('上海国庆游')
    expect(wrapper.text()).toContain('上海')
    expect(wrapper.text()).toContain('预算 3,200 元')
  })

  it('shows a clear validation message when the end date is before the start date', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(TripDashboard, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    fetchMock.mockClear()

    const vm = wrapper.vm as unknown as { form: Record<string, unknown>; submitTrip: () => Promise<void> }
    vm.form.title = '南京3日游'
    vm.form.destination = '南京'
    vm.form.start_date = '2026-10-10'
    vm.form.end_date = '2026-09-14'
    await vm.submitTrip()

    expect(wrapper.text()).toContain('结束日期不能早于出发日期')
    expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining('/trips'), expect.anything())
  })
})
