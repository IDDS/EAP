# MCP HTTP/SSE 请求观测

> 2026-10-21 更新：专用合成夹具已补充[固定阶段与耗时诊断](HTTP_FIXTURE_DIAGNOSTICS.md)。新 Agent88 单任务成功；旧 Agent87 的 UNKNOWN 根因仍未证实，不重放。诊断读取已更新后端，默认 Runner 和前端保持；以下首批验收描述保留其当时范围。

可选的 Python 宿主适配器按一次 HTTP POST 关联 `tools/call` 与 JSON 或 SSE 中的最终响应。MCP 服务无需修改，宿主需要接入适配器或在现有传输层调用观察接口。已在一个 EAP 创建和调度的独立 gVisor 验收 Actor 中接通实例身份、任务 Trace 和观测导出。它不是透明网络抓包、完整 MCP SDK、通用代理，也没有自动注入既有 Actor。

通用库为 `runtime/mcp-observer/http_observer.py` 和 `http_transport.py`；固定验收宿主及服务为 `http_actor_probe.py`、`http_actor_server.py`。库测试见[原始记录](evidence/mcp-http-20261020/README.md)，真实 EAP 链路见[Actor 验收](evidence/mcp-http-actor-20261020/README.md)。专用镜像只用于新验收实例，默认 Runner、前后端和既有实例镜像保持。

## 接口与归属

`ObservedHttpClient` 接收可信配置的固定 endpoint、server、tools、protocol_version 和 emit。endpoint 默认必须为 HTTPS；PoC 内网 HTTP 需显式 `allow_http=True`。拒绝 URL 用户信息、查询参数、片段和控制字符；HTTPS 使用标准证书校验。客户端不读取环境代理、不跟随重定向、不发现凭证、不自动重试或续接 SSE。

每次 `post(body, headers=..., trace_id=..., parent_span_id=...)` 使用独立连接和观察状态，body 为调用者提供的原始 bytes。返回的上下文管理器保留响应 status、headers；`read(size)` 每次最多读取 64 KiB，按原字节返回，不把分片合并为另一种 MCP 协议。调用者必须持续读取或显式关闭上下文。socket timeout 取值 0.1–30 秒，不是 DNS、整个请求或工具执行的绝对截止时间。

Trace 和父 Span 仅来自可信宿主任务绑定。未给定 Trace 时生成独立 Trace，标记 `UNAVAILABLE`；不能从请求参数、HTTP 头或 SSE 数据推定任务归属。并发请求即使使用相同 JSON-RPC ID，也不共享待完成表。工具名仅在配置白名单内导出，其余统一为 `unregistered_tool`；白名单不是执行授权。

宿主通过 headers 在内存传入 **MCP 服务自己的** Authorization、会话及协议元数据；它们仅发送到固定 endpoint，不写进事件。禁止把 `X-EAP-*` 运行身份头传给 MCP 服务。Content-Length、Host、Transfer-Encoding、Content-Type 等由传输层负责，调用者不能覆盖或制造重复头。此接口不验证某个 MCP 凭证的授权范围。

`emit` 可直接使用已有 `RuntimeExporter.submit`。EAP 凭证仍通过独立 `RuntimeLease` 匿名管道交接，固定送往 Runtime 观测入口；没有另设凭证文件、环境变量或查询参数。EAP 最终核实实例和 Trace 归属，宿主给出的标识不自动成为可信身份。Go Runner 已支持固定 HTTP 验收宿主及运行中的租约更新，任意 HTTP 服务的通用启动配置和 UI 尚未实现。

已有 HTTP SDK 若需要保留原传输实现，可显式调用 `HttpExchangeObserver.request → response → feed → finish`；同一个 exchange 内须串行调用。这是传输层接入点，不是自动 instrumentation。

## 协议与结果边界

支持观察固定版本 `2025-11-25`、`2026-07-28` 的共同最终工具结果子集。2026 请求还核对 body 中声明的 protocolVersion；宿主负责该版本的 Mcp-Method、Mcp-Name、参数头、能力声明及协议协商。本适配器不证明完整协议符合性。

| 观察到的情况 | 结果 |
| --- | --- |
| HTTP 200，完整 JSON 或已分派 SSE message，ID 匹配且工具结果格式有效 | SUCCEEDED，效果 UNVERIFIED |
| 匹配的合法 JSON-RPC error 或工具 isError=true | FAILED，效果 UNVERIFIED |
| HTTP 错误/重定向、未知 Content-Type、压缩响应 | UNKNOWN，不按 HTTP 状态推定工具成败 |
| 断连、读超时、提前关闭、没有匹配响应 | UNKNOWN，不重试工具 |
| 异步 task 或 MRTR inputRequests | UNKNOWN，不当成最终工具结果 |
| 非法 UTF-8/JSON、重复字段、错误 ID、超限、未知 SSE 事件类型 | 不确认成功；已有待完成调用最终为 UNKNOWN |
| 通知、心跳、进度、仅收到 HTTP 202 | 不生成虚构的工具成功 |

