# 团队真实验收实测与后续边界（2026-10-11）

最新 T3-H2E（2026-10-11）：用户继续主线后，已按新独立上界 24 调用 / 3072 输出 Token / 工具 0 / 最多 900 秒执行 root2。两套新实例 Agent66/67 实际身份和 profile 通过；原基线验证 6/6 槽位确认，候选验证 3 次确认后停止为 `FAILED / PRE_DISPATCH_REJECTED`。原回执证明一次提取返回 `finishReason=length` 且正文为空，下一角色在派发前拒绝空交接。HOLDOUT 未执行，root2 已关闭为 `INCOMPLETE_NO_ADOPTION`，不重试、不换根、不自动采纳。

实际 9 次调用、741 输入 / 737 输出 Token、UNKNOWN 0、预留 0、工具 0；9 个原始槽位分别与 Gateway 和 Agent Span 按 Trace / Agent / runtime 端点一一对应，费用与原账本和 PLATFORM 增量一致。这证明已发生调用的真实端到端关联；**完整团队验收、独立 HOLDOUT、稳定收益和整个主线仍未完成**。T7-E runtime-denominators 仍 false，不能据此宣称跨任务/对话分母已上线。

[本轮最终回执](evidence/eap-team-live-fixed-20261011-final.json)、[原始账本](evidence/eap-team-live-fixed-20261011/original-team-ledger.json)、[关联核验](evidence/eap-team-live-fixed-20261011/telemetry-proof.json)、[失败边界](evidence/eap-team-live-fixed-20261011/failure-boundary-proof.json)。归档 209570 bytes / SHA256 `8e3c1bb84dc0a8fb43d58f039bd011cfc5c68869b8f117b9a6977c44146893b6`；26 项摘要、750 源文件、4340 项旧证据通过。53 表历史保护通过，两套新原 UID 均休眠、身份撤销；本轮没有构建或更改应用部署，未 commit/push。旧 root1 UNKNOWN、关闭结果和旧实验额度均保留。

下一步继续已授权的仓库工具与旧发布迁移；本轮失败不授权再开模型实验或扩大输出上限。以下 T3-H2D 为历史记录。

最新T3-H2D（2026-10-11）：[真实团队验收](TEAM_EVOLUTION_LIVE_ACCEPTANCE.md)已完成本次失败诊断、修复部署和原根关闭；**真实模型端到端验收未完成，主线未全部完成。** 用户“继续”已确认公开工单分类、独立24调用/3072输出/工具0/最多900秒及仓库工具/旧发布迁移范围。生产root1首次原槽位UNKNOWN即停止，后5槽位未派发，候选验证/HOLDOUT未执行。原Gateway预占/上游消费0，账本与PLATFORM增量0；原UNKNOWN保留，原关闭键返回INCOMPLETE_NO_ADOPTION、无前沿/选择，不续期、不替换根或补分。

实测定位JPA以UTC保存的身份Instant被上海时区普通JDBC读早8小时，Gateway在预占前拒绝；另修复未封存前沿导致首次失败无法关闭。原MySQL红测4项（2失败/2错误）保留；修复远程209专项/7类、853全量/103类及打包通过，MySQL8.4.11/0900ai_ci/RR的UTC23+上海4项通过，全部0失败/错误/跳过。后端固定为`10.186.133.66:5001/eap-backend@sha256:3b3b1cebe23fd98e3d32c8d3f421721f5228be39a3409aa0734104057a731db8`，实际Pod imageID/健康/Flyway77通过；五个团队开关启用，T7-E runtime-denominators仍false，前端/Runner/AX/optimizer保持。

[验收索引](evidence/eap-team-live-20261011-index.html)、[最终回执](evidence/eap-team-live-20261011-final.json)：归档1094029bytes/SHA256 6af35d677757407ec415e96b45987c8cd2bf61c271c144715674ff899c42c9b4，322摘要/4源副本/750源码与4009旧证据核对。实际Secret仅节点RAM扫描；39表2831原行/旧UID/模板/身份/旧caps/revisions保护通过。新Agent64/65原UID休眠、身份撤销，5Job/5Pod按UID清理剩余0；接收核对后才精确4路径同步/root/eap-src，节点completed/closed/sourcePromoted=true、liveAcceptanceComplete=false。原127/11371实验计划及128/32768边界保持，PLATFORM160调用/13275输出/10工具、预留0未变。未commit/push。

