<template>
  <section class="substrate-monitor">
    <ConsolePageHeader>
      <template #actions><div class="monitor-controls">
        <el-select v-model="backend" aria-label="资源池" @change="changeBackend"><el-option label="常规执行池" value="RUNTIME" /><el-option label="隔离任务池" value="AX" /></el-select>
        <el-select v-model="minutes" aria-label="监控时间范围" @change="refresh"><el-option v-for="item in windows" :key="item.value" :value="item.value" :label="item.label" /></el-select>
        <el-button :loading="loading" @click="refresh">刷新监控</el-button>
        <router-link to="/registry?section=environment">发现运行实例 →</router-link>
      </div></template>
    </ConsolePageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="snapshot && snapshot.status !== 'CONNECTED'" :title="statusNotice" :type="snapshot.status === 'PARTIAL' ? 'warning' : 'info'" :closable="false" />
    <div class="monitor-meta"><span><i :class="['source-dot', snapshot?.status === 'CONNECTED' ? 'connected' : '']" />{{ statusLabel }} <span v-if="snapshot">· {{ backend === 'AX' ? '隔离任务池' : '常规执行池' }}</span></span><span>查询时间 {{ snapshot ? new Date(snapshot.queriedAt).toLocaleTimeString('zh-CN') : '—' }} · 30 秒刷新</span></div>
    <div class="monitor-top">
      <article class="capacity-panel">
        <div class="eyebrow">执行节点容量</div><div class="capacity-value">{{ metricText(idle, 0) }} <span>空闲 / {{ metricText(total, 0) }} 总计</span></div>
        <div class="capacity-track" role="img" :aria-label="`已分配 ${metricText(assigned,0)}，空闲 ${metricText(idle,0)}`"><span :style="{ width: assignedPercent + '%' }" /></div>
        <div class="capacity-legend"><span><i class="assigned-key" /> 已分配 {{ metricText(assigned,0) }}</span><span><i class="idle-key" /> 空闲 {{ metricText(idle,0) }}</span></div>
        <p>{{ total === null ? '等待容量数据' : idle === 0 ? '暂无空闲执行节点，新的唤醒可能需要等待' : '按执行节点池去重汇总，关注可分配容量' }}</p>
      </article>
      <article class="metric-card"><el-icon class="metric-icon blue" aria-hidden="true"><Odometer /></el-icon><div class="metric-caption">恢复耗时 P95</div><strong>{{ metricText(latency) }}<small>ms</small></strong><p>最近 5 分钟完成的恢复操作</p><span class="data-note">{{ metricState('restoreTrend') }}</span></article>
      <article class="metric-card"><el-icon class="metric-icon teal" aria-hidden="true"><Coin /></el-icon><div class="metric-caption">已采样实例内存</div><strong>{{ metricText(memory) }}<small>MiB</small></strong><p>当前命名空间 · 模板合计</p><span class="data-note">{{ metricState('memoryTrend') }}</span></article>
      <article class="metric-card"><el-icon class="metric-icon orange" aria-hidden="true"><Cpu /></el-icon><div class="metric-caption">CPU 使用量</div><strong>{{ metricText(cpu, 3) }}<small>核</small></strong><p>最近 5 分钟 CPU 时间变化率</p><span class="data-note">{{ metricState('cpuTrend') }}</span></article>
    </div>
    <div class="monitor-charts">
      <article class="monitor-panel"><div class="monitor-panel-heading"><div><h2>唤醒与路由</h2><p>P95 · 毫秒 · 5 分钟滚动窗口</p></div><span class="panel-label">延迟趋势</span></div>
        <TrendChart v-if="hasPoints(['restoreTrend','checkpointTrend','routeTrend'])" :option="latencyOption" height="280px" /><div v-else class="monitor-empty">{{ groupState(['restoreTrend','checkpointTrend','routeTrend']) }}</div>
        <p class="panel-footnote">路由指标反映寻址与唤醒过程，不是任务或模型执行耗时。无事件区间保持空缺。</p>
      </article>
      <article class="monitor-panel"><div class="monitor-panel-heading"><div><h2>资源使用</h2><p>已采样实例合计 · 内存与 CPU</p></div><span class="panel-label">资源趋势</span></div>
        <TrendChart v-if="hasPoints(['memoryTrend','cpuTrend'])" :option="resourceOption" height="280px" /><div v-else class="monitor-empty">{{ groupState(['memoryTrend','cpuTrend']) }}</div>
        <p class="panel-footnote">休眠实例通常没有资源样本；此图不能代替单实例资源统计。</p>
      </article>
    </div>
    <div class="monitor-bottom">
      <article v-if="backend === 'AX'" class="monitor-panel deployments-panel">
        <div class="monitor-panel-heading"><div><h2>隔离任务与沙箱</h2><p>业务状态与沙箱状态分别展示 · 最近 100 个任务</p></div><router-link to="/ax-tasks">任务中心 →</router-link></div>
        <el-table :data="snapshot?.axTasks ?? []" empty-text="暂无异步任务">
          <el-table-column label="任务" min-width="280"><template #default="{ row }"><router-link :to="{path:'/ax-tasks',query:{taskKey:row.taskKey}}">{{ row.taskKey }}</router-link><div class="template-name">{{ row.workflowRunKey || '独立任务' }}</div></template></el-table-column>
          <el-table-column prop="status" label="业务状态" min-width="140" /><el-table-column prop="sandboxPhase" label="沙箱状态" min-width="120" />
        </el-table><p class="panel-footnote">指标来自隔离任务池；任务状态来自持久回执。沙箱 Ready / Running 不等于业务成功。</p>
      </article>
      <article v-else class="monitor-panel deployments-panel"><div class="monitor-panel-heading"><div><h2>部署版本与实例</h2><p>选择版本，查看实时实例状态及 Agent 详情</p></div><span class="panel-label">{{ snapshot?.deployments.length ?? '—' }} 个版本</span></div>
        <el-table :data="(snapshot?.deployments ?? []).slice((page - 1) * 6, page * 6)" empty-text="暂无登记的部署版本" @row-click="inspect">
          <el-table-column label="Agent / 模板" min-width="230"><template #default="{ row }"><strong>{{ row.agentName }}</strong><div class="template-name">{{ row.template }}</div></template></el-table-column>
          <el-table-column label="版本" width="72"><template #default="{ row }">v{{ row.version }}</template></el-table-column>
          <el-table-column label="部署状态" width="118"><template #default="{ row }"><el-tag :type="row.status === 'READY' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column label="内存 MiB" width="112"><template #default="{ row }">{{ metricText(templateMemory(row.template)) }}</template></el-table-column>
          <el-table-column label="采样实例" width="100"><template #default="{ row }">{{ metricText(latest(snapshot?.metrics.sampledActors, 'ate_template_name', row.template), 0) }}</template></el-table-column>
          <el-table-column label="详情" width="84"><template #default="{ row }"><el-button text type="primary" @click.stop="inspect(row)">查看</el-button></template></el-table-column>
        </el-table>
        <el-pagination v-if="(snapshot?.deployments.length ?? 0) > 6" v-model:current-page="page" :page-size="6" :total="snapshot?.deployments.length ?? 0" layout="prev, pager, next" class="version-pagination" />
        <p class="panel-footnote">模板资源是其已采样实例合计；READY 表示部署就绪，不表示实例正在运行。{{ snapshot?.deploymentsTruncated ? '仅显示最近 100 个版本。' : '' }}</p>
      </article>
      <article class="monitor-panel signals-panel"><div class="monitor-panel-heading"><div><h2>异常信号</h2><p>所选时间范围内的累计增量</p></div></div>
        <div class="signal-summary"><span>调度未成功</span><strong>{{ metricText(latest(snapshot?.metrics.schedulerFailures)) }}</strong></div>
        <p class="data-note">{{ metricState('schedulerFailures') }}</p>
        <h3>Actor 崩溃原因</h3>
        <div v-for="item in crashes" :key="item.reason" class="failure-row"><div><span>{{ item.reason }}</span><strong>{{ metricText(item.value) }}</strong></div><div class="failure-track"><span :style="{width: item.width + '%'}" /></div></div>
        <p v-if="!crashes.length" class="signal-empty">{{ metricState('crashes') === '有采集数据' ? '该时段未观察到崩溃增量' : metricState('crashes') }}</p>
        <p class="panel-footnote">计数由采样数据估算，可能含小数。未采集不等于没有异常。</p>
      </article>
    </div>
    <el-drawer v-model="drawerOpen" append-to-body :z-index="4000" :title="selected ? `${selected.agentName} · v${selected.version}` : '实例详情'" size="min(620px, 96vw)">
      <template v-if="selected"><p class="template-name">{{ selected.template }}</p><el-alert v-if="instanceError" :title="instanceError" type="error" :closable="false" />
        <el-table :data="instances" v-loading="instanceLoading" empty-text="此版本没有可见实例"><el-table-column prop="name" label="实例" min-width="210" /><el-table-column prop="state" label="实时状态" min-width="130" /></el-table>
        <p class="muted">实例状态来自运行环境控制接口；查询不会唤醒或修改实例。</p><router-link :to="`/agents/${selected.agentId}`">打开 Agent 详情 →</router-link>
      </template>
    </el-drawer>
  </section>
