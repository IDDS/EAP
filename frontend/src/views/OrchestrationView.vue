<template>
  <section class="page-grid orchestration-workspace">
    <div class="toolbar">
      <div class="toolbar-title">
        <h2>团队与计划</h2>
        <span>围绕一个目标安排成员，按步骤执行，逐项核对结果。</span>
      </div>
      <div class="plan-team-picker">
        <label for="plan-team">当前团队</label>
        <el-select id="plan-team" v-model="selectedTeamId" class="agent-select" popper-class="plan-select-options" :loading="loading" placeholder="选择团队" @change="loadTeamDetail">
          <el-option v-for="team in teams" :key="team.id" :label="`${team.name} · ${team.code}`" :value="team.id" />
        </el-select>
      </div>
    </div>

    <el-alert v-if="executionError" :title="executionError" type="error" :closable="false" />
    <el-button v-if="executionError && !teams.length && !loading" @click="load">重新加载团队</el-button>

    <div class="plan-workspace-grid">
      <aside class="plan-library" aria-labelledby="plan-library-heading" :aria-busy="loading || teamLoading">
        <div class="plan-section-heading">
          <h3 id="plan-library-heading">任务计划 <span class="plan-count">{{ plans.length }}</span></h3>
          <el-button text :icon="Plus" :disabled="!selectedTeamId || teamLoading || !permissions.can('runWorkflows')" @click="openPlanForm">新增计划</el-button>
        </div>
        <p v-if="loading || teamLoading" class="plan-empty" role="status">正在加载计划…</p>
        <div v-else-if="teamError" class="plan-empty" role="alert"><p>{{ teamError }}</p><el-button @click="loadTeamDetail">重新加载计划</el-button></div>
        <ul v-else-if="plans.length" class="plan-library-list" aria-label="选择计划">
          <li v-for="plan in plans" :key="plan.id">
            <button type="button" class="plan-choice" :aria-pressed="plan.id === selectedPlanId" @click="selectPlan(plan.id)">
              <span class="plan-choice-meta"><span>计划 #{{ plan.id }}</span><el-tag :type="statusType(plan.status)" size="small">{{ statusLabel(plan.status) }}</el-tag></span>
              <strong>{{ plan.goal }}</strong>
              <span v-if="plan.id === selectedPlanId" class="plan-choice-current">当前计划</span>
            </button>
          </li>
        </ul>
        <div v-else class="plan-empty">
          <strong>{{ selectedTeamId ? '这个团队还没有计划' : '尚未选择团队' }}</strong>
          <p>{{ selectedTeamId ? (permissions.can('runWorkflows') ? '点击“新增计划”，先写下这次协作的目标。' : '团队创建计划后，可在这里查看步骤与进度。') : '选择一个团队，查看它的任务计划。' }}</p>
        </div>
      </aside>

      <div v-if="activePlan" class="plan-detail" :aria-busy="planLoading">
        <header class="plan-summary">
          <div class="plan-summary-meta"><span>计划 #{{ activePlan.id }}</span><el-tag :type="statusType(activePlan.status)">{{ statusLabel(activePlan.status) }}</el-tag><span v-if="frozen">已提交，配置已锁定</span></div>
          <h3>{{ activePlan.goal }}</h3>
          <details v-if="activePlan.contextSummary" class="plan-context"><summary>上下文说明</summary><p>{{ activePlan.contextSummary }}</p></details>
        </header>

        <el-alert v-if="planError" :title="planError" type="error" :closable="false" />
        <section class="plan-steps-section" aria-labelledby="plan-steps-heading">
          <div class="plan-section-heading">
            <div><h4 id="plan-steps-heading">计划步骤</h4><p class="muted">{{ planLoading ? '正在加载步骤…' : `${planSteps.length} 个步骤，${assignedStepCount} 个已分配成员` }}</p></div>
            <el-button text :icon="Plus" :disabled="!selectedPlanId || planLoading || !!planError || frozen || !permissions.can('runWorkflows')" @click="openStepForm">新增步骤</el-button>
          </div>
          <p v-if="planLoading" class="plan-empty" role="status">正在读取当前计划…</p>
          <ol v-else-if="planSteps.length" class="plan-step-list">
            <li v-for="row in planSteps" :key="row.id" class="plan-step">
              <span class="plan-step-order" :aria-label="`第 ${row.stepOrder} 步`">{{ row.stepOrder }}</span>
              <div class="plan-step-body">
                <div class="plan-step-title"><h5>{{ row.title }}</h5><el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></div>
                <dl class="plan-step-facts"><div><dt>执行方式</dt><dd>{{ row.executionBackend === 'AX' ? '隔离沙箱' : '常规执行' }}</dd></div><div><dt>所需能力</dt><dd>{{ row.requiredCapability }}</dd></div></dl>
                <div class="plan-step-contract"><span>结果契约</span><strong>{{ planOutputLabel(row.outputContractJson) }}</strong><el-button link type="primary" @click="openContract(row)">{{ frozen || !permissions.can('runWorkflows') ? '查看契约' : '配置契约' }}</el-button></div>
              </div>
              <div class="plan-step-assignment">
                <label :for="`plan-member-${row.id}`">执行成员</label>
                <el-select :id="`plan-member-${row.id}`" :model-value="row.assignedAgentId" popper-class="plan-select-options" :disabled="frozen || !!planError || !permissions.can('runWorkflows')" placeholder="选择成员" @change="assignMember(row.id, $event)">
                  <el-option v-for="member in members" :key="member.id" :label="`${member.role} · Agent ${member.agentId}`" :value="member.agentId" />
                </el-select>
                <span v-if="!row.assignedAgentId" class="muted">尚未分配</span>
                <span v-else class="muted">Agent {{ row.assignedAgentId }}</span>
              </div>
            </li>
          </ol>
          <div v-else-if="!planError" class="plan-empty"><strong>还没有步骤</strong><p>{{ permissions.can('runWorkflows') && !frozen ? '把目标拆成有序步骤，再为每一步选择执行成员。' : '当前计划尚未记录步骤。' }}</p></div>
        </section>

        <section class="plan-execution-section" aria-labelledby="plan-execution-heading">
          <div class="plan-section-heading"><h4 id="plan-execution-heading">执行与结果</h4><el-button :disabled="planLoading" @click="selectPlan(selectedPlanId!)">刷新进度</el-button></div>
          <div class="plan-execution-actions">
            <el-checkbox v-model="suspendAfterTask" :disabled="frozen || submitting || !permissions.can('runWorkflows')">步骤完成后休眠（专用实例）</el-checkbox>
            <el-button type="primary" :loading="submitting" :disabled="frozen || planLoading || !!planError || !planSteps.length || reclaimBlocked || !permissions.can('runWorkflows')" @click="executePlan">{{ frozen ? '已提交执行' : '执行计划' }}</el-button>
          </div>
          <p v-if="!permissions.can('runWorkflows')" class="plan-readonly-note">当前账号可查看计划，不能配置或执行。</p>
          <p class="plan-execution-guidance">按顺序执行，后一步接收前一步结果。提交后锁定成员分配与结果契约；结构不合格时停止后续交接。</p>
          <p class="plan-execution-guidance">未配置契约不代表业务验收通过。失败或结果未知时不会自动重跑，请先核对证据。</p>
          <PlanReclaimCheck v-if="suspendAfterTask && !frozen" :result="reclaimResult" :loading="reclaimLoading" :error="reclaimError" @refresh="refreshReclaim" />
          <ExecutionTimeline v-if="selectedExecution" :execution="selectedExecution" @changed="selectPlan(selectedPlanId!)" />
          <PlanOutputs v-if="activePlan.executionWorkflowId && activePlan.executionRunKey"
            :key="`${activePlan.executionWorkflowId}:${activePlan.executionRunKey}`"
            :workflow-id="activePlan.executionWorkflowId" :run-key="activePlan.executionRunKey" :steps="planSteps" />
        </section>
      </div>
      <div v-else class="plan-detail plan-empty plan-unselected"><h3>{{ teamLoading || loading ? '正在读取团队计划…' : '从一个明确目标开始协作' }}</h3><p>选择计划后，可在这里安排步骤、查看执行进度和结果。</p></div>
    </div>
    <el-collapse class="team-reference"><el-collapse-item name="members" title="团队与成员资料">
    <div class="registry-layout">
      <el-card shadow="never" class="panel">
        <template #header>团队</template>
        <el-table :data="teams" v-loading="loading">
          <el-table-column prop="name" label="团队" min-width="170">
            <template #default="{ row }">
              <strong>{{ row.name }}</strong>
              <div class="muted">{{ row.code }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="coordinationMode" label="协作模式" width="150" />
          <el-table-column prop="workspacePolicy" label="工作区策略" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag type="success">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>成员角色</template>
        <el-table :data="members">
          <el-table-column prop="agentId" label="Agent ID" width="100" />
          <el-table-column prop="role" label="角色" width="160" />
          <el-table-column prop="responsibility" label="责任" min-width="260" />
        </el-table>
      </el-card>
    </div>
    </el-collapse-item></el-collapse>
    <el-dialog v-model="planFormOpen" title="新增计划" width="min(560px, 94vw)" :close-on-click-modal="!savingPlan" :show-close="!savingPlan">
      <el-form label-position="top" @submit.prevent="savePlan">
        <el-form-item label="计划目标" label-for="plan-goal" required><el-input id="plan-goal" v-model="planDraft.goal" maxlength="128" show-word-limit :disabled="savingPlan" /></el-form-item>
        <el-form-item label="上下文说明" label-for="plan-context"><el-input id="plan-context" v-model="planDraft.contextSummary" type="textarea" :rows="4" maxlength="1024" show-word-limit :disabled="savingPlan" /></el-form-item>
        <el-alert v-if="planFormError" :title="planFormError" type="error" :closable="false" />
        <div class="button-row"><el-button :disabled="savingPlan" @click="planFormOpen = false">取消</el-button><el-button type="primary" native-type="submit" :loading="savingPlan" :disabled="!permissions.can('runWorkflows')">创建计划</el-button></div>
      </el-form>
    </el-dialog>
    <el-dialog v-model="stepFormOpen" title="新增步骤" width="min(560px, 94vw)" :close-on-click-modal="!savingStep" :show-close="!savingStep">
      <el-form label-position="top" @submit.prevent="saveStep">
        <el-form-item label="步骤顺序" label-for="step-order" required><el-input-number id="step-order" v-model="stepDraft.stepOrder" :min="1" :max="2147483647" :precision="0" :disabled="savingStep" /></el-form-item>
        <el-form-item label="步骤任务" label-for="step-title" required><el-input id="step-title" v-model="stepDraft.title" maxlength="128" show-word-limit :disabled="savingStep" /></el-form-item>
        <el-form-item label="执行方式"><el-select v-model="stepDraft.executionBackend" :disabled="savingStep"><el-option label="常规执行" value="RUNTIME" /><el-option label="隔离沙箱 · 完成后自动休眠" value="AX" /></el-select></el-form-item>
        <el-form-item label="所需能力" label-for="step-capability" required><el-input id="step-capability" v-model="stepDraft.requiredCapability" maxlength="128" show-word-limit :disabled="savingStep" /></el-form-item>
        <WorkflowOutputConfig v-model="stepOutputConfig" node-type="AGENT" plan-step :disabled="savingStep || frozen || !permissions.can('runWorkflows')" />
        <p class="muted">保存后在步骤列表分配团队成员。所有步骤分配完成后再执行计划。</p>
        <el-alert v-if="stepFormError" :title="stepFormError" type="error" :closable="false" />
        <div class="button-row"><el-button :disabled="savingStep" @click="stepFormOpen = false">取消</el-button><el-button type="primary" native-type="submit" :loading="savingStep" :disabled="frozen || !permissions.can('runWorkflows')">保存步骤</el-button></div>
      </el-form>
    </el-dialog>
    <el-dialog v-model="contractFormOpen" title="步骤结果契约" width="min(640px, 94vw)" :close-on-click-modal="!savingContract" :show-close="!savingContract">
      <template v-if="contractStep">
        <p>{{ contractStep.stepOrder }}. {{ contractStep.title }}</p>
        <p v-if="frozen" class="muted">计划已提交，原结果契约随执行冻结，仅可查看。修改要求请另建计划，不重发原任务。</p>
        <WorkflowOutputConfig v-model="contractConfig" node-type="AGENT" :agent-id="contractStep.assignedAgentId" plan-step :disabled="frozen || savingContract || !permissions.can('runWorkflows')" />
        <el-alert v-if="contractFormError" :title="contractFormError" type="error" :closable="false" />
        <div class="button-row"><el-button :disabled="savingContract" @click="contractFormOpen = false">关闭</el-button><el-button v-if="!frozen && permissions.can('runWorkflows')" type="primary" :loading="savingContract" @click="saveContract">保存结果契约</el-button></div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import PlanOutputs from '@/components/workflow/PlanOutputs.vue'
import PlanReclaimCheck from '@/components/workflow/PlanReclaimCheck.vue'
import { planSubmissionError, usePlanReclaimCheck } from '@/api/planReclaim'
import ExecutionTimeline from '@/components/workflow/ExecutionTimeline.vue'
import WorkflowOutputConfig from '@/components/workflow/WorkflowOutputConfig.vue'
import { taskConfig, planOutputConfig, planOutputValue, planOutputLabel } from '@/api/workflowTaskConfig'
import type { AgentPlan, AgentPlanStep, AgentTeam, AgentTeamMember, WorkflowExecution } from '@/types/domain'

const permissions = usePermissions()
const submitting = ref(false)
const suspendAfterTask = ref(false)
const executionError = ref('')
const selectedExecution = ref<WorkflowExecution>()
const activePlan = computed(() => plans.value.find(p => p.id === selectedPlanId.value))
const frozen = computed(() => !!plans.value.find(p => p.id === selectedPlanId.value)?.executionRunKey)
let disposed = false
let polling = false
let poll: ReturnType<typeof setInterval> | undefined
const loading = ref(false)
const teamLoading = ref(false)
const planLoading = ref(false)
const teamError = ref('')
const planError = ref('')
const teams = ref<AgentTeam[]>([])
const members = ref<AgentTeamMember[]>([])
const plans = ref<AgentPlan[]>([])
const planSteps = ref<AgentPlanStep[]>([])
const assignedStepCount = computed(() => planSteps.value.filter(step => step.assignedAgentId != null).length)
const selectedTeamId = ref<number>()
const selectedPlanId = ref<number>()
const { result: reclaimResult, loading: reclaimLoading, error: reclaimError, blocked: reclaimBlocked, refresh: refreshReclaim } = usePlanReclaimCheck({
  planId: () => selectedPlanId.value,
  enabled: () => suspendAfterTask.value && !frozen.value && !planLoading.value && !planError.value,
  revision: () => planSteps.value,
  principal: () => permissions.state.user,
})

async function load() {
  loading.value = true
  try {
    teams.value = await EapApi.listTeams()
    selectedTeamId.value = teams.value[0]?.id
    await loadTeamDetail()
  } catch {
    if (!disposed) executionError.value = '团队加载失败，请登录后刷新重试'
  } finally {
    loading.value = false
  }
}

let teamRequestId = 0
let planRequestId = 0
async function loadTeamDetail() {
  const requestId = ++teamRequestId
  const teamId = selectedTeamId.value
  ++planRequestId
  members.value = []
  plans.value = []
  planSteps.value = []
  selectedPlanId.value = undefined
  selectedExecution.value = undefined
  planLoading.value = false
  teamError.value = ''
  planError.value = ''
  teamLoading.value = !!teamId
  if (!teamId) return
  try {
    const [nextMembers, nextPlans] = await Promise.all([
      EapApi.listTeamMembers(teamId),
      EapApi.listTeamPlans(teamId),
    ])
    if (disposed || requestId !== teamRequestId || teamId !== selectedTeamId.value) return
    members.value = nextMembers
    plans.value = nextPlans
    selectedPlanId.value = nextPlans[0]?.id
    if (selectedPlanId.value) await selectPlan(selectedPlanId.value)
  } catch {
    if (!disposed && requestId === teamRequestId && teamId === selectedTeamId.value) teamError.value = '团队计划读取失败，请重新加载。'
  } finally {
    if (!disposed && requestId === teamRequestId) teamLoading.value = false
  }
}

async function selectPlan(planId: number, background = false) {
  const requestId = ++planRequestId
  const teamId = selectedTeamId.value
  const changed = planId !== selectedPlanId.value
  selectedPlanId.value = planId
  if (changed || !background) {
    selectedExecution.value = undefined
    planSteps.value = []
    planLoading.value = true
  }
  planError.value = ''
  try {
    const detail = await EapApi.getPlan(planId)
    if (disposed || requestId !== planRequestId || teamId !== selectedTeamId.value || planId !== selectedPlanId.value) return
    planSteps.value = detail.steps
    if (detail.plan) plans.value = plans.value.map(p => p.id === planId ? detail.plan : p)
    if (detail.plan?.executionRunKey) {
      const result = await EapApi.getPlanExecution(planId)
      if (!disposed && requestId === planRequestId && teamId === selectedTeamId.value && planId === selectedPlanId.value) selectedExecution.value = result?.execution
    } else selectedExecution.value = undefined
  } catch {
    if (!disposed && requestId === planRequestId && teamId === selectedTeamId.value && planId === selectedPlanId.value) planError.value = background ? '进度刷新失败，当前显示上次读取的记录。请刷新核对。' : '计划详情读取失败，请刷新进度后再操作。'
  } finally {
    if (!disposed && requestId === planRequestId) planLoading.value = false
  }
}

const planFormOpen = ref(false)
const stepFormOpen = ref(false)
const savingPlan = ref(false)
const savingStep = ref(false)
const planFormError = ref('')
const stepFormError = ref('')
const planDraft = ref({ goal: '', contextSummary: '' })
const stepDraft = ref({ stepOrder: 1, title: '', requiredCapability: '', executionBackend: 'RUNTIME' })
const stepContractConfig = ref('{}')
const stepOutputConfig = computed({
  get: () => JSON.stringify({ ...taskConfig(stepContractConfig.value), executionBackend: stepDraft.value.executionBackend }),
  set: (value: string) => { stepContractConfig.value = value },
})
const contractFormOpen = ref(false)
const contractStep = ref<AgentPlanStep>()
const contractConfig = ref('{}')
const contractFormError = ref('')
const savingContract = ref(false)
let contractPlanId: number | undefined
function openContract(step: AgentPlanStep) {
  if (savingContract.value || step.planId !== selectedPlanId.value) return
  try { contractConfig.value = planOutputConfig(step.outputContractJson, step.executionBackend) }
  catch { executionError.value = '结果契约无法读取，请核对原配置'; return }
  contractPlanId = selectedPlanId.value
  contractStep.value = step
  contractFormError.value = ''
  contractFormOpen.value = true
}
async function saveContract() {
  if (!contractFormOpen.value || !contractStep.value || savingContract.value || frozen.value || !permissions.can('runWorkflows') || contractPlanId !== selectedPlanId.value || !contractPlanId) return
  let definition: string | null
  try { definition = planOutputValue(contractConfig.value, contractStep.value.executionBackend) }
  catch (error) { contractFormError.value = (error as Error).message; return }
  const planId = contractPlanId, stepId = contractStep.value.id
  savingContract.value = true
  contractFormError.value = ''
  try {
    await EapApi.updatePlanOutputContract(stepId, definition)
    if (!disposed && selectedPlanId.value === planId && permissions.can('runWorkflows')) {
      contractFormOpen.value = false
      await selectPlan(planId)
    }
  } catch { contractFormError.value = '保存未确认，请刷新查看原步骤契约及计划冻结状态；不会自动重试' }
  finally { savingContract.value = false }
}
let formTeamId: number | undefined
let formPlanId: number | undefined

function openPlanForm() {
  if (!selectedTeamId.value || !permissions.can('runWorkflows') || savingPlan.value) return
  formTeamId = selectedTeamId.value
  planDraft.value = { goal: '', contextSummary: '' }
  planFormError.value = ''
  planFormOpen.value = true
}
function openStepForm() {
  if (!selectedPlanId.value || frozen.value || !permissions.can('runWorkflows') || savingStep.value) return
  formPlanId = selectedPlanId.value
  stepDraft.value = { stepOrder: Math.max(0, ...planSteps.value.map(s => s.stepOrder)) + 1, title: '', requiredCapability: '', executionBackend: 'RUNTIME' }
  stepContractConfig.value = '{}'
  stepFormError.value = ''
  stepFormOpen.value = true
}
async function savePlan() {
  if (!planFormOpen.value || savingPlan.value || !permissions.can('runWorkflows') || formTeamId !== selectedTeamId.value || !formTeamId) return
  const goal = planDraft.value.goal.trim()
  const contextSummary = planDraft.value.contextSummary.trim()
  if (!goal || goal.length > 128 || contextSummary.length > 1024) { planFormError.value = '请填写计划目标（最多 128 字），上下文最多 1024 字'; return }
  const teamId = formTeamId
  savingPlan.value = true
  planFormError.value = ''
  try {
    const plan = await EapApi.createPlan({ teamId, goal, contextSummary })
    planFormOpen.value = false
    if (!disposed && teamId === selectedTeamId.value) {
      plans.value = [...plans.value, plan]
      await selectPlan(plan.id)
    }
  } catch {
    planFormError.value = '创建未确认，请刷新计划列表核对后再操作，避免重复创建'
  } finally { savingPlan.value = false }
}
async function saveStep() {
  if (!stepFormOpen.value || savingStep.value || frozen.value || !permissions.can('runWorkflows') || formPlanId !== selectedPlanId.value || !formPlanId) return
  const { stepOrder } = stepDraft.value
  const title = stepDraft.value.title.trim()
  const requiredCapability = stepDraft.value.requiredCapability.trim()
  if (!Number.isInteger(stepOrder) || stepOrder < 1 || stepOrder > 2147483647 || planSteps.value.some(s => s.stepOrder === stepOrder) || !title || title.length > 128 || !requiredCapability || requiredCapability.length > 128) {
    stepFormError.value = '顺序须为未使用的正整数，任务与所需能力必填且各不超过 128 字'; return
  }
  let outputContractJson: string | null
  try { outputContractJson = planOutputValue(stepContractConfig.value, stepDraft.value.executionBackend) }
  catch (error) { stepFormError.value = (error as Error).message; return }
  const planId = formPlanId
  savingStep.value = true
  stepFormError.value = ''
  try {
    await EapApi.addPlanStep(planId, { stepOrder, title, requiredCapability, executionBackend: stepDraft.value.executionBackend, outputContractJson })
    stepFormOpen.value = false
    if (!disposed && planId === selectedPlanId.value) await selectPlan(planId)
  } catch {
    stepFormError.value = '保存未确认，请刷新核对步骤顺序及计划是否已冻结后再操作'
  } finally { savingStep.value = false }
}
watch([selectedTeamId, selectedPlanId, () => permissions.state.user], () => {
  planFormOpen.value = false
  stepFormOpen.value = false
  contractFormOpen.value = false
  contractStep.value = undefined
  contractConfig.value = '{}'
}, { flush: 'sync' })

async function assignMember(stepId: number, assignedAgentId: number) {
  if (frozen.value || !permissions.can('runWorkflows')) return
  const planId = selectedPlanId.value
  await EapApi.assignPlanStep(stepId, { assignedAgentId, status: 'ASSIGNED' })
  if (planId && selectedPlanId.value === planId) await selectPlan(planId)
}

async function executePlan() {
  const planId = selectedPlanId.value
  if (!planId || submitting.value || frozen.value || planLoading.value || planError.value || !planSteps.value.length || reclaimBlocked.value || !permissions.can('runWorkflows')) return
  submitting.value = true
  executionError.value = ''
  try {
    await EapApi.executePlan(planId, { requestKey: `plan-${planId}`, suspendAfterTask: suspendAfterTask.value })
    if (selectedPlanId.value === planId) await selectPlan(planId)
    ElMessage.success('计划已提交，请查看真实执行进度')
  } catch (error) {
    if (selectedPlanId.value === planId) executionError.value = planSubmissionError(error)
  } finally { submitting.value = false }
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    DRAFT: '草稿', READY: '待执行', QUEUED: '排队中', RUNNING: '执行中',
    COMPLETED: '已完成', CANCELLED: '已停止后续调度', DONE: '已完成', FAILED: '失败', BLOCKED: '已阻断',
    WAITING_APPROVAL: '待审批', EXECUTION_UNKNOWN: '结果未知', UNKNOWN: '结果未知',
    PENDING: '待分配', ASSIGNED: '已分配', SKIPPED: '已跳过',
  }
  return labels[status] ?? status
}
function statusType(status: string) {
  if (['COMPLETED', 'DONE'].includes(status)) return 'success'
  if (['FAILED', 'BLOCKED'].includes(status)) return 'danger'
  if (['RUNNING', 'WAITING_APPROVAL', 'EXECUTION_UNKNOWN', 'UNKNOWN'].includes(status)) return 'warning'
  return 'info'
}

onMounted(() => {
  void load()
  poll = setInterval(() => {
    const plan = plans.value.find(p => p.id === selectedPlanId.value)
    if (!disposed && !polling && plan && ['QUEUED', 'RUNNING'].includes(plan.status)) {
      polling = true
      void selectPlan(plan.id, true).catch(() => { if (!disposed && selectedPlanId.value === plan.id) executionError.value = '进度刷新失败，请重试' }).finally(() => { polling = false })
    }
  }, 3000)
})
onUnmounted(() => { disposed = true; ++teamRequestId; ++planRequestId; if (poll) clearInterval(poll) })
</script>


<style scoped>
@media (max-width: 760px) {
  .page-grid, .page-grid > * { min-width: 0; max-width: 100%; }
  .button-row { flex-wrap: wrap; align-items: center; }
  .button-row > strong { flex-basis: 100%; }
  .button-row .el-button { margin-left: 0; }
  .button-row .el-checkbox { height: auto; max-width: 100%; }
  .button-row :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; }
  .agent-select { width: 100%; min-width: 0; }
}
</style>
