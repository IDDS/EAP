import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import * as vue from 'vue'

function moduleAt(name, imports) {
  const source = readFileSync(new URL(`../src/${name}`, import.meta.url), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  new Function('exports', 'require', code)(exports, name => { if (!(name in imports)) throw Error(`Unexpected import ${name}`); return imports[name] })
  return exports
}
const stream = 'a'.repeat(64), otherStream = 'b'.repeat(64)
const row = (id = 1, key = stream) => ({ id, agentId: 7, runtimeInstanceKey: 'instance-7', streamKey: key,
  event: { framework: 'langgraph', frameworkVersion: '1.2.17' } })
const page = (items = [row()]) => ({ items, hasMore: false, nextAfterId: items.at(-1)?.id ?? 0 })
const graph = () => ({ streamKey: stream, runs: [], tools: [], artifacts: [], gaps: [], complete: false, evidenceKind: 'AUTHENTICATED_PRODUCER_REPORT' })
const api = getData => moduleAt('api/agentExecutions.ts', { './http': { getData } })
const helpers = moduleAt('components/trace/executionPresentation.ts', {})

test('event queries use bounded GET scope and forward request cancellation', async () => {
  const calls = [], signal = new AbortController().signal
  const client = api(async (...args) => { calls.push(args); return page([row(9)]) })
  await client.getExecutionPage({ agentId: 7, streamKey: stream, afterId: 8 }, signal)
  const [url, options] = calls[0]
  assert.equal(url, `/observability/agent-executions?afterId=8&limit=100&agentId=7&streamKey=${stream}`)
  assert.equal(options.signal, signal); assert.equal(options.silentError, true)
})
test('event pages reject foreign filters, duplicate/backward IDs and stuck cursors', async () => {
  for (const invalid of [page([row(8)]), page([row(9), row(9)]), page([row(9, otherStream)]),
    page([{ ...row(9), agentId: 8 }]), { ...page([row(9)]), nextAfterId: 8 }, { items: [], hasMore: true, nextAfterId: 8 }]) {
    await assert.rejects(api(async () => invalid).getExecutionPage({ agentId: 7, streamKey: stream, afterId: 8 }))
  }
})
test('an empty later page preserves the requested cursor', async () => {
  const response = { items: [], hasMore: false, nextAfterId: 9 }
  assert.equal(await api(async () => response).getExecutionPage({ afterId: 9 }), response)
})
test('graph reads reject mismatched streams and unsupported provenance or states', async () => {
  for (const invalid of [{ ...graph(), streamKey: otherStream }, { ...graph(), evidenceKind: 'KERNEL_PROOF' },
    { ...graph(), runs: [{ runId: 'r', state: 'RUNNING' }] }, { ...graph(), tools: Array(4097).fill({}) }]) {
    await assert.rejects(api(async () => invalid).getExecutionGraph(stream))
  }
  let calls = 0
  await assert.rejects(api(async () => { calls++; return graph() }).getExecutionGraph('../secret'))
  assert.equal(calls, 0)
})
test('a structurally complete graph can still contain reported failures', async () => {
  const response = { ...graph(), complete: true, runs: [{ runId: 'r', state: 'FAILED', parentRunId: null, definitionKey: 'worker', missingParent: false }] }
  const actual = await api(async () => response).getExecutionGraph(stream)
  assert.equal(actual.complete, true); assert.equal(actual.runs[0].state, 'FAILED')
  assert.equal(helpers.executionStateLabels.FAILED, '报告失败')
})
test('stream grouping keeps producer streams distinct and counts only current-page events', () => {
  const grouped = helpers.executionStreams(page([row(1), row(2, otherStream), row(3)]))
  assert.equal(grouped.length, 2); assert.deepEqual(grouped.map(s => s.pageEvents), [2, 1])
  assert.deepEqual(helpers.executionStreams(null), [])
})
test('tree rendering orders late parents before children and retains missing parents', () => {
  const result = helpers.executionTree([{ runId: 'child', parentRunId: 'parent' }, { runId: 'orphan', parentRunId: 'absent' }, { runId: 'parent', parentRunId: null }])
  assert.deepEqual(result.map(item => [item.run.runId, item.depth]), [['orphan', 0], ['parent', 0], ['child', 1]])
})
test('deep chains and malformed cycles remain bounded without recursive stack overflow', () => {
  const deep = Array.from({ length: 4096 }, (_, i) => ({ runId: String(i), parentRunId: i ? String(i - 1) : null }))
  assert.equal(helpers.executionTree(deep).at(-1).depth, 4095)
  assert.equal(helpers.executionTree([{ runId: 'a', parentRunId: 'b' }, { runId: 'b', parentRunId: 'a' }]).length, 2)
})

function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const settle = () => new Promise(resolve => setImmediate(resolve))
function mount(read, { allowed = true, initialKey = 'A' } = {}) {
  const access = vue.reactive({ user: { subject: 'one' }, allowed })
  const key = vue.shallowRef(initialKey)
  const permissions = { state: access, can: capability => capability === 'viewConsole' && access.allowed }
  const { useExecutionQuery } = moduleAt('components/trace/useExecutionQuery.ts', { vue, '@/stores/permissions': { usePermissions: () => permissions } })
  const scope = vue.effectScope()
  const query = scope.run(() => useExecutionQuery(() => key.value, read))
  return { access, key, query, stop: () => scope.stop() }
}
test('new filters abort and discard late successes and failures', async () => {
  for (const rejectOld of [false, true]) {
    const a = deferred(), b = deferred(), calls = []
    const state = mount((key, signal) => { calls.push({ key, signal }); return key === 'A' ? a.promise : b.promise })
    state.key.value = 'B'; assert.equal(calls[0].signal.aborted, true)
    b.resolve({ owner: 'B' }); await settle()
    if (rejectOld) a.reject(Error('old')); else a.resolve({ owner: 'A' })
    await settle(); assert.equal(state.query.data.value.owner, 'B'); assert.equal(state.query.error.value, ''); state.stop()
  }
})
test('permission loss synchronously clears records and prevents new reads', async () => {
  let calls = 0
  const state = mount(async () => { calls++; return { private: true } })
  await settle(); assert.ok(state.query.data.value)
  state.access.allowed = false
  assert.equal(state.query.data.value, null); await state.query.refresh(); assert.equal(calls, 1); state.stop()
})
test('account changes invalidate pending data even when both users may read', async () => {
  const old = deferred(), current = deferred(); let calls = 0
  const state = mount(() => ++calls === 1 ? old.promise : current.promise)
  state.access.user = { subject: 'two' }; old.resolve({ owner: 'one' }); await settle()
  assert.equal(state.query.data.value, null)
  current.resolve({ owner: 'two' }); await settle(); assert.equal(state.query.data.value.owner, 'two'); state.stop()
})
test('unmount cancels requests and discards results', async () => {
  const pending = deferred(); let signal
  const state = mount((key, passed) => { signal = passed; return pending.promise })
  state.stop(); assert.equal(signal.aborted, true); pending.resolve({ owner: 'gone' }); await settle()
  assert.equal(state.query.data.value, null); assert.equal(state.query.loading.value, false)
})
test('a failed refresh removes prior data and offers retry without exposing server errors', async () => {
  let failed = false
  const state = mount(async () => { if (failed) throw Error('PRIVATE SERVER MESSAGE'); return page() })
  await settle(); failed = true; await state.query.refresh()
  assert.equal(state.query.data.value, null); assert.match(state.query.error.value, /读取失败/); assert.doesNotMatch(state.query.error.value, /PRIVATE/)
  failed = false; await state.query.refresh(); assert.ok(state.query.data.value); assert.equal(state.query.error.value, ''); state.stop()
})
test('empty query and absent capability do not fetch', async () => {
  for (const options of [{ initialKey: '' }, { allowed: false }]) {
    let calls = 0; const state = mount(async () => { calls++; return page() }, options)
    await state.query.refresh(); assert.equal(calls, 0); assert.equal(state.query.data.value, null); state.stop()
  }
})
