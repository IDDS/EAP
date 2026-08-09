export type AgentLifecycleStatus = 'DRAFT' | 'REVIEWING' | 'ACTIVE' | 'PAUSED' | 'RETIRED'
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
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
export type DiscoverySourceType = 'PROCESS' | 'MANIFEST' | 'CONFIGURATION' | 'SKILL' | 'PLUGIN' | 'EXTERNAL' | 'A2A' | 'MCP_REGISTRY'
export type DiscoveryRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED'
export type DiscoveryTriggerType = 'SCHEDULED' | 'MANUAL' | 'ENDPOINT'
export type ModelInvocationStatus = 'STARTED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type ModelObservationSource = 'GATEWAY' | 'TELEMETRY_API' | 'OTLP'
export type KnowledgeInteractionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type KnowledgeStepType = 'INPUT_ANALYSIS' | 'RETRIEVAL' | 'TOOL' | 'REASONING' | 'TRANSFORM' | 'OUTPUT_ANALYSIS'
export type KnowledgeStepStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'
export type GovernanceDataDirection = 'INPUT_TO_MODEL' | 'OUTPUT_FROM_MODEL' | 'TOOL_INPUT' | 'TOOL_OUTPUT'
export type GovernanceDataSensitivity = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'SECRET' | 'RESTRICTED'
export type GovernanceDataAction = 'ALLOW' | 'WARN' | 'REDACT' | 'BLOCK' | 'APPROVAL_REQUIRED'
export type GovernanceGatewayDirective = 'PROCEED' | 'PROCEED_WITH_WARNING' | 'REDACT_AND_RECHECK' | 'REQUEST_APPROVAL' | 'BLOCK'
export type GovernanceApprovalStatus = 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'REJECTED'
export type TeamStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED'
export type PlanStatus = 'DRAFT' | 'READY' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type StepStatus = 'PENDING' | 'ASSIGNED' | 'RUNNING' | 'DONE' | 'BLOCKED'
export type WorkflowStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'

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
  resultMessage?: string
  createdAt: string
  updatedAt: string
  dispatchedAt?: string
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
  status: AgentTaskStatus
  startedAt?: string
  finishedAt?: string
  latencyMs?: number
  tokenCost?: number
  moneyCost?: number
  resultSummary?: string
  createdAt: string
  updatedAt: string
}

export interface LearningFeedback {
  id: number
  agentId: number
  feedbackType: string
  rating: number
  summary: string
  improvementAction?: string
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
  observedAt: string
  createdAt: string
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
  totalCost: number
  validatedValue: number
  netBenefit: number
  roi: number
  knowledgeAdoptionRate: number
  knowledgeReuseRate: number
}

export interface ManagementSummary {
  windowHours: number
  totalTasks: number
  succeededTasks: number
  taskSuccessRate: number
  totalModelCalls: number
  totalTokens: number
  totalCost: number
  validatedBusinessValue: number
  netBenefit: number
  roi: number
  adoptedKnowledgeInteractions: number
  reusedKnowledgeInteractions: number
  knowledgeAdoptionRate: number
  knowledgeReuseRate: number
  hasValidatedValue: boolean
  lastValueEventAt?: string
  agentUsage: AgentManagementUsage[]
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
  id: number
  teamId: number
  goal: string
  contextSummary?: string
  status: PlanStatus
  createdAt: string
  updatedAt: string
}

export interface AgentPlanStep {
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
}
