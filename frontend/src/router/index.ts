import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '@/views/DashboardView.vue'
import AgentsView from '@/views/AgentsView.vue'
import AgentRegistryView from '@/views/AgentRegistryView.vue'
import AuditView from '@/views/AuditView.vue'
import EthicsView from '@/views/EthicsView.vue'
import GovernanceView from '@/views/GovernanceView.vue'
import LearningView from '@/views/LearningView.vue'
import OperationsView from '@/views/OperationsView.vue'
import OrchestrationView from '@/views/OrchestrationView.vue'
import SafetyView from '@/views/SafetyView.vue'
import WorkflowBuilderView from '@/views/WorkflowBuilderView.vue'
import PhysicalView from '@/views/PhysicalView.vue'
import KnowledgeView from '@/views/KnowledgeView.vue'
import ManagementView from '@/views/ManagementView.vue'
import AgentDetailView from '@/views/AgentDetailView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: DashboardView, meta: { title: '运营工作台' } },
    { path: '/agents', component: AgentsView, meta: { title: 'Agent 目录' } },
    { path: '/agents/:id', component: AgentDetailView, meta: { title: 'Agent 详情' } },
    { path: '/registry', component: AgentRegistryView, meta: { title: '发现与接入' } },
    { path: '/physical', component: PhysicalView, meta: { title: '运行与遥测' } },
    { path: '/knowledge', component: KnowledgeView, meta: { title: '知识流转' } },
    { path: '/management', component: ManagementView, meta: { title: '成本与价值' } },
    { path: '/operations', component: OperationsView, meta: { title: '任务运行' } },
    { path: '/orchestration', component: OrchestrationView, meta: { title: 'Agent 团队' } },
    { path: '/workflow-builder', component: WorkflowBuilderView, meta: { title: '工作流自动化' } },
    { path: '/learning', component: LearningView, meta: { title: '自适应学习' } },
    { path: '/governance', component: GovernanceView, meta: { title: '策略与审批' } },
    { path: '/safety', component: SafetyView, meta: { title: '多智能体安全' } },
    { path: '/ethics', component: EthicsView, meta: { title: '伦理安全' } },
    { path: '/audit', component: AuditView, meta: { title: '审计日志' } },
  ],
})

export default router
