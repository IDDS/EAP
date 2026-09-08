# 系统审查第二轮改进

> 本文记录第二轮状态；第三轮已补真实工作流协议、首页读写分离、详情分页和校准历史，最新实现及验证见 [第三轮报告](SYSTEM_IMPROVEMENTS_ROUND3_2026-09-08.md)。

日期：2026-09-08。承接 `SYSTEM_REVIEW_2026-09-08.md`；本地修改，未提交、未部署。

## 结果

管理 ROI、Runtime 心跳、注册令牌并发、网关响应边界、历史查询和任务状态保护已修复。工作流已消除“检查通过即执行成功”的语义，现为明确的安全预演。真实工作流执行闭环仍待对接任务完成协议，不能将本轮结果视为该能力已实现。

完整后端 `mvn -q test`：159 tests，0 failures、0 errors、0 skipped。前端 `npm test`：16/16；`npm run build`、`git diff --check` 通过。H2 Flyway V1–V38 通过，无新迁移；未做线上 MySQL、实际业务 Runtime 和真实浏览器全站验收。

## 接口和行为变化

### 财务币种

`GET /api/management/summary?hours=24&currency=CNY` 只用 CNY 模型成本与 CNY 确认收益计算金额和 ROI。任务和知识指标仍覆盖整个时间窗口，页面已说明该口径。省略 currency 时单币种自动识别；混币返回 `currency=null`、`mixedCurrencies=true`、`availableCurrencies`，整体和 Agent 的金额与 ROI 均为 null，前端提示选择币种。没有引入汇率或修改历史金额。

客户端必须处理 nullable 财务字段，不能将 null 转成 0。管理工作台已同步币种选择、财务趋势、排行、决策提示和空值展示。本轮仅修复管理 ROI 口径；物理观测汇总的既有金额合同未扩展为跨币种账务报告。

### 工作流安全预演

`POST /api/workflows/{workflowId}/runs` 接受原 objective，并可追加：

```json
{"objective":"故障处置预演","context":{"severity":"high","approved":false}}
```

返回 `executionMode=SAFETY_PREVIEW`、`agentsInvoked=false`。通过状态为 `PREVIEW_COMPLETED`，节点为 `CHECK_PASSED`；不再返回伪执行成功。安全策略的 DENY/ASK 仍停止路径。

支持 `always`（无其他命中时兜底）、`never`、布尔 `approved`，以及 `severity >= medium` 等固定级别比较；未知条件、缺少上下文、歧义分支均拒绝，无匹配则 `PREVIEW_NO_MATCH`。这些是有界条件，不执行脚本或任意表达式。用户填写的 approved 仅用于预演分支，不是审批授权。

下一步真实执行需要明确 Runtime 任务提交、查询/回调、完成证据、幂等、取消和失败重试合同；现有 `/eap/control` 的 HTTP 2xx 不足以证明任务完成。实际运行环境尚未接入该闭环。

### Runtime 生命周期与注册

- `EAP_RUNTIME_HEARTBEAT_TTL_SECONDS` 默认 180 秒；列表、发现和控制按当前心跳即时判定。停止心跳的实例不再被选路，恢复心跳可再次 ONLINE。
- 后台离线持久化默认每 30 秒；依赖数据库状态的运营汇总最多延迟一个检查周期。
- enroll 在事务内锁定令牌，再校验和消费 usedCount；并发 `maxUses=1` 用例只有一个注册成功，既定 installationId 重放逻辑保留。

### 网关响应大小

JSON、SSE 和上游错误响应在读取时执行 `EAP_GATEWAY_MAX_RESPONSE_BYTES` 限制，超限立即关闭并拒绝；中断且不完整的定长响应不会作为成功返回。仍完整缓冲后做输出治理，未改成逐 Token 放行，也未保证所有上游 SSE 应用级事件完整性。

### 历史记录查询

知识和治理 list API 新增可选 `agentId`、从 0 开始的 `page`，保留 limit。数据库先过滤再限量，以时间和 ID 稳定排序，复用既有索引。Agent 详情现在读取该 Agent 的最近 500 条，并注明范围。API 支持后续页，详情页目前仍展示有限窗口，不声称完整历史。

### 任务与评估恢复

- 业务任务 PENDING 可进入 RUNNING 或终态；RUNNING 可进入终态；终态不能重开或切换为另一终态。重复相同状态保留原始时间，允许继续补录业务结果。
- 任务状态与遥测累计共用事务行锁；并发状态更新不能覆盖已累计 Token/费用。
- `EAP_EVALUATION_JOB_TIMEOUT_SECONDS` 默认 900 秒。worker 下一次检查或自动入队前，将超时 RUNNING 置为 FAILED 并注明“结果未知”，解除 idle 判断阻塞。
- 超时不自动重试同一任务，也不强行取消或回滚正在运行的投影；迟到完成用条件更新，不能覆盖已记录的超时状态。进程恢复后会检查遗留任务；正常调度被长调用阻塞时，清理也需等下一次检查。重新提交前应检查已有结果；此机制不宣称恰好执行一次。
- 评估页离开时清除待执行轮询；已发出请求返回后不更新旧页面，不取消已经提交的后台任务。

## 回归证据

| 范围 | 核心回归 |
|---|---|
| 财务 | 混币空金额、选币同币种收益/成本、多 Agent 归因、单币种自动识别、币种切换逆序响应 |
| Runtime | 精确 TTL 边界、定时器前即排除旧实例、ONLINE/DEGRADED 过期、心跳恢复 |
| 注册 | 双事务并发消费单次令牌，第二事务等待第一个提交后拒绝 |
| Gateway | 正常 SSE/JSON/429、尚未结束的大流提前拒绝、超限错误响应、截断响应 |
| 历史 | 501 条其他 Agent 数据不能挤掉目标记录，时间相同记录跨页稳定 |
| 业务任务 | 15 个状态机用例、4 线程状态和遥测累计并发用例 |
| 评估超时 | 新任务不被误过期，旧 worker 不能覆盖 FAILED，正常 worker 可完成 |
| 工作流 | 条件分支与兜底、无匹配停止、未知/缺失/歧义拒绝、治理阻断且不宣称调用 Agent |

## 剩余工作

1. 真实工作流任务执行与完成证据；已有预演路径不得作为真实执行验收。
2. 首页信号同步与读取分离、时间窗口数据库聚合、核心列表完整分页及生产负载验证。
3. 评估器版本快照、独立历史校准、投影 worker 更完整的租约/取消与运维恢复。
4. 生产 JWT/OIDC、HTTPS、真实浏览器 E2E、MySQL 并发与部署验收。公网演示环境状态未在本轮复核。
