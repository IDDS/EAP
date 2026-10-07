import { getData } from './http'
export interface SchedulingPhase { nodeSequence: number; kind: string; state: string; startedMs: number; durationMs: number | null }
export interface ScheduledRun { runKey: string; workflowId: number; status: string; groupKey: string; selectionReason: string | null; queueWaitMs: number | null; phases: SchedulingPhase[] }
export interface SchedulingSummary {
  policy: string; agingSeconds: number; observedMs: number; queued: number; legacyQueued: number; oldestWaitMs: number | null
  waitSamples: number; waitP50Ms: number | null; waitP95Ms: number | null
  groups: { groupKey: string; queued: number; oldestWaitMs: number | null }[]; runs: ScheduledRun[]
}
export const getScheduling = () => getData<SchedulingSummary>('/workflows/scheduling')
