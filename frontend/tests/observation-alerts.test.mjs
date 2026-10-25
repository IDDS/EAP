import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const script = readFileSync(new URL('../src/components/ObservationAlerts.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function scope(getObservationAlerts, actions = {}) {
  const ast = ts.createSourceFile('alerts.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const functions = ast.statements.filter(ts.isFunctionDeclaration).map(n => n.getText(ast)).join('\n')
  const state = vm.createContext({ generation: 0, alive: true, props: { operationId: 11 }, permissions: { state: { user: {} }, can: () => true }, data: { value: null }, loading: { value: false }, writing: { value: false }, pending: { value: null }, notice: { value: '' }, error: { value: '' }, getObservationAlerts, ...actions })
  vm.runInContext(ts.transpile(functions, { target: ts.ScriptTarget.ES2022 }), state)
  return state
}
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const view = (id, alerts = []) => ({ operation: { id }, reconciliationCurrent: true, reconciledRevision: 1, alerts })
const digest = 'a'.repeat(64)
const activeView = () => ({ ...view(11, [{ id: 3, factRevision: 1, factDigest: digest, lifecycleState: 'ACTIVE' }]), observedFactDigest: digest, reconciledFactDigest: digest, audit: [] })
const ackView = () => { const v = activeView(); v.alerts[0].lifecycleState = 'ACKNOWLEDGED'; return v }
test('switching the original operation rejects the previous operation response', async () => {
  const first = deferred(), second = deferred(); const s = scope(id => id === 11 ? first.promise : second.promise)
  const a = s.load(); s.props.operationId = 13; s.clear(); const b = s.load()
  second.resolve(view(13)); await b; first.resolve(view(11)); await a
  assert.equal(s.data.value.operation.id, 13); assert.equal(s.loading.value, false)
})
test('the latest refresh owns the result even when an earlier read finishes last', async () => {
  const first = deferred(), second = deferred(); let calls = 0; const s = scope(() => ++calls === 1 ? first.promise : second.promise)
  const a = s.load(), b = s.load(); second.resolve(view(11, [{ id: 3 }])); await b; first.resolve(view(11, [{ id: 1 }])); await a
  assert.equal(s.data.value.alerts[0].id, 3)
})
test('an account change prevents an old private response from being displayed', async () => {
  const pending = deferred(); const s = scope(() => pending.promise); const request = s.load()
  s.permissions.state.user = {}; s.clear(); pending.resolve(view(11)); await request
  assert.equal(s.data.value, null); assert.equal(s.error.value, '')
})
test('a lost capability rejects an outstanding read and prevents a new request', async () => {
  const pending = deferred(); let calls = 0; const s = scope(() => { calls++; return pending.promise }); const request = s.load()
  s.permissions.can = () => false; s.clear(); pending.resolve(view(11)); await request; await s.load()
  assert.equal(calls, 1); assert.equal(s.data.value, null); assert.equal(s.loading.value, false)
})
test('closing the drawer discards a pending response and prevents further requests', async () => {
  const pending = deferred(); let calls = 0; const s = scope(() => { calls++; return pending.promise }); const request = s.load()
  s.alive = false; s.clear(); pending.resolve(view(11)); await request; await s.load()
  assert.equal(s.data.value, null); assert.equal(calls, 1)
})
test('a failed refresh immediately clears old facts and preserves the error after an old success', async () => {
  const first = deferred(), second = deferred(); let calls = 0; const s = scope(() => ++calls === 1 ? first.promise : second.promise)
  s.data.value = view(11); const a = s.load(); assert.equal(s.data.value, null); const b = s.load()
  second.reject(new Error('503')); await b; first.resolve(view(11)); await a
  assert.equal(s.data.value, null); assert.match(s.error.value, /读取失败/); assert.equal(s.loading.value, false)
})
test('a response bound to another operation is never rendered', async () => {
  const s = scope(async () => view(13)); await s.load(); assert.equal(s.data.value, null); assert.match(s.error.value, /读取失败/)
})
test('acknowledgement wording preserves the unknown business result and fact lifecycle', () => {
  const s = scope(() => {}); assert.equal(s.lifecycle('ACKNOWLEDGED'), '已确认阅读'); assert.equal(s.outcome('EXECUTION_UNKNOWN'), '业务结果未知')
  assert.equal(s.lifecycle('SUPERSEDED'), '事实已替代'); assert.equal(s.lifecycle('RESOLVED'), '原事实已恢复')
})
test('a reviewed fact requires a separate confirmation and submits its exact operation version and digest', async () => {
  const calls = []; const s = scope(() => {}, { acknowledgeObservationAlert: async (...args) => { calls.push(args); return ackView() } })
  s.data.value = activeView(); s.reviewAcknowledgement(3); assert.equal(calls.length, 0); assert.equal(s.pending.value.factRevision, 1)
  await s.acknowledge(); assert.deepEqual(JSON.parse(JSON.stringify(calls)), [[11, 3, { factRevision: 1, factDigest: digest }]])
  assert.equal(s.data.value.alerts[0].lifecycleState, 'ACKNOWLEDGED'); assert.match(s.notice.value, /原业务结果和费用继续保留/); assert.equal(s.pending.value, null)
})
test('view-only capabilities cannot reconcile or prepare or submit an acknowledgement', async () => {
  let calls = 0; const s = scope(() => {}, { reconcileObservationAlerts: () => { calls++ }, acknowledgeObservationAlert: () => { calls++ } })
  s.permissions.can = cap => cap === 'viewConsole'; s.data.value = activeView(); s.reviewAcknowledgement(3); await s.acknowledge(); await s.reconcile()
  assert.equal(calls, 0); assert.equal(s.pending.value, null)
})
test('unreconciled stale malformed or historical alert facts cannot be acknowledged', () => {
  for (const change of [v => v.reconciledRevision = null, v => v.reconciliationCurrent = false, v => v.observedFactDigest = 'b'.repeat(64), v => v.reconciledFactDigest = 'bad', v => v.alerts[0].factRevision = 2, v => v.alerts[0].factDigest = 'b'.repeat(64), v => v.alerts[0].lifecycleState = 'ACKNOWLEDGED', v => v.operation.id = 13]) {
    const s = scope(() => {}); const v = activeView(); change(v); s.data.value = v; s.reviewAcknowledgement(3); assert.equal(s.pending.value, null)
  }
})
test('a changed fact never silently upgrades a pending confirmation to the new version', async () => {
  let calls = 0; const s = scope(() => {}, { acknowledgeObservationAlert: () => { calls++ } }); s.data.value = activeView(); s.reviewAcknowledgement(3)
  s.data.value.reconciledRevision = 2; s.data.value.alerts[0].factRevision = 2; await s.acknowledge()
  assert.equal(calls, 0); assert.equal(s.pending.value, null)
})
test('refresh and cancellation discard a prepared confirmation without submitting it', async () => {
  let calls = 0; const s = scope(async () => activeView(), { acknowledgeObservationAlert: () => { calls++ } })
  s.data.value = activeView(); s.reviewAcknowledgement(3); s.pending.value = null; await s.acknowledge(); assert.equal(calls, 0)
  s.reviewAcknowledgement(3); await s.load(); assert.equal(s.pending.value, null); await s.acknowledge(); assert.equal(calls, 0)
})
test('double confirmation and refresh during a pending write submit only one request', async () => {
  const pending = deferred(); let writes = 0, reads = 0; const s = scope(() => { reads++ }, { acknowledgeObservationAlert: () => { writes++; return pending.promise } })
  s.data.value = activeView(); s.reviewAcknowledgement(3); const a = s.acknowledge(); s.reviewAcknowledgement(3); await s.acknowledge(); await s.reconcile(); await s.load()
  assert.equal(writes, 1); assert.equal(reads, 0); assert.equal(s.writing.value, true); pending.resolve(ackView()); await a; assert.equal(s.writing.value, false)
})
test('a 409 clears stale facts and requires a fresh review without retrying or automatically reading', async () => {
  let writes = 0, reads = 0; const s = scope(() => { reads++ }, { acknowledgeObservationAlert: async () => { writes++; throw { response: { status: 409 } } } })
  s.data.value = activeView(); s.reviewAcknowledgement(3); await s.acknowledge(); await s.acknowledge(); await s.reconcile()
  assert.equal(writes, 1); assert.equal(reads, 0); assert.equal(s.data.value, null); assert.equal(s.pending.value, null); assert.match(s.error.value, /版本已变化/)
})
test('an uncertain write requires audit inspection and never resubmits automatically', async () => {
  let writes = 0; const s = scope(async () => ackView(), { acknowledgeObservationAlert: async () => { writes++; throw Error('connection lost after commit') } })
  s.data.value = activeView(); s.reviewAcknowledgement(3); await s.acknowledge(); await s.acknowledge(); assert.equal(writes, 1); assert.match(s.error.value, /不会自动重试/); assert.equal(s.data.value, null)
  await s.load(); assert.equal(s.data.value.alerts[0].lifecycleState, 'ACKNOWLEDGED'); assert.equal(writes, 1); assert.equal(s.error.value, '')
})
test('account operation capability and drawer changes discard late write results and messages', async () => {
  for (const change of [s => s.permissions.state.user = {}, s => s.props.operationId = 13, s => s.permissions.can = () => false, s => s.alive = false]) {
    const pending = deferred(); const s = scope(() => {}, { acknowledgeObservationAlert: () => pending.promise }); s.data.value = activeView(); s.reviewAcknowledgement(3); const a = s.acknowledge()
    change(s); s.clear(); pending.resolve(ackView()); await a; assert.equal(s.data.value, null); assert.equal(s.notice.value, ''); assert.equal(s.error.value, ''); assert.equal(s.writing.value, false)
  }
})
test('a late failed write does not clear a newer operation or leak an error message', async () => {
  const pending = deferred(); const s = scope(async () => view(13), { reconcileObservationAlerts: () => pending.promise }); s.data.value = activeView(); const a = s.reconcile()
  s.props.operationId = 13; s.clear(); await s.load(); pending.reject(Error('old write failed')); await a
  assert.equal(s.data.value.operation.id, 13); assert.equal(s.error.value, ''); assert.equal(s.notice.value, '')
})
test('reconciliation updates only from its bound operation and cannot overtake a pending write', async () => {
  let calls = 0; const pending = deferred(); const s = scope(() => {}, { reconcileObservationAlerts: () => { calls++; return pending.promise } }); s.data.value = activeView(); s.reviewAcknowledgement(3)
  const a = s.reconcile(); await s.reconcile(); assert.equal(calls, 1); assert.equal(s.pending.value, null); pending.resolve(activeView()); await a
  assert.match(s.notice.value, /已重新核对/); assert.equal(s.data.value.operation.id, 11)
})
test('mismatched operation or acknowledgement responses cannot report success', async () => {
  for (const reply of [view(13), activeView()]) {
    const s = scope(() => {}, { acknowledgeObservationAlert: async () => reply }); s.data.value = activeView(); s.reviewAcknowledgement(3); await s.acknowledge()
    assert.equal(s.data.value, null); assert.equal(s.notice.value, ''); assert.match(s.error.value, /尚无法确认/)
  }
})
test('write API paths carry only the reviewed fact and use panel-owned error handling', async () => {
  const source = readFileSync(new URL('../src/api/observationAlerts.ts', import.meta.url), 'utf8').replace(/^import .*$/gm, '').replace(/^export /gm, '')
  const calls = []; const state = vm.createContext({ getData: () => {}, http: { post: async (...args) => { calls.push(args); return { data: { data: ackView() } } } } })
  vm.runInContext(ts.transpile(source + '\nthis.reconcile = reconcileObservationAlerts; this.ack = acknowledgeObservationAlert', { target: ts.ScriptTarget.ES2022 }), state)
  await state.reconcile(11); await state.ack(11, 3, { factRevision: 1, factDigest: digest })
  assert.equal(calls[0][0], '/observability/operations/11/alerts/reconcile'); assert.equal(calls[0][1], undefined)
  assert.equal(calls[1][0], '/observability/operations/11/alerts/3/acknowledgement'); assert.deepEqual(JSON.parse(JSON.stringify(calls[1][1])), { factRevision: 1, factDigest: digest })
  assert.equal(calls[0][2].silentError, true); assert.equal(calls[1][2].silentError, true)
})
