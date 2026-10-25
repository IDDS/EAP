import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const vue = readFileSync(new URL('../src/components/ObservationLogSearch.vue', import.meta.url), 'utf8')
const script = vue.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function scope(searchObservationLogs) {
  const ast = ts.createSourceFile('logs.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const functions = ast.statements.filter(ts.isFunctionDeclaration).map(n => n.getText(ast)).join('\n')
  const events = []; const state = vm.createContext({ generation: 0, alive: true, props: { minutes: 60 }, query: { value: 'CHAT' }, permissions: { state: { user: {} }, can: () => true }, data: { value: null }, loading: { value: false }, error: { value: '' }, searchObservationLogs, emit: (...args) => events.push(args) })
  vm.runInContext(ts.transpile(functions, { target: ts.ScriptTarget.ES2022 }), state); state.events = events; return state
}
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const result = (minutes = 60) => ({ sourceContract: 'PERSISTED_OPERATION_EVENT_TEXT_V1', windowMinutes: minutes, logs: [{ operationId: 13, text: 'PUBLIC' }] })
test('search sends only the selected text and event window and links a returned original operation', async () => {
  const calls = []; const s = scope(async (...args) => { calls.push(args); return result() }); s.query.value = '  CHAT  '; await s.search(); assert.deepEqual(calls, [['CHAT', 60]])
  s.select(13); s.select(999); assert.deepEqual(s.events, [['select', 13]])
})
test('the latest query owns the result even when an old request finishes last', async () => {
  const a = deferred(), b = deferred(); let calls = 0; const s = scope(() => ++calls === 1 ? a.promise : b.promise)
  const first = s.search(); s.query.value = 'RUNTIME_TASK'; s.clear(); const second = s.search(); b.resolve(result()); await second; a.resolve({ ...result(), logs: [{ operationId: 11 }] }); await first
  assert.equal(s.data.value.logs[0].operationId, 13); assert.equal(s.loading.value, false)
})
test('query window account capability and unmount changes reject late private results', async () => {
  for (const change of [s => s.query.value = 'NEW', s => s.props.minutes = 1440, s => s.permissions.state.user = {}, s => s.permissions.can = () => false, s => s.alive = false]) {
    const pending = deferred(); const s = scope(() => pending.promise); const p = s.search(); change(s); s.clear(); pending.resolve(result()); await p; assert.equal(s.data.value, null); assert.equal(s.error.value, '')
  }
})
test('lost capabilities and unmount prevent requests or result navigation', async () => {
  for (const change of [s => s.permissions.can = () => false, s => s.alive = false]) {
    let calls = 0; const s = scope(() => { calls++ }); s.data.value = result(); change(s); s.select(13); await s.search(); assert.equal(calls, 0); assert.equal(s.data.value, null); assert.deepEqual(s.events, [])
  }
})
test('failed search clears old results and an older success cannot restore them', async () => {
  const a = deferred(), b = deferred(); let calls = 0; const s = scope(() => ++calls === 1 ? a.promise : b.promise); s.data.value = result()
  const first = s.search(); assert.equal(s.data.value, null); const second = s.search(); b.reject(Error('503')); await second; a.resolve(result()); await first; assert.equal(s.data.value, null); assert.match(s.error.value, /读取失败/)
})
test('unexpected source contract or window cannot be rendered or linked', async () => {
  for (const response of [{ ...result(), sourceContract: 'OTHER' }, result(1440)]) {
    const s = scope(async () => response); await s.search(); s.select(13); assert.equal(s.data.value, null); assert.deepEqual(s.events, []); assert.match(s.error.value, /读取失败/)
  }
})
test('empty results and both truncation flags retain the scope and business caveats', () => {
  assert.match(vue, /data\.scopeTruncated/); assert.match(vue, /data\.matchesTruncated/); assert.match(vue, /这不代表业务成功或观测齐全/); assert.match(vue, /日志数量不作为原操作分母/); assert.doesNotMatch(vue, /v-html|setInterval|\.roles|JWT/)
})
test('the search API encodes query parameters and performs only a silent GET', async () => {
  const source = readFileSync(new URL('../src/api/observationLogs.ts', import.meta.url), 'utf8').replace(/^import .*$/gm, '').replace(/^export /gm, '')
  const calls = []; const s = vm.createContext({ URLSearchParams, getData: (...args) => { calls.push(args); return result() } }); vm.runInContext(ts.transpile(source + '\nthis.search = searchObservationLogs', { target: ts.ScriptTarget.ES2022 }), s)
  await s.search('operation_id=13 CHAT', 1440); const u = new URL('http://fixture' + calls[0][0]); assert.equal(u.pathname, '/observability/logs'); assert.equal(u.searchParams.get('query'), 'operation_id=13 CHAT'); assert.equal(u.searchParams.get('minutes'), '1440'); assert.equal(u.searchParams.get('limit'), '50'); assert.equal(calls[0][1].silentError, true)
})
