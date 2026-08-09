# Enterprise Agent Platform（EAP）专业软件设计

## 1. 产品定位

EAP（Enterprise Agent Platform）面向企业内部智能体的全生命周期管理，解决“智能体如何被创建、授权、运行、观测、评估、治理和持续改进”的问题。

平台不是单纯的聊天机器人后台，而是企业级智能体控制平面，重点覆盖：

- 运行模块：智能体注册、发布、运行状态、任务执行、调用链路。
- 自适应学习：知识反馈、效果评估、版本迭代、经验沉淀。
- 自主推理：计划拆解、工具调用、推理策略、执行约束。
- 有效运营：指标看板、任务闭环、成本与质量分析。
- 管理模块：计划、组织、控制。
- 治理模块：权限管理、责任管理、利益管理。
- 伦理模块：隐私管理、安全管理、算法管理。

## 2. 设计原则

- 企业级优先：权限、审计、合规、稳定性先于炫技功能。
- 可演进架构：第一版采用模块化单体，后续可按智能体运行、审计、知识、工具网关拆分服务。
- 领域模型清晰：将智能体、版本、工具、任务、策略、审计、风险事件作为核心实体。
- API 契约稳定：前后端通过 REST API 与统一响应模型解耦。
- 数据可追溯：关键配置、版本发布、运行结果、治理动作全部留痕。
- 安全默认开启：最小权限、敏感数据脱敏、操作审计、策略校验。

## 3. 技术架构

### 3.1 总体架构

```text
Vue 3 + TypeScript 前端
        |
        | REST / JSON
        v
Spring Boot API 服务
        |
        | JPA / Flyway
        v
MySQL 数据库
        |
        +-- 后续扩展：Redis、消息队列、向量库、模型网关、对象存储、OpenTelemetry
```

### 3.2 后端技术栈

- Java 21 LTS：新项目默认运行基线；Java 25 LTS 作为后续升级目标。
- Spring Boot 3.5.x。
- Spring Web：REST API。
- Spring Validation：请求参数校验。
- Spring Data JPA：领域实体持久化。
- Spring Security：认证、授权、后续 RBAC/ABAC 扩展。
- Flyway：数据库版本管理。
- MySQL 8.x：业务数据存储。
- springdoc-openapi：API 文档。
- Actuator：健康检查与运维端点。

### 3.3 前端技术栈

- Vue 3。
- TypeScript。
- Vite。
- Vue Router。
- Pinia。
- Axios。
- Element Plus。
- ECharts。

## 4. 核心领域模型

### 4.1 智能体 Agent

智能体是平台的核心管理对象。

关键字段：

- id：唯一标识。
- code：企业内唯一编码。
- name：智能体名称。
- description：业务说明。
- owner_department：责任部门。
- owner_user：责任人。
- category：业务类别。
- lifecycle_status：DRAFT、REVIEWING、ACTIVE、PAUSED、RETIRED。
- risk_level：LOW、MEDIUM、HIGH、CRITICAL。
- created_at / updated_at。

### 4.2 智能体版本 Agent Version

智能体配置不可直接覆盖生产版本，必须通过版本发布。

关键字段：

- agent_id。
- version_no。
- model_provider / model_name。
- system_prompt。
- reasoning_policy。
- tool_policy。
- knowledge_policy。
- release_status：DRAFT、PUBLISHED、ROLLED_BACK。
- published_at。

### 4.3 工具 Tool

工具代表智能体可调用的外部能力，如企业 API、数据库查询、RPA、工单系统。

关键字段：

- name / code。
- endpoint。
- auth_type。
- timeout_ms。
- risk_level。
- enabled。

### 4.4 任务 Agent Task

记录智能体运行任务。

关键字段：

- agent_id / agent_version_id。
- task_type。
- input_summary。
- status：PENDING、RUNNING、SUCCEEDED、FAILED、CANCELLED。
- started_at / finished_at。
- latency_ms。
- token_cost / money_cost。
- result_summary。

