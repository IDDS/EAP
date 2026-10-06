# AX 任务执行集成

2026-09-28：用户明确要求接入 AX。任务入口已完成远程部署、真实任务和四角色浏览器验收。

## 当前合同

EAP `/ax-tasks` → 持久化任务/治理预检 → AX gRPC Task → Substrate 隔离 Actor → EAP AX Runner → EAP 模型网关。AX 的 Ready/Running 只表示沙箱就绪；SUCCEEDED/FAILED 必须来自匹配 taskKey 的 Runner 回执。业务状态和沙箱状态独立显示。

- `POST /api/ax/tasks`：agentId、requestKey、objective、mode（LLM/ECHO/FAIL）。LLM 使用固定 deepseek-flash；ECHO/FAIL 是明确标注的合成回执验证，不调用模型。
- `GET /api/ax/tasks` / `/{taskKey}`：仅元数据。最近 100 条任务。
- `GET /api/ax/tasks/{taskKey}/content`：受 VIEW_CONTENT 保护的输入和原始回执。前端以纯文本显示，关闭详情/权限变化后清除。
- `POST /api/ax/tasks/{taskKey}/suspend|resume`：仅已确认成功/失败的任务可操作沙箱。EAP 将操作交给 AX；不由两个控制器共同控制 AX Actor 生命周期。
- 元数据沿用 VIEW_CONSOLE；提交 RUN_WORKFLOWS；沙箱操作 OPERATE_INSTANCES。所有权限来自 PlatformPermission。

## 幂等和故障语义

V50 保存 taskKey、用户/Agent/requestKey 的唯一映射、稳定参数摘要、派发意图、回执和租约。同键同参复用，改变参数返回 409。Agent 行锁串行化并发提交。业务内容不放在列表与审计元数据中。

Runner 先在 `/workspace/.eap/started` 写入并同步执行标记，再执行任务；回执经临时文件原子替换。恢复看到标记但没有结果时返回 EXECUTION_UNKNOWN，不自动再次调用模型。EAP 在 HTTP 派发前提交 RUNNING；失去响应/重启后只读取回执，不重新 POST。30 分钟期限进入 UNKNOWN，保留原任务并继续核对迟到回执；不自动新建替代任务。

正常终态后通过 AX 休眠；EAP 额外读取 Substrate 物理状态，不能只把 SuspendTask 的 ACK 当成已休眠。模型调用带 Agent ID、Trace ID，OTLP Span 带 taskKey。Agent 出站只放行 EAP 网关主机。

## 固定版本与部署

AX 上游固定 `d0bc38bcf90bb2ad9c012ff1be9d68ff05347ba9`。与当前集群 Substrate `d277088bc1d081ef716d81dd7986d05d0a36ad3a` 对齐编译；补丁在 K8Substrate `tools/eap/ax/prepare-upstream.py`：

1. ContainerReadyz 对齐当前 ContainerWakeupProbe。
2. 指定 `workload=eap-ax` 独立池。
3. 禁止崩溃 Actor 自动删除重建。
4. 自定义模板创建失败时禁止回退到其他默认模板。

`ax-system`：AX server/controller + Redis AOF PVC。`eap-ax`：1 个专用 Worker；不改既有 eap-agents/demo 池。AX API 无上游用户认证，本 PoC 以 NetworkPolicy 限制仅 EAP backend 访问；Redis 仅 server/controller 访问。controller 无集群 Secret 读取权限。Runner 不持有模型供应商 API key。

通过 `tools/eap/ax/build.sh`、`deploy.py`、`enable.py` 在远程构建部署。启用参数为 EAP_AX_ENABLED、EAP_AX_TARGET、EAP_AX_ATESPACE、EAP_AX_RUNNER_IMAGE（必须 digest）。编译、测试、浏览器、录制都只在远程集群。

## 明确边界

