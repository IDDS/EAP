import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'
const component = readFileSync(new URL('../src/components/RuntimePlacementEvidence.vue', import.meta.url), 'utf8')
const script = ts.transpile(component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, ''), { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None })
function mount(samples) {
  const props = Vue.reactive({ samples })
  const state = new Function('computed', 'defineProps', script + ';return {rows,time,reason}')(Vue.computed, () => props)
  return { ...state, props }
}
const recorded = (status = 'ASSIGNED', source = 'SUBSTRATE_CONTROL_API') => ({ phase: 'DISPATCH_REQUESTED', requestedAt: '2026-10-21T02:00:00Z', captureState: 'RECORDED', snapshot: { status, source, reason: 'VERIFIED_CONTROL_SNAPSHOT', placement: { nodeName: 'original-node' } } })
test('changing the selected operation removes old location without probing or retaining a cache', () => {
  const s = mount([recorded()]); assert.equal(s.rows.value[0].location.nodeName, 'original-node')
  s.props.samples = []; assert.equal(s.rows.value[0].location, null); assert.equal(s.rows.value[0].state, 'MISSING')
  s.props.samples = undefined; assert.equal(s.rows.value[1].state, 'MISSING')
})
test('unassigned unknown and incomplete evidence never display coordinates from another state', () => {
  const s = mount([recorded('UNASSIGNED')]); assert.equal(s.rows.value[0].location, null); assert.equal(s.rows.value[0].state, 'UNASSIGNED')
  s.props.samples = [recorded('UNKNOWN')]; assert.equal(s.rows.value[0].location, null); assert.equal(s.rows.value[0].state, 'UNKNOWN')
  s.props.samples = [{ ...recorded(), captureState: 'INCOMPLETE' }]; assert.equal(s.rows.value[0].location, null); assert.equal(s.rows.value[0].state, 'INCOMPLETE')
  s.props.samples = [recorded('ASSIGNED', 'UNTRUSTED')]; assert.equal(s.rows.value[0].location, null)
})
test('missing stages and fixed failure reasons remain distinct without exposing upstream messages', () => {
  const s = mount([recorded()]); assert.equal(s.rows.value[1].status, '未记录')
  assert.equal(s.time('invalid'), '时间未确认'); assert.doesNotMatch(s.reason('private provider response'), /private/)
  assert.match(s.reason('ORIGINAL_IDENTITY_MISMATCH'), /未借用替代实例/)
  assert.match(component, /后台查询可能晚于触发时刻/); assert.match(component, /不证明执行期间没有迁移/)
})

test('valid observation time includes calendar date and full clock, not only fractional seconds', () => {
  const s = mount([]), text = s.time('2026-10-21T02:13:27.283Z')
  assert.match(text, /2026/); assert.match(text, /10/); assert.match(text, /21/)
  assert.match(text, /\d{2}:13:27/); assert.match(text, /283/)
  assert.notEqual(text, '283')
})