### 4.5 治理策略 Policy

用于约束权限、数据、工具、输出内容与审批流程。

关键字段：

- policy_type：PERMISSION、PRIVACY、SECURITY、ALGORITHM、BENEFIT。
- scope_type：GLOBAL、AGENT、TOOL、DEPARTMENT。
- rule_json。
- enforcement_mode：WARN、BLOCK、APPROVAL_REQUIRED。
- enabled。

### 4.6 审计 Audit Log

关键操作必须留痕。

记录范围：

- 智能体创建、编辑、发布、暂停、下线。
- 工具授权变更。
- 策略变更。
- 高风险任务执行。
- 权限变更。

## 5. 功能模块设计

### 5.1 工作台

- 总览指标：智能体数量、运行中任务、风险事件、成功率、平均延迟。
- 待办事项：待审核版本、待处理风险、失败任务。
- 趋势图：任务量、成功率、风险事件、成本。

### 5.2 智能体管理

- 智能体列表、筛选、搜索。
- 智能体详情。
- 智能体创建与编辑。
- 生命周期管理。
- 版本发布与回滚。
- 工具授权。

### 5.3 运行中心

- 任务列表。
- 任务详情。
- 执行轨迹。
- 失败原因。
- 手动重试或取消。

### 5.4 治理中心

- 权限策略。
- 责任归属。
- 利益冲突约束。
- 审批流程。
- 策略命中记录。

### 5.5 伦理与安全中心

- 隐私策略。
- 安全策略。
- 算法透明度配置。
- 高风险输出拦截。
- 敏感数据访问记录。

### 5.7 多智能体运行安全

平台集成 gensee-crate 的核心安全控制思想，先以 EAP 本地策略引擎运行，后续可委托给真实 gensee sidecar。

核心能力：

- 安全会话：记录多智能体协作目标、根智能体和会话状态。
- 事件采集：接收 `AGENT_MESSAGE`、`TOOL_CALL`、`DATA_ACCESS`、`ARTIFACT_WRITE`、`DELEGATION`、`POLICY_CHECK`。
- 决策三态：输出 `ALLOW`、`ASK`、`DENY`。
- fail-closed：敏感数据、生产特权资源、密钥、不可追溯委托默认拦截或要求确认。
- 溯源链路：保存事件、决策、控制动作、制品 URI 和内容哈希。
- sidecar 预留：通过配置 `EAP_GENSEE_SIDECAR_ENABLED` 和 `EAP_GENSEE_SIDECAR_URL` 切换到外部策略执行器。

### 5.8 多智能体团队编排

参考 AgentMesh 的团队协作思想和 AgentScope 的事件、权限、工作区模型，平台增加团队编排控制面。

核心对象：

- Agent Team：团队、目标、协作模式、工作区策略。
- Team Member：团队成员、角色、责任。
- Plan：围绕业务目标生成的任务计划。
- Plan Step：按能力拆分的可执行步骤。

编排原则：

- 步骤必须声明 `requiredCapability`，方便通过 Agent Discovery 自动匹配 Agent。
- 团队必须声明 `workspacePolicy`，用于约束文件、工具、生产环境和制品写入。
- 步骤执行前进入安全网关，输出 `ALLOW`、`ASK`、`DENY`。
- 团队协作链路需要保留父子 Agent、会话、事件和制品溯源。

### 5.9 拖拽式工作流构建

平台提供可视化工作流构建器，让业务运营人员通过拖拽方式编排智能体之间的协作流程。

核心对象：

- Workflow Definition：工作流元数据、编码、名称、状态。
- Workflow Node：画布节点，可绑定具体 Agent，也可表示开始、决策、制品、结束等系统节点。
- Workflow Edge：节点之间的执行顺序和条件表达式。

交互设计：

