export function executionLabel(status: string): string {
  return ({PENDING:'等待执行',RUNNING:'正在执行',SUCCEEDED:'执行已完成',FAILED:'执行失败',CANCELLED:'已取消'} as Record<string,string>)[status] || '状态未知'
}
export function outcomeLabel(resolved?: boolean): string {
  return resolved === true ? '业务问题已解决' : resolved === false ? '业务问题尚未解决' : '业务结果待确认'
}
export function taskNextStep(task: {status:string;problemResolved?:boolean}): string {
  if(task.status==='FAILED') return '先查看执行结果和相关记录，定位失败原因，再决定是否重新发起任务。'
  if(task.status==='CANCELLED') return '任务已取消。如仍需处理，请联系任务负责人确认后续安排。'
  if(task.status==='PENDING') return '任务尚未开始，请确认任务是否已安排执行。'
  if(task.status==='RUNNING') return '任务仍在执行。可以查看执行记录，结果产生后再确认业务问题是否解决。'
  if(task.status!=='SUCCEEDED') return '执行状态尚未确认，请刷新进展或联系任务负责人核对。'
  if(task.problemResolved===false) return '已记录问题尚未解决，请补充原因并联系任务负责人跟进。'
  if(task.problemResolved===true) return '已登记为解决。请保留结果说明和相关证据，方便后续复核。'
  return '请先阅读执行结果，再确认业务问题是否真正解决。执行完成不等于业务成功。'
}
