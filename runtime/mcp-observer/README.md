# MCP stdio / HTTP 观测与 Runtime 导出

无需修改 MCP 服务代码，通过显式启动包装器采集 `tools/call` 的有界元数据；仍需修改服务启动配置。EAP 接收端已部署 `MCP_PROTOCOL_V1`。Go Runner 身份回调与包装器现已接通，并在 EAP 创建的独立合成 Actor 中验证实际 Runtime 凭证入库及四角色查询。现有 Actor/Kagent 不会自动获得 MCP 接入，也不执行工具重试。

新增 HTTP/SSE 宿主适配器见下方“HTTP 请求观测”。它与已经验收的 stdio Actor 链路分开：HTTP 目前是可选 Python 接入库，尚未发布为真实 Actor 的 HTTP 执行能力。

仅在远程 PoC 环境使用：

```sh
python3 relay.py --server calculator --tool calculate_sum \
  --events /tmp/mcp-observation-new-session.jsonl \
  -- python3 /path/to/mcp_server.py
```

`--events` 必须是新的文件路径，文件权限 `0600`。`--trace-id`、`--parent-span-id` 和 `--agent-id` 只能由可信启动配置提供；不从协议参数推导归属。包装器保留 stdin/stdout 原字节；不采集 stderr；去掉子进程的 `EAP_*` 环境变量。子进程 stdout 关闭后的退出等待由 `--exit-timeout` 配置，默认 5 秒。

事件文件每行包含 `event` 和 `otlp`。网络导出模式可不写文件；`--events` 与网络导出也可同时启用。**必须先验证接收端支持 `MCP_PROTOCOL_V1`，不能直接向旧 EAP 发送未知结果**：旧版 `UNSET` 会被归为成功。观察到成功响应也不代表业务效果已验证。

## 可信宿主接入

新增 `launch.start_observed_server()`，供已经持有 EAP 专属实例凭证的宿主使用。宿主从原身份配送回调取得租约，通过匿名管道传入包装器，并在续期回调中调用返回的 `CredentialWriter.update()`。凭证不写入参数、环境变量或文件；MCP 子进程关闭额外文件描述符，不继承该管道。

启动函数接收：

- `command`：显式 MCP 服务参数数组，使用 `shell=False`。
- `server`、`tools`：允许导出的配置名称。
- `runtime_url`：可信配置中的 EAP 根地址，固定提交到 `/api/runtime-access/v1/traces`；默认只允许 HTTPS。PoC 内网 HTTP 需显式 `allow_http=True`，不关闭 HTTPS 证书校验。
- `lease`：内存字典，字段为 `instanceKey`、字符串 `agentId`、`actorUid`、`token`、整数 Unix 秒 `expiresAt`、递增整数 `sequence`。这是宿主交接格式，不是新的 EAP 发证接口。
- 可选 `trace_id`、`parent_span_id`：可信宿主给出的原调用标识。每个包装器的关联固定；跨任务复用同一个 MCP 长连接尚不支持。

返回 `(process, credential_writer)`。宿主通过 `process.stdin/stdout` 与原服务交互并读取有限摘要 `stderr`；直到包装器退出才关闭 `credential_writer`。管道 EOF、非法更新、实例/Agent/Actor UID 改变会使当前租约失效；低序号更新不能覆盖新租约。关闭管道不会重新执行或自动终止业务工具。

底层 CLI 对应 `--runtime-url` 和 `--runtime-credentials-fd`；仅接受继承的管道 FD，不支持凭证文件或令牌命令行参数。不要用终端命令或磁盘文件制作租约。自动 Runtime 模式不接受 `--agent-id`，归属由 EAP 验证凭证后赋予。启动配置提供的 Trace 仍由服务端核对和隔离，`CONFIGURED` 不是已认证任务的证明。

Python 宿主 API 的网络验证使用合成接收端。Go Runner 则通过 `mcp_session.go` 实现同一管道合同：`identityInstall` 成功后更新运行中的 MCP 会话；启动前核对投影身份、原 UID、凭证序号及有效期。配置仅来自可信镜像代码，任务或模型文本不能指定可执行文件。最多 16 个会话，单次管道写入有 50ms 截止时间，取消会终止包装器进程组。调用者必须排空 stdout 后调用一次 `finish()`，摘要最多 8 KiB，不保存原始 stderr。

