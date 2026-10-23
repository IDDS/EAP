import { getData, http } from './http'
import type { ApiResponse } from '@/types/domain'
import type { CoverageOperation } from './observationCoverage'

export interface ObservationAlert {
  id: number; kind: string; factRevision: number; factDigest: string; coverage: string
  executionStatus: string; lifecycleState: string; createdMs: number; changedMs: number
}
export interface ObservationAlertAudit {
  alertId: number; event: string; principalDigest: string; createdMs: number
}
export interface ObservationAlertView {
  operation: CoverageOperation; observedFactDigest: string; reconciledRevision: number | null
  reconciledFactDigest: string | null; reconciliationCurrent: boolean
  alerts: ObservationAlert[]; alertsTruncated: boolean; audit: ObservationAlertAudit[]; auditTruncated: boolean
}
export const getObservationAlerts = (id: number) => getData<ObservationAlertView>(`/observability/operations/${id}/alerts`)
export interface ObservationAlertAcknowledgement { factRevision: number; factDigest: string }
// The panel handles uncertain writes without suggesting an automatic resubmission.
async function writeAlerts(path: string, payload?: ObservationAlertAcknowledgement): Promise<ObservationAlertView> {
  return (await http.post<ApiResponse<ObservationAlertView>>(path, payload, { silentError: true })).data.data
}
export const reconcileObservationAlerts = (id: number) => writeAlerts(`/observability/operations/${id}/alerts/reconcile`)
export const acknowledgeObservationAlert = (operation: number, alert: number, fact: ObservationAlertAcknowledgement) =>
  writeAlerts(`/observability/operations/${operation}/alerts/${alert}/acknowledgement`, fact)
