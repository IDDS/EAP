import {readFileSync} from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'

export const evaluationHelpers=['currentAccess','canWrite','cancelPolling']
export function evaluationState(){
 const ref=value=>({value})
 return {contextAgentId:ref(undefined),contextTraceId:ref(undefined),accessEpoch:0,pollGeneration:0,pollingDisposed:false,permissions:{state:{user:{mode:'JWT'}},can:()=>true},
  editorLocked:ref(false),writeBusy:ref(false),submittingJob:ref(false),creatingDataset:ref(false),annotating:ref(false),syncing:ref(false),pageError:ref('')}
}

export function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
export const settle=()=>new Promise(resolve=>setImmediate(resolve))

// Actual Vue refs/watchers and the complete script setup. Browser tests cover templates.
export async function mountEvaluation(t,{capabilities={},api={}}={}){
 const permission=Vue.reactive({state:{user:{mode:'JWT',subject:'a'}},capabilities:{viewConsole:true,manageEvaluations:true,annotateEvaluations:true,...capabilities},can(name){return this.capabilities[name]===true}})
 const requests=[],messages=[],timers=new Map();let nextTimer=0,unmount
 const EapApi={listEvaluators:async()=>[],listEvaluationDatasets:async()=>[],listEvaluationResults:async()=>[],listEvaluationJobs:async()=>[],getEvaluationSummary:async()=>({total:0,passRate:0,evaluators:[]}),getContentAnalysisStatus:async()=>null,listEvaluationDatasetItems:async()=>[],getEvaluationDatasetCalibration:async()=>({evaluators:[]}),listEvaluationCalibrationRuns:async()=>[],...api}
 for(const [name,fn] of Object.entries(EapApi))EapApi[name]=(...args)=>{requests.push({name,args});return fn(...args)}
 const source=readFileSync(new URL('../src/views/EvaluationView.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText
 const values={ref:Vue.ref,reactive:Vue.reactive,computed:Vue.computed,watch:Vue.watch,onBeforeUnmount:fn=>{unmount=fn},useRoute:()=>Vue.reactive({query:{}}),usePageSection:()=>Vue.ref('overview'),useRouter:()=>({push:()=>{}}),usePermissions:()=>permission,EapApi,
  ElMessage:Object.fromEntries(['success','error','warning','info'].map(kind=>[kind,message=>messages.push({kind,message})])),
  window:{setTimeout:fn=>{timers.set(++nextTimer,fn);return nextTimer},clearTimeout:id=>timers.delete(id)},
 }
 const exposed='load,run,synchronize,createDemoDataset,annotateItem,runDatasetCalibration,loadDatasetItems,loadDatasetCalibration,loadCalibrationHistory,openResult,resumeJob,selectDataset,selectCalibrationRun,form,annotationActor,evaluators,datasets,results,datasetItems,selectedDatasetKey,calibration,calibrationDetail,calibrationRuns,selectedCalibrationRunId,traceSpans,selectedResult,detailVisible,running,syncing,creatingDataset,annotating,calibrating,writeBusy,pageError,activeJob,stopPolling,editorLocked,datasetSaved'
 const effects=Vue.effectScope();let scope
 effects.run(()=>{scope=new Function(...Object.keys(values),code+`;return {${exposed}}`)(...Object.values(values))})
 t.after(()=>{unmount();effects.stop()});await settle()
 return {...scope,permission,requests,messages,timers,tick:async()=>{const entry=timers.entries().next().value;if(entry){timers.delete(entry[0]);entry[1]();await settle()}}}
}
