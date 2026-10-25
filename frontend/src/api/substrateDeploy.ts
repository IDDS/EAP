// Pure helpers for console deployment onto Agent Substrate; no imports so node tests can load them directly.

export type DeploySource = 'RUNNER' | 'IMAGE' | 'SOURCE' | 'TEMPLATE'
export type FrameworkSampleKey = 'LANGGRAPH_SUM_V1' | 'AUTOGEN_SUM_V1'
export type EnvRow = { name: string; value: string }
export type DeployForm = {
  sourceType: DeploySource
  runnerFramework: 'EAP_NATIVE' | 'KAGENT_ADK'
  frameworkSample: FrameworkSampleKey
  runnerToolMode: 'NONE' | 'CALCULATE_SUM_V1'
  systemPrompt: string
  model: string
  maxTokens: number
  thinkingMode: 'PROVIDER_DEFAULT' | 'DISABLED'
  image: string
  gitUrl: string
  gitRef: string
  contextPath: string
  dockerfile: string
  port: number
  readinessPath: string
  command: string
  sandboxClass: 'GVISOR' | 'MICROVM'
  size: 'SMALL' | 'MEDIUM' | 'LARGE'
  env: EnvRow[]
  egressHosts: string
}

export type DeployPayload = {
  sourceType: DeploySource
  runnerFramework?: 'KAGENT_ADK' | FrameworkSampleKey
  runnerToolMode?: 'CALCULATE_SUM_V1'
  systemPrompt?: string
  model?: string
  maxTokens?: number
  thinkingMode?: 'PROVIDER_DEFAULT' | 'DISABLED'
  image?: string
  gitUrl?: string
  gitRef?: string
  contextPath?: string
  dockerfile?: string
  port?: number
  readinessPath?: string
  command?: string[]
  sandboxClass: string
  size: string
  env: Record<string, string>
  egressHosts: string[]
  requestKey: string
}

export const ACTIVE_DEPLOYMENT_STATUSES = ['BUILDING_IMAGE', 'QUEUED', 'BUILDING_GOLDEN', 'CREATING_INSTANCE', 'CONNECTING_IDENTITY']

export function defaultDeployForm(): DeployForm {
  return {
    sourceType: 'RUNNER', runnerFramework: 'EAP_NATIVE', frameworkSample: 'LANGGRAPH_SUM_V1', runnerToolMode: 'NONE', systemPrompt: '', model: 'deepseek-flash', image: '', gitUrl: '', gitRef: 'main', contextPath: '',
    dockerfile: 'Dockerfile', port: 80, readinessPath: '/readyz', maxTokens: 1024, thinkingMode: 'PROVIDER_DEFAULT',
    command: '', sandboxClass: 'GVISOR', size: 'SMALL', env: [], egressHosts: '',
  }
}

/** Only fields of the chosen source are sent; blank env rows and blank host lines are dropped. */
export function buildDeployPayload(form: DeployForm, requestKey: string): DeployPayload {
  if (form.sourceType === 'TEMPLATE') return {
    sourceType: 'TEMPLATE', runnerFramework: form.frameworkSample, sandboxClass: 'GVISOR', size: form.size,
    env: {}, egressHosts: [], requestKey,
  }
  const env: Record<string, string> = {}
  for (const row of form.env) {
    const name = row.name.trim()
    if (name) env[name] = row.value
  }
  const payload: DeployPayload = {
    sourceType: form.sourceType,
    sandboxClass: form.sandboxClass,
    size: form.size,
    env,
    egressHosts: form.egressHosts.split(/[\s,]+/).map(host => host.trim().toLowerCase()).filter(Boolean),
    requestKey,
  }
  if (form.sourceType === 'RUNNER') {
    if (form.runnerFramework === 'KAGENT_ADK') {
      payload.runnerFramework = form.runnerFramework
      if (form.runnerToolMode === 'CALCULATE_SUM_V1') payload.runnerToolMode = form.runnerToolMode
    }
    payload.systemPrompt = form.systemPrompt.trim()
    payload.model = form.model.trim()
    payload.maxTokens = form.maxTokens
    payload.thinkingMode = form.thinkingMode
  } else {
    if (form.sourceType === 'IMAGE') payload.image = form.image.trim()
    else {
      payload.gitUrl = form.gitUrl.trim()
      payload.gitRef = form.gitRef.trim() || 'main'
      payload.contextPath = form.contextPath.trim()
      payload.dockerfile = form.dockerfile.trim() || 'Dockerfile'
    }
    payload.port = form.port
    payload.readinessPath = form.readinessPath.trim() || '/'
    payload.command = form.command.split('\n').map(arg => arg.trim()).filter(Boolean)
  }
  return payload
}

