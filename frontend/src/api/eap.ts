import { sendControlCommand, retryControlCommand } from './control'
import type { HistoryParams } from '@/components/common/HistoryTable.vue'
import { submitWorkflowOnce } from './workflowSubmission'
import { deleteData, getData, patchData, postData, putData } from './http'
import type { DatasetLookup } from '@/evaluation/datasetLookup'
import type {
  Agent,
  AgentLifecycleStatus,
  AgentRequest,
  AgentCapability,
  AgentPlan,
  WorkflowExecution,
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
  EvaluationCalibrationRunSummary,
  EvaluationCalibrationRunDetail,
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
  pageModelHistory: (params: HistoryParams & {agentId?:number; endpointKey?:string; traceId?:string; eventKey?:string}) => getData<{items:ModelInvocationEvent[]; totalElements:number}>(`/telemetry/model-invocations/page${queryString({...params})}`),
  pageContentHistory: (params: HistoryParams & {agentId?:number; endpointKey?:string; traceId?:string; eventKey?:string}) => getData<{items:ContentObservation[]; totalElements:number}>(`/observability/content/page${queryString({...params})}`),
  pageSpanHistory: (params: HistoryParams & {agentId?:number; endpointKey?:string; traceId?:string; eventKey?:string}) => getData<{items:TraceSpanObservation[]; totalElements:number}>(`/observability/otlp/spans/page${queryString({...params})}`),
  pageToolHistory: (params: HistoryParams & {agentId?:number; endpointKey?:string; traceId?:string; eventKey?:string}) => getData<{items:ToolInvocationEvent[]; totalElements:number}>(`/observability/tool-invocations/page${queryString({...params})}`),
  pageDecisionHistory: (params: HistoryParams & {agentId?:number; endpointKey?:string; traceId?:string; eventKey?:string}) => getData<{items:GovernancePreflightDecision[]; totalElements:number}>(`/governance/preflight-decisions/page${queryString({...params})}`),
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
  lookupEvaluationDataset: (datasetKey: string) =>
    getData<DatasetLookup>(`/evaluations/datasets/lookup${queryString({ datasetKey })}`, { silentError: true }),
  lookupEvaluationDatasetItem: (datasetKey: string, itemKey: string) =>
    getData<DatasetLookup>(`/evaluations/dataset-items/lookup${queryString({ datasetKey, itemKey })}`, { silentError: true }),
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
  listEvaluationCalibrationRuns: (datasetKey: string, limit = 50) =>
    getData<EvaluationCalibrationRunSummary[]>(`/evaluations/datasets/${encodeURIComponent(datasetKey)}/runs?limit=${limit}`),
  getEvaluationCalibrationRun: (datasetKey: string, runId: string) =>
    getData<EvaluationCalibrationRunDetail>(`/evaluations/datasets/${encodeURIComponent(datasetKey)}/runs/${encodeURIComponent(runId)}`),
  getEvaluationDatasetCalibration: (datasetKey: string, limit = 200) =>
    getData<EvaluationDatasetCalibration>(`/evaluations/datasets/${encodeURIComponent(datasetKey)}/calibration?limit=${limit}`),
  createEvaluationDatasetItem: (datasetKey: string, payload: {
    itemKey: string
    evaluatorKey: string
    evaluatorVersion?: string
    agentId?: number
    traceId?: string
    spanId?: string
    verifySourceReference?: boolean
    input: Record<string, unknown>
    expected?: unknown
    actual: Record<string, unknown>
  }) => postData<EvaluationDatasetItem, typeof payload>(`/evaluations/datasets/${encodeURIComponent(datasetKey)}/items`, payload),
  annotateEvaluationDatasetItem: (datasetKey: string, itemKey: string, payload: {
    groundTruthLabel: 'PASS' | 'FAIL' | 'ABSTAIN'
    groundTruthScore?: number
    annotatedBy?: string
    rationale?: string
  }) => patchData<EvaluationDatasetItem, typeof payload>(
    `/evaluations/datasets/${encodeURIComponent(datasetKey)}/items/${encodeURIComponent(itemKey)}/annotation`, payload,
  ),
  listEvaluationResults: (params: { agentId?: number; traceId?: string; limit?: number } = {}) =>
    getData<EvaluationResult[]>(`/evaluations/results${queryString(params)}`),
  getEvaluationSummary: (hours = 24) => getData<EvaluationSummary>(`/evaluations/summary?hours=${hours}`),
  getContentAnalysisStatus: () => getData<{ enabled: boolean; counts: Record<string, number> }>('/observability/content-analysis'),
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
  issueControlCommand: sendControlCommand,
  retryControlCommand,
  pageAuditLogs: (params: HistoryParams) => getData<{items: AuditLog[]; totalElements: number}>(`/audit-logs/page${queryString({...params})}`),
  listAuditLogs: () => getData<AuditLog[]>('/audit-logs'),
  listAgents: () => getData<Agent[]>('/agents'),
  createAgent: (payload: AgentRequest) => postData<Agent, AgentRequest>('/agents', payload),
  updateAgentStatus: (id: number, lifecycleStatus: AgentLifecycleStatus) =>
    postData<Agent, { lifecycleStatus: AgentLifecycleStatus }>(`/agents/${id}/status`, { lifecycleStatus }),
  listPolicies: () => getData<GovernancePolicy[]>('/policies'),
  createPolicy: (payload: Omit<GovernancePolicy, 'id' | 'revision' | 'createdAt' | 'updatedAt'> & { expectedRevision?: number }) =>
    postData<GovernancePolicy, Omit<GovernancePolicy, 'id' | 'revision' | 'createdAt' | 'updatedAt'> & { expectedRevision?: number }>('/policies', payload),
  updatePolicy: (id: number, payload: Omit<GovernancePolicy, 'id' | 'revision' | 'createdAt' | 'updatedAt'> & { expectedRevision?: number }) =>
    putData<GovernancePolicy, Omit<GovernancePolicy, 'id' | 'revision' | 'createdAt' | 'updatedAt'> & { expectedRevision?: number }>(`/policies/${id}`, payload),
  getGovernanceDataSummary: (hours = 24) => getData<GovernanceDataSummary>(`/governance/data-summary?hours=${hours}`),
  pageGovernanceEvents: (params: HistoryParams & {agentId?:number; endpointKey?:string; eventKey?:string}) => getData<{items: GovernanceDataEvent[]; totalElements:number}>(`/governance/data-events/page${queryString({...params})}`),
  listGovernanceDataEvents: (limit = 100, params: { agentId?: number; page?: number } = {}) =>
    getData<GovernanceDataEvent[]>(`/governance/data-events${queryString({ limit, ...params })}`),
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
  pageTasks: (params: HistoryParams) => getData<{items: AgentTask[]; totalElements: number}>(`/tasks/page${queryString({...params})}`),
  listTasks: () => getData<AgentTask[]>('/tasks'),
  createTask: (payload: { agentId: number; agentVersionId?: number; taskType: string; inputSummary?: string; requester?: string }) =>
    postData<AgentTask, { agentId: number; agentVersionId?: number; taskType: string; inputSummary?: string; requester?: string }>('/tasks', payload),
  updateTaskStatus: (id: number, status: AgentTaskStatus, resultSummary?: string, problemResolved?: boolean, resolutionSummary?: string) =>
    postData<AgentTask, { status: AgentTaskStatus; resultSummary?: string; problemResolved?: boolean; resolutionSummary?: string }>(`/tasks/${id}/status`, { status, resultSummary, problemResolved, resolutionSummary }),
  feedbackEvidence: (agentId: number) => getData<{tasks: {id: number; taskType: string; status: string}[]; traces: string[]}>(`/learning-feedbacks/evidence?agentId=${agentId}`),
  listLearningFeedbacks: (agentId?: number) => getData<LearningFeedback[]>(`/learning-feedbacks?limit=100${agentId ? `&agentId=${agentId}` : ''}`),
  createLearningFeedback: (payload: { agentId: number; feedbackType: string; rating: number; summary: string; improvementAction?: string; taskId?: number; traceId?: string; requestKey: string }) =>
    postData<LearningFeedback, typeof payload>('/learning-feedbacks', payload),
  updateLearningFeedbackStatus: (id: number, status: LearningFeedbackStatus, expectedStatus: LearningFeedbackStatus) =>
    postData<LearningFeedback, { status: LearningFeedbackStatus; expectedStatus: LearningFeedbackStatus }>(`/learning-feedbacks/${id}/status`, { status, expectedStatus }),
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
  listModelInvocationEvents: (params: { limit?: number; page?: number; agentId?: number; endpointKey?: string; runtimeInstanceKey?: string; traceId?: string; status?: ModelInvocationStatus; from?: string; to?: string } = {}) => {
    const query = new URLSearchParams()
    query.set('limit', String(params.limit ?? 100))
    Object.entries(params).forEach(([key, value]) => {
      if (key !== 'limit' && value !== undefined && value !== null && value !== '') query.set(key, String(value))
    })
    return getData<ModelInvocationEvent[]>(`/telemetry/model-invocations?${query.toString()}`)
  },
  getContentObservationStatus: () => getData<{ enabled: boolean; captureMode: string; maxContentBytes: number; totalObservations: number; inputObservations: number; outputObservations: number; lastObservedAt?: string }>('/observability/status'),
  listContentObservations: (params: { limit?: number; page?: number; agentId?: number; endpointKey?: string; traceId?: string; direction?: string; from?: string; to?: string } = {}) => {
    const query = new URLSearchParams()
    query.set('limit', String(params.limit ?? 100))
    Object.entries(params).forEach(([key, value]) => {
      if (key !== 'limit' && value !== undefined && value !== null && value !== '') query.set(key, String(value))
    })
    return getData<ContentObservation[]>(`/observability/content?${query.toString()}`)
  },
  listTraceSpanObservations: (params: { limit?: number; page?: number; agentId?: number; endpointKey?: string; traceId?: string; spanKind?: string; from?: string; to?: string } = {}) => {
    const query = new URLSearchParams()
    query.set('limit', String(params.limit ?? 100))
    Object.entries(params).forEach(([key, value]) => {
      if (key !== 'limit' && value !== undefined && value !== null && value !== '') query.set(key, String(value))
    })
    return getData<TraceSpanObservation[]>(`/observability/otlp/spans?${query.toString()}`)
  },
  listToolInvocationEvents: (params: { limit?: number; page?: number; agentId?: number; endpointKey?: string; runtimeInstanceKey?: string; traceId?: string; toolKind?: string; status?: string; toolName?: string; from?: string; to?: string } = {}) => {
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
  listKnowledgeInteractions: (limit = 100, params: { agentId?: number; page?: number } = {}) =>
    getData<KnowledgeInteraction[]>(`/knowledge/interactions${queryString({ limit, ...params })}`),
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
  getManagementSummary: (hours = 24, currency?: string) => getData<ManagementSummary>(`/management/summary?hours=${hours}${currency ? `&currency=${encodeURIComponent(currency)}` : ''}`),
  pageManagementValues: (params: HistoryParams & {agentId?:number; endpointKey?:string; currency?:string}) => getData<{items: ManagementValueEvent[]; totalElements:number}>(`/management/value-events/page${queryString({...params})}`),
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
  updateTeam: (teamId: number, payload: { expectedRevision: number; name: string; objective?: string; coordinationMode: string; workspacePolicy: string }) =>
    patchData<AgentTeam, typeof payload>(`/orchestration/teams/${teamId}`, payload),
  deleteTeam: (teamId: number, expectedRevision: number) => deleteData<void, { expectedRevision: number }>(`/orchestration/teams/${teamId}`, { expectedRevision }),
  updateTeamMember: (teamId: number, memberId: number, payload: { expectedRevision: number; role: string; responsibility: string }) =>
    patchData<AgentTeamMember, typeof payload>(`/orchestration/teams/${teamId}/members/${memberId}`, payload),
  removeTeamMember: (teamId: number, memberId: number, expectedRevision: number) =>
    deleteData<void, { expectedRevision: number }>(`/orchestration/teams/${teamId}/members/${memberId}`, { expectedRevision }),
  addTeamMember: (teamId: number, payload: { agentId: number; role: string; responsibility: string }) =>
    postData<AgentTeamMember, { agentId: number; role: string; responsibility: string }>(`/orchestration/teams/${teamId}/members`, payload),
  listTeamPlans: (teamId: number) => getData<AgentPlan[]>(`/orchestration/teams/${teamId}/plans`),
  createPlan: (payload: { teamId: number; goal: string; contextSummary?: string }) =>
    postData<AgentPlan, { teamId: number; goal: string; contextSummary?: string }>('/orchestration/plans', payload),
  executePlan: (planId: number, payload: { requestKey: string; suspendAfterTask: boolean }) =>
    postData<{ planId: number; workflowId: number; execution: WorkflowExecution }, typeof payload>(`/orchestration/plans/${planId}/executions`, payload),
  getPlanExecution: (planId: number) => getData<{ planId: number; workflowId: number; execution: WorkflowExecution } | null>(`/orchestration/plans/${planId}/execution`),
  getWorkflowOutputs: (workflowId: number, runKey: string) =>
    getData<Record<string, unknown>>(`/workflows/${workflowId}/executions/${encodeURIComponent(runKey)}/outputs`),
  getPlan: (planId: number) => getData<AgentPlanDetail>(`/orchestration/plans/${planId}`),
  updatePlan: (planId: number, payload: { expectedRevision: number; goal: string; contextSummary?: string }) =>
    patchData<AgentPlan, typeof payload>(`/orchestration/plans/${planId}`, payload),
  copyPlan: (planId: number, payload: { expectedRevision: number; goal: string; contextSummary?: string }) =>
    postData<AgentPlan, typeof payload>(`/orchestration/plans/${planId}/copies`, payload),
  deletePlan: (planId: number, expectedRevision: number) => deleteData<void, { expectedRevision: number }>(`/orchestration/plans/${planId}`, { expectedRevision }),
  updatePlanStep: (stepId: number, payload: { expectedRevision: number; stepOrder: number; title: string; requiredCapability: string; executionBackend: string; outputContractJson: string | null }) =>
    patchData<AgentPlanStep, typeof payload>(`/orchestration/steps/${stepId}`, payload),
  deletePlanStep: (stepId: number, expectedRevision: number) => deleteData<void, { expectedRevision: number }>(`/orchestration/steps/${stepId}`, { expectedRevision }),
  reorderPlanSteps: (planId: number, expectedRevision: number, stepIds: number[]) =>
    postData<AgentPlanDetail, { expectedRevision: number; stepIds: number[] }>(`/orchestration/plans/${planId}/steps/order`, { expectedRevision, stepIds }),
  addPlanStep: (planId: number, payload: { stepOrder: number; title: string; requiredCapability: string; executionBackend?: string; outputContractJson?: string | null }) =>
    postData<AgentPlanStep, { stepOrder: number; title: string; requiredCapability: string }>(`/orchestration/plans/${planId}/steps`, payload),
  updatePlanOutputContract: (stepId: number, outputContractJson: string | null) =>
    postData<AgentPlanStep, { outputContractJson: string | null }>(`/orchestration/steps/${stepId}/output-contract`, { outputContractJson }),
  assignPlanStep: (stepId: number, payload: { assignedAgentId?: number | null; status: 'PENDING' | 'ASSIGNED' }) =>
    postData<AgentPlanStep, typeof payload>(`/orchestration/steps/${stepId}/assignment`, payload),
  listWorkflows: () => getData<WorkflowDefinition[]>('/workflows'),
  createWorkflow: (payload: { code: string; name: string; description?: string }) =>
    postData<WorkflowDefinition, { code: string; name: string; description?: string }>('/workflows', payload),
  getWorkflow: (workflowId: number) => getData<WorkflowDetail>(`/workflows/${workflowId}`),
  upsertWorkflowNode: (workflowId: number, payload: {
    expectedVersion?: number
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
  runWorkflow: (workflowId: number, payload: { objective?: string; context?: { severity: string; approved: boolean } }) =>
    postData<WorkflowRun, typeof payload>(`/workflows/${workflowId}/runs`, payload),
  getWorkflowToolRecovery: (workflowId: number, runKey: string) => getData<import('@/types/domain').WorkflowToolRecovery>(`/workflows/${workflowId}/executions/${encodeURIComponent(runKey)}/tool-recovery`),
  workflowExecutionStatus: () => getData<{ enabled: boolean; protocol: string; message: string }>('/workflows/execution-status'),
  executeWorkflow: (workflowId: number, payload: { objective?: string; context?: { severity: string; approved: boolean } }) =>
    submitWorkflowOnce(workflowId, payload, request =>
      postData<import('@/types/domain').WorkflowExecution, typeof request>(`/workflows/${workflowId}/executions`, request)),
  listWorkflowExecutions: (workflowId: number) =>
    getData<import('@/types/domain').WorkflowExecution[]>(`/workflows/${workflowId}/executions`),
  getWorkflowExecution: (workflowId: number, runKey: string) =>
    getData<WorkflowExecution>(`/workflows/${workflowId}/executions/${encodeURIComponent(runKey)}`),
}
