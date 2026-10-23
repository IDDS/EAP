<template>
  <PaginatedList :data="items" :reset-key="resetKey" v-slot="{ rows }">
  <el-table v-if="items.length || loading" v-loading="loading" :data="rows" size="small" class="work-item-table" row-key="id">
    <el-table-column label="级别" width="72">
      <template #default="{ row }"><span class="severity-badge" :class="`severity-${row.severity.toLowerCase()}`">{{ row.severity }}</span></template>
    </el-table-column>
    <el-table-column label="运营事项" min-width="300">
      <template #default="{ row }">
        <button class="work-item-title" type="button" @click="$emit('inspect', row)">
          <strong>{{ row.title }}</strong>
          <span>{{ typeLabel(row.itemType) }} · {{ row.sourceType }}</span>
        </button>
      </template>
    </el-table-column>
    <el-table-column label="范围 / 责任" min-width="160">
      <template #default="{ row }">
        <div class="work-item-owner"><span>{{ row.organizationScope || '企业全局' }}</span><strong>{{ row.assignee || row.assignedTeam || '待认领' }}</strong></div>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="105">
      <template #default="{ row }"><span class="work-item-status" :class="`work-item-status-${row.status.toLowerCase()}`">{{ statusLabel(row.status) }}</span></template>
    </el-table-column>
    <el-table-column label="证据" width="105">
      <template #default="{ row }"><EvidenceBadge :basis="row.evidenceBasis" /></template>
    </el-table-column>
    <el-table-column label="SLA" width="130">
      <template #default="{ row }"><span :class="['sla-cell', { overdue: row.overdue }]">{{ slaLabel(row) }}</span></template>
    </el-table-column>
    <el-table-column label="操作" width="190" fixed="right">
      <template #default="{ row }">
        <el-button v-if="row.agentId" link type="primary" @click="$emit('open-agent', row.agentId)">Agent</el-button>
        <el-button v-if="row.status === 'OPEN'" link type="primary" @click="$emit('transition', row, 'CLAIMED')">认领</el-button>
        <el-button v-if="row.status === 'CLAIMED'" link type="primary" @click="$emit('transition', row, 'IN_PROGRESS')">开始</el-button>
        <el-button v-if="row.status === 'IN_PROGRESS' || row.status === 'WAITING'" link type="success" @click="$emit('transition', row, 'RESOLVED')">解决</el-button>
      </template>
    </el-table-column>
  </el-table>
  <div v-if="loading && !items.length" class="work-item-mobile-loading"><el-skeleton :rows="3" animated /></div>
  <div v-if="items.length" class="work-item-cards">
    <article v-for="item in rows" :key="item.id" class="work-item-mobile">
      <header><span class="severity-badge" :class="`severity-${item.severity.toLowerCase()}`">{{ item.severity }}</span><span>{{ statusLabel(item.status) }}</span><span :class="{ overdue: item.overdue }">{{ slaLabel(item) }}</span></header>
      <button class="work-item-title" type="button" @click="$emit('inspect', item)"><strong>{{ item.title }}</strong><span>{{ typeLabel(item.itemType) }}</span></button>
      <div class="work-item-mobile-meta"><span>{{ item.assignee || item.assignedTeam || '待认领' }} · {{ item.organizationScope || '企业全局' }}</span><EvidenceBadge :basis="item.evidenceBasis" /></div>
      <footer><el-button link type="primary" @click="$emit('inspect', item)">查看证据</el-button><el-button v-if="item.agentId" link @click="$emit('open-agent', item.agentId)">智能体详情</el-button><el-button v-if="item.status === 'OPEN'" type="primary" plain @click="$emit('transition', item, 'CLAIMED')">认领</el-button><el-button v-else-if="item.status === 'CLAIMED'" type="primary" plain @click="$emit('transition', item, 'IN_PROGRESS')">开始</el-button><el-button v-else-if="item.status === 'IN_PROGRESS' || item.status === 'WAITING'" type="success" plain @click="$emit('transition', item, 'RESOLVED')">解决</el-button></footer>
    </article>
  </div>
  <el-empty v-if="!loading && !items.length" description="当前筛选范围没有待处理事项" :image-size="64" />
  </PaginatedList>
