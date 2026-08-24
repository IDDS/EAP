<template>
  <section class="page-grid safety-page">
    <div class="metric-strip">
      <div v-for="item in metrics" :key="item.label" class="small-box" :class="item.tone">
        <div>
          <strong>{{ item.value }}</strong>
          <span>{{ item.label }}</span>
        </div>
        <el-icon><component :is="item.icon" /></el-icon>
      </div>
    </div>

    <div class="security-flow">
      <div class="flow-step">
        <strong>Hook Event</strong>
        <span>采集工具调用、数据访问、制品写入和智能体委托。</span>
      </div>
      <div class="flow-step">
        <strong>Policy Decision</strong>
        <span>本地策略或 gensee sidecar 输出 ALLOW / ASK / DENY。</span>
      </div>
      <div class="flow-step">
        <strong>Lineage Audit</strong>
        <span>记录会话、父子智能体、控制动作和制品哈希。</span>
      </div>
    </div>

    <div class="safety-layout">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>gensee-crate 兼容安全评估</span>
            <el-button text :icon="Refresh" @click="load">刷新</el-button>
          </div>
        </template>
        <el-form label-position="top">
          <div class="form-row">
            <el-form-item label="会话 Key">
              <el-input v-model="form.sessionKey" />
            </el-form-item>
            <el-form-item label="事件类型">
              <el-select v-model="form.eventType">
                <el-option label="工具调用" value="TOOL_CALL" />
                <el-option label="数据访问" value="DATA_ACCESS" />
                <el-option label="制品写入" value="ARTIFACT_WRITE" />
                <el-option label="智能体委托" value="DELEGATION" />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="动作">
              <el-input v-model="form.action" />
            </el-form-item>
            <el-form-item label="目标">
              <el-input v-model="form.target" />
            </el-form-item>
          </div>
          <el-form-item label="摘要">
            <el-input v-model="form.payloadSummary" type="textarea" :rows="3" />
          </el-form-item>
          <el-button type="primary" :icon="Lock" @click="evaluate">执行安全评估</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>最近决策</template>
        <div class="decision-list">
          <div v-for="decision in summary.recentDecisions" :key="decision.id" class="decision-item">
            <el-tag :type="decisionType(decision.decision)">{{ decision.decision }}</el-tag>
            <div>
              <strong>{{ decision.reasonCode }}</strong>
              <p>{{ decision.reason }}</p>
              <span>{{ decision.controlActions }} · {{ decision.sidecarMode }}</span>
            </div>
          </div>
        </div>
      </el-card>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>安全会话</template>
      <el-table :data="sessions" v-loading="loading">
        <el-table-column prop="sessionKey" label="会话" min-width="180" />
        <el-table-column prop="objective" label="目标" min-width="260" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="createdAt" label="创建时间" width="220" />
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, Lock, Refresh, Warning } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { SafetyDecision, SafetyEventType, SafetySession, SafetySummary } from '@/types/domain'

const loading = ref(false)
const sessions = ref<SafetySession[]>([])
const summary = ref<SafetySummary>({
  sessionCount: 0,
  eventCount: 0,
  denyCount: 0,
  askCount: 0,
  criticalAlertCount: 0,
  recentDecisions: [],
})

const form = reactive({
  sessionKey: 'demo-incident-001',
  eventType: 'TOOL_CALL' as SafetyEventType,
  action: 'kubectl describe pod',
  target: 'prod/kubernetes/default/api-pod',
  payloadSummary: '运维智能体请求访问生产集群资源。',
})

const metrics = computed(() => [
  { label: '安全会话', value: summary.value.sessionCount, tone: 'box-primary', icon: Connection },
  { label: '运行事件', value: summary.value.eventCount, tone: 'box-info', icon: Refresh },
  { label: '拦截', value: summary.value.denyCount, tone: 'box-danger', icon: Lock },
  { label: '需确认', value: summary.value.askCount, tone: 'box-warning', icon: Warning },
])

async function load() {
  loading.value = true
  try {
    const [nextSummary, nextSessions] = await Promise.all([EapApi.getSafetySummary(), EapApi.listSafetySessions()])
    summary.value = nextSummary
    sessions.value = nextSessions
  } finally {
    loading.value = false
  }
}

async function evaluate() {
  await EapApi.createSafetySession({
    sessionKey: form.sessionKey,
    rootAgentId: 3,
    objective: '多智能体安全运行评估',
  })
  const result = await EapApi.evaluateSafetyEvent({ ...form, agentId: 3 })
  ElMessage.success(`决策：${result.decision.decision}`)
  await load()
}

function decisionType(decision: SafetyDecision) {
  return decision === 'ALLOW' ? 'success' : decision === 'ASK' ? 'warning' : 'danger'
}

onMounted(load)
</script>
