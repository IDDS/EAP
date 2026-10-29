import { onBeforeUnmount, ref, watch } from 'vue'
import type { AgentPlan, AgentPlanStep } from '@/types/domain'

export function editablePlan(plan: AgentPlan) {
  return !plan.executionRunKey && !plan.executionWorkflowId && ['DRAFT', 'READY'].includes(plan.status)
}

export function reorderedStepIds(steps: AgentPlanStep[], id: number, offset: number) {
  const ids = steps.map(step => step.id)
  const index = ids.indexOf(id)
  const target = index + offset
  if (index < 0 || ![-1, 1].includes(offset) || target < 0 || target >= ids.length) return null
  ;[ids[index], ids[target]] = [ids[target], ids[index]]
  return ids
}

export function orchestrationEditError(error: unknown) {
  if (error === 'cancel' || error === 'close') return ''
  const response = (error as { response?: { status?: number; data?: { message?: string } } })?.response
  if (response?.status === 403) return '当前账号没有配置或执行计划的权限。'
  if ([400, 409, 422].includes(response?.status ?? 0)) return response?.data?.message || '数据已变化，请刷新后重新操作。'
  return '操作结果未确认，请刷新核对；不会自动重试。'
}

// Each editor owns its request and invalidates results on selection or identity changes.
export function useOrchestrationMutation(scope: () => unknown, permitted: () => boolean, working: (value: boolean) => void = () => {}) {
  const busy = ref(false)
  const error = ref('')
  let generation = 0
  let disposed = false
  let notified = false
  function release() { if (notified) { notified = false; working(false) } }
  watch([scope, permitted], () => { ++generation; error.value = '' }, { flush: 'sync' })
  onBeforeUnmount(() => { disposed = true; ++generation; release() })
  async function run<T>(action: (current: () => boolean) => Promise<T>, complete: (result: T) => void) {
    if (disposed || busy.value || !permitted()) return
    const ticket = generation
    const current = () => !disposed && ticket === generation && permitted()
    busy.value = true; error.value = ''; notified = true; working(true)
    try {
      const result = await action(current)
      if (current()) complete(result)
    } catch (cause) { if (current()) error.value = orchestrationEditError(cause) }
    finally { busy.value = false; release() }
  }
  return { busy, error, run }
}
