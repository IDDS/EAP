<template>
  <section class="operation-alerts" aria-label="原操作告警与审计">
    <div class="alert-heading"><h3>原操作告警与审计</h3><el-button :loading="loading" :disabled="writing || !permissions.can('viewConsole')" @click="load">刷新告警</el-button></div>
    <p class="alert-note">确认表示已阅读，业务结果、费用和窗口告警继续按原事实保留。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="notice" class="alert-note" role="status">{{ notice }}</p>
    <template v-if="data">
      <el-button v-if="permissions.can('governance')" :loading="writing" :disabled="loading || writing" @click="reconcile">重新核对事实</el-button>
      <p v-if="data.reconciledRevision === null" class="alert-note">此原操作尚未协调告警，不能据此判断没有问题。</p>
      <p v-else-if="!data.reconciliationCurrent" class="alert-stale" role="status">原事实已变化，以下记录属于先前版本，尚待重新协调。</p>
      <p v-else class="alert-note">已核对当前事实 · 版本 {{ data.reconciledRevision }}</p>
      <p v-if="data.reconciliationCurrent && !data.alerts.length" class="alert-note">已协调当前事实，此原操作没有告警记录。</p>
      <p v-if="data.alertsTruncated" class="alert-stale">告警只显示最近 100 条，较早记录未在此处列出。</p>
      <ul v-if="data.alerts.length" class="alert-records">
        <li v-for="alert in data.alerts" :key="alert.id" :data-alert-id="alert.id">
          <div class="alert-title"><strong>{{ kind(alert.kind) }}</strong><span>{{ lifecycle(alert.lifecycleState) }}</span></div>
          <p>告警 #{{ alert.id }} · 事实版本 {{ alert.factRevision }} · {{ outcome(alert.executionStatus) }}</p>
          <p>创建 {{ time(alert.createdMs) }} · 更新 {{ time(alert.changedMs) }}</p>
          <el-button v-if="permissions.can('governance') && alert.lifecycleState === 'ACTIVE'" :disabled="loading || writing || !acknowledgeable(alert.id)" @click="reviewAcknowledgement(alert.id)">确认阅读</el-button>
        </li>
      </ul>
      <div v-if="pending" class="alert-confirmation" role="group" aria-label="确认当前版本阅读">
        <p>确认已阅读原操作 #{{ pending.operation }} 的告警 #{{ pending.alert }}（事实版本 {{ pending.factRevision }}）。这只记录阅读确认，业务结果、费用和窗口告警继续保留。</p>
        <el-button :disabled="writing" @click="pending = null">取消确认</el-button>
        <el-button type="primary" :disabled="writing" @click="acknowledge">确认已阅读此版本</el-button>
      </div>
      <h4>确认与事实变更记录</h4>
      <p v-if="data.auditTruncated" class="alert-stale">审计只显示最近 200 条，较早记录未在此处列出。</p>
      <p v-if="!data.audit.length" class="alert-note">暂无持久审计记录。</p>
      <ol v-else class="alert-audit"><li v-for="event in data.audit" :key="`${event.alertId}:${event.event}`"><strong>#{{ event.alertId }} · {{ lifecycle(event.event) }}</strong><time>{{ time(event.createdMs) }}</time><small>主体摘要 {{ event.principalDigest.slice(0, 12) }}</small></li></ol>
    </template>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { acknowledgeObservationAlert, getObservationAlerts, reconcileObservationAlerts, type ObservationAlertView } from '@/api/observationAlerts'
