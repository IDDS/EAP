## 最新：普通 Runtime 的模型预期可按提交声明（2026-10-20）

已部署 `EAP_OBSERVATION_V1`：绑定智能体的普通 `AGENT`/`ARTIFACT` 任务可以声明模型预期 `NONE`（0）或 `ONE`（1），缺省仍为 1。预期随原提交事务登记并冻结，不因草稿修改或同 requestKey 重放改变；不回填历史。Agent Span、原实例身份及 Trace 回执仍必须匹配，声明 0 却收到 Gateway 事件会显示 `UNEXPECTED_MODEL`。这不是模型禁用或效果证明。

新 Agent79 / Workflow33 原任务及工作流成功，模型 0/0、Agent 1/1，覆盖率 `COMPLETE`；MCP 成功/失败/UNKNOWN 各 1 均保留。四角色 API、桌面/手机验证通过。旧 Workflow31 继续保持模型预期 1、`MISSING_MODEL` 和原失败结果，历史全局 UNKNOWN 未改。新任务显式关闭任务后自动休眠，随后经 EAP 手动休眠撤销；本轮不证明自动回收成功。

[配置与证据判定](WORKFLOW_OBSERVATION_CONTRACT.md)、[验收记录](evidence/observation-contract-20261020/README.md)。下文“每个任务预期一条模型事件”是原默认规则；新明确声明的普通 Runtime 任务按本节执行，聊天、实验、AX 和内置工具规则保持。

## 历史：T7-K 原操作告警只读页面有界闭环

原操作证据抽屉已展示告警、事实版本和确认审计，四角色桌面/手机通过，原 UNKNOWN、费用和窗口告警保持。只 GET，读取失败或切换操作/账号时清空旧事实，详见[页面范围与验收](OBSERVATION_ALERT_CONSOLE.md)。后台协调、全文日志、真实模型成功及规模仍待。

## 最新：T7-J 原操作告警事实去重与确认审计有界闭环

同原登记/事实版本的显式协调、确认与恢复/替代审计已部署，原两个登记的三告警/六审计实测通过，四角色权限、重放新增 0 和旧版本拒绝通过。确认不改 UNKNOWN、原身份、费用或聚合告警。证据与边界见[原操作告警合同](OBSERVATION_ALERT_GOVERNANCE.md)；后台全量扫描/告警界面、全文日志、真实模型成功与规模仍待。

## 最新：T7-I 集群内聚合告警已闭环

五条规则的 18 组/105 项远程断言通过，原进程热加载后任务/对话各一个缺口、对话一个 UNKNOWN 告警与持久事实一致；没有新业务请求或模型/工具消费。证据与边界见[观测告警合同](OBSERVATION_ALERTS.md)。全文日志、持久原操作告警治理、真实模型成功与规模仍待，下文为历史状态。

## 最新：T7-H 独立指标采集已闭环

八类/74 条 Prometheus Gauge 与 1440 分钟原报告逐项对应，任务 2、对话 1、旧外部未知保留；最终回执和采集边界见[独立指标合同](OBSERVATION_METRICS_EXPORT.md)。下文 T7-G/T7-F/T7-E 的“独立指标待验收”保留当时含义。真实模型成功、全文日志、告警及规模仍待。

# 工作流模型任务与对话的原始观测登记

最新 T7-G（2026-10-11）：真实新 Actor 的零额度拒绝路径通过。Agent68 使用 T7-F 固定新 Runner，三项 Agent 额度均为 0；新工作流原登记 2 个模型节点，首节点实际派发后 FAILED，后续未派发节点仍保留分母。原对话只提交一次，HTTP400，原 `EXECUTION_UNKNOWN` 保留。两条实际 Agent Span 与原 Trace/runtime 端点对应，登记、派发、回执结构化事件可查；Gateway 模型事件及预算预占均为 0，缺失模型证据未显示为完整成功。四角色只读覆盖指标核对通过，未重试、未运行真实上游模型或工具。

