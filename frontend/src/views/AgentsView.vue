<template>
  <section class="page-grid agentops-catalog-page">
    <header class="toolbar agent-directory-heading">
      <div class="toolbar-title">
        <h2>智能体目录</h2>
        <span>查找智能体，查看责任归属、运行状态与观测证据。</span>
      </div>
      <div class="button-row">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!permissions.can('deployAgents')" @click="dialogVisible = true">创建智能体</el-button>
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
      <div class="agent-directory-list-heading">
        <h3>智能体列表 <span>{{ page.totalElements }}</span></h3>
        <span>概览统计为当前组织范围，列表可进一步筛选</span>
      </div>
      <div class="agent-directory-search">
        <div class="agent-directory-field">
          <label for="agent-directory-query">搜索智能体</label>
          <el-input id="agent-directory-query" v-model="filters.query" clearable :prefix-icon="Search" placeholder="名称、编码、部门、责任人或类别" />
        </div>
        <el-button text @click="resetFilters">重置筛选</el-button>
      </div>
      <details class="agent-directory-filters">
        <summary>状态筛选 <span>{{ filters.lifecycleStatus || filters.health || filters.coverage || filters.risk ? '已设置筛选条件' : '生命周期、健康、覆盖与风险' }}</span></summary>
        <div class="agentops-catalog-toolbar">
        <div class="agent-directory-field"><label for="agent-filter-lifecycle">生命周期</label>
        <el-select id="agent-filter-lifecycle" v-model="filters.lifecycleStatus" clearable placeholder="全部生命周期">
          <el-option label="草稿" value="DRAFT" /><el-option label="审核中" value="REVIEWING" /><el-option label="已启用" value="ACTIVE" /><el-option label="已暂停" value="PAUSED" /><el-option label="已下线" value="RETIRED" />
        </el-select></div>
        <div class="agent-directory-field"><label for="agent-filter-health">运行健康</label>
        <el-select id="agent-filter-health" v-model="filters.health" clearable placeholder="全部健康状态">
          <el-option label="健康" value="HEALTHY" /><el-option label="降级" value="DEGRADED" /><el-option label="离线" value="OFFLINE" /><el-option label="未接入" value="NOT_CONNECTED" />
        </el-select></div>
        <div class="agent-directory-field"><label for="agent-filter-coverage">观测覆盖</label>
        <el-select id="agent-filter-coverage" v-model="filters.coverage" clearable placeholder="全部覆盖状态">
          <el-option label="完整" value="FULL" /><el-option label="部分" value="PARTIAL" /><el-option label="缺失" value="MISSING" />
        </el-select></div>
        <div class="agent-directory-field"><label for="agent-filter-risk">风险态势</label>
        <el-select id="agent-filter-risk" v-model="filters.risk" clearable placeholder="全部风险状态">
          <el-option label="正常" value="NORMAL" /><el-option label="关注" value="ATTENTION" /><el-option label="高风险" value="HIGH" /><el-option label="关键风险" value="CRITICAL" />
        </el-select></div>
        </div>
      </details>

      <el-table v-loading="loading" :data="page.items" class="agentops-agent-table" size="small" row-key="id" @row-click="openAgentRow">
        <template #empty>{{ loading ? '正在加载智能体…' : '当前范围或筛选条件下没有智能体' }}</template>
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

      <div v-loading="loading" class="agent-directory-mobile" :aria-busy="loading">
        <ul aria-label="智能体列表">
          <li v-for="row in page.items" :key="row.id" class="agent-directory-mobile-row">
            <router-link :to="`/agents/${row.id}`" class="agent-directory-name">{{ row.name }}<span aria-hidden="true">查看详情 →</span></router-link>
            <p class="agent-directory-code">{{ row.code }} · {{ row.category || '未分类' }}</p>
            <p>{{ row.ownerDepartment || '待分配部门' }} · {{ row.ownerUser || '待认领' }}</p>
            <dl class="agent-directory-states">
              <div><dt>生命周期</dt><dd><StatusAxis axis="lifecycle" :value="row.lifecycleStatus" /></dd></div>
              <div><dt>运行健康</dt><dd><StatusAxis axis="health" :value="row.operationalHealth" /></dd></div>
              <div><dt>接入纳管</dt><dd><StatusAxis axis="onboarding" :value="row.onboardingState" /></dd></div>
              <div><dt>观测覆盖</dt><dd><StatusAxis axis="coverage" :value="row.observationCoverage" /></dd></div>
              <div><dt>风险态势</dt><dd><StatusAxis axis="risk" :value="row.riskPosture" /></dd></div>
              <div><dt>运营事项</dt><dd :class="{ 'is-danger': row.openWorkItemCount > 0 }">{{ row.openWorkItemCount }} 项</dd></div>
            </dl>
            <div class="agent-directory-evidence"><EvidenceBadge :basis="row.statusBasis" /><StatusAxis axis="freshness" :value="row.dataFreshness" /><small>{{ formatDate(row.latestObservedAt) }}</small></div>
          </li>
        </ul>
        <p v-if="!page.items.length" class="agent-directory-empty" role="status">{{ loading ? '正在加载智能体…' : '当前范围或筛选条件下没有智能体，可调整筛选或组织范围。' }}</p>
      </div>

      <div class="agentops-pagination">
        <span>共 {{ page.totalElements }} 个 Agent</span>
        <PageControls v-model:current-page="currentPage" v-model:page-size="pageSize" :total="page.totalElements" :page-sizes="[20, 50, 100]"  />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="创建智能体身份" width="min(560px, calc(100vw - 28px))">
      <el-alert type="info" :closable="false" title="先创建草稿身份，再配置部署、启用并验证运行。已有实例可在“发现与接入 → 运行环境发现”中关联。" />
      <el-form :disabled="!permissions.can('deployAgents')" label-position="top" style="margin-top: 16px">
        <el-form-item label="唯一编码"><el-input v-model="form.code" placeholder="例如 FIN-ANALYST" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="业务说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
        <div class="form-row"><el-form-item label="责任部门"><el-input v-model="form.ownerDepartment" /></el-form-item><el-form-item label="责任人"><el-input v-model="form.ownerUser" /></el-form-item></div>
        <div class="form-row"><el-form-item label="业务类别"><el-input v-model="form.category" /></el-form-item><el-form-item label="声明风险"><el-select v-model="form.riskLevel"><el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option label="高" value="HIGH" /><el-option label="关键" value="CRITICAL" /></el-select></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="creating" :disabled="!permissions.can('deployAgents')" @click="createAgent">创建并继续配置</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import PageControls from '@/components/common/PageControls.vue'
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import EvidenceBadge from '@/components/EvidenceBadge.vue'
import StatusAxis from '@/components/StatusAxis.vue'
import { EapApi } from '@/api/eap'
import { useAgentOpsContextStore } from '@/stores/agentOps'
import { usePermissions } from '@/stores/permissions'
import type { AgentLifecycleStatus, AgentOpsAgentState, AgentOpsDashboard, AgentRequest, ObservationCoverage, OperationalHealth, PagedAgentOpsResponse, RiskPosture } from '@/types/domain'

