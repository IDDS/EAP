<template>
  <section class="page-grid agentops-workbench" :aria-busy="loading">
    <header class="agentops-page-header">
      <div>
        <span class="interface-eyebrow">EAP · ACTION WORKBENCH</span>
        <h2>运营工作台</h2>
        <p>优先处理影响运行、身份、治理和观测完整性的事项。按优先级查看证据、分派责任并跟进处置。</p>
      </div>
      <div class="button-row">
        <span class="context-summary">{{ context.scopeLabel }} · {{ context.windowLabel }}</span>
        <router-link to="/command-center" class="command-center-link">打开运行大屏 ↗</router-link>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新视图</el-button>
      </div>
    </header>
    <p class="muted">信号同步：{{ dashboard.lastSignalSyncAt ? formatDate(dashboard.lastSignalSyncAt) : '尚未完成' }} · {{ ({ NO_DATA: '暂无同步记录', FRESH: '最近已同步', AGING: '待更新', STALE: '同步已过期' } as Record<string, string>)[dashboard.signalSyncFreshness] }}<span v-if="dashboard.demoDataIncluded"> · 含演示智能体</span></p>



    <el-alert v-if="dashboardError" type="error" :closable="false" show-icon :title="dashboardError" />
    <div class="workbench-priority-strip">
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
        <span>SLA · 已逾期</span><strong>{{ hasDashboard ? dashboard.workItems.overdue : '—' }}</strong><small>{{ showOverdueOnly ? '再次点击取消筛选' : '需要升级或重新分派' }}</small>
      </button>
    </div>

    <nav class="workbench-tabs" aria-label="工作台视图">
      <button v-for="tab in workbenchTabs" :key="tab.key" :aria-pressed="workbenchTab === tab.key" @click="workbenchTab = tab.key">{{ tab.label }}<span v-if="tab.key === 'queue' && hasDashboard">{{ dashboard.workItems.totalOpen }}</span></button>
    </nav>
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
              <el-option label="有运行证据" value="EVIDENCE" />
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
        <div
          ref="networkCanvasRef"
          class="network-canvas"
          @mousemove="moveNetworkNode"
          @mouseup="stopNetworkDrag"
          @mouseleave="stopNetworkDrag"
        >
          <svg class="network-lines" viewBox="0 0 1040 620" preserveAspectRatio="xMidYMid meet">
            <defs>
              <marker id="arrow-control" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
                <path d="M0,0 L0,6 L9,3 z" fill="#2563eb" />
              </marker>
              <marker id="arrow-data" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto" markerUnits="strokeWidth">
                <path d="M0,0 L0,6 L9,3 z" fill="#16a34a" />
              </marker>
            </defs>
            <line
              v-for="edge in networkEdges"
              :key="edge.id"
              :x1="edge.x1"
              :y1="edge.y1"
              :x2="edge.x2"
              :y2="edge.y2"
              :class="['network-edge', edge.plane.toLowerCase()]"
              :marker-end="edge.plane === 'CONTROL' ? 'url(#arrow-control)' : 'url(#arrow-data)'"
            />
            <text
              v-for="edge in networkEdges"
              :key="`${edge.id}-label`"
              :x="edge.labelX"
              :y="edge.labelY"
              :class="['network-edge-label', edge.plane.toLowerCase()]"
            >
              {{ edge.label }}
            </text>
          </svg>
          <button
            v-for="node in network.nodes"
            :key="node.id"
            type="button"
            class="network-node"
            :class="[node.plane.toLowerCase(), { selected: selectedNetworkNode?.id === node.id, dragging: draggingNodeId === node.id }]"
            :style="{ left: `${node.x}px`, top: `${node.y}px` }"
            @mousedown.stop="startNetworkDrag(node, $event)"
            @click="selectNetworkNode(node)"
          >
            <span>{{ node.nodeType }}</span>
            <strong>{{ node.label }}</strong>
            <em>{{ node.status }} · {{ node.metric }}</em>
          </button>
          <el-empty v-if="!networkLoading && !network.nodes.length" description="暂无拓扑数据" :image-size="64" />
        </div>

        <aside class="network-side">
          <div class="network-stat-grid">
            <div><span>控制节点</span><strong>{{ network.stats.controlNodeCount }}</strong></div>
            <div><span>Agent 节点</span><strong>{{ network.stats.agentNodeCount }}</strong></div>
            <div><span>控制边 · 当前视图</span><strong>{{ visibleControlEdgeCount }}</strong></div>
            <div><span>数据边 · 当前视图</span><strong>{{ visibleDataEdgeCount }}</strong></div>
          </div>

          <div class="network-interpretation-note">
            <strong>读图提示</strong>
            <span>数据边表示工作流/编排关系，不等于真实网络连接；“有运行证据”仅保留当前有在线、活动或可派发证据的关系。</span>
          </div>

          <div class="control-console">
            <div class="control-console-heading">
              <strong>控制命令</strong>
              <el-tag size="small" :type="controlDispatchEnabled ? 'success' : 'warning'">
                {{ controlDispatchEnabled ? 'Runtime 派发' : '模拟 outbox' }}
              </el-tag>
            </div>
            <el-alert
              v-if="platformStatus && !platformStatus.controlDispatchEnabled"
              type="warning"
              :closable="false"
              show-icon
              title="当前命令只写入 EAP outbox，不会触达真实 Agent Runtime。"
            />
            <span v-else-if="!platformStatus" class="muted">正在确认控制派发状态；提交结果以服务端返回为准。</span>
            <span v-else class="muted">控制面向选中的 Agent Runtime 下发命令，需等待 ACK 才算执行完成。</span>
            <el-select v-model="commandForm.agentId" placeholder="选择 Agent">
              <el-option
                v-for="node in agentNodes"
                :key="node.id"
                :label="node.label"
                :value="Number(node.id.replace('agent:', ''))"
              />
            </el-select>
            <el-select v-model="commandForm.commandType">
              <el-option v-for="command in commandTypes" :key="command" :label="command" :value="command" />
            </el-select>
            <el-input v-model="commandForm.payloadJson" type="textarea" :rows="3" />
            <el-button type="primary" :icon="Connection" :disabled="!commandForm.agentId" :loading="commandLoading" @click="issueCommand">下发控制命令</el-button>
          </div>

          <div class="command-log">
            <div class="panel-header compact">
              <span>最近控制消息</span>
              <el-button text :icon="Refresh" @click="loadNetwork">刷新</el-button>
            </div>
            <div v-for="command in commands" :key="command.id" class="command-log-item">
              <div class="command-log-title">
                <strong>{{ command.commandType }}</strong>
                <div class="command-log-badges">
                  <el-tag size="small" :type="commandStatusType(command.status)">{{ commandStatusLabel(command.status) }}</el-tag>
                  <el-tag size="small" :type="command.dispatchMode === 'CONTROL_PLANE_OUTBOX' ? 'warning' : 'success'">
                    {{ commandDispatchLabel(command.dispatchMode) }}
                  </el-tag>
                </div>
              </div>
              <div class="command-status-timeline" :aria-label="`命令 ${command.commandKey} 状态时间线`">
                <span class="command-status-step is-done"><i />已入队<small>{{ formatDate(command.createdAt) }}</small></span>
                <span :class="['command-status-step', command.dispatchedAt ? 'is-done' : command.status === 'FAILED' ? 'is-failed' : 'is-pending']"><i />已派发<small>{{ command.dispatchedAt ? formatDate(command.dispatchedAt) : '未发生' }}</small></span>
                <span :class="['command-status-step', command.status === 'ACKED' ? 'is-done' : command.status === 'FAILED' ? 'is-failed' : 'is-pending']"><i />ACK<small>{{ command.acknowledgedAt ? formatDate(command.acknowledgedAt) : '未确认' }}</small></span>
              </div>
              <span>{{ command.commandKey }} · Agent {{ command.agentId }} · 尝试 {{ command.attemptCount ?? 0 }} 次</span>
              <small v-if="command.resultMessage">{{ command.resultMessage }}</small>
              <div v-if="command.status === 'FAILED'" class="command-log-actions">
                <el-button size="small" type="warning" plain :loading="retryingCommandKey === command.commandKey" @click="retryCommand(command)">重新尝试</el-button>
              </div>
            </div>
            <el-empty v-if="!commands.length" description="暂无控制消息" :image-size="48" />
          </div>
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
      <WorkItemTable v-else
        :items="filteredWorkItems"
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

  <el-drawer v-model="detailVisible" title="运营事项证据" size="min(520px, 100vw)">
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
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Connection, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import EvidenceBadge from '@/components/EvidenceBadge.vue'
import StatusAxis from '@/components/StatusAxis.vue'
import WorkItemTable from '@/components/WorkItemTable.vue'
import { EapApi } from '@/api/eap'
import { useAgentOpsContextStore } from '@/stores/agentOps'
import type { AgentControlCommand, AgentOpsDashboard, AgentOpsWorkItem, DashboardNetwork, NetworkNode, PlatformStatus, WorkItemSeverity, WorkItemStatus } from '@/types/domain'

