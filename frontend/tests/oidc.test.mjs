import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

const source = readFileSync(new URL('../src/api/oidc.ts', import.meta.url), 'utf8')
const config = { issuer: 'https://idp.example/realm/eap', clientId: 'console', scopes: 'openid profile' }
const metadata = { issuer: config.issuer, authorization_endpoint: 'https://idp.example/authorize',
  token_endpoint: 'https://idp.example/token', code_challenge_methods_supported: ['S256'] }
const origin = 'https://console.example'

function fixture(fetchImpl = async () => ({ ok: true, json: async () => metadata })) {
  const items = new Map()
  let destination
  const scope = vm.createContext({ exports: {}, crypto: webcrypto, TextEncoder, URL, URLSearchParams, Uint8Array, btoa,
    sessionStorage: { getItem: key => items.get(key) ?? null, setItem: (key, value) => items.set(key, value), removeItem: key => items.delete(key) },
    window: { location: { origin, assign: value => { destination = value } } }, fetch: fetchImpl })
  vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }), scope)
  return { ...scope.exports, items, destination: () => new URL(destination) }
}

const transaction = (overrides = {}) => ({ issuer: config.issuer, clientId: config.clientId, state: 's'.repeat(43),
  verifier: 'v'.repeat(43), redirectUri: `${origin}/auth/callback`, returnTo: '/agents/7?tab=deploy#instances',
  createdAt: 1000000, ...overrides })
const callback = `?code=code-123&state=${'s'.repeat(43)}`

function store(f, value) { f.items.set('eap.oidc.transaction', JSON.stringify(value)) }

test('S256 challenge matches RFC 7636 and random verifier/state use 256 bits of entropy', async () => {
  const f = fixture()
  assert.equal(await f.pkceChallenge('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk'), 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM')
  const first = f.randomPkceValue()
  assert.match(first, /^[A-Za-z0-9_-]{43}$/)
  assert.notEqual(first, f.randomPkceValue())
  assert.equal(f.base64Url(new Uint8Array([251, 255])), '-_8')
})

test('authorization request uses discovery, fresh state, S256 and the same callback URI', async () => {
  const f = fixture()
  await f.startOidcSignIn(config, '/agents/7?tab=deploy')
  const url = f.destination()
  const stored = JSON.parse(f.items.get('eap.oidc.transaction'))
  assert.equal(url.origin + url.pathname, metadata.authorization_endpoint)
  assert.equal(url.searchParams.get('response_type'), 'code')
  assert.equal(url.searchParams.get('client_id'), config.clientId)
  assert.equal(url.searchParams.get('redirect_uri'), stored.redirectUri)
  assert.equal(url.searchParams.get('scope'), 'openid profile')
  assert.equal(url.searchParams.get('state'), stored.state)
  assert.notEqual(stored.state, stored.verifier)
  assert.equal(url.searchParams.get('code_challenge_method'), 'S256')
  assert.equal(url.searchParams.get('code_challenge'), await f.pkceChallenge(stored.verifier))
  assert.equal(url.searchParams.has('code_verifier'), false)
})

test('callback is bound to state, issuer, client, origin and expires after ten minutes', () => {
  const f = fixture()
  for (const [stored, query] of [
    [transaction(), '?code=x&state=wrong'],
    [transaction(), `${callback}&state=${'s'.repeat(43)}`],
    [transaction({ issuer: 'https://other.example' }), callback],
    [transaction({ clientId: 'other' }), callback],
    [transaction({ redirectUri: 'https://other.example/auth/callback' }), callback],
    [transaction({ createdAt: 1 }), callback],
    [transaction({ createdAt: 2000000 }), callback],
    [transaction({ verifier: '' }), callback],
    [transaction(), `${callback}&iss=https://other.example`],
    [transaction(), `${callback}&code=second`],
    [transaction(), `?state=${'s'.repeat(43)}`],
    [transaction(), `${callback}&error=access_denied`],
  ]) {
    store(f, stored)
    assert.throws(() => f.consumeOidcCallback(query, config, origin, 1000001))
    assert.equal(f.items.size, 0)
  }
  store(f, transaction())
  assert.equal(f.consumeOidcCallback(callback, config, origin, 1000001).code, 'code-123')
  assert.throws(() => f.consumeOidcCallback(callback, config, origin, 1000001), /已使用/)
})

test('return path stays within the console and cannot loop through the callback', () => {
  const f = fixture()
  for (const value of ['https://evil.example', '//evil.example', '/\\evil.example', '/\nevil.example', '/auth/callback?code=x', '/auth/../auth/callback']) {
    assert.equal(f.safeReturnPath(value), '/')
  }
  assert.equal(f.safeReturnPath('/agents/7?tab=deploy#instances'), '/agents/7?tab=deploy#instances')
})

test('code exchange sends PKCE verifier without cookies and returns the opaque access token', async () => {
  const calls = []
  const f = fixture(async (url, options) => {
    calls.push({ url, options })
    return { ok: true, json: async () => url === metadata.token_endpoint
      ? { access_token: 'opaque-access-token', token_type: 'Bearer', id_token: 'unused', refresh_token: 'unused' } : metadata }
  })
  store(f, transaction({ createdAt: Date.now() }))
  const result = await f.finishOidcSignIn(config, callback)
  assert.equal(result.accessToken, 'opaque-access-token')
  assert.equal(result.returnTo, '/agents/7?tab=deploy#instances')
  const exchange = calls.find(call => call.url === metadata.token_endpoint)
  assert.equal(exchange.options.method, 'POST')
  assert.equal(exchange.options.credentials, 'omit')
  assert.equal(exchange.options.redirect, 'error')
  assert.equal(exchange.options.body.get('code_verifier'), 'v'.repeat(43))
  assert.equal(exchange.options.body.get('redirect_uri'), `${origin}/auth/callback`)
  assert.equal(exchange.options.body.get('grant_type'), 'authorization_code')
  assert.equal(exchange.options.body.has('client_secret'), false)
  assert.equal(f.items.size, 0)
})

test('discovery rejects mismatched issuer, unsafe endpoints and missing S256 support', async () => {
  for (const overrides of [{ issuer: 'https://wrong.example' }, { token_endpoint: 'http://remote.example/token' },
    { authorization_endpoint: 'javascript:alert(1)' }, { code_challenge_methods_supported: ['plain'] }]) {
    const f = fixture(async () => ({ ok: true, json: async () => ({ ...metadata, ...overrides }) }))
    await assert.rejects(f.startOidcSignIn(config, '/'))
    assert.equal(f.items.size, 0)
  }
})

test('failed exchange and malformed tokens fail closed and consume the transaction', async () => {
  for (const token of [null, {}, { access_token: '', token_type: 'Bearer' }, { access_token: 'token', token_type: 'MAC' }]) {
    const f = fixture(async url => ({ ok: url !== metadata.token_endpoint || token !== null,
      json: async () => url === metadata.token_endpoint ? token : metadata }))
    store(f, transaction({ createdAt: Date.now() }))
    await assert.rejects(f.finishOidcSignIn(config, callback))
    assert.equal(f.items.size, 0)
  }
})
