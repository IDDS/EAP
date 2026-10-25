<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { getExecutionGraph } from '@/api/agentExecutions'
import { useExecutionQuery } from './useExecutionQuery'
import { executionGapLabels, executionStateLabels, executionTree } from './executionPresentation'

const props = defineProps<{ streamKey: string }>()
const { data, loading, error, refresh } = useExecutionQuery(() => props.streamKey, getExecutionGraph)
const selectedId = shallowRef('')
const page = shallowRef(0)
const toolPage = shallowRef(0)
const size = 50
const tree = computed(() => executionTree(data.value?.runs ?? []))
const visibleRuns = computed(() => tree.value.slice(page.value * size, (page.value + 1) * size))
const selected = computed(() => data.value?.runs.find(run => run.runId === selectedId.value))
const tools = computed(() => data.value?.tools.filter(tool => tool.runId === selectedId.value) ?? [])
const visibleTools = computed(() => tools.value.slice(toolPage.value * size, (toolPage.value + 1) * size))
const artifacts = computed(() => {
  const digests = new Set(tools.value.map(tool => tool.start?.artifactDigest).filter(Boolean))
  return data.value?.artifacts.filter(artifact => artifact.runId === selectedId.value || digests.has(artifact.artifactDigest)) ?? []
})
const reportedFailures = computed(() => data.value?.runs.filter(run => run.state === 'FAILED').length ?? 0)
watch(data, graph => { selectedId.value = graph?.runs[0]?.runId ?? ''; page.value = 0; toolPage.value = 0 })
watch(selectedId, () => { toolPage.value = 0 })
function selectRun(id: string) {
  const index = tree.value.findIndex(item => item.run.runId === id)
  if (index < 0) return
  selectedId.value = id
  page.value = Math.floor(index / size)
}
function stateLabel(state: string) { return executionStateLabels[state] ?? '未知状态' }
</script>

<template>
  <section class="execution-graph" aria-label="执行关系详情" :aria-busy="loading">
    <div class="graph-heading"><h3>执行关系</h3><el-button :loading="loading" @click="refresh">刷新关系</el-button></div>
    <p class="scope-note">仅显示同一运行实例、身份代际与生产者的关系。这里的状态来自已认证运行实例的报告，业务效果仍需独立核验。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-else-if="loading" role="status">正在读取执行关系…</p>
    <template v-else-if="data">
      <div class="graph-summary" aria-label="执行关系摘要">
        <span><strong>{{ data.runs.length }}</strong> 次 Agent 执行</span>
        <span><strong>{{ data.tools.length }}</strong> 次工具调用</span>
        <span><strong>{{ reportedFailures }}</strong> 次 Agent 报告失败</span>
        <span>{{ data.complete ? '已收到事件无结构缺口' : '事件结构待核对' }}</span>
      </div>
      <p class="scope-note">事件齐全不表示业务成功，也不能排除尚未上报的尾部事件。缺少结束记录的执行不会被推断为仍在运行。</p>
      <div v-if="data.gaps.length" class="gap-panel" role="status">
        <strong>发现 {{ data.gaps.length }} 类证据缺口</strong>
        <ul><li v-for="gap in data.gaps" :key="gap">{{ executionGapLabels[gap] || '未知缺口类型，请核对服务版本' }}</li></ul>
        <p>先检查采集与交付状态；不要因观测缺口重复执行原任务或工具。</p>
      </div>
      <el-empty v-if="!data.runs.length" description="该事件流暂无已接收的执行记录" />
      <div v-else class="graph-workspace">
        <div class="execution-run-list-panel">
          <h4>Agent 父子关系</h4>
          <p class="scope-note">缩进表示已报告的父子关系，层级深度另行标注。</p>
          <ol class="run-list" aria-label="Agent 执行列表">
            <li v-for="item in visibleRuns" :key="item.run.runId" :style="{ marginLeft: `${Math.min(item.depth, 5) * 10}px` }">
              <button class="run-choice" type="button" :aria-pressed="selectedId === item.run.runId" @click="selectRun(item.run.runId)">
                <span class="run-name">{{ item.run.definitionKey || '缺少开始事件' }}</span>
                <span class="run-state" :data-state="item.run.state">{{ stateLabel(item.run.state) }}</span>
                <span class="run-meta">{{ item.run.parentRunId ? `子执行 · 层级 ${item.depth}` : '根执行' }}{{ item.run.missingParent ? ' · 父执行缺失' : '' }}</span>
                <code class="run-id">{{ item.run.runId }}</code>
              </button>
            </li>
          </ol>
          <div v-if="tree.length > size" class="page-actions">
            <el-button :disabled="page === 0" @click="page--">上一组 Agent</el-button><span>{{ page + 1 }} / {{ Math.ceil(tree.length / size) }}</span>
            <el-button :disabled="(page + 1) * size >= tree.length" @click="page++">下一组 Agent</el-button>
          </div>
        </div>
        <section v-if="selected" class="run-detail" aria-label="选中执行详情">
          <h4>{{ selected.definitionKey || '缺少开始事件' }}</h4>
          <dl class="identity"><dt>执行编号</dt><dd><code>{{ selected.runId }}</code></dd><dt>报告状态</dt><dd>{{ stateLabel(selected.state) }}</dd>
            <dt>父执行</dt><dd v-if="!selected.parentRunId">未报告父执行</dd><dd v-else><code>{{ selected.parentRunId }}</code><el-button v-if="!selected.missingParent" link type="primary" @click="selectRun(selected.parentRunId)">定位父执行</el-button><span v-else>（缺少父执行开始事件）</span></dd>
          </dl>
          <h4>工具调用 <span class="count">{{ tools.length }}</span></h4>
          <p v-if="!tools.length" class="scope-note">未收到该执行的工具事件；这不证明没有调用工具。</p>
          <ol v-else class="tool-list">
            <li v-for="tool in visibleTools" :key="tool.toolCallId">
              <div class="tool-heading"><strong>{{ tool.start?.definitionKey || '缺少工具开始事件' }}</strong><span class="run-state" :data-state="tool.state">{{ stateLabel(tool.state) }}</span></div>
              <code class="run-id">{{ tool.toolCallId }}</code>
              <p class="scope-note">开始：{{ tool.start?.occurredAt || '未收到' }}<br>结束：{{ tool.finish?.occurredAt || '未收到' }}</p>
              <p v-if="tool.start?.artifactDigest" class="digest">代码摘要：<code>{{ tool.start.artifactDigest }}</code></p>
              <router-link v-if="tool.start?.traceId || tool.finish?.traceId" :to="{ path: '/physical', query: { traceId: tool.start?.traceId || tool.finish?.traceId } }">查看关联 Trace</router-link>
            </li>
          </ol>
          <div v-if="tools.length > size" class="page-actions"><el-button :disabled="toolPage === 0" @click="toolPage--">上一组工具</el-button><span>{{ toolPage + 1 }} / {{ Math.ceil(tools.length / size) }}</span><el-button :disabled="(toolPage + 1) * size >= tools.length" @click="toolPage++">下一组工具</el-button></div>
          <details class="artifact-details"><summary>代码产物摘要（{{ artifacts.length }} 条）</summary>
            <p class="scope-note">只登记摘要、大小和语言，不保存代码正文；摘要不能恢复代码或证明其执行效果。</p>
            <ul><li v-for="artifact in artifacts.slice(0, size)" :key="artifact.eventId"><code class="digest">{{ artifact.artifactDigest }}</code><span>{{ artifact.language }} · {{ artifact.sizeBytes }} 字节</span></li></ul>
            <p v-if="artifacts.length > size" class="scope-note">仅展示前 {{ size }} 条摘要；完整记录请使用事件查询接口。</p>
          </details>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.execution-graph { min-width: 0; color: var(--console-ink); }
