<template>
  <section class="tool-recovery-facts" aria-label="原工具步骤核验">
    <div class="recovery-heading"><h3>原工具步骤核验</h3><el-button :loading="loading" :disabled="!permissions.can('viewConsole')" @click="load">刷新核验记录</el-button></div>
    <p class="muted">查询责任保存在平台中，中断后继续核对原操作。确认工具效果不代表工作流已经恢复，也不会自动派发后续节点。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="report">
      <p v-if="!report.enrolled" class="muted">此历史执行未加入持久核验协议，原结果保持。</p>
      <p v-else-if="!report.entries.length" class="muted">暂无已登记的受控工具步骤；普通模型及其他工具不在此协议范围。</p>
      <article v-for="entry in report.entries" :key="entry.sequence" class="recovery-entry">
        <div class="recovery-heading"><strong>{{ entry.nodeKey }}</strong><el-tag :type="entry.state === 'CONFIRMED' ? 'success' : entry.state === 'NO_COMMIT' ? 'info' : 'warning'">{{ stateLabel(entry.state) }}</el-tag></div>
        <p>{{ entry.reason }}</p>
        <dl><dt>原操作</dt><dd>{{ entry.operationId }}</dd><dt>原授权根</dt><dd>{{ entry.rootId }}</dd><dt>原预算</dt><dd>{{ entry.budgetKey }}</dd><dt>绑定实例 UID</dt><dd>{{ entry.actorUid }}</dd><dt>查询责任</dt><dd>平台查询队列 · 第 {{ entry.queryEpoch }} 轮</dd><dt>最近核验</dt><dd>{{ entry.observedAt ? new Date(entry.observedAt).toLocaleString() : '尚未核验' }}</dd><template v-if="entry.operation?.effectId"><dt>原效果</dt><dd>{{ entry.operation.effectId }}</dd><dt>原计数值</dt><dd>{{ entry.operation.counterValue }}</dd></template></dl>
      </article>
      <p class="muted">{{ report.responsibility }}</p>
    </template>
  </section>
</template>
<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { EapApi } from '@/api/eap'
import { usePermissions } from '@/stores/permissions'
import type { WorkflowToolRecovery } from '@/types/domain'
const props = defineProps<{ workflowId: number; runKey: string }>()
const permissions = usePermissions()
const report = ref<WorkflowToolRecovery | null>(null)
const loading = ref(false), error = ref('')
let revision = 0, disposed = false
function clear() { revision++; report.value = null; loading.value = false; error.value = '' }
async function load() {
  if (disposed || loading.value || !permissions.can('viewConsole')) return
  const request = ++revision, workflowId = props.workflowId, runKey = props.runKey
  loading.value = true; report.value = null; error.value = ''
  try {
    const result = await EapApi.getWorkflowToolRecovery(workflowId, runKey)
    if (!disposed && request === revision && workflowId === props.workflowId && runKey === props.runKey && permissions.can('viewConsole')) report.value = result
  } catch { if (!disposed && request === revision) error.value = '核验记录读取失败，不能推定原操作未提交' }
  finally { if (request === revision) loading.value = false }
}
function stateLabel(state: string) { return ({ PENDING: '等待核验', CHECKING: '正在核验', UNKNOWN: '效果未知 · 保留责任', CONFIRMED: '原效果已确认', NO_COMMIT: '原事务明确未提交' } as Record<string,string>)[state] || state }
watch([() => props.workflowId, () => props.runKey, () => permissions.state.user, () => permissions.can('viewConsole')], () => { clear(); void load() }, { immediate: true, flush: 'sync' })
const timer = setInterval(() => { if (report.value?.entries.some(e => ['PENDING','CHECKING','UNKNOWN'].includes(e.state))) void load() }, 5000)
onUnmounted(() => { disposed = true; clear(); clearInterval(timer) })
</script>
<style scoped>
.tool-recovery-facts{margin-top:22px;padding-top:18px;border-top:1px solid #dfe7ee}.recovery-heading{display:flex;justify-content:space-between;align-items:center;gap:12px}.recovery-heading h3{font-size:16px;margin:0}.recovery-entry{border:1px solid #dfe7ee;border-radius:6px;padding:16px;margin:14px 0;background:#f7f9fc}.recovery-entry p,.muted{font-size:13px;color:#64748b;line-height:1.7}.recovery-entry dl{display:grid;grid-template-columns:110px minmax(0,1fr);gap:8px;font-size:13px}.recovery-entry dt{color:#64748b}.recovery-entry dd{margin:0;overflow-wrap:anywhere}@media(max-width:600px){.recovery-heading{align-items:flex-start;flex-wrap:wrap}.recovery-entry dl{grid-template-columns:1fr}.recovery-entry dd{margin-bottom:6px}}
</style>
