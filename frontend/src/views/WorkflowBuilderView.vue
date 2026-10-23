<template>
  <section class="page-grid workflow-page">
    <div class="toolbar">
      <div class="toolbar-title">
        <h2>智能体工作流</h2>
        <span>从左侧拖拽 Agent 到画布，点击两个节点创建执行连线</span>
      </div>
      <div class="button-row">
        <el-select v-model="selectedWorkflowId" aria-label="选择工作流" class="agent-select" @change="loadWorkflow">
          <el-option v-for="workflow in workflows" :key="workflow.id" :label="`${workflow.name} · ${workflow.code}`" :value="workflow.id" />
        </el-select>
        <el-button type="primary" :icon="VideoPlay" :loading="running" :disabled="!permissions.can('runWorkflows')" @click="runWorkflow">安全预演</el-button>
        <el-button :disabled="!selectedWorkflowId" @click="loadWorkflow">重新加载流程</el-button>
        <el-button type="success" :disabled="!permissions.can('runWorkflows') || !executionAvailability.enabled || !selectedWorkflowId || hasUnsavedConfig || !!nodeConfigError" :loading="submittingExecution" @click="executeWorkflow">真实执行</el-button>
        <el-button type="primary" :icon="Plus" :disabled="!permissions.can('runWorkflows')" @click="createWorkflow">新建工作流</el-button>
      </div>
    </div>

    <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon>
      <el-button @click="load">重新读取工作流</el-button>
    </el-alert>
    <div class="workflow-view-tabs" aria-label="工作流视图">
      <button :class="{ active: workflowTab === 'design' }" :aria-pressed="workflowTab === 'design'" @click="workflowTab = 'design'">流程设计</button>
      <button :class="{ active: workflowTab === 'runs' }" :aria-pressed="workflowTab === 'runs'" @click="workflowTab = 'runs'">执行过程 <span>{{ executions.length }}</span></button>
    </div>
    <template v-if="workflowTab === 'design'">
    <el-alert title="安全预演：检查节点策略与分支路径，不调用 Agent、不生成制品；预演通过不代表任务执行成功。" type="info" :closable="false" show-icon />
    <el-alert :title="executionAvailability.enabled ? '支持常规执行与隔离沙箱串行协作；结果未知时不会自动重试。' : executionAvailability.message" :type="executionAvailability.enabled ? 'warning' : 'info'" :closable="false" />
    <div class="button-row">
      <span>预演场景</span>
      <el-select v-model="previewSeverity" aria-label="预演严重程度" style="width: 140px">
        <el-option label="低" value="low" /><el-option label="中" value="medium" />
        <el-option label="高" value="high" /><el-option label="严重" value="critical" />
      </el-select>
      <el-checkbox v-model="previewApproved">假设 approved 分支条件为真（不授予治理审批）</el-checkbox>
    </div>
    <div class="workflow-builder">
      <aside class="workflow-palette">
        <div class="palette-title">可用智能体</div>
        <div
          v-for="agent in agents"
          :key="agent.id"
          class="palette-agent"
          :draggable="permissions.can('runWorkflows')"
          @dragstart="onPaletteDrag(agent, $event)"
        >
          <strong>{{ agent.name }}</strong>
          <span>{{ agent.code }} · {{ agent.riskLevel }}</span>
        </div>
        <div class="palette-title">流程节点</div>
        <div
          v-for="node in systemNodes"
          :key="node.nodeType"
          class="palette-agent system"
          :draggable="permissions.can('runWorkflows')"
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
            <span>{{ nodeToolLabel(node) || node.capability || 'system' }}</span>
          </div>
        </div>

        <div v-if="nodes.length === 0" class="canvas-empty">
          将左侧 Agent 或系统节点拖入画布
        </div>
      </main>

      <aside class="workflow-inspector">
        <div class="palette-title">节点配置</div>
        <template v-if="selectedNode">
          <el-form label-position="top" :disabled="!permissions.can('runWorkflows')">
            <el-form-item label="节点名称">
              <el-input v-model="selectedNode.label" />
            </el-form-item>
            <el-form-item label="节点类型">
              <el-input v-model="selectedNode.nodeType" />
            </el-form-item>
            <el-form-item label="能力">
              <el-input v-model="selectedNode.capability" />
            </el-form-item>
            <el-form-item v-if="['AGENT','ARTIFACT'].includes(selectedNode.nodeType)" label="执行智能体">
              <el-input :model-value="agents.find(agent => agent.id === selectedNode?.agentId)?.name || '尚未绑定（请从左侧拖入智能体）'" disabled />
            </el-form-item>
            <el-form-item v-if="['AGENT','ARTIFACT'].includes(selectedNode.nodeType)" label="执行方式">
              <el-select :model-value="nodeBackend" @change="setNodeBackend"><el-option label="常规执行" value="RUNTIME" /><el-option label="隔离沙箱" value="AX" :disabled="nodeHasTool" /></el-select>
              <small v-if="nodeBackend === 'AX'">隔离沙箱默认执行模型任务，完成后自动休眠；可通过 inputFrom 接收上游结果。</small>
            </el-form-item>
            <WorkflowTaskConfig v-if="['AGENT','ARTIFACT'].includes(selectedNode.nodeType)" :key="`${selectedWorkflowId}:${selectedNode.nodeKey}`"
              :model-value="selectedNode.configJson" :node-type="selectedNode.nodeType" :agent-id="selectedNode.agentId"
              :disabled="!permissions.can('runWorkflows')" @update:model-value="setNodeConfig" />
            <details class="advanced-node-config">
              <summary>高级配置 JSON</summary>
              <el-form-item label="节点配置"><el-input v-model="selectedNode.configJson" aria-label="节点配置 JSON" type="textarea" :rows="7" /></el-form-item>
            </details>
            <el-alert v-if="hasUnsavedConfig" title="节点配置尚未保存；请保存后再真实执行。" type="warning" :closable="false" />
            <el-alert v-if="nodeConfigError" :title="nodeConfigError" type="error" :closable="false" />
            <el-button type="primary" :icon="Check" :disabled="!permissions.can('runWorkflows') || !!nodeConfigError" @click="saveSelectedNode">保存节点</el-button>
          </el-form>
        </template>
        <el-empty v-else description="选择一个节点查看配置" />

        <div class="inspector-help">
          <strong>连线方式</strong>
          <span>点击源节点，再点击目标节点，即可创建一条执行边。</span>
        </div>

        <div v-if="lastRun" class="run-panel">
          <div class="run-head">
            <strong>安全预演结果</strong>
            <el-tag size="small" :type="runTagType(lastRun.status)">{{ runStatusLabel(lastRun.status) }}</el-tag>
          </div>
          <span class="run-key">{{ lastRun.runKey }}</span>
          <div class="run-steps">
            <div v-for="step in lastRun.steps" :key="step.nodeKey" class="run-step">
              <div>
                <strong>{{ step.label }}</strong>
                <span>{{ step.nodeType }} · {{ step.safetyDecision }}</span>
              </div>
              <el-tag size="small" :type="stepTagType(step.status)">{{ runStatusLabel(step.status) }}</el-tag>
            </div>
          </div>
        </div>
      </aside>
    </div>
    </template>
    <template v-if="workflowTab === 'runs'">
    <el-card shadow="never">
      <template #header><div class="button-row"><strong>真实执行记录 · 最近 20 次</strong><el-button :loading="executionsLoading" @click="loadExecutions">刷新</el-button></div></template>
      <el-alert v-if="executionError" :title="executionError" type="error" :closable="false" />
      <el-empty v-if="!executions.length && !executionsLoading && !executionError" description="暂无真实执行记录；安全预演记录保留在流程设计中" />
      <el-table v-else :data="executions" @row-click="selectedExecutionKey = $event.runKey" highlight-current-row>
        <el-table-column prop="runKey" label="执行标识" min-width="210" />
        <el-table-column prop="requester" label="发起人" width="140" />
        <el-table-column label="状态" width="150"><template #default="{ row }">{{ executionStatusLabel(row.status) }}</template></el-table-column>
        <el-table-column prop="reason" label="结果说明" min-width="220" />
      </el-table>
      <router-link v-if="selectedExecution" :to="{ path: '/task-workspace', query: { workflowId: selectedExecution.workflowId, runKey: selectedExecution.runKey } }">在任务工作台查看结果与证据 →</router-link>
      <ExecutionTimeline v-if="selectedExecution" :execution="selectedExecution" />
      <PlanOutputs v-if="selectedExecution" :workflow-id="selectedExecution.workflowId" :run-key="selectedExecution.runKey"
        :node-labels="Object.fromEntries(selectedExecution.steps.map(step => [step.nodeKey, step.label]))" />
      <el-table v-if="selectedExecution" :data="selectedExecution.steps" size="small">
        <el-table-column prop="label" label="节点" /><el-table-column prop="taskKey" label="任务标识" min-width="240" />
        <el-table-column prop="runtimeInstanceKey" label="运行实例" />
        <el-table-column label="状态"><template #default="{ row }">{{ executionStatusLabel(row.status) }}</template></el-table-column>
        <el-table-column prop="reason" label="完成证据" min-width="180" />
      </el-table>
    </el-card>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Check, Plus, VideoPlay } from '@element-plus/icons-vue'
