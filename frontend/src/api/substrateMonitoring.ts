import { getData } from './http'
export type Metric = { status: 'OK' | 'EMPTY' | 'ERROR'; series: { labels: Record<string,string>; points: { at: number; value: number | null }[] }[] }
export type MonitoredDeployment = { agentId: number; agentName: string; version: number; template: string; status: string }
export type SubstrateMonitoring = { status: 'CONNECTED' | 'PARTIAL' | 'UNAVAILABLE' | 'DISABLED'; scope: string; queriedAt: string; windowMinutes: number; metrics: Record<string,Metric>; deployments: MonitoredDeployment[]; deploymentsTruncated: boolean; backend: string; axTasks: {taskKey: string; agentId: number; status: string; sandboxPhase: string; workflowRunKey?: string}[] }
export const fetchSubstrateMonitoring = (minutes: number, backend = 'RUNTIME') => getData<SubstrateMonitoring>(`/substrate/monitoring?minutes=${minutes}&backend=${encodeURIComponent(backend)}`)
// Missing or non-finite observations are not healthy zeros. Never carry an old sample forward.
export function latest(metric?: Metric, label?: string, match?: string): number | null {
  if (!metric || metric.status !== 'OK') return null
  const series = label ? metric.series.find(s => s.labels[label] === match) : metric.series[0]
  const value = series?.points.at(-1)?.value
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}
export function metricText(value: number | null, digits = 1): string {
  return value === null ? '—' : value.toLocaleString('zh-CN', { maximumFractionDigits: digits })
}
