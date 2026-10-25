# EAP Agent Runner

平台参考运行程序，支持模型任务及显式启用的受控计算工具。旧镜像、旧模板和平台默认Runner不会因为源码变更自动升级。

可信 MCP 宿主可使用镜像内 `/opt/eap-mcp/services.json` 声明服务，再调用 `startMCPService`。配置启动时冻结；`GET /eap/mcp/profile` 需任务认证，仅返回服务和摘要。未配置的普通 Runner 保持关闭。参见 [配置合同](../../docs/MCP_SERVICE_MANIFEST.md)；这不自动开启模型或 Kagent 的 MCP 工具调用。

## 模型与接入

- AGENT_SYSTEM_PROMPT、AGENT_MODEL、AGENT_MAX_TOKENS配置模型任务；模型只通过EAP网关调用。
- 普通平台实例沿用EAP_TASK_TOKEN、EAP_GATEWAY_TOKEN。外部受控实例使用EAP_RUNTIME_KEY、EAP_RUNTIME_TOKEN；后者不发送旧平台机器凭证。
- /eap/task、/chat、/eap/state需要任务认证。/eap/integration返回签名挑战；/eap/integration/configure仅显式开启独立所有者配置入口时可用；/readyz为健康探针。
- 模型会话和旧模型任务缓存仍在内存；不将新增工具的持久化语义推广到模型任务。

零代码部署可通过 `maxTokens`（1–8192，Runner 默认1024）和 `thinkingMode` 冻结模型参数。思考模式默认 `PROVIDER_DEFAULT`；`DISABLED` 仅支持 deepseek-flash/deepseek-pro，向网关传递 `thinking.type=disabled`。这不会增加平台预算；用户环境变量不能覆盖这些参数。

普通任务只接受单个 `finish_reason=stop` 且非空的文本选择。截断、过滤、空正文、未确认结束或工具调用返回失败码，不进入历史和下游；无法解析或确认的响应保持 UNKNOWN。同 taskKey 的成功、失败及未知回执在内存复用，变参返回409，不自动再次调用模型。进程重建后的防重放仍依赖网关任务身份。

Agent Span 记录 `eap.model.finish_reason`、`eap.model.response_state`、请求上限及思考模式，不包含被拒绝的正文。`stop` 只代表模型正常结束，不能证明 JSON 格式、事实或业务质量合格。

## 实例内工具

设置EAP_TOOLS_ENABLED=true，绑定已纳管、已握手、ACTIVE且凭证有效的外部实例。EAP_TOOL_JOURNAL_DIR默认/data/eap-tool-journal，必须位于该实例私有的持久目录；一个目录只供一个Runner进程使用，不支持多进程共享执行。

当前仅内置calculate_sum，最多100个有限数、每项绝对值不超过1e12。未暴露shell、任意URL、文件路径或MCP执行器。计算不会调用模型。示例任务体（由EAP工作流执行API的context传入）：

```json
{"taskKey":"stable-business-task-id","objective":"求和","context":{"eapTool":{"name":"calculate_sum","arguments":{"numbers":[10,20,12]}}}}
```

工作流控制台已提供工具类型与数字表单，保存为节点 configJson.eapTool；执行器从冻结的节点配置注入上述任务 context，taskKey 由工作流生成。新工作流提交接口不再接受全局 context.eapTool；直接调用本 Runner 仍使用上面的任务格式。迁移与能力校验见 [工作流工具节点](../../docs/WORKFLOW_TOOL_NODES.md)。

有eapTool时，程序只走工具路径。未启用、不支持的名称、身份失效或未获准执行均停止，不回退到模型。没有eapTool的任务仍走原模型路径。

先保存固定调用标识和输入摘要，再检查策略、验证本地参数、持久化领取意图，最后领取一次性许可并计算。完成后保存本地计算结果，发送摘要回执与同Trace的Agent Span。正文参数与凭证不写入工具记录；本地记录保留计算结果以供重放，HTTP返回的结果仍通过EAP内容权限保护。

同taskKey同参数重放返回原记录；改变目标或工具参数409。并发串行处理。重建进程时复用持久目录；遇到未完成记录返回EXECUTION_UNKNOWN，不再次领取或执行。领取响应丢失时不执行；计算成功但回执失败时保留结果、receiptConfirmed=false。没有自动重试或重新领取路径。

撤销由在线平台通道校验，新工具不能领取许可。Runner内存中的旧令牌不会被自动抹除，已缓存结果和本地状态读取仍依赖本地认证；本轮不宣称已撤销所有本地读取能力。

/eap/state的tools字段只显示enabled、executed、failed、unknown统计；这是本地执行记录计数，不是对外部副作用的独立证明。计算完成前后发生进程/存储故障可能留下未知状态，本轮不声称分布式exactly-once。

## 远程测试和构建

在PoC节点执行，不在本机安装依赖或构建：

```bash
GO111MODULE=off /usr/local/go/bin/go test -count=1 -race -v .
CGO_ENABLED=0 GO111MODULE=off /usr/local/go/bin/go build -trimpath -o runner .
```

必须编译整个目录，不能使用只列少量文件的旧构建步骤。2026-10-02在远程独立Pod配置C编译器运行-race：13项顶层/18含子用例通过；节点本身仍没有gcc。K8Substrate旧actor-tools/build.sh是历史两文件验收脚本，不应用来构建当前Runner。


## 无状态提示词对照实验（2026-10-02）

GET /eap/experiments/profile和POST /eap/experiments/run需要现有任务认证；协议EAP_STATELESS_PROMPT_V1。模型通过现有受管网关，温度0、空会话、工具禁用；不读取/修改聊天历史。受控任务工具与实验入口相互独立，实验不提供工具定义。显式关闭思考时，该设置计入 profile 摘要并实际传给模型；默认设置保留旧 profile 摘要。EAP_EXPERIMENT_JOURNAL_DIR默认/data/eap-experiments，需实例独享、持久且不可回滚的目录；只能供一个Runner进程使用。

输出上限1–256且不超过AGENT_MAX_TOKENS。稳定taskKey、Trace、profileDigest、候选提示词和输入被一起指纹化，先持久UNKNOWN再发模型请求。相同键重放原结果，变参409；意图未完成时不再次执行。最多256条日志，不自动回收。未知/失败回执不代表上游没有花费；删除日志、克隆/回滚快照或多进程共享不在保证范围内。日志0600、目录0700，但不是加密存储。

当前平台只对固定版本的平台托管参考Runner开放；新默认镜像不会覆盖已发布模板。Agent43两版实际6次模型调用、配置式v2额外0次均已验收，两实例最后休眠。具体权限、限额、评分及未完成项见[实验合同](../../docs/AGENT_PROMPT_EXPERIMENTS.md)。
