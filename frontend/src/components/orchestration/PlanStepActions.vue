<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { EapApi } from '@/api/eap'
import { editablePlan, reorderedStepIds, useOrchestrationMutation } from '@/api/orchestrationEditing'
import { planOutputConfig, planOutputValue, taskConfig } from '@/api/workflowTaskConfig'
import { usePermissions } from '@/stores/permissions'
import WorkflowOutputConfig from '@/components/workflow/WorkflowOutputConfig.vue'
import type { AgentPlan, AgentPlanStep } from '@/types/domain'

const props = defineProps<{ plan: AgentPlan; step: AgentPlanStep; steps: AgentPlanStep[]; disabled?: boolean }>()
const emit = defineEmits<{ changed: []; working: [value: boolean] }>()
const permissions = usePermissions()
const allowed = () => permissions.can('runWorkflows') && editablePlan(props.plan)
const scope = () => [props.plan.id, props.step.id, permissions.state.user]
const { busy, error, run } = useOrchestrationMutation(scope, allowed, value => emit('working', value))
const open = ref(false)
const draft = ref({ stepOrder: 1, title: '', requiredCapability: '', executionBackend: 'RUNTIME' })
const contract = ref('{}')
const outputConfig = computed({ get: () => JSON.stringify({ ...taskConfig(contract.value), executionBackend: draft.value.executionBackend }), set: (value: string) => { contract.value = value } })
let expectedRevision = 0
watch([scope, allowed], () => { open.value = false; contract.value = '{}' }, { flush: 'sync' })
function edit() {
  if (!allowed() || props.disabled || busy.value) return
  expectedRevision = props.plan.revision
  draft.value = { stepOrder: props.step.stepOrder, title: props.step.title, requiredCapability: props.step.requiredCapability, executionBackend: props.step.executionBackend ?? 'RUNTIME' }
  error.value = ''
  try { contract.value = planOutputConfig(props.step.outputContractJson, props.step.executionBackend) }
  catch { error.value = '原结果契约无法读取，请先核对配置。'; return }
  open.value = true
}
async function save() {
  if (!open.value) return
  const title = draft.value.title.trim(), requiredCapability = draft.value.requiredCapability.trim()
  if (!title || title.length > 128 || !requiredCapability || requiredCapability.length > 128) { error.value = '任务与所需能力必填且各不超过128字。'; return }
  let outputContractJson: string | null
  try { outputContractJson = planOutputValue(contract.value, draft.value.executionBackend) }
  catch (cause) { error.value = (cause as Error).message; return }
  const body = { ...draft.value, title, requiredCapability, outputContractJson, expectedRevision }
  const id = props.step.id
  await run(() => EapApi.updatePlanStep(id, body), () => { open.value = false; emit('changed') })
}
async function move(offset: number) {
  if (props.disabled) return
  const ids = reorderedStepIds(props.steps, props.step.id, offset)
  if (!ids) return
  const id = props.plan.id, revision = props.plan.revision
  await run(() => EapApi.reorderPlanSteps(id, revision, ids), () => emit('changed'))
}
async function remove() {
  if (props.disabled) return
  const id = props.step.id, revision = props.plan.revision, title = props.step.title
  await run(async current => {
    await ElMessageBox.confirm(`删除步骤“${title}”？`, '删除步骤', { type: 'warning', confirmButtonText: '删除步骤', cancelButtonText: '取消' })
    if (!current()) throw 'cancel'
    await EapApi.deletePlanStep(id, revision)
  }, () => emit('changed'))
}
</script>

<template>
  <div v-if="allowed()" class="step-management-actions">
    <div class="button-row">
      <el-button text :disabled="busy || disabled" @click="edit">编辑步骤</el-button>
      <el-button text :disabled="busy || disabled || steps[0]?.id === step.id" aria-label="上移步骤" @click="move(-1)">上移</el-button>
      <el-button text :disabled="busy || disabled || steps[steps.length - 1]?.id === step.id" aria-label="下移步骤" @click="move(1)">下移</el-button>
      <el-button text type="danger" :disabled="busy || disabled" @click="remove">删除步骤</el-button>
    </div>
    <el-alert v-if="error && !open" :title="error" type="error" :closable="false" />
    <el-dialog v-model="open" title="编辑步骤" width="min(640px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="步骤任务" required><el-input v-model="draft.title" aria-label="修改步骤任务" maxlength="128" :disabled="busy" /></el-form-item>
        <el-form-item label="所需能力" required><el-input v-model="draft.requiredCapability" aria-label="修改步骤能力" maxlength="128" :disabled="busy" /></el-form-item>
        <el-form-item label="执行方式"><el-select v-model="draft.executionBackend" aria-label="修改步骤执行方式" :disabled="busy"><el-option label="常规执行" value="RUNTIME" /><el-option label="隔离沙箱 · 完成后自动休眠" value="AX" /></el-select></el-form-item>
        <WorkflowOutputConfig v-model="outputConfig" node-type="AGENT" plan-step :disabled="busy || !allowed()" />
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <div class="button-row"><el-button :disabled="busy" @click="open = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!allowed()">保存步骤修改</el-button></div>
      </el-form>
    </el-dialog>
  </div>
</template>

<style scoped>
.button-row { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
.button-row .el-button { margin-left: 0; }
</style>
