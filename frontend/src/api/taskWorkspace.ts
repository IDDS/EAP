export function executionStatusLabel(status: string) {
  const additional: Record<string, string> = { ACCEPTED: '已受理', DISPATCHED: '已派发', PROVISIONING: '准备运行环境', NOT_DISPATCHED: '未派发' }
  if (additional[status]) return additional[status]
  return ({ QUEUED: '等待执行', RUNNING: '正在执行', DISPATCHING: '正在派发', SUCCEEDED: '已确认完成', FAILED: '执行失败', UNKNOWN: '结果未知 · 需核对', EXECUTION_UNKNOWN: '结果未知 · 需核对', WAITING_APPROVAL: '等待审批', BLOCKED: '已阻断', CANCELLED: '后续未执行', CHECK_PASSED: '检查通过' } as Record<string, string>)[status] || status
}
export function executionStatusTone(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (['SUCCEEDED', 'CHECK_PASSED'].includes(status)) return 'success'
  if (['FAILED', 'BLOCKED'].includes(status)) return 'danger'
  if (['UNKNOWN', 'EXECUTION_UNKNOWN', 'WAITING_APPROVAL', 'DISPATCHING', 'RUNNING'].includes(status)) return 'warning'
  return 'info'
}
