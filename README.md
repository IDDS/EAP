# Enterprise Agent Platform（EAP）

Enterprise Agent Platform（EAP）是面向企业智能体的管理控制平面，覆盖智能体生命周期、运行观测、治理策略、伦理安全和审计追踪。

当前版本已加入 gensee-crate 风格的多智能体运行安全控制能力：

- 安全会话：记录多智能体协作目标和根智能体。
- 运行事件：接收工具调用、数据访问、制品写入、智能体委托等事件。
- 策略决策：输出 `ALLOW`、`ASK`、`DENY` 三态决策。
- fail-closed：生产、特权、密钥、敏感数据等风险默认拦截。
- 溯源审计：保存事件、决策、控制动作和制品 URI。
- Sidecar 预留：可通过 `EAP_GENSEE_SIDECAR_ENABLED` 和 `EAP_GENSEE_SIDECAR_URL` 切换到真实 gensee sidecar 委托模式。

同时参考 AgentMesh 和 AgentScope 补充了多智能体团队编排能力：

- Team：定义多 Agent 团队、协作模式和工作区策略。
- Member：登记 Agent 在团队中的角色和责任。
- Plan：围绕目标创建任务计划。
- Step：按 required capability 拆分步骤，并分配到具体 Agent。
- 与注册发现衔接：步骤声明能力后，可进一步通过 Agent Discovery 自动选择候选 Agent。
- 与安全网关衔接：步骤执行前应提交安全事件，进入 `ALLOW/ASK/DENY` 决策链路。

当前前端已提供拖拽式智能体工作流构建器：

- 左侧智能体/系统节点面板：支持把 Agent、开始、决策、制品、结束节点拖入画布。
- 中间画布：支持节点拖动定位、点击节点建立连线。
- 右侧属性面板：维护节点名称、类型、能力声明和 JSON 配置。
- 后端持久化：工作流、节点、边统一落库，后续可接入计划编排和安全网关执行。
- 受控执行：第一版提供模拟执行器，按工作流边顺序遍历节点，并把每个节点提交到安全网关审计；真实业务工具调用需要在后续执行适配器中接入。

当前已加入第一版自主智能体发现：

- 后台定时扫描本机运行进程、项目清单和常见 Agent/MCP 配置目录。
- 识别 Codex、Claude Code、Cursor、Gemini CLI、Mastra、LangGraph、CrewAI、AutoGen、Semantic Kernel 等信号。
- 发现结果进入独立候选资产层，不会未经复核直接写入正式 `agents` 表。
- 每次发现保留来源、位置、证据摘要、首次/最后发现时间、置信度和风险等级。
- 只读取配置和清单，不执行配置文件中的 MCP 命令。
- 可通过 `EAP_DISCOVERY_ENABLED`、`EAP_DISCOVERY_SCAN_ROOTS` 和扫描周期参数控制范围与频率。

协议级发现已经接入候选资产层：

- A2A：探测标准 `/.well-known/agent-card.json`，兼容旧版 `/.well-known/agent.json`，登记 Agent Card 中的名称、版本、能力、技能、流式能力和认证声明。
- MCP：支持官方 Registry 的 `/v0.1/servers`，以及 `server.json` / `/.well-known/mcp/*.json` 元数据，登记包、远程传输和仓库等证据；不会安装、启动或执行 MCP 包中的命令。
- `AUTO` 会按顺序尝试 A2A、MCP Server Metadata 和 MCP Registry；所有响应都有大小限制、超时和禁止重定向控制。
- 手动接口为 `POST /api/discovery/protocol/probe`，请求体：`{"targetUrl":"https://agent.example.com","discoveryType":"AUTO"}`。也可以通过 `EAP_DISCOVERY_PROTOCOL_TARGETS` 配置定时探测目标。
- 出于 SSRF 防护，私有、回环、链路本地和云元数据地址默认拒绝；仅在受控内网部署时显式设置 `EAP_DISCOVERY_PROTOCOL_ALLOW_PRIVATE_NETWORKS=true`。

该层解决“已知地址的协议自描述发现”。要做到无需预先配置目标、覆盖 Kubernetes、服务网格和主机上所有 Agent，还需要后续接入 Kubernetes Watch/Admission、eBPF 网络证据和跨源身份图谱。