import { usePermissions } from '@/stores/permissions'
import { EapApi } from '@/api/eap'
import ExecutionTimeline from '@/components/workflow/ExecutionTimeline.vue'
import PlanOutputs from '@/components/workflow/PlanOutputs.vue'
import WorkflowTaskConfig from '@/components/workflow/WorkflowTaskConfig.vue'
import { toolConfigError, outputContractError, observationContractError } from '@/api/workflowTaskConfig'
import type { Agent, WorkflowDefinition, WorkflowEdge, WorkflowNode, WorkflowRun, WorkflowExecution } from '@/types/domain'

type DragPayload = {
  nodeType: string
  label: string
  agentId?: number
  capability?: string
}

const permissions = usePermissions()
const canvasRef = ref<HTMLElement>()
const workflowTab = ref('design')
const workflows = ref<WorkflowDefinition[]>([])
const loadError = ref('')
const selectedWorkflowId = ref<number>()
const agents = ref<Agent[]>([])
const nodes = ref<WorkflowNode[]>([])
const edges = ref<WorkflowEdge[]>([])
const selectedNode = ref<WorkflowNode>()
const savedNodeConfigs = ref<Record<string, string | undefined>>({})
const hasUnsavedConfig = computed(() => nodes.value.some(node => node.configJson !== savedNodeConfigs.value[node.nodeKey]))
const nodeConfigError = computed(() => nodes.value.map(node => toolConfigError(node.configJson, node.nodeType, node.agentId) || outputContractError(node.configJson, node.nodeType, node.agentId) || observationContractError(node.configJson, node.nodeType, node.agentId)).find(Boolean) || '')
const nodeHasTool = computed(() => { try { return Object.hasOwn(JSON.parse(selectedNode.value?.configJson || '{}'), 'eapTool') } catch { return false } })
function nodeToolLabel(node: WorkflowNode) {
  try { return JSON.parse(node.configJson || '{}').eapTool?.name === 'calculate_sum' ? '工具 · 求和计算' : '' } catch { return '' }
}
function setNodeConfig(value: string) {
  if (permissions.can('runWorkflows') && selectedNode.value && nodes.value.includes(selectedNode.value)) selectedNode.value.configJson = value
}
const nodeBackend = computed(() => { try { return JSON.parse(selectedNode.value?.configJson || '{}').executionBackend || 'RUNTIME' } catch { return '' } })
function setNodeBackend(value: string) {
  if (!selectedNode.value || !permissions.can('runWorkflows')) return
  if (value === 'AX' && nodeHasTool.value) { ElMessage.error('请先将工具任务改为模型任务'); return }
  try { const config = JSON.parse(selectedNode.value.configJson || '{}'); selectedNode.value.configJson = JSON.stringify({ ...config, executionBackend: value }, null, 2) }
  catch { ElMessage.error('请先修正配置 JSON，再选择执行方式') }
}
const pendingSourceKey = ref<string>()
const movingNode = ref<WorkflowNode>()
const movingOffset = ref({ x: 0, y: 0 })
const movedDuringDrag = ref(false)
const dragPayload = ref<DragPayload>()
const running = ref(false)
const lastRun = ref<WorkflowRun>()
const previewSeverity = ref('medium')
const previewApproved = ref(false)
const executionAvailability = ref({ enabled: false, protocol: '', message: '正在检查真实执行配置' })
const executions = ref<WorkflowExecution[]>([])
const selectedExecutionKey = ref('')
const selectedExecution = computed(() => executions.value.find(row => row.runKey === selectedExecutionKey.value))
const executionsLoading = ref(false)
const executionError = ref('')
const submittingExecution = ref(false)
let executionRequestId = 0
let disposed = false
let executionTimer: ReturnType<typeof setInterval> | undefined

