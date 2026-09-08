# EAP 系统审查与优化记录

审查日期：2026-09-08。基于本地分支 `feat/galileo-evaluation-foundation`、起始提交 `0780de2`。状态：DONE_WITH_CONCERNS。

> 后续更新：第二轮已处理下文多数问题，最终状态与 159 项后端测试基线见 [第二轮改进记录](SYSTEM_IMPROVEMENTS_2026-09-08.md)。下文保留首次审查时的发现与优先级；工作流真实执行、历史校准及规模化读取仍待完善。

## 结论

本轮确认并修复了前端对象切换错配、JWT 权限边界、评估事务回滚、错误状态误报、控制重试计数和连接器异常恢复等问题，并完成页面按需加载。当前能力适合继续演示和集成验收；尚不能以本轮测试通过作为生产就绪结论。

最值得继续投入的是：多币种成本与 ROI 的正确口径、真实 Runtime 生命周期和工作流执行、历史数据服务端过滤/分页、异步评估崩溃恢复。单纯继续美化页面的优先级低于这些问题。

## 范围与证据

- 静态审查：前端路由、请求层、主要工作台；后端认证、发现、控制、网关、观测、运营事项、评估、任务、管理价值、工作流与部署配置。
- 动态验证：Java 测试、H2 迁移与事务代理、JWT MockMvc、前端 TypeScript/Vite 构建、受控逆序请求回归。
- 源码和凭证未外发；没有提交、推送或部署，没有更改云端运行状态。
- 未完成真实浏览器全站端到端验收、真实供应商调用、云端状态复核、生产负载测试或依赖漏洞数据库扫描。以下性能结果仅为构建产物体积。
- 本机缺少 Go（`go: command not found`），Docker daemon 不可连接，因此未重新运行 Discovery Agent Go 测试。

## 已修复的问题

下表置信度均为 9/10：已读取对应调用链，并有针对性测试或构建验证；不代表真实外部系统验收。

| 优先级 | 问题及触发条件 | 本轮处理 | 主要证据 |
|---|---|---|---|
| P1 | 切换 `/agents/A` 到 `/agents/B` 时 Vue 复用详情组件，仍显示或操作 A | 监听路由 ID、清空旧对象、禁用未加载操作、拒绝旧响应 | `frontend/src/views/AgentDetailView.vue`；`frontend/tests/request-races.test.mjs` |
| P1 | 数据集切换或 Trace 逆序返回导致样本/证据错配；旧样本可能使用新数据集 key 标注 | 请求代次保护、切换清空、标注校验当前有效行 | `EvaluationView.vue`；前端请求回归 |
| P1 | 切换工作流仍保留旧节点，保存时可能写入新工作流的同名节点 | 清除节点、拖动和连线状态，拒绝旧响应 | `WorkflowBuilderView.vue`；前端请求回归 |
| P1 | 内容双人审批信任客户端填写的角色和姓名，一个账号可模拟两人 | JWT 模式校验真实角色并以 token subject 记录身份，要求不同审批人；DATA_OWNER 只获得该审批入口权限 | `DiscoveryAgentV1Service.java`、`SecurityConfig.java`；`JwtSecurityIntegrationTests` |
| P1 | VIEWER 可通过 authenticated 兜底规则修改、启停和同步业务连接器 | 连接器写入限制为 OPERATOR/PLATFORM_ADMIN；保留 VIEWER 读取 | `SecurityConfig.java`；`viewerCanReadButCannotMutateBusinessConnectors` |
| P1 | 普通查看者无正文权限，详情页的正文 403 导致整组 Promise.all 失败 | 将正文失败隔离并显示明确错误，其余详情继续加载 | `AgentDetailView.vue`；前端请求回归 |
| P1 | 批量评估捕获单条异常，但内层事务已把整批标记 rollback-only | 移除批次外层事务，让每次 evaluate 独立提交 | `EvaluationDatasetRunService.java`；`EvaluationDatasetRunTransactionTests` |
| P1 | 异步评估 RUNNING 和 FAILED 与执行共用事务，运行中不可见，异常后状态可能回滚 | 保留原有悲观锁，短事务领取、独立执行、短事务保存结果 | `EvaluationJobService.java`；`EvaluationJobTransactionTests` |
| P1 | 未恢复的审批/评估告警移出最近 500 条扫描后自动关闭 | 关闭前回查源记录；评估证据版本使用结果保存的历史版本 | `WorkItemService.java`；`WorkItemSignalWindowTests` |
| P2 | 无在线 Runtime 时 attemptCount 每次归 1，无限重试 | 每次派发统一递增并保留最大尝试次数规则 | `AgentControlService.java`；`AgentControlServiceTests` |
| P2 | 连接器首次查询/保存异常后运行标记不清理，后续重试永久被拒 | 将整个同步生命周期纳入 finally 清理 | `ManagementConnectorService.java`；`ManagementConnectorServiceTests` |
| P2 | 非法 JSON、枚举、数字或缺少必填查询参数返回 500 | 返回不泄露请求内容的 400；权限拒绝返回 403 | `GlobalExceptionHandler.java`；`GlobalExceptionHandlerTests` |
| P2 | 目录、总览和编排快速切换，旧响应覆盖新选择；清空全局搜索后 loading 残留 | 增加请求顺序保护并清理加载状态 | `AgentsView.vue`、`DashboardView.vue`、`OrchestrationView.vue`、`App.vue` |
| P2 | 数据集样本先全量查出再 limit，创建时全量加载计数 | 数据库 Pageable 限量查询、createdAt/id 稳定排序、count 查询 | `EvaluationDatasetItemRepository.java`、对应 service 与测试 |

