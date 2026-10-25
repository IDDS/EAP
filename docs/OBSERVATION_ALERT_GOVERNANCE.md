# 原操作告警事实与确认审计

2026-10-11，T7-J 已部署并完成有界 API 验收。修正版远程专项 97 项、全量 889 项及打包通过，独立 MySQL 17 项通过；初版迁移总数断言失败原样保留。T7-I 的集群内窗口聚合告警保持；本阶段补充按原登记显式协调的持久告警与确认审计，不发送外部通知。

`GET /api/observability/operations/{id}/alerts` 只读原事实、已协调版本、告警及审计。`POST .../reconcile` 重新读取该原登记的元数据事实；同摘要无任何写入，变化时递增原操作版本，保留原告警并新建对应事实的告警。事实摘要包括原登记/冻结身份、当前身份匹配、原状态及同身份 Agent/GATEWAY 计数，不含读取时间、请求正文或凭证。A→B→A 仍为三个版本，旧确认不能作用于后来的相同摘要。

证据完整性与执行 UNKNOWN 分别生成 `EVIDENCE_GAP`、`EXECUTION_UNKNOWN`。COMPLETE/PENDING/NOT_EXECUTED/NOT_EXPECTED 不触发缺口；UNKNOWN 即使观测完整仍单列。事实变化而问题持续时旧告警 SUPERSEDED，问题消失时 RESOLVED，均留审计；确认仅将当前原版本置为 ACKNOWLEDGED，不能写业务状态、预留、结算或身份。对陈旧版本、跨原操作 ID 或尚未协调的新事实拒绝确认。两类写入都按原登记锁串行，读取不会创建告警或修复登记。

写入权限复用 `PlatformPermission.GOVERNANCE`，读取复用 `VIEW_CONSOLE`，`/me` 仍为唯一能力源；主体只存 SHA256 摘要，无自由文本审计理由。新表不回填旧业务，不以外键阻止或连带删除原业务；原登记被删除后不自动改写告警审计，但本阶段不提供脱离原登记的审计查询。

当前只有显式单原操作 API；没有后台全量扫描、自动确认/采纳、告警页面或告警通知。每次最多返回 100 条告警与 200 条审计并标明截断。协调与确认只针对事务中读到的原事实；其它写者随后提交新 Span 或状态时，读取会显示协调已过期，必须重新核对，不能把确认当作业务停止或事实不会变化的保证。

计划远程验证：同事实并发去重、确认重放、事实变化/恢复/A→B→A、未知与缺口独立、超时派生版本、跨操作与旧版本拒绝、无写读取/失败回滚、生产可见性、四角色权限、迁移不回填及 MySQL 并发。完成后按实际结果补充，真实模型/工具消费为 0；不 commit/push。

初版实际结果：独立 MySQL 8.4.11/utf8mb4_0900_ai_ci/REPEATABLE-READ 的 17 项真实 Spring 合同通过。H2 专项 97 项、1 失败/0 错误/0 跳过，唯一失败为 `appliesAllMigrationsAndSeedDataOnH2` 仍断言 77、实际迁移 78；原日志、报告及九路径源副本保留。只修正该未在 MySQL 执行的测试断言，生产源码/迁移及 MySQL 17 项的执行路径逐字一致，因此保留其原绿结果；H2 专项/全量/打包重新在 v2 目录与新 Job 验证，不覆盖原失败。


最终 v2 远程专项 97/8 类、全量 889/106 类与打包通过，失败/错误/跳过均为 0。独立 MySQL 8.4.11/0900ai_ci/RR 的 17 项原绿合同复用经过逐源核对：生产代码、迁移和执行的测试路径未变，只有未在该 MySQL Job 执行的 Flyway 总数断言变化；未重复不相关测试。前端/Runner 源码与镜像保持，未重测其历史绿色结果或浏览器。

