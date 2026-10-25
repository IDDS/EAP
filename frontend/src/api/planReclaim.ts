import { computed, onScopeDispose, readonly, shallowRef, watch } from 'vue'
import { getData } from '@/api/http'

export interface PlanReclaimReadiness {
  planId: number
  allowed: boolean
  steps: readonly { stepId: number; stepOrder: number; allowed: boolean; code: string; message: string }[]
}

export function planSubmissionError(error: unknown): string {
  const response = (error as { response?: { status?: number; data?: { message?: unknown } } } | null)?.response
  if ([400, 401, 403, 409, 422].includes(response?.status ?? 0)) {
    const message = response?.data?.message
    return typeof message === 'string' && message.trim()
      ? `提交被拒绝：${message.slice(0, 500)}` : '提交被拒绝，请核对权限、计划配置与实例状态。'
  }
  return '提交未确认，请刷新核对；再次提交使用同一请求标识，不会重复执行。'
}

/** A current preview, never an execution reservation. The server checks again on submit and dispatch. */
export function usePlanReclaimCheck(options: {
  planId: () => number | undefined
  enabled: () => boolean
  revision: () => unknown
  principal: () => unknown
}) {
  const result = shallowRef<PlanReclaimReadiness>()
  const loading = shallowRef(false)
  const error = shallowRef('')
  let generation = 0
  let disposed = false

  async function refresh() {
    const request = ++generation
    const planId = options.planId()
    result.value = undefined
    error.value = ''
    loading.value = false
    if (disposed || !options.enabled() || planId == null) return
    loading.value = true
    try {
      const data = await getData<PlanReclaimReadiness>(`/orchestration/plans/${planId}/reclaim-readiness`, { silentError: true })
      if (disposed || request !== generation) return
      if (data.planId !== planId) throw new Error('Mismatched plan')
      result.value = data
    } catch {
      if (!disposed && request === generation) error.value = '自动休眠检查未完成，请重新检查；当前不能按此选项提交。'
    } finally {
      if (!disposed && request === generation) loading.value = false
    }
  }

  watch([options.planId, options.enabled, options.revision, options.principal], () => { void refresh() }, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { disposed = true; ++generation; result.value = undefined })
  const blocked = computed(() => options.enabled() && (loading.value || !result.value?.allowed || result.value.planId !== options.planId()))
  return { result: readonly(result), loading: readonly(loading), error: readonly(error), blocked, refresh }
}
