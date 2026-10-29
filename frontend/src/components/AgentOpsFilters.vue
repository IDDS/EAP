<script setup lang="ts">
import { useAgentOpsContextStore } from '@/stores/agentOps'
withDefaults(defineProps<{ description: string; organization?: boolean }>(), { organization: true })
const context = useAgentOpsContextStore()
</script>

<template>
  <section class="agentops-scope" aria-label="当前数据范围">
    <div class="agentops-scope-fields">
      <label>统计时间<el-select :model-value="context.windowHours" aria-label="当前页面统计时间" @change="context.setWindowHours(Number($event))">
        <el-option label="最近 1 小时" :value="1" /><el-option label="最近 6 小时" :value="6" /><el-option label="最近 24 小时" :value="24" /><el-option label="最近 3 天" :value="72" /><el-option label="最近 7 天" :value="168" />
      </el-select></label>
      <label v-if="organization">组织范围<el-input :model-value="context.organizationScope" aria-label="当前页面组织范围" clearable placeholder="企业全局" @change="context.setOrganizationScope(String($event || ''))" /></label>
    </div>
    <p>{{ description }}</p>
  </section>
</template>

<style scoped>
.agentops-scope { display: flex; flex-wrap: wrap; align-items: end; gap: 12px 20px; padding: 14px 0; border-bottom: 1px solid var(--console-line); }
.agentops-scope-fields { display: flex; flex-wrap: wrap; gap: 12px; }
.agentops-scope label { display: grid; gap: 6px; color: var(--console-muted); font-size: 12px; }
.agentops-scope .el-select { width: 160px; }
.agentops-scope .el-input { width: 200px; }
.agentops-scope p { margin: 0 0 7px; font-size: 12px; color: var(--console-muted); line-height: 1.6; max-width: 45em; }
@media (max-width: 500px) {
  .agentops-scope-fields { width: 100%; display: grid; grid-template-columns: 1fr 1fr; }
  .agentops-scope label, .agentops-scope .el-select, .agentops-scope .el-input { min-width: 0; width: 100%; }
}
</style>
