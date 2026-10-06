# Substrate 长任务边界实测

2026-09-27，在现有 PoC 配置下，30 秒和 300 秒工作流成功；900 秒工作流到达 EAP 总期限后记为 EXECUTION_UNKNOWN，但实例最终完成了任务。直接经路由器的 900 秒请求成功。不能把“路由器允许等待 20 分钟”理解为“EAP 保证 15 分钟任务成功”。

| 路径 | 合成工作时长 | 日志中执行耗时 | 结果 |
| --- | --- | --- | --- |
| EAP → Substrate → 独立 IMAGE actor | 30 秒 | 30.227 秒 | SUCCEEDED |
| 同上 | 300 秒 | 300.151 秒 | SUCCEEDED |
| 同上 | 900 秒 | 900.006 秒 | EXECUTION_UNKNOWN |
| 直接经路由器 → 同一独立 actor | 900 秒 | 客户端 900.109 秒 | HTTP 200，响应匹配 |

## 配置与范围

- EAP read timeout：900000 ms；整条工作流 timeoutSeconds：900（源码默认值，部署无覆盖）；连接超时默认 3000 ms。整条工作流期限从 RUNNING 开始，包含治理、唤醒、任务处理和写入日志；多个步骤共用，不是每步独享 900 秒。
- 路由器实际参数：route-timeout=20m、drain-delay=13s；本轮不改变配置，不重启路由器，不做升级中断实验。
- 通用 LLM Runner 的模型 HTTP 客户端超时是 110 秒，EAP 网关 read timeout 默认 120 秒，属于代码核对。本轮使用纯睡眠 IMAGE Runtime，没有模型请求；结果不证明长时间模型生成或第三方 Agent 都能成功。
- 独立 Agent 12，Workflow 7，实例 eap-agents/a12-v1-d57b2f。复用镜像 `localhost:5001/eap-demo-agent@sha256:0ebe150052cd1fa3176d08d942be080a69a897a6e9a309b68c4363d03c14a902`。未操作原有演示实例。

## 超时与幂等证据

三个任务分别只让 actor 的 tasksHandled 从 0→1→2→3；每次相同 requestKey 重放都返回相同运行和终态，计数不增加。900 秒运行的原因是“执行超过期限，外部效果可能已发生；请按任务标识核对，未自动重试”。脚本等待 actor 计数确认完成之后才清理，最终实例 SUSPENDED。HTTP 超时不等于工作已取消，UNKNOWN 不能直接改成失败并自动重跑。

执行 ID：
- 30 秒：wf-exec-e68e0381-2e82-4f1c-9d73-e92f92cb22cd
- 300 秒：wf-exec-dfedb2ae-4374-4d6a-9d3b-d4a7aff08e7d
- 900 秒：wf-exec-984a6145-1e4b-4304-aaa2-6c9c7cfbe362

## 决策

短任务 PoC 可以继续使用 V1，但应保留充分的整条工作流预算余量。若要求可靠执行接近/超过 15 分钟的任务、跨服务滚动升级继续查询结果或并发调度，应设计异步提交/查询/完成回执、持久化任务状态、幂等与取消合同，再决定采用 EAP V2 或外部执行器。单纯延长 read timeout 不能解决总期限、进程退出或外部效果确认。

EAP 当前执行 worker 使用单线程；长任务会占用队列处理能力，这是代码确认，尚非吞吐压测结论。未测试路由器重启、节点故障、真实长模型生成或更大并发。

证据位于 K8Substrate `artifacts/eap-timeout-20260927/{report.json,config.json,run.log}`。脚本为 `tools/eap/verify-timeout.py` 与 `timeout-config.py`；节点 `/root/eap-timeout-evidence.json` 应保留，失败后先核对在途 actor，再决定后续操作。
