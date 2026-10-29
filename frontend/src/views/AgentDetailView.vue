<template>
  <section class="page-grid agent-detail-page" v-loading="loading">
    <ConsolePageHeader :description="agent ? `当前智能体：${agent.name} · #${agent.id}` : '正在读取当前智能体…'">
      <template #actions>
      <el-button text :icon="ArrowLeft" @click="router.push('/agents')">返回智能体目录</el-button>
      <div class="agent-detail-actions"><el-button :icon="Refresh" :loading="loading" @click="load">刷新观测</el-button><el-button type="primary" :disabled="loading || !agent || !permissions.can('deployAgents')" @click="changeStatus(agent?.lifecycleStatus === 'ACTIVE' ? 'PAUSED' : 'ACTIVE')">{{ agent?.lifecycleStatus === 'ACTIVE' ? '暂停智能体' : '启用智能体' }}</el-button></div>
      </template>
    </ConsolePageHeader>

    <AgentOpsFilters v-if="activeTab === 'overview'" :organization="false" description="用于运营状态统计；调用摘要显示最近加载的记录。" />

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />

    <el-card v-if="agent && activeTab === 'overview'" shadow="never" class="panel agent-profile-hero">
      <div class="agent-profile-layout">
        <div class="agent-profile-primary">
          <div class="agent-profile-heading">
            <div class="agent-avatar agent-profile-avatar" :class="`agent-avatar-${agent.riskLevel.toLowerCase()}`">
              <span>{{ initials(agent.name) }}</span>
              <i :class="`risk-signal-${agent.riskLevel.toLowerCase()}`"></i>
            </div>
            <div class="agent-profile-copy">
              <div class="agent-profile-eyebrow">
                <span>智能体资产档案</span>
                <b v-if="primaryDiscovery"><i></i>自主发现·已纳管</b>
              </div>
              <div class="agent-profile-title">
                <h2>{{ agent.name }}</h2>
                <span class="agent-state-pill" :class="`agent-state-${agent.lifecycleStatus.toLowerCase()}`">{{ statusText(agent.lifecycleStatus) }}</span>
                <span class="agent-risk-pill" :class="`agent-risk-${agent.riskLevel.toLowerCase()}`">{{ riskText(agent.riskLevel) }}风险</span>
              </div>
              <p>{{ profileSummary }}</p>
            </div>
          </div>

          <details class="agent-profile-disclosure">
            <summary>身份与运行线索 <span>编码、厂商、主机与进程</span></summary>
          <div class="agent-profile-facts">
            <div>
              <span>资产编码</span>
              <strong class="mono">{{ agent.code }}</strong>
            </div>
            <div>
              <span>厂商 / 产品</span>
              <strong>{{ primaryDiscovery ? `${primaryDiscovery.vendor || '未知厂商'} · ${primaryDiscovery.product || agent.name}` : agent.name }}</strong>
            </div>
            <div>
              <span>运行主机</span>
              <strong>{{ primaryDiscovery?.hostName || '尚未关联' }}</strong>
              <small v-if="primaryDiscovery?.accountName">账户 {{ primaryDiscovery.accountName }}</small>
            </div>
            <div>
              <span>运行进程</span>
              <strong>{{ primaryDiscovery?.processName || '尚未发现' }}</strong>
              <small v-if="primaryDiscovery?.executablePath" :title="primaryDiscovery.executablePath">{{ primaryDiscovery.executablePath }}</small>
            </div>
          </div>
          </details>

          <div class="agent-profile-footer">
            <div class="agent-profile-badges">
              <span v-if="primaryDiscovery">发现来源 <b>{{ discoverySourceSummary }}</b></span>
              <span v-if="primaryDiscovery">置信度 <b>{{ primaryDiscovery.confidence }}%</b></span>
              <span>责任归属 <b>{{ ownerSummary }}</b></span>
            </div>
            <small>档案更新 {{ formatDate(agent.updatedAt) }}</small>
          </div>
        </div>

      <nav class="agent-record-links" aria-label="当前智能体的相关记录">
        <span>在工作区查看</span>
        <router-link v-for="item in relatedRecords" :key="item.label" :to="{ path: item.path, query: { section: item.section, agentId: String(agent.id) } }">{{ item.label }}</router-link>
      </nav>
      </div>
    </el-card>

    <p v-if="agent && activeTab !== 'overview'" class="agent-object-heading"><strong>{{ agent.name }}</strong><span>{{ agent.code }} · {{ statusText(agent.lifecycleStatus) }}</span></p>

    <el-card v-if="agentOpsState && activeTab === 'overview'" shadow="never" class="panel agentops-detail-axis-panel">
      <template #header><div class="panel-header"><span>运行状态</span><EvidenceBadge :basis="agentOpsState.statusBasis" :source="`最近 ${context.windowHours} 小时信号`" /></div></template>
      <div class="agentops-detail-axis-grid">
        <div><span>生命周期</span><StatusAxis axis="lifecycle" :value="agentOpsState.lifecycleStatus" /></div>
        <div><span>运行健康</span><StatusAxis axis="health" :value="agentOpsState.operationalHealth" /></div>
        <div><span>接入纳管</span><StatusAxis axis="onboarding" :value="agentOpsState.onboardingState" /></div>
        <div><span>观测覆盖</span><StatusAxis axis="coverage" :value="agentOpsState.observationCoverage" /><small>{{ agentOpsState.coverageScore }} 类信号</small></div>
        <div><span>风险态势</span><StatusAxis axis="risk" :value="agentOpsState.riskPosture" /></div>
        <div><span>数据新鲜度</span><StatusAxis axis="freshness" :value="agentOpsState.dataFreshness" /><small>{{ formatDate(agentOpsState.latestObservedAt) }}</small></div>
      </div>
    </el-card>

    <el-alert v-if="!agent" type="warning" :closable="false" title="未找到该智能体，请返回目录重新选择。" />

    <details v-if="agent && ['overview', 'deploy'].includes(activeTab)" class="agent-setup-guide" :open="route.query.setup === '1'">
      <summary>创建与接入进度 <span>登记、部署与试运行状态</span></summary>
      <div><strong>创建与接入进度</strong><p>登记身份 → 配置部署或关联实例 → 启用 → 试运行与观测验证</p></div>
      <div class="setup-guide-actions"><el-tag>身份已登记</el-tag><el-tag :type="agent.lifecycleStatus === 'ACTIVE' ? 'success' : 'info'">{{ agent.lifecycleStatus === 'ACTIVE' ? '已启用' : '未启用' }}</el-tag><el-tag :type="runtimes.length ? 'success' : 'warning'">{{ runtimes.length ? '已有运行登记' : '待接入运行实例' }}</el-tag><el-tag :type="modelEvents.length && spans.length ? 'success' : 'info'">{{ modelEvents.length && spans.length ? '当前窗口有模型与调用链观测' : '执行与观测待验证' }}</el-tag></div>
      <div class="setup-guide-actions"><el-button link type="primary" @click="activeTab='deploy'">配置部署与试运行</el-button><el-button link type="primary" @click="activeTab='runtime'">查看运行接入</el-button><router-link to="/registry?section=environment">关联已有实例</router-link></div>
    </details>

    <template v-if="agent">

      <div v-show="activeTab === 'overview'" class="agent-detail-content">
        <el-alert v-if="historyKinds.some(kind => history[kind].error)" title="部分调用摘要加载失败，请刷新或进入相关记录查看。" type="warning" :closable="false" />
        <div class="metric-strip agent-detail-metrics" aria-label="最近加载的调用摘要"><div class="small-box box-primary"><div><strong>{{ formatNumber(totalTokens) }}</strong><span>近期 Token</span></div><el-icon><Tickets /></el-icon></div><div class="small-box box-success"><div><strong>{{ modelEvents.length }}</strong><span>近期模型调用</span></div><el-icon><Connection /></el-icon></div><div class="small-box box-info"><div><strong>{{ tools.length }}</strong><span>近期工具调用</span></div><el-icon><Tools /></el-icon></div><div class="small-box box-warning"><div><strong>{{ formatMoney(totalCost) }}</strong><span>近期费用 USD</span></div><el-icon><Money /></el-icon></div></div>
        <p class="agent-summary-note">摘要基于最近加载的最多 50 条记录；完整记录与分页请从相关工作区查看。</p>
        <div class="agent-detail-grid agent-detail-grid-main"><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>智能体摘要</span><el-tag size="small" effect="plain">实时数据</el-tag></div></template><div class="attribute-grid"><div><span>业务类别</span><strong>{{ agent.category || '未分类' }}</strong></div><div><span>运行实例</span><strong>{{ runtimes.length }} 个</strong></div><div><span>能力标签</span><strong>{{ capabilities.length }} 项</strong></div><div><span>本页 Trace</span><strong class="mono">{{ latestTraceId || '暂无' }}</strong></div><div><span>成功率</span><strong>{{ modelEvents.length ? `${successRate}%` : '暂无数据' }}</strong></div><div><span>平均延迟</span><strong>{{ modelEvents.length ? `${averageLatency} ms` : '暂无数据' }}</strong></div></div></el-card><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>运行健康</span><span class="muted">当前实例状态</span></div></template><div class="runtime-health-list"><div v-for="runtime in runtimes" :key="runtime.id"><span class="runtime-status-dot" :class="runtime.status.toLowerCase()"></span><div><strong>{{ runtime.instanceKey }}</strong><small>{{ runtime.endpoint }} · {{ runtime.protocol }}</small></div><el-tag size="small" :type="runtime.status === 'ONLINE' ? 'success' : runtime.status === 'DEGRADED' ? 'warning' : 'info'">{{ runtimeStatusLabel(runtime.status) }}</el-tag></div><el-empty v-if="!runtimes.length" description="尚未登记运行实例" :image-size="60" /></div></el-card></div>
        <el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>关联运营事项</span><span class="muted">{{ workItems.length }} 项待处理</span></div></template><WorkItemTable :items="workItems" @inspect="inspectWorkItem" @transition="transitionWorkItem" @open-agent="openAgent" /></el-card>
        <el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>本页活动</span><el-button link type="primary" @click="router.push({ path: '/physical', query: { section: 'trace', agentId: String(agentId) } })">查看完整 Trace</el-button></div></template><el-table :data="recentActivity" size="small"><el-table-column prop="time" label="时间" width="170" /><el-table-column prop="kind" label="类型" width="110" /><el-table-column prop="title" label="活动" min-width="240" /><el-table-column prop="detail" label="详情" min-width="260" show-overflow-tooltip /><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.tone">{{ row.status }}</el-tag></template></el-table-column></el-table><el-empty v-if="!recentActivity.length" description="暂无活动记录" :image-size="70" /></el-card>
      </div>

      <div v-show="activeTab === 'runtime'" class="agent-detail-content"><RuntimeInventoryPanel v-if="activeTab === 'runtime' && runtimes.some(r => ['SUBSTRATE_OBSERVED','SUBSTRATE'].includes(r.protocol))" :agent-id="agent.id" /><el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>运行实例</span><span class="muted">心跳、端点与负载</span></div></template><el-table :data="runtimes"><el-table-column prop="instanceKey" label="实例" min-width="180" /><el-table-column prop="endpoint" label="端点" min-width="250" /><el-table-column prop="protocol" label="协议" width="110" /><el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'ONLINE' ? 'success' : row.status === 'DEGRADED' ? 'warning' : 'info'">{{ runtimeStatusLabel(row.status) }}</el-tag></template></el-table-column><el-table-column label="负载" width="180"><template #default="{ row }"><el-progress :percentage="Math.round(row.loadScore * 100)" :stroke-width="7" :show-text="false" /><small>{{ Math.round(row.loadScore * 100) }}%</small></template></el-table-column><el-table-column label="最后心跳" width="190"><template #default="{ row }">{{ formatDate(row.lastHeartbeatAt) }}</template></el-table-column></el-table><el-empty v-if="!runtimes.length" description="尚未登记运行实例" /></el-card><el-card shadow="never" class="panel"><template #header><span>能力与技能</span></template><div class="capability-list"><el-tag v-for="capability in capabilities" :key="capability.id" effect="plain" size="large">{{ capability.name }}<small>{{ capability.capability }}</small></el-tag><el-empty v-if="!capabilities.length" description="尚未登记能力" :image-size="70" /></div></el-card></div>

      <div v-if="activeTab === 'deploy' && agent" class="agent-detail-content"><SubstrateDeployPanel :agent-id="agent.id" /></div>

      <div v-show="activeTab === 'identity'" class="agent-detail-content"><el-card shadow="never" class="panel"><template #header><span>身份与管理属性</span></template><div class="agent-identity-grid"><div><span>智能体 ID</span><strong class="mono">{{ agent.id }}</strong></div><div><span>唯一编码</span><strong class="mono">{{ agent.code }}</strong></div><div><span>责任部门</span><strong>{{ agent.ownerDepartment || '未分配' }}</strong></div><div><span>责任人</span><strong>{{ agent.ownerUser || '未分配' }}</strong></div><div><span>业务类别</span><strong>{{ agent.category || '未分类' }}</strong></div><div><span>创建时间</span><strong>{{ formatDate(agent.createdAt) }}</strong></div><div><span>更新时间</span><strong>{{ formatDate(agent.updatedAt) }}</strong></div><div><span>风险等级</span><strong>{{ riskText(agent.riskLevel) }}</strong></div></div></el-card><el-card v-if="discoveryAssets.length" shadow="never" class="panel"><template #header><div class="panel-header"><span>自主发现证据</span><el-tag type="success" effect="plain">已关联目录身份</el-tag></div></template><div v-for="asset in discoveryAssets" :key="asset.id" class="agent-discovery-evidence"><div class="agent-identity-grid"><div><span>厂商 / 产品</span><strong>{{ asset.vendor }} · {{ asset.product }}</strong></div><div><span>发现主机</span><strong>{{ asset.hostName || '未知' }}</strong></div><div><span>系统账户</span><strong>{{ asset.accountName || '未知' }}</strong></div><div><span>运行进程</span><strong>{{ asset.processName || '未发现' }}</strong></div><div><span>执行路径</span><strong :title="asset.executablePath">{{ asset.executablePath || '未知' }}</strong></div><div><span>发现来源</span><strong>{{ asset.sourceTypes.map(discoverySourceLabel).join(' / ') }}</strong></div><div><span>置信度</span><strong>{{ asset.confidence }}%</strong></div><div><span>证据数量</span><strong>{{ asset.evidenceCount }} 条</strong></div><div><span>最后发现</span><strong>{{ formatDate(asset.lastSeenAt) }}</strong></div></div></div></el-card><el-card shadow="never" class="panel"><template #header><span>关联任务</span></template><el-table :data="tasks"><el-table-column prop="taskType" label="任务类型" min-width="180" /><el-table-column prop="inputSummary" label="输入摘要" min-width="260" show-overflow-tooltip /><el-table-column prop="status" label="状态" width="110" /><el-table-column prop="tokenCost" label="Token" width="100" /><el-table-column prop="createdAt" label="创建时间" width="175"><template #default="{ row }">{{ formatDate(row.createdAt) }}</template></el-table-column></el-table><el-empty v-if="!tasks.length" description="暂无关联任务" /></el-card></div>
    </template>
  </section>
