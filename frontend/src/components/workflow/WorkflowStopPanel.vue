<template>
  <section class="workflow-stop" aria-label="停止后续调度">
    <div class="stop-heading"><h3>停止后续调度</h3><el-button :disabled="loading || submitting || !permissions.can('viewConsole')" @click="load">刷新状态</el-button></div>
    <p>排队中的执行立即停止；运行中的执行在当前步骤结束后停止后续调度。已发生的外部效果与预算记录保留。</p>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <p v-if="loading && !report" role="status">正在核对停止状态…</p>
    <template v-if="report">
      <p class="stop-state" role="status">{{ stateLabel(report.state) }}</p>
      <p v-if="report.requestedMs">请求时间：{{ new Date(report.requestedMs).toLocaleString() }}<template v-if="report.appliedMs"> · 生效时间：{{ new Date(report.appliedMs).toLocaleString() }}</template></p>
      <el-button v-if="report.supported && report.state === 'NONE' && active(report.executionStatus)"
        type="warning" :loading="submitting" :disabled="!canStop" @click="stop">停止后续调度</el-button>
      <p v-if="report.state === 'NONE' && !active(report.executionStatus)">执行已结束或结果未知，请按原任务标识核对。</p>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { getWorkflowStop, requestWorkflowStop } from '@/api/workflowStop'
import type { WorkflowStopReport } from '@/api/workflowStop'
import { usePermissions } from '@/stores/permissions'

const props = defineProps<{ workflowId: number; runKey: string; status: string }>()
const emit = defineEmits<{ changed: [] }>()
const permissions = usePermissions()
const report = ref<WorkflowStopReport | null>(null)
const loading = ref(false), submitting = ref(false), error = ref('')
let revision = 0, disposed = false, controller: AbortController | undefined
const canStop = computed(() => !disposed && !loading.value && !submitting.value && permissions.can('runWorkflows')
  && report.value?.supported && report.value.state === 'NONE' && active(report.value.executionStatus))

function active(status: string) { return ['QUEUED', 'RUNNING'].includes(status) }
function clear() {
  revision++; controller?.abort(); controller = undefined
  report.value = null; loading.value = false; submitting.value = false; error.value = ''
}
function current(id: number, workflowId: number, runKey: string) {
  return !disposed && id === revision && workflowId === props.workflowId && runKey === props.runKey && permissions.can('viewConsole')
}
async function load() {
  if (disposed || loading.value || submitting.value || !permissions.can('viewConsole')) return
  const id = ++revision, workflowId = props.workflowId, runKey = props.runKey
  controller?.abort(); controller = new AbortController(); loading.value = true; error.value = ''
  try {
    const value = await getWorkflowStop(workflowId, runKey, controller.signal)
    if (current(id, workflowId, runKey)) report.value = value
  } catch {
    if (current(id, workflowId, runKey)) { report.value = null; error.value = '停止状态读取失败，不能推定已停止；请刷新核对。' }
  } finally { if (id === revision) loading.value = false }
}
async function stop() {
  if (!canStop.value) return
  const id = ++revision, workflowId = props.workflowId, runKey = props.runKey
  // One fixed key for this immutable execution, including after refresh or lost HTTP response.
  const requestKey = `stop-${runKey}`
  submitting.value = true; error.value = ''; controller?.abort()
  try {
    const value = await requestWorkflowStop(workflowId, runKey, requestKey)
    if (current(id, workflowId, runKey)) { report.value = value; emit('changed') }
  } catch {
    if (current(id, workflowId, runKey)) {
      report.value = null
      error.value = '停止请求未确认，正在核对原请求；不会自动重新提交。'
      controller = new AbortController()
      try {
        const value = await getWorkflowStop(workflowId, runKey, controller.signal)
        if (current(id, workflowId, runKey)) {
          report.value = value
          if (value.requestKey) { error.value = ''; emit('changed') }
          else error.value = '尚未查询到停止请求；如需重试，将沿用同一请求键。'
        }
      } catch {
        if (current(id, workflowId, runKey)) error.value = '停止请求及状态均未确认；请刷新核对，当前任务可能仍在执行。'
      }
    }
  } finally { if (id === revision) submitting.value = false }
}
function stateLabel(state: string) {
  return ({ UNSUPPORTED: '历史执行未加入停止协议，原状态保持。', NONE: '尚无停止请求。',
    REQUESTED: '已登记停止请求，等待当前步骤结束；当前任务可能仍在执行。',
    APPLIED: '已停止后续调度；既有任务结果与外部效果保留。',
    UNCONFIRMED: '停止未确认 · 执行责任过期或结果未知，请核对原任务。',
    FINISHED: '执行已自行结束，请以原执行结果为准；未确认由停止请求终止。' } as Record<string, string>)[state] || '停止状态未知，请核对原任务。'
}
watch([() => props.workflowId, () => props.runKey, () => permissions.state.user, () => permissions.can('viewConsole'), () => permissions.can('runWorkflows')],
  () => { clear(); void load() }, { immediate: true, flush: 'sync' })
watch(() => props.status, () => { void load() })
const timer = setInterval(() => { if (active(props.status) || report.value?.state === 'REQUESTED') void load() }, 3000)
onUnmounted(() => { disposed = true; clear(); clearInterval(timer) })
</script>
<style scoped>
.workflow-stop{border-top:1px solid #dfe7ee;padding-top:18px;margin-top:22px}.stop-heading{display:flex;justify-content:space-between;gap:12px;align-items:center}.stop-heading h3{margin:0;font-size:16px}.workflow-stop p{font-size:13px;line-height:1.7;color:#64748b;overflow-wrap:anywhere}.workflow-stop .stop-state{color:#334155;font-weight:600}@media(max-width:600px){.stop-heading{flex-wrap:wrap}}
</style>
