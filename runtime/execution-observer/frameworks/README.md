# 固定版本框架观测适配

首版面向 Linux / CPython 3.10.12，验证 LangGraph 1.2.17、AutoGen Core 0.7.5。采用显式入口包装，不修改全局框架函数，不自动把图节点或消息对象当成Agent。依赖精确版本见[锁定清单](requirements-lock.txt)，逐项能力见[能力清单](capabilities.json)。适配器会拒绝未登记的框架版本和不一致的Journal框架身份。

这些模块提供执行观测接入。配套[固定求和 IMAGE 模板](../../framework-runner/README.md)已在 EAP 托管实例完成创建、派发、工具许可、真实执行关系查询与显式休眠；镜像 CPython 3.10.22 内复验通过。当前控制台零代码创建仍只有 EAP 原生与有界 Kagent/Go ADK。任意镜像/源码应用仍须适配任务、身份、控制和模型/工具治理协议，安装本模块不会自动完成这些工作。

## LangGraph

```python
from eap_execution import Journal
from eap_langgraph import LangGraphAdapter

journal = Journal(private_journal_path, runtime_instance_key,
                  framework="langgraph", version="1.2.17")
observer = LangGraphAdapter(journal)

# researcher 是应用原有节点函数；只有显式声明的节点计为Agent。
graph.add_node("researcher", observer.agent_node("researcher", researcher))
# 工具函数可用于 StructuredTool.from_function 或原有工具构造入口。
observed_calculation = observer.tool("calculation", calculation)
```

函数签名与解析后的类型注解保留，支持框架注入 `RunnableConfig`，同时支持同步/异步函数。异步生成器、生成器及任意callable对象不在首版范围；不要把开始返回一个迭代器当成流式调用完成。

每次节点调用都会产生新的执行编号。重试分别记录失败和后续成功的尝试，但当前事件协议不包含 `RETRY_OF` 关系。嵌套子图只有显式包裹的Agent入口才产生节点；图的边、并行汇合、检查点和普通数据处理节点不自动变成父子Agent。

同步图的中断控制流不写成功或失败结束事件，保留 `INCOMPLETE`；恢复后的再次调用产生新执行编号，不擅自关闭原记录，也没有 `RESUME_OF` 关联。**本次Python3.10环境中，异步图里的 `interrupt()` 无法取得Runnable上下文，未包装的原生调用也已复现；该用法不在已支持范围。** Python3.11+尚未验证。LangGraph官方也明确指出Python3.11以下的异步上下文传播存在限制，接入时需核对实际调用方式。[官方说明](https://docs.langchain.com/oss/python/langgraph/streaming#async-with-python-3-11)

## AutoGen Core

```python
from autogen_core import SingleThreadedAgentRuntime, RoutedAgent, AgentId, MessageContext
from eap_execution import Journal
from eap_autogen import AutoGenAdapter

journal = Journal(private_journal_path, runtime_instance_key,
                  framework="autogen-core", version="0.7.5")
runtime = SingleThreadedAgentRuntime()
observer = AutoGenAdapter(journal, runtime)

class Worker(RoutedAgent):
    def __init__(self):
        super().__init__("worker")

    @observer.message_handler("worker")
    async def handle(self, message: Work, ctx: MessageContext) -> Result:
        return await perform_work(message, ctx.cancellation_token)

# 在原有事件循环中注册、启动并调用；Work/Result由应用定义。
await Worker.register(runtime, "worker", Worker)
runtime.start()
try:
    with journal.agent("coordinator"):
        result = await observer.send_message(Work(), AgentId("worker", "one"))
    await runtime.stop_when_idle()
finally:
    await runtime.close()
```

装饰器代替这些指定入口的 `@message_handler`；根调用和Agent内部嵌套调用均通过 `observer.send_message()`。嵌套调用可传 `sender=self.id` 和原取消令牌。只接受同一个 `SingleThreadedAgentRuntime`、同一事件循环中的直接RPC，其他运行时拒绝。适配器不会接管框架启动、停止和订阅。

发送时捕获本地观测上下文，以新消息编号保存临时关联；接收时校验消息编号、发送者和接收者，单次消费，然后建立执行。父节点不从消息正文、Agent名称或队列启动时的环境上下文推断。待处理关联默认128、上限1024，发送完成/失败/取消均清理；超出容量在派发前报错。未经过本适配器发送的消息抵达已包装处理器时，会在业务处理前拒绝，不能直接对所有既有处理器批量替换。

本阶段不支持发布订阅、分布式Runtime、AgentChat、组聊handoff或跨进程父子关系。`FunctionTool`可使用 `observer.tool()` 包装的函数；这不会自动接入所有第三方工具、MCP和代码执行器。AutoGen Core与AgentChat的生命周期不同，应单独适配。[官方说明](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/framework/agent-and-agent-runtime.html)

## 上报、治理与验证

本模块复用[SDK](../README.md)及[事件v1契约](../../../docs/AGENT_EXECUTION_EVENT_CONTRACT.md)。开始事件先本地持久化，显式 `flush(HttpTransport(...))` 才发送给EAP；运行凭证由可信启动器在内存供给。每个框架使用自己的Journal；跨Journal委派尚不支持。临时代码仍使用 `capture_code()` 和 `tool(..., code=snapshot)` 显式登记，不自动捕获源码。

适配器不增加工具许可、预算、隔离或业务重试能力。错误类型与返回值向调用者传播；EAP事件不包含状态正文、消息、参数、结果或异常文字。第三方框架自身的日志、模型调用及其他exporter须由部署方单独配置；本模块不能保证应用的全部出口都已受管。本轮验证使用无生产凭证的干净环境，禁用LangSmith/AutoGen运行遥测及OTel SDK，没有真实模型调用。

所有依赖安装和测试只在远程进行。SDK与真实框架专项分别运行；`fixture.py`执行真实图和消息运行时，生成纯元数据事件供EAP后端H2/MySQL契约验证。具体结果、首次失败和未测范围以[验证记录](../../../docs/VALIDATION.md)为准。
