import { onScopeDispose, shallowReadonly, shallowRef, watch } from 'vue'
import { usePermissions } from '@/stores/permissions'

/** A read belongs to one filter and one authenticated session, including late failures. */
export function useExecutionQuery<T>(key: () => string, read: (key: string, signal: AbortSignal) => Promise<T>) {
  const permissions = usePermissions()
  const data = shallowRef<T | null>(null)
  const loading = shallowRef(false)
  const error = shallowRef('')
  let revision = 0
  let disposed = false
  let controller: AbortController | undefined
  function clear() {
    revision++
    controller?.abort()
    data.value = null
    error.value = ''
    loading.value = false
  }
  async function refresh() {
    clear()
    const query = key(), user = permissions.state.user, version = revision
    if (disposed || !query || !permissions.can('viewConsole')) return
    controller = new AbortController()
    const signal = controller.signal
    const owns = () => !disposed && version === revision && query === key() && user === permissions.state.user && permissions.can('viewConsole')
    loading.value = true
    try {
      const result = await read(query, signal)
      if (owns()) data.value = result
    } catch {
      if (owns()) error.value = '执行观测读取失败，请重试；未将失败显示为空记录。'
    } finally {
      if (owns()) loading.value = false
    }
  }
  watch([key, () => permissions.state.user, () => permissions.can('viewConsole')], refresh, { immediate: true, flush: 'sync' })
  onScopeDispose(() => { disposed = true; clear() })
  return { data: shallowReadonly(data), loading: shallowReadonly(loading), error: shallowReadonly(error), refresh }
}
