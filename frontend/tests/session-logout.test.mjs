import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

function load(source, globals) {
  const context = vm.createContext({ exports: {}, ...globals })
  vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }), context)
  return context.exports
}

test('logout sends the captured token and distinguishes unconfirmed server revocation', async () => {
  let request
  let status = 200
  const source = readFileSync(new URL('../src/api/sessionLogout.ts', import.meta.url), 'utf8')
  const api = load(source, {
    AbortController,
    window: { setTimeout, clearTimeout },
    fetch: async (url, options) => { request = { url, options }; if (status === 0) throw Error('offline'); return { ok: status === 200, status, json: async () => ({ success: true, data: { revoked: true } }) } },
  })
  assert.equal(await api.revokeSessionToken('original'), true)
  assert.equal(request.url, '/api/platform/session/logout')
  assert.equal(request.options.headers.Authorization, 'Bearer original')
  for (status of [401, 500, 0]) assert.equal(await api.revokeSessionToken('original'), false)
})

function componentLogout(revoke) {
  let token = 'original', reloaded = 0
  const storage = new Map()
  const component = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
  const source = component.match(/async function logout\(\) \{[\s\S]*?\n\}\n\nfunction requireAuthentication/)[0]
    .replace(/\n\nfunction requireAuthentication$/, '')
  const { logout } = load(source + '\nexport { logout }', {
    signingOut: { value: false },
    getAccessToken: () => token,
    clearAccessToken: () => { token = '' },
    revokeSessionToken: revoke,
    sessionStorage: { setItem: (key, value) => storage.set(key, value) },
    window: { location: { reload: () => { reloaded++ } }, alert: () => { throw Error('unexpected alert') } },
  })
  return { logout, replace: value => { token = value }, token: () => token, storage, reloaded: () => reloaded }
}

test('an unconfirmed revocation clears local identity and leaves a visible warning marker', async () => {
  const state = componentLogout(async () => false)
  await state.logout()
  assert.equal(state.token(), '')
  assert.equal(state.storage.get('eap.logout-revocation-unconfirmed'), '1')
  assert.equal(state.reloaded(), 1)
})

test('a late logout response cannot clear or reload a replacement session', async () => {
  let finish
  const state = componentLogout(() => new Promise(resolve => { finish = resolve }))
  const pending = state.logout()
  state.replace('replacement')
  finish(true)
  await pending
  assert.equal(state.token(), 'replacement')
  assert.equal(state.reloaded(), 0)
})
