# 团队评价原槽位执行适配（T3-H2A，2026-10-10）

本阶段实现默认关闭的两角色串行执行适配、原槽位责任及可信Gateway回执核对。结果最多为`RECEIPTS_COMPLETE`，表示完整原验证批次的回执已确认，尚无平台评分、Pareto前沿、独立HOLDOUT或自动采用。真实业务任务和新预算尚未明确，本轮只运行远程隔离夹具，生产仍为T7-D/Flyway70，无新生产预算或模型/工具消耗。

前置：[持久合同](TEAM_EVOLUTION_CONTRACTS.md)、[独立预算及只读准备](TEAM_EVOLUTION_ADMISSION.md)。此文描述新增行为，原阶段证据和注册时冻结的`NOT_CHECKED/DISABLED_NO_EXECUTOR`不回填。

## 责任与单次派发

V73仅新增`team_evolution_executions`、`team_evolution_dispatches`，不修改或自动登记历史工作流、优化根、评价或槽位。每个原评价只允许一个执行请求，冻结原主体、原请求键/参数摘要、随机holder、从原批准复制的绝对期限及`team-evaluation:{原评价UUID}`。这是团队评价自己的串行工作单标识，尚未接通通用`workflow_executions`调度/接管。

`POST /api/team-evolutions/{id}/evaluations/{evaluation}/execution`正文严格为`{"requestKey":"..."}`；写权限继续来自中央`PlatformPermission.RUN_AGENT_EXPERIMENTS`，且必须是根的原主体。`GET`同路径只返回工作单、状态、固定原因码与派发/确认数量，使用`VIEW_CONSOLE`，不返回输入、输出、提示词、业务任务或持有者。没有客户端回执/评分写入接口。

启用必须同时满足合同、预算和`eap.team-evolution.slot-execution-enabled`；均默认关闭。`status.slotExecutionAvailable`只是配置能力，并非当前身份、额度或执行授权证明。完整搜索的`executionAvailable/receiptIngestionAvailable`仍false；具体根与计划在开启单槽适配时分别显示`SLOT_ADAPTER_UNSCORED/BUDGET_FROZEN_SLOT_ADAPTER_UNSCORED`。

首次提交在持久事务中冻结责任，按`sample_id/role_ordinal`串行发送完整验证集，训练/留出样本不派发。独立计划同时冻结两角色已安装机器身份generation；发送前后采用独立新事务读取，避免继承合同事务的旧快照。撤销后重新批准的新generation不能沿用旧计划；同epoch内正常凭证租期轮换不改变原身份。旧计划若缺该字段，检查失败关闭，不补填或重新授权。每次发送前复核原UID/模板、专属凭证、平台创建/发布批准、冻结profile与期限；不会主动Resume、配送或续期。槽位`NOT_DISPATCHED → DISPATCHED`的条件更新与完整请求意图同事务提交，之后才进行一次HTTP发送，网络等待不持有该事务。网关仍独立执行实时身份/批准/期限和共享预算准入；之前的check不是租约。H1的`budget/check`保持首次零使用准备口径；执行中的复核走专用verifyExecution并允许已有可信使用/预留，不用旧check替代每次网关准入。

同原键/参数的并发或重复请求只返回保存状态，不重复派发；换键或换参数409。没有责任续期、自动恢复、接管、重新提交槽位或自动重试。发生进程中断时，已有责任保留；原请求在绝对期限后仅可关闭为UNKNOWN，不再次发送。单纯GET不会推进状态；没有后台过期回收器。数据库状态夹具不等于真实Pod崩溃验收。

## 有界完整交接

第一角色使用公开验证样本原输入；第二角色只接收第一角色已确认的完整正文。expected从不传给Runner。上下文EMPTY、工具DISABLED、temperature=0、固定模型和每次提示词覆盖沿用原stateless协议，不修改运行配置。

派发前检查Runner现有限制：输入非空且UTF-8不超过2000字节；完整JSON请求体不超过16384字节；候选提示词原上限8000字节和输出上限256继续生效。转义膨胀也计入HTTP体。前序正文不能完整容纳时，已确认的完整回执保留，整批FAILED且后续槽位仍NOT_DISPATCHED；不截断、摘要化、换键或重试。

## 可信网关与费用

原团队Trace只能由冻结实例/Agent/UID/模板使用。`RuntimeConnectionService.resolveTrace`识别所有原团队槽位，包括未派发、已结束与UNKNOWN，保留服务端原Trace，不把停止的原操作重新解释为普通对话；非原实例401，未发行Trace仍按实例命名空间转换。

`RuntimeAccessController`在机器认证后通过同步服务端上下文携带Access，调用结束必清除；客户端不能通过JSON或HTTP头设置它。Legacy网关、客户端任务根头或调用键不能借用团队Trace。预算准入在共享预算锁下核对持久DISPATCHED意图、RUNNING责任、绝对期限、原实例身份epoch与原计划相等、有效租期及配送状态、ACTIVE Agent、未撤销创建、原READY RUNNER发布Agent/模板/版本/镜像/配置摘要，以及冻结的完整模型请求摘要。可变授权读取采用FOR UPDATE当前读；原合同/计划不可变。

