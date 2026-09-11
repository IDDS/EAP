<template>
  <div class="run-progress" :aria-busy="active">
    <div class="run-heading"><div><span class="run-eyebrow">观测评估</span><h3>{{ label }}</h3></div><span class="run-count" aria-live="polite">{{ job ? `${job.processed || 0} / ${job.total || 0} 条` : '等待任务' }}</span></div>
    <svg viewBox="0 0 640 90" role="img" :aria-label="`处理阶段：${label}`" class="run-flow">
      <path d="M65 30H575" class="flow-track" />
      <path :d="`M65 30H${65 + Math.max(0, stage) * 170}`" class="flow-completed" />
      <g v-for="(step, index) in steps" :key="step.key" :transform="`translate(${65 + index * 170},30)`" :class="{ current: active && index === stage, done: index < stage || finished }">
        <circle r="14" /><text text-anchor="middle" dy="5" class="step-number">{{ index + 1 }}</text><text text-anchor="middle" y="44" class="step-label">{{ step.label }}</text>
      </g>
    </svg>
    <ol class="mobile-stages"><li v-for="(step, index) in steps" :key="step.key" :class="{ current: active && index === stage, done: index < stage || finished }"><span>{{ index + 1 }}</span>{{ step.label }}</li></ol>
    <el-progress v-if="job && (job.total || 0) > 0" :percentage="percentage" :status="job.status === 'FAILED' ? 'exception' : job.status === 'SUCCEEDED' ? 'success' : undefined" :stroke-width="6" />
    <p class="run-description">{{ description }}</p>
    <div v-if="job" class="run-outcomes"><span>评估结果 <b>{{ job.evaluated }}</b></span><span>证据不足或不适用 <b>{{ job.skipped }}</b></span><span :class="{ danger: job.failed }">处理失败 <b>{{ job.failed }}</b></span></div>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { EvaluationJob } from '@/types/domain'
const props = defineProps<{ job?: EvaluationJob }>()
const steps = [{ key: 'TOOLS', label: '工具调用' }, { key: 'TRACES', label: '任务证据' }, { key: 'RAG', label: '知识检索' }, { key: 'COMPLETE', label: '结果归档' }]
const active = computed(() => props.job?.status === 'RUNNING')
const finished = computed(() => ['SUCCEEDED', 'PARTIAL'].includes(props.job?.status || ''))
const stage = computed(() => steps.findIndex(step => step.key === props.job?.phase))
const percentage = computed(() => Math.min(finished.value ? 100 : 99, Math.round((props.job?.processed || 0) / Math.max(1, props.job?.total || 0) * 100)))
const label = computed(() => !props.job ? '尚未开始' : props.job.status === 'QUEUED' ? '等待处理' : props.job.status === 'FAILED' ? '处理中断' : props.job.status === 'PARTIAL' ? '本批完成，部分失败' : finished.value ? '本批处理完成' : stage.value < 0 ? '正在准备观测' : `正在处理 · ${steps[stage.value]?.label}`)
const description = computed(() => props.job?.errorMessage || (finished.value ? '进度仅代表本批。后续批次继续扫描，历史观测会定期复查。结果数包含复用的已有评估。' : '依据后台实际处理状态更新；没有明确任务完成证据时，不推断任务成功。'))
</script>
<style scoped>
.mobile-stages{display:none}.run-progress{padding:22px 24px;background:#fff;border:1px solid #e2e8f0;border-top:3px solid #2683b8;border-radius:6px}.run-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.run-eyebrow{color:#64748b;font-size:12px}.run-heading h3{margin:5px 0;font-size:18px;color:#25364b}.run-count{font-variant-numeric:tabular-nums;color:#475569;font-size:14px}.run-flow{width:100%;max-height:112px;margin:16px 0 0;overflow:visible}.flow-track{stroke:#e2e8f0;stroke-width:3}.flow-completed{stroke:#2683b8;stroke-width:3;transition:d .3s}.run-flow circle{fill:#eef2f6;stroke:#cbd5e1;stroke-width:2}.run-flow .done circle{fill:#238075;stroke:#238075}.run-flow .current circle{fill:#2683b8;stroke:#2683b8;animation:phase-pulse 1.6s ease-in-out infinite}.step-number{fill:#64748b;font-size:13px}.done .step-number,.current .step-number{fill:white}.step-label{font-size:13px;fill:#475569}.run-description{font-size:13px;line-height:1.7;color:#64748b;margin:12px 0}.run-outcomes{display:flex;gap:24px;flex-wrap:wrap;color:#64748b;font-size:13px}.run-outcomes b{color:#334155;margin-left:6px}.run-outcomes .danger,.run-outcomes .danger b{color:#b45309}@keyframes phase-pulse{50%{stroke-width:7;stroke-opacity:.22}}@media(prefers-reduced-motion:reduce){.run-flow .current circle{animation:none}.flow-completed{transition:none}}@media(max-width:600px){.run-flow{display:none}.mobile-stages{display:grid;grid-template-columns:1fr 1fr;gap:16px 8px;list-style:none;padding:0;margin:24px 0}.mobile-stages li{display:flex;align-items:center;gap:8px;font-size:13px;color:#64748b}.mobile-stages li span{display:grid;place-items:center;border:1px solid #cbd5e1;border-radius:50%;width:24px;height:24px;font-size:12px}.mobile-stages li.current{color:#176b9b;font-weight:600}.mobile-stages li.current span{background:#2683b8;color:white;border-color:#2683b8}.mobile-stages li.done span{background:#238075;color:white;border-color:#238075}.run-progress{padding:16px}.run-heading{align-items:flex-start}.run-heading h3{font-size:16px}.run-count{white-space:nowrap}.step-label{font-size:15px}}
</style>
