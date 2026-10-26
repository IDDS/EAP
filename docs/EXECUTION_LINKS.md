# 跨实例执行与代码交接观测

EAP 支持由两个已认证运行实例分别报告同一关联：`DELEGATE` 表达执行委派声明，`CODE_HANDOFF` 表达代码摘要交接声明。来源报告后是 `PENDING_CONFIRMATION`；目标以自己的身份报告同一编号和内容后才是 `CONFIRMED`。这是观测证据，不是远程执行、任务接管或授权接口。

每个实例仍保留自己的事件流和逻辑 `runId`。同名 Agent、相同 PID、Trace 或代码摘要不会自动建立关系，也不会改写本地 `parentRunId`。原始执行事件和结果保持不变。

## 接入顺序

1. 两方分别用自己的 EAP 运行身份、独立 Journal 上报 `RUN_STARTED`，取得服务端确认的 `streamKey`。实例的创建和任务调度继续走 EAP 已有入口。
2. 应用通过既有受信任业务通道交换 `ExecutionReference`，由协调方生成一次 `linkId` 并冻结来源、目标及关系类型。不要在观测 envelope 中传密码、令牌、授权或源码。
3. 来源将 `SOURCE` 意图写入原 Journal，显式调用 `deliver()`。收到 `PENDING_CONFIRMATION` 只说明来源声明已接收。
4. 目标核对原意图，再将同一 envelope 以 `TARGET` 写入自己的 Journal 并上报。目标先于来源时返回冲突；应补投原来源声明，再投原目标声明，不重跑任务。
5. 在“运行与观测 → 执行关系”打开事件流，读取跨实例关联。确认后的记录可以跳转到另一方事件；未确认记录明确显示目标身份尚未确认。

`CODE_HANDOFF` 还要求两方在各自的确切 `runId` 下登记同一摘要、大小和语言。接收方必须独立捕获收到的字节，并由原有授权执行器决定是否执行。观测接口不传送或保存源码正文，双方声明一致也不证明代码安全、实际执行字节或业务效果。下述固定样例通过 EAP 工作流的业务输出传递公开程序正文，与观测记录分开，不能据此声称整个系统没有保存这份样例代码。

## 已完成的跨实例样例（历史独立镜像验收）

可选 [handoff-sample](../runtime/handoff-sample/README.md) 已通过真实 EAP 创建、IMAGE 部署、工作流调度和收尾：来源实例的 LangGraph 生成公开固定程序，目标实例的 AutoGen Core 短进程执行，随后两个运行实例观察器核对原结果并确认关联。样例工作流仅一次求和工具执行，结果42、模型调用0；14条事件、5次逻辑执行、2条代码登记、2条已确认关联。四次原关联重发没有产生第二次代码执行。

生成和执行的 Python 进程退出后，父运行实例继续持有原结果与私有 outbox。后续 `offer` / `confirm` 是运行实例观察器的报告，保持原 producer、stream、run、link 和身份代际；不是复活原进程，也没有放开 SDK 重开 Journal 新建意图的限制。两实例最终休眠并撤销凭证，历史事件及确认记录仍可通过四角色控制台读取。

样例镜像独立为 `eap-handoff-sample@sha256:c6e53ce1f97d109a0a2af46045928171b72245d2e42b5a2b21d33b90871bae09`，默认固定框架镜像未替换。完整流程、远程测试及真实截图见[验证范围](VALIDATION.md)。该公开固定程序不是任意生成代码沙箱，也不是通用源码归档服务。

## Python SDK

将 `eap_execution.py` 和 `eap_links.py` 一起放入受管运行环境。以下代码是两端已有执行的接入片段；`source_context`、`target_context` 来自各自 `journal.agent()` 的上下文，且原开始事件已成功投递。不要为了得到引用重新执行已有任务。

```python
# 来源端；target_ref 通过既有业务通道取得。
from uuid import uuid4
from eap_links import ExecutionReference, LinkIntent, LinkJournal, reference

source_ref = reference(source_journal, source_context)
intent = LinkIntent(str(uuid4()), source_ref, target_ref)
outbox = LinkJournal(source_journal)
outbox.queue(intent, side="SOURCE")
source_status = outbox.deliver(source_transport)
envelope = intent.envelope()  # 仅公开关联字段，由应用传给接收端。

# 接收端；使用自己的 Journal 和凭据。
accepted = LinkIntent.parse(envelope)
if accepted.target != reference(target_journal, target_context):
    raise ValueError("target execution differs")
target_outbox = LinkJournal(target_journal)
target_outbox.queue(accepted, side="TARGET")
target_status = target_outbox.deliver(target_transport)
```

