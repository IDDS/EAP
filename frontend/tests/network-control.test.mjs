import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'
import { ref, computed, reactive } from 'vue'

function evaluate(file, names, globals = {}, vue = false) {
  let source = readFileSync(new URL('../src/' + file, import.meta.url), 'utf8')
  if (vue) source = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const ast = ts.createSourceFile('test.ts', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS)
  source = ast.statements.filter(n => !ts.isImportDeclaration(n)).map(n => n.getText(ast).replace(/^export /, '')).join('\n')
  const ctx = vm.createContext({ ...globals, ref, computed, watch() {}, onBeforeUnmount() {} })
  vm.runInContext(ts.transpile(source + '\nglobalThis.subject = {' + names + '}', { target: ts.ScriptTarget.ES2022 }), ctx)
  return ctx.subject
}
const graph = evaluate('utils/networkGraph.ts', 'graphScene,bundleEdges,fitCamera,zoomCamera,edgePath,controlBusPath,controlBusPaths')
const nodes = [{id:'control:eap',plane:'CONTROL'}, ...Array.from({length:23}, (_,i) => ({id:`agent:${i+1}`,plane:'DATA'}))]
const edge = (source,target,id='a') => ({id, source,target,plane:'CONTROL',label:id})

test('all 23 agents remain reachable; scene bounds grow past legacy 620px canvas', () => {
  const scene = graph.graphScene(nodes,[], '',false)
  assert.equal(scene.nodes.length,24)
  assert.ok(scene.height > 1200)
  for (const n of scene.nodes) { assert.ok(n.x+196<=scene.width);assert.ok(n.y+92<=scene.height) }
  const camera = graph.fitCamera(600,570,scene.width,scene.height)
  assert.ok(camera.y + scene.height * camera.scale <=570)
  assert.ok(camera.x + scene.width * camera.scale <=600)
})
test('focus includes both endpoints and removes dangling edges; repeated declarations remain inspectable', () => {
  const edges = Array.from({length:13},(_,i)=>edge('control:eap','agent:23','w'+i))
  edges.push(edge('agent:1','agent:2'),edge('missing','agent:23'))
  const scene = graph.graphScene(nodes,edges,'agent:23',true)
  assert.deepEqual(Array.from(scene.nodes,n=>n.id),['control:eap','agent:23'])
  assert.equal(scene.edges.length,1);assert.equal(scene.edges[0].count,13);assert.equal(scene.edges[0].relations.length,13)
})
test('zoom preserves the world coordinate under the pointer and paths support loops', () => {
  const old={x:12,y:-80,scale:.5}, next=graph.zoomCamera(old,1.25,300,200)
  assert.equal((300-old.x)/old.scale,(300-next.x)/next.scale)
  assert.equal((200-old.y)/old.scale,(200-next.y)/next.scale)
  assert.match(graph.edgePath({id:'a',x:0,y:0},{id:'a',x:0,y:0}),/^M .* C /)
})
function panel(getTargets = async () => ({instances:[],dispatchEnabled:true,maxAttempts:3})) {
  const props=reactive({agents:[],agentId:1,commands:[],refreshing:false}), messages=[],writes=[],events=[]
  const permissions=reactive({state:{user:{subject:'a'}},can:()=>true})
  const s=evaluate('components/AgentControlPanel.vue','loadTargets,issueCommand,retryCommand,notifyResult,canSubmit,targets,instanceKey,commandType,payloadJson,targetLoading,targetError,commandLoading,allMessages,page,pageCommands,canRetry',{
    defineProps:()=>props, defineEmits:()=> (...args)=>events.push(args),usePermissions:()=>permissions,getControlTargets:getTargets,
    ElMessage:{error:m=>messages.push(['error',m]),success:m=>messages.push(['success',m]),warning:m=>messages.push(['warning',m])},
    ElMessageBox:{confirm:async()=>{}}, EapApi:{issueControlCommand:async p=>{writes.push(p);return {status:'ACKED'}},retryControlCommand:async k=>{writes.push(k);return {status:'ACKED'}}}
  },true)
  return {...s,props,permissions,messages,writes,events}
}
const available=key=>({instances:[{instanceKey:key,status:'ONLINE',commands:['RUN_HEALTH_CHECK','PAUSE']}],dispatchEnabled:true,maxAttempts:3})
test('absent runtime and readonly permission cannot submit; concrete selected instance is sent',async()=>{
  const s=panel(async()=>available('instance-a'));await s.issueCommand();assert.equal(s.writes.length,0)
  await s.loadTargets();s.permissions.can=()=>false;await s.issueCommand();assert.equal(s.writes.length,0)
  s.permissions.can=()=>true;await s.issueCommand();assert.equal(s.writes[0].runtimeInstanceKey,'instance-a')
})
test('unsupported command and malformed config JSON never reach dispatch',async()=>{
  const s=panel(async()=>available('instance-a'));await s.loadTargets();s.commandType.value='UPDATE_CONFIG';await s.issueCommand();assert.equal(s.writes.length,0)
  s.targets.value.instances[0].commands.push('UPDATE_CONFIG');s.payloadJson.value='[]';await s.issueCommand();assert.equal(s.writes.length,0);assert.equal(s.messages.at(-1)[0],'warning')
})
test('HTTP 200 command failure is an error, queued is pending, only ACK is success',()=>{
  const s=panel();for(const status of ['FAILED','QUEUED','DISPATCHED','ACKED'])s.notifyResult({status})
  assert.deepEqual(s.messages.map(m=>m[0]),['error','warning','warning','success'])
})
test('out-of-order target requests cannot put an old instance into the new agent form',async()=>{
  let resolveA,resolveB;const a=new Promise(r=>resolveA=r),b=new Promise(r=>resolveB=r)
  const s=panel(id=>id===1?a:b),first=s.loadTargets();s.props.agentId=2;const second=s.loadTargets()
  resolveB(available('b'));await second;resolveA(available('a'));await first
  assert.equal(s.instanceKey.value,'b');assert.equal(s.targetLoading.value,false)
})
test('identity switch discards a pending target response',async()=>{
  let resolve;const s=panel(()=>new Promise(r=>resolve=r)),pending=s.loadTargets()
  s.permissions.state.user={subject:'b'};resolve(available('old'));await pending;assert.equal(s.targets.value,undefined);assert.equal(s.instanceKey.value,'')
})
test('message list is limited to four rows per page and selected agent by default',()=>{
  const s=panel();s.props.commands=Array.from({length:20},(_,i)=>({commandKey:'c'+i,agentId:i<12?1:2}));
  assert.equal(s.pageCommands.value.length,4);s.page.value=3;assert.equal(s.pageCommands.value[0].commandKey,'c8')
  s.allMessages.value=true;s.page.value=5;assert.equal(s.pageCommands.value[0].commandKey,'c16')
})
test('retry never uses a different selected instance or bypasses attempt limit',async()=>{
  const s=panel(async()=>available('a'));await s.loadTargets();const c={agentId:1,runtimeInstanceKey:'b',status:'FAILED',attemptCount:1,commandType:'PAUSE'}
  assert.equal(s.canRetry(c),false);c.runtimeInstanceKey='a';assert.equal(s.canRetry(c),true);c.attemptCount=3;assert.equal(s.canRetry(c),false)
})

