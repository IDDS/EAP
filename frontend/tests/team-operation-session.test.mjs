import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const raw = readFileSync(new URL('../src/api/teamOperationSession.ts', import.meta.url), 'utf8')
function module() { const exports = {}; vm.runInNewContext(ts.transpile(raw, { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }), { exports }); return exports }
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function intent(action = 'CREATE') { return { action, requestKey: 'original-key', body: { requestKey: 'original-key', private: 'original prompt' }, summary: ['private preview'], ...(action === 'CREATE' ? {} : { rootId: 2 }), ...(action === 'EXECUTE' || action === 'SCORE' ? { evaluationId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa' } : {}) } }
function scope(overrides = {}) {
  const m = module(), state = m.emptyOperationState(), writes = [], reads = []
  let user = {}, allowed = true, fresh = true
  const deps = { user: () => user, allowed: () => allowed, fresh: () => fresh, invalidate: () => { fresh = false }, send: async i => { writes.push(i); return { id: 3 } }, receipt: async i => { reads.push(i); return { action: i.action, requestKey: i.requestKey, rootId: i.rootId ?? 3, evaluationId: i.evaluationId ?? null, state: 'FOUND', result: { id: 3 } } }, ...overrides }
  const session = m.createOperationSession(state, deps)
  return { state, session, writes, reads, setUser: () => { user = {} }, revoke: () => { allowed = false }, fresh: value => { fresh = value } }
}
test('review snapshots the request and confirmation submits once despite repeated clicks', async () => {
  const wait = deferred(), sent = []; const s = scope({ send: i => { sent.push(i); return wait.promise } }), draft = intent()
  assert.equal(s.session.prepare(draft), true); draft.body.private = 'later edit'; await s.session.reconcile(); assert.equal(sent.length, 0)
  const pending = s.session.confirm(); await s.session.confirm(); assert.equal(sent.length, 1); assert.equal(sent[0].body.private, 'original prompt')
  wait.resolve({ id: 3 }); await pending; assert.equal(s.state.phase, 'FOUND'); assert.equal(s.state.rootToRead, 3); assert.deepEqual(Object.keys(s.state.intent.body), []); assert.equal(s.state.intent.summary.length, 0)
})
test('cancelled reviews and revoked or stale conditions cannot send', async () => {
  for (const action of [s => s.session.cancel(), s => s.revoke(), s => s.setUser(), s => s.fresh(false)]) { const s = scope(); s.session.prepare(intent()); action(s); await s.session.confirm(); assert.equal(s.writes.length, 0) }
})
test('unknown network outcome blocks new intents and NOT_FOUND never becomes failure or a retry', async () => {
  let sent = 0, reads = 0; const s = scope({ send: async () => { sent++; throw Error('timeout') }, receipt: async i => { reads++; return { action: i.action, requestKey: i.requestKey, rootId: null, evaluationId: null, state: 'NOT_FOUND', result: null } } })
  s.session.prepare(intent()); await s.session.confirm(); assert.equal(s.state.phase, 'UNKNOWN'); assert.equal(s.state.intent.body.private, undefined)
  s.fresh(true); assert.equal(s.session.prepare({ ...intent(), requestKey: 'replacement' }), false); await s.session.confirm(); await s.session.reconcile()
  assert.equal(sent, 1); assert.equal(reads, 1); assert.equal(s.state.phase, 'UNKNOWN'); assert.match(s.state.message, /不证明/)
})
test('GET reconciliation preserves RUNNING and UNKNOWN records rather than declaring success', async () => {
  for (const originalState of ['RUNNING', 'UNKNOWN']) {
    let writes = 0; const s = scope({ send: async () => { writes++; throw Error('lost') }, receipt: async i => ({ ...i, rootId: 2, evaluationId: i.evaluationId, state: 'FOUND', result: { state: originalState } }) })
    s.session.prepare(intent('EXECUTE')); await s.session.confirm(); await s.session.reconcile(); assert.equal(s.state.result.state, originalState); assert.equal(writes, 1); assert.equal(s.state.phase, 'FOUND'); assert.match(s.state.message, originalState === 'RUNNING' ? /仍在进行/ : /结果未知/)
  }
})
test('mismatched receipt never replaces the original request result', async () => {
  for (const change of [{ requestKey: 'other' }, { rootId: 9 }, { evaluationId: null }, { action: 'SCORE' }, { state: 'FOUND', result: null }]) {
    const s = scope({ send: async () => { throw Error('lost') }, receipt: async i => ({ action: i.action, requestKey: i.requestKey, rootId: i.rootId, evaluationId: i.evaluationId, state: 'FOUND', result: {}, ...change }) })
    s.session.prepare(intent('EXECUTE')); await s.session.confirm(); await s.session.reconcile(); assert.equal(s.state.phase, 'UNKNOWN'); assert.equal(s.state.result, null); assert.match(s.state.message, /核对失败/)
  }
})
test('execution 4xx remains unknown while transactional validation rejection remains explicit', async () => {
  for (const action of ['EXECUTE', 'BUDGET']) {
    const s = scope({ send: async () => { throw { response: { status: 400, data: { message: 'TEAM_APPROVAL_EXPIRED' } } } } }); s.session.prepare(intent(action)); await s.session.confirm()
    assert.equal(s.state.phase, action === 'EXECUTE' ? 'UNKNOWN' : 'REJECTED'); assert.equal(s.state.result, null)
  }
})
test('untrusted exception text is not rendered or retained as a server reason', async () => {
  const s = scope({ send: async () => { throw { response: { status: 400, data: { message: 'private provider prompt' } } } } }); s.session.prepare(intent()); await s.session.confirm(); assert.doesNotMatch(s.state.message, /private/)
})
test('a durable rejected proposal is shown as rejected and requires fresh facts', async () => {
  const s = scope({ send: async () => ({ disposition: 'REJECTED', reasonCode: 'TEAM_SINGLE_ROLE_CHANGE_REQUIRED' }) }); s.session.prepare(intent('CANDIDATE')); await s.session.confirm(); assert.match(s.state.message, /提案被拒绝/); assert.equal(s.session.prepare(intent('CANDIDATE')), false)
})
test('root navigation clears private request and ignores a late original response', async () => {
  const wait = deferred(), s = scope({ send: () => wait.promise }); s.session.prepare(intent()); const pending = s.session.confirm(); s.session.navigate(); assert.equal(s.state.phase, 'UNKNOWN'); assert.equal(s.state.intent.body.private, undefined)
  wait.resolve({ id: 3, private: 'late' }); await pending; assert.equal(s.state.phase, 'UNKNOWN'); assert.equal(s.state.result, null); await s.session.reconcile(); assert.equal(s.reads.length, 1)
})
test('account change capability loss and unmount discard late write results', async () => {
  for (const change of [s => { s.setUser(); s.session.reset() }, s => { s.revoke(); s.session.reset() }, s => s.session.dispose()]) {
    const wait = deferred(), s = scope({ send: () => wait.promise }); s.session.prepare(intent()); const pending = s.session.confirm(); change(s); wait.resolve({ id: 3 }); await pending; assert.equal(s.state.phase, 'IDLE'); assert.equal(s.state.intent, null); assert.equal(s.state.result, null)
  }
})
test('recovery after reentry uses GET only and does not require a fresh writable context', async () => {
  const s = scope(); s.fresh(false); await s.session.recover(intent('BUDGET')); assert.equal(s.writes.length, 0); assert.equal(s.reads.length, 1); assert.equal(s.state.phase, 'FOUND'); assert.equal(s.reads[0].body.private, undefined)
})
test('late recovery results cannot restore metadata after leaving the account', async () => {
  const wait = deferred(), s = scope({ receipt: () => wait.promise }); const pending = s.session.recover(intent('BUDGET')); s.setUser(); s.session.reset(); wait.resolve({ action: 'BUDGET', requestKey: 'original-key', rootId: 2, evaluationId: null, state: 'FOUND', result: {} }); await pending; assert.equal(s.state.result, null); assert.equal(s.state.intent, null)
})
test('operation API maps all explicit actions and receipt queries without retry or storage', async () => {
  const apiRaw = readFileSync(new URL('../src/api/teamEvolutionOperations.ts', import.meta.url), 'utf8').replace(/^import .*$/gm, '').replace(/^export /gm, ''), calls = []
  const s = vm.createContext({ URLSearchParams, getData: async (...args) => { calls.push(['GET', ...args]); return {} }, http: { post: async (...args) => { calls.push(['POST', ...args]); return { data: { data: {} } } } } })
  vm.runInContext(ts.transpile(apiRaw + '\nthis.api = TeamOperationApi', { target: ts.ScriptTarget.ES2022 }), s)
  for (const action of ['CREATE', 'CANDIDATE', 'ENROLL', 'BUDGET', 'CHECK', 'EXECUTE', 'SCORE', 'FRONTIER', 'HOLDOUT', 'CLOSE']) await s.api.send(intent(action))
  assert.deepEqual(calls.map(c => c[1]), ['/team-evolutions', '/team-evolutions/2/candidates', '/team-evolutions/2/evaluations', '/team-evolutions/2/budget', '/team-evolutions/2/budget/check', '/team-evolutions/2/evaluations/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/execution', '/team-evolutions/2/evaluations/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/score', '/team-evolutions/2/frontier', '/team-evolutions/2/holdout', '/team-evolutions/2/closure'])
  assert.equal(calls[5][3].timeout, 930000); assert.ok(calls.every(c => c[3].silentError)); await s.api.receipt(intent('EXECUTE')); assert.equal(calls.at(-1)[0], 'GET'); assert.match(calls.at(-1)[1], /requestKey=original-key&rootId=2&evaluationId=/)
  assert.throws(() => s.api.receipt(intent('CHECK'))); assert.doesNotMatch(raw + apiRaw, /setInterval|localStorage|sessionStorage|\.roles\.includes/)
})
test('failed receipt refresh clears previously read facts and retains an unknown result', async () => {
  const s = scope({ receipt: async () => { throw Error('503') } }); s.session.prepare(intent('BUDGET')); await s.session.confirm(); assert.equal(s.state.phase, 'FOUND'); await s.session.reconcile(); assert.equal(s.state.result, null); assert.equal(s.state.phase, 'UNKNOWN')
})

test('proposal write errors including 4xx retain the original unknown intent and never resend', async () => {
  const s = scope({ send: async () => { throw { response: { status: 400 } } } }); s.session.prepare(intent('PROPOSE')); await s.session.confirm(); assert.equal(s.state.phase, 'UNKNOWN'); assert.equal(s.session.prepare({ ...intent('PROPOSE'), requestKey: 'new-key' }), false)
})
test('proposal PREPARING and DISPATCHED receipts remain pending rather than success', async () => {
  for (const state of ['PREPARING', 'DISPATCHED']) { const s = scope({ send: async () => ({ state }) }); s.session.prepare(intent('PROPOSE')); await s.session.confirm(); assert.equal(s.state.result.state, state); assert.match(s.state.message, /仍在进行/); assert.equal(Object.keys(s.state.intent.body).length, 0) }
})
test('proposal GET receipt checks root and original key without resubmission or mapping missing data to success', async () => {
  const apiRaw = readFileSync(new URL('../src/api/teamEvolutionOperations.ts', import.meta.url), 'utf8').replace(/^import .*$/gm, '').replace(/^export /gm, ''), calls = []; let result = { contractId: 2, requestKey: 'original-key', state: 'UNKNOWN' }
  const c = vm.createContext({ URLSearchParams, getData: async (...args) => { calls.push(['GET', ...args]); return result }, http: { post: async (...args) => { calls.push(['POST', ...args]); return { data: { data: result } } } } }); vm.runInContext(ts.transpile(apiRaw + '\nthis.api = TeamOperationApi', { target: ts.ScriptTarget.ES2022 }), c)
  await c.api.send(intent('PROPOSE')); assert.equal(calls[0][1], '/team-evolutions/2/proposals'); assert.equal(calls[0][3].timeout, 930000)
  const read = await c.api.receipt(intent('PROPOSE')); assert.equal(read.state, 'FOUND'); assert.equal(read.result.state, 'UNKNOWN'); assert.equal(calls[1][0], 'GET')
  result = { contractId: 9, requestKey: 'original-key', state: 'COMPLETED' }; await assert.rejects(c.api.receipt(intent('PROPOSE')), /不匹配/); assert.equal(calls.filter(c => c[0] === 'POST').length, 1)
})
