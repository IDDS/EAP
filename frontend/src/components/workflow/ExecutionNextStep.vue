<template>
  <section class="execution-next-step" aria-label="执行结论与下一步">
    <div><span>流程状态</span><strong>{{ statusLabel(execution.status) }}</strong></div>
    <div><span>结果结构验收</span><strong>{{ validationLabel }}</strong><small>结构合格仍需核对业务结论。</small></div>
    <div class="next-action"><span>建议下一步</span><p>{{ nextAction }}</p></div>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { WorkflowExecution } from '@/types/domain'
import { executionStatusLabel as statusLabel } from '@/api/taskWorkspace'
const props = defineProps<{ execution: WorkflowExecution }>()
const validations = computed(() => props.execution.steps.flatMap(step => step.resultValidation ? [step.resultValidation.status] : []))
const validationLabel = computed(() => {
  const values = validations.value
  if (values.includes('REJECTED')) return '存在不合格结果'
  if (!values.length) return '未记录验收'
  if (values.every(value => value === 'PASSED')) return `${values.length} 项已配置契约通过`
  return '部分结果尚未验收'
})
const nextAction = computed(() => {
  const status = props.execution.status
  if (['UNKNOWN','EXECUTION_UNKNOWN'].includes(status)) return '先核对原任务的工具效果、费用与资源占用。保留原编号交由负责人处置；查询证据不会重新执行，暂勿重发任务。'
  if (status === 'WAITING_APPROVAL') return '请在治理审批中核对对应申请。批准前先确认申请与原任务一致；审批完成不代表本次执行已自动恢复。'
  if (status === 'BLOCKED') return '查看步骤中的治理决定，核对权限、策略与预算。修正配置后先确认原任务没有待核对效果，再决定是否发起新任务。'
  if (validations.value.includes('REJECTED')) return '查看原步骤结果并修正结果契约或任务要求。已发生的调用和费用保留，当前流程已停止下游交接。'
  if (status === 'FAILED') return '查看首个失败步骤及关联观测。确认外部效果和资源状态后再处置，执行失败不代表所有费用或外部操作已撤销。'
  if (status === 'SUCCEEDED') return '打开步骤结果，核对证据与业务要求。流程完成和结构校验不能替代业务验收。'
  if (status === 'CANCELLED') return '核对已执行步骤的效果、费用及资源状态；后续未执行不代表此前操作已撤销。'
  return '等待原任务更新并按需刷新。保留原执行编号，避免重复提交。'
})
</script>
<style scoped>
.execution-next-step{display:grid;grid-template-columns:1fr 1fr;gap:16px;padding:16px;margin:16px 0;background:#f4f8fb;border:1px solid #dce7ef;border-radius:6px}.execution-next-step span,.execution-next-step small{display:block;color:#64748b;font-size:12px;line-height:1.7}.execution-next-step strong{display:block;font-size:14px;margin:5px 0;color:#334155}.next-action{grid-column:1/-1}.next-action p{font-size:13px;line-height:1.8;margin:4px 0;color:#475569}@media(max-width:600px){.execution-next-step{grid-template-columns:1fr}}
</style>
