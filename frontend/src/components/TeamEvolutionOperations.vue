<template>
  <el-card shadow="never" class="team-operations">
    <template #header><div class="ops-heading"><strong>管理团队优化</strong><el-button :disabled="state.phase === 'SENDING' || state.phase === 'REVIEW'" @click="refreshContext">刷新操作条件</el-button><el-button :disabled="locked || !fresh || loading || !availability?.contractsEnabled" @click="openCreate">创建团队任务</el-button></div></template>
    <p class="ops-hint">每一步分别核对并提交。执行评价和模型提案会调用模型；预算、原实例权限和批准期限仍由服务器逐次检查。</p>
    <el-alert v-if="readError" :title="readError" type="error" :closable="false" />
    <p v-if="detail?.closure" class="ops-hint">此任务已关闭，不能重开、续期或重跑原评价。可以继续查看原结果。</p>
    <p v-else-if="detail && context && !context.originalSubmitter" class="ops-hint">此任务只能由原创建账号操作。</p>
    <div v-if="detail && context?.originalSubmitter && !detail.closure" class="ops-choices">
      <el-button v-for="item in options" :key="item.action" :disabled="locked || !fresh || !item.enabled" @click="choose(item.action)">{{ item.label }}</el-button>
      <p v-if="options.every(item => !item.enabled)" class="ops-hint">当前没有可提交的后续操作，请检查原状态、运行条件和批准期限。</p>
    </div>
    <section v-if="proposals.length" class="ops-proposals" aria-label="原模型提案记录">
      <h3>原模型提案记录</h3><p class="ops-hint">最多两条。准备中、已发送或未知记录不会自动重发；这里显示原费用，不把未知当作零消费。</p>
      <article v-for="proposal in proposals" :key="proposal.requestKey" class="ops-proposal-row"><strong>{{ proposal.role }} · {{ proposalLabel(proposal.state) }}</strong><code>{{ proposal.requestKey }}</code><p class="ops-hint">{{ proposalLabel(proposal.usageState) }} · 输入 {{ proposal.inputTokens ?? '待确认' }} / 输出 {{ proposal.outputTokens ?? '待确认' }} Token</p><div class="ops-buttons"><el-button :disabled="locked" @click="inspectProposal(proposal)">只读核对此提案</el-button><el-button v-if="proposal.draftAvailable && permissions.can('viewContent')" :disabled="locked || proposalLoading" @click="readProposalDraft(proposal)">读取模型草稿</el-button></div></article>
    </section>
    <el-alert v-if="proposalError" :title="proposalError" type="error" :closable="false" />
    <section v-if="proposalDraft" class="ops-review"><h3>模型草稿审核</h3><p>原请求 {{ proposalDraft.requestKey }} · 变更角色 {{ proposalDraft.role }}</p><p class="ops-hint">只读取了草稿，尚未登记或采用。另一角色保持父代原提示词；请核对后再登记候选。</p><pre>{{ proposalDraft.prompt }}</pre><div class="ops-buttons"><el-button :disabled="locked || !fresh || !options.some(o => o.action === 'CANDIDATE' && o.enabled)" @click="useProposalDraft">带入候选登记表</el-button><el-button @click="clearProposalDraft">清除草稿正文</el-button></div></section>
    <form v-if="mode && !locked" class="ops-form" @submit.prevent="prepare">
      <h3>{{ labels[mode] }}</h3>
      <template v-if="mode === 'CREATE'">
        <p class="ops-hint">两个角色按顺序交接，基线提示词取自所选实例的原发布。创建只登记合同，不唤醒实例、不分配预算。</p>
        <div class="ops-grid">
          <fieldset v-for="(role, index) in draft.roles" :key="index"><legend>第 {{ index + 1 }} 个角色</legend>
            <label>角色名称<el-input v-model="role.role" maxlength="64" /></label>
            <label>智能体<el-select v-model="role.agentId" filterable placeholder="选择已启用的智能体" @change="loadTargets(index)"><el-option v-for="agent in agents" :key="agent.id" :label="`${agent.name} #${agent.id}`" :value="agent.id" /></el-select></label>
            <label>已登记实例<el-select v-model="role.instanceKey" placeholder="选择实例"><el-option v-for="instance in targets[index]" :key="instance.instanceKey" :label="instance.instanceKey" :value="instance.instanceKey" /></el-select></label>
          </fieldset>
        </div>
        <div class="ops-grid ops-limits"><label>最大候选数（含基线）<el-input-number v-model="draft.maxCandidates" :min="1" :max="32" /></label><label>最大提案次数<el-input-number v-model="draft.maxAttempts" :min="1" :max="128" /></label><label>最大代数<el-input-number v-model="draft.maxGeneration" :min="0" :max="8" /></label><label>质量门槛（万分比）<el-input-number v-model="draft.minimumQualityBps" :min="0" :max="10000" /></label><label>每角色输出 Token 上限<el-input-number v-model="draft.maxTokens" :min="1" :max="256" /></label></div>
        <h4>公开评价样本</h4><p class="ops-hint">训练与验证各1–3条，独立留出1–10条。不同分组不能跨集合，输入不得重复；评分比较最终完整文本是否精确相同。</p>
        <fieldset v-for="(sample, index) in draft.samples" :key="sample.localId" class="ops-sample"><legend>样本 {{ index + 1 }}</legend><div class="ops-grid"><label>样本编号<el-input v-model="sample.id" maxlength="64" /></label><label>业务分组<el-input v-model="sample.groupId" maxlength="64" /></label><label>集合<el-select v-model="sample.split"><el-option label="训练" value="TRAIN" /><el-option label="验证" value="VALIDATION" /><el-option label="独立留出" value="HOLDOUT" /></el-select></label></div><label>输入<el-input v-model="sample.input" type="textarea" :rows="2" /></label><label>期望最终文本<el-input v-model="sample.expected" type="textarea" :rows="2" /></label><el-button :disabled="draft.samples.length <= 3" @click="draft.samples.splice(index, 1)">移除此样本</el-button></fieldset>
        <el-button :disabled="draft.samples.length >= 16" @click="addSample">添加样本</el-button>
        <el-checkbox v-model="draft.publicSamplesConfirmed">确认样本可公开用于本次评测，不含客户数据或凭据</el-checkbox>
      </template>
      <template v-else-if="mode === 'PROPOSE'">
        <p class="ops-hint">本次实际调用模型，只使用父代提示词和公开训练集。验证与独立留出不送入提案模型。草稿不会自动登记或采用。</p>
        <label>父代<el-select v-model="parentId"><el-option v-for="candidate in proposalParents" :key="candidate.id" :label="candidateLabel(candidate.id)" :value="candidate.id" /></el-select></label>
        <label>只为一个角色提案<el-select v-model="changedRole"><el-option v-for="role in detail!.roles" :key="role.role" :label="role.role" :value="role.role" /></el-select></label>
        <p class="ops-hint">每次最多1次模型调用、{{ detail!.budget!.maxProposalTokens }} 输出Token，计入原任务预算。提案也消耗一次合同尝试。</p>
      </template>
      <template v-else-if="mode === 'CANDIDATE'">
        <p class="ops-hint">每次只修改一个角色。拒绝的提案也计入提案次数；可手动填写，也可读取模型草稿后核对登记。</p>
        <label>父代<el-select v-model="parentId" @change="clearParent"><el-option v-for="candidate in detail!.summary.candidates" :key="candidate.id" :label="candidateLabel(candidate.id)" :value="candidate.id" /></el-select></label>
        <el-button :disabled="!parentId || !permissions.can('viewContent') || parentLoading" @click="readParent">读取所选父代提示词</el-button>
        <template v-if="parentPrompts"><label>变更角色<el-select v-model="changedRole" @change="newPrompt = parentPrompts?.[changedRole] || ''"><el-option v-for="role in detail!.roles" :key="role.role" :label="role.role" :value="role.role" /></el-select></label><label>新提示词<el-input v-model="newPrompt" type="textarea" :rows="6" /></label><details><summary>查看父代原提示词</summary><div v-for="(prompt, role) in parentPrompts" :key="role"><strong>{{ role }}</strong><pre>{{ prompt }}</pre></div></details></template>
      </template>
      <template v-else-if="mode === 'ENROLL'"><label>登记验证的候选<el-select v-model="candidateId"><el-option v-for="candidate in unenrolled" :key="candidate.id" :label="candidateLabel(candidate.id)" :value="candidate.id" /></el-select></label><p class="ops-hint">登记全验证集的两个角色原槽位，不派发模型调用。留出批次须在验证前沿封存后单独登记。</p></template>
      <template v-else-if="mode === 'BUDGET'">
        <p class="ops-hint">完整计划至少 {{ requiredCalls }} 次模型调用；按当前调用上限，输出额度至少 {{ requiredTokens }} Token，已计入所选提案用途。业务工具固定0，当前没有输入 Token 或金额硬上限。不会自动提高平台或智能体共享额度。</p>
        <label>提案用途<el-select v-model="budget.proposalMode"><el-option label="仅人工候选，不调用提案模型" value="OFFLINE_ONLY" /><el-option v-if="availability?.modelProposalAvailable" label="允许单角色模型提案草稿" value="MODEL_SINGLE_ROLE" /></el-select></label>
        <template v-if="budget.proposalMode === 'MODEL_SINGLE_ROLE'"><div class="ops-grid"><label>模型提案次数上限<el-input-number v-model="budget.maxProposalCalls" :min="1" :max="2" /></label><label>每次提案输出Token上限<el-input-number v-model="budget.maxProposalTokens" :min="64" :max="512" /></label></div><el-checkbox v-model="budget.proposalUseConfirmed">明确批准父提示词及公开训练样本用于模型提案，费用包含在本次总预算内</el-checkbox></template>
        <label>本次业务任务<el-input v-model="budget.businessTask" type="textarea" :rows="2" /></label>
        <div class="ops-grid"><label>模型调用上限<el-input-number v-model="budget.maxCalls" :min="1" :max="128" /></label><label>输出 Token 上限<el-input-number v-model="budget.maxOutputTokens" :min="1" :max="32768" /></label><label>批准截止时间（本地时间）<el-input v-model="budget.approvalExpiresAt" type="datetime-local" step="1" /></label></div>
        <p class="ops-hint">提交时截止时间须在未来30–900秒；预算一经登记不能修改或续期，共享容量尚未预留。</p><el-checkbox v-model="budget.confirmed">确认这是独立批准的新预算，不继承旧实验剩余额度</el-checkbox>
      </template>
      <template v-else-if="mode === 'EXECUTE' || mode === 'SCORE'"><label>原评价批次<el-select v-model="evaluationId"><el-option v-for="item in eligibleEvaluations" :key="item.registration.id" :label="`${item.registration.split} · ${candidateLabel(item.registration.candidateId)} · ${item.registration.id.slice(0, 8)}`" :value="item.registration.id" /></el-select></label><p v-if="mode === 'EXECUTE'" class="ops-hint">本次将实际调用模型，按原槽位串行执行。失败或未知原批次不会换键重跑，也不会自动执行下一批。</p><p v-else class="ops-hint">只对原回执齐全且费用已结算的完整批次评分，不补分、不调用模型。</p></template>
      <p v-else class="ops-hint">{{ explanation[mode] }}</p>
      <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      <div class="ops-buttons"><el-button native-type="submit" type="primary" :disabled="!fresh || parentLoading">核对本次操作</el-button><el-button @click="closeForm">取消</el-button></div>
    </form>
    <section v-if="state.phase !== 'IDLE'" class="ops-review" aria-live="polite">
      <h3>{{ state.phase === 'REVIEW' ? '提交前核对' : '原请求状态' }}</h3>
      <template v-if="state.intent"><p>{{ labels[state.intent.action] }}<span v-if="state.intent.rootId"> · 团队 #{{ state.intent.rootId }}</span></p><p>原请求编号 <code>{{ state.intent.requestKey }}</code></p><p class="ops-hint">编号仅在当前会话保留；离开前请保存，未知结果须核对原记录。</p><ul v-if="state.phase === 'REVIEW'"><li v-for="line in state.intent.summary" :key="line">{{ line }}</li></ul></template>
      <template v-if="state.phase === 'REVIEW'"><details v-if="reviewSamples.length"><summary>核对本次冻结的全部公开样本</summary><article v-for="sample in reviewSamples" :key="sample.id"><strong>{{ sample.split }} · {{ sample.id }} · {{ sample.groupId }}</strong><pre>{{ sample.input }}</pre><pre>{{ sample.expected }}</pre></article></details><el-checkbox v-model="confirmed">已核对当前任务、操作范围和上述费用/关闭影响</el-checkbox><div class="ops-buttons"><el-button type="primary" :disabled="!confirmed || !fresh" @click="submit">确认提交一次</el-button><el-button @click="session.cancel()">返回修改</el-button></div></template>
      <p v-else>{{ state.message }}</p>
      <p v-if="resultLabel" class="ops-hint">服务器原状态：{{ resultLabel }}</p>
      <div class="ops-buttons"><el-button v-if="['UNKNOWN', 'FOUND', 'REJECTED'].includes(state.phase) && state.intent?.action !== 'CHECK'" :loading="state.reading" @click="session.reconcile()">只读核对原请求</el-button><el-button v-if="['FOUND', 'REJECTED'].includes(state.phase)" @click="refreshFacts">读取最新原记录</el-button></div>
    </section>
    <details v-if="!locked" class="ops-recovery"><summary>核对已保存的原请求编号</summary><p class="ops-hint">用于重新进入页面后只读核对；找不到记录不能证明原请求未发生。</p><div class="ops-grid"><label>原操作<el-select v-model="recovery.action"><el-option v-for="(label, key) in receiptLabels" :key="key" :label="label" :value="key" /></el-select></label><label>原请求编号<el-input v-model="recovery.requestKey" maxlength="64" /></label><label v-if="recovery.action !== 'CREATE'">原团队编号<el-input-number v-model="recovery.rootId" :min="1" /></label><label v-if="['EXECUTE', 'SCORE'].includes(recovery.action)">原评价编号<el-input v-model="recovery.evaluationId" maxlength="36" /></label></div><el-button @click="recover">只读查找此原请求</el-button></details>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { EapApi } from '@/api/eap'
