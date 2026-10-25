import type { ExecutionPage, ExecutionRun } from '@/api/agentExecutions'

export const executionStateLabels: Record<string, string> = {
  SUCCEEDED: '报告成功', FAILED: '报告失败', CANCELLED: '报告取消', INCOMPLETE: '记录未齐', INCONSISTENT: '顺序矛盾',
}
export const executionGapLabels: Record<string, string> = {
  MISSING_SEQUENCE: '事件序号有缺口', MISSING_PARENT: '父执行开始事件缺失', INCOMPLETE_RUN: 'Agent 开始或结束事件缺失',
  INCOMPLETE_TOOL: '工具开始或结束事件缺失', RUN_ORDER_CONFLICT: 'Agent 生命周期顺序矛盾',
  TOOL_ORDER_CONFLICT: '工具生命周期顺序矛盾', MISSING_ARTIFACT: '工具引用的代码摘要尚未登记',
}
export function executionStreams(page: ExecutionPage | null) {
  const streams = new Map<string, { streamKey: string; agentId: number; runtimeInstanceKey: string; framework: string; version: string; pageEvents: number }>()
  for (const row of page?.items ?? []) {
    const stream = streams.get(row.streamKey)
    if (stream) stream.pageEvents++
    else streams.set(row.streamKey, { streamKey: row.streamKey, agentId: row.agentId, runtimeInstanceKey: row.runtimeInstanceKey,
      framework: row.event.framework, version: row.event.frameworkVersion, pageEvents: 1 })
  }
  return [...streams.values()]
}
/** Iterative traversal also bounds malformed cycles and avoids recursive rendering of deep chains. */
export function executionTree(runs: ExecutionRun[]) {
  const byId = new Map(runs.map(run => [run.runId, run]))
  const children = new Map<string, ExecutionRun[]>()
  for (const run of runs) {
    if (!run.parentRunId || !byId.has(run.parentRunId)) continue
    const siblings = children.get(run.parentRunId) ?? []
    siblings.push(run); children.set(run.parentRunId, siblings)
  }
  const result: { run: ExecutionRun; depth: number }[] = [], seen = new Set<string>()
  const visit = (root: ExecutionRun) => {
    const stack = [{ run: root, depth: 0 }]
    while (stack.length) {
      const item = stack.pop()!
      if (seen.has(item.run.runId)) continue
      seen.add(item.run.runId); result.push(item)
      const next = children.get(item.run.runId) ?? []
      for (let i = next.length - 1; i >= 0; i--) stack.push({ run: next[i], depth: item.depth + 1 })
    }
  }
  runs.filter(run => !run.parentRunId || !byId.has(run.parentRunId)).forEach(visit)
  runs.forEach(visit)
  return result
}
