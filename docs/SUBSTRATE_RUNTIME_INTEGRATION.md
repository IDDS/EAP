# EAP × Agent Substrate 运行时集成设计

> 日期：2026-09-26。状态：设计稿，尚未实现。本文只做设计，没有修改 EAP 业务代码或部署状态。
> 依据：EAP 当前代码（0.3.1.0）；Agent Substrate `github.com/agent-substrate/substrate` 提交 d277088b，该提交已在两节点集群 10.186.133.66/.44 上部署并完成 e2e 验证。
> 标注约定：**[已验证]** 表示在该集群上实测过；**[代码确认]** 表示从源码或文档读到但未实测；**[待验证]** 表示推断或尚需 PoC 证明。

## 1. 结论

- **可以集成。** 由 EAP 负责管理和治理：身份、编排、观测、治理、审计和界面；由 Substrate 负责执行：沙箱隔离、挂起与恢复、高密度复用、按 actor 路由、出站管控。EAP 文档写明“尚未接入真实业务 Runtime”，Substrate 正好补上这一环。Substrate 本身没有管理界面、内容治理和空闲回收，这些由 EAP 补上。
- **集成价值最大的一点：** 用 Substrate 的出站策略强制 agent 只能访问 EAP 治理网关。
  - 模型调用无法绕过 EAP 的预检、脱敏、审计和 Token 归因。
  - 模型 API key 只保存在网关中，agent 拿不到。
- **主要风险：**
  - Substrate 仍处于早期，官方声明不适合生产，API 可能变化。
  - 同步派发协议与路由器超时是否兼容，需要实测。
  - 目前集群为 2 台 4C8G 节点，资源紧张。
- **建议：** 按第 9 节分阶段做 PoC。第一步只需新增一个控制适配器、一种派发方式和一份部署清单，不改 EAP 现有数据模型。
- **前端部署：** 用户在 EAP 控制台用向导部署 agent，支持零代码配置、已有镜像、源码构建三种来源，并可在前端管理版本和实例，见第 6 节。

## 2. 职责划分

```text
            ┌──────────────────────── EAP（控制平面） ────────────────────────┐
  用户 ───▶ │ 控制台 · Agent 目录 · Team/Plan/工作流 · 治理 · 审计 · 评估 · 价值 │
            │    │控制命令        │任务派发         ▲OTLP        ▲模型调用       │
            └────┼───────────────┼─────────────────┼────────────┼──────────────┘
                 ▼ gRPC          ▼ HTTP+路由头     │            │ 唯一允许的出站
            ┌───────────── Agent Substrate（运行时） ─────────────────────────┐
            │ ate-api-server ─ atenet-router ─ atelet ─ atenet-egress（出站）   │
            │ Actor（gVisor / microVM 沙箱，内存与文件状态可挂起和恢复）          │
            └───────────────────────────────────────────────────────────────┘
```

| 能力 | 负责方 | 说明 |
|---|---|---|
| Agent 定义、版本、目录 | EAP | 以 EAP 的正式 Agent 为主实体 |
| 沙箱执行、隔离、资源限制 | Substrate | ActorTemplate 的 `resources` 与 `sandboxConfig` |
| 挂起与恢复 | Substrate 执行，EAP 下令 | 已验证：恢复约 0.3–1 秒，内存和文件状态保留 |
| 空闲自动挂起策略 | EAP | Substrate 没有此功能 [代码确认] |
| 多智能体编排 | EAP | 复用 Team/Plan/Step 与工作流 |
| 模型调用治理、Token、费用 | EAP 网关 | 配合 Substrate 出站策略强制所有调用经过网关 |
| 出站网络管控 | Substrate | EgressPolicy，默认拒绝 [已验证] |
| Trace 与指标 | 两者 | Substrate 的 OTel 数据汇入 EAP 的 OTLP 接收端 |
| 审计 | EAP | Substrate 暂无审计日志，已列入其路线图 |

## 3. 概念映射

