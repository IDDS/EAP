# EAP 项目记忆

> 最近更新：2026-08-10。此文件用于跨会话保存项目共识、已完成能力、验证基线和下一步工作；具体实现以代码、数据库迁移和测试为准。

## 1. 产品目标

EAP 是企业智能体管理控制平面，核心目标是让企业内的 Agent、Skill、MCP、模型调用和知识流转具备以下能力：

- 自主发现：尽量无需修改 Agent 代码、无需逐个配置目标即可发现资产。
- 深度可观测：按 Agent 归因 Token、费用、模型调用、工具调用、Prompt/输出观测和 Trace。
- 知识度量：分析输入知识、过程步骤、输出知识、采用率和复用率。
- 管理度量：分析智能体投入、业务价值、净收益、ROI 和任务成功率。
- 数据治理：识别内部信息与保密信息进入模型的路径，执行预检、脱敏、审批、阻断和审计。

## 2. 当前已实现能力

### 2.1 自主发现与身份管理

- 本机进程、项目清单以及常见 Agent、MCP、Skill、Plugin 配置扫描。
- A2A Agent Card、MCP Server Metadata、MCP Registry 协议发现。
- 已注册端点采集器及批量观察结果上报。
- Kubernetes Watch、AdmissionReview 和 eBPF 网络观察连接器。
- 候选资产复核、纳管、扫描运行记录、发现摘要和跨来源身份链接。
- Agent Runtime endpoint 注册和运行实例状态管理。
- 发现过程只读取元数据和配置，不执行发现到的 MCP 或插件命令。

### 2.2 物理与深度可观测

- 模型调用事件：供应商、模型、输入/输出/缓存/推理 Token、费用、延迟、状态和工具数量。
- Agent 级 Token 消耗趋势与明细。
- OTLP/HTTP JSON Trace 接收和 OTLP gRPC 接入。
- Trace Span 按 Agent、endpoint、runtime、trace、span 类型和时间范围查询。
- GenAI、Tool、MCP、RAG、A2A 和 Agent Span 归一化。
- Prompt 输入和模型输出支持 `OFF`、`HASH_ONLY`、`REDACTED` 三种观测模式。
- 工具调用明细支持工具名、工具类型、MCP Server、Agent、Trace/Span、状态、时延及输入/输出观测关联。
- OpenAI-compatible 网关可归因非流式和流式模型调用及 tool call/tool result。

### 2.3 四类业务界面

- 物理界面：智能体结构、运行状态、Token、费用、延迟、成功率、模型和工具调用 Trace。
- 知识界面：输入知识、过程步骤、输出知识、知识采用率和知识复用率。
- 管理界面：投入成本、已确认业务价值、净收益、ROI 和 Agent 级指标。
- 治理界面：敏感数据分类、保密信息、预检决策、审批、脱敏、阻断、审计和网关状态。

### 2.4 前端交互与可读性

- 主要工作台使用顶部 Tag 分区，减少单页信息密度。
- 注册发现的 Agent、Skill、Plugin 等列表支持分页。
- 已注册 Agent Runtime Discovery 区域已重新整理布局。
- Trace 详情检查器、趋势曲线、柱状图、全局搜索和 Agent Token 图表可用。
- 已移除无后端能力的通知死按钮；保留的主要按钮均连接前端逻辑或后端接口。
- 默认使用舒适字号，顶部可切换标准、舒适和大字号，设置保存在浏览器本地。
- 控制台采用统一的深蓝/蓝绿科技视觉层：轻量网格背景、实时状态脉冲、渐变主按钮、统一卡片层级和页面切换动效；核心页面减少重复英文和冗长说明。

### 2.5 控制、编排与安全

- Agent Runtime 控制命令 outbox、投递、重试、ACK 和失败状态。
- 多智能体 Team、Member、Plan、Step 编排。
- 拖拽式工作流定义、节点、边和受控执行。
- `ALLOW`、`ASK`、`DENY` 三态安全决策和 fail-closed 策略。
- OpenAI-compatible 治理网关执行输入/输出预检，并回传遥测和审计记录。

## 3. 最近验证基线

- 后端测试：`mvn -q test`，50 tests，0 failures，0 errors。
- 前端构建：`npm run build` 通过，Vue TypeScript 检查和 Vite 构建成功。
- 前端主要路由 `/`、`/physical`、`/registry`、`/knowledge`、`/management`、`/governance`、`/observability` 返回 HTTP 200。
- 自动发现最近一次复核能够成功完成，观察到约 300 条信号并形成约 255 个候选资产；数量会随扫描环境变化。
- 隔离 OTLP 验证已确认 Tool Span 能归因到 Agent，并生成工具调用、输入/输出观测和 Trace 关联记录。

## 4. 已知边界和风险

- 真正覆盖企业所有主机和集群仍需要部署端点采集器、Kubernetes 连接器或 eBPF 探针；中央服务无法凭空看到其他机器上的进程。
- 无侵入 Prompt/输出观测优先依赖统一模型网关、代理流量或 OTLP；端到端加密且不经过受控网关时，不能可靠获取正文。
- `HASH_ONLY` 只能证明内容关联与变化，不能展示原文；`REDACTED` 需要配置脱敏策略后才适合生产使用。
- 本地 `local` profile 默认使用内存 H2，后端重启后遥测、Trace 和工具调用历史会清空；正式部署应使用 MySQL。
- 发现资产、Runtime、OTLP Span 和网关调用必须尽量携带稳定 Agent 身份，否则只能通过 endpoint、进程、工作负载或启发式身份图谱关联。
- 前端 HTTP 200 和构建通过不等于所有业务按钮完成端到端外部系统验证；涉及真实模型、Kubernetes、eBPF 和 Runtime 控制时仍需在目标环境验收。

## 5. 下一步优先级

1. 完善发现身份图谱：提高 endpoint、进程、Kubernetes workload、模型调用和正式 Agent 的自动合并准确率，并提供冲突复核。
2. 完成端到端演示链路：自动发现 Agent → 采集 Trace → 查看模型与工具调用 → 触发治理策略 → 统计 Token、成本和 ROI。
3. 增加真实采集器发行包：主机 Agent、Kubernetes Helm Chart、Tetragon/eBPF 适配器和服务网格接入说明。
4. 强化无侵入观测：透明代理、OpenTelemetry Collector 处理器、常见 Agent 框架自动探针和网关旁路元数据采集。
5. 增加前端端到端自动化测试，覆盖分页、筛选、Trace 钻取、治理审批、控制命令和错误状态。
6. 在生产部署中启用 MySQL、JWT、机器凭证、保密信息 fail-closed、审计留存和数据生命周期策略。

## 6. 开发与交付约定

- 本地后端：`cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=local`
- 本地前端：`cd frontend && npm run dev`
- 后端验证：`cd backend && mvn -q test`
- 前端验证：`cd frontend && npm run build`
- 一体化部署：`docker compose up -d --build`
- 远端仓库：`https://gitee.com/wallemit/eap.git`
- 功能实现、测试结果或重要风险发生变化后，应同步更新本文件。
