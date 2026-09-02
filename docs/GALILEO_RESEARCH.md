# Galileo / Splunk Agent Observability 借鉴研究与 EAP 演进建议

研究日期：2026-09-02
研究范围：Galileo.ai 当前产品、公开技术文档、公开 SDK，以及 EAP 当前代码和项目记忆。
结论性质：本文将“事实”“厂商声明”“EAP 设计判断”和“待验证假设”分开；Galileo 的性能、准确率和成本数字均不视为独立验证结果。

## 结论先行

Galileo 值得借鉴，但不建议 EAP 现阶段把它作为核心依赖或简单“集成一个外部 APM”。更合适的路线是：

> 把 Galileo 的“评估工程 → 生产评估 → 异常信号 → 运行时护栏 → 人工反馈调优”闭环，内生为 EAP 的评估与控制子系统；把 Galileo/Splunk 保留为可选的 OTLP/评分交换适配器。

原因有三点：

1. EAP 已经具备 Agent 资产发现、OTLP/Trace、模型和工具归因、内容治理、`ALLOW/ASK/DENY` 控制、知识度量和 ROI 管理。缺口不是“再做一个观测平台”，而是缺少正式的 evaluator（评估器）生命周期，以及“评估结果如何进入策略和行动”的闭环。
2. Galileo 的公开资料最有价值的不是某一个指标或 Luna 模型，而是把离线测试、生产数据、SME 标注、LLM-as-a-judge/代码评估器、实验回归、在线护栏放进同一个对象模型和工作流。
3. Galileo 的控制服务、Luna 模型和云端后端属于商业产品。公开的 `galileo-python`、`galileo-js` 主要是客户端 SDK，并不等于可嵌入的评估/控制引擎；公开仓库中的 `protect` 已标记为 deprecated，不应直接作为 EAP 的基础依赖。

建议采用以下优先级：

| 能力 | 借鉴/自研建议 | 近期价值 |
| --- | --- | --- |
| Eval Engineering 生命周期 | EAP 原生实现 | 最高 |
| GenAI OpenTelemetry / OpenInference 语义 | EAP 兼容并扩展 | 最高 |
| Agent/RAG/Tool/Safety 指标体系 | EAP 原生实现，先规则后模型 | 最高 |
| 评估结果驱动的运行时控制 | 接入现有治理网关和控制 outbox | 高 |
| Signals 式未知问题发现 | EAP 原生实现 | 高 |
| AI Assistant 式证据链调查 | 作为分析层，默认只建议不执行 | 中高 |
| Luna 类专用小模型 | 先做可替换的本地 SLM/规则评分器 | 中 |
| Galileo 直接 SDK/云端绑定 | 只做可选适配器 | 低到中 |

### 关键判断、可能性与置信度

- **判断 1：EAP 内生实现 Galileo 类评估闭环将明显提升产品价值。** 可能性：Likely；置信度：High。依据是 EAP 已有 Trace、治理、控制和 ROI 基础，而 Galileo 的公开技术路径正好补足 evaluator、实验和反馈闭环；这是架构匹配判断，不是对客户收入的保证。
- **判断 2：直接把 Galileo/Splunk 作为 EAP 核心依赖不利于长期产品主线。** 可能性：Likely；置信度：Medium。依据是品牌/产品已发生收购和迁移，公开 SDK 只是客户端，核心 Control/Luna/后端为商业能力；若客户已采购该平台，结论可切换为“保留双平台适配”。
- **判断 3：先规则与轻量模型、后 LLM judge，再进入阻断，最可能在成本、延迟和安全之间取得可接受平衡。** 可能性：Likely；置信度：Medium。依据是 EAP 的安全策略需要 fail-closed，而评估器存在误报和模型调用成本；具体阈值必须由 EAP PoC 测得。
- **判断 4：EAP 的差异化应放在企业资产/基础设施/治理/责任/ROI 与评估结果的关联。** 可能性：Likely；置信度：High。依据是这些能力已存在于 EAP，而 Galileo 公开定位主要集中在 GenAI/Agent evaluation、observability 和 protection。

## Galileo 现在是什么

