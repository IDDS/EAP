<template>
  <div class="substrate-deploy">
    <el-alert v-if="capabilities && !capabilities.enabled" type="warning" :closable="false" show-icon
      title="托管运行环境未启用，请联系平台管理员启用。" />

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header"><span>部署新版本</span><span class="muted">发布后生成不可变版本；修改配置即发布新版本</span></div>
      </template>
      <el-form :disabled="!actions.deploy" label-width="120px" class="deploy-form" @submit.prevent>
        <el-form-item label="部署方式">
          <el-radio-group v-model="form.sourceType">
            <el-radio-button value="RUNNER" :disabled="capabilities ? !capabilities.runnerAvailable : false">零代码配置</el-radio-button>
            <el-radio-button value="IMAGE">已有镜像</el-radio-button>
            <el-radio-button value="SOURCE" :disabled="capabilities ? !capabilities.sourceBuildAvailable : false">源码构建</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <template v-if="form.sourceType === 'RUNNER'">
          <p class="muted">创建后自动配置专属实例身份，接入授权有效期为24小时；任务派发前按需续期短期凭证，撤销后停止派发。</p>
          <el-form-item label="系统提示词">
            <el-input v-model="form.systemPrompt" type="textarea" :rows="4" maxlength="8000" show-word-limit
              placeholder="描述 Agent 的角色、能力和回答要求，例如：你是财务助手，只回答报销制度相关问题。" />
          </el-form-item>
          <el-form-item label="模型">
            <el-select v-model="form.model" filterable allow-create style="width: 260px">
              <el-option v-for="model in models" :key="model" :label="model" :value="model" />
            </el-select>
            <span class="field-hint">模型调用经 EAP 治理网关，Agent 不接触模型密钥</span>
          </el-form-item>
        </template>
        <template v-else>
          <el-form-item v-if="form.sourceType === 'IMAGE'" label="镜像地址">
            <el-input v-model="form.image" placeholder="localhost:5001/my-agent:latest 或 registry/repo@sha256:…" />
          </el-form-item>
          <template v-else>
            <el-form-item label="Git 仓库">
              <el-input v-model="form.gitUrl" placeholder="https://gitee.com/org/agent.git（公开仓库）" />
            </el-form-item>
            <el-form-item label="分支 / 提交">
              <el-input v-model="form.gitRef" class="short-input" placeholder="main、refs/tags/v1 或 40 位提交号" />
              <el-input v-model="form.contextPath" class="inline-input" placeholder="构建目录（默认仓库根目录）" />
              <el-input v-model="form.dockerfile" class="inline-input" placeholder="Dockerfile" />
            </el-form-item>
            <el-form-item>
              <span class="field-hint no-margin">在集群内用 kaniko 构建；Docker Hub 基础镜像经镜像加速拉取，推送后按摘要固定。仓库需可匿名克隆。</span>
            </el-form-item>
          </template>
          <el-form-item label="服务端口">
            <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
            <el-input v-model="form.readinessPath" class="inline-input" placeholder="就绪检查路径，如 /readyz" />
            <span v-if="form.port !== 80" class="field-hint no-margin">
              路由器的普通 HTTP 请求（任务派发、试运行）只转发到端口 80；其他端口只能经 CONNECT 隧道访问。建议让服务监听 80。
            </span>
          </el-form-item>
          <el-form-item label="启动命令">
            <el-input v-model="form.command" type="textarea" :rows="2" placeholder="每行一个参数；留空使用镜像默认命令" />
            <span class="field-hint no-margin">注意：运行环境始终在根目录 / 启动进程，不使用镜像的 WORKDIR；依赖相对路径的程序请在命令中写绝对路径，例如 serve -s /app/build。</span>
          </el-form-item>
        </template>

        <el-form-item label="沙箱">
          <el-radio-group v-model="form.sandboxClass">
            <el-radio value="GVISOR">gVisor（默认）</el-radio>
            <el-radio value="MICROVM">microVM（隔离更强）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="资源规格">
          <el-radio-group v-model="form.size">
            <el-radio-button value="SMALL">小 · 0.5 核 / 256Mi</el-radio-button>
            <el-radio-button value="MEDIUM">中 · 1 核 / 512Mi</el-radio-button>
            <el-radio-button value="LARGE">大 · 2 核 / 1Gi</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="环境变量">
          <div class="env-rows">
            <div v-for="(row, index) in form.env" :key="index" class="env-row">
              <el-input v-model="row.name" placeholder="名称" />
              <el-input v-model="row.value" placeholder="值（不可填写密钥）" />
              <el-button link type="danger" @click="form.env.splice(index, 1)">移除</el-button>
            </div>
            <el-button link type="primary" @click="form.env.push({ name: '', value: '' })">添加变量</el-button>
          </div>
        </el-form-item>
        <el-form-item label="出站访问">
          <el-input v-model="form.egressHosts" type="textarea" :rows="2"
            placeholder="额外允许访问的主机名，每行一个；每一项都进入治理审批" />
          <span class="field-hint">默认只允许访问 EAP 网关{{ capabilities?.gatewayHost ? `（${capabilities.gatewayHost}）` : '' }}</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" :disabled="!actions.deploy || !capabilities?.enabled" @click="submit">发布部署</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header"><span>部署版本</span><el-button link type="primary" @click="load">刷新</el-button></div>
      </template>
      <div v-for="deployment in deployments" :key="deployment.deploymentKey" class="deployment-item">
        <div class="deployment-head">
          <strong>v{{ deployment.version }}</strong>
          <el-tag size="small" effect="plain">{{ sourceLabel(deployment.sourceType) }}</el-tag>
          <el-tag size="small" :type="progress(deployment.status, deployment.sourceType, String(deployment.config.identityMode ?? '')).tone">{{ progress(deployment.status, deployment.sourceType, String(deployment.config.identityMode ?? '')).label }}</el-tag>
          <span class="muted">{{ deployment.requestedBy }} · {{ formatTime(deployment.createdAt) }}</span>
        </div>
        <el-steps :active="deployment.status === 'FAILED' ? failedStageIndex(deployment.steps, deployment.sourceType, String(deployment.config.identityMode ?? '')) : progress(deployment.status, deployment.sourceType, String(deployment.config.identityMode ?? '')).active"
          :process-status="progress(deployment.status, deployment.sourceType, String(deployment.config.identityMode ?? '')).state" finish-status="success" simple>
          <el-step v-for="stage in deployStages(deployment.sourceType, String(deployment.config.identityMode ?? ''))" :key="stage" :title="stage" />
        </el-steps>
        <p v-if="deployment.reason" class="deployment-reason">
          {{ deployment.reason }}
          <el-button v-if="deployment.status === 'FAILED' && actions.retry" link type="primary" :loading="retrying === deployment.version"
            @click="retry(deployment)">从失败步骤重试</el-button>
        </p>
        <el-collapse>
          <el-collapse-item title="步骤与配置">
            <div v-for="(step, index) in deployment.steps" :key="index" class="step-line">
              <el-tag size="small" :type="step.status === 'DONE' || step.status === 'ALLOW' ? 'success' : step.status === 'FAILED' || step.status === 'DENY' ? 'danger' : 'warning'">{{ step.status }}</el-tag>
              <span class="mono">{{ step.step }}</span><span>{{ step.detail }}</span><small class="muted">{{ formatTime(step.at) }}</small>
            </div>
            <div class="mono small">模板 {{ deployment.templateName }} · 镜像 {{ deployment.imageRef || '构建中' }}</div>
          </el-collapse-item>
          <el-collapse-item v-if="deployment.sourceType === 'SOURCE'" :name="`log-${deployment.version}`" title="构建日志">
            <el-button link type="primary" :loading="logLoading === deployment.version" @click="loadLog(deployment)">刷新日志</el-button>
            <pre class="build-log">{{ buildLogs[deployment.version] || '点击“刷新日志”查看最近 200 行' }}</pre>
          </el-collapse-item>
        </el-collapse>
      </div>
      <el-empty v-if="!deployments.length" description="尚未部署，填写上方表单发布第一个版本" :image-size="70" />
    </el-card>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>运行实例</span>
          <span>
            <el-button size="small" :loading="creating" :disabled="!actions.createInstance || !hasReadyVersion" @click="createInstance">{{ pendingCreation ? '核对原创建请求' : '新建实例' }}</el-button>
            <el-button link type="primary" @click="loadInstances">刷新</el-button>
          </span>
        </div>
      </template>
      <p v-if="!hasReadyVersion" class="muted">新建会话需要当前版本支持专属实例身份</p>
      <el-table v-loading="instancesLoading" :data="instances" size="small">
        <el-table-column prop="name" label="实例" min-width="170" />
        <el-table-column label="版本" width="80"><template #default="{ row }">{{ row.version ? `v${row.version}` : '—' }}</template></el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag size="small" :type="actorState(row.state).tone">{{ actorState(row.state).label }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" min-width="300">
          <template #default="{ row }">
            <el-button v-if="row.version != null" link type="primary" :disabled="!actions.chat" @click="openChat(row)">试运行</el-button>
            <el-button link :disabled="!actions.control" :loading="busy === row.instanceKey + 'RESUME'" @click="control(row, 'RESUME')">唤醒</el-button>
            <el-button link :disabled="!actions.control" :loading="busy === row.instanceKey + 'PAUSE'" @click="control(row, 'PAUSE')">休眠</el-button>
            <el-button v-if="row.version != null && row.state === 'CRASHED'" link type="warning" :disabled="!actions.control" :loading="busy === row.instanceKey + 'REVERT'" @click="control(row, 'REVERT')">回退</el-button>
            <el-button v-if="row.version != null" link type="danger" :disabled="!actions.remove" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="field-hint">休眠会保存完整内存与文件状态并释放计算资源；再次请求时自动唤醒，会话不丢失。</p>
    </el-card>

    <el-drawer v-model="chatOpen" :title="`试运行 · ${chatInstance?.name ?? ''}`" size="480px">
      <div class="chat-log">
        <div v-for="(message, index) in chatMessages" :key="index" :class="['chat-message', message.role]">
          <span>{{ message.text }}</span>
        </div>
        <el-empty v-if="!chatMessages.length" description="发送一条消息测试 Agent；同一实例会记住对话上下文" :image-size="60" />
      </div>
      <template #footer>
        <div class="chat-input">
          <el-input :disabled="!actions.chat" v-model="chatText" type="textarea" :rows="2" maxlength="4000" placeholder="输入消息，Ctrl+Enter 发送"
            @keydown.ctrl.enter="sendChat" />
          <el-button type="primary" :loading="chatting" :disabled="!actions.chat" @click="sendChat">发送</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { SubstrateApi } from '@/api/substrate'