**管道不继承和环境变量清理不是恶意进程隔离。** 当前包装器与合成服务使用同一 Actor 用户；同 UID/root 的恶意服务可能读取其他进程信息。只能启动受信任的镜像内服务，不能把它当作可运行任意第三方 MCP 程序的安全沙箱。

## EAP 管理的独立验收镜像

`runtime/agent-runner/mcp_fixture.go` 仅在 `mcpfixture && !kagent` 构建条件下启用。该镜像固定执行 `fixture_server.py` 的成功、工具失败和断连未知三种合成请求，禁止模型调用。任务完成表示三种预期结果均被观察并收到 EAP 接收回执，不表示所有工具调用成功。先保存 UNKNOWN 意图，重复同一任务只返回已保存回执；参数冲突拒绝执行。没有实现通用 MCP 客户端或完整服务符合性认证。

Agent78 / Workflow31 已通过 EAP 完成创建、版本发布、加密身份配送、序号 1→2 续期及原任务派发。实际保存 3 个 MCP Span、3 个被动工具记录和 1 个 Agent 父 Span；四角色查询一致。实际续期发生在派发前；会话运行期间的续期仅由远程集成测试验证。

原任务成功，但工作流因现存全局 `LEGACY/UNKNOWN` 占用保护停在 `SKIPPED_BUSY`，保持 FAILED，未执行 END。之后经 EAP 显式休眠此新实例并撤销凭证，没有重跑任务或修改历史占用。不能将本轮表述为自动回收完整通过。详细镜像、Trace、测试及原始失败见[实例验收](../../docs/evidence/mcp-actor-20261020/README.md)。

默认原生/Kagent 镜像未更换，暂无控制台通用 MCP 配置入口或既有 Actor 自动注入。后续已独立完成镜像内可信 stdio 清单和不同 UID 的实例替换验收，见[服务清单](../../docs/MCP_SERVICE_MANIFEST.md)及[替换边界](../../docs/MCP_INSTANCE_REPLACEMENT.md)。真实同名 UID 重建、通用持久恢复、多任务共享长连接及生产隔离策略仍待验证。

## 投递与丢失

网络队列默认 64 项（模块限制最多 256），每项最多 8 KiB，只在内存保存。事件只尝试一次 HTTP 请求；不跟随重定向、不读取代理环境变量、不自动重试。401/403 禁用当前租约，等待宿主的新序号配送；旧请求返回 401 不会禁用已经安装的新租约。

摘要 `runtimeExport` 区分 `accepted`、`rejected`、`unknown`、`unavailable`、`queueDropped`、`shutdownDropped`。`accepted` 需要 EAP 明确返回一个 MCP Span 的接收回执且没有警告；它不表示工具执行成功。超时、5xx、不完整或含警告回执记为 `unknown`；业务协议和工具退出码保持原样。

`--export-timeout` 默认 1 秒（0.1–5），是连接/读取超时；不是 DNS 和整个系统调用的绝对截止时间。关闭时最多等待队列 2 秒，再把在途请求记为未知、余队列记为丢弃并尝试断开连接。摘要 `workerStopped=false` 表示后台线程尚未退出；它是 daemon，不阻止包装器退出。无磁盘补投、自动重启或持久恢复；进程崩溃会丢失内存队列和未输出的统计。事件文件写入有独立队列，不受网络队列容量控制。

远程回归：

```sh
python3 -m unittest -v test_observer test_runtime_export test_http_observer
python3 fixture.py > mcp-protocol-fixture.json
python3 http_fixture.py > mcp-http-protocol-fixture.json
```

`fixture.py` 通过三个实际子进程生成成功、工具失败和断连未知的 OTLP，用于后端跨语言契约测试。全部为合成数据，不调用模型或生产 MCP 服务。按项目约定，不在本机运行这些测试。

