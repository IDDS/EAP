export type AgentLifecycleStatus = 'DRAFT' | 'REVIEWING' | 'ACTIVE' | 'PAUSED' | 'RETIRED'
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type AgentRecordOrigin = 'PRODUCTION' | 'DEMO'
export type PolicyType = 'PERMISSION' | 'PRIVACY' | 'SECURITY' | 'ALGORITHM' | 'BENEFIT'
export type EnforcementMode = 'WARN' | 'BLOCK' | 'APPROVAL_REQUIRED'
export type AgentTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type LearningFeedbackStatus = 'OPEN' | 'REVIEWING' | 'ADOPTED' | 'REJECTED'
export type SafetyEventType = 'AGENT_MESSAGE' | 'TOOL_CALL' | 'DATA_ACCESS' | 'ARTIFACT_WRITE' | 'DELEGATION' | 'POLICY_CHECK'
export type SafetyDecision = 'ALLOW' | 'ASK' | 'DENY'
export type SafetySeverity = 'INFO' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
export type RuntimeStatus = 'ONLINE' | 'DEGRADED' | 'OFFLINE'
export type DiscoveryAssetStatus = 'DISCOVERED' | 'REVIEWING' | 'CONFIRMED' | 'MANAGED' | 'IGNORED'
export type DiscoveryAssetType = 'AGENT' | 'AGENT_PROJECT' | 'MCP_CONFIGURATION' | 'AGENT_SKILL' | 'AGENT_PLUGIN'
export type DiscoverySourceType = 'PROCESS' | 'MANIFEST' | 'CONFIGURATION' | 'SKILL' | 'PLUGIN' | 'EXTERNAL' | 'DOCKER' | 'A2A' | 'MCP_REGISTRY' | 'KUBERNETES_WATCH' | 'KUBERNETES_ADMISSION' | 'EBPF' | 'HOST_OBSERVER'
export type DiscoveryRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type DiscoveryTriggerType = 'SCHEDULED' | 'MANUAL' | 'ENDPOINT'
export type ModelInvocationStatus = 'STARTED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type ModelObservationSource = 'GATEWAY' | 'TELEMETRY_API' | 'OTLP' | 'CONTENT_PROXY'
export type ProviderUsageSource = 'PROVIDER_API' | 'BILLING_EXPORT' | 'MANUAL'
export type KnowledgeInteractionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type KnowledgeStepType = 'INPUT_ANALYSIS' | 'RETRIEVAL' | 'TOOL' | 'REASONING' | 'TRANSFORM' | 'OUTPUT_ANALYSIS'
export type KnowledgeStepStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'
export type GovernanceDataDirection = 'INPUT_TO_MODEL' | 'OUTPUT_FROM_MODEL' | 'TOOL_INPUT' | 'TOOL_OUTPUT'
export type GovernanceDataSensitivity = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'SECRET' | 'RESTRICTED'
export type GovernanceDataAction = 'ALLOW' | 'WARN' | 'REDACT' | 'BLOCK' | 'APPROVAL_REQUIRED'
export type GovernanceGatewayDirective = 'PROCEED' | 'PROCEED_WITH_WARNING' | 'REDACT_AND_RECHECK' | 'REQUEST_APPROVAL' | 'BLOCK'
export type GovernanceApprovalStatus = 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'REJECTED'
export type TeamStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED'
export type PlanStatus = 'CANCELLED' | 'DRAFT' | 'READY' | 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'BLOCKED' | 'WAITING_APPROVAL' | 'EXECUTION_UNKNOWN'
export type StepStatus = 'PENDING' | 'ASSIGNED' | 'RUNNING' | 'DONE' | 'BLOCKED' | 'WAITING_APPROVAL' | 'FAILED' | 'EXECUTION_UNKNOWN' | 'SKIPPED'
export type WorkflowStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type WorkItemSeverity = 'P0' | 'P1' | 'P2' | 'P3'
export type WorkItemStatus = 'OPEN' | 'CLAIMED' | 'IN_PROGRESS' | 'WAITING' | 'RESOLVED' | 'CLOSED'
export type WorkItemType =
  | 'DISCOVERY_REVIEW'
  | 'OWNER_ASSIGNMENT'
  | 'IDENTITY_REVIEW'
  | 'PROBE_OFFLINE'
  | 'RUNTIME_DEGRADED'
  | 'RUNTIME_OFFLINE'
  | 'SECURITY_RISK'
  | 'GOVERNANCE_APPROVAL'
  | 'CONNECTOR_FAILURE'
  | 'OBSERVABILITY_GAP'
  | 'EVALUATION_FAILURE'
  | 'USAGE_RECONCILIATION'
  | 'GENERAL'