</template>

<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { usePageSection } from '@/composables/usePageSection'
import { legacyAgentDestination } from '@/navigation/catalog'
const relatedRecords = [
  { label: 'Trace 链路', path: '/physical', section: 'trace' },
  { label: '模型调用', path: '/physical', section: 'models' },
  { label: '工具与 MCP', path: '/physical', section: 'tools' },
  { label: '知识链路', path: '/knowledge', section: 'trace' },
  { label: '评估结果', path: '/evaluation', section: 'results' },
  { label: '治理事件', path: '/governance', section: 'audit' },
]

import AgentOpsFilters from '@/components/AgentOpsFilters.vue'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Collection, Connection, DataAnalysis, Document, Lock, Money, Refresh, Tickets, Tools, Upload } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Ref } from 'vue'
import EvidenceBadge from '@/components/EvidenceBadge.vue'
import StatusAxis from '@/components/StatusAxis.vue'
import WorkItemTable from '@/components/WorkItemTable.vue'
import RuntimeInventoryPanel from '@/components/RuntimeInventoryPanel.vue'
import SubstrateDeployPanel from '@/components/substrate/SubstrateDeployPanel.vue'
import { EapApi } from '@/api/eap'
import { useAgentOpsContextStore } from '@/stores/agentOps'
import { usePermissions } from '@/stores/permissions'
import type { Agent, AgentCapability, AgentOpsAgentState, AgentOpsWorkItem, AgentRuntimeInstance, AgentTask, ContentDirection, ContentObservation, DiscoveredAgentAsset, EvaluationResult, GovernanceDataAction, GovernanceDataEvent, KnowledgeInteraction, ModelInvocationEvent, ModelInvocationStatus, RiskLevel, RuntimeStatus, ToolInvocationEvent, TraceSpanKind, TraceSpanObservation, WorkItemStatus } from '@/types/domain'

