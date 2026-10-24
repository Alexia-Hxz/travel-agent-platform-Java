import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import App from './App.vue'
import { setAuthToken } from '@/api/http'
import { logout } from '@/composables/useAuth'

const account = {
  id: 7,
  username: 'traveler',
  email: null,
  phone: null,
  display_name: null,
  created_at: '2026-01-01T00:00:00',
  last_login_at: null,
  email_verified_at: null,
  phone_verified_at: null,
}

function stubApi(routes: Record<string, unknown>): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input)
    const match = Object.keys(routes).find((path) => url.includes(path))
    if (match === undefined) return Promise.resolve(new Response('{}', { status: 404 }))
    return Promise.resolve(
      new Response(JSON.stringify(routes[match]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

describe('App', () => {
  beforeEach(() => {
    setAuthToken(null)
    logout()
  })

  afterEach(() => {
    setAuthToken(null)
    logout()
    vi.unstubAllGlobals()
  })

  it('shows the login screen when there is no session, and no request is made', async () => {
    const fetchMock = stubApi({})

    const wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('智能旅行规划平台')
    expect(wrapper.find('button[aria-label="登录"]').exists()).toBe(true)
    // Without a token there is nothing to verify, so the app must not call the API at all.
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('restores a stored session and then shows the trip list', async () => {
    const fetchMock = stubApi({ '/auth/me': account, '/trips': [] })
    setAuthToken('stored-token')

    const wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(fetchMock.mock.calls[0][0]).toContain('/auth/me')
    expect(wrapper.get('h1').text()).toBe('我的旅行')
    expect(wrapper.text()).toContain('新建旅行')
    // The chip tells the user which account they are in, and offers the way out.
    expect(wrapper.get('[aria-label="当前账号"]').text()).toContain('traveler')
    expect(wrapper.find('button[aria-label="设置"]').exists()).toBe(true)
  })

  it('drops an expired session instead of showing an empty app', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 401 }))
    vi.stubGlobal('fetch', fetchMock)
    setAuthToken('expired-token')

    const wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('智能旅行规划平台')
    expect(window.localStorage.getItem('travel-agent:token')).toBeNull()
  })

  it('signs out from the dashboard and returns to the login screen', async () => {
    stubApi({ '/auth/me': account, '/trips': [] })
    setAuthToken('stored-token')

    const wrapper = mount(App, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('我的旅行')

    await wrapper.get('button[aria-label="设置"]').trigger('click')
    await flushPromises()
    ;(document.querySelector('button[aria-label="退出登录"]') as HTMLButtonElement).click()
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('智能旅行规划平台')
    expect(window.localStorage.getItem('travel-agent:token')).toBeNull()
  })
})