export type EvidenceBasis = 'MEASURED' | 'EXTERNAL_RECONCILED' | 'HUMAN_CONFIRMED' | 'DERIVED' | 'ESTIMATED' | 'DEMO'
export type OperationalHealth = 'HEALTHY' | 'DEGRADED' | 'OFFLINE' | 'NOT_CONNECTED'
export type OnboardingState = 'NEEDS_REVIEW' | 'NEEDS_OWNER' | 'MANAGED' | 'MANUAL'
export type ObservationCoverage = 'FULL' | 'PARTIAL' | 'MISSING'
export type RiskPosture = 'NORMAL' | 'ATTENTION' | 'HIGH' | 'CRITICAL'

export interface ApiResponse<T> {
  success: boolean
  data: T
  message: string
  traceId: string
}

export interface DashboardSummary {
  agentCount: number
  activeAgentCount: number
  runningTaskCount: number
  riskEventCount: number
  successRate: number
  averageLatencyMs: number
}

export interface NetworkNode {
  id: string
  label: string
  nodeType: string
  plane: 'CONTROL' | 'DATA'
  status: string
  riskLevel: string
  metric: string
  x: number
  y: number
}

export interface NetworkEdge {
  id: string
  source: string
  target: string
  label: string
  plane: 'CONTROL' | 'DATA'
  relationType: string
  status: string
}

export interface NetworkStats {
  controlNodeCount: number
  agentNodeCount: number
  controlEdgeCount: number
  dataEdgeCount: number
}

export interface DashboardNetwork {
  nodes: NetworkNode[]
  edges: NetworkEdge[]
  stats: NetworkStats
}

export interface PlatformStatus {
  oidc: import('@/api/oidc').OidcConfig | null
  securityMode: string
  controlDispatchEnabled: boolean
  controlDispatchMode: string
  generatedAt: string
}

export interface AgentOpsWorkItem {
  id: number
  itemKey: string
  itemType: WorkItemType
  title: string
  description: string
  severity: WorkItemSeverity
  status: WorkItemStatus
  agentId?: number
  endpointKey?: string
  runtimeInstanceKey?: string
  sourceType: string
  sourceId?: string
  organizationScope?: string
  assignee?: string
  assignedTeam?: string
  evidenceBasis: EvidenceBasis
  evidenceJson?: string
  occurrenceCount: number
  firstDetectedAt: string
  lastDetectedAt: string
  slaDueAt?: string
  overdue: boolean
  resolution?: string
  resolutionEvidenceJson?: string
  resolvedAt?: string
  closedAt?: string
  createdAt: string
  updatedAt: string
}

export interface AgentOpsWorkItemEvent {
  id: number
  workItemId: number
  eventType: string
  actor: string
  fromStatus?: string
  toStatus?: string
  note?: string
  evidenceJson?: string
  createdAt: string
}

export interface AgentOpsAgentState {
  id: number
  code: string
  name: string
  description?: string
  ownerDepartment?: string
  ownerUser?: string
  category?: string
  lifecycleStatus: AgentLifecycleStatus
  declaredRiskLevel: RiskLevel
  operationalHealth: OperationalHealth
  onboardingState: OnboardingState
  observationCoverage: ObservationCoverage
  riskPosture: RiskPosture
  coverageScore: number
  coverageSignals: string[]
  runtimeCount: number
  onlineRuntimeCount: number
  openWorkItemCount: number
  latestObservedAt?: string
  dataFreshness: 'FRESH' | 'AGING' | 'STALE' | 'NO_DATA'
  statusBasis: EvidenceBasis
  updatedAt: string
}

