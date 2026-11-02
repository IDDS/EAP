# 工作流真实执行（EAP_HTTP_TASK_V1）

初始实现日期：2026-09-08；2026-09-27 已接入 Substrate PoC，新增显式上游结果传递。产品默认关闭，PoC 已启用。此协议是 EAP 自有的同步 HTTP 任务合同，不是 A2A、MCP 或既有 `/eap/control` 的兼容保证。安全预演仍使用原 `/runs` 接口。

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

明确失败可返回 `status=FAILED`。HTTP 202、普通 2xx ACK、RUNNING、错误任务标识、超限/无效响应或网络中断均不能证明完成，执行进入 `EXECUTION_UNKNOWN` 并停止后续节点。EAP 不自动重试，不自动回滚外部效果，不宣称恰好执行一次；Runtime 的去重能力必须单独验证。仅保存成功响应的 output 字段及其 SHA-256，不保存整个 Runtime 响应正文。原始 output 与普通执行元数据分开读取，受内容权限保护。

## 控制台与 API

- `GET /api/workflows/execution-status`：是否具备启用配置。
- `POST /api/workflows/{id}/executions`：提交 `{ "objective": "...", "context": {...} }`，返回 QUEUED 和 runKey；JWT 下需 OPERATOR 或 PLATFORM_ADMIN。
- `GET /api/workflows/{id}/executions`：最近 20 次执行。
- `GET /api/workflows/{id}/executions/{runKey}`：执行状态、taskKey、runtimeInstanceKey、outputSha256、resultValidation；output 固定隐藏。
- `GET /api/workflows/{id}/executions/{runKey}/outputs`：按 nodeKey 返回成功 Runtime 回执的原始输出，包括结构验收未通过的结果；JWT 需 `PlatformPermission.VIEW_CONTENT`（GOVERNANCE_ADMIN / PLATFORM_ADMIN）。保存原输出不代表已获准向下游交接。

真实执行请求可追加 `requestKey`（1–64 位字母、数字、短横线或下划线）。相同工作流、认证身份、requestKey 和 objective/context 返回原执行，包括已结束记录；相同键改变参数返回 HTTP 409。服务端在工作流行锁内查重，并使用数据库唯一索引兜底。请求的上下文字段排序不影响服务端指纹；重试返回原快照，不因工作流后续修改而重新执行。遗漏 requestKey 的旧客户端仍每次创建新执行。

新版控制台自动附加 requestKey；超时或其他失败保留标识，同一标签页刷新后也可复用；成功确认后释放标识，下一次明确点击创建新执行。改变目标/上下文或切换工作流视为不同提交。待确认标识及匹配参数保存在 sessionStorage，不含认证令牌；浏览器禁用存储时仅在当前页面内保留，刷新后需先查询历史核对。新标签页、清理浏览器存储或旧客户端自行换键不受原提交去重保护。此机制只防止创建重复执行，不保证 Runtime 外部效果恰好一次。

控制台显示近期记录和运行中轮询；真实执行入口与安全预演分开。

## 执行边界

支持至多 64 节点、256 连线、60KB 快照；提交时解析单一路径并冻结节点、配置与上下文。支持 START、END、DECISION、AGENT、ARTIFACT；任务节点必须绑定有效 Agent。循环、歧义、未知或缺少条件输入拒绝。分支只依赖提交上下文，不接收上游 Runtime 输出作为新分支变量。

每个节点经过安全判断；任务判断包含实际 Runtime URL，以及 objective/context/config。ASK 或 DENY 均不会派发该节点。WAITING_APPROVAL 目前没有恢复接口，治理处理后仍需核对已完成节点，再显式创建新执行；用户填写 approved 不代表治理授权。Runtime 内部的实际工具/数据/制品访问仍必须实施自己的授权与治理，本协议无法证明下游行为合规。

2026-10-31 PoC 已部署：工作流预演和真实执行仍使用实际目标与完整输入进行内存内安全判断，但新安全事件不再保存该输入的 JSON 副本，新安全会话使用固定说明而非任务描述。安全事件保留目标、事件类型、决策和固定摘要；此变更不删除历史安全记录，也不改变工作流自身任务目标、快照、输出及其权限和留存规则。AX 任务的安全事件采用同样的最小化方式；其他安全入口不因此自动改变。

先持久化 DISPATCHING 再发 HTTP；网络调用不占数据库事务。每次进度更新检查执行期限，超时后不继续节点；进程重启后在下一次检查将遗留超时 RUNNING 标为 UNKNOWN。迟到结果不能覆盖终态。V94 新增新执行的持久停止请求：排队直接停止，运行中在已获准的当前步骤结束后停止后续节点；历史执行不自动加入协议。立即中断、通用异步回调/查询、补偿、失败恢复和输出驱动分支不在本协议内。

HttpServer 集成回归（按项目要求在远程集群运行）覆盖匹配终态、配置快照、实际地址安全检查、非终态/错标识、失败/拒绝、超时、失联恢复和禁止自动重试；Substrate 真实集群验收记录见 PROJECT_MEMORY.md 最新进展。


