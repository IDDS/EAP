import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import {learningLocation,learningRecordPath,mountLearning,deferred,settle} from './learning-test-support.mjs'

test('record links accept only a single positive safe integer for each supported identifier',()=>{
 assert.deepEqual(learningLocation({agentId:'71',experimentId:'900'}),{agentId:71,experimentId:900})
 assert.deepEqual(learningLocation({optimizationId:'901',unknown:'ignored'}),{optimizationId:901})
 assert.deepEqual(learningLocation({}),{})
 for(const value of [null,'',[],['1'],['1','2'],'0','01','-1','1.5','1e3',' 1','1 ','+1','9007199254740992',1]){
  for(const key of ['agentId','experimentId','optimizationId'])assert.ok(learningLocation({[key]:value}).error,`${key}: ${JSON.stringify(value)}`)
 }
 assert.ok(learningLocation({experimentId:'1',optimizationId:'2'}).error)
})

test('generated links contain only verified record identifiers',()=>{
 assert.equal(learningRecordPath('experiment',900,71),'/learning?agentId=71&experimentId=900')
 assert.equal(learningRecordPath('optimization',901,71),'/learning?agentId=71&optimizationId=901')
 for(const id of [0,-1,1.1,NaN,Infinity,Number.MAX_SAFE_INTEGER+1])assert.equal(learningRecordPath('experiment',id,71),'/learning')
 assert.equal(learningRecordPath('experiment',1,0),'/learning')
})

const panels=[
 {kind:'experiment',file:'components/AgentExperimentPanel.vue',api:'ExperimentApi',rows:'experiments'},
 {kind:'optimization',file:'components/PromptOptimizationPanel.vue',api:'OptimizationApi',rows:'runs'},
]
for(const {kind,file,api,rows} of panels){
 const key=`${kind}Id`
 const expose=`load,loadContent,select,selected,content,contentLoading,error,loading,busy,submitUnknown,${rows}`
 test(`${kind}: old record uses direct GET, validates context and does not wake or execute anything`,async()=>{
  const calls=[],record={id:900,agentId:71,status:'COMPLETED'}
  const s=mountLearning(file,expose,{query:{agentId:'71',[key]:'900'},EapApi:{listAgents:async()=>{calls.push(['agents']);return []}},[api]:{
   status:async()=>{calls.push(['status']);return {enabled:true}},get:async id=>{calls.push(['get',id]);return record},
   list:async()=>{calls.push(['list']);return []},content:async()=>{calls.push(['content']);return {samples:[]}},
   targets:async()=>{calls.push(['targets'])},profile:async()=>{calls.push(['profile'])},submit:async()=>{calls.push(['submit'])},
  }})
  await s.load();assert.deepEqual(calls,[['get',900]]);assert.equal(s.selected.value,record);assert.deepEqual(s[rows].value,[record]);assert.equal(s.content.value,undefined)
  s.permission.can=name=>name!=='viewContent';await s.loadContent();assert.deepEqual(calls,[['get',900]])
  s.permission.can=()=>true;await s.loadContent();assert.deepEqual(calls,[['get',900],['content']])
 })
 test(`${kind}: agent-only link filters records without selecting or loading their content`,async()=>{
  const s=mountLearning(file,expose,{query:{agentId:'71'},[api]:{list:async()=>[{id:1,agentId:72},{id:2,agentId:71}]}})
  await s.load();assert.deepEqual(s[rows].value,[{id:2,agentId:71}]);assert.equal(s.selected.value,undefined);assert.equal(s.content.value,undefined)
 })
 test(`${kind}: invalid links do not issue API requests`,async()=>{
  let calls=0
  const called=async()=>{calls++;return []}
  const s=mountLearning(file,expose,{query:{[key]:['900','901']},[api]:{get:called,list:called,status:called},EapApi:{listAgents:called}})
  await s.load();assert.equal(calls,0);assert.match(s.error.value,/链接无效/)
 })
 test(`${kind}: mismatched IDs or agents and inaccessible records discard stale content`,async()=>{
  for(const result of [{id:901,agentId:71},{id:900,agentId:72},null]){
   const s=mountLearning(file,expose,{query:{agentId:'71',[key]:'900'},[api]:{get:async()=>{if(!result)throw Error('404');return result}}})
   s.selected.value={id:1};s.content.value={private:'old'};s[rows].value=[{id:1}]
   await s.load();assert.equal(s.selected.value,undefined);assert.equal(s.content.value,undefined);assert.deepEqual(s[rows].value,[]);assert.match(s.error.value,/不存在、不可访问或读取失败/)
  }
 })
 test(`${kind}: switching records discards an older metadata reply`,async()=>{
  const first=deferred(),second=deferred()
  const s=mountLearning(file,expose,{query:{agentId:'71',[key]:'900'},[api]:{get:id=>id===900?first.promise:second.promise}})
  const old=s.load();await s.routing.navigate({agentId:'72',[key]:'901'});second.resolve({id:901,agentId:72});await settle()
  first.resolve({id:900,agentId:71});await old
  assert.equal(s.selected.value.id,901);assert.deepEqual(s[rows].value,[{id:901,agentId:72}]);assert.equal(s.loading.value,false)
 })
 test(`${kind}: changing to an invalid link clears protected content and ignores a late body`,async()=>{
  const pending=deferred()
  const s=mountLearning(file,expose,{query:{[key]:'900'},[api]:{get:async()=>({id:900,agentId:71}),content:()=>pending.promise}})
  await s.load();const body=s.loadContent();await s.routing.navigate({[key]:'invalid'});pending.resolve({private:'old'})
  await body;assert.equal(s.content.value,undefined);assert.equal(s.selected.value,undefined);assert.equal(s.contentLoading.value,false);assert.match(s.error.value,/链接无效/)
 })
 test(`${kind}: failed refresh also invalidates a pending private-content reply`,async()=>{
  const pending=deferred();let failed=false
  const s=mountLearning(file,expose,{query:{[key]:'900'},[api]:{get:async()=>{if(failed)throw Error('403');return {id:900,agentId:71}},content:()=>pending.promise}})
  await s.load();const body=s.loadContent();failed=true;await s.load();pending.resolve({private:'old'});await body
  assert.equal(s.selected.value,undefined);assert.equal(s.content.value,undefined);assert.equal(s.contentLoading.value,false)
 })
 test(`${kind}: navigation cannot discard a pending or unconfirmed submission`,async()=>{
  const s=mountLearning(file,expose,{query:{agentId:'71'}})
  s.busy.value=true;assert.equal(await s.routing.navigate({[key]:'900'}),false)
  s.busy.value=false;s.submitUnknown.value=true;assert.equal(await s.routing.navigate({agentId:'72'}),false)
  assert.deepEqual(s.routing.route.query,{agentId:'71'});assert.equal(s.messages.length,2)
  s.submitUnknown.value=false;assert.equal(await s.routing.navigate({agentId:'72'}),true);await settle()
 })
}

