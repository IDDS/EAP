<script setup lang="ts">
import { computed } from 'vue'
import type { FrameworkSampleCapability } from '@/api/substrate'
import type { FrameworkSampleKey } from '@/api/substrateDeploy'

const model = defineModel<FrameworkSampleKey>({ required: true })
const props = defineProps<{ samples: FrameworkSampleCapability[]; disabled: boolean }>()
const selected = computed(() => props.samples.find(sample => sample.key === model.value))
</script>

<template>
  <section class="framework-sample" aria-label="受控框架样例">
    <el-form-item label="框架样例">
      <el-select v-model="model" aria-label="框架样例" :disabled="disabled || !samples.length" class="sample-select">
        <el-option v-for="sample in samples" :key="sample.key" :value="sample.key"
          :label="`${sample.label} · ${sample.frameworkVersion}`" :disabled="!sample.available" />
      </el-select>
    </el-form-item>
    <p v-if="!selected" class="sample-notice" role="status">暂未取得样例目录，请刷新后重试。</p>
    <p v-else-if="!selected.available" class="sample-notice" role="status">{{ selected.reason || '该样例暂不可部署，请联系管理员。' }}</p>
    <div class="sample-description">
      <strong>协调、校验与临时代码执行</strong>
      <p>逻辑 Agent 在同一实例内协作完成受控求和任务。输入数字，查看结果、父子执行、工具调用和临时代码摘要。</p>
      <dl class="sample-facts">
        <div><dt>模型调用</dt><dd>无</dd></div>
        <div><dt>执行工具</dt><dd>受控求和</dd></div>
        <div><dt>任务结束</dt><dd>显式休眠</dd></div>
      </dl>
      <p class="sample-next">部署后，在工作流中配置求和工具任务及工具额度；到“运行与观测 → 执行关系”查看历史。</p>
      <p class="sample-scope">样例的代码和启动配置由平台固定，仅支持 gVisor。任意源码、模型生成代码、跨实例子 Agent 和自动回收尚未开放。</p>
    </div>
  </section>
</template>

<style scoped>
.framework-sample { margin-bottom: 20px; }
.sample-select { width: 100%; max-width: 360px; }
.sample-description { margin-left: 120px; padding: 18px 20px; border-left: 3px solid var(--el-color-primary); background: var(--el-fill-color-light); line-height: 1.65; }
.sample-description p { margin: 10px 0 0; }
.sample-facts { display: flex; flex-wrap: wrap; gap: 16px 32px; margin: 16px 0; }
.sample-facts dt { color: var(--el-text-color-secondary); font-size: 12px; }
.sample-facts dd { margin: 2px 0 0; font-weight: 600; }
.sample-notice { margin: 0 0 12px 120px; color: var(--el-text-color-secondary); }
.sample-scope { color: var(--el-text-color-secondary); font-size: 12px; }
@media (max-width: 640px) {
  .sample-description { margin-left: 0; padding: 14px; }
  .sample-notice { margin-left: 0; }
  .sample-facts { gap: 12px 24px; }
}
</style>
