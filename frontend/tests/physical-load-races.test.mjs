import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { ref } from 'vue'
const source = readFileSync(new URL('../src/views/PhysicalView.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const ast = ts.createSourceFile('physical.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
const fn = ast.statements.find(n => ts.isFunctionDeclaration(n) && n.name.text === 'load').getText(ast)
function setup() {
 const pending = []
 const EapApi = new Proxy({}, { get: () => () => new Promise((resolve,reject) => pending.push({resolve,reject})) })
 const refs = Object.fromEntries(['loading','loadError','summary','reconciliation','events','endpoints','assets','agents','contentObservations','traceSpans','toolInvocations','windowHours','contextAgentId','contextEndpointKey','contextTraceId'].map(k => [k,ref()]))
 const c = vm.createContext({ ...refs, EapApi, telemetryForm: {}, ref })
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
test('unmounted physical page ignores results and same-context failure retains data',async()=>{
 const s=setup();s.summary.value={label:'retained'}
 const a=s.c.load();s.pending[0].reject(new Error('failure'));await a
 assert.equal(s.summary.value.label,'retained');assert.ok(s.loadError.value)
 const b=s.c.load();s.c.dispose();resolveBatch(s.pending,9,'late');await b
 assert.equal(s.summary.value.label,'retained')
})
