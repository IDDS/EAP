import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'
const source = readFileSync(new URL('../src/api/workflowTaskConfig.ts', import.meta.url), 'utf8')
const scope = { exports: {} }
new Function('exports', 'TextEncoder', ts.transpile(source, { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }))(scope.exports, TextEncoder)
const api = scope.exports
const none = () => api.setObservationContract('{"inputFrom":["prior"],"suspendAfterTask":true}', 'NONE')
test('explicit no-model expectation and removal preserve the original task configuration', () => {
  const raw = none()
  assert.equal(api.observationContractError(raw, 'AGENT', 1), '')
  assert.equal(api.observationContractError(api.setObservationContract(raw, 'ONE'), 'ARTIFACT', 1), '')
  assert.deepEqual(JSON.parse(api.setObservationContract(raw, 'DEFAULT')), { inputFrom: ['prior'], suspendAfterTask: true })
  assert.equal(api.observationContractError('{}', 'AGENT', 1), '')
})
test('rejects invalid values and future contract extensions instead of silently using zero', () => {
  for (const value of [null, [], {}, { version: 'FUTURE', modelEvents: 'NONE' },
    { version: 'EAP_OBSERVATION_V1', modelEvents: 0 }, { version: 'EAP_OBSERVATION_V1', modelEvents: 'none' },
    { version: 'EAP_OBSERVATION_V1', modelEvents: 'NONE', extra: true }]) {
    assert.ok(api.observationContractError(JSON.stringify({ eapObservation: value }), 'AGENT', 1))
  }
})
test('switching execution modes preserves an incompatible declaration for explicit correction', () => {
  for (const extra of [{ executionBackend: 'AX' }, { executionBackend: null }, { eapTool: null }]) {
    const raw = JSON.stringify({ ...JSON.parse(none()), ...extra })
    assert.ok(api.observationContractError(raw, 'AGENT', 1))
    assert.throws(() => api.setObservationContract(raw, 'NONE'))
    assert.equal(Object.hasOwn(JSON.parse(api.setObservationContract(raw, 'DEFAULT')), 'eapObservation'), false)
  }
  assert.ok(api.observationContractError(none(), 'START', 1))
  assert.ok(api.observationContractError(none(), 'AGENT'))
})
const component = readFileSync(new URL('../src/components/workflow/WorkflowObservationConfig.vue', import.meta.url), 'utf8')
const script = ts.transpile(component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, ''), { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None })
function mount() {
  const props = Vue.reactive({ modelValue: '{}', nodeType: 'AGENT', agentId: 1, disabled: false }), emitted = [], errors = []
  const args = { ...api, computed: Vue.computed, defineProps: () => props,
    defineEmits: () => ((event, value) => { emitted.push(value); props.modelValue = value }),
    ElMessage: { error: value => errors.push(value) } }
  const state = new Function(...Object.keys(args), script + ';return {change,value,error,available,hasContract}')(...Object.values(args))
  return { ...state, props, emitted, errors }
}
test('permission revocation blocks edits and selecting a different node replaces the displayed expectation', () => {
  const s = mount(); s.change('NONE'); assert.equal(s.value.value, 'NONE')
  s.props.disabled = true; const count = s.emitted.length; s.change('ONE'); assert.equal(s.emitted.length, count)
  s.props.modelValue = '{}'; assert.equal(s.value.value, 'DEFAULT')
  s.props.modelValue = '{broken'; assert.equal(s.available.value, false); s.change('NONE'); assert.equal(s.emitted.length, count)
})
test('incompatible or malformed declarations remain visible until explicitly removed', () => {
  const s = mount(); s.props.modelValue = JSON.stringify({ ...JSON.parse(none()), executionBackend: 'AX' })
  assert.equal(s.available.value, false); assert.equal(s.hasContract.value, true); assert.ok(s.error.value)
  s.change('ONE'); assert.equal(s.emitted.length, 0); s.change('DEFAULT'); assert.equal(s.hasContract.value, false)
  s.props.modelValue = '{"eapObservation":null}'; assert.equal(s.value.value, 'INVALID'); assert.ok(s.error.value)
  s.change('DEFAULT'); assert.equal(s.error.value, '')
})
test('unbound and control nodes cannot emit a no-model declaration', () => {
  const s = mount(); s.props.agentId = undefined; s.change('NONE'); assert.equal(s.emitted.length, 0)
  s.props.agentId = 1; s.props.nodeType = 'START'; s.change('NONE'); assert.equal(s.emitted.length, 0)
})
