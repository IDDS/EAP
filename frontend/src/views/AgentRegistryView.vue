<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>自主智能体发现</strong>
        <span>持续扫描受控目录和本机进程，发现未登记智能体、MCP 配置与高风险运行方式</span>
      </div>
      <el-button type="primary" :icon="Refresh" :loading="scanLoading" @click="triggerAutonomousScan">立即扫描</el-button>
    </div>

    <el-alert
      :type="autonomousSummary?.autonomousDiscoveryEnabled ? 'success' : 'warning'"
      :closable="false"
      show-icon
      :title="autonomousSummary?.autonomousDiscoveryEnabled ? '自主发现已启用：后台将按配置周期持续扫描' : '自主发现未启用：当前只能手动触发扫描'"
    />

    <div v-if="drilldownLabel" class="drilldown-context">
        <span class="drilldown-context-kicker">筛选条件</span>
      <strong>{{ drilldownLabel }}</strong>
      <span>发现端点列表已按上下文收敛</span>
      <el-button link type="primary" @click="clearDrilldown">清除筛选</el-button>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>协议级智能体 / MCP 发现</span>
          <span class="muted">读取 A2A Agent Card、MCP Registry 或 server.json 元数据，不执行远程命令</span>
        </div>
      </template>
      <div class="protocol-probe-row">
        <el-input v-model="protocolForm.targetUrl" placeholder="https://agent.example.com 或 MCP Registry 地址" />
        <el-select v-model="protocolForm.discoveryType" class="protocol-type-select">
          <el-option label="自动探测" value="AUTO" />
          <el-option label="A2A Agent Card" value="A2A" />
          <el-option label="MCP Registry" value="MCP_REGISTRY" />
          <el-option label="MCP server.json" value="MCP_SERVER_JSON" />
        </el-select>
        <el-button type="primary" :loading="protocolLoading" @click="probeProtocol">探测并登记</el-button>
      </div>
      <el-alert
        v-if="protocolResult"
        class="protocol-result"
        type="success"
        :closable="false"
        :title="`已登记 ${protocolResult.endpointsRegistered} 个端点，读取 ${protocolResult.documentsFetched} 份文档，发现 A2A ${protocolResult.a2aAgentsDiscovered} 个、MCP ${protocolResult.mcpServersDiscovered} 个`"
      />
      <div v-if="protocolResult?.warnings?.length" class="protocol-warnings">
        <span>部分探测未命中：</span>
        <el-tag v-for="warning in protocolResult.warnings.slice(0, 3)" :key="warning" type="warning" size="small">{{ warning }}</el-tag>
      </div>
    </el-card>

    <div class="discovery-overview">
      <div class="summary-metric">
        <span>智能体候选</span>
        <strong>{{ autonomousSummary?.agentCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric mcp">
        <span>MCP 配置</span>
        <strong>{{ autonomousSummary?.mcpConfigurationCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric extension">
        <span>技能 / 插件</span>
        <strong>{{ autonomousSummary?.skillCount ?? 0 }} / {{ autonomousSummary?.pluginCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric risk">
        <span>高风险资产</span>
        <strong>{{ autonomousSummary?.highRiskCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric managed">
        <span>已纳管</span>
        <strong>{{ autonomousSummary?.managedCount ?? 0 }}</strong>
      </div>
      <div class="summary-metric last-run">
        <span>最近扫描</span>
        <strong>{{ autonomousSummary?.lastRun ? formatTime(autonomousSummary.lastRun.startedAt) : '尚未执行' }}</strong>
        <small v-if="autonomousSummary?.lastRun">
          {{ autonomousSummary.lastRun.triggerType }} · {{ autonomousSummary.lastRun.status }} ·
          {{ autonomousSummary.lastRun.observationsCount }} 条证据
        </small>
      </div>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>已接入发现端点</span>
          <span class="muted">端点采集器注册后，定期向中央 EAP 上报观察结果</span>
        </div>
      </template>
      <el-table :data="pagedDiscoveryEndpoints" v-loading="endpointLoading" row-key="id">
        <el-table-column prop="hostName" label="主机" min-width="180" />
        <el-table-column prop="endpointKey" label="端点标识" min-width="220" show-overflow-tooltip />
        <el-table-column prop="accountName" label="账户" width="140" />
        <el-table-column prop="operatingSystem" label="操作系统" width="140" />
        <el-table-column prop="collectorVersion" label="采集器版本" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag type="success" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后上报" width="180">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="visibleDiscoveryEndpoints.length" class="pagination-row">
        <el-pagination
          v-model:current-page="endpointPage"
          v-model:page-size="endpointPageSize"
          :page-sizes="pageSizes"
          :total="visibleDiscoveryEndpoints.length"
          layout="total, sizes, prev, pager, next, jumper"
          background
          small
        />
      </div>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>跨来源身份关联</span>
          <span class="muted">把本地、协议、Kubernetes 与 eBPF 证据归并到稳定身份键</span>
        </div>
      </template>
      <el-table :data="pagedIdentityLinks" v-loading="endpointLoading" row-key="id">
        <el-table-column prop="discoveredAgentId" label="候选资产" width="100" />
        <el-table-column label="身份键" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">{{ row.identityKey }}</template>
        </el-table-column>
        <el-table-column label="关联方式" width="110">
          <template #default="{ row }">
            <el-tag :type="row.relationType === 'EXACT' ? 'success' : 'warning'" size="small">{{ row.relationType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="来源" min-width="190">
          <template #default="{ row }">
            <el-tag v-for="source in row.sourceTypes" :key="source" size="small" effect="plain">{{ source }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="120">
          <template #default="{ row }"><el-progress :percentage="row.confidence" :stroke-width="8" /></template>
        </el-table-column>
        <el-table-column label="最后观测" width="170">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
      </el-table>
      <div v-if="identityLinks.length" class="pagination-row">
        <el-pagination
          v-model:current-page="identityPage"
          v-model:page-size="identityPageSize"
          :page-sizes="pageSizes"
          :total="identityLinks.length"
          layout="total, sizes, prev, pager, next, jumper"
          background
          small
        />
      </div>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>自动发现资产清单</span>
          <span class="muted">共 {{ discoveredAssets.length }} 条；候选资产需完成复核与认领</span>
        </div>
      </template>
      <el-table :data="pagedDiscoveredAssets" v-loading="autonomousLoading" row-key="id">
        <el-table-column label="智能体 / 产品" min-width="210">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <div class="muted">
              <el-tag size="small" effect="plain">{{ assetTypeLabel(row.assetType) }}</el-tag>
              {{ row.vendor }} · {{ row.product }}<span v-if="row.version"> · {{ row.version }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="设备与账户" min-width="170">
          <template #default="{ row }">
            <div>{{ row.hostName || '未知设备' }}</div>
            <div class="muted">{{ row.accountName || '未关联账户' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="发现证据" min-width="170">
          <template #default="{ row }">
            <div class="source-tags">
              <el-tag v-for="source in row.sourceTypes" :key="source" size="small" effect="plain">{{ source }}</el-tag>
            </div>
            <div class="muted">{{ row.evidenceCount }} 条 · MCP {{ row.mcpServerCount }}</div>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="130">
          <template #default="{ row }">
            <el-progress :percentage="row.confidence" :stroke-width="8" />
          </template>
        </el-table-column>
        <el-table-column label="风险" width="105">
          <template #default="{ row }">
            <el-tag :type="riskTagType(row.riskLevel)">{{ row.riskLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="运行特征" min-width="150">
          <template #default="{ row }">
            <div>{{ row.processName || '未发现运行进程' }}</div>
            <div class="muted">
              自动批准 {{ row.autoApprove ? '是' : '否' }} ·
              进程信任 {{ row.trustedProcess === undefined || row.trustedProcess === null ? '未知' : row.trustedProcess ? '是' : '否' }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最后发现" width="170">
          <template #default="{ row }">{{ formatTime(row.lastSeenAt) }}</template>
        </el-table-column>
        <el-table-column label="处置状态" width="150" fixed="right">
          <template #default="{ row }">
            <el-select :model-value="row.status" size="small" @change="handleAssetStatusChange(row.id, $event)">
              <el-option label="新发现" value="DISCOVERED" />
              <el-option label="复核中" value="REVIEWING" />
              <el-option label="已确认" value="CONFIRMED" />
              <el-option label="已纳管" value="MANAGED" />
              <el-option label="忽略" value="IGNORED" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="discoveredAssets.length" class="pagination-row">
        <el-pagination
          v-model:current-page="assetPage"
          v-model:page-size="assetPageSize"
          :page-sizes="pageSizes"
          :total="discoveredAssets.length"
          layout="total, sizes, prev, pager, next, jumper"
          background
          small
        />
      </div>
    </el-card>

    <div class="toolbar registry-toolbar">
      <div class="toolbar-title">
        <strong>已注册智能体运行时发现</strong>
        <span>管理已登记智能体的能力、运行实例和心跳，并按任务能力选择运行节点</span>
      </div>
      <el-select v-model="selectedAgentId" class="agent-select" @change="loadAgentRegistry">
        <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.name} · ${agent.code}`" :value="agent.id" />
      </el-select>
    </div>

    <div class="registry-layout runtime-discovery-layout">
      <div class="registry-column">
        <el-card shadow="never" class="panel">
          <template #header>能力注册</template>
          <el-form label-position="top">
            <el-form-item label="能力标识">
              <el-input v-model="capabilityForm.capability" placeholder="ops.incident.triage" />
            </el-form-item>
            <el-form-item label="能力名称">
              <el-input v-model="capabilityForm.name" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="capabilityForm.description" type="textarea" :rows="2" />
            </el-form-item>
            <el-button type="primary" :icon="Plus" @click="registerCapability">注册能力</el-button>
          </el-form>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>运行实例</template>
          <el-form label-position="top">
            <el-form-item label="实例 Key">
              <el-input v-model="runtimeForm.instanceKey" placeholder="ops-incident-prod-1" />
            </el-form-item>
            <el-form-item label="Endpoint">
              <el-input v-model="runtimeForm.endpoint" placeholder="http://agent-service:9000" />
            </el-form-item>
            <div class="form-row">
              <el-form-item label="协议">
                <el-select v-model="runtimeForm.protocol">
                  <el-option label="HTTP" value="HTTP" />
                  <el-option label="MCP" value="MCP" />
                  <el-option label="A2A" value="A2A" />
                </el-select>
              </el-form-item>
              <el-form-item label="负载">
                <el-input-number v-model="heartbeatLoad" :min="0" :max="100" />
              </el-form-item>
            </div>
            <div class="button-row">
              <el-button type="primary" :icon="Connection" @click="registerRuntime">注册实例</el-button>
              <el-button :icon="Refresh" @click="heartbeat">发送心跳</el-button>
            </div>
          </el-form>
        </el-card>
      </div>

      <div class="registry-column">
        <el-card shadow="never" class="panel">
          <template #header>能力发现搜索</template>
          <el-form label-position="top">
            <div class="form-row">
              <el-form-item label="能力">
                <el-input v-model="discoveryForm.capability" placeholder="ops.incident.triage" />
              </el-form-item>
              <el-form-item label="最大风险">
                <el-select v-model="discoveryForm.maxRiskLevel">
                  <el-option label="LOW" value="LOW" />
                  <el-option label="MEDIUM" value="MEDIUM" />
                  <el-option label="HIGH" value="HIGH" />
                  <el-option label="CRITICAL" value="CRITICAL" />
                </el-select>
              </el-form-item>
            </div>
            <div class="form-row">
              <el-form-item label="部门">
                <el-input v-model="discoveryForm.department" placeholder="可选" />
              </el-form-item>
              <el-form-item label="协议">
                <el-select v-model="discoveryForm.protocol" clearable>
                  <el-option label="HTTP" value="HTTP" />
                  <el-option label="MCP" value="MCP" />
                  <el-option label="A2A" value="A2A" />
                </el-select>
              </el-form-item>
            </div>
            <el-button type="primary" :icon="Search" @click="discover">发现智能体</el-button>
          </el-form>
        </el-card>

        <el-card shadow="never" class="panel">
          <template #header>候选智能体</template>
          <el-table :data="candidates" v-loading="loading">
            <el-table-column prop="name" label="智能体" min-width="170">
              <template #default="{ row }">
                <strong>{{ row.name }}</strong>
                <div class="muted">{{ row.code }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="capability" label="能力" min-width="170" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag type="success">{{ row.runtimeStatus }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="loadScore" label="负载" width="90" />
            <el-table-column prop="score" label="评分" width="90" />
            <el-table-column prop="endpoint" label="Endpoint" min-width="220" show-overflow-tooltip />
          </el-table>
        </el-card>
      </div>
    </div>

    <div class="registry-layout registry-data-layout">
      <el-card shadow="never" class="panel">
        <template #header>已注册能力</template>
        <el-table :data="capabilities">
          <el-table-column prop="capability" label="能力标识" min-width="180" />
          <el-table-column prop="name" label="名称" width="160" />
          <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
        </el-table>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>运行实例</template>
        <el-table :data="runtimeInstances">
          <el-table-column prop="instanceKey" label="实例" min-width="180" />
          <el-table-column prop="protocol" label="协议" width="90" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ONLINE' ? 'success' : 'warning'">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="loadScore" label="负载" width="90" />
          <el-table-column prop="endpoint" label="Endpoint" min-width="220" show-overflow-tooltip />
        </el-table>
      </el-card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import { useRoute, useRouter } from 'vue-router'
import type {
  Agent,
  AgentCapability,
  AgentDiscoverySummary,
  AgentRuntimeInstance,
  DiscoveredAgentAsset,
  DiscoveryEndpoint,
  DiscoveryIdentityLink,
  DiscoveryAssetStatus,
  DiscoveryCandidate,
  RiskLevel,
  ProtocolDiscoveryType,
  ProtocolProbeResponse,
} from '@/types/domain'

const agents = ref<Agent[]>([])
const selectedAgentId = ref<number>()
const capabilities = ref<AgentCapability[]>([])
const runtimeInstances = ref<AgentRuntimeInstance[]>([])
const candidates = ref<DiscoveryCandidate[]>([])
const loading = ref(false)
const autonomousLoading = ref(false)
const scanLoading = ref(false)
const autonomousSummary = ref<AgentDiscoverySummary>()
const discoveredAssets = ref<DiscoveredAgentAsset[]>([])
const discoveryEndpoints = ref<DiscoveryEndpoint[]>([])
const identityLinks = ref<DiscoveryIdentityLink[]>([])
const endpointLoading = ref(false)
const protocolLoading = ref(false)
const protocolResult = ref<ProtocolProbeResponse>()
const heartbeatLoad = ref(22)
const pageSizes = [10, 20, 50, 100]
const endpointPage = ref(1)
const endpointPageSize = ref(20)
const identityPage = ref(1)
const identityPageSize = ref(20)
const assetPage = ref(1)
const assetPageSize = ref(20)

const capabilityForm = reactive({
  capability: 'ops.incident.triage',
  name: '运维事件分诊',
  description: '识别告警影响面并生成处置建议',
})

const runtimeForm = reactive({
  instanceKey: 'ops-incident-local-2',
  endpoint: 'http://ops-agent.local:9003',
  protocol: 'HTTP',
})

const discoveryForm = reactive({
  capability: 'finance.analysis',
  maxRiskLevel: 'HIGH' as RiskLevel,
  department: '',
  protocol: 'HTTP',
})

const protocolForm = reactive({
  targetUrl: '',
  discoveryType: 'AUTO' as ProtocolDiscoveryType,
})
const route = useRoute()
const router = useRouter()
const contextEndpointKey = computed(() => String(route.query.endpointKey || '').trim())
const drilldownLabel = computed(() => contextEndpointKey.value ? `端点 ${contextEndpointKey.value}` : '')
const visibleDiscoveryEndpoints = computed(() => contextEndpointKey.value
  ? discoveryEndpoints.value.filter(endpoint => endpoint.endpointKey === contextEndpointKey.value)
  : discoveryEndpoints.value)
const pagedDiscoveryEndpoints = computed(() => pageItems(visibleDiscoveryEndpoints.value, endpointPage.value, endpointPageSize.value))
const pagedIdentityLinks = computed(() => pageItems(identityLinks.value, identityPage.value, identityPageSize.value))
const pagedDiscoveredAssets = computed(() => pageItems(discoveredAssets.value, assetPage.value, assetPageSize.value))

watch(contextEndpointKey, () => {
  endpointPage.value = 1
})

watch([visibleDiscoveryEndpoints, endpointPageSize], () => {
  endpointPage.value = clampPage(endpointPage.value, visibleDiscoveryEndpoints.value.length, endpointPageSize.value)
})

watch([identityLinks, identityPageSize], () => {
  identityPage.value = clampPage(identityPage.value, identityLinks.value.length, identityPageSize.value)
})

watch([discoveredAssets, assetPageSize], () => {
  assetPage.value = clampPage(assetPage.value, discoveredAssets.value.length, assetPageSize.value)
})

function clearDrilldown() {
  router.replace({ path: '/registry', query: {} })
}

function pageItems<T>(items: T[], page: number, pageSize: number) {
  const start = Math.max(0, (page - 1) * pageSize)
  return items.slice(start, start + pageSize)
}

function clampPage(page: number, total: number, pageSize: number) {
  const pageCount = Math.max(1, Math.ceil(total / pageSize))
  return Math.min(Math.max(1, page), pageCount)
}

async function load() {
  const [nextAgents] = await Promise.all([EapApi.listAgents(), loadAutonomousDiscovery()])
  agents.value = nextAgents
  selectedAgentId.value = agents.value[0]?.id
  await loadAgentRegistry()
  await discover()
}

async function loadAutonomousDiscovery() {
  autonomousLoading.value = true
  try {
    endpointLoading.value = true
    const [summary, assets, endpoints, links] = await Promise.all([
      EapApi.getAgentDiscoverySummary(),
      EapApi.listDiscoveredAgentAssets(),
      EapApi.listDiscoveryEndpoints(),
      EapApi.listDiscoveryIdentityLinks(),
    ])
    autonomousSummary.value = summary
    discoveredAssets.value = assets
    discoveryEndpoints.value = endpoints
    identityLinks.value = links
  } finally {
    autonomousLoading.value = false
    endpointLoading.value = false
  }
}

async function triggerAutonomousScan() {
  scanLoading.value = true
  try {
    const run = await EapApi.triggerAgentDiscoveryScan()
    ElMessage.success(`扫描完成：发现 ${run.discoveredCount} 个资产，记录 ${run.observationsCount} 条证据`)
    await loadAutonomousDiscovery()
  } finally {
    scanLoading.value = false
  }
}

async function probeProtocol() {
  if (!protocolForm.targetUrl.trim()) {
    ElMessage.warning('请输入 A2A Agent 或 MCP Registry 地址')
    return
  }
  protocolLoading.value = true
  try {
    protocolResult.value = await EapApi.probeProtocolDiscovery({
      targetUrl: protocolForm.targetUrl.trim(),
      discoveryType: protocolForm.discoveryType,
    })
    ElMessage.success('协议发现完成，资产已进入候选清单')
    await loadAutonomousDiscovery()
  } finally {
    protocolLoading.value = false
  }
}

async function updateAssetStatus(id: number, status: DiscoveryAssetStatus) {
  await EapApi.updateDiscoveredAgentStatus(id, status)
  ElMessage.success('资产处置状态已更新')
  await loadAutonomousDiscovery()
}

function handleAssetStatusChange(id: number, value: unknown) {
  return updateAssetStatus(id, value as DiscoveryAssetStatus)
}

function riskTagType(riskLevel: RiskLevel) {
  if (riskLevel === 'CRITICAL' || riskLevel === 'HIGH') return 'danger'
  if (riskLevel === 'MEDIUM') return 'warning'
  return 'success'
}

function assetTypeLabel(assetType: DiscoveredAgentAsset['assetType']) {
  if (assetType === 'MCP_CONFIGURATION') return 'MCP 配置'
  if (assetType === 'AGENT_PROJECT') return '智能体项目'
  if (assetType === 'AGENT_SKILL') return '智能体技能'
  if (assetType === 'AGENT_PLUGIN') return '智能体插件'
  return '运行智能体'
}

function formatTime(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

async function loadAgentRegistry() {
  if (!selectedAgentId.value) return
  const [nextCapabilities, nextInstances] = await Promise.all([
    EapApi.listAgentCapabilities(selectedAgentId.value),
    EapApi.listAgentRuntimeInstances(selectedAgentId.value),
  ])
  capabilities.value = nextCapabilities
  runtimeInstances.value = nextInstances
}

async function registerCapability() {
  if (!selectedAgentId.value) return
  await EapApi.registerAgentCapability(selectedAgentId.value, { ...capabilityForm })
  ElMessage.success('能力已注册')
  await loadAgentRegistry()
}

async function registerRuntime() {
  if (!selectedAgentId.value) return
  await EapApi.registerAgentRuntimeInstance(selectedAgentId.value, { ...runtimeForm })
  ElMessage.success('运行实例已注册')
  await loadAgentRegistry()
}

async function heartbeat() {
  await EapApi.sendAgentHeartbeat(runtimeForm.instanceKey, { status: 'ONLINE', loadScore: heartbeatLoad.value })
  ElMessage.success('心跳已更新')
  await loadAgentRegistry()
}

async function discover() {
  loading.value = true
  try {
    const response = await EapApi.discoverAgents({
      capability: discoveryForm.capability,
      maxRiskLevel: discoveryForm.maxRiskLevel,
      department: discoveryForm.department || undefined,
      protocol: discoveryForm.protocol || undefined,
    })
    candidates.value = response.candidates
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>
