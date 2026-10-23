import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'
import * as Router from 'vue-router'
import {learningLocation,learningRecordPath,deferred,settle} from './learning-test-support.mjs'

// Real Vue effects and RouterView/guards; the host intentionally has no DOM or UI library.
// These tests exercise component scripts and routing, not browser layout or Element Plus.
function hostRenderer(){
 const node=(type,text='')=>({type,text,children:[],parent:null})
 function remove(child){if(child.parent){const index=child.parent.children.indexOf(child);if(index>=0)child.parent.children.splice(index,1);child.parent=null}}
 return Vue.createRenderer({
  createElement:type=>node(type),createText:text=>node('text',text),createComment:text=>node('comment',text),
  insert(child,parent,anchor=null){remove(child);const index=anchor?parent.children.indexOf(anchor):-1;parent.children.splice(index<0?parent.children.length:index,0,child);child.parent=parent},
  remove,setText:(child,text)=>{child.text=text},setElementText:(child,text)=>{child.text=text;child.children=[]},
  parentNode:child=>child.parent,nextSibling:child=>child.parent?.children[child.parent.children.indexOf(child)+1]??null,patchProp:()=>{},
 })
}

async function mountPage(t,url='/learning',overrides={}){
 const scopes={},messages=[],calls=[]
 const permission=Vue.reactive({state:{user:{subject:'a'}},contentAllowed:true,consoleAllowed:true,can(name){return name==='viewContent'?this.contentAllowed:name==='viewConsole'?this.consoleAllowed:true}})
 const EapApi={listAgents:async()=>{calls.push('agents');return []},listLearningFeedbacks:async()=>{calls.push('feedback');return []},...overrides.EapApi}
 const ExperimentApi={list:async()=>[],get:async id=>{calls.push(`experiment:${id}`);return {id,agentId:71,status:'COMPLETED'}},content:async id=>{calls.push(`experiment-content:${id}`);return {samples:[],outputs:[]}},...overrides.ExperimentApi}
 const OptimizationApi={status:async()=>{calls.push('status');return {enabled:true}},list:async()=>[],get:async id=>{calls.push(`optimization:${id}`);return {id,agentId:71,status:'COMPLETED'}},content:async id=>{calls.push(`optimization-content:${id}`);return {samples:[]}},review:async()=>({runId:901,status:'UNKNOWN',evidenceDigest:'a'.repeat(64),decision:null,blockers:[]}),...overrides.OptimizationApi}
 function component(file,key,names,render){
  const source=readFileSync(new URL(`../src/${file}`,import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
  const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText
  return Vue.defineComponent({name:key,props:key==='unknown'?['runId','disabled']:[],setup(props,{expose}){
   const values={ref:Vue.ref,computed:Vue.computed,watch:Vue.watch,onMounted:Vue.onMounted,onBeforeUnmount:Vue.onBeforeUnmount,useRoute:Router.useRoute,useRouter:Router.useRouter,onBeforeRouteUpdate:Router.onBeforeRouteUpdate,onBeforeRouteLeave:Router.onBeforeRouteLeave,defineProps:()=>props,defineExpose:expose,usePermissions:()=>permission,learningLocation,learningRecordPath,EapApi,ExperimentApi,OptimizationApi,Plus:null,MagicStick:null,ElMessage:{warning:m=>messages.push(m),error:m=>messages.push(m),success:m=>messages.push(m)}}
   const scope=new Function(...Object.keys(values),code+`;return {${names}}`)(...Object.values(values));scopes[key]=scope
   return ()=>render?render(scope):Vue.h('div')
  }})
 }
 const Unknown=component('components/OptimizationUnknownReview.vue','unknown','busy,uncertain,accepted,review,decide,load,error')
 const Experiment=component('components/AgentExperimentPanel.vue','experiment','busy,submitUnknown,draft,selected,content,loadContent,loading,error')
 const Optimization=component('components/PromptOptimizationPanel.vue','optimization','busy,submitUnknown,draft,profile,selected,content,loadContent,load,select,submit,unknownReview,error',s=>Vue.h('div',s.selected.value?.status==='UNKNOWN'?[Vue.h(Unknown,{key:s.selected.value.id,runId:s.selected.value.id,disabled:s.busy.value,ref:s.unknownReview})]:[]))
 const Page=component('views/LearningView.vue','feedback','busy,submitUnknown,draft,filterAgent,changeFilter,location',s=>Vue.h('div',[
  !s.location.value.error&&!s.location.value.optimizationId?Vue.h(Experiment):null,
  !s.location.value.error&&!s.location.value.experimentId?Vue.h(Optimization):null,
 ]))
 const router=Router.createRouter({history:Router.createMemoryHistory(),routes:[{path:'/learning',component:Page},{path:'/other',component:{render:()=>Vue.h('div')}}]})
 const app=hostRenderer().createApp({render:()=>Vue.h(Router.RouterView)})
 app.use(router);await router.push(url);app.mount({children:[],parent:null});t.after(()=>app.unmount());await router.isReady();await settle();await Vue.nextTick()
 return {scopes,router,permission,calls,messages}
}

for(const key of ['feedback','experiment','optimization']){
 test(`real router: ${key} blocks query navigation and page exit until submission is reconciled`,async t=>{
  const {scopes,router}=await mountPage(t)
  const scope=scopes[key],originalKey=scope.draft.value.requestKey
  scope.busy.value=true
  assert.ok(Router.isNavigationFailure(await router.push('/learning?optimizationId=901'),Router.NavigationFailureType.aborted))
  assert.equal(router.currentRoute.value.fullPath,'/learning');assert.equal(scope.draft.value.requestKey,originalKey)
  scope.busy.value=false;scope.submitUnknown.value=true
  assert.ok(Router.isNavigationFailure(await router.push('/other'),Router.NavigationFailureType.aborted))
  assert.equal(scope.draft.value.requestKey,originalKey)
  scope.submitUnknown.value=false;await router.push('/other');assert.equal(router.currentRoute.value.path,'/other')
 })
}

test('real router: nested UNKNOWN decision survives row selection, refresh and navigation with the same retry key',async t=>{
 const pending=deferred(),bodies=[];let gets=0,submissions=0,persisted=false
 const {scopes,router}=await mountPage(t,'/learning?optimizationId=901',{OptimizationApi:{
  get:async id=>{gets++;return {id,agentId:71,status:'UNKNOWN'}},
  review:async()=>({runId:901,evidenceDigest:'a'.repeat(64),blockers:[],decision:persisted?{decision:'STOP_NO_REPLAY'}:null}),
  decide:(_id,body)=>{bodies.push(JSON.parse(JSON.stringify(body)));return pending.promise},
  submit:async()=>{submissions++;return {id:902}},
 }})
 const panel=scopes.optimization,review=scopes.unknown
 assert.ok(review.review.value);review.accepted.value=true;const write=review.decide()
 panel.select({id:902,agentId:71,status:'COMPLETED'});await panel.load()
 panel.profile.value={profile:{profileDigest:'a'.repeat(64)}};panel.draft.value.agentId=71;await panel.submit()
 assert.equal(panel.selected.value.id,901);assert.equal(gets,1);assert.equal(submissions,0)
 assert.ok(Router.isNavigationFailure(await router.push('/learning?experimentId=900'),Router.NavigationFailureType.aborted))
 pending.reject(Error('lost receipt'));await write
 assert.ok(Router.isNavigationFailure(await router.push('/other'),Router.NavigationFailureType.aborted))
 panel.select({id:902,agentId:71,status:'COMPLETED'});assert.equal(panel.selected.value.id,901)
 await review.decide();assert.equal(bodies.length,2);assert.deepEqual(bodies[1],bodies[0])
 persisted=true;await review.load();assert.equal(review.uncertain.value,false)
 await router.push('/learning?experimentId=900');await settle();assert.equal(scopes.experiment.selected.value.id,900)
})

test('real router: direct links skip unrelated lists and cross-panel/back navigation reloads only metadata',async t=>{
 const {scopes,router,calls}=await mountPage(t,'/learning?agentId=71&optimizationId=901')
 assert.deepEqual(calls,['optimization:901']);assert.equal(scopes.optimization.selected.value.id,901)
 await router.push(learningRecordPath('experiment',900,71));await settle()
 assert.equal(scopes.experiment.selected.value.id,900);assert.equal(scopes.experiment.content.value,undefined)
 assert.deepEqual(calls,['optimization:901','experiment:900'])
 await scopes.experiment.loadContent();assert.equal(calls.at(-1),'experiment-content:900')
 router.back();await settle();await Vue.nextTick()
 assert.equal(router.currentRoute.value.query.optimizationId,'901');assert.equal(scopes.optimization.selected.value.id,901)
 assert.equal(scopes.optimization.content.value,undefined);assert.equal(calls.at(-1),'optimization:901')
})

for(const failed of [false,true]){
 test(`real router: an in-flight metadata ${failed?'failure':'reply'} cannot erase a pending governance receipt`,async t=>{
  const refresh=deferred(),decision=deferred();let gets=0
  const {scopes}=await mountPage(t,'/learning?optimizationId=901',{OptimizationApi:{
   get:id=>++gets===1?Promise.resolve({id,agentId:71,status:'UNKNOWN'}):refresh.promise,
   decide:()=>decision.promise,
  }})
  const panel=scopes.optimization,review=scopes.unknown
  panel.content.value={manualPrompt:'previous protected content'}
  const loading=panel.load();review.accepted.value=true;const writing=review.decide()
  if(failed)refresh.reject(Error('read unavailable'));else refresh.resolve({id:901,agentId:71,status:'COMPLETED'})
  await loading;await Vue.nextTick()
  assert.equal(panel.selected.value.status,'UNKNOWN');assert.equal(scopes.unknown,review)
  if(failed)assert.equal(panel.content.value,undefined)
  decision.reject(Error('lost receipt'));await writing
  assert.equal(review.uncertain.value,true);assert.equal(review.accepted.value,true)
 })
}

test('real router: creating an optimization disables concurrent governance submission',async t=>{
 let writes=0
 const {scopes}=await mountPage(t,'/learning?optimizationId=901',{OptimizationApi:{get:async id=>({id,agentId:71,status:'UNKNOWN'}),decide:async()=>{writes++;return {decision:{decision:'STOP_NO_REPLAY'}}}}})
 const panel=scopes.optimization,review=scopes.unknown
 review.accepted.value=true;panel.busy.value=true;await Vue.nextTick();await review.decide();assert.equal(writes,0)
 panel.busy.value=false;await Vue.nextTick();await review.decide();assert.equal(writes,1)
})

test('real router: an old record reply cannot overwrite a new link or restore content after permission loss',async t=>{
 const first=deferred(),body=deferred()
 const {scopes,router,permission}=await mountPage(t,'/learning?agentId=71&experimentId=900',{ExperimentApi:{get:id=>id===900?first.promise:Promise.resolve({id,agentId:72,status:'COMPLETED'}),content:()=>body.promise}})
 await router.push('/learning?agentId=72&experimentId=901');await settle()
 first.resolve({id:900,agentId:71,status:'COMPLETED'});await settle();assert.equal(scopes.experiment.selected.value.id,901)
 const request=scopes.experiment.loadContent();permission.contentAllowed=false;await settle()
 body.resolve({samples:[{input:'old private body'}]});await request
 assert.equal(scopes.experiment.content.value,undefined);assert.equal(scopes.experiment.selected.value.id,901)
})

test('real router: revoking console permission clears an unknown write and permits leaving the page',async t=>{
 const {scopes,router,permission}=await mountPage(t)
 scopes.experiment.submitUnknown.value=true;scopes.experiment.draft.value.candidatePrompt='private draft'
 permission.consoleAllowed=false;await Vue.nextTick()
 assert.equal(scopes.experiment.draft.value.candidatePrompt,'');assert.equal(scopes.experiment.submitUnknown.value,false)
 await router.push('/other');assert.equal(router.currentRoute.value.path,'/other')
})
