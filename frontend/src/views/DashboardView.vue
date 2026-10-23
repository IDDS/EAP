<template>
  <section class="page-grid agentops-workbench" :aria-busy="loading">
    <header class="agentops-page-header">
      <div>
        <span class="interface-eyebrow">EAP · 工作入口</span>
        <h2>运营工作台</h2>
        <p>先查看待办，再跟进任务进展和业务结果。当前展示所选范围的事项，并非仅分配给你的事项。</p>
      </div>
      <div class="button-row">
        <span class="context-summary">{{ context.scopeLabel }} · {{ context.windowLabel }}</span>
        <router-link to="/command-center" class="command-center-link">打开运行大屏 ↗</router-link>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新视图</el-button>
      </div>
    </header>
    <p class="muted">信号同步：{{ dashboard.lastSignalSyncAt ? formatDate(dashboard.lastSignalSyncAt) : '尚未完成' }} · {{ ({ NO_DATA: '暂无同步记录', FRESH: '最近已同步', AGING: '待更新', STALE: '同步已过期' } as Record<string, string>)[dashboard.signalSyncFreshness] }}<span v-if="dashboard.demoDataIncluded"> · 含演示智能体</span></p>



    <el-alert v-if="dashboardError" type="error" :closable="false" show-icon :title="dashboardError" />
    <nav class="workbench-start" aria-label="开始处理工作">
      <button type="button" @click="openWorkQueue"><strong><el-icon aria-hidden="true"><List /></el-icon>处理待办</strong><span>查看异常、负责人和处理记录</span><small>{{ hasDashboard ? `${dashboard.workItems.totalOpen} 项待处理` : '查看待办列表' }} →</small></button>
      <router-link to="/task-workspace"><strong><el-icon aria-hidden="true"><VideoPlay /></el-icon>跟进任务</strong><span>查看执行进展，核对任务结果</span><small>打开全部执行记录 →</small></router-link>
      <router-link to="/management"><strong><el-icon aria-hidden="true"><Money /></el-icon>查看业务成果</strong><span>查看登记的收益和业务改善记录</span><small>打开业务价值 →</small></router-link>
    </nav>
    <div v-show="workbenchTab === 'queue'" class="workbench-priority-strip">
      <button class="priority-cell priority-critical" type="button" :aria-pressed="severityFilter === 'P0'" :class="{ 'is-selected': severityFilter === 'P0' }" @click="applySeverity('P0')">
        <span>P0 · 立即响应</span><strong>{{ hasDashboard ? dashboard.workItems.p0 : '—' }}</strong><small>关键安全或治理阻断</small>
      </button>
      <button class="priority-cell priority-high" type="button" :aria-pressed="severityFilter === 'P1'" :class="{ 'is-selected': severityFilter === 'P1' }" @click="applySeverity('P1')">
        <span>P1 · 高优先</span><strong>{{ hasDashboard ? dashboard.workItems.p1 : '—' }}</strong><small>离线、身份与高风险事项</small>
      </button>
      <button class="priority-cell priority-medium" type="button" :aria-pressed="severityFilter === 'P2'" :class="{ 'is-selected': severityFilter === 'P2' }" @click="applySeverity('P2')">
        <span>P2 · 计划内处理</span><strong>{{ hasDashboard ? dashboard.workItems.p2 : '—' }}</strong><small>降级与观测完整性</small>
      </button>
      <button class="priority-cell priority-overdue" type="button" :aria-pressed="showOverdueOnly" :class="{ 'is-selected': showOverdueOnly }" @click="toggleOverdue">
        <span>处理时限 · 已逾期</span><strong>{{ hasDashboard ? dashboard.workItems.overdue : '—' }}</strong><small>{{ showOverdueOnly ? '再次点击取消筛选' : '需要升级或重新分派' }}</small>
      </button>
    </div>

    <nav class="workbench-tabs" aria-label="工作台视图">
      <button v-for="tab in workbenchTabs" :key="tab.key" :aria-pressed="workbenchTab === tab.key" @click="workbenchTab = tab.key">{{ tab.label }}<span v-if="tab.key === 'queue' && hasDashboard">{{ dashboard.workItems.totalOpen }}</span></button>
    </nav>
    <section v-if="workbenchTab === 'catalog'" class="capability-directory" aria-label="平台能力导航">
      <header><h2>按工作流程找到功能</h2><p>从智能体接入到业务价值，功能入口与左侧导航保持一致。</p></header>
      <div v-for="(group, index) in navigationGroups" :key="group.id" class="capability-row">
        <div class="capability-purpose"><span class="capability-order">{{ String(index + 1).padStart(2, '0') }}</span><div><h3><el-icon aria-hidden="true"><component :is="navigationIcons[group.icon]" /></el-icon>{{ group.label }}</h3><p>{{ group.description }}</p></div></div>
        <div class="capability-links"><router-link v-for="item in group.items" :key="item.path" :to="item.path"><el-icon aria-hidden="true"><component :is="navigationIcons[item.icon]" /></el-icon><span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span><el-icon aria-hidden="true"><ArrowRight /></el-icon></router-link></div>
      </div>
    </section>
    <div v-show="workbenchTab === 'metrics'" class="evidence-metric-grid">
      <article v-for="metric in dashboard.metrics" :key="metric.key" class="evidence-metric-card">
        <div><span>{{ metric.label }}</span><EvidenceBadge :basis="metric.basis" :source="metric.source" :freshness="freshnessLabel(metric.freshness)" /></div>
        <strong>{{ formatNumber(metric.value) }}</strong>
        <small>{{ metric.source }} · {{ metric.measuredAt ? formatDate(metric.measuredAt) : '尚无观测时间' }}</small>
      </article>
    </div>

    <el-card v-show="workbenchTab === 'network'" shadow="never" class="panel topology-panel">
      <template #header>
        <div class="panel-header">
          <span>智能体网络控制面 / 数据面</span>
          <div class="plane-legend">
            <span><i class="legend-control" />控制面</span>
            <span><i class="legend-data" />数据面</span>
            <el-select v-model="networkViewMode" size="small" class="network-view-select" aria-label="拓扑关系视图">
              <el-option label="全部关系" value="ALL" />
              <el-option label="仅控制面" value="CONTROL" />
              <el-option label="仅数据面" value="DATA" />
            </el-select>
            <el-button text :icon="Refresh" :loading="networkLoading" @click="loadNetwork">刷新拓扑</el-button>
          </div>
        </div>
      </template>
      <el-alert
        v-if="networkError"
        class="topology-error-alert"
        type="error"
        :closable="false"
        show-icon
      >
        <template #default>
          <span>{{ networkError }}</span>
          <el-button link type="danger" :loading="networkLoading" @click="loadNetwork">重试</el-button>
        </template>
      </el-alert>
      <div class="network-workbench">
        <AgentNetworkGraph :nodes="network.nodes" :edges="visibleNetworkEdges" :selected-id="selectedNetworkNode?.id" @select="selectNetworkNode" />

        <aside class="network-side">
          <div class="network-stat-grid">
            <div><span>控制节点</span><strong>{{ network.stats.controlNodeCount }}</strong></div>
            <div><span>智能体节点</span><strong>{{ network.stats.agentNodeCount }}</strong></div>
            <div><span>控制关系 · 筛选后</span><strong>{{ visibleControlEdgeCount }}</strong></div>
            <div><span>编排关系 · 筛选后</span><strong>{{ visibleDataEdgeCount }}</strong></div>
          </div>

          <div class="network-interpretation-note">
            <strong>读图提示</strong>
            <span>连线表示注册、控制或编排关系，不代表实际网络流量或实例在线状态。可派发能力以右侧实例检查为准。</span>
          </div>

          <AgentControlPanel :agents="agentNodes" :agent-id="selectedAgentId" :commands="commands" :refreshing="networkLoading" @select="selectNetworkNode" @refresh="loadNetwork" />
        </aside>
      </div>
    </el-card>

    <el-card v-show="workbenchTab === 'queue'" shadow="never" class="panel workbench-queue-panel">
      <template #header>
        <div class="panel-header workbench-panel-header">
          <div><strong>运营事项队列</strong><span>{{ queueCaption }}</span></div>
          <div class="workbench-filters">
            <el-select v-model="severityFilter" clearable placeholder="全部级别" aria-label="事项优先级" style="width: 126px">
              <el-option v-for="level in ['P0', 'P1', 'P2', 'P3']" :key="level" :label="level" :value="level" />
            </el-select>
            <el-button text @click="clearFilters">清除筛选</el-button>
          </div>
        </div>
      </template>
      <div v-if="severityFilter || showOverdueOnly" class="queue-filter-summary" role="status"><span>当前筛选：{{ severityFilter || '全部级别' }}{{ showOverdueOnly ? ' · 仅逾期' : '' }}</span><el-button link @click="clearFilters">重置</el-button></div>
      <el-empty v-if="!hasDashboard && !loading" description="事项数据尚未加载，请刷新重试" :image-size="64" />
      <WorkItemTable id="work-queue-list" v-else
        :items="filteredWorkItems"
        :reset-key="`${severityFilter}:${showOverdueOnly}`"
        :loading="loading"
        @inspect="inspectWorkItem"
        @transition="prepareTransition"
        @open-agent="openAgent"
      />
    </el-card>

    <div v-show="workbenchTab === 'queue' && hasDashboard" class="workbench-lower-grid">
      <el-card shadow="never" class="panel attention-agent-panel">
        <template #header><div class="panel-header"><span>需要关注的 Agent</span><el-button link type="primary" @click="router.push('/agents')">查看全部</el-button></div></template>
        <button v-for="agent in dashboard.attentionAgents" :key="agent.id" class="attention-agent-row" type="button" @click="openAgent(agent.id)">
          <div class="attention-agent-identity"><strong>{{ agent.name }}</strong><span>{{ agent.code }} · {{ agent.ownerDepartment || '待分配部门' }}</span></div>
          <StatusAxis axis="health" :value="agent.operationalHealth" />
          <StatusAxis axis="coverage" :value="agent.observationCoverage" />
          <StatusAxis axis="risk" :value="agent.riskPosture" />
          <b>{{ agent.openWorkItemCount }} 项</b>
          <el-icon><ArrowRight /></el-icon>
        </button>
        <el-empty v-if="!dashboard.attentionAgents.length" description="当前没有需要关注的 Agent" :image-size="64" />
      </el-card>

      <el-card shadow="never" class="panel lifecycle-entry-panel">
        <template #header><span>生命周期入口</span></template>
        <button v-for="entry in lifecycleEntries" :key="entry.path" type="button" @click="router.push(entry.path)">
          <span class="lifecycle-entry-index">{{ entry.index }}</span>
          <div><strong>{{ entry.title }}</strong><small>{{ entry.description }}</small></div>
          <el-icon><ArrowRight /></el-icon>
        </button>
      </el-card>
    </div>
  </section>

  <el-drawer v-model="detailVisible" append-to-body :z-index="4000" title="运营事项证据" size="min(520px, 100vw)">
    <template v-if="selectedWorkItem">
      <div class="work-item-drawer-heading">
        <span class="severity-badge" :class="`severity-${selectedWorkItem.severity.toLowerCase()}`">{{ selectedWorkItem.severity }}</span>
        <h3>{{ selectedWorkItem.title }}</h3>
        <p>{{ selectedWorkItem.description }}</p>
      </div>
      <div class="work-item-evidence-grid">
        <div><span>事项状态</span><strong>{{ workItemStatusLabel(selectedWorkItem.status) }}</strong></div>
        <div><span>组织范围</span><strong>{{ selectedWorkItem.organizationScope || '企业全局' }}</strong></div>
        <div><span>责任人</span><strong>{{ selectedWorkItem.assignee || '待认领' }}</strong></div>
        <div><span>证据基础</span><EvidenceBadge :basis="selectedWorkItem.evidenceBasis" /></div>
        <div><span>首次发现</span><strong>{{ formatDate(selectedWorkItem.firstDetectedAt) }}</strong></div>
        <div><span>最后发现</span><strong>{{ formatDate(selectedWorkItem.lastDetectedAt) }}</strong></div>
      </div>
      <div class="evidence-json-block"><span>源证据</span><pre>{{ formattedEvidence }}</pre></div>
    </template>
  </el-drawer>

  <el-dialog v-model="transitionVisible" :title="transitionTitle" width="min(520px, 94vw)">
    <el-form label-position="top">
      <el-form-item v-if="targetStatus === 'CLAIMED'" label="负责人">
        <el-input v-model="transitionForm.assignee" placeholder="输入本次事项负责人" />
      </el-form-item>
      <el-form-item v-if="targetStatus === 'RESOLVED'" label="解决说明">
        <el-input v-model="transitionForm.reason" type="textarea" :rows="4" placeholder="说明处置结果和可验证证据" />
      </el-form-item>
      <el-form-item v-if="targetStatus === 'RESOLVED'" label="证据引用（可选）">
        <el-input v-model="transitionForm.resolutionEvidenceJson" placeholder='例如 {"traceId":"...","ticket":"..."}' />
      </el-form-item>
    </el-form>
    <template #footer><el-button @click="transitionVisible = false">取消</el-button><el-button type="primary" :loading="transitioning" @click="submitTransition">确认</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { navigationGroups } from '@/navigation/catalog'
