<template>
  <section class="execution-configuration" aria-label="执行配置溯源" v-if="permissions.can('viewConsole')">
    <header><h3>执行配置溯源</h3><el-button size="small" :loading="loading" @click="load">刷新配置记录</el-button></header>
    <p>节点配置在提交时保存；实例绑定在派发前记录，不能单独证明任务已执行成功。</p>
    <p v-if="loading" role="status">正在读取原配置记录…</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="report">
      <p v-if="!report.nodes.length">原执行未保存节点配置。</p>
      <details v-for="node in report.nodes" :key="node.nodeKey" class="configuration-node">
        <summary><span>{{ node.sequence + 1 }}. {{ node.label }}</span><small>{{ bindingLabel(node.bindingState) }}</small></summary>
        <dl><dt>提交时节点版本</dt><dd>{{ node.nodeVersion == null ? '未记录' : `v${node.nodeVersion}` }} · {{ node.nodeKey }}</dd><dt>执行方式</dt><dd>{{ backendLabel(node.executionBackend) }}<template v-if="node.agentId != null"> · Agent #{{ node.agentId }}</template></dd></dl>
        <template v-if="node.runtime">
          <dl><dt>派发时选择的实例</dt><dd>{{ node.runtime.instanceKey }}</dd><dt>运行协议</dt><dd>{{ node.runtime.protocol }}</dd><dt>Actor UID</dt><dd>{{ node.runtime.actorUid || '未记录' }}</dd><dt>ActorTemplate</dt><dd>{{ node.runtime.actorTemplate || '未记录' }}</dd><dt>部署版本</dt><dd>{{ node.runtime.deploymentVersion == null ? '未确认原部署关系' : `v${node.runtime.deploymentVersion}` }}<template v-if="node.runtime.sourceType"> · {{ sourceLabel(node.runtime.sourceType) }}</template></dd><dt v-if="node.runtime.deploymentKey">原部署编号</dt><dd v-if="node.runtime.deploymentKey">{{ node.runtime.deploymentKey }}</dd><dt>镜像摘要</dt><dd>{{ node.runtime.imageDigest || '未记录不可变摘要' }}</dd><dt>绑定记录时间</dt><dd>{{ new Date(node.runtime.capturedAt).toLocaleString('zh-CN') }}</dd></dl>
        </template>
        <p v-else>{{ missingLabel(node.bindingState) }}</p>
      </details>
      <p>这些记录用于核对原执行。当前模板或部署变化不会改写已保存的记录；未记录的历史版本不使用当前版本补推。</p>
    </template>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { usePermissions } from '@/stores/permissions'
