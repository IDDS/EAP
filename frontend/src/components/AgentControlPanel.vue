<template>
  <section class="agent-control-panel">
    <div class="control-console">
      <div class="control-console-heading"><strong>控制命令</strong><el-tag size="small" :type="targets?.dispatchEnabled ? 'success' : 'info'">{{ targets ? (targets.dispatchEnabled ? '实例派发' : '仅入队') : '待确认' }}</el-tag></div>
      <label>目标智能体</label><el-select :model-value="agentId" filterable placeholder="选择智能体" aria-label="控制目标智能体" @change="chooseAgent"><el-option v-for="node in agents" :key="node.id" :label="node.label" :value="Number(node.id.slice(6))" /></el-select>
      <label>目标运行实例</label><el-select v-model="instanceKey" :loading="targetLoading" :disabled="targetLoading || !agentId" placeholder="选择运行实例" aria-label="控制目标运行实例"><el-option v-for="instance in targets?.instances || []" :key="instance.instanceKey" :label="instance.instanceKey + (instance.unavailableReason ? ' · ' + instance.unavailableReason : '')" :value="instance.instanceKey" :disabled="!!instance.unavailableReason" /></el-select>
      <el-alert v-if="targetError || unavailable" type="warning" :closable="false" :title="targetError || unavailable"><el-button v-if="targetError" link @click="loadTargets">重新加载实例</el-button></el-alert>
      <small v-if="targets && !targets.dispatchEnabled">当前仅写入队列，不会触达运行实例。</small>
      <label>控制操作</label><el-select v-model="commandType" placeholder="选择控制操作" :disabled="!target || targetLoading" aria-label="控制操作"><el-option v-for="type in target?.commands || []" :key="type" :value="type" :label="commandLabel(type)" /></el-select>
      <small v-if="commandType === 'RUN_HEALTH_CHECK'">状态检查不会唤醒休眠实例。执行成功表示已收到实例状态。</small>
      <small v-if="commandType === 'REVERT'">回退会恢复上一个快照，请确认目标实例及操作影响。</small>
      <template v-if="needsPayload"><label>参数（JSON 对象）</label><el-input v-model="payloadJson" type="textarea" :rows="3" aria-label="控制命令参数" /></template>
      <el-button type="primary" :disabled="!canSubmit" :loading="commandLoading" @click="issueCommand">下发控制命令</el-button>
      <small v-if="!permissions.can('operateInstances')">当前账号只有查看权限，不能下发或重试命令。</small>
    </div>
    <div class="command-log">
      <div class="panel-header compact"><strong>最近控制消息</strong><el-button text :loading="refreshing" @click="emit('refresh')">刷新</el-button></div>
      <el-switch v-model="allMessages" active-text="查看全部智能体" aria-label="查看全部智能体的最近消息" />
      <small class="command-scope">{{ allMessages || !agentId ? '全部智能体' : agentName }} · 最近 20 条记录中匹配 {{ filteredCommands.length }} 条</small>
      <div class="command-log-scroll">
        <article v-for="command in pageCommands" :key="command.commandKey" class="command-row">
          <div class="command-row-title"><strong>{{ commandLabel(command.commandType) }}</strong><el-tag size="small" :type="statusType(command.status)">{{ statusLabel(command.status) }}</el-tag></div>
          <span class="command-row-meta">{{ nameFor(command.agentId) }} · {{ formatDate(command.createdAt) }}</span>
          <p class="command-result" :title="command.resultMessage">{{ command.resultMessage || '等待结果' }}</p>
          <details><summary>查看详情</summary><dl><dt>命令编号</dt><dd>{{ command.commandKey }}</dd><dt>目标实例</dt><dd>{{ command.runtimeInstanceKey || '未选到可用实例' }}</dd><dt>派发方式</dt><dd>{{ command.dispatchMode === 'CONTROL_PLANE_OUTBOX' ? '仅入队' : '实例派发' }}</dd><dt>尝试次数</dt><dd>{{ command.attemptCount || 0 }}</dd><dt>入队 / 派发 / 确认</dt><dd>{{ formatDate(command.createdAt) }}<br>{{ formatDate(command.dispatchedAt) }}<br>{{ formatDate(command.acknowledgedAt) }}</dd><dt>完整结果</dt><dd>{{ command.resultMessage || '等待结果' }}</dd></dl></details>
          <el-button v-if="canRetry(command)" size="small" type="warning" plain :disabled="commandLoading || !!retryingKey" :loading="retryingKey === command.commandKey" @click="retryCommand(command)">重新尝试</el-button>
        </article>
        <el-empty v-if="!filteredCommands.length" description="当前范围暂无最近控制消息" :image-size="40" />
      </div>
      <el-pagination v-if="filteredCommands.length > pageSize" v-model:current-page="page" :page-size="pageSize" :total="filteredCommands.length" layout="prev, pager, next" small aria-label="控制消息分页" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { EapApi } from '@/api/eap'
