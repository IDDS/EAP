# EAP 项目记忆

> 最近更新：2026-10-24（最新状态见末尾 Codex 进展）。此文件用于跨会话保存项目共识、已完成能力、验证基线和下一步工作；具体实现以代码、数据库迁移和测试为准。

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
- 注册发现页已按“发现总览 / 身份关联 / 资产清单 / 运行时注册”拆分顶部 Tag 分区；物理界面进一步压缩运行全景说明，并将网络状态、风险、扫描触发类型和关联方式转为中文显示。
- 企业控制台导航进一步按“总览 / 智能体中心 / 运行与观测 / 编排与学习 / 治理与安全”分组；顶部字号、空间与认证控件完成紧凑化，Element Plus 全局启用中文语言包。API 异常统一转为中文并在 3 秒内合并重复通知，避免多个并发请求连续弹出英文 500。
- 智能体管理已升级为 Agent-centric 资产目录，支持运行、风险和待处理筛选、卡片/列表切换与分页；点击智能体进入独立详情工作台，可在顶部 Tab 中查看身份属性、运行实例、能力、任务、模型与 Token、Trace Span、输入/输出观测、工具与 MCP、知识交互及治理事件。全局搜索中的已纳管智能体和发现资产会直接进入对应智能体详情。

### 2.5 控制、编排与安全

- Agent Runtime 控制命令 outbox、投递、重试、ACK 和失败状态。
- 多智能体 Team、Member、Plan、Step 编排。
- 拖拽式工作流定义、节点、边和受控执行。
- `ALLOW`、`ASK`、`DENY` 三态安全决策和 fail-closed 策略。
- OpenAI-compatible 治理网关执行输入/输出预检，并回传遥测和审计记录。

### 2.6 全站安全运营中心改版（2026-08-14）

- 已确认并实施全站改版：采用深色导航 + 浅色高密度企业安全运营中心主视觉，保留现有 API、业务按钮和路由。
- App 导航重组为“总览 / 资产与智能体 / 运行监控 / 编排与自动化 / 治理与响应”五大入口；旧 URL 继续兼容，现有能力按二级视图归类。
- 全局样式新增 SOC 设计系统：统一页面层级、卡片、表格、筛选条、指标卡、风险状态、输入控件、响应式布局和页面切换。
- 新增本地 SVG `ModelGlyph` 组件，使用抽象星核图标识别大模型；按供应商提供小尺寸文字徽标和色彩区分，未引入外部 Logo 依赖。
- 模型图标已接入智能体目录、智能体详情模型分布/调用记录、物理界面模型排行与 Trace、治理决策模型列。
- 本轮采用 grilling skill 逐项确认设计共识后实施；外部企业控制台仅作为通用信息架构参考，未复制品牌素材。

### 2.7 腾讯云部署基线（2026-08-14）

- EAP 已部署至腾讯云 `114.132.234.109`，公网入口为 `http://114.132.234.109:8091`，服务器目录为 `/home/ubuntu/eap`。
- 部署使用 `docker-compose.tencent.yml`；远端首次从 Maven Central 构建过慢时，使用 `docker-compose.tencent.prebuilt.yml` 和本地验证产物完成部署。
- 仅前端 Nginx 暴露宿主机 `8091`；后端 `8080/4317` 和 MySQL `3306` 仅在 Docker 网络内使用。
- 独立容器为 `eap-frontend`、`eap-backend`、`eap-mysql`，数据库使用持久化卷 `eap_eap_mysql_data`；服务器上既有的 Boss Agent 容器和端口保持不变。
- 修复 MySQL 首次迁移兼容性：`V17__content_observability.sql` 的 `content_json` 从 `clob` 改为 `text`。远端 24 个 Flyway 迁移全部成功，无失败迁移。
- 当前公网实例使用 `DEVELOPMENT` 安全模式，只适合作为演示/验收环境；正式生产前必须接入 JWT/OIDC、HTTPS 和更严格的入口访问控制。

### 2.8 旧宿主机 Docker 采集器（2026-08-14）

- 轻量端点采集器升级到 `0.3.0`，新增宿主机 Docker 工作负载发现：只读调用 `docker ps` / `docker inspect`，无需将 Docker Socket 挂载给 EAP 主后端。
- 识别信号包括显式 `eap.agent` / `com.eap.agent` / `ai.agent` 标签、已知产品和框架签名、Compose 项目与服务角色、LLM 凭证环境变量名称；只上报变量名称，不上报密钥值。
- 新增 `DOCKER` 发现来源，前端显示为“Docker 容器”；数据库、前端、代理、可观测基础设施和 EAP 自身容器默认排除。
- 原 systemd oneshot + timer 模板保留为兼容方案；腾讯云上的 0.3.0 timer 已停用，历史端点保留为 `OFFLINE`。
- 当前服务器已自动发现 `HelloBoss @ VM-0-15-ubuntu`：产品 `HelloBoss`、进程 `boss-agent-backend`、来源 `DOCKER`、置信度 98%、风险等级 MEDIUM。
- Boss Agent 仓库存在大量未提交改动，且当前只提供自定义 FastAPI/OpenAPI 接口，并未实现完整 A2A 协议；本轮未修改 Boss Agent 工作区，也未发布可能误导调用方的 Agent Card。

### 2.9 独立 Discovery Agent 与业务内容通道（2026-08-14）

- 新增独立 Go 模块 `discovery-agent/`，提供 Linux amd64/arm64 的 `eap-discovery-agent` 和 `eap-content-proxy`，不依赖 EAP 后端进程执行本机扫描。
- 采用一次性安装令牌、每机器凭证、发现/内容凭证分离、心跳、策略下发、幂等批次、加密离线队列、增量/FULL 快照和离线判定。
- 资产护照覆盖主机、账户、进程、可执行路径、容器、端口、协议、服务端点、模型、Skill、Plugin、Tool、MCP、关系和安全信号；环境变量只采集 LLM 凭证键名，不采集值。
- 业务内容通过独立透明代理或 OTLP/SDK 进入 EAP，支持分类、脱敏、AES-GCM 密文库、密钥版本、30 天原文保留和长期脱敏数据；启用前必须由平台管理员与数据责任人双重审批。
- 外部大模型可按数据分类和供应商策略用于效用分析；正式经济收益仍必须来自业务方确认的价值事件。
- 腾讯云已安装 `eap-discovery-agent` 1.4.0，端点 `ONLINE`、心跳和扫描正常、离线队列为 0；1.3.0、1.2.1、1.2.0 与 1.1.0 二进制保留为回滚副本。内容代理二进制已同步升级但服务保持禁用。
- Content Proxy 的模型响应 `usage` 已接入 EAP 模型遥测：支持 OpenAI/Anthropic/Gemini 常见输入、输出、缓存和推理 Token 字段，兼容带最终 usage 事件的 SSE，携带延迟、费用、工具调用与错误类型，并以稳定事件键幂等入库，观测来源标记为 `CONTENT_PROXY`。
- 新增 `eap-content-proxy@.service` systemd 模板，支持在同一机器上为不同 Agent 运行独立代理实例；每个实例使用不同 `agent_id`、监听端口和状态目录，可以共享同一个供应商凭证与非保密用量池别名。
- 已为 HelloBoss 准备专属配置 `deploy/tencent/content-proxy-helloboss.json`：目录 Agent ID 4、DeepSeek 上游、`shared-deepseek-production` 用量池，只监听 Boss Docker bridge 的 `172.18.0.1:8687`。远端隔离状态目录和内容身份已安装，但 systemd 实例保持 `disabled/inactive`。
- HelloBoss 已由新探针稳定识别为当前端点的 Docker Agent 资产，继续关联正式目录 Agent ID 4；前端“发现探针”显示 1.4.0/ONLINE/0 批队列，目录与证据页均可查看。全局历史数据中仍可能保留停用旧采集器产生的资产，不等同于新探针当前上报结果。

### 2.10 共享 API Key 用量归因与对账（2026-08-14）

- 多个 Agent 共用供应商 API Key 时，EAP 使用非保密的 `usagePoolKey` 别名关联调用事件与供应商总量，不采集、回显或持久化真实 API Key。
- 模型网关、OTLP 和 Content Proxy 均可上报用量池别名；Content Proxy 推荐每个 Agent 独立实例，从调用入口保留稳定 Agent 身份。
- 新增供应商用量快照模型和 Flyway V25，支持按供应商、账户别名、时间窗、Token、费用、币种和来源保存幂等快照；同一用量池拒绝重叠时间窗以及供应商/币种冲突。
- 新增 `/api/telemetry/provider-usage-snapshots` 导入/查询接口和 `/api/telemetry/usage-reconciliation` 对账接口。导入接口使用现有机器遥测凭证，读取接口用于管理前端。
- 物理界面新增“共享 API Key 用量对账”，展示供应商总量、已归因量、未分配量、覆盖率、超额归因和未标记调用。差额保留为未分配量，不按 Agent 平均摊分。
- 当前完成的是供应商无关的标准化导入边界；连接真实供应商 Usage/Billing API 仍需选定供应商，并配置只读管理凭证或账单导出任务。

### 2.11 LiteLLM 无代码自动挂接（2026-08-15）

- Discovery Agent 1.2.1 起提供 `attach` 命令（当前部署为 1.4.0），能够从 Docker 元数据自动定位 Compose 项目、名称、服务和配置文件，并通过容器内只读探测识别 LiteLLM；默认仅输出脱敏 dry-run 计划。
- 新增只读 Python `sitecustomize` 注入器，LiteLLM 被应用首次导入时自动注册其官方 `CustomLogger` 回调；无需修改 Agent 源代码、模型 Base URL 或供应商 API Key。
- 回调采集 Prompt、模型响应、Token usage、时延和结果状态，只发送给 Agent 专属宿主机内容收集器。回调不读取供应商凭证，并会移除响应隐藏字段、API Key、Authorization、Credential 和 Access Token 等敏感配置字段。
- Content Proxy 新增 `/eap/content-proxy/capture` 本地注入入口，使用每 Agent 独立的随机作用域令牌；代理强制覆盖 `agentId`、供应商和 `usagePoolKey`，因此工作负载无法冒充其他 Agent，也无需获得 EAP 内容机器凭证。
- `--apply` 会安装只读注入资产、生成 Compose Override、启动 Agent 专属收集器并只重建目标服务；执行前必须同时满足内容采集授权、中央 `contentAutoAttachEnabled` 策略和允许的框架 Profile，否则 fail-closed。
- 主配置新增声明式 `auto_attach` 目标。安装包新增 root-only 的 `eap-discovery-auto-attach.service` 与每分钟 timer；协调器先获取中央有效策略，只有内容双重授权与自动挂接开关同时生效才检查 Docker 并执行挂接，常驻发现服务继续使用低权限账户。
- 自动挂接通过仪表版本、Collector URL、Agent ID、供应商和用量池判断幂等；已匹配的工作负载只校验资产与收集器，不重启。首次挂接或归因配置变化才会重建目标 Compose 服务，并显式保留原 Compose project name 与文件顺序。
- 腾讯云 HelloBoss dry-run 已自动识别 `boss-agent-backend`、Compose `backend` 服务、LiteLLM、Agent ID 4、DeepSeek 和 `shared-deepseek-production`。验证确认未生成 Override、未创建作用域令牌、未启动代理、未重启或修改 HelloBoss。
- 腾讯云已声明 HelloBoss 自动挂接目标并启用协调 timer；后端基础开关已设为 true，但有效策略仍因内容双重授权未完成而返回 false。协调器成功执行且 `targets_applied=0`，未生成 Override/令牌，Content Proxy 保持 inactive，Boss 容器 ID 与启动时间不变。

### 2.12 跨平台 Host Observer（2026-08-15）

- Agent 发现与 EAP 已进一步解耦：新增统一 Host Observer 协议，支持 `EBPF`、`ETW`、`WFP`、`ENDPOINT_SECURITY`、`NETWORK_EXTENSION` 和 `PROCESS_NETWORK_SCAN` 六类传感器；旧 eBPF API 作为兼容适配器保留。
- Discovery Agent 1.3.0 实现可运行的便携主机网络快照：Linux 使用 `ss` + `/proc` + Docker 元数据，macOS 使用 `lsof`，Windows 使用 `netstat` + `tasklist`。安装包新增 root-only `eap-host-observer.service/timer`，默认每分钟执行；常驻发现服务仍为低权限账户。
- 独立探针通过 `/api/discovery/v1/agent/host-observations` 和每机器凭证上报，端点身份由 EAP 服务端绑定；外部企业采集器可通过 `/api/discovery/connectors/host/observations` 和发现机器令牌接入。
- 机器凭证 Host Observation 复用已认证端点，不再调用端点注册逻辑；`collectorVersion` 只作为证据保存，不会覆盖控制台中的 Discovery Agent `agentVersion`。腾讯云回归确认提交测试采集器版本后端点仍显示 1.4.0/ONLINE。
- 主机观察只采集进程、PID、执行路径、容器/cgroup/workload 身份、地址、端口、方向、协议提示、TLS SNI、供应商/模型提示和字节数。普通网络观察不能解密 HTTPS，也不能获得 Prompt、响应正文、API Key 或精确 Token usage。
- 精确跨源身份由显式 `identityKey` 与主机构成，不混入不同来源推断的显示名称/产品；被动网络证据命中既有 Docker 资产时只补充来源、证据、置信度和风险，不覆盖 canonical 名称、厂商、产品、指纹、进程和执行路径。
- 腾讯云端到端验证：合成 `PROCESS_NETWORK_SCAN` 证据成功合并到 HelloBoss ID 1，名称、厂商、产品、指纹、进程和路径均保持不变，来源由 `DOCKER` 变为 `DOCKER + HOST_OBSERVER`，未创建重复资产。
- 初次试运行发现 Tencent TAT 与 EAP 自身进程可能被宽泛 `agent` 词误识别；已将 ID 25/26 标记 `IGNORED`，收紧允许标记并显式排除 `tat_agent`、`/qcloud/`、EAP 自身及常见遥测进程。修复后二次 dry-run 和正式 oneshot 均为 0 条观察，没有新增误报。
- 首次启用 timer 时发现仅使用 `OnBootSec` 会在主机已长期运行的情况下进入 `elapsed`；已增加 `OnActiveSec=15s`，腾讯云验证首次触发成功、后续 60 秒调度已生成，timer 为 `active/waiting`。
- Discovery Agent 1.4.0 新增 Linux 原生 eBPF 适配器：BTF + `bpftrace` 在 `sys_enter_connect` 采集 IPv4/IPv6 连接，使用 `/proc`/只读 Docker 元数据补充进程、容器和稳定 identityKey，按 30 秒/1000 事件批次上报 `EBPF`。systemd service 使用 root 仅为加载 eBPF，能力集不含写磁盘权限；读取机器凭证仅增加 `CAP_DAC_READ_SEARCH`。
- eBPF 服务启动失败时不阻断 Discovery Agent 或便携 Host Observer；安装脚本 fail-open，`eap-host-observer.timer` 继续提供回退观测。原生 Windows ETW/WFP、macOS EndpointSecurity/NetworkExtension 仍待实现。
- 腾讯云已验证：Linux 6.8 + BTF + bpftool/bpftrace 条件满足；eBPF 编译、systemd 沙箱 dry-run、真实 connect 事件采集和 EAP 上报均成功。稳定 Docker 身份合成回归后，HelloBoss 来源为 `DOCKER + HOST_OBSERVER + EBPF`，canonical 字段保持不变。
- eBPF 真实测试产生的候选资产 27/28（测试进程和测试容器触发的网络候选）已通过正式状态 API 标记为 `IGNORED`，未删除证据或修改 Boss 容器。
- 上线后的最终回归再次由独立测试进程触发 1 条 eBPF connect 事件，service 返回 EAP 成功响应且没有产生新资产；当前 eBPF service `enabled/active`，Host Observer fallback timer 和自动挂接 timer 同样保持 active。

### 2.13 智能体请求内容观测边界与纳管能力（2026-08-21）

- 当前平台已具备针对常见智能体产品、Agent 开发框架、Skill、Plugin、MCP 和 A2A Runtime 的自动发现、身份归并、风险识别和正式纳管能力；已覆盖 Codex、Claude Code、Cursor、Windsurf、Kiro、OpenCode、Amp、Amazon Q、Antigravity、Cline、Roo Code、Gemini CLI、GitHub Copilot、Hermes Agent、OpenClaw，以及 Dify、Flowise、Langflow、LangGraph、LangChain、CrewAI、AutoGen、Semantic Kernel、Mastra、OpenAI Agents SDK 等常见框架/运行时。
- 自动发现层（进程、路径、配置、容器、Kubernetes、Host Observer、eBPF、协议探测）主要采集资产与运行元数据：进程、端口、工作负载身份、模型/供应商提示、协议、Token/延迟/字节数等；普通网络观测不能解密 HTTPS，也不能直接获得 Prompt、响应正文、API Key 或精确调用级 Token usage。
- 智能体请求输入和模型输出可以通过受控 EAP 模型网关、每 Agent 的 `eap-content-proxy`、SDK/框架插件或 OTLP GenAI Trace 接入，并与 Agent、Endpoint、Runtime、Trace、Provider、Model、Tool/MCP 关联。统一接口包括 `/api/observability/content`、`/api/observability/otlp/v1/traces` 和 `/api/discovery/v1/agent/content`。
- 深度内容观测策略为 `OFF`、`HASH_ONLY`、`REDACTED`：`HASH_ONLY` 只保存 SHA-256、大小、Token 估算、敏感级别、治理动作和 Trace 关联；`REDACTED` 只保存脱敏版本，原始正文仅在内存中用于计算哈希，不落库；`OFF` 完全关闭正文观测。默认安全模式为 `HASH_ONLY`。
- 当前腾讯云演示环境已部署 Discovery Agent 1.5.0，但内容采集仍未启用，内容观测统计为 0 条输入/0 条输出；`eap-content-proxy@helloboss.service` 保持 `disabled/inactive`。启用真实请求内容前必须同时满足中央策略、内容采集授权、受控 TLS/网关或代理接入、机器凭证以及数据分类/保留/出境合规要求。
- 正式正文读取受权限约束：JWT 模式下仅 `GOVERNANCE_ADMIN` 或 `PLATFORM_ADMIN` 可读取 `/api/observability/content`；生产环境应优先使用 `HASH_ONLY`，如需展示正文必须启用 HTTPS + JWT/OIDC，并由平台管理员与数据负责人完成审批。

### 2.14 首页智能体拓扑恢复与线上发布（2026-08-24）

- AgentOps 首页改版曾移除“智能体网络控制面 / 数据面”拓扑组件，但后端 `/api/dashboard/network` 数据接口仍保留并正常返回数据。
- 已在 `frontend/src/views/DashboardView.vue` 恢复拓扑画布、控制面/数据面连线、节点拖拽、拓扑统计和控制命令 outbox；新的 AgentOps 行动队列继续保留。
- 已完成前端构建并仅重建腾讯云 `eap-frontend` 容器；后端、MySQL、Discovery Agent、Host Observer、eBPF 和 HelloBoss 容器未重启或修改。
- 线上首页已切换到新前端资源；当前拓扑接口返回 4 个控制节点、6 个 Agent 节点、22 条控制边和 2 条数据边。浏览器若仍显示旧页面，需要执行一次强制刷新。
- 前端新增构建版本 `version.json`、每 60 秒和页面重新可见时的版本探测，以及“检测到 EAP 前端有新版本 / 立即刷新”提示；Nginx 对 `index.html`、`version.json` 禁止缓存，对带 hash 的静态资源启用长期缓存。
- 用户可见品牌统一为 EAP（顶部“EAP · 企业智能体控制台”、首页/目录/详情 EAP 文案）；`AgentOps` 仅保留为内部 API、类型和实现模块名称，不作为产品名称展示。

### 2.23 Galileo 方法借鉴与 Evaluation Foundation（2026-09-02）

- 已完成 Galileo / Splunk Agent Observability 借鉴研究，结论是吸收其“评估工程 → 生产评估 → Signals → 运行时护栏 → 人工反馈”闭环，不把 Galileo/Splunk 作为 EAP 核心依赖；完整 memo 见 `docs/GALILEO_RESEARCH.md`。
- 新增 EAP 原生 `evaluation` 后端模块和 Flyway V31：评估器定义/版本、评估数据集、评估结果、确定性执行器和查询 API。
- 新增接口：`GET/POST /api/evaluations/evaluators`、`GET/POST /api/evaluations/datasets`、`GET/POST /api/evaluations/results`。
- 预置 6 个确定性评估器：`ACTION_COMPLETION`、`TOOL_ARGUMENT_VALIDITY`、`TOOL_STATUS`、`PII_POLICY`、`SENSITIVE_APPROVAL`、`COST_BUDGET`；另支持 `EXACT_MATCH` 实现用于回归测试。
- 评估请求支持 `agentId`、`traceId`、`spanId`、数据集标识、输入/期望/实际 JSON；结果保存分数、通过状态、置信度、理由和证据 JSON。评估执行所需的 JSON 单字段默认限制 64KB，持久化遵循现有 `OFF/HASH_ONLY/REDACTED` 观测模式，避免把原始内容无边界写入评估库。
- 当前实现是 observe/evaluate 基础，不会自动改变现有 `ALLOW/ASK/DENY` 控制；后续仍应增加异步 worker、Session/Trace 聚合、LLM Judge/本地 SLM，以及 evaluator-to-policy 编译器。
- 前端新增 `/evaluation`“评估与可靠性”工作台，可查看评估器/数据集/最近结果，创建示例数据集并显式运行一次确定性评估；明确标注当前不会自动改变控制决策。
- 新增 `EvaluationProjectionService` 和可选 `EvaluationProjectionScheduler`：扫描近期终态 Tool Invocation，自动生成 `TOOL_STATUS`，当 OTLP attributes 提供 `requiredArguments` 时同时生成 `TOOL_ARGUMENT_VALIDITY`；结果键按工具事件幂等，默认不会自动启用定时扫描。
- Evaluation 投影进一步覆盖 Trace：按 Trace 聚合近期 Span，优先读取 `eap.task.completed` 等显式任务信号；缺少显式信号时采用“存在模型/Agent 步骤且无失败 Span”的保守启发式，生成 `TRACE_ACTION_COMPLETION`，同时记录 Span、Tool、失败数、耗时和推断模式等证据；启发式结果置信度固定为 0.6，避免把推断结果伪装成业务确认。
- 新增 RAG 投影：复用知识交互的检索源、输出源、引用重合、采用结果和质量分，生成 `RAG_CONTEXT_COVERAGE`；当前只衡量“是否形成可追踪上下文证据”，不宣称语义相关性或事实正确性，输入/输出正文仍受现有观测模式约束。
- 新增 `POST /api/evaluations/synchronize?limit=200`，用于手动投影近期 Tool、Trace 和 RAG 观测；通过 `EAP_EVALUATION_AUTO_SYNC_ENABLED=true` 才启用每分钟自动投影。该投影只写评估结果，不派发控制命令。
- 新增异步投影任务表 `eap_evaluation_jobs`（Flyway V34）和 Worker：`POST /api/evaluations/jobs?limit=200` 提交任务，`GET /api/evaluations/jobs` 查看最近任务，`GET /api/evaluations/jobs/{id}` 查询状态；原同步接口保留兼容，自动同步调度器改为入队而不是占用调度线程执行。
- 前端评估工作台已同步升级：结果表支持点击查看评估证据、分数/置信度/版本/Trace 标识，自动加载关联 Span，并可跳转 Physical Trace 工作台；投影任务区展示队列状态和扫描统计。
- 新增 `GET /api/evaluations/summary?hours=24` 汇总接口，按完整时间窗口返回评估总数、通过率、平均分、平均置信度和各评估器失败分布；前端增加“评估窗口摘要”，避免仅根据最近 100 条结果判断整体质量。
- AgentOps 信号同步现已消费未通过评估结果，生成 `EVALUATION_FAILURE` 运营事项，携带评估器、版本、分数、置信度、Trace/Span 和理由；安全相关评估器默认为 P1，其他评估器默认为 P2。该信号只创建/更新运营事项，不改变模型或工具调用决策。
- 新增 Flyway V32，预置 `TRACE_ACTION_COMPLETION`；新增 V33，预置 `RAG_CONTEXT_COVERAGE`；新增 V34，持久化异步投影任务；新增 V35，持久化数据集样本和 SME ground-truth 标注。新增 `POST/GET /api/evaluations/datasets/{datasetKey}/items` 与 `PATCH /api/evaluations/datasets/{datasetKey}/items/{itemKey}/annotation`，支持 `PASS/FAIL/ABSTAIN`、分数、理由和标注人；样本正文继续遵循 `OFF/HASH_ONLY/REDACTED` 脱敏策略。
- 前端评估工作台已同步增加“SME 标注样本”入口，可按数据集查看样本、选择标注结果、填写理由并回写服务端；创建示例数据集时会同时生成一个可标注样本，形成“采集 → 评估 → 人工反馈 → 回归集”的最小闭环。
- V35 阶段验证基线为 `mvn -q test` 共 99 项测试，0 failures、0 errors、0 skipped；前端 `npm run build` 通过。
- 新增 Flyway V36：评估结果保存执行时 `evaluatorVersion` 与请求指纹，历史结果不再随评估器当前版本变化；相同 `resultKey` 只有请求指纹一致才幂等返回，避免不同输入静默复用旧结果。
- JWT 模式新增评估样本创建与标注权限：创建限制 `OPERATOR/PLATFORM_ADMIN`，标注限制 `OPERATOR/GOVERNANCE_ADMIN/PLATFORM_ADMIN`；标注接口优先使用认证主体作为操作者，并写入 `ANNOTATE_EVALUATION_DATASET_ITEM` 审计事件。
- 当前验证基线更新为：Flyway 迁移至 V36；`mvn -q test` 共 105 项测试，0 failures、0 errors、0 skipped；前端 `npm run build` 通过。
- 新增数据集批量运行与校准接口：`POST /api/evaluations/datasets/{datasetKey}/run` 执行最多 500 条样本，`GET /api/evaluations/datasets/{datasetKey}/calibration` 读取已有结果；返回 TP、FP、TN、FN、Precision、Recall、F1、Accuracy、未标注/弃权/跳过/失败统计，并按评估器拆分。
- 批量校准只会使用仍可安全读取的结构化样本；`OFF`、`HASH_ONLY`、`REDACTED` 内容会被跳过，不会为了评估重新暴露原文。运行结果使用数据集、样本和评估器版本生成稳定 `resultKey`，复用现有结果幂等机制。
- 评估工作台新增“运行数据集校准”和混淆矩阵视图，展示 SME ground truth 与评估器预测的差异；本轮前端源码已同步更新并通过生产构建。
- Agent 详情页新增“评估质量”页签：按 Agent 加载最近评估结果，汇总通过率、平均分、置信度和失败评估器，并可从结果直接跳转到关联 Trace；评估接口不可用时详情页仍保持可用。
- 新增 Flyway V37：运行任务支持记录 `requester`、`problem_resolved` 与 `resolution_summary`，用于区分员工/调用方使用频率、任务完成和业务问题是否真正解决；兼容旧版任务请求与状态更新构造器。
- 管理界面价值总览新增“员工使用与业务效果”面板：按窗口统计活跃调用方、任务次数、日均使用、明确解决率，并按任务类型展示应用场景、完成率、解决率和关联确认价值；未采集身份或解决判断时明确显示“未采集”，不把调用量当作业务价值。
- 运行任务界面新增员工/调用方录入、问题“已解决/未解决”及证据/原因登记；任务 API 前后端字段已同步，旧客户端仍可按原 payload 调用。
- 任务创建接口在 JWT 模式下优先从认证主体自动填充 `requester`（最多 128 字符），避免依赖前端手工输入或被调用方伪造；开发模式仍支持显式传入调用方。价值总览增加“结果判断覆盖率”提示，提醒管理者区分真实业务结论与未采集数据。
- 新增 Flyway V38：业务价值连接器支持 `task_id_path`，可从 CRM / 工单 / 运维结果记录提取 EAP 任务 ID；价值事件列表展示来源记录标识和关联任务，形成“外部业务结果 → 智能体任务 → 价值确认”的可追溯链路。
- 管理界面价值总览新增“业务价值漏斗”：按窗口展示使用任务、执行成功、结果判断、问题解决、业务证据和确认价值的逐级转化率；业务证据仅认外部来源/来源记录，帮助定位智能体价值在使用、执行、解决还是价值确认环节流失。

### 2.15 功能与界面专项审查（2026-08-24）

- 本地审查与 DeepSeek 脱敏第二意见结论一致：当前版本适合演示，不适合直接试点或生产；主要阻断项为 DEVELOPMENT + HTTP、控制命令仍为 outbox 模拟派发、核心长列表缺少服务端分页，以及模拟/已启用能力在拓扑和控制界面上需要更醒目标识。
- 前端当前没有 Playwright/Cypress 自动化测试；后端 84 个测试通过，但 Discovery Agent Go 测试尚未在本机执行，应在 CI 或容器中补齐。
- 后续优先补充：开发模式/模拟数据水印、控制流与数据流启用状态、卡片级错误与重试、拓扑缩放/过滤/聚焦、窄屏导航 tooltip/可访问性、Trace/模型/工具服务端分页和 URL 分区状态。
- DeepSeek 第二意见只接收了脱敏项目摘要，没有发送源码、数据库内容、企业正文或其他凭证；使用的 API Key 应立即轮换。

### 2.16 演示环境与控制派发状态显式化（2026-08-24）

- 后端新增只读 `/api/platform/status`，仅返回安全模式、控制派发是否启用、派发模式和生成时间，不返回凭证、地址或业务数据；JWT 模式下该端点保持公开，便于前端在认证前展示环境事实。
- 前端新增全局“开发演示环境”提示：当后端返回 `DEVELOPMENT` 时明确提示当前未启用 JWT，避免把演示实例误认为生产控制台；状态探测失败时不猜测环境。
- 首页拓扑控制台新增“模拟 outbox / Runtime 派发”标签、模拟派发警告、命令结果说明和最近消息的派发模式；模拟命令成功提示改为“已写入 outbox，尚未触达 Agent”，不再显示为已执行。
- 拓扑请求失败时保留卡片级错误提示和“重试”入口；拓扑刷新同时刷新环境和控制派发状态。

### 2.17 控制命令闭环界面（2026-08-24）

- 首页最近控制消息现在显示命令状态、派发模式、尝试次数、入队/派发/ACK 时间线和服务端结果说明；模拟 outbox 不再被视觉上当作已执行。
- 失败命令提供“重新尝试”操作，复用后端已有 `POST /api/control/commands/{commandKey}/retry` 接口；ACK、达到最大尝试次数和模拟派发限制仍由服务端决定。
- 本轮仅重建腾讯云 `eap-frontend`，后端、MySQL 和控制命令数据未重启或修改；线上控制消息接口、平台状态接口和三个应用容器均回归正常。

### 2.18 拓扑关系视图筛选（2026-08-24）

- 首页拓扑新增“全部关系 / 仅控制面 / 仅数据面 / 有运行证据”筛选；右侧统计同步显示当前视图中的控制边与数据边数量。
- 增加读图提示，明确数据边是工作流/编排关系，不等于真实网络连接；“有运行证据”用于快速排除 `NO_RUNTIME` 等仅配置关系。
- 本轮仅重建腾讯云 `eap-frontend`；线上拓扑接口仍正常返回 4/6 节点、22/2 条边，三个应用容器保持运行。

### 2.19 前端信息层级收敛（2026-08-24）

- 首页优先级与证据指标由多个独立卡片改为连续信息带，使用分隔线表达同组指标；运营队列、拓扑和下方入口的外层容器取消强阴影和重复圆角。
- 全站普通 `panel.el-card` 降低阴影和 hover 变化，减少“卡片套卡片”的视觉噪声；功能内容和表格边界仍保留，避免失去分组和可读性。
- 本轮仅重建腾讯云 `eap-frontend`；线上平台状态、拓扑接口和三个应用容器回归正常。

### 2.20 详情、目录与发现页分区化（2026-08-24）

- 智能体详情页保留档案主面板，五轴状态、关键指标、运行/Trace/模型/工具/治理/身份内容改为连续分区；同组内容使用分隔线和信息带，不再每块都使用独立阴影卡片。
- Agent 目录统计改为连续指标带，目录表格外层弱化为工作区；发现与接入页面的主要统计和表格面板同样取消重复阴影与圆角。
- 本轮仅重建腾讯云 `eap-frontend`；`/`、`/agents`、`/agents/1`、`/registry` 均返回 HTTP 200，平台状态和拓扑接口正常，三个应用容器保持运行。

### 2.21 治理、安全、知识与价值页面分区化（2026-08-24）

- 治理、安全、知识和管理价值页面增加统一页面标识，并将指标组、流程组、图表组和表格组改成连续工作区；外层面板取消重复阴影和圆角，内部表格与表头边界保留。
- 治理执行统计、知识统计、价值统计和安全指标统一采用信息带；安全流程、知识图表、价值归因等双列区域改为分隔区，移动端自动堆叠。
- 本轮仅重建腾讯云 `eap-frontend`；`/governance`、`/safety`、`/knowledge`、`/management` 均返回 HTTP 200，平台状态和三个应用容器回归正常。

### 2.22 左侧工作区导航收敛（2026-08-24）

- 左侧原本平铺的多组导航改为一个可折叠工作区菜单：总览与拓扑、智能体中心、运行与观测、治理与响应、成本与价值、编排与学习。
- 当前路由会自动展开所属工作区，其他分组保持收起；侧栏折叠时使用工作区图标，保留路由导航能力。
- 本轮仅重建腾讯云 `eap-frontend`；主要路由 HTTP 200，`eap-frontend`、`eap-backend`、`eap-mysql` 均保持运行。

### 2.24 系统审查与前后端修复（2026-09-08，本地未部署）

- 完整审查记录见 `docs/SYSTEM_REVIEW_2026-09-08.md`，包含已修复问题、代码证据、剩余优先事项和验证限制。本轮没有提交、推送或改动线上部署。
- 前端修复智能体详情路由复用、数据集/Trace/工作流/编排切换与目录/总览筛选的旧响应覆盖；切换对象时清空旧数据，避免使用新对象 ID 操作旧对象内容。清空全局搜索时正确结束 loading。
- JWT 普通查看者读取正文返回 403 时，仅正文区域显示无权限，智能体详情主体继续可用；正文无权限不再伪装为 0 条数据。
- 16 个路由改为按需加载，业务入口包约 884 KB → 80 KB（gzip 279 KB → 30 KB）；共享 UI/图表包仍存在，不等于首屏整体下降同样比例。
- 内容双人审批在 JWT 模式下验证真实 PLATFORM_ADMIN / DATA_OWNER 角色并使用认证 subject，拒绝同一身份通过不同姓名双签；DATA_OWNER 仅获审批接口权限。业务连接器写入限制为 OPERATOR / PLATFORM_ADMIN，VIEWER 保留读取。
- 修复无在线 Runtime 时重试次数归 1 的问题，最大尝试次数正常生效；非法 JSON、枚举、数字或缺少必填参数返回 400，权限拒绝返回 403。
- 批量评估按单条独立事务执行，单条异常不会回滚整批；异步评估保留原有领取行锁，改为短事务领取、独立投影、短事务完成，RUNNING 和 FAILED 可以持久化查询。
- 数据集样本使用数据库限量查询与 createdAt/id 稳定排序，计数改为 count 查询；连接器初始化异常也会释放运行标记，允许后续重试。
- 运营事项移出最近 500 条源扫描时会回查源记录，避免仍未处理的审批/评估告警被自动关闭；评估证据使用结果保存的评估器历史版本。
- 尚需优先处理：多币种 ROI 混算、工作流仅安全预演却返回执行成功、Runtime 心跳租约、注册令牌并发次数、SSE 有界读取、历史服务端过滤与分页、异步任务崩溃恢复和任务状态机。异步 RUNNING 持久化后尚无 lease/超时恢复，自动运行生产化前必须补齐。

### 2.25 第二轮完善（2026-09-08，本地未部署）

- 详细接口、配置和限制见 `docs/SYSTEM_IMPROVEMENTS_2026-09-08.md`。本轮继续保留全部本地修改，未提交、推送或部署。
- 管理 summary 支持可选 currency，单币种自动识别；混币未选择时整体和 Agent 金额/ROI 返回 null，页面明确提示选币种，不能跨币种计算伪总额。未引入汇率，物理观测汇总原合同未扩展为账务报告。
- 工作流改为明确安全预演：`executionMode=SAFETY_PREVIEW`、`agentsInvoked=false`，通过为 PREVIEW_COMPLETED/CHECK_PASSED。支持 always 兜底、never、approved 和 severity 固定级别比较，未知/缺失/歧义分支拒绝；预演输入不授予治理审批。真实任务执行与完成协议仍待接入。
- Runtime 心跳默认有效 180 秒（`EAP_RUNTIME_HEARTBEAT_TTL_SECONDS`），发现/控制即时排除过期实例，列表即时显示 OFFLINE，后台默认每 30 秒同步运营状态，心跳可恢复。
- enroll 在事务内悲观锁定令牌，双事务并发验证 maxUses=1 只成功一次。
- Gateway 的 JSON、SSE、错误响应均在读取时限制字节数，超限关闭；仍完整缓冲后治理，不是逐 Token 放行。
- 知识/治理列表新增 agentId 和 page 参数，数据库先过滤再限量，按时间/ID 稳定排序；详情读取当前 Agent 最近 500 条并标明范围。
- 任务状态机禁止终态重开/切换终态，重复状态不修改时间；状态与遥测累计共用行锁，终态可补录业务结果。前端同步隐藏无效操作。
- 评估 worker 下一次检查或自动入队前将超时 RUNNING 置 FAILED/结果未知，默认 900 秒（`EAP_EVALUATION_JOB_TIMEOUT_SECONDS`）；迟到完成不能覆盖超时，不自动重试同一任务，不宣称取消或回滚既有投影。评估页面离开后停止轮询和旧页面更新。
- 剩余重点：真实工作流执行闭环、首页读写分离和规模化聚合、完整分页、评估版本快照/历史校准、更完整 worker 租约与取消、生产认证和目标环境验收。

### 2.26 第三轮完善（2026-09-08，本地未部署）

- 详细记录见 `docs/SYSTEM_IMPROVEMENTS_ROUND3_2026-09-08.md`，真实执行合同见 `docs/WORKFLOW_EXECUTION.md`。全部改动保留在本地，未提交、推送或部署。
- 首页 GET 不再触发信号写入，独立同步持久化成功时间/数量，页面显示实际同步时间与新鲜度。覆盖证据改为数据库按 Agent 聚合，避免全局最近 500 条导致漏算；不代表已完成生产负载验收。
- 模型、Trace、工具、正文、知识、治理六类历史在 Agent 详情独立翻页，服务端先过滤再分页、时间/ID 稳定排序；失败保留当前页，空尾页保留上一有效页，切换 Agent 隔离迟到响应，统计使用本页口径。
- 校准运行保存不可变 runId、数据集版本、评估器定义/版本/阈值、当时 SME 标签与预测、指标；不存原始样本正文。历史 API/界面不受后续标注影响，当前 calibration 仍为当前标签预览。
- 工作流新增默认关闭的 `EAP_HTTP_TASK_V1` 真实执行，冻结提交路径/context/config，持久化队列与逐节点任务标识，短事务记录 DISPATCHING 后调用 Runtime。实际地址和请求内容经过安全检查；仅匹配 taskKey 的 SUCCEEDED/FAILED 被视为明确终态。ACK、网络中断、超时等进入 UNKNOWN，停止后续节点，不自动重试。
- 真实执行需服务端令牌与精确主机白名单，默认 HTTPS/禁止私网；全程检查总期限，HTTP 请求受剩余期限约束。未知外部效果不回滚；没有取消、补偿、异步回调、审批后恢复或动态输出传递。协议为 EAP 自有合同，不能视为现有 A2A/MCP Runtime 已兼容。
- 新增 V39 校准历史、V40 信号同步状态及索引、V41 工作流执行日志；V41 steps_json 使用 MEDIUMTEXT。尚未在目标 MySQL 应用。
- 后续优先：真实 Runtime 协议验收、浏览器 E2E/目标 MySQL、工作流恢复与取消、评估发布版本与 worker 租约、规模化负载/留存。

### 2.27 第四轮编辑可靠性完善（2026-09-08，本地未部署）

- 工作流节点/连线写入前锁定所属工作流，避免并发首次 upsert 同一节点造成重复插入；同一源、目标、条件的连线重放返回原记录，空条件按 always 处理。已有历史重复连线未自动删除；不同条件仍保留独立边。
- 节点配置在写库前校验：非 null 配置必须是单个 JSON 对象、UTF-8 不超过 60000 字节；拒绝非法 JSON、数组/标量、空串、多份尾随 JSON，避免数据库异常和错误配置持久化。null 仍兼容未配置节点。
- 工作流画布按加载版本隔离新增节点、连线、预演、真实执行提交的迟到响应，覆盖 A→B→A；新建流程响应可更新目录，但不抢占用户已经切换的画布。节点保存不再对旧选择显示成功提示，离开页面后不更新画布。
- 节点创建/连线创建期间阻止重复请求，失败释放标志；节点标识增加随机部分，并兼容 HTTP 环境缺少 crypto.randomUUID 的情况。
- 回归覆盖 4 线程同节点/连线写入、非法配置不覆盖旧值、中文 UTF-8 字节边界以及前端重复点击/失败/迟到响应。锁仅保护单次写入顺序，不提供多用户编辑版本冲突检测，也不解决真实执行 POST 重放幂等。
- 无新数据库迁移，版本保持 V41；未提交、推送或部署。后续优先仍为真实浏览器与目标 MySQL/Runtime 验收、运行恢复、编辑乐观版本控制及真实执行提交幂等。

### 2.28 第五轮提交幂等与编辑版本（2026-09-08，本地未部署）

- 新增 V42：workflow_nodes.version 与执行 submission_key/request_fingerprint，工作流/提交键唯一索引。Hibernate 乐观版本结合既有工作流行锁，返回保存后的最新节点版本。
- 真实执行可传 requestKey；服务端按完整认证身份、工作流和键去重，冻结 objective/context 指纹。相同参数重试返回原执行，改变参数返回 409；原执行已完成、流程已修改或执行开关关闭时，仍可查询式重放原结果，不重新派发。
- 新版控制台在请求失败后保留提交键，sessionStorage 支持同标签页刷新后复用；成功后释放，下一次明确点击使用新键。改变输入为新提交；存储不可用时仅内存保留，清理存储/新标签页仍须核对历史。无 requestKey 的旧调用保留每次新建行为。
- 节点响应增加 version、更新可传 expectedVersion；冲突返回 HTTP 409。新版控制台拖动/保存携带版本，保留冲突草稿，提供重新加载入口；保存成功只更新版本，不吞掉等待期间继续输入的内容。单纯点击不再写坐标。旧客户端省略 expectedVersion 仍为兼容写入，不宣称全部集成已防止覆盖。
- 新增回归：4 线程相同键只创建一条执行、参数冲突、身份隔离、完成后重放、旧编辑版本拒绝、409 响应、标签页刷新/存储受限重试及草稿保留。配置和协议见 `docs/WORKFLOW_EXECUTION.md`。
- 未提交、推送或部署；真实业务 Runtime、目标 MySQL 和浏览器全流程仍待验收。

### 2.29 0.2.0.0 已提交与部署（2026-09-08）

- 前五轮本地改进已以 `29b77a97ec23864ff9d74831fcdb2c3495d3ab53` 提交并推送到 Gitee `feat/galileo-evaluation-foundation`；未合并 main。此前各轮“本地未部署”描述为当时状态，以本条为最新状态。
- 2026-09-08 20:41 左右，已部署到腾讯云 `/home/ubuntu/eap`，公网 `http://114.132.234.109:8091`。镜像 `eap-backend:29b77a9` / `eap-frontend:29b77a9`，现有配置保留，真实工作流执行 disabled。
- 远端 MySQL 从 V38 升至 V42，失败迁移数为 0；后端与 MySQL healthy，前端运行正常，health=UP，首页信号新鲜度 FRESH，节点 version 字段可读。
- 浏览器冒烟：总览、工作流、评估、Agent 详情加载正常，无控制台错误；详情模型区/历史分页空态可见。未向业务 Runtime 派发任务，未做全业务写操作 E2E。
- 发布前全量重新构建/回归：后端 185 项、前端 34 项与生产构建通过。备份位于 `/home/ubuntu/eap-backups/20260908T123803Z`，保留数据库、旧产物及旧镜像标签。
- 服务器 Compose 现包含 `docker-compose.release.yml` 镜像覆盖文件；后续部署需连同原腾讯云/预构建配置一起使用。完整清单、校验值、回滚材料与验证见 `docs/DEPLOYMENT_2026-09-08.md`。

### 2.30 通信内容采集修复与实测（2026-09-08）

- 已复核线上并完成一次无敏感真实 DeepSeek 调用：新增输入/输出各一条（id 34/35），Agent 4 HelloBoss，同 Trace ID `eap-capture-check-82cb93247f4248fe9b1cfaab036da32b`，记录已真实落库。此前 33 条记录（1 输入/32 输出），最近时间为 8 月 30 日；当前代理及探针在线，不能把缺少新记录一概当作采集服务停机。
- 修复 LiteLLM 1.80.0 使用同步输入回调而原注入器仅实现异步方法的问题；补同步/异步输入、成功、失败方法。修复 sitecustomize loader 丢失资源读取接口导致模型价格表离线回退失败。错误日志仅输出异常类型。
- 代码 `6db452b` 已提交推送；Discovery Agent 1.5.1、注入器 1.2.2 已部署，HelloBoss 后端短暂重建后恢复 HTTP 200。备份 `/var/backups/eap-capture-20260908T130342Z`；重复自动协调不再重建容器，探针/代理/timer active。
- 本地及实际依赖环境 Python 4 项通过，Go 全包测试通过。真实测试使用 102 输入/24 输出 Token（其中 21 推理），预期 EAP_CAPTURE_OK，实际 EAP；已显式记录 ACTION_COMPLETION 评估 id=1、score=0、passed=false。
- 仅证明采集与 Trace ID 关联、显式规则评估；本次 Trace Span 数量为 0，尚无完整 Trace 时间线、自动正文语义评估或 LLM Judge。DEVELOPMENT/HTTP 仍不向控制台返回正文。
- 详细证据与限制见 `docs/CONTENT_CAPTURE_CHECK_2026-09-08.md`。测试只读取本次固定提示的正文，不涉及历史业务正文。

### 2.31 通信时间线与自动调用状态评估（2026-09-08）

- 0.2.1.0 增加 V43：采集 OUTPUT 在独立后台事务中生成派生 MODEL Span 和 MODEL_CALL_STATUS v1 规则结果；按内容 ID 加锁保证并发幂等，最多三次失败后停止，采集不受分析失败影响。
- 仅使用元数据白名单，不复制正文/原始异常/任意 metadata；未知终态只生成 UNKNOWN 时间线，不评分。含内容投影的 Trace 没有显式任务完成信号时不再进行任务完成度启发式判断。
- 评估页新增自动分析积压和失败计数；配置 EAP_CONTENT_ANALYSIS_ENABLED 默认 true，独立于原启发式评估同步开关。
- 后端 192/192，前端 36/36，构建通过；H2 V1–V43 迁移通过。原 Span ID 关联匹配防止时间线备用模型记录重复展示，最终补丁重跑 5 项内容集成测试通过。
- 0.2.1.0 / `b79b7fd` 已推送当前功能分支并部署腾讯云 EAP 前后端，目标 MySQL V43 成功。33 条历史输出全部 DONE，生成 33 份自动调用状态评估，失败和重试为 0。
- 固定测试新增 eap-content-35 MODEL Span，自动调用状态通过与原任务完成度失败并存；浏览器详情/Trace 钻取通过，正文 API 继续隐藏 contentJson。备份 `/home/ubuntu/eap-backups/20260908T145015Z`，未重启 HelloBoss、未新增付费调用。完整证据和边界见 `docs/CONTENT_ANALYSIS.md`。

### 2.32 持久评估扫描与运行指挥大屏（2026-09-11，本地未部署）

- 新增 `/command-center` 独立大屏：1080p 一屏呈现健康、协作拓扑、风险事项、Token、业务记录、真实评估进度；支持全屏、暂停、15 秒刷新、小屏布局与减少动画。
- 七个只读数据源独立刷新，失败保留旧值并标明来源；只在完整刷新后更新时间。明确开发/演示状态、未知值、币种和图形语义。
- Flyway V44 增加持久扫描游标、固定上界和独占租约；工具、Span、知识交互按主键分页循环回访，支持迟到数据重评、失败隔离和过期持有者隔离。
- Trace 任务完成度要求显式完成信号，按完整已接收 Trace 聚合；超 2000 Span、完成信号冲突、多 Agent 歧义跳过，不再根据普通模型调用推断完成。
- 评估任务增加真实阶段/计数和 PARTIAL；前端支持断线继续跟踪、防不确定提交重复执行、结果证据条形图。工作流设计与执行记录分离，步骤时间线来自真实日志。
- 大屏第二轮增加分源成功时间/暂停年龄/45 秒过期提示、恢复立即刷新、拓扑分页与节点详情、指标和异常事项入口；1440×900 与1080p布局复核，新增 4 项回归通过。
- 大屏第三轮增加名称/编号搜索、只看关联及跨页关系计数、默认关闭的20秒轮播、放大/适应与局部滚动；刷新保持选中节点可见。交互/过期时停止轮播，新增4项测试，前端累计51项通过。
- 主控制台改为队列优先的运营工作台，拓扑/指标切换浏览；筛选快捷返回队列。手机事项卡片、窄屏抽屉/弹窗、环境提示进入文档流、侧栏真实配置状态和键盘跳转入口已实现；新增3项回归，前端累计54项通过。
- 设计借鉴 AdminLTE 与 D3 图形表达，实际使用 Vue/SVG/CSS，无新增这些运行依赖。
- 实现与验收边界见 [本轮说明](CORE_RELIABILITY_2026-09-11.md)，外部研究见 [相关产品调研](RELATED_PRODUCTS_RESEARCH_2026-09-11.md)。本轮未解决真实 Runtime 断点恢复、强身份执行边界和完整语义 Judge 体系。

### 2.33 0.3.0.0 已部署（2026-09-11）

- 发布代码 `6996472`，腾讯云8091已切换0.3.0.0；前端buildId与公网一致。运营工作台、运行大屏和持久评估扫描已上线。
- clean package后端202/202、前端54/54；目标MySQL副本V44验证与线上迁移成功，失败迁移0；健康检查和公网接口/浏览器冒烟通过。
- 备份与回退记录见 [部署记录](DEPLOYMENT_2026-09-11.md)。首次启动探测触发自动应用回退，修正重试后再次切换成功；未回退数据库。
- 本地已提交发布代码，未推送或合并主分支。既有DEVELOPMENT/HTTP/控制派发关闭保持。

### 2.34 大屏配色提亮补丁（2026-09-11，已部署）

- 0.3.0.1，前端2ddf166已上线，蓝色背景/面板提亮，文字和拓扑线对比增强，指标增加色条。前端54项与构建通过；公网版本与布局验收通过。
- 后端6996472及V44不变，后端/数据库未重启；解包权限问题自动回退后已修正，并增加Nginx读取权限预检。见当天部署记录。

## 3. 最近验证基线

**当前线上前端补丁：** 0.3.0.1 / 2ddf166（13:29），后端仍为6996472/V44。

**2026-09-11 最新发布基线：** 0.3.0.0、代码6996472已部署；后端202/202，前端54/54，构建通过，目标MySQL V44与公网大屏/工作台验收通过。见 [部署记录](DEPLOYMENT_2026-09-11.md)。以下未发布标记属于历史阶段记录。

**2026-09-11 本地工作区基线（未发布）：** 后端 202/202，H2 V1–V44；前端 54/54，生产构建与 diff 检查通过。大屏 1920×1080 无页面溢出、390px 无横向页面溢出，全屏与暂停交互通过；本地合成 Trace 验证批次 24/24 和 12 个规则结果。尚未验证目标 MySQL V44 或远端部署；下面的发布基线仍为 2026-09-08。

**2026-09-08 最新发布基线：** 0.2.1.0 已部署，代码 `b79b7fd`；后端全量 192/192、最终内容补丁集成 5/5、前端 36/36、构建通过；目标 MySQL V43 与通信分析/Trace 浏览器验收通过。证据见 `docs/CONTENT_ANALYSIS.md`。此前 0.2.0.0 验收见 `docs/DEPLOYMENT_2026-09-08.md`；以下未部署状态为历史记录。

**2026-09-08 第五轮最终基线：** 完整后端 `mvn -q test` 185 项通过（0 failures/errors/skipped），前端 `npm test` 34/34、`npm run build` 和 `git diff --check` 通过；H2 V1–V42 迁移通过。新增提交去重/版本冲突回归，未做目标 MySQL、真实 Runtime、浏览器全站或 Go 验收。

**2026-09-08 第四轮基线（已被第五轮替代）：** 完整后端 `mvn -q test` 182 项通过（0 failures/errors/skipped），前端 `npm test` 29/29、`npm run build` 和 `git diff --check` 通过。H2 V1–V41 通过，无新迁移；未完成真实浏览器、目标 MySQL/Runtime 或 Go 探针验收。

**2026-09-08 第三轮基线（已被第四轮替代）：** 完整后端 `mvn -q test` 174 项通过（0 failures/errors/skipped），前端 `npm test` 23/23、`npm run build` 与 `git diff --check` 通过。H2 Flyway V1–V41 全部通过；包含本地 HTTP Runtime 终态、配置快照、安全目标、超时/失败和无自动重试回归。未接入真实业务 Runtime，未做目标 MySQL、浏览器全站或 Go 探针验收。

**2026-09-08 第二轮基线（已被第三轮替代）：** 后端完整 `mvn -q test` 159 项全部通过（0 failures/errors/skipped）；前端 `npm test` 16 项、`npm run build` 和 `git diff --check` 通过。H2 V1–V38，无新迁移。新增测试包含 HTTP 上游大小边界、真实 JPA 分页/并发、任务状态机和工作流分支。未复核云端 MySQL、未跑真实浏览器全站 E2E。

**2026-09-08 第一轮本地基线（已被第二轮最终基线替代）：**

- 后端完整 `mvn -q test`：116 项通过；追加业务连接器权限回归后，单独重跑 `JwtSecurityIntegrationTests` 通过，当前报告合计 117 项，0 failures、0 errors、0 skipped。首次沙箱执行的 HTTP/gRPC 临时监听被拒，已在允许本机端口的环境重跑完整测试。
- Flyway H2 V1–V38 通过，本轮无新迁移；未重验线上 MySQL。
- 前端 `npm run build` 通过，`npm test` 11 项通过；测试覆盖实际 SFC 函数的逆序响应和正文权限降级，未进行真实浏览器端到端验收。
- `git diff --check` 通过。本机无 Go，Docker daemon 不可连接，未重跑探针 Go 测试。

**以下为历史验证记录，不代表本轮重新核验的线上状态：**

- 后端测试：`mvn -q test`，84 tests，0 failures，0 errors；后端 JAR 已用于腾讯云演示环境重建。
- 前端构建：`npm run build` 通过，Vue TypeScript 检查和 Vite 构建成功。
- 全站 SOC 改版后前端构建：`npm run build` 通过（Vite 6.4.3，TypeScript 检查通过）。
- 腾讯云部署后的 `eap-backend` 与 `eap-mysql` 健康检查均为 healthy，`eap-frontend` 正常运行并映射 `8091:80`。
- 公网 `/actuator/health` 返回 `{"status":"UP"}`；`/`、`/agents`、`/physical`、`/registry`、`/knowledge`、`/management`、`/governance` 均返回 HTTP 200，`/api/dashboard/summary` 返回真实初始化数据。
- 数据库迁移专项测试通过，远端 MySQL schema version 为 25，V25 `shared api key usage reconciliation` 执行成功，失败迁移数为 0。
- Docker 采集器单元测试 2 项通过，覆盖 Boss Agent 识别、基础设施容器过滤和密钥值不外泄；`python3 -m py_compile scripts/eap_discovery_collector.py` 通过。
- `mvn -q -Dtest=EndpointDiscoveryIngestionTests test` 通过；前端 `npm run build` 通过，并包含 `DOCKER` 来源中文显示。
- 腾讯云正式采集上报成功：最后一次 ENDPOINT 运行记录 1 条观察、发现 1 个资产；发现汇总为 1 个 Agent，且无额外误报资产。
- Discovery Agent Go 全包测试和 Linux amd64/arm64 交叉构建通过；腾讯云安装包下载返回 HTTP 200。
- Discovery Agent 1.4.0 Go 全包测试通过；Linux amd64/arm64 的 Discovery Agent 与 Content Proxy 均重新构建，1.4.0 二进制已安装，1.3.0/1.2.1/1.2.0/1.1.0 保留回滚；服务状态显示策略版本 `2026.08.2`、队列为 0。
- 腾讯云部署后的发现管理 API 显示唯一在线探针版本为 1.4.0、队列为 0、内容采集状态为 `DISABLED`；自动协调 timer、Host Observer timer 与 eBPF service 均为 active，`eap-content-proxy@helloboss.service` 仍未运行。
- Host Observer 解析测试覆盖 Linux `ss`、macOS `lsof`、Windows `netstat/tasklist`、IPv6、Agent 筛选和误报排除；eBPF Python 适配器测试覆盖事件解析、IPv4/IPv6、Docker identity、凭证请求和基础设施过滤；腾讯云真实 eBPF 事件成功上报，便携 dry-run/oneshot 仍返回 0 条误报。
- HelloBoss Content Proxy 配置、bridge 地址、内容身份和 DeepSeek 兼容性验证通过；手工启动校验按预期被 EAP 双人审批策略拒绝，服务仍为 `disabled/inactive`。
- 腾讯云内容库主密钥已配置，观测模式为 `REDACTED`；当前内容观测统计仍为 0 条输入、0 条输出，说明尚未采集 HelloBoss 的真实业务正文。
- LiteLLM 注入器在 HelloBoss 真实镜像中完成注册验证，确认是 LiteLLM `CustomLogger` 实例；Python 单元测试、语法检查、Go 自动挂接/内容入口测试均通过。
- 共享 Key 对账 API 在远端返回成功；当前尚未导入真实供应商快照，因此用量池数量为 0，这是预期的未配置状态。
- 前端主要路由 `/`、`/agents`、`/agents/:id`、`/physical`、`/registry`、`/knowledge`、`/management`、`/governance` 返回 HTTP 200。
- 2026-08-24 线上前端仅重建 `eap-frontend` 后健康运行，新首页资源 `index-PNW0A9iJ.js` 已加载，`/api/dashboard/network` 返回 4/6 节点、22/2 边统计。
- 2026-08-24 线上后端新增 `/api/platform/status` 并完成前后端重建；接口确认 `DEVELOPMENT`、`CONTROL_PLANE_OUTBOX`、派发未启用，前端已展示开发演示环境和模拟控制状态，拓扑请求失败时提供卡片级重试。
- 2026-08-24 修复左侧工作区导航首次点击无页面响应：工作区标题现在直接跳转到对应首页，右侧箭头仍可展开子项；前端构建通过并仅重建 `eap-frontend`，后端与 MySQL 未变更。
- 隔离 OTLP 验证已确认 Tool Span 能归因到 Agent，并生成工具调用、输入/输出观测和 Trace 关联记录。
- Agent-centric 详情工作台已完成一次实例链路验收：对 `fin-analyst-local-1` 发送在线心跳并更新负载为 42%，通过 OTLP JSON 上报一条包含模型、工具、MCP、RAG 的 Trace；平台实际生成 1 条模型调用（900 Token）、4 个 Span、2 条工具/MCP 调用、8 条输入输出观测、1 条知识交互和 1 条治理事件，前端代理按 `agentId=1` 均可读取。该验证覆盖“实例心跳 → OTLP 归因 → 详情页数据读取”，但 `http://fin-agent.local:9001` 仍是注册测试地址，不代表真实外部 Agent 进程已执行任务。

## 4. 已知边界和风险

- 真正覆盖企业所有主机和集群仍需要在每台主机部署 Discovery Agent，并为 Kubernetes/eBPF 增加集群级发行包；中央 EAP 无法凭空看到其他机器上的进程。Windows/macOS 当前只有解析器和统一协议，尚无签名安装包与原生服务安装器。
- 无侵入 Prompt/输出观测优先依赖统一模型网关、代理流量或 OTLP；端到端加密且不经过受控网关时，不能可靠获取正文。
- `HASH_ONLY` 只能证明内容关联与变化，不能展示原文；`REDACTED` 需要配置脱敏策略后才适合生产使用。
- 本地 `local` profile 默认使用内存 H2，后端重启后遥测、Trace 和工具调用历史会清空；正式部署应使用 MySQL。
- 发现资产、Runtime、OTLP Span 和网关调用必须尽量携带稳定 Agent 身份，否则只能通过 endpoint、进程、工作负载或启发式身份图谱关联。
- 前端 HTTP 200 和构建通过不等于所有业务按钮完成端到端外部系统验证；涉及真实模型、Kubernetes、eBPF 和 Runtime 控制时仍需在目标环境验收。
- 当前实例验收使用本地开发模式写入的合成 OTLP 测试数据；需要部署可访问的真实 Agent Runtime，并让其实际调用模型和工具，才能完成生产级执行验收。
- 腾讯云演示实例尚未启用 JWT/OIDC 和 HTTPS，且 Flyway 11.7.2 对 MySQL 8.4 输出“高于已验证版本”的兼容性提示；当前迁移和运行正常，但正式生产应完成版本兼容矩阵验证。
- Docker 发现仍属于启发式识别；生产环境应优先为自研智能体添加显式 Agent 标签，并在容器重命名、Compose 项目迁移或密钥环境变量调整后复核身份合并结果。
- 当前腾讯云仍是明文 HTTP 演示环境，因此探针安装显式开启了 insecure HTTP；生产环境必须切换 HTTPS，并将 `EAP_DISCOVERY_AGENT_REQUIRE_HTTPS` 保持为 `true`。
- 内容代理未经双重审批不会启动；即使允许外部大模型分析，也必须根据数据分类、供应商地域/留存条款和企业出境政策做路由。
- 业务正文读取新增硬约束：JWT 模式下只有 `GOVERNANCE_ADMIN` 或 `PLATFORM_ADMIN` 可以读取 `/api/observability/content`；DEVELOPMENT 模式即使误设为 `REDACTED`，接口也会移除 `contentJson`，只返回哈希、大小和分类元数据。
- 当前腾讯云 DEVELOPMENT/HTTP 实例不会向公网返回业务正文。要查看脱敏正文，必须先启用 HTTPS + JWT/OIDC，再由不同人员完成平台管理员与数据负责人审批。
- 共享 Key 只能提供供应商账户级总量；Agent 级精确归因仍依赖网关、OTLP 或“每 Agent 一个 Content Proxy”在调用时写入身份。没有调用级身份时，EAP 会保留未分配差额，不会猜测分摊。
- 真实供应商用量尚未自动拉取；下一步需要确定供应商、计费时间粒度、币种和只读管理凭证，再实现对应 Usage/Billing 适配器。多个 Agent 共用的真实 API Key 不能作为 Agent 身份。
- “用户无感”指业务用户和 Agent 开发者无需执行命令或修改代码：运维预先声明目标后，timer 会在合法授权生效时自动挂接。首次自动挂接仍会重建一次目标容器，这是基础设施变更。腾讯云后端基础开关已开启，但当前内容双重授权为 `DISABLED`，所以有效 `contentAutoAttachEnabled` 仍为 false，协调器不会触碰 HelloBoss。

## 5. 下一步优先级

1. 完善发现身份图谱：提高 endpoint、进程、Kubernetes workload、模型调用和正式 Agent 的自动合并准确率，并提供冲突复核。
2. 完成端到端演示链路：自动发现 Agent → 采集 Trace → 查看模型与工具调用 → 触发治理策略 → 统计 Token、成本和 ROI。
3. 扩展发行包：Kubernetes Helm Chart、Tetragon/eBPF 适配器、Windows/macOS 服务安装器和服务网格接入说明。
4. 强化无侵入观测：透明代理、OpenTelemetry Collector 处理器、常见 Agent 框架自动探针和网关旁路元数据采集；为实际使用的模型供应商增加 Usage/Billing 只读适配器。
5. 完善评估数据集校准：增加已发布版本只读与按版本比较；服务端分页和不可变校准历史已在本地实现，需补目标环境验收。
6. 增加前端端到端自动化测试，覆盖分页、筛选、Trace 钻取、治理审批、控制命令、评估标注和错误状态。
7. 在生产部署中启用 MySQL、JWT、机器凭证、保密信息 fail-closed、审计留存和数据生命周期策略。

## 6. 开发与交付约定

- 本地后端：`cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=local`
- 本地前端：`cd frontend && npm run dev`
- 后端验证：`cd backend && mvn -q test`
- 前端验证：`cd frontend && npm run build`
- 一体化部署：`docker compose up -d --build`
- 腾讯云源码部署：`docker compose -f docker-compose.tencent.yml up -d --build`
- 腾讯云预构建产物部署：`docker compose -f docker-compose.tencent.yml -f docker-compose.tencent.prebuilt.yml up -d --build`
- 远端仓库：`https://gitee.com/wallemit/eap.git`
- 功能实现、测试结果或重要风险发生变化后，应同步更新本文件。

## 7. 前端组件选型调研（2026-09-11）

- 已完成[组件选型深度调研](research/component-selection/REPORT.md)，附 35 个包的公开 npm 元数据和 CSV；这是研究成果，未安装新组件或变更部署。
- 核实 AdminLTE 已有官方 Vue 3 包；建议先验证 KPI/信息盒/卡片与现有 Element Plus 的样式隔离。保留 ECharts，D3 按模块增强交互；Vue Flow、G6 分别作为流程编辑与复杂拓扑候选。
- 注意最新 vue-echarts 要求 ECharts 6；当前工程声明 ECharts 5。Trace 瀑布图需明确 span 开始/结束时间语义，调用流向图需真实关联边权，不能从独立聚合总量推导。所有推荐尚待 PoC 验收。

### 首批组件本地实现

- 已接入 AdminLTE Vue 0.7.0 的 SmallBox、InfoBox、Card，以及 D3 zoom/selection；大屏支持连续缩放、拖动、定位选中和键盘视图控制。采用组件局部样式，没有加载全局 Bootstrap 主题。
- 用户明确希望保留持续流动动画；展示为潜在调用路径示意，不等于实测流量。暂停刷新、隐藏页和减少动态偏好继续控制动画。
- 本地构建与 54 项测试通过，完成桌面/窄屏交互检查；尚未部署。详情见 [组件接入验证](COMMAND_CENTER_COMPONENTS_2026-09-11.md)。

## 8. 持续优化执行（2026-09-11）

- 用户授权“记录要修复或者优化的地方，逐一实现”。统一跟踪 [OPTIMIZATION_BACKLOG.md](OPTIMIZATION_BACKLOG.md)，18项分优先级、依赖、验收和部署状态。
- 本轮完成 OBS-01/02/03 与 UI-02：V45生产端Span起止时间、Trace时间诊断/失败筛选、请求竞态保护、路由多根动画警告修复。后端203/203、前端60/60和构建通过；未部署。详见 [交付记录](TRACE_DIAGNOSTICS_2026-09-11.md)。
- 旧Span不回填时间，500条读取上限明确提示；当前图只代表已加载观测范围。下一项为稳定任务关联，再推进Runtime查询合同。

- 第二轮：OBS-04第一阶段任务证据API与诊断抽屉，显式taskId关联四类证据，隔离Agent冲突并支持Trace往返。大屏按用户要求突出业务结果与登记收益，后端新增业务结果三态计数。208后端/62前端测试通过，未部署；[交付记录](TASK_EVIDENCE_2026-09-11.md)。OTLP任务归属、工作流taskKey映射仍待实现，未将OBS-04整体标完成。

## 9. 最新部署（2026-09-11）

0.3.1.0 / 395edef 已部署腾讯云 http://114.132.234.109:8091/ ，前后端镜像均为 395edef，数据库 V45。此前第7/8节“本地/未部署”为历史阶段记录，本次包含 AdminLTE/D3、Trace诊断、任务证据第一阶段和业务价值大屏。后端208/前端62测试及构建通过；公网接口、1920×1080大屏缩放和任务诊断抽屉验收通过。备份与回退信息见 [部署记录](DEPLOYMENT_2026-09-11_0310.md)。OBS-04整体、Runtime合同等待办仍未关闭。

### 长列表分页（2026-09-11）

运行任务、审计、物理观测、治理、管理明细共17张表及工作台事项桌面/手机卡片接入统一分页，默认10条，可选20/50。筛选/上下文变化重置，刷新保留页码，数据缩减时校正越界；数量标记为已加载，不宣称服务端全部历史。保留Agents及评估已有服务端分页。前端62项测试及构建通过；28条临时合成数据浏览器验收翻页、刷新、筛选和390px无溢出，未写线上数据。

分页前端已部署：2bcddcd / frontend 0.3.3，buildId 2026-09-11T07:19:32.521Z。仅切换前端，后端仍395edef且healthy。公网审计列表实测每页10条、下一页11–20条，控制台无错误。回退配置保留于 /home/ubuntu/eap-backups/pagination-2bcddcd/docker-compose.release.yml。

### 历史分页第二阶段（2026-09-11）

任务和审计新增数据库分页API、完整条件计数、关键词/状态/时间窗和排序；前端请求竞态保护，旧列表API保留兼容。V46增加查询索引。后端211/211、前端62/62及构建通过；详见 [历史分页交付](HISTORY_PAGINATION_2026-09-11.md)。其他列表仍待逐步接入服务端分页。

历史分页已部署3c148c3，MySQL V46；公网任务/审计分页接口和页面通过验收，审计141条可全部分页检索。备份和回退见上述交付记录。

### 治理和价值历史分页（2026-09-11）

治理事件与价值记录接入服务端分页；按observedAt检索，保留路由上下文、共同贡献归属和币种过滤。213后端/62前端测试通过，无数据库迁移。详见 [交付记录](EVENT_HISTORY_PAGINATION_2026-09-11.md)。物理观测和预检决策等仍待完善。

治理和价值历史分页已部署3c4ae7e；公网页面与只读接口验收通过，当前两类线上事件为空，多页验证以本地505/111条事务测试为准。数据库仍V46，备份和回退见交付记录。

### 物理观测与预检历史分页（2026-09-11）

模型调用、内容观测、OTLP Span、工具调用和预检决策已接入服务端分页；保留Trace钻取、审批刷新、内容权限和开发脱敏。V47补内容/工具全局时间索引。216后端/62前端测试及构建通过，详见 [交付记录](OBSERVATION_HISTORY_2026-09-11.md)。Trace诊断仍声明500条加载范围，不将历史分页等同完整证据链。

物理观测与预检历史分页已部署d887b9c，MySQL V47，后端UP；五类公网接口及跨页Trace钻取通过。备份/回退和线上数据覆盖范围见交付记录。真实执行闭环、完整任务归属和业务价值核验仍未完成。

### 普通用户使用路径优化（2026-09-12）

首页增加待办/任务/成果入口；任务使用中文执行进展与独立业务结果，技术管理操作折叠。详情优先说明任务、结果和下一步，支持业务结果确认，证据默认折叠。前端62项测试及浏览器手机交互通过，详见 [交付记录](USER_EXPERIENCE_2026-09-12.md)。

### Agent Substrate 运行时集成 PoC（2026-09-27，未提交）

- 用户决定把 Agent Substrate 作为 EAP 的真实运行时，并要求能在前端部署 agent。设计见 [SUBSTRATE_RUNTIME_INTEGRATION.md](SUBSTRATE_RUNTIME_INTEGRATION.md)。
- PoC 环境：独立的 Substrate 两节点集群（10.186.133.66/.44，经跳板机访问；该集群不属于腾讯云演示环境）。EAP 部署在 `eap` 命名空间；访问脚本与部署清单在 `K8Substrate/tools/`。
- 新增 `protocol=SUBSTRATE`：
  - 控制命令 PAUSE/RESUME/REVERT/RUN_HEALTH_CHECK 通过 gRPC 调用 Substrate Control API；
  - 工作流任务经 atenet-router 派发，派发前先唤醒 actor；
  - SUBSTRATE 实例不参与心跳过期。
  - 实测控制、派发、挂起后状态保留均通过。
- 后端测试 228 项中 227 项通过。唯一失败项在 HEAD 上同样失败（JWT 视图排除种子 Agent），属原有问题，未修复。
- 同日完成前端部署：DeepSeek 经网关接入，平台通用 Agent Runner，Agent 详情“部署与运行”Tab（零代码/已有镜像、预检、进度、重试、实例管理、试运行），V48 迁移。实测全链路通过；后端 233/234（原有失败 1 项）、前端 66/66 通过。
- 已完成：删除实例同时移除登记（不再产生离线事项）；Runner 上报 AGENT Span，并与网关的模型调用共用 traceId；v2 版本发布验证通过。
- 同日完成：长任务（路由器 `--route-timeout=20m`，600 秒任务 SUCCEEDED）；JWT 部署权限测试（401/403/OPERATOR）；源码构建部署（kaniko Job、按 digest 固定、构建日志、从失败步骤重试），示例仓库部署成功。限制见集成设计文档第 9 节：GitHub 网络、WORKDIR 被忽略、普通 HTTP 只转发 80 端口。

### 交接：Substrate 集成的下一步（2026-09-27，用户改用 Codex 编写代码）

- **已提交（本地，未推送）：** `e45d522`（Substrate 运行时与前端部署）、`1789c55`（源码构建、长任务、JWT 部署权限测试）。分支为 `feat/galileo-evaluation-foundation`；推送前需用户同意。
- **进行中、未提交、未测试：JWT 登录模式，要求前后端一致。** 目前只改了 3 个文件（见 `git diff`）：
  - `SecurityProperties`：新增 `oidcClientId`、`oidcScopes`（`EAP_SECURITY_OIDC_CLIENT_ID` / `_SCOPES`）。
  - `SecurityConfig`：同时配置 jwk-set-uri 与 issuer-uri 时，从 jwk-set-uri 取公钥，但仍校验 `iss` 等于 issuer-uri（适用于 IdP 公网地址后端访问不到的情况）；已放行 `GET /api/platform/me`，但**该接口尚未实现**。
- **计划中的设计（尚未实现）：**
  1. **后端是唯一的权限来源。**
     - `/api/platform/status` 增加 `oidc`（issuer、clientId、scopes）；
     - 新增 `/api/platform/me`，返回当前 subject、角色以及能力列表，例如 `deployAgents`、`operateInstances`、`viewContent`；
     - 能力按 `SecurityConfig` 的实际规则计算。
     - 前端不解码 JWT、不另写一套角色逻辑，只根据 `/me` 隐藏或禁用按钮；后端依然按原规则拦截。
     - 需要测试保证 `/me` 给出的能力与 `SecurityConfig` 的实际拦截一致。
  2. **前端登录：**
     - 用 status 中的 oidc 配置走授权码 + PKCE 流程；
     - 新增 `/auth/callback` 路由；令牌仍按现有 `api/auth.ts` 的做法存在 sessionStorage；
     - 保留手工粘贴令牌作为兜底；401 时引导重新登录。
  3. **PoC 身份提供方：** 在集群部署 Keycloak（realm `eap`），为 VIEWER、OPERATOR、GOVERNANCE_ADMIN、PLATFORM_ADMIN 各建一个测试用户，然后把 `eap-poc.yaml` 切到 `EAP_SECURITY_MODE=JWT`。
  4. **切换前：** `AgentOpsIntegrationTests.excludesDevelopmentSeedAgentsFromJwtProductionViews` 的原有失败（JWT 视图仍返回 7 个种子 Agent）需要先确认原因，因为它直接关系到 JWT 模式。
- **编译与测试只在远程 PoC 集群上进行**（用户要求，不在本机安装、编译）。步骤与脚本见 `…/联通/软件研究院/2026/K8Substrate/tools/eap/README.md`；集群环境与限制见本文件上一节，以及 `docs/SUBSTRATE_RUNTIME_INTEGRATION.md` 第 9 节。
- **安全：** DeepSeek key 只存在集群 Secret `eap-gateway` 中，不得写入代码、文档或部署清单。

#### Codex 进展（2026-09-27）

- 已实现第 1、2 步：status 发布 OIDC 配置；`/me` 返回身份、角色及能力，DEVELOPMENT 全能力开启；`PlatformPermission` 与 SecurityConfig 共用角色规则。
- 前端增加无新依赖的授权码 + PKCE 登录、一次性 state/有效期校验、`/auth/callback` 和权限 store；令牌仍存 sessionStorage，保留手工入口，401 清理权限并提示重登。Substrate 发布、重试、实例控制及聊天只依据 `/me` 能力门控，不解码 JWT 或按角色名授权。
- 第 1、2 步及 JWT 逐角色接口对照、DEVELOPMENT/status、PKCE、能力门控和登录竞态测试已完成远程测试，并提交为 `20f4703`（用户交接确认）；真实 Keycloak 联调仍待完成。
- 本轮先处理第 4 步，根因是**跨 Spring 测试上下文共享 H2 库导致数据串扰**：`application-test.yml` 固定 `jdbc:h2:mem:eap_test` 且 `DB_CLOSE_DELAY=-1`；`WorkflowExecutionIntegrationTests` 的七个测试各在 `@BeforeEach` 提交一个默认 `PRODUCTION` 的 `WF-*` Agent，没有回滚/清理，AgentOps 测试因此读到 7 条正式记录。V2 只有三个种子 Agent，V29 已正确标记 `DEMO`，`AgentOpsService.visibleAgent` 的 JWT 过滤也正确。零值断言对仅含种子的隔离测试库成立，不能用于已有正式 Agent 的 PoC 集群。
- 修复测试配置：H2 名改为 `eap_test_${random.uuid}`，隔离各 Spring 上下文。保留原零值断言，增加“三个种子均为 DEMO”的前置检查及“混合种子与正式 Agent 时 JWT 仅返回正式 Agent”的回归测试；恢复测试前的 security mode。未改业务过滤或历史迁移。远程需先运行 Workflow 后运行 AgentOps（Surefire `reversealphabetical`）复核原污染路径，再执行迁移检查及全量测试。
- 第 3 步已提供 `deploy/substrate-poc/keycloak.yaml`：Keycloak `26.7.4`（2026-09-27 核对官方发布与下载页）、`eap-auth` 命名空间、单副本 dev-file H2、realm-import ConfigMap 和 ClusterIP Service。`eap-console` 为无 secret 的 public client，强制授权码 + PKCE S256；配置 localhost:5173 的精确回调/origin、五个 realm role 和四个无 credentials 用户。管理员凭证仅引用 Claude 预先创建的 `keycloak-admin` Secret。
- 固定 `KC_HOSTNAME=http://localhost:8180`，启用动态后端通道；EAP 使用该公开 issuer 与集群内 JWKS 地址。realm roles 映射为顶层多值 `roles`；客户端显式给 access token 添加 `aud=eap-api`，对应 `EAP_SECURITY_AUDIENCE=eap-api`，不依赖 `azp=eap-console` 或 Keycloak 默认 audience。
- 中文操作交接见 [Substrate PoC JWT 部署说明](../deploy/substrate-poc/README.md)：包含 apply 顺序、Secret 的两个键、无文件保存凭证的交互命令、kcadm 设置四个随机密码、SSH 隧道、完整 EAP 环境变量、远程回归与角色验收。使用 emptyDir，Pod 替换会丢失密码/签名密钥，需重新设密和登录；已有 realm 的启动导入会跳过。
- **本轮第 3、4 步未编译、未测试、未部署**：仅静态审阅、YAML/内嵌 JSON 解析与 `git diff --check`。本机未运行 mvn/java/npm/node/docker/kubectl，未连接集群，未提交/推送/切分支，未写入任何实际密码或密钥；待 Claude 远程测试并应用清单。


#### Codex 集群验收与 JWT 上线（2026-09-27）

- 已完成 `CODEX_NEXT_TASK.md` 的远程测试、Keycloak 部署、JWT 切换、真实逐角色登录与权限对照、8180 隧道。编译、测试、构建及部署均在 PoC 集群执行；本机未安装依赖或运行构建，未 commit/push。
- 后端全量 **254/254**，failures/errors/skipped 均为 0；明确先 Workflow 后 AgentOps 的 reversealphabetical 专项 **11/11**，Flyway+AgentOps **5/5**。原 H2 数据串扰修复已远程验证，前面“尚未测试”属于历史状态。前端 **82/82** 与生产构建通过。
- 修复 K8Substrate `tools/eap/fe-test.sh` 的管道退出码问题：测试/构建先检查原始退出码，再展示日志尾部，避免 tail 掩盖失败。
- Keycloak 26.7.4 已部署到 `eap-auth`，realm eap、public client eap-console，授权码+PKCE S256；未开启 console direct grant。管理员密码在节点用 openssl 随机生成，四个账号密码也在节点生成，经 kcadm 设置，分别仅存集群 Secret `keycloak-admin` / `eap-test-users`。
- 外部仓库与镜像站曾发生 TLS/响应超时；镜像已转存至集群本地仓库，运行镜像固定为 `localhost:5001/keycloak@sha256:3d911baa186f352563854039b95f21a7e2c01c76b527fdc64f24a0885b927bdf`。环境补丁为 K8Substrate `tools/eap/keycloak-image-poc.yaml`；转存采用 ctr 的 `--local` 推送模式。
- EAP 已切 JWT：issuer `http://localhost:8180/realms/eap`，JWKS 走集群 Keycloak 服务，audience eap-api，roles claim 为 roles，clientId eap-console，origin 为 `http://localhost:5173`。浏览器隧道与集群发现文档的 issuer 一致。
- 已核对运行中的 imageID：backend `sha256:199bd8afd5d972c73d5d28110f85d030019d276b5d580bddb143ebf1077b759e`；frontend `sha256:4a337fee3c2c7d524299ce9b77d8d9b55a6c05b9cb69a513e9cab6a6a7b3f983`。前端 `/version.json` buildId 为 `2026-09-27T09:56:34.547Z`。
- 四账号在节点以独立 Cookie 会话完成真实授权码+PKCE S256、state 校验、code 兑换、token endpoint CORS、JWT iss/aud/roles 验证；`/me` 能力与下表真实 API 响应一致。部署 POST 使用已有版本的相同 requestKey 和配置做幂等重放，返回同一 deploymentKey，前后部署总数未变；未创建/删除/修改 demo Agent 或操作现有 actor。

| 账号 / 角色 | `/me` | Agent 列表 GET | 部署 POST 幂等重放 | 内容分页 GET |
| --- | --- | --- | --- | --- |
| viewer / VIEWER | 200 | 200 | 403 | 403 |
| operator / OPERATOR | 200 | 200 | 200 | 403 |
| governance / GOVERNANCE_ADMIN | 200 | 200 | 403 | 200 |
| admin / PLATFORM_ADMIN | 200 | 200 | 200 | 200 |

- 匿名 `/me` 返回 401；四账号的真实签名 ID token（aud=eap-console，不含 eap-api）均被 EAP 返回 401。PLATFORM_ADMIN 具有本次全部能力，没有应当返回 403 的角色禁止项。缺少 PKCE 或使用 plain 的授权请求均返回 302 回调且 `error=invalid_request`、无 code。
- JWT dashboard `demoDataIncluded=false`，`agentCount=4`，说明正式 Agent 保留可见；未错误套用隔离测试库的零数量断言。
- 可复验脚本在 K8Substrate `tools/eap/keycloak-users.py`（节点生成/恢复密码）及 `verify-jwt.py`（节点真实登录/权限检查）；均仅输出脱敏结果。`tools/ate-ui-tunnel.sh` 增加 8180，支持只补开指定服务，现有 5173 隧道保留。
- **边界与下一步：** 本轮真实登录使用节点 HTTP 会话，未逐角色操作真实浏览器验证按钮显隐、页面 callback 与 401 重登交互；前端这些逻辑仅由远程单元/组件回归覆盖。Keycloak 仍为 PoC emptyDir/dev-file，替换 Pod 后需运行 keycloak-users.py 从 Secret 恢复密码并重新登录；本轮切到本地镜像后已实际恢复并复验。生产持久化/TLS/网关注入身份仍待后续任务，不属于本轮。


#### Codex 真实浏览器验收与修复（2026-09-27）

- 已补齐上一轮未做的真实浏览器验收。浏览器运行于 PoC 集群临时 Pod `eap/eap-browser-qa`，使用 Chromium 152.0.7977.82 + Playwright 1.62.1；本机未安装依赖、运行浏览器测试或构建。测试使用现有 Secret，经进程 stdin 将账号密码传入浏览器，不输出/保存凭证、令牌、HAR 或浏览器存储快照。
- 浏览器发现并修复三处问题：① Agent 目录“登记 Agent”未接权限；② Agent 详情“暂停/启用”未接权限；③ 初始懒加载路由尚未就绪时，App 提前弹出登录窗口，遮挡异常 callback 的“重新登录”。前两项统一读取 `/me` 的 deployAgents 能力，按钮/表单与提交函数均设检查；第三项通过等待 router.isReady() 后再判定认证入口修复。未新增前端角色表或 JWT 授权解析。
- 新增三项回归覆盖无能力/撤销能力后的登记与生命周期写入阻断，以及初始 callback 路由竞态。完整前端 **85/85**、生产构建通过；本轮未改后端，也未重复后端测试，上一轮 254/254 仍为后端基线。
- 修复已部署到 PoC 前端，运行 imageID 为 `sha256:d0bee1dcb25e4adfaa8c03e7efc8d210f27a03cf3e64332216c35accb52b1264`，buildId `2026-09-27T10:29:27.683Z`。后端与 Keycloak 配置保持上一轮状态。

| 账号 | 真正 PKCE 登录/回调/刷新 | 登记与暂停/启用 | 发布部署 | 实例新建、试运行、唤醒、休眠、删除 | 退出 |
| --- | --- | --- | --- | --- | --- |
| viewer | 通过 | 禁用 | 禁用 | 禁用 | 通过 |
| operator | 通过 | 可用 | 可用 | 可用 | 通过 |
| governance | 通过 | 禁用 | 禁用 | 禁用 | 通过 |
| admin | 通过 | 可用 | 可用 | 可用 | 通过 |

- 四角色 UI 状态均与实时 `/me` 一致；每个现有实例操作按钮都逐一检查。测试不点击业务写入按钮，不创建/删除 Agent、版本或 actor，不发送模型请求。
- 无 transaction 的异常 callback 显示明确错误、清除 URL 查询参数，并可直接点击“重新登录”进入真实 Keycloak 表单；成功 callback 消费一次性 transaction，返回登录前的页面且无认证弹窗遮挡。
- 保留 viewer 的真实访问令牌并等待服务端到期后，通过“刷新观测”得到真实 API 401；确认 sessionStorage 令牌被清除、认证弹窗出现。重新点击企业登录，经 Keycloak SSO 完成新授权码回调，恢复 `/me` 200 与 viewer 只读按钮状态，再验证退出后的 `/me` 401。未伪造过期时间、拦截请求或缩短身份提供方配置。
- 无未捕获页面 JavaScript 异常。未登录页面读取 API 的 401、viewer/operator 读取内容观测的 403 属预期拒绝；后者仍会显示现有权限提示，不代表部署按钮触发了写入。此轮没有扩大到全站每个管理页面。
- 可复验脚本位于 K8Substrate `tools/eap/browser-qa-pod.yaml`、`browser-jwt.mjs`、`browser-jwt-run.py`；脱敏结果及四角色截图位于 `artifacts/eap-jwt-browser-20260927/`。临时浏览器 Pod 在证据导出后清理，账号与 Secret 不变。未 commit/push。


#### Codex 串行多 Agent 集群验收与修复（2026-09-27）

- 用户询问是否完全对接 Substrate，并授权继续。此轮完成**串行工作流的多 Agent 结果交接与证据闭环**；Team/Plan 当前只有登记和分配，并无执行 API，未将它标为完成。
- 执行引擎原来丢弃 Runner output。现在任务节点可在 configJson 配置 `inputFrom:["draft"]`；提交时仅允许引用所选路径上的前序任务，拒绝保留上下文 `eapPreviousResults` 伪造、重复/前向/控制节点引用。按引用合并真实输出，再做治理检查；不改变已冻结的分支路线。
- 成功 output 上限 16KB，整份合并任务请求上限 60KB。缺失/JSON null 上游输出或合并超限在下游派发前停止；超限响应进入 UNKNOWN，不截断、不自动重试。结果和 SHA-256 保存在既有 steps_json，无数据库迁移。普通提交/列表/详情隐藏原文；`GET /api/workflows/{id}/executions/{runKey}/outputs` 共用 PlatformPermission.VIEW_CONTENT。
- 实测发现上游完成后占用资源，导致下游唤醒 RESOURCE_EXHAUSTED。增加任务节点 `suspendAfterTask:true`：先持久化成功结果，再确认 actor SUSPENDED，随后才派发下游；休眠未确认则停止工作流并保留成功步骤。失败或结果未知不自动休眠。默认关闭，仅用于专用 actor；不是后台空闲休眠/全局容量调度或跨工作流互斥。
- 实测又发现 JWT 下 OTLP 上报无凭证而被拒绝：PoC 新增专用 Secret `eap/eap-observability`，节点随机生成/复用；清单只有 Secret 引用，DeploymentService 向新 ActorTemplate 注入 EAP_OBSERVABILITY_TOKEN。Runner 任务在终态响应前等待一次有界、尽力而为的 Span 上报，避免立即休眠冻结后台上报；聊天仍异步，上报失败不重试已成功任务。
- 最终后端全量 **264/264**（基线 254 + 10 项新回归；另扩展六角色内容权限及部署凭证注入断言），failures/errors/skipped 全 0。Runner HTTP 行为测试 **2/2**，race 因节点缺少 gcc 未能启动。全在远程执行，本机未编译/安装依赖；本轮未改前端，未重跑前端 85/85 基线。
- 已部署并核对 backend imageID `sha256:c8e89e7e1bd186d155ebdd0bf23f00368f73e40ae8ad052cd67685f013f73ef1`；Runner 固定 `localhost:5001/eap-agent-runner@sha256:4f826ed18915193b7669ea5468795b800ddd2945411f613fad39336ae16788c2`。前端仍为上一轮浏览器验收版本。
- 实际测试夹具：Agent 8（draft）和 9（review），最终实例 `eap-agents/a8-v2-7f14b1` / `eap-agents/a9-v2-6b54c7`，均 SUSPENDED。只替换了本轮创建的 v1 actor；原有 Agent 4–7 及其演示实例未操作，旧模板未升级，不能声称它们已恢复 JWT 下的 Trace 上报。
- 工作流 5，最终运行 `wf-exec-06187f5b-401e-4b09-b35b-b83f0cc17ef1` SUCCEEDED。生成者只在自己系统提示词中持有合成标记，复核者从真实上游 output 取得并复述；初始 context 和下游系统提示词没有该标记。两个 output 的 SHA-256 与持久化原文一致。
- 两步 taskKey 分别关联 Agent Span 与网关模型调用：draft Trace `be534f61808e9b7f4503f712214acba6`，321 tokens；review Trace `f7a3225d9f1660120ef0cfbc84af41bd`，322 tokens。最终成功运行 2 次模型调用；包含前置失败/无 Trace 尝试共 5 次。相同 requestKey 重放不新增模型调用。
- 真实四角色：执行元数据均 200 且 output 隐藏；outputs 接口 viewer/operator 403，governance/admin 200。两个 actor 由工作流自动休眠，脚本最后另做 PAUSE 兜底。
- 证据：K8Substrate `artifacts/eap-multi-agent-20260927/{README.md,report.json}`。脚本 `tools/eap/verify-multi-agent.py`、`configure-observation.py`，节点状态 `/root/eap-multi-agent-evidence.json`（不含凭证）；保留前置失败记录，不覆盖为成功。未 commit/push。
- **下一步：** 将 Team/Plan 接入已有执行引擎，冻结有序步骤/成员映射，持久化 Plan→workflow/runKey 关联与幂等提交，禁止重复派发并回写状态。之后推进结果 UI、并行编排、后台空闲休眠/容量与回滚验收、生产身份注入和 Keycloak 持久化/TLS。本轮只证明专用 actor 串行协作，不宣称完整生产集成。


#### Codex Team/Plan 自动执行（2026-09-27）

- Team/Plan 已接入现有串行工作流引擎：冻结有序步骤、成员及上下文，V49 持久关联 workflowId/runKey；每步使用自身标题作为 taskObjective，自动将前一步真实 output 交给下一步。
- 每 Plan 仅一次执行；行锁保证并发相同请求复用同一运行，换 key/参数返回 409。提交后步骤与生成的工作流不可修改，不能绕过 Plan 直接再次执行工作流。生成快照、入队及关联同事务，校验失败全部回滚。
- 后台每 2 秒投影计划/步骤状态，不依赖浏览器打开；失败、治理阻断、未知与未执行步骤分别保留，不把人工 assignment 当作真实执行。前端提供提交、专用实例完成后休眠选项、刷新与时间线，按 /me.runWorkflows 授权。
- 远程后端 **272/272**、前端 **89/89** 及生产构建通过，失败/错误/跳过均 0。新增 8 项后端集成测试与 4 项前端回归；涵盖并发提交、冻结、事务回滚、失败/未知/治理状态及能力撤销。本机没有编译或安装依赖，未 commit/push。
- 已部署 backend `sha256:e9baece7f09f13b2a34f621023140e2052805e66fec346ad4b9bcfa33aae731e`、frontend `sha256:4e60e2a5c1211955d1f76704612434fd2760fe176dfdf05bd3091c9c153a3ba7`，核对实际 imageID；前端 buildId `2026-09-27T12:25:12.941Z`。MySQL V49 success=1。Runner 沿用上一轮固定 digest 4f826ed1。
- 真实夹具 Agent 10/11，Team 2、Plan 2、Workflow 6；运行 `wf-exec-c2162874-ed84-447f-b23a-6da6b8c44a97` SUCCEEDED，Plan COMPLETED、两个步骤 DONE。四次并发请求和后续跨角色重放始终只有一个执行，两次模型调用（378/576 tokens），分别关联 taskKey 与 Trace。SQL 在任何 Plan GET 前确认后台已写入 COMPLETED。两个专用 actor 自动 SUSPENDED；未操作原有 Agent 4–9。
- 四角色真实 API：元数据均 200 且隐藏 output；执行重放 viewer/governance 403、operator/admin 200；原始输出 viewer/operator 403、governance/admin 200。更换请求键、修改冻结分配、修改生成节点、绕过 Plan 再次提交均 409。
- API 脱敏证据位于 K8Substrate `artifacts/eap-plan-20260927/report.json`，复验脚本 `tools/eap/verify-plan.py`。节点夹具状态 `/root/eap-plan-evidence.json` 应保留，不要删除后重新 prepare。
- 边界：当前只支持串行；coordinationMode/workspacePolicy/requiredCapability 是声明和上下文，不代表已实施相应隔离策略。没有原地失败恢复/补偿/取消、并行或自动选 Agent；UI 新增计划/步骤仍使用示例内容，原始结果查看器尚未实现。后台每轮最多扫描 100 项，未做生产吞吐保证。详见 PLAN_EXECUTION.md。

- 浏览器最终 **PASS**：集群 Chromium 中 viewer/operator 真正 PKCE 登录、已提交按钮与步骤冻结、创建计划能力、真实 taskKey 时间线及刷新均通过，无未捕获页面异常。首轮发现匿名初始加载的 401 未捕获；修复 load 的错误处理，增加回归，前端从 88 增至 **89/89**，重新构建发布并复验。截图与报告在 K8Substrate artifacts/eap-plan-20260927/browser/。浏览器仅验证已完成计划，没有点击提交；真实提交由 API 验收覆盖。临时浏览器 Pod 已清理。


#### Codex 计划表单与结果查看（2026-09-27）

- 团队页面“新增计划/步骤”已替换示例写入：计划目标/上下文、步骤顺序/任务/所需能力由用户填写，长度与重复顺序在提交前校验，默认顺序取现有最大值加一。保存后选择新计划，步骤在列表分配成员；双击、切换对象、冻结与提交时权限再次检查。尚不提供已保存文本的修改/删除，不宣称完整 CRUD 编辑器。
- 新增 PlanOutputs：复用已有受 VIEW_CONTENT 保护的 outputs 接口，按 /me.viewContent 显示主动加载入口；输出作为纯文本（对象格式化 JSON）展示，不执行 HTML，不存浏览器持久存储。隐藏/切换计划/身份或权限变化/卸载时清理内容并使在途请求失效；无权限账号不请求该接口，时间线仍只有元数据。
- 本轮仅改前端。远程 **95/95**、生产构建 EXIT=0（新增 6 项表单/权限/竞态回归）；后端未变，沿用上一轮 272/272，不重复声称本轮跑过后端。所有编译、测试、构建、浏览器验收都在集群，本机未装依赖。未 commit/push。
- 已部署并核对 frontend `sha256:1e2bf88d8092a0d36b95e26bb635e62fcdd3acef4a5eeaae0a592b198050b21d`，buildId `2026-09-27T13:04:14.332Z`。后端与 Runner 沿用上轮。
- 四角色远程 Chromium 真实 PKCE 验收 PASS。operator 在 Team 2 创建合成计划“浏览器自定义计划-1790520313678”，填写上下文，新增顺序 3 的“浏览器自定义步骤”/synthetic.review，并分配 Agent 10；计划未提交执行。验证空表单阻断、用户输入保存、冻结计划禁增步骤。
- viewer/operator 结果入口不可见且没有 outputs 请求；governance/admin 显示 Plan 2 持久化的两步结果，隐藏后 DOM 移除，切换到未执行计划后原文消失；四账号无未捕获页面异常。结果使用上一轮合成标记，本轮没有调用模型或唤醒/修改 actor。
- 证据与截图：K8Substrate `artifacts/eap-plan-editor-20260927/`；脚本 `tools/eap/browser-plan-editor.mjs`、`browser-plan-editor-run.py`，凭证仅内存/stdin，不记录 storage/HAR。临时浏览器 Pod 证据导出后清理。
- 下一步建议优先做已列为 P1 专项的 30 秒/5 分钟/15 分钟长任务超时实测，形成同步协议边界证据，再决定是否需要异步任务协议；并行编排、后台空闲休眠/容量、生产身份注入及 Keycloak 持久化/TLS 仍待推进。此轮不扩大这些范围。


#### Codex 长任务边界实测（2026-09-27）

- 修正前次交接遗漏：原来已有 600 秒工作流、1000 秒直接路由的历史记录，但没有精确 900 秒工作流验收。此次在不改变超时/重启生产服务的情况下，使用独立 Agent 12、Workflow 7 的纯睡眠 IMAGE Runtime 实测。
- 30 秒任务 SUCCEEDED（30.227 秒），300 秒 SUCCEEDED（300.151 秒），900 秒 EXECUTION_UNKNOWN（900.006 秒）。同期直接经路由器的 900 秒请求 HTTP 200（900.109 秒）。EAP 总期限是整条工作流 900 秒，路由器 route-timeout=20m；不是每一步有 900 秒。
- actor 实际完成数 0→1→2→3；包括 UNKNOWN 在内的三次相同请求重放都保持原 runKey/终态，没有再次执行。等待真实完成后暂停本轮 actor，最终 eap-agents/a12-v1-d57b2f SUSPENDED；未调用模型或操作既有演示 actor。
- 不同层次要区分：通用 LLM Runner 模型 HTTP timeout=110s（代码确认），本次测量不证明真实模型请求能运行 15 分钟。未测试滚动升级/节点故障/高并发。
- 证据在 K8Substrate artifacts/eap-timeout-20260927/，协议结论见 LONG_TASK_TIMEOUT.md；脚本 verify-timeout.py，节点状态文件应保留。短任务可继续 V1；长任务可靠恢复需要异步状态与回执合同，不能把 UNKNOWN 自动当失败重跑。
- 用户同时要求集成 Substrate 监控并提供良好可视化；已着手运行概览与实例下钻，进展另记。用户询问 google/ax 的价值，已基于官方文档做适配评估，尚未部署或接入 AX。未 commit/push。


#### Codex Substrate 监控可视化（2026-09-28）

- 已上线运行与观测 → Substrate 监控：真实 Worker 容量、恢复/快照/路由 P95、CPU/内存趋势、崩溃/调度异常、部署版本与实时实例抽屉。后端只读 Prometheus 聚合，限定 eap-agents；复用 VIEW_CONSOLE。无新增依赖或数据库迁移。
- Worker gauge 去除多 API 副本重复；采样空缺显示为缺失，不伪造为零。模板资源与单实例状态分开；前端可见时 30 秒刷新，后端 15 秒缓存。版本列表分页，抽屉 append-to-body 修复被顶栏遮挡。
- 远程后端 279/279、前端 99/99、生产构建通过。四角色 API 与真实浏览器登录/图表/实例下钻/只读验证 PASS，无未捕获异常；viewer 另测 390 像素布局、模拟 HTTP 503 清除旧图表。没有实际中断 Prometheus。最终截图和报告：K8Substrate artifacts/eap-monitor-20260927/。临时浏览器 Pod 已删除。
- 最终运行 backend imageID `sha256:06ca46db734d6e50aef167797c6b715279d3cd8df5f679f3d534142ef05812ba`；frontend `sha256:2bd580b33d609fee5986a5187f6abc5cc6b3913ca28caa75ca1e1f638273af80`；buildId `2026-09-27T16:08:17.101Z`。Runner 不变。清单新增 EAP_SUBSTRATE_MONITORING_PROMETHEUS_URL。
- 历史日志后端、基础设施 Trace 自动关联、告警与容量自动化未完成。监控合同见 SUBSTRATE_MONITORING.md。
- AX 官方资料核查及适配建议已保存 AX_INTEGRATION_ASSESSMENT.md；尚未安装或接入。建议作为可选执行后端验证 Git/MCP/技能工作区，自定义 Runner 补退出码/结果回执；不能把 Ready/Running 当业务成功。下一步先做有界适配 PoC，保留现有 V1 路径。未 commit/push。


#### Codex 前端巡检问题修复（2026-09-28）

- 用户要求继续修复巡检发现的问题。修复观测页把 VIEW_CONTENT 请求与普通遥测绑定，导致 viewer/operator 全页加载失败的问题：内容分页和列表均按 /me 能力查询；内容异常隔离；权限变化清除内容、拒绝过期响应。普通遥测失败时隐藏旧指标/图表，避免把加载失败显示为零。
- WorkflowBuilder 的预演、执行、创建、节点保存、拖拽、移动落库和连线入口均检查 runWorkflows；无权限账号仍能选择节点查看，不能发写请求。Physical 页发现扫描/治理预检入口同步按 deployAgents/governance 控制。
- 修复工作流、团队执行区、观测页的 390px 控件裁切。工作流在窄屏纵向展示列表/画布/配置，画布内部可滚动；工具栏换行，健康面板不再撑破容器。未完成手机触摸拖拽编辑专项，不称其为完整手机编辑器。
- 连续浏览器验收进一步发现手机折叠/展开导航时调用 Element Plus open() 遇到子菜单注册竞态。最终去掉手动 open 调用，按折叠状态/当前分组声明式初始化导航，复测无未捕获异常。
- 最终远程前端 104/104、类型检查、生产构建通过（22.84s）；后端与 Runner 未改未重跑，后端沿用此前 279/279 基线。最终 frontend imageID `sha256:1e2db32af07738f4fe6259eee4b9bd8587bc9b4395208a16c2302707ad58ccdc`，buildId `2026-09-27T17:04:52.842Z`，已核对集群运行实例。
- 四角色真实 PKCE 浏览器验收 PASS：普通遥测显示真实 14 次调用；viewer/operator 内容请求为 0，governance/admin 可查询；工作流按钮/拖拽按能力控制；无权限选择节点不连线写入。三页手机布局均通过；viewer 浏览器模拟 503 时旧指标/图表移除。各角色 pageErrors=[]、业务写请求为 0，无新增模型调用。仅模拟浏览器响应，未中断后端或真实遥测服务。
- 证据：K8Substrate artifacts/eap-frontend-fix-20260928/；原问题见 artifacts/eap-frontend-audit-20260928/。临时浏览器 Pod 已清理。未 commit/push。
- 用户询问是否需要专业化设计和美化：建议需要，重点为信息层级、状态表达、组件一致性及复杂工作流体验；此轮完成问题修复，未开展全站视觉重设计。计划/步骤保存后修改删除、AX 接入、异步长任务合同与其他生产能力仍未完成。


#### Codex AX 任务执行接入（2026-09-28）

- 用户明确授权 AX 接入。已部署固定上游 d0bc38bc 的 AX server/controller、Redis AOF/PVC、eap-ax 独立 Worker；针对当前 Substrate API 调整探针与池选择，并禁用崩溃 actor 自动重建和默认模板回退。
- 新增 /ax-tasks 页面、AX gRPC 客户端、V50 持久任务队列、治理预检、幂等映射、异步 Runner 回执、模型网关/OTLP 归属、完成后休眠和终态沙箱恢复。Ready/Running 不作为业务成功；UNKNOWN 不自动重放。元数据/执行/内容/沙箱分别复用 PlatformPermission 能力。
- 实测发现现有 Keycloak access token 无 sub（只启用 profile）。已补 basic scope 到运行客户端及 ConfigMap/仓库清单，后端拒绝无主体 JWT，修正审计保留原提交者。历史 console 归属不能追认；新令牌四角色主体与新任务归属已验证。没有轮换密码或 Secret。
- 最终远程后端 288/288、前端 107/107 与生产构建、Runner 4/4 通过。真实 Agent 13：四并发同键只有一个任务；ECHO/FAIL/LLM、匹配回执、单 Trace 单模型调用、物理休眠、恢复后回执不变均 PASS。身份修复前后各 3 个独立合成任务、共 2 次模型调用；旧 demo 未操作。
- 四角色真实 PKCE 浏览器 PASS，viewer/operator 内容请求为 0，governance/admin 能查看并清除内容；390px 控件可见、无页面异常。期间修复 AX 抽屉层级遮挡。
- 镜像：backend e5dc0c0fa606134e9df7025be4fc72c0427cdb63cb488fed1a4c3f10af09127b；frontend 09eefa5d8615720751e4f9a9a337e000a13845432576c005e4c905f41866fe12；AX Runner 7682089a63dffa4b5dad2c4e4f551618d68c30af39be1eb82c64158ce711ac85。实际 imageID 已核对。
- 合同与边界：AX_EXECUTION.md。当前为独立 AX 任务入口，Team/Plan 仍走既有 Runtime；未开放 AX Git/MCP/Skills、任意 shell、debug/SSH，未测 Watch 重连和生产身份/TLS/灾备。Substrate 监控仍以 eap-agents 池为范围，AX 状态在任务页查看。
- 证据 K8Substrate artifacts/eap-ax-20260928/，节点夹具 /root/eap-ax-evidence.json 必须保留。工具 tools/eap/ax/。未 commit/push。
- 视频任务继续：全面 EAP 实操、无旁白、中文文字，imagegen 架构图已更新为真实 AX 双路径。eap-video-recorder Pod 用于后续录制，尚未清理，视频仍在制作中。


## Codex 全功能演示视频完成（2026-09-28）

- 用户要求全功能介绍、逻辑清晰、过程准确、容易理解，中文文字，无旁白，架构图使用 imagegen。已交付 37:37.28、1920×1080、25 fps、H.264 视频，无音轨，37 个章节；按“接入与部署 → 编排与执行 → 观测与追踪 → 评估与治理 → 业务价值”组织。
- 覆盖当前路由中的 19 个功能页面（认证回调不录），含全部主要标签页与子功能。30 段实录加架构片头、5 个分组过渡和结尾。配置入口、真实执行、既有实测、合成样本与空状态分别说明，不等同于所有外部集成都完成验收。
- 交付目录：`/Users/cpf/Documents/项目/联通/软件研究院/2026/K8Substrate/artifacts/eap-demo-video-20260928/`。包含 MP4、离线章节播放页 index.html、中文 SRT、imagegen 架构图、README、功能覆盖清单与验证证据。视频 SHA-256：`c8268f5609e4ea029e9b29ccc8cecb340eceea43bf3a5ece13a2b0491e6d575c`。
- 现场新建 AX ECHO 任务 `ax-b32c0ed3-4f08-4b00-9ced-7c27a770f6f5`，Agent 13，业务 SUCCEEDED、物理 Suspended；未新增模型调用。真实模型结果使用此前已验证任务，原生双 Agent 计划使用 Team 2 / Plan 2 / Workflow 6 的既有成功记录。
- 现场 COST_BUDGET 合成评估通过（成本 0.12、预算 0.50，非真实账单）。录制过程中产生两个独立示例数据集，保留不删除；最终使用 `EAP-DEMO-1790567463692` 完成人工 PASS 标注并保存快照 `calibration-8f3bade9-46ee-4822-9cff-1f5116ac5334`。HASH_ONLY 保存策略使 1 条样本被 SKIPPED，实际评估 0 条，字幕和文档明确不能据此评价准确率。没有为录片更改内容保护策略。
- 录制器的选择器、数据集标识大小写及只读 POST 搜索拦截问题已在录制工具中修正/补录；成片保留真实成功操作与结果，排除失败录制段。发现页已重新录制正常能力查询。没有因此修改 EAP 业务代码，也没有重复执行校准快照。
- 所有录屏、编码、抽帧、浏览器验证均在远程集群完成。本地仅编辑文本与保存、打包产物。19 页面覆盖检查通过，纳入成片的片段无页面/API 错误、无被阻止查询；抽帧检查与 37 章节桌面/390px 手机播放验证通过，无横向溢出，文件校验和一致。
- 原始素材和脚本保存在 ate-node1 `/root/eap-video-20260928/` 与 `/root/eap-video-tools/`；K8Substrate `tools/eap/video/` 保留制作脚本。临时录屏 Pod 在交付验证后清理，素材位于 hostPath，仍保留。未 git commit/push。
- AX 接入的既有验证仍是后端 288/288、前端 107/107、Runner 4/4；本次视频未更改业务源码，未重新运行这些构建。AX 仍为独立任务入口，未嵌入工作流节点；生产身份、容灾、规模测试等边界见 AX_EXECUTION.md。


## Codex EAP / AX / Substrate 统一编排完成（2026-09-28）

- 已将 AX 接入 Workflow 与 Team/Plan。AGENT/ARTIFACT 节点 configJson.executionBackend 可选 RUNTIME/AX，计划新增步骤可选择后端并随提交冻结；默认保持 RUNTIME。AX 结果通过 inputFrom 交给后续节点。新生成计划节点纵向排列，避免原先全堆在 (0,0)。
- V51 增加计划步骤 execution_backend 和 AX workflowId/runKey/nodeKey；工作流使用独立幂等命名空间，保持既有独立 AX 任务的幂等算法。任务创建前校验 Agent ACTIVE、治理与输入大小；AX 专用沙箱业务成功且物理休眠确认后才推进下游。失败/未知阻断下游，未自动重试。仍沿用工作流总期限，未实现重启续跑。
- 时间线展示实际 AX taskKey 与 ax:// 实例，链接至任务详情；详情显示来源工作流与节点，深链单独查询不受最近 100 条限制。原始输出继续受 VIEW_CONTENT 保护，关闭/切换/身份变化清除并拒绝旧响应。
- Substrate 监控增加 RUNTIME / AX 池切换，固定查询分别限 eap-agents / eap-ax，缓存按池和时间窗口隔离；AX 池展示任务业务状态、沙箱状态、运行归属和下钻链接。不是任意命名空间查询，也不把空采样伪装为零。
- 远程后端全量 **296/296**，节点布局补充后 Plan 专项 **9/9**；前端 **111/111**、类型检查与生产构建通过。前端首轮测试夹具缺 backend ref 导致 1 项失败，修正并增加切池竞态、深链、后端选择与表单回归后通过。所有构建测试在集群，本机未安装依赖或编译；未 commit/push。
- 已部署并核对 backend imageID `sha256:2f74d9b9383e24a72adecf104a6acce960441860ec571d6d60ca1ce809748c77`、frontend `sha256:cfc67174a5ebd3dab9047b56f88ee471c66c773226d74f2aa1ac895eb4f81d28`；MySQL V51 已应用。Runner 镜像未变。
- 独立真实夹具 Agent 14（原生生成）→ Agent 15（AX）→ Agent 16（原生复核），Team 3 / Plan 4 / Workflow 8，runKey `wf-exec-a4ba1a42-b727-4964-a9de-d24be247464e` SUCCEEDED，Plan COMPLETED、三个步骤 DONE。上游独有合成标记通过两次跨后端交接到达最终输出；四并发仅一次执行，共三次模型调用（366/517/638 tokens），各有 taskKey→Span→模型调用关联。AX task `ax-af136224-452c-431c-b7d2-ce1d0495c8b9`。原生两 Actor 和 AX 沙箱最终都物理休眠；未操作既有演示实例。
- 四角色 API 验收 PASS：普通元数据不含 output；viewer/operator 原始输出与 AX content 403，governance/admin 200；viewer/governance 执行403、operator/admin 同键重放原记录；改变键、修改冻结分配与绕过计划提交均409。原生/AX 监控池范围、非法后端拒绝通过。
- 四角色真实 PKCE 浏览器 PASS：AX 来源/深链、监控池切换与下钻、工作流统一时间线、计划后端列与冻结、390px 手机布局；各角色无页面异常、无业务写请求，viewer/operator content 请求0，governance/admin各1。首次脚本因 SSH stdin 重试传输空文件，随后内部 input 点击超时；修正传输和选择器后通过，未重跑业务任务。
- 证据 K8Substrate `artifacts/eap-unified-20260928/`；工具 `tools/eap/unified/`；节点夹具 `/root/eap-unified-evidence.json` 必须保留。复用同一 requestKey；不要删除状态后重复 prepare。合同见 AX_WORKFLOW_INTEGRATION.md。
- 明确保留边界：串行、原工作流总期限、UNKNOWN 不自动重放；未实现并行、取消/补偿、重启续跑、AX Git/MCP/Skills、生产身份/每任务凭证隔离、TLS、灾备、规模保证或基础设施日志/Trace 自动关联。此前演示视频未重新录制，本轮新增统一编排以报告和截图交付。


## Codex 导航分组、图标与业务命名完成（2026-09-28）

- 用户要求按整个平台能力重新组织导航和页面、补充图标，并尽量避免 AX/Substrate 等底层产品名称。已统一为接入与资产、编排与执行、运行与观测、评估与优化、治理与审计、成本与价值；保留工作台与运行大屏快捷入口。
- 单一 navigation/catalog.ts 驱动菜单、页面标题、面包屑、搜索与工作台能力导航。分组只展开，叶子才跳转；智能体详情高亮父目录。所有入口有语义图标和文字。
- 团队页计划/执行优先，成员资料折叠；异步任务默认历史与状态，新建表单由有权限用户展开。主要文案改为异步任务、基础设施监控、常规执行、隔离沙箱、常规执行池/隔离任务池。内部 API/路由/枚举、历史数据名称与技术标识保持兼容，不伪造历史记录。
- 900px 以下使用完整文字抽屉导航，点击关闭、支持 Escape、按钮44px。实测修复两个旧CSS冲突：固定侧栏248px阻止桌面收起；全局隐藏菜单span导致手机分组文字消失。
- 最终远程前端115/115、类型检查与生产构建通过。四角色 PKCE 浏览器PASS：18入口、图标/当前路由/面包屑、目录、搜索、桌面折叠、详情归属、新建权限、冻结计划、团队资料展开、390px手机抽屉及监控/任务无横向溢出、1024px布局。各角色页面异常0、业务写请求0。viewer有1次只读POST /api/agent-discovery/search；审查后端确认只查询，已修正测试拦截器分类。
- 前端最终 imageID sha256:5a72d5ffe9b5752f632d6b1b90498c7ff49f62680ef89a3109cc69f9709ce9f7，后端未改。远程证据与前后截图 K8Substrate artifacts/eap-navigation-20260928/，index.html 可查看效果；实现说明 NAVIGATION_INFORMATION_ARCHITECTURE.md。
- 本轮不重跑模型任务、不修改业务数据、不重录旧视频；不是全站视觉重设计。未 commit/push，本机未安装依赖或执行构建测试。


## Codex 运行实例纳管与智能体创建链路（2026-09-28）

- 用户要求继续补齐底层直接创建的实例纳管，并核查智能体创建链路。新增“发现与接入 → 运行环境发现”：默认读取 eap-agents，人工复核关联目录身份；查看实时状态，检查、休眠、恢复。只读元数据，不读取模板环境或复制凭证，不改造外部程序。
- V52 保存外部实例 UID/模板绑定。纳管前、控制前重新检查身份；重复提交复用绑定，冲突归属/UID/模板409；普通注册不可覆盖导入记录或改绑归属。审计主体来自服务端认证。导入记录 SUBSTRATE_OBSERVED / UNVERIFIED，不假造心跳，禁止直接工作流派发、聊天、删除与回退，等待任务协议、模型网关和遥测接入。
- 目录按钮改为“创建智能体”，必填责任归属等字段；创建草稿后自动进入部署配置，详情展示创建与接入进度及下一步入口。创建身份不自动启用，也不等同于运行与治理就绪。
- 真实 operator 界面创建 Agent17，发布版本1，随后 API 等待 READY、启用、模型试运行；1次模型调用/111 tokens，Trace关联、网关治理决策和审计主体 PASS。外部 CLI 创建独立实例，界面发现并复核纳管为 Agent18；没有读取/复制模板凭证。Agent18保持DRAFT。
- 四角色 API、4并发重复纳管仅1绑定、UID/归属冲突、普通注册覆盖阻断、生命周期命令 ACKED 均通过。Workflow9 试图直接派发给导入实例返回400，未入队、未产生模型调用；Agent18恢复DRAFT。两实例最终均SUSPENDED；既有演示实例未操作。
- 权限：四角色均可读清单；viewer/governance纳管与控制403，operator/admin可纳管与控制。内容查看仍按独立 viewContent 能力。最终截图检查发现详情页曾主动发出无权限内容请求并弹提示，已补请求前能力检查、身份/权限变化立即清理和旧响应丢弃，增加回归与真实浏览器 HTTP 响应检查。此前页面异常为0并不能证明没有被捕获的403，旧截图仅作过程证据。
- 最终测试、镜像与浏览器结果见 K8Substrate artifacts/eap-onboarding-20260928/status.json、onboarding/report.json 与 README.md；后端305/305，前端120/120、类型检查与生产构建通过（33.31s）。部署 backend sha256:3ac01faa751a52c9d0360cf229e90e73469d4a07dfb7d294e8dffa4b3b39bcac，frontend sha256:4a773085aa61c4be5b53ea516f78de2b1287883596eb5c3e1d981fbf39d650d8；V52已应用。全部编译、构建、测试和浏览器在远程执行，本机仅编辑和保存证据。未 commit/push。
- 节点夹具 /root/eap-onboarding-evidence.json 必须保留，不要删除后重建 Agent 或重复模型试运行；本地脱敏副本 api-evidence.json。工具 tools/eap/onboarding/。临时浏览器 Pod 在证据导出后清理。
- 最终四角色真实浏览器PASS：页面异常0、失败API响应0、业务写请求0，viewer/operator内容请求0，governance/admin各2；390px页面宽度通过，清单表格内部横向滚动。最终前端buildId 2026-09-28T07:44:31.412Z。
- 范围：访问/刷新时读取配置的空间，不是所有租户的自动认领。异步任务池仍归其任务控制器，不应加入额外发现范围；当前无跨控制器接管协议。底层名称式生命周期API无UID原子前置条件，检查/执行间并发删除重建仍有竞态边界。外部程序内容/工具/模型治理、任务适配、生产身份凭证隔离及容灾不能因“已纳管”而宣称完成。合同 RUNTIME_ENROLLMENT.md；创建形态/限制 AGENT_CREATION_CHAIN.md。本轮未重跑已有镜像/源码构建，也未重录视频。


## Codex 外部智能体实例凭证、任务接入与治理闭环（2026-09-28）

- 用户授权继续完善外部实例纳管与创建链路。本轮增加“运行环境发现 → 接入验证”：实例身份、任务协议、实际受管模型和关联调用链四项独立状态。签发/换发/撤销受既有DEPLOY_AGENTS控制，读取VIEW_CONSOLE；前端只依赖/me，凭证仅在内存，关闭/权限变化清除，迟到响应不能恢复。
- V53 runtime_connections保存随机生成号、请求标识、有效期和验证证据，不存明文令牌。24小时实例凭证由独立签名Secret与Agent/实例/UID/模板/随机生成号绑定；客户端请求UUID不作为生成号，撤销后不能恢复旧令牌。当前请求标识幂等重放不换发。
- 签名握手只发送nonce，不向待验证程序泄露平台或实例凭证。验证协议/归属与HMAC；失败清除就绪，原休眠实例尽量恢复休眠。任务派发前重新核对UID/模板，外部实例使用专属凭证，凭证有效且目录ACTIVE才允许模型/观测通道。
- 新机器入口/api/runtime-access/v1/chat/completions、/traces与人类JWT隔离。模型/OTLP强制Agent与endpoint归属，拒绝覆盖task/治理决策与伪造身份；Trace/Span按实例命名空间映射。实际模型成功才记网关证据，只有已保存的同Agent/endpoint且匹配模型Trace的Span才记关联证据。旧平台机器通道未迁移。
- 参考Runner新增scoped任务/网关/OTLP及签名握手。可选所有者配置入口默认关闭，仅显式独立EAP_PAIRING_TOKEN开启；只在内存安装当前实例令牌，不回显、不允许改Agent/实例键。本轮独立验收模板仅允许访问EAP后端，不更改既有实例出站策略。
- 真实夹具Agent19、Workflow10、实例eap-agents/external-connect-b9f6bcbd。旧凭证拒绝、旧安装令牌握手PENDING、新配置握手READY、探测恢复休眠、伪造归属拒绝、四角色API权限均通过。viewer/governance可读但配置403，operator/admin可配置；只读状态不含令牌。
- 首次运行wf-exec-999aa332-f88f-418e-a2c8-d474991b65a2 FAILED，原因是新受控通道未预登记发现端点，模型结果记账失败。已修复为上游模型调用前登记端点，补网关/遥测/数据库集成回归。原失败保留，记录0不能证明上游无消耗，不作自动重放。
- 修复部署后显式新建合成执行wf-exec-518c9a50-2c20-4605-96ac-8ddb252baa9f SUCCEEDED，1次成功记账/641 tokens，治理决策和Trace关联通过。首次失败用量不完整，不能将641当作两次总消耗。验收脚本曾把suspendAfterTask放在执行请求顶层，导致没有触发节点自动休眠；脚本已改为节点configJson，本次用显式PAUSE结束，不能称为自动休眠验收，未追加模型任务。
- 撤销后模型401、观测401、新工作流请求400，仍仅两条执行；最终凭证REVOKED、实例SUSPENDED。节点/root/eap-runtime-connection-evidence.json必须保留，禁止删除后重新prepare或为截图重新签发/执行。
- 最终远程后端316/316（失败/错误/跳过0）、前端123/123与类型检查/生产构建、Runner5/5通过。部署后端localhost:5001/eap-backend@sha256:72c960a3c1a49600ba56783f363a8d9502d386082a08ec694b90d6407f5879ed，前端localhost:5001/eap-frontend@sha256:b922084106e48f33150d50551890afd1a9bf94e6ff6041fc391e36e9fd275a11，buildId 2026-09-28T08:31:44.915Z。V53已应用；参考Runnerlocalhost:5001/eap-agent-runner@sha256:cb0ec71b62bb1352627b24ce3dab9afbcdf3a4c56232ffbe960e7cb11f706573仅用于本轮外部实例，平台默认模板未改。
- 四角色真实浏览器在READY与REVOKED两阶段均PASS：无页面异常/失败API/业务写请求/凭证读取。最终增加等待抽屉动画结束与实际矩形断言，桌面780×1050贴右、手机390×844全屏，无偏移。最初fullPage截图在已滚动页面记录了固定层偏移，另一次过早测量读到进入动画；改为动画结束后的视口截图，无需改产品源码。
- 证据K8Substrate artifacts/eap-runtime-connection-20260928/，index.html展示效果；工具tools/eap/runtime-connection/。合同RUNTIME_CONNECTION.md，已更新RUNTIME_ENROLLMENT.md、AGENT_CREATION_CHAIN.md。所有编译/测试/构建/浏览器都在远程，本机仅编辑和保存产物；未commit/push，旧演示视频未重录。
- 边界：本通道治理不等于所有工具/出站行为都受控；原程序仍需适配与安全配置，凭证人工续期，旧平台共享凭证未迁移。PoC HTTP/明文模板配置、上游生命周期缺UID原子条件、生产TLS/灾备、并行/取消补偿/重启续跑等未完成。

- 最终截图导出并检查完成，临时eap-browser-qa Pod已删除；节点夹具、现有业务实例和Secret保留。


## Codex 外部工具执行前治理与回执（2026-09-28）

- 用户继续完善集成。本轮实现实例凭证工具通道 /api/runtime-access/v1/tools/check、/claim、/receipt；GET /api/registry/runtime-tools按VIEW_CONSOLE读取最近30条。平台身份、实例/endpoint、Trace映射和凭证生成号由服务端固定，拒绝覆盖归属。复用既有行为规则及TOOL_INPUT数据策略，只有允许继续时可领取。
- V54 runtime_tool_calls保存调用标识、归属、参数摘要、决策与时间，不保存参数/结果正文或令牌。GenseeSafetyService新增metadata路径，在内存检查工具和参数（含特权路径），存固定说明及工具标识，旧入口保持原逻辑。工具观测新来源RUNTIME_GUARD，前端显示“工具接入”。受阻请求不生成实际工具调用事件。
- 许可有效60秒且不超过凭证期限；同一callId绑定工具/Trace/规范化参数，同请求复用决策、参数改变409。实例行事务锁串行处理首次检查、领取和回执；并发仅一次execute=true。凭证换发/撤销阻止旧许可；未领取不能伪造成功回执，已存回执不能改写。已领取但无回执显示未知，不自动重试。策略签发后的60秒窗口不自动重新评估，必要时撤销凭证。
- runtime/tool-guard/eap_tool_guard.py是仅Python标准库的参考客户端；冻结参数、先检查再一次领取、拒绝重定向、默认HTTPS、通信不确定停止，已执行而回执丢失不会重跑工具。工具成功/失败是执行方上报，不等于独立验证外部副作用。文档RUNTIME_TOOL_GUARD.md、RUNTIME_CONNECTION.md、AGENT_CREATION_CHAIN.md及客户端README已更新。
- 接入验证抽屉新增工具检查卡片，区分阻断/待复核/待领取/已领取未知/执行方报告。关联标识折叠显示，手机自动换行。切换身份/实例/权限或卸载清理数据并拒绝迟到响应；加载失败清除旧列表。未提供人工批准后续跑或任意MCP执行器。
- 最终远程后端 **323/323**（失败/错误/跳过0）、前端 **125/125** 与类型检查/生产构建、Python客户端 **5/5** 均通过。本轮未改GoRunner或重复其测试。后端新增7项涵盖真实数据库、并发、归属、策略、参数脱敏、轮换/撤销/过期；前端新增2项权限/竞态回归。
- 部署backend imageID `10.186.133.66:5001/eap-backend@sha256:fe0017305d8ec6079c44f3c21350921fb8d9f259b4b327c7f40a07327b4eb8f1`；frontend `10.186.133.66:5001/eap-frontend@sha256:6d9be0af4785a1877323921c30a29b1e144b833a4e7bea4cc3ab2c82ffc38463`；前端buildId `2026-09-28T10:21:29.010Z`；MySQL V54 success=1。已核对实际运行镜像和就绪状态。Runner继续用上轮cb0ec71b…，未构建新Runner。
- 独立Agent20 / eap-agents/external-tools-eb38c827：原actor验证实例身份与签名握手；工具回调实际在ate-node1参考客户端执行，不在该actor内执行，不宣称已给原程序自动注入工具适配器。只允许合成回调执行1次，模型调用0次；敏感参数、特权路径、高风险工具、Agent范围策略、重复调用均停止。伪造归属400、参数改变与未领取回执409，四并发检查1决策、四并发领取1许可；真实丢弃领取响应后回调未执行且未重试。
- 共7条工具检查：3 BLOCKED、1 REVIEW_REQUIRED、2 CLAIMED、1执行方报告SUCCEEDED。两个CLAIMED分别为并发/丢响应测试，刻意不发成功回执，回调未执行。观测仅3条已领取调用（1 SUCCEEDED、2 REQUESTED），全部归属Agent20与同一映射Trace `b32249f8cf77bc7762955de7cca5894d`；合成Agent OTLP Span关联通过。
- 四角色API读取200；四角色真实PKCE浏览器桌面/390px手机、状态和观测来源PASS，页面异常/失败API/业务写请求/凭证读取均0。截图摄于READY阶段；随后撤销专用凭证，check/claim/receipt全401，记录数不变。验收Agent范围策略已停用、实例SUSPENDED；截图已导出并目视检查，临时eap-browser-qa Pod已删除。
- 证据K8Substrate `artifacts/eap-tool-guard-20260928/`（index.html展示页），工具 `tools/eap/tool-guard/`。保留节点 `/root/eap-tool-guard-evidence.json`，不要删除后prepare、换callId或重放工具；旧Agent19/Workflow10夹具未操作。所有编译/测试/构建/浏览器均在远程，本机仅编辑与保存证据。未commit/push；旧视频未重录。
- 边界：本轮完成可适配的工具前置治理通道，不会强制截获任意外部程序绕过客户端的行为；启发式规则不等于完整DLP/工具语义验证。输出只上报摘要，未检查输出正文。安全配送/自动续期、旧模板凭证迁移、工具取消/补偿、批准后续跑、通用MCP执行、异步重启恢复和生产设施仍未完成。


## Codex 实例内工具执行与工作流闭环（2026-09-28）

- 用户同意继续整合。本轮首先补齐“工具回调仍只在节点参考客户端”的缺口，新增runtime/agent-runner/tools.go；外部受控Runner显式EAP_TOOLS_ENABLED=true后，可由Workflow执行API的context.eapTool调用固定calculate_sum。没有eapTool仍走原模型路径；有工具请求而未启用/不支持/未获准时不会回退模型。旧模板及平台默认Runner未升级。
- 实例内先写私有持久记录（默认/data/eap-tool-journal），再检查平台策略、验证本地参数、保存领取意图、领取一次性许可、计算并保存结果、上报摘要回执和Agent Span。记录只存输入摘要/标识/状态及计算结果，不存凭证或原始参数。按实例/taskKey稳定生成callId，同键改参409，并发串行，恢复读取原结果；未完成意图不重跑。工具执行记录不能推论旧模型任务缓存也已持久化。
- 内置工具只对1–100个有限数求和，单项绝对值不超过1e12；未开放shell、任意URL/文件/MCP或模型自主选工具。一个实例私有目录只支持一个Runner进程。结果回执丢失保留本地成功结果并标明receiptConfirmed=false；领取响应未知时不调用计算函数。不能宣称外部副作用exactly-once。
- 远程Runner **9项顶层测试及5个场景子测试PASS**。覆盖四并发重复请求、同目录新Runner对象恢复、改参拒绝、阻断/复核、领取响应丢失、错误callId、回执失败和崩溃意图保留。-race首次因CGO关闭而无法运行，节点也无C编译器，最终没有竞态检测结果。没有本机编译、没有集群强制进程/存储故障注入。
- 新镜像 `localhost:5001/eap-agent-runner@sha256:15309f2f2d04b1408523c777380616072c7c4cf782abc915e8e042555dcc7828`。只用于独立Agent22，实例 `eap-agents/external-actor-tools2-f6ee366b`，模板template-actor-tools2-f6ee366b。后端/前端代码和镜像未改，323/125是此前已通过基线，本轮未重跑其构建测试。
- Workflow12，运行 `wf-exec-282b8c6f-d575-4749-9ef7-e186a1bee86e`：实际actor计算10+20+12，输出sum=42/count=3，SUCCEEDED并自动SUSPENDED。恢复后四并发重放原taskKey，结果及已确认回执一致，本地executed仍1。改参409、未知工具400、敏感参数及Agent范围TOOL_INPUT规则在计算前阻断；测试策略随后停用。模型调用0。
- 平台3条检查（1 SUCCEEDED/2 BLOCKED），工具观测只有1条实际已领取执行；与真实actor Span共用映射Trace `c4f2613ac2ed313aaaff22e7bbfb1a41`。四角色工具元数据可读；工作流原始输出viewer/operator403、governance/admin200。
- 最终撤销实例凭证；直接向actor提交独立验收任务时在线工具检查拒绝旧凭证，计算计数仍1，task为EXECUTION_UNKNOWN（许可未确认，回调已知未执行）。本地最终executed=1/failed=2/unknown=1，平台没有新增工具记录，实例最终SUSPENDED。撤销不会自动抹除actor内存令牌，本地状态和缓存结果仍使用本地认证，不能声称所有本地读取立即撤销。
- 最终四角色真实浏览器在REVOKED状态复验PASS：工作流时间线与任务/实例关联、工具状态、按当前Trace只有1条工具记录、390px页面均通过，页面异常/失败API/业务写入/凭证读取均0。截图已导出并目视核对，临时浏览器Pod已清理。首轮浏览器断言缺口和修复见下方；最初依赖未装好导致模块缺失，等待远程安装完成后只读复验。
- 证据K8Substrate artifacts/eap-actor-tools-20260928/；工具tools/eap/actor-tools/。保留/root/eap-actor-tools-v2-evidence.json及旧Agent19/20夹具，禁止清空标识后重新prepare或重跑。源码工作副本tools/eap/eap-agent-runner.go和eap-agent-tools.go必须同时编译，远程/root/eap-src/runtime/agent-runner已同步。未commit/push，旧视频未重录。
- 剩余：工具通过执行API的context输入，图形界面尚无工具参数表单，也未将参数固化为工具节点模板；不能把某次API上下文当成可复用界面配置。凭证安全配送/续期、旧凭证迁移和认证数据持久化本轮未实施，现状与实施条件见INTEGRATION_HARDENING_NEXT.md。认证仍H2+emptyDir；切换前必须验证一致性备份、用户/角色/签名键及旧token保留，不能直接挂新空PVC重启。


首轮Agent21/Workflow11在截图复核中发现eap.tool.call_id使Agent Span误分类为TOOL，产生额外工具观测。已改为eap.guard.call_id；回归断言增加Span类型与按Agent查询的工具记录总数，浏览器也检查总行数。最终Agent22/Workflow12确认只有1条AGENT Span及1条工具观测。首轮任务和错误观测保留，凭证已撤销、实例休眠；过程证据在legacy/并标明缺陷。两轮独立合成计算合计2次、模型0次，不能把最终夹具的1次当成两轮总数。

首轮/root/eap-actor-tools-evidence.json和最终/root/eap-actor-tools-v2-evidence.json均必须保留，不能清空后重建或重跑。


## Codex 工具节点图形配置与工作流结果查看（2026-09-28）

- 用户继续推进系统整合。本轮补齐工具任务图形表单：模型/工具类型、求和数字、绑定智能体说明、高级配置折叠、工具节点标识。参数保存至节点configJson.eapTool，保留原上游/休眠配置；无效数字及未保存草稿禁止界面执行，刷新可复用。
- 后端保存与提交校验工具、数量/范围、Agent/节点类型与执行后端；冻结每个节点配置，只给对应节点注入工具上下文。新执行请求拒绝全局context.eapTool；已有相同requestKey参数幂等读取仍返回原运行。旧API格式需迁移，直接Runner任务协议不变。
- 工具仅使用已验证专属凭证的导入实例。恢复后凭证认证读取/eap/state并确认tools.enabled=true；旧程序/禁用/无效/超限响应停止派发，原休眠实例尝试恢复。成功还需executed/state/receiptConfirmed三项工具证据，否则UNKNOWN不自动重试。未迁移平台默认/旧模板，隔离沙箱工具仍不支持。
- 工作流执行过程复用受viewContent保护的主动结果查看器；切换执行/身份/权限清除内容。界面不展示底层产品名的新配置术语，历史测试Agent名称未改。
- 远程后端337/337。首次原有OpenAiUpstreamClientTests.rejectsChunkedOversizeBeforeUpstreamFinishes超时；未改网关或该测试，完整复测通过，首次摘要保留。前端最终131/131、类型检查与生产构建通过。新用例覆盖参数/后端约束、冻结、跨节点隔离、旧程序零派发、未确认回执、草稿禁止执行及匿名失败恢复。Runner与schema未变，V54已确认，本轮未重跑Runner测试。
- 真实浏览器operator配置并保存10/20/12、刷新读取后点击一次执行，Agent23 / Workflow13 / eap-agents/external-tool-ui-f87dfe1f，runKey=wf-exec-547f3bfc-6c75-40cc-9f2f-9f53b0d0d81d，sum42/count3。初次页面整体检查因匿名初始化401未捕获而FAIL，任务本身SUCCEEDED；已定位WorkflowBuilder.load并补错误提示/回归，修复后仅做只读复验，没有重新计算。
- 实例计数executed1/failed0/unknown0、模型0次；工具回执与工具观测各1条，关联AGENT Span，Trace=578b7b25bc7fce67e380281e14f3f788。自动休眠先确认，然后恢复只读计数并再次休眠；凭证最终撤销。四种非法节点保存及全局工具上下文400且无定义变化/新执行；四角色写/内容权限200/403符合统一能力规则。
- 最终REVOKED四角色浏览器、390px配置与接入面板、结果权限PASS，34张截图；页面异常0、登录后失败API0、业务写入0、凭证读取0。匿名复验仍有预期401但无未处理异常；原始失败截图和报告保留。证据K8Substrate artifacts/eap-tool-ui-20260928/（index.html可看效果），脚本tools/eap/tool-ui/。
- 最终backend 10.186.133.66:5001/eap-backend@sha256:b20d945ab5bff3866a0521cf541dc0eea0710b15f2581bf2d1fa7c0ff6fa05c7；frontend 10.186.133.66:5001/eap-frontend@sha256:42649a8047b344d7512e3958d093b297aeb51a62dd6e0f7577a89205ad42ff42；buildId=2026-09-28T15:45:12.290Z，两Pod Ready。所有测试/构建/浏览器均远程；本机仅编辑和导出材料，未commit/push，旧视频未重录。
- 保留/root/eap-tool-ui-evidence.json及旧Agent19/20/21/22夹具；不得为截图重放已完成任务。首次browserExit=1是保留的过程记录，finalBrowser=PASS是最终只读复验结论。合同WORKFLOW_TOOL_NODES.md。凭证安全配送/自动续期、旧凭证迁移、认证数据持久化仍待完成，不能宣称系统全部整合完毕。

- 最终截图与链接已检查，全部证据导出完成；临时eap-browser-qa Pod已删除。


## Codex 控制台科技感与 AdminLTE 风格更新（2026-09-29）

- 用户要求继续，并增强前端科技感、参考 AdminLTE。采用深色导航与浅色工作区，工作台/目录深色标题区、蓝青色操作、统一数字/表格/分隔线；保留业务导航与真实指标语义，无新增前端依赖。此前已安装 @adminlte/vue 0.7.0，运行大屏原用 LteCard/LteSmallBox/LteInfoBox；本轮不是全站 Bootstrap 重写或全深色模式。
- 新增 frontend/src/styles/console.css，经 main.ts 在旧样式后导入；SubstrateMonitorView 的特殊字符指标改为 Element Plus SVG。设计约定 docs/CONSOLE_VISUAL_DESIGN.md。视觉复查发现旧 !important 覆盖标题辅助文字，最后用局部变量与字号覆盖修正，远程实测对比度 9.88:1。
- 远程前端 131/131、vue-tsc 与生产构建通过；最终 CSS 小修后重新构建镜像，未改变 JavaScript 行为。最终镜像四角色、18 路由、1500/1024/390px、菜单折叠/搜索/手机抽屉/权限 PASS，页面错误/API失败/业务写入 0；六页前后对照及三个监控 SVG 断言通过。后端/Runner 未改未重跑，后端 337/337 仍为前轮基线；未声称全站无障碍或性能验收。
- 最终前端镜像 10.186.133.66:5001/eap-frontend@sha256:d006c38e1f0eb37c74b65ccf54a3712b1c83ca874539adb1921b786d91d4f93f，buildId 2026-09-28T16:39:46.467Z；实际 Pod imageID 与 version.json 已核对。源码三文件 SHA256 与远程构建目录一致。
- 证据 K8Substrate artifacts/eap-console-design-20260929/index.html 可切换前后截图，status.json/README.md/机器报告及构建日志已导出，脚本 tools/eap/console-design/。临时浏览器下载约21分钟；SSH重试曾传空截图脚本，已用可重放同步、kubectl cp 与字节数校验修复；重复只读尝试曾端口冲突，自动任务最终PASS。过程记录与初轮截图/报告保留，未重新执行业务任务。
- 本轮所有构建、测试、浏览器均远程；本机仅编辑/导出，未commit/push，旧视频未重录。保留已有工具/模型验收夹具和撤销/休眠状态，不为截图换发凭证或重放任务。
- 整合剩余仍是凭证安全配送/续期、旧凭证迁移、认证数据持久化及长任务恢复/生产韧性。Keycloak仍H2+emptyDir，禁止直接换空PVC重启，继续按 INTEGRATION_HARDENING_NEXT.md 的一致性备份/身份保留条件推进。本轮视觉完成不等于系统全部对接或完整测试。

- 共48张最终/基线截图已导出；对照页9组图片切换与390px验证PASS。临时浏览器Pod已删除并确认不存在，前后端Ready。


## Codex 融资 BP 初稿与研究（2026-09-29）

- 用户要求结合上级 BP 目录样例、EAP 系统实现、联通研究材料、字节安全申报书和 AgentChaos 等工作撰写图文结合的融资 PPT，明确技术主线为性能、可靠性、安全性，并要求正式 BP 质感。
- 交付目录 `BP/EAP融资BP_初稿_20260929/`：34 页可编辑 PPTX、PDF、缩略预览、逐页文案、研究说明及市场/学术/安全证据底稿。26 页正文、8 页尽调附录；4 张真实 PoC 界面，其他图表与机制图为原生可编辑对象。
- 已查阅三份 BP 样例、最新产品合同与历史验收，核读 McKinsey 2026、主要竞品官方资料及相关论文。区分已有实现、远程 PoC、研究方向与商业假设；未把联通/字节材料当客户背书或已归公司的知识产权，未把论文收益写成 EAP 数据。融资金额、轮次、团队、收入与客户事实待用户补充。
- 文档生成、LibreOffice PDF 渲染和机器版面核查在授权 PoC 节点隔离临时目录完成，未修改业务或部署。PPT/PDF/渲染均 34 页，原生对象 1,069 个（含 4 张截图）、备注 34 页；坐标越界和 PDF 文字缺失检查均 0。已目视检查全部缩略图及代表页，修复标题孤字换行、页脚过长与默认阴影。
- 本轮未重新运行 EAP 产品测试、性能/可靠性/安全基准或论文实验，也未访谈客户、审计财务或验证成果权属。引用的 337/131 是此前不同轮次远程基线。未在本机安装依赖或运行应用构建，未 commit/push；既有实例、凭证及验收夹具未操作。

## Codex 融资 BP 视觉重制（2026-09-29）

- 用户指出初稿视觉简单、不专业，要求参考提供的 BP 样例。本轮实际渲染并目视阅读一微与嘉为样例的概览、竞争矩阵、阶梯路线、半圆生态和技术架构页面，重做视觉与图形表达。
- 新交付 `BP/EAP融资BP_重制版_20260929/`：31 页可编辑 PPTX、PDF、整套缩略预览、31 张大图、逐页文案与来源。25 页正文 + 6 页附录；旧稿保留。白底蓝色正式风格，包含分层架构、生命周期环、性能泳道、恢复分支、授权时间线、行业半圆、产品阶梯、ROI 瀑布和资金环图。
- 使用 4 张既有真实 PoC 界面及 1 张通用基础设施概念图（封面与结尾复用）。概念图由内置 imagegen 生成，未发送项目代码、私有研究内容、客户数据或凭证，不作为真实硬件或部署证据。图表与文字为原生可编辑对象。
- 远程临时目录完成生成、PDF 导出与渲染；PPT/PDF/图片均 31 页，1,394 个对象（含 6 个图片对象）、31 页备注；最终坐标越界与文字缺失警告均 0。全套缩略图与关键大图目视检查，修复环形标签、文本越界、恢复连线、泳道虚线、瀑布连接和结束页压图问题。机器通过不等于用户已认可视觉。
- 市场与技术证据沿用并整理既有核读底稿；保留已实现、研究目标与商业假设边界。融资金额、团队、客户和收入仍未补造。本轮未重跑产品测试或改动业务部署、实例与验收夹具，无本机依赖安装或应用构建，无 commit/push。


## Codex 智能体拓扑与控制修复（2026-09-29）

- 用户报告控制面/数据面图不能扩展、连线混乱、控制命令有问题，并补充最近控制消息列表过长。真实 API 复现27节点91关系、15节点超固定620px范围、13条工作流声明同端点重叠。浏览器基线加入独立验收资产后28节点94边，消息区3577px、画布4183px、页面4851px。最近两次Agent1状态检查实际FAILED且无可用实例，旧UI却在HTTP200时提示提交成功。
- 拆分 AgentNetworkGraph / AgentControlPanel：动态分层布局、聚焦与全图、搜索节点、窗口展开、滚轮/按钮缩放、空白拖动平移、适应画布；重复关系合并、明细按需展开；全图使用共享主干分支，展开时六列排布。4/6列回归检查全部控制线不穿过无关节点。移除由硬编码READY等关系状态推断的“有运行证据”视图，明确逻辑关系不等于网络流量或实例在线。展开实测发现align-self:start使fixed画布收缩，并被路由层级遮挡，已用显式窗口高度和Teleport修正。
- 消息仍读取全平台最近20条，默认筛选当前智能体；每页4条、330px区域内滚动、详情默认折叠。最终桌面消息卡469px、画布570px、页面1811px；展开画布1476×976（1500×1000窗口）。筛选范围标明“最近20条中”，不声称完整历史分页。
- 新增GET /api/control/agents/{agentId}/targets，按实际实例协议、租约、纳管方式及退役状态返回支持命令、不可用原因、派发开关/重试上限。UI明确绑定runtimeInstanceKey，按/me operateInstances控制提交与重试，阻止缺实例/不支持命令/无效JSON及过期目标响应；HTTP指定实例也检查心跳租约，禁止改投其他实例。ACKED才成功、FAILED报错、QUEUED/DISPATCHED待确认。控制请求从12秒改为90秒，与已有实例控制页一致。合同 docs/NETWORK_CONTROL.md。
- 远程最终前端144/144、vue-tsc/生产构建PASS，后端340/340、失败/错误/跳过0；11个源码文件与远程构建输入SHA256一致。首轮新增前端响应式模拟和后端Mockito嵌套模拟错误均已修复，完整重测通过。业务后端仅控制目标查询和指定HTTP实例租约校验变更；Runner/数据库无改动，本轮未重跑Runner。
- 最终镜像四角色只读浏览器PASS：无实例禁止派发、节点定位、缩放/平移/展开/Esc、全图全部节点适配不裁切、4条分页/330px/折叠详情、导入实例命令限制、390px无横向溢出；页面错误/API失败/非授权写入0。过程中保留展开布局失败和两处Element Plus控件选择器失败记录；后者未提交业务命令。
- 独立夹具Agent24 / eap-agents/control-topology-16e19973，模板template-topology-16e19973，不配置模型或机器凭证。真实operator UI仅执行RUN_HEALTH_CHECK、RESUME、PAUSE各1次，全部ACKED；合成HTTP200+FAILED回执显示错误而非成功。真实命令验收基于本轮5efe3f3c前端与最终后端；后续仅修布局/空选择提示，最终edeee0ad通过上述只读复验，不重复控制链路。最终ACTOR_STATE_SUSPENDED，模型0次；未操作旧夹具，保留/root/eap-topology-control-evidence.json，勿为截图重复prepare或模型/工具任务。
- 最终frontend 10.186.133.66:5001/eap-frontend@sha256:edeee0ad14c8e0796ddb8df340a0d66f1f8efde86dbe912e1971bf3feeff17d7，buildId 2026-09-28T23:45:52.746Z；backend 10.186.133.66:5001/eap-backend@sha256:7f3f4ba2b2f9272319ddf8e8f852a09bb4318e46fc49a01e8f8d722a2613ba62；两部署Ready，实际imageID/version.json已核对。
- 证据 K8Substrate artifacts/eap-topology-control-20260929/，脚本 tools/eap/topology-control/；全部测试/构建/浏览器远程，本机只编辑和导出。未commit/push，旧演示视频未更新。前端/登录隧道已恢复，localhost:5173和8180均HTTP200；保持隧道进程，别随浏览器QA清理一并停止。
- 本轮是工作台拓扑与控制修复，未完成凭证自动续期/旧模板迁移、Keycloak持久化、长任务恢复等后续整合。不要把局部测试通过当成整个平台生产就绪。

## Codex 融资 BP 内容深化与信息密度提升（2026-09-29）

- 用户随后要求“保存记忆”。完整可恢复上下文保存于 [BP 深化版交接记忆](../.context/checkpoints/20260929-090959-eap-bp-refined-handoff.md)，含版本位置、用户审美与信息密度偏好、研究和工程边界、远程生成方法、待补融资事实及工作区状态。当前 35 页深化版已交付但尚未获用户确认满意；后续从此版继续，保存记忆本身不触发进一步改稿。
- 用户要求继续较大改进，并明确内容要丰富、信息密度要高。本轮重组投资叙事，交付 `BP/EAP融资BP_深化版_20260929/`：35 页可编辑 PPT/PDF（27 页正文、8 页尽调附录）、逐页文案、来源、全页图片与缩略预览，旧稿保留。
- 新增产品五段生命周期能力矩阵；性能加入合法候选、工作流依赖、状态复核及全成本对照/消融；可靠性加入已生效但回执丢失的合成时序和四层故障矩阵；安全加入共享额度反例及 S/F/N、委托、撤销、效果核验与来源补证机制。
- 商业页补充采购主体与进入条件、竞品部署生态、验证包/年度订阅/扩容交付范围、可复用资产与阶段验收出口。未知团队页移入尽调附录；未虚构客户、收入、融资金额、作者与公司关系或实测收益。研究与工程事实沿用已有核读底稿和限定 PoC 记录。
- 远程临时目录 `/tmp/eap-bp-v3-20260929` 完成生成、导出和检查。PPT/PDF/渲染图均 35 页，1,477 个对象（含 6 个图片对象）、35 页备注；坐标越界与文字缺失警告均 0。全套缩略图与密集内容、关键大图目视检查，修复流程箭头遮字、章节眉题和截图比例。
- 本轮未安装本机依赖或运行应用构建；没有改动业务代码、部署、实例、凭证及验收夹具，没有重跑产品测试或机制实验，无 commit/push。BP 引用的 337/131 明确为较早历史记录，不是仓库当前最新测试基线；其他并行开发成果未改动。

- 本轮最终证据已全部导出，归档SHA256=45ff03d31eb61b3f3242eae4acad8e5d1697469b4eaf69964f086ade8d8c6801；截图索引和报告链接已核对。临时eap-browser-qa已删除，前后端仍1/1 Ready。跳板机曾再次超时，隧道已改为自动重连循环（当前执行会话9243），保持5173/8180映射。

## Claude 融资 BP 精品版（2026-09-29）

- 用户要求在 35 页深化版基础上参照优秀 BP 样例深入调研、做成精美 BP。新交付 `BP/EAP融资BP_精品版_20260929/`：36 页（29 正文 + 7 附录）可编辑 PPTX、PDF、预览与逐页文案；旧三版保留。
- 叙事从“研究申报体”改为投资人叙事：一页看懂、投资亮点、EAP = ServiceNow + Datadog + Zenity 对标、市场空间、Why now、L1–L5 阶梯、赛道图谱与并购、产品架构/矩阵/半圆方案、实拍、三大技术壁垒、竞争圆饼矩阵、商业模式、ROI、飞轮、战略曲线、团队占位、融资计划。
- 新增两份调研底稿（research/market_2026-09-29.md、competition_2026-09-29.md），关键数据：艾瑞中国企业级智能体 55.9→815.1 亿元（CAGR 70.9%）、Gartner 2028 年财富 500 强平均 15 万+ Agent 且仅 13% 治理到位、40%+ 项目取消预测、AI 治理平台 2030 超 10 亿美元、三部门 2026-05 智能体实施意见；Dynatrace 9.15 亿美元收购 Arize、Cisco 收购 Galileo（影响 Galileo 集成路线）、Zenity 1.25 亿美元 C 轮；国内阿里 AgentLoop、腾讯 ADP 4.0、火山 AgentKit 已进场，私有化本身不构成差异化。
- 远程节点生成，36 页、越界 0、文字缺失 0，全套目视检查。融资金额、团队、客户、收入仍为占位；未改业务代码，未 commit/push。
- 第二轮美化（同日）：精品版扩为 44 页（7 张章节扉页、深色结尾、浅色光晕背景、章节标签、截图深色展示台、标题自适应），新增“真实环境验证”页；关键数字经原文核对（Gartner 经 archive.org 快照），已修正政策引文（原文为“加强行为管控”“权限管理、行为控制等安全技术”，非连续“权限管理、行为管控”）与艾瑞口径。Gartner 2026-07 Hype Cycle 已将 I&O 预测改为 2029 年 60%，BP 仍引 Predicts 2026 的 70% 并注明出处。


## Codex 控制深度补测与大图性能修复（2026-09-29）

- 用户在“都测试了吗”后要求继续，补测上轮未覆盖的失败重试、回执丢失、配置/限流命令、快照回退、断网恢复和大拓扑。按 investigate 先复现再修复，全部编译/测试/构建/浏览器均在远程 PoC；未 commit/push，未重录视频，模型 0。
- 首轮新增真实 HTTP/H2 集成用例 8 项中 3 失败：四并发全部派发、退役仍能重试、审计归属原操作者。现按命令数据库悲观锁串行重试、重新检查退役状态，RETRY_CONTROL_COMMAND 记录当前认证操作者，保留原命令发起者。503→成功、最大次数、指定离线不改投、丢回执同键重试且接收端只执行一次、结构化配置/限流/策略负载都通过。仅隔离测试允许私网，运行后端 SSRF 保护未放开。
- 500/1000 节点用例复现 fitCamera 5% 下限造成裁切；实际浏览器还复现重复长虚线主干的绘制卡顿。修复全图适配、低倍缩放连续性，主干/每行干线仅绘一次，虚线周期保持屏幕像素尺度。最终合成 100/500/1000 智能体（另 3 控制节点）均无裁切；1000 节点 3000 关系刷新/展开/适配的浏览器观测约 8.8 秒（上一轮通过版约 27 秒，含脚本固定等待，非生产 SLO），可搜索聚焦最后节点。
- 最终远程后端 348/348、前端 148/148、vue-tsc/生产构建通过；11 个相关源码与远程构建输入摘要一致。最终四角色浏览器、390px、断网后保留旧图并报错/恢复后刷新、消息每页4条/330px通过，页面异常和业务写入均0。最后版首轮出现登录超时，保留脱敏失败记录，重跑通过；认证 Pod 未重启。
- 实际 Agent25 / eap-agents/control-control-deep-b0c1a1e4：保存 snapshot-A → 恢复并改 volatile-B → REVERT → 再恢复读到 snapshot-A，6 个生命周期命令全部 ACKED，最终 SUSPENDED。此项在本轮后端发布前执行，原生适配器未改，未重复业务操作。保留 /root/eap-control-deep-evidence.json，禁止为截图重建或重放。
- Agent26 / control-http-26 使用故意被 SSRF 阻断的端点。真实界面只重试一次，FAILED 提示正确/次数2；随后四并发只放行剩余一次配额（次数3），其他三个400。viewer/governance403，两个重试审计均归当前 operator；退役后重试400。最终 RETIRED/OFFLINE。保留 /root/eap-control-deep-http.json 与 uiRetryStarted，禁止盲目重放。真实 UI 重试在前端365e3137...完成；其后只有虚线缩放样式和手机标题布局变更，最终f646a062...只读复验通过，没有再次派发。
- 只读接口有界采样：5 并发、120 请求，全部200，P50 30.9ms/P95 199.1ms/最大243.7ms；不代表生产容量压测。1000 节点是浏览器合成数据，非1000实例同时运行。HTTP ACKED仅指2xx，业务限流实现和副作用去重仍由接入程序承担；未做真实集群断网/断电和长期压测。
- 最终前端 10.186.133.66:5001/eap-frontend@sha256:f646a0625f830d771b44cd15e46f152823058f70ed93ac62bff78ffefd3c8d73，buildId=2026-09-29T02:50:53.925Z；后端 10.186.133.66:5001/eap-backend@sha256:9b57031fe68eba363246598dc92ab09800ec4ea6f6d7b53ea9dbc383d3765cfd，均1/1 Ready。Runner/DB未变。
- 证据 K8Substrate artifacts/eap-control-deep-20260929/，包含修复前失败、虚线对照、两轮通过、最终截图、真实API/快照与版本摘要。节点归档SHA256=5647d5692aae249005e5a9b0e85e19589f1fc1b8d2f2018ec68b5c222dd81ee3。脚本 tools/eap/control-deep/README.md；使用合同 NETWORK_CONTROL.md 已更新。不要重跑这些夹具来补截图。
- 这轮是控制与显示补测加固；凭证安全配送/续期、旧模板迁移、Keycloak持久化、长任务重启恢复仍按 INTEGRATION_HARDENING_NEXT.md 后续推进，不能把本轮通过称为全系统生产验收完成。Keycloak仍临时卷，不得换空PVC直接重启。

- 截图复核另发现手机拓扑标题被图例和筛选器挤成竖排；已在700px以下将标题与工具栏分行，图例自动换行。新增真实浏览器标题宽度/高度断言通过，截图见 browser-final/after/mobile.png；最终源码摘要与镜像已重新核对。

- 最终归档已下载并校验SHA256，截图已目视复核，index.html所有本地链接有效；eap-control-deep-qa已删除并确认不存在。前后端仍1/1 Ready，本地5173 HTTP200、8180 HTTP302，隧道保持。
- 第三轮（同日）：48 页。新增核心流程/部署实拍/指挥大屏/界面集锦页；技术章节改为四大核心技术（多层次协同调度←联通方案，多智能体自我优化←华为五期方案，执行合同，权限证据），对外匿名为“运营商研究院/头部 ICT 企业合作”；团队页写入陈鹏飞、汪林公开信息（汪林杰青未核实，未写）。字体改为 Barlow + Noto Sans SC（远程 /tmp/eap-bp-fonts，经 FONTCONFIG_FILE 注册）。
- 第四轮（同日）：50 页。产品架构改为六层（入口/能力/引擎/数据/接入/底座）+ 安全合规纵栏；新增风险与应对页；技术四扩为两页，写入字节项目三项创新（区域化排除证据、判定充分性推导协同范围、多源最小阻断集归因），对外匿名为“头部互联网企业合作”；研发纵深改为四条研究线；强调“无侵入观测”。
- 第五轮（同日）：51 页，新增“创始团队项目经历”页：腾讯 IEG（召回 88.6%、+13%、年降本约 1200 万、Crane CPU +5%）、腾讯蓝鲸微服务 RCA 落地、阿里云 eBPF 可观测应用于 ARMS（均来自企业应用证明）；华为仅按公开口径（9 项合作、SRE/无侵入可观测/多智能体在研），其验收证明标注保密、不得他用，未引用合同号与验收结论。


## Codex 真实实例容量与恢复补测（2026-09-29）

- 用户继续补测。本轮建立独立两槽位池 eap-agents/eap-capacity-8c9f6c78 和 12 个真实实例（Agent27–38），只执行合成状态程序，无模型/工具/机器凭证注入。两个槽位满载时第三个实例 FAILED 且仍休眠，释放后原 commandKey 重试 ACKED、次数2；12实例×3轮=36循环、72次休眠/恢复、144次同键并发请求，owner/counter隔离通过。去重由合成程序实现，不是任意业务 exactly-once。
- **进程退出自动检测未通过。** process.exit(41) 后 runsc agent=stopped、应用HTTP502，但控制API仍RUNNING，EAP状态检查ACKED/RUNNING。已定位当前退出路径未触发控制面异常转移；RPC带崩溃标记/worker丢失另有处理，不代表应用自行退出受覆盖。没有改底层运行时或伪造CRASHED。保留首轮90秒等待失败及后续实际容器/控制元数据。
- 显式EAP REVERT→RESUME恢复快照计数6，未保存增量10000丢弃，再PAUSE成功。共84次控制尝试/83个commandKey（含一次容量失败和同键重试）。循环恢复HTTP控制请求P50/P95=1002.5/1195.3ms，休眠629.4/720.4ms；非生产SLO。最终170条正向断言通过，但总体PARTIAL_PROCESS_EXIT_DETECTION_GAP，不把检测失败隐藏为全通过。
- 浏览器发现RuntimeInventoryPanel登录身份竞态：身份watcher使旧请求失效，却未重新加载，页面持续转圈。现等viewConsole授权后查询，身份变化清旧数据并重新加载，退出停止loading且不发匿名请求。新增2回归在旧代码失败；修复后远程前端150/150、vue-tsc/生产构建通过。业务源码仅此组件与测试两文件，摘要与远程构建输入一致；后端348为前轮基线，本轮后端/Runner/DB未改未重跑。
- 最终新镜像四角色浏览器PASS：每次进入实例页故意延迟/me 1.5秒；12实例皆可见且SUSPENDED，常规/隔离监控范围、390px、42真实拓扑节点展开无裁切、消息每页4条/330px均通过。页面异常/业务写入0。初轮误拦只读POST搜索和修复前转圈FAIL均保留，未为截图再次注入故障。
- 最终frontend 10.186.133.66:5001/eap-frontend@sha256:4ced8bc21d3df9a7ba2fc7b32716bb33f94a2ada70d1e4b57b5e6c0bed8f2db2，buildId2026-09-29T06:43:35.597Z；backend仍9b57031fe68eba363246598dc92ab09800ec4ea6f6d7b53ea9dbc383d3765cfd，均1/1 Ready，实际imageID已核对。
- 原有19个休眠实例状态未改，新增12个全部SUSPENDED，独立池已缩容0。模型0。节点/root/eap-capacity-evidence.json必须保留，crashTriggered/crashRecoveryDone不得清除或盲重跑；模板及快照保留。脚本K8Substrate tools/eap/capacity/README.md。
- 证据K8Substrate artifacts/eap-capacity-20260929/index.html，22张截图、原失败与最终测试/浏览器日志、版本及原始状态。raw-evidence.tgz SHA256=e38dd0c298c069b5460d2dd84507076cb7646750c4795e1f977f426099badc88，已导出核对。eap-capacity-qa和eap-capacity-fe-test已删除；独立池0副本，本地隧道保持。无本机测试/构建，无commit/push，未重录旧视频。
- 下一项优先补进程退出识别与控制状态同步：实例UID/分配代次、防旧事件、主动休眠区分、多副本幂等释放、重启恢复和快照保留；不得用会唤醒实例的路由轮询代替健康监测。凭证配送/续期、旧模板迁移、Keycloak持久化、长任务恢复仍未完成；Keycloak仍临时卷，不得换空PVC直接重启。详见RUNTIME_CAPACITY_ACCEPTANCE.md。
- 第六轮（同日）：55 页。赛道图谱按 Codex 建议改为采购用途分区并加产品 favicon 与 EAP logo（EAP logo 由 frontend/public/eap-logo.svg 还原为 assets/eap_logo.png，放封面/结尾/页眉）；新增资本验证页、ROI 节省率敏感性。由 Codex（workspace-write）基于 research/sources/ 下三份方案新增技术一/二/三“创新点深化”页并更新四大核心技术总览；Codex 调用须用 `codex exec ... - < prompt.txt`，否则卡在读 stdin。
- 第七轮（同日）：56 页。分工改为 Codex 实现、Claude 审查：Codex 精简四项技术创新点页（核心思想/痛点/分条方法/关键结果）、重做“十年愿景”页、逐页修复重叠与小字号（清单见 BP 目录“视觉审查与修改说明.md”）；术语“执行合同”全局改为“执行契约”；团队页汪林排第一；去掉页脚来源行；项目经历页保留“华为 · 长期合作”。


## Codex 进展：工具共享授权已发布，底层退出检测验证中（2026-09-30）

用户要求继续完善底层性能与权限，并参考字节/联通研究。已读 BP/EAP融资BP_精品版_20260929/research/sources/bytedance_security.md 和 unicom_runtime.md，研究目标不能冒充已实现能力。

工具授权已实现并发布：显式工具名+类型范围、默认拒绝、共享 agentId 累计领取额度、领取时版本复核、配置 CAS 与审计、失败/未知/换发/恢复不重置用量。界面新增工具权限卡片、额度和 governance 能力编辑；参数/结果仍不存检查表。V55。后端354/354，前端156/156及类型/构建均远程通过。首次默认未授权红测复现原APPROVED；首次全量因JSON映射失败，JdbcTypeCode(JSON)修复后通过，失败证据留节点。

已发布 backend740ec3181ccb586642e15e75c4bbe1d976b2ed9d8a6526c2c03ba91a321ccf97、frontendaeb0088993109fdafd60412853698874b63f3a06875a6c05dd3b156dde69842a；1/1就绪，V55=1。真实双实例验收通过：8并发只领取2次，其余6阻断；未知/失败保留预算；凭证换发及快照恢复不清零；增加1额度后实际actor求和42成功，模型0。合成25请求各路径：前置范围拒绝中位30.62ms/P95 37.06ms，完整允许检查47.39ms/P95 59.74ms；是不同路径对照，不是生产容量保证。节点/root/eap-tool-policy-evidence.json completed=true，runStarted/effectSubmitted=true，禁止重放run或清除标记。独立夹具/root/eap-tool-policy-fixture-a.json、-b.json，同一新agent；保留模板/快照与旧夹具。尚待四角色浏览器最终验收、停用测试授权与撤销本轮凭证、导出证据。

底层退出检测补丁尚未部署。源码基线/root/substrate d277088bc1d081ef716d81dd7986d05d0a36ad3a；本地临时/private/tmp/eap-runtime-exit/base和work。已编写内部GetWorkloadHealth RPC、gVisor应用容器状态检查（排除_pause、未知不当退出）、控制面按actor租约确认CRASHED并保留assignment到清理确认后才释放、清理重试、可选--actor-health-atespaces和15s有界轮询。需要完成远程生成/编译/测试，再将补丁作为固定基线的可审查patch收入EAP/deploy/substrate-poc。不可将临时源码等同于已上线。Go生成插件首次proxy.golang.org超时，改为既有goproxy.cn；测试临时数据库eap/eap-exit-test-db仅节点127.0.0.1:55432，独立emptyDir；测试辅助支持SUBSTRATE_TEST_POSTGRES_DSN。底层镜像仍原版本，原容量退出缺口尚未关闭。

当前脚本K8Substrate/tools/eap/tool-policy/（fixtures.py/verify.py/browser.mjs/browser-run.py），/private/tmp/eap-tool-policy/；节点浏览器eap-tool-policy-qa，/work/tool-policy；后端/root/eap-tool-policy-test-out；底层/root/eap-exit-test-out。中断恢复先读取记录，禁止重放已完成工具或旧容量crash。继续只远程编译测试、不commit/push、凭证只内存/既有Secret；最终追加结果与清理状态。


## Codex 进展：工具共享授权收尾与退出检测灰度通过（2026-10-01）

- 工具权限已全部验收并导出：Agent39 两实例8并发只放行2次，额度/授权版本、停用、失败与未知不返还、换发与恢复不清零通过；实际actor求和42，累计用量3，模型0。四角色真实浏览器及390px通过，最终页面/API异常和业务写入0。测试授权disabled/revision8，两凭证REVOKED，实例SUSPENDED。后端354/354、前端156/156和类型/构建均为本轮远程结果，V55成功。最终backend740ec3181ccb586642e15e75c4bbe1d976b2ed9d8a6526c2c03ba91a321ccf97、frontendaeb0088993109fdafd60412853698874b63f3a06875a6c05dd3b156dde69842a，buildId2026-09-29T12:31:13.545Z。入口与边界见RUNTIME_TOOL_PERMISSIONS.md。
- 底层固定基线d277088bc1d081ef716d81dd7986d05d0a36ad3a：内部只读健康RPC按actor UID/worker Pod UID核对实际应用容器，_pause不代表应用存活；未知/超时/旧版本不支持不释放。取得原生actor租约重新读取状态，确认退出后先持久CRASHED并保留assignment，终止与卷分离确认才释放槽位。清理失败重试、重启继续清理有代码级验证，不自动重放业务。扫描显式范围、15s间隔、4并发/32队列/100分页；生命周期锁等待20ms、容器读取2s、RPC3s、操作45s有界。输出1MiB限制的ReaderFrom绕过已红测复现并修复。
- 远程底层测试4包：节点140个顶层/399含子用例通过，3个既有root文件权限模拟跳过；控制面220个顶层/1261含子用例通过、无跳过。未运行-race。首轮接口适配编译失败已修复；首次镜像缺版本戳被检查阻止部署，改为显式ldflags后验证。灰度部署脚本的patch-file stdin不兼容和新Pod暂缺nodeName均已修复，未以失败结果冒充通过。最终补丁git apply --check在独立基线通过，包位于deploy/substrate-poc/patches/process-exit-health，patch SHA256=4475d222a58b1ee376ec575a229c2409609f0cc9ce98f8ea0620ec7cb218d070。
- 独立单槽位池eap-exit-6c56c536、Agent40/41，无模型或机器凭证注入。正常恢复/休眠3轮通过；一次process.exit(41)后8389.8ms观测CRASHED，8479.9ms观测清理后assignment清空。EAP RUN_HEALTH_CHECK报告CRASHED，真实页面显示异常。第二实例复用槽位且owner=null/counter0；原实例显式REVERT→RESUME恢复计数6，未保存10000增量不保留。旧33个EAP实例状态未改；新两实例最终SUSPENDED，测试池已缩容0，模板/快照保留，模型0。故障前后浏览器与390px通过，无页面异常/业务写入。一次计时非生产SLO；真实网络断开、外部卷故障和清理中途强制重启未做。
- **灰度范围不能误读为全量上线**：API两副本镜像beccc8ec798845dc45ed96a293f3f1f1e6bc8006f7a7194a794ef9ea3fe114ae；node1 atelet5bdb9868d8c44c6559c10fa72fbaf9dc8e1af109319db7f6e232291bb65a0636，均Ready且实际imageID已核对；独立池工作镜像2961d9e7f47480ba7b43f716ad7a044b1d8ed58d02b0eccfa7d05673b320be39。node2 atelet Pod UID e71c4a10-6c3b-4f46-b98c-19295ce8d036未变，默认eap-agents/eap-ax工作池仍旧2fb84afb...，旧worker不具备健康RPC。DaemonSet保持OnDelete；不能直接切回RollingUpdate。node2历史ate-golden任务803ab1fe-747c-40f4-86d4-14c357d96550仍RESUMING，占用eap-agents-7fd67597f5-b9w9l；先调查其归属/安全终结，再迁移默认池，不能直接杀掉来升级。
- 证据已下载核对：K8Substrate artifacts/eap-tool-policy-20260930/（22张含过程失败截图，归档SHA256=202c1f6713fd850dc27d5608e5b7608b768d3bb21522115ebc0e1a48ee95065a）；artifacts/eap-process-exit-20261001/（12张截图，归档SHA256=f8545ad130ae0d053e6510477be6926843257bcaf8b7cbc4c2af8af49b5209b8）。脚本分别tools/eap/tool-policy与process-exit；进程合同RUNTIME_PROCESS_EXIT_HEALTH.md。保留节点/root/eap-tool-policy-evidence.json和fixture-a/b、/root/eap-process-exit-evidence.json，已completed，不清标记、不重放工具或crash。原容量夹具不变。
- 临时eap-tool-policy-qa、eap-tool-policy-fe-test、eap-exit-test-db、eap-exit-version-check清理；业务部署、旧夹具和本地访问隧道保留。不本机编译、不commit/push、不重录旧视频。研究借鉴只落地共享额度/版本复核和有界确认回收，完整委托、效果证明、LLM/内核调度未实现。凭证配送/续期、旧模板迁移、Keycloak持久化、长任务重启恢复仍待后续，不能将本轮当成整个系统生产验收完成。

- 最终收尾：四个本轮临时Pod确认不存在；本地隧道因daemon重启中断后已恢复，当前自动重连会话70891，localhost:5173/version.json及8180认证公开配置均HTTP200。保留此会话；不要再依赖历史9243。证据索引41个链接检查有效，补丁包摘要与远程导出一致。


## Codex 进展：融资 BP 投资人修订版已交付（2026-10-01）

- 用户要求从投资人角度提出意见并直接修改，逻辑自洽、条理清楚；保留图文与技术深度。新目录 `BP/EAP融资BP_投资人修订版_20261001/`，已完成 56 页详细稿、26 页讲解稿、两套 PDF、逐页 PNG、来源备注、修改意见和检查说明。总入口 `BP/README.md`。原精品版 7 个源文件及 PPT 共 8 项 SHA256 与修订前一致，未覆盖历史版本。
- 购买逻辑前置：采购人、固定流程、有偿验证、年度扩展。统一性能/可靠性/安全性三个目标与调度/受控演化/执行契约/权限证据四项机制。撤下跨口径 80—120 亿元主张，SAM 改为账户与合同范围假设；竞争按具体产品采购条件比较，不用主观评分。
- ROI 明示 45/200=22.5% 现金平衡点；本例验证费已抵扣软件或实施费，产能不重复计现金。新增平均 12 人、18 个月基础预算 918 万、含 15% 缓冲约 1056 万情景及 8/16 人敏感性；不是正式融资金额。10 家×30—80 万仅为目标年化软件合同额，不是累计收入。团队角色与公司成果使用范围待确认，未虚构客户、收入或任职。
- 核读到其他工程工作已在 10/1 完成工具授权四角色验收及独立池退出检测灰度，已同步到 BP 第 4/24/32/47/50 页；强调默认池和旧工作进程尚未全部迁移、一次退出计时非生产 SLO。本轮仅引用工程证据，未重放工具/故障、未测试或改变产品部署。
- 技术文字卡片改为调度反馈、按证据协作、授权与额度关系图；研究前提、强基线、证伪条件保留。官方复核更新 Cisco/Galileo 5/22 收购完成状态，Zenity 来源改为可访问的官方融资博客。新增资料核查日 10/1，沿用资料保留原 9/29 口径。
- 最终生成在既有 n1 通道的远程文档环境完成，复用现有依赖/字体；最终目录见 output/remote_build_path.txt（20261001-205422）。详细/讲解稿 56/26 页、PDF 56/26 页、渲染 56/26 页、备注 56/26 页，文字缺失及越界告警均 0。查看全套缩略图并放大复核关键页；未在本机 PowerPoint/Keynote 原生打开。没有本机安装、业务代码修改、commit 或 push。
- 当前待补为真实经营事实：客户访谈/付费合同与收益、团队任职/投入及权属、真实现金与融资条款、同条件技术收益与交付成本。继续时以新目录和《投资人修改意见与落实说明.md》为起点，不回退到此前“工具权限收尾未完成/退出补丁未部署”的旧表述。


## Codex 进展：启动探测减压与访问边界灰度通过（2026-10-01）

- 用户继续完善。本轮查明node2历史ate-golden任务属于Agent7旧发布eap-a7-v4：EAP版本4 FAILED，版本5 READY；v4仅有自身准备任务引用，无业务实例、无快照/外部卷。其/路径3000端口一直404；原就绪探测固定1ms，节点日志每120秒约4.7万次请求，底层反复重试而状态仍RESUMING。不是实际业务长任务。没有导出模板环境（其中含旧机器凭证），诊断仅保留元数据/计数。
- 已向用户发出异步确认：是否删除这个失败v4模板及准备任务、保留EAP发布历史/v5后继续默认池升级。**尚未收到确认，不得将默认选项或等待视为同意。** 默认池和node2未改，DaemonSet仍OnDelete；未删除旧v4。节点/root/eap-failed-template-retirement.json仅有preflight、无retirementRequested。tools/eap/runtime-rollout/retire-failed-template.py默认只读；仅明确获准后才可传--approved-template-uid=803ab1fe-747c-40f4-86d4-14c357d96550。脚本复核UID、FAILED/READY版本、无业务引用/快照/卷，由原生API串行清理；请求先持久标记，未知结果不自动重放。
- 独立完成就绪检查模块修复（2个Go文件）：首次立即执行；失败间隔1ms指数增加至100ms；仅目标直接200通过，拒绝重定向；总超时覆盖进行中请求；最多读取4KiB响应体；取消/超时后的200不可转成功。5项回归旧代码全部失败；相同1秒合成404窗口753→16次，约减少97.9%请求，不是生产CPU或推理吞吐承诺。迟到响应、取消、读取1MiB和重定向越界均被回归覆盖。
- 远程internal/wakeupprobe、ateom-gvisor、ateom-microvm、atelet四包190顶层/503含子用例通过，3个既有root文件权限模拟跳过，0失败；未运行-race，未真实启动microVM。EAP前后端未改、未重跑，不能把上一轮354/156说成此次新跑结果。
- 真实独立PROBE-a69ec423/Agent42，池eap-probe-a69ec423，节点1：正常恢复/休眠3轮成功（恢复1300.4/833.6/926.4ms），保存8次未就绪的合成状态后恢复，第9次200通过（1006ms）。原35个EAP实例状态不变，模型0；测试实例已休眠、池缩容0，模板/快照保留。新工作镜像localhost:5001/eap-probe-ateom-gvisor@sha256:4fb1df0f3767477c5a71f14502470ebb51d0fed9734cd759c3f6a6f1f299594c，实际imageID已核对；包含此前退出检测补丁，仅独立池使用。
- 可审查补丁deploy/substrate-poc/patches/bounded-readiness，固定基线d277088bc1d081ef716d81dd7986d05d0a36ad3a；独立基线git apply --check通过，patch SHA256=2f603975063302eb88bbacc38c29e712634493f23dec39cc8cece83855b1d80a。合同RUNTIME_READINESS_PERFORMANCE.md。证据K8Substrate artifacts/eap-readiness-20261001/已下载核对，归档SHA256=184d3f3626acfe77c88488d9b45723c8d5cf1f561a357288613e33c269aa6037，含5项红测、最终事件、真实调用、历史任务脱敏诊断和补丁。保留/root/eap-probe-evidence.json completed=true，不重跑夹具；旧工具/容量/退出标记亦保留。
- 下一步取决于用户确认：若获准，安全清理仅旧失败v4，核对节点/池无未释放分配，再升级node2组件及两个默认工作池，补普通/隔离实例和旧快照兼容验证。不能直接杀worker或切回RollingUpdate。凭证续期、旧模板身份迁移、认证持久化、长任务恢复等后续边界仍有效。本轮无本机构建、无commit/push，现有隧道70891保持。


## Codex 进展：融资 BP 投资人二轮版已交付（2026-10-02）

- 用户要求继续投资人角度修订。新目录 `BP/EAP融资BP_投资人二轮版_20261001/`（10/1 起草、10/2 交付）完成 56 页详细 PPT/PDF、28 页讲解 PPT/PDF、两套逐页图片、预览、修改说明、十个投资人问答、月度现金/季度汇总/回款敏感性 CSV。入口 `BP/README.md` 已更新。上一轮两份 PPT 与二轮开始时摘要一致；历史目录没有覆盖或删除。
- 新增/重做首战运维流程、购买框架加云/自建/EAP 比较、8—12 周验收、最新启动探测证据、交付经济性、现金图、扩编预算与壁垒形成条件；统一使用“执行契约”。LangGraph 官方文档已有持久化与恢复，AWS AgentCore Policy 已公开工具和会话历史/次数/预算条件，不把这些当 EAP 独有能力；竞争未知项保留采购验证问题。新增资料核查日 10/1，交付日期不冒充全面重新核查日期。
- 财务统一 15 万验证/50 万年约，转约抵扣 15 万、追加收 35 万。目标 20 验证、10 年约：累计现金 650 万、期末年化合同额 500 万均是假设。客户 ROI 统一为采购 50 万+客户侧运营 5 万，对 200 万可优化支出的平衡点 27.5%；15/25/35% 节省对应 −25/−5/+15 万，不能沿用上一轮 22.5% 或 +5 万例子。
- 直接毛利：验证独立 43.3%，年度软件独立 84%，验证转约首年组合 67%；均为成本假设，不是已实现公司利润。期内验证与支持 952.5 人日/47.6 人月。团队六季度 8/10/12/12/14/16 人、216 人月、工资 648 万，非人力 270 万，基础支出 918 万、15% 缓冲 137.7 万。工资不与交付人日重复计入现金。
- 月度峰值缺口加缓冲、未扣真实现金：零回款 1055.7 万、半数回款 730.7 万、全部延后 3 个月 527.7 万、目标按期 405.7 万。延迟情景峰值在 M17 为 390 万，不取 M18 的 388 万，因此不是 525.7 万；全部说明已修正。后期年约 M19 后仍有支持义务；模型不计续费、税务或融资费用。实际融资金额、轮次、客户、团队投入未获补充，不编造，不把可选提问当阻塞。
- 性能引用最新限定工程证据：同 1 秒合成 404 窗口探测 753→16 次，约减 97.9%，仅独立池灰度；不推导 CPU/推理吞吐/客户账单降幅。190 顶层用例与 3 跳过、未 race、microVM 未实启边界已列；354/156 属另一轮验收。默认池未全量迁移，本 BP 不触发旧失败模板清理授权，也未重放任何产品夹具或故障。
- 最终远程生成 `/tmp/eap-bp-investor-r2-20261001/20261002-094327`，复用现有依赖与字体，归档上传/下载 SHA256 已核对。PPT/PDF/渲染/备注均为 56/28；对象总数 2726/1437（含图片236/105）；文字缺失及越界告警均 0，六项财务勾稽通过。查看全部 7+4 张缩略总览和关键新页大图，最终术语页复核。未于本机 PowerPoint/Keynote 原生打开；视觉结论基于远程 PDF/PNG，不代表用户已认可审美。
- 本轮无本机依赖安装、应用构建、业务代码/部署/凭证/实例修改、产品测试或 git commit/push。后续从二轮目录继续；财务输入变化须远程重新生成且人工同步示例文案。优先待补真实客户验证约定、同条件业务结果、实际交付账和真实现金/团队/融资安排。


## Codex 进展：默认池迁移、精确模板维护与旧快照兼容完成（2026-10-02）

- 用户已回复“继续”，沿用针对旧失败v4清理的确认；不是再次等待许可。daemon中断恢复后先核对retirementRequested不存在，再执行。首次原生删除和随后240秒/89次有限重试均为模板锁冲突（Aborted、清理前）；无直接数据库修改或强杀占用worker。新增精确atespace/name@UID维护排除，API正常滚动取消旧协调请求后原生DeleteActorTemplate成功。仅旧eap-a7-v4及无快照准备actor删除；EAP v4 FAILED历史、v5 READY和其余模板/快照保留；最终移除维护排除参数。
- 新补丁4个Go文件，11顶层/63含子用例全部远程通过，0跳过/失败；固定基线依次应用退出检测、就绪退避、模板维护三个补丁并比对源码摘要通过。源码暂存与远程差异先被校验阻止，确认仅gofmt后同步；导出补丁新文件模式错误修正后复验PASS。补丁deploy/substrate-poc/patches/template-maintenance，SHA256=55b7d79b0f7811f8c78ab0d98a09e128d73320024616bf5405e073a1ff44b3b8；未跑-race。
- node2及两个默认WorkerPool均已升级，不再仅灰度。API 2/2=a44359ce412667701b70b010f8cd944cb4f4830dd5d35686cee9cfe4bcf8b8fe；两节点atelet2/2=5bdb9868d8c44c6559c10fa72fbaf9dc8e1af109319db7f6e232291bb65a0636；普通池2/2、隔离池1/1=4fb1df0f3767477c5a71f14502470ebb51d0fed9734cd759c3f6a6f1f299594c。实际imageID均核对，OnDelete保留；health范围eap-agents,eap-ax、间隔15s。其他命名空间/历史0副本夹具池未升级。镜像锁定runtime-image-lock.json。
- 清理后83个actor在升级期间元数据/状态/快照引用完全一致。新的兼容记录只恢复Agent24/25及Agent13既有隔离ECHO：普通分别node2/node1，内存snapshot-A保留，隔离在node2读到原回执摘要；20秒后均RUNNING，最终三个重新休眠、分配清空，其余80个actor状态/快照不变。恢复观测2522.2/10217.5/9362.0ms，非SLO或性能收益；模型原计数0/0/2均未增加。未调用任务执行接口，未改旧夹具完成标记。
- 四角色真实接口通过：监控200；内容viewer/operator403、governance/admin200；viewer/governance控制403。前后端未改，仍backend740ec318.../frontendaeb00889...；本轮未重跑其全套测试/浏览器，未重复注入退出故障或真实断网/断电、microVM和长期压力。不能称整个系统生产验收完成。
- 证据K8Substrate/artifacts/eap-default-rollout-20261002，rawSHA256=b2b5c285a8ae4c590ff2fe19677f2f23609d3e5f3cb32d2bc3482c7248aa094c，已下载校验。合同RUNTIME_DEFAULT_POOL_ROLLOUT.md。节点/root/eap-failed-template-retirement.json、/root/eap-default-rollout.json、/root/eap-rollout-compatibility.json均completed=true，禁止清标记或重放；/root/eap-maintenance-deployment.json保存启停记录。临时版本Pod已删除，83actor最终没有活动分配；5173/8180均HTTP200，现有隧道保留。无本机构建、无commit/push。
- 下一步回到统一实例身份、安全配送/短期凭证续期和旧模板迁移协议；再按INTEGRATION_HARDENING_NEXT.md推进认证持久化、长任务重启恢复。精确维护开关尚不等于EAP发布失败自动取消/清理产品链路。Keycloak仍emptyDir，不能换空PVC直接重启。旧工具/容量/退出/探测夹具和completed标记全部保留，不重复验收业务。


## Codex 进展：融资 BP 汇报版已交付（2026-10-02）

- 用户要求继续优化内容、可以直接汇报。独立新目录 `BP/EAP融资BP_汇报版_20261002/`，保持 56 页详细版与 28 页汇报版；两套可编辑 PPT、PDF、PNG、预览及完整来源。全部 28 个讲解页加入建议口播、转场与追问备注，独立讲稿约 5355 字（含转场），建议 20—25 分钟，未录音实测。另附汇报手卡、内容优化说明和财务底稿；入口 `BP/README.md` 已更新。
- 6 页重做：详细版第 9、39、40、43、47、48 页；23 页改结论式标题（第43页两类重叠）。运维场景增加指定实例重启的解释性路径，明确为拟议场景，不由合成工具 PoC 自动证明。ROI 增加 200/300/500 万元可优化支出与 15/25/35% 节省率矩阵；年约50万+客户运营5万口径不变，现金平衡27.5/18.3/11.0%，均为假设。毛利页以67%组合口径为主，说明包含验证期及首个订阅年，非会计年度利润。团队资料与拟配置职责分别呈现；融资对应产品定型、付费验证、复制交付；结尾移除联系人占位，提出资本/设计客户/产业伙伴合作方向。
- 页数没有增加，也未新增外部市场事实；延续原有9/29和10/1核查口径。当前工程实测、研究目标、商业假设与未确认融资/客户/任职等事实边界均保留。已阅读并按 pages:write-page 写作规则整理本地讲稿，遵循既有项目目录，没有创建或上传外部 Page。
- 全部生成与检查在既有远程文档环境，最终 `/tmp/eap-bp-report-20261002/20261002-100947`。首轮结尾页缺sub参数导致生成停止，修复后重跑；目视发现历史项目页标题换行接近副标题，缩短后最终重导并放大复核。最终PPT/PDF/渲染均56/28，备注56/28，实际从PPT读出的完整口播备注28/28；对象2730/1441（含图片235/104），文字缺失及越界告警0，六项财务勾稽通过。
- 查看全部7+4张缩略总览及6个重做页大图；最终45详细页及24汇报页标题复核通过。上传/下载归档SHA256一致；二轮原稿18个源文件和成品摘要全部不变，核对记录在research/二轮原稿保留核对.json；本版交付摘要在output/交付文件摘要.json。未在本机PowerPoint/Keynote原生打开，视觉依据远程PDF/PNG，不代表用户已确认审美。
- 本轮无本机依赖安装、应用构建、产品测试、业务部署/实例/夹具改动、模型调用、git commit/push。正式融资金额、真实客户、公司任职和投入仍需实际事实补齐；可直接用于项目介绍与早期融资交流。继续改稿应从本汇报版开始，同时更新presentation_pass.py、speaker_notes.py与财务示例，避免PPT和口播脱节。


## Codex 进展：按用户纠正完成原稿文字优化与观众视角试讲（2026-10-02）

- 用户明确纠正“原来的文字需要优化，页数不要修改”，随后要求“可以演示报告一遍，从观众的角度优化一下”。本轮回到投资人二轮版作为基准，未沿用上一轮汇报版重做的六页版式。当前入口改为 `BP/EAP融资BP_文字优化版_20261002/README.md`，详细稿 56 页、既有讲解稿 28 页均保持原顺序；历史二轮和汇报版全部保留。
- 原位润色全部 56 页，修改 215 处文字节点，既有 28 页同步 117 处。重点解释产品与采购对象、三条技术线的业务价值、局部 PoC 与研究目标、客户 ROI 与交付毛利、预算与融资需求。所有原数字、图表、图片、位置、尺寸、字体字号和样式不变。正文映射 `source/text_polish.py`，口播与转场 `source/rehearsal_notes.py`。
- 以观众问题逐页桌面模拟讲述；56 页均加口播备注，28 页均加与下一页对应的转场。另附 `output/56页逐页模拟讲稿.md`、`output/模拟试讲稿.md`（既有28页，含转场约4500字符）和 `research/观众视角试讲复盘.md`。没有录音计时或真实观众测试，不将模拟判断写成观众实际反馈。
- 最终生成 `/tmp/eap-bp-text-20261002/20261002-103253`。实际 PPT/PDF/渲染/备注 56/28；对象2726/1437、图片236/105与原稿一致；文字缺失和对象越界告警均0。逐页去文字后的幻灯片XML完全相同，数字集合及页序相同，除幻灯片文字和备注外所有包内文件字节一致；财务底稿和原勾稽记录原样保留。实际读取PPT核对口播56/56、28/28，转场28/28及原来源URL均保留。原稿18个源文件及成品摘要全相同。
- 查看全部56页总览和28页总览；第3、8页新文案出现不自然换行后只缩短措辞重导，最终放大查看3、8、40页。全部生成、导出、渲染与机器检查均远程完成，归档传输SHA256一致；未在本机PowerPoint/Keynote原生打开。没有业务代码、部署、实例或夹具改动，没有本机依赖安装/构建/应用测试，没有git commit/push。
- 本轮仅修改表达，来源核查日仍按9/29、10/1记录，工程页明确沿用截至10/1的原稿；未把10/2其他线程的默认池迁移进展悄悄更新进固定事实底稿。需要更新事实时应另行按证据修订。后续继续文字优化应从本目录继续，不从上一轮重排汇报版继续；保持56/28页和原版式，除非用户另行明确要求。


## Codex 进展：Agent 调优及系统能力方法调研（2026-10-02）

- 用户澄清“性能优化”重点是 Agent 调优，随后要求调研论文/开源工具，并把调度、权限、恢复、观测等其他功能纳入，参考联通与字节项目。当前优先级按此更新，保留此前集成加固任务。
- 本轮完成 `docs/research/agent-system-methods-20261002/` 八份研究/工程文档：主报告、T1–T8实施合同、提示词/上下文、路由/工作流/训练、项目方法映射、平台来源、任务约定与质量复核。核对论文、作者仓库/发行版本、原始项目主稿和 EAP 代码；来源留档在 K8Substrate `artifacts/eap-system-methods-20261002/`，28份公开快照/元数据及 SHA256 清单。不是全部论文全文归档。
- 选型判断：先真实 Agent 基线/候选实验与共享预算，再受限 GEPA 提示词优化；MIPROv2作对照，ACE/TextGrad/AFlow/路由/RL按条件后置。GEPA独立包与DSPy适配不能算两种算法；ACE适应成本下降不保证推理Token下降。Agent Lightning v1已重构，AgentScope旧Tuner与2.x兼容未验证，不沿用旧接口想当然接入。
- 联通参考阶段画像、已用+在途预算、生效确认与恢复核验；字节参考任务作用域、收窄委托、提交前授权、工具效果能力与分级证据。两份原稿都不是已完成实验成果，字节最新交付还明确未原型/未证明。OPA/OpenFGA/Kueue/Temporal/SPIRE等仅方法或后续候选，本轮没有安装接入。
- 代码核读发现：LearningView新增仍固定agentId=1及示例内容，LearningFeedbackService为CRUD；EvaluationDatasetRunService对已有actual判定，不是新候选Agent重跑；Workflow已有FIFO和最小loadScore选择，缺统一预留；GenseeSafetyService是字符串规则；工具回执是运行时自报，不能称独立效果证明。这些明确列入改进合同，未在本轮修改业务代码。
- 下一步按T1真实反馈与版本化Agent实验、T2共享预算、T3GEPA试点推进；权限/工具合同与身份续期/认证持久化可并行形成独立工程交付。候选、样本、初态和权限隔离；未知不自动重放、不提前释放预留。原默认池/旧快照/工具/故障夹具保持，不重跑已完成验收。Keycloak仍emptyDir，不得直接换空PVC重启。
- 已做独立反例审查并修正配对实验因素、回执证据等级、预算硬保证前提、调度计划校验四处表述；文档本地链接与归档摘要检查通过。此次无模型调用、无训练、无测试/构建/部署、无commit/push；没有把历史354/156等测试基线说成此次新测。


## Codex 进展：按精品版视觉基础完成 BP 精修（2026-10-02）

- 用户授权彻底美化后，明确反馈后续版本不如 `EAP融资BP_精品版_20260929`，认可原版可视化并要求在其基础上优化；又明确允许字体大小与颜色变化以突出重要信息。后续 BP 编辑偏好：保持丰富机制图、数据图与信息密度，沿精品版深蓝/蓝青橙、柔和阴影、渐变、图标、3D 章节图和真实截图精修，不再默认采用扁平化简化。页数与页序仍保持 56 页，既有 28 页讲解路径同步。
- 交付目录 `BP/EAP融资BP_视觉精修版_20261002/`，四份 PPT/PDF、56/28 页图片与预览、设计规范、逐页视觉调整、核验记录、原口播和财务底稿；`BP/README.md` 已更新入口。以精品版视觉语言和组件为参照，正文与演讲备注继承文字优化版。重绘 27 页：2、3、4、5、7、8、9、12、22、24—29、32—34、36、37、39、40、42—44、47、52。其余保留产品架构、生命周期、行业弧形、截图、时序、历史项目等优质图形，并增强标题与局部文字层级。
- 图形表达恢复采购箭头、三线大数字、13% 治理环图及 SAM 推导、三层接入、调度账本与节点反馈、分支拓扑、版本发布回路、双实例共享领取与跨 Agent 预留解释、27.5% ROI 环图及现金净值条形、直接毛利环图、人力变化与成本构成、融资阶段时间线。旧版未经确认市场外推、主观能力球评分、团队岗位和收益数字未恢复。关键文字 107 处加粗/颜色强调，数据、机制、正文与来源分层；研究方案与限定 PoC 状态保留。
- 最终生成 `/tmp/eap-bp-premium-refined-20261002/20261002-1136-final`。PPT/PDF/图片/备注均 56/28；对象 2758/1480，图片 289/138；文本缺失与对象越界告警均 0。原可见文字、备注与既有原生图表数据逐页相同；保存后重新打开两套 PPT 验证完整口播及转场。六份财务文件与原版 SHA256 一致、原六项勾稽通过；原文字版 PPT 与本轮 inputs 摘要一致。最终归档及四份主成品下载 SHA256 已核对。
- 查看详细 7 张与讲解 4 张总览，重点放大技术、采购、纳管、调度、权限、ROI、毛利、竞争、预算等页。修正短句孤字、三线重点颜色继承、图标别名和 EAP 标志、预算结论距离；最后两处字号变化仅重渲染对应详细 26/44 与讲解 14/25 页，重导并核验完整 PDF。下载流程中断后续传已有成品；未因此重跑业务验收。视觉依据远程 PDF/PNG，没有本机 PowerPoint/Keynote 原生检查，也不代表用户已认可本次审美。
- 所有生成、导出、渲染与检查都在已授权远程环境，复用现有依赖和字体。没有本机安装/构建/应用测试，没有业务代码、部署、实例、夹具或凭证修改，没有 git commit/push。来源日与工程状态仍沿用原稿 9/29、10/1；本轮未将其他线程 10/2 默认池迁移进展或 Agent 调优调研加入固定事实稿。正式融资额、付费客户、公司任职、投入、成果权属与真实收益仍需实际材料。
- 后续视觉从本目录 `source/design.py`、`source/pages.py`、`source/build_deck.py` 继续；运行 `source/build_remote.sh`，通过 `n1.sh` 在远程生成。不要在脚本运行中修改同一个入口文件；已完成目录可用 `source/download_completed.sh` 续传并校验。用户纠正前的未采用扁平化代码仅在 `research/早期扁平化草稿/` 存档，不作为后续设计基准。历史版本均保留。

## Codex 进展：恢复竞品图谱，并完成优秀 BP 对照优化（2026-10-02）

- 用户明确反馈上一版“改得不好，一些竞品图谱没有了”，随后要求继续，并建议研究其他优秀 BP。确认原精品第5页三家国际对标被采购卡替代、第37页六维竞争矩阵被缩成五家产品文字；详细第11页行业总图虽仍在，但讲解稿遗漏。今后不能把用户允许的文字/字号优化理解为删除图谱或压缩竞争覆盖。用户偏好仍是20260929精品版的丰富可视化与信息密度，不代表其认可此前视觉精修结果。
- 独立完成 `BP/EAP融资BP_精品图谱恢复版_20261002/`：第5页恢复ServiceNow/Datadog/Zenity能力组合；第11页保留六分区、21个外部产品及标识；第37页恢复六维矩阵、原有厂商并增加AWS，共九行。用官方公布/限定PoC/研究中/范围待核代替主观强弱评分。核对官方产品公告、文档与仓库，来源和适用范围写入备注及研究说明；无竞品同环境实测。
- 按新建议对照嘉为第3/8页、一微第12页、华翊22页文字结构与重点技术/融资页；阅读LinkedIn原始44页B轮材料及创始人复盘，远程渲染并查看PDF第12/15/37页；阅读Front A/D轮创始人官方复盘、Sequoia和YC指引。Front原稿图片CDN连接超时，未逐页目视验证，研究报告已如实说明。学习投资命题、证据组织和资金里程碑，不移植其他公司的经营、客户或技术成绩。
- 最终入口 `BP/EAP融资BP_精品标杆优化版_20261002/README.md`；56页可编辑PPT/PDF、28页讲解PPT/PDF、逐页口播、PNG预览、研究报告和可切换的改稿对照HTML均已保存。详细稿页数和顺序不变；讲解仍28页，加入详细11页替换重复的详细25页，后者仍在56页详细稿。最终相对上一视觉精修版累计涉及2/4/5/11/37/47/48七页，其余49页保留；相对图谱恢复版仅改2/4/37/47/48，其余51页XML与备注一致。
- 新修改：第2页集中说明采购触发→产品切入→商业扩展；第4页解释UNKNOWN及“8次并发→2次领取”，保留753→16合成探测边界；第47页把时间线落实到基线/回归、合同/回款/验收、交付工时和扩容成本证据；第48页保留3D主视觉并回到技术起点、商业路径和本轮目标。真实买方访谈、付费订单、业务收益、团队角色及融资条款仍待实际材料，不得编造。工程状态仍沿用截至10/1原稿，未混入其他10/2开发线程新进度。
- 最终远程 `/tmp/eap-bp-benchmark-20261002/final`；PPT/PDF/PNG56/28，备注56/28，对象3022/1811、图片297/166，PDF文字缺失与越界告警0。首轮发现开篇图标缓存名称错误及证据位置松散，修复后重导。目视复核修改页和讲解图谱；财务CSV/模型/勾稽JSON字节不变；归档上传下载SHA256一致。详细PPT SHA256=d0aee489a978701cd553aa31277ac1e57cb9f3392d51092f7a9507d6848e08df；PDF=8be3e1149224db9660914e950f33ac978de074e3d23e16dbbe4e26444b3dfb73。未本机原生打开PPT，未真实观众试讲；辅助对照HTML未浏览器交互测试。
- 全程未改业务代码/部署/实例/夹具，无本机依赖安装或产品构建测试，无git commit/push。历史精品版、文字优化版、视觉精修版及图谱恢复版全部保留。后续以本标杆优化版为起点，同时维护图谱完整性、口播与页码映射。


## Codex 进展：T1真实反馈与版本化Agent提示词对照已上线（2026-10-02）

- 用户继续实施调研合同T1。本轮实现真实反馈表单、实际Agent选择、可选任务/Trace逐项归属校验、JWT提交/评审主体、同键幂等与expectedStatus状态比较更新；反馈正文仅VIEW_CONTENT可见，JWT种子Agent不可用于新反馈。采纳仅元数据更新，不部署/改提示词。权限集中PlatformPermission及/me，前端无JWT/角色判断。
- 新增V56/V57迁移与实验模块。认证读取真实参考Runner基线，冻结actorUID/template/镜像digest/发布版本/配置摘要、样本和评分器；候选只改系统提示词，同模型温度0/空会话/工具禁用/同输出上限。1–3公开手工样本配对，最多6次调用、输出≤256，全平台一实验；新输出及实际Trace/Token覆盖/Runner、端到端、排队延迟独立显示。未知计数保持null，不算金额，无自动发布。
- 后端数据库准入锁、提交前持久执行意图、Runner私有fsync日志与原键重放，响应不确定/180秒失联UNKNOWN并停止剩余项，新实验不能绕过UNKNOWN。禁止自动重新花费；总HTTP期限130秒/响应64KiB/禁重定向/逐调用身份配置复核。日志最多256项，单进程私有持久目录前提，不保证删除存储、回滚快照、多进程共享或分布式exactly-once。UNKNOWN人工对账解除尚未做。
- 新增反馈11/实验12项集成和6项前端行为回归。最终全部在远程：后端377/377、82套、0失败/跳过；前端162/162、vue-tsc/生产构建；Runner-race13顶层/18含子用例（新增实验4），均PASS。最初旧权限数量、TypeScript可选参数/索引和节点缺gcc/CGO问题修正后重测；C工具链仅独立集群测试Pod安装，没有本机安装或构建。
- 新Agent43、v1=eap-agents/a43-v1-39668d、实验1，3组公开文本真实6calls/6Trace网关归属一致，原键重复不加调用。基线input160/output110=270Token、平均Runner901ms；候选input223/output132=355Token、平均693ms，两版3/3。样本少且Token增加，不宣称优化/成本收益或生产SLO。实际浏览器提交反馈1条（双激活仅1POST），治理主体REVIEWING→ADOPTED，评审前后运行配置相同且模型仍6。
- 四角色真实JWT接口及浏览器1500/390px通过，operator/viewer内容403，governance/admin200，只有admin实验提交；登录变化清私有状态/迟到结果、未知提交保持原键、移动表单/对照卡片通过。最新只读browser runId=5136d3c5-992c-4092-9801-e7b81de6d9cf，各角色业务写入0。第一轮真实反馈写入独立留档；不把最终只读轮冒充写入验证。
- 新默认配置式Runner已固定为10.186.133.66:5001/eap-agent-runner@sha256:d0f49d3f854f86cb6cccf6bc058fa65c1932e6df7fe5093ba4178c1b7faa1eb2；同Agent43发布v2，用户systemPrompt/model与认证实验基线/镜像/版本一致，额外模型0。v1/v2最后均SUSPENDED。旧模板/workerPool镜像及旧夹具不被替换。/root/eap-t1-evidence.json completed/executionCompleted/reviewCompleted/browserCompleted/configuredRunnerCompleted/suspended均true；原requestKey和结果必须保留，不重放模型。
- backend=10.186.133.66:5001/eap-backend@sha256:0b4b5834333128317929e572fb271023d8ea7aa2c35a8a76551f607c219452da；frontend=10.186.133.66:5001/eap-frontend@sha256:2cf47b7d6fae7f67692d4d7c0a2145c22490bbc20fd38096c69f7081064e2ee9；均Ready并核对实际imageID，buildId=2026-10-02T04:00:00.729Z。DB实际迁移57，EAP_EXPERIMENT_ENABLED=true；清单同步固定镜像/开关，不为此重新应用Keycloak。Runner当前构建必须列main.go/tools.go/experiments.go三个文件。
- 合同docs/AGENT_PROMPT_EXPERIMENTS.md、操作tools/eap/tuning/README.md。证据K8Substrate artifacts/eap-agent-tuning-20261002/index.html，45份实测源码manifest/局部patch、四角色截图/真实结果/最终状态已导出；归档SHA256=ad517b510d025cc0ef6730e7ec3836b815acf086f6d01ac59d65b1d22ab73fb3。SSH中断后沿原键继续配置验收；源码包和浏览器SHA、每次新runId验证防止空stdin/旧报告误PASS。无密钥/密码/token导出，无commit/push；本地隧道自动重连54906保持（旧70891已失效）。
- 下一步T2跨调用共享已用+在途预留/准入，随后T3受限GEPA及独立留出；当前不是共享Token/金额硬预算，更没有接入GEPA/DSPy或训练。工具效果合同、统一身份/凭证续期、旧模板迁移、认证持久化和长任务恢复继续保留；Keycloak仍emptyDir，不直接换空PVC重启。本轮不声称全系统生产完整验收。

- T1收尾已核对：45份源码按摘要同步/root/eap-src，最终健康记录模型仍6、两个actorSUSPENDED、前后端实际imageID/Ready及新版Runner/实验开关匹配；本轮6个Job和6个临时Pod全部不存在。清理先--wait=false后即时QA消失断言失败，随后只轮询确认全部删除，最终PASS，无强杀。38份导出文件/链接/归档SHA复核通过，final-health.json和file-manifest.json保存；本地5173/version.json及8180认证公开配置HTTP200，隧道54906保持。未重放调用、未重启Keycloak、未commit/push。


## 主任务与优先级（用户再次强调，2026-10-02）

主线是持续完善EAP智能体系统，不因单项收尾、BP或页面美化而偏离：

1. Agent调优：质量、成功率、延迟、Token与工具策略；不以底层CPU/启动探测优化替代Agent业务调优。参考论文/开源方法，先可复现对照与共享预算，再受限提示词优化。
2. 调度优化：任务/工作流/实验的共享预算、已用+在途预留、确定性准入、并发与恢复边界，结合联通阶段画像/预算、生效确认和恢复核验方法。
3. 权限与治理：统一实例身份、凭证配送/续期、收窄委托、执行前授权、工具效果证据，结合字节任务作用域/委托/授权/分级证据方法。
4. 集成闭环：EAP持续纳管运行底座与任务网络创建/运行的智能体，完善创建→调度→执行→观测→治理→恢复链路；不能把局部PASS说成全部生产整合完成。
5. 产品交付：清晰导航和可视化、前后端权限一致、真实演示与远程完整验证；前端尽量不展示AX/Substrate产品名。所有构建测试部署远程，不commit/push，不导出密钥。

当前正在T2共享预算与准入，服务于以上主线；T1已完成，之后T3受限GEPA。保留身份/权限/恢复加固任务，单项完成不删除总目标；旧夹具和完成/未知标记不重放。

## Codex 进展：融资 BP 技术创新与互联网图表研究（2026-10-04）

- 用户要求进一步优化技术创新，简单易懂且创新性强，并强调充分调研互联网上优秀 BP 的 PPT charts 和展示；再次确认主稿一共 56 页。**主版必须保持 56 页、原页序及完整竞品图谱**。28 页只是已有辅助讲解稿，不能将它说成主版压缩或新的主稿页数。继续沿 20260929 精品版的丰富可视化、信息密度、深蓝 / 蓝青橙、图标与层级优化。
- 新目录 `BP/EAP融资BP_技术创新优化版_20261004/`。以 10/2 精品标杆优化版为基准，修改 3、4、23—34、50、51、53，共 17 页；39 页 XML 和备注保持；第 5、11、37 页国际对标、行业总图和六维矩阵完整保留。56 页可编辑 PPT / PDF / PNG、逐页模拟讲稿、既有 28 页辅助讲解稿、研究说明、来源登记与参考对照 HTML；入口 `BP/README.md` 已更新。历史版本没有删除。
- 实际取得并查看 Front B 轮 24 页、D 轮 11 页公开原图及 6 张研究总览，放大检查 B 4/10/12/13、D 5/7/9 页；此次解决了 10/2 原图 CDN 未取得的问题。阅读 LinkedIn 创始人原稿复盘，承接此前原 PDF；Buffer 只查看官方第 3 页与作者说明，不能说全稿目视完成。YC 两张旧图 404，只依据核实正文；Sequoia 是内容指导，不冒认官方 PPT 模板。未核实的 PostHog / dbt deck 不作依据。公开参考图只在研究对照目录，不充当 EAP 产品或客户证据。
- 借鉴成对指标、图内注释、平台关系与替代方案比较，形成四种技术机制：按任务分资源、用实验改进 Agent、按实际效果恢复、随任务变化收紧权限。调度明确状态触发同步、候选排除证书、保留硬约束的语义压缩；调优明确 GEPA 候选、真实执行、独立评测和预算权限门禁；恢复用“重启生效但回执丢失”泳道解释；安全用字节原稿工单权限收窄、必要协同保护到提交及多源联合阻断解释研究。成熟研究与拟议增量分列，不能把一般调度、幂等、GEPA 或状态化预算说成独创。
- 工程事实主动更新至 10/2：T1 3 组公开样本、6 次真实模型调用、6 Trace、两版完整文本匹配均 3/3；原生堆叠柱图显示基线 160+110=270、候选 223+132=355 Token，候选更耗，不能称调优成功。默认两类运行池迁移与旧快照兼容已有记录。当前同 Agent 领取计数不等于跨 Agent 任务预算；UNKNOWN 不等于通用外部恰好一次；GEPA、留出集、动态拓扑、效果账本及动态授权仍待实现 / 验证。
- 核对 Agentix、Murakkab、GEPA / MIPRO、AgentChaos / MAST、Temporal、Progent、AgentSpec、AgentCore 官方文档及当前实验服务 / Runner 源码；联通、字节私人申报书仅本地核读，未上传外部服务。65 / 14 是论文的配置 / 失败分类范围，不是 EAP 测试覆盖；+10pp 及降本门槛仍为研究目标，研究材料不自动证明公司独占知识产权。
- 所有生成、导出、渲染与检查在授权远程 `/tmp/eap-bp-tech-20261004/final`，复用依赖与字体。最终 PPT / PDF / PNG / 备注均 56 / 28；对象 3060 / 1811、图片 293 / 174，缺正文字符与对象越界告警 0；原生图表包含数据与嵌入工作簿，财务模型 / 现金情景 / 敏感性 / 勾稽文件与基准逐字节保持。逐页目视检查全部 17 个改动页，修正标题换行、卡片边距、验收横条和脚注距离、图表数字居中及权限例；下载连接短暂关闭后沿既有成品重试，归档摘要核对一致。
- 本轮未运行模型、产品测试或生产验收，未改业务代码、部署、实例、夹具或凭证；无本机依赖安装 / 构建，无 git commit / push。视觉依据远程 PDF / PNG，未本机 PowerPoint / Keynote 原生播放，未真实观众试讲。后续从该目录 `source/technical_refine.py` 经 `source/build_remote.sh` 继续；不要在入口脚本运行时改同一入口。T2 / T3 实现仍按既有主任务推进，BP 工作没有替代系统研发目标。
- 最终远程成品结构核查通过：两个新原生图表系列数据 / 嵌入工作簿完整；对照 HTML 26、主版预览 17、讲解预览 11 个本地资源链接无缺失。主 PPT SHA256=`dde3b214c3adcdb40838689c62f55eea3c3b2bb897b31e3d193137be855e6f13`；主 PDF=`60faef578eac9f85df351804da457bf4a05fe7be3b75b6facdbb4d9acc743cde`。静态链接核查不代表浏览器交互验收。

## Codex 进展：融资 BP 投资逻辑与具体表达优化（2026-10-04）

- 用户要求继续优化，并明确“不要有太多 AI 味道”。后续沿用专业 BP 写法：少口号、少排比、少泛化的“闭环 / 赋能 / 沉淀”，具体说明客户问题、操作方法、已有结果、交付和金额；保留技术详页与信息密度，不把内容改成泛泛介绍。
- 新目录 `BP/EAP融资BP_投资逻辑优化版_20261004/`，入口 `BP/README.md` 已更新。主稿仍为 56 页、原页序；修改 2、3、9、14、15、16、18、24、34、36、38、47、48、52，共 14 页，其余 42 页及备注 XML 与技术创新优化版一致。第 5、11、37 页竞品图谱、第 4 / 29 页原生图表及嵌入工作簿、现金 / 敏感性 / 财务口径和勾稽文件保持。历史版本全部保留，28 页仍为既有辅助讲解稿。
- 聚焦已有 Agent、生产工具和审批要求的运维团队；首期用告警诊断、负责人批准、凭许可执行、业务系统效果核对解释产品。统一架构和六阶段流程：真实对照、许可与 UNKNOWN 已有局部验收，共享预算、独立评测、自动采用、效果查询和动态任务授权仍有待完成 / 验证。技术总览说明减少重复同步、独立样本评测、先查实际效果再恢复、权限证据维护等研究用途，详细机制保留在既有技术页。
- 收费统一为讨论情景：15 万验证费抵扣首年 50 万年约，转约补收 35 万；18 个月 20 项验证、10 项转约对应累计回款 650 万，期末年化合同额 500 万。阶段目标 6 个月 3 项验证、12 个月 5 项年约、18 个月 10 项年约与原模型一致；金额不是实际订单、已实现收益或正式融资条款。首单仍为拟议 8—12 周验证，列明验收人、支持责任与转约条件。
- 全部生成 / 导出 / 渲染 / 检查在 `/tmp/eap-bp-investor-20261004/final` 完成，传输摘要一致。最终 PPT / PDF / PNG / 备注 56 / 28，对象 3064 / 1795，图片 295 / 177；文字缺失和越界告警 0。放大查看全部 14 个修改页与关键辅助页，修正孤字、卡片溢出、表格与横条遮挡、脚注间距及缺失图标。附 10 页修改前后对照 HTML、修改说明、逐页模拟讲稿和最终核验记录；对照 / 主预览 / 辅助预览本地资源链接 44 / 17 / 11 均完整。
- 工程记录沿用截至 10/2 的事实，没有新增外部市场数字、模型调用或产品验收。研究参考沿上一轮 Front / LinkedIn / Buffer / YC / Sequoia 原始资料；本轮未冒称重新研究或验证全部产品。未本机 PowerPoint / Keynote 原生播放，未真实观众 / 录音试讲，静态链接核对不等于浏览器交互测试。
- 无业务代码、部署、实例、夹具或凭证修改，无本机依赖安装 / 构建，无 git commit / push；系统研发主任务与 T2 / T3 优先级保留。继续修改从新目录 `source/investor_refine.py` 经 `source/build_remote.sh` 远程生成，勿在入口运行中编辑同一脚本。主 PPT SHA256=`e392fd8d63edbb0a7523b9fe1a0472c98f1b11c965e8d0551c8dcc45ac0e8ed5`，主 PDF=`afabc9d78f1a83cefb2a4c517aa7dc86bf31c676cd1da255881e621d15996caf`。


### Codex 进展：T2共享预算与准入已发布并有界验收（2026-10-04）

- 用户“继续”仍执行原Agent调优、调度、权限治理主线；T1之后接T2共享账本，下一项T3受限GEPA与独立留出集。不用基础CPU/启动优化替代Agent业务调优。合同SHARED_BUDGET_ADMISSION.md，T1–T8研究方法仍为后续依据。
- MySQL V58新增账户/预留/唯一结算/额度变更与共享锁；部署共享、智能体、已验证任务、运行实验联合检查已用+在途+余量。派发前独立事务持久预留；工具许可与原策略同事务，拒绝回滚策略消费。未知用量/响应保留输出占用，持有者/迟到/重复回执受限，超上界记实际并冻结。实验Trace由服务端确定；显式稳定调用键及新Runner任务键防重。
- 新manageBudgets只由PlatformPermission定义，治理/平台管理员配置，前端只读/me能力；CAS expectedRevision、认证主体及budget_limit_changes存档。成本与价值新增共享预算页，三指标/范围进度/100条内限高意图表和390px表单；身份切换丢弃过期数据，未知修改先刷新，双激活1次PUT。
- 远程后端399/399、83 suites、0skip；前端166/166与类型/构建；普通Runner-race16顶层/21含子用例，任务网络Runner5/5；四真实角色API/浏览器1500/390px PASS，页面/API错误0。两个Runner区分已证明派发前拒绝与无证明上游429，不错误释放未知。627源码摘要本地/已测目录一致；相对绿色T1的28文件manifest与patch先保存，再镜像/root/eap-src。首轮新账本编译缺括号已修复；任务网络镜像首轮OutOfcpu、容器未启动，其他构建结束后100m/128Mi独立Job重建通过，不采用失败镜像。
- 真实独立Agent44，v1实例由证据保存。工作流1+实验2+任务网络1共4个实际网关调用，全部成功/SETTLED；实验与任务范围各自计数及Agent累加核对通过。耗尽后的新实验FAILED/BUDGET_EXHAUSTED而非UNKNOWN，0新增模型；三种原请求重放0新增。真实四角色写权限200/403、旧revision409；界面原值CAS保存不改变used=4。完成标记/root/eap-t2-evidence.json completed=true，最终实例SUSPENDED且网络任务确认休眠。保留所有键、模板/快照、历史实验，不重放、不清UNKNOWN。验收请求最初被保留环境变量规则正确拒绝（0实例/0调用），脚本改env={}后部署；普通/网络输出声明默认1024，实验64，不绕过保留前缀。
- 最终镜像：backend 10.186.133.66:5001/eap-backend@sha256:9cff8e157d546fc0eb0088e5dd6e76610de99923af7788a4395d1c6e7ab9164d；frontend .../eap-frontend@sha256:41eaf700d865290b7dfe1b71ff0012936dddb40691e5b0490a37a91b09746b3e；Runner .../eap-runner@sha256:4df1c70dcd2a3d03948fef82989d9f37122ac4ef39f5964eaafb368be6bbf75b；网络Runner .../eap-ax-runner@sha256:96f9bad61f3969830028d35dd3f2896820d3309aa32d970826a3081aec1edc47。EAP_BUDGET_ENABLED=true；实际前后端imageID、Ready与version.json核对，buildId2026-10-04T01:20:56.122Z。只定向更新EAP、新默认Runner用于未来发布，旧模板/认证不迁移。
- 证据K8Substrate artifacts/eap-budget-20261004，包含真实记录/最终健康、日志/28文件patch和manifest、四角色截图、构建准入失败元数据。原导出SHA256=227b04cf1a3d510c51e9baf4f016e4154bf62d964faf84636c9fa3a9623064dc，41文件内存检查实际Secret值通过；不导出token/profile/HAR。ops清单四digest/开关与Runner三工作副本已更新，操作tools/eap/budget/README.md。临时资源导出后只清本轮，保留旧夹具/完成/未知/恢复标记；5173/8180自动重连隧道保持。没有commit/push，没有本机应用编译测试。
- 覆盖边界：没有输入Token或账单金额硬限额、预算公平/老化调度、多租户预算实体、租约迁移、未知人工对账或自动优化器；未做真实在途节点故障/长期压力。工具预算计许可，不能当作外部效果证据。旧机器凭证智能体归属信任仍须统一实例身份/配送/续期加固；Keycloak仍emptyDir，不换空PVC直接重启。下一项T3及这些加固目标继续保留，不能称全部生产整合完成或性能已提升。

## Codex 进展：融资BP核心技术精品版与展示研究已交付（2026-10-04）

- 用户指出此前核心技术未充分参考联通、字节申请书和华为报告，指定 `/Users/cpf/Documents/教学/研究生/校企联合课程/outputs/ai-native-aiops-2026-latex/Huawei-Multi-Agent-2026-Full-Updated.pdf`，要求使用grilling并凝练创新性。用户随后明确协作与资源调度联合优化、动态最小权限与安全执行、Agent质量与成本调优**全部为EAP核心**，并要求展示借鉴优秀作品、专业精致。继续时保留三项并列，可靠性贯穿且有独立详页；主稿56页和原页序不变，不强制选择单一核心。
- 新目录 `BP/EAP融资BP_核心技术精品版_20261004/`，BP/README.md已更新。主56页与既有辅助28页可编辑PPT/PDF/PNG/备注/讲稿齐全；附12组改稿前后对照、6组优秀作品展示对照、方法页码映射、原材料复核意见和核验说明。历史版本全部保留。相对投资逻辑优化版修改2、3、9、14、16、23—34、47、50、51、53、55共22页，其余34页幻灯片和备注XML逐字节一致；竞品图谱5/11/37完整保留。
- 核读联通2026最终研究方案及LLM调度专题、字节9/23创新凝练申请书，华为指定报告实际246页。参考阶段×位置×资源准备、全任务成本与候选质量界、LLM全段提案及确定性复核/确认、稳定操作与UNKNOWN恢复；华为的三图、协作配置、证据交接、记忆撤回、语义/系统观测、受控干预和发布门禁；字节的区域负证据与能力闭包、充分状态来源/位置/提交保护联合选择、可控多源偏好极小阻断。私人原稿未上传互联网检索或外部AI服务，研究材料不自动证明EAP原创、生产收益或公司独占权属。
- 展示实际重看Front B第12页、D第5/9页（承接此前24/11页原图），用户嘉为第8页、一微第12页；取得并远程渲染MASS官方ICLR26页演示的12/15/21页，YC官方正文重查。采用三图关系、阶段/准备双路径、候选成本区间、六配置变量环图、协作拓扑、真实效果泳道、故障传播、共享预留与提交保护、权限变化表。保留精品版深蓝/蓝青橙与3D章节。参考图仅用于研究，不当EAP产品或客户证据。
- 两处重要技术修正：质量证书排除仍合法且下界15高于保留方案上界12的候选，区间9—16仍需展开，不能以容量不合法替代质量证明；调查时指标复核与获批处置后的独立效果核验分别表示。原材料复核还修正已知违规先阻断、统计风险界不等于零风险、不可分解联合约束才需额外协调，以及提交端检查与效果提交原子衔接。六配置变量是跨华为章节综合归纳，不宣称原报告已有统一六变量算法。
- T2状态依据本记忆已有10/4代码和有界验收记录更新：同Agent工作流/实验/任务网络4次真实调用结算，原请求重放0新增；不是本轮新跑。输入Token、金额、多租户公平、动态委托和远端提交强保证仍未解决。T1原生图保留160+110=270对223+132=355Token与两版3/3，不能称优化成功。新增第26页原生图20/21/22/14明确为联通构造反例，含嵌入工作簿，既不证明EAP收益也不证明LLM优于完整枚举。华为辅助AgenticSRE26例仅核读并在备忘录保留时间/Token增加等限制，未迁入BP作EAP结果。
- 所有生成、导出、渲染和机器检查远程 `/tmp/eap-bp-methods-20261004/final` 完成，复用现有依赖/字体；上传、成品和核验下载归档摘要一致。最终PPT/PDF/PNG/备注56/28，总对象3003/1760（含图片279/168），正文缺失及越界告警0。两处原图4/29的XML、数值及工作簿与底稿字节一致，五份财务文件一致。12组改稿/展示参考/主预览/辅助预览本地资源链接53/13/17/11均无缺失。查看主7张及辅助4张总览，全部22个修改页大图与修正后的关键页，修复孤字、英语标签拥挤、数字边距。未本机PowerPoint/Keynote原生播放，未真实观众或录音试讲，静态文件检查不冒充浏览器交互验收。
- 本轮没有模型调用、产品测试或生产验收，没有业务代码、部署、实例、夹具、凭证修改，没有本机依赖安装/构建，没有git commit/push。系统研发主线和T3及身份/权限/恢复加固继续保留。下一次BP从新目录source/methods_refine.py经source/build_remote.sh远程生成，勿在入口运行时编辑同一入口；最终资料由source/finalize_remote.sh整理。主PPT SHA256=`f3a01623561d333158487ba619cb820fba41831cb3a58523fbf93bf75047584a`，主PDF=`ff204c05322778dcbea740e0f0d9ba6dcb8f22a84c22bfbccca12d08a36204d1`；四份成品本地只读摘要与远程一致。


### Codex 进展：华为报告方法纳入主线，T3适配器与授权预算边界开发（2026-10-04，进行中）

- 246页报告已核读相关机制并映射到T1–T8，固定PDF摘要44a90b78ec52cf94608717da26dfc64d4b4526a0233bd4f88e2797b0fa54c16b，页码和研究/论文/算例性质分开；没有把华为交流场合称成华为产品能力，没有上传私有全文到外部研究服务。
- 固定GEPA0.1.4、release commit8b0ce6cd99a234f6b74daf37558a2ac0ce18f975、wheel12b971039599625c156d2231f6d72a29c31a22e9c237689459b5f1a3c353f532。远程初次pip索引缺/simple失败；直接官方下载并核wheel修复，实际安装/导入/接口验证通过。
- adapter实际GEPA与SIMPLE、精确字段/UTF8、训练验证组/输入去重、结果ID对齐、部分验证不能选优、用途接口、全部样本分母、未知不重放等19项远程通过。首轮17项中1失败暴露GEPA内存缓存列表ID均从0开始，把训练评价复用为验证；关闭该缓存并加反例。真实包提案路径还能捕获异常回退，不受raise_on_exception完整约束；锁存未知/合同错误，后续不得新反思。合成Backend不是实际EAP服务或真实模型收益。
- V59开发表、根预算冻结、T1内部链接及相同基线两次真实Trial、服务端Trace根归属/反思、状态和独立用途检查、冻结主体/实例/profile/maxTokens、搜索禁留出/留出禁未封存候选已实现。隔离完整后端411/411、83 suites、0skip；随后补保持公共T1原指纹的迁移测试，待下一完整验证。无业务模型、无UI或部署，本轮没有把原T2源/生产镜像换成WIP。
- 用户问部署了吗：答复本轮尚未部署，线上仍T2；会继续补任务接口、持久协调器/单次claim、反思/封存/留出/建议流程、页面，再远程回归部署和独立真实验收。不要以本条阶段收尾替代原主要任务。
- 方法证据/root/eap-t3-test-out，隔离源码/root/eap-t3-method-final-src，保留最初失败日志；Python探测Pod eap-t3-python-probe-1004失败、eap-t3-python-probe-retry-1004运行，两个backend方法测试Job已完成，未清理。绿色/root/eap-src与所有历史夹具/完成/UNKNOWN/故障标记保持。
- 继续保留调度公平/老化/阶段画像、范围委托/独立效果合同、身份配送续期/旧模板、认证持久化、长任务恢复/T4–T8；不本机应用编译测试，不commit/push，不导出密钥。Keycloak仍emptyDir，不能直接换空PVC重启。

## Codex 进展：BP背景恢复、技术方法深化与典型系统截图（2026-10-04）

- 用户继续反馈“核心技术不够丰富、内容单调、系统截图不典型”，随后明确前面的背景不如9月29日精品版。当前BP以9/29前部视觉和论证为基础，避免再把公司、市场背景压成少量概括。主稿仍56页、原页序；三项核心并列，原竞品图谱与财务底稿保留。后续优先沿此稿精修，不能将28页辅助稿称成新的主版。
- 新目录 `BP/EAP融资BP_技术与产品深化版_20261004/`；主PPT/PDF `output/EAP_BP_Premium_Deep_20261004.*`，既有辅助28页 `EAP_BP_Pitch_Deep_20261004.*`。BP/README.md已更新；历史版本不删除。相对核心技术版修改2、3、6–9、12、17、19–22、24–28、30–34、50、51、53、55共26页，其余30页及备注XML逐字节一致。
- 前部恢复公司幕封、六模块全景、具体市场依据、四驱动与五阶段演进；市场图扩展为2025–2030六年原生可编辑柱图。实际查看艾瑞报告原图，数值55.9/129.4/252.7/430.9/627.0/815.1亿元及70.9% CAGR；原图从案例厂商360亿方云官网转载取得，不声称读完整报告。市场、政策、MCP/A2A与八案例官方公告本轮重核，访问日10/4；其他历史竞争引用保留各页日期。取消守护型Agent占比乘总市场的旧80–120亿元推导，SAM仍内部1,000家×50万元=5亿元。CyberArk使用SEC 211亿美元会计购买对价，Traceloop金额未披露，Arize仅核实签约，不假定交割。
- 使用grilling既有三核心共识及deepresearch/browse指引。核读备忘录 `research/联通技术深化核读.md`、`华为技术深化核读.md`、`字节技术深化核读.md`；私人全文未发互联网或外部AI服务。方法进入可见图形：阶段准备与完整计划、集群/节点/执行分层、覆盖全部候选的误差触发同步、代价区间排除与双通道语义压缩；六配置分层搜索、三类记忆/依赖撤回/条件写入、TIMEOUT→EMPTY传播反例与单变量配对回放；必要状态反例覆盖、来源/位置/保护协议联合选择、能力闭包、区域负证据κ=(θ,R)、多源偏好包含极小阻断与覆盖重试/新ID/迟到/恢复/入场的版本屏障。已知MaxWeight、凸优化及MASS/GEPA不冒称原创定理，32次评测不等于32次模型调用，20/21/22/14构造图不等于收益。
- 经授权n1.sh只读采集远程现有控制台，临时Pod仅本轮 `eap-bp-browser-1004`，远程安装浏览器依赖；本机未安装。45张采集归档中选用11张：17页表单/版本/休眠登记实例，19页双Agent生成复核时间线，20页Agent44任务Span与内容元数据，21页调用额度用尽与四条结算，22页T1指标/执行记录局部/实际公开输出。最终采集runId=`fd3d1684-c720-49e5-b627-55699fcc0637`，业务写入0；选用图片SHA与清单及PPT嵌入图全部对应。采集调试有被阻止的请求和端口占用重试，最终清单CAPTURED/无错误；没有提交配置表单、新任务或模型调用，不把调试失败隐写成一次全程无异常。
- 本轮只展示既有T1/T2记录，不新增工程验收。任务Span不冒充完整模型/工具跨Agent轨迹；内容Token估计不等于实际结算用量；T1候选更耗Token，不宣称调优收益；Runtime完成不自动证明外部业务效果。T2同Agent四次调用结算及UNKNOWN保护仅沿10/4已有记录，不宣称金额硬预算或外部提交强保证。T3研发与此BP分别进行，本轮未操作其源码、部署、测试Pod或证据。
- 全部PPT生成/PDF导出/1600px逐页渲染/机器核验远程 `/tmp/eap-bp-depth-20261004/final`，复用既有依赖字体；输入/输出归档摘要一致。主56/辅助28页及备注全部齐；对象2969/1753，图片274/166，正文缺失及越界告警0。查看主7张及辅助4张总览、全部26个修改页及修正页，修复大字与说明重叠、深色图对比度、节点孤字、区间贴线及截图裁切。未本机PowerPoint/Keynote原生播放，未真实观众或录音试讲。
- 结构最终远程断言通过：30页与备注、竞品5/11/37、原生图4/29 XML/数值/工作簿、五份财务文件均保持；新增市场图7及构造图26缓存与嵌入数据一致。附26组修改对照、7组9/29背景直接对照、6组展示参考；静态链接109/31/13/17/11全部无缺失，检查不冒充浏览器交互验收。本轮临时浏览器Pod已定向删除，并等待删除完成；无业务代码/业务部署/实例/夹具/凭证改动，无git commit/push。
- 四份成品本地只读摘要与远程一致：主PPT `eff4ad4cbef0d56e90754e926cdd14d2c28077a77540f7e4ecf164bc2ce37682`，主PDF `b717bb413f62424d2f97c219a0439001d9a3dbbe49c9db4fd830e55194abf217`；辅助PPT `11e53fdc79e5fed7767beae8f67b8ff672e12b85031104ab758b9e410b33af7f`，辅助PDF `756cdaaa8a45c2ab36b7e5fc8ea28baea019e3ad4ab3d939078a77186d12a925`。下一轮从source/depth_refine.py与depth_finalize.py.inc修改，经build_remote.sh/finalize_remote.sh远程生成核验；不要在入口运行时编辑同一入口。

### Codex 进展：BP实景恢复与技术图解重做（2026-10-04）

- 用户进一步指出大屏丢失、多个核心功能截图缺失、截图大小不齐、功能缺乏完整追踪、技术页仍是简单卡片。本轮完成具体改稿，保持56页及原页序；三核心仍并列：协作与资源联合优化、Agent质量与成本调优、动态最小权限与安全执行，可靠性贯穿。以后不要再次把大屏或多功能实景替换成单个验收截图，也不要仅靠同款卡片介绍方法。
- 新目录 `BP/EAP融资BP_实景与技术图解版_20261004/`，主稿 `output/EAP_BP_Premium_Visual_20261004.pptx/pdf`，原28页辅助稿 `EAP_BP_Pitch_Visual_20261004.pptx/pdf`；BP/README.md指向新稿。相对Deep版重做16、17、19–22、24–28、30–34、50、53共18页，其余38页及备注XML逐字节一致，历史版本不删除。
- 19页恢复9/29精品版原始运行指挥大屏；原图实际为2026-09-11早期PoC示例，明确日期，不当作当前健康或客户规模。20页恢复目录、协作拓扑和基础设施三视角；17页创建与版本发布，21页四节点编排画布与既有执行时间线，22页共享预算、真实公开样本对照与采纳反馈，31页保留实际任务Span及输入输出元数据。合计13张原始PNG：3张9/29素材、10张10/4已有只读采集；本轮未重新采集或创建临时Pod。
- 17/21双窗口规格5.90×3.55英寸，20/22三窗口规格3.84×2.70，31两观测窗口5.90×1.65；同组及对应跨页窗口等宽等高，图片保持原比例。宽幅记录在统一外框内完整显示，避免居中裁掉左侧时间线与表格字段。13图原始字节、采集清单SHA和PPT嵌入SHA全部核对；截图溯源与尺寸.json记录各图裁切、框尺寸、时间与哈希，不生成仿真UI。
- 16页原生八阶段曲线：创建/发布/编排/执行/观测/评估/治理/恢复，标出Agent身份、配置摘要、计划版本、稳定操作、Trace、实验、策略版本及父证据；21页曲线解释任务键/实例/回执关联。曲线是功能及追踪对象映射，不冒称一次真实任务贯通全部八阶段，也不制造跨Agent OTLP Trace。核验原生cubic Bezier段分布16/21/24/25/31/33/34/53为4/1/3/3/1/2/6/1。
- 技术页改为三层任务/协作/资源联合图、准备复用甘特与原生成本图、替代候选变化曲线与代价区间、8→4→2配置搜索树及通信拓扑、稳定操作恢复时序、EMPTY/UNKNOWN配对传播、必要状态反例及原子预留时序、16项有限权限候选区域、六执行路径的版本屏障、证据依赖DAG和OR/AND干预图。依据此前联通/字节/华为核读材料，未再次网上调研或重新实验。16候选12/3/1拆分是字节四工单例在明确有限假设下的编稿推导；κ₁/κ₂均相关并重验，分别保留/撤回。构造20/21/22/14、32次评测、并发金额例均非实测，已知基础算法不冒称原创。
- 页内移除“同尺寸”“恢复旧版”等改稿过程说明，使用可汇报的产品表达，过程放在README/研究说明。T1候选Token更多，反馈采纳不等于自动发布；Trace只有任务Span，内容Token为估计；Runtime完成与独立业务结果分开。版本记录不证明实例当前运行或全部隔离组合已验收。T2沿10/4已有验收，未介入T3研发、部署、任务、测试或证据。
- 所有PPT生成/PDF导出/1600px逐页渲染/结构核验在 `/tmp/eap-bp-visual-20261004/final` 经n1.sh完成，复用原依赖字体；本机仅文本编辑、原图查看和成品接收。修正曲线标签贴线、流程截图居中裁掉节点、标题断行、宽表字段和大屏白边，未在入口运行期间编辑同一入口。最终56/28页、PDF/PNG/备注数量均匹配，正文缺失与越界0；原生对象3071/1743，图片266/160。放大查看18个修改页及修正页，主7张与辅助4张总览；未本机PowerPoint/Keynote原生播放或真实观众/录音试讲。
- 最终远程断言：38页及备注、背景2/3/6–9、竞品5/11/37、所有原生图表与嵌入工作簿及五份财务文件保持。18组前后对照 `research/实景与技术图解对照.html`；对照/主预览/辅助预览静态本地资源75/17/11均完整，非浏览器交互验收。输入及成品归档传输SHA一致，无业务代码/部署/实例/夹具/凭证改动，无本机依赖安装/构建，无git commit/push。
- 最终主PPT SHA256=`30e075c37bb744d61904d2a864cfda844d11ce9b7548c20ff867080087819838`，主PDF=`975cdc5dca5df32a7a011597d4bc732068e22887bb2bc63601dfb3f055c60f97`；辅助PPT=`de9cb50344fe13338ab4f89a184bfbc4f8d5d2475c5ed38e7597e0227b3f77ab`，辅助PDF=`3c0797d7163eb7a1fb1997fca4ac1a1d69997236b8d11410e2d23f0bd8f62bd1`。后续沿source/visual_refine.py，经build_remote.sh及finalize_remote.sh远程生成；不要把28页辅助稿当成主稿，也不要覆盖历史版本。

## Codex 记忆保存：第4页产品进展重写（2026-10-05，进行中）

- 用户明确否定“工程起点：少做无效探测，避免盲目重试，守住调用限额”一页，随后要求保存记忆。修改主4／辅助5，56页和原页序不变，从实景与技术图解版继续；保留大屏、13实景、同尺寸窗口、八阶段曲线、技术图解、背景、竞品图谱及财务。
- 已核读串行双Agent、T1真实对照与T2共享预算记录并查看原页，拟改成产品进展：实际协作与结果交接、两版质量和Token对照、跨执行入口共享预留及耗尽拒绝，并对应三项核心技术。原753→16是就绪探测，不能代表Agent性能突破；调用次数限制不能代表完整安全。
- 事实边界：三组独立验收；T1两版3/3、6调用、270／355Token，不能说降本；T2同Agent4次调用结算／上限4，耗尽后拒绝0新增，不能说金额硬预算或动态授权全完成。协作联合优化、留出评测、动态权限等研发方向分别标明。
- 保存时尚未修改或生成新PPT，新页设计、远程导出、检查和前后对照仍待完成。该记忆追加没有取消当前改稿；不得把进行中写成已交付。全部文档生成／导出／渲染／检查仍远程；不改业务代码／部署，不commit／push，不动另一线程T3开发。
- 完整续接记录：`/Users/cpf/Documents/项目/创业/深圳矩维智能/EAP/.context/checkpoints/20261005-000526-eap-bp-page4-product-progress.md`；同名git-state.txt保留当前分支和脏工作树路径／统计，属于整个工作区状态，不等于本轮修改。技能按项目既有工作区checkpoint保存，未做外部同步。

### 范围更新：Claude Code审阅全稿（2026-10-05，进行中）

- 用户随后要求“请使用claude code优化一下内容和形式”，明确“是所有的页面”，再补充“好的内容可以保留”。**当前任务已扩为全稿56页逐页审阅、择优修改内容与形式**，不是仅第4页；好内容与图文保留，页数／页序、三核心并列、原大屏／13实景／曲线／图谱／财务等约束继续。
- 已读 `/Users/cpf/.codex/skills/gstack-claude/SKILL.md`，实际解析本机已安装Claude Code CLI，并用只读Read/Grep/Glob、禁Bash/Edit/Write、禁slash skills及空MCP配置调用；不是仅口头声称使用Claude。仅向其提供BP文字和渲染图片，不提供业务源码、密钥、私人申报书全文或客户数据。
- 授权远程生成的第4页草稿主56／辅助28、文字缺失／几何0告警已通过；后续严格XML字节对照发现第21页序列化差异，草稿未作为新成品交付。此次全稿优化不以该草稿“55页字节保持”作已完成承诺，仍以最新已交付实景与技术图解版为对照，保护图表数据、截图与财务。
- 全稿文字框ID／原文／坐标／字号和56张远程渲染图已整理为 `/tmp/eap-bp-claude-full-review/`。三个独立只读审阅段为1–20、21–38、39–56；调用正在进行，尚未取得全部意见或完成全稿修改。输出将保留审阅记录、采纳与不采纳说明；宿主据此实际修改PPT并远程生成、逐页检查。
- 新拟交付目录 `BP/EAP融资BP_Claude全稿优化版_20261005/`；旧实景版仍为已交付基准。不能因调用了Claude就称全稿改完；需核对意见与实际采用、图表／截图／财务、56／28页、文字与版式，再保存最终状态和成品路径。没有业务代码／部署修改，没有本机依赖或应用构建，没有git commit／push。


### Codex 进展：T3 已部署、真实反思截断修复在测（2026-10-05）

主线仍未全部完成：Agent业务调优、共享预算调度、权限/身份/效果证据、恢复及产品闭环。T1/T2有界完成，不替代T4–T8。T3已接任务API、单次claim/持久协调器、反思实际证据核对、候选封存与服务端留出、能力权限和流程页面；远程完整backend431/84 suites零失败零跳过、frontend174+类型/构建、Worker23通过。三镜像已部署并rollout，/version.json=2026-10-04T14:32:28.269Z，实际digest见/root/eap-t3-images.json；绿色/root/eap-src尚保留T2，未被覆盖。

真实Agent45 Root1 FAILED/REFLECTION_TRUNCATED，严格7次实际调用、输出794、0在途0未知；反思输出512全部reasoningTokens，未进入留出/未采用。保留/root/eap-t3-evidence.json与所有原键/已完成T1/T2/UNKNOWN/旧夹具。官方DeepSeek文档确认默认thinking启用；修复为新根冻结_reflectionThinking，对明确支持的deepseek-flash/pro仅反思调用显式disabled，输出上限仍512，评价Runner/model/options不改。补截断付费失败/模式冻结、轮询响应竞态回归；remote/root/eap-t3-reflection-v2-src与测试Job/Pod正在跑，修复尚未重新部署。

下一步：修复验证/镜像digest上线，再用独立新根、不同公开样本进行有界验收；新证据/root/eap-t3-reflection-v2-evidence.json，复用Agent45保留原7次消耗，智能体32次总上限不增。旧FAILED不重放，UNKNOWN不能新建意图。roles/browser/finish和脱敏证据导出尚待；真实成功优化不能说已验收。Keycloak仍emptyDir、认证/旧模板迁移/统一身份续期/工具独立效果/公平调度/长任务恢复仍待。不本机构建、不commit/push。

## Codex 进展：Claude Code全稿优化成品与记忆保存（2026-10-05）

- 用户要求Claude Code优化“所有的页面”，并明确“好的内容可以保留”。本轮已完成全部56页逐页审阅、择优修改及成品，不是仅第4页。新目录 `BP/EAP融资BP_Claude全稿优化版_20261005/`；BP/README.md已更新，主56页及原页序、原28页辅助稿保持，历史完整保留。
- 实际使用已安装Claude Code，分1–20、21–38、39–56三段，日志证明56张单页PNG均Read，兼读文字/字号/坐标/备注。实际模型claude-opus-5-5[1m]，三次成功，仅Read/Grep/Glob、禁写工具与MCP。只提供BP文本和渲染图，不提供业务源码、凭证、客户数据或私人申报书全文。原始意见/实际读取/建议取舍均存research；落实128项文字视觉建议，47页内容或细节调整，全部备注补开场，3项封面替换建议未采纳。
- 第4页改为产品进展：双Agent交接、真实质量/Token对照、跨执行入口共享预算准入，右列对应三项研发核心。三组记录独立：T1六次、两版3/3、270/355Token，不说降本；T2同Agent三入口四次结算及4/4耗尽、新实验派发前拒绝0新增，不说金额硬限额或动态权限完成。T1重放0新增有独立证据。全稿统一技术/章节/定位与客户问题、工程底座、研究路线；可靠性贯穿，保留性能/可靠性/安全性价值目标。
- 好的背景与图文保留：原大屏、13张原PNG、同尺寸窗口、八阶段曲线、丰富技术机制图、竞品5/11/37及财务数字。重点改文字、字号、颜色、图注、断行、标签，去掉原连接线意外主题阴影，保留3D插画/图标/卡片效果。21页完整原执行面板裁切含四节点/终态/未知核对提示；44页给原六根形状柱补已有数值和Q1–Q6；50页三类记忆图例与DAG分开。财务标题明确测算，人员角色空缺和待确认不编造。
- 全部生成/导出/1600px逐页渲染/结构核验经n1.sh远程 `/tmp/eap-bp-claude-20261005/final` 完成；本机仅文本编辑、Claude审阅、传输与查看。最终PPT/PDF/PNG/备注56/28，原生对象3118/1788、图片263/157，正文缺失/越界0；原13图原始及嵌入SHA、框尺寸、曲线段保持；原图表部件/工作簿与五份财务文件字节保持；11/37文字保持，5只统一定位。47组前后对照/主/辅助预览静态资源98/17/11完整，下载SHA与远程一致。
- 查看主7/辅助4总览及修改/修正页大图，修正深色文字对比度、页眉标签底框、50页标记遮字与21页面板外残行。未本机PowerPoint/Keynote原生播放或真实观众/录音试讲，静态链接核对不冒充浏览器交互验收。市场/工程证据复用此前记录，本轮无EAP调用、业务写入、新产品测试/验收、业务代码/部署/实例/夹具/凭证改动，无本机应用依赖或构建，无commit/push；另一线程T3及后续主任务不因BP完成而完成。
- 最终主PPT SHA256=`b0ed2a6b2e83de70fa245f2fcc46a205fd2dd3f84b061c2173f9a234b0043d4a`，主PDF=`0b21852ef809f670aac4157b645b0be4882d292abd640bf1dcf9db9ea72b5c17`；辅助PPT=`272b1890b61f8aa3a31edb21aea4411127352863c025351393dba3d1a9f7d656`，辅助PDF=`8e303f8595e0f9c92d2e4931095bb42b8c8ccb3dfed9689e3dcd3d14a7aa4489`。后续修改从本版source/claude_refine.py与build_remote.sh远程生成，不在执行时改同一入口，保留远程Visual和Progress依赖目录。
- 完整结束状态checkpoint：`/Users/cpf/Documents/项目/创业/深圳矩维智能/EAP/.context/checkpoints/20261005-100840-eap-bp-claude-full-final.md`；同名git-state.txt为工作区整体状态，不等于本轮业务修改。旧进行中记录保留，续接以本次成品及checkpoint为准；未外部同步或提交。


### Codex 进展：T3受限提示词优化已部署并完成有界验收（2026-10-05）

**主线未全部完成。** T1真实反馈/对照、T2共享预算、T3受限提示词搜索/独立留出有界阶段已交付。下一项优先T4工具合同、收窄委托和独立效果证据；保留T5身份/续期与认证持久化、T6通用恢复/发布取消、T7分级风险观测、T8知识/模型路由/工作流策略，以及调度公平/老化/阶段画像。报告及字节/联通方法是设计依据，不当成已验证的创新收益。不能将一项PASS说成系统生产整合或Agent整体性能已提升。

T3有任务API、持久协调器/单次900秒claim、根+子实验+反思共同预算、反馈实际证据核对、候选只改system_prompt、服务端封存留出、统一能力与桌面/手机流程页。Worker固定GEPA0.1.4，训练/验证关闭包内索引混淆缓存，未知锁存防提案回退重发，无供应商/全局密钥/SA token，UID65532且只读根。新根对明确支持的deepseek-flash/pro仅反思冻结thinking disabled、512上限不增；运行评价程序/模型/选项不改。金额和输入Token硬预算、自动采用、结构搜索不在本项。

远程完整backend431/84 suites零失败零跳过；真实模型截断修复涉及优化/实验/预算67通过（不说修复后重跑全量433）；frontend175、类型/生产构建、真实GEPA/Worker23通过。真实四角色API与1500/390px浏览器、正文权限、异步清除、页面/表单无横裁通过，浏览器业务写入0且无未捕获异常/失败API。实际控制台JWT/假claim不能代替机器身份：403；三imageID和前端buildId已核。

Agent45/eap-agents/a45-v1-6aecb6：根1 FAILED/REFLECTION_TRUNCATED，7调用/794输出，反思512全reasoning，0未知/预留；没有留出、没有重放或清零。不同公开样本的新根2 COMPLETED，16调用含2反思，输入1203/输出603；合计23/32调用、输入1752/输出1397=3149Token，全部SETTLED。留出人工/候选各1/1、两次新基线各1/1；人工115 vs基线81Token，选定候选93 vs另一基线92，建议KEEP_BASELINE。没有确认算法收益。原根请求回放新增0，实际运行基线不改；最终实例SUSPENDED。保留两根、所有原意图/模板/快照/旧T1/T2及UNKNOWN标记。

最终backend=10.186.133.66:5001/eap-backend@sha256:eb706f6dbe443f2408c94486e534aa5850a3f359eeedd4ade1436aec9d408f2b；frontend=10.186.133.66:5001/eap-frontend@sha256:85b15ac66d55707caf4e58e0987f0f60deea461a5fdda97c0f7057655d37d454；optimizer=10.186.133.66:5001/eap-prompt-optimizer@sha256:2c05a3aa110662b5c83e242c1231eb31f5e7e2c4e945eac4dc10b9475da5add4。Runner仍T2固定版本。MySQLV59，前端buildId2026-10-05T01:56:00.860Z。主清单已固定digest/优化开关，内部服务清单tools/eap/optimization/poc.yaml；不整份重应用或重启Keycloak。

641源码摘要一致、相对绿色T2的31文件精确patch、Secret内存扫描和导出236文件摘要通过后，已镜像/root/eap-src。证据K8Substrate artifacts/eap-prompt-optimization-20261005/index.html；归档SHA256=37b71ca51035f2a2904effd39e690b8b02efd9d47a2e2b097e4cbe2d52c2ab51，2998656bytes，首次反思失败及浏览器定位歧义均保留。节点/root/eap-t3-evidence.json保留根1失败，/root/eap-t3-reflection-v2-evidence.json completed=true；不重放模型只为截图。Root2原提交保存ID查询，不能恢复时误选另一根。

所有依赖/测试/构建/浏览器/部署远程，本机只文本编辑/哈希/证据查看；无commit/push。保护另一线程BP内容，PROJECT_MEMORY只追加，不改融资稿。Keycloak仍emptyDir、旧模板全局凭据及实例身份迁移仍待；不换空PVC重启。现有本地5173/8180隧道保持。后续从T4开始，不再重复本项真实调用。

## Codex 进展：一张PPT展示EAP完整功能Demo（2026-10-05）

- 用户逐步明确要展示EAP本项目的完整功能过程，允许Demo、不要求实际执行，并最终要求“呈现在一张PPT上”。本轮已完成独立单页PPT/PDF/高清PNG和56页整合主稿，目录`BP/EAP融资BP_完整案例版_20261005/`。只替换原49页附录幕封，其他55页及备注XML精确保留，原28页辅助稿沿用Claude全稿版，BP/README.md已更新；历史版本不删除。
- 双Agent生成并复核交接说明的案例，用7段原生可编辑蛇形曲线展示创建、发布、编排、权限预算准入、协作交接、观测、评估反馈、恢复归档。展示输入、两个结果、完成态和预算条，底部4张等尺寸真实EAP功能截图；不再使用通用IT告警情景或网页演示。Demo参数2/4、余量2、原键重放0新增是模拟完成态，既有预算实景4/4等只作功能对照，明确非同一次执行。
- Claude Code实际辅助单页内容/展示设计，并两次只读Read最终PNG。第一轮指出截图排序、上下段归属、输入条间距、预算空白、连线换色等问题，已修改并远程重建；第二轮pass=true。实际建议、读取记录、采纳说明保存在research，不覆盖初轮未通过记录。独立页01与整合页49分别核对，原17/19–22实景和31观测引用确认；人工放大查看最终独立页及主49，曲线/文字无交叠。
- 全部生成、导出、1600/2000px渲染、结构检查经n1.sh远程`/tmp/eap-bp-case-20261005/final`完成。最终56/1页、备注与PNG匹配，文字缺失和越界0，原生对象3208/104、图片268/7；55页和备注、原图表/工作簿、13原截图、五份财务文件保持，4窗口等尺寸且原PNG哈希匹配。下载与远程摘要一致。本机未应用构建/依赖安装，未PowerPoint/Keynote原生播放或真实观众试讲。
- 主PPT SHA256=`bf00f602805906ff4d8f79097fdadb4bba5544dabd5b33be4aa8f3354ad7a4de`，主PDF=`e23ece142a2e42cc1bb52f164a4cd26283660fb379d1a8fddcd382530c9efc35`；单页PPT=`4e7ae6d700eda0792eb9e88469613c98133bb0432bad30ab286336fcf95e773e`，单页PDF=`fe36966c202313c42b1b3af6c9839f8ee8860571d576769932d571513111d976`。续接checkpoint：`.context/checkpoints/20261005-eap-function-demo-final.md`。
- 本轮无新EAP模型调用、业务写入、任务/实例/发布/夹具/凭证修改，无业务代码或部署修改，无commit/push，没有干预另一线程T3。研究核心与后续研发不能因Demo贯通而标成完成；三核心、可靠性、安全性、性能及原56页图文约束继续。后续从case_refine.py经build_remote.sh远程生成，不在入口执行时修改入口。

## Codex 进展：BP最终合稿、全文措辞精修与完整导航实景（2026-10-05）

- 用户要求全文减少AI味道、增加能看到左侧导航的系统全景，并明确“结合之前的版本，利用claude code形成最后的版本”。已交付`BP/EAP融资BP_文字精修版_20261005/`中的`output/EAP融资BP_最终合稿_20261005.pptx/pdf`，主稿56页与原页序保持，BP/README.md更新入口。同步28页辅助及独立1页Demo，所有历史版本保留。
- 结合9/29背景与图表、后续技术机制图及完整案例稿。主稿168处正文精修、56页讲稿重写，跨三份稿件257处正文编辑记录；用具体功能、动作和判断替代口号、抽象堆词与模板问答。三项核心并列，性能/可靠性/安全性目标、竞品5/11/37、19页运行大屏、原生图表/工作簿及五份财务文件保持。55个非20页的图形/图片/位置/尺寸/裁切保持，备注原来源URL全部保留。
- 第20页加入现有2400×1500工作台原PNG，未裁切，左侧六模块导航、页头、操作区与任务列表完整可见；原目录/拓扑/资源三张截图保留为等尺寸窗口。原13张产品PNG字节全部保留，增加全景后14张。右侧小图用于功能概览，不宣称其细节可在投影中逐字阅读。
- 第49页保留8节点曲线流程、输入/输出/完成态/额度条和4等尺寸真实功能截图。按Claude意见明确2/4为Demo设定、4/4为独立历史预算记录，并注明未新增实际调用；保持单页同步。流程仍非同一次真实EAP完整执行，任务Trace分别关联，研究算法不因Demo而完成。
- 实际Claude Code审阅：全部56张主稿大图与历史参考真实读取，最终前后28页逐页JSON意见，13项建议全部直接采纳或压缩落实。Opus长响应预扣费4次失败；Sonnet后半完成，前半thinking输出超上限及旧会话恢复system空块失败，最后用每页一行精简材料、fresh Sonnet low会话完成。失败未冒称一次成功；全部Read路径在BP，未发业务源码、凭据或原申报书全文。最终意见、采纳/待核处理及实际读取metadata在research和source/claude_final_review.json。
- 针对待核事项，只读确认25条识别rule、发现方式分组与T1历史377/162记录；沿已有核读确认华为p139的32次评测构造例及主稿51页。2026-10-05重查MarketsandMarkets Agent、Gartner治理与保护AI三份原官方预测，数值不变，不叠加不同口径。独立财务复算800+61×30/12=952.5人日、47.625人月，前6月3项验证/12月5份年约/18月10份，与原月度模型一致；仍为假设。商业合同、团队任职和历史成果归属继续列待确认。
- 经授权n1.sh在远程`/tmp/eap-bp-natural-20261005/final`完成所有生成、PDF导出、1600/2000px渲染及结构核验。最终主PPT/PDF/PNG/备注56、辅助28、独立1，正文缺失/越界0；截图、来源、图表和财务保护检查通过。远程归档与本地下载摘要一致，人工放大检查最终20/28/32/40/49/51及此前全稿总览，标题、脚注与截图说明无交叠。未本机PowerPoint/Keynote原生播放，未真实观众或录音试讲。
- 主PPT SHA256=`03318117c4392c788ea8edf4fec6a2860bb61d502d980633acc8f5170e123770`，主PDF=`834e8c6ae03821aeaa5033493049ae76e3af02dd295a55305b6b8105ae322cf6`；中文交付别名与内部Natural文件字节一致。续接checkpoint：`.context/checkpoints/20261005-eap-bp-final-merge.md`；.context/claude-session-id为成功前半会话。
- 没有业务/部署/任务/实例/夹具/凭据修改，没有本机依赖安装/应用编译测试，没有commit/push。保留另一线程T3工作；交付前该线程已追加T3完成记录，本稿仍按已有T1/T2及各截图日期展示，不声称概括当前全部系统状态。后续若更新T3，要另核其真实证据与边界，不将T1与T3数字混合，也不以完整配置搜索研究图代替GEPA单提示词验收。

## Codex 进展：Claude生成核心方法强化BP与外部融资展示借鉴（2026-10-05）

- 状态DONE：用户明确允许超过56页，要求Claude Code实际生成、借鉴其他公司优秀BP、美观且专业。已交付`BP/EAP融资BP_核心方法强化版_20261005/output/EAP融资BP_核心方法强化版_20261005.pptx/pdf`，主稿64页，原56页顺序保留，插入8页；BP/README.md已指向新版本。原56页文字精修版及全部历史稿保留，旧28页辅助稿未同步扩充，不能称本版主稿。
- 实际Claude Code先核读当前技术与历史图片，再生成五份原生PPT绘图模块a1/a2/a3/B/C，共11页完整绘图：原24/28/32重绘，新增成品26/29/31/33/35/37/40/42。长A输出超过24000 token且片段遗漏安全核心，整份未采用，拆分生成后成功；全部原文、调用metadata、实际Read路径与主代理修正随稿保存。实际模型claude-sonnet-5-5，只允许Read/Grep/Glob，禁Bash/Edit/Write及MCP；仅提供BP、公开原稿图片、去敏方法概要，未发送原私有申报书全文、业务源码、凭据或私有遥测。
- 10/5复核Front Series B/D、Intercom原融资材料、YC设计和Sequoia写作指南，实际查看并让Claude读取Front/Intercom原稿图片，具体借鉴对照图、产品动作图、清晰标题与渐进关系图，不照搬其数字。方法结合此前联通/华为/字节原文核读及本轮四篇官方论文页面；图解为阶段服务曲线+候选表、全候选成本界+双通道压缩、六维配置分层搜索、共享搜索预算、外配置/内RL双回路、恢复持有、AgentChaos配对泳道与三分母、W/位置/协议联合保护、可行见证+排除集与三类执行契约/OR-AND干预。三核心并重，可靠性贯穿，成熟基础与拟议研究增量分开。
- T3复用10/5既有验收：仅system_prompt受限GEPA；失败根消耗7调用、完成根16，共23/32，3149Token全部结算，候选93vs基线92Token、留出1/1vs1/1，保留基线，不声称收益。7次消费不等于7次调用全部失败。搜索/训练反馈来自训练评测，独立留出不回流；RL图明确训练集终态评分，EAP尚无训练能力。研究32次评测不等于工程32次模型调用。恢复120/6.5为GiB·s构造、同60s、交接另加0.5；0.1/2持有图按5%高度，不能把成本构造当实测降本。权限包含极小不等于最小费用，未知不能判不可行，ACK不代表无效果。
- 原竞品5/11/37（成品5/11/45）、原背景与市场、大屏19、未裁切导航控制台20、全部14产品PNG、同组截图规格、原49页曲线完整Demo（成品57）保持；Demo仍示例设定，不冒充一次实际完整执行。原生图表与工作簿、五份财务文件字节保持，52个未改机制结构的原页图形/位置/非文字对象保持。更新成品55页资金需求引用50、57页观测引用36及来源索引页码，原研究报告物理页码不重映射。
- 所有PPT生成、PDF转换、1600px渲染与核验经n1.sh在远程`/tmp/eap-bp-core-20261005/final`完成，复用既有依赖字体；最终PPT/PDF/PNG/备注各64，正文缺失和对象越界0。放大查看核心图、全景与Demo，并查看全部8张总览。Claude成品抽查实际读取20/24/26/30/31/35/40/42八张PNG，采纳26页扫描标签必改和35页说明位置建议；没有称其审阅全64页。最终26/35/55人工回看，页码引用和图标文字正确；本机只读摘要与远程一致，未本机PowerPoint/Keynote原生播放或真实观众试讲。
- 最终PPT SHA256=`b9f2b394a42f8f91eed4582293d356fd3c84963e50f58efc6372e20ef60cec1e`，PDF=`b6e63b836ee3b6c873c1c3f4d1981964c07d6535853a7643c3c8a97759cf35eb`。续接`.context/checkpoints/20261005-eap-core-methods-bp.md`；源入口`source/refine.py`，修改绘图模块后经`source/build_remote.sh`远程生成，不在运行期间改同一入口。
- 本轮无新EAP调用、业务写入、产品验收、业务代码/部署/实例/夹具/凭据修改，无本机依赖或构建，无commit/push；工作区其他工程改动属于并行主线，不因本次BP交付而完成。

## Codex 进展：Claude投资人评审与第15页指标栏意见（2026-10-05）

- 用户要求Claude Code从投资人角度判断故事与融资条件，随后圈出第15页底部数字询问是否合适。本轮只完成评审及建议，没有改PPT/PDF。报告入口为`BP/EAP融资BP_核心方法强化版_20261005/research/投资人评审_20261005/投资人评审与修改优先级.md`，同目录保留Claude原文、任务和实际读取记录；本版README已链接。
- 实际Claude全稿读取3份JSONL覆盖全部64页正文与8张全稿总览，未声称逐页读64张大图。会话a148c644-0e4a-4579-acf8-950801246d27，claude-sonnet-5-5，成功71.629秒；第15页专项另实际读取PNG，会话cb17688c-bc0f-44e6-8d9f-f4b40c6f6f86，成功13.456秒。只允许Read/Grep/Glob，未读业务源码、凭据、私有申报书全文或客户数据。
- 模拟投委会判断“技术故事完整，商业没有闭合”：材料支持技术天使二次会谈，企业软件种子可继续沟通、未足以投资承诺，产业投资可谈场景合作。属于材料判断，不是真实机构意见，不给无依据融资概率。六项缺口为买方采购触发、真实商业推进、经营团队投入/成果使用范围、技术到客户收益、确定融资请求/里程碑、交付复用经济性。没有展示不等于现实不存在，早期融资也不一律要求已有收入/PMF或全部研发完成。
- 主代理复核财务口径/月度和季度表，抵扣与现金峰值勾稽正确；问题是价格、成交、工时假设未获外部验证。27.5%只属现金ROI情景，不代表所有客户的统一采购门槛。Claude只读BP历史证明陈述，未本轮独立核验原证明/权属。保留未确认事实，不以删除附录64或隐藏负结果解决叙事；前5分钟应先讲具体场景与工程起点，T1/T3负结果继续保留在证据中。
- 第15页底部6次/4-4/377-162/4角色/6通道混合内部验证与结构数，不宜大字当市场成绩：4/4是已用/上限而非成功率，377/162是T1当时回归而非客户价值。建议底部改为统一纳管、执行可核对、额度可控制，原验收数字移至已有进展/证据页或工程尽调。橙色预算内候选搜索需区分T3受限system_prompt已验与六维配置研发目标；现图没有声称六维已完成，但阶段标注落后。尚未执行这些版式/文案修改。
- 10/5参考YC种子融资与Sequoia写作官方指南，链接和范围在报告，不把它们当中国天使融资硬规则。没有真实投资人/客户访谈、外发消息、EAP调用或业务改动，没有本机依赖、构建或commit/push。输入成品摘要保持：PPT b9f2b394a42f8f91eed4582293d356fd3c84963e50f58efc6372e20ef60cec1e；PDF b6e63b836ee3b6c873c1c3f4d1981964c07d6535853a7643c3c8a97759cf35eb。后续用户要求改稿时再沿64页生成入口远程执行，不把本轮评审说成已经修改交付。

## Codex 进展：第15页投资人展示修改已落实（2026-10-05）

- 用户追问“修改了吗”，本轮实际落实前述第15页修改，不再停留在评审建议。新主稿`BP/EAP融资BP_投资人展示修订版_20261005/output/EAP融资BP_投资人展示修订版_20261005.pptx/pdf`；保持64页，其余63页及备注字节保持，旧核心方法版不覆盖。BP/README.md更新入口，前轮投资人评审仍链接原目录。
- Claude Code实际读取第15页原PNG及绘图helper，生成`source/claude_page15.py`主体函数；会话d798930c-c4e2-4d38-a1d4-07ea4042fe17，claude-sonnet-5-5，成功13.080秒，主代理仅补入helper import。原始代码、任务与读取记录随新版保存，未提供业务源码、原私有申报书或凭据。
- 底部大号6次、4/4、377/162、4角色、6发现通道改为“统一纳管／执行可核对／调用受控”及具体动作，五项工程数字与范围留在第15页讲稿和`output/工程核验数据说明.md`。六模块矩阵、字号/色彩/品牌保留；白色格改“提示词对照／受限搜索”，橙色改“完整配置搜索”，副标题/脚注明确受限提示搜索已验、完整配置搜索研发中，没有夸大客户收益。
- 全部生成、PDF导出、1600px渲染及检查在远程`/tmp/eap-bp-investor-display-20261005/final`经n1.sh完成。64页PPT/PDF/PNG/备注齐全，正文缺失与越界0；ZIP只改变slide15.xml和notesSlide15.xml，其余63页、所有14产品PNG、竞品5/11/45、大屏19、完整导航20、Demo57、原生图表/工作簿及五份财务文件保持。实际放大查看第15页成品，底部文字和双行单元格无遮挡；提供修改前后PNG/JPG。归档下载与最终文件本地只读SHA一致。
- 新PPT SHA256=`aba7a986896a4ca6c026207407c0a67d8790b1afd1636466f9ea45b9c017e690`，PDF=`7b5cc18bad6267bef3ba29befe909359b670f01b48eedeeac79d54e5c2e580d3`。续接`.context/checkpoints/20261005-eap-bp-page15-investor-display.md`。只有第15页展示意见落实；团队任职/成果权属、真实客户推进、融资请求等缺口没有被填造，也没有声称全部投资人意见完成。
- 没有EAP调用、工程重测、业务代码/部署/实例/夹具/凭据修改、客户/投资人外发、本机依赖或构建、git commit/push；原核心方法远程目录和素材仍为依赖，不清理。


### Codex 进展：T4受控工具作用域、委托与效果核验已部署（2026-10-05）

**主线尚未全部完成。** T1/T2/T3有界阶段已交付；本轮完成T4第一个受控工具适配。下一项T5统一实例身份、安全配送/续期及认证持久化；业务工具逐个适配、T6通用恢复/发布取消、T7分级观测、T8知识/模型/工作流策略，以及调度公平/老化/阶段画像继续保留。字节/联通/华为报告方法是设计依据，不当作已证明收益。

- 合同docs/SCOPED_TOOL_CONTRACTS.md：controlled_counter_increment/poc-counter-v1，明确测试对象/接收实例/增量/期限、最多4级收窄委托、兄弟共享不可变根额度；提交事务复核整条祖先策略/身份/撤销及预算，可信工具存储原子提交计数和效果UUID。普通执行方回执仍RUNTIME_REPORTED；不是全部外部工具或客户系统的效果/撤销保证。未知操作保留原标识，不自动重发。
- 远程完整backend459/85 suites零失败、错误、跳过；真实集成修复后八类相关回归121通过，不声称修复后再跑完整462。frontend186、类型检查/生产构建，Runner-race18顶层/29含子用例通过。首轮安全通道/事务mock、迁移版本、前端类型、Runner Trace、测试构造器失败，以及旧同JVM运行挂起日志保留。最终完整回归采用逐类JVM，不抹掉首轮失败。
- 真实Agent46/47，两个子委托共用根cap2：首次工作流提交+3，八并发争最后额度仅一次+1、七次TOOL_BUDGET_EXHAUSTED/已确认无提交；最终计数4/效果2，根used2/reserved0/cap2，两个不同授权Agent本地策略各消费2，模型0。原操作3次回放同效果UUID，同根换父/实例409。祖先策略版本变化/撤销阻断新操作；子额度耗尽不再遮住祖先失效原因。
- 首次工作流按旧格式将已确认的工具效果记录EXECUTION_UNKNOWN，原运行保留。修复WorkflowTaskClient独立核验持有实例、参数、根/操作/effectId/计数/来源；另一条核对运行使用相同工具意图返回原UUID，SUCCEEDED且0新增效果。不是原UNKNOWN工作流的通用恢复；T6仍待。首轮策略拒绝因子额度先耗尽而显示预算原因，原REJECTED不改；已确认无提交后用明确新拒绝操作检验修复，不换根或清零。
- 四角色API及1500/390px真实浏览器PASS，浏览器业务写入0、无未捕获异常/失败API；控制台JWT不能替代实例机器身份，实际401。页面区分存储确认提交/未提交/未知与普通回执，子委托显示祖先撤销，列表限高/限量，工作流工具配置冻结稳定意图。实际任务Span与工具Trace关联已核。
- 最终backend=10.186.133.66:5001/eap-backend@sha256:1cddf5a1f3eff987c51a6e54b4dd1adf0bbb26f4510dcfdf1273c166a1371dfc；frontend=10.186.133.66:5001/eap-frontend@sha256:ce49a8acffca1cd03115e83ca00867146135c2ab9b88d6d16036e331fae59316；未来实例Runner=10.186.133.66:5001/eap-runner@sha256:150d537c3af42866e5e245fda654eabe08ae0da14610d687e1d703f1c41c9c3a。实际imageID/两验收模板镜像、健康UP、MySQLV60、前端buildId2026-10-05T09:30:54.993Z均核；优化Worker/任务网络Runner及旧模板不改。主清单只同步固定digest，不整份应用。
- 两实例最终SUSPENDED、工具策略停用、专属凭证撤销；根已撤销，子行历史ACTIVE不代表可执行。节点/root/eap-t4-evidence.json completed/browserCompleted/rolesCompleted=true，保留原workflow/runKey/confirmationRunKey、grant/operation/effect、模板/快照及所有旧T1/T2/T3/FAILED/UNKNOWN；不删除标记或重跑roots/workflow/race。最终backend源码/root/eap-t4-backend-v8、frontend/root/eap-t4-frontend-v5、Runner/root/eap-t4-go-v5；666源码摘要一致，绿色T3基线/root/eap-t4-green-t3固定。证据导出门禁通过后再镜像/root/eap-src。
- 操作脚本K8Substrate tools/eap/scoped-tools；证据入口artifacts/eap-scoped-tools-20261005/index.html，导出摘要另追加。所有依赖/编译/测试/构建/浏览器/部署远程，本机只文本/摘要/脱敏证据查看；无commit/push。保护另一线程BP内容，PROJECT_MEMORY只追加。Keycloak仍emptyDir、不空PVC覆盖重启；本地5173/8180隧道保持。

#### T4 导出与收尾确认（2026-10-05）

666源码摘要、相对绿色T3的28文件patch、实际Secret字节/base64扫描、624证据文件摘要及归档传输均PASS，已镜像/root/eap-src。证据本地K8Substrate artifacts/eap-scoped-tools-20261005/index.html；原归档SHA256=a378f950c48281c5580f435d6c3440ca1eb19c811241b29389706d81921e35e5，6820460bytes。已查看admin桌面及手机效果图，无横裁/重叠；浏览器runId=75eb6c2e-231c-465e-ba40-e4e2fb0e7ea7。仅定向清理本轮9个Job和9个独立Pod，确认无剩余T4临时资源；业务实例46/47、模板/快照、Secret、全部原UNKNOWN/REJECTED/COMMITTED及旧T1/T2/T3不动。保留节点eap-t4-evidence.json completed=true，不重复roots/workflow/race，也不重跑已删除构建Job依赖的旧部署/导出入口。源patch固定绿色T3，不以新/root/eap-src当旧基线。下一项继续T5，主线未全部完成；无commit/push。

## Codex 进展：Claude正式汇报BP、优秀样例对照与正文清理（2026-10-05）

- 状态DONE：用户要求移除正式投屏中的PoC日期、仍需验收等内部说明，参考嘉为等优秀企业BP，并明确嘉为仅是BP参考，不增加竞争定位；再次要求Claude Code实际处理。新主稿`BP/EAP融资BP_正式汇报版_20261005/output/EAP融资BP_正式汇报版_20261005.pptx/pdf`保持64页与顺序，BP/README.md指向新版；历史稿完整保留。
- Claude实际分段读取全部64页可见文字并生成清理方案；另读取嘉为23页、一微25页、华翊22页全文和参考总览/关键图，生成对照意见。首次比较误延伸到嘉为竞争位置，用户纠正后撤回；研究材料标明原文未采纳，不作为最终意见。最终文字清理、主代理复核及研究报告皆无新增嘉为竞品。借鉴标题结论、平台层次、比较轴、机制到价值、融资首尾呼应，不挪用样例经营数字。
- Claude生成`source/claude_finalize.py`文案与字重/颜色模块，`source/refine.py`真实调用。第一次模块误加回核验说明且关键词不匹配，未采用为成品，Claude修正版及最后24页方法换行对象实际执行。最终59页159处文字精修、5页11形状关键词字重/颜色优化。PoC日期、截图验收口径、编制说明移至对应讲稿和`output/不投屏核验记录.md`，原文完整保留；研究路线、方法示例、测算、目标与Demo简洁标签保留，没有把构造/研发/商业假设写成实绩。
- 全部生成、PDF转换、1600px渲染与核验经n1.sh远程`/tmp/eap-bp-formal-20261005/final`完成，复用现有字体/依赖。初次两次审计定位历史插页逻辑序与ZIP part编号对应问题，失败保留、未作为成品交付。python-pptx首次访问slides集合就会重命名part，必须在此之前从原ZIP presentation.xml与关系表读取真实slide/notes路径；现将新内容映射回原编号，继续严格比较原形状与媒体，没有绕过断言。
- 成品PPT/PDF/PNG/备注各64，正文缺失/越界0，全部未改形状、261图片形状、14原产品PNG、原生图表/工作簿及五份财务文件保持。竞品5/11/45、大屏19、完整左导航20、57页曲线完整Demo及同组截图尺寸保留。修改/删除前原文与来源URL留在讲稿。远程归档与本地下载摘要一致。
- 主代理查看全部8张64页总览，放大15/17/20/24/26/38/45/56/57等关键页。实际Claude成品会话fc9d5e20-86de-48d6-bf3e-2233812e6458读取九张关键图和成品核验JSON，通过无必须修改项；进一步采纳24页单字悬行建议，Claude生成两行替换后远程重新导出、核验，主代理与Claude实际再次读取最终24页PNG确认无遮挡。没有称Claude逐页读全部64张大图；实际调用、任务、Read记录和建议保存research。未本机PowerPoint/Keynote原生播放、未真实观众试讲。
- 最终PPT SHA256=`992faf4ac131f34e9e12e664a28db3a8046fa6d71787c3967b5a410e89c8f72b`，PDF=`9713011b0bce8acd94ae6d9c40a6a9a7138e205215b034b6b5882c441dc5daf0`；续接`.context/checkpoints/20261005-eap-bp-formal-report.md`。样例研究、图形对照HTML、最终生成/复核记录随稿保存。下一次改稿沿source/build_remote.sh远程执行，不在运行时修改生成入口。
- 本轮未读私有申报书全文/业务源码给外部Claude，仅BP绘图源、BP文本与授权参考图；无新EAP模型调用、工程重测、客户验收、业务写入、业务代码/部署/实例/凭据修改，无本机依赖安装/构建或git commit/push。T4等工程主线属于另一线程，未将其新进展补成BP实绩；客户推进、团队任职/权属与正式融资请求的证据缺口仍须实际确定。

## Codex 进展：BP文字自然版、去除套话与专业措辞复核（2026-10-05）

- 状态DONE：用户在正式汇报版基础上问文字是否还能优化，随后明确“去掉一些AI味道”。已交付`BP/EAP融资BP_文字自然版_20261005/output/EAP融资BP_文字自然版_20261005.pptx/pdf`，保持64页与页序，30页47处改写；旧正式汇报版及所有历史文件保留，BP/README.md已指向新版。
- 实际Claude Code分段完整读取1–32、33–64页正文JSONL，会话90cdfa36-c62d-4999-8b15-5ea2ef98ee22与845a944c-140b-4e43-b15a-e5c71905079c，实际模型claude-sonnet-5-5，56.163/73.220秒；Read超过默认截断部分实际补读。初稿56处，主代理保留9处原文（包括38/3保护关键词框、41/4修正对象不匹配及其他弱化建议），要求Claude修正过度口语和技术边界，同会话10.673/15.006秒返回修正文字。最终47处文案均为实际Claude输出，由远程入口应用；所有任务、原文、采纳对照及真实Read记录在research。
- 改写聚焦摘要、方法说明和页底结论，用具体管理/查询/比较动作替代抽象联动、支撑、进入同一套及口号总结；不是机械禁用词或删除算法。例第3页“跨框架纳管Agent，统一管理协作、评测、执行权限和调用成本”；第33页明确外搜索配置/内学习策略、证据影响范围及未知全量回退；第38页仍联合选择W/位置/协议保护到提交；第60页明确比较下一项目适配人日、重复排错与支持成本。未采用“直接用”连接器的保证，也没有把授权失效窄化成只撤权或把在研经验都写成已完成。
- 仅替换文字，原11个彩色关键词框完整保持，所有数字/来源/竞品/财务不改，原文与原讲稿来源保留。三核心、全部技术机制图、5/11/45竞品、19大屏、20左导航、57曲线完整Demo、同规格截图保留；研究/方法示例/测算/目标/Demo标识在。嘉为仍仅作为前轮BP展示参考，没有新增竞争定位。
- 全PPT生成、PDF转换、1600px渲染和核验经n1.sh远程`/tmp/eap-bp-natural-language-20261005/final`，复用既有依赖字体。PPT/PDF/PNG/备注各64，文字缺失/越界0；未改形状、261图片形状、14原产品PNG、原生图表/工作簿及五份财务文件保持。所有改写框逐项确认数值序列不变。远程归档下载匹配，本地只读SHA一致。
- 主代理查看全部8张全稿总览和关键大图，Claude实际成品抽查会话dfe6f45e-e2ba-4c56-9dca-7a215db4677c，23.379秒，真实读取3/10/24/25/29/33/35/38/45/53/60/64十二张PNG及核验JSON，通过且无必须修改。没有将该抽查称为64张大图逐页审阅；没有本机PowerPoint/Keynote原生播放或真实观众试讲。
- 最终PPT SHA256=`ca850bbdb919be2de151b841b0ed7781dfd47fb01f83308166380f1ff9816317`，PDF=`6fb43b706b62be98dff349fd17666cfd0b016c3e7b032189c8aba206d88fd6a2`；checkpoint `.context/checkpoints/20261005-eap-bp-natural-wording.md`。改写对照`research/文字精修对照.md`。以后沿source/build_remote.sh远程执行，不在运行时改入口。
- 本轮不新增互联网调研、EAP模型调用、工程重测或客户验收，未发送业务源码/私有原申报书全文/凭据，未改业务代码/部署/实例，无本机依赖安装/构建、无git commit/push。客户进展、团队任职/成果权属与正式融资请求仍需真实确定，没有用文案补造。其他工程dirty文件属于主线，不因BP改稿而验收。

## Codex 进展：核心技术创新点条目版（2026-10-05）

- 状态DONE：用户要求“核心技术需要分条目列出创新点，简单明了”。成品`BP/EAP融资BP_创新条目版_20261005/output/EAP融资BP_创新条目版_20261005.pptx/pdf`保持64页及页序；BP/README.md已更新主入口，文字自然版及历史文件完整保留。第24页三项核心各4条研发方法要点、价值行移至图下；第25–42页各2组“编号短标题＋一句说明”，只替换长副标题，原机制图/公式/曲线/泳道/矩阵/标题/结论和研究标签保持。
- 实际Claude Code生成创新点JSON，会话b93ba5bd-bb0a-46de-aa91-709a27a28377，56.899秒，同会话技术边界修正16.304秒。实际读取19页正文完整至42及原24/25/33/39大图；修正“只有误差超阈值同步”、无条件重试、OR无区别全路径等问题，企业筛选/分层搜索与基础方法不作首创。实际生成原生PPT模块会话2730c18d-7474-4922-ac65-42d01f84a303，104.107秒，读取plan、refine入口、正文和四张原图；`source/claude_innovation_layout.py`与原始返回字节一致，远程入口实际调用。模型claude-sonnet-5-5，限Read/Grep/Glob，禁Bash/Edit/Write及MCP。只提供BP文稿/绘图源和授权图片，未提供业务源码、原私有申请书全文、秘密或客户遥测。
- 三核心并重、可靠性贯穿。创新条目覆盖阶段收益/资源联合、完整任务成本/恢复交接、全候选成本界、六类配置/搜索运行预算、外配置内策略、证据局部重评、W/位置/提交协议、限定域极小授权、范围/版本屏障、OR/AND干预。31/32页用工程验证、34恢复基础、36/37验证方法，其余核心方法/创新点并保留研究路线/方法示例；不将幂等、fencing、MaxWeight、GEPA或AgentChaos基础说成首创，不把T3保留基线负结果改成降本。34仅无效果且契约允许才重试，未知继续占用；42保留ACK不保证无副作用及OR全部致险路径/AND可控条件。新增条目方法映射在research/创新点条目与方法映射.md。
- 全部PPT生成、PDF转换、1600px渲染与核验经n1.sh远程`/tmp/eap-bp-innovation-items-20261005/final`完成，复用依赖字体。39个原文字形状修改/移除，新增96个原生文字框；4034未改形状及技术图形保持。技术章外45页及备注字节保持，261图片形状、14原产品PNG、竞品5/11/45、大屏19、完整左导航20、曲线完整Demo57、原生图表/工作簿及5财务文件保持。PPT/PDF/PNG/备注各64，正文缺失与越界0；替换原文在讲稿和原文留档。远程下载归档摘要及本地成品SHA一致。
- 主代理查看8张全稿总览并放大全部19页技术章。Claude成品复核会话ccac8f3c-5530-4fd8-afbe-143adc207218，77.752秒，实际读取24/25/30/31/33/34/38/40/42九张PNG及plan/核验JSON，通过、无必改。其两项33/40原结论补“未知则全量”的可选建议未采用：24/30/39页已有此边界且本轮保留原结论。没有称Claude逐页审阅64张PNG，没有本机PowerPoint/Keynote原生播放或真实观众试讲。所有任务、实际Read记录和原始返回压缩件随稿保存。
- 最终PPT SHA256=`f2985e4572436b076155f62f467f74f680d8a6db63b9102cfdcb34aa3676e3e3`，PDF=`7c81dec1959c5a326ded0feadf9dbff1d73f6d070b87717963c9c200c132aab9`；checkpoint `.context/checkpoints/20261005-eap-bp-innovation-items.md`。后续仍沿source/build_remote.sh远程执行，ZIP物理slide/notes映射在首次访问python-pptx slides前读取。只有文稿/展示变更，不新增互联网首创性检索、EAP模型调用、技术实验或客户验收。
- 未改业务代码/部署/实例/凭据，未本机安装依赖或构建，无git commit/push。其他工程dirty和T4/T5属于主线，不因本次BP改稿而完成，也未以其新进展改BP业绩；客户推进、团队任职/成果权属与正式融资请求仍须真实确定。嘉为仍仅是BP展示参考，未加入竞争定位。

## Codex 进展：Claude全稿第二轮精修（2026-10-06）

- 状态DONE：用户要求“再次利用Claude code优化一遍”。新稿`BP/EAP融资BP_Claude二次精修版_20261006/output/EAP融资BP_Claude二次精修版_20261006.pptx/pdf`沿64页创新条目版，64页及页序不变；18页50处文字精修和19处重点显示，共56个文字形状变化。BP/README.md主入口更新，所有历史稿保留。主体按条目、自然中文，原字号/段落/坐标保持，无新增删除形状。
- 实际Claude Code全稿会话ada2c09a-8fb0-4c3a-9240-ddb6da91f742，188.355秒，同会话修正53.356秒；读取01–23/24–42/43–64三份正文JSONL完整至23/42/64、八张全稿总览、原24/28/29/33/39/40六张大图和既有BP样例/方法简报。初稿51文字/19强调，主代理要求修正极小性、换ID范围、重评对象、AND条件和正式汇报内部注释后采用50/19。没有要求每页硬改，重点技术章及59验证对照，其他好的内容保留。
- Claude实际输出原生文字/强调模块会话78318a25-0cb0-41c7-bb3a-0e9da8ed663f，39.913秒；读取plan、宿主refine、原字号及两张原图。`source/claude_second_pass.py`与返回源码字节一致，宿主远程入口实际调用。全部使用claude-sonnet-5-5，限Read/Grep/Glob，禁Bash/Edit/Write和MCP；只发送BP文字/绘图入口及授权图片/摘要，没有业务源码、原私有申请书全文、凭据或客户遥测。原始返回压缩、实际Read/任务、初稿和修正版随research保存。
- 改“恢复资格入选/共置复用/最终动作获证/三判断分记”等过度压缩名词，明确方法对象；26改标方法基础，服务曲线/队列权重不独立首创；33保留研究路线，联合配置与行为、按证据范围重评及未知回退全量。三核心各4条、详解每页2点、工程/恢复/验证阶段保留。19框用蓝/青/橙字重强调边际收益/共用总预算/保护至提交/限定域/范围版本等。独立留出不反哺；40包含极小不等于权限最少/费用最低；41不能一概拒绝换ID、仍按效果范围和版本核验；42AND仅干预可控致险条件；28沿用预算账。没有把“尚未实现/当前仅验证”等内部说明写回投屏。
- 全PPT生成、PDF转换、1600px渲染与结构/数字/字号核验经n1.sh在远程`/tmp/eap-bp-second-pass-20261006/final`完成，复用既有依赖字体，第一次完整相关核验通过。PPT/PDF/PNG/备注各64，正文缺失/对象越界0。4083未改形状和其他46页/备注字节保持，所有几何位置和字号保持；261图片形状、14原产品PNG、竞品5/11/45、大屏19、左导航20、完整Demo57、原生图表/工作簿及五财务文件保持。逐项文字数字字符串不变，T1/T3负结果保留，原文留讲稿及原文留档，下载归档和本地SHA一致。
- 主代理看全部八张总览及放大全部18改动页。Claude成品会话af658332-528c-46e6-b30b-3fe81a4e4765，39.156秒，实际读24/25/26/28/29/30/33/37/39/40/41/42/59十三张PNG及plan/核验，结论通过、无必须修改。没有读31/34/38或另读上一轮PNG，其比较是before/new_text，不称图像前后对照或64张PNG逐页审阅。可选29橙色偏浅经远程读取28/29/42run均B8590A/11pt，不成立，未再改色；40标题保留，方法说明和副标题限定域已有。评审原文完整保存，并在说明校正其“全部原对象字节不变”的泛化。没有本机PowerPoint/Keynote播放或真实观众试讲。
- 最终PPT SHA256=`935899fe7285e2514421457b3e0a9d04186ab1310517b997fc676381a7a4968f`，PDF=`09b325f026b1cd612e28ffed773f5897a419efd439674f8d4aa91688286b5cb9`；checkpoint `.context/checkpoints/20261006-eap-bp-claude-second-pass.md`；逐项对照`research/二次优化说明与对照.md`。后续沿source/build_remote.sh，物理slide/notes映射在首次访问python-pptx slides前从原ZIP读取，不在运行中改入口。
- 本轮未新互联网调研/首创检索、EAP模型调用、技术实验/客户验收，没有新经营/团队权属/融资事实；市场与技术依据沿此前资料及日期。没有业务代码/部署/实例/凭据修改，无本机依赖安装/构建，无git commit/push。其他工程dirty及T4/T5属于主线，不因本BP交付完成，商业/团队/权属/融资证据缺口未补造。嘉为仍只是BP展示参考，未增加竞争定位。


### Codex 进展：T5-A认证持久化已部署与验收（2026-10-06）

**主线尚未全部完成。** T1/T2/T3/T4有界阶段及本轮T5-A认证持久化已交付。T5实例身份安全配送/续期与旧模板迁移、业务工具逐项适配、T6通用恢复/发布取消、T7分级观测、T8知识/模型/工作流策略及调度公平/老化/阶段画像仍待；没有确认Agent调优稳定收益。字节/联通/华为报告方法仍作为设计依据，不当作已证明收益。

- 正式认证为eap-auth/keycloak-persistent单副本Recreate，Service keycloak selector=app:keycloak-persistent，镜像保持Keycloak26.7.4固定sha256:3d911baa186f352563854039b95f21a7e2c01c76b527fdc64f24a0885b927bdf。原公开issuer/localhost5173回调不变；keycloak-data-1005为local-path RWO，keycloak-backups-1005为NFS RWX，两个PV Retain。不重新导入realm，init必须看到已恢复H2数据库，空卷拒绝启动。旧emptyDir Deployment keycloak最终replicas0，原Pod已退役，保留配置；不能扩旧Deployment到1冒充恢复。
- 先实测Java/H2独占记录锁与只读共享锁冲突，绑定原UID，正常关闭PID1、同Pod容器自动重启保留emptyDir；共享锁持有期间读取已关闭数据库，AES-256-GCM加密/fsync。原文件只在应用卷与内存；三个加密恢复点和不可变keycloak-backup-cipher Secret留在集群，不导出密文或密钥。不用在线文件copy/JSON export当事务一致备份。实际解密、篡改、错误备份身份拒绝通过。
- 独立恢复与最终切换核对eap realm全部4用户/7客户端/8角色ID及JWKS摘要；四角色原未过期访问令牌、原刷新会话、新PKCE登录和原权限200/403、错误audience401通过。首次切换HTTPmetadata401、已回滚原路由，保留失败尝试及其备份；原访问有效期300秒未改变。调整EAP验权至正式路由恢复后，新一轮78.77秒切换通过。首次没有栈证据，不将401归于账号丢失或令牌过期；后续阶段/安全栈与有效期记录补齐。
- 实际持久化Pod由158d1d58-1e7a-4d03-ab88-bf2d793877ee替换为bfa818d4-6f6d-4d03-b974-7f6ac6179dcb，原访问/刷新会话、账号ID、客户端/角色、签名公钥均保留；实际UID见证据，不能以容器重启冒充Pod替换。独立空卷负测init exit1、writer未启动；当前manifest远程server dry-run通过。原密码/客户端运维脚本按Service selector选唯一Ready Pod：重启中拒绝未就绪writer，就绪后选择新Pod，仅check-target、密码与scope写入0。
- 远程Chromium四角色1500/390px登录、原能力、刷新页面/手机导航通过，12截图、无未捕获异常或失败API、浏览器业务写入0。旧实例停用后本地5173/version.json与8180 issuer仍可达，原共享SSH隧道保持。未重跑历史业务、调用模型或修改T4效果/UNKNOWN、预算、旧模板/快照；后台及前端镜像保持T4固定digest，backend健康UP、frontend buildId2026-10-05T09:30:54.993Z。
- 合同docs/AUTH_PERSISTENCE.md；EAP deploy/substrate-poc/keycloak.yaml已改为恢复态PVC配置，旧首次初始化说明注明历史；AX主体运维入口及K8Substrate密码入口适配新目标。没有业务代码变更或新应用构建；本阶段使用远程真实迁移/恢复/权限/浏览器/空卷及清单验证，不将T4完整回归数字说成本轮重测。
- 证据K8Substrate artifacts/eap-auth-persistence-20261006/index.html，归档1126313bytes，SHA256=fbd13b5719aefc6c12da6fee7ae33fe047ce4fc1cf0193dd92de3bd547e43ea2；39文件摘要/index链接/传输及节点实际Secret值与base64扫描PASS。节点/root/eap-t5-auth-evidence.json completed=true，保留所有阶段标记与失败尝试；已完成后不重跑prepare/ready/preflight/cutover/restart。原应用卷/备份PVC/Secret不清理，仅本轮临时测试资源在导出后定向清理。
- 边界：单writer H2 PoC、一次已验证的加密备份恢复流程，不是外部数据库/生产HA/TLS、定期备份调度、外部密钥托管或整集群灾备。未来备份重新绑定当前writer/PVC，不复用退役Pod hostPath。实例24h人工凭证、自动安全配送/续期及旧模板全局token迁移仍是T5下一阶段。所有依赖/测试/浏览器/部署远程；本机只文本/摘要/脱敏证据与入口公开元数据读取，无commit/push；PROJECT_MEMORY只追加、保护BP。

补充：首轮导航截图处于展开动画，最终新增抽屉完整展开边界检查并重拍，四角色导航宽300px均在390px视口内；首轮39文件归档及其摘要7b5aac21333254b517d727e909b83e0750fa6dcec5895fbf2f18315259c9e137保留first-export。本轮最终浏览器runId=c1199835-a1d3-44a4-b94a-5496da0b2e37，最终39文件/1126313bytes摘要=fbd13b5719aefc6c12da6fee7ae33fe047ce4fc1cf0193dd92de3bd547e43ea2。不重复迁移/业务/模型任务。

T5-A收尾确认：最终39文件摘要和index链接、首轮归档保留、实际Secret扫描及本地入口复核均PASS。只删除本轮3个临时Pod和keycloak-preview Service，确认剩余临时资源0；data/backup PVC、加密key Secret、3个恢复点、业务Agent/旧模板/全部历史及UNKNOWN不动。远程绿色目录仅定向同步认证清单与运维/恢复说明，不覆盖backend/frontend/runtime或BP；本轮应用镜像未更新，无commit/push。主线仍未完成，下一项统一实例身份安全配送与续期。

## Codex 进展：依据三家原文与9月29日版重构技术创新点（2026-10-06）

- 状态DONE。用户要求根据联通、字节、华为材料与9/29精品版完善完整技术名称，明确大模型驱动双层调度、多方式Agent构建，必须用Claude Code并凝练创新点。已交付`BP/EAP融资BP_技术方法重构版_20261006/output/EAP融资BP_技术方法重构版_20261006.pptx/pdf`，64页及页序保持；20页修改、101个原文字框重构，17创建说明与24原生技术总览重绘。BP/README.md更新主入口，历史稿完整保留。
- 三核心同等重要，各3创新方向：大模型双层调度、角色阶段资源联合、全候选误差同步与成本界；六维配置行为策略联合、证据绑定预算拓扑、可审计非参数演化局部重评；限定域任务最小权限证明增量维护、来源位置保护协议到提交、范围版本阻断及OR/AND。三种创建（零代码/已有镜像/源码）＋已有外部实例接入为产品基础，不称算法原创。可靠性由跨层追踪、故障传播配对与工具效果查询/责任恢复贯穿；保留研究/示例/目标/负结果，不声称首创、完整算法已实现或稳定降本。
- 宿主直接核读联通最终研究方案55–198/230–270/958–977/1050–1095/1150–1190，字节20260923正文268–294/456–505/542–595，用户指名华为PDF同源LaTeX824–950/1420–1565/1670–1702/1980–2016。9/29旧16/17/26–29/33文稿及图对照；源码仅根代理rg核对创建RUNNER/IMAGE/SOURCE分支及创建链文档。原材料核读行号、研究条件和EAP延伸在技术简报/对应表。只向Claude提供去敏BP摘要和授权图片，未发私有原报告全文、业务源码、凭据或客户遥测。
- 实际Claude均claude-sonnet-5-5、只Read/Grep/Glob，禁Bash/Edit/Write/MCP/Slash，没有子代理。内容会话686be011-61d7-46d2-bf26-2b8a4d7bc535初稿255859ms、9/29修订134504ms、凝练28381ms；首绘图43a8bab2-32a5-4ddd-8a8e-4200badaf5d8在641520ms超18000输出，未采用。新绘图e6dcf6e1-13a3-45ae-82d9-35c007011e7d、low/30000上限、185287ms成功生成276行模块，同会话17762ms最小色彩/文案修正。模块与实际Claude原文加3处实际Claude源码替换字节一致，宿主before及文本比对通过；全部9次调用、任务、真实Read和压缩原始返回随research保存。
- 成品评审e9aedb79-9baf-4c71-ab99-ee970af5d9ad首轮58793ms，实际读5BP文件/八张总览/17、24、25、28、30、33、34、38、39、40、42十一张大图，九项内容完整，要求修17对比度、24折行。修正后实际重读17/24/25/28，24仍有孤字，采用其“阶段/节点/预算”替代；最后实际重读24及报告21932ms，PASS、无必须修改。没有称Claude逐页看64张大图或独立核原申报书/首创。17截图按钮意见为条件式，宿主对照基线图与原裁切核验确认非本轮截掉；所有图片保持。其“到提交点才失效权限”的错误takeaway未采用，正确含义为保持决定性事实/保护协议到真实提交。
- 全PPT生成、LO PDF导出、1600px PNG与完整包/数字/图片/图表/财务核验经n1.sh远程`/tmp/eap-bp-method-recast-20261006/final`，复用既有依赖字体。前两次全64渲染，最后仅24标签变化，重导完整PPT/PDF、重渲染24并重验全部64页，其他63页及PNG保持前一核验版。最终PPT/PDF/PNG/备注各64，文字缺失与越界0，3939未改形状、261图片、14产品PNG、原生图表/嵌入工作簿与五财务文件保持，原文/来源留notes。31/32真实T3/T1及负结果、竞品5/11/45、大屏19、左导航20、曲线完整Demo57不变；未混入新T4/T5工程成果。
- 宿主看八张总览及放大全部20改动页，修正后回看17/24/25/28，最后再看24。远程归档下载与本地PPT/PDF摘要一致。最终PPT SHA256=`3548898f7b09c683ce05cd3508d13baf2a30608cd49a67ce396ea7f0b3adb4b4`；PDF=`172cb4f5fee85ae48b226b79920c42c31364bebcfbe35ad9671bb09d457c754d`。完成checkpoint `.context/checkpoints/20261006-eap-bp-method-recast.md`，旧wip只作过程。入口source/refine.py/approved_plan.json/claude_method_recast.py，沿build_remote.sh远程执行；默认全渲染，参数24仅在其他63页不变且已有全稿PNG时使用。首次.slides前读ZIP物理映射，原包仅替换20改动页及notes。
- 未本机PowerPoint/Keynote原生播放或真实观众试讲。本轮未新增互联网首创检索、EAP调用、实验或客户验收；商业/团队/权属/融资缺口未补造。无业务代码/部署/实例/凭据修改，无本机依赖安装/构建，无git commit/push；PROJECT_MEMORY仅追加保护另一线程T5记录，其他工程主线未因BP交付而完成。已通过后停止重复构建或评审。


### Codex 进展：T5-B外部实例安全配送、续期与旧快照重装已验收（2026-10-06）

**主线尚未全部完成。** 本轮完成已复核外部实例的EAP工作流入口阶段，不是整个T5统一身份迁移。下一项优先平台创建链路的专属身份、公钥和只读UID目录，再接任务网络/实验/自主及长任务入口、旧共享凭证迁移。业务工具逐项适配、T6通用恢复/发布取消、T7分级观测、T8知识/模型/工作流策略，以及调度公平/老化/阶段画像仍待；没有确认Agent调优稳定收益，字节/联通/华为方法仍是设计依据。

- 合同docs/RUNTIME_AUTOMATIC_IDENTITY.md。显式批准24小时实例接入授权，默认900秒短凭证，不自动延长身份/工具授权期限或额度。纳管记录与原生空间/名称/UID/模板核对；程序每次读取systemInfo.actorMetadata只读目录。generation为EAP身份周期，不是原生分配代次；credential_revision为短凭证版本，配送序号跨撤销单调递增。
- 独立Ed25519配送私钥只在不可变集群Secret；模板只含公钥与Agent/只读身份配置，无全局机器凭证、供应商密钥或控制API服务账号。签名45秒挑战、内存RSA2048/OAEP-SHA256密文、身份/nonce/序号/期限验签、HMAC确认回执；确认事务前候选在EAP不可用。网络配送不持有数据库行锁等待HTTP，撤销及UID复核优先；并发共享原pending，回执丢失/页面重开保持原请求，旧请求撤销后409。
- 工作流派发前按需续期，空闲不唤醒；有效版本也重装，修复旧快照内存。新旧版本切换不改身份周期、工具根、原操作/效果/预算。平台创建、任务网络、实验、自主出站/长任务及旧模板未迁移；内部可信HTTP路由PoC不等于生产mTLS/证明挑战公钥来源。原生MintActorJWT调用方授权有TODO，未配送控制账号或开放任意签发。运行快照仍包含受运行环境保护的短凭证内存。
- 最终远程后端完整471项/86类，零失败/错误/跳过；前端188项、类型/生产构建通过；Runner-race22顶层/33含子用例通过。8项配送回归包含回执丢失、撤销竞态、8并发续期、到期保留预算、旧快照重装、授权期及重建UID阻断。首轮短凭证错误约束工具授权、v2启动apiVersion错误、v3作用域并发断言DATA_REDACT_AND_RECHECK与期望预算耗尽不同均保留，不猜测v3原因；最终v5完整回归通过。镜像构建首次因打包尚未结束被门禁阻止，待EXIT0后成功。
- 真实独立Agent48/eap-agents/eap-t5b-identity，UID33f32366-4438-4929-a67f-908ab6f19c05。初次加密接入后一次计数+1、效果14d35858-88d8-4cbe-a73e-04cd343dfbb1；实际休眠跨120秒自然到期，版本不变/没有闲时续期。恢复900秒默认后Workflow16原运行wf-exec-8e05e56a-347a-45eb-8a55-0630d7435539成功续期并返回原效果，旧凭证401，身份周期不变。
- 快照初次EAP REVERT命令190/CTRL-46c37d10明确FAILED、未下发，因为外部纳管权限只允许休眠/恢复/检查；原记录保留，不放宽权限。通过原所有者原生CLI回退同一UID/原快照URI，HMAC在内存证明客体实际持有旧凭证；另一条原工具意图核对工作流成功，当前凭证重新安装且版本/期限不变。最终计数1/效果1、根used1/reserved0/cap2、模型0，实际Agent Span关联原工具Trace。
- 四角色真实API与1500/390px浏览器通过，权限按/platform/me，控制台JWT不能作机器身份；浏览器业务写入0，无页面异常/失败API。12张READY截图，另有最终撤销状态只读检查和公开配置截图；补拍首轮details dl选择器歧义失败保留，缩到automatic-connection后通过，不改应用或恢复凭证。
- 最终backend=10.186.133.66:5001/eap-backend@sha256:2fa697e8b004107db8eea3041241cc6856644c65a1621cb6c75c51f579258f88；frontend=10.186.133.66:5001/eap-frontend@sha256:281294980c601d0dc6dfb45f0cc890d7f0f89c06c8af71c5676d2b7fab881501；未来默认Runner=10.186.133.66:5001/eap-runner@sha256:87b20aa4d7459467ab51ffce86212076e13d9b1c920016e4e1fb4620e012f51e。实际imageID、MySQLV61、健康UP、前端buildId2026-10-06T01:55:07.377Z已核；默认900秒。现有模板/优化Worker/任务网络Runner不替换，不整份应用主清单或重启认证。
- 最终根撤销、策略停用、实例SUSPENDED、接入REVOKED/自动关闭，原凭证401、原接入请求409；保留所有授权、效果、快照/模板及旧T1/T2/T3/T4和UNKNOWN。认证持久化T5-A保持，原共享5173/8180隧道仍可达。/root/eap-t5b-evidence.json completed=true及所有阶段标记保留，禁止再prepare/workflow/snapshot或换意图重放。
- 操作K8Substrate tools/eap/identity-delivery；最终源码backend/Runner在/root/eap-t5b-src-v5，frontend/root/eap-t5b-src-v4，671源码摘要一致，比较基线/root/eap-t5b-green-t4固定。证据artifacts/eap-runtime-identity-20261006/index.html；导出摘要与定向清理确认另追加，门禁通过后才镜像/root/eap-src。已完成后不重跑依赖临时Job的构建/部署/导出旧入口。
- 全部依赖/编译/测试/构建/部署/浏览器远程，本机仅文本、摘要、脱敏图和公开入口元数据；无git commit/push。PROJECT_MEMORY只追加、保护BP内容。


#### T5-B 导出与收尾确认（2026-10-06）

归档3327941bytes，SHA256=01f8efca7f3b03ff8d2918165edde3b38159041fd9348aaba6b2123a0c19eeae；本地459项文件摘要和20个index链接一致，实际Secret/双层编码与3个历史机器凭证扫描PASS。入口K8Substrate artifacts/eap-runtime-identity-20261006/index.html及同名tar.gz，15截图；已查看admin桌面/手机/最终撤销公开配置，完整可见、无横裁。原READY浏览器runId=fcce6fc0-9740-4365-b1a8-3e29fd865a5b，最终撤销只读runId=6eb46055-28aa-4fe5-b8a9-afdfaea489a2，首轮补拍定位器失败独立保留。

仅定向删除本轮7个Job和8个独立Pod，确认剩余临时资源0。Agent48/模板/快照/配送Secret及全部原意图、效果、授权与历史保留；T4实例46/47仍休眠/撤销，Workflow15原wf-exec-46611fa5-8722-4fef-a771-971969c5f2c0仍EXECUTION_UNKNOWN。认证持久化PodUIDbfa818d4-6f6d-4d03-b974-7f6ac6179dcb保持，旧emptyDir Deployment replicas0；5173新版buildId及8180原issuer仍可达。

671源码门禁及17文件固定T4补丁通过后已镜像/root/eap-src，交接与合同定向同步。历史/root/eap-poc.yaml不含当前镜像摘要/身份配置，旧副本校验失败保留，没有整份应用或伪装成当前清单；实际固定版本定向配置/root/eap-t5b-release-patch.json保存且未再次应用，K8Substrate tools/eap-poc.yaml仅同步三个摘要与配送Secret引用/900秒。导出后新建的同步/收尾脚本及最终状态留在tools/eap/identity-delivery与节点，原归档SHA不再更换；不重跑已删除Job依赖的旧build/deploy/export。

主线仍未完成，下一项T5平台创建与其他执行入口的统一实例身份/旧共享凭证迁移；T6/T7/T8及调度公平/老化/阶段画像继续保留，无确认Agent调优收益。旧演示视频未重录。本机仅文本/摘要/脱敏证据，无commit/push；PROJECT_MEMORY只追加、BP保持。


## Codex 进展：大屏标注与Claude技术展示精修（2026-10-06）

- 状态DONE：用户认可核心技术内容，要求“PPT展示还需要美化，更加专业化，有层次有重点，看看其他专业报告如何组织”，并再次确认“是用claude code吗”。实际Claude Code生成五份原生视觉模块，64页及页序保持，24–42共19页技术章展示精修，已确认正文逐字不变。主入口BP/README.md更新到`BP/EAP融资BP_技术展示精修版_20261006/output/EAP融资BP_技术展示精修版_20261006.pptx/pdf`，旧版本全部保留。第24页三核心九创新在上、构建入口在基础层；详解分标题/双条目/主机制/结论，蓝青橙含义稳定、技术组导航、重点字号与字重、减弱边框底色阴影。三核心同等重要，可靠性贯穿，三种构建方式＋外部实例接入不称四种创建算法。
- 第19页大屏先按用户要求补四个原生编号与四个同色细线区域，对应运行态势/协作关系/运营待办/成本线索，截图/裁切/数据/原对象不变；实际Claude生成模块和复核，远程64页导出核验通过。带标注方法重构版PPT SHA256=`8d8ba8845850c78a84db295a863a74f6cba29aea978ecccffab44e0aa2721145`，PDF=`b7b085f7509833f66a8de18cabeda888d0e16b15212bf4e04c7172fc1f4cf27f`。无标注原稿留`history/大屏编号补充前_20261006`，编号映射output/大屏编号映射.json；当前视觉稿以此固定基线，不再改写基线。
- 2026-10-06实际浏览Datadog官方2026 Investor Day与Front官方B/D轮BP；Datadog210页PDF远程下载、提取文本并查看20/22/97，Front重点B12/D9；用户嘉为BP复用9–16总览重点14，仅是展示参考不加竞争定位。采用垂直能力＋共同基础、分层说明、共同对照轴、渐进关系图，不迁移他方产品/业绩/数字。来源与选页在research/专业报告对照与视觉采纳.md，前后对照research/修改前后展示对照.html。未新增首创性检索。
- Claude真实模型claude-sonnet-5-5，9次有效调用/3次失败元数据、任务、Read记录、原始JSON/stderr完整保存。有效精简评审21252ms；总览绘图123955ms；详细绘图333885ms并分三段核读25–42全部库存；预览精修29735ms；首成品复核35347ms实际读技术19张PNG＋大屏19和核验，发现41对比度；成品修正绘图31298ms；修正后复核25119ms实际读9张PNG，字色/长结论/阴影通过但标题仍居中；标题模块15400ms禁spAutoFit/启wrap=square/固定框并断言18标题；最后复核会话f664cf0d-b582-4f75-bd42-7ef230cca0cb，15308ms，实际读24/25/33/37/41/42六PNG及核验，通过无真实必改项。失败为首视觉评审8000输出超限、补全API400、首详细生成14000输出超限，未当有效完成。没有称Claude最后逐页看64图或独立审原申请书。
- 实际入口调用claude_overview_design.py、claude_overview_finish.py、claude_technical_design.py、claude_technical_finish.py、claude_title_export.py。四份保持真实生成源码；成品修正模块宿主另补41页码id11字色93A9C0，原Claude模块另档。宿主负责编排和审计。修41深底/32、38局部对比，拟合28/39结论，清主题effectRef；最后固定标题框修复XML左对齐而LO导出居中。只读工具禁Bash/Edit/Write、slash commands和MCP；未向Claude发送业务源码、原私有申报书全文、客户遥测或凭据。首评审“四种构建方式”和末评审泛化工程验证的说法未采用，不影响正式原文。
- 全部PPT/PDF/PNG及审计经n1.sh远程`/tmp/eap-bp-visual-20261006/final`完成，复用依赖字体。首版完整64渲染，两次修正重渲染24–42同时重导完整PPT/PDF并重验64；另外45张PNG沿相同页面。原文字逐字保持、原形状全保留、261图片及原生图表XML不变，媒体/工作簿/备注和5财务文件字节保持，缺字/越界0。其他45页逐对象保持，竞品5/11/45、大屏19、左导航20、曲线完整Demo57、31/32工程数据/负结果不变。ZIP物理映射在首次访问slides前读取，仅替换19个技术页部分。一次非必需只读图层诊断上传沙箱网络拒绝、未运行；最终实际图完整可见，无依赖或采用诊断结果，不需再试。
- 宿主最终查看八张全稿总览并放大全部19精修页；未本机PowerPoint/Keynote原生播放或真实观众/投资人评审。最终下载归档SHA256=`f9a960dc589285c9338fa085ed554aaa7b417552e16ac5dfeea31e436e25af5c`，本地成品与远程摘要一致。PPT SHA256=`5c6889d8fa2de5b7cf28ccb7b0f9244427cb3e211809191ea2f225a4c3ead1b9`，PDF=`0c52766ea50455b084fab58406caa61bcae46e66d92ac91ffd1a1ccdeb93f075`。完成checkpoint`.context/checkpoints/20261006-eap-bp-visual-refine.md`，旧wip已标作早期过程；交付范围research/成品视觉核验与交付.md。通过后停止重复构建/美化，后续有实际新变更才重测；沿source/build_remote.sh，tech参数只在其它45页PNG一致且未变时使用。
- 本机只编辑文本源、ClaudeCLI、接收和看图；无本机依赖安装/构建/测试，无业务代码/部署/实例/凭据修改，无EAP模型调用/技术实验/客户验收，无git commit/push。PROJECT_MEMORY只追加保护另一工程线程。T4/T5主线、既有UNKNOWN和商业/团队/权属/融资证据缺口保持原状态，不以BP改稿宣称完成或收益，也未混入另一线程新工程结果。

### Codex进展：64页BP全稿检查与Claude核修（2026-10-06）

- 用户要求整体检查；完成 `BP/EAP融资BP_全稿核修版_20261006`，64页顺序不变，18页共46个原生对象修正。上一版技术展示精修版PPT/PDF hash不变，历史版不覆盖。BP/README当前索引已更新；研究目录含全稿结论、文字对照、官方来源、实际调用与核验记录。
- 实际Claude Code分四批Read全部64张PNG与全文；3/4首次因6000输出token上限失败并保留原记录，原会话续审成功。实际全稿一致性复核Read4正文+4结论+计划并生成claude_whole_deck_fixes.py；宿主只保存代码、修订计划、编排/审计，无业务源码或私有原申报书全文发送。9次实际CLI调用，7成功/2首次超限；metadata见research/Claude全部实际调用汇总.json，四批原记录在基线research/全稿检查_20261006。没有新spawn子代理。
- 修清Token/调用次数及32调用与32评测区别；7页不同市场不加总；10页逐项来源年份；12页Arize官方2026-10-01完成及并购/融资分类，8金额原链接保留、新增完成公告；27方法示例、37干预/核对图例、40旧工具16子集与新工具见证；45自有ROI为研发/竞品灰点不等于无能力；48抵扣与交付工作量情景；50现金峰值+15%缓冲/未扣现有现金；55当期与累计；57示例2/4与独立截图4/4分别展示；62SAM假设字重；64三核心/可靠性贯穿。保留T1 270→355/+85 Token与T3 KEEP_BASELINE。31遮挡、26缺权重、原页脚残留等假阳性未采纳。
- 宿主看八张总览、放大全部18改动页；实际Claude成品Read18PNG+audit/plan，96ae18bd-ab0b-4b85-a07a-4b6dc5d9de9d，28082ms，PASS。新增链接使12页公司标题变蓝，宿主将链接移到页脚而恢复标题样式；实际Claude最后读12PNG+audit，e06c8450-55b2-41e5-9a60-55b6e6f01015，9776ms，PASS。最终无真实图文必改项，未称真实投资人通过或可获融资。
- 全部生成、LO导出、PNG与审计经n1.sh远程/tmp/eap-bp-whole-check-20261006/final；首轮完整64，最后12页重新渲染、全PPT/PDF重导并重新64核验。PPT/PDF/PNG64；原对象集合exact、未授权XML不变，261图片/crop、4原生图表、媒体/工作簿/64备注/所有原来源链接与5财务模型字节保持，缺字/越界0。竞品5/11/45、大屏19的四编号、20左导航、57完整曲线Demo保持。918/1,056、650/500、406/528/731/1,056、27.5%和43.3%/84%/67%复算正确。
- 最终本地与远程hash核对：PPT10384c80b725c83abcc3c3a98ad013eafd9b34dc5340183eb978e7935fce7c81；PDFb3454a2df95b729edb6fb59390c751bf927afe2b712314d34c3caa70c76e2f44；归档91b522acacdfe77b79218dd4edfd5e6eac3230ff8a8cc8a6cef98774b55174ac。checkpoint .context/checkpoints/20261006-eap-bp-whole-check.md DONE；通过后停止无新变化重复构建。
- 商业证据缺口保持：首批客户/合同/回款及采购周期、核心成员投入与IP/成果归属、本轮正式融资轮次/金额/股权和账上现金。技术稳定收益仍按原90天计划验证；资料不足不推定事实不存在。竞品沿公开资料，未外部产品实测；未本机原生播放或人工路演。未本机安装/构建/测试，未改业务代码/部署/实例，未调用EAP模型或新实验/验收，无git commit/push；PROJECT_MEMORY仅追加保护其他T4/T5线程。

### Codex进展：Claude产品进展单页精修（2026-10-06）

- 状态DONE。用户要求用Claude Code改美观和专业度，仅重做第4页“产品进展：协作运行、质量对照与调用受控”。新稿BP/EAP融资BP_产品进展精修版_20261006，64页及页序保持，其余63页按ZIP字节保持；BP/README已更新入口，历史全稿核修版不覆盖。
- 实际Claude生成原生可编辑三展区：协作节点与交接顺序、同尺度总Token柱图、三入口共享预算与四格额度。主数字、单位、深蓝标题带、蓝青橙对应和统一下置研发区建立层次，共用可靠性底栏保留。原主标题、背景、公司标签、logo和页码保持；原表格body按授权替换，未删弱其他页面的图。
- 事实仍为2个Agent专用实例串行、两步关联Trace；3组公开合成样本/6次真实调用、两版精确文本匹配均3/3、总Token270与355，未节省Token；4/4次调用额度用尽，新实验派发前拒绝、0新增调用。三研发方向同等呈现，保留任务阶段与资源准备/复用、预算搜索与独立留出、任务收权与提交前权限/版本复核。未混入其他T4/T5线程的新工程结果，不以小样本声称商业收益或研究已完成。
- 三次实际claude-sonnet-5-5，只Read授权BP，禁Bash/Edit/Write、MCP/slash、无子代理。设计56830b9a-98a1-42e2-a811-71945007e35c/135147ms，实际读库存及4/3/5/24四图；首审324f6051-69cd-470a-acf6-7c2cd0081a35/28315ms读新旧图、audit、模块，给出孤字/预算汇入线/新实验范围三处最小替换；最后35a8d967-0f5c-4308-8be7-44a937998e93/12131ms实际Read最终4PNG和两份核验，PASS、无必须修改。宿主也看两轮4大图。不称本轮Claude逐图审64页或真人投资人评审。
- 全生成、LO PDF、PNG、来源/范围/事实核验经n1.sh远程/tmp/eap-bp-progress-design-20261006/final。首次全64渲染，最后仅重4且重导完整PPT/PDF、核对全文64；其他63PNG沿同一不变页面。ZIP仅4页slide部件变，原63页与所有媒体/图表/工作簿/64备注/来源关系、261图片/4原生图表/五财务文件保持，缺字/越界0。最终模块原Claude+三替换逐字一致。首次严格代码来源断言因少一个文件末尾空行中止，恢复后通过；首次verbose JSON事件数组解析不匹配已修复，设计成功未重调。旧柱图零基线的错误诊断未采纳。
- 本地与远程摘要一致：PPT25a3a8239cbc106b194e4d97c18424d67e478f14133358c09048af464290233d；PDF8cfe98bd6dbe80301f8d48cf7c26fd508d8ace9c6f346774d0ecda2d84d70949；归档bccf4e2f6bdbe07a003fab085d7c5f6875ba333314b2d3a977f66e887c620ef8。checkpoint .context/checkpoints/20261006-eap-bp-progress-page.md DONE。入口source/build_remote.sh默认全64，4参数仅对已有不变63PNG使用。通过后不重复构建/审阅。
- 本机仅文本编辑、ClaudeCLI、摘要/接收看图，无本机依赖安装/构建/测试，无业务源码/私有申报书全文/凭据/客户遥测外发，无业务代码/部署/实例/凭据修改，无EAP模型调用/新技术实验/验收，无git commit/push。PROJECT_MEMORY仅追加保护其他工程线程；未本机原生PowerPoint/Keynote播放或真人路演，商业与融资证据缺口沿基线。

### Codex进展：Claude专业配色与中文术语（2026-10-06）

- 本阶段DONE：BP/EAP融资BP_专业配色中文版_20261006，64页及页序保持，35页111个原生文字框改为智能体/功能方法表述，全64页配色收敛。主深墨蓝142B43、灰蓝355A77、灰青4E7075、低饱和暖金8B6C47；正/负/未知继续区分。正式原生正文Agent及AgentChaos/MASS/Cognify/GEPA/MAST/TPE为0，原产品截图和品牌logo保持真实，64页备注说明中文化，原URL/代码标识符保留。方法内容、原研究依据与边界不删。
- 实际Claude五次成功CLI，只Read授权BP与公开参考图片，禁Bash/Edit/Write、MCP/slash和其他Agent；未外发业务源码、原私有申报书全文、凭据/客户遥测。设计260915ms生成配色/术语模块；首审43988ms读64总览+11大图；渐变修正73055ms生成模块；再审25890ms读64总览+8大图确认配色/对比度、指出59孤字；末审41d66be5-42ac-43db-bb2d-c168cf08360f/12399ms实际Read59最终PNG+两核验PASS。长JSON跨两assistant文本恢复校验，不重跑成功调用。宿主查看两轮八总览、关键页/8表头页及最终59。未称64大图逐页读或真人投资人通过。
- 2026-10-06联网核读Front官方B/D轮页，实际看保存D3/D9，借鉴白底深字/固定色义/有限强调；实际看保存Datadog20图，只沿结构、不采用亮紫/青/绿。嘉为仅沿此前样例结构，不新增竞品或虚称本轮重读。参考/采用、术语对照、前后HTML及成品核验随稿存。
- 全生成、PDF/PNG/审计经n1.sh远程/tmp/eap-bp-editorial-palette-20261006/final。两轮全64渲染，最后仅59重渲染并重导完整PPT/PDF，全64核验；最后仅逻辑59的ppt/slides/slide51.xml变，其他63及其他部件字节不变。4384原对象/几何、261图片、4图表数据/工作簿、来源关系、五财务保持，缺字/越界0。渐变底首审字色不足以Claude模块修正；15六模块统一灰蓝保留白图标；一次新段落换行被原run保留模块忽略、断言中止不覆盖成品，改18字自然两行后通过。T1/T2/T3负结果、串行/独立Demo记录及三核心研发范围保持，未混另一T4/T5线程新进展。
- 最终PPTb7ad3f10a44a42458d74800c2521829d0fcf81a4394d59c7f573f551178ff87c；PDFa38c96ceb30dccde990ae8f60dad98df9dbe13dfd240e069f4d9995eb40c7eeb；下载归档d2003dc559612bb8a52d5c7fee98b563f67b73ec8fcc6e15a884bad7ec83f772，本地/远程一致。checkpoint .context/checkpoints/20261006-eap-bp-editorial-palette.md DONE。无本机依赖/构建/测试，无业务代码/部署/实例/凭据修改，无模型实验/客户验收，无commit/push；只追加保护工程线程，商业/权属/融资证据缺口沿基线。
- 用户随后反馈24“三条主线九项创新方向”、25“协作角色与阶段—节点—资源联合决策”、33“外配置、内策略”内容认可但排版/层次/重点/配色不满意。下一项只对这三页重组原生图文，内容保持，实际Claude Code生成。新工作不把上述配色阶段DONE视为用户三页需求完成。

### Codex进展：Claude核心技术图解及其他页精修（2026-10-06）

- 本阶段DONE：BP/EAP融资BP_三页技术精修版_20261006，最终EAP融资BP_核心技术图解版_20261006.pptx/.pdf完整64页。用户参考三栏工程图、授权必要其他页同类组织并强调配色一致；实际查全64总览与密集大图，精修24/25/26/30/33/35/38/39/40/41/42/45/57/58/61共15页。8页图解组织、7页局部精修，49页原生XML字节不变。沿用深墨蓝142B43、灰蓝355A77、灰青4E7075、浅灰及低饱和暖金语义色，三核心同等展示，章幕深底保留。
- 实际Claude Code只Read授权BP、公开图及BP绘图代码，无Bash/Edit/Write/MCP/slash/其他智能体。plan/other实际看64总览与核心/密集大图，宿主剔除58线交叉、35标题碰撞等可见图不支持的判断。实际生成三原生模块：core_a24/25（retry session c67a9552-1e22-4378-856f-5e2c623a1ee0，100337ms），core_b33/41（264478ms），additional其余11（323941ms）；各与actual original副本SHA相同。宿主路由/组装/保护/host_finalizer独立保存，不冒充Claude源。actual imagegen透明2048×704图集SHA4532befcdd79d4b72aee722436340a9d86c10e6ff38020cbda45ef7a355232bf，只作三小图标，主体可编辑。
- 宿主实际看15大图与八张64页总览，修正后再看42/24/30。Claude三组成品actual Read合计同样范围，session9229d0dd-e224-4390-ac2a-6a348cb91dab/18891ms、6a2a564e-4b79-4fb7-afcd-6e07aa8daa88/16261ms、ff89a838-04c5-4f27-815d-369ebf0fc6e7/20947ms。首组24三角图/30反馈箭头须改，其他组PASS；42×恢复对应风险路径后第二组PASS。最后f543e9d8-caa7-4974-a21a-c9f2b2a3f76e/11533ms实际Read24/30新成品及核验PASS。raw缩略图页码及“四种构建”表述不作内容证据，保持三创建＋外部接入；未称64大图逐页Claude核读或真人路演。
- 全Python/PPT/PDF/PNG/审计经n1.sh远程/tmp/eap-bp-three-tech-20261006/final。首次15页新渲染后仅因真问题重渲染42，再24/30；每次完整64PPT/PDF与全64核验，49PNG复用核验基线。原4384对象/原文保持，新增52对象含3图标；261原图片、4图表/数据/工作簿、备注、来源关系、原curve/箭头/虚实线/翻转/旋转及五财务保持。57截图只移动/等比缩放，原media/crop保护；缺字/越界/正式原生Agent与方法专名0。T1/T2/T3负结果、串行交接、独立Demo和研发边界沿基线，未混另一工程线程T4/T5数据。
- 失败保留：四页整批20分钟无返回终止；24/25首拆14000输出上限无代码，explicit low effort+提高上限+复用helper后实际成功。additional只修JSON多引号包装、源未改。首次全成品QA Read后403预扣额度不足无结论，拆5页/组+4096输出成功，失败不计PASS。原图片opacity、普通XML namespace断言中止后修正，不绕过保护；宿主背景/字体/小框内边距/继承阴影/连线修正与原模块分开。下载断开后重试/摘要验证完成。
- 最终本地/远程一致：PPT06210f7ba5a09d84820cad961d44707a383111eb02bd06f8e66b1edceba3f445；PDFe51cf1e7e7ab37c43ebf17dfbf50b8bdaf0a92d7ae756e1b86c8f1b41d5b954a；归档604f734524700a426d0db86e8361d9545e0a08a17063affc85ed2cb99bc98681。README/BP索引/八页前后HTML/参考与actual生成/交付报告更新，checkpoint .context/checkpoints/20261006-eap-bp-technical-layout.md DONE。两条经验写本地gstack学习记录，通过后不重复构建。
- 无本机Python/node等构建/测试/依赖安装，无业务源码/私有申报书全文/凭据/客户数据外发，无业务代码/部署/实例/模型实验/客户验收/commit/push。PROJECT_MEMORY只追加保护工程线程；未本机原生PowerPoint/Keynote或真人投资人播放核验，商业/权属/融资证据缺口沿基线，不据模型审阅宣称可获投。


### Codex 进展：T5-C平台创建与专属身份已验收（2026-10-06）

**主线尚未全部完成。下一项 T5-D：任务网络及其他执行入口的统一实例身份、旧共享凭证迁移；随后保留 T6 通用恢复/发布取消、T7 分级观测、T8 知识/模型/工作流策略，以及调度公平/老化/阶段画像。没有确认 Agent 调优稳定收益，字节/联通/华为方法仍是设计依据。** 本轮完成新平台 RUNNER 创建链路及工作流、对话、提示词实验入口，不是全部 T5 迁移。

- 合同 docs/PLATFORM_RUNTIME_IDENTITY.md；V62 保存平台 UID/模板与固定创建请求。Create 回执 UID 才能绑定所有权，未知结果保存原 attempted 并拒绝凭名接管/再建；普通注册/心跳不能写身份。新 RUNNER 版本冻结 PER_INSTANCE_V1、公钥、固定镜像和只读 atespace/name/uid 目录，不含共享机器/供应商/控制账号凭证。旧模板/快照不改；旧 RUNNER/IMAGE/SOURCE 首次创建保留旧路径，额外会话需新专属身份版本，前端禁用并解释。
- 平台创建最多批准24小时接入授权、短凭证默认900秒，复用加密配送/HMAC确认；READY仅在确认后。相同创建请求固定原版本/名称/UID，撤销后永久取消；不完整身份/OFFLINE/UID或模板变化不回退共享凭证。工作流、对话、实验派发前按需复核/安装，空闲不唤醒、不续期，不延长授权或重置工具根/操作/效果/预算。
- 真实 Agent49，原主实例 eap-agents/a49-v1-fa986da51fdc UID2b569b00-eb20-4dff-9968-5cf042f65e9e、额外实例 eap-agents/a49-v1-f782253f75de UID70da5524-acc3-483a-9b62-758244a8da55。原两实例实际空闲跨900秒自然到期，原生版本/短凭证不变、旧凭证401；修复后使用两个原创建请求续期，UID及身份周期不变、恢复休眠，恰好两个实例，跨实例凭证401。不是换请求创建新实例冒充续期。
- Workflow17 原运行 wf-exec-a142628b-d8e1-4b95-8b5b-cb57062ab18f SUCCEEDED，受控计数1/效果1，存储效果 10261c2a-4bd9-424e-9870-bb297607c1af 与工作流回执一致，模型0；原v1一次对话和修正版v2公开单样本对照实验加上保留的首次预算未关联实验2次，共5次模型调用；原聊天网关/Agent Span关联，修复后实验两网关Trace与冻结试验及预算关联。实验只验证身份执行入口，不证明优化收益。
- 实验首轮原v1配置读取400；只读诊断确认Runner404/experiments unavailable，工具标志错误关闭独立实验端点，原一次对话/模型消费保留，未提交实验。修正版允许两入口共存，实验不读会话历史/不发工具定义，工具调用回执UNKNOWN、不执行、不重发；红测复现后远程-race通过。显式发布v2，一个新UID 94748c6a-9a26-41f1-a16c-7dc9d6830873，实例 eap-agents/a49-v2-8bcf967daa8e，冻结新Runner；原两v1模板和UID不变、发布v2后原额外请求仍返回v1，恰好三个实例。
- 最终远程后端完整498项/89类，零失败/错误/跳过；v12身份/追踪/预算相关61项、之前v11协议57项回归通过；前端189项、类型及生产构建通过。676源码摘要一致，相对固定T5-B 671文件变更43，Runner修受控任务工具与独立实验共存，本轮红测复现、完整-race23顶层/36含子用例及生产编译通过；随后v12追踪预算修复后完整后端498/89重跑通过，前端源码未再变化，原189/类型/构建保持有效。最终源 backend /root/eap-t5c-src-v12、frontend /root/eap-t5c-frontend-v6。
- 历史失败保留：v1编译/依赖、v2启动API版本/编译、v3–v5回归/编译与中断、v6完整权限测试空请求不符、v7完整485通过。真实额外创建重放暴露原生策略更新缺UID/version，原请求/UID保留；v8协议修复编译误用repository.status；v9原有工具效果回归一次REJECTED原因未记录，不反推原因。v10补非秘密诊断、17项通过，完整回归因确认的合法UUID数字片段PHONE/PII误判修复中断。v11仅对服务端验证的受控计数标识符按合同元数据检查，普通工具API仍检查全参数，无调用方豁免；原指纹/预算/效果与UNKNOWN保持。协议读取原策略UID/version、同规则不更新、改变带CAS、冲突不盲重试。
- v2首次实验14 COMPLETED、两调用实际SETTLED但experiment_id为空；加上原聊天共3条旧结算行32/33/34保留，不倒填、清零或重放。追踪校验误把仅有网关记录的实验入口要求为聊天AGENT Span；正确核对原聊天历史Span。实际发现实例Trace隔离转换断开预算归属，v12只对服务端已签发且Agent/实例UID/模板/端点匹配的实验编号保留原编号，其他继续实例隔离，Span ID仍隔离、跨链接仍剥除；同Agent其他实例/重建UID拒绝，已结束试验不能降为普通模型调用。四项新集成及61项相关回归通过。修复后新公开样本原实验 15 的两模型意图均有实验预算SETTLED、used2/reserved0，网关Trace与冻结试验一致，旧/新完成原意图409、跨实例401，新增模型0；总模型5。实验协议当前不发AGENT Span，T7继续保留，不伪造观测。额外原v1创建请求续期首400后留下DELIVERY_PENDING；再查询原创建请求409另一配送待核对，首次400原因未确定。管理员按状态中既有deliveryOperation核对原配送，周期/UID/24小时期限和消费5保持，再查询原创建请求返回原实例；未换批准或重放模型。独立RESUME命令192明确RESOURCE_EXHAUSTED，先仅休眠本轮已结束实验的v2实例释放槽位，再按原配送核对成功；通用自动恢复与公平调度仍待。
- 四角色真实API、1500/390px浏览器PASS，按/platform/me能力；人工凭证接口对授权人400、无权限403，控制台JWT机器入口401。20截图，无未捕获异常/失败API、浏览器业务写入0。初次手机关闭按钮定位器歧义修正后预检通过；看真实截图仍发现最后步骤竖排，v5修步骤宽度并独立重测，v6仅清理一处行尾空格并独立复检/构建，最终浏览器补单行检查。没有把首轮宽度PASS当作视觉无问题。
- 实际 backend=10.186.133.66:5001/eap-backend@sha256:9ad1ec397c40f0b2359f43e0a88d400ac07aa54a7f0469fc03919a5e230546d3；frontend=10.186.133.66:5001/eap-frontend@sha256:4f2a8f3b4837e6d69a9473b44aa5ef74f8dd1b93699d05dbf5c9e1e6deba8aa5；修正版默认Runner=10.186.133.66:5001/eap-runner@sha256:511647de34fc7e671b232a1d995696f9e0e4295549847cee1e10b0b7789985b6。实际imageID、健康UP、MySQLV62、前端buildId=2026-10-06T04:54:00.120Z；配送Secret仍不可变且引用/默认900秒保持。前后端镜像及修正版默认Runner定向更新，不整份应用历史清单或重启认证。首次只读健康连接Errno110超时，独立有界读取及补10秒超时后核验通过，失败记录保留、不归因；停止尝试时原读进程已自然退出，没有发送信号。v12发布后10秒直接健康读取再次超时、实验配置首预检400；独立核验健康UP及配置200后才提交新的有界实验，原消费当时仍3次，失败原因没有确定。
- 最终三个UID实例SUSPENDED、身份REVOKED，根撤销、策略停用，原额外创建请求409且原生version不变，旧凭证401；计数1/效果1、根used1/reserved0/cap2保留。旧T1–T5-B、T4 Workflow15原UNKNOWN及模板/快照/预算不重放；持久认证原PodUID及旧emptyDir replicas0保持。
- 操作 K8Substrate tools/eap/platform-identity，证据 artifacts/eap-platform-identity-20261006/index.html；导出/清理摘要另追加。节点 /root/eap-t5c-evidence.json completed=true，保留所有阶段键，禁止再prepare/workflow/model或换意图重放。门禁与导出扫描通过后才镜像/root/eap-src，历史/root/eap-poc.yaml不应用。
- 全部依赖/测试/构建/部署/浏览器远程，本机只文本/摘要/脱敏证据；无commit/push。PROJECT_MEMORY只追加，保护BP。内部可信HTTP PoC不等于生产mTLS/原生分配代次/完整灾备；任务网络独立执行器、自主/超长任务及全量旧模板迁移仍未完成，旧演示视频未重录。


#### T5-C 导出与收尾确认（2026-10-06）

归档6230497bytes，SHA256=af6ca4c7b7108d2fed4024a207418690a9a6f3299f543b351fa0279834f35be5；本地1268项文件摘要、index链接、实际Secret/编码/历史机器凭证及嵌套源码包扫描PASS。入口K8Substrate artifacts/eap-platform-identity-20261006/index.html及同名tar.gz；20张四角色桌面/手机截图，手机最终步骤横排已目视复核。初次创建重放、回归和手机预检失败历史保留。

676源码摘要和相对固定T5-B的43文件补丁通过后才镜像/root/eap-src；合同与交接定向同步，PROJECT_MEMORY只追加保护BP。仅定向清理本轮20个Job和7个独立Pod，最终T5-C临时资源0。保留Agent49两个原v1 UID实例与显式v2实例/模板/快照及全部原意图、效果、授权、预算和旧T1–T5-B；原T4 Workflow15仍UNKNOWN，T5B保持关闭。持久认证原PodUID/旧emptyDir replicas0保持，5173新版buildId及8180原issuer仍可达。

tools/eap-poc.yaml只同步前后端及未来默认Runner三个摘要，旧模板和Secret引用/900秒保持；没有整份应用历史/root/eap-poc.yaml。导出后收尾脚本/状态独立保存，不重跑依赖已删除Job的build/deploy/export。节点eap-t5c-evidence.json completed=true及所有阶段键保留，禁止重新prepare/workflow/model/budget_model/new_version或换意图重放。

主线及整个T5仍未完成，继续T5-D其他执行入口/旧共享凭证迁移，再T6/T7/T8及调度公平/老化/阶段画像；没有确认Agent调优收益，旧演示视频未重录。全部测试构建部署浏览器远程，本机仅文本/摘要/脱敏证据，无commit/push。

### Codex进展：实际imagegen完整工程图与Claude嵌图版（2026-10-06）

- 本阶段DONE，交付`BP/EAP融资BP_工程图示版_20261006/output/EAP融资BP_工程图示版_20261006.pptx/.pdf`完整64页；BP索引、README、七页前后HTML和视觉修正记录已更新。用户明确否定上一版“没有用生成的图片、风格不是提供样式”，因此不把上一版模型PASS当用户认可。本版重做24/25/30/33/38/41/58的主机制区，实际使用七张完整生成图，不再只是三个小图标。深墨蓝142B43/灰蓝355A77/灰青4E7075，编号圆标、浅底描边分区、图标说明条、工程部件、明确箭头和共同控制链路沿用户图参考。其他57页与原数据图、竞品图谱、大屏四标注、完整左导航、曲线Demo保持。
- 实际imagegen12次调用，原图与修正均留assets，提示和生成路径保存在research；采用最终七图原始字节嵌入，无Python改位图、无拉伸/裁切。24仍用原生九创新正文与研发目标、33保留原生四组研究对照和路线；图内机制文字为栅格，不宣称整图全可编辑/全部可搜索。原机制对象仍在遮罩下，历史原版不覆盖。
- 实际Claude Code四次成功只读调用，claude-sonnet-5-5。代码生成553fe6d2-5fad-4272-8e4d-8bc7f3b499d4/53903ms；模块与actual original副本SHA8b6a97cf8b80ac567d7e268742ab21dddd04e190589cb5b68854959b95c9f3eb一致，宿主host_finalizer与组装审计分开，不冒充Claude源码。B组efebfa76-3e73-4322-8f4c-d9cda956dcc5/29621ms实际Read参考与38/41/58及核验，无必改；A组868119d2-8036-4100-a1b7-0dc665beeebf/45942ms实际Read24/25/30/33，指出24目标压边、25说明错位与专家汇入不清，均修正。最后13f7fbd9-70f4-44eb-bb41-abfb7082a69a/24561ms实际Read参考、24/25/33、新核验与原表说明，style_match=true、must_fix空。原始/结果/Read/usage均保存，禁Bash/Edit/Write、MCP/slash、无子代理；未发业务源码、私有原申报书全文、凭据或客户遥测。
- A组“33表缺成本/迁移/负收益数值行”不采纳：原对象62–83一直只有表头＋做法行，用于四组研究设计；评价维度在标题，实验尚未产出这些数值。原文与出处单独保存，不编造数据。最后Claude把节点/专家与契约/校验条混称的句子也不当作方法事实。宿主实际看八张64页总览、七张改动大图，修正后再看24/25/33；不称Claude本轮逐张审64页或真人投资人认可。
- 已修24两个下层节点快速决策、独立留出不参与训练反馈；25专家分别汇入仲裁、校验小字与低饱和度，不称全局最优；30漏斗8/4/2与16+8+8=32完整评测，预算反馈只指回搜索，非32模型调用。33两评分反馈回路与独立留出、总预算、失败计账/负收益保持。38的80/30、总100已用20、A预留50余30、B拒绝50和v7/v8栅栏；41六路径连续屏障/未知按原操作查；58六证据依赖、e2历史保留及v17/v18条件提交保持。
- 全Python/PPT组装、LO导出、PNG和64页审计经n1.sh远程`/tmp/eap-bp-generated-diagrams-20261006/final`完成。三次导出，首次7页新渲染；第二次修24/30；第三次修24/25/33，每次完整64PPT/PDF及全64核验。原4436对象/文本全保留，新增26对象含7整图，原264图片与4原生图表、全部原媒体/工作簿/64备注/来源关系/财务文件保持；57原生页和PNG字节一致，可见原生缺字/越界0。栅格标签另以图片检查；正常PDF导出与审计通过，不把隐藏原文本视为图内文字可搜证据。
- 最终本地/远程一致：PPT SHA256=f8b37370367824515fb9d995768e23094cca15e01c5c2bbaf9e3d64fcf588af5；PDF=d91ffbce0d1c29088226c0fafb2daaa23da919fce4832cd1f0c0e37d43cf5faf；归档8623a9122fcf911c4d212ac111935b0c9427f4d4d37570b2db38a85bc7c600a3。checkpoint`.context/checkpoints/20261006-eap-bp-generated-engineering.md` DONE。通过后停止无新问题的重复构建。
- T1/T2/T3原正负结果、串行交接、独立Demo与三核心研发范围保持，未把其他线程T5-C新结果写进BP。无本机Python/node/依赖安装/构建/测试，无业务代码/部署/实例/模型实验/客户验收/commit/push；PROJECT_MEMORY只追加，T5-D等工程待项保持。未本机PowerPoint/Keynote原生播放或真人路演，商业/权属/融资证据缺口沿基线，不根据图稿或模型评审宣称可融资。

### Codex进展：BP原配色恢复、第18/29页修订（2026-10-06）

- 本阶段DONE。完整64页交付 `BP/EAP融资BP_配色恢复版_20261006/output/EAP融资BP_配色恢复版_20261006.pptx/.pdf`。用户先要求全稿提亮，随后反馈太单调，要求保留首页/过渡页蓝色并评价不如9月29日，最后明确“配色改回到前面的配色吧”。已撤回整轮提亮，恢复本轮开始的工程图示版固定基线；不把提亮试稿或模型PASS视为用户认可。首页、6章蓝色过渡页、结束页、正文原配色恢复，62页原始XML保持，今后以原配色为基线作局部必要对比度修正。历史版本均保留。
- 第18页shape19“编排执行”和22“治理审计”从白色改355A77，其他原对象与原文保持。第29页标题改“降低决策开销：精简输入、排除候选、复核动作”，两条创新明示语义摘要/硬约束分开和有依据剪枝/最终动作再复核。实际imagegen完整三栏图与原生共同成本区间图结合，A[10,12]、D[15,18]、C[9,16]在9..18同一轴，D可排除、C继续细评。动作合法且上界≤全部合法候选最低下界+容差；否则细评/未认证，不自动执行。硬约束变化重建合法集，同名资源1GB≠1TB反例保留；方法示意不是实测收益。原条件误差式δ+2ε+2η+κ与假设补入讲者备注，不声称新定理，通用剪枝不是原创方法。
- 本轮实际imagegen2次；29图SHA331f3f1c781840fcb609a3c165dd816bf8dc36ac2bef2f10e6f5f06328ea3a92原字节嵌入，未裁切/拉伸/改位图。浅色章节图真实生成但随用户撤回未采用，保留作过程档案，旧7张完整机制图均保留。图内文字有栅格，创新点/区间轴/结论仍是原生对象。
- 实际Claude Code成功6次、配色请求失败1次（393358ms/12000输出超限）原始/结果/Read/usage保存。两页代码2fca8bb5-20d8-4953-b3ec-7b5dad4790bc/58972ms，代码与original副本SHA585469b7082edcca30c88d44f74046d407dc86bad8c4bc2f9897cbdc81fcb7ff一致；宿主独立finish修区间轴/注释间距与遮罩。全稿提亮模块d2f34700-3731-4795-95c3-1df9fefde6dc/95986ms仅用于撤回试稿，不在最终入口调用。试稿A/B实际看8联系表及6重点大图指出29拥挤；“区间端点不准”推测由专项坐标检查否定，1GB/1TB删除建议不采纳。50“资金测算约”为基线简写，未借配色任务改变财务文案。
- 正式四页评审0792aadb-6dea-4dfe-b712-471cff6bc3a2/22539ms实际Read1/23/18/29，PASS、must_fix空；之后清理29旧3短横线，单页再审PASS。宿主看恢复后首页/23/18/29/50及恢复总览，清理后再看29；不称Claude逐张看最终64页。只读CLI禁Bash/Edit/Write、slash/MCP，无子代理，未发业务源码、原私有申报书全文、凭据/客户遥测。完成经验/时间线按skill写本地，未外发。
- 全部组装/LO导出/PNG/64页审计经n1.sh远程 `/tmp/eap-bp-palette-restored-20261006/final`完成，62页原XML与原配色保持，原4462对象/271图片/4原生图表全留，新增28对象含1图，原媒体/图表/工作簿/来源/财务不变。其他63备注字节保持，29原备注完整追加；可见原生缺字/越界0，正式PDF方法名称核验通过。轴坐标专项读最终PPT验证端点精确、刻度与说明间距0.041in。两次恢复稿导出由实际间距/残线问题驱动，未因任务完成重复部署或重跑业务实验。SSH短暂断连经包装器恢复，上传/下载摘要验证通过。
- 最终本地/远程：PPT SHAe536e94312bc4a48b2a521828b45f7809b24506f5ac4fad1aa74e8705edc9c62；PDF6e80de77d0afc1777e0062a794ea750a545940e93090ab2a35be68ef1c59bd22；归档b50b9194ba89a697e0057cce2a8767df762e7a68f5b2f5e0e04f76d2ca7e49f4。BP索引/README/三版对照/检查记录更新，checkpoint `.context/checkpoints/20261006-eap-bp-palette-restored.md` DONE。最后成品核验通过后停止无新问题的重复构建。
- 本机无Python/node/依赖安装/构建/测试，无业务代码/部署/实例/模型实验/客户验收/commit/push。T1/T2/T3原正负结果、完整Demo、真实大屏与左导航、竞品图谱、三核心/九创新和研究/商业边界保持；没有把T5-C新工程结果写进BP，T5-D等工程待办不变。PROJECT_MEMORY只追加；没有实际本机PowerPoint/Keynote播放/真人路演验证，不根据模型评审宣称可融资。


### Codex 进展：T5-D1任务实例身份远程回归通过，待部署验收与Gitee提交（2026-10-06）

用户明确要求“别忘记提交gitee”，本轮据此授权正常提交并推送当前工程分支，不再沿用此前未授权commit/push的限制。主线尚未全部完成。

- T5-D1新任务冻结原生Create回执UID/模板、固定镜像、公钥、网关和原创建请求；底层Redis在Create前保存不可重试意图，回执丢失或UID变化不按名称接管、不重建。旧任务保留LEGACY_SHARED，旧模板、意图、预算和快照不迁移。公开补丁与源摘要在runtime/ax-integration，合同docs/TASK_RUNTIME_IDENTITY.md。
- 新任务执行器仅持有专属短凭据，复用签名挑战、内存RSA加密配送和HMAC确认；UID复核及配送确认后才持久化RUNNING并POST一次，其后只查询原回执。普通登记/心跳、人工批准、通用控制及其他工具入口不能绕过任务身份。
- 模型与观测须绑定原RUNNING任务、Agent、实例UID和模板；缺任务头、跨任务、终态、变更调用标识及共享网关借用新任务均拒绝。预算继续使用服务端验证的Task头，Trace变化不产生新额度。按需重装/续期不重置身份周期、原授权、工具根或预算；默认900秒，原批准最多24小时，撤销永久取消原任务批准。
- 最终远程后端定向58项/6类、完整521项/91类，零失败/错误/跳过，打包通过；前端190项、类型检查和生产构建通过。任务执行器完整-race12顶层及生产编译通过；底层新合同-race12顶层/26含子项，完整68顶层/135含子项及server/controller编译通过。Go无测试包的skip事件不计作测试用例通过。
- 真实合成签名加密HTTP、独立Redis并发、实际MockMvc身份/预算拒绝及真实Go protobuf回执跨语言解析均有覆盖；不把模拟原生API回归当真实集群全链路。v2测试桩错误、v3完整回归主动中断、v4真实HTTP拒绝仍显示UNKNOWN的失败记录保留；v5将明确前置身份拒绝标为REJECTED并重新完整回归。
- 690候选源码摘要核对及集群实际Secret原文/编码内存扫描通过。本轮36文件精确补丁包含14新文件；Gitee提交同时包含此前T1–T5-C已经验证且新代码依赖的工程底座，按源码清单和工程文档白名单选择，保护其他线程BP及研究材料。PROJECT_MEMORY本地只追加；提交该文件只使用原HEAD记录加本工程条目，其他未提交记录留在工作区。
- 尚未部署新镜像、运行V63迁移或创建真实验收任务。下一步固定五个镜像digest、先发布兼容底层并冻结旧任务Runner，再部署后端并启用新任务模式；之后有界ECHO/单次模型/串行工作流、实际预算/Trace、到期/撤销/恢复及四角色桌面/手机验收。不能称T5-D1真实验收已完成。
- 只读预检首次失败因认证Pod匹配仍用旧app=keycloak标签，实际持久实例app=keycloak-persistent，原UID bfa818d4-6f6d-4d03-b974-7f6ac6179dcb且Ready。配送Secret实际immutable=true；没有变更密钥或认证资源来满足断言。
- T5其他入口/旧共享凭据迁移、T6通用恢复、T7分级观测、T8策略及调度公平/老化/画像仍待，没有Agent调优稳定收益证据，旧演示视频未重录。旧T4 Workflow15原UNKNOWN及所有已完成验收不重放。全部测试构建部署远程，本机只文本/摘要/接收证据。

### Codex 进展：T5-D1已提交并部署，时区修复上线、原实例恢复仍待验收（2026-10-07）

主线尚未全部完成。下一步继续当前任务身份阶段；T5其他入口和旧共享凭证迁移、T6通用恢复/发布取消、T7分级观测、T8策略及调度公平/老化/画像保持待办，没有Agent调优稳定收益证据。

- 用户“别忘记提交gitee”授权正常提交推送，原工程提交38300900ad93806bb8acf58a76ba6d0861bc419c已核对Gitee远端。提交690精确验证源码与工程文档，BP/其他研究/AGENTS未带入；PROJECT_MEMORY提交仅HEAD加工程条目，本地其他记录保留。
- 已部署后端/前端/任务执行器/原生任务服务及控制器五个固定digest，V63成功；新任务身份模式开启，补齐eap-ax可观察空间后真实capabilities enabled=true/instanceIdentity=true。持久认证原UID、不可变配送Secret及默认900秒、旧任务Runner和通用Runner保持。
- 独立Agent50、模型总预算1/输出1024；仅提交原回显ax-82cb1041-e7f2-4fb3-aafd-ce2f6fa1df51，UID d7dd465c-873b-405f-a9bc-87cee1d79659，SUCCEEDED/SUSPENDED、模型0。短凭证真实空闲跨900秒自然到期，原metadata/UID/版本不变、旧凭证401。
- 实际MySQL JDBC Timestamp读取较现有Hibernate Instant映射少8小时，导致任务UI提前EXPIRED，原连接授权首次缩短为16小时。首版UTC Calendar在H2三项回归失败、未部署；旧源码加H2非UTC会话14项PASS，不能称红测复现。实际MySQL只读驱动探测PASS、确认8小时偏移，原数据没有写。
- 最终时区修复使用AxTaskIdentity类型化JPA投影及现有连接实体映射，不改全库时区/历史时间。远程定向59/6及完整522/91零失败、错误、跳过，打包通过；691源码摘要与实际Secret内存扫描PASS，精确6文件安装。只更新后端至10.186.133.66:5001/eap-backend@sha256:c8fa8cb5cb46c355797a8791966e4aee32a94b33e46b59baabf93375940e3865，实际Pod UID3b1590cb-8c6c-476b-84b2-b931e8947b43，健康与能力通过；原任务和连接EXPIRED一致，原UID、Task24小时及连接原16小时期限保持。
- 原实例恢复两次400，没有生成新的待配送操作，未执行模型。只读诊断确认登记被旧心跳清理在16:01:20.988395误标OFFLINE；批量JPQL未排除SUBSTRATE_TASK。修复该排除项的V8新增回归有1失败，原因是夹具初次保存自动补心跳，HTTP“缺心跳”断言没有测到NULL；V9保存后明确清空，再做缺失/陈旧心跳、旧原生协议、普通HTTP和显式OFFLINE边界回归。V9测试进行中、尚未安装/部署；不把首版失败说成全通过。
- 为避免占用运行槽，仅对本轮已确认终态原实例由所有者CLI恢复原快照到休眠，UID和原externalSnapshot保持，原消费0。修复发布后需审计/CAS核对本轮唯一误清理登记，不恢复任意OFFLINE，不延长旧批准或换任务冒充续期；原实例恢复、真实模型一次、串行工作流、跨任务拒绝、四角色/手机浏览器及最终撤销仍未完成。
- 原690版本前端190/类型/构建、任务执行器race12顶层和原生完整68顶层/135含子项结果沿未变源码保持有效，不称本次重新全部测试。旧T4 Workflow15原EXECUTION_UNKNOWN、旧预算、模板及所有完成标记不重放。全部测试/构建/部署/浏览器远程；节点状态eap-t5d-evidence.json completed未置true，绿色/root/eap-src仍T5-C。


### Codex 进展：T5-D1任务入口专属身份已部署、真实验收与证据导出通过（2026-10-07）

**主线尚未全部完成。** 本轮完成新任务入口的有界实例身份阶段；T5其他入口和旧共享凭证迁移、T6通用恢复/发布取消、T7分级观测、T8策略，以及调度公平/老化/阶段画像继续待办。没有确认Agent调优稳定收益。当前状态清单见docs/MAINLINE_STATUS.md，下一步T5-D2先盘点剩余入口与旧共享凭证，逐批设计迁移验收；不能改写已完成/UNKNOWN原意图或预算。

- 修复原生任务登记被通用心跳清理误标OFFLINE：批量JPQL排除SUBSTRATE_TASK，缺失/陈旧心跳、普通HTTP过期与显式OFFLINE边界覆盖。正确新回归在旧实现3项/1失败，V8夹具自动补心跳失败保留；V9定向62项/7类、完整523项/91类零失败、错误、跳过并打包通过。源码691摘要与实际Secret内存扫描PASS，精确2文件安装。前端190/类型/构建、任务执行器race12顶层、原生68顶层/135含子项沿未变源码保持有效，不称本次重新全部测试。
- 当前backend固定10.186.133.66:5001/eap-backend@sha256:5a989a8bea5b7b2c91da0be6a34da0bf5d83c40369168400ebbdb001f8c42f00，实际PodUID a00021b9-048d-42a8-86d1-e7b018f24190；其余4镜像保持本阶段首次发布摘要，V63成功，新任务身份能力开启。认证持久Pod原UID、旧emptyDir replicas0、不可变配送Secret及默认900秒、旧任务Runner和通用Runner不变。公开清单仅同步当前摘要与身份配置，没有整份应用历史清单。
- 独立Agent50/模型总上限1/输出1024。回显原任务ax-82cb1041-e7f2-4fb3-aafd-ce2f6fa1df51、UID d7dd465c-873b-405f-a9bc-87cee1d79659；模型原任务ax-633e5361-a1f9-497c-a84f-992e8169b895、UID ded65903-7752-4cca-ac9a-0f6d2776a672。模型恰好1次，SETTLED、根used1/reserved0/cap1；实际网关、预算、任务回执和Agent Span的隔离Trace一致，runtimeTraceId保留原执行器编号，traceId已被后端隔离，不二次散列。
- 原回显真实空闲跨900秒自然到期、旧凭证401、UID/原生版本/身份周期不变。仅本轮确证误清理的一条登记按原UID/时戳/批准/租约CAS修ONLINE并同事务审计RECONCILE_TASK_LEASE_BUG，未重置凭证/预算，任意OFFLINE不恢复。修复后首次resume400产生原待配送c3163618-9f0f-453c-bd63-e1b37166db4d；客体readyz200后仅核对同一操作成功，首400原因没有栈证据，不猜测。周期不变、serial1→2，旧凭证401；原Task24h与旧时区错误首连接缩短16h期限均保持，不倒填延长。
- 原所有者CLI恢复同UID/原快照，当前凭证读取旧客体401；原任务重装同一当前租约后读取原SUCCEEDED回执200，再休眠。回执SHA保持、没有业务重派、没有模型消费。只证明本轮原实例快照重装，不表示EAP通用恢复/T6完成。
- Workflow18原运行wf-exec-a9c27901-59c7-488c-bd6b-f1a91efbde97成功；first UID7a1dec03-7d1e-4b8d-96f2-a623a5d19785，second UID9925941b-8b82-47da-b5c9-da04ef83df22，两个原任务实际串行传递第一步回执，模型新增0。缺Task头/跨任务/终态/不同调用标识/人工凭证/不支持入口被拒绝；原提交重复返回同Taskkey，改参数409；跨凭证401。共4独立UID且都跨180秒心跳超时仍ONLINE，最终SUSPENDED/REVOKED，原恢复409且原生version不变，模型1/预算不清零；旧Workflow15原EXECUTION_UNKNOWN保留。
- 四角色真实API和1500/390px浏览器通过，runId feb59e41-817f-42ff-9fef-e627f5b00d2e，20截图、业务写入0、页面异常0、失败API0。已查看admin桌面/手机/公开配置，长UID及公钥换行，无横裁。模板验收初次误排原生公开AX_TASK_YAML、卷名identity与实际eap-identity不同，按原源码合同修正，未改应用绕断言。收尾首门禁因浏览器脚本写旧状态覆盖工作流汇总标记被阻断，业务回执未变；只读核对原运行后恢复证据，非阻塞阶段锁和原子保存使写证据阶段串行。失败史留acceptance-diagnostics.json。
- 归档artifacts/eap-task-identity-20261006/index.html及同名tar.gz，7993584bytes，SHA2567229a231ac4b842b9b8396bf7d725cae8aa3dd73f1cf6637e0424422296376e5；本地1085文件摘要、25链接、20截图核验一致，实际Secret及原/续期机器凭证内存扫描PASS，合成日志凭证2处导出脱敏且原SHA保留。导出最初误估39变更被门禁挡住；固定基线676→V5变36，V7新增投影及原未变Repository共2，V9共2，最终精确40路径/691源码核对通过，不改测试源码凑数。通过后已镜像/root/eap-src691；节点eap-t5d-evidence.json completed=true及所有阶段键保留，禁止重跑prepare/submit/workflow或换意图重放。
- 已正常推送工程底座38300900ad93806bb8acf58a76ba6d0861bc419c及时区修复fcf3904ecc2fbf82b49dd8c77cc0b6e5fe5c320f；本轮另提交心跳修复和工程交接。用户“别忘记提交gitee”授权正常commit/push；BP、其他研究和AGENTS不带入，PROJECT_MEMORY工作区只追加，提交只用原HEAD加本工程条目。全部依赖/测试/构建/部署/浏览器在远程，本机仅文本/摘要/脱敏证据；旧演示视频未重录，内部PoC不等于生产mTLS/原生分配栅栏/完整灾备。

#### T5-D1收尾确认

归档接收与本地1085摘要/25链接核对通过、admin桌面/手机/公钥配置已查看。按冻结UID和DeleteOptions UID前置条件仅清理本轮20个测试Job及26个测试Pod，剩余0；保护状态、4原生实例休眠/撤销及旧Workflow15 UNKNOWN再次确认通过。清理脚本与最终状态另留K8Substrate tools/eap/task-identity和节点，原归档SHA不再更换。

### BP记忆保存确认与当前文件定位（2026-10-07）

- 用户明确“保存记忆”，本次仅保存最终偏好、现有成稿位置与恢复记录，不修改PPT内容/配色，不运行新构建、业务实验、部署或git提交。详细恢复记录 `.context/checkpoints/20261007-eap-bp-memory-confirmed.md`，前次完整过程记录继续保留。
- 最后生效的配色指示仍是“配色改回到前面的配色吧”：撤回全稿提亮，沿用工程图示版原蓝色首页/章节过渡页与正文配色。全白/过浅方案被反馈单调，不能把提亮试稿或模型PASS当作用户认可。当前64页、页序和好内容保留，第18页白字修复、第29页完整技术图解及成本轴改进保留；其余62页原始XML不变。
- 本地现有文件实际已简化为 `BP/EAP融资BP_配色恢复版_20261006/output/EAP融资BP_20261006.pptx` 与同名 `.pdf`。2026-10-07重新读取SHA：PPT `e536e94312bc4a48b2a521828b45f7809b24506f5ac4fad1aa74e8705edc9c62`、PDF `6e80de77d0afc1777e0062a794ea750a545940e93090ab2a35be68ef1c59bd22`，与10月6日最终交付一致，文件未丢失。旧README/索引及SHA JSON仍用长名称，后续定位使用本条实际短文件名；本次不改历史记录或恢复别名。
- 原有真实大屏四编号、完整左导航、统一尺寸截图、竞品图谱、完整曲线Demo、七幅原生成机制图与财务口径保持。核心三线同等重要，创新点分条简单易懂，充分依据联通/字节/华为原文；正文用“智能体”，不出现AgentChaos等方法名称，避免PoC验收备注与AI式口号。继续实际用Claude Code协助，需要图形时使用实际imagegen，不以简单卡片替代完整工程图。
- 前次64页PPT/PDF/PNG核验、成本轴精确检查和正式Claude关键页复核继续有效，本轮只核文件摘要，未宣称重新逐页检查/验收。项目记忆只追加，保护工程线程T5-D1/T5-D2记录及当前未提交改动；记忆保存不授权commit/push或改变业务/部署。


### Codex 进展：T5-D2新镜像/源码入口专属身份已部署、有界验收与导出通过（2026-10-07）

**主线尚未全部完成。** 新发布 RUNNER/IMAGE/SOURCE 统一专属实例身份；旧共享凭证与模板尚未迁移，不能称T5全部完成。继续T5旧身份逐批迁移、T6通用恢复/发布取消、T7分级观测、T8策略，以及公平/老化/阶段画像和真实Agent调优收益验证。用户最新要求继续核读字节、华为、联通申请书，改进建议按原文与当前代码/证据核对；研究不能自动触发业务实施。

- 合同docs/CUSTOM_RUNTIME_IDENTITY.md。新IMAGE/SOURCE冻结PER_INSTANCE_V1与公钥，源码先构建并固定摘要，原生Create回执UID确认后配送/确认才可运行；不注入共享机器凭证、不因失败回退。配送/派发配置缺失或自定义端口非80在产生新副作用前拒绝。同一旧发布请求优先读取原记录，旧端口/模板/身份不迁移，异参409。前端固定80、说明协议要求、区分旧身份待迁移，镜像6/源码7阶段；权限仍从PlatformPermission及/me读取。
- 只读盘点47个可见Agent、24部署、56登记、59原生实例，范围已连接/未截断；22旧发布为RUNNER13/SOURCE6/IMAGE3，包含失败历史，不等同在线实例。24可读取引用模板含至少一个旧任务/网关/观测共享凭证名；未涵盖其他配对凭证及无实例引用的遗留模板。盘点仅环境名/公开元数据/摘要，不保存凭证值。
- 精确7源码变更、691源摘要与实际Secret内存扫描PASS。远程旧实现20项产生3失败/2错误，定位5个新合同缺口；新增第6项禁用派发原已拒绝。修正版定向44项/5类、完整529项/91类零失败/错误/跳过并打包通过，前端192项零失败/跳过、类型检查/生产构建通过。未变Runner与原生服务沿前阶段验证，不称本次重新全部测试。
- 只更新后端/前端镜像并验证实际imageID。backend固定10.186.133.66:5001/eap-backend@sha256:1a8e7e2c95eff77eeac0cb416c98a6fa667f0e7ef9e59f6b6ab9b962d24dfd8f，PodUIDdf5b1200-c30f-4bc9-9ebe-938039d9ed67；frontend固定10.186.133.66:5001/eap-frontend@sha256:468689eab2b1a481e8bf1d209adad78602abf7f28111ba5543c206f338cdd8d9，PodUID2bf54081-8631-4713-952a-0b3d07fa13c0。buildId=2026-10-07T01:50:52.323Z，健康与身份/构建能力PASS。认证PodUID、不可变配送Secret及默认900秒、后端环境、任务Runner/通用Runner/原生镜像保持。公开PoC清单只同步两镜像，不整份apply。
- 独立IMAGE Agent51、SOURCE Agent52，模型/输出总预算均0。两个原工作流成功，各产生一个工具存储确认的受控计数效果，root TOOL_CLAIMS cap1/used1/reserved0；模型新增0。跨实例凭证401，同一发布/创建请求幂等，异参409，四角色能力对应200/403。最终两个原UID休眠、身份及根授权REVOKED、旧凭证401；原创建请求409/原生版本不变。旧24部署配置/状态/实例键、两个EXECUTION_UNKNOWN及原模型记录摘要保持。
- SOURCE首次错误CGI路径导致v1构建404/失败/无实例，显式更正仓库URL形成新v2；随后固定40位提交构建失败，因为独立夹具Git未允许精确Tip SHA获取。仅该新裸仓库开启uploadpack.allowTipSHA1InWant，保留两次原失败日志，再经既有API重试同v2/同提交，成功构建并接入原唯一实例。没有改全局Git配置、降低固定提交要求或另建v3。公开夹具commit35112d3570e91dd5c73d244d96e83fa5f62d907c；旧/新回执及诊断全部保留。
- 4角色1500/390px浏览器PASS，runId0d217814-2460-46f0-bf59-ae718b868d57，22截图、业务写入0、页面异常0、失败API0；已查看admin源码桌面/手机/表单，阶段和长编号换行可读。没有重录旧视频。
- 预算原始整体看板只保存摘要，不能声称全看板字节未变。两次新工具结算使平台TOOL_CLAIMS合法增加2及更新时间改变；MODEL_CALLS仍33/OUTPUT_TOKENS仍1904，无新增模型。old-budget-proof.json原false结果与budget-delta-proof.json语义PASS一并保留；未清零原消费。
- 脱敏证据K8Substrate artifacts/eap-custom-runtime-identity-20261007/index.html，同名tar.gz2461767bytes、SHA256b4f9092ecda6b40626bd568c4bf155a06fa16bd0b803c4c218fa1be86062dac6，260文件摘要/22截图本地核对PASS。导出后精确7变更镜像/root/eap-src，691源核对一致；节点eap-t5d2-evidence.json completed=true。禁止重跑prepare/publish/workflow或旧UNKNOWN原意图。仅清理本轮7测试Job/8测试Pod、按冻结UID前置条件，原实例与身份撤销保留；辅助清理首次误拒构建Pod后缀，冻结计划与诊断保留，修正后7/8全部不存在、保护检查PASS。
- 本地访问隧道复用批准桥接脚本，绑定127.0.0.1:5173/8180，前端/认证HTTP200；现隧道会话保持。偶发延迟仍需重试，不称跳板故障已彻底解决。全部编译/测试/构建/部署/浏览器远程，本地仅文本、摘要和脱敏证据。内部HTTP PoC不等于生产mTLS、原生分配栅栏或完整灾备。

用户“别忘记提交gitee”授权工程正常commit/push。提交只选本轮精确源码与工程文档；不含BP、其他研究、AGENTS或私有申请书。PROJECT_MEMORY工作区只追加，提交使用原HEAD加本工程条目，保护其他线程未提交记录。


### Codex 进展：字节/华为/联通方法再核读与主线完善建议（2026-10-07，研究记录）

用户要求继续调研原申请书和报告，已按deepresearch逐项核对原文、当前实现和既有实测。研究成果仅本地K8Substrate outputs/eap-project-methods-review-20261007/README.md（同目录contract/evidence/source-register/qa及8公开快照）；37来源注册含6私有原文、15代码/合同、8PoC回执、8官方公开资料。原文只本地核读/存摘要，未上传私人全文、未覆盖旧研究/BP、没有研究触发的新模型/业务执行或部署。

改进优先级：R1补全旧身份引用并逐批迁移；R2真实工具对象/批准来源/幂等查询取消提交补偿能力矩阵；R3按原操作恢复/outbox/执行侧旧写者隔离与责任交接；R4确定性公平/老化/阶段和准备占用画像；R5真实独立任务组/完整成本后再扩上下文、工具和工作流变量；R6分级观测/关联依据/实验Span；R7知识来源派生失效/联合版本包；R8有限模型的极小权限/必要协同/阻断集/调度证书独立研究。每项已有原文锚点、代码缺口、强基线、验收与停止条件，建议不代表实施授权或效果成立。

纠正旧10/2、10/4状态：T3固定GEPA已验收但KEEP_BASELINE；新受控工具有独立存储效果；新任务与IMAGE/SOURCE身份已验收，旧共享凭证模板仍待迁移。T3两个根各maxCalls32，实际7+16=23，不把“23/32”误读为两根累计上限32；输入/金额不是现硬预算单位。E1整看板SHA因合法工具结算改变，旧false证明与解释保留。字节/联通原文明确研究方法未实验；华为为246页交流报告，不代表华为产品/独立申请书，未把论文数字当EAP收益。

此前授权的T5-D2工程收尾已完成、正常提交推送Gitee 1b19ddb189ee61d46dd74fd4fc25c4785ba086ce并核对远端；仅12工程文件，不含本研究/原申请书/BP/AGENTS。全部691源与远程绿色摘要一致。原完成/失败/UNKNOWN、预算及效果仍保留；主线尚未全部完成，没有稳定Agent调优收益。

### Codex 进展：工具能力合同、原操作只读核验与完整旧身份清单（2026-10-07）

**始终聚焦调度、Agent性能调优、权限治理三条主线；系统主线尚未全部完成。** 用户最新“继续完成”授权实施，随后强调持续聚焦主线。本阶段完成R1扩展清单、R2受控合同能力表和R3原操作查询边界，不等于旧身份迁移完成、全部业务工具适配或通用恢复。下一步继续持久步骤责任/执行侧隔离与工具适配、旧凭证消费者核对，再推进确定性公平/老化/阶段画像与独立任务上的真实Agent调优；分级观测和联合策略沿主线保留。

- 新工具能力表按名称和种类精确区分可信存储合同、不支持和尚未核实；MCP或策略白名单不能证明幂等、查询、取消、补偿及提交屏障。控制台查询原根/原操作，显示原效果、原根预算和观察时间，超出最近30条也可查；失败、记录缺失、PREPARED/UNKNOWN不变为未提交。查询不执行、不领取/退还预算、不续授权，也不自动改写工作流终态。权限仍来自PlatformPermission.VIEW_CONSOLE及/me，无新角色解析。合同docs/TOOL_CONTRACT_RECOVERY.md。
- 全局58模板与五作用域逐一枚举一致，39模板有旧凭证引用名（任务30/网关28/观测21/配对9，存在重叠）；9模板在当前EAP实例/发布记录无引用，不等于全球无消费者。61纳管盘点项/27部署/22旧部署；环境值只留节点RAM，输出名字、UID、公开镜像和配置摘要。命令、卷内容、golden快照、外部消费者和上游分页完整性未证明，因此共享凭证未退役、旧模板未迁移。
- 精确7源码/691摘要及实际Secret节点内存扫描PASS。远程定向59项/4类、完整540项/91类零失败/错误/跳过并打包；前端198项零失败/跳过、类型及生产构建通过。覆盖丢弃执行返回后查询原效果、撤销后历史查询、持久UNKNOWN不重试、跨范围拒绝、31条之后原操作查询、同名MCP无可信能力和四角色/匿名权限。Runner和原生未变，沿此前证据，不称本轮重测全部底层组件。
- 仅更新backend/frontend固定镜像，健康、实际imageID与公开版本PASS。backend 10.186.133.66:5001/eap-backend@sha256:fdd52528e0c8d1857167f551915713a59b2e22cc6539a0efae5f70fbe4649f4d，PodUID 86033343-97a6-4578-a617-d9edabc64542；frontend 10.186.133.66:5001/eap-frontend@sha256:49a8b4009af5827e9e44dea20e6ac65b079134b7ed863d577b076d40648ae0e8，PodUID 20307d83-eaf7-4dff-a69f-9c009fe46e82；buildId 2026-10-07T03:21:37.704Z。认证Pod、配送Secret/900秒、环境和原生/Runner镜像保持，不整份apply。
- 独立IMAGE Agent53，原UID cdce6f92-6d9b-499f-b94e-b09ed9775a19，原发布 dep-31001b83-63a9-4a28-9488-1c17e6d4c422；MODEL_CALLS/OUTPUT_TOKENS上限均0。验收客户端持实际专属机器凭证直接调用受控工具一次，主动丢弃响应体，再由四角色查询原O a6f96201-fb67-4aff-b97c-e69197374abe，确认同一effectId 9fcfd346-f121-448d-bb60-2471c3f4ea88、计数1。不是实际网络分区/控制器崩溃，也不是该原生程序自行发起业务。最后原UID仍休眠，身份和根撤销，查询原终态保持，根cap1/used1/reserved0，模型新增0。
- 原60账本账户（平台TOOL_CLAIMS合法+1及更新时间除外）、39预占、252关联项、39结算逐行保持；原27工作流/13任务/27部署、7范围授权/16工具操作/6计数、19连接身份元数据/58UID绑定的冻结公开元数据与摘要保持。两个UNKNOWN物理行12/20保持，未重放。原各Agent模型观测条数保持；总观测表60条与账本MODEL_CALLS已用33是不同统计，history-proof原字段originalCalls按观测行计数，不作为60次真实供应商调用证明。平台OUTPUT_TOKENS仍1904，没有清零原消费。
- 四角色1500/390px浏览器通过，runId 3a40f1e8-be6f-4899-b334-b0708522bba1，16截图、业务写入0、页面异常0、失败API0；实际查看admin桌面能力表和手机原操作，长编号换行可读。证据K8Substrate artifacts/eap-tool-contract-recovery-20261007/index.html，tar.gz3400870bytes、SHA25643e3b33dfb691d67bf2bbc1919e15a9b3ee9f2221a0e0b913a1489cdc4a6a1ff，247文件摘要/16截图本地核对PASS。仅按冻结UID清理4测试/构建Job和5测试Pod、剩余0，保护检查PASS；原归档不修改。节点completed=true，导出后691精确源已更新/root/eap-src；禁止重跑effect/publish或旧UNKNOWN。
- 盘点首轮env列表当map、授权冻结首轮MySQL批处理转义嵌套JSON，均只读失败、未写基线，修正后完成。起测脚本遗留t5d2-v1标签及RED措辞，仅执行两个绿色测试Job，无red回归；清理精确名称/UID避免误选，诊断随归档。全部依赖/测试/构建/部署/浏览器远程，本机只文本、摘要、脱敏文件和看图。旧视频未重录；没有稳定Agent调优收益，KEEP_BASELINE不变。

沿用户此前“别忘记提交gitee”授权正常工程commit/push。只选择本轮源码与工程文档；AGENTS、BP、私人原申请书和其他研究不提交。PROJECT_MEMORY工作区只追加，提交内容是原HEAD加本工程条目，保留其他线程所有未提交记忆。

### Codex 进展：原工具步骤持久查询责任与平台进度隔离已部署验收（2026-10-07，T6-B）

**始终聚焦调度、Agent性能调优、权限治理；主线尚未全部完成。** 本阶段有界完成受控计数工具的查询责任，不等于通用恢复、实际执行侧epoch屏障或自动推进。下一步先执行侧责任交接/逐项业务工具适配与旧凭证消费者核对，再继续确定性公平/老化/阶段画像、独立真实任务上的Agent调优、分级观测及联合策略。旧GEPA保持KEEP_BASELINE，没有确认稳定收益。

- 合同[WORKFLOW_TOOL_RECOVERY.md](WORKFLOW_TOOL_RECOVERY.md)。V64新运行version1保存owner/epoch，派发前核对原快照/类型化参数，冻结原根、O、对象、delta、taskKey及原UID/generation/合同/指纹，保存查询outbox；已有工具意图不能重新认领为新运行。独立worker每轮最多8个原查询，30秒责任租约及CAS允许接管并拒绝过期查询者；旧progress入口不能修改新版本，运行进度同时核对owner/epoch。查询不执行、续授权、领取/退还预算或派发后续节点。缺失/PREPARED/读失败/指纹不匹配为UNKNOWN保留责任，可信存储确认才CONFIRMED/NO_COMMIT。核验与工作流终态分开。
- VIEW_CONSOLE和/me沿现有集中权限；四控制台角色可读，匿名401、DATA_OWNER/NONE403，接口无原工作流业务输出。执行过程展示原操作/根预算/UID/查询轮次/观察时间/效果，权限与身份切换、读失败和迟到响应清理旧证据；刷新只GET。新页面无AX/Substrate产品名。
- 精确19变更/697源码摘要及实际9个Secret节点RAM扫描PASS。最终远程定向99项/6类、完整561项/92类零失败/错误/跳过并打包，前端203项零失败/跳过及类型/生产构建PASS，前端v1源码与最终v5逐文件相同。隔离故障Runner普通Go回归23顶层/36含子项及编译PASS，不是race；生产Runner和原生未修改，不称本轮重测所有底层组件。
- 固定backend 10.186.133.66:5001/eap-backend@sha256:fb1eeeedf61f0fae11c1aa513ffa70cb5cffc1d6d60d8908b44d6027cb9091e3，首次PodUID 7c36e38f-43f0-4591-8e0b-2e70503f111f，重启后实际PodUID e1391188-cd79-4097-ba47-5cecb3e4eee4；frontend 10.186.133.66:5001/eap-frontend@sha256:af7eb74c853a1ed2c9ad93f5ffc6af321c67a019843f622d13d97d658cb643ff，PodUID fb8e28fc-3975-46dc-b9c5-56d05fdcc6db，buildId 2026-10-07T04:20:30.939Z。实际imageID/健康/版本、V64 PASS；认证Pod、配送Secret/默认900秒、环境和原生/生产Runner镜像保持。只定向更改两镜像及一次有界backend重启注解，清单只同步两digest，无整份apply。
- 独立IMAGE Agent54，原发布 dep-302583b4-1eee-4035-a70a-75db722865e0，UID 9c6e4802-7a94-453e-9e7d-33f72025bab5，模型/输出额度0。实际Actor通过专属身份执行原受控工具，存储与actor日志提交后HTTP Hijack关闭任务TCP响应；原工作流21/运行 wf-exec-98e23653-98ac-4001-bf5c-e301b07926a4 保存EXECUTION_UNKNOWN，END没有执行。查询原O 04e45d31-5a5b-4024-989b-4ef4c4a2e855，确认同一effectId 1cc154b5-f78d-49de-935c-39130a04a8b4、计数1。实际backend更换Pod后查询/原UNKNOWN/完整预算行保持。重启发生在工作流已UNKNOWN/核验CONFIRMED之后，不称活跃查询租约内崩溃或生产网络分区；该租约并发/接管由独立数据库回归覆盖。
- 原64账户/40预占/255关联项/40结算逐行保留（平台TOOL_CLAIMS仅预期+1及更新时间）；原27工作流快照/步骤/理由摘要、13任务/28部署、8授权/17工具操作/7计数、20连接公开元数据/59UID绑定保持。旧UNKNOWN物理12/20未重放，V64后旧27运行仍version0/epoch0/owner空。原模型观测60行和账本MODEL_CALLS33/OUTPUT_TOKENS1904分别保持；新增模型0，不把观测行数当供应商调用次数。最后原UID休眠、实例身份/根撤销、策略停用，根cap1/used1/reserved0，历史核验仍保持。
- 本轮盘点59模板/62管理项/28发布/原22旧发布/39旧凭证引用模板/9当前无管理引用模板；全局和分作用域枚举一致，不证明外部消费者、挂载内容、快照、分页完整性。共享凭证未退役、旧模板未迁移。
- 四角色1500/390px浏览器PASS，runId 188be9f6-cc91-41b6-9fcd-1bac34b9bb08，8截图、业务写入0、页面异常0、失败API0；实际查看admin桌面/手机，长编号及预算换行可读。证据K8Substrate artifacts/eap-workflow-tool-recovery-20261007/index.html，tar.gz 2172154 bytes/SHA256 92b296d6d6fb3a2dc8e08b0d7eb04e0d2fe5666064058fa6adb932d019065841，494摘要/8截图/23 HTML引用本地核对；697本地源与远程源一致。导出后精确19文件推进/root/eap-src，节点completed=true。10本轮测试/构建Jobs和11Pods按冻结UID清理、剩余0，保护PASS；清理及最终运行状态单列补充，原归档未修改。
- 候选v1 AOP目标Object编译失败；v2六项JSON数值节点比较错误及一项迁移计数失败；v3中间绿色；v4源overlay中途CAS拒绝后按原/最终摘要恢复、三项泛型断言编译失败；最终v5明确Integer修正后通过。无旧实现red对照。在线发布检查误用AVAILABLE，实际READY，仅修正辅助检查；未published的workflow guard在业务写入前拒绝，原发布和运行随后各提交一次。首次本地下载SSH255失败、经批准桥接重试后摘要通过。失败记录随归档保留，不称首次全绿。
- 实际执行侧epoch隔离、资源责任交接、自动节点恢复、取消/补偿、真实业务工具、多版本混合生产升级及灾备仍待。全部依赖/测试/构建/部署/浏览器在远程，本机仅文本/摘要/脱敏文件与看图。旧全功能视频未重录。本地5173最新版本与8180认证元数据200，既有隧道保留；跳板偶发中断不称已彻底消除。

用户此前“别忘记提交gitee”授权本工程正常commit/push。只选择本轮19源码和5工程文档，保护BP/AGENTS/其他研究/私人原文。PROJECT_MEMORY工作区只追加，提交内容为原HEAD加本条目，其他线程未提交记录完整保留。禁止重跑prepare/publish/workflow/effect或旧UNKNOWN，不换意图造新证据。


### Codex 进展：新受控计数根的效果执行责任屏障已部署验收（2026-10-07，T6-C）

**始终聚焦调度、Agent性能调优、权限治理；主线尚未全部完成。** 新根效果事务核对原责任是有界阶段，不等于通用工具恢复或全部集成生产验收。合同[WORKFLOW_TOOL_EFFECT_FENCE.md](WORKFLOW_TOOL_EFFECT_FENCE.md)。下一步推进T2确定性公平/老化/阶段画像和T3独立真实任务对照，继续真实业务工具适配、旧消费者迁移、资源交接/取消/补偿、T7分级观测及T8联合策略；旧GEPA KEEP_BASELINE，没有确认稳定优化收益。

- V65只新增workflow_tool_effect_roots，不自动改旧根/操作/查询。新运行派发前登记原root/run/owner/epoch；已有意图/历史查询不能升级，同根另运行/轮次不能接管。工具存储按可信根找到冻结原O/参数/身份和DISPATCHING步骤，不接受客户端epoch；原RUNNING/owner/epoch/deadline失效拒绝。scope→workflow→identity/policy→budget锁顺序一致，提交锁序保证已确认责任变更后无旧效果。预算/策略写后、计数前再次期限检查，失效同事务rollback未提交准入，再独立记录原REJECTED，不是退款。原COMMITTED回放只读；新O/参数绕过拒绝。历史/未登记/非工作流根保持原合同，业务工具没有自动获得这些能力。
- 精确7源码/699摘要与实际9Secret节点RAM扫描PASS。最终远程定向108项/6类、完整570项/92类零失败/错误/跳过及打包通过；新增8真实DB/并发/预算回归和1迁移回归。隔离故障Runner普通Go回归23顶层/36含子项与编译通过，不是race。前端源码逐项与T6-B相同，沿203项/类型/生产构建证据，未在T6-C重复构建。生产Runner/原生代码镜像未变，不称本轮重测全部底层组件。
- backend固定10.186.133.66:5001/eap-backend@sha256:08518654fee23e764e4f08eeec1f1a29dd6448b692dd82bbfd71504f8429a552，实际PodUID2f5f6bcd-cc5d-4ee5-bfd8-f8663c4bc64e，MySQLFlyway65/健康/imageID/能力PASS。frontend保持af7eb74c853a1ed2c9ad93f5ffc6af321c67a019843f622d13d97d658cb643ff与04:20:30.939Z构建。认证原Pod、不可变配送Secret/默认900秒、环境、原生镜像和29原发布保持。仅backend镜像定向CAS及清单1digest同步，无整份apply或认证重启。
- 三个新IMAGE零模型夹具。最初Agent55延迟25秒实际处于集群900000ms读超时内，原成功/1效果保留。Agent56原故障脚本被single-query分号校验在DB调用前拒绝，同原CAS恢复时因原已成功0行，原成功/1效果也保留，不重放/清零/伪称UNKNOWN。Agent57原调用期间同进程按精确原run/owner/epoch/noOperation CAS成UNKNOWN/epoch2/ownerNULL，原预算摘要不变。实际Actor随后持原O到达存储，被REJECTED/WORKFLOW_OWNER_CHANGED/TOOL_STORE_NO_COMMIT；queryNO_COMMIT、counter0/effects0、rootcap1/used0/res0，原UNKNOWN只有start/counter两步无END。这是隔离责任失效注入，不是自然超时、控制器崩溃或已实现取消API。
- 原合法对照Agent55的首次negative probe401来自900秒短lease自然到期，不计屏障PASS；沿原24h批准续短lease，generation/身份截止/工具授权/额度保持且无业务执行。随后合法与迟到两个主夹具各newO409/clientEpoch400，原操作数/效果/预算不变。四角色API读取三个原查询一致；附加Agent56合法对照没有再做machine探针，明确notPerformed，不能造PASS。
- 四角色1500/390px合法及拒绝场景共16通过截图，run141810b4-1691-425e-8822-ac58961139ea，浏览器写入0/页面异常0/失败API0。首轮viewer负向界面通过，阳性断言误期望“成功”，实际已有UI“已确认完成”；只修辅助期望并独立重跑，原3诊断截图/report保留。原O、预算、NO_COMMIT/CONFIRMED、UNKNOWN/已确认完成及2/3步骤可读。三个原UID最终SUSPENDED、根/身份REVOKED、策略停用，原查询保持，2次合法消费不退款。
- 原68账户/41预占/258关联/41结算逐行保持，唯一旧平台TOOL_CLAIMS使用+2及更新时间；新11账户、2TOOL预占结算/6关联。原28工作流快照/步骤/理由/owner/epoch与1T6-B原查询完整元数据、13任务/29部署、9授权/18工具操作/8计数/21连接/60UID绑定保持；新workflow/deployment/query/root/O/counter/connection/UID各3。旧UNKNOWN12/20/28不重放，新31UNKNOWN。模型观测60与账本MODEL_CALLS33/OUTPUT_TOKENS1904分别保持，新增模型0。
- 本轮旧消费者完整盘点冻结60模板/63管理项/29部署、22旧发布/39旧凭证引用模板/9当前无管理引用模板；不证明外部消费者、挂载、快照或上游分页完整性，共享凭证未退役。
- 证据K8Substrate artifacts/eap-workflow-tool-effect-fence-20261007/index.html，tar.gz2814881bytes/SHA256e7f40a5726748ad6c06afd71e421612a0eca6a650c82163878ef26820495a268。286摘要/699本地源及全部HTML引用核对；16通过和3失败PNG分别计数。实际Secret及所有新实例初始/最终/续期派生token只节点RAM扫描。真实闭环及导出后才精确7文件推进/root/eap-src至699；原基线/归档保留。5测试/构建Jobs和6Pods按冻结UID清理、剩余0且保护PASS；最终运行与清理单列补充，不改原归档。
- 验收辅助失败均保留：首classifier detailDTO无name，预检第三classifier无ownerEpoch在执行前修正；短凭证401、fault reason分号、未READY只读预检、浏览器已有状态label误用、history附加对照缺negativeProbes键在报告构造时失败。最后history重跑全原行PASS；不把辅助失败归为应用漏洞或首次全绿。隔离Runner首v1允许性预检发现EAP_保留前缀、未发布，v2使用ACCEPTANCE_EFFECT_DELAY_SECONDS，两轮普通Go/build记录保留；它只延迟，不是T6-B EOF机制。
- 仍待真实业务工具适配/多步骤资源交接、新epoch自动恢复、取消/补偿、混合版本生产升级/HA；调度公平/老化/阶段画像、独立更大Agent调优收益、旧凭证迁移、T7/T8持续主线。全部编译/依赖/测试/构建/部署/浏览器远程，本机只文本/摘要/脱敏证据和看图；旧视频未重录。

用户此前“别忘记提交gitee”持久授权本工程正常commit/push。仅7源码和6工程文档，PROJECT_MEMORY提交HEAD加本条目，保护其他线程记忆、BP、AGENTS、研究和私人原文，不强推。已闭环不得重跑原publish/workflow/fault/effect或换原意图造证据。

人工审图发现初始合法手机badge压缩；独立只读稳定复检run8bfe9060-e3d2-414a-b970-93d16737ef9d，等待700ms后完整标签/宽度/opacity1/无transform/无动画/不裁切PASS。首帧未记录动画状态，动画时机为推断。4张稳定补拍和DOM报告单列在artifacts/eap-workflow-tool-effect-fence-20261007-visual/index.html，原归档不改、应用源不改；实际9Secrets节点RAM扫描通过。初下载SSH255后经批准桥接重试，原sha/bytes保持。

负向Agent57原UID ea9d2910-d3b5-41d9-b1e1-ef8f1438b784，run wf-exec-7893638b-19e5-421b-b09e-c45bf0505b30，root 3a5ef7fd-f252-4c02-bffa-69269ab68ab4，O 17015236-c0d5-440f-8b8c-ae5adddea49e。合法Agent55原run wf-exec-b7e94916-38e2-408d-9519-b72333c2a92a，原effect 24886c97-e3c6-47fd-af33-e3ff4ebe9f14。

### Codex 进展：申请人公平调度、自然老化与责任画像已部署验收（2026-10-07，T2/R4）

始终聚焦调度、Agent性能调优、权限治理；主线未全部完成。合同[WORKFLOW_SCHEDULING.md](WORKFLOW_SCHEDULING.md)。V66仅新表，新提交按完整认证主体等权轮转、默认60秒最旧等待老化；持久序号、同事务原owner/epoch，context tenant/priority不授权。历史31运行/4原查询/3效果根不回填。单进程非抢占，不称租户/CPU配额或全入口调度。

PREPARATION/TASK_WAIT/RECLAIM/ASYNC_WAIT记录实际边界，原运行与阶段自身owner/epoch/期限双校验。开放阶段失去责任显示UNCONFIRMED且无伪造duration；缺失/时钟倒退不补零，异步整体等待不叫模型时延。只读API复用中央VIEW_CONSOLE；导航“调度与画像”，样本覆盖/P50/P95、申请人分布、近期原运行和阶段。

最终远程定向139项/9类、完整582项/93类及打包零失败/错误/跳过；前端207项/类型/生产构建。19精确源码、706摘要与实际9Secret RAM扫描PASS。v1/v2定向绿但完整被后续加固替代，partial保留/UID退役；v4完整582中2失败1错误保留。根因是旧三参数mock以及删除H2运行与新审计FK冲突留下QUEUED；仅修正两个测试，四参数mock执行Prepared屏障，原新H2夹具按runKey/QUEUED CAS终止并保留审计，不放松生产FK/权限或虚称取消API。v5完整闭环才发布。v2启动辅助错误manifest路径在创建源前失败，fixed独立入口保留；v3未启动Job。 部署只读核验v1误用MAX(varchar version)，保留失败记录；v2按installed_rank读实际66，无重复部署。Runner/原生代码镜像未变，本轮不声称Go重测。

新agent模型额度0：一个原ECHO异步工作流及随后14A/2B控制节点，共17个原意图全部成功。B在A突发队列耗尽前实际获轮转，旧等待自然达到阈值并记WAIT_AGING，未改worker/队列时钟/生产策略。原ECHO同UID任务回执、物理休眠和ASYNC_WAIT画像确认；准备/HTTP等待/回收边界为远程HTTP/DB/client回归，不称三种原生阶段均完成本轮实测或FIFO goodput对照。四角色API和1500/390px只读浏览器12截图，写入0/页面异常0/失败API0，最终仅新ECHO同UID休眠及专属身份撤销。

原79账户/43预占/264关联/43结算、31工作流/4查询/3效果根、12授权/21操作/11计数、24连接/32部署/13异步任务/60模型观测全行SHA保持；模型与工具消费新增0，不退款/清零/重放。认证原Pod、不可变配送Secret/默认短lease、后端环境、原生与Runner镜像保持。仅前后端镜像resourceVersion CAS，实际MySQL66/健康/imageID确认。

固定镜像backend=10.186.133.66:5001/eap-backend@sha256:aa4e90d2e6cd14717ae1ab400aefcd3ef872e81753da971ffe0b2ff823599315；frontend=10.186.133.66:5001/eap-frontend@sha256:e9a713bcc3d1be72e9c7776d4fd7594d1e1222055aa0d766cc56d13c125ba6cc；buildId=2026-10-07T10:12:16.754Z。

证据K8Substrate artifacts/eap-workflow-scheduling-20261007/index.html，归档2989896bytes/SHA25679b3672f19c2445a031d5d2eba75accb28e4c0d38a0ea5050cf0f9d39cf8f963；671文件摘要/706源/12截图/HTML引用核对。实际Secret与新实例派生token只节点RAM扫描，通过后才精确19源码推进/root/eap-src。5现存临时Jobs/6Pods按冻结UID清理、剩余0，另两被替代测试Job已UID退役；原失败/partial归档不改。

用户此前“别忘记提交gitee”授权本工程正常commit/push；仅19源码和4工程文档，PROJECT_MEMORY index=HEAD加本条，保留其他线程记忆、AGENTS/BP/私有报告和研究。下一项T3更大独立公开样本/持久留出批次/完整成本与真实收益门禁，旧GEPA保持KEEP_BASELINE；继续可信租户/资源配额、跨入口调度、业务工具/旧凭证迁移、恢复/取消/补偿及T7/T8。主线未全完成，旧视频未重录。


### Codex 进展：持久留出批次、完整调优成本与回执修复（2026-10-08，T3-B）

主线继续聚焦调度、Agent性能调优、权限治理，尚未全部完成。本轮工程代码与部署通过；真实完整留出评测未完成，没有稳定生产Agent收益证据。合同[PROMPT_OPTIMIZATION_BATCHES.md](PROMPT_OPTIMIZATION_BATCHES.md)。

V67仅新增协议登记及持久留出批次表，不回填旧根。新登记训练/验证各最多3条、留出最多10条；完整认证主体参与幂等摘要，展示截断不改变身份。候选封存和搜索claim失效同事务登记每批最多3条，10条对应人工/候选共8逻辑批次；原操作键和评价关联持久化，同提示词复用原评价，根成本按实际预占去重。UNKNOWN或缺usage停止，不更换意图重发。四配对卡片保留全部登记分母和未执行/失败/未知。搜索、反思、人工验证、留出成本全部计入；usage/时延缺失不补零，无价格版本显示UNPRICED。

完整计划先检查原共享/Agent容量，每次实际调用继续实时预占。3/3/10、8次样本评价、128输出上限的最坏计划64次/8960输出Token（反思最多2次、各512），不是8192。GEPA固定0.1.4，reflection_minibatch_size=1，依赖未更新。至少10个登记留出组且全部回执/usage满足原质量门禁时也仅给REVIEW_*及INDEPENDENT_COHORT_REVIEW_REQUIRED，不自动采用；分组隔离不证明语义/统计独立，公开合成格式任务不能外推业务收益。

实际只提交新Agent59的原公开任务A一次：root3、experiment16在首次人工验证trial35成为UNKNOWN/INVALID_RECEIPT，后5试验取消。provider消费已知且SETTLED：1次、58输入/128输出（reasoning128），预留0；业务回执未知不等于费用未知。10条留出均未运行，chosen未封存、批次计划0；预登记B未提交，不重放、不退款、不增旧额度。原响应全文未保存，空最终文本是usage支持的推断，不能称原根因已完全确认。原UNKNOWN在优化与实验准入的全局门禁中继续阻止新任务；需原回执核对/明确治理合同，不能新建Agent或新键绕过。

独立红测确认执行器有效空文本因omitempty被省略，缺失/null供应商内容也未正确区分。修复始终返回文本output、只接受实际字符串，缺失/null/对象/数组保持未知；有效空文本可被原评分器判0或截断，不伪造质量通过。6个子场景原4失败/2通过保留；相同意图再次读取只访问一次假供应商。修复普通及-race均24顶层/43含子项通过、静态编译0，仅将未来发布默认Runner切换新固定digest；旧Actor/template/root3的原511镜像与身份周期不变，新模型执行未验收。

远程后端定向106项/5类、完整591项/93类零失败/错误/跳过，打包通过；前端213项及类型/生产构建通过；真实GEPA0.1.4 Worker24项通过。后续2执行器文件之外的backend/frontend/optimizer全源摘要与v2相同，沿原证据不无谓重测。708源/14精确改动与实际9Secret节点RAM扫描通过。初Worker写日志权限失败发生在测试前，v2b相同源码修正独立输出目录后通过；初浏览器/Runner依赖安装卡住，不计应用测试通过，新的有界下载Pod完成浏览器/竞态；旧Runner Job按冻结UID退役。辅助包等待门禁、错误表/字段与上传时序的早期失败保留，均在对应发布写入前停止。

四角色API及1500/390px只读浏览器14截图通过，写入0、页面异常0、失败API0；普通角色不能读正文或运行实验，管理员表单可见。截图聚焦本轮公开任务，不导出无关私有反馈。Agent59原UID8a0463e3-5477-40f3-9861-9a6539832022已物理SUSPENDED、专属身份REVOKED；原UNKNOWN/已付费用保持。

实际MySQL67与健康UP，固定backend=10.186.133.66:5001/eap-backend@sha256:45fcad1e2b2e511dfb47bff2a87a8bd13bec1df85f0abe9bb0e5cf4992060712；frontend=10.186.133.66:5001/eap-frontend@sha256:855171df3893b51cdf8f5b3a0f06e56f0387814699861eebc34ac6c98263bd4d；optimizer=10.186.133.66:5001/eap-prompt-optimizer@sha256:35a8bb20a051d1321c22f3563747aa14ddd5fb7c9f42416806e2c3857609776d；未来Runner默认=10.186.133.66:5001/eap-runner@sha256:3e4570444af8bbed03aef1f9007905dc89a109f090402c229c6504aff36673a3；前端buildId2026-10-07T14:25:10.580Z。只CAS三个应用镜像和一个公开未来默认字段，其余环境/认证原Pod/不可变配送Secret/默认900秒/原生与异步Runner镜像不变。

原58Agents/48工作流/4查询/3效果根、43预占/264关联/43结算、2旧优化根/15旧实验和其他旧行SHA保持；旧非平台账户/共享cap/revision不变。旧平台MODEL33→34、OUTPUT1904→2032严格只增加原1次消费，TOOL10不变。证据K8Substrate artifacts/eap-optimization-batches-20261007/index.html；归档2463113bytes/SHA25646ae84607fd9998451a828943cf258a64671c4980287d8cb71974f77c1426d0a，285文件摘要/708源/14截图。导出明确STOPPED_ON_UNKNOWN/fullLiveHoldoutCompleted=false；工程保护PASS不冒称真实完整评测PASS。通过RAM扫描后精确14源同步/root/eap-src。

下一步优先原UNKNOWN回执核对与可审计治理，之后才能继续独立真实任务完整成本和复验。持续保留可信租户/资源配额、跨入口调度、业务工具及旧凭证迁移、恢复/取消/补偿、T7分级观测和T8联合策略。研究/申请书只作方法依据，私有正文不自动用于反思；旧演示视频未重录。沿用户“别忘记提交gitee”授权只提交14源及5工程文档，PROJECT_MEMORY索引只包含原HEAD和本条，保护其他线程的未提交记忆与材料。

### Codex 进展：原未知回执审计治理已部署与验收（2026-10-08，V68）

持续聚焦调度、Agent性能调优、权限治理，主线尚未全部完成。本阶段工程部署、只读真实验收及保护证据通过；不把审计停止决定当作原业务执行成功或真实调优收益。合同 docs/OPTIMIZATION_UNKNOWN_REVIEW.md。

- V68 仅新增 agent_optimization_unknown_reviews。中央 PlatformPermission 新增 reviewExperimentUnknowns，治理/平台管理员可登记 STOP_NO_REPLAY，普通角色只能读取元数据；前端只按 /me 能力。完整主体与固定提交键参与幂等，原正文/claim/凭证不进审计。原登记合同、失效 claim、原 UID/模板与撤销身份、全部停止子意图、完整 SETTLED 费用/usage及根预算均重新核对后才接受明确决定。证据变化重新阻断；历史/独立 UNKNOWN 仍阻断，旧实例不能用于新实验。
- 远程后端专项120项、完整605项/93类零失败/错误/跳过及打包通过；前端220项/类型/生产构建通过。702源摘要与实际9 Secret原文/编码节点RAM扫描PASS。Runner和optimizer未改，本轮不重复声称底层测试。
- 仅 CAS 前后端两镜像。backend=10.186.133.66:5001/eap-backend@sha256:f6d5a097afe4f122837f46e4b4a89fa60df94e4bef04b01ff34e81522253cb61，PodUID a1f55cde-1ffd-483d-8b8e-45d79a2c159a；frontend=10.186.133.66:5001/eap-frontend@sha256:d4827bfd9884b3faecbfd77b27fb0dfb800e6d7a62dc026910b491fd73f14739，PodUID 6c31f155-2fce-4e3c-8a96-11cf416f75b5。实际MySQL68/健康UP/imageID通过，buildId=2026-10-08T02:17:07.890Z；其他环境、optimizer/Runner、认证原Pod、不可变配送Secret和短lease不变。
- 四角色真实API通过；无ack治理请求400，普通角色403，原root3仍UNKNOWN/INVALID_RECEIPT，子16/未知1/未运行5，原费用1次及58输入/128输出保持。四角色1500/390px只读浏览器8截图，业务写入0/页面异常0/失败API0。已查看管理员手机及治理桌面，原费用、分母和明确停止说明可读，无横裁。
- 部署前后24张历史表全行摘要及认证/环境保护完全一致；本阶段新增模型与工具消费0。证据 docs/evidence/eap-unknown-review-20261008/index.html，同名tar.gz980408bytes/SHA2568021fb84ae596609bcea8698c6fae625daf1e4da952e13796433e0c98ac55350；本地17项摘要均核对。归档时原停止决定尚未登记，后续真实决定与独立任务另存，原归档不覆盖。导出及扫描通过后才推进远程702源码镜像。
- 辅助失败如实保留：旧最终状态脚本断言、首包Mac元数据预检拒绝、初保护读取二进制解码失败、后端构建未完成时发布门禁拒绝，均未产生对应业务/发布写入。初就绪检查在新Pod未Ready时停止，无重复发布；首浏览器隐藏input点击超时，改点可见label后独立重跑通过，应用源码未改。误查 eap-poc 命名空间 NotFound 后按实际 eap 查询。
- 下一步在已部署合同下登记原意图永久停止、保留全部原事实，再开展另行预登记的独立公开任务。原A不重放，原B保持未提交；不清预算、退款、提高原额度或改旧实例。仍需可信租户/资源配额、跨入口调度、真实工具/旧消费者迁移、恢复/取消/补偿及T7/T8。用户当前指示继续主线，不自行git commit/push，保护AGENTS/BP/研究与其他线程未提交记忆。全部测试/依赖/构建/部署/浏览器远程，本机仅文本/摘要/脱敏证据。

### Codex 进展：原UNKNOWN停止已审计；独立C真实评测停止并保护费用（2026-10-08，T3-C）

主线持续为调度、Agent性能调优、权限治理，尚未全部完成。V68完成工程与真实权限验收后，按用户继续主线的指示，在治理身份下登记root3唯一STOP_NO_REPLAY/ORIGINAL_RECEIPT_UNAVAILABLE，固定键与完整主体幂等核对通过。原root3/experiment16仍UNKNOWN、原58输入/128输出及1次SETTLED保持，旧UID/模板/预算和原B未提交保持。原审计证据仍一致，不复活claim或原Trace，不退款或重放。

- 两组新公开合成格式任务C/D预登记各2训练/2验证/10留出，GEPA固定0.1.4、8次metric评价、每次128输出，单根最坏62次/8704输出，总新增上限124次/17408输出。加原1次/128输出仍低于此前128次/32768输出计划；不扩大旧Agent59/root3额度或改共享cap/revision。计划docs/evidence/eap-optimization-live-20261008/plan.json。
- 仅创建新Agent60/部署v1，专属UID b90aee65-d4c4-453b-a014-283f3d5d0760，模板eap-agents/a60-v1-9a08b6019564，修复后Runner3e4570…固定摘要，工具禁用/无共享bearer/自动专属配送。只提交C原root4一次；D未提交。搜索及人工验证完成，候选封存；实际留出中experiment29首试验89在执行前配置检查FAILED/RUNTIME_NOT_READY，后5条CANCELLED，最后选定臂批次未运行。root4保持FAILED/KEEP_BASELINE，不改成完成或新键补发。
- C全流程50次全部SETTLED/usage已知，3642输入/3567输出，无预留、未知费用或工具新增。搜索12次、反思2次、人工验证4次、留出32次；实际登记到停止164秒，金额仍UNPRICED。人工留出10/10完成、精确匹配10/10；该臂基线7/10。搜索选定臂仅完成6/10，原10条分母、失败和未运行保留；整体不确认收益，不排除未完成臂或外推生产效果。8个真实有效空文本回执已正确保存SUCCEEDED/length/score0，不再因缺失output变UNKNOWN；没有把空输出判通过。
- 原24表889行逐项SHA保持，仅平台used及更新时间允许严格新消费增量；旧cap/revision/reserved不变。平台MODEL34→84、OUTPUT2032→5599，TOOL不变。含原T3-B消费共51次、3695输出，未清零/退款。新UID最终物理SUSPENDED、身份REVOKED；原root3审计仍Current。
- 四角色真实API、原FAILED相同键查询、异参409、正文200/403与执行403通过，原UNKNOWN元数据保持；1500/390px16截图、业务写入0/页面异常0/失败API0。已查看管理员手机和原决定治理桌面，原分母/费用/不可覆盖决定均可读。
- 排障已排除心跳和凭证到期：原登记SUBSTRATE/ONLINE、未被清理，停止时连接READY、批准/短lease未到期，原生同UID RUNNING；再次只读配置查询200/同digest，未新增模型。原失败前配置检查239ms，现有代码将所有底层RuntimeException统记RUNTIME_NOT_READY，原日志缺失阶段证据；路由/API/worker在原窄时间窗无可核对记录。不能断言原瞬时失败根因，也不能凭假设修改准入/超时/重试。首次辅助SQL因shell引用失败，无写入；改用文件化查询后完成。
- 证据docs/evidence/eap-optimization-live-20261008/index.html，同名tar.gz2173786bytes/SHA256e7d45d1b23e71a379953e5d5dd5fec9f1d3d4159ff66e29152c392ee49632b52；实际9Secret节点RAM扫描、本地27摘要及16截图通过。明确STOPPED_WITH_ORIGINAL_FAILURE/fullLiveHoldoutCompleted=false，工程保护PASS不等于完整调优PASS。首次V68归档不覆盖。
- 为继续调优故障定位，使用investigate流程确认“准备诊断丢失”：远程独立红测8项/7失败/0错误，HTTP401/503、原生恢复UNAVAILABLE/RESOURCE_EXHAUSTED、原生查询DEADLINE_EXCEEDED、任意错误码和坏配置被原实现统记泛化码；成功用例保持1次派发，失败用例无模型派发。修复限定AgentExperimentClient和新回归两个文件，白名单阶段/控制码与HTTP状态，不保留异常正文/响应正文/凭证，不改变调用次数、权限、预算、超时、身份核对或重试。远程完整回归进行中，尚未部署诊断新镜像，不能称原瞬时失败已修复。

当前节点/root/eap-t3c-evidence.json completed=true，closed=true；禁止重跑prepare/submit C或以poll恢复原FAILED。D按原预登记门禁未提交。后续先完成诊断修复回归/发布/零模型验收；若再做真实任务，需重新明确独立数据与剩余额度，不能当作原C重试。已消费51次占此前总计划，不能再次自动分配完整128次。可信租户/资源配额、跨入口调度、业务工具/旧消费者迁移、恢复/取消/补偿及T7/T8继续待办；本轮不commit/push，全部测试构建部署浏览器远程，BP/研究/其他线程记忆保持。

### Codex 进展：实验准备诊断已远程回归、发布与保护验收（2026-10-08，T3-C 后续）

仍在调度、Agent性能调优、权限治理主线；主线未全部完成。本阶段修复可复现的准备诊断信息丢失，原C瞬时RUNTIME_NOT_READY的底层原因仍UNCONFIRMED，未修改原失败或重跑原C/D。investigate流程下先远程红测8项/7失败，再限定AgentExperimentClient和新AgentExperimentReadinessTests两个源码文件改动。只保存固定白名单的身份/恢复/查询阶段控制码及配置HTTP状态、坏配置或UNCONFIRMED，不保存异常/响应正文/凭证，不增加重试、模型派发或放宽身份、预算、超时。

- 最终远程专项128项/6类、完整613项/94类、打包通过，零失败/错误/跳过；8项新回归全部通过，失败准备均0模型派发，成功保持单次派发并接受有效空文本。前端源码与镜像未变，沿V68的220项/类型/构建与C真实四角色16截图；本阶段未重复浏览器、Runner/optimizer或真实模型评测。
- 703源摘要、本地与候选逐项一致及实际9Secret节点RAM原文/编码扫描PASS。仅后端镜像resourceVersion CAS为10.186.133.66:5001/eap-backend@sha256:8de8b9d010773438981925de00f6b4ed98c5598636cc8088bcb1c92e30b79414，实际PodUID8ca103e2-a9a8-481f-afb2-28cf80bab238、imageID/健康UP/MySQL68通过。前端d4827b…、其他环境、optimizer/Runner、认证原Pod及不可变配送Secret保持。
- 四角色真实元数据/正文200或403、中央/me能力、原UNKNOWN决定Current及撤销UID配置请求403/管理员400通过；新Agent60原UID仍SUSPENDED/REVOKED。发布/验收前后25表全部原行摘要、账本/费用与审计完全一致，模型和工具新增0。原root3仍UNKNOWN及唯一STOP_NO_REPLAY，root4仍FAILED/RUNTIME_NOT_READY/KEEP_BASELINE、C50次全部SETTLED，D未提交。没有完整双组留出或稳定收益结论。
- 证据docs/evidence/eap-experiment-readiness-20261008/index.html，归档58402bytes/SHA25659701ec75f8f7f636487d9f583743cbb1fbbd096ebbe6f7e45517917b75d8bc2，16文件/15文件摘要本地核对。红测XML、绿色XML、完整源码清单、两源及发布/原25表/API保护均保留。扫描/归档通过后才精确推进远程/root/eap-src两文件，全部703摘要再次一致；原V68/C归档不覆盖。
- 辅助失败如实保留：初API四角色检查完成后，子进程保护读取与父进程审计锁冲突而停止；改为同进程快照后通过，未改应用源码。API证明尚未生成时过早导出被门禁拒绝，未创建归档或同步源码。跳板SSH间歇断开/认证辅助失败由n1原路径重试，最终退出与归档摘要通过；不称连接问题已完全修复。临时日志已实际Secret内存扫描并保存，按冻结UID仅清理本轮7测试/构建Job与8Pod，最终保护结果另列补充。

下一阶段真实独立任务须先冻结数据/身份与原计划剩余最多77次调用、29073输出Token，并与实际共享/实例预算取更严格值，不能扩旧额度、重放C或绕过D原门禁。本改进只帮助后续自然失败定位，不证明原可用性问题已修复；可信租户/资源配额、跨入口调度、业务工具与旧消费者迁移、恢复/取消/补偿、T7/T8仍待。未commit/push，未修改AGENTS/BP/研究或其他线程已有记忆；全部测试、构建、依赖、部署与浏览器均远程。

收尾已确认：本轮7个临时Job和8Pod全部清理、剩余0；删除携带冻结UID前置条件，日志已扫描并保存。最后25表/费用/认证/环境保护PASS、模型/工具新增0，原58402bytes归档SHA保持。最终补充证明单列docs/evidence/eap-experiment-readiness-20261008-final.json，远程/root/eap-readiness-out-v1/final-proof.json；不要重跑旧构建/评测入口或覆盖原归档。

### Codex 进展：完整事件分级调优、独立对照及凭证读取修复已闭环（2026-10-08，T3-E）

继续调度、Agent性能调优、权限治理主线；本阶段完成完整公开任务评测与独立对照，不等于全主线或生产收益已完成。原root3仍UNKNOWN、唯一STOP_NO_REPLAY决定Current；原C/root4仍FAILED/RUNTIME_NOT_READY/KEEP_BASELINE、旧D未提交，旧身份/UID/模板及费用不改、不重放。

- 新计划在查看结果前冻结中性基线、人工提示词和22条公开虚构事件；SHA256 `5b9c501bc0771560db30c2ad4e8d3d20597a12f367790582f8325b8850ae01a3`。1训练/1验证/10留出、GEPA0.1.4、metric上限4、每次输出256，根最坏52次/13824输出；另一批10条人工提示词独立对照20次/5120输出，总新增上限72次/18944输出，含此前51次/3695输出不超过原128次/32768输出，不改旧/共享cap或revision。
- 仅新Agent61/实例`eap-agents/a61-v1-f4d38a83dc09`、专属UID`20e48478-5c79-4c66-8cda-99783235b26b`，Runner3e4570…固定摘要，工具禁用、无共享bearer。原root5一次提交，COMPLETED/KEEP_BASELINE/NO_CONFIRMED_HOLDOUT_BENEFIT；两个留出对照共8个持久批次全部完成，基线/人工/选定均10/10。根全流程50次全部SETTLED、10218输入/5239输出，搜索6/反思2/人工验证2/留出40，usage和时延完整。原优化结果没有自动采用。
- 另一批实验42–45全部COMPLETED，20个配对回执/供应商usage完整，基线10/10、人工9/10；trial154为SUCCEEDED/length/OUTPUT_TRUNCATED、输出256、质量计0，保留原分母与费用，不加输出上限补跑。该对照不支持采用人工候选，更不能证明真实SRE或稳定生产收益；分组隔离也不证明统计独立。
- 第二批首次准入HTTP400没有生成实验或额外预占；首响应正文未保存，后续只查询原键，没有换键或补跑业务失败。当前同UID自然过期时配置同样400、模型仍56；调查确认调用方事务先加载连接，独立续期后再查询得到旧JPA对象。四项远程红测覆盖只读/写事务×已过期/临近到期：旧实现2错误/2失败，前者拒绝旧租约，后者返回与实例安装值不同的凭证。按investigate流程只改`RuntimeConnectionService`与`RuntimeCredentialDeliveryTests`两个源码文件；派发凭证读取使用NOT_SUPPORTED暂停调用方持久化上下文/数据库快照，不改身份、权限、预算、批准期限、超时或模型重试，不清全局EntityManager。
- 修复远程定向103项/6类、完整617项/94类及打包通过，零失败/错误/跳过；703源码摘要和实际9Secret节点内存原文/编码扫描通过。仅后端resourceVersion CAS至`10.186.133.66:5001/eap-backend@sha256:1bf217de24924e01197c9c6d80df0e2d5635e02faff1971035ed008eb5a71588`，实际PodUID`05d5e2ac-3006-42db-9cb7-6f79de6c9e71`、imageID/健康UP/MySQL68通过。前端d4827b…、optimizer、Runner、原生服务及认证/配送Secret/默认900秒保持。
- 真实MySQL同UID自然EXPIRED→READY，配置200、旧客体凭证401/当前200，serial3→4，身份epoch与原批准期限保持，验证前后模型都是原56次。凭证只在节点进程内存使用。此后核对原第二批无实验/额外预占且参数摘要不变，仅一次恢复原键/全部参数的准入为experiment43，首400和恢复标记持久保留；不是持久化FAILED/UNKNOWN重放。原C瞬时失败仍缺底层证据，不能倒推为本次根因。
- 本轮全流程70次、14336输入/7310输出全部SETTLED、预留0、工具新增0；含此前T3消费共121次/11005输出，未退款或重置。25表1541旧行SHA保持，平台used/time严格仅增加实际消费，旧cap/revision保持。新UID最终SUSPENDED、身份REVOKED。四角色元数据/正文、中央/me能力、关闭实例拒绝、原键返回、改参数409通过，权限复验模型/工具新增0。前端源码和镜像未变，沿原220项/类型/构建及四角色16截图；本阶段未重复浏览器、Runner/optimizer构建，不把沿用证据说成本次重跑。
- 辅助核验首次错用`budget_reservations.task_key=optimization:5`，实际该字段NULL。改为预占关联项→TASK/optimization:5/MODEL_CALLS，按唯一预占去重，50次供应商汇总一致；未改业务账本、回执或结果，原v1与失败史保留。首探针查询错误字段的SAFE_METADATA_QUERY_FAILED也保留。节点原计划、原键及helper版本均不覆盖历史归档。
- 证据[事件分级实测索引](evidence/eap-incident-optimization-20261008/index.html)，归档313740bytes/SHA256 `5f75cb72b7eedc7c2a57220bf9eab63fce6014d37eac0cafe0e25e37e3135075`，22文件/21文件摘要本地通过，完整测试类计数再次核对617。扫描/导出后仅精确两源推进`/root/eap-src`，703摘要一致。3个临时Job/3个Pod日志扫描保存、按冻结UID删除，剩余0；清理后全部最终业务行/费用/身份保护PASS，原归档不覆盖，最终补充回执单列。

节点`/root/eap-t3e-evidence.json` completed=true/closed=true；禁止重跑prepare/submit/recover_admission/corroborate或复活原UID。下一步先补T7实验入口Agent Span与观测完整性，再继续可信租户/资源配额、跨入口调度、真实业务工具适配/旧消费者迁移、T6取消/补偿与T8策略。业务工具名称/接口路径已向用户询问，尚无回复，不阻塞独立观测工作。原T3总计划最多剩7次/21763输出，仅是差额，新实测仍需独立预登记和更严格实时预算，不刷新128次额度。未commit/push，未改AGENTS、BP、研究或其他线程已有记忆；所有依赖、编译、测试、构建、部署和浏览器均远程，本机仅文本与摘要/脱敏证据操作。

最终补充回执已保存并本地核对：`docs/evidence/eap-incident-optimization-20261008-final.json`、节点`/root/eap-t3e-out/final-completion.json`，outcome=PASS。完整留出/独立对照、70次结算、617项回归、部署、同UID自然续期及3Job/3Pod清理门禁全部通过，fullMainlineCompleted=false、productionBenefitEstablished=false、gitCommitOrPush=false；源码与归档内两份副本摘要一致，原313740bytes归档保持。

### Codex 进展：T7实验观测已部署实测，清理与证据接收待恢复连接（2026-10-08）

继续调度、Agent性能调优、权限治理主线；本轮完成实验入口的一项观测能力，不等于全入口风险分级观测或全主线完成。合同`docs/EXPERIMENT_TELEMETRY.md`。精确8源：Runner在首次新执行的业务回执落盘后，以实例身份向traces入口发送一次固定元数据Agent Span，超时2秒、不跟随跳转、不重试、不记录原始错误；旧回执/UNKNOWN意图不重跑模型或重发Span。原模型latency不包含导出耗时。只读完整性接口按Agent、实例键、端点、冻结Actor UID/模板匹配，关联原Trace；不续期、不补写。身份不能确认的计数为null，全原试验分母保持；UNKNOWN/FAILED不因Span存在而变成功。权限复用PlatformPermission.VIEW_CONSOLE，正文权限保持，前端不解析JWT或按角色名授权。

首次工程回归632/95类和Runner53项全绿，但真实实验46发现完整性查询错误地只看OTLP MODEL行；两个Agent Span和成功的GATEWAY调用事件都已存在，错误测试夹具也沿用了错误数据源。保存首次MISSING_MODEL与2次真实费用；实际网关夹具对旧查询红测1失败/0错误。修正只读查询只统计同身份、同Trace的GATEWAY事件及成功状态，拒绝OTLP替代、失败模型事件和跨归属，保留重复计数。最终远程专项113/6类、完整634/95类及打包零失败/错误/跳过；Runner源码与第一次绿色回归逐项相同，沿普通/-race27顶层53含子项及编译，不冒称修正后再构建Runner。前端未改，沿原220/类型/构建，本轮没有浏览器复验。707源码摘要和实际9Secret节点内存原文/编码扫描通过。

最终后端仅镜像CAS至`10.186.133.66:5001/eap-backend@sha256:7275f8e98ba515d1867b2faf79f4f52e3a85ac3a9ad8a8e3379153861fab9008`，实际PodUID`cdec2257-4b90-4f6b-9e6a-39066a9c7a81`、imageID/健康UP/MySQL68通过。首阶段只改后端镜像与一个未来Runner默认字段，初版后端19ab698…保留；未来Runner固定`10.186.133.66:5001/eap-runner@sha256:6313d9e1a2630b9c44d5aa67dc8f3005e585d00627992a38341bb803aa1d3420`，旧UID/模板、其他环境、认证原Pod和不可变配送Secret保持。精确8源已同步`/root/eap-src`，707摘要一致；不commit/push，不改AGENTS/BP/研究/其他线程已有记忆。

新公开固定样例预登记2次/256输出上限，仅Agent62/`eap-agents/a62-v1-0101684d9c78`、UID`4c75d4ae-bab4-4c5f-bb54-31c51f961ee3`，original experiment46一次提交。实际两个原Trace均COMPLETE，Agent Span2、GATEWAY模型事件2、OTLP Model Span0；110输入/110输出、2次全部SETTLED、预留0、工具0。数据源修正与复核没有新增模型或Span，三个原表的这两行全字段SHA保持。四角色元数据/内容200或403、中央/me能力、平台同键返回原记录/改参409及直接Runner缓存回执200通过，额外消费0/Span0。新UID最终SUSPENDED/REVOKED，撤销后历史覆盖保持。27表2609旧行、旧/共享cap和revision、旧原生UID/模板状态保持；原experiment45仍MISSING_AGENT，不补造旧观测；原root3 UNKNOWN唯一STOP_NO_REPLAY审计Current、root4 FAILED及root5 COMPLETED/KEEP_BASELINE保持。含此前消费共123次/11115输出，未退款或重置128次总计划，没有生产调优收益证据。

当前节点`/root/eap-t7-out-v2/live-state.json` completed=true/closed=true，禁止重跑freeze/prepare/submit/roles_replay或重放业务。6个本轮测试Job/6个Pod已冻结UID，实际Secret扫描日志已保存；清理与接收时跳板banner超时，delete/check与归档接收尚未确认完成，不能写成剩余0或归档已接收。仍只通过原n1.sh连接，不换通道。下一步按冻结UID继续`/root/eap-t7-close.py delete`→`check`，再`/root/eap-t7-export.py`，接收摘要核对后追加收尾记录。首次macOS元数据包、原生列表字段、超长传输、过早构建/发布核验均在相应门禁拒绝且未执行额外模型，首失败/辅助版本保留。

原计划最多剩5次/21653输出，只是计划差额；新实测仍须独立预登记和实时预算，不扩旧额度、不复活已关闭UID。之后继续T7全入口分级采样与日志指标关联、可信租户/资源配额、跨入口调度、业务工具及旧消费者迁移、T6取消/补偿、T8联合策略。业务工具名称/接口路径仍待用户信息，未再次询问，不阻塞独立工作。全部编译、测试、依赖、构建和部署远程，本机仅文本/摘要/脱敏证据操作。

### Codex 进展：IBM Control Plane 对照与T7续接检查（2026-10-08）

用户提供`demo-now.techzone.ibm.com/psl/lw5902sz`并表示相似，随后要求继续；作为原主线参考，未取消调度、Agent调优、权限治理主线。公开HTML标题确认是watsonx Orchestrate Control Plane；内嵌33步导览覆盖总览、使用情况、成本、质量、可靠性、安全风险。网页工具首次失败后公开HTML读取成功，未运行IBM平台或做浏览器点击验收。核对官方产品页、2026-07-02发布说明、开发者文章及外部Agent接入文档；文档直接打开403，索引正文可读，访问条件与厂商说明性质明确保留。

新增`docs/IBM_CONTROL_PLANE_COMPARISON.md`，结论先行记录来源强度、EAP实际代码/实测差距、接入选项、未知项与未执行的零模型/零工具副作用PoC候选计划。IBM周期任务触发不等同于公平队列/资源阶段调度；EAP当前Runner协议不能自动视为A2A兼容；云端演示不能推定私有化功能对等。研究未更改业务源码或部署配置，没有注册IBM Agent、发送私有数据或创建外部服务账号，也没有改变既有Galileo方案。更新主线状态和当前交接，保留原历史；未commit/push。

T7续接仍只使用授权K8Substrate/n1.sh。连通检查与按冻结UID的delete调用均遇banner超时、exit255；当前不能确认六Job/六Pod剩余0，未执行依赖清理证明的归档导出，未声称归档已接收。原应用、634项后端回归、53含子项Runner证据与原实验46两条COMPLETE保持；未重跑模型、增加Span或复活关闭UID。下一步仍为原通道恢复后依次delete→check→export→接收摘要核对；全部依赖、编译、测试、构建、部署和浏览器继续远程。

### Codex 进展：T7收尾恢复并完成证据接收，准备下一批观测验证（2026-10-08）

用户要求继续、询问远端连接并要求再试。原n1.sh通道在多次banner超时后恢复：连通检查返回0，随后实际delete和check都返回0。仅按原冻结UID清理6Job/6Pod、剩余0，日志已扫描保存，最终27表原行/费用/身份/旧共享额度和revision保护PASS，清理新增模型/工具效果0。保留前文断连时的真实待完成检查点；这次结果不代表网络永久修复，下载和后续上传仍出现间歇认证辅助失败及连接reset。

`/root/eap-t7-export.py`实际9Secret节点RAM扫描和保护通过后导出；原通道接收至[实验观测索引](evidence/eap-experiment-telemetry-20261008/index.html)。归档570529bytes/SHA256`016d85eca12b305b77a604078f3d41bc23bd53115db98f24887cdf97c3afe4ed`与节点一致，44文件摘要/29索引链接/8精确源码副本本地核对通过；[最终接收回执](evidence/eap-experiment-telemetry-20261008-final.json)单列，原归档保持。最终后端634项/95类、专项113/6类，Runner沿未变53普通/竞态，前端沿未变220/类型/构建，707源码保持。原实验46两条COMPLETE、2次/110输入/110输出SETTLED和旧UNKNOWN/FAILED/KEEP_BASELINE均保持；没有为修正来源或收尾补造Span/重发模型。本实验入口阶段已闭环，全主线和生产调优收益仍未完成；未commit/push。

新增[OBSERVABILITY_ROLLOUT_PLAN.md](OBSERVABILITY_ROLLOUT_PLAN.md)，按实际通用Runner、AX Runner、实例接收、内容模式和调度阶段核读，定义下一批顺序：导出边界统一→冻结版本的可选诊断采样→跨入口覆盖/日志指标/问题定位。费用、原操作/UNKNOWN、身份和工具效果不降采样；无可信持久分母的对话/旧入口不能虚称完整覆盖。方案尚未实现或验收。

独立负向准备包`/private/tmp/eap-t7b-negative`复制两个当前Runner并各加4项合成HTTP回归，覆盖错误文本、跳转、2秒导出边界及原始URL日志；未改绿色业务源码，尚未编译或执行。包39779bytes/SHA256`15f868e478cc8adc257b1ef3ceff586ad64210341484913b600d5bfa90bceec3`，23摘要核对，当前源副本逐项一致。通过原n1上传后必须独立验证节点字节/摘要再解包、RAM扫描和远程负向测试；SSH退出0不能替代上传摘要门禁。该包不是已通过的测试证据，不申请新模型消费。所有依赖、格式化、编译、测试、构建、部署和浏览器继续远程。

### Codex 进展：T7-B两个Runner导出边界已远程回归、发布与收尾（2026-10-09）

用户再次要求重试远端并继续主线。仍通过已授权n1.sh经原跳板进入PoC；首次只读核对已成功，恢复后仍有间歇reset/连接关闭和认证辅助失败，经原入口重试完成，不称永久网络修复。继续调度、Agent性能调优、权限治理，本批是T7第一批导出边界，不等于诊断采样、全入口覆盖或全主线完成。

- 先确认中断前的实际红测：`eap-t7b-export-red-v1-1008`完成，Agent与AX各4项合成HTTP合同失败/0通过、各退出1、无panic，原错误文本、跳转、2秒上限与URL日志边界已复现。负向包39779bytes/SHA256`15f868e478cc8adc257b1ef3ceff586ad64210341484913b600d5bfa90bceec3`与23摘要保留；没有真实凭证、供应商、业务正文或工具，不推断生产泄露。首辅助归档根目录校验在解包/起测前拒绝，修正记录随证据保留；红测未做race。
- 精确四源码：两个Runner的`main.go`及各新增`telemetry_export_contract_test.go`。2秒导出/禁止跳转/响应体上限4096，固定失败状态和日志不含原始异常/URL，复制HTTP客户端不改模型110秒期限。后台对话固定业务结束时间，实例身份/凭据缺失不降级旧观测入口；AX现有UID/期限核对保持。导出失败不重试模型、工具或Span，不改变原意图、回执及UNKNOWN。实验导出实现保持。每个Runner6项新合同，现有回归全部保留。
- 初候选8806bytes/SHA256`b9c8a43391ecb7cdb5509b795f4f0153b58500feacad6f145c7bdd4dce3c2987`独立上传核对后才解包，gofmt远程执行。最终远程普通/竞态：Agent各33顶层/59含子项、AX各18项，两个编译退出0，无失败或race；709源码、本地与测试候选逐项一致，四格式化源码已接收。后端与前端未变，沿此前634/95类与220/类型/构建，本批没有重跑后端/前端/浏览器或新原生Actor业务验收。原始模型/工具消费新增0。
- 集群构建两个固定镜像，并从仓库核对manifest/config/layer摘要和层内二进制与实际测试构建逐字节一致，实际9Secret原文/编码节点RAM扫描PASS。仅CAS更新未来`EAP_SUBSTRATE_RUNNER_IMAGE`为`10.186.133.66:5001/eap-runner@sha256:a69969ea4f41c7e1c58624dd242c5b4a29d0533f9e45ab4745f75c0ba7c63df2`及`EAP_AX_RUNNER_IMAGE`为`10.186.133.66:5001/eap-ax-runner@sha256:3a7f0d9d0604bd194a93eb828bdb320b48d0a3c04e14d07b6e9019108e88b10e`。原活动工作为0；没有整份apply、替换旧冻结任务/Actor模板或重启认证。仓库最小默认补丁只记录两值，与实际配置核对一致，未改外部K8Substrate历史全量清单。
- backend仍7275f8e98ba515d1867b2faf79f4f52e3a85ac3a9ad8a8e3379153861fab9008，环境更新后实际新PodUID`7a6fcfbd-e37f-46b6-9ef5-a45b7dc8a244`，健康UP/imageID/MySQL68通过。冻结33表2703行全字段摘要，包括平台已用/预留和原AX任务、调度/阶段/配送登记；两个atespace旧UID/模板/状态、认证Pod及不可变配送Secret、其他镜像/环境保持。旧UNKNOWN审计Current、C FAILED及E KEEP_BASELINE、原experiment46两条COMPLETE和旧experiment45缺口完全保持，无新Span/模型/工具，原费用不退款、旧cap/revision不扩。
- [验收索引](evidence/eap-runner-export-boundary-20261009/index.html)、[最终回执](evidence/eap-runner-export-boundary-20261009-final.json)：归档173257bytes/SHA256`773f85055a913bb4523cd36a1b4d2e4af8ca5fda60f24d96750c94c2aaa1ce36`，43文件摘要/42链接/四源副本/709源接收核对。首次本地整源比较两份列表排序不同，统一路径排序后全部709一致，非应用测试失败；辅助历史保留。4Job/4Pod日志扫描保存、按冻结UID清理、剩余0，最终保护PASS。归档后、接收核对完成后才精确四文件推进`/root/eap-src`，709再次核对；节点`/root/eap-t7b-out-v1/final-completion.json` completed=true/closed=true。原T7及本批归档不覆盖；不能重跑已删Job或旧业务执行来造新证据。

合同[RUNNER_TELEMETRY_EXPORT.md](RUNNER_TELEMETRY_EXPORT.md)，交接/主线/后续方案已同步。下一步T7服务端冻结版本可选诊断采样，再补跨入口可信分母、日志指标和问题定位；第二、三批仍未实现。费用、原意图/UNKNOWN、身份和工具效果不降采样，缺少持久对话登记不虚称完整率。继续可信租户/资源配额、跨入口调度、真实业务工具及旧消费者迁移、T6取消/补偿与T8策略；业务工具接口仍待用户信息，不阻塞独立工作。原总计划差额最多5次/21653输出没有重新分配，本批零消费、没有稳定生产调优收益。全部格式化/依赖/测试/编译/构建/部署远程；本机仅文本、摘要和脱敏证据操作。未commit/push，AGENTS/BP/研究和其他线程既有记忆保持。

### Codex 进展：KISS AgentEvolver借鉴研究与T7-C红测检查点（2026-10-09）

用户先要求继续下一步，随后提供ksenxx/kiss_ai并指出AgentEvolver，澄清不是通义同名项目，最终要求主要判断是否可以借鉴。研究已聚焦KISS内部`src/kiss/agents/obsolete/create_and_optimize_agent/agent_evolver.py`；前期同名搜索未作为结论依据。新增[研究备忘录](KISS_AGENT_EVOLVER_REVIEW.md)、[来源登记](research/kiss-agent-evolver-20261009/source-register.json)，不覆盖既有IBM/Galileo及其他研究。

公开GitHub API冻结提交`44583f1cf416e7befda46e3cb79345390e598b2a`、时间2026-10-08T16:27:33Z，递归tree不截断；12个关键公开文件下载的git blob逐项匹配固定提交，SHA256/大小/来源性质登记。核读旧AgentEvolver/ImproverAgent、弃用说明、当前skillopt/rsi7d/设置调优及任务分派/预算实现；另核对独立作者SkillOpt论文v2作为方法依据，不引用作者收益为EAP事实。网页工具读取固定GitHub路径cache miss后由公开固定raw读取成功；公开来源核读没有执行外部代码、安装依赖或向外传输私有EAP源码/轨迹/凭证。

结论：原生借鉴受限变异、候选谱系、质量门禁后的Pareto前沿及成功/失败双视角提案，复用已有GEPA/持久留出/原回执/全费用/UNKNOWN合同。旧类优化候选Agent程序，候选种群不等于团队联合优化；其模块已弃用。代码自报指标、同任务评价、初始化无独立尝试上限、无显式候选子进程timeout与临时状态边界不能替代平台验收。底层Sorcar确有预算能力，未错误描述成整个KISS没有预算。当前skillopt的select是反复搜索选择集，不能冒充最终未接触HOLDOUT。上述风险为静态核读，未做运行复现。

现有EAP只优化单Agent提示词，团队/计划串行执行不等于已经具备团队候选配置与端到端配对评价接口。计划先用公开合成指标零模型验证前沿/谱系，再新增两角色受控评测合同；候选不能修改模型/工具/身份/权限/预算。示例完整团队对照最坏94次/12800输出，独立复验另60次/7680输出，明确超出当前单根64次和原剩余差额，不能提交或拆根绕过；不是新配额。没有KISS接入PoC、团队优化实现、新模型/工具、生产收益或新部署。

主线独立T7-C负向合同已通过原n1.sh远程执行。新增一份HTTP合同，旧709源码+合同形成隔离红测710；上传测试及辅助脚本独立摘要确认后才执行。`eap-t7c-policy-red-v1-1009` UID`4999d3ae-4218-47cf-9598-f2c75fbbdb84`于2026-10-09T01:48:45Z完成；XML实际7项/6失败/0错误/0跳过、Maven退出1、Job按预期红测退出0。节点`/root/eap-t7c-out-v1/red-proof.json` outcome=EXPECTED_CONTRACT_GAPS_CONFIRMED，真实9Secret内存原文/编码扫描PASS、33表2703行全字段/费用及两atespace原UID/模板/状态等保护PASS，模型/工具新增0。不是7项绿色通过或真实JWT浏览器验收。

用户研究澄清后将本轮自建负向测试移存为[待实现合同夹具](evidence/eap-diagnostic-policy-red-20261009/DiagnosticPolicyAccessContractTests.java)，内容SHA256仍`35170bfc96f4d70309d7daf7c6cfe1f4f0980bcad188afedf42e668f96f7c0f0`；不会进入当前绿色后端默认测试集。[独立回执](evidence/eap-diagnostic-policy-red-20261009.json)明确implemented/greenVerified/deployed/completed/closed均false。本地原709业务源码摘要全部一致，远程/root/eap-src仍709；没有采样策略修复、新构建、发布或推进绿色源。红测Job/隔离源码/日志报告保留，尚未确认清理，不称临时资源剩余0。下次直接接续已保存红测，不重跑旧模型或原意图；先新实验可信身份/原Trace冻结版本，再跨入口覆盖与团队优化适配。

交接、主线状态和T7方案已如实补充。文档diff检查通过，12来源与合同摘要核对、709业务源核对通过；没有在本机运行应用、测试、编译或构建。没有重复已闭环T7/T7-B构建/验收，原归档及UNKNOWN/FAILED/KEEP_BASELINE均保持。主线仍未全部完成，无commit/push，AGENTS/BP/既有研究和其他线程记忆不改。

### Codex 进展：新实验诊断策略冻结、实例接收与权限已部署验收（2026-10-09，T7-C）

用户“继续之前的任务”后，接续上述已保存红测；本批后端基础能力已完成有界验收并闭环，主线尚未全部完成。合同[DIAGNOSTIC_SAMPLING.md](DIAGNOSTIC_SAMPLING.md)，原7项/6预期失败红测与历史回执保持，原合同逐字进入绿色测试，没有重跑红测、旧实验或原UNKNOWN。

V69仅新增策略版本、策略头、实验诊断决定、诊断接收四表，不回填旧事实。不可变策略由中央GOVERNANCE发布，expectedVersion/CAS与新实验登记通过行锁串行化；冻结读取使用当前锁定版本，避免MySQL旧快照看不到新版本。新原实验在提交事务内冻结每个试验的可信Agent/实例键/数据库实例编号/端点/UID/模板、原规范化Trace和策略；相同原请求返回原登记。默认SHA256_TRACE_V1、10000万分比，未降低生产保留率。旧身份不确定的选择为null，不推断低风险。

仅实例观测入口中名称严格等于agent.diagnostic的额外Span参与选择；先机器认证、身份/命名空间与全批限制校验，再核对原登记。可信平台FAILED/UNKNOWN保留新到必要元数据，调用方risk/approved/状态不授权。仅保存规范化ID、固定名称、有效生产者边界与服务端原因；去除原attrs/resource/events/links/errors/正文。每试验最多16个不同可选Span，重复只计数，超限单列；按Trace排序锁定，接收与OTLP写入共事务，失败回滚。核心Agent/GATEWAY usage、费用、原回执/UNKNOWN、身份/责任、工具准入及效果不降采样；不回放以前过滤的Span。只读/diagnostics区分冻结选择、收到/未收到、身份与分母未确认，复用中央VIEW_CONSOLE及原Agent可见性，不签发凭证或执行业务。

最终远程v2定向144项/9类、完整662项/97类，失败/错误/跳过均0，打包退出0。覆盖版本保持/确定性、0%下核心保留、可信失败/未知、未收到、重复/限额、旧无登记、身份隔离、事务回滚、并发发布、四角色无副作用及实际机器认证接收链路。v1定向142项通过；补两项跨实例借Trace和全批验证先于写入测试后由v2替代，生产源码不变，v1日志保留。709基线精确16路径变更至715源码（6个新文件）；候选包76109bytes/SHA256 e243cf0cc7a6408102607fa0900b5201a1e4c5c9d753cf302ffd2664b1d2fd2e，上传独立核对后才解包。全部依赖/测试/打包/镜像构建/部署均远程，本机仅文本、摘要、脱敏归档；前端/两个Runner源码和镜像未变，本批没有重测它们或重复浏览器。

固定后端镜像`10.186.133.66:5001/eap-backend@sha256:cb8201c3ccfc8213f5d286e9ed8cfddb85d6f8dd21387eae322efe4ef45b5161`。manifest/config/末层摘要及层内jar逐字匹配测试打包：92647135bytes/SHA256 203b111759dc36ce16ff83880461120d5b47b4e5320ffde3cfff3ce566bee90a。只CAS后端镜像，实际PodUID`1f1f26b8-0cf1-452a-9bd7-68352d33e6eb`、imageID/健康UP/MySQL69通过。两个Runner未来默认、其他环境、前端/optimizer、旧模板/UID、认证与配送保持；仓库最小补丁[diagnostic-policy-backend-image.json](../deploy/substrate-poc/diagnostic-policy-backend-image.json)与实际一致，不整份apply历史清单，未改外部K8Substrate文件。

真实现有四角色PKCE登录：/me、策略和旧实验诊断元数据均200；viewer/operator正文及发布403、GOVERNANCE=false，governance/admin正文及发布200、GOVERNANCE=true。陈旧版本409、非法正文400、匿名401，并发发布一成功一冲突；只发布10000，当前v4/10000。旧实验46两个试验仍UNREGISTERED，版本/选择null；生产决定和接收表均0行。没有新建真实实验/Actor、没有真实模型/工具或新Span，模型调用和工具效果新增0。新实验冻结与可选Span接收是隔离回归，真实集群验证只覆盖策略/权限/CAS和历史未回填，不能称真实模型端到端采样已实测。

33表2703行全字段摘要及费用、两个atespace原UID/模板/状态、认证Pod、不可变配送Secret及其他环境完全保持。原UNKNOWN唯一STOP_NO_REPLAY审计、C FAILED、E KEEP_BASELINE、实验46两条COMPLETE与旧45缺口不变，不补Span、不退款、不扩旧/共享cap或revision。实际9Secret原文/编码仅节点RAM扫描PASS，凭证不落日志/归档。4个本批测试/构建Job和4个Pod按冻结UID清理、剩余0；日志保存扫描、清理及源码推进后保护PASS。

[验收索引](evidence/eap-diagnostic-policy-20261009/index.html)、[最终接收回执](evidence/eap-diagnostic-policy-20261009-final.json)：归档809132bytes/SHA256 f38fc1be29eb394675305fb77c5b503f50a15c20bb22d036a0712301a9af90c7，与节点一致；本地264文件摘要/263索引链接/16源副本/715完整源核对通过。接收后才精确16路径推进/root/eap-src至715，并逐项核对；节点/root/eap-t7c-green-out-v2/final-completion.json completed=true/closed=true，回执SHA256 e38750a68279b9851f82108cbde418069d9ceb47ca4f3d0a7748a1933825e753。最终源manifest SHA256 439f64024d038032285a857174bdd35a162c0680f2278bc05ef744593521d771，与本地及归档逐项一致。不可变归档保留导出时的promotion pending，最终回执单列，不覆盖原归档；不要重跑已删Job、角色发布、旧业务或export。

辅助历史均保存：过早构建因原package日志未EXIT=0被门禁拒绝，尚未建镜像上下文/Job或部署；等原package完成后继续，未重跑测试。Registry大小只读查询仅Accept Docker时OCI返回404，补同时接受OCI/Docker后同digest200；基础29层展开较慢，未据此改应用。发布后首次健康503，后同Pod/同镜像只读复核200UP，无额外部署，原503原因未证明。可选Metrics API不可用，资源用量未测，不声称性能或节省。n1.sh仍间歇Connection closed/reset，按原入口重试，上传/接收独立摘要核对，不能凭SSH退出0推定传输完整，也不称永久网络修复。

本批没有前端页面或agent.diagnostic生产者；跨入口持久分母、统一问题入口、日志指标、告警与规模对照仍待。下一步第三批优先新AX公开ECHO/FAIL与控制节点的可信原请求/身份/原Trace及预期0模型/平台检查事实；任务/对话/AX Trace当前由Runner生成，不从任务键猜分母、不回填旧操作，未登记入口保持未确认。继续调度、Agent性能调优、权限治理主线：可信租户/资源配额、跨入口调度、独立真实任务/团队优化适配、业务工具与旧消费者迁移、T6取消/补偿、T8策略。原计划差额5次/21653输出不是新授权额度，无稳定生产调优收益。交接、主线状态和方案同步，未commit/push；AGENTS/BP/既有研究及其他线程已有内容保持。


### Codex 进展：T7-D可信分母与日志指标关联开始实现（2026-10-09，进行中）

用户确认就绪，要求接续跨入口可信分母、日志/指标关联以及真实新实验采样验证。已从T7-C completed/closed、715绿色源检查点接续；未重跑旧实验或闭环的测试/角色发布。新增合同docs/OBSERVATION_COVERAGE.md，V70仅新增两表，新AX原Trace/执行模式登记、原创建身份冻结，新实验复用原试验/诊断身份，工作流控制路径与原owner/epoch进度CAS日志；只读窗口指标/详情与新页面、兼容旧JSON的AX Trace及实验最小可选诊断生产者已实现。未登记通用任务/对话/旧外部入口分母null，不虚称全入口覆盖。

725源码/19路径候选已上传至/root/eap-t7d-upload-v1.tar.gz，82104bytes/SHA256 783ca235a5595ba1c7a4fa780aec4aa9446706c2817d7be077e9ca1fd2b040b3；辅助脚本/root/eap-t7d-stage.py 13372bytes/SHA256 aa3ac2c7a652f84808d3546238d1f149b7f788d46af345bc7ac86fa8c45beebb，节点独立核对一致后才调用start。独立SRC/root/eap-t7d-src-v1、OUT/root/eap-t7d-out-v1；格式化、专项/完整/打包、前端和两个Runner回归待结果，尚未部署或推进/root/eap-src。不能称已验收或临时资源剩余0。

真实验证预登记最多新4次/每次128输出，分别100%和0%两个新独立公开配对实验；先核对原计划已123次/剩最多5次和实时共享账本，失败/UNKNOWN停止不换键，结束新版本恢复100%。零模型新AX ECHO/FAIL与控制工作流、真实四角色桌面/手机只读浏览器待执行。当前尚无本轮真实模型/工具或新Actor消费；不扩旧额度/revision、不退款或复活旧UID。原n1.sh已连通仍偶有延迟；全部依赖/格式化/测试/构建/部署/浏览器远程，未commit/push，AGENTS/BP/研究及其他线程既有内容保持。最终结果另条追加。


### Codex 进展：T7-D跨入口可信分母、日志指标关联与真实采样验收闭环（2026-10-09）

用户确认就绪后要求继续跨入口可信分母、日志/指标关联及真实新实验采样；本轮完成有界实现、部署、实测和收尾，整条主线未完成。合同[OBSERVATION_COVERAGE.md](OBSERVATION_COVERAGE.md)。相对715绿色基线精确19路径、10新文件至725项：V70仅新增原操作与固定事件表；AX原提交平台Trace/模式、原派发可信实例/UID/模板/端点冻结；实验复用原试验Trace/诊断冻结身份；控制路径原提交登记，完成事件随原owner/epoch CAS提交。失败/未知保留分母，执行与覆盖分列；只读窗口聚合/详情、新定位页面关联Trace、固定日志、核心Agent/GATEWAY与原费用，不发起业务。未登记通用任务/对话/旧外部入口为null，不从Span推分母。实验新Runner显式协议声明后才启用最多1条最小额外诊断；旧省略字段JSON及缓存/UNKNOWN不重发。

最终远程后端v2专项130项/9类、全量675项/99类、打包零失败/错误/跳过；前端v3的224项/类型/构建通过；通用Runner普通/race各35顶层/63含子项、AX各21项和两个编译通过。首辅助重复import获得同一非阻塞锁，在候选/Job创建前拒绝，修正为复用原模块。首应用专项130项/4错误，Java条件表达式对未确认null计数隐式拆箱；显式可空后只重跑后端，前端/两个Runner原证据逐源一致复用。两个Runner镜像最初缺Alpine标签MANIFEST_UNKNOWN，保留失败后改用原固定Runner镜像基础层，不改二进制或重测。以上失败/辅助历史随归档保留。

四镜像manifest/config/末层及jar、dist、二进制逐字匹配测试构建。最终后端10.186.133.66:5001/eap-backend@sha256:1911386d9f40cf88a53085bb3e9d786b8730189e59ec0c548557e1bd5ede7631；前端10.186.133.66:5001/eap-frontend@sha256:250841bf3c0f9b160f078cd6d2252b1242e4e8745f9e8142af596e5a8a831267；未来Runner10.186.133.66:5001/eap-runner@sha256:2146291f373a2dd1542efff053610bc663817f9e94bcca686c39802575ff78c6；未来AX10.186.133.66:5001/eap-ax-runner@sha256:3e4e0307ef960ed5a6e864fe2a03808f02bbc5ea9426eb457b9b5ba533cb06f1。仅CAS这四项，实际imageID/健康UP/MySQL70/前端版本确认；原frontend236abcd0…中间候选与证据保留。真实浏览器确认顶部栏遮挡新抽屉标题和关闭按钮，只加append-to-body/z-index=4000，前端独立224项/类型/构建、固定镜像和浏览器复验，后端/Runner不变。原页面两失败报告/截图、首选择器匹配两个抽屉的歧义均保留。过早构建被测试未结束门禁拒绝，未建镜像Job，待同Job完成接续，没有重测。仓库最小补丁为observation-coverage-backend.json、observation-coverage-frontend.json，不整份apply历史清单，旧模板/optimizer及其他环境保持。

实时共享预算先核对156模型/13019输出/10工具与原计划123次/11115输出；独立预登记最多新4次、每次128输出、总512，新Agent单独4/512/工具0，不扩大旧/共享cap或revision。Agent63/实例eap-agents/a63-v1-172e155d84de、UID6c12e81a-c4e1-4b6c-a482-72e41a7f771d，两次原提交experiment47(v4/10000)及48(v5/0)，各两臂成功。四个原Trace各1核心Agent/1成功GATEWAY且原费用SETTLED，可选诊断2保留/2过滤，无重复/超限，随后发布v6/10000恢复；旧v4/v5决定不追改，旧46无登记。新AX ECHO/FAIL各1及START/END各1的四个零模型登记COMPLETE，FAIL业务仍FAILED。共8新登记，模型实际4次/218输入/256输出全SETTLED、预留0、工具0；PLATFORM精确增加4/256、工具保持10，原总计划累计127次/11371输出，不扩大128/32768，剩余1次差额不是新额度。三个新Actor原UID已SUSPENDED、专属身份REVOKED，历史证据和费用保留，不补Span、退款或重放旧意图。

四角色真实PKCE API、/me能力与正文200/403一致；固定stdout事件关联原Trace/原登记与窗口指标，详情含SETTLED原费用。桌面1500px/手机390px共8截图，原Trace/日志/费用、失败与齐全分列、未登记分母未确认、打开/关闭/刷新均通过，页面错误/失败API/业务写入0。首浏览器安装180秒退出，重定向安装日志未取得，保留原Pod状态/可取得空stdout；恢复辅助最初因此拒绝后明确标记缺失，换USTC包源新Pod就绪，最终安装日志保存。没有把未取得日志或未运行浏览器记为通过。所有依赖/格式化/测试/编译/构建/部署/浏览器远程；本机仅文本、摘要与脱敏接收，n1仍间歇reset，不称网络永久修复。

原33表2703行逐行摘要保护，仅PLATFORM真实已用/时间与新工作流调度序号按合同允许变化，其他旧列保持；另冻结4个旧策略版本及诊断/观测原行不回填。两atespace旧UID/模板/状态、认证Pod/配送Secret保持，旧UNKNOWN审计/FAILED/KEEP_BASELINE、45缺口、46原证据不改。实际9Secret原文/编码仅节点RAM扫描，源/产物/可取得日志/归档PASS。12个测试/构建Job、14个Pod按冻结UID清理、剩余0，日志先保存扫描，最终保护通过。

[验收索引](evidence/eap-observation-coverage-20261009/index.html)、[最终接收回执](evidence/eap-observation-coverage-20261009-final.json)：归档3540680bytes/SHA256 ac2537e58921584875a50bc03cd160640efc0f005b167d0cd86d2ed0d27c9ffd，439摘要/438链接/19源副本/725完整源本地核对通过。接收后才精确19路径推进/root/eap-src至725；节点/root/eap-t7d-out-v2/final-completion.json completed=true/closed=true。原最终回执3284bytes/SHA256 9007f26b3dd718c2eb9072c62c0bb0f85d23045485aa30ff1870dc99ee3db191，原字节独立接收；最终源manifest SHA256 9b4b5d6238422f726627f84a4cc154333b9e6cebdf74d542e4e56103e10e707d，与归档/本地一致。归档保留导出时promotion pending，最终结果单列，原归档不回写；不要重跑已删Job、freeze/prepare、实验/AX提交、策略发布或export。

交接、主线、方案和诊断合同同步。这里是新AX/实验/控制节点的持久事实窗口聚合与固定元数据日志，不是全平台全文日志、独立Prometheus采集、告警或生产规模验收。通用任务/对话/旧外部入口、AX LLM真实端到端仍未测；可信租户/资源配额、跨入口调度、真实业务任务与团队优化、业务工具/旧消费者迁移、T6取消/补偿及T8继续待完成，无稳定生产调优收益。不commit/push，AGENTS初始摘要、BP/既有研究及其他线程原内容保持。


### Codex 进展：继承AgentEvolver方法的零模型候选谱系与前沿已验证（2026-10-09，T3-F）

用户要求继续主线，并询问之前AgentEvolver能否继承。核对KISS已有研究、T7-D已闭环与725绿色源后，按已记录第一步原生实现[离线候选合同](AGENT_EVOLUTION_CANDIDATES.md)，不引入或运行KISS弃用执行器。新增runtime/prompt-optimizer/candidate_frontier.py、frontier_demo.py、test_candidate_frontier.py，更新该目录README，精确4路径/3新文件至728源码；原adapter/server/依赖/Dockerfile、前后端和两个Runner保持。没有commit/push。

候选冻结角色映射/协议/批次，只准一次改变一个原角色提示词，记录父代/代数/变化角色；原键复用、改参冲突、重复内容/循环谱系拒绝，调用方不能修改已保存对象。有效JSON的失败提案也消耗独立尝试上限，原拒绝重读不再计数；候选/代数/尝试各有限。每候选只保存原观察，UNKNOWN不能换键替换。只接受同协议/批次的完整VALIDATION，TRAIN/HOLDOUT/额外正文/外来或重复样本拒绝；全部登记样本构成分母，FAILED/UNKNOWN/NOT_RUN与缺失分列，未知Token/时延不补零。质量下限取配置及实际同批基线的较大值，再按质量/总Token/整任务端到端时延之和保留非支配候选，严格支配、相等保留、摘要确定性排序；始终NO_ADOPTION_OFFLINE_ONLY。当前为进程内纯算法账本，尚无平台持久claim/CAS/恢复或可信团队评价接收，调用方自报usage不能证明真实来源。

仅原n1.sh远程执行。候选上传48039bytes/SHA256 7b47042984a8c4962089c5bac8a423e82e117f76e8e85d40c967470fd2a39682，辅助13249bytes/SHA256 fafcae3dcc014a3f5ca0703561725134e674fc460cf0f71b0eb74c856480a4a4，节点长度和摘要核对后才启动。隔离Job eap-t3-frontier-v1-1009/UID c8a4b85f-b7d4-467a-8dc2-df64e409fe6b，既有固定optimizer镜像35a8bb20…/实际GEPA0.1.4，候选合同19项、原GEPA适配器20项、单任务服务4项，共43项，失败/错误/跳过0。公开合成baseline/A/B前沿、C低质量拒绝、D证据缺失、E被支配；非回环网络尝试0、无凭证挂载、真实模型和工具新增0，不将合成1000/800等数值当生产成本或收益。

原33表2784行及6个诊断/观测表全列摘要保持，费用/预算、两atespace旧UID/模板/状态、认证配送、应用镜像/配置和optimizer spec保持。实际Secret原文/编码仅节点RAM扫描本组件源及本轮日志/证据，通过后导出；1Job/1Pod按冻结UID删除、剩余0，最后保护复核一致。没有生产构建/部署、真实团队模型或浏览器验证，没有重复其他组件回归。初始沙箱连接被阻止后按原入口申请提升；仍有banner超时/reset，原n1重试及摘要核对成功，不声称通道永久修复。本机只文本编辑、文件摘要/清单及脱敏接收，没有运行应用、测试、编译或安装依赖。

[证据清单](evidence/eap-agent-evolution-offline-20261009/manifest.json)、[测试](evidence/eap-agent-evolution-offline-20261009/test-summary.json)、[合成结果](evidence/eap-agent-evolution-offline-20261009/public-synthetic-demo.json)、[最终接收回执](evidence/eap-agent-evolution-offline-20261009-final.json)。归档104858bytes/SHA256 d10672540f802881fa25f7a451aaa9eb3cceeeeeb31b77134dfc173dffcd15cf，22证据摘要/4源副本/728完整源码本地核对通过；接收后才精确4路径推进/root/eap-src。节点/root/eap-t3-frontier-20261009/final-completion.json completed/closed/sourcePromoted=true、deployed=false；原最终547bytes/SHA256 874244b3fe0747fbd8173f2f63ff26283702dc2fec1e6f8ca80088523b0182ef，原字节单独接收。source-manifest SHA256 2263142e8b61bc5838b9b1359ebc108beb1f0fe51d22ac73fa4ae49e3d39cbe6；本地最终回执SHA256 ad752cacfd144434d3364ba803b4e8f28884631e8ebcfe1a2256ba0d8af88389。原归档保留导出时promotion pending，不回写，不重建Job或重复export/promote。

交接、主线状态与KISS研究时点补充已同步，AGENTS原始摘要80db1f…/BP/既有证据/其他研究和其他线程记忆保持。主线尚未全部完成，历史UNKNOWN/FAILED/KEEP_BASELINE不变，没有团队真实收益。下一步先平台持久团队候选及可信原工作流/原身份/原GATEWAY usage的端到端评测适配，再封存及独立HOLDOUT。真实任务与新预算尚需明确，原计划127次/11371输出、128/32768上限保持，剩余1次差额不是新授权，不拆根或重放。可信租户/资源配额、跨入口调度、通用任务/对话分母、业务工具/旧消费者迁移、取消补偿与统一策略继续待推进。


### Codex 进展：平台持久团队候选与原评价槽位开始远程验证（2026-10-10，T3-G进行中）

用户继续后，接续T3-F已闭环的728源码。核对通用工作流尚不支持团队候选提示词执行，不能补挂历史工作流作为新证据；本批先实现[持久合同](TEAM_EVOLUTION_CONTRACTS.md)。V71只新增准入锁/原合同/候选/尝试/评价/槽位，冻结数据库原平台角色/身份/发布/配置和公开分组样本；单角色提示词候选、持久原键/拒绝、原主体与并发上限、全验证集两角色原Trace/操作槽位，VIEW_CONSOLE/VIEW_CONTENT/RUN_AGENT_EXPERIMENTS沿中央权限。liveIdentityState明确NOT_CHECKED，没有Actor实时profile/身份检查；执行/回执入口和预算分配均关闭，所有新槽位NOT_DISPATCHED，不能声称真实团队评价已执行。

相对728精确6源码路径、4新文件至732。上传/root/eap-t3g-upload-v1.tar.gz 54050bytes/SHA256 ff8b6670fc4920456c24db93dcf4dd04e4499acd457bcdd4cf4b204755e2a11a；辅助/root/eap-t3g-remote.py 10496bytes/SHA256 a8e4686de2c16f3edcef92ebc0dec20c78a2378153ee43d5848edffceb66ed9c，独立核对后才启动。隔离/root/eap-t3g-20261010-v1/src和out；Job eap-t3g-contract-v1-1010/UID9b59698a-e73d-4fdd-b4fa-2a7bdc5e3a96，远程专项、完整回归和打包进行中，尚未验收/构建镜像/部署或推进/root/eap-src。临时资源未确认清理，不能写剩余0。前端/Runner/optimizer源保持，原预算/业务/身份/镜像保护已冻结，模型/工具新增0。未commit/push，AGENTS/BP/原证据及其他线程内容保持；最终结果另条追加。

### Codex 进展：T3-G首轮通过，存储边界补强版开始验证（2026-10-10，仍进行中）

服务恢复后先读取原Job，首轮已在2026-10-09T16:37:16Z独立完成；没有重复启动。专项93项/4类、全量693项/100类，失败/错误/跳过均0，打包通过。日志与XML报告已保存，原业务/费用/身份/镜像快照保持；首轮1Job/1Pod按冻结UID清理并复查剩余0，确认保存在/root/eap-t3g-20261010-v1/out/cleanup.json。

静态复核发现两个合法角色提示词经JSON转义可超过MySQL TEXT上限；最终v2改configuration_json为MEDIUMTEXT，并增加超65535字节配置往返与管理员完整HTTP登记两项用例。首轮没有应用测试失败，其源码、结果和日志作为superseded-v1保留。最终上传/root/eap-t3g-upload-v2.tar.gz 54636bytes/SHA256 d527c737558de152a82aae8d768c2122e0638a250a447e26764ff4f9fc4c6361；辅助/root/eap-t3g-remote-v2.py 11207bytes/SHA256 959c6b25cfe371526e7c4ef27f29a329f480fad1dc399503d5d01b3ceb757689，大小/摘要重新确认后才启动Job eap-t3g-contract-v2-1010/UID b61235f8-5a6f-433d-b39b-ef075c471cd3，隔离/root/eap-t3g-20261010-v2/src、out。v2专项/全量/打包尚在进行，不能写最终通过、资源已清理或源码已推进。不要重复start；完成后依次collect、cleanup、export、接收核对、promote，原始归档和最终推进回执分开保存。没有镜像构建/部署，生产MySQL仍V70，模型/工具新增0。


### Codex 进展：T3-G第二版通过，MySQL大小写兼容修订开始验证（2026-10-10，仍进行中）

第二版专项95项/4类、完整695项/100类及打包通过，失败/错误/跳过0，日志/XML/保护检查保存；第二版1Job/1Pod按冻结UID清理，剩余0。生产只读确认Flyway70、team_evolution表0及排序规则utf8mb4_0900_ai_ci，没有生产迁移或部署。静态复核结合该排序规则发现仅大小写不同的角色/样本键可能在MySQL槽位PK冲突；第三版将ASCII请求键/角色名规范为小写，样本ID/组ID按小写查重，候选摘要引用/评价UUID按小写读写，实例键冻结平台登记原值。原参数指纹保持，原键只改大小写仍冲突，不产生新行。新增大小写别名隔离及原键/引用一致性两项有意义回归；第二版是已通过但被补强替代的版本，不记为应用测试失败。

第三版上传/root/eap-t3g-upload-v3.tar.gz 55020bytes/SHA256 53d17f362c24c3dc36b205e0cf08703ab1930386c1ca6b9d386da33a2309c1dd；辅助/root/eap-t3g-remote-v3.py 11349bytes/SHA256 df2748b3bfdf9c6b483b9ba553feb00ce13b7dfbe9b352170147261145e672dd，节点独立大小/SHA核对通过才启动Job eap-t3g-contract-v3-1010/UID ea956c7a-b5a9-4ccb-b098-5b7a4b830c6f，隔离/root/eap-t3g-20261010-v3/src、out。仍732源码/6路径，第三版测试进行中，未导出/验收/清理第三版或推进/root/eap-src。不要重复start；后续collect、cleanup、export、接收校验、promote按顺序执行。前两版原源码/报告/清理事实嵌套保存为superseded-v2及其中superseded-v1。模型/工具新增0，旧预算/业务/身份/生产镜像保持；n1曾Connection closed后原入口恢复，不能因连接退出猜测远程动作未执行。最终结果另条追加，未commit/push。


### Codex 进展：T3-G持久团队候选与原评价槽位验收闭环（2026-10-10）

继承T3-F离线方法，完成[平台持久合同](TEAM_EVOLUTION_CONTRACTS.md)：V71新增准入锁及五业务表，冻结数据库原平台两角色/UID/发布/配置/公开切分，单角色提示词候选、持久原键/拒绝与独立次数上限、全验证集两角色原操作/Trace槽位。中央VIEW_CONSOLE/VIEW_CONTENT/RUN_AGENT_EXPERIMENTS一致。实际身份/profile未检查，liveIdentityState=NOT_CHECKED；executionAvailable/receiptIngestionAvailable/budgetAllocated均false，所有新槽位NOT_DISPATCHED，无工作流绑定、派发、可信评分或自动采用。

最终v3远程专项97项/4类、全量697项/100类及打包通过，失败/错误/跳过0，其中团队合同21项。v1的93/693及v2的95/695均通过并保留；静态补强分别解决JSON转义超过TEXT和当前MySQL忽略大小写的键兼容问题，新增往返/HTTP/大小写隔离用例。当前回归为H2，服务实例重建和UNKNOWN夹具不等于真实Pod重启或执行器崩溃。生产只读确认MySQL70/团队表0，未构建镜像或部署；前端/Runner/optimizer保持，模型/工具新增0。

原33表2784行及6诊断/观测表、预算/原UID/模板/认证配送/全部应用镜像与环境保持，实际Secret仅节点RAM扫描。三轮共3Job/3Pod按冻结UID清理，剩余0。[证据清单](evidence/eap-team-contracts-20261010/manifest.json)、[最终接收回执](evidence/eap-team-contracts-20261010-final.json)：归档2233792bytes/SHA256 f320a26b465fb3fc7f67ec03d87a0fa6fd2dc61dfc6974d19b94e145241242c2，703摘要/6最终源副本/732完整源本地核对；接收后才精确6路径推进/root/eap-src。节点/root/eap-t3g-20261010-v3/final-completion.json completed/closed/sourcePromoted=true、deployed=false。原不可变归档保留promotion pending，最终推进单列。不要重建三轮Job、重复export/promote或重放旧业务；当前源码基线732，生产镜像仍T7-D及既有optimizer。

**下一步：独立预算准入与可信两角色执行适配。** 先完成原UID/模板/实时profile/批准期限核对、原工作流/责任/期限及stateless输入交接合同，再有界派发、可信接收原回执/GATEWAY usage、平台评分/整任务时延并接T3-F前沿；继续独立HOLDOUT和完整搜索/提案成本。真实业务任务与新预算尚需明确，原127次/11371输出、128/32768上限不扩大，差额1次不是新授权，不拆根、重放UNKNOWN/FAILED/KEEP_BASELINE或自动采用。没有团队真实收益，主线未全部完成。

通用任务/对话可信分母、可信租户/资源配额、跨入口调度、业务工具/旧消费者迁移、取消/补偿及统一策略继续待推进。所有执行/依赖/测试/构建/部署远程，仅原n1.sh；本机文本/摘要/脱敏接收。未commit/push，AGENTS初始摘要/BP/既有研究/其他线程内容保持。服务中断后只读核对原Job再继续，n1仍有间歇reset/关闭，传输独立核对大小/SHA。

最终source-manifest SHA256 50da35cf7575a016310a586d89961696f85c2ef0f33b71753f159450b81391c3；节点原最终回执388bytes/SHA256 6a06d3afda455d4c98b21216997190f9b9fc8da4a8b08a8677563cabc4906ddd；本地最终回执SHA256 e8eb4a8705245f837a08f2945926de70af82f7845271aef8d675a7117a2b6780。三轮Job UID依次9b59698a-e73d-4fdd-b4fa-2a7bdc5e3a96、b61235f8-5a6f-433d-b39b-ef075c471cd3、ea956c7a-b5a9-4ccb-b098-5b7a4b830c6f均已按UID清理。旧T7-D439摘要与T3-F22摘要重新核对保持。当前仅合同元数据已验收，不能声称真实MySQL71、实时Actor身份、真正Pod重启、团队工作流、可信回执/评分、前沿生产集成或真实收益已验证。


### Codex 进展：T3-H1 独立预算与只读实时准备实现，远程验证待执行（2026-10-10）

新增显式独立业务计划/额度/期限冻结；计划键不能绑定第二根、原根不能改键扩额；覆盖全部候选验证及基线/所选候选独立HOLDOUT的两角色费用，提案限OFFLINE_ONLY。预算冻结与计划同事务，平台及两Agent共享 used+reserved+margin/违例预检不预留共享容量，工具上限0。新增不恢复Actor、不配送/续期的实时profile读取与原UID/模板/desired配置/撤销复核。执行/回执仍关闭，未运行真实模型/工具、未部署，尚不能报告测试通过。待在原n1通道远程隔离验证并归档/清理。


### Codex 进展：T3-H1 首轮真实回归失败与修复（2026-10-10）

首轮隔离专项132项/7类，17失败、7错误、0跳过；全部集中新预算准入：跨服务直接读取Spring代理的contracts.enabled字段得到false，导致入口误判关闭；已改成getter，禁用测试改为真实target字段。首轮full/package未执行，不能记绿。另静态复核补HTTP发送前专属身份再次确认，防登记消失回退共享凭证，并补最终共享预算缩减的原子拒绝回归。原报告/源码保留待v2嵌套归档，生产保护PASS、模型/工具0。


### Codex 进展：T3-H1 独立预算及只读运行准备最终闭环（2026-10-10）

本轮新增独立计划、明确任务/预算确认与30–900秒期限；完整候选验证+基线/所选候选HOLDOUT两角色上界；OFFLINE_ONLY无模型提案。计划及MODEL_CALLS/OUTPUT_TOKENS/TOOL_CLAIMS=0三TASK账户同事务冻结，原键/计划/主体/额度/期限不可替换或续期。平台与两Agent保守used+reserved+margin/违例预检，不预留共享容量；发送前再次验证专属身份、不回退共享凭证，readOnlyChecksPassed仅当次观察。实时UID/模板/profile白名单摘要与原创建/发布/批准状态检查；不主动调用Actor恢复接口、不配送/续期。完整主体摘要用于账本审计，中央权限保持。

最终远程专项135项/7类、完整734项/101类及打包通过，失败/错误/跳过0，新准入30项。首轮132项17失败7错误，Spring代理字段getter修复后重跑；原失败及源码保留，首轮全量/打包未执行。独立MySQL 8.4.11隔离SQL检查全部通过：V58/V71/V72、零默认计划/任务预算、MEDIUMTEXT、大小写/单根唯一及FK、微秒期限、InnoDB回滚。首轮MySQL大字段断言失败，显式UTF-8夹具后通过，默认/UTF8 JSON长度21942/96018；没有修改业务迁移。不是Spring真实MySQL、真实Actor检查或Pod重启验收。

原33表2784行和6诊断/观测表、费用/原UID/模板/认证配送/全部应用镜像与环境保持；实际Secret原值/编码仅节点RAM扫描。4Job/4Pod按UID清理剩余0。归档1032889bytes/SHA256 9c8957ad677d838f9944b7799cafe4a1fbe178e81f7db820289d31824664495c，320证据摘要、12最终源副本、735完整源本地核对；接收后精确12路径推进/root/eap-src，节点/root/eap-t3h1-20261010-v2/final-completion.json completed/closed/sourcePromoted=true、deployed=false；最终回执docs/evidence/eap-team-admission-20261010-final.json。原归档不覆写，推进单列。旧703/22/439证据、AGENTS初始摘要和本段之前PROJECT_MEMORY前缀保持。未commit/push。

生产仍Flyway70/团队表0，未分配新生产预算、未构建应用镜像/部署/浏览器，模型/工具0。执行/回执仍关闭、槽位NOT_DISPATCHED，T3-F前沿未接可信执行；下一步T3-H2责任/期限/完整交接、持久claim/CAS及单次有界派发、原实例Trace转换/可信usage/评分/整任务时延、独立HOLDOUT与全费用。Runner输入2000字节限制需明确处理，不能静默截断。真实任务与新预算仍待明确，旧127次/11371输出及128/32768上限不变，差额1次不是授权，不改旧/共享caps/revision、不拆根或重放UNKNOWN/FAILED/KEEP_BASELINE。主线未全完成，无稳定生产收益。


### Codex 进展：T3-H1B MySQL预算当前读补强待验证（2026-10-10）

T3-H1最终734项/135专项、MySQL SQL及320摘要闭环后，复核提交事务的MySQL快照：共享额度二次检查改为FOR UPDATE当前读，避免REPEATABLE READ沿用早期快照。仅BudgetLedger一处查询/注释变化；准备独立双连接SQL证据及受影响58项专项/打包。原全量734结果与归档保持，不将补强后未重跑的全量说成已重跑。生产仍未部署、模型/工具0。


### Codex 进展：T3-H1B MySQL预算当前读补充闭环（2026-10-10）

BudgetLedger共享账户查询加FOR UPDATE当前读。独立MySQL 8.4.11双连接实际验证：原事务快照12，另一事务按全局预算锁缩额1；原事务再锁全局行后普通读仍12，FOR UPDATE读1。补强后远程58项/2类预算/团队准入与打包通过，0失败/错误/跳过；此前完整734项/101类是补强前版本，本次未重跑全量，不混称。其他734份源码未变。

补充归档131629bytes/SHA256 ddce7328a7aaa465adbef7d85c8b826b090d6ed227d85e707094ee3bc19aaf23，45摘要/1源副本/735当前源核对后仅BudgetLedger路径推进；节点/root/eap-t3h1b-20261010/final-completion.json completed/closed/sourcePromoted=true、deployed=false，docs/evidence/eap-team-admission-current-read-20261010-final.json。补充2Job/2Pod按UID清理剩余0，含H1本轮6Job/6Pod。原33表2784行及6表/费用/认证/UID/模板/镜像/环境和原320/703/22/439证据保持，实际Secret只节点RAM扫描。生产Flyway70/团队表0、新预算/真实模型/工具0；不部署、未commit/push。只读平台路径不主动Resume，但路由器在并发休眠时可能自动唤醒的竞争边界未实测，不承诺整个检查无资源副作用；真实Actor与Spring MySQL集成仍待。

下一步仍T3-H2可信两角色执行/原Trace和usage/评分/整任务时延/独立HOLDOUT与完整费用。输入2000字节及HTTP编码16384字节限制需明确处理；check不是授权租约。真实业务任务/新预算未明确，不用旧127/128的1次差额，不修改旧/共享caps/revision、不拆根或重放UNKNOWN/FAILED/KEEP_BASELINE。主线未全部完成，无稳定业务收益。


### Codex 进展：T3-H2A原槽位单次派发与网关回执适配，首轮失败待重验（2026-10-10）

新增默认关闭的原评价执行责任/holder/绝对期限、两角色串行单次派发与完整输出交接；原槽位Trace只允许原实例，Gateway按机器身份及冻结载荷/当前批准/共享与根预算准入，正文摘要与可信SETTLED费用共同验证回执，不采用Runner自报usage。无评分/前沿/HOLDOUT/自动采用，无真实模型授权或调用，生产未部署。首轮远程专项171项/10类，1失败19错误0跳过：Mockito重新配置执行已有回调的空参数错误19项，权限夹具缺ROLE_导致唯一403断言失败；首轮full/package未执行，报告与源码保留。已修正夹具，准备独立修订版复验。静态补强运行中关闭开关与发布Agent/模板核对，均待远程验证。


### Codex 进展：T3-H2A第二版夹具重载失败保留，第三版开始验证（2026-10-10）

第二版远程专项176项/10类，28错误集中BeforeEach的ReflectionTestUtils重载：AopTestUtils泛型返回被推断为Class，导致ClassCastException，尚未执行新适配业务断言。改为显式目标对象，第三版重新执行专项/完整/打包；不将前两版记绿。独立MySQL V73迁移/完整正文/原槽位FK唯一/原子回滚及双连接CAS（首持有者1、重复者0）通过，原预算快照12/当前读1复验通过，已按UID清理独立MySQL Job/Pod。生产保护PASS，真实模型/工具0。


### Codex 进展：T3-H2A第三版专项通过，原计划epoch补强版开始验证（2026-10-10）

第三版专项176项/10类、其中新执行28项全部0失败/错误/跳过，全量仍运行中，原报告不覆盖。复核增加机器identityGeneration冻结：独立REQUIRES_NEW读取已安装epoch，在profile前后及最终提交复核；Gateway必须匹配原计划epoch，撤销后重新批准的新epoch不能借旧计划Trace/额度。旧计划缺epoch字段失败关闭，不补填或续授权；无需改V73 SQL。新增原计划epoch替换/profile中变化回滚、有效新epoch借旧槽位拒绝及真实身份服务撤销检查，第四版执行最终专项/全量/打包，均只隔离fixture，模型/工具0。


### Codex 进展：T3-H2A最终专项180项通过，完整回归进行中（2026-10-10）

epoch补强版第四轮最终专项180项/10类，0失败/错误/跳过：新执行29项、独立预算32项、真实身份Trace8项、客户端14项、权限26项、迁移10项及既有预算/合同/接入专项通过。完整后端回归和打包仍在运行，尚未进行源码推进；当前远程/root/eap-src仍735基线，最终候选740源/20路径。第三轮176专项已通过，完整回归与其源码独立保留，不冒充最终epoch版。真实模型/工具0、生产Flyway70及团队表0保持，既有独立MySQL SQL证明保持，未部署/构建应用镜像或commit/push。待两绿版本完整门禁结束，UID清理及实际Secret节点RAM扫描、归档接收核对后精确推进20路径。


### Codex 进展：T3-H2A可信原槽位执行适配最终闭环（2026-10-10）

T3-H2A已完成[原槽位执行适配](TEAM_EVOLUTION_EXECUTION.md)：原评价/主体/请求键、持久holder/绝对期限、两角色单次串行派发与有界完整交接；原身份epoch与冻结载荷的Gateway准入、实际返回正文摘要及SETTLED账本共同确认回执。UNKNOWN不接管/换键/重放，不信Runner自报usage/latency；RECEIPTS_COMPLETE仍未评分。原计划新增机器generation冻结，旧计划缺字段失败关闭、不补填；完整搜索/评分/前沿/HOLDOUT/自动采用继续关闭。

最终第四版远程专项180项/10类、完整770项/102类及打包通过，失败/错误/跳过0；第三版176/766是epoch补强前绿版，独立保留。首轮171项1失败19错误、第二版176项28错误均为已修复夹具问题，full/package未运行，原报告与源码保留。独立MySQL8.4.11/0900ai_ci迁移、完整正文大字段、原槽位FK/唯一与原子回滚、双连接CAS首持有者1/重复者0及预算快照12/当前读1通过；SQL夹具不代表Spring真实MySQL集成、真实Actor或Pod崩溃。

[证据清单](evidence/eap-team-dispatch-20261010/manifest.json)、[最终回执](evidence/eap-team-dispatch-20261010-final.json)：归档2191826bytes/SHA256 9288bae84e5a1bc70abe6de8a5d219af03d10c939046b356d497e94c84dcab90，661摘要、20最终源副本/三历史版本源及740完整源本地核对；源manifest SHA256 6c080846804ff25d32959be69597ce87eb8bd16144b618062af4d142a2598c24。5Job/5Pod按UID清理剩余0；接收后才精确20路径推进/root/eap-src。节点/root/eap-t3h2a-20261010-v4/final-completion.json completed/closed/sourcePromoted=true、deployed=false。原33表2784行及6表/费用/认证/UID/模板/全部镜像和环境保持，实际Secret只节点RAM扫描；生产Flyway70/团队表0、新生产预算/真实模型/工具0。原45/320/703/22/439证据不覆盖，未构建应用镜像/部署/浏览器或commit/push，AGENTS/BP/其他线程保持。

**下一步T3-H2B：平台完整批次评分、两原角色与完整交接的整任务时延、可信前沿，再独立HOLDOUT及全费用。** 当前已有时间戳和可信回执，不把它们冒充评分或稳定收益。真实任务及新预算仍需明确；旧127次/11371输出和128/32768上限不变，差额1次不是新授权，不改旧/共享caps/revision、不拆根或重放UNKNOWN/FAILED/KEEP_BASELINE或自动采用。没有真实团队端到端或全系统生产验收，主线未全部完成，T2/T4–T8剩余项保留。旧Job/原export/promote不得重跑。


### Codex 进展：T3-H2B平台评分与可信前沿待远程验证（2026-10-10）

新增默认关闭的EXACT_TEXT_V1完整验证批次评分：只取原两角色/完整交接/正文摘要/SETTLED原账本及六归属项；新增每样本首次准备检查前的原holder责任起点，统计至第二角色确认完成的微秒整任务时延，旧回执缺起点不回填。原键不可替换、无客户端评分/usage写入。可信基线门槛/质量-token-时延Pareto、确定性留出候选选择及单根前沿封存；封存后阻止追加候选、新验证登记或新派发；UNKNOWN/未执行保留不完整，原未知费用与预留保留。仅远程隔离fixture验证准备，尚未测试通过、未推进绿色源码/部署，无HOLDOUT派发或自动采用、真实模型/工具0。


### Codex 进展：T3-H2B首轮编译失败保留，修订候选准备（2026-10-10）

首轮隔离Maven在testCompile失败：新测试response声明JsonNode却调用ObjectNode.set；测试尚未运行，full/package未执行，不记为绿。修正显式ObjectNode，更新Flyway计数74；同时封存前拒绝DISPATCHED未结算费用，防止费用快照遗漏迟到结算。v2仅上传未启动，v3准备最终专项/全量/打包及独立MySQL。生产保持、真实模型/工具0，无HOLDOUT或自动采用。


### Codex 进展：T3-H2B样本顺序兼容性补强待最终回归（2026-10-10）

v3已进入专项业务测试，尚未记绿。静态复核统一派发/评分的样本ID字符顺序及角色序号，避免MySQL0900ai_ci大小写/标点排序不同造成批次误拒；新增混合ID顺序与四并发同键前沿封存检查。v4执行最终专项/全量/打包，v1编译失败与v3源码/报告独立保留，v2仅上传未启动。独立MySQL验证V74微秒/大字段/唯一/FK/事务回滚及双连接原评分创建，均待完成；未推进绿色源码/生产部署，真实模型/工具0。


### Codex 进展：T3-H2B最终专项219项通过，完整回归进行中（2026-10-10）

最终v4远程专项219项/10类全部通过，0失败/错误/跳过，完整回归/打包仍运行，未推进源码。独立MySQL8.4.11/0900ai_ci的V74微秒/大字段/唯一/FK/回滚与双连接原评分创建1/0通过，并复验预算快照12/当前读1；隔离MySQL Job/Pod清理并确认关闭。v3专项217已绿，因统一样本顺序的v4接替，主动按UID先删Job所有者再删原Pod以停止其未完成全量；full=INTERRUPTED_NOT_VERIFIED、package=NOT_RUN，不记全量绿。v1编译失败保留，v2仅上传未启动；生产Flyway70/团队表0、真实模型/工具0，绿色源码仍740基线，最终候选742源/10路径。待最终完整门禁、清理、Secret RAM扫描、归档本地核对后才推进。


### Codex 进展：T3-H2B第四版完整809通过，封存当前读补强待专项验证（2026-10-10）

第四版最终219专项/10类、完整809/102类及打包全部0失败/错误/跳过。复核MySQL RR封存事务发现当前执行状态与旧快照费用/评价状态可能不一致，封存改共享预算锁→执行锁，与Gateway同序；终态执行、费用预留和TASK在途量采用当前读，防止遗漏刚结算费用或完整未评分批次。第五版仅TeamEvolutionScoringService与执行集成测试两路径较第四版变化，其余740源保持；新增等原网关预算锁并纳入其完成费用的并发检查、独立MySQL双连接0旧快照/1实际费用及完成状态证据。补强后重跑220专项和打包，不重跑全量；809是补强前结果，需明确区分。新真实模型/工具0、生产保持、未源码推进/部署。


### Codex 进展：T3-H2B平台评分与可信前沿最终闭环（2026-10-10）

T3-H2B已完成[平台完整批次评分与可信前沿](TEAM_EVOLUTION_SCORING.md)：原两角色/完整交接/正文摘要、SETTLED原预留和唯一settlement及六预算归属项共同核对；EXACT_TEXT_V1精确比较最终输出、固定完整样本分母及精确比例质量门槛。V74新增样本首次准备检查前的holder责任起点，统计至第二角色确认的微秒整任务时延，旧回执缺起点不回填。统一样本字符顺序不依赖数据库排序；默认关闭的新评分与持久前沿，根原键/主体/候选/评分不可替换。封存前阻断RUNNING、未评分完整批次和DISPATCHED费用，封存后阻止新候选/新验证/新派发；UNKNOWN及预留保持不完整，不退款/零补齐，不自动采用。后端复现T3-F规则，未直接调用Python模块或改生产GEPA。

第四版远程专项219项/10类、完整809项/102类及打包通过，失败/错误/跳过0。最终第五版补强封存MySQL当前读：共享预算锁按Gateway同序、原执行完成状态当前读、费用预留及TASK在途量当前读，避免RR旧快照漏算新结算/漏掉完整未评分批次；补强后专项220项/10类及打包通过，0失败/错误/跳过，完整回归未在第五版重跑，809结果属于补强前第四版。其余740源相同，两路径变化独立核对。第三版专项217项通过，全量运行中因最终排序补强版接替而按原UID停止，full=INTERRUPTED_NOT_VERIFIED、package=NOT_RUN，不记全量绿。首轮testCompile因夹具JsonNode/ObjectNode错误失败，业务测试未运行、full/package未运行；原日志/源码保留。v2仅上传未启动。独立MySQL8.4.11/0900ai_ci检查V74微秒/大字段/唯一/FK/回滚通过，双连接根锁原评分首创建1/重复0，并复验快照12/当前读1及封存费用快照0/当前1、执行旧RUNNING/当前RECEIPTS_COMPLETE；这些是SQL夹具，不代表Spring真实MySQL业务集成、真实Actor或Pod崩溃。

[证据清单](evidence/eap-team-scoring-20261010/manifest.json)、[最终回执](evidence/eap-team-scoring-20261010-final.json)：归档1598726bytes/SHA256 c0de5b3025135653ea834dcb459c92ccccc62202ddb7ae25b260559cc44f81af，500摘要、10最终源副本/三历史版本源及742完整源本地核对；源manifest SHA256 57182ce264d4364b8464f16e21c06a1a5d61c58f27cd8a31f963f87fe8f47b10。本轮6Job/6Pod按UID清理剩余0；接收核对后才精确10路径推进/root/eap-src。节点/root/eap-t3h2b-20261010-v5/final-completion.json completed/closed/sourcePromoted=true、deployed=false。原33表2784行及6表/费用/认证/UID/模板/全部镜像和环境保持，实际Secret仅节点RAM扫描。生产仍Flyway70/团队表0，新生产预算/真实模型/工具0；未构建应用镜像/部署/浏览器，未commit/push，旧661/45/320/703/22/439证据与AGENTS/BP/其他线程保持。

**下一步T3-H2C：独立基线/所选候选HOLDOUT批次、全根消费闭环，再准备有界真实团队验收。** 当前前沿只有验证结果，留出选择不是采用，尚无独立HOLDOUT/真实团队端到端或稳定收益。HOLDOUT样本在原公开合同内已可由VIEW_CONTENT读取，不能宣称标签从未公开；需独立新槽位与冻结选择，不混入验证前沿或据留出再选候选。真实业务任务、新独立预算和共享容量仍待明确，旧127次/11371输出与128/32768上限不变，剩余1次不是授权；不改旧/共享caps/revision、不拆根或重放UNKNOWN/FAILED/KEEP_BASELINE或自动采用。主线未全部完成，T2/T4–T8剩余项保持。旧Job/export/promote不得重跑。


### Codex 进展：T3-H2C独立HOLDOUT与全根消费待远程验证（2026-10-10）

新增封存选择后的独立BASELINE/SELECTED HOLDOUT批次：即使所选为基线，也冻结两套不同评价/原槽位/Trace；原期限/预算/身份epoch不刷新，复用原Gateway结算与整任务责任时延评分，验证前沿只读VALIDATION，不据留出再选。新增默认关闭开关及原键最终根消费关闭，包含所有已发生验证/留出费用和未知预留；关闭不退款、阻止新派发，不自动采用、不宣称稳定收益。V75/V76仅分类旧验证并新增未来阶段约束/表，不回填旧UNKNOWN回执或预算。首轮远程专项241项/10类、0失败215错误：H2 ADD COLUMN重建表后自动约束名变化导致迁移初始化失败；full/package均NOT_RUN，原源码/日志保留，生产保护PASS。已调整约束发现顺序，第二版746源/14路径专项/全量/打包及隔离MySQL Spring业务夹具待完成；未推进绿色源/部署，真实模型/工具0。


### Codex 进展：T3-H2C迁移引用根因已证实，最终版待回归（2026-10-10）

第二版专项242项/10类、0失败216错误，full/package均NOT_RUN，仍是H2约束删除初始化失败；第一轮关于“新增列使约束改名”的推断不成立，前文记录为当时判断。远程最小H2 2.3.232对照实际返回大写CONSTRAINT_3：反引号变为小写报90057，双引号成功。迁移已按MySQL反引号/H2双引号分别处理。最小诊断首轮依赖路径遗漏repository导致未执行，第二轮正确路径对照成功；均保留并按UID清理。第二版隔离MySQL8.4.11/0900ai_ci/RR的8项真实Spring服务链路夹具通过，包含独立执行/评分/并发登记/原根费用当前读；Mock Runner/上游/机器认证，不是实际Actor或模型。最终版补足10个留出样本/原42调用计划与冻结账户不一致检查，另核对UNKNOWN预留等于原未知bound之和；待最终专项/全量/打包及MySQL复验，未源码推进/部署、真实模型/工具0。


### Codex 进展：T3-H2C外键索引对照已通过，第四版完整门禁进行中（2026-10-10）

第三版专项249项/10类、0失败25错误：H2新唯一约束/候选FK保留旧两列唯一索引，独立HOLDOUT插入被拒；full/package均NOT_RUN，原报告/源码保留。远程最小三方式SQL对照：先新约束后删旧约束失败，仅先删旧再新增仍失败，先暂移候选FK→替换唯一约束→恢复同一FK通过，最终迁移H2分支按实证修改；MySQL始终保留FK及覆盖索引。迁移断言新增两套留出实际插入及旧VALIDATION重复拒绝。第三版隔离MySQL9项Spring业务检查全部通过，第四版746源/14路径专项、完整回归、打包与MySQL9项复验进行中，绿色源仍742，未部署、真实模型/工具0。真实验收候选方案已写入TEAM_EVOLUTION_LIVE_ACCEPTANCE.md，公开工单分类候选24调用/3072输出/工具0/离线提案，具体业务任务和新独立预算/共享容量仍待明确；方案不是支出授权。


### Codex 进展：T3-H2C第四版专项249通过，原始MySQL时钟精度补强（2026-10-10）

第四版专项249项/10类通过，0失败/错误/跳过；已进入全量。复核发现原TIMESTAMP(6)列配CURRENT_TIMESTAMP写入在MySQL实际只保留整秒，手工注入微秒的既有夹具不能证明原始写入精度。执行责任/派发/回执时间与共享账本reservation/settlement的三处新时间写入改为CURRENT_TIMESTAMP(6)，不重写历史行；增加原始执行/责任/派发/账本时间带小数秒、实际整任务时延为正的业务检查。第四版未完成全量按原UID先删除Job所有者Orphan再删除Pod，保留部分报告，不记完整绿；package未运行。第五版将重跑最终专项250项、完整回归/打包和MySQL10项业务夹具，尚未推进绿色源/部署、真实模型/工具0。


### Codex 进展：T3-H2C最终专项250与真实MySQL业务十项通过（2026-10-10）

第五版最终专项250项/10类、隔离MySQL8.4.11/0900ai_ci/RR真实Spring服务链路10项全部通过，0失败/错误/跳过；包括原始执行/责任/派发/账本小数秒及实际时延、10样本/原42调用上界、独立两批次/冻结选择、成本完整性和共享预算当前读。真实Runner/模型/机器认证仍为Mock，不是实际Actor端到端。完整回归/打包进行中，未推进绿色源/部署；保护与最终UID清理/归档待闭环。原四版报告保留，第四版249专项绿、全量按UID主动中止不记完整绿；生产实际模型/工具0，旧预算/身份/镜像保持。


### Codex 进展：T3-H2C独立HOLDOUT与全根消费最终闭环（2026-10-10）

T3-H2C已完成[独立HOLDOUT与全根消费](TEAM_EVOLUTION_HOLDOUT.md)：先封存验证选择，再登记独立BASELINE/SELECTED批次；所选为基线仍用两套原评价/槽位/Trace。复用完整两角色/交接、原Gateway SETTLED账本及责任时延评分，HOLDOUT不混入验证前沿或据此再选。原主体/键/期限/epoch/预算不刷新，关闭当前读核对全部根消费和三TASK账户，UNKNOWN预留保留、不补零/退款；阻断RUNNING、完整未评分及在途费用，关闭后阻止新执行，原键只回放元数据。默认关闭，不自动采用，单次支持不等于稳定收益；公开HOLDOUT标签不宣称盲测。

最终第五版远程250专项/10类、840全量/102类、打包及隔离MySQL8.4.11/0900ai_ci/RR真实Spring业务10项全部通过，失败/错误/跳过0。微秒原始写入改显式CURRENT_TIMESTAMP(6)，不只手工时间夹具；10样本/42调用计划、独立完整分母/冻结选择、原账本费用、共享预算当前读/并发和集中权限通过。前两轮241/215错误、242/216错误为H2反引号折叠大写原约束名；第三轮249/25错误为H2候选FK保留旧唯一索引；均full/package未运行，原源码/报告保留。最小诊断首路径错误未执行SQL、后两轮证实双引号及FK/索引顺序；第四版249专项通过，全量被精度修正版接替后按UID主动停止，full=INTERRUPTED_NOT_VERIFIED、package=NOT_RUN，不记完整绿。最终MySQL为真实Spring服务链路、Mock Runner/上游/机器认证，不是实际Actor/Go Runner/真实模型或Pod崩溃。

[证据清单](evidence/eap-team-holdout-20261010/manifest.json)、[最终回执](evidence/eap-team-holdout-20261010-final.json)：归档3033508bytes/SHA256 29bbc2cd53017ac75f4662bde4eea5fc3fe40a92673a843a04d3025297b6f716，560摘要、14最终源副本/四历史版本源及746完整源本地核对；源manifest SHA256 2e79fc1f1c5094160c1a42ed9612ab696bb61a10dc7aee2ffeac581cf107efbc。12Job/12Pod按原UID清理剩余0；接收核对后才精确14路径推进/root/eap-src。节点/root/eap-t3h2c-20261010-v5/final-completion.json completed/closed/sourcePromoted=true、deployed=false。原33表2784行及6表/费用/认证/UID/模板/全部镜像/环境保持，实际Secret仅节点RAM扫描。生产仍Flyway70/团队表0，新生产预算/真实模型/工具0；未构建应用镜像/部署/浏览器，未commit/push。既有500/661/45/320/703/22/439证据、AGENTS/BP/其他线程保持。

**下一步T3-H2D：具体任务/新预算明确后的有界真实团队验收。** [候选方案](TEAM_EVOLUTION_LIVE_ACCEPTANCE.md)已准备公开工单分类、两角色/两候选、24模型调用/3072输出/工具0/离线提案，但不是支出授权；实际业务任务、新独立预算及共享容量仍待明确。旧127次/11371输出和128/32768上限不变，剩余1次不是新授权；不改旧/共享caps/revision、不拆根、不重放UNKNOWN/FAILED/KEEP_BASELINE或自动采用。当前隔离团队链路通过，真实Actor/模型端到端与稳定收益尚未验证，整条主线仍未完成，T2/T4–T8剩余项保留。旧Job/export/promote不得重跑。

原始节点完成回执：[remote-final.json](evidence/eap-team-holdout-20261010-remote-final.json)，367bytes/SHA256 8f24f757580e177f9b465f5813404c184ff525d92900ceab8e78c1ab8592c919，实际文件原字节独立核对通过。


### Codex 进展：T7-E任务与对话原始观测登记隔离闭环（2026-10-11）

用户要求继续完成全部主线；本轮先补齐[工作流模型任务与对话的原始观测登记](RUNTIME_OBSERVATION_COVERAGE.md)，不宣称整条主线已完成。默认关闭；新工作流/计划常规模型节点在原提交事务冻结完整路径分母/平台Trace，未执行节点不丢失，原重放不重复登记。原身份/准备/责任屏障后、实际HTTP发送前冻结UID/模板/端点及期限；Runner原Trace贯通模型/Agent Span/回执。新对话逐POST登记，未知不自动重试；旧/不匹配回执及身份漂移单列。失联超过原期限只读显示UNKNOWN，不重写费用/回填旧数据、不接管或续授权。

远程后端专项110项/8类、全量850项/103类及打包通过；前端224项及类型/生产构建通过；Runner普通/竞态各37顶层/73含子项及编译通过。全部0失败/错误/跳过。隔离MySQL8.4.11/0900ai_ci/RR真实Spring20项/2类通过，包括原团队HOLDOUT/费用闭环10项和新任务/对话实际HTTP客户端10项。后者Runner为loopback夹具、身份为Mock，Go测试也不是实际Actor/真实模型或Pod崩溃验收。

[证据清单](evidence/eap-runtime-observation-20261011/manifest.json)、[最终回执](evidence/eap-runtime-observation-20261011-final.json)：归档7173482bytes/SHA256 aea34832d5e08872715faa704af6a0b3f8499fc1dfd49dc5a991a6d84ef2956b，280摘要、11源副本及749完整源核对；sourceManifest SHA256 ee1e4200b9ec174c3f11e6401997b060fa95b726a699b4cfd0ea720fa285cbc4。4Job/4Pod按冻结UID清理剩余0；实际Secret仅节点RAM扫描，原33表2784行+6表、费用/身份/UID/模板/全部镜像及环境保持。接收核对后精确11路径推进/root/eap-src，节点/root/eap-t7e-20261011/final-completion.json completed/closed/sourcePromoted=true、deployed=false。生产仍Flyway70/团队表0，真实模型/工具0，未构建应用镜像/部署/浏览器、未commit/push。原3250证据摘要、AGENTS/BP/其他线程保持。

只读最新全局模板盘点71个、39个旧共享凭证引用、22条旧发布；全局与各作用域数量一致，不证明挂载、黄金快照、外部消费者或分页完整。未迁移、未退役共享凭证。

真实团队验收的公开工单分类/两角色/24调用/3072输出/工具0/最多900秒新独立计划及实际工具/旧消费者迁移范围已向用户发出明确问题，目前尚无答复，不能视为授权。通用任务/对话新功能部署与真实验收、T2可信租户/资源配额与跨入口调度、T4/T5业务工具/旧消费者迁移、T6恢复/取消/补偿、T7独立指标/日志/告警/规模以及T8统一策略继续保留；稳定收益尚未验证。旧127/11371实验计划及128/32768边界不扩大，不重放历史UNKNOWN/FAILED/KEEP_BASELINE。旧Job/派发/export/promote不重跑。

原始节点完成回执：[remote-final.json](evidence/eap-runtime-observation-20261011-remote-final.json)，419bytes/SHA256 786b77c224af0340eae682eb4f0e335a0103245d9aa6d149fd75f7777b1d4c02，实际原文件字节独立核对通过。


### Codex 进展：T3-H2D真实团队验收已获继续授权（2026-10-11，进行中）

用户在明确列出公开工单分类、两角色、最多24次模型调用/3072输出Token/工具0/最多900秒新独立计划，以及仓库现有工具和旧发布迁移范围后回复“继续”。本轮据此执行该具体方案；前段“目前尚无答复”是此前时点记录，保留原文。此授权不扩大旧127/11371计划与128/32768边界，不授权重放UNKNOWN/FAILED/KEEP_BASELINE、修改历史发布或退役未盘清的共享凭证。先核对749绿色源和实际容量、构建部署后端、两套新专属平台实例就绪后才建立原绝对期限计划。验收与费用结果尚待实际执行；不宣称主线完成。


### Codex 进展：T3-H2D首槽位停止与根因复现（2026-10-11，修复验证中）

749绿色后端已定向发布，实际Pod imageID、Flyway77、团队开关及旧39表/身份/环境保护通过；T7-E观测开关保持关闭。新Agent64/65专属RUNNER及profile核对通过。首套原凭证已ACK，恢复休眠遇Substrate ABORTED，FAILED步骤保留；只用同部署/版本/UID核对已ACK连接，没有重发创建或凭证。新root1独立预算24/3072/工具0及绝对期限已冻结、离线单角色候选登记；基线首槽位UNKNOWN即停，另5槽位NOT_DISPATCHED，候选验证/HOLDOUT未派发。Gateway输入治理PROCEED、实际载荷摘要匹配，但无预算预留/结算或模型事件，共享模型160/输出13275/工具10保持；不把业务UNKNOWN填成成功。两新原UID已休眠/身份撤销。

原关闭POST因TEAM_SEALED_FRONTIER_REQUIRED拒绝，原请求键保留。独立MySQL8.4.11/0900ai_ci/RR上海时区复现：JPA UTC Instant期限有效，普通JDBC读取偏移8小时，造成团队准入失败；原UTC夹具未覆盖。修复前4项为2失败/2业务错误/0跳过，日志及源保留。修复针对身份表三个Instant按UTC读取，不改JDBC管理的计划/责任时钟、数据库时区或旧行；无前沿/基线评分可关闭INCOMPLETE_NO_ADOPTION，选择为空、不造评分，保留UNKNOWN预留及RUNNING/完整未评分/费用在途屏障。750源远程专项、全量/打包、UTC/+08回归进行中，尚未记绿或发布修复。root1不换键重跑、不拆根花剩余额度；完整真实团队/留出/收益尚未验证。


### Codex 进展：T3-H2D首槽UNKNOWN定位、时区/关闭修复部署与原根闭环（2026-10-11）

最新T3-H2D（2026-10-11）：[真实团队验收](TEAM_EVOLUTION_LIVE_ACCEPTANCE.md)已完成本次失败诊断、修复部署和原根关闭；**真实模型端到端验收未完成，主线未全部完成。** 用户“继续”已确认公开工单分类、独立24调用/3072输出/工具0/最多900秒及仓库工具/旧发布迁移范围。生产root1首次原槽位UNKNOWN即停止，后5槽位未派发，候选验证/HOLDOUT未执行。原Gateway预占/上游消费0，账本与PLATFORM增量0；原UNKNOWN保留，原关闭键返回INCOMPLETE_NO_ADOPTION、无前沿/选择，不续期、不替换根或补分。

实测定位JPA以UTC保存的身份Instant被上海时区普通JDBC读早8小时，Gateway在预占前拒绝；另修复未封存前沿导致首次失败无法关闭。原MySQL红测4项（2失败/2错误）保留；修复远程209专项/7类、853全量/103类及打包通过，MySQL8.4.11/0900ai_ci/RR的UTC23+上海4项通过，全部0失败/错误/跳过。后端固定为`10.186.133.66:5001/eap-backend@sha256:3b3b1cebe23fd98e3d32c8d3f421721f5228be39a3409aa0734104057a731db8`，实际Pod imageID/健康/Flyway77通过；五个团队开关启用，T7-E runtime-denominators仍false，前端/Runner/AX/optimizer保持。

[验收索引](evidence/eap-team-live-20261011-index.html)、[最终回执](evidence/eap-team-live-20261011-final.json)：归档1094029bytes/SHA256 6af35d677757407ec415e96b45987c8cd2bf61c271c144715674ff899c42c9b4，322摘要/4源副本/750源码与4009旧证据核对。实际Secret仅节点RAM扫描；39表2831原行/旧UID/模板/身份/旧caps/revisions保护通过。新Agent64/65原UID休眠、身份撤销，5Job/5Pod按UID清理剩余0；接收核对后才精确4路径同步/root/eap-src，节点completed/closed/sourcePromoted=true、liveAcceptanceComplete=false。原127/11371实验计划及128/32768边界保持，PLATFORM160调用/13275输出/10工具、预留0未变。未commit/push。

下一步是修复后的新独立真实团队验收；须另行明确新的独立支出，不能沿用已到期root1或把其剩余额度转移。仓库现有工具/旧发布迁移范围已确认，尚未迁移；T2可信租户/资源配额与跨入口调度、T4/T5执行侧/旧消费者、T6恢复/取消/补偿、T7实际任务/对话/AX LLM与独立指标/日志/告警/规模、T8统一策略仍待，稳定收益未验证。以下旧阶段的未部署/未授权描述保留其当时含义，当前以本条及实际回执为准。

节点原始最终回执独立接收1120bytes/SHA256 329228410aee8399ec32520bb7a87923395f33a6065b8f33ea9b21750598f597；最终750源manifest SHA256 55eba48ed47586fa35be473d2333e528d8a50a0607d28e3c3464c8f2af0e835a。不可变归档保留导出时sourcePromoted=false，接收核验后的节点/本地最终回执sourcePromoted=true单列，不回写旧归档。PROJECT_MEMORY前486391字节SHA256 0ef1dce2bbeb86e224929d3d36ed9c9cad3d0d41b5caf7cffac991cf20987047保持；AGENTS原SHA256 80db1f747231f878bf2da42806bd91b14f29e4efe89c87331177f287fc786ff4保持。原首次身份配送ACK后睡眠发生another operation in progress的FAILED历史保留，只对同UID原v1做元数据重协调；没有重发凭证或改旧模板。真实上游、完整HOLDOUT、Pod崩溃及浏览器仍未验收，不自动采用。


### Codex 进展：仓库工具与旧发布当前迁移队列已核对（2026-10-11）

用户已确认迁移范围为仓库现有工具和旧发布，已完成[只读盘点与具体顺序](LEGACY_CONSUMER_MIGRATION.md)。73模板中39仍引用旧共享凭证；34模板对应35个当前可见Actor，均SUSPENDED，其余5模板未见当前Actor引用，不等于无快照/外部消费者。全局/各作用域模板数量一致，分页完整性、挂载内容及快照仍未证明。

完整发布39条，旧身份历史22条（READY17/FAILED5），涉及13个Agent；按每个Agent最新版本核对后为12个READY当前迁移候选、1个FAILED，无最新版本已迁移。RUNNER共10个：Agent5/8/9/43为v2，Agent10/11/14/16/17/45为v1；IMAGE两条Agent12/44/v1须先验身份协议，不能直接改成Runner。Agent7/v6/SOURCE仍FAILED，不自动重试或回退早期READY。历史22条不能作为新部署队列。仓库执行工具仅calculate_sum及controlled_counter_increment/poc-counter-v1；MCP发现/allowlist/观测不是新效果适配器，新UID不能继承旧工具根授权/恢复责任或重放UNKNOWN。

两份实际Secret节点RAM扫描后的清单独立接收：模板53103bytes/SHA256 3908066c29977fa67d2c9699ec5efddd88c8c527668989cfea38fea5e4c63ec8；当前发布22752bytes/SHA256 9711fa29a9a292163cbb69ffd4778ac4059f29cbc444e0fb9f15fdb64dff7ca5。证据为docs/evidence/eap-mainline-migration-{inventory,heads}-20261011.json及对应只读脚本。没有迁移/部署写入、唤醒旧Actor、退役共享凭证、模型或工具新增消费。T3修复后的新独立24调用/3072输出/工具0/最多900秒验收授权问题已发出，尚无答复；root1原UNKNOWN和已到期预算保持关闭，不重试、不转移剩余额度。主线仍未全部完成，不commit/push。


### Codex 进展：T3-H2E新独立真实团队采样与原失败关闭（2026-10-11）

用户“继续原来的主线任务”后按前述独立上界24调用/3072输出/工具0/最多900秒执行新公开合成工单cohort；旧root1保持UNKNOWN/已关闭，未复用原预算或Actor。先核对固定修复后端实际Pod imageID、750源码、Flyway77、共享容量并冻结53表历史/原UID/模板/旧批准与费用基线。两套新Agent66/67专属配送、固定Runner、EMPTY/temp0/toolsDISABLED/profile通过。

root2原基线6/6槽位确认，候选3槽位后FAILED/PRE_DISPATCH_REJECTED：原提取调用finishReason=length且outputCharacters=0，空交接在下一角色派发前拒绝。实际9模型调用、741输入/737输出Token、UNKNOWN0、预留0、工具0，未运行HOLDOUT；原根已关闭INCOMPLETE_NO_ADOPTION，未重试/换根/补分/采纳。9条Gateway与9个Agent Span均按原Trace/Agent/runtime端点一一对应，原账本及PLATFORM增量匹配；仅已发生调用的关联闭环通过，完整团队验收、独立HOLDOUT及稳定收益未验证。runtime-denominators仍false。四角色真实PKCE的metadata/closure200，content仅governance/admin200，其余403；runAgentExperiments仅admin。

归档209570bytes/SHA256 8e3c1bb84dc0a8fb43d58f039bd011cfc5c68869b8f117b9a6977c44146893b6，26文件摘要/750源码/4340旧证据本地核对PASS，实际Secret及编码仅节点RAM扫描。53表历史保护通过，Agent66/67原UID均休眠/身份撤销，未创建Job/Pod；证据docs/evidence/eap-team-live-fixed-20261011及-final.json。PLATFORM最终169调用/14012输出/10工具、预留0，旧127/11371实验计划及128/32768边界未改。应用源码/镜像/开关本轮未改，无新增构建测试（沿用已部署修复版853全量/27MySQL基线），全部业务执行远程，无commit/push。

继续已授权仓库工具和旧发布迁移，先10个READY RUNNER零模型身份/profile迁移，再2个IMAGE兼容性核对；Agent7最新FAILED保持、不重试。整个主线仍未完成；失败不构成自动开新模型实验或提高输出上界的授权。T2/T4/T5/T6/T7/T8其余项按实际证据推进。


### Codex 进展：10个旧RUNNER发布专属身份迁移完成（2026-10-11）

按用户已确认的仓库工具/旧发布范围完成10个当前READY RUNNER迁移：Agent5/8/9/43 v2→v3，10/11/14/16/17/45 v1→v2，均独立新意图、新UID、新模板、PER_INSTANCE_V1/READY；固定Runner2146291f…，原提示词/模型/规格/沙箱/出站/env逐项保持。实际配送ACK、机器认证、profile、原UID及工具策略关闭通过，最终10个新原UID全部休眠/连接REVOKED，不继承原工具grant/根额度/恢复责任，旧Actor均保持休眠。首个助手使用profile前的状态跳过PAUSE，在最终断言处停止；实际profile会唤醒实例，已修正为请求后重新查询同UID再休眠，不重发部署或业务意图。全部实际模型/工具新增0，预算账户完整相等。

两个IMAGE原程序静态固定manifest/config/layer及二进制摘要核对：Agent12/v1 demo-agent和44/v1旧Runner均缺V1配送协议标记，未运行镜像/唤醒旧Actor/创建失败新发布，也没有改成RUNNER。须先确认原源码与业务合同再保留程序适配；Agent7最新v6 SOURCE FAILED未重试。旧模板/快照/挂载/外部消费者尚未证明完整覆盖，共享凭证没有退役；整个迁移和主线仍非全部完成。

远程53表3036原行、117原Actor/75原模板、两个团队根关闭、旧UNKNOWN、完整预算保持；750源码不变。归档190636bytes/SHA256 774c12e535ab5141c6ae79213ca622a49dd9674d000262485b06a40dc24499f0，16摘要/750源码/4368旧证据本地核对PASS；实际Secret只节点RAM扫描。本轮不改业务源码/应用部署，未新增构建测试；实际10个新实例及只读profile验收均远程，未commit/push。证据docs/evidence/eap-legacy-migration-20261011及-final.json，合同LEGACY_CONSUMER_MIGRATION.md。后续继续T7-E已测试Runner打包及可信任务/对话分母启用，付费模型成功路径仍需新的明确独立上界。


### Codex 进展：T7-F任务/对话可信分母及新Runner默认上线（2026-10-11）

继续主线在远程复用T7-E已通过普通/竞态各37顶层73含子项和编译的精确二进制7a7b6ac38bab28d5e5eab081b53188174f28b3ed0690e4c60f3fa162e3802851，先核对完整Runner源码与既有测试/产物，Kaniko打包后固定manifest/config/layer/可执行权限与二进制一致。新未来默认Runner=10.186.133.66:5001/eap-runner@sha256:43e112555b4dfdb67a79b0869ecad1290192a3e3cfeeb342d1dc0006d7feb83c；同时用resourceVersion及旧值CAS启用EAP_OBSERVABILITY_RUNTIME_DENOMINATORS_ENABLED=true。后端镜像仍3b3b1ce…，真实新PodUID2697cd03-640b-43c3-b894-a9ad3eb5408d/imageID/健康读取/Flyway77通过，四角色真实PKCE可读新任务/对话原登记指标0，旧外部入口分母null保持。

只修改两个公开配置字段，旧UID/Actor/template/批准/预算/两个团队根关闭、认证/配送Secret和其它镜像/环境保护通过，业务源码750未改。既有10个迁移Runner保持原2146291f…版本，不通过默认变化冒称旧实例已升级。SOURCE/IMAGE/旧外部程序亦未自动替换。没有重复无变化的已有测试；本轮远程新镜像打包/registry/实际Pod/四角色API核对，模型/工具新增0，任务/对话/AX真实模型成功路径尚未验证。

归档190364bytes/SHA256 2f120392bf2307b24ee7c0ce85a9fd8cb281b654f8d5774160603945ff4bf35c，27文件摘要/750源码/4386旧证据本地核对PASS，实际Secret及编码只节点RAM扫描。唯一构建Job/Pod按冻结UID清理剩余0；节点/root/eap-t7f-20261011/final-completion.json completed/closed/sourcePromoted=true，deployed=true，runtimeDenominatorsEnabled=true，mainlineComplete=false。证据docs/evidence/eap-runtime-denominator-release-20261011及-final.json，配置补丁deploy/substrate-poc/runtime-denominator-backend.json；不整份apply旧清单，无commit/push。后续先真实新实例零额度拒绝和未执行分母验证；完整付费团队/HOLDOUT/任务/对话/AX成功与独立指标/全文日志/告警/规模仍待。


### Codex 进展：T7-G 真实新 Actor 零额度拒绝闭环（2026-10-11）

继续主线，未将负向验收当作真实模型成功。Agent68/原 UID 5c7abe79-6d3f-4911-9574-7ad84ebc4320 使用 T7-F 新 Runner，MODEL_CALLS/OUTPUT_TOKENS/TOOL_CLAIMS 三账户均限额0。新工作流28/run wf-exec-597e00c8-3483-4a51-95f6-4df23a5366be 原登记两节点，首节点真实Actor HTTP200/FAILED，后续未派发仍保留；新对话原POST仅一次，HTTP400，原EXECUTION_UNKNOWN保留。两条实际AGENT/FAILED Span与原Trace/runtime端点对应，四角色coverage读取及原登记/派发/回执事件核对通过；模型事件/预占0、无上游/工具消费，不把缺失证据显示为完整成功。新原UID已暂停、连接撤销，三零额度不得提高后复用，无重试、临时Job/Pod或应用源码/部署变更。

远程完成/关闭回执 docs/evidence/eap-runtime-zero-quota-20261011-final.json；归档193256bytes/SHA256 e81c06f23f4d7369af2cc0c5a2fda4bba87c243dc7c61f62a521fb548e427076，23摘要/750源/4415旧证据/Memory原500237bytes及AGENTS本地核对通过。53表3086原行、127旧Actor、85旧模板、旧账户及root1/root2关闭结果保持，Secret仅节点RAM扫描。只在远程执行实际验收，未重复既有测试，无本机构建/测试，无commit/push。

真实任务/对话/AX LLM成功路径、完整团队HOLDOUT与稳定收益仍未完成。docs/NEXT_LIVE_ACCEPTANCE_PLAN.md 已形成27调用/9216输出/工具0新独立计划并询问支出授权，尚未创建新付费预算或调用；等待期间继续旧IMAGE原程序/源码核对与兼容适配。10个RUNNER已迁移，Agent12/44 IMAGE待适配，Agent7最新FAILED不重试，共享凭证不退役，T2/T4/T5/T6/T7/T8剩余项保留。


### Codex 进展：两个旧 IMAGE 原程序兼容与专属身份迁移闭环（2026-10-11）

当前旧 READY 发布头迁移核验12/12完成（10 RUNNER＋2 IMAGE），不等于共享凭证退役或全部主线完成。Agent12/v1→v2仍IMAGE，保留原eap-demo-agent任务计数、同键去重及会话内存，新增runtime/demo-agent七文件的V1身份适配；身份实现为现有Runner原样副本，日志不记录目标正文，V1下状态/睡眠也要求凭据。原源码SHA256 9aa9009a0206741297bc56404c71e93e94d811b90382086894d0c573152a64aa；原固定镜像基础构建新镜像10.186.133.66:5001/eap-demo-agent@sha256:558cfe8d29b9d230e88263fceb069c0ea89ca581eca924031497f1a3123df7f9。Agent44/v1→v2仍IMAGE，其原程序即同系列EAP Runner，默认配置和请求/响应隔离对照通过后使用已验证43e11255…镜像。端口/readiness/cmd/env/规格/沙箱/出站配置保持。

从固定原镜像manifest/config/layer提取的原二进制在隔离Pod对照新程序，演示任务/重复键/会话及Runner默认模型/提示词/max_tokens/任务/对话响应一致；所有Gateway流量只到Pod loopback夹具，无真实供应商。V1伪造签名/跨Agent/UID漂移/缺投影/过期/迟到旧配送与并发同键合同普通/竞态各8项0失败/跳过，编译及远程gofmt核对通过。实际两个新Actor加密配送/原UID确认，Agent44实时默认profile通过；Agent12新UID0fd559f0-93a0-475a-9240-97a389fee632，Agent44新UID08b38a59-e66b-488d-b5b2-23f836f19d0c，全部SUSPENDED/REVOKED、工具策略关闭，不继承旧grant/根/责任。实际Actor上未发送业务任务，模型/工具消费0，不能将隔离对照称为真实模型成功。

最终回执docs/evidence/eap-image-migration-20261011-final.json，归档208225bytes/SHA256 8b24c70e61474907143bcffcf7d3a4361de0b08bd29c33212479d6dce88892d4。37摘要/7新源副本/750原源/4440旧证据/Memory原502006bytes/AGENTS核对后精确推进新增7源，绿色目录757文件，completed/closed/sourcePromoted=true。53表3119原行、128旧Actor、86旧模板、全部预算及两根关闭结果保持；2Job/2Pod按UID清理剩余0，实际Secret及编码仅节点RAM扫描。全部构建测试部署远程，未在本机运行构建/业务测试，无commit/push。

Agent7/v6/SOURCE原FAILED保持，共享凭证仍缺完整消费者/挂载/快照证据，不删除原模板/Secret或重跑历史意图。T7-G真实零额度拒绝通过，真实模型成功及完整团队HOLDOUT仍未通过；新27调用/9216输出/工具0计划已询问授权、未创建或调用。已只读确认otel-system现有Collector0.157.0/Prometheus3.5.3及OTLP metrics→prometheus管线、logs→debug，尚未接入EAP独立指标或建立全文日志存储。继续该方向及T2/T6/T8其它主线，稳定收益未验证。


### Codex 进展：T7-H 原登记独立指标采集闭环（2026-10-11）

继续主线，新增 ObservationMetricsSnapshotService/Exporter 与 11 个新合同用例、五行默认关闭配置。独立只读事务配置 2 秒期限，单线程固定延迟、窗口 1–1440 分钟；HTTP 整体 2 秒/响应4096/发送65536 bytes、集群或 loopback 固定路径、禁止跳转，旧 OTLP 依赖下按 wire 字段解析现代 partial_success。窗口 Gauge 只含固定入口/状态/证据标签，无 Trace/操作键/UID/正文；未知分母不发零调用值，查询或接收失败不发送空快照/重发业务。事务期限不是连接获取与整个查询的严格墙钟保证。

远程 v3 专项79项/7类、全量864项/104类、打包全绿，失败/错误/跳过0。原首次辅助入口假设错误在Job前停止；v1漏POM在Maven项目前失败、v2不兼容固定旧Proto API在编译前失败，两者业务测试0，原诊断/源码/Job日志保留。镜像内及实际容器JAR与测试产物SHA一致；后端固定000b37429ab833518b83452ad1b1ad512642a57290ba5f3821f8cc679632b203，实际PodUID609d43db-c2dd-429a-94c5-4cde7fe37dd6、Flyway77和四角色API通过，未来Runner/前端/AX/optimizer不变。

独立查询首次无序列，最终确认Prometheus原keep只允许(substrate|otelcol)_.*；Collector实际已接收真实eap-observation数据并导出八类。只在同一规则增加八个完整EAP指标名，原固定promtool校验后CAS ConfigMap、挂载内容核对与SIGHUP热加载，Collector配置/部署、Prometheus Deployment和两个实际监控进程不重启，不放开重复的其它应用指标。初次Collector读取2MB上限的空结果不能证明无数据，后续完整读取纠正；诊断各一次独立Protobuf/JSON探针不使用原登记指标名、无模型/工具调用，证据保持。

实际Prometheus八类/74序列与1440分钟原报告全部对应，快照年龄约68秒；任务原分母2、对话1、未执行/FAILED/UNKNOWN保留，LEGACY_EXTERNAL known=0且registered序列缺席。基础设施namespace/pod/instance标签来自原抓取规则，不作为应用业务标签。本轮不证明长期可用性、生产性能、真实模型成功、全文日志或告警。

最终回执docs/evidence/eap-independent-metrics-20261011-final.json；归档1033824bytes/SHA256 13d0c9113a51b332a5bebc3dc64c969f4cbbc0512be5eb0ce9a425b89c76c2c8，295摘要/760源/4479旧证据/Memory原504839bytes及AGENTS本地核对后，仅推进四源码路径，completed/closed/sourcePromoted=true。53表3129原行、130旧Actor/88旧模板、全部183账户与两根关闭保持，4Job/4Pod按UID清理剩余0；实际Secret原文/编码仅节点RAM扫描。PLATFORM169调用/14012输出/10工具、预留0，本轮模型/工具新增0。全部编译、测试、构建、部署与诊断远程，无本机构建或依赖安装，无commit/push。

继续零模型告警工程；新27调用/9216输出/工具0真实模型计划仍待明确授权，不提高旧限额或重开root1/root2。共享凭证未退役，Agent7最新FAILED保持；完整团队HOLDOUT、真实任务/对话/AX LLM成功、全文日志/规模及T2/T4/T5/T6/T8其它项仍待，主线未全部完成。不要重跑已关闭T7-H操作，源码推进后不使用原757源保护函数；下一阶段重建760源/本次Prometheus白名单基线。


### Codex 进展：T7-I 集群内观测聚合告警闭环（2026-10-11）

继续主线，新增两份公开规则/合成夹具配置与OBSERVATION_ALERTS合同；无应用源码、镜像或数据库变更。规则限定原Collector抓取来源，只输出固定类型/owner/severity/七个固定入口；快照缺失/重复/180秒陈旧/超前5秒、八类74序列及1440分钟窗口、未知分母、观测缺口和执行UNKNOWN分别判断。旧外部known=0保留；PENDING不算缺口、FAILED不改UNKNOWN，陈旧快照抑制其它判断。各规则持续两分钟触发，无Alertmanager或外部通知。

原固定Prometheus3.5.3的远程promtool：v1五规则/17组100断言绿色且未发布；补固定七入口及异常入口夹具后，v2规则语法通过、夹具exec-i传输实际32768/375332bytes，直接文件摘要确认原始前缀，YAML解析失败、105断言尚未执行。原源码/日志/摘要保留。v3同一规则/夹具在独立目录以参数分块写入，容器内wc/sha256完整核对后五规则/18组105断言全绿，候选完整配置通过。无本地业务测试或依赖安装。

按原ConfigMap UID/resourceVersion及data CAS，仅增加eap-observation.rules.yml和rule_files引用，原主配置字节前缀保持。实际配置核对后一次SIGHUP，未重发发布/信号；原PodUID及Collector/Prometheus容器startedAt早于本阶段冻结时间，进程未重启。首次两分钟条件等待保留；最终全部health=ok，ALERTS三条与1440分钟持久原报告逐项对应：RUNTIME_TASK/CHAT EvidenceGap各1、CHAT OutcomeUnknown1，快照/集合/分母inactive。不新增业务请求或补造原Span，原FAILED/UNKNOWN及未执行保留。告警为窗口聚合，不宣称绑定原操作/事实版本的持久去重、确认/关闭审计或新告警界面完成。

最终回执docs/evidence/eap-observation-alerts-20261011-final.json；归档241178bytes/SHA256 8258564141086109a2f2bc8cccc8b89caf49a196c0890d2d22e28388828a7455，66摘要/760应用源/两配置/4777旧证据、Memory原508215bytes及AGENTS核对后仅推进两规则配置，completed/closed/configurationArtifactsPromoted=true，应用760源保持。53表3129原行、130旧Actor/88旧模板、183账户及root1/root2关闭保持，PLATFORM仍169调用/14012输出/10工具、预留0。六临时文件/三目录清理剩余0，无新Job/Pod或监控Pod删除；实际Secret及编码只节点RAM扫描，新增模型/工具0，无commit/push。

继续零模型原操作告警治理/全文日志工程；新27调用/9216输出/工具0计划仍待明确支出授权，不提高Agent68三零额度、不重开旧根或换键重做UNKNOWN。完整团队HOLDOUT、实际任务/对话/AX LLM成功、长期规模及T2/T4/T5/T6/T8其它项未完成，主线未全部完成。阶段已关闭，禁止重跑T7-I init/tests/publish/reload/finish/promote；不要复用T7-H原757源保护函数，后续以当前760应用源/两规则配置/已加载Prometheus规则另建基线。原导出false与核对后true回执独立保存，不回写归档。


### Codex 进展：T7-J 原操作告警事实与确认审计开始验证（2026-10-11，进行中）

T7-I 五规则/105项及三条实际聚合告警已闭环后，继续零模型的原操作持久告警治理。初版新增ObservationAlertService/Controller、V78三张独立元数据表与事实摘要，复用中央VIEW_CONSOLE/GOVERNANCE及/me能力；同原登记锁串行、同摘要不写、变化单调版本递增，确认绑定原操作/版本/摘要，旧版本拒绝，原UNKNOWN/身份/预算不改。A→B→A不能复用旧确认；证据缺口与执行未知独立，事实恢复/替代留审计，主体只存SHA256，不发送外部通知。无后台全平台扫描或新页面，不以外键阻止业务删除或级联删除审计。

766候选应用源、相对760绿色源九路径；新增17项真实Spring事务/投影合同、七项角色/请求合同与迁移不回填测试，独立MySQL继承17项并发合同。刚准备通过n1.sh远程验证；尚无测试绿色、镜像、部署或真实API结果，不能按计划标记完成。Memory原511289bytes、AGENTS和4846旧证据基线另立；模型/工具新增0，无本机构建、依赖安装、commit/push。新27调用/9216输出/工具0计划仍待明确支出授权，不重开旧根或提高旧限额。主线尚未全部完成。


### Codex 进展：T7-J 独立MySQL通过，迁移总数断言修正后重验（2026-10-11，进行中）

独立MySQL8.4.11/utf8mb4_0900_ai_ci/REPEATABLE-READ真实Spring17项全部通过，包含同原登记行锁并发去重、确认/版本/恢复/未知独立合同。初版H2专项97项、1失败/0错误/0跳过，唯一为FlywayMigrationTests.appliesAllMigrationsAndSeedDataOnH2仍期望77而实际新增V78后为78；全量和打包未执行，无镜像/部署。已保留原失败Job/日志/报告/九路径源码及初版manifest，仅修正这一断言，不改生产实现或迁移。MySQL17项的生产/测试执行路径逐字一致并记录复用证明；独立src-v2和新Job重新跑H2专项/全量/打包，结果尚待。766应用源、两份规则配置及全部旧保护基线保持；没有模型/工具新增，不能标记主线或本阶段完成。


### Codex 进展：T7-J 原操作告警事实与确认审计有界闭环（2026-10-11）

新增ObservationAlertService/Controller、V78三张独立元数据表及原覆盖投影Fact摘要，读取只读不补造登记。显式单原操作协调锁原登记、同摘要零写，变化单调版本；A→B→A仍为新版本，确认绑定原操作/版本/摘要且旧事实拒绝。证据缺口和执行UNKNOWN分别生成，PENDING/未执行不算缺口，确认仅ACKNOWLEDGED；事实恢复RESOLVED/变化持续SUPERSEDED均留审计，主体只存SHA256。复用PlatformPermission.VIEW_CONSOLE/GOVERNANCE与/me，无角色权限副本、自由文本或外部通知；无后台全量扫描或新页面，不以外键阻止业务删除或删除原审计。

首版远程H2专项97/8类、1失败/0错误/0跳过，唯一迁移总数仍期望77、实际新增V78后78；全量/打包未执行，原Job/日志/报告/九路径源与manifest保留。只修正Flyway测试断言后独立src-v2/新Job专项97/8类、全量889/106类及打包通过，失败/错误/跳过0。原独立MySQL8.4.11/utf8mb4_0900_ai_ci/RR17项全绿，逐源确认生产/迁移/所执行合同未变、仅未执行的总数测试变动后复用；包括真实行锁并发、版本/重放/恢复/未知独立与权限可见性。没有重测未变前端/Runner或浏览器。

测试JAR、镜像manifest/config/末层精确一致后仅CAS后端image；固定10.186.133.66:5001/eap-backend@sha256:5b5de3cf6030b8b7771804a16f3c68edacc7ea589ad42ca74f1350ffd0d6e30e，实际Pod/Flyway78通过，原环境/前端/两Runner/optimizer/Collector/Prometheus及两规则配置保持。迁移后三表空证明未回填；随后只对T7-G已有CHAT和失败RUNTIME_TASK原登记协调与确认，实际2头/3告警/6审计，重放新增0、旧版本409，四角色读200、viewer/operator写403、governance/admin与/me能力一致。原UNKNOWN/观测/费用不改，原独立指标新鲜且三条Prometheus窗口告警继续触发；确认不作为业务成功或告警消失。未新建业务请求、补Span或重放UNKNOWN。

最终回执docs/evidence/eap-operation-alerts-20261011-final.json；归档1198492bytes/SHA256 c24c1d2379370db9f017175f796b31c28161cc429fcf118203ae8449757943d3，337摘要/766源/九副本/4846旧证据、Memory原511289bytes及AGENTS核对后精确九路径推进760→766，completed/closed/sourcePromoted=true。53表3129原行、130旧Actor/88旧模板、183全部账户及root1/root2关闭保持，PLATFORM169调用/14012输出/10工具、预留0，新增模型/工具0；4Job/4Pod按UID清理剩余0。实际Secret原文/编码仅节点RAM扫描，全部编译/测试/构建/部署远程，无本机依赖安装或commit/push。

首次发布组合断言未过且未记录具体子条件，后续完整实际Pod/Flyway/保护通过；首次权限拒绝空响应被脚本JSON解析误拒，三新表仍0和无写回执核对后，原脚本/原两操作意图保留，修正解析并有界续接，最终全部API门通过，没有重置意图或重放业务。

剩余后台有界协调/告警界面、全文日志与长期规模，日志存储只读盘点未见按名称/镜像匹配的Deployment/Service，不能据此排除其它控制器或外部存储。新27调用/9216输出/工具0真实成功/完整团队HOLDOUT计划仍待明确支出授权，不提高Agent68三零额度或重开旧根。T2可信租户/资源/跨入口调度、T4/T5业务工具和其他消费者/共享凭证退役、T6恢复/取消/补偿、T8统一策略及稳定收益仍未完成，主线未全部完成。T7-J已闭环，禁止重跑init/assemble/start/mysql_start/start_v2/build/deploy/check/live/resume_live/cleanup/finish/promote；check空表前置只适用于验收前。下一阶段以当前766源/两规则和当前事实另立基线，不能复用推进前760/757源保护函数。


### Codex 进展：T7-K 原操作告警只读页面开始验证（2026-10-11，进行中）

在原操作证据抽屉增加告警与确认审计，只 GET 原登记，不提交协调/确认或业务请求；当前事实/未协调/陈旧版本/截断分别说明，ACK保留业务UNKNOWN与费用。复用/me viewConsole能力，切换操作/账号/能力、关闭或失败均清除旧事实，八项异步与隐私合同候选。769候选源/四前端路径准备上传，尚未远程测试、构建或发布；节点绿色源仍766，后端T7-J 5b5de3cf…/Flyway78保持。新模型/工具0，27调用/9216输出新验收仍待明确授权。下一步远程前端测试/类型/构建、固定镜像精确产物与实际版本、四角色桌面/手机只读浏览器、完整原记录/预算/UID保护及归档后推进四路径；不重跑T7-J已关闭流程。


### Codex 进展：T7-K 原操作告警只读页面闭环（2026-10-11）

在证据覆盖页原操作抽屉增加只GET告警与确认审计，按原登记/版本显示当前或陈旧事实、未协调和截断，不把空列表当业务成功；ACK仅“已确认阅读”，原UNKNOWN/费用/窗口告警保持。前端只用/me viewConsole能力，切换操作/账号/能力、关闭和失败清除旧私有事实，迟到响应与跨原操作响应不展示；没有协调/确认按钮、轮询或后台扫描。

远程前端232项全量、类型检查、生产构建通过，失败/跳过/取消/TODO0，包括八项异步/隐私合同；后台/Runner源未变，没有重复其历史绿色测试。固定前端10.186.133.66:5001/eap-frontend@sha256:0a374eab109bd430a45d19a86e19f3f24c2bbb7e355e3cf6654a3d00c551df84，测试dist/镜像manifest/config/末层逐文件核对，实际Pod/version.json精确通过，仅CAS前端image，后端T7-J/Flyway78与其它应用/认证/监控/两规则保持。四角色真实PKCE、1500/390px页面与八截图通过，CHAT原两告警/四审计、失败TASK原一告警/两审计与Trace保留，关闭卸载、手机无溢出、viewer模拟503清旧事实通过；非GET/OPTIONS业务API拦截，写请求0、页面异常/非预期API失败0。

56表3140原行逐摘要完全一致，原操作视图/三告警/六审计、覆盖指标/最近明细、全部183账户/两团队关闭及旧Actor/模板保持；新业务/协调/确认/模型/工具请求0。2Job/3Pod按UID清理剩余0。最终回执docs/evidence/eap-operation-alert-console-20261011-final.json，归档1225695bytes/SHA25666206039da0e61548e92daf1f8615d720201ef309b9ac3faebaf5efd6d2a8dcc、64摘要/769源/四副本/5186旧证据、Memory原517363bytes与AGENTS核对后推进四路径766→769，completed/closed/sourcePromoted=true。密钥及编码只节点RAM扫描，不导出凭证/令牌/浏览器profile；全部测试/构建/部署/浏览器远程，无commit/push。

首次发布保护因新验证脚本前端摘要占位值不一致失败；只读定位仅frontend字段差异，原函数规则EXPECTED_APPLICATION_IMAGE_CHANGE核实且当前spec按原算法与基线一致后修正。原失败和旧脚本保留，不改应用源/dist、不重跑测试/发布，随后完整保护门通过。

主线仍未全部完成；后台有界协调/可写告警界面、全文日志、长期规模与T2/T4/T5/T6/T8仍待。新27调用/9216输出/工具0真实成功与完整Team HOLDOUT计划尚待明确支出授权，不提高Agent68零额度或重开root1/root2。T7-K已闭环，不重跑原阶段init/start/build/deploy/live/cleanup/finish/promote；下阶段以769源/两规则和当前原三告警六审计另立保护基线，旧766/760/757保护不复用。


### Codex 进展：T7-L 原操作告警后台有界协调闭环（2026-10-12）

最新 T7-L（2026-10-12）：[原操作告警后台协调](OBSERVATION_ALERT_RECONCILIATION.md)已部署并完成有界验收。持久循环游标、固定轮次上界、单条事务与失败隔离、多实例行锁去重通过；实际后台遍历现有 13 个原登记，增加 11 个无告警事实头。原三告警/六审计/确认状态及 UNKNOWN 完全保持，真实部分扫描后的 Pod 更换和后续至少两轮推进通过。

同一源码复核远程专项 111/9 类、全量 903/107 类及打包通过；独立 MySQL 30/2 类通过，最终失败/错误/跳过为 0。初轮已有指标 HTTP 正向测试返回 false 的唯一失败原样保留，根因仍未确认；未改源码、放宽断言或提高超时。八类独立指标与按当前窗口重算的冻结原事实一致，配置/监控和全部预算保持；模型/工具新增 0。

349 项证据/774 应用源/八路径、5253 旧证据及 Memory/AGENTS 核对后精确推进绿色源，4 Job/4 Pod 按 UID 清理剩余 0。PoC 显式启用每批 2 条、周期 15 秒；仓库默认关闭。无 commit/push。主线仍未全部完成：告警写入页面、全文日志、长期规模、真实新付费实验与完整团队 HOLDOUT，以及其它主线仍待。

实际后端 `10.186.133.66:5001/eap-backend@sha256:92a3cf2fdd2171b2f42880cf8d9f1047b16539cec2afc0ef21fb4e090c27c815` / Flyway79，默认关闭发布先验证游标零值、原视图不变；后续 PoC 五个公开配置显式启用。部分持久进度后仅调整启动等待，真实新 Pod 续接并至少两轮；13 原登记、11 无告警新头、原三告警/六审计全部当前且原记录逐字保持。56 表 3140 原行、130 旧 Actor/88 模板、183 账户与 root1/root2 关闭保护通过；PLATFORM 169/14012/10、reserved0，各原 cap/revision 及 Agent68 三零保持。

复用构建脚本遗漏目的标签，误写旧 t7j 镜像标签；已原样恢复其原 digest，将本轮镜像登记为 t7l。原请求与日志、修正回执保留；线上固定 digest 未变，后续清理按冻结 UID，未重建镜像或重放部署。

初轮唯一失败 `sendsOnlyProtobufSnapshotToReceiverAndAcceptsOriginalReceipt` 返回 false，原日志缺少异常类型，根因未确认。未改源码/超时/断言，独立新 Job 一次同源复核专项/全量/打包全绿；独立 MySQL 原 30 项源码一致并保留。上传遇跳板断开后，补充脚本改为校验完整 stdin 大小/摘要后原子替换；未重放业务或原测试 Job。

[最终回执](evidence/eap-alert-reconciliation-20261011-final.json) completed/closed/sourcePromoted=true，归档 1177889 bytes/SHA256 2dc12d22cd03ebf7627018512cf346c015f53d6cefbf443dd74ab0c38121330b，349 摘要与八源副本、774 应用源/两配置、5253 旧证据及 Memory 前 521014 bytes/AGENTS 核对后精确推进八路径。4 Job/4 Pod 按 UID 清理剩余 0，实际凭证与编码仅节点 RAM 扫描。前端/Runner/监控不改，未在本机编译/测试/构建/部署，未 commit/push。

主线仍未全部完成。继续告警写入 UI、全文日志、规模或 T2/T4/T5/T6/T8；新的 27 调用/9216 输出/工具 0 实验仍待明确授权，不复活 root1/root2 或提高 Agent68 三零 cap。下一轮以当前 774 源/两规则及 13 头/三告警/六审计、动态游标另立基线；旧 769/766/760/757 protect 与 T7-L 已关闭入口不再执行。不能把真实服务实例并发合同称为已验证生产多副本规模或稳定调优收益。


### Codex 进展：T7-M 原操作告警确认页面有界闭环（2026-10-12）

最新 T7-M（2026-10-12）：[原操作告警确认页面](OBSERVATION_ALERT_ACTIONS.md)已部署并完成有界验收。前端仅用 `/me` governance 能力提供单原操作事实核对与两步阅读确认；确认绑定此前看到的版本/摘要，409 或断线后清除旧事实，不自动升级版本、读取或重发。ACK 只表示已阅读，原 UNKNOWN、费用和窗口告警保持。

远程前端 245 项全量测试、类型检查和生产构建通过，包含 21 项告警合同（本轮新增 13 项）。镜像末层与绿色 dist、实际 Pod/version.json 精确核对；四角色真实 PKCE、1500/390px 和十张截图通过。治理/admin 页面各对既有 CHAT 同事实协调一次，原告警/审计零写；六个 ACTIVE 确认成功/409/断线场景仅为隔离浏览器响应夹具，不能宣称新增真实告警或新业务端到端采样已验证。

56 表 3151 原行、13 原登记/13 头/三告警/六审计、130 旧 Actor/88 模板、全部 183 账户及两团队关闭保持；后台游标持续推进且失败 0。后端 92a3cf…/Flyway79、Runner/认证/监控/两规则保持。模型/工具新增 0，2 Job/3 Pod 按 UID 清理剩余 0，无 commit/push。

前端固定 `10.186.133.66:5001/eap-frontend@sha256:9a9cb5129a77a6cfdd1a1be0975c8b9a506a5a6d6bf46c0072e55c3f83535f0a`，实际 Pod/version.json 与绿色 dist 精确通过，仅 CAS 前端 image。确认先显示原操作/告警/版本；刷新、取消、账号/能力/操作切换及关闭清除待确认与旧私有事实；旧版本不静默换新，重复点击单在途，写结果未知要求查看审计。后端 ACK 实际 API/事务合同沿用 T7-J 验收，本轮未新增真实 ACTIVE 告警，六个正向/409/断线浏览器响应夹具单独标识。

87 项摘要/774 源/三路径、5605 旧证据与 Memory 前 524599 bytes/AGENTS 核对后推进绿色三路径。[最终回执](evidence/eap-operation-alert-actions-20261012-final.json) completed/closed/sourcePromoted=true；归档 1354860 bytes / SHA256 400d443490aa8b11371cc64996223d1bddbcd0c08aac248094c68219d9d9c395。凭证及编码只在节点 RAM 扫描，未导出令牌/profile；所有测试、构建、部署、浏览器均在远程，未 commit/push。

[最终回执](evidence/eap-operation-alert-actions-20261012-final.json) completed/closed/sourcePromoted=true，应用源仍 774，仅精确推进三个前端路径。下一阶段以当前源、固定前端 sha256:9a9cb5129a77a6cfdd1a1be0975c8b9a506a5a6d6bf46c0072e55c3f83535f0a、当前原事实和动态游标另立基线；T7-M 所有 init/start/browser_start/tests/build/registry/deploy/check/browser_run/live/cleanup/finish/promote 已关闭，不重跑原意图或调用旧保护函数。

主线仍未全部完成：全文日志、长期规模、真实新模型成功与完整团队 HOLDOUT、T2/T4/T5/T6/T8 和稳定收益仍待。新的 27 调用/9216 输出/工具 0 计划仍待明确支出授权，不提高 Agent68 三零额度、不重开 root1/root2。


### Codex 进展：T7-N 原操作持久事件检索候选完成，远程通道待恢复（2026-10-12，未测试）

继承 T7-M 的774绿色源，候选783源/13路径：原事件与当前原登记Trace组成固定文本，支持字面多词AND检索、1—10080分钟窗口、最多10000候选/100返回，范围与结果截断独立；文本只保留固定入口/事件/原因与合法Trace，不含operationKey、正文、异常、端点或凭证。生产可见性复用覆盖规则，在候选上限前排除DEMO；GET沿用中央VIEW_CONSOLE，其它方法与子路径拒绝。V80只新增原事件窗口索引，Flyway迁移总数候选80。前端按/me viewConsole显示只读检索，默认不请求日志，命中链接打开原Trace/告警/费用抽屉；查询/窗口/账号/能力改变与失败/卸载清旧事实，迟到响应丢弃。原始stdout、任意正文日志、Collector日志存储和独立全文索引没有接入，不能把固定元数据投影称为这些能力已完成。

新增候选10项Spring/MySQL合同、7项角色/请求合同、8项前端异步与隐私合同；所有编译/测试/构建/浏览器均尚未执行。两轮n1.sh完整校验后原子上传未取得成功回执，SSH banner握手多次超时；没有执行init、创建Job或发布命令，不盲目推进绿色源。只做本地源码打包/摘要与脚本语法元数据核对，git diff --check通过；没有本机依赖安装、业务测试/构建、模型/工具调用、commit/push。不能把候选实现或未获回执上传标为已验证。

本轮本地基线/private/tmp/eap-t7n-local-before.json保存774原源、5695旧证据、Memory原527694bytes及AGENTS摘要；source-receipt与delta/helper/browser已准备，节点入口拟为/root/eap-t7n-remote.py、工作目录/root/eap-t7n-20261012，尚未init。恢复后先只读核对上传的大小/摘要及节点目录/状态，再执行新基线与原Job流程；不要重跑T7-M/L/K/J已关闭入口。最后验证线上为后端92a3cf…/Flyway79、前端9a9cb512…；本轮没有应用部署。

待远程全量后端/打包、独立MySQL新合同、前端全量/类型/构建，固定镜像与实际Pod/Flyway80/索引、四角色只读浏览器与全部原事实/预算/动态游标保护。没有远程绿色结果，T7-N与主线均未完成。新27调用/9216输出计划仍待明确支出授权，不提高Agent68三零cap、不重开root1/root2；真实新模型成功、完整Team HOLDOUT、长期规模与T2/T4/T5/T6/T8仍待。


T7-N 通道检查补记：两次完整原子上传及一次只读 true 探测均最终退出255，观察到SSH banner握手超时，根因未确认。没有执行init/测试/构建/发布，绿色源未推进。候选与连接回执分别保存为 docs/evidence/eap-operation-log-search-candidate-20261012.json 和 eap-operation-log-search-connection-20261012.json，明确 completed/deployed/sourcePromoted/mainlineComplete=false。783候选摘要、5695旧证据、Memory原527694bytes前缀与AGENTS再次核对通过；git diff --check通过。恢复后先核对节点实际文件和目录状态，不假定失败上传已完整落盘。


### Codex 续接：T7-N 通道重试（2026-10-12）

用户“继续尝试”及中断后的“继续”期间，通过原 n1.sh 通道再次检查候选文件/工作目录和最小 true。三次有完整结果的只读命令均退出255，观察到SSH banner握手超时；另一次只读命令被中断、未取得结果，不能算作已确认失败或成功。仍未取得节点文件/状态回执，根因未确认。此段续试没有请求上传、init、Job、测试、构建或部署，也没有重新核验远程业务状态。

本机仅核对元数据：783候选源/13路径摘要、四成员上传包与当前文件一致；5697份既有证据和AGENTS保持，PROJECT_MEMORY只追加。没有本机编译/业务测试、模型或业务工具请求、commit/push。新记录：`docs/evidence/eap-operation-log-search-connection-retry-20261012-113354.json`。T7-N及主线均未完成；通道恢复后先只读核对节点文件摘要与工作目录状态，再接续独立远程验收。


### Codex 续接：T7-N 再次连通重试（2026-10-12 13时）

原n1.sh通道的候选文件/工作目录只读检查及最小true探测再次各自退出255，均观察到SSH banner握手超时，根因仍未确认；节点文件/状态无回执。没有请求上传、init、Job、远程测试/构建/部署，远程业务状态未重新核验。本机仅核对783候选源/13路径、四成员上传包、5698份原证据与AGENTS，Memory只追加；无本机编译/业务测试、模型或业务工具请求、commit/push。见 `docs/evidence/eap-operation-log-search-connection-retry-20261012-130228.json`。T7-N及主线仍未完成；恢复后先只读核对节点实际状态。


### Codex 续接：T7-N 13:10 连通重试（2026-10-12）

用户“再试试”：原n1.sh只读文件/状态检查观察到五次SSH banner握手超时，最终退出255，根因未确认，节点状态无回执。未请求上传、init、Job、测试/构建/部署，远程业务状态未核验。783候选源/13路径摘要、5699份原证据与AGENTS保持，Memory只追加；无本机编译/业务测试、模型或业务工具请求、commit/push。见 `docs/evidence/eap-operation-log-search-connection-retry-20261012-131042.json`。T7-N及主线仍未完成。


### Codex 续接：T7-N 13:24 连通重试（2026-10-12）

原n1.sh只读文件/工作目录检查五次SSH banner握手超时后退出255，根因未确认，节点状态仍无回执。未请求上传、init、Job、测试/构建/部署，远程业务状态未重新核验。783候选源/13路径、5700份既有证据与AGENTS保持，Memory只追加；无本机编译/业务测试、模型或业务工具请求、commit/push。见 `docs/evidence/eap-operation-log-search-connection-retry-20261012-132422.json`。T7-N及主线仍未完成。


### Codex 续接：T7-N 连接阶段诊断（2026-10-12 13:29起）

普通只读检查仍五次握手超时后退出255。按investigate方法，在原n1.sh只读true调用中临时启用仅输出固定阶段的SSH过滤诊断，沿用原连接/认证参数且未修改连接脚本；诊断也退出255。五个跳板转发进程均只记录TCP_CONNECT_STARTED，未见TCP_CONNECTED、服务端banner或认证；同轮两个转发PID的TCP状态实测为SYN_SENT。外层约20秒超时，是跳板入口TCP尚未建立所致；网络路径/访问规则/跳板服务的具体根因仍未确认。用户问是否密码错误：当前失败发生在认证之前，不能据此认定密码错误，也没有验证密码本身正确性。

结论/经验：经跳板的外层banner超时不能直接归因密码，应结合内层连接阶段和TCP状态判别。新证据 `docs/evidence/eap-operation-log-search-connection-diagnostic-20261012-132940.json` 仅保存固定阶段/PID/状态，无地址、凭证、原始调试文本；诊断进程最终TCP套接字检查为空。未请求上传、init、Job、测试/构建/部署；远程业务状态未重新核验。783候选源/13路径、5701份旧证据和AGENTS保持，Memory只追加；无本机编译/业务测试、模型或业务工具请求、commit/push。T7-N及主线仍未完成；先恢复跳板入口TCP连通，再通过n1.sh只读核对节点实际状态。


### Codex 续接：T7-N 通道恢复并启动远程验收（2026-10-12）

用户确认后，05:50 UTC原n1.sh只读探测退出0，节点四候选文件与T7-N目录均不存在。随后四文件完整校验/原子上传退出0，大小/SHA256全部一致；init通过并冻结783候选源/13路径、56表3151原行、全部原身份/预算/关闭与监控/两规则。三个原Job（前端、后端专项/全量/打包、隔离MySQL）已启动，远程浏览器准备通过；测试结果尚未评定，未构建发布镜像或推进绿色源。未修改n1.sh/认证配置/密码，连通恢复的根因未确认，也未完成用户提供地址与SSH解析目标的比较，不能据脚本无地址字面量认定目标不一致。新回执 `docs/evidence/eap-operation-log-search-connection-recovered-20261012.json`；5702旧证据/AGENTS保持，Memory只追加。无本机编译/业务测试、新模型/业务工具请求、commit/push。下一步读取原Job结果，不重跑init/start/backend_start/backend_mysql_start/browser_start。


### Codex 进展：T7-N 原操作持久事件文本检索有界闭环（2026-10-12）

最新 T7-N（2026-10-12）：[原操作持久事件文本检索](OBSERVATION_LOG_SEARCH.md)已部署并完成有界验收。按 Trace、入口、事件、原因码或原操作编号进行字面多词 AND 检索，命中打开原 Trace/告警/费用抽屉。只读查询复用中央 VIEW_CONSOLE；生产可见性在候选上限前过滤，最多检查10000条/返回100条，范围与匹配截断分别提示。文本是原事件与当前登记元数据的固定投影，原始 stdout、任意业务正文、Collector 日志存储及独立全文索引仍未接入。

远程后端专项128项/11类、全量920项/109类及打包通过，隔离 MySQL 10项通过；前端253项、类型检查及生产构建通过，失败/错误/跳过均为0。测试 JAR/dist 与两个固定镜像末层逐字节核对，实际 Pod/imageID、Flyway80与原事件窗口索引通过。四角色真实 PKCE 的1500/390px页面、八张截图及日志→原操作→Trace/告警/费用关联通过，503与候选截断另用两个隔离浏览器响应验证。

四角色只读 API 与窗口内34条原事件精确一致；13原登记/13头/三告警/六审计及56表3151原行保持，130旧Actor/88模板、183账户和两团队关闭保持。后台游标持续推进且失败0，监控/两规则及身份环境保持；本轮模型/业务工具新增0，浏览器业务写请求0。5 Job/6 Pod按冻结UID清理，剩余0。

[最终回执](evidence/eap-operation-log-search-20261012-final.json) completed/closed/sourcePromoted=true，783应用源/13路径已在归档接收核对后精确推进；[验收索引](evidence/eap-operation-log-search-20261012-index.html)。后端固定 `sha256:9502cf68a3b0028c03455c3dfb371a7ec9bb98bb2d46d8281d81b69c2f601ca6`，前端固定 `sha256:bb509946fbfbc5338ae7c8f8cff8ec7393f0b4716cf2e03ca5cba475a58d0619`。下一阶段以783源、两规则、当前原事实及动态游标另立保护基线；T7-N全部初始化、测试、构建、发布、浏览器、live、清理、归档及promote入口已关闭，不重跑原意图或调用依赖774源的旧protect。

主线仍未全部完成：原始/正文日志、独立全文索引和长期规模、真实任务/对话/AX LLM成功与完整团队HOLDOUT，以及T2/T4/T5/T6/T8仍待。新的27调用/9216输出/工具0计划仍待明确支出授权，不提高Agent68三零额度或重开root1/root2。所有编译、测试、构建、部署和浏览器均在远程完成；本机仅文本、摘要与脱敏证据核对，无commit/push。

归档2028114bytes/SHA256 `3bf783c65209aa4863dc2ba820166de949b2c88ef541644c877938c236a9d88a`，386项摘要与783源/13路径通过；5703份旧证据、Memory前536152bytes及AGENTS保持。新10项Spring/MySQL与7项权限合同覆盖字面AND、下划线、稳定主键排序、10006事件的10000候选上限、非法输入、生产DEMO过滤、元数据脱敏与缺失事件只读不修补；不宣称真实事务回滚专测、长期负载或新业务成功已验证。连接恢复后有瞬时重置并续读成功，原网络根因仍未知；原失败和恢复回执保持。


### Codex 进展：T8-A 治理策略版本候选与优化状态核对（2026-10-12，未验收）

[行为合同与范围](GOVERNANCE_POLICY_VERSIONS.md)：790 候选源、18 路径，新增 V81、当前策略乐观锁、追加历史、显式版本/摘要回退和前端两步核对。新增 14 项 Spring/MySQL、7 项权限、13 项前端用例；尚无远程通过结论，未部署，不能标记完成。

三文件完整原子上传已核对，节点 `/root/eap-t8a-remote.py` 当前仅开放准备和隔离测试入口。`init` 已发出，后续只读已确认独立目录及源清单存在，尚未取得最终初始化回执；不要重跑初始化。临时会话 48312 仍待返回。接续先读 `/root/eap-t8a-20261012/out/state.json` 及冻结保护回执，再启动一次前端、后端、独立 MySQL 原 Job。绿色源仍为 T7-N 的783项，两固定应用镜像和 Flyway80保持，旧阶段入口关闭。

本地 `/private/tmp/eap-t8a-local-before.json` 冻结783旧源、6094旧证据、Memory前539342bytes及AGENTS摘要；源码收据及完整上传包在同目录。禁止本机编译/测试/构建/安装依赖，不commit/push。用户询问 Agent 优化是否完整：已明确单Agent有真实对照但仍KEEP_BASELINE，完整团队HOLDOUT、自动提案/完整优化闭环和稳定收益未完成。新的27调用/9216输出/工具0计划已再次提交明确支出确认，当前仍未收到批准；不提高Agent68三零额度、不重开root1/root2。


T8-A 远程续记：原init最终通过，复制等待结束；节点文件I/O等待及磁盘80%仅为当时观察，不认为集群存在已确认故障。三个原测试Job已启动；前端266项/类型检查/生产构建退出0，后端专项及独立MySQL尚在执行。未构建发布镜像、未部署，不能把前端通过扩展为整阶段完成；本轮无模型/业务工具调用。


T8-A 首轮测试及修正：前端266/266、类型/构建通过并生成 tests-proof；后端专项与独立MySQL在新建策略历史时同时报 policy_id NULL，未进入全量/打包。检查发现 @Version 为Long但getter回退primitive0，以及create忽略saveAndFlush返回受管实体；改为一致long并使用返回实体，publish同样使用返回实体，新增仓储返回新对象的回归。原失败源码/清单/helper保留 candidate-v1，原Job/日志不覆盖；三文件修复通过完整摘要同步到本轮隔离SRC，790候选源仍18路径。新v2后端/MySQL验证已请求，尚未核定结果；未构建发布镜像、未部署或推进绿色源，原T7-N保持。


Agent优化状态补核：PromptOptimizationPanel已有单Agent GEPA/SIMPLE、独立留出/费用/未知页面；frontend/src尚无team-evolutions/TeamEvolution调用，团队准入明确只接受OFFLINE_ONLY提案。因此团队后端合同已有实现不等于完整团队控制台、模型自动提案或端到端优化收益完成。已向用户明确此差距，并更新主线T3待办。T8-A第二轮两个新测试Job已取得启动回执，仍待结果；新支出确认未收到回复。


T8-A 第三轮：只修正一份集成测试，预检使用测试库真实Agent，原行JSON byte[]转无损十六进制后逐值比较；业务实现与第二轮相同。远程专项68项/6类和MySQL8.4.11原14项均0失败/错误/跳过，mysql/backend-tests-proof.json明确实际日志目录mysql-v3；前端266/类型/构建保持。后端全量/打包在原v3 Job继续，尚未评定。release helper及browser已通过原子上传/摘要，初始化和旧测试入口关闭，后续镜像/部署需绿色回执；尚未创建镜像Job/浏览器Pod、未修改线上。候选回执docs/evidence/eap-governance-policy-versions-candidate-20261012.json明确completed/deployed/sourcePromoted=false；790源/18路径、6094旧证据、Memory原539342bytes前缀及AGENTS再次核对通过。新27调用授权仍无回复，本轮模型/工具0，无commit/push。


T8-A 第三轮全量确认：原v3 Job完成，后端942项/111类全部0失败/错误/跳过，full与package均EXIT=0；专项68/6类、MySQL14、前端266/类型/构建已通过。已请求backend_tests精确源/JAR核对及两个固定镜像构建、远程浏览器准备；尚未取得构建回执、未部署。现有两次失败记录保留，没有新模型或业务工具请求，也未commit/push。


T8-A 发布及实际API：942全量/68专项/14MySQL/266前端与JAR、dist源摘要核对通过；两个固定镜像末层与测试产物逐字节一致，后端37789f900a1927c6728550ddd42b47e0647397bfa8167541729ee541e87c6bb5、前端bcf33889dfda4621b262c69f496b97e82eb87406c86c94ae60c44f10034c16dc实际Pod/version.json已核对。Flyway81新表PK及空历史/原策略revision0通过，原56表3151行/身份/预算/关闭/监控保持。实际只新建一条停用MODEL公开策略，CREATED0→UPDATED1→ROLLED_BACK2；四角色读200、viewer/operator写403、治理/admin原版本写409，缺expected400、错摘要409、无修改保存不推进，原三快照保持。四角色远程浏览器已请求，原请求browser-request冻结4个仅该停用策略的实际页面写入，不重放；尚未浏览器/清理/归档闭环、未推进783规范源，T8-A不能标记完成。新付费计划仍无回复，无模型/工具新增，未commit/push。


### Codex 进展（2026-10-12）：T8-A 治理策略版本与回退已闭环

[治理策略版本与回退](GOVERNANCE_POLICY_VERSIONS.md)已部署并完成有界验收：追加版本、乐观锁、内容摘要、两步回退和命中策略版本记录；无变化保存不写，旧策略不伪造历史，旧决策保持原结果。知识、模型、工作流统一策略尚未全部实现。

远程后端专项68项/6类、全量942项/111类及打包、独立MySQL14项通过；前端266项、类型检查及生产构建通过，失败/错误/跳过均0。首轮实体保存缺陷已修复，第二轮测试夹具/字节比较已修正，原失败源码和日志保留。测试JAR/dist与固定镜像末层、实际Pod和Flyway81均核对。

真实API及四角色PKCE的1500/390px页面通过，八张截图逐张核对。仅新增一条始终停用的公开策略，API创建/编辑/回退后，治理/admin页面各编辑及回退一次，最终版本6、七份不可变快照。两个额外503/409场景为隔离浏览器响应夹具。56表3151原行、原策略/身份/183预算账户、两团队关闭及13原登记/13头/三告警/六审计保持；游标继续推进且失败0，监控/两规则保持。模型/工具新增0，9 Job/10 Pod按UID清理剩余0。

[最终回执](evidence/eap-governance-policy-versions-20261012-final.json) completed/closed/sourcePromoted=true；[验收索引](evidence/eap-governance-policy-versions-20261012-index.html)。450项证据、790应用源/18路径、6095份既有证据及Memory/AGENTS核对后已精确推进远程基准。后端固定 `sha256:37789f900a1927c6728550ddd42b47e0647397bfa8167541729ee541e87c6bb5`，前端固定 `sha256:bcf33889dfda4621b262c69f496b97e82eb87406c86c94ae60c44f10034c16dc`，Flyway81。

T8-A所有初始化、测试、构建、发布、浏览器、live、清理、归档及promote入口已关闭，禁止重跑原意图或调用旧源保护函数。下一阶段须以790源、当前一条新增停用策略/七个版本、原观测事实与动态游标另立保护基线。所有应用编译/测试/构建/部署/浏览器均在远程，本机只处理文本、摘要和归档；无commit/push。

**[Agent优化尚未全部完成](AGENT_OPTIMIZATION_STATUS.md)**：单Agent已有真实对照但仍KEEP_BASELINE；团队后端已有，完整团队控制台、模型自动提案、完整真实HOLDOUT及稳定收益仍待。T2/T4/T5/T6、T7原始日志/规模/真实成功路径及T8其余统一策略继续待办。[新27调用/9216输出/工具0计划](NEXT_LIVE_ACCEPTANCE_PLAN.md)仍待明确支出授权，不提高Agent68三零额度、不重开root1/root2。

补充交接：新快照只保存可信主体SHA256，不持久明文身份或凭据；来源摘要用于内容核对，不代表管理员不可篡改。JPA primitive long @Version与保存返回的受管实体一致，先flush当前行再追加历史，同事务失败回滚；无变化保存不改字节/时间/版本。旧策略首次真实编辑才记BASELINE，旧决策缺少版本保留未知。最新50个历史的截断显式提示，不删旧历史。

归档2042693字节、450项，SHA256 `45bd4a0bb69608060929935b058d6e8b8291ec96e76608f862fe3424b83f26ce`。原783源对照、最新790源、18个源快照及构建输入均与本机一致；本地6095份旧证据、Memory前544679字节（另核原539342字节）和原AGENTS摘要保持。八张PNG逐张视觉检查通过；归档接收后仅执行一次promote，返回退出0及sourcePromoted=true，完整回执另存，不改归档中的旧时间点事实。

本轮实际验证没有Agent优化实验或业务工具执行，不能据此宣称优化收益、完整团队HOLDOUT或新任务/对话/AX模型成功路径通过。用户最新问题“Agent的优化功能等都完整实现了吗”已按实际代码与证据明确答复尚未全部完成。新增独立支出确认尚无答复，继续保留待授权状态；授权前可推进团队控制台与明确合同等零模型工程，不重开已关闭根。


### Codex 进展（2026-10-12）：T3-I 团队优化结果控制台候选

[本轮合同](TEAM_EVOLUTION_CONSOLE.md)：796候选源、11路径。新增中央权限的有界团队列表与一致性详情，只读展示候选/前沿/独立留出/原槽位及全根费用；正文显式读取、账号/能力/任务变更清除及迟到响应丢弃。创建/执行操作、模型自动提案与完整真实HOLDOUT仍待，不宣称整个团队控制台完成。

本地 `/private/tmp/eap-t3i-local-before.json` 冻结790源、6550旧证据、Memory前548719字节和AGENTS；源码/收据/helper完整校验原子上传通过。节点入口 `/root/eap-t3i-remote.py` 当前只开放初始化与隔离测试。init已请求，原会话65381等待回执，不重跑初始化；工作目录 `/root/eap-t3i-20261012`。尚未测试/构建/部署或推进规范源。规范源仍790，线上仍T8-A两个固定镜像/Flyway81；旧已关闭阶段入口不重跑。

新27调用/9216输出/工具0计划仍待单独授权。本轮零模型工程，不重开root1/root2、不提高Agent68三零额度。无本机应用测试/构建、无commit/push。


T3-I 远程续记：原init及三个测试Job启动均取得退出0回执。前端279/279、类型检查和生产构建通过并生成tests-proof；后端专项及独立MySQL仍在运行，全量/打包尚未评定。release/helper与只读browser脚本按完整大小/SHA核对后原子上传，旧初始化和重复启动入口已关闭；无应用镜像/部署、模型/工具或业务写入，未推进790规范源。


T3-I 保护范围补充：继承验收脚本时确认，x.history()的原56表不含governance_policies。T8-A回执的originalPolicyRowsUnchanged布尔值不能单独证明原策略逐行核对；其实际写意图均限定新增停用策略、七版本与页面验收仍有效，原归档不改写。本轮helper已按摘要原子补充独立policies-before和protect检查；freeze_policies已请求，原会话72042待回执，禁止重复冻结。该步骤在任何新应用发布之前建立当前策略全行基线，并与T8-A最终七版本逐值比对，不能追溯补足T8-A之前缺失的策略行快照。应用源码未变，原测试继续；前端279、专项171/4类、MySQL31/1类均已通过，全量尚未结束。


### Codex 进展：T3-I 镜像核对与部署接续（2026-10-13）

远程后端全量952项/111类、专项171项/4类、隔离MySQL31项、前端279项和类型/生产构建及后端打包全部通过，失败/错误/跳过0。5个测试/镜像Job成功，浏览器准备成功。通过只读检查确认两个registry-proof已生成，测试JAR/dist末层精确对应。此前SSH编排会话没有收到完成响应；节点实际状态和原进程已退出分别核对，不重放原构建/准备操作，通道问题根因未知。freeze_policies已成功：6条当前策略与T8-A七份版本历史单独核对。已提交一次backend_deploy，响应尚待核定；发布、浏览器、清理、证据接收和规范源推进仍未完成，不标记阶段完成。


### Codex 进展：T3-I 团队优化结果控制台发布闭环（2026-10-13）

[团队优化结果页面](TEAM_EVOLUTION_CONSOLE.md)已部署并完成有界验收：有界列表与一致性详情、候选谱系/验证前沿/独立留出/原槽位与全根费用、正文显式读取及账号/能力/任务变化清除。权限统一读取中央能力。页面只读；创建/执行/模型自动提案尚未接入，不能称整个团队优化控制台完成。

远程后端专项171项/4类、全量952项/111类及打包、独立MySQL31项通过；前端279项、类型检查及生产构建通过，失败/错误/跳过均0。测试JAR/dist与固定镜像末层、实际Pod/imageID、前端version.json及Flyway81核对。首次后端发布核验复合断言失败原样留档，原失败子条件及具体根因未确认；同一源码与镜像后续检查通过，无重复部署、放宽断言或应用源码修订。

四角色真实PKCE、只读API及1500/390px页面通过，八张实际截图逐张核对；503读取失败和空过滤页继续翻页另用两种隔离浏览器响应验证。原root1 UNKNOWN、root2九次调用/九次结算、两根不完整关闭与未运行HOLDOUT保持。57表3158原行、六条当前策略及七份版本、13原登记/13头/三告警/六审计、130旧Actor/88模板及183账户保持；游标继续推进且失败0，监控/两规则保持。模型/业务工具新增0，浏览器业务写入0；5 Job/6 Pod按UID清理剩余0。

[最终回执](evidence/eap-team-console-20261012-final.json) completed/closed/sourcePromoted=true；[验收索引](evidence/eap-team-console-20261012-index.html)。353项归档证据、796应用源/11路径、6551既有证据及Memory/AGENTS核对后已精确推进远程基准。后端固定 `sha256:a57cc9c3584fbb0da2c66bb5a05a2ab20bb9466ef8079502d75b06d9b4583fdd`，前端固定 `sha256:4a042b467e9d36adf403f67442f748a4df0d4c6cd457fa17574b70fc6a8c645d`。T8-A策略行历史证明的缺口已另行说明；本轮六条当前策略全行保护不能追溯补齐旧发布前缺失的快照。

T3-I所有初始化、测试、构建、发布、浏览器、live、清理、归档及promote入口已关闭，不重跑原意图或调用旧源保护函数。下一阶段以796源、两固定镜像/Flyway81、六条策略/七份版本、原观测事实与动态游标建立新基线。所有编译/测试/构建/部署/浏览器均在远程，本机只处理文本、摘要和归档；无commit/push。

**主线和Agent优化仍未全部完成。** 下一步为团队创建/执行等操作流程、自动提案及完整真实HOLDOUT；T2/T4/T5/T6、T7原始日志/规模/真实成功路径和T8其余统一策略仍待。[27调用/9216输出/工具0的新计划](NEXT_LIVE_ACCEPTANCE_PLAN.md)仍待单独支出授权，不提高Agent68三零额度或重开root1/root2。

归档 1916471 字节 / SHA256 e676a5cd4cc9c66297d6fd88d05c6d55efb710ae4223a3899ed5db9658b71141。保留原6550份历史证据及本轮候选共6551份，Memory原548719字节和接收前551800字节前缀均保持，AGENTS保持。首次后端发布检查失败和后续同版通过单独留档；此前SSH编排完成响应缺失，以节点原状态核定并未重放，具体通道根因未确认。


T3-I接续收尾：归档353项、796源摘要、6551份既有证据、Memory前551800字节、AGENTS及索引36处本地引用最终核对通过，git diff --check通过。远程最终回执completed/closed/sourcePromoted=true。此前未返回完成响应的旧本地编排会话，在确认远程全部完成后仅对核定的本地bash/SSH进程树发SIGTERM；原会话以143退出，未重发任何远程操作，见[本地连接收尾记录](evidence/eap-team-console-20261013-local-transport.json)。文档生成脚本曾在文件方式执行时报UTF-8解码错误，原字节可UTF-8解码且内存解析通过，显式声明UTF-8后生成成功；仅本机元数据脚本调整，应用源码与远程归档保持，原错误记录在发布检查补充回执中。


### Codex 进展：T3-J 团队优化逐步操作候选（2026-10-13，未验收）

[候选合同](TEAM_EVOLUTION_OPERATIONS.md)：802源/12路径。逐步创建、候选、验证、独立预算、准备检查、执行、评分、前沿、留出和关闭页面，以及仅GET的原请求核对。未知响应不自动重发，原关闭/UNKNOWN与原主体边界保持；自动模型提案及完整真实HOLDOUT仍待。

本地 `/private/tmp/eap-t3j-local-before.json` 冻结796源、6911份既有证据、Memory前555838字节和AGENTS。完整上传一次已提交，回执尚待核定；目标 `/root/eap-t3j-remote.py`，工作目录 `/root/eap-t3j-20261013` 尚未初始化。先核对原上传结果，不盲目重发。当前helper仅开放隔离准备/测试入口。未运行应用测试/构建、未部署；规范源和线上仍为T3-I已验收版本，所有旧阶段入口保持关闭。

新的27调用/9216输出支出仍待明确批准；本轮先做零模型工程和隔离验证，不提高旧额度、不重开root1/root2。无本机应用测试/构建，无commit/push。


补充范围：预算关闭检查属于异常记录状态的防御性补强，正常关闭本来要求已存在预算，不宣称复现生产越权。所有尚未取得的测试/部署/浏览器结论保持待验证。


## Codex进展：T3-J 团队优化操作台完成有界验收（2026-10-13）

# 最新进展：T3-J 团队优化逐步操作台（2026-10-13）

[团队优化操作流程](TEAM_EVOLUTION_OPERATIONS.md)已部署并完成有界验收：创建合同、人工单角色候选、验证登记、独立预算、运行条件检查、显式执行、评分、前沿封存、独立留出与关闭，以及按原编号GET核对回执。中央能力、原主体、冻结预览和单次提交贯通；断线不自动重发，原UNKNOWN与关闭事实保持。

远程前端302项/类型检查/生产构建，后端专项184项/4类、全量965项/111类及打包，独立MySQL44项/2类通过，测试失败/错误/跳过均0。两个测试产物与固定镜像层、实际Pod、前端version及Flyway81核对。四角色真实PKCE、1500/390px页面和13条原回执通过；十张最终截图已逐张审查。十类操作流程使用隔离浏览器响应：17次成功模拟提交、1次模拟断线执行、3次GET核对，不能称真实模型或完整真实HOLDOUT通过。

57表3158原行、六条当前策略/七份版本、13原登记/13头/三告警/六审计、130旧Actor/88模板、183预算账户及root1/root2原结果保持。模型/业务工具新增0，真实业务写入0；5 Job/6 Pod按UID清理，剩余0。

本轮旧后台在部署前10:43对原操作3发生一次扫描失败，累计从0增1。原事实未变，后续至少四个完整扫描轮次无新增失败；根因未确认，原失败不清零。验收明确调整为保留这一次已审计历史失败、任何新增失败或失败身份变化继续阻断，其他保护保持。后续以累计1为保护基线，不再宣称本轮后台失败总数为0。只读回执验收的TSV空字段错误、两次浏览器定位/状态文本断言失败均保留；修正验收脚本后通过，应用源码未因此改变。

[最终回执](evidence/eap-team-operations-20261013-final.json) completed/closed/sourcePromoted=true；[验收索引](evidence/eap-team-operations-20261013-index.html)。802应用源/12路径、6911份旧证据、Memory原前缀及AGENTS核对后精确推进远程基准；无commit/push，所有应用测试、构建、部署及浏览器均在远程完成。

**主线与Agent优化仍未全部完成。** 下一步为团队模型自动提案与完整真实HOLDOUT，以及T2/T4/T5/T6、T7原始正文日志/长期规模/真实成功路径和T8其余统一策略。27调用/9216输出/工具0的新计划仍待明确支出授权，不提高Agent68三零额度、不重开root1/root2。

固定后端 `10.186.133.66:5001/eap-backend@sha256:a649aa5a6009eae73cd7994c0c5b27ecfb3c57f8ba31226ffeae3ce583f6e9c3`；固定前端 `10.186.133.66:5001/eap-frontend@sha256:8e6e593689aa4e16e50aa451b5dbcf55d0fff01d9e8a6b456460d8a1956c7611`。归档403项，SHA256 `bafc9fe1a5d5b91f787e2d7bea3a3cebc1bbfe23e9793e64c6c085c5cd031123`，4231325字节。

T3-J所有初始化、测试、构建、发布、浏览器、live、清理、归档及promote入口均已关闭，不重跑原意图。下一阶段以802源、两固定镜像/Flyway81、原业务事实与动态游标另建基线；历史扫描失败累计1必须保留，不调用要求旧796源或失败累计0的旧保护函数。遗留扫描故障根因可另作有界诊断，不把已完成的本轮操作台验收当作整个主线完成。

连接补充：本轮曾出现SSH握手超时、只读status辅助脚本导入阶段kubectl失败，以及askpass/连接关闭提示；后续原n1节点读取、Kubernetes readyz、构建/发布/浏览器/清理和源码推进均成功，未修改认证配置。具体原因未确认，不能将这些现象认定为密码错误。见 `docs/evidence/eap-team-operations-20261013-connection-observations.json`。


### Codex 进展：T3-K1 团队模型提案后端候选（2026-10-13，验证中）

继续用户主线：新增显式 TRAIN-only 单角色草稿、独立提案用途/次数/输出批准、Gateway 原意图许可及同根费用归属、只读原请求/正文接口；不自动登记或应用候选。候选807源，当前17条改动路径，见 `docs/TEAM_EVOLUTION_PROPOSALS.md`。本轮尚未部署/启用，没有真实模型或工具请求；操作台接入与真实HOLDOUT仍待。

本地原802源、7322份旧证据、Memory前560946字节和AGENTS摘要冻结在 `/private/tmp/eap-t3k-before.json`。远程独立工作目录 `/root/eap-t3k1-20261013`；原线上两镜像与 `/root/eap-src` 保持T3-J。专项测试Job `eap-t3k1-contract-1013` 已启动，首次提案类19项出现1个测试错误，正在读取原堆栈；其他类尚未全部结束，不能称测试通过。不要重放旧T3-J入口。所有应用测试在远程，未commit/push。


## Codex 进展：T3-K1 团队模型提案后端完成隔离验证（2026-10-13）

# 最新进展：T3-K1 团队模型提案后端隔离验证完成（2026-10-13）

[团队模型提案](TEAM_EVOLUTION_PROPOSALS.md)后端已实现并完成远程隔离验证：显式公开 TRAIN-only 单角色草稿、独立用途/次数/输出批准、平台内部原意图许可、同根 Gateway 预算结算、原请求只读核对及受保护正文读取。重复请求只发送一次；未知用量保留原预留，责任到期后的迟到响应不改写原 UNKNOWN。草稿不自动登记或应用，不是自动搜索循环或稳定收益证明。

远程完整后端988项/112类与打包通过，独立MySQL23项/2类通过，迁移复验15项通过，失败/错误/跳过均0。新提案22项覆盖训练/验证/留出隔离、并发与幂等、严格输出、原主体与中央能力、用途和预算上界、伪造/改变载荷、UNKNOWN和迟到回执。首次专项测试替身重设NPE、第二次迁移用例对三条原种子预算的错误空表假设均保留，修正测试后通过；迁移现比较原预算的完整字段。提案预算指纹采用规范化JSON摘要，字段顺序不改变原意图。

**尚未部署或启用。** 本轮仅后端候选和隔离验证；操作台模型提案流程、部署与线上权限核验、真实模型端到端和完整真实HOLDOUT仍待。线上仍为T3-J两固定镜像/Flyway81，`/root/eap-src`仍为802份已验收源；新候选807源、17改动路径及测试JAR位于`/root/eap-t3k1-20261013/src`。V82只在隔离数据库应用。新增真实模型/业务工具0，没有生产业务写入；5个Job/5个Pod按UID清理，剩余0。不把后端隔离验证称为整条团队自动提案功能已上线。

[验证回执](evidence/eap-team-proposals-backend-20261013-final.json)、[交接核对](evidence/eap-team-proposals-backend-20261013-handoff.json)、[证据索引](evidence/eap-team-proposals-backend-20261013-index.html)。归档321项，3512837字节，SHA256 `9fe35137d7a1b79b2c2daf39a73fc5394ecdb6f4661a4567f8702a20d8ef3a39`。原7322份证据、Memory前560946字节和AGENTS保持；无commit/push，本机未运行应用测试/构建。

下一步T3-K2：将新计划用途/提案上界、显式提案与原键核对、私密草稿审核/登记接入现有两步操作台，再在远程验证并定向部署；继续使用807候选，同时与802线上基准区分。原T3-K1测试/归档入口关闭，不重放原Job或覆盖首轮证据。后续部署需另建原业务事实/动态游标基线，保留历史扫描累计失败1。

**主线和Agent优化仍未全部完成。** 27调用/9216输出/工具0的新真实验收计划仍待单独明确支出授权；不扩大Agent68三零额度、不重开root1/root2。T2/T4/T5/T6、T7原始正文日志/长期规模/真实成功路径和T8其余统一策略仍待。


### Codex 进展：继续T3-K2模型提案操作台（2026-10-13，候选验证中）

T3-K1归档后继续本轮主线，未结束于后端隔离验证。已补受原主体及中央能力保护的最多2条提案元数据列表；前端加入独立提案用途/费用确认、原提案历史与UNKNOWN阻断、只读原键核对、显式草稿正文读取与父代逐角色校验、另行核对登记。807源，相对线上802基准23路径；本地阶段基线 `/private/tmp/eap-t3k2-before.json` 冻结807源、7648既有证据、Memory前564811字节及AGENTS。

完整上传原入口 `/private/tmp/eap-t3k2-upload.py` 已提交，节点候选 `/root/eap-t3k2-remote.py`；先核对上传原回执，再初始化 `/root/eap-t3k2-20261013`，其当前只开放准备/测试入口。不得重放已关闭T3-K1 Job或推广未部署的K1候选。T3-K2尚未取得测试、部署或浏览器结论，线上仍T3-J/Flyway81。真实付费验收仍待原单独明确批准。


T3-K2接续：已核定原上传和初始化成功；前端311项/类型检查/生产构建通过，原后端Job `eap-t3k2-backend-1013` 正在全量测试，未重复启动。release与browser脚本完整上传通过；发布前原两根控制台快照冻结已请求，等待原回执。尚未部署或调用模型。一次本机沙箱报Operation not permitted，经显式沙箱外重试后上传成功，不能认定跳板机密码错误。


T3-K2远程验证续记：原前端311项/类型/生产构建，后端专项208项/5类、全量989项/112类及打包通过，失败/错误/跳过0。原控制台快照冻结成功。独立MySQL Job `eap-t3k2-mysql-1013` 已启动；浏览器准备及测试通过后的镜像构建编排已提交，待核定原结果。尚未发布，无模型/业务写入，不重放原任务。


T3-K2发布接续：独立MySQL实际66项/3类通过；两个镜像与已测JAR/dist末层精确核对通过，浏览器BROWSER_READY。后端固定候选 `sha256:0d0ef7f323db173c380959215f29df0c87646741b581ee2127d46d5c25dbc897`，前端 `sha256:c640e0422c24a226da87cbdda3e74c577f47d3ef3cef31efeef89bf4f4cf42ef`。已请求一次backend_deploy及原rollout核定（会话74273），尚待回执，不重复发发布意图；旧57表/预算/策略/游标保护继续。


T3-K2部署核定：原后端及前端rollout成功；实际Pod镜像、前端version和Flyway82检查通过，原业务保护通过。四角色原结果/内容边界已通过，13条原请求与新增提案权限只读核验仍在原会话13766中；浏览器流程、证据接收/清理/规范源推进尚未完成，不标记阶段完成。原任务根与预算保持，真实模型调用0。


## Codex 进展：T3-K2 团队模型提案发布与验收完成

# 最新进展：T3-K2 团队模型提案操作台完成有界验收（2026-10-13）

[团队模型提案](TEAM_EVOLUTION_PROPOSALS.md)已部署：独立用途与总预算确认、显式单角色提案、最多两条原提案历史、原编号GET核对、私密草稿读取与父代校验、独立预览登记候选。TRAIN-only、中央权限、原主体、同根费用与未知用量边界贯通；草稿不自动登记或应用。不是自动反馈搜索循环或稳定收益证明。

远程前端311项/类型检查/生产构建，后端专项208项/5类、全量989项/112类及打包，独立MySQL66项/3类通过，失败/错误/跳过均0。固定镜像与测试JAR/dist、实际Pod、前端version和Flyway82核对。四角色真实PKCE与只读API、14张桌面/移动端截图已核定；原十类操作及新增用途确认、草稿审核登记、UNKNOWN与读取失败使用隔离浏览器响应，实际业务写入和真实模型调用均0。

V82新增提案表和三项预算字段。旧计划保持OFFLINE_ONLY/提案调用0/输出0，旧控制台只增加这三项默认字段；57表3158原行、六条策略/七版本、13原登记/13头/三告警/六审计、130Actor/88模板、183账户、root1 UNKNOWN及root2九次结算和两根不完整关闭均保持。部署只变更两应用镜像并显式开启提案可用性开关，未创建新付费计划。历史扫描累计失败1及原失败身份保持，本轮无新增失败，旧根因仍未确认。

[最终回执](evidence/eap-team-model-proposals-20261013-final.json)、[交接核对](evidence/eap-team-model-proposals-20261013-handoff.json)、[验收索引](evidence/eap-team-model-proposals-20261013-index.html)。5个Job/6个Pod按UID清理，剩余0。归档385项，2854257字节，SHA256 `06bfad00b7f05e1a98cfd450e13b800f2a0ba69ab2271bce4b1abf85219280dc`。807份应用源/23条改动路径和7648份既有证据、Memory原前缀、AGENTS核对后，已精确推进远程规范源；无commit/push，所有应用测试/构建/部署/浏览器均在远程。

后端 `10.186.133.66:5001/eap-backend@sha256:0d0ef7f323db173c380959215f29df0c87646741b581ee2127d46d5c25dbc897`；前端 `10.186.133.66:5001/eap-frontend@sha256:c640e0422c24a226da87cbdda3e74c577f47d3ef3cef31efeef89bf4f4cf42ef`。T3-K1仍是此前未部署的后端隔离验证历史，其原证据不改写。T3-K2准备、测试、构建、发布、浏览器、live、清理、归档及promote原入口关闭，不重放。下一阶段以807源、两固定镜像/Flyway82、提案开关和原业务事实另建基线。

**主线和Agent优化仍未全部完成。** 真实供应商提案、完整真实HOLDOUT和稳定收益尚未验证；既有27调用/9216输出/工具0计划仍待单独明确支出授权，不能把该计划自动扩成含模型提案的新支出。不提高Agent68三零额度或重开root1/root2。T2/T4/T5/T6、T7原始正文日志/长期规模/真实成功路径及T8其余统一策略仍待。


### Codex 进展：T3-K2UI 截图复核后的文案收尾（2026-10-13）

T3-K2归档/807源核对并promote完成。截图复核发现结果页仍写“模型自动提案和自动采用尚未开放”，费用文案仍写“离线提案模型调用”；本轮仅修改`frontend/src/views/TeamEvolutionView.vue`两处文案，明确草稿须单独批准/主动生成/另行登记，并改为通用提案费用名称。补拍手机草稿正文和原UNKNOWN回执。

原8037份证据、807源、Memory前缀与AGENTS冻结于`/private/tmp/eap-t3k2ui-before.json`。独立节点阶段`/root/eap-t3k2ui-20261013`，原入口`eap-t3k2ui-release.py`只开放前端准备/测试/构建/发布/浏览器/归档；初始化及后续前端验证编排已提交，回执待核定。后端源码/镜像/V82/提案开关保持，继承T3-K2的989项及MySQL66项证明，不称本次又跑了后端。没有新增测试或业务逻辑变化，无真实模型/业务写入，已关闭K2入口不重放。


T3-K2UI接续：原前端311项/类型/生产构建通过，固定镜像`sha256:ef992ae68a60c70f512d25c8cdba2ef5e3b16d6bf8cf358772afcd4f940f4eaf`与dist精确核对，定向前端发布、实际Pod/version、四角色结果及原13回执、旧预算/提案权限和原业务保护均通过。原浏览器与后续live/清理编排会话8070待核定。未改变后端镜像/源码/V82或提案开关，规范源尚待本阶段验收后推进这一个文件；原K2阶段已关闭。


## Codex 进展：T3-K2UI 前端说明与手机截图收尾完成

# 最新进展：T3-K2 模型提案功能及文案收尾完成（2026-10-13）

团队模型提案已部署并完成有界验收：独立用途/总预算批准、显式TRAIN-only单角色草稿、原键只读核对、草稿审核与独立候选登记。截图复核后的两处旧文案已修正，明确草稿须主动生成及另行登记，费用不再统称离线提案；手机端草稿正文和UNKNOWN回执已补拍。

主功能远程后端全量989项/112类、专项208项/5类、MySQL66项/3类，前端311项及构建通过。文案收尾仅改一份前端源，再次运行311项/类型检查/生产构建和四角色真实PKCE、只读API及四组隔离浏览器场景；未重复运行未改动的后端。新的14张截图逐张核定，2个Job/3个Pod按UID清理，剩余0。两阶段均无真实模型或业务写入；原两根关闭/UNKNOWN/费用、旧预算、策略和历史扫描失败1保持。

[主功能验收](evidence/eap-team-model-proposals-20261013-index.html)、[文案收尾回执](evidence/eap-team-model-proposals-copy-20261013-final.json)、[最新截图和证据](evidence/eap-team-model-proposals-copy-20261013-index.html)。远程规范源807份已核对推进。当前前端 `10.186.133.66:5001/eap-frontend@sha256:ef992ae68a60c70f512d25c8cdba2ef5e3b16d6bf8cf358772afcd4f940f4eaf`；后端 `10.186.133.66:5001/eap-backend@sha256:0d0ef7f323db173c380959215f29df0c87646741b581ee2127d46d5c25dbc897`、Flyway82及提案可用性开关保持T3-K2。旧计划均为OFFLINE_ONLY/提案额度0，未新建付费计划。

本次归档105项，1802028字节，SHA256 `f5173e69df2ae1be934565a3852e08f99578999dd1bc6679ce151f759038ef19`。原8037份证据、Memory前缀与AGENTS保持。T3-K2和文案收尾阶段均已关闭，不重跑原发布/浏览器/归档/源码推进入口；后续以最新807源和两固定镜像新建基线。无commit/push，本机未运行应用测试或构建。

**主线与Agent优化尚未全部完成。** 真实供应商提案、完整真实HOLDOUT、自动反馈搜索循环及稳定收益仍待验证或实现；原27调用/9216输出/工具0计划仍待单独明确支出授权，也未授权额外提案调用。T2/T4/T5/T6、T7其余能力和T8其余统一策略仍待。不会提高Agent68三零额度或重开root1/root2。


T3-K2与T3-K2UI最终核对：385+105项归档摘要、当前807源、8037份既有证据、Memory前570530字节和AGENTS保持，52处验收索引引用有效；两阶段共28张截图已逐张审查，最新手机图明确覆盖草稿正文和UNKNOWN原回执。两最终回执completed/closed/deployed/sourcePromoted均true，临时资源剩余0。git diff --check通过，无commit/push。后续不得调用依赖旧802源或旧前端镜像的已关闭入口，使用最新807源/前端ef992ae/后端0d0ef7/Flyway82建立新基线。主线尚未全部完成，真实付费提案与完整HOLDOUT未验。


### Codex 进展：2026-10-13 T3-K3 三源修订已保存，SSH 中断，未验收/未部署

本轮三源修订已准备：团队提案继承单 Agent 的既有推理参数策略，deepseek-flash/deepseek-pro 的新提案冻结 thinking=disabled，其它模型及评估参数保持原默认；新增四个参数/摘要/幂等用例和一个“草稿→审核登记→两候选验证→独立留出→同根结算→原键读取”的完整集成用例。它们使用隔离替身，不能代替真实模型端到端验证。

远程已完成 807 源与原业务事实快照，随后启动测试和只读核对连续出现 SSH banner exchange 超时（exit 255）。**本轮测试结果未确认、未部署、未推进规范源，主线未完成。** 原测试 Job 是否创建仍须按原名称/UID核对；不能将连接失败解释为密码错误或原 Job 未执行。之后静态复核的测试替身修订仅保存在本地 revision2，尚未上传。详见[续跑交接](evidence/eap-team-proposal-journey-20261013-pending/README.md)和[待验收状态](evidence/eap-team-proposal-journey-20261013-pending/preflight.json)。

本轮开始确认的线上基线仍为 T3-K2 后端 0d0ef7f…dbc897、T3-K2UI 前端 ef992ae…f4eaf、Flyway82；以下已完成阶段的结果不代表本轮验证。原 8148 份证据、Memory 原前缀和 AGENTS 保持，本机未运行应用测试或构建，无 commit/push。

[真实验收计划](NEXT_LIVE_ACCEPTANCE_PLAN.md)新增可评审选项：含一次提案共 28 调用/9728 输出/工具0，替代而非叠加旧27调用方案；已询问单独授权，尚无答复。未新建付费预算或请求真实模型调用，不提高 Agent68 三零额度、不重开 root1/root2。完整真实 HOLDOUT、自动反馈搜索、稳定收益及其余主线继续保留。


远程阶段 /root/eap-t3k3-20261013 已初始化；原测试计划 eap-t3k3-backend-1013 未取得 UID/结果回执。本地续跑包保留 revision1 原上传补丁、receipt、修订2和新版 helper；恢复连接先检查 state.json 及原 Job，不能重放 init 或旧 K2/K2UI 入口。修订2的 doAnswer 修复来自静态复核，未声称远程测试已失败或通过。新 helper 的 revise 要求所有原 Job 已终态并保存旧源，测试后才可构建/发布；仍以实际结果及密钥扫描后的证据为准。


### Codex 进展：30 分钟中文文字 Demo 制作完成（2026-10-13）

用户要求尽量覆盖全部功能的完整演示，并指定约 30 分钟、增加文字说明。沿用此前中文文字、无旁白的偏好，已生成 [30 分钟 MP4](../demo_recordings/eap_full_20261013/output/EAP-full-demo-30min-1080p.mp4)、[离线章节播放器](../demo_recordings/eap_full_20261013/output/index.html)、[完整包](../demo_recordings/eap_full_20261013/output/EAP-30min-demo-package.zip)及[功能覆盖清单](../demo_recordings/eap_full_20261013/output/功能覆盖清单.md)。成片恰为 1800 秒、1920×1080、25 fps、H.264、无音轨；40 章、280 段烧录中文说明，另附 SRT。覆盖当前全部 21 个导航页面与 Agent 详情，包含发现接入、发布、团队计划、工作流、AX、调度、观测与 Trace、知识、评估校准、反馈实验、单 Agent / 团队优化、提案、治理审批、权限、安全审计、成本价值。

素材主体为已验收的 2026-09-28 真实录屏，保留操作节点并删减停留；补入 10-02 至 10-13 的历史真实结果与验收截图。团队操作/模型草稿/UNKNOWN 隔离界面样例持续标注，不是新模型执行证据。历史失败、UNKNOWN、KEEP_BASELINE、团队第二根 9 次结算且证据不完整均如实保留；页面覆盖不代表全面真实端到端验收。抽查后将放大过度的手机告警样例替换为真实桌面告警/审计，将反馈段替换为完整手机反馈表单。

使用既有 ffmpeg/ffprobe 与 Pillow 进行本地媒体剪辑、字幕排版、编码和媒体检查，没有安装依赖；未在本机运行应用测试、构建或应用浏览器验收。全片解码无错误，45,000 帧、40 章时间/标题、280 段连续字幕、无音轨与字幕排版均核对通过；40 张章节抽帧及 28 张截图段抽帧经目视复核，两个调整片段另行全分辨率复核。未声称逐秒人工观看整片。离线播放器目录时间及本地链接静态核对通过，未进行浏览器执行验证。完整包 CRC 与全部文件 SHA256 通过；[媒体验证记录](../demo_recordings/eap_full_20261013/output/validation.json)。MP4 59,060,041 字节，SHA256 `22a5d57725f0d1ce27108970b32a3dfaa980653cb9b254d3acd5eefe95808e9a`；ZIP 59,127,726 字节，SHA256 `f0cfbc8911fd589a375ccb1e2cf54269b3382a0fcc0c879106e72ced57e75d57`。

新的远程录制未完成：通过既有 n1.sh 连接多次 SSH banner exchange 超时，最后只读原回执查询 exit 255。临时录制工作区 `/root/eap-demo30-20261013`、Pod `eap-demo30-recorder-1013` 的创建和清理均未获得确认，不能断言它不存在；[连接与待清理交接](../demo_recordings/eap_full_20261013/remote-recording-status.json)。恢复连接后先核对原 pod-owned.json 和同名 Pod 的 UID，只对确认属于本轮的临时资源按 UID 清理，不能重放 bootstrap init。没有请求应用部署、模型调用或业务写入。

807 份主线应用源与 reviewed-source-manifest 完全一致，AGENTS 保持；仅新增演示素材目录并追加本节，无 commit/push。T3-K3 的未验收/未部署状态不变；真实新提案/完整 HOLDOUT、自动反馈搜索、稳定收益及其余主线仍待，原单独支出授权问题仍未获答复。本次视频交付不代表主线任务全部完成。


### Codex 进展：用户明确要求继续实现并部署，T3-K3 续跑仍未取得远程回执（2026-10-13）

用户明确要求“继续实现”“要部署”，并确认连接信息没有变化。沿原 n1.sh 核对原任务、最小连接、上传续跑包、最终只读回执查询；沙箱内阻断后已在沙箱外重试，四轮远程命令均最终 SSH 255 / banner exchange timeout，没有节点成功回执。不能据此推断原 Job 或续跑进程未创建；新部署状态未确认，最后确认基线仍为 T3-K2 后端/T3-K2UI 前端/Flyway82。

三源候选已复核，与 807 份 reviewed-source-manifest 完全一致。本轮仅补强发布工具：专项至少 213、全量至少 994、隔离 MySQL 至少 71，并按名称要求四个参数用例与一个提案到留出用例实际通过；新 Job 保存被测源码清单摘要。以上是检查门槛，不是本轮已通过的测试结果。准备了持久状态和文件锁控制的远程续跑脚本：原 UID/终态核对→revision2→远程测试/打包→固定镜像与 JAR 核对→后端定向发布→四角色/原回执/原业务保护→UID 清理→脱敏归档。接收核定归档之前不 promote，无真实模型调用入口。

[续跑说明和工具](evidence/eap-team-proposal-journey-20261013-resume/README.md)、[未确认状态](evidence/eap-team-proposal-journey-20261013-resume/continuation-status.json)。原上传入口 `/private/tmp/eap-t3k3-resume-20261013/upload-and-start.sh` 已尝试一次，未获上传或启动回执；恢复后先读 `/root/eap-t3k3-resume-20261013/launch-receipt.json` 与 `resume-status.json`，以及 `/root/eap-t3k3-20261013/out/state.json`，不得盲目另起进程、Job 或重跑 init。部署已经授权，不再询问是否部署；真实付费验收仍待此前单独授权。

原 807 应用源、8157 份既有证据、Memory 原前缀及 AGENTS 均核对保持；本轮未新增业务源码修改，本机仅静态语法/逻辑复核与文件摘要，没有应用测试、构建或安装依赖，无 commit/push。T3-K3 和整个主线仍未完成，不能把部署准备记录当作成功部署。


### Codex 进展：本地访问隧道自动重连已启动，仍待 SSH 连通（2026-10-14）

用户要求建立隧道以便本机访问。最初 5173/8180 均无监听，本地 HTTP 连接失败；通过既有 ate-ui-tunnel.sh 显式转发 frontend:80 与 keycloak:8080，脚本最终报“无法查询服务地址”。随后原 n1.sh 最小 hostname 检查仍连续 banner exchange timeout / exit 255，尚未获得节点连接。

已启动仅调用原隧道脚本的本地自动重连守护进程，PID 97724（脱离当前终端、PPID 1），源码 `/private/tmp/eap-local-access/tunnel_supervisor.py`，状态 `/private/tmp/eap-local-access/status.json`。凭证继续由现有连接入口使用，不读取或存储密码/令牌，不记录隧道原始输出。子进程退出后等待 30 秒再尝试；每轮检查前端 HTTP、认证公开元数据 issuer，以及后端匿名请求 401，三项满足才写 READY。停止时只终止其自己创建的隧道进程组；需要停止可向 97724 发送 SIGTERM，先核对进程身份。

记录时状态仍为 CONNECTING，前端与认证 HTTP 均为 0，端口尚无监听；不能称本地访问已恢复。预期访问地址为 http://localhost:5173，认证为 http://localhost:8180/realms/eap。没有修改应用源码、认证配置、凭证或集群部署，没有本机应用测试/构建、依赖安装、commit/push。上一轮 T3-K3 部署状态仍待原回执核对。


### Codex 进展：T3-K3 原部署已核定、验收归档和源码推进完成（2026-10-15）

团队提案的推理参数与“草稿→审核登记→两候选验证→独立留出→同根结算→原键读取”流程已完成远程工程验证。新提案只对 deepseek-flash/deepseek-pro 冻结 thinking=disabled；其它模型及验证/留出参数保持既有策略。完整流程使用隔离替身，真实供应商提案、完整真实 HOLDOUT、自动反馈搜索和稳定收益仍未验证或实现。

原续跑实际于 10 月 14 日完成测试、后端发布与清理；10 月 15 日恢复连接后读取原启动/状态回执，接收并核定原归档，没有重复创建 Job 或再次发布。远程专项 213 项/5 类、全量 994 项/112 类及打包、隔离 MySQL 71 项/3 类全部通过，失败/错误/跳过均 0；四个参数用例及一个完整流程用例在三组原 XML 报告中均实际出现并通过。前端未改，311 项及浏览器截图继承 T3-K2UI，没有声称本轮重跑。

当前后端 `sha256:e654e61a2111d613d98a0c643b249d2d44eca12b706180c222828d0f7b3faadc`，前端 `sha256:ef992ae68a60c70f512d25c8cdba2ef5e3b16d6bf8cf358772afcd4f940f4eaf`，Flyway82。测试 JAR/固定镜像/实际 Pod 一致；15 日四角色只读核验通过，58 表 3158 原行、六条策略、原预算/实例和两根关闭事实保持。历史扫描累计失败仍为 1、原失败身份保持，没有新增，旧根因仍未确认。模型/工具新增与业务写入均 0，3 Job/3 Pod 已按 UID 清理，剩余 0。

[最终回执](evidence/eap-team-proposal-journey-20261015-final.json) completed/closed/deployed/sourcePromoted 均为 true；[验收索引](evidence/eap-team-proposal-journey-20261015-index.html)。331 项原证据、1262322 字节归档的 SHA256 为 `ba1240652403810eac1e50e9e11ecb918d35c21c735dfa103b3312038b9ef179`。本地 807 源、8163 份既有证据、Memory 原前缀及 AGENTS 保持；核对归档后仅推进远程三份已验收源码，807 份规范源完全匹配。无 commit/push，本机只做文本、归档摘要和原 XML 报告核对，没有应用测试/构建或安装依赖。

T3-K3 原初始化、测试、发布、清理、归档、续跑和源码推进入口均已关闭；不要重跑原意图或调用依赖旧规范源的 protect。原 resume-status 停留在 COMPLETE_AWAITING_ARCHIVE_REVIEW 是归档前记录；交接后的权威回执是 `/root/eap-t3k3-20261013/final-completion.json` 与 `/root/eap-t3k3-close-20261015/final.json`。下一阶段以当前 807 源、两固定镜像/Flyway82及最新原业务/动态游标另建基线。

**主线尚未全部完成。** 下一轮 [28 调用/9728 输出 Token/工具 0 的真实验收方案](NEXT_LIVE_ACCEPTANCE_PLAN.md)仍待单独支出授权；它替代旧 27 调用方案，不叠加，不提高 Agent68 三零额度或重开 root1/root2。T2/T4/T5/T6、T7 原始正文日志/长期规模/真实成功路径及 T8 其余统一策略继续保留。

本地访问守护进程在本轮收尾时报告 READY：前端/认证 HTTP 200、后端匿名 HTTP 401，入口 http://localhost:5173；只是当前可达性，不证明连接永不再断。本轮首次沙箱内连接被阻止，按要求沙箱外重试后原 n1.sh 成功；此前 SSH 超时没有证明远程续跑未执行，本次原回执确认其已于 10 月 14 日完成。未修改连接信息或认证配置。


### Codex 进展：T8-B 发布中发现 schema 映射差异，revision5 验证中（2026-10-15）

审批依据失效控制和 JWT 治理权限修复已实现。revision4 曾通过专项101/全量1032/MySQL29/前端318，但首次真实后端启动因 V83 `policy_context_digest CHAR(64)` 与默认 VARCHAR 映射不符而失败。原后端实例一直可用；已停止 worker4 PID228304 并撤回失败镜像，V83 新增字段保留，无历史数据回写。原失败日志、发布意图及回退证据在 `/root/eap-t8b-20261015/out/failed-release-r4/`，其中 rollback-proof.json 为 PASS；旧 worker-v4-status 的 RUNNING 由 worker-v4-stop-proof.json 取代，不能重放。

revision5 显式绑定 CHAR JDBC 类型，并让审批 MySQL 回归启用与部署环境相同的 `ddl-auto=validate`。813源／相对本轮初始18个变更路径；前端源码、318项及构建镜像保持，按文件摘要继承。修复后的专项101项/7类和MySQL29项/2类已通过，失败/错误/跳过0；全量回归仍在执行，尚未重新发布或验收闭环。

续跑：`/root/eap-t8b-worker-v5.py`，PID287536，SHA256 `77e9848ab81aa7ebb2a32f0e4fd07d4ba6e3f951bfd0d69363a1764c978d82f6`；验收工具 `/root/eap-t8b-verify-v4.py`，候选 `/root/eap-t8b-20261015/revision5-src`，清单 `/root/eap-t8b-revision5.json`。只读先检查 worker-v5-status.json 与 out/state.json，不能重放已有 Job、初始化、发布或回退入口。Job 为 eap-t8b-backend-r5-1015、eap-t8b-mysql-r5-1015，按 UID 核对；成功后 worker 停在截图复核阶段，再做12图核定、UID清理、归档接收、规范源推进和最终记忆更新。

[续跑材料](evidence/eap-approval-context-20261015-pending/README.md) 与 [设计边界](GOVERNANCE_APPROVAL_CONTEXT.md)。原807规范源尚未推进；不把revision4报告当成最终后端验证。模型/工具调用新增0，无新付费预算，主线未全部完成。全部应用构建测试与部署在集群；本机仅文本/摘要/归档操作，无commit/push。


### Codex 进展：T8-B 治理审批依据失效控制完成（2026-10-15）

已冻结预检时全部已启用策略及强制治理配置的摘要；编辑、启停、新增与回退使旧审批失效，原批准事实保持。旧记录没有摘要时不能批准或复用；独立 READ COMMITTED 复用检查和乐观锁覆盖旧事务快照及并发批准/拒绝。JWT 预检/审批和界面按 /me 同源治理能力控制操作，审批审计人来自认证身份，区分当前/变更/未记录，提交未确认时不自动重发。

远程后端专项 101 项/7 类、全量 1032 项/114 类与打包，隔离 MySQL 29 项/2 类，前端 318 项及类型/生产构建全部通过，失败/错误/跳过均 0。Flyway83、固定镜像/实际 Pod/前端版本核对通过；四角色真实 PKCE/只读 API、7 个隔离浏览器场景、12 张终版截图已核定；首轮截图定位不完整，原件保留后重新采集。首轮两处测试断言失败及原报告保留，修订后通过。首次后端发布发生 CHAR/VARCHAR 映射校验失败，已回退并修正 Java 映射；隔离 MySQL 增加部署同等 schema 校验，最终后端测试与再次发布通过。前端 318 项/构建及镜像沿用相同源码的 revision4 结果，逐文件摘要一致。

[设计与边界](GOVERNANCE_APPROVAL_CONTEXT.md)、[验收索引](evidence/eap-approval-context-20261015-index.html)、[最终回执](evidence/eap-approval-context-20261015-final.json)。后端 `10.186.133.66:5001/eap-backend@sha256:f2c41dab129e06ad209410048be3b963383315b32ecc08048265effcfad2bd9e`；前端 `10.186.133.66:5001/eap-frontend@sha256:729b0b5ad42bfad41d48cedb6409612816970c1682f6d8af5ab09cbdbab676ee`。813 份远程规范源已核对推进，18 个变更路径。12 Job/13 Pod 按 UID 清理、剩余 0；58 表 3158 原行、六策略、旧预算和两根事实保持；扫描历史失败 1/新增 0，旧根因仍未确认。模型/工具新增和业务写入均 0。

本阶段已关闭，不重放原 worker、初始化、测试、发布或清理入口；下一阶段按最新 813 源、两镜像/Flyway83 建基线。主线尚未全部完成；28 调用/9728 输出 Token/工具 0 的真实提案及 HOLDOUT 方案仍待单独支出授权，其余 T2/T4/T5/T6、T7/T8 工作仍保留。无 commit/push，本机未运行应用测试或构建。

归档 983 项、5636130 字节，SHA256 `6d3048b68a3c71c064d0e735e3d2ca5c2858b870fb3483e4d5127cd2d9da4e9e`；接收并逐文件核定后才推进规范源。原 8504 份证据、Memory 前 587748 字节及 AGENTS 保持。权威终态 `/root/eap-t8b-20261015/final-completion.json`，completed/closed/deployed/sourcePromoted 均 true。原 worker-v5-status 的 AWAITING_REVIEW 为归档前状态，不能据此重放。

### Codex 进展：AgentCore 等同类平台借鉴研究（2026-10-15）

用户继续要求调查AgentCore等同类竞品。本轮完成[结论与优先级](research/agent-platforms-20261015/REPORT.md)，按deepresearch流程核对Amazon Bedrock AgentCore、Google当前Gemini Enterprise Agent Platform、Microsoft Foundry Agent Service、LangGraph/LangSmith Deployment与Dify；61项公开一手来源登记及访问日期归档，另有[当前EAP代码/验收映射](research/agent-platforms-20261015/eap-baseline.md)、[检索与冲突记录](research/agent-platforms-20261015/collection-log.md)、[反方检查](research/agent-platforms-20261015/QA.md)。官方合同/公告不是独立实测，未保存整站网页快照。

研究建议：P0完善具体工具的执行覆盖、工具版本/schema与审批依据绑定；P1做跨运行时能力合同、失败原操作到回归样本的入口；P2再做可治理长期记忆。复用已有短凭证、委托、预算、UNKNOWN查询、效果epoch屏障、候选谱系及评估服务，不重造已有底座。T8-B当前仍是模型审批闭环，不能扩称全部工具统一审批。旧READY发布头迁移已12/12，共享凭证全面退役仍未完成。

下一轮若实施工具等待批准/恢复，须新增版本化状态合同和独立新授权根/新操作，复用账本机制，不重开历史REJECTED/UNKNOWN，不升级已有操作根的效果协议；受控计数的保证不自动属于真实业务工具。报告给出零模型、合成工具PoC及旁路/并发/撤销/版本变化/丢回复/重启验收。会话或checkpoint持久化不等于业务效果exactly-once；云限流不替代硬预算，外部评分不自动授予采用权。

本轮只有静态代码/资料研究、文档与摘要检查；未执行PoC、测试、构建、部署或供应商模型调用，未向外部发送源码/私有遥测，未commit/push。研究建议未转为实施授权，原28调用/9728输出Token/工具0的付费方案状态不变。T8-B已关闭、主线仍未全部完成；不因本研究重放旧worker或变更部署。具体文档检查结果见[validation.json](research/agent-platforms-20261015/validation.json)。

### Codex 进展：继续产品调查，明确暂不进入实现（2026-10-15）

用户在“继续”后明确“先做产品调查”。本轮只完成[第二轮产品研究](research/agent-control-products-20261015/REPORT.md)，没有进入P0代码或部署。后续泛指“继续”应优先沿产品调查推进，不能据上一轮建议自行开始工具审批实现；新的实现请求再按当时范围处理。

补充IBM watsonx Orchestrate、ServiceNow AI Control Tower、Salesforce Agentforce，并细化AgentCore的接入/发布/运营体验。42项官方来源登记（含上轮重叠资料）、四份产品分册、[EAP产品基线](research/agent-control-products-20261015/eap-product-baseline.md)及[反方检查](research/agent-control-products-20261015/QA.md)已归档。EAP已有WorkItem来源/状态/证据/复现重开和价值来源/基线/窗口/归因结构，不建议重建统一工作台、事项或ROI底座。

主要产品判断：IBM与ServiceNow应纳入企业Agent运营治理的直接对标，AgentCore用于构建/运行体验，Agentforce用于业务目标到会话/动作/改进的闭环。优先研究三条用户任务：接入时明确责任与控制范围；异常到处置再到原效果核验；改进到可比结果与采用依据。跨平台和统一看板已经有多家产品覆盖，不宣称EAP独有；私有环境、运行责任和效果查证只是待客户验证的定位假设。

边界：IBM原生AgentOps有人审与显式应用流程但当前不支持on-premises；Bedrock发现GA、Foundry/Gemini发现Preview，外部优化GA范围未核实，governance同步是双产品组合。ServiceNow已有宿主和Gateway控制，但发现列表不等于阻断覆盖、开关状态不证明原副作用撤销；家族EA与Store应用版本分开。Agentforce部分业务结果来自LLM评分，外部治理需另看MuleSoft，不能统算基础套餐。新报告已纠正IBM发现/优化范围的过度概括。

只做公开资料读取、本地静态代码核对和文档完整性检查；未实操竞品、访谈、外部联络、PoC、应用测试、构建、部署或付费调用，没有外发源码/私有遥测，无commit/push。已有代码、上一轮研究和历史验收保持，Memory只追加；[检查回执](research/agent-control-products-20261015/validation.json)。部署仍以T8-B终态为准，主线和原付费方案状态不变。

### Codex 进展：国内产品与私有交付场景调查（2026-10-15）

用户继续产品调查。本轮完成[百炼、HiAgent、千帆综合报告](research/china-agent-products-20261015/REPORT.md)，三份产品分册、34项一手来源登记（百炼12、HiAgent10，其中1项为仓库版本元数据、千帆12），以及[部署与数据边界](research/china-agent-products-20261015/deployment-boundaries.md)、[研究QA](research/china-agent-products-20261015/QA.md)。这些是厂商不同资料类型的核对，不是独立实测；产品价格、完整私有版功能矩阵、客户采用意愿与收益仍未验证。

定位修正：私有化、统一工作台、外部Trace接入、观测评估及有人审都不能单独作为EAP独有优势。百炼官方资料已有专属/私有云形态，千帆有私有化/混合云历史记录；火山官方文档描述HiAgent私有化集成，但未取得完整离线交付合同。私有平台不等于模型、工具、评估、遥测和许可都不出网；EAP也按相同标准核对，HASH_ONLY保存不证明正文从未经过传输。

值得借鉴：百炼的发布差异与线上Span进入版本化评测、HiAgent对高代码应用的OTel/自定义推理评估接入、千帆对评测对象/渠道发布与指标窗口的说明。EAP继续复用已有WorkItem、身份/工具合同、候选谱系、评估/留出和价值基础，研究接入责任、异常原结果查证与改进采用的用户任务，不重建这些底座。

关键边界：百炼高代码是上传托管，不等于任意外部Agent原地纳管；Managed Agents有人审等待与回填，pause只停定时触发，不阻手工触发，不能推定原业务效果取消。HiAgent的外部观测/评估不证明外部执行控制；DataAgent目录及Hibot模块分开，AgentSphere未获可靠证据。千帆当前A2A文档是平台应用对外发布，不能推出反向导入治理；试用/专享资源等限定单列。SDK开源不证明平台服务端开源或免费私有交付。

下一轮产品研究宜减少扩名单，对同一组外部Agent接入、工具超时查证、失败样本到改进采用的案例做逐步对照，并验证客户实际痛点与接入代价。“已有异构Agent的责任和原结果难查证”仍是待客户验证的定位假设；不把公开文档缺失当作竞品没有能力，也不把EAP特定受控计数适配器的保证宣传为通用业务能力。

本轮仅公开资料研究、静态代码读取与文档校验；未实操竞品、访谈或对外联系，未执行PoC、模型调用、应用测试、构建或部署，未commit/push。原文件和Memory前缀保存结果见[validation.json](research/china-agent-products-20261015/validation.json)。T8-B已关闭及当前部署不变，主线未完成、原付费验证方案状态不变。用户“先做产品调查”范围继续生效。

### Codex 进展：三个统一产品案例比较（2026-10-15）

用户继续产品调查。本轮不再扩厂商名单，以同一虚构 IT 服务场景比较“已有自托管 Agent 接入”“创建工单超时后查原结果”“分类错误到候选比较与采用”。交付[综合报告](research/agent-product-scenarios-20261015/REPORT.md)、三份案例分册、[EAP 当前旅程](research/agent-product-scenarios-20261015/eap-journeys.md)、研究契约和[反方检查](research/agent-product-scenarios-20261015/QA.md)。三份来源登记共23项、20个不同URL，包含前轮资料复读，不是23次独立验证。

新增正面证据：IBM 外部教程可用协议包装与遥测保留 EKS/GKE 等宿主；ServiceNow 可通过 SDK 为外部自建应用接入观测和评分，遏制还明确逐 PEP 确认完成，不能描述成只发请求。HiAgent 已有外部观测/自定义推理评测。百炼本轮核实的高代码上传托管路径改变了“保留原宿主”的案例前提。外部接入不能自动继承平台原生优化能力，执行点控制回执不证明原业务工单未创建。

EAP 静态核对修正产品建议：已有外部 HTTP/MCP/A2A 登记，但专属实例身份与实验目标仍有 Substrate 合同范围；已有原操作查询 UI、WorkItem 及单 Agent/团队多种候选比较，不应重建。WorkItem 认领/处理/解决 UI 与后端等待/关闭状态不同。当前强效果保证限于受控计数适配器，真实工单仍缺具体原请求关联与查询证据；线上问题来源进入样本/比较、比较结论交给发布的连续旅程未由本轮证实。T8-B 模型审批不等于候选发布审批。

下一步产品研究应验证异常后的原业务结果查证是否值得采购：需要真实事件频率与损失、现有处理基线、可靠查询条件、接入/维护成本及预算责任。当前仅低至中等置信的需求假设，不能宣称市场已验证。若单云运营足够、企业工单平台已经解决或客户只需离线评估，则新控制平面可能不值得购买。评测要分别固定数据/评分口径、候选与依赖、采用/发布对象。直接 Galileo 集成、EAP 原生、两者组合继续分开。

本轮只有公开资料和本地静态读取，未实操竞品、访谈、对外联络、PoC、付费调用、应用测试、构建或部署，未commit/push；没有外发源码、私有遥测或凭证。未来合成工单 PoC 只记录方案，不表示执行授权。来源有链接/日期/读取范围及部分短摘录，未完成所有关键网页的完整快照归档，不能标记全部 deepresearch 质量门槛通过。文件保护与文档检查见[validation.json](research/agent-product-scenarios-20261015/validation.json)，产物摘要见[manifest.json](research/agent-product-scenarios-20261015/manifest.json)。T8-B终态、主线未完成和原付费验证待授权状态均保持；后续一般“继续”仍沿产品调查推进。


### Codex 进展：采购替代与采用成本调查（2026-10-15）

用户继续产品调查。本轮完成[采购替代与采用成本报告](research/agent-buying-options-20261015/REPORT.md)，AWS/IBM/ServiceNow 分册、[EAP 交付责任与 TCO 模板](research/agent-buying-options-20261015/eap-adoption.md)、[客户案例反证](research/agent-buying-options-20261015/market-evidence.md)和[需求验证记录卡](research/agent-buying-options-20261015/customer-validation-card.md)。18项来源定位，其中 IBM Cloud Catalog 的价格正文不可稳定复核，未采用搜索索引费率；其余依据实际读取的公开正文。保存公开 HTML 响应或明确标识的文本节选，未将403响应、动态壳当有效正文，也未声称完整离线网页/客户采购体验归档。

采购判断继续条件化：已有 AWS 原生组合、IBM/ServiceNow 权益与企业自建工作都是真实替代。Syngenta 的多账户治理/SDK、Mobileye 的混合工单编排/MCP和内部部署平台，不能证明自建工作很少或很多，也不证明客户会另买 EAP。当前值得验证的是保留宿主与正式工单系统时，能否减少一项高成本跨系统原结果查证/交接任务；必须有实际事件、现有方案缺口、可信业务查询、维护方和预算责任。没有这些证据，允许沿用现有方案，不以购买为研究终点。

计费边界：AgentCore 短期 Memory 自2026-10-06改按写入/读取GB和GB-month，旧事件价不能混用；Identity经Runtime/Gateway使用有包含关系，其他云/模型/遥测费用另核。IBM公开Essentials从USD530/月、Standard从USD6360/月起，明确control plane；评估/观测的JSON-LD与可见付费列表有差异，付费权益/额度未知，不能由Trial推定。ServiceNow询价，4月9日文件按指定受管model/system/dataset记录计SU，不等于一个Agent一次调用；Store SKU/EA额外权益和目标版本需另核。海外标价不证明中国区可采购或同等私有交付。

EAP静态核对：管理台ROI的成本主要来自模型遥测，收益为登记ValueEvent，不含完整许可/基础设施/人工/适配/维护；缺金额可存零，不代表免费。现有业务价值连接器为GET读取并映射ValueEvent，不是工单原结果查询或双向事项同步。专属身份/实验仍有限定范围，强效果限受控适配；已有恢复验收不等于长期灾备和客户SLA。原基础不重建，但真实业务适配与长期责任不能隐藏在“接入成功”中。

反方审查已修订两点：不预判企业自建投入轻重；未来走查同时对照现有流程、利用已购产品配置/小扩展后的流程、拟议EAP补充流程，使用相同证据/权限/判据，避免把普遍流程改善都归因EAP。额外交叉复核修正IBM权益推断。成本一次性/周期性分开，未知不按零，未停用旧产品费用不扣除；当前无客户数据，不出总价排名、ROI或回本期。直接Galileo、EAP原生及组合仍分开。

本轮未访谈或外联、注册试用、执行竞品/工单操作、PoC、付费调用、应用测试/构建/部署，未commit/push或外发私有材料。仅新增研究文档/公开快照并追加记忆；[QA](research/agent-buying-options-20261015/QA.md)、[文件保护检查](research/agent-buying-options-20261015/validation.json)和[摘要清单](research/agent-buying-options-20261015/manifest.json)归档。T8-B终态、主线未完成、原付费验收待授权状态保持；后续一般“继续”仍在产品调查范围，可优先研究具体业务系统原查询合同或已购平台小扩展的可行边界，不自行转开发或联系客户。

### Codex 进展：业务工单原结果合同调查（2026-10-15）

用户继续产品调查。本轮从控制平台进一步核对被调用的业务系统，完成[工单超时后的原结果报告](research/business-result-contracts-20261015/REPORT.md)、Jira Cloud/ServiceNow/Zendesk 三份分册、[EAP 六对象族映射](research/business-result-contracts-20261015/eap-contract-map.md)与[接口能力记录卡](research/business-result-contracts-20261015/connector-assessment-card.md)。16项一手来源定位（Jira6、ServiceNow6、Zendesk4）均登记访问日、适用范围和归档类型；Z02仅取得官方索引段落，完整Tickets大页读取失败，已降证据强度；不能宣称所有来源均有完整网页快照。

新增正面证据：Zendesk 单条 Tickets 创建文档明确 Idempotency-Key，同键同请求体复用响应、同键异参拒绝，键两小时过期；过期后可能新建。Jira 有已知 ID 读取、增强搜索 reconcileIssues（最多50个已知 ID）与 webhook 线索；未知创建ID不能直接使用 reconcile。ServiceNow Table 支持字段查询，单条 Import Set 同步转换并返回目标 record_link，coalesce 匹配更新/不匹配插入；唯一字段和 Scripted REST 是客户扩展路径。这些能力支持“原系统或小扩展已足够”的替代，不能重命名为 EAP 独有能力。

关键边界：幂等创建重试仍可能首次写入，不可藏在“刷新查证”后；缓存的历史响应不等于工单当前状态。关联字段不自动唯一，搜索空结果、权限不可见或导入回执历史清理不证明未提交；ServiceNow coalesce 不是只读回放或所有副作用 exactly-once。Jira/ServiceNow 指定接口的通用调用方幂等合同本轮未确立，不推整个厂商没有相关产品或扩展。

EAP 静态核对：普通 ToolGuard 回调异常可报告 FAILED，即便外部已创建后读取响应超时；UI“执行方报告失败”应保留。callId、指纹和一次领取是本地合同，不自动成为供应商幂等键/业务 ID。强存储证明、原操作纯读取和持久查询责任仍限受控计数；价值 GET 导入与 WorkItem 的 ticket 引用不是通用工单查询适配。复用已有对象，不再建事项或账本；直接 Galileo、EAP 原生和组合继续区分，评估分数不能证明业务提交。

产品建议：并列说明原生幂等、只读关联和辅助搜索；分别记录官方文档支持、目标环境验证和未知。反方审查修正了“必须公开所有并发/失败边界才承认文档支持”的过严标签门槛。动作性质、证据来源/时间与未确认责任比统一“恢复”按钮更值得评审，但客户频率、成本、愿付仍未验证。未来走查继续同条件比较现有流程、已有平台配置/扩展、EAP补充，允许不采购；有界合成PoC只写方案，未执行。

本轮仅公开资料读取、本地静态核对与文档完整性检查；未外联、注册、调用租户API、写工单、执行PoC/付费调用/应用测试/构建/部署，未commit/push或外发私有资料。新增研究目录并追加记忆，保护结果见[validation.json](research/business-result-contracts-20261015/validation.json)，[QA](research/business-result-contracts-20261015/QA.md)和[manifest](research/business-result-contracts-20261015/manifest.json)归档。T8-B终态、主线未完成及原付费验收待授权状态保持；后续“继续”沿产品调查，可进一步比较已购平台小扩展与EAP补充的具体操作旅程和维护责任，不自行转开发或外联。

### Codex 进展：已购平台扩展后的产品旅程比较（2026-10-15）

用户继续产品调查。本轮完成[已有平台扩展与 EAP 增量报告](research/incumbent-workflows-20261015/REPORT.md)、Jira/ServiceNow/Zendesk 三份旅程分册、[EAP 静态映射](research/incumbent-workflows-20261015/eap-workflow-map.md)及[同条件走查与维护责任表](research/incumbent-workflows-20261015/walkthrough.md)。新登记24项官方来源（Jira6、ServiceNow6、Zendesk12），26份来源归档含2张官方原图；其余为HTML或明确节选，不是完整网站归档。既有API合同引用前轮，不宣称全部重验。客户实际流程、权益、耗时和收益仍未知。

新增强替代：Jira Incoming webhook 官方图明确 No issues from the webhook，可在没有原业务 ID 时触发，再建立独立处置事项；ServiceNow 有异步 REST 触发，需 Integration Hub Enterprise，也可先经已配接口落异常记录再记录触发。Zendesk 新 Action flows 可接外部 HTTP 自定义触发，已有查询动作、错误分支、轨迹和失败通知；ZIS 另有入站路径，ZAF 可在原工作台扩展查证。不能仅以传统 ticket trigger 作对照，也不能把这些组合当已经实测。

产品判断进一步收窄：新增异常工作台、负责人、状态和自动化不独有。优先验证把原调用依据交给现有处理流程能否减少补录、找证据和交接；如果已购平台配置/扩展已经满足，则不增加EAP。业务记录、异常处置事项、自动化执行上下文分开；处置创建响应本身也可能丢失，不能把处置ID当业务ID或无限递归开单。每项责任明确正式维护处，允许多团队关联子任务，不要求一切合并为单队列。

运行语义边界：ServiceNow context Complete 可包含 error caught/skipped，不等于业务成功；运行详情开关和保留不是业务事项保留合同。Zendesk ZIS 特定错误会从头重试整条flow，Action flows 的按步骤重试和 Support webhook 投递另论；日志七天不外推所有轨迹。Zendesk两页25/50与100/60的flow/步骤上限冲突保留未知；Copilot自定义动作许可/更新说明按小节限定。所有客户配置、额度、网络和维护投入另核。

EAP新增精确核对：WorkItem已有人员、状态、证据、itemKey信号聚合与复现重开，但没有本轮可见的普通ToolGuard FAILED/无回执或受控工具UNKNOWN专门自动建项来源；AgentOps也未见通用外部异常建项接口。治理待批来源未按direction过滤，可能包含TOOL_INPUT，不能说只有模型；待批事项不等于工具失败处置或批准续跑。首页与Agent详情证据输入有差异，后端事件API存在不等于完整历史UI；真实外部工单交接尚不能由现有对象推出。受控计数保证不扩大，Galileo/EAP原生/组合继续区分。

反方审查已改正主稿sourceKey→itemKey，并补全Zendesk Copilot小节限定。未来走查同条件对照A客户实际流程、B已购平台扩展、C加EAP，记录投入和错误，不编造节省百分比；合成PoC只写方案，未执行。仅新增本轮研究并追加记忆，未外联/注册/调用租户/写工单/运行应用测试构建部署/付费调用/commit/push。原工程和记忆前缀保存见[validation](research/incumbent-workflows-20261015/validation.json)，另有[QA](research/incumbent-workflows-20261015/QA.md)与[manifest](research/incumbent-workflows-20261015/manifest.json)。T8-B终态、主线未完成和原付费验收待授权保持。后续一般“继续”仍做产品调查，可回到“失败样本→候选比较→采用”这一主线用户任务，与已有平台优化功能对照，避免持续围绕工单单一场景缩窄整体EAP定位。


### Codex 进展：失败样本到改进采用的产品调查（2026-10-15）

用户“继续完善”仍沿产品调查。本轮回到失败样本→候选比较→采用/生效主线，完成[综合报告](research/improvement-adoption-20261015/report.md)、[产品决策与有界走查](research/improvement-adoption-20261015/product-decisions.md)、AWS/LangSmith/Galileo三份分册与[EAP六对象族核对](research/improvement-adoption-20261015/eap-improvement-map.md)。21项官方来源（AWS6、LangSmith7、Galileo/Splunk8）；保存6份公开HTML响应、7份解析节选、8份短引文/摘记，不称全部完整网页归档。部分API/SDK参考读取失败；文档机制不等于租户实测。

重要正向反证：AgentCore发布记录已列Recommendations、Batch Evaluations、A/B GA；具体A/B流程要求Gateway/variants/在线评分，默认开始分流，promote停止实验并写配置后仍需deploy。配置bundle由Gateway传引用，运行时须取用，不能自动替换硬编码提示词。AWS已有原生改进路线，不能再只描述成零散观测/评分服务；旧trace重评、运行新候选、生产分流是不同证据。Bedrock提示词优化是另一路径，不与AgentCore或外部宿主能力无条件拼接。

LangSmith已支持run→审核/修正→版本化样本→逐例比较；Production是commit tag，可影响动态读取的应用。指针更新、实际加载commit、全部应用生效分开；SDK缓存默认值不是全实例一致SLA。实验metadata可改，不证明代码/模型/工具依赖冻结；自动附加评分器也需核对。当前Splunk文档分列业务ground truth与evaluator ground truth，比较要求输入位置对应；已有输出评分不证明候选重跑。SaaS不提供custom code-based evaluators；旧Galileo SDK兼容及旧memo客户日期分界未重证，不作采购保证。

EAP核对进一步明确：已有失败评估事项、反馈、样本/校准、单Agent对照/优化、团队草稿/候选/留出和独立版本发布，不重建引擎。Evaluation数据集version本身不令样本不可变，历史校准快照与优化合同冻结samples是不同对象；PASS/FAIL/ABSTAIN不代替分类任务正确expected。当前UI新增样本仍为示例路径，真实API存在不等于Trace入集旅程打通。反馈ADOPTED仅改状态/评审人，实验候选只作用于该次请求；正常runner仍用发布配置。已有部署新版本/预检，不等于候选证据与采用决定已绑定发布；新实例READY不证明旧流量切换，T8-B不是候选发布批准。

产品建议收敛为三项交接：问题来源带入正确业务标签；源样本关联本次冻结比较及逐例回归；采用决定关联发布和指定请求实际版本。优先复用客户原平台和EAP现有对象，若原平台加既有流水线已足够则停止新增。H1原平台能力足够的反证增强；H2机制风险可证但客户主因未知；H3跨宿主采用交接有商业价值仍低置信、待需求验证。避免继续只围绕工单收窄整体定位，也不拿“治理关联”自动作为已证差异化。

反方审查修订：配置/tag变化只证明运行选择条件变化；不预判客户根因概率；16次调用只作单阶段基线/候选的纸面上限，EAP每批1–3样本需另列，优化/团队须重算；不把位置对应要求升级为未公开配对算法。未来PoC仅写方案，未执行，不替代原28调用/9728输出Token/工具0待授权方案。

本轮只有公开资料读取、本地静态核对和文档校验；未外联/注册/调用租户或模型/执行实验A-B发布/运行应用测试构建部署/commit或push，未外发私有资料。只新增本轮目录并追加记忆；[QA](research/improvement-adoption-20261015/QA.md)、[validation](research/improvement-adoption-20261015/validation.json)、[manifest](research/improvement-adoption-20261015/manifest.json)归档。T8-B终态、主线未完成与原付费验收待授权状态不变。后续一般“继续”仍在产品调查，可优先把三项交接做成可走查的产品方案或用真实工作流材料检验是否值得新增，不自动转开发、外联或付费执行。


### Codex 进展：改进交接的产品方案与纸面走查（2026-10-15）

用户继续完善，仍在产品研究范围。本轮将失败来源→样本、样本→比较、采用→发布/生效三项建议具体化为[产品方案报告](research/improvement-handoff-design-20261015/report.md)、[三处现有页面结构与变化规则](research/improvement-handoff-design-20261015/page-blueprint.md)、[填写完整的合成案例及十个反例](research/improvement-handoff-design-20261015/paper-walkthrough.md)、[EAP入口/权限/持久化边界](research/improvement-handoff-design-20261015/eap-design-boundaries.md)和[原平台替代反方](research/improvement-handoff-design-20261015/alternative-workflows.md)。不是界面已实现或PoC验收，未改业务代码。

新增具体发现：/learning、/evaluation、/team-evolutions未消费来源query；观测生成的/learning?agentId链接不等于能定位实验。三项交接可放现有入口，暂不新增一级工作台；但来源一致性、标签/冻结副本、候选采用决定及发布关联需要后端持久关系与历史，不能把全部工作称为加按钮。已有团队候选、标注审计和历史校准不能再建。Feedback ADOPTED只改反馈状态/评审人，不能充当绑定候选的发布批准。观测内部有部分派发时冻结的实例/UID/模板基础，但详情未暴露完整联合证据，不能仅前端拼接即声称指定候选生效。

权限核对更精确：责任人称呼不等于角色，仍用PlatformPermission与/me能力。DatasetItem GET为viewConsole并返回采集模式处理后的材料；Feedback/实验等正文另有viewContent规则，不能把所有正文统一概括为同一开关。HASH_ONLY不能还原原文，OFF/withheld不因权限增加变成可重放。原Trace标识也不自动证明样本内容来自该Trace。Splunk本轮当前官方权限页还明确按部署区分，SaaS需专门Agent Observability角色，不能推出跨产品权限继承。

产品规则收敛：源标签修订保留旧比较历史，新的适用范围单独解释；候选内容变化不能继承旧好成绩；重评旧输出与运行新候选分开。采用/发布优先引用客户已有正式记录，避免同一人再次填标签、评分器、审批人和发布时间。必要运行快照有价值，但不要复制成第二套可变事实库；也不能只留可变tag或会过期URL。手填sourceId/digest/resolvedVersion仍是声明，须能回到来源核对。

纸面6例为人工编写，示意基线3/6、候选4/6，其中关键安全类别回归，示例决定不采用。算术经文档校验，不是模型成绩。10个反例涵盖缺标签、标签修订、重排、候选/评分变化、哈希、漏评分、部分目标旧版、tag无消费证据和权限/过期。两个目标生效场景是独立手工反例，未接续不采用的候选。A现状、B原平台小扩展、C加EAP同材料/权限/判据，客户现状和耗时留未知；C若只能用合成副本而B用真实请求，不当成同任务生产效果比较。

本轮5项来源定位对应4个官方URL（AWS同页主/反方各读一次，不计独立互证），保留短引文/摘记，其他事实继承上一轮并注明。反方审查保留原平台规范可能已足够的停止条件；代码审查收紧标签变化不自动引发新模型调用。未做外联、试用、租户/模型调用、应用测试构建部署、commit/push；原28调用/9728输出Token/工具0待授权方案不变，T8-B终态与主线未完成状态不变。

只新增本研究目录并追加记忆，质量范围见[QA](research/improvement-handoff-design-20261015/QA.md)，保护与校验见[validation](research/improvement-handoff-design-20261015/validation.json)及[manifest](research/improvement-handoff-design-20261015/manifest.json)。后续一般继续仍沿产品研究；适合审阅这份具体方案、判断是否引用现有变更记录已够，或取得真实工作流材料验证重复交接是否确实存在，不自行转开发或付费执行。


### Codex 进展：改进记录导航已编码，远程验证受阻（2026-10-15）

用户明确“继续完善代码”，结束仅产品研究的限制，开始实现产品交接中可复用现有接口的第一段。本轮只实现 `/learning` 的导航与记录核对；未实现来源→样本持久关系、候选采用决定、发布绑定或运行生效证明。未改后端、数据库迁移、中央权限定义或运行时。

已实现：`agentId` 筛选现有列表；`experimentId`/`optimizationId` 直接读取已存在的 GET 详情接口，不受最近列表是否含该记录影响；优化评测中的配对实验编号可跳回原实验，两个详情提供独立链接。指定记录时直接展示对应面板，不读取无关反馈列表、Agent 列表或优化开关；正文仍需显式操作和 `viewContent`。打开链接不读取运行 profile、不唤醒实例、不提交实验或模型任务。Agent 列表筛选仍限于接口返回的最近实验/优化记录，不宣称获取该 Agent 的全部历史。

链接只接受单个正安全整数，拒绝重复/数组、空值、非整数、溢出及同时指定两种记录。响应记录编号及显式 Agent 上下文不匹配时清除结果并提示；URI 参数只用于定位，不作为授权或采用凭据。切换上下文使旧元数据/正文请求失效；读取失败不能保留旧正文。优化轮询同一记录保留已主动加载的正文，切换目标或权限变化时清除。反馈、对照实验、优化任务提交中/回执未确认时阻止同页记录切换；UNKNOWN 操作治理提交也增加同样保护。修复反馈 `openForm` 对 ref 漏用 `.value`，使新增反馈可带入当前 Agent，未知提交重开仍保留原内容。

新增 `learning-links.test.mjs` 和共享测试支架，更新原三个相关测试支架及 UNKNOWN 治理导航用例，覆盖参数解析、直接 GET/无自动执行、Agent 筛选、归属不匹配、异步竞态、失败清理、正文权限、取消筛选跳转和未确认提交保留。这些是脚本级逻辑用例，**尚未执行**，不能代替真实 Vue Router/浏览器验证。当前修改范围为 11 份前端源码/测试文件（8 份既有文件、3 份新增文件）。仅本轮增量及逐文件摘要见 [changes.patch](evidence/learning-links-20261015/changes.patch) 和 [source-manifest.json](evidence/learning-links-20261015/source-manifest.json)，避免把原工作树大量变更归入本轮。

验证状态：本轮开始经授权 `n1.sh` 的 hostname、节点和缓存目录只读检查成功，随后查询集群/hostname 的三轮连接（包含沙箱外重试）均发生 `Connection timed out during banner exchange`，最终 exit 255。**没有发送源码上传或测试 Pod 创建命令，npm test、类型检查、生产构建、浏览器验收均未启动；没有部署或规范源推进。** 不把 SSH 超时解释成密码错误或应用故障，不沿用 T8-B 的 318 项结果作为本轮通过。只完成静态 diff/空白检查及原文件摘要保护核对，本机未运行 Node/npm/mvn/java/docker 或安装依赖。

待接续：先恢复同一 n1.sh 通道，以最新 813 源/固定镜像/Flyway83 建独立前端候选目录，摘要核对后远程 Node 22 完整 `npm test` 和 `npm run build`，再核对实际路由、返回导航、权限和未知提交路径。详见 [CODEX_NEXT_TASK.md](CODEX_NEXT_TASK.md) 顶部和 [validation.json](evidence/learning-links-20261015/validation.json)。本机临时包 `/private/tmp/eap-learning-links-20261015.tgz` 仅供摘要一致时续用；它未上传，临时文件若丢失应按当前源码重建。连接恢复前不把本轮标成已验收。

本轮没有 commit/push、外部服务私有资料传输、模型或业务工具调用，没有修改现网。T8-B 终态与旧证据保持；主线仍未全部完成，原 28 调用/9728 输出 Token/工具 0 的真实验收计划仍待单独支出授权。后续“继续”优先完成这次代码验证，不再默认回到产品调查，也不重放此前已关闭的发布/清理入口。


### Codex 进展：改进记录导航 r2，补齐跨页与治理提交保护（2026-10-15）

用户继续要求完善代码。本轮沿上一轮导航切片查漏，没有转回产品调查或扩大到持久化采用/发布。静态检查发现：r1 仅注册 `onBeforeRouteUpdate`，离开整个页面仍会卸载待确认提交；优化列表的行选择、刷新和创建结果也能绕过路由守卫卸载 UNKNOWN 治理核对组件。原冻结 requestKey 保存在组件内，因此卸载会丢失原键核对入口。

修订 4 个 Vue 组件与测试支架，新增一个真实路由测试文件，共 6 份前端文件。反馈、实验、优化、UNKNOWN 治理均使用同一组件内离开检查绑定 update/leave 两种守卫。UNKNOWN 核对通过 `defineExpose` 给父面板提供相同检查；列表选择、元数据刷新、新建任务不打断 pending/uncertain 治理提交。异步元数据响应在返回时再次检查；失败时清除旧受保护正文，保留原核对组件并明确提示本次记录未刷新。父面板提交期间禁用子组件治理提交，避免交叉写入。没有改后端协议、权限定义、预算或原未知结果。

新增 `learning-router.test.mjs` 使用实际 Vue 响应式/生命周期、RouterView、memory history 和导航守卫，补足 r1 手动调用守卫的测试支架限制；API 使用内存替身，host 没有 DOM。用例覆盖三类主提交的同页/跨页阻断，嵌套治理原键保留，列表/刷新/在途响应保护，读回已保存决定后解锁，反向并发写入门控，独立链接/返回、旧响应及权限撤销。**尚未执行，不能称路由实测通过，更不能称 Element Plus 或浏览器验收。** 这些用例将随现有完整 `npm test` 在远程执行，无新增依赖。

本轮 n1.sh 两次只读 hostname 调用分别在沙箱内和已授权沙箱外重试，均连续 SSH banner exchange timeout，最终 exit 255；没有发出源码上传、测试 Pod 创建或应用变更命令。远程测试、vue-tsc/Vite 构建、浏览器均未开始；本机仅做文本编辑、diff 空白检查、归档和摘要核对，没有运行 Node/npm/mvn/java/docker 或安装依赖。不能复用 T8-B 的 318 项通过或上一轮静态检查为新代码背书。

[本轮回执](evidence/learning-links-20261015-r2/validation.json)、[仅 r2 增量](evidence/learning-links-20261015-r2/changes.patch)、[源码摘要](evidence/learning-links-20261015-r2/source-manifest.json)、[候选包摘要](evidence/learning-links-20261015-r2/candidate.json)独立归档，r1 证据不覆盖。CODEX_NEXT_TASK 顶部已更新。下一步应先恢复 n1.sh 通道，在独立候选目录完成两轮代码的完整远程前端测试、类型/生产构建及真实浏览器检查，再继续扩展；临时 r2 包若丢失应按当前源码重建并核对摘要。原 813 规范源/T8-B/Flyway83 是已验收基线，不使用已关闭阶段脚本，不直接覆盖 `/root/eap-src`。

未 commit/push、部署、推进规范源、调用模型/业务工具或发送私有资料到外部服务；AGENTS、旧代码/研究/验收证据在本轮授权修改范围外保持。主线未完成，原 28 调用/9728 输出 Token/工具 0 计划仍待单独支出授权。本轮保护针对应用内路由和面板切换，不宣称可以跨硬刷新、关闭浏览器或身份/权限失效持久保存未确认表单。


### Codex 进展：记录导航的隔离验证入口已准备，连接仍受阻（2026-10-16）

用户继续完善代码。本轮先校验当前前端 155 文件与 r2 归档逐文件一致，未继续改应用逻辑。通过既有 n1.sh 发起两次只读 hostname，分别使用默认沙箱与已授权沙箱外重试，最终均 SSH banner exchange timeout / exit 255。已向用户异步询问跳板机维护状态或更新后的 n1.sh 入口，当前尚无答复；未据等待推断授权或恢复。没有发送源码上传、测试 Pod 创建或应用变更命令。

新增 3 份部署辅助源码：`verify-learning-baseline.py` 只在 ate-node1 校验原 151 前端规范源的文件集合及 SHA256，遇差异停止；`verify-learning-input.mjs` 只在指定 Node 22 测试 Pod 核对 155 候选文件集合/摘要并拒绝符号链接；`verify-learning-frontend.sh` 保存 started 标记、依赖/完整测试/类型与生产构建的分别退出码及完整日志，已有尝试即拒绝重复执行，输出持久化在独立本轮目录。原源码/contract 只读挂载，工作区 emptyDir，npm 使用既有缓存；不挂载应用 Secret 或 ServiceAccount token，不覆盖规范源。Pod 使用 Node 22 镜像入口，需实际 imageID/版本回执才能固定验证环境，不把标签当不可变摘要。

准备了 [输入包摘要](evidence/learning-navigation-validation-20261016/bundle.json)、[Pod 清单](evidence/learning-navigation-validation-20261016/pod.json)、原/候选前端摘要和 [待验证回执](evidence/learning-navigation-validation-20261016/validation.json)。CODEX_NEXT_TASK 顶部给出查原对象→上传后单独验摘要→新目录解包→基线核对→创建并冻结 UID→轮询原回执→按 UID 清理的接续顺序。没有重用已关闭阶段脚本，也没有启动这些新脚本。若出现同名对象/旧 started，须先读取来源和原回执，不能删除覆盖或因超时自动重复测试。

本轮仅静态文本审阅、Python AST 语法解析、归档/摘要保护核对；三个辅助脚本尚未执行，前两轮 npm test、vue-tsc/Vite 构建和真实浏览器仍未开始，不记任何新的应用测试通过。没有本机安装依赖或 Node/npm/mvn/java/docker 执行，没有 commit/push、部署、规范源推进、模型/业务工具调用。旧 r1/r2 和既有验收证据保持。下一步先恢复远程通道并完成当前候选验证，主线与原 28 调用/9728 输出 Token/工具 0 待授权状态不变。


### Codex 进展：记录导航完成远程工程与浏览器验收（2026-10-16）

用户“继续？”后，既有 n1.sh 通道恢复并成功连接 ate-node1。本轮完成此前 r1/r2 的验证，没有继续增加业务逻辑。当前 155 份前端源码与 r2 manifest 一致；仅修改基线核对辅助脚本、新增远程浏览器验收脚本并归档证据。本机没有运行 Node/npm/mvn/java/docker 或安装依赖。

基线首次核对只报告额外文件 `Dockerfile.cluster`。只读检查确认它是旧集群构建配方，SHA256 为 `de1a2d7296b9a118697c55f64853b9452bcc93080a12ec1f7c29bf868d020807`。辅助脚本改为对它单独校验固定摘要，仍拒绝其它多余、缺失或变化文件；原 151 份前端源加 1 辅助文件在测试前、清理前均通过。首轮失败记录保留，没有将辅助文件算作应用源变更。

经 n1.sh 上传、独立核对归档长度/SHA256 后，在隔离 Pod 执行 Node v22.23.3 的 npm ci、全量 npm test、npm run build；各阶段原退出码均为 0，Pod Succeeded、容器 exit0、result complete0 一致。**351 项测试全部通过，失败/取消/跳过均 0；vue-tsc 类型检查与 Vite 生产构建通过。** 新增真实 Vue Router 的 10 个用例均在原 TAP 中出现并通过。实际 imageID 为 `docker.m.daocloud.io/library/node@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402`，没有用可变标签代替镜像记录。

真实 Chromium 使用候选 dist、实际 Vue/Element Plus 和完全合成的 API，不连接应用后端、Keycloak 或模型。9 组覆盖历史独立链接与刷新/返回、重复/冲突/归属不符/404 链接、Agent 筛选与返回、UNKNOWN 治理提交的选行/刷新/路由阻断及读回解锁、反馈和实验回执丢失后的原内容/原键核对、390px 无正文权限页面、元数据失败清正文、身份失效后拒绝迟到正文。最终没有未捕获页面异常或意外网络请求。首轮 r2 在隐藏 checkbox 上定位失败；r3 改为可见标签与实际导航链接后前五组通过，第六组定位内部 readonly input 被 placeholder 遮挡；r4 改为可见下拉 wrapper，仅重跑第六至第九组，四组通过。三次原报告及失败截图保留；前五组与后四组 dist 逐文件摘要相同，不宣称首次全绿。抽看手机只读、实验核对和治理核对后截图；故障场景错误提示来自主动模拟断线，未做全站视觉审计。

[最终回执](evidence/learning-navigation-execution-20261016/validation.json)、[测试/构建](evidence/learning-navigation-execution-20261016/unit-build.json)、[浏览器及原报告索引](evidence/learning-navigation-execution-20261016/browser-summary.json)、[清理记录](evidence/learning-navigation-execution-20261016/cleanup.json)已归档。四份远程归档核对长度、SHA256 和逐文件 manifest 后提取。四个临时验证 Pod 按冻结 UID 清理并确认剩余 0，远程输出保留。应用后端/前端 Deployment 仍引用 T8-B 两个固定镜像，规范前端源未推进。本轮没有重验后端、数据库或真实登录，不能称生产验收或全部主线完成。

未部署、未 commit/push、未调用真实模型/业务工具、未写实际业务记录，AGENTS 与旧验收/研究证据保持。浏览器能力来自合成 /me，不是新增 JWT/Keycloak 验证；身份失效仅验证客户端清理。链接仍只定位记录，未实现来源→样本持久关系、候选采用/发布绑定或运行生效；未确认表单不保证跨硬刷新/关闭浏览器恢复。原 28 调用/9728 输出 Token/工具 0 方案继续待单独支出授权。

CODEX_NEXT_TASK 顶部已更新为已验证、未发布。所有本轮命名验证/归档/清理入口关闭，不重新创建相同 Pod 或覆盖原输出，不重放 T8-B/T3 关闭入口。后续区分本地已验证候选与现网原基线继续：新改动另建验证尝试，发布另建当时部署基线。


### Codex 进展：评估工作区权限与异步状态保护完成远程验证（2026-10-16）

用户继续要求完善代码，本轮沿已通过导航验收的 155 文件候选推进。审阅发现 `EvaluationView` 未按 `/me` 能力控制写入入口，身份失效后的迟到响应和轮询仍可能填回旧数据，部分写入缺少重复点击保护。修订该页面、API 标注人可选类型及两份既有测试，新增共享测试支架和 18 项行为测试；候选共 157 份前端文件。仅本轮六个前端文件的增量见 [changes.patch](evidence/evaluation-access-20261016/changes.patch)，没有把历史工作树修改合并计入本轮。

已实现：运行评估、提交任务、示例数据集与校准按 `manageEvaluations` 控制，标注按 `annotateEvaluations` 控制；只读账号保留数据读取与已有任务跟踪。前端只消费 `/me` 能力，不解析 JWT 或按角色名授权，后端 `PlatformPermission` 和最终鉴权未改。JWT 模式隐藏标注人输入且不发送自填身份，既有后端从认证对象确定审计人；开发模式保留原输入。五类写操作共用忙碌状态，函数入口与按钮均防并发重复/交叉提交；失败不自动重试，示例创建的部分失败提示原数据集键供核对。示例键改用 UUID，两步创建之间再检查当前身份和能力。

页面使用访问代次、请求序号和独立轮询代次处理竞态。身份或相关能力变化同步清空数据、详情、表单与加载状态，取消旧定时器；旧请求完成不能填回内容、显示旧成功提示、解锁新提交或清掉新轮询。读取样本、校准历史/快照和关联 Trace 失败时清除相应旧内容并显示可处理的错误。`load` 在子读取结束后再次检查代次。新增用例执行完整脚本和真实 Vue refs/watchers，API 与计时器使用可控替身；它们不替代浏览器验证。

全部编译、依赖安装、测试和浏览器执行均经已授权 `n1.sh` 在远程隔离 Pod 完成，本机仅文本编辑、归档、摘要与 diff 检查。r1 的 369 项测试全部通过，但 vue-tsc 报两处 `activeJob` 被收窄为 `never`：watcher 先清空 ref，异步加载后的赋值不能被 TypeScript 推断。将后续任务读取拆为 `resumeCurrentJob` 后，新建 r2 完整重验，**369/369 通过，失败/取消/跳过均 0；类型检查和 Vite 生产构建通过**。源码核对、npm ci/test/build 原退出码均 0，Pod Succeeded/容器 exit0/result complete0 一致。实际 Node v22.23.3、固定镜像及完整 r1 失败/r2 通过日志均归档；没有将首轮写成全绿。

Chromium 152.0.7977.82 加载 r2 的生产构建，使用真实 Vue/Element Plus 和完全合成 API，7 组场景全部通过：390px 只读页、仅标注权限与省略自填身份、评估重复点击后身份失效、两步创建中间身份失效、迟到总览响应清理、只读任务轮询停止、样本读取失败清除。页面未捕获异常和意外请求均 0，浏览器 dist 摘要与构建产物一致。截图中的开发环境状态用于无凭据启动，`/me.mode=JWT` 也是合成响应；这里只验证客户端行为，不是 JWT/Keycloak 真实登录验收。抽看手机只读和标注页面截图，未做全站视觉审计。

[最终回执](evidence/evaluation-access-20261016/validation.json)、[测试与构建](evidence/evaluation-access-20261016/unit-build.json)、[浏览器及截图](evidence/evaluation-access-20261016/browser-summary.json)、[清理回执](evidence/evaluation-access-20261016/cleanup.json)已保存。三份归档均核对长度、SHA256 和逐文件 manifest。执行中服务重启仅打断下载；恢复后先检查远程任务和本地文件，未重跑验证。三个临时 Pod 按冻结 UID 清理，剩余 0，远程输出保留。测试前及清理前原 151 份规范前端源和固定摘要的辅助 Dockerfile 匹配，应用仍引用 T8-B 两固定镜像。

未部署、未推进 `/root/eap-src`、未 commit/push，真实模型和业务 API 写入均 0。旧文件保护核对仅允许本轮四份既有前端文件及两份交接文档变化，记忆旧内容作为完整前缀保留；AGENTS、后端、研究与旧验收证据不改。本轮不保证跨硬刷新/关闭浏览器恢复，也没有增加服务端幂等；既有示例数据集仍是演示样本。来源→真实样本的持久关系、候选采用/发布关联与运行生效证明仍未实现，主线未全部完成；原 28 调用/9728 输出 Token/工具 0 真实计划仍待单独授权。

CODEX_NEXT_TASK 顶部已更新。当前 157 文件是已验证、未发布的本地候选；下一小段可推进真实样本录入及来源关联，先定义持久契约和验证范围。本轮命名测试/浏览器/归档/清理入口关闭，不重放或覆盖；新代码另建验证尝试，若发布则另建当时现网基线。


### Codex 进展：自定义回归数据录入完成远程验证（2026-10-16）

用户继续完善代码。本轮确认后端已有自定义数据集及样本创建接口，样本已支持 Agent/Trace/Span 字段，但前端仅有固定示例创建。新增 `EvaluationDatasetEditor.vue`、`datasetEntry.ts` 和 20 项回归测试，更新 `EvaluationView` 与共享测试支架，共五份前端文件；完整候选从 157 增至 160。后端和数据库无改动。仅本轮增量见 [changes.patch](evidence/dataset-entry-20261016/changes.patch)，[范围与契约](evidence/dataset-entry-20261016/scope.json)区分手动引用和已验证来源。

已实现：新建自定义数据集（名称、标识、版本、说明），向选定数据集录入 JSON 对象输入/实际结果、可选期望结果和 Agent/Trace/Span 标识。评估器版本来自当前目录，禁用评估器不能选择；EXACT_MATCH 要求期望 JSON。校验必填、字符串长度、UTF-8 64KB、JSON 对象格式、Agent 正安全整数及 Span 必须同时带 Trace。数据集强制 MANUAL/初始条数 0，保存不触发模型、评估或人工标注。引用字段在表格标为“手填来源引用（未校验归属）”；现有后端只检查 Agent 存在，不验证 Trace/Span 与样本的归属，未新增来源证据关系。正文仍沿用 OFF/HASH_ONLY/REDACTED 保存策略，哈希或缺失正文不能用于规则运行。

所有写入按 `/me` 的 viewConsole/manageEvaluations 门控。提交使用冻结的目标、原键和内容，同页重复点击只发一次；收到明确参数/授权拒绝时保留可编辑草稿，传输/服务器故障或不匹配成功回执进入未确认状态。此时不自动 POST，原内容保留，父页面刷新、数据集切换及其它写入被锁定，页签切换不卸载录入组件，离开路由被守卫阻止。提供只读同标识查询：发现记录只说明存在，不证明与本次内容一致；样本 GET 最大 500 条且没有精确键接口，未命中明确保持未知。用户勾选已核对后显式结束，才能重新录入。身份/相关能力失效会清空旧草稿及核对状态，晚到响应不能显示旧结果或解锁新提交。没有服务端幂等或跨刷新恢复承诺。

经已授权 n1.sh 上传独立归档，核对 SHA256 后在新隔离 Pod 运行 Node v22.23.3：**前端 389/389（新增 20 项），失败/取消/跳过 0；vue-tsc 与 Vite 生产构建通过**，源码核对、npm ci/test/build 原退出码全部 0，Pod Succeeded/容器 exit0/result complete0 一致。新增测试使用真实 Vue 响应式与完整组件脚本，覆盖校验、当前评估器版本、权限、并发、未知提交、有限查询、身份变化和父页面竞态。依赖 Rollup 的 PURE 注释警告保留在原构建日志；不是构建失败。本机没有安装依赖或运行 Node/npm/mvn/java/docker。

浏览器使用同一构建、真实 Vue/Element Plus/Chromium 及合成 API。首轮自定义数据集、带引用样本保存、390px 校验三组通过，第四组因定位器把侧栏菜单项当成 link 而超时；无页面异常或意外请求。修订为已有工作台路由链接，在新 r2 Pod 将七组完整重验，全部通过：上述三组、丢失回执后找到同标识记录并显式结束、列表未命中保留未知、保存中身份失效、仅标注权限无录入入口。应用源码及 dist 未改变，首轮失败报告/截图保留。浏览器是合成身份与保存响应，不是实际业务数据库写入、后端事务或 Keycloak 登录验证。

抽看未命中核对页和手机截图。前者的连接错误提示来自主动模拟丢失回执；手机功能断言通过，但 full-page 截图含滚动/焦点状态（固定顶栏位置和跳转链接），该截图不作为移动端视觉通过证明，本轮未做全站视觉审计。

[最终回执](evidence/dataset-entry-20261016/validation.json)、[完整工程结果](evidence/dataset-entry-20261016/unit-build.json)、[浏览器报告与截图](evidence/dataset-entry-20261016/browser-summary.json)、[清理回执](evidence/dataset-entry-20261016/cleanup.json)已归档。三份远程证据包按长度、SHA256、逐文件 manifest 核验，浏览器两次 dist 与工程构建一致。三个临时 Pod 按冻结 UID 清理并确认剩余 0，远程输出保留。测试前、清理前原 151 份规范前端源及固定辅助 Dockerfile 摘要匹配，T8-B 两固定应用镜像保持。

未部署、未推进 `/root/eap-src`、未 commit/push，实际模型调用和业务 API 写入均 0；后端、数据库和真实登录未重验。旧文件保护检查仅两份既有前端文件与两份交接文档变化，旧记忆完整前缀及 AGENTS、研究、后端、既有证据保持。主线仍未完成；后续应补精确记录查询、服务端来源归属/冻结契约，再推进来源→样本及采用/发布持久绑定，不能将手填 Trace 当成已验证来源。原 28 调用/9728 输出 Token/工具 0 的真实计划仍待单独授权。本轮命名测试/浏览器/归档/清理入口关闭，新代码与发布另建当时基线，不重放旧入口。


### Codex 进展：原提交标识精确查询完成远程验证（2026-10-16）

用户继续完善代码。本轮补齐上一轮回读受前 500 条样本限制的问题，新增 GET /api/evaluations/datasets/lookup 与 GET /api/evaluations/dataset-items/lookup。参数通过 query string 传递，支持斜杠、问号、井号与中文；数据集键 trim/ROOT uppercase，样本键 trim 并保留大小写，长度校验在查询前完成。查询样本先定位数据集，再按 dataset_id/item_key 读取既有唯一键，不扫描列表。后端只返回 kind/state/datasetKey/itemKey/recordId/createdAt，不返回正文、标签、说明或来源内容。数据库近似匹配结果再次做 Java 字符串精确核对，不同写法返回 KEY_MISMATCH 且不返回记录 ID/时间。两条 GET 均继承既有 PlatformPermission.VIEW_CONSOLE，无新增或分散权限规则，没有 SQL/索引/迁移变化。

前端保留冻结的原目标和原键，改用精确接口；校验回执 kind、原数据集/样本键及记录 ID，拒绝错上下文、矛盾状态或异常回执。FOUND 只说明原标识记录存在，不证明内容一致或本次请求成功；NOT_FOUND 明确仍可能处理中；KEY_MISMATCH、请求失败及旧后端 404 均保持未确认。没有自动重发、列表回退、自动解锁、服务端幂等、内容指纹或跨刷新恢复。既有人工核对完成、路由/并发保护与身份失效清理继续保留。仅本轮 9 份应用/测试文件见 [changes.patch](evidence/dataset-lookup-20261016/changes.patch)，接口语义见 [scope.json](evidence/dataset-lookup-20261016/scope.json)。

经授权 n1.sh，归档校验后在新隔离 Pod 执行：**后端定向 39/39（3 类）、完整 1045/1045（116 类）、Maven 打包通过；前端 393/393、vue-tsc 类型检查和 Vite 生产构建通过；11 组真实 Chromium 场景通过。** 后端新增 13 项覆盖 501 条样本、跨数据集同键隔离、特殊标识、只返回元数据、权限/无认证拒绝、非法参数、近似键防护和查询不增写入/审计；前端新增 4 项并更新已有未知提交用例，覆盖回执语义和真实 API URL 编码。浏览器验证同一 dist，覆盖原录入流程、390px、501 条之外原键、未命中、权限失效、数据集精确查询、近似匹配、错上下文及旧接口不可用；全部无页面异常或意外请求。没有本机安装依赖或运行 Node/npm/mvn/java/docker。

验证边界：后端是隔离 H2/Flyway、MockMvc 和模拟 JWT，近似排序分支由 mock 仓库覆盖；未运行本轮真实 MySQL 排序规则或 Keycloak 登录验收。浏览器 API、身份和保存均为合成，不能视为真实持久化业务验收。抽看手机和标识不匹配截图；连接错误 toast 来自主动模拟丢失保存回执，不代表连接线上后端；没有做全站视觉审计。前端 161 份候选，后端测试输入为 630 份 src 加 pom.xml，源码挂载只读、工作目录独立、无应用 Secret 或 ServiceAccount token。三个 Pod 均 Succeeded/容器 exit0，阶段回执一致。完整日志、报告与截图保留，[测试构建](evidence/dataset-lookup-20261016/test-build.json)、[浏览器](evidence/dataset-lookup-20261016/browser-summary.json)、[最终回执](evidence/dataset-lookup-20261016/validation.json)已归档；7,042,372 字节证据包及 310 份文件摘要匹配，浏览器 dist 与构建逐文件一致。

三个临时 Pod 已按冻结 UID 删除，远程输出保留。清理脚本在删除均成功后将“全不存在”时的空 stdout 误解析为 JSON，单独只读检查已确认剩余 0 并补齐 [cleanup.json](evidence/dataset-lookup-20261016/cleanup.json)，未重放删除。测试前与清理前，规范后端 626 份 src 加 pom.xml、规范前端 151 文件加固定辅助 Dockerfile 均与基线相符，T8-B 两固定应用镜像保持。初始只读 Deployment 检查误用了 eap-backend/eap-frontend 名称，实际名称 backend/frontend 核对后正常继续，没有应用变更。

未部署、未推进规范源、未 commit/push，真实模型和业务 API 写入 0。旧记忆完整前缀、AGENTS、研究与既有证据保持。主线仍未全部完成；下一小段为服务端来源归属与冻结契约，再考虑来源→样本、采用→发布的持久绑定，不能将手填标识当作已验证来源。发布本候选时需同时验证新查询后端与前端，不能只推进前端。原 28 调用/9728 输出 Token/工具 0 真实计划仍待单独授权。CODEX_NEXT_TASK 顶部已更新；本轮命名验证/归档/清理入口关闭，不重放，新代码或发布另建当时基线。


### Codex 进展：评估与改进候选已部署并通过真实登录只读验收（2026-10-16）

用户明确“部署啊”，本轮获得部署授权，发布前几轮已验证的完整候选。没有修改业务代码，没有 git commit/push；没有调用付费模型、执行业务工具或提交业务 API 写操作。所有镜像构建、部署和真实浏览器都在授权 PoC 集群经 n1.sh 进行，本机仅文本编辑、摘要/归档/AST 检查。复用上一轮后端 1045/1045、前端 393/393、类型/生产构建及 11 组合成场景通过的精确 JAR/dist，没有重复这些测试或重新生成前端 buildId。

发布前核对规范源、旧 T8-B 两固定镜像、应用就绪与健康；冻结应用 UID/除 image 外 spec 摘要、eap/eap-auth 其他 Deployment/StatefulSet/Service/ConfigMap 内容摘要以及 Secret 的 UID/resourceVersion，不记录 Secret 值。r1 准备遇到 metadata jsonpath 字符串换行转义错误；r2 遇到只声明 Docker 媒体类型而当前镜像实际是 OCI manifest 的 404，已用只读请求证明增加 OCI Accept 后返回 200。两次均未构建镜像或切换应用，r2 的构建入口因缺 prepared.json 被守卫拒绝；原失败目录与脚本保留。修正后在新 /root/eap-evaluation-release-20261016-r3 完成，不重跑旧业务验收入口。

使用固定 digest Kaniko 将原测试产物打包为新镜像。后端继承旧运行镜像只替换 JAR；原基础镜像 43 层、压缩约 3.20 GB，解包约四分钟，是本轮构建等待的主要原因，不是服务中断。前端在新镜像构建文件系统内清除旧静态目录再复制同一 dist，线上原文件不直接删除。核对 registry manifest/config/layer 摘要、JAR SHA256 与 67 份 dist 文件一致，运行时 config 不变。随后用 deployment UID/resourceVersion/旧 image 的 JSON Patch 前置断言，先后端健康通过再前端，只有 image 字段发生变化。

**当前 backend：10.186.133.66:5001/eap-backend@sha256:16fdf1491d323edac0111084bb64510ec80fd200babe4006f0812d7e203024a0；frontend：10.186.133.66:5001/eap-frontend@sha256:42fae7b252ac4b862fd17b88e8c6a7eb33d72169ac3c96ad121d712c48264e2e。** 前端 buildId 仍为原测试产物的 2026-10-16T04:51:26.823Z。两 Deployment 均 1/1，就绪 Pod 实际 imageID 匹配，健康 UP、重启次数 0；index.html/version.json 与原产物逐字节一致。旧 T8-B 镜像保留作为回退材料，未执行回退。当前本机 5173 由原 SSH 隧道监听，隧道按稳定 ClusterIP 转发且带重试，不是绑定旧 Pod 的 port-forward，未另启或替换隧道。

**新增真实验收四角色全部通过：viewer、operator、governance、admin。** 从线上页面跳转 Keycloak，完成 PKCE S256、真实回调、刷新保留会话、退出清空会话；访问 /evaluation 和 /learning。数据录入入口严格跟随 /me 的 manageEvaluations，viewer/governance 无编辑器，operator/admin 可打开后取消。四角色均查到现有数据集和样本 FOUND，缺失及含 /?#中文的原键返回 NOT_FOUND，六字段元数据契约与记录 ID/时间匹配。这次是真实 backend/MySQL 读取，不再只引用合成接口验证。桌面与 390px 页面、8 张截图保留；没有 pageerror、API 5xx、外部 origin 或业务 API 写入尝试。登录密码仅从现有 Secret 在集群内经 stdin 送入进程，不打印或导出凭证、token、HAR、storage/profile。浏览器退出后清理临时 Pod。

真实写入/规则运行、MySQL 近似排序的全部边界和全站视觉不在本轮验收内。抽看 admin 手机及 viewer 桌面截图；手机校准按钮文字显示/对比度仍需专项核查，因此不宣称视觉完全通过。已验证的功能也不提供服务端幂等、跨刷新恢复、来源归属持久关系、采用/发布绑定或运行生效证明。页面只读检查没有花费原 28 调用/9728 输出 Token/工具 0 的真实计划预算，该计划仍须单独授权。

四角色验收通过后，先备份原差异文件再将 27 份已验证差异源码推进 /root/eap-src，核对规范后端 631 文件（630 src + pom.xml）、前端 161 文件和原辅助 Dockerfile 摘要。外部 K8Substrate/tools/eap-poc.yaml 原两个镜像引用已落后于真实 T8-B，现仅更新为本轮两个已运行摘要，其余字节保持；没有 apply 整份清单。应用除镜像外的 spec、认证、数据库和其他受保护配置摘要/身份保持，不将此声明扩展为所有业务表历史状态核对。

[发布结果](evidence/evaluation-release-20261016/validation.json)、[运行证明](evidence/evaluation-release-20261016/deployed.json)、[真实浏览器](evidence/evaluation-release-20261016/browser-summary.json)、[源码推进](evidence/evaluation-release-20261016/remote-r3/release/source-promotion-proof.json)、[清理回执](evidence/evaluation-release-20261016/cleanup.json)已归档。910,819 字节证据包 SHA256 2e094fd6b2b3b434f878181b84453cd8387a2462c298b39388c73bec78ec220e，55 份证据逐文件核验通过。两个镜像构建 Pod、一个真实浏览器 Pod 按冻结 UID 清理并确认剩余 0，应用 Pod 保持运行，原产物、证据和备份保留。远程 completion.json closed=true/deployed=true/sourcePromoted=true；本轮命名发布、浏览器、归档、推进和清理入口关闭，不重放。旧记忆完整前缀、AGENTS、业务源码、研究与既有证据保持；下一步仍是来源归属与冻结契约，主线并未全部完成。CODEX_NEXT_TASK 顶部已改为“已部署”，此前“未发布”段落仅作为历史保留。


### Codex 进展：回归样本观测引用快照与按钮对比度修复已验证（2026-10-16）

用户“继续完善”。本轮在已部署候选基础上完成 16 份应用/测试文件增量，新增 [观测引用契约](EVALUATION_SOURCE_REFERENCE.md)。添加回归样本可选择“保存时核对观测引用”；服务端要求 Agent/Trace/Span 同时存在，与同一条观测行做精确字符串和 Agent ID 比较。不存在、无 Agent 归属、不一致及数据库近似标识匹配均拒绝，不写样本或增加条数。未勾选与旧客户端仍为手填引用。

V84 新增四个可空快照列：观测行 ID、endpoint key、观测时间和核对时间。历史记录不推断、不回填已核对状态。快照由服务端在创建时保存，实体映射不可更新，无到可清理观测表的外键；人工标注、观测更新或删除后保持原快照。响应 `sourceReference.state` 区分 MANUAL_UNVERIFIED/OBSERVATION_MATCHED。这只证明保存时记录元数据一致，不证明上报者认证身份或样本正文来源；正文继续手填，沿用 OFF/HASH_ONLY/REDACTED，没有复制观测内容。权限继续只定义在 PlatformPermission。

前端勾选时检查三个引用必填，冻结核对选项和原请求；保存回执须与原引用一致且包含有效快照，旧后端忽略新字段或回执异常时保留未确认状态，不自动重发。列表明确显示“来源引用（正文手填）”与保存时快照。修复全局实心 primary 样式覆盖 plain/text/link/disabled 按钮的问题，并以 :where() 保持原选择器优先级；plain 正常文本使用较深主题色。

**全部编译、测试、构建及 Chromium 都在授权 PoC 经 n1.sh 执行：后端定向 37/37，完整 1063/1063（118 类）及 Maven 打包通过；最终前端 397/397、vue-tsc/Vite 构建通过；最终 16 组浏览器场景通过。** 后端新增 18 项、前端新增 4 项。覆盖 V83→V84 历史数据保持、引用不完整/不存在/无归属/跨 Agent/近似标识拒绝、事务不写、旧客户端、四角色权限、源变更/删除和标注后快照不变，以及旧服务端回执与未确认提交保护。前端最终 buildId 为 2026-10-16T06:41:25.655Z，67 份 dist 文件与浏览器一致；最终后端输入 634 文件、前端 161 文件。

首轮后端 37 项中 1 项发现 Instant 纳秒与 timestamp(6) 微秒回读不一致，改为微秒后以新 r2 完整验证，原失败保留。browser-r1 前 11 组通过后定位到 Element Plus 隐藏原生 checkbox 而超时；改点可见标签并断言勾选状态，r2 余下 5 组通过。之后复查 CSS 优先级，新增 frontend-r3 构建和 browser-r3 全 16 组验收；原轮次不覆盖。桌面和 390px 正常文本对比度 5.848、悬停/组合焦点 4.654，深色页头实际 CSS DOM 夹具保持原背景；已目视核看最终手机按钮和桌面来源快照截图。不是全站视觉、独立键盘可达性或线上认证验收。

[最终回执](evidence/source-reference-20261016/validation.json)、[测试构建](evidence/source-reference-20261016/test-build.json)、[浏览器](evidence/source-reference-20261016/browser-summary.json)、[增量](evidence/source-reference-20261016/changes.patch)、[清理](evidence/source-reference-20261016/cleanup.json)。归档 16,195,804 字节，SHA256 d338eb2c0bbbc57fe10487d6a01907ed616d35fa7577c6089fb904db09740228，420 份文件摘要及 33 张截图已核验，JWT/PEM 模式扫描无命中；验证 Pod 未挂生产 Secret 或 ServiceAccount token。7 个临时 Pod 按冻结 UID 清理为 0，远程输出保留，命名入口关闭不重放。根目录 /root/eap-source-reference-20261016-r1、最终后端 r2、最终前端 r3 和 browser-r1/r2/r3 详见证据 README。

**本轮未部署、未推进 /root/eap-src、未 commit/push；真实模型/业务工具调用 0。** 清理前确认规范后端 631 文件、前端 161 文件加固定辅助 Dockerfile 保持；两应用 Deployment UID/spec 和镜像与上一轮发布一致且均 1/1 就绪。现网 backend 仍为 16fdf149…、frontend 仍为 42fae7b2…，本轮 V84 未上线。本机仅文本编辑、摘要/归档/AST 检查与截图查看。AGENTS 及未改动的捕获源码校验保持，旧记忆完整前缀保留。

验证边界：隔离 H2/Flyway、MockMvc JWT、合成浏览器 API/身份；没有真实 MySQL 迁移/样本写入、Keycloak 重验或线上按钮修复验收。新候选发布须按届时现网基线成对验证 V84 后端和前端，不重用旧发布入口。主线还需可信来源正文指纹/内容绑定、服务端幂等和跨刷新恢复、采用→发布关联；不能把本轮观测引用快照视为这些已完成。原 28 调用/9728 输出 Token/工具 0 真实计划仍待单独授权。


### Codex 进展：综合测试补齐真实 MySQL、Keycloak、Runtime 与全站冒烟（2026-10-16）

用户要求“全面测试一下”。本轮只做测试与证据整理，没有修改业务代码、部署、推进规范源或 commit/push。全部编译/测试/浏览器在远程 PoC 经 n1.sh 进行。候选源码后端 634、前端 161、Runtime 38 文件摘要保持；复用同一候选此前后端 1063/1063（118 类）、前端 397/397、打包/类型/构建通过的结果，未冒充本轮重跑。JAR SHA256 6bbf4be1c90cbd5020fec42e3a0050afc13d87de31211e24a363dded559bfacf、前端 67 个 dist 文件逐一核对，buildId 2026-10-16T06:41:25.655Z 不变。

新执行最终通过：**真实 MySQL 352/352（10 类，UTC 248 项与 +08 会话 104 项）；Go race 66 个顶层/含子用例 102 个及三个 Runtime 构建；Python 优化器 43/43、工具守卫 5/5；现网与隔离候选各四角色真实 Keycloak 登录及各 21 路由桌面/390px 冒烟。** MySQL 采用与现网相同固定镜像，8.4.11/utf8mb4_0900_ai_ci；完整 84 个迁移含 V75 Java 迁移，验证 V83→V84、旧正文保留/新字段不回填、正常应用启动。浏览器真实操作保存数据集/样本，后续 API 标注、ACTION_COMPLETION 本地规则校准/历史读取通过；删除原观测后来源快照保持。真实排序规则下大小写/重音别名被拒绝，精确查询不泄露近似记录元数据，拒绝写入不增条数。候选 viewer/governance 写入 403、operator/admin 200，UI 使用 /me 能力。两环境健康 UP，匿名/无效令牌 401。

**发现仍在线上的 P2：评估页“运行数据集校准”按钮文字对比度异常、手机截图几乎看不见。** 修复已在上一轮候选中，本轮截图确认候选桌面按钮可读，完整对比度值仍引用上一轮验证；本轮没有发布，因此不能宣称线上已修复。候选 V84/来源快照同样尚未上线。现网仍 backend 16fdf149…、frontend 42fae7b2…、buildId 2026-10-16T04:51:26.823Z。页面冒烟不等于每个模块的 CRUD、穷尽视觉/无障碍验收。

首次失败完整保留：r1 探针只加载 SQL 漏 V75 Java 迁移，隔离应用启动失败、候选登录 502；通用 UTC 参数使时区专用类 104 项前置条件失败。Demo 初轮缺历史二进制，补齐时 kubectl stdin 截断文件；改只读挂载并容器内再次核对摘要后兼容 race 8/8。r2 准备误计隔离 MySQL 容器后修正，Pod 又被节点 CPU 预留不足拒绝；清理已结束资源后 r3 正常。r3 样本浏览器操作未完成，后续依赖断言失败；r4 诊断包装有路径/语法错误；最终 r5 远程语法校验后以可见 Element Plus select 点击，定向 7/7 通过。没有把这些失败改写为首次全绿，也没有为修测试脚本修改产品代码。Flyway 对 MySQL 8.4 超出其声明测试上限 8.1 的提示仍在，实际迁移/测试通过。

[完整报告](evidence/comprehensive-20261016/README.md)、[最终结果](evidence/comprehensive-20261016/validation.json)、[MySQL 明细](evidence/comprehensive-20261016/mysql-summary.json)、[最终现网](evidence/comprehensive-20261016/post-check.json)。归档 2,762,437 字节、SHA256 6abc5667b3edfbe0a17946f0ca8010da710ce4ff16812f3bc1733ac0927c097d，117 份文件/28 张截图摘要核对通过；三张截图人工抽查。测试账号密码仅经内存/stdin，未导出 token、HAR、storage；导出前实际测试口令及 JWT/私钥模式扫描无命中。五个临时 Pod 按冻结 UID 清理为 0，原始证据保留，命名入口关闭不重放。现网前后端 1/1、重启 0、UID/spec/image 未变，规范源保持。

真实模型/业务工具调用 0，现网业务 API 写入 0；所有样本写入在独立合成数据库。未做真实任务/付费模型全链路、压测/长稳、故障注入、备份恢复或生产升级演练。原 28 调用/9728 输出 Token/工具 0 计划仍待单独授权。主线还需正文可信来源绑定、服务端幂等/跨刷新恢复及采用→发布。下一步若发布候选需基于届时现网新建发布操作，不重用旧发布/测试入口。


### Codex 进展：观测引用快照与按钮修复已发布并验收（2026-10-16）

用户在综合测试之后要求“继续”，沿用此前“部署啊”的授权，将已验证候选发布到远程 PoC。本轮没有修改业务代码、重跑全量测试或 commit/push。原 JAR SHA256 6bbf4be1c90cbd5020fec42e3a0050afc13d87de31211e24a363dded559bfacf 和 67 份前端 dist 按摘要封装；源码后端 634、前端 161 文件与前轮测试输入一致，后端 1063、前端 397、MySQL 352、Go 66 顶层和 Python 48 的结果按证据复用，不称本轮重新执行。

**当前上线 backend b2a8a4e00e0c1c65cdc1ded45741bc48bd0e01e492de7378f460f9f594d6dc9a、frontend 2c69c5092e561d2dd77b0c7fffc677f1db90ffd0a21f21d06e4f2986f23f646c；buildId 2026-10-16T06:41:25.655Z。** 镜像前缀仍 10.186.133.66:5001/eap-backend 或 eap-frontend，使用 @sha256 固定摘要。构建及验证均在远程，经 n1.sh；registry/config/层内精确产物核验通过，运行配置未变，只 patch 两应用镜像字段。先后端健康后再前端；真实 MySQL 8.4.11 从 V83 到 V84，迁移 84、失败 0、四快照列存在，两条历史样本仍全部未核对，未推断回填。

**四角色真实 Keycloak PKCE/回调/刷新/退出及桌面/390px 验收通过。** /me 能力与录入按钮一致；线上来源复选框与新 sourceReference API 字段正常，既有数据集/样本精确查询与特殊缺失原键契约通过。operator/admin 在两尺寸的校准按钮对比度正常 5.848、悬停 4.654、独立 focus 5.848，原线上 P2 文字不可读问题已修复；手机按钮与桌面来源编辑器截图人工核看。程序 focus 不是键盘无障碍审计。本轮页面异常、应用 API 5xx、意外源、应用 API 写请求、真实模型/业务工具调用均 0。实际样本保存和校准执行引用上一轮隔离真实 MySQL 结果，没有现网业务写入。

首次浏览器 r1 提前启动，等待发布超过 2400 秒，DeadlineExceeded，尚未执行验收。新 r2 首次前置检查发现依赖还在安装、ready 未出现，未创建运行意图；等待就绪后原脚本四角色验收成功。失败/准备记录原样保留。远程规范源 16 份差异推进前备份，后端 634、前端 161 及辅助 Dockerfile 校验通过；外部 K8Substrate/tools/eap-poc.yaml 仅改两处镜像引用，未应用完整清单。其他 Deployment/StatefulSet/Service/ConfigMap 摘要及 Secret 身份/版本保持。

[完整发布报告](evidence/source-reference-release-20261016/README.md)、[最终回执](evidence/source-reference-release-20261016/validation.json)、[上线浏览器](evidence/source-reference-release-20261016/remote/browser/out/report.json)、[清理后状态](evidence/source-reference-release-20261016/post-check.json)。1,229,747 字节归档 SHA256 47c6adf498a435b508ca7930d7fa7113c9bc44de8ea364782599aced569598a8，72 份文件/14 张截图核验；导出前实际测试密码及 JWT/私钥模式扫描无命中。测试密码仅在远程内存/stdin，未导出 token/HAR/storage。四个临时 Pod 按冻结 UID 清理为 0，产物、旧镜像、备份与证据保留。发布根 /root/eap-source-reference-release-20261016-r1 及浏览器 /root/eap-source-ref-live-20261016-r1/r2 已关闭，不重放旧入口。清理后两应用仍 1/1、重启 0、健康 UP，匿名/无效 Bearer 401，原 http://localhost:5173 入口保持。

本轮未重跑全站 21 路由或做现网样本写入、负载/长稳、故障/备份恢复、真实模型、穷尽视觉与无障碍。快照不证明正文来源或上报者可信身份；可信正文绑定、服务端幂等与跨刷新恢复、采用→发布仍需继续。原 28 调用/9728 输出 Token/工具 0 的真实验收计划仍待单独授权。CODEX_NEXT_TASK 已前置当前发布状态，之前未发布段落保留为历史。


### Codex 进展：企业工作区设计细化已上线（2026-10-16）

用户要求“继续完善，前端可以参照一些经典设计或者当前已有系统的风格，设计一下”。本轮沿用既有 AdminLTE 深色导航、浅内容、青色强调，参考 Carbon 数据表层级与 Cloudscape 资源视图；没有收到另行风格选择，按现有设计约定推进，并非用户另选了外部品牌。更新 [控制台视觉约定](CONSOLE_VISUAL_DESIGN.md)，保留原文，补充资料链接、访问日期、色彩/字号/间距与具体实现规则。

四份前端代码变更：console.css 统一中性画布、标题、面板、表格与平面按钮，并保留工作台深蓝头部和大屏独立风格；EvaluationView 增加页签用途说明，桌面连续指标栏/手机双列页签，数据集选择前置，原生 button 的 aria-pressed、禁用和 Enter/Space 操作，普通表格取消误导的整行点击光标；EvaluationDatasetEditor 收起关闭态空白内容，打开时分样本内容/来源引用并保留契约提示，仍持续挂载；LearningView 标题使用相同层级。未改业务处理函数、后端/API/权限合同或依赖，前端仍只消费 /me 能力，不解析 JWT/角色。无外部字体、虚构指标或装饰数据。

**最终 r2：前端 397/397、类型/生产构建、16 组隔离 Chromium 交互场景、四角色真实 Keycloak 和 21 路由桌面/390px 巡检通过；上线后四角色真实登录与评估界面再次通过。** 布局断言检查手机页签实际两列、桌面指标栏无间隙；数据集 Enter/Space、原生按钮状态通过。校准按钮正常/悬停/焦点对比度最低 4.654；选中来源复选框等待过渡后验证青色填充与白色勾选。最终四张候选截图及一张上线来源表单人工复核；不是 100 张全量人工审查，也不是全站无障碍验收。

r1 的 397 前端项与 16 隔离场景通过，但截图发现局部 CSS 作用域规则覆盖主题，手机页签四行、指标栏间隙未消失；真实浏览器总结果因把 /api/agent-discovery/search 的只读 POST 拦截为写操作而失败。确认 AgentRegistryService.discover 仅只读仓库查询后，r2 测试只精确放行该接口，其余业务写请求仍禁止；提高主题选择器限定并增加实际布局断言，以新目录/Pod 重验。首轮记录保留，不称首次全绿。真实浏览器业务写入、真实模型/工具调用均 0；允许的搜索 POST 是只读行为，未改后台权限或 API。

**当前前端已部署 10.186.133.66:5001/eap-frontend@sha256:b5212a8fba420eeeff844a6a09f6c1dee82856105b71c3a95391a439d1f649ae，buildId 2026-10-16T12:38:53.132Z。后端仍 b2a8a4e00e0c1c65cdc1ded45741bc48bd0e01e492de7378f460f9f594d6dc9a。** 沿用已有部署授权，仅 patch 前端镜像字段，manifest/config/镜像层内测试 dist 精确核验；未重新生成构建时间，nginx 配置保持。现网前后端 1/1、健康 UP、重启 0，匿名/无效令牌 401，后端 Deployment 和其他配置保护通过。后端源码 634 份摘要保持，前端最终 161 份输入匹配；四份源文件备份后推进规范源，外部部署清单仅同步一个前端镜像引用。

[完整设计与发布报告](evidence/console-design-20261016/README.md)、[最终回执](evidence/console-design-20261016/validation.json)、[上线浏览器](evidence/console-design-20261016/remote/r2/browser-out/deployed/report.json)、[清理后状态](evidence/console-design-20261016/post-check.json)。20,853,128 字节归档 SHA256 f9ecb5e2071cf6006a07c6ad82e61ce8436103b17c24a2928193ced0f0ed16a5，197 份证据和 100 张截图摘要核验；导出前实际口令及 JWT/私钥模式扫描无命中，不导出 token/HAR/storage。五个临时 Pod 按冻结 UID 清理为 0，远程 /root/eap-console-design-20261016-r1/r2 已 closed，产物与源码备份保留。命名入口不重放，新验证/发布另建当时基线。没有 commit/push；AGENTS 和旧记忆保持。

本轮为共享主题与核心评估流程的第一版细化，没有逐页重做全部业务模块。21 路由是布局/导航冒烟，不覆盖完整 CRUD；未做穷尽视觉/无障碍、字号/设备全组合、压测/性能基准和真实模型任务，未重跑后端/数据库/Runtime 测试。主线仍有可信正文来源绑定、服务端幂等和跨刷新恢复、采用→发布；原 28 调用/9728 输出 Token/工具 0 真实验收仍待单独授权。


### Codex 进展：智能体目录与详情设计第二批已上线（2026-10-17）

用户继续前端设计迭代。本轮仅三份源码：AgentsView.vue 紧凑标题、手机两列概览、持久筛选标签/可折叠条件与专用手机资源列表；AgentDetailView.vue 身份线索/接入进度可展开，setup=1 保持展开，详情视图原生按钮/aria-pressed 与原深链接保持；console.css 集中样式并修复浅底指标栏继承白字、缩小图标。两个视图 script 块逐字不变，API、依赖和 /me 权限保持。没有 commit/push。

**最终 r2：397/397、类型/生产构建、16 组隔离交互、四角色目录详情专项与 21 路由桌面/390px 冒烟通过；上线后四角色再次通过。** 实际测试搜索/空结果/重置/风险筛选/折叠提示/分页/键盘进详情/展开收起/九个视图入口/原深链接；指标标签最低对比度 5.729:1。页面异常、API 5xx、真实业务写入、真实模型调用均 0。21 路由不是完整 CRUD 或无障碍验收。

r1 前两次专项脚本失败：Element Plus 占位文字覆盖内部输入框，第三次改为点击实际选择框外层后通过；证据保留，无强制点击或为测试改变业务。r1 原全套检查通过后，人工截图发现旧指标白字残留，补三条 scoped CSS 并新建 r2 重验。r2 首次浏览器前置检查因构建回执未出现而停止，未创建浏览器资源；构建通过后启动。没有称首次全绿。

**当前前端 10.186.133.66:5001/eap-frontend@sha256:6b048fea467b833c2aae44e0d4038748f6ec831b55ffa3d113fccd30e9dcbf99，buildId 2026-10-17T01:10:52.361Z；后端仍 b2a8a4e00e0c1c65cdc1ded45741bc48bd0e01e492de7378f460f9f594d6dc9a。** 只 patch 前端镜像；镜像内测试 dist 摘要一致，规范源三份备份后推进，前端 161/后端 634 文件核对，其他配置与 Secret 身份/版本保持。应用 1/1、健康 UP、重启 0，匿名/无效 Bearer 401；外部清单只改一个前端摘要。入口 http://localhost:5173/agents。

[设计发布报告](evidence/agent-workspace-design-20261017/README.md)、[最终回执](evidence/agent-workspace-design-20261017/validation.json)、[清理后状态](evidence/agent-workspace-design-20261017/post-check.json)。归档 29307957 字节/SHA256 ea5e226418025ca1424dc204f0744b9c615ebec63bb9a53abcdffb022d997d87，293 份证据/178 张截图摘要核验，测试口令及 JWT/私钥扫描无命中；不导出 token/HAR/storage。五个临时 Pod 按 UID 清理，/root/eap-agent-workspace-20261017-r1/r2 已关闭，旧入口不重放，产物与备份保留。

未跑后端/数据库/Runtime、压测/性能/长稳、全部 CRUD 或穷尽视觉/无障碍；没有真实模型/工具任务。主线的可信正文来源、幂等/跨刷新恢复、采用→发布未完成；原 28 调用/9728 输出 Token/工具 0 真实验收仍待单独授权。


### Codex 进展：治理工作区设计第三批已上线（2026-10-17）

本轮治理工作区已部署到远程 PoC，前端 `10.186.133.66:5001/eap-frontend@sha256:3311fe6a905fca6c014592addb90d2985a2e4574f5df37e85f143fd5415b3a9a`，buildId `2026-10-17T01:46:08.380Z`；后端保持 `b2a8a4e00e0c1c65cdc1ded45741bc48bd0e01e492de7378f460f9f594d6dc9a`。前后端 1/1、UP、重启 0，匿名/无效 Bearer 401。入口 http://localhost:5173/governance；本机隧道未另行测试。

三个源码文件：GovernanceView.vue 的原生四分区按钮、持续状态栏、前置事件指标与可展开执行/接入说明，预检表单 DOM 前置；GovernancePolicies.vue 收起规则 JSON；console.css 统一手机双列、层级和浅底深字。两个 Vue 文件的 script 块逐字保持，表单切换状态、深链接与 /me 能力控制保持。实际输入以归档和逐文件 manifest/source-check 绑定，Pod 提示性注解仍沿用旧模板，详见 input-binding-note.json。

**远程 397/397、类型/生产构建、16 隔离交互、治理四角色专项及 21 路由桌面/390px 冒烟通过；上线四角色再次通过。** 专项实测分区 Enter/Space、展开/收起、未提交表单内容保持、时间筛选、策略编辑器打开/取消、版本历史、policyId/eventKey 深链接和清除筛选；指标标签最低对比度 5.729:1。没有提交预检/策略/审批，没有真实模型或业务工具调用。21 路由只是导航与布局冒烟。

浏览器 v1 在“事件标识”精确名称定位超时；上线可访问树确认实际名称为“*事件标识”，包含必填星号，真实标签与 input 的 for/id 关联存在。v2 改为同一字段名称的非精确匹配，viewer/operator 通过后又因立即检查关闭动画而失败。v3 等待弹窗隐藏后四角色通过。上线 v4 保持断言并等待打开动画稳定后截图，额外记录字段可访问名称；没有为验收脚本失败修改产品代码或重新构建。所有失败报告保留，不称首次全绿。

镜像层内 dist 与测试 manifest 精确一致，nginx 运行配置保持，只 patch 前端镜像字段。三份规范源备份后推进；前端 161、后端 634 文件核验，其他配置与 Secret 身份/版本保持。外部部署清单只同步一个前端引用，未 apply 全量清单。没有 commit/push。

归档 25382693 字节，SHA256 `ca32030eb6ca05898dc8007b6ae01107541820a9c2b885371e22a30f8f9d844a`；253 份证据 / 182 张 PNG 摘要核验。导出前实际测试密码及 JWT/私钥扫描通过；凭证只在远程内存/stdin，不导出 token/HAR/storage。三个临时 Pod 按 UID 清理，远程 `/root/eap-governance-workspace-20261017-r1` closed，旧入口不重放，产物和源码备份保留。

未重跑后端、数据库、Runtime；未做完整 CRUD、穷尽视觉/无障碍、性能/压测/长稳或真实模型任务。功能主线的可信正文来源、幂等/跨刷新恢复及采用→发布仍未完成；原 28 调用/9728 输出 Token/工具 0 真实验收仍待单独授权。

[设计与发布报告](evidence/governance-workspace-design-20261017/README.md)、[最终回执](evidence/governance-workspace-design-20261017/validation.json)、[清理后状态](evidence/governance-workspace-design-20261017/post-check.json)。


### Codex 进展：审计工作区设计第四批已上线（2026-10-17）

审计日志页已部署到远程 PoC。前端 `10.186.133.66:5001/eap-frontend@sha256:6a73f130ab4733fce7095cfa9e9ad31ebe593bf11f0c5577b024a0d82ca57235`，buildId `2026-10-17T02:28:41.023Z`；后端保持 `b2a8a4e00e0c1c65cdc1ded45741bc48bd0e01e492de7378f460f9f594d6dc9a`。前后端 1/1、UP、重启 0，匿名/无效 Bearer 401。入口 http://localhost:5173/audit；本机隧道未另行测试。

本轮三份源码：AuditView.vue 增加明确的详情按钮、同数据手机记录列表及自适应详情抽屉；HistoryTable.vue 提供可选 mobile 插槽，共用查询和分页状态；console.css 统一信息分区、长标识与 JSON 换行。两个 Vue script 块逐字保持，无新增依赖、API 或权限变更。缺失正文显示“未记录”，真实 `{}` 原样保留；未知结果中性显示，含 HTML 的审计字符串仍作为文本。

**最终 r2 远程 397/397、类型/生产构建、4 组审计隔离场景、既有 16 组交互回归、审计四角色专项和 21 路由桌面/390px 冒烟通过；上线后四角色再次通过。** 覆盖原记录与变更正文、键盘 Enter/Space、Escape/页脚/顶部关闭、焦点返回、搜索空结果、状态、排序、刷新、分页与登录刷新/退出。隔离场景额外覆盖未知结果、长正文/HTML 转义、加载、错误清空、迟到响应。关闭按钮使用实际坐标命中检查及普通点击，无强制点击。真实业务写入、真实模型/业务工具调用均 0。

r1 原自动检查通过，人工截图发现标题/关闭按钮被固定顶部导航遮挡；r2 沿用现有抽屉 `append-to-body` 与 z-index 4000 约定，重新构建并重验。r1 浏览器准备曾因构建回执先于 Pod Succeeded 而停止，未创建浏览器；等待原 Pod 成功后继续，构建未重复。两轮原始证据保留，不称首次全绿。r2 修复了准备阶段状态等待。r2 首轮真实角色命中检查失败，诊断记录滑入动画仍有约 11.9px 位移；v2 等待 transform 回到原位后保留同一命中断言与普通点击，复用相同 dist 重验。原失败及诊断截图保留。浏览器 Pod 提示注解沿用旧模板，实际绑定以归档 SHA256、源码/dist manifest 和 Pod UID 为准，见 input-binding-note.json。

精确封装测试 dist，核对镜像层与运行配置，只 patch 前端镜像；三份规范源备份后推进，前端 161、后端 634 文件核验，其他配置与 Secret 身份/版本保持。外部清单只同步一处前端镜像。没有 commit/push，AGENTS 和历史记忆保持。

归档 27078827 字节，SHA256 `aa1f210886be1f2cfe49fdc1a7a606ea124ff1318f7b7b6c2e248d29db9c8989`；290 份证据 / 163 张 PNG 逐文件摘要核验。导出前测试密码及 JWT/私钥扫描通过，凭证仅远程内存/stdin，不导出 token/HAR/storage。五个临时 Pod 按 UID 清理，远程 `/root/eap-audit-workspace-20261017-r1/r2` closed；旧命名入口不重放，产物与备份保留。

**独立未修复项：安全响应写权限。** 源码发现 `/api/safety/sessions`、`/api/safety/events/evaluate`、`/api/safety/artifacts` 写接口目前落入 authenticated 通用规则，SafetyView 未消费对应 `/me` 能力且带固定 Agent 3 演示数据；没有执行现网写请求验证。后续应在 PlatformPermission 集中定义规则，补服务端允许/拒绝测试，再处理前端能力与演示归属，不能只隐藏按钮。详见 DESIGN_NOTES.md。

未专项输入日期范围，未重跑后端/数据库/Runtime，未做完整 CRUD、性能/长稳或穷尽视觉/无障碍。21 路由仅导航与布局冒烟。功能主线的可信正文来源、幂等/跨刷新恢复、采用→发布仍待；原 28 调用/9728 输出 Token/工具 0 真实验收仍待单独授权。

[完整报告](evidence/audit-workspace-design-20261017/README.md)、[最终回执](evidence/audit-workspace-design-20261017/validation.json)、[清理后状态](evidence/audit-workspace-design-20261017/post-check.json)。


### Codex 进展：安全响应写权限修复并上线（2026-10-17）

安全响应权限修复已部署到远程 PoC，入口 http://localhost:5173/safety。前后端均 1/1、健康 UP、重启 0，匿名/无效 Bearer 401；本机隧道未重新验证。

- backend：`10.186.133.66:5001/eap-backend@sha256:f89bb9e69ea23245ca4f186f57848b79196779816ea6dab6aa424e3d9964b9d7`
- frontend：`10.186.133.66:5001/eap-frontend@sha256:da4bb1d9eaf22e5a4f743981c11dbaf379b3e52cf0c16535d0bce93be86e7f94`，buildId `2026-10-17T03:04:47.861Z`

新增 PlatformPermission.MANAGE_SAFETY / `manageSafety`，治理管理员、平台管理员可写；查看者、操作员、数据所有者、无角色不可写。SecurityConfig 对三个安全写接口明确匹配该能力，四类读取沿用 VIEW_CONSOLE，其他 `/api/safety` 路径/方法默认拒绝。/me 从相同枚举返回能力；前端只消费能力。运行时工具、工作流等直接调用共享安全服务的路径保持。

SafetyView 使用已登记会话及其 rootAgentId，取消固定 Agent 3、演示默认值和隐式创建/覆盖会话。未关联会话继续不关联。补上只读解释、加载错误、无数据、重复点击防护及身份/权限变化时的清理；迟到响应不能复活旧数据或解锁新请求。回执丢失后明确未确认，刷新不自动重发，需用户核对后手动允许再次评估；此状态不保证跨硬刷新恢复。文案改为实际执行的本地策略评估，不声称已委托 sidecar。

**远程权限/开发模式/运行时工具专项 54/54、后端全量 1074/1074、前端 409/409、类型与生产构建通过。** 后端 Spring 测试验证六角色 /me 与真实接口拦截、允许请求进入服务、拒绝请求不调用服务、匿名与未知路径/方法；身份由测试 JWT 替身提供。新前端 12 项行为测试和 5 组真实 Chromium 隔离场景通过，既有审计 4 组、评估 16 组回归通过。隔离浏览器只使用合成 API。

上线四角色真实 Keycloak PKCE/回调/刷新/退出、桌面/390px 页面和 21 路由冒烟通过。另发 12 个空对象请求：viewer/operator 三个写接口均 403，governance/admin 均通过鉴权后因必填缺失返回 400；没有有效业务请求体，前后安全会话、统计与最近决策保持。该检查不等同于现网成功写入验收。应用写入、真实模型/业务工具调用均 0。

r1 前端测试通过，但浏览器第三场景发现显式 disabled=false 覆盖 Element Plus 表单禁用，界面仍可点击；函数 guard 已阻止再次写入。r2 显式绑定 running/writeUncertain 后重新执行前端测试、构建与浏览器，原失败截图保留。r1 打包等待进程在创建镜像前取消。r2 既有评估回归在第四场景截图阶段触发 12 秒超时，原报告保留；v2 仅将截图等待调至 30 秒并关闭截图动画，交互断言不变，使用同一 dist 重验全部 16 场景。原 r2 打包因该失败报告停止，v2 才使用最终通过证据。后端源码逐文件相同，只在 r1 编译/测试一次，r2 按相同 manifest、原 Pod UID 与原产物复用，不宣称重跑后端。

六份工程文件改动，无新增依赖或迁移。镜像精确封装已测试 JAR/dist、运行配置保持；先后端后前端定向更新镜像，其他配置和 Secret 身份/版本保持。六份规范源备份后推进；后端 634、前端 162 文件核对。外部清单只同步两处镜像，未 apply 全量清单。没有 commit/push，AGENTS 与旧记忆保持。

证据归档 17281080 字节、SHA256 `cb5e7dbb437dbe7a118367d85f22041500a65bc90468cc1e9180c1a484f2defa`，722 份文件 / 66 张 PNG 摘要核验；导出前测试口令、JWT/私钥扫描通过，不导出 token/HAR/storage。七个临时 Pod 按 UID 清理为 0，远程 `/root/eap-safety-permissions-20261017-r1/r2` closed，旧命名入口不重放，产物与备份保留。

未重跑真实 MySQL、独立 Runtime 构建、真实模型/工具任务、性能/长稳或穷尽无障碍。没有完成实体级授权、正文溯源、制品跨会话一致性、sidecar 委托或服务端幂等；会话 Agent 关联仍是元数据。原主线可信正文来源、跨刷新恢复、采用→发布待继续，28 调用/9728 输出 Token/工具 0 的真实验收仍待单独授权。

[完整证据](evidence/safety-permissions-20261017/README.md)、[回执](evidence/safety-permissions-20261017/validation.json)。


### Codex 进展：安全会话与制品关联校验已部署（2026-10-18）

会话／事件／制品关联修复已部署到远程 PoC。后端 `10.186.133.66:5001/eap-backend@sha256:1d31e5bdf734ea8ca41e7a5466155632f7e333b626b76ac15210d960bb003acf`；前端保持 `10.186.133.66:5001/eap-frontend@sha256:da4bb1d9eaf22e5a4f743981c11dbaf379b3e52cf0c16535d0bce93be86e7f94`，buildId `2026-10-17T03:04:47.861Z`。前后端 1/1、健康 UP、重启 0，匿名及无效 Bearer 401；本机访问隧道没有重验。

根因是 registerArtifact 仅检查事件存在，未确认它属于指定会话，可把 A 会话事件登记到 B 会话。现在要求指定会话存在且标识精确匹配、事件存在且其 sessionKey 与会话完全相同，全部校验通过才保存。响应保存已核对的会话键和事件 ID。普通/元数据评估也检查精确会话标识；会话登记在数据库近似命中时拒绝改名或更新，避免大小写/重音别名重写历史关联。精确同名会话继续沿用原元数据更新行为。

只有一份生产服务及三份测试文件改动，没有前端、权限、依赖或数据库迁移变化；角色规则仍在 PlatformPermission。事件 Agent/父子关系、制品 URI/正文/哈希真实性、实体级授权均未扩展。本次保证新写入关联，不自动修改历史记录或宣称历史数据全部一致。

全部测试、构建、镜像及浏览器在远程 PoC 经 n1.sh 运行。先用同一新回归在旧服务上复现：应返回 400 的跨会话请求实际 200，1 项预期断言失败，非编译/环境错误。恢复冻结的修复版后，**九类专项 131/131、独立 MySQL 12/12 通过**，失败/错误/跳过 0。专项包含新增 18 项 H2/单元测试及权限、运行时工具、工作流、AX、部署相关回归。MySQL 8.4.11、utf8mb4_0900_ai_ci、全部 84 个 Flyway 迁移成功；继承九项集成场景并增加三种实际大小写/重音近似匹配，证明数据库命中但服务拒绝、合法关联保存、拒绝后事件/决策/制品数量及会话元数据不变。MySQL 仅在临时 Pod 内 127.0.0.1 监听，独立 emptyDir 合成库，无现网 Secret 挂载，验证后正常关闭。

本轮 Maven 打包通过，JAR 与 registry 镜像层逐字节摘要一致，运行镜像配置保持。只定向更新后端 image 字段；前端镜像、应用其余配置及认证/数据库/Secret 身份版本核对保持。没有重新执行后端全量 1074 或前端 409/类型/构建；这些是上一轮基线结果，不计为本轮新增验证。637 份后端候选及 162 份未变前端摘要核对，四份规范源差异备份后推进，外部清单只同步一个后端镜像引用，未 apply 全量清单。

上线四角色真实 Keycloak PKCE、回调、刷新与退出通过；Safety 页 1440/390px、12 个空对象权限探针及 21 路由冒烟通过。viewer/operator 三个安全写入口仍为 403，governance/admin 通过权限后必填字段校验 400。现网会话与安全统计保持；这些空请求不是成功业务写入验收。合法写入和拒绝路径在隔离库验证。真实业务写入、模型及业务工具调用 0，未使用原 28 调用/9728 输出 Token/工具 0 的独立任务预算。

证据 3636587 字节，SHA256 `39c21d1d1bdda30e083c5fbadd67c24c1471ccbf3dab228fc60aed1d6adbcf12`，120 份文件/34 张截图逐份摘要核验。导出前实际测试口令、JWT/私钥扫描通过；凭证只在远程内存/stdin，不导出 token/HAR/storage。上线截图抽查通过，不作为完整视觉/无障碍审计。三个本轮临时 Pod 按 UID 清理，剩余 0；远程 `/root/eap-safety-association-20261018-r1` closed，源码、产物、失败复现与回退材料保留，不重放命名入口。

无 commit/push，AGENTS 保持，旧记忆完整保留。未运行长稳、性能、付费模型、独立 Runtime 构建或现网有效业务写入。服务端幂等、跨刷新恢复、可信正文来源和采用→发布仍待；安全会话的实体身份、同名元数据更新约束及历史关联审计也未完成。

[完整证据](evidence/safety-association-20261018/README.md)。


### Codex 进展：系统进一步测试与磁盘清理完成（2026-10-18）

按用户“现在进一步测试系统”和随后“磁盘空间也可以清理一下”，完成远程全量回归、数据库/Runtime/浏览器验证及缓存清理。本轮未修改业务代码、未部署、无 commit/push；AGENTS 与此前记忆保持。远程 `/root/eap-system-validation-20261018-r1` 已 closed，12 个本轮临时 Pod 已按 UID 清理，剩余 0，不重放旧命名入口。

当前后端仍 `10.186.133.66:5001/eap-backend@sha256:1d31e5bdf734ea8ca41e7a5466155632f7e333b626b76ac15210d960bb003acf`；前端仍 `10.186.133.66:5001/eap-frontend@sha256:da4bb1d9eaf22e5a4f743981c11dbaf379b3e52cf0c16535d0bce93be86e7f94`，buildId `2026-10-17T03:04:47.861Z`。最终健康 UP、前后端原 UID / 就绪 1/1 / 重启 0，匿名与无效 Bearer 401，受保护配置及 Secret 身份/版本保持。本机隧道未重验。

本轮实际执行：**后端 120 类 1092/1092；前端 409/409、类型与构建；MySQL UTC 248、上海时区 104、安全关联 12，共 364；Go Runtime 66 个顶层/含子测试 102，三个组件构建与 race；Python 优化器 43、tool-guard 5，全部通过，无跳过。** MySQL 8.4.11、utf8mb4_0900_ai_ci、每个合成库 84 项 Flyway 成功，独立 emptyDir、Pod 内回环监听、无现网 Secret。

当前部署 dist 的真实四角色 PKCE/回调/刷新/退出、21 路由桌面与 390px 冒烟、12 个空对象安全权限探针，以及评估16/审计4/安全5共25组隔离交互通过。新增8组读失败503注入/恢复、匿名与无效令牌拒绝、退出后历史导航通过；pageErrors/真实 API 5xx 0。v1 一次下拉选项断言跨全页误计9个时间/事件类型选项；v2 按会话 combobox 所属列表限定，实际会话选项0，两种宽度完整重验，原失败证据保留，没有改产品代码。真实业务写入、模型及业务工具调用0；现网成功写入仍未验收。

**环境风险如实保留：** 初始全量后端和两组 MySQL 并行，在约8GB共享节点引发严重内存压力；02:07:31 UTC 可用112096kB、memory PSI some avg10=95.80。API/CRI清理超时后，仅停止两个本轮固定UID cgroup，02:11:01健康恢复。压力期间无连续业务请求采样，无法排除短时用户延迟。数据库r2随后被DiskPressure拒绝，未启动；停止等待，保留全部失败/中断材料。r3清理后串行，开跑前检查内存、PSI、磁盘>22GiB及无压力，三组最低可用内存约1.57/1.58/1.65百万kB，全部通过。结论DONE_WITH_CONCERNS，不是容量/长稳验收通过。

用户授权后删除28个未使用历史node_modules与1个Go编译缓存目录，排除活动挂载/工作目录/打开文件，核对相邻文件。**净释放7,034,331,136字节（6.55GiB）**，清理当时可用24.54GiB，后续测试与归档结束可用**22.72GiB**；inode增加422348，节点MemoryPressure/DiskPressure=False、Ready=True。保留源码、dist、证据、截图、归档、数据库、日志和镜像，未做全量prune；历史依赖需要时在远程按锁文件重装。

后端637/前端162/Runtime38输入与本机末态摘要一致，远程规范源含Dockerfile.cluster共163份前端文件核对。证据归档13,352,847字节，SHA256 `5dacd926a6716c9993d98a03916357be005f5c49b502d259de488630b870347d`，523份文件/69张截图逐文件核验，导出前实际口令/JWT/私钥扫描通过。最后清理回执单独下载并计算摘要；不导出token/HAR/storage。人工抽查安全和评估桌面/手机4张截图及失败截图，未见遮挡/横向溢出，不作为穷尽视觉审计。

未做完整现网CRUD、性能/长稳、故障切换、备份恢复或付费模型/工具任务。可信正文、幂等/跨刷新恢复、采用→发布、会话实体身份/同名元数据约束/历史关联核查仍待；原28调用/9728输出Token/工具0的独立真实验收未使用、仍须单独授权。

[完整报告](evidence/system-validation-20261018/README.md)、[最终回执](evidence/system-validation-20261018/post-check.json)、[所有尝试](evidence/system-validation-20261018/ATTEMPTS.md)。


### Codex 进展：会话、权限变化与导航补测完成（2026-10-18）

按“继续测试”完成新一轮远程浏览器专项，采用qa-only黑盒方法。**20个最终场景/宽度组合通过，另记录两个行为观察项。** 本轮不改业务代码、依赖声明或部署，无commit/push；后端637、前端162、Runtime38份本机输入摘要保持，AGENTS及此前记忆保留。未重跑上一轮后端1092/前端409/MySQL364等，不能把上一轮数字计入本轮。

真实页面14组：已登录读取实际401清除令牌与写入口；退出后的迟到200不能恢复权限；旧的注入401在完整重新登录后不破坏新会话；无效回调不创建会话且可通过真实“重新登录”按钮完成PKCE；viewer审计→安全的客户端导航/前进后退/刷新与手机抽屉；双标签退出范围；登录页后退取消及重试。合成API6组：/me能力优先于矛盾角色名、刷新后的能力降级清除写表单；写401清除入口且不假成功/重试；写403不假成功或刷新重发。每项1440/390 px各一次，远程Chromium155.0.8059.39，绑定当前部署68文件dist。

实际共执行22组：live-r1的10组通过，异常回调两组因脚本错找“企业账号登录”失败；截图实际存在“重新登录”与错误提示。v2保留原脚本/失败证据，只更正测试入口并执行完整登录恢复，原2组及新增2组取消重试通过；capability-r1的6组通过。总回执allAttemptsPassed=false、latestChecksPassed=true、uniqueChecks=20、rawAttempts=22；不能称首次全绿。未处理JS pageerror与真实API5xx均0，未采集完整console日志。

**OBS-01：退出范围。** 两种宽度均验证A退出后B刷新仍可/me200，A再次登录不显示口令表单且回到原subject。属于当前标签退出、其他标签及SSO会话保留的实测行为；“退出”是否应为全局退出尚无明确产品契约，不能当作已证明越权或全局会话撤销保证。

**OBS-02：403提示/流程一致性。** 合成API明确403后，顶部toast说明“当前账号没有执行此操作的权限”，表单同时仍显示结果未确认和手动核对入口；没有假成功或自动重发。两种宽度复现，桌面截图完整显示两种语义并存；手机常驻提示在折叠以下，存在性由DOM断言证明。不能描述为页面完全没有展示权限原因，也不能把合成复现当作现网有效写入验证。观察项未修复，产品代码未改。

初始n1连接挂起，45秒只读检查超时；按精确命令/stdin路径核对本轮PID树后仅结束自己的连接，后一次约1秒恢复，根因未确证为应用故障。预检无旧回执后重新安全执行，03:57:18UTC健康UP、内存2,964,764kB、PSI0、节点无压力。仅一个Pod，内存请求1GiB/上限1536MiB，串行执行；最低观测可用2,540,528kB，未触发900,000kB保护。没有运行并行后端/MySQL或模型任务。

backend仍 `10.186.133.66:5001/eap-backend@sha256:1d31e5bdf734ea8ca41e7a5466155632f7e333b626b76ac15210d960bb003acf`；frontend仍 `10.186.133.66:5001/eap-frontend@sha256:da4bb1d9eaf22e5a4f743981c11dbaf379b3e52cf0c16535d0bce93be86e7f94`，buildId `2026-10-17T03:04:47.861Z`。清理后健康UP，前后端原Pod/镜像、就绪1/1、重启0，受保护配置和Secret身份/版本保持；磁盘可用约21.86GiB，节点MemoryPressure/DiskPressure=False。唯一临时Pod UID 570eb0c0-68f5-4a97-9010-ec071fa5c513按UID清理，剩余0，`/root/eap-session-navigation-20261018-r1`已closed，不重放旧入口。

现网业务写入、真实模型和业务工具调用均0。口令仅远程内存/stdin，不导出token/HAR/storage。证据归档4,315,906字节，SHA256 `da66e6ac507807ab19f44f64bd073c459275f51b6354bd2c379047be109e3c87`，91份文件/42张截图逐文件核验，实际口令/JWT/私钥扫描通过；最后清理回执另行下载。人工抽查6张截图，不构成全站视觉/无障碍验收。

未做自然JWT到期、同一文档内令牌续期竞态、服务端撤销/全局退出、其他SSO账号切换、现网有效业务写入、性能/长稳或完整路由/无障碍验证。本机隧道未重验；原28调用/9728输出Token/工具0的真实验收未使用。可信正文、幂等/跨刷新恢复、采用→发布等主线待办保持。观察项细节见[报告](evidence/session-navigation-20261018/README.md)、[FINDINGS](evidence/session-navigation-20261018/FINDINGS.md)、[最终回执](evidence/session-navigation-20261018/post-check.json)。


### Codex 进展：通过 EAP 创建、调度与观测三智能体样例（2026-10-18）

用户要求利用当前系统构建样例，随后明确创建、调度、观测都由 EAP 完成。新增 `examples/multi-agent-incident/`：虚构订单服务事件 E1–E6，分析员→复核员→报告员，以及仅调用 EAP API 的准备、单次执行、只读核对、反馈和导出客户端。客户端不直连模型供应商、不调用 Actor 任务入口、不自行逐步调度。所有校验和执行在远程 PoC 节点；本机仅编辑、打包及核对接收文件。

通过 EAP 建立 Agent 69/70/71、Team 4、Plan 5、步骤10/11/12；先 DRAFT/九项零预算，再由 EAP 发布固定 Runner 镜像和不同专属实例。明确独立3调用/3072输出/工具0范围后，按用户“全部用EAP”的要求，通过平台启用Agent、读取真实profile及UID/身份期限、检查后休眠，设置三个新Agent各1调用/1024输出，原工具0；只向计划API提交一次。EAP生成Workflow29，原runKey `wf-exec-fd62d5ef-ca65-4b9c-a47a-ae8e6b11ed88`，串行派发、结果交接和自动休眠完成。

工程链路通过：三份网关实际INPUT摘要与含完整原上游output/taskKey/agentId/outputSha256的重建请求逐字节一致；三份输出摘要、三条agent.task Span与唯一GATEWAY事件197/198/199对应。平台保持HASH_ONLY，没有改变内容采集策略；实际原步骤输出按viewContent权限读取。Plan COMPLETED、Workflow SUCCEEDED，三个实例最终SUSPENDED，Actor UID不同且未替换。

内容验收未通过：3次模型共2808输入/3072输出Token，其中2539为reasoning（已包含在3072内）；三步均用满1024。分析员、复核员JSON中途结束且不能解析；报告员指出上游截断，但自身简报也在证据段中途结束，缺少后续章节。普通Runner askScoped没有检查finish_reason、空正文或结构完整性即返回，导致任务状态成功不等于内容合格。HASH_ONLY未保留原供应商finish_reason，不能把具体length枚举当作实测事实。已通过EAP登记QUALITY反馈4/5/6，关联原Trace，OPEN；未伪造完整正文、补跑、升额度或调用优化器。

平台总增量精确为MODEL_CALLS+3、OUTPUT_TOKENS+3072、TOOL_CLAIMS+0，原PLATFORM上限/margin/revision/reserved/violated保持；三个新Agent无未结算预留且调用额度已耗尽。网关金额字段0未经供应商账单核验，不代表免费。本轮独立样例没有执行此前待单独授权的28调用/9728输出方案，也未重开旧根或转移旧额度。

最终前后端原镜像/Pod UID保持，ready1/1、重启0、健康UP。样例实例、配置、原执行与反馈保留在EAP供查看，无临时测试Pod，无业务源码或前后端部署更改，无commit/push。远程目录 `/root/eap-sample-incident-20261018-r1` 保留原操作意图与回执，模型支出已结束，不重跑execute-once或删除state.json。归档40006bytes/SHA256 `1b67e20368742a618d6ba7968f98a707c9f8097a6f4b1403b737e0faed929d45`，10文件；实际敏感Secret值仅远程RAM扫描通过，本地9文件摘要/6源文件一致。

尚未修复普通Runner截断识别、EAP普通任务思考模式/输出上限配置和终止原因元数据；应先远程回归，再另定新计划及独立预算验证完整简报，不能重放Plan5。本轮未做浏览器点击、全量应用回归、性能或长稳测试。工程verified=true、完整liveRunVerified=false，不能宣称业务样例验收全过。[样例说明](../examples/multi-agent-incident/README.md)、[实测报告](evidence/multi-agent-sample-20261018/README.md)、[最终回执](evidence/multi-agent-sample-20261018/receipt.json)。前序测试观察和可信正文/幂等/采用→发布等主线待办继续保留。


### Codex 进展：普通任务截断修复进行中（历史过程，最终结果见下一节，2026-10-18）

用户要求继续。已定位并远程复现普通 Runner 未检查 finish_reason 的根因，新增失败/未知回执缓存、严格文本终态检查、安全 Span 元数据、EAP maxTokens/thinkingMode 部署字段与界面、工作流失败码说明及下游阻断回归。原请求省略新字段时保留配置形状，旧模板/实例不自动升级。

本轮工作目录 `docs/evidence/model-output-20261018/`，远程 `/root/eap-model-output-20261018-r1`。修复后 Runner 普通回归通过；主机无 C 编译器，race 待隔离 Pod。后端专项已通过，完整后端以及随后前端测试/构建仍在执行，尚未发布。没有新增真实模型/工具调用，没有 commit/push。保留旧 Plan5、Agent69–71、原 state 和已用预算；已通过 EAP 读取发布前基线。

准备 `examples/multi-agent-incident/verification-v2/` 新独立样例配置（另建三名 Agent、计划，各1次/1024输出/工具0，总3次/3072，显式关闭思考）。它只在本轮测试、发布与实际配置核对完成后执行一次；旧28调用方案仍不执行。当前尚未创建新样例。后续需补全测试回执、镜像验证、定向发布、四角色部署界面、新样例实际输出审阅、证据及按UID清理；以上未完成事项不能记为通过。


### Codex 进展：截断修复已部署，EAP独立三智能体样例完整运行（2026-10-18）

用户要求继续后，完成普通Runner严格终态检查、失败/未知回执缓存与同键变参409、受控maxTokens/thinkingMode部署配置、前端显示、工作流安全失败码及下游阻断。只接受单个正常stop、非空且无工具调用的文本响应；截断/空正文等FAILED，无法确认的响应UNKNOWN，不进入历史/下游。缓存仍在进程内存，重建后依赖网关任务身份；stop不能证明JSON、事实或业务质量。旧请求省略新配置字段时保留幂等形状。18项应用/测试/Runner文档改动，无权限、数据库迁移、认证或依赖版本变更，无commit/push。

远程后端专项55、完整1097，前端410/类型/构建通过；Runner两次竞态回归各42顶层/85含子项。四角色真实PKCE、桌面/手机8组部署表单能力和配置检查通过，业务写入/JS异常/API5xx为0。后续实际样例发现两个新观测字段被已有通用脱敏规则隐藏：max_tokens命中token、output_status命中output；保留原Span及首次自动核对失败。更名为generation_limit、response_state，不放宽脱敏；新增4项EAP摄取测试，直接使用新Runner的合成OTLP载荷，确认字段保留且不会重复模型计数。第二轮仅更新默认Runner，没有新增模型调用。

当前backend `10.186.133.66:5001/eap-backend@sha256:20e6c879be6927833045a61d949ec34e4c1226acd6cafcf318d066ac9a07390c`，Pod UID `a68ed8be-fc78-4442-ad32-62fd00775939`；frontend `10.186.133.66:5001/eap-frontend@sha256:3cc753c115ad3a96001d15544898bbb00fa6a6b5559ef9dce3635daf4b8574eb`，Pod UID `a84758e8-6d92-4931-827a-9a7c3f6d2463`，buildId `2026-10-18T05:25:06.366Z`。未来新实例默认Runner `10.186.133.66:5001/eap-runner@sha256:bea2047e557504f69572a940d6bf8d26aac6f0c680226f97df8904d6a2ee6974`。只定向更新镜像和Runner默认项，其他部署spec与受保护配置哈希保持。规范源18项与本地最终摘要核验一致，补丁在deploy/substrate-poc/model-output-release.json。

另建独立Team5 / Plan6 / Workflow30，分析员72→复核员73→报告员74，步骤13/14/15；所有创建、部署、身份、预算、派发、交接、休眠、观测、反馈均通过EAP API，客户端未调用Actor任务或供应商接口。只提交一次新计划，runKey `wf-exec-b868cfe0-d00a-49b8-b178-81c6fce23725`。各新Agent限1调用/1024输出/工具0，总3调用/3072；平台限额不提高。实际3调用、3152输入/979输出、reasoning0、工具0，网关事件200/201/202。三个独立UID、三份完整请求摘要和原输出摘要、Agent Span与Gateway对应；实际finish_reason=stop、thinking_mode=DISABLED。单次上限由冻结profile和完整请求摘要独立证明，不能声称旧Span的被隐藏字段可见。

两份JSON和最终五节简报完整，保留虚构/未执行修复标记、数值/证据、因果不确定性及三个只读建议。质量观察：复核员称已纠正过度推断，但上游原本已经谨慎；单时刻CPU数据也不宜笼统表述为排除饱和。最终报告未采用此排除结论，EAP反馈7/8/9关联原Trace；反馈8评分3明确记录，其余评分4。结论BOUNDED_SAMPLE_PASS_WITH_REVIEW_CONCERNS、liveRunVerified=true，不代表稳定生产质量或自动修复能力。三个新实例休眠，无未结算预留。样例实际用第一次修复Runner b362e486…，保持原版本；新默认bea2047e…仅作合成契约回归，未为新观测字段额外付费重跑。

原Team4 / Plan5 / Agent69–71、原state、实例和已用额度通过EAP前后核对保持。新客户端在examples/multi-agent-incident/verification-v2/，远程 `/root/eap-sample-incident-20261018-r2`，modelSpendClosed=true，不重跑execute-once、不删state、不换键补跑。旧28调用/9728输出团队优化方案仍未授权、未执行。

远程 `/root/eap-model-output-20261018-r1` 已closed；长SSH观察曾超时，首浏览器Pod超过2小时时限退出，另建UI专用Pod完成验证。10个临时测试/构建Pod按UID清理，剩余0，证据和两个样例均保留。最终健康UP、前后端就绪1/1/重启0，可用磁盘19.04GiB。修复归档1,907,751bytes/SHA256 `64949ef99e9e384eaad6e069e5aef282f1fe6d88c6f6ba6d9f672f06a9742ec3`，393份内容摘要；样例归档42,487bytes/SHA256 `394735eda9c2797f216c27428daf86b816b0d05c967bb5112e6f3c227c3e4c97`，9份内容摘要均核验。实际Secret值仅远程RAM扫描，无密钥或JWT导出。

本轮未重跑独立MySQL、性能、长稳、全部页面或本机隧道；前序会话退出范围/403提示观察及可信正文、跨刷新幂等、采用→发布等主线仍待。后续可先改进复核员提示词的证据定位和措辞，但任何新真实运行必须有新的明确范围及预算，不能复用已结束调用。见[修复报告](evidence/model-output-20261018/README.md)、[完整样例](evidence/multi-agent-sample-20261018-v2/README.md)、[事件简报](evidence/multi-agent-sample-20261018-v2/incident-brief.md)。


### Codex 进展：全系统审查与两项缺陷复现（2026-10-18）

用户要求全面 review，评估还需更新修改的方面。本轮按 review 技能检查安全/权限、工作流和 Runner、预算/观测、前端、产品闭环与运维证据；专项审查并行核对。新增 [系统审查报告](SYSTEM_REVIEW_20261018.md)，列 5 项 P1、6 项 P2，均附具体位置、触发条件、修复和验收建议。没有修改业务源码或部署，没有 commit/push。

P1：业务连接器可选择任意后端环境变量并发给配置地址；OTLP rag.query/mcp.request 正文可经 attributesJson 绕过采集模式和 viewContent；同一 Runner 的 task/chat 共用历史；等待任务取消后仍可新发起调用；聊天缺少持久提交身份，丢响应重试可能重复计费。P2：标准 JSON 凭据漏脱敏；部署原键在刷新/切页/5xx 后丢失；A 实例迟到回复进入 B 聊天；明确 FAILED 提前返回绕过独占资源回收；原文期限没有擦除实现；最新500条窗口可能首次漏建旧运营信号。没有读取真实凭据或实施外发，不宣称系统已经遭攻击；REDACTED/retainRaw 等条件项不等于已发现现网泄漏或超期。

经 n1.sh 在 ate-node1 新独立目录 `/root/eap-system-review-20261018-r1` 使用当前16份 Go 源及合成 httptest 网关运行两项负向回归：TestReviewIndependentTasksDoNotShareHistory、TestReviewCancelledWaitingTaskDoesNotStartModelCall，均 FAIL，分别确认 B 模型请求含 A 标记、取消后仍有1次替身模型请求。这是新发现的失败证据，不是通过的回归，也没有将探针混入业务测试套件。源包 SHA256 `00cb48f09332ba03ddeab2166b6594c4b82e0d26759aef08ace049a0229583ed`；探针及源摘要见 [证据](evidence/system-review-20261018/README.md)。首次 SSH 被本机沙箱阻断，授权后远程执行一次；没有真实模型调用、业务写入或测试 Pod。本机仅编辑、打包、核对文件，不编译测试。

只读抽查 backend/frontend/mysql/prompt-optimizer ready1/1，根分区约22GB可用；未改线上版本。没有重跑全量后端、前端、MySQL、浏览器、性能或长稳；旧1097/410/Runner42/85及后续4项契约等仍属于前序证据。已确认现有预算锁/未知预留、工作流owner/epoch、实例身份绑定、公平老化和Keycloak持久备份等防护，未将其误记为缺失。

仍需统一 systemPrompt 可见性、结构化输出与证据引用、通用持久回执/未知核对、反馈采用→发布、退出范围/403文案、生产认证HA/TLS/灾备及容量。修复优先顺序：安全边界→任务隔离/取消/聊天幂等→控制台及运营恢复→业务闭环→生产门槛。原Plan5/Plan6及已用额度保持关闭，旧28调用方案未执行。此次任务是审查，报告中的修复尚未实施。


### Codex 进展：R1/R2/R6 安全边界修复已部署（2026-10-18）

用户在全系统review后要求继续，完成第一批安全修复：连接器不再按业务输入读取任意后端环境变量，改为平台登记引用并绑定connectorKey、HTTPS origin和认证参数；span/tool属性统一允许字段和类型，新写入与历史分页/非分页读取均投影；OTLP派生RAG知识摘要去正文、引用摘要化，历史读取也过滤；JSON凭据递归脱敏，含对象/数组、嵌入字符串和异常结构隐藏。前端同步凭据引用提示。共11个生产文件、6个测试文件，无数据库迁移、依赖版本、权限或Runner变更，无commit/push。兼容字段credentialEnvKey保留，但值已不是任意环境变量名。

所有编译、测试、构建、发布在ate-node1隔离Pod串行完成，本机只编辑、打包和核对证据。r1专项86项中3个错误来自新测试ObjectMapper未注册Instant模块，原日志保留且未进入全量/发布；修正测试并补普通文本内嵌JSON用例后，独立r2专项87、完整后端1132、前端410及vue-tsc/Vite通过，失败/错误/跳过均0。专项包含在完整计数中。三种采集模式服务测试和四角色旧属性数据库集成是分别覆盖，不能描述为现网切换策略的交叉测试。

发布前后viewer/operator/governance/admin真实PKCE及只读接口通过。四角色spans/tool分页及非分页16个组合各读5条，未出现检查的正文属性；正文接口viewer/operator403、governance/admin200，与/me能力一致。现网连接器总数0，未发生旧认证配置中断；后续须登记最小权限凭据及目标绑定，未知旧引用默认拒绝。没有真实外部业务连接器验收，没有向现网写合成敏感样本。

只更新前后端镜像，测试后JAR/dist与镜像层内容摘要核验，入口/version逐字节一致。backend `10.186.133.66:5001/eap-backend@sha256:d2c4e973855b1869fbfda832b5fd0757337a173d0ed6ea31db50bf7547ac3e94`，Pod UID `93e81b70-fc27-4ed1-bfde-0db7608a0c5d`；frontend `10.186.133.66:5001/eap-frontend@sha256:cdbc0c6030c81dca19c6dc4bd1c807cb5de20cc2f01f2578e5665c7748d62768`，Pod UID `4dc80b61-3bf6-4f71-9d14-6e9256008ae6`，buildId `2026-10-18T10:40:56.870Z`。默认Runner `10.186.133.66:5001/eap-runner@sha256:bea2047e557504f69572a940d6bf8d26aac6f0c680226f97df8904d6a2ee6974`保持。平台预算、Deployment其余spec、受保护配置摘要及Secret身份/版本保持；真实模型、业务工具调用和现网业务写入均0。补丁记录deploy/substrate-poc/security-boundaries-release.json。

17份变更先检查旧摘要再同步/root/eap-src，804份后端/前端输入与本机最终摘要一致。5个临时Pod核对UID与终态后清理、剩余0；收尾健康UP、两端ready1/1/重启0，节点MemoryPressure/DiskPressure/PIDPressure=False，磁盘可用20.51GiB。远程/root/eap-security-boundaries-20261018-r2已closed，不重放bootstrap/发布/收尾入口。原r1失败证据保留；应用源包、历史运行和样例未删除。

归档927879bytes/SHA256 `ea09cdadeb86231df2713dc267424c6ec960a714a73f40eea8615de8b7cf4433`，329份内容摘要逐文件核验。导出前实际Secret值仅远程RAM扫描，JWT/私钥模式检查通过，无令牌、Secret对象、HAR/storage导出。[验收报告](evidence/security-boundaries-20261018/README.md)、[安全边界约定](SECURITY_BOUNDARIES.md)、[最终回执](evidence/security-boundaries-20261018/final-receipt.json)。

本轮未重跑独立MySQL、Runner、浏览器、性能/长稳、故障切换或备份恢复。历史属性和正文未物理擦除，旧REDACTED评估样本仍需审计；PUBLIC保持启发式兼容，不能作为公开性证明。系统审查剩余3项P1和5项P2，下一步优先R3任务历史隔离、R4取消/执行责任，再做R5持久聊天提交身份。原Plan5/Plan6及已用额度关闭，旧28调用方案未执行。可信正文、采用→发布、会话退出范围/403体验、生产门槛等仍待，不宣称系统已无问题。


### Codex 进展：Runner隔离与取消修复验证中（2026-10-18，过程记录）

用户继续后推进R3/R4，采用investigate定位根因。当前工作区已改17份应用/测试文件（9生产/8测试）：普通task无隐式历史、chat仅显式当前窗口历史；可取消等待锁及上下文传递、发送后UNKNOWN缓存；旧平台RUNNER任务及chat能力前检阻断；工作流模型准入核对RUNNING/期限/当前DISPATCHING步骤/实例及owner-epoch的TASK_WAIT，按workflow→budget锁顺序；控制台会话重置与迟到响应隔离同步处理R8。未改变权限/依赖/迁移，没有commit/push，没有真实模型或工具调用。原Plan5/Plan6和旧28调用保持关闭。

远程r1旧两探针负向复现失败、修复Runner race/vet/build通过，后端测试编译因旧三参数chat测试失败；r2补兼容入口/能力夹具及旧Runner任务前检，Runner再次通过，后端专项117中1个新断言失败（UNKNOWN调用次数计入used，输出Token仍reserved）。r3只校准该测试，复用Go输入摘要完全一致的r2通过证据。/root/eap-runner-isolation-20261018-r3主验证PID357631、后续PID357632；后端专项/完整、前端、独立MySQL和发布串行排队，尚未确认上线成功。不要重放bootstrap或r1/r2发布入口；读取status.json/release-status.json/release回执。browser-contract已准备未执行，最终源码同步/证据/清理仍待。详见docs/evidence/runner-isolation-20261018/README.md。


### Codex 进展：R3/R4/R8 已部署并完成最终验收（2026-10-18）

用户继续后完成Runner显式历史、可取消串行等待、HTTP上下文传递及工作流模型准入责任检查；旧平台RUNNER任务/chat要求EXPLICIT_V1，否则在模型调用前拒绝并提示新版本。前端按会话代次丢弃迟到成功/失败/finally，切换身份/Agent/实例及卸载重置，仅发送已成功完整问答，限制20轮/64KB。共17个应用/测试文件，9生产/8测试，无权限规则、依赖或数据库迁移变化，无commit/push。旧实例/模板/快照未迁移。

全部编译/测试/构建/发布在远程隔离环境串行执行。后端专项117/完整1144、前端417及vue-tsc/Vite、独立MySQL8.4.11的BudgetLedger37项均通过（失败/错误/跳过0）；Runner48顶层/94含子用例race/vet/build通过，r3复用Go输入完全一致的r2证据。旧实现两项探针失败、修复后原探针通过。r1旧三参数chat测试编译失败；r2新UNKNOWN预算断言错误，修正为调用次数used/输出Token reserved，没有因此修改计费实现。

浏览器首轮定位误匹配隐藏导航抽屉，operator-only诊断确认；限定试运行抽屉后第二轮8组通过。人工截图发现顶栏z-index3000遮住聊天标题/关闭按钮，于ui-fix独立目录补append-to-body/z-index4000；此前已补手机min(480px,100vw)。只重跑前端417/类型/构建并更新前端。第三轮启动前遇旧浏览器30分钟DeadlineExceeded，未执行用例；新专用Pod的第四轮4角色×1440/390共8组通过，终轮16次合成聊天、pageerror/5xx/真实业务写入0，标题命中无覆盖、关闭按钮实际点击、输入框适配均通过。最终8张截图已人工查看。真实PKCE、/me、版本和只读接口，聊天/实例响应是合成拦截，未作真实模型收费验收。

最终backend `10.186.133.66:5001/eap-backend@sha256:d5be7af126eb1b84eed5f8f1980df17da49320476f45f447c81ea0e089d61417`，frontend `10.186.133.66:5001/eap-frontend@sha256:51f0dded4d0745b9d47a19c4b3c99910460e9ecf95a99b0d51e57c2c68cb4441`，buildId `2026-10-18T13:02:38.067Z`，新版本默认Runner `10.186.133.66:5001/eap-runner@sha256:1037b9efd93b1388e9a023368f9511504bfa8c532a5b887d494d6be6747a5482`。JAR/dist/二进制与镜像层摘要核对、入口/version逐字节核对，健康UP、两端ready1/1且重启0；平台预算、受保护配置及Secret身份/版本保持，真实模型/业务工具调用与现网业务写入0。原Plan5/Plan6和已用额度仍关闭，旧28调用未执行。14个临时Pod核对UID清理、剩余0，节点无Memory/Disk/PIDPressure，可用磁盘17.94GiB。17份最终文件核对旧摘要后同步/root/eap-src，825份最终输入与本机一致。远程父目录已closed，不重放bootstrap/发布/收尾入口。

归档3052680bytes/SHA256 `9651b8b96a8bd23dde0a548b1abef7ee1bcaf0818d7659523f159b3629ec3c92`，497份证据逐文件摘要验证。导出前实际Secret仅远程RAM扫描，JWT/私钥模式检查通过，不导出令牌、Secret对象、HAR/storage。失败过程、两轮前端发布及旧浏览器到期状态均保留。[验收](evidence/runner-isolation-20261018/README.md)、[契约](RUNNER_CONTEXT_ISOLATION.md)、[最终回执](evidence/runner-isolation-20261018/final-receipt.json)。

系统审查剩余1项P1(R5)和4项P2(R7/R9/R10/R11)。取消不能撤销已准入/发出的模型费用，UNKNOWN预留不释放；聊天持久提交键/跨重启模型回执未实现，旧实例需要经EAP显式新版本/实例迁移。无新增性能/长稳、HA/TLS、故障恢复或灾备验证。下一步先R5持久聊天身份和原回执查询，再推进控制台/运营恢复及已有产品闭环，不宣称系统全无问题。


### Codex 进展：R5 聊天持久提交验证中（2026-10-18，过程记录）

用户继续后推进 R5。当前16份应用/测试文件（9生产/7测试）新增V85回执表、subject/原键唯一准入及冻结载荷摘要、先提交派发屏障/成功状态再返回；重复POST和GET只查原回执，UNKNOWN不重发。前端发送前保存最小sessionStorage编号，刷新/切页恢复待核对入口，按/me能力控制；同身份原Trace链接使用规范化Trace ID。无正文/异常原文新存储，首次响应正文仅在内存，查询成功不恢复正文；闭浏览器会话/清理存储不保证自动找回编号。协议见CHAT_SUBMISSION_RECEIPTS.md。无真实模型、业务工具调用，无commit/push。

旧源码原键两次POST负向探针实际派发2次（期望1），复现成立。r1专项49通过；补V84→V85迁移/服务重建/Trace关联等检查后中止其未完成全量，不能当作全量通过。r2专项通过；代码复查发现旧PlatformPermissionSecurityTests仍用无requestKey夹具，已更新并加入GET能力矩阵，中止其未完成全量。最终r3所有生产输入与r2一致，仅改该测试。首次bootstrap在Python语法解析阶段失败，未执行任何语句，修正后才建r1目录。

远程最终目录/root/eap-chat-receipts-20261018-r3；validation/release/browser分离后台worker串行等待，只在全量/前端/MySQL通过后定向发布前后端。Runner不改、不重测；旧镜像实例不迁移。请读started.json、status.json、release-status.json和release回执，勿重放bootstrap或发布入口。829份源码清单和16项diff记录在docs/evidence/chat-receipts-20261018。r3最终专项104/全量1155、前端425及类型/构建、隔离MySQL8.4.11共48已通过，前后端已部署且健康UP、四角色只读复验通过；浏览器验收与收尾仍待。backend为b2289d7b3657364b15877be2dc530f13d9cc1689080a0818d69d93d12421fc69，frontend为57d393d7f898dc51dca09c1b75eb22b07c2ed373b41ff18f61d868c29da7e149，buildId为2026-10-18T14:18:12.530Z，Runner保持1037b9ef…原版本。后续需完成证据导出、UID清理、规范源码同步和最终状态更新。原Plan5/Plan6、旧28调用计划保持关闭。


### Codex 进展：R5 持久聊天回执已部署并收尾（2026-10-18）

用户继续后完成R5，按investigate先复现原实现原键两次POST→合成Runner收到2次派发，固定回归要求1次。当前16个应用/测试文件（9生产/7测试）：V85回执表、subject摘要/原键唯一准入、冻结Agent/实例/消息/完整历史摘要；先提交派发屏障再发送，先提交成功状态再返回。重复POST和GET都不恢复执行，原键改载荷拒绝，查询归属及接口能力统一使用PlatformPermission.OPERATE_INSTANCES。前端发送前保存sessionStorage最小编号，刷新/切页/切实例保留待核对入口，迟到响应不串入新窗口，完整问答只追加一次；规范化Trace链接指向原执行链路。没有依赖或Runner变更，无commit/push。

最终r3远程专项104/7类、完整1155/126类及打包、前端425/类型/构建、隔离MySQL8.4.11共48项全部通过（失败/错误/跳过0，专项包含在全量中）。Mysql覆盖V84→V85、重复迁移保留UNKNOWN、真实DB并发回执及旧预算回归；服务重建/遗留派发是持久状态夹具，不是现网kill。首次bootstrap解析语法失败未执行语句；r1专项49后为补迁移/重建/Trace检查中止未完成全量，r2专项67后发现旧权限矩阵夹具需requestKey，修正并加GET能力对照后中止未完成全量；r3生产源码与r2相同，仅该测试更新。原记录保留，不把r1/r2算全量成功。

发布前/后/最终四角色真实PKCE、只读接口/新GET权限通过。浏览器真实登录和新部署UI、聊天接口合成拦截；4角色×1440/390共8组首轮通过，24次合成聊天、8次原回执查询，pageerror/API5xx/业务写入0。8截图已人工查看，聊天标题/关闭无遮挡、手机编号/查询/输入区适配。没有收费模型或业务工具调用，Plan5/Plan6和旧28调用保持关闭。

backend `10.186.133.66:5001/eap-backend@sha256:b2289d7b3657364b15877be2dc530f13d9cc1689080a0818d69d93d12421fc69`，Pod UID `14c9ec6f-1ad1-4852-9e52-3934361aa132`；frontend `10.186.133.66:5001/eap-frontend@sha256:57d393d7f898dc51dca09c1b75eb22b07c2ed373b41ff18f61d868c29da7e149`，Pod UID `7e9ff8b9-e6f4-47d4-baa0-ed2444ee4b53`，buildId `2026-10-18T14:18:12.530Z`。默认Runner保持 `10.186.133.66:5001/eap-runner@sha256:1037b9efd93b1388e9a023368f9511504bfa8c532a5b887d494d6be6747a5482`，本轮未重测。JAR/dist与镜像层、入口/version核对；生产V84→V85成功，新表0行，健康UP/ready1/1/重启0；平台预算、其余部署spec、受保护配置及Secret身份/版本保持。9个临时Pod按UID清理，剩余0，节点无Memory/Disk/PIDPressure，可用磁盘19.09GiB。16项变更核对旧摘要后同步/root/eap-src，后续独立只读核对全部829份与本机最终输入一致。远程/root/eap-chat-receipts-20261018-r3已closed，勿重放任何原入口。

归档2057533bytes/SHA256 `295947f41eb07ab69ffe5290899b1c2cad170366707ac709b2a215f704d34315`，432份证据逐项核验；预览截图与最终归档匹配。实际Secret仅远程RAM扫描，JWT/私钥模式扫描通过，无令牌、Secret对象、HAR/storage导出。[验收](evidence/chat-receipts-20261018/README.md)、[契约](CHAT_SUBMISSION_RECEIPTS.md)、[回执](evidence/chat-receipts-20261018/final-receipt.json)、[规范源码核对](evidence/chat-receipts-20261018/canonical-source-verification.json)。

回执仅存元数据，不恢复丢失正文；派发后失联可能长期UNKNOWN，不能自动补发或释放既有未知预算。sessionStorage不保证关闭会话/清存储/换设备后自动找回编号，不是跨标签页共享会话。未做真实供应商回执、收费复验、性能/长稳、HA/TLS或灾备；旧Runner实例仍需EAP显式迁移。此次审查已定位P1修复完成，剩余R7/R9/R10/R11四项P2及既有产品/生产门槛。下一步优先R7部署跨刷新恢复，不宣称系统全无问题。


### Codex 进展：R7 部署提交恢复验证中（2026-10-18，过程记录）

用户继续后推进R7：6生产/4测试文件，原键GET只读元数据查询、subject/Agent作用域sessionStorage最小编号和配置SHA-256摘要；网络/5xx等失败保留编号，刷新/切页恢复，迟到回包隔离，配置变化阻止按原键发送。NOT_FOUND仅为当前快照，不能释放原键；人工重新填写原配置才能按原编号提交。未保存提示词/环境变量；查询不调用运行时，按DEPLOY_AGENTS统一权限，部署仍是既有协作资源，不是逐用户私有ACL。

旧组件502后两次请求生成不同键的负向测试已复现。r1前端443项、类型及Vite构建通过；后端专项92项中5个新增集成测试因合成签名材料少于32字符被现有配送校验挡住，87通过、5失败，全量未启动，现网未改变。仅修正测试夹具，生产输入完全相同，r2新目录/root/eap-deployment-recovery-20261018-r2验证中，831份清单；前端复用r1完全相同输入的通过结果。不可重放r1/r2 bootstrap/worker；先查看started/status/release回执。后端全量、隔离MySQL及发布/浏览器/收尾未完成，不能声称R7已部署或审查已关闭。证据docs/evidence/deployment-recovery-20261018，契约docs/DEPLOYMENT_SUBMISSION_RECOVERY.md。没有commit/push、本机构建测试、真实模型或业务工具调用，Plan5/Plan6及旧28调用仍关闭。


### Codex 进展：R7 部署原键恢复已部署并收尾（2026-10-18）

R7部署提交跨刷新恢复已修复并部署：发送前保存按subject/Agent分隔的原编号与配置SHA-256摘要，刷新/切页/账户切换隔离，迟到回复不串入新页面；网络/5xx等失败保留编号，NOT_FOUND不释放原键，同配置人工重试复用原键。新增只读原键GET，不受最近20条限制，不返回提示词/环境变量，不触发运行时操作，权限统一来自DEPLOY_AGENTS。部署仍为现有协作资源，不新增个人私有ACL。6生产/4测试文件，无迁移、依赖或Runner更改。

远程最终后端专项92/6类、完整1163/127类及打包、前端443及类型/Vite构建、隔离MySQL8.4.11共23项全部通过（失败/错误/跳过0，专项包含在全量中）。旧组件502后原键丢失负向探针已复现。r1前端通过，但新集成夹具的合成签名材料不足32字符导致后端专项87通过/5失败，全量未启动、无发布；仅修正夹具后r2重验后端，前端沿用完全相同输入的r1通过结果。失败过程保留，不计作全量通过。

发布前/后/最终四角色真实PKCE和只读接口检查通过。浏览器4角色×1440/390共8组首轮通过：8次合成部署POST对应4个原意图/4个合成版本、8次部署查询，覆盖502/断线、NOT_FOUND、刷新、切页签、导航、配置变化及原键重试；另24次合成聊天和8次聊天查询保持R5/R8回归。预期合成502共4次，非预期API5xx/pageerror/真实业务写入0。12张截图人工检查通过（4部署、4聊天、4只读页面）；只读手机截图未包含操作区，按钮权限以DOM断言为据。没有真实Actor故障注入、供应商模型或业务工具调用；首实例只创建一次在隔离服务/数据库测试中核验，浏览器部署接口为合成拦截。

backend `10.186.133.66:5001/eap-backend@sha256:b5731e4739b2b987bad8de886c579336d026d4fe24f8cbdf9a05d2ec3a667fca`，Pod UID `20cc1f53-36e9-4416-a302-9271c4c7c7d5`；frontend `10.186.133.66:5001/eap-frontend@sha256:7ed25830dac7ef25d3820a3757eac157c47b82eac4ceb293ea5e94dadc6ba0f1`，Pod UID `842ac761-e2cb-4d86-988c-48255435747d`，buildId `2026-10-18T14:49:15.964Z`。Runner保持 `10.186.133.66:5001/eap-runner@sha256:1037b9efd93b1388e9a023368f9511504bfa8c532a5b887d494d6be6747a5482`，本轮未重测。JAR/dist与镜像层及实际入口/version核对；健康UP、ready1/1、重启0。Schema保持V85、聊天回执表0行，平台预算、受保护配置及Secret身份/版本不变。8个本轮临时Pod按UID清理、剩余0；节点Memory/Disk/PIDPressure均False，可用磁盘18.27GiB。10项修改核对旧摘要后同步/root/eap-src，全部831份最终输入核对。远程/root/eap-deployment-recovery-20261018-r2已closed，不得重放任何原入口。

归档2375461bytes/SHA256 `f7dc9436993e54253898f56d21d306ae8ebecdb816f3148d86eaa2103f21837f`，408份证据逐项核验，12份预览截图与最终归档一致。实际Secret仅远程RAM扫描，JWT/私钥模式扫描通过，无令牌、Secret对象、HAR/storage导出。没有本机编译/测试/构建，没有commit/push。 [验收](evidence/deployment-recovery-20261018/README.md)、[契约](DEPLOYMENT_SUBMISSION_RECOVERY.md)、[回执](evidence/deployment-recovery-20261018/final-receipt.json)。

sessionStorage不保证关闭会话/清存储/换设备后的自动找回；配置正文不恢复，按原键再次提交必须重新填写相同配置。NOT_FOUND可能长期未确认，不能自动换键；既有协作资源和字段可见性问题仍按审查另行推进。未做性能长稳、HA/TLS、灾备或真实收费验证，旧Runner实例须经EAP显式迁移。原Plan5/Plan6及旧28调用计划保持关闭。系统审查剩余R9/R10/R11三项P2及既有产品/生产门槛，下一步R9明确失败任务的安全回收。

### Codex 进展：R9 工作流失败回收验证中（2026-10-19）

继续主线审查R9。发现明确失败在休眠前提前返回，且“专用实例”选项没有跨聊天/实验的在途保护。当前19份应用/测试文件（835份源码输入）：新增V86和RuntimeWorkService，按物理Actor地址登记业务占用，UNKNOWN不按TTL释放；回收先持久提交屏障，原生休眠不明/进程中断继续阻止新派发。工作流先保存原业务回执，再独立记录RECLAIM；失败原因不被回收覆盖。聊天、普通/团队实验及会唤醒的profile进入保护，移除能力预检失败时不安全的直接休眠；时间线/调度显示回收状态。历史UNKNOWN保留，旧步骤不可解析或映射丢失时保守全局阻止自动回收。没有自动核销/强制解锁接口，不承诺覆盖绕过EAP的原生操作。

r1远程旧源码负向探针已复现（明确失败实际休眠0次，期望1次）。专项发现旧迁移夹具非JSON导致3错误、元数据投影漏reclaimStatus导致4断言失败；全量未启动，无发布。r2修正后在/root/eap-workflow-reclaim-20261018-r2串行验证（validation PID644178），当前仅启动验证，未确认通过/部署。禁止重放r1/r2 bootstrap/worker，先读started/status回执。后续独立MySQL、发布、真实四角色只读/合成浏览器、收尾仍待。后端当前RollingUpdate；首次启用V86必须保证旧派发进程先结束再迁移，不能让未登记占用的旧后端与新后端重叠。具体发布契约尚待整理。

证据docs/evidence/workflow-reclaim-20261018，设计docs/WORKFLOW_RUNTIME_RECLAIM.md。规范/root/eap-src仍为R7基线、现网镜像仍为R7，未同步R9到规范源码。没有本机测试构建、模型或业务工具调用、commit/push。Plan5/Plan6和旧28调用保持关闭。R9未关闭，R10/R11及既有生产门槛继续保留。

R9过程补充：r2专项123项中122通过，剩余1项为Mockito配置不能mock final Ticket，非生产行为错误。仅修正该聊天单测为真实RuntimeWorkService+隔离H2，生产输入完全相同；r3已启动，validation/release/browser PID652956/652957/652958，目录/root/eap-workflow-reclaim-20261018-r3。release先等待全量与前端，通过隔离MySQL后才构建/发布；首次后端切换暂用Recreate保证旧进程先结束，待新版本健康后恢复原RollingUpdate策略，期间短暂停机。现网只读计数：工作流43成功/5失败/4UNKNOWN，实验154成功/11取消/2失败/1UNKNOWN，团队9成功/1UNKNOWN，聊天0；历史未改。最终验收、源码同步、UID清理及导出仍待，不能宣称R9已关闭。

R9过程补充（r4）：r3专项123全通过。随后代码复查确认registerRuntime允许改写未绑定实例地址，旧UNKNOWN任务只存registry key，不能信任其当前endpoint。仅补V86不可变绑定筛选、非法步骤结构保守处理及迁移夹具；按已冻结PID/Pod UID中止r3未完成全量及release/browser等待进程，Pod UID a08b64d6-37ef-40a9-ab89-14bdf12a9f5f已删除，无发布。最终r4目录/root/eap-workflow-reclaim-20261018-r4，validation/release/browser PID669090/669091/669092，835输入/19修改文件；r4启动完整验证，尚未确认通过。MySQL5类每类独立schema，避免调度清理跨夹具。r4收尾脚本需全部通过、真实四角色只读、浏览器和人工截图检查后才同步源码/清理/闭合。禁止重放r1–r4启动入口，先读状态文件。当前现网和规范源码仍R7。继续维护所有历史UNKNOWN、预算、Plan5/Plan6及旧28调用关闭状态。

### Codex进展：R9 r4全量通过，MySQL测试日期修订后r5补验（2026-10-19）

r4专项123、全量1174（129类）、前端443/类型/构建已通过；隔离MySQL第一类6项中5通过、1条因无TTL测试把updated_at设为Unix零点而触发TIMESTAMP非法值，后续4类未执行。发布停在MySQL，没有镜像更新；browser等待器退出。根因是测试数据的数据库兼容性，未改生产逻辑。

只将RuntimeWorkIntegrationTests的旧日期改为2000-01-01，保留同一无TTL释放断言。r5核对835输入中只有这一个测试文件不同，复用r4后端全量、专项及前端结果和JAR/dist摘要；追加受影响H2类6项及完整MySQL52项，不重跑无关全量。/root/eap-workflow-reclaim-20261018-r5已启动，release PID714362、browser PID714363；此时补验和发布均未完成。r4旧失败证据保留，后续成功需另写回执，勿重放启动入口。无真实业务写入/模型调用、无本机测试构建、无commit/push。

### Codex进展：R9 r5数据库格式差异，r6补验（2026-10-19）

r5 H2 6项、MySQL占用/迁移7项通过；MySQL工作流26项中25通过、1条因JSON原文空格比较不兼容（mode字段同为audit，数据库读回冒号后多空格）失败，后2类未执行，没有发布。只把该测试改为解析后的完整JSON内容比较，不改生产逻辑。r6和r4全量基线之间仅2份测试文件有变化，生产/JAR/dist按摘要复用。

/root/eap-workflow-reclaim-20261018-r6已启动，release PID726440、browser PID726441。保留r5未变的H2 6项与MySQL7项报告，补跑受影响H2工作流26项和余下MySQL45项；组合验证预期H2 32项/MySQL52项，此时尚未完成。启动入口不得重放；原r1–r5失败/中止证据保留。没有本机测试构建、真实业务/模型调用、commit/push。

### Codex进展：R9工作流安全回收已部署并收尾（2026-10-19）

R9新增V86占用与回收屏障，工作流匹配终态失败也进入回收，业务原因/状态与reclaimStatus分开保留。聊天、普通/团队实验及会唤醒的profile登记占用，按物理地址协调；UNKNOWN不按TTL释放，RECLAIMING在RPC前持久提交，不明回收阻止新派发。去掉预检失败时不安全的直接休眠。历史仅信任不可变绑定，失去原目标的未知记录保守保护；现网旧记录含无法确认原绑定的UNKNOWN，触发全局自动回收保护；普通新派发仍允许，但自动休眠在历史核验前会被跳过。 没有自动核销或强制解锁接口，不覆盖绕过EAP的原生操作。10生产/9测试文件，无Runner/依赖/权限变更，无commit/push。

远程r4专项123/完整1174（129类）、前端443/类型/Vite通过；r5修测试日期后H2 6项/MySQL7项通过，r6只修JSON结构比较后H2 26项/MySQL45项通过。合计独立MySQL8.4.11共52（每类独立schema）、H2补验32项。复用r4全量和构建，生产源码/JAR/dist摘要保持，未重复跑整套回归。最终成功验证失败/错误/跳过0。旧源码失败回收0次/期望1的负向探针复现，修复后同一探针通过。r1非JSON历史兼容与元数据投影漏字段导致3错4失败；r2专项122通过/1夹具不能mock final Ticket；r3改真实占用服务+H2后专项123通过，补查未绑定注册可改写地址后按PID/UID中止未完成全量，未发布；r4补不可变绑定筛选及夹具后完整H2/前端通过，但MySQL第一类6项中1条因Unix零点TIMESTAMP值报错，未发布；r5修测试日期后，工作流MySQL26项中1条因JSON格式空格比较失败，未发布；r6只改完整JSON结构比较，补跑受影响H2类及余下MySQL45项，保留r5通过的7项MySQL。原失败/中止记录保留。

发布前/后/最终真实四角色PKCE及只读接口通过。浏览器4角色×1440/390共8组，合成执行/调度数据覆盖三种回收状态且不改业务失败；24截图人工查看（含8张实际手机视口补图；原4张长组件手机截图固定顶栏叠入，补查确定位置后标题和3种详情均未遮挡；原失败取样/脚本记录保留，未改应用）。pageerror/API5xx/真实业务写入0，供应商模型/业务工具调用0，无现网Actor故障注入。首次后端Recreate确保旧派发进程先结束，随后恢复原RollingUpdate；有短暂停机，不是HA发布。

backend `10.186.133.66:5001/eap-backend@sha256:1d152adf6a16ad103f40fade81c89750dfe28d3bd6cbf14d48d2347b882710d4`，Pod UID `e4549a5f-20ae-48a9-a486-fddc63d0c0c3`；frontend `10.186.133.66:5001/eap-frontend@sha256:07f810632f7724308cddbf67c8c724018828b8c0c5f83c1859a67fbc8fa866d2`，Pod UID `ef4f1eaf-72e8-4716-a051-f3c9e3d67ebb`，buildId `2026-10-18T17:02:49.853Z`。默认Runner保持`10.186.133.66:5001/eap-runner@sha256:1037b9efd93b1388e9a023368f9511504bfa8c532a5b887d494d6be6747a5482`，本轮未重测。JAR/dist/镜像层及实际入口/version核对，健康UP、两端ready1/1、重启0/0。V85→V86成功、聊天0行、新业务占用0、旧占用保留6个范围；平台预算、原业务状态计数、受保护配置及Secret身份/版本保持。12个临时Pod按UID清理，剩余0，节点无Memory/Disk/PIDPressure，可用磁盘17.70GiB。19份改动核对旧摘要后同步/root/eap-src，全部835份最终输入一致。

最终/root/eap-workflow-reclaim-20261018-r6已closed，勿重放任何旧入口。归档3242231bytes/SHA256 `ed4b784ba39133845615a5503c987ba2bb473c7518892b475f3e36e9b671c06c`，630份证据逐项核验，24预览与最终归档一致。实际Secret仅远程RAM扫描，JWT/私钥模式检查通过，无令牌、Secret对象、HAR/storage导出。没有本机测试构建、commit/push。详见[验收](evidence/workflow-reclaim-20261018/README.md)、[协议](WORKFLOW_RUNTIME_RECLAIM.md)、[回执](evidence/workflow-reclaim-20261018/final-receipt.json)。

审查剩余R10原文到期清理和R11运营信号扫描两项P2及既有生产门槛。历史UNKNOWN核对、旧Runner迁移、性能长稳、HA/TLS及灾备仍待；Plan5/Plan6与旧28调用继续关闭，不宣称系统已全无问题。


### Codex进展：当前部署全面功能视频已交付（2026-10-19）

按用户“帮我录制一个全面功能的视频”，重新录制当前已部署 EAP，未复用旧版视频画面。成片 1708.72 秒（28:28.72）、1920×1080、25 fps、H.264，无音轨；28 章、139 段烧录中文字幕，覆盖 21 个导航页面及智能体详情。沿用此前中文文字讲解、无旁白偏好；为完整展示实际约 28 分半。以 Team5 / Plan6 / Workflow30、Agent72/73/74 的历史真实结果串联创建配置、部署、协作、调度、Trace、证据覆盖与反馈，再覆盖知识、评估、单体/团队优化、治理、审计、角色和成本预算。表单只展示后取消；原请求恢复机制用字幕解释，没有制造新超时。没有替换 API 响应，没有业务写入、新模型调用或重放旧预算。

所有浏览器录制、编码、媒体校验和播放检查均在远程 PoC 进行，本机仅编辑脚本/文档、下载、哈希核验及看图，没有本机安装依赖、运行应用测试或构建。完整解码 0 错误、字幕重叠 0；桌面1440/手机390视口首中末共6次章节跳转、视频解码、章节搜索和横向布局通过。人工查看28个章节中点画面、6张关键原始画面和播放器截图，不声称逐帧人工观看。100个文本文件通过实际测试账号密码及JWT模式扫描；登录未录入，凭据仅远程RAM/stdin。修正录屏VP8全范围到H.264有限范围色阶，校准4条切页字幕，未加速画面。第17段两次脚本定位失败保留（旧状态文案、正文自动展开后按钮不存在），只续录未完成章节；成功章节没有页面错误/API5xx。

交付：[视频](../demo_recordings/eap_full_20261019/output/EAP-full-demo-20261019-1080p.mp4)、[章节播放器](../demo_recordings/eap_full_20261019/output/index.html)、[完整包](../demo_recordings/eap_full_20261019/output/EAP-full-demo-20261019-package.zip)、[范围与校验说明](../demo_recordings/eap_full_20261019/README.md)。视频41797739bytes/SHA256 `52e31b5bd96cf96f9b764b15b491919708e4baca6625820e441035eb9fd5836d`；ZIP38907954bytes/SHA256 `a4c295c873de3869f8344e5fbcf4ebaf83f09a773f42fc2d0d2a2062661351fc`。9份交付文件本地哈希一致。

远程 `/root/eap-video-20261019-r1` 已 CLOSED，原素材保留，勿重放旧脚本。唯一临时Pod `eap-video-20261019-r1` / UID `18a64027-a826-4963-b6ea-317ed2243607` 按UID条件删除。2026-10-19T01:58:49Z前后端仍为R9原Pod，ready1/1、重启0，部署spec未改，健康UP，节点Memory/Disk/PIDPressure均False，可用磁盘17.81GiB。未改业务源码，未重新跑R9系统回归，无commit/push。历史UNKNOWN、Plan5/Plan6及旧28调用关闭状态不变，R10/R11及生产门槛仍按前文待办保留。


### Codex 进展：R10/R11 已部署并收尾，完成 Kagent 调研（2026-10-19）

按用户“继续完成”推进核心缺口。新增 V87，12 生产/迁移与 6 测试/配置文件：原文期限改为服务端时间，受控批次锁定后清空密文/IV/密钥版本并保留脱敏证据；旧未来/缺失期限受服务端接收上界约束。待审批和失败评估采用持久游标、固定轮次上界、先过滤后限额，回滚不跳过记录，旧结果不覆盖更新证据。未改权限、依赖、前端或 Runner，没有 commit/push。

远程 /root/eap-retention-signals-20261019-r1：旧实现两项负向按预期失败/错误0；最终专项39/8类、完整后端1184/132类及打包、独立MySQL8.4.11共13/4类全通过，正向失败/错误/跳过0。专项包含在全量中，各MySQL类独立schema。四角色真实PKCE与只读API发布前后通过；测试JAR、镜像层和实际Pod一致。前端/Runner未改未重测，没有新增浏览器验收。事务回滚/持久批次已覆盖，未做现网进程崩溃注入。新扫描定时任务通过已认证EAP API确认部署后提交；线上密文、待审批、失败评估与擦除标记均0，不能声称已清除真实历史原文或在线追赶过积压。

backend 10.186.133.66:5001/eap-backend@sha256:9f6eb601b15da80d0c12dbacf9abc4da12898f8b79bee923fa9d9ab8a8d38eb5，Pod backend-5549bcbf5d-dxjml，UID aefecafd-63be-4b35-9cdb-51e4d1d35e19。frontend 保持 sha256:07f810632f7724308cddbf67c8c724018828b8c0c5f83c1859a67fbc8fa866d2，UID ef4f1eaf-72e8-4716-a051-f3c9e3d67ebb；Runner保持 sha256:1037b9efd93b1388e9a023368f9511504bfa8c532a5b887d494d6be6747a5482。Recreate短暂切换后恢复原RollingUpdate；最终2026-10-19T06:32:10Z健康UP、Schema87、两端重启0，原业务状态/预算/占用摘要、受保护配置及Secret身份版本保持。三个本轮临时Pod按UID条件清理，剩余0；节点无压力，可用磁盘17.90GiB。18项改动核对旧摘要后同步/root/eap-src，658份后端输入一致。

远程目录已closed，勿重放bootstrap/worker/发布/收尾。1096022bytes归档SHA256 d2fb81641cb5993ea847d41be96ba127e4c10e9662b5fdd9b777a2fb0e7e3626，354份证据本地逐项核对，实际Secret仅远程RAM扫描，JWT/私钥模式通过，无令牌、Secret对象或浏览器会话导出。[验收](evidence/retention-signals-20261019/README.md)、[契约](CONTENT_RETENTION_AND_SIGNAL_SCAN.md)、[回执](evidence/retention-signals-20261019/final-receipt.json)。

过程：首次传输脚本解析失败后修正才实际启动；冻结输入后正向回归通过。构建提前verify被未Succeeded检查挡住，无提前发布；原构建最终约16分钟完成，基础镜像53层/4078114317压缩bytes，持续IO进展有快照。相近旧构建94秒，本轮变慢的全部环境原因未确定；镜像层膨胀列交付优化。尝试延长时限仅做服务端dry-run且被拒，未改Pod时限，未重新构建。原生SQL UNIX_TIMESTAMP与应用Instant直接比较产生8小时差，使新增上线检查误失败；改用认证API通过，只修改检查脚本。SSH偶发握手失败后读取原回执，没有重复发布。

UNKNOWN只读清点4工作流/1实验试次/1团队派发、6个非DONE占用范围；缺乏完整原绑定和原终态回执，保持UNKNOWN及全局自动回收保护，普通新派发允许。原Plan5/Plan6、旧28调用继续关闭。原文在线列清理不包括物理页/binlog/备份和其他正文副本。R1–R11对应修复已完成，旧实例迁移、生产认证/TLS/HA/灾备、容量长稳、业务结果契约仍待。

用户另问“是否可以吸收Kagent的能力和展示方式”。已查官方1.x alpha文档、三张官方1.x界面图及v1.0.0-alpha14源码；[调研](research/kagent-adoption-20261019/README.md)。结论优先EAP原生任务工作台、行为模板/运行环境/固定版本关系、MCP工具绑定体验、结构化结果验收；随后有界验证Kagent作为受管后端。它1.x同用Substrate，但alpha版本与当前EAP接口兼容性未实测；内嵌子智能体共享父Actor，不能直接替换EAP独立Actor编排。创建、调度、治理、预算和观测仍归EAP。没有安装Kagent、改业务代码实现该方案或新建真实模型预算。


### Codex 进展：Kagent 展示方式首批吸收，任务工作台已部署（2026-10-19）

用户继续并再次询问“Kagent的核心能力和展示方式可以集成或者借鉴吗”。结论：展示方式直接吸收，模板/运行环境/固定版本及结构化结果契约适合EAP原生补齐；Kagent作为受管运行时仍需隔离PoC，创建、调度、治理、预算与观测归EAP。再次核对官方1.x核心概念/结构化输出文档，未安装Kagent、不承诺Substrate版本兼容或替换独立Actor协作。

本轮7生产/2测试文件，仅前端：新增/task-workspace及导航、流程执行跳转；按可见工作流/原runKey读取最近20次列表及精确历史执行，统一显示状态、步骤、受权限保护的结果、原工具核验、实例线索和执行上下文。观测按步骤taskKey精确关联7天返回明细，限制任务入口，核对详情ID/原键/类型；不按同Agent/时间或前缀关联，不伪造Trace，缺失用量不当作0。明细可能截断且不含控制节点；不是全部会话/任务历史或冻结部署版本读模型。账号/任务切换、权限撤销和卸载使迟到回包失效，查询失败不回退到无关成功记录。页面业务请求仅GET，没有执行/重放按钮。权限仍由PlatformPermission与/me驱动，复用正文能力；无后端/迁移/依赖/Runner更改，无commit/push。

所有测试、构建、浏览器和部署均在远程PoC。r1前端453/类型/Vite与四角色8组功能断言通过，但人工发现真实长工作流名使手机Grid轨道534px大于可用362px，外层overflow:hidden掩盖document.scrollWidth检查。远程DOM与临时样式诊断证实根因；仅补grid-template-columns:minmax(0,1fr)和toolbar min-width:0，r2重新453/类型/构建通过并发布。r2四角色×1440/390共8组与逐元素边界检查通过，20最终截图均人工查看。真实Workflow30原执行及结果/Trace/用量只读查看；UNKNOWN、历史编号、查无记录、迟到成功为合成GET拦截，每轮132次。pageerror/非预期5xx/业务写入/真实模型/工具调用均0。两轮不是906项独立覆盖；后端/MySQL/Go本轮未重测。

最终frontend 10.186.133.66:5001/eap-frontend@sha256:5e7aac52741cc6e6db68f42c4fcdc1719dda683eecbfc1a8109f963304a20746，buildId 2026-10-19T07:47:54.327Z，Pod frontend-dcc584447-wjgb6，UID 50682c05-7fb7-4fac-859d-413c2c9ff24c。backend保持9f6eb601…原Pod aefecafd-63be-4b35-9cdb-51e4d1d35e19，Runner保持1037b9ef…；Schema87。测试dist与镜像层、实际入口/version逐字节核对。2026-10-19T07:55:27Z健康UP、两端ready1/1/重启0，原预算/占用/执行状态摘要及受保护配置/Secret身份版本保持，节点无压力，磁盘约17.55GiB。

9项修改核对旧摘要后同步/root/eap-src，168前端输入完全匹配。5临时Pod按原UID清理、剩余0；r2复用r1原浏览器Pod完成第二轮。首次收尾脚本变量重名TypeError在源码同步后、清理前停止，更名后只继续受原摘要/UID保护的收尾，没有改应用或重复发布。首轮截图/诊断/两轮测试和发布及收尾异常均保留。

/root/eap-task-workspace-20261019-r1、-r2均已closed，以closed.json为最终状态，status.json是发布阶段快照；勿重放任何原启动/发布/浏览器/收尾入口。归档9372271bytes/SHA256 65519533d65fa25a7d7955eb429bf6dccfa372edd46e2b0af8f51cbca91ca5c1，139份证据逐项核验，21最终预览与归档一致。实际Secret仅远程RAM扫描、JWT模式检查通过，无令牌/Secret对象/HAR/storage导出。[验收](evidence/task-workspace-20261019/README.md)、[契约](TASK_WORKSPACE.md)、[回执](evidence/task-workspace-20261019/final-receipt.json)。

后续优先统一任务读模型、原证据可分页关联，再做可复用模板/运行配置/执行固定版本及结构化结果验收；持久审批恢复与检查点需真实协议支持。Kagent运行时兼容PoC未做。原Plan5/Plan6、旧28调用和历史UNKNOWN保持，未新增收费预算。性能长稳、HA/TLS/灾备、旧实例迁移等仍待，不宣称系统或全部Kagent能力已完成。

#### Codex 进展：原执行观测分页已部署并收尾（2026-10-19）

任务工作台新增原执行分页及归属校验详情，移除最近7天/100条全平台明细限制，纳入控制节点和AX原父关联；不补造历史登记，UNKNOWN与未确认用量保持。6生产/3测试文件，无Schema/权限/依赖/Runner变更。

远程后端1194/133类、专项86（包含在全量）、独立MySQL10、前端457及类型/构建全部通过。四角色API和桌面/手机8组通过，补拍时同8组复验；40张截图已检查，8张带固定导航/测试提示的局部截图由12张完整页/详情视口补拍替代，32张作为最终布局证据。无应用代码返工或追加真实模型/工具调用。

backend `sha256:7892633b8bd267eb6a72ba87bdefd4330fdeb7af18f77111e81ef7015313e6b2`；frontend `sha256:1c02e724675500e3eed17c69a931604719d40c4378253d4802a4d44083d922ac`，buildId `2026-10-19T09:25:21.322Z`。健康UP/Schema87/重启0，Runner/预算/原业务状态保持；830项输入匹配、9项同步规范源、6临时Pod按UID清理，414份证据核验。无commit/push或本机应用构建测试。

远程 `/root/eap-workflow-evidence-20261019-r1` 已closed，勿重放任何原入口。[验收](evidence/workflow-evidence-20261019/README.md)、[契约](WORKFLOW_OBSERVATIONS.md)。

下一步：推进模板/运行环境/执行固定版本关系与结构化结果验收，再扩展聊天和独立AX的统一任务视图。Kagent运行时接入仍需隔离PoC；EAP继续掌握创建、调度、权限、预算与观测。旧实例迁移、历史UNKNOWN核对、容量长稳、HA/TLS与灾备等仍待。原Plan5/Plan6及旧28调用计划保持关闭。


归档10694709bytes/SHA256`784c85a48462e2c71d2d65ab739a72660c109b73a5afc11cc57b7001c62b3b3e`，实际Secret/JWT/私钥标记扫描通过，414份证据与42份预览逐项核对。本轮源码补丁相对编辑前保存的文件生成，不是整仓库Git差异。分页状态为读取时投影，不是冻结快照；现网5条登记分3页已四角色验证，超过100条及31天历史来自隔离数据。未重测Runner、容量长稳或Kagent兼容性。后端镜像本轮约81秒构建完成，但累计镜像层问题尚未优化。

### Codex 进展：执行配置溯源已部署并收尾（2026-10-19）

任务工作台新增原提交节点版本与 Runtime 派发前配置溯源。实例、Actor UID、ActorTemplate、原部署版本和不可变镜像摘要随步骤持久保存；业务回执、未知结果和回收更新保留原值。原平台创建关系必须精确匹配，读取只用原执行快照/步骤，不按当前部署补推旧历史。6生产/4测试文件，无Schema、权限、依赖或Runner变更。

远程后端1209/134类、专项82（包含于全量）、独立MySQL15、前端463及类型/构建全部通过。四角色真实PKCE与只读API通过；四角色桌面/手机8组浏览器功能验收通过，另4组手机详情补拍定位检查。24截图已检查，4张初始手机详情截到了页面顶部，由4张定位正确的视口图替代；20张作为最终布局证据。无应用返工或追加模型/业务工具调用。

backend `sha256:8f50bb750da4840455eccf40892e94576bcd7db73763d2413fce1d87c2b46b87`；frontend `sha256:e5677dfb1b297254cf4cb823bcdc926436e898e9b05dff180cfac7b8587fe45f`，buildId `2026-10-19T11:22:49.356Z`。最终健康UP、Schema87、两端重启0，预算/原业务状态/Runner/受保护配置保持。836份输入匹配，10项变更同步规范源；6个临时Pod按UID清理，397份远程证据及28份预览内容逐项核验。无commit/push或本机应用测试构建。

远程 `/root/eap-execution-configuration-20261019-r1` 已closed，勿重放原bootstrap/worker/pipeline/发布/收尾入口。[验收](evidence/execution-configuration-20261019/README.md)、[契约](EXECUTION_CONFIGURATION.md)。

这是执行配置的原始记录，不是提交时固定全部运行版本，也不是Actor内实际加载内容的证明。AX版本、可复用行为模板、完整运行策略和结构化结果契约仍待；Kagent运行时隔离PoC未做。EAP继续掌握创建、调度、权限、预算与观测。旧实例迁移、历史UNKNOWN核对、容量长稳、HA/TLS/灾备继续保留；原Plan5/Plan6及旧28调用计划保持关闭。


收尾时刻 2026-10-19T11:42:44.710395+00:00，backend Pod `backend-7ffcbdf876-ttsfj` / UID `0a9f77be-5213-47c1-8a45-e78314b79313`；frontend Pod `frontend-79849c899d-nn5wm` / UID `e63737d1-0f1f-440f-872e-83f98faddb4b`。两端ready1/1、节点无Memory/Disk/PIDPressure，可用磁盘约16.90GiB。后端以Recreate切换并证实旧Pod已结束，随后恢复原策略。测试JAR/dist、镜像层和实际imageID一致，前端入口/version逐字节匹配。

归档6958964bytes/SHA256 `bf08fae228712f4e922bc67ac86d17ae667177c6e13abd66d9c7e4aeb10272ae`，实际Secret只在远程RAM扫描，JWT/私钥模式通过。导出status.json为关闭前快照，以closed.json/final-receipt.json为准。历史真实Workflow30只读确认节点快照及缺失绑定；新绑定持久化来自隔离H2/MySQL及真实HTTP测试替身，不声称额外真实Actor/model执行。未重测Runner、性能长稳、回滚旧后端或Kagent兼容性。

本轮后端镜像构建约419秒，约414秒在基础镜像解包；两份IO快照证明持续读写，累计镜像层问题仍待优化。初始手机详情截图使用window.scrollTo，实际应用滚动容器未移动；改截图脚本为定位版本标签滚动，未改应用。收尾包装脚本全局替换占位符误改嵌入PREVIEW文字，在解析阶段失败，改为字面量插值后通过原摘要/未启动门禁继续；没有重复部署、源码变更或业务执行。[准备记录](evidence/execution-configuration-20261019/preparation-note.json)。

### Codex 进展：结构化结果契约进入远程验证（2026-10-19）

已实现常规Runtime的EAP_JSON_V1有界JSON结构验收：配置保存/提交检查、原快照冻结、文本或对象显式来源、结构拒绝保留成功回执和原文但停止下游、独立回收状态、内容权限及表单/时间线。11生产/4测试文件，841冻结输入，无依赖/Schema/权限/Runner更改。AX和完整JSON Schema不支持，不宣称Kagent运行时接入。

r1专项117中1错：新断言用了未加载Java时间模块的普通ObjectMapper，业务断言已完成，未发布。r2仅改测试为应用ObjectMapper，专项117通过，完整后端回归中；MySQL/前端/发布/浏览器尚未完成。当前/root/eap-structured-results-20261019-r2，worker PID1936566、pipeline PID1940492，后续测试门禁后的发布/浏览器已登记，勿重放原入口。r1失败证据待并入最终归档及UID清理；以实际回执继续，不把预期覆盖当已通过。源码/前后摘要和脚本见docs/evidence/structured-results-20261019/，契约见STRUCTURED_RESULTS.md。原Plan5/Plan6/旧28调用与历史UNKNOWN保持，无真实模型/工具派发、commit/push或本机应用测试构建。

#### Codex 进展：结构化结果 MySQL 夹具修正与 r3 补验（2026-10-19）

r2完整后端1244/135类、专项117、打包通过，MySQL32中31通过/1失败；未发布。新测试将输入JSON顺序的摘要与MySQL保存排序后的摘要直接比较；按MySQL键长/字节序排序复现两个SHA256，确认生产逻辑仍在使用原保存快照。仅将测试期望改为提交时已保存的契约。r3经841输入比对仅此测试文件不同，复用r2全量报告/JAR，补验H2工作流32与MySQL32，再前端验证和发布/浏览器。当前/root/eap-structured-results-20261019-r3已启动，读pid.json/pipeline-pid.json/status.json继续，不重放入口。r1/r2失败记录保留，最终将清理三轮本任务UID资源。尚未宣称发布完成，无真实模型/工具调用、commit/push或本机应用构建测试。

#### Codex 进展：结构化结果已部署，浏览器收尾中（2026-10-20）

r3 H2补验32/MySQL32、前端471及类型/构建全部通过；复用r2完整后端1244/135类及JAR，生产代码未变。已发布backend sha256:44fae063940a35fd57cacb98b69385b599326f452d1150d1ca582eca576ccf11（Pod backend-5d9df4fdb9-9s7rv / UID ee28fc6b-a061-46ba-9c5d-5b62a271a6ea），frontend sha256:3b0e4640ea07942eb1508d62c3b2c2bfd21d3bc9d5777c82d3a02a592174e808（Pod frontend-bf44f9f68-wblq7 / UID d19c168c-9624-45a5-9ec5-bc1c7d325887），buildId 2026-10-19T15:44:47.421Z。两端健康UP/重启0，四角色只读API、预算/原业务/配置保护通过。未变Runner，无新增真实模型或工具调用。

浏览器首次2组viewer通过；operator1440停在要求整份表单同时位于视口的截图断言。桌面节点面板原本独立滚动，未改应用；保留ui-out/attempt1和本地failed-browser，续验改为分别检查编辑区与规则区，operator1440通过。随后operator390取景可见性断言失败，发现全局html scroll-behavior:smooth，100ms取样未必完成滚动；保留ui-out/attempt2、本地failed-browser-2，当前browser-continuation-2使用instant滚动但保持同一可见性断言，复用已有3组，只继续剩余5组。先读/root/eap-structured-results-20261019-r3/ui-out/ui-report.json和continuation2.log/continuation2-pid.json，不重放旧pipeline/browser入口。应用未返工，未重建/重新部署。

仍需最终截图人工检查、写release/visual-review.json、执行已暂存但未启动的contract/finish.py（按UID清理本轮三轮共9Pod、推广15文件、验证841输入、导出敏感扫描证据），再下载/核对归档并更新最终交接。root尚未closed；原r1/r2失败证据收尾合并。所有应用测试/构建/浏览器远程，无commit/push。

### Codex 进展：结构化结果契约已部署并收尾（2026-10-20）

常规 Runtime 的 AGENT/ARTIFACT 节点已支持 EAP_JSON_V1 有界 JSON 结构契约，在“工作流自动化 → 节点配置 → 结果契约”配置。契约随原提交快照冻结；原任务回执、结构验收、资源回收分别记录。结构拒绝保留原输出并停止后续节点；未知回执不重放、不改成成功。11 生产/4 测试文件，无 Schema、权限、依赖或 Runner 变更。

远程完整后端 1244 项/135 类及 JAR 复用 r2；最终 r3 只修新测试的数据库 JSON 排序期望，生产代码一致，补验受影响 H2 32 项、隔离 MySQL 32 项。前端 471 项、类型/构建、四角色真实认证/API、桌面/手机 8 组通过。28 张最终截图与 2 张失败诊断图已复核；表单取景修正只改测试脚本。原浏览器 Pod 到期后按拥有关系启动替代 Pod 续验，未再次部署。

backend `sha256:44fae063940a35fd57cacb98b69385b599326f452d1150d1ca582eca576ccf11`；frontend `sha256:3b0e4640ea07942eb1508d62c3b2c2bfd21d3bc9d5777c82d3a02a592174e808`，buildId `2026-10-19T15:44:47.421Z`。健康 UP、Schema 87、两端重启 0；预算、原业务、受保护配置及 Runner 保持。841 份冻结输入与本地/规范源一致，15 项修改推广，10 个本轮临时 Pod 按 UID 清理，798 份脱敏证据下载核验。无真实模型/业务工具调用、commit/push 或本机应用测试构建。

`/root/eap-structured-results-20261019-r3` 及 r1/r2 均已关闭。不要重放原 bootstrap、worker、pipeline、浏览器、发布或 finish 入口；归档 status 是关闭前快照，以 final-receipt.json 为准。[完整验收](evidence/structured-results-20261019/README.md)、[契约](STRUCTURED_RESULTS.md)。

这是 EAP 原生能力吸收，未安装或验证 Kagent 运行时。团队计划编辑器、AX 与独立聊天尚无结果契约入口；可复用行为模板、完整运行策略、提交时固定版本、AX 版本及持久审批/检查点仍待。下一步优先梳理可复用行为模板与既有部署版本、派发实例的责任关系，再形成有界实现；不把配置溯源当作提交时固定版本。旧实例迁移、历史 UNKNOWN、容量长稳、HA/TLS/灾备仍待，Plan5/Plan6 与旧 28 次调用继续关闭。

对上条浏览器进展的补充：continuation2 首次在原 Pod 到期门禁停止，未执行剩余用例；随后专用 browser2（UID `615c31d0-a861-4486-aec0-337d1326a13a`）续验完成。最终不是 9 个而是 10 个临时 Pod，原到期 Pod 与替代 Pod 均按各自 UID 清理。r1 的 ObjectMapper 时间模块问题和 r2 的 MySQL JSON 排序期望问题均仅在测试，原失败记录完整保留；专项 117 包含在全量中，H2 补验 32 不叠加计作新增独立覆盖。

收尾健康时间 `2026-10-19T23:41:35Z`（北京时间 10 月 20 日）；backend Pod `backend-5d9df4fdb9-9s7rv` / UID `ee28fc6b-a061-46ba-9c5d-5b62a271a6ea`，frontend Pod `frontend-bf44f9f68-wblq7` / UID `d19c168c-9624-45a5-9ec5-bc1c7d325887`。两端 ready、重启 0，节点无 Memory/Disk/PIDPressure，可用磁盘约 22.16 GiB。JAR/dist、镜像载荷与实际 imageID、入口/version 匹配；原后端进程退出后恢复原发布策略。后端构建 IO 取样到达时已完成，不能声称本轮证明了构建全过程持续 IO；累计镜像层优化仍未处理。

归档 14,815,385 字节，SHA256 `89dd38d104a90bddcb1a88f9073d403f75911045e324496e5a369c23b2dcc97c`；798 份远程文件逐项核验。实际 Secret 值仅在远程 RAM 扫描，JWT/私钥模式检查通过，未导出令牌或浏览器会话。真实 Workflow 30 只读核对；新状态/表单来自浏览器 GET 替身，新契约执行来自隔离 H2/MySQL/HTTP 替身，未追加真实 Actor/模型执行。没有重测 Runner、性能长稳、HA/TLS、灾备、真实收费或 Kagent 兼容性。


### Codex 进展：Kagent Go ADK 已接入并发布，浏览器收尾中（2026-10-20）

用户要求“继续集成啊”。本轮实际使用 Kagent alpha14 固定提交 673d181ec269 的 OpenAIModel 与 Google Go ADK Runner，作为 EAP 的可选 KAGENT_ADK 文本运行程序。EAP 保留版本发布、Actor/身份、派发入口、预算和观测；不是完整 Kagent 控制器/Harness/Session 部署。每轮独立 Session、显式历史、一次网关请求、无 SDK 重试；供应商终态先由 EAP 校验。适配上游将普通 assistant 文本转为 user 的投影，实际网关保持原历史角色。框架版本用既有 service.version 元数据；框架工具/MCP/子智能体/持久审批/检查点/实验未开放。

远程后端 1248 项（专项 105 包含其中）、前端 472 项及类型/构建通过。Kagent Runner 52 顶层/104 含子用例、原生 Runner 48 顶层/94 含子用例均 race/vet 通过；Go 模块与 sum 固定。Runner r1 首次依赖编译 OOM，r2 限制内存后定位 genai.Role 类型错误；r3 修复后全部通过，前两轮保留。所有应用测试、编译、构建和浏览器均远程；无本机依赖安装、commit/push。

已部署 backend sha256:9cf4642e7597c6ff88add3cb4b4b0b23de4240a43dd989ed044a7e7b10a8b1e7，frontend sha256:c4b13c9dcf18e0a75067fc8ffc85bf48a466e0c5ffddea1ce797021de02fa58e，可选 eap-kagent-runner sha256:e215b3f4994b0a4bad767b2f77a4700a1264fd28a9673e7cd2efe4e2633b3ac7。原生 Runner 1037b9ef…保持；Schema87；载荷/实际 imageID 与健康检查通过。

EAP 创建 Agent75、v1 dep-5cd871b8-e57e-49f1-927c-1ee342db3ac3、实例 eap-agents/a75-v1-5cd871b8e57e，完成自动身份配送、健康命令和休眠，最终 SUSPENDED/REVOKED。健康命令读到休眠状态，不当作文本任务执行证明。模型执行为真实 SDK + 合成 HTTP 网关验证；新增收费模型调用0。原 Plan5/Plan6/旧28调用、历史 UNKNOWN 保持。

当前远程 /root/eap-kagent-adk-20261020-r1 未关闭。原 worker/pipeline 已完成发布及实机验收，pipeline 在浏览器 operator1440 框架选择超时停止；viewer 两组通过。首轮截图显示固定导航遮挡自动滚动后的选择区。仅改浏览器使用可见 wrapper 和 instant 滚动，原 browser Pod UID 5d8d0cdf-6ae2-4174-af74-a7ead5c73184 上 continuation PID2600875 续验四角色八组；不要重放原 pipeline、actor-proof、bootstrap 或部署入口。读 browser-continuation.log、ui-out/ui-report.json、release 回执继续。

待：最终截图复核；写 release/visual-review.json；执行尚未启动的 contract/finish.py，推广已验证源码、按本轮 UID 清理临时 Pod、敏感扫描导出；下载核验并更新最终交接。合同与源码补丁在 docs/evidence/kagent-adk-20261020，能力边界见 KAGENT_RUNTIME_INTEGRATION.md。

### Codex 进展：Kagent Go ADK 文本 Runner 已部署并收尾（2026-10-20）

本节取代上一条未关闭的进展。真实 Kagent `v1.0.0-alpha14` 固定提交 `673d181ec26960f5ab0ddfe7a7c58f07433d1014` 的模型适配器与 Google Go ADK Runner 已作为可选 KAGENT_ADK 接入。入口“智能体详情 → 部署与运行 → 零代码配置 → 运行框架”。EAP掌握创建、版本、Actor身份、调度入口、网关准入、预算与观测；原生Runner仍可选且镜像未变。每轮独立Session、显式历史、一次模型请求、SDK无重试；上游历史角色投影经校验后仍转发原始角色，供应商终态在SDK归一化前严格检查。框架工具/MCP/子智能体/持久审批/检查点/压缩/实验均未开放，完整Kagent控制器未部署。

全部应用测试与构建在远程：完整后端1248项/135类、专项105（包含于全量）、前端472及类型/生产构建通过；Kagent Runner 52顶层/104含子用例、原生Runner 48顶层/94含子用例均race/vet通过，Kagent静态二进制构建通过。真实SDK与合成HTTP网关验证历史角色/内容、原身份与任务关联、单次调用、无重试、终态拒绝、取消及任务隔离。未重复隔离MySQL套件；本轮无Schema变更，实际部署配置持久保存/读取通过现网EAP/MySQL验收。

真实EAP创建Agent75（EAP-KAGENT-ADK-20261020），v1部署 `dep-5cd871b8-e57e-49f1-927c-1ee342db3ac3`，实例 `eap-agents/a75-v1-5cd871b8e57e`。发布READY、自动专属身份配送/握手、原生健康及休眠命令ACKED；最终SUSPENDED/REVOKED，模板/实例/记录保留。健康命令返回休眠状态，不作为实际文本推理证明。未派发生产模型任务，Agent75模型调用事件0；新增收费模型调用0。历史执行、预算/预留、运行领取摘要及受保护配置保持，原Plan5/Plan6、旧28调用及历史UNKNOWN未重开。

最终浏览器四角色×1440/390共8组通过，16张最终截图人工复核；真实认证与能力校验通过，operator/admin可编辑，viewer/governance禁用；Kagent不可用状态使用4次合成GET验证，业务写入/pageerror/非预期5xx均0。首轮框架选择因固定导航遮挡自动滚动后的控件超时。第一次续验修正取景后，在合成能力响应的route.fetch分支中止：导出守卫在写入前发现临时子进程stdout/stderr的JWT模式，原始输出仅在RAM中检查，没有写入文件或导出；具体传输错误未保存，不能据此宣称确定根因。第二次续验用先前实际认证读取的能力数据生成GET替身，最终八组通过。应用未改、未再次发布；首轮两组不重复计数。r1编译OOM、r2 genai.Role类型错误及修正、浏览器原失败均保留。

最终健康时间 `2026-10-20T03:00:17.571121+00:00`（北京时间11:00），UP、Schema87、两端重启0；两节点无Memory/Disk/PIDPressure，可用磁盘21,027,483,648字节（约19.58GiB）。backend `sha256:9cf4642e7597c6ff88add3cb4b4b0b23de4240a43dd989ed044a7e7b10a8b1e7`，Pod `backend-665469866c-w6m72` / UID `8bf7556a-fff6-4536-971f-5ab0a02dc5f2`；frontend `sha256:c4b13c9dcf18e0a75067fc8ffc85bf48a466e0c5ffddea1ce797021de02fa58e`，Pod `frontend-7b5fcd75dc-tzd9q` / UID `c30b07d7-d2cc-4895-9281-6d9c1c23f831`，buildId `2026-10-20T02:23:16.981Z`。可选Kagent Runner `sha256:e215b3f4994b0a4bad767b2f77a4700a1264fd28a9673e7cd2efe4e2633b3ac7`；原生Runner `sha256:1037b9efd93b1388e9a023368f9511504bfa8c532a5b887d494d6be6747a5482`保持。JAR/dist/Runner载荷与镜像逐字节核对，实际imageID、入口/version匹配；原后端退出后恢复原发布策略。后端构建约253秒，约250秒为基础镜像解包，I/O取样到达时已完成，不作为持续I/O证明。

15项代码/依赖变更已同步规范源，864项输入与本地/规范源一致，9个临时Pod按精确UID清理。远程 `/root/eap-kagent-adk-20261020-r1` 已closed，**不得重放bootstrap、worker、pipeline、发布、actor-proof、浏览器或finish入口**。历史pipeline失败和关闭前status保留，以[最终回执](evidence/kagent-adk-20261020/final-receipt.json)为准。归档6,331,044字节，SHA256 `2dd2fd0caa40e6d0764995d8b1b2867e9f6f9486f3f79a53d156b039bd0f0366`；476份文件逐项核验，18份预览内容与归档匹配。Secret值仅在远程RAM检查，敏感扫描通过；无密钥/令牌导出、commit/push或本机应用测试构建。

后续优先有界框架工具调用：冻结工具声明、EAP授权与效果编号、每轮预算及取消/UNKNOWN行为，独立批次验证后再扩展MCP/子智能体，不能直接解除当前单次请求保护。真实模型端到端任务、持久审批/检查点、完整控制器兼容性、容量长稳、HA/TLS与灾备仍未验证。详见[集成范围](KAGENT_RUNTIME_INTEGRATION.md)和[完整验收](evidence/kagent-adk-20261020/README.md)。

### Codex 进展：Kagent 有界工具与 MCP 被动观测验证进行中（2026-10-20）

用户要求继续集成，并询问无侵入观测/eBPF及工具与MCP追踪。当前两个独立批次都未关闭，不得重放启动入口：

1. `/root/eap-kagent-tools-20261020-r1`：`CALCULATE_SUM_V1` 可选工具模式已实现，冻结声明、实际ADK回调、EAP check/claim/receipt、持久调用意图与UNKNOWN屏障；最多一次模型请求/一次只读求和，没有工具后的模型循环。Kagent最终r2竞态56顶层/125含子用例，原生48/94均通过；后端专项107通过，全量仍在运行。worker PID2630275、pipeline PID2638498 已登记，pipeline 等验证后才构建发布和真实EAP生命周期/浏览器。先读status与release回执，不重启流水线。`contract/finish.py` 已准备并上传，但未执行；最终截图、源码推广、按UID清理和归档仍待。证据 `docs/evidence/kagent-tools-20261020`。旧文本批次 `/root/eap-kagent-adk-20261020-r1` 已关闭，继续禁止重放。
2. `/root/eap-mcp-observer-20261020-r2`：独立stdio包装器，不改服务业务代码但需要改启动配置；不含eBPF或网络导出。r1九项测试通过后发现旧EAP把OTLP UNSET记为成功，故新增 `MCP_PROTOCOL_V1` 明确结果合同、MCP专用投影、固定枚举白名单和UNKNOWN筛选。r2 Python10项通过，真实子进程生成三种结果，fixture SHA256 f034d237018e3ad8fa698c3f386d806c257c0b3dc4a311f8f69fb5d89ae4a858；后端验证等待前一批后端结束，worker PID2660038。尚未部署或接收生产流量。证据 `docs/evidence/mcp-observer-20261020`，说明 `docs/MCP_OBSERVATION.md`。

本地已包含后续MCP改动（2既有后端文件、3新增Java文件、1远程生成JSON夹具、4Python文件），故不能把本地工作树整体称作Kagent冻结输入；Kagent的866项清单与远程待发布源单独冻结，MCP增量另有before和r2-source-manifest。所有测试构建远程；无真实收费模型/业务工具调用，无commit/push。Plan5/Plan6、旧28次调用及历史UNKNOWN继续关闭。

进行中补充：Kagent后端完整1250项/135类零失败/错误/跳过，前端测试与生产构建已结束，状态进入RUNNER_IMAGE_BUILD。MCP r2后端测试已启动，真实入库四角色类已运行，尚未拿到整体验收回执。MCP独立backend-only发布/live-proof/finish脚本已准备并上传，但**未启动发布**；必须等Kagent原批次closed、MCP test-proof通过后再执行deploy-worker.py。后续MCP只新增合成观测元数据，不调用模型或生产工具；live-proof会通过EAP新建专用Agent并提交3个合成Span，原历史/预算保护。MCP没有自动导出或eBPF。

进行中补充：Kagent已部署 backend043baeee… / frontendfaba24db… / KagentRunner1fb2b019…，Agent76完成EAP创建/发布/身份配送与健康/休眠、最终SUSPENDED/REVOKED，模型0；浏览器正在运行，未收尾。MCP后端86项/11类及打包通过。另在同目录runtime-r3独立补测解析器内容/参数类型，Python最终11项通过；r3子进程夹具与r2 Java输入除随机ID/时间外语义一致，Java源码/JAR未变。MCP发布及finish契约已按旧摘要更新，要求r3通过，仍未启动。最终MCP增量清单docs/evidence/mcp-observer-20261020/final-source-manifest.json共10项；runtime-r3在finish按来源映射推广，勿覆盖原r2冻结输入。

### Codex 进展：Kagent有界工具已部署并收尾，MCP独立发布中（2026-10-20）

Kagent原批次现已closed，不得重放任何启动/发布/actor/浏览器/finish入口。最终后端1250/135类（专项107包含其中）、前端473/类型/构建、Kagent Runner56/125与原生48/94 race/vet通过；四角色8组浏览器、16截图人工复核通过。Agent76 v1 dep-b1b1153c-28ed-4a26-a37c-c2dbdb8a967c，实例eap-agents/a76-v1-b1b1153c28ed，READY/身份配送/健康与休眠ACKED，最终SUSPENDED/REVOKED，模型0；未验证真实模型工具任务。

收尾2026-10-20T04:10:19.887574+00:00，UP/Schema87/两端重启0、两节点无压力；backend043baeee…、frontendfaba24db…（buildId2026-10-20T03:56:58.970Z）、KagentRunner1fb2b019…，原生Runner保持。12项源推广，866冻结输入，8个临时Pod按UID清理；440远程文件/18预览核验。归档4446185字节、SHA256 8f385af7b101678586759718aaa060dab9db886f96a2e0fa9531ebeacc57ff64。无真实模型、commit/push或本机应用测试构建。完整证据docs/evidence/kagent-tools-20261020/README.md。最新源码本地另有独立MCP增量，不能把整个工作区称作此Kagent版本。

MCP `/root/eap-mcp-observer-20261020-r2` 现已启动独立backend-only发布，release PID2697146；先读status/release-worker.log和回执，不重放deploy-worker。前置86后端/11类与打包、最终Python11项已通过，源码与JAR冻结。待镜像/部署、live-proof合成Agent与3Span/4角色查询、finish清理/归档；此刻尚未确认发布或live通过。runtime-r3只是解析器补验子目录，不是另一个部署批次。

### Codex 进展：MCP合同接收端已部署并收尾（2026-10-20）

本节取代前述MCP发布中状态。stdio包装器保留原字节、配置名称白名单、类型化ID/并发/分片关联，断连/取消/非法或不明结果保留UNKNOWN，默认不记录参数/结果/请求ID。`MCP_PROTOCOL_V1`后端明确解析、固定枚举投影及UNKNOWN筛选已部署；旧无合同OTLP行为保持，不回写旧历史。被动OTLP记录不授予执行权，也不结算预算或证明效果。

最终Python11项通过（4个实际协议子进程场景+1退出回收场景），后端专项86项/11类及打包通过（含19新增用例）；Java未改动的1250全量基线取自前一批，未重跑独立MySQL全套。runtime-r3加强结果/参数类型，实际新夹具与r2 Java输入除随机ID/时间外语义一致，复用原Java/JAR测试；不合并重复计数。

EAP创建Agent77「MCP协议观测合成验收」，现有采集机器凭证只在RAM使用；3个合成Span/3个OTLP工具记录，SUCCEEDED/FAILED/UNKNOWN各1，Trace aa0176a2b5b749938bf932d17230d398。四角色真实认证与两个分页接口、UNKNOWN筛选通过；实际MySQL存取通过API核验。effect=UNVERIFIED、task_correlation=UNAVAILABLE；没有真实任务、Runtime专属凭证导出或生产MCP流量。模型/业务工具效果0，原观测行摘要、业务/预算/配置保持。没有HTTP/SSE、eBPF或自动网络导出，未自动注入Actor或Kagent。

最新backend `10.186.133.66:5001/eap-backend@sha256:4b6df1e277a86064a2879ff254905e43b4cb1ed2c174d2d283e6c6e3442e6093`，Pod `backend-67db5c8ffb-bjxsw` / UID `44550bec-a141-455f-a204-454d1ce14b7c`；frontend仍faba24db…，原生/KagentRunner均保持前一批。最终2026-10-20T04:15:38.818426+00:00，UP/Schema87/两端重启0，可用磁盘20413579264字节。10项增量推广，874份完整输入与本地/规范源一致，2个本轮Pod按UID清理。归档172567字节、SHA256 cbb6b6a489b7ab154d84adc9b5ddcab7124bf63d1024b7216476ce5766265089，89文件核验；Secret在远程RAM扫描、JWT/私钥检查通过。证据docs/evidence/mcp-observer-20261020/README.md。

`/root/eap-mcp-observer-20261020-r2`已closed，禁止重放启动/发布/live-proof/finish；r1历史原型和r2/runtime-r3补验目录都不作为新部署入口。Agent77及合成观测保留，不为截图重复提交。Kagent工具批次同样已关闭，前一文本批次继续关闭。所有应用测试构建部署远程，无commit/push。下一步先做实例凭证导出与启动配置，再HTTP/SSE及eBPF/gVisor对照；真实模型工具任务、多轮框架/MCP执行、子智能体、持久恢复、性能长稳、HA/TLS/灾备未完成。Plan5/Plan6、旧28调用和历史UNKNOWN保持。


### Codex 进展：MCP 实例凭证导出与可信宿主接口已完成合成验证（2026-10-20）

继续推进无业务代码修改的 MCP 观测。本轮增加 Runtime 网络导出模块、匿名管道租约交接和宿主启动 API；`relay.py` 可只导出网络元数据，不必落事件文件。凭证不进命令行/环境/文件，MCP 子进程关闭额外 FD 并去掉 EAP_* 环境。实例/Agent/Actor UID 在进程生命周期内固定，续期序号只递增；非法更新、换身份、EOF、过期使租约失效。服务端仍负责真正的凭证认证和 Trace 原实例归属，配置中的 CONFIGURED 不等于已认证任务。

单事件最多一次固定 Runtime 路径 HTTP 请求；默认 HTTPS，PoC 内网 HTTP 显式开放，无重定向/环境代理/自动重试。默认网络队列64项，每项8KiB；区分accepted/rejected/unknown/unavailable/queueDropped/shutdownDropped。401/403停用对应租约，旧请求不能停用新配送。明确回执才记为已接收，不证明工具效果。关闭最多等队列2秒后在途记未知、余项记丢弃；网络超时不声称绝对DNS截止，workerStopped报告残余daemon线程。无磁盘补投、崩溃恢复或跨任务共享长连接关联。

远程 `/root/eap-mcp-exporter-20261020-r1` 初轮25项通过；r2补充管道原子写限制、真实代理环境拒用、在途401/续期竞态和失败/断连三态网络载荷，最终27项全部通过（11原有+16新增，初轮包含其中）。真实子进程与本机环回HTTP替身，凭证运行时合成；未使用真实Runtime租约，没有现网认证端到端或Actor自动注入。前一批Agent77的机器采集凭证入库证据仍是独立证明，不能合并夸大。本轮未重跑后端/前端测试，其代码和镜像未变。

4项代码增量推广，完整877项规范源摘要核验；健康UP，backend仍4b6df1e2…、frontend仍faba24db…；没有Actor/Agent/临时Pod创建或收费模型/生产工具调用。批次已closed，r2只是同批补验子目录，不得重放bootstrap/refine/worker/finish；下一批应独立冻结。归档122226字节，SHA256 e27671f3efe2c71bb3adbfdb4efa80ef43bc0321d383008785f5bcec0706747a，31文件；证据 `docs/evidence/mcp-exporter-20261020`。无密钥落盘、commit/push或本机应用编译测试构建。

下一步将 `launch.start_observed_server` 的语义接到现有Go Runner凭证安装/续期及任务启动回调，完成新EAP受管Actor的真实创建/调度/观测/撤销验收；尚未实现此自动交接，不能仅把Python文件打入镜像便宣布完成。随后再做HTTP/SSE与固定OBI版本普通容器/gVisor对照。Kagent多轮/MCP执行、子智能体、持久审批/恢复、容量长稳与HA/TLS/灾备仍待；原Plan5/Plan6、旧28调用及历史UNKNOWN未重开。


### Codex 进展：MCP 实例身份与原任务观测已接通并收尾（2026-10-20）

用户要求“继续完善”。本轮新增 Go Runner 的可信 MCP 子进程适配 `mcp_session.go`，自动身份安装/续期后更新管道租约；固定原实例/Agent/Actor UID，拒绝缺失、过期或投影身份不匹配；会话、管道写入与摘要有界，取消终止进程组。新增 `mcpfixture && !kagent` 专用任务入口及合成服务，禁止模型调用，持久UNKNOWN意图和原回执复用，原生/Kagent默认行为保持。5生产/2测试文件；另更新接入/验收说明。FD与EAP环境变量清理不构成同UID/root恶意子进程隔离，只接受可信镜像内服务。

应用测试构建均在远程：原生Runner53顶层/99含子用例、MCP构建专项12/12、Kagent61/130，race/vet均通过；Python27通过。各构建条件和专项存在重叠，不叠加为独立覆盖。测试Pod Python3.14.8；实际专用镜像Python3.12.15验证真实合成链路，未在其上重跑完整Python套件。运行中身份续期、拒绝原UID变化、满管道和取消来自远程替身集成测试；真实Actor验证派发前1→2轮换。

通过EAP创建Agent78、部署v1 `dep-1dee31db-1477-4a30-bbe6-08563e8d62f6`，实例 `eap-agents/a78-v1-1dee31db1477` / UID `83dab373-cefb-41ae-9944-cfeb83bb9113`。专用镜像 `10.186.133.66:5001/eap-mcp-observer-fixture@sha256:5bfd420bbe4929fea2af8d66cf0cdb57b665f8c4ec21f3bc2506403148560ccc`，二进制与4个Python载荷逐项核对。默认原生/Kagent、backend4b6df1e2…和frontendfaba24db…均未更换。

Workflow31原Run `wf-exec-bc80fff5-5857-460c-bb94-1fede5ac34fb` 的`:1`任务SUCCEEDED。Trace `6063d564562b4c469d96b428d6f68da8`有3个MCP Span/3个被动工具记录与1个真实Agent父Span，四角色各4Span/3工具/1UNKNOWN查询通过。协议结果分别成功/失败/断连未知，效果全部UNVERIFIED、关联标记CONFIGURED，没有采集参数/结果正文或收费模型调用。

**整条工作流保持FAILED。** 自动回收SKIPPED_BUSY，诊断确认现存全局LEGACY/UNKNOWN占用阻止发送休眠RPC；本Actor唯一WORKFLOW占用已DONE。通过investigate流程确认根因后未修改保护代码、核销旧占用或重跑任务。随后经EAP显式PAUSE此新实例、撤销身份，最终SUSPENDED/REVOKED；手动收尾不改写自动回收结果或原工作流，END未派发。

验收脚本的初次SQL全行摘要UTF8解码在业务写前失败，续验改为原始字节摘要；只读page=0失败改page=1；初始上传占位符修正亦发生在执行前。原失败脚本/回执保留。导出扫描的两份配送公钥仅从副本移除；两份未改动基线测试的固定假凭证按精确文件和字面量摘要核验，实际Secret仅在远程RAM扫描，未导出真实令牌/私钥/密码。不称首次全绿。

7应用文件同步远程规范源，882份输入与本地一致；2个临时Pod按精确UID清理，剩余0。最终健康UP、Schema87、两端ready/重启0，节点无Memory/Disk/PIDPressure，可用磁盘约19.0GiB。原Span/工具/工作流、预算/模型事件、应用配置和全局UNKNOWN行摘要保持。130份远程证据下载逐项校验，归档235915字节，SHA256 `2bae1d991530f0f0ba21fd58cb9cb5380a48fd328e7edae80c12fdcf1021bc94`。

远程 `/root/eap-mcp-actor-20261020-r1` 已closed，禁止重放bootstrap、worker、build、live、continuation、finish等任何原写入口；以[最终回执](evidence/mcp-actor-20261020/final-receipt.json)为准，归档是关闭前快照。[完整验收](evidence/mcp-actor-20261020/README.md)、[MCP边界](MCP_OBSERVATION.md)。本机仅编辑/静态检查/摘要核对，没有应用测试构建、commit/push。

下一步：通用可信MCP配置、按执行契约完善观测分母（当前普通Runtime统一期望1模型事件，不能把无模型任务误报为漏采或随意取消分母）、Actor重建/原身份与旧任务隔离，随后HTTP/SSE与固定OBI版本在普通容器/gVisor的可见率和开销。尚无通用UI、既有Actor自动注入、Kagent MCP执行、多任务共享连接或持久恢复；历史占用须单独查证，不能清表或按TTL释放。Plan5/Plan6、旧28调用、历史UNKNOWN及已关闭批次保持关闭。


### Codex 进展：模型观测约定已部署、真实新任务与四角色界面验收已收尾（2026-10-20）

用户要求“继续”。针对上一轮无模型MCP任务仍登记预期1模型的缺口，实现提交时冻结的 `EAP_OBSERVATION_V1`：普通Runtime绑定Agent的AGENT/ARTIFACT节点可声明ONE或NONE，缺省保留1。后端严格校验版本、字段、重复JSON、尾随内容及适用范围，保存和执行准入两处校验；预期值随原提交事务登记。旧requestKey返回原执行，编辑当前草稿不改旧分母。前端按 `/me.runWorkflows` 控制编辑，新增模型调用观测选项，非法/不兼容声明须显式修正，未保存不能真实执行。8生产/4测试文件；无Schema、权限、依赖和默认Runner变化。

原代码负向测试已真实复现expected0/actual1；修复后专项59项/4类及全量1277项/138类通过，失败/错误/跳过均0。全量包含上一轮MCP新增19项/2类与本轮新增8项，不能把差额全部算本轮新增。隔离loopback MySQL15项、前端479项及类型/生产构建通过；Node日志pass479、fail/cancelled/skipped/todo均0。新增覆盖无模型仍需AgentSpan/身份/回执、意外模型、缺省模型缺失、旧提交不变、新提交读取新声明、非法保存/准入无派发、权限撤销及切换节点。不重跑未改动Go/Python套件；固定MCP合成镜像沿用前批。

通过EAP创建Agent79、发布 `eap-agents/a79-v1-1c07eff97aa1`，Actor UID `60429069-21d9-48c6-992d-a5686eceeb44`，自动身份配送后仅提交一次Workflow33。Run `wf-exec-80c6792b-4e8b-453c-ad7b-94f46e6b4eab` / Task后缀`:1`，Trace `9c7234b3c3541e79f8aba216f43709a0`、operation29。任务和工作流均SUCCEEDED，coverage COMPLETE，模型实收/预期0/0、Agent1/1；3个MCP Span与3工具记录成功/失败/UNKNOWN各1，四角色查询一致。业务效果仍未验证，未调用收费模型或生产工具。

该新任务明确 `suspendAfterTask:false`，完成后经EAP显式PAUSE ACKED、SUSPENDED，再撤销为REVOKED；没有测试自动回收。上一轮Workflow31的FAILED、MISSING_MODEL、预期1及SKIPPED_BUSY原样保持，全局LEGACY/UNKNOWN未解除；没有重放旧任务或用手动收尾改写自动回收。另建Workflow32仅保存声明/验证权限，绑定Agent78但没有执行或唤醒。四角色API读200，viewer/governance写403，operator/admin写200；非法声明400且原版本不变。

真实PKCE浏览器四角色×1440/390共8组通过，9张截图逐张复核；NONE持久显示、未保存禁止执行、非法JSON禁止保存、只读能力与实际0/0任务行均核对。浏览器无应用写请求、页面错误或服务端5xx，凭证仅经RAM/stdin，不导出会话。16份预览内容和最终460份远程证据均校验；截图没有触发重复任务。

最终backend `10.186.133.66:5001/eap-backend@sha256:1949131b28608b28bd7df2fd5300e3d33140cd3d43cfafc75708b87b04b703f0`，Pod `backend-bb7dc7849-8czjc` / UID `aca69925-7e84-48c5-a243-f5392efd95be`；frontend `10.186.133.66:5001/eap-frontend@sha256:3c9838cb14aa144b79bb8253a302fa00ac8b32538c9e01c5554194a0e3312d39`，Pod `frontend-86d88876c7-4s44k` / UID `168d2204-bf90-4e29-a8db-a80f4ef788e1`，buildId `2026-10-20T06:43:23.689Z`。两端镜像载荷与已测试JAR/71前端文件一致，backend采用Recreate切换并确认旧Pod退出后恢复原策略，默认原生1037b9ef…和Kagent1fb2b019…均保持。

收尾 `2026-10-20T06:57:18.571724+00:00`，UP/Schema87/两端ready且重启0，节点无Memory/Disk/PIDPressure，可用磁盘19505123328字节（约18.2GiB）。12项应用变更推广至规范源，887份完整输入与本地/规范源一致；4测试+2构建+1浏览器共7个临时Pod按UID清理，剩余0。旧Span/工具/工作流/观测登记与原运行占用行、完整模型事件和预算账本及受保护配置摘要保持。

归档2319363字节、SHA256 `4aed4d308e711dfcd201597d5f92ad1b87db40ef1e845e105dfcee7aa4147181`，460文件逐项核验。配送回执中的1处公钥只在导出副本移除，原回执远程保留；实际Secret值只在远程RAM对比，JWT/Runtime令牌/私钥模式扫描通过，无真实密钥落盘。远程 `/root/eap-observation-contract-20261020-r1` 已closed，所有bootstrap/worker/pipeline/发布/API/live/浏览器/finish写入口禁止重放；归档status是关闭前快照，以[最终回执](evidence/observation-contract-20261020/final-receipt.json)为准。[完整验收](evidence/observation-contract-20261020/README.md)、[使用合同](WORKFLOW_OBSERVATION_CONTRACT.md)。本机仅编辑/静态检查/摘要验证，无应用编译测试构建、git commit/push。

下一步是通用可信MCP服务配置及Actor重建/旧任务身份隔离，再独立做HTTP/SSE和固定OBI版本普通容器/gVisor可见率/开销；尚无通用MCP配置UI、既有Actor自动注入、Kagent MCP执行、多任务共享长连接或持久恢复，不宣称完整Kagent集成。历史全局占用独立查证，不清表、不按TTL解除；Plan5/Plan6、旧28调用、历史UNKNOWN及已关闭批次全部保持关闭。


### Codex 进展：可信 MCP 镜像清单已验证并收尾（2026-10-20）

用户要求“继续”。新增镜像内 `/opt/eap-mcp/services.json` / `EAP_MCP_SERVICES_V1`，严格限制JSON字段、重复/大小写/null/UTF8/大小/深度、服务/工具/argv数量及可执行路径；普通文件读取、拒绝符号链接、缺省关闭、非法停止启动。启动时冻结原字节摘要，返回副本；可信宿主按注册名称调用既有实例凭证/续期/取消链路。新增认证GET `/eap/mcp/profile`，仅返回服务/工具/摘要；样例入口使用注册名并在持久回执附摘要。4生产/2测试文件，未改权限、Schema、前后端、依赖或默认Runner。工具表仅是观测名称白名单，不能视作执行授权、恶意同UID隔离或Kagent MCP接入。

远程原生58顶层/133含子用例、MCP专项17/46、Kagent66/164 race/vet及Python27全部通过，无失败/跳过，构建间共享用例不累加。新增5项顶层覆盖严格解析、冻结/副本隔离、非法文件、凭证轮换/过期、两服务真实子进程归属；原生命周期/背压/续期继续通过。未重跑未变前后端/MySQL/浏览器；四角色使用真实认证API。最终镜像验证合成链路，没有在该镜像内重跑完整Python套件。

专用镜像 `10.186.133.66:5001/eap-mcp-observer-fixture@sha256:4ca1c65771f1107d0f1dcb3e490fc068324339862ef6644f93a27b1052d581e9`，二进制+4Python+实际services.json载荷逐项核验。EAP创建Agent80、部署v1 `dep-d419b0ed-0b2e-41fb-82b0-4284a66741ab`，实例 `eap-agents/a80-v1-d419b0ed0b2e` / UID `01ee2067-db4b-4cff-b482-e5bc515c5f4b`。Workflow34仅一次原Run `wf-exec-264b58ec-1ce1-44de-9c56-f460cee2d059`，`:1`任务及整条工作流SUCCEEDED。Trace `0bb4bd0583feb201a1f60c7a6e6aca60` / operation32模型0/0、Agent1/1、COMPLETE；3个MCP Span/3工具记录成功/失败/UNKNOWN各1，四角色一致，效果UNVERIFIED。清单摘要98ba2ab5…与原持久输出匹配。显式suspendAfterTask:false，完成后EAP PAUSE ACKED并撤销，最终SUSPENDED/REVOKED，不证明自动回收。

原验收脚本错误地从执行元数据取业务output，断言失败；依据代码与实际output:null/outputSha256定位为元数据脱敏设计，沿用investigate仅修正验收读取，用原Run受保护outputs接口核对输出摘要及清单摘要并继续收尾，无重派/新任务或业务代码修补。导出扫描首次命中序列化部署回执中的1处配送公钥，仅从导出副本移除；原回执保留。实际Secret值只在远程RAM扫描，令牌/私钥模式检查通过。不称首次全绿。

6项受测源码推广，890份完整应用输入与本地/规范源匹配；2个临时Pod按UID清理。最终2026-10-20T07:50:31.411748+00:00，UP/Schema87/两端ready重启0、节点无压力，可用磁盘20059086848字节。backend1949131b…、frontend3c9838cb…和默认原生/Kagent镜像保持。旧观测、工作流、模型/预算和全局LEGACY/UNKNOWN行摘要不变，收费模型/生产工具0。92份脱敏证据下载逐项核验，归档178306字节、SHA256 f0435dd731ec8c9d72d8941afd61698a06d645f68aa310df3ca452debe8ae0ae。

远程 `/root/eap-mcp-config-20261020-r1` 已closed，禁止重放bootstrap/runner/build/live/continue/finish等原写入口；归档是关闭前快照，以[最终回执](evidence/mcp-config-20261020/final-receipt.json)为准。[完整记录](evidence/mcp-config-20261020/README.md)、[配置合同](MCP_SERVICE_MANIFEST.md)。本机仅编辑、静态/摘要核验；无应用测试构建、commit/push。

下一步独立验证Actor重建/旧任务身份隔离，再HTTP/SSE与固定OBI普通容器/gVisor可见率和开销。尚无通用配置UI、既有Actor自动注入、Kagent MCP执行、共享多任务连接或持久恢复；生产服务接入与恶意子进程隔离另行设计。历史占用不得按TTL释放或清表，Plan5/Plan6、旧28调用及全部已关闭批次保持。


### Codex 进展：MCP 替代实例与原历史隔离已完成有界验收（2026-10-20）

用户要求“继续”。核对现有UID绑定、创建墓碑与工作流幂等后，本轮没有修改生产代码；新增/更新3份测试文件（1后端、2Go），补足5项顶层测试。覆盖新Runner进程状态不能复用旧RSA配送包/nonce、重新配送并轮换后拒绝旧凭证，同UID私有持久UNKNOWN日志返回原意图且不启动MCP；当前原生UID不匹配时阻止MCP入库/派发，撤销后替代实例不能借旧实验Trace，同一合成MCP原始Trace/Span在不同实例命名空间下不覆盖原观测。

全部应用测试远程：原生59顶层/136含子用例、MCP专项19/50、Kagent67/167 race/vet，Python27通过。后端6类58项初轮有1个测试初始化NPE：when(any())重设Mockito已有answer触发旧回调并传入null，改为doAnswer后在独立r2 Pod重跑58项全部通过，失败/错误/跳过0。原失败源码和报告保留，不称首次全绿、不叠加Go共享用例或两轮后端。未重跑未改动后端完整套件、独立MySQL、前端/浏览器；四角色现网用真实认证API。r2是同批测试修正目录，不是新业务批次。

固定复用MCP镜像 `10.186.133.66:5001/eap-mcp-observer-fixture@sha256:4ca1c65771f1107d0f1dcb3e490fc068324339862ef6644f93a27b1052d581e9`，重新核对manifest/config digest及二进制、4Python、services.json共6载荷。EAP创建Agent81 / Workflow35，首实例 `eap-agents/a81-v1-69469a73d802` / UID `1b648074-f6a0-439e-bd64-e420ea8f1c88`，Run `wf-exec-3119852a-fe31-4f3c-b010-4538f0a7e51f` / 原任务`:1`，Trace `ab596ad7bfc4af90cda7f1c302871aef`。整条工作流成功后，经EAP DELETE，保留OFFLINE原身份记录、创建revoked和空credential epoch；原创建请求409。

随后EAP新建同版本替代实例 `eap-agents/a81-v1-a200ea80fefc` / UID `a3a23a1d-c4c7-4291-b01c-4d3939a88186`。只对本批明确已完成的原执行requestKey做幂等返回核对，返回原Run/原绑定，执行数量仍1，未在替代实例重派。显式新requestKey派发第二次新任务：Run `wf-exec-f855c50f-7cca-442a-b90b-6edbc8c2f59e` /`:1`，Trace `cdb80a87da89d1c7e5379084305a426f`。两次执行均SUCCEEDED/COMPLETE，各模型0/0、Agent1/1、3MCP Span/3被动工具记录成功/失败/UNKNOWN各1，效果UNVERIFIED；四角色读取完全一致，原输出摘要、旧Run、原Span/工具记录不变。替代实例完成后经EAP PAUSE ACKED、SUSPENDED、撤销REVOKED；原实例已删除而身份记录保留。

边界：真实验收是不同instanceKey/UID的替代，不是现网强制同名UID重建；未读取真实Runtime token做负向认证，旧凭证/UID漂移来自隔离测试。进程恢复用例是新Runner对象与测试日志，不证明实际崩溃、磁盘回滚、快照克隆或跨节点恢复。任务显式suspendAfterTask:false，未测自动回收，原全局LEGACY/UNKNOWN保护、旧Workflow31及预算/模型记录保持；没有收费模型或生产工具调用，也未重放以往关闭批次。

3项测试源码推广，891份完整应用输入与本地/规范源一致；Runner、初轮失败后端和补验后端共3临时Pod按UID清理。最终2026-10-20T08:45:08.534395+00:00，UP/Schema87/两端ready重启0、节点无压力，可用磁盘20072919040字节。backend1949131b…、frontend3c9838cb…、默认原生/Kagent镜像均保持。1处配送公钥仅从证据副本移除，原回执保留；Secret值只在远程RAM扫描，令牌/私钥检查通过。121份脱敏证据下载逐项核验，归档324053字节，SHA256 2a31acad978eb72b14c6c95d03a21db35c809ab66b5625ded396f358976dd112。

远程 `/root/eap-mcp-rebuild-20261020-r1` 已closed，r2仅属其中测试补验；禁止重放bootstrap/refine/runner/verify/live/finish等写入口，归档是关闭前快照，以[最终回执](evidence/mcp-rebuild-20261020/final-receipt.json)为准。[完整验收](evidence/mcp-rebuild-20261020/README.md)、[实例替换边界](MCP_INSTANCE_REPLACEMENT.md)。本机仅编辑/静态检查/摘要验证，无应用编译测试构建、commit/push。

下一步独立实现HTTP/SSE观测，再固定OBI版本验证普通容器/gVisor可见率与开销。通用MCP配置UI、Kagent MCP执行、跨任务共享连接、同名UID真实重建、通用持久恢复、生产服务及容量稳定性仍待；不按TTL释放旧占用或清表。Plan5/Plan6、旧28调用、历史UNKNOWN和所有已关闭批次继续保持关闭。


### Codex 进展：HTTP/SSE 请求观测库及隔离入库验收（2026-10-20）

用户要求继续。核对官方2025-11-25和2026-07-28规范后，新增 `http_observer.py` / `http_transport.py`，复用现有MCP_PROTOCOL_V1和RuntimeExporter；共享observer新增完整消息入口、严格UTF-8及HTTP资源名。固定可信endpoint/名称/版本，HTTP POST逐请求独立状态，Trace/parent仅由可信宿主指定；JSON/SSE原字节读取，SSE支持UTF-8分片、BOM、LF/CRLF/CR、多行data、注释/进度。名称白名单只管观测；不导出参数/结果/头/URL/原ID/游标，MCP服务认证和EAP实例导出凭证分流。不使用环境代理、自动重试、重定向或GET恢复，async/MRTR/压缩/断连保守UNKNOWN，效果UNVERIFIED。宿主仍负责协议协商、会话和2026元数据，非完整SDK或无配置抓包。

新增http_fixture/test_http_observer；共3代码/2测试模块，后端/前端/Go/依赖/权限/Schema未改。所有测试在远程：Python58项（原有27、新增31）和后端专项30项/3类全通过，失败/错误/跳过0。合成HTTP真实socket验证原字节、chunked、并发同RPC ID独立Trace、错误/超时/早关闭不重试、导出失败不影响响应及凭证分流；三种HTTP OTLP结果直接进入隔离后端，MockMvc四角色查询与模型/预算无新增通过。不是线上四账号或真实Runtime身份HTTP验收。未重跑完整后端、独立MySQL、Go、前端/浏览器；未构建镜像。

首轮57项中1项失败，保留原日志：Python3.10.12 HTTPResponse.read1在Content-Length归零后仍可能isclosed=false，完整JSON没及时确认。远程逐次读取诊断确认remaining=0/bytes=0但pending=1；修复为结合EOF/剩余长度/关闭状态，短于声明长度仍UNKNOWN，新增网络回归和导出SUCCEEDED断言。r2独立修正快照58项通过后再运行后端，不称首次全绿、不叠加测试次数为覆盖。

5项应用改动测试后同步至 `/root/eap-src`，895输入与本地/测试/规范源一致。仅1临时后端Pod清理，精确UID `d2e47b57-0e89-43a0-a35f-3c665c5d744a`；复制清单沿用了历史名称eap-mcp-rebuild-backend-20261020-r1，但本次src/fixture/out均在新批次r2，已留scope-proof并用UID前置条件删除，没有执行旧入口或触碰旧PodUID。没有创建Agent、派发Workflow或模型/生产工具调用。前后端保持1949131b… / 3c9838cb…、原UID、ready且重启0，默认Runner、原配置/Secret身份、旧观测/占用/模型/预算摘要保持；UP/Schema87、节点无压力。

收尾于2026-10-20T09:30:53.421533+00:00。69份证据远程实际Secret RAM及令牌/私钥模式扫描通过，下载逐项校验；归档255152bytes，SHA256 `431820b16b9435c4768275b155661544a69354eec75d6b2a47e1524a98c3fc89`。远程 `/root/eap-mcp-http-20261020-r1` 已closed，r2为内部修正，所有bootstrap/refine/worker/finish写入口禁止重放。归档保留初轮failed和r2 tests-passed快照，以本地最终回执/closed.json为准。[完整证据](evidence/mcp-http-20261020/README.md)、[HTTP合同](MCP_HTTP_OBSERVATION.md)。本机仅编辑、AST、归档/哈希/链接检查，无应用编译测试构建、git commit/push。

当前只完成HTTP宿主库和隔离入库验证，**尚未发布到真实Actor**。下一步独立固定合成HTTP镜像，接入宿主身份回调，经EAP创建、调度、观测和收尾，再推进固定OBI版本普通容器/gVisor可见率与开销对照。真实TLS/OAuth、生产服务、通用MCP UI、Kagent MCP执行、HTTP会话/跨任务共享连接、同名UID真实重建及通用恢复仍待，不拼接stdio证据为HTTP已上线。旧UNKNOWN保护、Plan5/Plan6、旧28调用及所有已关闭批次保持。


### Codex 进展：HTTP MCP 受管 Actor 已完成真实链路验收（2026-10-20）

用户要求“继续”。在上一轮HTTP观测库基础上，新增Go Runner固定HTTP宿主和合成服务，接通原实例租约、匿名管道续期、任务Trace/父Span与进程组取消；可信配置固定观察器摘要，不能由任务选择endpoint/命令。任务先持久UNKNOWN意图，再执行并保存回执，重复已完成或未知任务只读原回执，冲突409。共4实现/2测试文件。子进程检查无EAP环境和继承管道FD、HTTP不携带EAP身份头，但不构成同UID/root恶意进程隔离。没有通用HTTP启动清单/UI或Kagent MCP执行。

远程原生Runner62顶层/139含子项、stdio专项22/53、HTTP专项21/52、Kagent70/170全部race/vet通过，Python58通过；不同构建存在重叠，不能叠加覆盖。Go1.27.1，专用镜像Python3.12.15；没有在该镜像里重跑完整Python套件，实际Actor运行固定集成链路。运行中身份续期、缺失身份拒绝、取消及持久回执不重派来自远程隔离集成测试；真实轮换仅在任务派发前。后端、前端、权限、Schema未改，本轮未重跑其完整/独立MySQL/浏览器测试。

通过EAP创建Agent82，v1部署 `dep-22070636-32ae-4352-85c5-d1ec72b1b0de`；实例 `eap-agents/a82-v1-2207063632ae`，UID `875968e2-78fd-4bd6-8832-5d560e4d0692`。专用镜像 `10.186.133.66:5001/eap-mcp-http-fixture@sha256:c2984e405fdc08d8137ea76a48fb6d7660c5161a6fb171e88b57aeac37540014` 按registry摘要读取，二进制与6个Python载荷逐项核对。默认原生/Kagent、前后端和既有Actor镜像保持。

Workflow36原Run `wf-exec-a1c6921b-92a7-483f-af88-35c2ef584247` 及`:1`任务SUCCEEDED，EAP存储Trace `d94f44a5f3430a463ce5fb17a5842fd7`。固定2025-11-25/2026-07-28配置：旧初始化/通知2次及工具6次，共8个HTTP POST；JSON成功、chunked多行SSE工具错误、SSE中断未知各2。入库6MCP Span/6被动工具记录、1Agent父Span，所有父子关联一致；viewer/operator/governance/admin真实登录API各7Span/6工具，coverage COMPLETE、模型0/0、Agent1/1。原输出SHA验证、执行数量1。工作流成功是预期分类和导出确认，UNKNOWN仍未知、全部效果UNVERIFIED，不证明生产工具效果。

执行后通过EAP显式PAUSE并撤销连接，最终SUSPENDED/REVOKED。没有重派任务、模型调用或生产工具调用；自动回收未验，原全局LEGACY/UNKNOWN占用保护、Plan5/Plan6及旧28调用保持。工具服务仅Actor内回环，不证明TLS/OAuth、跨进程恶意隔离、生产MCP服务、共享连接或通用崩溃恢复。

6项应用源码已推广远程规范源，900份完整输入与本地一致；两个本轮临时Pod按精确UID删除，验收Actor休眠保留。最终UP/Schema87，backend仍1949131b…、frontend仍3c9838cb…，原Pod UID/ready/重启0保持；两节点无Memory/Disk/PIDPressure，可用磁盘19798179840字节。旧观测/工作流/预算/模型事件/占用行及受保护配置摘要一致。

应用测试及真实验收通过后，归档首次因白名单漏go-version.txt停止；仅导出续验发现身份轮换回执也含配送公钥，再限定两份回执副本脱敏。原finish/export失败日志、脚本及部分导出保留，不重做推广、清理或任务。最终实际Secret及编码值只在远程RAM扫描，令牌/私钥模式检查通过，2处公钥只从导出副本移除。归档266525字节，SHA256 `88329a1e0eab82980e6aeac43ce3c48e14e3b4a9dbb98c07a385521ff39d92ae`，108文件下载逐项校验。证据 `docs/evidence/mcp-http-actor-20261020/README.md`。

远程 `/root/eap-mcp-http-actor-20261020-r1` 已closed，所有原写入口禁止重放；归档是关闭前快照，以final-receipt为准。全程应用编译/测试/构建/部署远程，本机仅文本、AST、源码/证据摘要核验；无commit/push。下一项应新批次固定OBI版本对照普通容器/gVisor可见率及开销。通用MCP UI、Kagent MCP执行/多轮/子智能体、持久审批恢复、长稳容量、HA/TLS/灾备和全量无侵入观测仍未完成。


### Codex 进展：OBI 有界实验已收尾，gVisor 可见率暂不可判定（2026-10-21）

用户要求“继续完善”。在上一轮HTTP Actor链路上新增 `runtime/obi-lab/scenario.py`、`summarize.py`、`test_summarize.py`，固定普通容器合成控制、唯一Span计数、SERVER/INTERNAL区分、元数据裁剪、非法分母/延迟拒绝；只有普通容器对照和同节点证据都有效时才计算可见率。没有后端/前端/权限/Schema业务变更，未重跑未改部分完整套件、独立MySQL或浏览器。初轮8项、最终10项分析测试全部在远程通过，包含关系不相加；本机仅文本/AST/摘要检查，无应用构建测试或commit/push。

OBI固定v0.15.2 / commit b28e4e7fe0a01f1961b7c4747a13b8ff486d6fec，官方Linux amd64发布包SHA256 739641fef1a14c20c50e71b2e0cef99c922ee4ccc8e62606531f81f9bbaae9cc。ate-node1、Linux6.8.0-138-generic、BTF可用；独立可执行名eap-obi-lab-python与28761端口AND选择，MCP解析显式启用，HTTPbuffer65536，上下文注入/协议调试/日志增强关闭，只向宿主回环合成接收器导出。没有将OBI Span送入生产EAP或外部服务。专用镜像sha256:2f8e57881056a94220a5acf3422fb00756cf9d9955d8673147dffc5eba549919，从此前HTTP镜像c2984e40…保留原层，增加独立Python可执行名、固定端口及3秒发现等待；载荷摘要验证，默认Runner和前后端保持。

普通容器基线/观测各20批、160 HTTP请求/120工具调用，每阶段协议成功/失败/UNKNOWN各40。OBI观测阶段480唯一Span中160为SERVER、320为INTERNAL，不能拿480除以160。仅20 initialize被标记为MCP，tools/call0/120、工具名0；状态均UNSET，不能转为工具成功。固定源码在无Mcp-Session-Id时只允许带protocolVersion的initialize，结果与此条件一致；没有追加只改会话头的独立对照，不宣称所有MCP均不支持。源码工具结果未解析isError，官方集成测试也未断言SSE错误；不替代EAP已有MCP_PROTOCOL_V1。

EAP创建Agent83、v1实例eap-agents/a83-v1-300b17f47a90 / ActorUID a38b7879-46d4-4a86-b847-05b99f460cf3。Workflow37仅一次Run wf-exec-75418fa6-d42b-400b-84ef-f83f49c0e7bb / Task后缀:1，Trace e707509d40576c401317b40cd378f0f9。原任务/工作流SUCCEEDED，8 POST、6 MCP Span/工具记录、1 Agent父Span；成功/失败/UNKNOWN各2、效果UNVERIFIED，模型0/0、Agent1/1、coverage COMPLETE。viewer/operator/governance/admin真实API各7 Span/6工具，凭证轮换在派发前。最终经EAP PAUSE ACKED、SUSPENDED并REVOKED；自动回收未验，旧全局LEGACY/UNKNOWN及旧Workflow31保持，无模型/生产工具调用。

OBI的gVisor阶段收到0 Span，但不能据此认定gVisor不可见：集群两个Worker节点，OBI只在ate-node1，当前EAP实例/清单接口未暴露位置，Actor暂停后worker_assignment会清空。本次未在派发前冻结位置，两个Worker日志也无该Actor UID/名称可用记录。最终analysis-final裁决INCONCLUSIVE_PLACEMENT、不输出fraction；comparison.json仅保留初始描述性计数。新增测试禁止缺失/异节点证据得出可见率。gVisor用户态内核是潜在可见性限制，只是待验证解释，不是实测根因。

基线160样本median1.059708ms/p951.604475ms，开启OBI median1.2472515ms/p952.204943ms；进程采样峰值RSS129.765625MiB、累计CPU11.48秒含启动。只有单轮顺序运行，未随机/重复或做同负载资源对照，不给生产开销百分比、零开销或性能退化结论。没有验证TLS/OAuth、生产MCP、OBI原任务关联、实际副作用、通用配置或Kagent MCP执行。

OBI退出码0，新增BPF程序剩余0；收尾发现专用BPFFS目录仍固定traces_ctx_v1 Map id2172。核对本轮配置路径、Map id/name/type、无程序引用、OBI进程退出后，仅unlink该路径并验证id消失，再rmdir空目录；不清理全局BPF。构建Pod UID ed9ca83e-b437-4db3-91d7-672eb0e185ee、普通容器Pod UID f51681a5-0ec1-40dc-95e6-6e31542422b8均Succeeded并按UID前置条件删除，临时Pod剩余0。

3个受测源码推广，903份应用输入与本地/规范源一致。最终2026-10-20T23:24:30.409507+00:00（北京时间2026-10-21），UP/Schema87，backend1949131b…/frontend3c9838cb…原UID、ready重启0保持，两节点无Memory/Disk/PIDPressure，可用磁盘19875319808字节。旧Span/工具/工作流/占用行、完整预算/模型事件及配置摘要一致。112份证据远程实际Secret/编码值仅RAM比对，令牌/私钥模式扫描通过；两处配送公钥只从副本移除。归档210779字节，SHA256 6728a79d4b5ed9f34a6d548fd2c5c48aa9e344d589aa6b7b32feafd1802ca32a，全部文件和903份输入已本地核验。

远程 /root/eap-obi-20261020-r1 已closed，所有原写入口禁止重放；归档是关闭前快照，以[最终回执](evidence/obi-20261020/final-receipt.json)为准。[完整证据](evidence/obi-20261020/README.md)、[适用性与来源](OBI_OBSERVATION_ASSESSMENT.md)。下一步先补EAP Actor权威Worker/Pod UID、nodeName与观测时间，并在派发前留证，然后在新批次实际节点做单任务与前后普通容器对照；不能唤醒旧Actor补证。生产OBI导入、通用MCP/Kagent执行、长稳容量、HA/TLS/恢复仍待。Plan5/Plan6、旧28调用、历史UNKNOWN和所有关闭批次保持。


### Codex 进展：运行位置只读 API 已部署，任务边界快照真实验收通过（2026-10-21）

用户要求“继续完善”，期间要求“继续”。本轮补上一轮OBI缺失的权威位置读取能力：新增 RuntimePlacementController / RuntimePlacementService，扩展 SubstrateClient / GrpcSubstrateClient，4生产/3测试文件。GET /api/agents/{agentId}/substrate/placement?instanceKey=... 先核对Agent所属实例、绑定Actor UID/模板/路由，再读取Actor及其不透明Worker引用；核对namespace/pool/Pod UID/node后复读Actor，身份/模板/状态变化拒绝组合快照。上游读取共享最多5秒期限且服从更短配置，无重试、缓存、唤醒、任务派发或历史回填。

响应ASSIGNED/UNASSIGNED/UNKNOWN，包含查询起止时间、已验证的Actor UID/版本/状态与安全Worker/Pod/节点字段。已确认SUSPENDED/PAUSED/CRASHED返回UNASSIGNED且不读Worker；身份不符、缺失、迁移中、上游失败均固定UNKNOWN原因，不泄露上游正文/IP/命令/环境。权限仍由PlatformPermission.VIEW_CONSOLE统一定义，没有新增角色规则、前端按钮、Schema、依赖或默认Runner变更。详见[接口合同](RUNTIME_PLACEMENT.md)。

新增17项测试：gRPC7项验证真实本地测试服务器的Worker引用/UID、暂停、缺失分配、错配、同名替换、读取期间变化和权限失败；Service8项验证绑定、跨Agent、无缓存、未知与异常不泄露；MVC2项覆盖四角色、匿名401和无权限403。全程在远程独立Pod运行，专项8类80项、完整141类1297项均通过，失败/错误/跳过0；专项包含在完整套件中不累加。没有重跑独立MySQL、Go/Python、前端或浏览器；真实HTTP验收使用现网MySQL。受测输入908份（原903+5新文件），7应用文件的冻结摘要与本地/远程规范源一致。

镜像仅在旧后端父镜像上替换受测JAR，载荷及运行配置核对通过。后端经UID/resourceVersion/原镜像前置条件发布，临时Recreate避免新旧进程并行，旧Pod退出后恢复原策略。当前backend sha256:5595e6d93c9665f3fa3fbd939bc95690cfd8b742737be3facb5c1db56160a52e，Pod backend-6cd66db6f6-q9nrl / UID d86c16c5-7856-473b-9d41-5f060b4fa1fc。frontend仍sha256:3c9838cb14aa144b79bb8253a302fa00ac8b32538c9e01c5554194a0e3312d39，原Pod UID168d2204-bf90-4e29-a8db-a80f4ef788e1保持；默认原生/Kagent镜像与受保护配置保持。

EAP创建Agent84、实例eap-agents/a84-v1-a51dfc6fb827 / ActorUID3f09165a-b736-4323-8fad-8e9b9cd2f8e5，复用经重新核验的专用镜像sha256:2f8e57881056a94220a5acf3422fb00756cf9d9955d8673147dffc5eba549919。EAP自动身份配送、一次派发前续期、必要的显式RESUME；未读取Runtime令牌。任务前2026-10-21T01:40:04Z和任务后01:40:21Z均ASSIGNED、Actor版本11，WorkerUID65de6e5c-3564-4548-bb70-3713efa5e95c，Pod eap-agents-5bf774b9cd-kxh6w / UIDb0e17e0f-ecd5-4e42-a8a7-22608edb0bf0，nodeName ate-node2；独立Kubernetes只读查询核对PodUID和节点。四角色运行位置一致；真实匿名401、其他Agent访问400。

Workflow38仅一次执行：Run wf-exec-909e78c8-4ebb-42b6-8e6f-17795a6a4105，Task后缀:1，Trace2719758938a80882f785b1fb8883c119。任务/工作流SUCCEEDED，8 POST、6 MCP Span/工具记录、1 Agent父Span；模型0/0、Agent1/1、coverage COMPLETE，四角色各7 Span/6工具。成功/失败/UNKNOWN各2，效果仍UNVERIFIED。观测来自已有协议观察器，不是OBI。随后EAP显式PAUSE ACKED/SUSPENDED，四角色GET均UNASSIGNED/placement=null/同一UID版本13，确认读取没有唤醒；最后连接REVOKED。自动回收未验，没有模型或生产工具调用，原业务/预算/模型事件/全局LEGACY/UNKNOWN及Workflow31保持。

7个受测文件推广到/root/eap-src，908输入与本地一致。测试Pod UID36055be0-e675-4208-bd6a-1f4611f71f00、构建Pod UIDba37a79f-6ae2-49a5-a7e8-20d52a7a322a均Succeeded，按精确UID前置条件删除，临时Pod剩余0。最终UP/Schema87/两端ready重启0，两节点无Memory/Disk/PIDPressure，可用磁盘19049713664字节；样例Actor休眠保留。

401份脱敏证据下载逐项核验，并校验全部908输入及本地/归档合同脚本一致。实际Secret及编码值仅远程RAM扫描，令牌/私钥模式检查通过；两处配送公钥只从导出副本移除。归档1138559字节，SHA256 3e0d9ea218d81b5b10db959f8c8bcae06245dca07cb90006d8140dd045eac187。本机只文本/AST/摘要检查，无应用测试构建或commit/push。

远程/root/eap-placement-20261021-r1于2026-10-21T01:40:37.583473+00:00（北京时间09:40）closed，所有原写入口禁止重放。归档status是关闭前快照，以[最终回执](evidence/runtime-placement-20261021/final-receipt.json)、[关闭标记](evidence/runtime-placement-20261021/closed.json)为准；[完整证据](evidence/runtime-placement-20261021/README.md)、[下载核验](evidence/runtime-placement-20261021/local-verification.json)。

未完成项：位置快照由本次调用方留存，尚未自动写入工作流派发记录；无前端位置视图/迁移事件流，边界同位置不证明连续位置。本轮未运行OBI/eBPF，旧Agent83的INCONCLUSIVE_PLACEMENT保持，禁止唤醒旧样例补证。下一步另建独立批次，经EAP查询新样例的实际节点，在该节点布置探针并保留任务前后的普通容器阳性对照及位置响应，再判断gVisor可见性。生产OBI导入、TLS/OAuth、通用MCP配置/UI、Kagent MCP执行、长稳容量、HA/恢复仍待。Plan5/Plan6、旧28调用、历史UNKNOWN和所有已关闭批次继续保持。


### Codex 进展：同节点 OBI 前后对照完成，固定配置覆盖缺口已确认（2026-10-21）

用户继续推进。新增runtime/obi-lab/bracket_control.py、bracketed.py、test_bracketed.py：普通容器任务前后各10批/80请求，按Span开始结束时间分为三个互不重叠窗口，不按导出到达时间归类。重复、跨窗/窗口外、缺失前后阳性对照、观察器中断/重启、Actor/Worker/Pod/节点变化都不能生成有效可见率；前后同分配也不宣称连续位置。远程22项测试通过（原10+新12），失败/跳过0。3新文件推广，基线908→911应用输入全部一致；未改后端/前端/权限/Schema/Go或MCP运行代码，没有重跑这些未改部分完整套件、独立MySQL或浏览器，上轮1297后端测试不是本轮重跑。

新批次/root/eap-obi-node-20261021-r1。固定OBI v0.15.2 / commit b28e4e7fe0a01f1961b7c4747a13b8ff486d6fec，复用已核验发布归档SHA256 739641fef1a14c20c50e71b2e0cef99c922ee4ccc8e62606531f81f9bbaae9cc、二进制840085c21aa22419ffa3615b4dd1d2851a29dd42305a9c057e87599f7c649b8b。未升级/重下载或构建新镜像。专用合成镜像2f8e5788…重新核对载荷，前后端和默认原生/Kagent Runner保持。

EAP创建Agent85 / Workflow39，实例eap-agents/a85-v1-cf7322e406f5 / ActorUID ea0e293d-2cbd-4fdf-903b-0cef065bfc68。自动身份配送与一次派发前续期，必要RESUME均经EAP；不读取Runtime令牌。EAP位置初读与Kubernetes只读核对WorkerUID65de6e5c-3564-4548-bb70-3713efa5e95c，Pod eap-agents-5bf774b9cd-kxh6w / UIDb0e17e0f-ecd5-4e42-a8a7-22608edb0bf0，节点ate-node2。在该节点创建独立观察器和普通容器对照；观察器无服务账号令牌，privileged/hostPID/hostNetwork、有界deadline和专用BPFFS路径，宿主挂载用于BTF/进程读取与精确BPF收尾，不读凭证目录内容。

OBI同一规则限定*/eap-obi-lab-python与28761端口，端口用于进程发现而非请求级过滤。MCP解析开启，上下文注入/协议调试/日志增强关闭，只向宿主回环合成接收器导出，未送入生产EAP或外部遥测服务。Linux6.8.0-138-generic/BTF可用；日志两条云元数据探测超时并忽略告警保留。正式任务前普通容器80/80 SERVER、160 INTERNAL，先确认完整阳性对照才派发；任务后对照同样80/80 SERVER、160 INTERNAL。合计480唯一Span，320内部子Span不能当作请求；initialize20、tools/call0/120、无工具名、状态UNSET，不代表工具成功。

原任务仅执行一次，Run wf-exec-7852f3d0-2c95-4f34-abfb-0176d025cc2b / Task后缀:1，Trace fcb7b4d251ee31f66cd47ba0be2c9a2b。EAP任务/工作流SUCCEEDED，8 POST，既有协议观察器产生6 MCP Span/工具记录+1 Agent父Span，成功/失败/UNKNOWN各2、效果UNVERIFIED；模型0/0、Agent1/1、coverage COMPLETE，四角色均7 Span/6工具。任务前02:48:33Z与后02:48:51Z位置均同一Actor/Worker/Pod/ate-node2、Actor版本11。快照是边界证据，不证明持续不迁移。

观察器未中断或重启，最终退出0；按同节点时钟窗口统计，任务阶段OBI 0 Span/8 HTTP请求，窗口外Span0。analysis-final裁决VISIBILITY_GAP、fraction0.0，范围仅本次固定版本/可执行名AND端口发现配置/合成gVisor回环HTTP负载。排除了简单的观察器整体失效和边界异节点，但没有单独定位进程发现/协议可见性哪层缺口，不能声称所有OBI或eBPF方案都无法观察gVisor。EAP的6MCP不是OBI成果，旧Agent83的INCONCLUSIVE_PLACEMENT不改写。

任务后EAP显式PAUSE ACKED/SUSPENDED，四角色GET位置UNASSIGNED且没有唤醒，连接REVOKED。自动回收未验，没有模型/生产工具调用；原业务/预算/模型事件/全局LEGACY/UNKNOWN及旧Workflow31保持。所有原任务、输出、四角色观测及运行位置回执留存，不重派任务。

观察器退出后新增BPF程序0，专用Map835经路径/名称traces_ctx_v1/类型lru_hash/无程序引用核对后unlink、确认ID消失，删除空专用BPFFS目录；不清理全局BPF。测试Pod UIDb5efe9ff-0f42-468a-80d9-f22485f443b2、观察器UIDd41e4bcc-e1d4-4353-87fe-f52ad5f520cf、普通容器UID4f63faae-e352-442a-bed4-17aefc3b07a1均Succeeded并按精确UID清理，临时Pod剩余0。一次并发只读进度查询遇到Pod已按正常流程删除，正式完成回执随后核对通过，非实验失败。

最终UP/Schema87，backend5595e6d9…/UIDd86c16c5-7856-473b-9d41-5f060b4fa1fc、frontend3c9838cb…/UID168d2204-bf90-4e29-a8db-a80f4ef788e1原镜像/UID保持、ready重启0。两节点无Memory/Disk/PIDPressure，可用磁盘19455889408字节，默认Runner与受保护配置/旧行摘要保持。117份脱敏证据、911输入及本地/归档合同脚本核验一致；实际Secret/编码值仅远程RAM比对，令牌/私钥模式扫描通过，两处配送公钥仅从导出副本移除。归档210968字节，SHA256 4cd22940803ffc4b98186a7a2dbccd141a7aa4c8d3de4615c274cf9ab115f908。

远程批次于2026-10-21T02:49:26.872205+00:00（北京时间10:49）closed，所有bootstrap/worker/live/探针/任务/finish写入口禁止重放。归档为关闭前快照，以[最终回执](evidence/obi-node-20261021/final-receipt.json)为准；[完整证据](evidence/obi-node-20261021/README.md)、[本地核验](evidence/obi-node-20261021/local-verification.json)、[适用性评估](OBI_OBSERVATION_ASSESSMENT.md)。所有应用测试/实验均远程，本机仅编辑、AST与摘要核验，无commit/push。

下一步产品化：位置快照仍由验收调用方保存，应接入原任务持久证据与控制台，明确时间、来源和覆盖边界；观测失败不触发业务重派或更改原任务结果。gVisor MCP继续使用既有协议观察器，网络/协议/任务/效果证据分层。更广发现规则、会话头单变量、TLS/OAuth、共享连接与性能对照需独立有界实验；生产OBI导入、完整Kagent MCP执行、通用配置/UI、长稳/HA/灾备仍未完成。Plan5/Plan6、旧28调用、历史UNKNOWN与所有关闭批次保持，不唤醒旧样例补证。


## Codex 进展：任务运行位置持久证据与界面（2026-10-21）

EAP 现已在已登记 Substrate 工作流任务派发时、收到回执后请求后台采样，按原任务绑定保存位置、来源和查询时间；任务工作台及覆盖详情共用只读展示。V88 只新增记录表，不回填旧任务。位置失败不触发业务重派；重复采样不覆盖原记录，绑定替换不借用新实例。权限仍来自 PlatformPermission /me。[功能合同](TASK_RUNTIME_PLACEMENT_EVIDENCE.md)。

远程后端完整 143 类 1311 项、专项 8 类 71 项（包含在完整套件中）、隔离 MySQL 9 项、前端 483 项全部通过，失败/错误/跳过0；类型检查与生产构建通过。首轮新增测试夹具漏配目标白名单，2项断言失败，修正夹具后完整验证；原失败源码和报告保留。真实浏览器发现时间仅显示毫秒尾数，已修复日期/时分秒格式并增加回归测试，单独重建发布前端；抽屉动画稳定后边界检查通过。919份输入核验、16份变更推广。全部应用测试/构建在远程，无本机运行或commit/push。

前后端均已部署，Schema88。EAP 新建 Agent86 / Workflow40，只派发一次无模型合成任务，SUCCEEDED、coverage COMPLETE、6 MCP/工具+1 Agent Span；两个位置采样均ASSIGNED、节点ate-node1。四角色API与桌面/移动端共8组浏览器验证通过；最终SUSPENDED/REVOKED后持久采样不变，旧Agent85仍无位置记录，读取不补采、不唤醒。原工具UNKNOWN和效果UNVERIFIED、旧业务/预算/模型记录保持，默认Runner未改。

批次 `/root/eap-placement-evidence-20261021-r1` 已closed，原写入口禁止重放。9个临时Pod按UID清理，UP/两端ready重启0/节点无压力；566份脱敏证据及归档下载核验。[最终回执](evidence/runtime-placement-evidence-20261021/final-receipt.json)、[完整验收](evidence/runtime-placement-evidence-20261021/README.md)。

**限制与下一步：** 采样异步执行，实际查询可能晚于触发或任务结束；队列驻留内存，拥塞/中断可能留下缺失或未完成，无补采和恢复。两个采样不证明连续位置，Actor宿主位置也不代表远端MCP服务位置。下一步评估采样投递指标、容量与持久化需求，单独设计有界验证；同时复核前端Pod就绪与HTTP就绪之间的发布窗口。迁移事件流、长稳/HA/恢复、通用MCP配置/UI、Kagent MCP执行、更广OBI规则/TLS/OAuth仍未完成。Plan5/Plan6、旧28调用、历史UNKNOWN和关闭批次保持，不唤醒旧样例补证。以下为历史记录，以本节为准。

受测后端镜像 `10.186.133.66:5001/eap-backend@sha256:dc72e3427e80904922a0764686b2c5e879454c5c07add688fe9f4ca5866ddbbd`，Pod `backend-8bf6687c5-85h5n` / UID `9e3e11cd-4351-498e-bc6e-442d7cd242cc`；前端镜像 `10.186.133.66:5001/eap-frontend@sha256:e1a74568363e40fe516eef97bad89b423aee04f9e910397f8b07f5975b382997`，Pod `frontend-cfb799c74-zxnfs` / UID `5215ed0d-8f65-4609-afe2-7b7392ce0b7f`。原任务 `wf-exec-3758cef9-b752-4263-9cd9-0c2ed8f264ea:1` / Trace `5de2695c428222b5748f697aa3d2d8c3`，Actor `eap-agents/a86-v1-8a7455e86b76` / UID `ced73035-c7e7-4c70-bee2-a94a44c07692`。归档SHA256 `067dcaa65d5caf97c4bc02fa628b7e09876d113e7f2471e09b169e8ef88aa72d`。关闭后禁止重放本批次任何写入口，下一批使用新独立记录。


## Codex 进展：前端 HTTP 就绪与发布门槛（2026-10-21）

已补齐前端 HTTP readiness、5 秒稳定时间和 maxSurge=1/maxUnavailable=0 滚动更新设置；新增只读发布门槛，同时核对 Deployment、Pod、EndpointSlice 与受测 index/version 摘要。连接拒绝/503 只在总时限内重读，不重试部署或业务派发；身份、配置或内容漂移立即失败。[操作合同](FRONTEND_HTTP_READINESS.md)。

远程 19 项回归通过。隔离对照实测：无探针时 HTTP503 仍 Ready；同款探针会排除 503 端点，200 后加入，退化后移除，再恢复无需重启。真实发布连续三轮验收通过，更新期间61次页面采样均成功；四角色真实认证代理 /me 能力与后端一致，原有两条位置证据可读，匿名401。短时离散采样不证明零中断或长期HA。

前后端镜像不变，Schema88；前端新Pod `frontend-6676d684c9-p7vhg` / UID `6e5fc474-3b32-4448-a7b9-cd2f658ad8ee`，后端原Pod/UID保持，两端Ready重启0。11张业务表全量摘要/数量、默认Runner和其他受保护配置保持。919份应用输入未改；两份运维源码远程受测后安装到 `/root/eap-src/deploy/substrate-poc/`。没有重建应用、派发任务、唤醒旧Agent、模型/生产工具调用或commit/push。

独立批次 `/root/eap-frontend-readiness-20261021-r1` 已closed，原写入口禁止重放。2个实验Pod和1个Service按UID清理；39份脱敏证据、归档及本地/远程源码摘要已核验。[完整验收](evidence/frontend-readiness-20261021/README.md)、[最终回执](evidence/frontend-readiness-20261021/final-receipt.json)。后续前端发布必须保留探针并使用新的 HTTP 验收门槛，不整份 apply 旧清单。

**下一步回到位置采样可见性：** 当前内存队列拥塞、进程退出可能丢失采样或留下未完成记录，尚无投递指标和恢复。先检查现有 ObservationMetricsExporter，设计有界指标与拒绝/失败统计；不能以当前位置回填历史，也不能因采样失败重派任务。容量/持久化、迁移事件流、长稳/HA、通用MCP配置/UI、Kagent MCP执行和更广OBI/TLS/OAuth仍待。Plan5/Plan6、旧28调用、历史UNKNOWN及所有关闭批次保持。本轮未重跑未改应用的完整套件、浏览器或eBPF。以下为历史记录，以本节为准。

前端镜像保持 `10.186.133.66:5001/eap-frontend@sha256:e1a74568363e40fe516eef97bad89b423aee04f9e910397f8b07f5975b382997`；后端保持 `10.186.133.66:5001/eap-backend@sha256:dc72e3427e80904922a0764686b2c5e879454c5c07add688fe9f4ca5866ddbbd`。历史瞬时连接拒绝缺少更细事件记录，探针缺失已证实，但不能排除端点传播参与。归档SHA256 `1b57c17cdd556896ca06b5d3a0828ad58dacf77c7fd8e58cb0112be4c311a556`，70617字节。已关闭批次仅补做只读认证审计、证据导出与两份受测运维脚本安装，没有重放发布；以最终回执为准。所有应用测试、实验、部署远程完成，本机仅编辑、AST/摘要核验和归档处理。


## Codex 进展：采样指标已部署，原 HTTP 样例 UNKNOWN 保留（2026-10-21）

已补齐原任务位置采样的 2 阶段 / 14 事件计数、队列水位、只读 API 及现有 OTLP 导出。Prometheus 固定白名单扩展后，实际 7 类 / 34 条序列与 API 一致，进程起点及新鲜度通过，旧覆盖指标保持。权限仍由 PlatformPermission 定义；没有新增前端授权规则、Schema、Runner 或重试/历史回填。[功能合同](PLACEMENT_SAMPLING_METRICS.md)。

远程后端完整 144 类 1319 项、专项 10 类 90 项（包含于完整套件）、隔离 MySQL 9 项均通过，失败/错误/跳过 0；打包与镜像载荷通过。5生产/5测试文件、921份应用输入核验；前端/Go/Python/浏览器/eBPF未改未重测。四角色真实采样 API 一致、匿名401；前端原镜像/Pod保持，HTTP就绪门槛连续3次通过。所有应用测试构建部署远程，无commit/push。

**真实样例未通过：** EAP 新建 Agent87 / Workflow41，唯一任务 `wf-exec-6b4d25a6-06ee-4f41-ba54-aa69a102e127:1` 为 EXECUTION_UNKNOWN，Agent Span FAILED / 20010ms，MCP Span及工具记录0、模型0，HTTP请求次数未知。耗时与固定夹具20秒截止吻合，但阶段未定位；不能把原coverage COMPLETE或采样成功解释为MCP功能通过。原成功验收失败日志保留；随后只对同一原任务验证采样指标，没有重派或重查/task。

原操作56的两次ASSIGNED位置已持久化，各阶段REQUESTED/ACCEPTED/STARTED/RECORDED_ASSIGNED均1，其余0。Agent最终经EAP显式SUSPENDED/REVOKED，原任务与UNKNOWN占用摘要保持、执行仍1；旧Agent85未回填。最终裁决 `PLACEMENT_SAMPLING_VERIFIED_WITH_UNKNOWN_FIXTURE`。

后端已更新 `sha256:f439b3bb903cb6074aeab9c641234c6272188fe95a5e12e48b1a39b7064b620f`，Pod `backend-d75758495-c8s7s` / UID `04e7a78c-cca3-4dbe-afcb-4ae797dad4cf`。前端仍e1a74568… / 原UID6e5fc474…，Schema88、两端Ready重启0、节点无压力，受保护旧业务/预算/模型/配置保持。3临时Pod按UID清理，10受测文件推广；472份脱敏证据、921输入、22合同脚本和归档本地核验。

远程 `/root/eap-placement-sampling-20261021-r1` 已closed，原写入口禁止重放。归档为关闭前快照，以[最终回执](evidence/placement-sampling-metrics-20261021/final-receipt.json)和[完整验收](evidence/placement-sampling-metrics-20261021/README.md)为准。中间部署脚本索引错误、promtool stdin超时、Python纳秒解析错误与原修正均保留，不掩盖真实样例失败。

**下一步优先定位 HTTP 夹具失败。** 阅读runtime/agent-runner/mcp_http_fixture.go、mcp_session.go及runtime/mcp-observer/http_actor_probe.py；原失败路径丢弃细分阶段诊断，需增加固定、安全阶段码和有界故障注入，再用新批次验证。不能盲目延长超时、重派原任务或唤醒Agent87。之后评估采样容量/时延/持久化；内存计数重启归零，不能重建崩溃丢失量。镜像层累积与构建解包耗时也需另批处理。迁移事件流、通用MCP配置/UI、Kagent MCP执行、长稳/HA/恢复、更广OBI/TLS/OAuth仍待。Plan5/Plan6、旧28调用、历史UNKNOWN和所有关闭批次保持。以下为历史记录，以本节为准。


采样进程起点2026-10-21T07:21:11.510963876Z；实际Prom计数名eap_runtime_placement_sampling_events_total，核对快照年龄61.17秒。Actor UID02b0c033-a7a0-40b7-9225-eadd404d0815，两个位置均ate-node1 / Podeap-agents-5bf774b9cd-kbw6s / UIDd1cf5e50-2d09-452a-ab3a-769e993ea361，边界位置不证明持续不迁移。原UNKNOWN占用摘要f20c9ef3456b0e9be81b7a9738d2028ecfe741c83975c424f430c5e05b8b2948保持。归档1265938字节，SHA25640f1fa9aeb2f327c13b633815d12d6cddd954ff0699dd9942622ffd509c7bb8b，关闭时间2026-10-21T08:59:48.165458Z。最终可用磁盘18299953152字节；没有清理仍被引用的镜像层。


## Codex 进展：固定 HTTP 阶段诊断已部署，新样例成功（2026-10-21）

修复了固定HTTP夹具的诊断丢失：回执及父Agent Span保留固定失败阶段、deadline标记与5个分段耗时，后端TraceMetadata按固定白名单读写。20秒截止、原意图不重放、UNKNOWN与效果UNVERIFIED语义保持；没有将阶段伪造为额外Span。4应用文件（2生产/2测试），无Schema、权限、前端或默认Runner变更。[功能合同](HTTP_FIXTURE_DIAGNOSTICS.md)。

远程后端145类1321项，Go HTTP/身份23顶层/含子用例60（race/vet），Python58项通过，失败/错误/跳过0。原版对照按预期失败，证明诊断缺失；新版覆盖READY/证明超时、异常退出、错误证明、导出未确认和启动失败。未重测前端、浏览器、原生/Kagent、独立MySQL或eBPF。所有应用测试/gofmt/构建/部署远程，无commit/push。

EAP创建Agent88 / Workflow42，唯一Task `wf-exec-6993b05d-8c35-4a46-bb0e-6a23112558be:1` 成功。父Span11123ms，阶段COMPLETED、deadline=false；启动15ms、READY7241ms、控制0ms、证明3861ms、退出1ms。8合成HTTP请求、6MCP/工具+1Agent，模型0，协议成功/失败/UNKNOWN各2、效果UNVERIFIED。四角色API阶段/耗时相同，两次位置自动留存；最终经EAP显式SUSPENDED/REVOKED，未重放。原Agent87 UNKNOWN、占用与旧业务/预算/模型摘要保持。

**原失败根因尚未证实。** 新成功缩小排查范围但不证明旧问题消失；READY包含解释器启动、导入、凭据管道与服务启动，证明读取含原变体3秒发现等待。额外隔离探针证实HTTPServer绑定时调用getfqdn，DNS请求0；这不等于生产DNS超时。下一步新独立批次细分READY阶段并做有界单变量对照，不唤醒Agent87/88、不重派旧任务、不盲目延长超时或放宽网络。

后端镜像 `sha256:ebdbf52a38e35d188d8d6de0c091a0f3011a97392bcd195ef95753f03994b49f`，Pod `backend-5cc89fcd8d-dmhwg` / UID `d1f09797-b3e2-4c3f-83e0-51348491de2f`。新专用夹具 `sha256:9fa69a583673516058578eda39fd5df30dc38ea0c95bae04957b49421e27264f` 只替换受测Go二进制，原13层保持。前端仍e1a74568… / 原UID6e5fc474…；Schema88、两端Ready重启0、节点无压力。现有34条采样指标与API/进程起点一致、旧指标新鲜，前端HTTP门槛连续3次通过。

远程 `/root/eap-http-diagnostics-20261021-r1` 已closed，所有原写入口禁止重放。4临时Pod按UID清理，4受测文件推广，922输入、440脱敏证据、15合同脚本及归档本地核验；以[最终回执](evidence/http-fixture-diagnostics-20261021/final-receipt.json)和[完整验收](evidence/http-fixture-diagnostics-20261021/README.md)为准。归档为关闭前快照。阶段目前通过Trace API读取，前端阶段展示未实现。

调查状态DONE_WITH_CONCERNS：诊断缺口修复，历史超时根因仍待。容量/持久采样、迁移事件流、通用MCP配置/UI、Kagent MCP执行、长稳/HA/恢复、更广OBI/TLS/OAuth及镜像层精简仍待。Plan5/Plan6、旧28调用、历史UNKNOWN及所有关闭批次保持。以下为历史记录，以本节为准。


Actor eap-agents/a88-v1-06886fb444af / UIDd12abca3-4271-470b-9e61-3675f313e261。独立Prom快照年龄68.851秒，计数名events_total，34条序列。归档1217977字节，SHA256937fd2bcfbadccb7b2eece9e2b38b3fd9f1d042b3cac40a31ab6e78585bee85d，关闭2026-10-21T11:26:31.930340Z，可用磁盘17632788480字节。调查长期经验：必须区分主机阶段与子进程内部原因，首次失败阶段不能被取消/清理覆盖；CPython服务绑定包含getfqdn依赖，但拦截测试只证明调用路径，不能把它写成生产根因。采用investigate流程，未增加遥测外发或提交。


## Codex 进展：HTTP 启动细分已验证，主要耗时落在导入阶段（2026-10-22）

增加固定HTTP夹具成功输出的10个启动分段计时及version=1，Python/Go校验固定键、整数范围及包含关系，未输出凭据、命令或错误正文；旧摘要无新字段仍兼容。5应用文件，922→923输入。保留20秒截止、5秒服务READY、固定端口和原变体3秒等待；没有实施启动加速、后端/前端/权限/Schema或默认Runner发布。[功能合同](HTTP_STARTUP_TIMING.md)。

远程Python63项、变体5项；Go HTTP24顶层/61含子用例、原生18/49、stdio22/53、变体1项竞态测试及HTTP vet通过，失败/跳过0。旧版真实READY因缺少startup字段按预期失败；新增测试验证解析延迟归因、异常恢复、类型/范围与私有字段拒绝。后端、前端、浏览器、独立MySQL、Kagent完整套件及eBPF未重跑；全部应用测试/gofmt/构建在远程，无commit/push。

EAP新建Agent89 / Workflow43，唯一任务 `wf-exec-6d8e2e17-ab46-4cfa-959d-b5d4d613378f:1` SUCCEEDED，父Span10787ms；READY7106ms。观察器导入3170ms、服务导入3203ms，合计约占READY90%；身份就绪5ms，getfqdn113ms。剩余204ms包含解释器/调度/管道等，不是精确解释器时间。8合成HTTP、6MCP/工具+1Agent、模型0，四角色观测一致，两条ASSIGNED位置在ate-node2；显式SUSPENDED/REVOKED后原回执、工作占用和位置保持，未重放。

同镜像/同节点普通容器500m/256Mi三次对照：服务导入202/193/194ms、READY238/270/221ms，无HTTP、模型或凭据。此为顺序小样本，未控制缓存/调度/隔离，不能推断gVisor内部具体根因。**历史Agent87 UNKNOWN未解决，不能用新成功改写。** 新细分计时目前随成功输出保存，失败/截止时未保证可得；前端细分界面未实现。

专用镜像 `sha256:69ba53c78113988a01d5f12eeb497fef9b1780a5b0c23a2c4b4818d9ab511532`，继承原14层，只替换Go二进制及两个受测Python载荷。后端ebdbf52a…/UIDd1f09797…、前端e1a74568…/UID6e5fc474…原样，UP/Schema88、Ready重启0、节点无压力；原34条采样指标与API/进程起点一致，前端HTTP门槛通过。旧业务/预算/模型/配置摘要保持。

远程 `/root/eap-http-startup-20261022-r1` 已于 `2026-10-22T01:55:57.523852+00:00` closed，原写入口禁止重放。3临时Pod按UID清理，5受测文件推广、923输入核对；118脱敏证据、15合同文件及归档本地核验。归档272106字节，SHA256 `f86464c3d7feedb4166e57402b5283108cb78935eb84f473b2d9f81a95e18861`，可用磁盘17961369600字节。[完整验收](evidence/http-startup-timing-20261022/README.md)、[最终回执](evidence/http-startup-timing-20261022/final-receipt.json)。

调查状态DONE_WITH_CONCERNS：启动计时交付、成功样本的主要耗时已定位至导入阶段；历史超时及底层导入慢原因仍待。下一步先剖析导入模块/I/O/调度，再做预编译/缓存等有界单变量实验，不能盲目增加超时。Plan5/Plan6、旧28调用、Agent87/88/89和所有关闭批次保持，不唤醒补证。采样持久化/迁移事件流、通用MCP配置与UI、完整Kagent MCP执行、长稳/HA/恢复、更广OBI/TLS/OAuth及镜像层精简仍未完成。


## Codex 进展：缓存缺口已补齐，新的原镜像对照仍出现超时（2026-10-22）

本轮调查先确认：固定Python3.12.15镜像大部分导入已有缓存，普通容器中观察器/服务仍各6/4次源码编译、409/426次文件状态查询。新增prepare_http_bytecode.py及7项回归，匹配解释器、保持TIMESTAMP校验/optimize=0和assert，生成20个缺失/失效缓存、77个既有缓存保持；包含部分frozen/启动模块，20不等于运行期编译次数。候选普通容器复测两入口源码读取/缺失缓存/编译均0，状态查询次数未减少；gVisor内部缓存命中未测。[功能与限制](HTTP_BYTECODE_CACHE.md)。

运维工具远程7项全通过，镜像文件/继承层/入口核验通过；没有重跑未改后端、前端、完整Python observer、Go、Kagent、浏览器、独立MySQL或eBPF。923应用输入未变，仅新增安装2份受测运维工具。所有测试/编译/构建/部署远程，无commit/push。

**真实对照并非全通过。** EAP分别创建Agent90/Workflow44原镜像与Agent91/Workflow45缓存候选，各派发一次。两者边界位置同一ate-node1 Worker/Pod。原Task `wf-exec-4f09a2e4-a01e-4b4e-8337-75da46d76bca:1` 为EXECUTION_UNKNOWN：父Span20012ms，启动414、READY18801、PROOF789ms，READ_PROOF/deadline=true；MCP/工具0，实际HTTP次数未知，模型0。原回执、UNKNOWN占用保持，没有重试。失败时内部细分计时仍不可得，不能归因到具体导入或直接套用Agent87根因。

候选Task `wf-exec-5a863864-6368-4262-b0e1-8e90e5051219:1` SUCCEEDED，总12708ms、READY9101ms；观察器/服务导入4795/3521ms，身份3ms、getfqdn163ms。8合成HTTP、6MCP/工具+1Agent，模型0，协议三种结果各2、效果UNVERIFIED。四角色各自观测一致，两条任务各保存2条ASSIGNED位置；最终均SUSPENDED/REVOKED。单次顺序/被截止的对照不能证明稳定提速，缓存未解决全部启动开销。

候选专用镜像 `sha256:e8784ef962a611c5a6303cca575f4e8697733c6483af2479edb9ff26a5d2c13e` 只增加缓存层，原源码/Go二进制/解释器/入口/20秒截止保持；未推广默认Runner。前后端原ebdbf52a…/e1a74568…及原Pod UID保持，UP/Schema88、Ready重启0，节点无Memory/Disk/PIDPressure；34条指标与API/原进程一致，前端HTTP门槛通过，旧业务/预算/模型/配置摘要保持。

批次 `/root/eap-http-bytecode-20261022-r1` 于 `2026-10-22T02:27:11.354004+00:00` closed，原写入口禁止重放。4临时Pod按UID清理；172脱敏证据、15合同脚本、923应用输入、2运维文件及归档核验。归档121668字节/SHA256 `1711a10851841459f0f5ac677871e4e26b1a416a5490077c2754baa8fdca977c`；可用磁盘18145087488字节。[完整验收](evidence/http-bytecode-20261022/README.md)、[最终回执](evidence/http-bytecode-20261022/final-receipt.json)。

裁决CACHE_VERIFIED_WITH_INCONCLUSIVE_EAP_TASK / DONE_WITH_CONCERNS。下一步优先保留失败路径已到达READY的细分计时，然后测Worker CPU/I/O/镜像读取，避免继续猜测或扩大截止。Agent87历史根因及Agent90新超时的具体慢启动原因未解决；Agent87/88/89/90/91、Plan5/Plan6、旧28调用和全部关闭批次保持，不唤醒补证。通用MCP配置/UI、完整Kagent MCP执行、采样持久化/迁移事件、长稳/HA/恢复和更广OBI/TLS/OAuth仍待。



## Codex 进展：READY 后失败计时留存已部署验收（2026-10-22）

已修复固定HTTP夹具在READY后失败时丢失启动细分的问题：观察器在READY携带固定计时，Go收到并校验后立即写入原任务diagnostic，父Agent Span保留固定11属性；后端TraceMetadata读写同一白名单。旧版READY可省略startup，非法/缺失不补零，不输出私有字符串；20秒截止、UNKNOWN和同任务不重放保持。5应用文件、923输入。[功能合同](HTTP_READY_RETENTION.md)。

远程后端145类1323项全部通过，Go HTTP26顶层/63含子用例、原生完整62/139、stdio22/53、镜像变体1项竞态测试，以及Python63+变体5项、HTTP vet通过。旧版在超时留存和后端投影回归中按预期失败。首次误将普通模型用例放入禁用模型的HTTP夹具构建，产生MODEL_NOT_DISPATCHED；旧版重现后修正测试选择，失败证据保留。初次发布等待入口也在测试恢复前退出，尚未构建/部署；后续独立入口核验后推进。前端、浏览器、独立MySQL、完整Kagent和eBPF未重跑。全部应用测试/gofmt/构建/部署远程，无commit/push。

EAP新建Agent92/Workflow46正常样例：原Task `wf-exec-64dd2e65-af00-4644-8b40-3e655cf67150:1` SUCCEEDED，父Span11299ms、READY7046ms，观察器/服务导入3203/3201ms；8合成HTTP、6MCP/工具+1Agent、模型0。Agent93/Workflow47固定故障样例仅将原控制后3秒等待改为30秒：原Task `wf-exec-c3c26bf4-7bdd-4ac3-89cd-91b91b20d272:1` EXECUTION_UNKNOWN，父Span20016ms、READ_PROOF/deadline=true，READY7265ms、PROOF12735ms；**失败后仍保留观察器导入3103ms、服务导入3414ms等完整READY细分**。MCP/工具0、模型0，实际HTTP次数未由成功证明确认，不把缺失解释为0。两条任务各仅执行一次，四角色读取一致，原回执/占用保持，最终均SUSPENDED/REVOKED；各2条ASSIGNED位置，采样位置均ate-node2同Worker。故障注入只验证留存，不解释Agent87/90历史自然超时。

后端已部署 `sha256:4024798d6463ab96eb2c991aeae1c4614fcdbce7d92e243dac76ba861e9702a8`，Pod `backend-f687c8bd-wqwcx` / UID `0ac84f21-658d-42ba-83f3-185a5567228b`。正常专用夹具 `sha256:85fd4a8805040502ca4b00cd53588655a903814aa822c5e77acfd6f9ea7d8ba3`；固定故障专用镜像 `sha256:b005307f1df4bedc8c5aeea648d68be6b85dfe400fb135d65ad71e6aad97a782`，均继承缓存镜像16层、增加受测Go与观察器2层，不改默认Runner。前端e1a74568… / 原UID保持，Schema88、两端Ready重启0、节点无压力；34条采样指标与新后端进程/API一致、新鲜度13.243秒，旧指标和前端HTTP门槛通过，旧业务/预算/模型/配置摘要保持。持久位置记录共16条，非历史回填。

批次 `/root/eap-http-ready-20261022-r1` 于 `2026-10-22T03:28:12.469473+00:00` closed，全部写入口禁止重放。6临时Pod按UID清理；611脱敏证据、21合同文件及923应用输入本地核验，远程gofmt的2文件已同步，5受测文件已推广。归档1494101字节/SHA256 `27b4a10114565049eca94a561ff9c36388f039ba4a154c06ea4a55d1b326b159`；磁盘可用16803704832字节。[完整验收](evidence/http-ready-retention-20261022/README.md)、[最终回执](evidence/http-ready-retention-20261022/final-receipt.json)。

裁决HTTP_READY_RETENTION_VERIFIED / DONE_WITH_CONCERNS：诊断留存已交付；Agent87/90慢启动根因仍未解决，未证明稳定性能收益。下一步先只读盘点Worker CPU/节流、I/O和镜像读取指标，再设计固定镜像/资源的有界新样例，不扩大截止、不唤醒Agent87–93、不重派旧任务。前端细分展示、通用MCP配置/UI、完整Kagent MCP执行、采样持久化/迁移事件、长稳/HA/恢复和更广OBI/TLS/OAuth仍待。Plan5/Plan6、旧28调用、全部UNKNOWN和关闭批次保持。


## Codex 进展：产品交付改善第一批已部署验收（2026-10-22）

用户要求按产品审查建议逐条改善。首批已落实统一交付入口与版本/能力合同、只读部署检查和 CAS 升级计划；任务工作台汇集工作流/计划/独立 AX/本人聊天，支持历史分页与筛选；补充原执行下一步说明、结构验收结论、折叠配置溯源，以及 HTTP 阶段/截止/启动细分。权限仍唯一来自 PlatformPermission 与 `/me`，聊天仍限本人及原操作能力，没有自动重试/恢复/补偿。

人工对照发现并修复目录时间比原详情偏早8小时：JPA UTC 与 JDBC 东八区时间各按原语义读取/筛选，转换后合并；最多翻阅每次筛选前10000条，超出提示缩小范围。14应用文件、933受测输入。[功能合同](TASK_WORKSPACE.md)、[交付清单 D1–D9](../deploy/delivery/README.md)。

最终后端专项10、MySQL UTC/东八区各10、前端493及类型/生产构建、交付工具7项通过；旧实现负对照按预期失败1项。首版完整后端146类1332项通过，但**最后时间修复未重跑完整后端，不能冒称最终版全量通过**。四角色真实API核对80条/8页及原时间、日期边界、倒序与UNKNOWN；桌面/手机8组浏览器通过，6张最终截图人工核对。现场聊天记录0，所有者隔离与回执由隔离用例覆盖；未新增真实模型/业务调用。Go/Python/完整Kagent/eBPF未改未重跑。

首版登录脚本两次超时、初次时间修复H2派生表类型错误，以及前端构建Pod因DiskPressure在启动前被驱逐的证据均保留。13个结束临时Pod先按UID清理恢复空间，前端沿相同受测dist重建；总计17个临时Pod清理、剩余0。最终UP/Schema88、两端Ready重启0、节点无压力；34条采样指标与新进程/API一致，新鲜度43.512秒，旧指标新鲜。受保护业务/预算/模型摘要和全部原UNKNOWN保持，无旧任务重放或旧实例唤醒。

后端 `sha256:8a2d8424036eb467c26da40621d35004a659e4ccfc4c1977b80271f8609ff06d` / UID `d6165f2c-065f-4a06-b0f9-87b2a4c6cdd5`；前端 `sha256:6c233cc2d66ae9f07e7626ebef0c2020b692c9b5729a9e209b2f91be1eff5240` / UID `75a2decb-6abd-49a3-bbb9-b33c46880c08`。默认Runner与Kagent镜像、Schema及权限定义保持。最新交付版本为 `private-pilot-20261022-product-delivery-r2`。

远程 `/root/eap-product-20261022-r2` 于 `2026-10-22T07:35:03.468793+00:00` closed，r1以superseded关闭；**所有原入口禁止重放**。最终933输入已推广 `/root/eap-src`，本地核验620份脱敏证据及应用源码。归档11811889字节，SHA256 `a940be9217ee417d07db10c36f0269e40449b2602521ce8a23f5ae6b693ca65e`；磁盘可用18489835520字节。[完整验收](evidence/product-delivery-20261022/README.md)、[最终回执](evidence/product-delivery-20261022/final-receipt.json)、[最新源码清单](evidence/product-delivery-20261022/tested-source-manifest.json)。无commit/push；本机没有运行应用测试或构建。

**尚非全部产品化完成。** 下一批优先 D1 全新环境安装自动化、干净基础镜像构建/层精简和独立实施验收，随后 D6 团队计划结果契约入口。D2 首个真实业务系统待用户指定；D3负责人分派/处置回执与通用恢复补偿、D7项目范围授权、D8 TLS/HA/整体灾备、D9容量长稳和历史慢启动根因仍待。当前是有实施支持的私有化试点，不宣称生产交付完成。新工作以本节的源码与两固定镜像建立独立批次，保留Plan5/Plan6、Agent87–93、全部UNKNOWN和关闭批次。



## Codex 进展：固定基础镜像交付与层精简已部署（2026-10-22）

按产品改善清单继续完成 D1 的受测产物构建路径。新增 `deploy/delivery/image_build.py`、固定基础镜像清单及两份模板，核对完整产物集合、摘要、路径、链接、容量与外部固定的上下文回执。远程 Kaniko 从原 JRE/nginx 基础层新增一层，不继续叠加旧应用版本。应用 933 输入和原受测 JAR/dist/nginx 配置保持；不是新业务功能发布，也不是全部产品化完成。

后端由68层/5403300666字节降至7层/203187283字节（压缩层合计减少96.24%）；前端由79层/67810918字节降至9层/22072362字节（减少67.45%）。运行配置与原镜像相同。没有删除旧发布/回退镜像或执行仓库GC；体积下降不代表同量磁盘已释放，也没有证明启动或长稳性能收益。

最终远程交付工具21项通过（原7+新增14）；基础层digest、JAR及72静态文件+nginx配置逐项核对。非root后端探针、前端独立和现网HTTP通过。四角色真实PKCE/API每角色80条/8页、权限、原UNKNOWN和时间戳通过。34条采样指标与新后端进程/API一致，新鲜度23.288秒，旧指标新鲜。原任务/工具/预算/模型/责任/观测11表摘要保持；业务写入0、模型0。完整应用、独立MySQL、浏览器、Go/Python/Kagent/eBPF未重跑；沿用相同受测产物，不能宣称本轮全量业务回归通过。

首次预检错误Accept造成500、首个候选因 `--reproducible` 改写基础层摘要被拒、前端删除标记被误判额外资源、Python3.10拒绝纳秒时间戳及过早收尾被缺失指标证据阻止，均保留并修正。首候选未发布；前端校验修正没有重建。5临时Pod按UID清理、剩余0；最终UP/Schema88，两端Ready重启0，两节点无压力。节点可用18056368128字节，imagefs驱逐阈值以上余量2312231116字节，后续仍须预检。

已部署后端 `sha256:51869b881cde3be0fe22fb9e5e6068bc331063f89d213dfb80eabc9b6ccd02ab` / Pod UID `2485cae9-c6a9-4788-aa52-4e431b778856`；前端 `sha256:d6e2cffe3dd06d34dc9a3fc3cf82b345c077361c02b6154a405944222bfbdee5` / UID `6fd2b64a-4e7f-4bfb-9e05-1db2a0316219`。定向CAS发布，后端Recreate后恢复原策略；默认Runner、Kagent及其他非镜像配置保持。版本 `private-pilot-20261022-clean-images-r1`，远程 `/root/eap-src` 已安装受测交付代码/版本清单。

批次 `/root/eap-clean-images-20261022-r1` 于 `2026-10-22T08:54:17.199755+00:00` closed，所有写入口禁止重放。归档136198字节/SHA256 `86ec87a183edfb7315bffb6534bb36234fe868c00cfbcbd0a5fa3ab6dabfd474`；本地核对91份脱敏证据、933应用输入和7份受测交付代码。[完整验收](evidence/clean-images-20261022/README.md)、[最终回执](evidence/clean-images-20261022/final-receipt.json)、[源码清单](evidence/clean-images-20261022/tested-source-manifest.json)、[交付构建方式](../deploy/delivery/images/README.md)。无commit/push，本机仅编辑、打包和摘要核对。

**下一项：D1全新环境安装自动化、完整发布流水线和独立实施验收，随后D6团队计划结果契约入口。** D2首个真实业务系统仍待用户指定，D3负责人/处置回执与工具恢复补偿、D7项目范围授权、D8 TLS/HA/整体灾备、D9容量长稳及历史慢启动根因继续保留。镜像签名/SBOM和基础镜像安全更新也待。保留Plan5/Plan6、Agent87–93、全部UNKNOWN和所有关闭批次，不重放旧任务。


## Codex 进展：JWT 控制台首次安装包已完成隔离验收（2026-10-22）

按产品改善清单推进 D1，新增 `deploy/delivery/bootstrap.py`、15 项回归用例、配置示例及安装说明，更新交付入口。安装包绑定外部摘要、固定镜像、Namespace UID/安装标识和 Secret 引用；服务器 dry-run 后，由一次性 Job 验证连接与空库，再仅 create 5 个应用资源。保存每次写入意图，不覆盖、不收养、不自动重试；应用重启不重复空库检查。[安装说明](../deploy/delivery/install/README.md)。

**远程验收通过：** 交付工具36项（原21+新增15）、独立空库 Flyway 到88/失败迁移0、四角色真实PKCE/API、五类拒绝场景（错误UID/已有应用/重复回执/缺键/非空库）、72静态文件及实际imageID、桌面/手机8组浏览器、4张截图人工核对。仅在新库经EAP创建1个合成草稿Agent；替换该环境backend与MySQL Pod后UID改变，原PVC/PV及记录保持。运行派发0、模型0、现网业务写入0。API创建响应纳秒与MySQL读回微秒的差异已记录，未改业务代码、未重发创建。

首次Secret键名模板转义失败、浏览器手机定位误选隐藏桌面文字、收尾误以为规范目录已有README，均保留原因与失败证据并修正。最终5交付文件已推广 `/root/eap-src/deploy/delivery` 并与本地一致；应用933输入和两固定镜像保持，不是现网应用升级。未重跑完整应用测试/构建、Runner、完整Kagent、eBPF、容量长稳或整体灾备。原前后端Pod UID、配置/Secret身份及11张业务表摘要保持；Plan5/Plan6、Agent87–93、全部UNKNOWN与旧关闭批次不重放。

测试工作负载、两个新命名空间、测试Secret和PVC均已按UID清理，合成PV已回收。最终原系统UP/Schema88，两节点无压力；ate-node1可用17475026944字节，imagefs阈值以上余量1730889932字节，后续仍需预检。远程批次 `/root/eap-install-20261022-r1` 于 `2026-10-22T09:54:35.448449+00:00` closed，所有写入口禁止重放。归档1945478字节/SHA256 `d19b108fda394a5cb467c67f7f06e137a9e1c7a3c997cd546ab87f350384592d`；本地核验153导出文件、5交付文件及933应用输入。[完整证据](evidence/bootstrap-install-20261022/README.md)、[最终回执](evidence/bootstrap-install-20261022/final-receipt.json)。无commit/push，本机未运行应用测试或构建。

**范围仍为 CONTROL_PLANE_BOOTSTRAP。** 数据库、IdP由实施人员准备，Substrate/网关/模型/派发关闭；本次是同一集群的新命名空间/空库，不是独立客户集群或独立实施验收。安装器无自动续装/卸载/数据库回退；空库与资源创建非原子事务，必须独占目标环境。`kubectl`仍需get Secret权限，只将键名交给Python，不声称服务端字段权限隔离。生产TLS、完整运行系统接入、备份恢复/HA、发布流水线及签名/SBOM仍待。下一步可继续D6团队计划结果契约；D2真实业务系统待用户指定，D3处置责任、D7范围授权、D8/D9和历史慢启动根因继续保留。


## Codex 进展：代码规模统计（2026-10-22）

按用户“统计一下代码”的要求，只读统计当前工作区，包含未提交/未跟踪源码。7个源码根目录共998文件、111493物理行、102531非空行；其中测试270文件/31469物理行（28.23%），非测试728文件/80024物理行。非空行仍含注释，不冒称排除注释的SLOC；测试文件数不等于用例数。排除文档、配置/lock、依赖、构建产物、缓存、录像及历史证据归档，按路径计数。逐文件SHA256和模块/语言汇总交叉核对通过。[完整统计](evidence/code-statistics-20261022/summary.json)、[逐文件明细](evidence/code-statistics-20261022/files.csv)。

未改业务代码、未构建/测试/部署，未commit/push；本机仅做只读文件计数和报告写入。上一节安装交付结果保持，主线待办仍包括D6团队计划结果契约，以及D1完整运行系统接入和独立客户环境验收。


## Codex 进展：团队计划结果契约已部署（2026-10-22）

D6已加入计划步骤结果契约配置/清除/只读查看、提交冻结、生成工作流快照与结构拒绝后的下游停止；V89仅新增可空列。成员分配与契约修改先锁父计划再当前读步骤，避免并发覆盖。前端按 `/me` 能力控制，不按角色名授权。

远程前端499/类型/构建、独立MySQL17通过；后端完整覆盖1341/146类，修正仅测试夹具后重跑权限37与计划16项，汇总0失败，未重新跑最终测试文件版本的完整后端。生产源码自完整运行后未变。四角色API与桌面/手机8组浏览器通过，仅新增未执行Plan7与2步骤、浏览器保存一次契约；模型/运行派发0，旧11表/全部原计划及配置Secret身份保持。15临时Pod按UID清理。完整失败历史、边界与摘要见[验收报告](evidence/plan-output-contract-20261022/README.md)。

后端f35931a6…、前端c14842cb…、Schema89，936应用输入/20应用文件推广；版本private-pilot-20261022-plan-contract-r1。远程/root/eap-plan-contract-20261022-r1于2026-10-22T15:03:20Z closed，禁止重放所有原写入口。归档2871546字节/SHA25611438088252f04b77a4d83ecae5b084a815b8c10f5661b6fb411d88a707a7964，813导出文件本地校验。

用户随后要求安装UI技能并用于前端，补充Impeccable参考。已安装frontend-design、web-design-guidelines、vue-best-practices，[固定来源与摘要](evidence/frontend-skills-20261022/README.md)；没有安装已移出官方目录的frontend-skill，也没有安装Impeccable CLI或发送私有源码。独立视觉改版批次/root/eap-plan-design-20261023-r1正在远程测试，当前修改4个前端文件，尚未声称上线。D1完整交付与独立客户集群、D2实际业务、D3处置责任、D7范围授权、D8/D9和历史慢启动根因仍待。保护Plan5/Plan6、Agent87–93及全部UNKNOWN；无commit/push。


## Codex 进展：计划工作区改版交付与 operator 登录核验（2026-10-23）

团队与计划工作区按现有控制台风格完成改版：桌面计划列表/详情分栏、手机同一 DOM 重排，有序步骤内聚合成员分配与结果契约，中文状态、读取错误及请求竞态保护。4 份前端文件、936 受测应用输入，无新依赖或后端变更；设计使用已安装三个技能和 Impeccable 公开参考。[完整验收](evidence/plan-workspace-design-20261023/README.md)。

远程前端503项、类型/生产构建、四角色桌面1440/手机390共8组真实PKCE及页面验证、3项边界检查通过，5张截图已核对。仅浏览器模拟长文本/UNKNOWN/503，业务写入0、模型0。后端未重跑，保持D6镜像f35931a6…及Schema89；前端8a124d63…，版本private-pilot-20261023-plan-design-r2。两端Ready重启0、UP；旧11表/团队/计划/步骤与配置Secret身份保持。5个临时Pod按UID清理，936输入核对后推广4份前端源码；无commit/push，本机没有运行应用测试或构建。

r1手机长团队名浮层溢出已修复并以superseded关闭。r2的重复定位器、外框箭头宽度误判、临时浏览器到期、空脚本传输和诊断语法错误均保留；修正验收脚本后重新验证，未覆盖失败记录。另一次等待登录表单超时发生在输入密码之前，后续未复现，根因未确定。

针对用户反馈operator无法登录，现有Secret密码认证和/me为200，最终桌面/手机真实浏览器均登录成功；剪贴板与当前密码不同，已只在内存中重新复制，没有重置密码或保存凭据。用户原错误提示未确认，不能把剪贴板差异当作已证实根因，也不宣称偶发超时已根治。

远程/root/eap-plan-design-20261023-r2于2026-10-23T02:09:49Z closed，禁止重放原写入口；归档1100169字节/SHA256475db9fb44fd792d0c6c6684b17e0e7732895ac8077ff1913289b58a4e14c84a，121份导出文件。D1完整运行系统/独立客户验收、D2真实业务、D3处置责任、D7项目隔离、D8/D9及历史慢启动根因仍待。Plan5/Plan6、Agent87–93、全部UNKNOWN和旧关闭批次保持。


## Codex 进展：控制台24页第一轮优化已部署验收（2026-10-23）

控制台全部24个路由完成第一轮逐页优化与验收。四页原生分区导航、具名筛选/分页、手机任务记录、响应式弹窗、任务管理能力与异步确认保护、伦理真实计数/读取失败，以及统一标题和指标样式已部署；成熟页面保留结构。使用frontend-design、web-design-guidelines、vue-best-practices，延续Impeccable参考。[逐页清单](CONSOLE_PAGE_OPTIMIZATION.md)、[完整验收与截图](evidence/console-pages-20261023/README.md)。

最终前端510项、类型/生产构建通过。candidate-2完成四角色×24路由×桌面1440/手机390，共192次页面检查、172项交互、8次真实PKCE登录；人工截图发现监控筛选仍过窄，candidate-3仅加强3处手机监控CSS选择器，其他938输入保持，重新通过510项/类型/构建，并补测8次监控及2次分页。不得宣称192次全量检查在最终镜像重新执行。24页代表截图已复核；不等于所有业务写入、嵌套状态或完整可访问性验收。

版本private-pilot-20261023-console-pages-r1，最终前端fb3e2e14…，后端保持f35931a6…、Schema89；UP、两端Ready重启0、节点无压力。939应用输入核对后推广24份前端文件，11张业务表/原团队计划步骤及配置Secret身份保持；业务写入0、模型0，11临时Pod按UID清理、剩余0。现有localhost:5173入口返回buildId2026-10-23T03:46:26.614Z。本机未运行应用测试/构建，无commit/push。

首轮不支持的确认框zIndex、浏览器辅助调用错误、抽屉动画断言、实际成本网格溢出、监控选择器覆盖和一次镜像入口导入错误均保留并修正。首次收尾因临时Pod尚未退出而停止，后续只读确认清空后完成，没有强删或绕过检查。远程/root/eap-console-pages-20261023-r1及candidate-2/3于2026-10-23T03:55:50.271354+00:00关闭，原写入口禁止重放；归档21344299字节/SHA256 d90ca16c835e2eef2e4e8d3d9a907cdd6926612e3c679e05c2f92f940a48a7fb，375文件本地核验。

后端、独立MySQL、Runner、完整Kagent/eBPF及长稳未重跑；Plan5/Plan6、Agent87–93、全部UNKNOWN和旧关闭批次保持。UI后续可深化高密度表格的手机操作、长内容/异常/空状态与屏幕阅读器检查。D1完整运行系统和独立客户交付、D2真实业务、D3处置责任、D7项目范围授权、D8/D9及历史慢启动根因仍待。


## Codex 进展：完整回归、时区修复与 GitHub 首次发布（2026-10-23）

按用户“继续完整测试”“验证后上传 GitHub”以及“README 要好好写，产生系统图片”完成本批次。公开产品源码、README、接入说明、验证边界和系统总览图已发布到 https://github.com/IDDS/EAP 的 main，提交 `838d6c0c87cae46e72edcc87315549f55ef4b07d`。逐文件核对远端 1,037 个 blob 与公开候选一致；公开交付工具另复验 36 项，实际集群凭据仅在内存中比对，匹配 0。没有向 GitHub 推送原 Git 历史、内部证据、业务计划书或录像；原工作区及 Gitee origin 保持。此次 commit/push 来自用户明确上传授权。

README 重写为产品概览、架构、远程开发预览、JWT 安装、双 Agent 样例、权限与能力边界；新增 GETTING_STARTED、VALIDATION 和样例指南。使用 imagegen 内置工具生成 docs/images/eap-system-overview.png，已检查并嵌入 README，完整提示词与边界见 docs/images/README.md。公开清单、AX Dockerfile 和一处 Java 注释中的内部仓库地址改为 registry.example.com，摘要保持，变换记录在 SOURCE_PROVENANCE.json。没有承诺公共镜像已发布或擅自指定项目许可证。

本轮发现并修复 MySQL +08:00 会话中两处普通 JDBC 读取 JPA UTC 时间导致的八小时时差：预算误拒新工作流、阶段报告误标 UNCONFIRMED。BudgetLedger、WorkflowSchedulingService 按 UTC Calendar 读取，GatewayException 将派发前 workflow_not_running 标为 REJECTED；新增真实 MySQL 三项先红后绿。原失败 Plan8/Workflow48 保持 EXECUTION_UNKNOWN，未重放、未改写。

最终受测修复版：后端完整 1,341 项/146 类与打包通过；前端 510 项、类型与生产构建通过；最终镜像四角色×24页×桌面/手机 192 场景通过。Python 181 项；Go 原生 62/139、Kagent 70/170、MCP HTTP 26/63、stdio 22/53、AX 21/21、demo 8/8、发现探针 34 项的竞态/vet/构建通过，发现组件 amd64/arm64 构建通过。MySQL 共 468 项为分阶段验收：16 类基线及修复后独立库 16+32 项、时区3项，不能称19类在最终源码统一重跑。MCP夹具选择、旧隐式会话兼容断言、共享MySQL串扰、复制错目录、脚本字段和推广顺序等失败均保留，修正后通过。

修复后的 Plan9/Workflow49 首步成功并完成结构校验，已有全局 LEGACY UNKNOWN 导致 SKIPPED_BUSY，自动休眠被阻止并停止下游；保留失败状态与输出，不清除历史围栏。另建 Plan10/Workflow50，用原生 Agent98 与 Kagent Agent99 执行11+13再核验，关闭自动挂起并通过 EAP 显式收尾；输出 sum=24、verified=true，结构/两次调用/10输出Token结算/四角色内容200或403对照通过。本轮共6新Agent94–99、3次模型调用/15输出Token/工具0，全部实例最终SUSPENDED且连接REVOKED。旧11表原行、原团队计划步骤、配置及Secret身份保持。

发布版本 private-pilot-20261023-full-regression-r1；后端 e55754a1…，前端 fb3e2e14…保持，Schema89。940规范应用输入核对，5源码/测试文件及发布清单推广 /root/eap-src。最终UP，两端Ready重启0，两节点无压力；ate-node1可用19243876352字节，imagefs驱逐余量3499739340字节。11临时Pod和1ConfigMap按UID清理，剩余0；只删本轮重复构建副本约296MB，旧回退镜像和闭合批次未改。本机没有应用测试、编译或构建。

远程 /root/eap-full-regression-20261023-r1 于 2026-10-23T06:24:31.984128+00:00 closed，原入口禁止重放。归档16014216字节，SHA256 5768f6419c2d7ca13bc21d2992f7f806953b5eaf2e7bcd03b88cc22c3a3db589；本地逐项核验1280导出文件。详见[完整证据](evidence/full-regression-20261023/README.md)、[最终回执](evidence/full-regression-20261023/evidence/final-receipt.json)、[GitHub发布核验](evidence/full-regression-20261023/publication-proof.json)。

后续优先：历史全局 UNKNOWN 的归属确认与有证据的回收处置、完整运行系统安装及独立客户验收；继续D2真实业务、D3责任/恢复补偿、D7项目隔离、D8 TLS/HA/整体灾备、D9容量长稳与慢启动根因。保留全部旧 UNKNOWN、Plan5/6/8/9与Agent87–99，不重放原任务。仍为有实施支持的私有化试点，不能宣称生产交付完成。公开快照位于 /tmp/eap-public-20261023-candidate，后续发布应继续明确的公开文件清单，不直接把内部工作区历史推往 GitHub。


## Codex 进展：README 详细架构图更新（2026-10-23）

用户要求已有 README 图片更详细。使用 imagegen 内置编辑工具，以原图为风格参考生成详细版，并校正观测回流方向和数据库连线。新增 docs/images/eap-system-architecture-detailed.png（1448×1086，1850074字节，SHA256 5841970055b940e40a7ca58c172c46df678036f5a04d2ca6418ea7917196a600）；README 改为详细图并提供原图/简版链接，旧图保留。图中包含九组控制平面能力、三类运行适配、五类发现观测入口和六步任务链路，仍明确 Kagent 部分集成、MCP 观测需配置、eBPF 网络证据及 UNKNOWN 回收限制。完整生成和两次定向校正提示词见 docs/images/README.md。

沿用既有 GitHub 发布授权，仅更新公开快照四个文件（README、图片说明、新PNG、SOURCE_PROVENANCE），正常推送 main 提交114a3dddc599374fbb306d7a85173bde9b766138。1038个远端文件与候选逐项比对、文档链接及git空白检查通过。没有业务代码变更，不涉及应用测试、构建或部署；前一节完整回归结果保持。原工作区Git历史/Gitee origin及关闭批次保持；核验记录见[图片更新证据](evidence/readme-architecture-20261023/publication-proof.json)。


## Codex 进展：README 改为详细产品与技术说明（2026-10-23）

按用户最新要求，README 移除测试与验证章节、测试数字、测试命令和验证报告入口；原验证文档及证据保留。README 扩写为347行/22617字节，覆盖产品定位、适用场景、资产/团队/任务/预算/治理/观测/评测能力、核心技术、结果契约样例、部署接入与控制台导航。技术说明对照实际PlatformPermission、执行快照、预算事务、实例身份配送和观测代码；保留Kagent/MCP/eBPF及UNKNOWN范围。已有详细架构图保持。

沿用用户上传授权，公开快照仅README与SOURCE_PROVENANCE变更，GitHub main最终ae87d68d1871a5041c27b5310d8ba4018e756b37，README远端blob737153e262ef1fbdbab36dcb6e4ca4ca4805b878与本地一致。文档链接、围栏与空白检查通过；无业务代码或部署变化，不运行应用构建/回归。用户另要求介绍系统的推文并包含核心技术，交付对话中的产品技术介绍稿，不向社交平台发送。图更新与完整回归的历史记录保持。

## Codex 进展：自动休眠提交前检查已部署验收（2026-10-23）

本轮完成工作流自动休眠的提交前检查、物理 Actor 准入锁内复查，以及计划只读 `GET /api/orchestration/plans/{planId}/reclaim-readiness`。全局历史/同 Actor ACTIVE或UNKNOWN、回收未确认及AX生命周期归属会提前阻止要求自动休眠的新执行；原幂等请求先返回原记录，未要求自动休眠的普通执行保留原准入。前端按 vue-best-practices 拆出检查状态和展示组件，逐步骤说明原因，读取失败/切换计划/步骤编辑/账号变化使旧结果失效，服务端明确拒绝与提交未确认分别提示。预检查不构成资源预留；历史UNKNOWN未清理，不新增通用解锁入口。

远程后端完整1348项/146类及打包、前端517项/类型/生产构建通过；真实MySQL独立库计划19项、工作流34项通过。最终四角色×桌面/手机8场景，89条通过断言+8条控件语义记录，8次真实PKCE、新接口四角色GET及匿名401通过；另2张实际视口复核。边界状态为浏览器模拟的未提交计划，生产业务写入0、模型0、工具0。运行系统、全量24页面和供应商真实执行本轮未重跑，不能把此前全栈结果合并称为当前统一复验。

版本 `private-pilot-20261023-reclaim-preflight-r1`，后端 `cce7f493f68feee4a03679615af54a19dd4e1c26de3f73d98ee9149eb511fe7c`，前端 `a22213751acae5acb43475a82f60f15a08a3c7137156ee1b63f9178399b2178f`。Schema89，UP，两端Ready重启0、节点无压力。886份前后端输入逐项核对，12份变更及发布清单同步 `/root/eap-src`；11张原业务表、原团队/计划/步骤和配置及Secret身份保持。8个临时Pod和1个ConfigMap按UID清理，剩余0；ate-node1可用18760269824字节，imagefs驱逐余量3016132812字节。本机没有运行应用测试/构建，没有git commit/push，GitHub仍为此前公开版本。

前端测试/镜像暂存回执同名导致一次发布中止，保留原回执后仅续发尚未暂存的前端镜像。一次后台shell浏览器等待入口写成空文件，未执行浏览器。首轮复选框自动化定位到尺寸为0的原生输入而超时；可访问名称存在，后续用可见标签交互并核对原生状态通过。手机整页截图另补拍实际视口。原失败报告和日志保留，没有重跑业务或放宽应用保护。

远程 `/root/eap-reclaim-preflight-20261023-r1` 于2026-10-23T08:59:06.153129+00:00关闭，原写入口禁止重放。归档2114631字节，SHA256 `0d42c786a2718d13458ff0fb666b75b21e7c132d47a1576c626960a45822c4e9`，440份导出文件本地全部核验。见[验收记录](evidence/reclaim-preflight-20261023/README.md)、[最终回执](evidence/reclaim-preflight-20261023/evidence/final-receipt.json)与[回收契约](WORKFLOW_RUNTIME_RECLAIM.md)。README继续保留产品与核心技术说明，不添加测试章节。

下一步仍是历史全局UNKNOWN的归属确认与有证据的处置、完整运行系统安装及独立客户验收；继续D2真实业务、D3责任/恢复补偿、D7项目隔离、D8 TLS/HA/整体灾备、D9容量长稳与慢启动根因。保留Plan5/6/8/9、Agent87–99和所有关闭批次，不重放旧任务。当前仍为有实施支持的私有化试点，不宣称生产交付完成。

---

## Codex 进展：排队后的实例协议变化保护已部署（2026-10-23）

要求自动休眠的工作流排队后，原登记改成HTTP或离线Actor被HTTP实例接替时，现在会在派发前记录当前绑定及“未派发”原因，以FAILED结束并停止下游，避免跳过冻结的回收要求。原请求标识返回原执行；未要求自动休眠的普通任务、AX生命周期保持。仅2个应用/测试文件变更，新增3项回归，无数据库迁移、依赖或权限变更。

旧实现3项负向复现中预期2项失败。修复版远程完整后端1,351项/146类及打包、独立MySQL计划19项/工作流37项通过，四角色真实PKCE及/me、计划只读检查200，匿名401。首次完整回归有1项既有放置观测测试收到DEADLINE_EXCEEDED而非ABORTED；耗时8.266秒，底层延迟原因未确认。保留原失败，在新隔离Pod中相同源码/设置的7项定向和完整回归通过；未放宽断言或超时。原发布等待超时未部署，恢复入口另建。随后磁盘余量门槛再次中止发布，在仅清理七天前归档系统日志和apt下载缓存、恢复原余量要求后继续；本轮证据、当前日志和回退镜像保留。

版本 `private-pilot-20261023-runtime-drift-r1`，后端 `sha256:3c12cba096eae00ee259627a6ca6d86caf7876e502f38b30ea778108a2567bfe`；前端保持a2221375…、Schema89。UP、两端Ready重启0、节点无压力。886份前后端输入核对，2份变更及发布清单同步/root/eap-src；原11表/团队计划步骤和配置Secret身份保持，生产业务/模型/工具新增0，4个临时Pod清理完毕。本轮未重跑前端、浏览器及运行系统，历史517项前端结果不算本轮复验；本机无应用测试/构建，无commit/push，GitHub版本保持。

远程 `/root/eap-runtime-drift-20261023-r1` 于 `2026-10-23T15:45:36.325399+00:00` 关闭，禁止重放写入口。归档1920302字节，SHA256 `90b82e8e52184b565c4ad0b4a82aef14c5bbc7b671fe2d3c6cc6789ef648a559`，701份导出文件核验。[完整记录](evidence/runtime-drift-20261023/README.md)。README仍只写产品与核心技术，不添加测试章节。

后续仍需历史全局UNKNOWN的证据归属与处置、完整运行系统安装/独立客户验收、项目隔离、TLS/HA/灾备及容量长稳；并关注既有位置观测测试的偶发超时。保留旧UNKNOWN、Plan5/6/8/9、Agent87–99及已关闭批次，当前不宣称生产交付完成。

## Codex 进展：用户授权本地提交（2026-10-23）

用户明确要求“提交”，已将两轮已验收的自动休眠提交检查、派发前协议变化保护及其控制台、测试和文档整理为本地提交 `9f568936753ae499d7903460e4cfef5f76fed420`（16文件，553行增加/37行删除）。基于此前GitHub公开快照ae87d68，提交位于 `/tmp/eap-public-20261023-candidate` 的main，并已通过本地fetch保存为本仓库独立分支 `release/reclaim-guards-20261023`。当前feat工作分支、原暂存区和历史工作区改动保持；未向GitHub或Gitee推送，远端仍为此前版本。

核对1042份提交树文件与公开候选一致，885份可直接对照的前后端输入与最终远程受测清单相同；另1份ImageBuilder仅保持此前公开登记地址注释转换。公开清单、文档链接、空白和敏感信息模式检查通过；没有新增应用代码改动，因此未重跑应用测试或部署。内部证据、业务计划书和历史运行环境不进入公开提交；Root README保持产品与核心技术说明。[提交核验](evidence/reclaim-commit-20261023/commit-proof.json)。

后续推送如获用户授权，可从该公开历史分支执行；不要推送当前feat分支的内部历史或重放已关闭的运维批次。

## Codex 进展：GitHub 与 Gitee 均已推送（2026-10-23）

用户明确要求“github和gitee都得提交”，已将本地已验证提交 `9f568936753ae499d7903460e4cfef5f76fed420` 正常推送到 GitHub `IDDS/EAP` 的 `main`（ae87d68→9f56893）和 Gitee `wallemit/eap` 的新分支 `release/reclaim-guards-20261023`。两边通过git ls-remote核对为同一完整SHA。Gitee与公开快照使用不同历史，原main（5e62625）和feat/galileo-evaluation-foundation（71e4111）保持，未强推或替换旧历史；Gitee最新源码应从新发布分支读取。

推送仅含此前核验的1042份公开文件和16文件增量，原工作区、暂存区及Gitee origin配置保持。没有改业务代码、重新测试或部署，也没有发布内部证据及历史工作区内容。[推送核验](evidence/reclaim-commit-20261023/push-proof.json)。先前“未推送”的记录为历史状态，以本节为准。


## Codex 进展：工单并发更新保护已部署验收（2026-10-24）

修复重叠事务同时认领同一事项、旧信号快照覆盖已提交负责人/状态的问题。工单加入JPA版本检查，人工状态修改先flush再写事件及审计，冲突事务整体回滚并沿用409刷新提示，不自动重试。V90只新增row_version并初始化存量记录；共6份应用/测试文件变更，权限规则和前端保持。

旧实现3项复现中2项按预期失败；修复版远程完整后端1,355项/147类与打包、H2专项4项、独立MySQL4项通过，失败/错误/跳过均0。专项包括真实并发认领、旧信号实体、409提示和带存量工单/事件的89→90迁移及重复迁移。H2专项已包含在完整回归中。最终四角色真实PKCE、/me与工单查询200；不存在编号的写请求按能力分别403/400，匿名401，未创建或流转真实工单。历史放置观测偶发超时本次未出现，根因仍待。

版本private-pilot-20261024-workitem-concurrency-r1，后端523e4314…、前端保持a2221375…、Schema90，UP、两端Ready重启0、节点无压力。889份前后端输入与受测源码核对，6份变更及发布清单推广/root/eap-src。原11表/团队计划步骤、工单身份与人工处理字段以及配置Secret身份保持；生产任务/模型/工具新增0。3个临时Pod按UID清理，剩余0。节点可用18774876160字节，imagefs驱逐余量3030739148字节。本机无应用测试/构建，无本轮commit/push；GitHub main与Gitee发布分支仍为前次9f56893。

远程/root/eap-workitem-concurrency-20261024-r1于2026-10-23T23:46:27.284213+00:00关闭，原写入口禁止重放。归档1082657字节，SHA25613307b04c55cb69f8a44e554a10cc81a9f7d8428e0ba232216eb574ee559e6ca，384份导出文件核验。[完整记录](evidence/workitem-concurrency-20261024/README.md)、[AgentOps并发契约](AGENTOPS_PHASE1.md)。

边界：乐观锁只保护重叠的EAP事务，不是旧浏览器表单的版本令牌，也不覆盖直接SQL写入。前端/浏览器、运行系统、供应商执行及长稳未重跑，历史517项前端不能算作本轮复验。下一步仍需执行结果到工单的责任分派/回执、历史UNKNOWN有证据的归属处置、完整运行系统安装与独立客户验收、项目隔离、TLS/HA/灾备及容量长稳。保留旧UNKNOWN、Plan5/6/8/9、Agent87–99与所有关闭批次，不宣称生产交付完成。README继续只写产品和核心技术。

## Codex 进展：eBPF P0 第一批已部署验收，采集器已打包（2026-10-24）

完成连接尝试/未知测量契约、有界缓存、单次异步上传、指数退避、严格回执及同键更新保护、固定错误类别和本机健康计数；修复TextIO预读导致真实管道突发事件滞留。host-observation后端识别CONNECT_ATTEMPT并兼容已知1.0.0采集器，不再把未测量字节写成0。共5份应用/测试/配置文件修改，BPF程序、权限、前端和数据库结构保持。使用investigate流程先复现再修改。

远程旧Python3项预期失败、后端3项中2项预期失败、真实管道1项预期失败均保留。最终采集器21项与相邻Python接入4项、完整后端1,360项/148类及打包通过，失败/错误/跳过均0。契约5项包含在完整回归中，中间Python19项不重复计数。四角色真实PKCE、/me和发现查询通过，匿名401；新观测类型可解析，缺少机器凭证仍拒绝，未知类型拒绝。首次验收工具误将401空响应按JSON解析，原release.log保留；修正验收工具后从验收继续，没有改应用或重复部署。

版本private-pilot-20261024-ebpf-p0-r1，后端2fc2361d6a786de6b345d9eac013cef811f26fd25f2e2dc9cb24d44de4b6c78f，前端保持a2221375…，Schema90。UP、两端Ready重启0、节点无压力。898份规范输入（890前后端+8采集器/相邻Python）逐项核对，10份后端/采集器文件及清单推广/root/eap-src；其中含首次纳入的未修改采集器输入。原11表、团队/计划/步骤、工单身份与人工字段、配置及Secret身份保持，额外4张发现表摘要保持。生产任务、模型、工具及发现记录新增0。3个临时Pod按UID清理，剩余0；节点可用18173837312字节，imagefs驱逐余量2429700300字节。

1.1.0覆盖包7307字节，SHA256 fd5df881d3aa988a6ad76b96f9737ec5008e52e8dc07f42eb5727e29285ac14b。PoC有BTF但缺bpftrace，已验证UNAVAILABLE/BPFTRACE_MISSING；采集器未启用，也未替换其他宿主上的服务。包只含Python适配器、原BPF、配置示例和manifest，不含凭证或安装器。健康状态只写本机日志；没有集中上报、内核丢失计数、持久补投或恰好一次保证。连接结果、RTT、字节和工具语义仍未由此探针测量；早期独立eBPF聚合入口契约未改。

远程/root/eap-ebpf-p0-20261024-r1于2026-10-24T00:52:32.942600+00:00关闭，所有已执行入口禁止重放。归档1120543字节，SHA256 94da0ea3bd605924d7a158622410644106f91d352f0ddc006c3e72add8fa7d1b，407份导出文件核验通过，实际集群凭据扫描匹配0。发布清单已同步本地。[完整记录与采集器包](evidence/ebpf-p0-20261024/README.md)、[契约说明](EBPF_OBSERVER_RELIABILITY.md)。本机无应用测试/构建，本轮未commit/push，公开GitHub/Gitee仍为前次9f56893。

未重跑前端/浏览器、MySQL专项、Go全栈、OBI/gVisor或容量长稳。下一步eBPF优先补集中健康、过期与丢失可见性、旧聚合协议未知测量语义，再推进进程代际/CRI/Kubernetes身份、连接结果/字节、有界OBI协议与gVisor原生监控实验。主线仍需工单责任分派/回执、历史UNKNOWN证据处置、完整运行系统安装与客户验收、项目隔离、TLS/HA/灾备及容量长稳。保护Plan5/6/8/9、Agent87–99与所有关闭批次，不清除UNKNOWN，不宣称生产交付完成。根README继续仅写产品与核心技术。

## Codex 进展：真实 eBPF 采集已启用并保持运行（2026-10-24）

用户明确要求“启用”。已在ate-node1安装bpftrace0.14及依赖（共5个新包，不升级系统），采集器1.1.1以eap-ebpf-observer.service常驻，active/running/enabled、NRestarts0，服务组内bpftrace持续挂载。机器凭证经EAP登记后直接写入集群Secret eap/eap-ebpf-observer，启动器从原Secret读取，经匿名管道传给--credential-fd，读取后关闭，不写state.json、环境变量、命令行或日志。端点每60秒心跳，重启后复用同一凭证。当前节点配置使用已有管理kubeconfig，专用最小权限供给仍待；单节点PoC内网HTTP，不宣称生产TLS。

真实预检发现bpftrace0.14不支持bswap，改为显式位运算后挂载成功。第一轮12条无独立资产身份的网络事件按既有保护不创建Agent；第二轮登记合成资产后的12条仍未关联，原因经对照确认是服务能力限制使/proc/PID/exe读取EACCES。补CAP_SYS_PTRACE后可读并准确关联，保留原保护，不把网络流量当身份。CAP_SYS_PTRACE具有更广进程跟踪权限，不是只读能力。共6份采集器/测试/启动器/服务文件修改，无后端、前端、数据库或权限模型变更。

远程Python24项通过（原21+匿名管道凭证3项）。权限修正后IPv4/IPv6各6条、服务重启后再各6条，共24条真实网络证据入库；全程总计48次合成回环连接，另有1条独立PROCESS发现记录。四角色真实PKCE后均查询到同一资产；网络记录仍明确连接结果UNCONFIRMED、字节/协议UNAVAILABLE。首次最终验收因journalctl不接受ISO日期格式中断，仅修验收工具后通过，不改应用、不重复流量。上传observationsRecorded字段计数发生在后续身份过滤之前，不保证最终落库；本次专门核对了数据库，回执语义列入待办。

测试进程全部退出，合成发现资产1自动纳管的Agent100已通过EAP暂停，证据保留。采集服务、端点与Secret有意保留运行；未安装完整Go Discovery Agent，其他资产仍需独立发现/登记。原11表、计划、工单人工字段、历史发现行及原配置/Secret身份保持，模型/工具0，旧UNKNOWN与Agent87–99保持。前后端镜像与Schema90保持上一轮，未重新构建部署；901份规范源码摘要核对、6文件推广/root/eap-src。节点可用17952620544字节、imagefs驱逐余量2208483532字节。原后台Ready/UP、节点无压力。

远程/root/eap-ebpf-activation-20261024-r1于2026-10-24T01:36:21.165833+00:00关闭，禁止重放原登记/测试入口，关闭批次不等于停用服务。归档108846字节，SHA256 27bf75b949d75de994b0de4e5d84eff4b702293066ff48a2ab9be772f6fa66b9，73份导出文件核验；实际凭据RAM扫描匹配0。1.1.1覆盖包7554字节，SHA256 4aaea1a38d728ed7f65f47cd0049fae2b45ea61a7683c62690d25485e69b71c6。[完整验收](evidence/ebpf-activation-20261024/README.md)、[常驻部署](../deploy/substrate-poc/ebpf/README.md)、[契约](EBPF_OBSERVER_RELIABILITY.md)。本机没有应用测试/构建，本轮未commit/push，公开GitHub/Gitee仍为9f56893。

下一步：修正上传回执的候选数与持久化数语义、集中采集器健康/丢失、可信进程代际/CRI/Kubernetes身份，以及连接结果/字节与有界OBI/gVisor实验。未重跑前后端完整回归、浏览器、MySQL、Go、第二节点、节点重启或容量长稳，不复用历史数字声称全系统已复验。主线工单责任/回执、历史UNKNOWN有据处置、完整运行系统/客户验收、项目隔离、TLS/HA/灾备仍待，不宣称生产交付完成。根README继续只写产品与核心技术。

## Codex 进展：观测回执已修复，跨框架执行观测设计已完成（2026-10-24）

Host观测回执现在按身份关联后实际保存行数计数，Agent/MCP资产按canonical类型去重；未关联候选返回数量警告。保留JSON字段、历史发现运行输入数和网络不能创建身份的保护。共5份后端应用/测试文件修改，无数据库、前端、权限或采集器变更。

远程旧实现5项中3项按预期失败；修复版完整后端1,365项/148类与打包通过，失败/错误/跳过均0，专项5项已包含在完整回归。最初负向夹具localPort误传null导致编译失败，修正夹具后另建阶段复现，原证据保留。四角色真实PKCE及查询通过，匿名401，机器凭证仍必需；未知身份检查1条/保存0条，混合批次检查3条/保存2条/关联1资产，数据库一致。另在新合成资产上验证常驻采集器2条IPv4/IPv6真实连接入库，未重启采集器。新发现资产2/Agent101已暂停，进程退出；原资产1/Agent100及历史记录保持，模型/工具/业务任务新增0。

版本private-pilot-20261024-ebpf-receipt-r1，后端faf2f7d71e371b61836fc1c43949a9753384425a912dce307facaf9fc07b1596，前端保持a2221375…、Schema90。UP、两端Ready重启0、节点无压力；采集器MainPID3697680、安装文件、Secret UID保持，active/enabled/NRestarts0。901份规范输入核对，5文件及发布清单同步/root/eap-src。原11表、计划、工单人工字段、原Agent状态、历史发现行及原配置身份保持。3个临时Pod按UID清理，余量1,609,472,204字节；后续构建前继续执行空间门槛。

远程/root/eap-ebpf-receipt-20261024-r1于2026-10-24T02:18:42.707011+00:00关闭，禁止重放原写入口。归档1,101,927字节/SHA256 92aaa338cc8e1e7550eff992c13d08803944e690958af0ec2fe44d428b1d6ab3，400份导出文件本地全部核验，实际凭据RAM扫描匹配0。[完整记录](evidence/ebpf-receipt-20261024/README.md)。本机无应用测试/构建，本轮无commit/push，公开GitHub/Gitee仍为9f56893。未重跑前端/浏览器、MySQL专项、Go全栈、第二节点、节点重启和长稳，不合并历史数字宣称全系统复验。

用户进一步要求考虑同进程子Agent、临时代码工具及各种多Agent框架。本轮核对官方来源与EAP代码，形成[跨框架执行观测设计](MULTI_AGENT_EXECUTION_OBSERVABILITY.md)：统一Agent定义/执行/关系/工具调用/产物版本，按框架版本维护适配器；包含LangGraph、CrewAI、AutoGen、ADK/Kagent、OpenAI Agents SDK、Microsoft Agent Framework、LlamaIndex、Pydantic AI、Agno和自研路径。设计包含异步上下文隔离、跨框架因果关联、执行前代码摘要、幂等事件和缺口、可信身份与去重、分阶段PoC。**这些适配器尚未实现或实测**，eBPF不能凭PID恢复逻辑Agent关系，当前Kagent执行范围保持。

下一步优先实现统一执行观测契约和同进程并发/临时代码有界PoC，再验证两类代表框架与EAP实际Go ADK版本；继续集中采集器健康/丢失、进程代际/CRI身份及协议覆盖。既有工单责任回执、历史UNKNOWN有据处置、完整运行系统/客户验收、项目隔离、TLS/HA/灾备和长稳主线保留。保留Plan5/6/8/9、Agent87–101及所有关闭批次。根README继续只写产品与核心技术，不添加验证章节；不宣称生产交付完成。

## Codex 进展：逻辑 Agent 执行观测首版已部署（2026-10-24）

落实跨框架设计的第一阶段：新增V91追加事件表，受运行实例身份认证的五类事件接收、批次幂等与冲突校验、分页历史与关系查询。服务端按实例/代际/producer隔离，复用原身份验证并在写锁内复核代际及有效期；读权限来自PlatformPermission.VIEW_CONSOLE，SUBSTRATE_TASK白名单保持。新增Linux/Python3.10标准库SDK，用ContextVar区分同进程协程、独立run/tool编号、SQLite持久日志与原事件补发；临时代码只记录不可变快照的摘要/大小/语言。没有执行器或自动框架发现。

远程SDK16项、完整后端1,381项/149类及打包、独立MySQL16项通过，失败/错误/跳过0；H2观测16项及工单4项包含在完整后端中。实际SDK夹具11事件/3运行/2工具/1产物被Java接收并核对关系。首轮契约14项中3错误由JSON数字节点比较与Mockito夹具导致；中间完整1,380项只有原迁移数量假设失败，明确89→90专项范围并新增90→91存量保护后通过，所有失败保留。没有在生产运行实例执行SDK或真实框架适配器工作负载。

版本private-pilot-20261024-execution-observation-r1，后端410da077d576067a1c93b729a44fbc65773714547923de3eced57b53b186e7bd，前端保持a2221375…，Schema91。四角色真实PKCE、/me、新查询200；只读地址写403、人类令牌冒充运行实例401，匿名/缺机器凭证401。生产新事件表0行，业务/模型/工具新增0；原11表、计划、工单人工字段、发现旧行及配置身份保持。采集器1.1.1仍active/enabled、PID3697680、NRestarts0，文件/Secret身份保持，本轮未重启或复测内核流量。

UP、两端Ready重启0、节点无压力，3个临时Pod按UID清理。首次收尾因拉取镜像后空间低于1GiB门槛中断，仅按摘要删除本轮两份重复JAR186,122,640字节，保留受测原件/所有报告/历史证据，未删除镜像；空间复检与归档恢复通过，未重复部署。最终可用19,719,479,296字节、imagefs驱逐余量3,975,342,284字节。910份规范输入核验，13份应用/测试文件及Schema91发布清单推广并同步。

远程/root/eap-execution-observation-20261024-r1于2026-10-24T03:26:24.347099+00:00关闭，禁止重放原写入口。归档4,187,485字节、SHA256 abdc8bde2a9a39cb71f4e2289fb8abbdb2675607e69c7160fe36e4a93b6345ad，1,607份导出文件本地核验、实际凭据RAM扫描匹配0。[完整记录](evidence/execution-observation-20261024/README.md)、[事件契约](AGENT_EXECUTION_EVENT_CONTRACT.md)、[SDK](../runtime/execution-observer/README.md)。本机无应用测试/构建，本轮未commit/push，公开GitHub/Gitee仍为前次9f56893。

边界：当前只支持同流父子关系；生产者报告不是独立执行证明，complete仅判断收到事件结构，代码摘要不能恢复内容。跨实例委派、handoff/重试/恢复、多来源去重、OTel映射、前端关系图及框架适配器仍待。未重跑前端/浏览器、Go完整运行体系、第二节点采集、节点重启或长稳。下一步先固定LangGraph与AutoGen版本做有界适配及真实EAP受管实例SDK验证，再独立接入实际Go ADK/Kagent；保留采集器集中健康/丢失、进程代际/CRI身份、工单责任回执、历史UNKNOWN有据处置、完整运行系统/客户验收、项目隔离、TLS/HA/灾备等主线。保留Plan5/6/8/9、Agent87–101和所有关闭批次。根README仍只写产品和核心技术，不宣称全框架覆盖或生产交付完成。

## Codex 进展：两类框架观测适配已验证，创建能力单独列明（2026-10-24）

新增LangGraph1.2.17显式Agent节点/工具包装，保留签名及类型注解；AutoGen Core0.7.5同SingleThreadedAgentRuntime直接RPC消息处理器/工具适配，发送时捕获本地父上下文，接收时按有界单次消息绑定恢复，拒绝未绑定消息。SDK补显式parent/独立根及框架控制流不结束策略。覆盖同进程并发、嵌套子图/RPC、共享Agent多次调用、取消、工具、临时代码摘要及重试分次记录；没有typed retry/resume关系或跨实例关联。

远程Python3.10.12：SDK21项、真实框架19项、后端H2契约18项、独立MySQL18项通过，失败/错误/跳过0。真实框架输出10事件/3运行/2工具与14事件/5运行/2工具，由服务端验证认证归属、父子/嵌套关系与去重。原生Actor身份检查使用隔离测试夹具，不是生产EAP实例端到端运行。依赖精确锁定，无生产凭证/真实模型调用。节点缺ensurepip，使用本批次校验过的pip wheel完成隔离依赖安装；freeze初次调用路径错误也保留，未安装宿主机系统包。LangGraph两轮异步图中断缺少Runnable上下文，原生无包装对照也复现；同步invoke中断/恢复最终通过，Python3.10异步图中断明确不支持，未修补框架内部实现。

适配包eap-framework-observers-0.1.0.tgz为7,813字节/SHA256 03dd7583ade8f6ed19eac4718a6f8ed8b427075f8ecb5d6718bab4457cd84f39，含EAP模块、版本/能力清单与摘要，不含第三方依赖或凭证，尚未装入生产运行实例。920份规范输入核验，13份SDK/能力/测试文件推广/root/eap-src。后端主程序、数据库迁移、前端、发布清单与镜像保持private-pilot-20261024-execution-observation-r1/Schema91，没有重新部署或重跑完整后端/前端/浏览器。UP，两端原Pod/镜像保持，节点无压力；采集器PID3697680、active/enabled/NRestarts0、文件/Secret身份保持，未重启或复发内核流量。

生产执行事件0条、业务/模型/工具新增0，原11表、计划、工单人工字段、发现旧行及配置身份保持。1个临时测试Pod按UID清理，独立依赖目录保留用于复现。节点可用17,644,318,720字节，imagefs余量1,900,181,708字节。远程/root/eap-framework-adapters-20261024-r1于2026-10-24T04:29:24.230494+00:00关闭，禁止重放原入口；归档359,182字节/SHA256 ac86fbad9c39a021e9b0f5aee8841881d478bc1bcfde3ecbf8e70d547448affa，82份导出文件本地核验，实际凭据RAM扫描匹配0。[完整证据与接入包](evidence/framework-adapters-20261024/README.md)。本机无应用测试/构建，本轮无commit/push，公开GitHub/Gitee仍为前次9f56893。

用户询问是否支持不同框架创建Agent，已核对前后端并形成[框架支持表](FRAMEWORK_SUPPORT.md)：零代码创建仍仅EAP_NATIVE与有界KAGENT_ADK；IMAGE/SOURCE可部署应用，但需适配EAP运行协议。新增观测模块不等于新增创建/调度能力。下一步优先为LangGraph和AutoGen补受控运行模板，验证EAP登记→部署→身份→派发→观测→回收后再开放控制台选项；随后实际Go ADK/Kagent与其他框架、跨实例/消息/重试/恢复关系和前端图。AgentChat、发布订阅、分布式Runtime、生产框架工作负载、其他Python版本、Go全栈、真实内核流量及长稳未验证。保留既有交付主线、旧UNKNOWN、Plan5/6/8/9、Agent87–101和所有关闭批次，根README只写产品与核心技术，不宣称全框架/生产交付完成。

## Codex 进展：已保存并创建本地执行观测提交（2026-10-24）

用户明确要求“保存和提交”。沿用已建立的独立公开源码历史，以9f568936为父提交创建a87ab00ef1ec2f63fea416ce006b7ffb72b2ff38（feat(observability): track logical agent executions across frameworks），49文件、3,724行增加/127行删除，完整公开树1,074文件。提交已通过本地fetch保存到原仓库的release/execution-observation-20261024分支；隔离工作副本为/tmp/eap-public-20261024-observation，已干净。未push，GitHub/Gitee仍为此前已发布的9f568936；本轮没有访问远端仓库重新查询状态。

包含V90工单并发保护、eBPF可靠性/真实启用与保存回执修复、V91逻辑执行事件与Python SDK、LangGraph/AutoGen Core有界观测适配及依赖/夹具/公开文档。920份当前输入与已有远程受测清单全部一致；公开树919份逐字一致，ImageBuilder原有镜像地址示例注释替换继续保留，删除该单条注释后内容相同。源码清单、全部文档相对链接、变更文件敏感模式检查与git diff --check通过。仅做本地文本、摘要与Git检查，没有本地应用测试/构建，没有新远程测试/部署或重放关闭批次。未把此前完整回归当成最新适配器的完整重跑。

公开文档区分观测适配与创建/运行能力；保留Python3.10异步LangGraph中断、AutoGen仅本地直接RPC、无跨实例关系/前端图等限制。PoC文档移除内部宿主与证据路径，保留特权能力与管理kubeconfig依赖的说明；根README保持产品内容，不新增验证章节。原feat/galileo-evaluation-foundation的HEAD71e4111、暂存区及历史工作文件未被提交操作改动；内部记忆/证据继续留在原工作区，不并入公开树。[提交凭据](evidence/execution-observation-commit-20261024/README.md)。

下一步仍是LangGraph与AutoGen受控运行模板和EAP登记→部署→身份→派发→观测→回收闭环，完成后再开放控制台创建入口。当前Schema91与常驻eBPF保持，无新模型/工具/业务写入；旧UNKNOWN、Plan5/6/8/9、Agent87–101和全部关闭批次保持。完整运行系统/客户验收、工单责任回执、集中采集健康、项目隔离、TLS/HA/灾备和容量长稳仍待，不宣称生产交付完成。

## Codex 进展：执行观测提交已推送 GitHub 与 Gitee（2026-10-24）

用户明确要求“推送”。a87ab00ef1ec2f63fea416ce006b7ffb72b2ff38已推送GitHub main（9f568936快进）与Gitee release/execution-observation-20261024（新分支）；两端git ls-remote回读相同SHA。Gitee原main5e626250、feature71e4111、前次release/reclaim-guards-20261023的9f568936均保持，无force push、无内部历史发布。本地开发分支HEAD71e4111与空暂存区保持；源码没有新改动、没有应用测试/构建/部署。[推送凭据](evidence/execution-observation-commit-20261024/push-proof.json)。

GitHub沙箱DNS失败后获准网络重试成功；Gitee一次回读TLS失败后重试核验成功。既有“未推送”和context-save检查点记录的是推送前状态，以本节和当前交接顶部为准。下一步仍是受控LangGraph/AutoGen模板与EAP创建、身份、派发、观测、回收闭环；不是已完成多框架创建。禁止重放关闭批次，保留旧UNKNOWN和全部历史记录。

## Codex 进展：执行关系控制台已部署并完成远程增量验收（2026-10-24）

新增“运行与观测 → 执行关系”：按 Agent/事件流查询、游标分页、同流父子执行、工具调用、临时代码摘要、Trace 入口及结构缺口。读取错误与空结果分开；筛选、账号、权限及组件生命周期变化会取消查询并拒绝迟到结果。沿用 `/me` 的 `viewConsole`，没有新增后端接口、迁移或框架创建选项。[产品说明](AGENT_EXECUTION_CONSOLE.md)。

修正版 `private-pilot-20261024-execution-console-r2` 已上线，前端 `f4a223e35e39b8e0e13606ebfaef54e5e2a8ef808f8af14eab706f39d92c9a27`，后端保持 `410da077…`、Schema91。远程前端 531 项、类型/构建及四角色两种宽度共 8 场景通过；额外覆盖 503 恢复、无效筛选，新增 14 项单元测试。首轮全局网格类名冲突已修复。旧 Pod 退出竞争、原浏览器测试环境到期、截图步骤顺序错误均保留失败证据并分别修正验收工具后通过。[完整记录](evidence/execution-console-20261024-r2/README.md)。

真实生产执行表仍为 0；浏览器非空图与故障由响应夹具验证，不能当作真实框架生产工作负载。后端/MySQL/SDK/框架/Go/真实内核与长稳均未在本轮重跑。最终 UP、两端 Ready/重启 0；eBPF active/enabled、PID3697680、NRestarts0，未重启。原业务/计划/工单/发现数据与配置身份保持。r2 的 6 个临时 Pod 和 1 个 ConfigMap 全部清理；926 份规范输入、132 份导出文件核验，发布清单已同步。节点可用 17,604,915,200 字节，imagefs 余量 1,860,778,188 字节，下次构建前须重新测量。

两轮远程批次 `/root/eap-execution-console-20261024-r1` 与 `-r2` 均关闭，禁止重放原入口。r1 在 `docs/evidence/execution-console-20261024`，当前 r2 在 `docs/evidence/execution-console-20261024-r2`；r2 归档 SHA256 `59e0be589e5ced32a0d325e622859da8fe140b3461410edb8b39ff1e6e0e8b6e`。本机没有应用测试/构建，本轮未 commit/push；前次公开提交仍为 `a87ab00`，原开发分支与空暂存区保持。

下一步优先用新的受控 LangGraph/AutoGen 模板完成 EAP 登记→部署→身份→派发→观测→回收，在真实工作负载中验收现有控制台；然后补实际 Go ADK/Kagent、跨实例/消息/重试关联及集中采集器健康与丢失。框架观测适配不等于创建能力，不得直接开放未经闭环验证的框架选项。保持旧 UNKNOWN、Plan5/6/8/9、Agent87–101、全部关闭批次和既有交付主线；不宣称全框架或生产交付完成。

## Codex 进展：两个受控框架已跑通 EAP 真实创建、调度与观测（2026-10-24）

新增 LangGraph 1.2.17 / AutoGen Core 0.7.5 固定求和 IMAGE 模板；共享 Runner 的身份、工具许可、预算、持久化意图和原结果。EAP 创建 Agent102/103，由 Workflow51 原 runKey `wf-exec-630788b7-8bf5-4e2f-954c-5902992d6e4b` 调度，两个任务及工作流 SUCCEEDED、结果42。共18条真实事件、6次同流逻辑Agent执行、2次工具、2条同一代码摘要记录。各流三次Agent共享Python进程，Trace与工具账本一致；临时文件删除后摘要仍可查。四角色真实API及桌面/手机两框架16浏览器场景通过，无图夹具。[完整记录](evidence/framework-runtime-20261024/README.md)。

远程SDK23、真实适配器19、Worker8通过；Go native/framework/Kagent顶层62/5/70（含子测试139/13/170），race与vet通过。实际镜像CPython3.10.22内复跑50项及两种Go→Python桥接。首次AutoGen泛型返回注解、Go认证测试夹具、浏览器续接UID字段错误均修正并保留失败历史；构建空间门槛和镜像站元数据问题也留证。没有本机应用测试/构建。

首次自动休眠提交被原全局LEGACY/UNKNOWN保护拒绝，确认零执行行/工具账本不变后只改本轮节点为`suspendAfterTask:false`，保留原拒绝意图与请求键。成功后经EAP显式PAUSE ACKED→SUSPENDED→REVOKED，Agent已PAUSED；未证明自动回收，未解除/重放旧UNKNOWN。模型增量0，平台工具用量10→12。旧业务/计划/人工工单/发现行及配置身份保持。

专用镜像`7cd76ee9b24a75039417897e58f98f4f6e1c1d1692a308b47a6b940fd6c2216b`，最终载荷7文件核验；9份实现/测试/构建文件同步规范源码，982份规范输入、166份导出文件核验，文档独立清单。前后端与Schema91保持（后端410da077…、前端f4a223e3…），UP、Ready/重启0；eBPF原PID3697680、active/enabled/NRestarts0。5个本轮Pod和2个ConfigMap已按UID清理；样例实例/模板/证据保留。节点可用18,731,520,000字节，imagefs余量2,987,382,988字节，后续构建前重测。

批次`/root/eap-framework-runtime-20261024-r1`已关闭，禁止重跑。归档871,976字节，SHA256 `e044cee540343e67b365dfa0aa2bc13198a6521a1fddf6d6657dd87bf0c378e1`；实际Secret/编码/JSON转义/JWT扫描通过，4张真实视口截图已复核。本轮未commit/push；原开发分支/HEAD71e4111/空暂存区保持，前次公开提交仍a87ab00。

下一步优先产品化两个受控模板的创建配置、取消/恢复和事件待投递状态，再补实际Go ADK/Kagent内部观测、跨实例/消息/重试关联与集中采集健康。现有模板只执行固定代码，无任意模型生成代码、业务MCP或模型调用；Python3.10 LangGraph异步中断、AutoGen AgentChat/分布式、后台补发仍未覆盖。本轮未重跑完整后端/MySQL/前端单元构建/真实内核/长稳，不宣称通用框架或生产交付完成。保留Plan5/6/8/9、Agent87–101及全部关闭批次。

## Codex 进展：执行事件有界补发与持久投递状态已验收（2026-10-24）

SDK新增deliver()/delivery_status()/existing_only：网络故障、429/5xx有限退避，认证拒绝/409/非法回执停止；同批固定原事件及编号，并发新事件不混入原批。SQLite持久记录全量待发/已确认数、累计尝试及时间；旧日志只补发不重执行，取消只停止后续投递。固定框架Worker最多三次，原任务回执新增deliveryState/deliveryAttempts，成功计算和上传状态分开。该回执是原始快照，后续显式补发不改写它；没有后台常驻服务、集中状态UI或Agent取消。[说明与证据](evidence/execution-delivery-20261024/README.md)。

远程SDK23、投递14、适配器19、Worker10共66项通过；Go native/framework/Kagent顶层62/5/70（含子测试139/16/170），race/vet通过。实际镜像CPython3.10.22内复跑66项及两框架×正常/回执丢失/401/503共8组Go→Python故障场景。服务端保存后丢失回执只确认原事件；Runner退出后显式打开原SQLite可补发，唯一事件仍9条，check/claim/receipt各1，不重算工具。故障仅注入隔离HTTP接收器，真实EAP验证正常链路，不外推为生产故障或崩溃恢复。

新镜像707dfc2c5c01e947913ff846247ebea18a3ad14cb0e9cb22d5217df1da67cd13，最终7份应用载荷核验。EAP创建Agent104/105、Workflow52，原runKey `wf-exec-696411aa-7c72-49a5-9473-ae40a19b9cf3` SUCCEEDED，两个结果42，新增18条事件（原18保持、总36），四角色真实API通过。显式PAUSE ACKED→SUSPENDED→REVOKED，Agent PAUSED；没有自动回收、没有解除/重放旧UNKNOWN。模型增量0，平台工具12→14。Agent102/103、Workflow51、历史业务/计划/人工工单/发现行和配置身份保持。

前后端及Schema91保持，UP、Ready/重启0；eBPF原PID3697680、active/enabled/NRestarts0。8份实现/测试/能力JSON输入同步规范源码，68份Runtime/983份全量规范输入和118份导出文件核验；未重跑完整后端/MySQL/前端单元构建/浏览器/真实内核/长稳，沿用输入不能视为全部重测。3个本轮Pod/1个ConfigMap按UID清理、余量0。节点可用18,400,124,928字节，imagefs余量2,655,987,916字节，后续构建前重测。

批次/root/eap-execution-delivery-20261024-r1已关闭，禁止重跑。归档424,161字节/SHA256 e2f8538c3c597ec05d0815198cddf1517547adf98341782641ab3051a500fb2b，实际Secret/编码/JSON转义/JWT扫描通过。本机无应用测试/构建，本轮未commit/push；原开发分支HEAD71e4111、空暂存区及前次公开a87ab00保持。

下一步仍优先产品化固定模板创建配置、集中投递状态及受控后台补发，再做Agent任务取消/恢复、实际Go ADK/Kagent内部观测、跨实例/消息/重试关联。后台设计必须保留实例UID/身份代际约束，不能自动唤醒/重开已撤销实例或重跑业务来消除待发。通用零代码框架、任意模型生成代码/业务MCP、Python3.10 LangGraph异步中断、AutoGen AgentChat/分布式仍未覆盖；不宣称生产交付完成。保留全部旧UNKNOWN、Plan5/6/8/9、Agent87–105和关闭批次。


## Codex 进展：执行控制台、框架样例及补传已提交并推送（2026-10-24）

用户明确要求“提交和推送”。公开提交 `0bf5819c7efe320caaa86dbf71b238ecaeb59a55`（父 a87ab00），30 文件、+1,752/-75，包含执行关系 UI、LangGraph/AutoGen 固定 IMAGE 样例、有界事件补传和持久投递状态、测试及公开文档。GitHub main 与 Gitee release/execution-observation-20261024 均已快进到同一 SHA，分别由官方 API 和 git ls-remote 回读确认。GitHub Git 连接失败后改用官方 API，目录树与提交 SHA 均与本地一致，force=false；Gitee 普通推送，原 main/feature/上次发布分支保持。[提交与推送凭据](evidence/execution-delivery-commit-20261024/README.md)。

独立公开副本 `/tmp/eap-public-20261024-delivery` 干净，原仓库已保存本地引用 release/execution-delivery-20261024；当前开发分支/HEAD71e4111和空索引保持。983 份规范输入核验，公开 982 份逐字一致，1 份保留既有 ImageBuilder 示例注释替换；全树来源摘要、99 处文档相对链接、暂存空白及敏感模式扫描通过。内部记忆、AGENTS、研究/BP、原始证据及连接信息不在公开历史。原工作区仍有历史改动，不能整体暂存；本次只追加内部交接/证据，没有应用代码修改、测试重跑、构建或部署。

下一步继续固定模板创建配置、集中投递状态及受控后台补发，再推进任务取消/恢复、Go ADK/Kagent 内部观测与跨实例/消息/重试关联。既有远程测试与真实样例结果继续以此前三个关闭批次为准，未重新全系统验收。保留全部旧 UNKNOWN、原实例/任务历史与关闭批次；不自动唤醒撤销实例或重跑业务。下方“未提交/未推送”均为先前阶段历史状态，以本段为准。


## Codex 进展：固定框架模板入口已部署验收（2026-10-25）

新增控制台“部署与运行 → 受控框架样例”，以独立TEMPLATE来源提供LangGraph1.2.17/AutoGenCore0.7.5固定求和样例。平台固定镜像/命令/端口/就绪路径，拒绝调用方覆盖；目录返回可用性原因，前端按/me控制按钮。原requestKey在镜像配置更换或清空后仍返回原部署，换样例复用原键冲突。RUNNER白名单仍为EAP_NATIVE/KAGENT_ADK。[产品说明](FRAMEWORK_SAMPLE_TEMPLATES.md)。

远程后端1389项/149类、部署专项40项（子集）、前端534项/类型/构建通过。EAP API创建Agent106/107、Workflow53，原runKey `wf-exec-13a516b8-8e1b-4d03-8787-477e0307b707` SUCCEEDED，两结果42；重复请求返回原部署v1和原runKey。新增18条事件、6次同进程逻辑执行、2次工具/2条代码摘要，原36条保持、总54，模型增量0、平台工具14→16。四角色API和16模板+16真实图浏览器场景通过；无图夹具，浏览器未点击发布。首轮选择框定位超时、容器到期与续接脚本UID结构错误均保留并修正，只复验只读浏览器，没有重跑业务或修改受测应用代码。两张模板视口截图人工复核。

发布private-pilot-20261024-framework-templates-r1已上线，后端3e866412d90cc0ba4bb1796d798b4817fd9f5b1c951694500eb890073369d09c、前端37cfabfff6b09d0f454bbf6f3858c119471c7fc9cf5d7a6826d45271c274b891；框架镜像707dfc2c…保持，Schema91。新增后端环境配置EAP_SUBSTRATE_FRAMEWORK_SAMPLE_IMAGE固定该摘要；其余部署配置核验。显式EAP PAUSE ACKED后SUSPENDED/REVOKED，Agent PAUSED；没有证明自动回收。原业务/计划/人工工单/发现记录、配置身份和历史UNKNOWN保持。

最终UP、应用Ready/重启0；eBPF原PID3697680、active/enabled/NRestarts0，未重启。8个临时Pod/1个ConfigMap按UID清理，余量0；12份变更同步规范源码，985份输入及466份导出文件校验，发布清单已同步。节点可用17,027,952,640字节、imagefs阈值余量1,283,815,628字节，下轮构建前重新测量。批次/root/eap-framework-templates-20261024-r1已关闭，禁止重放。归档SHA256 ea085d4b40c39487a200d577c092863fa956b4adde13470e66e024d2d6417c69、2,404,999字节；实际凭据扫描通过。[完整证据](evidence/framework-templates-20261024/README.md)。

未重跑MySQL专项、Go/Python套件、真实内核与长稳；不是全系统/生产重新验收。没有任意框架托管、集中投递状态、后台补发或任务取消/恢复。本机没有应用测试/构建；本轮未commit/push，开发分支HEAD71e4111及空索引保持，前次公开提交仍0bf5819。保留原工作区历史改动，禁止整体暂存。

下一步优先集中投递状态和受控后台补发，再推进Agent任务取消/恢复、实际Go ADK/Kagent内部观测及跨实例/消息/重试关系。必须保留实例UID/身份代际约束，不能自动唤醒/重开已撤销实例，也不能重跑业务消除待发。保留全部旧UNKNOWN、Plan5/6/8/9、Agent87–107和所有关闭批次。此前“无专用表单”及旧镜像状态属于历史，以本段为准。

## Codex 进展：执行事件投递快照已部署验收（2026-10-25）

本轮新增执行事件集中投递快照：V92独立表、机器身份报告入口、只读查询、SDK `report_status()`/`delivery_report_status()`及控制台面板。单调报告序号拒绝迟到和回退；同序号同内容不刷新年龄；已确认数不能超过服务端事件数。事件与报告共用实例/身份代际/producer约束。四个计数独立展示，报告缺失不填零；超过5分钟、撤销、换代、过期分别提示。Worker在有界事件投递后只报告一次，无后台补发或业务重执行。[产品与接口](EXECUTION_DELIVERY_STATUS.md)。

远程后端1401项/149类、H2专项30项（全量子集）、隔离MySQL30项、前端538项及类型/构建通过。Python SDK23、投递14、报告8、适配器19、Worker12共76项，通过宿主隔离环境与实际运行镜像复跑；实际Go→Python桥接两框架共14组正常/事件与报告回执丢失/401/503通过。Go源码与原受测二进制摘要保持，未重跑Go/race。首次H2并发两秒锁等待超时原样单例及后续全量通过，未放宽超时。MySQL非UTC会话暴露租约时间误判，修复为按Hibernate UTC读取，JDBC报告时间保持原日历；新增测试夹具JPA版本和续接脚本引号错误修正，失败记录均保留。

EAP创建Agent108/109、Workflow54，runKey `wf-exec-e950af2e-40f5-4014-a04d-c84612d1a7a9` SUCCEEDED，两个结果42。新增18条真实事件、6次同进程逻辑执行、2次工具/2条摘要、2份报告；旧54条保持、总72。报告均总9/确认9/待确认0/服务端9，序号1、尝试1。重复请求返回原部署v1及原runKey。模型增量0，平台工具16→18。四角色API及四角色×两宽度×两框架16个真实报告/图场景通过；另5个只读浏览器响应夹具覆盖异常展示，明确不是生产故障注入。真实桌面/手机截图复核，无浏览器业务写入或意外页面错误。显式EAP PAUSE ACKED后SUSPENDED/REVOKED、Agent PAUSED；报告显示凭据撤销，历史图仍可查，未证明自动回收。

发布 `private-pilot-20261025-delivery-status-r1` 已上线，Schema92。后端 `ecde8d5f2fc231ab7109fdb79ff1db3b25c4aec6164e8b6c6f01ebd7ab4329ba`、前端 `ec1f02566a681c8455cd183ff8a6a57f2f4f8de38811189c61ec7b5efefdb862`、框架镜像 `d9d3a59bfb0120501bb43881c1fd3567a1d27960ebc9a34d73fd74151c6244e2`。后端仅替换受控样例镜像配置值，旧部署请求保持原版本。UP、应用Ready/重启0，节点无压力；原业务/计划/工单人工字段/发现记录、配置身份与旧UNKNOWN保持。eBPF仍active/enabled、PID3697680、NRestarts0，本轮未重启或复测内核流量。

前端构建前磁盘门槛暂停，清理摘要核验的重复上下文后只续接前端阶段，没有重复后端切换或业务执行。两次共清理2245340834字节，保留受测原件、源码、依赖、镜像及证据。9个临时Pod、2个ConfigMap按UID清理，剩余0。收尾节点可用17307607040字节、imagefs阈值余量1563470028字节；下一轮构建前重测。

17份实现/测试文件推广规范源码，992份输入本地与远程逐项一致；10份产品/验证文档和发布清单同步。远程批次 `/root/eap-delivery-status-20261025-r1` 于2026-10-25T02:32:26.980737+00:00关闭，禁止重放原入口。归档3289072字节、SHA256 `b381ed45f2a82bf873779da1469226db60dda9f50641884a6eb08a1f1aef2908`，580份导出文件核验；实际Secret及编码/JWT扫描577文件、匹配0。[完整证据](evidence/delivery-status-20261025/README.md)。远程规范目录原无根README，已新增当前产品说明，没有覆盖不明版本。

本机没有应用测试/构建。本轮未commit/push，开发分支HEAD71e4111及空索引保持，前次公开提交仍0bf5819。保留历史工作区改动，禁止整体暂存。后台常驻补发、任务取消/恢复、实际Go ADK/Kagent内部观测、跨实例/消息/重试关系仍待；报告是认证客户端快照，不是实时队列、存活或业务效果证明。下一步优先受控后台补发，必须保留原日志/实例UID/身份代际，不自动续期、唤醒撤销实例或重跑业务消除待确认。继续保留全部旧UNKNOWN、Plan5/6/8/9、Agent87–109和关闭批次，以及完整运行体系/客户验收、项目隔离、TLS/HA/灾备与容量长稳主线；不宣称全系统或生产交付完成。


### Codex 进展：后台补发批次收尾（2026-10-25）

原身份绑定的后台补发已上线并收尾：Python109项，Go原生/框架/Kagent顶层62/11/70（含子139/29/170），race/vet，前端538项、类型/生产构建通过；实际运行镜像14组前台桥接和16组签名身份常驻补发通过。EAP Agent110/111、Workflow55、runKey `wf-exec-f28747be-205b-4e02-8bdf-52aff0851c75` SUCCEEDED，两结果42，新增18条事件/2报告，总90事件/4报告；模型新增0，平台工具新增2。四角色API、16真实报告/图浏览器场景、5只读展示夹具通过，两张桌面/手机图复核。显式PAUSE后SUSPENDED/REVOKED/PAUSED；旧UNKNOWN、旧业务/计划/人工字段/发现配置保持。

发布private-pilot-20261025-background-delivery-r1，后端ecde8d5f2fc231ab7109fdb79ff1db3b25c4aec6164e8b6c6f01ebd7ab4329ba保持，前端4a61943ace4cf63abf189fda839c7283a313ad81e439e739bf410ec9ff6ab59d，运行镜像c02d79469375cdc8a39158a342e4c6b170c96099ae0dfe5ccea7e603da1356c6；Schema92。规范源码998份输入/15份改动已推广，11份文档同步；Java/MySQL没有本轮重测，真实内核和长稳未测。9个Pod/2个ConfigMap按UID清理，余量0；原eBPF PID3697680/NRestarts0保持。节点可用17250136064字节，imagefs阈值余量1505999052字节。

远程/root/eap-background-delivery-20261025-r1已关闭，禁止重放。归档1901448字节，SHA256 23e49bddc6f29f24dda7a3322d3ae095adddfbfd1afe740734b5277d2e4ad171；295文件本地校验，实际Secret及编码/JWT扫描通过。镜像站首轮失败保留，采用同一Python基础摘要的内部缓存完成构建。[证据](evidence/background-delivery-20261025/README.md)。

用户随后要求完善并测试短生命周期工具/subagent和动态代码。新独立批次/root/eap-ephemeral-code-20261025-r1在验证中，不能将其视为已上线；上述线上版本和旧批次保持。用户已要求“别忘记提交”，提交候选在独立公开副本/tmp/eap-public-20261025-background整理，原开发分支HEAD71e4111和历史工作区保留。Claude只读审查接口多次返回HTTP200 HTML/no_events，未得到审查结论；尚未commit/push。


### Codex 进展：临时子 Agent 和动态代码专项收尾（2026-10-25）


SDK新增 `bind_code()`：仅同Journal、同Trace、已登记快照可交给当前消费者，跨日志/Trace拒绝。样例按已校验数字数量生成受限求和源码，由temporary-coder捕获，删除文件后交给嵌套ephemeral-executor执行同一字节；四次逻辑执行/一次工具/两条同摘要登记共12事件。没有上传源码正文，没有新增跨流生成来源边或开放任意模型代码执行。另修复SQLite初始化失败后数据库句柄/文件锁泄漏，旧代码已远程复现。

远程Python116项（SDK23、短生命周期6、投递14、报告8、后台17、适配器19、Worker17、补发12）及镜像CPython3.10.22内复跑通过。Go顶层62/11/70、含子139/30/170，race/vet与两个二进制构建通过。新源码和新二进制挂载到已发布镜像后14组真实Go→Python桥接通过；100个并发临时coder/执行者组合201逻辑执行/100工具/802事件，父子和代码归属、取消、文件覆盖/删除、重开原事件补发通过。旧9事件Go测试夹具修正为12，首次失败及两处测试续接脚本错误保留。

**本次未发布新运行镜像，未部署，未推广规范源码。** 在线仍为private-pilot-20261025-background-delivery-r1：后端ecde8d5f…、前端4a61943a…、框架c02d7946…、Schema92；原Agent110/111、Workflow55保持。新源码和旧9事件Worker不能混搭。后端/MySQL/前端、真实EAP创建/调度/图展示、16组后台定时器、内核和长稳没有本轮重跑。规范输入998份保持，候选999份、7份代码/测试变化；健康UP、应用Ready/重启0，三个本轮测试Pod按UID清理、余量0。

新批次/root/eap-ephemeral-code-20261025-r1已关闭，禁止重放；归档254510字节/SHA256 301d530ad1b1a00e90167e1cc188cf30705f996514f832d473a7d9998548c218，101文件本地核验，实际Secret/编码/JWT扫描通过。[证据](evidence/ephemeral-code-20261025/README.md)；[能力说明](EPHEMERAL_EXECUTION_OBSERVABILITY.md)。上一轮后台补发已完成收尾，295文件归档见[后台证据](evidence/background-delivery-20261025/README.md)。

用户“别忘记提交”要求保留。独立公开候选/tmp/eap-public-20261025-background，分支release/background-delivery-20261025，基于0bf5819，53文件（此前模板入口、集中状态、后台补发及本轮追踪），999输入核验且保留既有ImageBuilder公开示例注释；暂存空白及文档链接检查通过。原开发分支HEAD71e4111/空索引和历史工作区保持。Claude审查CLI持续返回HTTP200 HTML/no_events、input0，无有效审查结果。已向用户询问是否允许按Codex复核替代本次Claude门槛；未获答复前不commit，不push。不是自动审批拒绝；不应排查或抄录凭据来猜测API故障。

下一步：得到审查方式确认后提交候选（未授权新推送）；若继续发布临时执行增量，开独立批次按新Go+Python整套构建镜像、EAP创建/工作流/控制台验收，不能复用已关闭批次重做业务。仍保留原文代码归档/来源边、跨实例委派、任务取消恢复、实际Go ADK内部子Agent和生产交付条件主线。保留所有旧UNKNOWN、Plan5/6/8/9、Agent87–111及已关闭批次。全部应用测试/构建在远程，本机仅文本和证据摘要。


### Codex 进展：本次复核及GitHub/Gitee推送完成（2026-10-25）


用户明确授权本次由Codex替代Claude审查，并要求推送GitHub和Gitee。提交 `4dcfa6d2882b39ede31240444cc0b8515ec45e1c`（父0bf5819），55文件、+2615/-208，包含固定模板入口、集中投递快照、身份绑定后台补发及临时子Agent/动态代码追踪。GitHub main与Gitee release/execution-observation-20261024均已普通快进至同一SHA；官方API/ls-remote回读通过。Gitee原main/feature/旧发布分支保持，没有force-push。详见[提交与复核证据](evidence/background-delivery-commit-20261025/README.md)。

Codex复核后修正样例说明写死3个Agent、旧公开发布/源码摘要清单和验证日期/基线遗漏。前端说明改为兼容新旧样例，独立远程批次/root/eap-publication-review-20261025-r1的538项、类型/生产构建通过，一个测试Pod按UID清理后关闭。Python116、Go62/11/70顶层（含子139/30/170）与14桥接沿用已关闭临时代码批次；后台16组、后端1401/MySQL30是此前独立结果。本机无应用测试或构建；未重新全系统测试、未部署。在线仍为后台补发版本，动态代码运行镜像尚未发布。

公开副本/tmp/eap-public-20261025-background分支release/background-delivery-20261025干净；原仓库保存同名发布引用，当前开发分支HEAD71e4111、空索引及历史工作区保持。999输入核验，998逐字一致，1份保留既有ImageBuilder示例注释公开替换；源码摘要覆盖1108文件（公开树1109），125处相对链接和敏感模式检查通过。原工作区仍有大量历史改动，不能整体暂存。上述提交和双端推送已完成，下方“等待确认/未提交/未推送”为历史。

下一步如继续运行主线，开独立批次构建新Go+Python整套镜像，经EAP创建、工作流和关系图验证后再发布动态代码增量；不重跑已关闭批次或旧业务。保留全部旧UNKNOWN、Plan5/6/8/9、Agent87–111及现有运行身份/记录。原文代码归档、跨实例委派/来源边、Go ADK内部子Agent、任务取消恢复和生产交付条件仍待。


### Codex 进展：临时执行整套发布与部署正文权限修复（2026-10-25）

用户要求继续逐条修复，本轮已完成两个独立远程批次。第一批 `/root/eap-ephemeral-release-20261025-r1` 将新Go+Python整套镜像和入口说明上线，修复二进制复制丢失执行权限（Dockerfile显式0755）。新镜像Python116、实际桥接14、签名身份/后台定时器16场景通过；Go62/11/70顶层（含子139/30/170）race/vet与前端538、类型/构建复用已封存且摘要一致的结果，第一批Java/MySQL未重测。Agent112/113、Workflow56、runKey `wf-exec-4c46ae40-087a-49a7-9fd6-b648d91e9459` SUCCEEDED，两结果42；新增24事件、8逻辑执行、2工具、4代码登记和2报告，总114事件/6报告。模型新增0，工具额度20→22；四角色×两宽度×两框架16真实浏览器场景及5合成只读状态通过，人工复核三截图。两个样例经EAP显式PAUSE、策略禁用、身份撤销、Agent暂停，未证明自动回收。清理7Pod/3ConfigMap。归档1655482字节/SHA256 `93d4fa7f6229437ba2a95608d730f31b4cb14e336845256e877715642954bb44`，214文件核验。[证据](evidence/ephemeral-release-20261025/README.md)。

AutoGen原v1在身份已配送后恢复休眠遇到Substrate ABORTED操作冲突。通过EAP对同实例PAUSE及同v1失败阶段重试恢复，UID/身份原验证时间不变，无业务重放；这是人工收尾，自动处理竞态仍待。恢复验收脚本误重建已确认策略版本被请求指纹拒绝，随后只读核对原策略；原失败保留。

第二批 `/root/eap-deployment-content-20261025-r1` 修复部署通用响应原样返回完整配置：白名单元数据投影应用于历史读、创建、重放与重试；正文接口按agentId/version精确定位，并从PlatformPermission.VIEW_CONTENT授权，与/me一致。历史configJson、请求指纹、执行参数不改写，未新增前端正文按钮，不等于项目隔离。隔离原行为4个预期失败复现，修复专项15、全量1411项/149类及隔离MySQL8.4.11专项15通过；线上四角色真实JWT权限通过，正文只在内存比对摘要。新后端载荷/非root探针/发布通过，原配置、计划、业务、预算、114事件/6报告保持，模型/工具新增0。3Pod按UID清理；内存/磁盘门槛暂停后续接，清理两份同摘要JAR副本186164156字节，原件保留。查询脚本JSON路径字符集错误3144及归档MySQL版本断言错误保留并修正；不重跑测试/部署/业务。归档1823015字节/SHA256 `25768be6cb61a4d5d4bf402448f567922a61df1c5fca8a61a9e4c7cae25c8ad0`，704文件核验。[证据](evidence/deployment-content-20261025/README.md)。

当前发布 `private-pilot-20261025-deployment-content-r1`，后端754e1abf0ed09daa7b6935394a4ae502c4c17ee1b892bcc387c5914ced6b33a2，前端ef53fbbefcb82b60e0b27e01e5df89104372ad777a2f9be4ac4564b7337e1fa9，框架155c6cf52557a95b8d3c2c5ffaead590cd9ae2c14e272c4fafc8903f8a0cf529，Schema92。999输入本地与远程逐一一致，第一批9份、第二批6份变化已推广。第二批前端/浏览器/运行时/内核未重测，两个批次均无长稳；UP/Ready/重启0，节点无压力，imagefs阈值余量1116580044字节，后续构建前重新核对。两个批次已关闭，禁止重放。eBPF配置保持，不将SDK事件计作内核语义识别。

用户询问代码量：当前工作区1052源文件、119084物理行、109518非空行；测试292文件/35686行约30%。排除依赖、构建、文档、证据、BP和录屏材料，包含注释及未提交改动；不是覆盖率指标。本机仅统计文本/摘要，所有应用构建、测试、浏览器和部署在远程。

本轮未获新的提交/推送请求，未commit/push；前次公开4dcfa6d保持，开发分支HEAD71e4111及空索引不变。下一项优先定位 RuntimeCredentialDeliveryService 配送确认后的原实例休眠恢复冲突，再继续项目隔离与通用取消/恢复。项目目标尚未回复可选问题，可先按单企业多项目私有化作为设计假设。继续保留全部旧UNKNOWN、Plan5/6/8/9、Agent87–113与已关闭批次，不能宣称全系统或生产交付完成。


### Codex 进展：身份配送后休眠冲突和错误就绪已修复（2026-10-25）

用户在代码量说明后要求继续，按investigate流程进一步处理真实AutoGen暴露的恢复问题。确认两个根因：凭证ACK事务提交后，finally中一次suspend遇ABORTED占用直接报失败；同创建请求重放只检查身份READY，未检查实际SUSPENDED。隔离原实现两个预期失败复现后，四文件修复：RuntimeCredentialDeliveryService仅对明确占用最多4次休眠、间隔250ms，前后核对原UID/模板与实际终态，中断停止，不重试配送/唤醒/业务/未知结果；原配送异常保持主错误。PlatformRuntimeCreationService在身份READY后再次核对实际原实例SUSPENDED，不用重试强行暂停运行实例。相关两个测试类新增15项，完整后端1411→1426项。

独立远程/root/eap-identity-restore-20261025-r1：专项29+10、全量1426项/149类、隔离MySQL8.4.11专项39通过，修复后失败/错误/跳过0。真实EAP创建Agent114/115（LangGraph/AutoGen），均READY/SUSPENDED；同部署及同UUID实例创建保持原版本/UID/身份/配送时间，模型/工具/工作流新增0。随后身份REVOKED、Agent PAUSED，实例仍SUSPENDED；原114事件/6报告、业务/计划/预算与全部UNKNOWN保持。新样例各建三项0额度账户，没有派发任务。未对生产注入故障，持续占用/未知结果仍需原实例复核；前后UID核对不等于原生RPC拥有UID原子条件控制。

发布private-pilot-20261025-identity-restore-r1，后端3cac58ba89bba6d429c101f2db09a036c83da5427b07463ee12b7b3cebb5ab6a，前端ef53fbbefcb82b60e0b27e01e5df89104372ad777a2f9be4ac4564b7337e1fa9和框架155c6cf52557a95b8d3c2c5ffaead590cd9ae2c14e272c4fafc8903f8a0cf529保持，Schema92。非root探针/镜像载荷/发布/UP/Ready/重启0通过，3临时Pod按UID清理。原件/镜像/证据保持，清理3份旧同摘要副本与本批上下文重复JAR共372163754字节；节点可用17189576704字节、imagefs阈值余量1445439692字节，后续重测。首次过长SSH命令断开，确认未创建批次后改用约30KB压缩传输；无重复创建或执行。

批次已关闭，禁止重放。归档2775353字节，SHA25672f63c23909b2feb15e09572dece396d435ad6d4ea24879b5b898e4a88b7b984，1005文件/1004条manifest、实际Secret/编码/JWT扫描通过。999实现输入本地/远程一致，4文件推广。前端/浏览器/运行时/内核及长稳没有本批重测，沿用对应独立验收。[行为](RUNTIME_IDENTITY_SLEEP_RECOVERY.md)、[完整证据](evidence/identity-restore-20261025/README.md)。

耐久排查结论：身份READY只能证明凭证确认，不能推出运行实例已经休眠；幂等请求跳过已完成配送后仍须复查对用户承诺的实例终态。finally中的恢复异常不能覆盖原配送失败。两个缺陷在真实记录和原实现隔离测试均有依据，避免下一轮用重新配送身份或新建版本掩盖。

本轮始终未commit/push，当前开发HEAD71e4111/空索引及历史改动保持，公开提交4dcfa6d未变。本机仅文本、统计和摘要。下一项继续单企业多项目权限范围、通用任务取消/恢复、跨实例委派/源码归档及Go ADK内部观测、TLS/HA/灾备和长稳；不宣称全系统已完备。保留所有旧UNKNOWN、Plan5/6/8/9、Agent87–115和封存批次。

### Codex 进展（进行中）：项目独立部署边界，2026-10-25

用户“继续吧，要修改就都完成”，要求继续尚未完成的主线。当前先做每项目独立部署/独立数据库，不实现同库行级多租户。新增 V93 数据库项目绑定、JWT 精确 eap_project 校验、/me 项目信息、控制台标识、安装 V2 项目配置；进一步在原生控制、AX 和源码构建入口增加项目空间拒绝，源码镜像仓库名包含项目。生产 PoC 尚不启用项目归属，旧数据保持共享范围。设计边界见 docs/PROJECT_ISOLATION.md。尚未 commit/push，不能自动提交。

独立远程批次 /root/eap-project-boundary-20261025-r1，准备文件 /tmp/eap-project-boundary-20261025。初版绑定12项通过；JWT夹具漏开Flyway导致7项启动错误，修正后7项通过。前端538项及类型/构建已通过，产物在 test-out/frontend。首轮后端1445项/151类，唯一失败为旧V92专项未固定目标版本、执行了V92+V93；已把该专项目标固定为92，最新全迁移断言仍为93。失败原件保留。原测试Pod按UID清理，r2-stage-closed.json明确passed=false，没有部署。

最终候选R3包含运行和构建范围防护、迁移测试修正。远程 contract/start-r3b.py 正在等旧Pod实际退出后启动独立 eap-project-boundary-tests-20261025-r3；读取 start-r3b.log、tests-r3.log。不要重放原start/test。旧等待进程1221519已核对cmdline后停止，原任务未重复派发。R3预计绑定16+JWT7、完整后端1449，须以实际结果为准；还需隔离MySQL、安装器测试，再构建前后端、部署与四角色/浏览器验收、保护旧行、规范源推广、归档和文档更新。1004候选输入清单本地 source-manifest-r3.json；远程 source-manifest.json 在R3启动时更新。在线仍private-pilot-20261025-identity-restore-r1/Schema92，尚无本轮发布。最终源清单以R3为准，不使用首轮产物发布。

全部应用测试在远程；本机仅代码/文本/摘要。Vue技能及4份核心参考已读取，用/me而非JWT/角色决定前端权限。尚未进入通用取消/恢复、跨实例委派/代码归档、Go ADK内部观测、保留/容量与生产TLS/HA/灾备；这些仍属本次请求待完成工作，不可把当前项目边界阶段当作全主线完成。


### Codex 进展：项目独立部署边界已部署（2026-10-25）

发布 `private-pilot-20261025-project-boundary-r1`，后端 e2c82a95…、前端 c8133900…、框架 155c6cf5…保持，Schema93。新增数据库项目绑定与防改绑、精确 JWT eap_project 校验、/me 项目信息和控制台标识；原生/AX/源码构建在访问目标前校验专属空间，安装器支持 PROJECT_CONTROL_PLANE_BOOTSTRAP。采用每项目独立部署/数据库，**没有同库行级多租户；现有 PoC 仍为 SHARED_TRUST_DOMAIN，数据库未绑定项目**。项目 IdP、原生 RBAC/网络和业务上线还须独立验收。[边界](PROJECT_ISOLATION.md)、[证据](evidence/project-boundary-20261025/README.md)。

远程后端1449项/151类、绑定与范围16/签名JWT7（均为全量子集）、隔离MySQL8.4.11专项23、前端538/类型/构建、安装器39通过；线上四角色×两宽度8场景通过。另2个长项目名展示场景使用合成/me响应，不是线上双项目认证验收。两张截图人工复核。初次JWT夹具漏开Flyway、V92专项目标未固定及安装器清单遗漏的失败保留，最终修正后通过；磁盘门槛暂停仅续接前端，没有重复后端部署。

原业务/计划/预算、114执行事件/6报告、全部UNKNOWN保持，新增业务/模型/工具0。9临时Pod和1ConfigMap已清理，最终UP/Ready/重启0、节点无压力；imagefs阈值余量1240455372字节，下轮构建前复核。1014实现输入核验，23变化已推广；清单补纳10份既有交付工具。批次/root/eap-project-boundary-20261025-r1已关闭，禁止重放；803份导出文件验证，归档2683017字节/SHA256 `0a7317b554a50a974e30abf0cd3470e45b46d3912c1adb1d2e3352449ef3c9b9`。实际Secret/编码/JWT扫描通过。运行时/内核/长稳未重测。本机无应用测试/构建，未commit/push，原开发HEAD71e4111/空索引及公开4dcfa6d保持。

下一项继续通用停止/恢复、跨实例委派与代码归档、实际Go ADK内部观测、集中诊断/保留/容量及生产TLS/HA/灾备。保留旧UNKNOWN、Plan5/6/8/9、Agent87–115及所有已关闭批次；不宣称全部主线或生产交付完成。

### Codex 进展（进行中）：工作流与团队计划停止后续调度（2026-10-25）

用户仍为“继续吧，要修改就都完成”，没有新commit/push授权。项目隔离批次已完成；现在独立 `/root/eap-workflow-stop-20261025-r1`，本地准备 `/tmp/eap-workflow-stop-20261025`。V94、WorkflowStopService持久单一请求、全主体摘要幂等、一次审计、排队锁与worker节点边界owner fence；新执行stopVersion1、历史0不回填。运行中的当前步骤仍可完成，原回执/回收/预算保留，未知结果不变CANCELLED。计划状态投影CANCELLED/未执行步骤SKIPPED，原计划不可再提交为新执行。前端时间线两处入口和调度状态已补齐，/me能力控制，无JWT或角色解析。设计在docs/WORKFLOW_STOP.md。

目前远程专项stop-r3 12、plan-r3 20、permissions-r3 42均通过；完整backend-r3和MySQL12等待实际结果。前端R2 546及类型/构建通过，补调度状态中文后最终R3复验中。源1020份、21变化（含6新文件），本地source-manifest-final.json/远程contract/source-manifest.json为最终候选；尚未推广。失败均保留：首次只读完整性工具误用复合主键表id；后端测试夹具先加边后建目标；前端旧isolated脚本测试漏defineEmits；Mockito重设Answer提前调用null参数。均修正，没有改功能断言或为测试放开保护。当前首轮失败Pods复用，前端最终另开R3Pod；不能重放这些脚本。

远程 release-final.py.log（等待进程由旧1307896替换，旧未构建前停止）须等完整backend1467/152、MySQL和frontend-R3通过才构建/探针/部署，并执行live.py。browser-ops.log已BROWSER_READY，等待live-stop-proof.json；预期8真实角色场景+14只读合成状态场景。live.py只经EAP创建1个start/end控制节点工作流并停止；不创建/唤醒Agent，不调用模型/工具，保持旧UNKNOWN。状态写在live-state.json，未确认意图须先核对，不可重放或换键。

finish.py只在本地准备，尚未上传/运行；必须等真实上线、live和浏览器通过，按实际结果核对后封存、取回证据、更新发布清单/文档并CAS同步规范目录。当前线上仍project-boundary-r1/Schema93。原历史行摘要使用原列/原最大ID，允许新控制工作流和其观测行；所有原UNKNOWN、Plan5/6/8/9、Agent87–115、114事件/6报告不改。

磁盘仍低，重复导出只读审计duplicate-export-audit-r2.py进行中：核对原始文件、归档SHA及每个归档条目，允许finish.log仅追加完成回执，未删除任何历史导出。原始档、证据、镜像始终保留；需要清理时只删核验一致的export副本。保持>1GiB imagefs阈值余量，不能放宽门槛。

全部应用构建/测试/浏览器/部署远程；本机仅代码、文本/摘要。禁止新子agent、commit、push。尚未完成即时中断、通用补偿恢复、框架泛化/实际Go ADK子Agent、跨实例委派/消息/源码归档、集中诊断保留容量和生产TLS/HA/灾备长稳，不能把本批停止协议当作全部主线完成。

本批追加进度：backend-r3完整1467项/152类、前端最终R3 546/类型/构建均通过。MySQL首轮12项仅过期夹具失败，诊断确认隐式JDBC Timestamp与Hibernate Instant映射相差8小时；改用UTC Calendar并增加夹具已过期断言，H2 stop-r4 12和MySQL-stop-r4 12全部通过。产品代码未改，原完整回归/受测JAR保持；最新source-manifest-final.json只更新该测试文件。test-completion-r4.json已通过。当前最终发布脚本release-r4.py.log等待旧测试Pod退出后继续；旧release-final.py等待进程在未构建前停止，见utc-fixture-correction.json。finish.py本地已改为最终R4证明，仍未上传运行。三份逐字核验重复export副本已清理62375611字节，原始证据和完整归档保留。


### Codex 进展：停止调度与手机菜单修复完成封存（2026-10-25）

本节替代前面的本批“进行中/待发布/finish未执行”。发布private-pilot-20261025-workflow-stop-r1，后端98fb349f2708a4a28f71e5c4ddb4aa95d6483e8ffc2171b12ca833da655dabd9，最终前端80a620457108e5550c3b2b641171bb3ce94a9b7919871062ef706e59512876d8，框架155c6cf52557a95b8d3c2c5ffaead590cd9ae2c14e272c4fafc8903f8a0cf529保持，Schema94。V94持久停止、节点边界owner/epoch校验、幂等与调用者绑定、一次审计、CANCELLED计划投影和两处前端入口已交付；当前已获准步骤可完成，保留回执/回收/预算，未知不自动变停止。历史stop_version0不可改写，不提供强杀、自动重放、通用恢复或补偿。

远程完整backend-r3 1467项/152类、plan20/permissions42通过。MySQL首轮过期测试夹具UTC差8小时，修正测试并断言已过期后，H2 stop-r4 12/MySQL8.4.11 stop-r4 12通过；完整全量在夹具修正前通过，产品代码和JAR未变。最终前端546/类型/构建通过。首次浏览器发现长名称工作流弹层886px撑开390px手机页；限制最大宽度与选项换行，只重新构建/发布前端，无后端重部署/业务重放。最终8真实角色×宽度场景、14合成停止状态场景通过，显式核对菜单打开时边界；桌面/手机截图人工复核。所有首次失败与诊断保留，没有放宽功能断言或空间门槛。

EAP唯一新增Workflow57/runKey wf-exec-2f8c0e2a-b5f9-4960-aa8b-fa43c69aba35，只有START/END控制节点；APPLIED/CANCELLED、同键原调用者重放、更换键/主体409、四类JWT读取/停止权限、历史版本拒绝均通过。真实运行中任务中断没有线上注入，由隔离合同测试覆盖。Agent任务/模型/工具新增0，旧业务/预算/计划/UNKNOWN及114执行事件/6报告保持，停止表1行/审计1条；既有PoC项目绑定为空。11临时Pod/1ConfigMap清理，余量0，最终UP/Ready/重启0，节点无压力；imagefs阈值余量1206974668字节，后续构建前复核。只清理逐条匹配原始/归档的3份重复export副本62375611字节。

1020实现输入/21变化已同步到/root/eap-src并与本地逐一核验。/root/eap-workflow-stop-20261025-r1已关闭，禁止重放；归档3439193字节、SHA256 93bdc1893f0875e668083322cb76d47e0134625331550b6af6d6f8e713dc6f35，871文件本地核验，实际Secret/编码/JWT扫描通过。[证据](evidence/workflow-stop-20261025/README.md)。所有应用测试/构建/浏览器/部署均远程，本机只编辑/摘要/证据核验。本批运行时/内核/长稳未重测。没有新commit/push，原HEAD71e4111、空索引、历史工作区及公开4dcfa6d保持。

剩余主线：双项目实际IdP/RBAC/网络及业务验收、立即中断和通用恢复/补偿、框架泛化与真实Go ADK内部子Agent、跨实例委派/消息/源码原文归档、集中诊断/保留/容量、生产TLS/HA/灾备长稳。不能把本次增量称作全部主线完成。继续保留旧UNKNOWN、Plan5/6/8/9、Agent87–115及关闭批次。文档同步通过独立CAS批次处理，不改写封存证据。


### Codex 进展：统一运维诊断与诊断包保留计划完成（2026-10-25）

用户继续要求“要修改就都完成”，本批完成运维诊断缺口。eapctl新增diagnose、plan-diagnostic-retention，独立diagnostics模块只读采集backend/frontend Deployment、真实controller UID链Pod、命名空间PVC与承载节点；核对镜像、就绪、重启、卷绑定、节点压力和实际nodefs/imagefs硬驱逐余量/inode。采样时效120秒、额外预留默认1GiB；权限失败、列表截断、身份/代次变化、有效阈值未知不能显示绿色。容器文件系统别名不猜测，PoC两节点containerfs均UNKNOWN。白名单输出不含环境、命令、注解、错误正文或kubelet其他配置；产品不读Secret/日志，不执行业务和部署。诊断包0700/0600、防覆盖、SHA256；保留计划只读、按报告时间和命名空间保留最新N份，跳过篡改/残缺/链接/额外文件/未来时间，不删除文件或业务数据。

远程/root/eap-ops-diagnostics-20261025-r1最终61项交付工具测试通过（原39+新增22）；首轮60通过后，复核修正完整枚举后才限流，改为第1001项/包内第3项即停止，新增回归后全部复跑。真实CLI采集21PASS/0FAIL/2UNKNOWN，整体UNKNOWN/退出2正确；保留计划最终复查保护原包、删除0。实际Secret及编码/JWT仅在验收器RAM扫描、零匹配。原应用健康/镜像/配置、计划、预算与业务历史摘要保持；模型/工具新增0、临时集群资源0。node1 imagefs阈值余量1198790860字节，后续构建前重新读，不能视为持续容量保证。

1022实现输入/3变化已同步到/root/eap-src并与本地核验。应用仍private-pilot-20261025-workflow-stop-r1 / Schema94：backend98fb349f…、frontend80a62045…、框架155c6cf5…保持；不为运维脚本改动重建或重启应用。本批后端/MySQL/前端/浏览器/框架/内核/长稳均未重测；所有应用测试只在远程。本批已关闭，禁止重放；归档265409字节、SHA256 3d46f86a06e54482d06299aad980ca83924ae1c59c2e8a195e48de4e193c1680，53文件本地核验。[证据](evidence/ops-diagnostics-20261025/README.md)、[说明](OPERATIONS_DIAGNOSTICS.md)。文档通过独立CAS同步，不改写封存证据。

没有新commit/push，原HEAD71e4111/空索引/历史工作区及公开4dcfa6d保持。剩余主线：双项目真实IdP/RBAC/网络/业务、立即中断与通用恢复/补偿、框架泛化/真实Go ADK内部子Agent、跨实例委派/消息/源码原文归档、业务数据保留/容量长稳、生产TLS/HA和整体灾备。统一只读诊断已交付；保留计划不是自动清理。继续保留旧UNKNOWN、Plan5/6/8/9、Agent87–115与已关闭批次。

### Codex 进展：跨实例执行委派与代码交接观测（2026-10-25）

用户要求继续主线。本轮完成可选双方确认协议：Schema95新增独立关联表与串行写保护；SOURCE/TARGET分别复用当前实例身份和producer锁，原RUN_STARTED须已接收。来源只建立PENDING_CONFIRMATION，目标确认同一linkId/两端流与run/关系后才CONFIRMED；CODE_HANDOFF同时核对双方确切run下的代码摘要、大小和语言。来源随后退出或撤销不抹去历史声明，失效目标仍不能写；无执行许可、预算传播、父节点重写或业务重试。每来源流最多64条，读接口沿用PlatformPermission.VIEW_CONSOLE。

Python新增eap_links.py：不可变引用/envelope、原SQLite内有界outbox；只接受已确认原执行/代码事件，同编号不同内容拒绝，重开/封存只补原意图，fork不能复用父日志，回执格式与不授予权限严格核验。状态是最近回执；来源本地送达不表示目标已确认。当前事件后台补发器和固定框架Worker没有自动投递关联，应用需显式调用。Vue新增只读关联面板，按/me权限和同一查询生命周期清理过期结果，显示待确认/双方确认、代码元数据并跳转对方事件。使用已读取的vue-best-practices约定。

独立远程/root/eap-execution-links-20261025-r1：完整后端1477项/152类、观测H2专项40与权限42（全量子集）、隔离MySQL8.4.11专项40、Python SDK80、前端551/类型/生产构建全部通过，无失败/错误/跳过。SDK新增12项含双日志/不同Trace、双方独立代码捕获、丢回执重开同意图、严格回执、容量和敏感错误不落盘；Java新增10项含双身份、不可变重放、无来源/错误身份、确切run代码绑定、撤销/并发与权限。源码复核确认已有实例禁止换Agent归属；未将相同Trace/PID/摘要当作因果证据。

部署前后只按固定摘要更新前后端，未整份apply历史清单。后端f93d311d41828341a70a919812f983f675aada0e5e3cf4a028c1d2d12642b88c，前端6b096481c862f9af3770492be7a011ab856762ba2d95344504d142144aeac33f，框架155c6cf52557a95b8d3c2c5ffaead590cd9ae2c14e272c4fafc8903f8a0cf529保持；发布private-pilot-20261025-execution-links-r1、Schema95。非root后端探针、镜像载荷及前端全部静态文件/缓存/深链接核对通过。

线上四角色GET关联200，匿名读取401；匿名和四角色人类JWT均不能冒充机器上报（401），与/me.viewConsole一致。四角色×1440/390px共8真实登录/空关联读取及原4次逻辑执行场景通过；另12个关联展示场景明确合成，覆盖待确认、确认委派/代码、分页、503恢复和跳转真实目标事件。两张合成代码关联截图已人工复核。线上execution_links仍0；未执行两个真实原生实例的委派/代码业务链，不能把隔离写入或浏览器夹具说成该验收已完成。

两次空间门槛暂停：第一次在首构建前，第二次在后端成功/前端切换前；清理逐字摘要相同的10份额外导出目录202944031字节、旧JAR副本93028994字节及本次镜像构建上下文JAR副本，原始证据/归档/JAR保留。旧封存使用verdict/closedAt而非closed布尔，首清理脚本在删除前拒绝；核读合同后按原阶段续接。没有放宽1GiB阈值余量门槛，没有重跑业务或重复后端部署。最终余量3383204044字节；遗留只读整目录扫描已停止。

原业务/计划/预算/UNKNOWN、Agent87–115、Workflow57停止状态、114执行事件/6投递报告均保持；新增业务/模型/工具0。7临时Pod和1ConfigMap已按UID清理，UP/Ready/重启0、节点无压力；1030实现输入全部核验，14文件推广到/root/eap-src。实际Secret值及编码/JWT在远程内存扫描无匹配。封存归档2146676字节/SHA256 36e251c7f6c42db2be793c3cac701d8afa572962d8fc5c1e6f72555c1bfad750，763文件本地逐一核验，[证据](evidence/execution-links-20261025/README.md)。批次closed，禁止重放。

下一项：受控跨实例框架样例的真实EAP创建/调度/关联/收尾验收，保留原linkId、实例UID和身份代际；再继续双项目实际隔离、立即中断/恢复补偿、Go ADK内部观测、源码归档及生产交付。当前PoC仍共享未绑定项目。没有本轮Go/框架自动适配/内核/长稳重测，不宣称生产或所有主线完成。应用测试/构建/浏览器/部署全部远程，本机仅编辑和证据核验；无新commit/push授权，开发HEAD71e4111/空索引/历史工作区和公开4dcfa6d保持。

### Codex 进展：真实跨实例临时代码交接完成封存（2026-10-25）

独立可选 handoff 样例已完成 EAP 创建、IMAGE部署、工作流调度、观测和收尾。Agent116（LangGraph 1.2.17）生成固定公开程序，Agent117（AutoGen Core 0.7.5）在短进程执行；Python进程结束后，由原运行实例观察器确认原producer/run/link和身份代际。Workflow58 / `wf-exec-87f83762-eadb-4f63-ac58-5a572c01f51c` 成功：一次求和42、模型0；14事件、5逻辑执行、2代码登记和2条双方确认。后续4次关联报告只补原意图，没有再次执行代码。[接入](../runtime/handoff-sample/README.md)、[完整证据](evidence/handoff-sample-20261025/README.md)。

可选镜像c6e53ce1…，默认框架155c6cf5…保持。应用仍private-pilot-20261025-execution-links-r1 / Schema95，后端f93d311d…与前端6b096481…未重建或重启。新实例最终SUSPENDED/REVOKED/PAUSED，四角色桌面/手机8个真实控制台场景均通过；实际摘要和双向跳转可在“运行与观测 → 执行关系”按116/117查询。不能为读取证据重放Workflow58。

远程Go race原生62、框架11、交接3项通过（含子用例139/30/5），vet通过；SDK80、交接Python13、实际镜像内同一13项复验通过。初始编译/测试环境/夹具和AutoGen bytes序列化失败均保留，最终修正后通过。首次IMAGE端口8080被400拒绝，确认原请求没有部署/实例记录后改为已有身份协议的80与/readyz；未放宽约束，没有重放业务。

旧114事件/6报告及业务/计划/预算/UNKNOWN保持；总量现128事件/8报告、关联2。4临时Pod/2ConfigMap已清理；UP/Ready/重启0，最后imagefs阈值余量2723657932字节。1036实现输入核验、7文件推广。批次/root/eap-handoff-sample-20261025-r1已关闭，归档867815字节、SHA256 `01b44bcc6f64d76f22ca9a325b1108abb13c687f828cc11f810bc1e89d9d74ec`，132文件逐一核验，实际Secret/编码/JWT扫描通过。

限制：样例只允许逐字固定的公开三数求和程序，不是任意代码沙箱或通用框架追踪；工作流业务输出含该公开源码，观测接口只存元数据。父实例在子进程结束后报告，不伪称原进程仍存活；未知执行不重跑。默认模板未自动接入，通用源码归档/消息/恢复补偿仍待。本轮后端/MySQL/前端构建/内核/容量长稳未重测；所有应用工作均远程，无commit/push，原HEAD71e4111/空索引和公开4dcfa6d保持。

下一项继续真实Go ADK内部子Agent、通用跨消息关联与代码归档、双项目实际隔离、立即中断/恢复补偿、保留/容量长稳及生产TLS/HA/灾备。保护范围增加Agent116/117和Workflow58；保留旧UNKNOWN、Plan5/6/8/9、Agent87–115、Workflow57及所有封存批次。下方“线上关联0行/真实交接待验收”等均为前一批次的历史状态，以本节和PROJECT_MEMORY末尾为准。

### Codex 进展：按用户要求保存与本地提交（2026-10-25）

用户明确要求“保存和提交”，本轮授权本地 Git 提交，没有新的推送指令。提交保存当前 EAP 主线代码、配套产品文档和跨实例交接的已扫描验收证据；受测1036份实现输入逐一匹配封存清单，其余历史运行组件与已有公开提交4dcfa6d逐字核对。原开发分支为feat/galileo-evaluation-foundation、提交前HEAD71e4111。融资材料、录屏、会话记录及其他大体积原始归档继续保存在本地。

本轮只进行提交范围、文件摘要、敏感内容及Git差异核对，应用代码未再修改，引用前述远程测试与真实验收结果，没有在本机执行应用测试或构建，也不宣称新一轮全系统回归。发布、Schema95、已休眠/撤销实例和全部历史UNKNOWN保持。提交回执见本次对话；公开分支仍以原4dcfa6d为准，未来公开发布继续使用明确文件清单，不直接推送内部工作区历史。
