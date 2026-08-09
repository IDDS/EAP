<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <span class="interface-eyebrow">管理 · 价值</span>
        <strong>智能体管理界面</strong>
        <span>查看投入产出比、任务成效、知识采用与复用，并登记业务方确认的价值事件</span>
      </div>
      <div class="button-row">
        <el-select v-model="windowHours" style="width: 150px" @change="load">
          <el-option label="最近 1 小时" :value="1" />
          <el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" />
          <el-option label="最近 30 天" :value="720" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="interface-tabs interface-tabs-top" role="tablist" aria-label="管理界面分区">
      <el-tag :class="{ 'is-active': activeSection === 'overview' }" :effect="activeSection === 'overview' ? 'dark' : 'plain'" @click="activeSection = 'overview'">价值总览</el-tag>
      <el-tag :class="{ 'is-active': activeSection === 'attribution' }" :effect="activeSection === 'attribution' ? 'dark' : 'plain'" @click="activeSection = 'attribution'">投入产出</el-tag>
      <el-tag :class="{ 'is-active': activeSection === 'value' }" :effect="activeSection === 'value' ? 'dark' : 'plain'" @click="activeSection = 'value'">价值事件</el-tag>
    </div>

    <div class="analysis-hero management-hero">
      <div class="analysis-hero-copy">
        <span class="analysis-eyebrow">价值分析 / 最近 {{ windowHours }} 小时</span>
        <h2>价值回路</h2>
        <p>把模型投入、任务执行、知识贡献与业务确认价值放在同一个回路里，避免只看调用量而忽略实际产出。</p>
      </div>
      <div class="management-value-card">
        <span class="analysis-eyebrow">价值信号</span>
        <strong>{{ roiLabel }}</strong>
        <span>投入产出比 · {{ summary.hasValidatedValue ? '业务方已确认' : '等待业务方确认' }}</span>
        <div class="management-value-meta"><span>净收益 <b>{{ formatMoney(summary.netBenefit) }}</b></span><span>确认价值 <b>{{ formatMoney(summary.validatedBusinessValue) }}</b></span></div>
      </div>
    </div>

    <div class="management-flow-strip">
      <div class="management-flow-step">
        <span class="management-flow-index">01</span>
        <div><span>投入</span><strong>{{ formatMoney(summary.totalCost) }}</strong><small>{{ formatNumber(summary.totalTokens) }} Token</small></div>
      </div>
      <span class="management-flow-arrow">→</span>
      <div class="management-flow-step">
        <span class="management-flow-index">02</span>
        <div><span>执行</span><strong>{{ formatNumber(summary.totalTasks) }}</strong><small>任务成功率 {{ successPercent }}%</small></div>
      </div>
      <span class="management-flow-arrow">→</span>
      <div class="management-flow-step">
        <span class="management-flow-index">03</span>
        <div><span>知识贡献</span><strong>{{ adoptionPercent }}% / {{ reusePercent }}%</strong><small>采用率 / 复用率</small></div>
      </div>
      <span class="management-flow-arrow">→</span>
      <div class="management-flow-step">
        <span class="management-flow-index">04</span>
        <div><span>业务价值</span><strong>{{ formatMoney(summary.validatedBusinessValue) }}</strong><small>{{ summary.hasValidatedValue ? '已确认' : '待确认' }}</small></div>
      </div>
    </div>

    <div v-if="drilldownLabel" class="drilldown-context">
      <span class="drilldown-context-kicker">筛选条件</span>
      <strong>{{ drilldownLabel }}</strong>
      <span>当前价值数据已按上下文收敛</span>
      <el-button link type="primary" @click="clearDrilldown">清除筛选</el-button>
    </div>

    <el-alert
      v-if="!summary.hasValidatedValue"
      type="warning"
      :closable="false"
      show-icon
      title="尚未录入业务方确认价值，投入产出比暂不计算。请在下方登记与智能体或发现端点关联的价值事件。"
    />

    <div v-show="activeSection === 'overview'" class="metric-strip">
      <div class="small-box box-warning">
        <div><strong>{{ formatMoney(summary.totalCost) }}</strong><span>模型投入 USD</span></div>
        <el-icon><Money /></el-icon>
      </div>
      <div class="small-box box-success">
        <div><strong>{{ formatMoney(summary.validatedBusinessValue) }}</strong><span>确认业务价值 USD</span></div>
        <el-icon><TrendCharts /></el-icon>
      </div>
      <div class="small-box box-primary">
        <div><strong>{{ roiLabel }}</strong><span>投入产出比</span></div>
        <el-icon><DataAnalysis /></el-icon>
      </div>
      <div class="small-box box-info">
        <div><strong>{{ successPercent }}%</strong><span>任务成功率</span></div>
        <el-icon><CircleCheck /></el-icon>
      </div>
    </div>

    <div v-show="activeSection === 'overview'" class="management-stat-grid">
      <div><span>任务数</span><strong>{{ formatNumber(summary.totalTasks) }}</strong></div>
      <div><span>模型调用</span><strong>{{ formatNumber(summary.totalModelCalls) }}</strong></div>
      <div><span>总 Token</span><strong>{{ formatNumber(summary.totalTokens) }}</strong></div>
      <div><span>净收益</span><strong>{{ formatMoney(summary.netBenefit) }}</strong></div>
      <div><span>知识采用率</span><strong>{{ adoptionPercent }}%</strong></div>
      <div><span>知识复用率</span><strong>{{ reusePercent }}%</strong></div>
    </div>

    <div v-show="activeSection === 'overview'" class="chart-grid management-chart-grid">
      <el-card shadow="never" class="panel chart-panel">
        <template #header>
          <div class="panel-header">
            <span>确认价值走势</span>
            <span class="muted">价值事件累计</span>
          </div>
        </template>
        <TrendChart :option="valueTrendOption" />
      </el-card>
      <el-card shadow="never" class="panel chart-panel">
        <template #header>
          <div class="panel-header">
            <span>知识采用 / 复用对比</span>
            <span class="muted">智能体维度</span>
          </div>
        </template>
        <TrendChart :option="knowledgeRateOption" />
      </el-card>
    </div>

    <el-card v-show="activeSection === 'overview'" shadow="never" class="panel management-leaderboard-panel">
      <template #header>
        <div class="panel-header">
          <span>智能体价值排行</span>
          <span class="muted">优先展示已有业务价值归因的智能体</span>
        </div>
      </template>
      <div v-if="topAgentUsage.length" class="management-leaderboard">
        <div v-for="(row, index) in topAgentUsage" :key="row.subjectKey" class="management-leader-row">
          <span class="management-rank">{{ String(index + 1).padStart(2, '0') }}</span>
          <div class="management-leader-name"><strong>{{ row.subjectName }}</strong><span>{{ row.subjectKey }}</span></div>
          <div class="management-leader-metric"><span>投入产出比</span><strong>{{ row.validatedValue > 0 ? row.roi.toFixed(4) : '—' }}</strong></div>
          <div class="management-leader-metric"><span>确认价值</span><strong>{{ formatMoney(row.validatedValue) }}</strong></div>
          <div class="management-leader-bars">
            <div><span>采用 {{ Math.round(row.knowledgeAdoptionRate * 100) }}%</span><i><b :style="{ width: `${Math.round(row.knowledgeAdoptionRate * 100)}%` }"></b></i></div>
            <div><span>复用 {{ Math.round(row.knowledgeReuseRate * 100) }}%</span><i><b class="reuse" :style="{ width: `${Math.round(row.knowledgeReuseRate * 100)}%` }"></b></i></div>
          </div>
        </div>
      </div>
      <el-empty v-else description="当前窗口没有可排行的智能体价值数据" />
    </el-card>

    <div v-show="activeSection === 'attribution'" class="management-two-column">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>智能体投入产出与知识指标</span>
            <span class="muted">按窗口聚合真实任务、遥测和知识交互</span>
          </div>
        </template>
        <el-table :data="visibleAgentUsage" v-loading="loading" size="small">
          <el-table-column label="智能体 / 端点" min-width="170">
            <template #default="{ row }">
              <strong>{{ row.subjectName }}</strong>
              <div class="muted">{{ row.subjectKey }}</div>
            </template>
          </el-table-column>
          <el-table-column label="任务" width="72">
            <template #default="{ row }">{{ row.succeededTasks }}/{{ row.tasks }}</template>
          </el-table-column>
          <el-table-column label="调用 / Token" width="128">
            <template #default="{ row }">{{ row.modelCalls }} / {{ formatNumber(row.totalTokens) }}</template>
          </el-table-column>
          <el-table-column label="投入" width="92">
            <template #default="{ row }">{{ formatMoney(row.totalCost) }}</template>
          </el-table-column>
          <el-table-column label="确认价值" width="105">
            <template #default="{ row }">{{ formatMoney(row.validatedValue) }}</template>
          </el-table-column>
          <el-table-column label="投入产出比" width="85">
            <template #default="{ row }">{{ row.validatedValue > 0 ? row.roi.toFixed(4) : '—' }}</template>
          </el-table-column>
          <el-table-column label="采用 / 复用" width="112">
            <template #default="{ row }">{{ Math.round(row.knowledgeAdoptionRate * 100) }}% / {{ Math.round(row.knowledgeReuseRate * 100) }}%</template>
          </el-table-column>
          <el-table-column label="钻取" width="92" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="openPhysical(row)">物理观测</el-button></template>
          </el-table-column>
        </el-table>
        <el-empty v-if="visibleAgentUsage.length === 0" description="当前窗口没有可归因的运行数据" />
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>登记业务价值</span>
            <el-tag size="small" type="warning" effect="plain">需要业务方确认</el-tag>
          </div>
        </template>
        <el-form label-position="top" class="management-form" @submit.prevent="recordValue">
          <div class="form-row">
            <el-form-item label="事件标识" required>
              <el-input v-model="form.eventKey" placeholder="例如 task-2026-001-value" />
            </el-form-item>
            <el-form-item label="价值类型" required>
              <el-select v-model="form.valueType" style="width: 100%">
                <el-option label="节省人工成本" value="LABOR_SAVING" />
                <el-option label="提升收入" value="REVENUE" />
                <el-option label="减少损失" value="LOSS_AVOIDANCE" />
                <el-option label="其他已验证价值" value="OTHER" />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="智能体 ID">
              <el-input-number v-model="form.agentId" :min="1" :controls="false" style="width: 100%" placeholder="可选" />
            </el-form-item>
            <el-form-item label="发现端点标识">
              <el-input v-model="form.endpointKey" placeholder="可选，至少填写智能体或端点之一" />
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="确认价值金额" required>
              <el-input-number v-model="form.benefitMoney" :min="0" :precision="6" :controls="false" style="width: 100%" />
            </el-form-item>
            <el-form-item label="币种">
              <el-input v-model="form.currency" maxlength="16" />
            </el-form-item>
          </div>
          <el-form-item label="确认人 / 业务方" required>
            <el-input v-model="form.validatedBy" placeholder="例如 finance@example.com 或 财务部" />
          </el-form-item>
          <el-form-item label="价值说明">
            <el-input v-model="form.description" type="textarea" :rows="2" maxlength="2048" show-word-limit placeholder="只填写脱敏后的业务结果，不要粘贴公司机密或模型原文" />
          </el-form-item>
          <div class="button-row management-submit-row">
            <span class="muted">提交后会计入投入产出汇总，并保留幂等事件标识。</span>
            <el-button type="primary" :loading="saving" @click="recordValue">登记确认价值</el-button>
          </div>
        </el-form>
      </el-card>
    </div>

    <el-card v-show="activeSection === 'value'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>价值事件记录</span>
          <span class="muted">最近 {{ windowHours }} 小时的价值确认；原始知识正文不进入此界面</span>
        </div>
      </template>
      <el-table :data="visibleValueEvents" v-loading="loading" row-key="id">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.observedAt) }}</template>
        </el-table-column>
        <el-table-column label="智能体 / 端点" min-width="170">
          <template #default="{ row }">
            <strong>{{ row.agentId ? `智能体 ${row.agentId}` : row.endpointKey }}</strong>
            <div class="muted">{{ row.eventKey }}</div>
          </template>
        </el-table-column>
        <el-table-column label="价值类型" width="150">
          <template #default="{ row }">{{ managementValueTypeLabel(row.valueType) }}</template>
        </el-table-column>
        <el-table-column label="确认价值" width="125">
          <template #default="{ row }">{{ row.benefitMoney.toFixed(2) }} {{ row.currency }}</template>
        </el-table-column>
        <el-table-column label="确认人" width="150" prop="validatedBy" />
        <el-table-column label="说明" min-width="240">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="visibleValueEvents.length === 0" description="尚未登记业务价值事件" />
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CircleCheck, DataAnalysis, Money, Refresh, TrendCharts } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { EapApi } from '@/api/eap'
import { useRoute, useRouter } from 'vue-router'
import TrendChart from '@/components/TrendChart.vue'
import type { EChartsOption } from 'echarts'
import type { ManagementSummary, ManagementValueEvent } from '@/types/domain'