代码交接使用 `LinkIntent(original_link_id, source_ref, target_ref, "CODE_HANDOFF", digest)`。两个登记事件须先得到确认；同一 Journal 的 `bind_code()` 约束没有放宽，不能把另一 Journal 的内存快照直接当作本地登记。

每份 Journal 最多64条关联意图，包含已确认历史；服务端每来源流最多64条关联。`deliver(limit=16)` 每次最多处理1–64条，每条只尝试一次，遇错停止；不调用 Agent、模型或工具。回执丢失时重新打开**原日志**，显式补发相同编号和内容。重开或封存的日志不能新增关联，原有意图可继续补发；fork 后不能复用父进程日志。更改已存在编号的内容会拒绝。

`status()` 的 `pending` 是本地尚未确认送达的意图数，`lastReceiptState` 是最后一次回执，不是实时服务端状态。来源收到待确认回执后，本地投递可以完成；目标稍后确认不会主动更新来源日志。需要最新双方状态时查询 EAP。现有后台事件补发器尚未自动发送关联 outbox，需要应用显式调用。

## HTTP 契约与权限

机器接口 `POST /api/runtime-access/v1/execution-links` 使用原有 `X-EAP-Runtime-Key` / `X-EAP-Runtime-Token` 认证，只有当前有效身份可写自己的流。人类 JWT 不能替代运行身份。请求字段为：

| 字段 | 约束 |
| --- | --- |
| `schemaVersion` | 整数1 |
| `producerId`、`linkId`、`sourceRun`、`targetRun` | 非全零、小写 UUID |
| `framework`、`frameworkVersion` | 与原 producer 的已接收事件一致 |
| `side` | `SOURCE` 或 `TARGET` |
| `relation` | `DELEGATE` 或 `CODE_HANDOFF` |
| `sourceStream`、`targetStream` | 不同的64位小写十六进制服务端流编号 |
| `artifactDigest` | 仅 `CODE_HANDOFF` 必填；其他关系禁止该字段 |

拒绝额外字段。Agent ID、实例归属、接收时间及状态由服务端确定。确认要求两方属于不同实例；同实例逻辑子 Agent 使用已有父子事件契约。原编号重复上报幂等，不刷新原时间；更改关联内容冲突。没有收到来源声明、原开始事件或代码登记时，不替调用者补造证据。

读接口 `GET /api/observability/agent-executions/{streamKey}/links?afterId=0&limit=50` 沿用 `PlatformPermission.VIEW_CONSOLE`；`limit` 为1–100，按记录ID翻页。控制台每页50条，权限、账号、流选择改变后清空旧数据并中止旧请求。读取失败显示错误，不能当作没有关联。

回执及查询记录中的 `executionPermissionGranted` 固定为 `false`。证据级别分别是 `SOURCE_PRODUCER_REPORT` 与 `TWO_AUTHENTICATED_PRODUCER_REPORTS`。来源在有效身份下成功报告后，即使随后退出或撤销，目标仍可确认该历史声明；这不恢复来源权限。失效或换代的目标身份不能写原流。未确认记录没有自动过期或自动确认策略。

## 当前边界

这是显式接入协议和控制台能力。新版默认 LangGraph/AutoGen 模板已复用 handoff 实现，工作流设置 `context.eapHandoff={"protocol":"EAP_CODE_HANDOFF_V1"}` 后可生成双方确认关联，配置见[默认模板](FRAMEWORK_SAMPLE_TEMPLATES.md)。普通求和任务、旧实例、通用 Go Runner 和 Kagent 不自动产生这些关联；其他框架消息交换和关联 envelope 的可靠传送仍需应用适配。没有通用消息总线、跨实例任务接管、预算或授权传播、通用源码原文归档、自动业务重试和补偿。大规模并发、保留策略和长稳仍需单独验证；当前关联写入使用短事务串行保护。

部署、测试与未验证项集中记录在[验证范围](VALIDATION.md)。不要把隔离契约测试或浏览器合成状态展示当作真实原生跨实例业务验收。
