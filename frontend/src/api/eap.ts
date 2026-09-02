import { getData, patchData, postData, putData } from './http'
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
  DiscoveryProbeAgent,
  DiscoveryEnrollmentToken,
  DiscoveryIdentityLink,
  ProtocolDiscoveryType,
  ProtocolProbeResponse,
  AuditLog,
  DashboardNetwork,
  PlatformStatus,
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
  ManagementValueConnector,
  ManagementConnectorRequest,
  ManagementConnectorSyncResult,
  KnowledgeInteraction,
  KnowledgeInteractionStatus,
  KnowledgeStepStatus,
  KnowledgeStepType,
  KnowledgeSummary,
  ModelInvocationEvent,
  ContentObservation,
  TraceSpanObservation,
  ToolInvocationEvent,
  ModelInvocationStatus,
  ModelGatewayStatus,
  PhysicalTelemetrySummary,
  ProviderUsageSnapshot,
  ProviderUsageSource,
  UsageReconciliationSummary,
  SafetyEvaluation,
  SafetyEventType,
  SafetySession,
  SafetySummary,
  WorkflowDefinition,
  WorkflowDetail,
  WorkflowEdge,
  WorkflowNode,
  WorkflowRun,
  AgentOpsDashboard,
  AgentOpsAgentState,
  AgentOpsWorkItem,
  AgentOpsWorkItemEvent,
  ObservationCoverage,
  OperationalHealth,
  PagedAgentOpsResponse,
  RiskPosture,
  WorkItemSeverity,
  WorkItemStatus,
  EvaluatorDefinition,
  EvaluationDataset,
  EvaluationDatasetItem,
  EvaluationDatasetCalibration,
  EvaluationResult,
  EvaluationJob,
  EvaluationSummary,
} from '@/types/domain'

function queryString(params: Record<string, string | number | boolean | undefined>) {
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') query.set(key, String(value))
  })
  const encoded = query.toString()
  return encoded ? `?${encoded}` : ''
}

