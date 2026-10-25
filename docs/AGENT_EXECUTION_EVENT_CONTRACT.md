# Agent 执行事件 v1 接入契约

本契约是[跨框架设计](MULTI_AGENT_EXECUTION_OBSERVABILITY.md)的第一阶段实现范围。框架适配器应输出这些事件；LangGraph与AutoGen Core的固定版本有界适配见[接入说明](../runtime/execution-observer/frameworks/README.md)，其他框架不因存在本契约而自动获得支持。框架创建/运行状态另见[支持表](FRAMEWORK_SUPPORT.md)。Python参考实现见[execution-observer](../runtime/execution-observer/README.md)。

## 接收与身份

`POST /api/runtime-access/v1/execution-events` 使用现有 `X-EAP-Runtime-Key` 与运行实例凭证认证。凭证由可信启动器在内存提供；不得放入请求正文、事件、配置示例或日志。接收端复用原实例、Agent状态和原生Actor身份验证，并在写锁内复核身份代际及有效期。当前 `SUBSTRATE_TASK` 的专属任务通道白名单保持，不支持本新增入口。

Agent、运行实例和事件流归属由服务端决定。客户端不提供 `agentId`、`endpointKey`、任务ID、其他运行实例或治理许可。新增观测不能创建受管Agent、获得工具许可、消费模型预算或修改业务任务结果。

服务端事件流由已认证实例、身份代际和 `producerId` 隔离。一个运行实例的同一 producer 在事件或投递报告首次落库后不能切换到其他身份代际；换代后应建立新的生产者日志，旧待交付记录按缺口处理。不同实例自报相同 producer/run 编号不会串成同一条链。

## 批次

| 字段 | 含义与界限 |
|---|---|
| `schemaVersion` | 整数1 |
| `producerId` | 非零、小写、规范UUID；一个有界日志流使用一个编号 |
| `framework` | 1–64位标识符，例如 `eap-native`；是生产者声明，不是框架认证 |
| `frameworkVersion` | 1–32位版本标识；同一流不能混用版本 |
| `events` | 1–64条事件 |

字段白名单之外的字段拒绝。批次序列化后的UTF-8大小上限128KiB；这不是HTTP层读取前的流量限制。一个流最多4,096条已保存事件，去重重投不重复占用条数。

## 事件

每条事件都包含：

| 字段 | 含义 |
|---|---|
| `eventId` | 非零、小写规范UUID；首次写入本地日志时产生，重投不变 |
| `sequence` | 1至2^53−1的整数；仅表示本生产者的局部顺序 |
| `kind` | 下表五种事件之一 |
| `runId` | 一次逻辑Agent调用的非零UUID，不能用Agent定义ID或PID代替 |
| `traceId` | 非零32位小写十六进制；服务端按既有可信运行规则解析/命名空间隔离 |
| `occurredAt` | ISO-8601时间；2020-01-01以后，不能超过服务端当前时间5分钟 |
| `processId` | 正的32位整数；仅作为生产者报告，不是内核身份认证 |

| kind | 必填附加字段 | 可选附加字段 |
|---|---|---|
| `RUN_STARTED` | `definitionKey` | `parentRunId` |
| `RUN_FINISHED` | `outcome` | 无 |
| `TOOL_STARTED` | `toolCallId`, `definitionKey` | `artifactDigest` |
| `TOOL_FINISHED` | `toolCallId`, `outcome` | 无 |
| `ARTIFACT_REGISTERED` | `artifactDigest`, `sizeBytes`, `language` | 无 |

`definitionKey` 是最多64位的受控标识，`framework`/版本/定义标识只接受字母、数字及 `_.:/@+-`，不能填写提示词、代码、参数或异常消息。`outcome` 为 `SUCCEEDED` / `FAILED` / `CANCELLED`；它表示生产者报告的结果。工具编号使用非零UUID。代码摘要为64位小写SHA256，大小0–1MiB，语言为 `PYTHON` / `JAVASCRIPT` / `SHELL` / `OTHER`。