</template>
<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import TrendChart from '@/components/TrendChart.vue'
import { Coin, Cpu, Odometer } from '@element-plus/icons-vue'
import { fetchSubstrateMonitoring, latest, metricText } from '@/api/substrateMonitoring'
import type { MonitoredDeployment, SubstrateMonitoring } from '@/api/substrateMonitoring'
import { SubstrateApi } from '@/api/substrate'
import type { SubstrateInstance } from '@/api/substrate'
import { usePermissions } from '@/stores/permissions'
const permissions = usePermissions()
const windows = [{label:'最近 15 分钟',value:15},{label:'最近 1 小时',value:60},{label:'最近 6 小时',value:360},{label:'最近 24 小时',value:1440}]
const page=ref(1), backend=ref('RUNTIME')
function changeBackend(){snapshot.value=undefined;drawerOpen.value=false;page.value=1;void refresh()}
const minutes=ref(60), snapshot=ref<SubstrateMonitoring>(), loading=ref(false), error=ref('')
const selected=ref<MonitoredDeployment>(), drawerOpen=ref(false), instances=ref<SubstrateInstance[]>([]), instanceLoading=ref(false), instanceError=ref('')
let revision=0, instanceRevision=0, disposed=false, timer: ReturnType<typeof setInterval> | undefined
async function refresh() {
  const request=++revision, window=minutes.value
  if(snapshot.value?.windowMinutes!==window)snapshot.value=undefined
  error.value=''
  if(!permissions.can('viewConsole')) { loading.value=false;return }
  loading.value=true
  try { const result=await fetchSubstrateMonitoring(window,backend.value);if(!disposed && request===revision && permissions.can('viewConsole')) snapshot.value=result }
  catch { if(!disposed && request===revision) { snapshot.value=undefined;error.value='监控查询失败，请检查登录状态或稍后重试。当前未显示旧数据。' } }
  finally {if(request===revision)loading.value=false}
}
async function inspect(row: MonitoredDeployment) {
  if(!permissions.can('viewConsole'))return
  const request=++instanceRevision
  selected.value=row;drawerOpen.value=true;instances.value=[];instanceError.value='';instanceLoading.value=true
  try {const result=await SubstrateApi.instances(row.agentId);if(!disposed && request===instanceRevision && drawerOpen.value && permissions.can('viewConsole'))instances.value=result.filter(i=>i.version===row.version)}
  catch {if(!disposed && request===instanceRevision)instanceError.value='实例状态查询失败，请稍后重试'}
  finally {if(request===instanceRevision)instanceLoading.value=false}
}
watch(drawerOpen, open=>{if(!open){instanceRevision++;instances.value=[];instanceLoading.value=false}})
watch(()=>permissions.state.user,()=>{revision++;instanceRevision++;snapshot.value=undefined;drawerOpen.value=false;instances.value=[];void refresh()},{flush:'sync'})
const idle=computed(()=>latest(snapshot.value?.metrics.workers,'ate_worker_state','idle'))
const assigned=computed(()=>latest(snapshot.value?.metrics.workers,'ate_worker_state','assigned'))
const total=computed(()=>idle.value===null || assigned.value===null?null:idle.value+assigned.value)
const assignedPercent=computed(()=>total.value && assigned.value!==null?Math.max(0,Math.min(100,assigned.value/total.value*100)):0)
const latency=computed(()=>{const v=latest(snapshot.value?.metrics.restoreTrend);return v===null?null:v*1000})
const memory=computed(()=>{const v=latest(snapshot.value?.metrics.memoryTrend);return v===null?null:v/1048576})
const cpu=computed(()=>latest(snapshot.value?.metrics.cpuTrend))
const statusLabel=computed(()=>loading.value?'查询中':({CONNECTED:'监控已连接',PARTIAL:'部分指标不可用',UNAVAILABLE:'监控源不可用',DISABLED:'监控未配置'}[snapshot.value?.status ?? 'UNAVAILABLE']))
const statusNotice=computed(()=>snapshot.value?.status==='DISABLED'?'尚未配置监控数据源，部署信息仍可查看。':snapshot.value?.status==='PARTIAL'?'部分指标查询失败。不可用指标以空缺显示，请勿据此判断系统正常。':'监控源暂不可用。未用历史数据或零值替代。')
function metricState(key:string){const m=snapshot.value?.metrics[key];return !m?'尚无数据':m.status==='ERROR'?'采集查询失败':m.status==='EMPTY'?'没有可用样本':key.endsWith('Trend') && latest(m)===null?'当前无样本，历史可见':'有采集数据'}
function hasPoints(keys:string[]){return keys.some(k=>snapshot.value?.metrics[k]?.status==='OK')}
function groupState(keys:string[]){return keys.some(k=>snapshot.value?.metrics[k]?.status==='ERROR')?'指标查询失败，请稍后刷新':'此时间范围暂无可用样本'}
function templateMemory(template:string){const v=latest(snapshot.value?.metrics.memoryByTemplate,'ate_template_name',template);return v===null?null:v/1048576}
const crashes=computed(()=>{
 const m=snapshot.value?.metrics.crashes;if(m?.status!=='OK')return []
 const rows=m.series.map(s=>({reason:s.labels.ate_failure_reason || '未分类',value:s.points.at(-1)?.value ?? 0})).filter(s=>s.value>0).sort((a,b)=>b.value-a.value)
 const max=rows[0]?.value || 1;return rows.slice(0,8).map(s=>({...s,width:s.value/max*100}))
})
function chart(defs:{key:string;name:string;color:string;factor:number;axis?:number}[],resource=false){return {
 animation:false,tooltip:{trigger:'axis',renderMode:'richText'},legend:{bottom:0,icon:'roundRect',textStyle:{color:'#66758e'}},grid:{left:54,right:resource?54:24,top:30,bottom:55},
 xAxis:{type:'time',axisLine:{lineStyle:{color:'#dce4ef'}},axisLabel:{color:'#718097',hideOverlap:true}},
 yAxis:resource?[{type:'value',name:'MiB',splitLine:{lineStyle:{color:'#edf1f7'}}},{type:'value',name:'CPU 核',splitLine:{show:false}}]:{type:'value',name:'ms',splitLine:{lineStyle:{color:'#edf1f7'}}},
 series:defs.map(d=>({name:d.name,type:'line',yAxisIndex:d.axis??0,showSymbol:false,connectNulls:false,lineStyle:{width:2.5,color:d.color},itemStyle:{color:d.color},areaStyle:{opacity:0.035},data:(snapshot.value?.metrics[d.key]?.status==='ERROR'?[]:snapshot.value?.metrics[d.key]?.series[0]?.points ?? []).map(p=>[p.at*1000,p.value===null?null:p.value*d.factor])}))
}}
const latencyOption=computed(()=>chart([{key:'restoreTrend',name:'恢复 P95',color:'#3268e8',factor:1000},{key:'checkpointTrend',name:'快照 P95',color:'#0d9e91',factor:1000},{key:'routeTrend',name:'路由 P95',color:'#eaa044',factor:1000}]))
const resourceOption=computed(()=>chart([{key:'memoryTrend',name:'内存',color:'#3268e8',factor:1/1048576},{key:'cpuTrend',name:'CPU',color:'#0d9e91',factor:1,axis:1}],true))
onMounted(()=>{void refresh();timer=setInterval(()=>{if(!loading.value && !document.hidden)void refresh()},30000)})
onUnmounted(()=>{disposed=true;revision++;instanceRevision++;if(timer)clearInterval(timer)})
</script>
<style scoped>
.version-pagination{justify-content:flex-end;margin-top:16px}
.substrate-monitor{max-width:1680px;margin:auto;display:grid;gap:20px;color:#263852}.monitor-heading,.monitor-controls,.monitor-meta,.monitor-panel-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.monitor-heading h2{font-size:28px;margin:8px 0;font-weight:750;letter-spacing:-.6px}.monitor-heading p,.monitor-panel-heading p{margin:0;color:#7a879b;font-size:13px}.eyebrow{font-size:11px;letter-spacing:1.2px;color:#8799b5;font-weight:650}.monitor-controls .el-select{width:150px}.monitor-meta{font-size:12px;color:#76879c}.source-dot{display:inline-block;width:7px;height:7px;background:#a5b1c2;border-radius:50%;margin-right:7px}.source-dot.connected{background:#0d9e91;box-shadow:0 0 0 4px #0d9e9115}.monitor-top{display:grid;grid-template-columns:1.35fr 1fr 1fr 1fr;gap:16px}.capacity-panel{background:linear-gradient(135deg,#132946,#203e68);border:1px solid #24486f;border-radius:14px;padding:24px;color:white;box-shadow:0 8px 24px #16355714}.capacity-value{font-size:48px;font-weight:700;margin:14px 0 20px;line-height:1}.capacity-value span{font-size:13px;font-weight:400;color:#adc2dc;margin-left:4px}.capacity-track{height:9px;border-radius:6px;background:#30baac;overflow:hidden}.capacity-track>span{display:block;height:100%;background:#789ef9;transition:width .3s}.capacity-legend{display:flex;gap:20px;font-size:12px;color:#c3d3e8;margin-top:12px}.capacity-legend i{display:inline-block;width:7px;height:7px;border-radius:2px;margin-right:5px}.assigned-key{background:#789ef9}.idle-key{background:#30baac}.capacity-panel p{font-size:11px;color:#a5b9d2;margin:18px 0 0}.metric-card,.monitor-panel{background:white;border:1px solid #e2e9f2;border-radius:14px;box-shadow:0 3px 14px #203f6804;min-width:0}.metric-card{padding:20px}.metric-icon{display:flex;width:33px;height:33px;align-items:center;justify-content:center;border-radius:9px;font-size:22px;margin-bottom:14px}.blue{background:#edf3ff;color:#3268e8}.teal{background:#e7f7f4;color:#0d9e91}.orange{background:#fff5e8;color:#e49b37}.metric-caption{color:#6c7e95;font-size:13px}.metric-card strong{display:block;font-size:31px;line-height:1.4;font-weight:700;margin-top:4px;letter-spacing:-.5px}.metric-card small{font-size:12px;color:#8b98aa;font-weight:400;margin-left:7px;letter-spacing:0}.metric-card p{font-size:11px;color:#8b98aa;margin:6px 0}.data-note{font-size:11px;color:#8798ad}.monitor-charts{display:grid;grid-template-columns:1fr 1fr;gap:20px}.monitor-panel{padding:22px}.monitor-panel h2{font-size:16px;margin:0 0 7px}.panel-label{font-size:11px;color:#6c819b;background:#f3f6fb;border-radius:5px;padding:5px 8px;white-space:nowrap}.panel-footnote{font-size:11px;color:#8c99ac;line-height:1.7;margin:14px 0 0}.monitor-empty{height:280px;display:grid;place-items:center;color:#96a3b5;background:repeating-linear-gradient(0deg,transparent,transparent 55px,#f2f5f9 56px)}.monitor-bottom{display:grid;grid-template-columns:minmax(0,3fr) minmax(260px,1fr);gap:20px}.deployments-panel .el-table{margin-top:18px}.template-name{font-size:11px;color:#889ab0;margin-top:5px;overflow-wrap:anywhere}.signals-panel h3{font-size:12px;color:#6b7e97;margin-top:24px}.signal-summary{display:flex;justify-content:space-between;align-items:center;margin-top:24px;font-size:13px}.signal-summary strong{font-size:24px}.failure-row{margin:18px 0}.failure-row>div:first-child{display:flex;justify-content:space-between;font-size:11px;gap:10px;overflow-wrap:anywhere}.failure-track{height:5px;background:#f4f0ef;border-radius:5px;margin-top:8px}.failure-track span{display:block;height:100%;background:#dba26f;border-radius:5px}.signal-empty{color:#91a0b3;font-size:12px;padding:25px 0}.monitor-heading,.monitor-controls{flex-wrap:wrap}
@media(max-width:1200px){.monitor-top{grid-template-columns:repeat(2,minmax(0,1fr))}.monitor-bottom{grid-template-columns:1fr}.monitor-charts{grid-template-columns:1fr}}
@media(max-width:650px){.monitor-top{grid-template-columns:1fr}.monitor-panel{padding:16px}.monitor-meta{align-items:flex-start;flex-direction:column;gap:10px}.monitor-heading h2{font-size:24px}.monitor-controls{width:100%}.monitor-controls .el-select{flex:1}.capacity-panel{padding:22px}}
</style>