const permissions = usePermissions()
const route = useRoute(); const router = useRouter()
const context = useAgentOpsContextStore()
const loading = ref(false); const agent = ref<Agent>(); const activeTab = usePageSection()
const capabilities = ref<AgentCapability[]>([]); const runtimes = ref<AgentRuntimeInstance[]>([]); const modelEvents = ref<ModelInvocationEvent[]>([]); const spans = ref<TraceSpanObservation[]>([]); const tools = ref<ToolInvocationEvent[]>([]); const tasks = ref<AgentTask[]>([])
const loadError = ref('')
const discoveryAssets = ref<DiscoveredAgentAsset[]>([])
const agentOpsState = ref<AgentOpsAgentState>()
const workItems = ref<AgentOpsWorkItem[]>([])
const agentId = computed(() => Number(route.params.id))
const primaryDiscovery = computed(() => discoveryAssets.value[0])
const profileSummary = computed(() => primaryDiscovery.value ? '由 EAP 自主发现并纳入企业智能体目录，可统一查看运行、调用链路与治理风险。' : agent.value?.description || '尚未填写业务说明')
const discoverySourceSummary = computed(() => primaryDiscovery.value?.sourceTypes.map(discoverySourceLabel).join(' / ') || '未知')
const ownerSummary = computed(() => [agent.value?.ownerDepartment, agent.value?.ownerUser].filter(Boolean).join(' · ') || '待认领')
const totalTokens = computed(() => modelEvents.value.reduce((sum, item) => sum + (item.totalTokens || 0), 0)); const totalCost = computed(() => modelEvents.value.reduce((sum, item) => sum + (item.moneyCost || 0), 0)); const successRate = computed(() => modelEvents.value.length ? Math.round(modelEvents.value.filter(item => item.status === 'SUCCEEDED').length / modelEvents.value.length * 100) : 0); const averageLatency = computed(() => modelEvents.value.length ? Math.round(modelEvents.value.reduce((sum, item) => sum + item.latencyMs, 0) / modelEvents.value.length) : 0); const latestTraceId = computed(() => modelEvents.value.find(item => item.traceId)?.traceId || spans.value[0]?.traceId || ''); const healthLabel = computed(() => runtimes.value.some(item => item.status === 'ONLINE') ? '健康' : runtimes.value.some(item => item.status === 'DEGRADED') ? '降级' : runtimes.value.length ? '离线' : '未接入'); const healthTone = computed(() => healthLabel.value === '健康' ? 'success' : healthLabel.value === '降级' ? 'warning' : healthLabel.value === '未接入' ? 'neutral' : 'danger'); const healthHint = computed(() => healthLabel.value === '健康' ? '运行实例心跳正常，观测链路可用' : healthLabel.value === '降级' ? '部分实例存在异常，建议及时检查' : healthLabel.value === '离线' ? '已登记实例，但当前未收到心跳' : '尚未发现运行实例心跳'); const lastObservedLabel = computed(() => modelEvents.value[0] ? `本页首条模型观测 ${formatDate(modelEvents.value[0].observedAt)}` : '暂无模型调用观测')
const recentActivity = computed(() => [...modelEvents.value.slice(0, 6).map(item => ({ time: formatDate(item.observedAt), kind: '模型调用', title: `${item.provider} · ${item.modelName}`, detail: `${formatNumber(item.totalTokens)} Token · ${item.latencyMs} ms`, status: modelStatusLabel(item.status), tone: item.status === 'SUCCEEDED' ? 'success' : 'danger' })), ...tools.value.slice(0, 4).map(item => ({ time: formatDate(item.observedAt), kind: item.toolKind === 'MCP' ? 'MCP' : '工具', title: item.toolName, detail: `${item.durationMs} ms · ${item.traceId || '无 Trace'}`, status: toolStatusLabel(item.status), tone: item.status === 'SUCCEEDED' ? 'success' : 'warning' }))].slice(0, 8))

