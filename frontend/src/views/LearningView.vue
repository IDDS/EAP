<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <strong>反馈闭环</strong>
        <span>沉淀质量反馈、知识缺口和改进动作</span>
      </div>
      <el-button type="primary" :icon="Plus" @click="createFeedback">新增反馈</el-button>
    </div>

    <el-card shadow="never" class="panel">
      <el-table :data="feedbacks" v-loading="loading">
        <el-table-column prop="feedbackType" label="反馈类型" width="150" />
        <el-table-column label="评分" width="180">
          <template #default="{ row }">
            <el-rate :model-value="row.rating" disabled />
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="问题摘要" min-width="260" show-overflow-tooltip />
        <el-table-column prop="improvementAction" label="改进动作" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag>{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button text @click="setStatus(row.id, 'REVIEWING')">评审</el-button>
            <el-button text @click="setStatus(row.id, 'ADOPTED')">采纳</el-button>
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
import type { LearningFeedback, LearningFeedbackStatus } from '@/types/domain'

const feedbacks = ref<LearningFeedback[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    feedbacks.value = await EapApi.listLearningFeedbacks()
  } finally {
    loading.value = false
  }
}

async function createFeedback() {
  await EapApi.createLearningFeedback({
    agentId: 1,
    feedbackType: 'QUALITY',
    rating: 4,
    summary: '回答质量稳定，但需要补充责任边界说明。',
    improvementAction: '在系统提示词中增加责任边界和引用要求。',
  })
  ElMessage.success('反馈已记录')
  await load()
}

async function setStatus(id: number, status: LearningFeedbackStatus) {
  await EapApi.updateLearningFeedbackStatus(id, status)
  await load()
}

onMounted(load)
</script>

