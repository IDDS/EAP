<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>运行任务</strong>
        <span>跟踪智能体任务状态、延迟、成本和执行结果</span>
      </div>
      <el-button type="primary" :icon="Plus" @click="createDemoTask">创建模拟任务</el-button>
    </div>

    <el-card shadow="never" class="panel">
      <el-table :data="tasks" v-loading="loading">
        <el-table-column prop="taskType" label="任务类型" width="180" />
        <el-table-column prop="inputSummary" label="输入摘要" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="taskStatusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="延迟 ms" width="110" />
        <el-table-column prop="tokenCost" label="Token" width="110" />
        <el-table-column prop="resultSummary" label="结果" min-width="240" show-overflow-tooltip />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button text @click="updateStatus(row.id, 'RUNNING')">运行</el-button>
            <el-button text @click="updateStatus(row.id, 'SUCCEEDED')">完成</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { AgentTask, AgentTaskStatus } from '@/types/domain'

const tasks = ref<AgentTask[]>([])
const loading = ref(false)

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
  })
  ElMessage.success('任务已创建')
  await load()
}

async function updateStatus(id: number, status: AgentTaskStatus) {
  await EapApi.updateTaskStatus(id, status, status === 'SUCCEEDED' ? '任务已完成并写入审计链路' : undefined)
  await load()
}

function taskStatusType(status: AgentTaskStatus) {
  return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'danger' : status === 'RUNNING' ? 'warning' : 'info'
}

onMounted(load)
</script>

