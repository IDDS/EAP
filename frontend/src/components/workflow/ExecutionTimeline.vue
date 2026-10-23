<template>
  <section class="execution-timeline" aria-label="执行过程">
    <div class="timeline-title"><div><span class="timeline-kicker">执行过程</span><h3>{{ execution.objective }}</h3></div><el-tag :type="tone(execution.status)">{{ label(execution.status) }}</el-tag></div>
    <p class="timeline-description">{{ execution.reason || '按实际执行顺序显示已记录步骤。' }}</p>
    <ol v-if="execution.steps.length" class="execution-stages">
      <li v-for="step in execution.steps" :key="step.sequence" :class="{ live: execution.status === 'RUNNING' && step.status === 'DISPATCHING' }">
        <button :aria-expanded="selected === step.sequence" @click="selected = selected === step.sequence ? undefined : step.sequence">
          <span class="stage-dot" :class="tone(step.status)">{{ step.sequence }}</span><span class="stage-body"><strong>{{ step.label }}</strong><span>{{ label(step.status) }}<small v-if="step.taskKey" class="validation-label" :class="{ rejected: step.resultValidation?.status === 'REJECTED' }">{{ validationLabel(step.resultValidation?.status) }}</small></span></span>
        </button>
        <div v-if="selected === step.sequence" class="step-detail"><p>{{ step.reason }}</p><p v-if="step.taskKey" class="result-validation">{{ validationLabel(step.resultValidation?.status) }}<template v-if="step.resultValidation"> · {{ validationReason(step.resultValidation.code) }}<br />契约摘要：<span class="contract-digest">{{ step.resultValidation.contractSha256 }}</span></template></p><p v-if="step.reclaimStatus" class="reclaim-status">资源回收：{{ reclaimLabel(step.reclaimStatus) }}</p><dl><dt>执行方式</dt><dd>{{ step.runtimeInstanceKey?.startsWith('ax://') ? '隔离沙箱' : step.taskKey ? '常规执行' : '平台控制节点' }}</dd><dt>任务标识</dt><dd>{{ step.taskKey || '无外部任务' }}</dd><dt>运行实例</dt><dd>{{ step.runtimeInstanceKey || '平台节点' }}</dd><dt>策略决定</dt><dd>{{ step.safetyDecision }}</dd></dl><router-link v-if="step.runtimeInstanceKey?.startsWith('ax://')" :to="{ path: '/ax-tasks', query: { taskKey: step.taskKey } }">查看任务与沙箱 →</router-link></div>
      </li>
    </ol>
    <el-empty v-else :image-size="48" :description="execution.status === 'QUEUED' ? '已入队，等待开始处理' : '暂无步骤记录'" />
    <ToolRecoveryFacts :workflow-id="execution.workflowId" :run-key="execution.runKey" />
    <p class="timeline-footnote">点击步骤查看证据。结果未知时先核对外部任务；此视图不会重放操作。</p>
  </section>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import ToolRecoveryFacts from './ToolRecoveryFacts.vue'