import { usePermissions } from '@/stores/permissions'
const props = defineProps<{ operationId: number }>()
const permissions = usePermissions()
const data = ref<ObservationAlertView | null>(null), loading = ref(false), error = ref('')
const writing = ref(false), notice = ref('')
type ReviewedFact = { operation: number; alert: number; factRevision: number; factDigest: string }
const pending = ref<ReviewedFact | null>(null)
let generation = 0, alive = true
function clear() { generation++; data.value = null; loading.value = false; writing.value = false; pending.value = null; error.value = ''; notice.value = '' }
function current(run: number, user: unknown, operation: number) {
  return alive && run === generation && user === permissions.state.user && operation === props.operationId && permissions.can('viewConsole')
}
async function load() {
  if (writing.value) return
  if (!alive || !permissions.can('viewConsole')) { clear(); return }
  const run = ++generation, user = permissions.state.user, operation = props.operationId
  data.value = null; pending.value = null; error.value = ''; notice.value = ''; loading.value = true
  try {
    const result = await getObservationAlerts(operation)
    if (!current(run, user, operation)) return
    if (result.operation.id !== operation) throw new Error('operation mismatch')
    data.value = result
  } catch {
    if (current(run, user, operation)) { data.value = null; error.value = '原操作告警读取失败，请刷新重试' }
  } finally { if (current(run, user, operation)) loading.value = false }
}
function acknowledgeable(id: number) {
  const view = data.value
  return alive && permissions.can('viewConsole') && permissions.can('governance') && !loading.value && !writing.value && !!view
    && view.operation.id === props.operationId && view.reconciliationCurrent
    && Number.isSafeInteger(view.reconciledRevision) && view.reconciledRevision! > 0
    && /^[a-f0-9]{64}$/.test(view.reconciledFactDigest || '') && view.observedFactDigest === view.reconciledFactDigest
    && view.alerts.some(a => a.id === id && a.lifecycleState === 'ACTIVE' && a.factRevision === view.reconciledRevision && a.factDigest === view.reconciledFactDigest)
}
function reviewAcknowledgement(id: number) {
  if (!acknowledgeable(id)) return
  pending.value = { operation: props.operationId, alert: id, factRevision: data.value!.reconciledRevision!, factDigest: data.value!.reconciledFactDigest! }
  notice.value = ''
}
async function reconcile() { await write() }
async function acknowledge() {
  const fact = pending.value
  if (!fact || fact.operation !== props.operationId || !acknowledgeable(fact.alert)
      || fact.factRevision !== data.value!.reconciledRevision || fact.factDigest !== data.value!.reconciledFactDigest) { pending.value = null; return }
  await write(fact)
}
async function write(fact?: ReviewedFact) {
  if (!alive || loading.value || writing.value || !data.value || data.value.operation.id !== props.operationId
      || !permissions.can('viewConsole') || !permissions.can('governance')) return
  const run = ++generation, user = permissions.state.user, operation = props.operationId
  pending.value = null; error.value = ''; notice.value = ''; writing.value = true
  try {
    const result = fact
      ? await acknowledgeObservationAlert(operation, fact.alert, { factRevision: fact.factRevision, factDigest: fact.factDigest })
      : await reconcileObservationAlerts(operation)
    if (!current(run, user, operation) || !permissions.can('governance')) return
    if (result.operation.id !== operation || (fact && !result.alerts.some(a => a.id === fact.alert && a.factRevision === fact.factRevision && a.factDigest === fact.factDigest && a.lifecycleState === 'ACKNOWLEDGED'))) throw new Error('write response mismatch')
    data.value = result
    notice.value = fact ? '已确认阅读此版本；原业务结果和费用继续保留。' : '已重新核对原操作事实。'
  } catch (failure) {
    if (current(run, user, operation) && permissions.can('governance')) {
      data.value = null
      const status = (failure as { response?: { status?: number } } | null)?.response?.status
      error.value = status === 409 ? '原事实或版本已变化，确认未提交成功。请刷新核对，再重新确认阅读。'
        : status === 401 || status === 403 ? '当前账号已无法执行此操作，请刷新权限与告警。'
        : '请求结果尚无法确认，请刷新告警与审计核实是否已记录；页面不会自动重试。'
    }
  } finally { if (current(run, user, operation)) writing.value = false }
}
function kind(value: string) { return ({ EVIDENCE_GAP: '观测证据缺口', EXECUTION_UNKNOWN: '执行结果未知' } as Record<string, string>)[value] || value }
function lifecycle(value: string) { return ({ ACTIVE: '待确认', ACKNOWLEDGED: '已确认阅读', SUPERSEDED: '事实已替代', RESOLVED: '原事实已恢复', CREATED: '告警建立' } as Record<string, string>)[value] || value }
function outcome(value: string) { return ({ UNKNOWN: '业务结果未知', EXECUTION_UNKNOWN: '业务结果未知', FAILED: '业务失败', SUCCEEDED: '业务成功', COMPLETED: '执行结束' } as Record<string, string>)[value] || value }
function time(value: number) { return new Date(value).toLocaleString('zh-CN') }
watch([() => props.operationId, () => permissions.state.user, () => permissions.can('viewConsole'), () => permissions.can('governance')], () => { clear(); void load() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { alive = false; clear() })
</script>
<style scoped>
.operation-alerts{margin-top:24px;border-top:1px solid #e2e8f0;padding-top:8px;min-width:0}.alert-heading,.alert-title{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap}.alert-heading h3{margin:16px 0}.alert-note,.alert-records p,.alert-audit small,.alert-audit time{color:#64748b;font-size:13px;line-height:1.7}.alert-stale{color:#92400e;font-size:13px;line-height:1.7}.alert-records{list-style:none;padding:0;display:grid;gap:12px}.alert-records li{padding:14px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;overflow-wrap:anywhere}.alert-title span{font-size:13px;color:#475569}.alert-records p{margin:8px 0 0}.alert-audit{padding-left:22px}.alert-audit li{display:grid;gap:4px;margin:14px 0;overflow-wrap:anywhere}.alert-audit time,.alert-audit small{display:block}
.alert-confirmation{padding:14px;border:1px solid #cbd5e1;border-radius:8px;overflow-wrap:anywhere}.alert-confirmation p{font-size:13px;line-height:1.7;margin-top:0}.alert-confirmation .el-button{margin:4px 8px 4px 0}
</style>
