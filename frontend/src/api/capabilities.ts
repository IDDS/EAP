export type Capability = 'viewConsole' | 'deployAgents' | 'operateInstances' | 'runWorkflows' | 'governance'
  | 'viewContent' | 'approveContent' | 'manageEvaluations' | 'annotateEvaluations' | 'manageFeedback' | 'runAgentExperiments' | 'reviewExperimentUnknowns' | 'manageBudgets' | 'manageSafety'
export type CapabilityMap = Partial<Record<Capability, boolean>>
export type ProjectContext = { isolationMode: 'SHARED_TRUST_DOMAIN' | 'DEDICATED_PROJECT'; projectKey: string | null }
export type CurrentUser = { mode: string; subject: string | null; roles: string[]; capabilities: CapabilityMap; project?: ProjectContext }

export function hasCapability(capabilities: CapabilityMap | null | undefined, capability: Capability): boolean {
  return capabilities?.[capability] === true
}

export function substrateActions(capabilities: CapabilityMap | null | undefined) {
  return {
    deploy: hasCapability(capabilities, 'deployAgents'),
    retry: hasCapability(capabilities, 'deployAgents'),
    createInstance: hasCapability(capabilities, 'operateInstances'),
    control: hasCapability(capabilities, 'operateInstances'),
    remove: hasCapability(capabilities, 'operateInstances'),
    chat: hasCapability(capabilities, 'operateInstances'),
  }
}
