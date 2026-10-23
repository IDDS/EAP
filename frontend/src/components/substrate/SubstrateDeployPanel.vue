<template>
  <div class="substrate-deploy">
    <el-alert v-if="capabilities && !capabilities.enabled" type="warning" :closable="false" show-icon
      title="托管运行环境未启用，请联系平台管理员启用。" />
    <el-alert v-if="capabilities?.enabled && !capabilities.instanceIdentityAvailable" type="warning" :closable="false" show-icon
      title="专属身份配送暂不可用，新版本发布已暂停，请联系平台管理员。" />

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header"><span>部署新版本</span><span class="muted">发布后生成不可变版本；修改配置即发布新版本</span></div>
      </template>
      <div v-if="actions.deploy && (pendingDeployment || deployNotice)" class="deployment-recovery" aria-label="部署提交核对">
        <el-alert v-if="deployNotice" :title="deployNotice" type="info" :closable="false" />
        <template v-if="pendingDeployment">
          <div class="receipt-key">提交编号：{{ pendingDeployment.requestKey }}</div>
          <el-button :disabled="submitting" @click="queryDeploymentReceipt">查询原部署</el-button>
          <p class="muted">原编号已保留。可查询受理结果；重新提交须填写原配置，不会生成新编号。</p>
        </template>
      </div>
      <el-form :disabled="!actions.deploy || submitting" label-width="120px" class="deploy-form" @submit.prevent>
        <el-form-item label="部署方式">
          <el-radio-group v-model="form.sourceType">
            <el-radio-button value="RUNNER" :disabled="capabilities ? !capabilities.runnerAvailable && !capabilities.kagentRunnerAvailable : false">零代码配置</el-radio-button>
            <el-radio-button value="IMAGE">已有镜像</el-radio-button>
            <el-radio-button value="SOURCE" :disabled="capabilities ? !capabilities.sourceBuildAvailable : false">源码构建</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <template v-if="form.sourceType === 'RUNNER'">
          <el-form-item label="运行框架">
            <el-select v-model="form.runnerFramework" aria-label="运行框架" style="width: 260px">
              <el-option label="EAP 原生" value="EAP_NATIVE" :disabled="capabilities ? !capabilities.runnerAvailable : true" />
              <el-option label="Kagent Go ADK" value="KAGENT_ADK" :disabled="!capabilities?.kagentRunnerAvailable" />
            </el-select>
          </el-form-item>
          <p v-if="form.runnerFramework === 'KAGENT_ADK'" class="muted">使用 Kagent Go ADK 模型适配器执行文本任务，由 EAP 管理实例、上下文、模型额度与执行记录。可启用受控求和工具；子智能体、审批恢复和提示词实验尚未开放。</p>
          <el-form-item v-if="form.runnerFramework === 'KAGENT_ADK'" label="框架工具">
            <el-select v-model="form.runnerToolMode" aria-label="框架工具" style="width: 260px">
              <el-option label="关闭，仅文本" value="NONE" />
              <el-option label="受控求和 calculate_sum" value="CALCULATE_SUM_V1" />
            </el-select>
          </el-form-item>
          <p v-if="form.runnerFramework === 'KAGENT_ADK' && form.runnerToolMode === 'CALCULATE_SUM_V1'" class="muted">每次最多一次模型调用和一次求和。工具需另行取得 EAP 实例工具授权及额度；确认回执后直接返回计算结果，不追加模型总结。拒绝或结果未知时停止。</p>
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
          <el-form-item label="单次输出上限">
            <el-input-number v-model="form.maxTokens" :min="1" :max="8192" :precision="0" controls-position="right" />
            <span class="field-hint">Token 上限，可能包含思考用量；仍受平台预算约束。截断输出会停止后续步骤。</span>
          </el-form-item>
          <el-form-item label="思考模式">
            <el-select v-model="form.thinkingMode" style="width: 260px">
              <el-option label="遵循模型默认" value="PROVIDER_DEFAULT" />
              <el-option label="关闭思考（DeepSeek）" value="DISABLED" :disabled="!['deepseek-flash', 'deepseek-pro'].includes(form.model.trim())" />
            </el-select>
          </el-form-item>
        </template>
        <template v-else>
          <el-alert class="identity-hint" type="info" :closable="false" show-icon
            title="镜像和源码也使用专属实例身份"
            description="程序需监听端口 80 并实现身份挑战、加密接收及确认接口；接入确认后才能运行。模板不包含共享凭证。可基于平台运行程序制作镜像。" />
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
            <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" :disabled="true" />
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
          <el-button type="primary" :loading="submitting" :disabled="!actions.deploy || !capabilities?.enabled || !capabilities?.instanceIdentityAvailable" @click="submit">{{ pendingDeployment ? '按原编号提交' : '发布部署' }}</el-button>
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
          <el-tag v-if="deployment.config.runnerToolMode === 'CALCULATE_SUM_V1'" size="small" effect="plain">受控求和 · 单次</el-tag>
          <el-tag v-if="deployment.config.runnerFramework === 'KAGENT_ADK'" size="small" effect="plain">Kagent Go ADK</el-tag>
          <el-tag size="small" :type="deployment.config.identityMode === 'PER_INSTANCE_V1' ? 'success' : 'warning'" effect="plain">
            {{ deployment.config.identityMode === 'PER_INSTANCE_V1' ? '专属实例身份' : '旧身份版本 · 待迁移' }}
          </el-tag>
          <el-tag size="small" :type="progress(deployment.status, deployment.sourceType, String(deployment.config.identityMode ?? '')).tone">{{ progress(deployment.status, deployment.sourceType, String(deployment.config.identityMode ?? '')).label }}</el-tag>
          <span class="muted">{{ deployment.requestedBy }} · {{ formatTime(deployment.createdAt) }}</span>
        </div>
        <p v-if="deployment.config.identityMode !== 'PER_INSTANCE_V1'" class="muted">
          此版本仍使用旧身份配置。迁移需发布支持专属身份的新版本并核验；原实例、任务与预算记录保留。
        </p>
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
            <p v-if="deployment.sourceType === 'RUNNER'" class="small">
              模型 {{ deployment.config.model }} · 输出上限 {{ deployment.config.maxTokens ?? '未显式配置（Runner 默认 1024）' }} ·
              思考模式 {{ deployment.config.thinkingMode === 'DISABLED' ? '关闭' : '遵循模型默认' }}
            </p>
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

    <el-drawer v-model="chatOpen" append-to-body :z-index="4000" :title="`试运行 · ${chatInstance?.name ?? ''}`" size="min(480px, 100vw)">
      <div class="chat-log">
        <div v-for="(message, index) in chatMessages" :key="index" :class="['chat-message', message.role]">
          <span>{{ message.text }}</span>
        </div>
        <el-empty v-if="!chatMessages.length" description="仅使用当前窗口的已完成问答；切换实例或刷新会清空上下文" :image-size="60" />
      </div>
      <el-alert v-if="chatNotice" :title="chatNotice" type="info" :closable="false" class="chat-receipt" />
      <router-link v-if="chatTraceId" :to="{ path: '/physical', query: { traceId: chatTraceId, agentId: String(props.agentId) } }">查看原请求执行链路 ↗</router-link>
      <div v-if="pendingChatKey" class="chat-receipt">
        <div class="receipt-key">提交编号：{{ pendingChatKey }}</div>
        <el-button :disabled="!actions.chat || chatting" @click="queryChatReceipt">查询原回执</el-button>
      </div>
      <template #footer>
        <el-button :disabled="chatting || !!pendingChatKey" @click="resetChat">新对话</el-button>
        <div class="chat-input">
          <el-input :disabled="!actions.chat || !!pendingChatKey" v-model="chatText" type="textarea" :rows="2" maxlength="4000" placeholder="输入消息，Ctrl+Enter 发送"
            @keydown.ctrl.enter="sendChat" />
          <el-button type="primary" :loading="chatting" :disabled="!actions.chat || !!pendingChatKey" @click="sendChat">发送</el-button>
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
import type { DeploymentReceipt, ChatReply, ChatHistory, SubstrateCapabilities, SubstrateDeployment, SubstrateInstance } from '@/api/substrate'
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
const chatHistory = ref<ChatHistory>([])
let chatGeneration = 0
const pendingChatKey = ref('')
const chatNotice = ref('')
const chatTraceId = ref('')
const chatStorageKey = (instance: SubstrateInstance) => 'eap.chat.pending.' + JSON.stringify([permissions.state.user?.subject, props.agentId, instance.instanceKey])
function chatStorage() {
  if (!globalThis.sessionStorage) throw new Error('Chat receipt storage unavailable')
  return globalThis.sessionStorage
}
type PendingDeployment = { requestKey: string; digest: string }
const pendingDeployment = ref<PendingDeployment>()
const deployNotice = ref('')
let deploymentGeneration = 0
const deploymentStorageKey = () => 'eap.deploy.pending.' + JSON.stringify([permissions.state.user?.subject, props.agentId])
function readPendingDeployment(storageKey: string): PendingDeployment | undefined {
  const value = chatStorage().getItem(storageKey)
  if (!value) return undefined
  const parsed = JSON.parse(value)
  if (!parsed || typeof parsed.requestKey !== 'string' || !/^[a-f0-9-]{36}$/.test(parsed.requestKey)
      || typeof parsed.digest !== 'string' || !/^[a-f0-9]{64}$/.test(parsed.digest)
      || Object.keys(parsed).sort().join(',') !== 'digest,requestKey') throw new Error('Invalid deployment index')
  return parsed
}
function restoreDeployment() {
  deploymentGeneration++
  submitting.value = false
  pendingDeployment.value = undefined
  deployNotice.value = ''
  try {
    pendingDeployment.value = readPendingDeployment(deploymentStorageKey())
    if (pendingDeployment.value) deployNotice.value = '发现待核对提交，请查询原部署；未保存原配置正文。'
  } catch { deployNotice.value = '无法读取部署编号存储，已暂停提交；请恢复当前会话存储后再核对。' }
}
restoreDeployment()
function sameDeploymentContext(generation: number, storageKey: string) {
  return generation === deploymentGeneration && storageKey === deploymentStorageKey() && actions.value.deploy
}
function canonicalDeployment(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonicalDeployment).join(',') + ']'
  if (value && typeof value === 'object') return '{' + Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
    .map(([key, item]) => JSON.stringify(key) + ':' + canonicalDeployment(item)).join(',') + '}'
  return JSON.stringify(value)
}
async function deploymentDigest(payload: unknown): Promise<string> {
  const bytes = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(canonicalDeployment(payload)))
  return Array.from(new Uint8Array(bytes), n => n.toString(16).padStart(2, '0')).join('')
}
function acceptDeploymentReceipt(receipt: DeploymentReceipt, key: string, storageKey: string) {
  if (receipt.requestKey !== key || receipt.agentId !== props.agentId) throw new Error('Deployment receipt identity mismatch')
  if (receipt.status === 'NOT_FOUND') {
    deployNotice.value = '暂未查到原部署；原请求仍可能在提交中。请稍后查询，或填写原配置后按原编号提交。'
    return
  }
  if (!receipt.deploymentKey || !Number.isInteger(receipt.version) || (receipt.version ?? 0) < 1) throw new Error('Incomplete deployment receipt')
  if (readPendingDeployment(storageKey)?.requestKey !== key) throw new Error('Deployment index changed')
  chatStorage().removeItem(storageKey)
  if (chatStorage().getItem(storageKey) !== null) throw new Error('Deployment index not cleared')
  pendingDeployment.value = undefined
  deployNotice.value = `已确认原部署 v${receipt.version}：${progress(receipt.status).label}。后续进度请查看部署版本。`
}
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
  const agentId = props.agentId, generation = deploymentGeneration
  const [list] = await Promise.all([SubstrateApi.deployments(agentId), loadInstances()])
  if (agentId !== props.agentId || generation !== deploymentGeneration) return
  const becameReady = list.some(item => item.status === 'READY'
    && deployments.value.some(old => old.deploymentKey === item.deploymentKey && old.status !== 'READY'))
  deployments.value = list
  if (becameReady) await loadInstances()
  schedulePolling()
}

