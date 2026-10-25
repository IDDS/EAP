<template>
  <main ref="screen" class="command-center" :class="{ 'motion-paused': !autoRefresh || documentHidden }">
    <header class="cc-header">
      <div class="cc-brand"><img src="/eap-logo.svg" alt="EAP" width="44" height="44" /><div><span>EAP · 企业智能体控制平面</span><h1>智能体运行指挥中心</h1></div></div>
      <div class="cc-context"><span class="cc-live-dot" :class="{ stale: failedSources.length || !lastUpdated || !autoRefresh || documentHidden }" />{{ autoRefresh ? '自动刷新 · 15 秒' : '已暂停刷新' }}<span class="cc-divider">/</span>企业全局</div>
      <div class="cc-actions"><button @click="toggleAutoRefresh" :aria-pressed="!autoRefresh">{{ autoRefresh ? '暂停' : '继续' }}</button><button :disabled="loading" @click="refresh">{{ loading ? '更新中…' : '刷新' }}</button><button @click="toggleFullscreen">{{ fullscreen ? '退出全屏' : '全屏展示' }}</button><router-link to="/">返回控制台 ↗</router-link></div>
    </header>
    <div class="cc-statusline"><span :class="{ warning: ops?.demoDataIncluded || platform?.securityMode === 'DEVELOPMENT' }">{{ platform ? (platform.securityMode === 'DEVELOPMENT' ? '开发演示环境' : '企业认证环境') : '环境状态待确认' }}{{ ops?.demoDataIncluded ? ' · 含演示资产' : '' }} · {{ platform ? (platform.controlDispatchEnabled ? '控制派发已启用' : '控制派发未启用') : '控制状态未知' }}</span><span>{{ !autoRefresh ? '已暂停 · ' : '' }}{{ lastUpdated ? `最近完整刷新 ${time(lastUpdated)}` : '等待完整数据' }} · {{ clockText }}</span></div>
    <div v-if="failedSources.length || fullscreenError" class="cc-error" role="status">{{ fullscreenError || `以下数据更新失败，保留上次结果：${failedSources.join('、')}。请检查连接或返回控制台认证。` }}</div>
    <div class="cc-period">资产与事项 · 当前状态 <span>调用、Token、评估与业务结果 · 最近 24 小时</span></div>
    <section class="cc-kpis" aria-label="运行指标：资产与事项为当前状态，调用和评估为最近24小时">
      <CommandMetric label="纳管智能体" to="/agents" accent="#79ddff"><template #value><strong>{{ number(ops?.agentCount) }}<small>个</small></strong></template><span class="metric-detail">健康 {{ number(ops?.healthyAgentCount) }} · 离线 {{ number(ops?.offlineAgentCount) }}</span><SourceStatus :updated-at="sourceUpdated['运行健康']" :now="now" :failed="failedSources.includes('运行健康')" :paused="!autoRefresh" /></CommandMetric>
      <CommandMetric label="模型调用" to="/physical" accent="#82b8ff"><template #value><strong>{{ number(telemetry?.totalCalls) }}<small>次</small></strong></template><span class="metric-detail">调用成功率 {{ rate(telemetry?.successRate, telemetry?.totalCalls) }}</span><SourceStatus :updated-at="sourceUpdated['调用遥测']" :now="now" :failed="failedSources.includes('调用遥测')" :paused="!autoRefresh" /></CommandMetric>
      <CommandMetric label="Token 消耗" to="/physical" accent="#69e1c8"><template #value><strong>{{ compact(telemetry?.totalTokens) }}</strong></template><span class="metric-detail">输入 {{ compact(telemetry?.inputTokens) }} · 输出 {{ compact(telemetry?.outputTokens) }}</span><SourceStatus :updated-at="sourceUpdated['调用遥测']" :now="now" :failed="failedSources.includes('调用遥测')" :paused="!autoRefresh" /></CommandMetric>
      <CommandMetric label="待处理事项" to="/" accent="#f4c473"><template #value><strong :class="{ warning: (ops?.workItems.totalOpen || 0) > 0 }">{{ number(ops?.workItems.totalOpen) }}<small>项</small></strong></template><span class="metric-detail">P0 {{ number(ops?.workItems.p0) }} · P1 {{ number(ops?.workItems.p1) }}</span><SourceStatus :updated-at="sourceUpdated['运行健康']" :now="now" :failed="failedSources.includes('运行健康')" :paused="!autoRefresh" /></CommandMetric>
      <CommandMetric label="规则评估通过率" to="/evaluation" accent="#b7adff"><template #value><strong>{{ rate(evaluation?.passRate, evaluation?.total) }}</strong></template><span class="metric-detail">{{ number(evaluation?.total) }} 次评估 · 不代表业务成功率</span><SourceStatus :updated-at="sourceUpdated['评估质量']" :now="now" :failed="failedSources.includes('评估质量')" :paused="!autoRefresh" /></CommandMetric>
    </section>
    <div class="cc-main-grid">
      <section class="cc-panel cc-health"><div class="cc-panel-title"><h2>运行健康</h2><SourceStatus :updated-at="sourceUpdated['运行健康']" :now="now" :failed="failedSources.includes('运行健康')" :paused="!autoRefresh" /></div>
        <LteInfoBox class="health-info" text="健康资产占比" :title="ops && ops.agentCount ? Math.round(ops.healthyAgentCount / ops.agentCount * 100) : '—'" :unit="ops?.agentCount ? '%' : undefined"><span class="health-info-note">基于当前纳管资产</span></LteInfoBox>
        <div class="health-track" aria-hidden="true"><i v-for="state in healthStates" :key="state.label" :style="{ flex: state.value, background: state.color }" /></div>
        <div v-for="state in healthStates" :key="state.label" class="health-row"><span><i :style="{ background: state.color }" />{{ state.label }}</span><strong>{{ ops ? state.value : '—' }}</strong></div>
        <div class="cc-submetric"><span>观测缺失</span><strong>{{ number(ops?.missingObservationCount) }}</strong></div>
        <div class="cc-submetric"><span>高风险智能体</span><strong class="warning">{{ number(ops?.highRiskAgentCount) }}</strong></div>
        <p class="cc-note">基于心跳与遥测覆盖；未观测不等于正常。</p>
      </section>
      <section class="cc-panel cc-topology" @focusin="topologyFocused = true" @focusout="onTopologyBlur"><div class="cc-panel-title"><h2>智能体协作网络</h2><SourceStatus :updated-at="sourceUpdated['拓扑']" :now="now" :failed="failedSources.includes('拓扑')" :paused="!autoRefresh" /></div>
        <div class="topology-toolbar">
          <div class="topology-search"><input :value="agentQuery" type="search" aria-label="搜索拓扑智能体" placeholder="搜索名称或编号" maxlength="100" @input="setAgentQuery(($event.target as HTMLInputElement).value)" @keydown.esc="setAgentQuery('')" /><button v-if="agentQuery" aria-label="清除拓扑搜索" @click="setAgentQuery('')">×</button></div>
          <span class="graph-count">{{ agentQuery ? `${matchingAgents().length} 个匹配智能体` : `${graph.nodes.length} / ${network?.nodes.length || 0} 节点` }} · {{ graph.edges.length }} 条关系</span>
          <div class="topology-pages"><button v-if="selectedNode" :aria-pressed="focusConnections" @click="focusConnections = !focusConnections">{{ focusConnections ? '全部关系' : '只看关联' }}</button><template v-if="agentPageCount > 1"><button :disabled="agentPage === 0" aria-label="上一组智能体" @click="changeAgentPage(-1)">‹</button><span>{{ agentPage + 1 }} / {{ agentPageCount }}</span><button :disabled="agentPage + 1 >= agentPageCount" aria-label="下一组智能体" @click="changeAgentPage(1)">›</button><button :disabled="!!agentQuery" :aria-pressed="autoRotate" title="每20秒切换一组；搜索、选择节点或交互时暂停" @click="autoRotate = !autoRotate">{{ autoRotate ? '停止轮播' : '轮播' }}</button></template></div>
        </div>
        <div class="topology-canvas">
          <TopologyViewport v-if="graph.nodes.length && (!agentQuery || matchingAgents().length)" :height="graph.nodes.filter(n => n.plane === 'DATA').length > 5 ? 450 : 330" :selected="graph.nodes.find(n => n.id === selectedNode?.id)">
            <defs><pattern id="cc-grid" width="32" height="32" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r=".8" fill="#456f91" /></pattern><marker id="cc-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse"><path d="M0 0L10 5L0 10" fill="#7db6d6" /></marker></defs>
            <rect width="1000" height="450" fill="url(#cc-grid)" />
            <path v-for="edge in graph.edges" :key="edge.id" :d="edge.path" class="cc-edge" :class="{ data: edge.plane === 'DATA', highlighted: selectedNode?.id === edge.source || selectedNode?.id === edge.target, observed: edge.status === 'ONLINE' && !failedSources.includes('拓扑') && now - (sourceUpdated['拓扑'] || 0) < 45000 }" marker-end="url(#cc-arrow)"><title>{{ edge.label }} · {{ edge.status }}（关系状态，不是实时流量）</title></path>
            <g v-for="node in graph.nodes" :key="node.id" :transform="`translate(${node.px},${node.py})`" class="cc-node" :class="{ control: node.plane === 'CONTROL', selected: selectedNode?.id === node.id }" role="button" tabindex="0" :aria-pressed="selectedNode?.id === node.id" :aria-label="`${node.label}，${node.status}`" @click="selectedNode = node" @keydown.enter="selectedNode = node" @keydown.space.prevent="selectedNode = node">
              <rect x="-78" y="-31" width="156" height="62" rx="7" /><circle cx="-60" cy="-11" r="3" :fill="node.status === 'ONLINE' ? '#47d6b0' : '#91a4b6'" /><text x="-49" y="-6" class="node-kind">{{ node.plane === 'CONTROL' ? '控制节点' : '智能体' }}</text><text text-anchor="middle" y="17" class="node-name">{{ shorten(node.label, 10) }}</text><title>{{ node.label }} · {{ node.status }} · {{ node.metric }}</title>
            </g>
          </TopologyViewport>
          <div v-else class="cc-empty">{{ agentQuery ? '没有匹配的智能体，试试其他名称或编号。' : loading ? '正在加载关系网络…' : '暂无拓扑数据' }}</div>
        </div>
        <div class="topology-caption"><span v-if="selectedNode" class="selected-node"><b>{{ selectedNode.label }}</b> · {{ selectedNode.status }} · {{ selectedNode.metric }} <span v-if="offPageConnections">· {{ offPageConnections }} 条关联在其他页或筛选外</span> <router-link v-if="selectedAgentPath" :to="selectedAgentPath">查看智能体 ↗</router-link><button aria-label="清除节点选择" @click="selectedNode = undefined">×</button></span><span v-else>{{ autoRotate ? (topologyFocused || !autoRefresh ? '轮播等待中 · 交互或暂停刷新时保持当前组' : '轮播已开启 · 每 20 秒切换一组') : '选择节点查看关系与状态' }}</span><span>控制关系 <i class="legend-line" /> 编排关系 <i class="legend-line data" /></span></div>
        <p class="cc-note">流动线表示潜在调用路径示意，非实测流量；关系来自配置与心跳。<span>拖动画布、滚轮缩放；键盘方向键平移，Home 复位。</span></p>
      </section>
      <section class="cc-panel cc-attention"><div class="cc-panel-title"><h2>优先处理 · 前 4 项</h2><router-link to="/">处理事项 ↗</router-link></div>
        <div class="attention-summary"><span>逾期事项</span><strong>{{ number(ops?.workItems.overdue) }}</strong></div>
        <ol v-if="ops?.priorityWorkItems.length" class="attention-list"><li v-for="item in ops.priorityWorkItems.slice(0, 4)" :key="item.id"><span class="severity" :class="item.severity.toLowerCase()">{{ item.severity }}</span><div><router-link :to="item.agentId ? `/agents/${item.agentId}` : '/'" :title="item.title"><strong>{{ item.title }} <span aria-hidden="true">↗</span></strong></router-link><p>{{ item.assignee || '未分派' }} · {{ item.overdue ? '已逾期' : '待处理' }}</p></div></li></ol>
        <div v-else class="cc-empty">{{ ops ? '当前没有待处理事项' : '事项数据暂不可用' }}</div>
        <p class="cc-note">信号同步 {{ ops?.lastSignalSyncAt ? time(ops.lastSignalSyncAt) : '暂无记录' }} · {{ freshness }}</p>
      </section>
    </div>
    <div class="cc-bottom-grid">
      <CommandPanel title="模型 Token 分布 · 前 4 个"><div class="panel-freshness"><SourceStatus :updated-at="sourceUpdated['调用遥测']" :now="now" :failed="failedSources.includes('调用遥测')" :paused="!autoRefresh" /></div><div v-for="model in topModels" :key="`${model.provider}:${model.modelName}`" class="model-row"><span :title="`${model.provider} / ${model.modelName}`">{{ model.modelName }}</span><div><i :style="{ transform: `scaleX(${model.totalTokens / maxModelTokens})` }" /></div><strong>{{ compact(model.totalTokens) }}</strong></div><div v-if="!topModels.length" class="cc-empty small">{{ telemetry ? '暂无模型调用观测' : '模型调用数据暂不可用' }}</div></CommandPanel>
      <section class="cc-panel"><div class="cc-panel-title"><h2>业务价值与成果</h2><router-link to="/management">查看价值 ↗</router-link></div><div class="panel-freshness"><SourceStatus :updated-at="sourceUpdated['业务价值']" :now="now" :failed="failedSources.includes('业务价值')" :paused="!autoRefresh" /></div><div class="business-grid"><div><span>业务标记已解决</span><strong>{{ number(management?.resolvedTasks) }}</strong></div><div><span>业务结果待确认</span><strong>{{ number(management?.unconfirmedTasks) }}</strong></div><div><span>可度量价值事件</span><strong>{{ number(management?.measuredImpactEvents) }}</strong></div><div><span>{{ management?.currency ? `登记收益 · ${management.currency}` : '登记收益' }}</span><strong>{{ management?.hasValidatedValue && management.currency && management.validatedBusinessValue != null ? number(management.validatedBusinessValue, 2) : '—' }}</strong></div></div><p class="cc-note">{{ management?.mixedCurrencies && !management.currency ? '存在多币种，未合计金额。' : '收益为登记值，以业务证据核验；未计算 ROI。' }}</p></section>
      <section class="cc-panel"><div class="cc-panel-title"><h2>评估处理进度</h2><router-link to="/evaluation">查看证据 ↗</router-link></div><div class="panel-freshness"><SourceStatus :updated-at="sourceUpdated['评估进度']" :now="now" :failed="failedSources.includes('评估进度')" :paused="!autoRefresh" /></div><div v-if="latestJob" class="cc-job"><div><strong>{{ jobLabel }}</strong><span>{{ latestJob.processed || 0 }} / {{ latestJob.total || 0 }}</span></div><div class="cc-job-track" role="progressbar" aria-label="本批评估处理进度" :aria-valuenow="latestJob.processed || 0" :aria-valuemin="0" :aria-valuemax="Math.max(1, latestJob.total || 0)"><i :style="{ transform: `scaleX(${jobProgress})` }" /></div><p>评估 {{ latestJob.evaluated }} · 跳过 {{ latestJob.skipped }} · 失败 {{ latestJob.failed }}</p><small>进度代表单批处理；跳过可能因为证据不足。</small></div><div v-else class="cc-empty small">{{ sourceUpdated['评估进度'] ? '暂无后台评估任务' : '评估进度暂不可用' }}</div></section>
    </div>
    <footer class="cc-footer"><span>EAP / 运行态势 · 只读展示</span><span>金额不跨币种合计 · 规则评分不等同业务效果 · 页面隐藏时暂停刷新</span></footer>
  </main>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { LteInfoBox } from '@adminlte/vue'
