import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import * as Vue from 'vue'

function api(realVue = false) {
  const source = readFileSync(new URL('../src/api/orchestrationEditing.ts', import.meta.url), 'utf8')
  const callbacks = [], cleanup = []
  const vue = { ref: realVue ? Vue.ref : value => ({ value }), watch: realVue ? Vue.watch : (_, callback) => callbacks.push(callback), onBeforeUnmount: callback => cleanup.push(callback) }
  const exports = {}
  new Function('require', 'exports', ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }))(() => vue, exports)
  return { ...exports, invalidate: () => callbacks.forEach(f => f()), unmount: () => cleanup.forEach(f => f()) }
}
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }

test('only unsubmitted draft or ready plans can be edited', () => {
  const a = api()
  assert.equal(a.editablePlan({ status: 'READY' }), true)
  for (const value of [{ status: 'READY', executionWorkflowId: 9 }, { status: 'READY', executionRunKey: 'original' }, { status: 'RUNNING' }, { status: 'EXECUTION_UNKNOWN' }, { status: 'CANCELLED' }]) assert.equal(a.editablePlan(value), false)
})
test('reordering returns one adjacent permutation without modifying original steps', () => {
  const a = api(), steps = [{ id: 8 }, { id: 2 }, { id: 11 }]
  assert.deepEqual(a.reorderedStepIds(steps, 2, -1), [2, 8, 11])
  assert.deepEqual(a.reorderedStepIds(steps, 2, 1), [8, 11, 2])
  assert.deepEqual(steps.map(s => s.id), [8, 2, 11])
  for (const [id,offset] of [[8,-1],[11,1],[99,1],[2,3]]) assert.equal(a.reorderedStepIds(steps, id, offset), null)
})
test('mutations enforce permission and single-flight before making requests', async () => {
  const a = api(), wait = deferred(), activity = []
  let allowed = false, calls = 0, completed = 0
  const m = a.useOrchestrationMutation(() => 'plan', () => allowed, v => activity.push(v))
  const action = () => { calls++; return wait.promise }
  await m.run(action, () => completed++)
  assert.equal(calls, 0)
  allowed = true
  const first = m.run(action, () => completed++)
  await m.run(action, () => completed++)
  assert.equal(calls, 1)
  wait.resolve(); await first
  assert.equal(completed, 1); assert.deepEqual(activity, [true, false])
})
test('scope change or unmount discards late responses and releases parent operation state once', async () => {
  for (const invalidate of ['invalidate', 'unmount']) {
    const a = api(), wait = deferred(), activity = []
    const m = a.useOrchestrationMutation(() => 'plan', () => true, v => activity.push(v))
    const first = m.run(() => wait.promise, () => assert.fail('stale response'))
    a[invalidate](); wait.resolve(); await first
    assert.deepEqual(activity, [true, false]); assert.equal(m.busy.value, false)
  }
})
test('confirmation cannot write after the selection or account changed', async () => {
  const a = api(), wait = deferred()
  let writes = 0
  const m = a.useOrchestrationMutation(() => 'plan', () => true)
  const first = m.run(async current => { await wait.promise; if (!current()) throw 'cancel'; writes++ }, () => {})
  a.invalidate(); wait.resolve(); await first
  assert.equal(writes, 0); assert.equal(m.error.value, '')
})
test('permission revocation discards response and network failures remain unconfirmed', async () => {
  const a = api(), wait = deferred()
  let allowed = true, calls = 0
  const m = a.useOrchestrationMutation(() => 'plan', () => allowed)
  const first = m.run(() => wait.promise, () => assert.fail('revoked permission'))
  allowed = false; wait.resolve(); await first
  allowed = true
  await m.run(async () => { calls++; throw Error('private transport detail') }, () => assert.fail('not confirmed'))
  assert.equal(calls, 1); assert.match(m.error.value, /未确认/); assert.doesNotMatch(m.error.value, /private/)
  assert.equal(a.orchestrationEditError('cancel'), '')
  assert.match(a.orchestrationEditError({ response: { status: 403 } }), /权限/)
})

test('real account identity changes invalidate each editor even when both accounts can write', async () => {
  for (const name of ['TeamManager', 'PlanActions', 'PlanStepActions']) {
    const source = readFileSync(new URL(`../src/components/orchestration/${name}.vue`, import.meta.url), 'utf8')
    const scopeExpression = source.match(/const scope = (.+)/)[1]
    const props = Vue.reactive({ team: { id: 1 }, plan: { id: 2 }, step: { id: 3 } })
    const permissions = Vue.reactive({ state: { user: { subject: 'first' } } })
    const scope = new Function('props', 'permissions', `return ${scopeExpression}`)(props, permissions)
    const effects = Vue.effectScope(), a = api(true), waiting = deferred()
    let mutation, completed = false, currentRequest
    effects.run(() => { mutation = a.useOrchestrationMutation(scope, () => true) })
    const running = mutation.run(current => { currentRequest = current; return waiting.promise }, () => { completed = true })
    assert.equal(currentRequest(), true)
    permissions.state.user = { subject: 'second' }
    assert.equal(currentRequest(), false, name + ' must detect actual identity, not object stringification')
    waiting.resolve(); await running
    assert.equal(completed, false); a.unmount(); effects.stop()
  }
})
