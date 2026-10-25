# EAP 管理的 Kagent Go ADK Runner

这是可选执行程序：使用 Kagent 官方 `go/adk/pkg/models.OpenAIModel` 和 Google Go ADK Runner 执行文本轮次。EAP 仍负责发布版本、Actor 创建、身份配送、派发、模型准入、预算与观测。它不是完整 Kagent 控制器、Harness 或持久 Session 服务。

固定来源：Kagent `v1.0.0-alpha14`，提交 `673d181ec26960f5ab0ddfe7a7c58f07433d1014`；Go 模块版本 `v0.0.0-20261016134445-673d181ec269`。上游要求 Go 1.27。模块与校验和独立保存于本目录；普通 EAP Runner 不引入这些依赖。

远程构建时在 `runtime/agent-runner` 执行：

```sh
go test -modfile ../kagent-runner/go.mod -tags kagent -p 1 -race .
CGO_ENABLED=0 go build -modfile ../kagent-runner/go.mod -tags kagent -trimpath -o eap-kagent-runner .
```

按项目约定，以上命令只在远程集群运行。大型 SDK 的首次编译需要限制编译器内存，本轮最终通过的配置为 `GOMAXPROCS=2 GOMEMLIMIT=900MiB GOGC=30`；实际验证结果见本轮验收记录，不把构建配置当作容量证明。

后端仅在 `EAP_SUBSTRATE_KAGENT_RUNNER_IMAGE` 配置不可变 `@sha256:` 镜像且身份配送可用时报告 `kagentRunnerAvailable`。创建零代码部署时选择 `runnerFramework: KAGENT_ADK`。原生选项保持旧请求形状和幂等关系；已受理的 Kagent 请求在配置停用后仍可查询原回执。已有版本不会随默认镜像变化。

每次执行创建新的内存 Session，只读取调用者显式提供的合法历史。SDK 通过进程内 transport 调用 EAP 原生网关 transport，一次任务最多一次模型请求，无 SDK 重试。SDK 的 `.invalid` URL 不会解析或访问网络；真实实例凭证不进入 SDK。输出先按 EAP 严格终态规则检查，再交给 ADK 处理；无法确认的结果保留 UNKNOWN。

alpha14 的普通文本历史转换会把 assistant 角色投影为 user。适配层验证这一固定版本的投影，同时向 EAP 网关发送原始角色和内容；未知转换会在转发前被拒绝。系统提示词的 SDK 去空白投影也不会修改原始网关输入。升级上游必须重新跑真实 SDK 的历史与错误边界回归。

`/eap/state` 报告 `runnerFramework`、`engineVersion` 和 `EXPLICIT_V1`。EAP Agent Span 的 `service.version` 记录 Kagent 版本，模型用量继续以网关事件为准，不另造用量。

新增可选 `runnerToolMode: CALCULATE_SUM_V1`（未选择则保持文本模式）。只支持零代码 `RUNNER + KAGENT_ADK`；工具声明随部署冻结，环境变量由平台生成。实际 ADK functiontool 可执行一次只读 `calculate_sum`，最多 100 个有限数值，绝对值不超过 1e12。先由 EAP 检查并领取工具许可，计算后确认回执，再向调用方返回工具结果。`SkipSummarization` 终止框架后续模型请求，一次任务仍最多一次模型转发。

调用前持久保存原实例、任务/Trace及输入摘要的意图；相同成功请求可读取已保存结果，冲突拒绝，UNKNOWN/失败不重新派发。工具回执不明时保留UNKNOWN，不能把计算完成当作EAP已确认。Span记录 `KAGENT_COMPLETED`，供应商实际finish由网关持有，不编造第二次模型stop。

本轮有界工具的发布状态见[验收记录](../../docs/evidence/kagent-tools-20261020/README.md)。测试使用真实SDK及合成网关，不能作为真实供应商选择工具的证据。当前仍不支持框架内多轮工具循环、MCP、子智能体、持久审批恢复、检查点、压缩或提示词实验。独立MCP被动观测原型不属于Kagent的MCP执行集成。

官方来源（访问日期 2026-10-20）：[发布版本](https://github.com/kagent-dev/kagent/releases/tag/v1.0.0-alpha14)、[模型适配器](https://github.com/kagent-dev/kagent/blob/v1.0.0-alpha14/go/adk/pkg/models/openai_adk.go)、[Go 模块](https://github.com/kagent-dev/kagent/blob/v1.0.0-alpha14/go/go.mod)。上游采用 Apache-2.0；发布时保留依赖许可证，模块清单不替代逐依赖审计。
