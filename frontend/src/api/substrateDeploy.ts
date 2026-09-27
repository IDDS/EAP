// Pure helpers for console deployment onto Agent Substrate; no imports so node tests can load them directly.

export type DeploySource = 'RUNNER' | 'IMAGE'
export type EnvRow = { name: string; value: string }
export type DeployForm = {
  sourceType: DeploySource
  systemPrompt: string
  model: string
  image: string
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
  systemPrompt?: string
  model?: string
  image?: string
  port?: number
  readinessPath?: string
  command?: string[]
  sandboxClass: string
  size: string
  env: Record<string, string>
  egressHosts: string[]
  requestKey: string
}

export const ACTIVE_DEPLOYMENT_STATUSES = ['QUEUED', 'BUILDING_GOLDEN', 'CREATING_INSTANCE']

export function defaultDeployForm(): DeployForm {
  return {
    sourceType: 'RUNNER', systemPrompt: '', model: 'deepseek-flash', image: '', port: 80, readinessPath: '/readyz',
    command: '', sandboxClass: 'GVISOR', size: 'SMALL', env: [], egressHosts: '',
  }
}

/** Only fields of the chosen source are sent; blank env rows and blank host lines are dropped. */
export function buildDeployPayload(form: DeployForm, requestKey: string): DeployPayload {
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
    payload.systemPrompt = form.systemPrompt.trim()
    payload.model = form.model.trim()
  } else {
    payload.image = form.image.trim()
    payload.port = form.port
    payload.readinessPath = form.readinessPath.trim() || '/'
    payload.command = form.command.split('\n').map(arg => arg.trim()).filter(Boolean)
  }
  return payload
}

/** Client-side checks that mirror the server so users see problems before submitting. */
export function validateDeployForm(form: DeployForm): string | null {
  if (form.sourceType === 'RUNNER') {
    if (!form.systemPrompt.trim()) return '请填写系统提示词'
    if (!form.model.trim()) return '请选择模型'
  } else {
    if (!form.image.trim()) return '请填写镜像地址'
    if (!(form.port >= 1 && form.port <= 65535)) return '端口必须在 1–65535 之间'
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

/** Maps a deployment status onto DEPLOY_STAGES for a progress indicator. */
export function deploymentProgress(status: string): { active: number; state: 'process' | 'finish' | 'error' | 'wait'; label: string; tone: string } {
  switch (status) {
    case 'QUEUED': return { active: 1, state: 'process', label: '排队中', tone: 'info' }
    case 'BUILDING_GOLDEN': return { active: 2, state: 'process', label: '生成快照', tone: 'warning' }
    case 'CREATING_INSTANCE': return { active: 3, state: 'process', label: '创建实例', tone: 'warning' }
    case 'READY': return { active: 5, state: 'finish', label: '可运行', tone: 'success' }
    case 'WAITING_APPROVAL': return { active: 0, state: 'wait', label: '待治理审批', tone: 'warning' }
    case 'BLOCKED': return { active: 0, state: 'error', label: '策略拒绝', tone: 'danger' }
    case 'FAILED': return { active: 0, state: 'error', label: '失败', tone: 'danger' }
    default: return { active: 0, state: 'wait', label: status, tone: 'info' }
  }
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
