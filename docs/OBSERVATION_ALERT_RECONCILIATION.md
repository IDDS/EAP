# 原操作告警后台协调

2026-10-12，T7-L 已部署并完成有界验收：远程专项 111、全量 903、隔离 MySQL 30 与打包通过；真实后台循环及部分进度后的 Pod 更换续接通过。仓库默认关闭，PoC 显式每批 2 条/15 秒；模型和工具新增 0。主线尚未全部完成。以下合同与过程记录保留，最终实测与证据见文末。

巡检只读取已经登记的原操作。候选筛选复用原事实查询的生产可见性，排除 DEMO；无创建时间窗口，不补造历史登记或遥测。每轮冻结最大可见 ID，按 ID 递增分批扫描，新增操作进入后续轮次；完成后从头复查，让旧操作的迟到证据、身份变化和派发期限变化得到协调。尚未登记的入口仍不能据此宣称覆盖完整。

V79 仅创建单行持久游标，不回填告警或修改业务。每条协调在 READ_COMMITTED、5 秒超时的独立事务中锁定游标，复用原操作告警协调服务，然后原子提交该条事实和游标进度。多副本按数据库锁串行；进程中断后，另一实例续接已提交位置。固定轮次防止旧批次越过新轮次；每批最多 100 次尝试，默认 50 次。

单条失败时，其告警和审计整体回滚。独立事务只在轮次与游标版本仍匹配时跳过该条，累计失败次数并保留原操作 ID、固定错误码和时间；不保存异常文本、请求内容或凭证。后续正常原操作继续，下轮可复查失败条目。失败检查点若提交失败，批次停止，保留数据库原位置。失败操作不会被改为成功，也不会重试业务、模型或工具请求。

默认启动等待 30 秒、周期 60 秒、每批启动工作时间预算 10 秒。时间预算在每条事务前检查，已经启动的事务可继续至自身超时；该预算不是整个批次的硬性截止。独立守护线程与进程内 busy 标记避免占用调度线程和本实例重叠任务；关停中断线程，数据库事务负责提交或回滚。

| 配置 | 默认 | 约束/用途 |
| --- | --- | --- |
| `EAP_OBSERVABILITY_ALERT_RECONCILIATION_ENABLED` | `false` | 显式启用后台组件 |
| `EAP_OBSERVABILITY_ALERT_RECONCILIATION_BATCH_SIZE` | `50` | 1–100 次尝试/批 |
| `EAP_OBSERVABILITY_ALERT_RECONCILIATION_MAX_RUN_MS` | `10000` | 100–30000，逐条启动前检查 |
| `EAP_OBSERVABILITY_ALERT_RECONCILIATION_INITIAL_DELAY_MS` | `30000` | 应用启动后首次调度等待 |
| `EAP_OBSERVABILITY_ALERT_RECONCILIATION_INTERVAL_MS` | `60000` | 批次触发间隔，忙时跳过 |

相同原事实不增加告警版本、不刷新事实时间，也不撤销原确认。新事实按既有规则替代或解决旧告警；确认仍表示已阅读，UNKNOWN 与费用结算保持。系统审计主体为固定标识的 SHA256，页面和人工接口的权限仍从 `PlatformPermission` 与 `/me` 读取，没有新增后台控制接口。游标表提供轮次、位置、累计处理/失败和最近失败元数据；固定结构日志提供每批计数，不记录异常堆栈或内容。

远程验收覆盖持久恢复、扫描上界、重复确认保持、旧操作迟到证据、过期派发、身份漂移、单条失败回滚与继续、多副本并发、删除与生产可见性、空库无写、时间与数量上界、迁移无回填。隔离 MySQL 使用真实事务和行锁。实际 PoC 将先发布关闭版本核对原事实，再启用小批巡检，验证真实后台进度和 Pod 更换后的续接；不重新提交旧业务。

全文日志、告警写入页面、外部通知、长期规模、真实新付费实验与完整团队 HOLDOUT，以及其它主线仍未完成。不得将循环检查的次数视为业务重试次数或新的执行分母。


2026-10-12 远程阶段结果：隔离 MySQL 8.4.11/utf8mb4_0900_ai_ci/REPEATABLE-READ 的 30 项、两类真实 Spring/行锁合同全部通过。初轮 H2 专项 111 项、九类，唯一失败为未修改的 `ObservationMetricsExporterTests.sendsOnlyProtobufSnapshotToReceiverAndAcceptsOriginalReceipt` 返回 false；新增 13 项巡检合同与迁移合同通过，全量/打包未执行。生产与测试源码未因此改动。原日志/XML 与失败回执保留；原导出器不记录异常详情，根因尚未确认，不能归因为已证实的网络或负载问题。

对逐字相同源码仅做一次有界复核，第二轮专项 111 项已通过，正在继续全量与打包。MySQL 源码及执行路径未改动，使用其原始 30 项结果；不重跑模型、工具或业务。候选 774 源/八路径，绿色目录仍为 769。T7-L 自 10 月 11 日开始，阶段目录保留原日期；后续实测与关闭日期按实际记录。


持久恢复的实际验收只针对现有 13 个原登记：其中 11 个原事实为 COMPLETE 或 NOT_EXECUTED，允许增加无告警的事实头；已有两头、三告警、六审计必须逐字保持。PoC 初次启用每批 2 条、周期 15 秒，等待实际部分进度后仅调整公开启动等待值触发同固定镜像的新 Pod，再验证游标单调推进并至少完成两轮。原业务、观测事件、模型、工具、账户、旧 Actor/模板和团队关闭不允许变化。未来运行时间变长导致 1440 分钟窗口自然缩减时，应按冻结原事实的 createdMs 重算当前报告与独立指标，不改原登记时间维持旧窗口告警。


