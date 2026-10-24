import { apiFetch } from './http'

export interface AuthUser {
  id: number
  username: string | null
  email: string | null
  phone: string | null
  display_name: string | null
  created_at: string
  last_login_at: string | null
  email_verified_at: string | null
  phone_verified_at: string | null
}

export interface AuthResult {
  access_token: string
  token_type: string
  expires_in_seconds: number
  user: AuthUser
  claimed_trip_count: number
}

/** What the login/register form collects: one identifier, one password. */
export interface Credentials {
  identifier: string
  password: string
}

async function submit(path: string, credentials: Credentials): Promise<AuthResult> {
  const response = await apiFetch(path, {
    method: 'POST',
    body: JSON.stringify({
      // Trimmed here so a stray space pasted from a password manager is not a failed login.
      identifier: credentials.identifier.trim(),
      password: credentials.password,
    }),
  })
  if (!response.ok) {
    throw new Error(await readDetail(response, '登录失败，请稍后重试。'))
  }
  return (await response.json()) as AuthResult
}

export function registerAccount(credentials: Credentials): Promise<AuthResult> {
  return submit('/auth/register', credentials)
}

export function login(credentials: Credentials): Promise<AuthResult> {
  return submit('/auth/login', credentials)
}

export function fetchCurrentUser(): Promise<AuthUser> {
  return apiFetch('/auth/me').then(async (response) => {
    if (!response.ok) throw new Error(await readDetail(response, '无法获取账号信息。'))
    return (await response.json()) as AuthUser
  })
}

export function updateIdentifiers(payload: { email?: string; phone?: string }): Promise<AuthUser> {
  return apiFetch('/auth/me', { method: 'PATCH', body: JSON.stringify(payload) }).then(async (response) => {
    if (!response.ok) throw new Error(await readDetail(response, '保存失败。'))
    return (await response.json()) as AuthUser
  })
}

/**
 * The backend's own message when it has one.
 *
 * "密码至少 8 位" or "这个邮箱已经注册过了" are far more useful than a generic failure
 * line, and the backend is the only place that knows which rule was broken.
 */
export async function readDetail(response: Response, fallback: string): Promise<string> {
  try {
    const body = (await response.json()) as { detail?: unknown }
    if (typeof body.detail === 'string' && body.detail) return body.detail
  } catch {
    // Not JSON, or already consumed: fall through to the caller's own message.
  }
  return fallback
}
