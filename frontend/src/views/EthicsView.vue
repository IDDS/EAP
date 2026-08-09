<template>
  <section class="page-grid">
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
            <el-button text :icon="Refresh" @click="load">刷新</el-button>
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
            <el-progress :percentage="denyPercent" :stroke-width="8" status="exception" />
            <strong>{{ safetySummary.denyCount }}</strong>
          </div>
          <div class="risk-row">
            <span>确认</span>
            <el-progress :percentage="askPercent" :stroke-width="8" status="warning" />
            <strong>{{ safetySummary.askCount }}</strong>
          </div>
          <div class="risk-row">
            <span>严重告警</span>
            <el-progress :percentage="criticalPercent" :stroke-width="8" status="exception" />
            <strong>{{ safetySummary.criticalAlertCount }}</strong>
          </div>
        </div>
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
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Lock, Refresh, ScaleToOriginal, Warning } from '@element-plus/icons-vue'
import { EapApi } from '@/api/eap'
import type { GovernancePolicy, SafetySummary } from '@/types/domain'

const loading = ref(false)
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
const denyPercent = computed(() => Math.min(100, safetySummary.value.denyCount * 25))
const askPercent = computed(() => Math.min(100, safetySummary.value.askCount * 25))
const criticalPercent = computed(() => Math.min(100, safetySummary.value.criticalAlertCount * 25))
const metrics = computed(() => [
  { label: '伦理策略', value: ethicsPolicies.value.length, tone: 'box-primary', icon: ScaleToOriginal },
  { label: '启用策略', value: activePolicyCount.value, tone: 'box-success', icon: Lock },
  { label: '需确认', value: safetySummary.value.askCount, tone: 'box-warning', icon: Warning },
  { label: '拦截事件', value: safetySummary.value.denyCount, tone: 'box-danger', icon: Lock },
])
const checks = computed(() => [
  { title: '隐私保护', description: '敏感个人信息输出和访问需经过策略评估。', status: hasPolicy('PRIVACY') ? '已覆盖' : '待配置', type: hasPolicy('PRIVACY') ? 'success' : 'warning' },
  { title: '算法透明', description: '高影响智能体需要保留规则、提示词和决策依据。', status: hasPolicy('ALGORITHM') ? '已覆盖' : '待配置', type: hasPolicy('ALGORITHM') ? 'success' : 'warning' },
  { title: '安全边界', description: '生产环境、特权工具和密钥访问默认 fail-closed。', status: hasPolicy('SECURITY') ? '已覆盖' : '待配置', type: hasPolicy('SECURITY') ? 'success' : 'warning' },
  { title: '责任归属', description: '智能体应具备责任部门、责任人和可追溯控制命令。', status: '已覆盖', type: 'success' },
])

async function load() {
  loading.value = true
  try {
    const [nextPolicies, nextSafetySummary] = await Promise.all([EapApi.listPolicies(), EapApi.getSafetySummary()])
    policies.value = nextPolicies
    safetySummary.value = nextSafetySummary
  } finally {
    loading.value = false
  }
}

function hasPolicy(type: string) {
  return policies.value.some((policy) => policy.policyType === type && policy.enabled)
}

onMounted(load)
</script>