import { ExperimentApi } from '@/api/agentExperiments'
import { TeamEvolutionApi, type TeamDetail } from '@/api/teamEvolutions'
import { TeamOperationApi, type TeamAction, type TeamActionContext, type TeamAvailability, type TeamIntent, type TeamProposal } from '@/api/teamEvolutionOperations'
import { createOperationSession, emptyOperationState } from '@/api/teamOperationSession'
import { usePermissions } from '@/stores/permissions'
const props = defineProps<{ detail: TeamDetail | null; loading: boolean }>()
const emit = defineEmits<{ 'read-root': [id: number] }>()
const permissions = usePermissions(), state = reactive(emptyOperationState())
const availability = ref<TeamAvailability | null>(null), context = ref<TeamActionContext | null>(null), fresh = ref(false)
const mode = ref<TeamAction | null>(null), readError = ref(''), formError = ref(''), confirmed = ref(false)
let alive = true, readGeneration = 0, parentGeneration = 0, proposalGeneration = 0, targetGeneration = [0, 0]
const labels: Record<TeamAction, string> = { CREATE: '创建团队任务', CANDIDATE: '登记提示词候选', PROPOSE: '生成模型提案草稿', ENROLL: '登记验证批次', BUDGET: '冻结独立预算', CHECK: '检查当前运行条件', EXECUTE: '执行原评价批次', SCORE: '生成原批次评分', FRONTIER: '封存验证前沿', HOLDOUT: '登记独立留出', CLOSE: '关闭本次优化' }
const explanation: Partial<Record<TeamAction, string>> = { CHECK: '检查原实例、批准期限和共享额度。本次检查不预留容量，也不能代替执行时的检查。', FRONTIER: '根据已评分候选冻结验证选择。封存后不能继续登记候选或验证，不会自动采用提示词。', HOLDOUT: '按已封存选择登记基线与所选候选两套独立留出批次。只登记，不自动执行。', CLOSE: '保存完整比较结论或原不完整结论。关闭后不能继续派发、重开或续期；未知与已消费额度保持，不自动采用。' }
const receiptLabels = Object.fromEntries(Object.entries(labels).filter(([key]) => key !== 'CHECK'))
const reviewSamples = computed(() => (state.phase === 'REVIEW' && state.intent?.action === 'CREATE' ? state.intent.body.samples : []) as { id: string; groupId: string; split: string; input: string; expected: string }[])
const agents = ref<{ id: number; name: string }[]>([]), targets = ref<{ instanceKey: string }[][]>([[], []])
function sample(split: string, id: string) { return { localId: crypto.randomUUID(), id, groupId: id, split, input: '', expected: '' } }
function newDraft() { return { roles: [{ role: 'extractor', agentId: null as number | null, instanceKey: '' }, { role: 'classifier', agentId: null as number | null, instanceKey: '' }], maxCandidates: 2, maxAttempts: 4, maxGeneration: 1, minimumQualityBps: 10000, maxTokens: 128, publicSamplesConfirmed: false, samples: [sample('TRAIN', 'train-1'), sample('VALIDATION', 'validation-1'), sample('HOLDOUT', 'holdout-1')] } }
function newBudget() { return { businessTask: '', maxCalls: 1, maxOutputTokens: 1, approvalExpiresAt: '', confirmed: false, proposalMode: 'OFFLINE_ONLY', proposalUseConfirmed: false, maxProposalCalls: 1, maxProposalTokens: 512 } }
const draft = reactive(newDraft()), budget = reactive(newBudget())
const proposals = ref<TeamProposal[]>([]), proposalLoading = ref(false), proposalError = ref('')
const proposalDraft = ref<{ rootId: number; requestKey: string; parentId: string; role: string; prompt: string; parents: Record<string, string> } | null>(null)
const parentId = ref(''), parentPrompts = ref<Record<string, string> | null>(null), parentLoading = ref(false), changedRole = ref(''), newPrompt = ref(''), candidateId = ref(''), evaluationId = ref('')
const recovery = reactive({ action: 'CREATE' as TeamAction, requestKey: '', rootId: 1, evaluationId: '' })
const session = createOperationSession(state, { user: () => permissions.state.user, allowed: () => permissions.can('runAgentExperiments'), fresh: () => fresh.value && !props.loading, send: TeamOperationApi.send, receipt: TeamOperationApi.receipt, invalidate: () => { fresh.value = false; clearPrivate() } })
const locked = computed(() => ['REVIEW', 'SENDING', 'UNKNOWN'].includes(state.phase))
const resultLabel = computed(() => { const r = state.result as { state?: string; disposition?: string; recommendation?: string; reasonCode?: string } | null; return [r?.state, r?.disposition, r?.recommendation, r?.reasonCode].filter(Boolean).join(' · ') })
const requiredCalls = computed(() => props.detail ? 2 * (props.detail.sampleCounts.VALIDATION * props.detail.summary.maxCandidates + 2 * props.detail.sampleCounts.HOLDOUT) + (budget.proposalMode === 'MODEL_SINGLE_ROLE' ? budget.maxProposalCalls : 0) : 0)
const requiredTokens = computed(() => (Math.max(budget.maxCalls, requiredCalls.value) - (budget.proposalMode === 'MODEL_SINGLE_ROLE' ? budget.maxProposalCalls : 0)) * (context.value?.maxTokens || 0) + (budget.proposalMode === 'MODEL_SINGLE_ROLE' ? budget.maxProposalCalls * budget.maxProposalTokens : 0))
const unresolvedProposal = computed(() => proposals.value.some(p => ['PREPARING', 'DISPATCHED', 'UNKNOWN'].includes(p.state)))
const activeProposal = computed(() => proposals.value.some(p => ['PREPARING', 'DISPATCHED'].includes(p.state)))
const proposalParents = computed(() => props.detail?.summary.candidates.filter(p => p.generation < props.detail!.summary.maxGeneration) || [])
const unenrolled = computed(() => props.detail?.summary.candidates.filter(c => !props.detail!.evaluations.some(e => e.registration.candidateId === c.id && e.registration.split === 'VALIDATION')) || [])
function inOpenPhase(split: string) { return split === 'VALIDATION' ? !props.detail?.frontier.sealed : !!props.detail?.holdout }
function executable() { return props.detail?.evaluations.filter(e => !e.execution && e.registration.state === 'NOT_DISPATCHED' && inOpenPhase(e.registration.split)) || [] }
function scoreable() { return props.detail?.evaluations.filter(e => !e.score && e.execution?.state === 'RECEIPTS_COMPLETE' && inOpenPhase(e.registration.split)) || [] }
const eligibleEvaluations = computed(() => mode.value === 'EXECUTE' ? executable() : scoreable())
const options = computed(() => {
  const d = props.detail, a = context.value?.availability, approved = !!d?.budget && Date.parse(d.budget.approvalExpiresAt) > Date.now()
  if (!d || !a) return []
  const running = d?.evaluations.some(e => e.execution?.state === 'RUNNING'), unscored = d?.evaluations.some(e => e.execution?.state === 'RECEIPTS_COMPLETE' && !e.score)
  return ([['PROPOSE', a?.modelProposalAvailable && approved && d.budget?.proposalMode === 'MODEL_SINGLE_ROLE' && !d.frontier.sealed && !unresolvedProposal.value && proposals.value.length < (d.budget.maxProposalCalls || 0) && proposalParents.value.length > 0 && d.summary.attempts < d.summary.maxAttempts && d.summary.candidates.length < d.summary.maxCandidates], ['CANDIDATE', !unresolvedProposal.value && a?.contractsEnabled && !d?.frontier.sealed && permissions.can('viewContent') && d!.summary.attempts < d!.summary.maxAttempts && d!.summary.candidates.length < d!.summary.maxCandidates], ['ENROLL', a?.contractsEnabled && !d?.frontier.sealed && unenrolled.value.length > 0], ['BUDGET', a?.budgetAdmissionAvailable && !d?.budget], ['CHECK', a?.budgetAdmissionAvailable && approved], ['EXECUTE', a?.slotExecutionAvailable && approved && executable().length > 0], ['SCORE', a?.platformScoringAvailable && scoreable().length > 0], ['FRONTIER', !unresolvedProposal.value && a?.platformScoringAvailable && !d?.frontier.sealed && !running && !unscored && d?.frontier.scores.some(s => s.candidateId === d.summary.baselineId)], ['HOLDOUT', a?.independentHoldoutAvailable && approved && d?.frontier.sealed && !!d.frontier.selectedCandidateId && !d.holdout], ['CLOSE', !activeProposal.value && a?.independentHoldoutAvailable && !!d?.budget && !running && !unscored && d?.currentCosts.pendingCalls === 0]] as [TeamAction, unknown][]).map(([action, enabled]) => ({ action, label: labels[action], enabled: !!d && !d.closure && !!context.value?.originalSubmitter && !!enabled }))
})
function clearParent() { parentGeneration++; parentPrompts.value = null; newPrompt.value = ''; changedRole.value = ''; parentLoading.value = false }
function clearPrivate() { mode.value = null; clearParent(); Object.assign(draft, newDraft()); Object.assign(budget, newBudget()); clearProposalDraft(); targets.value = [[], []]; agents.value = []; targetGeneration = targetGeneration.map(n => n + 1); formError.value = ''; confirmed.value = false }
function closeForm() { session.cancel(); clearPrivate() }
function readable(run: number, user: unknown) { return alive && run === readGeneration && user === permissions.state.user && permissions.can('runAgentExperiments') }
async function refreshContext() {
  const run = ++readGeneration, user = permissions.state.user, id = props.detail?.summary.id
  context.value = null; availability.value = null; proposals.value = []; clearProposalDraft(); fresh.value = false; readError.value = ''
  if (!permissions.can('runAgentExperiments')) return
  try {
    const status = await TeamOperationApi.status(), current = id ? await TeamOperationApi.context(id) : null
    const history = id && current?.originalSubmitter && Object.hasOwn(status, 'modelProposalAvailable') ? await TeamOperationApi.proposals(id) : []
    if (!readable(run, user) || id !== props.detail?.summary.id) return
    if (!Array.isArray(history) || history.length > 2 || history.some(p => p.contractId !== id)) throw new Error('提案列表与原任务不匹配')
    proposals.value = history; availability.value = status; context.value = current; fresh.value = true
  } catch { if (readable(run, user)) readError.value = '操作条件读取失败，未保留旧条件；请刷新原记录。' }
}
async function openCreate() {
  if (locked.value || !fresh.value || props.loading || !availability.value?.contractsEnabled) return
  clearPrivate(); mode.value = 'CREATE'
  const run = readGeneration, user = permissions.state.user
  try { const list = await EapApi.listAgents(); if (readable(run, user) && mode.value === 'CREATE') agents.value = list.filter(a => a.lifecycleStatus === 'ACTIVE') }
  catch { if (readable(run, user)) formError.value = '智能体列表读取失败，请重新打开创建表单。' }
}
async function loadTargets(index: number) {
  const role = draft.roles[index], id = role.agentId, run = ++targetGeneration[index], user = permissions.state.user
  role.instanceKey = ''; targets.value[index] = []
  if (!id) return
  try { const r = await ExperimentApi.targets(id); if (alive && user === permissions.state.user && permissions.can('runAgentExperiments') && mode.value === 'CREATE' && run === targetGeneration[index] && draft.roles[index].agentId === id) targets.value[index] = r.instances }
  catch { if (run === targetGeneration[index] && user === permissions.state.user) formError.value = '实例列表读取失败，原实例选择已清除。' }
}
function addSample() { if (draft.samples.length < 16) draft.samples.push(sample('HOLDOUT', `sample-${crypto.randomUUID().slice(0, 8)}`)) }
function candidateLabel(id: string) { return id === props.detail?.summary.baselineId ? '基线' : `候选 ${id.slice(0, 12)}` }
function choose(action: TeamAction) { if (locked.value || !fresh.value || !options.value.some(o => o.action === action && o.enabled)) return; clearPrivate(); mode.value = action; parentId.value = ''; candidateId.value = ''; evaluationId.value = '' }
async function readParent() {
  clearParent(); const id = props.detail?.summary.id, parent = parentId.value, run = parentGeneration, user = permissions.state.user
  if (!id || !parent || !permissions.can('viewContent')) return
  parentLoading.value = true
  try { const content = await TeamEvolutionApi.content(id); if (!alive || user !== permissions.state.user || !permissions.can('viewContent') || run !== parentGeneration || props.detail?.summary.id !== id || parentId.value !== parent) return; const candidate = content.candidates.find(c => c.id === parent); if (!candidate) throw new Error(); parentPrompts.value = { ...candidate.configuration.rolePrompts }; changedRole.value = props.detail.roles[0].role; newPrompt.value = parentPrompts.value[changedRole.value] }
  catch { if (run === parentGeneration && user === permissions.state.user) { clearParent(); formError.value = '父代正文读取失败，旧提示词已清除。' } }
  finally { if (run === parentGeneration) parentLoading.value = false }
}
function clearProposalDraft() { proposalGeneration++; proposalDraft.value = null; proposalLoading.value = false; proposalError.value = '' }
function proposalLabel(value: string) { return ({ PREPARING: '准备中', DISPATCHED: '已发送，待回执', COMPLETED: '草稿已生成', FAILED: '提案失败', UNKNOWN: '结果未知', SETTLED: '用量已结算', NO_RESERVATION: '未查到预算预留' } as Record<string, string>)[value] || value }
async function readProposalDraft(proposal: TeamProposal) {
  clearPrivate(); const id = props.detail?.summary.id, run = proposalGeneration, user = permissions.state.user
  if (!id || id !== proposal.contractId || proposal.state !== 'COMPLETED' || !proposal.draftAvailable || !permissions.can('viewContent') || !context.value?.originalSubmitter) return
  proposalLoading.value = true
  try {
    const [draft, content] = await Promise.all([TeamOperationApi.proposalContent(id, proposal.requestKey), TeamEvolutionApi.content(id)])
    if (!alive || run !== proposalGeneration || user !== permissions.state.user || !permissions.can('viewContent') || !permissions.can('runAgentExperiments') || id !== props.detail?.summary.id) return
    const original = content.candidates.find(c => c.id === proposal.parentId)?.configuration.rolePrompts, prompts = draft.configuration?.rolePrompts
    if (draft.parentId !== proposal.parentId || !original || !prompts || !Object.hasOwn(original, proposal.role) || Object.keys(original).length !== 2 || Object.keys(prompts).sort().join('|') !== Object.keys(original).sort().join('|')) throw new Error('不匹配的模型草稿')
    for (const [role, prompt] of Object.entries(prompts)) { if (typeof prompt !== 'string' || !prompt.trim() || new TextEncoder().encode(prompt).length > 8000 || (role !== proposal.role && prompt !== original[role])) throw new Error('非法角色变更') }
    if (prompts[proposal.role] === original[proposal.role]) throw new Error('草稿未变更')
    proposalDraft.value = { rootId: id, requestKey: proposal.requestKey, parentId: proposal.parentId, role: proposal.role, prompt: prompts[proposal.role], parents: { ...original } }
  } catch { if (run === proposalGeneration && user === permissions.state.user) { clearProposalDraft(); proposalError.value = '模型草稿读取或原父代校验失败，旧正文已清除。' } }
  finally { if (run === proposalGeneration) proposalLoading.value = false }
}
function useProposalDraft() {
  const selected = proposalDraft.value
  if (!selected || selected.rootId !== props.detail?.summary.id || !permissions.can('viewContent') || !fresh.value || locked.value || !options.value.some(o => o.action === 'CANDIDATE' && o.enabled)) return
  choose('CANDIDATE'); parentId.value = selected.parentId; parentPrompts.value = { ...selected.parents }; changedRole.value = selected.role; newPrompt.value = selected.prompt
}
function inspectProposal(proposal: TeamProposal) {
  if (locked.value) return
  clearPrivate(); void session.recover({ action: 'PROPOSE', requestKey: proposal.requestKey, rootId: proposal.contractId, body: {}, summary: [] })
}
function text(value: string, limit: number) { if (!value.trim() || new TextEncoder().encode(value).length > limit) throw new Error(`请填写内容，并保持在 ${limit} 字节以内。`); return value }
function key(value: string) { if (!/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/.test(value)) throw new Error('编号或角色名称须为1–64位字母、数字、点、冒号、下划线或连字符，并以字母数字开头。'); return value }
function bounded(value: number, low: number, high: number) { if (!Number.isInteger(value) || value < low || value > high) throw new Error(`请填写 ${low}–${high} 范围内的整数。`); return value }
function prepare() {
  formError.value = ''; confirmed.value = false
  const action = mode.value, id = props.detail?.summary.id
  if (!action || locked.value || !fresh.value) return
  try {
    if (action !== 'CREATE' && !options.value.some(o => o.action === action && o.enabled)) throw new Error('当前原状态不允许此操作，请刷新核对。')
    const requestKey = crypto.randomUUID(), body: Record<string, unknown> = action === 'CHECK' ? {} : { requestKey }; let summary = [labels[action], ...(id && action !== 'CREATE' ? [`原团队 #${id}`] : [])]
    if (action === 'CREATE') {
      if (!draft.publicSamplesConfirmed) throw new Error('请确认样本可公开使用。')
      const roles = draft.roles.map(r => ({ role: key(r.role), agentId: bounded(r.agentId!, 1, Number.MAX_SAFE_INTEGER), instanceKey: text(r.instanceKey, 128) }))
      if (roles[0].agentId === roles[1].agentId || roles[0].role.toLowerCase() === roles[1].role.toLowerCase()) throw new Error('请选择两个不同的智能体及角色名称。')
      const samples = draft.samples.map(s => ({ id: key(s.id), groupId: key(s.groupId), split: s.split, input: text(s.input, 2000), expected: text(s.expected, 2000) }))
      for (const [split, maximum] of [['TRAIN', 3], ['VALIDATION', 3], ['HOLDOUT', 10]] as const) { const n = samples.filter(s => s.split === split).length; if (n < 1 || n > maximum) throw new Error('请按训练1–3、验证1–3、留出1–10条配置样本。') }
      Object.assign(body, { roles, samples, publicSamplesConfirmed: true, maxCandidates: bounded(draft.maxCandidates, 1, 32), maxAttempts: bounded(draft.maxAttempts, 1, 128), maxGeneration: bounded(draft.maxGeneration, 0, 8), minimumQualityBps: bounded(draft.minimumQualityBps, 0, 10000), maxTokens: bounded(draft.maxTokens, 1, 256) })
      summary = [...summary, ...roles.map((r, i) => `角色${i + 1} ${r.role} · Agent #${r.agentId} · ${r.instanceKey}`), `${samples.length}条公开样本；最多${draft.maxCandidates}个候选、${draft.maxAttempts}次提案、${draft.maxGeneration}代`, `质量门槛${draft.minimumQualityBps / 100}%；每角色输出上限${draft.maxTokens} Token`, '只登记新合同，不分配预算或执行模型。']
    } else if (action === 'PROPOSE') {
      if (!proposalParents.value.some(p => p.id === parentId.value) || !props.detail!.roles.some(r => r.role === changedRole.value)) throw new Error('请选择可继续提案的父代和一个原角色。')
      Object.assign(body, { parentId: parentId.value, role: changedRole.value }); summary.push(`父代 ${candidateLabel(parentId.value)}；仅为 ${changedRole.value} 生成草稿`, `只使用公开训练集 ${props.detail!.sampleCounts.TRAIN} 条和父代提示词；验证、留出不送入提案模型`, `本次最多1次模型调用、${props.detail!.budget!.maxProposalTokens} 输出Token，计入原任务总预算`, `原批准截止 ${props.detail!.budget!.approvalExpiresAt}`, '只生成待审草稿；不自动登记候选、执行样本或修改线上提示词。')
    } else if (action === 'CANDIDATE') {
      if (!parentPrompts.value || !changedRole.value || !Object.hasOwn(parentPrompts.value, changedRole.value)) throw new Error('请先读取所选父代提示词。')
      if (newPrompt.value === parentPrompts.value[changedRole.value]) throw new Error('新提示词与父代相同，请只修改一个角色。')
      Object.assign(body, { parentId: parentId.value, configuration: { rolePrompts: { ...parentPrompts.value, [changedRole.value]: text(newPrompt.value, 8000) } } }); summary.push(`父代 ${candidateLabel(parentId.value)}；仅变更 ${changedRole.value}`, `新提示词：${newPrompt.value}`, '登记一次提示词候选，不调用模型；拒绝也计入提案次数。')
    } else if (action === 'ENROLL') {
      if (!unenrolled.value.some(c => c.id === candidateId.value)) throw new Error('请选择尚未登记验证的原候选。')
      Object.assign(body, { candidateId: candidateId.value, split: 'VALIDATION' }); summary.push(candidateLabel(candidateId.value), `全验证集 ${props.detail!.sampleCounts.VALIDATION} 条、每条两个角色；只登记，不执行。`)
    } else if (action === 'BUDGET') {
      if (!budget.confirmed) throw new Error('请明确确认本次独立预算。')
      const calls = bounded(budget.maxCalls, 1, 128), tokens = bounded(budget.maxOutputTokens, 1, 32768), until = new Date(budget.approvalExpiresAt), seconds = (until.getTime() - Date.now()) / 1000
      if (!Number.isFinite(seconds) || seconds < 30 || seconds > 900) throw new Error('批准截止须在未来30–900秒。')
      if (calls < requiredCalls.value || tokens < requiredTokens.value) throw new Error('额度不足以覆盖全部候选及两套独立留出。')
      Object.assign(body, { planKey: `plan-${crypto.randomUUID()}`, businessTask: text(budget.businessTask, 2000), independentBudgetConfirmed: true, maxCalls: calls, maxOutputTokens: tokens, approvalExpiresAt: until.toISOString(), proposalMode: budget.proposalMode });
      if (budget.proposalMode === 'MODEL_SINGLE_ROLE') {
        if (!availability.value?.modelProposalAvailable || !budget.proposalUseConfirmed) throw new Error('请明确批准父提示词及公开训练样本用于模型提案。')
        const count = bounded(budget.maxProposalCalls, 1, 2), bound = bounded(budget.maxProposalTokens, 64, 512)
        if (count > props.detail!.summary.maxAttempts - props.detail!.summary.attempts) throw new Error('合同剩余提案次数不足。')
        Object.assign(body, { proposalUseConfirmed: true, maxProposalCalls: count, maxProposalTokens: bound }); summary.push(`明确批准模型提案：最多${count}次，每次${bound}输出Token，含在总预算内`, '父提示词及公开TRAIN样本可用于模型提案；不使用验证或留出数据。')
      } else if (budget.proposalMode !== 'OFFLINE_ONLY') throw new Error('提案用途无效。')
      summary.push(budget.businessTask, `独立上限 ${calls} 次模型 / ${tokens} 输出 Token / 0 次业务工具`, `批准截止 ${until.toLocaleString('zh-CN')}`, '没有输入Token或金额硬上限；不预留共享容量，不能续期或扩大该计划。')
    } else if (action === 'EXECUTE' || action === 'SCORE') {
      const evaluation = eligibleEvaluations.value.find(e => e.registration.id === evaluationId.value); if (!evaluation) throw new Error('请选择当前可操作的原评价批次。')
      summary.push(`原评价 ${evaluationId.value}`, `${evaluation.registration.split} · ${candidateLabel(evaluation.registration.candidateId)}`)
      if (action === 'EXECUTE') summary.push(`最多 ${evaluation.registration.slots.length} 个原槽位，每角色输出上限 ${context.value!.maxTokens} Token`, `原批准截止 ${props.detail!.budget!.approvalExpiresAt}`, '本次实际调用模型；发生失败或未知即保留原结果，不自动换批次或重跑。')
      else summary.push('只对完整可信原证据评分，不执行模型。')
    } else summary.push(explanation[action] || '')
    const intent: TeamIntent = { action, requestKey, body, summary, ...(action === 'CREATE' ? {} : { rootId: id }), ...(['EXECUTE', 'SCORE'].includes(action) ? { evaluationId: evaluationId.value } : {}) }
    session.prepare(intent)
  } catch (e) { formError.value = (e as Error).message }
}
function submit() {
  if (!confirmed.value || !state.intent) return
  const intent = state.intent, now = Date.now()
  if (intent.action === 'BUDGET') {
    const seconds = (Date.parse(String(intent.body.approvalExpiresAt)) - now) / 1000
    if (!Number.isFinite(seconds) || seconds < 30 || seconds > 900) { readError.value = '核对期间批准期限已不足30秒，请返回修改后重新核对。'; return }
  }
  if (['EXECUTE', 'PROPOSE', 'HOLDOUT', 'CHECK'].includes(intent.action) && (!props.detail?.budget || Date.parse(props.detail.budget.approvalExpiresAt) <= now)) { readError.value = '原批准期限已过，不能继续提交此操作。'; return }
  void session.confirm()
}
function refreshFacts() { const id = state.rootToRead || state.intent?.rootId || props.detail?.summary.id; if (id) emit('read-root', id); else { session.reset(); void refreshContext() } }
function recover() {
  formError.value = ''
  try {
    const intent: TeamIntent = { action: recovery.action, requestKey: key(recovery.requestKey), body: {}, summary: [], ...(recovery.action === 'CREATE' ? {} : { rootId: bounded(recovery.rootId, 1, Number.MAX_SAFE_INTEGER) }), ...(['EXECUTE', 'SCORE'].includes(recovery.action) ? { evaluationId: recovery.evaluationId } : {}) }
    if (intent.action === 'CHECK' || (intent.evaluationId !== undefined && !/^[a-fA-F0-9-]{36}$/.test(intent.evaluationId))) throw new Error('请填写完整的原请求及评价编号。')
    clearPrivate(); void session.recover(intent)
  } catch (e) { readError.value = (e as Error).message }
}
watch(() => props.detail, () => { readGeneration++; session.navigate(); clearPrivate(); void refreshContext() }, { flush: 'sync' })
watch(() => [permissions.state.user, permissions.can('runAgentExperiments'), permissions.can('viewContent')], () => { readGeneration++; session.reset(); clearPrivate(); context.value = null; availability.value = null; proposals.value = []; fresh.value = false; Object.assign(recovery, { action: 'CREATE', requestKey: '', rootId: 1, evaluationId: '' }); if (permissions.can('runAgentExperiments')) void refreshContext() }, { flush: 'sync' })
onMounted(() => { void refreshContext() })
onUnmounted(() => { alive = false; readGeneration++; session.dispose(); clearPrivate() })
</script>