async function loadExecutions() {
  const requestId = ++executionRequestId
  const id = selectedWorkflowId.value
  if (!id || disposed) { executionsLoading.value = false; return }
  executionsLoading.value = true
  try {
    const rows = await EapApi.listWorkflowExecutions(id)
    if (disposed || requestId !== executionRequestId || id !== selectedWorkflowId.value) return
    executions.value = rows
    if (!rows.some(row => row.runKey === selectedExecutionKey.value)) selectedExecutionKey.value = rows[0]?.runKey || ''
    executionError.value = ''
  } catch {
    if (!disposed && requestId === executionRequestId) executionError.value = '执行记录加载失败，请重试'
  } finally { if (requestId === executionRequestId) executionsLoading.value = false }
}
async function executeWorkflow() {
  if (!permissions.can('runWorkflows')) return
  const id = selectedWorkflowId.value
  if (!id || !executionAvailability.value.enabled || submittingExecution.value) return
  if (hasUnsavedConfig.value || nodeConfigError.value) { ElMessage.error(nodeConfigError.value || '请先保存节点配置'); return }
  const revision = workflowRequestId
  submittingExecution.value = true
  try {
    const result = await EapApi.executeWorkflow(id, { objective: workflows.value.find(row => row.id === id)?.name,
      context: { severity: previewSeverity.value, approved: previewApproved.value } })
    if (disposed || id !== selectedWorkflowId.value || revision !== workflowRequestId) return
    workflowTab.value = 'runs'
    selectedExecutionKey.value = result.runKey
    await loadExecutions()
    if (!disposed && id === selectedWorkflowId.value && revision === workflowRequestId) ElMessage.success('已提交真实执行，可在执行记录查看进度')
  } finally { submittingExecution.value = false }
}
function executionStatusLabel(status: string) {
  return ({ QUEUED: '排队中', RUNNING: '执行中', DISPATCHING: '派发中', SUCCEEDED: '已确认完成', FAILED: '失败',
    EXECUTION_UNKNOWN: '结果未知，需核对', WAITING_APPROVAL: '需要审批', BLOCKED: '策略阻断', CHECK_PASSED: '检查通过' } as Record<string, string>)[status] || status
}
watch(selectedWorkflowId, () => {
  ++executionRequestId; executions.value = []; selectedExecutionKey.value = ''; executionError.value = ''
  void loadExecutions()
})

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
  loadError.value = ''
  try {
    const [nextAgents, nextWorkflows] = await Promise.all([EapApi.listAgents(), EapApi.listWorkflows()])
    if (disposed) return
    agents.value = nextAgents
    workflows.value = nextWorkflows
    selectedWorkflowId.value = nextWorkflows[0]?.id
    await loadWorkflow()
  } catch {
    if (!disposed) loadError.value = '工作流加载失败，请确认登录状态后重试'
  }
}