.graph-heading, .graph-summary, .tool-heading, .page-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.graph-heading { justify-content: space-between; }
.graph-heading h3, .execution-run-list-panel h4, .run-detail h4 { margin: 0; font-size: 16px; }
.scope-note { color: var(--console-muted); font-size: 13px; line-height: 1.7; max-width: 80ch; }
.graph-summary { border-block: 1px solid var(--console-line); padding: 14px 0; font-size: 13px; }
.graph-summary strong { font-variant-numeric: tabular-nums; font-size: 20px; margin-right: 4px; }
.gap-panel { border-left: 3px solid #a96913; background: #fff8e9; padding: 12px 16px; font-size: 13px; line-height: 1.7; margin-bottom: 20px; }
.gap-panel p { margin-bottom: 0; }
.graph-workspace { display: grid; grid-template-columns: minmax(260px, .9fr) minmax(0, 1.3fr); gap: 24px; margin-top: 20px; }
.execution-run-list-panel, .run-detail { min-width: 0; }
.run-list, .tool-list { list-style: none; padding: 0; margin: 12px 0; }
.run-list li { border-left: 2px solid var(--console-line); padding: 0 0 8px 8px; }
.run-choice { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 6px 10px; width: 100%; padding: 12px; border: 1px solid var(--console-line); border-radius: 4px; background: var(--console-surface); text-align: left; font: inherit; color: inherit; cursor: pointer; }
.run-choice[aria-pressed='true'] { border-color: var(--console-accent); background: var(--console-accent-soft); }
.run-choice:focus-visible, .artifact-details summary:focus-visible { outline: 2px solid var(--console-accent); outline-offset: 3px; }
.run-name { font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.run-meta, .run-id { font-size: 12px; color: var(--console-muted); grid-column: 1 / -1; overflow-wrap: anywhere; }
.run-id { display: block; }
.run-state { font-size: 12px; color: var(--console-muted); }
.run-state[data-state='FAILED'], .run-state[data-state='INCONSISTENT'] { color: #a62d32; }
.run-state[data-state='INCOMPLETE'] { color: #87510c; }
.run-detail { border-left: 1px solid var(--console-line); padding-left: 24px; }
.identity { display: grid; gap: 8px; font-size: 13px; margin-bottom: 24px; }
.identity dt { color: var(--console-muted); }.identity dd { margin: 0 0 8px; overflow-wrap: anywhere; }
.tool-list li { padding: 14px 0; border-bottom: 1px solid var(--console-line); }.tool-heading strong { font-size: 14px; overflow-wrap: anywhere; }
.tool-list a { font-size: 13px; }.digest { font-size: 12px; overflow-wrap: anywhere; }.count { color: var(--console-muted); font-weight: 400; }
.artifact-details { margin-top: 20px; font-size: 13px; line-height: 1.7; }.artifact-details summary { cursor: pointer; padding: 10px 0; }.artifact-details li { margin-bottom: 12px; }.artifact-details li span { display: block; }
.page-actions { font-size: 12px; margin-top: 16px; }.page-actions .el-button { margin-left: 0; }
@media (max-width: 900px) { .graph-workspace { grid-template-columns: minmax(0, 1fr); }.run-detail { border-left: 0; border-top: 1px solid var(--console-line); padding: 20px 0 0; } }
</style>
