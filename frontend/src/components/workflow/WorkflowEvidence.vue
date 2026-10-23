<template>
  <section class="workflow-evidence" aria-label="关联观测证据">
    <h3>关联观测证据</h3>
    <p>按原执行读取已登记的任务与控制节点证据，不限登记时间。每次读取 20 条；状态和用量以读取时的证据为准。</p>
    <el-button size="small" :loading="loading" :disabled="!permissions.can('viewConsole')" @click="load">刷新关联证据</el-button>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="report && !operations.length">原执行没有可见登记，不代表没有执行、没有费用或观测齐全；历史记录不会在读取时补登记。</p>
    <ul class="evidence-list">
      <li v-for="operation in operations" :key="operation.id">
        <button type="button" :aria-expanded="detail?.operation.id === operation.id" :disabled="detailLoading && selectedId === operation.id" @click="select(operation)">{{ stepLabel(operation) }}<span>{{ coverageLabel(operation.coverage) }}</span></button>
        <small>{{ operation.operationKey }}</small>
      </li>
    </ul>
    <el-button v-if="report?.nextCursor" size="small" :loading="loading" :disabled="!permissions.can('viewConsole')" @click="loadMore">加载更早证据</el-button>
    <p v-if="report && operations.length">已读取 {{ operations.length }} 条{{ report.nextCursor ? '，还有更早登记' : '，已读完本轮可见登记' }}。刷新可读取新登记和更新后的状态。</p>
    <p v-if="detailLoading" role="status">正在读取原操作证据…</p>
    <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
    <div v-if="detail" class="evidence-detail">
      <h4>原操作 #{{ detail.operation.id }}</h4>
      <dl><dt>执行状态</dt><dd>{{ statusLabel(detail.operation.executionStatus) }}</dd><dt>身份依据</dt><dd>{{ detail.operation.identityEvidence }}</dd><dt>Agent Span · 实收 / 预期</dt><dd>{{ count(detail.operation.agentSpans) }} / {{ count(detail.operation.expectedAgentSpans) }}</dd><dt>模型事件 · 实收 / 预期</dt><dd>{{ count(detail.operation.modelEvents) }} / {{ count(detail.operation.expectedModelEvents) }}</dd><dt>Trace</dt><dd>{{ detail.operation.traceId || '未确认' }}</dd></dl>
      <router-link v-if="traceId" :to="{ path: '/physical', query: { traceId, ...(detail.operation.agentId ? { agentId: detail.operation.agentId } : {}) } }">查看原 Trace →</router-link>
      <RuntimePlacementEvidence v-if="detail.operation.entryKind === 'RUNTIME_TASK'" :samples="detail.placements" />
      <h4>原预算与用量</h4>
      <p v-if="!detail.costs.length">暂无关联预占记录，不能推断为零费用或已结算。</p>
      <div v-for="cost in detail.costs" :key="cost.reservationId" class="cost-evidence"><strong>预占 #{{ cost.reservationId }} · {{ cost.state }}</strong><span>输入 {{ count(cost.inputTokens) }} / 输出 {{ count(cost.outputTokens) }} Token</span></div>
      <h4>平台记录的事件</h4>
      <p v-if="!detail.logs.length">暂无事件记录。</p>
      <ol class="evidence-events"><li v-for="event in detail.logs" :key="event.kind"><strong>{{ event.kind }}</strong><span>{{ event.reasonCode }}</span><time>{{ new Date(event.createdMs).toLocaleString('zh-CN') }}</time></li></ol>
    </div>
    <router-link to="/observation-coverage">打开证据覆盖与定位 →</router-link>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import RuntimePlacementEvidence from '@/components/RuntimePlacementEvidence.vue'
