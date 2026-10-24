/**
 * Who is signed in, for the whole app.
 *
 * The state lives at module level (like ``panelState`` and ``planningHandoff``) so the
 * dashboard, the login screen, and the api layer all read the same answer without props
 * being threaded through every component.
 *
 * The token itself lives in ``localStorage`` and is handled by ``api/http``; this module
 * only decides when one exists and who it belongs to.
 */

import { readonly, ref } from 'vue'

import { fetchCurrentUser, login as loginRequest, registerAccount, type AuthUser, type Credentials } from '@/api/auth'
import { authToken, onUnauthorized, setAuthToken } from '@/api/http'

const user = ref<AuthUser | null>(null)
const restoring = ref(false)

/** A 401 from anywhere in the app ends the session here, once. */
onUnauthorized(() => {
  user.value = null
})

export function useAuth() {
  return {
    user: readonly(user),
    restoring: readonly(restoring),
    login,
    register,
    logout,
  }
}

/** The signed-in account, for code that needs it outside a component. */
export function currentUser(): AuthUser | null {
  return user.value
}

/**
 * Turn a token into an account, or clear it.
 *
 * Called once on start-up. A stored token that the backend no longer accepts (expired,
 * signed with an old secret, account deleted) is dropped silently: the user sees the
 * login screen, which is exactly right.
 */
export async function restoreSession(): Promise<void> {
  if (!authToken()) {
    user.value = null
    return
  }
  restoring.value = true
  try {
    user.value = await fetchCurrentUser()
  } catch {
    setAuthToken(null)
    user.value = null
  } finally {
    restoring.value = false
  }
}

async function adoptSession(request: Promise<{ access_token: string; user: AuthUser }>): Promise<AuthUser> {
  const result = await request
  setAuthToken(result.access_token)
  user.value = result.user
  return result.user
}

export function login(credentials: Credentials): Promise<AuthUser> {
  return adoptSession(loginRequest(credentials))
}

export function register(credentials: Credentials): Promise<AuthUser> {
  return adoptSession(registerAccount(credentials))
}

/**
 * Sign out.
 *
 * The token is signed, not stored on the server, so there is nothing to revoke: dropping
 * it on this device is the whole operation. Any request made with a copy of it would
 * still work until it expires, which is the trade a stateless token makes.
 */
export function logout(): void {
  setAuthToken(null)
  user.value = null
}
