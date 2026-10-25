# EAP 系统审查（2026-10-18）

结论：EAP 已具备通过平台创建、部署、调度、预算控制、交接和观测多智能体的可运行链路，但还不适合直接扩大到真实敏感数据和多人长期使用。本次发现 **5 项 P1、6 项 P2**；其中两项执行问题已在远程用合成请求复现。应先修安全边界和任务隔离，再补失败恢复与产品闭环，最后进行容量及生产部署验收。

原始审查覆盖当前工作区，不是单次 PR 的增量审查。主分支参照为 `origin/main`，当前分支为 `feat/galileo-evaluation-foundation`；大量既有未提交修改和新增文件均保留。原始审查没有修改业务源码或部署；后续修复状态如下。

**后续进展：R1至R11均已完成对应修复并部署到PoC集群。** 最新R10/R11后端1184、专项39（包含在全量中）、隔离MySQL13、发布前后四角色只读接口验收通过，健康UP、Schema V87，扫描批次通过EAP API确认提交。前端和Runner未改，本轮未重测。见[最新验收与限制](evidence/retention-signals-20261019/README.md)。无commit/push或真实模型调用，原业务状态、预算保持。

本报告列出的 **11项缺陷已完成对应修复**，不代表系统所有产品和生产门槛已完成。现网旧记录含无法确认原绑定的UNKNOWN，触发全局自动回收保护；普通新派发仍允许，自动休眠在历史核验前会被跳过。旧Runner实例仍须显式迁移，UNKNOWN不自动重试/核销。历史敏感数据、其他正文副本及备份保留、容量/HA/TLS/灾备仍待。另发现基础镜像53层约3.8GiB，构建解包效率需要优化；原生SQL运维时间比较须明确会话时区。以下缺陷描述/位置保留原始审查基线。

## 证据范围与判断标准

- 检查：权限和认证入口、连接器、运行时、工作流调度与预算、OTLP/正文处理、评估与运营事项、前端发布和聊天、反馈闭环及部署/测试记录。安全、前端和运维按 review 技能的专项检查并行核对。
- 新执行：经 `n1.sh` 在 ate-node1 独立目录 `/root/eap-system-review-20261018-r1` 对当前 16 份 Runner Go 源文件运行两项合成负向回归。它们要求隔离/取消的正确行为，**审查时实现两项均失败**，本轮修复后原探针均通过；原记录不是通过的回归测试。源码摘要及测试原文见 [本轮证据目录](evidence/system-review-20261018/README.md)。没有在本机编译或测试。
- 新只读抽查：远程 backend/frontend/mysql/prompt-optimizer 均为 ready 1/1；节点根分区显示约 22 GB 可用。仅代表抽查时状态，不等于容量/长稳通过。
- 既有证据：最新后端全量 1097、前端 410/类型/构建、Runner 42 顶层/85 含子用例、后续 4 项 OTLP 契约及四角色桌面/手机 8 组检查属于上一轮。独立 MySQL 364 项与更广页面冒烟属于更早基线，**本次没有重跑**。
- P1：扩大使用前优先解决；P2：可触发的可靠性/数据治理缺口。置信度指证据强度，不表示已经发生生产事故。除两项 Runner 探针外，下述缺陷以当前代码及调用链为证，没有在现网实施攻击或故障注入。

## 需要优先修改的 P1

### R1：连接器可以外发任意后端环境变量（9/10，代码确认）

**状态：已修复并部署。** 凭据改为平台登记引用并绑定 connectorKey、HTTPS origin 与认证参数；未知引用默认拒绝。现网连接器为 0，没有旧配置迁移中断。

