<template>
  <router-view v-if="route.meta.standalone" />
  <div v-else class="app-shell console-template-layout">
    <a class="skip-content-link" href="#main-content">跳到主要内容</a>
    <el-aside v-show="!compactViewport" :width="sidebarCollapsed ? '72px' : '248px'" :class="['sidebar', { collapsed: sidebarCollapsed }]">
      <div class="brand">
        <img class="brand-logo" src="/eap-logo.svg" alt="EAP logo" />
        <div class="brand-copy">
          <strong>EAP</strong>
          <span>企业智能体平台</span>
        </div>
        <el-button class="sidebar-toggle" :aria-label="sidebarCollapsed ? '展开导航' : '收起导航'" :aria-expanded="!sidebarCollapsed" text circle :icon="sidebarCollapsed ? Expand : Fold" @click="sidebarCollapsed = !sidebarCollapsed" />
      </div>
      <div class="sidebar-nav">
        <PlatformNavigation :collapsed="sidebarCollapsed" @expand="sidebarCollapsed = false" />
      </div>
      <div class="sidebar-status">
        <span class="status-dot" :class="{ 'status-unknown': !platformStatus || platformStatusLoading, 'status-development': platformStatus?.securityMode === 'DEVELOPMENT' }"></span>
        <div class="sidebar-status-copy">
          <strong>{{ platformStatusLoading ? '正在确认环境' : !platformStatus ? '环境状态未知' : platformStatus.securityMode === 'DEVELOPMENT' ? '开发演示环境' : '企业认证环境' }}</strong>
          <span>{{ platformStatus ? (platformStatus.controlDispatchEnabled ? '控制派发已启用' : '控制派发未启用') : '请检查平台连接' }}</span>
        </div>
      </div>
    </el-aside>

    <div class="console-main-column">
      <el-header class="topbar">
        <el-button v-if="compactViewport" class="mobile-nav-trigger" :icon="Expand" aria-label="打开功能导航" :aria-expanded="mobileNavigationOpen" @click="mobileNavigationOpen = true" />
        <div class="topbar-title">
          <span v-if="permissionState.user?.project?.projectKey" class="topbar-kicker project-identity" :title="permissionState.user.project.projectKey">项目 · {{ permissionState.user.project.projectKey }}</span>
          <nav class="breadcrumb-line" aria-label="当前位置">
            <router-link to="/" class="topbar-kicker">EAP 工作台</router-link>
            <template v-if="navigationLocation.group"><span class="breadcrumb-separator" aria-hidden="true">/</span><span>{{ navigationLocation.group.label }}</span></template>
            <template v-if="routeTitle !== '工作台'"><span class="breadcrumb-separator" aria-hidden="true">/</span><span class="breadcrumb-current" aria-current="page">{{ routeTitle }}</span></template>
          </nav>
        </div>
        <div class="topbar-actions">
          <el-button class="global-search-trigger" :icon="Search" aria-label="打开全局搜索" @click="openSearchDialog"><span>搜索功能与资源</span></el-button>
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
          <el-button class="session-button" :title="hasAccessToken ? '更新企业认证凭证' : '连接企业认证'" @click="openAuthDialog">
            <span :class="['session-indicator', { connected: hasAccessToken }]" />
            {{ hasAccessToken ? '企业认证' : platformStatus?.securityMode === 'DEVELOPMENT' ? '本地模式' : '登录' }}
          </el-button>
          <el-button v-if="hasAccessToken" text @click="logout">退出</el-button>
        </div>
      </el-header>
      <main id="main-content" class="main" tabindex="-1">
        <h1 class="console-page-title-sr">{{ routeTitle }}</h1>
        <LteAppContent>
  <div v-if="!route.meta.standalone && platformStatus?.securityMode === 'DEVELOPMENT'" class="app-environment-banner" role="status">
    <span class="app-environment-dot"></span>
    <span><strong>开发演示环境</strong> · 当前接口未启用 JWT 认证，请勿接入生产数据</span>
  </div>
  <div v-else-if="!route.meta.standalone && platformStatusLoading" class="app-environment-banner app-environment-banner-muted" role="status">
    <span class="app-environment-dot"></span>
    <span>正在确认 EAP 运行环境…</span>
  </div>

        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <div :key="route.path" class="route-page"><component :is="Component" /></div>
          </transition>
        </router-view>
        </LteAppContent>
      </main>
    </div>
  </div>


  <el-drawer v-model="mobileNavigationOpen" title="功能导航" direction="ltr" size="min(300px, 90vw)" append-to-body :z-index="4000" class="mobile-navigation-drawer">
    <PlatformNavigation @navigate="mobileNavigationOpen = false" />
  </el-drawer>

  <div v-if="updateAvailable" class="app-update-banner" role="status">
    <span>检测到 EAP 前端有新版本</span>
    <el-button type="primary" size="small" @click="reloadForUpdate">立即刷新</el-button>
  </div>

  <el-dialog v-model="authDialogVisible" title="企业身份认证" width="min(520px, calc(100vw - 28px))">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="访问令牌仅保存在当前标签页，刷新后保留，关闭标签页即清除。"
    />
    <el-button v-if="platformStatus?.oidc" type="primary" :loading="signingIn" style="margin-top: 18px" @click="signIn">企业账号登录</el-button>
    <el-form label-position="top" style="margin-top: 18px">
      <el-form-item label="手工访问令牌（备用）">
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
      <el-button type="primary" :disabled="!authTokenDraft.trim()" :loading="savingToken" @click="saveAuthToken">保存并连接</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="searchDialogVisible" title="全局搜索" width="min(680px, calc(100vw - 28px))" class="global-search-dialog">
    <el-input
      v-model="globalSearchTerm"
      autofocus
      aria-label="全局搜索关键词"
      clearable
      :prefix-icon="Search"
      placeholder="输入页面名称、任务、监控、智能体或策略"
      @input="scheduleGlobalSearch"
      @keyup.enter="runGlobalSearchNow"
    />
    <div v-loading="globalSearchLoading" class="global-search-results">
      <button v-for="result in globalSearchResults" :key="`${result.type}-${result.title}-${result.subtitle}`" class="global-search-result" @click="selectSearchResult(result)">
        <span class="global-search-result-type">{{ result.type }}</span>
        <span class="global-search-result-copy"><strong>{{ result.title }}</strong><small>{{ result.subtitle }}</small></span>
        <el-icon><ArrowRight /></el-icon>
      </button>
      <el-empty v-if="!globalSearchLoading && !globalSearchResults.length" :description="globalSearchTerm.trim() ? '没有匹配的功能或资源' : '输入关键词开始搜索'" />
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, ArrowRight, Expand, Fold, Search } from '@element-plus/icons-vue'
import PlatformNavigation from '@/components/PlatformNavigation.vue'
import { navigationForPath, pageHeadingForPath, searchNavigation, searchPageSections } from '@/navigation/catalog'
import { LteAppContent } from '@adminlte/vue'
import { ElMessage } from 'element-plus'
import { clearAccessToken, getAccessToken, setAccessToken } from '@/api/auth'
import { EapApi } from '@/api/eap'
import { startOidcSignIn } from '@/api/oidc'
import { loadCurrentUser, usePermissions } from '@/stores/permissions'
const { state: permissionState } = usePermissions()
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
const navigationLocation = computed(() => navigationForPath(route.path))
const routeTitle = computed(() => pageHeadingForPath(route.path, route.query).title)
const compactViewport = ref(false), mobileNavigationOpen = ref(false)
const sidebarCollapsed = ref(false)
let compactViewportQuery: MediaQueryList | undefined
const authDialogVisible = ref(false)
const authTokenDraft = ref('')
const signingIn = ref(false)
const savingToken = ref(false)
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
const currentFontScaleLabel = computed(() => fontScaleOptions.find(option => option.value === fontScale.value)?.label || '舒适')

