<template>
  <el-container class="app-shell">
    <el-aside width="248px" class="sidebar">
      <div class="brand">
        <img class="brand-logo" src="/eap-logo.svg" alt="EAP logo" />
        <div>
          <strong>Enterprise Agent Platform</strong>
          <span>EAP Control Plane</span>
        </div>
      </div>
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
      <div class="sidebar-status">
        <span class="status-dot"></span>
        <div>
          <strong>Safety Gateway</strong>
          <span>EAP_LOCAL_POLICY</span>
        </div>
      </div>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="topbar-title">
          <span>Enterprise Agent Platform · EAP</span>
          <h1>{{ routeTitle }}</h1>
        </div>
        <div class="topbar-actions">
          <el-input class="global-search" placeholder="搜索智能体、任务、策略" :prefix-icon="Search" />
          <el-tag type="success" effect="plain">Local</el-tag>
          <el-button :icon="Bell" circle title="通知" />
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Bell, Collection, Connection, Cpu, DataAnalysis, DataBoard, DocumentChecked, Lock, Money, Monitor, ScaleToOriginal, Search, Share, TrendCharts } from '@element-plus/icons-vue'

const route = useRoute()
const routeTitle = computed(() => String(route.meta.title ?? '工作台'))
</script>
