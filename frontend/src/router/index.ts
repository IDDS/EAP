import { createRouter, createWebHistory } from 'vue-router'
import { navigationItems } from '@/navigation/catalog'
const DashboardView = () => import('@/views/DashboardView.vue')
const AgentsView = () => import('@/views/AgentsView.vue')
const AgentRegistryView = () => import('@/views/AgentRegistryView.vue')
const AuditView = () => import('@/views/AuditView.vue')
const EthicsView = () => import('@/views/EthicsView.vue')
const GovernanceView = () => import('@/views/GovernanceView.vue')
const LearningView = () => import('@/views/LearningView.vue')
const OperationsView = () => import('@/views/OperationsView.vue')
const OrchestrationView = () => import('@/views/OrchestrationView.vue')
const SafetyView = () => import('@/views/SafetyView.vue')
const WorkflowBuilderView = () => import('@/views/WorkflowBuilderView.vue')
const PhysicalView = () => import('@/views/PhysicalView.vue')
const KnowledgeView = () => import('@/views/KnowledgeView.vue')
const ManagementView = () => import('@/views/ManagementView.vue')
const AgentDetailView = () => import('@/views/AgentDetailView.vue')
const EvaluationView = () => import('@/views/EvaluationView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/auth/callback', component: () => import('@/views/AuthCallbackView.vue'), meta: { title: '企业身份认证', standalone: true } },
    { path: '/command-center', component: () => import('@/views/CommandCenterView.vue'), meta: { title: '运行指挥大屏', standalone: true } },
    { path: '/', component: DashboardView, meta: { title: '运营工作台' } },
    { path: '/agents', component: AgentsView, meta: { title: '智能体目录' } },
    { path: '/agents/:id', component: AgentDetailView, meta: { title: '智能体详情' } },
    { path: '/registry', component: AgentRegistryView, meta: { title: '发现与接入' } },
    { path: '/substrate-monitor', component: () => import('@/views/SubstrateMonitorView.vue'), meta: { title: 'Substrate 运行监控' } },
    { path: '/physical', component: PhysicalView, meta: { title: '运行与观测' } },
    { path: '/observation-coverage', component: () => import('@/views/ObservationCoverageView.vue'), meta: { title: '证据覆盖与定位' } },
    { path: '/knowledge', component: KnowledgeView, meta: { title: '知识流转' } },
    { path: '/management', component: ManagementView, meta: { title: '成本与价值' } },
    { path: '/operations', component: OperationsView, meta: { title: '任务运行' } },
    { path: '/ax-tasks', component: () => import('@/views/AxTasksView.vue'), meta: { title: 'AX 任务执行' } },
    { path: '/orchestration', component: OrchestrationView, meta: { title: 'Agent 团队' } },
    { path: '/scheduling', component: () => import('@/views/SchedulingView.vue'), meta: { title: '调度与画像' } },
    { path: '/task-workspace', component: () => import('@/views/TaskWorkspaceView.vue'), meta: { title: '任务工作台' } },
    { path: '/workflow-builder', component: WorkflowBuilderView, meta: { title: '工作流自动化' } },
    { path: '/learning', component: LearningView, meta: { title: '自适应学习' } },
    { path: '/team-evolutions', component: () => import('@/views/TeamEvolutionView.vue'), meta: { title: '团队优化结果' } },
    { path: '/evaluation', component: EvaluationView, meta: { title: '评估与可靠性' } },
    { path: '/governance', component: GovernanceView, meta: { title: '策略与审批' } },
    { path: '/safety', component: SafetyView, meta: { title: '安全响应' } },
    { path: '/ethics', component: EthicsView, meta: { title: '责任与伦理' } },
    { path: '/audit', component: AuditView, meta: { title: '审计日志' } },
  ].map(route => ({ ...route, meta: { ...route.meta, title: navigationItems.find(item => item.path === route.path)?.label || route.meta.title } })),
})

export default router