## 性能优化

16 个业务路由从同步 import 改为按需加载。业务入口 JS 从 884.03 KB 降至约 80 KB，gzip 从 278.50 KB 降至约 30 KB。共享 Element Plus 和图表包仍存在；这些数字不是首屏总下载量、渲染时间或 Core Web Vitals 的降幅。

## 尚未解决的优先事项

### P1：多币种 ROI 不可直接相加（置信度 9/10）

`backend/src/main/java/com/eap/platform/management/ManagementService.java:177` 使用 `values.stream().map(value -> value.benefitMoney).reduce(...)`，随后直接减遥测总成本；价值事件和模型事件又允许不同 currency。100 CNY 与 100 USD 会被当成同量纲相加，影响净收益和 ROI。

建议先支持按币种分别报告，再设计统一结算币种及带日期的汇率快照。验收：混合 USD/CNY 输入不能显示无币种的单一 ROI；单币种行为保持可追溯。此项需要业务结算口径，未自动引入汇率来源或改写历史金额。

### P1：工作流“成功”尚不代表 Agent 实际执行（置信度 9/10）

`WorkflowService.java:143` 对出边排序后 `findFirst()`，未解释 `conditionExpression`；`:168` 只调用安全评估，`:182` 把 `ALLOW` 映射为 `DONE`。建议先在界面/API 显式区分安全预演，再实现条件执行与 Runtime 结果回传。验收：条件不满足时不会走第一条边，Agent 调用失败不能被记为成功，状态能关联真实执行证据。

### P2：Runtime 心跳缺少过期处理（置信度 9/10）

`AgentRegistryService.java:58` 注册为 ONLINE，`:69` 更新心跳；`:87` 的候选过滤与 `AgentControlService` 只检查 ONLINE，不检查心跳年龄。需要统一可配置租约、过期状态与调度过滤。验收：停止心跳超过租约后不再参与选路，恢复心跳后可恢复。

### P2：注册令牌并发次数限制（置信度 8/10，未做并发集成复现）

`DiscoveryAgentV1Service.enroll` 先读令牌、检查 usedCount，再递增；`DiscoveryEnrollmentTokenRepository.findByTokenHash` 无锁，实体无版本字段。两个不同 installationId 并发注册可能同时消费单次令牌。建议使用事务内锁或条件更新；验收 `maxUses=1` 并发请求只成功一个，重放同 installationId 保持既定幂等行为。

### P2：网关 SSE 全量缓冲（置信度 9/10）

`OpenAiUpstreamClient.java:39` 先 `toEntity(String.class)`，`OpenAiCompatibleGatewayService` 再检查 maxResponseBytes；限制无法约束读取期间的内存，首事件需等上游结束。先实现有界读取并明确缓冲语义；真正流式输出需同时设计逐块治理，不能为了延迟绕过输出预检。验收包括超限响应提前中止与治理阻断不泄漏正文。

### P2：历史数据与查询规模（置信度 9/10）

- Agent 详情仍从全局最近 500 条知识/治理记录中按 Agent 过滤，繁忙环境会漏掉目标 Agent 的记录。改为后端 agentId 过滤后分页；用其他 Agent 超过 500 条新事件验证旧目标记录仍可查。
- `AgentOpsService.loadSnapshot` 全量读取任务/资产/开放事项后在内存筛选；首页 GET 还调用 `synchronizeSignals` 写入事项。规模增大时应拆分读取与信号同步，使用窗口查询和数据库聚合，并做负载验证。
- 校准仍依赖当前启用评估器与版本；历史校准需要独立版本快照，防止停用评估器改变历史解释。

### P2：状态恢复与操作语义（置信度 9/10）

- 本轮异步任务 RUNNING 已独立持久化，但尚无 lease/超时恢复；执行进程崩溃会遗留 RUNNING，影响 submitIfIdle。上线多实例/自动运行前需确定超时、幂等重试和恢复策略；不能把失败状态持久化测试当成崩溃恢复验证。
- `AgentTaskService.java:46` 直接覆盖 status，可把终态改回 RUNNING 并保留 finishedAt。需明确允许的重试/重开状态机及时间字段处理。
- 评估页面轮询离页后仍可能继续；其他工作台仍有整体 Promise.all 错误耦合。需取消离页轮询并逐区块提供错误和重试入口。

## 验证与交付限制

- 后端全量 `mvn -q test` 116 项已在允许本机临时端口的环境运行通过；追加连接器权限回归后重跑 `JwtSecurityIntegrationTests` 通过，当前报告合计 117 项，0 failures、0 errors、0 skipped。首次沙箱运行的 4 个错误为本机 HTTP/gRPC 监听被拒，不是断言失败。
- H2 Flyway V1–V38 成功；本轮无新数据库迁移，不代表 MySQL 生产兼容性重新验收。
- 前端 `npm run build` 和 `npm test`（11 项）通过。Node 测试从实际 SFC 中提取函数，以受控响应顺序验证对象错配，不能替代真实浏览器端到端测试。
- 云端仍应按历史记忆中的演示环境边界看待；本轮没有重新读取线上状态，也没有开启正文采集或真实控制派发。

## 本轮可复用经验

Vue 路由复用及异步选择必须绑定对象 ID 和请求代次；Java 在外层事务内捕获内层异常不等于事务恢复；有界近期扫描的“未出现”不能作为源问题已恢复的证据。后续新增页面、批处理与运营信号时应复用这些回归场景。
