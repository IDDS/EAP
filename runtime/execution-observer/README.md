# 同实例逻辑执行观测 SDK（第一阶段）

本目录提供框架中立的 Python 生命周期 API，首版面向 Linux/Python 3.10，使用标准库，不依赖特定多 Agent 框架。配套 EAP 接收接口为 `POST /api/runtime-access/v1/execution-events`，查询为 `GET /api/observability/agent-executions` 与 `GET /api/observability/agent-executions/{streamKey}/graph`。完整字段与回执见[事件v1契约](../../docs/AGENT_EXECUTION_EVENT_CONTRACT.md)。上报后可在“运行与观测 → 执行关系”查询，界面范围见[控制台说明](../../docs/AGENT_EXECUTION_CONSOLE.md)。

这是显式接入的观测 SDK，不会自动发现框架内部 Agent，不提供代码执行器、工具许可或子 Agent 调度。[固定版本框架适配](frameworks/README.md)已提供 LangGraph 节点和 AutoGen Core 直接 RPC 入口；配套[固定求和模板](../framework-runner/README.md)已完成 EAP 创建、派发、真实观测与显式休眠验收。该结论只覆盖两个受控模板，不代表任意项目或零代码框架创建；CrewAI、ADK/Kagent 等内部执行适配仍待开发。当前接口适用于既有受管、已验证的非 `SUBSTRATE_TASK` 运行实例；专属 AX 任务入口的原通道限制保持。

## 使用方式

把 `eap_execution.py` 放入远程运行环境的模块路径。调用方提供目录权限受控的日志文件路径，以及已经由 EAP 发放的运行实例凭证供应函数；凭证只在内存中使用，不能写进源码、日志或 SQLite。

```python
import asyncio
from pathlib import Path
from eap_execution import Journal, HttpTransport

# get_current_runtime_token 由可信运行启动器实现；这里不填入任何凭证。
journal = Journal(private_journal_path, runtime_instance_key)
transport = HttpTransport(eap_base_url, get_current_runtime_token)

async def worker():
    with journal.agent("researcher"):
        with journal.tool("local-calculation"):
            await controlled_local_work()

async def main():
    with journal.agent("coordinator"):
        await asyncio.gather(worker(), worker())
    while await asyncio.to_thread(journal.flush, transport):
        pass

try:
    asyncio.run(main())
finally:
    journal.close()
```

两次 `worker()` 即使处于同一进程、同一线程，也会得到不同的 `runId`。它们共享父执行的 Trace，工具调用归属当前协程的执行上下文。线程池不默认继承上下文，需要显式 `contextvars.copy_context()`；不要在多个线程同时进入同一个 Context 对象。fork 后旧 Journal 拒绝继续记录或发送，不能把父进程上下文当作子进程的执行事实。

可信任务入口可通过 `journal.agent("coordinator", trace_id=task_trace_id)` 传入既有 Trace，要求非全零的 32 位小写十六进制。子执行继承父 Trace，显式冲突会在开始记录前拒绝。未传入时仍自动生成；调用方不能借此覆盖服务端的实例身份归属。[受控框架样例](../framework-runner/README.md)展示了 Runner 工具调用与逻辑执行共用 Trace 的方式。

框架适配器可通过 `current_or_none()` 捕获本地上下文，并在同Journal的队列接收边界使用 `agent(..., parent=captured_context)`；显式 `parent=None` 创建独立根执行，避免继承无关的队列启动上下文。不要从消息正文或远端自报字段构造父身份。`incomplete_on=(FrameworkControlFlow,)` 让指定的框架控制流异常保留开始事件而不伪造结束；该参数只接受Exception子类，不改变异常传播。

`agent()` / `tool()` 是同步上下文管理器，可包住 `await`；本地记录有 SQLite 提交开销。网络发送使用显式 `flush()`，异步应用可通过 `asyncio.to_thread` 调用。没有性能或低延迟保证，需针对客户负载另测。

## 有界投递与状态

`flush()` 保留每批一次 HTTP 尝试。需要恢复短暂网络故障时使用 `deliver()`：

```python
status = journal.deliver(transport, max_attempts=3, max_batches=1,
                         backoff_seconds=0.25, stop_event=stop_event)
```

每批最多 64 条。单批尝试次数为 1–8，单次调用处理 1–64 批；默认一批、最多三次。只对连接故障、HTTP 429 和 5xx 退避重试，每次等待最长 5 秒；单个 HTTP 请求仍受传输超时限制。同一批重试固定原事件与编号，并发新增事件留给后续批次。重传没有 Agent、模型、工具或代码执行回调。

