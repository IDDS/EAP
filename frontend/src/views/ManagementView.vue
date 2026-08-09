<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <span class="interface-eyebrow">MANAGEMENT · VALUE</span>
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

    <div class="analysis-hero management-hero">
      <div class="analysis-hero-copy">
        <span class="analysis-eyebrow">MANAGEMENT PLANE / {{ windowHours }}H OBSERVATION</span>
        <h2>价值回路</h2>
        <p>把模型投入、任务执行、知识贡献与业务确认价值放在同一个回路里，避免只看调用量而忽略实际产出。</p>
        <div class="analysis-tags">
          <span class="analysis-tag">INVEST</span>
          <span class="analysis-tag">EXECUTE</span>
          <span class="analysis-tag">ADOPT</span>
          <span class="analysis-tag analysis-tag-muted">VALIDATE</span>
        </div>
      </div>
      <div class="management-value-card">
        <span class="analysis-eyebrow">RETURN SIGNAL</span>
        <strong>{{ roiLabel }}</strong>
        <span>ROI · {{ summary.hasValidatedValue ? '业务方已确认' : '等待业务方确认' }}</span>
        <div class="management-value-meta"><span>净收益 <b>{{ formatMoney(summary.netBenefit) }}</b></span><span>确认价值 <b>{{ formatMoney(summary.validatedBusinessValue) }}</b></span></div>
      </div>
    </div>

    <div class="management-flow-strip">
      <div class="management-flow-step">
        <span class="management-flow-index">01</span>
        <div><span>投入</span><strong>{{ formatMoney(summary.totalCost) }}</strong><small>{{ formatNumber(summary.totalTokens) }} tokens</small></div>
      </div>
      <span class="management-flow-arrow">→</span>
      <div class="management-flow-step">
        <span class="management-flow-index">02</span>
        <div><span>执行</span><strong>{{ formatNumber(summary.totalTasks) }}</strong><small>{{ successPercent }}% tasks succeeded</small></div>
      </div>
      <span class="management-flow-arrow">→</span>
      <div class="management-flow-step">
        <span class="management-flow-index">03</span>
        <div><span>知识贡献</span><strong>{{ adoptionPercent }}% / {{ reusePercent }}%</strong><small>adoption / reuse</small></div>
      </div>
      <span class="management-flow-arrow">→</span>
      <div class="management-flow-step">
        <span class="management-flow-index">04</span>
        <div><span>业务价值</span><strong>{{ formatMoney(summary.validatedBusinessValue) }}</strong><small>{{ summary.hasValidatedValue ? 'validated' : 'not validated' }}</small></div>
      </div>
    </div>

    <el-alert
      v-if="!summary.hasValidatedValue"
      type="warning"
      :closable="false"
      show-icon
      title="尚未录入业务方确认价值，ROI 暂不计算。请在下方登记与 Agent 或发现端点关联的价值事件。"
    />

    <div class="metric-strip">
      <div class="small-box box-warning">
        <div><strong>{{ formatMoney(summary.totalCost) }}</strong><span>模型投入 USD</span></div>
        <el-icon><Money /></el-icon>
      </div>
      <div class="small-box box-success">
        <div><strong>{{ formatMoney(summary.validatedBusinessValue) }}</strong><span>确认业务价值 USD</span></div>
        <el-icon><TrendCharts /></el-icon>
      </div>
      <div class="small-box box-primary">
        <div><strong>{{ roiLabel }}</strong><span>投入产出比 ROI</span></div>
        <el-icon><DataAnalysis /></el-icon>
      </div>
      <div class="small-box box-info">
        <div><strong>{{ successPercent }}%</strong><span>任务成功率</span></div>
        <el-icon><CircleCheck /></el-icon>
      </div>
    </div>

    <div class="management-stat-grid">
      <div><span>任务数</span><strong>{{ formatNumber(summary.totalTasks) }}</strong></div>
      <div><span>模型调用</span><strong>{{ formatNumber(summary.totalModelCalls) }}</strong></div>
      <div><span>总 Token</span><strong>{{ formatNumber(summary.totalTokens) }}</strong></div>
      <div><span>净收益</span><strong>{{ formatMoney(summary.netBenefit) }}</strong></div>
      <div><span>知识采用率</span><strong>{{ adoptionPercent }}%</strong></div>
      <div><span>知识复用率</span><strong>{{ reusePercent }}%</strong></div>
    </div>

    <el-card shadow="never" class="panel management-leaderboard-panel">
      <template #header>
        <div class="panel-header">
          <span>Agent 价值排行</span>
          <span class="muted">优先展示已有业务价值归因的 Agent</span>
        </div>
      </template>
      <div v-if="topAgentUsage.length" class="management-leaderboard">
        <div v-for="(row, index) in topAgentUsage" :key="row.subjectKey" class="management-leader-row">
          <span class="management-rank">{{ String(index + 1).padStart(2, '0') }}</span>
          <div class="management-leader-name"><strong>{{ row.subjectName }}</strong><span>{{ row.subjectKey }}</span></div>
          <div class="management-leader-metric"><span>ROI</span><strong>{{ row.validatedValue > 0 ? row.roi.toFixed(4) : '—' }}</strong></div>
          <div class="management-leader-metric"><span>确认价值</span><strong>{{ formatMoney(row.validatedValue) }}</strong></div>
          <div class="management-leader-bars">
            <div><span>采用 {{ Math.round(row.knowledgeAdoptionRate * 100) }}%</span><i><b :style="{ width: `${Math.round(row.knowledgeAdoptionRate * 100)}%` }"></b></i></div>
            <div><span>复用 {{ Math.round(row.knowledgeReuseRate * 100) }}%</span><i><b class="reuse" :style="{ width: `${Math.round(row.knowledgeReuseRate * 100)}%` }"></b></i></div>
          </div>
        </div>
      </div>
      <el-empty v-else description="当前窗口没有可排行的 Agent 价值数据" />
    </el-card>

    <div class="management-two-column">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>Agent 投入产出与知识指标</span>
            <span class="muted">按窗口聚合真实任务、遥测和知识交互</span>
          </div>
        </template>
        <el-table :data="summary.agentUsage" v-loading="loading" size="small">
          <el-table-column label="Agent / 端点" min-width="170">
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
          <el-table-column label="ROI" width="85">
            <template #default="{ row }">{{ row.validatedValue > 0 ? row.roi.toFixed(4) : '—' }}</template>
          </el-table-column>
          <el-table-column label="采用 / 复用" width="112">
            <template #default="{ row }">{{ Math.round(row.knowledgeAdoptionRate * 100) }}% / {{ Math.round(row.knowledgeReuseRate * 100) }}%</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="summary.agentUsage.length === 0" description="当前窗口没有可归因的运行数据" />
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
            <el-form-item label="事件 Key" required>
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
            <el-form-item label="Agent ID">
              <el-input-number v-model="form.agentId" :min="1" :controls="false" style="width: 100%" placeholder="可选" />
            </el-form-item>
            <el-form-item label="发现端点 Key">
              <el-input v-model="form.endpointKey" placeholder="可选，至少填写 Agent 或端点之一" />
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
            <span class="muted">提交后会进入 ROI 聚合，并保留幂等事件 Key。</span>
            <el-button type="primary" :loading="saving" @click="recordValue">登记确认价值</el-button>
          </div>
        </el-form>
      </el-card>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>价值事件记录</span>
          <span class="muted">最近 {{ windowHours }} 小时的价值确认；原始知识正文不进入此界面</span>
        </div>
      </template>
      <el-table :data="valueEvents" v-loading="loading" row-key="id">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.observedAt) }}</template>
        </el-table-column>
        <el-table-column label="Agent / 端点" min-width="170">
          <template #default="{ row }">
            <strong>{{ row.agentId ? `Agent ${row.agentId}` : row.endpointKey }}</strong>
            <div class="muted">{{ row.eventKey }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="valueType" label="价值类型" width="150" />
        <el-table-column label="确认价值" width="125">
          <template #default="{ row }">{{ row.benefitMoney.toFixed(2) }} {{ row.currency }}</template>
        </el-table-column>
        <el-table-column label="确认人" width="150" prop="validatedBy" />
        <el-table-column label="说明" min-width="240">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="valueEvents.length === 0" description="尚未登记业务价值事件" />
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CircleCheck, DataAnalysis, Money, Refresh, TrendCharts } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { EapApi } from '@/api/eap'
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

const successPercent = computed(() => Math.round(summary.value.taskSuccessRate * 100))
const adoptionPercent = computed(() => Math.round(summary.value.knowledgeAdoptionRate * 100))
const reusePercent = computed(() => Math.round(summary.value.knowledgeReuseRate * 100))
const roiLabel = computed(() => summary.value.hasValidatedValue ? summary.value.roi.toFixed(4) : '—')
const topAgentUsage = computed(() => [...summary.value.agentUsage]
  .sort((left, right) => right.validatedValue - left.validatedValue || right.totalCost - left.totalCost)
  .slice(0, 3))

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
    ElMessage.warning('请填写事件 Key、价值类型和确认人')
    return
  }
  if (!form.agentId && !form.endpointKey.trim()) {
    ElMessage.warning('请至少关联 Agent ID 或发现端点 Key')
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

onMounted(load)
</script>
