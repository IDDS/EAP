<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>团队与计划</strong>
        <span>先选择计划，再分配步骤、执行并查看结果。团队与成员资料可在下方展开。</span>
      </div>
      <el-select v-model="selectedTeamId" class="agent-select" @change="loadTeamDetail">
        <el-option v-for="team in teams" :key="team.id" :label="`${team.name} · ${team.code}`" :value="team.id" />
      </el-select>
    </div>

    <el-alert v-if="executionError" :title="executionError" type="error" :closable="false" />

    <div class="page-grid">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>任务计划</span>
            <el-button text :icon="Plus" :disabled="!selectedTeamId || !permissions.can('runWorkflows')" @click="openPlanForm">新增计划</el-button>
          </div>
        </template>
        <el-table :data="plans">
          <el-table-column prop="goal" label="目标" min-width="220" />
          <el-table-column prop="contextSummary" label="上下文" min-width="280" show-overflow-tooltip />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag>{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button text @click="selectPlan(row.id)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>计划步骤</span>
            <el-button text :icon="Plus" :disabled="!selectedPlanId || frozen || !permissions.can('runWorkflows')" @click="openStepForm">新增步骤</el-button>
          </div>
        </template>
        <el-table :data="planSteps">
          <el-table-column prop="stepOrder" label="#" width="60" />
          <el-table-column prop="title" label="步骤" min-width="180" />
          <el-table-column label="执行方式" width="130"><template #default="{ row }">{{ row.executionBackend === 'AX' ? '隔离沙箱' : '常规执行' }}</template></el-table-column>
          <el-table-column prop="requiredCapability" label="所需能力" min-width="170" />
          <el-table-column prop="assignedAgentId" label="Agent" width="90" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="stepType(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="分配成员" min-width="170">
            <template #default="{ row }">
              <el-select :model-value="row.assignedAgentId" :disabled="frozen || !permissions.can('runWorkflows')" placeholder="选择成员" @change="assignMember(row.id, $event)">
                <el-option v-for="member in members" :key="member.id" :label="`${member.role} · ${member.agentId}`" :value="member.agentId" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
    <el-card v-if="selectedPlanId" shadow="never" class="panel">
      <template #header><div class="button-row">
        <strong>计划真实执行</strong>
        <el-checkbox v-model="suspendAfterTask" :disabled="frozen || submitting || !permissions.can('runWorkflows')">步骤完成后休眠（专用实例）</el-checkbox>
        <el-button type="primary" :loading="submitting" :disabled="frozen || !planSteps.length || !permissions.can('runWorkflows')" @click="executePlan">{{ frozen ? '已提交执行' : '执行计划' }}</el-button>
        <el-button @click="selectPlan(selectedPlanId)">刷新进度</el-button>
      </div></template>
      <p class="muted">按步骤顺序执行，后一步接收前一步结果。提交后冻结分配；失败或结果未知时不会自动重跑，请先核对证据。</p>
      <ExecutionTimeline v-if="selectedExecution" :execution="selectedExecution" />
      <PlanOutputs v-if="activePlan?.executionWorkflowId && activePlan.executionRunKey"
        :key="`${activePlan.executionWorkflowId}:${activePlan.executionRunKey}`"
        :workflow-id="activePlan.executionWorkflowId" :run-key="activePlan.executionRunKey" :steps="planSteps" />
    </el-card>
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
        <p class="muted">保存后在步骤列表分配团队成员。所有步骤分配完成后再执行计划。</p>
        <el-alert v-if="stepFormError" :title="stepFormError" type="error" :closable="false" />
        <div class="button-row"><el-button :disabled="savingStep" @click="stepFormOpen = false">取消</el-button><el-button type="primary" native-type="submit" :loading="savingStep" :disabled="frozen || !permissions.can('runWorkflows')">保存步骤</el-button></div>
      </el-form>
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
import ExecutionTimeline from '@/components/workflow/ExecutionTimeline.vue'
import type { AgentPlan, AgentPlanStep, AgentTeam, AgentTeamMember, StepStatus, WorkflowExecution } from '@/types/domain'

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
const teams = ref<AgentTeam[]>([])
const members = ref<AgentTeamMember[]>([])
const plans = ref<AgentPlan[]>([])
const planSteps = ref<AgentPlanStep[]>([])
const selectedTeamId = ref<number>()
const selectedPlanId = ref<number>()

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
  if (!teamId) return
  const [nextMembers, nextPlans] = await Promise.all([
    EapApi.listTeamMembers(teamId),
    EapApi.listTeamPlans(teamId),
  ])
  if (disposed || requestId !== teamRequestId || teamId !== selectedTeamId.value) return
  members.value = nextMembers
  plans.value = nextPlans
  selectedPlanId.value = nextPlans[0]?.id
  if (selectedPlanId.value) {
    await selectPlan(selectedPlanId.value)
  }
}

