# Team/Plan 真实执行

2026-09-27：计划可以提交到 EAP 工作流引擎，每步骤支持选择 RUNTIME 或 AX 执行后端。执行模式为串行；coordinationMode、workspacePolicy 和 requiredCapability 是冻结的上下文/声明，不能替代实际治理决策或证明沙箱权限已按这些字符串实施。

## 操作

1. 创建团队并登记成员。在团队页面点击“新增计划”，填写目标（1–128 字）与上下文（最多 1024 字），创建 READY 计划。
2. 点击“新增步骤”填写顺序、任务与所需能力（后两项各 1–128 字）。选择执行后端（默认 RUNTIME；AX 使用模型任务并自动休眠）。添加 1–62 个步骤；stepOrder 必须是互不重复的正整数。将每步分配给本团队成员，状态为 ASSIGNED（或已有分配的 PENDING）。运行状态由引擎回写，不能通过 assignment 手工设为 RUNNING/DONE。
3. 在团队页面选择计划，检查成员分配，点击“执行计划”。仅按 /me 的 runWorkflows 能力开放操作。需要完成后休眠时，明确勾选专用实例选项；共享 actor 不应勾选。
4. 查看执行进度和步骤证据；页面轮询与手动刷新均可。计划、步骤和生成的工作流提交后不可再编辑，也不能通过工作流接口独立重复派发。

## API

`POST /api/orchestration/plans/{planId}/executions`，JWT 需要 PlatformPermission.RUN_WORKFLOWS：

```json
{"requestKey":"plan-123","context":{"ticket":"synthetic"},"suspendAfterTask":true}
```

返回 `{planId, workflowId, execution}`，execution 使用现有工作流执行 DTO。`GET /api/orchestration/plans/{planId}/execution` 返回当前日志并同步计划投影，未提交时 data=null；计划详情/列表增加 executionWorkflowId、executionRunKey。

每个 Plan 只允许一次真实执行。相同 requestKey 与 context/suspendAfterTask 返回原运行，跨具备执行权限的调用者也不会重复派发。换 key 或参数返回 409，包括已结束/失败/未知的计划；需要重做时核对外部效果后另建计划。本版本没有原地失败恢复、补偿或取消。

提交持有 Plan 行锁；步骤添加/分配也持同一锁。生成工作流、冻结快照、入队和 Plan 关联在单个事务中提交；Agent 未启用、Runtime 不可用或配置无效时全部回滚，不留下可执行的孤立快照。V49 增加关联字段、唯一约束及索引。

## 执行合同

- 按 stepOrder 排序，生成 START → step-<id> → … → END。
- 每步的 taskObjective 等于步骤标题；整体目标、原上下文摘要、团队成员角色与责任放在只读 eapPlan 上下文中。提交者不能提供 eapPlan / eapPreviousResults 保留字段。
- 后一步的 inputFrom 自动指向前一步，依赖真实 output。第一步不附上游结果。
- 继续沿用 Runtime 认证、派发前治理检查、输出/请求大小限制、结果未知不自动重试、可选成功后休眠；详见 WORKFLOW_EXECUTION.md。
- 普通元数据隐藏 output。原始结果仍通过 `/api/workflows/{workflowId}/executions/{runKey}/outputs` 读取，需要 VIEW_CONTENT；团队页面提供“查看步骤结果”，仅 viewContent 能力可用；主动加载，按纯文本呈现，隐藏、切换计划、身份/权限变化或离开页面时清除，不存浏览器持久存储。

## 状态与边界

后台投影每 2 秒扫描最多 100 个 QUEUED/RUNNING 计划，读取持久工作流日志并回写；进程重启后继续扫描，无需页面触发。计划详情读取也会刷新投影。

| 工作流 | Plan | 已派发步骤 | 未派发步骤 |
| --- | --- | --- | --- |
| QUEUED / RUNNING | QUEUED / RUNNING | DISPATCHING→RUNNING，SUCCEEDED→DONE | ASSIGNED |
| SUCCEEDED | COMPLETED | DONE | 不应有剩余任务 |
| FAILED | FAILED | 保留真实成功/失败状态 | SKIPPED |
| EXECUTION_UNKNOWN | EXECUTION_UNKNOWN | DISPATCHING/UNKNOWN→EXECUTION_UNKNOWN | SKIPPED |
| BLOCKED / WAITING_APPROVAL | 同名 | 保留步骤治理状态 | SKIPPED |

休眠失败时可能出现 Plan FAILED、已完成步骤 DONE：任务结果已保存，但资源释放未确认，不能把已完成任务重跑。全局治理在 START 阻断时，所有业务步骤都为 SKIPPED，具体阻断原因在执行日志的控制节点中。

这一轮不包含并行 fan-out/fan-in、自动选 Agent、能力签名校验、共享 actor 排他调度或生产级吞吐保证。前端可填写计划目标/上下文与步骤顺序/任务/能力，保存后分配成员；本版本不提供已保存目标/步骤文本的修改或删除。

## AX 混合编排

新增步骤请求可传 executionBackend=AX；省略时保持 RUNTIME。后端选择随步骤冻结到生成工作流，支持 Runtime → AX → Runtime 的串行结果交接。AX 使用独立任务沙箱，总是自动休眠；原生 Runtime 休眠仍受 suspendAfterTask 控制。工作流执行时间线显示 AX 任务标识与详情链接。新生成节点按执行顺序纵向排列。

AX 未启用或分配 Agent 不在 ACTIVE 状态时，计划提交整体回滚。AX 任务失败、治理阻断或结果未知时停止下游；原工作流总期限和不自动重放规则不变。详见 AX_WORKFLOW_INTEGRATION.md。
