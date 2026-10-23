import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { ref } from 'vue'
const source = readFileSync(new URL('../src/views/PhysicalView.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const ast = ts.createSourceFile('physical.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const fn = ast.statements.filter(n => ts.isFunctionDeclaration(n) && ['load','fetchContentObservations'].includes(n.name.text)).map(n => n.getText(ast)).join('\n')
function setup() {
 const pending = []
 const EapApi = new Proxy({}, { get: () => () => new Promise((resolve,reject) => pending.push({resolve,reject})) })
 const refs = Object.fromEntries(['summaryLoaded','contentError','modelHistory','contentHistory','spanHistory','toolHistory','loading','loadError','summary','reconciliation','events','endpoints','assets','agents','contentObservations','traceSpans','toolInvocations','windowHours','contextAgentId','contextEndpointKey','contextTraceId'].map(k => [k,ref()]))
 const c = vm.createContext({ ...refs, permissions: {state:{user:{}},can:()=>true}, EapApi, telemetryForm: {}, ref })
 vm.runInContext(ts.transpile('let loadVersion=0; let disposed=false;\n'+fn+'\nglobalThis.dispose = () => {disposed=true;loadVersion++}',{target:ts.ScriptTarget.ES2022}),c)
 return {c,pending,...refs}
}
function resolveBatch(pending,offset,label) {
 for(let i=0;i<9;i++)pending[offset+i].resolve(i===0?{label}:i===1?{}:[])
}
test('late successful physical refresh cannot overwrite a newer trace request',async()=>{
 const s=setup();const a=s.c.load();const b=s.c.load()
 resolveBatch(s.pending,9,'new');await b
 resolveBatch(s.pending,0,'old');await a
 assert.equal(s.summary.value.label,'new');assert.equal(s.loading.value,false)
})
test('late errors do not change current loading or error state',async()=>{
 const s=setup();const a=s.c.load();const b=s.c.load()
 s.pending[0].reject(new Error('old failure'));await a
 assert.equal(s.loading.value,true);assert.equal(s.loadError.value,'')
 resolveBatch(s.pending,9,'new');await b
 assert.equal(s.summary.value.label,'new')
})
test('unmounted physical page ignores results and failures hide stale summary',async()=>{
 const s=setup();s.summary.value={label:'retained'}
 const a=s.c.load();s.pending[0].reject(new Error('failure'));await a
 assert.equal(s.summary.value.label,'retained');assert.ok(s.loadError.value);assert.equal(s.summaryLoaded.value,false)
 const b=s.c.load();s.c.dispose();resolveBatch(s.pending,9,'late');await b
 assert.equal(s.summary.value.label,'retained')
})

test('content service failure does not prevent ordinary telemetry from loading', async()=>{
 const s=setup();const request=s.c.load()
 for(let i=0;i<9;i++) { if(i===6)s.pending[i].reject(new Error('content unavailable')); else s.pending[i].resolve(i===0?{totalCalls:14}:i===1?{}:[]) }
 await request
 assert.equal(s.summary.value.totalCalls,14)
 assert.equal(s.summaryLoaded.value,true)
 assert.equal(s.loadError.value,'')
 assert.match(s.contentError.value,/普通遥测不受影响/)
})
