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
function state() {return {disposed:false,revision:0,retryPending:false,props:{workflowId:7,runKey:'original'},permissions:{can:()=>true},loading:{value:false},error:{value:''},report:{value:null}}}
function fixture(state='UNKNOWN') {
  const entry={sequence:0,nodeKey:'node',taskKey:'task',rootId:'root',operationId:'original-O',holderKey:'holder',actorUid:'uid',contractVersion:'v1',budgetKey:'budget',state,reason:'reason',queryEpoch:1,operation:null}
  if(state==='CONFIRMED') entry.operation={rootId:'root',operationId:'original-O',state:'COMMITTED',effectSource:'TOOL_STORE_VERIFIED',effectId:'effect',counterValue:1}
  if(state==='NO_COMMIT') entry.operation={rootId:'root',operationId:'original-O',state:'REJECTED',effectSource:'TOOL_STORE_NO_COMMIT'}
  return {contract:'workflow-tool-query-v1',workflowId:7,runKey:'original',mode:'QUERY_ONLY',enrolled:true,responsibility:'query only',entries:[entry]}
}
test('workflow tool recovery reads original run without executing or resuming',async()=>{
  const g=state(),calls=[];g.EapApi={getWorkflowToolRecovery:async(...args)=>{calls.push(args);return fixture()}}
  await scope(g).load();assert.deepEqual(calls,[[7,'original']]);assert.equal(g.report.value.entries[0].operationId,'original-O')
})
test('late workflow recovery evidence cannot reappear after identity clear',async()=>{
  const g=state();let resolve;g.EapApi={getWorkflowToolRecovery:()=>new Promise(r=>resolve=r)};const s=scope(g),pending=s.load();s.clear();resolve({entries:[{state:'CONFIRMED'}]});await pending;assert.equal(g.report.value,null)
})
test('failed workflow recovery read clears old evidence and preserves uncertainty',async()=>{
  const g=state();g.report.value={entries:[{state:'CONFIRMED'}]};g.EapApi={getWorkflowToolRecovery:async()=>{throw Error()}}
  await scope(g).load();assert.equal(g.report.value,null);assert.match(g.error.value,/不能推定.*未提交/)
})
test('temporary read failure clears old evidence but keeps retrying the original pending operation',async()=>{
  const g=state(),calls=[];g.EapApi={getWorkflowToolRecovery:async(...args)=>{calls.push(args);if(calls.length===2)throw Error('temporary');return fixture(calls.length===1?'UNKNOWN':'CONFIRMED')}}
  const s=scope(g);await s.load();await s.load();assert.equal(g.report.value,null);assert.match(g.error.value,/将继续尝试读取/)
  await s.poll();assert.deepEqual(calls,[[7,'original'],[7,'original'],[7,'original']]);assert.equal(g.report.value.entries[0].state,'CONFIRMED');assert.equal(g.error.value,'');assert.equal(g.retryPending,false)
  await s.poll();assert.equal(calls.length,3)
})
test('authorization denial clears evidence and stops automatic retry',async()=>{
  const g=state();let calls=0;g.EapApi={getWorkflowToolRecovery:async()=>{calls++;if(calls===1)return fixture();throw {response:{status:403}}}}
  const s=scope(g);await s.load();await s.load();assert.equal(g.report.value,null);assert.equal(g.retryPending,false);assert.match(g.error.value,/核对权限/);await s.poll();assert.equal(calls,2)
})
test('a slow recovery read is not duplicated by the next polling tick',async()=>{
  const g=state();let calls=0,resolve;g.EapApi={getWorkflowToolRecovery:()=>{calls++;return calls===1?Promise.resolve(fixture('PENDING')):new Promise(r=>resolve=r)}}
  const s=scope(g);await s.load();const pending=s.poll();await s.poll();assert.equal(calls,2);resolve(fixture('CONFIRMED'));await pending;assert.equal(g.retryPending,false)
})
test('identity clear cancels retry responsibility for the previous run',async()=>{
  const g=state();g.retryPending=true;let calls=0;g.EapApi={getWorkflowToolRecovery:async()=>calls++};const s=scope(g);s.clear();await s.poll();assert.equal(g.retryPending,false);assert.equal(calls,0)
})
test('permission denial and run change reject recovery evidence',async()=>{
  const g=state();let calls=0;g.permissions.can=()=>false;g.EapApi={getWorkflowToolRecovery:async()=>calls++};const s=scope(g);await s.load();assert.equal(calls,0)
  let resolve;g.permissions.can=()=>true;g.EapApi.getWorkflowToolRecovery=()=>new Promise(r=>resolve=r);const pending=s.load();g.props.runKey='another';resolve({entries:[{state:'CONFIRMED'}]});await pending;assert.equal(g.report.value,null)
})
test('missing intent and definite store rejection have distinct labels',()=>{
  const s=scope(state());assert.match(s.stateLabel('UNKNOWN'),/未知.*保留责任/);assert.match(s.stateLabel('NO_COMMIT'),/原事务明确未提交/)
})
test('wrong execution, legacy protocol and malformed evidence never become a confirmed outcome',async()=>{
  const variants=[
    r=>delete r.runKey, r=>r.runKey='another', r=>r.workflowId=8, r=>r.contract='other', r=>r.mode='EXECUTE',
    r=>r.entries[0].operation.operationId='another', r=>r.entries[0].operation.effectSource='unverified',
    r=>delete r.entries[0].operation.effectId, r=>r.entries[0].operation.counterValue=Number.MAX_SAFE_INTEGER+1,
    r=>r.entries.push({...r.entries[0]}), r=>r.entries[0].state='UNRECOGNIZED', r=>r.entries=null,
    r=>r.enrolled=false
  ]
  for(const mutate of variants){
    const g=state(),bad=fixture('CONFIRMED');mutate(bad);let calls=0
    g.EapApi={getWorkflowToolRecovery:async()=>{calls++;return calls===1?fixture():bad}}
    const s=scope(g);await s.load();await s.poll();assert.equal(g.report.value,null);assert.equal(g.retryPending,false)
    assert.match(g.error.value,/不匹配.*不能确认/);await s.poll();assert.equal(calls,2)
  }
})
test('only store rejection evidence can establish no commit',async()=>{
  const g=state(),result=fixture('NO_COMMIT');g.EapApi={getWorkflowToolRecovery:async()=>result};const s=scope(g)
  await s.load();assert.equal(g.report.value.entries[0].state,'NO_COMMIT')
  result.entries[0].operation.effectSource='unverified';await s.load();assert.equal(g.report.value,null);assert.match(g.error.value,/不能确认/)
})
