# 系统架构图片

项目首页现在使用[详细架构图](eap-system-architecture-detailed.png)，保留[简版总览](eap-system-overview.png)供快速浏览。两张图均使用内置 `image_gen` 生成，2026-10-23；未使用 CLI 或本地图片编辑脚本。

详细版为 1448 × 1086 PNG，补充了访问与身份、九组控制平面能力、原生/Kagent/AX 运行适配、实例生命周期、外部依赖、五类发现与观测入口，以及一次多智能体任务的六步链路。实线表示控制与调用，虚线表示发现与遥测；MySQL 由控制平面持久化链路连接，观测证据从运行实例和外部服务回流。

详细版 SHA256：`5841970055b940e40a7ca58c172c46df678036f5a04d2ca6418ea7917196a600`。

这是产品概念图。部署依赖和支持范围以项目 README 及能力文档为准；图中外部资源不表示已经适配任意业务系统。Kagent 为部分 ADK 集成，eBPF 提供网络证据，不能单独恢复加密流量中的工具参数。

## 简版生成提示词

```text
Use case: infographic-diagram
Asset type: README system overview illustration for EAP (Enterprise Agent Platform), a Chinese enterprise agent control plane.
Create a polished, technically clear landscape infographic, approximately 16:9, high resolution. It should communicate the lifecycle and architecture at a glance, with refined editorial typography, restrained deep navy, teal and warm white colors, subtle grid, generous whitespace, crisp thin connectors, flat layered cards, no 3D robots, no decorative circuitry, no fake dashboard screenshots.
Top left title: "EAP" and "Enterprise Agent Platform". Subtitle exactly: "企业智能体控制平面".
Upper lifecycle ribbon, five connected steps, exactly: "创建" → "编排" → "执行" → "观测" → "治理".
Main middle: a large outlined container titled "EAP CONTROL PLANE". Inside six neatly aligned cards in two rows with simple line icons, labels exactly: "Agent 目录", "Team · Plan · Workflow", "身份与权限", "模型网关与预算", "Trace · Tool · MCP", "安全策略与审计".
Below control plane, connect downward to a wide layer titled "RUNTIME ADAPTERS", containing "EAP Runner" and "Kagent ADK · 部分集成". Under that a clean layer titled "Substrate / Kubernetes", containing three small generic agent workload tiles "Agent A", "Agent B", "Agent C".
Along the bottom show three external resource boxes "模型服务", "Tools / MCP", "业务系统" with thin runtime connections. These are integration resources, not claims of complete support.
At the right, a narrow vertical lane titled "DISCOVERY & TELEMETRY", with labels "Host Observer", "OTLP", "eBPF · 网络证据", connecting both external workloads and the control plane. A short footnote exactly: "概念总览 · 能力范围以文档为准".
Keep every label legible, use correct Chinese glyphs. Correct hierarchy: EAP is control plane, runtime adapters execute via Substrate, model/tools are resources; discovery and telemetry return observations. Avoid implying eBPF alone recovers encrypted tool semantics. No internal network addresses, credentials, company names or production certification badges. White opaque background.
```

## 详细版生成与校正提示词

以简版图片为参考生成详细版，再分别校正观测方向、数据库连线。以下保留完整提示词；实际生成尺寸以文件元数据为准。

### 详细版

