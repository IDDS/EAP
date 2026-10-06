# 外部运行实例的受控接入

## 使用路径

先在“发现与接入 → 运行环境发现”复核纳管，再点击该实例的“接入验证”。四项状态分别表示实例身份、任务握手、实际受管模型调用和已关联调用链，不能相互替代。

1. operator/admin 生成实例凭证，有效期24小时。配置值只保留在当前页面内存，关闭面板、身份或权限变化立即清除；不写浏览器存储。只有生成接口会返回凭证，响应标记 no-store。只读查询不会返回凭证。
2. 通过程序所有者的安全配置渠道安装 EAP_AGENT_ID、EAP_RUNTIME_KEY、EAP_GATEWAY_URL、EAP_RUNTIME_TOKEN。原程序必须实现任务和握手协议。当前通用 Runner 已提供参考实现；配对配置入口默认关闭，仅显式配置独立 EAP_PAIRING_TOKEN 时可用。它要求所有者凭证，只修改内存中的当前实例令牌，响应不回显令牌。
3. 点击验证连接：检查当前UID/模板；必要时唤醒，发送随机挑战并验证签名、归属和 EAP_HTTP_TASK_V1 声明。原实例若休眠，检查后尝试恢复休眠；无法确认会显示失败并保持不可派发。握手没有模型调用，不自动启用身份。
4. 在目录启用智能体，通过 Workflow/Team/Plan 的常规执行运行任务。派发前重新核对实例身份并使用实例凭证。实际模型与调用链到达后才显示相应证据。
5. 可显式换发或撤销。换发后旧凭证立即无效，新凭证需重新配置和握手；撤销/过期后拒绝新任务选择、派发、模型请求与观测上报。已有请求不保证被中断，业务未知结果不自动重放。

## 接口与权限

| 接口 | 权限/用途 |
| --- | --- |
| GET /api/registry/runtime-connection?instanceKey=... | VIEW_CONSOLE；只读状态 |
| POST /api/registry/runtime-connection/credential | DEPLOY_AGENTS；instanceKey + UUID requestKey，当前请求重放返回同一有效凭证 |
| POST /api/registry/runtime-connection/verify | DEPLOY_AGENTS；instanceKey；会唤醒并尽量恢复原休眠状态 |
| POST /api/registry/runtime-connection/revoke | DEPLOY_AGENTS；instanceKey |
| POST /api/runtime-access/v1/chat/completions | 实例凭证；非流式 OpenAI 请求体 |
| POST /api/runtime-access/v1/traces | 实例凭证；OTLP JSON scopeSpans，最多128 Span、256KiB |

机器请求携带 X-EAP-Runtime-Key，加 X-EAP-Runtime-Token 或 Bearer 实例令牌。机器凭证只能进入这些受限机器接口及工具检查/领取/回执接口，不能作为人类控制台JWT。模型归属和endpoint由凭证固定，拒绝伪造Agent、endpoint、taskId或复用其他治理决策。凭证仅授权有效、已握手且目录ACTIVE的绑定实例；每次检查当前UID/模板和允许空间。

OTLP资源/Span若声明Agent身份，必须匹配凭证；完整输入先校验再写入。服务端固定agent与endpoint，将Trace/Span ID映射到实例命名空间，避免覆盖其他实例记录。模型请求与OTLP使用相同Trace映射；父Span一致映射，外部跨实例links不采信。Trace为非零32位十六进制，Span为非零16位十六进制。本通道仅支持JSON；现有平台通道保持原有格式。

模型成功回执才记录网关证据；只有已保存、属于同一实例和Agent并匹配模型Trace的记录，才标记关联观测。任务协议握手只确认密钥持有者的协议声明；实际业务完成仍必须由对应taskKey的终态回执确认。

## 握手合同

EAP经配置的运行路由向 /eap/integration POST {nonce}，不发送任何平台凭证或实例凭证。程序返回 nonce、agentId、instanceKey、protocol=EAP_HTTP_TASK_V1、proof。