固定后端 `10.186.133.66:5001/eap-backend@sha256:5b5de3cf6030b8b7771804a16f3c68edacc7ea589ad42ca74f1350ffd0d6e30e`，实际 Pod imageID 和 Flyway78 通过。V78 上线后先验证三个新表为空、无历史回填；随后只对 T7-G 已存在的一个 CHAT 和一个 RUNTIME_TASK 原登记显式协调。实际两头版本、三告警（两个 EVIDENCE_GAP、一个 EXECUTION_UNKNOWN）、六审计（各 CREATED/ACKNOWLEDGED）；相同事实协调和确认重放新增 0，旧版本确认 409。四角色读取 200，viewer/operator 写入 403，governance/admin 写入与 `/me` 的 governance 能力一致。没有新业务请求、原操作重试、Span 补造或费用结算。

原 UNKNOWN 保持 UNKNOWN，确认仅代表阅读；八类独立指标仍新鲜，原三条 Prometheus 聚合告警继续 firing，确认没有抑制窗口事实。53 表 3129 原行、130 旧 Actor/88 旧模板、183 全部账户及两个团队关闭保持；Collector/Prometheus 配置与部署、两规则源保持。4 Job/4 Pod 按冻结 UID 清理剩余 0，PLATFORM 仍 169 调用/14012 输出/10 工具、预留 0。

证据：[清单](evidence/eap-operation-alerts-20261011/manifest.json)、[测试](evidence/eap-operation-alerts-20261011/tests-proof.json)、[独立 MySQL](evidence/eap-operation-alerts-20261011/mysql/tests-proof.json)、[实际 API 与审计](evidence/eap-operation-alerts-20261011/live-proof.json)、[原指标和告警保持](evidence/eap-operation-alerts-20261011/monitoring-after.json)、[最终回执](evidence/eap-operation-alerts-20261011-final.json)、[本地接收](evidence/eap-operation-alerts-20261011-reception.json)。归档 1198492 bytes，SHA256 `c24c1d2379370db9f017175f796b31c28161cc429fcf118203ae8449757943d3`，337 摘要、766 源、九源副本及 4846 旧证据核对；Memory 原 511289 bytes 与 AGENTS 摘要保持。接收核对后才精确推进九路径，760→766；原导出 false 与推进后 true 回执分开保存，不回写归档。

公开发布仅[后端镜像补丁](../deploy/substrate-poc/observation-alert-governance-backend.json)。此阶段无后台全量扫描、告警页面、全文日志、规模或真实模型成功验收，不能称全部主线完成。实际 Secret 及编码仅节点 RAM 扫描；全部构建、测试和部署远程，无本机依赖安装、模型/工具新增或 commit/push。阶段已关闭，不重跑旧操作；下一阶段重新建立当前 766 源基线。

验收脚本故障同样保留：首次发布组合断言未通过，未记录具体子条件；后续完整实际 Pod/Flyway/保护门已通过。首次权限拒绝响应为空，脚本 JSON 解析中断；只读确认三个新表仍为 0、没有写回执后，保留原脚本与原两操作意图，修正空响应解析并有界续接，最终四角色、三告警和六审计通过。没有重置意图或重放业务。见 [续接核对](evidence/eap-operation-alerts-20261011/live-empty-denial-recovery.json)。

后续 T7-K 已完成[原操作告警只读页面](OBSERVATION_ALERT_CONSOLE.md)，四角色桌面/手机读取原三告警/六审计，业务请求 0。本篇“无告警页面”描述 T7-J 当时范围；后台协调、写入交互、全文日志及规模仍待。


后续 T7-L 已完成[后台有界循环协调](OBSERVATION_ALERT_RECONCILIATION.md)：持久游标、逐条事务、失败隔离与真实 Pod 恢复通过；现有确认/UNKNOWN/审计保持。页面仍只读，全文日志与长期规模待办。本篇未自动扫描的描述为此前阶段范围。


## T7-M 页面确认交互（2026-10-12）

[确认页面](OBSERVATION_ALERT_ACTIONS.md)已完成远程有界验收。245 项前端测试、类型/构建及四角色浏览器通过；按 /me governance 能力提供事实核对与两步阅读确认，绑定所见版本/摘要。409/断线不自动重试，原 UNKNOWN、费用和窗口告警保留。原三条告警均已 ACK；新增 ACTIVE 的六个浏览器场景是隔离响应夹具，线上只对既有 CHAT 做两次同事实零写协调，不能当作新的真实业务成功实验。13 原登记/13 头/三告警/六审计、预算与运行环境保持。