```text
Use case: infographic-diagram
Asset type: a detailed architecture infographic for the public README of EAP, an enterprise agent control plane.
Input image 1 is the existing EAP overview, the edit target and visual style reference. Redesign it into a substantially MORE DETAILED, HIGHLY LEGIBLE system architecture. Preserve its EAP identity, navy/teal/soft blue palette, white opaque background, restrained flat enterprise style and thin line icons. Expand the canvas vertically to roughly 4:3 landscape, at high resolution, ideally 2560×1920 or larger. This is a coherent readable architecture poster, not a screenshot, not a marketing collage. Use short exact Chinese labels below, clear grouping, large bold headers, aligned cards and comfortable whitespace. Do not cram prose into tiny type.

LAYOUT: compact title at top; entry layer; a broad main column occupying about 77% width with the control plane, runtime adapters and substrate layers stacked; a telemetry column on the right occupying 21% width; bottom external dependencies row; bottom task-lifecycle strip; a small boundary footer. Keep all information within safe margins. The resulting diagram should be more informative than the input without arbitrary invented features.

TITLE: "EAP · Enterprise Agent Platform"
Subtitle: "企业智能体控制平面 · 系统架构与任务链路"

ENTRY LAYER heading "访问与身份"
Three small equal horizontal boxes: "Vue 控制台", "REST API", "企业 IdP · OIDC / PKCE".
Arrow from console/API into control plane. Authentication link from IdP to the identity card below.

MAIN LAYER 1 heading "控制平面 · Spring Boot"
A 3 by 3 grid of NINE cards, each with the exact bold title and two short lines:
1. "Agent 资产" / "登记 · 发现 · 能力" / "版本 · 生命周期"
2. "团队与编排" / "Team → Plan → Workflow" / "分工 · 依赖 · 结果交接"
3. "任务与结果" / "派发 · 状态 · 执行快照" / "JSON 结构契约"
4. "身份与权限" / "/me 能力授权" / "实例身份验证 · 撤销"
5. "模型网关" / "统一模型接入 · 调用记录" / "上游凭据留在网关"
6. "预算账本" / "执行前预占 → 调用后结算" / "调用数 · 输出 Token · 工具额度"
7. "工具与安全" / "工具 / MCP 目录" / "ALLOW · ASK · DENY"
8. "评测与优化" / "数据集 · 提示词实验" / "有界优化 · 成本对照"
9. "运维与审计" / "审批 · 操作审计" / "UNKNOWN 保留与核对"
The nine cards are capabilities, not nine microservices. Visually distinguish model gateway and budget cards with a subtle teal accent.

MAIN LAYER 2 heading "运行适配层"
Three equal cards:
"EAP Runner" / "原生任务 · 显式会话"
"Kagent ADK · 部分集成" / "文本轮次 · 受控工具"
"AX Runner" / "异步任务 · 独立生命周期"
Arrow from control plane to this layer labelled "冻结配置 / 身份 / 派发".

MAIN LAYER 3 heading "Substrate / Kubernetes"
One clear subheading "实例创建 · 唤醒 · 挂起"
Three workload cards "Agent A", "Agent B", "Agent C", with a single shared caption "隔离运行实例 · 原任务回执".
This is an execution substrate; do not imply the adapters run outside all infrastructure.

RIGHT VERTICAL LANE heading "发现与观测"
Five compact cards, top-to-bottom:
"Discovery Agent" / "资产候选 · 进程 · 连接"
"Host Observer" / "宿主与运行时证据"
"MCP 宿主观测" / "stdio / HTTP · 需配置"
"应用遥测" / "OTLP · Trace · 调用事件"
"eBPF 网络证据" / "连接元数据 · 非语义还原"
At lane bottom a short aggregation label "按任务 / 实例 / 调用关联".
Use teal dashed arrows from execution layer and external tools into this lane; a teal return arrow from lane to control plane labelled "证据回流". Never draw this as mandatory inline execution through the observers.

EXTERNAL DEPENDENCIES bottom row four boxes:
"MySQL / Flyway" / "配置 · 账本 · 执行记录"
"模型服务" / "模型请求经网关"
"Tools / MCP" / "按合同接入与授权"
"业务系统" / "按场景适配"
Use restrained connectors with labels to make clear MySQL persists control-plane state, model requests go through the model gateway and budget admission, tool/MCP execution requires a supported tool contract. Do not imply Kagent supports arbitrary MCP execution. Avoid crossing connectors over labels.

BOTTOM FLOW STRIP heading "一次多智能体任务"
A coherent sequential chain of SIX numbered boxes:
"① 创建 Agent"
"② Team / Plan 编排"
"③ 权限与预算准入"
"④ Agent A → 契约校验 → Agent B"
"⑤ 原结果 / 观测 / 结算"
"⑥ 核对后挂起"
Make step 4 wider. This workflow explains the concept, it must not promise guaranteed semantic correctness or automatic recovery.
Small legend: "实线：控制与调用   虚线：发现与遥测".
Small footer, verbatim: "有实施支持的私有化试点 · Kagent 为部分集成 · MCP 观测需配置 · eBPF 不还原加密业务语义"
Second footer line: "UNKNOWN 不自动重放；自动回收可能受历史占用阻止。能力边界以 README 为准。"

CONSTRAINTS: correct simplified Chinese typography, no misspellings, no fake public endpoints, no internal hostnames, no passwords or keys, no production-readiness badges. Do not claim full Kagent integration, universal zero-configuration observability, automatic compensation, multi-tenant isolation, HA or disaster recovery. No 3D robots, circuitry decoration, or ornamental text. Make architecture and arrows technically truthful. The exact wording above should remain concise and readable.
```

