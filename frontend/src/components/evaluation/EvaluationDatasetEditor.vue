<template>
  <el-card v-if="allowed" shadow="never" class="panel dataset-editor" :class="{ 'is-editing': mode }">
    <template #header><div class="editor-heading"><div class="editor-heading-copy"><strong>录入回归数据</strong><p class="editor-note">保存后可人工标注、运行规则校准。录入本身不执行评估或调用模型。</p></div><div class="editor-actions"><el-button :disabled="locked || busy" @click="open('dataset')">新建数据集</el-button><el-button type="primary" :disabled="locked || busy || !datasetKey" @click="open('sample')">添加回归样本</el-button></div></div></template>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <el-form v-if="mode" label-position="top" :disabled="locked || busy" @submit.prevent="submit">
      <template v-if="mode === 'dataset'">
        <div class="editor-grid"><el-form-item label="数据集标识"><el-input v-model="dataset.datasetKey" aria-label="数据集标识" maxlength="128" /></el-form-item><el-form-item label="数据集名称"><el-input v-model="dataset.name" aria-label="数据集名称" maxlength="256" /></el-form-item></div>
        <div class="editor-grid"><el-form-item label="数据集版本"><el-input v-model="dataset.version" aria-label="数据集版本" maxlength="64" /></el-form-item><el-form-item label="说明（可选）"><el-input v-model="dataset.description" aria-label="数据集说明" maxlength="2048" /></el-form-item></div>
      </template>
      <template v-else>
        <p class="editor-target">目标数据集：<code>{{ frozen?.datasetKey || datasetKey }}</code></p>
        <div class="editor-grid"><el-form-item label="样本标识"><el-input v-model="sample.itemKey" aria-label="样本标识" maxlength="128" /></el-form-item><el-form-item label="评估器"><el-select v-model="sample.evaluatorKey" aria-label="样本评估器"><el-option v-for="item in evaluators.filter(e => e.enabled !== false)" :key="item.evaluatorKey" :label="`${item.name} · ${item.version}`" :value="item.evaluatorKey" /></el-select></el-form-item></div>
        <h3 class="editor-section-heading">样本内容</h3>
        <div class="editor-grid"><el-form-item label="输入 JSON 对象"><el-input v-model="sample.inputJson" type="textarea" :rows="4" aria-label="样本输入 JSON" /></el-form-item><el-form-item label="实际结果 JSON 对象"><el-input v-model="sample.actualJson" type="textarea" :rows="4" aria-label="样本实际结果 JSON" /></el-form-item></div>
        <el-form-item label="期望结果 JSON（EXACT_MATCH 必填，其余可选）"><el-input v-model="sample.expectedJson" type="textarea" :rows="2" aria-label="样本期望结果 JSON" /></el-form-item>
        <h3 class="editor-section-heading">来源引用 <span>正文仍由人工录入</span></h3>
        <div class="editor-grid"><el-form-item label="Agent ID（可选）"><el-input v-model="sample.agentId" inputmode="numeric" aria-label="样本 Agent ID" /></el-form-item><el-form-item label="Trace ID（可选）"><el-input v-model="sample.traceId" maxlength="128" aria-label="样本 Trace ID" /></el-form-item><el-form-item label="Span ID（可选，须同时填写 Trace ID）"><el-input v-model="sample.spanId" maxlength="128" aria-label="样本 Span ID" /></el-form-item></div>
        <el-checkbox v-model="sample.verifySourceReference">保存时核对观测引用</el-checkbox>
        <div class="editor-source-note"><p>勾选后须填写全部三个标识；服务器核对已有观测记录并保存当时的引用快照。未勾选时保留为未核对引用。</p><p>仅核对观测记录中的 Agent/Trace/Span，不验证上报者身份或手填正文来源。</p><p>请仅录入已脱敏的测试数据。输入与结果按当前观测设置保存为脱敏内容、哈希或不保存正文；哈希或缺失正文的样本不能用于规则运行。</p></div>
      </template>
      <div class="editor-actions"><el-button native-type="submit" type="primary" :loading="sending">{{ mode === 'dataset' ? '保存数据集' : '保存样本' }}</el-button><el-button @click="close">取消录入</el-button></div>
    </el-form>
    <div v-if="uncertain && frozen" class="editor-reconcile">
      <p>原提交标识：<code>{{ frozen.kind === 'dataset' ? frozen.body.datasetKey : frozen.body.itemKey }}</code>。结果未确认，原内容已保留，不会自动重新提交。</p>
      <el-button :loading="checking" :disabled="checking" @click="reconcile">按原标识精确查询</el-button>
      <p v-if="lookup" role="status">{{ lookup }}</p>
      <el-checkbox v-model="reviewed" :disabled="checking">我已核对保存记录，理解重新录入可能产生新记录</el-checkbox>
      <el-button :disabled="!reviewed || checking" @click="finishReview">结束本次核对</el-button>
      <p class="editor-note">核对完成前请保留此页；刷新或关闭浏览器后无法恢复当前表单。</p>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage } from 'element-plus'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import type { EvaluatorDefinition } from '@/types/domain'
import { datasetPayload, samplePayload, newDatasetDraft, newSampleDraft, matchesSourceReceipt } from '@/evaluation/datasetEntry'
import { datasetLookupMessage } from '@/evaluation/datasetLookup'

