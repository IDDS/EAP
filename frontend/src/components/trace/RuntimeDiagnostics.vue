<template>
  <section v-if="report" class="runtime-diagnostic" aria-label="运行阶段诊断">
    <h4>运行阶段诊断</h4><p>最后记录阶段：<strong>{{ report.stage }}</strong> · {{ report.deadline === true ? '已触及执行截止时间' : report.deadline === false ? '未报告触及截止时间' : '截止时间状态未记录' }}</p>
    <dl><template v-for="item in report.timings" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ item.value === null ? '未记录' : `${item.value.toLocaleString()} ms` }}</dd></template></dl>
    <details v-if="report.startup.length"><summary>启动细分计时</summary><dl><template v-for="item in report.startup" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ item.value!.toLocaleString() }} ms</dd></template></dl><p>计时包含嵌套区间和等待，不可相加为总耗时，也不代表 CPU 时间。</p></details>
    <p v-else>没有可展示的完整启动细分，缺失计时不按零处理。</p>
    <p>阶段用于定位等待位置；原任务回执与工具效果仍需分别核对。请从任务工作台打开原执行，不要因超时重复派发。</p>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { runtimeDiagnostics } from './runtime-diagnostics'
const props = defineProps<{ attributesJson?: string }>()
const report = computed(() => runtimeDiagnostics(props.attributesJson))
</script>
<style scoped>
.runtime-diagnostic{border-top:1px solid #d4e0ec;margin-top:16px;padding-top:12px}.runtime-diagnostic h4{font-size:14px;margin:0 0 10px}.runtime-diagnostic p{font-size:12px;line-height:1.7;color:#60758b}.runtime-diagnostic dl{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:9px;font-size:12px}.runtime-diagnostic dd{margin:0;font-variant-numeric:tabular-nums}.runtime-diagnostic summary{cursor:pointer;font-size:13px;font-weight:600;padding:10px 0}.runtime-diagnostic summary:focus-visible{outline:2px solid #287eac}
</style>
