<template>
  <el-card shadow="never" class="panel experiment-panel">
    <template #header><div class="experiment-header"><div><strong>提示词对照实验</strong><p>同一智能体、模型和公开样本，比较基线与候选提示词的真实新输出</p></div><div><el-button :disabled="busy" :loading="loading" @click="load">刷新实验</el-button><el-button v-if="permissions.can('runAgentExperiments')&&!location.experimentId" type="primary" :disabled="busy||loading" @click="editing=!editing">{{ editing?'收起表单':'创建对照' }}</el-button></div></div></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form v-if="editing&&!location.experimentId&&permissions.can('runAgentExperiments')" class="experiment-form" label-position="top" @submit.prevent="submit">
      <el-alert v-if="submitUnknown" title="提交结果未确认；请用原内容核对提交，避免重建实验重复调用。" type="warning" :closable="false" />
      <div class="experiment-grid"><el-form-item label="智能体"><el-select v-model="draft.agentId" filterable :disabled="busy||submitUnknown" placeholder="选择已启用的智能体" aria-label="实验智能体" @change="chooseAgent"><el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" :disabled="a.lifecycleStatus!=='ACTIVE'" /></el-select></el-form-item><el-form-item label="实验实例"><el-select v-model="draft.instanceKey" :disabled="busy||submitUnknown" placeholder="选择支持独立上下文实验的实例" aria-label="实验实例" @change="clearProfile"><el-option v-for="t in targets" :key="t.instanceKey" :label="t.instanceKey" :value="t.instanceKey" /></el-select></el-form-item></div>
      <el-button :disabled="busy||submitUnknown||!draft.instanceKey" :loading="profileLoading" @click="readProfile">读取基线配置</el-button><p class="experiment-note">读取会唤醒所选实例，不调用模型；旧运行程序会明确拒绝此实验协议。</p>
      <template v-if="profile">
        <div class="experiment-profile"><span>发布版本 v{{ profile.deploymentVersion }} · {{ profile.profile.model }}</span><span>独立上下文 · 工具关闭 · temperature=0</span><code :title="profile.imageRef">{{ profile.imageRef }}</code></div>
        <div class="experiment-grid"><el-form-item label="基线提示词（来自当前运行配置）"><el-input :model-value="profile.profile.systemPrompt" type="textarea" :rows="4" readonly aria-label="基线提示词" /></el-form-item><el-form-item label="候选提示词（唯一比较变量）"><el-input v-model="draft.candidatePrompt" type="textarea" :rows="4" :maxlength="8000" :disabled="busy||submitUnknown" aria-label="候选提示词" /></el-form-item></div>
        <div v-for="(sample,index) in draft.samples" :key="index" class="experiment-sample"><strong>样本 {{ index+1 }}</strong><div class="experiment-grid"><el-form-item label="公开输入"><el-input v-model="sample.input" type="textarea" :rows="2" :maxlength="2000" :disabled="busy||submitUnknown" :aria-label="`实验输入 ${index+1}`" /></el-form-item><el-form-item label="期望文本（精确匹配）"><el-input v-model="sample.expected" type="textarea" :rows="2" :maxlength="2000" :disabled="busy||submitUnknown" :aria-label="`期望文本 ${index+1}`" /></el-form-item></div><el-button v-if="draft.samples.length>1" text :disabled="busy||submitUnknown" @click="draft.samples.splice(index,1)">移除样本</el-button></div>
        <div class="experiment-actions"><el-button :disabled="busy||submitUnknown||draft.samples.length>=3" @click="draft.samples.push({input:'',expected:''})">添加样本（最多 3 条）</el-button><label>每次输出上限 <el-input-number v-model="draft.maxTokens" :min="1" :max="Math.min(256,profile.profile.maxTokens)" :precision="0" :disabled="busy||submitUnknown" aria-label="实验输出上限" /></label></div>
        <el-checkbox v-model="draft.publicSamplesConfirmed" :disabled="busy||submitUnknown">我确认这些样本为公开或专门编写的测试数据，不含密钥和业务敏感内容</el-checkbox>
        <p class="experiment-note">将执行 {{ draft.samples.length*2 }} 次模型调用。先冻结配置，再顺序执行；结果未知时停止。这里只验证文本任务，不代表业务成功率或生产性能提升，候选不会自动发布。</p>
        <el-button type="primary" native-type="submit" :loading="busy" :disabled="profileLoading">{{ submitUnknown?'核对原实验提交':'提交并执行对照' }}</el-button>
      </template>
    </el-form>
    <el-table :data="experiments" row-key="id" max-height="320" v-loading="loading" @row-click="select">
      <el-table-column label="实验" width="130"><template #default="{row}">#{{ row.id }} · Agent {{ row.agentId }}</template></el-table-column><el-table-column prop="instanceKey" label="实例" min-width="200" show-overflow-tooltip /><el-table-column label="状态" width="120"><template #default="{row}"><el-tag :type="row.status==='COMPLETED'?'success':row.status==='UNKNOWN'?'warning':'info'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column><el-table-column label="执行进度" width="140"><template #default="{row}">{{ row.trials.filter((t:Trial)=>t.status==='SUCCEEDED').length }} / {{ row.trials.length }} 有结果</template></el-table-column><el-table-column label="创建时间" min-width="180"><template #default="{row}">{{ new Date(row.createdAt).toLocaleString('zh-CN',{hour12:false}) }}</template></el-table-column>
    </el-table><el-empty v-if="!experiments.length&&!loading&&!error" description="当前范围内暂无对照实验" />
    <template v-if="selected">
      <div class="experiment-detail-title"><strong>实验 #{{ selected.id }} · 版本 v{{ selected.deploymentVersion }}</strong><router-link :to="learningRecordPath('experiment',selected.id,selected.agentId)">此实验的独立链接</router-link><span>EXACT_TEXT_V1 · 单轮配对 · 固定空上下文 · 仅改变提示词</span></div>
      <div class="experiment-grid experiment-metrics"><div v-for="name in ['BASELINE','CANDIDATE']" :key="name"><strong>{{ name==='BASELINE'?'基线':'候选' }}</strong><div class="experiment-score"><b>{{ scoreText(name) }}</b><span>精确匹配 / {{ selected.trials.filter(t=>t.variant===name&&t.score!==null).length }} 条已评分</span></div><el-progress :percentage="scorePercent(name)" :show-text="false" /><p>{{ usageText(name) }}</p><p>平均实例执行耗时：{{ latencyText(name) }}</p><code :title="selected.variants.find(v=>v.name===name)?.configDigest">配置 {{ selected.variants.find(v=>v.name===name)?.configDigest.slice(0,16) }}</code></div></div>
      <el-table :data="selected.trials" size="small"><el-table-column label="配置" width="90"><template #default="{row}">{{ row.variant==='BASELINE'?'基线':'候选' }}</template></el-table-column><el-table-column label="样本" width="80"><template #default="{row}">{{ row.sampleIndex+1 }}</template></el-table-column><el-table-column label="执行 / 评分" min-width="180"><template #default="{row}">{{ statusLabel(row.status) }} · {{ row.score===null?'未评分':row.score===1?'精确匹配':'未匹配' }}<small v-if="row.reasonCode">{{ row.reasonCode }}</small></template></el-table-column><el-table-column label="排队 / 执行端到端" width="180"><template #default="{row}">{{ row.queueMs??'未知' }} / {{ row.endToEndMs??'未知' }} ms</template></el-table-column><el-table-column prop="traceId" label="Trace" min-width="220" show-overflow-tooltip /></el-table>
      <template v-if="content&&permissions.can('viewContent')"><div v-for="(sample,index) in content.samples" :key="index" class="experiment-output"><strong>样本 {{ index+1 }}</strong><p>输入：{{ sample.input }}</p><p>期望：{{ sample.expected }}</p><div class="experiment-grid"><div v-for="name in ['BASELINE','CANDIDATE']" :key="name"><small>{{ name==='BASELINE'?'基线新输出':'候选新输出' }}</small><pre>{{ outputText(name,index) }}</pre></div></div></div></template>
      <el-button v-else-if="permissions.can('viewContent')" :disabled="busy" :loading="contentLoading" @click="loadContent">查看样本与实际输出</el-button>
      <p v-else class="experiment-note">当前权限可查看指标和版本；样本、提示词及实际输出需正文查看权限。</p>
      <p class="experiment-note">用量缺失显示未知，不按 0 计算；金额尚未核算。每次最多 6 次调用并顺序执行，同时受共享和智能体预算约束。少量文本样本不能证明普遍优化收益。</p>
    </template>
  </el-card>