[最终回执](evidence/eap-runtime-zero-quota-20261011-final.json)、[验收证明](evidence/eap-runtime-zero-quota-20261011/acceptance-proof.json)。归档 193256 bytes / SHA256 `e81c06f23f4d7369af2cc0c5a2fda4bba87c243dc7c61f62a521fb548e427076`，23 项摘要、750 源码、4415 旧证据及 PROJECT_MEMORY 原 500237 bytes 前缀核对通过。53 表 3086 原行、127 旧 Actor、85 旧模板、旧账户和 root1/root2 关闭结果保持；新原 UID `5c7abe79-6d3f-4911-9574-7ad84ebc4320` 已休眠撤销，未创建临时 Job/Pod。业务源码和部署未改，无本机构建/测试，无 commit/push。真实模型成功路径及独立指标采集、全文日志、告警、规模仍未完成；[下一轮独立预算计划](NEXT_LIVE_ACCEPTANCE_PLAN.md)尚待授权。

最新 T7-F（2026-10-11）：任务/对话可信分母登记已启用。未来默认 Runner 已更新为 `10.186.133.66:5001/eap-runner@sha256:43e112555b4dfdb67a79b0869ecad1290192a3e3cfeeb342d1dc0006d7feb83c`；镜像 manifest/config/layer 与 T7-E 通过普通及竞态各 37 顶层/73 含子项的二进制 `7a7b6ac3…e3802851` 一致，Runner 全源亦与既有绿色产物核对。实际后端 Pod imageID、Flyway77、两个公开配置字段及四角色读取通过；新 RUNTIME_TASK/CHAT 原登记指标为 0，LEGACY_EXTERNAL 仍为 null。既有 Actor/template 未升级，新默认只用于未来发布；10 个刚迁移的 RUNNER 仍保留其已验证的 `2146291f…` 版本。

[最终回执](evidence/eap-runtime-denominator-release-20261011-final.json)、[实际发布证明](evidence/eap-runtime-denominator-release-20261011/release-proof.json)。归档 190364 bytes / SHA256 `2f120392bf2307b24ee7c0ce85a9fd8cb281b654f8d5774160603945ff4bf35c`，27 文件摘要、750 源码与 4386 旧证据核对通过；1 Job/1 Pod 按 UID 清理剩余 0，旧数据、身份、模板、预算及 root1/root2 关闭结果保持。业务源码未改，未重复既有测试；本次只远程打包已测试产物、配置发布与真实四角色核验，模型/工具新增 0，未 commit/push。配置补丁为 [runtime-denominator-backend.json](../deploy/substrate-poc/runtime-denominator-backend.json)，不整份应用历史清单。

T7-F 发布后的零额度拒绝核验已由 T7-G 完成；任务/对话/AX LLM 的真实模型成功路径、独立指标采集、全文日志、告警和规模验收仍未完成。以下默认关闭/未部署描述属于 T7-E 历史状态。

日期：2026-10-11，T7-E。实现、远程隔离验证、归档接收、UID清理及绿色源码推进已闭环。后续T3-H2D已将后端代码及V77部署，runtime-denominators开关仍false，新Runner尚未部署；本入口真实模型/工具验收未新增。本文末尾原T7-E回执保留当时deployed=false含义。

`eap.observability.runtime-denominators-enabled` 默认 `false`。开启后，新工作流和计划所解析路径中的常规模型任务，在原提交事务内登记每个节点的原任务键、Agent、平台生成的 Trace 和预期事件数。未到达的后续节点保留分母；原提交事务回滚不留登记，原 requestKey 重放不重复登记。AX 继续沿用 T7-D；工具节点、人工任务台账和旧外部入口不借用模型任务规则。

常规任务在既有身份、准备及原责任屏障通过后、第一次 HTTP 发送前，冻结实际运行登记、端点、UID、模板及原派发期限。平台将原 Trace 放入任务信封；Runner 的模型请求、`agent.task` 和回执使用同一 Trace。重复原派发登记不能重新发送，丢失响应保留未知。已登记的原 Trace 不从客户端上下文或已收到的 Span 推导。

控制台对话在既有身份检查后、唤醒前登记一个新意图；每次 POST 表示一次新对话轮次，接口尚无 caller requestKey，不提供幂等重试承诺。唤醒失败、HTTP 响应丢失及未知结果分别保留。新 Runner 接受平台 Trace，并在 `agent.turn` 有界导出结束后返回带 Trace 的回执，防止成功返回后立刻挂起而冻结尚未开始的导出；未提供 Trace 的历史请求继续使用原行为。

