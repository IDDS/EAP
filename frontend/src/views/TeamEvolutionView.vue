<template>
  <div class="team-evolution-page">
    <ConsolePageHeader>
      <template #actions><el-button :loading="loading" @click="load()">刷新列表</el-button></template>
    </ConsolePageHeader>
    <el-alert title="结果保留原执行与费用事实。具备实验操作能力的原创建账号可逐步推进任务；模型草稿须单独批准并主动生成，审核后另行登记；自动采用尚未开放。" type="info" :closable="false" />
    <TeamEvolutionOperations v-if="permissions.can('runAgentExperiments')" :detail="detail" :loading="detailLoading || loading" @read-root="select" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-card shadow="never">
      <template #header><strong>已登记的优化任务</strong></template>
      <div class="team-roots" v-loading="loading">
        <button v-for="row in page?.items || []" :key="row.id" class="team-root" :class="{ active: selectedId === row.id }" :aria-pressed="selectedId === row.id" @click="select(row.id)">
          <strong>团队优化 #{{ row.id }}</strong><span>{{ row.closed ? label(row.recommendation || 'CLOSED') : '尚未关闭' }}</span>
          <small>{{ row.candidates }} 个候选 · {{ row.evaluations }} 个评价批次 · {{ time(row.createdAt) }}</small>
        </button>
      </div>
      <el-empty v-if="page && !page.items.length && !loading" :description="page.nextBeforeId ? '本段没有可见记录，可继续查看更早任务' : '暂无可见团队优化任务'" />
      <p v-if="page?.scanLimited" class="hint">本次已达到检索上限，请继续查看更早任务。</p>
      <div class="team-pagination"><span class="hint">每页最多 {{ page?.pageLimit || 20 }} 条，按登记顺序排列。</span><el-button v-if="page?.nextBeforeId" :disabled="loading" @click="load(page.nextBeforeId)">查看更早任务</el-button></div>
    </el-card>
    <p v-if="detailLoading" role="status">正在读取团队原记录…</p>
    <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" />
    <template v-if="detail">
      <el-card shadow="never" class="team-detail">
        <template #header><div class="team-detail-heading"><strong>团队优化 #{{ detail.summary.id }}</strong><el-button :loading="detailLoading" @click="select(detail.summary.id)">刷新原记录</el-button></div></template>
        <div class="team-conclusion"><strong>{{ detail.closure ? label(detail.closure.recommendation) : '尚无最终留出结论' }}</strong><p>{{ detail.closure?.completeEvidence ? '本轮比较证据完整，仍需独立样本复验。' : '本轮比较证据尚不完整，缺失结果不能补成零分或成功。' }}</p><span>自动采用：未开放 · 稳定收益：尚未验证</span></div>
        <div class="team-facts"><div><small>角色顺序</small><p v-for="(role, index) in detail.roles" :key="role.role">{{ index + 1 }}. {{ role.role }} · <router-link :to="`/agents/${role.agentId}`">Agent #{{ role.agentId }}</router-link></p></div><div><small>冻结样本</small><p>训练 {{ detail.sampleCounts.TRAIN }} · 验证 {{ detail.sampleCounts.VALIDATION }} · 留出 {{ detail.sampleCounts.HOLDOUT }}</p><p>质量门槛 {{ detail.summary.minimumQualityBps / 100 }}%</p></div><div><small>候选与尝试</small><p>{{ detail.summary.candidates.length }} / {{ detail.summary.maxCandidates }} 个候选</p><p>{{ detail.summary.attempts }} / {{ detail.summary.maxAttempts }} 次提案尝试 · 最多 {{ detail.summary.maxGeneration }} 代</p></div></div>
        <h3>预算与全程费用</h3>
        <p v-if="detail.budget" class="hint">独立上限：{{ detail.budget.maxCalls }} 次模型调用 / {{ detail.budget.maxOutputTokens }} 输出 Token。批准截止 {{ time(detail.budget.approvalExpiresAt) }}；这不是当前仍可执行或共享容量已预留的证明。</p><p v-else class="hint">尚无独立预算计划，未分配不代表已完成零费用评测。</p>
        <div class="team-costs"><article><small>原账本调用预占 / 已结算</small><strong>{{ detail.currentCosts.modelCalls }} / {{ detail.currentCosts.settledCalls }}</strong></article><article><small>账本未知 / 在途 / 违规</small><strong>{{ detail.currentCosts.unknownCalls }} / {{ detail.currentCosts.pendingCalls }} / {{ detail.currentCosts.violationCalls }}</strong></article><article><small>已知输入 / 输出 Token</small><strong>{{ detail.currentCosts.knownInputTokens }} / {{ detail.currentCosts.knownOutputTokens }}</strong></article><article><small>保留的输出预留</small><strong>{{ detail.currentCosts.reservedOutputTokens }}</strong></article></div>
        <p class="hint">{{ detail.currentCosts.unknownUsage ? '部分调用用量未知，上方仅为已知用量。' : '上方为原账本已登记用量。' }}执行未知与账本未知分别记录；零预占不能推断没有发生未知执行。费用包含全部登记候选及留出，不提供货币价格。</p>
        <p v-if="detail.closure" class="hint">关闭时记录：{{ detail.closure.costs.modelCalls }} 次预占，{{ detail.closure.costs.knownInputTokens }} 输入 / {{ detail.closure.costs.knownOutputTokens }} 输出 Token，未知用量 {{ detail.closure.costs.unknownUsage ? '有' : '无' }}。提案模型调用 {{ detail.closure.proposalModelCalls }} 次。</p>
        <h3>候选谱系与验证前沿</h3>
        <p class="hint">{{ detail.frontier.sealed ? '验证前沿已封存，所选候选固定。' : '验证前沿尚未封存，当前候选排序不代表最终选择。' }}只有完整可信评分进入比较，留出评分不回流验证前沿。</p>
        <div class="team-candidates"><article v-for="candidate in detail.summary.candidates" :key="candidate.id"><strong>{{ candidateName(candidate.id) }}</strong><p>第 {{ candidate.generation }} 代 · {{ candidate.parentId ? `父代 ${candidateName(candidate.parentId)}` : '初始基线' }}<span v-if="candidate.changedRole"> · 变更 {{ candidate.changedRole }}</span></p><p>{{ label(detail.frontier.decisions[candidate.id] || 'EVIDENCE_INCOMPLETE') }}<span v-if="detail.frontier.sealed && detail.frontier.selectedCandidateId === candidate.id"> · 已选定</span></p><details><summary>候选摘要</summary><code>{{ candidate.id }}</code></details></article></div>
        <h3>独立留出比较</h3>
        <p v-if="!detail.holdout" class="hint">尚未登记独立留出批次，不能视为留出通过。</p>
        <p v-else class="hint">已登记基线与所选候选两套独立批次，结果见下方执行记录。独立批次不代表公开留出标签未被读取。</p>
        <div class="team-holdout"><article><small>留出基线</small><strong>{{ scoreText(holdoutScore('baseline')) }}</strong></article><article><small>留出所选候选</small><strong>{{ scoreText(holdoutScore('selected')) }}</strong></article></div>
        <h3>原评价批次与执行槽位</h3>
        <p v-if="!detail.evaluations.length" class="hint">尚未登记评价批次。</p>
        <article v-for="item in detail.evaluations" :key="item.registration.id" class="team-evaluation">
          <h4>{{ label(item.registration.split) }} · {{ label(item.registration.arm) }} · {{ candidateName(item.registration.candidateId) }}</h4>
          <p>登记状态：{{ label(item.registration.state) }} · 执行：{{ item.execution ? label(item.execution.state) : '尚无执行记录' }}</p>
          <p v-if="item.execution">实际派发 {{ item.execution.dispatchedSlots }} / 回执确认 {{ item.execution.confirmedSlots }}<span v-if="item.execution.reasonCode"> · 原原因码 {{ item.execution.reasonCode }}</span></p>
          <p>评分：{{ scoreText(item.score) }}<span v-if="item.score"> · {{ item.score.totalTokens }} Token · 两角色样本总耗时 {{ (item.score.totalLatencyMicros / 1000).toFixed(1) }} ms</span></p>
          <p v-if="item.score" class="hint">耗时包括准备检查和角色交接，不是纯模型生成耗时。</p>
          <details><summary>查看 {{ item.registration.slots.length }} 个原槽位及 Trace</summary><ol class="team-slots"><li v-for="slot in item.registration.slots" :key="slot.operationKey"><strong>{{ slot.sampleId }} · {{ slot.role }} · {{ label(slot.state) }}</strong><span>原操作 <code>{{ slot.operationKey }}</code></span><span>Trace <code>{{ slot.traceId }}</code></span></li></ol></details>
        </article>
        <details class="team-digests"><summary>冻结协议与数据集摘要</summary><dl><dt>协议</dt><dd>{{ detail.summary.protocolDigest }}</dd><dt>数据集</dt><dd>{{ detail.summary.datasetDigest }}</dd><dt>验证集</dt><dd>{{ detail.summary.validationDigest }}</dd></dl></details>
        <div v-if="permissions.can('viewContent')" class="team-content-controls"><el-button :loading="contentLoading" @click="loadContent">查看提示词与公开样本</el-button><el-button v-if="content" @click="clearContent">收起正文</el-button></div>
        <el-alert v-if="contentError" :title="contentError" type="error" :closable="false" />
        <section v-if="content && permissions.can('viewContent')" class="team-content"><h3>受权限保护的提示词与样本</h3><details v-for="candidate in content.candidates" :key="candidate.id"><summary>{{ candidateName(candidate.id) }} · 提示词</summary><div v-for="(prompt, role) in candidate.configuration.rolePrompts" :key="role"><strong>{{ role }}</strong><pre>{{ prompt }}</pre></div></details><details v-for="sample in content.samples" :key="sample.id"><summary>{{ label(sample.split) }} · {{ sample.id }}</summary><p>任务组 {{ sample.groupId }}</p><strong>输入</strong><pre>{{ sample.input }}</pre><strong>期望文本</strong><pre>{{ sample.expected }}</pre></details></section>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { TeamEvolutionApi, type TeamPage, type TeamDetail, type TeamContent, type TeamScore } from '@/api/teamEvolutions'
