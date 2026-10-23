export interface DatasetLookup {
  kind: 'DATASET' | 'SAMPLE'
  state: 'FOUND' | 'NOT_FOUND' | 'KEY_MISMATCH'
  datasetKey: string
  itemKey?: string | null
  recordId?: number | null
  createdAt?: string | null
}

export function datasetLookupMessage(result: DatasetLookup, expected: { kind: 'DATASET' | 'SAMPLE'; datasetKey: string; itemKey?: string }) {
  if (!result || result.kind !== expected.kind || result.datasetKey !== expected.datasetKey.trim().toUpperCase()
      || (expected.kind === 'SAMPLE' ? result.itemKey !== expected.itemKey?.trim() : result.itemKey != null)) throw Error('查询回执与原标识不匹配')
  if (result.state === 'FOUND') {
    if (!Number.isSafeInteger(result.recordId) || Number(result.recordId) <= 0) throw Error('查询回执缺少有效记录编号')
    return `发现原标识记录 #${result.recordId}；仅确认记录存在，不能据此证明内容与本次提交一致。`
  }
  if (result.recordId != null || result.createdAt != null) throw Error('查询回执状态与记录不一致')
  if (result.state === 'NOT_FOUND') return '本次精确查询未找到原标识；提交仍可能在处理中，不能据此确认保存失败。'
  if (result.state === 'KEY_MISMATCH') return '数据库匹配到不同写法的标识，不能确认为原记录，请人工核对。'
  throw Error('无法识别查询回执状态')
}
