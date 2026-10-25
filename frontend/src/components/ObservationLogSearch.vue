<template>
  <el-card class="operation-log-search" shadow="never">
    <template #header><strong>原操作日志检索</strong></template>
    <p class="log-note">检索持久固定事件文本，可按 Trace、入口、事件或原因码搜索，空格表示同时匹配。命中后打开原操作核对执行、告警与费用。</p>
    <form class="log-form" @submit.prevent="search">
      <el-input v-model="query" aria-label="日志检索词" placeholder="例如 CHAT OUTCOME_UNCONFIRMED" :maxlength="128" clearable />
      <el-button native-type="submit" type="primary" :loading="loading" :disabled="!permissions.can('viewConsole')">检索日志</el-button>
    </form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="data">
      <p class="log-note">事件窗口 {{ time(data.windowStartMs) }} — {{ time(data.observedMs) }} · 已检查 {{ data.candidatesScanned }} 条事件。查询只读，日志数量不作为原操作分母。</p>
      <p v-if="data.scopeTruncated" class="log-warning" role="status">窗口内事件超过 {{ data.candidateLimit }} 条，只检查最近部分；未命中不表示整个窗口没有匹配，请缩短窗口。</p>
      <p v-if="data.matchesTruncated" class="log-warning" role="status">匹配超过 {{ data.limit }} 条，只显示最近部分，请增加检索词。</p>
      <p v-if="!data.logs.length" class="log-note">已检查的事件中没有匹配；这不代表业务成功或观测齐全。</p>
      <ol v-else class="log-results">
        <li v-for="log in data.logs" :key="`${log.operationId}:${log.eventKind}`">
          <div><el-button link type="primary" @click="select(log.operationId)">打开原操作 #{{ log.operationId }}</el-button><time>{{ time(log.createdMs) }}</time></div>
          <code>{{ log.text }}</code>
        </li>
      </ol>
    </template>
    <p class="log-note">文本由原事件和当前登记的 Trace 元数据组成；原始 stdout、任意业务正文日志和旧未登记入口尚未接入。</p>
  </el-card>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { searchObservationLogs, type ObservationLogSearch } from '@/api/observationLogs'
import { usePermissions } from '@/stores/permissions'
const props = defineProps<{ minutes: number }>()
const emit = defineEmits<{ select: [id: number] }>()
const permissions = usePermissions()
const query = ref(''), data = ref<ObservationLogSearch | null>(null), loading = ref(false), error = ref('')
let generation = 0, alive = true
function clear() { generation++; data.value = null; loading.value = false; error.value = '' }
function current(run: number, user: unknown, minutes: number, text: string) {
  return alive && run === generation && user === permissions.state.user && minutes === props.minutes && text === query.value.trim() && permissions.can('viewConsole')
}
async function search() {
  if (!alive || !permissions.can('viewConsole')) { clear(); return }
  const run = ++generation, user = permissions.state.user, minutes = props.minutes, text = query.value.trim()
  data.value = null; error.value = ''; loading.value = true
  try {
    const result = await searchObservationLogs(text, minutes)
    if (!current(run, user, minutes, text)) return
    if (result.sourceContract !== 'PERSISTED_OPERATION_EVENT_TEXT_V1' || result.windowMinutes !== minutes) throw new Error('log scope mismatch')
    data.value = result
  } catch { if (current(run, user, minutes, text)) { data.value = null; error.value = '原操作日志读取失败，请核对检索词和窗口后重试' } }
  finally { if (current(run, user, minutes, text)) loading.value = false }
}
function select(id: number) {
  if (alive && permissions.can('viewConsole') && data.value?.logs.some(log => log.operationId === id)) emit('select', id)
}
function time(value: number) { return new Date(value).toLocaleString('zh-CN') }
watch([() => props.minutes, query], clear, { flush: 'sync' })
watch([() => permissions.state.user, () => permissions.can('viewConsole')], () => { clear(); query.value = '' }, { flush: 'sync' })
onBeforeUnmount(() => { alive = false; clear(); query.value = '' })
</script>
<style scoped>
.operation-log-search{min-width:0}.log-note{font-size:13px;color:#64748b;line-height:1.7}.log-form{display:flex;gap:12px;margin:14px 0}.log-form .el-input{min-width:0;flex:1}.log-warning{font-size:13px;color:#92400e;line-height:1.7}.log-results{list-style:none;padding:0;display:grid;gap:14px}.log-results li{padding:12px;border:1px solid #e2e8f0;border-radius:8px;min-width:0}.log-results li>div{display:flex;gap:12px;flex-wrap:wrap;align-items:center}.log-results time{font-size:12px;color:#64748b}.log-results code{display:block;margin-top:8px;font-size:12px;line-height:1.8;overflow-wrap:anywhere;white-space:pre-wrap}@media(max-width:600px){.log-form{flex-wrap:wrap}.log-form .el-input{flex-basis:100%}}
</style>
