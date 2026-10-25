const stages: Record<string, string> = { START_OBSERVER: '启动观察器', WAIT_READY: '等待服务就绪', SEND_CONTROL: '发送执行信号', READ_PROOF: '等待执行证明', WAIT_EXIT: '等待进程退出', VALIDATE_PROOF: '校验执行证明', VERIFY_EXPORT: '核对观测导出', PERSIST_RECEIPT: '保存回执', COMPLETED: '阶段已完成' }
const phases: Record<string, string> = { observer_start_ms: '启动观察器', ready_wait_ms: '等待就绪', control_write_ms: '发送执行信号', proof_read_ms: '读取执行证明', exit_wait_ms: '等待退出' }
const startup: Record<string, string> = { observer_import_ms: '观察器模块导入', identity_ready_ms: '身份准备', exporter_start_ms: '导出器启动', server_spawn_ms: '服务进程创建', server_ready_wait_ms: '等待服务就绪', observer_before_ready_ms: '观察器就绪前总计', server_import_ms: '服务模块导入', server_bind_ms: '服务绑定', server_fqdn_ms: '主机名处理（包含在服务绑定中）', server_before_ready_ms: '服务就绪前总计' }
function milliseconds(value: unknown) {
  if (typeof value !== 'string' && typeof value !== 'number') return null
  if (typeof value === 'string' && !/^(0|[1-9]\d*)$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= 0 && parsed <= 60000 ? parsed : null
}
export function runtimeDiagnostics(raw?: string) {
  let data: Record<string, unknown>
  try { data = JSON.parse(raw || '{}'); if (!data || Array.isArray(data) || typeof data !== 'object') return null } catch { return null }
  const stage = typeof data['eap.runtime.stage'] === 'string' ? stages[data['eap.runtime.stage'] as string] : undefined
  if (!stage) return null
  const deadline = data['eap.runtime.deadline_exceeded']
  const timings = Object.entries(phases).map(([key, label]) => ({ label, value: milliseconds(data['eap.runtime.' + key]) }))
  const startupTimings = Object.entries(startup).map(([key, label]) => ({ label, value: milliseconds(data['eap.runtime.startup.' + key]) }))
  const startupValid = String(data['eap.runtime.startup.version']) === '1' && startupTimings.every(t => t.value !== null)
  return { stage, deadline: deadline === 'true' || deadline === true ? true : deadline === 'false' || deadline === false ? false : null, timings, startup: startupValid ? startupTimings : [] }
}