async function loadInstances() {
  const agentId = props.agentId, generation = deploymentGeneration
  instancesLoading.value = true
  try {
    const list = await SubstrateApi.instances(agentId)
    if (agentId === props.agentId && generation === deploymentGeneration) instances.value = list
  } finally { if (generation === deploymentGeneration) instancesLoading.value = false }
}

function schedulePolling() {
  const active = deployments.value.some(item => ACTIVE_DEPLOYMENT_STATUSES.includes(item.status))
  if (active && !timer) timer = setInterval(() => { void load().catch(() => undefined) }, 3000)
  if (!active && timer) { clearInterval(timer); timer = undefined }
}

async function submit() {
  if (!actions.value.deploy || submitting.value || !permissions.state.user?.subject) return
  const problem = validateDeployForm(form)
  if (problem) { ElMessage.warning(problem); return }
  const generation = deploymentGeneration, agentId = props.agentId, storageKey = deploymentStorageKey()
  submitting.value = true
  let sent = false
  try {
    const pending = readPendingDeployment(storageKey)
    const payload = buildDeployPayload(form, pending?.requestKey ?? globalThis.crypto.randomUUID())
    const digest = await deploymentDigest({ ...payload, requestKey: '' })
    if (!sameDeploymentContext(generation, storageKey)) return
    if (pending && digest !== pending.digest) {
      pendingDeployment.value = pending
      deployNotice.value = '当前配置与原提交不同，未发送。请查询原部署，或重新填写原配置后按原编号提交。'
      return
    }
    // Recheck after asynchronous hashing: never replace another in-flight intent.
    const current = readPendingDeployment(storageKey)
    if (current?.requestKey !== pending?.requestKey || current?.digest !== pending?.digest) throw new Error('Deployment index changed')
    const index = { requestKey: payload.requestKey, digest }
    chatStorage().setItem(storageKey, JSON.stringify(index))
    if (JSON.stringify(readPendingDeployment(storageKey)) !== JSON.stringify(index)) throw new Error('Deployment index not persisted')
    pendingDeployment.value = index
    sent = true
    const deployment = await SubstrateApi.deploy(agentId, payload)
    if (!sameDeploymentContext(generation, storageKey)) return
    acceptDeploymentReceipt({ ...deployment, requestKey: payload.requestKey }, payload.requestKey, storageKey)
    void load().catch(() => undefined)
  } catch {
    if (sameDeploymentContext(generation, storageKey)) deployNotice.value = sent
      ? '未取得完整部署回执；原编号已保留，请查询原部署。重试须使用原配置和原编号。'
      : '部署编号或配置摘要无法安全保存，未发送；请检查当前会话存储与浏览器安全连接。'
  } finally { if (sameDeploymentContext(generation, storageKey)) submitting.value = false }
}