type NetworkViewMode = 'ALL' | 'CONTROL' | 'DATA' | 'EVIDENCE'

const router = useRouter()
const context = useAgentOpsContextStore()
const workbenchTab = ref('queue')
const workbenchTabs = [{ key: 'queue', label: '待处理事项' }, { key: 'network', label: '拓扑与控制' }, { key: 'metrics', label: '观测指标' }]
const hasDashboard = ref(false)
const dashboardError = ref('')
const loading = ref(false)
const networkLoading = ref(false)
const networkError = ref('')
const commandLoading = ref(false)
const retryingCommandKey = ref('')
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
const platformStatus = ref<PlatformStatus>()
const selectedNetworkNode = ref<NetworkNode>()
const networkCanvasRef = ref<HTMLElement>()
const draggingNodeId = ref<string>()
const dragOffset = ref({ x: 0, y: 0 })
const draggedDuringMove = ref(false)
const commandForm = ref({
  agentId: undefined as number | undefined,
  commandType: 'RUN_HEALTH_CHECK',
  payloadJson: '{"reason":"operator_request"}',
})
const commandTypes = ['RUN_HEALTH_CHECK', 'RELOAD_POLICY', 'PAUSE', 'RESUME', 'UPDATE_CONFIG', 'SET_RATE_LIMIT']
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
const nodeMap = computed(() => new Map(network.value.nodes.map((node) => [node.id, node])))
const visibleNetworkEdges = computed(() => network.value.edges.filter((edge) => {
  if (networkViewMode.value === 'CONTROL') return edge.plane === 'CONTROL'
  if (networkViewMode.value === 'DATA') return edge.plane === 'DATA'
  if (networkViewMode.value === 'EVIDENCE') return ['ACTIVE', 'ONLINE', 'READY', 'ACKED'].includes(edge.status)
  return true
}))
const visibleControlEdgeCount = computed(() => visibleNetworkEdges.value.filter((edge) => edge.plane === 'CONTROL').length)
const visibleDataEdgeCount = computed(() => visibleNetworkEdges.value.filter((edge) => edge.plane === 'DATA').length)
const networkEdges = computed(() => visibleNetworkEdges.value.map((edge) => {
  const source = nodeMap.value.get(edge.source)
  const target = nodeMap.value.get(edge.target)
  if (!source || !target) return undefined
  return {
    ...edge,
    x1: source.x + 86,
    y1: source.y + 38,
    x2: target.x + 86,
    y2: target.y + 38,
    labelX: (source.x + target.x) / 2 + 86,
    labelY: (source.y + target.y) / 2 + 30,
  }
}).filter((edge): edge is NonNullable<typeof edge> => Boolean(edge)))
const agentNodes = computed(() => network.value.nodes.filter((node) => node.nodeType === 'AGENT'))
const formattedEvidence = computed(() => {
  if (!selectedWorkItem.value?.evidenceJson) return '无结构化源证据'
  try { return JSON.stringify(JSON.parse(selectedWorkItem.value.evidenceJson), null, 2) } catch { return selectedWorkItem.value.evidenceJson }
})
const transitionTitle = computed(() => targetStatus.value === 'CLAIMED' ? '认领运营事项' : targetStatus.value === 'IN_PROGRESS' ? '开始处理' : '提交解决结果')
const controlDispatchEnabled = computed(() => platformStatus.value?.controlDispatchEnabled === true)

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

