# 固定 HTTP 合成夹具的阶段诊断

本次改动用于修复已确认的证据缺口：旧夹具将启动、READY、控制、回执和退出错误统一折叠成 UNKNOWN，未保留阶段及耗时。Agent87 的原任务仍是 EXECUTION_UNKNOWN，20010ms；新增诊断不能回填该任务，也不能据此声称已经修复其底层原因。远程发布与真实样例结果见[本次证据](evidence/http-fixture-diagnostics-20261021/README.md)。

## 合同

专用 `mcphttpfixture` 构建保持 20 秒任务截止时间、一次任务意图、原键不重放、无模型及固定合成 HTTP/SSE 协议行为。默认原生 / Kagent Runner 不随本次更新；前端、权限与 Schema 保持。

任务回执新增可选 `diagnostic`，旧持久意图没有此字段时按原样返回。新任务即使失败，也只在父 Agent Span 中输出固定阶段、是否到达截止时间、5 个非负毫秒耗时。失败维持 UNKNOWN，证据不完整时不生成成功输出。进程取消与清理不能覆盖最先确定失败的阶段。

| 阶段 | 表示正在进行或未能确认的操作 |
| --- | --- |
| START_OBSERVER | 启动可信的观察子进程 |
| WAIT_READY | 读取并校验观察子进程 READY |
| SEND_CONTROL | 发送可信控制消息 |
| READ_PROOF | 读取有界协议执行证明 |
| WAIT_EXIT | 等待子进程退出并读取有界观察摘要 |
| VALIDATE_PROOF | 校验执行证明的精确字段与固定协议结果 |
| VERIFY_EXPORT | 确认 6 条观测均收到接收回执，且无退化/丢弃 |
| PERSIST_RECEIPT | 持久化最终任务回执 |
| COMPLETED | 前述执行与观测校验通过 |

阶段表示主机侧路径，不是 Python 内部栈，也不是工具或外部效果结论。WAIT_READY 可包含解释器启动、模块导入、凭据管道和合成服务启动；READ_PROOF 可包含协议请求、观察导出和子进程清理。不能仅凭阶段名归因于 DNS、CPU 或网络。

公开 Trace 属性：`eap.runtime.stage`、`eap.runtime.deadline_exceeded`，以及 `eap.runtime.observer_start_ms`、`eap.runtime.ready_wait_ms`、`eap.runtime.control_write_ms`、`eap.runtime.proof_read_ms`、`eap.runtime.exit_wait_ms`。阶段和布尔标记只接受固定字符串集合，耗时只接受受限长度的非负整数；写入与历史读取都通过 `TraceMetadata`。角色权限沿用现有原操作 / Trace API，不新增角色规则。

不保留命令、进程错误正文、请求头/体、凭据、任意阶段名或堆栈。父 Span 仍为一条 `agent.task`，不会将阶段记录伪造为额外 Agent 或 MCP Span。耗时为进程内单调时钟的分段差值，毫秒截断与未单列的校验/落盘时间意味着不能要求总和严格等于父 Span 时长。`deadline_exceeded=false` 也不代表成功，可能是其他失败或上游取消。

## 验证范围

远程旧版本对照要求确实缺失阶段证据；新版覆盖等待 READY 超时、错误 READY、读取证明超时、子进程异常、错误证明、导出未确认及启动失败。验证固定诊断、不含私有文本和令牌、截止时间、清理与原 UNKNOWN 意图不变；Python 观察器与后端完整套件另行回归。应用测试和构建不在本机执行。

真实验收使用新 Agent 和一次新任务。EAP 完成创建、部署、工作流派发、观测、显式暂停和连接撤销；成功与 UNKNOWN 都保留原结果并导出阶段，不为获得成功证据重派。四角色读取应相同；原 Agent87 及历史记录摘要必须保持。

## 尚未证实的启动线索

历史成功任务父 Span 约为 9559、11122、11481、12911、18519ms，但协议 Span 多数只有几毫秒；Agent87 在 20010ms 失败。这些不同批次的离散样本不是受控性能对照，不能据此断言持续退化或确定根因。

CPython 3.12 的 `HTTPServer.server_bind` 调用 `socket.getfqdn(host)`，ThreadingHTTPServer 继承该行为。[官方源码](https://github.com/python/cpython/blob/3.12/Lib/http/server.py#L136)（访问 2026-10-21）。远程 Python3.10.12 对当前服务源码的拦截测试确认 READY 前进入该调用；拦截后立即停止，实际 DNS 请求 0、模型和任务派发 0。结论仅是存在解析依赖，没有重现生产 DNS 卡顿，也没有证明它造成历史 UNKNOWN。应结合新阶段证据后再设计单变量对照，不盲目延长超时或放宽网络权限。

## 本轮实测

后端145类1321项、Go HTTP/身份23顶层/含子用例60（race/vet）、Python58项通过。旧版缺失诊断的对照按预期失败；新版故障注入通过。Agent88 / Workflow42唯一新任务SUCCEEDED、总11123ms，启动15ms、READY7241ms、控制0ms、证明3861ms、退出1ms，6MCP/工具+1Agent、模型0。四角色API读数一致；最终EAP显式SUSPENDED/REVOKED。原Agent87仍UNKNOWN、未重放、根因未证实。

后端和专用夹具已部署；默认Runner、前端/Schema/权限保持。本轮没有新增前端阶段展示，阶段证据通过现有Trace API读取。既有34条采样指标和前端HTTP门槛复验通过，4临时Pod按UID清理。440份证据及922输入下载核验；批次已关闭，原写入口禁止重放。

## 2026-10-22 启动细分后续

新增成功输出内的固定启动分段，在新Agent89样例中确认两段Python导入占READY约90%；历史Agent87根因仍未证实。新字段没有扩展父Span白名单或前端界面，失败时不保证留存；详见[字段合同](HTTP_STARTUP_TIMING.md)及[验收](evidence/http-startup-timing-20261022/README.md)。
