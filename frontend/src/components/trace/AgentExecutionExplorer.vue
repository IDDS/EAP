<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { executionStreamPattern, getExecutionPage, type ExecutionFilter } from '@/api/agentExecutions'
import { usePermissions } from '@/stores/permissions'
import { useExecutionQuery } from './useExecutionQuery'
import { executionStreams } from './executionPresentation'
import AgentExecutionGraph from './AgentExecutionGraph.vue'

const props = defineProps<{ initialAgentId?: number }>()
const permissions = usePermissions()
const agentInput = shallowRef(props.initialAgentId ? String(props.initialAgentId) : '')
const streamInput = shallowRef('')
const filter = shallowRef<ExecutionFilter | null>({ agentId: props.initialAgentId, afterId: 0 })
const cursors = shallowRef<number[]>([])
const validationError = shallowRef('')
const selectedStream = shallowRef('')
const queryKey = computed(() => filter.value ? JSON.stringify(filter.value) : '')
const { data, loading, error, refresh } = useExecutionQuery(() => queryKey.value, (key, signal) => getExecutionPage(JSON.parse(key), signal))
const streams = computed(() => executionStreams(data.value))
watch([agentInput, streamInput], () => { filter.value = null; cursors.value = []; selectedStream.value = ''; validationError.value = '' }, { flush: 'sync' })
watch(() => props.initialAgentId, value => { agentInput.value = value ? String(value) : ''; search() })
watch([data, () => permissions.state.user, () => permissions.can('viewConsole')], () => { selectedStream.value = '' }, { flush: 'sync' })

function search() {
  const agentText = agentInput.value.trim(), streamKey = streamInput.value.trim()
  const agentId = agentText ? Number(agentText) : undefined
  selectedStream.value = ''; cursors.value = []; validationError.value = ''; filter.value = null
  if (agentText && (!/^[1-9]\d*$/.test(agentText) || !Number.isSafeInteger(agentId))) { validationError.value = '智能体编号须为有效的正整数。'; return }
  if (streamKey && !executionStreamPattern.test(streamKey)) { validationError.value = '事件流编号须为 64 位小写十六进制字符。'; return }
  filter.value = { agentId, streamKey: streamKey || undefined, afterId: 0 }
}
function next() {
  if (loading.value || !filter.value || !data.value?.hasMore) return
  cursors.value = [...cursors.value, filter.value.afterId]
  filter.value = { ...filter.value, afterId: data.value.nextAfterId }
}
function previous() {
  if (loading.value || !filter.value || !cursors.value.length) return
  const before = cursors.value.slice(0, -1)
  filter.value = { ...filter.value, afterId: cursors.value[cursors.value.length - 1] }
  cursors.value = before
}
</script>

