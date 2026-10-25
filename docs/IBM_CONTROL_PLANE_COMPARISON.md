# IBM Control Plane 与 EAP 主线对照

访问日期：2026-10-08。结论：用户提供的是 **IBM watsonx Orchestrate 的 Control Plane 演示**，与 EAP 的企业 Agent 控制平面定位接近。可以借鉴其统一运营入口；EAP 的后续交付仍围绕调度、Agent 调优、权限治理，以及这些能力的真实证据推进。本次仅研究公开材料和对照本地代码，没有运行 IBM 平台、注册外部 Agent 或修改业务代码、部署配置。

已读取[原演示页面](https://demo-now.techzone.ibm.com/psl/lw5902sz?g=cmtbwqm38000s0akx3xea7kg1&s=2)内嵌的 33 步导览数据，页面标题确认产品身份。网页读取工具首次失败，公开 HTML 随后读取成功；没有进行浏览器点击或运行时验收。导览围绕总览、使用情况、成本、质量、可靠性和安全风险组织信息，以待处理问题连接到具体资产和指标。这能证明演示的信息组织，不能证明真实负载、权限阻断或优化收益。

IBM 官方将控制平面定位为跨 Agent 的观测、治理和优化入口，并列出评估、审计、策略、身份和统一目录。[产品页](https://www.ibm.com/products/watsonx-orchestrate/agent-control-plane)支持这一定位；这些是厂商功能说明，尚未由本项目独立验证。

| 对照维度 | IBM 公开证据 | EAP 已有能力与边界 |
| --- | --- | --- |
| 运营入口 | 演示按六类运营问题组织指标与资产 | 已有观测、调度画像、预算与实验页面；统一的待处理问题入口和全入口关联仍待完成 |
| 观测完整性 | 官方介绍统一分析和问题调查 | T7 原实验46的两条 Trace，各有一条 Agent Span 与一条成功 GATEWAY 调用事件；旧缺失保留，执行成功、费用和覆盖分别呈现 |
| Agent 调优 | 产品页列出质量、成本、安全评估与优化 | 已有版本冻结、完整留出批次及成本；root5 与独立对照不支持采用候选，保持 KEEP_BASELINE，未确认生产收益 |
| 调度 | 官方发布说明支持周期任务自动执行 | EAP 已验证申请人轮转、等待老化和阶段责任；单工作进程、非抢占，可信租户、资源配额和跨入口调度仍待 |
| 权限与身份 | 官方发布说明列出运行时策略、凭证健康和访问视图 | 中央 PlatformPermission、实例独立身份、自然续期/撤销及受控工具检查已有限验收；真实业务工具和旧共享凭证迁移仍待 |
| 资产与生态 | 官方发布说明列出有版本和依赖的共享目录 | EAP 有 Agent/实例/发布登记；跨组织资产复用、兼容性和依赖发布不据此认定已完成 |

IBM 的[2026-07-02 发布说明](https://www.ibm.com/new/announcements/introducing-the-agentic-control-plane)分别介绍运营告警、凭证与策略管理、共享目录及周期任务。这里的 scheduling 是定时触发，不能等同于公平队列、CPU 配额或资源阶段调度；该来源也没有证明 EAP 所需的同到达负载收益或效果幂等保证。

EAP 对照依据为 [PROJECT_MEMORY.md](PROJECT_MEMORY.md) 最新 T3-E/T7 记录及实际实现：[观测查询](../backend/src/main/java/com/eap/platform/experiment/AgentExperimentTelemetryService.java)、[Runner 协议](../runtime/agent-runner/main.go)、[中央权限](../backend/src/main/java/com/eap/platform/config/PlatformPermission.java)、[优化门禁](../backend/src/main/java/com/eap/platform/optimization/OptimizationService.java)，以及 [EXPERIMENT_TELEMETRY.md](EXPERIMENT_TELEMETRY.md)、[WORKFLOW_SCHEDULING.md](WORKFLOW_SCHEDULING.md)。T7 后端最终远程完整回归为634项/95类；应用及真实验收已完成。对照初期，六组临时资源清理与归档接收受跳板 banner 超时阻塞；随后原通道恢复，按冻结UID清理并接收核对归档，[最终回执](evidence/eap-experiment-telemetry-20261008-final.json)已保存。本实验入口阶段闭环不代表全入口观测或全主线完成。

**证据强度与未知项。** 演示标题和导览结构有页面直接证据；IBM 功能定位有产品页及带日期发布说明交叉支持。外部接入另有官方操作文档和开发者文章，属于不同来源类型，但都来自 IBM，不是独立第三方验证。官方文档的直接打开返回403，已读信息来自搜索索引正文。未验证 IBM 实际版本、租户许可、价格、私有化功能对等、延迟、数据留存或故障期间的执行保证。

**接入选择。** EAP 原生路线可先完成只读运营入口，把缺失观测、未知回执、预算占用和凭证问题链接到原记录；治理判断仍由现有权限与预算合同承担。外部 IBM 路线需要显式协议适配：官方[第三方 Agent 接入说明](https://www.ibm.com/docs/en/watsonx/watson-orchestrate/base?topic=platforms-adding-agents-from-third-party)列出 A2A 或兼容的聊天接口，EAP 当前 Runner 的 `/eap/task`、`/eap/experiments/run` 并不会因此自动兼容。IBM [开发者文章](https://developer.ibm.com/articles/watsonx-orchestrate-agentic-control-plane/)还介绍了外部 Agent 与 OpenTelemetry 接入；实际属性映射、Trace 连续性和数据边界仍须逐项测试。

共同使用两个控制平面时，必须明确谁最终授权、谁派发原操作、谁结算原消费、谁保留 UNKNOWN；不能因外部返回成功而绕过 EAP 的身份、预算或停止门禁。仅注册可访问端点，也不证明能约束该端点背后的所有模型和工具。官方[Agent 间连接说明](https://www.ibm.com/docs/en/watsonx/watson-orchestrate/base?topic=center-adding-agent-agent-connection)提示相关安全中心导航在本地部署环境不可用，不能从云端演示推定私有化同等能力。本次没有调整既有 Galileo 接入方案，也不把 Galileo 评估能力、EAP 原生执行治理和双方配合的能力相互替代。

**有界 PoC 候选计划，尚未执行。** 若后续选择验证互操作，在远程 PoC 环境使用独立公开回显夹具和独立测试身份，模型调用与工具副作用上限均为0；不导出源码、秘密或私有遥测。先测试成功回执、身份不匹配、执行超时和相同意图重复读取四类场景，逐条核对原操作键、冻结实例归属、Trace 和实际请求数。超时保持未知，原意图不换键重发；撤销后验证拒绝，并确认历史记录仍可按权限读取。只有协议、权限和证据边界通过，才另行设计有预算的新模型评测；此计划没有授予新的消费额度。

主线顺序保持：先完成 T7 清理与证据接收，再补全入口观测和统一问题定位；继续可信租户/资源配额、跨入口调度、真实业务工具及旧消费者迁移、取消/补偿与联合策略。IBM 演示作为产品和能力对照依据，不把它的展示数值写成 EAP 验收结果。