| EAP | Substrate | 映射规则 |
|---|---|---|
| Agent（`agentId`） | ActorTemplate | 一个 Agent 版本对应一个模板，模板名 `eap-agent-<agentId>-v<version>`。模板不可修改 [代码确认]，发布新版本即新建一个模板 |
| 租户或组织 | Atespace | 每个组织一个 atespace，例如 `eap-<orgKey>` |
| AgentRuntimeInstance | Actor | `instanceKey` 保存 `<atespace>/<actor>`，`protocol` 新增取值 `SUBSTRATE` |
| Runtime 状态 | Actor 状态 | RUNNING→ONLINE；SUSPENDED 或 PAUSED→新增 HIBERNATED（休眠，可随时唤醒）；CRASHED→FAILED |
| 运行容量 | WorkerPool | 按沙箱类型和资源规格划分 worker pool |
| Team 成员、Plan 步骤 | 每个成员一个 Actor | 各成员之间状态隔离 |

## 4. 四条集成接口

### 4.1 控制：新增 `SubstrateControlAdapter`

现状 [代码确认]：
- `AgentControlAdapter.dispatch(command, runtime)` 目前只有 `HttpAgentControlAdapter` 一个实现。
- 该实现要求 `protocol=HTTP`，通过 HTTP 把命令发给 agent 自己处理。
- 允许的命令：PAUSE、RESUME、RELOAD_POLICY、UPDATE_CONFIG、RUN_HEALTH_CHECK、SET_RATE_LIMIT。

设计：
- 新增实现，处理 `protocol=SUBSTRATE` 的实例，调用 ate-api-server 的 gRPC `ateapi.Control`：

| EAP 命令 | Substrate 调用 | 语义 |
|---|---|---|
| PAUSE | `SuspendActor` | 快照后释放 worker；也可按配置改用 `PauseActor` |
| RESUME | `ResumeActor` | 等所有 `wakeupProbe` 返回 200 后才算成功 [代码确认] |
| RUN_HEALTH_CHECK | `GetActor` | 读取 actor 状态，不打扰 agent |
| RELOAD_POLICY / UPDATE_CONFIG / SET_RATE_LIMIT | 仍用 HTTP，经路由器发给 actor | 这些命令需要 agent 配合处理 |

- 新增命令 `REVERT`（对应 `RevertActor`），把崩溃的 actor 回退到上一个快照 [代码确认]。
- `DispatchResult.status`：gRPC 返回成功即为 ACKED，但 ACKED 表示操作已完成，不只是请求已收到。失败原因保留 gRPC 状态码，例如 `ResourceExhausted` 表示没有空闲 worker。

认证 [代码确认，待验证]：
- ate-api-server 只信任 `authentication.yaml` 中配置的 JWT 提供方。
- 当前配置为 Kubernetes 服务账号令牌，要求 `issuer=https://kubernetes.default.svc.cluster.local`、`audience=api.ate-system.svc`。
- EAP 后端 Pod 使用投影服务账号令牌，audience 设为 `api.ate-system.svc`。
- 服务端证书通过 ClusterTrustBundle `servicedns.podcert.ate.dev/identity` 校验；官方 Locust 清单就是这样配置的。
- 服务账号需要被授予哪些操作权限，要在 PoC 中确认。

实现方式：Java 侧从 `pkg/proto/ateapipb/ateapi.proto` 生成 gRPC 客户端，只使用第 4 节列出的 RPC。所有调用都封装在 `SubstrateClient` 接口之后，隔离上游 API 的变化。

### 4.2 任务派发：`EAP_HTTP_TASK_V1` 通过路由器

现状 [代码确认]：
- 工作流 worker 直接 POST 到 Runtime 的 `endpoint`。
- 请求头带 `Authorization`、`Idempotency-Key`。
- 同步等待任务终态，默认最长 900 秒。
- 有主机白名单、私网开关、禁止重定向等出站保护。

设计：
- 实例 `protocol=SUBSTRATE` 时：
  - endpoint 固定为路由器地址 `http://atenet-router.ate-system.svc/<任务路径>`。
  - 额外添加请求头 `ate-target-actor: <atespace>/<actor>`。
  - actor 处于挂起状态时，路由器会自动唤醒它 [已验证]。