import { substrateActions } from '@/api/capabilities'
import { usePermissions } from '@/stores/permissions'
import type { SubstrateCapabilities, SubstrateDeployment, SubstrateInstance } from '@/api/substrate'
import {
  ACTIVE_DEPLOYMENT_STATUSES, actorStateLabel, buildDeployPayload, defaultDeployForm, deployStages, failedStageIndex,
  deploymentProgress, validateDeployForm,
} from '@/api/substrateDeploy'

const props = defineProps<{ agentId: number }>()
const permissions = usePermissions()
const actions = computed(() => substrateActions(permissions.state.user?.capabilities))
const models = ['deepseek-flash', 'deepseek-v4-pro']
const buildLogs = reactive<Record<number, string>>({})
const logLoading = ref<number>()
const capabilities = ref<SubstrateCapabilities>()
const form = reactive(defaultDeployForm())
const deployments = ref<SubstrateDeployment[]>([])
const instances = ref<SubstrateInstance[]>([])
const submitting = ref(false)
const creating = ref(false)
const instancesLoading = ref(false)
const busy = ref('')
const chatOpen = ref(false)
const chatInstance = ref<SubstrateInstance>()
const chatText = ref('')
const chatting = ref(false)
const chatMessages = ref<{ role: 'user' | 'agent' | 'error'; text: string }[]>([])
// Reused while a submission's outcome is uncertain, so a retry cannot create a second version.
let pendingRequestKey = ''
const creationStorageKey = () => 'eap.instance.create.' + props.agentId + '.' + permissions.state.user?.subject
const pendingCreation = ref(globalThis.sessionStorage?.getItem(creationStorageKey()) || '')
let timer: ReturnType<typeof setInterval> | undefined

