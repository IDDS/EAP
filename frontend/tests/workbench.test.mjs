import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
const ref = value => ({ value })
function workbench(api) {
  const source = readFileSync(new URL('../src/views/DashboardView.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile('DashboardView.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const selected = ['applySeverity', 'toggleOverdue', 'clearFilters', 'load']
  const script = ast.statements.filter(n => ts.isFunctionDeclaration(n) && selected.includes(n.name.text)).map(n => n.getText(ast)).join('\n')
  const state = vm.createContext({ workbenchTab: ref('network'), severityFilter: ref(), showOverdueOnly: ref(false), loading: ref(false), hasDashboard: ref(false), dashboardError: ref(''), dashboard: ref({}), dashboardRequestId: 0, context: { windowHours: 24, organizationScope: '' }, EapApi: { getAgentOpsDashboard: api } })
  vm.runInContext(ts.transpile(script, { target: ts.ScriptTarget.ES2022 }), state)
  return state
}
test('priority and overdue shortcuts reveal queue and reflect toggled filter state', () => {
  const s = workbench()
  s.applySeverity('P1')
  assert.equal(s.workbenchTab.value, 'queue')
  assert.equal(s.severityFilter.value, 'P1')
  s.applySeverity('P1')
  assert.equal(s.severityFilter.value, undefined)
  s.workbenchTab.value = 'metrics'
  s.toggleOverdue()
  assert.equal(s.workbenchTab.value, 'queue')
  assert.equal(s.showOverdueOnly.value, true)
  s.clearFilters()
  assert.equal(s.showOverdueOnly.value, false)
})
test('a failed refresh preserves previously loaded values with an explicit failure state', async () => {
  let fail = false
  const s = workbench(async () => { if (fail) throw new Error('internal detail'); return { agentCount: 4 } })
  await s.load()
  fail = true
  await s.load()
  assert.equal(s.hasDashboard.value, true)
  assert.equal(s.dashboard.value.agentCount, 4)
  assert.ok(s.dashboardError.value.includes('保留'))
  assert.ok(!s.dashboardError.value.includes('internal detail'))
  assert.equal(s.loading.value, false)
})
test('an obsolete request failure cannot replace the current view with an error', async () => {
  let rejectOld
  let calls = 0
  const s = workbench(() => ++calls === 1 ? new Promise((_, reject) => { rejectOld = reject }) : Promise.resolve({ agentCount: 9 }))
  const old = s.load()
  await s.load()
  rejectOld(new Error('late failure'))
  await old
  assert.equal(s.dashboard.value.agentCount, 9)
  assert.equal(s.dashboardError.value, '')
})
