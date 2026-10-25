import { getData, http } from './http'
import type { ApiResponse, GovernancePolicy } from '@/types/domain'
export type PolicyValues = Omit<GovernancePolicy, 'id' | 'revision' | 'createdAt' | 'updatedAt'>
export interface PolicyVersion {
  revision: number; changeKind: string; restoredFromRevision: number | null; snapshotDigest: string
  principalDigest: string; recordedAt: string; values: PolicyValues
}
export interface PolicyHistory {
  policyId: number; currentRevision: number; legacyHistoryUnavailable: boolean
  truncated: boolean; versions: PolicyVersion[]
}
export const policyApi = {
  list: () => getData<GovernancePolicy[]>('/policies', { silentError: true }),
  versions: (id: number) => getData<PolicyHistory>(`/policies/${id}/versions`, { silentError: true }),
  create: async (values: PolicyValues) => (await http.post<ApiResponse<GovernancePolicy>>('/policies', values, { silentError: true })).data.data,
  update: async (id: number, values: PolicyValues & { expectedRevision: number }) =>
    (await http.put<ApiResponse<GovernancePolicy>>(`/policies/${id}`, values, { silentError: true })).data.data,
  rollback: async (id: number, expectedRevision: number, sourceRevision: number, sourceDigest: string) =>
    (await http.post<ApiResponse<GovernancePolicy>>(`/policies/${id}/rollback`, { expectedRevision, sourceRevision, sourceDigest }, { silentError: true })).data.data,
}
