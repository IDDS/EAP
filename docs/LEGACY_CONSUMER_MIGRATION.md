# 仓库工具与旧发布迁移清单

2026-10-11。用户已确认范围为仓库现有工具和旧发布。当前清单中的 12 个 READY 旧发布头均已通过新版本迁移核验：10 RUNNER、2 IMAGE。新实例全部休眠、连接撤销；旧 Actor、历史发布、预算和效果保留。Agent7/v6/SOURCE 原 FAILED 不重试，共享凭证未退役。

## 最新 IMAGE 迁移结果

Agent12 与 Agent44 均 v1→v2，来源仍为 IMAGE、身份为 PER_INSTANCE_V1，端口/readiness/command/env/沙箱/规格/出站配置逐项保持。Agent12 保留原演示程序的任务计数、同键去重与会话状态，新增 [V1 身份适配源码](../runtime/demo-agent/README.md)，使用原固定镜像基础和新二进制，未替换成模型 Runner。新固定镜像为 `10.186.133.66:5001/eap-demo-agent@sha256:558cfe8d29b9d230e88263fceb069c0ea89ca581eca924031497f1a3123df7f9`。Agent44 原程序即 EAP Runner；隔离对照通过后升级同系列 `43e11255…` 固定镜像，保留默认模型、系统提示词、输出上限、任务/对话输入输出及去重行为。

从原镜像按固定 manifest/config/layer 提取二进制，在隔离 Pod 内与新程序对照：演示程序任务/重复键/会话状态，以及 Runner 默认请求参数/历史/任务/对话响应一致。全部 Gateway 请求终止于 Pod loopback 夹具，非真实供应商。V1 伪造签名/跨 Agent/UID 漂移/缺投影/过期/迟到旧配送与并发同键测试，普通、竞态各 8 项，零失败/跳过；编译及镜像摘要/入口/二进制核对通过。这是所列合同的有界兼容验证，不证明所有任意负载或真实模型成功。

实际新 Actor 的原创建 UID 与加密配送确认通过。Agent12 新 UID `0fd559f0-93a0-475a-9240-97a389fee632`；Agent44 新 UID `08b38a59-e66b-488d-b5b2-23f836f19d0c`，并核对实时默认业务 profile。两者最终 SUSPENDED/REVOKED、工具策略关闭，无旧授权/根额度继承、无真实模型/工具调用。没有在实际 Actor 上发送业务任务，业务响应对照属于隔离二进制测试。

[最终回执](evidence/eap-image-migration-20261011-final.json)、[兼容测试](evidence/eap-image-migration-20261011/tests-proof.json)、[逐版本/UID/配送及 profile](evidence/eap-image-migration-20261011/state.json)。归档 208225 bytes / SHA256 `8b24c70e61474907143bcffcf7d3a4361de0b08bd29c33212479d6dce88892d4`，37 摘要、7 新增源副本、原 750 源及 4440 旧证据核对通过；接收后精确推进新 7 文件，当前绿色源 757。53 表 3119 原行、128 旧 Actor、86 旧模板、全部预算及两根关闭结果保持，2 Job/2 Pod 按 UID 清理剩余 0；实际 Secret 只在节点 RAM 扫描，未 commit/push。下面为早期 RUNNER 结果和原始盘点，IMAGE 当时待适配的描述保留历史含义。

## 本轮迁移结果

| Agent | 原版本 | 新版本 |
| --- | --- | --- |
| 5、8、9、43 | v2 | v3 |
| 10、11、14、16、17、45 | v1 | v2 |

10 个新发布均 `PER_INSTANCE_V1 / READY`，使用固定已验证 Runner `10.186.133.66:5001/eap-runner@sha256:2146291f373a2dd1542efff053610bc663817f9e94bcca686c39802575ff78c6`。原提示词、模型、规格、沙箱、出站配置及调用方 env 均核对；新 UID/模板、配送 ACK、机器身份、实际 profile 与工具策略关闭通过。profile 会唤醒实例，已在请求后重新查询同 UID 并休眠；10 个新实例最终均 SUSPENDED、连接 REVOKED。原工具 grant、根预算及恢复责任没有继承，也未重发旧任务。

Agent12/v1 与 Agent44/v1 的原固定 manifest/config/layer 和程序摘要核对通过；两个二进制均缺少 `EAP_IDENTITY_PUBLIC_KEY`、challenge/install 路径及 `EAP_IDENTITY_V1` 标记。此为静态兼容检查，未执行镜像或唤醒原 Actor；不能宣称原程序具备 V1 配送协议，也未把 IMAGE 改成 RUNNER。下一步须确认原源码与业务行为，再做保留业务程序的身份适配。Agent7/v6/SOURCE 原 FAILED 保持，未重试。

