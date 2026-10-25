import { getData, postData } from './http'

export interface WorkflowStopReport {
  contract: 'EAP_WORKFLOW_STOP_V1'
  supported: boolean
  mode: 'AFTER_CURRENT_STEP'
  state: 'UNSUPPORTED' | 'NONE' | 'REQUESTED' | 'APPLIED' | 'UNCONFIRMED' | 'FINISHED'
  executionStatus: string
  requestKey: string | null
  requestedBy: string | null
  requestedMs: number | null
  appliedMs: number | null
  effectsRolledBack: false
}
function path(workflowId: number, runKey: string) {
  return `/workflows/${workflowId}/executions/${encodeURIComponent(runKey)}/stop`
}
export function getWorkflowStop(workflowId: number, runKey: string, signal?: AbortSignal) {
  return getData<WorkflowStopReport>(path(workflowId, runKey), { signal, silentError: true })
}
export function requestWorkflowStop(workflowId: number, runKey: string, requestKey: string) {
  return postData<WorkflowStopReport, { requestKey: string }>(path(workflowId, runKey), { requestKey })
}
