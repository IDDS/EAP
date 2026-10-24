import { getData } from './http'

export interface ExecutionEvent {
  eventId: string; sequence: number; kind: string; runId: string; traceId: string
  occurredAt: string; processId: number; framework: string; frameworkVersion: string
  definitionKey?: string; parentRunId?: string; toolCallId?: string; outcome?: string
  artifactDigest?: string; sizeBytes?: number; language?: string
}
export interface ExecutionRecord {
  id: number; agentId: number; runtimeInstanceKey: string; streamKey: string
  event: ExecutionEvent; receivedAt: string
}
export interface ExecutionPage { items: ExecutionRecord[]; hasMore: boolean; nextAfterId: number }
export interface ExecutionFilter { agentId?: number; streamKey?: string; afterId: number }
export interface ExecutionRun {
  runId: string; parentRunId: string | null; definitionKey: string | null; state: string; missingParent: boolean
}
export interface ExecutionTool { toolCallId: string; runId: string; state: string; start?: ExecutionEvent; finish?: ExecutionEvent }
export interface ExecutionGraph {
  streamKey: string; runs: ExecutionRun[]; tools: ExecutionTool[]; artifacts: ExecutionEvent[]
  gaps: string[]; complete: boolean; evidenceKind: string
}
export const executionPageSize = 100
export const executionStreamPattern = /^[a-f0-9]{64}$/
const states = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED', 'INCOMPLETE', 'INCONSISTENT'])

export async function getExecutionPage(filter: ExecutionFilter, signal?: AbortSignal): Promise<ExecutionPage> {
  const params = new URLSearchParams({ afterId: String(filter.afterId), limit: String(executionPageSize) })
  if (filter.agentId !== undefined) params.set('agentId', String(filter.agentId))
  if (filter.streamKey) params.set('streamKey', filter.streamKey)
  const page = await getData<ExecutionPage>(`/observability/agent-executions?${params}`, { silentError: true, signal })
  let previous = filter.afterId
  if (!page || !Array.isArray(page.items) || page.items.length > executionPageSize || typeof page.hasMore !== 'boolean') throw Error('INVALID_EXECUTION_PAGE')
  for (const row of page.items) {
    if (!Number.isSafeInteger(row.id) || row.id <= previous || !Number.isSafeInteger(row.agentId) || row.agentId <= 0
      || !executionStreamPattern.test(row.streamKey) || typeof row.runtimeInstanceKey !== 'string'
      || !row.event || typeof row.event.framework !== 'string' || typeof row.event.frameworkVersion !== 'string'
      || (filter.agentId !== undefined && row.agentId !== filter.agentId)
      || (filter.streamKey && row.streamKey !== filter.streamKey)) throw Error('INVALID_EXECUTION_PAGE')
    previous = row.id
  }
  if (page.nextAfterId !== previous || (page.hasMore && !page.items.length)) throw Error('INVALID_EXECUTION_CURSOR')
  return page
}

export async function getExecutionGraph(streamKey: string, signal?: AbortSignal): Promise<ExecutionGraph> {
  if (!executionStreamPattern.test(streamKey)) throw Error('INVALID_STREAM_KEY')
  const graph = await getData<ExecutionGraph>(`/observability/agent-executions/${streamKey}/graph`, { silentError: true, signal })
  if (!graph || graph.streamKey !== streamKey || graph.evidenceKind !== 'AUTHENTICATED_PRODUCER_REPORT'
    || typeof graph.complete !== 'boolean' || !Array.isArray(graph.runs) || !Array.isArray(graph.tools)
    || !Array.isArray(graph.artifacts) || !Array.isArray(graph.gaps)
    || Math.max(graph.runs.length, graph.tools.length, graph.artifacts.length) > 4096
    || graph.runs.some(run => !run.runId || !states.has(run.state))
    || graph.tools.some(tool => !tool.toolCallId || !tool.runId || !states.has(tool.state))) throw Error('INVALID_EXECUTION_GRAPH')
  return graph
}