[最终回执](evidence/eap-legacy-migration-20261011-final.json)、[逐实例原意图与 profile](evidence/eap-legacy-migration-20261011/state.json)、[IMAGE 协议检查](evidence/eap-legacy-migration-20261011/custom-image-protocol-inspection.json)、[历史保护](evidence/eap-legacy-migration-20261011/protection-after.json)。归档 190636 bytes / SHA256 `774c12e535ab5141c6ae79213ca622a49dd9674d000262485b06a40dc24499f0`，16 项摘要、750 源码、4368 项旧证据核对通过。53 表 3036 原行、117 原 Actor、75 原模板、全部原预算及两个团队根关闭结果保持。实际模型/工具新增均 0；未改业务源码或应用部署，无新构建测试（沿固定镜像既有证据），未 commit/push。旧共享引用仍存在，退役条件尚未满足。

## 原始盘点对象（迁移前）

[模板及引用清单](evidence/eap-mainline-migration-inventory-20261011.json)共 73 个模板，39 个仍引用旧共享凭证；34 个旧模板被当前可见的 35 个 Actor 引用，35 个均为 SUSPENDED。其余 5 个模板在本次可见 Actor 列表中没有引用，不能据此证明没有快照、挂载或外部消费者。全局与各作用域模板计数一致，不证明上游分页完整。

[完整发布版本清单](evidence/eap-mainline-migration-heads-20261011.json)包含 39 条发布；22 条为旧身份模式，其中 READY 17 条、FAILED 5 条，涉及 13 个 Agent。按每个 Agent 的最新版本重新判断后，当前迁移候选是 12 条 READY，另有 1 条最新版本 FAILED；13 个 Agent 的最新版本均尚未改为 PER_INSTANCE_V1。不能把 22 条历史记录全部作为新部署队列，不能重试历史 FAILED 来补齐清单。

| Agent | 原最新版本 | 来源 | 下一步 |
| --- | --- | --- | --- |
| 5、8、9、43 | v2 | RUNNER | 核对原配置与固定新 Runner，创建新发布意图，保留原版本及 UID |
| 10、11、14、16、17、45 | v1 | RUNNER | 同上；逐个核对专属身份及实时 profile，不调用模型 |
| 12、44 | v1 | IMAGE | 先核对原镜像的身份配送协议与端口，不能直接改成 RUNNER 或更换业务程序 |
| 7 | v6 | SOURCE，FAILED | 保留原失败，不自动构建、重试或回退到较早 READY 版本 |

## 仓库执行工具

| 工具 | 当前执行边界 | 迁移核对项 |
| --- | --- | --- |
| `calculate_sum` | Runner 显式白名单、固定有限数字 schema、原意图日志及平台 check/claim/receipt；确定性纯计算 | 新专属身份、原请求指纹、原工具策略及共享费用归属；不把程序回执提升为独立外部效果 |
| `controlled_counter_increment` / `poc-counter-v1` | 受控存储事务、原授权链与 UID、持久效果及原操作查询、执行侧责任屏障 | 新 UID 不能继承旧授权、旧工具根额度或旧恢复责任；历史 UNKNOWN/COMMITTED 保持，不为迁移重放效果 |

依据为 [tools.go](../runtime/agent-runner/tools.go)、[WorkflowToolConfig](../backend/src/main/java/com/eap/platform/workflow/WorkflowToolConfig.java)、[工具合同](SCOPED_TOOL_CONTRACTS.md)和[恢复责任屏障](WORKFLOW_TOOL_EFFECT_FENCE.md)。MCP 发现、工具 allowlist 和观测记录不证明存在可执行效果适配器。当前范围不新增任意 MCP 或外部业务系统写入。

## 执行顺序与完成条件

1. 对 12 个当前 READY 候选核对 Agent 生命周期、原最新发布和配置摘要；保留原 UID、模板、连接、批准、预算、版本及历史操作。IMAGE 先做协议兼容验证，未确认则保留待适配。
2. RUNNER 逐个创建独立新发布版本和新实例，用已验证固定镜像及专属安全配送。配置差异逐字段可解释；不复制共享凭证、旧私钥、旧工具授权或未知操作，不扩大旧额度。
3. 每个新实例先验证实际 UID/模板、配送 ACK、机器认证与只读 profile；身份、模型配置或共享容量不符即停。模型验收另须明确独立支出，零模型迁移检查不授权模型调用。
4. 新实例核验后休眠，按实际使用需求管理其专属批准；旧实例继续休眠。旧模板和历史发布保留，新旧身份不能混用。
5. 当前 READY 消费者迁移后，继续核对挂载、快照、AX/演示及其他消费者。只有完整消费者集合和退役验证满足后才退役共享凭证；当前这项条件尚未成立。

盘点只执行原 n1.sh 通道的只读 API/SELECT；实际 Secret 及编码形式只在节点 RAM 扫描。没有业务调用、模型或工具新增消费，没有部署状态修改。模板清单 53103 bytes / SHA256 `3908066c29977fa67d2c9699ec5efddd88c8c527668989cfea38fea5e4c63ec8`；发布清单 22752 bytes / SHA256 `9711fa29a9a292163cbb69ffd4778ac4059f29cbc444e0fb9f15fdb64dff7ca5`，本地接收已核对。
