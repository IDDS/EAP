/** Shared information architecture. Authorization remains in /platform/me. */
export interface NavigationItem { path: string; label: string; icon: string; description: string; keywords?: string; query?: Record<string, string> }
export interface NavigationGroup { id: string; label: string; icon: string; description: string; items: NavigationItem[] }
export const navigationShortcuts: NavigationItem[] = [
  {path:'/',label:'工作台',icon:'House',description:'选择工作入口，处理待办，查看运行概况。',keywords:'运营工作台 首页'},
  {path:'/command-center',label:'运行大屏',icon:'Monitor',description:'全屏查看运行态势与关键指标。',keywords:'指挥大屏'},
]
export const navigationGroups: NavigationGroup[] = [
  {id:'workspace-assets',label:'智能体',icon:'Cpu',description:'选择智能体，管理接入与部署。',items:[
    {path:'/agents',label:'智能体目录',icon:'Cpu',description:'查看和管理智能体，配置身份、部署与实例。'},
    {path:'/registry',label:'发现与接入',icon:'Search',description:'发现已有智能体，核对能力后纳入平台。'},
  ]},
  {id:'workspace-design',label:'协作设计',icon:'Connection',description:'安排成员分工，设计执行步骤。',items:[
    {path:'/orchestration',label:'团队与计划',icon:'UserFilled',description:'组织团队，为一次协作安排有序步骤和执行成员。',keywords:'Agent 团队 多智能体 编排与执行'},
    {path:'/workflow-builder',label:'工作流编排',icon:'Share',description:'用可视化流程组织节点、条件和交接关系。',keywords:'工作流自动化 编排与执行'},
  ]},
  {id:'workspace-execution',label:'任务与结果',icon:'VideoPlay',description:'跟进执行，阅读结果，确认业务效果。',items:[
    {path:'/task-workspace',label:'执行记录',icon:'List',description:'按一次真实执行查看步骤、结果和关联证据。',keywords:'任务工作台 任务 会话 编排与执行'},
    {path:'/ax-tasks',label:'沙箱任务',icon:'VideoPlay',description:'提交独立沙箱任务，查看执行结果和资源状态。',keywords:'AX 任务执行 异步任务'},
    {path:'/scheduling',label:'调度队列',icon:'Histogram',description:'查看排队、等待时间和任务执行阶段。',keywords:'调度与画像 公平'},
    {path:'/operations',label:'业务验收',icon:'DocumentChecked',description:'阅读业务任务记录，确认业务问题是否解决。',keywords:'业务任务记录 任务运行 运行任务'},
  ]},
  {id:'workspace-observation',label:'观测与排障',icon:'DataLine',description:'从异常找到调用、执行关系和运行证据。',items:[
    {path:'/physical',label:'调用与链路',icon:'DataLine',description:'查看运行实例、模型与工具调用、Trace 和执行关系。',keywords:'运行与观测 运行与遥测 执行关系 eBPF MCP'},
    {path:'/observation-coverage',label:'证据覆盖',icon:'CircleCheck',description:'核对观测覆盖、原操作证据与缺失环节。',keywords:'证据覆盖与定位'},
    {path:'/knowledge',label:'知识链路',icon:'Collection',description:'追踪知识检索、处理与输出的流转过程。',keywords:'知识流转 知识界面 知识调用链路 RAG'},
    {path:'/substrate-monitor',label:'基础设施',icon:'Histogram',description:'查看资源池容量、资源使用与沙箱运行状态。',keywords:'Substrate 监控 Worker 基础设施监控'},
  ]},
  {id:'workspace-quality',label:'评估与优化',icon:'TrendCharts',description:'评估质量，改进协作，衡量投入与成果。',items:[
    {path:'/evaluation',label:'质量评估',icon:'CircleCheck',description:'配置评估规则和数据集，比较结果与校准。',keywords:'评估与可靠性'},
    {path:'/learning',label:'反馈与改进',icon:'TrendCharts',description:'根据真实反馈比较提示词、新输出和用量。',keywords:'自适应学习'},
    {path:'/team-evolutions',label:'团队优化',icon:'TrendCharts',description:'比较团队候选、独立验证结果和全程费用。',keywords:'团队优化结果 AgentEvolver 团队调优 提示词优化'},
    {path:'/management',label:'成本与价值',icon:'Money',description:'核对调用成本、使用情况和已确认的业务价值。'},
  ]},
  {id:'workspace-governance',label:'治理与安全',icon:'Lock',description:'控制执行边界，处理风险，核对责任。',items:[
    {path:'/governance',label:'策略与审批',icon:'ScaleToOriginal',description:'设置执行规则，处理需要人工决定的审批。'},
    {path:'/safety',label:'安全响应',icon:'Warning',description:'调查安全事件，记录处置决定。'},
    {path:'/ethics',label:'责任与伦理',icon:'DocumentChecked',description:'核对责任归属、伦理规则与评审记录。'},
    {path:'/audit',label:'审计日志',icon:'Document',description:'查询谁在何时做了什么，回查操作证据。',keywords:'治理与审计'},
  ]},
]
export const navigationItems: NavigationItem[] = [...navigationShortcuts,...navigationGroups.flatMap(group=>group.items)]
export function navigationForPath(path:string) {
  const activePath=path.startsWith('/agents/')?'/agents':path
  const item=navigationItems.find(item=>item.path===activePath)
  const group=navigationGroups.find(group=>group.items.some(item=>item.path===activePath))
  return {item,group,activePath}
}
export function searchNavigation(term:string): NavigationItem[] {
  const query=term.trim().toLocaleLowerCase()
  return query?navigationItems.filter(item=>`${item.label} ${item.description} ${item.keywords||''}`.toLocaleLowerCase().includes(query)):[]
}


