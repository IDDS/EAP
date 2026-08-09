import { getData, postData, putData } from './http'
import type {
  Agent,
  AgentLifecycleStatus,
  AgentRequest,
  AgentCapability,
  AgentPlan,
  AgentPlanDetail,
  AgentPlanStep,
  AgentRuntimeInstance,
  AgentTeam,
  AgentTeamMember,
  AgentTask,
  AgentTaskStatus,
  AgentControlCommand,
  AgentDiscoveryRun,
  AgentDiscoverySummary,
  DiscoveredAgentAsset,
  DiscoveryAssetStatus,
  DiscoveryEndpoint,
  DiscoveryIdentityLink,
  ProtocolDiscoveryType,
  ProtocolProbeResponse,
  AuditLog,
  DashboardNetwork,
  DashboardSummary,
  DiscoveryResponse,
  GovernancePolicy,
  GovernanceDataEvent,
  GovernanceDataSummary,
  GovernanceEnforcementStatus,
  GovernancePreflightDecision,
  GovernanceDataAction,
  GovernanceDataDirection,
  GovernanceDataSensitivity,
  LearningFeedback,
  LearningFeedbackStatus,
  ManagementSummary,
  ManagementValueEvent,
  KnowledgeInteraction,
  KnowledgeInteractionStatus,
  KnowledgeStepStatus,
  KnowledgeStepType,
  KnowledgeSummary,
  ModelInvocationEvent,
  ContentObservation,
  TraceSpanObservation,
  ModelInvocationStatus,
  ModelGatewayStatus,
  PhysicalTelemetrySummary,
  SafetyEvaluation,
  SafetyEventType,
  SafetySession,
  SafetySummary,
  WorkflowDefinition,
  WorkflowDetail,
  WorkflowEdge,
  WorkflowNode,
  WorkflowRun,
} from '@/types/domain'