这是一个需要特别注意的时间点。Galileo 官网仍使用 Galileo 品牌并描述自己为“AI Observability and Evaluation Platform”，覆盖 GenAI/Agent 的评估、观测和保护；官网同时强调离线评估可以转化为生产护栏、评估分数可以控制工具访问和升级路径。[Galileo 官网](https://galileo.ai/)

Splunk 在 2026-05-28 的官方公告称 Cisco 已完成对 Galileo Technologies 的收购，Galileo 的能力将并入 Splunk Observability 的 AI Agent Monitoring；公告将其能力概括为多智能体系统的 evaluation、observability 和 real-time protection，并提到 Luna-2 小模型用于低成本、低延迟的持续评估和护栏。[Splunk 收购与产品整合公告](https://www.splunk.com/en_us/blog/leadership/splunk-observability-galileo.html)

官方当前文档已经以 “Splunk Agent Observability” 为主，旧 Galileo 文档只适用于 2026-08-07 之前入驻的客户。[当前 Splunk Agent Observability 文档](https://agent-observability-docs.splunk.com/what-is-splunk-agent-observability) 因此本文把“Galileo”理解为 Galileo 技术体系及其已迁移到 Splunk 的产品能力，而不是一个稳定不变的独立品牌或 API 承诺。

### 公开资料能确认的能力

- **统一追踪模型**：项目 → Log Stream → Session → Trace → Span；Span 可表示 LLM、Tool、Retriever、Workflow 等步骤。[Logging Basics](https://docs.galileo.ai/sdk-api/logging/logging-basics)
- **OpenTelemetry/OpenInference 接入**：官方提供 `GalileoSpanProcessor`，支持直接把 OTLP Trace 发到服务端，也支持跨进程通过 `traceparent` 传播分布式 Trace；OpenInference instrumentor 用于补充 GenAI 语义属性。[OTel/OpenInference 总览](https://docs.galileo.ai/sdk-api/third-party-integrations/opentelemetry-and-openinference)、[分布式追踪](https://docs.galileo.ai/sdk-api/logging/distributed-tracing-otel)、[OTel 集成建议](https://docs.galileo.ai/sdk-api/third-party-integrations/opentelemetry-and-openinference/integration-recommendations)
- **多层次评估器**：既有 LLM-as-a-judge，也有自定义代码指标；评估可作用在 span、trace、session 等不同层级，并返回分数、解释、理由、Token/成本等结果。[自定义代码指标](https://docs.galileo.ai/concepts/metrics/custom-metrics/custom-metrics-ui-code)、[Action Completion](https://docs.galileo.ai/concepts/metrics/agentic/action-completion)
- **Agentic 指标**：Action Completion、Tool Selection Quality、Agent Efficiency、Agent Flow、Reasoning Coherence、Tool Error 等；它们关注“是否完成用户目标、轨迹是否有效、是否选对工具和参数”，而不只是模型延迟。[Tool Selection Quality](https://docs.galileo.ai/concepts/metrics/agentic/tool-selection-quality)、[Action Completion](https://docs.galileo.ai/concepts/metrics/agentic/action-completion)
- **RAG 指标**：Chunk Relevance、Context Relevance/Precision、Context Adherence、Completeness 等，把检索质量、上下文使用和最终回答的依据关系拆开。[Galileo 指标与文档索引](https://docs.galileo.ai/llms.txt)
- **生产信号发现**：Signals 扫描生产 Trace，聚合重复故障、区分新旧模式、按严重性和规模排序，并可把信号转成新的 LLM 评估器。[Signals](https://docs.galileo.ai/concepts/signals)
- **运行时 Agent Control**：在 LLM/工具输入和输出的 pre/post 阶段执行 evaluator，根据 `Deny` 等动作阻断或放行；Control 绑定 Log Stream，可不重新部署而生效。[Agent Control 概览](https://docs.galileo.ai/concepts/agent-control/overview)、[创建 Control](https://docs.galileo.ai/how-to-guides/agent-control/create-a-control)、[监控 Control](https://docs.galileo.ai/how-to-guides/agent-control/monitor-a-control)
- **AI Assistant 式调查**：用自然语言跨 Trace、Span、Session 和评估分数查询，并返回证据链接；官方文档也明确其只提供建议、不自动执行，且可能出错。[AI Assistant](https://docs.galileo.ai/concepts/ai-assistant)
- **评估工程**：离线基准、A/B、回归评估和 CI/CD 质量门；评估资产由 evaluator 模型、评估 Prompt、参考数据集和 SME ground truth 组成，并通过标注反馈调优。[Eval Engineering](https://galileo.ai/evalengineering)
- **Luna 小模型**：官方把 Luna-2 定义为面向大规模评估的专用小语言模型，强调低延迟、低成本和可为自定义指标微调；它是 Enterprise 能力，不是公开可复制的通用开源组件。[Luna](https://docs.galileo.ai/concepts/luna/luna)

### 需要谨慎对待的厂商声明

官网提到 Luna 可覆盖 100% 流量并声称“96% lower cost”，Splunk 公告提到可在 200ms 内拦截有害 Prompt/Output，研究页面还展示了 ChainPoll、RAG 分析和 DEP 等效果数字。这些是产品或研究方的声明，不是本文独立复现的基准。EAP 应建立自己的标注集、成本/延迟预算和回归测试后再决定是否达到相同目标。

## 与 EAP 的能力映射

### EAP 已有、无需重复建设的部分

根据 [EAP 项目记忆](./PROJECT_MEMORY.md) 及当前代码，EAP 已具备：

- Agent、MCP、Skill、Plugin、A2A Runtime 的发现、身份归并、纳管和运行实例管理；
- OTLP/HTTP JSON 与 OTLP gRPC Trace 接入，且已有 GenAI、Tool、MCP、RAG、A2A、Agent Span 归一化；
- 模型调用 Token、成本、延迟、状态，以及 Tool/MCP 调用明细；
- Prompt/Output 的 `OFF`、`HASH_ONLY`、`REDACTED` 观测策略和内容治理；
- `ALLOW`、`ASK`、`DENY` 与 fail-closed 的治理决策、网关预检、审批和审计；
- Agent/Team/Plan/Step 编排、控制命令 outbox、知识采用/复用和业务价值/ROI 归因。

这意味着 EAP 与 Galileo 的重叠区主要在“Trace/Span 观测”和“运行时控制”，而 EAP 的差异化资产在基础设施证据、企业身份、数据治理、组织责任和业务价值。EAP 不应为了模仿 Galileo 而退化成只看 LLM Trace 的工具。

### EAP 当前最值得补齐的部分

1. **没有正式的评估器注册表**：缺少 evaluator 名称、版本、输入/输出 schema、作用层级、阈值、置信度、成本和适用策略的统一对象。
2. **没有数据集/ground truth/实验回归闭环**：目前可观测到生产数据，但还不能把一批 Trace 固化为数据集，进行版本化实验、A/B、CI 质量门和回归比较。
3. **没有 Agent 轨迹级质量分数**：已有 Span 类型和调用明细，但还缺 Action Completion、Tool Selection Quality、Agent Flow、Agent Efficiency、Reasoning Coherence 等可查询、可追踪、可解释的评估结果。
4. **没有 RAG 证据链质量分数**：需要区分 chunk 是否相关、上下文是否充分、回答是否遵循上下文、引用/证据是否覆盖结论。
5. **没有“评估结果 → 策略动作”的标准编译器**：现有控制 outbox 和三态决策是基础，但还需要把 evaluator + 阈值 + 风险级别 + 作用范围编译成 observe/warn/ask/steer/deny 等动作。
6. **没有 Signals 式未知问题发现**：需要从成功率、工具错误、重试、延迟、成本、评估分数和错误文本中聚类新模式，而不是只展示已知字段。
7. **没有证据链接型调查助手**：EAP 可把发现、Trace、治理事件、K8s/主机证据和业务结果串起来，形成比单纯 Trace 问答更适合企业运维的调查视图。
8. **没有持续校准和反馈学习**：SME 对“分数是否合理”的标注应能反哺 evaluator Prompt、阈值、样本和版本，而不是停留在一次性规则配置。

## 建议吸收的核心理念

### 1. 把“模型调用监控”升级为“任务结果评估”

EAP 的最小质量对象不应只是一次 LLM 调用，而应是：

```text
用户目标 → Agent 计划/轨迹 → LLM/Tool/RAG/A2A 步骤 → 最终结果 → 业务确认
```

同一个 Trace 既保留底层 Span，也在 Session/Task 层生成结果评估。这样“调用成功”不再等同于“任务成功”，可以回答：任务是否完成、是否漏掉用户要求、是否走了不必要的工具、是否花费过高、是否需要人工升级。

### 2. 用“评估器即软件制品”的方式管理质量

每个 evaluator 都应像代码或策略一样有版本和测试资产：

```text
Evaluator
  ├─ version / input schema / output schema
  ├─ evaluator type: RULE | CODE | LLM_JUDGE | LOCAL_SLM
  ├─ level: SPAN | TRACE | SESSION | AGENT | WORKFLOW
  ├─ dataset + ground truth + SME annotations
  ├─ threshold / confidence / severity / action
  ├─ calibration: precision, recall, F1 或 RMSE
  └─ runtime budget: p95 latency, cost per 1k evaluations
```

这比在页面上堆更多“质量指标”更重要，因为它保证指标可复现、可回归、可审计，也能解释为什么一次动作被阻断。

### 3. 用分层评分解决成本和延迟

不应一开始就对 100% 请求调用昂贵的 frontier LLM judge。建议 EAP 使用四级流水线：

1. **确定性规则**：PII/密钥、工具参数 schema、超时、重复重试、成本异常、敏感目标访问等，实时执行。
2. **本地轻量评分器**：对高频的注入、毒性、上下文遵循、工具选择等做低成本、高吞吐评分；模型可插拔，不复制 Luna 的专有实现。
3. **LLM judge**：只对低置信度、边界样本、抽样流量和离线实验执行，保存 rationale 和版本。
4. **SME 标注/主动学习**：将人工确认的误报、漏报和未知模式加入测试集，调优 Prompt、阈值或轻量模型。

线上先“observe-only”，确认评估器稳定后再对高风险动作启用 `ASK`/`DENY`。这可以避免把不成熟的模型评分器直接变成生产单点故障。

## 建议的 EAP 原生架构

```text
┌─────────────────────────────────────────────────────────────┐
│ Evidence Plane                                              │
│ OTLP / Gateway / Content Proxy / Discovery / Host Observer   │
└───────────────┬─────────────────────────────────────────────┘
                │ normalized GenAI + Tool + RAG + A2A spans
┌───────────────▼─────────────────────────────────────────────┐
│ Evaluation Plane                                            │
│ Evaluator Registry · Dataset/Ground Truth · Eval Worker     │
│ Span/Trace/Session scoring · Calibration · Experiment/CI    │
└───────────────┬─────────────────────────────────────────────┘
                │ score + confidence + rationale + evidence refs
┌───────────────▼─────────────────────────────────────────────┐
│ Signal & Investigation Plane                                │
│ Drift/baseline · clustering · new/recurring · impact        │
│ Evidence-linked assistant · owner/work item                 │
└───────────────┬─────────────────────────────────────────────┘
                │ compiled predicate
┌───────────────▼─────────────────────────────────────────────┐
│ Control Plane                                               │
│ OBSERVE / WARN / ASK / STEER / DENY · pre/post · selector   │
│ agent/tool/model scope · audit · fail-closed                │
└───────────────┬─────────────────────────────────────────────┘
                │ outcome and human feedback
┌───────────────▼─────────────────────────────────────────────┐
│ Management Plane                                            │
│ success/cost/latency/risk/value/ROI · chargeback             │
└─────────────────────────────────────────────────────────────┘
```

### 建议新增的核心数据对象

- `evaluator_definition` / `evaluator_version`：评估器类型、Prompt/代码引用、输入输出、版本和状态；
- `evaluation_dataset` / `dataset_version` / `ground_truth_label`：脱敏 Trace 样本、期望结果、SME 标注和来源；
- `evaluation_run` / `evaluation_result`：实验或生产评分、层级、分数、置信度、解释、成本、延迟、证据 Span；
- `evaluation_policy`：阈值、严重性、适用 Agent/Tool/Model、pre/post 阶段和动作；
- `signal_cluster` / `signal_occurrence`：模式指纹、首次/最近时间、影响范围、重复率、责任人和关联 Work Item；
- `evaluator_feedback`：人工接受/拒绝、误报/漏报、修订意见及其对应评估器版本。

### 与现有 EAP 控制模型的对应

当前 EAP 的 `ALLOW/ASK/DENY` 可以保留，并增加两个非破坏性动作：

- `OBSERVE`：记录分数和信号，不影响请求；
- `STEER`：要求重新规划、换工具、补充证据、转人工或降低权限。

一个控制规则应至少包含：`evaluator`、`version`、`selector`、`stage=PRE|POST`、`threshold`、`confidence_min`、`severity`、`action`、`scope`、`enforcement_environment` 和 `expiry/rollback`。任何评分器都不能单独决定阻断，必须经过策略、风险等级和置信度的组合判断。

### 已落地的 P0 API

当前代码已提供一个不改变现有控制链路的评估基础：

```text
GET  /api/evaluations/evaluators
POST /api/evaluations/evaluators
GET  /api/evaluations/datasets
POST /api/evaluations/datasets
GET  /api/evaluations/datasets/{datasetKey}/items?limit=
POST /api/evaluations/datasets/{datasetKey}/items
PATCH /api/evaluations/datasets/{datasetKey}/items/{itemKey}/annotation
GET  /api/evaluations/results?agentId=&traceId=&limit=
POST /api/evaluations/results
```

数据集样本接口用于把脱敏 Trace/评估输入、期望和实际结果固化为可回归条目，并记录 SME 的 `PASS`、`FAIL` 或 `ABSTAIN` 标注、分数与理由。它是人工反馈回流评估工程的最小闭环；`OFF` 模式下正文列允许为空，`HASH_ONLY/REDACTED` 仍由服务端统一处理。评估结果另保存执行时 evaluator 版本和请求指纹，避免评估器升级后历史结果被重新解释。

示例：对一次工具调用做参数完整性评分。`input.requiredArguments` 是工具契约，`actual.arguments` 是实际参数：

```json
{
  "resultKey": "trace-001-tool-001-args-v1",
  "evaluatorKey": "TOOL_ARGUMENT_VALIDITY",
  "evaluatorVersion": "v1",
  "agentId": 4,
  "traceId": "trace-001",
  "spanId": "span-001",
  "input": {"requiredArguments": ["accountId", "region"]},
  "actual": {"arguments": {"accountId": "a-1"}}
}
```

结果会保存 `score=0.5`、`passed=false`、缺少参数列表和 Trace/Agent 关联。显式评估 API 仍然保留；当前已经增加持久化异步投影任务，把现有 ToolInvocation、TraceSpan 和 RAG/Knowledge 观测投影为后台评估任务。

### P1/P2 已增加：Tool、Trace 与 RAG 观测投影

EAP 现在提供 `EvaluationProjectionService`，可扫描最近的终态 Tool Invocation：

- 每个成功/失败/未知的工具事件生成一条幂等 `TOOL_STATUS` 结果；
- 如果 OTLP attributes 中存在 `requiredArguments` 和 `arguments`，再生成 `TOOL_ARGUMENT_VALIDITY`；
- `REQUESTED` 中间态会跳过，避免把尚未完成的调用误判为失败；
- 可通过 `POST /api/evaluations/synchronize` 手动触发，也可用 `EAP_EVALUATION_AUTO_SYNC_ENABLED=true` 开启定时投影；
- 该阶段只写评估结果，不自动派发控制命令，便于先校准分数和误报。

这对应 Galileo 的“生产 Trace → evaluator”思路，但 EAP 先选择确定性、低成本、可回滚的 Tool、Trace 和 RAG 证据评估，暂不把生产数据直接发送到第三方平台。投影通过 `eap_evaluation_jobs` 队列和 Worker 执行；原同步接口保留作为兼容与诊断入口。

评估未通过结果还会被 AgentOps 的信号同步消费，生成 `EVALUATION_FAILURE` 运营事项。事项包含 evaluator、版本、分数、置信度、Trace/Span 和理由，可以被认领、转 Work Item 并进入审计；它仍然不会直接修改 Agent 的运行决策。这是从“评分”走向“可执行运营闭环”的中间层。

## 建议的 EAP 评估器目录

### Agent 与任务

- **Action Completion**：是否完成用户的全部目标，并给出可验证的结果；
- **Agent Flow/Trajectory**：步骤顺序是否合理，是否存在循环、越权或无效分支；
- **Agent Efficiency**：完成任务所需的工具次数、Token、延迟和人工升级；
- **Reasoning Coherence**：计划、工具结果和最终回答是否一致（只保存必要的解释，不默认保存思维链）。

### Tool/MCP/A2A

- **Tool Selection Quality**：是否选对工具，参数是否完整、合法、最小权限；
- **Tool Error/Retry**：错误类型、重试次数、是否放大故障；
- **Side-effect Risk**：写操作、外发、删除、权限变更是否经过审批；
- **A2A Handoff Quality**：交接是否携带完整上下文、责任边界和结果确认。

### RAG/知识

- Chunk Relevance、Context Relevance/Precision；
- Context Adherence/Grounding：回答是否由检索证据支持；
- Completeness/Citation Coverage：是否覆盖结论所需证据，引用是否指向真实来源；
- 知识采用率、复用率与任务成功/业务价值的关联。

### 安全与合规

- Prompt Injection、上下文切换、越权指令；
- PII、密钥、凭证和保密信息外泄；
- Unsafe/Toxic 输出、数据外发、工具越权和审批绕过；
- 数据分类、出境/供应商策略、保留期限和责任人是否满足政策。

### 运行与经济性

- p95/p99 延迟、Token/费用异常、缓存命中、模型降级；
- 成本/成功率、每次成功任务成本、人工升级率；
- 评估器自身的延迟、调用成本、拒绝率和误报率。

## 直接集成 Galileo/Splunk 的边界

### 三种落地方式的判断

| 方式 | 技术可行性 | 适合场景 | 建议 |
| --- | --- | --- | --- |
| EAP 原生实现 Galileo 类能力 | 高 | 私有化、数据不出域、需要和 EAP 治理/ROI 深度联动 | 主路线 |
| EAP ↔ Galileo/Splunk OTLP/评分适配器 | 中高 | 客户已采购 Splunk Agent Observability，或需要外部对照 | 可选、异步、默认脱敏 |
| 在 EAP 后端直接嵌入 Galileo SDK/Protect | 低到中 | 仅适合短期实验，不适合作为平台架构 | 不建议 |
| EAP 与外部平台双写、结果对比 | 中 | 评估器校准和采购决策 | PoC 阶段可用 |

### 可行但不应成为主路线的适配器

EAP 已有 OTLP HTTP/gRPC 接入，因此技术上可以增加一个 Galileo/Splunk OTLP exporter 或 score adapter，把 EAP 归一化的 Trace/评估结果发送到客户已有的外部平台。Galileo 官方文档明确支持 OTLP/OpenInference、A2A、LangChain、OpenAI Agents SDK 等接入。[OTel/OpenInference](https://docs.galileo.ai/sdk-api/third-party-integrations/opentelemetry-and-openinference)、[A2A 集成](https://docs.galileo.ai/sdk-api/third-party-integrations/a2a)

适配器应满足：

- EAP 是身份、权限、脱敏和审计的 source of truth；
- 默认只发送结构化 Span、哈希、脱敏字段和评估结果，不发送原始 Prompt/Output；
- 外部平台不可用时不影响 EAP 主链路，采用异步 outbox、重试和熔断；
- 使用客户自己的凭证和数据驻留配置，不把 Galileo API Key 写入 Agent 或后端代码；
- 明确区分“外部平台评分”和“EAP 策略评分”，不要把两者静默合并。

### 不建议直接嵌入公开 SDK

`galileo-python` 和 `galileo-js` 是 Apache-2.0 的客户端 SDK，适合 Agent 应用开发者把调用记录发送到 Galileo；它们不是 Galileo 后端、Agent Control 服务或 Luna 模型的开源实现。[galileo-python](https://github.com/rungalileo/galileo-python)、[galileo-js](https://github.com/rungalileo/galileo-js)

公开的 `protect` 仓库已归档并声明 deprecated，README 指向新的 `galileo` 包；不应把它作为 EAP 后端的长期依赖。[protect 仓库](https://github.com/rungalileo/protect)

### 需要商业合同或单独验证的部分

- Luna 模型的部署方式、权重/API、数据驻留和微调权限；
- Agent Control 的 SLA、私有化版本、真实动作语义和故障模式；
- Galileo/Splunk 当前 API、价格、版本兼容和收购后的产品路线；
- 评估分数是否可导出、是否允许作为第三方策略输入。

这些问题不能从公开 SDK 推断，应在客户采购或 PoC 中逐项确认。

## 推荐实施路线

### P0：评估基础（约 1 周）

1. 定义 `Evaluator / Dataset / DatasetItem / EvalRun / EvalResult / Feedback` 六组对象及 API；
2. 复用现有 TraceSpan、ToolInvocation、ContentObservation 和治理事件；
3. 统一 GenAI/OpenInference 语义属性：operation、provider、model、input/output、token、error、tool、retrieval、session、traceparent；
4. 先实现 5 个确定性评估器：工具参数合法性、工具错误/重试、成本异常、PII/密钥、敏感操作审批；数据集样本和 SME 标注已落地，可直接作为回归集入口。

### P1：任务与知识质量（约 1 周）

1. 增加 Session/Task 聚合；
2. 实现 Action Completion、Tool Selection Quality、Context Adherence、Citation Coverage、Agent Efficiency；
3. 以 `OBSERVE` 方式展示分数、置信度、理由和证据 Span；
4. 支持脱敏 Trace 进入可版本化 Dataset。

### P2：实验、生产评分和回归（约 1–2 周）

1. 增加异步评估队列和 worker；
2. 支持规则评分、本地轻量模型和可配置 LLM judge 三种执行器；
3. 提供实验/A-B/CI 回归 API，记录 evaluator 和 dataset 版本；
4. 建立每个评估器的 F1/precision/recall 或 RMSE、p95 延迟、每千条成本基线。

### P3：Signals 与控制闭环（约 1–2 周）

1. 对评估结果、错误、成本和延迟做基线、漂移和聚类；
2. 生成新/重复/级联问题信号，并关联 Agent、Runtime、Tool、K8s/主机证据和 Work Item；
3. 将高置信度 evaluator 编译为 `OBSERVE/WARN/ASK/STEER/DENY`；
4. 先在低风险策略启用 `ASK`，安全类高置信度规则再逐步启用 `DENY`。

### P4：可选外部平台适配（并行或后置）

实现 Galileo/Splunk OTLP exporter 和外部评分导入，不改变 EAP 原生数据模型。若客户没有 Galileo/Splunk，EAP 仍能完整运行；若客户已有采购，则可把 EAP 的企业资产与外部评估视图关联起来。

## 建议的 PoC 验收标准

不要直接复刻 Galileo 的“96% 降本”或“200ms 拦截”等数字；应在 EAP 自己的脱敏测试集上验证：

- 生成至少 100 条包含正常、工具误用、RAG 无依据、Prompt Injection、PII 和失败重试的合成 Trace；
- 每个 evaluator 都有版本、阈值、证据引用和人工标签；
- 先跑 observe-only，比较 evaluator 与 SME 标注的一致性；
- 记录 p50/p95 延迟、每千条评估成本、队列积压和失败重试；
- 评估器误报导致的 `ASK/DENY` 比例可回滚，任何外部 exporter 故障不影响 Agent 主链路；
- 至少验证一次“Signal → Work Item → Policy → Control outbox → 审计”的闭环；
- 对原始内容默认 `HASH_ONLY` 或 `REDACTED`，验证权限、保留和删除策略。

建议把“是否能稳定用于阻断”作为最后一个门槛，而不是 PoC 的默认前提。

## 主要风险与缓解

| 风险 | 具体表现 | 缓解 |
| --- | --- | --- |
| 评估器偏差 | LLM judge 与业务真实成功不一致 | SME ground truth、版本化数据集、混淆矩阵、observe-only |
| 误阻断 | 正常任务被 PII/注入规则拦截 | 置信度阈值、低风险先 ASK、快速回滚、人工豁免 |
| 延迟/成本 | 每个请求多一次模型判断 | 规则优先、采样、异步、轻量本地模型、边界样本才用 judge |
| 隐私和驻留 | Prompt/Output 进入第三方评估服务 | 默认哈希/脱敏，字段级 selector，租户/区域隔离和出境审批 |
| 语义漂移 | Agent、工具或模型升级后分数失效 | Trace schema 合约、回归集、版本 pin、漂移告警 |
| 供应商锁定 | 策略绑定 Galileo 专有 evaluator/Luna | EAP evaluator 接口、OTLP/OpenInference、外部平台只做 adapter |
| 解释过度 | 把 rationale 当作模型真实思维链 | 只记录可审计证据和摘要，不要求或保存隐藏思维链 |

## 最终建议

EAP 应把 Galileo 的核心思想吸收为一个新的“Evaluation & Reliability”能力域，优先实现：

```text
统一 Trace 语义
→ 评估器/数据集/标注版本化
→ 任务、工具、RAG、安全和成本评分
→ 异步生产评估与 Signals
→ 评分编译为治理策略和控制动作
→ 人工反馈校准并反哺回归集
```

直接 Galileo/Splunk 集成只作为边界适配器，适合已有客户采购或需要外部对比时启用。这样 EAP 得到 Galileo 最有价值的工程方法，同时保留自身在企业资产、基础设施、治理、安全、责任和 ROI 上的产品主线。

## 反向检验与切换条件

为避免“看到一个成熟产品就全盘照搬”，需要保留以下竞争性解释：

1. **外部平台优先的情形**：如果目标客户已经统一采购 Splunk，并且数据驻留、私有化和 EAP 自有 evaluator 不是主要约束，那么先做 OTLP/评分适配器可能比自研完整评估器更快产生价值。
2. **暂缓评估层的情形**：如果 EAP 的 Trace 关联、Session 边界或业务成功标签还不稳定，先增加更多评分器只会制造“精确但不可信”的分数，应先修复证据质量。
3. **不做实时模型评分的情形**：如果本地轻量模型无法达到 SME 基线，或线上延迟/成本超预算，则保留规则实时检查，把 LLM judge 限制在异步抽样和离线回归。

最重要的 kill points（触发重新评估路线）：

- 在代表性脱敏集上，关键安全评估器与 SME 标签的一致性长期低于约定基线；
- `ASK/DENY` 的误报率使业务流程不可用，且无法通过阈值、规则或人工豁免降下来；
- 评估成本或 p95 延迟超过业务预算，异步/采样/本地模型也无法解决；
- 真实客户不需要私有化和 EAP 责任/ROI 关联，且外部平台已覆盖主要采购标准。

这些条件出现时，EAP 应缩小为“证据与策略编排层”，把重评估交给外部平台，而不是继续扩大自研范围。

## 来源与证据等级

### 高可信的产品/技术事实来源

- [Galileo 官网](https://galileo.ai/)：当前产品定位、评估/观测/护栏的公开描述；
- [Galileo 官方文档](https://docs.galileo.ai/what-is-galileo)：旧品牌的产品概念、SDK/API 和能力边界；
- [Galileo OTel/OpenInference 文档](https://docs.galileo.ai/sdk-api/third-party-integrations/opentelemetry-and-openinference)：OTLP、语义属性和集成路径；
- [Agent Control 文档](https://docs.galileo.ai/concepts/agent-control/overview)：运行时控制的 evaluator、阶段和动作模型；
- [Signals 文档](https://docs.galileo.ai/concepts/signals)：生产 Trace 聚类、发现和转评估器思路；
- [Eval Engineering](https://galileo.ai/evalengineering)：数据集、SME ground truth、实验和 CI/CD 方法；
- [公开 GitHub SDK](https://github.com/rungalileo/galileo-python)、[JavaScript SDK](https://github.com/rungalileo/galileo-js)：公开客户端代码的范围和许可证；
- [Splunk 官方收购公告](https://www.splunk.com/en_us/blog/leadership/splunk-observability-galileo.html)：收购和品牌整合时间线。

### EAP 内部证据

- [EAP 项目记忆](./PROJECT_MEMORY.md)；
- 当前后端 `observability`、`governance`、`safety`、`control`、`telemetry` 包和前端对应 API/视图。

### 尚未独立验证的事项

Galileo/Luna 的成本节省、延迟、准确率、100% 流量覆盖、真实私有化能力和收购后 API 稳定性均需要独立 PoC 或商业技术澄清，不能仅依据营销页作产品承诺。