第二轮最终测试：同源专项 111/9 类、全量 903/107 类和打包 PASS，失败/错误/跳过均 0；MySQL 原 30/2 类 PASS。现在构建精确测试 JAR 镜像，尚未发布。此前“正在继续全量与打包”为阶段过程记录。


镜像构建脚本复用时遗漏了目的标签与 stage 标签的字面替换：本轮 Job 名/冻结 UID 为 T7-L，但构建错误写入 `eap-backend:t7j-20261011`，Job/Pod 标签亦为 t7j。原请求和日志保留，未重建镜像；正在将旧标签恢复为原固定 digest，并把本轮镜像登记为 t7l。线上应用使用固定 digest，未因标签覆盖改变；后续清理依冻结 UID，不按 stage 标签。修正结果待回执核对，不能把该问题描述为构建基础设施故障。


2026-10-12 最终实测：远程同源复核专项 111/9 类、完整 903/107 类及打包通过，独立 MySQL 30/2 类通过，最终失败/错误/跳过均 0。初轮 111 项中既有指标 HTTP 正向测试的唯一失败保持；原异常类型未记录，根因仍未确认，不能据复核通过声称已修复该偶发故障。源码未因此改动，旧失败 Job/XML/日志和一次同源复核意图均归档。

构建脚本目的标签漏改造成的旧 t7j 标签误覆盖已恢复：旧标签重新指向原 5b5de3cf…，新镜像登记到 t7l；原 Job 请求、日志和[修正回执](evidence/eap-alert-reconciliation-20261011/image-tag-repair-proof.json)保留。Job/Pod 的旧 stage 标签没有用于资源识别，清理均按冻结 UID。没有重建镜像、重放部署或改动原固定引用。

后端固定 `10.186.133.66:5001/eap-backend@sha256:92a3cf2fdd2171b2f42880cf8d9f1047b16539cec2afc0ef21fb4e090c27c815`，镜像配置与末层 JAR 摘要等于测试打包结果；实际 Pod/Flyway79 通过。先发布默认关闭版本，单行游标全零、所有原操作视图逐字不变、无告警回填。随后只启用五个公开巡检字段；实际每批 2 条/周期 15 秒、最大启动预算 10 秒，部分扫描后仅将启动等待 5000→7000ms 触发同镜像新 Pod。原 Pod `e30f3c84-266e-402e-bbe3-904bfe63da56`、后续 `c1136715-71b7-45ef-b4e8-fcc27669505d` 不同，持久轮次/修订/处理计数单调，至少完成两轮，失败计数 0。

13 个原登记全部协调当前事实，11 个新增无告警事实头；已有两头/三告警/六审计及其原时间戳逐字不变，原 CHAT UNKNOWN 与任务 FAILED 保持。未调用协调/确认 API 或重放业务，原状态、遥测和费用未写；全部 183 账户、130 旧 Actor/88 旧模板、两团队关闭保持。当前 1440 分钟窗口按冻结原 createdMs 独立重算；八类/74 条 Prometheus 指标与当前报告一致、快照新鲜，当前窗口触发 3 条告警，允许原登记自然到期。Collector/Prometheus 配置/部署和两规则源保持；未增加外部通知。

4 Job/4 Pod 按冻结 UID 清理，剩余 0，原失败资源先保存日志再清理。349 项清单，归档 1177889 bytes，SHA256 `2dc12d22cd03ebf7627018512cf346c015f53d6cefbf443dd74ab0c38121330b`。本地逐项核对 774 源、八源副本、5253 旧证据、Memory 原 521014 bytes 和 AGENTS 摘要后才推进八路径，769→774；推进前后回执分开保存，归档不回写。实际 Secret/编码仅节点 RAM 扫描，凭证文件导出 0。

证据：[清单](evidence/eap-alert-reconciliation-20261011/manifest.json)、[测试](evidence/eap-alert-reconciliation-20261011/tests-proof.json)、[原唯一失败](evidence/eap-alert-reconciliation-20261011/original-metrics-receiver-failure.json)、[MySQL](evidence/eap-alert-reconciliation-20261011/mysql/tests-proof.json)、[启用前迁移](evidence/eap-alert-reconciliation-20261011/release-proof.json)、[实际后台与 Pod 恢复](evidence/eap-alert-reconciliation-20261011/live-proof.json)、[当前窗口与独立指标](evidence/eap-alert-reconciliation-20261011/monitoring-after.json)、[最终回执](evidence/eap-alert-reconciliation-20261011-final.json)、[接收](evidence/eap-alert-reconciliation-20261011-reception.json)。公开部署仅[后端与五个公开配置补丁](../deploy/substrate-poc/observation-alert-reconciliation-backend.json)；前端及 Runner 源/镜像未变，未重跑其构建或浏览器。

补充日志只读盘点覆盖 Deployment/StatefulSet/DaemonSet、Service/PVC/CRD：35/1/4 个控制器、24 Service/6 PVC 中按名称/镜像匹配 6 个对象，匹配日志 CRD 为 0；仅元数据，无 Secret 或配置正文，不能排除外部或不透明命名存储，全文日志仍未验证。见[盘点](evidence/eap-alert-reconciliation-20261011/logging-storage-inventory.json)。

本阶段已关闭，不重跑原测试/发布/启用/替换或清理入口。下一阶段重新冻结当前 774 应用源、两规则、13 头/三告警/六审计和持续推进的游标。后台协调已完成有界验证；告警写入 UI、全文日志、长期运行/规模、真实新付费成功实验/完整团队 HOLDOUT 与其它主线仍未完成。
