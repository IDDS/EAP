<template>
  <section class="page-grid knowledge-page">
    <ConsolePageHeader>
      <template #actions><div class="button-row">
        <el-select v-model="windowHours" aria-label="统计时间范围" style="width: 150px" @change="load">
          <el-option label="最近 1 小时" :value="1" />
          <el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" />
          <el-option label="最近 30 天" :value="720" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div></template>
    </ConsolePageHeader>



    <div v-show="activeSection === 'overview'" class="analysis-hero knowledge-hero">
      <div class="analysis-hero-copy">
        <span class="analysis-eyebrow">知识流转 / 最近 {{ windowHours }} 小时</span>
        <h2>知识流转链路</h2>
        <p>把输入知识、处理过程与输出结果放到同一条观测链上，快速判断知识是否真正被智能体采用并形成复用。</p>
      </div>
      <div class="knowledge-gauges">
        <div class="knowledge-gauge">
          <el-progress type="circle" :percentage="adoptionPercent" :width="82" :stroke-width="8" color="#3167e8" />
          <div><strong>采用率</strong><span>知识进入结果</span></div>
        </div>
        <div class="knowledge-gauge">
          <el-progress type="circle" :percentage="reusePercent" :width="82" :stroke-width="8" color="#16a085" />
          <div><strong>复用率</strong><span>知识再次被调用</span></div>
        </div>
      </div>
    </div>

    <div v-show="activeSection === 'overview'" class="knowledge-stage-board">
      <div class="knowledge-stage">
        <span class="knowledge-stage-index">01</span>
        <div><span class="knowledge-stage-label">输入知识</span><strong>{{ formatNumber(summary.inputSourceCount) }} <small>来源</small></strong><p>{{ formatNumber(summary.inputTokens) }} 输入 Token</p></div>
      </div>
      <span class="knowledge-stage-arrow">→</span>
      <div class="knowledge-stage">
        <span class="knowledge-stage-index">02</span>
        <div><span class="knowledge-stage-label">过程跟踪</span><strong>{{ summary.averageProcessLatencyMs }} <small>ms</small></strong><p>{{ summary.adoptedInteractions }} 次结果被采用</p></div>
      </div>
      <span class="knowledge-stage-arrow">→</span>
      <div class="knowledge-stage">
        <span class="knowledge-stage-index">03</span>
        <div><span class="knowledge-stage-label">输出知识</span><strong>{{ formatNumber(summary.outputSourceCount) }} <small>来源</small></strong><p>{{ formatNumber(summary.outputTokens) }} 输出 Token</p></div>
      </div>
    </div>

    <div v-if="drilldownLabel" class="drilldown-context">
      <span class="drilldown-context-kicker">筛选条件</span>
      <strong>{{ drilldownLabel }}</strong>
      <span>当前知识交互已按上下文收敛</span>
      <router-link v-if="contextAgentId" :to="`/agents/${contextAgentId}`">返回智能体</router-link>
      <el-button link type="primary" @click="clearDrilldown">查看全部记录</el-button>
    </div>

    <el-alert
      v-if="!summary.lastInteractionAt"
      type="warning"
      :closable="false"
      show-icon
      title="尚未收到知识交互遥测。请由智能体运行时上报输入、过程步骤和输出引用。"
    />

    <el-card v-show="activeSection === 'ingest'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>知识交互接入</span>
          <span class="muted">上报脱敏后的输入分析、过程跟踪和输出知识元数据</span>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent="recordInteraction">
        <div class="form-row">
          <el-form-item label="交互标识" required>
            <el-input v-model="form.interactionKey" placeholder="例如 knowledge-fin-001" />
          </el-form-item>
          <el-form-item label="关联智能体" required>
            <el-select v-model="form.agentId" style="width: 100%" placeholder="选择智能体">
              <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.name} (${agent.code})`" :value="agent.id" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="输入知识类型" required><el-input v-model="form.inputType" placeholder="DOCUMENT / DATABASE / SEARCH" /></el-form-item>
          <el-form-item label="输入来源数"><el-input-number v-model="form.inputSourceCount" :min="0" :controls="false" style="width: 100%" /></el-form-item>
          <el-form-item label="输入 Token"><el-input-number v-model="form.inputTokenCount" :min="0" :controls="false" style="width: 100%" /></el-form-item>
        </div>
        <el-form-item label="输入知识摘要">
          <el-input v-model="form.inputSummary" type="textarea" :rows="2" maxlength="2048" show-word-limit placeholder="只填写脱敏摘要，不粘贴知识正文或公司机密" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="过程步骤类型">
            <el-select v-model="form.stepType" style="width: 100%">
              <el-option label="输入分析" value="INPUT_ANALYSIS" />
              <el-option label="知识检索" value="RETRIEVAL" />
              <el-option label="工具处理" value="TOOL" />
              <el-option label="推理" value="REASONING" />
              <el-option label="转换" value="TRANSFORM" />
              <el-option label="输出分析" value="OUTPUT_ANALYSIS" />
            </el-select>
          </el-form-item>
          <el-form-item label="步骤名称"><el-input v-model="form.stepName" placeholder="例如 检索制度知识库" /></el-form-item>
          <el-form-item label="步骤耗时 ms"><el-input-number v-model="form.stepLatencyMs" :min="0" :controls="false" style="width: 100%" /></el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="输出知识类型"><el-input v-model="form.outputType" placeholder="ANSWER / REPORT / DECISION" /></el-form-item>
          <el-form-item label="输出来源数"><el-input-number v-model="form.outputSourceCount" :min="0" :controls="false" style="width: 100%" /></el-form-item>
          <el-form-item label="输出 Token"><el-input-number v-model="form.outputTokenCount" :min="0" :controls="false" style="width: 100%" /></el-form-item>
        </div>
        <el-form-item label="输出知识摘要">
          <el-input v-model="form.outputSummary" type="textarea" :rows="2" maxlength="2048" show-word-limit placeholder="填写脱敏后的输出知识摘要" />
        </el-form-item>
        <div class="form-row">
          <el-form-item label="交互状态">
            <el-select v-model="form.status" style="width: 100%">
              <el-option label="成功" value="SUCCEEDED" />
              <el-option label="失败" value="FAILED" />
              <el-option label="进行中" value="RUNNING" />
              <el-option label="取消" value="CANCELLED" />
            </el-select>
          </el-form-item>
          <el-form-item label="质量分"><el-input-number v-model="form.qualityScore" :min="0" :max="100" :controls="false" style="width: 100%" /></el-form-item>
          <el-form-item label="结果使用情况">
            <div class="governance-switch-row">
              <el-checkbox v-model="form.adopted">已采用</el-checkbox>
              <el-checkbox v-model="form.reused">已复用</el-checkbox>
            </div>
          </el-form-item>
        </div>
        <div class="button-row governance-submit-row">
          <span class="muted">交互标识幂等；过程步骤会单独持久化并计入采用率、复用率和管理指标。</span>
          <el-button type="primary" :loading="saving" @click="recordInteraction">上报知识交互</el-button>
        </div>
      </el-form>
    </el-card>

    <div v-show="activeSection === 'overview'" class="metric-strip">
      <div class="small-box box-primary">
        <div><strong>{{ summary.totalInteractions }}</strong><span>知识交互</span></div>
        <el-icon><DataAnalysis /></el-icon>
      </div>
      <div class="small-box box-info">
        <div><strong>{{ formatNumber(summary.inputSourceCount) }}</strong><span>输入知识来源</span></div>
        <el-icon><Collection /></el-icon>
      </div>
      <div class="small-box box-success">
        <div><strong>{{ adoptionPercent }}%</strong><span>知识采用率</span></div>
        <el-icon><Select /></el-icon>
      </div>
      <div class="small-box box-warning">
        <div><strong>{{ reusePercent }}%</strong><span>知识复用率</span></div>
        <el-icon><Refresh /></el-icon>
      </div>
    </div>

    <div v-show="activeSection === 'overview'" class="knowledge-stat-grid">
      <div><span>输入 Token</span><strong>{{ formatNumber(summary.inputTokens) }}</strong></div>
      <div><span>输出 Token</span><strong>{{ formatNumber(summary.outputTokens) }}</strong></div>
      <div><span>输出知识来源</span><strong>{{ formatNumber(summary.outputSourceCount) }}</strong></div>
      <div><span>平均质量分</span><strong>{{ summary.averageQualityScore.toFixed(1) }}</strong></div>
      <div><span>过程平均耗时</span><strong>{{ summary.averageProcessLatencyMs }} ms</strong></div>
      <div><span>交互成功率</span><strong>{{ successPercent }}%</strong></div>
    </div>

    <div v-show="activeSection === 'overview'" class="chart-grid knowledge-chart-grid">
      <el-card shadow="never" class="panel chart-panel">
        <template #header>
          <div class="panel-header">
            <span>知识交互走势</span>
            <span class="muted">输入 / 输出 Token</span>
          </div>
        </template>
        <TrendChart :option="knowledgeTrendOption" />
      </el-card>
      <el-card shadow="never" class="panel chart-panel">
        <template #header>
          <div class="panel-header">
            <span>智能体知识效率</span>
            <span class="muted">采用 / 复用</span>
          </div>
        </template>
        <TrendChart :option="knowledgeAgentRateOption" />
      </el-card>
    </div>

    <div v-show="activeSection === 'overview'" class="knowledge-two-column">
      <el-card shadow="never" class="panel">
        <template #header>智能体知识采用与复用</template>
        <el-table :data="visibleAgentUsage" size="small">
          <el-table-column prop="subjectName" label="智能体 / 端点" min-width="170" />
          <el-table-column prop="interactions" label="交互" width="75" />
          <el-table-column label="采用率" width="95">
            <template #default="{ row }">{{ Math.round(row.adoptionRate * 100) }}%</template>
          </el-table-column>
          <el-table-column label="复用率" width="95">
            <template #default="{ row }">{{ Math.round(row.reuseRate * 100) }}%</template>
          </el-table-column>
          <el-table-column label="质量" width="80">
            <template #default="{ row }">{{ row.averageQualityScore.toFixed(1) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>知识流转阶段</template>
        <div class="knowledge-flow">
          <div><strong>输入知识</strong><span>来源 {{ formatNumber(summary.inputSourceCount) }} · Token {{ formatNumber(summary.inputTokens) }}</span></div>
          <div><strong>过程跟踪</strong><span>平均 {{ summary.averageProcessLatencyMs }} ms · 采用 {{ summary.adoptedInteractions }} 次</span></div>
          <div><strong>输出知识</strong><span>来源 {{ formatNumber(summary.outputSourceCount) }} · 复用 {{ summary.reusedInteractions }} 次</span></div>
        </div>
      </el-card>
    </div>

    <el-card v-show="activeSection === 'trace'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>知识交互追踪</span>
          <span class="muted">只展示脱敏摘要和引用标识，不展示知识正文</span>
        </div>
      </template>
      <el-table :data="visibleInteractions" v-loading="loading" row-key="id">
        <el-table-column type="expand" width="50">
          <template #default="{ row }">
            <div class="knowledge-steps">
              <div v-for="step in row.processSteps" :key="step.id" class="knowledge-step">
                <el-tag size="small" effect="plain">{{ knowledgeStepLabel(step.stepType) }}</el-tag>
                <strong>{{ step.name }}</strong>
                <span>{{ step.sourceCount }} 来源 · {{ formatNumber(step.inputTokens + step.outputTokens) }} Token · {{ step.latencyMs }} ms</span>
              </div>
              <span v-if="row.processSteps.length === 0" class="muted">没有上报过程步骤</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="交互" min-width="200">
          <template #default="{ row }">
            <strong>{{ row.inputType }} → {{ row.outputType || '未定义输出' }}</strong>
            <div class="muted">{{ row.interactionKey }} · {{ row.traceId || '无 Trace' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="输入知识" min-width="220">
          <template #default="{ row }">
            <div>{{ row.inputSummary || '无输入摘要' }}</div>
            <div class="muted">{{ row.inputSourceCount }} 来源 · {{ formatNumber(row.inputTokenCount) }} Token</div>
          </template>
        </el-table-column>
        <el-table-column label="过程" width="125">
          <template #default="{ row }">{{ row.processStepCount }} 步 · {{ row.processLatencyMs }} ms</template>
        </el-table-column>
        <el-table-column label="输出知识" min-width="220">
          <template #default="{ row }">
            <div>{{ row.outputSummary || '无输出摘要' }}</div>
            <div class="muted">{{ row.outputSourceCount }} 来源 · {{ formatNumber(row.outputTokenCount) }} Token</div>
          </template>
        </el-table-column>
        <el-table-column label="采用 / 复用" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="row.adopted ? 'success' : 'info'">采用 {{ row.adopted ? '是' : '否' }}</el-tag>
            <el-tag size="small" :type="row.reused ? 'warning' : 'info'">复用 {{ row.reused ? '是' : '否' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ knowledgeStatusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="钻取" width="82" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="openPhysical(row)">物理观测</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { usePageSection } from '@/composables/usePageSection'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Collection, DataAnalysis, Refresh, Select } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { EapApi } from '@/api/eap'
import { useRoute, useRouter } from 'vue-router'
import TrendChart from '@/components/TrendChart.vue'
import type { EChartsOption } from 'echarts'
import type {
  Agent,
  KnowledgeInteraction,
  KnowledgeInteractionStatus,
  KnowledgeStepType,
  KnowledgeSummary,
} from '@/types/domain'

const emptySummary: KnowledgeSummary = {
  windowHours: 24,
  totalInteractions: 0,
  succeededInteractions: 0,
  failedInteractions: 0,
  successRate: 0,
  inputTokens: 0,
  outputTokens: 0,
  inputSourceCount: 0,
  outputSourceCount: 0,
  adoptedInteractions: 0,
  reusedInteractions: 0,
  adoptionRate: 0,
  reuseRate: 0,
  averageQualityScore: 0,
  averageProcessLatencyMs: 0,
  agentUsage: [],
}

const windowHours = ref(24)
const activeSection = usePageSection()
const loading = ref(false)
const saving = ref(false)
const summary = ref<KnowledgeSummary>({ ...emptySummary })
const interactions = ref<KnowledgeInteraction[]>([])
const agents = ref<Agent[]>([])
const form = reactive({
  interactionKey: '',
  agentId: undefined as number | undefined,
  inputType: 'DOCUMENT',
  inputSourceCount: 0,
  inputTokenCount: 0,
  inputSummary: '',
  stepType: 'RETRIEVAL' as KnowledgeStepType,
  stepName: '检索企业知识库',
  stepLatencyMs: 0,
  outputType: 'ANSWER',
  outputSourceCount: 0,
  outputTokenCount: 0,
  outputSummary: '',
  status: 'SUCCEEDED' as KnowledgeInteractionStatus,
  adopted: false,
  reused: false,
  qualityScore: 80,
})
const route = useRoute()
const router = useRouter()
const contextAgentId = computed(() => {
  const value = Number(route.query.agentId)
  return Number.isFinite(value) && value > 0 ? value : undefined
})
const contextEndpointKey = computed(() => String(route.query.endpointKey || '').trim())
const contextTraceId = computed(() => String(route.query.traceId || '').trim())
const drilldownLabel = computed(() => {
  const parts: string[] = []
  if (contextAgentId.value) parts.push(`智能体 #${contextAgentId.value}`)
  if (contextEndpointKey.value) parts.push(`端点 ${contextEndpointKey.value}`)
  if (contextTraceId.value) parts.push(`Trace ${contextTraceId.value}`)
  return parts.join(' · ')
})
watch(contextTraceId, traceId => {
  if (traceId) activeSection.value = 'trace'
}, { immediate: true })
const adoptionPercent = computed(() => Math.round(summary.value.adoptionRate * 100))
const reusePercent = computed(() => Math.round(summary.value.reuseRate * 100))
const successPercent = computed(() => Math.round(summary.value.successRate * 100))
const visibleAgentUsage = computed(() => summary.value.agentUsage.filter(subject => {
  if (contextAgentId.value && subject.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && subject.endpointKey !== contextEndpointKey.value) return false
  return true
}))
const visibleInteractions = computed(() => interactions.value.filter(interaction => {
  if (contextAgentId.value && interaction.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && interaction.endpointKey !== contextEndpointKey.value) return false
  if (contextTraceId.value && interaction.traceId !== contextTraceId.value) return false
  return true
}))
const knowledgeTrendOption = computed<EChartsOption>(() => {
  const rows = [...visibleInteractions.value].sort((left, right) => new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime()).slice(-16)
  return {
    color: ['#3167e8', '#16a085'],
    grid: { top: 36, right: 18, bottom: 28, left: 54 },
    legend: { top: 0, right: 0, textStyle: { color: '#718096', fontSize: 11 } },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: rows.map(row => new Date(row.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })), axisLabel: { color: '#8795a9', fontSize: 10 } },
    yAxis: { type: 'value', name: 'Token', nameTextStyle: { color: '#8795a9', fontSize: 10 }, axisLabel: { color: '#8795a9', fontSize: 10 }, splitLine: { lineStyle: { color: '#edf1f7' } } },
    series: [
      { name: '输入 Token', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, data: rows.map(row => row.inputTokenCount) },
      { name: '输出 Token', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, data: rows.map(row => row.outputTokenCount) },
    ],
  }
})
const knowledgeAgentRateOption = computed<EChartsOption>(() => {
  const rows = [...visibleAgentUsage.value].sort((left, right) => right.adoptionRate - left.adoptionRate).slice(0, 8)
  return {
    color: ['#3167e8', '#16a085'],
    grid: { top: 34, right: 18, bottom: 22, left: 104 },
    legend: { top: 0, right: 0, textStyle: { color: '#718096', fontSize: 11 } },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    xAxis: { type: 'value', max: 100, axisLabel: { color: '#8795a9', fontSize: 10, formatter: '{value}%' }, splitLine: { lineStyle: { color: '#edf1f7' } } },
    yAxis: { type: 'category', inverse: true, data: rows.map(row => row.subjectName), axisLabel: { color: '#5f7188', fontSize: 10 } },
    series: [
      { name: '采用率', type: 'bar', barMaxWidth: 12, data: rows.map(row => Math.round(row.adoptionRate * 100)), itemStyle: { borderRadius: [0, 5, 5, 0] } },
      { name: '复用率', type: 'bar', barMaxWidth: 12, data: rows.map(row => Math.round(row.reuseRate * 100)), itemStyle: { borderRadius: [0, 5, 5, 0] } },
    ],
  }
})