test('control dispatch and retry allow the runtime RPC deadline instead of the default 12s timeout',async()=>{
  const calls=[];const api=evaluate('api/control.ts','sendControlCommand,retryControlCommand',{http:{post:async(...args)=>{calls.push(args);return {data:{data:{status:'ACKED'}}}}}})
  await api.sendControlCommand({agentId:7,runtimeInstanceKey:'x',commandType:'RESUME'});await api.retryControlCommand('CTRL-test')
  assert.equal(calls.length,2);assert.ok(calls.every(c=>c[2].timeout===90000))
})

for(const columns of [4,6]) test(`overview ${columns}-column control fan-out shares trunks without crossing cards`,()=>{
  const all=[...Array.from({length:4},(_,i)=>({id:'control:'+i,plane:'CONTROL'})),...nodes.slice(1)]
  const scene=graph.graphScene(all,[], '',false,columns),controls=scene.nodes.slice(0,3),agents=scene.nodes.slice(4)
  for(const [lane,source] of controls.entries()) {
    let trunk
    for(const target of agents) {
      const path=graph.controlBusPath(source,target,lane),numbers=path.match(/-?\d+(?:\.\d+)?/g).map(Number)
      const prefix=path.split(' V ').slice(0,2).join(' V ')
      if(trunk)assert.equal(prefix,trunk);trunk=prefix
      const [sx,sy,y1,x1,y2,x2,y3]=numbers,points=[[sx,sy],[sx,y1],[x1,y1],[x1,y2],[x2,y2],[x2,y3]]
      for(const node of scene.nodes.filter(n=>n.id!==source.id&&n.id!==target.id)) {
        for(let i=1;i<points.length;i++) {
          const [a,b]=[points[i-1],points[i]]
          const crosses=a[0]===b[0]
            ? a[0]>node.x&&a[0]<node.x+196&&Math.max(a[1],b[1])>node.y&&Math.min(a[1],b[1])<node.y+92
            : a[1]>node.y&&a[1]<node.y+92&&Math.max(a[0],b[0])>node.x&&Math.min(a[0],b[0])<node.x+196
          assert.equal(crosses,false,source.id+' -> '+target.id+' crosses '+node.id)
        }
      }
    }
  }
})

