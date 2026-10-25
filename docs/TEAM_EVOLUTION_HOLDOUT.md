# 独立团队HOLDOUT与全根消费（T3-H2C，2026-10-10）

本阶段接续[验证评分与前沿](TEAM_EVOLUTION_SCORING.md)，增加独立基线/所选候选HOLDOUT批次及一次性全根消费关闭。最终远程验证、UID清理、归档接收和绿色源码推进已闭环；未部署，不代表主线全部完成或真实业务收益。

## 冻结选择与独立批次

新增`eap.team-evolution.holdout-enabled`，默认false。结合原contracts/budget/slot-execution/scoring开关才显示完整留出能力。完整搜索、生产GEPA循环及自动采用仍关闭。

`POST /api/team-evolutions/{root}/holdout`仅接受`{"requestKey":"..."}`，中央`PlatformPermission.RUN_AGENT_EXPERIMENTS`及根原主体共同授权。只读取已封存前沿的baselineId及selectedCandidateId，不接收候选、模型、身份、Trace、预算或客户端评分。选择为空时拒绝；原期限已过时不新增批次。相同原键/指纹/主体返回原记录，换键冲突，不刷新期限、账户、身份epoch或共享额度。

BASELINE与SELECTED分别生成独立评价、原操作键和Trace，包含冻结HOLDOUT的全部样本×原两角色。即使所选候选就是基线，仍生成两套独立批次。样本顺序按Java字符顺序及角色序号统一，与MySQL排序规则无关。TRAIN不执行；HOLDOUT不回流VALIDATION前沿，不据留出重选、变异候选或修改提示词。

公开合同内的HOLDOUT样本/标签从提交起即可由VIEW_CONTENT读取；这里只保证批次独立和先冻结选择，不能宣称标签未公开、未被人读取或获得盲测。

## 迁移与原执行责任

V75保留旧评价/槽位/状态，将旧评价明确分类为VALIDATION/CANDIDATE，替换原候选唯一约束为根/候选/split/arm唯一约束；原操作键唯一及候选FK保留。按列精确发现原未命名约束，MySQL用反引号，H2用双引号引用原名。H2外键会保留旧两列唯一索引，故其分支先暂移候选FK、替换唯一约束后恢复同一FK；MySQL始终保留FK及覆盖索引。V76新增根唯一holdouts和closures表，不生成历史派发、预算、评分、留出或关闭记录，不修复/重放旧UNKNOWN。

沿用`/{root}/evaluations/{evaluation}/execution`和`/score`。独立批次必须属于原holdouts记录，并匹配原封存前沿摘要。新派发仍用原holder/CAS、原绝对批准期限、原UID/模板/实例/模型/运行版本/机器身份epoch/实时profile和固定预算；不接管、续租、换键重试或重新批准身份。Gateway仍原Trace识别、原实例认证、固定模型正文摘要、实际SETTLED账本结算，任何不确定发送停止后续槽位。预期答案不发给Runner，第二角色接收第一角色完整正文。

新留出Trace复用现有原团队槽位识别路径，停止/关闭后仍识别为原团队Trace，不退回普通聊天归属。Gateway在实际花费前再次检查HOLDOUT开关和根关闭记录。

## 留出评分与保守结论

评分复用原完整两角色/完整交接/正文摘要/唯一SETTLED原预留及settlement/六预算归属项的核对。EXACT_TEXT_V1只精确比较最终完整正文与冻结expected，不trim、归一化或用客户端usage/latency。固定分母为全部HOLDOUT样本，1–10；缺失/UNKNOWN/FAILED/VIOLATION不能补成0分、0费用或快完成。

执行/责任/派发/回执及账本reservation/settlement的新时间写入显式使用CURRENT_TIMESTAMP(6)，修正MySQL默认函数精度为整秒的问题；不回填历史行。每样本责任在第一次准备检查HTTP前开始，至第二角色可信回执确认，微秒计时；包括两个角色、准备检查及完整交接。评分增加split/arm/batchDigest，validationDigest仍表示原根验证批次，HOLDOUT有独立batchDigest。旧评分JSON不重写。