import type { WorkflowExecution } from '@/types/domain'
const props = defineProps<{ execution: WorkflowExecution }>()
const selected = ref<number>()
watch(() => props.execution.runKey, () => { selected.value = undefined })
function label(status: string) {
  return ({ QUEUED: '等待执行', RUNNING: '正在执行', DISPATCHING: '正在派发', SUCCEEDED: '已确认完成', FAILED: '执行失败', EXECUTION_UNKNOWN: '结果未知 · 需核对', WAITING_APPROVAL: '等待审批', BLOCKED: '已阻断', CHECK_PASSED: '检查通过' } as Record<string, string>)[status] || status
}
function validationLabel(status?: string) {
  return ({ PASSED: '结构验收通过', REJECTED: '结构验收未通过 · 已停止后续交接', NOT_EVALUATED: '未验收 · 任务未确认成功' } as Record<string, string>)[status || ''] || '未记录结构验收'
}
function validationReason(code: string) {
  return ({ VALID: '符合原结果契约', OUTPUT_MISSING: '缺少结果', TEXT_REQUIRED: '应返回 JSON 文本', INVALID_JSON: '结果不是完整且无歧义的 JSON', TYPE_MISMATCH: '字段类型不符', REQUIRED_MISSING: '缺少必填字段', UNEXPECTED_PROPERTY: '包含未允许的字段', RECEIPT_NOT_SUCCEEDED: '先核对原任务回执' } as Record<string, string>)[code] || '请核对原结果契约'
}
function reclaimLabel(status: string) {
  return ({ CONFIRMED: '已确认休眠', UNCONFIRMED: '休眠未确认，已阻止此实例的新派发，请核对原实例', SKIPPED_BUSY: '存在其他执行或未确认占用，已保留运行状态', SKIPPED_UNKNOWN: '任务结果未知，未休眠', SKIPPED_LEGACY: '旧执行未纳入回收保护，未休眠' } as Record<string, string>)[status] || '回收状态待核对'
}
function tone(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (['SUCCEEDED', 'CHECK_PASSED'].includes(status)) return 'success'
  if (['FAILED', 'BLOCKED'].includes(status)) return 'danger'
  if (['EXECUTION_UNKNOWN', 'WAITING_APPROVAL', 'DISPATCHING', 'RUNNING'].includes(status)) return 'warning'
  return 'info'
}
</script>
<style scoped>
.validation-label{display:block;font-size:12px;margin-top:4px}.validation-label.rejected{color:#ac3939}.contract-digest{overflow-wrap:anywhere}.result-validation{line-height:1.7;overflow-wrap:anywhere}.execution-timeline{border:1px solid #dfe7ee;border-radius:6px;padding:22px;background:#fff;margin:16px 0}.timeline-title{display:flex;justify-content:space-between;gap:16px;align-items:center}.timeline-kicker{font-size:12px;color:#64748b}.timeline-title h3{font-size:18px;margin:6px 0;color:#25364b}.timeline-description,.timeline-footnote{color:#64748b;line-height:1.7;font-size:13px}.execution-stages{list-style:none;padding:0;margin:24px 0 16px;display:grid;gap:0}.execution-stages li{position:relative;padding:0 0 18px}.execution-stages li:not(:last-child):before{content:'';position:absolute;left:17px;top:36px;bottom:0;border-left:2px solid #dbe4ed}.execution-stages button{border:0;background:none;display:flex;align-items:center;gap:14px;padding:4px 0;text-align:left;width:100%;min-height:44px;cursor:pointer;border-radius:4px}.execution-stages button:hover{background:#f5f8fb}.execution-stages button:focus-visible{outline:2px solid #2683b8}.stage-dot{width:34px;height:34px;border:1px solid #cbd5e1;border-radius:50%;display:grid;place-items:center;flex-shrink:0;font-size:13px;background:#f1f5f9;color:#475569}.stage-dot.success{color:#277a58;background:#e8f4ee;border-color:#86bca2}.stage-dot.danger{color:#ac3939;background:#fceced;border-color:#daa0a0}.stage-dot.warning{color:#986011;background:#fff5df;border-color:#d8b777}.stage-body{display:flex;justify-content:space-between;gap:16px;align-items:center;flex:1}.stage-body strong{font-size:14px;color:#334155}.stage-body>span{font-size:13px;color:#64748b}.live .stage-dot{animation:live-step 1.5s ease-in-out infinite}.step-detail{margin:8px 0 0 48px;padding:12px 16px;background:#f6f8fb;border-radius:4px;font-size:13px;color:#475569}.step-detail p{margin:0 0 10px}.step-detail dl{display:grid;grid-template-columns:80px 1fr;gap:8px;margin:0}.step-detail dd{margin:0;overflow-wrap:anywhere}.step-detail dt{color:#64748b}@keyframes live-step{50%{box-shadow:0 0 0 5px #d8b77725}}@media(prefers-reduced-motion:reduce){.live .stage-dot{animation:none}}@media(max-width:600px){.execution-timeline{padding:16px}.stage-body{align-items:flex-start;flex-direction:column;gap:4px}.timeline-title{align-items:flex-start}.timeline-title h3{font-size:16px}.step-detail{margin-left:18px}.step-detail dl{grid-template-columns:1fr}}
</style>
