<template>
  <el-container class="app-shell">
    <el-aside :width="sidebarCollapsed ? '72px' : '248px'" :class="['sidebar', { collapsed: sidebarCollapsed }]">
      <div class="brand">
        <img class="brand-logo" src="/eap-logo.svg" alt="EAP logo" />
        <div class="brand-copy">
          <strong>企业智能体平台</strong>
          <span>EAP · 智能体控制平面</span>
        </div>
        <el-button class="sidebar-toggle" text circle :icon="sidebarCollapsed ? Expand : Fold" @click="sidebarCollapsed = !sidebarCollapsed" />
      </div>
      <div class="sidebar-nav">
        <div class="nav-section"><span class="nav-section-index">01</span>总览</div>
        <el-menu router :default-active="navActivePath" class="nav-menu">
        <el-menu-item index="/">
          <el-icon><DataBoard /></el-icon>
          <span>工作台</span>
        </el-menu-item>
        </el-menu>
        <div class="nav-section"><span class="nav-section-index">02</span>资产与智能体</div>
        <el-menu router :default-active="navActivePath" class="nav-menu">
        <el-menu-item index="/agents">
          <el-icon><Cpu /></el-icon>
          <span>智能体目录</span>
        </el-menu-item>
        <el-menu-item index="/registry">
          <el-icon><Search /></el-icon>
          <span>发现与身份</span>
        </el-menu-item>
        </el-menu>
        <div class="nav-section"><span class="nav-section-index">03</span>运行监控</div>
        <el-menu router :default-active="navActivePath" class="nav-menu">
        <el-menu-item index="/physical">
          <el-icon><DataAnalysis /></el-icon>
          <span>运行观测</span>
        </el-menu-item>
        <el-menu-item index="/knowledge">
          <el-icon><Collection /></el-icon>
          <span>知识流转</span>
        </el-menu-item>
        <el-menu-item index="/management">
          <el-icon><Money /></el-icon>
          <span>价值与成本</span>
        </el-menu-item>
        <el-menu-item index="/operations">
          <el-icon><Monitor /></el-icon>
          <span>任务运行</span>
        </el-menu-item>
        </el-menu>
        <div class="nav-section"><span class="nav-section-index">04</span>编排与自动化</div>
        <el-menu router :default-active="navActivePath" class="nav-menu">
        <el-menu-item index="/orchestration">
          <el-icon><Connection /></el-icon>
          <span>智能体团队</span>
        </el-menu-item>
        <el-menu-item index="/workflow-builder">
          <el-icon><Share /></el-icon>
          <span>工作流自动化</span>
        </el-menu-item>
        <el-menu-item index="/learning">
          <el-icon><TrendCharts /></el-icon>
          <span>自适应学习</span>
        </el-menu-item>
        </el-menu>
        <div class="nav-section"><span class="nav-section-index">05</span>治理与响应</div>
        <el-menu router :default-active="navActivePath" class="nav-menu">
        <el-menu-item index="/governance">
          <el-icon><ScaleToOriginal /></el-icon>
          <span>治理策略</span>
        </el-menu-item>
        <el-menu-item index="/safety">
          <el-icon><Connection /></el-icon>
          <span>安全响应</span>
        </el-menu-item>
        <el-menu-item index="/ethics">
          <el-icon><Lock /></el-icon>
          <span>责任与伦理</span>
        </el-menu-item>
        <el-menu-item index="/audit">
          <el-icon><DocumentChecked /></el-icon>
          <span>审计日志</span>
        </el-menu-item>
        </el-menu>
      </div>
      <div class="sidebar-status">
        <span class="status-dot"></span>
        <div class="sidebar-status-copy">
          <strong>安全网关在线</strong>
          <span>本地策略已加载</span>
        </div>
      </div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-title">
          <div class="breadcrumb-line">
            <span class="topbar-kicker">EAP · 安全运营中心</span>
            <span class="breadcrumb-separator">/</span>
            <span>实时运行</span>
          </div>
          <h1>{{ routeTitle }}</h1>
        </div>
        <div class="topbar-actions">
          <el-input
            v-model="globalSearchTerm"
            class="global-search"
            placeholder="搜索智能体、资产、策略"
            :prefix-icon="Search"
            readonly
            @focus="openSearchDialog"
          />
          <el-dropdown class="font-scale-menu" trigger="click" @command="handleFontScaleCommand">
            <el-button class="topbar-compact-control" text>
              字号 · {{ currentFontScaleLabel }}<el-icon><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  v-for="option in fontScaleOptions"
                  :key="option.value"
                  :command="option.value"
                  :class="{ 'is-active': fontScale === option.value }"
                >{{ option.menuLabel }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <div class="workspace-pill"><span class="workspace-dot"></span><span>矩维智能 · 主空间</span></div>
          <el-button class="session-button" :title="hasAccessToken ? '更新企业认证凭证' : '连接企业认证'" @click="openAuthDialog">
            <span :class="['session-indicator', { connected: hasAccessToken }]" />
            {{ hasAccessToken ? '企业认证' : '本地模式' }}
          </el-button>
          <el-button v-if="hasAccessToken" text @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" :key="route.path" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>

  <el-dialog v-model="authDialogVisible" title="企业身份认证" width="520px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="访问令牌仅保存在当前标签页，刷新后保留，关闭标签页即清除。"
    />
    <el-form label-position="top" style="margin-top: 18px">
      <el-form-item label="访问令牌">
        <el-input
          v-model="authTokenDraft"
          type="password"
          show-password
          clearable
          autocomplete="off"
          placeholder="粘贴企业身份提供商签发的 JWT，可包含 Bearer 前缀"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="authDialogVisible = false">取消</el-button>
      <el-button type="primary" :disabled="!authTokenDraft.trim()" @click="saveAuthToken">保存并连接</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="searchDialogVisible" title="全局搜索" width="680px" class="global-search-dialog">
    <el-input
      v-model="globalSearchTerm"
      autofocus
      clearable
      :prefix-icon="Search"
      placeholder="输入智能体名称、代码、发现资产或治理策略"
      @input="scheduleGlobalSearch"
      @keyup.enter="runGlobalSearchNow"
    />
    <div v-loading="globalSearchLoading" class="global-search-results">
      <button v-for="result in globalSearchResults" :key="`${result.type}-${result.title}-${result.subtitle}`" class="global-search-result" @click="selectSearchResult(result)">
        <span class="global-search-result-type">{{ result.type }}</span>
        <span class="global-search-result-copy"><strong>{{ result.title }}</strong><small>{{ result.subtitle }}</small></span>
        <el-icon><ArrowRight /></el-icon>
      </button>
      <el-empty v-if="!globalSearchLoading && !globalSearchResults.length" :description="globalSearchTerm.trim() ? '没有匹配的资产或策略' : '输入关键词开始搜索'" />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, ArrowRight, Collection, Connection, Cpu, DataAnalysis, DataBoard, DocumentChecked, Expand, Fold, Lock, Money, Monitor, ScaleToOriginal, Search, Share, TrendCharts } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { clearAccessToken, getAccessToken, setAccessToken } from '@/api/auth'