let workflowRequestId = 0
let previewRequestId = 0
const creatingEdge = ref(false)
const creatingNode = ref(false)
async function loadWorkflow() {
  const requestId = ++workflowRequestId
  ++previewRequestId
  running.value = false
  const workflowId = selectedWorkflowId.value
  nodes.value = []
  savedNodeConfigs.value = {}
  edges.value = []
  selectedNode.value = undefined
  movingNode.value = undefined
  pendingSourceKey.value = undefined
  lastRun.value = undefined
  if (!workflowId) return
  loadError.value = ''
  try {
    const detail = await EapApi.getWorkflow(workflowId)
    if (disposed || requestId !== workflowRequestId || workflowId !== selectedWorkflowId.value) return
    nodes.value = detail.nodes
    savedNodeConfigs.value = Object.fromEntries(detail.nodes.map(node => [node.nodeKey, node.configJson]))
    edges.value = detail.edges
    selectedNode.value = nodes.value[0]
    pendingSourceKey.value = undefined
    lastRun.value = undefined
  } catch {
    if (!disposed && requestId === workflowRequestId && workflowId === selectedWorkflowId.value) loadError.value = '流程读取失败，请重试'
  }
}

function onPaletteDrag(agent: Agent, event: DragEvent) {
  if (!permissions.can('runWorkflows')) return
  dragPayload.value = {
    nodeType: 'AGENT',
    label: agent.name,
    agentId: agent.id,
    capability: inferCapability(agent.code),
  }
  event.dataTransfer?.setData('text/plain', agent.code)
}

