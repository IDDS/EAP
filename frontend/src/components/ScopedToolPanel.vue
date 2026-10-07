<template>
  <section class="scoped-tools" aria-label="作用域授权与效果核验">
    <header><div><h3>作用域授权与效果核验</h3><p>限定对象、接收实例、参数和期限；子委托共享根预算。</p></div><el-button :disabled="busy" :loading="loading" @click="load">刷新授权与效果</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p class="scope-note">当前支持受控计数工具。工具服务原子复核授权并保存效果；普通工具的执行方回执仍单独展示。未知操作保留原标识，须查询核验。子额度是额外上限，根预算不会复制；有效期内也须在提交时复核整条授权链。</p>
    <details class="contract-catalog"><summary>工具能力表 · {{ catalog?.tools.length??0 }} 项</summary>
      <p>能力来自已实现的工具合同。策略中允许只代表工具名称获准；每次提交仍复核实例、授权链和预算。</p>
      <el-alert v-if="catalogError" :title="catalogError" type="warning" :closable="false" />
      <article v-for="tool in catalog?.tools" :key="tool.kind+tool.toolName" class="scope-card"><div class="scope-title"><strong>{{ tool.toolName }} · {{ tool.kind }}</strong><el-tag :type="tool.authority==='TOOL_STORE'?'success':'warning'">{{ tool.authority==='TOOL_STORE'?'工具存储合同':'能力待核实' }}</el-tag></div>
        <p>合同 {{ tool.contractVersion||'未登记' }} · {{ tool.allowedByPolicy?'策略中允许':'策略未启用此工具' }}</p>
        <dl><template v-for="field in contractFields" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ capability(tool[field.key]) }}</dd></template></dl>
      </article>
    </details>
    <details class="original-inspection"><summary>核验原操作</summary><p>按原根授权与操作标识读取工具存储。查询不执行、不续授权、不领取或退还预算；查不到记录不能推定未提交。</p>
      <el-form label-position="top" @submit.prevent="inspect"><el-form-item label="原根授权标识"><el-input v-model="lookup.rootId" :disabled="checking" aria-label="原根授权标识" /></el-form-item><el-form-item label="原操作标识"><el-input v-model="lookup.operationId" :disabled="checking" aria-label="原操作标识" /></el-form-item><el-button native-type="submit" :loading="checking" :disabled="busy||loading||!permissions.can('viewConsole')">核验原操作结果</el-button></el-form>
      <el-alert v-if="inspectionError" :title="inspectionError" type="warning" :closable="false" />
      <article v-if="inspection" class="scope-card inspection-result"><strong>{{ operationState(inspection.operation.state) }}</strong><p>{{ evidence(inspection.operation.effectSource) }} · 核验时间 {{ new Date(inspection.observedAt).toLocaleString('zh-CN') }}</p><dl><dt>原操作</dt><dd>{{ inspection.operation.operationId }}</dd><dt>原根预算</dt><dd>{{ inspection.budgetKey }}</dd><dt>原效果标识</dt><dd>{{ inspection.operation.effectId||'无已确认效果' }}</dd><dt>提交后计数</dt><dd>{{ inspection.operation.counterValue??'未知或未提交' }}</dd><dt>工具合同</dt><dd>{{ inspection.contractVersion }}</dd></dl><p>{{ reason(inspection.operation.reasonCode) }}</p></article>
    </details>
    <el-button v-if="permissions.can('governance')&&!editing&&!frozen" :disabled="busy||loading" @click="edit">创建受限根授权</el-button>
    <el-alert v-if="frozen" title="创建结果尚未确认。请刷新核对原授权，不要更换标识重复创建。" type="warning" :closable="false" />
    <el-form v-if="editing&&permissions.can('governance')" label-position="top" @submit.prevent="create">
      <el-form-item label="测试对象标识（每行一个，以 poc_ 开头）"><el-input v-model="draft.resources" type="textarea" :rows="2" :disabled="busy" aria-label="测试对象标识" /></el-form-item>
      <el-form-item label="可接收委托的实例（每行一个）"><el-input v-model="draft.recipients" type="textarea" :rows="2" :disabled="busy" aria-label="可接收委托的实例" /><small>必须包含当前实例。委托时还须满足接收方自己的工具策略。</small></el-form-item>
      <div class="scope-form-grid"><el-form-item label="每次最大增量"><el-input-number v-model="draft.maxDelta" :min="1" :max="100" :precision="0" :disabled="busy" /></el-form-item><el-form-item label="根授权累计次数"><el-input-number v-model="draft.claimLimit" :min="1" :max="32" :precision="0" :disabled="busy" /></el-form-item><el-form-item label="有效期（秒）"><el-input-number v-model="draft.ttl" :min="60" :max="3600" :precision="0" :disabled="busy" /></el-form-item></div>
      <div class="scope-actions"><el-button type="primary" native-type="submit" :loading="busy">保存根授权</el-button><el-button :disabled="busy" @click="editing=false">取消</el-button></div>
    </el-form>
    <template v-if="dashboard">
      <div class="scope-summary"><div><strong>{{ dashboard.grants.length }}</strong><span>最近授权（最多 30）</span></div><div><strong>{{ dashboard.operations.filter(o=>o.state==='COMMITTED').length }}</strong><span>已核验提交（最近记录）</span></div><div><strong>{{ dashboard.operations.filter(o=>o.state==='UNKNOWN').length }}</strong><span>待核验操作（最近记录）</span></div></div>
      <el-empty v-if="!dashboard.grants.length" description="尚无作用域授权" :image-size="48" />
      <div class="scope-list"><article v-for="g in dashboard.grants" :key="g.grantId" class="scope-card"><div class="scope-title"><strong>{{ g.parentGrantId?'子委托':'根授权' }} · {{ g.depth }} 级</strong><el-tag :type="grantStatus(g).type">{{ grantStatus(g).label }}</el-tag></div><p>累计 {{ g.usedCount }} / {{ g.claimLimit }} 次 · 单次增量 ≤ {{ g.maxDelta }}</p><dl><dt>持有实例</dt><dd>{{ g.holderKey }}</dd><dt>对象范围</dt><dd>{{ g.resourceSet.join('、') }}</dd><dt>接收实例</dt><dd>{{ g.recipientSet.join('、') }}</dd><dt>有效期至</dt><dd>{{ new Date(g.expiresAt).toLocaleString('zh-CN') }}</dd></dl><details><summary>授权链与版本</summary><dl><dt>根授权</dt><dd>{{ g.rootId }}</dd><dt>父授权</dt><dd>{{ g.parentGrantId||'无' }}</dd><dt>策略版本</dt><dd>{{ g.policyRevision }}</dd><dt>工具合同</dt><dd>{{ g.contractVersion }}</dd></dl></details><el-button v-if="permissions.can('governance')&&g.state==='ACTIVE'" :disabled="busy" @click="revoke(g.grantId)">撤销此授权及后续提交权限</el-button></article></div>
      <h4>工具存储效果</h4><div class="scope-list"><article v-for="c in dashboard.counters" :key="c.resourceId" class="scope-card"><strong>{{ c.resourceId }}</strong><p>当前计数 {{ c.value }} · 实际提交 {{ c.effectCount }} 次</p></article></div>
      <h4>最近操作与独立证据</h4><div class="scope-list"><article v-for="o in dashboard.operations" :key="o.rootId+o.operationId" class="scope-card"><div class="scope-title"><strong>{{ o.resourceId }} · +{{ o.delta }}</strong><el-tag :type="o.state==='COMMITTED'?'success':o.state==='UNKNOWN'?'warning':'danger'">{{ o.state==='COMMITTED'?'工具存储确认提交':o.state==='UNKNOWN'?'结果待核验':'工具存储确认未提交' }}</el-tag></div><p>{{ reason(o.reasonCode) }}</p><dl><dt>证据来源</dt><dd>{{ evidence(o.effectSource) }}</dd><dt>效果标识</dt><dd>{{ o.effectId||'无已确认效果' }}</dd><dt>提交后计数</dt><dd>{{ o.counterValue??'未知或未提交' }}</dd></dl><details><summary>操作与关联标识</summary><dl><dt>根授权</dt><dd>{{ o.rootId }}</dd><dt>操作标识</dt><dd>{{ o.operationId }}</dd><dt>调用链</dt><dd>{{ o.traceId }}</dd></dl></details></article></div>
    </template>
  </section>
