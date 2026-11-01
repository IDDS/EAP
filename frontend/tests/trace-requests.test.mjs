import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
const source = readFileSync(new URL('../src/components/trace/trace-requests.ts', import.meta.url), 'utf8').replace(/^import .*$/m, '').replace('export interface', 'interface').replace('export function', 'function')
const context = vm.createContext({})
vm.runInContext(ts.transpile(source, { target: ts.ScriptTarget.ES2022 }), context)
const spans = [
 { traceId: 'a', spanId: '1', spanKind: 'AGENT', spanName: 'main', agentId: 127, status: 'SUCCEEDED', observedAt: '2026-10-30T10:00:00Z' },
 { traceId: 'a', spanId: '2', parentSpanId: '1', spanKind: 'TOOL', spanName: 'tool', agentId: 128, status: 'FAILED', observedAt: '2026-10-30T10:01:00Z' },
 { traceId: 'b', spanId: '3', spanKind: 'MODEL', spanName: 'model', status: 'STARTED', observedAt: '2026-10-30T10:02:00Z' },
]
test('groups by trace, preserves agent attribution and reports observed failures', () => {
 const result = context.traceRequests(spans)
 assert.equal(result.length, 2)
 assert.equal(result[0].traceId, 'b')
 assert.equal(result[0].observationStatus, 'active')
 assert.equal(result[1].label, 'main')
 assert.equal(result[1].observedSpanCount, 2)
 assert.deepEqual(Array.from(result[1].agentIds), [127, 128])
 assert.equal(result[1].observationStatus, 'failed')
})
test('unidentified traces are omitted and input is unchanged', () => {
 const values = [...spans, { traceId: '', observedAt: '2026-10-30T10:03:00Z' }]
 assert.equal(context.traceRequests(values).length, 2)
 assert.equal(values[0].spanId, '1')
})
