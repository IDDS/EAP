<template>
  <span class="status-axis" :class="`status-axis-${presentation.tone}`">
    <i />
    <span>{{ presentation.label }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

type Axis = 'lifecycle' | 'health' | 'onboarding' | 'coverage' | 'risk' | 'freshness'

const props = defineProps<{ axis: Axis; value: string }>()

const labels: Record<Axis, Record<string, { label: string; tone: string }>> = {
  lifecycle: {
    DRAFT: { label: '草稿', tone: 'neutral' }, REVIEWING: { label: '审核中', tone: 'info' },
    ACTIVE: { label: '已启用', tone: 'success' }, PAUSED: { label: '已暂停', tone: 'warning' }, RETIRED: { label: '已下线', tone: 'neutral' },
  },
  health: {
    HEALTHY: { label: '健康', tone: 'success' }, DEGRADED: { label: '降级', tone: 'warning' },
    OFFLINE: { label: '离线', tone: 'danger' }, NOT_CONNECTED: { label: '未接入', tone: 'neutral' },
  },
  onboarding: {
    NEEDS_REVIEW: { label: '待复核', tone: 'warning' }, NEEDS_OWNER: { label: '待认领', tone: 'danger' },
    MANAGED: { label: '已纳管', tone: 'success' }, MANUAL: { label: '手工登记', tone: 'info' },
  },
  coverage: {
    FULL: { label: '完整', tone: 'success' }, PARTIAL: { label: '部分', tone: 'warning' }, MISSING: { label: '缺失', tone: 'danger' },
  },
  risk: {
    NORMAL: { label: '正常', tone: 'success' }, ATTENTION: { label: '关注', tone: 'warning' },
    HIGH: { label: '高风险', tone: 'danger' }, CRITICAL: { label: '关键风险', tone: 'critical' },
  },
  freshness: {
    FRESH: { label: '新鲜', tone: 'success' }, AGING: { label: '临近过期', tone: 'warning' },
    STALE: { label: '已过期', tone: 'danger' }, NO_DATA: { label: '无数据', tone: 'neutral' },
  },
}

const presentation = computed(() => labels[props.axis][props.value] || { label: props.value || '未知', tone: 'neutral' })
</script>
