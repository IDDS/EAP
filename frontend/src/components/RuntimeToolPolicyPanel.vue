<template>
  <section class="tool-policy" aria-label="工具权限与额度">
    <header><div><h3>工具权限与额度</h3><p>同一智能体的所有接入实例共享授权和累计额度。</p></div><el-button :disabled="busy" :loading="loading" @click="load">刷新授权</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="policy">
      <div class="policy-status"><el-tag :type="policy.enabled?'success':'info'">{{ policy.revision===0?'尚未授权':policy.enabled?'授权已启用':'授权已停用' }}</el-tag><span>授权版本 {{ policy.revision }}</span></div>
      <div class="policy-metrics"><div><strong>{{ policy.remainingClaims }}</strong><span>剩余可领取</span></div><div><strong>{{ policy.claimedCount }}</strong><span>累计已领取</span></div><div><strong>{{ policy.claimLimit }}</strong><span>累计额度</span></div></div>
      <p class="scope-label">允许的工具</p>
      <div class="policy-scopes"><el-tag v-for="tool in policy.allowedTools" :key="tool.kind+':'+tool.name" type="info">{{ tool.name }} · {{ tool.kind }}</el-tag><span v-if="!policy.allowedTools.length">未配置，工具调用将被阻断</span></div>
      <p class="policy-note">领取许可时再次核验授权和额度。失败或结果未知不返还额度，换发凭证与恢复实例不重置计数。停用授权可阻止后续领取，已经领取的许可无法撤回。</p>
      <el-button v-if="permissions.can('governance')&&!editing" class="edit-policy" :disabled="loading||busy" @click="edit">编辑工具授权</el-button>
      <el-form v-if="editing&&permissions.can('governance')" label-position="top" @submit.prevent="save">
        <el-form-item label="启用工具授权"><el-switch v-model="draft.enabled" :disabled="busy" aria-label="启用工具授权" /></el-form-item>
        <el-form-item label="累计领取额度"><el-input-number v-model="draft.claimLimit" :min="policy.claimedCount" :max="1000000000" :precision="0" :disabled="busy" aria-label="累计领取额度" /><small>上限至少为已领取次数 {{ policy.claimedCount }}，只修改上限，不清零历史使用量。</small></el-form-item>
        <div class="scope-editor" v-for="(tool,index) in draft.allowedTools" :key="index">
          <el-input v-model="tool.name" :maxlength="96" :disabled="busy" placeholder="例如 calculate_sum" :aria-label="'工具名称 '+(index+1)" />
          <el-select v-model="tool.kind" :disabled="busy" :aria-label="'工具类型 '+(index+1)"><el-option label="TOOL" value="TOOL" /><el-option label="MCP" value="MCP" /></el-select>
          <el-button :disabled="busy" :aria-label="'移除工具 '+(index+1)" @click="draft.allowedTools.splice(index,1)">移除</el-button>
        </div>
        <el-button :disabled="busy||draft.allowedTools.length>=64" @click="draft.allowedTools.push({name:'',kind:'TOOL'})">添加允许的工具</el-button>
        <p class="policy-note">工具名称区分大小写，精确匹配，不支持通配符。保存会增加授权版本，使尚未领取的旧检查失效。</p>
        <div class="policy-actions"><el-button type="primary" native-type="submit" :loading="busy">保存工具授权</el-button><el-button :disabled="busy" @click="editing=false">取消</el-button></div>
      </el-form>
    </template>
  </section>
