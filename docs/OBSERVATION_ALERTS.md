# 观测链路的集群内告警

2026-10-11，T7-I 已发布并闭环：最终五条规则、18 组/105 项远程断言及容器内字节/摘要校验通过；原进程热加载和三条实际告警核对通过。T7-H 独立指标采集已完成；本阶段不调用模型或工具，不增加外部通知目标。

使用既有固定 Prometheus 3.5.3 的 `promtool` 在集群内验证五条规则、18 组纯合成序列及 105 项告警断言；本机只生成配置、核对文本和摘要。规则只读取 T7-H 的八类指标，限定原 `otel-collector-self`/`otel-system`/`eap-observation` 来源，按原 1440 分钟窗口评估。标签聚合后只保留固定告警名、owner、severity 和必要的固定入口，不携带 Trace、操作键、UID、主体或正文。

| 告警 | 判断与边界 |
| --- | --- |
| `EapObservationSnapshotUnavailable` | 快照缺失、重复、年龄超过 180 秒或时钟超前超过 5 秒 |
| `EapObservationRegistryIncomplete` | 快照新鲜但八类指标集合、74 条序列或窗口不符合本 PoC 合同 |
| `EapObservationDenominatorUnconfirmed` | 非旧外部入口的分母未确认，单列显示；旧外部 known=0 为已有未知边界 |
| `EapObservationEvidenceGap` | 分母确认、快照新鲜且指标集合完整时，GAP/UNCONFIRMED 大于 0；PENDING 不算缺口 |
| `EapExecutionOutcomeUnknown` | 同样门禁下原执行 UNKNOWN 大于 0，与观测缺口分开判断；FAILED 不改成 UNKNOWN |

所有规则持续满足两分钟后才触发，状态恢复后撤销。陈旧快照抑制指标集合、分母和业务状态判断，避免使用过期数值解释业务状态；快照失联自身单列告警。规则存在不等于数据新鲜或模型成功。查看入口指向既有 `/observation-coverage`，只读取原事实，不派发或重试业务。

这些是链路和窗口聚合告警，告警标识按固定类型/入口保持；尚未实现绑定原操作及事实版本的持久去重、确认/关闭审计或 EAP 告警页面。既有 Prometheus 未配置 Alertmanager，本阶段只添加规则文件及引用并热加载，保留全部原采集规则。不会配置邮件、Slack、Webhook 或其它通知目标。

发布前先冻结当前应用/Collector/Prometheus/源代码/预算/历史基线。使用原固定工具验证实际规则与夹具及候选配置，再按 ConfigMap UID/resourceVersion CAS 发布、核对挂载字节并向原 Prometheus 进程 SIGHUP。已核对全部规则 health=ok、实际 ALERTS 序列与持久原报告：任务/对话各一条 EvidenceGap、对话一条 OutcomeUnknown，总计三条 firing；快照、集合及分母三类健康告警 inactive。T7-G 原事实保留，未新增业务请求。Collector 和 Prometheus 容器 startedAt 均早于本阶段冻结时间，证明未因本阶段重启监控进程。

公开配置：[规则](../deploy/substrate-poc/observation-alerts.rules.yml)、[合成夹具](../deploy/substrate-poc/observation-alerts.test.yml)。格式依据 [Prometheus 官方规则单测](https://prometheus.io/docs/prometheus/latest/configuration/unit_testing_rules/)与[告警规则文档](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/)，访问日期 2026-10-11；实际兼容性以集群固定 3.5.3 工具结果为准。

全文日志存储、原操作告警治理、长期可用性/规模、真实模型成功、完整团队 HOLDOUT 和其它主线不随本阶段自动完成。新 27 调用/9216 输出/工具 0 计划仍待明确授权，旧根及限额不变，无 commit/push。

发布前保留了初版 17 组/100 项的绿色结果及候选配置；随后显式限定七个固定入口，并加入异常入口夹具。异常入口使集合告警触发，但其任意字段不能进入逐入口告警标签。最终版本已重新通过 18 组/105 项验证，初版未发布、不作为最终版本测试结果。

v2 规则语法通过，夹具在 `kubectl exec -i` 传输后容器内只有 32768/375332 bytes，直接文件摘要证实为原始前缀，YAML 解析失败且告警断言尚未执行。原失败/源码/摘要保留；v3 使用独立目录，通过命令参数分块解码写入，并在容器内验证完整字节数与 SHA256 后才运行同一版规则和 105 项断言。此修复只影响验收辅助传输，规则和夹具内容未变。


最终证据：[清单](evidence/eap-observation-alerts-20261011/manifest.json)、[远程测试](evidence/eap-observation-alerts-20261011/tests-proof.json)、[挂载输入校验](evidence/eap-observation-alerts-20261011/fixture-transfer-proof.json)、[实际规则与告警](evidence/eap-observation-alerts-20261011/live-proof.json)、[进程保持](evidence/eap-observation-alerts-20261011/monitoring-process-proof.json)、[最终回执](evidence/eap-observation-alerts-20261011-final.json)、[本地接收](evidence/eap-observation-alerts-20261011-reception.json)。归档 241178 bytes，SHA256 `8258564141086109a2f2bc8cccc8b89caf49a196c0890d2d22e28388828a7455`；66 项摘要、760 应用源、两配置与 4777 旧证据逐项核对。只推进两个配置文件，应用源码清单保持 760；原 Memory 508215 bytes 和 AGENTS 摘要保持。

53 表 3129 原行、130 旧 Actor/88 旧模板、183 账户及两团队关闭结果保护通过。应用源码/镜像/环境与 Collector 保持；Prometheus ConfigMap 只增加已验证规则文件和 rule_files 引用，原采集配置字节前缀保留。两分钟持续条件及实际 firing 已核对；这是一轮事实匹配，不是长期告警可用性保证。未创建或删除监控 Pod/Job，六个测试临时文件及三个目录清理剩余 0；无本机构建、模型/工具消费、外部通知、commit 或 push。

阶段已关闭，禁止重跑其 init/tests/publish/reload/finish/promote。只读 API 核对须另建当前基线；归档保留导出时 configurationArtifactsPromoted=false，核对后推进的 true 最终回执独立保存，不回写原证据。

后续 T7-J 已完成[显式单原操作事实去重与确认审计](OBSERVATION_ALERT_GOVERNANCE.md)：三条实际原事实告警及六条审计，重放新增 0，确认后原三条 Prometheus 窗口告警继续触发；本篇“尚未持久去重”仅为 T7-I 当时范围。后台全量扫描、告警界面、全文日志与规模仍待。
