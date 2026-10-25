# 团队平台评分与可信前沿（T3-H2B，2026-10-10）

本阶段在[原槽位执行适配](TEAM_EVOLUTION_EXECUTION.md)之上增加默认关闭的平台完整验证批次评分、每样本责任时延与持久前沿封存。最终远程验证、归档接收、UID清理与绿色源码精确推进已完成；未部署。独立HOLDOUT、真实团队端到端、生产GEPA接入及自动采用仍未完成。

## 原证据与评分

`eap.team-evolution.scoring-enabled`默认false，写入还要求合同开关开启。中央`PlatformPermission.RUN_AGENT_EXPERIMENTS`保护评分和封存写入，且只能由根的原主体操作；GET使用`VIEW_CONSOLE`，只返回指标、摘要、原因和样本ID，不返回输入、expected、输出、提示词、业务任务、机器身份epoch或凭证。`status.platformScoringAvailable`表示配置能力，不是身份/预算授权或真实效果证明。完整搜索`executionAvailable/receiptIngestionAvailable`继续false。

新增API：

- `POST /api/team-evolutions/{root}/evaluations/{evaluation}/score`，严格正文`{"requestKey":"original-score"}`。
- `GET`同路径读取原评分。
- `GET /api/team-evolutions/{root}/frontier`，未封存时为当次只读预览，封存后返回保存的原快照。
- `POST`同frontier路径，严格正文`{"requestKey":"original-frontier"}`，封存本根验证前沿。

没有外部回执、质量、usage或时延写入接口。原请求键/参数/主体冻结，重复只读原结果，改键或参数冲突。评分不会调用Runner、模型、工具或实时profile检查；原业务证据完成后，即使身份已撤销或授权到期，仍可进行原证据的审计评分，不能因此重新派发。

评分要求完整冻结VALIDATION批次，每个原样本恰好两角色，执行与评价均`RECEIPTS_COMPLETE`。重新核对协议/数据/验证摘要、候选配置、原holder/绝对期限、原角色Agent/runtime/实例/UID/模板/endpoint/profile绑定、原请求与模型载荷摘要、第一角色原输入和第二角色完整交接、完整输出及Gateway正文摘要/finish reason。每次消费必须有原Trace、Agent、TASK根的MODEL预留、已知且未超上界的SETTLED usage、对应唯一SETTLED settlement，以及PLATFORM/实际Agent/TASK的MODEL_CALLS和OUTPUT_TOKENS六归属项。Runner自报usage、时延及OTLP span不作为证明。

`EXACT_TEXT_V1`只比较第二角色完整正文与冻结expected；不trim、不做大小写或Unicode归一化，不读取TRAIN/HOLDOUT评分。完整业务答案错误计分0，两个角色的已知实际token照计；分母始终为冻结验证样本总数。缺失角色、回执、时延责任或账本，以及UNKNOWN/FAILED/VIOLATION都不能形成完整评分；不以0分、0费用或0时延补齐。

结果包含passed/denominator、向下取整的展示qualityBps、两角色实际输入/输出及总token、每样本/整批时延之和、协议标记和原证据摘要。质量门禁和选择用整数交叉相乘比较精确比例，不使用展示qualityBps或浮点数。原评分单独保存，不把执行状态改为业务成功，也不回填旧评分。

## 整任务时延

V74新增`team_evolution_sample_responsibilities`。每个样本在首次运行准备/profile HTTP之前，持久保存原执行holder与平台数据库微秒起点，终点取该样本第二角色完整可信回执确认的原finished_at。口径`PLATFORM_SAMPLE_RESPONSIBILITY_MICROS_V1`覆盖首次准备检查、原派发意图、两个角色调用、完整交接及第二次准备检查；整个批次指标为所有样本该区间之和，不把整批墙钟时间当作单样本时延。

它不包含客户端到平台的网络时间、根批准与候选提案时间，也不是模型推理时延。所有时间来自同一平台数据库，检查起止与串行先后关系；时钟倒退或缺失拒绝评分，不截为0。证据摘要保留微秒时间字符串，避免默认SQL Timestamp JSON编码丢失微秒。T3-H2A及更早回执没有样本责任起点时不能补算或回填；不能重放旧UNKNOWN/FAILED来制造新测量。

## 可信前沿与费用

在后端复现[T3-F离线候选](AGENT_EVOLUTION_CANDIDATES.md)的规则，输入改为本平台原评分：基线先完整确认；质量门槛为配置下限与同批基线质量的较大值；质量越高、同批两角色总token越少、整任务时延之和越小越优，各项不差且至少一项严格优才构成支配。完全相等的不同配置都保留，候选摘要排序确保确定性。未运行或UNKNOWN候选保留`EVIDENCE_INCOMPLETE`，完整但未达门槛为`QUALITY_GATE_REJECTED`，其费用仍计入根账本。

