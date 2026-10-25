import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'

const vue = readFileSync(new URL('../src/views/GovernanceView.vue', import.meta.url), 'utf8')
const source = vue.split('<script setup lang="ts">')[1].split('</script>')[0]
function row(state = 'CURRENT') { return { decisionKey: 'original', approvalStatus: 'PENDING', approvalContextState: state } }
function scope(api = {}) {
  const ast = ts.createSourceFile('approval.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const names = new Set(['canApprove', 'approvalContextLabel', 'approveDecision', 'rejectDecision', 'mutateApproval', 'runPreflight'])
  const code = ast.statements.filter(n => ts.isFunctionDeclaration(n) && names.has(n.name.text)).map(n => n.getText(ast)).join('\n')
  const state = vm.createContext({ approvalAlive: true, saving: { value: false }, permissions: { state: { user: {} }, can: () => true }, approvalMutationError: { value: '' }, EapApi: api, reads: 0, messages: [], ElMessage: { success: message => state.messages.push(message) }, load: async () => { state.reads++ } })
  vm.runInContext(ts.transpile(code, { target: ts.ScriptTarget.ES2022 }), state)
  return state
}
function deferred() { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }

test('console preflight requires the central governance capability and prevents concurrent submissions', async () => {
  let calls = 0; const s = scope({ evaluateGovernancePreflight: async () => { calls++ } })
  s.permissions.can = () => false; await s.runPreflight()
  s.permissions.can = () => true; s.saving.value = true; await s.runPreflight()
  s.saving.value = false; s.approvalAlive = false; await s.runPreflight()
  assert.equal(calls, 0)
})

test('only current pending approval and the central governance capability can submit', async () => {
  let calls = 0; const s = scope({ approveGovernanceDecision: async () => { calls++ } })
  for (const value of ['CHANGED', 'UNRECORDED', 'NOT_APPLICABLE', undefined]) await s.approveDecision({ ...row(), approvalContextState: value })
  s.permissions.can = () => false; await s.approveDecision(row()); assert.equal(calls, 0)
  s.permissions.can = () => true; await s.approveDecision({ ...row(), approvalStatus: 'APPROVED' }); assert.equal(calls, 0)
  await s.approveDecision(row()); assert.equal(calls, 1); assert.equal(s.reads, 1)
})
test('a second click while the original approval is pending sends nothing', async () => {
  const pending = deferred(), calls = []; const s = scope({ approveGovernanceDecision: (...args) => { calls.push(args); return pending.promise } })
  const a = s.approveDecision(row()); await s.approveDecision(row()); await s.rejectDecision(row()); assert.equal(calls.length, 1)
  pending.resolve({}); await a; assert.equal(calls[0][0], 'original'); assert.equal(s.saving.value, false)
})
test('a lost or conflicting response cannot leave the old approve action enabled', async () => {
  for (const reason of ['409', 'disconnected']) {
    let calls = 0; const s = scope({ approveGovernanceDecision: async () => { calls++; throw Error(reason) } }); const original = row()
    await s.approveDecision(original); await s.approveDecision(original)
    assert.equal(calls, 1); assert.equal(s.reads, 0); assert.equal(original.approvalStatus, 'PENDING'); assert.equal(original.approvalContextState, 'UNRECORDED'); assert.match(s.approvalMutationError.value, /不要直接重发/)
  }
})
test('stale approvals remain rejectable without granting access', async () => {
  let calls = 0; const s = scope({ rejectGovernanceDecision: async () => { calls++ } }); await s.rejectDecision(row('CHANGED'))
  assert.equal(calls, 1); assert.equal(s.reads, 1)
})
test('account capability and unmount changes suppress late approval completion', async () => {
  for (const change of [s => { s.permissions.state.user = {} }, s => { s.permissions.can = () => false }, s => { s.approvalAlive = false }]) {
    const pending = deferred(), s = scope({ approveGovernanceDecision: () => pending.promise }); const request = s.approveDecision(row()); change(s); pending.resolve({}); await request
    assert.equal(s.reads, 0); assert.equal(s.messages.length, 0)
  }
})
test('display distinguishes original approval from current policy validity and unknown history', () => {
  const s = scope(); assert.match(s.approvalContextLabel({ ...row('CHANGED'), approvalStatus: 'APPROVED' }), /已变更/)
  assert.match(s.approvalContextLabel({ ...row(), approvalContextState: undefined }), /未记录/)
  assert.equal(s.approvalContextLabel({ ...row(), approvalStatus: 'REJECTED' }), '不适用')
})
