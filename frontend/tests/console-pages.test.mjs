import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

function handlers(file, names, globals) {
  const source = readFileSync(new URL('../src/views/' + file, import.meta.url), 'utf8').split('<script setup lang="ts">')[1].split('</script>')[0]
  const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
  const code = ast.statements.filter(n => ts.isFunctionDeclaration(n) && names.includes(n.name.text)).map(n => n.getText(ast)).join('\n')
  const scope = vm.createContext(globals)
  vm.runInContext(ts.transpile(code, { target: ts.ScriptTarget.ES2022 }), scope)
  return scope
}
const ref = value => ({ value })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const task = { id: 3, status: 'SUCCEEDED', resultSummary: 'original result' }
function operations(api = {}) {
  const writes = [], messages = []
  const s = handlers('OperationsView.vue', ['createDemoTask','updateStatus','markResolution'], {
    permissions: { state: { user: {} }, can: () => true }, disposed: false, saving: ref(false), actionError: ref(''), requester: ref('review'), selectedTaskId: ref(3), taskDetails: ref(null),
    EapApi: { createTask: async (...args) => writes.push(args), updateTaskStatus: async (...args) => writes.push(args), ...api },
    ElMessage: { success: message => messages.push(message) }, ElMessageBox: { prompt: async () => ({ value: 'evidence' }) }, load: async () => {},
  })
  return { s, writes, messages }
}
test('business task writes honor /me capability even when handlers are invoked directly', async () => {
  const { s, writes } = operations(); s.permissions.can = () => false
  await s.createDemoTask(); await s.updateStatus(3, 'RUNNING'); await s.markResolution(task, true)
  assert.equal(writes.length, 0); assert.equal(s.saving.value, false)
})
test('business result prompt blocks revoked permission, changed account, changed task and unmount', async () => {
  for (const change of [s => { s.permissions.can = () => false }, s => { s.permissions.state.user = {} }, s => { s.selectedTaskId.value = 4 }, s => { s.disposed = true }]) {
    const { s, writes } = operations(), pending = deferred()
    s.ElMessageBox.prompt = () => pending.promise
    const first = s.markResolution(task, true); change(s); pending.resolve({ value: 'evidence' }); await first
    assert.equal(writes.length, 0); assert.equal(s.saving.value, false)
  }
})
test('business result prompt is single-flight and cancellation releases the lock', async () => {
  const { s, writes } = operations(), pending = deferred(); let prompts = 0
  s.ElMessageBox.prompt = () => { prompts++; return pending.promise }
  const first = s.markResolution(task, false); await s.markResolution(task, true)
  assert.equal(prompts, 1); pending.resolve({ value: ' still unresolved ' }); await first
  assert.equal(writes.length, 1); assert.deepEqual(Array.from(writes[0]), [3, 'SUCCEEDED', 'original result', false, 'still unresolved'])
  s.ElMessageBox.prompt = async () => { throw 'cancel' }; await s.markResolution(task, true)
  assert.equal(writes.length, 1); assert.equal(s.saving.value, false)
})
test('simulated task creation blocks concurrent clicks and discards late account responses', async () => {
  const pending = deferred(); let count = 0
  const { s, messages } = operations({ createTask: () => { count++; return pending.promise } })
  const first = s.createDemoTask(); await s.createDemoTask(); assert.equal(count, 1)
  s.permissions.state.user = {}; s.load = () => assert.fail('must not refresh another account')
  pending.resolve({ id: 9 }); await first; assert.equal(messages.length, 0); assert.equal(s.saving.value, false)
})
test('failed business writes report uncertainty without retrying or showing success', async () => {
  for (const kind of ['create','update','confirm']) {
    let writes = 0; const fail = async () => { writes++; throw Error('response lost') }
    const { s, messages } = operations({ createTask: fail, updateTaskStatus: fail })
    if (kind === 'create') await s.createDemoTask()
    if (kind === 'update') await s.updateStatus(3, 'SUCCEEDED')
    if (kind === 'confirm') await s.markResolution(task, true)
    assert.equal(writes, 1); assert.equal(messages.length, 0); assert.match(s.actionError.value, /未确认/); assert.equal(s.saving.value, false)
  }
})
function ethics(api) {
  return handlers('EthicsView.vue', ['load'], { generation: 0, loading: ref(false), loaded: ref(false), error: ref(''), policies: ref([]), safetySummary: ref({ denyCount: 99 }), EapApi: api })
}
test('ethics read failure hides old and default metrics until both sources recover', async () => {
  const s = ethics({ listPolicies: async () => [], getSafetySummary: async () => { throw Error('offline') } })
  s.loaded.value = true; await s.load(); assert.equal(s.loaded.value, false); assert.match(s.error.value, /无法判断/)
  s.EapApi.getSafetySummary = async () => ({ denyCount: 5 }); await s.load()
  assert.equal(s.loaded.value, true); assert.equal(s.error.value, ''); assert.equal(s.safetySummary.value.denyCount, 5)
})
test('ethics refresh ignores stale and unmounted responses', async () => {
  const pending = deferred(); let calls = 0
  const s = ethics({ listPolicies: async () => [], getSafetySummary: () => ++calls === 1 ? pending.promise : Promise.resolve({ denyCount: 2 }) })
  const old = s.load(); await s.load(); pending.resolve({ denyCount: 100 }); await old
  assert.equal(s.safetySummary.value.denyCount, 2)
  const late = deferred(); s.EapApi.getSafetySummary = () => late.promise
  const request = s.load(); s.generation++; late.resolve({ denyCount: 200 }); await request
  assert.equal(s.loaded.value, false); assert.equal(s.safetySummary.value.denyCount, 2)
})