export const EapApi = {
  getDashboardSummary: () => getData<DashboardSummary>('/dashboard/summary'),
  getDashboardNetwork: () => getData<DashboardNetwork>('/dashboard/network'),
  listControlCommands: () => getData<AgentControlCommand[]>('/control/commands'),
  issueControlCommand: (payload: { agentId: number; commandType: string; payloadJson?: string; issuedBy?: string }) =>
    postData<AgentControlCommand, { agentId: number; commandType: string; payloadJson?: string; issuedBy?: string }>('/control/commands', payload),
  listAuditLogs: () => getData<AuditLog[]>('/audit-logs'),
  listAgents: () => getData<Agent[]>('/agents'),
  createAgent: (payload: AgentRequest) => postData<Agent, AgentRequest>('/agents', payload),
  updateAgentStatus: (id: number, lifecycleStatus: AgentLifecycleStatus) =>
    postData<Agent, { lifecycleStatus: AgentLifecycleStatus }>(`/agents/${id}/status`, { lifecycleStatus }),
  listPolicies: () => getData<GovernancePolicy[]>('/policies'),
  createPolicy: (payload: Omit<GovernancePolicy, 'id' | 'createdAt' | 'updatedAt'>) =>
    postData<GovernancePolicy, Omit<GovernancePolicy, 'id' | 'createdAt' | 'updatedAt'>>('/policies', payload),
  updatePolicy: (id: number, payload: Omit<GovernancePolicy, 'id' | 'createdAt' | 'updatedAt'>) =>
    putData<GovernancePolicy, Omit<GovernancePolicy, 'id' | 'createdAt' | 'updatedAt'>>(`/policies/${id}`, payload),
  getGovernanceDataSummary: (hours = 24) => getData<GovernanceDataSummary>(`/governance/data-summary?hours=${hours}`),
  listGovernanceDataEvents: (limit = 100) => getData<GovernanceDataEvent[]>(`/governance/data-events?limit=${limit}`),
  recordGovernanceDataEvent: (payload: {
    eventKey: string
    agentId?: number
    endpointKey?: string
    taskId?: number
    traceId?: string
    direction: GovernanceDataDirection
    dataCategory: string
    sensitivity: GovernanceDataSensitivity
    action: GovernanceDataAction
    internalData: boolean
    companySecret: boolean
    tokenCount: number
    payloadBytes: number
    policyId?: number
    policyRule?: string
  }) => postData<GovernanceDataEvent, typeof payload>('/governance/data-events', payload),
  getGovernanceEnforcementStatus: () => getData<GovernanceEnforcementStatus>('/governance/enforcement-status'),
  getModelGatewayStatus: () => getData<ModelGatewayStatus>('/gateway/status'),
  listGovernancePreflightDecisions: (limit = 100) => getData<GovernancePreflightDecision[]>(`/governance/preflight-decisions?limit=${limit}`),
  evaluateGovernancePreflight: (payload: {
    decisionKey: string
    eventKey: string
    agentId?: number
    endpointKey?: string
    taskId?: number
    traceId?: string
    direction: GovernanceDataDirection
    dataCategory: string
    sensitivity: GovernanceDataSensitivity
    internalData: boolean
    companySecret: boolean
    tokenCount: number
    payloadBytes: number
    classifier: string
    provider?: string
    modelName?: string
    detectedTypes: string[]
  }) => postData<GovernancePreflightDecision, typeof payload>('/governance/preflight', payload),
  approveGovernanceDecision: (decisionKey: string, payload: { approver: string; reason?: string }) =>
    postData<GovernancePreflightDecision, { approver: string; reason?: string }>(`/governance/preflight-decisions/${encodeURIComponent(decisionKey)}/approve`, payload),
  rejectGovernanceDecision: (decisionKey: string, payload: { approver: string; reason?: string }) =>
    postData<GovernancePreflightDecision, { approver: string; reason?: string }>(`/governance/preflight-decisions/${encodeURIComponent(decisionKey)}/reject`, payload),
  listTasks: () => getData<AgentTask[]>('/tasks'),
  createTask: (payload: { agentId: number; agentVersionId?: number; taskType: string; inputSummary?: string }) =>
    postData<AgentTask, { agentId: number; agentVersionId?: number; taskType: string; inputSummary?: string }>('/tasks', payload),
  updateTaskStatus: (id: number, status: AgentTaskStatus, resultSummary?: string) =>
    postData<AgentTask, { status: AgentTaskStatus; resultSummary?: string }>(`/tasks/${id}/status`, { status, resultSummary }),
  listLearningFeedbacks: () => getData<LearningFeedback[]>('/learning-feedbacks'),
  createLearningFeedback: (payload: { agentId: number; feedbackType: string; rating: number; summary: string; improvementAction?: string }) =>
    postData<LearningFeedback, { agentId: number; feedbackType: string; rating: number; summary: string; improvementAction?: string }>('/learning-feedbacks', payload),
  updateLearningFeedbackStatus: (id: number, status: LearningFeedbackStatus) =>
    postData<LearningFeedback, { status: LearningFeedbackStatus }>(`/learning-feedbacks/${id}/status`, { status }),
  getSafetySummary: () => getData<SafetySummary>('/safety/summary'),
  listSafetySessions: () => getData<SafetySession[]>('/safety/sessions'),
  createSafetySession: (payload: { sessionKey: string; rootAgentId?: number; objective: string }) =>
    postData<SafetySession, { sessionKey: string; rootAgentId?: number; objective: string }>('/safety/sessions', payload),
  evaluateSafetyEvent: (payload: {
    sessionKey: string
    agentId?: number
    parentAgentId?: number
    eventType: SafetyEventType
    action: string
    target: string
    payloadSummary?: string
    payloadJson?: string
  }) => postData<SafetyEvaluation, typeof payload>('/safety/events/evaluate', payload),
  listAgentCapabilities: (agentId: number) => getData<AgentCapability[]>(`/agents/${agentId}/capabilities`),
  registerAgentCapability: (agentId: number, payload: { capability: string; name: string; description?: string }) =>
    postData<AgentCapability, { capability: string; name: string; description?: string }>(`/agents/${agentId}/capabilities`, payload),
  listAgentRuntimeInstances: (agentId: number) => getData<AgentRuntimeInstance[]>(`/agents/${agentId}/runtime-instances`),
  registerAgentRuntimeInstance: (agentId: number, payload: { instanceKey: string; endpoint: string; protocol: string }) =>
    postData<AgentRuntimeInstance, { instanceKey: string; endpoint: string; protocol: string }>(`/agents/${agentId}/runtime-instances`, payload),
  sendAgentHeartbeat: (instanceKey: string, payload: { status: 'ONLINE' | 'DEGRADED' | 'OFFLINE'; loadScore: number }) =>
    postData<AgentRuntimeInstance, { status: 'ONLINE' | 'DEGRADED' | 'OFFLINE'; loadScore: number }>(`/agent-runtime/${instanceKey}/heartbeat`, payload),
  discoverAgents: (payload: { capability: string; maxRiskLevel?: string; department?: string; protocol?: string }) =>
    postData<DiscoveryResponse, { capability: string; maxRiskLevel?: string; department?: string; protocol?: string }>('/agent-discovery/search', payload),
  getAgentDiscoverySummary: () => getData<AgentDiscoverySummary>('/discovery/summary'),
  listDiscoveredAgentAssets: () => getData<DiscoveredAgentAsset[]>('/discovery/agents'),
  listAgentDiscoveryRuns: () => getData<AgentDiscoveryRun[]>('/discovery/runs'),
  listDiscoveryEndpoints: () => getData<DiscoveryEndpoint[]>('/discovery/endpoints'),
  listDiscoveryIdentityLinks: () => getData<DiscoveryIdentityLink[]>('/discovery/identity-links'),
  probeProtocolDiscovery: (payload: { targetUrl: string; discoveryType: ProtocolDiscoveryType }) =>
    postData<ProtocolProbeResponse, { targetUrl: string; discoveryType: ProtocolDiscoveryType }>('/discovery/protocol/probe', payload),
  getPhysicalTelemetrySummary: (hours = 24) => getData<PhysicalTelemetrySummary>(`/telemetry/physical-summary?hours=${hours}`),
  listModelInvocationEvents: (limit = 100) => getData<ModelInvocationEvent[]>(`/telemetry/model-invocations?limit=${limit}`),
  getContentObservationStatus: () => getData<{ enabled: boolean; captureMode: string; maxContentBytes: number; totalObservations: number; inputObservations: number; outputObservations: number; lastObservedAt?: string }>('/observability/status'),
  listContentObservations: (limit = 100) => getData<ContentObservation[]>(`/observability/content?limit=${limit}`),
  listTraceSpanObservations: (limit = 100) => getData<TraceSpanObservation[]>(`/observability/otlp/spans?limit=${limit}`),
  recordModelInvocation: (payload: {
    eventKey: string
    traceId?: string
    spanId?: string
    agentId?: number
    endpointKey?: string
    runtimeInstanceKey?: string
    taskId?: number
    governanceDecisionKey: string
    provider: string
    modelName: string
    status: ModelInvocationStatus
    inputTokens: number
    outputTokens: number
    cachedInputTokens?: number
    reasoningTokens?: number
    latencyMs: number
    moneyCost?: number
    currency?: string
    toolCallCount?: number
    mcpServerCount?: number
    errorType?: string
    observedAt?: string
  }) => postData<ModelInvocationEvent, typeof payload>('/telemetry/model-invocations', payload),
  getKnowledgeSummary: (hours = 24) => getData<KnowledgeSummary>(`/knowledge/summary?hours=${hours}`),
  listKnowledgeInteractions: (limit = 100) => getData<KnowledgeInteraction[]>(`/knowledge/interactions?limit=${limit}`),
  recordKnowledgeInteraction: (payload: {
    interactionKey: string
    traceId?: string
    agentId?: number
    endpointKey?: string
    taskId?: number
    inputType: string
    inputSourceCount: number
    inputTokenCount: number
    inputSummary?: string
    inputKnowledgeRefs: string[]
    outputType?: string
    outputSourceCount: number
    outputTokenCount: number
    outputSummary?: string
    outputKnowledgeRefs: string[]
    processSteps: Array<{
      stepOrder: number
      stepType: KnowledgeStepType
      name: string
      status: KnowledgeStepStatus
      sourceCount: number
      inputTokens: number
      outputTokens: number
      latencyMs: number
      knowledgeRefs: string[]
      metadata: Record<string, unknown>
    }>
    status: KnowledgeInteractionStatus
    adopted: boolean
    reused: boolean
    qualityScore?: number
    startedAt?: string
    completedAt?: string
  }) => postData<KnowledgeInteraction, typeof payload>('/knowledge/interactions', payload),
  updateKnowledgeOutcome: (id: number, payload: { adopted: boolean; reused: boolean; qualityScore?: number }) =>
    postData<KnowledgeInteraction, typeof payload>(`/knowledge/interactions/${id}/outcome`, payload),
  getManagementSummary: (hours = 24) => getData<ManagementSummary>(`/management/summary?hours=${hours}`),
  listManagementValueEvents: (limit = 100) => getData<ManagementValueEvent[]>(`/management/value-events?limit=${limit}`),
  recordManagementValueEvent: (payload: {
    eventKey: string
    agentId?: number
    endpointKey?: string
    taskId?: number
    knowledgeInteractionId?: number
    valueType: string
    description?: string
    validatedBy: string
    benefitMoney: number
    currency?: string
  }) => postData<ManagementValueEvent, typeof payload>('/management/value-events', payload),
  triggerAgentDiscoveryScan: () => postData<AgentDiscoveryRun, Record<string, never>>('/discovery/scans', {}),
  updateDiscoveredAgentStatus: (id: number, status: DiscoveryAssetStatus) =>
    postData<DiscoveredAgentAsset, { status: DiscoveryAssetStatus }>(`/discovery/agents/${id}/status`, { status }),
  listTeams: () => getData<AgentTeam[]>('/orchestration/teams'),
  createTeam: (payload: { code: string; name: string; objective?: string; coordinationMode: string; workspacePolicy: string }) =>
    postData<AgentTeam, { code: string; name: string; objective?: string; coordinationMode: string; workspacePolicy: string }>('/orchestration/teams', payload),
  listTeamMembers: (teamId: number) => getData<AgentTeamMember[]>(`/orchestration/teams/${teamId}/members`),
  addTeamMember: (teamId: number, payload: { agentId: number; role: string; responsibility: string }) =>
    postData<AgentTeamMember, { agentId: number; role: string; responsibility: string }>(`/orchestration/teams/${teamId}/members`, payload),
  listTeamPlans: (teamId: number) => getData<AgentPlan[]>(`/orchestration/teams/${teamId}/plans`),
  createPlan: (payload: { teamId: number; goal: string; contextSummary?: string }) =>
    postData<AgentPlan, { teamId: number; goal: string; contextSummary?: string }>('/orchestration/plans', payload),
  getPlan: (planId: number) => getData<AgentPlanDetail>(`/orchestration/plans/${planId}`),
  addPlanStep: (planId: number, payload: { stepOrder: number; title: string; requiredCapability: string }) =>
    postData<AgentPlanStep, { stepOrder: number; title: string; requiredCapability: string }>(`/orchestration/plans/${planId}/steps`, payload),
  assignPlanStep: (stepId: number, payload: { assignedAgentId?: number; status: 'PENDING' | 'ASSIGNED' | 'RUNNING' | 'DONE' | 'BLOCKED' }) =>
    postData<AgentPlanStep, { assignedAgentId?: number; status: 'PENDING' | 'ASSIGNED' | 'RUNNING' | 'DONE' | 'BLOCKED' }>(`/orchestration/steps/${stepId}/assignment`, payload),
  listWorkflows: () => getData<WorkflowDefinition[]>('/workflows'),
  createWorkflow: (payload: { code: string; name: string; description?: string }) =>
    postData<WorkflowDefinition, { code: string; name: string; description?: string }>('/workflows', payload),
  getWorkflow: (workflowId: number) => getData<WorkflowDetail>(`/workflows/${workflowId}`),
  upsertWorkflowNode: (workflowId: number, payload: {
    nodeKey: string
    label: string
    nodeType: string
    agentId?: number
    capability?: string
    positionX: number
    positionY: number
    configJson?: string
  }) => postData<WorkflowNode, typeof payload>(`/workflows/${workflowId}/nodes`, payload),
  addWorkflowEdge: (workflowId: number, payload: { sourceNodeKey: string; targetNodeKey: string; conditionExpression?: string }) =>
    postData<WorkflowEdge, { sourceNodeKey: string; targetNodeKey: string; conditionExpression?: string }>(`/workflows/${workflowId}/edges`, payload),
  runWorkflow: (workflowId: number, payload: { objective?: string }) =>
    postData<WorkflowRun, { objective?: string }>(`/workflows/${workflowId}/runs`, payload),
}
