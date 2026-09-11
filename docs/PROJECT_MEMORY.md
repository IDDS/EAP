# EAP 项目记忆

> 最近更新：2026-09-11。此文件用于跨会话保存项目共识、已完成能力、验证基线和下一步工作；具体实现以代码、数据库迁移和测试为准。

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
