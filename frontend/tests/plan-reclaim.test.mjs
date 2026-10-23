import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
import * as vue from 'vue'

const source = readFileSync(new URL('../src/api/planReclaim.ts', import.meta.url), 'utf8')
function load(getData) {
  const exports = {}
  vm.runInNewContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }), {
    exports, require: name => name === 'vue' ? vue : { getData },
  })
  return exports
}
function deferred() {
  let resolve, reject
  const promise = new Promise((a, b) => { resolve = a; reject = b })
  return { promise, resolve, reject }
}
function setup(getData, enabled = true) {
  const scope = vue.effectScope()
  const input = vue.reactive({ planId: 1, enabled, revision: 0, principal: 'operator' })
  const check = scope.run(() => load(getData).usePlanReclaimCheck({
    planId: () => input.planId, enabled: () => input.enabled,
    revision: () => input.revision, principal: () => input.principal,
  }))
  return { scope, input, check }
}
const flush = () => new Promise(resolve => setImmediate(resolve))
const ready = planId => ({ planId, allowed: true, steps: [] })

test('changing plan synchronously blocks submission and discards previous success', async () => {
  const a = deferred(), b = deferred(); const urls = []
  const { scope, input, check } = setup(url => { urls.push(url); return urls.length === 1 ? a.promise : b.promise })
  assert.equal(check.blocked.value, true)
  input.planId = 2
  b.resolve({ planId: 2, allowed: false, steps: [{ code: 'GLOBAL_UNRESOLVED_WORK' }] }); await flush()
  a.resolve(ready(1)); await flush()
  assert.equal(check.result.value.planId, 2); assert.equal(check.blocked.value, true)
  assert.deepEqual(urls, ['/orchestration/plans/1/reclaim-readiness', '/orchestration/plans/2/reclaim-readiness'])
  scope.stop()
})

test('disabling automatic reclaim allows ordinary submission without another GET', async () => {
  const a = deferred(); let calls = 0
  const { scope, input, check } = setup(() => { calls++; return a.promise })
  input.enabled = false
  assert.equal(check.blocked.value, false); assert.equal(check.loading.value, false)
  a.resolve(ready(1)); await flush()
  assert.equal(check.result.value, undefined); assert.equal(calls, 1)
  scope.stop()
})

test('edits and identity changes invalidate old readiness; failed refresh stays blocked', async () => {
  let calls = 0
  const { scope, input, check } = setup(async () => { if (++calls === 1) return ready(1); throw new Error('503') })
  await flush(); assert.equal(check.blocked.value, false)
  input.revision++
  assert.equal(check.result.value, undefined); assert.equal(check.blocked.value, true)
  await flush(); assert.match(check.error.value, /未完成/)
  input.principal = 'viewer'; await flush(); assert.equal(calls, 3)
  scope.stop()
})

test('latest manual refresh owns results and unmount cannot restore them', async () => {
  const a = deferred(), b = deferred(); let calls = 0
  const { scope, check } = setup(() => ++calls === 1 ? a.promise : b.promise)
  const refresh = check.refresh()
  b.resolve(ready(1)); await refresh
  a.reject(new Error('old failure')); await flush()
  assert.equal(check.blocked.value, false); assert.equal(check.error.value, '')
  scope.stop(); assert.equal(check.result.value, undefined)
  await check.refresh(); assert.equal(calls, 2)
})

test('disabled check and mismatched response never grant automatic reclaim', async () => {
  let calls = 0
  const { scope, input, check } = setup(async () => { calls++; return ready(9) }, false)
  assert.equal(calls, 0)
  input.enabled = true; await flush()
  assert.equal(check.blocked.value, true); assert.match(check.error.value, /未完成/)
  scope.stop()
})

test('known server rejection differs from uncertain transport or server failure', () => {
  const { planSubmissionError } = load(() => {})
  assert.match(planSubmissionError({ response: { status: 400, data: { message: '自动休眠受阻' } } }), /提交被拒绝：自动休眠受阻/)
  assert.match(planSubmissionError({ response: { status: 409 } }), /提交被拒绝/)
  assert.match(planSubmissionError({ response: { status: 500 } }), /提交未确认/)
  assert.match(planSubmissionError(new Error('timeout')), /提交未确认/)
})