每个登记预期一条同身份 Agent Span 和一条 GATEWAY 模型事件。OTLP MODEL 或其他上报事件不能替代 GATEWAY。旧 Runner 缺少 Trace 回执、回执 Trace 不匹配、实例 UID/模板/端点变化、缺失或重复证据均单列；业务成功不自动代表证据完整。未知业务结果仍可独立显示已取得的观测证据，不能从统计分母删除。

V77 仅增加可空运行状态及派发期限列，不回填历史记录。派发进程消失时，读取超过原 HTTP 期限的运行意图显示 `EXECUTION_UNKNOWN`，不写回成功、不接管、不重新派发或续期。原费用通过同 Trace/Agent 关联，只展示实际预留、结算和已知 usage，不补零或退款。登记不替代原身份、工作流责任、预算或工具效果准入。

沿用只读 coverage/detail API、中央 `PlatformPermission.VIEW_CONSOLE` 和原内容隔离。新日志只有固定事件、原因码和原 Trace；不保存消息、提示词、输出、凭证或异常文本。窗口指标按持久原登记计算；开关关闭且没有新类型登记时分母仍为 `null`，不把缺少登记解释为零调用。旧外部入口始终保持未确认；不宣称覆盖其历史调用。

本阶段只扩展工作流/计划的常规模型任务及控制台对话。AX LLM 的真实端到端、独立指标采集、全文日志、告警、规模对照、业务工具和旧消费者迁移、统一调度及策略治理仍需后续验收。真实团队验收另见 [候选方案](TEAM_EVOLUTION_LIVE_ACCEPTANCE.md)，其公开任务/24调用原计划已获“继续”确认，但首次UNKNOWN即停止并已不完整关闭；修复后的下一次独立支出须重新明确。

## 验证记录

新增 10 项 Spring 集成用例已通过，覆盖原提交/回滚/重放、未到达节点、实际任务/对话 HTTP 信封、GATEWAY 证据来源、旧及不匹配回执、UID 漂移、失联期限、未知结果与零自动重试。测试中的 Runner 为远程 Pod 内 loopback HTTP 夹具，不代表真实 Actor 或供应商调用。前端测试/生产构建、Runner 普通/竞态/编译已通过；后端专项与全量、隔离 MySQL 20 项及最终证据尚待收尾。

## 旧消费者迁移盘点

本轮通过 n1.sh 只读枚举全局及各作用域模板，只保存数量：71 个模板，其中 39 个仍有旧共享凭证环境引用，22 条发布记录仍为旧身份模式。全局与各作用域枚举数量一致；这不能证明上游分页完整、挂载文件、黄金快照或外部消费者已全部覆盖。没有迁移或退役凭证，没有修改模板、Actor、旧发布或预算。共享凭证退役条件仍不满足，后续需据明确迁移范围逐项核对。


## 最终证据

[证据清单](evidence/eap-runtime-observation-20261011/manifest.json)、[最终回执](evidence/eap-runtime-observation-20261011-final.json)：归档7173482bytes/SHA256 aea34832d5e08872715faa704af6a0b3f8499fc1dfd49dc5a991a6d84ef2956b，280摘要、11源副本及749完整源核对；sourceManifest SHA256 ee1e4200b9ec174c3f11e6401997b060fa95b726a699b4cfd0ea720fa285cbc4。4Job/4Pod按冻结UID清理剩余0；实际Secret仅节点RAM扫描，原33表2784行+6表、费用/身份/UID/模板/全部镜像及环境保持。接收核对后精确11路径推进/root/eap-src，节点/root/eap-t7e-20261011/final-completion.json completed/closed/sourcePromoted=true、deployed=false。生产仍Flyway70/团队表0，真实模型/工具0，未构建应用镜像/部署/浏览器、未commit/push。原3250证据摘要、AGENTS/BP/其他线程保持。


## 2026-10-11 后端上线边界

后端固定镜像及V77随T3-H2D上线，runtime-denominators仍关闭；仅含代码不等于本入口可用或真实验收完成。未发布新Runner、未跑任务/对话模型、未做浏览器/Pod崩溃或独立指标/日志/告警/规模验收。迁移范围已明确为仓库现有工具和旧发布，39旧共享引用/22旧发布尚未迁移；共享凭证仍不能退役。[本次实测与回执](TEAM_EVOLUTION_LIVE_ACCEPTANCE.md)。
