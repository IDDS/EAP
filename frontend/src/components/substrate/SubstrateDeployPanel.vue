<template>
  <div class="substrate-deploy">
    <el-alert v-if="capabilities && !capabilities.enabled" type="warning" :closable="false" show-icon
      title="Substrate 运行时未启用：请联系平台管理员配置 EAP_SUBSTRATE_ENABLED。" />

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header"><span>部署新版本</span><span class="muted">发布后生成不可变版本；修改配置即发布新版本</span></div>
      </template>
      <el-form label-width="120px" class="deploy-form" @submit.prevent>
        <el-form-item label="部署方式">
          <el-radio-group v-model="form.sourceType">
            <el-radio-button value="RUNNER" :disabled="capabilities ? !capabilities.runnerAvailable : false">零代码配置</el-radio-button>
            <el-radio-button value="IMAGE">已有镜像</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <template v-if="form.sourceType === 'RUNNER'">
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
          <el-form-item label="镜像地址">
            <el-input v-model="form.image" placeholder="localhost:5001/my-agent:latest 或 registry/repo@sha256:…" />
          </el-form-item>
          <el-form-item label="服务端口">
            <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
            <el-input v-model="form.readinessPath" class="inline-input" placeholder="就绪检查路径，如 /readyz" />
          </el-form-item>
          <el-form-item label="启动命令">
            <el-input v-model="form.command" type="textarea" :rows="2" placeholder="每行一个参数；留空使用镜像默认命令" />
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
          <el-button type="primary" :loading="submitting" :disabled="capabilities ? !capabilities.enabled : false" @click="submit">发布部署</el-button>
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
          <el-tag size="small" effect="plain">{{ deployment.sourceType === 'RUNNER' ? '零代码' : '镜像' }}</el-tag>
          <el-tag size="small" :type="progress(deployment.status).tone">{{ progress(deployment.status).label }}</el-tag>
          <span class="muted">{{ deployment.requestedBy }} · {{ formatTime(deployment.createdAt) }}</span>
        </div>
        <el-steps :active="progress(deployment.status).active" :process-status="progress(deployment.status).state" finish-status="success" simple>
          <el-step v-for="stage in stages" :key="stage" :title="stage" />
        </el-steps>
        <p v-if="deployment.reason" class="deployment-reason">
          {{ deployment.reason }}
          <el-button v-if="deployment.status === 'FAILED'" link type="primary" :loading="retrying === deployment.version"
            @click="retry(deployment)">从失败步骤重试</el-button>
        </p>
        <el-collapse>
          <el-collapse-item title="步骤与配置">
            <div v-for="(step, index) in deployment.steps" :key="index" class="step-line">
              <el-tag size="small" :type="step.status === 'DONE' || step.status === 'ALLOW' ? 'success' : step.status === 'FAILED' || step.status === 'DENY' ? 'danger' : 'warning'">{{ step.status }}</el-tag>
              <span class="mono">{{ step.step }}</span><span>{{ step.detail }}</span><small class="muted">{{ formatTime(step.at) }}</small>
            </div>
            <div class="mono small">模板 {{ deployment.templateName }} · 镜像 {{ deployment.imageRef }}</div>
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
            <el-button size="small" :loading="creating" :disabled="!hasReadyVersion" @click="createInstance">新建实例</el-button>
            <el-button link type="primary" @click="loadInstances">刷新</el-button>
          </span>
        </div>
      </template>
      <el-table v-loading="instancesLoading" :data="instances" size="small">
        <el-table-column prop="name" label="实例" min-width="170" />
        <el-table-column label="版本" width="80"><template #default="{ row }">{{ row.version ? `v${row.version}` : '—' }}</template></el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag size="small" :type="actorState(row.state).tone">{{ actorState(row.state).label }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" min-width="300">
          <template #default="{ row }">
            <el-button link type="primary" @click="openChat(row)">试运行</el-button>
            <el-button link :loading="busy === row.instanceKey + 'RESUME'" @click="control(row, 'RESUME')">唤醒</el-button>
            <el-button link :loading="busy === row.instanceKey + 'PAUSE'" @click="control(row, 'PAUSE')">休眠</el-button>
            <el-button v-if="row.state === 'CRASHED'" link type="warning" :loading="busy === row.instanceKey + 'REVERT'" @click="control(row, 'REVERT')">回退</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
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
          <el-input v-model="chatText" type="textarea" :rows="2" maxlength="4000" placeholder="输入消息，Ctrl+Enter 发送"
            @keydown.ctrl.enter="sendChat" />
          <el-button type="primary" :loading="chatting" @click="sendChat">发送</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { SubstrateApi } from '@/api/substrate'
