# 执行事件投递状态

EAP 保存每条事件流最近一次认证生产者报告，并与实际已保存事件数一起查询。它帮助区分事件尚未确认、回执丢失和报告缺失；不是实时队列、运行存活证明或业务效果证明。

## 写接口

`POST /api/runtime-access/v1/execution-delivery` 使用既有实例机器身份，认证与原事件入口一致。人类 JWT 不能替代机器凭据；`SUBSTRATE_TASK` 通道限制不变。正文只接受下列字段，序列化后最多4KiB：

| 字段 | 范围与含义 |
|---|---|
| `schemaVersion` | 整数1 |
| `producerId` | 原日志的非零小写规范 UUID |
| `framework` / `frameworkVersion` | 与事件流一致的有界标识；最大64/32字符 |
| `reportSequence` | 1至2^53−1，原日志内单调递增 |
| `total` / `acknowledged` / `pending` | 0–4096整数；总数必须等于已确认加待确认 |
| `attempts` | 0至2^53−1的累计事件投递尝试数，不含报告请求次数 |
| `state` | SDK 的有界投递状态枚举 |

状态允许 `EMPTY`、`ACKNOWLEDGED`、`PENDING`、`SENDING`、`RETRY_WAIT`、`BATCH_LIMIT`、`RETRY_EXHAUSTED`、`BLOCKED_AUTH`、`BLOCKED_CONFLICT`、`BLOCKED_REQUEST`、`BLOCKED_RECEIPT`、`BLOCKED_TRANSPORT`、`CANCELLED`、`RETRYABLE_NETWORK`、`RETRYABLE_SERVER`、`RETRYABLE_THROTTLED`。总数为0必须是EMPTY；正总数且待确认0必须是ACKNOWLEDGED；有待确认事件不能报告EMPTY/ACKNOWLEDGED。

服务端在同一事务内按租约→实例顺序加锁，复核身份代际、到期时间和生产者绑定。先报告后发事件也不能改变原框架/版本或跨代际重新归属。已确认数不能大于服务端保存数；总数、已确认数、累计尝试不能回退。相同序号同内容返回重复确认且不刷新时间；旧序号或同序号改内容返回409。计数/格式错误返回400，身份不可用返回401。

成功响应的 `data` 只有 `streamKey`、`reportSequence`、`duplicate`。这是报告回执，不能用于确认事件。无源码、路径、参数、结果、异常正文、环境变量或凭据字段。SDK 的显式上报用法见[接入说明](../runtime/execution-observer/README.md#显式上报投递快照)。

## 读接口与界面

`GET /api/observability/agent-executions/deliveries` 支持 `agentId`、`streamKey`、`afterId`、`limit`（1–100，默认50）。权限统一来自 `PlatformPermission.VIEW_CONSOLE`。返回 `items`、`hasMore`、`nextAfterId`、`asOf` 和 `evidenceKind=AUTHENTICATED_PRODUCER_SNAPSHOT`。按首次报告记录ID升序分页，后续更新不改变记录ID；不是变更订阅或全局一致快照。

每条记录包含原实例/流/框架、报告序号和计数，以及：

- `reportedAt`：平台接收该报告的时间；不是客户端生成时间。
- `receivedEvents`：查询时该流实际保存事件数，与客户端计数独立。
- `stale`：本次查询时报告接收时间是否超过5分钟，界面需刷新后重新判断。
- `credentialState`：当前保存的身份元数据状态。AVAILABLE仅表示凭据记录有效；其余为REVOKED_OR_UNAVAILABLE、GENERATION_CHANGED、EXPIRED、UNAVAILABLE。查询不会在线探测、续期或唤醒实例。

控制台入口为“运行与观测 → 执行关系 → 投递状态”。报告可以先于任何事件出现；没有报告显示未知，不合成零待确认。旧事件升级后不会被回填伪造的状态报告。撤销后保留历史快照，并提示先核对原身份。

## 如何理解差异

| 客户端报告 | 服务端已接收 | 含义与下一步 |
|---|---|---|
| 总9 / 已确认0 / 待确认9 | 9 | 可能丢失事件回执，核对原日志和原回执，不重跑业务 |
| 总9 / 已确认0 / 待确认9 | 0 | 这份报告有待确认事件，服务端暂未保存事件；保留原日志 |
| 总9 / 已确认9 / 待确认0 | 10 | 报告可能落后于后续事件，需要更新快照 |
| 无报告 | 任意 | 投递状态未知，可能是旧版程序、离线或报告请求失败 |

固定框架Worker在有界投递结束后报告一次。新版受控模板可将新日志登记为原身份绑定的后台补发对象，由常驻Runner有限次补事件或报告；普通SDK仍需显式调用。详情见[后台补发契约](EXECUTION_BACKGROUND_DELIVERY.md)。每次报告产生更大序号；原任务回执保留最初快照，不自动跨代际恢复或重执行业务。控制台没有实时后台队列状态。

## Go ADK / Kagent Runner

新版可选 Kagent Runner 也使用同一报告协议。事件回执和报告回执独立持久化；未确认报告只重发原序号/正文，确认后才推进下一份快照。每日志最多16份快照，每份最多3次发送，报告失败不阻断事件在原6次额度内的补传。没有变化的快照不重复刷新时间；旧实例与旧事件不会自动补造报告。适配器范围、身份与存储边界见[内部执行观测](KAGENT_EXECUTION_OBSERVATION.md)。