const emptySummary: ManagementSummary = {
  windowHours: 24,
  totalTasks: 0,
  succeededTasks: 0,
  taskSuccessRate: 0,
  totalModelCalls: 0,
  totalTokens: 0,
  totalCost: 0,
  validatedBusinessValue: 0,
  netBenefit: 0,
  roi: 0,
  adoptedKnowledgeInteractions: 0,
  reusedKnowledgeInteractions: 0,
  knowledgeAdoptionRate: 0,
  knowledgeReuseRate: 0,
  hasValidatedValue: false,
  agentUsage: [],
}

const windowHours = ref(24)
const activeSection = ref<'overview' | 'attribution' | 'value'>('overview')
const loading = ref(false)
const saving = ref(false)
const summary = ref<ManagementSummary>({ ...emptySummary })
const valueEvents = ref<ManagementValueEvent[]>([])
const form = reactive({
  eventKey: '',
  agentId: undefined as number | undefined,
  endpointKey: '',
  valueType: 'LABOR_SAVING',
  description: '',
  validatedBy: '',
  benefitMoney: 0,
  currency: 'USD',
})
const route = useRoute()
const router = useRouter()
const contextAgentId = computed(() => {
  const value = Number(route.query.agentId)
  return Number.isFinite(value) && value > 0 ? value : undefined
})
const contextEndpointKey = computed(() => String(route.query.endpointKey || '').trim())
const drilldownLabel = computed(() => {
  const parts: string[] = []
  if (contextAgentId.value) parts.push(`智能体 #${contextAgentId.value}`)
  if (contextEndpointKey.value) parts.push(`端点 ${contextEndpointKey.value}`)
  return parts.join(' · ')
})

