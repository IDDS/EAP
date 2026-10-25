import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import ts from 'typescript'

function client(read) {
  const source = fs.readFileSync(new URL('../src/api/executionDeliveries.ts', import.meta.url), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText
  const exports = {}
  new Function('exports', 'require', code)(exports, name => {
    if (name === './http') return { getData: read }
    if (name === './agentExecutions') return { executionStreamPattern: /^[a-f0-9]{64}$/ }
    throw Error('Unexpected import')
  })
  return exports
}
const stream = 'a'.repeat(64)
const row = () => ({ id: 1, agentId: 7, runtimeInstanceKey: 'eap-agents/one', streamKey: stream, framework: 'langgraph', frameworkVersion: '1.2.17',
  reportSequence: 2, total: 9, acknowledged: 0, pending: 9, attempts: 3, state: 'RETRY_EXHAUSTED', reportedAt: '2026-10-25T01:00:00Z', stale: false, receivedEvents: 9, credentialState: 'AVAILABLE' })
const page = (items = [row()]) => ({ items, hasMore: false, nextAfterId: items.at(-1)?.id ?? 0, asOf: '2026-10-25T01:01:00Z', evidenceKind: 'AUTHENTICATED_PRODUCER_SNAPSHOT' })

test('delivery reads keep bounded GET filters, cancellation and snapshot provenance', async () => {
  const calls = [], signal = new AbortController().signal, api = client(async (...args) => { calls.push(args); return page() })
  const result = await api.getDeliveryPage({ agentId: 7, streamKey: stream, afterId: 0 }, signal)
  assert.equal(calls[0][0], `/observability/agent-executions/deliveries?afterId=0&limit=50&agentId=7&streamKey=${stream}`)
  assert.equal(calls[0][1].signal, signal); assert.equal(result.items[0].pending, 9)
  assert.match(api.deliveryAdvice(result.items[0]), /客户端仍待确认/)
})
test('bad identities, counters, state combinations and pagination cannot look like valid status', async () => {
  const invalid = [page([{ ...row(), agentId: 8 }]), page([{ ...row(), streamKey: 'b'.repeat(64) }]), page([row(),row()]),
    page([{ ...row(), acknowledged: 1 }]), page([{ ...row(), pending: -1 }]), page([{ ...row(), attempts: 0.5 }]),
    page([{ ...row(), state: 'ACKNOWLEDGED' }]), page([{ ...row(), state: 'RUNNING' }]), page([{ ...row(), reportSequence: 0 }]),
    page([{ ...row(), total: 5000, pending: 5000 }]), page([{ ...row(), reportedAt: 'bad' }]), page([{ ...row(), stale: 'false' }]),
    page([{ ...row(), credentialState: 'RENEWED_AUTOMATICALLY' }]), { ...page(), evidenceKind: 'LIVE_QUEUE' },
    { ...page(), nextAfterId: 0 }, { ...page([]), hasMore: true }]
  for (const data of invalid) await assert.rejects(client(async () => data).getDeliveryPage({ agentId: 7, streamKey: stream, afterId: 0 }))
})
test('empty later pages preserve the cursor and do not fabricate zero pending reports', async () => {
  const empty = { ...page([]), nextAfterId: 12 }
  const result = await client(async () => empty).getDeliveryPage({ afterId: 12 })
  assert.deepEqual(result.items, []); assert.equal(result.nextAfterId, 12)
})
test('advice preserves identity fences, stale snapshots and unknown future events', () => {
  const api = client()
  assert.match(api.deliveryAdvice({ ...row(), credentialState: 'GENERATION_CHANGED' }), /核对原实例身份/)
  assert.match(api.deliveryAdvice({ ...row(), receivedEvents: 10 }), /报告可能落后/)
  assert.match(api.deliveryAdvice({ ...row(), receivedEvents: 0 }), /本报告不代表后台队列的实时状态/)
  assert.match(api.deliveryAdvice({ ...row(), pending: 0, acknowledged: 9, state: 'ACKNOWLEDGED' }), /不代表后续没有新事件/)
})