function onSystemDrag(node: DragPayload, event: DragEvent) {
  if (!permissions.can('runWorkflows')) return
  dragPayload.value = node
  event.dataTransfer?.setData('text/plain', node.nodeType)
}

async function onCanvasDrop(event: DragEvent) {
  if (!permissions.can('runWorkflows')) return
  if (!selectedWorkflowId.value || !canvasRef.value || !dragPayload.value || creatingNode.value) return
  const workflowId = selectedWorkflowId.value
  const revision = workflowRequestId
  const rect = canvasRef.value.getBoundingClientRect()
  const payload = dragPayload.value
  const uniqueKey = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
  const nodeKey = `${payload.nodeType.toLowerCase()}-${uniqueKey}`
  creatingNode.value = true
  dragPayload.value = undefined
  try {
    const node = await EapApi.upsertWorkflowNode(workflowId, {
      nodeKey,
      label: payload.label,
      nodeType: payload.nodeType,
      agentId: payload.agentId,
      capability: payload.capability,
      positionX: Math.max(20, Math.round(event.clientX - rect.left - 80)),
      positionY: Math.max(20, Math.round(event.clientY - rect.top - 36)),
      configJson: JSON.stringify({ safety: 'required' }),
    })
    if (disposed || workflowId !== selectedWorkflowId.value || revision !== workflowRequestId) return
    nodes.value.push(node)
    savedNodeConfigs.value[node.nodeKey] = node.configJson
    selectedNode.value = node
  } finally { creatingNode.value = false }
}

function startNodeMove(node: WorkflowNode, event: MouseEvent) {
  if (!permissions.can('runWorkflows')) return
  movingNode.value = node
  movedDuringDrag.value = false
  movingOffset.value = {
    x: event.offsetX,
    y: event.offsetY,
  }
}

function onNodeMove(event: MouseEvent) {
  if (!permissions.can('runWorkflows')) return
  if (!movingNode.value || !canvasRef.value) return
  const rect = canvasRef.value.getBoundingClientRect()
  movedDuringDrag.value = true
  movingNode.value.positionX = Math.max(8, Math.round(event.clientX - rect.left + canvasRef.value.scrollLeft - movingOffset.value.x))
  movingNode.value.positionY = Math.max(8, Math.round(event.clientY - rect.top + canvasRef.value.scrollTop - movingOffset.value.y))
}

