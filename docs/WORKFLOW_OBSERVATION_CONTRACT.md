# 工作流模型观测约定

2026-10-20。普通 Runtime 任务现在可以明确声明“预期 1 次模型调用”或“不预期模型调用”。约定用于判断原任务的证据是否齐全；它不会禁止模型调用，也不证明业务效果。未声明时仍按原规则预期 1 条 Gateway 模型事件。

已部署至远程 PoC。后端完整 1,277 项、隔离 MySQL 15 项、前端 479 项及类型/构建通过；真实 Agent79 / Workflow33 的模型 0/0、Agent 1/1 和四角色桌面/手机验收通过。完整范围与原始证据见[本轮验收记录](evidence/observation-contract-20261020/README.md)。

## 控制台使用

1. 进入“编排与执行 → 工作流编排”，选择流程和已绑定智能体的 `AGENT` 或 `ARTIFACT` 节点。
2. 在“模型 / 自定义任务”下设置“模型调用观测”。
3. 保存节点后提交新的执行。未保存的配置不能用于真实执行。

| 选项 | 保存的声明 | 模型事件预期 |
| --- | --- | --- |
| 默认（预期 1 次） | 不保存 `eapObservation` | 1 |
| 预期 1 次模型调用 | `modelEvents: "ONE"` | 1 |
| 不预期模型调用 | `modelEvents: "NONE"` | 0 |

只有 `/api/platform/me` 返回 `runWorkflows` 能力的用户可修改。接口授权仍由 `PlatformPermission` 定义，前端不解析 JWT 或自行判断角色名。

应根据已部署程序的实际行为选择。默认文本 Runner 仍会请求模型；选择“不预期模型调用”不会改变 Runner 的执行方式，也不会取消预算或工具授权检查。

## JSON 合同与范围

```json
{
  "executionBackend": "RUNTIME",
  "eapObservation": {
    "version": "EAP_OBSERVATION_V1",
    "modelEvents": "NONE"
  }
}
```

`executionBackend` 可以省略，含义仍为普通 Runtime。声明仅用于已绑定智能体的 `AGENT`、`ARTIFACT` 节点，不适用于 AX、内置 `eapTool`、控制节点或未绑定节点；即使 `eapTool` 为 `null` 也不接受。独立聊天、实验、AX 和内置工具的观测规则保持原有行为。

声明必须恰好包含 `version` 和 `modelEvents`，大小写及版本必须匹配。后端拒绝重复 JSON 字段、尾随 JSON、未知字段、非法类型和不支持的版本。保存节点与执行准入都会检查，避免数据库中的非法草稿绕过准入。

切换到不支持的执行方式时，界面保留原声明并提示错误；用户可选“默认（预期 1 次）”显式移除。不会静默删除声明或把非法值解释为 0。

## 冻结时间与证据判定

约定随执行提交时的原节点配置快照冻结，模型预期值在同一事务登记到 `observation_operations.expected_model_events`。未到达的任务节点也保留原登记；执行失败、取消或未知不会删除分母。同一个提交 `requestKey` 查询或重放仍返回原执行，修改当前草稿不会改变它。

`NONE` 保留以下要求：

- 原实例、Agent、Actor UID 等登记身份必须匹配。
- 原任务回执中的 Trace 必须确认。
- 仍预期 1 条同身份的 Agent Span；模型计数为 0 不能代替 Agent 执行证据。
- 实际收到 Gateway 模型事件时保留原记录，并显示 `UNEXPECTED_MODEL`，不会隐藏或忽略该调用。

| 原任务证据 | 在身份及回执已确认等条件满足时的判断 |
| --- | --- |
| `NONE`、Agent Span 为 1、模型事件为 0、原任务已结束 | `COMPLETE`，界面模型计数为 `0 / 0` |
| `NONE`、缺 Agent Span | `MISSING_AGENT` |
| `NONE`、收到模型事件 | `UNEXPECTED_MODEL` |
| 默认或 `ONE`、Agent Span 为 1、无模型事件 | `MISSING_MODEL` |
| Actor 身份或回执 Trace 不匹配 | 原有未确认状态，不能因模型预期为 0 显示完整 |

Gateway 模型事件仍是模型计数依据，OTLP `MODEL` Span 不替代它。`COMPLETE` 表示登记所需的证据齐全，不表示全部工具成功、模型从未在未接管的外部入口运行，或业务效果经过验证。

## 历史兼容与限制

本次不迁移数据库、不回填旧登记。上一轮 Workflow31 原任务的模型预期仍为 1，因此保留 `MISSING_MODEL`；其工作流 `FAILED` 和 `SKIPPED_BUSY` 自动回收结果也保持。不能事后给旧执行补上 `NONE` 来改写验收结果。

当前只支持预期 0 或 1 次，尚无多轮模型数量范围、条件分支内动态调用数量或任意自定义证据合同。这也没有增加 MCP 启动配置、HTTP/SSE、eBPF、Kagent MCP 执行或自动回收能力。相关采集能力见 [MCP 观测](MCP_OBSERVATION.md)，基础登记规则见 [Runtime 观测覆盖](RUNTIME_OBSERVATION_COVERAGE.md)。
