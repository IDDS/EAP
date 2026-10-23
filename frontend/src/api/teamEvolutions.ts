import { getData } from '@/api/http'

export type TeamRow = { id: number; createdAt: string; candidates: number; evaluations: number; closed: boolean; recommendation: string | null }
export type TeamPage = { items: TeamRow[]; nextBeforeId: number | null; pageLimit: number; scanLimited: boolean }
export type TeamSlot = { sampleId: string; role: string; ordinal: number; operationKey: string; traceId: string; state: string }
export type TeamEvaluation = { id: string; candidateId: string; state: string; split: string; arm: string; slots: TeamSlot[] }
export type TeamCandidate = { id: string; parentId: string | null; generation: number; changedRole: string | null }
export type TeamScore = { evaluationId: string; candidateId: string; split: string; arm: string; denominator: number; passed: number; qualityBps: number; inputTokens: number; outputTokens: number; totalTokens: number; totalLatencyMicros: number; proofDigest: string }
export type TeamCosts = { modelCalls: number; settledCalls: number; unknownCalls: number; pendingCalls: number; violationCalls: number; knownInputTokens: number; knownOutputTokens: number; unknownUsage: boolean; reservedOutputTokens: number }
export type TeamFrontier = { sealed: boolean; baselineId: string; frontier: string[]; decisions: Record<string, string>; scores: TeamScore[]; selectedCandidateId: string | null; recommendation: string; costs: TeamCosts }
export type TeamDetail = {
  summary: { id: number; protocolDigest: string; datasetDigest: string; validationDigest: string; baselineId: string; attempts: number; maxAttempts: number; maxCandidates: number; maxGeneration: number; minimumQualityBps: number; candidates: TeamCandidate[]; evaluations: TeamEvaluation[] }
  createdAt: string; roles: { role: string; agentId: number }[]; sampleCounts: Record<string, number>
  budget: { proposalMode?: string; maxProposalCalls?: number; maxProposalTokens?: number; maxCalls: number; maxOutputTokens: number; requiredCalls: number; approvalExpiresAt: string; budgetAllocated: boolean; sharedCapacityReserved: boolean } | null
  frontier: TeamFrontier
  holdout: { baseline: TeamEvaluation; selected: TeamEvaluation; holdoutDigest: string } | null
  closure: { recommendation: string; completeEvidence: boolean; stableBenefitVerified: boolean; adoptionAvailable: boolean; baseline: TeamScore | null; selected: TeamScore | null; costs: TeamCosts; proposalModelCalls: number; proposalAttempts: number; registeredCandidates: number } | null
  currentCosts: TeamCosts
  evaluations: { registration: TeamEvaluation; execution: { state: string; reasonCode: string | null; dispatchedSlots: number; confirmedSlots: number } | null; score: TeamScore | null }[]
}
export type TeamContent = { samples: { id: string; groupId: string; split: string; input: string; expected: string }[]; candidates: { id: string; configuration: { rolePrompts: Record<string, string> } }[] }
export const TeamEvolutionApi = {
  list: (beforeId?: number) => getData<TeamPage>('/team-evolutions/console' + (beforeId === undefined ? '' : `?beforeId=${beforeId}`), { silentError: true }),
  get: (id: number) => getData<TeamDetail>(`/team-evolutions/${id}/console`, { silentError: true }),
  content: (id: number) => getData<TeamContent>(`/team-evolutions/${id}/content`, { silentError: true }),
}
