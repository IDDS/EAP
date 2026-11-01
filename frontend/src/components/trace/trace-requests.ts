import type { TraceSpanObservation } from '@/types/domain'

export interface TraceRequestRow {
  traceId: string
  lastObservedAt: string
  label: string
  agentIds: number[]
  observationStatus: 'failed' | 'active' | 'observed'
  observedSpanCount: number
}

/** These rows summarize only the supplied observations, not a complete execution. */
export function traceRequests(spans: TraceSpanObservation[]): TraceRequestRow[] {
  const groups = new Map<string, TraceSpanObservation[]>()
  for (const span of spans) {
    if (!span.traceId) continue
    const group = groups.get(span.traceId) || []
    group.push(span)
    groups.set(span.traceId, group)
  }
  return [...groups.entries()].map(([traceId, observations]) => {
    const ordered = [...observations].sort((a, b) => Date.parse(b.observedAt) - Date.parse(a.observedAt))
    const representative = observations.find(span => span.spanKind === 'AGENT' && !span.parentSpanId)
      || observations.find(span => span.spanKind === 'AGENT') || ordered[0]
    const states = observations.map(span => span.status)
    return {
      traceId,
      lastObservedAt: ordered[0].observedAt,
      label: representative.spanName || '未命名观测',
      agentIds: [...new Set(observations.map(span => span.agentId).filter((id): id is number => typeof id === 'number' && id > 0))],
      observationStatus: states.includes('FAILED') ? 'failed' as const : states.includes('STARTED') ? 'active' as const : 'observed' as const,
      observedSpanCount: observations.length,
    }
  }).sort((a, b) => Date.parse(b.lastObservedAt) - Date.parse(a.lastObservedAt))
}
