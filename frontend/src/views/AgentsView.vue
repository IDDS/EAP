<template>
  <section class="page-grid agent-catalog-page">
    <div class="agent-catalog-heading">
      <div>
        <span class="interface-eyebrow">AGENT CONTROL PLANE · ASSET DIRECTORY</span>
        <h2>智能体管理</h2>
        <p>以智能体为中心，统一查看身份、运行、模型、工具、知识与治理状态。</p>
      </div>
      <div class="button-row">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新数据</el-button>
        <el-button type="primary" :icon="Plus" @click="dialogVisible = true">新建智能体</el-button>
      </div>
    </div>

    <div class="agent-catalog-summary">
      <div class="catalog-summary-item"><span>纳管智能体</span><strong>{{ agents.length }}</strong><small>统一身份目录</small></div>
      <div class="catalog-summary-item"><span>运行中</span><strong class="is-success">{{ activeCount }}</strong><small>当前生命周期为已启用</small></div>
      <div class="catalog-summary-item"><span>风险关注</span><strong class="is-danger">{{ riskCount }}</strong><small>高风险或关键风险</small></div>
      <div class="catalog-summary-item"><span>已观测</span><strong>{{ observedAgentCount }}</strong><small>最近窗口存在模型调用</small></div>
    </div>

    <el-card shadow="never" class="panel agent-catalog-panel">
      <div class="agent-catalog-toolbar">
        <el-tabs v-model="activeFilter" class="agent-filter-tabs">
          <el-tab-pane label="全部智能体" name="all" />
          <el-tab-pane label="运行中" name="active" />
          <el-tab-pane label="风险关注" name="risk" />
          <el-tab-pane label="待处理" name="pending" />
        </el-tabs>
        <div class="agent-catalog-controls">
          <el-input v-model="keyword" class="agent-search" placeholder="搜索名称、编码、部门或责任人" clearable :prefix-icon="Search" />
          <el-select v-model="viewMode" class="agent-view-select" aria-label="视图模式">
            <el-option label="卡片视图" value="cards" />
            <el-option label="列表视图" value="table" />
          </el-select>
        </div>
      </div>

      <div v-if="viewMode === 'cards'" v-loading="loading" class="agent-card-grid">
        <button v-for="agent in pagedAgents" :key="agent.id" class="agent-directory-card" @click="openAgent(agent.id)">
          <div class="agent-directory-card-head">
            <div class="agent-avatar" :class="`agent-avatar-${agent.riskLevel.toLowerCase()}`">{{ initials(agent.name) }}</div>
            <div class="agent-directory-identity">
              <strong>{{ agent.name }}</strong>
              <span>{{ agent.code }}</span>
            </div>
            <el-tag size="small" :type="statusType(agent.lifecycleStatus)" effect="light">{{ statusText(agent.lifecycleStatus) }}</el-tag>
          </div>
          <p class="agent-directory-description">{{ agent.description || '尚未填写业务说明' }}</p>
          <div class="agent-directory-meta">
            <span><el-icon><OfficeBuilding /></el-icon>{{ agent.ownerDepartment || '未分配部门' }}</span>
            <span><el-icon><User /></el-icon>{{ agent.ownerUser || '未分配责任人' }}</span>
            <span><el-icon><CollectionTag /></el-icon>{{ agent.category || '未分类' }}</span>
          </div>
          <div class="agent-directory-footer">
            <span class="risk-caption"><i :class="['risk-dot', `risk-dot-${agent.riskLevel.toLowerCase()}`]"></i>{{ riskText(agent.riskLevel) }}风险</span>
            <span class="agent-card-link">查看智能体 <el-icon><ArrowRight /></el-icon></span>
          </div>
        </button>
        <el-empty v-if="!loading && pagedAgents.length === 0" description="没有符合条件的智能体" />
      </div>

      <el-table v-else v-loading="loading" :data="pagedAgents" class="agent-directory-table" row-key="id" @row-click="openAgentRow">
        <el-table-column label="智能体" min-width="230">
          <template #default="{ row }">
            <div class="table-agent-identity"><div class="agent-avatar agent-avatar-mini">{{ initials(row.name) }}</div><div><strong>{{ row.name }}</strong><span>{{ row.code }}</span></div></div>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="业务类别" min-width="120" />
        <el-table-column prop="ownerDepartment" label="责任部门" min-width="140" />
        <el-table-column label="生命周期" width="125"><template #default="{ row }"><el-tag :type="statusType(row.lifecycleStatus)">{{ statusText(row.lifecycleStatus) }}</el-tag></template></el-table-column>
        <el-table-column label="风险" width="110"><template #default="{ row }"><el-tag :type="riskType(row.riskLevel)" effect="plain">{{ riskText(row.riskLevel) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="125" fixed="right"><template #default="{ row }"><el-button text type="primary" @click.stop="openAgent(row.id)">查看详情</el-button></template></el-table-column>
      </el-table>

      <div class="agent-directory-pagination">
        <span>共 {{ filteredAgents.length }} 个智能体</span>
        <el-pagination v-model:current-page="currentPage" v-model:page-size="pageSize" layout="prev, pager, next" :total="filteredAgents.length" :page-sizes="[6, 12, 24]" @size-change="currentPage = 1" />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="新建智能体" width="560px">
      <el-form label-position="top">
        <el-form-item label="编码"><el-input v-model="form.code" placeholder="例如 FIN-ANALYST" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="业务说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
        <div class="form-row"><el-form-item label="责任部门"><el-input v-model="form.ownerDepartment" /></el-form-item><el-form-item label="责任人"><el-input v-model="form.ownerUser" /></el-form-item></div>
        <div class="form-row"><el-form-item label="业务类别"><el-input v-model="form.category" /></el-form-item><el-form-item label="风险等级"><el-select v-model="form.riskLevel"><el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option label="高" value="HIGH" /><el-option label="关键" value="CRITICAL" /></el-select></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" @click="createAgent">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, CollectionTag, OfficeBuilding, Plus, Refresh, Search, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { EapApi } from '@/api/eap'