type HistoryKind = 'models' | 'spans' | 'tools'
type HistoryRow = ModelInvocationEvent | TraceSpanObservation | ToolInvocationEvent
const historyKinds: HistoryKind[] = ['models', 'spans', 'tools']
const historyRows: Record<HistoryKind, Ref<HistoryRow[]>> = {
  models: modelEvents, spans, tools,
}
const history = reactive(Object.fromEntries(historyKinds.map(kind => [kind, {
  page: 0, requestedPage: 0, loading: false, hasNext: false, error: '', requestId: 0,
}])) as Record<HistoryKind, { page: number; requestedPage: number; loading: boolean; hasNext: boolean; error: string; requestId: number }>)
const historyPageSize = 50

function resetHistory() {
  for (const kind of historyKinds) {
    const state = history[kind]
    state.requestId++
    state.page = 0
    state.requestedPage = 0
    state.loading = false
    state.hasNext = false
    state.error = ''
    historyRows[kind].value = []
  }
}

async function fetchHistory(kind: HistoryKind, id: number, page: number): Promise<HistoryRow[]> {
  const params = { agentId: id, limit: historyPageSize, page }
  switch (kind) {
    case 'models': return EapApi.listModelInvocationEvents(params)
    case 'spans': return EapApi.listTraceSpanObservations(params)
    case 'tools': return EapApi.listToolInvocationEvents(params)
  }
}