export interface PagedAgentOpsResponse {
  items: AgentOpsAgentState[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AgentOpsMetricEvidence {
  key: string
  label: string
  value: number
  basis: EvidenceBasis
  source: string
  measuredAt?: string
  freshness: string
}

export interface AgentOpsDashboard {
  lastSignalSyncAt: string | null
  signalSyncFreshness: 'NO_DATA' | 'FRESH' | 'AGING' | 'STALE'
  windowHours: number
  windowStart: string
  windowEnd: string
  timeZone: string
  organizationScope: string
  agentCount: number
  healthyAgentCount: number
  degradedAgentCount: number
  offlineAgentCount: number
  missingObservationCount: number
  highRiskAgentCount: number
  workItems: {
    totalOpen: number
    p0: number
    p1: number
    p2: number
    p3: number
    overdue: number
  }
  metrics: AgentOpsMetricEvidence[]
  priorityWorkItems: AgentOpsWorkItem[]
  attentionAgents: AgentOpsAgentState[]
  demoDataIncluded: boolean
  generatedAt: string
}

export type ControlCommandStatus = 'QUEUED' | 'DISPATCHED' | 'ACKED' | 'FAILED'

export interface AgentControlCommand {
  id: number
  commandKey: string
  agentId: number
  commandType: string
  payloadJson?: string
  status: ControlCommandStatus
  issuedBy: string
  dispatchMode: string
  runtimeInstanceKey?: string
  attemptCount?: number
  httpStatus?: number
  resultMessage?: string
  createdAt: string
  updatedAt: string
  dispatchedAt?: string
  acknowledgedAt?: string
}

export interface AuditLog {
  id: number
  actor: string
  action: string
  resourceType: string
  resourceId?: string
  beforeJson?: string
  afterJson?: string
  result: string
  traceId: string
  createdAt: string
}

export interface Agent {
  id: number
  code: string
  name: string
  description?: string
  ownerDepartment: string
  ownerUser: string
  category: string
  lifecycleStatus: AgentLifecycleStatus
  riskLevel: RiskLevel
  recordOrigin: AgentRecordOrigin
  createdAt: string
  updatedAt: string
}

export interface AgentRequest {
  code: string
  name: string
  description?: string
  ownerDepartment: string
  ownerUser: string
  category: string
  riskLevel: RiskLevel
}

export interface GovernancePolicy {
  id: number
  revision: number
  name: string
  policyType: PolicyType
  scopeType: string
  scopeValue?: string
  ruleJson: string
  enforcementMode: EnforcementMode
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface AgentTask {
  id: number
  agentId: number
  agentVersionId?: number
  taskType: string
  inputSummary?: string
  requester?: string
  status: AgentTaskStatus
  startedAt?: string
  finishedAt?: string
  latencyMs?: number
  tokenCost?: number
  moneyCost?: number
  resultSummary?: string
  problemResolved?: boolean
  resolutionSummary?: string
  createdAt: string
  updatedAt: string
}

export interface LearningFeedback {
  id: number
  agentId: number
  feedbackType: string
  rating: number
  summary?: string
  improvementAction?: string
  taskId?: number
  traceId?: string
  requestedBy?: string
  reviewedBy?: string
  contentVisible: boolean
  status: LearningFeedbackStatus
  createdAt: string
  updatedAt: string
}

export interface SafetySession {
  id: number
  sessionKey: string
  rootAgentId?: number
  objective: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface SafetyEvent {
  id: number
  sessionKey: string
  agentId?: number
  parentAgentId?: number
  eventType: SafetyEventType
  action: string
  target: string
  payloadSummary?: string
  createdAt: string
}

export interface SafetyDecisionLog {
  id: number
  eventId: number
  sessionKey: string
  decision: SafetyDecision
  severity: SafetySeverity
  reasonCode: string
  reason: string
  controlActions: string
  sidecarMode: string
  createdAt: string
}

export interface SafetyEvaluation {
  event: SafetyEvent
  decision: SafetyDecisionLog
}

export interface SafetySummary {
  sessionCount: number
  eventCount: number
  denyCount: number
  askCount: number
  criticalAlertCount: number
  recentDecisions: SafetyDecisionLog[]
}

export interface AgentCapability {
  id: number
  agentId: number
  capability: string
  name: string
  description?: string
  createdAt: string
  updatedAt: string
}

export interface AgentRuntimeInstance {
  id: number
  agentId: number
  instanceKey: string
  endpoint: string
  protocol: string
  dispatchAuthenticated: boolean
  status: RuntimeStatus
  loadScore: number
  lastHeartbeatAt?: string
  createdAt: string
  updatedAt: string
}

export interface DiscoveryCandidate {
  agentId: number
  code: string
  name: string
  ownerDepartment: string
  lifecycleStatus: AgentLifecycleStatus
  riskLevel: RiskLevel
  capability: string
  endpoint: string
  protocol: string
  runtimeStatus: RuntimeStatus
  loadScore: number
  score: number
  constraints: string[]
}

export interface DiscoveryResponse {
  candidates: DiscoveryCandidate[]
}

export interface DiscoveredAgentAsset {
  id: number
  fingerprint: string
  name: string
  vendor: string
  product: string
  version?: string
  assetType: DiscoveryAssetType
  status: DiscoveryAssetStatus
  riskLevel: RiskLevel
  confidence: number
  sourceTypes: DiscoverySourceType[]
  hostName?: string
  accountName?: string
  processName?: string
  executablePath?: string
  autoApprove?: boolean
  trustedProcess?: boolean
  mcpServerCount: number
  evidenceCount: number
  managedAgentId?: number
  firstSeenAt: string
  lastSeenAt: string
  createdAt: string
  updatedAt: string
}

export interface AgentDiscoveryRun {
  id: number
  triggerType: DiscoveryTriggerType
  endpointKey?: string
  status: DiscoveryRunStatus
  observationsCount: number
  discoveredCount: number
  errorMessage?: string
  startedAt: string
  finishedAt?: string
}

export interface DiscoveryEndpoint {
  id: number
  endpointKey: string
  hostName: string
  accountName?: string
  operatingSystem?: string
  collectorVersion?: string
  status: string
  firstSeenAt: string
  lastSeenAt: string
  createdAt: string
  updatedAt: string
}

export interface DiscoveryProbeAgent {
  id: number
  endpointKey: string
  hostName: string
  accountName?: string
  operatingSystem?: string
  architecture?: string
  agentVersion?: string
  status: string
  tenantId: string
  siteName?: string
  environmentName?: string
  department?: string
  labels: Record<string, string>
  capabilities: string[]
  policyVersion?: string
  rulesVersion?: string
  lastHeartbeatAt?: string
  lastScanAt?: string
  queueDepth: number
  queueBytes: number
  lastError?: string
  fingerprintReviewRequired: boolean
  contentCaptureStatus: string
  contentPlatformApprovedBy?: string
  contentDataApprovedBy?: string
  contentApprovalMode: 'DUAL' | 'NONE'
  contentCaptureEnabled: boolean
  collectionScopes: string[]
  tokenUsageStatus: string
  tokenUsageNote?: string
  firstSeenAt: string
  lastSeenAt: string
  uninstalledAt?: string
}

export interface DiscoveryEnrollmentToken {
  id: number
  token?: string
  tokenHint: string
  tenantId: string
  siteName?: string
  environmentName?: string
  department?: string
  labels: Record<string, string>
  maxUses: number
  usedCount: number
  status: string
  expiresAt: string
  createdBy: string
  createdAt: string
}

export interface DiscoveryIdentityLink {
  id: number
  identityKey: string
  discoveredAgentId: number
  relationType: 'EXACT' | 'HEURISTIC'
  confidence: number
  identityBasis?: string
  sourceTypes: DiscoverySourceType[]
  firstSeenAt: string
  lastSeenAt: string
}

export type ProtocolDiscoveryType = 'AUTO' | 'A2A' | 'MCP_REGISTRY' | 'MCP_SERVER_JSON'

export interface ProtocolProbeResponse {
  targetUrl: string
  discoveryType: ProtocolDiscoveryType
  endpointsRegistered: number
  documentsFetched: number
  a2aAgentsDiscovered: number
  mcpServersDiscovered: number
  warnings: string[]
}

export interface ModelInvocationEvent {
  id: number
  eventKey: string
  traceId?: string
  spanId?: string
  agentId?: number
  endpointKey?: string
  runtimeInstanceKey?: string
  taskId?: number
  governanceDecisionKey?: string
  observationSource: ModelObservationSource
  governanceCovered: boolean
  usagePoolKey?: string
  provider: string
  modelName: string
  status: ModelInvocationStatus
  inputTokens: number
  outputTokens: number
  cachedInputTokens: number
  reasoningTokens: number
  totalTokens: number
  latencyMs: number
  moneyCost: number
  currency: string
  toolCallCount: number
  mcpServerCount: number
  errorType?: string
  observedAt: string
  createdAt: string
}

export type ContentDirection = 'INPUT' | 'OUTPUT' | 'TOOL_INPUT' | 'TOOL_OUTPUT' | 'SYSTEM'
export type ContentCaptureMode = 'OFF' | 'HASH_ONLY' | 'REDACTED'

export interface ContentObservation {
  id: number
  observationKey: string
  eventKey?: string
  traceId?: string
  spanId?: string
  agentId?: number
  endpointKey?: string
  taskId?: number
  provider?: string
  modelName?: string
  direction: ContentDirection
  captureMode: ContentCaptureMode
  contentHash: string
  contentBytes: number
  estimatedTokens: number
  contentJson?: string
  dataCategory?: string
  sensitivity?: string
  governanceAction?: string
  redactionApplied: boolean
  observedAt: string
  createdAt: string
}

export type TraceSpanKind = 'MODEL' | 'TOOL' | 'MCP' | 'RAG' | 'A2A' | 'AGENT'

export interface TraceSpanObservation {
  id: number
  traceId: string
  spanId: string
  parentSpanId?: string
  endpointKey: string
  agentId?: number
  spanName: string
  spanKind: TraceSpanKind
  operation?: string
  toolName?: string
  mcpServerName?: string
  ragSource?: string
  status?: string
  durationMs: number
  attributesJson?: string
  observedAt: string
  /** Producer timestamps; absent on legacy/synthetic observations. */
  startedAt?: string | null
  endedAt?: string | null
  /** Frontend fallback when a model invocation has a trace ID but no OTLP child span yet. */
  synthetic?: boolean
}

export type ToolInvocationStatus = 'REQUESTED' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'
export type ToolInvocationKind = 'TOOL' | 'MCP'

export interface ToolInvocationEvent {
  id: number
  invocationKey: string
  traceId?: string
  spanId?: string
  parentSpanId?: string
  agentId?: number
  endpointKey?: string
  runtimeInstanceKey?: string
  taskId?: number
  observationSource: 'OTLP' | 'GATEWAY' | 'RUNTIME_GUARD'
  toolKind: ToolInvocationKind
  toolName: string
  mcpServerName?: string
  status: ToolInvocationStatus
  durationMs: number
  inputObservationKey?: string
  outputObservationKey?: string
  attributesJson?: string
  observedAt: string
  createdAt: string
  updatedAt: string
}

export interface TelemetrySubjectUsage {
  subjectKey: string
  subjectName: string
  agentId?: number
  endpointKey?: string
  calls: number
  succeededCalls: number
  failedCalls: number
  successRate: number
  inputTokens: number
  outputTokens: number
  totalTokens: number
  moneyCost: number
  averageLatencyMs: number
  lastEventAt: string
}

export interface TelemetryModelUsage {
  provider: string
  modelName: string
  calls: number
  totalTokens: number
  moneyCost: number
  averageLatencyMs: number
}

export interface PhysicalTelemetrySummary {
  windowHours: number
  totalCalls: number
  succeededCalls: number
  failedCalls: number
  successRate: number
  inputTokens: number
  outputTokens: number
  cachedInputTokens: number
  reasoningTokens: number
  totalTokens: number
  moneyCost: number
  averageLatencyMs: number
  p95LatencyMs: number
  registeredEndpoints: number
  activeEndpoints: number
  onlineRuntimeCount: number
  degradedRuntimeCount: number
  offlineRuntimeCount: number
  lastEventAt?: string
  subjectUsage: TelemetrySubjectUsage[]
  modelUsage: TelemetryModelUsage[]
}

export interface ProviderUsageSnapshot {
  id: number
  snapshotKey: string
  usagePoolKey: string
  provider: string
  accountLabel?: string
  windowStart: string
  windowEnd: string
  inputTokens: number
  outputTokens: number
  cachedInputTokens: number
  reasoningTokens: number
  totalTokens: number
  moneyCost: number
  currency: string
  source: ProviderUsageSource
  observedAt: string
  createdAt: string
}

export interface UsagePoolReconciliation {
  usagePoolKey: string
  provider: string
  accountLabel?: string
  currency: string
  snapshotCount: number
  attributedCalls: number
  attributedAgents: number
  providerInputTokens: number
  providerOutputTokens: number
  providerTotalTokens: number
  providerMoneyCost: number
  attributedInputTokens: number
  attributedOutputTokens: number
  attributedTotalTokens: number
  attributedMoneyCost: number
  unallocatedTokens: number
  unallocatedMoneyCost: number
  overAttributedTokens: number
  overAttributedMoneyCost: number
  tokenCoverageRate: number
  costCoverageRate: number
  providerWindowStart?: string
  providerWindowEnd?: string
  lastSnapshotAt?: string
}

export interface UsageReconciliationSummary {
  windowHours: number
  windowStart: string
  windowEnd: string
  snapshotCount: number
  usagePoolCount: number
  unmappedTelemetryCalls: number
  unmappedTelemetryTokens: number
  unmappedTelemetryMoneyCost: number
  pools: UsagePoolReconciliation[]
}

export interface KnowledgeTraceStep {
  id: number
  stepOrder: number
  stepType: KnowledgeStepType
  name: string
  status: KnowledgeStepStatus
  sourceCount: number
  inputTokens: number
  outputTokens: number
  latencyMs: number
  knowledgeRefs: string[]
  observedAt: string
}

export interface KnowledgeInteraction {
  id: number
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
  status: KnowledgeInteractionStatus
  adopted: boolean
  reused: boolean
  qualityScore?: number
  processStepCount: number
  processTokenCount: number
  processLatencyMs: number
  startedAt?: string
  completedAt?: string
  createdAt: string
  processSteps: KnowledgeTraceStep[]
}

export interface KnowledgeAgentUsage {
  subjectKey: string
  subjectName: string
  agentId?: number
  endpointKey?: string
  interactions: number
  inputTokens: number
  outputTokens: number
  adoptedInteractions: number
  reusedInteractions: number
  adoptionRate: number
  reuseRate: number
  averageQualityScore: number
  lastInteractionAt: string
}

export interface KnowledgeSummary {
  windowHours: number
  totalInteractions: number
  succeededInteractions: number
  failedInteractions: number
  successRate: number
  inputTokens: number
  outputTokens: number
  inputSourceCount: number
  outputSourceCount: number
  adoptedInteractions: number
  reusedInteractions: number
  adoptionRate: number
  reuseRate: number
  averageQualityScore: number
  averageProcessLatencyMs: number
  lastInteractionAt?: string
  agentUsage: KnowledgeAgentUsage[]
}

export interface ManagementValueEvent {
  id: number
  eventKey: string
  agentId?: number
  endpointKey?: string
  taskId?: number
  knowledgeInteractionId?: number
  valueType: string
  description?: string
  validatedBy: string
  benefitMoney: number
  currency: string
  metricName?: string
  metricUnit?: string
  baselineValue?: number
  currentValue?: number
  improvementRate?: number
  sourceType: string
  sourceSystem?: string
  sourceRecordKey?: string
  confidence: number
  measurementWindowStart?: string
  measurementWindowEnd?: string
  agentContributions: ManagementAgentContribution[]
  observedAt: string
  createdAt: string
}

export interface ManagementAgentContribution {
  agentId: number
  contributionWeight: number
  attributionMethod: string
  evidenceReference?: string
}

export interface AgentManagementUsage {
  subjectKey: string
  subjectName: string
  agentId?: number
  endpointKey?: string
  tasks: number
  succeededTasks: number
  modelCalls: number
  knowledgeInteractions: number
  totalTokens: number
  totalCost: number | null
  validatedValue: number | null
  netBenefit: number | null
  roi: number | null
  knowledgeAdoptionRate: number
  knowledgeReuseRate: number
  measuredImpactEvents: number
  averageImprovementRate: number
}

export interface ManagementSummary {
  resolvedTasks?: number
  unresolvedTasks?: number
  unconfirmedTasks?: number
  windowHours: number
  currency: string | null
  availableCurrencies: string[]
  mixedCurrencies: boolean
  totalTasks: number
  succeededTasks: number
  taskSuccessRate: number
  totalModelCalls: number
  totalTokens: number
  totalCost: number | null
  validatedBusinessValue: number | null
  netBenefit: number | null
  roi: number | null
  adoptedKnowledgeInteractions: number
  reusedKnowledgeInteractions: number
  knowledgeAdoptionRate: number
  knowledgeReuseRate: number
  measuredImpactEvents: number
  averageImprovementRate: number
  efficiencyImpactEvents: number
  businessImpactEvents: number
  businessSystemValueEvents: number
  multiAgentValueEvents: number
  averageConfidence: number
  hasValidatedValue: boolean
  lastValueEventAt?: string
  agentUsage: AgentManagementUsage[]
}

export type ManagementConnectorStatus = 'NEVER_SYNCED' | 'RUNNING' | 'HEALTHY' | 'DEGRADED' | 'ERROR' | 'DISABLED'
export type ManagementConnectorAuthType = 'NONE' | 'BEARER_ENV' | 'HEADER_ENV'

export interface ManagementValueConnector {
  id: number
  connectorKey: string
  name: string
  systemType: string
  sourceSystem: string
  endpointUrl: string
  enabled: boolean
  syncIntervalMinutes: number
  authType: ManagementConnectorAuthType
  credentialEnvKey?: string
  authHeader?: string
  authPrefix?: string
  recordsPath?: string
  recordKeyPath: string
  observedAtPath?: string
  valueTypePath?: string
  fixedValueType: string
  descriptionPath?: string
  benefitMoneyPath: string
  currencyPath?: string
  fixedCurrency: string
  metricNamePath?: string
  fixedMetricName?: string
  metricUnitPath?: string
  fixedMetricUnit?: string
  baselineValuePath?: string
  currentValuePath?: string
  improvementDirection: 'HIGHER_IS_BETTER' | 'LOWER_IS_BETTER'
  agentIdPath?: string
  defaultAgentId?: number
  endpointKeyPath?: string
  taskIdPath?: string
  defaultEndpointKey?: string
  validatedBy: string
  confidence: number
  measurementWindowStartPath?: string
  measurementWindowEndPath?: string
  incrementalParameter?: string
  maxRecords: number
  status: ManagementConnectorStatus
  lastSyncStartedAt?: string
  lastSyncCompletedAt?: string
  lastSuccessAt?: string
  lastImportedCount: number
  lastSkippedCount: number
  lastError?: string
  createdAt: string
  updatedAt: string
}

export type ManagementConnectorRequest = Omit<ManagementValueConnector,
  'id' | 'status' | 'lastSyncStartedAt' | 'lastSyncCompletedAt' | 'lastSuccessAt' |
  'lastImportedCount' | 'lastSkippedCount' | 'lastError' | 'createdAt' | 'updatedAt'>

export interface ManagementConnectorSyncResult {
  connectorId: number
  connectorKey: string
  success: boolean
  fetchedRecords: number
  importedRecords: number
  skippedRecords: number
  failedRecords: number
  message: string
  startedAt: string
  completedAt: string
}

export interface GovernanceDataEvent {
  id: number
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
  observedAt: string
  createdAt: string
}

export interface GovernanceSubjectUsage {
  subjectKey: string
  subjectName: string
  agentId?: number
  endpointKey?: string
  events: number
  modelInputs: number
  internalEvents: number
  companySecretEvents: number
  blockedEvents: number
  redactedEvents: number
  tokenCount: number
}

export interface GovernanceDataSummary {
  windowHours: number
  totalEvents: number
  modelInputEvents: number
  internalInfoEvents: number
  companySecretEvents: number
  allowedEvents: number
  warnedEvents: number
  redactedEvents: number
  blockedEvents: number
  approvalRequiredEvents: number
  internalExposureRate: number
  secretExposureRate: number
  blockRate: number
  totalTokens: number
  totalPayloadBytes: number
  lastEventAt?: string
  subjectUsage: GovernanceSubjectUsage[]
}

export interface GovernanceMatchedPolicy {
  id: number
  revision?: number | null
  name: string
  policyType: PolicyType
  enforcementMode: EnforcementMode
  reasonCode: string
}

export interface GovernancePreflightDecision {
  id: number
  decisionKey: string
  dataEventId: number
  action: GovernanceDataAction
  gatewayDirective: GovernanceGatewayDirective
  requestAllowed: boolean
  matchedPolicyCount: number
  matchedPolicies: GovernanceMatchedPolicy[]
  reasonCodes: string[]
  classifier: string
  provider?: string
  modelName?: string
  approvalStatus: GovernanceApprovalStatus
  approvalContextState?: 'CURRENT' | 'CHANGED' | 'UNRECORDED' | 'NOT_APPLICABLE'
  approvedBy?: string
  approvalReason?: string
  approvedAt?: string
  requestFingerprint?: string
  decidedAt: string
}

export interface GovernanceEnforcementStatus {
  enforcementEnabled: boolean
  failClosedOnSecret: boolean
  enabledPolicies: number
  preflightDecisions: number
  blockedDecisions: number
  redactionDecisions: number
  approvalDecisions: number
  pendingApprovals: number
  lastDecisionAt?: string
}

export interface ModelGatewayStatus {
  enabled: boolean
  configured: boolean
  authenticationConfigured: boolean
  provider: string
  classifier: string
  readTimeoutMs: number
  maxResponseBytes: number
  pricingConfigured: boolean
  checkedAt: string
}

export interface AgentDiscoverySummary {
  autonomousDiscoveryEnabled: boolean
  totalDiscovered: number
  agentCount: number
  mcpConfigurationCount: number
  skillCount: number
  pluginCount: number
  highRiskCount: number
  reviewingCount: number
  managedCount: number
  lastRun?: AgentDiscoveryRun
}

export interface AgentTeam {
  revision: number
  id: number
  code: string
  name: string
  objective?: string
  coordinationMode: string
  workspacePolicy: string
  status: TeamStatus
  createdAt: string
  updatedAt: string
}

export interface AgentTeamMember {
  id: number
  teamId: number
  agentId: number
  role: string
  responsibility: string
  createdAt: string
  updatedAt: string
}

export interface AgentPlan {
  revision: number
  id: number
  teamId: number
  goal: string
  contextSummary?: string
  status: PlanStatus
  executionWorkflowId?: number
  executionRunKey?: string
  createdAt: string
  updatedAt: string
}

export interface AgentPlanStep {
  outputContractJson?: string | null
  executionBackend?: string
  id: number
  planId: number
  stepOrder: number
  title: string
  requiredCapability: string
  assignedAgentId?: number
  status: StepStatus
  createdAt: string
  updatedAt: string
}

export interface AgentPlanDetail {
  plan: AgentPlan
  steps: AgentPlanStep[]
}

export interface WorkflowDefinition {
  id: number
  code: string
  name: string
  description?: string
  status: WorkflowStatus
  createdAt: string
  updatedAt: string
}

export interface WorkflowNode {
  version: number
  id: number
  workflowId: number
  nodeKey: string
  label: string
  nodeType: string
  agentId?: number
  capability?: string
  positionX: number
  positionY: number
  configJson?: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowEdge {
  id: number
  workflowId: number
  sourceNodeKey: string
  targetNodeKey: string
  conditionExpression?: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowDetail {
  workflow: WorkflowDefinition
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
}

export interface WorkflowRunStep {
  nodeKey: string
  label: string
  nodeType: string
  agentId?: number
  status: string
  safetyDecision: SafetyDecision
  reason: string
}

export interface WorkflowRun {
  runKey: string
  status: string
  objective: string
  steps: WorkflowRunStep[]
  executionMode: 'SAFETY_PREVIEW'
  agentsInvoked: boolean
}

export interface WorkflowToolRecovery {
  contract: string; enrolled: boolean; mode: 'QUERY_ONLY'; responsibility: string
  entries: { sequence: number; nodeKey: string; taskKey: string; rootId: string; operationId: string;
    holderKey: string; actorUid: string; contractVersion: string; budgetKey: string; state: string; reason: string;
    queryEpoch: number; leaseUntil?: string; observedAt?: string;
    operation?: { effectId?: string; counterValue?: number; state: string; effectSource: string } }[]
}

export interface WorkflowExecution {
  runKey: string
  workflowId: number
  status: string
  executionMode: 'EAP_HTTP_TASK_V1' | 'EAP_MIXED_TASK_V1'
  objective: string
  requester: string
  steps: { sequence: number; nodeKey: string; label: string; agentId?: number; taskKey?: string;
    runtimeInstanceKey?: string; status: string; safetyDecision: string; reason: string; httpStatus?: number; reclaimStatus?: string | null;
    resultValidation?: { contract: string; source: string; contractSha256: string; status: string; code: string } | null }[]
  reason?: string
  createdAt: string
  startedAt?: string
  completedAt?: string
}

export type EvaluatorType = 'RULE' | 'CODE' | 'LLM_JUDGE' | 'LOCAL_SLM'
export type EvaluationLevel = 'SPAN' | 'TRACE' | 'SESSION' | 'AGENT' | 'WORKFLOW'
export type EvaluationStatus = 'COMPLETED' | 'FAILED'
export type EvaluationJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'PARTIAL' | 'FAILED'

export interface EvaluatorDefinition {
  id: number
  evaluatorKey: string
  name: string
  description?: string
  evaluatorType: EvaluatorType
  level: EvaluationLevel
  implementationKey: string
  version: string
  threshold: number
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface EvaluationDataset {
  id: number
  datasetKey: string
  name: string
  description?: string
  sourceType: string
  version: string
  itemCount: number
  createdAt: string
  updatedAt: string
}

export type EvaluationGroundTruthLabel = 'PASS' | 'FAIL' | 'ABSTAIN'

export interface EvaluationDatasetItem {
  id: number
  itemKey: string
  evaluatorKey: string
  evaluatorVersion?: string
  agentId?: number
  traceId?: string
  spanId?: string
  sourceReference?: {
    state: 'MANUAL_UNVERIFIED' | 'OBSERVATION_MATCHED'
    observationId?: number
    endpointKey?: string
    observedAt?: string
    checkedAt?: string
  }
  input: Record<string, unknown>
  expected?: unknown
  actual: Record<string, unknown>
  groundTruthLabel?: EvaluationGroundTruthLabel
  groundTruthScore?: number
  annotationRationale?: string
  annotatedBy?: string
  annotatedAt?: string
  createdAt: string
  updatedAt: string
}

export interface EvaluationResult {
  id: number
  resultKey: string
  evaluatorKey: string
  evaluatorVersion: string
  datasetKey?: string
  agentId?: number
  traceId?: string
  spanId?: string
  level: EvaluationLevel
  status: EvaluationStatus
  score: number
  passed: boolean
  confidence: number
  rationale?: string
  evidence?: Record<string, unknown>
  evaluatedAt: string
  createdAt: string
}

export interface EvaluationJob {
  phase?: string
  processed?: number
  total?: number
  id: number
  jobKey: string
  requestedLimit: number
  status: EvaluationJobStatus
  scanned: number
  evaluated: number
  skipped: number
  failed: number
  errorMessage?: string
  createdAt: string
  startedAt?: string
  completedAt?: string
  updatedAt: string
}

export interface EvaluationSummary {
  windowHours: number
  total: number
  passed: number
  failed: number
  passRate: number
  averageScore: number
  averageConfidence: number
  evaluators: EvaluationEvaluatorSummary[]
}

export interface EvaluationDatasetCalibration {
  runId?: string | null
  datasetKey: string
  datasetVersion: string
  requestedLimit: number
  totalItems: number
  evaluatedItems: number
  skippedItems: number
  failedItems: number
  unannotatedItems: number
  abstainedItems: number
  truePositive: number
  falsePositive: number
  trueNegative: number
  falseNegative: number
  precision: number
  recall: number
  f1: number
  accuracy: number
  calculatedAt: string
  evaluators: EvaluationEvaluatorCalibration[]
}

export interface EvaluationCalibrationRunSummary {
  runId: string
  datasetKey: string
  datasetVersion: string
  requestedLimit: number
  totalItems: number
  evaluatedItems: number
  failedItems: number
  calculatedAt: string
}

export interface EvaluationCalibrationRunDetail {
  calibration: EvaluationDatasetCalibration
  evaluatorDefinitions: Array<{
    evaluatorKey: string
    evaluatorVersion: string
    evaluatorType: string
    implementationKey: string
    level: string
    threshold: number
    enabled: boolean
  }>
  samples: Array<{
    itemKey: string
    evaluatorKey: string
    evaluatorVersion: string
    groundTruthLabel?: EvaluationGroundTruthLabel
    groundTruthScore?: number
    annotatedAt?: string
    outcome: 'EVALUATED' | 'SKIPPED' | 'FAILED'
    reason?: string
    resultKey?: string
    score?: number
    passed?: boolean | null
  }>
}

export interface EvaluationEvaluatorCalibration {
  evaluatorKey: string
  evaluatorVersion: string
  totalItems: number
  evaluatedItems: number
  skippedItems: number
  failedItems: number
  unannotatedItems: number
  abstainedItems: number
  truePositive: number
  falsePositive: number
  trueNegative: number
  falseNegative: number
  precision: number
  recall: number
  f1: number
  accuracy: number
}

export interface EvaluationEvaluatorSummary {
  evaluatorKey: string
  name: string
  total: number
  passed: number
  failed: number
  passRate: number
  averageScore: number
}
