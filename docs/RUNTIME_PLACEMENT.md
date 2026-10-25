# Actor 当前运行位置

EAP 提供只读位置快照，用于在发起观测实验前确认 Actor 实际分配到哪个 Worker/节点。它不唤醒实例、不派发任务、不回填历史位置，也不证明任务整个执行期间没有迁移。

2026-10-21 已部署并通过真实验收：Agent84 / Workflow38 的任务前后均查到同一 Pod 与 `ate-node2`，暂停后四角色均返回 `UNASSIGNED`，没有因读取而唤醒。远程后端完整 1297 项测试通过；详见[本轮证据](evidence/runtime-placement-20261021/README.md)。本轮未重测 eBPF 可见率。

```http
GET /api/agents/{agentId}/substrate/placement?instanceKey={atespace}/{actor}
```

权限沿用 `PlatformPermission.VIEW_CONSOLE` 对 `/api/**` GET 的统一规则；viewer、operator、governance、admin 可读。未登录返回 401，没有控制台权限返回 403；不存在、其他 Agent 的实例或非 Substrate 实例返回 400。该只读接口最初上线未新增角色规则、前端按钮、Schema 或模型调用；后续任务留存功能新增V88和只读展示，见[原任务位置证据](TASK_RUNTIME_PLACEMENT_EVIDENCE.md)。

## 返回合同

响应使用现有 `ApiResponse`，`data` 含以下字段：

| 字段 | 含义 |
| --- | --- |
| `instanceKey` | EAP 中的实例标识 |
| `status` | `ASSIGNED`、`UNASSIGNED` 或 `UNKNOWN` |
| `reason` | 固定原因枚举，不透传上游错误正文 |
| `source` | 已验证时为 `SUBSTRATE_CONTROL_API`，未知时为空 |
| `observationStartedAt` / `observedAt` | EAP 开始与结束查询的时间；不是上游分配事件时间 |
| `actorUid` / `actorVersion` / `actorState` | 与 EAP 登记绑定匹配的 Actor 身份、版本和状态；未知时为空 |
| `placement` | 已分配时包含 `workerName`、`workerUid`、`namespace`、`pool`、`podName`、`podUid`、`nodeName`；否则为空 |

不返回 Worker IP、命令、环境变量、Token 或原始上游资源。Worker 名称是上游的不透明标识，不能从 Pod 名自行拼接。位置字段只用于观测关联，不能作为新的任务执行凭证或权限依据。

`ASSIGNED` 表示本次查询获得了匹配的运行位置；`UNASSIGNED` 表示已确认同一 Actor 处于 SUSPENDED / PAUSED / CRASHED，当前不返回 Worker；`UNKNOWN` 表示证据不足，不能解释为“没有运行位置”或“eBPF 不可见”。

未知原因包括 `INSTANCE_OFFLINE`、`IDENTITY_UNBOUND`、`INVALID_REFERENCE`、`ACTOR_OR_WORKER_NOT_FOUND`、`SNAPSHOT_CHANGED`、`INCONSISTENT_ASSIGNMENT`、`CONTROL_API_UNAVAILABLE`、`INCOMPLETE_METADATA`、`IDENTITY_MISMATCH`、`ACTOR_TRANSITIONING`、`NO_WORKER_ASSIGNMENT`。

## 查询一致性

先按 Agent 和 instanceKey 查询 EAP 登记，确认其绑定身份与引用。然后通过 Control API 读取 Actor：仅 RUNNING 且有分配时读取其引用的 Worker，核对 namespace、pool、Pod 名、Pod UID 和节点与 Actor 分配一致。随后复读 Actor；元数据、模板或状态发生变化就返回 `UNKNOWN / SNAPSHOT_CHANGED`，不重试并拼接两个不同时刻的身份。

这些上游读取共用最多 5 秒的截止时间，同时服从更短的现有调用超时。暂停或没有分配时不查询 Worker；没有依据时不从路由地址、历史 IP、同名 Pod 或其他 Actor 推测位置。业务层再核对 Actor UID、模板、运行空间和名称，拒绝同名替代实例借用原绑定。

查询不缓存位置。调用方应新发起 GET 并检查时间，在派发前保存原响应；任务后再读一次，比对 Actor UID、Worker UID、Pod UID 与节点。前后相同仅是边界位置证据，不证明中间没有迁移，也不保证未来仍在该节点。

## 本轮范围与后续使用

实现和测试见 [RuntimePlacementService](../backend/src/main/java/com/eap/platform/deployment/RuntimePlacementService.java)、[gRPC 查询](../backend/src/main/java/com/eap/platform/substrate/GrpcSubstrateClient.java)；远程结果以[本轮证据](evidence/runtime-placement-20261021/README.md)为准。

后续已实现[原任务位置自动留存与界面](TASK_RUNTIME_PLACEMENT_EVIDENCE.md)，通过Agent86 / Workflow40真实验收。派发时、收到回执后请求后台采样，GET仅读已有记录；异步查询可能延迟，不是精确执行边界。队列拥塞或中断可能缺失，无补采和连续位置保证。

[同节点OBI实验](evidence/obi-node-20261021/README.md)此前使用Agent85在ate-node2完成前后普通容器阳性对照；固定配置下gVisor请求0/8，结论仍仅限该实验。旧Agent83的INCONCLUSIVE_PLACEMENT和旧Agent85的无持久位置记录保持，不唤醒旧实例补写历史。

相关背景：[第一次 OBI 实测](OBI_OBSERVATION_ASSESSMENT.md)。
