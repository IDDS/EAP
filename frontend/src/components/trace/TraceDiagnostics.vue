<template>
  <section class="trace-diagnostics" aria-label="Trace 诊断">
    <div class="diagnostic-summary">
      <div><span>当前记录</span><strong>{{ spans.length }}</strong></div>
      <div><span>失败记录</span><strong :class="{ failure: diagnostic.failed }">{{ diagnostic.failed }}</strong></div>
      <div><span>有效时间记录</span><strong>{{ diagnostic.timed }} / {{ spans.length }}</strong></div>
      <div><span>已观测时间跨度</span><strong>{{ diagnostic.timed ? `${diagnostic.duration.toLocaleString()} ms` : '未知' }}</strong></div>
    </div>
    <p class="diagnostic-note">仅分析当前已加载记录，不能证明任务完整或业务成功。并发 Span 不累加为任务耗时。时间显示精度为毫秒。</p>
    <p v-if="possiblyTruncated" class="diagnostic-warning" role="status">本次已达到 500 条读取上限，可能存在未加载的记录；当前诊断不代表完整 Trace。</p>
    <p v-if="diagnostic.missingParents || diagnostic.cycles" class="diagnostic-warning">{{ diagnostic.missingParents }} 条记录的父 Span 不在当前范围内；{{ diagnostic.cycles }} 条记录存在祖先循环。</p>
    <div class="diagnostic-toolbar"><h3>调用时间与异常</h3><label><input v-model="failuresOnly" type="checkbox" /> 仅看失败</label></div>
    <div class="waterfall-head"><span>调用步骤</span><span>相对最早生产端时间 · {{ diagnostic.duration.toLocaleString() }} ms</span></div>
    <button v-for="row in visibleRows" :key="`${row.span.traceId}:${row.span.spanId}:${row.span.id}`" class="waterfall-row" :aria-pressed="selectedId === row.span.id" @click="selectedId = row.span.id">
      <span class="span-label" :style="{ paddingLeft: `${Math.min(row.depth, 4) * 10}px` }"><b>{{ row.span.spanName }}</b><small>{{ row.span.spanKind }} · {{ row.span.status || '未知' }}{{ row.missingParent ? ' · 父记录缺失' : '' }}</small></span>
      <span v-if="row.timed" class="span-track"><i :class="{ failed: row.failed }" :style="{ left: `${row.left}%`, width: `${row.width}%` }" /><small>{{ row.end - row.start }} ms</small></span>
      <span v-else class="timing-unknown">{{ row.timingIssue }}</span>
    </button>
    <p v-if="!visibleRows.length" class="diagnostic-note">{{ failuresOnly ? '当前记录没有已报告的失败。' : '暂无调用记录。' }}</p>
    <div v-if="selected" class="span-detail" aria-live="polite">
      <div><h3>{{ selected.span.spanName }}</h3><button @click="selectedId = undefined">关闭详情</button></div>
      <dl><dt>Span ID</dt><dd>{{ selected.span.spanId }}</dd><dt>父 Span</dt><dd>{{ selected.span.parentSpanId || '未提供' }}</dd><dt>生产端开始</dt><dd>{{ selected.span.startedAt || '未知' }}</dd><dt>生产端结束</dt><dd>{{ selected.span.endedAt || '未知' }}</dd><dt>上报耗时</dt><dd>{{ selected.span.durationMs }} ms（可能与时间戳口径不同）</dd><dt>工具 / 操作</dt><dd>{{ selected.span.toolName || selected.span.operation || '未提供' }}</dd></dl>
      <p>此处展示运行元数据；失败原因需要结合已有治理与内容观测证据核对。</p>
      <RuntimeDiagnostics :attributes-json="selected.span.attributesJson" />
    </div>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { TraceSpanObservation } from '@/types/domain'
import { traceDiagnostics } from './trace-diagnostics'
import RuntimeDiagnostics from './RuntimeDiagnostics.vue'
const props = defineProps<{ spans: TraceSpanObservation[]; possiblyTruncated?: boolean; traceId: string }>()
const failuresOnly = ref(false)
const selectedId = ref<number>()
const diagnostic = computed(() => traceDiagnostics(props.spans))
const visibleRows = computed(() => diagnostic.value.bars.filter(row => !failuresOnly.value || row.failed))
const selected = computed(() => diagnostic.value.rows.find(row => row.span.id === selectedId.value))
watch(() => props.traceId, () => { selectedId.value = undefined; failuresOnly.value = false })
</script>
<style scoped>
.trace-diagnostics{margin:16px 0;color:#24415d}.diagnostic-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;padding:16px;background:#f1f6fc;border:1px solid #d7e3ef;border-radius:8px}.diagnostic-summary span{display:block;font-size:12px;color:#5d728b}.diagnostic-summary strong{display:block;font-size:22px;margin-top:6px;font-variant-numeric:tabular-nums}.failure{color:#bc384e}.diagnostic-note,.diagnostic-warning{font-size:12px;line-height:1.6}.diagnostic-note{color:#60758b}.diagnostic-warning{padding:10px;background:#fff6e4;color:#8c5b15;border-radius:5px}.diagnostic-toolbar,.span-detail>div{display:flex;justify-content:space-between;align-items:center;gap:12px}.diagnostic-toolbar h3,.span-detail h3{font-size:15px}.diagnostic-toolbar label{font-size:13px}.waterfall-head,.waterfall-row{display:grid;grid-template-columns:minmax(130px,35%) minmax(0,1fr);gap:14px}.waterfall-head{font-size:11px;color:#647a91;padding:10px}.waterfall-row{width:100%;background:transparent;border:0;border-top:1px solid #e2eaf2;text-align:left;color:inherit;padding:10px;cursor:pointer}.waterfall-row:hover,.waterfall-row[aria-pressed=true]{background:#edf5ff}.waterfall-row:focus-visible{outline:2px solid #3286cf}.span-label{min-width:0}.span-label b{display:block;font-size:13px;overflow-wrap:anywhere;font-weight:500}.span-label small{font-size:11px;color:#687d92}.span-track{position:relative;min-height:32px;border-left:1px solid #c8d7e6;background:repeating-linear-gradient(90deg,transparent 0,transparent calc(25% - 1px),#e1eaf3 calc(25% - 1px),#e1eaf3 25%)}.span-track i{position:absolute;top:4px;height:9px;min-width:2px;background:#398ac5;border-radius:2px;max-width:100%}.span-track i.failed{background:#cc4d63}.span-track small{position:absolute;right:0;bottom:0;font-size:11px;color:#526b83;background:#fff}.timing-unknown{font-size:12px;color:#8a6c3b}.span-detail{padding:16px;background:#f6f9fc;border:1px solid #d4e0ec;border-radius:6px;margin-top:12px}.span-detail dl{display:grid;grid-template-columns:100px 1fr;gap:8px;font-size:12px}.span-detail dd{margin:0;overflow-wrap:anywhere}.span-detail p{font-size:12px;color:#60758b}.span-detail button{cursor:pointer;padding:6px 10px;border:1px solid #b7cddd;background:white;color:#315e87;border-radius:4px}@media(max-width:600px){.diagnostic-summary{grid-template-columns:1fr 1fr}.waterfall-head,.waterfall-row{grid-template-columns:minmax(100px,42%) minmax(0,1fr);gap:8px}.diagnostic-summary strong{font-size:19px}}
</style>
