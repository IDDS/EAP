import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import * as Vue from 'vue'

const source = readFileSync(new URL('../src/components/workflow/PlanOutputs.vue', import.meta.url), 'utf8')
  .split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const code = ts.transpileModule(source, {compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return {promise,resolve,reject} }

function mount(t, getWorkflowOutputs) {
  const props=Vue.reactive({workflowId:65,runKey:'original'})
  const permissions=Vue.reactive({state:{user:{subject:'first'}},allowed:true,can(name){return name==='viewContent' && this.allowed}})
  const scope=Vue.effectScope();let unmount
  const args={ref:Vue.ref,watch:Vue.watch,onUnmounted:fn=>{unmount=fn},defineProps:()=>props,usePermissions:()=>permissions,EapApi:{getWorkflowOutputs}}
  const state=scope.run(()=>new Function(...Object.keys(args),code+';return {outputs,error,loading,loadOutputs}')( ...Object.values(args)))
  t.after(()=>{unmount();scope.stop()})
  return {...state,props,permissions,unmount}
}

test('account switch synchronously clears visible ticket content and ignores the old pending response',async t=>{
  const wait=deferred();let calls=0
  const s=mount(t,()=>++calls===1?Promise.resolve({lookup:{description:'synthetic private ticket'}}):wait.promise)
  await s.loadOutputs();assert.equal(s.outputs.value.lookup.description,'synthetic private ticket')
  const pending=s.loadOutputs();s.permissions.state.user={subject:'second'}
  assert.equal(s.outputs.value,null);assert.equal(s.loading.value,false)
  wait.resolve({lookup:{description:'late old account content'}});await pending
  assert.equal(s.outputs.value,null);assert.equal(s.error.value,'');assert.equal(calls,2)
})

test('permission loss, execution changes and failed refresh never retain old ticket content',async t=>{
  const s=mount(t,async()=>({lookup:{description:'synthetic private ticket'}}))
  await s.loadOutputs();s.permissions.allowed=false;assert.equal(s.outputs.value,null)
  s.permissions.allowed=true;await s.loadOutputs();s.props.runKey='other';assert.equal(s.outputs.value,null)
  const failed=mount(t,async()=>{throw Error('403 private server response')})
  failed.outputs.value={lookup:{description:'old ticket'}};await failed.loadOutputs()
  assert.equal(failed.outputs.value,null);assert.match(failed.error.value,/结果读取失败/);assert.doesNotMatch(failed.error.value,/private server response/)
})
