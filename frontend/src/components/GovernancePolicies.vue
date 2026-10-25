<template>
  <el-card class="governance-policies" shadow="never">
    <template #header><div class="policy-actions policy-heading"><strong>治理策略</strong><el-button :disabled="busy" @click="refresh">刷新策略</el-button><el-button v-if="permissions.can('governance')" type="primary" :disabled="busy" @click="openEditor()">新建策略</el-button></div></template>
    <p class="policy-note">编辑、启停和回退均校验当前版本。回退会发布一个新版本，既有决策与审批记录保留原结果。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="busy" role="status">正在读取或保存策略…</p>
    <ul class="policy-list">
      <li v-for="policy in shown.slice((page - 1) * 10, page * 10)" :key="policy.id" :data-policy-id="policy.id">
        <div class="policy-actions"><strong>{{ policy.name }}</strong><el-tag>版本 {{ policy.revision }}</el-tag><span>{{ policy.enabled ? '启用' : '停用' }}</span></div>
        <p>{{ types[policy.policyType] }} · {{ scopes[policy.scopeType] || policy.scopeType }} {{ policy.scopeValue }} · {{ modes[policy.enforcementMode] }}</p>
        <details class="policy-rule-details"><summary>查看规则 JSON</summary><pre>{{ pretty(policy.ruleJson) }}</pre></details>
        <div class="policy-actions">
          <el-button :disabled="busy" @click="showHistory(policy)">版本历史 #{{ policy.id }}</el-button>
          <template v-if="permissions.can('governance')"><el-button :disabled="busy" @click="openEditor(policy)">编辑 #{{ policy.id }}</el-button><el-button :disabled="busy" @click="toggle(policy)">{{ policy.enabled ? '停用' : '启用' }} #{{ policy.id }}</el-button></template>
        </div>
      </li>
    </ul>
    <el-empty v-if="!busy && !error && !shown.length" description="没有可显示的策略" />
    <el-pagination v-if="shown.length > 10" v-model:current-page="page" :page-size="10" :total="shown.length" layout="prev, pager, next" small />
    <section v-if="history" class="policy-history" aria-label="策略版本历史">
      <div class="policy-actions"><h3>策略 #{{ history.policyId }} · 当前版本 {{ history.currentRevision }}</h3><el-button :disabled="busy" @click="reset(false)">关闭历史</el-button></div>
      <p v-if="history.legacyHistoryUnavailable" class="policy-note">首次记录前的历史版本未保存；已有策略在首次修改时才记录当时的版本 0，不能推测更早历史。</p>
      <p v-if="history.truncated" class="policy-note">仅展示最近 50 个版本，更早版本仍保留。</p>
      <p v-if="!history.versions.length">尚无版本记录，读取不会补造历史。</p>
      <ol class="policy-list">
        <li v-for="version in history.versions" :key="version.revision">
          <strong>版本 {{ version.revision }} · {{ changes[version.changeKind] || version.changeKind }}</strong>
          <p>{{ version.values.name }} · {{ version.values.enabled ? '启用' : '停用' }} · {{ new Date(version.recordedAt).toLocaleString('zh-CN') }}</p>
          <details><summary>查看此版本规则与摘要</summary><pre>{{ pretty(JSON.stringify(version.values)) }}</pre><code>{{ version.snapshotDigest }}</code></details>
          <el-button v-if="permissions.can('governance') && version.revision < history.currentRevision" :disabled="busy" @click="reviewRollback(version)">核对回退到版本 {{ version.revision }}</el-button>
        </li>
      </ol>
      <section v-if="review" class="policy-review" aria-label="回退核对">
        <h4>核对回退</h4><p>策略 #{{ review.policyId }}，当前版本 {{ review.expectedRevision }} → 采用历史版本 {{ review.sourceRevision }} 的规则。确认后发布新版本，旧历史保留。</p>
        <pre>{{ pretty(JSON.stringify(review.values)) }}</pre><code>{{ review.sourceDigest }}</code>
        <div class="policy-actions"><el-button :disabled="busy" @click="review = null">取消回退</el-button><el-button type="warning" :disabled="busy || !permissions.can('governance')" @click="confirmRollback">确认发布回退版本</el-button></div>
      </section>
    </section>
    <el-dialog :model-value="editorVisible" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" :title="editing ? `编辑策略 #${editing.id} · 版本 ${editing.revision}` : '新建治理策略'" width="min(680px, calc(100vw - 24px))">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-form label-position="top" :disabled="busy || !permissions.can('governance')">
        <el-form-item label="策略名称"><el-input v-model="form.name" aria-label="策略名称" maxlength="128" /></el-form-item>
        <el-form-item label="策略类型"><el-select v-model="form.policyType"><el-option v-for="(label, value) in types" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="作用范围"><el-select v-model="form.scopeType"><el-option v-for="(label, value) in scopes" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="范围值"><el-input v-model="form.scopeValue" :disabled="form.scopeType === 'GLOBAL'" /></el-form-item>
        <el-form-item label="执行模式"><el-select v-model="form.enforcementMode"><el-option v-for="(label, value) in modes" :key="value" :label="label" :value="value" /></el-select></el-form-item>
        <el-form-item label="启用状态"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="规则 JSON"><el-input v-model="form.ruleJson" type="textarea" :rows="7" :maxlength="16384" /><p class="policy-note">支持 sensitivities、dataCategories、directions、providers、models、internalData、companySecret、detectedTypes / piiTypes 和 action。请勿填写密码或令牌。</p></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="busy" @click="reset(false)">取消</el-button><el-button type="primary" :disabled="busy || !permissions.can('governance')" @click="save">保存策略</el-button></template>
    </el-dialog>
  </el-card>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { policyApi, type PolicyHistory, type PolicyValues, type PolicyVersion } from '@/api/governancePolicies'