/** Client-side checks that mirror the server so users see problems before submitting. */
export function validateDeployForm(form: DeployForm): string | null {
  if (!['RUNNER', 'IMAGE', 'SOURCE', 'TEMPLATE'].includes(form.sourceType)) return '请选择有效部署方式'
  if (form.sourceType === 'TEMPLATE') return ['LANGGRAPH_SUM_V1', 'AUTOGEN_SUM_V1'].includes(form.frameworkSample)
    ? null : '请选择已支持的受控框架样例'
  if (form.sourceType === 'RUNNER') {
    if (!['EAP_NATIVE', 'KAGENT_ADK'].includes(form.runnerFramework)) return '请选择有效运行框架'
    if (form.runnerFramework === 'KAGENT_ADK' && !['NONE', 'CALCULATE_SUM_V1'].includes(form.runnerToolMode)) return '请选择有效工具模式'
    if (!form.systemPrompt.trim()) return '请填写系统提示词'
    if (!form.model.trim()) return '请选择模型'
    if (!Number.isInteger(form.maxTokens) || form.maxTokens < 1 || form.maxTokens > 8192) return '输出上限必须为 1–8192 的整数'
    if (!['PROVIDER_DEFAULT', 'DISABLED'].includes(form.thinkingMode)) return '思考模式无效'
    if (form.thinkingMode === 'DISABLED' && !['deepseek-flash', 'deepseek-pro'].includes(form.model.trim())) return '关闭思考仅支持 deepseek-flash 和 deepseek-pro'
  } else {
    if (form.sourceType === 'IMAGE' && !form.image.trim()) return '请填写镜像地址'
    if (form.sourceType === 'SOURCE') {
      const url = form.gitUrl.trim()
      // http is accepted here only syntactically; the server allows it for registered internal Git hosts alone.
      if (!/^https?:\/\/[A-Za-z0-9.-]+(:\d{1,5})?\/[A-Za-z0-9._~/-]+$/.test(url)) return 'Git 地址必须是不含账号、参数的 https 地址'
      if (form.contextPath.includes('..') || form.dockerfile.includes('..')) return '构建目录和 Dockerfile 路径不能包含 ..'
    }
    if (form.port !== 80) return '专属实例身份接入需要程序监听端口 80'
    if (form.readinessPath.trim() && !form.readinessPath.trim().startsWith('/')) return '就绪检查路径必须以 / 开头'
  }
  for (const row of form.env) {
    const name = row.name.trim()
    if (!name) continue
    if (!/^[A-Z_][A-Z0-9_]{0,63}$/.test(name)) return `环境变量名无效：${name}`
    if (name.startsWith('EAP_') || name.startsWith('AGENT_')) return `EAP_、AGENT_ 前缀由平台保留：${name}`
    if (/(KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL)/.test(name)) return `不能通过环境变量传递密钥：${name}（模型调用请经 EAP 网关）`
  }
  return null
}

export const DEPLOY_STAGES = ['校验与治理预检', '创建模板', '生成快照', '创建实例', '可运行']
const SOURCE_STAGES = ['校验与治理预检', '构建镜像', '创建模板', '生成快照', '创建实例', '可运行']

export function deployStages(sourceType: string, identityMode = ''): string[] {
  const stages = [...(sourceType === 'SOURCE' ? SOURCE_STAGES : DEPLOY_STAGES)]
  if (identityMode === 'PER_INSTANCE_V1') stages.splice(stages.length - 1, 0, '专属身份接入')
  return stages
}

/** Maps a deployment status onto deployStages(sourceType) for a progress indicator. */
export function deploymentProgress(status: string, sourceType = 'RUNNER', identityMode = ''): { active: number; state: 'process' | 'finish' | 'error' | 'wait'; label: string; tone: string } {
  const offset = sourceType === 'SOURCE' ? 1 : 0
  switch (status) {
    case 'BUILDING_IMAGE': return { active: 1, state: 'process', label: '构建镜像', tone: 'warning' }
    case 'QUEUED': return { active: 1 + offset, state: 'process', label: '排队中', tone: 'info' }
    case 'BUILDING_GOLDEN': return { active: 2 + offset, state: 'process', label: '生成快照', tone: 'warning' }
    case 'CREATING_INSTANCE': return { active: 3 + offset, state: 'process', label: '创建实例', tone: 'warning' }
    case 'CONNECTING_IDENTITY': return { active: 4 + offset, state: 'process', label: '专属身份接入', tone: 'warning' }
    case 'READY': return { active: deployStages(sourceType, identityMode).length, state: 'finish', label: '可运行', tone: 'success' }
    case 'WAITING_APPROVAL': return { active: 0, state: 'wait', label: '待治理审批', tone: 'warning' }
    case 'BLOCKED': return { active: 0, state: 'error', label: '策略拒绝', tone: 'danger' }
    case 'FAILED': return { active: 0, state: 'error', label: '失败', tone: 'danger' }
    default: return { active: 0, state: 'wait', label: status, tone: 'info' }
  }
}

const FAILED_STEP_STAGE: Record<string, string> = {
  VALIDATE: '校验与治理预检', PREFLIGHT: '校验与治理预检', BUILD_IMAGE: '构建镜像', BUILDING_IMAGE: '构建镜像',
  QUEUED: '创建模板', CREATE_TEMPLATE: '创建模板', GOLDEN_SNAPSHOT: '生成快照', BUILDING_GOLDEN: '生成快照',
  CREATING_INSTANCE: '创建实例', CREATE_INSTANCE: '创建实例', CONNECTING_IDENTITY: '专属身份接入',
}

/** For a failed deployment, the index in deployStages(sourceType) of the stage whose step failed. */
export function failedStageIndex(steps: { step: string; status: string }[], sourceType: string, identityMode = ''): number {
  const failed = [...steps].reverse().find(step => step.status === 'FAILED' || step.status === 'DENY')
  const index = failed ? deployStages(sourceType, identityMode).indexOf(FAILED_STEP_STAGE[failed.step] ?? '') : -1
  return index < 0 ? 0 : index
}

export function actorStateLabel(state: string): { label: string; tone: string } {
  switch (state) {
    case 'RUNNING': return { label: '运行中', tone: 'success' }
    case 'SUSPENDED': return { label: '休眠', tone: 'info' }
    case 'PAUSED': return { label: '暂停', tone: 'info' }
    case 'RESUMING': case 'SUSPENDING': case 'PAUSING': case 'REVERTING': return { label: '切换中', tone: 'warning' }
    case 'CRASHED': return { label: '崩溃', tone: 'danger' }
    case 'DELETING': return { label: '删除中', tone: 'warning' }
    default: return { label: '未知', tone: 'info' }
  }
}
