# 系统总览图

`eap-system-overview.png` 用于项目首页，使用内置 `image_gen` 生成，2026-10-23。

这是产品概念图。部署依赖和支持范围以项目 README 及能力文档为准；图中外部资源不表示已经适配任意业务系统。Kagent 为部分 ADK 集成，eBPF 提供网络证据，不能单独恢复加密流量中的工具参数。

生成提示词：

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
