<template>
  <div class="coverage-page">
    <ConsolePageHeader>
      <template #actions><div class="coverage-actions"><el-select v-model="minutes" aria-label="观测窗口" @change="load"><el-option label="最近1小时" :value="60" /><el-option label="最近24小时" :value="1440" /><el-option label="最近7天" :value="10080" /></el-select><el-button :loading="loading" @click="load">刷新</el-button></div></template>
    </ConsolePageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert title="观测齐全表示预期证据已收到；业务失败或结果未知仍保留原状态。未登记入口的分母未确认。" type="info" :closable="false" />
    <template v-if="data">
      <p class="window-note">登记窗口 {{ time(data.windowStartMs) }} — {{ time(data.observedMs) }} · 聚合覆盖窗口内全部登记，最近明细最多 {{ data.detailLimit }} 条。</p>
      <el-card shadow="never" class="panel"><template #header><strong>入口指标</strong><span class="hint">登记数与缺口来自持久原请求，刷新只读取已有事实。</span></template>
        <el-table :data="data.metrics" class="coverage-table" empty-text="窗口内暂无登记">
          <el-table-column label="入口" min-width="165"><template #default="{ row }">{{ entry(row.entryKind) }}</template></el-table-column>
          <el-table-column label="登记分母" min-width="110"><template #default="{ row }">{{ count(row.registered) }}</template></el-table-column>
          <el-table-column label="证据齐全" min-width="100"><template #default="{ row }">{{ row.registered === null ? '未确认' : row.complete }}</template></el-table-column>
          <el-table-column label="待执行/无需观测" min-width="145"><template #default="{ row }">{{ row.registered === null ? '—' : row.pending }}</template></el-table-column>
          <el-table-column label="观测缺口" min-width="100"><template #default="{ row }">{{ row.registered === null ? '—' : row.gaps }}</template></el-table-column>
          <el-table-column label="身份/Trace未确认" min-width="155"><template #default="{ row }">{{ row.registered === null ? '—' : row.unconfirmed }}</template></el-table-column>
          <el-table-column label="业务失败 / 未知" min-width="145"><template #default="{ row }">{{ row.registered === null ? '—' : `${row.failed} / ${row.unknown}` }}</template></el-table-column>
        </el-table>
      </el-card>
      <el-card shadow="never" class="panel"><template #header><strong>原操作与问题定位</strong></template>
        <p v-if="data.detailsTruncated" class="hint">明细已截取最近 {{ data.detailLimit }} 条，上方指标仍包含窗口内全部登记。</p>
        <el-table :data="data.recent" class="coverage-table" empty-text="窗口内没有新的原操作登记">
          <el-table-column label="原操作" min-width="260"><template #default="{ row }"><button class="operation-link" @click="select(row.id)">{{ row.operationKey }}</button><small>{{ entry(row.entryKind) }}</small></template></el-table-column>
          <el-table-column label="执行状态" min-width="140"><template #default="{ row }">{{ state(row.executionStatus) }}</template></el-table-column>
          <el-table-column label="观测状态" min-width="175"><template #default="{ row }"><el-tag :type="row.coverage === 'COMPLETE' ? 'success' : 'warning'">{{ state(row.coverage) }}</el-tag></template></el-table-column>
          <el-table-column label="Agent Span 实收 / 预期" min-width="190"><template #default="{ row }">{{ count(row.agentSpans) }} / {{ row.expectedAgentSpans }}</template></el-table-column>
          <el-table-column label="网关事件 实收 / 预期" min-width="185"><template #default="{ row }">{{ count(row.modelEvents) }} / {{ row.expectedModelEvents }}</template></el-table-column>
        </el-table>
      </el-card>
    </template>
    <ObservationLogSearch v-if="permissions.can('viewConsole')" :minutes="minutes" @select="select" />
    <el-drawer v-model="detailOpen" append-to-body :z-index="4000" title="原操作证据" size="min(720px, 96vw)" @closed="clearDetail">
      <p v-if="detailLoading">正在读取证据…</p><el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
      <template v-if="detail"><dl><dt>原操作</dt><dd>{{ detail.operation.operationKey }}</dd><dt>Trace</dt><dd>{{ detail.operation.traceId || '未确认' }}</dd><dt>执行 / 观测</dt><dd>{{ state(detail.operation.executionStatus) }} / {{ state(detail.operation.coverage) }}</dd><dt>身份确认</dt><dd>{{ state(detail.operation.identityEvidence) }}</dd></dl>
        <router-link :to="detail.operation.originalPath">打开原记录</router-link>
        <RuntimePlacementEvidence v-if="detail.operation.entryKind === 'RUNTIME_TASK'" :samples="detail.placements" />
        <h3>结构化事件日志</h3><p class="hint">服务端固定原因码，与上方 Trace 关联；不包含任务正文或凭证。</p>
        <ol class="event-list"><li v-for="event in detail.logs" :key="event.kind"><strong>{{ event.kind }}</strong><time>{{ time(event.createdMs) }}</time><code>{{ event.reasonCode }}</code><span v-if="event.ownerEpoch !== null">责任版本 {{ event.ownerEpoch }}</span></li></ol>
        <ObservationAlerts v-if="detailOpen && permissions.can('viewConsole')" :key="detail.operation.id" :operation-id="detail.operation.id" />
        <h3>原费用记录</h3><p v-if="!detail.costs.length" class="hint">没有关联预占记录，不推断为已结算或零费用。</p>
        <div v-for="cost in detail.costs" :key="cost.reservationId" class="cost-row"><strong>#{{ cost.reservationId }} · {{ cost.state }}</strong><span>输入 {{ count(cost.inputTokens) }} / 输出 {{ count(cost.outputTokens) }} Token</span></div>
      </template>
    </el-drawer>
  </div>
