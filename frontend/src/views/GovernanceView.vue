<template>
  <section class="page-grid governance-page">
    <div class="toolbar governance-heading">
      <div class="toolbar-title">
        <span class="interface-eyebrow">治理 · 策略</span>
        <strong>智能体治理</strong>
        <span>查看风险事件、执行预检与管理策略；仅保留分类元数据，不保存敏感正文</span>
      </div>
      <div class="button-row">
        <el-select v-model="windowHours" aria-label="治理统计时间范围" style="width: 150px" @change="load">
          <el-option label="最近 1 小时" :value="1" />
          <el-option label="最近 24 小时" :value="24" />
          <el-option label="最近 7 天" :value="168" />
          <el-option label="最近 30 天" :value="720" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="governance-sections" role="group" aria-label="治理界面分区">
      <button type="button" :aria-pressed="activeSection === 'overview'" aria-label="治理总览" @click="activeSection = 'overview'"><strong>治理总览</strong><small>风险与执行状态</small></button>
      <button type="button" :aria-pressed="activeSection === 'preflight'" aria-label="实时预检" @click="activeSection = 'preflight'"><strong>实时预检</strong><small>校验请求与审批</small></button>
      <button type="button" :aria-pressed="activeSection === 'audit'" aria-label="事件审计" @click="activeSection = 'audit'"><strong>事件审计</strong><small>追踪分类与动作</small></button>
      <button type="button" :aria-pressed="activeSection === 'policies'" aria-label="治理策略" @click="activeSection = 'policies'"><strong>治理策略</strong><small>管理规则与版本</small></button>
    </div>

    <dl class="governance-status-bar" aria-label="当前治理状态">
      <div><dt>执行模式</dt><dd>{{ enforcement.enforcementEnabled ? '强制' : '观察' }}</dd></div>
      <div><dt>模型网关</dt><dd>{{ gatewayStatusLabel }}</dd></div>
      <div><dt>启用策略</dt><dd>{{ enforcement.enabledPolicies }}</dd></div>
      <div><dt>待审批</dt><dd>{{ enforcement.pendingApprovals }}</dd></div>
    </dl>

    <div v-if="drilldownLabel" class="drilldown-context">
      <span class="drilldown-context-kicker">筛选条件</span>
      <strong>{{ drilldownLabel }}</strong>
      <span>当前治理事件和决策已按上下文收敛</span>
      <el-button link type="primary" @click="clearDrilldown">清除筛选</el-button>
    </div>

    <el-alert
      v-if="activeSection === 'overview' && !summary.lastEventAt"
      type="warning"
      :closable="false"
      show-icon
      title="尚未收到治理数据事件。请由模型网关、DLP 或智能体运行时只上报分类、计数和策略动作。"
    />

    <el-alert
      v-if="!gateway.configured"
      type="warning"
      :closable="false"
      show-icon
      title="模型网关尚未配置上游，智能体暂不能经由统一入口调用模型。"
    />

    <div v-show="activeSection === 'overview'" class="governance-overview-heading"><h2>事件概览</h2><span>最近 {{ windowHours }} 小时</span></div>

    <div v-show="activeSection === 'overview'" class="metric-strip">
      <div class="small-box box-primary">
        <div><strong>{{ formatNumber(summary.modelInputEvents) }}</strong><span>进入模型事件</span></div>
        <el-icon><DataAnalysis /></el-icon>
      </div>
      <div class="small-box box-warning">
        <div><strong>{{ formatNumber(summary.internalInfoEvents) }}</strong><span>内部信息事件</span></div>
        <el-icon><OfficeBuilding /></el-icon>
      </div>
      <div class="small-box box-danger">
        <div><strong>{{ formatNumber(summary.companySecretEvents) }}</strong><span>公司保密事件</span></div>
        <el-icon><Lock /></el-icon>
      </div>
      <div class="small-box box-success">
        <div><strong>{{ formatNumber(summary.blockedEvents + summary.redactedEvents) }}</strong><span>阻断 / 脱敏</span></div>
        <el-icon><CircleCheck /></el-icon>
      </div>
    </div>

    <div v-show="activeSection === 'overview'" class="governance-stat-grid">
      <div><span>内部信息占比</span><strong>{{ internalPercent }}%</strong></div>
      <div><span>保密信息占比</span><strong>{{ secretPercent }}%</strong></div>
      <div><span>阻断率</span><strong>{{ blockPercent }}%</strong></div>
      <div><span>脱敏事件</span><strong>{{ summary.redactedEvents }}</strong></div>
      <div><span>待审批事件</span><strong>{{ summary.approvalRequiredEvents }}</strong></div>
      <div><span>治理 Token</span><strong>{{ formatNumber(summary.totalTokens) }}</strong></div>
    </div>

    <details v-show="activeSection === 'overview'" class="governance-integration-details">
      <summary>执行详情与接入说明<span>预检链路、默认策略与网关配置</span></summary>
      <div class="governance-integration-content">
        <el-alert
          v-if="enforcement.enforcementEnabled"
          type="success"
          :closable="false"
          show-icon
          title="实时强制治理已启用：模型网关必须在发送请求前调用预检接口，并严格执行返回的网关指令。"
        />

        <div class="governance-flow-strip">
          <template v-for="(step, index) in governanceFlow" :key="step.index">
            <div class="governance-flow-step" :class="{ 'is-ready': step.ready }">
              <span class="governance-flow-index">{{ step.index }}</span>
              <div><span>{{ step.label }}</span><strong>{{ step.value }}</strong><small>{{ step.detail }}</small></div>
            </div>
            <span v-if="index < governanceFlow.length - 1" class="governance-flow-arrow">→</span>
          </template>
        </div>

        <div class="governance-enforcement-strip">
          <div>
            <span>保密信息默认策略</span>
            <strong>{{ enforcement.failClosedOnSecret ? '默认阻断' : '按策略' }}</strong>
          </div>
          <div><span>预检决策</span><strong>{{ enforcement.preflightDecisions }}</strong></div>
          <div><span>强制阻断</span><strong>{{ enforcement.blockedDecisions }}</strong></div>
          <div><span>脱敏 / 审批</span><strong>{{ enforcement.redactionDecisions }} / {{ enforcement.approvalDecisions }}</strong></div>
          <div><span>网关供应商</span><strong>{{ gateway.provider }}</strong></div>
          <div><span>客户端鉴权</span><strong>{{ gateway.authenticationConfigured ? '就绪' : '缺失' }}</strong></div>
        </div>

        <el-card shadow="never" class="panel gateway-integration-card">
          <template #header>
            <div class="panel-header">
              <span>生产接入入口</span>
              <el-tag :type="gateway.configured ? 'success' : 'warning'">{{ gateway.configured ? '可接入' : '待配置' }}</el-tag>
            </div>
          </template>
          <div class="gateway-integration-grid">
            <div><span>Base URL</span><code>/api/gateway/v1</code></div>
            <div><span>Chat Completions</span><code>POST /api/gateway/v1/chat/completions</code></div>
            <div><span>智能体归属</span><code>X-EAP-Agent-Id</code></div>
            <div><span>端点归属</span><code>X-EAP-Endpoint-Key</code></div>
            <div><span>分类器</span><code>{{ gateway.classifier }}</code></div>
            <div><span>超时</span><code>{{ gateway.readTimeoutMs }} ms</code></div>
          </div>
          <p class="muted">兼容非流式与安全缓冲式 SSE Chat Completions；请求和输出分别执行治理预检，正文不落库。外部 DLP 可通过 X-EAP-Sensitivity、X-EAP-Company-Secret、X-EAP-Detected-Types 提交分类标签。</p>
        </el-card>

      </div>
    </details>

    <div v-show="activeSection === 'preflight'" class="governance-two-column">
      <el-card shadow="never" class="panel governance-preflight-panel">
        <template #header>
          <div class="panel-header">
            <span>模型网关实时预检</span>
            <el-tag size="small" type="danger" effect="plain">调用模型前执行</el-tag>
          </div>
        </template>
        <el-form label-position="top" class="governance-event-form">
          <div class="form-row">
            <el-form-item label="事件标识" required>
              <el-input v-model="form.eventKey" placeholder="例如 gateway-event-001" />
            </el-form-item>
            <el-form-item label="数据方向" required>
              <el-select v-model="form.direction" style="width: 100%">
                <el-option label="输入模型" value="INPUT_TO_MODEL" />
                <el-option label="模型输出" value="OUTPUT_FROM_MODEL" />
                <el-option label="工具输入" value="TOOL_INPUT" />
                <el-option label="工具输出" value="TOOL_OUTPUT" />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="智能体 ID">
              <el-input-number v-model="form.agentId" :min="1" :controls="false" style="width: 100%" />
            </el-form-item>
            <el-form-item label="发现端点标识">
              <el-input v-model="form.endpointKey" placeholder="至少填写智能体或端点之一" />
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="数据类别" required>
              <el-input v-model="form.dataCategory" placeholder="例如 CUSTOMER_DATA / SOURCE_CODE" />
            </el-form-item>
            <el-form-item label="敏感级别" required>
              <el-select v-model="form.sensitivity" style="width: 100%">
                <el-option label="公开" value="PUBLIC" />
                <el-option label="内部" value="INTERNAL" />
                <el-option label="机密" value="CONFIDENTIAL" />
                <el-option label="秘密" value="SECRET" />
                <el-option label="严格限制" value="RESTRICTED" />
              </el-select>
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="分类器" required>
              <el-input v-model="form.classifier" placeholder="例如 enterprise-dlp-v1" />
            </el-form-item>
            <el-form-item label="检测类型">
              <el-input v-model="form.detectedTypesText" placeholder="PHONE,ID_CARD,SOURCE_CODE" />
            </el-form-item>
          </div>
          <div class="form-row">
            <el-form-item label="Token 数">
              <el-input-number v-model="form.tokenCount" :min="0" :controls="false" style="width: 100%" />
            </el-form-item>
            <el-form-item label="载荷字节数">
              <el-input-number v-model="form.payloadBytes" :min="0" :controls="false" style="width: 100%" />
            </el-form-item>
          </div>
          <div class="governance-switch-row">
            <el-checkbox v-model="form.internalData">包含内部信息</el-checkbox>
            <el-checkbox v-model="form.companySecret">涉及公司保密信息</el-checkbox>
          </div>
          <div class="button-row governance-submit-row">
            <span class="muted">不提交正文；返回 BLOCK 时网关必须停止模型请求。</span>
            <el-button type="danger" :loading="saving" :disabled="!permissions.can('governance')" @click="runPreflight">执行实时预检</el-button>
          </div>
        </el-form>
      </el-card>
      <el-card shadow="never" class="panel governance-risk-panel">
        <template #header>
          <div class="panel-header">
            <span>智能体 / 端点风险分布</span>
            <span class="muted">仅聚合分类元数据</span>
          </div>
        </template>
        <PaginatedTable :reset-key="`${route.fullPath}:${windowHours}`" :data="visibleSubjectUsage" v-loading="loading" size="small">
          <el-table-column label="智能体 / 端点" min-width="170">
            <template #default="{ row }">
              <strong>{{ row.subjectName }}</strong>
              <div class="muted">{{ row.subjectKey }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="modelInputs" label="进模型" width="78" />
          <el-table-column prop="internalEvents" label="内部" width="68" />
          <el-table-column prop="companySecretEvents" label="保密" width="68" />
          <el-table-column prop="blockedEvents" label="阻断" width="68" />
          <el-table-column prop="redactedEvents" label="脱敏" width="68" />
          <el-table-column label="Token" width="100">
            <template #default="{ row }">{{ formatNumber(row.tokenCount) }}</template>
          </el-table-column>
        </PaginatedTable>
        <el-empty v-if="summary.subjectUsage.length === 0" description="当前窗口没有治理数据" />
      </el-card>

    </div>

    <el-card v-show="activeSection === 'preflight'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>最近预检与执行指令</span>
          <span class="muted">放行 · 脱敏后复检 · 请求审批 · 阻断</span>
        </div>
      </template>
      <HistoryTable ref="decisionHistory" :labels="{PENDING: '待审批'}" :fetch-page="fetchDecisionHistory" :reset-key="route.fullPath" :statuses="['NOT_REQUIRED','PENDING','APPROVED','REJECTED']" placeholder="搜索决策标识、模型或分类器">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.decidedAt) }}</template>
        </el-table-column>
        <el-table-column label="决策标识" min-width="190">
          <template #default="{ row }">
            <strong>{{ row.decisionKey }}</strong>
            <div class="muted">事件 #{{ row.dataEventId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="网关指令" width="210">
          <template #default="{ row }"><el-tag :type="directiveType(row.gatewayDirective)">{{ gatewayDirectiveLabel(row.gatewayDirective) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="原请求结论" width="125">
          <template #default="{ row }"><el-tag :type="row.requestAllowed ? 'success' : 'danger'">{{ row.requestAllowed ? '原允许' : '原停止' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="命中策略" min-width="190">
          <template #default="{ row }">
            <span>{{ row.matchedPolicyCount }} 条</span>
            <div class="muted">{{ matchedPolicyNames(row.matchedPolicies) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="决策依据" min-width="230">
          <template #default="{ row }">{{ row.reasonCodes.join(', ') }}</template>
        </el-table-column>
        <el-table-column label="模型" min-width="150">
          <template #default="{ row }"><div class="model-table-name"><ModelGlyph :provider="row.provider" size="xs" /><span><strong>{{ row.modelName || '—' }}</strong><small>{{ row.provider || '企业模型' }}</small></span></div></template>
        </el-table-column>
        <el-table-column label="审批状态" width="145">
          <template #default="{ row }"><el-tag :type="approvalType(row.approvalStatus)">{{ approvalStatusLabel(row.approvalStatus) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="审批依据" min-width="190">
          <template #default="{ row }"><span :class="{ muted: row.approvalContextState === 'NOT_APPLICABLE' }">{{ approvalContextLabel(row) }}</span></template>
        </el-table-column>
        <el-table-column label="审批操作" width="190" fixed="right">
          <template #default="{ row }">
            <div v-if="row.approvalStatus === 'PENDING' && permissions.can('governance')" class="button-row compact-actions">
              <el-button size="small" type="success" :disabled="!canApprove(row)" @click="approveDecision(row)">批准</el-button>
              <el-button size="small" type="danger" plain :disabled="saving" @click="rejectDecision(row)">拒绝</el-button>
            </div>
            <span v-else class="muted">{{ row.approvedBy ? `${row.approvedBy} · ${formatTime(row.approvedAt)}` : row.approvalStatus === 'PENDING' ? '需要治理权限' : '无需审批' }}</span>
          </template>
        </el-table-column>
      </HistoryTable>
      <el-alert v-if="approvalMutationError" :title="approvalMutationError" type="warning" :closable="false" show-icon />
      <p class="muted">审批状态保留原记录；批准和再次调用前会核对当前策略。策略改变或依据未记录时，请使用新的决策标识重新预检。</p>
    </el-card>

    <el-card v-show="activeSection === 'audit'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>治理事件历史</span>
          <span class="muted">分类、敏感级别和策略动作；不展示信息正文</span>
        </div>
      </template>
      <HistoryTable ref="eventHistory" :fetch-page="fetchEventHistory" :reset-key="route.fullPath" :statuses="['ALLOW','WARN','REDACT','BLOCK','APPROVAL_REQUIRED']" placeholder="搜索事件标识、Trace、数据类别、端点">
        <el-table-column label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.observedAt) }}</template>
        </el-table-column>
        <el-table-column label="智能体 / 端点" min-width="170">
          <template #default="{ row }">
            <strong>{{ row.agentId ? `智能体 ${row.agentId}` : row.endpointKey }}</strong>
            <div class="muted">{{ row.eventKey }}</div>
          </template>
        </el-table-column>
        <el-table-column label="方向" width="150"><template #default="{ row }">{{ governanceDirectionLabel(row.direction) }}</template></el-table-column>
        <el-table-column prop="dataCategory" label="数据类别" min-width="150" />
        <el-table-column label="敏感级别" width="115">
          <template #default="{ row }"><el-tag :type="sensitivityType(row.sensitivity)">{{ sensitivityLabel(row.sensitivity) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="策略动作" width="135">
          <template #default="{ row }"><el-tag :type="actionType(row.action)">{{ governanceActionLabel(row.action) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="内部 / 保密" width="120">
          <template #default="{ row }">{{ row.internalData ? '是' : '否' }} / {{ row.companySecret ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="Token" width="90">
          <template #default="{ row }">{{ formatNumber(row.tokenCount) }}</template>
        </el-table-column>
        <el-table-column label="钻取" width="92" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="openPhysical(row)">物理观测</el-button></template>
        </el-table-column>
      </HistoryTable>
      <el-empty v-if="visibleEvents.length === 0" description="尚未收到治理数据事件" />
    </el-card>

    <GovernancePolicies v-if="activeSection === 'policies'" :policy-id="contextPolicyId" @changed="load" />

  </section>
