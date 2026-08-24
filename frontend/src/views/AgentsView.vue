<template>
  <section class="page-grid agentops-catalog-page">
    <header class="agentops-page-header">
      <div>
        <span class="interface-eyebrow">EAP · FORMAL AGENT DIRECTORY</span>
        <h2>Agent 目录</h2>
        <p>正式 Agent 是唯一主实体；发现记录、端点、运行实例和遥测均作为身份或运行证据关联展示。</p>
      </div>
      <div class="button-row">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">登记 Agent</el-button>
      </div>
    </header>

    <el-alert v-if="dashboard.demoDataIncluded" type="warning" :closable="false" show-icon title="开发演示记录已用“演示”证据标记；生产 JWT 模式不会把它们混入正式 Agent 目录。" />

    <div class="agentops-catalog-summary">
      <article><span>当前范围 Agent</span><strong>{{ dashboard.agentCount }}</strong><small>{{ context.scopeLabel }}</small></article>
      <article><span>运行健康</span><strong class="is-success">{{ dashboard.healthyAgentCount }}</strong><small>窗口内存在有效心跳</small></article>
      <article><span>高风险关注</span><strong class="is-danger">{{ dashboard.highRiskAgentCount }}</strong><small>五轴风险态势结果</small></article>
      <article><span>观测缺失</span><strong class="is-warning">{{ dashboard.missingObservationCount }}</strong><small>缺失不按零值处理</small></article>
      <article><span>待处理事项</span><strong>{{ dashboard.workItems.totalOpen }}</strong><small>统一 Work Item 队列</small></article>
    </div>

    <el-card shadow="never" class="panel agentops-catalog-panel">
      <div class="agentops-catalog-toolbar">
        <el-input v-model="filters.query" clearable :prefix-icon="Search" placeholder="搜索 Agent、编码、部门、责任人或类别" />
        <el-select v-model="filters.lifecycleStatus" clearable placeholder="生命周期">
          <el-option label="草稿" value="DRAFT" /><el-option label="审核中" value="REVIEWING" /><el-option label="已启用" value="ACTIVE" /><el-option label="已暂停" value="PAUSED" /><el-option label="已下线" value="RETIRED" />
        </el-select>
        <el-select v-model="filters.health" clearable placeholder="运行健康">
          <el-option label="健康" value="HEALTHY" /><el-option label="降级" value="DEGRADED" /><el-option label="离线" value="OFFLINE" /><el-option label="未接入" value="NOT_CONNECTED" />
        </el-select>
        <el-select v-model="filters.coverage" clearable placeholder="观测覆盖">
          <el-option label="完整" value="FULL" /><el-option label="部分" value="PARTIAL" /><el-option label="缺失" value="MISSING" />
        </el-select>
        <el-select v-model="filters.risk" clearable placeholder="风险态势">
          <el-option label="正常" value="NORMAL" /><el-option label="关注" value="ATTENTION" /><el-option label="高风险" value="HIGH" /><el-option label="关键风险" value="CRITICAL" />
        </el-select>
        <el-button text @click="resetFilters">重置</el-button>
      </div>

      <el-table v-loading="loading" :data="page.items" class="agentops-agent-table" size="small" row-key="id" @row-click="openAgentRow">
        <el-table-column label="Agent" min-width="230" fixed="left">
          <template #default="{ row }"><div class="agentops-agent-identity"><span>{{ initials(row.name) }}</span><div><strong>{{ row.name }}</strong><small>{{ row.code }} · {{ row.category || '未分类' }}</small></div></div></template>
        </el-table-column>
        <el-table-column label="责任归属" min-width="155"><template #default="{ row }"><div class="agentops-owner-cell"><strong>{{ row.ownerDepartment || '待分配部门' }}</strong><span>{{ row.ownerUser || '待认领' }}</span></div></template></el-table-column>
        <el-table-column label="生命周期" width="112"><template #default="{ row }"><StatusAxis axis="lifecycle" :value="row.lifecycleStatus" /></template></el-table-column>
        <el-table-column label="运行健康" width="108"><template #default="{ row }"><StatusAxis axis="health" :value="row.operationalHealth" /></template></el-table-column>
        <el-table-column label="纳管状态" width="112"><template #default="{ row }"><StatusAxis axis="onboarding" :value="row.onboardingState" /></template></el-table-column>
        <el-table-column label="观测覆盖" width="108"><template #default="{ row }"><StatusAxis axis="coverage" :value="row.observationCoverage" /></template></el-table-column>
        <el-table-column label="风险态势" width="112"><template #default="{ row }"><StatusAxis axis="risk" :value="row.riskPosture" /></template></el-table-column>
        <el-table-column label="运营事项" width="92" align="center"><template #default="{ row }"><b :class="{ 'is-danger': row.openWorkItemCount > 0 }">{{ row.openWorkItemCount }}</b></template></el-table-column>
        <el-table-column label="证据 / 新鲜度" min-width="175"><template #default="{ row }"><div class="agentops-evidence-cell"><EvidenceBadge :basis="row.statusBasis" /><StatusAxis axis="freshness" :value="row.dataFreshness" /><small>{{ formatDate(row.latestObservedAt) }}</small></div></template></el-table-column>
        <el-table-column label="操作" width="92" fixed="right"><template #default="{ row }"><el-button link type="primary" @click.stop="openAgent(row.id)">详情</el-button></template></el-table-column>
      </el-table>

      <div class="agentops-pagination">
        <span>共 {{ page.totalElements }} 个 Agent</span>
        <el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" :total="page.totalElements" :page-sizes="[20, 50, 100]" layout="sizes, prev, pager, next" />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="登记正式 Agent" width="560px">
      <el-alert type="info" :closable="false" title="此操作创建正式目录身份；发现资产应在“发现与接入”中复核后纳管。" />
      <el-form label-position="top" style="margin-top: 16px">
        <el-form-item label="唯一编码"><el-input v-model="form.code" placeholder="例如 FIN-ANALYST" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="业务说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
        <div class="form-row"><el-form-item label="责任部门"><el-input v-model="form.ownerDepartment" /></el-form-item><el-form-item label="责任人"><el-input v-model="form.ownerUser" /></el-form-item></div>
        <div class="form-row"><el-form-item label="业务类别"><el-input v-model="form.category" /></el-form-item><el-form-item label="声明风险"><el-select v-model="form.riskLevel"><el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option label="高" value="HIGH" /><el-option label="关键" value="CRITICAL" /></el-select></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="creating" @click="createAgent">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import EvidenceBadge from '@/components/EvidenceBadge.vue'