- 左侧为节点面板，展示已注册 Agent 和系统节点模板。
- 中间为流程画布，支持拖拽创建节点、移动节点位置、点击两个节点创建连线。
- 右侧为属性面板，维护节点标签、节点类型、能力声明和 JSON 配置。
- 所有节点坐标、能力、配置和连线实时持久化，为后续执行器、计划编排和安全网关提供结构化输入。

执行原则：

- Agent 节点应声明 capability，方便运行时通过注册发现选择实例。
- 高风险节点执行前必须进入 gensee-compatible 安全评估链路。
- 边上的 conditionExpression 作为分支条件，后续由工作流执行器解释。
- 第一版执行器采用受控模拟执行，不直接调用真实生产工具；它负责拓扑遍历、节点状态返回、安全事件记录和审计闭环。
- 真实 Agent 调用、工具调用、人工审批、失败重试和补偿事务应通过后续 Runtime Adapter 层接入。
- 工作流版本化、发布审批和运行实例追踪将在后续迭代中补充。

### 5.10 自主智能体发现

自主发现与运行时服务发现分离。运行时服务发现负责从已经登记的能力和实例中选择执行节点；自主发现负责回答“组织环境里有哪些尚未登记的智能体、它们在哪里运行、由谁使用、连接了什么能力”。

第一版通过 `LocalAgentScanner` 周期扫描本机进程、项目清单和常见 Agent/MCP 配置目录，形成独立的候选资产层：

- `discovery_runs`：扫描运行记录。
- `discovered_agents`：归一化候选资产、指纹、状态、风险和置信度。
- `discovery_observations`：每次发现的来源位置和证据摘要。

扫描结果先进入 `DISCOVERED`，由治理人员复核、确认、忽略或认领后再进入正式 Agent 生命周期。扫描器只解析配置和清单，不执行 MCP 配置中的命令，避免发现过程本身引入代码执行风险。

第二阶段增加“端点 Collector + 中央汇聚”：端点先注册到 `discovery_endpoints`，再批量上报观察结果；服务端统一归并到候选资产层，并以 `ENDPOINT` 扫描记录保留来源。轻量 Collector 只采集进程元数据以及受限范围内的配置/清单特征，不上传文件原文和凭证值，不执行 MCP 命令。后续扩展方向包括代码仓库静态扫描、云平台/Provider API、身份系统、运行日志和暴露关系图谱。

### 5.11 模型调用物理遥测

模型调用遥测作为物理界面的统一计量源，记录 `provider`、`model_name`、输入/输出 Token、缓存/推理 Token、延迟、费用、工具/MCP 数量、状态、端点和 Agent 归属。事件通过 `event_key` 幂等写入，支持按时间窗口聚合调用次数、成功率、Token、费用、平均/P95 延迟、端点活跃度和运行实例健康度。遥测表不保存提示词和模型输出正文，知识分析和治理分析在后续数据平面扩展。

### 5.12 知识界面

知识界面以 `knowledge_interactions` 和 `knowledge_trace_steps` 为核心：一条交互记录输入知识类型/来源、处理步骤、输出知识类型/来源、Token、状态、质量、采用和复用结果；步骤记录检索、工具、推理、转换和输出分析等过程。摘要接口按时间窗口计算输入/输出来源数、过程延迟、知识采用率和知识复用率。知识正文不进入平台数据库，只保留脱敏摘要和引用标识。

### 5.13 管理界面

管理界面把物理投入、任务结果、知识使用和业务价值放到同一时间窗口中分析。`management_value_events` 保存由业务方确认的价值事件，必须关联已注册 Agent 或自主发现的端点，并以 `event_key` 幂等写入；事件只保存价值类型、脱敏说明、确认人、金额和归因标识，不保存模型提示词或知识正文。

管理摘要复用物理遥测的真实费用、任务状态和知识交互结果，输出总投入、确认业务价值、净收益、任务成功率、知识采用率、知识复用率和 Agent/端点级明细。ROI 定义为 `(确认业务价值 - 模型投入) / 模型投入`；当窗口内没有业务方确认价值，接口返回 `hasValidatedValue=false`，前端不展示数值 ROI，避免把推测当成经营结果。

