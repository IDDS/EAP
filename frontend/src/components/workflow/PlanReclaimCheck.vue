<script setup lang="ts">
import { computed } from 'vue'
import type { PlanReclaimReadiness } from '@/api/planReclaim'

const props = defineProps<{ result?: Readonly<PlanReclaimReadiness>; loading: boolean; error: string }>()
const emit = defineEmits<{ refresh: [] }>()
const blockers = computed(() => props.result?.steps.filter(step => !step.allowed) ?? [])
</script>

<template>
  <section class="reclaim-check" aria-label="自动休眠检查" aria-live="polite" :aria-busy="loading">
    <div class="check-heading">
      <strong>{{ loading ? '正在检查自动休眠条件…' : error ? '检查未完成' : result?.allowed ? '当前可申请自动休眠' : '自动休眠暂不可用' }}</strong>
      <el-button size="small" :loading="loading" @click="emit('refresh')">重新检查</el-button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <ul v-else-if="blockers.length" class="blockers">
      <li v-for="step in blockers" :key="step.stepId"><strong>步骤 {{ step.stepOrder }}：</strong>{{ step.message }}</li>
    </ul>
    <p v-else-if="result && !result.steps.length">请先添加并分配计划步骤。</p>
    <p class="check-note">检查不会派发任务，也不会清除历史占用。结果仅反映当前状态，提交和派发时会复查；执行期间新增占用仍可能阻止休眠。</p>
  </section>
</template>

<style scoped>
.reclaim-check { padding: 14px 16px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-fill-color-light); overflow-wrap: anywhere; }
.check-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.blockers { padding-left: 20px; margin: 12px 0; }
.blockers li + li { margin-top: 8px; }
.check-note { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.7; margin-bottom: 0; }
</style>
