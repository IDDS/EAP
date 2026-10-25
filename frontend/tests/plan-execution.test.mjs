import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

function handler(name, globals) {
  const source = readFileSync(new URL('../src/views/OrchestrationView.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
  const declaration = ast.statements.find(node => ts.isFunctionDeclaration(node) && node.name.text === name)
  const context = vm.createContext(globals)
  vm.runInContext(ts.transpile(declaration.getText(ast), { target: ts.ScriptTarget.ES2022 }), context)
  return context[name]
}
const ref = value => ({ value })
function state(api) {
  return { selectedPlanId: ref(7), submitting: ref(false), frozen: ref(false), suspendAfterTask: ref(true),
    planLoading: ref(false), planError: ref(''), planSteps: ref([{ id: 1 }]), reclaimBlocked: ref(false),
    planSubmissionError: () => '提交未确认',
    executionError: ref(''), permissions: { can: () => true }, EapApi: api, ElMessage: { success() {} }, selectPlan: async () => {} }
}

test('plan execution enforces current capability and prevents concurrent clicks', async () => {
  const calls = []; let resolve
  const pending = new Promise(r => { resolve = r })
  const s = state({ executePlan: (...args) => { calls.push(args); return pending } })
  s.permissions.can = () => false
  const execute = handler('executePlan', s)
  await execute(); assert.equal(calls.length, 0)
  s.permissions.can = () => true
  const first = execute(); await execute(); assert.equal(calls.length, 1)
  s.selectedPlanId.value = 8
  s.selectPlan = async () => { assert.fail('old response must not select a different plan') }
  resolve(); await first
  assert.equal(calls[0][0], 7)
  assert.equal(calls[0][1].requestKey, 'plan-7')
  assert.equal(s.submitting.value, false)
})

test('uncertain plan submission retries with the same stable request key', async () => {
  const calls = []
  const s = state({ executePlan: async (id, body) => { calls.push(body.requestKey); throw new Error('network lost') } })
  const execute = handler('executePlan', s)
  await execute(); await execute()
  assert.deepEqual(calls, ['plan-7', 'plan-7'])
  assert.match(s.executionError.value, /提交未确认/)
  s.frozen.value = true
  await execute(); assert.equal(calls.length, 2)
})

test('known reclaim blocker prevents any plan submission', async () => {
  const s = state({ executePlan: async () => assert.fail('blocked execution must not be submitted') })
  s.reclaimBlocked.value = true
  await handler('executePlan', s)()
  assert.equal(s.submitting.value, false)
})

test('member assignment cannot fake running and respects frozen/revoked permissions', async () => {
  const calls = []
  const s = state({ assignPlanStep: async (...args) => { calls.push(args) } })
  const assign = handler('assignMember', s)
  await assign(1, 9)
  assert.equal(calls[0][1].status, 'ASSIGNED')
  s.frozen.value = true; await assign(1, 10)
  s.frozen.value = false; s.permissions.can = () => false; await assign(1, 10)
  assert.equal(calls.length, 1)
})


test('initial unauthenticated team loading handles rejection and clears loading state', async () => {
  const s = { loading: ref(false), teams: ref([]), selectedTeamId: ref(), disposed: false,
    executionError: ref(''), EapApi: { listTeams: async () => { throw new Error('401') } },
    loadTeamDetail: async () => { assert.fail('must not load team detail after rejection') } }
  await handler('load', s)()
  assert.equal(s.loading.value, false)
  assert.match(s.executionError.value, /登录/)
  s.disposed = true; s.executionError.value = ''
  await handler('load', s)()
  assert.equal(s.executionError.value, '')
})
