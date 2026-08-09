<template>
  <section class="page-grid">
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索智能体名称、编码、责任人" clearable />
      <el-button type="primary" :icon="Plus" @click="dialogVisible = true">新建智能体</el-button>
    </div>

    <el-card shadow="never" class="panel">
      <el-table :data="filteredAgents" v-loading="loading">
        <el-table-column prop="name" label="智能体" min-width="180">
          <template #default="{ row }">
            <strong>{{ row.name }}</strong>
            <div class="muted">{{ row.code }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="业务类别" width="140" />
        <el-table-column prop="ownerDepartment" label="责任部门" width="140" />
        <el-table-column prop="ownerUser" label="责任人" width="120" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusType(row.lifecycleStatus)">{{ statusText(row.lifecycleStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="120">
          <template #default="{ row }">
            <el-tag :type="riskType(row.riskLevel)" effect="plain">{{ riskText(row.riskLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button text @click="changeStatus(row.id, 'ACTIVE')">启用</el-button>
            <el-button text @click="changeStatus(row.id, 'PAUSED')">暂停</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建智能体" width="560px">
      <el-form label-position="top">
        <el-form-item label="编码">
          <el-input v-model="form.code" placeholder="例如 FIN-ANALYST" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="业务说明">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="责任部门">
            <el-input v-model="form.ownerDepartment" />
          </el-form-item>
          <el-form-item label="责任人">
            <el-input v-model="form.ownerUser" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="业务类别">
            <el-input v-model="form.category" />
          </el-form-item>
          <el-form-item label="风险等级">
            <el-select v-model="form.riskLevel">
              <el-option label="低" value="LOW" />
              <el-option label="中" value="MEDIUM" />
              <el-option label="高" value="HIGH" />
              <el-option label="关键" value="CRITICAL" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="createAgent">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { Agent, AgentLifecycleStatus, AgentRequest, RiskLevel } from '@/types/domain'

const agents = ref<Agent[]>([])
const loading = ref(false)
const keyword = ref('')
const dialogVisible = ref(false)
const form = reactive<AgentRequest>({
  code: '',
  name: '',
  description: '',
  ownerDepartment: '',
  ownerUser: '',
  category: '',
  riskLevel: 'LOW',
})

const filteredAgents = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  if (!term) return agents.value
  return agents.value.filter((agent) =>
    [agent.name, agent.code, agent.ownerUser, agent.ownerDepartment].some((item) => item.toLowerCase().includes(term)),
  )
})

async function load() {
  loading.value = true
  try {
    agents.value = await EapApi.listAgents()
  } finally {
    loading.value = false
  }
}

async function createAgent() {
  await EapApi.createAgent({ ...form })
  ElMessage.success('智能体已创建')
  dialogVisible.value = false
  Object.assign(form, { code: '', name: '', description: '', ownerDepartment: '', ownerUser: '', category: '', riskLevel: 'LOW' })
  await load()
}

async function changeStatus(id: number, status: AgentLifecycleStatus) {
  await EapApi.updateAgentStatus(id, status)
  await load()
}

function statusText(status: AgentLifecycleStatus) {
  return { DRAFT: '草稿', REVIEWING: '审核中', ACTIVE: '已启用', PAUSED: '已暂停', RETIRED: '已下线' }[status]
}

function statusType(status: AgentLifecycleStatus) {
  return status === 'ACTIVE' ? 'success' : status === 'PAUSED' ? 'warning' : 'info'
}

function riskText(risk: RiskLevel) {
  return { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '关键' }[risk]
}

function riskType(risk: RiskLevel) {
  return risk === 'LOW' ? 'success' : risk === 'MEDIUM' ? 'warning' : 'danger'
}

onMounted(load)
</script>

