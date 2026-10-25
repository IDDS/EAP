# 运行位置采样的投递与失败指标

已为原任务位置采样补充进程内计数、队列水位、只读 API 和 OTLP 指标，并部署到 PoC 集群。它们用于定位采样缺失，不能替代[原任务的持久位置证据](TASK_RUNTIME_PLACEMENT_EVIDENCE.md)，也不计算业务执行成功率或全量任务覆盖率。

## 读取与导出

`GET /api/observability/runtime-placement-sampling` 沿用 `PlatformPermission.VIEW_CONSOLE`，四个控制台角色可读，匿名为 401，无该能力为 403。读取只访问进程内统计，不查询 Control API 或数据库，不创建采样、不唤醒 Agent。前端应用与权限定义未改，本轮没有新增统计界面。

响应字段：`processStartedAt`、`observedAt`、`queueDepth`、`activeWorkers`、`queueCapacity=32`、`workerCapacity=2`，以及两个固定阶段 `DISPATCH_REQUESTED`、`RECEIPT_RETURNED` 的 `events`。计数从当前采样服务实例创建时开始，重启后从零开始，不从数据库重建历史。

采样计数通过现有 `ObservationMetricsExporter` 单线程异步导出，继续使用原 OTLP/HTTP Collector、60 秒周期和有界响应处理。新增独立 resource `service.name=eap-placement-sampling`；既有覆盖指标 `service.name=eap-observation` 与窗口语义保持。Prometheus 仅扩展采集白名单，不替换既有规则，不新增外部告警接收方。

| OTLP 指标名（前缀 `eap_runtime_placement_sampling_`） | 类型 | 序列数 / 标签 |
| --- | --- | --- |
| `events` | 当前进程累计单调 Sum，含 `startTimeUnixNano` | 28；`phase` × `event` |
| `queue_depth` | Gauge | 1；当前排队数 |
| `active_workers` | Gauge | 1；执行器当前活动线程数 |
| `queue_capacity` | Gauge | 1；32 |
| `worker_capacity` | Gauge | 1；2 |
| `process_start_time_seconds` | Gauge | 1；计数周期起点的 Unix 秒 |
| `snapshot_time_seconds` | Gauge | 1；统计快照的 Unix 秒 |

共 7 类、34 条序列。所有事件都输出，包括值为零的事件；没有 Agent、task、Trace、实例、节点、URL、异常正文或任意动态标签。Prometheus 可能将单调 Sum 名称规范化为 `events_total`；白名单仅允许 `events` / `events_total` 两种固定拼写，本轮真实接收名称已确认是 `eap_runtime_placement_sampling_events_total`。

## 事件含义

| event | 增加条件 |
| --- | --- |
| `REQUESTED` | 通过既有 Substrate、原 Trace、绑定身份筛选，并准备向执行器提交 |
| `ACCEPTED` | 执行器接受请求；不代表开始查询或已保存 |
| `REJECTED` | 执行器因队列已满或关闭而拒绝；不在调用方线程执行 |
| `SCHEDULING_FAILED` | 提交执行器发生其他运行时异常 |
| `STARTED` | 后台开始处理采样 |
| `NOT_CLAIMED` | 未取得原任务阶段认领，可能是重复、登记或原绑定不匹配；没有发起位置查询 |
| `CLAIM_UNCONFIRMED` | 认领事务抛出异常，提交结果不能确认；不查询、不重试 |
| `QUERY_FAILED` | 查询抛出未处理运行时异常或没有返回快照，尝试保存固定 UNKNOWN 结果 |
| `WRITE_UNCONFIRMED` | 完成记录的事务抛出异常，不能据此确定数据库完全未写入 |
| `NOT_RECORDED` | 完成更新影响 0 行，不算一次新保存 |
| `RECORDED_ASSIGNED` | 完成事务确认更新 1 行，保存 ASSIGNED 快照 |
| `RECORDED_UNASSIGNED` | 完成事务确认更新 1 行，保存 UNASSIGNED 快照 |
| `RECORDED_UNKNOWN` | 完成事务确认更新 1 行，保存 UNKNOWN 快照 |
| `SHUTDOWN_DROPPED` | 正常关闭执行器时返回的尚未开始的排队请求 |

`QUERY_FAILED` 和 `RECORDED_UNKNOWN` 可以同时增加：前者表示查询异常，后者表示“未知”这一结果已保存。Control API 不可用等已由位置服务转换成 UNKNOWN 的情况，只增加 `RECORDED_UNKNOWN`；不能将其当作已定位成功。写入确认失败保持未确认，不重派任务或补采位置。

