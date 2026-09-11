# EAP 前端与运行指挥大屏组件选型深度调研

调研日期：2026-09-11。适用基线：EAP 前端 0.3.1，Vue 3、Element Plus、ECharts 5；以企业私有化部署、中文界面、明亮清晰的大屏和可追溯运行为目标。版本为访问当日的公开注册表快照，不是建议统一升级到的版本。

## 1. 选型结论

建议采用“现有通用界面 + 专用可视化组件”的渐进路线：保留 Element Plus 和 ECharts；在独立大屏验证 AdminLTE 官方 Vue 组件；用 D3 的缩放和时间选择模块完善专属交互；用 Vue Flow 改善工作流编辑；在拓扑复杂度确实上升时引入 G6。Unovis 是值得对照验证的 Vue 可视化候选，但不宜与多个通用图表库同时铺开。

目前最值得改进的不是图表数量，而是三个具体能力：拓扑能稳定定位和追踪对象；运行过程能按真实时间和状态解释；统计图能筛选、联动、钻取到证据。AdminLTE 更适合解决信息组织与界面部件，D3 更适合解决自定义图形和交互，两者不能互相替代。

**重要的新发现：AdminLTE 已有官方 Vue 3 包 `@adminlte/vue`。** 官方文档明确列出 `LteCard`、`LteSmallBox`、`LteInfoBox`，采用组合式能力而非 jQuery；本次 npm 查询确认 0.7.0 已发布，Vue peer 为 `^3.5.0`。这使真正接入官方组件成为可行选项，但其版本仍在 0.x，且样式基于 Bootstrap，需要先验证与 Element Plus 共存的行为。[1][2][R]

| 决策层 | 首选 | 对照候选 | 对 EAP 的决策 |
|---|---|---|---|
| 表单、弹窗、导航 | Element Plus | AdminLTE Vue 布局组件 | 保留现有主控制台；大屏局部验证 AdminLTE |
| KPI、信息盒、面板 | AdminLTE Vue 的三类部件 | 当前 Vue 组件统一封装 | 优先 PoC，重点验证样式和主题影响 |
| 趋势、排名、分布 | ECharts | Unovis、uPlot | 优先深化现有 ECharts |
| 特殊缩放、框选、层级图 | D3 按需模块 | Unovis Vue | D3 管特定交互，不重写全部图表 |
| 工作流编辑 | Vue Flow | X6、LogicFlow | 首选 Vue Flow 的独立编辑器 PoC |
| 运行关系拓扑 | 小图延续 SVG + D3；复杂图 G6 | Cytoscape.js、Sigma.js | 按规模和分析任务升级 |
| Trace | D3 时间轴 + 现有详情面板 | vis-timeline、d3-flame-graph | 先明确时间字段，再做瀑布图 |
| 自定义看板布局 | 固定 CSS Grid | GridStack、Grid Layout Plus | 展示模式保持稳定；编辑布局后置 |
| 大量审计数据 | 服务端分页 + 现有表格 | TanStack、AG Grid | 等复杂分析需求明确后引入 |

以上为结合 EAP 代码的工程判断，不是性能测试排名。第一轮应同时比较少量独立候选，然后收敛依赖；不建议把表中所有库装进产品。

## 2. 现状核对与证据边界

`frontend/package.json` 声明 Vue `^3.5.13`、Element Plus `^2.9.3`、ECharts `^5.6.0`；其中没有 AdminLTE 或 D3。声明范围不等于 lockfile 的精确安装版本。`TrendChart.vue` 已使用 ECharts 的按需图表模块，而大屏拓扑、过程条和部分状态展示主要由 Vue、SVG、CSS 自行实现。因此，“AdminLTE 和 D3 实际引入不多”这一观察成立。[L1]

当前大屏已经有数据源状态、刷新、暂停、对象搜索、拓扑节点选择、分页和全屏等交互。下一轮迁移必须保留这些能力，尤其不能用新图形覆盖掉数据过期、请求失败、跨页关系等提示。组件迁移的收益应以操作改善和代码维护成本衡量，不能只凭截图更加复杂来判断。[L2]

