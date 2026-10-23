<template>
  <section class="connection-panel">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="status">
      <el-alert v-if="status.taskManaged" title="这是原任务的专属运行实例。创建与配送由该任务管理，撤销后原任务授权不能恢复。" type="info" :closable="false" />
      <p class="connection-lead">将当前程序接入任务派发、模型治理和调用链观测。每个实例使用独立凭证，其他实例不能复用。</p>
      <el-alert v-if="!status.available" title="管理员尚未启用实例接入，请先完成服务配置。" type="warning" :closable="false" />
      <el-alert v-if="status.message" :title="status.message" type="warning" :closable="false" />
      <div class="connection-checks">
        <div><span>实例身份</span><el-tag :type="status.identity==='MATCHED'?'success':'danger'">{{ status.identity==='MATCHED'?'与纳管记录一致':status.identity==='CHANGED'?'身份已变化':'暂时无法核对' }}</el-tag></div>
        <div><span>任务协议</span><el-tag :type="status.connection==='READY'?'success':'info'">{{ connectionLabel(status.connection) }}</el-tag></div>
        <div><span>模型治理</span><el-tag :type="status.gatewayAt?'success':'info'">{{ status.gatewayAt?'已观测到受管模型调用':'等待受管模型调用' }}</el-tag></div>
        <div><span>调用链</span><el-tag :type="status.traceAt?'success':'info'">{{ status.traceAt?'已关联模型与调用链':'等待关联观测' }}</el-tag></div>
      </div>
      <p class="muted">原程序可通过工具接入客户端进行执行前检查与结果回传，记录见下方；上述证据仅覆盖接入通道，不代表所有出站请求都已受控。</p>
      <section class="automatic-connection">
        <strong>安全自动接入</strong><p class="muted">支持新版接入协议的程序由平台加密配送短期凭证，并在下一次任务派发前续期。程序只需配置平台公钥及只读实例身份目录；凭证不会返回到此页面。撤销后停止自动接入。</p>
        <el-tag :type="status.automaticDelivery?'success':'info'">{{status.automaticDelivery?'自动配送已启用':'尚未启用自动配送'}}</el-tag>
        <el-button v-if="permissions.can('deployAgents') && !status.taskManaged" type="primary" :loading="busy==='automatic'" :disabled="!!busy||!status.deliveryAvailable||status.identity!=='MATCHED'" @click="automatic">{{status.automaticDelivery?'核对自动接入':'启用安全自动接入'}}</el-button>
        <p v-if="status.lastDeliveryAt" class="muted">最近配送：{{new Date(status.lastDeliveryAt).toLocaleString('zh-CN')}}。休眠期间不为续期唤醒；任务派发前核对身份并更新凭证。</p>
        <p v-if="status.identityExpiresAt" class="muted">实例接入授权到期：{{new Date(status.identityExpiresAt).toLocaleString('zh-CN')}}。凭证续期不延长此授权或工具授权。</p>
        <details v-if="status.deliveryAvailable"><summary>程序接入配置</summary><dl><dt>EAP_IDENTITY_PUBLIC_KEY</dt><dd>{{status.deliveryPublicKey}}</dd><dt>{{status.taskManaged?'EAP_IDENTITY_DIR':'EAP_IDENTITY_DIRECTORY'}}</dt><dd>程序的只读身份目录，包含 atespace、name、uid 三个文件</dd></dl></details>
      </section>
      <ol v-if="!status.automaticDelivery && !status.automaticOnly" class="connection-steps">
        <li><strong>配置实例凭证</strong><p>凭证有效期为 24 小时。通过原程序的安全配置渠道注入下列身份与地址，凭证放入密钥配置，不写入代码或日志。</p>
          <dl><dt>EAP_AGENT_ID</dt><dd>{{ status.agentId }}</dd><dt>EAP_RUNTIME_KEY</dt><dd>{{ status.instanceKey }}</dd><dt>EAP_GATEWAY_URL</dt><dd>{{ status.gatewayUrl || '需管理员配置' }}</dd><dt>模型入口</dt><dd>/api/runtime-access/v1/chat/completions</dd><dt>观测入口</dt><dd>/api/runtime-access/v1/traces</dd></dl>
          <template v-if="permissions.can('deployAgents')"><el-button :loading="busy==='issue'" :disabled="!!busy||!status.available||status.identity!=='MATCHED'" @click="issue">{{ status.connection==='NOT_CONFIGURED'?'生成实例凭证':'换发实例凭证' }}</el-button>
          <div v-if="credential" class="credential-row"><span>EAP_RUNTIME_TOKEN</span><el-input type="password" readonly :model-value="credential.token" autocomplete="off" /><el-button @click="copyCredential">复制凭证</el-button><el-button text @click="credential=undefined">清除显示</el-button></div></template>
          <p v-if="status.expiresAt" class="muted">凭证到期：{{ new Date(status.expiresAt).toLocaleString('zh-CN') }}。关闭接入面板或退出登录后，页面会清除本次生成的凭证。</p>
        </li>
        <li><strong>验证任务连接</strong><p>原程序实现 EAP_HTTP_TASK_V1 及带签名的接入握手后，执行检查。检查可能唤醒实例；原来处于休眠状态时，检查结束会尝试恢复休眠。</p><el-button v-if="permissions.can('deployAgents')" type="primary" :loading="busy==='verify'" :disabled="!!busy||!status.available||!['PENDING','READY'].includes(status.connection)" @click="verify">验证连接</el-button></li>
        <li><strong>启用并验证实际调用</strong><p>连接通过后，在智能体详情启用身份，再通过工作流或团队执行任务。模型调用和调用链到达后，上方显示实际接入证据。</p><router-link :to="'/agents/'+status.agentId">查看智能体与运行证据</router-link></li>
      </ol>
      <footer><el-button :loading="loading" :disabled="!!busy" @click="load">刷新接入状态</el-button><el-button v-if="permissions.can('deployAgents')" type="danger" plain :disabled="!!busy||['NOT_CONFIGURED','REVOKED'].includes(status.connection)" @click="revoke">撤销实例凭证</el-button></footer>
      <RuntimeToolGuardPanel v-if="!status.taskManaged" :instance-key="props.instanceKey" />
    </template>
  </section>
