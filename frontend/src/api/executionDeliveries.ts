import { getData } from './http'
import { executionStreamPattern, type ExecutionFilter } from './agentExecutions'

export const deliveryStateLabels: Record<string, string> = {
  EMPTY: '尚无事件', ACKNOWLEDGED: '客户端已确认', PENDING: '待投递', SENDING: '最近记录为发送中',
  RETRY_WAIT: '最近记录为等待重试', BATCH_LIMIT: '本次批数用尽', RETRY_EXHAUSTED: '本次重试用尽',
  BLOCKED_AUTH: '身份验证受阻', BLOCKED_CONFLICT: '事件或身份冲突', BLOCKED_REQUEST: '请求被拒绝',
  BLOCKED_RECEIPT: '回执无法确认', BLOCKED_TRANSPORT: '传输异常', CANCELLED: '投递已停止',
  RETRYABLE_NETWORK: '网络暂不可用', RETRYABLE_SERVER: '接收服务暂不可用', RETRYABLE_THROTTLED: '接收端限流',
}
export const deliveryCredentialLabels: Record<string, string> = {
  AVAILABLE: '凭据记录有效', REVOKED_OR_UNAVAILABLE: '凭据已撤销或不存在', GENERATION_CHANGED: '身份代际已改变',
  EXPIRED: '凭据已过期', UNAVAILABLE: '身份未就绪或实例已离线',
}
export interface ExecutionDelivery {
  id: number; agentId: number; runtimeInstanceKey: string; streamKey: string; framework: string; frameworkVersion: string
  reportSequence: number; total: number; acknowledged: number; pending: number; attempts: number; state: string
  reportedAt: string; stale: boolean; receivedEvents: number; credentialState: string
}
export interface DeliveryPage {
  items: ExecutionDelivery[]; hasMore: boolean; nextAfterId: number; asOf: string; evidenceKind: string
}
const nonnegative = (value: number) => Number.isSafeInteger(value) && value >= 0
export async function getDeliveryPage(filter: ExecutionFilter, signal?: AbortSignal): Promise<DeliveryPage> {
  const params = new URLSearchParams({ afterId: String(filter.afterId), limit: '50' })
  if (filter.agentId !== undefined) params.set('agentId', String(filter.agentId))
  if (filter.streamKey) params.set('streamKey', filter.streamKey)
  const page = await getData<DeliveryPage>(`/observability/agent-executions/deliveries?${params}`, { silentError: true, signal })
  if (!page || !Array.isArray(page.items) || page.items.length > 50 || typeof page.hasMore !== 'boolean'
    || page.evidenceKind !== 'AUTHENTICATED_PRODUCER_SNAPSHOT' || !Number.isFinite(Date.parse(page.asOf))) throw Error('INVALID_DELIVERY_PAGE')
  let previous = filter.afterId
  const streams = new Set<string>()
  for (const row of page.items) {
    if (!Number.isSafeInteger(row.id) || row.id <= previous || !Number.isSafeInteger(row.agentId) || row.agentId <= 0
      || !executionStreamPattern.test(row.streamKey) || streams.has(row.streamKey)
      || (filter.agentId !== undefined && row.agentId !== filter.agentId) || (filter.streamKey && row.streamKey !== filter.streamKey)
      || ![row.total, row.acknowledged, row.pending, row.attempts, row.receivedEvents, row.reportSequence].every(nonnegative)
      || row.reportSequence < 1 || row.total > 4096 || row.receivedEvents > 4096 || row.total !== row.acknowledged + row.pending || row.acknowledged > row.receivedEvents
      || !Object.hasOwn(deliveryStateLabels, row.state) || !Object.hasOwn(deliveryCredentialLabels, row.credentialState)
      || (row.total === 0 ? row.state !== 'EMPTY' : row.pending === 0 ? row.state !== 'ACKNOWLEDGED' : ['EMPTY', 'ACKNOWLEDGED'].includes(row.state))
      || typeof row.stale !== 'boolean' || !Number.isFinite(Date.parse(row.reportedAt))
      || [row.runtimeInstanceKey, row.framework, row.frameworkVersion].some(value => typeof value !== 'string' || !value)) throw Error('INVALID_DELIVERY_ROW')
    previous = row.id; streams.add(row.streamKey)
  }
  if (page.nextAfterId !== previous || (page.hasMore && !page.items.length)) throw Error('INVALID_DELIVERY_CURSOR')
  return page
}

export function deliveryAdvice(row: ExecutionDelivery): string {
  if (row.credentialState !== 'AVAILABLE') return '需先核对原实例身份。此页面不会续期、唤醒实例或重新派发任务。'
  if (row.receivedEvents > row.total) return '服务端已有更多事件；这份客户端报告可能落后，请刷新或等待新的状态报告。'
  if (row.pending > 0 && row.receivedEvents >= row.total) return '服务端接收数已达到报告总数，客户端仍待确认。请核对原回执，不要重跑业务。'
  if (row.pending > 0) return '原日志仍有待确认事件。是否自动补发取决于运行程序版本、原实例身份及重试额度；本报告不代表后台队列的实时状态。'
  return '确认范围仅限本次报告中的事件；不代表后续没有新事件或业务结果已独立验证。'
}
