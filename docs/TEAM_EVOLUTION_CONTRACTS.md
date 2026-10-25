# 持久团队候选与原评价槽位合同

日期：2026-10-10。T3-G源码、远程隔离回归、归档接收、资源清理和源码推进已验收闭环；未部署。继承[T3-F离线候选](AGENT_EVOLUTION_CANDIDATES.md)，本阶段把原提交、候选谱系、拒绝和待执行评价保存到平台数据库。

续接T3-H1：[独立预算与只读准备检查](TEAM_EVOLUTION_ADMISSION.md)源码和远程隔离验证已闭环，未部署。下述“不分配预算/不读实时profile”描述T3-G元数据操作；新的显式预算请求单独受默认关闭的开关控制。预算接口仍不能执行团队槽位，原合同协议和旧记录不回填。

## 范围与可信边界

V71新增准入锁和五个业务表，不回填旧实验、工作流、预算或谱系。新合同冻结两个不同Agent/实例的有序角色映射、已登记的平台UID/模板、端点、固定镜像/发布版本、部署配置摘要及模型；基线提示词来自平台RUNNER已批准配置。必须是可见且ACTIVE的Agent、平台专属身份、可执行的接入登记和未撤销的原创建记录，配置与原发布吻合。

这些是数据库中原平台登记的冻结，**没有执行Actor实时检查或读取运行中profile**，协议明确`liveIdentityState=NOT_CHECKED`。不唤醒Actor、不签发或换发凭证。后续派发必须重新核对原UID/模板/运行profile及当前批准；冻结记录不延长批准，不授权消费，也不证明运行程序支持团队评测。

`eap.team-evolution.contracts-enabled`默认false，控制元数据合同写入。无论此配置为何值，`executionAvailable=false`、`receiptIngestionAvailable=false`、`budgetAllocated=false`。当前没有dispatcher、模型/工具调用、工作流绑定、可信结果接收、评分或前沿发布接口。新槽位始终`NOT_DISPATCHED`，不能称团队端到端已执行或指标齐全。

## 原提交、谱系与额度

`TeamEvolutionService`在数据库事务中登记完整认证主体摘要和原请求键，全局锁保证同键并发只有一个合同。相同原键/参数返回原记录，即使目标之后撤销也不会重建；改参数冲突，另一原键按当前批准重新验证。使用`PlatformPermission.RUN_AGENT_EXPERIMENTS`，只允许平台管理员写入；只有原认证主体可继续该合同的候选或评价登记。

样本仅接受明确确认公开的TRAIN/VALIDATION各1–3条、HOLDOUT 1–10条。ID、输入规范化后及跨切分任务组查重；正文/答案各2000 UTF-8字节。协议冻结EXACT_TEXT_V1、两角色串行原输出交接、空上下文和禁工具约束，以及每角色最大输出1–256；这些只是未来执行合同，不证明已在Runner执行。

ASCII请求键和角色名统一小写；样本ID和组ID按小写查重，样本正文保持原文。仅大小写不同的重复角色/样本、跨切分任务组拒绝。原请求指纹仍包含原参数，原键只改大小写会冲突，不创造第二条记录；候选摘要引用和评价UUID按小写读写。实例键冻结平台登记原值。该规则使H2与当前生产MySQL的`utf8mb4_0900_ai_ci`排序规则具有一致的准入语义。

基线与候选保存配置摘要、父代、代数和变化角色。每次只接受完整`rolePrompts`映射，相对原父代恰好变化一个角色；每角色8000 UTF-8字节，不能修改模型、权限、身份、预算、拓扑、角色集合或留出。相同原尝试键读回原结果，改参冲突。候选数1–32、尝试1–128、代数0–8，各自设限；基线计入候选数。结构有效而候选不合规的尝试持久标记REJECTED并消耗次数，只保存摘要/固定理由，不保存被拒绝的额外字段或正文。参数不合法及上限耗尽不会创造额外尝试行。

提示词按原UTF-8字节限长；控制字符经过JSON转义后会扩张，两个合法角色的编码配置可能超过MySQL TEXT的65535字节。V71的候选配置和样本使用MEDIUMTEXT，保留原字节限制；隔离回归覆盖超过该阈值的配置保存和正文读取。该回归使用H2，不代表已在生产MySQL迁移V71。

原合同按行锁串行登记候选与评价，唯一键/FK约束作为数据库屏障。候选不能用另一根的父代行，不能用同配置造第二份谱系或指回基线；已有候选配置没有修改接口。进程重建后从同一数据库读取原事实，不依赖进程内缓存。这里的次数是元数据尝试限制，**不是新模型预算**，不创建budget账户、预占或消费。

## 原评价槽位

每个原候选最多一个当前VALIDATION评价意图，全部冻结验证样本逐一登记两个有序角色槽位；操作键和Trace由平台生成并持久保存。同键同参数返回同一评价及Trace，改参冲突；换键不能替换原评价，也不能替换原UNKNOWN。并发登记由原合同行锁及唯一约束保持一次。

请求只接受`requestKey/candidateId/split`，拒绝调用者的旧workflowRunKey、Trace、score、usageSource、Token和额外字段。TRAIN/HOLDOUT当前不开放评价登记，留出答案不会进入槽位或元数据响应。没有把旧工作流补挂成新候选执行证据，没有接收优化器自报分数、调用数或费用。后续实际执行、费用与平台评分需要新增有界适配合同；不能仅将槽位状态改为完成。

## API与权限

