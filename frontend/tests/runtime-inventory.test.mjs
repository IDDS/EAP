import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import vm from 'node:vm'
const source=readFileSync(new URL('../src/components/RuntimeInventoryPanel.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function handler(name,globals){
 const ast=ts.createSourceFile('inventory.ts',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS)
 const node=ast.statements.find(n=>ts.isFunctionDeclaration(n)&&n.name.text===name)
 const context=vm.createContext(globals);vm.runInContext(ts.transpile(node.getText(ast),{target:ts.ScriptTarget.ES2022}),context);return context[name]
}
test('late inventory replies cannot restore stale scope or revoked data',async()=>{
 let allowed=true;const pending=[]
 const globals={generation:0,alive:true,loading:{value:false},error:{value:''},snapshot:{value:undefined},permissions:{can:()=>allowed},http:{get:()=>new Promise(resolve=>pending.push(resolve))}}
 const load=handler('load',globals)
 const old=load(),recent=load()
 pending[1]({data:{data:{scopes:['new']}}});await recent
 pending[0]({data:{data:{scopes:['old']}}});await old
 assert.equal(globals.snapshot.value.scopes[0],'new')
 const revoked=load();allowed=false;pending[2]({data:{data:{scopes:['secret']}}});await revoked
 assert.equal(globals.snapshot.value,undefined)
})
test('failed inventory refresh clears the old list rather than reporting a healthy empty scope',async()=>{
 const globals={generation:0,alive:true,loading:{value:false},error:{value:''},snapshot:{value:{scopes:['old']}},permissions:{can:()=>true},http:{get:async()=>{throw Error('down')}}}
 await handler('load',globals)()
 assert.equal(globals.snapshot.value,undefined);assert.ok(globals.error.value);assert.equal(globals.loading.value,false)
})
test('revoked enrollment and control permissions prevent writes from already open views',async()=>{
 let writes=0
 const globals={selected:{value:{uid:'u'}},targetAgent:{value:7},busy:{value:''},permissions:{can:()=>false},postData:()=>writes++,SubstrateApi:{control:()=>writes++}}
 await handler('adopt',globals)()
 await handler('control',globals)({agentId:7},'PAUSE')
 assert.equal(writes,0)
})

function panelHarness(){
 const pending=[],watchers=[],mounts=[];let allowed=false
 const permissions={state:{user:null},can:()=>allowed}
 const context=vm.createContext({ref:value=>({value}),defineProps:()=>({}),usePermissions:()=>permissions,
  watch:(read,callback)=>watchers.push({read,callback}),onMounted:callback=>mounts.push(callback),onBeforeUnmount:()=>{},
  http:{get:()=>new Promise(resolve=>pending.push(resolve))}})
 const ast=ts.createSourceFile('inventory.ts',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS)
 const body=ast.statements.filter(n=>!ts.isImportDeclaration(n)).map(n=>n.getText(ast)).join('\n')
 vm.runInContext(ts.transpile(body+'\nglobalThis.view={snapshot,loading,error,connectionKey,adoptOpen,agents}',{target:ts.ScriptTarget.ES2022}),context)
 return {pending,mount:()=>mounts[0](),view:context.view,identity:user=>{permissions.state.user=user;allowed=!!user;watchers[1].callback(user)}}
}
const settled=()=>new Promise(resolve=>setImmediate(resolve))
test('inventory loads after delayed identity resolution and leaves no permanent spinner',async()=>{
 const h=panelHarness();h.mount()
 assert.equal(h.pending.length,0,'anonymous mounting must wait for console permission')
 assert.equal(h.view.loading.value,false)
 h.identity({id:'operator'});assert.equal(h.pending.length,1)
 h.pending[0]({data:{data:{scopes:['operator-scope']}}});await settled()
 assert.equal(h.view.snapshot.value.scopes[0],'operator-scope');assert.equal(h.view.loading.value,false)
})
test('identity switch reloads the inventory while logout cancels pending data and loading',async()=>{
 const h=panelHarness();h.identity({id:'first'});h.identity({id:'second'})
 assert.equal(h.pending.length,2,'new identity must have its own inventory request')
 h.pending[0]({data:{data:{scopes:['first-private']}}});await settled();assert.equal(h.view.snapshot.value,undefined)
 h.pending[1]({data:{data:{scopes:['second-private']}}});await settled();assert.equal(h.view.snapshot.value.scopes[0],'second-private')
 h.identity({id:'third'});h.view.connectionKey.value='old-connection';h.view.adoptOpen.value=true;h.identity(null)
 assert.equal(h.view.snapshot.value,undefined);assert.equal(h.view.loading.value,false)
 assert.equal(h.view.connectionKey.value,'');assert.equal(h.view.adoptOpen.value,false)
 h.pending[2]({data:{data:{scopes:['third-private']}}});await settled();assert.equal(h.view.snapshot.value,undefined)
 assert.equal(h.pending.length,3,'logout must not issue an anonymous request')
})
