import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import CollapsiblePanel from './CollapsiblePanel.vue'
import { forgetPanelPreferences, panelCommand, setAllPanels } from '@/composables/panelState'

function mountPanel(props: Record<string, unknown> = {}, slots: Record<string, string> = {}) {
  return mount(CollapsiblePanel, {
    props: { title: '收藏地点', panelKey: 'places', scope: 7, ...props },
    slots: { default: '<p class="body-content">上海博物馆</p>', ...slots },
    global: { plugins: [ElementPlus] },
  })
}

describe('CollapsiblePanel', () => {
  beforeEach(() => {
    forgetPanelPreferences()
    panelCommand.value = null
  })

  afterEach(() => {
    forgetPanelPreferences()
    panelCommand.value = null
  })

  it('starts open and folds its body away when the header is pressed', async () => {
    const wrapper = mountPanel()
    const body = wrapper.get('.panel-body')

    expect(wrapper.get('.panel-toggle').attributes('aria-expanded')).toBe('true')
    expect(body.attributes('style') ?? '').not.toContain('display: none')

    await wrapper.get('.panel-toggle').trigger('click')

    expect(wrapper.get('.panel-toggle').attributes('aria-expanded')).toBe('false')
    // Hidden, not removed: the content is still in the DOM so nothing downstream
    // (image loading, tests, the app's own lookups) loses it.
    expect(wrapper.get('.panel-body').attributes('style')).toContain('display: none')
    expect(wrapper.find('.body-content').exists()).toBe(true)
    expect(wrapper.text()).toContain('上海博物馆')
  })

  it('can start closed for the long lists, and still shows what is inside', async () => {
    const wrapper = mountPanel({ defaultOpen: false, count: '12 个地点' })
    const toggle = wrapper.get('.panel-toggle')

    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.panel-body').attributes('style')).toContain('display: none')
    // The collapsed header has to say what is being hidden.
    expect(wrapper.get('.panel-count').text()).toBe('12 个地点')
    expect(toggle.attributes('aria-label')).toBe('展开收藏地点')

    // Open, the body already says it, so the badge steps aside.
    await toggle.trigger('click')
    expect(wrapper.get('.collapsible-panel').classes()).not.toContain('is-collapsed')
  })

  it('keeps the header actions reachable while the body is folded', async () => {
    const wrapper = mountPanel(
      { defaultOpen: false },
      { actions: '<button class="header-action">清空收藏</button>' },
    )

    const action = wrapper.get('.header-action')
    expect(action.isVisible()).toBe(true)
    // It must NOT live inside the folded body, or the user could never press it.
    expect(wrapper.get('.panel-body').find('.header-action').exists()).toBe(false)

    await wrapper.get('.panel-toggle').trigger('click')
    expect(wrapper.get('.header-action').isVisible()).toBe(true)
  })

  it('shows a status line even while the body is folded', () => {
    const wrapper = mountPanel(
      { defaultOpen: false },
      { status: '<p class="status-line" role="status">已清空 2 个收藏地点</p>' },
    )

    expect(wrapper.get('.status-line').isVisible()).toBe(true)
    expect(wrapper.get('.panel-body').attributes('style')).toContain('display: none')
  })

  it('remembers the choice for this trip', async () => {
    const first = mountPanel({ defaultOpen: true })
    await first.get('.panel-toggle').trigger('click')
    first.unmount()

    const second = mountPanel({ defaultOpen: true })

    expect(second.get('.panel-toggle').attributes('aria-expanded')).toBe('false')
  })

  it('opens itself for a result that was just produced, without forgetting the choice', async () => {
    const wrapper = mountPanel({ defaultOpen: false, autoOpenToken: 0 })
    expect(wrapper.get('.panel-toggle').attributes('aria-expanded')).toBe('false')

    // A new plan arrived.
    await wrapper.setProps({ autoOpenToken: 1 })
    expect(wrapper.get('.panel-toggle').attributes('aria-expanded')).toBe('true')

    // The user folds it again, and that is what is remembered -- not the auto-open.
    await wrapper.get('.panel-toggle').trigger('click')
    expect(wrapper.get('.panel-toggle').attributes('aria-expanded')).toBe('false')
    wrapper.unmount()

    const reopened = mountPanel({ defaultOpen: true, autoOpenToken: 0 })
    expect(reopened.get('.panel-toggle').attributes('aria-expanded')).toBe('false')
  })

  it('opens a newly mounted result even when a long list was previously folded', async () => {
    const oldPanel = mountPanel({ defaultOpen: false })
    await oldPanel.get('.panel-toggle').trigger('click')
    await oldPanel.get('.panel-toggle').trigger('click')
    oldPanel.unmount()

    // AI results mount after the response arrives, already carrying token 1.
    const result = mountPanel({ defaultOpen: false, autoOpenToken: 1 })
    expect(result.get('.panel-toggle').attributes('aria-expanded')).toBe('true')
    expect(result.get('.panel-body').attributes('style') ?? '').not.toContain('display: none')
  })

  it('follows the one 全部收起 / 全部展开 button', async () => {
    const first = mountPanel({ panelKey: 'places' })
    const second = mountPanel({ panelKey: 'itinerary', title: '每日行程' })

    setAllPanels('close')
    await flushPromises()
    expect(first.get('.panel-toggle').attributes('aria-expanded')).toBe('false')
    expect(second.get('.panel-toggle').attributes('aria-expanded')).toBe('false')

    setAllPanels('open')
    await flushPromises()
    expect(first.get('.panel-toggle').attributes('aria-expanded')).toBe('true')
    expect(second.get('.panel-toggle').attributes('aria-expanded')).toBe('true')

    // The sweep is an explicit choice, so it is remembered too.
    setAllPanels('close')
    await flushPromises()
    expect(first.get('.panel-toggle').attributes('aria-expanded')).toBe('false')
    first.unmount()
    const remounted = mountPanel({ panelKey: 'places', defaultOpen: true })
    expect(remounted.get('.panel-toggle').attributes('aria-expanded')).toBe('false')
  })
})