各计数增量是线程安全的，但一个快照不对所有计数、队列和线程状态加全局事务锁；并发下短暂的先后顺序可能导致计数无法即时配平。它们是处理事件统计，不能把 `REQUESTED - RECORDED_*` 直接解释为确定丢失量。

## 使用边界

- 先检查 `snapshot_time_seconds` 是否新鲜、`process_start_time_seconds` 是否变化，再解读计数或增量。接口返回当前服务进程数据；多副本聚合和 HA 尚未设计或验收。
- `REJECTED` 增加表示采样投递未被接受；检查队列与活动线程。`NOT_CLAIMED` 不等于存储故障，也不能全部归为重复。
- `CLAIM_UNCONFIRMED` / `WRITE_UNCONFIRMED` 增加时检查存储健康；保留原任务与持久证据，不通过重派“修复”统计。
- 进程硬退出会丢失内存队列及计数。`SHUTDOWN_DROPPED` 仅覆盖执行正常关闭路径时的排队项，最终增量也可能来不及导出；不声称能够精确统计崩溃丢失量。
- 没有增加持久投递、补采、重试、采样窗口回填或自动扩容。位置未知不改变原 coverage、工具 UNKNOWN 或外部效果 UNVERIFIED。

## 验证

远程专项覆盖：真实队列 2 活动/32 排队/2 拒绝、关闭排队丢弃、关闭后拒绝、并发计数、进程实例重置、保存零行/事务异常、UNKNOWN 与保存成功的区别、固定标签及无身份泄漏、四角色只读权限、OTLP 累计类型/起点和原导出失败保护。隔离 MySQL 另验一次保存与重复更新的实际行数。

远程后端完整 144 类 1319 项、专项 10 类 90 项、隔离 MySQL 9 项通过，失败/错误/跳过均为 0；专项包含在完整套件中，不累加。打包与受测 JAR 镜像载荷核对通过。10 份应用源码变更，921 份应用输入与本地冻结摘要一致。前端、Go/Python Runtime、浏览器及 eBPF 未改也未重测；前端 HTTP 就绪与页面摘要另作只读核对。

本轮 EAP 新建 Agent87 / Workflow41，仅派发一次任务。两阶段各得到 `REQUESTED=ACCEPTED=STARTED=RECORDED_ASSIGNED=1`，其他事件为 0；队列和活动线程归零，两个 ASSIGNED 快照已关联原操作 56。四角色读数相同，后端与前端代理匿名读取均为 401。Agent 最终通过 EAP 显式暂停到 SUSPENDED、连接撤销为 REVOKED，原持久采样保持，旧 Agent85 没有回填。

**样例任务失败单列：** 原执行为 `EXECUTION_UNKNOWN`，HTTP 200 只表示收到回执；唯一 Agent Span 为 FAILED、耗时 20010ms，MCP Span / 工具记录均为 0，HTTP 实际请求次数未知，模型调用 0。耗时与固定夹具的 20 秒截止时间吻合，但不足以定位启动、握手、协议请求或导出哪个阶段失败。没有重派、重查 `/task` 或改写 UNKNOWN，占用行摘要保持。原 coverage COMPLETE 的分母只包含预期 Agent Span 与模型事件，不能作为这次 MCP 链路通过的依据。

采样指标验收只证明原 UNKNOWN 任务的两次采样投递及留存可观测，不表示样例完整功能通过。应在下一独立批次为固定夹具增加不含凭证/正文的阶段诊断，使用故障注入与有界验证定位问题；不能靠延长超时、唤醒旧 Agent 或重跑原任务抹平失败。

独立 Prometheus 核对得到 7 类 / 34 条序列，计数与 API 完全相同、进程起点一致、快照年龄 61.17 秒；旧覆盖指标仍新鲜。批次已关闭，原写入口禁止重放。3 个临时 Pod 按 UID 清理，UP / Schema88 / 两端 Ready 重启 0，前端 HTTP 门槛连续三次通过。472 份脱敏证据及归档已下载逐项核验。

证据目录：[本轮验收](evidence/placement-sampling-metrics-20261021/README.md)。所有测试、构建、部署在远程进行，无 commit/push。长期容量、崩溃恢复和多副本连续导出仍未覆盖。

后续[HTTP阶段诊断](HTTP_FIXTURE_DIAGNOSTICS.md)已部署并由Agent88新任务验证；本篇Agent87的失败仍保留，不能用新成功替代旧根因调查。指标导出本身未再修改，后续发布后34条序列复验通过。
