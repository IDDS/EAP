import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import {routeStubs} from './learning-test-support.mjs'
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function inspection(overrides={}){return {runId:3,status:'UNKNOWN',evidenceDigest:'a'.repeat(64),blockers:[],decision:null,decisionCurrent:false,...overrides}}
function mount(api={}){
 const source=readFileSync(new URL('../src/components/OptimizationUnknownReview.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText
 const props={runId:3},permission={state:{user:{subject:'a'}},can:()=>true},watchers=[];let unmount
 const routing=routeStubs()
 const values={...routing.values,ref:value=>({value}),watch:(_s,fn)=>watchers.push(fn),onBeforeUnmount:fn=>unmount=fn,defineProps:()=>props,usePermissions:()=>permission,OptimizationApi:{review:async()=>inspection(),...api}}
 const s=new Function(...Object.keys(values),code+';return {review,accepted,uncertain,error,busy,loading,load,decide}')(...Object.values(values))
 return {...s,props,permission,routing,reset:()=>watchers.forEach(fn=>fn()),unmount:()=>unmount?.()}
}
test('governance stop requires capability explicit acknowledgment and clear original evidence',async()=>{
 let writes=0;const s=mount({decide:async()=>{writes++;return inspection({decision:{decision:'STOP_NO_REPLAY'},decisionCurrent:true})}});await s.load();await s.decide();assert.equal(writes,0)
 s.accepted.value=true;s.permission.can=cap=>cap==='viewConsole';await s.decide();assert.equal(writes,0);s.permission.can=()=>true;s.review.value.blockers=['BUDGET_EVIDENCE_INCOMPLETE'];await s.decide();assert.equal(writes,0);s.review.value.blockers=[];await s.decide();assert.equal(writes,1);assert.equal(s.review.value.status,'UNKNOWN')
})
test('unknown governance receipt reuses the exact frozen decision and prevents concurrent writes',async()=>{
 const first=deferred(),bodies=[];const s=mount({decide:(_id,body)=>{bodies.push(JSON.parse(JSON.stringify(body)));return first.promise}});await s.load();s.accepted.value=true;const run=s.decide();await s.decide();assert.equal(bodies.length,1);first.reject(Error('lost'));await run;assert.equal(s.uncertain.value,true)
 await s.load();assert.equal(s.accepted.value,true);s.review.value.evidenceDigest='b'.repeat(64);await s.decide();assert.equal(bodies.length,2);assert.deepEqual(bodies[0],bodies[1])
})
test('reading a persisted stop reconciles lost acknowledgment without another write',async()=>{const s=mount({decide:async()=>{throw Error('lost')}});await s.load();s.accepted.value=true;await s.decide();assert.equal(s.uncertain.value,true);s.review.value=undefined;/* use a new server response */const approved=mount({review:async()=>inspection({decision:{decision:'STOP_NO_REPLAY'},decisionCurrent:true})});approved.uncertain.value=true;await approved.load();assert.equal(approved.uncertain.value,false);assert.equal(approved.review.value.decisionCurrent,true)})
test('stale evidence rejection clears the decision and requires a new inspection',async()=>{const s=mount({decide:async()=>{throw {response:{status:409}}}});await s.load();s.accepted.value=true;await s.decide();assert.equal(s.review.value,undefined);assert.equal(s.accepted.value,false);assert.equal(s.uncertain.value,false)})
test('permission change ignores late audit response and clears prior evidence',async()=>{const p=deferred();const s=mount({review:()=>p.promise});const work=s.load();s.permission.state.user={subject:'b'};s.permission.can=()=>false;s.reset();p.resolve(inspection({decision:{principalDigest:'previous'}}));await work;assert.equal(s.review.value,undefined);assert.equal(s.accepted.value,false)})
test('task changes and unmount prevent a late stop receipt from populating another task',async()=>{const p=deferred();const s=mount({decide:()=>p.promise});await s.load();s.accepted.value=true;const work=s.decide();s.props.runId=9;s.permission.can=()=>false;s.reset();s.unmount();p.resolve(inspection({decision:{decision:'STOP_NO_REPLAY'}}));await work;assert.equal(s.review.value,undefined);assert.equal(s.busy.value,false)})
test('failed original inspection discards prior paid evidence',async()=>{const s=mount({review:async()=>{throw Error('failed')}});s.review.value=inspection({modelCalls:77});await s.load();assert.equal(s.review.value,undefined);assert.equal(s.loading.value,false)})

test('record navigation retains pending governance decisions and their original retry key',async()=>{
 const pending=deferred(),bodies=[]
 const s=mount({decide:(_id,body)=>{bodies.push(JSON.parse(JSON.stringify(body)));return pending.promise}})
 await s.load();s.accepted.value=true;const write=s.decide()
 assert.equal(await s.routing.navigate({experimentId:'900'}),false)
 pending.reject(Error('lost receipt'));await write
 assert.equal(await s.routing.navigate({agentId:'71'}),false)
 await s.decide();assert.deepEqual(bodies[0],bodies[1]);assert.match(s.error.value,/治理回执未确认/)
})
