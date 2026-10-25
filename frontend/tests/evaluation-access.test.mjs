import test from 'node:test'
import assert from 'node:assert/strict'
import {mountEvaluation,deferred,settle} from './evaluation-test-support.mjs'

const item={itemKey:'case-1'}
function selectSample(s){s.selectedDatasetKey.value='DATASET';s.datasetItems.value=[item];return s.datasetItems.value[0]}

test('evaluation denies every mutation without its current capability, even when invoked directly',async t=>{
 const s=await mountEvaluation(t,{capabilities:{manageEvaluations:false,annotateEvaluations:false}}),row=selectSample(s)
 s.requests.length=0;s.form.inputJson='invalid JSON'
 await Promise.all([s.run(),s.synchronize(),s.createDemoDataset(),s.annotateItem(row,'PASS'),s.runDatasetCalibration()])
 assert.deepEqual(s.requests,[]);assert.deepEqual(s.messages,[]);assert.equal(s.writeBusy.value,false)
 s.permission.capabilities.viewConsole=false;await settle();await s.load();await s.openResult({traceId:'private'});assert.deepEqual(s.requests,[])
})

test('annotation-only capability permits labels and omits caller-supplied identity in JWT mode',async t=>{
 let payload
 const s=await mountEvaluation(t,{capabilities:{manageEvaluations:false},api:{annotateEvaluationDatasetItem:async(_dataset,_item,body)=>{payload=body}}})
 const row=selectSample(s);s.annotationActor.value='untrusted actor'
 await s.runDatasetCalibration();await s.annotateItem(row,'FAIL')
 assert.equal(payload.groundTruthLabel,'FAIL');assert.equal(payload.annotatedBy,undefined)
 assert.equal(s.requests.filter(r=>r.name==='runEvaluationDataset').length,0)
})

for(const kind of ['evaluation','job','dataset','annotation','calibration']){
 test(`evaluation ${kind} suppresses concurrent duplicate and cross-action writes`,async t=>{
  const pending=deferred();let writes=0
  const apiName={evaluation:'runEvaluation',job:'submitEvaluationJob',dataset:'createEvaluationDataset',annotation:'annotateEvaluationDatasetItem',calibration:'runEvaluationDataset'}[kind]
  const s=await mountEvaluation(t,{api:{[apiName]:()=>{writes++;return pending.promise},createEvaluationDatasetItem:async()=>({}),getEvaluationJob:async()=>({id:1,status:'SUCCEEDED',scanned:0,evaluated:0})}})
  const row=selectSample(s),invoke=()=>({evaluation:()=>s.run(),job:()=>s.synchronize(),dataset:()=>s.createDemoDataset(),annotation:()=>s.annotateItem(row,'PASS'),calibration:()=>s.runDatasetCalibration()}[kind]())
  const first=invoke();await invoke();await s.createDemoDataset();assert.equal(writes,1)
  pending.resolve({id:1,runId:'run-1',status:'SUCCEEDED',evaluatedItems:0,f1:0});await settle();if(kind==='job')await s.tick();await first
  assert.equal(s.writeBusy.value,false);assert.equal(writes,1)
 })
}

test('revocation or identity switch between dataset and sample creation prevents the second write',async t=>{
 const pending=deferred();let samples=0
 const s=await mountEvaluation(t,{api:{createEvaluationDataset:()=>pending.promise,createEvaluationDatasetItem:async()=>{samples++}}})
 const creation=s.createDemoDataset();s.permission.state.user={mode:'JWT',subject:'b'};await settle()
 pending.resolve({datasetKey:'old'});await creation
 assert.equal(samples,0);assert.equal(s.messages.length,0);assert.equal(s.creatingDataset.value,false)
})

test('an older write completion cannot unlock a new write after permission revoke and restore',async t=>{
 const first=deferred(),second=deferred();let writes=0
 const s=await mountEvaluation(t,{api:{runEvaluation:()=>++writes===1?first.promise:second.promise}})
 s.form.actualJson='{"private":"old"}';const old=s.run()
 s.permission.capabilities.manageEvaluations=false;s.permission.capabilities.manageEvaluations=true;await settle()
 assert.equal(s.form.actualJson,'{}');const current=s.run();first.resolve({});await old
 assert.equal(s.running.value,true);assert.equal(s.messages.length,0)
 second.resolve({});await current;assert.equal(s.running.value,false);assert.equal(s.messages.filter(m=>m.kind==='success').length,1)
})

