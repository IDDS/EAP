<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { createPersonalMemory, listPersonalMemories, revokePersonalMemory } from '@/api/personalMemory'
import type { PersonalMemory, PersonalMemoryRequest } from '@/api/personalMemory'
import { usePermissions } from '@/stores/permissions'

const permissions = usePermissions()
const available = computed(() => permissions.state.user?.mode === 'JWT' && permissions.can('manageOwnMemory'))
const content = ref(''), expiry = ref(''), records = ref<PersonalMemory[]>([]), cursor = ref<string | null>(null)
const loading = ref(false), saving = ref(false), error = ref(''), notice = ref('')
const pending = ref<PersonalMemoryRequest | null>(null)
let generation = 0, loadRevision = 0, alive = true
function current(revision: number, user: unknown) { return alive && available.value && revision === generation && user === permissions.state.user }
function reset() { generation++; loadRevision++; content.value = ''; expiry.value = ''; records.value = []; cursor.value = null; pending.value = null; loading.value = false; saving.value = false; error.value = ''; notice.value = '' }
function time(value: number) { return new Date(value).toLocaleString('zh-CN') }

async function load(more = false) {
  if (!available.value || (more && (loading.value || !cursor.value))) return
  const revision = generation, read = ++loadRevision, user = permissions.state.user, next = more ? cursor.value || undefined : undefined
  loading.value = true; error.value = ''
  if (!more) { records.value = []; cursor.value = null }
  try {
    const page = await listPersonalMemories(next)
    if (!current(revision, user) || read !== loadRevision) return
    const seen = new Set(more ? records.value.map(record => record.id) : [])
    if (!Array.isArray(page.items) || page.items.length > 50 || page.items.some(record => seen.has(record.id) || (seen.add(record.id), false))
      || (page.nextCursor !== null && (!page.nextCursor || page.nextCursor === next || !page.items.length))) throw new Error('Invalid memory page')
    records.value = more ? [...records.value, ...page.items] : page.items
    cursor.value = page.nextCursor
  } catch { if (current(revision, user) && read === loadRevision) error.value = '记忆读取失败，请刷新重试。' }
  finally { if (current(revision, user) && read === loadRevision) loading.value = false }
}

async function save() {
  if (!available.value || saving.value) return
  if (!pending.value) {
    const trimmed = content.value.trim(), expiresAtMs = expiry.value ? new Date(expiry.value).getTime() : null
    if (!trimmed || trimmed.length > 2048) { error.value = '请填写不超过 2048 个字符的内容。'; return }
    if (expiresAtMs !== null && (!Number.isFinite(expiresAtMs) || expiresAtMs <= Date.now())) { error.value = '过期时间应晚于当前时间。'; return }
    if (typeof crypto === 'undefined' || typeof crypto.randomUUID !== 'function') { error.value = '当前访问环境无法安全创建提交编号，请使用 HTTPS 或本机地址。'; return }
    pending.value = { requestKey: crypto.randomUUID(), content: trimmed, expiresAtMs, explicitlyRemember: true }
  }
  const revision = generation, user = permissions.state.user, request = pending.value
  saving.value = true; error.value = ''; notice.value = ''
  try {
    await createPersonalMemory(request)
    if (!current(revision, user)) return
    pending.value = null; content.value = ''; expiry.value = ''; notice.value = '已保存。'
    await load()
  } catch { if (current(revision, user)) error.value = '保存结果尚未确认。可重试同一次提交，或刷新列表核对。' }
  finally { if (current(revision, user)) saving.value = false }
}

