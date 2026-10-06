<template>
  <section class="runtime-inventory">
    <header><div><h2>运行环境发现</h2><p>查看允许接入的运行空间，将已有实例关联到智能体身份。</p></div><el-button :icon="Refresh" :loading="loading" @click="load">刷新实例</el-button></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="snapshot && !snapshot.enabled" title="运行环境尚未启用，请联系管理员配置。" type="info" :closable="false" />
    <p class="inventory-note">纳管后可查看状态并控制休眠、恢复。模型、内容治理与任务执行需要另行接入验证；绑定不会修改原实例配置或网络策略。</p>
    <template v-for="scope in snapshot?.scopes" :key="scope.name">
      <div class="inventory-scope"><strong>运行空间 · {{ scope.name }}</strong><el-tag :type="scope.status === 'CONNECTED' ? 'success' : 'warning'">{{ scope.status === 'CONNECTED' ? '已连接' : '查询不可用' }}</el-tag></div>
      <el-alert v-if="scope.truncated" :title="scope.status === 'CONNECTED' ? '实例数量超过当前查询上限，以下不是完整清单。' : '本次未取得完整数据，请稍后刷新；不代表实例已删除。'" type="warning" :closable="false" />
      <el-table :data="scope.items.filter(row => !agentId || row.agentId === agentId)" empty-text="当前范围没有可显示的实例">
        <el-table-column label="实例" min-width="230"><template #default="{row}"><strong>{{ row.name }}</strong><small class="inventory-template">{{ row.template }}</small></template></el-table-column>
        <el-table-column label="实时状态" width="120"><template #default="{row}">{{ stateLabel(row.state) }}</template></el-table-column>
        <el-table-column label="归属与接入" min-width="220"><template #default="{row}"><router-link v-if="row.agentId" :to="'/agents/'+row.agentId">{{ row.agentName }}</router-link><span v-else>待复核归属</span><small class="inventory-template">{{ enrollmentLabel(row.enrollment) }}</small></template></el-table-column>
        <el-table-column label="治理接入" min-width="180"><template #default="{row}">{{ governanceLabel(row.governance) }}</template></el-table-column>
        <el-table-column label="操作" min-width="230"><template #default="{row}">
          <el-button v-if="row.enrollment === 'UNMANAGED' && permissions.can('deployAgents')" link type="primary" @click="openAdopt(row)">复核纳管</el-button>
          <template v-if="row.enrollment === 'IMPORTED' && permissions.can('operateInstances')"><el-button link :disabled="!!busy" @click="control(row,'RUN_HEALTH_CHECK')">检查状态</el-button><el-button link :disabled="!!busy" @click="control(row,'RESUME')">恢复</el-button><el-button link :disabled="!!busy" @click="control(row,'PAUSE')">休眠</el-button></template>
          <el-button v-if="['IMPORTED','PLATFORM_IDENTITY'].includes(row.enrollment)" link type="primary" @click="connectionKey=row.scope+'/'+row.name">接入验证</el-button>
          <router-link v-if="row.enrollment === 'IMPORTED'" :to="{path:'/agents/'+row.agentId,query:{tab:'deploy'}}">配置托管版本</router-link>
          <span v-if="['IDENTITY_CHANGED','MISSING'].includes(row.enrollment)">需管理员复核</span>
        </template></el-table-column>
      </el-table>
    </template>
    <el-drawer :model-value="!!connectionKey" title="智能体实例接入" size="min(780px, 100vw)" append-to-body destroy-on-close @close="connectionKey='';void load()"><RuntimeConnectionPanel v-if="connectionKey" :key="connectionKey" :instance-key="connectionKey" /></el-drawer>
    <el-dialog v-model="adoptOpen" title="复核并纳管运行实例" width="min(620px, 94vw)" :close-on-click-modal="!busy">
      <template v-if="selected">
        <p><strong>{{ selected.name }}</strong> · {{ selected.scope }}</p>
        <el-form label-position="top"><el-form-item label="归属智能体"><el-select v-model="targetAgent" filterable placeholder="选择目录中的智能体"><el-option v-for="a in agents.filter(a=>a.lifecycleStatus !== 'RETIRED')" :key="a.id" :label="a.name+' · '+a.code" :value="a.id" /></el-select></el-form-item></el-form>
        <router-link to="/agents" @click="adoptOpen=false">尚无身份？前往智能体目录创建</router-link>
        <p class="inventory-note">请核对责任归属。纳管不会自动启用智能体，也不会把现有程序视为已通过任务协议或内容治理验证。</p>
      </template>
      <template #footer><el-button :disabled="!!busy" @click="adoptOpen=false">取消</el-button><el-button type="primary" :loading="busy === 'adopt'" :disabled="!targetAgent || !permissions.can('deployAgents')" @click="adopt">确认纳管</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { http, postData } from '@/api/http'
