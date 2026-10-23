import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
const script=readFileSync(new URL('../src/views/ObservationCoverageView.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function scope(getCoverage,getCoverageOperation=()=>{}) {
 const ast=ts.createSourceFile('coverage.ts',script,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS)
 const functions=ast.statements.filter(ts.isFunctionDeclaration).map(n=>n.getText(ast)).join('\n')
 const state=vm.createContext({generation:0,detailGeneration:0,minutes:{value:60},data:{value:null},loading:{value:false},error:{value:''},detail:{value:null},detailOpen:{value:false},detailLoading:{value:false},detailError:{value:''},getCoverage,getCoverageOperation})
 vm.runInContext(ts.transpile(functions,{target:ts.ScriptTarget.ES2022}),state);return state
}
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
test('a later window response owns the page and stale response cannot replace it',async()=>{
 const a=deferred(),b=deferred();let calls=0;const s=scope(()=>++calls===1?a.promise:b.promise)
 const first=s.load(),second=s.load();b.resolve({windowMinutes:60});await second;a.resolve({windowMinutes:10080});await first;assert.equal(s.data.value.windowMinutes,60)
})
test('current failure clears old facts and an old success cannot restore them',async()=>{
 const a=deferred(),b=deferred();let calls=0;const s=scope(()=>++calls===1?a.promise:b.promise);s.data.value={old:true}
 const first=s.load(),second=s.load();b.reject(new Error('403'));await second;a.resolve({old:true});await first;assert.equal(s.data.value,null);assert.ok(s.error.value)
})
test('closing or changing a detail prevents previous private metadata from appearing',async()=>{
 const a=deferred();const s=scope(()=>{},()=>a.promise);const pending=s.select(1);s.clearDetail();s.detailOpen.value=false;a.resolve({operation:{id:1}});await pending;assert.equal(s.detail.value,null)
})
test('unconfirmed denominator differs from zero and failed business differs from complete evidence',()=>{
 const s=scope(()=>{});assert.equal(s.count(null),'—');assert.equal(s.count(0),'0');assert.equal(s.state('FAILED'),'业务失败');assert.equal(s.state('COMPLETE'),'预期证据齐全');assert.equal(s.entry('CHAT'),'对话');assert.match(s.entry('LEGACY_EXTERNAL'),/未登记/)
})
