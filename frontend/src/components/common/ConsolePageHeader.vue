<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { pageHeadingForPath } from '@/navigation/catalog'

const props = defineProps<{ title?: string; description?: string }>()
const route = useRoute()
const heading = computed(() => pageHeadingForPath(route.path, route.query))
</script>

<template>
  <header class="console-page-header">
    <div class="console-header-copy">
      <h2>{{ props.title || heading.title }}</h2>
      <p>{{ props.description || heading.description }}</p>
    </div>
    <div v-if="$slots.actions" class="console-header-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.console-page-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px 24px; min-width: 0; padding: 8px 0 20px; border-bottom: 1px solid var(--console-line); margin: 0; }
.console-header-copy { flex: 1 1 300px; min-width: 0; }
.console-header-copy h2 { margin: 0 0 8px; color: var(--console-ink); font-size: 24px; font-weight: 650; line-height: 1.4; overflow-wrap: anywhere; }
.console-header-copy p { margin: 0; color: var(--console-muted); font-size: 13px; line-height: 1.8; max-width: 70ch; }
.console-header-actions { display: flex; flex: 0 1 auto; flex-wrap: wrap; justify-content: flex-end; align-items: center; gap: 10px; max-width: 58%; min-width: 0; }
.console-header-actions :deep(> div) { display: flex; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 10px; min-width: 0; }
.console-header-actions :deep(.el-button) { margin-left: 0; min-height: var(--console-control-height); }
.console-header-actions :deep(a) { color: var(--el-color-primary-dark-2); font-size: 13px; line-height: 1.8; text-decoration: none; }
.console-header-actions :deep(a:hover) { text-decoration: underline; }
.console-header-actions :deep(.context-summary) { flex-basis: 100%; text-align: right; color: var(--console-muted); font-size: 12px; }
@media (max-width: 1100px) { .console-page-header { flex-wrap: wrap; }.console-header-actions { max-width: 100%; }.console-header-copy { flex-basis: 100%; } }
@media (max-width: 600px) { .console-page-header { padding: 4px 0 16px; gap: 14px; }.console-header-copy h2 { font-size: 22px; }.console-header-actions, .console-header-actions :deep(> div) { width: 100%; justify-content: flex-start; }.console-header-actions :deep(.context-summary) { text-align: left; } }
</style>