</template>

<script setup lang="ts">
import PaginatedList from '@/components/common/PaginatedList.vue'
import EvidenceBadge from '@/components/EvidenceBadge.vue'
import type { AgentOpsWorkItem, WorkItemStatus, WorkItemType } from '@/types/domain'

defineProps<{ items: AgentOpsWorkItem[]; loading?: boolean; resetKey?: string }>()
defineEmits<{
  inspect: [item: AgentOpsWorkItem]
  transition: [item: AgentOpsWorkItem, status: WorkItemStatus]
  'open-agent': [agentId: number]
}>()

const types: Record<WorkItemType, string> = {
  DISCOVERY_REVIEW: '发现复核', OWNER_ASSIGNMENT: '责任认领', IDENTITY_REVIEW: '身份复核',
  PROBE_OFFLINE: '探针离线', RUNTIME_DEGRADED: '运行降级', RUNTIME_OFFLINE: '运行离线', SECURITY_RISK: '安全风险',
  GOVERNANCE_APPROVAL: '治理审批', CONNECTOR_FAILURE: '连接器故障', OBSERVABILITY_GAP: '观测缺失', EVALUATION_FAILURE: '评估未通过',
  USAGE_RECONCILIATION: '用量对账', GENERAL: '一般事项',
}
const statuses: Record<WorkItemStatus, string> = {
  OPEN: '待处理', CLAIMED: '已认领', IN_PROGRESS: '处理中', WAITING: '等待中', RESOLVED: '已解决', CLOSED: '已关闭',
}

function typeLabel(value: WorkItemType) { return types[value] || value }
function statusLabel(value: WorkItemStatus) { return statuses[value] || value }
function slaLabel(item: AgentOpsWorkItem) {
  if (!item.slaDueAt) return '未设置'
  if (item.status === 'RESOLVED' || item.status === 'CLOSED') return '已完成'
  const minutes = Math.round((new Date(item.slaDueAt).getTime() - Date.now()) / 60000)
  if (minutes < 0) return `逾期 ${duration(Math.abs(minutes))}`
  return `剩余 ${duration(minutes)}`
}
function duration(minutes: number) {
  if (minutes < 60) return `${minutes} 分钟`
  if (minutes < 1440) return `${Math.round(minutes / 60)} 小时`
  return `${Math.round(minutes / 1440)} 天`
}
</script>

<style scoped>
.work-item-cards,.work-item-mobile-loading{display:none}
@media(max-width:760px){.work-item-mobile-loading{display:block;padding:16px}.work-item-table{display:none}.work-item-cards{display:grid;gap:12px}.work-item-mobile{padding:14px;border:1px solid var(--soc-line,#dce3eb);border-radius:8px;background:var(--soc-surface,#fff);min-width:0}.work-item-mobile header{display:flex;align-items:center;gap:10px;font-size:12px}.work-item-mobile header>span:last-child{margin-left:auto}.work-item-mobile .work-item-title{margin:12px 0;width:100%;text-align:left}.work-item-mobile .work-item-title strong{white-space:normal;overflow-wrap:anywhere;line-height:1.6}.work-item-mobile-meta{display:flex;flex-wrap:wrap;align-items:center;gap:8px;font-size:12px;color:#64748b}.work-item-mobile footer{display:flex;align-items:center;flex-wrap:wrap;gap:8px;border-top:1px solid var(--soc-line,#dce3eb);padding-top:10px;margin-top:12px}.work-item-mobile footer .el-button{margin:0;min-height:36px}.work-item-mobile .overdue{color:#b45309}}
</style>
