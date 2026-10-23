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
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
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

test('switching financial currency ignores a late response for the previous currency', async () => {
  const usd = deferred(), cny = deferred()
  const state = functionsFrom(['load'], {
    loadRequestId: 0, windowHours: ref(24), selectedCurrency: ref('USD'), loading: ref(false), summary: ref({}),
    valueEvents: ref([]), agents: ref([]), connectors: ref([]), tasks: ref([]),
    EapApi: {
      getManagementSummary: (_hours, currency) => currency === 'USD' ? usd.promise : cny.promise,
      listManagementValueEvents: async () => [], listAgents: async () => [], listManagementConnectors: async () => [], listTasks: async () => [],
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
})
