<template>
  <section class="page-grid operations-page">
    <div class="toolbar">
      <div class="toolbar-title">
        <h2>业务任务记录</h2>
        <span>查看任务进展、阅读结果，并确认业务问题是否解决</span>
      </div>
      <el-button v-if="permissions.can('runWorkflows')" @click="showAdvanced = !showAdvanced" :aria-expanded="showAdvanced">{{ showAdvanced ? '收起管理操作' : '管理操作' }}</el-button>
    </div>

    <el-alert v-if="!permissions.can('runWorkflows')" title="当前账号可查看任务与证据，没有修改任务或登记业务结果的权限。" type="info" :closable="false" show-icon />
    <el-alert v-if="actionError" :title="actionError" type="warning" :closable="false" show-icon />
    <el-card v-if="showAdvanced && permissions.can('runWorkflows')" shadow="never" class="panel">
      <p class="muted">模拟任务用于演示；手动标记只更新任务记录，不会启动或完成真实执行。</p>
      <div class="button-row"><el-input v-model="requester" aria-label="模拟任务的调用方" placeholder="模拟任务的调用方" clearable style="width:205px" /><el-button :icon="Plus" :loading="saving" @click="createDemoTask">创建模拟任务</el-button></div>
    </el-card>
    <el-card shadow="never" class="panel">
      <HistoryTable ref="history" :fetch-page="EapApi.pageTasks" :statuses="['PENDING','RUNNING','SUCCEEDED','FAILED','CANCELLED']" placeholder="搜索任务类型、调用方、输入或结果">
        <el-table-column label="任务" width="140"><template #default="{ row }"><el-button link type="primary" @click="openEvidence(row.id)">任务 #{{ row.id }}</el-button></template></el-table-column>
        <el-table-column prop="requester" label="员工 / 调用方" width="160">
          <template #default="{ row }">{{ row.requester || '未提供' }}</template>
        </el-table-column>
        <el-table-column prop="inputSummary" label="要处理的事情" min-width="260" show-overflow-tooltip />
        <el-table-column label="执行进展" width="130">
          <template #default="{ row }">
            <el-tag :type="taskStatusType(row.status)">{{ executionLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="业务结果" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.problemResolved === true" type="success">已解决</el-tag>
            <el-tag v-else-if="row.problemResolved === false" type="warning">未解决</el-tag>
            <span v-else class="muted">待确认</span>
          </template>
        </el-table-column>
        <el-table-column v-if="showAdvanced" prop="latencyMs" label="延迟 ms" width="110" />
        <el-table-column v-if="showAdvanced" prop="tokenCost" label="Token" width="110" />
        <el-table-column prop="resultSummary" label="结果" min-width="240" show-overflow-tooltip />
        <el-table-column label="下一步" :width="showAdvanced ? 400 : 155" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openEvidence(row.id)">查看结果与过程</el-button>
            <el-button v-if="showAdvanced && permissions.can('runWorkflows') && row.status === 'PENDING'" :disabled="saving" text @click="updateStatus(row.id, 'RUNNING')">标记运行中</el-button>
            <el-button v-if="showAdvanced && permissions.can('runWorkflows') && (row.status === 'PENDING' || row.status === 'RUNNING')" :disabled="saving" text @click="updateStatus(row.id, 'SUCCEEDED')">标记执行完成</el-button>
          </template>
        </el-table-column>
        <template #mobile="{ items, loading, error }">
          <article v-for="row in items" :key="row.id" class="operation-record">
            <header><h3>任务 #{{ row.id }}</h3><el-tag :type="taskStatusType(row.status)">{{ executionLabel(row.status) }}</el-tag></header>
            <p>{{ row.inputSummary || '尚未提供任务说明' }}</p>
            <dl><dt>调用方</dt><dd>{{ row.requester || '未提供' }}</dd><dt>执行结果</dt><dd>{{ row.resultSummary || '尚无结果' }}</dd><dt>业务问题</dt><dd>{{ outcomeLabel(row.problemResolved) }}</dd></dl>
            <div class="button-row">
              <el-button @click="openEvidence(row.id)">查看结果与过程</el-button>
              <el-button v-if="showAdvanced && permissions.can('runWorkflows') && row.status === 'PENDING'" :disabled="saving" @click="updateStatus(row.id, 'RUNNING')">标记运行中</el-button>
              <el-button v-if="showAdvanced && permissions.can('runWorkflows') && ['PENDING','RUNNING'].includes(row.status)" :disabled="saving" @click="updateStatus(row.id, 'SUCCEEDED')">标记执行完成</el-button>
            </div>
          </article>
          <p v-if="!items.length && !loading && !error" class="muted">没有符合当前筛选条件的任务。</p>
        </template>
      </HistoryTable>
    </el-card>
    <TaskEvidenceDrawer ref="taskDetails" @confirm-result="markResolution" v-if="selectedTaskId" :task-id="selectedTaskId" :can-confirm="permissions.can('runWorkflows')" :confirming="saving" @close="closeEvidence" />
  </section>
</template>

<script setup lang="ts">
import { executionLabel, outcomeLabel } from '@/utils/taskPresentation'
import HistoryTable from '@/components/common/HistoryTable.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskEvidenceDrawer from '@/components/trace/TaskEvidenceDrawer.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import type { AgentTask, AgentTaskStatus } from '@/types/domain'

const route = useRoute()
const router = useRouter()
const permissions = usePermissions()
const saving = ref(false)
const actionError = ref('')
let disposed = false
onBeforeUnmount(() => { disposed = true })
watch(() => permissions.state.user, () => { showAdvanced.value = false; actionError.value = '' }, { flush: 'sync' })
const selectedTaskId = computed(() => {
  const id = Number(route.query.taskId)
  return Number.isSafeInteger(id) && id > 0 ? id : undefined
})
function openEvidence(id: number) { void router.push({ query: { ...route.query, taskId: String(id) } }) }
function closeEvidence() { const { taskId, ...query } = route.query; void router.replace({ query }) }

const showAdvanced = ref(false)
const taskDetails = ref<InstanceType<typeof TaskEvidenceDrawer>>()
const history = ref<InstanceType<typeof HistoryTable>>()
const requester = ref('')
async function load() { await history.value?.reload() }

async function createDemoTask() {
  if (disposed || saving.value || !permissions.can('runWorkflows')) return
  saving.value = true; actionError.value = ''
  const user = permissions.state.user
  try {
    await EapApi.createTask({
    agentId: 1,
    taskType: 'GOVERNED_ANALYSIS',
    inputSummary: '在治理策略约束下生成经营分析摘要',
    requester: requester.value.trim() || undefined,
    })
    if (disposed || user !== permissions.state.user) return
    ElMessage.success('任务已创建')
    await load()
  } catch {
    if (!disposed && user === permissions.state.user) actionError.value = '创建结果未确认，请先刷新任务记录核对，避免重复创建。'
  } finally { saving.value = false }
}

async function updateStatus(id: number, status: AgentTaskStatus) {
  if (disposed || saving.value || !permissions.can('runWorkflows')) return
  saving.value = true; actionError.value = ''
  const user = permissions.state.user
  try {
    await EapApi.updateTaskStatus(id, status, status === 'SUCCEEDED' ? '人工标记执行完成；实际执行以关联回执为准' : undefined)
    if (!disposed && user === permissions.state.user) await load()
  } catch {
    if (!disposed && user === permissions.state.user) actionError.value = '更新结果未确认，请刷新原任务核对。'
  } finally { saving.value = false }
}

async function markResolution(task: AgentTask, resolved: boolean) {
  if (disposed || saving.value || !permissions.can('runWorkflows')) return
  saving.value = true; actionError.value = ''
  const user = permissions.state.user
  try {
    let result: { value: string }
    try { result = await ElMessageBox.prompt(
      resolved ? '填写业务问题的解决证据或结果' : '填写尚未解决的原因',
      resolved ? '确认问题已解决' : '记录问题未解决',
      { inputType: 'textarea', inputValue: task.resolutionSummary || '', confirmButtonText: '保存', cancelButtonText: '取消', customClass: 'task-resolution-prompt' },
    ) } catch { return }
    if (disposed || user !== permissions.state.user || !permissions.can('runWorkflows') || selectedTaskId.value !== task.id) return
    await EapApi.updateTaskStatus(task.id, task.status, task.resultSummary, resolved, result.value.trim() || undefined)
    if (disposed || user !== permissions.state.user) return
    ElMessage.success(resolved ? '已记录问题解决结果' : '已记录问题尚未解决')
    await load()
    if (!disposed && user === permissions.state.user) await taskDetails.value?.load()
  } catch {
    if (!disposed && user === permissions.state.user) actionError.value = '业务确认结果未确认，请重新打开原任务核对后再决定是否保存。'
  } finally { saving.value = false }
}

function taskStatusType(status: AgentTaskStatus) {
  return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'danger' : status === 'RUNNING' ? 'warning' : 'info'
}


</script>
