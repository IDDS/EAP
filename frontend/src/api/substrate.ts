import { http } from './http'
import type { ApiResponse, AgentControlCommand } from '@/types/domain'
import type { DeployPayload } from './substrateDeploy'

export type SubstrateCapabilities = {
  enabled: boolean
  runnerAvailable: boolean
  kagentRunnerAvailable?: boolean
  gatewayConfigured: boolean
  atespace: string
  gatewayHost: string
  sizes: string[]
  sourceBuildAvailable: boolean
  instanceIdentityAvailable: boolean
}
export type DeploymentStep = { step: string; status: string; detail: string; at: string }
export type DeploymentReceipt = { requestKey: string; agentId: number; deploymentKey?: string | null; version?: number | null; status: string; instanceKey?: string | null }
export type SubstrateDeployment = {
  deploymentKey: string
  agentId: number
  version: number
  sourceType: 'RUNNER' | 'IMAGE' | 'SOURCE'
  status: string
  imageRef?: string
  templateName: string
  instanceKey?: string
  reason?: string
  requestedBy: string
  steps: DeploymentStep[]
  config: Record<string, unknown>
  createdAt: string
  updatedAt: string
}
export type SubstrateInstance = { instanceKey: string; name: string; version?: number; state: string; eapStatus: string; detail?: string }
export type ChatReply = { requestKey: string; status: 'ACCEPTED' | 'DISPATCHED' | 'SUCCEEDED' | 'NOT_DISPATCHED' | 'UNKNOWN'; reply?: string | null; turns?: number; traceId?: string; resultDigest?: string; notice: string }
export type ChatHistory = { role: 'user' | 'assistant'; content: string }[]

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

const base = (agentId: number) => `/agents/${agentId}/substrate`

export const SubstrateApi = {
  capabilities: () => data<SubstrateCapabilities>(http.get('/agents/substrate/capabilities')),
  deployments: (agentId: number) => data<SubstrateDeployment[]>(http.get(`${base(agentId)}/deployments`)),
  deploymentReceipt: (agentId: number, requestKey: string) =>
    data<DeploymentReceipt>(http.get(`${base(agentId)}/deployments/requests/${encodeURIComponent(requestKey)}`)),
  // Resolving a tag contacts the image registry; allow more than the default 12 s.
  deploy: (agentId: number, payload: DeployPayload) =>
    data<SubstrateDeployment>(http.post(`${base(agentId)}/deployments`, payload, { timeout: 30000 })),
  retry: (agentId: number, version: number) =>
    data<SubstrateDeployment>(http.post(`${base(agentId)}/deployments/${version}/retry`, {})),
  buildLog: (agentId: number, version: number) =>
    data<string>(http.get(`${base(agentId)}/deployments/${version}/build-log`, { timeout: 30000 })),
  instances: (agentId: number) => data<SubstrateInstance[]>(http.get(`${base(agentId)}/instances`, { timeout: 30000 })),
  createInstance: (agentId: number, requestKey: string) => data<SubstrateInstance>(http.post(`${base(agentId)}/instances`, { requestKey }, { timeout: 60000 })),
  deleteInstance: (agentId: number, name: string) =>
    data<void>(http.delete(`${base(agentId)}/instances/${encodeURIComponent(name)}`, { timeout: 60000 })),
  // Waking a suspended instance and a reasoning model can take well over a minute.
  chat: (agentId: number, name: string, message: string, history: ChatHistory, requestKey: string) =>
    data<ChatReply>(http.post(`${base(agentId)}/instances/${encodeURIComponent(name)}/chat`, { requestKey, message, history }, { timeout: 180000 })),
  chatReceipt: (agentId: number, name: string, requestKey: string) =>
    data<ChatReply>(http.get(`${base(agentId)}/instances/${encodeURIComponent(name)}/chat/${encodeURIComponent(requestKey)}`)),
  control: (agentId: number, runtimeInstanceKey: string, commandType: 'PAUSE' | 'RESUME' | 'REVERT' | 'RUN_HEALTH_CHECK') =>
    data<AgentControlCommand>(http.post('/control/commands', { agentId, commandType, runtimeInstanceKey, issuedBy: 'console' },
      { timeout: 90000 })),
}