import { EapApi } from '@/api/eap'
import type { Agent, DiscoveredAgentAsset, GovernancePolicy } from '@/types/domain'

type GlobalSearchResult = {
  type: string
  title: string
  subtitle: string
  path: string
  query: Record<string, string>
}

type FontScale = 'standard' | 'comfortable' | 'large'

const fontScaleOptions: Array<{ value: FontScale; label: string; menuLabel: string }> = [
  { value: 'standard', label: '标准', menuLabel: '标准字号' },
  { value: 'comfortable', label: '舒适', menuLabel: '舒适字号' },
  { value: 'large', label: '大号', menuLabel: '大字号' },
]

const route = useRoute()
const routeTitle = computed(() => String(route.meta.title ?? '工作台'))
const sidebarCollapsed = ref(false)
const authDialogVisible = ref(false)
const authTokenDraft = ref('')
const hasAccessToken = ref(Boolean(getAccessToken()))
const searchDialogVisible = ref(false)
const globalSearchTerm = ref('')
const globalSearchLoading = ref(false)
const globalSearchResults = ref<GlobalSearchResult[]>([])
const persistedFontScale = localStorage.getItem('eap-font-scale')
const fontScale = ref<FontScale>(
  persistedFontScale === 'standard' || persistedFontScale === 'large' || persistedFontScale === 'comfortable'
    ? persistedFontScale
    : 'comfortable',
)
let searchRequestId = 0
let searchDebounceTimer: ReturnType<typeof setTimeout> | undefined

