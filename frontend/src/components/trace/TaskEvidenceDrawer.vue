<template>
  <el-drawer append-to-body :z-index="4000" :model-value="true" title="任务结果与执行过程" size="min(780px, 100vw)" @close="$emit('close')">
    <div v-loading="loading" class="task-evidence">
      <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
      <el-button :disabled="loading" @click="load">刷新进展</el-button>
      <template v-if="data">
        <h2>任务 #{{ data.task.id }}</h2>
        <div class="task-summary"><div><span>执行状态</span><strong>{{ executionLabel(data.task.status) }}</strong></div><div><span>业务问题</span><strong>{{ outcomeLabel(data.task.problemResolved) }}</strong></div></div>
        <section class="task-result"><h3>要处理的事情</h3><p>{{ data.task.inputSummary || '尚未提供任务说明' }}</p><h3>执行结果</h3><p>{{ data.task.resultSummary || '暂未提供执行结果。可刷新进展或查看下方执行记录。' }}</p><h3>业务确认说明</h3><p>{{ data.task.resolutionSummary || '尚未填写确认说明' }}</p></section>
        <section class="task-next"><h3>建议下一步</h3><p>{{ taskNextStep(data.task) }}</p>
          <div v-if="canConfirm" class="result-actions"><el-button :disabled="loading || !!error || confirming" type="success" plain @click="$emit('confirm-result', data.task, true)">确认问题已解决</el-button><el-button :disabled="loading || !!error || confirming" plain @click="$emit('confirm-result', data.task, false)">记录尚未解决</el-button></div>
          <p class="evidence-note">这是业务结果登记，不会改变任务的执行进展。</p>
        </section>
        <details class="evidence-note"><summary>关联方式与技术信息</summary><p>任务类型：{{ data.task.taskType }}。记录通过明确的任务标识关联；未关联不表示没有发生。工作流标识与任务编号可能不同。</p></details>
        <details v-for="section in data.sections" :key="section.source" class="evidence-section">
          <summary>{{ labels[section.source] || section.source }} <small>{{ section.records.length }} 条已加载</small></summary>
          <p v-if="section.hasMore || section.conflictingAgentRecords" class="evidence-warning">{{ section.hasMore ? '超过100条，仅展示最近100条。' : '' }}{{ section.conflictingAgentRecords ? ` ${section.conflictingAgentRecords} 条记录的智能体与任务冲突，已隔离，不计入当前列表。` : '' }}</p>
          <p v-if="!section.records.length" class="evidence-note">未找到明确关联的记录，不能据此判定没有发生。</p>
          <article v-for="record in section.records" :key="record.id">
            <div><strong>{{ record.label }}</strong><el-tag size="small" effect="plain">{{ record.status || '未知' }}</el-tag></div>
            <p class="evidence-key">{{ record.key }}</p>
            <p>{{ record.observedAt ? new Date(record.observedAt).toLocaleString() : '时间未知' }}<span v-if="record.agentId == null"> · 未提供智能体标识，仅有任务关联</span></p>
            <p v-if="record.amount != null">{{ section.source === 'VALUE' ? '记录收益' : '记录成本' }}：{{ record.amount }} {{ record.currency || '币种未知' }}</p>
            <router-link v-if="record.traceId" :to="{ path: '/physical', query: { traceId: record.traceId, taskId: String(data.task.id), ...(record.agentId == null ? {} : { agentId: String(record.agentId) }) } }">查看详细执行链路 ↗</router-link>
            <span v-else class="evidence-note">尚未关联执行链路</span>
          </article>
        </details>
        <p class="evidence-note">治理记录反映登记的动作，不单独证明实际拦截。Trace 上下文可能包含同一 Trace 的其他任务；金额逐条展示，不跨币种合计。</p>
      </template>
    </div>
  </el-drawer>
</template>
<script setup lang="ts">
import { executionLabel, outcomeLabel, taskNextStep } from '@/utils/taskPresentation'
import { onBeforeUnmount, ref, watch } from 'vue'
import { getData } from '@/api/http'
import type { AgentTask } from '@/types/domain'
interface Evidence { id: number; key: string; traceId?: string; agentId?: number; label: string; status: string; observedAt?: string; amount?: number; currency?: string }
interface EvidenceResponse { task: AgentTask; associationBasis: string; sections: { source: string; records: Evidence[]; hasMore: boolean; conflictingAgentRecords: number }[] }
const props = defineProps<{ taskId: number; canConfirm?: boolean; confirming?: boolean }>()
defineEmits<{ close: []; 'confirm-result':[task:AgentTask,resolved:boolean] }>()
const data = ref<EvidenceResponse>()
const error = ref('')
const loading = ref(false)
const labels: Record<string,string> = { MODEL: '模型调用', TOOL: '工具调用', GOVERNANCE: '治理动作记录', VALUE: '业务价值记录' }
let version = 0
async function load() {
  const request = ++version
  loading.value = true; error.value = ''
  try {
    const result = await getData<EvidenceResponse>(`/tasks/${props.taskId}/evidence`, { silentError: true })
    if (request === version) data.value = result
  } catch {
    if (request === version) error.value = '任务证据加载失败，请检查权限、连接或后端版本后重试。'
  } finally { if (request === version) loading.value = false }
}
defineExpose({load})
watch(() => props.taskId, () => { data.value = undefined; void load() }, { immediate: true })
onBeforeUnmount(() => { version++ })
</script>
<style scoped>
.task-result p{white-space:pre-wrap;overflow-wrap:anywhere}.task-next{padding:16px;margin:18px 0;background:#f0f6fc;border-radius:8px}.result-actions{display:flex;flex-wrap:wrap;gap:8px}.result-actions .el-button{margin:0}.evidence-section summary{cursor:pointer;font-weight:600;padding:12px 0}.task-evidence{color:#28435f}.task-evidence h2{font-size:18px;overflow-wrap:anywhere}.task-evidence p{font-size:13px;line-height:1.6}.task-summary{display:grid;grid-template-columns:1fr 1fr;gap:16px;padding:16px;background:#f0f6fc;border-radius:7px}.task-summary span{display:block;color:#667e95;font-size:12px}.task-summary strong{display:block;margin-top:6px;font-size:16px}.evidence-note{color:#6d7f93}.evidence-section{margin-top:24px}.evidence-section h3{font-size:16px;padding-bottom:10px;border-bottom:1px solid #dce6f0}.evidence-section small{font-weight:400;font-size:12px;color:#71849a;margin-left:8px}.evidence-section article{padding:12px 0;border-bottom:1px solid #e5edf4}.evidence-section article>div{display:flex;justify-content:space-between;gap:12px}.evidence-section article p{margin:5px 0;color:#627991}.evidence-section a{font-size:13px;color:#287ac0;text-decoration:none}.evidence-key{overflow-wrap:anywhere}.evidence-warning{color:#90631f;background:#fff6e5;padding:8px}.task-evidence .el-alert{margin-bottom:12px}
</style>
