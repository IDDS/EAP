# 受控框架样例

在智能体详情的“部署与运行”中，选择“受控框架样例”，可发布 LangGraph 1.2.17 或 AutoGen Core 0.7.5 的固定求和程序。平台负责镜像与启动配置；用户选择样例和实例规格。两个样例均执行“协调者 → 校验 Agent → 临时 coding Agent”，模型调用为零。

## 管理员配置

后端配置 `eap.substrate.framework-sample-image`，对应环境变量 `EAP_SUBSTRATE_FRAMEWORK_SAMPLE_IMAGE`，默认空值。配置必须是已构建、验收的框架 Runner 镜像及 `@sha256:` 固定摘要，不能使用可变标签。镜像应同时包含两个固定入口及锁定的 Python 依赖，构建要求见 [Runner 说明](../runtime/framework-runner/README.md)。

目录还检查 Substrate 启用、实例身份配送、任务派发及其目标主机、网关地址及其目标主机。未满足条件时，`GET /api/agents/substrate/capabilities` 的 `frameworkSamples` 返回 `available:false` 和原因；界面禁用发布并显示原因。配置有效只表示具备部署前提，不能替代镜像运行验收。

## 请求契约

`POST /api/agents/{agentId}/substrate/deployments` 示例：

```json
{
  "sourceType": "TEMPLATE",
  "runnerFramework": "LANGGRAPH_SUM_V1",
  "sandboxClass": "GVISOR",
  "size": "MEDIUM",
  "env": {},
  "egressHosts": [],
  "requestKey": "example-langgraph-deployment-v1"
}
```

另一个模板键为 `AUTOGEN_SUM_V1`。这是 `TEMPLATE` 的样例键，不能作为 `RUNNER` 框架值使用。`RUNNER` 仍仅支持 `EAP_NATIVE` 与 `KAGENT_ADK`。

服务端固定镜像、启动命令、端口 80、`/readyz` 和 gVisor；拒绝自定义镜像、命令、端口、健康路径、提示词、模型参数、源码地址、非空环境变量和出站列表。前端切换部署类型时不会把其他模式的草稿字段带入模板请求，后端独立校验。平台按现有契约注入实例身份与网关配置。

请求键遵循既有部署幂等契约：相同智能体、相同键及配置返回原部署；相同键改选样例返回冲突。已经接受的部署保留其原镜像，即使管理员随后修改或清空样例镜像配置。新部署才解析当前配置。不要为未确认的提交生成新键来绕过原意图。

权限继续以 `PlatformPermission` 为唯一来源，界面使用 `/api/platform/me` 的 `deployAgents`；目录与历史可读不意味着可发布。模板没有额外角色授权逻辑。

## 从部署到观测

1. 发布样例，等待原部署就绪及实例身份配送完成。
2. 为实例允许 `calculate_sum`，设置工具额度；Agent 的模型调用和输出 Token 预算可设为零。
3. 在工作流 Agent 节点选择该智能体，配置受控求和工具和数字列表，例如 `[10,20,12]`。实例行提示通过工作流执行，不显示聊天试运行。
4. 执行后检查结果 42，并在“运行与观测 → 执行关系”查询该智能体，查看同进程逻辑执行、工具 Trace 和临时代码摘要。
5. 任务终态后通过 EAP 显式 `PAUSE`，确认实例休眠；结束样例时关闭工具策略、撤销连接并暂停 Agent。

当前 PoC 有历史未决占用，验收工作流使用 `suspendAfterTask:false`，由 EAP 显式收尾。不能解除旧 UNKNOWN 或重复派发来清理状态；本功能不保证自动回收。

## 支持范围

样例仅接受受限求和输入，不接受任意源码、模型生成代码、外部业务 MCP 或自定义工具。当前源码按数字数量生成求和代码，再交给同进程临时子 Agent 执行；文件删除后保留事件摘要。新版成功任务产生 12 条事件，包括 4 次逻辑 Agent 执行、1 次工具调用和生成者/执行者的 2 条代码登记。已部署旧镜像仍产生 9 条事件；实际发布状态见[验证记录](VALIDATION.md)，实现边界见[短生命周期追踪](EPHEMERAL_EXECUTION_OBSERVABILITY.md)。

新版模板 Runner 增加[原身份绑定的有界后台补发](EXECUTION_BACKGROUND_DELIVERY.md)，普通 SDK 和旧日志不自动迁移。业务 Worker 在投递结束后报告一次[集中状态快照](EXECUTION_DELIVERY_STATUS.md)，后台可继续补原事件或报告。任务取消/恢复、跨实例子 Agent、AutoGen AgentChat/分布式和 LangGraph 异步中断仍未开放。具体实测范围见[验证记录](VALIDATION.md)，整体支持矩阵见[框架支持](FRAMEWORK_SUPPORT.md)。