async function queryDeploymentReceipt() {
  if (!actions.value.deploy || submitting.value || !permissions.state.user?.subject) return
  const generation = deploymentGeneration, agentId = props.agentId, storageKey = deploymentStorageKey()
  submitting.value = true
  try {
    const pending = readPendingDeployment(storageKey)
    if (!pending) return
    pendingDeployment.value = pending
    const receipt = await SubstrateApi.deploymentReceipt(agentId, pending.requestKey)
    if (!sameDeploymentContext(generation, storageKey)) return
    acceptDeploymentReceipt(receipt, pending.requestKey, storageKey)
    void load().catch(() => undefined)
  } catch {
    if (sameDeploymentContext(generation, storageKey)) deployNotice.value = '暂时无法确认原部署，编号仍保留。请稍后再查询。'
  } finally { if (sameDeploymentContext(generation, storageKey)) submitting.value = false }
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
  if (chatInstance.value?.instanceKey !== instance.instanceKey) resetChat()
  chatInstance.value = instance
  try {
    pendingChatKey.value = chatStorage().getItem(chatStorageKey(instance)) || ''
    if (pendingChatKey.value) chatNotice.value = '存在待核对的原提交，请查询回执；不会重新发送。'
  } catch { chatNotice.value = '无法保存提交编号，请恢复浏览器会话存储后再发送。' }
  chatOpen.value = true
}

