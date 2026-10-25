<template>
  <section v-if="available || hasContract" class="observation-contract" aria-label="模型观测约定">
    <el-form-item label="模型调用观测">
      <el-select :model-value="value" :disabled="disabled || !parsed" aria-label="模型调用观测" @change="change">
        <el-option label="默认（预期 1 次）" value="DEFAULT" />
        <el-option label="预期 1 次模型调用" value="ONE" :disabled="!available" />
        <el-option label="不预期模型调用" value="NONE" :disabled="!available" />
      </el-select>
    </el-form-item>
    <p>按程序的实际执行方式设置。保存节点后，约定随新的执行提交固定；已有执行保持原约定。</p>
    <p>用于判断观测是否完整，不会禁止模型调用。不预期时出现调用仍标为异常，Agent 观测与原回执仍须匹配。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { taskConfig, observationContractError, setObservationContract } from '@/api/workflowTaskConfig'
const props = defineProps<{ modelValue?: string; nodeType: string; agentId?: number; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const parsed = computed(() => { try { return taskConfig(props.modelValue) } catch { return null } })
const hasContract = computed(() => !!parsed.value && Object.hasOwn(parsed.value, 'eapObservation'))
const available = computed(() => !!parsed.value && !!props.agentId && ['AGENT', 'ARTIFACT'].includes(props.nodeType.toUpperCase())
  && !Object.hasOwn(parsed.value, 'eapTool') && (!Object.hasOwn(parsed.value, 'executionBackend') || parsed.value.executionBackend === 'RUNTIME'))
const error = computed(() => observationContractError(props.modelValue, props.nodeType, props.agentId))
const value = computed(() => hasContract.value ? parsed.value?.eapObservation?.modelEvents || 'INVALID' : 'DEFAULT')
function change(next: string) {
  if (props.disabled || !['DEFAULT', 'ONE', 'NONE'].includes(next) || (next !== 'DEFAULT' && !available.value)) return
  try { emit('update:modelValue', setObservationContract(props.modelValue, next as 'DEFAULT' | 'ONE' | 'NONE')) }
  catch (e) { ElMessage.error((e as Error).message) }
}
</script>
<style scoped>
.observation-contract { min-width: 0; border-top: 1px solid var(--el-border-color); margin-top: 18px; padding-top: 16px; }
.observation-contract p { font-size: 12px; line-height: 1.7; color: var(--el-text-color-secondary); overflow-wrap: anywhere; }
.observation-contract :deep(.el-select) { min-width: 0; width: 100%; }
</style>
