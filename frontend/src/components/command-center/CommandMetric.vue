<template>
  <LteSmallBox class="command-metric" :style="{ '--metric-accent': accent }">
    <template #title><slot name="value" /></template>
    <template #icon><router-link class="metric-label" :to="to">{{ label }}<span aria-hidden="true">↗</span></router-link></template>
    <slot />
  </LteSmallBox>
</template>
<script setup lang="ts">
import { LteSmallBox } from '@adminlte/vue'
defineProps<{ label: string; to: string; accent: string }>()
</script>
<style scoped>
/* Component-only skin: no Bootstrap reset, global theme or plugin installation. */
.command-metric.small-box{display:block;position:relative;min-width:0;padding:42px 20px 14px;border:1px solid #4d7797;border-top:3px solid var(--metric-accent);border-radius:8px;background:#214f73!important;box-shadow:0 4px 12px #071e3020}
.metric-label{position:absolute;top:13px;left:20px;right:20px;display:flex;justify-content:space-between;gap:8px;color:#e1f1ff;font-size:14px;text-decoration:none}.metric-label:hover{color:#9ceaff}
.command-metric :deep(h3){margin:0;font-weight:500}.command-metric :deep(.inner>p){margin:0;color:#c6ddef;font-size:12px;line-height:1.65}.command-metric :deep(.source-status){display:flex;margin-top:7px}.command-metric :deep(strong){margin:4px 0 8px}
@media(max-width:800px){.command-metric.small-box{display:block;padding:40px 14px 12px}.metric-label{left:14px;right:14px;font-size:13px}}
.command-metric.small-box::before,.command-metric.small-box::after{display:none}.command-metric.small-box:hover{transform:none}.command-metric :deep(span){margin-top:0}.command-metric :deep(.metric-detail){color:#c6ddef}.command-metric .metric-label span{display:inline;color:inherit}.command-metric :deep(.source-status){color:#c6ddef}
</style>