`delivery_status()` 从 SQLite 读取总事件数 `total`、全部待确认数 `pending`、已确认数 `acknowledged`、累计投递尝试 `attempts`（包括发送前发现凭据缺失）、最近尝试/确认时间和 `streamKey`；待发计数不受第一页 64 条限制。首次调用前尝试数为 0。状态与业务成功/失败分别表达：

| 状态 | 含义 |
|---|---|
| `EMPTY` / `ACKNOWLEDGED` | 尚无事件 / 当前日志的全部事件已确认 |
| `PENDING` / `BATCH_LIMIT` | 有待发事件 / 本次批数用尽，仍有待发事件 |
| `SENDING` / `RETRY_WAIT` | 最近记录的发送/退避阶段；不是进程存活证明 |
| `RETRY_EXHAUSTED` | 本次网络重试次数用尽，原事件保留 |
| `BLOCKED_AUTH` | 凭据不可用或 HTTP 401/403，停止本次投递 |
| `BLOCKED_CONFLICT` | HTTP 409 或流身份冲突，保留原日志核对 |
| `BLOCKED_REQUEST` / `BLOCKED_RECEIPT` / `BLOCKED_TRANSPORT` | 请求被拒、回执非法或无法分类的传输错误，不循环重试 |
| `CANCELLED` | 发送前或退避中收到停止信号，未确认事件保留 |

`stop_event` 为可选 `threading.Event`。停止信号仅取消后续投递尝试，不取消 Agent 任务；正在等待的 HTTP 请求按传输超时退出，已收到合法回执的事件仍会确认。状态不包含 HTTP 正文、异常文本或凭据。`attempts` 是跨调用累计数，固定 Worker 回执中的值则是该次新日志的尝试数。

进程退出后可对**已知的原日志和原实例身份**显式继续投递：

```python
recovery = Journal(original_journal_path, original_runtime_key,
                   framework=original_framework, version=original_version,
                   existing_only=True)
try:
    status = recovery.deliver(original_identity_transport)
finally:
    recovery.close()
```

`existing_only=True` 拒绝缺失或未初始化的文件，不创建新生产者；旧日志始终禁止新增执行事件。调用方必须确认原实例和身份代际仍然有效，不能换身份、换日志或重新执行任务来清除待发状态。永久错误的修复与再次调用需要调用方明确处理；普通SDK不会自行启动常驻服务。新版固定框架模板可显式登记并封存新日志，由持久Runner按原UID/身份代际有界补发，见[后台补发契约](../../docs/EXECUTION_BACKGROUND_DELIVERY.md)。未登记的日志不会在重启后自动补发。控制台读取最近报告快照，不能据此推断实时队列。

## 显式上报投递快照

```python
status = journal.deliver(transport, max_attempts=3, max_batches=1)
report = journal.report_status(transport)  # 单次元数据 HTTP 请求，不补发事件或执行任务
latest_report_result = journal.delivery_report_status()
```

`report_status()` 将当前计数与投递状态发送到固定的 `/api/runtime-access/v1/execution-delivery`；使用同一实例凭据、producer 与框架版本。报告序号在发送前提交到原 SQLite。每次显式调用使用更大序号，服务端拒绝迟到旧报告和计数回退；重复同序号同内容不会刷新报告时间。报告失败独立记录固定错误类别，不改变事件确认状态，不包含异常正文或凭据。`delivery_report_status()` 返回最近一次报告请求的结果，未调用时为 `NOT_REPORTED`。

可以用 `existing_only=True` 打开原日志后显式报告，不能借此更换原身份。固定框架 Worker 在一次有界事件投递结束后报告一次；新版 Go Runner 可对原身份下已登记的日志执行有界后台补发，不自动续期或唤醒。报告请求失败时，控制台可能仍为空或保留旧快照。

控制台分别展示客户端报告总数、已确认、待确认，以及服务端实际事件数。例如服务端已收到9条但回执丢失，客户端仍可报告9条待确认；不能把待确认数直接当作丢失数。报告格式、查询和过期含义见[集中投递状态](../../docs/EXECUTION_DELIVERY_STATUS.md)。

## 临时代码

```python
with journal.agent("coder"):
    snapshot = journal.capture_code(Path(temporary_path).read_bytes(), language="PYTHON")
    with journal.agent("temporary-executor"):
        executable = journal.bind_code(snapshot)
        with journal.tool("generated-calculation", code=executable):
            result = approved_executor(executable.data)
```

