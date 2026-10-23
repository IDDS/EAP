import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const script=readFileSync(new URL('../src/views/SchedulingView.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function scope(getScheduling){
  const ast=ts.createSourceFile('scheduling.ts',script,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS)
  const funcs=ast.statements.filter(ts.isFunctionDeclaration).map(n=>n.getText(ast)).join('\n')
  const state=vm.createContext({generation:0,data:{value:null},loading:{value:false},error:{value:''},getScheduling})
  vm.runInContext(ts.transpile(funcs,{target:ts.ScriptTarget.ES2022}),state);return state
}
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
test('a later refresh owns the page when the older request returns last',async()=>{
  const a=deferred(),b=deferred();let calls=0;const s=scope(()=>++calls===1?a.promise:b.promise)
  const first=s.load(),second=s.load();b.resolve({queued:1});await second;a.resolve({queued:99});await first
  assert.equal(s.data.value.queued,1);assert.equal(s.loading.value,false)
})
test('a denied current refresh clears stale scheduling facts and old success cannot restore them',async()=>{
  const a=deferred(),b=deferred();let calls=0;const s=scope(()=>++calls===1?a.promise:b.promise);s.data.value={queued:3}
  const old=s.load(),current=s.load();b.reject(new Error('403'));await current;a.resolve({queued:99});await old
  assert.equal(s.data.value,null);assert.ok(s.error.value);assert.equal(s.loading.value,false)
})
test('a disposed view cannot receive an in-flight response',async()=>{
  const a=deferred();const s=scope(()=>a.promise);const pending=s.load();s.generation++;a.resolve({queued:4});await pending;assert.equal(s.data.value,null)
})
test('missing observations differ from measured zero and waits are not labeled model latency',()=>{
  const s=scope(()=>{});assert.equal(s.duration(null),'—');assert.equal(s.duration(0),'0 ms')
  assert.equal(s.phaseLabel('TASK_WAIT'),'任务回执等待');assert.equal(s.phaseLabel('ASYNC_WAIT'),'异步任务等待');assert.equal(s.statusLabel('UNCONFIRMED'),'未确认')
})
