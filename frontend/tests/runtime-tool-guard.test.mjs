import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import vm from 'node:vm'
const source=readFileSync(new URL('../src/components/RuntimeToolGuardPanel.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function scope(globals){const ast=ts.createSourceFile('tools.ts',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);const code=ast.statements.filter(ts.isFunctionDeclaration).map(n=>n.getText(ast)).join('\n');const context=vm.createContext(globals);vm.runInContext(ts.transpile(code,{target:ts.ScriptTarget.ES2022}),context);return context}
function state(){return {alive:true,generation:0,rows:{value:[]},loading:{value:false},error:{value:''},props:{instanceKey:'space/runtime'},permissions:{state:{user:{}},can:()=>true}}}
test('late tool evidence cannot reappear after identity or permission clear',async()=>{
 let resolve;const g=state();g.http={get:()=>new Promise(r=>resolve=r)};const s=scope(g)
 const pending=s.load();s.clear();g.permissions.state.user={};resolve({data:{data:[{callId:'old'}]}});await pending
 assert.equal(g.rows.value.length,0)
})
test('failed tool history refresh removes old evidence and denied reads make no request',async()=>{
 const g=state();g.rows.value=[{callId:'old'}];let requests=0;g.http={get:async()=>{requests++;throw Error('unavailable')}};const s=scope(g)
 await s.load();assert.equal(g.rows.value.length,0);assert.ok(g.error.value)
 g.permissions.can=()=>false;await s.load();assert.equal(requests,1)
})