import { getControlTargets, type ControlTargets } from '@/api/control'
import { usePermissions } from '@/stores/permissions'
import type { AgentControlCommand, NetworkNode } from '@/types/domain'
const props = defineProps<{ agents: NetworkNode[]; agentId?: number; commands: AgentControlCommand[]; refreshing: boolean }>()
const emit = defineEmits<{ select: [node: NetworkNode]; refresh: [] }>()
const permissions = usePermissions()
const targets = ref<ControlTargets>(), instanceKey = ref(''), commandType = ref(''), payloadJson = ref('{}')
const targetLoading = ref(false), targetError = ref(''), commandLoading = ref(false), retryingKey = ref('')
const allMessages = ref(false), page = ref(1), pageSize = 4
const target = computed(() => targets.value?.instances.find(t => t.instanceKey === instanceKey.value && !t.unavailableReason))
const unavailable = computed(() => !props.agentId ? '请选择目标智能体' : targetLoading.value || !targets.value ? ''
  : !targets.value.instances.some(t => !t.unavailableReason) ? '该智能体没有可用运行实例。请先在智能体详情中创建或接入实例。' : '')
const canSubmit = computed(() => permissions.can('operateInstances') && !targetLoading.value && !commandLoading.value && !retryingKey.value && !!props.agentId && !!target.value?.commands.includes(commandType.value))
const needsPayload = computed(() => ['RELOAD_POLICY', 'UPDATE_CONFIG', 'SET_RATE_LIMIT'].includes(commandType.value))
const filteredCommands = computed(() => props.commands.filter(c => allMessages.value || !props.agentId || c.agentId === props.agentId))
const pageCommands = computed(() => filteredCommands.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const agentName = computed(() => nameFor(props.agentId || 0))
function nameFor(id: number) { return props.agents.find(n => n.id === `agent:${id}`)?.label || `智能体 ${id}` }
function chooseAgent(id: number) { const node = props.agents.find(n => n.id === `agent:${id}`); if (node) emit('select', node) }
let requestId = 0
async function loadTargets() {
  const revision = ++requestId, agentId = props.agentId, user = permissions.state.user
  targets.value = undefined; instanceKey.value = ''; targetError.value = ''; targetLoading.value = !!agentId
  commandType.value = ''; payloadJson.value = '{}'
  if (!agentId) return
  try {
    const result = await getControlTargets(agentId)
    if (revision !== requestId || agentId !== props.agentId || user !== permissions.state.user) return
    targets.value = result
    const available = result.instances.filter(t => !t.unavailableReason)
    if (available.length === 1) { instanceKey.value = available[0].instanceKey; commandType.value = available[0].commands[0] || '' }
  } catch (error) { if (revision === requestId && user === permissions.state.user) targetError.value = error instanceof Error ? error.message : '实例信息加载失败' }
  finally { if (revision === requestId) targetLoading.value = false }
}
function notifyResult(command: AgentControlCommand) {
  if (command.status === 'FAILED') ElMessage.error(command.resultMessage || '控制命令执行失败')
  else if (command.status === 'ACKED') ElMessage.success(command.resultMessage || '实例已确认执行完成')
  else ElMessage.warning(command.status === 'QUEUED' ? '命令已入队，尚未执行完成' : '命令已派发，等待实例确认')
}
async function issueCommand() {
  if (!canSubmit.value || !props.agentId || !target.value) return
  let payload = '{}'
  if (needsPayload.value) {
    try { const value = JSON.parse(payloadJson.value); if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error(); payload = JSON.stringify(value) }
    catch { ElMessage.warning('参数必须是有效的 JSON 对象'); return }
  }
  const request = { agentId: props.agentId, runtimeInstanceKey: instanceKey.value, commandType: commandType.value, payloadJson: payload }
  const user = permissions.state.user
  commandLoading.value = true
  try {
    if (request.commandType === 'REVERT') {
      await ElMessageBox.confirm(`确认将实例 ${request.runtimeInstanceKey} 回退到上一个快照？`, '回退快照', { type: 'warning' })
      if (user !== permissions.state.user || !permissions.can('operateInstances') || request.agentId !== props.agentId || request.runtimeInstanceKey !== instanceKey.value) return
    }
    const result = await EapApi.issueControlCommand(request)
    if (user !== permissions.state.user) return
    notifyResult(result); emit('refresh'); void loadTargets()
  } catch (error) { if (user === permissions.state.user && error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '控制命令下发失败') }
  finally { commandLoading.value = false }
}
function canRetry(command: AgentControlCommand) {
  return permissions.can('operateInstances') && !targetLoading.value && command.status === 'FAILED' && command.agentId === props.agentId
    && !!target.value && target.value.instanceKey === command.runtimeInstanceKey && target.value.commands.includes(command.commandType)
    && (command.attemptCount || 0) < (targets.value?.maxAttempts || 0)
}
async function retryCommand(command: AgentControlCommand) {
  if (!canRetry(command) || commandLoading.value || retryingKey.value) return
  const user = permissions.state.user
  retryingKey.value = command.commandKey
  try { const result = await EapApi.retryControlCommand(command.commandKey); if (user !== permissions.state.user) return; notifyResult(result); emit('refresh'); void loadTargets() }
  catch (error) { if (user === permissions.state.user) ElMessage.error(error instanceof Error ? error.message : '重试失败') }
  finally { retryingKey.value = '' }
}
function commandLabel(value: string) { return ({ RUN_HEALTH_CHECK: '检查实例状态', PAUSE: '休眠实例', RESUME: '恢复实例', REVERT: '回退快照', RELOAD_POLICY: '重新加载策略', UPDATE_CONFIG: '更新配置', SET_RATE_LIMIT: '设置限流' } as Record<string, string>)[value] || value }
function statusLabel(value: string) { return ({ QUEUED: '已入队', DISPATCHED: '等待确认', ACKED: '已完成', FAILED: '执行失败' } as Record<string, string>)[value] || value }
function statusType(value: string): 'danger' | 'success' | 'warning' { return value === 'FAILED' ? 'danger' : value === 'ACKED' ? 'success' : 'warning' }
function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未发生' }
watch(() => [props.agentId, permissions.state.user], loadTargets, { immediate: true, flush: 'sync' })
watch(instanceKey, () => { commandType.value = target.value?.commands[0] || ''; payloadJson.value = '{}' }, { flush: 'sync' })
watch(() => [props.agentId, allMessages.value], () => { page.value = 1 })
watch(filteredCommands, rows => { page.value = Math.max(1, Math.min(page.value, Math.ceil(rows.length / pageSize))) })
onBeforeUnmount(() => { requestId++ })
</script>

<style scoped>
.agent-control-panel{display:grid;gap:14px;min-width:0}.control-console>label{font-size:12px;color:#516c82}.control-console small,.command-scope{font-size:12px;color:#60788b;line-height:1.6}.command-log{min-width:0;overflow:hidden}.command-log-scroll{height:330px;overflow:auto;overscroll-behavior:contain;padding-right:4px}.command-row{padding:12px 0;border-top:1px solid #dce6ee;font-size:12px;overflow-wrap:anywhere}.command-row-title{display:flex;align-items:center;justify-content:space-between;gap:8px}.command-row-meta{display:block;color:#667d90;margin-top:6px}.command-result{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;color:#526a7e;line-height:1.5;margin:8px 0}.command-row details summary{color:#1676ab;cursor:pointer;padding:4px 0}.command-row dl{max-height:180px;overflow:auto;padding:8px;background:#f2f7fa;line-height:1.5}.command-row dt{font-weight:600;color:#567084}.command-row dd{margin:2px 0 8px;color:#344f64}.command-log :deep(.el-pagination){justify-content:center;flex-wrap:wrap}.control-console :deep(.el-select){width:100%;min-width:0}
</style>