import { usePermissions } from '@/stores/permissions'
import { getWorkflowEvidence, getWorkflowEvidenceDetail, type CoverageDetail, type CoverageOperation, type WorkflowEvidencePage, type WorkflowEvidenceEntry } from '@/api/observationCoverage'
import { executionStatusLabel as statusLabel } from '@/api/taskWorkspace'
import type { WorkflowExecution } from '@/types/domain'
const props = defineProps<{ execution: WorkflowExecution }>()
const permissions = usePermissions()
const report = ref<WorkflowEvidencePage | null>(null), detail = ref<CoverageDetail | null>(null)
const entries = ref<WorkflowEvidenceEntry[]>([])
const loading = ref(false), detailLoading = ref(false), error = ref(''), detailError = ref(''), selectedId = ref<number>()
const operations = computed(() => entries.value.map(entry => entry.operation))
const traceId = computed(() => { const operation = detail.value?.operation, trace = operation?.traceId; return operation?.entryKind !== 'WORKFLOW_CONTROL' && trace && /^[0-9a-f]{32}$/i.test(trace) && !/^0+$/.test(trace) ? trace : '' })
let generation = 0, detailGeneration = 0, alive = true
function clearDetail() { detailGeneration++; detail.value = null; selectedId.value = undefined; detailLoading.value = false; detailError.value = '' }
function clear() { generation++; report.value = null; entries.value = []; loading.value = false; error.value = ''; clearDetail() }
function permitted() { return alive && permissions.can('viewConsole') }
function count(value: number | null) { return value === null ? '未确认' : String(value) }
function stepLabel(operation: CoverageOperation) {
  const entry = entries.value.find(item => item.operation.id === operation.id)
  const step = props.execution.steps.find(item => (entry?.nodeSequence != null && item.sequence === entry.nodeSequence) || (entry?.nodeKey && item.nodeKey === entry.nodeKey) || item.taskKey === operation.operationKey)
  return step?.label || (operation.entryKind === 'WORKFLOW_CONTROL' ? `控制节点 · 步骤 ${(entry?.nodeSequence ?? 0) + 1}` : operation.operationKey)
}
function coverageLabel(value: string) { return ({ COMPLETE: '预期证据齐全', PENDING: '等待证据', NOT_EXPECTED: '无需观测', NOT_EXECUTED: '尚未执行', MISSING_BOTH: 'Agent 与模型证据缺失', MISSING_AGENT: 'Agent 证据缺失', MISSING_MODEL: '模型证据缺失', IDENTITY_UNCONFIRMED: '实例身份未确认', RECEIPT_TRACE_UNCONFIRMED: '回执 Trace 未确认', DUPLICATE_EVIDENCE: '证据重复', UNEXPECTED_MODEL: '非预期模型事件', MODEL_STATUS_UNCONFIRMED: '模型状态未确认' } as Record<string, string>)[value] || value }
async function load() {
  clear()
  await page()
}
async function loadMore() {
  if (loading.value || !report.value?.nextCursor) return
  await page(report.value.nextCursor)
}
async function page(cursor?: string) {
  if (!permitted()) return
  const request = generation, user = permissions.state.user, { workflowId, runKey } = props.execution
  loading.value = true; error.value = ''
  try {
    const response = await getWorkflowEvidence(workflowId, runKey, cursor)
    if (!permitted() || request !== generation || user !== permissions.state.user) return
    if (response.contract !== 'WORKFLOW_OBSERVATIONS_V1' || response.workflowId !== workflowId || response.runKey !== runKey || !Array.isArray(response.entries) || response.entries.length > 20) throw new Error('Evidence scope mismatch')
    let previous = entries.value.at(-1)?.operation.id ?? Number.MAX_SAFE_INTEGER
    for (const entry of response.entries) {
      if (!Number.isSafeInteger(entry.operation.id) || entry.operation.id <= 0 || entry.operation.id >= previous || !['WORKFLOW_CONTROL', 'RUNTIME_TASK', 'AX_ECHO', 'AX_FAIL', 'AX_LLM'].includes(entry.operation.entryKind)) throw new Error('Invalid evidence page')
      previous = entry.operation.id
    }
    if (response.nextCursor !== null && (typeof response.nextCursor !== 'string' || !response.nextCursor || response.nextCursor === cursor || !response.entries.length)) throw new Error('Invalid evidence cursor')
    entries.value = [...entries.value, ...response.entries]
    report.value = response
  } catch { if (permitted() && request === generation && user === permissions.state.user) error.value = '关联观测读取失败，请刷新重试。执行记录仍以原回执为准。' }
  finally { if (request === generation) loading.value = false }
}
async function select(operation: CoverageOperation) {
  clearDetail()
  if (!permitted() || !operations.value.some(item => item.id === operation.id && item.operationKey === operation.operationKey)) return
  const request = detailGeneration, user = permissions.state.user, { workflowId, runKey } = props.execution
  selectedId.value = operation.id; detailLoading.value = true
  try {
    const scoped = await getWorkflowEvidenceDetail(workflowId, runKey, operation.id)
    if (!permitted() || request !== detailGeneration || user !== permissions.state.user) return
    if (scoped.workflowId !== workflowId || scoped.runKey !== runKey) throw new Error('Evidence detail scope mismatch')
    const response = scoped.detail
    if (response.operation.id !== operation.id || response.operation.operationKey !== operation.operationKey || response.operation.entryKind !== operation.entryKind) throw new Error('Operation identity mismatch')
    detail.value = response
  } catch { if (permitted() && request === detailGeneration && user === permissions.state.user) detailError.value = '原操作证据读取失败，请重新选择。' }
  finally { if (request === detailGeneration) detailLoading.value = false }
}
watch([() => props.execution, () => permissions.state.user, () => permissions.can('viewConsole')], () => { void load() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { alive = false; clear() })
</script>
<style scoped>
.workflow-evidence{border-top:1px solid #dfe7ee;padding-top:18px;margin-top:20px;min-width:0}.workflow-evidence h3{font-size:15px;margin:0 0 12px}.workflow-evidence h4{font-size:13px;margin:20px 0 10px}.workflow-evidence p{font-size:12px;line-height:1.8;color:#64748b}.workflow-evidence a{font-size:13px;display:inline-block;line-height:1.7;margin-top:12px}.evidence-list{list-style:none;padding:0;display:grid;gap:12px;margin:16px 0}.evidence-list li{border-bottom:1px solid #e2e8f0;padding-bottom:12px;min-width:0}.evidence-list button{display:grid;gap:6px;background:none;border:0;padding:6px 0;text-align:left;color:#287eac;font-size:13px;cursor:pointer;width:100%;overflow-wrap:anywhere}.evidence-list button:focus-visible{outline:2px solid #287eac}.evidence-list button span{color:#475569;font-size:12px}.evidence-list small{display:block;color:#64748b;font-size:11px;overflow-wrap:anywhere}.evidence-detail dl{display:grid;gap:6px;font-size:12px}.evidence-detail dt{color:#64748b;margin-top:8px}.evidence-detail dd{margin:0;overflow-wrap:anywhere;line-height:1.7}.cost-evidence{display:grid;gap:7px;font-size:12px;margin:14px 0;overflow-wrap:anywhere}.evidence-events{padding:0;list-style:none;display:grid;gap:14px}.evidence-events li{display:grid;gap:7px;font-size:11px;overflow-wrap:anywhere}.evidence-events time{color:#64748b}.workflow-evidence .evidence-warning{color:#93600f}
</style>
