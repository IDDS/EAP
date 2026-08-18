<template>
  <section class="page-grid agentops-workbench" v-loading="loading">
    <header class="agentops-page-header">
      <div>
        <span class="interface-eyebrow">AGENTOPS · ACTION WORKBENCH</span>
        <h2>运营工作台</h2>
        <p>优先处理影响运行、身份、治理和观测完整性的事项。所有数字均显示证据基础与时间窗口。</p>
      </div>
      <div class="button-row">
        <span class="context-summary">{{ context.scopeLabel }} · {{ context.windowLabel }}</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新信号</el-button>
      </div>
    </header>

    <el-alert v-if="dashboard.demoDataIncluded" type="warning" :closable="false" show-icon title="当前为开发模式：页面包含明确标记的演示 Agent；JWT 生产模式会从 AgentOps 主视图排除这些记录。" />

    <div class="workbench-priority-strip">
      <button class="priority-cell priority-critical" type="button" @click="applySeverity('P0')">
        <span>P0 · 立即响应</span><strong>{{ dashboard.workItems.p0 }}</strong><small>关键安全或治理阻断</small>
      </button>
      <button class="priority-cell priority-high" type="button" @click="applySeverity('P1')">
        <span>P1 · 高优先</span><strong>{{ dashboard.workItems.p1 }}</strong><small>离线、身份与高风险事项</small>
      </button>
      <button class="priority-cell priority-medium" type="button" @click="applySeverity('P2')">
        <span>P2 · 计划内处理</span><strong>{{ dashboard.workItems.p2 }}</strong><small>降级与观测完整性</small>
      </button>
      <button class="priority-cell priority-overdue" type="button" @click="showOverdueOnly = !showOverdueOnly">
        <span>SLA · 已逾期</span><strong>{{ dashboard.workItems.overdue }}</strong><small>{{ showOverdueOnly ? '再次点击取消筛选' : '需要升级或重新分派' }}</small>
      </button>
    </div>

    <div class="evidence-metric-grid">
      <article v-for="metric in dashboard.metrics" :key="metric.key" class="evidence-metric-card">
        <div><span>{{ metric.label }}</span><EvidenceBadge :basis="metric.basis" :source="metric.source" :freshness="freshnessLabel(metric.freshness)" /></div>
        <strong>{{ formatNumber(metric.value) }}</strong>
        <small>{{ metric.source }} · {{ metric.measuredAt ? formatDate(metric.measuredAt) : '尚无观测时间' }}</small>
      </article>
    </div>

    <el-card shadow="never" class="panel workbench-queue-panel">
      <template #header>
        <div class="panel-header workbench-panel-header">
          <div><strong>我的运营队列</strong><span>{{ queueCaption }}</span></div>
          <div class="workbench-filters">
            <el-select v-model="severityFilter" clearable placeholder="全部级别" style="width: 126px">
              <el-option v-for="level in ['P0', 'P1', 'P2', 'P3']" :key="level" :label="level" :value="level" />
            </el-select>
            <el-button text @click="clearFilters">清除筛选</el-button>
          </div>
        </div>
      </template>
      <WorkItemTable
        :items="filteredWorkItems"
        :loading="loading"
        @inspect="inspectWorkItem"
        @transition="prepareTransition"
        @open-agent="openAgent"
      />
    </el-card>

    <div class="workbench-lower-grid">
      <el-card shadow="never" class="panel attention-agent-panel">
        <template #header><div class="panel-header"><span>需要关注的 Agent</span><el-button link type="primary" @click="router.push('/agents')">查看全部</el-button></div></template>
        <button v-for="agent in dashboard.attentionAgents" :key="agent.id" class="attention-agent-row" type="button" @click="openAgent(agent.id)">
          <div class="attention-agent-identity"><strong>{{ agent.name }}</strong><span>{{ agent.code }} · {{ agent.ownerDepartment || '待分配部门' }}</span></div>
          <StatusAxis axis="health" :value="agent.operationalHealth" />
          <StatusAxis axis="coverage" :value="agent.observationCoverage" />
          <StatusAxis axis="risk" :value="agent.riskPosture" />
          <b>{{ agent.openWorkItemCount }} 项</b>
          <el-icon><ArrowRight /></el-icon>
        </button>
        <el-empty v-if="!dashboard.attentionAgents.length" description="当前没有需要关注的 Agent" :image-size="64" />
      </el-card>

      <el-card shadow="never" class="panel lifecycle-entry-panel">
        <template #header><span>生命周期入口</span></template>
        <button v-for="entry in lifecycleEntries" :key="entry.path" type="button" @click="router.push(entry.path)">
          <span class="lifecycle-entry-index">{{ entry.index }}</span>
          <div><strong>{{ entry.title }}</strong><small>{{ entry.description }}</small></div>
          <el-icon><ArrowRight /></el-icon>
        </button>
      </el-card>
    </div>
  </section>

  <el-drawer v-model="detailVisible" title="运营事项证据" size="520px">
    <template v-if="selectedWorkItem">
      <div class="work-item-drawer-heading">
        <span class="severity-badge" :class="`severity-${selectedWorkItem.severity.toLowerCase()}`">{{ selectedWorkItem.severity }}</span>
        <h3>{{ selectedWorkItem.title }}</h3>
        <p>{{ selectedWorkItem.description }}</p>
      </div>
      <div class="work-item-evidence-grid">
        <div><span>事项状态</span><strong>{{ workItemStatusLabel(selectedWorkItem.status) }}</strong></div>
        <div><span>组织范围</span><strong>{{ selectedWorkItem.organizationScope || '企业全局' }}</strong></div>
        <div><span>责任人</span><strong>{{ selectedWorkItem.assignee || '待认领' }}</strong></div>
        <div><span>证据基础</span><EvidenceBadge :basis="selectedWorkItem.evidenceBasis" /></div>
        <div><span>首次发现</span><strong>{{ formatDate(selectedWorkItem.firstDetectedAt) }}</strong></div>
        <div><span>最后发现</span><strong>{{ formatDate(selectedWorkItem.lastDetectedAt) }}</strong></div>
      </div>
      <div class="evidence-json-block"><span>源证据</span><pre>{{ formattedEvidence }}</pre></div>
    </template>
  </el-drawer>

  <el-dialog v-model="transitionVisible" :title="transitionTitle" width="520px">
    <el-form label-position="top">
      <el-form-item v-if="targetStatus === 'CLAIMED'" label="负责人">
        <el-input v-model="transitionForm.assignee" placeholder="输入本次事项负责人" />
      </el-form-item>
      <el-form-item v-if="targetStatus === 'RESOLVED'" label="解决说明">
        <el-input v-model="transitionForm.reason" type="textarea" :rows="4" placeholder="说明处置结果和可验证证据" />
      </el-form-item>
      <el-form-item v-if="targetStatus === 'RESOLVED'" label="证据引用（可选）">
        <el-input v-model="transitionForm.resolutionEvidenceJson" placeholder='例如 {"traceId":"...","ticket":"..."}' />
      </el-form-item>
    </el-form>
    <template #footer><el-button @click="transitionVisible = false">取消</el-button><el-button type="primary" :loading="transitioning" @click="submitTransition">确认</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import EvidenceBadge from '@/components/EvidenceBadge.vue'
