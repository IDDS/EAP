<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { navigationForPath, navigationGroups, pageNavigationForPath, pageSections, workspaceDestinations } from '@/navigation/catalog'
import type { NavigationDestination } from '@/navigation/catalog'
import { navigationIcons } from '@/navigation/icons'

const props = withDefaults(defineProps<{ collapsed?: boolean }>(), { collapsed: false })
const emit = defineEmits<{ navigate: []; expand: [] }>()
const route = useRoute()
const location = computed(() => navigationForPath(route.path))
const page = computed(() => pageNavigationForPath(route.path, route.query))
const expandedGroup = shallowRef('')
const groups = computed(() => navigationGroups.map(group => ({ ...group, destinations: workspaceDestinations(group, route.path, route.query) })))
const homeSections = pageSections['/']

watch(() => route.path, () => {
  expandedGroup.value = location.value.group?.id || (route.path === '/' ? 'home' : '')
}, { immediate: true })
function toggleGroup(id: string) {
  if (props.collapsed) { emit('expand'); expandedGroup.value = id }
  else expandedGroup.value = expandedGroup.value === id ? '' : id
}
function isCurrent(item: NavigationDestination) {
  if (item.path !== route.path) return false
  return item.section ? item.section === page.value.active : !page.value.section
}
</script>

<template>
  <nav class="workspace-navigation" :class="{ 'is-compact': collapsed }" aria-label="平台功能导航">
    <section class="nav-group">
      <div class="home-row">
        <router-link to="/" aria-label="工作台" aria-current-value="false" :title="collapsed ? '工作台' : undefined" class="workspace-link home-link" :class="{ 'is-current-group': route.path === '/' }" @click="emit('navigate')">
          <el-icon aria-hidden="true"><component :is="navigationIcons.House" /></el-icon><span v-if="!collapsed">工作台</span>
        </router-link>
        <button v-if="!collapsed" type="button" class="home-toggle" aria-label="展开或收起工作台视图" :aria-expanded="expandedGroup === 'home'" @click="toggleGroup('home')"><el-icon :class="{ open: expandedGroup === 'home' }" aria-hidden="true"><ArrowDown /></el-icon></button>
      </div>
      <ul v-if="!collapsed && expandedGroup === 'home'" class="nav-items" aria-label="工作台视图">
        <li v-for="section in homeSections" :key="section.value"><router-link :to="{ path: '/', query: { ...(route.path === '/' ? route.query : {}), section: section.value } }" class="nav-item" :aria-current="route.path === '/' && page.active === section.value ? 'page' : undefined" @click="emit('navigate')">{{ section.label }}</router-link></li>
      </ul>
    </section>
    <p v-if="!collapsed" class="navigation-caption">工作区</p>
    <section v-for="group in groups" :key="group.id" class="nav-group">
      <button type="button" class="workspace-link group-toggle" :class="{ 'is-current-group': location.group?.id === group.id }" :aria-label="group.label" :title="collapsed ? group.label : undefined" :aria-expanded="!collapsed && expandedGroup === group.id" @click="toggleGroup(group.id)">
        <el-icon aria-hidden="true"><component :is="navigationIcons[group.icon]" /></el-icon><span v-if="!collapsed">{{ group.label }}</span>
        <el-icon v-if="!collapsed" class="disclosure-arrow" :class="{ open: expandedGroup === group.id }" aria-hidden="true"><ArrowDown /></el-icon>
      </button>
      <ul v-if="!collapsed && expandedGroup === group.id" class="nav-items" :aria-label="`${group.label}功能`">
        <li v-for="item in group.destinations" :key="item.key">
          <router-link :to="{ path: item.path, query: item.query }" class="nav-item" :title="item.context ? `${item.context}：${item.label}` : item.description" :aria-current="isCurrent(item) ? 'page' : undefined" @click="emit('navigate')">{{ item.label }}</router-link>
        </li>
      </ul>
    </section>
    <router-link to="/command-center" aria-label="运行大屏" :title="collapsed ? '运行大屏' : undefined" class="workspace-link screen-link" @click="emit('navigate')"><el-icon aria-hidden="true"><component :is="navigationIcons.Monitor" /></el-icon><span v-if="!collapsed">运行大屏</span></router-link>
  </nav>
</template>

<style scoped>
.workspace-navigation { display: grid; gap: 4px; padding: 0 4px; }
.workspace-link { display: flex; align-items: center; gap: 11px; min-height: 44px; width: 100%; padding: 10px 12px; border: 0; border-radius: 4px; background: transparent; color: #d0d7de; text-decoration: none; font: inherit; font-size: 14px; text-align: left; cursor: pointer; }
.workspace-link > .el-icon { flex: 0 0 18px; font-size: 18px; color: #aebbc6; }
.workspace-link:hover, .nav-item:hover, .home-toggle:hover { background: #324450; color: #fff; }
.group-toggle { font-weight: 550; }
.workspace-link.is-current-group { background: #30414f; color: #fff; }
.home-row { display: flex; }.home-link { flex: 1; min-width: 0; }
.home-toggle { flex: 0 0 32px; border: 0; border-radius: 4px; color: #b8c7d3; background: transparent; cursor: pointer; }
.disclosure-arrow { margin-left: auto; font-size: 12px !important; }
.disclosure-arrow:not(.open), .home-toggle .el-icon:not(.open) { transform: rotate(-90deg); }
.navigation-caption { margin: 16px 12px 6px; font-size: 11px; color: #aebbc6; }
.nav-items { list-style: none; margin: 4px 0 8px; padding: 0; }
.nav-item { display: flex; align-items: center; min-height: 38px; padding: 9px 12px 9px 42px; color: #c4cfd8; text-decoration: none; font-size: 13px; line-height: 1.5; border-radius: 4px; overflow-wrap: anywhere; }
.nav-item[aria-current='page'] { color: #fff; background: #087f9a; font-weight: 600; }
a:focus-visible, button:focus-visible { outline: 2px solid #8de1ee; outline-offset: -2px; }
.screen-link { margin-top: 12px; border-top: 1px solid #43525e; border-radius: 0; font-size: 13px; }
.is-compact { padding: 0; }.is-compact .workspace-link { justify-content: center; padding: 12px; }
@media (pointer: coarse) { .nav-item, .home-toggle { min-height: 44px; } }
</style>
