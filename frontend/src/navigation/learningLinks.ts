export type LearningLocation = {
  agentId?: number
  experimentId?: number
  optimizationId?: number
  error?: string
}

// Query strings are navigation hints, never evidence of ownership or execution.
export function learningLocation(query: Record<string, unknown>): LearningLocation {
  const result: LearningLocation = {}
  for (const key of ['agentId', 'experimentId', 'optimizationId'] as const) {
    const value = query[key]
    if (value === undefined) continue
    if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value) || !Number.isSafeInteger(Number(value))) {
      return { error: '记录链接无效，请使用单个有效的智能体、实验或优化任务编号。' }
    }
    result[key] = Number(value)
  }
  if (result.experimentId && result.optimizationId) return { error: '记录链接同时指定了实验和优化任务，请分别打开。' }
  return result
}

export function learningRecordPath(kind: 'experiment' | 'optimization', id: number, agentId: number): string {
  if (![id, agentId].every(value => Number.isSafeInteger(value) && value > 0)) return '/learning'
  return `/learning?agentId=${agentId}&${kind}Id=${id}`
}
