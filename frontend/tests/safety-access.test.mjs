import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'

const session={id:17,sessionKey:'registered-session',rootAgentId:42,objective:'registered objective',status:'ACTIVE'}
const overview={sessionCount:1,eventCount:0,denyCount:0,askCount:0,criticalAlertCount:0,recentDecisions:[]}
const settle=()=>new Promise(resolve=>setImmediate(resolve))
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
async function mount(t,{capabilities={},api={}}={}){
 const permission=Vue.reactive({state:{user:{mode:'JWT',subject:'first'}},capabilities:{viewConsole:true,manageSafety:true,...capabilities},can(name){return this.capabilities[name]===true}})
 const requests=[],messages=[];let unmount
 const EapApi={getSafetySummary:async()=>({...overview}),listSafetySessions:async()=>[{...session}],evaluateSafetyEvent:async()=>({decision:{decision:'ALLOW'}}),createSafetySession:async()=>{throw Error('Must not implicitly create or overwrite sessions')},...api}
 for(const [name,fn] of Object.entries(EapApi))EapApi[name]=(...args)=>{requests.push({name,args});return fn(...args)}
 const source=readFileSync(new URL('../src/views/SafetyView.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText
 const bindings={ref:Vue.ref,reactive:Vue.reactive,computed:Vue.computed,watch:Vue.watch,onBeforeUnmount:fn=>{unmount=fn},usePermissions:()=>permission,EapApi,ElMessage:{success:m=>messages.push(m)},Connection:{},Lock:{},Refresh:{},Warning:{}}
 const effects=Vue.effectScope();let state
 effects.run(()=>{state=new Function(...Object.keys(bindings),code+';return {form,sessions,summary,loading,running,writeUncertain,pageError,formError,canManage,load,evaluate,acknowledgeUncertain}')( ...Object.values(bindings))})
 t.after(()=>{unmount();effects.stop()});await settle()
 return {...state,permission,requests,messages,EapApi,unmount}
}
function fill(s){Object.assign(s.form,{sessionKey:session.sessionKey,action:' read ',target:' sandbox/resource ',payloadSummary:'synthetic'})}
function writes(s){return s.requests.filter(r=>!['getSafetySummary','listSafetySessions'].includes(r.name))}

test('safety missing or false /me capability denies direct writes even with an admin role label',async t=>{
 for(const value of [undefined,false]){
  const s=await mount(t,{capabilities:{manageSafety:value}});s.permission.state.user.roles=['PLATFORM_ADMIN'];fill(s)
  await s.evaluate();s.writeUncertain.value=true;s.acknowledgeUncertain()
  assert.deepEqual(writes(s),[]);assert.equal(s.writeUncertain.value,true)
 }
})
test('safety evaluation uses the selected registered session and its actual agent without a session upsert',async t=>{
 const s=await mount(t);assert.equal(s.form.sessionKey,'');assert.equal(s.form.action,'');fill(s);await s.evaluate()
 assert.equal(writes(s).length,1);assert.deepEqual(writes(s)[0],{name:'evaluateSafetyEvent',args:[{sessionKey:session.sessionKey,eventType:'TOOL_CALL',action:'read',target:'sandbox/resource',payloadSummary:'synthetic',agentId:42}]});assert.deepEqual(s.messages,['决策：ALLOW'])
})
test('unbound safety sessions remain unbound rather than inheriting a demo agent',async t=>{
 const s=await mount(t,{api:{listSafetySessions:async()=>[{...session,rootAgentId:null}]}});fill(s);await s.evaluate();assert.equal(writes(s)[0].args[0].agentId,undefined)
})
test('unregistered sessions or blank required fields cannot submit',async t=>{
 const s=await mount(t);fill(s);s.form.sessionKey='forged';await s.evaluate();fill(s);s.form.action=' ';await s.evaluate();fill(s);s.form.target='';await s.evaluate()
 assert.deepEqual(writes(s),[]);assert.ok(s.formError.value)
})
test('pending safety writes suppress duplicate evaluation and acknowledgement',async t=>{
 const pending=deferred(),s=await mount(t,{api:{evaluateSafetyEvent:()=>pending.promise}});fill(s);const first=s.evaluate();await s.evaluate();s.acknowledgeUncertain();assert.equal(writes(s).length,1);assert.equal(s.running.value,true)
 pending.resolve({decision:{decision:'ASK'}});await first;assert.equal(s.running.value,false)
})
test('an old safety write cannot unlock or notify a new identity request',async t=>{
 const first=deferred(),second=deferred();let count=0
 const s=await mount(t,{api:{evaluateSafetyEvent:()=>++count===1?first.promise:second.promise}});fill(s);const old=s.evaluate()
 s.permission.state.user={mode:'JWT',subject:'second'};await settle();assert.equal(s.form.payloadSummary,'');fill(s);const current=s.evaluate()
 first.resolve({decision:{decision:'DENY'}});await old;assert.equal(s.running.value,true);assert.deepEqual(s.messages,[])
 second.resolve({decision:{decision:'ALLOW'}});await current;assert.equal(s.running.value,false);assert.deepEqual(s.messages,['决策：ALLOW'])
})
test('revoking safety capability clears form and ignores late write failure',async t=>{
 const pending=deferred(),s=await mount(t,{api:{evaluateSafetyEvent:()=>pending.promise}});fill(s);const old=s.evaluate();s.permission.capabilities.manageSafety=false;await settle()
 pending.reject(Error('synthetic'));await old;assert.equal(s.form.sessionKey,'');assert.equal(s.formError.value,'');assert.equal(s.writeUncertain.value,false);assert.deepEqual(s.messages,[])
})
test('revoking console access prevents late safety records from returning',async t=>{
 const pending=deferred(),s=await mount(t);s.EapApi.listSafetySessions=()=>pending.promise;const old=s.load();s.permission.capabilities.viewConsole=false
 pending.resolve([session]);await old;assert.deepEqual(s.sessions.value,[]);assert.equal(s.summary.value.sessionCount,0);assert.equal(s.loading.value,false)
})
test('a stale safety load cannot replace a newer successful refresh',async t=>{
 const pending=deferred(),s=await mount(t);s.EapApi.listSafetySessions=()=>pending.promise;const old=s.load();s.EapApi.listSafetySessions=async()=>[{...session,sessionKey:'newer'}];await s.load();pending.reject(Error('old failure'));await old
 assert.equal(s.sessions.value[0].sessionKey,'newer');assert.equal(s.pageError.value,'');assert.equal(s.loading.value,false)
})
test('unknown evaluation does not retry and requires explicit acknowledgement',async t=>{
 const s=await mount(t,{api:{evaluateSafetyEvent:async()=>{throw Error('response lost')}}});fill(s);await s.evaluate();await s.evaluate();await s.load();await s.evaluate()
 assert.equal(writes(s).length,1);assert.equal(s.writeUncertain.value,true);assert.match(s.formError.value,/未确认/)
 s.acknowledgeUncertain();await s.evaluate();assert.equal(writes(s).length,2)
})
test('failed safety reads clear old records and allow a later refresh',async t=>{
 const s=await mount(t);s.EapApi.listSafetySessions=async()=>{throw Error('unavailable')};await s.load();assert.deepEqual(s.sessions.value,[]);assert.equal(s.summary.value.sessionCount,0);assert.ok(s.pageError.value)
 s.EapApi.listSafetySessions=async()=>[session];await s.load();assert.equal(s.sessions.value.length,1);assert.equal(s.pageError.value,'')
})
test('unmounted safety page ignores late evaluation responses',async t=>{
 const pending=deferred(),s=await mount(t,{api:{evaluateSafetyEvent:()=>pending.promise}});fill(s);const old=s.evaluate();s.unmount();pending.resolve({decision:{decision:'ALLOW'}});await old;assert.deepEqual(s.messages,[])
})
