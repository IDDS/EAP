<template>
  <el-tooltip :content="hint" placement="top">
    <span class="evidence-badge" :class="`evidence-${basis.toLowerCase()}`">{{ label }}</span>
  </el-tooltip>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { EvidenceBasis } from '@/types/domain'

const props = defineProps<{ basis: EvidenceBasis; source?: string; freshness?: string }>()

const labels: Record<EvidenceBasis, string> = {
  MEASURED: '实测', EXTERNAL_RECONCILED: '外部对账', HUMAN_CONFIRMED: '人工确认',
  DERIVED: '推导', ESTIMATED: '估算', DEMO: '演示',
}

const label = computed(() => labels[props.basis])
const hint = computed(() => [labels[props.basis], props.source, props.freshness].filter(Boolean).join(' · '))
</script>