</template>
<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { getCoverage, getCoverageOperation, type CoverageReport, type CoverageDetail } from '@/api/observationCoverage'
import { usePermissions } from '@/stores/permissions'
import ObservationAlerts from '@/components/ObservationAlerts.vue'
import ObservationLogSearch from '@/components/ObservationLogSearch.vue'
import RuntimePlacementEvidence from '@/components/RuntimePlacementEvidence.vue'
const permissions = usePermissions()
const minutes = ref(1440), data = ref<CoverageReport | null>(null), loading = ref(false), error = ref('')
const detail = ref<CoverageDetail | null>(null), detailOpen = ref(false), detailLoading = ref(false), detailError = ref('')
let generation = 0, detailGeneration = 0
function count(value: number | null) { return value === null ? '—' : String(value) }
function time(value: number) { return new Date(value).toLocaleString('zh-CN') }
function entry(value: string) { return ({AX_ECHO:'AX 回显',AX_FAIL:'AX 受控失败',AX_LLM:'AX 模型任务',EXPERIMENT:'提示词实验',WORKFLOW_CONTROL:'工作流控制节点',RUNTIME_TASK:'通用任务',CHAT:'对话',LEGACY_EXTERNAL:'旧外部入口 · 未登记'} as Record<string,string>)[value] || value }
function state(value: string) { return ({COMPLETE:'预期证据齐全',SUCCEEDED:'业务成功',FAILED:'业务失败',UNKNOWN:'结果未知',EXECUTION_UNKNOWN:'结果未知',COMPLETED:'执行结束',QUEUED:'等待执行',PROVISIONING:'准备实例',RUNNING:'执行中',CANCELLED:'后续未执行',BLOCKED:'治理阻断',WAITING_APPROVAL:'等待审批',PENDING:'等待证据',NOT_EXPECTED:'未派发，无需观测',NOT_EXECUTED:'控制节点未执行',MISSING_BOTH:'Agent与模型证据缺失',MISSING_AGENT:'Agent证据缺失',MISSING_MODEL:'模型证据缺失',UNEXPECTED_MODEL:'收到非预期模型事件',DUPLICATE_EVIDENCE:'证据重复',MODEL_STATUS_UNCONFIRMED:'模型状态未确认',IDENTITY_UNCONFIRMED:'实例身份未确认',RECEIPT_TRACE_UNCONFIRMED:'回执Trace未确认',FROZEN_REGISTRATION_MATCH:'冻结身份一致',PLATFORM_CHECK:'平台控制检查'} as Record<string,string>)[value] || value }
function clearDetail() { detailGeneration++; detail.value = null; detailLoading.value = false; detailError.value = '' }
async function load() {
  const current = ++generation; loading.value = true
  try { const response = await getCoverage(minutes.value); if (current !== generation) return; data.value = response; error.value = '' }
  catch { if (current === generation) { data.value = null; error.value = '观测证据读取失败，请刷新或缩短窗口重试' } }
  finally { if (current === generation) loading.value = false }
}
async function select(id: number) {
  clearDetail(); const current = detailGeneration; detailOpen.value = true; detailLoading.value = true
  try { const response = await getCoverageOperation(id); if (current === detailGeneration && detailOpen.value) detail.value = response }
  catch { if (current === detailGeneration) detailError.value = '原操作证据读取失败' }
  finally { if (current === detailGeneration) detailLoading.value = false }
}
watch(() => permissions.state.user, () => { generation++; data.value = null; clearDetail(); detailOpen.value = false; void load() }, { flush: 'sync' })
onMounted(load)
onUnmounted(() => { generation++; clearDetail() })
</script>
<style scoped>
.coverage-page{display:grid;gap:20px;min-width:0}.coverage-heading{display:flex;align-items:center;justify-content:space-between;gap:20px}.coverage-heading h2{font-size:28px;margin:5px 0 10px}.coverage-heading p,.hint,.window-note{color:#64748b;font-size:13px}.eyebrow{letter-spacing:2px}.coverage-actions{display:flex;gap:12px}.coverage-actions .el-select{width:160px}.coverage-table{width:100%}.hint{margin:10px 0}.operation-link{display:block;background:none;border:0;padding:0;color:#2563eb;cursor:pointer;text-align:left;overflow-wrap:anywhere}.operation-link+small{display:block;color:#64748b;margin-top:6px}dl{display:grid;grid-template-columns:100px minmax(0,1fr);gap:16px}dt{color:#64748b}dd{margin:0;overflow-wrap:anywhere}.event-list{padding-left:22px}.event-list li{display:grid;gap:7px;margin:20px 0}.event-list code{overflow-wrap:anywhere}.event-list time{color:#64748b;font-size:12px}.cost-row{display:grid;gap:8px;padding:15px 0;border-top:1px solid #e2e8f0}@media(max-width:760px){.coverage-heading{align-items:flex-start;flex-direction:column}.coverage-heading h2{font-size:24px}.coverage-actions{width:100%}.coverage-actions .el-select{flex:1}dl{grid-template-columns:85px minmax(0,1fr)}}
</style>
