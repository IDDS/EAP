<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>业务任务记录</strong>
        <span>查看任务进展、阅读结果，并确认业务问题是否解决</span>
      </div>
      <el-button @click="showAdvanced = !showAdvanced" :aria-expanded="showAdvanced">{{ showAdvanced ? '收起管理操作' : '管理操作' }}</el-button>
    </div>

    <el-card v-if="showAdvanced" shadow="never" class="panel">
      <p class="muted">模拟任务用于演示；手动标记只更新任务记录，不会启动或完成真实执行。</p>
      <div class="button-row"><el-input v-model="requester" placeholder="模拟任务的调用方" clearable style="width:205px" /><el-button :icon="Plus" @click="createDemoTask">创建模拟任务</el-button></div>
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
            <el-button v-if="showAdvanced && row.status === 'PENDING'" text @click="updateStatus(row.id, 'RUNNING')">标记运行中</el-button>
            <el-button v-if="showAdvanced && (row.status === 'PENDING' || row.status === 'RUNNING')" text @click="updateStatus(row.id, 'SUCCEEDED')">标记执行完成</el-button>
          </template>
        </el-table-column>
      </HistoryTable>
    </el-card>
    <TaskEvidenceDrawer ref="taskDetails" @confirm-result="markResolution" v-if="selectedTaskId" :task-id="selectedTaskId" @close="closeEvidence" />
  </section>
</template>

<script setup lang="ts">
import { executionLabel } from '@/utils/taskPresentation'
import HistoryTable from '@/components/common/HistoryTable.vue'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import TaskEvidenceDrawer from '@/components/trace/TaskEvidenceDrawer.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { AgentTask, AgentTaskStatus } from '@/types/domain'

const route = useRoute()
const router = useRouter()
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
  await EapApi.createTask({
    agentId: 1,
    taskType: 'GOVERNED_ANALYSIS',
    inputSummary: '在治理策略约束下生成经营分析摘要',
    requester: requester.value.trim() || undefined,
  })
  ElMessage.success('任务已创建')
  await load()
}

async function updateStatus(id: number, status: AgentTaskStatus) {
  await EapApi.updateTaskStatus(id, status, status === 'SUCCEEDED' ? '任务已完成并写入审计链路' : undefined)
  await load()
}

async function markResolution(task: AgentTask, resolved: boolean) {
  let resolutionSummary: string | undefined
  try {
    const result = await ElMessageBox.prompt(
      resolved ? '填写业务问题的解决证据或结果' : '填写尚未解决的原因',
      resolved ? '确认问题已解决' : '记录问题未解决',
      { inputType: 'textarea', inputValue: task.resolutionSummary || '', confirmButtonText: '保存', cancelButtonText: '取消' },
    )
    resolutionSummary = result.value.trim() || undefined
  } catch {
    return
  }
  await EapApi.updateTaskStatus(task.id, task.status, task.resultSummary, resolved, resolutionSummary)
  ElMessage.success(resolved ? '已记录问题解决结果' : '已记录问题尚未解决')
  await load()
  await taskDetails.value?.load()
}

function taskStatusType(status: AgentTaskStatus) {
  return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'danger' : status === 'RUNNING' ? 'warning' : 'info'
}


</script>
