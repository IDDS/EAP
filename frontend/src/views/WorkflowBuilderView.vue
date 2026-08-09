<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>拖拽式智能体工作流</strong>
        <span>从左侧拖拽 Agent 到画布，点击两个节点创建执行连线</span>
      </div>
      <div class="button-row">
        <el-select v-model="selectedWorkflowId" class="agent-select" @change="loadWorkflow">
          <el-option v-for="workflow in workflows" :key="workflow.id" :label="`${workflow.name} · ${workflow.code}`" :value="workflow.id" />
        </el-select>
        <el-button type="success" :icon="VideoPlay" :loading="running" @click="runWorkflow">执行工作流</el-button>
        <el-button type="primary" :icon="Plus" @click="createWorkflow">新建工作流</el-button>
      </div>
    </div>

    <div class="workflow-builder">
      <aside class="workflow-palette">
        <div class="palette-title">Agent Palette</div>
        <div
          v-for="agent in agents"
          :key="agent.id"
          class="palette-agent"
          draggable="true"
          @dragstart="onPaletteDrag(agent, $event)"
        >
          <strong>{{ agent.name }}</strong>
          <span>{{ agent.code }} · {{ agent.riskLevel }}</span>
        </div>
        <div class="palette-title">System Nodes</div>
        <div
          v-for="node in systemNodes"
          :key="node.nodeType"
          class="palette-agent system"
          draggable="true"
          @dragstart="onSystemDrag(node, $event)"
        >
          <strong>{{ node.label }}</strong>
          <span>{{ node.nodeType }}</span>
        </div>
      </aside>

      <main
        ref="canvasRef"
        class="workflow-canvas"
        @dragover.prevent
        @drop="onCanvasDrop"
        @mousemove="onNodeMove"
        @mouseup="stopNodeMove"
        @mouseleave="stopNodeMove"
      >
        <div class="workflow-surface" :style="{ width: `${canvasSize.width}px`, height: `${canvasSize.height}px` }">
          <svg class="workflow-lines">
            <line
              v-for="edge in edgeLines"
              :key="edge.id"
              :x1="edge.x1"
              :y1="edge.y1"
              :x2="edge.x2"
              :y2="edge.y2"
              class="workflow-line"
            />
          </svg>

          <div
            v-for="node in nodes"
            :key="node.nodeKey"
            class="workflow-node"
            :class="{
              selected: selectedNode?.nodeKey === node.nodeKey,
              pending: pendingSourceKey === node.nodeKey,
              executed: executedNodeKeys.has(node.nodeKey),
              blocked: blockedNodeKeys.has(node.nodeKey),
            }"
            :style="{ left: `${node.positionX}px`, top: `${node.positionY}px` }"
            @mousedown.stop="startNodeMove(node, $event)"
            @click.stop="selectOrConnect(node)"
          >
            <div class="node-type">{{ node.nodeType }}</div>
            <strong>{{ node.label }}</strong>
            <span>{{ node.capability || 'system' }}</span>
          </div>
        </div>

        <div v-if="nodes.length === 0" class="canvas-empty">
          将左侧 Agent 或系统节点拖入画布
        </div>
      </main>

      <aside class="workflow-inspector">
        <div class="palette-title">Inspector</div>
        <template v-if="selectedNode">
          <el-form label-position="top">
            <el-form-item label="节点名称">
              <el-input v-model="selectedNode.label" />
            </el-form-item>
            <el-form-item label="节点类型">
              <el-input v-model="selectedNode.nodeType" />
            </el-form-item>
            <el-form-item label="能力">
              <el-input v-model="selectedNode.capability" />
            </el-form-item>
            <el-form-item label="配置 JSON">
              <el-input v-model="selectedNode.configJson" type="textarea" :rows="5" />
            </el-form-item>
            <el-button type="primary" :icon="Check" @click="saveSelectedNode">保存节点</el-button>
          </el-form>
        </template>
        <el-empty v-else description="选择一个节点查看配置" />

        <div class="inspector-help">
          <strong>连线方式</strong>
          <span>点击源节点，再点击目标节点，即可创建一条执行边。</span>
        </div>

        <div v-if="lastRun" class="run-panel">
          <div class="run-head">
            <strong>运行结果</strong>
            <el-tag size="small" :type="runTagType(lastRun.status)">{{ lastRun.status }}</el-tag>
          </div>
          <span class="run-key">{{ lastRun.runKey }}</span>
          <div class="run-steps">
            <div v-for="step in lastRun.steps" :key="step.nodeKey" class="run-step">
              <div>
                <strong>{{ step.label }}</strong>
                <span>{{ step.nodeType }} · {{ step.safetyDecision }}</span>
              </div>
              <el-tag size="small" :type="stepTagType(step.status)">{{ step.status }}</el-tag>
            </div>
          </div>
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Plus, VideoPlay } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { Agent, WorkflowDefinition, WorkflowEdge, WorkflowNode, WorkflowRun } from '@/types/domain'

