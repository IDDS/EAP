import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import vm from 'node:vm'
import ts from 'typescript'
function functions(file,names,globals){
 let source=readFileSync(new URL('../src/'+file,import.meta.url),'utf8');if(file.endsWith('.vue'))source=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
 const ast=ts.createSourceFile('test.ts',source,ts.ScriptTarget.Latest,true)
 const code=ast.statements.filter(n=>ts.isFunctionDeclaration(n)&&names.includes(n.name.text)).map(n=>n.getText(ast).replace(/^export /,'')).join('\n')
 const context=vm.createContext(globals);vm.runInContext(ts.transpile(code,{target:ts.ScriptTarget.ES2022}),context);return context
}
const ref=value=>({value})
const pending=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{resolve,promise}}
test('missing, failed and newest nonfinite values never become healthy zero or stale values',()=>{
 const s=functions('api/substrateMonitoring.ts',['latest','metricText'],{})
 assert.equal(s.latest(),null);assert.equal(s.latest({status:'ERROR',series:[]}),null)
 assert.equal(s.latest({status:'OK',series:[{points:[{value:3},{value:null}]}]}),null)
 assert.equal(s.latest({status:'OK',series:[{points:[{value:0}]}]}),0);assert.equal(s.metricText(null),'—')
})
function state(api){return functions('views/SubstrateMonitorView.vue',['refresh','inspect'],{
 backend:ref('RUNTIME'),minutes:ref(60),snapshot:ref(),loading:ref(false),error:ref(''),revision:0,instanceRevision:0,disposed:false,
 permissions:{can:()=>true},fetchSubstrateMonitoring:api,selected:ref(),drawerOpen:ref(false),instances:ref([]),instanceLoading:ref(false),instanceError:ref(''),SubstrateApi:{instances:async()=>[]}
})}
test('window changes discard old responses and error clears stale charts',async()=>{
 const first=pending(),second=pending();let calls=0;const s=state(()=>++calls===1?first.promise:second.promise)
 const a=s.refresh();s.minutes.value=15;const b=s.refresh();second.resolve({windowMinutes:15});await b;first.resolve({windowMinutes:60});await a;assert.equal(s.snapshot.value.windowMinutes,15)
 s.fetchSubstrateMonitoring=async()=>{throw new Error('503')};await s.refresh();assert.equal(s.snapshot.value,undefined);assert.match(s.error.value,/未显示旧数据/)
})
test('revoked permission and unmount suppress monitoring responses',async()=>{
 for(const mutate of [s=>s.permissions.can=()=>false,s=>s.disposed=true]){const wait=pending();const s=state(()=>wait.promise);const p=s.refresh();mutate(s);wait.resolve({windowMinutes:60});await p;assert.equal(s.snapshot.value,undefined)}
 const s=state(()=>assert.fail('must not query'));s.permissions.can=()=>false;await s.refresh()
})
test('instance drawer ignores old selection and filters versions',async()=>{
 const wait=pending();const s=state();let calls=0;s.SubstrateApi.instances=async()=>++calls===1?wait.promise:[{name:'new',version:2},{name:'other',version:1}]
 const first=s.inspect({agentId:1,version:1});await s.inspect({agentId:2,version:2});wait.resolve([{name:'old',version:1}]);await first
 assert.equal(s.instances.value.length,1);assert.equal(s.instances.value[0].name,'new')
})

test('pool switch cannot mix AX and native metrics when responses arrive out of order',async()=>{
 const old=pending(),fresh=pending();const seen=[];const s=state((minutes,backend)=>{seen.push(backend);return backend==='AX'?fresh.promise:old.promise})
 const a=s.refresh();s.backend.value='AX';s.snapshot.value=undefined;const b=s.refresh()
 fresh.resolve({backend:'AX',windowMinutes:60,scope:'eap-ax'});await b;old.resolve({backend:'RUNTIME',windowMinutes:60,scope:'eap-agents'});await a
 assert.equal(s.snapshot.value.scope,'eap-ax');assert.deepEqual(seen,['RUNTIME','AX'])
})