const props = defineProps<{ datasetKey: string; evaluators: EvaluatorDefinition[]; busy: boolean }>()
const emit = defineEmits<{ locked: [value: boolean]; saved: [datasetKey: string] }>()
const permissions = usePermissions()
const allowed = computed(() => permissions.can('viewConsole') && permissions.can('manageEvaluations'))
const mode = ref<'' | 'dataset' | 'sample'>('')
const dataset = reactive(newDatasetDraft()), sample = reactive(newSampleDraft())
const sending = ref(false), uncertain = ref(false), checking = ref(false), reviewed = ref(false)
const error = ref(''), lookup = ref('')
const locked = computed(() => sending.value || uncertain.value)
type Intent = { kind: 'dataset'; body: ReturnType<typeof datasetPayload>; datasetKey: string } | { kind: 'sample'; body: ReturnType<typeof samplePayload>; datasetKey: string }
const frozen = ref<Intent>()
let generation = 0, alive = true
function current(epoch: number) { return alive && epoch === generation && allowed.value }
function reset() {
  generation++; sending.value = false; uncertain.value = false; checking.value = false; reviewed.value = false
  mode.value = ''; frozen.value = undefined; error.value = ''; lookup.value = ''
  Object.assign(dataset, newDatasetDraft()); Object.assign(sample, newSampleDraft())
}
function open(kind: 'dataset' | 'sample') {
  if (!allowed.value || locked.value || props.busy || (kind === 'sample' && !props.datasetKey)) return
  reset(); mode.value = kind
  sample.evaluatorKey = props.evaluators.find(e => e.enabled !== false)?.evaluatorKey || ''
}
function close() { if (!locked.value) reset() }
async function submit() {
  if (!allowed.value || locked.value || props.busy || !mode.value) return
  let intent: Intent
  try {
    if (mode.value === 'dataset') { const body = datasetPayload(dataset); intent = { kind: 'dataset', body, datasetKey: body.datasetKey } }
    else {
      if (!props.datasetKey) throw Error('请先选择目标数据集')
      intent = { kind: 'sample', body: samplePayload(sample, props.evaluators), datasetKey: props.datasetKey }
    }
  } catch (cause) { error.value = (cause as Error).message; return }
  const epoch = generation
  frozen.value = intent; sending.value = true; error.value = ''; lookup.value = ''
  try {
    if (intent.kind === 'dataset') {
      const result = await EapApi.createEvaluationDataset(intent.body)
      if (result.datasetKey !== intent.body.datasetKey) throw Error('Unexpected dataset receipt')
    } else {
      const result = await EapApi.createEvaluationDatasetItem(intent.datasetKey, intent.body)
      if (result.itemKey !== intent.body.itemKey || result.evaluatorKey !== intent.body.evaluatorKey) throw Error('Unexpected sample receipt')
      if (intent.body.verifySourceReference && !matchesSourceReceipt(result, intent.body)) throw Error('Missing source reference receipt')
    }
    if (!current(epoch)) return
    reset(); ElMessage.success(intent.kind === 'dataset' ? '数据集已保存，可以添加回归样本' : '样本已保存；可继续标注或校准')
    emit('saved', intent.datasetKey)
  } catch (cause) {
    if (!current(epoch)) return
    const status = (cause as { response?: { status?: number } })?.response?.status
    // A received validation/authorization rejection is definitive. Conflict or
    // transport/server failure requires manual reconciliation with the original key.
    if (status === 400 || status === 401 || status === 403 || status === 404 || status === 422) {
      frozen.value = undefined; error.value = '服务器拒绝本次录入，请检查标识、数据集和评估器后再提交。'
    } else { uncertain.value = true; error.value = '保存结果未确认，请核对原提交标识。' }
  } finally { if (current(epoch)) sending.value = false }
}
async function reconcile() {
  if (!allowed.value || !uncertain.value || checking.value || !frozen.value) return
  const epoch = generation, intent = frozen.value
  checking.value = true; lookup.value = ''; reviewed.value = false
  try {
    const result = intent.kind === 'dataset' ? await EapApi.lookupEvaluationDataset(intent.datasetKey) : await EapApi.lookupEvaluationDatasetItem(intent.datasetKey, intent.body.itemKey)
    if (!current(epoch)) return
    lookup.value = datasetLookupMessage(result, { kind: intent.kind === 'dataset' ? 'DATASET' : 'SAMPLE', datasetKey: intent.datasetKey, itemKey: intent.kind === 'sample' ? intent.body.itemKey : undefined })
  } catch { if (current(epoch)) lookup.value = '查询失败，原提交仍未确认；请稍后再查询。' }
  finally { if (current(epoch)) checking.value = false }
}
function finishReview() {
  if (!allowed.value || !uncertain.value || checking.value || !reviewed.value || !frozen.value) return
  const key = frozen.value.datasetKey
  reset(); emit('saved', key)
}
function canLeave() { if (locked.value) { ElMessage.warning('请先核对当前数据录入，再离开评估工作区。'); return false } }
onBeforeRouteLeave(canLeave); onBeforeRouteUpdate(canLeave)
watch(locked, value => emit('locked', value), { immediate: true, flush: 'sync' })
watch([() => permissions.state.user, () => allowed.value], reset, { flush: 'sync' })
watch(() => props.datasetKey, () => { if (!locked.value) reset() }, { flush: 'sync' })
onBeforeUnmount(() => { alive = false; generation++; emit('locked', false) })
</script>

<style scoped>
.editor-heading,.editor-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.editor-heading{justify-content:space-between}.editor-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.editor-grid :deep(.el-select){width:100%}.editor-note,.editor-target,.editor-reconcile{font-size:13px;line-height:1.7;color:#526175}.editor-target code,.editor-reconcile code{overflow-wrap:anywhere}.editor-reconcile{margin-top:16px;padding:16px;background:#fff8e8;border-radius:6px}.editor-reconcile :deep(.el-checkbox){white-space:normal;height:auto;margin:12px 0}.editor-reconcile :deep(.el-checkbox__label){white-space:normal}.dataset-editor :deep(.el-alert){margin-bottom:16px}@media(max-width:650px){.editor-grid{grid-template-columns:minmax(0,1fr)}}
</style>