watch(() => route.path, () => { mobileNavigationOpen.value = false })

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

async function signIn() {
  signingIn.value = true
  try {
    const status = await EapApi.getPlatformStatus()
    platformStatus.value = status
    if (!status.oidc) throw new Error('平台尚未配置企业登录，请使用手工令牌')
    await startOidcSignIn(status.oidc, route.fullPath)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '无法发起企业登录')
  } finally { signingIn.value = false }
}

async function saveAuthToken() {
  savingToken.value = true
  setAccessToken(authTokenDraft.value)
  try {
    if (!await loadCurrentUser()) return
    authDialogVisible.value = false
    authTokenDraft.value = ''
    window.location.reload()
  } catch {
    clearAccessToken()
  } finally { savingToken.value = false }
}

function syncAuthentication() {
  hasAccessToken.value = Boolean(getAccessToken())
}

function logout() {
  clearAccessToken()
  window.location.reload()
}

function requireAuthentication() {
  if (route.path !== '/auth/callback') openAuthDialog()
}

function openSearchDialog() {
  searchDialogVisible.value = true
  if (globalSearchTerm.value.trim()) void runGlobalSearch()
}

async function runGlobalSearch() {
  const term = globalSearchTerm.value.trim().toLowerCase()
  const requestId = ++searchRequestId
  if (!term) {
    globalSearchLoading.value = false
    globalSearchResults.value = []
    return
  }
  const pageResults: GlobalSearchResult[] = [...searchNavigation(term), ...searchPageSections(term)].map(item => ({type:'功能页面',title:item.label,subtitle:item.description,path:item.path,query:item.query || {}}))
  globalSearchResults.value = pageResults
  globalSearchLoading.value = true
  try {
    const [agents, assets, policies] = await Promise.all([
      EapApi.listAgents(),
      EapApi.listDiscoveredAgentAssets(),
      EapApi.listPolicies(),
    ])
    if (requestId !== searchRequestId) return
    globalSearchResults.value = [
      ...pageResults,
      ...agents.filter(agent => matchesSearch(agent, term)).slice(0, 8).map(agentResult),
      ...assets.filter(asset => matchesSearch(asset, term)).slice(0, 8).map(assetResult),
      ...policies.filter(policy => matchesSearch(policy, term)).slice(0, 8).map(policyResult),
    ].slice(0, 20)
  } catch {
    if (requestId === searchRequestId) globalSearchResults.value = pageResults
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
    // The initial lazy route must resolve before deciding whether to open the login dialog.
    await router.isReady()
    platformStatus.value = await EapApi.getPlatformStatus()
    if (route.path !== '/auth/callback') {
      if (platformStatus.value.securityMode === 'DEVELOPMENT' || getAccessToken()) await loadCurrentUser()
      else requireAuthentication()
    }
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
  compactViewport.value = Boolean(compactViewportQuery?.matches)
  mobileNavigationOpen.value = false
  if (compactViewportQuery?.matches) sidebarCollapsed.value = false
  else if (event && !event.matches) sidebarCollapsed.value = false
}

onMounted(() => {
  setFontScale(fontScale.value)
  compactViewportQuery = window.matchMedia('(max-width: 900px)')
  syncSidebarForViewport()
  compactViewportQuery.addEventListener('change', syncSidebarForViewport)
  window.addEventListener('eap-auth-required', requireAuthentication)
  window.addEventListener('eap-auth-changed', syncAuthentication)
  document.addEventListener('visibilitychange', handleVisibilityChange)
  void checkForBuildUpdate()
  void loadPlatformStatus()
  versionCheckTimer = window.setInterval(() => void checkForBuildUpdate(), 60_000)
})
onBeforeUnmount(() => {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  window.removeEventListener('eap-auth-required', requireAuthentication)
  window.removeEventListener('eap-auth-changed', syncAuthentication)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  compactViewportQuery?.removeEventListener('change', syncSidebarForViewport)
  if (versionCheckTimer) window.clearInterval(versionCheckTimer)
})
</script>

<style scoped>
/* AdminLTE sidebar / topbar / content regions with EAP auth and Element Plus controls. */
.console-template-layout { display: grid; grid-template-columns: auto minmax(0, 1fr); min-height: 100dvh; }
.console-main-column { min-width: 0; }
.console-page-title-sr { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.app-shell .breadcrumb-current { color: var(--console-ink); font-weight: 600; }
.app-shell .breadcrumb-line { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 10px; font-size: 13px; line-height: 1.6; }
.app-shell :deep(.app-content > .container-fluid) { min-width: 0; }

.app-shell .sidebar { position: sticky; top: 0; height: 100dvh; flex-shrink: 0; }
.app-shell .sidebar-nav { height: calc(100dvh - 174px); overflow-y: auto; padding: 14px 8px; }
.app-shell .topbar { min-height: 76px; height: auto; padding: 12px 24px; gap: 20px; flex-wrap: wrap; backdrop-filter: none; }
.app-shell .topbar-title { min-width: 0; flex: 1; }
.app-shell .topbar-actions { gap: 8px; flex-wrap: wrap; }
.app-shell .global-search-trigger { color: var(--console-muted); border-color: var(--console-line); background: var(--console-canvas); margin: 0; }
.app-shell .topbar-actions .el-button { margin-left: 0; }
.app-shell .main { min-width: 0; padding: 16px 24px 32px; }
@media (max-width: 900px) {
  .console-template-layout { grid-template-columns: minmax(0, 1fr); }
  .app-shell .topbar { padding: 12px 16px; gap: 12px; }
  .app-shell .main { padding: 12px 14px 28px; }
}
@media (max-width: 600px) {
  .app-shell .topbar-actions { flex-basis: 100%; justify-content: flex-end; }
  .app-shell .global-search-trigger { margin-right: auto; min-height: 36px; }
  .app-shell .topbar-actions .font-scale-menu, .app-shell .topbar-compact-control { display: inline-flex; }
  .app-shell .topbar-actions .el-button { font-size: 12px; }
  .app-shell .topbar-title { min-width: 160px; }
}

.project-identity {
  display: block;
  max-width: 180px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
