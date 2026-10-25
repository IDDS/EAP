<template>
  <section class="execution-catalog" aria-label="全部执行记录">
    <form class="catalog-filters" @submit.prevent="search">
      <label>任务类型<select v-model="filters.kind"><option value="ALL">全部类型</option><option value="WORKFLOW">工作流</option><option value="PLAN">团队计划</option><option value="AX">独立异步任务</option><option v-if="permissions.can('operateInstances')" value="CHAT">我的试运行对话</option></select></label>
      <label>执行状态<select v-model="filters.status"><option value="ALL">全部状态</option><option value="ACTIVE">执行中</option><option value="SUCCEEDED">已确认完成</option><option value="ATTENTION">需要处理</option><option value="UNKNOWN">结果待核对</option><option value="FAILED">执行失败</option></select></label>
      <label class="catalog-search">查找任务<input v-model="filters.query" maxlength="128" placeholder="任务名称、执行编号或发起人编号" /></label>
      <label>开始时间<input v-model="fromLocal" type="datetime-local" /></label><label>结束时间<input v-model="untilLocal" type="datetime-local" /></label>
      <label class="mine-filter"><input v-model="filters.mine" type="checkbox" />仅我发起</label>
      <button type="submit" :disabled="loading">查询</button><button type="button" :disabled="loading" @click="reset">重置</button>
    </form>
    <p class="catalog-scope">汇集工作流、团队计划和独立异步任务。对话仅显示当前账号有权查询的本人回执；工作流内的异步步骤在原执行中查看。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <p v-if="loading" role="status">正在查询执行记录…</p>
    <div v-else-if="report" class="catalog-results">
      <p v-if="!report.items.length" class="catalog-empty">没有符合条件的执行记录。可调整筛选，或从工作流编排与团队计划发起任务。</p>
      <ul v-else class="catalog-list">
        <li v-for="entry in report.items" :key="entry.kind + ':' + entry.runKey">
          <div class="catalog-title"><span class="kind-label">{{ executionKindLabel(entry.kind) }}</span><router-link v-if="executionLocation(entry)" :to="executionLocation(entry)!">{{ entry.title }}</router-link><button v-else class="chat-link" @click="openChat(entry)">{{ entry.title }} · 查看原回执</button></div>
          <el-tag :type="tone(entry.status)">{{ statusLabel(entry.status) }}</el-tag>
          <small class="catalog-time">{{ time(entry.createdAt) }}</small>
          <details class="catalog-identity"><summary>执行标识与发起人</summary><p>{{ entry.runKey }}</p><p>发起人编号：{{ entry.requester }}</p></details>
        </li>
      </ul>
      <nav class="catalog-pagination" aria-label="执行记录分页"><span>共 {{ report.total }} 条 · 第 {{ report.page }} / {{ maxPage }} 页</span><button :disabled="report.page <= 1 || loading" @click="changePage(-1)">上一页</button><button :disabled="report.page >= maxPage || loading" @click="changePage(1)">下一页</button></nav>
      <p v-if="report.total > (report.windowLimit ?? 10000)" class="catalog-scope">当前筛选仅支持翻阅前 {{ report.windowLimit ?? 10000 }} 条记录，请缩小时间或任务范围查看更早历史。</p>
      <small class="catalog-scope">查询时间 {{ time(report.observedAt) }}。状态会随执行更新；翻页读取当时记录，刷新不会重新执行。</small>
    </div>
    <section v-if="selectedChat" class="chat-receipt" aria-label="原对话回执">
      <div class="receipt-heading"><h3>原对话回执</h3><button @click="clearChat">关闭</button></div><p>{{ selectedChat.runKey }}</p>
      <p v-if="chatLoading" role="status">正在读取原回执…</p><el-alert v-if="chatError" :title="chatError" type="error" :closable="false" />
      <template v-if="chatReceipt"><el-tag :type="tone(chatReceipt.status)">{{ statusLabel(chatReceipt.status) }}</el-tag><p>{{ chatReceipt.notice }}</p><router-link v-if="chatReceipt.traceId && /^[0-9a-f]{32}$/i.test(chatReceipt.traceId) && !/^0+$/.test(chatReceipt.traceId)" :to="{ path: '/physical', query: { traceId: chatReceipt.traceId, agentId: selectedChat.agentId } }">查看关联观测 →</router-link></template>
    </section>
  </section>