export interface PageSection { value: string; label: string }
/** Page sections are addressable from the outer navigation, including old URLs. */
export const pageSections: Record<string, readonly PageSection[]> = {
  '/': [
    { value: 'start', label: '开始工作' }, { value: 'queue', label: '待办事项' },
    { value: 'metrics', label: '运行指标' }, { value: 'network', label: '协作拓扑' },
  ],
  '/registry': [
    { value: 'discovery', label: '发现总览' }, { value: 'probes', label: '发现探针' },
    { value: 'identity', label: '身份关联' }, { value: 'assets', label: '资产清单' },
    { value: 'runtime', label: '运行时注册' }, { value: 'environment', label: '运行环境发现' },
  ],
  '/physical': [
    { value: 'overview', label: '运行总览' }, { value: 'assets', label: '运行实例' },
    { value: 'trace', label: 'Trace 链路' }, { value: 'models', label: '模型调用与用量' },
    { value: 'tools', label: '工具与 MCP 调用' }, { value: 'executions', label: '执行关系' },
    { value: 'ingest', label: '调用接入' },
  ],
  '/knowledge': [{ value: 'overview', label: '知识总览' }, { value: 'trace', label: '过程追踪' }, { value: 'ingest', label: '交互接入' }],
  '/management': [
    { value: 'overview', label: '价值总览' }, { value: 'attribution', label: '投入产出' },
    { value: 'connectors', label: '业务连接器' }, { value: 'value', label: '价值事件' }, { value: 'budget', label: '预算与准入' },
  ],
  '/evaluation': [
    { value: 'overview', label: '评估总览' }, { value: 'results', label: '评估结果' },
    { value: 'datasets', label: '数据集与校准' }, { value: 'rules', label: '评估规则' },
  ],
  '/governance': [
    { value: 'overview', label: '治理总览' }, { value: 'preflight', label: '实时预检与审批' },
    { value: 'audit', label: '治理事件' }, { value: 'policies', label: '治理策略' },
  ],
  '/agents/:id': [
    { value: 'overview', label: '智能体概况' }, { value: 'runtime', label: '实例与能力' },
    { value: 'deploy', label: '部署与试运行' }, { value: 'identity', label: '身份与归属' },
  ],
}
export function pageNavigationForPath(path: string, query: Record<string, unknown> = {}) {
  const detail = /^\/agents\/[^/]+$/.test(path)
  const items = pageSections[detail ? '/agents/:id' : path] || []
  const parameter = detail ? 'tab' : 'section'
  let fallback = items[0]?.value
  if (path === '/physical' && (query.traceId || query.eventKey)) fallback = 'trace'
  if (path === '/governance') {
    if (query.policyId) fallback = 'policies'
    else if (query.eventKey || query.agentId) fallback = 'audit'
  }
  if (path === '/evaluation' && (query.agentId || query.traceId)) fallback = 'results'
  const active = items.find(item => item.value === query[parameter])?.value || fallback
  return { items, parameter, active, section: items.find(item => item.value === active), detail }
}
export function legacyAgentDestination(id: string, tab: unknown) {
  const destinations: Record<string, { path: string; section: string }> = {
    trace: { path: '/physical', section: 'trace' }, models: { path: '/physical', section: 'models' },
    tools: { path: '/physical', section: 'tools' }, quality: { path: '/evaluation', section: 'results' },
    governance: { path: '/governance', section: 'audit' },
  }
  const destination = typeof tab === 'string' ? destinations[tab] : undefined
  return destination ? { path: destination.path, query: { section: destination.section, agentId: id } } : undefined
}
export function searchPageSections(term: string): NavigationItem[] {
  const query = term.trim().toLocaleLowerCase()
  if (!query) return []
  return navigationItems.flatMap(item => (pageSections[item.path] || [])
    .filter(section => section.label.toLocaleLowerCase().includes(query))
    .map(section => ({ ...item, label: section.label, description: item.label, query: { section: section.value } })))
}