</template>

<script setup lang="ts">
import HistoryTable, { type HistoryParams } from '@/components/common/HistoryTable.vue'
import GovernancePolicies from '@/components/GovernancePolicies.vue'
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { CircleCheck, DataAnalysis, Lock, OfficeBuilding, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import ModelGlyph from '@/components/ModelGlyph.vue'
import { useRoute, useRouter } from 'vue-router'
import type {
  GovernanceDataAction,
  GovernanceDataDirection,
  GovernanceDataEvent,
  GovernanceEnforcementStatus,
  GovernanceGatewayDirective,
  GovernancePreflightDecision,
  GovernanceDataSensitivity,
  GovernanceDataSummary,
  GovernanceApprovalStatus,
  ModelGatewayStatus,
} from '@/types/domain'

const emptySummary: GovernanceDataSummary = {
  windowHours: 24,
  totalEvents: 0,
  modelInputEvents: 0,
  internalInfoEvents: 0,
  companySecretEvents: 0,
  allowedEvents: 0,
  warnedEvents: 0,
  redactedEvents: 0,
  blockedEvents: 0,
  approvalRequiredEvents: 0,
  internalExposureRate: 0,
  secretExposureRate: 0,
  blockRate: 0,
  totalTokens: 0,
  totalPayloadBytes: 0,
  subjectUsage: [],
}

const emptyEnforcement: GovernanceEnforcementStatus = {
  enforcementEnabled: false,
  failClosedOnSecret: false,
  enabledPolicies: 0,
  preflightDecisions: 0,
  blockedDecisions: 0,
  redactionDecisions: 0,
  approvalDecisions: 0,
  pendingApprovals: 0,
}

const emptyGateway: ModelGatewayStatus = {
  enabled: false,
  configured: false,
  authenticationConfigured: false,
  provider: 'openai-compatible',
  classifier: 'eap-heuristic-v1',
  readTimeoutMs: 120000,
  maxResponseBytes: 4194304,
  pricingConfigured: false,
  checkedAt: '',
}

const events = ref<GovernanceDataEvent[]>([])
const summary = ref<GovernanceDataSummary>({ ...emptySummary })
const enforcement = ref<GovernanceEnforcementStatus>({ ...emptyEnforcement })
const decisions = ref<GovernancePreflightDecision[]>([])
const gateway = ref<ModelGatewayStatus>({ ...emptyGateway })
const gatewayStatusLabel = computed(() => gateway.value.configured ? '就绪' : gateway.value.enabled ? '配置错误' : '关闭')
const windowHours = ref(24)
const activeSection = ref<'overview' | 'preflight' | 'audit' | 'policies'>('overview')
const loading = ref(false)
const saving = ref(false)
const permissions = usePermissions()
const approvalMutationError = ref('')
let approvalAlive = true
onBeforeUnmount(() => { approvalAlive = false })
const form = reactive({
  eventKey: '',
  agentId: undefined as number | undefined,
  endpointKey: '',
  direction: 'INPUT_TO_MODEL' as GovernanceDataDirection,
  dataCategory: '',
  sensitivity: 'INTERNAL' as GovernanceDataSensitivity,
  internalData: true,
  companySecret: false,
  tokenCount: 0,
  payloadBytes: 0,
  classifier: 'manual-dlp-check',
  detectedTypesText: '',
})
const route = useRoute()
const router = useRouter()
const contextAgentId = computed(() => {
  const value = Number(route.query.agentId)
  return Number.isFinite(value) && value > 0 ? value : undefined
})
const contextEndpointKey = computed(() => String(route.query.endpointKey || '').trim())
const contextEventKey = computed(() => String(route.query.eventKey || '').trim())
const contextPolicyId = computed(() => {
  const value = Number(route.query.policyId)
  return Number.isFinite(value) && value > 0 ? value : undefined
})
const drilldownLabel = computed(() => {
  const parts: string[] = []
  if (contextAgentId.value) parts.push(`智能体 #${contextAgentId.value}`)
  if (contextEndpointKey.value) parts.push(`端点 ${contextEndpointKey.value}`)
  if (contextEventKey.value) parts.push(`事件 ${contextEventKey.value}`)
  if (contextPolicyId.value) parts.push(`策略 #${contextPolicyId.value}`)
  return parts.join(' · ')
})

watch([contextPolicyId, contextEventKey], ([policyId, eventKey]) => {
  if (policyId) activeSection.value = 'policies'
  else if (eventKey) activeSection.value = 'audit'
}, { immediate: true })

const internalPercent = computed(() => Math.round(summary.value.internalExposureRate * 100))
const secretPercent = computed(() => Math.round(summary.value.secretExposureRate * 100))
const blockPercent = computed(() => Math.round(summary.value.blockRate * 100))
const governanceFlow = computed(() => [
  {
    index: '01',
    label: '分类',
    value: gateway.value.configured ? '就绪' : '等待配置',
    detail: `${summary.value.totalEvents} 个治理事件`,
    ready: gateway.value.configured,
  },
  {
    index: '02',
    label: '预检',
    value: enforcement.value.preflightDecisions > 0 ? '运行中' : '空闲',
    detail: `${enforcement.value.preflightDecisions} 次决策`,
    ready: enforcement.value.preflightDecisions > 0,
  },
  {
    index: '03',
    label: '策略',
    value: enforcement.value.enabledPolicies > 0 ? '启用' : '暂无',
    detail: `${enforcement.value.enabledPolicies} 条启用策略`,
    ready: enforcement.value.enabledPolicies > 0,
  },
  {
    index: '04',
    label: '网关指令',
    value: enforcement.value.enforcementEnabled ? '强制' : '观察',
    detail: `${enforcement.value.blockedDecisions} 次阻断`,
    ready: enforcement.value.enforcementEnabled,
  },
])

const visibleSubjectUsage = computed(() => summary.value.subjectUsage.filter(subject => {
  if (contextAgentId.value && subject.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && subject.endpointKey !== contextEndpointKey.value) return false
  return true
}))
const visibleEvents = computed(() => events.value.filter(event => {
  if (contextAgentId.value && event.agentId !== contextAgentId.value) return false
  if (contextEndpointKey.value && event.endpointKey !== contextEndpointKey.value) return false
  if (contextEventKey.value && event.eventKey !== contextEventKey.value) return false
  return true
}))
const visibleDecisions = computed(() => {
  if (!drilldownLabel.value) return decisions.value
  const eventIds = new Set(visibleEvents.value.map(event => event.id))
  return decisions.value.filter(decision => eventIds.has(decision.dataEventId))
})

function openPhysical(row: GovernanceDataEvent) {
  router.push({ path: '/physical', query: {
    agentId: row.agentId ? String(row.agentId) : undefined,
    endpointKey: row.endpointKey,
    traceId: row.traceId,
  } })
}

function clearDrilldown() {
  router.replace({ path: '/governance', query: {} })
}

const decisionHistory = ref<InstanceType<typeof HistoryTable>>()
function fetchDecisionHistory(params:HistoryParams) {return EapApi.pageDecisionHistory({...params,agentId:contextAgentId.value || undefined,endpointKey:contextEndpointKey.value || undefined,eventKey:contextEventKey.value || undefined})}
const eventHistory = ref<InstanceType<typeof HistoryTable>>()
function fetchEventHistory(params:HistoryParams) { return EapApi.pageGovernanceEvents({...params,agentId:contextAgentId.value || undefined,endpointKey:contextEndpointKey.value || undefined,eventKey:contextEventKey.value || undefined}) }
async function load() {
  approvalMutationError.value = ''
  loading.value = true
  try {
    const [nextSummary, nextEvents, nextEnforcement, nextDecisions, nextGateway] = await Promise.all([
      EapApi.getGovernanceDataSummary(windowHours.value),
      EapApi.listGovernanceDataEvents(100),
      EapApi.getGovernanceEnforcementStatus(),
      EapApi.listGovernancePreflightDecisions(100),
      EapApi.getModelGatewayStatus(),
    ])
    summary.value = nextSummary
    events.value = nextEvents
    enforcement.value = nextEnforcement
    decisions.value = nextDecisions
    gateway.value = nextGateway
  } finally {
    loading.value = false
    void eventHistory.value?.reload()
    void decisionHistory.value?.reload()
  }
}

async function runPreflight() {
  if (!approvalAlive || saving.value || !permissions.can('governance')) return
  const user = permissions.state.user
  if (!form.eventKey.trim() || !form.dataCategory.trim()) {
    ElMessage.warning('请填写事件标识和数据类别')
    return
  }
  if (!form.agentId && !form.endpointKey.trim()) {
    ElMessage.warning('请至少关联智能体 ID 或发现端点标识')
    return
  }
  saving.value = true
  try {
    const result = await EapApi.evaluateGovernancePreflight({
      decisionKey: `decision-${form.eventKey.trim()}`,
      eventKey: form.eventKey.trim(),
      agentId: form.agentId,
      endpointKey: form.endpointKey.trim() || undefined,
      direction: form.direction,
      dataCategory: form.dataCategory.trim(),
      sensitivity: form.sensitivity,
      internalData: form.internalData,
      companySecret: form.companySecret,
      tokenCount: form.tokenCount,
      payloadBytes: form.payloadBytes,
      classifier: form.classifier.trim() || 'unknown-classifier',
      provider: 'model-gateway',
      modelName: 'pending-routing',
      detectedTypes: form.detectedTypesText.split(',').map(value => value.trim()).filter(Boolean),
    })
    if (!approvalAlive || user !== permissions.state.user || !permissions.can('governance')) return
    const message = `网关指令：${gatewayDirectiveLabel(result.gatewayDirective)}`
    if (result.requestAllowed) ElMessage.success(message)
    else ElMessage.error(message)
    Object.assign(form, { eventKey: '', agentId: undefined, endpointKey: '', dataCategory: '', tokenCount: 0, payloadBytes: 0, detectedTypesText: '' })
    await load()
  } finally {
    saving.value = false
  }
}

function canApprove(row: GovernancePreflightDecision) {
  return approvalAlive && !saving.value && permissions.can('governance')
    && row.approvalStatus === 'PENDING' && row.approvalContextState === 'CURRENT'
}

function approvalContextLabel(row: GovernancePreflightDecision) {
  if (!['PENDING', 'APPROVED'].includes(row.approvalStatus)) return '不适用'
  if (row.approvalContextState === 'CURRENT') return '当前策略一致'
  if (row.approvalContextState === 'CHANGED') return '策略已变更，请重新预检'
  return '审批依据未记录，请重新预检'
}

async function approveDecision(row: GovernancePreflightDecision) {
  if (!canApprove(row)) return
  await mutateApproval(row, true)
}

async function rejectDecision(row: GovernancePreflightDecision) {
  await mutateApproval(row, false)
}

async function mutateApproval(row: GovernancePreflightDecision, approve: boolean) {
  if (!approvalAlive || saving.value || !permissions.can('governance') || row.approvalStatus !== 'PENDING') return
  if (approve && !canApprove(row)) return
  const user = permissions.state.user
  saving.value = true
  approvalMutationError.value = ''
  try {
    if (approve) await EapApi.approveGovernanceDecision(row.decisionKey, { approver: 'governance-console', reason: '控制台人工审批通过' })
    else await EapApi.rejectGovernanceDecision(row.decisionKey, { approver: 'governance-console', reason: '控制台人工拒绝' })
    if (!approvalAlive || user !== permissions.state.user || !permissions.can('governance')) return
    ElMessage.success(approve ? '审批已记录；再次调用时仍会核对策略与原请求' : '模型调用已拒绝')
    await load()
  } catch {
    if (!approvalAlive || user !== permissions.state.user || !permissions.can('governance')) return
    row.approvalContextState = 'UNRECORDED'
    approvalMutationError.value = '审批提交未确认。请刷新核对原记录，不要直接重发；策略已变更时须重新预检。'
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

function sensitivityType(value: GovernanceDataSensitivity) {
  if (value === 'SECRET' || value === 'RESTRICTED') return 'danger'
  if (value === 'CONFIDENTIAL') return 'warning'
  if (value === 'INTERNAL') return 'info'
  return 'success'
}

function actionType(value: GovernanceDataAction) {
  if (value === 'BLOCK') return 'danger'
  if (value === 'REDACT') return 'success'
  if (value === 'WARN' || value === 'APPROVAL_REQUIRED') return 'warning'
  return 'info'
}

function directiveType(value: GovernanceGatewayDirective) {
  if (value === 'BLOCK') return 'danger'
  if (value === 'REDACT_AND_RECHECK') return 'success'
  if (value === 'REQUEST_APPROVAL' || value === 'PROCEED_WITH_WARNING') return 'warning'
  return 'info'
}

function approvalType(value: GovernanceApprovalStatus) {
  if (value === 'APPROVED') return 'success'
  if (value === 'REJECTED') return 'danger'
  if (value === 'PENDING') return 'warning'
  return 'info'
}

function sensitivityLabel(value: GovernanceDataSensitivity) {
  const labels: Record<GovernanceDataSensitivity, string> = {
    PUBLIC: '公开',
    INTERNAL: '内部',
    CONFIDENTIAL: '机密',
    SECRET: '秘密',
    RESTRICTED: '严格限制',
  }
  return labels[value] || value
}

function governanceDirectionLabel(value: GovernanceDataDirection) {
  const labels: Record<GovernanceDataDirection, string> = {
    INPUT_TO_MODEL: '进入模型',
    OUTPUT_FROM_MODEL: '模型输出',
    TOOL_INPUT: '工具输入',
    TOOL_OUTPUT: '工具输出',
  }
  return labels[value] || value
}

function governanceActionLabel(value: GovernanceDataAction) {
  const labels: Record<GovernanceDataAction, string> = {
    ALLOW: '允许',
    WARN: '告警',
    REDACT: '脱敏',
    BLOCK: '阻断',
    APPROVAL_REQUIRED: '待审批',
  }
  return labels[value] || value
}

function gatewayDirectiveLabel(value: GovernanceGatewayDirective) {
  const labels: Record<GovernanceGatewayDirective, string> = {
    PROCEED: '放行',
    PROCEED_WITH_WARNING: '告警放行',
    REDACT_AND_RECHECK: '脱敏后复检',
    REQUEST_APPROVAL: '请求审批',
    BLOCK: '阻断',
  }
  return labels[value] || value
}

function approvalStatusLabel(value: GovernanceApprovalStatus) {
  const labels: Record<GovernanceApprovalStatus, string> = {
    NOT_REQUIRED: '无需审批',
    PENDING: '待审批',
    APPROVED: '已批准',
    REJECTED: '已拒绝',
  }
  return labels[value] || value
}

function matchedPolicyNames(items: Array<{ name: string; revision?: number | null }>) {
  return items.map(item => `${item.name}（${item.revision == null ? '版本未记录' : '版本 ' + item.revision}）`).join('、') || '默认治理规则'
}

onMounted(load)
</script>
