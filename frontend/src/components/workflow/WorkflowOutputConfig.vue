<template>
  <section class="output-contract" aria-label="结果契约">
    <h4>结果契约</h4>
    <p>在已确认完成的回执上检查结果结构；未通过时保存原文并停止下游交接。</p>
    <el-form-item label="结果来源">
      <el-select :model-value="source" :disabled="disabled || !parsed" aria-label="结果来源" @change="changeSource">
        <el-option label="不设结构契约" value="OFF" />
        <el-option label="文本中的 JSON（模型任务）" value="JSON_TEXT" :disabled="backend !== 'RUNTIME'" />
        <el-option label="回执 JSON 对象（工具或自定义程序）" value="OUTPUT" :disabled="backend !== 'RUNTIME'" />
      </el-select>
    </el-form-item>
    <template v-if="source !== 'OFF'">
      <el-form-item label="契约版本">
        <el-select :model-value="version" :disabled="disabled || !parsed" aria-label="结果契约版本" @change="changeVersion">
          <el-option label="V1：结构校验" value="EAP_JSON_V1" />
          <el-option label="V2：结构与布尔预期值" value="EAP_JSON_V2" />
        </el-select>
      </el-form-item>
      <template v-if="version === 'EAP_JSON_V2'">
        <p>添加必填的布尔验收字段。只有返回指定值才允许继续；仍需核实结果是否真实。</p>
        <el-form-item label="验收字段名称">
          <el-input v-model="acceptanceField" :disabled="disabled" maxlength="128" aria-label="验收字段名称" placeholder="例如 verified" />
        </el-form-item>
        <el-form-item label="要求返回">
          <el-select v-model="acceptanceExpected" :disabled="disabled" aria-label="验收字段预期值">
            <el-option label="true（通过）" :value="true" />
            <el-option label="false" :value="false" />
          </el-select>
        </el-form-item>
        <el-button :disabled="disabled || !parsed" @click="addAcceptanceField">添加必填验收字段</el-button>
      </template>
      <el-form-item label="JSON 结构">
        <el-input v-model="schemaText" aria-label="结果 JSON 结构" type="textarea" :rows="9" :disabled="disabled || !parsed" @input="changeSchema" />
      </el-form-item>
      <p>对象须填写 properties、required、additionalProperties；数组须填写 items。V2 的 boolean 可加 <code>"const": true</code> 或 <code>"const": false</code>；需要字段必须存在时，还须将字段名加入 required。其他 JSON Schema 关键字不支持。</p>
      <p>文本必须是完整 JSON，不接受代码围栏、重复键或尾随文字。契约检查结构及配置的预期值，不会修改提示词或保证业务结论正确。{{ planStep ? '保存步骤或结果契约后生效。' : '保存节点后生效。' }}</p>
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { taskConfig, outputContractError, setOutputContract, setOutputContractVersion, addRequiredBooleanExpectation, updateOutputSchema } from '@/api/workflowTaskConfig'
const props = defineProps<{ modelValue?: string; nodeType: string; agentId?: number; disabled: boolean; planStep?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const parsed = computed(() => { try { return taskConfig(props.modelValue) } catch { return null } })
const source = computed(() => parsed.value?.eapOutput?.source || 'OFF')
const version = computed(() => parsed.value?.eapOutput?.version || 'EAP_JSON_V1')
const backend = computed(() => parsed.value?.executionBackend || 'RUNTIME')
const error = computed(() => outputContractError(props.modelValue, props.nodeType, props.agentId, props.planStep === true))
const schemaText = ref('')
const acceptanceField = ref('')
const acceptanceExpected = ref(true)
let lastEmitted: string | undefined
watch(() => props.modelValue, value => {
  if (value === lastEmitted) return
  acceptanceField.value = ''
  acceptanceExpected.value = true
  const schema = parsed.value?.eapOutput?.schema
  schemaText.value = typeof schema === 'string' ? schema : schema ? JSON.stringify(schema, null, 2) : ''
}, { immediate: true })
function publish(value: string) { lastEmitted = value; emit('update:modelValue', value) }
function changeSource(value: string) {
  if (props.disabled || !['OFF', 'OUTPUT', 'JSON_TEXT'].includes(value)) return
  try {
    const config = setOutputContract(props.modelValue, value as 'OFF' | 'OUTPUT' | 'JSON_TEXT')
    const schema = taskConfig(config).eapOutput?.schema
    schemaText.value = typeof schema === 'string' ? schema : schema ? JSON.stringify(schema, null, 2) : ''
    publish(config)
  } catch (e) { ElMessage.error((e as Error).message) }
}
function changeSchema(value: string) {
  if (props.disabled) return
  try { publish(updateOutputSchema(props.modelValue, value)) } catch { ElMessage.error('请先修正高级配置') }
}
function changeVersion(value: string) {
  if (props.disabled || !['EAP_JSON_V1', 'EAP_JSON_V2'].includes(value)) return
  try { publish(setOutputContractVersion(props.modelValue, value as 'EAP_JSON_V1' | 'EAP_JSON_V2')) } catch { ElMessage.error('请先修正结果契约') }
}
function addAcceptanceField() {
  if (props.disabled) return
  try {
    const config = addRequiredBooleanExpectation(props.modelValue, acceptanceField.value, acceptanceExpected.value)
    schemaText.value = JSON.stringify(taskConfig(config).eapOutput.schema, null, 2)
    publish(config)
    acceptanceField.value = ''
  } catch (e) { ElMessage.error((e as Error).message) }
}
</script>
<style scoped>
.output-contract { min-width: 0; border-top: 1px solid var(--el-border-color); margin-top: 18px; padding-top: 8px; }
.output-contract h4 { margin: 8px 0; }
.output-contract p { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.7; overflow-wrap: anywhere; }
.output-contract :deep(.el-select) { min-width: 0; width: 100%; }
</style>
