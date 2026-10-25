# 团队独立预算与只读运行准备

日期：2026-10-10。T3-H1，继承[T3-G持久团队合同](TEAM_EVOLUTION_CONTRACTS.md)。源码、远程隔离验证、归档接收、临时资源清理和源码推进已闭环；未部署，可信团队执行尚未接通。

## 独立计划与预算

`eap.team-evolution.budget-enabled`默认false，同时要求合同写入开关开启。平台不会因登记合同、候选或评价自动分配预算。新预算须由原主体显式提交独立计划，严格接受以下字段：

| 字段 | 含义 |
|---|---|
| `requestKey` | 本次原请求键，ASCII规范化小写 |
| `planKey` | 独立计划键，全局规范化摘要，一个计划只绑定一个合同 |
| `businessTask` | 明确业务任务，最多2000 UTF-8字节；响应只返回摘要 |
| `independentBudgetConfirmed` | 必须明确为true，不继承旧计划的剩余额度 |
| `maxCalls` | 显式模型调用上限，1–128 |
| `maxOutputTokens` | 显式输出Token上限，1–32768 |
| `approvalExpiresAt` | 显式UTC期限，新计划要求距服务器当前时间30–900秒，精度不超过微秒 |
| `proposalMode` | 当前只支持`OFFLINE_ONLY`，不包含模型生成提案或模型反思 |

完整计划最低调用预算为 `2 × (验证样本数 × 最大候选数 + 2 × 留出样本数)`。前面的2对应有序两角色，后面的2覆盖基线与所选候选的独立HOLDOUT；基线计入最大候选数。输出预算至少覆盖`maxCalls × 每角色maxTokens`。这只是额度覆盖规则，HOLDOUT执行/封存适配尚未实现；TRAIN不执行，离线提案不调用模型。后续模型提案若要开放，必须设计费用归属并进入同一总预算，不能视为免费。

合法元数据合同也可能超过当前128调用/32768输出的完整计划上界，此时拒绝预算准入，不截短验证/留出集、不临时增额或拆根。当前预算没有输入Token或金额硬上限。

通过当前检查后，V72计划记录与三项TASK预算账户在同一事务内保存：`team-evolution:{contractId}`的MODEL_CALLS、OUTPUT_TOKENS和固定为0的TOOL_CLAIMS。三账户revision=1、margin=0、used/reserved=0，没有消费或预占。计划、根额度和期限没有修改/清零/续期接口；普通预算修改API拒绝该前缀。一个合同只能有一个原计划，换请求键、换参数或试图扩大额度均冲突；同一计划键不能绑定第二合同，包括大小写别名。

同一原主体、原键及原参数读回已保存计划，即使实例后来撤销或期限已过，也不会再次检查、分配或续期。该响应表示历史额度曾冻结，不表示当前可执行；实时准备必须走独立check。原合同及协议摘要保持不变，T3-G的`NOT_CHECKED/DISABLED_NO_EXECUTOR`不能被预算冻结倒填成执行证明。

原主体按完整值计算摘要，不截断为账本字段长度；预算审计的`updated_by/changed_by`保存`team-principal:{完整主体摘要}`。响应不回传原主体或业务任务正文。

## 共享预算与准备检查

冻结前先只读检查部署和两名Agent现有共享账户，计入`used + reserved + margin + 完整计划上界`和违例标记，提交时在预算准入锁下再复核。两Agent各按整个计划上界保守检查，而非将根额度复制为两份实际消费；平台实际总上限仍为一份。沿用现有账本的未设置上限语义，不隐式创建或扩大平台/Agent额度。

共享预检**不预留共享容量**，其他任务仍可能消耗额度。每次check重新检查；未来每次实际模型调用还必须接入可信原槽位关联及实时共享准入。当前网关拒绝调用者用`X-EAP-Task-Key: team-evolution:...`自行挂根，尚无团队网关归属或执行入口，预算冻结不能授权调用。

两角色逐一核对原平台数据库登记、ACTIVE状态、接入批准、实例UID/模板、未撤销创建记录、原发布版本、固定镜像和配置摘要。实时profile通过专属已安装凭证读取，校验Substrate当前UID/模板和Runner的agent/model/基线提示词、空上下文、禁工具、temperature=0及可覆盖合同的输出上限。首次观察仅保存角色/UID/模板、profile摘要和白名单内容摘要；未知响应字段及异常正文不入库。后续check须同时匹配首次观察的两个摘要，运行配置漂移阻断。