import CommandMetric from '@/components/command-center/CommandMetric.vue'
import CommandPanel from '@/components/command-center/CommandPanel.vue'
import TopologyViewport from '@/components/command-center/TopologyViewport.vue'
import SourceStatus from '@/components/command-center/SourceStatus.vue'
import { getData } from '@/api/http'
import type { AgentOpsDashboard, DashboardNetwork, NetworkNode, PhysicalTelemetrySummary, EvaluationSummary, EvaluationJob, ManagementSummary, PlatformStatus } from '@/types/domain'
const screen = ref<HTMLElement>()
const ops = ref<AgentOpsDashboard>()
const network = ref<DashboardNetwork>()
const telemetry = ref<PhysicalTelemetrySummary>()
const evaluation = ref<EvaluationSummary>()
const management = ref<ManagementSummary>()
const platform = ref<PlatformStatus>()
const jobs = ref<EvaluationJob[]>([])
const selectedNode = ref<NetworkNode>()
const agentPage = ref(0)
const agentQuery = ref('')
const focusConnections = ref(false)
const autoRotate = ref(false)
const topologyFocused = ref(false)
const offPageConnections = computed(() => {
  if (!selectedNode.value) return 0
  const visible = new Set(graph.value.nodes.map(n => n.id))
  return (network.value?.edges || []).filter(e => (e.source === selectedNode.value?.id || e.target === selectedNode.value?.id) && (!visible.has(e.source) || !visible.has(e.target))).length
})
const agentPageCount = computed(() => Math.max(1, Math.ceil(matchingAgents().length / 10)))
const selectedAgentPath = computed(() => {
  const id = selectedNode.value?.id.match(/^agent:(\d+)$/)?.[1]
  return id ? `/agents/${id}` : undefined
})
const sourceUpdated = ref<Record<string, number>>({})
const documentHidden = ref(document.hidden)
const autoRefresh = ref(true)
const loading = ref(false)
const fullscreen = ref(false)
const fullscreenError = ref('')
const lastUpdated = ref('')
const failedSources = ref<string[]>([])
const now = ref(Date.now())
let disposed = false
let refreshTimer: ReturnType<typeof setInterval> | undefined
let rotationTimer: ReturnType<typeof setInterval> | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
const clockText = computed(() => new Date(now.value).toLocaleString('zh-CN', { hour12: false }))
const freshness = computed(() => ({ FRESH: '最近已同步', AGING: '待更新', STALE: '已过期', NO_DATA: '无同步记录' }[ops.value?.signalSyncFreshness || 'NO_DATA']))
const healthStates = computed(() => {
  const o = ops.value
  return [{ label: '健康', value: o?.healthyAgentCount || 0, color: '#47d6b0' }, { label: '降级', value: o?.degradedAgentCount || 0, color: '#edba67' }, { label: '离线', value: o?.offlineAgentCount || 0, color: '#ed8491' }, { label: '其他 / 未知', value: o ? Math.max(0, o.agentCount - o.healthyAgentCount - o.degradedAgentCount - o.offlineAgentCount) : 0, color: '#63809a' }]
})
const graph = computed(() => {
  const all = network.value?.nodes || []
  const controls = all.filter(n => n.plane === 'CONTROL').sort((a,b) => a.id.localeCompare(b.id, undefined, { numeric: true })).slice(0,4)
  const agents = matchingAgents().slice(agentPage.value * 10, (agentPage.value + 1) * 10)
  const nodes = [...controls.map((n,i) => ({ ...n, px: (i + .5) * 1000 / Math.max(1, controls.length), py: 65 })), ...agents.map((n,i) => ({ ...n, px: ((i % 5) + .5) * 1000 / Math.min(5, agents.length - Math.floor(i/5)*5), py: i < 5 ? 220 : 340 }))]
  const byId = new Map(nodes.map(n => [n.id,n]))
  const edges = (network.value?.edges || []).flatMap(edge => {
    const a = byId.get(edge.source), b = byId.get(edge.target)
    if (!a || !b || (focusConnections.value && selectedNode.value && edge.source !== selectedNode.value.id && edge.target !== selectedNode.value.id)) return []
    const sameRow = a.py === b.py
    const path = sameRow ? `M${a.px} ${a.py+31} C${a.px} ${a.py+110},${b.px} ${b.py+110},${b.px} ${b.py+31}` : `M${a.px} ${a.py+31} C${a.px} ${(a.py+b.py)/2},${b.px} ${(a.py+b.py)/2},${b.px} ${b.py-31}`
    return [{ ...edge, path }]
  })
  return { nodes, edges }
})
const topModels = computed(() => [...(telemetry.value?.modelUsage || [])].sort((a,b) => b.totalTokens - a.totalTokens).slice(0,4))
const maxModelTokens = computed(() => Math.max(1,...topModels.value.map(m => m.totalTokens)))
const latestJob = computed(() => jobs.value.find(j => ['RUNNING','QUEUED'].includes(j.status)) || jobs.value[0])
const jobLabel = computed(() => ({ QUEUED: '等待处理', RUNNING: '正在处理', SUCCEEDED: '本批完成', PARTIAL: '部分失败', FAILED: '处理中断' }[latestJob.value?.status || 'QUEUED']))
const jobProgress = computed(() => Math.min(1, (latestJob.value?.processed || 0) / Math.max(1, latestJob.value?.total || 0)))
async function refresh() {
  if (disposed || loading.value) return
  loading.value = true
  const options = { silentError: true }
  const requests = [getData<AgentOpsDashboard>('/agentops/dashboard?hours=24',options),
    getData<DashboardNetwork>('/dashboard/network',options), getData<PhysicalTelemetrySummary>('/telemetry/physical-summary?hours=24',options),
    getData<EvaluationSummary>('/evaluations/summary?hours=24',options), getData<ManagementSummary>('/management/summary?hours=24',options),
    getData<PlatformStatus>('/platform/status',options), getData<EvaluationJob[]>('/evaluations/jobs',options)] as const
  try {
    const result = await Promise.allSettled(requests)
    if (disposed) return
    const names = ['运行健康','拓扑','调用遥测','评估质量','业务价值','平台状态','评估进度']
    const receivedAt = Date.now()
    result.forEach((r,i) => { if (r.status === 'fulfilled') sourceUpdated.value[names[i]] = receivedAt })
    failedSources.value = result.flatMap((r,i) => r.status === 'rejected' ? [names[i]] : [])
    if (result[0].status === 'fulfilled') ops.value = result[0].value
    if (result[1].status === 'fulfilled') { network.value = result[1].value; reconcileTopology() }
    if (result[2].status === 'fulfilled') telemetry.value = result[2].value
    if (result[3].status === 'fulfilled') evaluation.value = result[3].value
    if (result[4].status === 'fulfilled') management.value = result[4].value
    if (result[5].status === 'fulfilled') platform.value = result[5].value
    if (result[6].status === 'fulfilled') jobs.value = result[6].value
    if (!failedSources.value.length) lastUpdated.value = new Date().toISOString()
  } finally { if (!disposed) loading.value = false }
}
function number(value?: number | null, digits = 0) { return value == null ? '—' : value.toLocaleString('zh-CN', { maximumFractionDigits: digits }) }
function compact(value?: number) { return value == null ? '—' : value >= 10000 ? `${(value/10000).toFixed(1)} 万` : number(value) }
function rate(value?: number, count?: number) { return value == null || !count ? '—' : `${(value*100).toFixed(1)}%` }
function time(value: string) { return new Date(value).toLocaleTimeString('zh-CN', {hour12:false}) }
function shorten(value: string, size: number) { return value.length > size ? `${value.slice(0,size)}…` : value }
function updateFullscreen() { fullscreen.value = document.fullscreenElement === screen.value }
async function toggleFullscreen() {
  fullscreenError.value = ''
  try { if (document.fullscreenElement) await document.exitFullscreen(); else await screen.value?.requestFullscreen() }
  catch { fullscreenError.value = '浏览器未允许全屏，可使用浏览器全屏快捷键或继续窗口展示。' }
}
function matchingAgents() {
  const query = agentQuery.value.trim().toLocaleLowerCase()
  return (network.value?.nodes || []).filter(n => n.plane === 'DATA' && (!query || `${n.label} ${n.id}`.toLocaleLowerCase().includes(query)))
    .sort((a,b) => a.id.localeCompare(b.id, undefined, { numeric: true }))
}
function reconcileTopology() {
  const agents = matchingAgents()
  const selectedIndex = agents.findIndex(n => n.id === selectedNode.value?.id)
  agentPage.value = selectedIndex >= 0 ? Math.floor(selectedIndex / 10) : Math.min(agentPage.value, Math.max(0, Math.ceil(agents.length / 10) - 1))
  selectedNode.value = network.value?.nodes.find(n => n.id === selectedNode.value?.id)
  if (!selectedNode.value) focusConnections.value = false
}
function setAgentQuery(query: string) {
  agentQuery.value = query.slice(0, 100)
  agentPage.value = 0
  selectedNode.value = undefined
  focusConnections.value = false
  autoRotate.value = false
}
function onTopologyBlur(event: FocusEvent) {
  topologyFocused.value = !!(event.relatedTarget && (event.currentTarget as HTMLElement).contains(event.relatedTarget as Node))
}
function advanceRotation() {
  if (disposed || document.hidden || !autoRefresh.value || !autoRotate.value || topologyFocused.value || selectedNode.value || agentQuery.value || agentPageCount.value < 2 || failedSources.value.includes('拓扑') || Date.now() - (sourceUpdated.value['拓扑'] || 0) >= 45000) return
  agentPage.value = (agentPage.value + 1) % agentPageCount.value
}
function changeAgentPage(delta: number) {
  autoRotate.value = false
  focusConnections.value = false
  agentPage.value = Math.max(0, Math.min(agentPageCount.value - 1, agentPage.value + delta))
  selectedNode.value = undefined
}
function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  if (autoRefresh.value) visibleRefresh()
}
function visibleRefresh() {
  documentHidden.value = document.hidden
  if (!document.hidden && autoRefresh.value) void refresh()
}
onMounted(() => {
  void refresh()
  refreshTimer = setInterval(visibleRefresh,15000)
  rotationTimer = setInterval(advanceRotation,20000)
  clockTimer = setInterval(() => { now.value = Date.now() },1000)
  document.addEventListener('visibilitychange',visibleRefresh)
  document.addEventListener('fullscreenchange',updateFullscreen)
})
onBeforeUnmount(() => { disposed = true; clearInterval(refreshTimer); clearInterval(clockTimer); clearInterval(rotationTimer); document.removeEventListener('visibilitychange',visibleRefresh); document.removeEventListener('fullscreenchange',updateFullscreen) })
</script>
<style scoped>
.command-center{--cc-bg:#102c49;--cc-surface:#1c4263;--cc-line:#416585;--cc-text:#f5faff;--cc-muted:#bdd2e7;--cc-accent:#79ddff;min-height:100vh;box-sizing:border-box;padding:28px 36px 18px;background:radial-gradient(ellipse at 48% 18%,#28628a 0%,#173e60 44%,var(--cc-bg) 85%);color:var(--cc-text);font-family:"PingFang SC","Microsoft YaHei",sans-serif;color-scheme:dark;overflow:auto}.command-center:fullscreen{width:100vw;height:100vh}.cc-header{display:flex;align-items:center;justify-content:space-between;gap:28px;padding-bottom:20px;border-bottom:1px solid var(--cc-line)}.cc-brand{display:flex;align-items:center;gap:16px}.cc-brand img{width:52px;height:52px}.cc-brand span{color:var(--cc-muted);font-size:12px;letter-spacing:1.5px}.cc-brand h1{font-size:28px;letter-spacing:3px;font-weight:600;margin:7px 0 0;white-space:nowrap}.cc-context{font-size:13px;display:flex;gap:10px;align-items:center;color:var(--cc-muted);white-space:nowrap}.cc-live-dot{width:7px;height:7px;border-radius:50%;background:#47d6b0;box-shadow:0 0 12px #47d6b055}.cc-live-dot.stale{background:#edba67}.cc-divider{color:#456075;margin:0 6px}.cc-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.cc-actions button,.cc-actions a{background:#214e72;border:1px solid #5786ab;border-radius:4px;padding:9px 12px;font-size:13px;color:#e1f2ff;cursor:pointer;min-height:38px;text-decoration:none;box-sizing:border-box}.cc-actions button:hover,.cc-actions a:hover{border-color:var(--cc-accent);color:#fff}.cc-actions button:disabled{opacity:.55;cursor:wait}.command-center :is(button,a):focus-visible{outline:2px solid #79ddff;outline-offset:4px}.cc-statusline{display:flex;justify-content:space-between;gap:20px;color:var(--cc-muted);font-size:12px;padding:14px 0}.warning{color:#edba67!important}.cc-error{padding:10px 14px;border:1px solid #8c6b32;background:#342d20;color:#f7dba5;margin-bottom:12px;font-size:14px}.cc-kpis{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));border:1px solid var(--cc-line);background:#1a4164f2;border-radius:7px;margin-bottom:20px}.cc-kpis article{padding:20px 26px;border-right:1px solid var(--cc-line)}.cc-kpis article:last-child{border-right:0}.cc-kpis article>span{font-size:14px;color:#dcecff}.cc-kpis strong{display:block;font-size:38px;font-weight:500;letter-spacing:1px;font-variant-numeric:tabular-nums;margin:10px 0}.cc-kpis small{font-size:13px;color:var(--cc-muted);margin-left:8px;letter-spacing:0}.cc-kpis p{font-size:12px;color:var(--cc-muted);margin:0;line-height:1.6}.cc-main-grid{display:grid;grid-template-columns:270px minmax(0,1fr) 310px;gap:20px;margin-bottom:20px}.cc-panel{min-width:0;background:linear-gradient(150deg,#214d70f2,#173958f5);border:1px solid var(--cc-line);border-radius:7px;padding:20px;overflow:hidden}.cc-panel-title{display:flex;justify-content:space-between;align-items:center;gap:14px;padding-bottom:14px;border-bottom:1px solid #3c6283}.cc-panel-title h2{margin:0;font-size:17px;font-weight:500;letter-spacing:1px;white-space:nowrap}.cc-panel-title>span,.cc-panel-title>a{font-size:12px;color:var(--cc-muted);text-decoration:none}.cc-panel-title>a{color:#7dd3eb}.health-total{padding:22px 0 16px;display:flex;align-items:baseline;gap:16px}.health-total strong{font-size:40px;font-weight:500;color:#79dcc5}.health-total small{font-size:22px}.health-total>span{font-size:12px;color:var(--cc-muted)}.health-track{display:flex;height:6px;gap:3px;background:#315472;border-radius:4px;overflow:hidden;margin-bottom:18px}.health-track i{height:100%}.health-row,.cc-submetric{display:flex;justify-content:space-between;align-items:center;margin:15px 0;font-size:14px;color:#d0e2f2}.health-row span{display:flex;align-items:center;gap:10px}.health-row i{width:7px;height:7px;border-radius:2px}.health-row strong,.cc-submetric strong{font-weight:500;font-size:18px;color:#e8f0f8;font-variant-numeric:tabular-nums}.cc-submetric{border-top:1px solid #3c6283;padding-top:14px}.cc-note{font-size:12px;color:var(--cc-muted);line-height:1.65;margin:15px 0 0}.cc-topology{padding-bottom:16px;display:flex;flex-direction:column}.topology-canvas{flex:1;display:grid;place-items:center;min-height:280px}.topology-canvas svg{width:100%;max-height:440px;overflow:visible}.cc-edge{fill:none;stroke:#719cbd;stroke-width:1.6;opacity:.85}.cc-edge.data{stroke:#53c6b0;stroke-dasharray:5 5}.cc-edge.observed{stroke:#90e4fa;stroke-dasharray:4 9;animation:cc-flow 12s linear infinite}.cc-edge.highlighted{stroke:#a0e5f8;stroke-width:2.5;opacity:1}.cc-node{cursor:pointer;outline:none}.cc-node rect{fill:#215679;stroke:#68a2c7;stroke-width:1.3;transition:fill .2s}.cc-node.control rect{fill:#24506f;stroke:#6696b9}.cc-node.selected rect,.cc-node:focus-visible rect{stroke:#a1ecff;stroke-width:2.5;fill:#286e93}.cc-node:hover rect{fill:#2d668a}.node-kind{font-size:13px;fill:#c2e0f3}.node-name{font-size:18px;fill:#f1faff}.topology-caption{border-top:1px solid #3c6283;padding-top:14px;display:flex;justify-content:space-between;gap:16px;color:var(--cc-muted);font-size:12px;line-height:1.6;flex-wrap:wrap}.topology-caption b{color:#d8eafa;font-weight:500}.legend-line{display:inline-block;width:20px;border-top:2px solid #527c98;margin:0 8px}.legend-line.data{border-top-style:dashed;border-color:#438783}.attention-summary{display:flex;justify-content:space-between;align-items:center;margin:20px 0;color:var(--cc-muted);font-size:14px}.attention-summary strong{font-size:28px;color:#edba67;font-weight:500}.attention-list{padding:0;list-style:none;margin:0}.attention-list li{display:flex;align-items:flex-start;gap:12px;border-top:1px solid #3c6283;padding:15px 0}.severity{background:#193148;color:#9ec6e7;padding:4px 6px;border-radius:3px;font-size:12px}.severity.p0{background:#452631;color:#ff9aa8}.severity.p1{background:#423323;color:#f3c478}.attention-list strong{font-weight:500;font-size:14px;line-height:1.6;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.attention-list p{font-size:12px;color:var(--cc-muted);margin:6px 0 0}.cc-empty{display:grid;place-items:center;text-align:center;min-height:160px;font-size:14px;color:var(--cc-muted)}.cc-empty.small{min-height:100px}.cc-bottom-grid{display:grid;grid-template-columns:1fr 1fr 1fr;gap:20px}.model-row{display:grid;grid-template-columns:minmax(0,140px) minmax(30px,1fr) 74px;align-items:center;gap:12px;margin-top:17px;font-size:13px}.model-row>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#d0e7f7}.model-row>div{height:6px;background:#315472;border-radius:2px;overflow:hidden}.model-row i,.cc-job-track i{display:block;width:100%;height:100%;background:linear-gradient(90deg,#339fd0,#8deaff);transform-origin:left;transition:transform .5s ease-out}.model-row strong{font-variant-numeric:tabular-nums;font-weight:400;text-align:right}.business-grid{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-top:20px}.business-grid span{font-size:12px;color:var(--cc-muted);display:block}.business-grid strong{display:block;font-size:23px;font-weight:500;margin-top:6px;font-variant-numeric:tabular-nums}.cc-job{padding-top:24px}.cc-job>div:first-child{display:flex;justify-content:space-between;font-size:14px;color:#dcefff}.cc-job strong{font-weight:500}.cc-job-track{height:6px;background:#315472;border-radius:4px;overflow:hidden;margin:18px 0}.cc-job p,.cc-job small{font-size:12px;color:var(--cc-muted);line-height:1.6}.cc-footer{display:flex;justify-content:space-between;padding-top:16px;font-size:11px;color:#b5cce1;gap:16px}@keyframes cc-flow{to{stroke-dashoffset:-104}}.motion-paused .cc-edge{animation-play-state:paused}@media(min-width:1800px){.command-center{padding:30px 40px 20px}.cc-main-grid{grid-template-columns:300px minmax(0,1fr) 340px}.cc-kpis strong{font-size:46px}.cc-panel{padding:24px}.cc-panel-title h2{font-size:20px}.topology-canvas{min-height:410px}.cc-kpis article>span,.health-row,.cc-submetric,.attention-list strong{font-size:16px}.cc-context{font-size:14px}.cc-brand h1{font-size:32px}}@media(max-width:1300px){.command-center{padding:20px}.cc-context{display:none}.cc-main-grid{grid-template-columns:220px minmax(0,1fr)}.cc-attention{grid-column:1/-1}.attention-list{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}.cc-kpis article{padding:18px}.cc-kpis strong{font-size:30px}}@media(max-width:800px){.command-center{padding:16px}.cc-header{flex-wrap:wrap;gap:16px}.cc-brand h1{font-size:21px;letter-spacing:1px}.cc-actions{width:100%}.cc-actions button,.cc-actions a{min-height:44px}.cc-statusline{flex-direction:column;gap:8px;font-size:12px;line-height:1.6}.cc-kpis{grid-template-columns:1fr 1fr}.cc-kpis article{border-bottom:1px solid var(--cc-line)}.cc-kpis article:last-child{grid-column:1/-1}.cc-main-grid,.cc-bottom-grid{grid-template-columns:1fr}.cc-topology{grid-row:1}.cc-health{grid-row:2}.cc-attention{grid-column:auto}.attention-list{grid-template-columns:1fr}.topology-canvas{overflow:auto;display:block;min-height:280px}.topology-canvas svg{min-width:650px}.cc-footer{flex-direction:column;line-height:1.6}.cc-brand img{width:42px;height:42px}.cc-panel-title h2{font-size:17px}}@media(prefers-reduced-motion:reduce){.cc-edge.observed{animation:none}.model-row i,.cc-job-track i,.cc-node rect{transition:none}}
@media(min-width:1400px) and (min-height:900px){.command-center{padding:22px 32px 12px}.cc-header{padding-bottom:14px}.cc-brand h1{font-size:28px}.cc-kpis{margin-bottom:16px}.cc-kpis article{padding:14px 24px}.cc-kpis strong{font-size:38px;margin:8px 0}.cc-main-grid{height:calc(100vh - 610px);min-height:390px;margin-bottom:16px;gap:16px}.cc-panel{padding:18px 20px}.cc-panel-title{padding-bottom:12px}.cc-panel-title h2{font-size:18px}.cc-health{overflow:auto}.health-total{padding:14px 0 10px}.health-total strong{font-size:34px}.health-row,.cc-submetric{margin:10px 0;font-size:14px}.cc-submetric{padding-top:10px}.health-track{margin-bottom:12px}.cc-note{margin-top:10px}.topology-canvas{min-height:0}.topology-canvas svg{max-height:330px}.node-name{font-size:20px}.cc-attention{display:flex;flex-direction:column}.attention-list{overflow-y:auto;flex:1;min-height:0}.attention-list li{padding:12px 0}.attention-list strong{font-size:14px}.attention-summary{margin:12px 0}.cc-bottom-grid{gap:16px}.business-grid{gap:10px;margin-top:14px}.business-grid strong{font-size:21px}.cc-job{padding-top:18px}.cc-footer{padding-top:12px}.model-row{margin-top:14px}}

/* Data status and navigation stay in the same visual rhythm as the metrics. */
.cc-period{display:flex;justify-content:space-between;gap:16px;margin:0 0 9px;color:var(--cc-muted);font-size:11px}
.kpi-link{display:flex;align-items:center;justify-content:space-between;gap:8px;color:#dcecff;font-size:14px;text-decoration:none}.kpi-link span{opacity:.6}.kpi-link:hover{color:#8ce0f7}.cc-kpis article>.source-status{margin-top:8px}.panel-freshness{margin-top:8px}
.topology-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:30px;color:var(--cc-muted);font-size:11px}.topology-pages{display:flex;align-items:center;gap:9px}.topology-pages button,.selected-node button{border:1px solid #5786ab;background:#214e72;color:#e1f2ff;border-radius:4px;cursor:pointer;min-width:30px;min-height:28px}.topology-pages button:disabled{opacity:.35;cursor:default}.selected-node{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.selected-node a,.attention-list a{color:inherit;text-decoration:none}.selected-node a{color:#7dd3eb}.attention-list a:hover{color:#8ce0f7}.attention-list a strong span{color:#7dd3eb}.mobile-graph-hint{display:none}
@media(min-width:1360px) and (min-height:740px){
.command-center{height:100vh;min-height:740px;display:flex;flex-direction:column;padding:18px 28px 12px}.cc-header{flex-shrink:0;padding-bottom:12px;gap:16px}.cc-brand h1{font-size:26px}.cc-brand img{width:44px;height:44px}.cc-context{font-size:12px}.cc-statusline{padding:10px 0;flex-shrink:0}.cc-kpis{flex-shrink:0;margin-bottom:14px}.cc-kpis article{padding:12px 20px}.cc-kpis strong{font-size:34px;margin:7px 0}.cc-main-grid{flex:1;min-height:0;height:auto;grid-template-columns:250px minmax(0,1fr) 300px;gap:14px;margin-bottom:14px}.cc-panel{padding:14px 16px}.cc-panel-title{padding-bottom:10px;gap:8px}.cc-panel-title h2{font-size:16px}.cc-health,.cc-attention{overflow:auto}.cc-topology{min-height:0}.topology-canvas{min-height:0;overflow:hidden}.topology-canvas svg{max-height:100%;height:100%;min-height:0}.cc-health .health-total{padding:12px 0 8px}.health-total strong{font-size:30px}.health-row,.cc-submetric{margin:8px 0;font-size:13px}.cc-submetric{padding-top:8px}.health-track{margin-bottom:10px}.cc-note{margin-top:8px;font-size:11px}.topology-caption{padding-top:9px;font-size:11px}.attention-summary{margin:10px 0}.attention-list li{padding:10px 0}.attention-list strong{font-size:13px}.cc-bottom-grid{flex-shrink:0;gap:14px}.cc-bottom-grid .cc-panel{min-height:174px}.business-grid{gap:8px;margin-top:9px}.business-grid strong{font-size:20px;margin-top:4px}.model-row{margin-top:10px;font-size:12px;grid-template-columns:minmax(0,130px) minmax(30px,1fr) 62px}.cc-job{padding-top:12px}.cc-job-track{margin:12px 0}.cc-job p{margin:8px 0}.cc-footer{padding-top:10px;flex-shrink:0}.cc-error{flex-shrink:0;font-size:12px;padding:8px 12px;margin-bottom:8px}
}
@media(min-width:1800px) and (min-height:1000px){.command-center{padding:24px 32px 14px}.cc-main-grid{grid-template-columns:290px minmax(0,1fr) 330px;gap:16px}.cc-brand h1{font-size:30px}.cc-kpis article{padding:16px 24px}.cc-kpis strong{font-size:42px}.cc-panel{padding:18px 20px}.cc-panel-title h2{font-size:18px}.cc-bottom-grid .cc-panel{min-height:190px}.health-row,.cc-submetric{margin:12px 0;font-size:14px}.health-total strong{font-size:36px}.attention-list strong{font-size:14px}.attention-list li{padding:14px 0}.cc-context{font-size:14px}.cc-note{font-size:12px}}
@media(max-width:800px){.cc-period{flex-direction:column;gap:5px;line-height:1.5}.mobile-graph-hint{display:block;color:#7dd3eb}.cc-panel-title{flex-wrap:wrap}.topology-pages button{min-width:40px;min-height:40px}.kpi-link{font-size:13px}.topology-caption{overflow-wrap:anywhere}.selected-node button{min-width:40px;min-height:40px}}

@media(min-width:1360px) and (min-height:740px) and (max-height:999px){
.cc-header{padding-bottom:8px}.cc-brand span{font-size:10px}.cc-brand h1{font-size:23px;margin-top:4px}.cc-brand img{width:38px;height:38px}.cc-statusline{padding:8px 0}.cc-period{margin-bottom:6px}.cc-kpis article{padding:10px 18px}.cc-kpis strong{font-size:30px;margin:4px 0}.cc-kpis article>.source-status{margin-top:4px}.cc-bottom-grid .cc-panel{padding:12px 14px;min-height:0}.cc-bottom-grid .cc-panel-title{padding-bottom:8px}.business-grid{gap:6px;margin-top:6px}.business-grid strong{font-size:18px;margin-top:2px}.cc-bottom-grid .cc-note{margin-top:6px}.cc-job{padding-top:8px}.cc-job-track{margin:10px 0}.cc-bottom-grid .panel-freshness{margin-top:5px}.cc-bottom-grid .cc-job p{margin:5px 0}.cc-footer{padding-top:8px}.cc-main-grid{grid-template-columns:230px minmax(0,1fr) 290px}.node-name{font-size:20px}
}

.topology-toolbar{flex-wrap:wrap;padding:6px 0;gap:8px;flex-shrink:0}.topology-search{display:flex;align-items:center;position:relative;flex:0 1 180px;min-width:125px}.topology-search input{box-sizing:border-box;width:100%;height:30px;padding:5px 26px 5px 9px;background:#173b5b;border:1px solid #5786ab;border-radius:4px;color:#d8eafa;font:inherit}.topology-search input::placeholder{color:#bdd2e7}.topology-search input:focus-visible{outline:2px solid #79ddff;outline-offset:2px}.topology-search input::-webkit-search-cancel-button{display:none}.topology-search button{position:absolute;right:3px;border:0;background:transparent;color:#bdd2e7;cursor:pointer;font-size:17px}.graph-count{margin-right:auto}.topology-pages{gap:6px}.topology-pages button{font:inherit;padding:4px 7px}.topology-pages button[aria-pressed="true"]{border-color:#79ddff;color:#9de5f8}.topology-pages button:disabled{cursor:default}.topology-canvas .cc-empty{width:100%;height:100%}
@media(max-width:800px){.topology-search{flex-basis:100%}.topology-search input{height:40px;font-size:14px}.topology-search button{width:36px;height:36px}.topology-pages{flex-wrap:wrap}.topology-toolbar{padding:10px 0}}

.topology-canvas:focus-visible{outline:2px solid #79ddff;outline-offset:-2px}.topology-canvas.zoomed{display:block;place-items:start;overflow:auto;scrollbar-color:#729fc0 #214d70;scrollbar-width:thin}.topology-canvas.zoomed svg{display:block;justify-self:start;min-width:1000px;width:100%;height:auto;max-height:none;min-height:330px}.topology-canvas.zoomed:focus-visible{outline-offset:-3px}

/* Brighter blue surfaces keep the command center legible at a distance. */
.cc-panel{box-shadow:0 6px 20px #071e3026}.cc-kpis article{box-shadow:inset 0 3px 0 #79ddff}.cc-kpis article:nth-child(2){box-shadow:inset 0 3px 0 #82b8ff}.cc-kpis article:nth-child(3){box-shadow:inset 0 3px 0 #69e1c8}.cc-kpis article:nth-child(4){box-shadow:inset 0 3px 0 #f4c473}.cc-kpis article:nth-child(5){box-shadow:inset 0 3px 0 #b7adff}.cc-kpis strong{color:#f5fbff}.cc-brand h1{color:#f5fbff}.cc-brand img{filter:drop-shadow(0 0 10px #79ddff30)}
/* Official AdminLTE widgets use a local skin to avoid global Bootstrap effects. */
.cc-kpis{gap:12px;border:0;background:transparent;border-radius:0}.cc-kpis> :last-child{grid-column:auto}.cc-kpis :deep(strong){font-size:34px;font-weight:500;font-variant-numeric:tabular-nums;display:block}.metric-detail{display:block}
.health-info{margin:14px 0;padding:14px;border-radius:6px;background:#286184;border:1px solid #5789a9}.health-info :deep(.info-box-text){display:block;font-size:12px;color:#d8edfc}.health-info :deep(.info-box-number){display:block;font-size:34px;font-weight:500;color:#8ff0d4;line-height:1.4}.health-info :deep(small){font-size:18px}.health-info-note{font-size:11px;color:#c9e2f3}
.topology-canvas{overflow:hidden;display:flex;min-width:0}.cc-kpis{align-items:stretch}
@media(max-width:800px){.cc-kpis{gap:10px}.cc-kpis> :last-child{grid-column:1/-1}.cc-kpis :deep(strong){font-size:28px}.topology-canvas{min-height:340px}}
@media(min-width:1800px) and (min-height:1000px){.cc-kpis :deep(strong){font-size:40px}}
</style>
