# OBI 对 EAP 的适用性：同节点实验已完成

日期：2026-10-21。最新独立批次 `obi-node-20261021` 已补齐同节点与前后阳性对照：固定 OBI v0.15.2 在 `ate-node2` 的普通容器对照前后各采集80/80个SERVER Span，中间gVisor新任务8次HTTP请求采集0条，判定 `VISIBILITY_GAP`。**当前固定版本和进程/端口发现配置存在覆盖缺口，不能替代 EAP 已有协议观察器。** 不外推所有OBI配置/eBPF方案，也未单独确定缺口来自进程发现还是沙箱内协议可见性。

EAP创建Agent85 / Workflow39，仅一次无模型任务，6 MCP Span/工具记录+1 Agent父Span，四角色一致，最终显式SUSPENDED/REVOKED。EAP位置接口在任务前后返回同一Actor/Worker/Pod与ate-node2，独立Kubernetes核对一致；观察器与普通容器固定在该节点，观察器未中断/重启。计数按Span时间落入三个不重叠窗口，窗口外0；两组普通容器仍只识别initialize共20次，tools/call共0/120、无工具名。详见[新批次完整证据](evidence/obi-node-20261021/README.md)、[统计裁决](evidence/obi-node-20261021/remote/release/analysis-final.json)。

新增3份实验源码，远程22项统计测试通过，911份输入与117份证据核验。观察器退出0、新增BPF程序剩余0，专用Map835与3临时Pod已清理；前后端/默认Runner不变，UP/Schema87。没有生产OBI导入、自动任务身份关联、性能结论或连续位置证明；快照尚未自动写入工作流。下一步应将位置留存、观测来源和覆盖边界接入EAP任务证据与界面；更广发现规则或协议变量另行有界验证。

## 首次实验：历史结果保持

首批 `obi-20261020` 固定同一OBI版本，普通容器采集160/160个服务端请求Span；120次工具调用未被识别为MCP工具语义。旧Agent83的节点位置未在暂停前留证，其零采集仍为 `INCONCLUSIVE_PLACEMENT`，不改写为本次新样例的结论。以下首次实验事实、环境与开销记录继续保留。

本地实现为 [runtime/obi-lab](../runtime/obi-lab/README.md)，原始记录与收尾见[本轮证据](evidence/obi-20261020/README.md)。初始 `comparison.json` 是描述性计数；裁决以 `analysis-final.json` 和最终回执为准。

两个实验之间，[EAP 当前运行位置接口](RUNTIME_PLACEMENT.md)已部署，独立Agent84 / Workflow38验证任务前后位置、暂停查询不唤醒；后端完整1297项测试通过。[位置验收](evidence/runtime-placement-20261021/README.md)本身未运行OBI，随后才进行了上面的新同节点批次。

## 实测事实与证据强度

| 项目 | 结果 | 能支持的结论 |
| --- | --- | --- |
| 普通容器，开启 OBI | 160 请求，160 SERVER + 320 INTERNAL Span，唯一 Span 共 480 | 该合成负载的请求数匹配；内部子 Span 不能算作额外请求 |
| 普通容器 MCP 语义 | `initialize` 20；`tools/call` 0/120，无工具名称 | 本轮无 `Mcp-Session-Id` 的固定样例未识别工具语义；不外推所有 MCP 服务 |
| 工具真实协议分类 | 每阶段 SUCCEEDED / FAILED / UNKNOWN 各 40 | 来自合成客户端协议观察器；OBI 的 UNSET 不能代替这些结果 |
| EAP gVisor 样例 | Agent83 / Workflow37，1 次任务，8 POST，6 MCP + 1 Agent Span，6 工具记录，模型 0 | EAP 已有协议观察器链路通过；这些 Span 不是 OBI 导入 |
| gVisor 的 OBI 结果 | 该采集阶段 0 Span；Actor 运行节点未留证 | 位置证据不足；不能判断是否因 gVisor 隔离而不可见 |
| 关联与效果 | 未验证 OBI → EAP 原任务关联；工具效果 UNVERIFIED | 不将网络流量推断为可信任务、预算或外部事务成功 |
| 分析测试 | 远程最终 10 项通过 | 覆盖去重、类型计数、位置缺失、无对照、分母、延迟及字段裁剪 |

计数由固定请求数、客户端输出、OBI 原始 OTLP 和 EAP 原任务/四角色查询交叉核对，属于本环境实测强证据。它不是生产流量测试、完整协议符合性认证或长稳压力测试。

## 环境与版本

OBI 固定到 [v0.15.2 官方发布](https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation/releases/tag/v0.15.2)，源码 commit `b28e4e7fe0a01f1961b7c4747a13b8ff486d6fec`。Linux amd64 发布包 SHA256 为 `739641fef1a14c20c50e71b2e0cef99c922ee4ccc8e62606531f81f9bbaae9cc`，下载后核对。

