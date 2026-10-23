import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

const WINDOW_KEY = 'eap-agentops-window-hours'
const SCOPE_KEY = 'eap-agentops-organization-scope'
const allowedWindows = [1, 6, 24, 72, 168]

function initialWindow() {
  const parsed = Number(localStorage.getItem(WINDOW_KEY))
  return allowedWindows.includes(parsed) ? parsed : 24
}

export const useAgentOpsContextStore = defineStore('agentOpsContext', () => {
  const windowHours = ref(initialWindow())
  const organizationScope = ref(localStorage.getItem(SCOPE_KEY) || '')

  const windowLabel = computed(() => {
    if (windowHours.value < 24) return `最近 ${windowHours.value} 小时`
    if (windowHours.value === 24) return '最近 24 小时'
    return `最近 ${Math.round(windowHours.value / 24)} 天`
  })
  const scopeLabel = computed(() => organizationScope.value.trim() || '企业全局')

  function setWindowHours(value: number) {
    if (!allowedWindows.includes(value)) return
    windowHours.value = value
    localStorage.setItem(WINDOW_KEY, String(value))
  }

  function setOrganizationScope(value: string) {
    organizationScope.value = value.trim()
    if (organizationScope.value) localStorage.setItem(SCOPE_KEY, organizationScope.value)
    else localStorage.removeItem(SCOPE_KEY)
  }

  return {
    windowHours,
    organizationScope,
    windowLabel,
    scopeLabel,
    setWindowHours,
    setOrganizationScope,
  }
})