下一步是修复后的新独立真实团队验收；须另行明确新的独立支出，不能沿用已到期root1或把其剩余额度转移。仓库现有工具/旧发布迁移范围已确认，尚未迁移；T2可信租户/资源配额与跨入口调度、T4/T5执行侧/旧消费者、T6恢复/取消/补偿、T7实际任务/对话/AX LLM与独立指标/日志/告警/规模、T8统一策略仍待，稳定收益未验证。以下旧阶段的未部署/未授权描述保留其当时含义，当前以本条及实际回执为准。

## 最初候选方案（2026-10-10，历史记录）


此文是可评审的有界方案，尚未执行、未申请/创建真实预算或Actor、未部署。先完成[独立HOLDOUT与费用关闭](TEAM_EVOLUTION_HOLDOUT.md)的最终隔离门禁，再据明确业务任务、新独立预算和实际共享容量决定是否进入真实验收。具体用户任务及新预算仍待明确；旧共享额度不足时停止，不能借本方案修改旧caps/revision、拆根或重放历史调用。

建议业务为公开合成工单分类，两角色分别提取工单要素、输出固定类别；若用户选择其他公开任务，需在提交前调整样本与EXACT_TEXT_V1预期答案。样本不含私有业务资料/用户信息/密钥。TRAIN 1、VALIDATION 3、HOLDOUT 3，共7个公开样本，输入/标签均属于公开合同；不是保密盲测。候选仅基线及一次人工离线单角色提示词变异，候选总数2、尝试上限1、代数1，不启动模型提案或自动搜索。

预算候选上界：maxCalls=24、每角色maxTokens=128、maxOutputTokens=3072、tools=0、proposalMode=OFFLINE_ONLY、批准期最多900秒。24来自`2 × (3 × 2 + 2 × 3)`，覆盖两个候选验证及基线/所选候选独立留出；实际费用按Gateway已知结算与UNKNOWN预留报告。此处数值不是支出授权，也不是对旧计划的扩容；旧127次/11371输出、128/32768上限保持，剩余1次不是新授权。若新任务需要不同上界，必须在创建新独立根前明确，创建后不续期或扩大。

需要两套新专属平台实例，均为固定已验证Runner镜像，工具关闭、stateless EMPTY上下文、固定模型和temp=0。冻结部署版本/配置/UID/模板、原机器generation、实时profile摘要及根原主体。旧Actor保持休眠/撤销，不通过重新批准旧身份继承原预算；共享容量不足或原身份/期限漂移时不派发。

执行顺序：

1. 只读核对最新绿色源、镜像/集群健康、Flyway迁移及实际共享容量。应用镜像构建/部署与开关启用尚需据最终验收范围执行，不能整份apply历史部署清单。
2. 用已明确任务和新预算建立一次新根，冻结公开样本、两角色、独立planKey及原期限；原键仅元数据重放。
3. 顺序执行基线及单角色候选完整VALIDATION，逐一平台可信评分；检查实际账本费用、完整交接及整任务时延。
4. 封存原验证前沿及选择；独立登记BASELINE和SELECTED HOLDOUT。两套即使指向同一基线也各自执行，不复用验证槽位或凭证批准期限。
5. 完整HOLDOUT分别评分、全根消费关闭。不确定发送立即停后续槽位，UNKNOWN预留保留，不换键重试/补分/退款；未完成可显式关闭为不完整。
6. 导出脱敏证据，核对全部费用/原期限/共享账户及最终状态；新实例按实际原UID挂起、身份撤销，临时验证对象按UID清理。没有自动应用提示词的步骤。

通过标准是原证据链可核对、预算/身份/期限不越界、公开完整分母与独立留出一致、最终费用及未知预留可解释。单次有界样本支持不等于稳定业务收益；KEEP_BASELINE和INCOMPLETE_NO_ADOPTION均是允许且必须如实记录的结果。实际Runner/模型端到端、异常真实Pod崩溃以及生产体验不能由隔离Mock测试冒充。