</template>
<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue'
import {http} from '@/api/http'
import {usePermissions} from '@/stores/permissions'
const props=defineProps<{instanceKey:string}>(),permissions=usePermissions()
type Grant={grantId:string;rootId:string;parentGrantId?:string;holderKey:string;depth:number;state:string;resourceSet:string[];recipientSet:string[];maxDelta:number;claimLimit:number;usedCount:number;expiresAt:string;policyRevision:number;contractVersion:string}
type Operation={rootId:string;operationId:string;resourceId:string;delta:number;state:string;reasonCode:string;effectId?:string;counterValue?:number;effectSource:string;traceId:string}
type Dashboard={grants:Grant[];operations:Operation[];counters:{resourceId:string;value:number;effectCount:number}[]}
type Submission={grantId:string;holderKey:string;resourceSet:string[];recipientSet:string[];maxDelta:number;claimLimit:number;expiresAt:string}
type Contract={toolName:string;kind:string;contractVersion?:string;authority:string;allowedByPolicy:boolean;[key:string]:string|boolean|undefined}
type Inspection={contractVersion:string;budgetKey:string;observedAt:string;operation:Operation}
const catalog=ref<{tools:Contract[]}>(),catalogError=ref(''),lookup=ref({rootId:'',operationId:''}),inspection=ref<Inspection>(),inspectionError=ref(''),checking=ref(false)
const contractFields=[{key:'approvalSource',label:'批准来源'},{key:'resourceScope',label:'对象范围'},{key:'idempotencyDomain',label:'幂等范围'},{key:'retention',label:'保留期限'},{key:'changedParameters',label:'异参处理'},{key:'query',label:'状态查询'},{key:'cancellation',label:'取消能力'},{key:'commitBarrier',label:'提交屏障'},{key:'compensation',label:'补偿能力'},{key:'recovery',label:'恢复方式'}]
let lookupGeneration=0
function clearInspection(){lookupGeneration++;inspection.value=undefined;inspectionError.value='';checking.value=false}
function operationState(state:string){return state==='COMMITTED'?'工具存储确认提交':state==='REJECTED'?'工具存储确认未提交':'结果待核验'}
function capability(value:string|boolean|undefined){return ({CONSOLE_ROOT_AND_NARROWED_DELEGATION:'控制台根授权与逐级收窄委托',ROOT_OBJECT_AND_RECIPIENT_SET:'原根对象、接收实例与参数范围',ROOT_AND_OPERATION:'原根授权 + 稳定操作标识',PERSISTED_NO_AUTOMATIC_EXPIRY:'随原意图持久保存，无自动过期清理',CONFLICT_409:'同标识异参拒绝（409）',ORIGINAL_DURABLE_INTENT:'按原标识读取持久意图',UNSUPPORTED:'不支持',AUTH_BUDGET_EFFECT_ONE_TRANSACTION:'权限、预算与效果同一事务',QUERY_ONLY:'仅查询原操作，不自动重试',POLICY_CLAIM_ONLY:'工具策略只负责许可领取',UNVERIFIED:'尚未核实'} as Record<string,string>)[String(value)]||'尚未核实'}
async function inspect(){
 if(checking.value||busy.value||loading.value||!permissions.can('viewConsole'))return
 const root=lookup.value.rootId.trim(),operation=lookup.value.operationId.trim();clearInspection()
 if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(root)||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(operation)){inspectionError.value='请输入原根授权和原操作的规范标识。';return}
 const run=++lookupGeneration,user=permissions.state.user,key=props.instanceKey;checking.value=true
 try{const r=await http.get('/registry/runtime-tools/scopes/operations',{params:{instanceKey:key,rootId:root,operationId:operation},timeout:30000});if(alive&&run===lookupGeneration&&key===props.instanceKey&&user===permissions.state.user&&permissions.can('viewConsole'))inspection.value=r.data.data}
 catch{if(alive&&run===lookupGeneration)inspectionError.value='原操作核验失败或记录不可见；结果仍待确认，请保留原标识。'}finally{if(alive&&run===lookupGeneration)checking.value=false}
}
const dashboard=ref<Dashboard>(),loading=ref(false),busy=ref(false),editing=ref(false),error=ref(''),frozen=ref<Submission>()
const draft=ref({resources:'',recipients:'',maxDelta:5,claimLimit:4,ttl:900})
let generation=0,alive=true
function clear(){clearInspection();lookup.value={rootId:'',operationId:''};catalog.value=undefined;catalogError.value='';generation++;dashboard.value=undefined;loading.value=false;busy.value=false;editing.value=false;frozen.value=undefined;error.value='';draft.value={resources:'',recipients:'',maxDelta:5,claimLimit:4,ttl:900}}
async function load(){if(busy.value)return;const run=++generation,user=permissions.state.user;dashboard.value=undefined;catalog.value=undefined;catalogError.value='';error.value='';clearInspection();if(!permissions.can('viewConsole'))return;loading.value=true
 const contractRead=http.get('/registry/runtime-tools/contracts',{params:{instanceKey:props.instanceKey},timeout:30000}).then(r=>{if(alive&&run===generation&&user===permissions.state.user&&permissions.can('viewConsole'))catalog.value=r.data.data}).catch(()=>{if(alive&&run===generation)catalogError.value='工具能力读取失败，不能推定已支持。'})
 try{const r=await http.get('/registry/runtime-tools/scopes',{params:{instanceKey:props.instanceKey},timeout:30000});if(alive&&run===generation&&user===permissions.state.user&&permissions.can('viewConsole')){dashboard.value=r.data.data;if(frozen.value&&dashboard.value?.grants.some(g=>g.grantId===frozen.value?.grantId))frozen.value=undefined}}
 catch{if(alive&&run===generation)error.value='授权与效果读取失败，请刷新核对。'}finally{await contractRead;if(alive&&run===generation)loading.value=false}}