async function loadHistory(kind: HistoryKind, page = 0) {
  if (page < 0) return
  if (!permissions.can('viewConsole')) {
    history[kind].requestId++; history[kind].loading = false; history[kind].hasNext = false
    historyRows[kind].value = []; return
  }
  const user = permissions.state.user
  const id = agentId.value
  const state = history[kind]
  const requestId = ++state.requestId
  state.requestedPage = page
  state.loading = true
  state.error = ''
  try {
    const rows = await fetchHistory(kind, id, page)
    if (requestId !== state.requestId || id !== agentId.value || user !== permissions.state.user || !permissions.can('viewConsole')) return
    // An empty next page establishes the end without replacing the last nonempty page.
    if (page > state.page && rows.length === 0) {
      state.hasNext = false
      return
    }
    historyRows[kind].value = rows
    state.page = page
    // List APIs preserve their array contract; a next-page probe also works with server-side size caps.
    state.hasNext = rows.length > 0
  } catch {
    if (requestId !== state.requestId || id !== agentId.value || user !== permissions.state.user || !permissions.can('viewConsole')) return
    state.error = '摘要加载失败，请刷新重试。'
  } finally {
    if (requestId === state.requestId) state.loading = false
  }
}

let loadRequestId = 0
async function load() {
  const requestId = ++loadRequestId
  const id = agentId.value
  loading.value = true
  loadError.value = ''
  agent.value = undefined
  capabilities.value = []; runtimes.value = []; tasks.value = []
  discoveryAssets.value = []; agentOpsState.value = undefined; workItems.value = []
  resetHistory()
  if (!permissions.can('viewConsole') || !Number.isSafeInteger(id) || id <= 0) { loading.value = false; return }
  try {
    const [agents, nextCapabilities, nextRuntimes, nextTasks, nextDiscoveryAssets, nextAgentOpsState, nextWorkItems] = await Promise.all([
      EapApi.listAgents(),
      EapApi.listAgentCapabilities(id),
      EapApi.listAgentRuntimeInstances(id),
      EapApi.listTasks(),
      EapApi.listManagedAgentDiscoveryAssets(id),
      EapApi.getAgentOpsAgent(id, context.windowHours),
      EapApi.listAgentOpsWorkItems({ agentId: id, limit: 100 }),
      ...historyKinds.map(kind => loadHistory(kind)),
    ])
    if (requestId !== loadRequestId || id !== agentId.value) return
    agent.value = agents.find(item => item.id === id)
    capabilities.value = nextCapabilities
    runtimes.value = nextRuntimes
    tasks.value = nextTasks.filter(item => item.agentId === id)
    discoveryAssets.value = nextDiscoveryAssets
    agentOpsState.value = nextAgentOpsState
    workItems.value = nextWorkItems.filter(item => !['RESOLVED', 'CLOSED'].includes(item.status))
  } catch {
    if (requestId === loadRequestId) loadError.value = '智能体详情加载失败，请刷新重试。'
  } finally {
    if (requestId === loadRequestId) loading.value = false
  }
}
async function changeStatus(status: 'ACTIVE' | 'PAUSED') { if (!permissions.can('deployAgents') || !agent.value) return; await EapApi.updateAgentStatus(agent.value.id, status); ElMessage.success(status === 'ACTIVE' ? '智能体已启用' : '智能体已暂停'); await load() }
async function transitionWorkItem(item: AgentOpsWorkItem, status: WorkItemStatus) {
  let assignee = item.assignee
  let reason: string | undefined
  try {
    if (status === 'CLAIMED') {
      const result = await ElMessageBox.prompt('填写本次事项负责人', '认领运营事项', { inputValue: item.assignee || '当前操作员', confirmButtonText: '认领', cancelButtonText: '取消' })
      assignee = result.value
    } else if (status === 'RESOLVED') {
      const result = await ElMessageBox.prompt('说明处置结果和验证方式', '解决运营事项', { inputType: 'textarea', confirmButtonText: '提交', cancelButtonText: '取消' })
      reason = result.value
    }
  } catch {
    return
  }
  await EapApi.transitionAgentOpsWorkItem(item.id, { status, assignee, reason })
  ElMessage.success('运营事项状态已更新')
  await load()
}
function inspectWorkItem(item: AgentOpsWorkItem) { let evidence = item.evidenceJson || '无结构化证据'; try { evidence = JSON.stringify(JSON.parse(evidence), null, 2) } catch { /* 保留原始证据 */ } void ElMessageBox.alert(`${item.description}\n\n${evidence}`, item.title, { confirmButtonText: '关闭' }) }
function openAgent(id: number) { if (id !== agentId.value) router.push(`/agents/${id}`) }
function initials(name: string) { return Array.from(name || 'A').slice(0, 2).join('').toUpperCase() }; function formatNumber(value: number) { return new Intl.NumberFormat('zh-CN').format(value || 0) }; function formatMoney(value: number) { return `$${(value || 0).toFixed(4)}` }; function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—' }; function formatTime(value?: string) { return value ? new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '—' }
function statusText(status: string) { return ({ DRAFT: '草稿', REVIEWING: '审核中', ACTIVE: '运行中', PAUSED: '已暂停', RETIRED: '已下线', SUCCEEDED: '成功', FAILED: '失败', STARTED: '进行中', CANCELLED: '已取消', RUNNING: '运行中', REQUESTED: '已请求', UNKNOWN: '未知' } as Record<string, string>)[status] || status }; function modelStatusLabel(status: ModelInvocationStatus) { return statusText(status) }; function toolStatusLabel(status: string) { return statusText(status) }; function runtimeStatusLabel(status: RuntimeStatus) { return ({ ONLINE: '在线', DEGRADED: '降级', OFFLINE: '离线' } as Record<string, string>)[status] || status }; function riskText(risk: RiskLevel) { return ({ LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '关键' } as Record<string, string>)[risk] }; function riskType(risk: RiskLevel) { return risk === 'LOW' ? 'success' : risk === 'MEDIUM' ? 'warning' : 'danger' }; function statusType(status: string) { return status === 'ACTIVE' || status === 'SUCCEEDED' || status === 'ONLINE' ? 'success' : status === 'PAUSED' || status === 'DEGRADED' || status === 'STARTED' ? 'warning' : status === 'FAILED' || status === 'CRITICAL' ? 'danger' : 'info' }; function traceKindLabel(kind: TraceSpanKind) { return ({ MODEL: '模型', TOOL: '工具', MCP: 'MCP', RAG: '知识检索', A2A: '智能体协作', AGENT: '智能体' } as Record<string, string>)[kind] || kind }; function traceTone(kind: TraceSpanKind) { return kind === 'MODEL' ? 'primary' : kind === 'TOOL' || kind === 'MCP' ? 'warning' : kind === 'RAG' ? 'success' : 'info' }; function statusLabel(status: string) { return statusText(status) }; function directionLabel(direction: ContentDirection) { return ({ INPUT: '模型输入', OUTPUT: '模型输出', TOOL_INPUT: '工具输入', TOOL_OUTPUT: '工具输出', SYSTEM: '系统信息' } as Record<string, string>)[direction] || direction }; function directionGovernanceLabel(direction: string) { return ({ INPUT_TO_MODEL: '进入大模型', OUTPUT_FROM_MODEL: '模型输出', TOOL_INPUT: '工具输入', TOOL_OUTPUT: '工具输出' } as Record<string, string>)[direction] || direction }; function actionLabel(action: GovernanceDataAction) { return ({ ALLOW: '允许', WARN: '告警', REDACT: '脱敏', BLOCK: '阻断', APPROVAL_REQUIRED: '需审批' } as Record<string, string>)[action] || action }
function discoverySourceLabel(source: string) { return ({ PROCESS: '运行进程', MANIFEST: '项目清单', CONFIGURATION: '本地配置', SKILL: '智能体技能', PLUGIN: '智能体插件', EXTERNAL: '外部系统', DOCKER: 'Docker 容器', A2A: 'A2A 协议', MCP_REGISTRY: 'MCP 注册中心', KUBERNETES_WATCH: 'Kubernetes 监听', KUBERNETES_ADMISSION: 'Kubernetes 准入', EBPF: 'eBPF 网络观测', HOST_OBSERVER: '跨平台主机观测' } as Record<string, string>)[source] || source }
watch([() => route.params.id, () => route.query.tab], ([id, tab]) => {
  const destination = legacyAgentDestination(String(id), tab)
  if (destination) void router.replace(destination)
}, { immediate: true })
onBeforeUnmount(() => { loadRequestId++; resetHistory() })
watch([agentId, () => context.windowHours, () => permissions.state.user], load, { immediate: true })
</script>

<style scoped>
.agent-record-links { display: grid; align-content: center; gap: 2px; padding: 20px 24px; border-left: 1px solid var(--console-line); background: #f8fafc; }
.agent-record-links > span { color: var(--console-muted); font-size: 12px; margin-bottom: 8px; }
.agent-record-links a { color: var(--el-color-primary-dark-2); font-size: 13px; text-decoration: none; padding: 7px 0; line-height: 1.6; }
@media (max-width: 1180px) { .agent-record-links { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 4px 16px; border-left: 0; border-top: 1px solid var(--console-line); }.agent-record-links > span { grid-column: 1 / -1; } }
@media (max-width: 600px) { .agent-record-links { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 16px 20px; }.agent-record-links a { min-height: 40px; } }
.agent-record-links a:hover { text-decoration: underline; }
.agent-record-links a:focus-visible { outline: 2px solid var(--console-accent); outline-offset: 4px; }
.agent-object-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 12px; margin: 0; }.agent-object-heading strong { font-size: 20px; }.agent-object-heading span { color: var(--console-muted); font-size: 13px; }
.agent-summary-note { margin: 0; color: var(--console-muted); font-size: 12px; line-height: 1.7; }
</style>