function openPhysical(row: KnowledgeInteraction) {
  router.push({ path: '/physical', query: {
    agentId: row.agentId ? String(row.agentId) : undefined,
    endpointKey: row.endpointKey,
    traceId: row.traceId,
  } })
}

function clearDrilldown() {
  router.replace({ path: '/knowledge', query: { section: activeSection.value } })
}

async function load() {
  loading.value = true
  try {
    const [nextSummary, nextInteractions, nextAgents] = await Promise.all([
      EapApi.getKnowledgeSummary(windowHours.value),
      EapApi.listKnowledgeInteractions(100),
      EapApi.listAgents(),
    ])
    summary.value = nextSummary
    interactions.value = nextInteractions
    agents.value = nextAgents
    if (!form.agentId) form.agentId = nextAgents[0]?.id
  } finally {
    loading.value = false
  }
}

async function recordInteraction() {
  if (!form.interactionKey.trim() || !form.agentId || !form.inputType.trim()) {
    ElMessage.warning('请填写交互标识、智能体和输入知识类型')
    return
  }
  saving.value = true
  try {
    const processSteps = form.stepName.trim() ? [{
      stepOrder: 1,
      stepType: form.stepType,
      name: form.stepName.trim(),
      status: form.status === 'FAILED' ? 'FAILED' as const : 'SUCCEEDED' as const,
      sourceCount: form.inputSourceCount,
      inputTokens: form.inputTokenCount,
      outputTokens: form.outputTokenCount,
      latencyMs: form.stepLatencyMs,
      knowledgeRefs: [],
      metadata: { source: 'knowledge-console' },
    }] : []
    await EapApi.recordKnowledgeInteraction({
      interactionKey: form.interactionKey.trim(),
      traceId: `trace-${form.interactionKey.trim()}`,
      agentId: form.agentId,
      inputType: form.inputType.trim(),
      inputSourceCount: form.inputSourceCount,
      inputTokenCount: form.inputTokenCount,
      inputSummary: form.inputSummary.trim() || undefined,
      inputKnowledgeRefs: [],
      outputType: form.outputType.trim() || undefined,
      outputSourceCount: form.outputSourceCount,
      outputTokenCount: form.outputTokenCount,
      outputSummary: form.outputSummary.trim() || undefined,
      outputKnowledgeRefs: [],
      processSteps,
      status: form.status,
      adopted: form.adopted,
      reused: form.reused,
      qualityScore: form.qualityScore,
    })
    ElMessage.success('知识交互已上报，并计入知识与管理指标')
    Object.assign(form, {
      interactionKey: '',
      inputSummary: '',
      outputSummary: '',
      inputSourceCount: 0,
      inputTokenCount: 0,
      outputSourceCount: 0,
      outputTokenCount: 0,
      stepLatencyMs: 0,
      adopted: false,
      reused: false,
    })
    await load()
  } finally {
    saving.value = false
  }
}

function formatNumber(value: number) {
  return new Intl.NumberFormat('zh-CN', { notation: value >= 1_000_000 ? 'compact' : 'standard' }).format(value)
}

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function statusType(status: KnowledgeInteractionStatus) {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'RUNNING') return 'warning'
  return 'info'
}

function knowledgeStepLabel(value: KnowledgeStepType) {
  const labels: Record<KnowledgeStepType, string> = {
    INPUT_ANALYSIS: '输入分析',
    RETRIEVAL: '知识检索',
    TOOL: '工具处理',
    REASONING: '推理',
    TRANSFORM: '转换',
    OUTPUT_ANALYSIS: '输出分析',
  }
  return labels[value] || value
}

function knowledgeStatusLabel(value: KnowledgeInteractionStatus) {
  const labels: Record<KnowledgeInteractionStatus, string> = {
    RUNNING: '进行中',
    SUCCEEDED: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
  }
  return labels[value] || value
}

onMounted(load)
</script>
