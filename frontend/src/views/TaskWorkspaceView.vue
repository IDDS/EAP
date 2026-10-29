<template>
  <section class="task-workspace" aria-label="执行记录">
    <ConsolePageHeader>
      <template #actions><div class="workspace-links"><router-link v-if="!showCatalog" to="/task-workspace">← 全部执行记录</router-link><router-link v-if="permissions.can('runWorkflows')" to="/workflow-builder">设计与发起工作流</router-link></div></template>
    </ConsolePageHeader>
    <p v-if="!permissions.can('viewConsole')" role="status">请登录具有控制台查看能力的账号。</p>
    <template v-else>
      <ExecutionCatalog v-if="showCatalog" />
      <template v-else>
      <div class="workspace-toolbar">
        <label for="task-workflow">工作流</label>
        <select id="task-workflow" :value="workflowId || ''" :disabled="loadingCatalog" @change="chooseWorkflow(($event.target as HTMLSelectElement).value)">
          <option value="" disabled>选择工作流</option>
          <option v-for="workflow in workflows" :key="workflow.id" :value="workflow.id">{{ workflow.name }} · #{{ workflow.id }}</option>
        </select>
        <el-button :loading="loadingCatalog || loadingRuns" @click="reload">刷新记录</el-button>
      </div>
      <el-alert v-if="catalogError" :title="catalogError" type="error" :closable="false" />
      <p class="scope-note">侧栏展示本工作流最近 20 次执行。可返回全部执行记录筛选和翻页，或按原执行编号直接查询。</p>
      <div class="workspace-layout">
        <aside class="run-list" aria-label="执行记录">
          <h3>执行记录 <span>{{ runs.length }}</span></h3>
          <form @submit.prevent="findRun">
            <label for="task-run-key">按原执行编号查询</label>
            <div class="run-search"><input id="task-run-key" v-model="lookupKey" :maxlength="128" autocomplete="off" placeholder="wf-exec-…" /><button type="submit" :disabled="!workflowId || !lookupKey.trim() || loadingRun">查询</button></div>
          </form>
          <el-alert v-if="runsError" :title="runsError" type="error" :closable="false" />
          <p v-if="loadingRuns" role="status">正在读取记录…</p>
          <p v-else-if="!runs.length && !runsError" class="empty-note">{{ workflowId ? '暂无真实执行记录' : '请选择工作流' }}</p>
          <ul>
            <li v-for="run in runs" :key="run.runKey"><button type="button" :class="{ selected: run.runKey === runKey }" :aria-current="run.runKey === runKey ? 'true' : undefined" @click="chooseRun(run.runKey)">
              <span class="run-title">{{ run.objective }}</span><span class="run-status" :class="tone(run.status)">{{ statusLabel(run.status) }}</span><time>{{ time(run.createdAt) }}</time><small>{{ run.runKey }}</small>
            </button></li>
          </ul>
        </aside>
        <main class="run-main" aria-label="本次执行">
          <p v-if="loadingRun" role="status">正在读取本次执行…</p>
          <el-alert v-if="runError" :title="runError" type="error" :closable="false" />
          <template v-if="execution">
            <header class="run-heading"><span class="eyebrow">本次执行</span><h3>{{ execution.objective }}</h3><el-tag :type="tone(execution.status)">{{ statusLabel(execution.status) }}</el-tag><p>{{ execution.reason || '执行状态以平台保存的原回执为准。' }}</p></header>
            <el-alert v-if="['UNKNOWN', 'EXECUTION_UNKNOWN'].includes(execution.status)" title="原执行结果尚未确认。请先核对原任务与工具效果；刷新和查询不会重新执行任务。" type="warning" :closable="false" show-icon />
            <PlanOutputs :key="`${execution.workflowId}:${execution.runKey}`" :workflow-id="execution.workflowId" :run-key="execution.runKey" :node-labels="nodeLabels" />
            <ExecutionNextStep :execution="execution" />
            <ExecutionTimeline :execution="execution" />
            <details class="execution-advanced"><summary>配置与版本溯源</summary><ExecutionConfiguration :key="`${execution.workflowId}:${execution.runKey}`" :execution="execution" /></details>
          </template>
          <div v-else-if="!loadingRun && !runError" class="workspace-empty"><h3>选择一次执行</h3><p>这里将显示实际步骤和保存的结果。</p></div>
        </main>
        <aside v-if="execution" class="run-context" aria-label="执行上下文与证据">
          <details open><summary>执行上下文</summary><dl><dt>原执行编号</dt><dd>{{ execution.runKey }}</dd><dt>工作流</dt><dd>#{{ execution.workflowId }}</dd><dt>申请人</dt><dd>{{ execution.requester }}</dd><dt>创建时间</dt><dd>{{ time(execution.createdAt) }}</dd><dt>开始时间</dt><dd>{{ time(execution.startedAt) }}</dd><dt>结束时间</dt><dd>{{ time(execution.completedAt) }}</dd></dl><p>实例与策略决定见实际步骤。当前工作流配置可能已更新，本页保留原执行记录。</p></details>
          <WorkflowEvidence :key="`${execution.workflowId}:${execution.runKey}`" :execution="execution" />
        </aside>
      </div>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import type { WorkflowDefinition, WorkflowExecution } from '@/types/domain'