proof = 无填充 Base64URL(HMAC-SHA256(EAP_RUNTIME_TOKEN, nonce + "|" + agentId + "|" + instanceKey + "|EAP_HTTP_TASK_V1"))。

任务入口仍为 /eap/task，Authorization: Bearer 实例凭证；保持taskKey匹配、终态、幂等和不确定结果不自动重放的现有合同。当前Runner同步等待观测上报后返回任务回执，避免立即休眠冻结未启动的上报。完成后自动休眠须在任务节点 configJson 中设置 suspendAfterTask=true，执行提交请求顶层不接收该选项。

## 存储与部署

V53 runtime_connections 仅保存随机生成号、请求标识、有效期、握手和调用证据元数据，不存明文实例令牌。令牌由独立签名密钥和绑定的空间/实例、Agent、UID、模板及生成号派生；平台旧任务凭证不作为派生密钥。乐观锁避免并发修改覆盖当前凭证状态。

EAP_RUNTIME_INTEGRATION_SIGNING_KEY 引用集群 Secret eap-runtime-integration/signing-key。K8Substrate tools/eap/runtime-connection/configure.py 在节点内存生成并存入Secret，重复执行复用，不能输出密钥或复制到本地。手工轮换签名密钥后，应为外部实例显式换发并重新验证。

## 当前边界

- 接入证据覆盖本通道，不自动修改既有外部实例的出站策略，不能据此宣称所有模型/工具访问都受控。工具事件可经OTLP观测，工具执行前检查、一次性许可与结果回执已有参考客户端，仍需原程序适配，详见 [工具执行前检查](RUNTIME_TOOL_GUARD.md)。
- 24小时凭证需人工换发，尚无自动续期/外部密钥系统配送。现有平台托管实例仍沿用原凭证方式；本轮未迁移所有旧模板。
- 底层模板不可变且不支持Secret引用。所有者须提供安全的运行配置渠道；PoC参考程序可显式启用独立所有者配置入口。当前HTTP/运行平台明文配置资源仍属于PoC边界，不是生产凭证托管方案。
- UID/模板在关键操作前后检查，但上游名称式生命周期API没有原子UID条件，外部并发删除重建的检查/执行竞态仍存在。不会自动接管异步任务控制器维护的池。
- 不宣称并行编排、取消补偿、重启续跑、生产TLS/容灾已经完成。

## 集群验收与修复记录（2026-09-28）

使用独立外部实例、Agent19 / Workflow10 验证，不修改既有演示实例。签名握手、轮换失效、伪造归属拒绝及角色权限已通过；握手不会调用模型。首次真实任务发现受控通道没有预先登记发现端点，模型结果记账失败，执行记录保留为FAILED。现已改为在调用模型前登记端点，并增加真实网关/遥测/数据库集成回归。该失败发生在结果记账处，零调用记录不能证明没有上游模型消耗，不自动重放原任务。

后端316/316、前端123/123与生产构建、参考Runner5/5在远程通过。最终新任务、浏览器、撤销与镜像证据以 PROJECT_MEMORY.md 最新条目及 K8Substrate artifacts/eap-runtime-connection-20260928/ 为准。

修复后的独立新任务已成功，记录1次成功模型调用/641 tokens，并关联治理决策与Trace。首次失败没有完整用量记录，不能将641 tokens当作两次任务的总消耗。验收脚本的休眠选项曾误放到提交请求顶层，因此本次成功任务后通过显式PAUSE清理；不计为外部实例的自动休眠验收，不为此重放模型任务。脚本已改为节点配置。

## 工具接入补充（2026-09-28）

接入验证面板增加最近30条工具检查与回执，区分检查允许、待复核、阻断、许可已领取和执行方报告。三项新增机器接口 /api/runtime-access/v1/tools/check、/claim、/receipt 继续使用有效实例凭证；原始参数和结果不保存。不是任意工具自动接管，也不代表已经提供通用MCP执行器。详见 [工具合同](RUNTIME_TOOL_GUARD.md)。
