import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
const source = readFileSync(new URL('../src/components/trace/trace-diagnostics.ts', import.meta.url), 'utf8').replace(/^import .*$/m, '').replace('export function', 'function')
const context = vm.createContext({})
vm.runInContext(ts.transpile(source, { target: ts.ScriptTarget.ES2022 }), context)
const diagnose = context.traceDiagnostics
const span = (id, start, end, extra = {}) => ({ id, traceId: 't', spanId: String(id), durationMs: 900, startedAt: start == null ? undefined : new Date(start).toISOString(), endedAt: end == null ? undefined : new Date(end).toISOString(), ...extra })
test('concurrent spans use observed envelope rather than summed durations', () => {
 const d = diagnose([span(1, 1000, 2000), span(2, 1500, 1800, { parentSpanId: '1' })])
 assert.equal(d.duration, 1000)
 assert.equal(d.bars[1].left, 50)
 assert.equal(d.bars[1].width, 30)
 assert.equal(d.bars[1].depth, 1)
})
test('missing, reversed and synthetic timestamps do not create fabricated bars', () => {
 const d = diagnose([span(1, null, null), span(2, 2000, 1000), span(3, 1000, 2000, { synthetic: true }), span(4, 1000, 1000)])
 assert.equal(d.timed, 1)
 assert.equal(d.duration, 0)
 assert.equal(d.rows.filter(r => r.timingIssue).length, 3)
 assert.ok(d.bars.every(r => Number.isFinite(r.left) && Number.isFinite(r.width)))
})
test('parent matching is trace scoped and cycles terminate with explicit diagnostics', () => {
 const d = diagnose([span(1, 1000, 2000, { parentSpanId: '2' }), span(2, 1000, 2000, { parentSpanId: '1', status: 'FAILED' }), span(3, null, null, { traceId: 'other', parentSpanId: '1' })])
 assert.equal(d.cycles, 2)
 assert.equal(d.missingParents, 1)
 assert.equal(d.failed, 1)
})
