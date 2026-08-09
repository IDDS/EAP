<template>
  <section class="page-grid">
    <div class="metric-strip">
      <div v-for="item in metrics" :key="item.label" class="small-box" :class="item.tone">
        <div>
          <strong>{{ item.value }}</strong>
          <span>{{ item.label }}</span>
        </div>
        <el-icon><component :is="item.icon" /></el-icon>
      </div>
    </div>

    <div class="dashboard-layout">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>运行态势</span>
            <el-button text :icon="Refresh" @click="load">刷新</el-button>
          </div>
        </template>
        <div class="command-strip">
          <div class="command-card">
            <strong>计划</strong>
            <span>以业务目标、责任人和风险级别组织智能体上线计划。</span>
          </div>
          <div class="command-card">
            <strong>组织</strong>
            <span>按部门、角色和工具授权形成多智能体协作边界。</span>
          </div>
          <div class="command-card">
            <strong>控制</strong>
            <span>运行事件进入策略评估，输出允许、确认或拦截。</span>
          </div>
          <div class="command-card">
            <strong>审计</strong>
            <span>保存任务、决策、制品和治理动作的追踪记录。</span>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>风险控制面</template>
        <div class="risk-board">
          <div class="risk-row">
            <span>安全拦截</span>
            <el-progress :percentage="riskPercent" :stroke-width="8" status="exception" />
            <strong>{{ summary.riskEventCount }}</strong>
          </div>
          <div class="risk-row">
            <span>运行负载</span>
            <el-progress :percentage="loadPercent" :stroke-width="8" />
            <strong>{{ summary.runningTaskCount }}</strong>
          </div>
          <div class="risk-row">
            <span>成功率</span>
            <el-progress :percentage="successPercent" :stroke-width="8" status="success" />
            <strong>{{ successPercent }}%</strong>
          </div>
        </div>
      </el-card>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>智能体网络控制面 / 数据面</span>
          <div class="plane-legend">
            <span><i class="legend-control" />控制面</span>
            <span><i class="legend-data" />数据面</span>
          </div>
        </div>
      </template>
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
        </div>

        <aside class="network-side">
          <div class="network-stat-grid">
            <div>
              <span>控制节点</span>
              <strong>{{ network.stats.controlNodeCount }}</strong>
            </div>
            <div>
              <span>Agent 节点</span>
              <strong>{{ network.stats.agentNodeCount }}</strong>
            </div>
            <div>
              <span>控制边</span>
              <strong>{{ network.stats.controlEdgeCount }}</strong>
            </div>
            <div>
              <span>数据边</span>
              <strong>{{ network.stats.dataEdgeCount }}</strong>
            </div>
          </div>

          <div class="control-console">
            <strong>控制命令</strong>
            <span class="muted">控制面向选中的 Agent 下发命令，当前以 outbox 模拟派发。</span>
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
            <el-input v-model="commandForm.payloadJson" type="textarea" :rows="4" />
            <el-button type="primary" :icon="Connection" :disabled="!commandForm.agentId" @click="issueCommand">下发控制命令</el-button>
          </div>

          <div class="command-log">
            <div class="panel-header compact">
              <span>最近控制消息</span>
              <el-button text :icon="Refresh" @click="load">刷新</el-button>
            </div>
            <div v-for="command in commands" :key="command.id" class="command-log-item">
              <strong>{{ command.commandType }}</strong>
              <span>{{ command.commandKey }} · Agent {{ command.agentId }} · {{ command.status }}</span>
            </div>
          </div>
        </aside>
      </div>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheck, Connection, Cpu, Lock, Monitor, Refresh } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { AgentControlCommand, DashboardNetwork, DashboardSummary, NetworkNode } from '@/types/domain'

const summary = ref<DashboardSummary>({
  agentCount: 0,
  activeAgentCount: 0,
  runningTaskCount: 0,
  riskEventCount: 0,
  successRate: 0,
  averageLatencyMs: 0,
})
const network = ref<DashboardNetwork>({
  nodes: [],
  edges: [],
  stats: {
    controlNodeCount: 0,
    agentNodeCount: 0,
    controlEdgeCount: 0,
    dataEdgeCount: 0,
  },
})
const commands = ref<AgentControlCommand[]>([])
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

const metrics = computed(() => [
  { label: '智能体总数', value: summary.value.agentCount, tone: 'box-primary', icon: Cpu },
  { label: '已启用智能体', value: summary.value.activeAgentCount, tone: 'box-success', icon: CircleCheck },
  { label: '运行中任务', value: summary.value.runningTaskCount, tone: 'box-info', icon: Monitor },
  { label: '风险事件', value: summary.value.riskEventCount, tone: 'box-danger', icon: Lock },
])

const successPercent = computed(() => Math.round(summary.value.successRate * 100))
const riskPercent = computed(() => Math.min(100, summary.value.riskEventCount * 25))
const loadPercent = computed(() => Math.min(100, summary.value.runningTaskCount * 20))
const nodeMap = computed(() => new Map(network.value.nodes.map((node) => [node.id, node])))
const networkEdges = computed(() =>
  network.value.edges
    .map((edge) => {
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
    })
    .filter((edge): edge is NonNullable<typeof edge> => Boolean(edge)),
)
const agentNodes = computed(() => network.value.nodes.filter((node) => node.nodeType === 'AGENT'))

async function load() {
  const [nextSummary, nextNetwork, nextCommands] = await Promise.all([
    EapApi.getDashboardSummary(),
    EapApi.getDashboardNetwork(),
    EapApi.listControlCommands(),
  ])
  summary.value = nextSummary
  network.value = nextNetwork
  commands.value = nextCommands
}

function selectNetworkNode(node: NetworkNode) {
  if (draggedDuringMove.value) {
    draggedDuringMove.value = false
    return
  }
  selectedNetworkNode.value = node
  if (node.nodeType === 'AGENT') {
    commandForm.value.agentId = Number(node.id.replace('agent:', ''))
  }
}

function startNetworkDrag(node: NetworkNode, event: MouseEvent) {
  draggingNodeId.value = node.id
  draggedDuringMove.value = false
  dragOffset.value = {
    x: event.offsetX,
    y: event.offsetY,
  }
  selectedNetworkNode.value = node
  if (node.nodeType === 'AGENT') {
    commandForm.value.agentId = Number(node.id.replace('agent:', ''))
  }
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

function stopNetworkDrag() {
  draggingNodeId.value = undefined
}

async function issueCommand() {
  if (!commandForm.value.agentId) return
  const command = await EapApi.issueControlCommand({
    agentId: commandForm.value.agentId,
    commandType: commandForm.value.commandType,
    payloadJson: commandForm.value.payloadJson,
    issuedBy: 'platform-admin',
  })
  commands.value.unshift(command)
  await load()
  ElMessage.success('控制命令已下发')
}

onMounted(load)
</script>