宿主 ate-node1，Linux `6.8.0-138-generic`、BTF 可用、containerd `2.3.5`。专用镜像在上一轮 HTTP 合成镜像上，只增加独立 Python 可执行名、固定回环端口及 3 秒发现等待；父镜像层和载荷摘要核对。默认原生/Kagent Runner、前后端镜像不变。

OBI 选择同一条规则中的可执行名 `*/eap-obi-lab-python` 与端口 `28761`，MCP 解析显式开启，协议调试/上下文注入关闭。相同条目的条件为 AND；端口条件用于发现进程，不能当作请求级端口隔离。此行为有[官方服务发现文档](https://opentelemetry.io/docs/zero-code/obi/configure/service-discovery/)支持。进程只承担本次合成流量，OTLP 导出至宿主回环接收器，没有发送到外部服务或生产 EAP 导入入口。

## 为什么没有识别 tools/call

固定版本的 [`MCPSpanFromParsed`](https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation/blob/b28e4e7fe0a01f1961b7c4747a13b8ff486d6fec/pkg/ebpf/common/http/mcp.go) 先检查请求或响应中的 `Mcp-Session-Id`；没有该头时，只有具有协议版本字段的 `initialize` 走特殊识别路径。当前合成服务没有会话头，因此初始化可识别、工具调用未被标记为 MCP，与实际结果一致。**这是源码支持的解释；本轮没有再做“只增加会话头”的独立对照，不能写成该变量已单独实验证实。**

该版本源码的工具结果结构没有 `isError` 字段；[官方集成测试](https://github.com/open-telemetry/opentelemetry-ebpf-instrumentation/blob/b28e4e7fe0a01f1961b7c4747a13b8ff486d6fec/internal/test/integration/red_test_python_mcp.go) 也说明未断言 SSE 工具错误。这些限制要求 EAP 保留协议结果合同。不能把 HTTP 200 或 OTLP UNSET 自动投影为 MCP 成功；也不能通过伪造会话头掩盖实际协议差异。

以上一手来源访问于 2026-10-20 至 2026-10-21。文档/源码表明版本行为，不能替代在目标环境中的效果验证。

## 为什么首次 gVisor 结论未成立

集群有 ate-node1、ate-node2 两个 Substrate Worker，首次OBI只在ate-node1。当时EAP尚无位置查询接口；首次任务后立即暂停，`ActorStatus.worker_assignment`按协议在暂停后清空。两Worker的生命周期日志没有提供旧Actor UID的可靠位置记录。新接口和新批次不能补写旧样例的位置。

因此，“采集器普通容器对照有效”和“Actor 任务成功”仍不足以证明观察器与 Actor 同节点。现已在统计代码中加入强制位置门槛，`same_node` 缺失或不是 `True` 时不输出可见率。该限制是本轮发现的验收缺陷，不能用架构猜测弥补。

[gVisor 架构文档](https://gvisor.dev/docs/architecture_guide/intro/)说明其用户态内核边界，因此宿主探针可能只能看到部分外层活动；这是后续实验的假设，不是本轮已证实的原因。

## 延迟与资源：仅保留观测值

| 阶段 | 样本数 | 中位数 | P95 |
| --- | ---: | ---: | ---: |
| 未开启 OBI | 160 | 1.060 ms | 1.604 ms |
| 开启 OBI | 160 | 1.247 ms | 2.205 ms |

OBI 进程采样峰值 RSS 约 129.77 MiB，采样期间累计 CPU 约 11.48 秒，含启动。只有一次顺序运行，共享集群存在调度、热身等影响，未做随机顺序、多轮重复或同负载资源基线；**不据此给出生产开销百分比、零开销或性能退化结论。**

## 可借鉴的能力与下一步

1. **EAP 原生协议观测继续负责工具结果与原任务关联。** 当前 stdio/HTTP 适配器已有成功/失败/UNKNOWN 合同，不能被通用网络 Span 替换。
2. **OBI 作为进程/网络补充层候选。** 暂不导入生产 EAP；未来必须区分网络、协议、任务、效果证据，并防止重复计数、敏感属性泄漏和不可信 Trace 归属。
3. **权威位置读取已完成，下一步是产品内留存。** 当前只读接口可返回经过核对的Actor/Worker/Pod UID、节点和查询时间；已确认暂停返回UNASSIGNED，证据不足返回UNKNOWN。尚未自动关联到原任务持久证据或显示在界面；后续必须保留观测来源与缺口，不把旧IP或边界快照当作连续位置。
4. **同节点 PoC 已完成，保留限定结论。** Agent85新任务与前后阳性对照证明当前固定配置的覆盖缺口；不改变Agent83原位置未知结论。所有批次已关闭，禁止唤醒或重派旧样例补证。不能据此直接扩大生产探针的权限和发现范围。
5. **协议与容量需要独立验证。** 有/无会话头、JSON/SSE、明确工具错误、TLS、连接复用分别形成受控用例；重复性能对照后才讨论成本。Kagent MCP执行、生产服务和通用配置仍未完成。

本轮没有新增后端/前端/权限/Schema 业务改动，也没有重新运行这些未改部分的完整套件或浏览器。没有收费模型、生产工具调用或 commit/push。
