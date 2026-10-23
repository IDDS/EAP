import { getData } from './http'
export interface ObservationLog {
  operationId: number; entryKind: string; traceId: string | null; eventKind: string; reasonCode: string
  ownerEpoch: number | null; createdMs: number; text: string
}
export interface ObservationLogSearch {
  sourceContract: string; windowMinutes: number; windowStartMs: number; observedMs: number; limit: number
  candidateLimit: number; candidatesScanned: number; scopeTruncated: boolean; matchesTruncated: boolean; logs: ObservationLog[]
}
export const searchObservationLogs = (query: string, minutes: number) => getData<ObservationLogSearch>(
  `/observability/logs?${new URLSearchParams({ query, minutes: String(minutes), limit: '50' })}`, { silentError: true })