async function stopNodeMove() {
  if (!permissions.can('runWorkflows')) return
  if (!selectedWorkflowId.value || !movingNode.value) return
  const node = movingNode.value
  movingNode.value = undefined
  if (!movedDuringDrag.value) return
  const config = node.configJson
  const saved = await EapApi.upsertWorkflowNode(selectedWorkflowId.value, {
    expectedVersion: node.version,
    nodeKey: node.nodeKey,
    label: node.label,
    nodeType: node.nodeType,
    agentId: node.agentId,
    capability: node.capability,
    positionX: node.positionX,
    positionY: node.positionY,
    configJson: node.configJson,
  })
  node.version = saved.version
  if (nodes.value.includes(node)) savedNodeConfigs.value[node.nodeKey] = config
}

async function selectOrConnect(node: WorkflowNode) {
  if (!permissions.can('runWorkflows')) { selectedNode.value = node; pendingSourceKey.value = undefined; return }
  if (!selectedWorkflowId.value || creatingEdge.value || !nodes.value.includes(node)) return
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
  const workflowId = selectedWorkflowId.value
  const revision = workflowRequestId
  creatingEdge.value = true
  try {
    const edge = await EapApi.addWorkflowEdge(workflowId, {
      sourceNodeKey: pendingSourceKey.value,
      targetNodeKey: node.nodeKey,
      conditionExpression: 'always',
    })
    if (disposed || workflowId !== selectedWorkflowId.value || revision !== workflowRequestId) return
    if (!edges.value.some(row => row.id === edge.id)) edges.value.push(edge)
    pendingSourceKey.value = undefined
    selectedNode.value = node
    ElMessage.success('连线已创建')
  } finally { creatingEdge.value = false }
}

async function runWorkflow() {
  if (!permissions.can('runWorkflows')) return
  if (!selectedWorkflowId.value) return
  const workflowId = selectedWorkflowId.value
  const revision = workflowRequestId
  const requestId = ++previewRequestId
  lastRun.value = undefined
  running.value = true
  try {
    const result = await EapApi.runWorkflow(workflowId, {
      objective: workflows.value.find((workflow) => workflow.id === workflowId)?.name,
      context: { severity: previewSeverity.value, approved: previewApproved.value },
    })
    if (disposed || requestId !== previewRequestId || revision !== workflowRequestId || workflowId !== selectedWorkflowId.value) return
    lastRun.value = result
    ElMessage.info(`安全预演：${runStatusLabel(result.status)}`)
  } finally {
    if (requestId === previewRequestId) running.value = false
  }
}

async function saveSelectedNode() {
  if (!permissions.can('runWorkflows')) return
  if (nodeConfigError.value) { ElMessage.error(nodeConfigError.value); return }
  if (!selectedWorkflowId.value || !selectedNode.value || !nodes.value.includes(selectedNode.value)) return
  const workflowId = selectedWorkflowId.value
  const revision = workflowRequestId
  const node = selectedNode.value
  const config = node.configJson
  const saved = await EapApi.upsertWorkflowNode(selectedWorkflowId.value, {
    expectedVersion: node.version,
    nodeKey: node.nodeKey,
    label: node.label,
    nodeType: node.nodeType,
    agentId: node.agentId,
    capability: node.capability,
    positionX: node.positionX,
    positionY: node.positionY,
    configJson: node.configJson,
  })
  node.version = saved.version
  if (nodes.value.includes(node)) savedNodeConfigs.value[node.nodeKey] = config
  if (!disposed && workflowId === selectedWorkflowId.value && revision === workflowRequestId && selectedNode.value === node) ElMessage.success('节点已保存')
}

