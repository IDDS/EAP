import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'

const defer = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const settle = () => new Promise(resolve => setImmediate(resolve))
function mount(t, api = {}) {
  const source = readFileSync(new URL('../src/views/PersonalMemoryView.vue', import.meta.url), 'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const user = Vue.reactive({ state: { user: { mode: 'JWT', subject: 'alice' } }, enabled: true, can() { return this.enabled } })
  let unmount
  const scope = Vue.effectScope(), calls = []
  const args = { ref: Vue.ref, computed: Vue.computed, watch: Vue.watch, onBeforeUnmount: fn => { unmount = fn }, usePermissions: () => user,
    ElMessageBox: { confirm: async () => {} }, crypto: api.crypto ?? { randomUUID: () => '00000000-0000-4000-8000-000000000001' },
    listPersonalMemories: async (...p) => { calls.push(['list', ...p]); return api.list ? api.list(...p) : { items: [], nextCursor: null } },
    createPersonalMemory: async payload => { calls.push(['create', payload]); return api.create ? api.create(payload) : {} },
    revokePersonalMemory: async id => { calls.push(['revoke', id]); return api.revoke ? api.revoke(id) : { revoked: true } },
  }
  let state
  scope.run(() => { state = new Function(...Object.keys(args), ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText + ';return {content,expiry,records,cursor,loading,saving,error,notice,pending,load,save,revoke}')(...Object.values(args)) })
  t.after(() => { unmount(); scope.stop() })
  return { ...state, user, calls, unmount }
}

test('switching accounts clears personal content and rejects late reads', async t => {
  const old = defer(), next = defer(); let n = 0
  const s = mount(t, { list: () => ++n === 1 ? old.promise : next.promise })
  s.content.value = 'private draft'; s.user.state.user = { mode: 'JWT', subject: 'bob' }
  assert.equal(s.content.value, ''); assert.equal(s.records.value.length, 0)
  old.resolve({ items: [{ id: 'old', content: 'private' }], nextCursor: null }); await settle()
  assert.equal(s.records.value.length, 0)
  next.resolve({ items: [{ id: 'new', content: 'bob' }], nextCursor: null }); await settle()
  assert.equal(s.records.value[0].content, 'bob')
})

test('uncertain save reuses the exact request rather than creating a new intent', async t => {
  let attempts = 0
  const s = mount(t, { create: async () => { if (++attempts === 1) throw Error('lost response'); return {} } })
  await settle(); s.content.value = 'synthetic memory'; await s.save()
  assert.ok(s.pending.value); assert.match(s.error.value, /尚未确认/)
  await s.save()
  const writes = s.calls.filter(c => c[0] === 'create')
  assert.equal(writes.length, 2); assert.deepEqual(writes[0][1], writes[1][1]); assert.equal(writes[0][1].explicitlyRemember, true)
  assert.equal(s.pending.value, null); assert.equal(s.content.value, '')
})

test('development mode and loss of capability disable access and clear state', async t => {
  const s = mount(t); await settle(); const count = s.calls.length
  s.user.state.user = { mode: 'DEVELOPMENT', subject: null }; s.content.value = 'not sent'; await s.save()
  assert.equal(s.calls.length, count)
  s.user.state.user = { mode: 'JWT', subject: 'alice' }; await settle()
  s.user.enabled = false; assert.equal(s.records.value.length, 0); assert.equal(s.content.value, '')
})

test('a successful save refreshes the list and suppresses an older in-flight read', async t => {
  const old = defer(); let reads = 0
  const s = mount(t, { list: () => ++reads === 1 ? old.promise : { items: [{ id: 'fresh', content: 'saved' }], nextCursor: null } })
  s.content.value = 'saved'; await s.save()
  assert.equal(s.records.value[0].id, 'fresh')
  old.resolve({ items: [{ id: 'old', content: 'old' }], nextCursor: null }); await settle()
  assert.equal(s.records.value[0].id, 'fresh')
})

test('an insecure context never falls back to a weak submission identifier', async t => {
  const s = mount(t, { crypto: {} }); await settle(); s.content.value = 'synthetic'; await s.save()
  assert.match(s.error.value, /HTTPS/); assert.equal(s.calls.filter(c => c[0] === 'create').length, 0)
})
