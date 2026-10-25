# 原任务的运行位置证据

EAP 在已登记的 Substrate 工作流任务派发时、收到任务回执后，请求后台采样并保存运行位置。任务工作台的关联证据和“证据覆盖与定位”的原操作详情共用只读展示。位置来自 Substrate Control API，与原任务绑定核对；不代替 MCP 协议结果、任务回执或外部工具效果证明。这里记录的是 Actor 宿主位置，不是远端 MCP 服务或工具实际执行机器的位置，不能从 MCP 服务名或网络地址推断后者。

2026-10-21 已部署并完成验收：后端1311项、隔离MySQL9项、前端483项通过；新Agent86的一次任务自动保存两条采样，四角色与两种尺寸浏览器通过。[完整验收记录](evidence/runtime-placement-evidence-20261021/README.md)。

## 时序与边界

1. 现有身份、就绪及派发登记完成并提交后，向后台队列提交 `DISPATCH_REQUESTED` 请求，然后继续原 HTTP 派发。
2. 原 HTTP 调用返回、既有任务回执登记完成后，提交 `RECEIPT_RETURNED` 请求。
3. 后台线程先核对原登记中的 Agent、Runtime、instanceKey、endpoint、Actor UID、模板、原 Trace 及派发事件，认领对应阶段。事务结束后查询 Control API，再单独提交采样结果。
4. 查询沿用[当前位置接口的一致性检查](RUNTIME_PLACEMENT.md)。若登记已换成新 Actor UID 或模板，则保存 `UNKNOWN / ORIGINAL_IDENTITY_MISMATCH`，不借用替代实例的位置。

这是异步采样，查询可能晚于触发事件或任务结束。`requestedAt` 是请求采样时间，`observationStartedAt` / `observedAt` 是实际查询时间，均不是上游迁移事件时间。两次位置相同也不能证明整个任务期间没有迁移；本实现不提供连续位置轨迹。

采样使用 2 个后台线程和最多 32 个排队请求；队列满直接拒绝，不在派发线程执行查询。每阶段最多采样一次，不重试业务任务、不恢复采样、不补采历史。上游查询沿用最多 5 秒的共享截止时间。短数据库事务与网络查询分开。

队列驻留内存，进程中断或拥塞可能丢失尚未认领的请求。此实现不是持久任务队列；无需位置证据也可以继续业务。已认领后中断会留下“采样未完成”，不会把恢复后的当前位置填成历史位置。长稳、峰值吞吐和进程崩溃实测尚不属于本轮范围。

## 持久化与读取

V88 新增 `observation_runtime_placements`，以 `(operation_id, phase)` 为主键，外键关联原操作。迁移不回填旧数据。每阶段先写请求时间，再仅在 `snapshot_json IS NULL` 时写一次结果；重复请求和重复完成不能覆盖原采样。

现有原操作详情和工作流范围详情增加 `placements` 数组，不增加独立权限或新的位置写接口。服务先检查父操作可见性，再读取记录；前端沿用 `/api/platform/me` 能力。GET 不查询当前 Runtime、不唤醒 Actor，也不登记采样。

| 字段 / 状态 | 含义 |
| --- | --- |
| `phase` | `DISPATCH_REQUESTED` 或 `RECEIPT_RETURNED` |
| `requestedAt` | 后台请求创建时间 |
| `captureState: RECORDED` | 已保存一个位置查询结果；结果仍可能是 UNKNOWN |
| `captureState: INCOMPLETE` | 已认领，但未保存查询结果，可能正在处理或已中断 |
| `snapshot` | 位置接口的快照结构，或尚未完成时为 null |
| `placements: []` | 没有保存的位置证据，不能推断当时未运行 |

快照 `ASSIGNED`、`UNASSIGNED`、`UNKNOWN` 的区别见位置接口合同。界面仅在来源为 `SUBSTRATE_CONTROL_API` 且状态为 `ASSIGNED` 时展示节点和 Pod；未知、未完成及缺失不会复用其他任务的坐标。查询异常只保存固定原因，不保存上游错误正文或凭证。

实例后续暂停、凭证撤销或绑定替换，不修改已保存的历史采样。原 coverage 分类和事实摘要仍按原有 Agent / 模型 / 回执证据计算；位置采样不改变业务结果、不把原 UNKNOWN 判为成功，也不将两个位置采样算成 Agent Span。

## 范围

覆盖经过既有登记和派发链路的普通 Substrate `RUNTIME_TASK`。不自动扩展到外部 HTTP Runtime、对话、AX、独立工具入口或既有历史任务；不部署 OBI/eBPF，不更换默认 Runner。

本轮已验证：真实 HTTP 派发与慢查询隔离、异常不重派、重复与并发认领、原绑定替换、旧记录不回填、H2 / 隔离 MySQL、四角色元数据读取，以及远程浏览器的桌面和移动布局。随后已部署[采样投递与失败指标](PLACEMENT_SAMPLING_METRICS.md)，提供固定阶段/事件计数、队列水位及只读 API。指标不恢复内存队列，也不把缺失采样变成历史位置；长期容量、持久采样投递、迁移事件流、更广 OBI 规则、TLS/OAuth、Kagent MCP 执行与恢复仍待验证。
