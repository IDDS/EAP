import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

function handler(file, name, globals) {
  const source = readFileSync(new URL(`../src/views/${file}`, import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  const declaration = ast.statements.find(node => ts.isFunctionDeclaration(node) && node.name.text === name)
  const context = vm.createContext(globals)
  vm.runInContext(ts.transpile(declaration.getText(ast), { target: ts.ScriptTarget.ES2022 }), context)
  return context[name]
}

test('AX submit cannot write after capability revocation', async () => {
 let writes=0
 const fn=handler('AxTasksView.vue','submit',{submitting:{value:false},permissions:{can:()=>false},postData:async()=>{writes++}})
 await fn();assert.equal(writes,0)
})
test('AX content ignores a late response after selection or capability change', async () => {
 let resolve;const pending=new Promise(r=>resolve=r)
 const selected={value:{taskKey:'a'}}, content={value:null};let allowed=true
 const fn=handler('AxTasksView.vue','loadContent',{permissions:{can:()=>allowed},selected,content,contentLoading:{value:false},contentGeneration:0,alive:true,detailOpen:{value:true},getData:()=>pending})
 const done=fn();selected.value={taskKey:'b'};allowed=false;resolve({output:'private'});await done;assert.equal(content.value,null)
})
test('AX submission failure retains the same idempotency key', async () => {
 const requestKey={value:'stable'},submitting={value:false}
 const fn=handler('AxTasksView.vue','submit',{submitting,permissions:{can:()=>true},cap:{value:{enabled:true}},form:{agentId:1,objective:'test'},requestKey,postData:async()=>{throw new Error('lost response')}})
 await fn();assert.equal(requestKey.value,'stable');assert.equal(submitting.value,false)
})

test('AX deep link only loads metadata and discards a response after navigating away',async()=>{
 let resolve;const waiting=new Promise(r=>resolve=r);let selectedTask=null;const route={query:{taskKey:'ax-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'}}
 const fn=handler('AxTasksView.vue','openLinkedTask',{clearContent:()=>{},contentGeneration:1,selected:{value:null},detailOpen:{value:false},route,alive:true,getData:()=>waiting,select:task=>{selectedTask=task}})
 const done=fn();route.query.taskKey='ax-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';resolve({taskKey:'old'});await done;assert.equal(selectedTask,null)
})
test('backend selection preserves node inputs and cannot mutate after permission loss',()=>{
 const selectedNode={value:{configJson:JSON.stringify({inputFrom:['previous'],taskObjective:'review'})}}
 let allowed=true;const fn=handler('WorkflowBuilderView.vue','setNodeBackend',{nodeHasTool:{value:false},selectedNode,permissions:{can:()=>allowed},ElMessage:{error:()=>assert.fail('valid config')}})
 fn('AX');assert.deepEqual(JSON.parse(selectedNode.value.configJson),{inputFrom:['previous'],taskObjective:'review',executionBackend:'AX'})
 allowed=false;fn('RUNTIME');assert.equal(JSON.parse(selectedNode.value.configJson).executionBackend,'AX')
})