import type { GovernancePolicy, PolicyType, EnforcementMode } from '@/types/domain'
import { usePermissions } from '@/stores/permissions'
const props = defineProps<{ policyId?: number }>()
const emit = defineEmits<{ changed: [] }>()
const permissions = usePermissions()
const policies = ref<GovernancePolicy[]>([]), history = ref<PolicyHistory | null>(null)
const editing = ref<GovernancePolicy | null>(null), editorVisible = ref(false), busy = ref(false), error = ref(''), page = ref(1)
const review = ref<{ policyId: number; expectedRevision: number; sourceRevision: number; sourceDigest: string; values: PolicyValues } | null>(null)
const form = reactive<PolicyValues>(defaults())
const shown = computed(() => props.policyId ? policies.value.filter(p => p.id === props.policyId) : policies.value)
const types: Record<PolicyType, string> = { PERMISSION: '权限', PRIVACY: '隐私', SECURITY: '安全', ALGORITHM: '算法', BENEFIT: '效益' }
const scopes: Record<string, string> = { GLOBAL: '全局', AGENT: '智能体', ENDPOINT: '端点', DATA_CATEGORY: '数据类别', PROVIDER: '模型供应商', MODEL: '模型' }
const modes: Record<EnforcementMode, string> = { WARN: '告警并放行', BLOCK: '阻断', APPROVAL_REQUIRED: '人工审批' }
const changes: Record<string, string> = { CREATED: '创建', BASELINE: '首次修改前基线', UPDATED: '更新', ROLLED_BACK: '回退发布' }
let generation = 0, alive = true
function defaults(): PolicyValues { return { name: '', policyType: 'PRIVACY', scopeType: 'GLOBAL', scopeValue: '', ruleJson: '{"sensitivities":["SECRET"],"action":"BLOCK"}', enforcementMode: 'BLOCK', enabled: false } }
function reset(clearList = true) {
  generation++; history.value = null; review.value = null; editing.value = null; editorVisible.value = false
  busy.value = false; error.value = ''; Object.assign(form, defaults())
  if (clearList) { policies.value = []; page.value = 1 }
}
function current(run: number, user: unknown) { return alive && run === generation && user === permissions.state.user && permissions.can('viewConsole') }
function writable() { return alive && !busy.value && permissions.can('governance') && permissions.can('viewConsole') }
function values(policy: PolicyValues): PolicyValues { return { name: policy.name, policyType: policy.policyType, scopeType: policy.scopeType, scopeValue: policy.scopeValue || '', ruleJson: policy.ruleJson, enforcementMode: policy.enforcementMode, enabled: policy.enabled } }
async function refresh() {
  reset(); if (!alive || !permissions.can('viewConsole')) return
  const run = generation, user = permissions.state.user; busy.value = true
  try { const result = await policyApi.list(); if (current(run, user)) policies.value = result }
  catch { if (current(run, user)) { policies.value = []; error.value = '策略读取失败，请手动刷新' } }
  finally { if (current(run, user)) busy.value = false }
}
function openEditor(policy?: GovernancePolicy) {
  if (!writable() || (policy && !policies.value.some(p => p.id === policy.id && p.revision === policy.revision))) return
  reset(false); editing.value = policy ? { ...policy } : null
  Object.assign(form, policy ? values(policy) : defaults()); editorVisible.value = true
}
async function perform(action: () => Promise<GovernancePolicy>) {
  if (!writable()) return
  const run = ++generation, user = permissions.state.user; busy.value = true; error.value = ''
  try {
    await action()
    if (!current(run, user) || !permissions.can('governance')) return
    emit('changed'); await refresh()
  } catch {
    if (current(run, user)) { reset(); error.value = '保存未确认或版本冲突；请手动刷新策略与历史后重新核对，不要直接重发' }
  } finally { if (current(run, user)) busy.value = false }
}
async function save() {
  if (!writable() || !editorVisible.value) return
  const payload = values(form); payload.name = payload.name.trim(); payload.scopeValue = payload.scopeType === 'GLOBAL' ? '' : (payload.scopeValue || '').trim()
  try {
    const rule = JSON.parse(payload.ruleJson)
    if (!payload.name || (payload.scopeType !== 'GLOBAL' && !payload.scopeValue) || !rule || Array.isArray(rule) || typeof rule !== 'object' || payload.ruleJson.length > 16384) throw Error('invalid')
  } catch { error.value = '请填写名称、范围及有效的规则 JSON 对象'; return }
  const original = editing.value
  await perform(() => original ? policyApi.update(original.id, { ...payload, expectedRevision: original.revision }) : policyApi.create(payload))
}
async function toggle(policy: GovernancePolicy) {
  if (!writable() || !policies.value.some(p => p.id === policy.id && p.revision === policy.revision)) return
  const payload = { ...values(policy), enabled: !policy.enabled, expectedRevision: policy.revision }
  await perform(() => policyApi.update(policy.id, payload))
}
async function showHistory(policy: GovernancePolicy) {
  if (!alive || busy.value || !permissions.can('viewConsole') || !policies.value.some(p => p.id === policy.id)) return
  reset(false); const run = generation, user = permissions.state.user; busy.value = true
  try {
    const result = await policyApi.versions(policy.id)
    if (!current(run, user)) return
    if (result.policyId !== policy.id) throw Error('history mismatch')
    history.value = result
  } catch { if (current(run, user)) { history.value = null; review.value = null; error.value = '版本历史读取失败，请重新读取' } }
  finally { if (current(run, user)) busy.value = false }
}
function reviewRollback(version: PolicyVersion) {
  const original = history.value
  if (!writable() || !original || version.revision >= original.currentRevision || !original.versions.some(v => v.revision === version.revision && v.snapshotDigest === version.snapshotDigest)) return
  review.value = { policyId: original.policyId, expectedRevision: original.currentRevision, sourceRevision: version.revision, sourceDigest: version.snapshotDigest, values: { ...version.values } }
}
async function confirmRollback() {
  const original = review.value
  if (!writable() || !original || history.value?.policyId !== original.policyId || history.value.currentRevision !== original.expectedRevision) return
  await perform(() => policyApi.rollback(original.policyId, original.expectedRevision, original.sourceRevision, original.sourceDigest))
}
function pretty(text: string) { try { return JSON.stringify(JSON.parse(text), null, 2) } catch { return text } }
watch([() => props.policyId, () => permissions.state.user, () => permissions.can('viewConsole'), () => permissions.can('governance')], () => { reset(); void refresh() }, { flush: 'sync' })
onMounted(refresh)
onBeforeUnmount(() => { alive = false; reset() })
</script>
<style scoped>
.governance-policies{min-width:0}.policy-actions{display:flex;flex-wrap:wrap;gap:10px;align-items:center}.policy-note{font-size:13px;line-height:1.7;color:#64748b}.policy-list{list-style:none;padding:0;display:grid;gap:14px}.policy-list>li,.policy-review{padding:16px;border:1px solid #dce5ee;border-radius:8px;min-width:0}.policy-history{margin-top:24px;border-top:1px solid #dce5ee;padding-top:12px}.policy-review{margin-top:16px;background:#fffbeb}pre,code{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.7}pre{max-height:240px;overflow:auto}details{margin-bottom:12px}.el-select{width:100%}@media(max-width:600px){.policy-list>li,.policy-review{padding:12px}.policy-actions .el-button{margin-left:0}}
</style>
