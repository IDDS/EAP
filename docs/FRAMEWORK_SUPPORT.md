# Agent 框架的创建、运行与观测支持

源码核对日期：2026-10-26；各项验证日期以对应记录为准。EAP目前支持部分框架创建路径，不能把“可部署镜像”或“有观测适配器”视为所有框架均可从控制台创建并执行。

| 方式/框架 | 在EAP中创建与部署 | 执行与治理 | 内部逻辑Agent观测 |
|---|---|---|---|
| EAP原生Runner | 已有零代码配置入口 | 已有EAP任务与身份/模型/预算/工具治理路径，各能力仍有交付边界 | 现有遥测；新Python SDK不会自动注入Go Runner |
| Kagent / Go ADK | 已有 `KAGENT_ADK` 入口 | 文本轮次、指定受控求和工具；不是完整Kagent控制器或任意多Agent运行时 | 真实 ADK Agent/工具回调与有界补传；已注册树中的串行/并行子调用适配，任意子 Agent 创建仍未开放 |
| LangGraph 1.2.17 | 固定求和 `TEMPLATE` 表单；管理员配置固定摘要镜像后启用 | EAP 创建、身份配送、工作流派发、受控工具与显式休眠；仅限该模板 | 同进程 4 次逻辑执行、工具、生成者/执行者代码摘要及真实控制台；中断限制仍保持 |
| AutoGen Core 0.7.5 | 固定求和 `TEMPLATE` 表单；同一受控目录 | 同一受控闭环；AgentChat/分布式 Runtime 不包含在当前范围 | 同一 `SingleThreadedAgentRuntime` 的直接 RPC、工具、临时代码与真实控制台 |
| CrewAI、OpenAI Agents SDK等 | 尚无专用零代码创建模板 | 尚未逐框架完成EAP创建/调度验收 | 候选接入点已调研，尚未实现这些适配器 |
| 自有镜像/源码 | 有 `IMAGE` / `SOURCE` 部署入口 | 应用需要实现EAP的身份、任务、健康和控制契约；框架本身不会自动获得治理能力 | 可显式接入事件SDK或匹配版本的适配器，覆盖范围分别验证 |

部署服务接受 `RUNNER`、`TEMPLATE`、`IMAGE`、`SOURCE`。`RUNNER` 的零代码框架白名单仍是 `EAP_NATIVE` 和 `KAGENT_ADK`；`TEMPLATE` 独立提供两个版本化固定样例，不接受任意框架项目。平台固定镜像、命令、端口和就绪路径，拒绝调用方覆盖。[样例配置与使用](FRAMEWORK_SAMPLE_TEMPLATES.md)、[后端规范化入口](../backend/src/main/java/com/eap/platform/deployment/SubstrateDeploymentService.java)、[前端表单契约](../frontend/src/api/substrateDeploy.ts)。

已上报的逻辑执行可在“运行与观测 → 执行关系”查看同流父子关系、工具调用、代码摘要和证据缺口。[执行关系控制台](AGENT_EXECUTION_CONSOLE.md)沿用事件契约，不会自动接入框架，也不扩展创建与调度范围。

不同框架应共享EAP资产目录、发布记录、实例身份、任务和观测入口，框架差异留在版本化运行适配器内。正式补齐创建能力时，每个框架都需要固定依赖/镜像与配置校验、创建和启动模板、任务/取消/超时协议、受管模型与工具调用、运行身份配送及续期、观测覆盖声明，以及回收和升级验收。可以复用EAP现有运行与治理机制，不能通过包装观测函数自动获得这些能力。

两个[固定运行模板](../runtime/framework-runner/README.md)已在真实 EAP 工作流中完成“登记→部署→身份确认→派发→工具/子 Agent 观测→显式休眠/撤销”，最新验收合计 24 条事件、8 次逻辑执行、2 次工具调用和 4 条代码登记，模型调用 0。样例根据已校验的数字数量生成受限求和程序，coding Agent 捕获字节快照并删除文件，由嵌套临时执行者绑定同一摘要执行；不接受任意模型生成代码。当前环境有历史未决占用，样例关闭自动休眠并在终态后经 EAP 显式收尾，没有解除旧 UNKNOWN，也没有验证自动回收。

固定模板的事件上传现支持网络故障有界补发与持久投递状态；只重传原事件，不重跑工具。控制台支持集中投递快照；新版固定模板的持久 Runner 可按原 UID/身份代际有界后台补发。工作流已支持停止后续调度，即时中断和通用恢复仍未实现；中央快照也不等于实时投递队列。[投递说明](../runtime/execution-observer/README.md#有界投递与状态)。

后台补发范围见[专门契约](EXECUTION_BACKGROUND_DELIVERY.md)。集中诊断与停止后续调度已完成有界实现，立即中断和通用恢复仍待；[Go ADK/Kagent 内部观测](KAGENT_EXECUTION_OBSERVATION.md)已实现有界回调适配，并复用中央投递快照协议；事件与报告独立确认，发布状态见验证记录。跨实例委派与代码交接已有[双方确认协议](EXECUTION_LINKS.md)，独立可选 [handoff IMAGE样例](../runtime/handoff-sample/README.md)也已完成EAP创建/调度/短进程执行/确认/回收；新版默认模板已通过显式工作流协议复用交接流程；旧实例和通用框架适配器不会自动接入。通用发布订阅、任务接管和预算传播仍待，不能从固定样例推断任意框架项目已可托管。

当前可用的接入代码与界限见[框架适配说明](../runtime/execution-observer/frameworks/README.md)、[能力清单](../runtime/execution-observer/frameworks/capabilities.json)、[事件协议](AGENT_EXECUTION_EVENT_CONTRACT.md)。整体目标模型和其他框架接入点见[跨框架设计](MULTI_AGENT_EXECUTION_OBSERVABILITY.md)，实际测试范围见[验证记录](VALIDATION.md)。

2026-10-25 的实际发布及四角色浏览器证据见[验证范围](VALIDATION.md)。身份配送后的休眠恢复曾遇到 Substrate 操作冲突，经 EAP 对原版本恢复后完成；该明确占用错误随后已加入[有界恢复和创建终态复核](RUNTIME_IDENTITY_SLEEP_RECOVERY.md)，持续占用或结果含糊仍需核对原实例。