| 接口 | 用途 | 中央能力 |
|---|---|---|
| GET `/api/team-evolutions/status` | 合同与执行可用性 | VIEW_CONSOLE |
| POST `/api/team-evolutions` | 新原合同 | RUN_AGENT_EXPERIMENTS |
| GET `/api/team-evolutions/{id}` | 摘要、谱系、评价槽位 | VIEW_CONSOLE |
| GET `/api/team-evolutions/{id}/content` | 冻结部署协议、公开样本/留出和候选提示词 | VIEW_CONTENT |
| POST `/api/team-evolutions/{id}/candidates` | 原主体登记候选尝试 | RUN_AGENT_EXPERIMENTS |
| POST `/api/team-evolutions/{id}/evaluations` | 原主体登记全验证集待执行槽位 | RUN_AGENT_EXPERIMENTS |
| GET `/api/team-evolutions/{id}/evaluations/{evaluation}` | 按原根读取原槽位 | VIEW_CONSOLE |

权限匹配和`/me`继续读取`PlatformPermission`，没有角色名散落到业务代码。四控制台角色可读元数据，viewer/operator不可读正文；governance/admin可读正文，只有admin可写。匿名和非控制台角色拒绝。GET不登记、执行或续授权，当前没有前端页面。

## 验证与剩余工作

远程验证覆盖持久同键读取、重建服务实例、拒绝记录及尝试上限、并发候选/评价、跨根/原主体隔离、UNKNOWN禁止换键、角色/字节/代数限制、超TEXT阈值配置往返、四角色HTTP权限、管理员提交→候选→原评价→同键读取及旧UNKNOWN迁移不回填。UNKNOWN恢复用隔离测试数据库故障夹具，不是实际执行器崩溃；重建服务实例也不等于真实Pod重启。当前没有执行器。编译、测试与打包仅原n1.sh远程执行；原平台业务/费用/认证/UID/应用镜像完整保持，三轮临时测试资源均按UID清理，剩余0。

最终v3专项97项/4类、全量697项/100类及打包通过，失败/错误/跳过0，其中新增团队合同21项；[测试汇总](evidence/eap-team-contracts-20261010/test-summary.json)。首轮93/693和第二轮95/695均通过，因静态存储/排序规则补强被替代，原源码/报告嵌套保留。生产只读确认Flyway70、团队表0、utf8mb4_0900_ai_ci，V71实际MySQL迁移、部署与真实团队执行仍未验证；没有构建应用镜像、浏览器或模型/工具调用。

[证据清单](evidence/eap-team-contracts-20261010/manifest.json)、[最终回执](evidence/eap-team-contracts-20261010-final.json)：归档2233792bytes/SHA256 f320a26b465fb3fc7f67ec03d87a0fa6fd2dc61dfc6974d19b94e145241242c2，703文件摘要、6最终源副本和732完整源码本地核对。原33表2784行及6诊断/观测表和身份/费用/镜像保护通过，实际Secret原值/编码仅节点RAM扫描；3Job/3Pod按冻结UID清理剩余0。接收后精确6路径推进/root/eap-src，completed/closed/sourcePromoted=true、deployed=false。原归档不回写，推进回执单列；旧证据及AGENTS保持，未commit/push。

下一阶段先定义独立预算准入、原工作流绑定和两角色stateless执行合同，冻结原责任/期限/输入交接，可信接收原回执及GATEWAY usage，再由平台评分和整任务时延生成T3-F前沿输入。必须继续独立HOLDOUT及完整搜索/提案成本，不允许旧UNKNOWN重跑或自动采用。

真实业务任务与新预算仍需明确。原总计划127模型调用/11371输出，128/32768上限不扩大；差额1次不是新授权，不能拆根绕过。当前没有生产优化收益或主线全部完成的证据。


## 2026-10-10 T3-H2A后续

[可信原槽位执行适配](TEAM_EVOLUTION_EXECUTION.md)已完成最终180项专项、770项完整回归及独立MySQL SQL验证，默认关闭、未部署。独立计划新增原机器identityGeneration冻结，旧计划缺字段失败关闭；完整交接及Gateway返回正文摘要/SETTLED费用共同核对，不采信Runner自报usage/latency。本文早期“执行器尚无”等为当时阶段边界，以新合同为准。T3-F纯模块仍未接平台可信评分/前沿，独立HOLDOUT和真实团队端到端仍待，不自动采用或报告收益，旧额度不扩。


## 2026-10-10 T3-H2B后续

[平台评分与可信前沿](TEAM_EVOLUTION_SCORING.md)已完成当前读补强后220专项/打包，补强前第四版809全量/打包及独立MySQL SQL验证；最终版未重跑完整回归、归档/清理/742绿色源码推进。平台只取完整原两角色/交接/SETTLED费用和新样本责任时延，旧回执缺责任起点不能回填；默认关闭、未部署。原键前沿封存后关闭新验证搜索，后端复现T3-F规则，未直接调用Python模块或接生产GEPA。独立HOLDOUT/全消费及真实任务/新预算仍待，不自动采用或报告收益。本文早期阶段限制与原证据保持，以新合同为当前进展。


### 2026-10-10 T3-H2C续接

[独立HOLDOUT与全根消费](TEAM_EVOLUTION_HOLDOUT.md)已完成最终250专项/840完整回归/打包及隔离MySQL Spring10项验证、归档/UID清理/746绿色源码推进；默认关闭、未部署。独立两原批次先冻结选择，不混入验证前沿；全根费用当前读核对原账户，UNKNOWN预留保留，关闭阻止新派发，不自动采用。新执行/结算时间显式CURRENT_TIMESTAMP(6)，原历史行不回填；旧微秒列/手工时间夹具不足以证明MySQL原写入精度，本轮新增真实原始时间检查。真实Actor/模型端到端及稳定收益仍未验证，具体任务/新独立预算/共享容量待明确，旧额度不扩。本文历史阶段限制及原证据保持，以新合同为当前进展。