- EAP 出站白名单只需加入路由器主机名。私网开关改为按“路由器服务名”精确放行，不整体放开私网。
- 派发前先调用 `ResumeActor`，这样唤醒失败可以明确记为 FAILED，不会落入 `EXECUTION_UNKNOWN`。

超时兼容（2026-09-27 实测）：
- 路由器的请求排队预算默认 5 秒；超出预算、没有空闲 worker 时返回 503 [已验证]。
- 路由器默认 `--route-timeout=5m`：直接请求 30 秒、120 秒成功，300 秒与 960 秒都在 300 秒整返回 504 `upstream request timeout` [已验证]。
  - 源码注释说明它同时覆盖 Envoy 的流空闲超时，默认值针对“模型生成期间一直占着请求”的 agent 场景。
- 处理：
  - atenet-router 加参数 `--route-timeout=20m`；
  - EAP 设置 `EAP_WORKFLOW_EXECUTION_READ_TIMEOUT_MS=900000`，原默认 10 秒不足以等待模型回答。
  - 调整后，EAP 工作流派发一个 600 秒任务，经路由器同步等待，10 分 1 秒后 SUCCEEDED [已验证]。
- 注意：路由器滚动升级或重启时，只有约 10 秒排空时间，正在执行的长任务会被中断，EAP 记为 `EXECUTION_UNKNOWN`（源码注释：长请求不随升级保留）。
  - 当前同步协议可以支持 15 分钟以内的任务，暂不需要 V2 异步协议。
  - 若任务更长，或者需要跨过路由器升级，再改为 `EAP_HTTP_TASK_V2`：提交后返回 202，再轮询状态。

### 4.3 观测：OTLP 汇入 EAP，并带上身份

- Substrate 各组件将 OTel 数据发往集群内的 collector [已验证]。给 collector 增加一个 exporter，发往 EAP 的 OTLP gRPC `4317` 或 HTTP `/api/observability/otlp/v1/traces`，附带 `X-EAP-Observability-Token`（或当前使用的机器凭证）。
- agent 自己上报的 OTLP 直接发给 EAP。
- 身份来源：ActorTemplate 挂载 `systemInfo.actorMetadata` 卷，actor 名称、atespace 等身份信息以只读文件形式出现在沙箱内 [代码确认]。恢复后文件仍保持正确，而环境变量可能与快照不一致。
  - agent SDK 读取这些文件，写入 Span 属性 `eap.agent.id`、`eap.runtime.instance`。
  - 这可以缓解 EAP 已知风险中“缺少稳定 Agent 身份，只能启发式关联”的问题。

### 4.4 模型调用：出站策略强制经过 EAP 网关

现状 [代码确认]：
- EAP 网关接口为 `/api/gateway/v1/chat/completions`（兼容 OpenAI）。
- Agent 身份来自调用方自己填写的请求头 `X-EAP-Agent-Id`、`X-EAP-Endpoint-Key` 等。

设计：
1. **网络强制。** 每个 actor 的 EgressPolicy 只放行 EAP 网关的主机名，外加少量明确需要的工具地址。
   - 没有策略的 actor 连不出去 [已验证]。
   - UDP、非 HTTP 的 TCP、WebSocket 出站、CONNECT 代理一律拦截 [代码确认]。
   - 因此 agent 无法绕开网关直连模型供应商。
2. **密钥集中。** 上游模型 API key 只配置在 EAP 网关（`EAP_GATEWAY_UPSTREAM_API_KEY`），ActorTemplate 中不出现任何密钥。这也避开了 Substrate 环境变量只能写明文、不支持引用 Secret 的限制。
3. **身份可信。** 身份不能由 agent 自报，否则 agent 可以把自己冒充成别人。按可靠性从高到低有三个方案：
   - **方案 A（推荐，待验证）：** Substrate 出站网关在 MITM 模式下，可以按策略规则向请求注入静态请求头（`EgressRuleEffects.inject_static_headers`，属实验功能）[代码确认]。
     - 为每个 Agent 的出站策略配置注入 `X-EAP-Agent-Id` 和一个仅网关知道的 `X-EAP-Gateway-Token`，由网关覆盖 agent 自己填写的值。
     - 身份由 Substrate 基于 actor 证书认证后产生，agent 无法伪造。
     - 当前集群已开启 MITM 模式 [已验证]。
   - **方案 B：** 用 `MintActorJWT` 为 actor 签发身份令牌 [代码确认]，EAP 网关用 Substrate 的 JWKS 校验。需要 agent 代码配合，EAP 网关也要新增验签逻辑。
   - **方案 C（仅 PoC 过渡）：** 沿用自报身份，并在审计中标记为“未认证身份”。
