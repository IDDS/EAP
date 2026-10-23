import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import ts from 'typescript'
import vm from 'node:vm'
const source=readFileSync(new URL('../src/components/RuntimeConnectionPanel.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
function scope(globals){const ast=ts.createSourceFile('connection.ts',source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TS);const code=ast.statements.filter(ts.isFunctionDeclaration).map(n=>n.getText(ast)).join('\n');const context=vm.createContext(globals);vm.runInContext(ts.transpile(code,{target:ts.ScriptTarget.ES2022}),context);return context}
const ref=value=>({value})
function state(){return {alive:true,generation:0,requestKey:'',automaticRequestKey:'',credential:ref(),status:ref({connection:'NOT_CONFIGURED'}),error:ref(''),busy:ref(''),loading:ref(false),props:{instanceKey:'space/runtime'},permissions:{state:{user:{}},can:()=>true},crypto:{randomUUID:()=> 'request-id'}}}
test('late credential cannot reappear after permissions or runtime change',async()=>{
 let resolve,writes=0;const g=state();g.http={post:()=>{writes++;return new Promise(r=>resolve=r)}};const s=scope(g)
 const pending=s.issue();s.clear();g.permissions.state.user={};resolve({data:{data:{token:'synthetic-secret'}}});await pending
 assert.equal(writes,1);assert.equal(g.credential.value,undefined)
})
test('no-capability actions do not issue, verify, revoke or copy credentials',async()=>{
 const g=state();g.permissions.can=()=>false;g.credential.value={token:'must-not-copy'};const s=scope(g)
 for(const name of ['issue','automatic','verify','revoke','copyCredential'])await s[name]()
 assert.equal(g.busy.value,'')
})
test('closing the connection panel clears credential and invalidates pending state reads',async()=>{
 let resolve;const g=state();g.credential.value={token:'synthetic-secret'};g.http={get:()=>new Promise(r=>resolve=r)};const s=scope(g)
 const pending=s.load();s.clear();resolve({data:{data:{connection:'READY'}}});await pending
 assert.equal(g.credential.value,undefined);assert.equal(g.status.value,undefined)
})

test('automatic delivery keeps its original request after a lost response and never exposes a credential',async()=>{
 const g=state();let calls=[];g.http={post:async(_url,body)=>{calls.push(body.requestKey);throw Error('lost reply')}};const s=scope(g);await s.automatic();await s.automatic();assert.deepEqual(calls,['request-id','request-id']);assert.equal(g.credential.value,undefined)
})

test('reopened panel resumes the persisted delivery operation after losing the original response',async()=>{
 const g=state();g.status.value.deliveryOperation='persisted-original-operation';let sent;g.http={post:async(_url,body)=>{sent=body.requestKey;throw Error('lost reply')}};const s=scope(g);await s.automatic();assert.equal(sent,'persisted-original-operation')
})


test('task-managed instance never starts human approval or generic verification',async()=>{
 const g=state();g.status.value.taskManaged=true;let writes=0;g.http={post:async()=>{writes++;throw Error('unexpected human task approval')}};const s=scope(g)
 for(const name of ['issue','automatic','verify'])await s[name]()
 assert.equal(writes,0);assert.equal(g.busy.value,'')
})
