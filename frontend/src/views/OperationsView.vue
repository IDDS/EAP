<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>运行任务</strong>
        <span>跟踪智能体任务状态、员工使用频率、问题解决和执行结果</span>
      </div>
      <div class="button-row">
        <el-input v-model="requester" placeholder="员工 / 调用方" clearable style="width: 170px" />
        <el-button type="primary" :icon="Plus" @click="createDemoTask">创建模拟任务</el-button>
      </div>
    </div>

    <el-card shadow="never" class="panel">
      <el-table :data="tasks" v-loading="loading">
        <el-table-column prop="taskType" label="任务类型" width="180" />
        <el-table-column prop="requester" label="员工 / 调用方" width="160">
          <template #default="{ row }">{{ row.requester || '未采集' }}</template>
        </el-table-column>
        <el-table-column prop="inputSummary" label="输入摘要" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="taskStatusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="问题解决" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.problemResolved === true" type="success">已解决</el-tag>
            <el-tag v-else-if="row.problemResolved === false" type="warning">未解决</el-tag>
            <span v-else class="muted">未判断</span>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="延迟 ms" width="110" />
        <el-table-column prop="tokenCost" label="Token" width="110" />
        <el-table-column prop="resultSummary" label="结果" min-width="240" show-overflow-tooltip />
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button text @click="updateStatus(row.id, 'RUNNING')">运行</el-button>
            <el-button text @click="updateStatus(row.id, 'SUCCEEDED')">完成</el-button>
            <el-button text type="success" @click="markResolution(row, true)">已解决</el-button>
            <el-button text type="warning" @click="markResolution(row, false)">未解决</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { AgentTask, AgentTaskStatus } from '@/types/domain'

const tasks = ref<AgentTask[]>([])
const loading = ref(false)
const requester = ref('')

async function load() {
  loading.value = true
  try {
    tasks.value = await EapApi.listTasks()
  } finally {
    loading.value = false
  }
}

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
}

function taskStatusType(status: AgentTaskStatus) {
  return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'danger' : status === 'RUNNING' ? 'warning' : 'info'
}

onMounted(load)
</script>
