<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import type { ExecutionFilter } from '@/api/agentExecutions'
import { getDeliveryPage, deliveryAdvice, deliveryStateLabels, deliveryCredentialLabels } from '@/api/executionDeliveries'
import { useExecutionQuery } from './useExecutionQuery'

const props = defineProps<{ filter: Pick<ExecutionFilter, 'agentId' | 'streamKey'> | null }>()
const emit = defineEmits<{ openStream: [streamKey: string] }>()
const afterId = shallowRef(0)
const cursors = shallowRef<number[]>([])
const scope = computed(() => props.filter ? JSON.stringify({ agentId: props.filter.agentId, streamKey: props.filter.streamKey }) : '')
watch(scope, () => { afterId.value = 0; cursors.value = [] }, { flush: 'sync' })
const key = computed(() => scope.value ? JSON.stringify({ ...JSON.parse(scope.value), afterId: afterId.value }) : '')
const { data, loading, error, refresh } = useExecutionQuery(() => key.value, (query, signal) => getDeliveryPage(JSON.parse(query), signal))
const rows = computed(() => data.value?.items.map(item => ({ ...item, advice: deliveryAdvice(item) })) ?? [])
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
function next() {
  if (loading.value || !data.value?.hasMore) return
  cursors.value = [...cursors.value, afterId.value]; afterId.value = data.value.nextAfterId
}
function previous() {
  if (loading.value || !cursors.value.length) return
  afterId.value = cursors.value[cursors.value.length - 1]; cursors.value = cursors.value.slice(0, -1)
}
</script>

<template>
  <section class="delivery-panel" aria-label="执行事件投递状态">
    <header class="delivery-heading"><h4>投递状态</h4><el-button :disabled="!filter" :loading="loading" @click="refresh">刷新投递状态</el-button></header>
    <p class="delivery-note">最近一次客户端报告与当前服务端接收数分别展示。报告可能延迟或缺失；待确认不等于未接收，事件图完整也不等于客户端已确认。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="loading" role="status">正在读取投递报告…</p>
    <p v-else-if="!filter" class="delivery-note">筛选已改变，请查询执行。</p>
    <p v-else-if="data && !rows.length" class="delivery-empty">尚无投递状态报告。旧版程序或离线实例可能没有上报，不能据此判断待确认数为零。</p>
    <ul v-else-if="data" class="delivery-list">
      <li v-for="row in rows" :key="row.streamKey" class="delivery-row">
        <div class="delivery-title"><strong>智能体 #{{ row.agentId }} · {{ row.framework }} {{ row.frameworkVersion }}</strong><span class="delivery-state">{{ deliveryStateLabels[row.state] }}</span></div>
        <dl class="delivery-counts">
          <div><dt>报告总数</dt><dd>{{ row.total }}</dd></div><div><dt>客户端已确认</dt><dd>{{ row.acknowledged }}</dd></div>
          <div><dt>客户端待确认</dt><dd :class="{ 'delivery-pending': row.pending > 0 }">{{ row.pending }}</dd></div><div><dt>服务端已接收</dt><dd>{{ row.receivedEvents }}</dd></div>
        </dl>
        <p class="delivery-meta">报告接收于 {{ time(row.reportedAt) }} · 报告序号 {{ row.reportSequence }} · 累计投递尝试 {{ row.attempts }}</p>
        <p v-if="row.stale" class="delivery-stale">最近查询时，这份报告已超过5分钟；它不是实例当前状态的证明。</p>
        <p class="delivery-meta">{{ deliveryCredentialLabels[row.credentialState] }} · 实例 {{ row.runtimeInstanceKey }}</p>
        <p class="delivery-advice">{{ row.advice }}</p>
        <code class="delivery-stream">{{ row.streamKey }}</code>
        <el-button :disabled="row.receivedEvents === 0" @click="emit('openStream', row.streamKey)">查看已收事件</el-button>
      </li>
    </ul>
    <nav v-if="data" class="delivery-paging" aria-label="投递报告分页">
      <el-button :disabled="loading || !cursors.length" @click="previous">上一报告页</el-button><span>第 {{ cursors.length + 1 }} 页</span>
      <el-button :disabled="loading || !data.hasMore" @click="next">下一报告页</el-button><span>查询于 {{ time(data.asOf) }}</span>
    </nav>
  </section>
</template>

<style scoped>
.delivery-panel { margin-top: 24px; border-block: 1px solid var(--console-line); padding-bottom: 20px; min-width: 0; }
.delivery-heading, .delivery-title { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.delivery-heading h4 { font-size: 15px; }.delivery-note, .delivery-empty, .delivery-meta, .delivery-stale { color: var(--console-muted); font-size: 13px; line-height: 1.7; max-width: 80ch; overflow-wrap: anywhere; }
.delivery-list { padding: 0; margin: 16px 0 0; list-style: none; }.delivery-row { border-left: 3px solid var(--console-accent); padding: 16px 20px; margin-bottom: 16px; background: var(--console-canvas); }
.delivery-title { font-size: 14px; overflow-wrap: anywhere; }.delivery-state { color: var(--console-accent); font-weight: 600; }
.delivery-counts { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 20px 0; }
.delivery-counts dt { font-size: 12px; color: var(--console-muted); }.delivery-counts dd { font-size: 20px; font-weight: 600; margin: 6px 0 0; font-variant-numeric: tabular-nums; }.delivery-pending { color: #9a5c12; }
.delivery-meta, .delivery-stale { margin: 6px 0; }.delivery-advice { font-size: 13px; line-height: 1.7; max-width: 80ch; }.delivery-stream { display: block; overflow-wrap: anywhere; font-size: 12px; color: var(--console-muted); margin: 12px 0; }
.delivery-paging { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; font-size: 12px; color: var(--console-muted); }.delivery-paging .el-button { margin-left: 0; }
@media (max-width: 600px) { .delivery-row { padding: 14px; }.delivery-counts { grid-template-columns: repeat(2, minmax(0, 1fr)); }.delivery-heading .el-button { min-height: 40px; } }
</style>
