<template>
  <span class="source-status" :class="{ warning: failed || expired || paused || !updatedAt }" :title="updatedAt ? `最近接收数据：${new Date(updatedAt).toLocaleString('zh-CN')}` : '尚未收到该区域的数据'">
    <i aria-hidden="true" />{{ failed ? '更新失败' : !updatedAt ? '等待数据' : paused ? '已暂停' : expired ? '数据待更新' : '已更新' }}
    <span v-if="updatedAt && (failed || expired || paused)"> · {{ ageLabel }}</span>
  </span>
</template>
<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ updatedAt?: number; now: number; failed: boolean; paused: boolean }>()
const age = computed(() => Math.max(0, Math.floor((props.now - (props.updatedAt || props.now)) / 1000)))
const expired = computed(() => age.value >= 45)
const ageLabel = computed(() => age.value < 60 ? `${age.value} 秒前` : age.value < 3600 ? `${Math.floor(age.value / 60)} 分钟前` : `${Math.floor(age.value / 3600)} 小时前`)
</script>
<style scoped>
.source-status{display:inline-flex;align-items:center;gap:5px;font-size:11px;line-height:1.5;color:var(--cc-muted,#bdd2e7);white-space:nowrap}.source-status i{width:5px;height:5px;border-radius:50%;background:#47d6b0;flex:none}.source-status.warning{color:#edba67}.source-status.warning i{background:#edba67}
</style>