4. **治理结果回传。** 网关的 `ALLOW / ASK / DENY` 决策照常生效。DENY 与 fail-closed 的规则不变。

## 5. 生命周期与空闲回收（EAP 新增功能）

- **创建：**
  1. EAP 发布 Agent 版本时，生成 ActorTemplate，调用 `CreateActorTemplate`。
  2. 等待 golden 快照（模板的初始快照）生成，才标记为“可运行”。
  3. golden 快照生成失败时，可能因 actor 卡住而锁住模板。此时需要先用 `DeleteActor --any-state` 删掉卡住的 golden actor，再删除模板 [已验证]。
- **会话实例：** 每个会话或任务调用一次 `CreateActor`；新建的 actor 处于挂起状态，第一次请求时唤醒。
- **空闲挂起：**
  - EAP 按“最近一次派发或回调的时间 + 心跳”判断 actor 是否空闲。
  - 超过阈值（例如 60 秒，可按 Agent 配置）后调用 `SuspendActor`。
  - 这是释放 worker、提高复用率的前提：测试中如果不主动挂起，worker 会一直被占用，新请求在等待 5 秒后得到 503 [已验证]。
- **崩溃：** actor 状态变为 CRASHED 时，生成 EAP 事项。可执行 `REVERT`，或新建 actor。
- **删除：** 会话结束，或 Agent 下线时调用 `DeleteActor`。模板在对应版本不再使用后删除。

## 6. 在前端部署智能体

目标：用户在 EAP 控制台完成从定义到运行的全部步骤，不需要使用 `kubectl` 或 `kubectl ate`。部署入口放在现有“智能体中心”：`AgentsView` 已有创建正式 Agent 的对话框，`AgentDetailView` 是 Agent 详情工作台。

### 6.1 三种部署来源

| 方式 | 用户提供 | 平台操作 | 适用对象 |
|---|---|---|---|
| **A. 配置型 Agent（零代码）** | 名称、提示词、模型（从 EAP 网关已接入的模型中选）、MCP 工具、知识库、资源规格 | 使用平台维护的通用 Agent Runner 镜像；把配置写入模板，agent 启动时读取 | 业务人员、快速试用 |
| **B. 已有镜像** | 镜像地址、端口、启动命令、就绪检查路径、环境变量（不含密钥） | 将镜像复制到集群本地仓库，并换成 digest 引用；生成模板 | 已有容器化 agent 的团队 |
| **C. 源码构建** | Git 仓库地址、分支、Dockerfile 路径（可选，平台提供常见语言的构建模板） | 集群内 kaniko 构建任务 → 推送本地仓库 → 以 digest 引用；构建日志在前端实时显示 | 研发团队、持续发布 |

实现顺序：先做 B，这条路径最短，第 9 节 P1 即可用它部署测试 agent；再做 A，这是面向企业用户的主要形态；最后做 C。在集群内用 kaniko 构建镜像已在本集群验证过：Locust 镜像就是这样构建的 [已验证]。

### 6.2 部署向导（前端）

在 Agent 详情页新增“部署”Tab，也可以从创建 Agent 的对话框直接进入。向导分五步：

1. **来源：** 选择 A、B 或 C，按选择显示对应表单。
2. **运行配置：**
   - 沙箱类型：gVisor（默认）或 microVM（隔离更强）。
   - 资源规格：小、中、大，对应 CPU 和内存上限。
   - Worker 池：只列出与所选沙箱类型匹配的池。
   - 就绪检查：路径与超时。
   - 空闲挂起阈值。
