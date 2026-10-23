<template>
  <section class="page-grid ethics-page">
    <div class="toolbar"><div class="toolbar-title"><h2>责任与伦理</h2><span>核对策略配置与已记录的风险事件，执行效果需要结合原始证据判断。</span></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="loading && !loaded" role="status" class="muted">正在读取策略与风险记录…</p>
    <template v-if="loaded">
    <div class="metric-strip">
      <div v-for="item in metrics" :key="item.label" class="small-box" :class="item.tone">
        <div>
          <strong>{{ item.value }}</strong>
          <span>{{ item.label }}</span>
        </div>
        <el-icon><component :is="item.icon" /></el-icon>
      </div>
    </div>

    <div class="ethics-grid">
      <el-card shadow="never" class="panel">
        <template #header>
          <div class="panel-header">
            <span>伦理与安全控制项</span>
            <span class="muted">已配置不等于执行效果已验证</span>
          </div>
        </template>
        <div class="ethics-checks">
          <div v-for="item in checks" :key="item.title" class="ethics-check">
            <el-tag :type="item.type">{{ item.status }}</el-tag>
            <div>
              <strong>{{ item.title }}</strong>
              <span>{{ item.description }}</span>
            </div>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="panel">
        <template #header>风险决策概览</template>
        <div class="risk-board">
          <div class="risk-row">
            <span>拦截</span>
            <strong>{{ safetySummary.denyCount }} 次</strong>
          </div>
          <div class="risk-row">
            <span>确认</span>
            <strong>{{ safetySummary.askCount }} 次</strong>
          </div>
          <div class="risk-row">
            <span>严重告警</span>
            <strong>{{ safetySummary.criticalAlertCount }} 条</strong>
          </div>
        </div>
        <p class="muted">来自平台安全摘要的累计记录数；事件和告警可能重叠，不表示风险占比。</p>
      </el-card>
    </div>

    <el-card shadow="never" class="panel">
      <template #header>治理策略映射</template>
      <el-table :data="ethicsPolicies" v-loading="loading">
        <el-table-column prop="name" label="策略名称" min-width="220" />
        <el-table-column prop="policyType" label="类型" width="140" />
        <el-table-column prop="enforcementMode" label="执行模式" width="170" />
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ruleJson" label="规则" min-width="360" show-overflow-tooltip />
      </el-table>
    </el-card>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Lock, Refresh, ScaleToOriginal, Warning } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { GovernancePolicy, SafetySummary } from '@/types/domain'

const loading = ref(false)
const loaded = ref(false)
const error = ref('')
let generation = 0
const policies = ref<GovernancePolicy[]>([])
const safetySummary = ref<SafetySummary>({
  sessionCount: 0,
  eventCount: 0,
  denyCount: 0,
  askCount: 0,
  criticalAlertCount: 0,
  recentDecisions: [],
})

const ethicsPolicies = computed(() =>
  policies.value.filter((policy) => ['PRIVACY', 'SECURITY', 'ALGORITHM', 'BENEFIT'].includes(policy.policyType)),
)
const activePolicyCount = computed(() => ethicsPolicies.value.filter((policy) => policy.enabled).length)
const metrics = computed(() => [
  { label: '伦理策略', value: ethicsPolicies.value.length, tone: 'box-primary', icon: ScaleToOriginal },
  { label: '启用策略', value: activePolicyCount.value, tone: 'box-success', icon: Lock },
  { label: '需确认', value: safetySummary.value.askCount, tone: 'box-warning', icon: Warning },
  { label: '拦截事件', value: safetySummary.value.denyCount, tone: 'box-danger', icon: Lock },
])
const checks = computed(() => [
  { title: '隐私保护', description: '核对敏感个人信息输出和访问的策略及执行证据。', status: hasPolicy('PRIVACY') ? '已配置' : '待配置', type: hasPolicy('PRIVACY') ? 'info' : 'warning' },
  { title: '算法透明', description: '高影响智能体需要保留规则、提示词和决策依据。', status: hasPolicy('ALGORITHM') ? '已配置' : '待配置', type: hasPolicy('ALGORITHM') ? 'info' : 'warning' },
  { title: '安全边界', description: '核对生产环境、特权工具和密钥访问的具体限制。', status: hasPolicy('SECURITY') ? '已配置' : '待配置', type: hasPolicy('SECURITY') ? 'info' : 'warning' },
  { title: '责任归属', description: '需逐项核对责任部门、责任人与可追溯控制记录；本页未验证完整性。', status: '待核对', type: 'info' },
])

async function load() {
  const own = ++generation
  loading.value = true; loaded.value = false; error.value = ''
  try {
    const [nextPolicies, nextSafetySummary] = await Promise.all([EapApi.listPolicies(), EapApi.getSafetySummary()])
    if (own !== generation) return
    policies.value = nextPolicies
    safetySummary.value = nextSafetySummary
    loaded.value = true
  } catch {
    if (own === generation) error.value = '策略或风险记录读取失败，当前无法判断配置与风险状态。请刷新重试。'
  } finally {
    if (own === generation) loading.value = false
  }
}

function hasPolicy(type: string) {
  return policies.value.some((policy) => policy.policyType === type && policy.enabled)
}

onMounted(load)
onBeforeUnmount(() => { generation++ })
</script>
