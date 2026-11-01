<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import type { Agent, TraceSpanObservation } from '@/types/domain'
import { traceRequests } from './trace-requests'

const props = defineProps<{ spans: TraceSpanObservation[]; agents: Agent[]; agentId?: number }>()
const emit = defineEmits<{ selectAgent: [id: number | undefined]; openTrace: [traceId: string] }>()
const windowHours = shallowRef(24)
const status = shallowRef('')
const rows = computed(() => {
  const since = Date.now() - windowHours.value * 3600000
  return traceRequests(props.spans.filter(span => Date.parse(span.observedAt) >= since))
    .filter(row => !status.value || row.observationStatus === status.value)
})
const names = computed(() => new Map(props.agents.map(agent => [agent.id, agent.name])))
function agentLabel(ids: number[]) {
  return ids.length ? ids.map(id => `${names.value.get(id) || '智能体'} #${id}`).join('、') : '未归因智能体'
}
function statusLabel(value: string) {
  return value === 'failed' ? '观测到失败' : value === 'active' ? '观测到进行中' : '已有观测'
}
function formatTime(value: string) { return new Date(value).toLocaleString('zh-CN') }
</script>

<template>
  <section class="request-explorer" aria-labelledby="request-explorer-title">
    <div class="request-heading">
      <div><h3 id="request-explorer-title">查找一次调用</h3><p>先选择智能体和时间，再按观测状态找到调用；无需输入 Trace ID。</p></div>
    </div>
    <div class="request-filters">
      <label>智能体<el-select :model-value="agentId" clearable filterable placeholder="全部智能体" aria-label="选择智能体" @update:model-value="emit('selectAgent', $event || undefined)"><el-option v-for="agent in agents" :key="agent.id" :label="`${agent.name} · #${agent.id}`" :value="agent.id" /></el-select></label>
      <label>观测时间<el-select v-model="windowHours" aria-label="调用观测时间"><el-option label="最近 1 小时" :value="1" /><el-option label="最近 24 小时" :value="24" /><el-option label="最近 7 天" :value="168" /><el-option label="最近 30 天" :value="720" /></el-select></label>
      <label>观测状态<el-select v-model="status" aria-label="调用观测状态"><el-option label="全部状态" value="" /><el-option label="观测到失败" value="failed" /><el-option label="观测到进行中" value="active" /><el-option label="已有观测" value="observed" /></el-select></label>
    </div>
    <p class="request-scope">按已加载的最多 500 条 Span 归并；历史和单条链路可能不完整。状态仅反映已观测的 Span，不代表整次任务结果。</p>
    <ul v-if="rows.length" class="request-list">
      <li v-for="row in rows" :key="row.traceId">
        <div class="request-summary"><strong>{{ row.label }}</strong><span>{{ agentLabel(row.agentIds) }}</span></div>
        <div class="request-meta"><time>{{ formatTime(row.lastObservedAt) }}</time><span>{{ statusLabel(row.observationStatus) }} · {{ row.observedSpanCount }} 条已加载 Span</span></div>
        <el-button type="primary" plain @click="emit('openTrace', row.traceId)">查看调用链</el-button>
      </li>
    </ul>
    <p v-else class="request-empty">当前范围没有找到 Span。可以扩大时间范围、切换智能体，或在下方历史记录中继续查找。</p>
  </section>
</template>

<style scoped>
.request-explorer{background:var(--console-surface);border:1px solid var(--console-line);border-radius:var(--console-radius);padding:20px;min-width:0}.request-heading h3{margin:0;color:var(--console-ink);font-size:17px}.request-heading p,.request-scope,.request-empty{color:var(--console-muted);font-size:13px;line-height:1.7}.request-heading p{margin:6px 0 18px}.request-filters{display:flex;gap:14px;flex-wrap:wrap}.request-filters label{display:grid;gap:6px;color:var(--console-muted);font-size:13px;min-width:150px}.request-filters label:first-child{min-width:240px}.request-filters :deep(.el-select){width:100%}.request-scope{margin:14px 0}.request-list{list-style:none;margin:0;padding:0;border-top:1px solid var(--console-line)}.request-list li{display:grid;grid-template-columns:minmax(0,1fr) minmax(160px,auto) auto;align-items:center;gap:16px;padding:14px 0;border-bottom:1px solid var(--console-line)}.request-summary,.request-meta{display:grid;gap:5px;min-width:0}.request-summary strong{color:var(--console-ink);overflow-wrap:anywhere}.request-summary span,.request-meta{color:var(--console-muted);font-size:13px;overflow-wrap:anywhere}.request-empty{padding:12px 0;margin:0}@media(max-width:700px){.request-explorer{padding:16px}.request-filters{display:grid;grid-template-columns:1fr 1fr}.request-filters label:first-child{grid-column:1 / -1;min-width:0}.request-filters label{min-width:0}.request-list li{grid-template-columns:minmax(0,1fr);gap:10px}.request-list .el-button{justify-self:start}}
</style>