## 技术栈

- 后端：Java 21 LTS、Spring Boot 3.5、Spring Data JPA、Spring Security、Flyway、MySQL。
- 前端：Vue 3、TypeScript、Vite、Pinia、Element Plus、Axios。
- 数据库：MySQL 8.4。
 - 安全集成：gensee-crate compatible safety gateway。

### 生产认证与权限

本地开发默认使用 `EAP_SECURITY_MODE=DEVELOPMENT`，保持演示环境免登录。生产环境必须切换到 JWT Resource Server：

```bash
EAP_SECURITY_MODE=JWT
EAP_SECURITY_ISSUER_URI='https://idp.example.com/realms/eap'
EAP_SECURITY_AUDIENCE='eap-api'
EAP_SECURITY_ROLES_CLAIM='roles'
EAP_SECURITY_ALLOWED_ORIGINS='https://eap.example.com'
```

JWT 中配置的角色 Claim（支持 `realm_access.roles` 等点分嵌套路径）、`scope` 或 `scp` 会以大小写无关方式映射为 EAP 角色：`VIEWER`、`OPERATOR`、`GOVERNANCE_ADMIN`、`PLATFORM_ADMIN`。采集器和 OTLP 不使用用户 JWT，而使用各自的 `X-EAP-*-Token` 机器凭证；JWT 模式下未配置机器凭证的上报请求会被拒绝。浏览器 Token 只保存在当前标签页的 `sessionStorage` 中，不会写入前端构建产物或持久化到服务器。

## 目录

```text
backend/   Spring Boot 后端
frontend/  Vue 管理端
docs/      产品与技术设计
需求/      原始需求材料
```

## 本地启动

启动数据库：

```bash
docker compose up -d mysql
```

启动后端：

```bash
cd backend
mvn spring-boot:run
```

如果本机暂时没有 MySQL 或 Docker，可以使用本地内存数据库启动：

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

访问：

- 前端：http://localhost:5173
- 后端健康检查：http://localhost:8080/actuator/health
- API 文档：http://localhost:8080/swagger-ui

## 关键接口