const progress = deploymentProgress
const actorState = actorStateLabel
const hasReadyVersion = computed(() => deployments.value.find(item => item.status === 'READY')?.config.identityMode === 'PER_INSTANCE_V1')

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : ''
}

async function load() {
  const agentId = props.agentId
  const [list] = await Promise.all([SubstrateApi.deployments(agentId), loadInstances()])
  if (agentId !== props.agentId) return
  const becameReady = list.some(item => item.status === 'READY'
    && deployments.value.some(old => old.deploymentKey === item.deploymentKey && old.status !== 'READY'))
  deployments.value = list
  if (becameReady) await loadInstances()
  schedulePolling()
}

async function loadInstances() {
  const agentId = props.agentId
  instancesLoading.value = true
  try {
    const list = await SubstrateApi.instances(agentId)
    if (agentId === props.agentId) instances.value = list
  } finally { instancesLoading.value = false }
}

function schedulePolling() {
  const active = deployments.value.some(item => ACTIVE_DEPLOYMENT_STATUSES.includes(item.status))
  if (active && !timer) timer = setInterval(() => { void load().catch(() => undefined) }, 3000)
  if (!active && timer) { clearInterval(timer); timer = undefined }
}

async function submit() {
  if (!actions.value.deploy) return
  const problem = validateDeployForm(form)
  if (problem) { ElMessage.warning(problem); return }
  pendingRequestKey ||= globalThis.crypto?.randomUUID?.().replace(/-/g, '') ?? `${Date.now()}${Math.random().toString(36).slice(2)}`
  submitting.value = true
  try {
    const deployment = await SubstrateApi.deploy(props.agentId, buildDeployPayload(form, pendingRequestKey))
    pendingRequestKey = ''
    ElMessage.success(deployment.status === 'QUEUED' ? `已提交 v${deployment.version}，正在部署` : `v${deployment.version}：${progress(deployment.status).label}`)
    await load()
  } catch (error) {
    // Keep pendingRequestKey only when the server may have accepted the request (no response received).
    const response = (error as { response?: unknown }).response
    if (response) pendingRequestKey = ''
  } finally { submitting.value = false }
}

