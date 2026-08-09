<template>
  <section class="page-grid">
    <div class="toolbar">
      <div class="toolbar-title">
        <span class="interface-eyebrow">治理 · 策略</span>
        <strong>智能体治理界面</strong>
        <span>追踪内部信息进入模型、公司保密信息、策略动作和阻断结果，不保存敏感正文</span>
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

    <div class="interface-tabs interface-tabs-top" role="tablist" aria-label="治理界面分区">
      <el-tag :class="{ 'is-active': activeSection === 'overview' }" :effect="activeSection === 'overview' ? 'dark' : 'plain'" @click="activeSection = 'overview'">治理总览</el-tag>
      <el-tag :class="{ 'is-active': activeSection === 'preflight' }" :effect="activeSection === 'preflight' ? 'dark' : 'plain'" @click="activeSection = 'preflight'">实时预检</el-tag>
      <el-tag :class="{ 'is-active': activeSection === 'audit' }" :effect="activeSection === 'audit' ? 'dark' : 'plain'" @click="activeSection = 'audit'">事件审计</el-tag>
      <el-tag :class="{ 'is-active': activeSection === 'policies' }" :effect="activeSection === 'policies' ? 'dark' : 'plain'" @click="activeSection = 'policies'">治理策略</el-tag>
    </div>

    <div class="analysis-hero governance-hero">
      <div class="analysis-hero-copy">
        <span class="analysis-eyebrow">治理策略流 / 最近 {{ windowHours }} 小时</span>
        <h2>治理策略流</h2>
        <p>将分类、预检、策略命中与网关执行串成一条控制链，所有判断只保留可审计的分类元数据，不落库敏感正文。</p>
      </div>
      <div class="governance-health-card">
        <span class="analysis-eyebrow">控制状态</span>
        <div class="governance-health-main">
          <span class="governance-health-dot" :class="{ 'is-ready': enforcement.enforcementEnabled }"></span>
          <strong>{{ enforcement.enforcementEnabled ? '强制' : '观察' }}</strong>
        </div>
        <div class="governance-health-meta">
          <span>网关 <b>{{ gatewayStatusLabel }}</b></span>
          <span>待审批 <b>{{ enforcement.pendingApprovals }}</b></span>
        </div>
      </div>
    </div>

    <div class="governance-flow-strip">
      <template v-for="(step, index) in governanceFlow" :key="step.index">
        <div class="governance-flow-step" :class="{ 'is-ready': step.ready }">
          <span class="governance-flow-index">{{ step.index }}</span>
          <div><span>{{ step.label }}</span><strong>{{ step.value }}</strong><small>{{ step.detail }}</small></div>
        </div>
        <span v-if="index < governanceFlow.length - 1" class="governance-flow-arrow">→</span>
      </template>
    </div>

    <div v-if="drilldownLabel" class="drilldown-context">
      <span class="drilldown-context-kicker">筛选条件</span>
      <strong>{{ drilldownLabel }}</strong>
      <span>当前治理事件和决策已按上下文收敛</span>
      <el-button link type="primary" @click="clearDrilldown">清除筛选</el-button>
    </div>

    <el-alert
      v-if="!summary.lastEventAt"
      type="warning"
      :closable="false"
      show-icon
      title="尚未收到治理数据事件。请由模型网关、DLP 或智能体运行时只上报分类、计数和策略动作。"
    />

    <el-alert
      v-if="enforcement.enforcementEnabled"
      type="success"
      :closable="false"
      show-icon
      title="实时强制治理已启用：模型网关必须在发送请求前调用预检接口，并严格执行返回的网关指令。"
    />

    <el-alert
      v-if="!gateway.configured"
      type="warning"
      :closable="false"
      show-icon
      title="治理策略已经启用，但兼容模型网关尚未配置上游；生产智能体还不能经由统一入口调用模型。"
    />

    <div v-show="activeSection === 'overview'" class="governance-enforcement-strip">
      <div>
        <span>执行模式</span>
        <strong>{{ enforcement.enforcementEnabled ? '强制' : '观察' }}</strong>
      </div>
      <div>
        <span>保密信息默认策略</span>
        <strong>{{ enforcement.failClosedOnSecret ? '默认阻断' : '按策略' }}</strong>
      </div>
      <div><span>启用策略</span><strong>{{ enforcement.enabledPolicies }}</strong></div>
      <div><span>预检决策</span><strong>{{ enforcement.preflightDecisions }}</strong></div>
      <div><span>强制阻断</span><strong>{{ enforcement.blockedDecisions }}</strong></div>
        <div><span>脱敏 / 审批</span><strong>{{ enforcement.redactionDecisions }} / {{ enforcement.approvalDecisions }}</strong></div>
        <div><span>待审批</span><strong class="pending-approval-count">{{ enforcement.pendingApprovals }}</strong></div>
      <div>
        <span>模型网关</span>
        <strong>{{ gatewayStatusLabel }}</strong>
      </div>
      <div><span>网关供应商</span><strong>{{ gateway.provider }}</strong></div>
      <div><span>客户端鉴权</span><strong>{{ gateway.authenticationConfigured ? '就绪' : '缺失' }}</strong></div>
    </div>

    <el-card v-show="activeSection === 'overview'" shadow="never" class="panel gateway-integration-card">
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

    <div v-show="activeSection === 'preflight'" class="governance-two-column">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>智能体 / 端点风险分布</span>
            <span class="muted">仅聚合分类元数据</span>
          </div>
        </template>
        <el-table :data="visibleSubjectUsage" v-loading="loading" size="small">
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
        </el-table>
        <el-empty v-if="summary.subjectUsage.length === 0" description="当前窗口没有治理数据" />
      </el-card>

      <el-card shadow="never" class="panel">
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
            <el-button type="danger" :loading="saving" @click="runPreflight">执行实时预检</el-button>
          </div>
        </el-form>
      </el-card>
    </div>

    <el-card v-show="activeSection === 'preflight'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>最近预检与执行指令</span>
          <span class="muted">放行 · 脱敏后复检 · 请求审批 · 阻断</span>
        </div>
      </template>
      <el-table :data="visibleDecisions" v-loading="loading" row-key="id">
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
        <el-table-column label="请求模型" width="105">
          <template #default="{ row }"><el-tag :type="row.requestAllowed ? 'success' : 'danger'">{{ row.requestAllowed ? '允许' : '停止' }}</el-tag></template>
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
          <template #default="{ row }">{{ row.provider || '—' }} / {{ row.modelName || '—' }}</template>
        </el-table-column>
        <el-table-column label="审批状态" width="145">
          <template #default="{ row }"><el-tag :type="approvalType(row.approvalStatus)">{{ approvalStatusLabel(row.approvalStatus) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="审批操作" width="190" fixed="right">
          <template #default="{ row }">
            <div v-if="row.approvalStatus === 'PENDING'" class="button-row compact-actions">
              <el-button size="small" type="success" @click="approveDecision(row)">批准</el-button>
              <el-button size="small" type="danger" plain @click="rejectDecision(row)">拒绝</el-button>
            </div>
            <span v-else class="muted">{{ row.approvedBy ? `${row.approvedBy} · ${formatTime(row.approvedAt)}` : '无需审批' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="visibleDecisions.length === 0" description="尚未执行模型网关预检" />
    </el-card>

    <el-card v-show="activeSection === 'audit'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>最近治理事件</span>
          <span class="muted">分类、敏感级别和策略动作；不展示信息正文</span>
        </div>
      </template>
      <el-table :data="visibleEvents" v-loading="loading" row-key="id">
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
      </el-table>
      <el-empty v-if="visibleEvents.length === 0" description="尚未收到治理数据事件" />
    </el-card>

    <el-card v-show="activeSection === 'policies'" shadow="never" class="panel">
      <template #header>
        <div class="panel-header">
          <span>治理策略</span>
          <el-button size="small" type="primary" :icon="Plus" @click="openPolicyDialog()">新建策略</el-button>
        </div>
      </template>
      <el-table :data="visiblePolicies" v-loading="loading">
        <el-table-column prop="name" label="策略名称" min-width="180" />
        <el-table-column label="类型" width="140"><template #default="{ row }">{{ policyTypeLabel(row.policyType) }}</template></el-table-column>
        <el-table-column label="范围" width="120"><template #default="{ row }">{{ scopeTypeLabel(row.scopeType) }}</template></el-table-column>
        <el-table-column label="执行模式" width="170"><template #default="{ row }">{{ enforcementModeLabel(row.enforcementMode) }}</template></el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :loading="policySavingId === row.id"
              @change="togglePolicy(row, Boolean($event))"
            />
          </template>
        </el-table-column>
        <el-table-column prop="ruleJson" label="规则" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button text type="primary" @click="openPolicyDialog(row)">编辑</el-button></template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="policyDialogVisible" :title="editingPolicyId ? '编辑治理策略' : '新建治理策略'" width="680px">
      <el-form label-position="top">
        <div class="form-row">
          <el-form-item label="策略名称" required>
            <el-input v-model="policyForm.name" maxlength="128" />
          </el-form-item>
          <el-form-item label="策略类型" required>
            <el-select v-model="policyForm.policyType" style="width: 100%">
              <el-option label="权限" value="PERMISSION" />
              <el-option label="隐私" value="PRIVACY" />
              <el-option label="安全" value="SECURITY" />
              <el-option label="算法" value="ALGORITHM" />
              <el-option label="效益" value="BENEFIT" />
            </el-select>
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="作用范围" required>
            <el-select v-model="policyForm.scopeType" style="width: 100%">
              <el-option label="全局" value="GLOBAL" />
              <el-option label="指定智能体" value="AGENT" />
              <el-option label="指定端点" value="ENDPOINT" />
              <el-option label="数据类别" value="DATA_CATEGORY" />
              <el-option label="模型供应商" value="PROVIDER" />
              <el-option label="模型" value="MODEL" />
            </el-select>
          </el-form-item>
          <el-form-item label="范围值" :required="policyForm.scopeType !== 'GLOBAL'">
            <el-input v-model="policyForm.scopeValue" :disabled="policyForm.scopeType === 'GLOBAL'" placeholder="例如智能体 ID、端点标识或模型名" />
          </el-form-item>
        </div>
        <div class="form-row">
          <el-form-item label="执行模式" required>
            <el-select v-model="policyForm.enforcementMode" style="width: 100%">
              <el-option label="告警并放行" value="WARN" />
              <el-option label="阻断" value="BLOCK" />
              <el-option label="人工审批" value="APPROVAL_REQUIRED" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用状态">
            <el-switch v-model="policyForm.enabled" active-text="启用" inactive-text="停用" />
          </el-form-item>
        </div>
        <el-form-item label="规则 JSON" required>
          <el-input v-model="policyForm.ruleJson" type="textarea" :rows="7" />
          <div class="muted">支持 sensitivities、dataCategories、directions、providers、models、internalData、companySecret、detectedTypes / piiTypes 和 action。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="policyDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="policySaving" @click="savePolicy">保存策略</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { CircleCheck, DataAnalysis, Lock, OfficeBuilding, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { EapApi } from '@/api/eap'
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
  GovernancePolicy,
  GovernanceApprovalStatus,
  EnforcementMode,
  ModelGatewayStatus,
  PolicyType,
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

const policies = ref<GovernancePolicy[]>([])
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
const policyDialogVisible = ref(false)
const editingPolicyId = ref<number>()
const policySaving = ref(false)
const policySavingId = ref<number>()
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
const policyForm = reactive({
  name: '',
  policyType: 'PRIVACY' as PolicyType,
  scopeType: 'GLOBAL',
  scopeValue: '',
  ruleJson: JSON.stringify({ sensitivities: ['SECRET', 'RESTRICTED'], action: 'BLOCK' }, null, 2),
  enforcementMode: 'BLOCK' as EnforcementMode,
  enabled: true,
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
const visiblePolicies = computed(() => contextPolicyId.value
  ? policies.value.filter(policy => policy.id === contextPolicyId.value)
  : policies.value)
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

async function load() {
  loading.value = true
  try {
    const [nextPolicies, nextSummary, nextEvents, nextEnforcement, nextDecisions, nextGateway] = await Promise.all([
      EapApi.listPolicies(),
      EapApi.getGovernanceDataSummary(windowHours.value),
      EapApi.listGovernanceDataEvents(100),
      EapApi.getGovernanceEnforcementStatus(),
      EapApi.listGovernancePreflightDecisions(100),
      EapApi.getModelGatewayStatus(),
    ])
    policies.value = nextPolicies
    summary.value = nextSummary
    events.value = nextEvents
    enforcement.value = nextEnforcement
    decisions.value = nextDecisions
    gateway.value = nextGateway
  } finally {
    loading.value = false
  }
}

async function runPreflight() {
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
    const message = `网关指令：${gatewayDirectiveLabel(result.gatewayDirective)}`
    if (result.requestAllowed) ElMessage.success(message)
    else ElMessage.error(message)
    Object.assign(form, { eventKey: '', agentId: undefined, endpointKey: '', dataCategory: '', tokenCount: 0, payloadBytes: 0, detectedTypesText: '' })
    await load()
  } finally {
    saving.value = false
  }
}

function openPolicyDialog(policy?: GovernancePolicy) {
  editingPolicyId.value = policy?.id
  Object.assign(policyForm, policy ? {
    name: policy.name,
    policyType: policy.policyType,
    scopeType: policy.scopeType,
    scopeValue: policy.scopeValue || '',
    ruleJson: prettyRule(policy.ruleJson),
    enforcementMode: policy.enforcementMode,
    enabled: policy.enabled,
  } : {
    name: '',
    policyType: 'PRIVACY',
    scopeType: 'GLOBAL',
    scopeValue: '',
    ruleJson: JSON.stringify({ sensitivities: ['SECRET', 'RESTRICTED'], action: 'BLOCK' }, null, 2),
    enforcementMode: 'BLOCK',
    enabled: true,
  })
  policyDialogVisible.value = true
}

async function savePolicy() {
  if (!policyForm.name.trim()) {
    ElMessage.warning('请填写策略名称')
    return
  }
  if (policyForm.scopeType !== 'GLOBAL' && !policyForm.scopeValue.trim()) {
    ElMessage.warning('请填写范围值')
    return
  }
  try {
    const parsed = JSON.parse(policyForm.ruleJson)
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error('not object')
  } catch {
    ElMessage.warning('规则必须是有效的 JSON 对象')
    return
  }
  policySaving.value = true
  try {
    const payload = {
      name: policyForm.name.trim(),
      policyType: policyForm.policyType,
      scopeType: policyForm.scopeType,
      scopeValue: policyForm.scopeType === 'GLOBAL' ? '' : policyForm.scopeValue.trim(),
      ruleJson: policyForm.ruleJson,
      enforcementMode: policyForm.enforcementMode,
      enabled: policyForm.enabled,
    }
    if (editingPolicyId.value) await EapApi.updatePolicy(editingPolicyId.value, payload)
    else await EapApi.createPolicy(payload)
    ElMessage.success(editingPolicyId.value ? '治理策略已更新' : '治理策略已创建')
    policyDialogVisible.value = false
    await load()
  } finally {
    policySaving.value = false
  }
}

async function togglePolicy(policy: GovernancePolicy, enabled: boolean) {
  policySavingId.value = policy.id
  try {
    await EapApi.updatePolicy(policy.id, {
      name: policy.name,
      policyType: policy.policyType,
      scopeType: policy.scopeType,
      scopeValue: policy.scopeValue || '',
      ruleJson: policy.ruleJson,
      enforcementMode: policy.enforcementMode,
      enabled,
    })
    ElMessage.success(enabled ? '策略已启用' : '策略已停用')
    await load()
  } finally {
    policySavingId.value = undefined
  }
}

function prettyRule(value: string) {
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

async function approveDecision(row: GovernancePreflightDecision) {
  saving.value = true
  try {
    await EapApi.approveGovernanceDecision(row.decisionKey, { approver: 'governance-console', reason: '控制台人工审批通过' })
    ElMessage.success('模型调用已批准；客户端可携带该决策标识重试原请求')
    await load()
  } finally {
    saving.value = false
  }
}

async function rejectDecision(row: GovernancePreflightDecision) {
  saving.value = true
  try {
    await EapApi.rejectGovernanceDecision(row.decisionKey, { approver: 'governance-console', reason: '控制台人工拒绝' })
    ElMessage.success('模型调用已拒绝')
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

function policyTypeLabel(value: PolicyType) {
  const labels: Record<PolicyType, string> = {
    PERMISSION: '权限',
    PRIVACY: '隐私',
    SECURITY: '安全',
    ALGORITHM: '算法',
    BENEFIT: '效益',
  }
  return labels[value] || value
}

function scopeTypeLabel(value: string) {
  const labels: Record<string, string> = {
    GLOBAL: '全局',
    AGENT: '智能体',
    ENDPOINT: '端点',
    DATA_CATEGORY: '数据类别',
    PROVIDER: '模型供应商',
    MODEL: '模型',
  }
  return labels[value] || value
}

function enforcementModeLabel(value: EnforcementMode) {
  const labels: Record<EnforcementMode, string> = {
    WARN: '告警并放行',
    BLOCK: '阻断',
    APPROVAL_REQUIRED: '人工审批',
  }
  return labels[value] || value
}

function matchedPolicyNames(items: Array<{ name: string }>) {
  return items.map(item => item.name).join('、') || '默认治理规则'
}

onMounted(load)
</script>