SSE 支持分片 UTF-8、开头 BOM、LF/CRLF/CR、多行 data、注释、空 data 的初始事件。只有空行结束的 message 才提交 JSON-RPC 关联，EOF 不提交残缺事件。`id` 和 `retry` 不留存、不自动执行；不导出原始请求 ID、会话 ID、游标、URL、HTTP 头、参数、返回正文或错误消息。

观测单帧默认 64 KiB，可设置 1 KiB–1 MiB；JSON、SSE 单行和累计事件分别受界限约束。超限只停止语义关联，HTTP 字节继续返回调用者。事件投递异常由观察器吞并计数，不重新执行工具。网络导出继续使用现有有界队列、一次尝试和未知/丢弃统计；没有持久补投。

同一 POST 仅对应最多一个工具观察结果。2025 版独立 GET 流、跨请求共享会话和 Last-Event-ID 恢复没有实现；旧 2024 HTTP+SSE 双端点也不支持。2026 版 MRTR、订阅流及通用认证由原客户端负责。不能把断连 UNKNOWN 改写成取消完成或无业务效果。

## 验证分层

库层远程测试使用远程测试容器回环地址上的真实合成 HTTP 服务和合成 Runtime 接收端，验证 JSON/SSE、chunked 传输、原字节、并发关联、早关闭、超时、重定向不跟随、不重试及凭证分流。Python 生成的三种 OTLP 结果直接用于隔离后端测试，后端以测试身份验证入库和四角色查询；此层证据与下述真实 Actor 验收分开记录。

真实验收使用 `mcphttpfixture && !mcpfixture && !kagent` 构建。EAP 创建 Agent82 / Workflow36，派发一个固定合成任务；在独立 gVisor Actor 内启动仅监听回环地址的 HTTP 服务，执行 8 次 POST：旧版初始化/通知及两种版本各三次工具调用。JSON 成功、分块 SSE 工具错误、SSE 中断未知各两条，共 6 个 MCP Span、6 条被动工具记录和 1 个 Agent 父 Span；四个真实角色登录后的查询一致，原任务覆盖为 COMPLETE、模型 0/0、Agent 1/1。

工作流 SUCCEEDED 表示固定探针得到预期分类和明确导出回执，六次工具调用中仍有 FAILED、UNKNOWN；效果均为 UNVERIFIED。任务持久保存 UNKNOWN 意图及最终回执，同一已完成或未知任务不重新执行；回执冲突返回 409。取消终止宿主进程组。运行中凭证更新、取消及回执复用由远程隔离集成测试验证；真实 Actor 轮换发生在派发前，不声称实测了任务执行中的轮换或实际崩溃恢复。

合成 HTTP 子进程启动时检查无 `EAP_*` 环境和额外继承的管道 FD，请求也不携带 EAP 身份头；这不构成同 UID/root 恶意进程隔离。服务地址、命令和工具均由可信验收镜像固定，任务不能选择 endpoint。没有生产 MCP 服务、真实 TLS/OAuth、收费模型或生产工具调用。

验收后通过 EAP 显式 PAUSE 并撤销连接，实例 SUSPENDED/REVOKED；未验证自动回收，旧全局 UNKNOWN 保护保持。后端、前端及浏览器套件本轮未重跑，权限规则未改。通用 HTTP 配置、共享连接/会话恢复、跨节点恢复及容量仍待验证。下一步固定 OBI 版本，在独立普通容器与 gVisor 样本中比较可见率和开销；本轮没有 eBPF 证据。

## 规范依据

访问日期：2026-10-20。以下是官方规范事实；本实现覆盖范围以本页和测试证据为准。

- [MCP 2025-11-25 transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)：POST 可返回 JSON/SSE；该版本允许会话、独立 GET 流及以 Last-Event-ID 恢复。连接断开不能直接解释为取消。
- [MCP 2026-07-28 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)：移除协议会话和独立 GET 流，引入请求元数据；SSE 限于对应请求，恢复方式和取消语义与旧版不同。
- [WHATWG SSE parsing](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)：UTF-8、BOM、三种换行、多行 data 及空行分派规则。观察器对非法编码采取保守 UNKNOWN，不声称实现浏览器 EventSource 的全部行为。
