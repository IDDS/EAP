import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'

const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
const settle=()=>new Promise(resolve=>setImmediate(resolve))
const row=(key='original',kind='WORKFLOW')=>({kind,runKey:key,workflowId:1,agentId:1,instanceName:'original-instance',title:'task',status:'SUCCEEDED',requester:'alice',createdAt:'2026-01-01T00:00:00Z'})
const page=(params,items=[row()],total=45)=>({contract:'EXECUTION_CATALOG_V1',items,total,page:params.page,size:params.size,observedAt:'2026-01-01T00:00:00Z',ownChatsIncluded:true})
async function mount(t,{list,receipt}={}) {
  const permission=Vue.reactive({state:{user:{subject:'alice'}},view:true,operate:true,can(name){return name==='viewConsole'?this.view:this.operate}}),calls=[]
  let unmount;const scope=Vue.effectScope()
  const source=readFileSync(new URL('../src/components/workflow/ExecutionCatalog.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
  const args={ref:Vue.ref,reactive:Vue.reactive,computed:Vue.computed,watch:Vue.watch,onBeforeUnmount:fn=>{unmount=fn},usePermissions:()=>permission,listExecutions:async p=>{calls.push({...p});return list?list(p):page(p)},getData:receipt||(()=>Promise.reject(Error('unexpected')))}
  let state;scope.run(()=>{state=new Function(...Object.keys(args),ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText+';return {filters,report,loading,error,fromLocal,untilLocal,search,reset,changePage,openChat,chatReceipt,selectedChat,chatError}')(...Object.values(args))})
  t.after(()=>{unmount();scope.stop()});await settle();return {...state,permission,calls,unmount}
}
test('history pagination uses applied filters until a new search is submitted',async t=>{
  const s=await mount(t);s.filters.query='unsubmitted';s.changePage(1);await settle()
  assert.equal(s.calls.at(-1).query,'');assert.equal(s.report.value.page,2)
  s.search();await settle();assert.equal(s.calls.at(-1).query,'unsubmitted');assert.equal(s.report.value.page,1)
})
test('bounded history stops paging at the reported window and permits narrower queries',async t=>{
  const s=await mount(t,{list:p=>({...page(p,[row()],10001),windowLimit:20})})
  s.changePage(1);await settle();assert.equal(s.calls.length,1)
  s.filters.query='narrow';s.search();await settle();assert.equal(s.calls.length,2);assert.equal(s.calls.at(-1).page,1)
})
test('new searches suppress stale success and errors and do not retain old rows',async t=>{
  const a=deferred(),b=deferred();let count=0
  const s=await mount(t,{list:p=>++count===1?a.promise:count===2?b.promise:page(p,[row('new')])})
  s.filters.status='UNKNOWN';s.search();s.filters.status='FAILED';s.search();await settle()
  a.resolve(page(s.calls[0],[row('old')]));b.reject(Error('old'));await settle()
  assert.equal(s.report.value.items[0].runKey,'new');assert.equal(s.error.value,'')
})
test('account and capability switches clear records immediately and invalidate pending chat reads',async t=>{
  const wait=deferred();const s=await mount(t,{list:p=>page(p,[row('original','CHAT')]),receipt:()=>wait.promise})
  s.openChat(s.report.value.items[0]);assert.ok(s.selectedChat.value)
  s.permission.state.user={subject:'bob'};assert.equal(s.report.value,null);assert.equal(s.selectedChat.value,null)
  wait.resolve({requestKey:'original',status:'SUCCEEDED',notice:'old'});await settle();assert.equal(s.chatReceipt.value,null)
  s.permission.view=false;assert.equal(s.report.value,null);await settle();assert.equal(s.loading.value,false)
})
test('chat lookup uses original identity, rejects unrelated rows and mismatched receipts',async t=>{
  const paths=[];const s=await mount(t,{list:p=>page(p,[row('original','CHAT')]),receipt:async path=>{paths.push(path);return {requestKey:'different',status:'SUCCEEDED'}}})
  await s.openChat(row('unrelated','CHAT'));assert.equal(paths.length,0)
  await s.openChat(s.report.value.items[0]);assert.match(paths[0],/instances\/original-instance\/chat\/original$/);assert.equal(s.chatReceipt.value,null);assert.ok(s.chatError.value)
})
test('invalid date range prevents queries and clears misleading previous records',async t=>{
  const s=await mount(t);s.fromLocal.value='2026-01-02T10:00';s.untilLocal.value='2026-01-01T10:00';s.search()
  assert.equal(s.calls.length,1);assert.equal(s.report.value,null);assert.match(s.error.value,/结束时间/)
})
test('inconsistent pages and chats without capability are rejected',async t=>{
  const s=await mount(t,{list:p=>page({...p,page:99})});assert.equal(s.report.value,null);assert.ok(s.error.value)
  const chat=await mount(t,{list:p=>page(p,[row('original','CHAT')])});chat.permission.operate=false;await settle();assert.equal(chat.report.value,null);assert.ok(chat.error.value)
})
test('unmount invalidates pending reads',async t=>{
  const wait=deferred();const s=await mount(t,{list:()=>wait.promise});s.unmount();wait.resolve(page(s.calls[0]));await settle();assert.equal(s.report.value,null)
})
function moduleAt(path) {
  const exports={};new Function('exports',ts.transpileModule(readFileSync(new URL(path,import.meta.url),'utf8').replace(/^import .*$/gm,''),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText)(exports);return exports
}
const { runtimeDiagnostics }=moduleAt('../src/components/trace/runtime-diagnostics.ts')
test('runtime diagnostics do not invent missing timings or disclose arbitrary producer strings',()=>{
  assert.equal(runtimeDiagnostics('{bad'),null)
  assert.equal(runtimeDiagnostics(JSON.stringify({'eap.runtime.stage':'PRIVATE_ERROR'})),null)
  const result=runtimeDiagnostics(JSON.stringify({'eap.runtime.stage':'READ_PROOF','eap.runtime.deadline_exceeded':'true','eap.runtime.ready_wait_ms':'0','private':'SECRET','eap.runtime.proof_read_ms':null}))
  assert.equal(result.deadline,true);assert.equal(result.timings[1].value,0);assert.equal(result.timings[3].value,null);assert.deepEqual(result.startup,[]);assert.ok(!JSON.stringify(result).includes('SECRET'))
  for(const value of [true,-1,60001,'',1.5,'NaN']) assert.equal(runtimeDiagnostics(JSON.stringify({'eap.runtime.stage':'READ_PROOF','eap.runtime.ready_wait_ms':value})).timings[1].value,null)
})
test('next-step guidance preserves uncertainty and separates structure from business success',()=>{
  const source=readFileSync(new URL('../src/components/workflow/ExecutionNextStep.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
  const props=Vue.reactive({execution:{status:'EXECUTION_UNKNOWN',steps:[]}})
  const state=new Function('computed','defineProps',ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText+';return {validationLabel,nextAction}')(Vue.computed,()=>props)
  assert.match(state.nextAction.value,/暂勿重发/);assert.equal(state.validationLabel.value,'未记录验收')
  props.execution={status:'SUCCEEDED',steps:[{resultValidation:{status:'PASSED'}}]};assert.match(state.nextAction.value,/不能替代业务验收/)
  props.execution={status:'FAILED',steps:[{resultValidation:{status:'REJECTED'}}]};assert.match(state.nextAction.value,/费用保留/);assert.match(state.validationLabel.value,/不合格/)
})
