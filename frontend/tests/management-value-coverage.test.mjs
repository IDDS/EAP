import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const source = readFileSync(new URL('../src/utils/managementCoverage.ts', import.meta.url), 'utf8')
const exports = {}
new Function('exports', ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText)(exports)
const { protectUnclassifiedValueReturns } = exports

test('an older server cannot expose a derived return without amount provenance', () => {
  const previous = { totalCost: 2, validatedBusinessValue: 10, netBenefit: 8, roi: 4, agentUsage: [{ netBenefit: 8, roi: 4 }] }
  for (const coverage of [undefined, null]) {
    const result = protectUnclassifiedValueReturns({ ...previous, valueCoverage: coverage })
    assert.equal(result.netBenefit, null)
    assert.equal(result.roi, null)
    assert.equal(result.agentUsage[0].netBenefit, null)
    assert.equal(result.agentUsage[0].roi, null)
    assert.equal(result.totalCost, 2)
    assert.equal(result.validatedBusinessValue, 10)
    assert.equal(previous.agentUsage[0].roi, 4)
  }
  const current = { ...previous, valueCoverage: { reported: 1, missing: 0, unknown: 0 } }
  assert.equal(protectUnclassifiedValueReturns(current), current)
})
