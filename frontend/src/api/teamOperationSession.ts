import type { TeamIntent, TeamReceipt } from './teamEvolutionOperations'

export type OperationState = { phase: 'IDLE' | 'REVIEW' | 'SENDING' | 'UNKNOWN' | 'FOUND' | 'REJECTED'; intent: TeamIntent | null; message: string; result: unknown; reading: boolean; rootToRead: number | null }
export const emptyOperationState = (): OperationState => ({ phase: 'IDLE', intent: null, message: '', result: null, reading: false, rootToRead: null })

/** One explicit submission; a lost response can only be reconciled by GET. No storage or automatic retry. */
export function createOperationSession(state: OperationState, dependencies: {
  user: () => unknown; allowed: () => boolean; fresh: () => boolean;
  send: (intent: TeamIntent) => Promise<unknown>; receipt: (intent: TeamIntent) => Promise<TeamReceipt>;
  invalidate: () => void;
}) {
  let generation = 0, owner: unknown = null, alive = true
  const current = (run: number, user: unknown) => alive && generation === run && user === dependencies.user() && dependencies.allowed()
  function reset() { generation++; owner = null; Object.assign(state, emptyOperationState()) }
  function stripBody() { if (state.intent) state.intent = { ...state.intent, body: {}, summary: [] } }
  function navigate() {
    if (state.phase === 'SENDING' || state.phase === 'UNKNOWN') {
      generation++; stripBody(); state.phase = 'UNKNOWN'; state.result = null; state.reading = false
      state.message = '原请求尚未确认，请按下方原编号只读核对；不会重新提交。'
    } else reset()
  }
  function prepare(intent: TeamIntent) {
    if (!alive || !dependencies.allowed() || !dependencies.fresh() || ['SENDING', 'UNKNOWN'].includes(state.phase)) return false
    generation++; owner = dependencies.user(); Object.assign(state, emptyOperationState())
    state.intent = JSON.parse(JSON.stringify(intent)); state.phase = 'REVIEW'; return true
  }
  function cancel() { if (state.phase === 'REVIEW') reset() }
  function accept(result: unknown, rootId: number | null) {
    state.phase = 'FOUND'; state.result = result; state.rootToRead = rootId
    const value = result as { disposition?: string; state?: string; recommendation?: string } | null
    state.message = value?.disposition === 'REJECTED' ? '服务器已记录本次提案被拒绝，请读取原记录后调整。'
      : value?.state === 'UNKNOWN' ? '原执行结果未知；保留原记录，不提供换键重跑。'
      : ['RUNNING', 'PREPARING', 'DISPATCHED'].includes(value?.state || '') ? '原执行仍在进行，可继续只读核对；不会再次派发。'
      : '已取得原服务器记录。请读取最新原记录后再进行下一步。'
    stripBody(); dependencies.invalidate()
  }
  async function confirm() {
    if (state.phase !== 'REVIEW' || !state.intent || owner !== dependencies.user() || !dependencies.allowed() || !dependencies.fresh()) return
    const intent = JSON.parse(JSON.stringify(state.intent)) as TeamIntent, run = generation, user = owner
    state.phase = 'SENDING'; state.message = '请求已提交，请等待原结果；切换页面不会撤销服务器操作。'
    try {
      const result = await dependencies.send(intent)
      if (!current(run, user)) return
      const id = intent.action === 'CREATE' ? (result as { id?: number })?.id : intent.rootId
      if (intent.action === 'CREATE' && (!Number.isSafeInteger(id) || id! < 1)) throw new Error('Missing original root')
      accept(result, id ?? null)
    } catch (error) {
      if (!current(run, user)) return
      const status = (error as { response?: { status?: number; data?: { message?: string } } })?.response?.status
      const code = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
      // Execution spans committed transactions; even a later 4xx cannot prove that no dispatch happened.
      const rejected = intent.action === 'CHECK' || (!['EXECUTE', 'PROPOSE'].includes(intent.action) && [400, 403, 409, 422].includes(status ?? 0))
      state.phase = rejected ? 'REJECTED' : 'UNKNOWN'; state.result = null; stripBody(); dependencies.invalidate()
      state.message = rejected ? `服务器拒绝本次请求，请读取原记录后检查参数${code && /^TEAM_[A-Z_]+$/.test(code) ? `（${code}）` : ''}。`
        : '未取得确定结果，不能视为失败或零消费。只能按原请求编号核对，不会自动重发。'
    }
  }
  async function reconcile() {
    if (!state.intent || !['UNKNOWN', 'FOUND', 'REJECTED'].includes(state.phase) || state.intent.action === 'CHECK' || state.reading || owner !== dependencies.user() || !dependencies.allowed()) return
    const intent = JSON.parse(JSON.stringify(state.intent)) as TeamIntent, run = generation, user = owner
    state.reading = true; state.result = null
    try {
      const receipt = await dependencies.receipt(intent)
      if (!current(run, user)) return
      if (receipt.action !== intent.action || receipt.requestKey !== intent.requestKey.toLowerCase()
          || (intent.action !== 'CREATE' && receipt.rootId !== intent.rootId) || receipt.evaluationId !== (intent.evaluationId ?? null)) throw new Error('Mismatched original receipt')
      if (receipt.state === 'FOUND') {
        if (!Number.isSafeInteger(receipt.rootId) || receipt.rootId! < 1 || receipt.result == null) throw new Error('Invalid receipt')
        accept(receipt.result, receipt.rootId)
      } else if (receipt.state === 'NOT_FOUND') {
        state.phase = 'UNKNOWN'
        state.message = '暂未查到原请求记录；这不证明请求失败、已取消或未消费。请保留原编号，稍后只读核对。'
      } else throw new Error('Invalid receipt state')
    } catch {
      if (current(run, user)) { state.phase = 'UNKNOWN'; state.message = '原请求核对失败；未知状态保持，未重发任何操作。' }
    } finally { if (current(run, user)) state.reading = false }
  }
  function recover(intent: TeamIntent) {
    if (!alive || !dependencies.allowed() || ['SENDING', 'UNKNOWN', 'REVIEW'].includes(state.phase) || intent.action === 'CHECK') return
    reset(); owner = dependencies.user(); state.intent = { ...intent, body: {}, summary: [] }; state.phase = 'UNKNOWN'
    return reconcile()
  }
  return { prepare, confirm, cancel, reconcile, recover, navigate, reset, dispose: () => { alive = false; reset() } }
}
