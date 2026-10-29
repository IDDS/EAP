<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { EapApi } from '@/api/eap'
import { editablePlan, useOrchestrationMutation } from '@/api/orchestrationEditing'
import { usePermissions } from '@/stores/permissions'
import type { AgentPlan } from '@/types/domain'

const props = defineProps<{ plan: AgentPlan; disabled?: boolean }>()
const emit = defineEmits<{ changed: [planId?: number]; working: [value: boolean] }>()
const permissions = usePermissions()
const allowed = () => permissions.can('runWorkflows')
const scope = () => [props.plan.id, permissions.state.user]
const { busy, error, run } = useOrchestrationMutation(scope, allowed, value => emit('working', value))
const open = ref(false), copying = ref(false)
const draft = ref({ goal: '', contextSummary: '' })
let expectedRevision = 0
watch([scope, allowed], () => { open.value = false }, { flush: 'sync' })
function edit(copy: boolean) {
  if (props.disabled || busy.value || !allowed() || !copy && !editablePlan(props.plan)) return
  copying.value = copy; expectedRevision = props.plan.revision
  draft.value = { goal: copy ? `${props.plan.goal.slice(0, 123)}（副本）` : props.plan.goal, contextSummary: props.plan.contextSummary ?? '' }
  error.value = ''; open.value = true
}
async function save() {
  if (!open.value || !copying.value && !editablePlan(props.plan)) return
  const goal = draft.value.goal.trim(), contextSummary = draft.value.contextSummary.trim()
  if (!goal || goal.length > 128 || contextSummary.length > 1024) { error.value = '目标必填且不超过128字，上下文最多1024字。'; return }
  const planId = props.plan.id, copy = copying.value
  const body = { goal, contextSummary, expectedRevision }
  await run(() => copy ? EapApi.copyPlan(planId, body) : EapApi.updatePlan(planId, body), result => { open.value = false; emit('changed', result.id) })
}
async function remove() {
  if (props.disabled || !editablePlan(props.plan)) return
  const planId = props.plan.id, revision = props.plan.revision, goal = props.plan.goal
  await run(async current => {
    await ElMessageBox.confirm(`删除未执行计划“${goal}”及其全部步骤？`, '删除计划', { type: 'warning', confirmButtonText: '删除计划', cancelButtonText: '取消' })
    if (!current()) throw 'cancel'
    await EapApi.deletePlan(planId, revision)
  }, () => emit('changed'))
}
</script>

<template>
  <div class="plan-management-actions">
    <div v-if="allowed()" class="button-row">
      <el-button v-if="editablePlan(plan)" text :disabled="busy || disabled" @click="edit(false)">编辑计划</el-button>
      <el-button text :disabled="busy || disabled" @click="edit(true)">复制为新计划</el-button>
      <el-button v-if="editablePlan(plan)" text type="danger" :disabled="busy || disabled" @click="remove">删除计划</el-button>
    </div>
    <el-alert v-if="error && !open" :title="error" type="error" :closable="false" />
    <el-dialog v-model="open" :title="copying ? '复制为新计划' : '编辑计划'" width="min(560px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="计划目标" required><el-input v-model="draft.goal" aria-label="修改计划目标" maxlength="128" :disabled="busy" /></el-form-item>
        <el-form-item label="上下文说明"><el-input v-model="draft.contextSummary" aria-label="修改计划上下文" type="textarea" :rows="4" maxlength="1024" :disabled="busy" /></el-form-item>
        <p v-if="copying" class="muted">复制步骤、执行方式和结果契约。新计划需要重新分配成员，不会自动执行，也不复制原执行结果。</p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" />
        <div class="button-row"><el-button :disabled="busy" @click="open = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!allowed()">{{ copying ? '创建副本' : '保存计划' }}</el-button></div>
      </el-form>
    </el-dialog>
  </div>
</template>

<style scoped>
.button-row { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.button-row .el-button { margin-left: 0; }
</style>