本报告使用三类证据：官方文档说明功能，官方仓库和 npm 发布元数据核对包与许可，EAP 本地代码验证适配场景。官方性能宣传仍是供应方陈述；没有在 EAP 中运行的性能结果均列为待测。npm 近期发布只证明有发布活动，不代表生产成熟度、安全审计结果或长期维护承诺。

本次研究覆盖 35 个包，包含同一产品的配套包及 D3 子模块，并非 35 套独立 UI 框架。原始结果保存在 `npm-metadata.json`；其中取数失败明确保留错误，未补造版本。研究没有安装新业务依赖，也没有改变部署状态。

## 3. AdminLTE：从借鉴模板走向实际组件接入

### 3.1 适合引入的具体部件

建议首先验证 `LteSmallBox` 表示全局 KPI，`LteInfoBox` 表示平台状态与数据源，`LteCard` 承载可折叠面板。这样能在用户最关注的大屏上形成可识别的 AdminLTE 组件使用，又能把业务数据与模板结构分离。不要仅把 CSS 类名改成类似 AdminLTE 就宣称完成集成。[1]

布局壳、全局菜单和主题控制应单独评估。官方主题能力会操作 `<html>` 的 `data-bs-theme` 并持久化主题；这不是天然局限于某个 Vue 组件的样式。若直接在现有应用启动入口导入全部样式，可能影响字体、基础元素、层级和弹出层；是否实际冲突必须通过页面比对确定，不能预设“加 scoped 就解决”。[1][2]

### 3.2 集成方式比较

| 方式 | 收益 | 代价 | 判断 |
|---|---|---|---|
| 主控制台全面换 AdminLTE | 统一使用一套模板体系 | 表单、导航、交互和样式回归范围大 | 当前不建议 |
| 大屏独立入口使用官方组件 | 可控制样式加载范围，价值直观 | 需明确路由/文档级隔离和公共状态接口 | 首选验证方向 |
| 同一应用局部导入组件与样式 | 开发改动相对集中 | CSS 仍可能全局影响；路由卸载不保证撤销样式 | 可 PoC，不能直接认定隔离成功 |
| 仅借鉴设计，自有组件实现 | 保持现有依赖与主题 | 不属于实际 AdminLTE 组件集成 | 共存验证失败时的备选 |

这里的“独立入口”可以是独立 HTML 构建入口，不必拆成微服务。iframe 能提供更强文档隔离，但会增加主题、尺寸、焦点和导航联动成本，暂不作为默认方案。

官方把图表、富文本、日历等包装器放在独立的 `/plugins` 入口。npm 元数据确认这些外围库是 optional peers，不应把它们全部当成必须安装的依赖。官方安装说明仍建议引入 Bootstrap，并说明部分交互使用它的 JS。因此，“没有强制运行依赖”和“完整页面无需 Bootstrap”不是同一结论。[2][R]

**风险判断：中等。** Vue 主版本匹配有直接证据，CSS 共存和组件按需导出的实际打包效果尚无 EAP 实测证据。0.x 应锁定精确版本，升级前比较组件 API 与视觉快照。

## 4. 统计图表：首先用好 ECharts，再考虑 Unovis

EAP 已经承担了 ECharts 的接入成本。趋势、排名、分类对比等标准统计图优先沿用它；把统一单位、时间窗、空态、加载状态和钻取事件抽成产品自己的封装，比新增第二套折线图 API 更直接。ECharts 支持 Canvas/SVG 选择，但应按实际图元数量和设备测试，不把某个渲染器称为普遍更快。[3]

一个实际兼容性问题是：本次查到 `vue-echarts` 8.3.0 的 peer 要求 `echarts ^6.0.0`，而当前工程声明 ECharts 5。不能把安装最新 Vue 包装器当成无风险封装升级。可选路径是继续维护现有封装，或单独验证 ECharts 6 和相应包装器，再迁移；不推荐为了“组件数量”同时推进两种变化。[4][R]