import StatusAxis from '@/components/StatusAxis.vue'
import { EapApi } from '@/api/eap'
import { useAgentOpsContextStore } from '@/stores/agentOps'
import type { AgentLifecycleStatus, AgentOpsAgentState, AgentOpsDashboard, AgentRequest, ObservationCoverage, OperationalHealth, PagedAgentOpsResponse, RiskPosture } from '@/types/domain'

const router = useRouter()
const context = useAgentOpsContextStore()
const loading = ref(false)
const creating = ref(false)
const dialogVisible = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const filters = reactive<{
  query: string
  lifecycleStatus?: AgentLifecycleStatus
  health?: OperationalHealth
  coverage?: ObservationCoverage
  risk?: RiskPosture
}>({ query: '' })
const page = ref<PagedAgentOpsResponse>({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
const dashboard = ref<AgentOpsDashboard>({
  windowHours: 24, windowStart: '', windowEnd: '', timeZone: 'Asia/Shanghai', organizationScope: '企业全局',
  agentCount: 0, healthyAgentCount: 0, degradedAgentCount: 0, offlineAgentCount: 0,
  missingObservationCount: 0, highRiskAgentCount: 0,
  workItems: { totalOpen: 0, p0: 0, p1: 0, p2: 0, p3: 0, overdue: 0 }, metrics: [], priorityWorkItems: [], attentionAgents: [], demoDataIncluded: false, generatedAt: '',
})
const form = reactive<AgentRequest>({ code: '', name: '', description: '', ownerDepartment: '', ownerUser: '', category: '', riskLevel: 'LOW' })
let searchTimer: ReturnType<typeof setTimeout> | undefined

async function load() {
  loading.value = true
  try {
    const [nextPage, nextDashboard] = await Promise.all([
      EapApi.listAgentOpsAgents({
        hours: context.windowHours, page: currentPage.value - 1, size: pageSize.value,
        query: filters.query.trim() || undefined, department: context.organizationScope || undefined,
        lifecycleStatus: filters.lifecycleStatus, health: filters.health, coverage: filters.coverage, risk: filters.risk,
      }),
      EapApi.getAgentOpsDashboard({ hours: context.windowHours, organizationScope: context.organizationScope || undefined }),
    ])
    page.value = nextPage
    dashboard.value = nextDashboard
  } finally { loading.value = false }
}
function resetFilters() { Object.assign(filters, { query: '', lifecycleStatus: undefined, health: undefined, coverage: undefined, risk: undefined }); currentPage.value = 1 }
function openAgent(id: number) { router.push(`/agents/${id}`) }
function openAgentRow(row: AgentOpsAgentState) { openAgent(row.id) }
function initials(name: string) { return Array.from(name || 'A').slice(0, 2).join('').toUpperCase() }
function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : '无观测' }
async function createAgent() {
  if (!form.code.trim() || !form.name.trim()) { ElMessage.warning('请填写编码和名称'); return }
  creating.value = true
  try {
    await EapApi.createAgent({ ...form })
    ElMessage.success('正式 Agent 已登记')
    dialogVisible.value = false
    Object.assign(form, { code: '', name: '', description: '', ownerDepartment: '', ownerUser: '', category: '', riskLevel: 'LOW' })
    await load()
  } finally { creating.value = false }
}

watch(() => filters.query, () => { if (searchTimer) clearTimeout(searchTimer); searchTimer = setTimeout(() => { currentPage.value = 1; void load() }, 280) })
watch(() => [filters.lifecycleStatus, filters.health, filters.coverage, filters.risk], () => { currentPage.value = 1; void load() })
watch([currentPage, pageSize], () => void load())
watch(() => [context.windowHours, context.organizationScope], () => { currentPage.value = 1; void load() })
onMounted(load)
onBeforeUnmount(() => { if (searchTimer) clearTimeout(searchTimer) })
</script>
