<script setup lang="ts">
import { LteCard } from '@adminlte/vue'
import { ArrowRight } from '@element-plus/icons-vue'

defineProps<{ agentCount?: number; openItemCount?: number }>()
const emit = defineEmits<{ openQueue: [] }>()
const steps = [
  { title: '准备智能体', description: '选择执行成员，检查部署与接入。', action: '查看智能体', path: '/agents' },
  { title: '安排协作', description: '建立团队，安排分工与执行顺序。', action: '打开团队与计划', path: '/orchestration' },
  { title: '跟进执行', description: '查看运行到哪一步，以及实际输出。', action: '查看执行记录', path: '/task-workspace' },
  { title: '核对结果', description: '检查评估结果，再确认业务问题是否解决。', action: '查看质量评估', path: '/evaluation?section=results' },
]
</script>

<template>
  <section class="workspace-start" aria-label="开始处理工作">
    <LteCard class="start-card journey-card">
      <template #header><div class="start-card-heading"><h3>完成一次智能体协作</h3><span>从准备成员，到执行与结果核对</span></div></template>
      <ol class="journey-steps">
        <li v-for="(step, index) in steps" :key="step.path" class="journey-step">
          <span class="journey-order" aria-hidden="true">{{ index + 1 }}</span>
          <h4>{{ step.title }}</h4><p>{{ step.description }}</p>
          <router-link :to="step.path">{{ step.action }}<el-icon aria-hidden="true"><ArrowRight /></el-icon></router-link>
        </li>
      </ol>
      <template #footer><p class="journey-alternative">需要图形化设计流程？<router-link to="/workflow-builder">打开工作流编排</router-link></p></template>
    </LteCard>

    <div class="workbench-entry-grid">
      <LteCard class="start-card">
        <template #header><div class="start-card-heading"><h3>查看正在进行的工作</h3></div></template>
        <div class="workbench-entry-body"><p>从执行记录找到任务，查看步骤、结果与关联证据。</p><router-link to="/task-workspace" class="entry-action">打开执行记录<el-icon aria-hidden="true"><ArrowRight /></el-icon></router-link></div>
      </LteCard>
      <LteCard class="start-card">
        <template #header><div class="start-card-heading"><h3>排查运行问题</h3></div></template>
        <div class="workbench-entry-body"><p>先确认任务状态，再查看真实 Trace、模型和工具调用。</p><router-link :to="{ path: '/physical', query: { section: 'trace' } }" class="entry-action">查看调用链<el-icon aria-hidden="true"><ArrowRight /></el-icon></router-link></div>
      </LteCard>
    </div>
    <div class="workbench-context" aria-label="当前组织概况">
      <router-link to="/agents"><span>已登记智能体</span><strong>{{ agentCount ?? '—' }}</strong></router-link>
      <button type="button" @click="emit('openQueue')"><span>当前组织待办</span><strong>{{ openItemCount ?? '—' }}</strong><span class="context-action">查看待办</span></button>
      <router-link :to="{ path: '/', query: { section: 'metrics' } }" class="context-metrics">查看运行指标<el-icon aria-hidden="true"><ArrowRight /></el-icon></router-link>
    </div>
  </section>
</template>

<style scoped>
.workspace-start { display: grid; gap: 20px; min-width: 0; }
.start-card { min-width: 0; margin: 0; border: 1px solid var(--console-line); border-radius: 4px; background: var(--console-surface); box-shadow: 0 1px 2px #19324608; }
.start-card :deep(.card-header) { padding: 18px 22px; border-bottom: 1px solid var(--console-line); }
.start-card :deep(.card-body) { padding: 0; }.start-card :deep(.card-footer) { padding: 14px 22px; border-top: 1px solid var(--console-line); background: #fafbfc; }
.start-card-heading { display: flex; align-items: baseline; justify-content: space-between; flex-wrap: wrap; gap: 8px 20px; }
.start-card-heading h3 { margin: 0; font-size: 16px; font-weight: 650; }.start-card-heading > span { color: var(--console-muted); font-size: 13px; }
.journey-steps { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); list-style: none; padding: 0; margin: 0; }
.journey-step { min-width: 0; padding: 24px 22px; border-right: 1px solid var(--console-line); display: flex; flex-direction: column; align-items: flex-start; }
.journey-step:last-child { border-right: 0; }.journey-order { display: inline-flex; justify-content: center; align-items: center; width: 30px; height: 30px; border-radius: 50%; color: var(--console-accent); background: var(--console-accent-soft); font-size: 14px; font-weight: 650; }
.journey-step h4 { margin: 16px 0 8px; font-size: 16px; }.journey-step p { margin: 0 0 20px; color: var(--console-muted); font-size: 13px; line-height: 1.8; }
.journey-step a, .entry-action { display: inline-flex; align-items: center; gap: 10px; color: var(--el-color-primary-dark-2); font-size: 13px; font-weight: 600; text-decoration: none; padding: 6px 0; }.journey-step a { margin-top: auto; }
.journey-alternative { margin: 0; color: var(--console-muted); font-size: 13px; line-height: 1.8; }.journey-alternative a { margin-left: 8px; color: var(--el-color-primary-dark-2); text-decoration: none; }
.workbench-entry-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 20px; }
.workbench-entry-body { padding: 18px 22px; }.workbench-entry-body p { margin: 0 0 12px; color: var(--console-muted); font-size: 13px; line-height: 1.8; }
.workbench-context { display: flex; align-items: stretch; flex-wrap: wrap; border: 1px solid var(--console-line); border-radius: 4px; background: #fff; }
.workbench-context > :is(a,button) { display: flex; align-items: center; gap: 12px; padding: 16px 22px; border: 0; border-right: 1px solid var(--console-line); background: transparent; text-decoration: none; color: var(--console-muted); font: inherit; font-size: 13px; cursor: pointer; }
.workbench-context strong { color: var(--console-ink); font-size: 20px; font-weight: 650; }.context-action { color: var(--el-color-primary-dark-2); font-size: 12px; }.workbench-context > .context-metrics { margin-left: auto; border-right: 0; color: var(--el-color-primary-dark-2); }
.workspace-start a:hover { text-decoration: underline; }.workspace-start :is(a,button):focus-visible { outline: 2px solid var(--console-accent); outline-offset: 3px; }
@media (max-width: 1150px) { .journey-steps { grid-template-columns: repeat(2,minmax(0,1fr)); }.journey-step:nth-child(2) { border-right: 0; }.journey-step:nth-child(-n+2) { border-bottom: 1px solid var(--console-line); } }
@media (max-width: 600px) { .workspace-start { gap: 16px; }.journey-steps, .workbench-entry-grid { grid-template-columns: minmax(0,1fr); }.journey-step { border-right: 0; border-bottom: 1px solid var(--console-line); padding: 20px; }.journey-step:last-child { border-bottom: 0; }.journey-step h4 { margin-top: 12px; }.start-card :deep(.card-header), .workbench-entry-body { padding: 16px 20px; }.workbench-context { display: grid; }.workbench-context > :is(a,button) { border-right: 0; border-bottom: 1px solid var(--console-line); }.workbench-context > .context-metrics { margin-left: 0; border-bottom: 0; }.context-action { margin-left: auto; } }
</style>
