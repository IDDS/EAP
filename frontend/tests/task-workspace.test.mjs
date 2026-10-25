import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'

const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
const settle=()=>new Promise(resolve=>setImmediate(resolve))
function compile(path){return ts.transpileModule(readFileSync(new URL(path,import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,''),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText}
const module={exports:{}}
new Function('exports',ts.transpileModule(readFileSync(new URL('../src/api/taskWorkspace.ts',import.meta.url),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText)(module.exports)
const {executionStatusLabel,executionStatusTone}=module.exports
const run=(id=1,key='run-a',status='SUCCEEDED')=>({workflowId:id,runKey:key,objective:key,status,steps:[{sequence:1,nodeKey:'one',label:'Agent step',taskKey:key+':1'}]})
function mount(t,path,values,expose){
 let unmount;const effects=Vue.effectScope()
 const args={ref:Vue.ref,computed:Vue.computed,watch:Vue.watch,onBeforeUnmount:fn=>{unmount=fn},...values}
 let state;effects.run(()=>{state=new Function(...Object.keys(args),compile(path)+`;return {${expose}}`)(...Object.values(args))})
 t.after(()=>{unmount();effects.stop()});return{...state,unmount}
}
async function workspace(t,{query={workflowId:'1',runKey:'run-a'},api={}}={}){
 const permission=Vue.reactive({state:{user:{subject:'first'}},enabled:true,can(){return this.enabled}})
 const route=Vue.reactive({query}),calls=[]
 const EapApi={listWorkflows:async()=>[{id:1,name:'one'},{id:2,name:'two'}],listWorkflowExecutions:async id=>[run(id,id===1?'run-a':'run-b')],getWorkflowExecution:async(id,key)=>run(id,key),...api}
 for(const [name,fn] of Object.entries(EapApi))EapApi[name]=(...args)=>{calls.push({name,args});return fn(...args)}
 const s=mount(t,'../src/views/TaskWorkspaceView.vue',{usePermissions:()=>permission,useRoute:()=>route,useRouter:()=>({push:async value=>{route.query=value.query}}),EapApi},'execution,runs,workflows,workflowId,runKey,lookupKey,runError,runsError,catalogError,loadingRun,loadingRuns,reload,findRun')
 await settle();return{...s,permission,route,calls}
}
test('opens an exact historical run outside the latest list without submitting work',async t=>{
 const s=await workspace(t,{query:{workflowId:'1',runKey:'old-run'}})
 assert.equal(s.execution.value.runKey,'old-run');assert.equal(s.runs.value[0].runKey,'run-a')
 assert.deepEqual(s.calls.map(c=>c.name),['listWorkflows','listWorkflowExecutions','getWorkflowExecution'])
 s.lookupKey.value=' another-original ';s.findRun();await settle();assert.equal(s.execution.value.runKey,'another-original')
})
test('rapid workflow changes discard late success and failure from the original run/list',async t=>{
 const list=deferred(),detail=deferred()
 const s=await workspace(t,{api:{listWorkflowExecutions:id=>id===1?list.promise:Promise.resolve([run(2,'run-b')]),getWorkflowExecution:(id,key)=>id===1?detail.promise:Promise.resolve(run(id,key))}})
 s.route.query={workflowId:'2',runKey:'run-b'};await settle()
 list.reject(Error('old list'));detail.resolve(run(1));await settle()
 assert.equal(s.execution.value.workflowId,2);assert.equal(s.runs.value[0].workflowId,2);assert.equal(s.runsError.value,'');assert.equal(s.runError.value,'');assert.equal(s.loadingRun.value,false)
})
test('late errors cannot overwrite a newer task and failed exact lookup never substitutes latest success',async t=>{
 const old=deferred();const s=await workspace(t,{api:{getWorkflowExecution:(id,key)=>key==='run-a'?old.promise:Promise.reject(Error('not found'))}})
 s.route.query={workflowId:'1',runKey:'absent'};await settle();assert.equal(s.execution.value,null);assert.match(s.runError.value,/原执行读取失败/)
 old.reject(Error('old'));await settle();assert.equal(s.execution.value,null);assert.match(s.runError.value,/未展示其他执行/)
})
test('account switch and permission loss synchronously erase task data and invalidate pending reads',async t=>{
 const pending=deferred();let n=0
 const s=await workspace(t,{api:{getWorkflowExecution:()=>++n===1?Promise.resolve(run()):pending.promise}})
 assert.ok(s.execution.value);s.permission.state.user={subject:'second'};assert.equal(s.execution.value,null);await settle()
 s.permission.enabled=false;assert.equal(s.runs.value.length,0);pending.resolve(run());await settle();assert.equal(s.execution.value,null)
 const count=s.calls.length;await s.reload();assert.equal(s.calls.length,count)
})
test('invalid workflow and mismatched response are rejected and unmount suppresses responses',async t=>{
 const pending=deferred();const s=await workspace(t,{api:{getWorkflowExecution:()=>pending.promise}})
 s.route.query={workflowId:'999',runKey:'run-a'};assert.equal(s.execution.value,null);assert.match(s.runsError.value,/不在当前可见目录/)
 pending.resolve(run());await settle();assert.equal(s.execution.value,null)
 const wrong=await workspace(t,{api:{getWorkflowExecution:async()=>run(2,'unrelated')}});assert.equal(wrong.execution.value,null);assert.ok(wrong.runError.value)
 const late=deferred();const gone=await workspace(t,{api:{getWorkflowExecution:()=>late.promise}});gone.unmount();late.resolve(run());await settle();assert.equal(gone.execution.value,null)
})
test('malformed deep link never falls back to an unrelated latest execution',async t=>{
 const s=await workspace(t,{query:{workflowId:'1',runKey:['run-a','run-b']}})
 assert.equal(s.execution.value,null);assert.match(s.runError.value,/编号无效/);assert.deepEqual(s.calls.map(c=>c.name),['listWorkflows'])
})
test('unknown status is distinct from failure',()=>{
 assert.match(executionStatusLabel('EXECUTION_UNKNOWN'),/结果未知/);assert.equal(executionStatusTone('EXECUTION_UNKNOWN'),'warning');assert.notEqual(executionStatusLabel('FAILED'),executionStatusLabel('EXECUTION_UNKNOWN'))
})
const entry=(id=2,kind='RUNTIME_TASK')=>({operation:{id,operationKey:'original-'+id,entryKind:kind},nodeSequence:1,nodeKey:null})
const page=(entries=[entry()],nextCursor=null,id=1,key='run-a')=>({contract:'WORKFLOW_OBSERVATIONS_V1',workflowId:id,runKey:key,entries,nextCursor})
async function evidence(t,{coverage,operation}={}){
 const permission=Vue.reactive({state:{user:{subject:'first'}},enabled:true,can(){return this.enabled}}),props=Vue.reactive({execution:run()}),calls=[]
 const s=mount(t,'../src/components/workflow/WorkflowEvidence.vue',{defineProps:()=>props,usePermissions:()=>permission,getWorkflowEvidence:async(...args)=>{calls.push(['page',...args]);return coverage?coverage(...args):page()},getWorkflowEvidenceDetail:async(...args)=>{calls.push(['detail',...args]);return operation?operation(...args):{workflowId:1,runKey:'run-a',detail:{operation:entry().operation,logs:[],costs:[]}}}},'report,operations,entries,detail,error,detailError,detailLoading,traceId,select,load,loadMore,loading,stepLabel')
 await settle();return{...s,props,permission,calls}
}
test('scoped evidence includes recorded controls and AX without relying on progress task keys',async t=>{
 const s=await evidence(t,{coverage:async()=>page([entry(3,'WORKFLOW_CONTROL'),entry(2,'AX_LLM')])})
 assert.deepEqual(s.operations.value.map(o=>o.id),[3,2]);assert.equal(s.stepLabel(s.operations.value[0]),'Agent step')
 await s.select({id:9,operationKey:'unrelated'});assert.equal(s.calls.length,1)
})
test('pagination appends older evidence, serializes clicks and preserves the original cursor after failure',async t=>{
 const pending=deferred();let count=0
 const s=await evidence(t,{coverage:async(id,key,cursor)=>{if(!cursor)return page([entry(4),entry(3)],'cursor-a');if(++count===1)return pending.promise;return page([entry(2),entry(1)])}})
 const loading=s.loadMore();await s.loadMore();assert.equal(count,1)
 pending.reject(Error('temporary'));await loading;assert.deepEqual(s.operations.value.map(o=>o.id),[4,3]);assert.equal(s.report.value.nextCursor,'cursor-a');assert.ok(s.error.value)
 await s.loadMore();assert.deepEqual(s.operations.value.map(o=>o.id),[4,3,2,1]);assert.equal(s.report.value.nextCursor,null);assert.equal(s.error.value,'')
 await s.loadMore();assert.equal(count,2)
})
test('refresh and task changes invalidate old pages and in-flight original details',async t=>{
 const pending=deferred(),detail=deferred();let count=0
 const s=await evidence(t,{coverage:async(id,key,cursor)=>cursor?pending.promise:page([entry(2)],'cursor-a',id,key),operation:()=>detail.promise})
 const oldPage=s.loadMore(),oldDetail=s.select(s.operations.value[0]);await s.load();assert.equal(s.detail.value,null)
 pending.resolve(page([entry(1)]));detail.resolve({workflowId:1,runKey:'run-a',detail:{operation:entry().operation,logs:[],costs:[]}});await Promise.all([oldPage,oldDetail]);assert.deepEqual(s.operations.value.map(o=>o.id),[2]);assert.equal(s.detail.value,null)
 s.props.execution=run(2,'run-b');assert.equal(s.report.value,null);await settle();assert.equal(s.report.value.runKey,'run-b')
})
test('invalid scope, duplicate or reversed pages and stuck cursors are rejected without replacing evidence',async t=>{
 const bad=await evidence(t,{coverage:async()=>page([entry()],null,2,'other')});assert.equal(bad.report.value,null);assert.ok(bad.error.value)
 for(const invalid of [page([entry(2)]),page([entry(1),entry(3)]),page([entry(1)],'cursor-a'),page([],'more')]){
  const s=await evidence(t,{coverage:async(id,key,cursor)=>cursor?invalid:page([entry(2)],'cursor-a')});await s.loadMore();assert.deepEqual(s.operations.value.map(o=>o.id),[2]);assert.ok(s.error.value)
 }
})
test('detail must match both execution scope and original operation identity',async t=>{
 for(const response of [{workflowId:2,runKey:'run-a',detail:{operation:entry().operation}},{workflowId:1,runKey:'other',detail:{operation:entry().operation}},{workflowId:1,runKey:'run-a',detail:{operation:{...entry().operation,operationKey:'other'}}}]){
  const s=await evidence(t,{operation:async()=>response});await s.select(s.operations.value[0]);assert.equal(s.detail.value,null);assert.ok(s.detailError.value)
 }
})
test('permission loss, account changes and unmount erase data and suppress late pages',async t=>{
 for(const change of [s=>{s.permission.enabled=false},s=>{s.permission.state.user={subject:'second'}},s=>s.unmount()]){
  const pending=deferred();const s=await evidence(t,{coverage:()=>pending.promise});change(s);pending.resolve(page());await settle();assert.equal(s.detail.value,null)
  if(s.permission.enabled&&s.permission.state.user.subject==='second')assert.equal(s.calls.length,2)
  else assert.equal(s.report.value,null)
 }
})
test('control reference is not linked as an observed Trace and absent costs remain absent',async t=>{
 const control={...entry(2,'WORKFLOW_CONTROL').operation,traceId:'a'.repeat(32)}
 const s=await evidence(t,{coverage:async()=>page([{...entry(),operation:control}]),operation:async()=>({workflowId:1,runKey:'run-a',detail:{operation:control,logs:[],costs:[]}})})
 await s.select(s.operations.value[0]);assert.equal(s.traceId.value,'');assert.deepEqual(s.detail.value.costs,[])
})