import { usePermissions } from '@/stores/permissions'
import TeamEvolutionOperations from '@/components/TeamEvolutionOperations.vue'
const permissions = usePermissions()
const page = ref<TeamPage | null>(null), detail = ref<TeamDetail | null>(null), content = ref<TeamContent | null>(null)
const loading = ref(false), detailLoading = ref(false), contentLoading = ref(false)
const error = ref(''), detailError = ref(''), contentError = ref(''), selectedId = ref<number | null>(null)
let alive = true, generation = 0, detailGeneration = 0, contentGeneration = 0
function clearContent() { contentGeneration++; content.value = null; contentLoading.value = false; contentError.value = '' }
function clearDetail() { detailGeneration++; detail.value = null; selectedId.value = null; detailLoading.value = false; detailError.value = ''; clearContent() }
function reset() { generation++; page.value = null; loading.value = false; error.value = ''; clearDetail() }
function allowed(user: unknown) { return alive && user === permissions.state.user && permissions.can('viewConsole') }
async function load(beforeId?: number) {
  const run = ++generation, user = permissions.state.user
  page.value = null; clearDetail(); error.value = ''; loading.value = false
  if (!allowed(user) || (beforeId !== undefined && (!Number.isSafeInteger(beforeId) || beforeId < 1))) return
  loading.value = true
  try { const result = await TeamEvolutionApi.list(beforeId); if (run === generation && allowed(user)) page.value = result }
  catch { if (run === generation && allowed(user)) error.value = '团队任务列表读取失败，请刷新重试。' }
  finally { if (run === generation && allowed(user)) loading.value = false }
}
async function select(id: number) {
  clearDetail(); const run = detailGeneration, user = permissions.state.user
  if (!allowed(user) || !Number.isSafeInteger(id) || id < 1) return
  selectedId.value = id; detailLoading.value = true
  try {
    const result = await TeamEvolutionApi.get(id)
    if (run !== detailGeneration || !allowed(user) || selectedId.value !== id) return
    if (result.summary.id !== id) throw new Error('Unexpected team root')
    detail.value = result
  } catch { if (run === detailGeneration && allowed(user)) { detail.value = null; detailError.value = '团队原记录读取失败，未保留旧结果；请核对任务后刷新。' } }
  finally { if (run === detailGeneration && allowed(user)) detailLoading.value = false }
}
async function loadContent() {
  clearContent(); const id = detail.value?.summary.id, run = contentGeneration, user = permissions.state.user
  if (!id || !allowed(user) || !permissions.can('viewContent')) return
  contentLoading.value = true
  try {
    const result = await TeamEvolutionApi.content(id)
    if (run === contentGeneration && allowed(user) && permissions.can('viewContent') && detail.value?.summary.id === id) {
      // Keep only the content fields rendered by this page; no runtime endpoints or protocol identity.
      content.value = { samples: result.samples, candidates: result.candidates }
    }
  } catch { if (run === contentGeneration && allowed(user) && permissions.can('viewContent')) contentError.value = '正文读取失败，已清除此前内容。' }
  finally { if (run === contentGeneration && allowed(user)) contentLoading.value = false }
}
function candidateName(id: string) { if (id === detail.value?.summary.baselineId) return '基线'; const index = detail.value?.summary.candidates.filter(c => c.id !== detail.value?.summary.baselineId).findIndex(c => c.id === id) ?? -1; return index < 0 ? `候选 ${id.slice(0, 12)}` : `候选 ${index + 1}` }
function holdoutScore(arm: 'baseline' | 'selected') { const d = detail.value; if (d?.closure) return d.closure[arm]; const id = d?.holdout?.[arm].id; return id ? d?.evaluations.find(e => e.registration.id === id)?.score : null }
function scoreText(score: TeamScore | null | undefined) { return score ? `${score.passed} / ${score.denominator} 精确匹配` : '尚无可信评分' }
function time(value: string) { return new Date(value).toLocaleString('zh-CN') }
function label(value: string) {
  return ({ TRAIN: '训练', VALIDATION: '验证', HOLDOUT: '留出', BASELINE: '独立基线', SELECTED: '所选候选', CANDIDATE: '候选评价', CLOSED: '已关闭', KEEP_BASELINE: '保留基线', INCOMPLETE_NO_ADOPTION: '证据不完整 · 不采纳', BOUNDED_HOLDOUT_SUPPORT_NO_ADOPTION: '本轮留出支持改善 · 待人工复核', UNKNOWN: '结果未知', EXECUTION_UNKNOWN: '结果未知', FAILED: '执行失败', SUCCEEDED: '成功', NOT_DISPATCHED: '尚未派发', RUNNING: '执行中', RECEIPTS_COMPLETE: '回执齐全', EVIDENCE_INCOMPLETE: '证据不完整', BASELINE_UNCONFIRMED: '基线尚未确认', QUALITY_GATE_REJECTED: '未达到质量门槛', DOMINATED: '被其他候选支配', FRONTIER: '位于验证前沿' } as Record<string, string>)[value] || value
}
watch(() => [permissions.state.user, permissions.can('viewConsole'), permissions.can('viewContent')], () => { reset(); if (permissions.can('viewConsole')) void load() }, { flush: 'sync' })
onMounted(() => { void load() })
onUnmounted(() => { alive = false; reset() })
</script>

