import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

function functionsFrom(names, state) {
  const script = readFileSync(new URL('../src/views/ManagementView.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile('ManagementView.vue', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const source = ast.statements.filter(node => ts.isFunctionDeclaration(node) && names.includes(node.name.text)).map(node => node.getText(ast)).join('\n')
  const context = vm.createContext(state)
  vm.runInContext(ts.transpile(source, { target: ts.ScriptTarget.ES2022 }), context)
  return context
}
const ref = value => ({ value })
function deferred() {
  let resolve, reject
  const promise = new Promise((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

test('management money shows the selected currency and never formats unavailable totals as zero', () => {
  const state = functionsFrom(['formatMoney', 'decisionFor'], { summary: ref({ currency: null }) })
  assert.equal(state.formatMoney(null), '—')
  assert.equal(state.formatMoney(200), '—')
  state.summary.value.currency = 'CNY'
  assert.equal(state.formatMoney(200), '200.00 CNY')
  assert.equal(state.formatMoney(null), '—')
  assert.equal(state.decisionFor({ tasks: 1, succeededTasks: 1, validatedValue: null, totalCost: null, roi: null }).key, 'OBSERVE')
  state.summary.value.currency = 'EUR'
  assert.equal(state.formatMoney(12.5), '12.50 EUR')
})

test('management decisions distinguish an unknown ROI from mixed currencies', () => {
  const state = functionsFrom(['decisionFor'], {})
  const row = { tasks: 1, succeededTasks: 1, modelCalls: 1, knowledgeInteractions: 0, validatedValue: 10, totalCost: 0, roi: null }
  assert.equal(state.decisionFor(row).label, '待核实')
  assert.match(state.decisionFor(row).reason, /费用为零或缺失/)
  assert.equal(state.decisionFor({ ...row, roi: 0 }).label, '待核实')
  assert.match(state.decisionFor({ ...row, totalCost: 5, roi: null }).reason, /投入产出不可计算/)
  assert.equal(state.decisionFor({ ...row, validatedValue: null, totalCost: null }).label, '选择币种')
  assert.equal(state.decisionFor({ ...row, totalCost: 5, roi: 1 }).label, '关注潜力')
  assert.equal(state.decisionFor({ ...row, totalCost: 10, roi: 0 }).key, 'OPTIMIZE')
})

test('switching financial currency ignores a late response for the previous currency', async () => {
  const usd = deferred(), cny = deferred()
  const state = functionsFrom(['load'], {
    loadRequestId: 0, windowHours: ref(24), selectedCurrency: ref('USD'), loading: ref(false), loadState: ref('loading'), summary: ref({}),
    valueEvents: ref([]), valueEventTotal: ref(null), contextAgentId: ref(undefined), contextEndpointKey: ref(''),
    agents: ref([]), connectors: ref([]), tasks: ref([]), tasksAvailable: ref(true),
    EapApi: {
      getManagementSummary: (_hours, currency) => currency === 'USD' ? usd.promise : cny.promise,
      pageManagementValues: async () => ({ items: [], totalElements: 0 }), listAgents: async () => [], listManagementConnectors: async () => [], listTasks: async () => [],
    },
  })
  const first = state.load()
  state.selectedCurrency.value = 'CNY'
  const second = state.load()
  cny.resolve({ currency: 'CNY', totalCost: 20 }); await second
  usd.resolve({ currency: 'USD', totalCost: 2 }); await first
  assert.equal(state.summary.value.currency, 'CNY')
  assert.equal(state.summary.value.totalCost, 20)
  assert.equal(state.loading.value, false)
  assert.equal(state.loadState.value, 'ready')
})

test('task list read failure is visible and later successful empty list restores availability', async () => {
  let failing = true
  const state = functionsFrom(['load'], {
    loadRequestId: 0, windowHours: ref(24), selectedCurrency: ref(''), loading: ref(false), loadState: ref('loading'), summary: ref({}),
    valueEvents: ref([]), valueEventTotal: ref(null), contextAgentId: ref(undefined), contextEndpointKey: ref(''),
    agents: ref([]), connectors: ref([]), tasks: ref([{ id: 1 }]), tasksAvailable: ref(true),
    EapApi: {
      getManagementSummary: async () => ({}), pageManagementValues: async () => ({ items: [], totalElements: 0 }), listAgents: async () => [],
      listManagementConnectors: async () => [], listTasks: async () => { if (failing) throw new Error('unavailable'); return [] },
    },
  })
  await state.load()
  assert.equal(state.tasksAvailable.value, false)
  assert.equal(state.tasks.value.length, 0)
  failing = false
  await state.load()
  assert.equal(state.tasksAvailable.value, true)
  assert.equal(state.tasks.value.length, 0)
})

test('value records are filtered on the server before the bounded page and retain the total', async () => {
  let query
  const state = functionsFrom(['load'], {
    loadRequestId: 0, windowHours: ref(24), selectedCurrency: ref('CNY'), loading: ref(false), loadState: ref('loading'), summary: ref({}),
    valueEvents: ref([]), valueEventTotal: ref(null), contextAgentId: ref(7), contextEndpointKey: ref('endpoint'),
    agents: ref([]), connectors: ref([]), tasks: ref([]), tasksAvailable: ref(true),
    EapApi: {
      getManagementSummary: async () => ({ currency: 'CNY' }),
      pageManagementValues: async params => { query = params; return { items: [{ id: 3 }], totalElements: 125 } },
      listAgents: async () => [], listManagementConnectors: async () => [], listTasks: async () => [],
    },
  })
  const before = Date.now()
  await state.load()
  assert.equal(query.page, 1)
  assert.equal(query.size, 100)
  assert.equal(query.order, 'desc')
  assert.equal(query.currency, 'CNY')
  assert.equal(query.agentId, 7)
  assert.equal(query.endpointKey, 'endpoint')
  assert.ok(Math.abs(Date.parse(query.from) - (before - 24 * 60 * 60 * 1000)) < 2000)
  assert.equal(state.valueEventTotal.value, 125)
  assert.equal(state.valueEvents.value.length, 1)
})

test('failed summary hides old data, recovers, and stale failures cannot override a newer response', async () => {
  const old = deferred()
  let phase = 'old'
  const state = functionsFrom(['load'], {
    loadRequestId: 0, windowHours: ref(24), selectedCurrency: ref(''), loading: ref(false), loadState: ref('ready'), summary: ref({ currency: 'OLD' }),
    valueEvents: ref([{ id: 1 }]), valueEventTotal: ref(1), contextAgentId: ref(undefined), contextEndpointKey: ref(''),
    agents: ref([]), connectors: ref([]), tasks: ref([]), tasksAvailable: ref(true),
    EapApi: {
      getManagementSummary: () => phase === 'old' ? old.promise : phase === 'fail' ? Promise.reject(new Error('unavailable')) : Promise.resolve({ currency: 'CNY' }),
      pageManagementValues: async () => ({ items: [], totalElements: 0 }), listAgents: async () => [],
      listManagementConnectors: async () => [], listTasks: async () => [],
    },
  })
  const first = state.load()
  assert.equal(state.loadState.value, 'loading')
  phase = 'success'
  await state.load()
  assert.equal(state.loadState.value, 'ready')
  old.reject(new Error('late failure'))
  await first
  assert.equal(state.loadState.value, 'ready')
  assert.equal(state.summary.value.currency, 'CNY')
  phase = 'fail'
  await state.load()
  assert.equal(state.loadState.value, 'error')
  assert.equal(state.valueEventTotal.value, null)
  phase = 'success'
  await state.load()
  assert.equal(state.loadState.value, 'ready')
  assert.equal(state.valueEventTotal.value, 0)
})