function edit(){if(!permissions.can('governance')||busy.value||frozen.value)return;draft.value={resources:'poc_'+crypto.randomUUID(),recipients:props.instanceKey,maxDelta:5,claimLimit:4,ttl:900};editing.value=true}
function lines(s:string){return s.split(/\r?\n/).map(x=>x.trim()).filter(Boolean)}
async function create(){if(!editing.value||busy.value||frozen.value||!permissions.can('governance'))return;const d=draft.value,resources=lines(d.resources),recipients=lines(d.recipients)
 if(!resources.length||resources.length>8||new Set(resources).size!==resources.length||resources.some(x=>!/^poc_[A-Za-z0-9_-]{1,60}$/.test(x))||!recipients.includes(props.instanceKey)||recipients.length>8||new Set(recipients).size!==recipients.length||recipients.some(x=>x.length>128)||!Number.isInteger(d.maxDelta)||d.maxDelta<1||d.maxDelta>100||!Number.isInteger(d.claimLimit)||d.claimLimit<1||d.claimLimit>32||!Number.isInteger(d.ttl)||d.ttl<60||d.ttl>3600){error.value='请填写有效且不重复的对象、接收实例、参数、额度与期限。';return}
 const body={grantId:crypto.randomUUID(),holderKey:props.instanceKey,resourceSet:resources,recipientSet:recipients,maxDelta:d.maxDelta,claimLimit:d.claimLimit,expiresAt:new Date(Date.now()+d.ttl*1000).toISOString()};frozen.value=body;editing.value=false;busy.value=true;loading.value=false;const run=++generation,user=permissions.state.user;error.value=''
 try{await http.post('/registry/runtime-tools/scopes',body,{timeout:30000});if(alive&&run===generation&&user===permissions.state.user&&permissions.can('governance'))frozen.value=undefined}
 catch(e:unknown){if(alive&&run===generation){const status=(e as {response?:{status:number}})?.response?.status;if(status&&status>=400&&status<500){frozen.value=undefined;error.value='授权未获准，请核对实例接入、工具策略、范围和期限。'}else error.value='创建结果尚未确认，请刷新核对原标识。'}}
 finally{if(alive&&run===generation){const message=error.value;busy.value=false;await load();if(alive&&user===permissions.state.user&&props.instanceKey===body.holderKey&&message&&!error.value)error.value=message}}}