设计、边界与 eBPF 后续试验见 [MCP 观测说明](../../docs/MCP_OBSERVATION.md)，协议入库实测见 [原验收记录](../../docs/evidence/mcp-observer-20261020/README.md)，网络导出实测见 [导出器验收](../../docs/evidence/mcp-exporter-20261020/README.md)。

## 镜像内启动配置

Go Runner 可信宿主已支持 `EAP_MCP_SERVICES_V1`。将 `services.fixture.json` 按自己的可信服务配置调整，并作为 `/opt/eap-mcp/services.json` 烘焙到固定 digest 镜像；宿主通过注册名称调用，配置启动后冻结。清单中的 tools 仅是观测名称白名单，不是执行权限。详见 [服务清单合同](../../docs/MCP_SERVICE_MANIFEST.md)。Python 独立宿主 API 仍使用原显式参数，不自动读取这份 Go Runner 清单。

## HTTP 请求观测

`http_transport.ObservedHttpClient` 提供按请求独立的 HTTP POST 流式适配，接收固定 endpoint、server、tools、emit、protocol_version。HTTPS 是默认；PoC HTTP 必须显式启用。`post()` 返回上下文管理器，`read(size)` 原字节读取响应；status、headers 保持，headers 中的凭证不会进入事件。必须读取到结束或退出上下文。

`emit` 可复用 `RuntimeExporter.submit`；MCP 服务 Authorization 通过 `post(headers=...)` 在 RAM 传递，EAP 身份只经原 RuntimeLease 管道进入独立导出器。每次 post 的 trace_id / parent_span_id 由可信宿主任务提供；同一个客户端的并发请求不会共享 JSON-RPC ID 表。不是从 HTTP 请求头推断任务或 Agent。

适配器观察 JSON 与 SSE 最终工具结果，支持 2025-11-25、2026-07-28 的共同结果子集。宿主仍负责协商、会话、能力、2026 请求元数据头和客户端业务逻辑；它不是完整 MCP SDK。异步 task、MRTR、压缩响应、断连或不能确认的结果保持 UNKNOWN，不跟随重定向、不重连、不重试工具或遥测。

SSE 支持 LF/CRLF/CR、UTF-8 分片、BOM、多行 data、心跳和进度；不存储 SSE 游标或参数/正文。默认每帧、每行及累计事件 64 KiB，超限停止语义关联并继续返回原字节。2025 独立 GET 流、Last-Event-ID 恢复、旧版 HTTP+SSE 双端点和跨任务共享连接尚不支持。

`http_observer.HttpExchangeObserver` 也可直接接入既有 HTTP SDK 的传输层，依次调用 request、response、feed、finish。同一 exchange 必须串行调用。这需要宿主接入，不属于零配置无侵入观测。

验证分层、完整边界与规范来源见 [HTTP 观测合同](../../docs/MCP_HTTP_OBSERVATION.md)。

`http_actor_probe.py` 是可信固定验收宿主，通过 Go Runner 的 `mcpLaunchConfig.Observer` 固定选择 HTTP 摘要合同，复用匿名租约管道、续期、原任务 Trace/父 Span、取消和有界导出。`http_actor_server.py` 是仅回环监听的合成服务；宿主没有开放任务自定义 endpoint/command。子进程检查未继承 EAP 环境及额外管道 FD，这不构成同 UID/root 进程隔离。

专用 `mcphttpfixture` 构建已在 EAP 新建 Agent82 / Workflow36 验收：一个任务、8 个 HTTP POST、6 个 MCP Span/工具记录、1 个 Agent 父 Span，四角色一致，模型调用 0；完成后通过 EAP 显式暂停并撤销连接。任务成功表示预期的成功/错误/未知分类与导出全部确认，不能把其中的 UNKNOWN 改写为成功。远程测试验证运行中续期、取消清理和持久回执不重派；真实轮换发生在派发前。详见[HTTP Actor 验收](../../docs/evidence/mcp-http-actor-20261020/README.md)。

默认原生/Kagent 镜像没有因此升级。通用 HTTP 服务声明/UI、Kagent MCP 执行、TLS/OAuth、跨任务连接、实际崩溃恢复和 eBPF 对照仍待完成。

