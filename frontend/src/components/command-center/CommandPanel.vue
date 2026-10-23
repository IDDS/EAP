<template>
  <LteCard class="command-panel cc-panel">
    <template #header>
      <div class="panel-heading"><h2>{{ title }}</h2><button :aria-expanded="!collapsed" :aria-controls="bodyId" @click="collapsed = !collapsed">{{ collapsed ? '展开' : '收起' }}</button></div>
    </template>
    <div v-show="!collapsed" :id="bodyId"><slot /></div>
  </LteCard>
</template>
<script setup lang="ts">
import { ref, useId } from 'vue'
import { LteCard } from '@adminlte/vue'
defineProps<{ title: string }>()
const collapsed = ref(false)
const bodyId = useId()
</script>
<style scoped>
.panel-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;padding-bottom:10px;border-bottom:1px solid #4e7594}.panel-heading h2{font-size:16px;font-weight:500;margin:0;color:#f3faff}.panel-heading button{background:transparent;border:1px solid #6590b1;border-radius:4px;color:#d8edfc;padding:4px 8px;cursor:pointer;font:inherit;font-size:12px}.command-panel :deep(.card-body){padding:0}
</style>
