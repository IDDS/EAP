import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import vm from 'node:vm'
const source=readFileSync(new URL('../src/components/workflow/ToolRecoveryFacts.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function scope(g) {
  const ast=ts.createSourceFile('recovery.ts',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS)
  const code=ast.statements.filter(ts.isFunctionDeclaration).map(n=>n.getText(ast)).join('\n')
  const context=vm.createContext(g);vm.runInContext(ts.transpile(code,{target:ts.ScriptTarget.ES2022}),context);return context
}
function state() {return {disposed:false,revision:0,props:{workflowId:7,runKey:'original'},permissions:{can:()=>true},loading:{value:false},error:{value:''},report:{value:null}}}
test('workflow tool recovery reads original run without executing or resuming',async()=>{
  const g=state(),calls=[];g.EapApi={getWorkflowToolRecovery:async(...args)=>{calls.push(args);return {mode:'QUERY_ONLY',entries:[{operationId:'original-O',state:'UNKNOWN'}]}}}
  await scope(g).load();assert.deepEqual(calls,[[7,'original']]);assert.equal(g.report.value.entries[0].operationId,'original-O')
})
test('late workflow recovery evidence cannot reappear after identity clear',async()=>{
  const g=state();let resolve;g.EapApi={getWorkflowToolRecovery:()=>new Promise(r=>resolve=r)};const s=scope(g),pending=s.load();s.clear();resolve({entries:[{state:'CONFIRMED'}]});await pending;assert.equal(g.report.value,null)
})
test('failed workflow recovery read clears old evidence and preserves uncertainty',async()=>{
  const g=state();g.report.value={entries:[{state:'CONFIRMED'}]};g.EapApi={getWorkflowToolRecovery:async()=>{throw Error()}}
  await scope(g).load();assert.equal(g.report.value,null);assert.match(g.error.value,/不能推定.*未提交/)
})
test('permission denial and run change reject recovery evidence',async()=>{
  const g=state();let calls=0;g.permissions.can=()=>false;g.EapApi={getWorkflowToolRecovery:async()=>calls++};const s=scope(g);await s.load();assert.equal(calls,0)
  let resolve;g.permissions.can=()=>true;g.EapApi.getWorkflowToolRecovery=()=>new Promise(r=>resolve=r);const pending=s.load();g.props.runKey='another';resolve({entries:[{state:'CONFIRMED'}]});await pending;assert.equal(g.report.value,null)
})
test('missing intent and definite store rejection have distinct labels',()=>{
  const s=scope(state());assert.match(s.stateLabel('UNKNOWN'),/未知.*保留责任/);assert.match(s.stateLabel('NO_COMMIT'),/原事务明确未提交/)
})
