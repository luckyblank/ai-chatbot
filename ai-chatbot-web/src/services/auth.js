import { reactive } from 'vue'
import { authAPI } from './api'

export const authState = reactive({ user: null, checked: false })

export async function ensureAuth(force = false) {
  if (authState.checked && !force) return authState.user
  try { authState.user = await authAPI.me() }
  catch { authState.user = null }
  finally { authState.checked = true }
  return authState.user
}

export function setAuthenticatedUser(user) { authState.user = user; authState.checked = true }
export function clearAuthenticatedUser() { authState.user = null; authState.checked = true }
