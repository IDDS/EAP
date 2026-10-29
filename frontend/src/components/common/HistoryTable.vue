<template>
  <div class="history-table">
    <div class="history-filters">
      <el-input v-model="keyword" class="history-search" :placeholder="placeholder" clearable aria-label="搜索历史记录" />
      <el-select v-if="statuses.length" v-model="status" class="history-status" aria-label="状态筛选"><el-option label="全部状态" value="" /><el-option v-for="item in statuses" :key="item" :label="labels?.[item] || statusLabels[item] || item" :value="item" /></el-select>
      <el-date-picker v-model="range" class="history-range" type="datetimerange" start-placeholder="开始时间" end-placeholder="结束时间" aria-label="时间范围" />
      <el-select v-model="order" class="history-order" aria-label="时间排序"><el-option label="最新在前" value="desc"/><el-option label="最早在前" value="asc"/></el-select>
      <el-button @click="reload">刷新</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <el-table v-loading="loading" :data="items" :class="{ 'history-desktop-table': !!$slots.mobile }" @row-click="$emit('row-click', $event)"><slot /></el-table>
    <div v-if="$slots.mobile" v-loading="loading" class="history-mobile-records">
      <slot name="mobile" :items="items" :loading="loading" :error="error" />
    </div>
    <div class="history-pagination">
      <span aria-live="polite">{{ error ? '查询失败' : `共 ${total} 条符合条件的记录` }}</span>
      <PageControls v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[10,20,50,100]"  />
    </div>
  </div>
</template>
<script setup lang="ts">
import PageControls from '@/components/common/PageControls.vue'
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
export interface HistoryParams { page:number; size:number; keyword?:string; status?:string; order:string; from?:string; to?:string }
const props=defineProps<{ fetchPage:(params:HistoryParams)=>Promise<{items:any[];totalElements:number}>; statuses:string[]; placeholder:string; resetKey?:string; labels?:Record<string,string> }>()
defineEmits<{ 'row-click':[row:any] }>()
const statusLabels:Record<string,string>={PENDING:'待运行',RUNNING:'运行中',SUCCEEDED:'执行成功',FAILED:'失败',CANCELLED:'已取消',SUCCESS:'成功',STARTED:'已开始',REQUESTED:'已请求',UNKNOWN:'未知',INPUT:'输入',OUTPUT:'输出',TOOL_INPUT:'工具输入',TOOL_OUTPUT:'工具输出',SYSTEM:'系统',NOT_REQUIRED:'无需审批',APPROVED:'已批准',REJECTED:'已拒绝',ALLOW:'允许',WARN:'警告',REDACT:'脱敏',BLOCK:'阻断',APPROVAL_REQUIRED:'需要审批'}
const items=ref<any[]>([]), total=ref(0), page=ref(1), size=ref(10), keyword=ref(''), status=ref(''), order=ref('desc')
const range=ref<[Date,Date] | null>(null), loading=ref(false), error=ref('')
let generation=0, timer:ReturnType<typeof setTimeout> | undefined
async function reload(){
  clearTimeout(timer)
  const version=++generation
  loading.value=true; error.value=''; items.value=[]
  try {
    const result=await props.fetchPage({page:page.value,size:size.value,keyword:keyword.value.trim()||undefined,status:status.value||undefined,order:order.value,from:range.value?.[0].toISOString(),to:range.value?.[1].toISOString()})
    if(version!==generation)return
    total.value=result.totalElements
    const last=Math.max(1,Math.ceil(total.value/size.value))
    if(page.value>last){page.value=last;return}
    items.value=result.items
  } catch { if(version===generation){error.value='加载失败，请刷新重试';total.value=0} }
  finally {if(version===generation)loading.value=false}
}
function changed(){
  ++generation;clearTimeout(timer);items.value=[];total.value=0;loading.value=true
  page.value=1
  timer=setTimeout(reload,300)
}
watch([keyword,status,order,range,size,()=>props.resetKey],changed)
watch(page,()=>{clearTimeout(timer);timer=setTimeout(reload,0)})
onMounted(reload)
onBeforeUnmount(()=>{++generation;clearTimeout(timer)})
defineExpose({reload})
</script>
<style scoped>
.history-mobile-records{display:none;min-width:0}@media(max-width:760px){.history-table>.history-desktop-table{display:none}.history-mobile-records{display:block;min-height:80px}}
.history-table{min-width:0}.history-filters{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin-bottom:16px}.history-filters>.el-input{width:260px}.history-filters>.el-select{width:140px}.history-filters :deep(.el-date-editor){max-width:100%;width:350px;flex-grow:0;min-height:var(--console-control-height,38px)}.history-filters>.el-button{min-height:var(--console-control-height,38px);margin-left:0}.history-pagination{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-top:16px}.history-pagination>span{font-size:12px;color:var(--el-text-color-secondary)}.history-pagination :deep(.el-pagination){flex-wrap:wrap;row-gap:8px}
@media(max-width:760px){
  .history-filters{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
  .history-filters>:is(.el-input,.el-select,.el-date-editor){width:100%;min-width:0}
  .history-search{grid-column:1 / -1}
  .history-filters > :deep(.el-date-editor){grid-column:1 / -1;width:100%;min-width:0}
  .history-status{grid-row:2;grid-column:1}
  .history-order{grid-row:2;grid-column:2}
  .history-filters:not(:has(.history-status)) .history-order{grid-column:1 / -1}
  .history-filters>.el-button{justify-self:start}
}
</style>