function sourceLabel(source: string) {
  return source === 'RUNNER' ? '零代码' : source === 'SOURCE' ? '源码' : '镜像'
}

async function loadLog(deployment: SubstrateDeployment) {
  logLoading.value = deployment.version
  try { buildLogs[deployment.version] = (await SubstrateApi.buildLog(props.agentId, deployment.version)) || '（暂无日志）' }
  finally { logLoading.value = undefined }
}

const retrying = ref<number>()
async function retry(deployment: SubstrateDeployment) {
  if (!actions.value.retry) return
  retrying.value = deployment.version
  try {
    await SubstrateApi.retry(props.agentId, deployment.version)
    ElMessage.success(`v${deployment.version} 已重新进入部署队列`)
    await load()
  } finally { retrying.value = undefined }
}

async function createInstance() {
  if (!actions.value.createInstance) return
  creating.value = true
  const agentId=props.agentId,user=permissions.state.user,storageKey=creationStorageKey()
  try {
    pendingCreation.value ||= crypto.randomUUID()
    sessionStorage.setItem(storageKey, pendingCreation.value)
    const instance = await SubstrateApi.createInstance(agentId, pendingCreation.value)
    sessionStorage.removeItem(storageKey)
    if(user!==permissions.state.user||agentId!==props.agentId||!actions.value.createInstance)return
    pendingCreation.value = ''
    ElMessage.success(`已创建实例 ${instance.name}`)
    await loadInstances()
  } catch {
    ElMessage.warning('创建结果未确认，已保留原请求；再次点击核对，不会创建另一个实例')
  } finally { creating.value = false }
}

async function control(instance: SubstrateInstance, command: 'PAUSE' | 'RESUME' | 'REVERT') {
  if (!actions.value.control) return
  busy.value = instance.instanceKey + command
  try {
    const result = await SubstrateApi.control(props.agentId, instance.instanceKey, command)
    if (result.status === 'ACKED') ElMessage.success(result.resultMessage || '已完成')
    else ElMessage.error(result.resultMessage || '操作失败')
    await loadInstances()
  } finally { busy.value = '' }
}

