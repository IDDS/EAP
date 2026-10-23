<template>
  <div v-if="spans.length" class="trace-bars">
    <div class="bars-heading"><strong>步骤耗时</strong><span>当前加载 {{ spans.length }} 条 · 按观测时间排序</span></div>
    <button v-for="span in ordered" :key="`${span.traceId}:${span.spanId}`" class="span-row" :class="{ selected: selected === span.spanId }" @click="selected = selected === span.spanId ? '' : span.spanId" :aria-expanded="selected === span.spanId">
      <span class="span-label">{{ span.spanName || span.spanKind }}</span><span class="bar-track"><span class="bar-fill" :class="{ failed: /FAIL|ERROR|CANCEL/i.test(span.status || '') }" :style="{ width: `${Math.max(1, Math.max(0, span.durationMs || 0) / maxDuration * 100)}%` }"></span></span><span class="span-duration">{{ span.durationMs ?? '—' }} ms</span>
      <span v-if="selected === span.spanId" class="span-evidence">{{ span.spanId }} · {{ span.status || '未知' }} · Agent {{ span.agentId ?? '未关联' }}</span>
    </button>
    <p>条形表示各步骤上报耗时；并发步骤不可直接相加为任务总时长。</p>
  </div>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { TraceSpanObservation } from '@/types/domain'
const props = defineProps<{ spans: TraceSpanObservation[] }>()
const selected = ref('')
const ordered = computed(() => [...props.spans].sort((a, b) => Date.parse(a.observedAt) - Date.parse(b.observedAt)))
const maxDuration = computed(() => Math.max(1, ...props.spans.map(span => Math.max(0, span.durationMs || 0))))
watch(() => props.spans, () => { selected.value = '' })
</script>
<style scoped>
.trace-bars{margin:20px 0;border:1px solid #e2e8f0;border-radius:6px;padding:16px}.bars-heading{display:flex;justify-content:space-between;gap:12px;margin-bottom:14px;flex-wrap:wrap}.bars-heading span,.trace-bars p{font-size:12px;color:#64748b}.span-row{display:grid;grid-template-columns:minmax(80px,1fr) 1.4fr 75px;gap:10px;width:100%;background:transparent;border:0;border-radius:4px;padding:9px 6px;align-items:center;text-align:left;color:#334155;cursor:pointer}.span-row:hover,.span-row.selected{background:#f1f5f9}.span-row:focus-visible{outline:2px solid #2683b8}.span-label{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.bar-track{height:9px;background:#eef2f6;border-radius:3px;overflow:hidden}.bar-fill{display:block;height:100%;background:#2683b8;transition:width .35s}.bar-fill.failed{background:#c66b36}.span-duration{font-size:12px;text-align:right;font-variant-numeric:tabular-nums}.span-evidence{grid-column:1/-1;word-break:break-all;color:#64748b;font-size:12px}.trace-bars p{line-height:1.6;margin-bottom:0}@media(prefers-reduced-motion:reduce){.bar-fill{transition:none}}
</style>