const permissions = usePermissions()
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
  lastSignalSyncAt: null, signalSyncFreshness: 'NO_DATA',
  windowHours: 24, windowStart: '', windowEnd: '', timeZone: 'Asia/Shanghai', organizationScope: '企业全局',
  agentCount: 0, healthyAgentCount: 0, degradedAgentCount: 0, offlineAgentCount: 0,
  missingObservationCount: 0, highRiskAgentCount: 0,
  workItems: { totalOpen: 0, p0: 0, p1: 0, p2: 0, p3: 0, overdue: 0 }, metrics: [], priorityWorkItems: [], attentionAgents: [], demoDataIncluded: false, generatedAt: '',
})
const form = reactive<AgentRequest>({ code: '', name: '', description: '', ownerDepartment: '', ownerUser: '', category: '', riskLevel: 'LOW' })
let searchTimer: ReturnType<typeof setTimeout> | undefined

let loadRequestId = 0

async function load() {
  const requestId = ++loadRequestId
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
    if (requestId !== loadRequestId) return
    page.value = nextPage
    dashboard.value = nextDashboard
  } finally { if (requestId === loadRequestId) loading.value = false }
}
function resetFilters() { Object.assign(filters, { query: '', lifecycleStatus: undefined, health: undefined, coverage: undefined, risk: undefined }); currentPage.value = 1 }
function openAgent(id: number) { router.push(`/agents/${id}`) }
function openAgentRow(row: AgentOpsAgentState) { openAgent(row.id) }
function initials(name: string) { return Array.from(name || 'A').slice(0, 2).join('').toUpperCase() }
function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }) : '无观测' }
async function createAgent() {
  if (!permissions.can('deployAgents')) return
  if (![form.code,form.name,form.ownerDepartment,form.ownerUser,form.category].every(value => value?.trim())) { ElMessage.warning('请填写编码、名称、责任部门、责任人和业务类别'); return }
  creating.value = true
  try {
    const created = await EapApi.createAgent({ ...form })
    ElMessage.success('正式 Agent 已登记')
    dialogVisible.value = false
    Object.assign(form, { code: '', name: '', description: '', ownerDepartment: '', ownerUser: '', category: '', riskLevel: 'LOW' })
    await router.push({path: '/agents/'+created.id,query:{tab:'deploy',setup:'1'}})
  } finally { creating.value = false }
}

watch(() => filters.query, () => { if (searchTimer) clearTimeout(searchTimer); searchTimer = setTimeout(() => { currentPage.value = 1; void load() }, 280) })
watch(() => [filters.lifecycleStatus, filters.health, filters.coverage, filters.risk], () => { currentPage.value = 1; void load() })
watch([currentPage, pageSize], () => void load())
watch(() => [context.windowHours, context.organizationScope], () => { currentPage.value = 1; void load() })
onMounted(load)
onBeforeUnmount(() => { if (searchTimer) clearTimeout(searchTimer) })
</script>
