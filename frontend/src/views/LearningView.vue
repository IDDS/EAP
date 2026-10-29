<template>
  <section class="page-grid feedback-page">
    <ConsolePageHeader :title="location.experimentId ? '对照实验记录' : location.optimizationId ? '优化任务记录' : '反馈与改进'">
      <template #actions><div v-if="!location.error&&!location.experimentId&&!location.optimizationId" class="feedback-actions"><el-button :loading="loading" :disabled="busy" @click="load">刷新记录</el-button><el-button v-if="permissions.can('manageFeedback')" type="primary" :icon="Plus" :disabled="busy||loading" @click="openForm">新增反馈</el-button></div></template>
    </ConsolePageHeader>
    <el-alert v-if="location.error" :title="location.error" type="error" :closable="false" />
    <p v-if="location.error || location.agentId || location.experimentId || location.optimizationId" class="feedback-note">链接用于定位记录，不表示候选已经采用。<router-link to="/learning">返回全部记录</router-link></p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="!permissions.can('viewContent')" title="当前权限可查看评分和关联信息；问题摘要及建议需正文查看权限。" type="info" :closable="false" />
    <el-card v-if="!location.error&&!location.experimentId&&!location.optimizationId" shadow="never" class="panel">
      <div class="feedback-filter"><el-select v-model="filterAgent" clearable filterable :disabled="busy || submitUnknown" placeholder="全部智能体" aria-label="筛选智能体" @change="changeFilter"><el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" /></el-select><span>最近 100 条 · 采纳仅记录结论，配置变更须另行验证和发布</span></div>
      <el-table :data="feedbacks" v-loading="loading" row-key="id" max-height="620">
        <el-table-column label="智能体 / 证据" min-width="210"><template #default="{row}"><strong>{{ agentName(row.agentId) }}</strong><div class="feedback-evidence"><span v-if="row.taskId">任务 #{{ row.taskId }}</span><span v-if="row.traceId" :title="row.traceId">Trace {{ row.traceId }}</span><span v-if="!row.taskId&&!row.traceId">未关联执行证据</span></div></template></el-table-column>
        <el-table-column label="反馈" width="120"><template #default="{row}">{{ types[row.feedbackType]||row.feedbackType }}</template></el-table-column>
        <el-table-column label="评分" width="150"><template #default="{row}"><el-rate :model-value="row.rating" disabled /></template></el-table-column>
        <el-table-column label="问题与建议" min-width="290"><template #default="{row}"><template v-if="row.contentVisible"><div class="feedback-text">{{ row.summary }}</div><small v-if="row.improvementAction" class="feedback-text">建议：{{ row.improvementAction }}</small></template><span v-else>正文受权限保护</span></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{row}"><el-tag :type="row.status==='ADOPTED'?'success':row.status==='REJECTED'?'info':'primary'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="记录与评审人" min-width="170"><template #default="{row}"><small class="feedback-evidence" :title="row.requestedBy">记录：{{ row.requestedBy||'历史记录未留存' }}</small><small v-if="row.reviewedBy" class="feedback-evidence" :title="row.reviewedBy">评审：{{ row.reviewedBy }}</small><small>{{ formatTime(row.updatedAt) }}</small></template></el-table-column>
        <el-table-column v-if="canReview" label="评审" width="180" fixed="right"><template #default="{row}"><el-button v-if="row.status==='OPEN'" text :disabled="busy" @click="setStatus(row,'REVIEWING')">开始评审</el-button><el-button v-if="row.status==='REVIEWING'" text type="success" :disabled="busy" @click="setStatus(row,'ADOPTED')">采纳</el-button><el-button v-if="['OPEN','REVIEWING'].includes(row.status)" text :disabled="busy" @click="setStatus(row,'REJECTED')">不采纳</el-button></template></el-table-column>
      </el-table>
      <el-empty v-if="!feedbacks.length&&!loading&&!error&&!location.error" description="当前范围内暂无反馈" />
    </el-card>
    <AgentExperimentPanel v-if="!location.error && !location.optimizationId" />
    <PromptOptimizationPanel v-if="!location.error && !location.experimentId" />
    <el-dialog v-model="showForm" title="新增质量反馈" width="min(660px, calc(100vw - 28px))" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" :before-close="closeForm">
      <el-alert v-if="submitUnknown" title="提交结果尚未确认。可用原内容核对提交，平台会返回同一记录；请勿改写后重复提交。" type="warning" :closable="false" />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="智能体（必选）"><el-select v-model="draft.agentId" filterable :disabled="busy||submitUnknown" placeholder="选择反馈对应的智能体" aria-label="反馈智能体" @change="loadEvidence"><el-option v-for="a in agents" :key="a.id" :label="`${a.name} · #${a.id}`" :value="a.id" /></el-select></el-form-item>
        <div class="feedback-form-grid"><el-form-item label="关联任务（可选）"><el-select v-model="draft.taskId" clearable :disabled="busy||submitUnknown||evidenceLoading||!draft.agentId" placeholder="该智能体的任务" aria-label="关联任务"><el-option v-for="t in tasks" :key="t.id" :label="`#${t.id} · ${t.taskType} · ${t.status}`" :value="t.id" /></el-select></el-form-item><el-form-item label="关联 Trace（可选）"><el-select v-model="draft.traceId" clearable filterable :disabled="busy||submitUnknown||evidenceLoading||!draft.agentId" placeholder="该智能体最近的 Trace" aria-label="关联 Trace"><el-option v-for="t in traces" :key="t" :label="t" :value="t" /></el-select></el-form-item></div>
        <div class="feedback-form-grid"><el-form-item label="反馈类型"><el-select v-model="draft.feedbackType" :disabled="busy||submitUnknown" aria-label="反馈类型"><el-option v-for="(name,key) in types" :key="key" :label="name" :value="key" /></el-select></el-form-item><el-form-item label="总体评分（1–5 分）"><el-rate v-model="draft.rating" :disabled="busy||submitUnknown" aria-label="总体评分" /></el-form-item></div>
        <el-form-item label="问题摘要（必填）"><el-input v-model="draft.summary" type="textarea" :rows="3" :maxlength="1024" show-word-limit :disabled="busy||submitUnknown" placeholder="描述实际观察、预期表现及差异" aria-label="问题摘要" /></el-form-item>
        <el-form-item label="改进建议（可选）"><el-input v-model="draft.improvementAction" type="textarea" :rows="2" :maxlength="1024" show-word-limit :disabled="busy||submitUnknown" placeholder="例如提示词、知识、工具或执行策略的修改建议" aria-label="改进建议" /></el-form-item>
        <p class="feedback-note">任务和 Trace 各自校验是否属于该智能体；同时选择不代表两者存在已验证的关联。反馈不自动改变运行配置，请勿填写密钥或访问令牌。</p>
        <div class="feedback-actions"><el-button :disabled="busy" @click="showForm=false">关闭</el-button><el-button type="primary" native-type="submit" :loading="busy">{{ submitUnknown?'核对原提交':'记录反馈' }}</el-button></div>
      </el-form>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {useRoute,useRouter,onBeforeRouteUpdate,onBeforeRouteLeave} from 'vue-router'
