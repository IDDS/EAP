<template>
  <div class="ax-page">
    <header class="ax-heading"><div><p class="eyebrow">编排与执行 / 异步任务</p><h2>任务与沙箱</h2><p>提交任务，追踪隔离沙箱与真实执行回执。</p></div><div class="button-row"><el-button v-if="permissions.can('runWorkflows')" :icon="Plus" type="primary" @click="createOpen = !createOpen" :aria-expanded="createOpen">{{ createOpen ? '收起新建任务' : '新建任务' }}</el-button><el-button :icon="Refresh" :loading="loading" @click="load">刷新状态</el-button></div></header>
    <div class="ax-flow"><span>提交与检查</span><b>→</b><span>隔离执行</span><b>→</b><span>结果回执</span><b>→</b><span>沙箱休眠</span></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="cap && !cap.enabled" title="异步任务执行尚未启用；部署配置完成后可以提交任务。" type="info" :closable="false" />
    <div class="ax-stats"><div><small>最近任务</small><strong>{{ rows.length }}</strong></div><div><small>业务完成</small><strong>{{ rows.filter(t => t.status === 'SUCCEEDED').length }}</strong></div><div><small>执行中</small><strong>{{ rows.filter(t => ['QUEUED','PROVISIONING','RUNNING'].includes(t.status)).length }}</strong></div><div><small>失败或待核对</small><strong>{{ rows.filter(t => ['FAILED','EXECUTION_UNKNOWN'].includes(t.status)).length }}</strong></div></div>
    <el-card v-if="createOpen && permissions.can('runWorkflows')" class="ax-submit" shadow="never">
      <template #header><strong>新建任务</strong><span class="hint">独立沙箱 · 完成后自动休眠 · 同键提交复用原任务</span></template>
      <el-form label-position="top" @submit.prevent="submit">
        <div class="ax-form-row"><el-form-item label="归属 Agent"><el-select v-model="form.agentId" placeholder="选择已登记的 Agent"><el-option v-for="a in agents" :key="a.id" :label="a.name" :value="a.id" /></el-select></el-form-item><el-form-item label="任务类型"><el-select v-model="form.mode"><el-option label="模型任务 · EAP 网关" value="LLM" /><el-option label="回显验证 · 不调用模型" value="ECHO" /><el-option label="失败回执验证 · 不调用模型" value="FAIL" /></el-select></el-form-item></div>
        <el-form-item label="任务目标"><el-input v-model="form.objective" type="textarea" :rows="3" maxlength="8000" show-word-limit placeholder="描述需要完成的任务" /></el-form-item>
        <div class="submit-actions"><small>请求键 {{ requestKey }} · 请求失败后重试保留同一键</small><el-button type="primary" :disabled="!cap?.enabled || !form.agentId || !form.objective.trim()" :loading="submitting" @click="submit">提交执行</el-button></div>
      </el-form>
    </el-card>
    <el-card shadow="never"><template #header><strong>任务记录</strong><span class="hint">沙箱 Running / Ready 不代表业务成功</span></template>
      <el-table :data="rows" empty-text="暂无异步任务" @row-click="select" class="ax-table">
        <el-table-column label="任务" min-width="245"><template #default="{ row }"><button class="task-link" @click.stop="select(row)">{{ row.taskKey }}</button><small class="agent-line">Agent {{ row.agentId }} · {{ agentName(row.agentId) }}</small></template></el-table-column>
        <el-table-column label="实例身份" min-width="160"><template #default="{ row }"><el-tag :type="identityTag(row.identityStatus)">{{ identityLabel(row) }}</el-tag></template></el-table-column>
        <el-table-column label="业务状态" min-width="155"><template #default="{ row }"><el-tag :type="tagType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="sandboxPhase" label="沙箱状态" min-width="115" /><el-table-column prop="reason" label="执行说明" min-width="250" />
      </el-table>
      <div class="ax-mobile-records">
        <article v-for="row in rows" :key="row.taskKey" class="ax-mobile-record">
          <header><el-tag :type="tagType(row.status)">{{ statusLabel(row.status) }}</el-tag><span>{{ row.sandboxPhase || '沙箱状态未知' }}</span></header>
          <button type="button" class="task-link" @click="select(row)">{{ row.taskKey }}</button>
          <p>Agent {{ row.agentId }} · {{ agentName(row.agentId) }}</p>
          <el-tag :type="identityTag(row.identityStatus)">{{ identityLabel(row) }}</el-tag>
          <p>{{ row.reason || '尚无执行说明' }}</p>
          <el-button @click="select(row)">查看任务详情</el-button>
        </article>
        <p v-if="!rows.length && !loading" class="muted">暂无异步任务</p>
      </div>
    </el-card>
    <el-drawer v-model="detailOpen" append-to-body :z-index="4000" title="任务详情" size="min(680px, 96vw)" @closed="clearContent">
      <template v-if="selected"><dl class="ax-detail"><dt>任务标识</dt><dd>{{ selected.taskKey }}</dd><dt>执行来源</dt><dd>{{ selected.workflowRunKey ? `工作流 ${selected.workflowId} / ${selected.workflowNodeKey}` : '独立任务' }}</dd><dt v-if="selected.workflowRunKey">执行标识</dt><dd v-if="selected.workflowRunKey">{{ selected.workflowRunKey }}</dd><dt>业务状态</dt><dd>{{ statusLabel(selected.status) }}</dd><dt>沙箱状态</dt><dd>{{ selected.sandboxPhase }}</dd><dt>运行实例</dt><dd>{{ selected.actor }}</dd><dt>实例身份</dt><dd>{{ identityLabel(selected) }}</dd><dt v-if="selected.actorUid">创建 UID</dt><dd v-if="selected.actorUid">{{ selected.actorUid }}</dd><dt v-if="selected.identityExpiresAt">接入授权期</dt><dd v-if="selected.identityExpiresAt">{{ new Date(selected.identityExpiresAt).toLocaleString('zh-CN') }}</dd><dt>说明</dt><dd>{{ selected.reason }}</dd><dt>创建时间</dt><dd>{{ selected.createdAt }}</dd></dl>
        <p v-if="selected.identityMode==='PER_INSTANCE_V1'" class="hint">身份状态来自最近配送回执；实际派发前会重新核对原任务 UID、模板与授权期限。</p>
        <el-alert title="结果未知时不会自动重试。请核对原任务，避免重复外部操作。" type="info" :closable="false" />
        <div class="detail-actions"><el-button v-if="permissions.can('viewContent')" :loading="contentLoading" @click="loadContent">查看任务与结果</el-button><el-button v-if="permissions.can('operateInstances') && ['SUCCEEDED','FAILED'].includes(selected.status)" @click="suspend">释放沙箱</el-button></div>
        <RuntimeConnectionPanel v-if="selected.instanceKey && selected.actorUid" :key="selected.instanceKey" :instance-key="selected.instanceKey" />
        <pre v-if="content !== null" class="ax-result">{{ content }}</pre>
      </template>
    </el-drawer>
  </div>
