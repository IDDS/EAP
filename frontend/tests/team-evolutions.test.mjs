import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const vue = readFileSync(new URL('../src/views/TeamEvolutionView.vue', import.meta.url), 'utf8')
const script = vue.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const root = id => ({ summary: { id, baselineId: 'base', candidates: [{ id: 'base' }, { id: 'child' }] }, evaluations: [], holdout: null, closure: null })
const page = id => ({ items: [{ id }], nextBeforeId: null, pageLimit: 20, scanLimited: false })
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function scope(api = {}) {
  const ast = ts.createSourceFile('team.ts', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const functions = ast.statements.filter(ts.isFunctionDeclaration).map(n => n.getText(ast)).join('\n')
  const s = vm.createContext({ alive: true, generation: 0, detailGeneration: 0, contentGeneration: 0,
    page: { value: null }, detail: { value: null }, content: { value: null }, selectedId: { value: null },
    loading: { value: false }, detailLoading: { value: false }, contentLoading: { value: false },
    error: { value: '' }, detailError: { value: '' }, contentError: { value: '' },
    permissions: { state: { user: {} }, can: () => true }, TeamEvolutionApi: { list: async () => page(2), get: async id => root(id), ...api } })
  vm.runInContext(ts.transpile(functions, { target: ts.ScriptTarget.ES2022 }), s)
  return s
}
test('refresh lists metadata only and never automatically reads content or executes', async () => {
  const calls = []; const s = scope({ list: async cursor => { calls.push(cursor); return page(2) }, content: () => { throw Error('private read') } })
  await s.load(); assert.equal(s.page.value.items[0].id, 2); assert.equal(s.detail.value, null); assert.equal(s.content.value, null); assert.deepEqual(calls, [undefined])
})
test('pagination passes the original cursor and empty scanned pages retain continuation', async () => {
  const calls = []; const s = scope({ list: async cursor => { calls.push(cursor); return { ...page(1), items: [], nextBeforeId: 12, scanLimited: true } } })
  await s.load(20); assert.deepEqual(calls, [20]); assert.equal(s.page.value.nextBeforeId, 12); assert.equal(s.page.value.scanLimited, true)
  await s.load(-1); await s.load(1.5); assert.equal(calls.length, 1)
})
test('late list responses cannot overwrite a newer page', async () => {
  const p = deferred(); let n = 0; const s = scope({ list: () => ++n === 1 ? p.promise : Promise.resolve(page(1)) })
  const prior = s.load(); await s.load(2); p.resolve(page(9)); await prior; assert.equal(s.page.value.items[0].id, 1)
})
test('selecting another root discards old detail content and late prior results', async () => {
  const p = deferred(); const s = scope({ get: id => id === 1 ? p.promise : Promise.resolve(root(id)) })
  s.content.value = { private: 'old' }; const first = s.select(1); assert.equal(s.content.value, null)
  await s.select(2); p.resolve(root(1)); await first; assert.equal(s.detail.value.summary.id, 2); assert.equal(s.selectedId.value, 2)
})
test('failed or wrong-root detail leaves an explicit error and no previous facts', async () => {
  for (const get of [async () => { throw Error('503') }, async () => root(99)]) {
    const s = scope({ get }); s.detail.value = root(1); s.content.value = {}; await s.select(2)
    assert.equal(s.detail.value, null); assert.equal(s.content.value, null); assert.match(s.detailError.value, /读取失败/)
  }
})
test('losing account capability or component lifetime discards pending reads', async () => {
  for (const change of [s => s.permissions.state.user = {}, s => s.permissions.can = () => false, s => s.alive = false]) {
    const p = deferred(); const s = scope({ get: () => p.promise }); const pending = s.select(1); change(s); s.reset(); p.resolve(root(1)); await pending
    assert.equal(s.detail.value, null); assert.equal(s.page.value, null); assert.equal(s.content.value, null); assert.equal(s.detailError.value, '')
  }
})
test('content is explicit capability gated and retains no runtime protocol fields', async () => {
  let calls = 0; const s = scope({ content: async () => { calls++; return { protocol: { endpoint: 'private' }, samples: [], candidates: [] } } })
  await s.select(2); s.permissions.can = cap => cap === 'viewConsole'; await s.loadContent(); assert.equal(calls, 0)
  s.permissions.can = () => true; await s.loadContent(); assert.equal(calls, 1); assert.equal('protocol' in s.content.value, false); s.clearContent(); assert.equal(s.content.value, null)
})
test('late content after root switch collapse or revoked permission cannot reappear', async () => {
  for (const change of [s => s.select(2), s => s.clearContent(), s => { s.permissions.can = cap => cap !== 'viewContent' }]) {
    const p = deferred(); const s = scope({ content: () => p.promise }); await s.select(1); const pending = s.loadContent(); await change(s); p.resolve({ samples: ['private'], candidates: [] }); await pending; assert.equal(s.content.value, null)
  }
})
test('failed content read clears previous content without hiding valid metadata', async () => {
  const s = scope({ content: async () => { throw Error('403') } }); await s.select(1); s.content.value = { private: true }; await s.loadContent()
  assert.equal(s.content.value, null); assert.equal(s.detail.value.summary.id, 1); assert.match(s.contentError.value, /正文读取失败/)
})
test('list failure clears old selected facts and does not manufacture an empty success', async () => {
  const s = scope({ list: async () => { throw Error('offline') } }); s.detail.value = root(2); s.content.value = {}; await s.load()
  assert.equal(s.page.value, null); assert.equal(s.detail.value, null); assert.equal(s.content.value, null); assert.match(s.error.value, /列表读取失败/)
})
test('holdout shows only independent batch scores and preserves incomplete closure', () => {
  const s = scope(); s.detail.value = { ...root(1), holdout: { baseline: { id: 'h1' }, selected: { id: 'h2' } }, evaluations: [{ registration: { id: 'v1' }, score: { passed: 3, denominator: 3 } }, { registration: { id: 'h1' }, score: { passed: 1, denominator: 2 } }] }
  assert.equal(s.scoreText(s.holdoutScore('baseline')), '1 / 2 精确匹配'); assert.equal(s.scoreText(s.holdoutScore('selected')), '尚无可信评分')
  s.detail.value.closure = { baseline: null, selected: null }; assert.equal(s.holdoutScore('baseline'), null)
  assert.equal(s.label('INCOMPLETE_NO_ADOPTION'), '证据不完整 · 不采纳'); assert.equal(s.label('UNKNOWN'), '结果未知')
})
test('API provides GET reads only and uses existing separate content permission endpoint', async () => {
  const raw = readFileSync(new URL('../src/api/teamEvolutions.ts', import.meta.url), 'utf8').replace(/^import .*$/gm, '').replace(/^export /gm, '')
  const calls = []; const s = vm.createContext({ getData: (...args) => { calls.push(args); return {} } }); vm.runInContext(ts.transpile(raw + '\nthis.api = TeamEvolutionApi', { target: ts.ScriptTarget.ES2022 }), s)
  await s.api.list(); await s.api.list(11); await s.api.get(2); await s.api.content(2)
  assert.deepEqual(calls.map(c => c[0]), ['/team-evolutions/console', '/team-evolutions/console?beforeId=11', '/team-evolutions/2/console', '/team-evolutions/2/content']); assert.ok(calls.every(c => c[1].silentError)); assert.doesNotMatch(raw, /postData|http\.post|http\.put/)
})
test('page explains partial evidence and uses central capabilities without automatic polling', () => {
  assert.match(vue, /执行未知与账本未知分别记录/); assert.match(vue, /不能视为留出通过/); assert.match(vue, /viewContent/); assert.match(vue, /flush: 'sync'/); assert.doesNotMatch(vue, /v-html|\.roles\.includes|JWT|setInterval|localStorage|sessionStorage/)
})
