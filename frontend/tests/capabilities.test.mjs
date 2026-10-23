import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

function load(source, globals = {}) {
  const scope = vm.createContext({ exports: {}, ...globals })
  vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }), scope)
  return scope.exports
}
const source = readFileSync(new URL('../src/api/capabilities.ts', import.meta.url), 'utf8')
const helpers = load(source)

test('missing, loading and malformed capabilities never grant writes', () => {
  for (const capabilities of [null, undefined, {}, { deployAgents: false, operateInstances: false },
    { deployAgents: 'true', operateInstances: 1 }, { viewConsole: true }]) {
    assert.equal(Object.values(helpers.substrateActions(capabilities)).some(Boolean), false)
  }
})

test('deployment and instance capabilities independently control their actions', () => {
  for (const deployAgents of [false, true]) {
    for (const operateInstances of [false, true]) {
      const actions = helpers.substrateActions({ deployAgents, operateInstances })
      assert.equal(actions.deploy, deployAgents)
      assert.equal(actions.retry, deployAgents)
      for (const action of ['createInstance', 'control', 'remove', 'chat']) assert.equal(actions[action], operateInstances)
    }
  }
})

test('the permission store drops obsolete responses after logout or token replacement', async () => {
  let token = 'first'
  const requests = []
  const listeners = new Map()
  const storeSource = readFileSync(new URL('../src/stores/permissions.ts', import.meta.url), 'utf8')
  const store = load(storeSource, {
    window: { addEventListener: (name, listener) => listeners.set(name, listener) },
    require: name => {
      if (name === 'vue') return { reactive: value => value, readonly: value => value }
      if (name === '@/api/auth') return { getAccessToken: () => token }
      if (name === '@/api/capabilities') return helpers
      if (name === '@/api/http') return { getData: () => new Promise((resolve, reject) => requests.push({ resolve, reject })) }
      throw new Error(name)
    },
  })
  const permissions = store.usePermissions()
  const first = store.loadCurrentUser()
  assert.equal(store.loadCurrentUser(), first)
  assert.equal(permissions.can('deployAgents'), false)
  token = 'second'
  listeners.get('eap-auth-changed')()
  const second = store.loadCurrentUser()
  requests[1].resolve({ capabilities: { deployAgents: false, operateInstances: true } })
  await second
  requests[0].resolve({ capabilities: { deployAgents: true } })
  assert.equal(await first, null)
  assert.equal(permissions.can('deployAgents'), false)
  assert.equal(permissions.can('operateInstances'), true)
  token = ''
  listeners.get('eap-auth-changed')()
  assert.equal(permissions.can('operateInstances'), false)
  const failed = store.loadCurrentUser()
  requests[2].reject(new Error('unauthorized'))
  await assert.rejects(failed)
  assert.equal(permissions.state.loading, false)
  assert.equal(permissions.state.user, null)
})

test('Substrate write handlers also deny actions when invoked without a permitted button', async () => {
  const component = readFileSync(new URL('../src/components/substrate/SubstrateDeployPanel.vue', import.meta.url), 'utf8')
  const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const writes = []
  const api = new Proxy({}, { get: (_, key) => () => { writes.push(key); throw new Error(`unexpected write ${String(key)}`) } })
  const exports = load(script + '\nexport { submit, retry, createInstance, control, remove, openChat, sendChat }', {
    defineProps: () => ({ agentId: 7 }),
    require: name => {
      if (name === 'vue') return { computed: fn => ({ get value() { return fn() } }), ref: value => ({ value }), reactive: value => value,
        onMounted: () => {}, onBeforeUnmount: () => {}, watch: () => {} }
      if (name === 'element-plus') return { ElMessage: api, ElMessageBox: api }
      if (name === '@/api/substrate') return { SubstrateApi: api }
      if (name === '@/api/substrateDeploy') return { defaultDeployForm: () => ({}) }
      if (name === '@/api/capabilities') return helpers
      if (name === '@/stores/permissions') return { usePermissions: () => ({ state: { user: null } }) }
      throw new Error(name)
    },
  })
  for (const handler of Object.values(exports)) await handler({ name: 'actor', version: 1 }, 'PAUSE')
  assert.deepEqual(writes, [])
})

test('401 clears current permissions and offers sign-in even for silent requests, but ignores obsolete tokens', async () => {
  let token = 'current'
  let requestInterceptor
  let rejectResponse
  const events = []
  const httpSource = readFileSync(new URL('../src/api/http.ts', import.meta.url), 'utf8')
  load(httpSource, {
    Event,
    window: { dispatchEvent: event => events.push(event.type) },
    require: name => {
      if (name === 'axios') return { default: { create: () => ({ interceptors: {
        request: { use: callback => { requestInterceptor = callback } },
        response: { use: (_, callback) => { rejectResponse = callback } },
      } }) } }
      if (name === 'element-plus') return { ElMessage: { error: () => {} } }
      if (name === '@/api/auth') return { getAccessToken: () => token, clearAccessToken: () => { token = ''; events.push('cleared') } }
      throw new Error(name)
    },
  })
  assert.equal(requestInterceptor({ url: '/platform/status', headers: {} }).headers.Authorization, undefined)
  assert.equal(requestInterceptor({ url: '/platform/me', headers: {} }).headers.Authorization, 'Bearer current')
  const error = authorization => ({ response: { status: 401 }, config: { silentError: true, headers: { Authorization: authorization } } })
  await assert.rejects(rejectResponse(error('Bearer old')))
  assert.equal(token, 'current')
  assert.deepEqual(events, [])
  await assert.rejects(rejectResponse(error('Bearer current')))
  assert.equal(token, '')
  assert.deepEqual(events, ['cleared', 'eap-auth-required'])
})