async function revoke(id:string){if(busy.value||!permissions.can('governance'))return;busy.value=true;loading.value=false;const run=++generation,user=permissions.state.user,key=props.instanceKey;error.value=''
 try{await http.post('/registry/runtime-tools/scopes/revoke',{grantId:id},{timeout:30000})}catch{if(alive&&run===generation&&user===permissions.state.user)error.value='撤销结果尚未确认，请刷新核对。'}finally{if(alive&&run===generation){const message=error.value;busy.value=false;await load();if(alive&&user===permissions.state.user&&key===props.instanceKey&&message&&!error.value)error.value=message}}}
function grantStatus(g:Grant):{type:'danger'|'info';label:string}{
 const seen=new Set<string>();let current:Grant|undefined=g
 while(current){
  if(seen.has(current.grantId))return {type:'info',label:'授权链需复核'};seen.add(current.grantId)
  if(current.state==='REVOKED')return {type:'danger',label:current===g?'已撤销':'祖先授权已撤销'}
  if(Date.parse(current.expiresAt)<=Date.now())return {type:'info',label:current===g?'已过期':'祖先授权已过期'}
  if(!current.parentGrantId)return {type:'info',label:'提交时复核权限与额度'}
  const parentId:string=current.parentGrantId;current=dashboard.value?.grants.find(p=>p.grantId===parentId)
 }
 return {type:'info',label:'授权链需复核'}
}
function evidence(s:string){return ({TOOL_STORE_VERIFIED:'工具服务存储核验',TOOL_STORE_NO_COMMIT:'工具服务确认未提交',UNKNOWN:'缺少终态证据'} as Record<string,string>)[s]||s}
function reason(s:string){return ({TOOL_STORE_COMMITTED:'授权复核与效果提交在同一事务内完成',GRANT_REVOKED:'授权链已撤销',GRANT_EXPIRED:'授权已过期',TOOL_POLICY_CHANGED:'策略版本已变化',INSTANCE_CHANGED:'实例身份或凭证已变化',PARAMETER_SCOPE_DENIED:'对象或参数超出委托范围',TOOL_BUDGET_EXHAUSTED:'根、父授权或智能体额度已用尽',SHARED_BUDGET_EXHAUSTED:'共享预算不足',COMMIT_UNCONFIRMED:'持久意图存在，提交结果尚未确认'} as Record<string,string>)[s]||s}
watch([()=>props.instanceKey,()=>permissions.state.user,()=>permissions.can('viewConsole'),()=>permissions.can('governance')],()=>{clear();void load()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{alive=false;clear()})
</script>
<style scoped>
.scoped-tools{display:grid;gap:14px;min-width:0;border:1px solid #b9d5ec;border-radius:12px;padding:20px;background:linear-gradient(135deg,#f4faff,#fff)}.scoped-tools header,.scope-title,.scope-actions{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap}.scoped-tools h3{margin:0;font-size:17px}.scoped-tools p,.scoped-tools small{font-size:12px;line-height:1.7;color:#526c85;margin:0}.scope-summary,.scope-form-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.scope-summary>div{display:grid;gap:4px;border:1px solid #dce8f3;border-radius:8px;padding:12px;background:white}.scope-summary strong{font-size:24px;color:#1765ad}.scope-summary span{font-size:12px;color:#526c85}.scope-list{display:grid;gap:10px;max-height:420px;overflow:auto;min-width:0}.scope-card{border:1px solid #dce8f3;border-radius:8px;padding:14px;background:white;display:grid;gap:10px;min-width:0;overflow-wrap:anywhere}.scope-card dl{display:grid;grid-template-columns:84px minmax(0,1fr);gap:7px;font-size:12px;margin:0}.scope-card dt{color:#61748b}.scope-card dd{margin:0}.contract-catalog,.original-inspection{display:grid;gap:10px;min-width:0;overflow-wrap:anywhere}.contract-catalog>summary,.original-inspection>summary{cursor:pointer;font-size:14px;font-weight:600;color:#2156b6}.contract-catalog .scope-card{margin-top:10px}.original-inspection .el-form{margin-top:12px}.inspection-result{margin-top:12px}.scope-card summary{font-size:12px;color:#2156b6;cursor:pointer}.scope-card .el-tag{white-space:normal;height:auto;line-height:1.6}.scope-actions{justify-content:flex-start}.scoped-tools h4{font-size:14px;margin:4px 0}.scoped-tools :deep(.el-form-item__content){gap:8px;min-width:0}.scoped-tools :deep(.el-input-number){max-width:100%}@media(max-width:600px){.scoped-tools{padding:14px}.scope-form-grid{grid-template-columns:1fr}.scope-summary{gap:6px}.scope-summary>div{padding:8px}.scope-summary strong{font-size:21px}.scope-card{padding:12px}.scope-card .el-button{height:auto;white-space:normal;padding:8px}}
</style>
