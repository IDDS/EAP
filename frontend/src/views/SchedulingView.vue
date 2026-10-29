<template>
  <div class="scheduling-page">
    <ConsolePageHeader>
      <template #actions><el-button :loading="loading" @click="load">刷新</el-button></template>
    </ConsolePageHeader>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template v-if="data">
      <div class="schedule-hero"><div><span class="eyebrow">执行调度</span><h2>公平轮转 · 等待老化</h2><p>按申请人等权轮转，等待达到 {{ data.agingSeconds }} 秒的任务优先领取。</p></div><span class="policy-badge">单进程 · 非抢占</span></div>
      <div class="schedule-metrics">
        <article><span>当前排队</span><strong>{{ data.queued }}</strong><small>历史队列 {{ data.legacyQueued }} 条</small></article>
        <article><span>新队列最长等待</span><strong>{{ duration(data.oldestWaitMs) }}</strong><small>当前排队任务</small></article>
        <article><span>等待 P50</span><strong>{{ duration(data.waitP50Ms) }}</strong><small>最近 {{ data.waitSamples }} 个有效领取样本</small></article>
        <article><span>等待 P95</span><strong>{{ duration(data.waitP95Ms) }}</strong><small>同一领取样本窗口</small></article>
      </div>
      <el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>申请人排队分布</span><small class="muted">身份摘要 · 最多 100 组</small></div></template>
        <el-empty v-if="!data.groups.length" description="当前没有纳入调度的新任务排队" :image-size="60" />
        <div v-for="group in data.groups" :key="group.groupKey" class="queue-group"><code>{{ group.groupKey.slice(0, 12) }}</code><div class="queue-track"><div :style="{ width: `${Math.max(3, group.queued / Math.max(1, data.queued) * 100)}%` }" /></div><span>{{ group.queued }} 条 · {{ duration(group.oldestWaitMs) }}</span></div>
      </el-card>
      <el-card shadow="never" class="panel"><template #header><div class="panel-header"><span>最近运行与阶段画像</span><small class="muted">仅新纳入任务 · 最近 20 条</small></div></template>
        <el-empty v-if="!data.runs.length" description="尚无新调度记录，历史运行没有回填画像" :image-size="70" />
        <article v-for="run in data.runs" :key="run.runKey" class="schedule-run">
          <div class="run-header"><router-link :to="'/workflow-builder'">工作流 {{ run.workflowId }}</router-link><el-tag :type="run.status === 'SUCCEEDED' ? 'success' : run.status === 'EXECUTION_UNKNOWN' ? 'warning' : 'info'">{{ statusLabel(run.status) }}</el-tag></div>
          <code class="run-key">{{ run.runKey }}</code>
          <p class="muted">申请人 {{ run.groupKey.slice(0, 12) }} · {{ run.selectionReason === 'WAIT_AGING' ? '等待老化优先' : run.selectionReason === 'APPLICANT_TURN' ? '申请人轮转' : '等待领取' }} · 排队 {{ duration(run.queueWaitMs) }}</p>
          <div class="phase-grid"><div v-for="phase in run.phases" :key="`${phase.nodeSequence}-${phase.kind}-${phase.startedMs}`" class="phase" :class="{ uncertain: phase.state === 'UNCONFIRMED' || phase.state === 'EXECUTION_UNKNOWN' || phase.state.startsWith('SKIPPED_') }"><span>步骤 {{ phase.nodeSequence }} · {{ phaseLabel(phase.kind) }}</span><strong>{{ duration(phase.durationMs) }}</strong><small>{{ statusLabel(phase.state) }}</small></div></div>
          <p v-if="!run.phases.length" class="muted">尚无任务阶段观测</p>
        </article>
      </el-card>
      <p class="scope-note">当前只覆盖工作流排队。申请人轮转不代表租户资源配额或 CPU 分配公平。阶段耗时来自实际执行；“未确认”不代表任务失败或资源已回收。异步任务等待包含准备、执行与休眠确认，不等于模型耗时。时钟异常或缺失观测显示“—”。</p>
      <p class="muted">观测时间 {{ new Date(data.observedMs).toLocaleString() }}</p>
    </template>
    <el-empty v-else-if="!loading && !error" description="暂无调度数据" />
  </div>