export const EapApi = {
  listEvaluators: () => getData<EvaluatorDefinition[]>('/evaluations/evaluators'),
  createEvaluator: (payload: {
    evaluatorKey: string
    name: string
    description?: string
    evaluatorType: 'RULE' | 'CODE' | 'LLM_JUDGE' | 'LOCAL_SLM'
    level: 'SPAN' | 'TRACE' | 'SESSION' | 'AGENT' | 'WORKFLOW'
    implementationKey: string
    version: string
    threshold?: number
  }) => postData<EvaluatorDefinition, typeof payload>('/evaluations/evaluators', payload),
  listEvaluationDatasets: () => getData<EvaluationDataset[]>('/evaluations/datasets'),
  createEvaluationDataset: (payload: {
    datasetKey: string
    name: string
    description?: string
    sourceType?: string
    version?: string
    itemCount?: number
  }) => postData<EvaluationDataset, typeof payload>('/evaluations/datasets', payload),
  listEvaluationDatasetItems: (datasetKey: string, limit = 200) =>
    getData<EvaluationDatasetItem[]>(`/evaluations/datasets/${encodeURIComponent(datasetKey)}/items?limit=${limit}`),
  runEvaluationDataset: (datasetKey: string, limit = 200) =>
    postData<EvaluationDatasetCalibration, Record<string, never>>(`/evaluations/datasets/${encodeURIComponent(datasetKey)}/run?limit=${limit}`, {}),
  getEvaluationDatasetCalibration: (datasetKey: string, limit = 200) =>
    getData<EvaluationDatasetCalibration>(`/evaluations/datasets/${encodeURIComponent(datasetKey)}/calibration?limit=${limit}`),
  createEvaluationDatasetItem: (datasetKey: string, payload: {
    itemKey: string
    evaluatorKey: string
    evaluatorVersion?: string
    agentId?: number
    traceId?: string
    spanId?: string
    input: Record<string, unknown>
    expected?: unknown
    actual: Record<string, unknown>
  }) => postData<EvaluationDatasetItem, typeof payload>(`/evaluations/datasets/${encodeURIComponent(datasetKey)}/items`, payload),
  annotateEvaluationDatasetItem: (datasetKey: string, itemKey: string, payload: {
    groundTruthLabel: 'PASS' | 'FAIL' | 'ABSTAIN'
    groundTruthScore?: number
    annotatedBy: string
    rationale?: string
  }) => patchData<EvaluationDatasetItem, typeof payload>(
    `/evaluations/datasets/${encodeURIComponent(datasetKey)}/items/${encodeURIComponent(itemKey)}/annotation`, payload,
  ),
  listEvaluationResults: (params: { agentId?: number; traceId?: string; limit?: number } = {}) =>
    getData<EvaluationResult[]>(`/evaluations/results${queryString(params)}`),
  getEvaluationSummary: (hours = 24) => getData<EvaluationSummary>(`/evaluations/summary?hours=${hours}`),
  runEvaluation: (payload: {
    resultKey?: string
    evaluatorKey: string
    evaluatorVersion?: string
    datasetKey?: string
    agentId?: number
    traceId?: string
    spanId?: string
    input: Record<string, unknown>
    expected?: unknown
    actual: Record<string, unknown>
    evaluatedAt?: string
  }) => postData<EvaluationResult, typeof payload>('/evaluations/results', payload),
  synchronizeEvaluations: (limit = 200) =>
    postData<{ scanned: number; evaluated: number; skipped: number; failed: number }, Record<string, never>>(`/evaluations/synchronize?limit=${limit}`, {}),
  submitEvaluationJob: (limit = 200) =>
    postData<EvaluationJob, Record<string, never>>(`/evaluations/jobs?limit=${limit}`, {}),
  listEvaluationJobs: () => getData<EvaluationJob[]>('/evaluations/jobs'),
  getEvaluationJob: (id: number) => getData<EvaluationJob>(`/evaluations/jobs/${id}`),
  getAgentOpsDashboard: (params: { hours?: number; organizationScope?: string } = {}) =>
    getData<AgentOpsDashboard>(`/agentops/dashboard${queryString(params)}`),
  listAgentOpsAgents: (params: {
    hours?: number
    page?: number
    size?: number
    query?: string
    department?: string
    lifecycleStatus?: AgentLifecycleStatus
    health?: OperationalHealth
    coverage?: ObservationCoverage
    risk?: RiskPosture
  } = {}) => getData<PagedAgentOpsResponse>(`/agentops/agents${queryString(params)}`),
  getAgentOpsAgent: (id: number, hours = 24) =>
    getData<AgentOpsAgentState>(`/agentops/agents/${id}${queryString({ hours })}`),
  listAgentOpsWorkItems: (params: {
    status?: WorkItemStatus
    severity?: WorkItemSeverity
    agentId?: number
    organizationScope?: string
    limit?: number
  } = {}) => getData<AgentOpsWorkItem[]>(`/agentops/work-items${queryString(params)}`),
  listAgentOpsWorkItemEvents: (id: number) =>
    getData<AgentOpsWorkItemEvent[]>(`/agentops/work-items/${id}/events`),
  synchronizeAgentOpsWorkItems: () =>
    postData<number, Record<string, never>>('/agentops/work-items/synchronize', {}),
  transitionAgentOpsWorkItem: (id: number, payload: {
    status: WorkItemStatus
    assignee?: string
    assignedTeam?: string
    actor?: string
    reason?: string
    resolutionEvidenceJson?: string
  }) => postData<AgentOpsWorkItem, typeof payload>(`/agentops/work-items/${id}/transition`, payload),
  getDashboardSummary: () => getData<DashboardSummary>('/dashboard/summary'),
  getDashboardNetwork: () => getData<DashboardNetwork>('/dashboard/network'),
  getPlatformStatus: () => getData<PlatformStatus>('/platform/status'),
  listControlCommands: () => getData<AgentControlCommand[]>('/control/commands'),
  issueControlCommand: (payload: { agentId: number; commandType: string; payloadJson?: string; issuedBy?: string }) =>
    postData<AgentControlCommand, { agentId: number; commandType: string; payloadJson?: string; issuedBy?: string }>('/control/commands', payload),
  retryControlCommand: (commandKey: string) =>
    postData<AgentControlCommand, Record<string, never>>(`/control/commands/${encodeURIComponent(commandKey)}/retry`, {}),
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
  createTask: (payload: { agentId: number; agentVersionId?: number; taskType: string; inputSummary?: string; requester?: string }) =>
    postData<AgentTask, { agentId: number; agentVersionId?: number; taskType: string; inputSummary?: string; requester?: string }>('/tasks', payload),
  updateTaskStatus: (id: number, status: AgentTaskStatus, resultSummary?: string, problemResolved?: boolean, resolutionSummary?: string) =>
    postData<AgentTask, { status: AgentTaskStatus; resultSummary?: string; problemResolved?: boolean; resolutionSummary?: string }>(`/tasks/${id}/status`, { status, resultSummary, problemResolved, resolutionSummary }),
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
  registerAgentRuntimeInstance: (agentId: number, payload: { instanceKey: string; endpoint: string; protocol: string; dispatchAuthenticated: boolean }) =>
    postData<AgentRuntimeInstance, { instanceKey: string; endpoint: string; protocol: string; dispatchAuthenticated: boolean }>(`/agents/${agentId}/runtime-instances`, payload),
  sendAgentHeartbeat: (instanceKey: string, payload: { status: 'ONLINE' | 'DEGRADED' | 'OFFLINE'; loadScore: number }) =>
    postData<AgentRuntimeInstance, { status: 'ONLINE' | 'DEGRADED' | 'OFFLINE'; loadScore: number }>(`/agent-runtime/${instanceKey}/heartbeat`, payload),
  discoverAgents: (payload: { capability: string; maxRiskLevel?: string; department?: string; protocol?: string }) =>
    postData<DiscoveryResponse, { capability: string; maxRiskLevel?: string; department?: string; protocol?: string }>('/agent-discovery/search', payload),
  getAgentDiscoverySummary: () => getData<AgentDiscoverySummary>('/discovery/summary'),
  listDiscoveredAgentAssets: () => getData<DiscoveredAgentAsset[]>('/discovery/agents'),
  listManagedAgentDiscoveryAssets: (agentId: number) =>
    getData<DiscoveredAgentAsset[]>(`/discovery/agents/by-managed-agent/${agentId}`),
  listAgentDiscoveryRuns: () => getData<AgentDiscoveryRun[]>('/discovery/runs'),
  listDiscoveryEndpoints: () => getData<DiscoveryEndpoint[]>('/discovery/endpoints'),
  listDiscoveryProbeAgents: () => getData<DiscoveryProbeAgent[]>('/discovery/v1/admin/agents'),
  listDiscoveryEnrollmentTokens: () => getData<DiscoveryEnrollmentToken[]>('/discovery/v1/admin/enrollment-tokens'),
  createDiscoveryEnrollmentToken: (payload: { tenantId?: string; siteName?: string; environmentName?: string; department?: string; labels?: Record<string, string>; ttlMinutes?: number; maxUses?: number; createdBy?: string }) =>
    postData<DiscoveryEnrollmentToken, typeof payload>('/discovery/v1/admin/enrollment-tokens', payload),
  approveDiscoveryContent: (id: number, payload: { approvalRole: 'PLATFORM_ADMIN' | 'DATA_OWNER'; approvedBy: string }) =>
    postData<DiscoveryProbeAgent, typeof payload>(`/discovery/v1/admin/agents/${id}/content-approval`, payload),
  revokeDiscoveryContent: (id: number, actor?: string) =>
    postData<DiscoveryProbeAgent, Record<string, never>>(`/discovery/v1/admin/agents/${id}/content-revoke${actor ? `?actor=${encodeURIComponent(actor)}` : ''}`, {}),
  enableDiscoveryContent: (id: number, actor?: string) =>
    postData<DiscoveryProbeAgent, Record<string, never>>(`/discovery/v1/admin/agents/${id}/content-enable${actor ? `?actor=${encodeURIComponent(actor)}` : ''}`, {}),
  approveDiscoveryFingerprint: (id: number, actor?: string) =>
    postData<DiscoveryProbeAgent, Record<string, never>>(`/discovery/v1/admin/agents/${id}/approve-fingerprint${actor ? `?actor=${encodeURIComponent(actor)}` : ''}`, {}),
  listDiscoveryIdentityLinks: () => getData<DiscoveryIdentityLink[]>('/discovery/identity-links'),
  probeProtocolDiscovery: (payload: { targetUrl: string; discoveryType: ProtocolDiscoveryType }) =>
    postData<ProtocolProbeResponse, { targetUrl: string; discoveryType: ProtocolDiscoveryType }>('/discovery/protocol/probe', payload),
  getPhysicalTelemetrySummary: (hours = 24) => getData<PhysicalTelemetrySummary>(`/telemetry/physical-summary?hours=${hours}`),
  getUsageReconciliation: (hours = 24, usagePoolKey?: string) => {
    const query = new URLSearchParams({ hours: String(hours) })
    if (usagePoolKey) query.set('usagePoolKey', usagePoolKey)
    return getData<UsageReconciliationSummary>(`/telemetry/usage-reconciliation?${query.toString()}`)
  },
  listProviderUsageSnapshots: (limit = 100) => getData<ProviderUsageSnapshot[]>(`/telemetry/provider-usage-snapshots?limit=${limit}`),
  recordProviderUsageSnapshot: (payload: {
    snapshotKey: string
    usagePoolKey: string
    provider: string
    accountLabel?: string
    windowStart: string
    windowEnd: string
    inputTokens: number
    outputTokens: number
    cachedInputTokens?: number
    reasoningTokens?: number
    moneyCost?: number
    currency?: string
    source: ProviderUsageSource
    observedAt?: string
  }) => postData<ProviderUsageSnapshot, typeof payload>('/telemetry/provider-usage-snapshots', payload),
  listModelInvocationEvents: (params: { limit?: number; agentId?: number; endpointKey?: string; runtimeInstanceKey?: string; traceId?: string; status?: ModelInvocationStatus; from?: string; to?: string } = {}) => {
    const query = new URLSearchParams()
    query.set('limit', String(params.limit ?? 100))
    Object.entries(params).forEach(([key, value]) => {
      if (key !== 'limit' && value !== undefined && value !== null && value !== '') query.set(key, String(value))
    })
    return getData<ModelInvocationEvent[]>(`/telemetry/model-invocations?${query.toString()}`)
  },
  getContentObservationStatus: () => getData<{ enabled: boolean; captureMode: string; maxContentBytes: number; totalObservations: number; inputObservations: number; outputObservations: number; lastObservedAt?: string }>('/observability/status'),
  listContentObservations: (params: { limit?: number; agentId?: number; endpointKey?: string; traceId?: string; direction?: string; from?: string; to?: string } = {}) => {
    const query = new URLSearchParams()
    query.set('limit', String(params.limit ?? 100))
    Object.entries(params).forEach(([key, value]) => {
      if (key !== 'limit' && value !== undefined && value !== null && value !== '') query.set(key, String(value))
    })
    return getData<ContentObservation[]>(`/observability/content?${query.toString()}`)
  },
  listTraceSpanObservations: (params: { limit?: number; agentId?: number; endpointKey?: string; traceId?: string; spanKind?: string; from?: string; to?: string } = {}) => {
    const query = new URLSearchParams()
    query.set('limit', String(params.limit ?? 100))
    Object.entries(params).forEach(([key, value]) => {
      if (key !== 'limit' && value !== undefined && value !== null && value !== '') query.set(key, String(value))
    })
    return getData<TraceSpanObservation[]>(`/observability/otlp/spans?${query.toString()}`)
  },
  listToolInvocationEvents: (params: { limit?: number; agentId?: number; endpointKey?: string; runtimeInstanceKey?: string; traceId?: string; toolKind?: string; status?: string; toolName?: string; from?: string; to?: string } = {}) => {
    const query = new URLSearchParams()
    query.set('limit', String(params.limit ?? 100))
    Object.entries(params).forEach(([key, value]) => {
      if (key !== 'limit' && value !== undefined && value !== null && value !== '') query.set(key, String(value))
    })
    return getData<ToolInvocationEvent[]>(`/observability/tool-invocations?${query.toString()}`)
  },
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
    usagePoolKey?: string
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
    metricName?: string
    metricUnit?: string
    baselineValue?: number
    currentValue?: number
    improvementDirection?: 'HIGHER_IS_BETTER' | 'LOWER_IS_BETTER'
    sourceType?: 'MANUAL' | 'BUSINESS_SYSTEM' | 'DERIVED'
    sourceSystem?: string
    sourceRecordKey?: string
    confidence?: number
    measurementWindowStart?: string
    measurementWindowEnd?: string
    agentContributions?: Array<{
      agentId: number
      contributionWeight: number
      attributionMethod?: 'BUSINESS_CONFIRMED' | 'TRACE_DERIVED' | 'EQUAL_SPLIT'
      evidenceReference?: string
    }>
  }) => postData<ManagementValueEvent, typeof payload>('/management/value-events', payload),
  listManagementConnectors: () => getData<ManagementValueConnector[]>('/management/connectors'),
  createManagementConnector: (payload: ManagementConnectorRequest) =>
    postData<ManagementValueConnector, ManagementConnectorRequest>('/management/connectors', payload),
  updateManagementConnector: (id: number, payload: ManagementConnectorRequest) =>
    putData<ManagementValueConnector, ManagementConnectorRequest>(`/management/connectors/${id}`, payload),
  setManagementConnectorEnabled: (id: number, enabled: boolean) =>
    postData<ManagementValueConnector, Record<string, never>>(`/management/connectors/${id}/enabled?enabled=${enabled}`, {}),
  syncManagementConnector: (id: number) =>
    postData<ManagementConnectorSyncResult, Record<string, never>>(`/management/connectors/${id}/sync`, {}),
  triggerAgentDiscoveryScan: () => postData<AgentDiscoveryRun, Record<string, never>>('/discovery/scans', {}),
  updateDiscoveredAgentStatus: (id: number, status: DiscoveryAssetStatus) =>
    postData<DiscoveredAgentAsset, { status: DiscoveryAssetStatus }>(`/discovery/agents/${id}/status`, { status }),
  manageDiscoveredAgent: (id: number) =>
    postData<DiscoveredAgentAsset, Record<string, never>>(`/discovery/agents/${id}/manage`, {}),
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