<style scoped>
.ops-proposals{margin-top:20px}.ops-proposal-row{padding:14px;border:1px solid #dbe4ed;border-radius:6px;margin-bottom:10px}.ops-proposal-row code{display:block;overflow-wrap:anywhere;white-space:pre-wrap;margin-top:8px}.ops-heading,.ops-buttons,.ops-choices{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.ops-heading{justify-content:space-between}.ops-hint{font-size:13px;color:#64748b;line-height:1.7}.ops-form,.ops-review{display:grid;gap:16px;margin-top:20px;padding:20px;border:1px solid #dbe4ed;border-radius:8px}.ops-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.ops-form label,.ops-recovery label{display:grid;gap:8px;font-size:14px}.ops-form fieldset{border:1px solid #dbe4ed;border-radius:6px;min-width:0;display:grid;gap:14px;padding:16px}.ops-form legend{font-size:14px}.ops-form pre,.ops-review pre{white-space:pre-wrap;overflow-wrap:anywhere;padding:12px;background:#f8fafc}.ops-review{background:#f8fafc}.ops-review li,.ops-review code{white-space:pre-wrap;overflow-wrap:anywhere}.ops-recovery{margin-top:18px}.ops-recovery summary{cursor:pointer;font-size:13px;color:#35617d}.ops-recovery .ops-grid{margin:16px 0}.ops-form .el-select,.ops-recovery .el-select{width:100%;min-width:0}.ops-form :deep(.el-checkbox),.ops-review :deep(.el-checkbox){height:auto;white-space:normal}.ops-form :deep(.el-checkbox__label),.ops-review :deep(.el-checkbox__label){white-space:normal;line-height:1.6}.ops-buttons .el-button,.ops-choices .el-button{margin-left:0}@media(max-width:760px){.ops-grid{grid-template-columns:minmax(0,1fr)}.ops-form,.ops-review{padding:14px}.ops-heading{align-items:flex-start}.ops-limits .el-input-number{width:100%}}
</style>