留出候选选择规则固定为质量降序、token升序、时延升序、摘要升序；只是未来独立HOLDOUT的候选，没有应用配置或采用动作。输出`NO_ADOPTION_HOLDOUT_REQUIRED`。这不是直接运行Python模块，也未接入生产GEPA循环或改变optimizer镜像。

前沿每根只封存一次，保存全部已注册候选、评分、各候选决定、前沿、选择规则与当时账本。基线未评分、完整回执尚未评分、原执行仍RUNNING或网关费用仍在DISPATCHED结算中均拒绝封存。已注册但未运行的候选可以明确作为不完整关闭。根锁串行化评分/候选登记/封存；封存采用与Gateway一致的共享预算锁→原执行锁顺序，执行完成状态、费用预留和TASK在途量必须为MySQL当前读，取得锁本身不会刷新RR旧快照；封存后阻止新候选、新验证登记、新执行和新评分，原键重复读取保持。没有续期、接管、重试、第二前沿或自动采用。

费用聚合覆盖本根所有MODEL预留，包含被支配、低质量和不确定候选，分别显示SETTLED/UNKNOWN/在途/VIOLATION数量、已知输入/输出token、unknownUsage及保留的输出预留。未知部分不以0宣称已知总消费，不退款或清零；封存拒绝在途费用以避免迟到结算改变快照。这里报告实际发生的验证消费，不是货币价格或尚未执行的HOLDOUT消费，也不证明整个搜索已完成。OFFLINE_ONLY提案不调用模型。

派发和评分统一按样本ID字符顺序、角色序号排序，不依赖数据库大小写/标点排序规则。

V74三个新表仅保存新责任/评分/前沿，不修改历史合同、槽位、预算或结果。生产迁移和开关未变。

## 验证与下一步

第四版远程专项219项/10类、完整809项/102类及打包通过，0失败/错误/跳过。最终第五版仅补强封存当前读及其并发测试两个路径；补强后220专项/10类和打包通过，0失败/错误/跳过，完整回归未重跑，不将第四版809项说成第五版全量结果。第三版专项217项通过，完整回归因最终版接替而停止，full=INTERRUPTED_NOT_VERIFIED、package=NOT_RUN，源码与已执行日志保留；不将它记为全量通过。v2仅上传未启动。独立MySQL8.4.11/0900ai_ci迁移、大字段/微秒、唯一/FK/回滚及双连接原评分创建通过（首创建1、重复0），预算快照12/当前读1复验；新增同一根锁/共享锁后费用普通旧快照仍0、当前读得到实际1调用/2输入/3输出，执行旧快照RUNNING/当前RECEIPTS_COMPLETE。SQL夹具不是Spring真实MySQL、真实Actor或Pod崩溃验证。

[测试汇总](evidence/eap-team-scoring-20261010/test-summary.json)、[证据清单](evidence/eap-team-scoring-20261010/manifest.json)、[最终回执](evidence/eap-team-scoring-20261010-final.json)：500摘要/10最终源副本/三历史版本/742源核对，归档1598726bytes/SHA256 c0de5b3025135653ea834dcb459c92ccccc62202ddb7ae25b260559cc44f81af。接收核对后才精确10路径推进节点绿色源码；6Job/6Pod按UID清理剩余0。原33表2784行及6表/费用/身份/镜像/环境保持，实际Secret只节点RAM扫描。生产Flyway70/团队表0、新预算/真实模型/工具0，未构建应用镜像/部署/浏览器或commit/push。回归使用Spring H2和Mock上游/Runner，真实团队端到端、Router并发自动唤醒边界与固定镜像实时imageID证明仍待。

首轮testCompile失败来自新增测试夹具的JsonNode/ObjectNode类型调用；业务测试未运行，完整回归和打包未执行，原报告保留。修订版同时更新Flyway数量断言并拒绝在途费用封存。

下一步为独立基线/所选候选HOLDOUT批次及根完整消费闭环，再依据明确业务任务和新的独立预算做有界真实团队验收。旧127次/11371输出和128/32768上限保持，剩余1次差额不是新授权；不改旧/共享caps/revision、不拆根或重放UNKNOWN/FAILED/KEEP_BASELINE，不自动采用。主线未全部完成，没有稳定业务收益证明。


### 2026-10-10 T3-H2C续接

[独立HOLDOUT与全根消费](TEAM_EVOLUTION_HOLDOUT.md)已完成最终250专项/840完整回归/打包及隔离MySQL Spring10项验证、归档/UID清理/746绿色源码推进；默认关闭、未部署。独立两原批次先冻结选择，不混入验证前沿；全根费用当前读核对原账户，UNKNOWN预留保留，关闭阻止新派发，不自动采用。新执行/结算时间显式CURRENT_TIMESTAMP(6)，原历史行不回填；旧微秒列/手工时间夹具不足以证明MySQL原写入精度，本轮新增真实原始时间检查。真实Actor/模型端到端及稳定收益仍未验证，具体任务/新独立预算/共享容量待明确，旧额度不扩。本文历史阶段限制及原证据保持，以新合同为当前进展。