### 5.14 治理数据界面

治理数据界面以 `governance_data_events` 为核心，接收模型网关、DLP 或 Agent Runtime 对数据流的分类结果：数据方向、业务类别、敏感级别、是否内部信息、是否公司保密信息、Token/字节计数、匹配策略和处置动作。事件必须归因到已登记 Agent 或自主发现端点，并以 `event_key` 幂等写入。

摘要按窗口输出进入模型的事件数、内部信息事件数、公司保密事件数、允许/告警/脱敏/阻断/审批动作数，以及内部信息占比、保密信息占比和阻断率。平台不接收提示词、模型输出、文件原文或敏感字段值；治理系统记录“发生了什么分类和处置”，不复制“数据是什么”。

实时强制层通过 `governance_preflight_decisions` 保存预检结果。模型网关必须先提交由 DLP/分类器生成的标签，再根据 `gatewayDirective` 执行：允许、告警放行、脱敏后重检、等待审批或阻断。多条策略同时命中时按 `BLOCK > APPROVAL_REQUIRED > REDACT > WARN > ALLOW` 选择最严格动作；`SECRET/RESTRICTED` 默认 fail-closed，即使策略缺失或配置遗漏也不允许直接进入模型。每次预检同时生成不含正文的治理事件和审计日志。`APPROVAL_REQUIRED` 决策进入 `PENDING` 状态，只有审批 API 转换为 `APPROVED` 后才能被网关复用；复用时校验 SHA-256 请求指纹、Agent/端点、Provider 和 Model，任何变化都必须重新预检。

V16 增加审批状态机字段：`approval_status`、`approved_by`、`approval_reason`、`approved_at` 和 `request_fingerprint`。审批动作单独写入审计日志；生产部署可通过 `EAP_GOVERNANCE_APPROVAL_TOKEN` 将审批凭证与数据上报凭证分离。

物理遥测事件通过 `governance_decision_key` 与预检绑定。强制模式下，遥测接收端校验决策存在、未超过租约时长，并校验 Agent/端点、Provider、Model 与预检一致；不满足条件的模型调用不上账，防止 Agent Runtime 绕过治理路径。

OpenAI-compatible 治理模型网关提供统一数据路径：Agent 把 Chat Completions 请求提交给 `/api/gateway/v1/chat/completions`，网关完成身份归因、启发式或外部 DLP 分类、输入预检、上游模型转发、输出预检和遥测回传。非流式请求直接返回 JSON；`stream=true` 采用完整缓冲 SSE 后再输出治理的安全模式，并受最大响应字节数限制。`BLOCK/REQUEST_APPROVAL` 直接停止，`REDACT_AND_RECHECK` 先脱敏再生成新决策；模型正文只在内存中短暂经过，不进入 EAP 数据库。网关默认关闭且不配置任何供应商密钥，生产部署必须显式启用。审批接口可通过 `EAP_GOVERNANCE_APPROVAL_TOKEN` 与 `X-EAP-Governance-Approval-Token` 单独保护。

### 5.6 组织与权限

第一版保留简单用户与角色模型，后续对接企业 IAM/LDAP/OIDC。

角色建议：

- PLATFORM_ADMIN：平台管理员。
- GOVERNANCE_OFFICER：治理管理员。
- SECURITY_OFFICER：安全管理员。
- AGENT_OWNER：智能体负责人。
- OPERATOR：运营人员。
- AUDITOR：审计人员。

## 6. API 设计

统一响应：

```json
{
  "success": true,
  "data": {},
  "message": "ok",
  "traceId": "..."
}
```

核心 API：