此路径使用`inspectProfile/inspectionToken`，不主动调用Actor恢复接口、不配送或续发凭证、不派发实验或模型请求，不回退共享令牌。检查时观察到休眠的实例直接拒绝；凭证过期、未验证、配送未完成或UID变化均阻断。每角色检查前后及最终提交前复核登记与期限。外部状态检查不是分布式锁或执行租约，未来执行器必须在每个原槽位派发前复核，不能使用旧check结果跳过准入。Substrate库存接口当前只证明UID/模板，固定镜像来自平台原发布登记，不是本轮运行容器imageID实测。

“只读”指平台不发送恢复/配送/实验写请求。Substrate路由器具备请求触发唤醒能力，不能据此保证检查与HTTP发送之间发生并发休眠时仍绝不唤醒；该竞争边界未做真实路由实测，也未确认可禁止自动唤醒的路由协议。后续若要求整个检查链路无运行状态副作用，须先验证并明确支持方式。

## API、权限与执行边界

| 接口 | 用途 | 中央能力 |
|---|---|---|
| GET `/api/team-evolutions/{id}/budget` | 已保存计划摘要，不读实时profile或续授权 | VIEW_CONSOLE |
| POST `/api/team-evolutions/{id}/budget` | 原主体显式冻结独立计划及额度 | RUN_AGENT_EXPERIMENTS |
| POST `/api/team-evolutions/{id}/budget/check` | 原主体只读准备检查，JSON正文须为空对象 | RUN_AGENT_EXPERIMENTS |

权限规则继续由`PlatformPermission`提供，只有平台管理员可写入或请求准备检查；四控制台角色可读取可见Agent的预算元数据。check不写业务记录，不改变额度/revision或槽位。没有前端页面。

全局status的`budgetAdmissionAvailable`表示配置是否允许此类请求，不表示当前共享额度足够；其`budgetAllocated=false`表示没有自动分配预算。具体成功冻结计划的`budgetAllocated=true`表示三账户已建，但`sharedCapacityReserved=false`、`executionAvailable=false`、状态`BUDGET_FROZEN_NO_EXECUTOR`。所有团队槽位保持NOT_DISPATCHED；执行器、责任/租约、原回执、可信GATEWAY usage、平台评分、整任务时延与T3-F前沿适配均待完成，不能将profile响应摘要当作模型调用/费用证据。

check成功的`readOnlyChecksPassed=true`只表示本次检查通过，不表示未来执行已获准或仍满足条件；没有保存可复用的执行令牌。

## 验证与后续

远程最终专项135项/7类、完整后端734项/101类及打包通过，失败/错误/跳过0，其中新准入30项；[测试汇总](evidence/eap-team-admission-20261010/test-summary.json)。首轮132项的17失败/7错误来自Spring代理字段读取，完整回归/打包未执行，修复后重跑通过；原报告/源码保留。只读客户端与凭证路径用远程回环HTTP/Mock及Spring H2夹具验证，不等于真实Actor或真实Pod重启。

独立MySQL 8.4.11、utf8mb4_0900_ai_ci的建表/零隐式预算、大字段JSON、大小写唯一/单根唯一/FK、微秒期限、回滚通过；[MySQL结果](evidence/eap-team-admission-20261010/mysql-isolated-v2/result.json)。首轮夹具MEDIUMTEXT断言失败，显式UTF-8后JSON长度96018字节（默认字符值为21942），迁移未修改，原失败记录保留。此处是隔离SQL验证，尚未验证Spring完整业务在MySQL上的行为。

原33表2784行及6诊断/观测表、费用/身份/旧UID/模板/认证配送/镜像与环境保持，实际Secret仅节点RAM扫描；4Job/4Pod按冻结UID清理剩余0。归档1032889bytes/SHA256 9c8957ad677d838f9944b7799cafe4a1fbe178e81f7db820289d31824664495c，320摘要/12最终源副本/735完整源本地核对，之后精确12路径推进远程源码；[证据清单](evidence/eap-team-admission-20261010/manifest.json)、[最终回执](evidence/eap-team-admission-20261010-final.json)。生产只读仍Flyway70/团队表0，V71/V72未生产迁移；未构建应用镜像、部署或浏览器验证，真实模型/工具0。新增独立计划只在隔离测试数据库验证，未为生产业务分配新预算，未commit/push。

下一项T3-H2：原工作流/责任/期限及stateless两角色输入交接、有界派发与UNKNOWN防重，可信原回执/GATEWAY账本和平台评分/整任务时延，再接纯算法前沿及独立HOLDOUT。真实任务和新预算必须明确；旧总计划127次/11371输出及128/32768上限保持，差额1次不是新授权，不重放旧UNKNOWN/FAILED/KEEP_BASELINE，不拆根或自动采用。