3. **网络与工具：**
   - 出站默认只允许访问 EAP 网关。
   - 如需访问其他地址，逐条添加主机名规则，并填写理由；这类规则进入治理审批（ASK）。
   - 可勾选已在 EAP 登记的 MCP 工具，系统自动加入对应的出站规则。
4. **治理检查（预检）：**
   - 复用 `ALLOW / ASK / DENY` 链路，检查镜像来源、出站规则、资源规格和数据类别。
   - 结果为 ASK 时，进入审批队列，部署暂停。
   - 结果为 DENY 时，不能部署。
5. **确认与发布：**
   - 展示将要生成的模板摘要：镜像 digest、资源、沙箱、出站规则。
   - 提交后显示进度：`镜像准备 → 创建模板 → 生成 golden 快照 → 可运行`。每一步有状态和日志，失败时可重试或回滚。

发布后的实例操作：在详情页“运行实例”Tab 查看 actor 列表，显示状态（运行 / 休眠 / 崩溃）、所在 worker 和最近活动时间。支持以下操作，全部写入审计：

- 新建会话实例；
- 挂起、恢复、回退（REVERT）、删除；
- “试运行”：发送一条测试请求，查看返回结果和对应的 Trace。

### 6.3 版本与发布

- 模板不可修改，因此“修改配置”即“发布新版本”，生成新的模板 `eap-agent-<id>-v<n+1>`。
- 旧版本的 actor 继续运行，直到被删除。新会话默认使用新版本。
- 前端版本列表显示每个版本的模板、镜像 digest、发布人、发布时间和实例数。
- 回滚 = 把默认版本切回旧模板。
- 灰度发布（按比例把新会话分配给新版本）放到 P3 之后。Substrate 路线图中有类似能力，届时评估是否复用。

### 6.4 后端新增

- **表：**
  - `agent_deployment`：部署配置快照与状态。
  - `agent_build`：源码构建任务与日志位置。
  - `agent_release`：版本与模板的对应关系。
  - 现有 `AgentRuntimeInstance` 增加 `protocol=SUBSTRATE` 所需字段：atespace、actor、template。
- **接口（均需 OPERATOR 或 PLATFORM_ADMIN，JWT 模式下校验角色；与现有工作流执行一样，使用 requestKey 防止重复提交）：**
  - `POST /api/agents/{id}/deployments`：提交向导配置，返回 deploymentKey。
  - `GET /api/agents/{id}/deployments/{key}`：查询进度与各步日志。
  - `GET /api/agents/{id}/releases`：版本列表；`POST .../releases/{v}/default`：切换默认版本。
  - `POST /api/agents/{id}/instances`：新建会话实例；实例的挂起、恢复等操作复用 `/api/control/commands`。
  - `GET /api/runtime/worker-pools`：列出可选的 worker 池与剩余容量，供向导使用。
- **执行：** 部署是长流程，采用与工作流执行相同的方式：outbox 加 worker 分步推进，每一步落库后再调用外部系统，重启后可恢复，并允许重试。
- **镜像：** 统一复制到集群本地仓库，并换成 digest 引用。原因是 Substrate 要求镜像以 digest 引用，且 atelet 不走镜像加速 [已验证]。复制过程中记录来源地址与 digest，作为供应链审计证据。

### 6.5 通用 Agent Runner（方式 A 的基础）

- 平台维护一个 Runner 镜像，由 EAP 提供。职责：
  - 读取模板中的 Agent 配置：提示词、模型名、工具列表。
  - 通过 EAP 网关调用模型，通过 MCP 调用工具。
  - 实现 `EAP_HTTP_TASK_V1` 的任务接收接口与就绪检查。
  - 读取 `actorMetadata` 身份文件，写入 OTLP。
  - 会话状态保存在内存或持久目录中，挂起再恢复后仍然可用。
- 语言和框架待定：可基于 ADK 或 LangGraph。这决定了方式 A 能提供哪些功能，需要单独做选型。

## 7. 安全边界

