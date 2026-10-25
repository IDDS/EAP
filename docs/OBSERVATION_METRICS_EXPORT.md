# 持久原登记的独立指标采集

> 2026-10-21 更新：同一导出器已增加独立 resource `eap-placement-sampling`，输出[位置采样投递与失败指标](PLACEMENT_SAMPLING_METRICS.md)。新增 7 类 / 34 条固定序列，与本篇既有覆盖窗口指标分开；计数从当前采样服务实例创建时开始，重启归零。以下历史测试数和镜像不代表本次部署状态。

2026-10-11，T7-H。修正版远程专项 79 项/7 类、全量 864 项/104 类与打包通过，失败、错误及跳过均为 0；固定镜像已发布，实际 Pod/Flyway77/四角色读取与保护通过；Prometheus 八类指标与 1440 分钟原登记报告逐项一致，独立采集验收通过。295 项证据接收核对、四源码推进及 4 Job/4 Pod 清理已闭环，最终回执 completed/closed/sourcePromoted=true。此前 T7-G 的真实零额度拒绝及 IMAGE 迁移证据保持，新的付费模型预算尚待授权。

沿用 `ObservationCoverageService.report` 的原登记和窗口分类，单独只读事务配置 2 秒期限（不将其视为连接获取与整个查询的严格墙钟上限），不根据已收到的 Span 数量反推分母、不登记新操作、不写预算或运行状态。超过原 10000 条窗口上限、查询失败或接收失败时，本轮不发送快照；不能用空快照或零值掩盖未知。

导出默认关闭，启用后使用独立单线程按固定延迟发送，默认窗口 60 分钟、周期 60 秒，窗口范围 1–1440 分钟。实际 PoC 验收拟设 1440 分钟，与四角色覆盖 API 使用同一窗口对照。输出 OTLP/HTTP Protobuf 到现有集群 Collector，继续由现有 Prometheus 独立采集；Collector 配置与部署保持；Prometheus 将仅增加本轮八个指标名的采集白名单，保留原有 `substrate_*`/`otelcol_*` 过滤规则及 Deployment。目标限于 `.svc`/`.svc.cluster.local` 服务或 loopback 夹具的 `/v1/metrics`，禁止用户信息、查询参数、片段与重定向。

HTTP 全响应最多 2 秒、响应最多 4096 bytes、发送体最多 65536 bytes；非成功或部分拒绝保持未确认，无应用层重发。失败日志只有固定原因码，不记录远端 URL、正文、异常文本或凭据。查询与发送均不占用派发线程。内部 HTTP PoC 不构成生产传输认证或独立接收端防伪保证。

| 指标（OTLP 名称） | 含义 |
| --- | --- |
| `eap_observation_denominator_known` | 每个固定入口为 1/0，标记原分母是否确认 |
| `eap_observation_registered` | 已确认窗口内原登记数；未确认入口不发送该序列 |
| `eap_observation_coverage` | COMPLETE/PENDING/GAP/UNCONFIRMED 四个互斥类别，合计等于原登记数 |
| `eap_observation_outcome` | FAILED/UNKNOWN，单独保留，不从原登记删除 |
| `eap_observation_expected` | 原登记预期的 Agent Span/Gateway 模型事件数 |
| `eap_observation_snapshot_time_seconds` | 原快照时间，用于识别采集失联或陈旧值 |
| `eap_observation_window_start_seconds` | 窗口起点 |
| `eap_observation_window_minutes` | 窗口长度 |

全部为窗口 Gauge，不是累计 Counter，不能直接用 `rate` 推导调用率。只有固定入口、状态、证据类型标签；资源名固定为 `eap-observation`。不发送 Trace、操作键、Agent/UID、主体、路径、提示词、输出或费用正文。现有 Prometheus 抓取规则会附加 `namespace=otel-system` 与 Collector 的 `pod`/`instance` 等基础设施标签；它们不来自业务调用。应用输出的标签仍限于固定入口、状态和证据类型。实际指标名与对照结果以最终证据为准。

未确认入口只有 known=0，不能解释为调用数零；查询/告警应同时检查 known=1 和快照新鲜度。Collector 可能短暂保留上一轮序列，不能用“存在样本”代替新鲜度检查，也不能把采集成功解释为模型成功。

远程专项包括未知分母/已知零、失败与未知保留、分类总和、固定标签及内容排除、实际 Protobuf HTTP、重定向、部分拒绝、超大响应、慢响应正文与查询失败不发零值；随后完整后端回归、打包、固定镜像摘要、原预算/UID/模板保护、四角色只读报告和实际 Prometheus 同窗口对照。前端及各 Runner 未变，仅沿既有摘要一致的证据，不声称本轮重测。无数据库迁移，Flyway 应保持 77；本轮不调用模型或工具，不新增支出授权。

辅助历史：首次镜像入口检查假设直接 `java -jar`，实际为 `sh -c exec java $JAVA_OPTS -jar /app/eap.jar`，在创建测试 Job 前停止；首次 Job 因源码清单不包含 `pom.xml` 在 Maven 启动前失败、业务测试未运行。补入与绿色目录摘要一致的 POM 后使用独立 v2 测试 Job；v2 又在编译时发现固定依赖 `opentelemetry-proto:1.7.1-alpha` 使用旧消息 API，仍未进入业务测试。各次原诊断、源码及失败 Job/日志保留。

