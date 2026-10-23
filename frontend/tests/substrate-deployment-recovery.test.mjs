import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { webcrypto } from 'node:crypto'
import ts from 'typescript'

function compile(source) {
  return ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
}
function mount(storage = new Map(), subject = 'user-a', agentId = 7) {
  const helpers = new Function(compile(readFileSync(new URL('../src/api/substrateDeploy.ts', import.meta.url), 'utf8').replace(/export /g, '')) + ';return {defaultDeployForm,buildDeployPayload,validateDeployForm}')()
  const source = readFileSync(process.env.EAP_PANEL_SOURCE || new URL('../src/components/substrate/SubstrateDeployPanel.vue', import.meta.url), 'utf8')
    .split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import[\s\S]*?from ['"][^'"]+['"]\s*$/gm, '')
  const calls = [], queries = [], watchers = [], notices = []
  const permission = { state: { user: { subject, capabilities: {} } } }
  const session = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  let unmount, deploy = async () => { throw { response: { status: 502 } } }, lookup = async () => ({ status: 'NOT_FOUND' })
  const api = {
    instances: async () => [], deployments: async () => [],
    deploy: async (...args) => { calls.push(args); return deploy(...args) },
    deploymentReceipt: async (...args) => { queries.push(args); return { requestKey: args[1], agentId: args[0], ...await lookup(...args) } },
  }
  const values = { ...helpers, globalThis: { sessionStorage: session, crypto: webcrypto }, crypto: webcrypto,
    ref: value => ({ value }), reactive: value => value, computed: fn => ({ get value() { return fn() } }),
    watch: (source, fn) => watchers.push(fn), onMounted: () => {}, onBeforeUnmount: fn => { unmount = fn },
    defineProps: () => ({ agentId }), usePermissions: () => permission, substrateActions: () => ({ deploy: !!permission.state.user?.capabilities && permission.state.user.capabilities.deploy !== false }),
    deploymentProgress: () => ({ label: '已受理' }), actorStateLabel: () => ({}), ACTIVE_DEPLOYMENT_STATUSES: [],
    ElMessage: { warning: msg => notices.push(msg), success: msg => notices.push(msg) }, ElMessageBox: {}, SubstrateApi: api,
  }
  const names = 'submit,form,props,deployments,submitting'
  const added = process.env.EAP_PANEL_SOURCE ? '' : ',pendingDeployment,deployNotice,queryDeploymentReceipt,restoreDeployment'
  const state = new Function(...Object.keys(values), compile(source) + `;return {${names}${added}}`)(...Object.values(values))
  state.form.systemPrompt = 'private prompt'
  return { ...state, storage, session, calls, queries, notices, watchers, permission, unmount: () => unmount(), setDeploy: fn => { deploy = fn }, setLookup: fn => { lookup = fn } }
}
const stored = x => JSON.parse([...x.storage.values()][0])
const found = { deploymentKey: 'dep-one', version: 1, status: 'QUEUED' }
const tick = () => new Promise(resolve => setImmediate(resolve))

test('502 retry preserves the original deployment key', async () => {
  const x = mount(); await x.submit(); await x.submit()
  assert.equal(x.calls.length, 2)
  assert.equal(x.calls[1][1].requestKey, x.calls[0][1].requestKey)
})

test('refresh and tab remount recover metadata only and query without any POST', async () => {
  const x = mount(); await x.submit(); const p = stored(x)
  assert.deepEqual(Object.keys(p).sort(), ['digest', 'requestKey'])
  assert.match(p.digest, /^[a-f0-9]{64}$/); assert.equal(JSON.stringify([...x.storage]).includes('private prompt'), false)
  x.unmount(); const y = mount(x.storage); assert.equal(y.pendingDeployment.value.requestKey, p.requestKey)
  y.setLookup(async () => found); await y.queryDeploymentReceipt()
  assert.deepEqual(y.queries, [[7, p.requestKey]]); assert.equal(y.calls.length, 0); assert.equal(x.storage.size, 0)
  assert.match(y.deployNotice.value, /v1/)
})