每个原Trace使用固定唯一预留键，只计入PLATFORM、实际执行Agent及唯一`TASK team-evolution:{root}`。根MODEL_CALLS/OUTPUT_TOKENS及TOOL_CLAIMS=0账户不能扩额、清零或更改revision/margin。实际每次准入仍计入共享`used+reserved+margin`；没有事先占满整个共享计划容量。

网关仅在实际provider usage已结算SETTLED、输出治理完成且返回单文本/无工具结果时，记录返回正文摘要、finish reason及原reservation ID。平台接收器必须同时匹配原holder/Trace、期限、正文摘要、finish reason及原Agent/根预算的SETTLED账本；Runner自报usage/latency不采信。正文与费用分别证明输出和消费，profile摘要不能代替它们。

发送后失联、异常/格式不符、正文被替换、未知usage、额度违例或责任超时均停止为UNKNOWN，后续不发送；账本UNKNOWN保留输出预留，不退款或以零补全。Gateway已经结算而平台回执未确认时，费用仍保留SETTLED，槽位可为UNKNOWN。这两种事实不能合并为业务成功。

当前保留每个原发送/完成及整批责任时间戳。平台评分、每样本整任务时延的完整口径、可信前沿和独立HOLDOUT仍需后续适配，未把Runner自报时延当作这些指标。

## 验证范围与下一步

最终第四版专项180项/10类、完整770项/102类及打包通过，0失败/错误/跳过，其中执行29项、独立预算32项。第三版176/766为epoch补强前绿版，保留完整源码/报告；[最终测试汇总](evidence/eap-team-dispatch-20261010/test-summary.json)。[证据清单](evidence/eap-team-dispatch-20261010/manifest.json)、[最终回执](evidence/eap-team-dispatch-20261010-final.json)：661摘要/740源/20最终源副本及三历史版本核对，归档2191826bytes/SHA256 9288bae84e5a1bc70abe6de8a5d219af03d10c939046b356d497e94c84dcab90。接收后精确20路径推进节点绿色源码，5Job/5Pod按UID清理剩余0，生产及33原表2784行+6表保护通过，实际Secret只节点RAM扫描。首轮171项/10类的1失败19错误保留：Mockito重新配置的空参数回调19错误，权限夹具缺ROLE_导致1失败；full/package未执行。第二版176项/10类28错误来自反射方法重载夹具，full/package未执行，失败原样保留。

回归使用Spring H2、真实服务链与Mock上游/Runner；客户端另有回环HTTP，身份Trace另有真实机器认证服务夹具。MySQL 8.4.11、utf8mb4_0900_ai_ci在独立空卷/禁TCP/无Secret的Job运行迁移、完整正文超过TEXT存储、原槽位FK/唯一、原子回滚及双连接竞争，只一个持有者更新成功；并重验共享预算快照读12/当前读1。SQL验证不代表Spring真实MySQL业务集成。

没有真实Actor准备、Router并发休眠竞争、真实Pod崩溃、更换镜像、生产迁移/部署/浏览器或真实模型端到端验证。Substrate GET可能因并发休眠经Router自动唤醒，这一既有限制保留。平台固定镜像证据仍来自原发布登记，不是实时容器imageID证明。

下一步T3-H2B：平台完整批次评分、按两个原角色及完整交接计算整任务时延、接入T3-F可信前沿；再实现独立HOLDOUT和全费用。生产试验需要具体业务任务、新独立预算及共享容量明确授权。旧127次/11371输出与128/32768上限不变，剩余1次不是授权，不改旧/共享caps/revision、不拆根绕过、不重放UNKNOWN/FAILED/KEEP_BASELINE或自动采用。主线尚未全部完成，不能报告稳定业务收益。


## 2026-10-10 T3-H2B后续

[平台评分与可信前沿](TEAM_EVOLUTION_SCORING.md)已完成当前读补强后220专项/打包，补强前第四版809全量/打包及独立MySQL SQL验证；最终版未重跑完整回归、归档/清理/742绿色源码推进。平台只取完整原两角色/交接/SETTLED费用和新样本责任时延，旧回执缺责任起点不能回填；默认关闭、未部署。原键前沿封存后关闭新验证搜索，后端复现T3-F规则，未直接调用Python模块或接生产GEPA。独立HOLDOUT/全消费及真实任务/新预算仍待，不自动采用或报告收益。本文早期阶段限制与原证据保持，以新合同为当前进展。


### 2026-10-10 T3-H2C续接

[独立HOLDOUT与全根消费](TEAM_EVOLUTION_HOLDOUT.md)已完成最终250专项/840完整回归/打包及隔离MySQL Spring10项验证、归档/UID清理/746绿色源码推进；默认关闭、未部署。独立两原批次先冻结选择，不混入验证前沿；全根费用当前读核对原账户，UNKNOWN预留保留，关闭阻止新派发，不自动采用。新执行/结算时间显式CURRENT_TIMESTAMP(6)，原历史行不回填；旧微秒列/手工时间夹具不足以证明MySQL原写入精度，本轮新增真实原始时间检查。真实Actor/模型端到端及稳定收益仍未验证，具体任务/新独立预算/共享容量待明确，旧额度不扩。本文历史阶段限制及原证据保持，以新合同为当前进展。
