import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import {routeStubs} from './learning-test-support.mjs'
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function mount(file,expose,overrides={}){
 const source=readFileSync(new URL(`../src/${file}`,import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText
 const callbacks=[];let unmount
 const permission={state:{user:{subject:'a'}},can:()=>true}
 const EapApi={listLearningFeedbacks:async()=>[],listAgents:async()=>[],feedbackEvidence:async()=>({tasks:[],traces:[]}),...overrides.EapApi}
 const ExperimentApi={list:async()=>[],targets:async()=>({enabled:true,instances:[]}),...overrides.ExperimentApi}
 const notify=[]
 const values={...routeStubs().values,ref:value=>({value}),computed:fn=>({get value(){return fn()}}),watch:(_sources,fn)=>callbacks.push(fn),onBeforeUnmount:fn=>{unmount=fn},usePermissions:()=>permission,EapApi,ExperimentApi,ElMessage:{warning:v=>notify.push(v),success:v=>notify.push(v),error:v=>notify.push(v)},Plus:null}
 const scope=new Function(...Object.keys(values),code+`;return {${expose}}`)(...Object.values(values))
 return{...scope,permission,notify,reset:()=>callbacks.forEach(fn=>fn()),unmount:()=>unmount?.()}
}
test('feedback needs real agent, rating and text before creating a record',async()=>{
 let calls=0;const s=mount('views/LearningView.vue','draft,submit,busy',{EapApi:{createLearningFeedback:async()=>{calls++}}})
 await s.submit();assert.equal(calls,0);s.draft.value.agentId=71;s.draft.value.rating=3;s.draft.value.summary='Observed gap';await s.submit();assert.equal(calls,1)
})
test('feedback double-click is suppressed and uncertain submit preserves key and content',async()=>{
 const pending=deferred(),bodies=[];const s=mount('views/LearningView.vue','draft,submit,busy,submitUnknown',{EapApi:{createLearningFeedback:body=>{bodies.push(body);return pending.promise}}})
 Object.assign(s.draft.value,{agentId:71,rating:2,summary:'Observed gap',improvementAction:'Change instructions'})
 const key=s.draft.value.requestKey;const first=s.submit();await s.submit();assert.equal(bodies.length,1)
 pending.reject(new Error('lost response'));await first;assert.equal(s.submitUnknown.value,true);assert.equal(s.draft.value.requestKey,key);assert.equal(s.draft.value.summary,'Observed gap')
 await s.submit();assert.equal(bodies.length,2);assert.deepEqual(bodies[0],bodies[1]);assert.equal(s.busy.value,false)
})
test('feedback discards late data when authenticated identity changes',async()=>{
 const pending=deferred();const s=mount('views/LearningView.vue','feedbacks,load',{EapApi:{listLearningFeedbacks:()=>pending.promise}})
 const first=s.load();s.permission.state.user={subject:'b'};s.permission.can=()=>false;s.reset();pending.resolve([{summary:'previous user private data'}]);await first;assert.deepEqual(s.feedbacks.value,[])
})
test('feedback evidence from an older selected agent is discarded',async()=>{
 const first=deferred(),second=deferred();const s=mount('views/LearningView.vue','draft,tasks,traces,loadEvidence',{EapApi:{feedbackEvidence:id=>id===71?first.promise:second.promise}})
 s.draft.value.agentId=71;const a=s.loadEvidence();s.draft.value.agentId=72;const b=s.loadEvidence();second.resolve({tasks:[{id:2}],traces:['new-trace']});await b;first.resolve({tasks:[{id:1}],traces:['old-trace']});await a;assert.deepEqual(s.tasks.value,[{id:2}]);assert.deepEqual(s.traces.value,['new-trace'])
})
test('experiment usage never turns missing measurements into zero-token claims',()=>{
 const s=mount('components/AgentExperimentPanel.vue','selected,usageText,scoreText,latencyText')
 s.selected.value={trials:[{variant:'BASELINE',inputTokens:null,outputTokens:null,usageSource:'UNKNOWN',score:null,latencyMs:null}]}
 assert.match(s.usageText('BASELINE'),/Token 未知/);assert.equal(s.scoreText('BASELINE'),'未评分');assert.equal(s.latencyText('BASELINE'),'未知')
 s.selected.value.trials.push({variant:'BASELINE',inputTokens:7,outputTokens:3,usageSource:'PROVIDER_REPORTED',score:1,latencyMs:20})
 assert.match(s.usageText('BASELINE'),/已知 Token：10.*1\/2/);assert.equal(s.scoreText('BASELINE'),'1/1')
})
test('experiment private content cannot survive a late reply after logout',async()=>{
 const pending=deferred();const s=mount('components/AgentExperimentPanel.vue','select,content,selected',{ExperimentApi:{content:()=>pending.promise}})
 const req=s.select({id:1,trials:[]});s.permission.state.user=null;s.permission.can=()=>false;s.reset();pending.resolve({samples:[{input:'old private sample'}],outputs:[]});await req;assert.equal(s.content.value,undefined);assert.equal(s.selected.value,undefined)
})
