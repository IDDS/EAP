import type { TraceSpanObservation } from '@/types/domain'

export function traceDiagnostics(spans: TraceSpanObservation[]) {
  const key = (trace: string, span: string) => JSON.stringify([trace, span])
  const byId = new Map(spans.map(span => [key(span.traceId, span.spanId), span]))
  const time = (value?: string | null) => value ? Date.parse(value) : NaN
  const rows = spans.map(span => {
    const start = time(span.startedAt), end = time(span.endedAt)
    const timed = !span.synthetic && Number.isFinite(start) && Number.isFinite(end) && end >= start
    const missingParent = !!span.parentSpanId && !byId.has(key(span.traceId, span.parentSpanId))
    let depth = 0, current = span, cycle = false
    const seen = new Set([key(span.traceId, span.spanId)])
    while (current.parentSpanId) {
      const parentKey = key(current.traceId, current.parentSpanId)
      if (seen.has(parentKey)) { cycle = true; break }
      const parent = byId.get(parentKey)
      if (!parent) break
      seen.add(parentKey); depth++; current = parent
    }
    return { span, start, end, timed, missingParent, cycle, depth: Math.min(depth, 8),
      failed: ['FAILED', 'ERROR', 'CANCELLED'].includes((span.status || '').toUpperCase()),
      timingIssue: span.synthetic ? '派生记录，未提供生产端时间' : !Number.isFinite(start) || !Number.isFinite(end) ? '缺少有效开始/结束时间' : end < start ? '结束早于开始，需核对时钟' : '' }
  }).sort((a, b) => Number(b.timed) - Number(a.timed) || (a.timed && b.timed ? a.start - b.start : 0) || a.span.spanId.localeCompare(b.span.spanId))
  const timed = rows.filter(row => row.timed)
  const start = timed.length ? Math.min(...timed.map(row => row.start)) : 0
  const end = timed.length ? Math.max(...timed.map(row => row.end)) : 0
  const duration = end - start
  return { rows, start, duration, timed: timed.length, failed: rows.filter(row => row.failed).length,
    missingParents: rows.filter(row => row.missingParent).length,
    cycles: rows.filter(row => row.cycle).length,
    // This is the observed time envelope, not proof of complete task duration.
    bars: rows.map(row => ({ ...row, left: row.timed ? (row.start - start) / Math.max(1, duration) * 100 : 0,
      width: row.timed ? (row.end - row.start) / Math.max(1, duration) * 100 : 0 })) }
}