- `GET /api/tasks`：运行任务列表。
- `GET /api/learning-feedbacks`：自适应学习反馈。
- `GET /api/safety/summary`：多智能体安全摘要。
- `POST /api/safety/sessions`：创建安全会话。
- `POST /api/safety/events/evaluate`：提交运行事件并获得安全决策。
- `GET /api/orchestration/teams`：团队列表。
- `POST /api/orchestration/teams`：创建团队。
- `GET /api/orchestration/teams/{teamId}/members`：团队成员。
- `POST /api/orchestration/plans`：创建任务计划。
- `GET /api/orchestration/plans/{planId}`：计划详情与步骤。
- `POST /api/orchestration/plans/{planId}/steps`：添加计划步骤。
- `GET /api/workflows`：工作流定义列表。
- `POST /api/workflows`：创建工作流。
- `GET /api/workflows/{workflowId}`：工作流详情，包含节点和连线。
- `POST /api/workflows/{workflowId}/nodes`：新增或更新画布节点。
- `POST /api/workflows/{workflowId}/edges`：新增节点连线。
- `POST /api/workflows/{workflowId}/runs`：执行一次工作流，返回节点执行状态和安全决策。
- `GET /api/discovery/summary`：自主发现摘要。
- `GET /api/discovery/agents`：候选 Agent 资产清单。
- `GET /api/discovery/runs`：最近发现扫描记录。
- `GET /api/discovery/endpoints`：已注册的端点采集器清单。
- `GET /api/discovery/identity-links`：跨来源稳定身份键、关联方式、置信度和来源类型。
- `POST /api/discovery/scans`：立即触发一次本地发现扫描。
- `POST /api/discovery/agents/{id}/status`：更新候选资产的复核/纳管状态。
- `POST /api/discovery/endpoints/register`：注册或更新端点采集器。
- `POST /api/discovery/endpoints/observations`：批量上报端点发现观察结果。
- `POST /api/discovery/protocol/probe`：从 A2A Agent Card、MCP Registry 或 MCP Server 元数据自动发现并登记候选资产。
- `POST /api/discovery/connectors/kubernetes/watch`：接收 Kubernetes Watch 的 `ADDED/MODIFIED/DELETED` 事件，提取 Pod/Deployment 容器、镜像、框架标签、端口和安全上下文。
- `POST /api/discovery/connectors/kubernetes/admission`：接收 Kubernetes AdmissionReview 的 `request.object`，在工作负载落地前记录 Agent/MCP 候选及特权、hostNetwork 等风险。
- `POST /api/discovery/connectors/ebpf/observations`：接收主机 eBPF 网络探针归一化的进程—远端服务流，识别模型供应商、模型、MCP 和 Agent 运行时。
- `GET /api/telemetry/physical-summary?hours=24`：物理界面 Token、费用、延迟、成功率和运行状况汇总。
- `GET /api/telemetry/model-invocations?limit=100`：最近模型调用遥测事件。
- `POST /api/telemetry/model-invocations`：上报一次模型调用元数据；生产环境使用 `X-EAP-Telemetry-Token`。
- `GET /api/observability/status`：查看深度内容观测是否启用、采集模式和输入/输出数量。
- `GET /api/observability/content?limit=100`：查看与模型调用 trace 关联的输入/输出观测。
- `POST /api/observability/content`：由受控代理、SDK 插件或外部探针上报内容观测；生产环境使用 `X-EAP-Observability-Token`。
- `POST /api/observability/otlp/v1/traces`：接收 OTLP/HTTP JSON Trace，自动识别 GenAI Span、登记工作负载端点并归一化模型调用与内容观测。
- `GET /api/observability/otlp/spans?limit=100`：查看模型、工具、MCP、RAG、A2A 和 Agent 深度 Span 摘要。
- `GET /api/knowledge/summary?hours=24`：知识输入、过程、输出、采用率和复用率汇总。
- `GET /api/knowledge/interactions?limit=100`：知识交互及过程步骤明细。
- `POST /api/knowledge/interactions`：上报一次知识交互和过程步骤；生产环境使用 `X-EAP-Knowledge-Token`。
- `POST /api/knowledge/interactions/{id}/outcome`：补充知识采用、复用和质量结果。
- `GET /api/management/summary?hours=24`：管理界面投入、确认业务价值、净收益、ROI、任务成功率以及 Agent 级指标。
- `GET /api/management/value-events?limit=100`：查看已登记的业务价值事件。
- `POST /api/management/value-events`：登记由业务方确认的价值事件；生产环境使用 `X-EAP-Management-Token`。
- `GET /api/governance/data-summary?hours=24`：治理数据进入模型、公司保密信息、阻断/脱敏和按 Agent/端点汇总。
- `GET /api/governance/data-events?limit=100`：查看治理分类事件和策略动作。
- `POST /api/governance/data-events`：由模型网关、DLP 或 Agent Runtime 上报治理元数据；生产环境使用 `X-EAP-Governance-Token`。
- `POST /api/governance/preflight`：模型请求发送前的实时治理预检，返回网关必须执行的 `gatewayDirective`。
- `GET /api/governance/preflight-decisions?limit=100`：查看最近预检、命中策略和决策依据。
- `POST /api/governance/preflight-decisions/{decisionKey}/approve`：审批通过待审批模型请求，记录审批人和原因。
- `POST /api/governance/preflight-decisions/{decisionKey}/reject`：拒绝待审批模型请求并转为阻断。
- `GET /api/governance/enforcement-status`：查看强制执行、保密信息 fail-closed 和累计决策状态。
- `GET /api/gateway/status`：查看 OpenAI-compatible 治理模型网关是否启用及配置就绪状态。
- `POST /api/gateway/v1/chat/completions`：统一模型调用入口；执行输入预检、上游调用、输出预检及遥测回传。
- `GET /api/gateway/v1/models`：通过治理网关查看上游 OpenAI-compatible 模型列表。

自主发现配置示例：

```bash
EAP_DISCOVERY_ENABLED=true \
EAP_DISCOVERY_SCAN_ROOTS=/srv/agents,/opt/company/workspaces \
EAP_DISCOVERY_INTERVAL_MS=300000 \
EAP_DISCOVERY_INCLUDE_KNOWN_USER_LOCATIONS=true
```

