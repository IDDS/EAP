# EAP 同类产品与 GitHub 项目调研

调研及访问日期：2026-09-11。EAP 对照版本：本地 `621b880`，分支 `feat/galileo-evaluation-foundation`；项目记忆最近更新 2026-09-08。本次仅查询公开资料、读取本地实现并撰写文档，没有部署、运行外部组件或上传私有数据。

**结论：EAP 下一阶段最值得投入的是可信身份、执行前控制、可恢复执行和质量回归闭环。** 已有发现、观测、评估、治理、价值管理模块为此提供了基础。建议用一个真实业务场景把这些模块连接起来，再扩展框架和平台覆盖。

**产品定位判断：** EAP 可以围绕“企业跨框架 Agent 资产、权限、运行结果与业务价值的统一管理”形成差异化。单独增加 Trace 图表、Prompt 编辑器或更多规则评分，容易与已有平台重叠。这是基于功能对照的判断，尚未用客户访谈或付费意愿验证。

**按用户澄清收敛：本报告的重点是核心功能和技术方法的完备性，产品界面与组件数量不是评判标准。** 核心判断是：现有路线可以延续，但从观测和规则原型走向可信控制平面，还需要完善如下机制。

| 核心技术方法 | 本地证据与缺口 | 建议方法及验证 |
|---|---|---|
| 增量事件处理与 Trace 完整性 | `EvaluationProjectionService.synchronize` 对 Tool、Span、RAG 分别读取最近 500 条；Span 先截断再按 traceId 分组，存在形成部分 Trace 的风险；积压超过窗口时也可能漏评。这是静态风险判断，未做负载复现 | 持久化游标/待处理事件表、按 Trace 补全查询、迟到事件窗口与聚合版本；超过 500 条及乱序跨批输入后验证不漏评、不把部分 Trace 当完整任务 |
| 可信身份 | DiscoveryIdentityLink 的依据/置信度解决观测关联，不能单独证明请求主体 | 使用凭证验证主体，绑定实例/项目/权限；把推断关联与认证身份分开，验证伪造标识和撤销 |
| 可执行策略 | `GenseeSafetyService` 中可见基于 target/payload/action 子串的风险分类；不能据此推断全项目所有治理实现，但该路径存在依赖名称而非真实能力语义的局限 | 工具注册元数据、参数 Schema、资源/主体/动作/环境授权与数据流标签；在副作用前强制执行，规则版本化；关键词作为辅助信号，验证改名、间接调用和参数变化 |
| 持久任务状态机 | 已有提交幂等和 UNKNOWN，但缺查询、恢复和取消合同 | Runtime 能力协商、稳定 taskKey、查询核对、租约及过期 worker 隔离；重放前确认副作用，验证崩溃与迟到回执竞态 |
| 分层质量评估 | MODEL_CALL_STATUS 只证明调用状态；确定性评估未形成通用语义执行器 | 协议成功、轨迹合理性、答案正确性、业务完成分别评估；规则+可校准 Judge+人工真值，明确弃权；用业务确认信号校验最终结果 |
| 可重复实验 | 已有校准历史快照，但不能由 version 字段推导完整数据集版本读取与实验执行能力 | 冻结数据集、评估器、模型/Prompt/Agent 配置和工具 Schema；固定基线运行，逐样本比较、报告覆盖与不确定性 |

**实施顺序判断：** 首先保证事件不漏、身份不串、控制确实生效；随后保证状态可核对恢复；再扩展语义质量和版本实验。不能用增加 LLM Judge 弥补缺失的运行证据，也不能用 checkpoint 替代外部副作用幂等。

## 1. 证据口径与范围

- **事实 F**：官方文档/仓库明确描述的能力，或本地代码直接可见的实现。
- **判断 J**：对 EAP 的优先级、产品定位和架构建议。
- **待验证 U**：部署兼容性、性能、跨平台语义映射、商业版本边界等。
- **证据 A**：本地代码直接核对；**B**：官方文档和官方仓库两种材料相互支持；**C**：仅有仓库 README 或单一官方材料。B 仍属于厂商自述，不是独立运行验证。

选择覆盖观测评估、流量治理、运行恢复、安全测试的 10 个项目；不是完整市场排名。不使用 GitHub Star 数作为生产成熟度指标，没有把搜索摘要中的发布日期当成正式发布日期。下列链接均于上述日期访问，GitHub main 与 latest 文档会继续变化，PoC 前应固定 tag/commit 和镜像摘要。

