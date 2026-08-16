# EAP 项目记忆

> 最近更新：2026-08-15。此文件用于跨会话保存项目共识、已完成能力、验证基线和下一步工作；具体实现以代码、数据库迁移和测试为准。

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

## 3. 最近验证基线

- 后端测试：`mvn -q -f backend/pom.xml package`，69 tests，0 failures，0 errors。
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
5. 增加前端端到端自动化测试，覆盖分页、筛选、Trace 钻取、治理审批、控制命令和错误状态。
6. 在生产部署中启用 MySQL、JWT、机器凭证、保密信息 fail-closed、审计留存和数据生命周期策略。

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
