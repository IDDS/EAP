# EAP 受控框架执行样例

固定模板用于验证 EAP 创建、身份配送、工作流派发、逻辑执行观测和任务后休眠。LangGraph 1.2.17 使用顺序图，AutoGen Core 0.7.5 使用同运行时直接 RPC，均运行“协调者 → 校验 Agent → 临时 coding Agent”的确定性求和样例，没有模型调用。

复用 EAP Runner 的独立实例身份、在线工具检查、一次性许可、回执与持久化防重放。Python 框架只在许可确认后启动。控制台“部署与运行 → 受控框架样例”提供两个固定 `TEMPLATE` 选项，不代表可托管任意框架项目。管理员需配置固定摘要镜像，参见[模板配置与使用](../../docs/FRAMEWORK_SAMPLE_TEMPLATES.md)。

| 框架 | EAP `IMAGE` 部署命令 |
|---|---|
| LangGraph | `/usr/local/bin/eap-langgraph-runner` |
| AutoGen Core | `/usr/local/bin/eap-autogen-runner` |

两个入口共享镜像依赖，框架选择固定在各自二进制中。任务、模型输出和环境变量不能选择其他框架、源码或导出地址。

## 执行与观测

通过 EAP 的 `TEMPLATE` 部署创建实例，平台固定端口 80、就绪路径 `/readyz`，配送身份及网关配置；既有 `IMAGE` 接入仍保留。工作流 Agent 节点冻结 `eapTool.name=calculate_sum` 和 1–100 个有限数字，每项绝对值不超过 `1e12`。样例使用 `[10,20,12]`，结果 42；模型预算为 0、工具额度为 1。本轮环境存在历史未决占用，使用 `suspendAfterTask:false`；任务终态后通过 EAP `PAUSE` 显式休眠并撤销凭证，未证明自动回收。

Go Runner 持久化意图并领取许可后启动固定 Python 入口。协调者、校验者、coding Agent 和临时执行者是同一 Python 进程内的逻辑执行，各有独立编号。当前版本成功路径有 4 次 Agent 执行、1 次工具调用、2 条相同摘要的代码登记，共 12 条事件。2026-10-25 已成套发布并完成真实 EAP 工作流验收；历史旧镜像仍为 9 条事件，不回写旧记录。

临时代码按已校验的数字数量生成固定求和语句，生成者捕获字节快照，临时子 Agent 用 `bind_code()` 登记并执行同一字节；文件在执行前删除，事件保留 SHA256、大小与语言。数字值和用户文本不会拼接进代码，不开放任意代码执行器、shell、外部 MCP 或模型生成代码。根执行继承工具 Trace，子执行继续继承；服务端按已认证实例处理归属，可在“运行与观测 → 执行关系”查看。

接入示例和证据边界见[短生命周期执行追踪](../../docs/EPHEMERAL_EXECUTION_OBSERVABILITY.md)。

## 失败与持久化

- 框架进程异常、超时或回包无法核对时，原意图保持 `EXECUTION_UNKNOWN`，不回退或重新执行。
- 同任务键同参数返回持久化原回执，参数变化拒绝；未知任务也不自动重算。
- 上传时对网络故障、HTTP 429/5xx 最多尝试三次，只重传原事件。认证拒绝、冲突或非法回执停止投递；失败不改变已确认的计算结果。
- `observation.pending` 标记待确认事件，`deliveryState` 和 `deliveryAttempts` 表达该次投递的结果与次数。它们是原任务回执快照，不是集中队列的实时状态；当前本地状态可通过 SDK `delivery_status()` 读取。
- 一次有界投递结束后调用 `report_status()`，最多发送一次状态请求。该请求失败不会重算工具或改写事件确认结果；控制台展示最近成功报告的快照及当前服务端接收数，不能证明实时待发队列。
- SQLite 保留原事件，可用 `existing_only=True` 重开已知原日志并显式 `deliver()`。新版 Worker 将成功业务的新日志按捕获的原 UID/身份代际登记并封存；持久 Go Runner 每 30 秒检查，最多六轮，预算在发送前落盘，重启不重置。只补原日志，旧日志不自动登记；休眠时不投递，撤销后不能绕过身份验证。
- 每次调用以 `O_EXCL` 预留 Journal，已有文件禁止执行。补发只能打开原 Journal 并上传原事件，不能再次调用 `execute()`。原回执新增 `backgroundDeliveryState` 表示登记结果；后台最近一轮的脱敏诊断可经已认证 `/eap/state` 查询，详情见[后台补发契约](../../docs/EXECUTION_BACKGROUND_DELIVERY.md)。
- 凭据只经父子进程 stdin 传递，不写命令参数、环境、SQLite、工具日志或错误输出。事件不含参数、结果、源码正文和异常文字。

每次框架进程上限 30 秒，输入和输出有界。日志须由实例独享；删除日志、快照回滚/克隆、多进程共享不在防重放保证范围内。上传状态与业务结果分别表达；认证生产者报告不等于独立效果证明。

## 构建与验证

安装、测试和构建全部在远程执行。Go 原生与 Kagent 回归保护共享 Runner；`-tags framework` 专项验证桥接校验、许可、重复/未知任务和入口限制。Python 分别运行 SDK、真实适配器和 `test_worker.py`。构建后须在实际镜像内复跑 Python 套件并验证 Go 子进程桥接。

Dockerfile 的 `PYTHON_BASE` 必须为经核验的 Python 3.10 linux/amd64 固定摘要。构建目录包含受测的两个 Go 二进制、`worker.py`、SDK/适配模块和匹配[依赖锁定表](../execution-observer/frameworks/requirements-lock.txt)的 `site` 目录。连接信息和 Secret 不进入镜像上下文。

实测结果以[验证记录](../../docs/VALIDATION.md)和[框架支持表](../../docs/FRAMEWORK_SUPPORT.md)为准。默认模板的跨实例关联自动接入、AutoGen AgentChat/分布式运行时、LangGraph 异步中断恢复、任意业务工具及通用零代码创建仍需独立实现和验收。

发布证据见[验证范围](../../docs/VALIDATION.md)。镜像构建显式设置两个 Go 入口为 `0755`，避免复制产物丢失可执行权限。