Unovis 提供正式 Vue 适配、TypeScript API、按模块导入和 CSS 变量主题，适合比较“少写一些底层图形代码，仍保留定制能力”的路线。它应与一张具体的 D3 定制图做同数据对照，而不是先重做所有 ECharts 页面。其 Vue 1.7.0 peer 精确匹配 `@unovis/ts 1.7.0`，配套升级需要保持一致。[5][R]

uPlot 可以在高密度时间序列确实成为瓶颈时进入测试；当前 15 秒刷新、少量聚合指标并不足以证明需要替换 ECharts。Observable Plot 则适合统计探索和研究型图形，现阶段不是 EAP 工作流编辑或指挥拓扑的首选。[6][7]

## 5. D3：优先选择模块和可复用交互

D3 不是一套与 Element Plus 类似的现成界面组件。它提供数据到图形的转换、布局和交互能力，示例中的轴、提示框、空态、可访问性和生命周期仍需集成。直接复制复杂示例通常会同时带来隐藏的数据假设。[8][9]

| 模块或组件 | EAP 可见收益 | 引入前提 | 优先级 |
|---|---|---|---|
| `d3-zoom` | 拓扑连续缩放、拖动、适配视口、恢复视图 | 统一坐标与缩放状态；保留键盘操作 | 高 |
| `d3-brush` | 时间范围框选，与 Trace/统计列表联动 | 明确时间范围和服务端过滤语义 | 高 |
| `d3-force` | 关系图自动布局 | 不把持续抖动当动态展示；固定已选节点 | 中 |
| `d3-hierarchy` | Agent/工作流层级、调用树 | 数据是树或已明确转换规则 | 中 |
| `d3-sankey` | Token 或成本流向 | 有同一口径的真实边权、无环结构 | 条件成熟后 |
| `d3-flame-graph` | 聚合调用栈耗时分布 | 层级权重定义正确，不冒充时序瀑布 | 后置 |

建议用 Vue 管业务状态、工具栏、详情和空态；用 D3 管独立图形层或只计算几何数据。不要让 Vue 和 D3 同时修改同一组 SVG 子节点。页面销毁时释放监听器、计时器与力模拟；刷新时依赖稳定 ID 更新对象，保留选中和视口。这些是 EAP 接入设计要求，尚未声称完成实现。

`d3-sankey` 官方明确面向有向无环网络。Agent 相互调用可能有环，不能直接传入后期待算法正确表达。可以按调用阶段展开节点，但必须告知用户其含义；否则改用普通有向拓扑。[10]

## 6. 拓扑与工作流：选不同的引擎

### 6.1 运行拓扑

| 候选 | 已核实能力 | 适合 EAP 的情形 | 主要代价 |
|---|---|---|---|
| SVG + D3 模块 | 自定义 SVG 与缩放/布局组合 | 当前规模、独特视觉、精确控制 | 搜索聚焦、层级折叠等需自建 |
| G6 | 专业图可视化，可选 Canvas、SVG、WebGL | 控制面/数据面关系、分组、复杂关系探索 | Vue 生命周期桥接；渲染器需分别验收 |
| Cytoscape.js | 图结构、图算法、布局与扩展 | 路径分析、依赖影响分析、复合关系 | 富业务节点和 Vue UI 要额外整合 |
| Sigma.js + Graphology | WebGL 图显示与图数据模型 | 大规模只读网络探索 | 富表单节点和编辑器不占优势 |
| Vue Flow | Vue 自定义节点及交互 | 小到中等规模、节点里有丰富业务信息 | 大量 DOM/SVG 节点的上限需实测 |

G6 默认 Canvas，SVG/WebGL 需要对应渲染器依赖；不能仅安装 G6 就把所有渲染器能力视为已上线。Cytoscape.js 的 MIT 许可应与同名桌面 Cytoscape 产品区分。Sigma 本次 npm latest 为 3.0.3，而官方同时存在 v4 alpha 文档；稳定版与预览版不能混用。[11][12][13][R]

**建议：** 当前大屏先验证 D3 连续缩放和稳定视口；当需要分组展开、影响路径或大量关系时，对照 G6。不要为了展示“用了多个库”在同一拓扑画布混用多个引擎。Sigma 仅在规模测试证明存在必要性后升级为主候选。