import StatusAxis from '@/components/StatusAxis.vue'
import WorkItemTable from '@/components/WorkItemTable.vue'
import { EapApi } from '@/api/eap'
import { useAgentOpsContextStore } from '@/stores/agentOps'
import type { AgentOpsDashboard, AgentOpsWorkItem, WorkItemSeverity, WorkItemStatus } from '@/types/domain'

const router = useRouter()
const context = useAgentOpsContextStore()
const loading = ref(false)
const transitioning = ref(false)
const severityFilter = ref<WorkItemSeverity>()
const showOverdueOnly = ref(false)
const detailVisible = ref(false)
const transitionVisible = ref(false)
const selectedWorkItem = ref<AgentOpsWorkItem>()
const targetStatus = ref<WorkItemStatus>()
const transitionForm = reactive({ assignee: '当前操作员', reason: '', resolutionEvidenceJson: '' })
const dashboard = ref<AgentOpsDashboard>({
  windowHours: 24, windowStart: '', windowEnd: '', timeZone: 'Asia/Shanghai', organizationScope: '企业全局',
  agentCount: 0, healthyAgentCount: 0, degradedAgentCount: 0, offlineAgentCount: 0,
  missingObservationCount: 0, highRiskAgentCount: 0,
  workItems: { totalOpen: 0, p0: 0, p1: 0, p2: 0, p3: 0, overdue: 0 },
  metrics: [], priorityWorkItems: [], attentionAgents: [], demoDataIncluded: false, generatedAt: '',
})

