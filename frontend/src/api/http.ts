/**
 * One place that talks to the backend: base URL, the bearer token, and 401 handling.
 *
 * Every api/*.ts module used to build its own `fetch` call site by site. Accounts made
 * that untenable: a request without the token is a 401, and a 401 that nobody notices
 * looks like an empty page. So the token is attached here, once, and a 401 is turned into
 * a single signal -- ``UnauthorizedError`` plus the ``onUnauthorized`` callback that
 * ``useAuth`` registers -- which drops the session and sends the user back to the login
 * screen.
 */

const TOKEN_STORAGE_KEY = 'travel-agent:token'

export const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8001/api/v1'

/** Raised for any 401, so callers can tell "not signed in" from "request failed". */
export class UnauthorizedError extends Error {
  constructor() {
    super('登录状态已过期，请重新登录。')
    this.name = 'UnauthorizedError'
  }
}

let unauthorizedHandler: (() => void) | null = null

/** Registered by useAuth so a 401 anywhere ends the session everywhere. */
export function onUnauthorized(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

export function authToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_STORAGE_KEY)
  } catch {
    // Private-mode browsers can refuse storage. The session then simply does not survive
    // a reload, which is better than a crash on the login screen.
    return null
  }
}

export function setAuthToken(token: string | null): void {
  try {
    if (token === null) window.localStorage.removeItem(TOKEN_STORAGE_KEY)
    else window.localStorage.setItem(TOKEN_STORAGE_KEY, token)
  } catch {
    // Nothing to do: the in-memory session still works for this page.
  }
}

/** The options for a request that needs the backend, with the token already attached. */
export function authorizedInit(init?: RequestInit): RequestInit {
  const token = authToken()
  const headers = new Headers(init?.headers)
  if (!headers.has('Content-Type') && init?.body !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  if (token) headers.set('Authorization', `Bearer ${token}`)
  return { ...init, headers }
}

/**
 * Fetch from the API with the token attached.
 *
 * Only 401 is treated as special; everything else is returned for the calling module to
 * interpret, so each keeps the error message that fits its own feature.
 *
 * The "your session is over" reaction fires only when a token was actually sent. A 401
 * without one is a plain refusal -- a wrong password at the login screen is exactly that
 * -- and must reach the caller so it can show the backend's own message instead of
 * pretending a session expired.
 */
export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  const token = authToken()
  const response = await fetch(`${apiBaseUrl}${path}`, authorizedInit(init))
  if (response.status === 401 && token !== null) {
    setAuthToken(null)
    unauthorizedHandler?.()
    throw new UnauthorizedError()
  }
  return response
}