</template>
<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue'
import {http} from '@/api/http'
import {usePermissions} from '@/stores/permissions'
const props=defineProps<{instanceKey:string}>(),permissions=usePermissions()
type Tool={name:string;kind:'TOOL'|'MCP'}
type Policy={agentId:number;revision:number;enabled:boolean;allowedTools:Tool[];claimLimit:number;claimedCount:number;remainingClaims:number}
const policy=ref<Policy>(),loading=ref(false),busy=ref(false),editing=ref(false),error=ref('')
const draft=ref<{enabled:boolean;claimLimit:number;allowedTools:Tool[]}>({enabled:false,claimLimit:0,allowedTools:[]})
let generation=0,alive=true
function clear(){generation++;policy.value=undefined;editing.value=false;busy.value=false;loading.value=false;error.value='';draft.value={enabled:false,claimLimit:0,allowedTools:[]}}
async function load(){
 if(busy.value)return
 const run=++generation,user=permissions.state.user;policy.value=undefined;editing.value=false;error.value=''
 if(!permissions.can('viewConsole'))return
 loading.value=true
 try{const r=await http.get('/registry/runtime-tools/policy',{params:{instanceKey:props.instanceKey},timeout:30000});if(alive&&run===generation&&user===permissions.state.user&&permissions.can('viewConsole'))policy.value=r.data.data}
 catch{if(alive&&run===generation)error.value='授权读取失败，请刷新后重试。'}
 finally{if(alive&&run===generation)loading.value=false}
}
function edit(){
 if(!policy.value||busy.value||!permissions.can('governance'))return
 draft.value={enabled:policy.value.enabled,claimLimit:policy.value.claimLimit,allowedTools:policy.value.allowedTools.map(t=>({...t}))};editing.value=true;error.value=''
}
async function save(){
 if(!editing.value||!policy.value||busy.value||!permissions.can('governance'))return
 const value=draft.value
 if(!Number.isSafeInteger(value.claimLimit)||value.claimLimit<policy.value.claimedCount||value.claimLimit>1000000000||value.allowedTools.length>64||
    (value.enabled&&!value.allowedTools.length)||value.allowedTools.some(t=>!/^([A-Za-z][A-Za-z0-9_.:/-]{0,95})$/.test(t.name)||!['TOOL','MCP'].includes(t.kind))){error.value='请填写有效工具名称和额度；启用授权须至少配置一个工具。';return}
 const run=++generation,user=permissions.state.user,key=props.instanceKey;busy.value=true;error.value=''
 const body={instanceKey:key,expectedRevision:policy.value.revision,enabled:value.enabled,claimLimit:value.claimLimit,allowedTools:value.allowedTools.map(t=>({...t}))}
 try{const r=await http.put('/registry/runtime-tools/policy',body,{timeout:30000});if(alive&&run===generation&&user===permissions.state.user&&permissions.can('governance')){policy.value=r.data.data;editing.value=false}}
 catch(e:unknown){if(alive&&run===generation){editing.value=false;policy.value=undefined;error.value=(e as {response?:{status:number}})?.response?.status===409?'授权已被其他人修改，请刷新后重新编辑。':'保存结果未确认，请先刷新授权核对，不要重复提交。'}}
 finally{if(alive&&run===generation)busy.value=false}
}
watch([()=>props.instanceKey,()=>permissions.state.user,()=>permissions.can('viewConsole'),()=>permissions.can('governance')],()=>{clear();void load()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{alive=false;clear()})
</script>
<style scoped>
.tool-policy{border:1px solid #d6e2f0;border-radius:10px;padding:20px;display:grid;gap:14px;min-width:0;background:#f8fbff}.tool-policy header{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.tool-policy h3{margin:0;font-size:17px}.tool-policy p,.tool-policy small{font-size:12px;line-height:1.7;color:#586d85;margin:0}.policy-status,.policy-scopes,.policy-actions{display:flex;gap:10px;flex-wrap:wrap;align-items:center}.policy-status span,.policy-scopes{font-size:12px;color:#586d85}.policy-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.policy-metrics>div{padding:14px;border-radius:8px;background:white;border:1px solid #e0e8f3;display:grid;gap:4px}.policy-metrics strong{font-size:26px;color:#175cb1;overflow-wrap:anywhere}.policy-metrics span{font-size:12px;color:#586d85}.policy-scopes .el-tag{max-width:100%;height:auto;white-space:normal;overflow-wrap:anywhere;line-height:1.6}.scope-editor{display:grid;grid-template-columns:minmax(0,1fr) 100px auto;gap:8px;margin-bottom:10px}.tool-policy :deep(.el-form-item__content){gap:8px}.edit-policy{justify-self:start}.policy-actions{margin-top:14px}@media(max-width:600px){.tool-policy{padding:14px}.tool-policy header{flex-direction:column}.policy-metrics>div{padding:9px}.policy-metrics strong{font-size:22px}.scope-editor{grid-template-columns:minmax(0,1fr) 82px}.scope-editor .el-button{grid-column:1/-1;justify-self:end}}
</style>