import type { Agent, AgentLifecycleStatus, AgentRequest, ModelInvocationEvent, RiskLevel } from '@/types/domain'

const router = useRouter()
const agents = ref<Agent[]>([])
const modelEvents = ref<ModelInvocationEvent[]>([])
const loading = ref(false)
const keyword = ref('')
const activeFilter = ref<'all' | 'active' | 'risk' | 'pending'>('all')
const viewMode = ref<'cards' | 'table'>('cards')
const currentPage = ref(1)
const pageSize = ref(6)
const dialogVisible = ref(false)
const form = reactive<AgentRequest>({ code: '', name: '', description: '', ownerDepartment: '', ownerUser: '', category: '', riskLevel: 'LOW' })

const activeCount = computed(() => agents.value.filter(agent => agent.lifecycleStatus === 'ACTIVE').length)
const riskCount = computed(() => agents.value.filter(agent => ['HIGH', 'CRITICAL'].includes(agent.riskLevel)).length)
const observedAgentCount = computed(() => new Set(modelEvents.value.map(event => event.agentId).filter(Boolean)).size)
const filteredAgents = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  return agents.value.filter(agent => {
    const matchesTerm = !term || [agent.name, agent.code, agent.ownerUser, agent.ownerDepartment, agent.category].some(item => (item || '').toLowerCase().includes(term))
    const matchesFilter = activeFilter.value === 'all' || (activeFilter.value === 'active' && agent.lifecycleStatus === 'ACTIVE') || (activeFilter.value === 'risk' && ['HIGH', 'CRITICAL'].includes(agent.riskLevel)) || (activeFilter.value === 'pending' && ['DRAFT', 'REVIEWING'].includes(agent.lifecycleStatus))
    return matchesTerm && matchesFilter
  })
})
const pagedAgents = computed(() => filteredAgents.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value))

watch([keyword, activeFilter, pageSize], () => { currentPage.value = 1 })

function openAgent(id: number) { router.push(`/agents/${id}`) }
function openAgentRow(row: Agent) { openAgent(row.id) }
function initials(name: string) { return Array.from(name || 'A').slice(0, 2).join('').toUpperCase() }
function statusText(status: AgentLifecycleStatus) { return { DRAFT: '草稿', REVIEWING: '审核中', ACTIVE: '运行中', PAUSED: '已暂停', RETIRED: '已下线' }[status] }
function statusType(status: AgentLifecycleStatus) { return status === 'ACTIVE' ? 'success' : status === 'PAUSED' ? 'warning' : status === 'RETIRED' ? 'info' : 'primary' }
function riskText(risk: RiskLevel) { return { LOW: '低', MEDIUM: '中', HIGH: '高', CRITICAL: '关键' }[risk] }
function riskType(risk: RiskLevel) { return risk === 'LOW' ? 'success' : risk === 'MEDIUM' ? 'warning' : 'danger' }

async function load() {
  loading.value = true
  try {
    const [nextAgents, nextEvents] = await Promise.all([EapApi.listAgents(), EapApi.listModelInvocationEvents({ limit: 500 })])
    agents.value = nextAgents
    modelEvents.value = nextEvents
  } finally { loading.value = false }
}

async function createAgent() {
  await EapApi.createAgent({ ...form })
  ElMessage.success('智能体已创建')
  dialogVisible.value = false
  Object.assign(form, { code: '', name: '', description: '', ownerDepartment: '', ownerUser: '', category: '', riskLevel: 'LOW' })
  await load()
}

onMounted(load)
</script>