import ExecutionTimeline from '@/components/workflow/ExecutionTimeline.vue'
import ExecutionConfiguration from '@/components/workflow/ExecutionConfiguration.vue'
import PlanOutputs from '@/components/workflow/PlanOutputs.vue'
import WorkflowEvidence from '@/components/workflow/WorkflowEvidence.vue'
import ExecutionCatalog from '@/components/workflow/ExecutionCatalog.vue'
import ExecutionNextStep from '@/components/workflow/ExecutionNextStep.vue'
import { executionStatusLabel as statusLabel, executionStatusTone as tone } from '@/api/taskWorkspace'

const permissions = usePermissions(), route = useRoute(), router = useRouter()
const showCatalog = computed(() => route.query.workflowId === undefined && route.query.runKey === undefined)
const workflows = ref<WorkflowDefinition[]>([]), runs = ref<WorkflowExecution[]>([]), execution = ref<WorkflowExecution | null>(null)
const workflowId = ref<number>(), runKey = ref(''), lookupKey = ref('')
const loadingCatalog = ref(false), loadingRuns = ref(false), loadingRun = ref(false)
const catalogError = ref(''), runsError = ref(''), runError = ref('')
const nodeLabels = computed(() => Object.fromEntries((execution.value?.steps || []).map(step => [step.nodeKey, step.label])))
let catalogRevision = 0, runsRevision = 0, runRevision = 0, alive = true
function allowed() { return alive && permissions.can('viewConsole') }
function clearRun() { runRevision++; execution.value = null; runError.value = ''; loadingRun.value = false }
function clearRuns() { runsRevision++; runs.value = []; runsError.value = ''; loadingRuns.value = false; clearRun() }
function clearAll() { catalogRevision++; workflows.value = []; catalogError.value = ''; loadingCatalog.value = false; workflowId.value = undefined; runKey.value = ''; lookupKey.value = ''; clearRuns() }
function time(value?: string) { return value ? new Date(value).toLocaleString('zh-CN') : '尚未记录' }
function routeId() { const value = route.query.workflowId; return typeof value === 'string' && /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value)) ? Number(value) : undefined }
function routeKey() { const value = route.query.runKey; return typeof value === 'string' && value.length <= 128 ? value : '' }
function navigate(id: number, key = '') {
  if (!allowed()) return
  void router.push({ path: '/task-workspace', query: { workflowId: String(id), ...(key ? { runKey: key } : {}) } })
}
function chooseWorkflow(value: string) { const id = Number(value); if (workflows.value.some(w => w.id === id)) navigate(id) }
function chooseRun(key: string) { if (workflowId.value) navigate(workflowId.value, key) }
function findRun() { chooseRun(lookupKey.value.trim()) }

