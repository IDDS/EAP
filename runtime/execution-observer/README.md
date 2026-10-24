# 同实例逻辑执行观测 SDK（第一阶段）

本目录提供框架中立的 Python 生命周期 API，首版面向 Linux/Python 3.10，使用标准库，不依赖特定多 Agent 框架。配套 EAP 接收接口为 `POST /api/runtime-access/v1/execution-events`，查询为 `GET /api/observability/agent-executions` 与 `GET /api/observability/agent-executions/{streamKey}/graph`。完整字段与回执见[事件v1契约](../../docs/AGENT_EXECUTION_EVENT_CONTRACT.md)。

这是显式接入的观测 SDK，不会自动发现框架内部 Agent，不提供代码执行器、工具许可或子 Agent 调度。[固定版本框架适配](frameworks/README.md)已提供LangGraph节点和AutoGen Core直接RPC入口；CrewAI、ADK/Kagent等适配仍待开发，新增框架创建模板和EAP托管实例端到端验证仍未完成。当前接口适用于既有受管、已验证的非 `SUBSTRATE_TASK` 运行实例；专属 AX 任务入口的原通道限制保持。

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

框架适配器可通过 `current_or_none()` 捕获本地上下文，并在同Journal的队列接收边界使用 `agent(..., parent=captured_context)`；显式 `parent=None` 创建独立根执行，避免继承无关的队列启动上下文。不要从消息正文或远端自报字段构造父身份。`incomplete_on=(FrameworkControlFlow,)` 让指定的框架控制流异常保留开始事件而不伪造结束；该参数只接受Exception子类，不改变异常传播。

`agent()` / `tool()` 是同步上下文管理器，可包住 `await`；本地记录有 SQLite 提交开销。网络发送使用显式 `flush()`，异步应用可通过 `asyncio.to_thread` 调用。没有性能或低延迟保证，需针对客户负载另测。

## 临时代码

```python
with journal.agent("coder"):
    snapshot = journal.capture_code(Path(temporary_path).read_bytes(), language="PYTHON")
    with journal.tool("generated-calculation", code=snapshot):
        result = approved_executor(snapshot.data)
```

`CodeSnapshot` 持有不可变字节和摘要，日志与服务端只保存 SHA256、大小、语言和创建执行。必须把 `snapshot.data` 交给原有授权执行器，才能声称执行的是这个版本；SDK不证明调用方实际执行了哪些字节，也不授予执行权限。临时文件随后删除不影响已有元数据查询，代码正文没有被保管，不能靠摘要恢复代码或重放行为。

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
