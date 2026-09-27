import { reactive, readonly } from 'vue'
import { getData } from '@/api/http'
import { getAccessToken } from '@/api/auth'
import { hasCapability } from '@/api/capabilities'
import type { Capability, CurrentUser } from '@/api/capabilities'

const state = reactive<{ user: CurrentUser | null; loading: boolean }>({ user: null, loading: false })
let revision = 0
let pending: Promise<CurrentUser | null> | undefined

export function resetPermissions() {
  revision++
  state.user = null
  state.loading = false
  pending = undefined
}

window.addEventListener('eap-auth-changed', resetPermissions)

export function loadCurrentUser(): Promise<CurrentUser | null> {
  if (pending) return pending
  const requestRevision = ++revision
  const token = getAccessToken()
  state.user = null
  state.loading = true
  pending = getData<CurrentUser>('/platform/me').then(user => {
    if (requestRevision !== revision || token !== getAccessToken()) return null
    state.user = user
    return user
  }).finally(() => {
    if (requestRevision === revision) {
      state.loading = false
      pending = undefined
    }
  })
  return pending
}

export function usePermissions() {
  return { state: readonly(state), can: (capability: Capability) => hasCapability(state.user?.capabilities, capability) }
}