### 连线校正

```text
Use case: precise-object-edit
Input image is the newly generated detailed EAP architecture diagram. Keep ALL titles, Chinese text, cards, icons, layout, colors, and overall information unchanged. Make only these technical connector corrections, adding narrow left margin if needed:

1. TELEMETRY DIRECTION: the dashed teal branch currently points LEFT into the Substrate/Kubernetes box. Reverse this so telemetry originates FROM the execution/substrate layer and travels RIGHT to the collector spine, then UP into the right-side "发现与观测" lane. Likewise reverse the dashed teal branch currently pointing LEFT into the external dependencies row: it must originate FROM the "Tools / MCP" / "业务系统" region, travel RIGHT and then UP into the observation lane. No arrowheads should point from telemetry collection back INTO runtime or tools. Keep the existing "证据回流" arrow correctly pointing from the observation lane LEFT into the control plane. Keep the small direct runtime-adapter-to-observation dashed arrow pointing right.

2. DATABASE CONNECTION: MySQL / Flyway persists the CONTROL PLANE. Remove the MySQL branch from the runtime/external dependency horizontal bus. Add a clean dedicated thin NAVY connector from the LEFT EDGE of the "控制平面 · Spring Boot" container down along the OUTSIDE LEFT MARGIN, and into the LEFT EDGE of "MySQL / Flyway", with the arrowhead at MySQL. It must not touch or originate in the Substrate layer. Retain the label "持久化控制平面状态" near this dedicated storage relation. Other external model/tool/business connections remain and retain their existing gateway/contract labels.

No new capabilities. No other rewriting, no truncation of labels, no decorative changes. Preserve opaque white background. High resolution and crisp Chinese text.
```

### 清除重复数据库箭头

```text
Use case: precise-object-edit.
Use this detailed EAP architecture image as the edit target. Preserve the entire image exactly, including all text, typography, colors, cards, and telemetry arrows.

There are currently TWO arrowheads entering the MySQL/Flyway card. Keep ONLY the arrow coming from the far-left vertical navy connector that originates at the control-plane box.

Erase the obsolete SHORT VERTICAL DOWNWARD arrow above MySQL, positioned immediately to the right of the "外部依赖" heading badge and immediately to the left of the text "持久化控制平面状态". In the shown 1448×1086 layout it is near x=177, from y=826 down to y=873. Remove that short vertical stroke AND its downward triangle completely, restoring the white background. Retain the adjacent label and all container borders. Do not touch the long far-left navy connector or its right-pointing arrowhead into MySQL. Do not add any other arrow or alter any other part of the image. The final MySQL box must have exactly ONE incoming arrow, from the control plane.
```
