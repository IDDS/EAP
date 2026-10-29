<template>
  <section class="page-grid safety-page">
    <ConsolePageHeader />
    <el-alert v-if="pageError" :title="pageError" type="error" show-icon :closable="false" />
    <el-alert v-if="!canManage" title="当前账号可查看安全记录，没有执行安全评估的权限。" type="info" show-icon :closable="false" />
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
        <strong>调用事件</strong>
        <span>采集工具调用、数据访问、制品写入和智能体委托。</span>
      </div>
      <div class="flow-step">
        <strong>风险决策</strong>
        <span>平台本地规则给出 ALLOW / ASK / DENY，评估本身不执行目标操作。</span>
      </div>
      <div class="flow-step">
        <strong>审计证据</strong>
        <span>记录会话、父子智能体、控制动作和制品哈希。</span>
      </div>
    </div>

    <div class="safety-layout">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>操作风险评估</span>
            <el-button text :icon="Refresh" @click="load">刷新</el-button>
          </div>
        </template>
        <el-form v-if="canManage" label-position="top" :disabled="running || writeUncertain">
          <div class="form-row">
            <el-form-item label="安全会话">
              <el-select v-model="form.sessionKey" filterable placeholder="选择已登记会话" :disabled="loading || running || writeUncertain" aria-label="安全会话">
                <el-option v-for="session in sessions" :key="session.sessionKey" :label="`${session.sessionKey} · ${session.objective}`" :value="session.sessionKey" />
              </el-select>
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
              <el-input v-model="form.action" maxlength="128" placeholder="描述需要评估的操作" />
            </el-form-item>
            <el-form-item label="目标">
              <el-input v-model="form.target" maxlength="256" placeholder="输入本次操作的目标资源" />
            </el-form-item>
          </div>
          <el-form-item label="摘要">
            <el-input v-model="form.payloadSummary" type="textarea" :rows="3" maxlength="1024" />
          </el-form-item>
          <p v-if="selectedSession" class="muted">{{ selectedSession.rootAgentId == null ? '此会话未关联智能体' : `会话关联智能体 #${selectedSession.rootAgentId}` }}；本次评估不会执行目标操作。</p>
          <p v-else-if="!loading && !pageError && !sessions.length" class="muted">暂无安全会话，请先完成运行接入并登记会话。</p>
          <el-button type="primary" :icon="Lock" :loading="running" :disabled="loading || running || writeUncertain || !selectedSession" @click="evaluate">执行安全评估</el-button>
        </el-form>
        <p v-else class="muted">可在右侧查看最近决策，或在下方查询已登记的安全会话。</p>
        <el-alert v-if="formError" :title="formError" :type="writeUncertain ? 'warning' : 'error'" show-icon :closable="false" />
        <el-button v-if="canManage && writeUncertain" @click="acknowledgeUncertain">已核对记录，允许再次评估</el-button>
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
        <el-empty v-if="!loading && !pageError && !summary.recentDecisions.length" description="暂无安全决策" :image-size="56" />
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
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Connection, Lock, Refresh, Warning } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import type { SafetyDecision, SafetyEventType, SafetySession, SafetySummary } from '@/types/domain'

const loading = ref(false)
const permissions = usePermissions()
const canManage = computed(() => permissions.can('viewConsole') && permissions.can('manageSafety'))
const running = ref(false), writeUncertain = ref(false), pageError = ref(''), formError = ref('')
let accessEpoch = 0, loadSequence = 0, disposed = false
const sessions = ref<SafetySession[]>([])
const emptySummary = (): SafetySummary => ({
  sessionCount: 0,
  eventCount: 0,
  denyCount: 0,
  askCount: 0,
  criticalAlertCount: 0,
  recentDecisions: [],
})
const summary = ref<SafetySummary>(emptySummary())

const form = reactive({
  sessionKey: '',
  eventType: 'TOOL_CALL' as SafetyEventType,
  action: '',
  target: '',
  payloadSummary: '',
})
const selectedSession = computed(() => sessions.value.find(session => session.sessionKey === form.sessionKey))
const currentAccess = (epoch: number) => !disposed && epoch === accessEpoch && permissions.can('viewConsole')

const metrics = computed(() => [
  { label: '安全会话', value: summary.value.sessionCount, tone: 'box-primary', icon: Connection },
  { label: '运行事件', value: summary.value.eventCount, tone: 'box-info', icon: Refresh },
  { label: '拦截', value: summary.value.denyCount, tone: 'box-danger', icon: Lock },
  { label: '需确认', value: summary.value.askCount, tone: 'box-warning', icon: Warning },
])

async function load() {
  if (disposed || !permissions.can('viewConsole')) return
  const epoch = accessEpoch, sequence = ++loadSequence
  loading.value = true
  pageError.value = ''
  sessions.value = []
  summary.value = emptySummary()
  try {
    const [nextSummary, nextSessions] = await Promise.all([EapApi.getSafetySummary(), EapApi.listSafetySessions()])
    if (!currentAccess(epoch) || sequence !== loadSequence) return
    summary.value = nextSummary
    sessions.value = nextSessions
  } catch {
    if (currentAccess(epoch) && sequence === loadSequence) pageError.value = '安全记录加载失败，请刷新重试。'
  } finally {
    if (currentAccess(epoch) && sequence === loadSequence) loading.value = false
  }
}

async function evaluate() {
  if (disposed || !canManage.value || loading.value || running.value || writeUncertain.value) return
  const session = selectedSession.value
  if (!session || !form.action.trim() || !form.target.trim()) {
    formError.value = '请选择已登记会话，并填写动作和目标。'
    return
  }
  const epoch = accessEpoch
  const payload = { ...form, action: form.action.trim(), target: form.target.trim(), agentId: session.rootAgentId ?? undefined }
  running.value = true
  formError.value = ''
  try {
    const result = await EapApi.evaluateSafetyEvent(payload)
    if (!currentAccess(epoch) || !canManage.value) return
    ElMessage.success(`决策：${result.decision.decision}`)
    await load()
  } catch {
    if (currentAccess(epoch) && canManage.value) {
      writeUncertain.value = true
      formError.value = `会话 ${payload.sessionKey} 的评估结果未确认，请先刷新并核对最近决策；不会自动重试。`
    }
  } finally {
    if (currentAccess(epoch)) running.value = false
  }
}

function acknowledgeUncertain() {
  if (disposed || !canManage.value || running.value) return
  writeUncertain.value = false
  formError.value = ''
}

function decisionType(decision: SafetyDecision) {
  return decision === 'ALLOW' ? 'success' : decision === 'ASK' ? 'warning' : 'danger'
}

watch(() => [permissions.state.user, permissions.can('viewConsole'), permissions.can('manageSafety')], () => {
  ++accessEpoch
  ++loadSequence
  sessions.value = []
  summary.value = emptySummary()
  Object.assign(form, { sessionKey: '', eventType: 'TOOL_CALL', action: '', target: '', payloadSummary: '' })
  loading.value = running.value = writeUncertain.value = false
  pageError.value = formError.value = ''
  void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; ++accessEpoch; ++loadSequence })
</script>

<style scoped>
.safety-page .el-alert { margin-bottom: 12px; }
.safety-page .el-form .el-select { width: 100%; }
.safety-page .small-box span { color: var(--console-muted); }
.safety-page .small-box::before { display: none; }
@media (max-width: 760px) {
  .safety-page .security-flow, .safety-page .safety-layout, .safety-page .form-row { grid-template-columns: minmax(0, 1fr); }
}
</style>