async function loadNetwork() {
  networkLoading.value = true
  networkError.value = ''
  try {
    const [nextNetwork, nextCommands, nextPlatformStatus] = await Promise.all([
      EapApi.getDashboardNetwork(),
      EapApi.listControlCommands(),
      EapApi.getPlatformStatus().catch(() => undefined),
    ])
    network.value = nextNetwork
    commands.value = nextCommands
    platformStatus.value = nextPlatformStatus
  } catch (error) {
    networkError.value = error instanceof Error ? error.message : '智能体拓扑或控制状态加载失败'
  } finally { networkLoading.value = false }
}

function selectNetworkNode(node: NetworkNode) {
  if (draggedDuringMove.value) { draggedDuringMove.value = false; return }
  selectedNetworkNode.value = node
  if (node.nodeType === 'AGENT') commandForm.value.agentId = Number(node.id.replace('agent:', ''))
}

function startNetworkDrag(node: NetworkNode, event: MouseEvent) {
  draggingNodeId.value = node.id
  draggedDuringMove.value = false
  dragOffset.value = { x: event.offsetX, y: event.offsetY }
  selectedNetworkNode.value = node
  if (node.nodeType === 'AGENT') commandForm.value.agentId = Number(node.id.replace('agent:', ''))
}

function moveNetworkNode(event: MouseEvent) {
  if (!draggingNodeId.value || !networkCanvasRef.value) return
  const node = network.value.nodes.find((item) => item.id === draggingNodeId.value)
  if (!node) return
  const rect = networkCanvasRef.value.getBoundingClientRect()
  draggedDuringMove.value = true
  node.x = Math.min(860, Math.max(12, Math.round(event.clientX - rect.left + networkCanvasRef.value.scrollLeft - dragOffset.value.x)))
  node.y = Math.min(530, Math.max(12, Math.round(event.clientY - rect.top + networkCanvasRef.value.scrollTop - dragOffset.value.y)))
}