## 节点编辑版本（V42）

节点读取和保存响应增加 `version`；更新时传 `expectedVersion`。版本不匹配返回 HTTP 409，原记录不变。新版控制台在拖动和属性保存时均携带版本，成功后只更新本地版本，不覆盖保存期间继续输入的内容；冲突时保留本地草稿，可先复制草稿，再点击“重新加载流程”读取最新内容。单纯点击节点不再触发位置保存。

创建节点通常不传 expectedVersion。为兼容旧客户端，更新时省略该字段仍允许写入；因此版本保护要求编辑客户端使用这个字段，不等于所有旧集成都已防止覆盖。版本控制目前针对节点，不提供整个工作流的多人实时合并。


## 显式上游结果传递（2026-09-27）

任务节点的 `configJson` 可配置 `{"inputFrom":["draft"]}`。只允许引用提交时所选路径上已经在前面的 AGENT/ARTIFACT 节点；不存在、控制节点、自身、后续节点、重复引用或非字符串数组均拒绝。没有 inputFrom 的旧节点保持原 context，不自动接收其他 Agent 输出。

上游 Runtime 的成功响应可以增加任意 JSON `output`（字符串、对象、数组等）。执行器将指定输出合并到下游请求：

```json
{
  "context": {
    "purpose": "原提交上下文保持不变",
    "eapPreviousResults": {
      "draft": {
        "taskKey": "wf-exec-<uuid>:1",
        "agentId": 8,
        "output": "上游产出",
        "outputSha256": "<实际交接内容的 SHA-256>",
        "originalOutputSha256": "<原 output 的 SHA-256>"
      }
    }
  }
}
```

`eapPreviousResults` 是保留键，提交上下文不得自行提供。哈希按 ObjectMapper 序列化 output 的 UTF-8 字节计算（字符串包含 JSON 引号），是结果比对值，不是身份签名。配置 `EAP_JSON_V1` / `JSON_TEXT` 时，验收通过后交接解析出的对象，`outputSha256` 对应这个对象，`originalOutputSha256` 对应保存的原文本；未配置契约时交接内容不变。上游内容是待处理数据，不能授予治理审批权限；执行路线仍仅由初始 context 决定。

单节点 output 上限 16,384 字节，超限返回 EXECUTION_UNKNOWN，不截断、不重试、不传给下游。下游声明依赖但上游没有 output 或为 JSON null，执行 FAILED，后续节点不派发。合并后整个请求上限 60,000 字节，超限也在派发前停止。每个节点的安全判断使用实际合并 context；因此上游结果也参与治理检查。

成功 output 保存在已有 steps_json 中，普通提交、列表、详情响应一律隐藏原文，仅保留摘要和验收元数据；不需要数据库迁移。旧执行日志缺失新字段时仍可读取。结果内容遵循数据库原有保留策略；工作流页面和任务工作台已有受内容权限保护的主动结果查看器。此能力是串行工作流的协作基础，Team/Plan 桥接见下文，不包含并行 fan-out/fan-in。

执行目标属于普通执行元数据，可由具有控制台查看权限的账号读取；计划目标、上下文说明和步骤标题也属于计划元数据。不要在这些字段中填写敏感正文或凭据。提交上下文保存在 snapshot_json，步骤成功输出保存在 steps_json；结果接口权限不代表这些内容没有在数据库和备份中留存。


### 受限容量下的串行执行

Substrate 任务节点可显式配置 `"suspendAfterTask": true`。R9修订（已部署）先持久保存匹配任务标识的成功/明确失败回执，再核对跨工作流、聊天和实验的在途占用；只有当前任务明确结束、没有其他执行或未知占用时才申请休眠。业务 `status/reason` 保持，回收结果独立记入步骤 `reclaimStatus` 和 `RECLAIM` 阶段。成功任务只有确认 `SUSPENDED` 后继续下游；明确失败始终停止下游。结果未知或未派发的失败不自动休眠；休眠未确认保留持久派发屏障，不按超时释放、不重跑任务。历史无法确认原物理绑定的UNKNOWN会保守阻止自动回收。详见 [安全回收契约](WORKFLOW_RUNTIME_RECLAIM.md)。

默认关闭，仅适用于工作流专用 actor。共享 actor 可能还有其他请求，不应启用此选项。该机制是成功任务边界的显式资源释放，不是后台空闲计时器、容量调度器或跨工作流互斥保证。当前 PoC 实测两个不同 actor 连续唤醒可能因上游占用资源而触发 RESOURCE_EXHAUSTED；工作流可给两个节点都配置此选项，在保留会话的同时释放资源。


## 计划执行桥接

任务节点可通过 configJson.taskObjective 覆盖本步目标（1–128 个字符），实际派发和治理检查使用同一个目标。Team/Plan 生成的工作流是只读快照，不能经工作流接口修改或独立再次派发。计划入口、幂等和状态映射见 [PLAN_EXECUTION.md](PLAN_EXECUTION.md)。

## AX 执行节点