- `GET /api/dashboard/summary`：工作台摘要。
- `GET /api/agents`：智能体列表。
- `POST /api/agents`：创建智能体。
- `GET /api/agents/{id}`：智能体详情。
- `PUT /api/agents/{id}`：更新智能体。
- `POST /api/agents/{id}/status`：变更生命周期状态。
- `GET /api/workflows`：工作流定义列表。
- `POST /api/workflows`：创建工作流。
- `GET /api/workflows/{workflowId}`：工作流详情，包含节点和连线。
- `POST /api/workflows/{workflowId}/nodes`：新增或更新画布节点。
- `POST /api/workflows/{workflowId}/edges`：新增节点连线。
- `POST /api/workflows/{workflowId}/runs`：执行一次工作流，返回节点状态和安全决策。
- `GET /api/tasks`：运行任务列表。
- `GET /api/policies`：治理策略列表。
- `POST /api/policies`：创建治理策略。
- `GET /api/audit-logs`：审计日志。
- `GET /api/telemetry/physical-summary?hours=24`：物理投入和运行状态摘要。
- `GET /api/knowledge/summary?hours=24`：知识采用与复用摘要。
- `GET /api/management/summary?hours=24`：投入产出和管理指标摘要。
- `POST /api/management/value-events`：登记业务方确认价值事件。
- `GET /api/governance/data-summary?hours=24`：内部/保密数据流和策略动作摘要。
- `POST /api/governance/data-events`：上报不含正文的治理分类事件。
- `POST /api/governance/preflight`：模型网关实时治理预检。
- `POST /api/governance/preflight-decisions/{decisionKey}/approve|reject`：审批或拒绝待审批模型请求。
- `GET /api/governance/enforcement-status`：强制治理运行状态。
- `GET /api/gateway/status`：模型网关启用与配置状态。
- `POST /api/gateway/v1/chat/completions`：治理后的 OpenAI-compatible 模型调用入口。

## 7. 数据库设计

第一版数据库表：

- agents。
- agent_versions。
- tools。
- agent_tool_bindings。
- agent_tasks。
- governance_policies。
- audit_logs。
- users。
- roles。
- user_roles。

数据库要求：

- 所有业务表保留 `created_at`、`updated_at`。
- 关键业务编码建立唯一索引。
- 常用筛选字段建立组合索引。
- 审计日志只追加，不物理删除。
- Flyway 管理所有 DDL。

## 8. 前端信息架构

```text
工作台
智能体
  - 列表
  - 详情
  - 版本
  - 工具授权
运行中心
治理中心
伦理安全
审计日志
系统设置
```

前端工程约定：

- `src/api`：后端接口封装。
- `src/views`：页面。
- `src/components`：通用组件。
- `src/stores`：Pinia 状态。
- `src/types`：领域类型定义。
- API 层统一处理错误、traceId 和鉴权头。

## 9. 非功能设计

### 9.1 安全

- 所有 API 默认需要认证。
- 管理端操作按角色授权。
- 高风险工具需要显式授权。
- 敏感字段前端脱敏展示。
- 后端记录所有高风险操作。

### 9.2 可观测性

- Actuator 暴露健康检查。
- 日志包含 traceId。
- 运行任务记录延迟、状态、成本。
- 后续接入 OpenTelemetry。

### 9.3 可维护性

- 模块化单体，按领域包拆分。
- Controller 不直接写业务规则。
- DTO 与实体分离。
- 数据库变更通过 Flyway。
- 前端组件按业务视图拆分。

## 10. 开发分期

### 第一期：管理控制平面 MVP

- 工作台。
- 智能体 CRUD。
- 生命周期状态管理。
- 治理策略 CRUD。
- 任务列表与模拟运行数据。
- 审计日志。
- MySQL + Flyway。

### 第二期：运行与工具网关

- 工具注册。
- 智能体工具授权。
- 任务执行器。
- 策略执行点。
- 模型网关抽象。

### 第三期：智能治理

- 风险评分。
- 敏感数据识别。
- 算法评估报告。
- 自动审批建议。
- 质量反馈闭环。

### 第四期：企业集成

- 企业 IAM。
- 知识库/向量库。
- 工单、CRM、ERP 接入。
- 多租户。
- 私有化部署运维。
