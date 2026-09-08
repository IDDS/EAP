# 工作流真实执行（EAP_HTTP_TASK_V1）

本地实现日期：2026-09-08；默认关闭，尚未部署或接入真实业务 Runtime。此协议是 EAP 自有的同步 HTTP 任务合同，不是 A2A、MCP 或既有 `/eap/control` 的兼容保证。安全预演仍使用原 `/runs` 接口。

## 启用与接入

1. 执行数据库迁移至 V42；V41 保存执行快照与逐节点日志，V42 增加提交幂等键及节点编辑版本。
2. 为 ACTIVE Agent 注册 `protocol=EAP_HTTP_TASK_V1` 的 Runtime，endpoint 填完整任务接收 URL，并持续发送心跳（默认有效期 180 秒）。
3. 配置 `EAP_WORKFLOW_EXECUTION_ENABLED=true`、`EAP_WORKFLOW_EXECUTION_ALLOWED_HOSTS`（逗号分隔的精确主机名）、`EAP_WORKFLOW_EXECUTION_TOKEN`（仅服务端保存）。Runtime 必须验证该 Bearer token。
4. 默认只允许 HTTPS 和公开地址。内部部署需要显式启用 `EAP_WORKFLOW_EXECUTION_ALLOW_PRIVATE_NETWORKS=true`；本地 HTTP 联调还需 `EAP_WORKFLOW_EXECUTION_ALLOW_INSECURE_HTTP=true`。禁止重定向、URL 内凭证、query 和 fragment。白名单不是任意 URL 代理授权，部署时仍需限制出口网络和受信 DNS。

`EAP_WORKFLOW_EXECUTION_TIMEOUT_SECONDS` 默认 900 秒，从 worker 领取任务起计算，不含排队时间。`eap.workflow.execution.connect-timeout-ms` 默认 3000，`read-timeout-ms` 默认 10000，`max-response-bytes` 默认 65536。单次请求同时受连接/读取预算与执行剩余期限约束。

## Runtime 请求与响应

EAP POST 到登记的 endpoint，附 `Authorization: Bearer <server token>` 和 `Idempotency-Key: <taskKey>`。示例正文：

```json
{
  "protocol": "EAP_HTTP_TASK_V1",
  "taskKey": "wf-exec-<uuid>:1",
  "workflowRunKey": "wf-exec-<uuid>",
  "agentId": 123,
  "nodeKey": "diagnose",
  "nodeType": "AGENT",
  "capability": "diagnose",
  "objective": "诊断测试任务",
  "context": {"severity": "high", "approved": false},
  "configJson": "{\"mode\":\"audit\"}"
}
```

`configJson` 是提交时节点配置的原始 JSON 字符串。Runtime 应按 `taskKey` 去重，并且只有任务实际完成后才返回 HTTP 200：

```json
{"taskKey":"wf-exec-<uuid>:1","status":"SUCCEEDED"}
```

明确失败可返回 `status=FAILED`。HTTP 202、普通 2xx ACK、RUNNING、错误任务标识、超限/无效响应或网络中断均不能证明完成，执行进入 `EXECUTION_UNKNOWN` 并停止后续节点。EAP 不自动重试，不自动回滚外部效果，不宣称恰好执行一次；Runtime 的去重能力必须单独验证。日志不保存 Runtime 原始响应正文。

## 控制台与 API

- `GET /api/workflows/execution-status`：是否具备启用配置。
- `POST /api/workflows/{id}/executions`：提交 `{ "objective": "...", "context": {...} }`，返回 QUEUED 和 runKey；JWT 下需 OPERATOR 或 PLATFORM_ADMIN。
- `GET /api/workflows/{id}/executions`：最近 20 次执行。
- `GET /api/workflows/{id}/executions/{runKey}`：执行状态与节点证据。

真实执行请求可追加 `requestKey`（1–64 位字母、数字、短横线或下划线）。相同工作流、认证身份、requestKey 和 objective/context 返回原执行，包括已结束记录；相同键改变参数返回 HTTP 409。服务端在工作流行锁内查重，并使用数据库唯一索引兜底。请求的上下文字段排序不影响服务端指纹；重试返回原快照，不因工作流后续修改而重新执行。遗漏 requestKey 的旧客户端仍每次创建新执行。

新版控制台自动附加 requestKey；超时或其他失败保留标识，同一标签页刷新后也可复用；成功确认后释放标识，下一次明确点击创建新执行。改变目标/上下文或切换工作流视为不同提交。待确认标识及匹配参数保存在 sessionStorage，不含认证令牌；浏览器禁用存储时仅在当前页面内保留，刷新后需先查询历史核对。新标签页、清理浏览器存储或旧客户端自行换键不受原提交去重保护。此机制只防止创建重复执行，不保证 Runtime 外部效果恰好一次。

控制台显示近期记录和运行中轮询；真实执行入口与安全预演分开。

## 执行边界

支持至多 64 节点、256 连线、60KB 快照；提交时解析单一路径并冻结节点、配置与上下文。支持 START、END、DECISION、AGENT、ARTIFACT；任务节点必须绑定有效 Agent。循环、歧义、未知或缺少条件输入拒绝。分支只依赖提交上下文，不接收上游 Runtime 输出作为新分支变量。

每个节点经过安全判断；任务判断包含实际 Runtime URL，以及 objective/context/config。ASK 或 DENY 均不会派发该节点。WAITING_APPROVAL 目前没有恢复接口，治理处理后仍需核对已完成节点，再显式创建新执行；用户填写 approved 不代表治理授权。Runtime 内部的实际工具/数据/制品访问仍必须实施自己的授权与治理，本协议无法证明下游行为合规。

先持久化 DISPATCHING 再发 HTTP；网络调用不占数据库事务。每次进度更新检查执行期限，超时后不继续节点；进程重启后在下一次检查将遗留超时 RUNNING 标为 UNKNOWN。迟到结果不能覆盖终态。取消、异步回调/查询、补偿、失败恢复和动态输出传递不在本版本内。

本地 HttpServer 回归覆盖匹配终态、配置快照、实际地址安全检查、非终态/错标识、失败/拒绝、超时、失联恢复和禁止自动重试；尚需目标 MySQL、真实 Runtime 与浏览器验收。


## 节点编辑版本（V42）

节点读取和保存响应增加 `version`；更新时传 `expectedVersion`。版本不匹配返回 HTTP 409，原记录不变。新版控制台在拖动和属性保存时均携带版本，成功后只更新本地版本，不覆盖保存期间继续输入的内容；冲突时保留本地草稿，可先复制草稿，再点击“重新加载流程”读取最新内容。单纯点击节点不再触发位置保存。

创建节点通常不传 expectedVersion。为兼容旧客户端，更新时省略该字段仍允许写入；因此版本保护要求编辑客户端使用这个字段，不等于所有旧集成都已防止覆盖。版本控制目前针对节点，不提供整个工作流的多人实时合并。