import { EapApi } from '@/api/eap'
import { SubstrateApi } from '@/api/substrate'
import RuntimeConnectionPanel from '@/components/RuntimeConnectionPanel.vue'
import { usePermissions } from '@/stores/permissions'
import type { Agent } from '@/types/domain'
const props=defineProps<{agentId?:number}>()
const permissions=usePermissions()
type Item={scope:string;name:string;uid:string;template:string;state:string;agentId:number|null;agentName:string|null;enrollment:string;governance:string}
type Snapshot={enabled:boolean;queriedAt:string;scopes:{name:string;status:string;truncated:boolean;items:Item[]}[]}
const snapshot=ref<Snapshot>(),loading=ref(false),error=ref(''),busy=ref(''),adoptOpen=ref(false),selected=ref<Item>(),targetAgent=ref<number>(),agents=ref<Agent[]>([])
const connectionKey=ref('')
let generation=0,alive=true
async function load(){
 const run=++generation;error.value='';snapshot.value=undefined
 if(!permissions.can('viewConsole')){loading.value=false;return}
 loading.value=true
 try{const value=(await http.get<{data:Snapshot}>('/registry/runtime-inventory',{timeout:30000})).data.data;if(alive&&run===generation&&permissions.can('viewConsole'))snapshot.value=value}
 catch{if(alive&&run===generation)error.value='运行环境查询失败，请刷新重试。'}
 finally{if(alive&&run===generation)loading.value=false}
}
async function openAdopt(row:Item){
 if(!permissions.can('deployAgents'))return
 selected.value=row;targetAgent.value=undefined;adoptOpen.value=true
 try{const result=await EapApi.listAgents();if(alive&&adoptOpen.value)agents.value=result}catch{ElMessage.error('智能体目录读取失败')}
}
async function adopt(){
 if(!selected.value||!targetAgent.value||busy.value||!permissions.can('deployAgents'))return
 busy.value='adopt'
 try{await postData('/registry/runtime-inventory/adopt',{scope:selected.value.scope,name:selected.value.name,uid:selected.value.uid,template:selected.value.template,agentId:targetAgent.value});adoptOpen.value=false;ElMessage.success('实例已纳管；内容治理仍需验证');await load()}
 catch{ElMessage.error('纳管失败，请刷新后核对实例身份及归属。')}
 finally{busy.value=''}
}
async function control(row:Item,command:'PAUSE'|'RESUME'|'RUN_HEALTH_CHECK'){
 if(!row.agentId||busy.value||!permissions.can('operateInstances'))return
 busy.value=row.uid
 try{const result=await SubstrateApi.control(row.agentId,row.scope+'/'+row.name,command);if(result.status==='ACKED')ElMessage.success('操作已确认');else ElMessage.warning(result.resultMessage||'操作未确认，请核对状态');await load()}
 catch{ElMessage.error('操作失败，请刷新实例状态。')}finally{busy.value=''}
}
const governanceLabel=(value:string)=>({PLATFORM_PATH:'已登记，按调用证据核对',TASK_READY:'任务已接入，治理证据待验证',SCOPED_PATH:'已验证接入通道的模型与调用链'}[value]||'内容与模型治理待验证')
const stateLabel=(state:string)=>({RUNNING:'运行中',SUSPENDED:'已休眠',CRASHED:'异常',MISSING:'已不在当前清单'}[state]||state)
const enrollmentLabel=(state:string)=>({UNMANAGED:'未纳管',PLATFORM_IDENTITY:'平台创建 · 专属身份',PLATFORM_MANAGED:'平台已登记',REGISTERED:'已关联运行登记',IMPORTED:'运行已纳管',IDENTITY_CHANGED:'身份或模板已改变',MISSING:'原实例未找到'}[state]||state)
watch(()=>props.agentId,()=>{connectionKey.value='';adoptOpen.value=false;void load()})
watch(()=>permissions.state.user,()=>{connectionKey.value='';++generation;snapshot.value=undefined;adoptOpen.value=false;agents.value=[];void load()},{flush:'sync'})
onMounted(load)
onBeforeUnmount(()=>{alive=false;++generation})
</script>
<style scoped>
.runtime-inventory{display:grid;gap:16px;min-width:0}.runtime-inventory header,.inventory-scope{display:flex;justify-content:space-between;align-items:center;gap:16px}.runtime-inventory h2{font-size:20px;margin:0 0 8px}.runtime-inventory header p,.inventory-note{color:#62748b;font-size:13px;line-height:1.7;margin:0}.inventory-template{display:block;color:#7d8b9d;font-size:12px;overflow-wrap:anywhere;margin-top:6px}.inventory-scope{margin-top:10px;padding-top:14px;border-top:1px solid #dce4ed}.runtime-inventory .el-select{width:100%}@media(max-width:600px){.runtime-inventory header{align-items:flex-start;flex-direction:column}}
</style>