const successPercent = computed(() => Math.round(summary.value.taskSuccessRate * 100))
const adoptionPercent = computed(() => Math.round(summary.value.knowledgeAdoptionRate * 100))
const reusePercent = computed(() => Math.round(summary.value.knowledgeReuseRate * 100))
const roiLabel = computed(() => summary.value.hasValidatedValue ? summary.value.roi.toFixed(4) : '—')
const visibleAgentUsage = computed(() => summary.value.agentUsage.filter(subject => {
  if (contextAgentId.value && subject.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && subject.endpointKey !== contextEndpointKey.value) return false
  return true
}))
const visibleValueEvents = computed(() => valueEvents.value.filter(event => {
  if (contextAgentId.value && event.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && event.endpointKey !== contextEndpointKey.value) return false
  return true
}))
const topAgentUsage = computed(() => [...visibleAgentUsage.value]
  .sort((left, right) => right.validatedValue - left.validatedValue || right.totalCost - left.totalCost)
  .slice(0, 3))
const valueTrendOption = computed<EChartsOption>(() => {
  const rows = [...visibleValueEvents.value].sort((left, right) => new Date(left.observedAt).getTime() - new Date(right.observedAt).getTime()).slice(-16)
  let cumulative = 0
  const cumulativeValues = rows.map(row => {
    cumulative += row.benefitMoney || 0
    return Number(cumulative.toFixed(4))
  })
  return {
    color: ['#d68b2c', '#16a085'],
    grid: { top: 38, right: 42, bottom: 28, left: 54 },
    legend: { top: 0, right: 0, textStyle: { color: '#718096', fontSize: 11 } },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: rows.map(row => new Date(row.observedAt).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })), axisLabel: { color: '#8795a9', fontSize: 10 } },
    yAxis: { type: 'value', name: 'USD', nameTextStyle: { color: '#8795a9', fontSize: 10 }, axisLabel: { color: '#8795a9', fontSize: 10 }, splitLine: { lineStyle: { color: '#edf1f7' } } },
    series: [
      { name: '单次确认价值', type: 'bar', barMaxWidth: 18, data: rows.map(row => row.benefitMoney), itemStyle: { borderRadius: [4, 4, 0, 0] } },
      { name: '累计确认价值', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, data: cumulativeValues },
    ],
  }
})
const knowledgeRateOption = computed<EChartsOption>(() => {
  const rows = [...visibleAgentUsage.value].sort((left, right) => right.knowledgeAdoptionRate - left.knowledgeAdoptionRate).slice(0, 8)
  return {
    color: ['#3167e8', '#16a085'],
    grid: { top: 34, right: 18, bottom: 22, left: 104 },
    legend: { top: 0, right: 0, textStyle: { color: '#718096', fontSize: 11 } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', max: 100, axisLabel: { color: '#8795a9', fontSize: 10, formatter: '{value}%' }, splitLine: { lineStyle: { color: '#edf1f7' } } },
    yAxis: { type: 'category', inverse: true, data: rows.map(row => row.subjectName), axisLabel: { color: '#5f7188', fontSize: 10 } },
    series: [
      { name: '采用率', type: 'bar', barMaxWidth: 12, data: rows.map(row => Math.round(row.knowledgeAdoptionRate * 100)), itemStyle: { borderRadius: [0, 5, 5, 0] } },
      { name: '复用率', type: 'bar', barMaxWidth: 12, data: rows.map(row => Math.round(row.knowledgeReuseRate * 100)), itemStyle: { borderRadius: [0, 5, 5, 0] } },
    ],
  }
})

function openPhysical(row: { agentId?: number, endpointKey?: string }) {
  router.push({ path: '/physical', query: {
    agentId: row.agentId ? String(row.agentId) : undefined,
    endpointKey: row.endpointKey,
  } })
}

function clearDrilldown() {
  router.replace({ path: '/management', query: {} })
}

async function load() {
  loading.value = true
  try {
    const [nextSummary, nextEvents] = await Promise.all([
      EapApi.getManagementSummary(windowHours.value),
      EapApi.listManagementValueEvents(100),
    ])
    summary.value = nextSummary
    valueEvents.value = nextEvents
  } finally {
    loading.value = false
  }
}

async function recordValue() {
  if (!form.eventKey.trim() || !form.validatedBy.trim() || !form.valueType.trim()) {
    ElMessage.warning('请填写事件标识、价值类型和确认人')
    return
  }
  if (!form.agentId && !form.endpointKey.trim()) {
    ElMessage.warning('请至少关联智能体 ID 或发现端点标识')
    return
  }
  saving.value = true
  try {
    await EapApi.recordManagementValueEvent({
      eventKey: form.eventKey.trim(),
      agentId: form.agentId,
      endpointKey: form.endpointKey.trim() || undefined,
      valueType: form.valueType,
      description: form.description.trim() || undefined,
      validatedBy: form.validatedBy.trim(),
      benefitMoney: form.benefitMoney,
      currency: form.currency.trim() || 'USD',
    })
    ElMessage.success('业务价值事件已登记')
    Object.assign(form, { eventKey: '', agentId: undefined, endpointKey: '', description: '', validatedBy: '', benefitMoney: 0 })
    await load()
  } finally {
    saving.value = false
  }
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('zh-CN', { notation: value >= 1_000_000 ? 'compact' : 'standard' }).format(value)
}

function formatMoney(value: number) {
  return `$${new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(value || 0)}`
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function managementValueTypeLabel(value: string) {
  const labels: Record<string, string> = {
    LABOR_SAVING: '节省人工成本',
    REVENUE: '提升收入',
    LOSS_AVOIDANCE: '减少损失',
    OTHER: '其他已验证价值',
  }
  return labels[value] || value
}

onMounted(load)
</script>
