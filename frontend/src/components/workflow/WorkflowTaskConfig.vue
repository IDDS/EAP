<template>
  <div class="workflow-task-config">
    <el-form-item label="任务类型">
      <el-radio-group :model-value="mode" :disabled="disabled || !!parseError" @change="changeMode">
        <el-radio-button value="MODEL">模型 / 自定义任务</el-radio-button>
        <el-radio-button value="TOOL" :disabled="backend !== 'RUNTIME'">工具任务</el-radio-button>
      </el-radio-group>
    </el-form-item>
    <template v-if="mode === 'TOOL'">
      <el-form-item label="工具能力"><el-select :model-value="toolName" :disabled="disabled" aria-label="工具能力" @change="changeTool"><el-option label="求和计算" value="calculate_sum" /><el-option label="受控计数与效果核验" value="controlled_counter_increment" /></el-select></el-form-item>
      <template v-if="toolName==='controlled_counter_increment'">
        <p class="tool-help">工具服务在提交时复核整条授权链。稳定操作标识对应一次业务意图；重复执行节点仍返回原效果，不再增加计数。此任务不调用模型。</p>
        <el-form-item label="范围授权标识"><el-input :model-value="scoped.grantId" :disabled="disabled" aria-label="范围授权标识" @input="(v: string)=>changeScoped('grantId',v)" /></el-form-item>
        <el-form-item label="稳定操作标识"><el-input :model-value="scoped.operationId" :disabled="disabled" aria-label="稳定操作标识" @input="(v: string)=>changeScoped('operationId',v)" /></el-form-item>
        <el-form-item label="测试对象"><el-input :model-value="scoped.resourceId" :disabled="disabled" aria-label="测试对象" @input="(v: string)=>changeScoped('resourceId',v)" /></el-form-item>
        <el-form-item label="增量"><el-input-number :model-value="scoped.delta" :min="1" :max="100" :precision="0" :disabled="disabled" aria-label="计数增量" @change="(v: number|undefined)=>changeScoped('delta',v??0)" /></el-form-item>
      </template>
      <template v-else>
      <div class="tool-heading"><strong>求和计算</strong><span>受控工具</span></div>
      <p class="tool-help">在已接入的智能体实例内计算；执行前检查策略，完成后记录回执。此任务不调用模型。</p>
      <el-form-item label="计算数字">
        <el-input v-model="numbersText" aria-label="计算数字" type="textarea" :rows="4" :disabled="disabled" placeholder="输入数字，用逗号或换行分隔" @input="changeNumbers" />
      </el-form-item>
      <p class="tool-help">最多 100 个数字，单个数字绝对值不超过 10^12。保存节点后生效。</p>
      <p v-if="!error" class="tool-summary">已配置 {{ count }} 个数字 · 执行时使用已保存的参数</p>
      </template>
    </template>
    <el-alert v-if="error || parseError" :title="error || parseError" type="error" :closable="false" />
    <p v-if="mode === 'TOOL'" class="tool-help">实例需启用受控工具并通过专属凭证验证；不支持时停止执行。当前支持求和和受控计数；计数授权与对象在实例的工具权限页管理。</p>
    <WorkflowObservationConfig :model-value="modelValue" :node-type="nodeType" :agent-id="agentId" :disabled="disabled" @update:model-value="publish" />
    <WorkflowOutputConfig :model-value="modelValue" :node-type="nodeType" :agent-id="agentId" :disabled="disabled" @update:model-value="publish" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import WorkflowOutputConfig from './WorkflowOutputConfig.vue'
import WorkflowObservationConfig from './WorkflowObservationConfig.vue'
import { ElMessage } from 'element-plus'
import { taskConfig, toolConfigError, updateToolNumbers, setTaskMode } from '@/api/workflowTaskConfig'
const props = defineProps<{ modelValue?: string; nodeType: string; agentId?: number; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const parsed = computed(() => { try { return taskConfig(props.modelValue) } catch { return null } })
const parseError = computed(() => parsed.value ? '' : '请先修正高级配置中的 JSON 对象')
const mode = computed(() => parsed.value && Object.hasOwn(parsed.value, 'eapTool') ? 'TOOL' : 'MODEL')
const backend = computed(() => parsed.value?.executionBackend || 'RUNTIME')
const error = computed(() => toolConfigError(props.modelValue, props.nodeType, props.agentId))
const count = computed(() => parsed.value?.eapTool?.arguments?.numbers?.length || 0)
const toolName=computed(()=>parsed.value?.eapTool?.name||'calculate_sum')
const scoped=computed(()=>parsed.value?.eapTool?.arguments||{})
const numbersText = ref('')
let lastEmitted: string | undefined
watch(() => props.modelValue, value => {
  if (value === lastEmitted) return
  const numbers = parsed.value?.eapTool?.arguments?.numbers
  numbersText.value = Array.isArray(numbers) ? numbers.join(', ') : typeof numbers === 'string' ? numbers : ''
}, { immediate: true })
function publish(value: string) { lastEmitted = value; emit('update:modelValue', value) }
function changeMode(value: string | number | boolean | undefined) {
  if (props.disabled || !['MODEL', 'TOOL'].includes(String(value))) return
  try { numbersText.value = ''; publish(setTaskMode(props.modelValue, value as 'MODEL' | 'TOOL')) }
  catch (e) { ElMessage.error((e as Error).message) }
}
function changeTool(value: string) {
  if(props.disabled||!['calculate_sum','controlled_counter_increment'].includes(value))return
  try{const config=taskConfig(props.modelValue);config.eapTool=value==='calculate_sum'?{name:value,arguments:{numbers:[]}}:{name:value,arguments:{grantId:'',operationId:crypto.randomUUID(),resourceId:'',delta:1}};publish(JSON.stringify(config,null,2))}catch{ElMessage.error('请先修正高级配置')}
}
function changeScoped(key: string,value: string|number) {
  if(props.disabled||toolName.value!=='controlled_counter_increment')return
  try{const config=taskConfig(props.modelValue);config.eapTool.arguments={...config.eapTool.arguments,[key]:value};publish(JSON.stringify(config,null,2))}catch{ElMessage.error('请先修正高级配置')}
}
function changeNumbers(value: string) {
  if (props.disabled) return
  try { publish(updateToolNumbers(props.modelValue, value)) }
  catch { ElMessage.error('请先修正高级配置') }
}
</script>

<style scoped>
.workflow-task-config { min-width: 0; }
.tool-heading { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
.tool-heading span { color: #2363b5; background: #edf4ff; border-radius: 4px; padding: 2px 6px; font-size: 11px; }
.tool-help { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.65; margin: 8px 0 14px; overflow-wrap: anywhere; }
.tool-summary { color: #237454; font-size: 12px; line-height: 1.6; }
.el-radio-group { flex-wrap: wrap; }
</style>
