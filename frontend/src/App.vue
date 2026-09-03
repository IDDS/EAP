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
        <el-menu
          ref="navMenuRef"
          router
          :collapse="sidebarCollapsed"
          :default-active="navActivePath"
          :default-openeds="navOpenGroups"
          class="nav-menu nav-menu-workspaces"
        >
          <el-menu-item index="/">
            <el-icon><DataBoard /></el-icon>
            <span>总览与拓扑</span>
          </el-menu-item>
          <el-sub-menu index="workspace-agents">
            <template #title>
              <span class="workspace-menu-title-hit" @click.stop="openWorkspace('workspace-agents', '/agents')">
                <el-icon><Cpu /></el-icon><span>智能体中心</span>
              </span>
            </template>
            <el-menu-item index="/agents">智能体目录</el-menu-item>
            <el-menu-item index="/registry">发现与接入</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="workspace-observe">
            <template #title>
              <span class="workspace-menu-title-hit" @click.stop="openWorkspace('workspace-observe', '/physical')">
                <el-icon><DataAnalysis /></el-icon><span>运行与观测</span>
              </span>
            </template>
            <el-menu-item index="/physical">运行与遥测</el-menu-item>
            <el-menu-item index="/knowledge">知识流转</el-menu-item>
            <el-menu-item index="/operations">任务运行</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="workspace-governance">
            <template #title>
              <span class="workspace-menu-title-hit" @click.stop="openWorkspace('workspace-governance', '/governance')">
                <el-icon><ScaleToOriginal /></el-icon><span>治理与响应</span>
              </span>
            </template>
            <el-menu-item index="/governance">策略与审批</el-menu-item>
            <el-menu-item index="/safety">安全响应</el-menu-item>
            <el-menu-item index="/ethics">责任与伦理</el-menu-item>
            <el-menu-item index="/audit">审计日志</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="workspace-value">
            <template #title>
              <span class="workspace-menu-title-hit" @click.stop="openWorkspace('workspace-value', '/management')">
                <el-icon><Money /></el-icon><span>成本与价值</span>
              </span>
            </template>
            <el-menu-item index="/management">成本与价值</el-menu-item>
          </el-sub-menu>
          <el-sub-menu index="workspace-orchestration">
            <template #title>
              <span class="workspace-menu-title-hit" @click.stop="openWorkspace('workspace-orchestration', '/orchestration')">
                <el-icon><Connection /></el-icon><span>编排与学习</span>
              </span>
            </template>
            <el-menu-item index="/orchestration">Agent 团队</el-menu-item>
            <el-menu-item index="/workflow-builder">工作流自动化</el-menu-item>
            <el-menu-item index="/learning">自适应学习</el-menu-item>
          </el-sub-menu>
          <el-menu-item index="/evaluation">
            <el-icon><DataAnalysis /></el-icon>
            <span>评估与可靠性</span>
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
            <span class="topbar-kicker">EAP · 企业智能体控制台</span>
            <span class="breadcrumb-separator">/</span>
            <span>{{ agentOpsContext.scopeLabel }} · {{ agentOpsContext.windowLabel }}</span>
          </div>
          <h1>{{ routeTitle }}</h1>
        </div>
        <div class="topbar-actions">
          <el-select
            :model-value="agentOpsContext.windowHours"
            class="context-window-select"
            aria-label="全局时间窗口"
            @change="handleWindowChange"
          >
            <el-option label="最近 1 小时" :value="1" /><el-option label="最近 6 小时" :value="6" />
            <el-option label="最近 24 小时" :value="24" /><el-option label="最近 3 天" :value="72" /><el-option label="最近 7 天" :value="168" />
          </el-select>
          <el-input
            :model-value="agentOpsContext.organizationScope"
            class="context-scope-input"
            clearable
            placeholder="企业全局"
            aria-label="组织范围"
            @change="handleScopeChange"
          />
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

  <div v-if="platformStatus?.securityMode === 'DEVELOPMENT'" class="app-environment-banner" role="status">
    <span class="app-environment-dot"></span>
    <span><strong>开发演示环境</strong> · 当前接口未启用 JWT 认证，请勿接入生产数据</span>
  </div>
  <div v-else-if="platformStatusLoading" class="app-environment-banner app-environment-banner-muted" role="status">
    <span class="app-environment-dot"></span>
    <span>正在确认 EAP 运行环境…</span>
  </div>

  <div v-if="updateAvailable" class="app-update-banner" role="status">
    <span>检测到 EAP 前端有新版本</span>
    <el-button type="primary" size="small" @click="reloadForUpdate">立即刷新</el-button>
  </div>

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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, ArrowRight, Connection, Cpu, DataAnalysis, DataBoard, Expand, Fold, Money, ScaleToOriginal, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { clearAccessToken, getAccessToken, setAccessToken } from '@/api/auth'
import { EapApi } from '@/api/eap'
import { useAgentOpsContextStore } from '@/stores/agentOps'
import type { Agent, DiscoveredAgentAsset, GovernancePolicy, PlatformStatus } from '@/types/domain'

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
const agentOpsContext = useAgentOpsContextStore()
const routeTitle = computed(() => String(route.meta.title ?? '工作台'))
const sidebarCollapsed = ref(false)
let compactViewportQuery: MediaQueryList | undefined
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
let buildVersion = ''
let versionCheckTimer: number | undefined
let versionCheckInFlight = false
const updateAvailable = ref(false)
const platformStatus = ref<PlatformStatus>()
const platformStatusLoading = ref(true)