function resetChat() {
  chatGeneration++
  chatMessages.value = []
  chatHistory.value = []
  chatText.value = ''
  chatting.value = false
  pendingChatKey.value = ''
  chatNotice.value = ''
  chatTraceId.value = ''
}

function acceptChatReceipt(receipt: ChatReply, key: string, storageKey: string, text?: string) {
  if (receipt.requestKey !== key) throw new Error('Chat receipt identity mismatch')
  chatNotice.value = receipt.notice
  chatTraceId.value = receipt.traceId || ''
  if (receipt.status !== 'SUCCEEDED' && receipt.status !== 'NOT_DISPATCHED') return
  if (receipt.status === 'SUCCEEDED') {
    if (text && receipt.reply) {
      chatMessages.value.push({ role: 'agent', text: receipt.reply })
      chatHistory.value.push({ role: 'user', content: text }, { role: 'assistant', content: receipt.reply })
      while (chatHistory.value.length > 40 || new TextEncoder().encode(chatHistory.value.map(entry => entry.content).join('')).length > 65536)
        chatHistory.value.splice(0, 2)
    } else {
      // A receipt proves completion but deliberately carries no stored conversation content.
      chatHistory.value = []
      chatMessages.value = []
    }
  }
  if (chatStorage().getItem(storageKey) === key) chatStorage().removeItem(storageKey)
  pendingChatKey.value = ''
}