### 6.2 工作流编辑

Vue Flow 的 Vue 3 定制节点、拖动、选择、缩放以及 Minimap/Controls，与 EAP 现有工作流页面方向吻合，适合首选 PoC。X6 使用 SVG/HTML，提供较完整的图编辑扩展，更适合复杂图形和精细编辑；LogicFlow 则可作为业务流程图定制的对照候选。[14][15][16]

三者都不能替代后端工作流执行器。迁移后仍须保留 EAP 节点业务 ID、配置校验、版本冲突处理、保存协议及提交幂等语义。画布坐标和缩放状态应与执行定义分离存储，不能把一个前端框架的全部内部状态当成长期业务协议。

自动布局可先比较 Dagre 与 ELK。它们是布局算法，不是界面库；ELK 的 npm 许可表达式为 `EPL-2.0 OR GPL-3.0-or-later`，不能写成 MIT。选择适用许可及修改分发义务需要按具体版本文本确认。布局计算是否放入 Worker 应由主线程耗时决定。[17][R]

## 7. Trace 与数据语义：决定图形是否可信

目前 `TraceSpanObservation` 有 `traceId`、`spanId`、`parentSpanId`、`durationMs`、`observedAt`，没有显式的规范化 span 开始/结束字段。不能未经后端语义确认就把 `observedAt` 当作开始时间，或简单减去 duration 得出真实起点。当前耗时条可以继续表达“单个 span 用时”，真正瀑布图需要明确开始、结束、时区、精度、缺失和时钟偏移处理。[L3]

vis-timeline 适合带时间区间的事件、治理动作和运行泳道；D3 自定义时间轴适合 Trace 展开、刷选和证据钻取。d3-flame-graph 接受层级数据，适合火焰图；火焰图与按真实时间排列的瀑布图不是同一种图，父子耗时及并发也不能简单累加。[18][19]

| 想增加的图形 | 现有证据 | 还需完善的内容 | 现在可否直接做 |
|---|---|---|---|
| 模型调用/Token 排名 | `modelUsage` 有 calls、totalTokens、moneyCost | 单位、排序和无数据状态 | 可以 |
| Agent 输入/输出 Token 对比 | `subjectUsage` 有两类 Token | 未归因对象保留独立类别 | 可以 |
| 每模型输入/输出堆叠 | `modelUsage` 没有这两类细分字段 | 后端提供同口径细分 | 不可凭总量拆分 |
| Agent → 模型 → 工具 Sankey | Agent 与模型分别聚合 | 调用级关联及同单位边权 | 不可凭边际总量配对 |
| 延迟分布/热力图 | summary 有均值和 P95 | 桶边界、桶计数、时间桶 | 不能从均值/P95还原分布 |
| Trace 瀑布图 | 有父子 ID、duration、observedAt | 规范时间字段与语义验证 | 先补数据契约 |
| 评估阶段进度 | 已有阶段和计数 | 区分已知总量、未知总量、部分完成 | 可以，未知时不显示伪百分比 |
| 拓扑流动粒子 | 配置关系与心跳关系 | 实际调用事件、方向、时间和来源 | 当前只能表示关系，不能伪装实时流量 |

缓存输入 Token 和推理 Token 可能分别是输入、输出的子集，不能无条件与输入/输出再次堆叠。成本流必须统一币种、计价时间窗和归因口径。以上约束来自当前类型与聚合结构的分析，是下一轮图表接口设计的重要前提。[L3]

## 8. 布局与表格：避免为可配置性增加操作负担

GridStack 提供交互看板的拖动与调整尺寸；Grid Layout Plus 明确面向 Vue 3。二者都值得作为“编辑看板模式”的候选。运行指挥大屏默认应锁定布局，防止操作时误拖动；布局保存需要版本、权限、默认恢复和屏幕尺寸迁移规则。[20][21]

对当前固定大屏，CSS Grid 的成本最低。只有用户确实需要按角色保存不同面板组合，才值得引入布局库。图表容器变化还要触发 resize；多个布局引擎与图表 ResizeObserver 相互触发的情况需要实测。

