<template>
 <section class="unknown-review" aria-label="原未知操作核对">
  <div class="review-heading"><strong>原未知操作核对</strong><el-button :loading="loading" :disabled="busy" @click="load">读取原证据</el-button></div>
  <p>核对原操作、已付用量和实例身份。读取不会唤醒实例、调用模型或修改预算。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <template v-if="review">
   <div class="review-facts"><div><small>原业务结果</small><strong>未知 · 保留基线</strong><small>{{ review.unknownTrials }} 条未知，{{ review.notRunTrials }} 条未运行</small></div><div><small>模型消费 / 已结算</small><strong>{{ review.modelCalls }} / {{ review.settledCalls }}</strong><small>用量覆盖 {{ review.usageKnownCalls }} / {{ review.modelCalls }}</small></div><div><small>已付输入 / 输出 Token</small><strong>{{ review.inputTokens ?? '—' }} / {{ review.outputTokens ?? '—' }}</strong><small>原配对实验 {{ review.experimentIds.map(id=>'#'+id).join('、') || '无' }}</small></div></div>
   <p v-if="review.decision">已登记停止决定 · {{ review.decision.createdAt }}。{{ review.decisionCurrent?'核对证据仍一致。':'证据已变化，继续阻止新任务。' }}原 UNKNOWN 与已付费用保留。</p>
   <ul v-if="review.blockers.length"><li v-for="reason in review.blockers" :key="reason">{{ blockerLabel(reason) }}</li></ul>
   <template v-if="permissions.can('reviewExperimentUnknowns')&&!review.decision&&!review.blockers.length">
    <p>登记后仅允许另行提交独立任务与实例。原任务和配对实验永久停止，不补齐评分、不退还费用，也不自动开始新评测。</p>
    <el-checkbox v-model="accepted" :disabled="busy||uncertain||props.disabled">确认原回执无法补齐；接受保留未知结果、已付费用和全部未运行样本，停止原意图且不重放</el-checkbox>
    <el-button type="warning" :loading="busy" :disabled="!accepted||loading||props.disabled" @click="decide">{{ uncertain?'核对原治理提交':'登记停止决定' }}</el-button>
   </template>
   <small>证据摘要 {{ review.evidenceDigest.slice(0,16) }} · 原实例 {{ review.instanceKey }}</small>
  </template>
 </section>
