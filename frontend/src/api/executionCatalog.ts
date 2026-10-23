import { getData } from '@/api/http'

export interface ExecutionEntry {
  kind: 'WORKFLOW' | 'PLAN' | 'AX' | 'CHAT'
  runKey: string
  workflowId?: number
  agentId?: number
  instanceName?: string
  title: string
  status: string
  requester: string
  createdAt: string
}
export interface ExecutionPage {
  contract: 'EXECUTION_CATALOG_V1'
  items: ExecutionEntry[]
  total: number
  page: number
  size: number
  observedAt: string
  ownChatsIncluded: boolean
  windowLimit?: number
}
export interface ExecutionFilters { kind: string; status: string; query: string; mine: boolean; from?: string; until?: string; page: number; size: number }
export function listExecutions(filters: ExecutionFilters) {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== '') params.set(key, String(value)) })
  return getData<ExecutionPage>(`/executions?${params}`)
}
export const executionKindLabel = (kind: string) => (({ WORKFLOW: '工作流', PLAN: '团队计划', AX: '异步任务', CHAT: '试运行对话' } as Record<string, string>)[kind] || kind)
export function executionLocation(entry: ExecutionEntry) {
  if (['WORKFLOW', 'PLAN'].includes(entry.kind) && entry.workflowId)
    return { path: '/task-workspace', query: { workflowId: String(entry.workflowId), runKey: entry.runKey } }
  if (entry.kind === 'AX') return { path: '/ax-tasks', query: { taskKey: entry.runKey } }
  return null
}