表格方面，Element Plus 的 Table V2 官方仍标注 beta，并提醒虚拟化不能解决网络和内存问题。大量数据首先依赖服务端分页与筛选，不应一次性加载全部记录再以“虚拟列表”掩盖问题。[22]

TanStack Table 是无预设外观的表格逻辑方案，适合需要深度定制且愿意维护 UI 的页面；与 TanStack Virtual 组合前应先确认真实需求。AG Grid 则更适合复杂数据工作台，但 Community 与 Enterprise 的功能和许可应按目标模块逐项区分，不可把付费能力算作 Community 默认收益。[23][24]

现有工作项列表没有显示出必须全局更换表格框架的证据。建议维持当前分页表格；在审计或评估结果出现多列分析需求时，选择一个页面对照 TanStack 与 AG Grid，而不是同步改造所有表格。

## 9. 版本、维护与商用许可

| 产品/包 | 查询到的版本 | 该版本发布时间（UTC 日期） | 公开许可标识 | 结论 |
|---|---|---|---|---|
| @adminlte/vue | 0.7.0 | 2026-08-19 | MIT | 官方 Vue 包，0.x 锁版本验证 |
| d3 | 7.9.0 | 2024-03-12 | ISC | 基础模块长期稳定不等于停止维护 |
| d3-sankey | 0.12.3 | 2019-09-02 | BSD-3-Clause | 单独核对模块，不套用 d3 根包许可 |
| vue-echarts | 8.3.0 | 2026-09-06 | MIT | peer 要求 ECharts 6 |
| @unovis/vue | 1.7.0 | 2026-09-10 | Apache-2.0 | Vue/TS 包版本对应 |
| @vue-flow/core | 1.48.2 | 2026-01-28 | MIT | 工作流编辑首选验证 |
| @antv/g6 | 5.1.1 | 2026-05-08 | MIT | 拓扑主候选 |
| @antv/x6 | 3.1.8 | 2026-08-11 | MIT | 编辑器备选，按主版本查文档 |
| cytoscape | 3.34.3 | 2026-09-07 | MIT | 指 JavaScript 库 |
| sigma | 3.0.3 | 2026-04-30 | MIT | 不套用 v4 alpha API |
| gridstack | 13.2.0 | 2026-08-20 | MIT | 可配置看板候选 |
| grid-layout-plus | 1.1.1 | 2025-10-13 | MIT | Vue 3 布局候选 |
| ag-grid-community / enterprise | 36.1.0 | 2026-08-05 | MIT / Commercial | 精确到实际使用模块 |
| @tanstack/vue-table | 9.2.4 | 2026-08-28 | MIT | 不照搬 v8 教程 |
| elkjs | 0.12.0 | 2026-07-17 | EPL-2.0 OR GPL-3.0-or-later | 单独核对适用许可 |

来源为公开 npm 元数据快照。[R] 完整 35 包版本、仓库链接、peer 信息见附表。发布时间不是 SLA；本报告没有逐项目统计响应时长，也没有给出“无漏洞”承诺。元数据中的 unpackedSize 是解包体积，不能用作浏览器 gzip 包大小排名。

ApexCharts 当前官方页面按 Community、Commercial、OEM/再分发划分许可，包含收入门槛与嵌入式产品条款。EAP 面向企业交付，不能因为 AdminLTE 包装了它，就继承 AdminLTE 的 MIT 许可。Highcharts 也应单独评估商用授权。两者当前没有足够独特收益支持优先承担新许可复杂度。[25][26]

首选栈中的 MIT、ISC、BSD、Apache 标识也不意味着无需保留许可和版权说明。最终交付应依据锁定版本及传递依赖生成清单；示例代码、图标、地图、字体和库本体的授权不可相互替代。

## 10. 视觉设计与组件落点

大屏建议采用明亮蓝灰背景、清楚的文字对比和有限的状态色。若继续深蓝方案，应提高面板与文字亮度，减少大片低对比蓝字、发光描边和持续背景动画。红色只用于需处理的异常；离线、未知和数据过期分别表达，并同时提供文字或图标。

