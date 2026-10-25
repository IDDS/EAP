import { getData } from './http'
export interface CoverageOperation {
  id: number; entryKind: string; operationKey: string; agentId: number | null; traceId: string | null
  identityEvidence: string; executionStatus: string; coverage: string; expectedAgentSpans: number; expectedModelEvents: number
  agentSpans: number | null; modelEvents: number | null; successfulModelEvents: number | null; createdMs: number; originalPath: string
}
export interface CoverageMetric {
  entryKind: string; registered: number | null; complete: number; pending: number; gaps: number; unconfirmed: number
  failed: number; unknown: number; expectedAgentSpans: number | null; expectedModelEvents: number | null
}
export interface CoverageReport {
  windowMinutes: number; windowStartMs: number; observedMs: number; denominatorContract: string
  metrics: CoverageMetric[]; recent: CoverageOperation[]; detailLimit: number; detailsTruncated: boolean
}
export interface CoverageDetail {
  operation: CoverageOperation
  logs: { kind: string; reasonCode: string; ownerEpoch: number | null; createdMs: number }[]
  costs: { reservationId: number; state: string; inputTokens: number | null; outputTokens: number | null }[]
  placements?: RuntimePlacementEvidence[]
}
export interface RuntimePlacementEvidence {
  phase: 'DISPATCH_REQUESTED' | 'RECEIPT_RETURNED'
  requestedAt: string
  captureState: 'INCOMPLETE' | 'RECORDED'
  snapshot: {
    instanceKey: string; status: 'ASSIGNED' | 'UNASSIGNED' | 'UNKNOWN'; reason: string; source: string | null
    observationStartedAt: string; observedAt: string; actorUid: string | null; actorVersion: number | null; actorState: string | null
    placement: { workerName: string; workerUid: string; namespace: string; pool: string; podName: string; podUid: string; nodeName: string } | null
  } | null
}
export const getCoverage = (minutes: number) => getData<CoverageReport>(`/observability/coverage?minutes=${minutes}`)
export const getCoverageOperation = (id: number) => getData<CoverageDetail>(`/observability/operations/${id}`)

export interface WorkflowEvidenceEntry { operation: CoverageOperation; nodeSequence: number | null; nodeKey: string | null }
export interface WorkflowEvidencePage {
  contract: string; workflowId: number; runKey: string; entries: WorkflowEvidenceEntry[]; nextCursor: string | null
}
const workflowEvidencePath = (workflowId: number, runKey: string) => `/workflows/${workflowId}/executions/${encodeURIComponent(runKey)}/observations`
export const getWorkflowEvidence = (workflowId: number, runKey: string, cursor?: string) => getData<WorkflowEvidencePage>(`${workflowEvidencePath(workflowId, runKey)}?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)
export const getWorkflowEvidenceDetail = (workflowId: number, runKey: string, id: number) => getData<{ workflowId: number; runKey: string; detail: CoverageDetail }>(`${workflowEvidencePath(workflowId, runKey)}/${id}`)
