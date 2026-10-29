# 跨实例临时代码交接样例

这个可选镜像用于验证：LangGraph 生成的固定程序，经 EAP 工作流交给另一个实例的 AutoGen Core 短进程执行；进程退出后，来源与接收实例仍可确认原执行关系。默认框架镜像和现有 Agent 不随此样例替换。

## 执行路径

| EAP 节点 | 实例 | 动作 |
| --- | --- | --- |
| `generate` | source | LangGraph 1.2.17 的 `temporary-generator` 生成固定三数求和代码，捕获摘要并删除临时文件；确认 5 条执行事件后退出 Python 进程。 |
| `execute` | target | 接收 `generate` 的输出；经 EAP `calculate_sum` check/claim 后，用 AutoGen Core 0.7.5 的 `handoff-receiver` 调用 `ephemeral-executor`，执行捕获的确切字节；确认 9 条事件后退出进程，并提交工具回执。 |
| `offer` | source | 接收 `execute` 的输出；运行实例观察器核对自己保存的原结果，持久化两条不可变意图并上报 SOURCE。 |
| `confirm` | target | 核对原执行结果，上报相同 `DELEGATE`、`CODE_HANDOFF` 的 TARGET；后台分别校验原运行、身份和代码元数据。 |
| `resend-source` / `resend-target` | 各自实例 | 可选：仅重发已保存的相同意图，证明重复报告不会重新执行代码。 |

前两步是真实业务，后几步是实例观察器对已结束进程的补充观测，不声称已经退出的进程仍在发送消息。每个业务动作在执行前落下 `INTENT`，只有完成后保存原结果。中断或回执不确定时拒绝重新执行；工具层也保留原有未知状态。没有自动恢复或自动补偿。

## 接入 EAP

新版[默认框架镜像](../framework-runner/README.md)已复用本样例的交接实现：通过 `TEMPLATE` 部署 `LANGGRAPH_SUM_V1` 与 `AUTOGEN_SUM_V1`，提交工作流时设置 `context.eapHandoff={"protocol":"EAP_CODE_HANDOFF_V1"}`。来源/接收角色由框架入口固定。以下独立 `handoff` 镜像入口继续兼容；它不需要这一默认模板选择标记。

远程构建 `runtime/agent-runner`，使用 `handoff` build tag，并分别固定 `-X main.handoffRole=source`、`target`。镜像以经验证的固定框架镜像为基础，加入两个二进制、`handoff_worker.py`、`eap_execution.py`、`eap_links.py`。依赖、编译和测试都在远程执行。

通过 EAP 创建两个 Agent，使用 `IMAGE` 部署同一摘要镜像，命令分别为 `/usr/local/bin/eap-handoff-source` 和 `/usr/local/bin/eap-handoff-target`，端口 80、就绪探针 `/readyz`。必须使用 EAP 自动配送的运行身份；凭证只由父进程通过 stdin 传给子进程。不给任务开放镜像、路径、命令、任意程序、模型或网络地址选择。

工作流使用上表的固定 `nodeKey` 和 `executionBackend: RUNTIME`。`execute` 的 `inputFrom` 为 `["generate"]`，`eapTool` 为 `{"name":"calculate_sum","arguments":{"numbers":[10,20,12]}}`；所有确认/重发节点的 `inputFrom` 为 `["execute"]`，不配置工具。两实例模型预算为 0，仅 target 允许领取一次 `calculate_sum`。样例输出为 42；执行许可来自工具治理，关联接口不授予任何执行权限。

## 能力边界

- 两个独立 producer/stream/Trace，通过双方确认的明确引用关联；不靠 PID、名称或相同代码摘要推断因果。
- 执行事件和关联只保存代码摘要、语言、大小。这个样例的 **EAP 工作流业务输出包含固定公开程序的 Base64**，用于真实传递代码；读取沿用业务内容权限。它不是任意敏感源码归档服务。
- 可执行内容必须逐字等于内置固定程序，输入恰好三个有限数。Python `exec` 的限制不是通用沙箱保证；不能据此允许任意生成代码。
- 样例自己的私有 outbox 由运行实例持有，固定原 producer 和身份代际；它不绕过 SDK 对重开 Journal 创建新意图的限制。只有首次 `offer`/`confirm` 可固定两条关联，后续只重发相同内容。更换身份、任务输入或关联内容都会拒绝。
- 业务完成但事件投递未确认时保留不确定状态，不重新运行。当前没有独立后台恢复这个样例的未完成阶段；需要按原执行记录调查。
- 这是指定 LangGraph/AutoGen Core 版本的显式接入样例，不是所有框架、任意程序或 eBPF 的全自动语义追踪。

远程实际验收使用 Agent 116/117、Workflow 58，全部创建、部署、调度与回收均走 EAP。四角色桌面/手机控制台可读取双方确认并双向跳转，实例休眠及凭证撤销后证据仍可查。完整结果与失败修正记录见[验收证据](../../docs/evidence/handoff-sample-20261025/README.md)；不要重放该已完成工作流来代替读取记录。
