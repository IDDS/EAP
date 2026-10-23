import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
const source = readFileSync(new URL('../src/api/workflowTaskConfig.ts', import.meta.url), 'utf8')
const scope = vm.createContext({ exports: {} })
vm.runInContext(ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }), scope)
const { setTaskMode, updateToolNumbers, toolConfigError } = scope.exports

test('mode changes preserve upstream bindings and sleep settings without stale tool instructions', () => {
  const tool = setTaskMode('{"inputFrom":["first"],"suspendAfterTask":true}', 'TOOL')
  const edited = updateToolNumbers(tool, '10, 20\n12')
  assert.equal(toolConfigError(edited, 'AGENT', 1), '')
  assert.deepEqual(JSON.parse(edited).eapTool.arguments.numbers, [10, 20, 12])
  const model = JSON.parse(setTaskMode(edited, 'MODEL'))
  assert.equal(model.eapTool, undefined)
  assert.equal(model.suspendAfterTask, true)
  assert.deepEqual(model.inputFrom, ['first'])
})
test('invalid in-progress edits remain invalid instead of executing previous numbers', () => {
  for (const input of ['', '1,', 'abc', 'Infinity', '1e13', '1, ,2', '0xFF', Array(101).fill('1').join(',')]) {
    const edited = updateToolNumbers(setTaskMode('{}', 'TOOL'), input)
    assert.notEqual(toolConfigError(edited, 'AGENT', 1), '', input)
  }
})
test('tool mode rejects incompatible backends, missing agents and raw JSON bypasses', () => {
  assert.throws(() => setTaskMode('{"executionBackend":"AX"}', 'TOOL'))
  const valid = updateToolNumbers(setTaskMode('{}', 'TOOL'), '1')
  assert.notEqual(toolConfigError(valid, 'AGENT'), '')
  assert.notEqual(toolConfigError(valid, 'START', 1), '')
  assert.notEqual(toolConfigError(valid.replace('calculate_sum', 'shell'), 'AGENT', 1), '')
  assert.notEqual(toolConfigError(valid.replace('"numbers":', '"extra":true,"numbers":'), 'AGENT', 1), '')
  for (const raw of ['[]', 'null', '{bad']) assert.notEqual(toolConfigError(raw, 'AGENT', 1), '')
})

test('scoped effect configuration requires stable identifiers and bounded parameters',()=>{
 const args={grantId:'00000000-0000-4000-8000-000000000001',operationId:'00000000-0000-4000-8000-000000000002',resourceId:'poc_test',delta:3}
 const raw=a=>JSON.stringify({eapTool:{name:'controlled_counter_increment',arguments:a}})
 assert.equal(toolConfigError(raw(args),'AGENT',1),'')
 for(const change of [{delta:1.5},{delta:101},{resourceId:'business_record'},{grantId:'bad'},{effectSource:'forged'}])assert.notEqual(toolConfigError(raw({...args,...change}),'AGENT',1),'')
})