for (const size of [100,500,1000]) test(`fit and zoom retain all ${size} nodes on desktop and mobile`, () => {
  const nodes=Array.from({length:size},(_,i)=>({id:`agent:${i}`,plane:'DATA'}))
  for (const columns of [4,6]) for (const [width,height] of [[1200,780],[360,500]]) {
    const scene=graph.graphScene(nodes,[], '',false,columns),camera=graph.fitCamera(width,height,scene.width,scene.height)
    assert.ok(camera.scale>0)
    assert.ok(camera.x>=0 && camera.y>=0)
    assert.ok(camera.x+scene.width*camera.scale<=width,`horizontal clipping at ${size}`)
    assert.ok(camera.y+scene.height*camera.scale<=height,`vertical clipping at ${size}`)
    const zoomed=graph.zoomCamera(camera,1.25,width/2,height/2)
    assert.ok(zoomed.scale/camera.scale<1.251,'zoom must not jump at low fit scales')
  }
})

test('1000-target fan-out draws trunks once and retains every directed branch',()=>{
  const all=[{id:'control',plane:'CONTROL'},...Array.from({length:1000},(_,i)=>({id:'agent:'+i,plane:'DATA'}))]
  const scene=graph.graphScene(all,[], '',false,6),source=scene.nodes[0],targets=scene.nodes.slice(1)
  const bus=graph.controlBusPaths(source,targets,0)
  assert.equal(bus.branches.size,1000)
  assert.equal((bus.trunk.match(/ H /g)||[]).length,Math.ceil(1000/6)+1)
  for(const target of targets)assert.equal(bus.branches.get(target.id),`M ${target.x+98} ${target.y-48} V ${target.y}`)
  // Cumulative painted length is linear in graph height, instead of redrawing
  // every source-to-target trunk; this bounds expensive SVG dash tessellation.
  let oldLength=0
  for(const target of targets)oldLength+=Math.abs(target.y-source.y)
  const bound=scene.height+Math.ceil(1000/6)*scene.width+1000*48+scene.width
  assert.ok(bound<oldLength/20)
})