import { navigationIcons } from '@/navigation/icons'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { List, VideoPlay, Money, ArrowRight, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import EvidenceBadge from '@/components/EvidenceBadge.vue'
import StatusAxis from '@/components/StatusAxis.vue'
import WorkItemTable from '@/components/WorkItemTable.vue'
import AgentNetworkGraph from '@/components/AgentNetworkGraph.vue'
import AgentControlPanel from '@/components/AgentControlPanel.vue'
import { usePermissions } from '@/stores/permissions'
import { EapApi } from '@/api/eap'
import { useAgentOpsContextStore } from '@/stores/agentOps'
import type { AgentControlCommand, AgentOpsDashboard, AgentOpsWorkItem, DashboardNetwork, NetworkNode, WorkItemSeverity, WorkItemStatus } from '@/types/domain'

type NetworkViewMode = 'ALL' | 'CONTROL' | 'DATA'

const router = useRouter()
const context = useAgentOpsContextStore()
function openWorkQueue() {
  workbenchTab.value = 'queue'
  requestAnimationFrame(() => document.getElementById('work-queue-list')?.scrollIntoView({behavior:'smooth',block:'start'}))
}
const workbenchTab = ref('queue')
const workbenchTabs = [{ key: 'queue', label: '待处理事项' }, { key: 'catalog', label: '平台能力导航' }, { key: 'network', label: '拓扑与控制' }, { key: 'metrics', label: '观测指标' }]
const hasDashboard = ref(false)
const dashboardError = ref('')
const loading = ref(false)
const networkLoading = ref(false)
const networkError = ref('')
const transitioning = ref(false)
const severityFilter = ref<WorkItemSeverity>()
const showOverdueOnly = ref(false)
const detailVisible = ref(false)
const transitionVisible = ref(false)
const selectedWorkItem = ref<AgentOpsWorkItem>()
const targetStatus = ref<WorkItemStatus>()
const transitionForm = reactive({ assignee: '当前操作员', reason: '', resolutionEvidenceJson: '' })
const dashboard = ref<AgentOpsDashboard>({
  lastSignalSyncAt: null, signalSyncFreshness: 'NO_DATA',
  windowHours: 24, windowStart: '', windowEnd: '', timeZone: 'Asia/Shanghai', organizationScope: '企业全局',
  agentCount: 0, healthyAgentCount: 0, degradedAgentCount: 0, offlineAgentCount: 0,
  missingObservationCount: 0, highRiskAgentCount: 0,
  workItems: { totalOpen: 0, p0: 0, p1: 0, p2: 0, p3: 0, overdue: 0 },
  metrics: [], priorityWorkItems: [], attentionAgents: [], demoDataIncluded: false, generatedAt: '',
})
const network = ref<DashboardNetwork>({
  nodes: [],
  edges: [],
  stats: { controlNodeCount: 0, agentNodeCount: 0, controlEdgeCount: 0, dataEdgeCount: 0 },
})
const commands = ref<AgentControlCommand[]>([])
const selectedNetworkNode = ref<NetworkNode>()
const permissions = usePermissions()
const selectedAgentId = computed(() => selectedNetworkNode.value?.nodeType === 'AGENT' ? Number(selectedNetworkNode.value.id.slice(6)) : undefined)
const networkViewMode = ref<NetworkViewMode>('ALL')

const lifecycleEntries = [
  { index: '01', title: '资产与身份', description: '复核发现资产并纳入 Agent 目录', path: '/registry' },
  { index: '02', title: '运行与观测', description: '检查心跳、模型、Trace 和工具信号', path: '/physical' },
  { index: '03', title: '治理与安全', description: '处理审批、策略和高风险访问', path: '/governance' },
  { index: '04', title: '成本与价值', description: '核对用量、成本和业务价值证据', path: '/management' },
  { index: '05', title: '自动化', description: '编排团队与可控工作流', path: '/workflow-builder' },
]

const filteredWorkItems = computed(() => dashboard.value.priorityWorkItems.filter(item =>
  (!severityFilter.value || item.severity === severityFilter.value) && (!showOverdueOnly.value || item.overdue),
))
const queueCaption = computed(() => `${filteredWorkItems.value.length} / ${dashboard.value.workItems.totalOpen} 项 · 生成于 ${formatDate(dashboard.value.generatedAt)}`)
const visibleNetworkEdges = computed(() => network.value.edges.filter((edge) => {
  if (networkViewMode.value === 'CONTROL') return edge.plane === 'CONTROL'
  if (networkViewMode.value === 'DATA') return edge.plane === 'DATA'
  return true
}))
const visibleControlEdgeCount = computed(() => visibleNetworkEdges.value.filter((edge) => edge.plane === 'CONTROL').length)
const visibleDataEdgeCount = computed(() => visibleNetworkEdges.value.filter((edge) => edge.plane === 'DATA').length)
const agentNodes = computed(() => network.value.nodes.filter((node) => node.nodeType === 'AGENT'))
const formattedEvidence = computed(() => {
  if (!selectedWorkItem.value?.evidenceJson) return '无结构化源证据'
  try { return JSON.stringify(JSON.parse(selectedWorkItem.value.evidenceJson), null, 2) } catch { return selectedWorkItem.value.evidenceJson }
})
const transitionTitle = computed(() => targetStatus.value === 'CLAIMED' ? '认领运营事项' : targetStatus.value === 'IN_PROGRESS' ? '开始处理' : '提交解决结果')

let dashboardRequestId = 0
async function load() {
  const requestId = ++dashboardRequestId
  loading.value = true
  try {
    const result = await EapApi.getAgentOpsDashboard({
      hours: context.windowHours,
      organizationScope: context.organizationScope || undefined,
    })
    if (requestId === dashboardRequestId) { dashboard.value = result; hasDashboard.value = true; dashboardError.value = '' }
  } catch (error) {
    if (requestId === dashboardRequestId) dashboardError.value = hasDashboard.value ? '更新失败，以下保留上次成功加载的结果。请刷新重试。' : '运营数据暂不可用，请检查连接后刷新重试。'
  } finally { if (requestId === dashboardRequestId) loading.value = false }
}

let networkRequestId = 0
async function loadNetwork() {
  const revision = ++networkRequestId, user = permissions.state.user
  networkLoading.value = true
  networkError.value = ''
  try {
    const [nextNetwork, nextCommands] = await Promise.all([EapApi.getDashboardNetwork(), EapApi.listControlCommands()])
    if (revision !== networkRequestId || user !== permissions.state.user) return
    network.value = nextNetwork
    commands.value = nextCommands
    selectedNetworkNode.value = nextNetwork.nodes.find(n => n.id === selectedNetworkNode.value?.id)
      || nextNetwork.nodes.find(n => n.nodeType === 'AGENT') || nextNetwork.nodes[0]
  } catch (error) {
    if (revision === networkRequestId && user === permissions.state.user) networkError.value = error instanceof Error ? error.message : '智能体拓扑或控制状态加载失败'
  } finally { if (revision === networkRequestId) networkLoading.value = false }
}
function selectNetworkNode(node: NetworkNode) { selectedNetworkNode.value = node }

function applySeverity(value: WorkItemSeverity) { severityFilter.value = severityFilter.value === value ? undefined : value; workbenchTab.value = 'queue' }
function toggleOverdue() { showOverdueOnly.value = !showOverdueOnly.value; workbenchTab.value = 'queue' }
function clearFilters() { severityFilter.value = undefined; showOverdueOnly.value = false }
function openAgent(id: number) { router.push(`/agents/${id}`) }
function inspectWorkItem(item: AgentOpsWorkItem) { selectedWorkItem.value = item; detailVisible.value = true }
function prepareTransition(item: AgentOpsWorkItem, status: WorkItemStatus) {
  selectedWorkItem.value = item
  targetStatus.value = status
  transitionForm.assignee = item.assignee || '当前操作员'
  transitionForm.reason = ''
  transitionForm.resolutionEvidenceJson = ''
  if (status === 'IN_PROGRESS') void submitTransition()
  else transitionVisible.value = true
}
async function submitTransition() {
  if (!selectedWorkItem.value || !targetStatus.value) return
  if (targetStatus.value === 'CLAIMED' && !transitionForm.assignee.trim()) { ElMessage.warning('请填写负责人'); return }
  if (targetStatus.value === 'RESOLVED' && !transitionForm.reason.trim()) { ElMessage.warning('请填写解决说明'); return }
  transitioning.value = true
  try {
    await EapApi.transitionAgentOpsWorkItem(selectedWorkItem.value.id, {
      status: targetStatus.value,
      assignee: transitionForm.assignee || undefined,
      reason: transitionForm.reason || undefined,
      resolutionEvidenceJson: transitionForm.resolutionEvidenceJson || undefined,
    })
    transitionVisible.value = false
    ElMessage.success('运营事项状态已更新')
    await load()
  } finally { transitioning.value = false }
}
function formatNumber(value: number) { return new Intl.NumberFormat('zh-CN').format(value || 0) }
function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function freshnessLabel(value: string) { return ({ FRESH: '新鲜', AGING: '临近过期', STALE: '已过期', NO_DATA: '无数据' } as Record<string, string>)[value] || value }
function workItemStatusLabel(value: WorkItemStatus) { return ({ OPEN: '待处理', CLAIMED: '已认领', IN_PROGRESS: '处理中', WAITING: '等待中', RESOLVED: '已解决', CLOSED: '已关闭' } as Record<WorkItemStatus, string>)[value] }
watch(() => [context.windowHours, context.organizationScope], load)
watch(() => permissions.state.user, () => {
  networkRequestId++; commands.value = []; network.value = { nodes: [], edges: [], stats: { controlNodeCount: 0, agentNodeCount: 0, controlEdgeCount: 0, dataEdgeCount: 0 } }
  selectedNetworkNode.value = undefined
  if (permissions.state.user) void loadNetwork()
})
onMounted(() => { void Promise.all([load(), loadNetwork()]) })
</script>

<style scoped>
.command-center-link{color:#176b9b;text-decoration:none;border:1px solid #b8cede;border-radius:5px;padding:9px 14px;font-size:14px}.command-center-link:hover{background:#eef5fa}.command-center-link:focus-visible{outline:2px solid #2683b8;outline-offset:3px}
</style>

<style scoped>
.workbench-start{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px}.workbench-start>a,.workbench-start>button{display:flex;flex-direction:column;align-items:flex-start;gap:8px;padding:18px;border:1px solid var(--soc-line,#dce3eb);border-radius:8px;background:var(--soc-surface,#fff);text-align:left;color:var(--el-text-color-primary);text-decoration:none;font:inherit;cursor:pointer}.workbench-start strong{font-size:16px}.workbench-start span{font-size:13px;color:var(--el-text-color-secondary)}.workbench-start small{color:var(--el-color-primary);font-size:12px}.workbench-start>a:hover,.workbench-start>button:hover{border-color:var(--el-color-primary)}@media(max-width:700px){.workbench-start{grid-template-columns:1fr}}
</style>

<style scoped>
@media(max-width:700px){
  .topology-panel .panel-header{flex-direction:column;align-items:flex-start;gap:12px}
  .topology-panel .panel-header>span:first-child{width:100%;flex:none}
  .topology-panel .plane-legend{width:100%;flex-wrap:wrap;gap:8px}
  .topology-panel .plane-legend>span{white-space:nowrap;flex-shrink:0}
}
</style>
