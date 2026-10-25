import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const vue = readFileSync(new URL('../src/components/GovernancePolicies.vue', import.meta.url), 'utf8')
const script = vue.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const policy = () => ({ id: 7, revision: 3, name: 'PUBLIC', policyType: 'PRIVACY', scopeType: 'GLOBAL', scopeValue: '', ruleJson: '{}', enforcementMode: 'WARN', enabled: false })
const version = () => ({ revision: 1, changeKind: 'UPDATED', snapshotDigest: 'a'.repeat(64), values: { ...policy(), name: 'PUBLIC-prior' } })
const history = () => ({ policyId: 7, currentRevision: 3, versions: [version()] })
function scope(api = {}) {
  const ast = ts.createSourceFile('policies.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const functions = ast.statements.filter(ts.isFunctionDeclaration).map(n => n.getText(ast)).join('\n')
  const events = [], state = vm.createContext({ generation: 0, alive: true, props: {}, policies: { value: [policy()] }, history: { value: null }, review: { value: null }, editing: { value: null }, editorVisible: { value: false }, busy: { value: false }, error: { value: '' }, page: { value: 1 }, form: {}, permissions: { state: { user: {} }, can: () => true }, policyApi: { list: async () => [policy()], ...api }, emit: (...args) => events.push(args) })
  vm.runInContext(ts.transpile(functions, { target: ts.ScriptTarget.ES2022 }), state); state.events = events; return state
}
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function plain(value) { return JSON.parse(JSON.stringify(value)) }
test('edit freezes the original revision and prevents duplicate in-flight writes', async () => {
  const pending = deferred(), calls = []; const s = scope({ update: (...args) => { calls.push(plain(args)); return pending.promise } })
  s.openEditor(policy()); s.form.name = 'edited'; const save = s.save(); s.form.name = 'late'; s.policies.value = [{ ...policy(), revision: 9 }]; await s.save();
  assert.equal(calls.length, 1); assert.equal(calls[0][0], 7); assert.equal(calls[0][1].expectedRevision, 3); assert.equal(calls[0][1].name, 'edited'); assert.equal('revision' in calls[0][1], false)
  pending.resolve(policy()); await save; assert.deepEqual(s.events, [['changed']]); assert.equal(s.busy.value, false); assert.equal(s.editorVisible.value, false)
})
test('enable and disable carry the revision actually shown to the user', async () => {
  const calls = [], s = scope({ update: async (...args) => { calls.push(plain(args)); return policy() } }); await s.toggle(policy()); assert.equal(calls[0][1].expectedRevision, 3); assert.equal(calls[0][1].enabled, true)
  await s.toggle({ ...policy(), revision: 1 }); assert.equal(calls.length, 1)
})
test('creating a policy uses no caller revision and starts disabled', async () => {
  const calls = [], s = scope({ create: async p => { calls.push(plain(p)); return policy() } }); s.openEditor(); s.form.name = 'new'; await s.save()
  assert.equal(calls.length, 1); assert.equal(calls[0].enabled, false); assert.equal('expectedRevision' in calls[0], false)
})
test('rollback requires explicit review and sends exactly the frozen head source and digest', async () => {
  const calls = [], s = scope({ rollback: async (...args) => { calls.push(args); return policy() } }); s.history.value = history(); await s.confirmRollback(); assert.equal(calls.length, 0)
  const old = version(); s.reviewRollback(old); old.values.name = 'late'; assert.equal(s.review.value.values.name, 'PUBLIC-prior'); assert.equal(calls.length, 0)
  await s.confirmRollback(); assert.deepEqual(calls, [[7, 3, 1, 'a'.repeat(64)]]); assert.equal(s.review.value, null)
})
test('cancelled rollback and a changed history head cannot publish', async () => {
  let calls = 0; const s = scope({ rollback: async () => { calls++ } }); s.history.value = history(); s.reviewRollback(version()); s.review.value = null; await s.confirmRollback()
  s.reviewRollback(version()); s.history.value.currentRevision = 4; await s.confirmRollback(); assert.equal(calls, 0)
})
test('conflict or lost write response clears all private state without re-read or retry', async () => {
  for (const reason of ['409', 'disconnected']) {
    let writes = 0, reads = 0; const s = scope({ update: async () => { writes++; throw Error(reason) }, list: async () => { reads++; return [policy()] } })
    s.openEditor(policy()); await s.save(); await s.save(); assert.equal(writes, 1); assert.equal(reads, 0); assert.equal(s.policies.value.length, 0); assert.equal(s.history.value, null); assert.equal(s.review.value, null); assert.equal(s.editing.value, null); assert.equal(s.form.name, ''); assert.match(s.error.value, /不要直接重发/)
  }
})
test('account capability scope and unmount changes discard late history results', async () => {
  for (const change of [s => s.permissions.state.user = {}, s => s.permissions.can = () => false, s => s.props.policyId = 9, s => s.alive = false]) {
    const p = deferred(), s = scope({ versions: () => p.promise }); const read = s.showHistory(policy()); change(s); s.reset(); p.resolve(history()); await read; assert.equal(s.history.value, null); assert.equal(s.policies.value.length, 0); assert.equal(s.error.value, '')
  }
})
test('late successful write after account change cannot emit or fetch private data', async () => {
  const p = deferred(); let reads = 0; const s = scope({ update: () => p.promise, list: async () => { reads++; return [policy()] } })
  s.openEditor(policy()); const write = s.save(); s.permissions.state.user = {}; s.reset(); p.resolve(policy()); await write; assert.equal(reads, 0); assert.deepEqual(s.events, []); assert.equal(s.policies.value.length, 0)
})
test('read-only console capabilities can see history but cannot mutate', async () => {
  let writes = 0; const s = scope({ versions: async () => history(), update: async () => { writes++ }, rollback: async () => { writes++ } }); s.permissions.can = capability => capability === 'viewConsole'
  await s.showHistory(policy()); assert.equal(s.history.value.policyId, 7); s.openEditor(policy()); await s.toggle(policy()); s.reviewRollback(version()); await s.confirmRollback(); assert.equal(writes, 0); assert.equal(s.editorVisible.value, false); assert.equal(s.review.value, null)
})
test('failed and wrong-policy history reads leave no previous facts or rollback review', async () => {
  for (const versions of [async () => { throw Error('503') }, async () => ({ ...history(), policyId: 99 })]) {
    const s = scope({ versions }); s.history.value = history(); s.reviewRollback(version()); await s.showHistory(policy()); assert.equal(s.history.value, null); assert.equal(s.review.value, null); assert.match(s.error.value, /失败/)
  }
})
test('invalid form data cannot publish and losing capabilities blocks reads and writes', async () => {
  let calls = 0; const s = scope({ list: async () => { calls++; return [] }, create: async () => { calls++ } }); s.openEditor(); s.form.name = 'PUBLIC'; s.form.ruleJson = '[]'; await s.save(); assert.equal(calls, 0); assert.match(s.error.value, /有效/)
  s.permissions.can = () => false; await s.refresh(); s.openEditor(); await s.save(); assert.equal(calls, 0); assert.equal(s.policies.value.length, 0)
})
test('API sends exact version guards and returns typed response payloads', async () => {
  const source = readFileSync(new URL('../src/api/governancePolicies.ts', import.meta.url), 'utf8').replace(/^import .*$/gm, '').replace(/^export /gm, '')
  const calls = [], s = vm.createContext({ getData: (...args) => { calls.push(['get', ...args]); return history() }, http: { put: async (...args) => { calls.push(['put', ...args]); return { data: { data: policy() } } }, post: async (...args) => { calls.push(['post', ...args]); return { data: { data: policy() } } } } })
  vm.runInContext(ts.transpile(source + '\nthis.api = policyApi', { target: ts.ScriptTarget.ES2022 }), s)
  await s.api.versions(7); await s.api.update(7, { name: 'PUBLIC', expectedRevision: 3 }); await s.api.rollback(7, 3, 1, 'a'.repeat(64));
  assert.equal(calls[0][1], '/policies/7/versions'); assert.equal(calls[1][1], '/policies/7'); assert.deepEqual(plain(calls[2][2]), { expectedRevision: 3, sourceRevision: 1, sourceDigest: 'a'.repeat(64) }); assert.ok(calls.every(call => call.at(-1).silentError))
})
test('template keeps two-step confirmation central capabilities and explicit history limits', () => {
  assert.match(vue, /回退核对/); assert.match(vue, /确认发布回退版本/); assert.match(vue, /最近 50 个版本/); assert.match(vue, /既有决策与审批记录保留原结果/); assert.doesNotMatch(vue, /v-html|\.roles|JWT|setInterval/)
})
