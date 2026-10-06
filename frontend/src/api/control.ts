import type { AgentControlCommand } from '@/types/domain'
import { getData, http } from './http'
export interface ControlTarget { instanceKey: string; status: string; commands: string[]; unavailableReason?: string }
export interface ControlTargets { instances: ControlTarget[]; dispatchEnabled: boolean; maxAttempts: number }
export const getControlTargets = (agentId: number) => getData<ControlTargets>(`/control/agents/${agentId}/targets`)

export interface ControlRequest { agentId: number; commandType: string; runtimeInstanceKey?: string; payloadJson?: string; issuedBy?: string }
// Instance wake-up can use the backend's 60s RPC deadline. Keep the UI connected for its result.
export const sendControlCommand = (payload: ControlRequest) =>
  http.post<{ data: AgentControlCommand }>('/control/commands', payload, { timeout: 90000 }).then(r => r.data.data)
export const retryControlCommand = (key: string) =>
  http.post<{ data: AgentControlCommand }>(`/control/commands/${encodeURIComponent(key)}/retry`, {}, { timeout: 90000 }).then(r => r.data.data)