位置：[ManagementConnectorClient.java:117](../backend/src/main/java/com/eap/platform/management/ManagementConnectorClient.java#L117)、[ManagementConnectorService.java:195](../backend/src/main/java/com/eap/platform/management/ManagementConnectorService.java#L195)、[SecurityConfig.java:147](../backend/src/main/java/com/eap/platform/config/SecurityConfig.java#L147)。

关键代码为 `System.getenv(connector.credentialEnvKey.trim())`，随后 `request.header(header, prefix + secret)`，并发送到可配置的 `endpointUrl`。连接器写入/同步属于 `DEPLOY_AGENTS`，OPERATOR 可以使用；服务端没有把凭据引用绑定到获准目标。

具有连接器写权限的账户，可以选择后端已存在的秘密变量名和自己控制的可达公网地址。现有私网限制及禁止重定向不能阻止这一条路径。此处只核对了变量名和源代码，没有读取、发送任何真实秘密，也没有证明已有外泄。

修改：用平台登记的凭据引用替代任意环境变量名；凭据必须绑定允许使用者、目标 origin、协议和请求头。单纯把按钮改成管理员可用，不能解决目标绑定缺失。

验收：隔离测试仅注入合成哨兵变量；未授权引用、错误 origin、用户修改目标均在网络请求前被拒绝。权限规则继续只定义于 `PlatformPermission`，前端按 `/me` 能力控制。

### R2：OTLP 正文通过 span 属性绕过正文策略和权限（9/10，代码确认）

**状态：已修复并部署。** 新写入和历史读取统一字段投影，同时封闭 OTLP 派生 RAG 知识摘要旁路。旧数据未物理擦除，其他知识来源仍需独立审计。

位置：[OtlpTraceIngestionService.java:328](../backend/src/main/java/com/eap/platform/observability/OtlpTraceIngestionService.java#L328)、[同文件:375](../backend/src/main/java/com/eap/platform/observability/OtlpTraceIngestionService.java#L375)、[同文件:401](../backend/src/main/java/com/eap/platform/observability/OtlpTraceIngestionService.java#L401)。

`captureTracePayloads` 明确把 `rag.query`、`mcp.request` 当正文；但属性脱敏仅匹配 `input/output/prompt/completion/arguments/result/documents` 等子串。这两个 key 会进入 `safe.put(key, ... value.asText() ...)`，原文被另存到 `attributesJson`。spans 两个查询接口直接返回该字段，VIEWER 有权访问而没有 `viewContent`。

因此合法采集器上传这些字段时，即便正文采集配置为 `OFF` 或 `HASH_ONLY`，原文仍可能经元数据落库和返回。运行实例身份绑定不能替代内容权限。

修改：统一正文属性识别，元数据采用明确允许的字段和类型；正文只经正文存储/权限路径读取。修复后还需评估已有属性数据，不能只保护新写入。

验收：使用 `rag.query`、`mcp.request` 合成标记，分别覆盖三种采集模式和四角色，查询分页及非分页 spans；无正文权限的返回不得含标记。保留正常终止原因等安全元数据，避免再次误脱敏。

### R3：不同工作流及试运行共用同一 Runner 对话历史（10/10，远程复现）

**状态：参考Runner已修复并更新默认镜像。** task无隐式历史、chat使用显式当前窗口历史；平台RUNNER旧实例能力不兼容则阻断。旧模板/实例/快照未自动升级，自定义运行程序仍需独立核对。

位置：[main.go:141](../runtime/agent-runner/main.go#L141)、[main.go:193](../runtime/agent-runner/main.go#L193)、[main.go:284](../runtime/agent-runner/main.go#L284)、[main.go:345](../runtime/agent-runner/main.go#L345)。

任务解码结构只有 `taskKey/objective/context/traceId`，没有会话作用域；`askScoped` 通过 `append(..., r.history...)` 取全局历史，成功后又写回。`/chat` 和 `/eap/task` 走同一历史。工作流选择运行实例只按 Agent、有效性和负载，不按工作流隔离。

复现：同一 Runner 先接收 workflow-A 的唯一合成标记，再接收独立 workflow-B；B 实际发给替身模型的 messages 包含 A 标记。即使 B 没有声明 `inputFrom` 也会发生。已有样例使用独立实例只证明该样例的交接，不覆盖复用实例的隔离。

修改：工作流任务默认无隐式历史，只接受显式上下文；需要多轮记忆时，按服务端认证的 session/run 作用域保存。聊天也需明确会话归属及重置入口，不能仅在浏览器清空显示。

验收：不同工作流、不同聊天会话、chat→task、task→chat 均不得串入合成标记；同一显式会话保留预期历史。同步验证请求摘要和实际模型 messages。

### R4：请求取消后，排队任务仍可开始模型调用（10/10，远程复现）

**状态：参考Runner及已识别工作流准入已修复并部署。** 等待可取消、锁后及发送前核对上下文/身份；网关校验当前工作流责任。已经获准的调用仍可能计费，发送后异常保留UNKNOWN及预算证据；未新增通用取消API。

位置：[main.go:161](../runtime/agent-runner/main.go#L161)、[main.go:280](../runtime/agent-runner/main.go#L280)、[main.go:300](../runtime/agent-runner/main.go#L300)、[BudgetLedger.java:176](../backend/src/main/java/com/eap/platform/budget/BudgetLedger.java#L176)。

任务等待 `taskMu`/`conversationMu`，获得锁后只重新验证凭据，不检查 `req.Context()`；上游请求用 `http.NewRequest`，未传播请求上下文。普通任务预算归属只检查 task owner，未核对该执行是否仍在有效派发阶段。

复现：锁住任务入口，提交合成任务并取消其上下文，再释放锁；仍发生 1 次替身模型调用。这证明取消前尚未执行的工作仍会启动；未声称已观察到现网超支。现有预算上限仍然有效，也不意味着能撤销已发送给供应商的调用。

修改：统一任务 deadline/执行责任校验；等待阶段可取消、进入模型前再次核对执行权。取消已发出的调用时保持 UNKNOWN 和原计费证据，不能假设供应商未计费。

验收：锁等待期间取消/到期产生 0 次调用；执行责任失效后不能新准入；已发出后的断线保持原身份、预算与未知状态，不自动补发。

### R5：聊天丢失响应后重试会再次调用模型（9/10，代码确认）

**状态：已修复并部署。** V85持久原键/载荷摘要及派发屏障防重复，GET和重复POST仅查原回执，前端刷新保留待核对编号。旧实现负向探针2次派发，修复后1次；H2/MySQL及浏览器恢复通过。回执不保存正文，UNKNOWN不自动重发；详见[契约与边界](CHAT_SUBMISSION_RECEIPTS.md)。以下保留原缺陷。

位置：[SubstrateDeployPanel.vue:372](../frontend/src/components/substrate/SubstrateDeployPanel.vue#L372)、[SubstrateDeploymentService.java:386](../backend/src/main/java/com/eap/platform/deployment/SubstrateDeploymentService.java#L386)、[main.go:278](../runtime/agent-runner/main.go#L278)。

聊天 API 只提交 message；后端每次新建观测和 trace，Runner `askScoped(user, traceID, "")` 没有持久请求身份。任何异常时前端都提示“发送失败，请查看提示信息后重试”。若模型已完成而 HTTP 回执丢失，重试会再次调用并追加历史。普通任务新增的 task 缓存不覆盖 `/chat`。

修改：为聊天建立持久提交键、载荷摘要及回执查询。UNKNOWN 只查询原请求，明确区分“未准入”和“已派发但未确认”。不能仅禁用按钮。

验收：替身模型完成后丢弃响应，再用原键查询/重试；模型调用及历史追加都只能一次，同键变参应拒绝。

## 后续必须补齐的 P2

### R6：标准 JSON 凭据漏脱敏且被判为 PUBLIC（9/10，代码确认）

**状态：已修复并部署。** JSON 递归脱敏、嵌入字符串及异常结构隐藏已通过回归；PUBLIC 仍是兼容性启发式分类，旧 REDACTED 样本未批量改写。

位置：[SensitiveContentSanitizer.java:15](../backend/src/main/java/com/eap/platform/observability/SensitiveContentSanitizer.java#L15)、[EvaluationDatasetItemService.java:202](../backend/src/main/java/com/eap/platform/evaluation/EvaluationDatasetItemService.java#L202)。

正则要求 `password` 后直接出现空白和 `:`/`=`，不接受 JSON 键结束引号。`{"password":"synthetic-marker"}` 不命中，落到 `new SanitizedContent(value, "PUBLIC", ...)`。调用方确实传入 `JsonNode.toString()`；在 `REDACTED` 模式，评估样本可保存并通过普通查询返回这段原文。当前 HASH_ONLY 的样本分支不触发此项，不能声称现网样本已泄漏。

修改：结构化字段递归脱敏，文本处理作为补充；“未命中规则”不能等同于已经证明 PUBLIC。验收覆盖 quoted JSON、嵌套对象/数组、转义字符串及纯文本，并验证落库和查询输出。

### R7：部署发布幂等键在刷新、切页或 5xx 后丢失（9/10，代码确认）

**状态：已修复并部署。** 发送前保存按subject/Agent分隔的原编号和配置摘要；刷新/切页恢复，错误保留编号，配置变更阻断。新增只读原键查询，不受最近20条限制；NOT_FOUND不释放原键。同配置人工重试复用原键；并发仅一个版本/首实例在隔离数据库测试中验证。8组浏览器通过，8次合成部署POST仅4个意图/版本，12截图检查通过。关闭会话/清存储/换设备后不保证自动恢复；未保存配置正文，部署仍沿用协作资源权限。见[验收与边界](evidence/deployment-recovery-20261018/README.md)。

位置：[SubstrateDeployPanel.vue:238](../frontend/src/components/substrate/SubstrateDeployPanel.vue#L238)、[同文件:281](../frontend/src/components/substrate/SubstrateDeployPanel.vue#L281)、[同文件:291](../frontend/src/components/substrate/SubstrateDeployPanel.vue#L291)、[AgentDetailView.vue:117](../frontend/src/views/AgentDetailView.vue#L117)。

`let pendingRequestKey = ''` 只存于组件；详情 tab 切换会销毁组件。错误分支 `if (response) pendingRequestKey = ''` 也会丢弃 502/504 等未知结果的原键。服务端按原键去重，新键可新建版本/实例，无法保护这个重试路径。

修改：按 subject、Agent 持久化最小待确认索引，服务端提供原键查询；冻结配置摘要，不在浏览器持久存储完整提示词。网络断线/5xx 不应清除原身份。

验收：受理后断线、502、刷新、切 tab 和账户切换；同一次意图最终只创建一个版本和首实例。

### R8：实例 A 的迟到聊天回复显示在实例 B 中（9/10，代码确认）

**状态：已修复并部署。** 会话代次隔离成功/失败/finally回调，切换身份/Agent/实例及卸载时重置；8组真实登录与合成聊天通过。同步修复手机宽度和顶栏遮住试运行标题/关闭按钮。

位置：[SubstrateDeployPanel.vue:356](../frontend/src/components/substrate/SubstrateDeployPanel.vue#L356)、[同文件:373](../frontend/src/components/substrate/SubstrateDeployPanel.vue#L373)。

等待 A 回复时仍可关闭抽屉并打开 B；`openChat` 清空共用数组，而 A 的完成回调无条件执行 `chatMessages.value.push(...)`。返回的实例身份未校验，B 标题下会出现 A 的输出。

修改：按实例和会话保存状态，响应检查 subject、实例及请求代次。验收用延迟 A 回复的合成 API，切换 B 后释放 A，B 不得显示 A 内容。

### R9：明确失败的工作流任务绕过资源休眠（9/10，代码确认）

**状态：已完成安全修复并部署。** 先保存成功/明确失败的业务回执，再按跨入口占用和持久回收屏障申请休眠；失败原因、回收状态分开记录。现网旧记录含无法确认原绑定的UNKNOWN，触发全局自动回收保护；普通新派发仍允许，但自动休眠在历史核验前会被跳过。 见[R9验收](evidence/workflow-reclaim-20261018/README.md)。

位置：[WorkflowTaskClient.java:92](../backend/src/main/java/com/eap/platform/workflow/WorkflowTaskClient.java#L92)、[WorkflowExecutionService.java:263](../backend/src/main/java/com/eap/platform/workflow/WorkflowExecutionService.java#L263)。

任务调用前唤醒 Actor；收到任何非 SUCCEEDED 回执后，在 `... || terminal) return` 提前返回。`suspendAfterTask` 处理在下一行之后，因此 Runner 明确返回截断/空正文等 FAILED 时也不会进入回收阶段。可能留下活跃实例占用槽位；这里没有把 UNKNOWN 时不休眠当作错误，已有测试明确保护了该安全行为。

修改：为明确终态失败增加可审计的回收流程，并核对该实例的独占/在途状态。UNKNOWN 先核对执行，不做无条件 finally 休眠。

验收：明确失败、未知、成功、休眠失败四类分别验证；失败可释放独占资源，未知不会中断可能仍执行的任务，原业务回执保持。

### R10：原文保留期限只有字段，没有到期擦除（9/10，代码确认）

**状态：已修复并部署。** V87与有界清理任务按可信服务端期限擦除在线密文列，保留脱敏证据与原记录关系；复用投影行锁防止旧实体回写密文。现网密文0，擦除验证来自隔离数据；不包括物理页/binlog/备份或其他副本。见[R10/R11验收](evidence/retention-signals-20261019/README.md)。

位置：[BusinessContentVaultService.java:99](../backend/src/main/java/com/eap/platform/discovery/BusinessContentVaultService.java#L99)、[BusinessContentRecordRepository.java:7](../backend/src/main/java/com/eap/platform/discovery/BusinessContentRecordRepository.java#L7)。

启用 `retainRaw && sampled` 后写入密文与 `expiresAt`，但当前生产代码没有对应的到期擦除任务/删除查询。默认 30 天只体现在元数据中。没有检查生产原文记录，不断言已发生超期留存。

修改：有界、可恢复、幂等的到期清理，清除密文和 IV、更新保留状态，保留必要指纹与审计关系；明确期限采用可信接收时间还是采集时间。

验收：混合到期/未到期数据、执行中断和重复清理；内容不可恢复且证据引用仍正确。

### R11：运营事项首次扫描可能漏掉第 501 条之前的信号（9/10，代码确认）

**状态：已修复并部署。** 两类目标信号先过滤再分批，持久游标与轮次上界支持循环覆盖；回滚不跳项，旧失败不覆盖更新结果。超过1000条、旧状态变化、事务回滚在H2与独立MySQL验证；线上调度提交已确认，现网目标信号0，不声称在线积压验收。见[R10/R11验收](evidence/retention-signals-20261019/README.md)。

位置：[WorkItemService.java:241](../backend/src/main/java/com/eap/platform/agentops/WorkItemService.java#L241)、[同文件:277](../backend/src/main/java/com/eap/platform/agentops/WorkItemService.java#L277)。

实现先取全部结果最新 500 条，再筛选 PENDING 审批或失败评估；没有持久游标。停机恢复或两次同步之间积累超过窗口时，较旧信号可能从未进入建项流程，以后扫描仍然遗漏。已有“窗口外事项不误关”保护只针对已建项，不能补首次漏建。

修改：采用可恢复分页/增量游标或未处理源扫描。验收首次同步前加入 501+ 条并混入成功噪声，跨重启继续扫描，每个有效信号最终只建一项。

## 已实现的防护，不应重复报成缺陷

- 权限能力集中于 `PlatformPermission`；JWT 和机器身份路径分开，运行实例已有 UID/template/generation 绑定。
- 普通模型任务已检查 finish_reason、空正文、拒绝及工具响应；失败不进入下游；内存缓存阻止同进程重放并拒绝同键变参。
- 工作流有提交键、持久派发意图、执行 owner/epoch、未知状态不自动重发；受控工具有作用域及效果核验。不能把这些描述为完全没有幂等或恢复。
- 预算准入和结算共用数据库锁，未知结果保留预留是保守行为；不能以用户体验为由直接释放未知额度。
- 调度已有申请者轮转、等待老化和阶段耗时，不是完全缺少公平性。
- Keycloak 已迁移到持久卷，并验证过加密备份和恢复；旧文档的 emptyDir 不能当作当前事实。

## 产品与架构仍需完成的工作

| 方面 | 当前边界 | 下一步及验收 |
| --- | --- | --- |
| 正文/提示词权限一致性 | 部署列表完整 config 含 systemPrompt；相同提示词进入实验正文后要求 viewContent。部署提示词可见性尚未统一定义 | 明确字段级规则，部署元数据和正文分开；四角色验证。同一内容不能因入口不同出现无意的权限差异 |
| 结构化输出与业务质量 | 正常 stop 只能证明生成终止，不能保证 JSON schema、事实准确或任务完成；最新复核员仍有措辞过度 | 节点声明输出契约，结构/引用失败阻止下游；以原句和证据编号校验复核意见。使用多案例留出评估，不把一次成功当稳定收益 |
| 输出体积契约 | Runner 可配置至 8192 输出 token，但工作流 output 超过 16 KB 会变为 EXECUTION_UNKNOWN | 统一并公开 token/字节限制；大结果通过受控 artifact 引用交接。测试完整但超 16 KB 的输出，避免笼统建议增加 token |
| 通用未知结果恢复 | Runner 回执为内存；网关能拒绝重复调用，但没有因此自动恢复原正文。通用预算 DISPATCHED/UNKNOWN 缺少完整人工核销闭环 | 持久回执查询、失联意图核对、不可变对账调整记录；重启前后原键恢复且不新调用，不通过换键绕过未知 |
| 反馈采用→发布 | ADOPTED 只是评审状态，界面也明确运行配置未改变 | 串起候选配置、独立验证、目标版本、显式发布、回退与效果观测；保留审批和旧版本，不自动把反馈写成系统提示词 |
| 多智能体编排范围 | 当前普通工作流执行选定串行路径，分支在提交时按输入解析；不等同于并行 DAG、动态模型输出分支或通用补偿 | 按真实业务需求逐步补并发、动态分支和人工中断，每项先定义执行/预算语义，再做故障验收 |
| 会话退出与错误恢复 | 已有浏览器证据：退出仅当前 tab，SSO 和其他 tab 保留；403 toast 正确但表单仍显示结果未确认 | 定义本地退出/全局退出契约；统一确定拒绝与未知状态文案。不能把现有退出范围直接称为越权 |
| 测试证据与状态文档 | 测试记录充分，但多个“当前/最新”文件顶部停在不同阶段；例如 MAINLINE_STATUS 仍以 10 月 15 日为最新 | 建立单一发布摘要，链接各轮证据和未验范围；保留历史，避免把历史通过项当作现版本重验 |

## 生产部署门槛

1. **认证的生产持久化、可用性与灾备。** 当前 Keycloak 单副本/Recreate、H2、local-path、HTTP 是明确 PoC 配置；已有一次加密恢复验证，但定时备份、外部密钥恢复依赖、跨集群灾备、HA/TLS 尚未完成。这里需要迁移的外部数据库是 Keycloak 的数据库，不是误称 EAP 主库尚未使用 MySQL。依据：[AUTH_PERSISTENCE.md](AUTH_PERSISTENCE.md)、[keycloak.yaml:140](../deploy/substrate-poc/keycloak.yaml#L140)。验收需独立恢复目标并记录 RPO/RTO，不能以 Pod 重建代替节点灾难。
2. **构建测试资源与业务服务隔离。** 先前并行全量测试导致约 8 GB 节点剩余可用内存降至约 112 MB、SSH/API 变慢，另有 DiskPressure 拒绝测试。串行与清理已缓解，但没有压力期连续业务 SLO 数据。依据：[system-validation 报告](evidence/system-validation-20261018/README.md)。应建立统一资源准入或独立测试节点。
3. **容量和长期运行证据。** 明确目标 Agent 数、并发任务、遥测量，测业务 p95/p99、失败率、队列等待、数据库锁等待、积压、保留清理和节点压力；再做失联/重启/恢复演练。当前 ready 1/1、单样例通过都不能代替。
4. **发布可复现与回退。** 保留现有镜像 digest、源文件摘要、数据库迁移及按 UID 清理约定；把版本、迁移、原请求回执和回退验证纳入统一发布记录。本轮没有为整理工作区进行提交或推送。

## 建议执行顺序

1. 安全边界R1/R2/R6已修复；继续统一systemPrompt字段可见性和历史敏感数据治理。
2. R3/R4/R5已修复；保留UNKNOWN原结果核对与跨系统持久回执的后续边界。
3. R1–R11已修复；继续历史UNKNOWN证据核对、明确拒绝/未知的统一交互，以及[Kagent调研提出的任务工作台与结果契约](research/kagent-adoption-20261019/README.md)。
4. 业务闭环：输出契约、证据引用、反馈采用→版本发布→回退；在新的独立预算下做多案例真实验收。
5. 扩容上线：数据库/认证灾备、测试资源隔离、性能长稳及故障演练。

旧 Plan5/Plan6 的样例及已用预算保持关闭；不得为修复验收重放它们，也不执行尚未授权的 28 调用优化方案。安全和合成回归可以先完成，新的真实模型验收另定范围及预算。
