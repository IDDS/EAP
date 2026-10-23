import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import {ref} from 'vue'
const source=readFileSync(new URL('../src/components/trace/TaskEvidenceDrawer.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const ast=ts.createSourceFile('drawer.ts',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS)
const fn=ast.statements.find(n=>ts.isFunctionDeclaration(n)&&n.name.text==='load').getText(ast)
function setup(){
 const pending=[];const data=ref(),error=ref(''),loading=ref(false),props={taskId:1}
 const c=vm.createContext({data,error,loading,props,getData:url=>new Promise((resolve,reject)=>pending.push({url,resolve,reject}))})
 vm.runInContext(ts.transpile('let version=0;'+fn+'\nglobalThis.invalidate=()=>version++',{target:ts.ScriptTarget.ES2022}),c)
 return {c,pending,data,error,loading,props}
}
test('task switch ignores old evidence and late errors',async()=>{
 const s=setup();const a=s.c.load();s.props.taskId=2;const b=s.c.load()
 s.pending[1].resolve({task:{id:2}});await b;s.pending[0].reject(Error('old'));await a
 assert.equal(s.data.value.task.id,2);assert.equal(s.error.value,'');assert.equal(s.pending[1].url,'/tasks/2/evidence')
})
test('closing drawer prevents late result and current error retains last evidence',async()=>{
 const s=setup();s.data.value={task:{id:1}};const a=s.c.load();s.pending[0].reject(Error('failed'));await a
 assert.equal(s.data.value.task.id,1);assert.ok(s.error.value)
 const b=s.c.load();s.c.invalidate();s.pending[1].resolve({task:{id:9}});await b
 assert.equal(s.data.value.task.id,1)
})