function stopNetworkDrag() { draggingNodeId.value = undefined }

async function issueCommand() {
  if (!commandForm.value.agentId) return
  commandLoading.value = true
  try {
    const command = await EapApi.issueControlCommand({
      agentId: commandForm.value.agentId,
      commandType: commandForm.value.commandType,
      payloadJson: commandForm.value.payloadJson,
      issuedBy: 'platform-admin',
    })
    await loadNetwork()
    ElMessage.success(command.dispatchMode === 'CONTROL_PLANE_OUTBOX'
      ? '命令已写入模拟 outbox，尚未触达 Agent'
      : '命令已提交到 Agent Runtime，等待 ACK')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '控制命令下发失败')
  } finally { commandLoading.value = false }
}

async function retryCommand(command: AgentControlCommand) {
  retryingCommandKey.value = command.commandKey
  try {
    const retried = await EapApi.retryControlCommand(command.commandKey)
    await loadNetwork()
    ElMessage.success(retried.dispatchMode === 'CONTROL_PLANE_OUTBOX'
      ? '命令已重新写入模拟 outbox'
      : '命令已重新提交到 Agent Runtime')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '控制命令重试失败')
  } finally { retryingCommandKey.value = '' }
}

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
function commandDispatchLabel(value: string) { return value === 'CONTROL_PLANE_OUTBOX' ? '模拟 outbox' : value === 'HTTP_RUNTIME' ? 'Runtime' : value }
function commandStatusLabel(value: AgentControlCommand['status']) { return ({ QUEUED: '已入队', DISPATCHED: '已派发', ACKED: '已确认', FAILED: '失败' } as Record<AgentControlCommand['status'], string>)[value] }
function commandStatusType(value: AgentControlCommand['status']) { return ({ QUEUED: 'info', DISPATCHED: 'primary', ACKED: 'success', FAILED: 'danger' } as Record<AgentControlCommand['status'], 'info' | 'primary' | 'success' | 'danger'>)[value] }

watch(() => [context.windowHours, context.organizationScope], load)
onMounted(() => { void Promise.all([load(), loadNetwork()]) })
</script>

<style scoped>
.command-center-link{color:#176b9b;text-decoration:none;border:1px solid #b8cede;border-radius:5px;padding:9px 14px;font-size:14px}.command-center-link:hover{background:#eef5fa}.command-center-link:focus-visible{outline:2px solid #2683b8;outline-offset:3px}
</style>