import { getExecutionConfiguration, type ExecutionConfiguration } from '@/api/executionConfiguration'
import type { WorkflowExecution } from '@/types/domain'
const props = defineProps<{ execution: WorkflowExecution }>()
const permissions = usePermissions()
const report = ref<ExecutionConfiguration | null>(null), loading = ref(false), error = ref('')
let generation = 0, alive = true
function clear() { generation++; report.value = null; loading.value = false; error.value = '' }
function allowed() { return alive && permissions.can('viewConsole') }
function bindingLabel(state: string) { return ({ DEPLOYMENT_RECORDED: '已记录原部署版本', RUNTIME_RECORDED: '已记录实例，部署未确认', NOT_RECORDED: '实例版本未记录', AX_NOT_CAPTURED: '异步任务版本未记录', NOT_APPLICABLE: '控制节点' } as Record<string, string>)[state] || '未确认' }
function missingLabel(state: string) { return state === 'NOT_APPLICABLE' ? '控制节点不绑定运行实例。' : state === 'AX_NOT_CAPTURED' ? '本期不采集 AX 沙箱版本；原任务与执行证据仍可查看。' : '没有派发前的版本记录，可能尚未派发或属于旧记录。请结合步骤回执核对。' }
function backendLabel(value: string) { return ({ RUNTIME: '运行实例', AX: '异步沙箱', CONTROL: '流程控制' } as Record<string, string>)[value] || value }
function sourceLabel(value: string) { return ({ RUNNER: '配置式智能体', IMAGE: '镜像部署', SOURCE: '源码构建', TEMPLATE: '受控框架样例' } as Record<string, string>)[value] || value }
async function load() {
  clear()
  if (!allowed()) return
  const request = generation, user = permissions.state.user, { workflowId, runKey } = props.execution
  loading.value = true
  try {
    const response = await getExecutionConfiguration(workflowId, runKey)
    if (!allowed() || request !== generation || user !== permissions.state.user) return
    if (response.contract !== 'EXECUTION_CONFIGURATION_V1' || response.workflowId !== workflowId || response.runKey !== runKey || !Array.isArray(response.nodes) || response.nodes.length > 64) throw new Error('Configuration scope mismatch')
    const keys = new Set<string>()
    for (const [i, node] of response.nodes.entries()) {
      if (node.sequence !== i || !node.nodeKey || keys.has(node.nodeKey) || !['RUNTIME', 'AX', 'CONTROL'].includes(node.executionBackend) || !['DEPLOYMENT_RECORDED', 'RUNTIME_RECORDED', 'NOT_RECORDED', 'AX_NOT_CAPTURED', 'NOT_APPLICABLE'].includes(node.bindingState)) throw new Error('Invalid configuration node')
      if (node.runtime && (node.runtime.contract !== 'DISPATCH_BINDING_V1' || !node.runtime.instanceKey || !Number.isFinite(Date.parse(node.runtime.capturedAt)))) throw new Error('Invalid dispatch binding')
      if (['DEPLOYMENT_RECORDED', 'RUNTIME_RECORDED'].includes(node.bindingState) !== !!node.runtime || (node.runtime && node.executionBackend !== 'RUNTIME')) throw new Error('Invalid binding state')
      if (node.bindingState === 'DEPLOYMENT_RECORDED' && (!node.runtime?.deploymentKey || !Number.isSafeInteger(node.runtime.deploymentVersion) || node.runtime.deploymentVersion! < 1)) throw new Error('Invalid deployment version')
      keys.add(node.nodeKey)
    }
    report.value = response
  } catch { if (allowed() && request === generation && user === permissions.state.user) error.value = '原执行配置读取失败，请刷新重试。未使用当前配置替代。' }
  finally { if (request === generation) loading.value = false }
}
watch([() => props.execution, () => permissions.state.user, () => permissions.can('viewConsole')], () => { void load() }, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { alive = false; clear() })
</script>
<style scoped>
.execution-configuration{border-top:1px solid #dfe7ee;margin-top:24px;padding-top:18px;min-width:0}.execution-configuration header{display:flex;align-items:center;justify-content:space-between;gap:12px}.execution-configuration h3{font-size:16px;margin:0}.execution-configuration p{font-size:12px;color:#64748b;line-height:1.8;overflow-wrap:anywhere}.configuration-node{border:1px solid #e2e8f0;border-radius:6px;margin:10px 0;padding:12px;min-width:0}.configuration-node summary{cursor:pointer;line-height:1.7;font-size:13px;overflow-wrap:anywhere}.configuration-node summary small{display:block;margin-top:4px;color:#64748b;font-size:12px}.configuration-node summary:focus-visible{outline:2px solid #287eac;outline-offset:3px}.configuration-node dl{display:grid;grid-template-columns:128px minmax(0,1fr);gap:8px 12px;font-size:12px;line-height:1.7}.configuration-node dt{color:#64748b}.configuration-node dd{margin:0;overflow-wrap:anywhere}@media(max-width:700px){.configuration-node dl{grid-template-columns:minmax(0,1fr);gap:4px}.configuration-node dt{margin-top:8px}.execution-configuration header{flex-wrap:wrap}}
</style>