<template>
  <section class="execution-explorer" aria-label="逻辑 Agent 执行观测">
    <header class="explorer-heading"><h3>逻辑 Agent 执行观测</h3><p>查看同进程子 Agent、工具调用与临时代码摘要，定位未收到的执行证据。</p></header>
    <el-alert v-if="!permissions.can('viewConsole')" title="当前账号没有查看执行观测的权限" type="info" :closable="false" />
    <template v-else>
      <form class="execution-filters" @submit.prevent="search">
        <label>智能体编号<input v-model="agentInput" inputmode="numeric" placeholder="留空查询全部" aria-label="执行观测智能体编号"></label>
        <label class="stream-filter">事件流编号<input v-model="streamInput" placeholder="留空查询全部事件流" maxlength="64" aria-label="执行观测事件流编号"></label>
        <el-button native-type="submit" type="primary">查询执行</el-button>
      </form>
      <p class="scope-note">按接收顺序每页读取最多 100 条事件；下方列出当前页涉及的事件流，同一流可能出现在多页。不受模型统计时间窗口、端点或 Trace 筛选影响。</p>
      <el-alert v-if="validationError || error" :title="validationError || error" type="error" :closable="false" show-icon />
      <div class="stream-heading"><h4>事件流 <span v-if="data">{{ streams.length }}</span></h4><el-button :loading="loading" :disabled="!filter" @click="refresh">刷新事件页</el-button></div>
      <p v-if="loading" role="status">正在读取事件流…</p>
      <p v-else-if="!filter && !validationError" class="scope-note">筛选已改变，请查询执行。</p>
      <el-empty v-else-if="data && !streams.length" description="当前条件下尚无执行事件">
        <p class="empty-guide">接入执行观测 SDK 或已支持的框架适配器后，才会出现逻辑执行记录。没有记录不表示 Agent 没有运行。</p>
      </el-empty>
      <ul v-else-if="data" class="stream-list" aria-label="执行事件流">
        <li v-for="stream in streams" :key="stream.streamKey"><button type="button" :aria-pressed="selectedStream === stream.streamKey" @click="selectedStream = stream.streamKey">
          <span class="stream-name">智能体 #{{ stream.agentId }} <strong>{{ stream.framework }}</strong> {{ stream.version }}</span>
          <span class="event-count">本页 {{ stream.pageEvents }} 条事件</span>
          <span class="stream-meta">实例：{{ stream.runtimeInstanceKey }}</span><code class="stream-key">{{ stream.streamKey }}</code>
          <span class="stream-action">查看执行关系</span>
        </button></li>
      </ul>
      <nav v-if="filter" class="event-paging" aria-label="执行事件分页"><el-button :disabled="loading || !cursors.length" @click="previous">上一事件页</el-button><span>第 {{ cursors.length + 1 }} 页{{ data ? ` · ${data.items.length} 条事件` : '' }}</span><el-button :disabled="loading || !data?.hasMore" @click="next">下一事件页</el-button></nav>
      <AgentExecutionGraph v-if="selectedStream" :key="selectedStream" :stream-key="selectedStream" />
    </template>
  </section>
</template>

<style scoped>
.execution-explorer { padding: 24px; border: 1px solid var(--console-line); border-radius: 6px; background: var(--console-surface); min-width: 0; color: var(--console-ink); }
.explorer-heading h3 { margin: 0; font-size: 20px; }.explorer-heading p, .scope-note, .empty-guide { font-size: 13px; line-height: 1.7; color: var(--console-muted); max-width: 80ch; }
.execution-filters { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; margin: 24px 0 12px; }.execution-filters label { display: grid; gap: 8px; font-size: 13px; min-width: 0; }.stream-filter { flex: 1 1 300px; }
.execution-filters input { min-height: 40px; width: 100%; padding: 8px 10px; box-sizing: border-box; border: 1px solid var(--console-line); border-radius: 4px; font: inherit; color: inherit; background: var(--console-surface); }
.execution-filters input:focus-visible, .stream-list button:focus-visible { outline: 2px solid var(--console-accent); outline-offset: 2px; }
.execution-filters .el-button { min-height: 40px; }.stream-heading { display: flex; align-items: center; justify-content: space-between; margin-top: 24px; }.stream-heading h4 { font-size: 15px; }.stream-heading span { color: var(--console-muted); font-weight: 400; }
.stream-list { list-style: none; margin: 0; padding: 0; }.stream-list li { margin-bottom: 8px; }.stream-list button { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px 16px; width: 100%; text-align: left; padding: 14px 16px; font: inherit; color: inherit; border: 1px solid var(--console-line); border-radius: 4px; background: var(--console-surface); cursor: pointer; }
.stream-list button[aria-pressed='true'] { background: var(--console-accent-soft); border-color: var(--console-accent); }.stream-name { font-size: 14px; overflow-wrap: anywhere; }.stream-name strong { margin-left: 8px; }.event-count, .stream-action { font-size: 12px; }.stream-action { color: var(--console-accent); grid-column: 2; grid-row: 3; }
.stream-meta, .stream-key { font-size: 12px; color: var(--console-muted); overflow-wrap: anywhere; grid-column: 1; }.event-paging { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin: 20px 0; font-size: 13px; }.event-paging .el-button { margin-left: 0; }
@media (max-width: 600px) { .execution-explorer { padding: 16px; }.execution-filters { display: grid; grid-template-columns: minmax(0, 1fr); }.stream-list button { grid-template-columns: minmax(0, 1fr); }.stream-action, .stream-key, .stream-meta { grid-column: 1; grid-row: auto; } }
</style>
