import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'
const source=readFileSync(new URL('../src/components/workflow/ExecutionConfiguration.vue',import.meta.url),'utf8')
const script=ts.transpileModule(source.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,''),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
const settle=()=>new Promise(resolve=>setImmediate(resolve))
const binding=()=>({contract:'DISPATCH_BINDING_V1',instanceKey:'original/actor',actorUid:'original-uid',actorTemplate:'original/template',protocol:'SUBSTRATE',deploymentKey:'original-deployment',deploymentVersion:3,sourceType:'RUNNER',imageDigest:'sha256:'+'a'.repeat(64),capturedAt:'2026-10-19T00:00:00Z'})
const report=(id=1,key='original')=>({contract:'EXECUTION_CONFIGURATION_V1',workflowId:id,runKey:key,submittedAt:'2026-10-19T00:00:00Z',nodes:[{sequence:0,nodeKey:'task',label:'Original task',nodeType:'AGENT',agentId:8,nodeVersion:7,executionBackend:'RUNTIME',bindingState:'DEPLOYMENT_RECORDED',runtime:binding()}]})
async function mount(t,request=async(id,key)=>report(id,key)){
 const permission=Vue.reactive({state:{user:{subject:'first'}},enabled:true,can(){return this.enabled}}),props=Vue.reactive({execution:{workflowId:1,runKey:'original'}}),calls=[]
 let unmount,state;const scope=Vue.effectScope()
 const args={ref:Vue.ref,watch:Vue.watch,onBeforeUnmount:fn=>{unmount=fn},defineProps:()=>props,usePermissions:()=>permission,getExecutionConfiguration:(...values)=>{calls.push(values);return request(...values)}}
 scope.run(()=>{state=new Function(...Object.keys(args),script+';return {report,loading,error,load}')( ...Object.values(args))})
 t.after(()=>{unmount();scope.stop()});await settle();return{...state,permission,props,calls,unmount}
}
test('reads original configuration by exact identity and preserves missing historical versions',async t=>{
 const s=await mount(t);assert.deepEqual(s.calls,[[1,'original']]);assert.equal(s.report.value.nodes[0].runtime.deploymentVersion,3)
 const old=report();old.nodes[0]={...old.nodes[0],runtime:null,nodeVersion:null,bindingState:'NOT_RECORDED'}
 const legacy=await mount(t,async()=>old);assert.equal(legacy.report.value.nodes[0].nodeVersion,null);assert.equal(legacy.report.value.nodes[0].runtime,null)
})
test('switching tasks rejects late success and error from the previous execution',async t=>{
 for(const fail of [true,false]){
  const pending=deferred();const s=await mount(t,(id,key)=>id===1?pending.promise:Promise.resolve(report(id,key)))
  s.props.execution={workflowId:2,runKey:'new'};await settle()
  if(fail)pending.reject(Error('old'));else pending.resolve(report())
  await settle();assert.equal(s.report.value.runKey,'new');assert.equal(s.error.value,'')
 }
})
test('account changes, permission loss and unmount clear data and invalidate requests',async t=>{
 for(const change of [s=>{s.permission.state.user={subject:'next'}},s=>{s.permission.enabled=false},s=>s.unmount()]){
  let n=0;const pending=deferred();const s=await mount(t,()=>++n===1?Promise.resolve(report()):pending.promise)
  const read=s.load();change(s);assert.equal(s.report.value,null)
  if(s.permission.state.user.subject==='next'){s.permission.enabled=false;assert.equal(s.report.value,null)}
  pending.resolve(report());await read;await settle();assert.equal(s.report.value,null)
 }
})
test('refresh errors erase stale details and a retry uses the same original key',async t=>{
 let n=0;const s=await mount(t,async()=>{if(++n===2)throw Error('unavailable');return report()})
 await s.load();assert.equal(s.report.value,null);assert.match(s.error.value,/未使用当前配置/)
 await s.load();assert.equal(s.error.value,'');assert.equal(s.report.value.runKey,'original');assert.deepEqual(s.calls,[[1,'original'],[1,'original'],[1,'original']])
})
test('cross-run, duplicate nodes and inconsistent runtime records fail closed',async t=>{
 const invalid=[report(2),report(1,'other'),{...report(),contract:'other'}]
 const duplicate=report();duplicate.nodes.push({...duplicate.nodes[0],sequence:1});invalid.push(duplicate)
 for(const patch of [{sequence:1},{runtime:null},{executionBackend:'AX'},{bindingState:'NOT_RECORDED'},{runtime:{...binding(),deploymentVersion:null}},{runtime:{...binding(),capturedAt:'invalid'}}]){const value=report();Object.assign(value.nodes[0],patch);invalid.push(value)}
 for(const value of invalid){const s=await mount(t,async()=>value);assert.equal(s.report.value,null);assert.ok(s.error.value)}
})
test('refresh invalidates an older pending read without leaving the loading state stuck',async t=>{
 const pending=deferred();let n=0;const s=await mount(t,()=>++n===1?pending.promise:Promise.resolve(report()))
 await s.load();pending.reject(Error('old'));await settle();assert.equal(s.report.value.nodes[0].runtime.actorUid,'original-uid');assert.equal(s.loading.value,false);assert.equal(s.error.value,'')
})