`CodeSnapshot` 持有不可变字节和摘要，日志与服务端只保存 SHA256、大小、语言和创建执行。必须把 `snapshot.data` 交给原有授权执行器，才能声称执行的是这个版本；SDK不证明调用方实际执行了哪些字节，也不授予执行权限。临时文件随后删除不影响已有元数据查询，代码正文没有被保管，不能靠摘要恢复代码或重放行为。

`bind_code()` 在同一 Journal、同一 Trace 中把已捕获字节登记到当前消费者执行；跨日志、跨 Trace 和未登记快照被拒绝。它不添加跨流生成来源边，不通过相同摘要推断因果。详见[短生命周期执行追踪](../../docs/EPHEMERAL_EXECUTION_OBSERVABILITY.md)。

同一执行内重复登记相同摘要会复用产物记录。代码变化产生新摘要；运行环境、依赖和闭包状态尚未纳入本阶段产物模型。Python 测试中的 `exec` 只执行固定公开算术夹具，不是产品执行入口。

## 留证与回执

- `RUN_STARTED`、`RUN_FINISHED`、`TOOL_STARTED`、`TOOL_FINISHED`、`ARTIFACT_REGISTERED` 五类事件。
- 每次调用产生新的运行/工具编号；事件编号和本地序号在写入 SQLite 时固定，显式重投复用原值。
- 日志文件权限要求 `0600`，使用文件锁限制单写者；每份日志最多4,096条事件，计数包含已确认历史，不自动删除。达到容量必须在新根执行前切换新日志，不能在同一调用中换编号续发。
- 开始事件无法本地提交时，不进入被包裹代码；这是调用方显式选择的严格留证行为。它不等于“服务器确认后才执行”，需要该语义时调用方必须在开始后成功 `flush()` 再执行业务。
- 业务异常/取消会尝试写结束事件；若日志同时失败，原业务异常优先，开始记录仍可能是不完整状态。异常消息、参数、结果正文和环境变量不进入事件。
- 重开旧日志只允许补发，不允许新增事件；新进程使用新日志。不可删除旧文件来掩盖未确认事件。
- 每次 `flush()` 最多64条、一次HTTP尝试，无自动重定向、环境代理或业务重试。失败或不合法回执保留待发事件；成功回执须满足 `recorded + duplicates == received`。
- 服务端按已认证实例、身份代际及 producer 隔离。身份代际变更后不能给原 producer 重新归属。凭证过期/撤销时应报告待交付缺口，不绕过验证补投。

服务端不接受调用方覆盖 `agentId`、端点、实例、任务或任意 metadata；只接受有界元数据字段。Trace 按原有可信运行逻辑解析。普通父子引用只在同一事件流内解析，缺少父记录会显示 `MISSING_PARENT`；跨流、跨实例和跨框架委派尚未支持。

## 查询含义

事件查询支持 `agentId`、`streamKey`、`afterId`、`limit`（1–500），返回游标与 `hasMore`。关系查询最多处理该流4,096条事件，展示运行、工具和代码产物，以及序号缺失、父节点缺失、开始/结束不完整和顺序冲突。

`complete=true` 仅表示**已经接收到的事件**没有这些结构缺口，不证明所有活动都已埋点、不证明未上报尾部事件不存在，也不证明工具业务效果。状态是 `AUTHENTICATED_PRODUCER_REPORT`：EAP验证上报者身份，无法据此独立证明任意上报内容真实。没有结束事件不标成功；运行中与崩溃未结束均可呈现 `INCOMPLETE`，本阶段没有自动超时判定。

读接口沿用 `PlatformPermission.VIEW_CONSOLE`；机器写接口使用运行实例认证。新增接口不改变人类权限模型、不创建正式Agent资产、不派发模型或工具、不改变已有业务执行结果。

## 验证

所有测试在远程隔离环境执行：`python3 -m unittest discover -v`；`fixture.py <output>` 生成实际SDK事件供Java测试复用。覆盖协程并发、取消、线程池、fork拒绝复用、代码删除/变化、日志重开/容量/单写者、非法内容及HTTP确认失败。后端专项还覆盖幂等、并发重投、身份隔离、代际变化、关系环、乱序、权限及MySQL。

整体方案、后续框架接入与限制见[跨框架设计](../../docs/MULTI_AGENT_EXECUTION_OBSERVABILITY.md)。实际通过结果以[验证记录](../../docs/VALIDATION.md)最新条目为准。