不接收原始代码、输入输出、路径、环境变量或任意 metadata。代码内容保管、依赖摘要与业务效果核验属于后续独立能力；只有摘要不能恢复代码。

## 幂等与关系

同一流中，重复 `eventId` 且规范化内容相同返回重复确认；相同ID不同内容、重复局部序号、重复生命周期位置、运行Trace变化、工具归属变化、父子环或已知父子Trace不一致均返回409。一个批次整体事务提交，不部分写入后返回成功。

Python SDK 的有界 `deliver()` 会在网络故障、HTTP 429/5xx 后重传同一批原事件；认证拒绝、409 和非法回执停止。回执丢失不代表服务端未保存，后续重复确认用于确认原事件，不能触发业务重执行。本地 Journal 和原任务回执保留投递状态；显式 `report_status()` 可通过独立接口上报最近快照，供控制台查询，不增加本事件协议字段，也不代表实时队列。[投递状态与恢复](../runtime/execution-observer/README.md#有界投递与状态)。

允许结束事件先到、父节点迟到和序号乱序。接收端保留事件，查询时标记缺口；后续事件到齐后可消除对应结构缺口。只在同一流中解析 `parentRunId`，不以名称、PID、时间近似或其他流的同名UUID补父节点。跨实例委派和代码交接采用独立的双方确认协议；多来源汇合、独立重试/恢复关系仍未实现，不能把它们伪装成同流父子关系。

成功回执：

```json
{
  "success": true,
  "data": {
    "streamKey": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "received": 3,
    "recorded": 2,
    "duplicates": 1
  }
}
```

`streamKey` 由服务端生成，示例值仅用于说明格式。客户端仅在 `received` 与发送条数一致，且 `recorded + duplicates == received` 时确认该批次；超时、异常回执和未授权均保留原事件，不换事件ID重试。HTTP失败只影响遥测交付，不应重做业务工具。

## 查询与状态

控制台入口为“运行与观测 → 执行关系”，支持按智能体/事件流查询、接收顺序分页、父子执行、工具与代码摘要及缺口阅读。界面规则与限制见[执行观测控制台](AGENT_EXECUTION_CONSOLE.md)。

- `GET /api/observability/agent-executions?agentId=…&streamKey=…&afterId=0&limit=100`：按接收记录ID分页，支持1–500条，返回 `items`、`hasMore`、`nextAfterId`。这是历史查询接口，不承诺跨多个并发实例的全局CDC订阅顺序。
- `GET /api/observability/agent-executions/{streamKey}/graph`：返回运行关系、工具开始/结束记录、代码产物和缺口。读权限来自 `PlatformPermission.VIEW_CONSOLE`；其他写方法拒绝。

当前缺口类别包括 `MISSING_SEQUENCE`、`MISSING_PARENT`、`INCOMPLETE_RUN`、`INCOMPLETE_TOOL`、`RUN_ORDER_CONFLICT`、`TOOL_ORDER_CONFLICT` 和 `MISSING_ARTIFACT`。没有开始或结束记录时，状态为 `INCOMPLETE`；顺序矛盾为 `INCONSISTENT`。空流的 `complete` 为false。

`complete=true` 只判断已经收到的事件结构，不代表所有代码路径均已观测，也不能排除尚未上报的尾部事件。状态来源固定为 `AUTHENTICATED_PRODUCER_REPORT`；它既不是工具许可，也不是内核或外部系统对业务效果的独立证明。

常见响应：401表示运行凭证或身份不可用；403表示方法/人类权限不允许；400表示格式或界限不符；409表示事件或归属冲突。持久化失败不会返回成功回执。框架适配器应保留错误类别和交付缺口，不能因观测失败自动重做Agent或工具执行。

独立状态接口及其单调序号、计数校验与身份约束见[执行事件投递状态](EXECUTION_DELIVERY_STATUS.md)。事件回执与状态报告回执分别确认，报告成功不会确认任何事件。

跨实例关系采用独立的[双方确认协议](EXECUTION_LINKS.md)，两端保留各自事件流和逻辑执行编号，不改写本地 `parentRunId`。
