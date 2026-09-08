import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

const script = readFileSync(new URL('../src/views/PhysicalView.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const ast = ts.createSourceFile('PhysicalView.vue', script, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const fn = ast.statements.find(node => ts.isFunctionDeclaration(node) && node.name.text === 'representsModelEvent')
const context = vm.createContext({})
vm.runInContext(ts.transpile(fn.getText(ast), { target: ts.ScriptTarget.ES2022 }), context)
const match = context.representsModelEvent
const event = { traceId: 'trace-1', spanId: 'original-span' }
const projected = { traceId: 'trace-1', spanId: 'eap-content-35', spanKind: 'MODEL', attributesJson: JSON.stringify({ 'eap.projection.source': 'CONTENT_PROXY', 'eap.projection.original_span_id': 'original-span' }) }

test('derived content span suppresses the fallback for its original model call', () => {
  assert.equal(match(projected, event), true)
  assert.equal(match({ ...projected, spanId: 'original-span', attributesJson: '{}' }, event), true)
})

test('unrelated calls and malformed metadata keep their fallback observations', () => {
  assert.equal(match(projected, { ...event, spanId: 'another-call' }), false)
  assert.equal(match(projected, { ...event, traceId: 'another-trace' }), false)
  assert.equal(match({ ...projected, spanKind: 'TOOL' }, event), false)
  for (const attributesJson of ['null', '{broken', '{}']) {
    assert.equal(match({ ...projected, attributesJson }, event), false)
  }
})
