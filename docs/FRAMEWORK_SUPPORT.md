# Agent 框架的创建、运行与观测支持

源码核对与验证日期：2026-10-24。EAP目前支持部分框架创建路径，不能把“可部署镜像”或“有观测适配器”视为所有框架均可从控制台创建并执行。

| 方式/框架 | 在EAP中创建与部署 | 执行与治理 | 内部逻辑Agent观测 |
|---|---|---|---|
| EAP原生Runner | 已有零代码配置入口 | 已有EAP任务与身份/模型/预算/工具治理路径，各能力仍有交付边界 | 现有遥测；新Python SDK不会自动注入Go Runner |
| Kagent / Go ADK | 已有 `KAGENT_ADK` 入口 | 文本轮次、指定受控求和工具；不是完整Kagent控制器或任意多Agent运行时 | 原有有界遥测；通用子Agent观测适配尚待 |
| LangGraph 1.2.17 | 已有固定求和 `IMAGE` 样例；无专用零代码表单 | EAP 创建、身份配送、工作流派发、受控工具与显式休眠已验证；仅限该模板 | 同进程 3 次逻辑执行、工具、临时代码摘要及真实控制台已验证；中断限制仍保持 |
| AutoGen Core 0.7.5 | 已有固定求和 `IMAGE` 样例；无专用零代码表单 | 同一受控闭环已验证；AgentChat/分布式 Runtime 不包含在当前范围 | 同一 `SingleThreadedAgentRuntime` 的直接 RPC、工具、临时代码与真实控制台已验证 |
| CrewAI、OpenAI Agents SDK等 | 尚无专用零代码创建模板 | 尚未逐框架完成EAP创建/调度验收 | 候选接入点已调研，尚未实现这些适配器 |
| 自有镜像/源码 | 有 `IMAGE` / `SOURCE` 部署入口 | 应用需要实现EAP的身份、任务、健康和控制契约；框架本身不会自动获得治理能力 | 可显式接入事件SDK或匹配版本的适配器，覆盖范围分别验证 |

当前部署服务只接受 `RUNNER`、`IMAGE`、`SOURCE`；其中零代码 `runnerFramework` 白名单为 `EAP_NATIVE` 和 `KAGENT_ADK`，前端相同。仅新增一个框架下拉选项不足以完成接入。[后端规范化入口](../backend/src/main/java/com/eap/platform/deployment/SubstrateDeploymentService.java)、[前端表单契约](../frontend/src/api/substrateDeploy.ts)。

已上报的逻辑执行可在“运行与观测 → 执行关系”查看同流父子关系、工具调用、代码摘要和证据缺口。[执行关系控制台](AGENT_EXECUTION_CONSOLE.md)沿用事件契约，不会自动接入框架，也不扩展创建与调度范围。

不同框架应共享EAP资产目录、发布记录、实例身份、任务和观测入口，框架差异留在版本化运行适配器内。正式补齐创建能力时，每个框架都需要固定依赖/镜像与配置校验、创建和启动模板、任务/取消/超时协议、受管模型与工具调用、运行身份配送及续期、观测覆盖声明，以及回收和升级验收。可以复用EAP现有运行与治理机制，不能通过包装观测函数自动获得这些能力。

两个[固定运行模板](../runtime/framework-runner/README.md)已在真实 EAP 工作流中完成“登记→部署→身份确认→派发→工具/子 Agent 观测→显式休眠/撤销”，合计 18 条事件，模型调用 0。样例中的 coding Agent 执行模板固定代码，文件删除后保留摘要；不接受任意模型生成代码。验收样例关闭自动休眠并在终态后经 EAP 显式收尾；该结果不代表自动回收已验证，未决占用仍受原有保护。

固定模板的事件上传现支持网络故障有界补发与持久投递状态；只重传原事件，不重跑工具。后台常驻补发、集中队列 UI 和任务取消仍未实现。[投递说明](../runtime/execution-observer/README.md#有界投递与状态)。

下一步补框架模板的产品化表单、配置校验、取消和故障恢复，再扩展实际 Go ADK/Kagent 的内部观测。发布订阅、handoff、跨实例关系和预算传播仍需扩展契约。不能从两个固定样例推断任意框架项目已可托管。

当前可用的接入代码与界限见[框架适配说明](../runtime/execution-observer/frameworks/README.md)、[能力清单](../runtime/execution-observer/frameworks/capabilities.json)、[事件协议](AGENT_EXECUTION_EVENT_CONTRACT.md)。整体目标模型和其他框架接入点见[跨框架设计](MULTI_AGENT_EXECUTION_OBSERVABILITY.md)，实际测试范围见[验证记录](VALIDATION.md)。
