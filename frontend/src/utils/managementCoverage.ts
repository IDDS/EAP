import type { ManagementSummary } from '@/types/domain'

/** An older server cannot distinguish a missing value amount from a reported zero. */
export function protectUnclassifiedValueReturns(summary: ManagementSummary): ManagementSummary {
  if (summary.valueCoverage != null) return summary
  return {
    ...summary,
    netBenefit: null,
    roi: null,
    agentUsage: summary.agentUsage.map(row => ({ ...row, netBenefit: null, roi: null })),
  }
}