## 2. 产品与项目对照

| 产品/项目 | 已核实的公开能力 F | 对 EAP 最有价值的借鉴 J | 证据及边界 |
|---|---|---|---|
| Langfuse | Trace、评估、数据集、实验；数据集可以按版本运行实验 | 从失败 Trace 创建样本，冻结版本，比较不同 Agent/模型/Prompt 版本，形成发布门槛 | B：[仓库](https://github.com/langfuse/langfuse)、[评估概念](https://langfuse.com/docs/evaluation/core-concepts)、[数据集版本](https://langfuse.com/docs/evaluation/experiments/datasets)。EAP 已有校准历史，应补实验执行与版本比较 |
| Arize Phoenix | OpenTelemetry/OpenInference 观测、评估、版本化数据集、实验、Prompt Playground | 多步骤失败定位、检索评估、样本到实验的诊断工作流 | B：[仓库](https://github.com/Arize-ai/phoenix/)、[文档](https://arize.com/docs/phoenix/)。仓库注明 ELv2，不应直接按宽松许可证组件处理 |
| OpenLIT | 基于 OpenTelemetry 的模型/工具/框架自动插桩，可配置 OTLP 目标 | 扩大采集适配范围，制定 EAP 的框架版本和字段兼容矩阵 | C：[仓库](https://github.com/openlit/openlit)。此次文档入口未成功读取；支持自动插桩不等于所有进程都能自动发现或所有字段与 EAP 兼容 |
| AgentOps | Python SDK 提供 Agent 监控、成本跟踪及框架集成 | 按一次任务/会话串联模型、工具、子 Agent 和失败位置，缩短首次接入路径 | C：[仓库](https://github.com/AgentOps-AI/agentops)。此次所查文档路径未成功读取；该仓库是 SDK，不能由此推断完整平台可自托管 |
| agentgateway | LLM、MCP、A2A 通信代理；模型路由、预算控制、MCP 工具聚合与 OAuth；独立及 Kubernetes 部署 | 将 EAP 策略管理与请求路径上的执行点分开，补 MCP 工具级控制 | B：[仓库](https://github.com/agentgateway/agentgateway)、[部署文档](https://agentgateway.dev/docs/)。EAP 策略映射、身份传递、拦截覆盖和控制面故障行为尚未验证 |
| Microsoft MCP Gateway | MCP 会话亲和路由、授权、服务生命周期及工具动态路由 | 把 MCP 从资产列表推进到可管理的工具服务目录与生命周期 | C：[仓库](https://github.com/microsoft/mcp-gateway)。README 明确 Agents/Sessions 为可选 Preview、单副本；该限制不能误推广到所有网关功能，也不能忽略后直接做多租户运行时 |
| LiteLLM | 统一模型接口、虚拟 Key、成本管理、路由和负载均衡 | 每 Agent/项目独立凭证、模型白名单、预算及供应商费用对账 | B：[仓库](https://github.com/BerriAI/litellm)、[Virtual Keys](https://docs.litellm.ai/docs/proxy/virtual_keys)。EAP 现有 LiteLLM 回调采集不等于已部署完整 Proxy；预算严格性须验证并发、费用延迟和数据库故障 |
| LangGraph | 持久化执行状态，通过 interrupt/Command 支持人工介入和恢复 | 审批等待持久化，查询外部任务状态后恢复，明确副作用边界 | B：[仓库](https://github.com/langchain-ai/langgraph)、[Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)。恢复会重新进入节点，interrupt 前代码可能重跑；不能推导外部效果恰好一次 |
| Promptfoo | 声明式评估、模型对比、CI/CD 和红队测试 | 固定安全回归集、策略升级前后对比、失败样本留档 | B：[仓库](https://github.com/promptfoo/promptfoo)、[红队配置](https://www.promptfoo.dev/docs/red-team/configuration/)。本地执行不保证生成/评分均在本地，须核对各条数据路径 |
| Microsoft Agent Governance Toolkit / ACS | 公开预览的策略运行层，定义介入点、确定性决策、审批升级与证据；支持宿主提供完整状态快照 | 策略即代码、决策记录包含策略版本和证据、数据标签与工具目的地关联 | C：[仓库](https://github.com/microsoft/agent-governance-toolkit)、[ACS 包文档](https://github.com/microsoft/agent-governance-toolkit/blob/main/docs/packages/agent-control-specification.md)。同仓库材料，不算独立验证；Preview 和厂商覆盖声明不代表经过 EAP 验收 |

## 3. EAP 已有基础与实际差距

以下 A 级证据是静态代码核对，不代表本次重新运行测试或检查线上。

| 领域 | 已有实现 F / 代码证据 | 需要补齐的能力 J |
|---|---|---|
| 评估 | [EvaluationService](../backend/src/main/java/com/eap/platform/evaluation/EvaluationService.java) 使用确定性 implementationKey 分派；枚举虽含 LLM_JUDGE/LOCAL_SLM，不能据此认为执行器已实现 | 引入有版本、有证据、有校准的语义评分；规则与语义结果分别展示 |
| 通信分析 | [ContentAnalysisProjectionService](../backend/src/main/java/com/eap/platform/discovery/ContentAnalysisProjectionService.java) 以元数据生成 MODEL_CALL_STATUS，明确 MODEL_CALL_ONLY、taskCompletion UNKNOWN | 补任务、会话、业务结果关联；不要将 HTTP 成功展示为任务成功 |
| 实验 | [EvaluationDataset](../backend/src/main/java/com/eap/platform/evaluation/EvaluationDataset.java) 有 version；[CalibrationHistoryService](../backend/src/main/java/com/eap/platform/evaluation/EvaluationCalibrationHistoryService.java) 保存不可变校准快照 | 已有历史无需重建；补数据集发布快照、版本指定读取、候选运行、基线比较和 CI 门槛 |
| 执行恢复 | [WorkflowExecutionService](../backend/src/main/java/com/eap/platform/workflow/WorkflowExecutionService.java) 有提交去重、DISPATCHING 和 UNKNOWN；[执行合同](WORKFLOW_EXECUTION.md) 明确无查询/取消/恢复协议 | 先增加按 taskKey 查询与能力协商，再做安全恢复、审批恢复和取消回执 |
| 身份 | [DiscoveryIdentityLink](../backend/src/main/java/com/eap/platform/discovery/DiscoveryIdentityLink.java) 有来源、关联依据和置信度 | 发现关联与认证主体分开建模；绑定 owner、项目、环境、运行实例和可撤销凭证 |
| 网关 | [GatewayProperties](../backend/src/main/java/com/eap/platform/gateway/GatewayProperties.java) 配置单一上游、供应商及 Token 单价 | 核对此路径之外的能力后，建设按模型/供应商版本化价格、Agent 凭证及预算管理；未知价格不能默认为免费 |
| 治理证据 | [SafetyDecisionLog](../backend/src/main/java/com/eap/platform/safety/SafetyDecisionLog.java) 记录决策、原因、动作和 sessionKey | 增加策略版本/摘要、执行点、判定输入摘要、审批绑定和实际拦截回执，支持审计重现 |

## 4. 建议的改进顺序

P0 表示真实业务闭环的基础，P1 表示随后完成的产品能力，P2 表示规模扩大后的投入。均为建议，不是已批准的开发范围。

| 优先级 | 改进项 | 用户得到什么 | 最小验收条件 |
|---|---|---|---|
| P0 | Agent 身份与责任归属 | 知道谁创建、谁负责、以谁的权限执行 | 两个 Agent 共用供应商 Key 仍准确归因；伪造 agentId 不切换身份；撤销 A 不影响 B；无法归因的记录保留未知 |
| P0 | 执行前工具权限与审批绑定 | 高风险动作真正受控，可看到是否已拦截 | ALLOW/ASK/DENY 用例完整；拒绝时模拟工具副作用计数为 0；参数变化或审批过期后必须重新决策；无执行点路径标记未受控 |
| P0 | 状态查询、审批恢复、取消协议 | 中断后可继续处理，避免人工盲目重试 | 断网、重启、迟到回执不误报成功；taskKey 查询核对；取消请求与取消完成分开；未知外部效果不自动重放 |
| P1 | 失败 Trace → 回归集 → 版本实验 | 知道新版本是否真正更好 | 固定数据集/评估器/Agent 版本，运行 A/B；显示逐样本变化、通过率、成本、时延；样本更新不改变历史结果 |
| P1 | 语义质量与人工复核 | 分开回答调用是否成功、答案是否正确、任务是否解决 | 使用合成或已批准样本校准；报告误报/漏报、弃权和证据；低置信度进入人工队列；模型评分不直接触发高风险阻断 |
| P1 | 会话与业务结果关联 | 从业务问题追到具体工具步骤和确认价值 | session/task/trace/tool/业务事件可互相跳转；区分原始与派生 Span、观测事实与推断、执行完成与业务确认 |
| P1 | 每 Agent/项目预算 | 费用可解释、可限制、可对账 | 预占—结算—释放在并发下不丢账；流式中断/价格未知/供应商账单延迟显式处理；不同币种不直接相加 |
| P1 | 安全回归集 | 策略更新有可重复的证据 | 固定注入、越权、审批绕过、参数篡改等合成用例；按版本比较，记录误拦截正常请求的比例 |
| P2 | 遥测覆盖与生命周期 | 看见监控盲区和存储成本 | 区分资产发现、调用观测、任务关联、执行控制四类覆盖率；增加缺失字段、采样、丢弃、留存及删除指标 |

**界面建议 J：** Agent 详情优先呈现“负责人/权限、当前任务与等待原因、失败及证据、费用与预算、已确认业务结果”。Trace 仍是下钻证据。首页增加“未受控资产、待审批动作、结果未知任务、质量回归、预算异常”入口，并标注统计窗口、覆盖率和最后更新时间。

**暂不优先 J：** 完整复制 Prompt IDE、接入大量未使用框架、将每条输出送入 LLM Judge、用学习分数自动修改生产策略。现有正文限制下，元数据不能支撑语义结论；这些方向应由具体业务需求和可用数据推动。

## 5. 原生建设、组件接入与 Galileo 边界

| 路线 | EAP 负责 | 外部系统负责 | 当前结论 |
|---|---|---|---|
| EAP 原生 | 资产身份、责任归属、审批、策略版本、任务状态、预算账本、业务价值、统一证据模型 | 无必需外部平台 | 推荐作为核心；在既有模块上扩展 |
| EAP + agentgateway 或 LiteLLM | 管理意图、授权来源、审计与资产映射 | 相应协议路径的模型/工具代理与执行 | 候选 PoC；先各验证一个场景，不叠加多个网关；字段和策略不假定天然互通 |
| EAP + OpenLIT/OpenInference 类采集 | 身份归一化、接入认证、正文策略、去重与查询 | 框架自动插桩 | 候选 PoC；标准 OTLP 传输不意味着属性、Token 和父子 Span 语义兼容 |
| EAP + LangGraph | 跨 Runtime 的统一状态和治理 | 特定 Runtime 内部持久化与恢复 | 通过适配器联动，不把某一框架状态机强加给所有 Agent |
| EAP + Langfuse/Phoenix/Promptfoo | 样本授权、版本映射、评估结果归档 | 可选的实验或评估工具 | 先使用私有部署/隔离运行器及合成样本；保留独立退出路径 |
| 直接 Galileo 集成 | 经批准数据的最小化路由、结果关联 | 所选择版本实际提供的评估服务 | 本轮未重新核验 Galileo API、定价和部署条款，不新增兼容性承诺；原研究见 [Galileo memo](GALILEO_RESEARCH.md) |
| EAP + Galileo 联动 | 资产/任务/权限/审批/策略执行与业务结果 | 可选语义评分或诊断结果 | 只有选择该路线时才需要两套系统；低置信度评分先进入人工复核，不能直接等同治理许可 |

建议外部评估结果适配合同至少包含：sourceSystem、externalResultId、agentId、traceId、taskKey、datasetVersion、evaluatorVersion、score、confidence、evidenceRef、dataClassification。此为 EAP 设计建议，不是任何供应商现成协议。认证、重放去重、删除传播和版本映射须分别测试。

## 6. 选型风险与未知项

1. **许可证与版本范围 F/U。** [Langfuse LICENSE](https://github.com/langfuse/langfuse/blob/main/LICENSE) 区分 MIT 范围与 ee 目录；[Phoenix 仓库](https://github.com/Arize-ai/phoenix/) 标注 ELv2。这里只记录仓库信息，不据此给出可嵌入 EAP 商业发行版的结论；采用前固定提交并核对对应组件条款。
2. **自托管边界 F。** [Promptfoo 自托管文档](https://www.promptfoo.dev/docs/usage/self-hosting/) 明确基础版使用 SQLite，缺少内置认证、RBAC 和横向扩展能力，不建议用于生产服务。因此优先作为隔离 CI 工具，不能直接充当 EAP 多团队评估后台。
3. **出站数据 F。** [Promptfoo 数据处理说明](https://www.promptfoo.dev/docs/red-team/troubleshooting/data-handling/) 区分目标执行、用例生成和评分，部分路径使用托管服务；仅关闭遥测不足以保证无外发。PoC 使用合成数据，并在本地生成/评分配置外验证实际出站流量。
4. **恢复不等于副作用去重 F/J。** [LangGraph 文档](https://docs.langchain.com/oss/python/langgraph/interrupts) 明确恢复时节点前半段可能重跑。EAP 仍须依靠业务 taskKey 去重和状态核对，不能仅加入 checkpoint 就承诺恰好执行一次。
5. **执行覆盖 U。** SDK hook 和网关只能控制经过它们的路径。无法通过公开材料保证 Agent 不会绕过网关直接调用外部工具；需用隔离网络和旁路测试验证覆盖范围。
6. **预算精度 U。** 计费时延、并发、重试、缓存、推理 Token 及未定价模型均影响控制。预占预算是建议设计；供应商账单仍须独立对账，不把本地估算当最终账单。
7. **成熟度 U。** 未运行外部项目，未完成依赖漏洞审计、压力测试、HA/灾备或版本升级验证。文档 + README 能支持功能存在的判断，不能支持生产可靠性承诺。

## 7. 可执行 PoC 计划

**范围 J：** 单个隔离测试环境，两个合成 Agent、一个模拟模型服务、两个模拟工具（只读查询、写入测试记录）。全部工具只触碰测试数据。先固定候选组件版本和数据协议，不连接生产 HelloBoss，不读取历史业务正文。此处只提供计划，本次没有实施。

| 阶段 | 工作 | 产物与通过条件 |
|---|---|---|
| A：接入与身份 | 选择 OpenLIT 或现有 OTLP 插桩路径；两个 Agent 共用一个模拟供应商 Key，各用独立运行身份；建立固定事件清单 | 100 条合成调用按清单核对：不跨 Agent 归因、不重复计数；缺失身份显式 UNKNOWN；原生/派生 Span 可区分 |
| B：工具控制 | 单独部署 agentgateway 候选，验证 EAP 策略如何映射；不能表达的策略明确保留在 EAP 执行适配层 | 覆盖允许、拒绝、待审批、过期、参数变化、凭证撤销、绕过路径；拒绝/未审批时工具侧写入数为 0；记录控制故障行为 |
| C：可恢复运行 | 模拟 Runtime 提供 taskKey 查询和去重；注入响应丢失、进程重启、重复回调、审批等待及取消竞态 | EAP 不误判终态；查询后能核对完成；不重复写测试记录；不能确认的效果保留 UNKNOWN；审批后恢复仍校验当前权限 |
| D：质量实验 | 50 条合成样本，包含明确成功/失败、边界和正常反例；冻结数据集、规则及 Agent A/B 版本 | 输出逐样本差异、混淆矩阵、弃权、成本和时延；演示“调用成功但任务失败”；历史报告不随当前标注变化 |
| E：安全与预算 | 从 Promptfoo 借鉴或使用本地固定用例；验证实际出站目标；模拟预算并发和流式中断 | 无未配置外发；20 个并发请求下预占/结算一致，超预算不触发新调用；无价格记录进入待核对；安全集含正常请求误报统计 |

50 条样本和 100 条调用仅是功能验收集，不足以证明泛化质量或生产规模。性能记录同机直连基线与接入后 p50/p95、错误率和资源消耗；接受阈值由目标场景延迟预算确定，不引用供应商宣传数字代替测试。

**退出标准 J：** 身份不能可靠绑定、执行点不能阻止测试副作用、恢复导致重复写入、存在不受控外发，任一出现就停止扩大试点。保留证据并缩小方案，不以堆叠更多组件绕过问题。

**推荐第一项交付 J：** 做成可演示的“任务证据与治理闭环”：从 Agent 详情进入一次任务，看到调用成功但业务未完成的原因、待审批工具动作、核对与恢复入口，以及进入回归集的操作。它可以同时验证 EAP 的观测、控制和业务价值，而不仅展示模块数量。
