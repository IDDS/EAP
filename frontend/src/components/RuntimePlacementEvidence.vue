<template>
  <section class="placement-evidence" aria-label="任务运行位置证据">
    <h4>任务运行位置证据</h4>
    <p class="placement-note">读取已保存的采样。后台查询可能晚于触发时刻，位置仅对应所示查询时间；两次位置相同也不证明执行期间没有迁移。</p>
    <p v-if="!samples?.length" class="placement-empty">此任务未留存位置证据，不能判断当时运行在哪个节点。读取不会补采历史。</p>
    <div v-else class="placement-samples">
      <article v-for="row in rows" :key="row.phase" class="placement-sample">
        <div class="placement-heading"><h5>{{ row.label }}</h5><span :class="{ uncertain: row.state !== 'ASSIGNED' }">{{ row.status }}</span></div>
        <template v-if="row.sample">
          <dl><dt>请求采样</dt><dd>{{ time(row.sample.requestedAt) }}</dd>
            <template v-if="row.sample.snapshot"><dt>实际查询</dt><dd>{{ time(row.sample.snapshot.observationStartedAt) }} — {{ time(row.sample.snapshot.observedAt) }}</dd>
              <dt>位置来源</dt><dd>{{ row.source }}</dd>
              <template v-if="row.location"><dt>节点</dt><dd>{{ row.location.nodeName }}</dd><dt>Pod</dt><dd>{{ row.location.namespace }} / {{ row.location.podName }}</dd></template>
            </template>
          </dl>
          <p v-if="row.reason" class="placement-note">{{ row.reason }}</p>
          <details v-if="row.location && row.sample.snapshot"><summary>查看身份与位置标识</summary><dl>
            <dt>Actor UID</dt><dd>{{ row.sample.snapshot.actorUid }}</dd><dt>Actor 版本</dt><dd>{{ row.sample.snapshot.actorVersion }}</dd>
            <dt>Worker UID</dt><dd>{{ row.location.workerUid }}</dd><dt>Pod UID</dt><dd>{{ row.location.podUid }}</dd>
          </dl></details>
        </template>
        <p v-else class="placement-note">此阶段没有留存采样，不能据此判断位置。</p>
      </article>
    </div>
    <p class="placement-note">位置来自控制面查询，不能替代 MCP 协议结果、原任务身份核对或外部工具效果证明。</p>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { RuntimePlacementEvidence } from '@/api/observationCoverage'
const props = defineProps<{ samples?: RuntimePlacementEvidence[] }>()
const rows = computed(() => (['DISPATCH_REQUESTED', 'RECEIPT_RETURNED'] as const).map(phase => {
  const sample = props.samples?.find(item => item.phase === phase)
  const snapshot = sample?.captureState === 'RECORDED' ? sample.snapshot : null
  const verified = snapshot?.source === 'SUBSTRATE_CONTROL_API'
  const state = snapshot && (snapshot.status === 'UNKNOWN' || verified) ? snapshot.status : sample ? 'INCOMPLETE' : 'MISSING'
  return {
    phase, sample, state, label: phase === 'DISPATCH_REQUESTED' ? '派发时请求采样' : '收到回执后请求采样',
    status: ({ ASSIGNED: '已记录分配', UNASSIGNED: '已确认无当前分配', UNKNOWN: '位置未确认', INCOMPLETE: '采样未完成', MISSING: '未记录' } as Record<string, string>)[state],
    source: verified ? 'Substrate 控制面 · 已核对原任务绑定' : '未确认',
    location: state === 'ASSIGNED' && verified ? snapshot?.placement : null,
    reason: state === 'INCOMPLETE' ? '采样可能仍在处理或已中断；不会补采历史。' : state === 'UNKNOWN' ? reason(snapshot?.reason) : state === 'UNASSIGNED' ? '查询时实例没有可用的运行分配；不代表任务没有执行。' : '',
  }
}))
function time(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? '时间未确认' : date.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false, fractionalSecondDigits: 3 })
}
function reason(value?: string) {
  return ({ ORIGINAL_IDENTITY_MISMATCH: '当前实例绑定已变化，未借用替代实例的位置。', IDENTITY_MISMATCH: '查询身份与原任务不一致。',
    SNAPSHOT_CHANGED: '查询期间分配发生变化。', INSTANCE_OFFLINE: '查询时实例已离线。',
    CONTROL_API_UNAVAILABLE: '位置来源暂不可用，未推测运行节点。', CAPTURE_FAILED: '位置采样未成功，原任务结果保持。',
    NO_WORKER_ASSIGNMENT: '上游尚未提供可确认的运行分配。' } as Record<string, string>)[value || ''] || '没有足够证据确认查询时的位置。'
}
</script>
<style scoped>
.placement-evidence{margin:20px 0;min-width:0}.placement-evidence h4{font-size:14px;margin:0 0 10px}.placement-note,.placement-empty{font-size:12px;color:#64748b;line-height:1.8;margin:10px 0}.placement-empty{padding:12px;background:#f6f8fb;border-radius:4px}.placement-samples{display:grid;gap:12px}.placement-sample{border:1px solid #dfe7ee;border-radius:5px;padding:14px;min-width:0}.placement-heading{display:flex;gap:12px;justify-content:space-between;align-items:flex-start}.placement-heading h5{font-size:13px;margin:0}.placement-heading span{font-size:12px;color:#277a58;flex-shrink:0}.placement-heading .uncertain{color:#93600f}.placement-sample dl{display:grid;grid-template-columns:90px minmax(0,1fr);gap:8px;font-size:12px;margin:14px 0 0;line-height:1.7}.placement-sample dt{color:#64748b}.placement-sample dd{margin:0;overflow-wrap:anywhere}.placement-sample summary{font-size:12px;color:#287eac;cursor:pointer;line-height:2.4}.placement-sample summary:focus-visible{outline:2px solid #287eac}@media(max-width:600px){.placement-heading{flex-direction:column;gap:6px}.placement-sample dl{grid-template-columns:1fr;gap:3px}.placement-sample dt:not(:first-child){margin-top:6px}}
</style>
