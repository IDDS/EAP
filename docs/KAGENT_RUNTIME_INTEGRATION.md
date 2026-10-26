# Kagent 受管运行程序接入

初次接入日期为2026-10-20。EAP将真实 Kagent Go ADK 模型适配器接入 EAP 的可选文本 Runner。创建、版本发布、实例身份、调度入口、模型准入、预算结算与观测仍由 EAP 负责。不是完整 Kagent 平台集成。

控制台入口：**智能体详情 → 部署与运行 → 零代码配置 → 运行框架 → Kagent Go ADK**。原生 EAP Runner 仍可选。新增版本固定镜像摘要；不会更新已有 ActorTemplate 或替换现有实例。

| 能力 | 本轮范围 |
| --- | --- |
| Kagent 官方 Go 模型适配器 + ADK Runner | 已接入固定 alpha14 提交，每次执行使用独立 Session |
| EAP 创建、部署、专属实例身份、控制 | 复用原发布管线；专用实例已实机验收 |
| EAP 文本任务、聊天与显式历史 | 真实 SDK + 合成 HTTP 网关回归通过；后续真实模型/工具样例见验证记录 |
| 版本与执行观测 | 部署保存运行框架、镜像；Runner 状态报告版本，Agent Span 使用已有 `service.version` 白名单 |
| EAP 结果契约、工具治理、独立 Actor 编排 | 保留既有 EAP 实现；不计作 Kagent 同名能力已集成 |
| 一次 `calculate_sum` 框架工具 | 已实现实际 ADK 回调与 EAP 许可/回执；发布验收见下节 |
| Kagent MCP、多轮工具循环、内嵌子智能体 | 未开放 |
| 持久 Session、审批恢复、检查点、压缩 | 未接入 |
| Kagent 控制器、Harness、独立 UI、全套 A2A 服务 | 未部署 |
| 提示词实验 | Kagent Runner 中禁用，原生 Runner 保持原协议 |

执行链路为 EAP 原派发 → 独立 ADK Session → Kagent OpenAI 适配器 → 进程内校验 transport → EAP 原实例身份 transport → EAP 网关。SDK 不获取真实凭证、不直接访问模型供应商、不自动重试；一次任务只允许一次模型转发。模型响应先按 EAP 规则检查。文本模式拒绝工具调用；受控求和模式只接受一个合法的 `calculate_sum` 声明与完整参数。截断、缺失/未知终态不会被 SDK 默认转换为成功。

当前固定版本普通助手文本历史会被上游转换为用户角色。EAP 校验固定转换结果，并将原始角色和内容转发给网关。回归使用实际 Kagent/ADK 代码，验证原系统提示词、原历史、taskKey、traceId、输出上限及思考模式；未知转换拒绝转发。详情和远程构建方法见 [Runner 说明](../runtime/kagent-runner/README.md)。

Kagent alpha14 控制器依赖其 Substrate `v0.5.0-alpha2` 分支，与当前集群快照协议存在差异。本轮 Go ADK 适配器复用 EAP 的现有 Substrate 客户端，不需要切换集群控制面。不能据此推断整套 Kagent 控制器兼容。

新增有界工具模式 `CALCULATE_SUM_V1`：部署冻结声明；最多一个模型请求、一次只读求和；EAP check → claim → 计算 → receipt确认，实际ADK functiontool返回结果后跳过模型总结。调用意图与工具回执分别持久化；原journal目录保留时，进程重启不重放UNKNOWN或失败。网关模型预算和工具领取预算分别沿用现有EAP机制。此模式不是任意工具注册表，也不支持自行增加模型轮次。

本轮[验证范围](VALIDATION.md)已部署并收尾：后端1250、前端473、Kagent Runner56/125及原生48/94竞态、四角色8组浏览器通过。初期专用实例经EAP创建/发布/身份配送与休眠验证；后续真实模型/工具与执行观测验收见[验证记录](VALIDATION.md)。独立[stdio MCP观测实现](../runtime/mcp-observer/observer.py)只采集协议元数据，不替代Kagent MCP工具执行器。后续仍需单独验证远程MCP、跨实例子智能体、持久审批/检查点、恢复和逐轮预算。

官方依据（访问日期 2026-10-20）：[固定发布版本](https://github.com/kagent-dev/kagent/releases/tag/v1.0.0-alpha14)、[模型转换代码](https://github.com/kagent-dev/kagent/blob/v1.0.0-alpha14/go/adk/pkg/models/openai_adk.go)、[模块依赖](https://github.com/kagent-dev/kagent/blob/v1.0.0-alpha14/go/go.mod)。本轮验收记录位于 [验证范围](VALIDATION.md)。

2026-10-26 新增[Go ADK 内部执行观测](KAGENT_EXECUTION_OBSERVATION.md)：接入真实 Agent/工具生命周期回调及原身份的有界补传。子调用适配与零代码创建能力分开声明；下游没有新增任意子 Agent 配置或模型轮次。发布与测试情况见[验证记录](VALIDATION.md)，前述 2026-10-20 验收数字保留为历史。

Go ADK 观测器的中央投递状态已接入既有报告协议，回执丢失按原报告序号有界重发，报告确认不替代事件确认。控制台复用执行关系中的投递面板；具体限制见[内部执行观测](KAGENT_EXECUTION_OBSERVATION.md)，实际部署与验证结果见验证记录。