</template>
<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue'
import {ElMessage,ElMessageBox} from 'element-plus'
import {http} from '@/api/http'
import {usePermissions} from '@/stores/permissions'
import RuntimeToolGuardPanel from '@/components/RuntimeToolGuardPanel.vue'
const props=defineProps<{instanceKey:string}>(),permissions=usePermissions()
type Status={instanceKey:string;agentId:number;available:boolean;identity:string;connection:string;expiresAt?:string;verifiedAt?:string;gatewayAt?:string;traceAt?:string;gatewayUrl:string;message?:string;automaticDelivery?:boolean;automaticOnly?:boolean;taskManaged?:boolean;deliveryAvailable?:boolean;deliveryPublicKey?:string;lastDeliveryAt?:string;identityExpiresAt?:string;deliveryOperation?:string}
type Credential={token:string;expiresAt:string}
const status=ref<Status>(),credential=ref<Credential>(),loading=ref(false),busy=ref(''),error=ref('')
let generation=0,alive=true,requestKey='',automaticRequestKey=''
function clear(){++generation;error.value='';credential.value=undefined;status.value=undefined;requestKey='';automaticRequestKey='';busy.value='';loading.value=false}
async function load(){
 const run=++generation,user=permissions.state.user;loading.value=true;error.value=''
 try{const response=await http.get('/registry/runtime-connection',{params:{instanceKey:props.instanceKey},timeout:30000});if(alive&&run===generation&&user===permissions.state.user&&permissions.can('viewConsole'))status.value=response.data.data}
 catch{if(alive&&run===generation){status.value=undefined;error.value='接入状态读取失败，请刷新重试。'}}
 finally{if(alive&&run===generation)loading.value=false}
}
async function issue(){
 if(!permissions.can('deployAgents')||status.value?.taskManaged||busy.value||!status.value||status.value.automaticOnly)return
 if(status.value.connection!=='NOT_CONFIGURED')try{await ElMessageBox.confirm('换发将立即使原凭证失效，原程序需重新配置并验证连接。','换发实例凭证',{type:'warning'})}catch{return}
 if(!alive||!permissions.can('deployAgents')||busy.value)return
 const user=permissions.state.user,run=++generation,key=props.instanceKey;busy.value='issue';credential.value=undefined
 requestKey ||= crypto.randomUUID()
 try{const response=await http.post('/registry/runtime-connection/credential',{instanceKey:key,requestKey},{timeout:30000});if(!alive||run!==generation||user!==permissions.state.user||!permissions.can('deployAgents'))return;credential.value=response.data.data;requestKey='';await load()}
 catch{if(alive&&run===generation)error.value='凭证未确认生成，可重试同一请求；请勿将凭证写入日志。'}
 finally{if(alive&&user===permissions.state.user&&key===props.instanceKey)busy.value=''}
}
async function automatic(){
 if(!permissions.can('deployAgents')||status.value?.taskManaged||busy.value||!status.value)return
 const user=permissions.state.user,run=++generation,key=props.instanceKey;busy.value='automatic';error.value='';credential.value=undefined
 automaticRequestKey ||= status.value.deliveryOperation || crypto.randomUUID()
 try{const response=await http.post('/registry/runtime-connection/automatic',{instanceKey:key,requestKey:automaticRequestKey},{timeout:60000});if(!alive||run!==generation||user!==permissions.state.user||!permissions.can('deployAgents'))return;status.value=response.data.data;automaticRequestKey='';ElMessage[status.value?.connection==='READY'?'success':'warning'](status.value?.connection==='READY'?'安全自动接入已确认':'接入尚未就绪，请核对当前状态')}
 catch{if(alive&&run===generation)error.value='自动接入尚未确认。请刷新状态并重试原请求；请由管理员核对程序接入协议和实例身份。'}
 finally{if(alive&&user===permissions.state.user&&key===props.instanceKey)busy.value=''}
}
async function verify(){
 if(!permissions.can('deployAgents')||status.value?.taskManaged||busy.value)return
 const user=permissions.state.user,run=++generation;busy.value='verify';error.value=''
 try{const response=await http.post('/registry/runtime-connection/verify',{instanceKey:props.instanceKey},{timeout:60000});if(alive&&run===generation&&user===permissions.state.user){status.value=response.data.data;ElMessage[status.value?.connection==='READY'?'success':'warning'](status.value?.connection==='READY'?'任务连接已验证':'连接尚未通过，请核对接入配置')}}
 catch{if(alive&&run===generation)error.value='连接检查未完成，请刷新状态；不会自动重试任务。'}
 finally{if(alive&&run===generation)busy.value=''}
}
async function revoke(){
 if(!permissions.can('deployAgents')||busy.value)return
 try{await ElMessageBox.confirm('撤销后将拒绝新的任务派发、模型请求和观测上报，已在执行的请求不保证中断。','撤销实例凭证',{type:'warning'})}catch{return}
 if(!alive||!permissions.can('deployAgents')||busy.value)return
 const user=permissions.state.user,key=props.instanceKey;busy.value='revoke';credential.value=undefined
 try{await http.post('/registry/runtime-connection/revoke',{instanceKey:key});if(alive&&user===permissions.state.user&&key===props.instanceKey)await load()}
 finally{if(alive&&user===permissions.state.user&&key===props.instanceKey)busy.value=''}
}
async function copyCredential(){if(!permissions.can('deployAgents')||!credential.value)return;try{await navigator.clipboard.writeText(credential.value.token);ElMessage.success('凭证已复制，请仅用于当前实例的安全配置')}catch{ElMessage.warning('当前浏览器不允许写入剪贴板，请使用安全浏览器环境')}}
const connectionLabel=(value:string)=>({NOT_CONFIGURED:'尚未配置',PENDING:'等待握手验证',DELIVERY_PENDING:'安全配送待确认',READY:'已验证',EXPIRED:'凭证已过期',REVOKED:'凭证已撤销',BLOCKED:'身份不可用'}[value]||value)
watch(()=>props.instanceKey,()=>{clear();void load()},{immediate:true})
watch([()=>permissions.state.user,()=>permissions.can('deployAgents')],clear,{flush:'sync'})
onBeforeUnmount(()=>{alive=false;clear()})
</script>
<style scoped>
.automatic-connection{display:grid;gap:12px;border:1px solid #d8e5f1;background:#f5f9fd;padding:16px;border-radius:10px}.automatic-connection .el-button{justify-self:start}.automatic-connection details{min-width:0}.connection-panel{display:grid;gap:18px;min-width:0}.connection-lead{margin:0;line-height:1.8}.connection-checks{display:grid;grid-template-columns:1fr 1fr;gap:12px}.connection-checks>div{border:1px solid #e0e7f0;padding:14px;display:grid;gap:10px;border-radius:8px}.connection-steps{padding-left:24px;display:grid;gap:24px}.connection-steps p,.muted{font-size:13px;line-height:1.8;color:#61748b}dl{display:grid;grid-template-columns:160px 1fr;font-size:12px;gap:8px;background:#f5f8fc;padding:12px}dd{margin:0;overflow-wrap:anywhere}.credential-row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:12px}.credential-row .el-input{width:230px}footer{display:flex;gap:10px;flex-wrap:wrap}@media(max-width:600px){.connection-checks{grid-template-columns:1fr}dl{grid-template-columns:1fr}}
</style>
