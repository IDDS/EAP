import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

const source = readFileSync(new URL('../src/api/workflowSubmission.ts', import.meta.url), 'utf8')
function client(storage) {
  const scope = vm.createContext({ exports: {}, sessionStorage: storage })
  vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }), scope)
  return scope.exports.submitWorkflowOnce
}
function storage() {
  const values = new Map()
  return { getItem: key => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
}
const payload = { objective: 'test', context: { severity: 'high', approved: false } }

test('uncertain submission retries after reload use the same key, confirmed new runs use a new key', async () => {
  const saved = storage()
  let firstKey
  await assert.rejects(client(saved)(1, payload, async request => { firstKey = request.requestKey; throw new Error('timeout') }), /timeout/)
  const submit = client(saved)
  const result = await submit(1, payload, async request => {
    assert.equal(request.requestKey, firstKey)
    return { runKey: 'original' }
  })
  assert.equal(result.runKey, 'original')
  await submit(1, payload, async request => assert.notEqual(request.requestKey, firstKey))
})

test('changed inputs and workflows do not reuse another submissions identity', async () => {
  const submit = client(storage())
  const keys = []
  for (const [id, body] of [[1, payload], [2, payload], [1, { objective: 'changed' }]]) {
    await assert.rejects(submit(id, body, async request => { keys.push(request.requestKey); throw new Error('timeout') }))
  }
  assert.equal(new Set(keys).size, 3)
  await submit(1, payload, async request => assert.equal(request.requestKey, keys[0]))
})

test('disabled browser storage still preserves retries within the current page', async () => {
  const submit = client({ getItem() { throw new Error('blocked') }, setItem() { throw new Error('blocked') }, removeItem() { throw new Error('blocked') } })
  let firstKey
  await assert.rejects(submit(1, payload, async request => { firstKey = request.requestKey; throw new Error('offline') }))
  await submit(1, payload, async request => assert.equal(request.requestKey, firstKey))
})
