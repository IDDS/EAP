# EAP Agent Runner

平台通用 Agent（控制台“零代码配置”部署方式使用的镜像），运行在 Agent Substrate actor 中。见 `docs/SUBSTRATE_RUNTIME_INTEGRATION.md` 第 6.5 节。

- 行为由环境变量决定：`AGENT_SYSTEM_PROMPT`、`AGENT_MODEL`、`AGENT_MAX_TOKENS`。
- 模型调用只经 EAP 治理网关（`EAP_GATEWAY_URL` + `EAP_GATEWAY_TOKEN`），不持有模型供应商密钥。
- 接口：`POST /eap/task`（EAP_HTTP_TASK_V1）、`POST /chat`（控制台试运行）、`GET /eap/state`、`GET /readyz`；除 `/readyz` 外均校验 `EAP_TASK_TOKEN`。
- 每次任务或对话上报一个 AGENT Span，并与网关的模型调用共用 trace ID。
- 会话历史只在进程内存中；Substrate 挂起/恢复时随快照保留。

构建（PoC 集群上，推到集群本地仓库）：

```bash
KO_DOCKER_REPO=localhost:5001 ko build --bare=false -B .
```
