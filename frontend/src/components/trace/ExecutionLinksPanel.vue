<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { getExecutionLinks } from '@/api/executionLinks'
import { useExecutionQuery } from './useExecutionQuery'

const props = defineProps<{ streamKey: string }>()
const emit = defineEmits<{ openStream: [streamKey: string] }>()
const afterId = shallowRef(0)
const cursors = shallowRef<number[]>([])
watch(() => props.streamKey, () => { afterId.value = 0; cursors.value = [] }, { flush: 'sync' })
const key = computed(() => props.streamKey ? JSON.stringify({ stream: props.streamKey, afterId: afterId.value }) : '')
const { data, loading, error, refresh } = useExecutionQuery(() => key.value, (query, signal) => {
  const value = JSON.parse(query)
  return getExecutionLinks(value.stream, value.afterId, signal)
})
const rows = computed(() => data.value?.items.map(row => ({ ...row,
  relationLabel: row.relation === 'CODE_HANDOFF' ? '代码交接' : '执行委派',
  otherStream: row.sourceStream === props.streamKey ? row.targetStream : row.sourceStream,
  otherLabel: row.sourceStream === props.streamKey ? '查看接收方事件' : '查看来源事件',
})) ?? [])
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
  <section class="execution-links" aria-label="跨实例执行关联">
    <header class="links-heading"><h4>跨实例关联</h4><el-button :loading="loading" @click="refresh">刷新关联</el-button></header>
    <p class="links-note">双方以各自实例身份报告同一关联后才标记确认。确认只说明关联声明一致，不授予执行权限，也不证明业务效果或代码安全。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="loading" role="status">正在读取跨实例关联…</p>
    <p v-else-if="data && !rows.length" class="links-note">尚无跨实例关联记录；同名执行、相同 Trace 或相同代码摘要不会自动建立关系。</p>
    <ul v-else-if="data" class="links-list">
      <li v-for="row in rows" :key="row.linkId" class="link-row">
        <div class="links-heading"><strong>{{ row.relationLabel }}</strong><span>{{ row.state === 'CONFIRMED' ? '双方已确认' : '待接收方确认' }}</span></div>
        <dl class="link-parts">
          <div><dt>来源 · 智能体 #{{ row.sourceAgentId }}</dt><dd>{{ row.sourceRuntimeKey }}</dd><dd>执行 {{ row.sourceRun }}</dd></div>
          <div><dt>接收方 · {{ row.targetAgentId ? `智能体 #${row.targetAgentId}` : '身份尚未确认' }}</dt><dd v-if="row.targetRuntimeKey">{{ row.targetRuntimeKey }}</dd><dd>执行 {{ row.targetRun }}</dd></div>
        </dl>
        <p v-if="row.artifactDigest" class="link-code">{{ row.artifactLanguage }} · {{ row.artifactSize }} 字节<br><code>SHA256 {{ row.artifactDigest }}</code><br>仅登记摘要与双方交接声明，未保存源码正文。</p>
        <p v-if="row.state !== 'CONFIRMED'" class="links-note">目前只有来源方声明；不将接收方归入已确认关系。</p>
        <p class="link-meta">关联编号 {{ row.linkId }}</p>
        <el-button v-if="row.state === 'CONFIRMED'" @click="emit('openStream', row.otherStream)">{{ row.otherLabel }}</el-button>
      </li>
    </ul>
    <nav v-if="data" class="links-paging" aria-label="跨实例关联分页"><el-button :disabled="loading || !cursors.length" @click="previous">上一关联页</el-button><span>第 {{ cursors.length + 1 }} 页</span><el-button :disabled="loading || !data.hasMore" @click="next">下一关联页</el-button></nav>
  </section>
</template>

<style scoped>
.execution-links { border-top: 1px solid var(--console-line); margin-top: 24px; min-width: 0; }
.links-heading, .links-paging { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.links-heading h4 { font-size: 16px; }.links-note, .link-code, .link-meta { font-size: 13px; line-height: 1.7; color: var(--console-muted); overflow-wrap: anywhere; }
.links-list { list-style: none; margin: 16px 0; padding: 0; }.link-row { background: var(--console-canvas); border-left: 3px solid var(--console-accent); padding: 16px; margin-bottom: 16px; min-width: 0; }
.link-parts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; font-size: 13px; }.link-parts dt { font-weight: 600; margin-bottom: 8px; }.link-parts dd { margin: 0; line-height: 1.7; overflow-wrap: anywhere; }
.links-paging { justify-content: flex-start; font-size: 13px; }.links-paging .el-button { margin-left: 0; }
@media (max-width: 600px) { .link-parts { grid-template-columns: minmax(0, 1fr); }.links-heading .el-button { min-height: 40px; } }
</style>
