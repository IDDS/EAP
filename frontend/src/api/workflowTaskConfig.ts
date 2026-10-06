export function taskConfig(raw?: string): Record<string, any> {
  const config = JSON.parse(raw || '{}')
  if (!config || Array.isArray(config) || typeof config !== 'object') throw new Error('配置必须是 JSON 对象')
  return config
}

export function toolConfigError(raw: string | undefined, nodeType: string, agentId?: number): string {
  try {
    const config = taskConfig(raw)
    if (!Object.hasOwn(config, 'eapTool')) return ''
    if (!agentId || !['AGENT', 'ARTIFACT'].includes(nodeType.toUpperCase())) return '工具任务需要绑定智能体'
    if (config.executionBackend && config.executionBackend !== 'RUNTIME') return '工具任务仅支持常规执行'
    const tool = config.eapTool
    if(tool?.name==='controlled_counter_increment'&&Object.keys(tool).length===2){
      const a=tool.arguments,u=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
      return a&&Object.keys(a).length===4&&u.test(a.grantId)&&u.test(a.operationId)&&/^poc_[A-Za-z0-9_-]{1,60}$/.test(a.resourceId)&&Number.isInteger(a.delta)&&a.delta>=1&&a.delta<=100?'':'请填写有效授权、稳定操作标识、测试对象与1–100整数增量'
    }
    if (!tool || tool.name !== 'calculate_sum' || Object.keys(tool).length !== 2) return '当前仅支持求和与受控计数工具' 
    const args = tool.arguments, numbers = args?.numbers
    if (!args || Object.keys(args).length !== 1 || !Array.isArray(numbers) || !numbers.length || numbers.length > 100) return '请输入 1–100 个数字，以逗号或换行分隔'
    if (numbers.some(n => typeof n !== 'number' || !Number.isFinite(n) || Math.abs(n) > 1e12)) return '数字必须有限，绝对值不超过 10^12'
    return ''
  } catch { return '请修正高级配置中的 JSON 对象' }
}

// Invalid edits remain invalid in the node draft; saving/executing cannot silently use old arguments.
export function updateToolNumbers(raw: string | undefined, text: string): string {
  const config = taskConfig(raw)
  const tokens = text.trim().split(/[,，\n]/).map(s => s.trim())
  const valid = tokens.every(s => /^[-+]?(?:\d+\.?\d*|\.\d+)(?:e[-+]?\d+)?$/i.test(s))
  config.eapTool = { name: 'calculate_sum', arguments: { numbers: valid ? tokens.map(Number) : text } }
  return JSON.stringify(config, null, 2)
}

export function setTaskMode(raw: string | undefined, mode: 'MODEL' | 'TOOL'): string {
  const config = taskConfig(raw)
  if (mode === 'TOOL') {
    if (config.executionBackend && config.executionBackend !== 'RUNTIME') throw new Error('请先选择常规执行')
    config.eapTool = { name: 'calculate_sum', arguments: { numbers: [] } }
  } else delete config.eapTool
  return JSON.stringify(config, null, 2)
}