const router = useRouter()
const navActivePath = computed(() => route.path.startsWith('/agents/') ? '/agents' : route.path)
const currentFontScaleLabel = computed(() => fontScaleOptions.find(option => option.value === fontScale.value)?.label || '舒适')

function setFontScale(value: FontScale) {
  fontScale.value = value
  document.documentElement.dataset.fontScale = value
  localStorage.setItem('eap-font-scale', value)
}

function handleFontScaleCommand(value: FontScale) {
  setFontScale(value)
}

function openAuthDialog() {
  authTokenDraft.value = ''
  authDialogVisible.value = true
}

function saveAuthToken() {
  setAccessToken(authTokenDraft.value)
  hasAccessToken.value = Boolean(getAccessToken())
  authDialogVisible.value = false
  authTokenDraft.value = ''
  ElMessage.success('企业认证令牌已保存，后续请求将自动携带认证信息')
}

function logout() {
  clearAccessToken()
  hasAccessToken.value = false
  ElMessage.success('当前标签页的企业认证已清除')
}

function requireAuthentication() {
  if (!hasAccessToken.value) openAuthDialog()
}

function openSearchDialog() {
  searchDialogVisible.value = true
  if (globalSearchTerm.value.trim()) void runGlobalSearch()
}

async function runGlobalSearch() {
  const term = globalSearchTerm.value.trim().toLowerCase()
  const requestId = ++searchRequestId
  if (!term) {
    globalSearchResults.value = []
    return
  }
  globalSearchLoading.value = true
  try {
    const [agents, assets, policies] = await Promise.all([
      EapApi.listAgents(),
      EapApi.listDiscoveredAgentAssets(),
      EapApi.listPolicies(),
    ])
    if (requestId !== searchRequestId) return
    globalSearchResults.value = [
      ...agents.filter(agent => matchesSearch(agent, term)).slice(0, 8).map(agentResult),
      ...assets.filter(asset => matchesSearch(asset, term)).slice(0, 8).map(assetResult),
      ...policies.filter(policy => matchesSearch(policy, term)).slice(0, 8).map(policyResult),
    ].slice(0, 16)
  } finally {
    if (requestId === searchRequestId) globalSearchLoading.value = false
  }
}

function scheduleGlobalSearch() {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => void runGlobalSearch(), 240)
}

function runGlobalSearchNow() {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  void runGlobalSearch()
}

function matchesSearch(value: Agent | DiscoveredAgentAsset | GovernancePolicy, term: string) {
  return JSON.stringify(value).toLowerCase().includes(term)
}

function agentResult(agent: Agent): GlobalSearchResult {
  return {
    type: '智能体',
    title: agent.name,
    subtitle: `${agent.code} · ${agent.ownerDepartment} · ${agent.lifecycleStatus}`,
    path: `/agents/${agent.id}`,
    query: {},
  }
}

function assetResult(asset: DiscoveredAgentAsset): GlobalSearchResult {
  return {
    type: '发现资产',
    title: asset.name,
    subtitle: `${asset.vendor} · ${asset.product} · ${asset.hostName || '未知设备'}`,
    path: asset.managedAgentId ? `/agents/${asset.managedAgentId}` : '/registry',
    query: {},
  }
}

function policyResult(policy: GovernancePolicy): GlobalSearchResult {
  return {
    type: '治理策略',
    title: policy.name,
    subtitle: `${policy.policyType} · ${policy.enforcementMode} · ${policy.enabled ? '启用' : '停用'}`,
    path: '/governance',
    query: { policyId: String(policy.id) },
  }
}

function selectSearchResult(result: GlobalSearchResult) {
  searchDialogVisible.value = false
  router.push({ path: result.path, query: result.query })
}

onMounted(() => {
  setFontScale(fontScale.value)
  window.addEventListener('eap-auth-required', requireAuthentication)
})
onBeforeUnmount(() => {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  window.removeEventListener('eap-auth-required', requireAuthentication)
})
</script>
