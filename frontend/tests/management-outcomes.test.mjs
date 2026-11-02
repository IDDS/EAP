import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import { computed, ref } from 'vue'

function metrics(tasks, events) {
  const text = readFileSync(new URL('../src/views/ManagementView.vue', import.meta.url), 'utf8')
  const script = text.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile('ManagementView.vue', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const names = new Set(['employeeUsage', 'applicationUsage', 'resolvedTaskCount', 'resolutionObservedCount', 'resolutionRateLabel', 'outcomeCoverageLabel', 'businessValueFunnel'])
  const declarations = ast.statements.filter(node => ts.isVariableStatement(node) && node.declarationList.declarations.some(decl => names.has(decl.name.getText(ast))))
    .map(node => node.getText(ast)).join('\n')
  const js = ts.transpile(declarations, { target: ts.ScriptTarget.ES2022 })
  return new Function('computed', 'windowTasks', 'visibleValueEvents', 'valueEventsInWindow', 'windowHours',
    `${js}; return { employeeUsage, applicationUsage, resolutionObservedCount, resolutionRateLabel, outcomeCoverageLabel, businessValueFunnel }`)(
    computed, ref(tasks), ref(events), ref(events), ref(24))
}

test('missing results are not judgments and independent associations use the whole task denominator', () => {
  const now = new Date().toISOString()
  const tasks = [
    { id: 1, requester: 'fixture', taskType: 'TEST', status: 'SUCCEEDED', problemResolved: null, createdAt: now },
    { id: 2, requester: 'fixture', taskType: 'TEST', status: 'FAILED', createdAt: now },
    { id: 3, requester: 'fixture', taskType: 'TEST', status: 'FAILED', problemResolved: false, createdAt: now },
    { id: 4, requester: 'fixture', taskType: 'TEST', status: 'FAILED', problemResolved: true, createdAt: now },
  ]
  const events = [
    { taskId: 1, sourceType: 'DERIVED', benefitMoney: 5, observedAt: now },
    { taskId: 2, sourceType: 'MANUAL', benefitMoney: 2, observedAt: now },
  ]
  const result = metrics(tasks, events)
  assert.equal(result.resolutionObservedCount.value, 2)
  assert.equal(result.resolutionRateLabel.value, '50%')
  assert.equal(result.outcomeCoverageLabel.value, '50%')
  assert.equal(result.employeeUsage.value[0].judged, 2)
  assert.equal(result.applicationUsage.value[0].judged, 2)
  const stages = Object.fromEntries(result.businessValueFunnel.value.map(item => [item.key, item]))
  assert.equal(stages.succeeded.count, 1)
  assert.equal(stages.judged.count, 2)
  assert.equal(stages.judged.rateLabel, '50% 任务')
  assert.equal(stages.valued.count, 2)
  assert.equal(stages.valued.rateLabel, '50% 任务')
  assert.equal(stages.evidenced.count, 1)
  assert.equal(stages.evidenced.rateLabel, '25% 任务')
})

test('empty task sets do not fabricate a percentage', () => {
  const result = metrics([], [])
  assert.equal(result.resolutionRateLabel.value, '未采集')
  assert.equal(result.outcomeCoverageLabel.value, '未采集')
  for (const stage of result.businessValueFunnel.value) assert.equal(stage.rateLabel, '—')
})

test('trend labels its visible sample and cumulative value starts with the first displayed event', () => {
  const text = readFileSync(new URL('../src/views/ManagementView.vue', import.meta.url), 'utf8')
  const script = text.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile('ManagementView.vue', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const names = new Set(['valueSampleLabel', 'valueTrendOption'])
  const code = ast.statements.filter(node => ts.isVariableStatement(node) && node.declarationList.declarations.some(decl => names.has(decl.name.getText(ast))))
    .map(node => node.getText(ast)).join('\n')
  const events = Array.from({ length: 20 }, (_, i) => ({ observedAt: new Date(1700000000000 + i * 1000).toISOString(), benefitMoney: i + 1 }))
  const result = new Function('computed', 'valueEventTotal', 'valueEventsInWindow', 'summary',
    `${ts.transpile(code, { target: ts.ScriptTarget.ES2022 })}; return { valueSampleLabel, valueTrendOption }`)(
    computed, ref(125), ref(events), ref({ currency: 'CNY' }))
  assert.equal(result.valueSampleLabel.value, '符合筛选条件 125 条，取最近 20 条')
  const series = result.valueTrendOption.value.series
  assert.equal(series[0].data.length, 16)
  assert.equal(series[0].data[0], 5)
  assert.equal(series[1].name, '图中记录累计')
  assert.equal(series[1].data[0], 5)
  assert.equal(series[1].data.at(-1), 200)
})
