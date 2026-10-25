import type { EvaluatorDefinition } from '@/types/domain'

function key(prefix: string) {
  return `${prefix}-${globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`}`
}
export function newDatasetDraft() { return { datasetKey: key('REGRESSION').toUpperCase(), name: '', description: '', version: 'v1' } }
export function newSampleDraft() { return { itemKey: key('sample'), evaluatorKey: '', agentId: '', traceId: '', spanId: '', verifySourceReference: false, inputJson: '', actualJson: '', expectedJson: '' } }
function text(value: string, name: string, max: number, required = false) {
  const result = value.trim()
  if (required && !result) throw Error(`请填写${name}`)
  if (result.length > max) throw Error(`${name}不能超过 ${max} 个字符`)
  return result
}
function json(value: string, name: string, objectOnly: boolean) {
  if (!value.trim()) throw Error(`请填写${name}`)
  // Bound input before parsing; compact JSON is checked again to match the API limit.
  if (new TextEncoder().encode(value).length > 64 * 1024) throw Error(`${name}不能超过 64KB`)
  let parsed: unknown
  try { parsed = JSON.parse(value) } catch { throw Error(`${name}不是有效 JSON`) }
  if (objectOnly && (parsed === null || Array.isArray(parsed) || typeof parsed !== 'object')) throw Error(`${name}必须是 JSON 对象`)
  if (new TextEncoder().encode(JSON.stringify(parsed)).length > 64 * 1024) throw Error(`${name}不能超过 64KB`)
  return parsed
}
export function datasetPayload(draft: ReturnType<typeof newDatasetDraft>) {
  return { datasetKey: text(draft.datasetKey, '数据集标识', 128, true).toUpperCase(), name: text(draft.name, '数据集名称', 256, true), description: text(draft.description, '说明', 2048) || undefined, version: text(draft.version, '数据集版本', 64, true), sourceType: 'MANUAL', itemCount: 0 }
}
export function samplePayload(draft: ReturnType<typeof newSampleDraft>, evaluators: EvaluatorDefinition[]) {
  const evaluator = evaluators.find(e => e.evaluatorKey === draft.evaluatorKey && e.enabled !== false)
  if (!evaluator) throw Error('请选择当前可用的评估器')
  const rawAgent = draft.agentId.trim(), agentId = rawAgent ? Number(rawAgent) : undefined
  if (rawAgent && (!/^[1-9]\d*$/.test(rawAgent) || !Number.isSafeInteger(agentId))) throw Error('Agent ID 必须是正安全整数')
  const traceId = text(draft.traceId, 'Trace ID', 128), spanId = text(draft.spanId, 'Span ID', 128)
  if (spanId && !traceId) throw Error('填写 Span ID 时请同时填写 Trace ID')
  if (draft.verifySourceReference && (!agentId || !traceId || !spanId)) throw Error('核对观测引用需要同时填写 Agent ID、Trace ID 和 Span ID')
  if (evaluator.evaluatorKey === 'EXACT_MATCH' && !draft.expectedJson.trim()) throw Error('精确匹配评估需要期望结果 JSON')
  return { itemKey: text(draft.itemKey, '样本标识', 128, true), evaluatorKey: evaluator.evaluatorKey, evaluatorVersion: evaluator.version,
    verifySourceReference: draft.verifySourceReference, agentId, traceId: traceId || undefined, spanId: spanId || undefined,
    input: json(draft.inputJson, '输入 JSON', true) as Record<string, unknown>, actual: json(draft.actualJson, '实际结果 JSON', true) as Record<string, unknown>,
    expected: draft.expectedJson.trim() ? json(draft.expectedJson, '期望结果 JSON', false) : undefined }
}

// An older server may ignore the opt-in field. Never report a checked save without its receipt.
export function matchesSourceReceipt(result: unknown, body: { agentId?: number; traceId?: string; spanId?: string }) {
  if (!result || typeof result !== 'object') return false
  const row = result as Record<string, unknown>
  const source = row.sourceReference as Record<string, unknown> | undefined
  return row.agentId === body.agentId && row.traceId === body.traceId && row.spanId === body.spanId
    && source?.state === 'OBSERVATION_MATCHED' && Number.isSafeInteger(source.observationId) && Number(source.observationId) > 0
    && typeof source.endpointKey === 'string' && source.endpointKey.length > 0
    && typeof source.observedAt === 'string' && Number.isFinite(Date.parse(source.observedAt))
    && typeof source.checkedAt === 'string' && Number.isFinite(Date.parse(source.checkedAt))
}