接续时特别核对Runner现有实验协议的输入上限为2000字节；第一角色完整输出不能保证始终在此范围内。必须定义有界交接失败或显式新协议，不能偷偷截断后声称使用原完整输出。团队Trace还须按原实例经过`RuntimeScopedTelemetry`转换后再关联网关意图，不能只凭调用方Task头或猜测Trace归属。既有实验入口无法直接借用其单Agent意图代替团队槽位责任。

现有Runner实验HTTP请求体上限为16384字节。合法8000原字节的提示词经JSON转义可能超过该上限，未来执行适配须在任何派发前预检实际编码大小或定义新协议；元数据/预算准入通过不能保证每个候选可直接发给旧端点。


## T3-H1B：共享预算当前读补强（2026-10-10）

提交事务可能已有MySQL REPEATABLE READ快照，因此只取得全局预算锁不足以刷新后续普通查询。共享预算查询增加FOR UPDATE，确保读取当前已提交的used/reserved/限额/违例字段。独立MySQL双连接实际证明旧额度12、并发缩减1后普通读仍12、当前读1；[实测结果](evidence/eap-team-admission-current-read-20261010/mysql-current-read/result.json)。不修改历史额度/revision，也不预留共享容量。

这一处源码变化后远程预算/团队准入58项/2类及打包通过，0失败/错误/跳过。上述734项全量是补强之前的H1版本，之后未重跑全量；其他734份源码未变。[补充清单](evidence/eap-team-admission-current-read-20261010/manifest.json)、[最终回执](evidence/eap-team-admission-current-read-20261010-final.json)：归档131629bytes/SHA256 ddce7328a7aaa465adbef7d85c8b826b090d6ed227d85e707094ee3bc19aaf23、45摘要/1源副本/735当前源本地核对后单路径推进。补充2Job/2Pod清理，含H1本轮6Job/6Pod剩余0；生产70/团队表0及原状态保护，真实模型/工具0，仍未部署或commit/push。


## 2026-10-10 T3-H2A后续

[可信原槽位执行适配](TEAM_EVOLUTION_EXECUTION.md)已完成最终180项专项、770项完整回归及独立MySQL SQL验证，默认关闭、未部署。独立计划新增原机器identityGeneration冻结，旧计划缺字段失败关闭；完整交接及Gateway返回正文摘要/SETTLED费用共同核对，不采信Runner自报usage/latency。本文早期“执行器尚无”等为当时阶段边界，以新合同为准。T3-F纯模块仍未接平台可信评分/前沿，独立HOLDOUT和真实团队端到端仍待，不自动采用或报告收益，旧额度不扩。


## 2026-10-10 T3-H2B后续

[平台评分与可信前沿](TEAM_EVOLUTION_SCORING.md)已完成当前读补强后220专项/打包，补强前第四版809全量/打包及独立MySQL SQL验证；最终版未重跑完整回归、归档/清理/742绿色源码推进。平台只取完整原两角色/交接/SETTLED费用和新样本责任时延，旧回执缺责任起点不能回填；默认关闭、未部署。原键前沿封存后关闭新验证搜索，后端复现T3-F规则，未直接调用Python模块或接生产GEPA。独立HOLDOUT/全消费及真实任务/新预算仍待，不自动采用或报告收益。本文早期阶段限制与原证据保持，以新合同为当前进展。


### 2026-10-10 T3-H2C续接

[独立HOLDOUT与全根消费](TEAM_EVOLUTION_HOLDOUT.md)已完成最终250专项/840完整回归/打包及隔离MySQL Spring10项验证、归档/UID清理/746绿色源码推进；默认关闭、未部署。独立两原批次先冻结选择，不混入验证前沿；全根费用当前读核对原账户，UNKNOWN预留保留，关闭阻止新派发，不自动采用。新执行/结算时间显式CURRENT_TIMESTAMP(6)，原历史行不回填；旧微秒列/手工时间夹具不足以证明MySQL原写入精度，本轮新增真实原始时间检查。真实Actor/模型端到端及稳定收益仍未验证，具体任务/新独立预算/共享容量待明确，旧额度不扩。本文历史阶段限制及原证据保持，以新合同为当前进展。


## 2026-10-11 真实原身份时区修复

JPA `RuntimeConnection` 的expires_at/verified_at/identity_expires_at按UTC Instant保存，生产JDBC为Asia/Shanghai；Gateway原槽位核验对这三个字段使用UTC Calendar读取，与JPA一致。原计划/派发/预算时钟、数据库会话、已保存身份期限、epoch、旧额度和revision均不调整。真实root1在首次Gateway预占前被拒绝，UNKNOWN保持；隔离MySQL先复现再通过，原根用首次关闭键不完整关闭，新独立验收仍待新支出明确。详见[实测](TEAM_EVOLUTION_LIVE_ACCEPTANCE.md)及[证据](evidence/eap-team-live-20261011-index.html)。