验证前沿只读取VALIDATION评分，封存后的快照及选择保持。最终比较策略`HOLDOUT_QUALITY_THEN_PARETO_V1`要求所选质量精确比例不低于配置门槛及独立基线，token和时延均不高于基线且至少一项质量/token/时延严格改善。只在所选与基线不同且完整证据成立时报告`BOUNDED_HOLDOUT_SUPPORT_NO_ADOPTION`；其余完整比较为`KEEP_BASELINE`，缺证据为`INCOMPLETE_NO_ADOPTION`。同一候选的两独立批次即使有测量差异也不报告候选改善。始终adoptionAvailable=false、stableBenefitVerified=false。

## 全根消费与一次关闭

`POST /api/team-evolutions/{root}/closure`只接受原requestKey，原主体和集中权限授权。可将未登记/未执行/失败/未知留出显式关闭为不完整，不以此退款或重新开放。允许首批UNKNOWN或从未执行的原根在没有封存前沿时关闭为INCOMPLETE_NO_ADOPTION；前沿摘要及所选候选均为null，不编造评分/选择。拒绝任何RUNNING责任、RECEIPTS_COMPLETE未评分批次和DISPATCHED在途费用。此项为2026-10-11真实验收暴露后修复；HOLDOUT登记仍严格要求原封存前沿。

根锁串行化登记/评分/关闭；封存关闭的费用读取按根锁→共享预算锁→原执行锁顺序，匹配Gateway共享预算→执行的顺序。执行状态、全部根预留及TASK账户使用FOR UPDATE当前读；拿到锁不等于MySQL RR快照已刷新。根锁保护的不可变前沿/原评分保持原记录。实际业务服务的并发夹具检查关闭等待预算写入者并包含其新费用与账户。

消费包含所有已发生的候选验证、被支配/低质量候选及两套留出，不仅所选候选。分别报告SETTLED/UNKNOWN/在途/VIOLATION、已知输入/输出token、unknownUsage和保留的输出预留；不是货币价格。UNKNOWN预留必须等于原未知预留output_bound之和，调用/已知输出实际使用量与原三TASK账户核对，caps/revision=1/margin=0及tools=0不改变。OFFLINE_ONLY提案模型调用为0，同时报告提案尝试/登记候选及各批次状态。未完整验证的登记候选会使完整证据不成立。

原计划已覆盖`2 × (验证样本数 × 最大候选数 + 2 × 留出样本数)`，新阶段不另开额度或扩展原计划。关闭后新HOLDOUT登记、原评价的新执行/新评分均拒绝；原键的已存在执行、评分、holdout、closure仍只返回原元数据，不产生调用或重新批准。

`GET /{root}/holdout`与`/closure`仅VIEW_CONSOLE元数据/分数/摘要/样本ID/费用，不返回输入、expected、正文、提示词、业务任务或身份epoch。内容仍走原VIEW_CONTENT接口。

## 验证与边界

最终第五版远程专项250项/10类及隔离MySQL Spring十项均已通过，失败/错误/跳过0；完整840项/102类及打包亦通过；全部0失败/错误/跳过。前两轮H2迁移初始化失败报告保留：241项/215错误及242项/216错误，0失败，全量/打包均未执行。最小H2首轮因依赖路径遗漏repository未执行SQL，第二轮确认大写原名在反引号下转小写失败、双引号成功；原“新增列改名”判断已更正。第三版249项/25错误来自H2外键保留旧唯一索引，全量/打包未运行；第四版249专项通过，全量被显式CURRENT_TIMESTAMP(6)修正版接替而按UID停止，full=INTERRUPTED_NOT_VERIFIED、package=NOT_RUN。第二版MySQL8项、第三/第四版各9项及最终第五版10项通过；第五版包含原始责任/派发/账本小数秒与正时延检查，不仅是手工时间夹具。

所有业务执行、测试、编译与打包只在远程隔离集群进行。MySQL仅Pod回环绑定、emptyDir、无生产Secret或ServiceAccount；机器认证、Runner、模型上游为Mock。Spring真实MySQL业务夹具比SQL迁移检查范围更广，仍不代表真实Actor、Go Runner、模型调用、Pod崩溃、浏览器、应用镜像或生产部署验收。