import {learningLocation} from '@/navigation/learningLinks'
import {ElMessage} from 'element-plus'
import {Plus} from '@element-plus/icons-vue'
import {EapApi} from '@/api/eap'
import AgentExperimentPanel from '@/components/AgentExperimentPanel.vue'
import PromptOptimizationPanel from '@/components/PromptOptimizationPanel.vue'
import {usePermissions} from '@/stores/permissions'
import type {Agent,LearningFeedback,LearningFeedbackStatus} from '@/types/domain'
const permissions=usePermissions(),feedbacks=ref<LearningFeedback[]>([]),agents=ref<Agent[]>([]),tasks=ref<{id:number;taskType:string;status:string}[]>([]),traces=ref<string[]>([])
const route=useRoute(),router=useRouter(),location=computed(()=>learningLocation(route.query))
const loading=ref(false),busy=ref(false),evidenceLoading=ref(false),showForm=ref(false),submitUnknown=ref(false),error=ref(''),filterAgent=ref<number|undefined>(location.value.agentId)
const types:Record<string,string>={QUALITY:'回答质量',LATENCY:'执行耗时',COST:'调用成本',TOOL:'工具执行',KNOWLEDGE:'知识缺口',SAFETY:'安全与权限'}
const statuses:Record<LearningFeedbackStatus,string>={OPEN:'待评审',REVIEWING:'评审中',ADOPTED:'已采纳',REJECTED:'不采纳'}
const canReview=computed(()=>permissions.can('manageFeedback')&&permissions.can('viewContent'))
type Draft={agentId?:number;taskId?:number;traceId?:string;feedbackType:string;rating:number;summary:string;improvementAction:string;requestKey:string}
function emptyDraft():Draft{return{feedbackType:'QUALITY',rating:0,summary:'',improvementAction:'',requestKey:crypto.randomUUID()}}
const draft=ref<Draft>(emptyDraft())
let generation=0,evidenceGeneration=0,alive=true
function statusLabel(status:LearningFeedbackStatus){return statuses[status]}
function agentName(id:number){return agents.value.find(a=>a.id===id)?.name||`智能体 #${id}`}
function formatTime(time:string){return new Date(time).toLocaleString('zh-CN',{hour12:false})}
function current(run:number,user:unknown){return alive&&run===generation&&user===permissions.state.user&&permissions.can('viewConsole')}
async function load(){
  if(busy.value||!permissions.can('viewConsole'))return
  if(location.value.error||location.value.experimentId||location.value.optimizationId){feedbacks.value=[];agents.value=[];return}
  const run=++generation,user=permissions.state.user;loading.value=true;error.value=''
  try{const [rows,list]=await Promise.all([EapApi.listLearningFeedbacks(filterAgent.value),EapApi.listAgents()]);if(current(run,user)){feedbacks.value=rows;agents.value=list}}
  catch{if(current(run,user))error.value='反馈记录读取失败，请刷新重试。'}finally{if(current(run,user))loading.value=false}
}
async function changeFilter(){
  if(busy.value||submitUnknown.value)return
  const target=filterAgent.value
  await router.push({path:'/learning',query:target?{agentId:String(target)}:{}})
  filterAgent.value=location.value.agentId
}
function navigateContext(){
  generation++;feedbacks.value=[];agents.value=[];loading.value=false;error.value=''
  filterAgent.value=location.value.agentId
  void load()
}
function canLeaveRecord(){if(busy.value||submitUnknown.value){ElMessage.warning('请先核对当前反馈提交，再切换记录。');return false}}
onBeforeRouteUpdate(canLeaveRecord)
onBeforeRouteLeave(canLeaveRecord)
watch(()=>[route.query.agentId,route.query.experimentId,route.query.optimizationId],navigateContext,{flush:'sync'})
function openForm(){if(!permissions.can('manageFeedback')||busy.value)return;if(!submitUnknown.value){draft.value=emptyDraft();draft.value.agentId=filterAgent.value;tasks.value=[];traces.value=[]}showForm.value=true;if(draft.value.agentId&&!submitUnknown.value)void loadEvidence()}
function closeForm(done:()=>void){if(!busy.value)done()}
async function loadEvidence(){
  const run=++evidenceGeneration,user=permissions.state.user,id=draft.value.agentId;draft.value.taskId=undefined;draft.value.traceId=undefined;tasks.value=[];traces.value=[]
  if(!id||!permissions.can('manageFeedback'))return
  evidenceLoading.value=true
  try{const evidence=await EapApi.feedbackEvidence(id);if(alive&&run===evidenceGeneration&&user===permissions.state.user&&id===draft.value.agentId){tasks.value=evidence.tasks;traces.value=evidence.traces}}
  catch{if(alive&&run===evidenceGeneration)ElMessage.error('关联证据加载失败，可稍后重试或不关联证据。')}finally{if(alive&&run===evidenceGeneration)evidenceLoading.value=false}
}
async function submit(){
  if(busy.value||!permissions.can('manageFeedback'))return
  const d=draft.value;if(!d.agentId||!d.summary.trim()||d.rating<1||d.rating>5){ElMessage.warning('请选择智能体、评分并填写问题摘要');return}
  const run=++generation,user=permissions.state.user;busy.value=true;error.value=''
  try{await EapApi.createLearningFeedback({...d,agentId:d.agentId,summary:d.summary.trim(),improvementAction:d.improvementAction.trim()||undefined,traceId:d.traceId||undefined});if(current(run,user)){submitUnknown.value=false;draft.value=emptyDraft();showForm.value=false;ElMessage.success('反馈已记录')}}
  catch(e:unknown){if(current(run,user)){const status=(e as {response?:{status:number}}).response?.status;submitUnknown.value=!status||status>=500;error.value=submitUnknown.value?'提交结果未确认，请用原内容核对提交。':'反馈未提交，请核对填写内容及权限。'}}
  finally{if(current(run,user)){busy.value=false;if(!submitUnknown.value)void load()}}
}
async function setStatus(row:LearningFeedback,status:LearningFeedbackStatus){
  if(busy.value||!canReview.value)return
  const run=++generation,user=permissions.state.user;busy.value=true;error.value=''
  try{await EapApi.updateLearningFeedbackStatus(row.id,status,row.status);if(current(run,user))ElMessage.success(status==='ADOPTED'?'已记录采纳结论；运行配置尚未改变':'评审状态已更新')}
  catch{if(current(run,user))error.value='评审结果未确认或状态已改变，已刷新记录，请核对后操作。'}finally{if(current(run,user)){busy.value=false;void load()}}
}
watch([()=>permissions.state.user,()=>permissions.can('viewConsole'),()=>permissions.can('viewContent'),()=>permissions.can('manageFeedback')],()=>{generation++;evidenceGeneration++;feedbacks.value=[];agents.value=[];tasks.value=[];traces.value=[];draft.value=emptyDraft();showForm.value=false;submitUnknown.value=false;busy.value=false;loading.value=false;evidenceLoading.value=false;error.value='';void load()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{alive=false;generation++;evidenceGeneration++})
</script>
<style scoped>
.feedback-actions{display:flex;gap:10px;justify-content:flex-end;flex-wrap:wrap}.feedback-filter{display:flex;gap:16px;align-items:center;margin-bottom:18px;flex-wrap:wrap}.feedback-filter span,.feedback-evidence,.feedback-note,small{font-size:12px;color:#586d85;line-height:1.7}.feedback-evidence{display:grid;max-width:210px;overflow:hidden;text-overflow:ellipsis}.feedback-evidence span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.feedback-text{white-space:pre-wrap;overflow-wrap:anywhere;max-height:100px;overflow:auto}.feedback-text+small{display:block;margin-top:5px}.feedback-form-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:16px}.feedback-page :deep(.el-select){width:100%}.feedback-filter :deep(.el-select){width:240px}.feedback-note{margin:0 0 20px}@media(max-width:600px){.feedback-form-grid{grid-template-columns:minmax(0,1fr);gap:0}.toolbar{align-items:stretch}.feedback-filter :deep(.el-select){width:100%}.feedback-actions{justify-content:flex-start}}
</style>
