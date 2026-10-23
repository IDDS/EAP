import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
function mount(overrides={}){
 const source=readFileSync(new URL('../src/components/BudgetPanel.vue',import.meta.url),'utf8').split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm,'')
 const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.None}}).outputText
 const permission={state:{user:{subject:'a'}},can:()=>true};let reset;const notifications=[]
 const values={ref:value=>({value}),watch:(_s,fn)=>{reset=fn},onBeforeUnmount:()=>{},usePermissions:()=>permission,BudgetApi:{get:async()=>({enabled:true,accounts:[],reservations:[]}),set:async()=>{},...overrides},EapApi:{listAgents:async()=>[]},ElMessage:{warning:m=>notifications.push(m),success:m=>notifications.push(m)}}
 const scope=new Function(...Object.keys(values),code+';return {data,draft,busy,uncertain,open,save,load,remaining,percent}')( ...Object.values(values))
 return {...scope,permission,reset:()=>reset(),notifications}
}
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
test('budget cannot save empty scope or invalid numeric limit',async()=>{
 let writes=0;const s=mount({set:async()=>{writes++}});await s.save();s.draft.value.scopeKey='71';s.draft.value.limitAmount=-1;await s.save();assert.equal(writes,0)
})
test('double budget update is suppressed and lost receipt keeps expected revision',async()=>{
 const pending=deferred(),bodies=[];const s=mount({set:body=>{bodies.push(body);return pending.promise}});Object.assign(s.draft.value,{scopeKey:'71',limitAmount:20,expectedRevision:4});const first=s.save();await s.save();assert.equal(bodies.length,1);pending.reject(new Error('lost update response'));await first;assert.equal(s.uncertain.value,true);assert.equal(s.draft.value.expectedRevision,4);await s.save();assert.equal(bodies.length,1)
})
test('budget metadata and in-flight response are discarded after identity changes',async()=>{
 const pending=deferred();const s=mount({get:()=>pending.promise});const first=s.load();s.permission.state.user={subject:'b'};s.permission.can=()=>false;s.reset();pending.resolve({enabled:true,accounts:[{scopeKey:'old-account'}],reservations:[]});await first;assert.deepEqual(s.data.value.accounts,[])
})
test('unlimited or overcommitted budget never shows fabricated remaining credits',()=>{
 const s=mount();const a={usedAmount:7,reservedAmount:4,marginAmount:1,limitAmount:null};assert.equal(s.remaining(a),null);a.limitAmount=8;assert.equal(s.remaining(a),0);assert.equal(s.percent(a),100);a.limitAmount=0;assert.equal(s.percent(a),100)
})