for (const failure of [new Error('network'), { response: { status: 504 } }, { response: { status: 400 } }]) test(`failed response retains key (${failure.response?.status || 'network'})`, async () => {
  const x = mount(); x.setDeploy(async () => { throw failure }); await x.submit()
  const key = stored(x).requestKey; await x.submit(); assert.equal(x.calls[1][1].requestKey, key)
})

test('changed payload cannot reuse or replace a pending intent', async () => {
  const x = mount(); await x.submit(); const p = stored(x)
  x.form.systemPrompt = 'different'; await x.submit()
  assert.equal(x.calls.length, 1); assert.deepEqual(stored(x), p); assert.match(x.deployNotice.value, /配置/)
})

test('NOT_FOUND and failed query retain original identity and require same configuration', async () => {
  const x = mount(); await x.submit(); const p = stored(x)
  await x.queryDeploymentReceipt(); assert.deepEqual(stored(x), p)
  x.setLookup(async () => { throw new Error('lookup lost') }); await x.queryDeploymentReceipt()
  assert.deepEqual(stored(x), p); assert.equal(x.calls.length, 1)
  const y = mount(x.storage); await y.submit(); assert.equal(y.calls[0][1].requestKey, p.requestKey)
})

for (const event of ['identity', 'agent', 'unmount']) test(`late submission cannot clear index after ${event} switch`, async () => {
  const x = mount(); let resolve; x.setDeploy(() => new Promise(r => { resolve = r }))
  const promise = x.submit(); while (!resolve) await tick(); const p = stored(x)
  if (event === 'identity') { x.permission.state.user = { subject: 'user-b', capabilities: {} }; x.watchers[0]() }
  else if (event === 'agent') { x.props.agentId = 8; x.watchers[1]() }
  else x.unmount()
  resolve({ agentId: 7, ...found }); await promise
  assert.deepEqual(stored(x), p); assert.equal(x.notices.length, 0); assert.equal(x.deployments.value.length, 0)
})

test('identity namespaces and capability loss prevent cross-account recovery and writes', async () => {
  const a = mount(); await a.submit(); const key = stored(a).requestKey
  const b = mount(a.storage, 'user-b'); assert.equal(b.pendingDeployment.value, undefined)
  b.permission.state.user.capabilities.deploy = false; await b.submit(); await b.queryDeploymentReceipt()
  assert.equal(b.calls.length, 0); assert.equal(b.queries.length, 0)
  const again = mount(a.storage); assert.equal(again.pendingDeployment.value.requestKey, key)
})

for (const operation of ['read', 'write', 'malformed']) test(`storage ${operation} failure prevents deployment`, async () => {
  const x = mount()
  if (operation === 'read') x.session.getItem = () => { throw new Error('denied') }
  if (operation === 'write') x.session.setItem = () => { throw new Error('quota') }
  if (operation === 'malformed') x.storage.set('eap.deploy.pending.' + JSON.stringify(['user-a', 7]), '{bad')
  await x.submit(); assert.equal(x.calls.length, 0); assert.match(x.deployNotice.value, /存储|编号/)
})

test('query key or agent mismatch cannot erase original pending identity', async () => {
  const x = mount(); await x.submit(); const p = stored(x)
  x.setLookup(async () => ({ ...found, requestKey: 'wrong', agentId: 8 })); await x.queryDeploymentReceipt()
  assert.deepEqual(stored(x), p)
})

test('late query after switching agent leaves original pending entry intact', async () => {
  const x = mount(); await x.submit(); const p = stored(x); let resolve
  x.setLookup(() => new Promise(r => { resolve = r })); const q = x.queryDeploymentReceipt()
  x.props.agentId = 8; x.watchers[1](); resolve(found); await q
  assert.deepEqual(stored(x), p); assert.equal(x.deployNotice.value.includes('v1'), false)
})

test('double click during hash or POST creates only one submission', async () => {
  const x = mount(); await Promise.all([x.submit(), x.submit()]); assert.equal(x.calls.length, 1)
})

test('successful POST clears index only after matched receipt and does not resubmit', async () => {
  const x = mount(); x.setDeploy(async agentId => ({ agentId, ...found })); await x.submit()
  assert.equal(x.calls.length, 1); assert.equal(x.storage.size, 0); assert.match(x.deployNotice.value, /v1/)
})