type DragPayload = {
  nodeType: string
  label: string
  agentId?: number
  capability?: string
}

const canvasRef = ref<HTMLElement>()
const workflows = ref<WorkflowDefinition[]>([])
const selectedWorkflowId = ref<number>()
const agents = ref<Agent[]>([])
const nodes = ref<WorkflowNode[]>([])
const edges = ref<WorkflowEdge[]>([])
const selectedNode = ref<WorkflowNode>()
const pendingSourceKey = ref<string>()
const movingNode = ref<WorkflowNode>()
const movingOffset = ref({ x: 0, y: 0 })
const movedDuringDrag = ref(false)
const dragPayload = ref<DragPayload>()
const running = ref(false)
const lastRun = ref<WorkflowRun>()

const systemNodes: DragPayload[] = [
  { nodeType: 'START', label: '开始' },
  { nodeType: 'DECISION', label: '条件判断' },
  { nodeType: 'ARTIFACT', label: '制品输出' },
  { nodeType: 'END', label: '结束' },
]

const edgeLines = computed(() =>
  edges.value
    .map((edge) => {
      const source = nodes.value.find((node) => node.nodeKey === edge.sourceNodeKey)
      const target = nodes.value.find((node) => node.nodeKey === edge.targetNodeKey)
      if (!source || !target) return undefined
      return {
        id: edge.id,
        x1: source.positionX + 80,
        y1: source.positionY + 36,
        x2: target.positionX + 80,
        y2: target.positionY + 36,
      }
    })
    .filter((line): line is { id: number; x1: number; y1: number; x2: number; y2: number } => Boolean(line)),
)

const canvasSize = computed(() => {
  const maxX = Math.max(960, ...nodes.value.map((node) => node.positionX + 220))
  const maxY = Math.max(640, ...nodes.value.map((node) => node.positionY + 160))
  return { width: maxX, height: maxY }
})

const executedNodeKeys = computed(() => new Set(lastRun.value?.steps.map((step) => step.nodeKey) ?? []))
const blockedNodeKeys = computed(
  () => new Set(lastRun.value?.steps.filter((step) => ['BLOCKED', 'APPROVAL_REQUIRED'].includes(step.status)).map((step) => step.nodeKey) ?? []),
)

async function load() {
  const [nextAgents, nextWorkflows] = await Promise.all([EapApi.listAgents(), EapApi.listWorkflows()])
  agents.value = nextAgents
  workflows.value = nextWorkflows
  selectedWorkflowId.value = nextWorkflows[0]?.id
  await loadWorkflow()
}

async function loadWorkflow() {
  if (!selectedWorkflowId.value) return
  const detail = await EapApi.getWorkflow(selectedWorkflowId.value)
  nodes.value = detail.nodes
  edges.value = detail.edges
  selectedNode.value = nodes.value[0]
  pendingSourceKey.value = undefined
  lastRun.value = undefined
}

function onPaletteDrag(agent: Agent, event: DragEvent) {
  dragPayload.value = {
    nodeType: 'AGENT',
    label: agent.name,
    agentId: agent.id,
    capability: inferCapability(agent.code),
  }
  event.dataTransfer?.setData('text/plain', agent.code)
}

function onSystemDrag(node: DragPayload, event: DragEvent) {
  dragPayload.value = node
  event.dataTransfer?.setData('text/plain', node.nodeType)
}

async function onCanvasDrop(event: DragEvent) {
  if (!selectedWorkflowId.value || !canvasRef.value || !dragPayload.value) return
  const rect = canvasRef.value.getBoundingClientRect()
  const payload = dragPayload.value
  const nodeKey = `${payload.nodeType.toLowerCase()}-${Date.now()}`
  const node = await EapApi.upsertWorkflowNode(selectedWorkflowId.value, {
    nodeKey,
    label: payload.label,
    nodeType: payload.nodeType,
    agentId: payload.agentId,
    capability: payload.capability,
    positionX: Math.max(20, Math.round(event.clientX - rect.left - 80)),
    positionY: Math.max(20, Math.round(event.clientY - rect.top - 36)),
    configJson: JSON.stringify({ safety: 'required' }),
  })
  nodes.value.push(node)
  selectedNode.value = node
  dragPayload.value = undefined
}

