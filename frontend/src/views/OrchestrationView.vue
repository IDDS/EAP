<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>多智能体团队编排</strong>
        <span>参考 AgentMesh 的团队协作与 AgentScope 的事件、权限、工作区模型</span>
      </div>
      <el-select v-model="selectedTeamId" class="agent-select" @change="loadTeamDetail">
        <el-option v-for="team in teams" :key="team.id" :label="`${team.name} · ${team.code}`" :value="team.id" />
      </el-select>
    </div>

    <div class="security-flow">
      <div class="flow-step">
        <strong>Team</strong>
        <span>按目标组建 Lead / Worker / Reviewer 等角色。</span>
      </div>
      <div class="flow-step">
        <strong>Plan</strong>
        <span>将任务拆成可分配步骤，每步声明 required capability。</span>
      </div>
      <div class="flow-step">
        <strong>Workspace</strong>
        <span>每个团队绑定工作区策略，步骤执行前进入安全网关。</span>
      </div>
    </div>

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

    <div class="registry-layout">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>任务计划</span>
            <el-button text :icon="Plus" @click="createDemoPlan">新增计划</el-button>
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
            <el-button text :icon="Plus" :disabled="!selectedPlanId" @click="addDemoStep">新增步骤</el-button>
          </div>
        </template>
        <el-table :data="planSteps">
          <el-table-column prop="stepOrder" label="#" width="60" />
          <el-table-column prop="title" label="步骤" min-width="180" />
          <el-table-column prop="requiredCapability" label="所需能力" min-width="170" />
          <el-table-column prop="assignedAgentId" label="Agent" width="90" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="stepType(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button text @click="markRunning(row.id, row.assignedAgentId)">运行</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { AgentPlan, AgentPlanStep, AgentTeam, AgentTeamMember, StepStatus } from '@/types/domain'

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
  if (!teamId) return
  const [nextMembers, nextPlans] = await Promise.all([
    EapApi.listTeamMembers(teamId),
    EapApi.listTeamPlans(teamId),
  ])
  if (requestId !== teamRequestId || teamId !== selectedTeamId.value) return
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
  selectedPlanId.value = planId
  planSteps.value = []
  const detail = await EapApi.getPlan(planId)
  if (requestId !== planRequestId || teamId !== selectedTeamId.value || planId !== selectedPlanId.value) return
  planSteps.value = detail.steps
}

async function createDemoPlan() {
  if (!selectedTeamId.value) return
  const teamId = selectedTeamId.value
  await EapApi.createPlan({
    teamId,
    goal: '生成跨部门影响分析',
    contextSummary: '将运行告警、财务影响和治理策略合并为可审计计划。',
  })
  ElMessage.success('计划已创建')
  if (teamId === selectedTeamId.value) await loadTeamDetail()
}

async function addDemoStep() {
  if (!selectedPlanId.value) return
  await EapApi.addPlanStep(selectedPlanId.value, {
    stepOrder: planSteps.value.length + 1,
    title: '发现候选 Agent 并执行安全评估',
    requiredCapability: 'finance.analysis',
  })
  await selectPlan(selectedPlanId.value)
}

async function markRunning(stepId: number, assignedAgentId?: number) {
  await EapApi.assignPlanStep(stepId, { assignedAgentId, status: 'RUNNING' })
  ElMessage.success('步骤已进入运行态')
  if (selectedPlanId.value) await selectPlan(selectedPlanId.value)
}

function stepType(status: StepStatus) {
  return status === 'DONE' ? 'success' : status === 'RUNNING' ? 'warning' : status === 'BLOCKED' ? 'danger' : 'info'
}

onMounted(load)
</script>

