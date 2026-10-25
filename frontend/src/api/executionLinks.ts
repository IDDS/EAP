import { getData } from './http'
import { executionStreamPattern } from './agentExecutions'

export interface ExecutionLink {
  id: number; linkId: string; relation: 'DELEGATE' | 'CODE_HANDOFF'
  sourceStream: string; sourceRun: string; sourceAgentId: number; sourceRuntimeKey: string
  targetStream: string; targetRun: string; targetAgentId: number | null; targetRuntimeKey: string | null
  artifactDigest: string | null; artifactSize: number | null; artifactLanguage: string | null
  offeredMs: number; confirmedMs: number | null; state: 'PENDING_CONFIRMATION' | 'CONFIRMED'
  evidenceKind: string; executionPermissionGranted: false
}
export interface ExecutionLinkPage { items: ExecutionLink[]; hasMore: boolean; nextAfterId: number }
const uuid = (s: unknown) => typeof s === 'string' && /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(s) && s.replaceAll('-', '') !== '0'.repeat(32)
const positive = (n: unknown): n is number => typeof n === 'number' && Number.isSafeInteger(n) && n > 0
export async function getExecutionLinks(stream: string, afterId = 0, signal?: AbortSignal): Promise<ExecutionLinkPage> {
  if (!executionStreamPattern.test(stream) || !Number.isSafeInteger(afterId) || afterId < 0) throw Error('INVALID_LINK_QUERY')
  const page = await getData<ExecutionLinkPage>(`/observability/agent-executions/${stream}/links?afterId=${afterId}&limit=50`, { silentError: true, signal })
  if (!page || !Array.isArray(page.items) || page.items.length > 50 || typeof page.hasMore !== 'boolean') throw Error('INVALID_LINK_PAGE')
  let previous = afterId
  const ids = new Set<string>()
  for (const row of page.items) {
    if (!positive(row.id) || row.id <= previous || !uuid(row.linkId) || ids.has(row.linkId) || !uuid(row.sourceRun) || !uuid(row.targetRun)
      || !executionStreamPattern.test(row.sourceStream) || !executionStreamPattern.test(row.targetStream) || row.sourceStream === row.targetStream
      || ![row.sourceStream, row.targetStream].includes(stream) || !positive(row.sourceAgentId) || typeof row.sourceRuntimeKey !== 'string' || !row.sourceRuntimeKey
      || !positive(row.offeredMs) || row.executionPermissionGranted !== false) throw Error('INVALID_LINK_ROW')
    if (row.state === 'CONFIRMED') {
      if (!positive(row.targetAgentId) || typeof row.targetRuntimeKey !== 'string' || !row.targetRuntimeKey || row.targetRuntimeKey === row.sourceRuntimeKey
        || !positive(row.confirmedMs) || row.evidenceKind !== 'TWO_AUTHENTICATED_PRODUCER_REPORTS') throw Error('INVALID_LINK_CONFIRMATION')
    } else if (row.state !== 'PENDING_CONFIRMATION' || row.targetAgentId !== null || row.targetRuntimeKey !== null || row.confirmedMs !== null
      || row.evidenceKind !== 'SOURCE_PRODUCER_REPORT') throw Error('INVALID_LINK_CONFIRMATION')
    if (row.relation === 'CODE_HANDOFF') {
      if (typeof row.artifactDigest !== 'string' || !executionStreamPattern.test(row.artifactDigest) || !Number.isSafeInteger(row.artifactSize)
        || row.artifactSize === null || row.artifactSize < 0 || row.artifactSize > 1048576 || !['PYTHON', 'JAVASCRIPT', 'SHELL', 'OTHER'].includes(row.artifactLanguage ?? '')) throw Error('INVALID_LINK_ARTIFACT')
    } else if (row.relation !== 'DELEGATE' || row.artifactDigest !== null || row.artifactSize !== null || row.artifactLanguage !== null) throw Error('INVALID_LINK_ARTIFACT')
    previous = row.id; ids.add(row.linkId)
  }
  if (page.nextAfterId !== previous || page.hasMore && !page.items.length) throw Error('INVALID_LINK_CURSOR')
  return page
}