<style scoped>
.team-evolution-page{display:grid;gap:20px;min-width:0}.team-heading,.team-detail-heading,.team-pagination{display:flex;align-items:center;justify-content:space-between;gap:16px}.team-heading h2{margin:4px 0 8px;font-size:28px}.team-heading p,.hint,.eyebrow{color:#64748b;font-size:13px}.team-roots,.team-facts,.team-candidates,.team-holdout{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.team-root{text-align:left;background:#fff;border:1px solid #dbe4ed;border-radius:8px;padding:16px;color:#183047;cursor:pointer;display:grid;gap:10px;font:inherit}.team-root.active{border-color:#0891b2;background:#ecfeff}.team-root small,.team-facts small,.team-costs small,.team-holdout small{color:#64748b}.team-pagination{margin-top:16px}.team-conclusion{padding:20px;background:#f1f5f9;border-radius:8px;margin-bottom:20px}.team-conclusion>strong{font-size:22px}.team-conclusion p,.team-conclusion span{font-size:13px;color:#475569}.team-facts{grid-template-columns:repeat(3,minmax(0,1fr))}.team-facts p{margin:8px 0;font-size:14px}.team-costs{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.team-costs article,.team-candidates article,.team-holdout article{padding:16px;border:1px solid #dbe4ed;border-radius:8px;min-width:0}.team-costs article,.team-holdout article{display:grid;gap:12px}.team-costs strong{font-size:22px}.team-candidates p{font-size:13px}.team-detail h3{margin:26px 0 12px}.team-evaluation{border-top:1px solid #dbe4ed;padding:8px 0 18px}.team-evaluation p{font-size:14px}.team-evaluation h4{margin-bottom:12px}.team-slots{padding-left:20px}.team-slots li{display:grid;gap:7px;margin:18px 0;font-size:13px}.team-detail code,.team-detail dd,.team-detail p{overflow-wrap:anywhere}.team-digests{margin:16px 0}.team-digests dl{display:grid;grid-template-columns:70px minmax(0,1fr);gap:12px;font-size:13px}.team-digests dd{margin:0}.team-content-controls{display:flex;gap:10px;flex-wrap:wrap;margin:18px 0}.team-content details{padding:14px 0;border-top:1px solid #dbe4ed}.team-content pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;background:#f8fafc;padding:12px;border-radius:6px}summary{cursor:pointer;font-size:13px;color:#35617d}.team-root:focus-visible{outline:2px solid #0891b2;outline-offset:3px}@media(max-width:760px){.team-heading{align-items:flex-start;flex-direction:column}.team-heading h2{font-size:24px}.team-roots,.team-facts,.team-candidates,.team-holdout{grid-template-columns:minmax(0,1fr)}.team-costs{grid-template-columns:repeat(2,minmax(0,1fr))}.team-pagination{align-items:flex-start;flex-direction:column}.team-conclusion{padding:14px}.team-conclusion>strong{font-size:19px}.team-content-controls .el-button{margin-left:0}}
</style>