async function queryChatReceipt() {
  const instance = chatInstance.value
  if (!actions.value.chat || chatting.value || !instance || !pendingChatKey.value) return
  const generation = chatGeneration, key = pendingChatKey.value, storageKey = chatStorageKey(instance)
  chatting.value = true
  try {
    const receipt = await SubstrateApi.chatReceipt(props.agentId, instance.name, key)
    if (generation !== chatGeneration || !actions.value.chat) return
    acceptChatReceipt(receipt, key, storageKey)
  } catch {
    if (generation === chatGeneration) chatNotice.value = '尚未核实原提交，请保留编号稍后再查询，勿重发。'
  } finally { if (generation === chatGeneration) chatting.value = false }
}

async function sendChat() {
  if (!actions.value.chat) return
  const text = chatText.value.trim()
  const instance = chatInstance.value
  if (!text || !instance || chatting.value) return
  const generation = chatGeneration
  const agentId = props.agentId
  const storageKey = chatStorageKey(instance)
  let key: string
  try {
    const existing = chatStorage().getItem(storageKey)
    if (existing) {
      pendingChatKey.value = existing
      chatNotice.value = '存在待核对的原提交，请先查询回执，勿重发。'
      return
    }
    key = crypto.randomUUID()
    chatStorage().setItem(storageKey, key)
    if (chatStorage().getItem(storageKey) !== key) throw new Error('Chat receipt not saved')
  } catch {
    chatNotice.value = '无法保存提交编号，本次未发送；请恢复浏览器会话存储后再试。'
    return
  }
  pendingChatKey.value = key
  chatMessages.value.push({ role: 'user', text })
  chatText.value = ''
  chatting.value = true
  try {
    const reply = await SubstrateApi.chat(agentId, instance.name, text, chatHistory.value.map(entry => ({ ...entry })), key)
    if (generation !== chatGeneration || !actions.value.chat) return
    acceptChatReceipt(reply, key, storageKey, text)
    void loadInstances()
  } catch {
    if (generation === chatGeneration && actions.value.chat)
      chatNotice.value = '未取得完整回执；原提交编号已保留，请查询原回执，勿直接重发。'
  } finally { if (generation === chatGeneration) chatting.value = false }
}

onMounted(async () => {
  capabilities.value = await SubstrateApi.capabilities()
  if (capabilities.value && !capabilities.value.runnerAvailable) {
    if (capabilities.value.kagentRunnerAvailable) form.runnerFramework = 'KAGENT_ADK'
    else form.sourceType = 'IMAGE'
  }
  await load()
})
watch(() => permissions.state.user, () => { restoreDeployment(); Object.assign(form, defaultDeployForm()); deployments.value = []; instances.value = []; resetChat(); chatOpen.value = false; pendingCreation.value = globalThis.sessionStorage?.getItem(creationStorageKey()) || '' })
watch(() => props.agentId, () => { restoreDeployment(); Object.assign(form, defaultDeployForm()); deployments.value = []; instances.value = []; resetChat(); chatOpen.value = false; chatInstance.value = undefined; pendingCreation.value = globalThis.sessionStorage?.getItem(creationStorageKey()) || ''; deployments.value = []; instances.value = []; void load() })
onBeforeUnmount(() => { deploymentGeneration++; resetChat(); if (timer) clearInterval(timer) })
</script>

<style scoped>
.deployment-recovery { display: grid; gap: 12px; margin-bottom: 16px; }
.deployment-recovery :deep(.el-button) { justify-self: start; }
.identity-hint { margin-bottom: 18px; }
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
.chat-receipt { margin-top: 12px; }
.receipt-key { overflow-wrap: anywhere; font-size: 12px; margin-bottom: 8px; }
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