for(const kind of ['overview','samples','preview','history','snapshot','trace']){
 test(`late ${kind} data cannot repopulate evaluation after console access is revoked`,async t=>{
  const pending=deferred();let delay=false
  const names={overview:'listEvaluationResults',samples:'listEvaluationDatasetItems',preview:'getEvaluationDatasetCalibration',history:'listEvaluationCalibrationRuns',snapshot:'getEvaluationCalibrationRun',trace:'listTraceSpanObservations'}
  const response={overview:[{id:1,evidence:'private'}],samples:[item],preview:{datasetKey:'private'},history:[{runId:'private'}],snapshot:{calibration:{datasetKey:'private'},samples:[item]},trace:[{traceId:'private'}]}[kind]
  const s=await mountEvaluation(t,{api:{[names[kind]]:()=>delay?pending.promise:Promise.resolve([])}})
  selectSample(s);if(kind==='snapshot')s.selectedCalibrationRunId.value='old-run';delay=true
  const request=({overview:()=>s.load(),samples:()=>s.loadDatasetItems(),preview:()=>s.loadDatasetCalibration(),history:()=>s.loadCalibrationHistory(),snapshot:()=>s.loadDatasetCalibration(),trace:()=>s.openResult({traceId:'old'})}[kind])()
  s.permission.capabilities.viewConsole=false;await settle();pending.resolve(response);await request
  assert.equal(s.results.value.length,0);assert.equal(s.datasetItems.value.length,0);assert.equal(s.calibrationRuns.value.length,0);assert.equal(s.traceSpans.value.length,0)
  assert.equal(s.calibration.value.datasetKey,'');assert.equal(s.calibrationDetail.value,undefined);assert.equal(s.selectedResult.value,undefined);assert.equal(s.detailVisible.value,false)
 })
}

test('old job polling cannot clear or publish into a new identity polling loop',async t=>{
 const pending=deferred()
 const s=await mountEvaluation(t,{api:{getEvaluationJob:id=>id===1?pending.promise:Promise.resolve({id:2,status:'SUCCEEDED',scanned:0,evaluated:0})}})
 const first=s.resumeJob(1);await s.tick()
 s.permission.state.user={mode:'JWT',subject:'b'};await settle();const second=s.resumeJob(2)
 pending.resolve({id:1,status:'SUCCEEDED',scanned:99,evaluated:99});await first
 assert.equal(s.syncing.value,true);assert.equal(s.timers.size,1);assert.equal(s.messages.length,0)
 await s.tick();await second;assert.equal(s.activeJob.value.id,2);assert.equal(s.syncing.value,false)
})

test('sample and calibration read failures clear old data and produce a handled page error',async t=>{
 const s=await mountEvaluation(t,{api:{listEvaluationDatasetItems:async()=>{throw Error('offline')},getEvaluationDatasetCalibration:async()=>{throw Error('offline')},listEvaluationCalibrationRuns:async()=>{throw Error('offline')}}})
 selectSample(s);s.calibration.value={datasetKey:'old'};s.calibrationRuns.value=[{runId:'old'}]
 await s.selectDataset('NEW')
 assert.equal(s.datasetItems.value.length,0);assert.equal(s.calibration.value.datasetKey,'');assert.equal(s.calibrationRuns.value.length,0);assert.match(s.pageError.value,/读取失败/)
})

test('partial demo creation reports the original dataset without retrying either write',async t=>{
 const calls=[]
 const s=await mountEvaluation(t,{api:{createEvaluationDataset:async body=>{calls.push(body.datasetKey)},createEvaluationDatasetItem:async key=>{calls.push(key);throw Error('lost reply')}}})
 await s.createDemoDataset();assert.equal(calls.length,2);assert.equal(calls[0],calls[1]);assert.ok(s.pageError.value.includes(calls[0]));assert.equal(s.creatingDataset.value,false)
})
