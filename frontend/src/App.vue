<template>
  <el-container class="app-shell">
    <el-aside :width="sidebarCollapsed ? '78px' : '264px'" :class="['sidebar', { collapsed: sidebarCollapsed }]">
      <div class="brand">
        <img class="brand-logo" src="/eap-logo.svg" alt="EAP logo" />
        <div class="brand-copy">
          <strong>Enterprise Agent Platform</strong>
          <span>EAP Control Plane</span>
        </div>
        <el-button class="sidebar-toggle" text circle :icon="sidebarCollapsed ? Expand : Fold" @click="sidebarCollapsed = !sidebarCollapsed" />
      </div>
      <div class="sidebar-nav">
        <div class="nav-section">控制台</div>
        <el-menu router :default-active="$route.path" class="nav-menu">
        <el-menu-item index="/">
          <el-icon><DataBoard /></el-icon>
          <span>工作台</span>
        </el-menu-item>
        <el-menu-item index="/agents">
          <el-icon><Cpu /></el-icon>
          <span>智能体管理</span>
        </el-menu-item>
        <el-menu-item index="/registry">
          <el-icon><Search /></el-icon>
          <span>注册发现</span>
        </el-menu-item>
        <el-menu-item index="/physical">
          <el-icon><DataAnalysis /></el-icon>
          <span>物理界面</span>
        </el-menu-item>
        <el-menu-item index="/knowledge">
          <el-icon><Collection /></el-icon>
          <span>知识界面</span>
        </el-menu-item>
        <el-menu-item index="/management">
          <el-icon><Money /></el-icon>
          <span>管理界面</span>
        </el-menu-item>
        <el-menu-item index="/operations">
          <el-icon><Monitor /></el-icon>
          <span>运行中心</span>
        </el-menu-item>
        <el-menu-item index="/orchestration">
          <el-icon><Connection /></el-icon>
          <span>团队编排</span>
        </el-menu-item>
        <el-menu-item index="/workflow-builder">
          <el-icon><Share /></el-icon>
          <span>工作流编排</span>
        </el-menu-item>
        <el-menu-item index="/learning">
          <el-icon><TrendCharts /></el-icon>
          <span>自适应学习</span>
        </el-menu-item>
        </el-menu>
        <div class="nav-section">治理与安全</div>
        <el-menu router :default-active="$route.path" class="nav-menu">
        <el-menu-item index="/governance">
          <el-icon><ScaleToOriginal /></el-icon>
          <span>治理中心</span>
        </el-menu-item>
        <el-menu-item index="/safety">
          <el-icon><Connection /></el-icon>
          <span>多智能体安全</span>
        </el-menu-item>
        <el-menu-item index="/ethics">
          <el-icon><Lock /></el-icon>
          <span>伦理安全</span>
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
          <strong>Safety Gateway</strong>
          <span>EAP_LOCAL_POLICY</span>
        </div>
      </div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-title">
          <div class="breadcrumb-line">
            <span class="topbar-kicker">CONTROL PLANE</span>
            <span class="breadcrumb-separator">/</span>
            <span>{{ routeTitle }}</span>
          </div>
          <h1>{{ routeTitle }}</h1>
          <p>让每一个智能体都可发现、可观测、可治理、可度量</p>
        </div>
        <div class="topbar-actions">
          <el-input class="global-search" placeholder="搜索智能体、任务、策略" :prefix-icon="Search" />
          <div class="workspace-pill"><span class="workspace-dot"></span><span>矩维智能 · 主空间</span></div>
          <el-tag class="auth-state" :type="hasAccessToken ? 'success' : 'info'" effect="plain">
            {{ hasAccessToken ? 'JWT 已连接' : 'Local / 未认证' }}
          </el-tag>
          <el-button class="auth-button" :icon="Lock" @click="openAuthDialog">{{ hasAccessToken ? '更新凭证' : '认证' }}</el-button>
          <el-button v-if="hasAccessToken" text @click="logout">退出</el-button>
          <el-button class="notification-button" :icon="Bell" circle title="通知" />
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>

  <el-dialog v-model="authDialogVisible" title="企业 JWT 认证" width="520px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="Token 只保存在当前标签页的 sessionStorage，刷新后保留，关闭标签页即清除。"
    />
    <el-form label-position="top" style="margin-top: 18px">
      <el-form-item label="Access Token">
        <el-input
          v-model="authTokenDraft"
          type="password"
          show-password
          clearable
          autocomplete="off"
          placeholder="粘贴企业 IdP 签发的 JWT，可包含 Bearer 前缀"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="authDialogVisible = false">取消</el-button>
      <el-button type="primary" :disabled="!authTokenDraft.trim()" @click="saveAuthToken">保存并连接</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Bell, Collection, Connection, Cpu, DataAnalysis, DataBoard, DocumentChecked, Expand, Fold, Lock, Money, Monitor, ScaleToOriginal, Search, Share, TrendCharts } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { clearAccessToken, getAccessToken, setAccessToken } from '@/api/auth'

const route = useRoute()
const routeTitle = computed(() => String(route.meta.title ?? '工作台'))
const sidebarCollapsed = ref(false)
const authDialogVisible = ref(false)
const authTokenDraft = ref('')
const hasAccessToken = ref(Boolean(getAccessToken()))

function openAuthDialog() {
  authTokenDraft.value = ''
  authDialogVisible.value = true
}

function saveAuthToken() {
  setAccessToken(authTokenDraft.value)
  hasAccessToken.value = Boolean(getAccessToken())
  authDialogVisible.value = false
  authTokenDraft.value = ''
  ElMessage.success('JWT 已保存，后续 API 请求将自动携带 Bearer Token')
}

function logout() {
  clearAccessToken()
  hasAccessToken.value = false
  ElMessage.success('当前标签页的 JWT 已清除')
}

function requireAuthentication() {
  if (!hasAccessToken.value) openAuthDialog()
}

onMounted(() => window.addEventListener('eap-auth-required', requireAuthentication))
onBeforeUnmount(() => window.removeEventListener('eap-auth-required', requireAuthentication))
</script>
