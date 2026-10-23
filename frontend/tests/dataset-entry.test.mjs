import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'
import {deferred,settle,mountEvaluation} from './evaluation-test-support.mjs'

const compile=source=>ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText
const helpers={}
new Function('exports',compile(readFileSync(new URL('../src/evaluation/datasetEntry.ts',import.meta.url),'utf8')))(helpers)
const lookupHelpers={}
new Function('exports',compile(readFileSync(new URL('../src/evaluation/datasetLookup.ts',import.meta.url),'utf8')))(lookupHelpers)
Object.assign(helpers,lookupHelpers)
const evaluator={evaluatorKey:'ACTION_COMPLETION',name:'任务完成',version:'v3',enabled:true}
const valid=()=>({...helpers.newSampleDraft(),evaluatorKey:evaluator.evaluatorKey,inputJson:'{"task":"public test"}',actualJson:'{"completed":true}'})
function mount(t,{api={},can=true}={}){
 const props=Vue.reactive({datasetKey:'DS',evaluators:[evaluator],busy:false})
 const permission=Vue.reactive({state:{user:{subject:'a'}},capabilities:{viewConsole:can,manageEvaluations:can},can(k){return this.capabilities[k]===true}})
 const events=[],messages=[],requests=[],guards=[];let unmount
 const EapApi={createEvaluationDataset:async body=>({...body,id:1}),createEvaluationDatasetItem:async(_key,body)=>({...body,id:1}),lookupEvaluationDataset:async datasetKey=>({kind:'DATASET',datasetKey,state:'NOT_FOUND'}),lookupEvaluationDatasetItem:async(datasetKey,itemKey)=>({kind:'SAMPLE',datasetKey,itemKey,state:'NOT_FOUND'}),...api}
 for(const [name,fn] of Object.entries(EapApi))EapApi[name]=(...args)=>{requests.push({name,args});return fn(...args)}
 const source=readFileSync(new URL('../src/components/evaluation/EvaluationDatasetEditor.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const values={...helpers,...Object.fromEntries(['ref','reactive','computed','watch'].map(k=>[k,Vue[k]])),defineProps:()=>props,defineEmits:()=>((...args)=>events.push(args)),usePermissions:()=>permission,EapApi,onBeforeUnmount:fn=>{unmount=fn},onBeforeRouteLeave:fn=>guards.push(fn),onBeforeRouteUpdate:fn=>guards.push(fn),ElMessage:Object.fromEntries(['success','warning'].map(k=>[k,s=>messages.push(s)]))}
 const effects=Vue.effectScope();let scope
 effects.run(()=>{scope=new Function(...Object.keys(values),compile(source)+';return {open,close,submit,reconcile,finishReview,mode,dataset,sample,error,lookup,locked,sending,uncertain,checking,reviewed,frozen}')( ...Object.values(values))})
 t.after(()=>{unmount();effects.stop()})
 return {...scope,props,permission,requests,events,messages,guards}
}

test('manual dataset payload normalizes key and always starts empty with MANUAL source',()=>{
 const payload=helpers.datasetPayload({...helpers.newDatasetDraft(),datasetKey:' regression-1 ',name:' 回归 ',version:' v2 ',itemCount:99,sourceType:'TRACE'})
 assert.deepEqual(payload,{datasetKey:'REGRESSION-1',name:'回归',description:undefined,version:'v2',itemCount:0,sourceType:'MANUAL'})
 assert.throws(()=>helpers.datasetPayload({...helpers.newDatasetDraft(),name:' '}),/数据集名称/)
})
test('sample payload persists explicit references, version and expected JSON without mutation',()=>{
 const d={...valid(),agentId:'7',traceId:' trace-1 ',spanId:' span-1 ',expectedJson:'{"answer":"ok"}'},original={...d}
 const p=helpers.samplePayload(d,[evaluator]);assert.equal(p.agentId,7);assert.equal(p.traceId,'trace-1');assert.equal(p.spanId,'span-1');assert.equal(p.evaluatorVersion,'v3');assert.deepEqual(p.expected,{answer:'ok'});assert.deepEqual(d,original)
})
for(const [field,value,pattern] of [['inputJson','[1]',/JSON 对象/],['actualJson','null',/JSON 对象/],['inputJson','{',/有效 JSON/],['agentId','9007199254740992',/安全整数/],['agentId','1e3',/安全整数/],['spanId','orphan',/Trace ID/],['itemKey',' ',/样本标识/]]){
 test(`sample rejects invalid ${field} ${value}`,()=>assert.throws(()=>helpers.samplePayload({...valid(),[field]:value},[evaluator]),pattern))
}
test('sample checks UTF-8 byte size, disabled evaluator and required exact-match expectation',()=>{
 assert.throws(()=>helpers.samplePayload({...valid(),inputJson:JSON.stringify({text:'汉'.repeat(22000)})},[evaluator]),/64KB/)
 assert.throws(()=>helpers.samplePayload(valid(),[{...evaluator,enabled:false}]),/可用的评估器/)
 assert.throws(()=>helpers.samplePayload({...valid(),evaluatorKey:'EXACT_MATCH'},[{...evaluator,evaluatorKey:'EXACT_MATCH'}]),/期望结果/)
})
test('editor opens without writes, denies direct submits without capability or during parent writes',async t=>{
 const s=mount(t,{can:false});s.open('dataset');await s.submit();assert.equal(s.mode.value,'');assert.deepEqual(s.requests,[])
 s.permission.capabilities.viewConsole=true;s.permission.capabilities.manageEvaluations=true;s.props.busy=true;s.open('sample');assert.equal(s.mode.value,'')
 s.props.busy=false;s.open('sample');assert.equal(s.mode.value,'sample');assert.deepEqual(s.requests,[])
 s.props.busy=true;Object.assign(s.sample,valid());await s.submit();assert.deepEqual(s.requests,[])
})
test('successful custom dataset selects the saved canonical key without creating a sample or running evaluation',async t=>{
 const s=mount(t);s.open('dataset');Object.assign(s.dataset,{datasetKey:'custom',name:'人工集'});await s.submit()
 assert.equal(s.requests.length,1);assert.equal(s.requests[0].name,'createEvaluationDataset');assert.deepEqual(s.events.at(-1),['saved','CUSTOM']);assert.equal(s.locked.value,false)
})
test('sample double click freezes one payload and never invokes a run or annotation API',async t=>{
 const pending=deferred(),s=mount(t,{api:{createEvaluationDatasetItem:()=>pending.promise}})
 s.open('sample');Object.assign(s.sample,valid());const originalKey=s.sample.itemKey,first=s.submit();await s.submit()
 assert.equal(s.requests.length,1);assert.equal(s.locked.value,true);assert.ok(s.guards.every(fn=>fn()===false))
 s.sample.inputJson='{"mutated":true}';assert.deepEqual(s.requests[0].args[1].input,{task:'public test'})
 pending.resolve({itemKey:originalKey,evaluatorKey:evaluator.evaluatorKey});await first
 assert.equal(s.locked.value,false);assert.deepEqual(s.events.at(-1),['saved','DS'])
})
test('lost sample receipt retains original key, never reposts, and exact absent lookup cannot prove failure',async t=>{
 const s=mount(t,{api:{createEvaluationDatasetItem:async()=>{throw Error('lost reply')}}})
 s.open('sample');Object.assign(s.sample,valid());const key=s.sample.itemKey;await s.submit();s.close();s.open('dataset');await s.submit()
 assert.equal(s.uncertain.value,true);assert.equal(s.mode.value,'sample');assert.equal(s.sample.itemKey,key);assert.equal(s.requests.length,1)
 s.props.datasetKey='CHANGED';await s.reconcile();assert.deepEqual(s.requests.at(-1).args,['DS',key]);assert.match(s.lookup.value,/不能据此确认保存失败/)
 s.finishReview();assert.equal(s.locked.value,true);s.reviewed.value=true;s.finishReview();assert.equal(s.locked.value,false);assert.equal(s.requests.filter(r=>r.name==='createEvaluationDatasetItem').length,1)
})
test('matching lookup reports presence only, not equality with the submitted content',async t=>{
 let key
 const s=mount(t,{api:{createEvaluationDatasetItem:async(_d,b)=>{key=b.itemKey;throw Error('timeout')},lookupEvaluationDatasetItem:async datasetKey=>({kind:'SAMPLE',datasetKey,itemKey:key,state:'FOUND',recordId:501})}})
 s.open('sample');Object.assign(s.sample,valid());await s.submit();await s.reconcile();assert.match(s.lookup.value,/不能据此证明内容/);assert.equal(s.uncertain.value,true)
})
test('definitive rejection retains editable draft; malformed success remains uncertain',async t=>{
 const s=mount(t,{api:{createEvaluationDataset:async()=>{throw {response:{status:400}}}}})
 s.open('dataset');s.dataset.name='人工集';const key=s.dataset.datasetKey;await s.submit();assert.equal(s.locked.value,false);assert.equal(s.dataset.datasetKey,key);assert.match(s.error.value,/服务器拒绝/)
 const bad=mount(t,{api:{createEvaluationDataset:async()=>({datasetKey:'WRONG'})}});bad.open('dataset');bad.dataset.name='人工集';await bad.submit();assert.equal(bad.uncertain.value,true)
})
test('permission loss clears sample text and late submit does not publish success or unlock a newer submit',async t=>{
 const old=deferred(),next=deferred();let count=0
 const s=mount(t,{api:{createEvaluationDatasetItem:()=>++count===1?old.promise:next.promise}})
 s.open('sample');Object.assign(s.sample,valid());const key=s.sample.itemKey,first=s.submit()
 s.permission.state.user={subject:'b'};assert.equal(s.sample.inputJson,'');assert.equal(s.mode.value,'')
 s.open('sample');Object.assign(s.sample,valid());const nextKey=s.sample.itemKey,second=s.submit()
 old.resolve({itemKey:key,evaluatorKey:evaluator.evaluatorKey});await first;assert.equal(s.sending.value,true);assert.equal(s.messages.length,0)
 next.resolve({itemKey:nextKey,evaluatorKey:evaluator.evaluatorKey});await second;assert.equal(s.messages.length,1)
})
test('late reconciliation after capability revoke cannot expose previous lookup result',async t=>{
 const pending=deferred(),s=mount(t,{api:{createEvaluationDataset:async()=>{throw Error('offline')},lookupEvaluationDataset:()=>pending.promise}})
 s.open('dataset');s.dataset.name='人工集';const key=s.dataset.datasetKey;await s.submit();const query=s.reconcile()
 s.permission.capabilities.manageEvaluations=false;pending.resolve({kind:'DATASET',datasetKey:key,state:'FOUND',recordId:1});await query
 assert.equal(s.lookup.value,'');assert.equal(s.locked.value,false);assert.equal(s.frozen.value,undefined)
})
test('parent editor lock blocks dataset selection and every concurrent evaluation mutation',async t=>{
 const s=await mountEvaluation(t);s.selectedDatasetKey.value='ORIGINAL';s.editorLocked.value=true;s.requests.length=0
 await Promise.all([s.load(),s.selectDataset('NEW'),s.run(),s.synchronize(),s.createDemoDataset(),s.runDatasetCalibration()])
 assert.equal(s.selectedDatasetKey.value,'ORIGINAL');assert.deepEqual(s.requests,[])
})
test('parent in-flight refresh cannot change selected dataset while editor submission is locked',async t=>{
 const pending=deferred();let delay=false
 const s=await mountEvaluation(t,{api:{listEvaluationDatasets:()=>delay?pending.promise:Promise.resolve([])}})
 delay=true;const request=s.load();s.selectedDatasetKey.value='ORIGINAL';s.editorLocked.value=true;pending.resolve([{datasetKey:'OTHER'}]);await request;await settle()
 assert.equal(s.selectedDatasetKey.value,'ORIGINAL');assert.deepEqual(s.datasets.value,[])
})

test('exact lookup rejects wrong dataset, item, kind and invalid found record identifiers',()=>{
 const expected={kind:'SAMPLE',datasetKey:'DS',itemKey:'case'},found={...expected,state:'FOUND',recordId:501}
 assert.match(helpers.datasetLookupMessage(found,expected),/#501/)
 for(const patch of [{datasetKey:'OTHER'},{itemKey:'CASE'},{kind:'DATASET'},{recordId:null},{recordId:0},{recordId:1.5},{recordId:9007199254740992}])assert.throws(()=>helpers.datasetLookupMessage({...found,...patch},expected))
})
test('lookup negative and collation mismatch states preserve uncertainty and reject contradictory metadata',()=>{
 const expected={kind:'DATASET',datasetKey:' DS '}
 assert.match(helpers.datasetLookupMessage({kind:'DATASET',datasetKey:'DS',state:'NOT_FOUND'},expected),/仍可能在处理中/)
 assert.match(helpers.datasetLookupMessage({kind:'DATASET',datasetKey:'DS',state:'KEY_MISMATCH'},expected),/不同写法/)
 for(const patch of [{state:'PENDING'},{recordId:1},{createdAt:'2026-10-16T00:00:00Z'},{itemKey:'unrelated'}])assert.throws(()=>helpers.datasetLookupMessage({kind:'DATASET',datasetKey:'DS',state:'NOT_FOUND',...patch},expected))
})
test('a malformed lookup response remains unresolved and never releases the original submission',async t=>{
 const s=mount(t,{api:{createEvaluationDataset:async()=>{throw Error('lost reply')},lookupEvaluationDataset:async()=>({kind:'DATASET',datasetKey:'OTHER',state:'FOUND',recordId:1})}})
 s.open('dataset');s.dataset.name='manual';await s.submit();const original=s.frozen.value.body.datasetKey;await s.reconcile()
 assert.match(s.lookup.value,/查询失败/);assert.equal(s.locked.value,true);assert.equal(s.frozen.value.body.datasetKey,original);assert.equal(s.reviewed.value,false)
})
test('exact API lookup sends special identifiers as query parameters and never requests a capped list',async()=>{
 const calls=[],exports={}
 const source=readFileSync(new URL('../src/api/eap.ts',import.meta.url),'utf8')
 new Function('exports','require',compile(source))(exports,name=>name==='./http'?{getData:async(...args)=>{calls.push(args);return {}}}:{})
 const dataset='A/B ?#中',item='Case/?&#汉'
 await exports.EapApi.lookupEvaluationDataset(dataset);await exports.EapApi.lookupEvaluationDatasetItem(dataset,item)
 const first=new URL(calls[0][0],'http://local'),second=new URL(calls[1][0],'http://local')
 assert.equal(first.pathname,'/evaluations/datasets/lookup');assert.equal(second.pathname,'/evaluations/dataset-items/lookup');assert.equal(first.searchParams.get('datasetKey'),dataset);assert.equal(second.searchParams.get('datasetKey'),dataset);assert.equal(second.searchParams.get('itemKey'),item);assert.equal(second.searchParams.has('limit'),false);assert.equal(second.hash,'')
})

test('checked reference requires all three identifiers; default manual payload stays unverified',()=>{
 assert.equal(helpers.samplePayload(valid(),[evaluator]).verifySourceReference,false)
 for(const fields of [{},{agentId:'7',traceId:'trace'},{traceId:'trace',spanId:'span'},{agentId:'7',spanId:'span'}]){
  assert.throws(()=>helpers.samplePayload({...valid(),verifySourceReference:true,...fields},[evaluator]),/Agent ID|Trace ID/)
 }
 const p=helpers.samplePayload({...valid(),verifySourceReference:true,agentId:'7',traceId:' trace ',spanId:' span '},[evaluator])
 assert.equal(p.verifySourceReference,true);assert.equal(p.traceId,'trace');assert.equal(p.spanId,'span')
})
test('checked save from an old server or wrong reference stays uncertain and cannot auto-retry',async t=>{
 for(const extra of [{},{sourceReference:{state:'MANUAL_UNVERIFIED'}},{agentId:8,sourceReference:{state:'OBSERVATION_MATCHED',observationId:91,endpointKey:'endpoint',observedAt:'2026-10-16T00:00:00Z',checkedAt:'2026-10-16T00:00:01Z'}}]){
  const s=mount(t,{api:{createEvaluationDatasetItem:async(_d,b)=>({...b,...extra})}})
  s.open('sample');Object.assign(s.sample,valid(),{verifySourceReference:true,agentId:'7',traceId:'trace',spanId:'span'});await s.submit();await s.submit()
  assert.equal(s.uncertain.value,true);assert.equal(s.requests.length,1);assert.equal(s.messages.length,0)
 }
})
test('valid checked receipt succeeds and reset clears the opt-in',async t=>{
 const s=mount(t,{api:{createEvaluationDatasetItem:async(_d,b)=>({...b,sourceReference:{state:'OBSERVATION_MATCHED',observationId:91,endpointKey:'endpoint',observedAt:'2026-10-16T00:00:00Z',checkedAt:'2026-10-16T00:00:01Z'}})}})
 s.open('sample');Object.assign(s.sample,valid(),{verifySourceReference:true,agentId:'7',traceId:'trace',spanId:'span'});await s.submit()
 assert.equal(s.uncertain.value,false);assert.equal(s.sample.verifySourceReference,false);assert.equal(s.messages.length,1)
})
test('malformed source snapshot never passes runtime receipt checks',()=>{
 const body={agentId:7,traceId:'t',spanId:'s'},source={state:'OBSERVATION_MATCHED',observationId:91,endpointKey:'e',observedAt:'2026-10-16T00:00:00Z',checkedAt:'2026-10-16T00:00:01Z'}
 for(const patch of [{observationId:0},{observationId:'91'},{endpointKey:''},{checkedAt:'bad'},{observedAt:null},{state:'VERIFIED'}]) assert.equal(helpers.matchesSourceReceipt({...body,sourceReference:{...source,...patch}},body),false)
})
