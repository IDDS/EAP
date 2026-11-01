<template>
  <section class="tool-recovery-facts" aria-label="原工具步骤核验">
    <div class="recovery-heading"><h3>原工具步骤核验</h3><el-button :loading="loading" :disabled="!permissions.can('viewConsole')" @click="load">刷新核验记录</el-button></div>
    <p class="muted">查询责任保存在平台中，中断后继续核对原操作。确认工具效果不代表工作流已经恢复，也不会自动派发后续节点。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="report">
      <p v-if="!report.enrolled" class="muted">此历史执行未加入持久核验协议，原结果保持。</p>
      <p v-else-if="!report.entries.length" class="muted">暂无已登记的受控工具步骤；普通模型及其他工具不在此协议范围。</p>
      <article v-for="entry in report.entries" :key="entry.sequence" class="recovery-entry">
        <div class="recovery-heading"><strong>{{ entry.nodeKey }}</strong><el-tag :type="entry.state === 'CONFIRMED' ? 'success' : entry.state === 'NO_COMMIT' ? 'info' : 'warning'">{{ stateLabel(entry.state) }}</el-tag></div>
        <p>{{ entry.reason }}</p>
        <dl><dt>原操作</dt><dd>{{ entry.operationId }}</dd><dt>原授权根</dt><dd>{{ entry.rootId }}</dd><dt>原预算</dt><dd>{{ entry.budgetKey }}</dd><dt>绑定实例 UID</dt><dd>{{ entry.actorUid }}</dd><dt>查询责任</dt><dd>平台查询队列 · 第 {{ entry.queryEpoch }} 轮</dd><dt>最近核验</dt><dd>{{ entry.observedAt ? new Date(entry.observedAt).toLocaleString() : '尚未核验' }}</dd><template v-if="entry.state === 'CONFIRMED' && entry.operation?.effectId"><dt>原效果</dt><dd>{{ entry.operation.effectId }}</dd><dt>原计数值</dt><dd>{{ entry.operation.counterValue }}</dd></template></dl>
      </article>
      <p class="muted">{{ report.responsibility }}</p>
    </template>
  </section>
</template>
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import type { WorkflowToolRecovery } from '@/types/domain'
const props = defineProps<{ workflowId: number; runKey: string }>()
const permissions = usePermissions()
const report = ref<WorkflowToolRecovery | null>(null)
const loading = ref(false), error = ref('')
let revision = 0, disposed = false, retryPending = false
function clear() { revision++; report.value = null; loading.value = false; error.value = ''; retryPending = false }
function validReport(value: unknown, workflowId: number, runKey: string): value is WorkflowToolRecovery {
  const object = (item: unknown): item is Record<string, unknown> => typeof item === 'object' && item !== null && !Array.isArray(item)
  const nonempty = (item: unknown) => typeof item === 'string' && item.length > 0
  if (!object(value) || value.contract !== 'workflow-tool-query-v1' || value.mode !== 'QUERY_ONLY' ||
      value.workflowId !== workflowId || value.runKey !== runKey || typeof value.enrolled !== 'boolean' ||
      typeof value.responsibility !== 'string' || !Array.isArray(value.entries) || (!value.enrolled && value.entries.length > 0)) return false
  const sequences = new Set<number>()
  for (const entry of value.entries) {
    if (!object(entry) || !Number.isSafeInteger(entry.sequence) || (entry.sequence as number) < 0 || sequences.has(entry.sequence as number) ||
        !['nodeKey','taskKey','rootId','operationId','holderKey','actorUid','contractVersion','budgetKey'].every(key => nonempty(entry[key])) ||
        typeof entry.state !== 'string' || !['PENDING','CHECKING','UNKNOWN','CONFIRMED','NO_COMMIT'].includes(entry.state) ||
        typeof entry.reason !== 'string' || !Number.isSafeInteger(entry.queryEpoch) || (entry.queryEpoch as number) < 0 ||
        (entry.observedAt != null && (typeof entry.observedAt !== 'string' || !Number.isFinite(Date.parse(entry.observedAt))))) return false
    sequences.add(entry.sequence as number)
    const operation = entry.operation
    if (operation != null && (!object(operation) || operation.rootId !== entry.rootId || operation.operationId !== entry.operationId)) return false
    if (entry.state === 'CONFIRMED' && (!object(operation) || operation.state !== 'COMMITTED' || operation.effectSource !== 'TOOL_STORE_VERIFIED' ||
        !nonempty(operation.effectId) || !Number.isSafeInteger(operation.counterValue))) return false
    if (entry.state === 'NO_COMMIT' && (!object(operation) || operation.state !== 'REJECTED' || operation.effectSource !== 'TOOL_STORE_NO_COMMIT')) return false
  }
  return true
}
async function load() {
  if (disposed || loading.value || !permissions.can('viewConsole')) return
  const request = ++revision, workflowId = props.workflowId, runKey = props.runKey
  loading.value = true; report.value = null
  try {
    const result = await EapApi.getWorkflowToolRecovery(workflowId, runKey)
    if (!disposed && request === revision && workflowId === props.workflowId && runKey === props.runKey && permissions.can('viewConsole')) {
      if (!validReport(result, workflowId, runKey)) {
        retryPending = false; error.value = '核验响应与原执行或协议不匹配，不能确认原操作；请核对服务版本或联系管理员。'; return
      }
      const pending = result.entries.some(entry => ['PENDING','CHECKING','UNKNOWN'].includes(entry.state))
      report.value = result; error.value = ''; retryPending = pending
    }
  } catch (failure) {
    if (!disposed && request === revision && workflowId === props.workflowId && runKey === props.runKey && permissions.can('viewConsole')) {
      const status = (failure as { response?: { status?: number } } | null)?.response?.status
      if (status && status >= 400 && status < 500 && status !== 408 && status !== 429) retryPending = false
      error.value = retryPending ? '核验记录读取失败，不能推定原操作未提交；将继续尝试读取。' : '核验记录读取失败，不能推定原操作未提交；请刷新重试，或联系管理员核对权限。'
    }
  }
  finally { if (request === revision) loading.value = false }
}
function poll() { if (retryPending) return load() }
function stateLabel(state: string) { return ({ PENDING: '等待核验', CHECKING: '正在核验', UNKNOWN: '效果未知 · 保留责任', CONFIRMED: '原效果已确认', NO_COMMIT: '原事务明确未提交' } as Record<string,string>)[state] || state }
watch([() => props.workflowId, () => props.runKey, () => permissions.state.user, () => permissions.can('viewConsole')], () => { clear(); void load() }, { immediate: true, flush: 'sync' })
const timer = setInterval(poll, 5000)
onUnmounted(() => { disposed = true; clear(); clearInterval(timer) })
</script>
<style scoped>
.tool-recovery-facts{margin-top:22px;padding-top:18px;border-top:1px solid #dfe7ee}.recovery-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.recovery-heading h3{font-size:16px;margin:0}.recovery-entry{border:1px solid #dfe7ee;border-radius:6px;padding:16px;margin:14px 0;background:#f7f9fc}.recovery-entry p,.muted{font-size:13px;color:#64748b;line-height:1.7}.recovery-entry dl{display:grid;grid-template-columns:110px minmax(0,1fr);gap:8px;font-size:13px}.recovery-entry dt{color:#64748b}.recovery-entry dd{margin:0;overflow-wrap:anywhere}@media(max-width:600px){.recovery-heading{align-items:flex-start;flex-wrap:wrap}.recovery-entry dl{grid-template-columns:1fr}.recovery-entry dd{margin-bottom:6px}}
</style>