| 风险 | 控制 |
|---|---|
| agent 绕过治理直连模型 | EgressPolicy 只放行 EAP 网关（4.4） |
| agent 冒充其他身份 | 身份由出站网关注入，或用 Substrate 签发的 JWT（4.4-3） |
| agent 读取宿主机或其他 actor | gVisor 或 microVM 沙箱 [已验证宿主机文件不可见] |
| EAP 对 Substrate 的控制权限过大 | 服务账号最小授权；控制命令继续走 EAP 审计与 outbox |
| 路由器被任意调用 | 路由器只在集群内暴露；外部入口统一经 EAP 或受控反向代理，由代理覆盖路由头（Substrate 文档要求） |
| MITM 查看 HTTPS 明文 | 只在出站网关内终止 TLS；actor 需要信任网关 CA，按 `egress-mitm.ate.dev` 挂载信任包 |

## 8. 集成限制

- 不支持 GPU；不支持 UDP、WebSocket 出站；不支持 Kubernetes Secret 引用 [代码确认]。
- 挂起再恢复后，agent 看到的时钟会跳变，已有的长连接会断开，agent 需要能自动重连。
- Substrate 仍处于早期，API 可能不兼容地变化。EAP 只通过 `SubstrateClient` 这一层访问，并固定 Substrate 版本。
- 集群网络受限：atelet 直接拉取 actor 镜像，不经过 containerd 镜像加速。agent 镜像必须先推到集群本地仓库 `localhost:5001`，并以 digest 引用 [已验证]。

## 9. PoC 计划与验收

### 进展记录（2026-09-27）

- **P0 完成。** EAP（MySQL、后端、前端）已部署在 Substrate 集群的 `eap` 命名空间。
  - 镜像在集群内由 kaniko 构建，Maven 与 npm 依赖走国内镜像源。
  - MySQL 密码存放在 Secret 中，为随机值。
  - 部署清单：`K8Substrate/tools/eap-poc.yaml`；本机访问 `K8Substrate/tools/ate-ui-tunnel.sh` → http://localhost:5173 。
- **P1 核心链路已实现并实测通过。** 代码：`substrate/` 包、`SubstrateControlAdapter`、`WorkflowTaskClient` 的 Substrate 分支、租约豁免。
  - 控制：通过 EAP 控制命令 API 对真实 actor 执行健康检查、恢复、挂起，状态均为 ACKED，Substrate 侧状态同步变化 [已验证]。
  - 派发：工作流经路由器把任务派发到挂起中的 actor，actor 自动唤醒并返回 SUCCEEDED [已验证]。
  - 状态保留：EAP 挂起 actor 后再派发第二个任务，agent 内存中的计数为 2，最近目标为“第二次任务” [已验证]。
- **实现中确认的事实：**
  - ate-api-server 的服务证书为 **Ed25519**。grpc-java 默认的 BoringSSL 引擎无法校验此类证书，报错 `Algorithm constraints check failed on signature algorithm: Ed25519`；改用 JDK TLS 实现后通过 [已验证]。
  - `api.ate-system.svc` 是 headless 服务，gRPC 直连 Pod IP，证书 SAN 为 `api.ate-system.svc` [已验证]。
  - 挂起的 actor 没有心跳。原租约逻辑会在 180 秒后把它标为 OFFLINE，因此 SUBSTRATE 协议的实例不参与心跳过期，状态以 Substrate 为准。
  - Runtime 注册格式：`protocol=SUBSTRATE`，`endpoint=substrate://<atespace>/<actor>`。该地址只用于标识 actor，不会被当作 URL 访问；请求一律发往运维配置的路由器地址。