信息顺序建议为：顶部时间窗与数据新鲜度；第一行少量关键指标；中部主要拓扑；侧边当前选中对象；底部真实事件与运行过程。详情按需展开，避免所有面板默认堆满。大屏和管理控制台可以共享指标定义，但不必共享同一种信息密度。

过程动态应由事件驱动：节点状态改变时短暂强调，指标值平滑更新，阶段完成后再推进进度。用户正在选中、拖动、读详情时暂停自动轮播；后台标签页和减少动态效果偏好下停用非必要动画。动画时长与刷新周期分别配置，不用无限移动的线条暗示系统正在执行。

每个图表都应形成可复用契约：标题、单位、时间范围、来源更新时间、loading/error/empty/stale 状态、选择事件和钻取目标。这个 EAP 自有组件层比底层库品牌更能保证整体简洁一致。

## 11. 有边界的 PoC 与验收计划

本计划是下一步实施建议，尚未执行。时间为工程估计，不构成交付承诺；通过一个验证阶段后再决定是否扩大范围。

| 阶段 | 实验内容 | 建议投入 | 通过条件 | 失败后的选择 |
|---|---|---|---|---|
| A | AdminLTE 三类官方组件，对照现有大屏 | 1–2 人日 | Vue/TS 编译通过；主控制台无样式回归；主题不串扰；组件真实渲染 | 独立入口；仍不满足则仅保留设计借鉴 |
| B | D3 缩放、定位、刷选；保留旧图对照 | 2–3 人日 | 刷新保留视口/选择；键盘可用；无事件泄漏 | 收窄为只用几何/交互模块 |
| C | Vue Flow 与当前工作流定义适配 | 2–4 人日 | 旧定义往返不丢信息；冲突/幂等行为不变；运行只读状态明确 | 对照 X6，或延缓迁移 |
| D | G6 与 SVG 图同数据比较 | 2–3 人日 | 复杂图可用性收益明确且成本可接受 | 当前规模继续 SVG |
| E | Trace 时间契约及瀑布图样例 | 2–4 人日，依赖后端 | 并发、缺失父节点、异常时间都有正确表达 | 保留单 span 耗时条 |

性能用合成数据或脱敏的本地数据，不向公共演示站上传 EAP 数据。拓扑测试 50、200、1000 节点，记录边数、标签密度和布局算法；表格测试服务端分页与虚拟化的不同数据量；时间轴覆盖 100、1000、2000 span。上述规模是测试集，不是任一库已被证明支持的上限。

建议在指定验收设备上，以 1920×1080、3840×2160 和窄屏分别记录首屏可交互时间、资源增量、拖动帧率、长任务、60 分钟运行内存趋势。初步目标为常规拖动不低于 30 FPS、交互反馈 p95 不高于 100 ms；这是待协商验收目标而非实测结果。对比应采用相同数据、字体、节点形状和浏览器，避免比较不同复杂度的官方 demo。

数据正确性用固定用例验证：请求失败保留最后数据并显示过期；空数组显示无数据；未知总量不生成百分比；跨页关系明确计数；并发 span 不把 duration 总和当墙钟时长；无调用级关联不生成 Agent→模型流向。保持已有七路数据源的各自状态，不合并成模糊的“系统正常”。

对外部系统的关系也应明确：本报告推荐的组件均是 EAP 本地前端能力；不需要 Galileo 直接集成。若未来把 Galileo 等外部评估结果放入图表，应另行验证认证、结果模型、可见性和数据归属；图形组件本身不提供这些集成保证。

## 12. 最终优先次序

第一优先级是 AdminLTE 官方部件的隔离验证、现有 ECharts 封装统一，以及 D3 缩放/时间选择交互。第二优先级是 Vue Flow 工作流编辑器和 G6 拓扑对照。第三优先级是补齐 Trace 时间与调用关联后，再引入瀑布图和流向图。GridStack、AG Grid、Sigma 和额外通用图表库应由明确的规模或业务需求触发。

该顺序能让用户先看到清晰且有用的改善，同时把样式冲突、执行语义和数据真实性分别验证。正式引入名单应由 PoC 结果收敛，而不是由模板示例数量决定。