import type { SubstrateCapabilities, SubstrateDeployment, SubstrateInstance } from '@/api/substrate'
import {
  ACTIVE_DEPLOYMENT_STATUSES, DEPLOY_STAGES, actorStateLabel, buildDeployPayload, defaultDeployForm,
  deploymentProgress, validateDeployForm,
} from '@/api/substrateDeploy'

const props = defineProps<{ agentId: number }>()
const models = ['deepseek-flash', 'deepseek-v4-pro']
const stages = DEPLOY_STAGES
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
let timer: ReturnType<typeof setInterval> | undefined

const progress = deploymentProgress
const actorState = actorStateLabel
const hasReadyVersion = computed(() => deployments.value.some(item => item.status === 'READY'))

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

const retrying = ref<number>()
async function retry(deployment: SubstrateDeployment) {
  retrying.value = deployment.version
  try {
    await SubstrateApi.retry(props.agentId, deployment.version)
    ElMessage.success(`v${deployment.version} 已重新进入部署队列`)
    await load()
  } finally { retrying.value = undefined }
}

async function createInstance() {
  creating.value = true
  try {
    const instance = await SubstrateApi.createInstance(props.agentId)
    ElMessage.success(`已创建实例 ${instance.name}`)
    await loadInstances()
  } finally { creating.value = false }
}

async function control(instance: SubstrateInstance, command: 'PAUSE' | 'RESUME' | 'REVERT') {
  busy.value = instance.instanceKey + command
  try {
    const result = await SubstrateApi.control(props.agentId, instance.instanceKey, command)
    if (result.status === 'ACKED') ElMessage.success(result.resultMessage || '已完成')
    else ElMessage.error(result.resultMessage || '操作失败')
    await loadInstances()
  } finally { busy.value = '' }
}

async function remove(instance: SubstrateInstance) {
  try {
    await ElMessageBox.confirm(`删除实例 ${instance.name} 会丢弃它的会话状态，且无法恢复。`, '删除实例', { type: 'warning', confirmButtonText: '删除' })
  } catch { return }
  await SubstrateApi.deleteInstance(props.agentId, instance.name)
  ElMessage.success('实例已删除')
  await loadInstances()
}

function openChat(instance: SubstrateInstance) {
  if (chatInstance.value?.instanceKey !== instance.instanceKey) chatMessages.value = []
  chatInstance.value = instance
  chatOpen.value = true
}

async function sendChat() {
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
watch(() => props.agentId, () => { deployments.value = []; instances.value = []; void load() })
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
.chat-log { display: flex; flex-direction: column; gap: 10px; }
.chat-message { max-width: 85%; padding: 8px 12px; border-radius: 8px; white-space: pre-wrap; line-height: 1.6; }
.chat-message.user { align-self: flex-end; background: var(--el-color-primary-light-9); }
.chat-message.agent { align-self: flex-start; background: var(--el-fill-color-light); }
.chat-message.error { align-self: center; color: var(--el-color-danger); font-size: 12px; }
.chat-input { display: grid; grid-template-columns: 1fr auto; gap: 8px; align-items: end; }
@media (max-width: 640px) {
  .env-row { grid-template-columns: 1fr; }
  .inline-input { width: 100%; margin: 8px 0 0; }
}
</style>