协议发现配置示例：

```bash
EAP_DISCOVERY_PROTOCOL_ENABLED=true
EAP_DISCOVERY_PROTOCOL_TARGETS=https://agents.example.com,https://registry.example.com
EAP_DISCOVERY_PROTOCOL_INTERVAL_MS=300000
EAP_DISCOVERY_PROTOCOL_ALLOW_PRIVATE_NETWORKS=false
```

Kubernetes Watch/Admission 与 eBPF 连接器使用同一个 `EAP_DISCOVERY_INGESTION_TOKEN`，请求头为 `X-EAP-Discovery-Token`。连接器只接收元数据和网络流摘要：Kubernetes 环境变量只保存变量名，不保存值；eBPF 只保存进程、地址、端口、TLS SNI、模型/供应商和字节数，不保存 Prompt、响应正文或密钥。

跨来源关联优先使用采集器提供的稳定 `eap.ai/identity`（Kubernetes label/annotation）或 eBPF `identityKey`；未提供时，EAP 根据 Agent URL、工作负载 UID、可执行路径、远端服务和规范化产品特征生成哈希身份键。`EXACT` 关联会合并到同一候选资产，`HEURISTIC` 关联保留为可复核的身份图谱边。

```bash
EAP_DISCOVERY_KUBERNETES_ENABLED=true
EAP_DISCOVERY_KUBERNETES_MAX_PAYLOAD_BYTES=1048576
EAP_DISCOVERY_KUBERNETES_MAX_CONTAINERS=256
EAP_DISCOVERY_EBPF_ENABLED=true
EAP_DISCOVERY_EBPF_MAX_FLOWS=1000
```

部署时可使用 Kubernetes Informer/Watch、Admission Webhook 或 Tetragon/eBPF Collector 作为外部采集器；EAP 端不需要修改 Agent 代码。Admission 连接器当前是“观察型”接收器，不会自动修改或阻断 Kubernetes 请求，策略决策将在后续治理控制面实现。

