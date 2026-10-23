import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
const apiScope={exports:{}}
new Function('exports','TextEncoder',ts.transpile(readFileSync(new URL('../src/api/workflowTaskConfig.ts',import.meta.url),'utf8'),{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}))(apiScope.exports,TextEncoder)
const contractApi=apiScope.exports
function handlers(file, names, globals) {
  const source = readFileSync(new URL('../src/' + file, import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile('view.ts', source, ts.ScriptTarget.Latest, true)
  const code = ast.statements.filter(n => ts.isFunctionDeclaration(n) && names.includes(n.name.text)).map(n => n.getText(ast)).join('\n')
  const context = vm.createContext(globals)
  vm.runInContext(ts.transpile(code, { target: ts.ScriptTarget.ES2022 }), context)
  return context
}
const ref = value => ({ value })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
function editor(api = {}) {
  return handlers('views/OrchestrationView.vue', ['openPlanForm','openStepForm','savePlan','saveStep','openContract','saveContract'], {
    ...contractApi,stepContractConfig:ref('{}'),contractFormOpen:ref(true),contractStep:ref({id:11,planId:7,executionBackend:'RUNTIME'}),contractConfig:ref('{}'),contractFormError:ref(''),savingContract:ref(false),contractPlanId:7,executionError:ref(''),
    selectedTeamId:ref(2), selectedPlanId:ref(7), formTeamId:2, formPlanId:7, disposed:false,
    planFormOpen:ref(true), stepFormOpen:ref(true), savingPlan:ref(false), savingStep:ref(false), frozen:ref(false),
    planFormError:ref(''), stepFormError:ref(''), planDraft:ref({goal:'  用户目标  ',contextSummary:' 用户上下文 '}),
    stepDraft:ref({stepOrder:5,title:' 用户步骤 ',requiredCapability:' custom.analysis '}), planSteps:ref([{stepOrder:2},{stepOrder:4}]),
    plans:ref([]), permissions:{can:()=>true}, EapApi:api, selectPlan:async()=>{}
  })
}
test('plan form validates input and preserves user values without demo defaults', async () => {
  const calls=[];const s=editor({createPlan:async body=>{calls.push(body);return {id:8,...body}}})
  s.planDraft.value.goal='  '; await s.savePlan();assert.equal(calls.length,0)
  s.planDraft.value.goal='  我的任务  ';await s.savePlan()
  assert.equal(calls[0].goal,'我的任务');assert.equal(calls[0].contextSummary,'用户上下文');assert.equal(calls[0].teamId,2)
  assert.equal(s.planFormOpen.value,false)
})
test('plan saving blocks double click, revoked permission and a different selected team',async()=>{
  const wait=deferred();let calls=0;const s=editor({createPlan:()=>{calls++;return wait.promise}})
  s.permissions.can=()=>false;await s.savePlan();assert.equal(calls,0)
  s.permissions.can=()=>true;s.selectedTeamId.value=3;await s.savePlan();assert.equal(calls,0)
  s.selectedTeamId.value=2;const first=s.savePlan();await s.savePlan();assert.equal(calls,1)
  s.selectedTeamId.value=3;s.selectPlan=async()=>assert.fail('late response must not switch selection')
  wait.resolve({id:8});await first;assert.equal(s.plans.value.length,0)
})
test('step form suggests max order plus one and rejects duplicates or frozen plans',async()=>{
  const calls=[];const s=editor({addPlanStep:async(...args)=>calls.push(args)})
  s.openStepForm();assert.equal(s.stepDraft.value.stepOrder,5)
  s.stepDraft.value={stepOrder:4,title:'步骤',requiredCapability:'a'};await s.saveStep();assert.equal(calls.length,0)
  s.stepDraft.value.stepOrder=5;s.frozen.value=true;await s.saveStep();assert.equal(calls.length,0)
  s.frozen.value=false;s.permissions.can=()=>false;await s.saveStep();assert.equal(calls.length,0)
  s.permissions.can=()=>true;await s.saveStep();assert.equal(calls[0][1].title,'步骤');assert.equal(calls[0][0],7)
})
test('step submission ignores old selection after pending save and does not retry failures',async()=>{
  const wait=deferred();let calls=0;const s=editor({addPlanStep:()=>{calls++;return wait.promise}})
  const first=s.saveStep();await s.saveStep();assert.equal(calls,1)
  s.selectedPlanId.value=9;s.selectPlan=async()=>assert.fail('must keep current plan');wait.resolve();await first
  s.selectedPlanId.value=7;s.stepFormOpen.value=true;s.EapApi.addPlanStep=async()=>{throw new Error('409')}
  await s.saveStep();assert.match(s.stepFormError.value,/冻结/);assert.equal(s.savingStep.value,false)
})
function outputState(api) {
 return handlers('components/workflow/PlanOutputs.vue',['loadOutputs','clearOutputs','formatOutput'],{
  props:{workflowId:6,runKey:'run-a'},permissions:{can:()=>true},outputs:ref(null),loading:ref(false),error:ref(''),revision:0,disposed:false,EapApi:api
 })
}
test('outputs never fetch without content permission and discard stale responses on clear',async()=>{
 const wait=deferred();let calls=0;const s=outputState({getWorkflowOutputs:()=>{calls++;return wait.promise}})
 s.permissions.can=()=>false;await s.loadOutputs();assert.equal(calls,0)
 s.permissions.can=()=>true;const first=s.loadOutputs();await s.loadOutputs();assert.equal(calls,1)
 s.clearOutputs();wait.resolve({'step-1':'private'});await first;assert.equal(s.outputs.value,null)
})
test('outputs discard changed run, revoked capability and unmounted responses',async()=>{
 for(const change of [s=>s.props.runKey='run-b',s=>s.permissions.can=()=>false,s=>s.disposed=true]) {
  const wait=deferred();const s=outputState({getWorkflowOutputs:()=>wait.promise});const first=s.loadOutputs();change(s);wait.resolve({'step-1':'private'});await first;assert.equal(s.outputs.value,null)
 }
 const s=outputState({getWorkflowOutputs:async()=>{throw new Error('403')}});await s.loadOutputs();assert.equal(s.outputs.value,null);assert.match(s.error.value,/权限/)
})

test('plan step preserves the explicitly selected AX backend',async()=>{
 let body;const s=editor({addPlanStep:async(id,payload)=>{body=payload}})
 s.stepDraft.value.executionBackend='AX';await s.saveStep();assert.equal(body.executionBackend,'AX')
})

test('new plan step accepts contract before assignment and preserves it in the save payload',async()=>{
 let payload;const s=editor({addPlanStep:async(id,body)=>{payload=body}})
 s.stepContractConfig.value=contractApi.setOutputContract('{}','JSON_TEXT');await s.saveStep()
 assert.equal(JSON.parse(payload.outputContractJson).source,'JSON_TEXT')
})
test('invalid result edits or AX backend block step save without discarding the contract',async()=>{
 let calls=0;const s=editor({addPlanStep:async()=>{calls++}})
 const valid=contractApi.setOutputContract('{}','JSON_TEXT')
 s.stepContractConfig.value=contractApi.updateOutputSchema(valid,'{bad');await s.saveStep();assert.equal(calls,0);assert.ok(s.stepFormError.value)
 s.stepContractConfig.value=valid;s.stepDraft.value.executionBackend='AX';await s.saveStep();assert.equal(calls,0);assert.equal(s.stepContractConfig.value,valid)
})
test('contract editor reads frozen steps but blocks save after freeze, revocation or selection change',async()=>{
 let calls=0;const s=editor({updatePlanOutputContract:async()=>{calls++}})
 s.frozen.value=true;s.openContract({id:11,planId:7,outputContractJson:JSON.stringify(JSON.parse(contractApi.setOutputContract('{}','JSON_TEXT')).eapOutput)})
 assert.equal(s.contractFormOpen.value,true);await s.saveContract();assert.equal(calls,0)
 s.frozen.value=false;s.permissions.can=()=>false;await s.saveContract();assert.equal(calls,0)
 s.permissions.can=()=>true;s.selectedPlanId.value=9;await s.saveContract();assert.equal(calls,0)
})
test('contract saves serialize only the definition and explicit clearing sends null',async()=>{
 const calls=[];const s=editor({updatePlanOutputContract:async(...args)=>calls.push(args)})
 s.contractConfig.value=contractApi.setOutputContract('{"inputFrom":["must-not-send"]}','OUTPUT');await s.saveContract()
 assert.equal(calls[0][0],11);assert.equal(JSON.parse(calls[0][1]).source,'OUTPUT');assert.equal(Object.hasOwn(JSON.parse(calls[0][1]),'inputFrom'),false)
 s.contractFormOpen.value=true;s.contractConfig.value='{}';await s.saveContract();assert.equal(calls[1][1],null)
})
test('contract writes block double submit, discard stale selection and retain unconfirmed errors',async()=>{
 const wait=deferred();let calls=0;const s=editor({updatePlanOutputContract:()=>{calls++;return wait.promise}})
 const first=s.saveContract();await s.saveContract();assert.equal(calls,1)
 s.selectedPlanId.value=9;s.selectPlan=async()=>assert.fail('stale contract response');wait.resolve();await first
 s.selectedPlanId.value=7;s.EapApi.updatePlanOutputContract=async()=>{throw Error('lost response')};await s.saveContract()
 assert.match(s.contractFormError.value,/不会自动重试/);assert.equal(s.savingContract.value,false)
})

test('plan presentation preserves unknown and future statuses without implying success',()=>{
 const s=handlers('views/OrchestrationView.vue',['statusLabel','statusType'],{})
 for(const status of ['UNKNOWN','EXECUTION_UNKNOWN']) {
  assert.equal(s.statusLabel(status),'结果未知');assert.equal(s.statusType(status),'warning')
 }
 assert.equal(s.statusLabel('FUTURE_STATE'),'FUTURE_STATE');assert.equal(s.statusType('FUTURE_STATE'),'info')
 assert.equal(s.statusType('FAILED'),'danger');assert.equal(s.statusType('DONE'),'success')
})
