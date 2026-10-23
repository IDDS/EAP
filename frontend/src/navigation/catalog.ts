/** Navigation, page naming and capability discovery share this catalog. No authorization rules live here. */
export interface NavigationItem { path: string; label: string; icon: string; description: string; keywords?: string }
export interface NavigationGroup { id: string; label: string; icon: string; description: string; standalone?: boolean; items: NavigationItem[] }
export const navigationShortcuts: NavigationItem[] = [
  {path:'/',label:'工作台',icon:'House',description:'处理待办，查看平台能力与运行概况。',keywords:'运营工作台 首页'},
  {path:'/command-center',label:'运行大屏',icon:'Monitor',description:'全屏查看运行态势与关键指标。',keywords:'指挥大屏'},
]
export const navigationGroups: NavigationGroup[] = [
  {id:'workspace-assets',label:'接入与资产',icon:'Cpu',description:'发现、登记并管理智能体',items:[
    {path:'/registry',label:'发现与接入',icon:'Search',description:'发现外部资产，核对能力并纳入平台管理。'},
    {path:'/agents',label:'智能体目录',icon:'Cpu',description:'管理智能体身份、生命周期、部署与实例。'},
  ]},
  {id:'workspace-execution',label:'编排与执行',icon:'Connection',description:'组织协作，执行并跟踪任务',items:[
    {path:'/task-workspace',label:'任务工作台',icon:'List',description:'按原执行编号查看任务步骤、结果、实例与观测证据。',keywords:'任务 会话 执行记录'},
    {path:'/workflow-builder',label:'工作流编排',icon:'Share',description:'设计智能体执行步骤，查看协作与执行过程。',keywords:'工作流自动化'},
    {path:'/scheduling',label:'调度与画像',icon:'Histogram',description:'查看申请人排队公平、等待老化与真实执行阶段。'},
    {path:'/orchestration',label:'团队与计划',icon:'UserFilled',description:'分配团队成员与计划步骤，跟踪协作结果。',keywords:'Agent 团队 多智能体'},
    {path:'/ax-tasks',label:'异步任务',icon:'VideoPlay',description:'提交异步任务，查看执行结果与沙箱状态。',keywords:'AX 任务执行'},
    {path:'/operations',label:'业务任务记录',icon:'List',description:'查看业务任务记录，确认问题是否解决。',keywords:'任务运行 运行任务'},
  ]},
  {id:'workspace-observation',label:'运行与观测',icon:'DataLine',description:'定位运行问题与调用链路',items:[
    {path:'/physical',label:'运行与遥测',icon:'DataLine',description:'查看运行实例、模型调用、调用链与运行信号。'},
    {path:'/observation-coverage',label:'证据覆盖与定位',icon:'CircleCheck',description:'按原操作核对可信分母、Trace、事件日志和费用。'},
    {path:'/substrate-monitor',label:'基础设施监控',icon:'Histogram',description:'查看执行资源池的容量、资源使用与沙箱状态。',keywords:'Substrate 监控 Worker'},
    {path:'/knowledge',label:'知识调用链路',icon:'Collection',description:'追踪输入、检索、处理与输出的知识流转。',keywords:'知识流转 知识界面'},
  ]},
  {id:'workspace-quality',label:'评估与优化',icon:'CircleCheck',description:'评估质量，形成反馈改进',items:[
    {path:'/evaluation',label:'质量评估',icon:'CircleCheck',description:'管理评估规则与数据集，查看结果和校准。',keywords:'评估与可靠性'},
    {path:'/learning',label:'反馈与改进',icon:'TrendCharts',description:'记录真实反馈，比较提示词版本、新输出和用量。',keywords:'自适应学习'},
    {path:'/team-evolutions',label:'团队优化结果',icon:'TrendCharts',description:'核对团队候选谱系、执行槽位、独立留出与全程费用。',keywords:'AgentEvolver 团队调优 提示词优化'},
  ]},
  {id:'workspace-governance',label:'治理与审计',icon:'Lock',description:'控制风险，保留责任与证据',items:[
    {path:'/governance',label:'策略与审批',icon:'ScaleToOriginal',description:'配置治理策略，处理待审批事项。'},
    {path:'/safety',label:'安全响应',icon:'Warning',description:'查看安全会话、事件和处置决定。'},
    {path:'/ethics',label:'责任与伦理',icon:'DocumentChecked',description:'查看责任归属、伦理规则与评审记录。'},
    {path:'/audit',label:'审计日志',icon:'Document',description:'查询操作记录，核对主体与执行证据。'},
  ]},
  {id:'workspace-value',label:'成本与价值',icon:'Money',description:'衡量投入与业务成果',standalone:true,items:[
    {path:'/management',label:'成本与价值',icon:'Money',description:'分析调用成本、使用情况和已确认的业务价值。'},
  ]},
]
export const navigationItems: NavigationItem[] = [...navigationShortcuts,...navigationGroups.flatMap(group=>group.items)]
export function navigationForPath(path:string) {
  const activePath=path.startsWith('/agents/')?'/agents':path
  const item=navigationItems.find(item=>item.path===activePath)
  const group=navigationGroups.find(group=>group.items.some(item=>item.path===activePath))
  return {item,group,activePath,openGroups:group&&!group.standalone?[group.id]:[]}
}
export function searchNavigation(term:string): NavigationItem[] {
  const query=term.trim().toLocaleLowerCase()
  return query?navigationItems.filter(item=>`${item.label} ${item.description} ${item.keywords||''}`.toLocaleLowerCase().includes(query)):[]
}