export interface NavigationDestination extends NavigationItem { key: string; section?: string; context?: string }
/** Flatten page sections into workspace destinations: no third-level tree. */
export function workspaceDestinations(group: NavigationGroup, path: string, query: Record<string, unknown> = {}): NavigationDestination[] {
  const location = navigationForPath(path)
  const page = pageNavigationForPath(path, query)
  return group.items.flatMap(item => {
    if (item.path === '/agents' && page.detail) {
      return [
        { ...item, key: item.path },
        ...page.items.map(section => ({ ...item, key: `${path}:${section.value}`, path,
          label: section.label, section: section.value, context: `当前智能体 #${path.split('/').pop()}`,
          query: { ...query, tab: section.value } as Record<string, string> })),
      ]
    }
    const sections = pageSections[item.path]
    if (!sections?.length) return [{ ...item, key: item.path }]
    return sections.map(section => ({ ...item, key: `${item.path}:${section.value}`,
      label: section.label, section: section.value, context: item.label,
      query: { ...(location.activePath === item.path ? query : {}), section: section.value } as Record<string, string> }))
  })
}

const sectionDescriptions: Record<string, Record<string, string>> = {
  '/': {
    start: '搭建协作、跟进执行、处理问题。从这里进入今天的工作。',
    queue: '按优先级处理当前组织的待办事项，明确责任和下一步。',
    metrics: '查看所选组织和时间窗口内的运行指标，区分已有记录与观测缺口。',
    network: '查看智能体之间已记录的协作关系，定位相关成员。',
  },
  '/registry': {
    discovery: '发现已有智能体与 MCP 服务，核对协议和身份后接入。',
    probes: '查看端点探针的上报状态与发现结果。',
    identity: '核对发现端点与平台智能体之间的身份关联。',
    assets: '查看已经发现的技能、插件和服务资产。',
    runtime: '登记外部运行实例，明确其归属和连接方式。',
    environment: '查询托管运行环境中的实例，复核后纳入平台。',
  },
  '/physical': {
    overview: '查看运行健康、调用量与当前观测状态。',
    assets: '查看智能体的运行实例、端点和在线状态。',
    trace: '沿真实 Trace 查看调用顺序、耗时与关联证据。',
    models: '查看模型请求、Token 用量与延迟。',
    tools: '查看工具与 MCP 的调用记录、执行结果和关联智能体。',
    executions: '追踪主智能体、子智能体、短生命周期工具与动态代码的执行关系。',
    ingest: '配置调用观测接入，检查数据是否实际到达平台。',
  },
  '/knowledge': {
    overview: '查看知识输入、采用和复用的整体情况。',
    trace: '沿一次交互核对知识检索、处理和输出过程。',
    ingest: '登记知识交互，建立输入、处理与输出之间的关联。',
  },
  '/evaluation': {
    overview: '查看评估覆盖、通过率与需要跟进的质量问题。',
    results: '查看真实评估结果，并回到原始执行证据核对。',
    datasets: '管理回归样本、数据集与校准记录。',
    rules: '配置质量评估规则，明确通过条件与适用范围。',
  },
  '/management': {
    overview: '综合查看调用成本、任务成效和已确认的业务价值。',
    attribution: '按当前范围核对成本归属与投入产出。',
    connectors: '配置业务数据连接器，明确价值数据的来源。',
    value: '登记并核对业务方确认的价值事件。',
    budget: '查看预算与准入限制，明确下一次调用的可用额度。',
  },
  '/governance': {
    overview: '查看当前治理状态、风险分类与策略执行情况。',
    preflight: '核对预检决策与审批上下文，处理需要人工决定的请求。',
    audit: '查询治理事件，关联智能体和调用证据；此处不保存敏感正文。',
    policies: '配置执行规则与适用范围，查看策略是否启用。',
  },
  '/agents/:id': {
    overview: '查看当前智能体的身份、运行状态与待办事项。',
    runtime: '核对当前智能体的运行实例和已登记能力。',
    deploy: '配置部署、查看进度，并在明确权限和范围后试运行。',
    identity: '管理智能体身份、责任归属与业务属性。',
  },
}

/** Page titles, sidebar labels and deep links share one vocabulary. */
export function pageHeadingForPath(path: string, query: Record<string, unknown> = {}) {
  const location = navigationForPath(path)
  const page = pageNavigationForPath(path, query)
  const key = page.detail ? '/agents/:id' : path
  return {
    title: path === '/' && page.active === 'start' ? '工作台' : page.section?.label || location.item?.label || 'EAP',
    description: sectionDescriptions[key]?.[page.active || ''] || location.item?.description || '',
  }
}
