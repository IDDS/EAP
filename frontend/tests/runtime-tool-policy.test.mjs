import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import vm from 'node:vm'
const source=readFileSync(new URL('../src/components/RuntimeToolPolicyPanel.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function scope(globals){const ast=ts.createSourceFile('policy.ts',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);const code=ast.statements.filter(ts.isFunctionDeclaration).map(n=>n.getText(ast)).join('\n');const context=vm.createContext(globals);vm.runInContext(ts.transpile(code,{target:ts.ScriptTarget.ES2022}),context);return context}
const policy=()=>({agentId:7,revision:3,enabled:true,allowedTools:[{name:'calculate_sum',kind:'TOOL'}],claimLimit:10,claimedCount:2,remainingClaims:8})
function state(){return {alive:true,generation:0,policy:{value:policy()},loading:{value:false},busy:{value:false},editing:{value:false},error:{value:''},draft:{value:{}},props:{instanceKey:'space/one'},permissions:{state:{user:{}},can:()=>true}}}
test('switching identity clears authorization and discards an old read',async()=>{
 let resolve;const g=state();g.http={get:()=>new Promise(r=>resolve=r)};const s=scope(g);const pending=s.load();s.clear();g.permissions.state.user={};resolve({data:{data:policy()}});await pending
 assert.equal(g.policy.value,undefined);assert.equal(g.loading.value,false);assert.equal(g.editing.value,false)
})
test('revoked governance capability blocks writes even when a stale form is open',async()=>{
 const g=state();let writes=0;g.http={put:async()=>{writes++;return {data:{data:policy()}}}};const s=scope(g);s.edit();g.permissions.can=c=>c==='viewConsole';await s.save();assert.equal(writes,0)
})
test('edits preserve the server version and unknown save forces a fresh read',async()=>{
 const g=state();let body;g.http={put:async(_url,data)=>{body=data;throw Error('lost response')}};const s=scope(g);s.edit();g.draft.value.claimLimit=12;await s.save()
 assert.equal(body.expectedRevision,3);assert.equal(body.claimLimit,12);assert.equal('claimedCount' in body,false)
 assert.equal(g.policy.value,undefined);assert.equal(g.editing.value,false);assert.match(g.error.value,/未确认/);assert.equal(g.busy.value,false)
})
test('late save cannot repopulate another instance or retain an editable grant',async()=>{
 let resolve;const g=state();g.http={put:()=>new Promise(r=>resolve=r)};const s=scope(g);s.edit();const pending=s.save();s.clear();g.props.instanceKey='space/two';resolve({data:{data:policy()}});await pending
 assert.equal(g.policy.value,undefined);assert.equal(g.editing.value,false)
})
test('invalid scope and decreasing the budget below usage cannot be submitted',async()=>{
 const g=state();let writes=0;g.http={put:async()=>{writes++}};const s=scope(g);s.edit();g.draft.value.claimLimit=1;await s.save();g.draft.value.claimLimit=5;g.draft.value.allowedTools=[{name:'*',kind:'TOOL'}];await s.save();assert.equal(writes,0)
})
test('concurrent edit conflict requires a fresh policy and cannot blindly retry',async()=>{
 const g=state();let writes=0;g.http={put:async()=>{writes++;throw {response:{status:409}}}};const s=scope(g);s.edit();await s.save();await s.save();assert.equal(writes,1);assert.match(g.error.value,/其他人修改/)
})