修正版沿用固定依赖，以 `InstrumentationLibraryMetrics` 编码，不升级既有 Trace 接收依赖。旧/新 ResourceMetrics 的此字段均为 wire tag 2，内层 metrics 亦为 2；Gauge 与数据点字段按当前协议对应，静态字段检查与实际 Collector 接收须各自验证。[旧版官方 schema](https://github.com/open-telemetry/opentelemetry-proto/blob/v0.9.0/opentelemetry/proto/metrics/v1/metrics.proto)、[当前官方 schema](https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/metrics/v1/metrics.proto)，访问日期 2026-10-11。

固定旧响应类型没有 `partial_success` accessor，修正版按[官方响应协议](https://github.com/open-telemetry/opentelemetry-proto/blob/main/opentelemetry/proto/collector/metrics/v1/metrics_service.proto)解析有界 unknown field 1：拒绝点数非零即保持未确认，格式异常也不算成功；不丢弃新接收端的部分拒绝。不记录远端错误文本。独立 v3 Job 执行修正版 11 个新用例及既有回归，最终专项 79 项/7 类、全量 864 项/104 类、打包均通过；原两次失败不计入绿色测试数。

真实任务/对话/AX LLM 成功、完整团队 HOLDOUT、全文日志存储、告警、规模对照、其他消费者退役与 T2/T6/T8 均不随本阶段自动完成。

独立采集诊断：实际容器 JAR SHA256 与测试产物一致，组件及工作线程存在，Collector 接收计数已包含真实 `eap-observation` 数据点。初始独立查询无序列的根因是现有 `otel-collector-self` 的 `metric_relabel_configs` 仅保留 `(substrate|otelcol)_.*`，主动过滤了 EAP 指标；Collector 原始出口实际已包含八类 EAP Gauge。候选修正只在同一 keep 规则中并入八个完整指标名，先用现有固定 Prometheus 的 `promtool check config` 验证，再 CAS 修改 ConfigMap、核对挂载文件并 SIGHUP 热加载，不重启 Collector 或 Prometheus，不放开其它重复指标，也不添加通知目标。

诊断期间各发送一次独立名称的 Protobuf/JSON 合成协议探针，均未使用 `eap_observation_*` 原登记指标名；这两条探针及连接头元数据只用于诊断，模型/工具新增仍为 0。它们不能作为真实分母或成功路径证据。初始等待、原诊断和辅助脚本保留。

初次 Collector 端口诊断采用 2 MB 读取上限，空筛选结果不足以证明接收端没有 EAP 数据；后续完整指标名读取与独立接收计数纠正了这一判断。最终根因按实际 Prometheus keep 规则和热加载后的同窗口结果判断，不将早期受限读取作为丢失证据。

最终证据：[清单](evidence/eap-independent-metrics-20261011/manifest.json)、[独立对照](evidence/eap-independent-metrics-20261011/independent-metrics-proof.json)、[过滤修正与热加载](evidence/eap-independent-metrics-20261011/prometheus-filter-proof.json)、[最终回执](evidence/eap-independent-metrics-20261011-final.json)、[本地接收](evidence/eap-independent-metrics-20261011-reception.json)。归档 1033824 bytes，SHA256 `13d0c9113a51b332a5bebc3dc64c969f4cbbc0512be5eb0ce9a425b89c76c2c8`；295 摘要、760 源、4479 旧证据、Memory 原 504839 bytes 及 AGENTS 核对通过。只推进四个已验证源码路径，绿色目录由 757 到 760。53 表 3129 原行、130 旧 Actor/88 旧模板、全部账户/两根关闭保持。PLATFORM 仍 169 调用/14012 输出/10 工具，预留 0；本轮新增均 0。

固定后端为 `10.186.133.66:5001/eap-backend@sha256:000b37429ab833518b83452ad1b1ad512642a57290ba5f3821f8cc679632b203`，实际 Pod UID `609d43db-c2dd-429a-94c5-4cde7fe37dd6`。八类共 74 序列，验收时快照年龄约 68 秒；这是一轮实际新鲜度核对，不是长期可用性或性能保证。公开发布变更见[后端补丁](../deploy/substrate-poc/observation-metrics-backend.json)与[Prometheus 单行差异](../deploy/substrate-poc/observation-metrics-prometheus.diff)。无数据库迁移，Flyway77；没有重测未变的前端/Runner、浏览器或真实模型。所有构建测试部署与诊断均远程，无 commit/push。

节点阶段已完成，原 Job 已删除；禁止重跑旧 start/build/deploy/export/cleanup/promote。绿色源码推进后，原 757 源保护函数不再用于未来阶段；后续阶段另立当前 760 源及本次 Prometheus 配置基线。

后续 T7-I 已在同一 Prometheus 原进程热加载五条[集群内聚合告警](OBSERVATION_ALERTS.md)，18 组/105 项合成断言及三条实际 ALERTS 对照通过；Collector 和应用保持。原操作/事实版本告警治理、全文日志与长期规模仍待，以下历史未接入告警描述保留当时含义。

T7-J 后端新增[原操作告警事实与确认审计 API](OBSERVATION_ALERT_GOVERNANCE.md)，没有改变本篇指标导出器或监控配置；发布后原快照新鲜、三条聚合告警保持，确认不改变 UNKNOWN 或指标值。