</template>
<script setup lang="ts">
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue'
import { usePermissions } from '@/stores/permissions'
import { getData } from '@/api/http'
import { listExecutions, executionKindLabel, executionLocation } from '@/api/executionCatalog'
import type { ExecutionEntry, ExecutionPage, ExecutionFilters } from '@/api/executionCatalog'
import { executionStatusLabel as statusLabel, executionStatusTone as tone } from '@/api/taskWorkspace'
const permissions = usePermissions()
const defaults = (): ExecutionFilters => ({ kind: 'ALL', status: 'ALL', query: '', mine: false, page: 1, size: 20 })
const filters = reactive(defaults()), fromLocal = ref(''), untilLocal = ref('')
const report = ref<ExecutionPage | null>(null), loading = ref(false), error = ref('')
const selectedChat = ref<ExecutionEntry | null>(null), chatLoading = ref(false), chatError = ref('')
const chatReceipt = ref<{ requestKey: string; status: string; notice: string; traceId?: string } | null>(null)
let revision = 0, chatRevision = 0, alive = true
let applied = defaults()
const maxPage = computed(() => report.value ? Math.max(1, Math.ceil(Math.min(report.value.total, report.value.windowLimit ?? 10000) / report.value.size)) : 1)
const time = (value: string) => new Date(value).toLocaleString('zh-CN')
function clearChat() { chatRevision++; selectedChat.value = null; chatReceipt.value = null; chatError.value = ''; chatLoading.value = false }
async function load(params: ExecutionFilters) {
  const current = ++revision, user = permissions.state.user
  report.value = null; error.value = ''; loading.value = false; clearChat()
  if (!alive || !permissions.can('viewConsole')) return
  loading.value = true
  try {
    const response = await listExecutions(params)
    if (!alive || current !== revision || user !== permissions.state.user || !permissions.can('viewConsole')) return
    if (response.contract !== 'EXECUTION_CATALOG_V1' || response.page !== params.page || response.size !== params.size || !Number.isSafeInteger(response.total) || response.total < 0
      || response.items.length > params.size || new Set(response.items.map(e => e.kind + ':' + e.runKey)).size !== response.items.length
      || response.items.some(e => !['WORKFLOW','PLAN','AX','CHAT'].includes(e.kind) || !e.runKey || (e.kind === 'CHAT' && !permissions.can('operateInstances')))) throw new Error('Invalid catalogue response')
    report.value = response; applied = { ...params }
  } catch { if (alive && current === revision && user === permissions.state.user) error.value = '执行列表读取失败，请重新查询。未展示旧账号或其他筛选的记录。' }
  finally { if (current === revision) loading.value = false }
}
function search() {
  const from = fromLocal.value ? new Date(fromLocal.value) : null, until = untilLocal.value ? new Date(untilLocal.value) : null
  if ((from && !Number.isFinite(from.getTime())) || (until && !Number.isFinite(until.getTime())) || (from && until && from >= until)) {
    revision++; report.value = null; loading.value = false; clearChat(); error.value = '请填写有效时间，结束时间应晚于开始时间。'; return
  }
  void load({ ...filters, page: 1, from: from?.toISOString(), until: until?.toISOString() })
}
function reset() { Object.assign(filters, defaults()); fromLocal.value = ''; untilLocal.value = ''; search() }
function changePage(delta: number) {
  if (loading.value || !report.value) return
  const page = report.value.page + delta
  if (page < 1 || page > maxPage.value) return
  void load({ ...applied, page })
}
async function openChat(entry: ExecutionEntry) {
  clearChat()
  if (!alive || !permissions.can('operateInstances') || !report.value?.items.some(e => e === entry) || entry.kind !== 'CHAT' || !entry.agentId || !entry.instanceName) return
  selectedChat.value = entry; chatLoading.value = true
  const current = chatRevision, user = permissions.state.user
  try {
    const receipt = await getData<NonNullable<typeof chatReceipt.value>>(`/agents/${entry.agentId}/substrate/instances/${encodeURIComponent(entry.instanceName)}/chat/${encodeURIComponent(entry.runKey)}`)
    if (!alive || current !== chatRevision || user !== permissions.state.user || !permissions.can('operateInstances')) return
    if (receipt.requestKey !== entry.runKey) throw new Error('Receipt identity mismatch')
    chatReceipt.value = receipt
  } catch { if (alive && current === chatRevision && user === permissions.state.user) chatError.value = '原回执读取失败，请保留原编号核对。此次查询没有重新提交对话。' }
  finally { if (current === chatRevision) chatLoading.value = false }
}
watch([() => permissions.state.user, () => permissions.can('viewConsole'), () => permissions.can('operateInstances')], reset, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { alive = false; revision++; report.value = null; loading.value = false; clearChat() })
</script>
<style scoped>
.execution-catalog{display:grid;gap:16px;min-width:0}.catalog-filters{display:flex;flex-wrap:wrap;align-items:end;gap:12px;padding:18px;background:white;border:1px solid #dfe7ee;border-radius:8px}.catalog-filters label{display:grid;gap:7px;font-size:13px;color:#475569}.catalog-search{flex:1;min-width:220px}.catalog-filters select,.catalog-filters input:not([type=checkbox]){width:100%;min-width:0;box-sizing:border-box;padding:9px;border:1px solid #cbd5e1;border-radius:5px;background:white;color:#334155}.catalog-filters .mine-filter{display:flex;align-items:center;padding:9px 0}.execution-catalog button{padding:9px 12px;border:1px solid #cbd5e1;border-radius:5px;background:white;cursor:pointer;color:#334155}.execution-catalog button:disabled{opacity:.5;cursor:default}.catalog-scope{font-size:12px;line-height:1.7;color:#64748b;margin:0}.catalog-list{list-style:none;margin:0;padding:0;background:white;border:1px solid #dfe7ee;border-radius:8px}.catalog-list li{display:grid;grid-template-columns:minmax(0,1fr) auto 175px;gap:12px;padding:18px;border-bottom:1px solid #edf1f5;align-items:center}.catalog-list li:last-child{border-bottom:0}.catalog-title{display:flex;align-items:center;gap:12px;min-width:0}.catalog-title a{color:#245e87;font-weight:600;overflow-wrap:anywhere}.kind-label{font-size:12px;color:#64748b;flex-shrink:0}.catalog-time{color:#64748b;font-size:12px}.catalog-identity{grid-column:1/-1;font-size:12px;color:#64748b;overflow-wrap:anywhere}.catalog-identity summary{cursor:pointer}.catalog-identity p{margin:6px 0}.catalog-pagination{display:flex;justify-content:flex-end;align-items:center;gap:12px;padding:16px 0;font-size:13px}.catalog-pagination span{margin-right:auto}.catalog-empty{padding:35px;text-align:center;background:white;color:#64748b}.chat-receipt{padding:18px;border:1px solid #dfe7ee;border-radius:8px;background:white;overflow-wrap:anywhere}.receipt-heading{display:flex;align-items:center;justify-content:space-between}.receipt-heading h3{margin:0}.catalog-title .chat-link{border:0;padding:0;color:#245e87;text-align:left;font-weight:600}.execution-catalog :is(button,input,select,summary,a):focus-visible{outline:2px solid #287eac;outline-offset:3px}@media(max-width:700px){.catalog-list li{grid-template-columns:minmax(0,1fr) auto}.catalog-title{flex-direction:column;align-items:flex-start;gap:6px}.catalog-time{grid-column:1/-1}.catalog-filters label{flex:1;min-width:130px}.catalog-filters .catalog-search{flex-basis:100%}.catalog-pagination{flex-wrap:wrap}.catalog-pagination span{flex-basis:100%}}
</style>