const router = useRouter()
const navMenuRef = ref<{ open: (index: string) => void } | null>(null)
const navActivePath = computed(() => route.path.startsWith('/agents/') ? '/agents' : route.path)
const navOpenGroups = computed(() => {
  const path = route.path
  if (path.startsWith('/agents') || path === '/registry') return ['workspace-agents']
  if (['/physical', '/knowledge', '/operations'].includes(path)) return ['workspace-observe']
  if (['/governance', '/safety', '/ethics', '/audit'].includes(path)) return ['workspace-governance']
  if (path === '/management') return ['workspace-value']
  if (['/orchestration', '/workflow-builder', '/learning'].includes(path)) return ['workspace-orchestration']
  return []
})
const currentFontScaleLabel = computed(() => fontScaleOptions.find(option => option.value === fontScale.value)?.label || '舒适')

function syncOpenNavigationGroup() {
  const groups = navOpenGroups.value
  if (!groups.length) return
  void nextTick(() => {
    groups.forEach(index => navMenuRef.value?.open(index))
  })
}

watch(navOpenGroups, syncOpenNavigationGroup, { flush: 'post' })

function openWorkspace(index: string, path: string) {
  void router.push(path).then(() => {
    navMenuRef.value?.open(index)
    syncOpenNavigationGroup()
  })
}

function setFontScale(value: FontScale) {
  fontScale.value = value
  document.documentElement.dataset.fontScale = value
  localStorage.setItem('eap-font-scale', value)
}

function handleFontScaleCommand(value: FontScale) {
  setFontScale(value)
}

function handleWindowChange(value: unknown) {
  agentOpsContext.setWindowHours(Number(value))
}

function handleScopeChange(value: unknown) {
  agentOpsContext.setOrganizationScope(String(value || ''))
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

async function checkForBuildUpdate() {
  if (versionCheckInFlight) return
  versionCheckInFlight = true
  try {
    const response = await fetch(`/version.json?check=${Date.now()}`, { cache: 'no-store' })
    if (!response.ok) return
    const payload = await response.json() as { buildId?: string }
    if (!payload.buildId) return
    if (!buildVersion) buildVersion = payload.buildId
    else if (payload.buildId !== buildVersion) updateAvailable.value = true
  } catch {
    // 版本探测失败不影响业务页面和 API 请求。
  } finally {
    versionCheckInFlight = false
  }
}

async function loadPlatformStatus() {
  platformStatusLoading.value = true
  try {
    platformStatus.value = await EapApi.getPlatformStatus()
  } catch {
    // 状态端点不可用时不猜测环境，业务页面仍可继续使用。
  } finally {
    platformStatusLoading.value = false
  }
}

function handleVisibilityChange() {
  if (!document.hidden) void checkForBuildUpdate()
}

function reloadForUpdate() {
  window.location.reload()
}

function syncSidebarForViewport(event?: MediaQueryListEvent) {
  if (event?.matches || compactViewportQuery?.matches) sidebarCollapsed.value = true
}

onMounted(() => {
  setFontScale(fontScale.value)
  compactViewportQuery = window.matchMedia('(max-width: 760px)')
  syncSidebarForViewport()
  compactViewportQuery.addEventListener('change', syncSidebarForViewport)
  syncOpenNavigationGroup()
  window.addEventListener('eap-auth-required', requireAuthentication)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  void checkForBuildUpdate()
  void loadPlatformStatus()
  versionCheckTimer = window.setInterval(() => void checkForBuildUpdate(), 60_000)
})
onBeforeUnmount(() => {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  window.removeEventListener('eap-auth-required', requireAuthentication)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  compactViewportQuery?.removeEventListener('change', syncSidebarForViewport)
  if (versionCheckTimer) window.clearInterval(versionCheckTimer)
})
</script>
