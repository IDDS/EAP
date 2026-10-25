# 实验入口的观测完整性

实验执行结果、费用结算和观测覆盖是三个独立事实。此接口只查询已保存证据，不执行模型、不签发或续期实例凭证，也不补写旧实验。

`GET /api/agent-experiments/{id}/telemetry` 复用 `PlatformPermission.VIEW_CONSOLE`。四个控制台角色都可读取元数据；提示词、样本、输出仍由已有的正文接口和内容权限控制。返回实验编号、登记试验数、完整试验数，以及每条原试验的 Trace、执行状态、`agentSpans`、`modelInvocations`、`successfulModelInvocations` 和覆盖状态。模型网关的事实保存在 `model_invocation_events`，仅统计认证网关 `GATEWAY` 来源；调用事件不是 OTLP 模型 Span，不重复上报或回填模型 Span。

只有当前注册记录的 Agent、实例键、端点、Actor UID 和模板与实验冻结身份匹配，且注册支持实例身份，才查询 `runtime-{registrationId}` 下的原 Trace。`FROZEN_REGISTRATION_MATCH` 表示保存的注册记录匹配，并非本接口检查了实时原生 Actor。注册缺失、重建或解绑返回 `IDENTITY_UNCONFIRMED`，数量为 null，不补零。

| coverage | 含义 |
| --- | --- |
| COMPLETE | 原试验 SUCCEEDED，同一身份及 Trace 下恰好一条 `agent.experiment` AGENT Span 和一条成功的 GATEWAY 模型调用事件 |
| MISSING_AGENT / MISSING_MODEL / MISSING_BOTH | 成功回执对应的观测证据缺失 |
| DUPLICATE_EVIDENCE | 对应 Agent Span 或网关调用事件超过一条，保留实际数量 |
| MODEL_STATUS_UNCONFIRMED | 原试验成功，但对应网关调用事件没有确认成功 |
| EXECUTION_UNCONFIRMED | 原执行 FAILED 或 UNKNOWN；Span 存在不会改成执行成功，也不能据此推断费用为零 |
| PENDING / NOT_EXPECTED | 排队或进行中且尚无观测证据的原试验 / 已取消且没有观测证据的原试验 |
| UNEXPECTED_EVIDENCE | 未成功且非 FAILED/UNKNOWN 的试验出现观测证据，保留实际数量 |
| IDENTITY_UNCONFIRMED | 保存的身份不能与冻结实验相互印证，不推断完整性 |

分母始终包含全部原登记试验；缺失和未知不剔除。其他 Agent、其他实例端点或其他 Agent 操作的 Span 不能补齐本实验。

未来发布的实例 Runner 对首次新执行，在业务回执持久化之后发送一次 `agent.experiment` OTLP Span。Span 起止时间覆盖模型执行边界，已有模型 latency 不包含观测导出耗时。仅携带 Agent、实例键、任务键、执行状态和时间，不携带提示词、输入输出、usage 或凭证。只使用实例凭证访问 `/api/runtime-access/v1/traces`，凭证仅在 HTTP Header；禁止跳转，导出最多等待 2 秒，不重试、不输出原始错误。

相同原键/参数返回保存的回执，既不重跑模型也不重发 Span；保存的 UNKNOWN 意图也不执行或导出。导出失败或落盘之后的进程退出可能留下缺口，接口如实显示缺失；没有持久导出补偿队列。仅更新未来发布的 Runner 默认镜像，旧 UID、模板、实验及已结算费用保持。

该能力是 T7 实验入口的一项有界实现。它没有实现全入口采样、全局日志/指标关联、告警或完整风险分级观测策略。