async function createWorkflow() {
  if (!permissions.can('runWorkflows')) return
  const revision = workflowRequestId
  const created = await EapApi.createWorkflow({
    code: `FLOW-${Date.now()}`,
    name: '新的智能体工作流',
    description: '通过拖拽方式构建的多智能体流程',
  })
  if (disposed) return
  workflows.value.unshift(created)
  if (revision !== workflowRequestId) return
  ++workflowRequestId
  ++previewRequestId
  running.value = false
  lastRun.value = undefined
  movingNode.value = undefined
  pendingSourceKey.value = undefined
  selectedWorkflowId.value = created.id
  nodes.value = []
  savedNodeConfigs.value = {}
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
  if (status === 'PREVIEW_COMPLETED') return 'success'
  if (['WAITING_APPROVAL', 'PREVIEW_NO_MATCH'].includes(status)) return 'warning'
  return 'danger'
}

function stepTagType(status: string) {
  if (status === 'CHECK_PASSED') return 'success'
  if (status === 'APPROVAL_REQUIRED') return 'warning'
  return status === 'BLOCKED' ? 'danger' : 'info'
}

function runStatusLabel(status: string) {
  return ({ PREVIEW_COMPLETED: '预演通过', PREVIEW_NO_MATCH: '无匹配分支', CHECK_PASSED: '检查通过',
    WAITING_APPROVAL: '需要治理审批', APPROVAL_REQUIRED: '需要治理审批', BLOCKED: '策略阻断',
    FAILED_LOOP_GUARD: '超过预演步数限制' } as Record<string, string>)[status] || status
}

watch(() => permissions.can('runWorkflows'), allowed => {
  if (!allowed) { movingNode.value = undefined; pendingSourceKey.value = undefined; dragPayload.value = undefined }
}, { flush: 'sync' })

onMounted(() => {
  void load()
  EapApi.workflowExecutionStatus().then(value => { if (!disposed) executionAvailability.value = value })
    .catch(() => { if (!disposed) executionAvailability.value.message = '真实执行配置读取失败，暂不可提交' })
  executionTimer = setInterval(() => {
    if (!executionsLoading.value && executions.value.some(row => ['QUEUED', 'RUNNING'].includes(row.status))) void loadExecutions()
  }, 2000)
})
onUnmounted(() => { disposed = true; ++executionRequestId; ++workflowRequestId; ++previewRequestId; if (executionTimer) clearInterval(executionTimer) })
</script>

<style scoped>
.advanced-node-config { margin: 12px 0 18px; }
.advanced-node-config summary { cursor: pointer; margin-bottom: 12px; color: var(--el-text-color-secondary); }
.workflow-view-tabs{display:flex;border-bottom:1px solid #dbe3eb;gap:8px}.workflow-view-tabs button{font:inherit;border:0;border-bottom:3px solid transparent;background:none;min-height:44px;padding:12px 20px;color:#526175;cursor:pointer}.workflow-view-tabs button.active{color:#176b9b;font-weight:600;border-bottom-color:#2683b8}.workflow-view-tabs button:focus-visible{outline:2px solid #2683b8;outline-offset:-3px}.workflow-view-tabs span{font-size:12px;border-radius:4px;background:#e8eef4;padding:2px 6px;margin-left:8px}

@media (max-width: 1100px) {
  .workflow-builder { grid-template-columns: minmax(0, 1fr); max-height: none; }
  .workflow-palette { max-height: 230px; overflow: auto; }
  .workflow-canvas { min-height: 420px; height: 420px; }
  .workflow-inspector { overflow: visible; border-left: 0; }
}
@media (max-width: 760px) {
  .workflow-page, .workflow-page > *, .workflow-builder > * { min-width: 0; max-width: 100%; }
  .button-row { flex-wrap: wrap; align-items: center; }
  .button-row .el-button { margin-left: 0; }
  .agent-select { width: 100%; min-width: 0; }
  .button-row .el-checkbox { height: auto; white-space: normal; }
  .button-row :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; }
  .workflow-view-tabs button { padding: 12px; }
}
</style>