- **测试（在集群内编译运行）：** 228 个测试中 227 个通过。唯一失败的 `AgentOpsIntegrationTests.excludesDevelopmentSeedAgentsFromJwtProductionViews`，在未修改的 HEAD 上同样失败（216 个中 1 个失败），与本次改动无关。新增 5 个测试类共 17 个用例，全部通过。
- **前端部署与真实 LLM agent（同日完成并实测）：**
  - DeepSeek 已接入 EAP 治理网关。模型密钥只保存在 Secret `eap-gateway` 中；agent 只持有网关令牌。
  - 平台通用 Agent Runner（`eap-agent-runner`）：按配置的系统提示词和模型，经网关调用模型；会话记忆保存在进程内存中。
  - Agent 详情页新增“部署与运行”Tab，支持零代码配置和已有镜像两种来源、治理预检、分步进度、失败重试、实例新建/唤醒/休眠/回退/删除，以及试运行对话。
  - 后端：`deployment/` 包、V48 迁移（`substrate_agent_deployments`）。控制命令新增可选字段 `runtimeInstanceKey`，用于指定实例。
  - 实测：
    - 在控制台零代码部署“财务报销助手”，版本状态依次推进到“可运行”；
    - EgressPolicy 自动设置为只允许访问网关；另一个 actor 试图直连 DeepSeek，被拦截 [已验证]；
    - 试运行得到回答；休眠后再次提问，agent 能复述上一个问题 [已验证]；
    - 模型调用记入该 Agent 名下，包括 Token 与费用归属 [已验证]；
    - 浏览器中页面与对话抽屉正常，控制台无报错 [已验证]。
  - 带密钥特征的环境变量名（KEY、TOKEN、SECRET 等）与平台保留前缀会被拒绝。
  - 首次部署暴露 EgressPolicy 必须填写 `metadata`（kubectl-ate 在客户端补为 `default`）；已修复，并据此加入“从失败步骤重试”。
  - 测试：后端 234 项中 233 项通过（唯一失败为原有问题）；前端 66 项测试全部通过，构建成功。
- **后续完善（同日）：**
  - 删除实例时同时删除运行实例登记，不再产生“运行实例离线”事项、也不计入实例数；遗留的离线事项已自动变为 RESOLVED [已验证]。
  - Agent Runner 每次任务或对话都以 OTLP/HTTP 上报一个 AGENT Span（`agent.task` / `agent.turn`），并把同一 trace ID 通过 `X-EAP-Trace-Id` 传给网关，Trace 与模型调用记录可以互相关联 [已验证]。
    - Span 名称和属性刻意避开 `gen_ai.*` 模型字段以及含 “chat” 的名称，否则 EAP 会把它当作模型调用再记一次。
  - 以控制台发布 v2 验证版本管理：v1、v2 并存，各自的实例使用各自固定的镜像 digest [已验证]。
- **第三批（同日）：长任务、权限、源码构建：**
  - 长任务：见第 4.2 节。路由器超时调至 20 分钟后，600 秒的工作流任务成功；直接经路由器 1000 秒的请求也成功 [已验证]。
  - JWT 权限：新增 `SubstrateDeploymentSecurityTests`，覆盖 4 组情况 [已验证]：
    - 未登录返回 401；
    - VIEWER 只能读，部署、实例操作、试运行、重试都返回 403；
    - GOVERNANCE_ADMIN 不能部署；
    - OPERATOR 可以部署，并以令牌中的 subject 记为申请人。
  - 源码构建（方式 C）：
    - 流程：EAP 后端用自己的服务账号，在独立命名空间 `eap-builds` 创建 kaniko Job。该服务账号只有这个命名空间内 jobs、pods、pods/log 的权限，`kubectl auth can-i` 核对通过。
    - 镜像固定：kaniko 把推送后的 digest 写入终止信息，EAP 按 digest 固定镜像，不信任 tag。
    - 构建 Pod 不挂载服务账号令牌，设有资源上限与超时。
    - 前端：可查看构建日志，失败时可从构建步骤重试。
  - 实测：示例仓库 docker/welcome-to-docker（固定到某个提交）→ kaniko 构建 → 按 digest 固定 → golden 快照 → 实例可运行；经 CONNECT 隧道访问 3000 端口，返回页面 “Welcome to Docker” [已验证]。
  - 实测中发现的限制：
    - 本机房访问 GitHub 很不稳定（TLS 中断），克隆常常失败。PoC 在集群内搭了只读 Git 镜像（命名空间 `eap-git`，git http-backend）。http 仓库地址只允许 `EAP_SUBSTRATE_BUILD_HTTP_GIT_HOSTS` 中登记的内部主机，其余一律要求 https。
    - kaniko 按提交号拉取时，Git 服务端要允许按 SHA 获取（`uploadpack.allowReachableSHA1InWant`）；GitHub、Gitee 默认允许。
    - Substrate 总是以 `/` 为工作目录启动进程，不使用镜像的 WORKDIR（`internal/ocispec` 写死 `Cwd: "/"`）。依赖相对路径的程序需要在启动命令里写绝对路径，前端已加提示。
    - 路由器的普通 HTTP 请求只转发到 actor 的 80 端口，其他端口只能经 CONNECT 隧道访问。任务派发和试运行都走普通 HTTP，所以部署的 agent 应监听 80，前端已加提示。
