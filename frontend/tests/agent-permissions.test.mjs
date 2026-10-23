import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

function handler(file, name, globals) {
  const source = readFileSync(new URL(`../src/views/${file}`, import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const declaration = ast.statements.find(node => ts.isFunctionDeclaration(node) && node.name.text === name)
  const context = vm.createContext(globals)
  vm.runInContext(ts.transpile(declaration.getText(ast), { target: ts.ScriptTarget.ES2022 }), context)
  return context[name]
}

test('Agent registration denies missing or revoked capability before sending a write', async () => {
  let allowed = false
  const writes = []
  const form = { code: 'TEST', name: 'test', ownerDepartment:'研发', ownerUser:'负责人', category:'助手' }
  const destinations=[]
  const create = handler('AgentsView.vue', 'createAgent', {
    permissions: { can: capability => { assert.equal(capability, 'deployAgents'); return allowed } },
    form, creating: { value: false }, dialogVisible: { value: true },
    EapApi: { createAgent: async payload => { writes.push(payload); return {id:55} } },
    router:{push:async target=>destinations.push(target)},
    ElMessage: { success() {}, warning() {} }, load: async () => {},
  })
  await create()
  assert.equal(writes.length, 0)
  allowed = true
  await create()
  assert.equal(writes.length, 1)
  assert.equal(destinations[0].path,'/agents/55')
  assert.equal(destinations[0].query.tab,'deploy')
  Object.assign(form, { code: 'SECOND', name: 'second' })
  allowed = false // e.g. logout/401 while an already-open dialog has valid input
  await create()
  assert.equal(writes.length, 1)
})

test('Agent lifecycle changes use the latest server capability for each invocation', async () => {
  let allowed = false
  const writes = []
  const change = handler('AgentDetailView.vue', 'changeStatus', {
    permissions: { can: capability => { assert.equal(capability, 'deployAgents'); return allowed } },
    agent: { value: { id: 5 } },
    EapApi: { updateAgentStatus: async (...args) => writes.push(args) },
    ElMessage: { success() {} }, load: async () => {},
  })
  await change('PAUSED')
  assert.equal(writes.length, 0)
  allowed = true
  await change('PAUSED')
  assert.deepEqual(writes, [[5, 'PAUSED']])
  allowed = false
  await change('ACTIVE')
  assert.equal(writes.length, 1)
})


test('initial auth check waits for lazy routing so callback recovery is not covered by a login dialog', async () => {
  for (const path of ['/auth/callback', '/agents']) {
    let ready
    const routing = new Promise(resolve => { ready = resolve })
    const route = { path: '/' }
    let statusReads = 0
    let authDialogs = 0
    const load = handler('../App.vue', 'loadPlatformStatus', {
      router: { isReady: () => routing }, route,
      platformStatusLoading: { value: false }, platformStatus: { value: null },
      EapApi: { getPlatformStatus: async () => { statusReads++; return { securityMode: 'JWT' } } },
      getAccessToken: () => '', loadCurrentUser: async () => {},
      requireAuthentication: () => { authDialogs++ },
    })
    const pending = load()
    assert.equal(statusReads, 0)
    route.path = path
    ready()
    await pending
    assert.equal(statusReads, 1)
    assert.equal(authDialogs, path === '/auth/callback' ? 0 : 1)
  }
})


test('workflow writes and drag handlers deny missing capability before reading mutable state', async () => {
  for (const name of ['executeWorkflow','onPaletteDrag','onSystemDrag','onCanvasDrop','startNodeMove','onNodeMove','stopNodeMove','runWorkflow','saveSelectedNode','createWorkflow']) {
    let calls = 0
    const fn = handler('WorkflowBuilderView.vue', name, {
      permissions: { can: capability => { calls++; assert.equal(capability, 'runWorkflows'); return false } },
    })
    await fn({}, {})
    assert.equal(calls, 1, name)
  }
})

test('read-only workflow node selection never creates an edge', async () => {
  const selectedNode = { value: null }, pendingSourceKey = { value: 'old' }
  const select = handler('WorkflowBuilderView.vue', 'selectOrConnect', {
    permissions: { can: () => false }, selectedNode, pendingSourceKey,
  })
  const node = { nodeKey: 'inspect' }
  await select(node)
  assert.equal(selectedNode.value, node)
  assert.equal(pendingSourceKey.value, undefined)
})

test('physical content APIs are never requested without viewContent', async () => {
  for (const name of ['fetchContentObservations','fetchContentHistory']) {
    let requests = 0
    const fn = handler('PhysicalView.vue', name, {
      permissions: { state: { user: {} }, can: () => false }, loadVersion: 1,
      EapApi: new Proxy({}, { get: () => () => { requests++; throw new Error('must not request') } }),
    })
    const result = await fn({})
    assert.equal(requests, 0)
    assert.equal(JSON.stringify(result), name === 'fetchContentHistory' ? '{"items":[],"totalElements":0}' : '[]')
  }
})

test('physical content failure is isolated and revoked access drops late content', async () => {
  const user = {}, state = { user }, contentError = { value: '' }
  let allowed = true, resolve
  const pending = new Promise(done => { resolve = done })
  const globals = {
    loadVersion: 1, permissions: { state, can: () => allowed }, contentError,
    contextAgentId: {value:0}, contextEndpointKey: {value:''}, contextTraceId: {value:''},
    EapApi: { listContentObservations: () => pending },
  }
  const fetch = handler('PhysicalView.vue','fetchContentObservations', globals)
  const request = fetch()
  allowed = false; state.user = null
  resolve([{ contentJson: 'must not reappear' }])
  assert.equal(JSON.stringify(await request), '[]')
  allowed = true; state.user = user
  globals.EapApi.listContentObservations = async () => { throw new Error('unavailable') }
  assert.equal(JSON.stringify(await fetch()), '[]')
  assert.match(contentError.value, /普通遥测不受影响/)
})