## 证据与来源

以下外部来源均于 2026-09-11 访问。官方文档和仓库属于供应方一手证据；推荐顺序与集成设计属于本报告判断。未实测的性能、兼容性、交付时间已经在正文标明。

[L1] EAP 本地：[package.json](../../../frontend/package.json)、[TrendChart.vue](../../../frontend/src/components/TrendChart.vue)。

[L2] EAP 本地：[PROJECT_MEMORY.md](../../PROJECT_MEMORY.md)、[CommandCenterView.vue](../../../frontend/src/views/CommandCenterView.vue)、[WorkflowBuilderView.vue](../../../frontend/src/views/WorkflowBuilderView.vue)。

[L3] EAP 本地：[domain.ts](../../../frontend/src/types/domain.ts)、[TraceEvidenceBars.vue](../../../frontend/src/components/evaluation/TraceEvidenceBars.vue)。

[R] [npm 原始元数据快照](npm-metadata.json)，每条含官方 registry URL、访问时间、版本与许可；[组件清单 CSV](components.csv)。注册表标识需要在正式采用时与该版本 LICENSE 文本共同确认。

[1] [AdminLTE 官方 Vue 简介](https://docs.adminlte.io/vue/introduction)；[官方仓库](https://github.com/ColorlibHQ/adminlte-vue)。

[2] [AdminLTE Vue 安装与独立插件入口](https://docs.adminlte.io/vue/installation)。

[3] [ECharts Canvas 与 SVG 指南](https://echarts.apache.org/handbook/en/best-practices/canvas-vs-svg/)。

[4] [Vue ECharts 官方仓库](https://github.com/ecomfe/vue-echarts)；版本 peer 以 [npm 注册表](https://registry.npmjs.org/vue-echarts) 为准。

[5] [Unovis 官方介绍与 Vue 示例](https://unovis.dev/)。

[6] [uPlot 官方仓库](https://github.com/leeoniya/uPlot)。

[7] [Observable Plot 官方仓库](https://github.com/observablehq/plot)。

[8] [D3 zoom 官方 API](https://d3js.org/d3-zoom)。

[9] [D3 brush 官方 API](https://d3js.org/d3-brush)。

[10] [D3 Sankey 官方仓库与输入约束](https://github.com/d3/d3-sankey)。

[11] [G6 渲染器官方文档](https://g6.antv.antgroup.com/manual/further-reading/renderer)。

[12] [Cytoscape.js 官方文档](https://js.cytoscape.org/)。

[13] [Sigma.js 官方文档](https://www.sigmajs.org/docs/)；[Graphology 数据模型](https://www.sigmajs.org/docs/advanced/data/)。

[14] [Vue Flow 官方功能与示例](https://vueflow.dev/)。

[15] [AntV X6 官方仓库](https://github.com/antvis/X6)。

[16] [LogicFlow 官方仓库](https://github.com/didi/LogicFlow)。

[17] [ELKjs 官方仓库](https://github.com/kieler/elkjs)；[Dagre 官方仓库](https://github.com/dagrejs/dagre)。

[18] [vis-timeline 官方文档](https://visjs.github.io/vis-timeline/docs/timeline/)。

[19] [d3-flame-graph 官方仓库](https://github.com/spiermar/d3-flame-graph)。该包最新 npm 版本查询失败，报告未填入推测版本。

[20] [GridStack 官方仓库](https://github.com/gridstack/gridstack.js)。

[21] [Grid Layout Plus 官方仓库](https://github.com/qmhc/grid-layout-plus)。

[22] [Element Plus Table V2 官方文档](https://element-plus.org/en-US/component/table-v2.html)。

[23] [TanStack Table 官方网站](https://tanstack.com/table/latest)。

[24] [AG Grid Community 与 Enterprise 对照](https://www.ag-grid.com/vue-data-grid/community-vs-enterprise/)。

[25] [ApexCharts 现行许可](https://apexcharts.com/license/)。

[26] [Highcharts 授权页面](https://www.highcharts.com/license)。
