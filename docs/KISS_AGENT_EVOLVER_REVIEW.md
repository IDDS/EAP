# KISS AgentEvolver 对 EAP 的借鉴判断

2026-10-09实施补充：用户要求继续主线并确认继承方向后，已完成[第一步零模型候选合同](AGENT_EVOLUTION_CANDIDATES.md)的原生实现与远程43项回归、归档和清理。只继承方法，没有引入或运行KISS代码；尚无持久团队评测、真实优化收益或生产部署。下文保留研究时点的事实与计划。

访问日期：2026-10-09。研究对象已按用户澄清限定为 `ksenxx/kiss_ai` 中的 `AgentEvolver`。固定提交：[`44583f1cf416e7befda46e3cb79345390e598b2a`](https://github.com/ksenxx/kiss_ai/commit/44583f1cf416e7befda46e3cb79345390e598b2a)，提交时间 2026-10-08T16:27:33Z；12 个公开文件的 Git blob 与下载内容逐项一致，登记见[来源记录](research/kiss-agent-evolver-20261009/source-register.json)。研究未安装或运行 KISS，没有对外发送 EAP 源码、私有轨迹或凭证。

## 结论

**可以借鉴，优先吸收候选谱系、受限变异和多目标选择；适合扩展 EAP 的调优控制面。直接引入这个旧执行器的价值较低。** 这是基于源码与 EAP 当前实现的工程判断，置信度中高；是否改善真实团队的质量、费用或时延，仍需独立对照。

最有用的方向是：在 EAP 已有 GEPA 提示词调优之上，让一个候选表示团队内各角色的提示词配置，记录它从哪里来、改了哪个角色，以及整支团队的实测结果。先保留质量合格而成本、时延各有优势的候选，再做封存和独立留出；不让单一加权分数直接决定上线。

KISS 中的这一类优化多个候选 Agent 程序；候选程序可以包含组合逻辑，但源码没有 EAP 所需的团队身份、权限、预算和持久工作流评测合同。**“候选种群”与“协同执行的多角色团队”是两个不同维度。** 其模块 README 与包初始化均明确标记弃用；这是版本事实，不代表方法无用。[模块说明](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/obsolete/create_and_optimize_agent/README.md)、[弃用标记](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/obsolete/create_and_optimize_agent/__init__.py)。

## 借鉴点及适配方式

| 机制 | 代码核读事实 | 对 EAP 的判断与建议 |
| --- | --- | --- |
| Pareto 前沿 | `AgentVariant.dominates` 与前沿裁剪保留不同指标的非支配候选 | 值得借鉴。先过质量、身份、usage 完整性门禁，再比较业务质量、总 Token 和团队端到端时延；未知指标不参加排序 |
| 变异、交叉、谱系 | 每代从前沿选父代，执行单父代改进或双父代交叉，保存 `parent_ids`、代数和指标 | 值得借鉴。先每次只改一个角色的提示词；交叉仅组合已批准的提示词片段，保存父代摘要、变更范围和原评价关联 |
| 改进报告 | `ImproverAgent` 接收前次报告；交叉实际合并两个报告中的想法，再改主父代代码 | 可借鉴经验复用，但应引用真实失败码、训练轨迹与变更摘要。生成者的文字报告不能成为验收依据 |
| 多视角提案 | 当前 `skillopt` 分别分析成功、失败轨迹，由 ranker 合并受限补丁 | 适合补充现有反思器。提案、排序、执行评测分别计费，提案角色只拿获批训练信息；最终判断由平台完成 |
| 小范围编辑 | `skillopt` 的 SEA 候选检查禁止改变提示词常量之外的代码，接受后写 `.proposed` | 与 EAP 当前只接受 `system_prompt` 的规则接近。可扩成指定角色的提示词映射，输出候选版本，保留人工采用合同 |
| 从运行轨迹定位浪费 | `rsi7d` 分析工具错误、重复调用、停滞等运行信号 | 适合 T7 之后的问题定位和 T3 调优建议。先做只读建议；真实工具操作不能直接按历史任务重放 |

以上事实分别来自[AgentEvolver](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/obsolete/create_and_optimize_agent/agent_evolver.py)、[ImproverAgent](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/obsolete/create_and_optimize_agent/improver_agent.py)、[SkillOpt 实现](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/seas/skillopt/skillopt_sea.py)与[rsi7d 实现](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/seas/rsi7d/rsi7d_sea.py)。表中 EAP 建议是本研究推断，尚未实现。

## 接入前需要替换的边界

1. **评测可信性。** 旧 AgentEvolver 用同一 `task_description` 执行候选，并读取候选返回的指标；子进程失败或 JSON 无法解析时，返回失败但 Token、耗时为零。EAP 应继续以固定评分器、原回执与 GATEWAY usage 为准，缺失保持未知。这些是源码可见行为，本研究没有运行故障复现。
2. **搜索期限和总预算。** `max_generations` 只限制初始化之后的迭代。初始化按前沿大小循环，没有独立尝试次数上限；例如初始目标大于前沿容量时，按代码逻辑无法完成初始化。候选子进程没有显式 timeout；外层也没有统一覆盖全部候选生成与评测的根账本。底层 Sorcar 有预算能力，不能因此把旧类视为完全没有预算，更不能推定它已满足 EAP 的根预占合同。[旧执行器源码](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/obsolete/create_and_optimize_agent/agent_evolver.py)、[Sorcar 累计预算实现](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/sorcar/relentless_agent.py)。
3. **评分与隔离。** 旧类最终使用加权和选“最好”；质量损失可能被其他指标抵消，缺失指标在不同路径的默认值也不一致。临时目录及子进程提供进程分离，不能等同于限制凭证、网络和工具权限的沙箱。EAP 应先设不可被成本抵消的质量门禁，再运行受限配置；不执行优化器任意生成的 Python。[AgentEvolver 评分与子进程](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/obsolete/create_and_optimize_agent/agent_evolver.py)。
4. **独立留出。** 当前 SkillOpt 的 `select` 用于逐轮选择，默认未标记 split 的任务同时进入 train/select，ranker 还读取选择阶段轨迹。它可以用作搜索验证，不能充当搜索器从未接触的最终留出。EAP 继续保留第三份 HOLDOUT，并在候选封存后运行。[SkillOpt 切分与选择代码](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/seas/skillopt/skillopt_sea.py)。
5. **持久性与真实副作用。** 旧 `save_state` 保存 JSON，结束后清理工作目录；它没有 EAP 的原键、claim、持久批次及 UNKNOWN 审计合同。KISS 当前任务分派将等待超时与子任务继续运行区分，值得参考，但线程停止不证明外部效果已撤销。EAP 的未知结果仍需原意图核对。[旧状态保存](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/obsolete/create_and_optimize_agent/agent_evolver.py)、[当前分派实现](https://github.com/ksenxx/kiss_ai/blob/44583f1cf416e7befda46e3cb79345390e598b2a/src/kiss/agents/sorcar/agent_dispatch.py)。

## 与 EAP 当前能力的关系

核对 [OptimizationService](../backend/src/main/java/com/eap/platform/optimization/OptimizationService.java)、[OptimizationEvidenceService](../backend/src/main/java/com/eap/platform/optimization/OptimizationEvidenceService.java)、[WorkflowExecutionService](../backend/src/main/java/com/eap/platform/workflow/WorkflowExecutionService.java)及[项目记忆](PROJECT_MEMORY.md)后的判断：

- EAP 已有固定 GEPA 0.1.4、仅提示词候选、TRAIN/VALIDATION/HOLDOUT、封存后持久批次、全流程实际费用与 UNKNOWN 停止合同；这些能力应复用。
- 当前优化目标绑定一个 Agent 与一个实例。团队/计划已能串行执行和传递真实上游结果，但没有把整支团队作为优化候选并进行端到端配对评测的接口。逐个 Agent 分数相加不能代替团队业务结果。
- 当前完整公开任务留出与独立对照保持 `KEEP_BASELINE`，没有稳定生产收益。引入新的搜索器不改变这些历史结论，也不增加原计划的可用额度。
- `PlatformPermission` 仍是权限来源；优化提案不能更改工具授权、身份、模型、预算或运行期限。其他角色的内容也不能因组成团队就自动进入反思上下文。

| 路径 | 适合范围 | 当前判断 |
| --- | --- | --- |
| EAP 原生实现 | 候选登记、谱系、受限配置、可信评测、费用与权限 | 推荐；由现有控制面定义合同，算法作为可替换组件 |
| 直接接入 KISS 旧 AgentEvolver | 自动生成及执行 Agent 代码 | 暂不推荐；弃用与执行边界使适配成本较高 |
| KISS 作为外部候选提案器，EAP 执行验收 | 仅交换获批公开训练摘要与受限 JSON 候选 | 后续可验证；尚无兼容性实测，不能传私有源码或轨迹 |
| Galileo 等已有外部评测方案 | 延续各自的评价、观测接入定位 | 本研究不替换原方案；KISS 的候选搜索与外部评价是不同职责 |

## 有界 PoC 计划：先离线，再验证两角色团队

以下是待实施计划，没有分配新模型额度、部署执行器或运行真实团队优化。

**第一步：零模型的离线验证。** 用公开合成评价记录验证谱系、确定性前沿和错误处理。既有历史数据可只读核对，但旧实验不能补造新策略或重放。示例夹具只是计划输入：

| 候选 | 质量通过率 | 总 Token | 团队端到端时延 | 预期处理 |
| --- | --- | --- | --- | --- |
| 基线 | 100% | 1000 | 1200ms | 保留 |
| A | 100% | 800 | 1500ms | 保留，成本较低 |
| B | 100% | 1200 | 900ms | 保留，时延较低 |
| C | 80% | 500 | 600ms | 质量门禁拒绝 |
| D | 100% | 未知 | 1000ms | 证据不完整，不进入前沿 |

另外验证同键复用、改参数冲突、父代不可变、成功候选不被缺失指标候选替代，以及 UNKNOWN 不自动重跑。前沿只使用同一冻结评测协议、同一任务批次和相同完成条件；搜索成本与候选服务成本分别展示。

**第二步：固定两角色串行任务。** 采用公开结构化任务，例如角色 A 提取事实、角色 B 按固定规程分类；评分来自预先冻结的结构化答案。冻结模型、运行版本、实例身份、工具关闭、角色映射、结果交接、期限与评分器。先只变 A 的提示词，确认后再变 B；不同时搜索拓扑、权限和模型，以免无法归因。允许最多 4 个候选、1 轮、一次只生成一个新候选；次数限制独立于前沿大小。记录完整候选配置摘要、父代、变更角色、原工作流键和评价版本。

团队评测需要新增适配合同：冻结候选提示词映射、不更新生产 Agent；持久登记配对工作流原键与每角色调用；关联团队业务得分、角色 usage、端到端时间和调度阶段。现有单 Agent 优化 API **不能直接承担该验证**。并行拓扑搜索放到跨入口调度与资源配额完成之后。

**第三步：封存和独立验收。** TRAIN 只用于提案；VALIDATION 选候选；至少 10 个独立任务组的 HOLDOUT 对照基线、人工版本和已封存候选，并另留一批独立复验。搜索器不得读取留出答案或轨迹。所有登记任务进入分母，失败、未运行和 UNKNOWN 分列。十组只是现有门禁起点，不代表统计显著或生产通用性。

预算计划必须先列出每个提案、评价、反思和团队角色的实际调用上限。若一轮使用 3 个已存在配置加 1 个新配置、每配置 2 个训练和 2 个验证任务，封存后 3 个版本各评 10 条留出、每团队固定 2 次模型调用，再加最多 2 次提案调用，则第一批最坏为 `(4×4 + 3×10)×2 + 2 = 94` 次。每角色输出上限 128、提案上限 512 时，最坏输出为 `92×128 + 2×512 = 12800` Token。独立复验另外 60 次/7680 输出，合计 154 次/20480 输出；它超过当前单 Agent 根 64 次准入上限与原计划剩余差额，**只能作为说明团队预算必须另立合同的计算示例，当前不能提交，也不能拆根绕过限制**。真实计划仍取平台、实例和根实时预算的严格交集；输入 Token、价格版本与实际账本也需报告。

只有完整回执、全部 usage 与零未知预占满足门禁时，才能给人工复核建议。任何阶段出现身份漂移、UNKNOWN、预算不足或工具效果不明，停止原实验并保留基线。不能以降低采样、放宽期限或重放旧任务获得表面收益。

## 证据强度和可能改变结论的条件

代码、模块说明与独立作者的 [SkillOpt 论文 v2](https://arxiv.org/abs/2605.23904v2)共同支持“受限文本变更、反馈与搜索验证有方法基础”。论文实验属于作者报告，既不能证明 KISS 此实现复现论文，也不能证明 EAP 收益。所有关于旧执行器风险的判断是静态代码核读，没有本轮运行复现。

另一种可行解释是：现有 GEPA 与人工提示词已足够，种群和多视角提案只会增加搜索成本。若独立团队对照没有出现质量合格的成本或时延收益，保留现有搜索与基线；若后续主线的主要瓶颈是排队、身份准备或工具等待，则优先解决调度与执行边界，不能把这些耗时归咎于提示词。

本轮已完成研究、来源版本核对和 EAP 实现对照；没有完成 PoC、团队调优实现、生产收益验证或新部署。T7-C 独立负向合同的实际结果另见[回执](evidence/eap-diagnostic-policy-red-20261009.json)：7 项、6 个预期失败、0 错误，尚无绿色修复；它不构成 KISS 验收。主线后续先完成可信诊断/覆盖，再按零模型离线验证、两角色受控评测的顺序推进。
