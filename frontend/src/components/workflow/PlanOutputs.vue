<template>
  <section class="plan-outputs">
    <h3>步骤结果</h3>
    <p class="muted">以下为保存的原始回执结果；结构验收结论见执行过程。保存结果不代表已获准交给下游。</p>
    <p v-if="!permissions.can('viewContent')" class="muted">当前账号无内容查看权限，仅可查看执行状态。</p>
    <template v-else>
      <div class="button-row">
        <el-button :loading="loading" @click="loadOutputs">查看步骤结果</el-button>
        <el-button v-if="outputs" @click="clearOutputs">隐藏结果</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template v-if="outputs">
        <p v-if="!Object.keys(outputs).length" class="muted">当前没有已保存的步骤结果。</p>
        <article v-for="(value, key) in outputs" :key="key" class="step-output">
          <h4>{{ nodeLabels?.[key] || steps?.find(s => `step-${s.id}` === key)?.title || key }}</h4>
          <pre>{{ formatOutput(value) }}</pre>
        </article>
      </template>
    </template>
  </section>
</template>
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import type { AgentPlanStep } from '@/types/domain'
const props = defineProps<{ workflowId: number; runKey: string; steps?: AgentPlanStep[]; nodeLabels?: Record<string, string> }>()
const permissions = usePermissions()
const outputs = ref<Record<string, unknown> | null>(null)
const loading = ref(false)
const error = ref('')
let revision = 0
let disposed = false
function clearOutputs() {
  revision++
  outputs.value = null
  loading.value = false
  error.value = ''
}
async function loadOutputs() {
  if (!permissions.can('viewContent') || loading.value || disposed) return
  const request = ++revision
  const workflowId = props.workflowId
  const runKey = props.runKey
  loading.value = true
  error.value = ''
  outputs.value = null
  try {
    const result = await EapApi.getWorkflowOutputs(workflowId, runKey)
    if (!disposed && request === revision && workflowId === props.workflowId && runKey === props.runKey && permissions.can('viewContent')) outputs.value = result
  } catch {
    if (!disposed && request === revision) error.value = '结果读取失败，请检查内容权限后重试'
  } finally { if (request === revision) loading.value = false }
}
function formatOutput(value: unknown) {
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}
watch([() => props.workflowId, () => props.runKey, () => permissions.state.user, () => permissions.can('viewContent')], clearOutputs, { flush: 'sync' })
onUnmounted(() => { disposed = true; clearOutputs() })
</script>
<style scoped>
.plan-outputs { margin-top: 24px; }
.step-output pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 400px; overflow: auto; padding: 16px; background: var(--el-fill-color-light); border-radius: 6px; }
</style>
