<template>
  <el-menu :key="`${collapsed ? 'compact' : 'expanded'}:${location.openGroups.join(',')}`" router unique-opened
    :collapse="collapsed" :collapse-transition="false" :default-active="location.activePath" :default-openeds="location.openGroups"
    class="nav-menu nav-menu-workspaces platform-navigation" aria-label="平台功能导航" @select="emit('navigate')">
    <el-menu-item v-for="item in navigationShortcuts" :key="item.path" :index="item.path" :aria-current="location.activePath === item.path ? 'page' : undefined">
      <el-icon aria-hidden="true"><component :is="navigationIcons[item.icon]" /></el-icon><template #title><span>{{ item.label }}</span></template>
    </el-menu-item>
    <template v-for="group in navigationGroups" :key="group.id">
      <el-menu-item v-if="group.standalone" :index="group.items[0].path" :aria-current="location.activePath === group.items[0].path ? 'page' : undefined">
        <el-icon aria-hidden="true"><component :is="navigationIcons[group.icon]" /></el-icon><template #title><span>{{ group.label }}</span></template>
      </el-menu-item>
      <el-sub-menu v-else :index="group.id" :class="{'current-workspace': location.group?.id === group.id}">
        <template #title><el-icon aria-hidden="true"><component :is="navigationIcons[group.icon]" /></el-icon><span>{{ group.label }}</span></template>
        <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path" :aria-current="location.activePath === item.path ? 'page' : undefined">
          <el-icon aria-hidden="true"><component :is="navigationIcons[item.icon]" /></el-icon><span>{{ item.label }}</span>
        </el-menu-item>
      </el-sub-menu>
    </template>
  </el-menu>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { navigationForPath, navigationGroups, navigationShortcuts } from '@/navigation/catalog'
import { navigationIcons } from '@/navigation/icons'
withDefaults(defineProps<{collapsed?:boolean}>(),{collapsed:false})
const emit=defineEmits<{navigate:[]}>()
const route=useRoute()
const location=computed(()=>navigationForPath(route.path))
</script>
