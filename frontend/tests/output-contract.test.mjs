import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'
const source=readFileSync(new URL('../src/api/workflowTaskConfig.ts',import.meta.url),'utf8')
const scope={exports:{}}
new Function('exports','TextEncoder',ts.transpile(source,{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}))(scope.exports,TextEncoder)
const api=scope.exports
const draft=()=>api.setOutputContract('{"inputFrom":["previous"],"suspendAfterTask":true,"eapTool":{"name":"custom"}}','JSON_TEXT')
test('contract source edits and removal preserve tool, handoff and reclaim configuration',()=>{
  const raw=draft(),next=api.setOutputContract(raw,'OUTPUT'),removed=JSON.parse(api.setOutputContract(next,'OFF'))
  assert.equal(api.outputContractError(raw,'AGENT',1),'')
  assert.deepEqual(JSON.parse(raw).eapOutput.schema,JSON.parse(next).eapOutput.schema)
  assert.deepEqual(removed,{inputFrom:['previous'],suspendAfterTask:true,eapTool:{name:'custom'}})
})
test('invalid schema drafts cannot silently retain the previously valid schema',()=>{
  const raw=api.updateOutputSchema(draft(),'{ invalid')
  assert.equal(JSON.parse(raw).eapOutput.schema,'{ invalid')
  assert.notEqual(api.outputContractError(raw,'AGENT',1),'')
  assert.notEqual(api.outputContractError(api.setOutputContract(raw,'OUTPUT'),'AGENT',1),'')
})
test('refuses unsupported types, keywords, required fields and additional properties schemas',()=>{
  const good=JSON.parse(draft()).eapOutput.schema
  for(const bad of [{...good,$ref:'https://example.invalid'},{...good,required:['missing']},{...good,required:['summary','summary']},{...good,additionalProperties:{}},{...good,properties:{summary:{type:'string',pattern:'.*'}}},{type:'array',items:{type:'string'}},null]) {
    assert.notEqual(api.outputContractError(api.updateOutputSchema(draft(),JSON.stringify(bad)),'AGENT',1),'')
  }
})
test('rejects AX and control nodes without changing the original draft',()=>{
  assert.throws(()=>api.setOutputContract('{"executionBackend":"AX"}','JSON_TEXT'))
  assert.notEqual(api.outputContractError(draft(),'START',1),'')
  assert.notEqual(api.outputContractError(draft(),'AGENT'),'')
  assert.notEqual(api.outputContractError(JSON.stringify({...JSON.parse(draft()),executionBackend:'AX'}),'AGENT',1),'')
})
test('bounded nested schemas accept primitive and array fields but refuse excessive shape or UTF-8 size',()=>{
  const schema=JSON.parse(draft()).eapOutput.schema
  schema.properties.items={type:'array',items:{type:'boolean'}}
  assert.equal(api.outputContractError(api.updateOutputSchema(draft(),JSON.stringify(schema)),'ARTIFACT',1),'')
  let deep={type:'string'};for(let i=0;i<8;i++)deep={type:'array',items:deep}
  schema.properties.deep=deep
  assert.notEqual(api.outputContractError(api.updateOutputSchema(draft(),JSON.stringify(schema)),'AGENT',1),'')
  delete schema.properties.deep;schema.properties=Object.fromEntries(Array.from({length:64},(_,i)=>['界'.repeat(120)+i,{type:'string'}]));schema.required=[]
  assert.notEqual(api.outputContractError(api.updateOutputSchema(draft(),JSON.stringify(schema)),'AGENT',1),'')
})
const component=readFileSync(new URL('../src/components/workflow/WorkflowOutputConfig.vue',import.meta.url),'utf8')
const script=ts.transpile(component.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,''),{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None})
function mount(t){
  const props=Vue.reactive({modelValue:'{}',nodeType:'AGENT',agentId:1,disabled:false}),emitted=[],errors=[]
  const args={...api,computed:Vue.computed,ref:Vue.ref,watch:Vue.watch,defineProps:()=>props,defineEmits:()=>((event,value)=>{emitted.push(value);props.modelValue=value}),ElMessage:{error:e=>errors.push(e)}}
  const effect=Vue.effectScope();let state
  effect.run(()=>state=new Function(...Object.keys(args),script+';return {changeSource,changeSchema,schemaText,error}')( ...Object.values(args)))
  t.after(()=>effect.stop());return{...state,props,emitted,errors}
}
test('editing and clearing the form works while permission revocation blocks subsequent writes',async t=>{
  const s=mount(t);s.changeSource('JSON_TEXT');await Vue.nextTick();assert.match(s.schemaText.value,/summary/)
  s.changeSchema('{bad');await Vue.nextTick();assert.ok(s.error.value)
  s.props.disabled=true;const count=s.emitted.length;s.changeSource('OFF');s.changeSchema('{}');assert.equal(s.emitted.length,count)
  s.props.disabled=false;s.changeSource('OFF');await Vue.nextTick();assert.equal(s.schemaText.value,'');assert.equal(s.error.value,'')
})
test('switching nodes replaces the schema draft and refuses edits to malformed advanced configuration',async t=>{
  const s=mount(t);s.changeSource('OUTPUT');s.props.modelValue=api.updateOutputSchema(draft(),'{ broken');await Vue.nextTick()
  assert.equal(s.schemaText.value,'{ broken')
  s.props.modelValue='{bad';await Vue.nextTick();const count=s.emitted.length;s.changeSchema('{}');assert.equal(s.emitted.length,count);assert.ok(s.errors.length)
})
test('timeline distinguishes successful receipt, rejected result, unknown and legacy lack of validation',()=>{
  const template=readFileSync(new URL('../src/components/workflow/ExecutionTimeline.vue',import.meta.url),'utf8')
  const code=ts.transpile(template.split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,''),{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None})
  const labels=new Function('defineProps','ref','watch',code+';return {label,validationLabel,validationReason}')(()=>({execution:{runKey:'original'}}),Vue.ref,()=>{})
  assert.equal(labels.label('SUCCEEDED'),'已确认完成')
  assert.match(labels.validationLabel('REJECTED'),/停止后续交接/)
  assert.match(labels.validationLabel('NOT_EVALUATED'),/未确认成功/)
  assert.equal(labels.validationLabel(undefined),'未记录结构验收')
  assert.equal(labels.validationLabel('FORGED_SUCCESS'),'未记录结构验收')
  assert.match(labels.validationReason('INVALID_JSON'),/无歧义/)
})

test('plan definitions reuse structure validation without bypassing workflow agent requirements',()=>{
 const raw=draft();assert.notEqual(api.outputContractError(raw,'AGENT'),'')
 assert.equal(api.outputContractError(raw,'AGENT',undefined,true),'')
 assert.equal(JSON.parse(api.planOutputValue(raw)).version,'EAP_JSON_V1')
 assert.throws(()=>api.planOutputValue(raw,'AX'))
 assert.equal(api.planOutputLabel(null),'未配置');assert.equal(api.planOutputLabel('{}'),'配置需核对')
 assert.equal(api.planOutputLabel(api.planOutputValue(raw)),'JSON 文本结构')
})