- **未完成：**
  - 长任务超时实测；
  - agent 的任务令牌目前以明文写在 ActorTemplate 中（Substrate 限制，见第 8 节），PoC 可接受，生产环境需改用第 4.4 节的注入方案。

环境：上述两节点集群。EAP 部署在同一集群，以避免跨网络问题；部署前先清理不再使用的 demo 和压测组件，腾出资源。

| 阶段 | 内容 | 验收标准 |
|---|---|---|
| P0 部署 | EAP（MySQL、后端、前端）部署到集群；一个真实 LLM agent 镜像，经 EAP 网关调用模型 | EAP 健康检查通过；agent 的 golden 快照生成成功 |
| P1 最小闭环 | `SubstrateControlAdapter`、路由器派发、EgressPolicy 只放行网关、OTLP 汇入；前端部署向导（方式 B：已有镜像）与实例管理 | ⓪ 在控制台用向导部署测试 agent，进度显示到“可运行”，全程不使用命令行；① 工作流派发任务到 actor 并得到 SUCCEEDED；② EAP 中出现该任务的模型调用、Token、Trace 和审计记录；③ actor 直连模型供应商被拒绝；④ 从 EAP 控制台挂起、恢复后，agent 会话状态保留 |
| P1 专项 | 长任务超时 | 对 30 秒、5 分钟、15 分钟的同步任务分别记录结果，决定是否需要 V2 异步协议 |
| P2 多智能体与零代码 | Team/Plan 编排 2–3 个 agent 协作；通用 Agent Runner 与前端方式 A（零代码配置） | 每个成员在独立 actor 中运行；步骤结果与证据可追溯；业务人员只填写提示词、模型和工具即可部署可用的 agent |
| P3 运营 | 空闲自动挂起、容量视图、身份注入（方案 A）；前端方式 C（源码构建）与版本回滚 | N 个 actor 共用 M 个 worker（N>M）时无状态丢失；网关记录的 Agent 身份不能被 agent 伪造 |

需要用户提供或决定的事项：

- 模型供应商与 API key，或使用私有化模型的地址；
- 路由器超时实测后，是否接受 V2 异步协议的开发量；
- 身份方案是否选 A（依赖 Substrate 的实验功能）；
- 通用 Agent Runner 采用哪种框架（ADK、LangGraph 或其他），这决定零代码 agent 能提供哪些功能；
- 前端部署的权限划分：业务人员能否自行发布，还是必须经审批后上线。

## 10. 参考

- Substrate 源码：`pkg/proto/ateapipb/ateapi.proto`（Control API）、`docs/api-guide.md`、`docs/network-egress.md`、`docs/egress-traffic.md`、`docs/egress-trust-bundle.md`、`demos/egress/README.md`。
- EAP 源码：`control/AgentControlAdapter.java`、`control/HttpAgentControlAdapter.java`、`workflow/WorkflowExecutionService.java`、`gateway/OpenAiCompatibleGatewayController.java`、`observability/OtlpTraceIngestionController.java`；文档 [`WORKFLOW_EXECUTION.md`](WORKFLOW_EXECUTION.md)、[`PROJECT_MEMORY.md`](PROJECT_MEMORY.md)。
- 同类开源方案：kagent（CNCF 沙箱项目，其官方博客称运行时将全面迁往 Substrate）；Google AX / Agent Executor（基于 Substrate 的任务型编排器，README 声明正式版前还会有重大变更）。
