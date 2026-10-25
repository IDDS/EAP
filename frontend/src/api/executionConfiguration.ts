import { getData } from './http'

export interface DispatchBinding {
  contract: string; instanceKey: string; protocol: string; actorUid: string | null; actorTemplate: string | null
  deploymentKey: string | null; deploymentVersion: number | null; sourceType: string | null; imageDigest: string | null; capturedAt: string
}
export interface ExecutionConfigurationNode {
  sequence: number; nodeKey: string; label: string; nodeType: string; agentId: number | null; nodeVersion: number | null
  executionBackend: string; bindingState: string; runtime: DispatchBinding | null
}
export interface ExecutionConfiguration {
  contract: string; workflowId: number; runKey: string; submittedAt: string; nodes: ExecutionConfigurationNode[]
}
export const getExecutionConfiguration = (workflowId: number, runKey: string) =>
  getData<ExecutionConfiguration>(`/workflows/${workflowId}/executions/${encodeURIComponent(runKey)}/configuration`)