async function selectPlan(planId: number) {
  const requestId = ++planRequestId
  const teamId = selectedTeamId.value
  if (planId !== selectedPlanId.value) selectedExecution.value = undefined
  selectedPlanId.value = planId
  planSteps.value = []
  const detail = await EapApi.getPlan(planId)
  if (disposed || requestId !== planRequestId || teamId !== selectedTeamId.value || planId !== selectedPlanId.value) return
  planSteps.value = detail.steps
  if (detail.plan) plans.value = plans.value.map(p => p.id === planId ? detail.plan : p)
  selectedExecution.value = undefined
  if (detail.plan?.executionRunKey) {
    const result = await EapApi.getPlanExecution(planId)
    if (!disposed && requestId === planRequestId && teamId === selectedTeamId.value && planId === selectedPlanId.value) selectedExecution.value = result?.execution
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
  const planId = formPlanId
  savingStep.value = true
  stepFormError.value = ''
  try {
    await EapApi.addPlanStep(planId, { stepOrder, title, requiredCapability, executionBackend: stepDraft.value.executionBackend })
    stepFormOpen.value = false
    if (!disposed && planId === selectedPlanId.value) await selectPlan(planId)
  } catch {
    stepFormError.value = '保存未确认，请刷新核对步骤顺序及计划是否已冻结后再操作'
  } finally { savingStep.value = false }
}
watch([selectedTeamId, selectedPlanId, () => permissions.state.user], () => {
  planFormOpen.value = false
  stepFormOpen.value = false
}, { flush: 'sync' })

async function assignMember(stepId: number, assignedAgentId: number) {
  if (frozen.value || !permissions.can('runWorkflows')) return
  const planId = selectedPlanId.value
  await EapApi.assignPlanStep(stepId, { assignedAgentId, status: 'ASSIGNED' })
  if (planId && selectedPlanId.value === planId) await selectPlan(planId)
}

async function executePlan() {
  const planId = selectedPlanId.value
  if (!planId || submitting.value || frozen.value || !permissions.can('runWorkflows')) return
  submitting.value = true
  executionError.value = ''
  try {
    await EapApi.executePlan(planId, { requestKey: `plan-${planId}`, suspendAfterTask: suspendAfterTask.value })
    if (selectedPlanId.value === planId) await selectPlan(planId)
    ElMessage.success('计划已提交，请查看真实执行进度')
  } catch {
    if (selectedPlanId.value === planId) executionError.value = '提交未确认，请刷新核对；再次提交使用同一请求标识，不会重复执行。'
  } finally { submitting.value = false }
}

function stepType(status: StepStatus) {
  return status === 'DONE' ? 'success' : status === 'RUNNING' ? 'warning' : status === 'BLOCKED' ? 'danger' : 'info'
}

onMounted(() => {
  void load()
  poll = setInterval(() => {
    const plan = plans.value.find(p => p.id === selectedPlanId.value)
    if (!disposed && !polling && plan && ['QUEUED', 'RUNNING'].includes(plan.status)) {
      polling = true
      void selectPlan(plan.id).catch(() => { if (!disposed && selectedPlanId.value === plan.id) executionError.value = '进度刷新失败，请重试' }).finally(() => { polling = false })
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