</template>
<script setup lang="ts">
import {onBeforeUnmount,ref,watch} from 'vue'
import {onBeforeRouteUpdate,onBeforeRouteLeave} from 'vue-router'
import {OptimizationApi} from '@/api/agentOptimizations'
import type {UnknownReview,UnknownDecision} from '@/api/agentOptimizations'
import {usePermissions} from '@/stores/permissions'
const props=defineProps<{runId:number;disabled?:boolean}>(),permissions=usePermissions()
const review=ref<UnknownReview>(),loading=ref(false),busy=ref(false),accepted=ref(false),uncertain=ref(false),error=ref('')
let generation=0,alive=true,frozen:UnknownDecision|undefined
function current(run:number,user:unknown,id:number){return alive&&run===generation&&user===permissions.state.user&&id===props.runId&&permissions.can('viewConsole')}
async function load(){
 if(busy.value||loading.value||!permissions.can('viewConsole'))return
 const run=++generation,user=permissions.state.user,id=props.runId;loading.value=true;review.value=undefined;if(!uncertain.value)accepted.value=false
 try{const value=await OptimizationApi.review(id);if(current(run,user,id)){review.value=value;error.value='';if(value.decision){uncertain.value=false;frozen=undefined}}}catch{if(current(run,user,id)){review.value=undefined;error.value='原操作证据读取失败，请重新核对'}}finally{if(current(run,user,id))loading.value=false}
}
async function decide(){
 if(props.disabled||busy.value||loading.value||!permissions.can('reviewExperimentUnknowns')||!accepted.value||!review.value||review.value.decision||review.value.blockers.length)return
 if(!uncertain.value)frozen={evidenceDigest:review.value.evidenceDigest,requestKey:crypto.randomUUID(),acceptUnknownAndNoReplay:true}
 if(!frozen)return
 const run=++generation,user=permissions.state.user,id=props.runId;busy.value=true;error.value=''
 try{const value=await OptimizationApi.decide(id,frozen);if(current(run,user,id)&&permissions.can('reviewExperimentUnknowns')){review.value=value;uncertain.value=false;accepted.value=false;frozen=undefined}}
 catch(e:unknown){if(current(run,user,id)){const status=(e as {response?:{status:number}}).response?.status;uncertain.value=!status||status>=500;if(!uncertain.value){review.value=undefined;accepted.value=false;frozen=undefined}error.value=uncertain.value?'治理回执未确认，请读取原记录或使用原提交核对':'证据变化或决定被拒绝，请重新读取原证据'}}
 finally{if(current(run,user,id))busy.value=false}
}
function blockerLabel(reason:string){return ({ROOT_NOT_UNKNOWN:'原任务未处于 UNKNOWN',SEARCH_CLAIM_PRESENT:'原搜索身份尚未失效',REGISTERED_PROTOCOL_REQUIRED:'历史任务没有本阶段登记合同',FROZEN_IDENTITY_MISSING:'原冻结身份缺失',CHILD_EXPERIMENT_ACTIVE:'仍有执行中的配对实验',CHILD_IDENTITY_MISMATCH:'子实验与原实例身份不一致',TRIAL_ACTIVE:'仍有未停止的试验',REFLECTION_ACTIVE:'反思调用尚未核对',ORIGINAL_BOUND_IDENTITY_REQUIRED:'原专属实例身份无法核对',ORIGINAL_IDENTITY_NOT_REVOKED:'原实例身份尚未撤销或存在待配送',BUDGET_EVIDENCE_INCOMPLETE:'消费或结算证据不完整',UNKNOWN_OPERATION_COST_MISSING:'原未知操作缺少费用记录',ROOT_BUDGET_NOT_CLEAR:'原根仍有预算预留或违例',ROOT_BUDGET_LINK_MISSING:'消费缺少原根预算关联',BUDGET_ACCOUNT_VIOLATED:'关联预算存在违例',ROOT_COST_MISMATCH:'原根累计费用与结算不一致',BUDGET_SCOPE_MISMATCH:'消费归属不一致',USAGE_OVERFLOW:'用量合计超出可核对范围'} as Record<string,string>)[reason]||'原证据尚不满足核对条件'}
watch([()=>props.runId,()=>permissions.state.user,()=>permissions.can('viewConsole'),()=>permissions.can('reviewExperimentUnknowns')],()=>{generation++;review.value=undefined;loading.value=false;busy.value=false;accepted.value=false;uncertain.value=false;frozen=undefined;error.value='';void load()},{immediate:true,flush:'sync'})
function canLeaveRecord(){if(busy.value||uncertain.value){error.value='请先核对原治理提交，再切换记录。';return false}}
defineExpose({canLeaveRecord})
onBeforeRouteUpdate(canLeaveRecord)
onBeforeRouteLeave(canLeaveRecord)
onBeforeUnmount(()=>{alive=false;generation++})
</script>
<style scoped>
.unknown-review{margin:20px 0;padding:18px;border:1px solid #e4c8a1;border-radius:10px;background:#fffaf3;min-width:0}.review-heading{display:flex;align-items:center;justify-content:space-between;gap:12px}.unknown-review p,.unknown-review li{font-size:13px;line-height:1.7;color:#586d85}.review-facts{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin:18px 0}.review-facts>div{display:grid;gap:8px}.review-facts strong{font-size:19px;color:#725320}.unknown-review small{display:block;color:#586d85;font-size:12px;overflow-wrap:anywhere;margin-top:8px}.unknown-review :deep(.el-checkbox){height:auto;white-space:normal;margin:12px 0 18px;display:flex}.unknown-review :deep(.el-checkbox__label){white-space:normal;line-height:1.7}.unknown-review :deep(.el-button){white-space:normal}@media(max-width:650px){.review-facts{grid-template-columns:minmax(0,1fr)}.review-heading{flex-wrap:wrap}}
</style>
