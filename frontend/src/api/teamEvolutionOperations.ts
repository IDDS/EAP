import { getData, http } from '@/api/http'

export type TeamAction = 'CREATE' | 'CANDIDATE' | 'PROPOSE' | 'ENROLL' | 'BUDGET' | 'CHECK' | 'EXECUTE' | 'SCORE' | 'FRONTIER' | 'HOLDOUT' | 'CLOSE'
export type TeamAvailability = { modelProposalAvailable?: boolean; contractsEnabled: boolean; budgetAdmissionAvailable: boolean; slotExecutionAvailable: boolean; platformScoringAvailable: boolean; independentHoldoutAvailable: boolean }
export type TeamActionContext = { originalSubmitter: boolean; maxTokens: number; availability: TeamAvailability }
export type TeamIntent = { action: TeamAction; requestKey: string; rootId?: number; evaluationId?: string; body: Record<string, unknown>; summary: string[] }
export type TeamReceipt = { action: TeamAction; requestKey: string; rootId: number | null; evaluationId: string | null; state: 'FOUND' | 'NOT_FOUND'; result: unknown }

export type TeamProposal = { contractId: number; requestKey: string; parentId: string; role: string; traceId: string; state: string; reasonCode: string | null; draftAvailable: boolean; registered: boolean; reservationId: number | null; usageState: string; inputTokens: number | null; outputTokens: number | null }
export type TeamProposalDraft = { parentId: string; configuration: { rolePrompts: Record<string, string> } }
function proposalPath(id: number, key?: string) {
  if (!Number.isSafeInteger(id) || id < 1 || key !== undefined && !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/.test(key)) throw new Error('原提案编号无效')
  return `/team-evolutions/${id}/proposals${key === undefined ? '' : `/${encodeURIComponent(key)}`}`
}
function path(intent: TeamIntent) {
  if (intent.action === 'CREATE') return '/team-evolutions'
  if (!Number.isSafeInteger(intent.rootId) || intent.rootId! < 1) throw new Error('请选择原团队任务')
  const root = `/team-evolutions/${intent.rootId}`
  if (intent.action === 'EXECUTE' || intent.action === 'SCORE') {
    if (!intent.evaluationId || !/^[a-fA-F0-9-]{36}$/.test(intent.evaluationId)) throw new Error('请选择原评价批次')
    return `${root}/evaluations/${encodeURIComponent(intent.evaluationId)}/${intent.action === 'EXECUTE' ? 'execution' : 'score'}`
  }
  const suffix = { PROPOSE: 'proposals', CANDIDATE: 'candidates', ENROLL: 'evaluations', BUDGET: 'budget', CHECK: 'budget/check', FRONTIER: 'frontier', HOLDOUT: 'holdout', CLOSE: 'closure' }[intent.action]
  if (!suffix) throw new Error('未知团队操作')
  return `${root}/${suffix}`
}
export const TeamOperationApi = {
  status: () => getData<TeamAvailability>('/team-evolutions/status', { silentError: true }),
  context: (id: number) => getData<TeamActionContext>(`/team-evolutions/${id}/actions`, { silentError: true }),
  send: async (intent: TeamIntent): Promise<unknown> => (await http.post(path(intent), intent.body, { silentError: true, timeout: ['EXECUTE', 'PROPOSE'].includes(intent.action) ? 930000 : 60000 })).data.data,
  proposals: (id: number) => getData<TeamProposal[]>(proposalPath(id), { silentError: true }),
  proposalContent: (id: number, key: string) => getData<TeamProposalDraft>(`${proposalPath(id, key)}/content`, { silentError: true }),
  receipt: (intent: TeamIntent) => {
    if (intent.action === 'PROPOSE') {
      return getData<TeamProposal>(proposalPath(intent.rootId!, intent.requestKey), { silentError: true }).then(result => {
        if (result.contractId !== intent.rootId || result.requestKey !== intent.requestKey.toLowerCase()) throw new Error('原提案回执不匹配')
        return { action: intent.action, requestKey: result.requestKey, rootId: result.contractId, evaluationId: null, state: 'FOUND' as const, result }
      })
    }
    if (intent.action === 'CHECK') throw new Error('准备检查没有持久写入回执')
    const query = new URLSearchParams({ action: intent.action, requestKey: intent.requestKey })
    if (intent.rootId !== undefined) query.set('rootId', String(intent.rootId))
    if (intent.evaluationId !== undefined) query.set('evaluationId', intent.evaluationId)
    return getData<TeamReceipt>(`/team-evolutions/receipts?${query}`, { silentError: true })
  },
}
