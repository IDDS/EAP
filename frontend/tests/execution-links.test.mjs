import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'

function client(read) {
  const source = fs.readFileSync(new URL('../src/api/executionLinks.ts', import.meta.url), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  new Function('exports', 'require', code)(exports, name => name === './http' ? { getData: read } : { executionStreamPattern: /^[a-f0-9]{64}$/ })
  return exports
}
const stream = 'a'.repeat(64), other = 'b'.repeat(64), id = '73c68d21-37ac-452c-805b-294221027dda'
const row = () => ({ id: 1, linkId: id, relation: 'DELEGATE', sourceStream: stream, sourceRun: id, sourceAgentId: 7, sourceRuntimeKey: 'runtime/source', targetStream: other, targetRun: id,
  targetAgentId: null, targetRuntimeKey: null, artifactDigest: null, artifactSize: null, artifactLanguage: null, offeredMs: 1000, confirmedMs: null,
  state: 'PENDING_CONFIRMATION', evidenceKind: 'SOURCE_PRODUCER_REPORT', executionPermissionGranted: false })
const page = (items = [row()]) => ({ items, hasMore: false, nextAfterId: items.at(-1)?.id ?? 0 })
test('cross-instance link reads use bounded GET and propagate cancellation', async () => {
  const calls = [], signal = new AbortController().signal, api = client(async (...args) => { calls.push(args); return page() })
  assert.equal((await api.getExecutionLinks(stream, 0, signal)).items[0].state, 'PENDING_CONFIRMATION')
  assert.equal(calls[0][0], `/observability/agent-executions/${stream}/links?afterId=0&limit=50`); assert.equal(calls[0][1].signal, signal)
})
test('pending claims cannot masquerade as confirmed identities or permission grants', async () => {
  for (const patch of [{ targetAgentId: 8 }, { state: 'CONFIRMED' }, { evidenceKind: 'TWO_AUTHENTICATED_PRODUCER_REPORTS' }, { executionPermissionGranted: true }, { confirmedMs: 2000 }]) {
    await assert.rejects(client(async () => page([{ ...row(), ...patch }])).getExecutionLinks(stream))
  }
})
test('confirmed code handoff validates both runtime identities and original artifact metadata', async () => {
  const confirmed = { ...row(), state: 'CONFIRMED', targetAgentId: 8, targetRuntimeKey: 'runtime/target', confirmedMs: 2000, evidenceKind: 'TWO_AUTHENTICATED_PRODUCER_REPORTS', relation: 'CODE_HANDOFF', artifactDigest: 'c'.repeat(64), artifactSize: 14, artifactLanguage: 'PYTHON' }
  assert.equal((await client(async () => page([confirmed])).getExecutionLinks(other)).items[0].artifactSize, 14)
  for (const patch of [{ artifactSize: 1048577 }, { artifactSize: -1 }, { artifactDigest: null }, { artifactLanguage: 'UNKNOWN' }, { targetRuntimeKey: 'runtime/source' }, { confirmedMs: null }]) {
    await assert.rejects(client(async () => page([{ ...confirmed, ...patch }])).getExecutionLinks(stream))
  }
})
test('wrong stream, duplicate rows and nonadvancing pages cannot appear as a valid graph', async () => {
  for (const value of [page([{ ...row(), sourceStream: 'c'.repeat(64) }]), page([row(), row()]), { ...page(), nextAfterId: 0 }, { ...page([]), hasMore: true }, page([{ ...row(), sourceRun: 'bad' }])]) {
    await assert.rejects(client(async () => value).getExecutionLinks(stream))
  }
  assert.deepEqual((await client(async () => ({ ...page([]), nextAfterId: 9 })).getExecutionLinks(stream, 9)).items, [])
})
test('invalid link queries fail before transport', async () => {
  let calls = 0; const api = client(async () => { calls++; return page() })
  await assert.rejects(api.getExecutionLinks('../escape')); await assert.rejects(api.getExecutionLinks(stream, -1)); assert.equal(calls, 0)
})