</template>
<script setup lang="ts">
import ConsolePageHeader from '@/components/common/ConsolePageHeader.vue'
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { getScheduling, type SchedulingSummary } from '@/api/scheduling'
const data = ref<SchedulingSummary | null>(null), loading = ref(false), error = ref('')
let generation = 0
async function load() {
  const own = ++generation; loading.value = true; error.value = ''
  try { const result = await getScheduling(); if (own === generation) data.value = result }
  catch { if (own === generation) { data.value = null; error.value = '调度数据读取失败，请刷新重试' } }
  finally { if (own === generation) loading.value = false }
}
onMounted(load); onBeforeUnmount(() => { generation++ })
function duration(ms: number | null) { return ms === null ? '—' : ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s` }
function phaseLabel(kind: string) { return ({ PREPARATION: '实例准备', TASK_WAIT: '任务回执等待', RECLAIM: '休眠确认', ASYNC_WAIT: '异步任务等待' } as Record<string, string>)[kind] || kind }
function statusLabel(status: string) { return ({ QUEUED: '排队中', RUNNING: '执行中', CANCELLED: '已停止后续调度', SUCCEEDED: '已完成', FAILED: '失败', BLOCKED: '已阻断', WAITING_APPROVAL: '等待审批', EXECUTION_UNKNOWN: '结果未知', CONFIRMED: '已确认', UNCONFIRMED: '未确认', SKIPPED_BUSY: '其他执行或未知占用，未休眠', SKIPPED_UNKNOWN: '任务结果未知，未休眠', SKIPPED_LEGACY: '旧执行，未休眠' } as Record<string, string>)[status] || status }
</script>
<style scoped>
.scheduling-page{min-width:0}.schedule-hero{display:flex;justify-content:space-between;align-items:center;gap:20px;padding:26px;margin:18px 0;border:1px solid var(--el-border-color);border-radius:14px;background:linear-gradient(125deg,rgba(23,93,158,.09),rgba(20,184,166,.06))}.eyebrow{color:var(--el-color-primary);font-size:12px;letter-spacing:2px}.schedule-hero h2{margin:10px 0}.schedule-hero p{margin-bottom:0;line-height:1.7}.policy-badge{white-space:nowrap;padding:8px 12px;border:1px solid var(--el-border-color);border-radius:20px;font-size:12px}.schedule-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:18px}.schedule-metrics article{display:flex;flex-direction:column;gap:10px;padding:20px;border:1px solid var(--el-border-color);border-radius:12px}.schedule-metrics strong{font-size:26px;overflow-wrap:anywhere}.schedule-metrics span,.schedule-metrics small{color:var(--el-text-color-secondary)}.queue-group{display:flex;gap:14px;align-items:center;margin:14px 0;font-size:13px}.queue-group span{white-space:nowrap}.queue-track{flex:1;height:9px;min-width:20px;background:var(--el-fill-color);border-radius:9px;overflow:hidden}.queue-track div{height:100%;background:var(--el-color-primary);border-radius:9px}.schedule-run{padding:18px 0;border-bottom:1px solid var(--el-border-color-lighter)}.schedule-run:last-child{border-bottom:0}.run-header{display:flex;align-items:center;justify-content:space-between;gap:12px}.run-key{display:block;margin-top:10px;overflow-wrap:anywhere;font-size:12px}.phase-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(165px,1fr));gap:10px}.phase{display:flex;flex-direction:column;gap:8px;padding:14px;border-left:3px solid var(--el-color-primary);background:var(--el-fill-color-light);border-radius:6px;font-size:12px}.phase strong{font-size:19px}.phase.uncertain{border-color:var(--el-color-warning)}.scope-note{line-height:1.8;color:var(--el-text-color-secondary);font-size:13px}.panel{margin-bottom:18px}@media(max-width:700px){.schedule-hero{flex-direction:column;align-items:flex-start;padding:20px}.schedule-metrics{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.schedule-metrics article{padding:14px}.schedule-metrics strong{font-size:22px}.queue-group{flex-wrap:wrap}.queue-track{flex-basis:30%}.phase-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