下一步需准备有界真实团队验收：具体业务任务、新独立预算和共享容量须明确后再执行。旧127次/11371输出及128/32768上限不变，剩余1次不是授权；不重放旧UNKNOWN/FAILED/KEEP_BASELINE，不拆根、不改旧/共享caps/revision、不自动采用。通用任务/对话可信分母及T2/T4–T8剩余项保持，主线未全部完成。


## 最终证据

[证据清单](evidence/eap-team-holdout-20261010/manifest.json)、[最终回执](evidence/eap-team-holdout-20261010-final.json)：归档3033508bytes/SHA256 29bbc2cd53017ac75f4662bde4eea5fc3fe40a92673a843a04d3025297b6f716，560摘要、14最终源副本/四历史版本源及746完整源本地核对；源manifest SHA256 2e79fc1f1c5094160c1a42ed9612ab696bb61a10dc7aee2ffeac581cf107efbc。12Job/12Pod按原UID清理剩余0；接收核对后才精确14路径推进/root/eap-src。节点/root/eap-t3h2c-20261010-v5/final-completion.json completed/closed/sourcePromoted=true、deployed=false。原33表2784行及6表/费用/认证/UID/模板/全部镜像/环境保持，实际Secret仅节点RAM扫描。生产仍Flyway70/团队表0，新生产预算/真实模型/工具0；未构建应用镜像/部署/浏览器，未commit/push。既有500/661/45/320/703/22/439证据、AGENTS/BP/其他线程保持。


## 2026-10-11 实测续接

最新T3-H2D（2026-10-11）：[真实团队验收](TEAM_EVOLUTION_LIVE_ACCEPTANCE.md)已完成本次失败诊断、修复部署和原根关闭；**真实模型端到端验收未完成，主线未全部完成。** 用户“继续”已确认公开工单分类、独立24调用/3072输出/工具0/最多900秒及仓库工具/旧发布迁移范围。生产root1首次原槽位UNKNOWN即停止，后5槽位未派发，候选验证/HOLDOUT未执行。原Gateway预占/上游消费0，账本与PLATFORM增量0；原UNKNOWN保留，原关闭键返回INCOMPLETE_NO_ADOPTION、无前沿/选择，不续期、不替换根或补分。

实测定位JPA以UTC保存的身份Instant被上海时区普通JDBC读早8小时，Gateway在预占前拒绝；另修复未封存前沿导致首次失败无法关闭。原MySQL红测4项（2失败/2错误）保留；修复远程209专项/7类、853全量/103类及打包通过，MySQL8.4.11/0900ai_ci/RR的UTC23+上海4项通过，全部0失败/错误/跳过。后端固定为`10.186.133.66:5001/eap-backend@sha256:3b3b1cebe23fd98e3d32c8d3f421721f5228be39a3409aa0734104057a731db8`，实际Pod imageID/健康/Flyway77通过；五个团队开关启用，T7-E runtime-denominators仍false，前端/Runner/AX/optimizer保持。

[验收索引](evidence/eap-team-live-20261011-index.html)、[最终回执](evidence/eap-team-live-20261011-final.json)：归档1094029bytes/SHA256 6af35d677757407ec415e96b45987c8cd2bf61c271c144715674ff899c42c9b4，322摘要/4源副本/750源码与4009旧证据核对。实际Secret仅节点RAM扫描；39表2831原行/旧UID/模板/身份/旧caps/revisions保护通过。新Agent64/65原UID休眠、身份撤销，5Job/5Pod按UID清理剩余0；接收核对后才精确4路径同步/root/eap-src，节点completed/closed/sourcePromoted=true、liveAcceptanceComplete=false。原127/11371实验计划及128/32768边界保持，PLATFORM160调用/13275输出/10工具、预留0未变。未commit/push。

下一步是修复后的新独立真实团队验收；须另行明确新的独立支出，不能沿用已到期root1或把其剩余额度转移。仓库现有工具/旧发布迁移范围已确认，尚未迁移；T2可信租户/资源配额与跨入口调度、T4/T5执行侧/旧消费者、T6恢复/取消/补偿、T7实际任务/对话/AX LLM与独立指标/日志/告警/规模、T8统一策略仍待，稳定收益未验证。以下旧阶段的未部署/未授权描述保留其当时含义，当前以本条及实际回执为准。
