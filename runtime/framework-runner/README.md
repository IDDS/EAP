# EAP 受控框架执行样例

固定模板用于验证 EAP 创建、身份配送、工作流派发、逻辑执行观测和任务后休眠。LangGraph 1.2.17 使用顺序图，AutoGen Core 0.7.5 使用同运行时直接 RPC，均运行“协调者 → 校验 Agent → 临时 coding Agent”的确定性求和样例，没有模型调用。

复用 EAP Runner 的独立实例身份、在线工具检查、一次性许可、回执与持久化防重放。Python 框架只在许可确认后启动；尚未增加控制台零代码框架选项，也不代表可托管任意框架项目。

| 框架 | EAP `IMAGE` 部署命令 |
|---|---|
| LangGraph | `/usr/local/bin/eap-langgraph-runner` |
| AutoGen Core | `/usr/local/bin/eap-autogen-runner` |

两个入口共享镜像依赖，框架选择固定在各自二进制中。任务、模型输出和环境变量不能选择其他框架、源码或导出地址。

## 执行与观测

通过 EAP 的 `IMAGE` 部署创建实例，端口 80、就绪路径 `/readyz`，由平台配送身份及网关配置。工作流 Agent 节点冻结 `eapTool.name=calculate_sum` 和 1–100 个有限数字，每项绝对值不超过 `1e12`。样例使用 `[10,20,12]`，结果 42；模型预算为 0、工具额度为 1。验收样例使用 `suspendAfterTask:false`；任务终态后通过 EAP `PAUSE` 显式休眠并撤销凭证，未证明自动回收。

Go Runner 持久化意图并领取许可后启动固定 Python 入口。协调者、校验者、coding Agent 是同一 Python 进程内的逻辑执行，各有独立编号。每次任务产生独立事件流，成功路径有 3 次 Agent 执行、1 次工具调用、1 个代码产物，共 9 条事件。

临时代码由模板固定字节生成，执行 SDK 捕获的同一字节快照；文件在上传前删除，事件保留 SHA256、大小与语言。输入不会拼接进代码，不开放任意代码执行器、shell、外部 MCP 或模型生成代码。根执行继承工具 Trace，子执行继续继承；服务端按已认证实例处理归属，可在“运行与观测 → 执行关系”查看。

## 失败与持久化

- 框架进程异常、超时或回包无法核对时，原意图保持 `EXECUTION_UNKNOWN`，不回退或重新执行。
- 同任务键同参数返回持久化原回执，参数变化拒绝；未知任务也不自动重算。
- 上传时对网络故障、HTTP 429/5xx 最多尝试三次，只重传原事件。认证拒绝、冲突或非法回执停止投递；失败不改变已确认的计算结果。
- `observation.pending` 标记待确认事件，`deliveryState` 和 `deliveryAttempts` 表达该次投递的结果与次数。它们是原任务回执快照，不是集中队列的实时状态；当前本地状态可通过 SDK `delivery_status()` 读取。
- SQLite 保留原事件，可用 `existing_only=True` 重开已知原日志并显式 `deliver()`；当前没有后台补发服务。实例休眠时不会继续投递，凭据撤销后也不能绕过身份验证补发。
- 每次调用以 `O_EXCL` 预留 Journal，已有文件禁止执行。补发只能打开原 Journal 并上传原事件，不能再次调用 `execute()`。
- 凭据只经父子进程 stdin 传递，不写命令参数、环境、SQLite、工具日志或错误输出。事件不含参数、结果、源码正文和异常文字。

每次框架进程上限 30 秒，输入和输出有界。日志须由实例独享；删除日志、快照回滚/克隆、多进程共享不在防重放保证范围内。上传状态与业务结果分别表达；认证生产者报告不等于独立效果证明。

## 构建与验证

安装、测试和构建全部在远程执行。Go 原生与 Kagent 回归保护共享 Runner；`-tags framework` 专项验证桥接校验、许可、重复/未知任务和入口限制。Python 分别运行 SDK、真实适配器和 `test_worker.py`。构建后须在实际镜像内复跑 Python 套件并验证 Go 子进程桥接。

Dockerfile 的 `PYTHON_BASE` 必须为经核验的 Python 3.10 linux/amd64 固定摘要。构建目录包含受测的两个 Go 二进制、`worker.py`、SDK/适配模块和匹配[依赖锁定表](../execution-observer/frameworks/requirements-lock.txt)的 `site` 目录。连接信息和 Secret 不进入镜像上下文。

实测结果以[验证记录](../../docs/VALIDATION.md)和[框架支持表](../../docs/FRAMEWORK_SUPPORT.md)为准。跨实例关系、AutoGen AgentChat/分布式运行时、LangGraph 异步中断恢复、任意业务工具及通用零代码创建仍需独立实现和验收。