async function revoke(record: PersonalMemory) {
  if (!available.value || saving.value) return
  const revision = generation, user = permissions.state.user
  try { await ElMessageBox.confirm('撤回后当前记录的正文将被清除，已产生的备份仍按其保留规则处理。', '撤回这条记忆', { confirmButtonText: '撤回', cancelButtonText: '取消', type: 'warning' }) } catch { return }
  if (!current(revision, user)) return
  saving.value = true; error.value = ''; notice.value = ''
  try {
    await revokePersonalMemory(record.id)
    if (!current(revision, user)) return
    notice.value = '已撤回。'; await load()
  } catch { if (current(revision, user)) error.value = '撤回结果尚未确认，请刷新列表核对。' }
  finally { if (current(revision, user)) saving.value = false }
}
watch([() => permissions.state.user, () => available.value], () => { reset(); if (available.value) void load() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { alive = false; reset() })
</script>

<template>
  <section class="memory-page">
    <ConsolePageHeader />
    <p v-if="!available" role="status" class="message">请使用具有记忆管理权限的企业账号登录。</p>
    <template v-else>
      <div class="intro"><div><span class="eyebrow">个人空间</span><h2>我的记忆</h2><p>保存希望长期保留的信息，随时查看或撤回。</p></div><p class="boundary">页面仅展示当前账号的记忆。此阶段不会自动从对话提取，也不会自动提供给智能体。</p></div>
      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
      <el-alert v-if="notice" :title="notice" type="success" :closable="false" show-icon />
      <div class="memory-layout">
        <section class="panel composer"><h3>保存一条记忆</h3><p>只填写自己确认可以保存的内容，不要填写密码或令牌。</p><label for="memory-content">记忆内容</label><textarea id="memory-content" v-model="content" :disabled="!!pending || saving" maxlength="2048" rows="5" placeholder="例如：我希望工单摘要先列出当前状态。" /><small>{{ content.length }} / 2048</small><label for="memory-expiry">过期时间（可选）</label><input id="memory-expiry" v-model="expiry" :disabled="!!pending || saving" type="datetime-local" /><p>到期后标记为已过期，正文仍保留，直到你主动撤回；备份按其保留规则处理。</p><el-button type="primary" :loading="saving" @click="save">{{ pending ? '重试保存' : '保存记忆' }}</el-button></section>
        <section class="panel list"><div class="list-heading"><div><h3>已保存</h3><p>按保存时间倒序排列；状态以读取时为准，刷新可更新。</p></div><el-button :loading="loading" @click="load()">刷新</el-button></div><p v-if="loading && !records.length" role="status">正在读取记忆…</p><p v-else-if="!records.length && !error" class="empty">还没有保存记忆。</p><ul><li v-for="record in records" :key="record.id"><div class="record-head"><span :class="record.status === 'EXPIRED' ? 'expired' : 'active'">{{ record.status === 'EXPIRED' ? '已过期' : '有效' }}</span><time>{{ time(record.createdAtMs) }}</time></div><p class="record-content">{{ record.content }}</p><div class="record-foot"><small>{{ record.expiresAtMs ? `到期 ${time(record.expiresAtMs)}` : '未设置过期时间' }}</small><el-button text type="danger" :disabled="saving" @click="revoke(record)">撤回</el-button></div></li></ul><el-button v-if="cursor" :loading="loading" @click="load(true)">加载更多</el-button></section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.memory-page{display:grid;gap:18px;color:#26384a}.intro{display:flex;justify-content:space-between;gap:24px;align-items:end}.intro h2{font-size:27px;margin:6px 0}.intro p,.composer p,.list-heading p{color:#64748b;line-height:1.7;font-size:13px;margin:6px 0}.eyebrow{color:#287eac;font-size:12px}.intro .boundary{max-width:420px;padding-left:15px;border-left:3px solid #a8cad8}.memory-layout{display:grid;grid-template-columns:minmax(260px,350px) minmax(0,1fr);gap:18px;align-items:start}.panel{background:white;border:1px solid #dfe7ee;border-radius:8px;padding:22px;min-width:0}.panel h3{font-size:16px;margin:0 0 8px}.composer{display:grid;gap:10px}.composer label{font-size:13px;font-weight:600;margin-top:8px}.composer textarea,.composer input{box-sizing:border-box;width:100%;border:1px solid #cbd5e1;border-radius:5px;padding:10px;font:inherit;color:inherit}.composer textarea{resize:vertical}.composer small{color:#64748b;text-align:right}.composer .el-button{justify-self:start}.list-heading,.record-head,.record-foot{display:flex;align-items:center;justify-content:space-between;gap:12px}.list ul{padding:0;margin:16px 0;list-style:none}.list li{border-top:1px solid #e7eef3;padding:16px 0}.record-head,.record-foot{font-size:12px;color:#64748b}.record-content{white-space:pre-wrap;overflow-wrap:anywhere;font-size:14px;line-height:1.75}.active,.expired{font-weight:600;color:#287653}.expired{color:#93600f}.empty,.message{color:#64748b;font-size:14px}.memory-page :is(textarea,input,button):focus-visible{outline:2px solid #287eac;outline-offset:2px}@media(max-width:760px){.intro{align-items:start;flex-direction:column}.memory-layout{grid-template-columns:1fr}.intro .boundary{max-width:none}}
</style>
