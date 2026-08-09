<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <span class="interface-eyebrow">PHYSICAL · OBSERVABILITY</span>
        <strong>智能体物理界面</strong>
        <span>统一查看端点、Agent、模型调用、Token 消耗、费用、延迟与运行状况</span>
      </div>
      <div class="button-row">
        <el-select v-model="windowHours" style="width: 150px" @change="load">
          <el-option label="最近 1 小时" :value="1" />
          <el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" />
          <el-option label="最近 30 天" :value="720" />
        </el-select>
        <el-button :icon="Search" :loading="scanLoading" @click="triggerDiscovery">自主发现</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="analysis-hero physical-hero">
      <div class="analysis-hero-copy">
        <span class="analysis-eyebrow">PHYSICAL PLANE / {{ windowHours }}H OBSERVATION</span>
        <h2>运行面总览</h2>
        <p>从端点、运行实例到模型调用建立一条物理观测链，实时定位哪里在运行、哪里变慢，以及 Token 和费用流向哪里。</p>
        <div class="analysis-tags">
          <span class="analysis-tag">ENDPOINTS</span>
          <span class="analysis-tag">RUNTIMES</span>
          <span class="analysis-tag">MODEL CALLS</span>
          <span class="analysis-tag analysis-tag-muted">无侵入遥测</span>
        </div>
      </div>
      <div class="physical-health-panel">
        <span class="analysis-eyebrow">RUNTIME HEALTH</span>
        <div class="physical-health-grid">
          <div><strong>{{ summary.onlineRuntimeCount }}</strong><span>ONLINE</span></div>
          <div><strong>{{ summary.degradedRuntimeCount }}</strong><span>DEGRADED</span></div>
          <div><strong>{{ summary.offlineRuntimeCount }}</strong><span>OFFLINE</span></div>
        </div>
        <div class="physical-health-foot"><span>端点覆盖</span><b>{{ summary.activeEndpoints }}/{{ summary.registeredEndpoints }}</b></div>
      </div>
    </div>

    <div class="physical-ops-strip">
      <div class="physical-ops-card">
        <div><span>端点覆盖率</span><strong>{{ endpointCoveragePercent }}%</strong></div>
        <el-progress :percentage="endpointCoveragePercent" :show-text="false" :stroke-width="6" color="#3167e8" />
        <small>{{ summary.activeEndpoints }} 个活跃 / {{ summary.registeredEndpoints }} 个注册</small>
      </div>
      <div class="physical-ops-card">
        <div><span>运行实例健康度</span><strong>{{ runtimeHealthPercent }}%</strong></div>
        <el-progress :percentage="runtimeHealthPercent" :show-text="false" :stroke-width="6" color="#16a085" />
        <small>{{ summary.onlineRuntimeCount }} 在线 · {{ summary.degradedRuntimeCount }} 降级 · {{ summary.offlineRuntimeCount }} 离线</small>
      </div>
      <div class="physical-ops-card physical-ops-card-compact"><span>观测吞吐</span><strong>{{ formatNumber(summary.totalCalls) }}</strong><small>model calls / {{ windowHours }}h</small></div>
      <div class="physical-ops-card physical-ops-card-compact"><span>延迟窗口</span><strong>{{ summary.averageLatencyMs }} ms</strong><small>P95 {{ summary.p95LatencyMs }} ms</small></div>
    </div>

    <el-alert
      v-if="!summary.lastEventAt"
      type="warning"
      :closable="false"
      show-icon
      title="尚未收到真实模型调用遥测。物理界面不会使用模拟 Token 数据，请通过遥测接口或模型网关上报。"
    />

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>模型调用接入验证</span>
          <span class="muted">先执行治理预检，通过后再写入真实遥测元数据</span>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent="recordInvocation">
        <div class="form-row">
          <el-form-item label="调用事件 Key" required>
            <el-input v-model="telemetryForm.eventKey" placeholder="例如 fin-analysis-call-001" />
          </el-form-item>
          <el-form-item label="关联 Agent" required>
            <el-select v-model="telemetryForm.agentId" style="width: 100%" placeholder="选择智能体">
              <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.name} (${agent.code})`" :value="agent.id" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="模型供应商" required>
            <el-input v-model="telemetryForm.provider" placeholder="openai / azure-openai / local" />
          </el-form-item>
          <el-form-item label="模型名称" required>
            <el-input v-model="telemetryForm.modelName" placeholder="例如 gpt-5 / qwen3" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="数据类别" required>
            <el-input v-model="telemetryForm.dataCategory" placeholder="例如 BUSINESS_ANALYSIS" />
          </el-form-item>
          <el-form-item label="敏感级别" required>
            <el-select v-model="telemetryForm.sensitivity" style="width: 100%">
              <el-option label="公开" value="PUBLIC" />
              <el-option label="内部" value="INTERNAL" />
              <el-option label="机密" value="CONFIDENTIAL" />
              <el-option label="秘密" value="SECRET" />
              <el-option label="严格限制" value="RESTRICTED" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row telemetry-number-row">
          <el-form-item label="输入 Token"><el-input-number v-model="telemetryForm.inputTokens" :min="0" :controls="false" /></el-form-item>
          <el-form-item label="输出 Token"><el-input-number v-model="telemetryForm.outputTokens" :min="0" :controls="false" /></el-form-item>
          <el-form-item label="延迟 ms"><el-input-number v-model="telemetryForm.latencyMs" :min="0" :controls="false" /></el-form-item>
          <el-form-item label="费用 USD"><el-input-number v-model="telemetryForm.moneyCost" :min="0" :precision="6" :controls="false" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="调用状态">
            <el-select v-model="telemetryForm.status" style="width: 100%">
              <el-option label="成功" value="SUCCEEDED" />
              <el-option label="失败" value="FAILED" />
              <el-option label="进行中" value="STARTED" />
              <el-option label="取消" value="CANCELLED" />
            </el-select>
          </el-form-item>
          <el-form-item label="DLP 检测类型">
            <el-input v-model="telemetryForm.detectedTypesText" placeholder="PHONE,ID_CARD；可留空" />
          </el-form-item>
        </div>
        <div class="governance-switch-row">
          <el-checkbox v-model="telemetryForm.internalData">包含内部信息</el-checkbox>
          <el-checkbox v-model="telemetryForm.companySecret">涉及公司保密信息</el-checkbox>
        </div>
        <div class="button-row governance-submit-row">
          <span class="muted">不提交提示词和模型输出正文；阻断或待脱敏时不会写入模型调用记录。</span>
          <el-button type="primary" :loading="telemetrySaving" @click="recordInvocation">预检并记录调用</el-button>
        </div>
      </el-form>
    </el-card>

    <div class="metric-strip">
      <div class="small-box box-primary">
        <div><strong>{{ formatNumber(summary.totalTokens) }}</strong><span>总 Token</span></div>
        <el-icon><Tickets /></el-icon>
      </div>
      <div class="small-box box-success">
        <div><strong>{{ summary.totalCalls }}</strong><span>模型调用</span></div>
        <el-icon><Connection /></el-icon>
      </div>
      <div class="small-box box-info">
        <div><strong>{{ successPercent }}%</strong><span>调用成功率</span></div>
        <el-icon><CircleCheck /></el-icon>
      </div>
      <div class="small-box box-warning">
        <div><strong>{{ formatMoney(summary.moneyCost) }}</strong><span>模型费用 USD</span></div>
        <el-icon><Money /></el-icon>
      </div>
    </div>

    <div class="physical-stat-grid">
      <div><span>输入 Token</span><strong>{{ formatNumber(summary.inputTokens) }}</strong></div>
      <div><span>输出 Token</span><strong>{{ formatNumber(summary.outputTokens) }}</strong></div>
      <div><span>缓存输入</span><strong>{{ formatNumber(summary.cachedInputTokens) }}</strong></div>
      <div><span>推理 Token</span><strong>{{ formatNumber(summary.reasoningTokens) }}</strong></div>
      <div><span>平均延迟</span><strong>{{ summary.averageLatencyMs }} ms</strong></div>
      <div><span>P95 延迟</span><strong>{{ summary.p95LatencyMs }} ms</strong></div>
      <div><span>活跃端点</span><strong>{{ summary.activeEndpoints }}/{{ summary.registeredEndpoints }}</strong></div>
      <div><span>运行实例</span><strong>{{ summary.onlineRuntimeCount }} 在线</strong></div>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>物理结构与端点分布</span>
          <span class="muted">端点 → Agent / Agent 项目 / MCP 配置 / Skills 与插件</span>
        </div>
      </template>
      <div class="physical-node-grid">
        <div v-for="node in physicalNodes" :key="node.key" class="physical-node-card">
          <div class="physical-node-head">
            <div>
              <strong>{{ node.hostName }}</strong>
              <span>{{ node.endpointKey || '本机扫描器' }}</span>
            </div>
            <el-tag :type="node.active ? 'success' : 'info'" effect="plain">{{ node.active ? 'ACTIVE' : 'OBSERVED' }}</el-tag>
          </div>
          <div class="physical-node-stats">
            <span>Agent <strong>{{ node.agentCount }}</strong></span>
            <span>MCP <strong>{{ node.mcpCount }}</strong></span>
            <span>扩展 <strong>{{ node.extensionCount }}</strong></span>
            <span>高风险 <strong>{{ node.highRiskCount }}</strong></span>
          </div>
          <small>最后发现：{{ node.lastSeenAt ? formatTime(node.lastSeenAt) : '尚无资产观察' }}</small>
        </div>
        <el-empty v-if="physicalNodes.length === 0" description="尚未发现端点或物理资产" />
      </div>
    </el-card>

    <div class="physical-two-column">
      <el-card shadow="never" class="panel">
        <template #header>Agent / 端点消耗排行</template>
        <el-table :data="summary.subjectUsage" size="small">
          <el-table-column prop="subjectName" label="Agent / 端点" min-width="170" />
          <el-table-column prop="calls" label="调用" width="75" />
          <el-table-column label="Token" width="120">
            <template #default="{ row }">{{ formatNumber(row.totalTokens) }}</template>
          </el-table-column>
          <el-table-column label="成功率" width="95">
            <template #default="{ row }">{{ Math.round(row.successRate * 100) }}%</template>
          </el-table-column>
          <el-table-column label="平均延迟" width="110">
            <template #default="{ row }">{{ row.averageLatencyMs }} ms</template>
          </el-table-column>
          <el-table-column label="费用" width="95">
            <template #default="{ row }">{{ formatMoney(row.moneyCost) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>模型消耗排行</template>
        <el-table :data="summary.modelUsage" size="small">
          <el-table-column prop="provider" label="供应商" width="120" />
          <el-table-column prop="modelName" label="模型" min-width="160" />
          <el-table-column prop="calls" label="调用" width="75" />
          <el-table-column label="Token" width="120">
            <template #default="{ row }">{{ formatNumber(row.totalTokens) }}</template>
          </el-table-column>
          <el-table-column label="费用" width="95">
            <template #default="{ row }">{{ formatMoney(row.moneyCost) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>最近模型调用</span>
          <span class="muted">只保存物理遥测元数据，不在此表保存提示词与模型输出</span>
        </div>
      </template>
      <el-table :data="events" v-loading="loading" row-key="id">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.observedAt) }}</template>
        </el-table-column>
        <el-table-column label="Agent / 端点" min-width="170">
          <template #default="{ row }">
            <strong>{{ row.agentId ? `Agent ${row.agentId}` : row.endpointKey }}</strong>
            <div class="muted">{{ row.runtimeInstanceKey || row.traceId || row.eventKey }}</div>
            <div v-if="row.governanceDecisionKey" class="muted">治理 {{ row.governanceDecisionKey }}</div>
            <div class="table-tag-row">
              <el-tag size="small" effect="plain">{{ row.observationSource }}</el-tag>
              <el-tag size="small" :type="row.governanceCovered ? 'success' : 'warning'">
                {{ row.governanceCovered ? '治理覆盖' : '被动发现' }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="模型" min-width="160">
          <template #default="{ row }">{{ row.provider }} · {{ row.modelName }}</template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Token" width="145">
          <template #default="{ row }">
            <strong>{{ formatNumber(row.totalTokens) }}</strong>
            <div class="muted">{{ formatNumber(row.inputTokens) }} / {{ formatNumber(row.outputTokens) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="延迟" width="100">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column label="工具 / MCP" width="115">
          <template #default="{ row }">{{ row.toolCallCount }} / {{ row.mcpServerCount }}</template>
        </el-table-column>
        <el-table-column label="费用" width="100">
          <template #default="{ row }">{{ formatMoney(row.moneyCost) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>深度内容观测</span>
          <span class="muted">输入 / 输出正文仅在 REDACTED 模式保存；HASH_ONLY 只保留哈希、长度和 Token 估算</span>
        </div>
      </template>
      <el-table :data="contentObservations" v-loading="loading" row-key="id">
        <el-table-column label="方向" width="100">
          <template #default="{ row }">
            <el-tag :type="row.direction === 'INPUT' ? 'warning' : 'success'">{{ row.direction }}</el-tag>
            <div class="muted">{{ row.captureMode }}</div>
          </template>
        </el-table-column>
        <el-table-column label="Trace / 事件" min-width="230">
          <template #default="{ row }">
            <strong>{{ row.traceId || '无 trace' }}</strong>
            <div class="muted">{{ row.eventKey || row.observationKey }}</div>
          </template>
        </el-table-column>
        <el-table-column label="模型" min-width="150">
          <template #default="{ row }">{{ row.provider || '-' }} · {{ row.modelName || '-' }}</template>
        </el-table-column>
        <el-table-column label="正文观测" min-width="420">
          <template #default="{ row }">
            <pre v-if="row.contentJson" class="content-observation-preview">{{ contentPreview(row.contentJson) }}</pre>
            <div v-else class="muted">仅保留 Hash {{ row.contentHash }} · {{ row.contentBytes }} bytes · 约 {{ row.estimatedTokens }} tokens</div>
          </template>
        </el-table-column>
        <el-table-column label="治理" width="150">
          <template #default="{ row }">
            <el-tag v-if="row.governanceAction" effect="plain">{{ row.governanceAction }}</el-tag>
            <div class="muted">{{ row.sensitivity || '未标注' }}<span v-if="row.redactionApplied"> · 已脱敏</span></div>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="175">
          <template #default="{ row }">{{ formatTime(row.observedAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>Agent 深度 Trace</span>
          <span class="muted">无侵入识别模型、工具、MCP、RAG、A2A 与 Agent Span</span>
        </div>
      </template>
      <el-table :data="traceSpans" v-loading="loading" row-key="id">
        <el-table-column label="类型" width="100">
          <template #default="{ row }"><el-tag size="small" :type="traceTagType(row.spanKind)">{{ row.spanKind }}</el-tag></template>
        </el-table-column>
        <el-table-column label="Span" min-width="230">
          <template #default="{ row }"><strong>{{ row.spanName }}</strong><div class="muted">{{ row.traceId }} · {{ row.spanId }}</div></template>
        </el-table-column>
        <el-table-column label="工具 / MCP / RAG" min-width="220">
          <template #default="{ row }">{{ row.toolName || '-' }} · {{ row.mcpServerName || '-' }} · {{ row.ragSource || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态 / 延迟" width="145">
          <template #default="{ row }">{{ row.status || '-' }} · {{ row.durationMs }} ms</template>
        </el-table-column>
        <el-table-column label="端点" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.endpointKey }}</template>
        </el-table-column>
        <el-table-column label="时间" width="175"><template #default="{ row }">{{ formatTime(row.observedAt) }}</template></el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CircleCheck, Connection, Money, Refresh, Search, Tickets } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { EapApi } from '@/api/eap'
import type {
  Agent,
  ContentObservation,
  DiscoveredAgentAsset,
  DiscoveryEndpoint,
  GovernanceDataSensitivity,
  ModelInvocationEvent,
  ModelInvocationStatus,
  PhysicalTelemetrySummary,
  TraceSpanObservation,
  TraceSpanKind,
} from '@/types/domain'

const emptySummary: PhysicalTelemetrySummary = {
  windowHours: 24,
  totalCalls: 0,
  succeededCalls: 0,
  failedCalls: 0,
  successRate: 0,
  inputTokens: 0,
  outputTokens: 0,
  cachedInputTokens: 0,
  reasoningTokens: 0,
  totalTokens: 0,
  moneyCost: 0,
  averageLatencyMs: 0,
  p95LatencyMs: 0,
  registeredEndpoints: 0,
  activeEndpoints: 0,
  onlineRuntimeCount: 0,
  degradedRuntimeCount: 0,
  offlineRuntimeCount: 0,
  subjectUsage: [],
  modelUsage: [],
}

const windowHours = ref(24)
const loading = ref(false)
const scanLoading = ref(false)
const telemetrySaving = ref(false)
const summary = ref<PhysicalTelemetrySummary>({ ...emptySummary })
const events = ref<ModelInvocationEvent[]>([])
const contentObservations = ref<ContentObservation[]>([])
const traceSpans = ref<TraceSpanObservation[]>([])
const endpoints = ref<DiscoveryEndpoint[]>([])
const assets = ref<DiscoveredAgentAsset[]>([])
const agents = ref<Agent[]>([])
const telemetryForm = reactive({
  eventKey: '',
  agentId: undefined as number | undefined,
  provider: 'openai-compatible',
  modelName: 'demo-model',
  dataCategory: 'BUSINESS_ANALYSIS',
  sensitivity: 'INTERNAL' as GovernanceDataSensitivity,
  internalData: true,
  companySecret: false,
  detectedTypesText: '',
  status: 'SUCCEEDED' as ModelInvocationStatus,
  inputTokens: 0,
  outputTokens: 0,
  latencyMs: 0,
  moneyCost: 0,
})

const successPercent = computed(() => Math.round(summary.value.successRate * 100))
const endpointCoveragePercent = computed(() => summary.value.registeredEndpoints > 0
  ? Math.min(100, Math.round(summary.value.activeEndpoints / summary.value.registeredEndpoints * 100))
  : 0)
const runtimeHealthPercent = computed(() => {
  const total = summary.value.onlineRuntimeCount + summary.value.degradedRuntimeCount + summary.value.offlineRuntimeCount
  return total > 0 ? Math.min(100, Math.round(summary.value.onlineRuntimeCount / total * 100)) : 0
})
const physicalNodes = computed(() => {
  const nodes = new Map<string, {
    key: string
    hostName: string
    endpointKey?: string
    active: boolean
    agentCount: number
    mcpCount: number
    extensionCount: number
    highRiskCount: number
    lastSeenAt?: string
  }>()
  endpoints.value.forEach((endpoint) => nodes.set(endpoint.hostName, {
    key: endpoint.endpointKey,
    hostName: endpoint.hostName,
    endpointKey: endpoint.endpointKey,
    active: endpoint.status === 'ACTIVE',
    agentCount: 0,
    mcpCount: 0,
    extensionCount: 0,
    highRiskCount: 0,
    lastSeenAt: endpoint.lastSeenAt,
  }))
  assets.value.forEach((asset) => {
    const host = asset.hostName || '未知设备'
    const node = nodes.get(host) || {
      key: `host:${host}`,
      hostName: host,
      active: false,
      agentCount: 0,
      mcpCount: 0,
      extensionCount: 0,
      highRiskCount: 0,
      lastSeenAt: undefined,
    }
    if (asset.assetType === 'MCP_CONFIGURATION') node.mcpCount++
    else if (asset.assetType === 'AGENT_SKILL' || asset.assetType === 'AGENT_PLUGIN') node.extensionCount++
    else node.agentCount++
    if (asset.riskLevel === 'HIGH' || asset.riskLevel === 'CRITICAL') node.highRiskCount++
    if (!node.lastSeenAt || new Date(asset.lastSeenAt) > new Date(node.lastSeenAt)) node.lastSeenAt = asset.lastSeenAt
    nodes.set(host, node)
  })
  return Array.from(nodes.values())
})

async function load() {
  loading.value = true
  try {
    const [nextSummary, nextEvents, nextEndpoints, nextAssets, nextAgents, nextContent, nextTraceSpans] = await Promise.all([
      EapApi.getPhysicalTelemetrySummary(windowHours.value),
      EapApi.listModelInvocationEvents(100),
      EapApi.listDiscoveryEndpoints(),
      EapApi.listDiscoveredAgentAssets(),
      EapApi.listAgents(),
      EapApi.listContentObservations(100),
      EapApi.listTraceSpanObservations(100),
    ])
    summary.value = nextSummary
    events.value = nextEvents
    endpoints.value = nextEndpoints
    assets.value = nextAssets
    agents.value = nextAgents
    contentObservations.value = nextContent
    traceSpans.value = nextTraceSpans
    if (!telemetryForm.agentId) telemetryForm.agentId = nextAgents[0]?.id
  } finally {
    loading.value = false
  }
}

function traceTagType(kind: TraceSpanKind) {
  if (kind === 'MODEL') return 'primary'
  if (kind === 'MCP' || kind === 'TOOL') return 'warning'
  if (kind === 'RAG') return 'success'
  return 'info'
}

async function triggerDiscovery() {
  scanLoading.value = true
  try {
    const run = await EapApi.triggerAgentDiscoveryScan()
    ElMessage.success(`自主发现完成：观察 ${run.observationsCount} 条，识别 ${run.discoveredCount} 个资产`)
    await load()
  } finally {
    scanLoading.value = false
  }
}

async function recordInvocation() {
  if (!telemetryForm.eventKey.trim() || !telemetryForm.agentId || !telemetryForm.provider.trim() || !telemetryForm.modelName.trim()) {
    ElMessage.warning('请填写事件 Key、Agent、模型供应商和模型名称')
    return
  }
  telemetrySaving.value = true
  try {
    const eventKey = telemetryForm.eventKey.trim()
    const decision = await EapApi.evaluateGovernancePreflight({
      decisionKey: `physical-${eventKey}-preflight`,
      eventKey: `physical-${eventKey}-governance`,
      agentId: telemetryForm.agentId,
      direction: 'INPUT_TO_MODEL',
      dataCategory: telemetryForm.dataCategory.trim() || 'MODEL_INPUT',
      sensitivity: telemetryForm.sensitivity,
      internalData: telemetryForm.internalData,
      companySecret: telemetryForm.companySecret,
      tokenCount: telemetryForm.inputTokens,
      payloadBytes: 0,
      classifier: 'physical-console-manual',
      provider: telemetryForm.provider.trim(),
      modelName: telemetryForm.modelName.trim(),
      detectedTypes: telemetryForm.detectedTypesText.split(',').map(value => value.trim()).filter(Boolean),
    })
    if (!decision.requestAllowed) {
      ElMessage.error(`治理预检返回 ${decision.gatewayDirective}，模型调用遥测未写入`)
      return
    }
    await EapApi.recordModelInvocation({
      eventKey,
      traceId: `trace-${eventKey}`,
      agentId: telemetryForm.agentId,
      governanceDecisionKey: decision.decisionKey,
      provider: telemetryForm.provider.trim(),
      modelName: telemetryForm.modelName.trim(),
      status: telemetryForm.status,
      inputTokens: telemetryForm.inputTokens,
      outputTokens: telemetryForm.outputTokens,
      cachedInputTokens: 0,
      reasoningTokens: 0,
      latencyMs: telemetryForm.latencyMs,
      moneyCost: telemetryForm.moneyCost,
      currency: 'USD',
      toolCallCount: 0,
      mcpServerCount: 0,
    })
    ElMessage.success(`模型调用已通过治理预检并记录：${decision.gatewayDirective}`)
    Object.assign(telemetryForm, {
      eventKey: '',
      detectedTypesText: '',
      inputTokens: 0,
      outputTokens: 0,
      latencyMs: 0,
      moneyCost: 0,
      companySecret: false,
    })
    await load()
  } finally {
    telemetrySaving.value = false
  }
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('zh-CN', { notation: value >= 1_000_000 ? 'compact' : 'standard' }).format(value)
}

function formatMoney(value: number) {
  return Number(value || 0).toFixed(4)
}

function contentPreview(value: string) {
  return value.length > 900 ? `${value.slice(0, 900)}…` : value
}

function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

function statusType(status: ModelInvocationStatus) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'STARTED') return 'warning'
  return 'info'
}

onMounted(load)
</script>
