import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import vm from 'node:vm'
const source = readFileSync(new URL('../src/components/workflow/WorkflowStopPanel.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function scope(g) {
  const ast = ts.createSourceFile('stop.ts', source, ts.ScriptTarget.Latest, true)
  const functions = ast.statements.filter(ts.isFunctionDeclaration).map(n => n.getText(ast)).join('\n')
  const context = vm.createContext(g)
  vm.runInContext(ts.transpile(functions, { target: ts.ScriptTarget.ES2022 }), context)
  return context
}
function state() {
  const g = { props: { workflowId: 7, runKey: 'wf-exec-original', status: 'RUNNING' }, revision: 0, disposed: false,
    permissions: { can: () => true }, controller: undefined, AbortController,
    report: { value: { supported: true, state: 'NONE', executionStatus: 'RUNNING' } },
    loading: { value: false }, submitting: { value: false }, error: { value: '' }, emit() {} }
  g.canStop = { get value() { return !g.disposed && !g.loading.value && !g.submitting.value && g.permissions.can('runWorkflows')
    && g.report.value?.supported && g.report.value.state === 'NONE' && ['RUNNING','QUEUED'].includes(g.report.value.executionStatus) } }
  return g
}
test('stop preserves original request key after a lost response and never automatically resubmits', async () => {
  const g = state(), keys = []; let reads = 0
  g.requestWorkflowStop = async (_id, _run, key) => { keys.push(key); throw Error('lost') }
  g.getWorkflowStop = async () => { reads++; return { supported: true, state: 'NONE', executionStatus: 'RUNNING' } }
  const s = scope(g); await s.stop(); assert.equal(keys.length, 1); assert.equal(reads, 1)
  assert.match(g.error.value, /同一请求键/); await s.stop()
  assert.deepEqual(keys, ['stop-wf-exec-original','stop-wf-exec-original'])
})
test('lost response reconciles original accepted request without another mutation', async () => {
  const g = state(); let writes = 0, changes = 0
  g.emit = () => changes++
  g.requestWorkflowStop = async () => { writes++; throw Error('lost') }
  g.getWorkflowStop = async () => ({ supported: true, state: 'REQUESTED', requestKey: 'stop-wf-exec-original', executionStatus: 'RUNNING' })
  const s = scope(g); await s.stop(); await s.stop()
  assert.equal(writes, 1); assert.equal(changes, 1); assert.equal(g.report.value.state, 'REQUESTED'); assert.equal(g.error.value, '')
})
test('stop protects duplicate clicks and late identity/run responses', async () => {
  const g = state(); let writes = 0, resolve
  g.requestWorkflowStop = () => { writes++; return new Promise(r => resolve = r) }
  const s = scope(g); const first = s.stop(); await s.stop(); assert.equal(writes, 1)
  s.clear(); resolve({ state: 'APPLIED' }); await first; assert.equal(g.report.value, null)
})
test('old run response cannot populate a newly selected run', async () => {
  const g = state(); let resolve
  g.getWorkflowStop = () => new Promise(r => resolve = r)
  const s = scope(g), pending = s.load(); g.props.runKey = 'another'; s.clear()
  assert.equal(g.controller, undefined); resolve({ state: 'APPLIED' }); await pending
  assert.equal(g.report.value, null)
})
test('permission loss and unsupported historical runs cannot submit stop', async () => {
  const g = state(); g.requestWorkflowStop = () => assert.fail('must not submit')
  const s = scope(g); g.permissions.can = () => false; await s.stop(); await s.load()
  g.permissions.can = () => true; g.report.value.supported = false; await s.stop()
  g.report.value.supported = true; g.report.value.executionStatus = 'EXECUTION_UNKNOWN'; await s.stop()
})
test('failed read clears stale evidence and never presents an optimistic cancellation', async () => {
  const g = state(); g.report.value = { state: 'APPLIED' }; g.getWorkflowStop = async () => { throw Error('offline') }
  await scope(g).load(); assert.equal(g.report.value, null); assert.match(g.error.value, /不能推定已停止/)
})
test('both lost mutation and failed reconciliation preserve uncertainty', async () => {
  const g = state(); g.requestWorkflowStop = async () => { throw Error('lost') }; g.getWorkflowStop = async () => { throw Error('lost') }
  await scope(g).stop(); assert.equal(g.report.value, null); assert.match(g.error.value, /可能仍在执行/)
  assert.equal(g.submitting.value, false)
})
test('state text distinguishes requested, applied, unknown and independently finished work', () => {
  const s = scope(state()); assert.match(s.stateLabel('REQUESTED'), /可能仍在执行/)
  assert.match(s.stateLabel('APPLIED'), /外部效果保留/); assert.match(s.stateLabel('UNCONFIRMED'), /结果未知/)
  assert.match(s.stateLabel('FINISHED'), /未确认由停止请求/); assert.match(s.stateLabel('UNSUPPORTED'), /历史执行/)
})