test('feedback uses route Agent filter, supports history changes and retains unknown-submission drafts',async()=>{
 const ids=[]
 const s=mountLearning('views/LearningView.vue','load,filterAgent,feedbacks,draft,openForm,submitUnknown,busy',{query:{agentId:'71'},EapApi:{listLearningFeedbacks:async id=>{ids.push(id);return [{id}]},feedbackEvidence:async()=>({tasks:[],traces:[]})}})
 await s.load();assert.deepEqual(ids,[71]);s.openForm();assert.equal(s.draft.value.agentId,71)
 await s.routing.navigate({agentId:'72'});await settle();assert.equal(s.filterAgent.value,72);assert.deepEqual(s.feedbacks.value,[{id:72}])
 s.submitUnknown.value=true;s.draft.value.summary='uncertain feedback';s.openForm();assert.equal(s.draft.value.summary,'uncertain feedback')
 assert.equal(await s.routing.navigate({agentId:'73'}),false);assert.equal(s.filterAgent.value,72)
})

test('cancelled Agent-filter navigation restores the displayed route filter',async()=>{
 const s=mountLearning('views/LearningView.vue','filterAgent,changeFilter',{query:{agentId:'71'}})
 s.routing.guards.push(()=>false);s.filterAgent.value=72;await s.changeFilter();assert.equal(s.filterAgent.value,71)
})

test('record detail and invalid links do not load unrelated feedback',async()=>{
 for(const query of [{experimentId:'900'},{optimizationId:'901'},{agentId:'invalid'}]){
  let calls=0
  const s=mountLearning('views/LearningView.vue','load,feedbacks',{query,EapApi:{listLearningFeedbacks:async()=>{calls++;return []},listAgents:async()=>{calls++;return []}}})
  await s.load();assert.equal(calls,0);assert.deepEqual(s.feedbacks.value,[])
 }
})

test('experiment detail API is an existing read-only GET endpoint',async()=>{
 const source=readFileSync(new URL('../src/api/agentExperiments.ts',import.meta.url),'utf8').replace(/^import .*$/gm,'')
 const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText
 const calls=[],exports={}
 new Function('exports','getData','http',code)(exports,async path=>{calls.push(path);return {id:900}},{post:()=>{throw Error('unexpected write')}})
 assert.deepEqual(await exports.ExperimentApi.get(900),{id:900});assert.deepEqual(calls,['/agent-experiments/900'])
})
