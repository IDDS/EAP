# 通信内容采集排查与修复（2026-09-08）

后续进展：当日晚 0.2.1.0 已补齐派生 MODEL 时间线和自动调用状态评估，详见 [通信自动分析](CONTENT_ANALYSIS.md)。本文保留前一阶段排查时的事实与边界。

已确认内容通道可用，并修复 LiteLLM 输入回调遗漏及离线导入问题。Discovery Agent 1.5.1／注入器 1.2.2 已应用到腾讯云 HelloBoss，EAP 核心仍为 0.2.0.0。代码提交 `6db452b` 已推送当前 Gitee 分支。

## 排查事实

- 排查前有 33 条内容记录，全部归属 Agent 4（HelloBoss）：输入 1、输出 32；24 条 content_json 非空，最新 observedAt 为 2026-08-30 12:59（北京时间）。不能将这些记录数当作完整对话数。
- 当前探针 ONLINE，内容权限有效（NONE 模式），内容代理 active 且 health=UP/authorized=true。排查时近 24 小时业务容器日志仅 3 行，没有模型调用相关日志；这支持“近期流量很少”的判断，但不能仅凭日志证明所有调用都不存在。
- 已安装 LiteLLM 1.80.0 的实际调用代码使用同步 `log_pre_api_call`；原注入器只实现异步输入方法，导致正常输入回调被基类空实现吞掉。
- 原 sitecustomize loader 包装丢失 importlib 资源读取接口。已复现网络读取模型价格表超时后，本地资源回退报 `Can't open orphan path`；修复后离线导入成功。

## 修复与验证

- 同时实现同步、异步输入/成功/失败回调，复用相同逻辑及方向观测键。
- 包加载器转发原 loader API，保留 package resources 能力。
- 采集失败日志仅记录异常类型，不记录正文、凭证、地址或原始异常文本；失败仍不阻断模型业务调用。
- 本地 Python 4 项、HelloBoss 相同 LiteLLM 环境 Python 4 项通过；服务器既有 golang:1.23 容器内 `go test ./...` 全部通过。
- 备份 `/var/backups/eap-capture-20260908T130342Z` 保留旧探针、注入文件与 Compose 覆盖配置。
- 通过已有中央授权及自动挂接机制应用修复；HelloBoss 后端于 21:03 左右短暂重建，数据库/前端未重启。随后 HelloBoss OpenAPI 和 EAP health 均 HTTP 200，探针、定时器、代理 active。
- 再次协调显示 targets_applied=0、targets_already_attached=1，容器启动时间不变；默认挂载的回调注册且 ready=true，无需临时 PYTHONPATH 即可离线导入。

## 无敏感内容的真实调用

在 HelloBoss 容器的独立 Python 验证进程中，使用相同依赖及已配置 DeepSeek 凭证发起一次真实模型调用；测试时用临时目录加载修复后的注入器，验证通过后才部署相同代码。未执行普通聊天会话、业务工具或用户任务。

- Trace ID：`eap-capture-check-82cb93247f4248fe9b1cfaab036da32b`。
- 提示：`This is a non-sensitive EAP connectivity check. Reply exactly EAP_CAPTURE_OK.`
- 实际输出：`EAP`；API 请求成功，但未满足完整输出要求。
- 用量：输入 102、输出 24 Token，其中 reasoning_tokens=21。输出预算为 24；预算消耗是未完整输出的可能原因，未据此推断所有业务请求也有此问题。
- 代理返回输入/输出各一次 202；随后服务端实际落库 id=34/35，agentId=4、相同 Trace ID，脱敏正文分别包含测试提示和 `EAP`。不是仅凭 202 声称采集完成。
- 显式写入一次 ACTION_COMPLETION 规则评估，结果 id=1、score=0、passed=false、status=COMPLETED，原因“任务未完成”。此处 COMPLETED 指评估已完成，不代表测试任务成功。

## 当前边界

- 已证明：真实模型调用 → 输入/输出采集 → 脱敏存储 → Trace ID 关联 → 显式规则评估。
- 本次 Trace ID 的 trace_span_observations 数量为 0：内容代理带关联标识，但未生成完整 Trace Span 时间线。不能将它称为自动完成的全链路 Trace。
- 评估使用测试进程确认的实际完成事实显式提交；不是已有后台模型自动理解正文，也不是通用 LLM Judge。
- 本次查询只读取了固定测试的正文，没有读取历史业务正文。线上 DEVELOPMENT/HTTP 接口仍剥离 contentJson，未放开公开正文访问，也未改变采集授权模式。
- 下一步为内容代理补充 Trace Span 投影、自动评估触发及正文语义分析；这些是尚未实现的能力，需独立设计验证。常规业务新请求是否持续上报需在真实业务使用中继续观察。