async function loadRun(id: number, key: string) {
  clearRun()
  if (!allowed() || !key) return
  const revision = runRevision, user = permissions.state.user
  loadingRun.value = true
  try {
    const response = await EapApi.getWorkflowExecution(id, key)
    if (!allowed() || revision !== runRevision || user !== permissions.state.user) return
    if (response.workflowId !== id || response.runKey !== key) throw new Error('Execution identity mismatch')
    execution.value = response
  } catch { if (allowed() && revision === runRevision && user === permissions.state.user) runError.value = '原执行读取失败或不存在，请核对工作流与编号后刷新。当前未展示其他执行代替此记录。' }
  finally { if (revision === runRevision) loadingRun.value = false }
}
async function loadRuns(id: number) {
  const revision = ++runsRevision, user = permissions.state.user
  runs.value = []; runsError.value = ''; loadingRuns.value = true
  try {
    const response = await EapApi.listWorkflowExecutions(id)
    if (!allowed() || revision !== runsRevision || user !== permissions.state.user) return
    if (response.some(run => run.workflowId !== id)) throw new Error('Workflow identity mismatch')
    runs.value = response
    if (!runKey.value && response[0]) navigate(id, response[0].runKey)
  } catch { if (allowed() && revision === runsRevision && user === permissions.state.user) runsError.value = '最近执行列表读取失败，请刷新重试。' }
  finally { if (revision === runsRevision) loadingRuns.value = false }
}
function followRoute(force = false) {
  if (!allowed() || loadingCatalog.value) return
  const id = routeId(), key = routeKey()
  if (!id || !workflows.value.some(w => w.id === id)) {
    clearRuns(); workflowId.value = undefined; runKey.value = ''; lookupKey.value = ''
    if (route.query.workflowId !== undefined) runsError.value = '该工作流不在当前可见目录中，请重新选择。'
    return
  }
  const changedWorkflow = id !== workflowId.value, changedRun = key !== runKey.value
  if (changedWorkflow || force) clearRuns()
  workflowId.value = id; runKey.value = key; lookupKey.value = key
  if (route.query.runKey !== undefined && (!key.trim() || typeof route.query.runKey !== 'string' || route.query.runKey.length > 128)) {
    clearRuns(); runError.value = '原执行编号无效，请重新输入；未自动选择其他执行。'; return
  }
  if (changedWorkflow || force) void loadRuns(id)
  if (changedWorkflow || changedRun || force) void loadRun(id, key)
}
async function reload() {
  clearAll()
  if (!allowed() || showCatalog.value) return
  const revision = catalogRevision, user = permissions.state.user
  loadingCatalog.value = true
  try {
    const response = await EapApi.listWorkflows()
    if (!allowed() || revision !== catalogRevision || user !== permissions.state.user) return
    workflows.value = response
    if (!response.length) catalogError.value = '当前没有可见工作流。可先从工作流编排或团队计划创建。'
  } catch { if (allowed() && revision === catalogRevision && user === permissions.state.user) catalogError.value = '工作流目录读取失败，请刷新重试。' }
  finally { if (revision === catalogRevision) { loadingCatalog.value = false; followRoute(true) } }
}
watch([() => route.query.workflowId, () => route.query.runKey], () => { if (showCatalog.value || !workflows.value.length) void reload(); else followRoute() }, { flush: 'sync' })
watch([() => permissions.state.user, () => permissions.can('viewConsole')], () => { void reload() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { alive = false; clearAll() })
</script>

<style scoped>
.workspace-links{display:flex;flex-wrap:wrap;gap:16px}.execution-advanced{margin-top:18px}.execution-advanced>summary{font-size:14px;font-weight:600;padding:14px 0;cursor:pointer}
.task-workspace{display:grid;grid-template-columns:minmax(0,1fr);gap:18px;min-width:0}.workspace-heading{display:flex;justify-content:space-between;gap:20px;align-items:center}.workspace-heading h2{font-size:28px;margin:6px 0 10px}.workspace-heading p,.scope-note,.empty-note,.workspace-empty p{color:#64748b;font-size:13px;line-height:1.7;margin:0}.eyebrow{color:#64748b;font-size:12px}.workspace-heading a{font-size:14px;white-space:nowrap}.workspace-toolbar{display:flex;align-items:center;gap:12px;min-width:0}.workspace-toolbar label{font-size:14px;flex-shrink:0}.workspace-toolbar select{min-width:0;max-width:540px;flex:1;padding:10px;border:1px solid #cbd5e1;border-radius:6px;background:white;color:#334155}.workspace-layout{display:grid;grid-template-columns:220px minmax(0,1fr) 280px;border:1px solid #dfe7ee;border-radius:8px;background:white;min-width:0}.run-list{background:#f8fafc;border-right:1px solid #dfe7ee;padding:18px 12px;min-width:0}.run-list h3{font-size:15px;margin:0 0 18px}.run-list h3 span{font-size:12px;color:#64748b;margin-left:8px}.run-list form label{display:block;font-size:12px;color:#64748b;margin-bottom:8px}.run-search{display:flex;gap:6px;margin-bottom:18px}.run-search input{min-width:0;width:100%;padding:9px;border:1px solid #cbd5e1;border-radius:4px}.run-search button{flex-shrink:0;border:1px solid #cbd5e1;border-radius:4px;background:white;color:#334155;padding:8px;cursor:pointer}.run-search button:disabled{opacity:.5;cursor:default}.run-list ul{list-style:none;margin:0;padding:0;display:grid;gap:8px;max-height:780px;overflow:auto}.run-list li>button{display:grid;gap:8px;text-align:left;width:100%;padding:13px 10px;border:1px solid transparent;background:transparent;border-radius:6px;cursor:pointer;color:#334155}.run-list li>button:hover{background:#eef3f8}.run-list li>button.selected{background:white;border-color:#8aa9c1;box-shadow:inset 3px 0 #287eac}.run-title{font-size:14px;font-weight:600;line-height:1.6;overflow-wrap:anywhere}.run-list time,.run-list small{font-size:11px;color:#64748b;overflow-wrap:anywhere}.run-status{font-size:12px;color:#475569}.run-status.warning{color:#93600f}.run-status.danger{color:#af3636}.run-status.success{color:#287653}.run-main{padding:24px;min-width:0}.run-heading h3{font-size:22px;line-height:1.5;margin:6px 0 12px;overflow-wrap:anywhere}.run-heading p{font-size:13px;line-height:1.7;color:#64748b;overflow-wrap:anywhere}.run-context{padding:20px 16px;border-left:1px solid #dfe7ee;min-width:0}.run-context summary{cursor:pointer;font-size:15px;font-weight:600;padding:4px 0 12px}.run-context dl{display:grid;gap:6px;margin:8px 0 14px;font-size:12px}.run-context dt{color:#64748b;margin-top:8px}.run-context dd{margin:0;overflow-wrap:anywhere;line-height:1.6}.run-context p{font-size:12px;color:#64748b;line-height:1.7}.workspace-empty{padding:80px 12px;text-align:center}.workspace-empty h3{font-size:20px;color:#475569}.task-workspace button:focus-visible,.task-workspace select:focus-visible,.task-workspace input:focus-visible,.task-workspace summary:focus-visible{outline:2px solid #287eac;outline-offset:2px}
@media(max-width:1250px){.workspace-layout{grid-template-columns:210px minmax(0,1fr)}.run-context{grid-column:2;border-left:0;border-top:1px solid #dfe7ee}.run-list{grid-row:1 / span 2}.run-main{padding:20px}}
@media(max-width:700px){.workspace-heading{align-items:flex-start;flex-direction:column;gap:12px}.workspace-heading h2{font-size:24px}.workspace-toolbar{flex-wrap:wrap}.workspace-toolbar select{max-width:100%}.workspace-layout{display:flex;flex-direction:column}.run-list{border-right:0;border-bottom:1px solid #dfe7ee}.run-list ul{max-height:210px}.run-list li>button{grid-template-columns:minmax(0,1fr) auto}.run-list small{grid-column:1 / -1}.run-main{padding:18px 14px}.run-heading h3{font-size:20px}.run-context{padding:18px 14px}.workspace-empty{padding:28px 12px}}
</style>