- 已支持 Workflow/Team/Plan 串行混合编排，任务节点通过 executionBackend=AX 选择 AX；保留独立任务入口与原生 Runtime。详见 AX_WORKFLOW_INTEGRATION.md。
- 使用自定义异步 HTTP Runner；AX 的任意 shell 命令、Git/MCP/Skill Workspace、debug/SSH、Model 资源配置未开放到 EAP。当前工作区用于持久化执行标记与回执。
- 使用轮询，不声称已验证 WatchTask 断线重连。AX 上游 API 仍在变动。
- 不保证外部系统 exactly-once；不提供 UNKNOWN 自动重试、取消/补偿或生产吞吐保证。
- 网关/任务机器令牌与既有 PoC 一样由控制面注入环境，运行时配置在受限集群内存储；未完成生产身份绑定和每任务凭证隔离。不得导出含环境的 AX Task/ActorTemplate 原文。
- Redis 已有 AOF/PVC，但未做节点故障、灾备恢复和生产 TLS/身份认证专项。

## 验收

远程后端 **288/288**、前端 **107/107** 与类型检查/构建、Runner **4/4** 通过。Agent 13 的 ECHO、FAIL、LLM 真实任务与并发幂等、网关 Trace 归属、物理休眠、恢复后原回执不变均 PASS。四角色浏览器元数据/内容权限、内容清除、390px 手机布局均 PASS，无页面异常。远程脚本：`verify.py`、`browser.mjs` / `browser-run.py`。保留节点 `/root/eap-ax-evidence.json`，重复验收必须复用原 requestKey，不能删除证据后盲目重建任务。


### 验收中修复的身份与界面问题

- 发现 Keycloak 客户端只配置 profile，实际 access token 没有 sub，先前调用方降级为 console。Keycloak 25+ 的 sub 由 basic scope 的 mapper 提供，见 [官方升级说明](https://www.keycloak.org/docs/26.7.2/upgrading/)。已修正仓库导入清单、现有 realm 和 ConfigMap，不重启或轮换凭证。
- 后端增加真实 JWT sub 非空校验，并用临时 RSA 签名测试缺失/空白主体拒绝。新登录的四角色主体均存在，新一轮真实任务 requestedBy 等于登录主体；操作审计记录实际操作人，不覆盖原提交者。旧版无 sub 的令牌需重新登录。历史 console 记录不推断真实人员。
- 第一轮 3 个合成任务保留为 legacyTasks，身份修复后明确新增 3 个任务重新验证；两轮总计 2 次模型调用，其余为无模型回显/失败验证。没有操作旧 demo actor。
- AX 详情抽屉改为 append-to-body/z-index=4000，避免控制台布局遮挡关闭按钮；修复后四角色浏览器均通过。

最终运行镜像：backend `sha256:e5dc0c0fa606134e9df7025be4fc72c0427cdb63cb488fed1a4c3f10af09127b`；frontend `sha256:09eefa5d8615720751e4f9a9a337e000a13845432576c005e4c905f41866fe12`；AX Runner `sha256:7682089a63dffa4b5dad2c4e4f551618d68c30af39be1eb82c64158ce711ac85`。

证据：K8Substrate `artifacts/eap-ax-20260928/`。AX 上游 controller/runner 及当前 Substrate API 兼容测试通过。EAP 构建脚本改用 clean test，避免本地与节点时钟差导致 Maven 增量资源缓存未覆盖新迁移。全量测试仍有既有关闭上下文期间后台调度读空 H2 的日志噪音，JUnit 失败/错误/跳过均为 0，不声称日志完全无警告。

## 统一编排扩展（2026-09-28）

本轮 Workflow/Team/Plan 的串行混合执行与统一监控已部署并验收。后端296/296、计划专项9/9、前端111/111，真实 Runtime → AX → Runtime 三步计划及四角色 API/浏览器 PASS。原始独立任务合同继续有效，新增边界与使用方式见 AX_WORKFLOW_INTEGRATION.md。
