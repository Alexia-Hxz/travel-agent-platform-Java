import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import LoginView from './LoginView.vue'
import { setAuthToken } from '@/api/http'
import { logout, useAuth } from '@/composables/useAuth'

const account = {
  id: 12,
  username: 'traveler',
  email: null,
  phone: null,
  display_name: null,
  created_at: '2026-01-01T00:00:00',
  last_login_at: null,
  email_verified_at: null,
  phone_verified_at: null,
}

function stub(
  handler: (url: string, init?: RequestInit) => { status: number; body?: unknown },
): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
    const result = handler(String(input), init)
    return Promise.resolve(
      new Response(result.body === undefined ? '' : JSON.stringify(result.body), {
        status: result.status,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

async function fill(wrapper: ReturnType<typeof mount>, identifier: string, password: string) {
  await wrapper.get('input[name="identifier"]').setValue(identifier)
  await wrapper.get('input[name="password"]').setValue(password)
}

describe('LoginView', () => {
  beforeEach(() => {
    setAuthToken(null)
    logout()
  })

  afterEach(() => {
    setAuthToken(null)
    logout()
    vi.unstubAllGlobals()
  })

  it('offers one field that accepts a username, an email or a phone number', () => {
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus] } })

    const identifier = wrapper.get('input[name="identifier"]')
    expect(identifier.attributes('placeholder')).toContain('用户名 / 邮箱 / 手机号')
    expect(wrapper.get('input[name="password"]').attributes('type')).toBe('password')
    // Nothing typed yet: the submit button must not pretend to be ready.
    expect(wrapper.get('button[aria-label="登录"]').attributes('disabled')).toBeDefined()
  })

  it('signs in and stores the token, without keeping the password in the form', async () => {
    const fetchMock = stub(() => ({ status: 200, body: { access_token: 'fresh-token', user: account } }))
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus] } })

    await fill(wrapper, 'traveler', 'correct-horse-battery')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(String(fetchMock.mock.calls[0][0])).toContain('/auth/login')
    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)
    expect(body).toEqual({ identifier: 'traveler', password: 'correct-horse-battery' })
    expect(window.localStorage.getItem('travel-agent:token')).toBe('fresh-token')
    expect(useAuth().user.value?.id).toBe(12)
    expect((wrapper.get('input[name="password"]').element as HTMLInputElement).value).toBe('')
  })

  it('sends an email or a phone number through the same field', async () => {
    const fetchMock = stub(() => ({ status: 200, body: { access_token: 't', user: account } }))
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus] } })

    await fill(wrapper, '13800138000', 'correct-horse-battery')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string).identifier).toBe('13800138000')
  })

  it('registers through the same form once the register tab is chosen', async () => {
    const fetchMock = stub(() => ({ status: 201, body: { access_token: 'new-token', user: account } }))
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus] } })

    await wrapper.get('button[aria-label="切换到注册"]').trigger('click')
    expect(wrapper.text()).toContain('任意一种注册')
    await fill(wrapper, '13800138000', 'correct-horse-battery')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(String(fetchMock.mock.calls[0][0])).toContain('/auth/register')
    expect(window.localStorage.getItem('travel-agent:token')).toBe('new-token')
  })

  it('shows the backend message when the backend refuses', async () => {
    stub(() => ({ status: 401, body: { detail: '用户名/邮箱/手机号或密码不正确。' } }))
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus] } })

    await fill(wrapper, 'traveler', 'wrong-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('用户名/邮箱/手机号或密码不正确。')
    expect(window.localStorage.getItem('travel-agent:token')).toBeNull()
  })

  it('shows the registration rule when a password is too short', async () => {
    stub(() => ({ status: 422, body: { detail: '密码至少 8 位。' } }))
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus] } })

    await wrapper.get('button[aria-label="切换到注册"]').trigger('click')
    await fill(wrapper, 'new-traveler', 'short12')
    // Seven characters: the form refuses before spending a request on it.
    expect(wrapper.get('button[aria-label="注册并登录"]').attributes('disabled')).toBeDefined()

    await wrapper.get('input[name="password"]').setValue('1234567')
    expect(wrapper.get('button[aria-label="注册并登录"]').attributes('disabled')).toBeDefined()
  })

  it('clears a stale error when switching between login and register', async () => {
    stub(() => ({ status: 401, body: { detail: '用户名/邮箱/手机号或密码不正确。' } }))
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus] } })

    await fill(wrapper, 'traveler', 'wrong-password')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)

    await wrapper.get('button[aria-label="切换到注册"]').trigger('click')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('can reveal the password instead of asking for it twice', async () => {
    const wrapper = mount(LoginView, { global: { plugins: [ElementPlus] } })

    expect(wrapper.get('input[name="password"]').attributes('type')).toBe('password')
    await wrapper.get('[aria-label="显示密码"] input').setValue(true)
    expect(wrapper.get('input[name="password"]').attributes('type')).toBe('text')
  })
})