实现依据：A2A 官方 Agent Card 发现规范为 [`/.well-known/agent-card.json`](https://a2a-protocol.org/latest/topics/agent-discovery/)，MCP 官方 Registry API 使用 [`/v0.1/servers`](https://github.com/modelcontextprotocol/registry/blob/main/docs/reference/api/openapi.yaml)。

端点采集与中央汇聚：

- 后端本机扫描适合开发机和单机试用；要发现其他电脑、服务器或容器，需要在目标端点部署采集器。
- 采集器先调用 `/api/discovery/endpoints/register`，再调用 `/api/discovery/endpoints/observations` 批量上报经过脱敏的证据。
- 生产环境建议设置 `EAP_DISCOVERY_INGESTION_TOKEN`，采集请求通过 `X-EAP-Discovery-Token` 传递；未配置时仅适合本地开发。
- 服务端会把端点上报转换为统一的候选资产和 `ENDPOINT` 类型扫描记录，并按端点命名空间化指纹，避免多台机器相互覆盖。

示例配置：

```bash
EAP_DISCOVERY_INGESTION_TOKEN='change-me-in-production'
```

模型遥测上报凭证：

```bash
EAP_TELEMETRY_INGESTION_TOKEN='change-me-in-production'
```

模型遥测表只保存调用元数据：供应商、模型、输入/输出 Token、缓存/推理 Token、延迟、费用、工具/MCP 数量和结果状态。提示词和输出由独立的深度内容观测模块按 `OFF`、`HASH_ONLY` 或 `REDACTED` 策略处理，避免把正文与普通运营指标混存。

知识交互上报凭证：

```bash
EAP_KNOWLEDGE_INGESTION_TOKEN='change-me-in-production'
```

知识交互只保存输入/输出脱敏摘要、知识引用标识和过程步骤元数据；服务端会对明显的 API Key、Token、Secret、Password 形式进行脱敏。

管理价值上报凭证：

```bash
EAP_MANAGEMENT_INGESTION_TOKEN='change-me-in-production'
```

ROI 只使用模型遥测中的真实费用和管理价值事件中的确认金额计算：`ROI = (确认业务价值 - 模型投入) / 模型投入`。没有确认价值事件时，接口返回 `hasValidatedValue=false`，前端显示“—”，不会用估算或模拟收益填充。

治理数据上报凭证：

```bash
EAP_GOVERNANCE_INGESTION_TOKEN='change-me-in-production'
EAP_GOVERNANCE_APPROVAL_TOKEN='separate-approval-key-in-production'
```

治理事件只允许上报 Agent/端点归因、数据方向、数据类别、敏感级别、内部/保密标记、Token/字节数和策略动作；接口没有提示词、输出正文或文件内容字段。生产环境应在模型网关或 DLP 层完成分类后上报，并通过 `BLOCK`、`REDACT` 或 `APPROVAL_REQUIRED` 记录处置结果。

实时强制治理默认启用，`SECRET` 和 `RESTRICTED` 即使没有策略命中也会 fail-closed。可显式配置：

```bash
EAP_GOVERNANCE_ENFORCEMENT_ENABLED=true
EAP_GOVERNANCE_FAIL_CLOSED_ON_SECRET=true
```

模型网关必须在调用供应商模型前调用 `/api/governance/preflight`，并遵守返回指令：`PROCEED` 可继续、`PROCEED_WITH_WARNING` 可继续并告警、`REDACT_AND_RECHECK` 必须脱敏后重新预检、`REQUEST_APPROVAL` 必须等待审批、`BLOCK` 必须停止请求。预检接口仍然不接收正文，敏感内容检测由部署在数据路径上的 DLP/分类器完成。

在强制模式下，模型调用遥测还必须携带 `governanceDecisionKey`。后端会校验决策未过期（默认 300 秒）、Agent/端点归属、Provider 和 Model 是否与预检一致；阻断、待审批、待脱敏或不存在的决策会拒绝模型调用遥测，防止绕过预检直接调用模型。可调整有效期：

```bash
EAP_TELEMETRY_REQUIRE_GOVERNANCE_PREFLIGHT=true
EAP_TELEMETRY_GOVERNANCE_DECISION_TTL_SECONDS=300
```

## OpenAI-compatible 治理模型网关

生产 Agent 可以把 Chat Completions Base URL 指向 EAP，而不是直接访问模型供应商。网关默认关闭；只有显式配置上游后才会转发：

```bash
EAP_GATEWAY_ENABLED=true
EAP_GATEWAY_UPSTREAM_BASE_URL=https://api.openai.com
EAP_GATEWAY_UPSTREAM_API_KEY='upstream-provider-key'
EAP_GATEWAY_INGESTION_TOKEN='agent-facing-gateway-key'
EAP_GATEWAY_PROVIDER=openai
EAP_GATEWAY_AUTO_ATTRIBUTION_ENABLED=true
EAP_GATEWAY_MAX_RESPONSE_BYTES=4194304
EAP_GATEWAY_INPUT_COST_PER_1K_TOKENS=0
EAP_GATEWAY_OUTPUT_COST_PER_1K_TOKENS=0
```

客户端访问凭证默认 fail-closed：未设置 `EAP_GATEWAY_INGESTION_TOKEN` 时网关即使有上游地址也不会接收调用。本地隔离环境如确实需要无鉴权调试，必须显式设置 `EAP_GATEWAY_ALLOW_UNAUTHENTICATED=true`，生产环境不得使用。

调用示例：

```bash
curl http://localhost:8080/api/gateway/v1/chat/completions \
  -H 'Authorization: Bearer agent-facing-gateway-key' \
  -H 'Content-Type: application/json' \
  -H 'X-EAP-Agent-Id: 1' \
  -d '{"model":"your-model","messages":[{"role":"user","content":"生成本周摘要"}]}'
```

也可以使用 `X-EAP-Gateway-Token` 传递网关凭证。显式携带 `X-EAP-Agent-Id` 或已注册的 `X-EAP-Endpoint-Key` 可以获得最精确归属；未携带时，默认按来源网络地址生成 `auto-network-*` 端点归属，后续可与自主发现的主机、容器或 Pod 资产自动合并。可选 `X-EAP-Task-Id` 和 `X-EAP-Trace-Id` 用于业务归因；没有 Trace ID 时由 EAP 自动生成。DLP/分类器可通过以下请求头提交高可信标签：

- `X-EAP-Data-Category`
- `X-EAP-Sensitivity: PUBLIC|INTERNAL|CONFIDENTIAL|SECRET|RESTRICTED`
- `X-EAP-Internal-Data: true|false`
- `X-EAP-Company-Secret: true|false`
- `X-EAP-Detected-Types: PII,CREDENTIAL,SOURCE_CODE`

网关对输入和输出分别执行治理预检；`BLOCK` 和 `REQUEST_APPROVAL` 不会转发，`REDACT_AND_RECHECK` 会先做基础凭证、邮箱和手机号脱敏后重新预检。`stream=true` 使用“完整缓冲上游 SSE → 输出治理 → 原样返回 SSE”的安全模式，并受 `EAP_GATEWAY_MAX_RESPONSE_BYTES` 限制；这会增加首字节延迟，但不会把未经治理的增量输出直接暴露给调用方。响应头包含输入、输出治理决策 Key 和 Trace ID。是否保存内容由下述深度观测模式单独控制。

## 深度内容观测

EAP 网关会自动把输入、输出和模型调用事件绑定到同一个 `eventKey`、Trace ID、Agent/端点、Provider 和 Model。独立探针也可通过 `/api/observability/content` 上报同样的数据模型。

```bash
EAP_OBSERVABILITY_ENABLED=true
EAP_OBSERVABILITY_CAPTURE_MODE=HASH_ONLY
EAP_OBSERVABILITY_MAX_CONTENT_BYTES=65536
EAP_OBSERVABILITY_INGESTION_TOKEN='change-me-in-production'
```

- `OFF`：完全关闭正文观测。
- `HASH_ONLY`：安全默认值，只保存 SHA-256、字节数、Token 估算、敏感级别、治理动作和 trace 关联，不保存正文。
- `REDACTED`：只保存经过治理网关或 DLP 脱敏后的安全版本；原始内容只在内存中用于计算哈希，不落库。被 `BLOCK` 或 `REQUEST_APPROVAL` 拒绝且没有安全版本的正文也不会落库。

物理界面的“深度内容观测”面板会显示 INPUT/OUTPUT、模型、trace、治理动作和可用的脱敏内容。生产环境应对读取接口增加组织级 RBAC、审计和保留期策略，并优先使用 `HASH_ONLY`。

当前版本可通过统一网关做到业务代码低侵入，并已支持无请求头时的网络来源自动归属。产品目标是由主机探针、Kubernetes DaemonSet/Service Mesh 或企业模型出口代理完成一次性透明接入，使单个智能体无需修改代码、Endpoint 或 Trace 配置。HTTPS 正文观测必须在企业受控 TLS 终止点或受信运行时完成；普通 eBPF/网络抓包只能可靠获得连接元数据，不能绕过加密读取 Prompt/Output。

### OTLP / OpenTelemetry 被动接入

EAP 支持 OTLP/HTTP JSON 的 `resourceSpans`，兼容当前 OpenTelemetry GenAI 属性 `gen_ai.provider.name`、`gen_ai.request.model`、`gen_ai.response.model`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`、`gen_ai.input.messages`、`gen_ai.output.messages`，并兼容旧版 `gen_ai.system` 与常见 OpenLLMetry `llm.*` 属性。

可将企业 OpenTelemetry Collector 的 `otlphttp` traces exporter 基础地址指向（Collector 会自动追加 `/v1/traces`）：

```yaml
exporters:
  otlphttp/eap:
    endpoint: http://eap.example.com:8080/api/observability/otlp
    headers:
      X-EAP-Observability-Token: ${env:EAP_OBSERVABILITY_INGESTION_TOKEN}
```

直接发送 OTLP/HTTP JSON 时，完整入口为 `http://eap.example.com:8080/api/observability/otlp/v1/traces`。

同一入口同时接受 OTLP/HTTP Protobuf（`Content-Type: application/x-protobuf`），并默认启动标准 OTLP TraceService gRPC 端口 `4317`。gRPC 客户端通过 metadata `x-eap-observability-token` 传递采集凭证：

```bash
EAP_OTLP_GRPC_ENABLED=true
EAP_OTLP_GRPC_PORT=4317
EAP_OTLP_GRPC_MAX_INBOUND_MESSAGE_BYTES=4194304
```

EAP 不仅记录 GenAI 模型 Span，还会被动分类并持久化 `MODEL`、`TOOL`、`MCP`、`RAG`、`A2A`、`AGENT` Span，保存父子 Span 关系、工具名、MCP Server、RAG 来源、耗时、状态和脱敏属性摘要。工具参数、MCP 请求、RAG 查询/文档和 A2A 消息会复用深度观测模式处理；正文不会进入普通 Span 属性表。控制台或审计系统可通过 `GET /api/observability/otlp/spans?limit=100` 查看最近深度 Span。

如配置了 `EAP_OBSERVABILITY_INGESTION_TOKEN`，Collector 需要增加请求头 `X-EAP-Observability-Token`。EAP 会根据 `service.instance.id`、`k8s.pod.uid`、`container.id`、`host.id` 等资源属性自动生成稳定端点，不要求应用提供 EAP Agent ID。OTLP 观察到的调用会标记为 `OTLP / 被动发现` 和 `governanceCovered=false`，避免把“已经发生但未经过治理网关”的流量误报为已治理。

审批重试流程：收到 `REQUEST_APPROVAL` 后，客户端保存响应头或响应体中的输入决策 Key；治理控制台或审批系统调用 `.../{decisionKey}/approve`（生产环境另带 `X-EAP-Governance-Approval-Token`），然后客户端使用完全相同的请求正文、Agent/端点归属、Provider/Model，并增加 `X-EAP-Governance-Decision-Key: {decisionKey}` 重试。EAP 会校验审批状态和 SHA-256 请求指纹，正文、模型或归属任一变化都会要求重新预检；普通 `PROCEED` 决策不能冒充审批凭证。审批默认受遥测治理决策 TTL 约束，过期后必须重新预检。

运行轻量端点采集器（仅使用 Python 标准库，当前版本 0.2.0）：

```bash
EAP_DISCOVERY_SERVER_URL=http://eap.example.com:8080/api/discovery \
EAP_DISCOVERY_INGESTION_TOKEN='change-me-in-production' \
EAP_DISCOVERY_ENDPOINT_KEY='developer-mac-001' \
python3 scripts/eap_discovery_collector.py
```

持续运行可以增加 `--interval-seconds 300`；上线前可使用 `--dry-run` 检查将要上报的脱敏观察结果。采集器不会执行 MCP 配置命令，也不会上传配置文件原文、命令行参数或凭证值。

采集器和后端扫描器现在还会发现本地 Agent Skill（`skills/**/SKILL.md`）与 Codex/Claude 插件（`.codex-plugin/plugin.json`、`.claude-plugin/plugin.json`），并将其作为独立的 `AGENT_SKILL` / `AGENT_PLUGIN` 资产记录。宿主特征覆盖 Codex、Claude Code、Cursor、Windsurf、Kiro、OpenCode、Amp、Amazon Q Developer、Antigravity、OpenClaw、Cline、Roo Code、Gemini CLI、GitHub Copilot，以及 Mastra、LangGraph、CrewAI、AutoGen、Semantic Kernel、OpenAI Agents SDK 等框架。Windows 采集器使用 PowerShell CIM 读取进程元数据；任何平台都不会执行 MCP 命令或上传技能正文。

供应链静态信号包括提示注入、工具投毒、工具影子、恶意载荷、凭证引用和凭证-外联毒性流；命中文件只上传信号布尔值，不上传正文。Microsoft Defender 的 AgentsInfo/Exposure Graph 导出可通过 `POST /api/discovery/connectors/microsoft-defender/ingest` 导入，适配器会统一归并设备、账户、进程、MCP、暴露图和风险字段。该适配器不要求 EAP 直接持有 Microsoft 凭据，生产环境应由受控同步任务调用并配置 `EAP_DISCOVERY_INGESTION_TOKEN`。

如果需要使用 Snyk Agent Scan 的完整 MCP 工具描述分析，应在隔离沙箱中运行其 CLI，再将脱敏 JSON 结果接入 EAP；Snyk 文档明确提示扫描 stdio MCP 配置可能执行配置中的命令，因此 EAP 内置扫描保持被动读取模式，不会隐式执行第三方 MCP。
