# Agent 框架的创建、运行与观测支持

源码核对与验证日期：2026-10-24。EAP目前支持部分框架创建路径，不能把“可部署镜像”或“有观测适配器”视为所有框架均可从控制台创建并执行。

| 方式/框架 | 在EAP中创建与部署 | 执行与治理 | 内部逻辑Agent观测 |
|---|---|---|---|
| EAP原生Runner | 已有零代码配置入口 | 已有EAP任务与身份/模型/预算/工具治理路径，各能力仍有交付边界 | 现有遥测；新Python SDK不会自动注入Go Runner |
| Kagent / Go ADK | 已有 `KAGENT_ADK` 入口 | 文本轮次、指定受控求和工具；不是完整Kagent控制器或任意多Agent运行时 | 原有有界遥测；通用子Agent观测适配尚待 |
| LangGraph 1.2.17 | 尚无专用零代码创建模板 | 自有镜像/源码需适配EAP运行协议；尚未验证EAP托管实例端到端执行 | 显式节点/工具包装已做真实框架验证；同步中断、异步环境限制等见能力清单 |
| AutoGen Core 0.7.5 | 尚无专用零代码创建模板 | 自有镜像/源码需适配EAP运行协议；AgentChat/分布式Runtime不包含在当前范围 | 同一 `SingleThreadedAgentRuntime` 的显式直接RPC处理器/工具已验证 |
| CrewAI、OpenAI Agents SDK等 | 尚无专用零代码创建模板 | 尚未逐框架完成EAP创建/调度验收 | 候选接入点已调研，尚未实现这些适配器 |
| 自有镜像/源码 | 有 `IMAGE` / `SOURCE` 部署入口 | 应用需要实现EAP的身份、任务、健康和控制契约；框架本身不会自动获得治理能力 | 可显式接入事件SDK或匹配版本的适配器，覆盖范围分别验证 |

当前部署服务只接受 `RUNNER`、`IMAGE`、`SOURCE`；其中零代码 `runnerFramework` 白名单为 `EAP_NATIVE` 和 `KAGENT_ADK`，前端相同。仅新增一个框架下拉选项不足以完成接入。[后端规范化入口](../backend/src/main/java/com/eap/platform/deployment/SubstrateDeploymentService.java)、[前端表单契约](../frontend/src/api/substrateDeploy.ts)。

不同框架应共享EAP资产目录、发布记录、实例身份、任务和观测入口，框架差异留在版本化运行适配器内。正式补齐创建能力时，每个框架都需要固定依赖/镜像与配置校验、创建和启动模板、任务/取消/超时协议、受管模型与工具调用、运行身份配送及续期、观测覆盖声明，以及回收和升级验收。可以复用EAP现有运行与治理机制，不能通过包装观测函数自动获得这些能力。

下一步优先为LangGraph与AutoGen选择各一个受控运行模板，先在远程隔离环境跑通“EAP登记→部署→身份确认→派发→工具/子Agent观测→回收”；随后才开放对应控制台选项。先使用确定性的模型/工具夹具，不重放旧UNKNOWN。ADK/Kagent另按实际Go版本适配；发布订阅、handoff、跨实例关系和预算传播需扩展契约。

当前可用的接入代码与界限见[框架适配说明](../runtime/execution-observer/frameworks/README.md)、[能力清单](../runtime/execution-observer/frameworks/capabilities.json)、[事件协议](AGENT_EXECUTION_EVENT_CONTRACT.md)。整体目标模型和其他框架接入点见[跨框架设计](MULTI_AGENT_EXECUTION_OBSERVABILITY.md)，实际测试范围见[验证记录](VALIDATION.md)。