async function remove(instance: SubstrateInstance) {
  if (!actions.value.remove) return
  try {
    await ElMessageBox.confirm(`删除实例 ${instance.name} 会丢弃它的会话状态，且无法恢复。`, '删除实例', { type: 'warning', confirmButtonText: '删除' })
  } catch { return }
  if (!actions.value.remove) return
  await SubstrateApi.deleteInstance(props.agentId, instance.name)
  ElMessage.success('实例已删除')
  await loadInstances()
}

function openChat(instance: SubstrateInstance) {
  if (!actions.value.chat) return
  if (chatInstance.value?.instanceKey !== instance.instanceKey) chatMessages.value = []
  chatInstance.value = instance
  chatOpen.value = true
}

async function sendChat() {
  if (!actions.value.chat) return
  const text = chatText.value.trim()
  const instance = chatInstance.value
  if (!text || !instance || chatting.value) return
  chatMessages.value.push({ role: 'user', text })
  chatText.value = ''
  chatting.value = true
  try {
    const reply = await SubstrateApi.chat(props.agentId, instance.name, text)
    chatMessages.value.push({ role: 'agent', text: reply.reply || '（空回复）' })
    void loadInstances()
  } catch {
    chatMessages.value.push({ role: 'error', text: '发送失败，请查看提示信息后重试' })
  } finally { chatting.value = false }
}

onMounted(async () => {
  capabilities.value = await SubstrateApi.capabilities()
  if (capabilities.value && !capabilities.value.runnerAvailable) form.sourceType = 'IMAGE'
  await load()
})
watch(() => permissions.state.user, () => { pendingCreation.value = globalThis.sessionStorage?.getItem(creationStorageKey()) || '' })
watch(() => props.agentId, () => { pendingCreation.value = globalThis.sessionStorage?.getItem(creationStorageKey()) || '';  deployments.value = []; instances.value = []; void load() })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.substrate-deploy { display: grid; gap: 16px; }
.deploy-form { max-width: 860px; }
.field-hint { margin-left: 12px; color: var(--el-text-color-secondary); font-size: 12px; }
.inline-input { width: 240px; margin-left: 12px; }
.env-rows { display: grid; gap: 8px; width: 100%; }
.env-row { display: grid; grid-template-columns: 1fr 2fr auto; gap: 8px; }
.deployment-item { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-lighter); display: grid; gap: 10px; }
.deployment-item:last-child { border-bottom: none; }
.deployment-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.deployment-reason { margin: 0; color: var(--el-color-danger); }
.step-line { display: flex; align-items: center; gap: 8px; padding: 3px 0; flex-wrap: wrap; }
.small { font-size: 12px; margin-top: 6px; word-break: break-all; }
.short-input { width: 220px; }
.no-margin { margin-left: 0; }
.build-log { max-height: 320px; overflow: auto; margin: 8px 0 0; padding: 10px; background: var(--el-fill-color-darker); color: var(--el-text-color-primary);
  font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-all; border-radius: 4px; }
.chat-log { display: flex; flex-direction: column; gap: 10px; }
.chat-message { max-width: 85%; padding: 8px 12px; border-radius: 8px; white-space: pre-wrap; line-height: 1.6; }
.chat-message.user { align-self: flex-end; background: var(--el-color-primary-light-9); }
.chat-message.agent { align-self: flex-start; background: var(--el-fill-color-light); }
.chat-message.error { align-self: center; color: var(--el-color-danger); font-size: 12px; }
.chat-input { display: grid; grid-template-columns: 1fr auto; gap: 8px; align-items: end; }
@media (max-width: 640px) {
  .deployment-item :deep(.el-steps--simple) { display: grid; grid-template-columns: 1fr; gap: 10px; }
  .deployment-item :deep(.el-step.is-simple) { max-width: none !important; width: 100%; }
  .deployment-item :deep(.el-step__arrow) { display: none; }
  .env-row { grid-template-columns: 1fr; }
  .inline-input, .short-input { width: 100%; margin: 8px 0 0; }
}
</style>
