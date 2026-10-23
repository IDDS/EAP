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

export function outputContractError(raw: string | undefined, nodeType: string, agentId?: number, allowUnassignedPlanStep = false): string {
  try {
    const config = taskConfig(raw)
    if (!Object.hasOwn(config, 'eapOutput')) return ''
    const contract = config.eapOutput
    const exact = (value: any, keys: string[]) => value && typeof value === 'object' && !Array.isArray(value)
      && Object.keys(value).length === keys.length && keys.every(k => Object.hasOwn(value, k))
    if ((!agentId && !allowUnassignedPlanStep) || !['AGENT', 'ARTIFACT'].includes(nodeType.toUpperCase()) || (config.executionBackend && config.executionBackend !== 'RUNTIME')) return '结果契约仅支持绑定智能体的常规执行任务'
    if (!exact(contract, ['version', 'source', 'schema']) || contract.version !== 'EAP_JSON_V1' || !['OUTPUT', 'JSON_TEXT'].includes(contract.source)) return '请选择有效的结果契约与结果来源'
    let count = 0
    function shape(s: any, depth: number): boolean {
      if (!s || depth > 8 || ++count > 128) return false
      if (s.type === 'object') {
        if (!exact(s, ['type', 'properties', 'required', 'additionalProperties']) || typeof s.additionalProperties !== 'boolean'
          || !s.properties || typeof s.properties !== 'object' || Array.isArray(s.properties) || Object.keys(s.properties).length > 64
          || !Array.isArray(s.required) || new Set(s.required).size !== s.required.length
          || s.required.some((k: any) => typeof k !== 'string' || !Object.hasOwn(s.properties, k))) return false
        return Object.entries(s.properties).every(([key, value]) => key.length > 0 && key.length <= 128 && shape(value, depth + 1))
      }
      if (s.type === 'array') return exact(s, ['type', 'items']) && shape(s.items, depth + 1)
      return ['string', 'number', 'integer', 'boolean', 'null'].includes(s.type) && exact(s, ['type'])
    }
    if (new TextEncoder().encode(JSON.stringify(contract)).length > 8192 || contract.schema?.type !== 'object' || !shape(contract.schema, 1)) return '结构无效：根必须是对象，最多 8 层、128 个结构节点、每层 64 个属性、8KB；不支持的字段会被拒绝'
    return ''
  } catch { return '请修正结果契约或高级配置中的 JSON' }
}

export function setOutputContract(raw: string | undefined, source: 'OFF' | 'OUTPUT' | 'JSON_TEXT'): string {
  const config = taskConfig(raw)
  if (source === 'OFF') delete config.eapOutput
  else {
    if (config.executionBackend && config.executionBackend !== 'RUNTIME') throw new Error('结果契约仅支持常规执行')
    config.eapOutput = { version: 'EAP_JSON_V1', source, schema: config.eapOutput?.schema ?? {
      type: 'object', properties: { summary: { type: 'string' } }, required: ['summary'], additionalProperties: false,
    } }
  }
  return JSON.stringify(config, null, 2)
}

export function updateOutputSchema(raw: string | undefined, text: string): string {
  const config = taskConfig(raw)
  if (!config.eapOutput) throw new Error('请先启用结果契约')
  // Preserve invalid drafts, so saving cannot silently use a previously valid contract.
  try { config.eapOutput.schema = JSON.parse(text) } catch { config.eapOutput.schema = text }
  return JSON.stringify(config, null, 2)
}

export function observationContractError(raw: string | undefined, nodeType: string, agentId?: number): string {
  try {
    const config = taskConfig(raw)
    if (!Object.hasOwn(config, 'eapObservation')) return ''
    if (!agentId || !['AGENT', 'ARTIFACT'].includes(nodeType.toUpperCase()) || Object.hasOwn(config, 'eapTool')
      || (Object.hasOwn(config, 'executionBackend') && config.executionBackend !== 'RUNTIME'))
      return '模型观测约定仅支持绑定智能体的普通 Runtime 任务；请先移除约定再切换 AX 或内置工具'
    const c = config.eapObservation
    if (!c || Array.isArray(c) || typeof c !== 'object' || Object.keys(c).length !== 2
      || c.version !== 'EAP_OBSERVATION_V1' || !['NONE', 'ONE'].includes(c.modelEvents))
      return '请选择有效的模型观测约定'
    return ''
  } catch { return '请修正模型观测约定或高级配置中的 JSON' }
}

export function setObservationContract(raw: string | undefined, value: 'DEFAULT' | 'ONE' | 'NONE'): string {
  const config = taskConfig(raw)
  if (value === 'DEFAULT') delete config.eapObservation
  else {
    if (!['ONE', 'NONE'].includes(value) || Object.hasOwn(config, 'eapTool')
      || (Object.hasOwn(config, 'executionBackend') && config.executionBackend !== 'RUNTIME'))
      throw new Error('模型观测约定仅支持普通 Runtime 任务')
    config.eapObservation = { version: 'EAP_OBSERVATION_V1', modelEvents: value }
  }
  return JSON.stringify(config, null, 2)
}

// Plans store only the result definition. Runtime/handoff configuration remains server-owned.
export function planOutputConfig(definition?: string | null, backend = 'RUNTIME'): string {
  return JSON.stringify({ executionBackend: backend, ...(definition == null ? {} : { eapOutput: JSON.parse(definition) }) })
}
export function planOutputValue(raw: string, backend = 'RUNTIME'): string | null {
  const config: Record<string, unknown> = { ...taskConfig(raw), executionBackend: backend }
  const error = outputContractError(JSON.stringify(config), 'AGENT', undefined, true)
  if (error) throw new Error(error)
  return Object.hasOwn(config, 'eapOutput') ? JSON.stringify(config.eapOutput) : null
}
export function planOutputLabel(definition?: string | null): string {
  if (definition == null) return '未配置'
  try {
    const raw = planOutputConfig(definition)
    if (outputContractError(raw, 'AGENT', undefined, true)) return '配置需核对'
    return taskConfig(raw).eapOutput.source === 'JSON_TEXT' ? 'JSON 文本结构' : 'JSON 对象结构'
  } catch { return '配置需核对' }
}