任务节点配置支持 `{"executionBackend":"AX","axMode":"LLM","inputFrom":["previous"]}`。executionBackend 省略时为 RUNTIME，AX 模式默认为 LLM，ECHO/FAIL 仅用于合成回执验收。工作流提交前校验 AX 配置和 Agent 状态；执行时把目标与包含上游真实结果的上下文送给 AX Runner（合并上限 8000 字符 / 24KB）。AX 任务保存 workflowId/runKey/nodeKey，时间线记录实际 AX taskKey 和 ax:// 实例。业务成功且物理休眠确认后才继续下游；所有任务仍受原工作流总期限限制，不提供重启后的自动续跑。详见 AX_WORKFLOW_INTEGRATION.md。


## 工具节点与结果查看（2026-09-28）

控制台可保存任务类型、求和参数并刷新复用；执行器按冻结节点派发，要求专属接入及运行程序工具能力。新提交不再接受全局 context.eapTool，已有 requestKey 重放仍读取原执行。工作流页面已复用受内容权限保护的主动结果查看器。详细配置、迁移和边界见 [WORKFLOW_TOOL_NODES.md](WORKFLOW_TOOL_NODES.md)。

2026-10-31 PoC 已部署：已派发工具任务的 Runtime 若报告 `FAILED`，仅说明任务侧报告失败，不能证明工具没有外部效果；工作流保留 `EXECUTION_UNKNOWN` 和原占用，停止下游、不自动休眠或重试，后续按原操作核对。此规则也会保守覆盖部分实际未执行的失败；它不改变派发前明确拒绝的 `FAILED`，也不把普通模型任务的 `FAILED` 改成未知。独立工具存储的原操作查询可提供进一步证据，但不会自动将未知工作流恢复或重跑。

2026-11-01 PoC 已部署：若前一次已读取的受控工具核验记录仍待处理，后续读取遇到临时错误时清除旧证据并继续只读刷新；权限拒绝或其他非临时客户端错误停止自动刷新。切换身份或执行记录时清除原查询状态。页面不因查询失败推断原操作未提交，也不重发工具操作；初次读取失败时仍需用户手动刷新。

同日的前后端版本为核验报告增加 `workflowId` 和 `runKey`，页面在展示前核对协议、原执行、记录结构及终态的原操作和工具存储证据。响应不匹配、缺少必要证据或数值无法安全表达时，不展示该报告、不推定结果，并停止自动刷新、提示核对版本或联系管理员；这只是响应一致性校验，并不能独立证明服务端或业务结果可信。旧前端可忽略新增字段；新前端遇到未提供身份字段的旧后端会拒绝展示核验记录。因此发布须先升级所有提供该接口的后端，再升级前端；回滚旧后端前应先回滚前端，避免混合版本丢失核验展示。

2026-11-02 PoC 已部署：Runtime 对交接任务返回 `EXECUTION_UNKNOWN` 且固定原因码为 `HANDOFF_UNCONFIRMED` 时，工作流保留该固定代码和安全说明，便于按原任务查证；不接受 Runtime 自由文本，也不因此推定是冷启动、超时或未发生外部效果。未知状态、原占用和不重放规则保持，后续节点不派发。其他未知或不支持的代码仍使用通用提示；历史执行不会回填。

## 结构化结果验收（2026-10-20）

常规 Runtime 的 AGENT/ARTIFACT 节点可在 `configJson.eapOutput` 配置有界 JSON 结构。契约随原提交快照冻结，保存和提交均校验；成功回执再进行结构验收。验收失败保留步骤 `SUCCEEDED` 原回执及输出，将工作流置为 `FAILED` 并停止后续节点，包括没有 `inputFrom` 的节点；已知终态仍按原占用/回收规则处理。未知回执不作结构成功推断、不重放。

`resultValidation` 包含契约版本、来源、摘要、状态及固定类别代码，不包含 schema、属性路径或结果正文。没有契约或旧记录保持原行为，不回填。首版不是完整 JSON Schema，不会强制模型生成、修复回答或增加模型调用；AX、独立聊天和计划编辑器入口尚未接入。完整格式、限制与验收见 [结构化结果契约](STRUCTURED_RESULTS.md)。


## 停止后续调度（V94）

`GET /api/workflows/{id}/executions/{runKey}/stop` 查询原停止状态；`POST` 同一路径提交 `{ "requestKey": "original-stop" }`。读取走 `PlatformPermission.VIEW_CONSOLE`，提交走 `RUN_WORKFLOWS`。排队执行与出队共用行锁，运行中通过 ownerId/ownerEpoch 在节点边界生效；回执、外部效果、预算和原UNKNOWN保持。团队计划投影为CANCELLED，未开始步骤为SKIPPED；原提交键仍只能查询原执行。

REQUESTED不代表已经停止，UNCONFIRMED不能当作取消成功。HTTP回执丢失先查询原请求，不自动重提或换键。当前已获准步骤仍可能准备、派发、等待回执与回收；不会强杀Runtime或撤销既有效果。详细状态、幂等和边界见[停止协议](WORKFLOW_STOP.md)。
