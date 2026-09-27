import { http } from './http'
import type { ApiResponse, AgentControlCommand } from '@/types/domain'
import type { DeployPayload } from './substrateDeploy'

export type SubstrateCapabilities = {
  enabled: boolean
  runnerAvailable: boolean
  gatewayConfigured: boolean
  atespace: string
  gatewayHost: string
  sizes: string[]
}
export type DeploymentStep = { step: string; status: string; detail: string; at: string }
export type SubstrateDeployment = {
  deploymentKey: string
  agentId: number
  version: number
  sourceType: 'RUNNER' | 'IMAGE'
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
export type ChatReply = { reply: string; turns: number; instanceKey: string }

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

const base = (agentId: number) => `/agents/${agentId}/substrate`

export const SubstrateApi = {
  capabilities: () => data<SubstrateCapabilities>(http.get('/agents/substrate/capabilities')),
  deployments: (agentId: number) => data<SubstrateDeployment[]>(http.get(`${base(agentId)}/deployments`)),
  // Resolving a tag contacts the image registry; allow more than the default 12 s.
  deploy: (agentId: number, payload: DeployPayload) =>
    data<SubstrateDeployment>(http.post(`${base(agentId)}/deployments`, payload, { timeout: 30000 })),
  retry: (agentId: number, version: number) =>
    data<SubstrateDeployment>(http.post(`${base(agentId)}/deployments/${version}/retry`, {})),
  instances: (agentId: number) => data<SubstrateInstance[]>(http.get(`${base(agentId)}/instances`, { timeout: 30000 })),
  createInstance: (agentId: number) => data<SubstrateInstance>(http.post(`${base(agentId)}/instances`, {}, { timeout: 60000 })),
  deleteInstance: (agentId: number, name: string) =>
    data<void>(http.delete(`${base(agentId)}/instances/${encodeURIComponent(name)}`, { timeout: 60000 })),
  // Waking a suspended instance and a reasoning model can take well over a minute.
  chat: (agentId: number, name: string, message: string) =>
    data<ChatReply>(http.post(`${base(agentId)}/instances/${encodeURIComponent(name)}/chat`, { message }, { timeout: 180000 })),
  control: (agentId: number, runtimeInstanceKey: string, commandType: 'PAUSE' | 'RESUME' | 'REVERT') =>
    data<AgentControlCommand>(http.post('/control/commands', { agentId, commandType, runtimeInstanceKey, issuedBy: 'console' },
      { timeout: 90000 })),
}
