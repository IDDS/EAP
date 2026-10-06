# 真实反馈与版本化 Agent 提示词实验

2026-10-02 已部署 T1；这是受限的真实执行与对照底座，自动优化器尚未接入。研究依据及后续合同见 [T1–T8 实施合同](research/agent-system-methods-20261002/implementation-plan.md)。

## 操作路径

1. 进入“评估与优化 → 反馈与改进”，或智能体详情的质量页点击“反馈与提示词对照”。
2. 新增反馈选择实际智能体、类型、1–5分及问题摘要；可关联该智能体的任务或 Trace，填写改进建议。服务端记录真实登录主体，校验每项关联归属；浏览器不提供固定示例数据。
3. 治理账号可以将反馈从待处理转为评审中，再采纳或拒绝。状态带 expectedStatus 防止覆盖并发修改。采纳只改变反馈记录，不修改运行提示词、不发布版本。
4. 管理员选择已启用且平台托管的实例，读取运行基线。需要新的参考 Runner、固定镜像和 READY 发布版本；旧模板不会随新默认镜像自动升级。配置式新发布已验证用户模型及 systemPrompt 映射到真实基线。
5. 只填写候选提示词，提供 1–3 条公开合成输入和预期文本，确认样本用途，设置输出上限。提交会实际调用模型，每条样本分别运行基线和候选，一次实验最多6次调用。
6. 刷新查看两个版本的成功数、新输出、Trace、Token覆盖及延迟。正文查看受独立内容权限控制；样本、提示词、输出不会出现在元数据接口。

输入来自公开手工样本，不接入客户正文、现有数据集正文或历史 actual。当前评分器 EXACT_TEXT_V1 比较 trim 后的完整文本；输出截断记0分。它不是通用语义质量或业务成功评审，也没有训练集/留出集管理。

## 冻结与执行合同

- 冻结 actor UID、template、固定镜像、发布版本、认证运行配置及其 digest、候选配置、样本与评分器。候选和基线提示词是唯一变化因素。
- 两版使用同一模型、temperature=0、空会话、禁用工具和相同输出上限。实验不读取或更改普通聊天历史，也不改部署配置。样本交替运行顺序以减少顺序偏差；没有随机重复、温度0确定性保证或统计显著性结论。
- 每个输出上限1–256，且不能超过实际 Runner 配置。提示词最多8000 UTF-8字节，样本输入最多2000 UTF-8字节；预期文本最多2000字符。只支持非空且输入互异的1–3条样本。
- 全平台数据库准入锁串行实验，不是跨工作流/普通任务的共享 Token 或金额预算。存在 QUEUED/RUNNING/UNKNOWN 时，新实验拒绝；UNKNOWN 不能通过新键绕过。
- DB先提交稳定 taskKey/Trace 与执行意图，再发HTTP；认证调用前复核原生实例身份和运行配置。发送后响应丢失记 UNKNOWN，不自动重试；失联180秒标记未知，剩余样本取消。
- Runner私有日志先 fsync UNKNOWN 意图，再执行模型；成功结果以原键重放，改参409。每个日志目录最多256项，不自动删除。保留原目录时进程重启不重花模型调用；不保证删除存储、克隆旧快照、多进程共享目录或跨系统 exactly-once。
- HTTP响应最多64KiB，禁用重定向，后端单次调用总期限130秒。任务可能在请求超时后继续，UNKNOWN不是已取消、已失败或零消耗。
- provider-reported usage 只有合法输入/输出计数齐全时显示；缺失为 UNKNOWN/null，不是0。Token不是金额，当前不计算费用。queueMs、Runner latencyMs 与派发到收据的 endToEndMs 分开。
- 尚无 UNKNOWN人工对账解除、自动候选搜索、自动采用、共享预算预留或生产 SLO。

## 权限及接口

授权集中在 PlatformPermission，前端只读取 /api/platform/me 的 capabilities。

| 能力 | viewer | operator | governance | admin |
| --- | --- | --- | --- | --- |
| 反馈/实验元数据 | 有 | 有 | 有 | 有 |
| 新增真实反馈 manageFeedback | 无 | 有 | 有 | 有 |
| 反馈评审与正文 viewContent | 无 | 无 | 有 | 有 |
| 读取运行基线/执行 runAgentExperiments | 无 | 无 | 无 | 有 |

| 接口 | 用途 |
| --- | --- |
| GET /api/learning-feedbacks?agentId=… | 最近100条；无正文权限返回null及contentVisible=false |
| GET /api/learning-feedbacks/evidence?agentId=… | 该智能体可关联的任务/Trace元数据，不读取任务正文 |
| POST /api/learning-feedbacks | 实际agentId/type/rating/summary/action，可选taskId/traceId；必需requestKey |
| POST /api/learning-feedbacks/{id}/status | status与expectedStatus比较并更新；终态不可覆盖 |
| GET /api/agent-experiments/targets?agentId=… | 已纳入范围的托管实例与实验开关 |
| POST /api/agent-experiments/profile | agentId、instanceKey；读取认证基线及发布版本 |
| POST /api/agent-experiments | profileDigest、candidatePrompt、maxTokens、samples、publicSamplesConfirmed、requestKey |
| GET /api/agent-experiments 及 /{id} | 最近50个实验及单项元数据 |
| GET /api/agent-experiments/{id}/content | 提示词、样本和新输出，需内容权限 |

反馈幂等范围为 agentId+requestKey；实验requestKey全局唯一。相同键须保持payload与登录主体一致；改参冲突。前端双击只提交一次，未知提交保留原键与载荷，不能通过重新生成键“重试”。登录身份改变时清除原私有草稿与结果。

## 远程验收与结论

- 远程后端377/377、82套、0失败/跳过；前端162/162、vue-tsc及生产构建通过；Runner竞态13项顶层/18含子用例通过，其中4项新增实验回归。
- 四角色真实 JWT接口与浏览器通过；1500px/390px、内容403、前端capabilities、反馈防双击、身份竞态和未知载荷保持通过。
- 新Agent43、实验1、3组公开样本，严格6次实际模型调用，6个Trace与网关记录一致。重复提交返回原实验、没有增加调用。真实表单新增1条反馈，治理主体评审采纳，采纳前后运行配置相同。
- 基线3/3，input160/output110，共270Token，平均Runner901ms；候选3/3，input223/output132，共355Token，平均693ms。样本量很小、候选Token更高，不据此宣称优化收益、SLO或费用下降。
- v2配置式发布仅验基线、额外模型调用0。两个验收actor最终均SUSPENDED。MySQL迁移V56/V57实际应用，DB最新57。
- 实验开关EAP_EXPERIMENT_ENABLED=true；新默认Runner固定d0f49d3f…，backend0b4b5834…，frontend2cf47b7d…，buildId=2026-10-02T04:00:00.729Z。完整镜像摘要及截图位于K8Substrate artifacts/eap-agent-tuning-20261002/。
- 归档SHA256：ad517b510d025cc0ef6730e7ec3836b815acf086f6d01ac59d65b1d22ab73fb3。45份业务/测试源码摘要与远程实测源一致，可审查t1-source.patch。首次权限契约、前端类型及节点CGO环境问题已修正，过程失败未当作通过。

## 后续

T2先做所有真实调用共享的已用+在途预留预算和安全准入，再T3受限GEPA搜索与独立留出集；不能把六调用边界当成金额硬保证。工具效果合同、身份配送/续期、旧模板迁移、认证持久化及长任务恢复仍保留。既有模板、快照、完成/pending/crash标记不得清除；Keycloak仍emptyDir，不可替换空PVC直接重启。