</template>
<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {useRoute,onBeforeRouteUpdate,onBeforeRouteLeave} from 'vue-router'
import {learningLocation,learningRecordPath} from '@/navigation/learningLinks'
import {ElMessage} from 'element-plus'
import {EapApi} from '@/api/eap'
import {ExperimentApi} from '@/api/agentExperiments'
import type {Experiment,Trial,Profile,Content,Sample} from '@/api/agentExperiments'
import {usePermissions} from '@/stores/permissions'
import type {Agent} from '@/types/domain'
const permissions=usePermissions(),agents=ref<Agent[]>([]),experiments=ref<Experiment[]>([]),selected=ref<Experiment>(),content=ref<Content>(),profile=ref<Profile>(),targets=ref<{instanceKey:string}[]>([])
const route=useRoute(),location=computed(()=>learningLocation(route.query)),contentLoading=ref(false)
const editing=ref(false),loading=ref(false),profileLoading=ref(false),busy=ref(false),submitUnknown=ref(false),error=ref('')
function empty(){return{agentId:undefined as number|undefined,instanceKey:'',candidatePrompt:'',maxTokens:128,samples:[{input:'',expected:''}] as Sample[],publicSamplesConfirmed:false,requestKey:crypto.randomUUID()}}
const draft=ref(empty());let generation=0,profileGeneration=0,contentGeneration=0,alive=true
function current(run:number,user:unknown){return alive&&run===generation&&user===permissions.state.user&&permissions.can('viewConsole')}
const labels:Record<string,string>={QUEUED:'等待执行',RUNNING:'执行中',COMPLETED:'对照完成',SUCCEEDED:'已收到输出',FAILED:'执行失败',UNKNOWN:'结果未知',CANCELLED:'已停止'}
function statusLabel(s:string){return labels[s]||s}
function clearProfile(){profileGeneration++;profile.value=undefined;profileLoading.value=false}
async function chooseAgent(){clearProfile();targets.value=[];draft.value.instanceKey='';if(!draft.value.agentId)return;const run=profileGeneration,user=permissions.state.user;try{const r=await ExperimentApi.targets(draft.value.agentId);if(alive&&run===profileGeneration&&user===permissions.state.user){targets.value=r.instances;if(!r.enabled)error.value='提示词对照实验尚未启用'}}catch{if(alive&&run===profileGeneration)error.value='实验实例读取失败'}}
async function readProfile(){if(!draft.value.agentId||!draft.value.instanceKey||busy.value||!permissions.can('runAgentExperiments'))return;const run=++profileGeneration,user=permissions.state.user;profile.value=undefined;profileLoading.value=true;error.value='';try{const p=await ExperimentApi.profile(draft.value.agentId,draft.value.instanceKey);if(alive&&run===profileGeneration&&user===permissions.state.user){profile.value=p;draft.value.candidatePrompt='';draft.value.maxTokens=Math.min(128,p.profile.maxTokens)}}catch{if(alive&&run===profileGeneration)error.value='基线读取失败；请确认实例已发布且支持独立上下文实验协议。'}finally{if(alive&&run===profileGeneration)profileLoading.value=false}}
function clearContent(){contentGeneration++;content.value=undefined;contentLoading.value=false}
async function load(){
  if(busy.value||!permissions.can('viewConsole'))return
  const run=++generation,user=permissions.state.user,context=location.value,priorId=selected.value?.id
  experiments.value=[];selected.value=undefined;clearContent();loading.value=false;error.value=''
  if(context.error){error.value=context.error;return}
  if(context.optimizationId)return
  loading.value=true
  try{
    const [rows,list]=await Promise.all([context.experimentId?ExperimentApi.get(context.experimentId).then(row=>[row]):ExperimentApi.list(),context.experimentId?Promise.resolve([]):EapApi.listAgents()])
    if(!current(run,user))return
    if(context.experimentId&&(rows[0]?.id!==context.experimentId||(context.agentId!==undefined&&rows[0]?.agentId!==context.agentId)))throw Error('Record mismatch')
    experiments.value=rows.filter(row=>context.agentId===undefined||row.agentId===context.agentId);agents.value=list
    selected.value=experiments.value.find(row=>row.id===(context.experimentId??priorId))
  }catch{if(current(run,user)){experiments.value=[];selected.value=undefined;clearContent();error.value='实验记录不存在、不可访问或读取失败，请核对链接后重试。'}}
  finally{if(current(run,user))loading.value=false}
}
async function select(row:Experiment){if(busy.value)return;generation++;selected.value=row;clearContent();loading.value=false;error.value='';await loadContent()}
async function loadContent(){
  if(busy.value||!selected.value||!permissions.can('viewConsole')||!permissions.can('viewContent'))return
  clearContent();const run=contentGeneration,user=permissions.state.user,id=selected.value.id;contentLoading.value=true
  try{const result=await ExperimentApi.content(id);if(alive&&run===contentGeneration&&user===permissions.state.user&&permissions.can('viewConsole')&&permissions.can('viewContent')&&selected.value?.id===id)content.value=result}
  catch{if(alive&&run===contentGeneration&&user===permissions.state.user)error.value='实验正文读取失败，请刷新核对。'}
  finally{if(alive&&run===contentGeneration&&user===permissions.state.user)contentLoading.value=false}
}
function navigateContext(){generation++;experiments.value=[];agents.value=[];selected.value=undefined;clearContent();loading.value=false;error.value='';void load()}
function canLeaveRecord(){if(busy.value||submitUnknown.value){ElMessage.warning('请先核对当前实验提交，再切换记录。');return false}}
onBeforeRouteUpdate(canLeaveRecord)
onBeforeRouteLeave(canLeaveRecord)
watch(()=>[route.query.agentId,route.query.experimentId,route.query.optimizationId],navigateContext,{flush:'sync'})
async function submit(){if(busy.value||!permissions.can('runAgentExperiments')||!profile.value||!draft.value.agentId)return;const d=draft.value;if(!d.agentId)return;if(!d.candidatePrompt.trim()||d.candidatePrompt===profile.value.profile.systemPrompt||!d.publicSamplesConfirmed||d.samples.some(s=>!s.input.trim()||!s.expected.trim())){ElMessage.warning('请填写不同的候选提示词、样本及期望文本，并确认样本公开');return}const run=++generation,user=permissions.state.user;busy.value=true;error.value='';try{const r=await ExperimentApi.submit({...d,agentId:d.agentId,profileDigest:profile.value.profile.profileDigest});if(current(run,user)){selected.value=r;submitUnknown.value=false;editing.value=false;draft.value=empty();clearProfile();ElMessage.success('配置已冻结，实验进入执行队列')}}catch(e:unknown){if(current(run,user)){const status=(e as {response?:{status:number}}).response?.status;submitUnknown.value=!status||status>=500;error.value=submitUnknown.value?'提交结果未确认，请用原内容核对原实验。':'实验未提交；请核对基线、样本或已有未完成实验。'}}finally{if(current(run,user)){busy.value=false;if(!submitUnknown.value)void load()}}}
function variantTrials(name:string){return selected.value?.trials.filter(t=>t.variant===name)||[]}
function scoreText(name:string){const ts=variantTrials(name).filter(t=>t.score!==null);return ts.length?`${ts.filter(t=>t.score===1).length}/${ts.length}`:'未评分'}
function scorePercent(name:string){const ts=variantTrials(name).filter(t=>t.score!==null);return ts.length?Math.round(ts.filter(t=>t.score===1).length/ts.length*100):0}
function usageText(name:string){const ts=variantTrials(name),known=ts.filter(t=>t.usageSource==='PROVIDER_REPORTED'&&t.inputTokens!==null&&t.outputTokens!==null);return known.length?`已知 Token：${known.reduce((sum,t)=>sum+(t.inputTokens||0)+(t.outputTokens||0),0)} · 用量覆盖 ${known.length}/${ts.length}`:`Token 未知 · 用量覆盖 0/${ts.length}`}
function latencyText(name:string){const ts=variantTrials(name).filter(t=>t.latencyMs!==null);return ts.length?`${Math.round(ts.reduce((sum,t)=>sum+(t.latencyMs||0),0)/ts.length)} ms（${ts.length} 次）`:'未知'}
function outputText(name:string,index:number){const t=selected.value?.trials.find(t=>t.variant===name&&t.sampleIndex===index);return content.value?.outputs.find(o=>o.trialId===t?.id)?.output??'尚无已确认输出'}
watch([()=>permissions.state.user,()=>permissions.can('viewConsole'),()=>permissions.can('viewContent'),()=>permissions.can('runAgentExperiments')],()=>{generation++;clearProfile();clearContent();agents.value=[];experiments.value=[];selected.value=undefined;targets.value=[];draft.value=empty();editing.value=false;submitUnknown.value=false;busy.value=false;loading.value=false;error.value='';void load()},{immediate:true,flush:'sync'})
onBeforeUnmount(()=>{alive=false;generation++;profileGeneration++;contentGeneration++})
</script>
<style scoped>
.experiment-panel{margin-top:6px}.experiment-header{display:flex;gap:16px;justify-content:space-between;align-items:flex-start}.experiment-header p,.experiment-note{font-size:12px;line-height:1.7;color:#586d85;margin:7px 0 14px}.experiment-header>div:last-child{display:flex;gap:8px;flex-wrap:wrap}.experiment-grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:18px}.experiment-form{padding:20px;border:1px solid #dce7f5;background:#f7faff;border-radius:10px;margin-bottom:24px}.experiment-form :deep(.el-select){width:100%}.experiment-profile{display:grid;gap:6px;font-size:12px;padding:14px;background:#eaf2fd;margin:18px 0;border-radius:8px}.experiment-profile code{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.experiment-sample{padding:16px 0;border-top:1px solid #dce7f5}.experiment-actions{display:flex;gap:16px;align-items:center;flex-wrap:wrap;margin-bottom:16px}.experiment-actions label{font-size:12px}.experiment-form :deep(.el-checkbox){height:auto;white-space:normal}.experiment-form :deep(.el-checkbox__label){white-space:normal}.experiment-detail-title{display:grid;gap:7px;margin:26px 0 18px}.experiment-detail-title span,.experiment-metrics p,small{font-size:12px;color:#586d85;line-height:1.6}.experiment-metrics>div{padding:22px;background:#f0f6ff;border:1px solid #d6e5f8;border-radius:10px}.experiment-score{display:flex;align-items:baseline;gap:12px;margin:16px 0}.experiment-score b{font-size:32px;color:#175cb1}.experiment-score span{font-size:12px;color:#586d85}.experiment-metrics code{font-size:11px;color:#586d85}.experiment-output{padding:18px 0;border-top:1px solid #dce7f5}.experiment-output p{white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}.experiment-output pre{background:#f7f9fd;padding:14px;border-radius:8px;white-space:pre-wrap;overflow-wrap:anywhere;font-family:inherit;max-height:240px;overflow:auto;font-size:13px}small{display:block}@media(max-width:650px){.experiment-grid{grid-template-columns:minmax(0,1fr)}.experiment-header{flex-direction:column}.experiment-form{padding:14px}.experiment-score{flex-wrap:wrap}.experiment-metrics>div{padding:16px}}
</style>