</template>
<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, watch } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { getData, postData } from '@/api/http'
import { usePermissions } from '@/stores/permissions'
import RuntimeConnectionPanel from '@/components/RuntimeConnectionPanel.vue'
const permissions = usePermissions()
const createOpen = ref(false)
const route = useRoute()
type Task = { taskKey: string; agentId: number; status: string; sandboxPhase: string; reason: string; actor: string; createdAt: string; workflowId?: number; workflowRunKey?: string; workflowNodeKey?: string; identityMode?: string; identityStatus?: string; instanceKey?: string; actorUid?: string; identityExpiresAt?: string }
const rows = ref<Task[]>([]), agents = ref<{ id: number; name: string }[]>([])
const cap = ref<{enabled: boolean} | null>(null), loading = ref(false), submitting = ref(false), error = ref('')
const form = reactive({agentId: undefined as number | undefined, mode: 'LLM', objective: ''})
const requestKey = ref(crypto.randomUUID()), selected = ref<Task | null>(null), detailOpen = ref(false)
const content = ref<string | null>(null), contentLoading = ref(false)
let generation = 0, contentGeneration = 0, alive = true, timer: ReturnType<typeof setInterval> | undefined
function clearContent(){contentGeneration++;content.value=null;contentLoading.value=false}
function select(row: Task){clearContent();selected.value=row;detailOpen.value=true}
function agentName(id: number){return agents.value.find(a=>a.id===id)?.name || ''}
function statusLabel(status: string){return ({QUEUED:'等待执行',PROVISIONING:'准备沙箱',RUNNING:'执行中',SUCCEEDED:'业务完成',FAILED:'执行失败',EXECUTION_UNKNOWN:'结果待核对',BLOCKED:'治理阻断',WAITING_APPROVAL:'等待审批'} as Record<string,string>)[status] || status}
function identityLabel(task: Task){return task.identityMode!=='PER_INSTANCE_V1'?'旧接入方式':({PENDING:'创建身份待确认',BOUND:'已绑定创建 UID',DELIVERY_PENDING:'安全配送待确认',READY:'专属身份已确认',EXPIRED:'短凭证已到期',REVOKED:'原授权已撤销'} as Record<string,string>)[task.identityStatus||'']||'身份待核对'}
function identityTag(status?:string): 'success'|'danger'|'warning'|'info'{return status==='READY'?'success':status==='REVOKED'?'danger':status==='EXPIRED'||status==='DELIVERY_PENDING'?'warning':'info'}
function tagType(status: string): 'success'|'danger'|'warning'|'info' {return status==='SUCCEEDED'?'success':status==='FAILED'?'danger':status==='EXECUTION_UNKNOWN'?'warning':'info'}
async function load(){
 const version=++generation;loading.value=true
 try {const [tasks, capabilities, registered]=await Promise.all([getData<Task[]>('/ax/tasks'),getData<{enabled:boolean}>('/ax/capabilities'),getData<{id:number;name:string}[]>('/agents')]);if(!alive||version!==generation)return;rows.value=tasks;cap.value=capabilities;agents.value=registered;error.value='';if(selected.value){const key=selected.value.taskKey;const updated=tasks.find(t=>t.taskKey===key) || await getData<Task>(`/ax/tasks/${encodeURIComponent(key)}`);if(alive&&version===generation&&selected.value?.taskKey===key)selected.value=updated}}
 catch {if(alive&&version===generation)error.value='任务状态加载失败，请刷新重试'}finally{if(alive&&version===generation)loading.value=false}
}
async function submit(){
 if(submitting.value||!permissions.can('runWorkflows')||!cap.value?.enabled||!form.agentId||!form.objective.trim())return
 submitting.value=true
 try{const task=await postData<Task,unknown>('/ax/tasks',{...form,requestKey:requestKey.value});requestKey.value=crypto.randomUUID();await load();if(alive)select(task)}catch{}finally{submitting.value=false}
}
async function loadContent(){
 if(!permissions.can('viewContent')||!selected.value)return
 const key=selected.value.taskKey, version=++contentGeneration;contentLoading.value=true;content.value=null
 try{const result=await getData<unknown>(`/ax/tasks/${key}/content`);if(alive&&version===contentGeneration&&permissions.can('viewContent')&&selected.value?.taskKey===key&&detailOpen.value)content.value=JSON.stringify(result,null,2)}catch{}finally{if(version===contentGeneration)contentLoading.value=false}
}
async function suspend(){if(!permissions.can('operateInstances')||!selected.value)return;try{await postData(`/ax/tasks/${selected.value.taskKey}/suspend`,{});await load()}catch{}}
async function openLinkedTask(){
 clearContent(); selected.value=null; detailOpen.value=false
 const version=contentGeneration
 const key=route.query.taskKey
 if(typeof key!=='string' || !/^ax-[a-f0-9-]{36}$/.test(key))return
 try{const task=await getData<Task>(`/ax/tasks/${encodeURIComponent(key)}`);if(alive&&version===contentGeneration&&route.query.taskKey===key)select(task)}catch{}
}
watch(()=>route.query.taskKey,()=>{void openLinkedTask()},{immediate:true})
watch(()=>permissions.can('viewContent'),clearContent)
watch(()=>permissions.state.user,()=>{generation++;clearContent();selected.value=null;detailOpen.value=false;rows.value=[];void load()},{flush:'sync'})
watch(detailOpen,open=>{if(!open)clearContent()})
onMounted(()=>{void load();timer=setInterval(()=>{if(!loading.value)void load()},5000)})
onUnmounted(()=>{alive=false;generation++;clearContent();if(timer)clearInterval(timer)})
</script>
<style scoped>
.ax-page{display:grid;gap:20px;min-width:0}.ax-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.ax-heading h2{font-size:28px;margin:5px 0 8px}.ax-heading p{color:#64748b;margin:0}.eyebrow{font-size:11px;letter-spacing:2px;font-weight:700}.ax-flow{display:flex;gap:16px;align-items:center;justify-content:space-between;padding:22px 28px;background:#eef4ff;border:1px solid #dce8fb;border-radius:12px;color:#264f86}.ax-flow b{color:#8ba7cc}.ax-stats{display:grid;grid-template-columns:repeat(4,1fr);gap:16px}.ax-stats>div{display:grid;gap:10px;border:1px solid #e2e8f0;background:white;border-radius:12px;padding:18px 22px}.ax-stats small{color:#64748b}.ax-stats strong{font-size:30px;font-weight:600}.hint{margin-left:16px;color:#64748b;font-size:12px}.ax-form-row{display:grid;grid-template-columns:1fr 1fr;gap:20px}.submit-actions{display:flex;align-items:center;justify-content:space-between;gap:15px}.submit-actions small{color:#64748b;overflow-wrap:anywhere}.task-link{background:none;border:0;padding:0;color:#2563eb;cursor:pointer;text-align:left;font-size:12px}.agent-line{display:block;color:#64748b;margin-top:5px}.ax-detail{display:grid;grid-template-columns:85px minmax(0,1fr);gap:16px;font-size:13px}.ax-detail dt{color:#64748b}.ax-detail dd{margin:0;overflow-wrap:anywhere}.detail-actions{display:flex;gap:10px;margin:20px 0;flex-wrap:wrap}.ax-result{white-space:pre-wrap;overflow-wrap:anywhere;background:#f3f6fa;padding:18px;border-radius:8px;font-size:13px;line-height:1.7}.ax-table{width:100%}@media(max-width:760px){.ax-heading{align-items:flex-start}.ax-stats{grid-template-columns:repeat(2,1fr)}.ax-flow{flex-wrap:wrap;padding:18px;justify-content:flex-start}.ax-form-row{grid-template-columns:1fr;gap:0}.submit-actions{align-items:flex-start;flex-direction:column}.hint{display:block;margin:8px 0 0}.ax-heading h2{font-size:24px}}
</style>
