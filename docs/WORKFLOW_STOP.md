# 停止后续调度

EAP 的停止操作适用于加入 `EAP_WORKFLOW_STOP_V1` 协议的新工作流执行，以及由它们驱动的团队计划。它控制平台是否开始下一步骤。

- **排队中**：请求与调度出队共用数据库锁，成功后状态为 `CANCELLED`，不会派发任务。
- **运行中**：持久记录请求，当前已获准步骤仍可能准备、派发、等待回执和回收资源。原回执保存后，在下一节点边界停止。
- **未知结果或责任过期**：显示 `UNCONFIRMED`，保留 `EXECUTION_UNKNOWN` 与原查询责任，不改为成功停止。
- **原步骤失败或执行已结束**：保留真实终态；停止报告显示 `FINISHED`，不声称由请求终止。

`CANCELLED` 表示已停止后续调度，不保证中断了正在运行的进程，也不表示撤销外部效果、退回预算或完成业务补偿。单步骤长任务需要等待它返回；超时、失联按现有未知结果规则处理。AX 步骤仍需等待其原业务终态和沙箱休眠证据。没有自动重放、换键派发、强杀 Runtime 或重建实例。

## 使用

工作流的执行过程和团队计划的执行时间线都有“停止后续调度”面板。按钮使用 `/api/platform/me` 的 `runWorkflows` 能力；读取使用 `viewConsole`。权限仍统一定义于 `PlatformPermission`。停止状态读取失败不会被当作成功，HTTP 回执丢失时先查询原请求；只有用户再次操作才使用原键重试。

```http
GET /api/workflows/{workflowId}/executions/{runKey}/stop
POST /api/workflows/{workflowId}/executions/{runKey}/stop
Content-Type: application/json

{"requestKey":"original-stop-request"}
```

同一执行只允许一个停止意图；相同调用者与请求键返回原意图。更换键或调用者返回冲突，调用者身份比较使用完整主体摘要，显示名截断不影响比较。接受操作仅写一次审计日志，数据库事务回滚时不会留下成功停止记录。

响应包含 `supported`、`mode=AFTER_CURRENT_STEP`、`state`、`executionStatus`、原请求键、申请人、申请/生效时间和 `effectsRolledBack=false`。`REQUESTED` 只证明请求已存储；`APPLIED` 才证明平台已在安全边界停止后续调度。每个节点以 ownerId/ownerEpoch 核对责任，停止生效后旧写者不能覆盖终态。

## 兼容与恢复边界

V94 增加停止版本和独立请求表。历史执行默认 `stop_version=0`，不能用新协议改写；只有新提交的执行设置为1。计划停止后仍冻结原快照和提交键，未开始的步骤显示 `SKIPPED`，已完成的步骤保留回执。重用原计划提交键仅查询原执行，不能重新开始。

停止意图持久化后，即使查询服务重启仍可回读；正在执行的 worker 失联后按原超时规则保留未知责任，不自动恢复业务执行。原有受控工具效果查询继续使用原 operationId 和实例身份。通用外部工具补偿、业务恢复、跨实例接管和立即中断运行任务仍不在本协议内。

## 验证状态

已随 `private-pilot-20261025-workflow-stop-r1` / Schema94 部署。远程完整后端1467项、最终H2停止12与MySQL停止12、最终前端546项及类型/构建通过；8个真实浏览器场景和14个合成状态展示通过。线上只新增控制节点样例工作流并停止为APPLIED/CANCELLED，运行中步骤边界由隔离测试覆盖。详见[验证范围](VALIDATION.md)与[验证范围](VALIDATION.md)。