const lifecycleEntries = [
  { index: '01', title: '资产与身份', description: '复核发现资产并纳入 Agent 目录', path: '/registry' },
  { index: '02', title: '运行与观测', description: '检查心跳、模型、Trace 和工具信号', path: '/physical' },
  { index: '03', title: '治理与安全', description: '处理审批、策略和高风险访问', path: '/governance' },
  { index: '04', title: '成本与价值', description: '核对用量、成本和业务价值证据', path: '/management' },
  { index: '05', title: '自动化', description: '编排团队与可控工作流', path: '/workflow-builder' },
]

const filteredWorkItems = computed(() => dashboard.value.priorityWorkItems.filter(item =>
  (!severityFilter.value || item.severity === severityFilter.value) && (!showOverdueOnly.value || item.overdue),
))
const queueCaption = computed(() => `${filteredWorkItems.value.length} / ${dashboard.value.workItems.totalOpen} 项 · 生成于 ${formatDate(dashboard.value.generatedAt)}`)
const formattedEvidence = computed(() => {
  if (!selectedWorkItem.value?.evidenceJson) return '无结构化源证据'
  try { return JSON.stringify(JSON.parse(selectedWorkItem.value.evidenceJson), null, 2) } catch { return selectedWorkItem.value.evidenceJson }
})
const transitionTitle = computed(() => targetStatus.value === 'CLAIMED' ? '认领运营事项' : targetStatus.value === 'IN_PROGRESS' ? '开始处理' : '提交解决结果')

async function load() {
  loading.value = true
  try {
    dashboard.value = await EapApi.getAgentOpsDashboard({
      hours: context.windowHours,
      organizationScope: context.organizationScope || undefined,
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '运营工作台加载失败')
  } finally { loading.value = false }
}

function applySeverity(value: WorkItemSeverity) { severityFilter.value = severityFilter.value === value ? undefined : value }
function clearFilters() { severityFilter.value = undefined; showOverdueOnly.value = false }
function openAgent(id: number) { router.push(`/agents/${id}`) }
function inspectWorkItem(item: AgentOpsWorkItem) { selectedWorkItem.value = item; detailVisible.value = true }
function prepareTransition(item: AgentOpsWorkItem, status: WorkItemStatus) {
  selectedWorkItem.value = item
  targetStatus.value = status
  transitionForm.assignee = item.assignee || '当前操作员'
  transitionForm.reason = ''
  transitionForm.resolutionEvidenceJson = ''
  if (status === 'IN_PROGRESS') void submitTransition()
  else transitionVisible.value = true
}
async function submitTransition() {
  if (!selectedWorkItem.value || !targetStatus.value) return
  if (targetStatus.value === 'CLAIMED' && !transitionForm.assignee.trim()) { ElMessage.warning('请填写负责人'); return }
  if (targetStatus.value === 'RESOLVED' && !transitionForm.reason.trim()) { ElMessage.warning('请填写解决说明'); return }
  transitioning.value = true
  try {
    await EapApi.transitionAgentOpsWorkItem(selectedWorkItem.value.id, {
      status: targetStatus.value,
      assignee: transitionForm.assignee || undefined,
      reason: transitionForm.reason || undefined,
      resolutionEvidenceJson: transitionForm.resolutionEvidenceJson || undefined,
    })
    transitionVisible.value = false
    ElMessage.success('运营事项状态已更新')
    await load()
  } finally { transitioning.value = false }
}
function formatNumber(value: number) { return new Intl.NumberFormat('zh-CN').format(value || 0) }
function formatDate(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function freshnessLabel(value: string) { return ({ FRESH: '新鲜', AGING: '临近过期', STALE: '已过期', NO_DATA: '无数据' } as Record<string, string>)[value] || value }
function workItemStatusLabel(value: WorkItemStatus) { return ({ OPEN: '待处理', CLAIMED: '已认领', IN_PROGRESS: '处理中', WAITING: '等待中', RESOLVED: '已解决', CLOSED: '已关闭' } as Record<WorkItemStatus, string>)[value] }

watch(() => [context.windowHours, context.organizationScope], load)
onMounted(load)
</script>
