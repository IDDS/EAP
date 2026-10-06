<template>
  <section class="tool-guard-panel">
    <RuntimeToolPolicyPanel :instance-key="props.instanceKey" />
    <ScopedToolPanel :instance-key="props.instanceKey" />
    <header><div><h3>工具执行检查</h3><p>最近 30 条检查与回执。检查允许后还需领取一次性许可，执行结果由接入程序回传。</p></div><el-button :loading="loading" @click="load">刷新工具记录</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p class="tool-guard-note">原程序须使用工具接入客户端；绕过客户端的调用不在此范围。待复核不会执行，本轮不支持批准后续跑。已领取但没有回执时，结果未知，请勿自动重试。</p>
    <el-empty v-if="!loading&&!error&&!rows.length" description="尚无工具检查记录" :image-size="48" />
    <div v-for="row in rows" :key="row.callId" class="tool-guard-call">
      <div class="tool-call-title"><strong>{{ row.toolName }}</strong><el-tag :type="stateType(row.state)">{{ row.evidenceSource==='TOOL_STORE_VERIFIED'?'工具存储确认提交':stateLabel(row.state) }}</el-tag></div>
      <dl><dt>行为检查</dt><dd>{{ safetyLabel(row.safetyDecision) }}</dd><dt>数据策略</dt><dd>{{ dataLabel(row.dataAction) }}</dd><dt>原因</dt><dd>{{ reasonLabel(row.reasonCode) }}</dd><dt>检查时间</dt><dd>{{ new Date(row.createdAt).toLocaleString('zh-CN') }}</dd></dl>
      <small v-if="row.state==='APPROVED'">待领取许可，到期：{{ new Date(row.expiresAt).toLocaleTimeString('zh-CN') }}。尚不能据此判断工具已执行。</small>
      <small v-if="row.state==='CLAIMED'">许可已领取，尚无结果回执。不要重放此操作。</small>
      <small v-if="row.completedAt">{{ row.evidenceSource==='TOOL_STORE_VERIFIED'?'工具存储核验':'执行方回执' }} · {{ row.durationMs ?? 0 }} ms · {{ new Date(row.completedAt).toLocaleString('zh-CN') }}</small>
      <details><summary>关联标识</summary><dl><dt>授权版本</dt><dd>{{ row.policyRevision ?? '无' }}</dd><dt>调用标识</dt><dd>{{ row.callId }}</dd><dt>调用链</dt><dd>{{ row.traceId }}</dd><dt>治理决策</dt><dd>{{ row.decisionKey }}</dd></dl></details>
    </div>
  </section>
</template>
<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue'
import RuntimeToolPolicyPanel from '@/components/RuntimeToolPolicyPanel.vue'
import ScopedToolPanel from '@/components/ScopedToolPanel.vue'
import {http} from '@/api/http'
import {usePermissions} from '@/stores/permissions'
const props=defineProps<{instanceKey:string}>(),permissions=usePermissions()
type ToolCall={callId:string;toolName:string;traceId:string;state:string;safetyDecision:string;dataAction:string;reasonCode:string;decisionKey:string;createdAt:string;expiresAt:string;completedAt?:string;durationMs?:number;policyRevision?:number;evidenceSource?:string;effectId?:string;rootGrantId?:string}
const rows=ref<ToolCall[]>([]),loading=ref(false),error=ref('')
let generation=0,alive=true
function clear(){generation++;rows.value=[];error.value='';loading.value=false}
async function load(){
 const run=++generation,user=permissions.state.user;rows.value=[];error.value=''
 if(!permissions.can('viewConsole'))return
 loading.value=true
 try{const response=await http.get('/registry/runtime-tools',{params:{instanceKey:props.instanceKey},timeout:30000});if(alive&&run===generation&&user===permissions.state.user&&permissions.can('viewConsole'))rows.value=response.data.data}
 catch{if(alive&&run===generation)error.value='工具记录读取失败，请刷新重试。'}
 finally{if(alive&&run===generation)loading.value=false}
}
const stateLabel=(s:string)=>({APPROVED:'检查通过，待领取',CLAIMED:'已领取，结果待确认',BLOCKED:'已阻断，未获准执行',REVIEW_REQUIRED:'待人工复核，未获准执行',EXPIRED:'许可已过期',SUCCEEDED:'执行方报告成功',FAILED:'执行方报告失败'}[s]||s)
const stateType=(s:string):'success'|'warning'|'danger'|'info'=>s==='SUCCEEDED'?'success':s==='BLOCKED'||s==='FAILED'?'danger':['CLAIMED','REVIEW_REQUIRED'].includes(s)?'warning':'info'
const safetyLabel=(s:string)=>({NOT_EVALUATED:'未进入行为检查',ALLOW:'允许',ASK:'需要复核',DENY:'阻断'}[s]||s)
const dataLabel=(s:string)=>({NOT_EVALUATED:'未进入数据检查',ALLOW:'允许',WARN:'警告',BLOCK:'阻断',APPROVAL_REQUIRED:'需要审批',REDACT:'需要脱敏后重新检查'}[s]||s)
const reasonLabel=(s:string)=>({TOOL_POLICY_MISSING:'未配置工具授权',TOOL_POLICY_DISABLED:'工具授权已停用',TOOL_SCOPE_DENIED:'工具不在授权范围',TOOL_POLICY_CHANGED:'授权版本已变化，旧检查失效',TOOL_BUDGET_EXHAUSTED:'共享领取额度已用尽',POLICY_PASS:'通过当前检查',SENSITIVE_OR_PRIVILEGED_ACCESS:'涉及敏感数据或特权资源',HIGH_RISK_TOOL_CALL:'高风险工具操作',DATA_BLOCK:'数据策略阻断',DATA_REQUEST_APPROVAL:'数据策略要求审批',DATA_REDACT_AND_RECHECK:'须脱敏并重新检查'}[s]||s)
watch([()=>props.instanceKey,()=>permissions.state.user,()=>permissions.can('viewConsole')],()=>{clear();void load()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{alive=false;clear()})
</script>
<style scoped>
.tool-guard-panel{border-top:1px solid #dce4ef;padding-top:24px;display:grid;gap:14px;min-width:0}.tool-guard-panel header,.tool-call-title{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.tool-guard-panel h3{margin:0;font-size:17px}.tool-guard-panel p,.tool-guard-panel small{font-size:13px;line-height:1.7;color:#61748b}.tool-guard-note{margin:0}.tool-guard-call{border:1px solid #dce4ef;border-radius:8px;padding:16px;min-width:0}.tool-call-title{flex-wrap:wrap}.tool-call-title strong{overflow-wrap:anywhere}.tool-guard-call dl{display:grid;grid-template-columns:90px 1fr;gap:8px;font-size:12px;line-height:1.6}.tool-guard-call dt{color:#61748b}.tool-guard-call dd{margin:0;overflow-wrap:anywhere}.tool-guard-call summary{margin-top:12px;font-size:12px;cursor:pointer;color:#2156b6}@media(max-width:600px){.tool-guard-panel header{flex-direction:column}.tool-call-title .el-tag{white-space:normal;height:auto;min-height:24px;line-height:1.6}}
</style>