function startNodeMove(node: WorkflowNode, event: MouseEvent) {
  movingNode.value = node
  movedDuringDrag.value = false
  movingOffset.value = {
    x: event.offsetX,
    y: event.offsetY,
  }
}

function onNodeMove(event: MouseEvent) {
  if (!movingNode.value || !canvasRef.value) return
  const rect = canvasRef.value.getBoundingClientRect()
  movedDuringDrag.value = true
  movingNode.value.positionX = Math.max(8, Math.round(event.clientX - rect.left + canvasRef.value.scrollLeft - movingOffset.value.x))
  movingNode.value.positionY = Math.max(8, Math.round(event.clientY - rect.top + canvasRef.value.scrollTop - movingOffset.value.y))
}

async function stopNodeMove() {
  if (!selectedWorkflowId.value || !movingNode.value) return
  const node = movingNode.value
  movingNode.value = undefined
  await EapApi.upsertWorkflowNode(selectedWorkflowId.value, {
    nodeKey: node.nodeKey,
    label: node.label,
    nodeType: node.nodeType,
    agentId: node.agentId,
    capability: node.capability,
    positionX: node.positionX,
    positionY: node.positionY,
    configJson: node.configJson,
  })
}

async function selectOrConnect(node: WorkflowNode) {
  if (!selectedWorkflowId.value) return
  if (movedDuringDrag.value) {
    movedDuringDrag.value = false
    selectedNode.value = node
    return
  }
  if (!pendingSourceKey.value) {
    pendingSourceKey.value = node.nodeKey
    selectedNode.value = node
    return
  }
  if (pendingSourceKey.value === node.nodeKey) {
    pendingSourceKey.value = undefined
    selectedNode.value = node
    return
  }
  const edge = await EapApi.addWorkflowEdge(selectedWorkflowId.value, {
    sourceNodeKey: pendingSourceKey.value,
    targetNodeKey: node.nodeKey,
    conditionExpression: 'always',
  })
  edges.value.push(edge)
  pendingSourceKey.value = undefined
  selectedNode.value = node
  ElMessage.success('连线已创建')
}

async function runWorkflow() {
  if (!selectedWorkflowId.value) return
  running.value = true
  try {
    lastRun.value = await EapApi.runWorkflow(selectedWorkflowId.value, {
      objective: workflows.value.find((workflow) => workflow.id === selectedWorkflowId.value)?.name,
    })
    ElMessage.success(`工作流运行完成：${lastRun.value.status}`)
  } finally {
    running.value = false
  }
}

async function saveSelectedNode() {
  if (!selectedWorkflowId.value || !selectedNode.value) return
  const node = selectedNode.value
  await EapApi.upsertWorkflowNode(selectedWorkflowId.value, {
    nodeKey: node.nodeKey,
    label: node.label,
    nodeType: node.nodeType,
    agentId: node.agentId,
    capability: node.capability,
    positionX: node.positionX,
    positionY: node.positionY,
    configJson: node.configJson,
  })
  ElMessage.success('节点已保存')
}

async function createWorkflow() {
  const created = await EapApi.createWorkflow({
    code: `FLOW-${Date.now()}`,
    name: '新的智能体工作流',
    description: '通过拖拽方式构建的多智能体流程',
  })
  workflows.value.unshift(created)
  selectedWorkflowId.value = created.id
  nodes.value = []
  edges.value = []
  selectedNode.value = undefined
}

function inferCapability(code: string) {
  if (code.includes('FIN')) return 'finance.analysis'
  if (code.includes('HR')) return 'hr.policy.qa'
  if (code.includes('OPS')) return 'ops.incident.triage'
  return ''
}

function runTagType(status: string) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'WAITING_APPROVAL') return 'warning'
  return 'danger'
}

function stepTagType(status: string) {
  if (status === 'DONE') return 'success'
  if (status === 'APPROVAL_REQUIRED') return 'warning'
  return status === 'BLOCKED' ? 'danger' : 'info'
}

onMounted(load)
</script>
