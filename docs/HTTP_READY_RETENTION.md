# HTTP READY 启动计时的失败留存

2026-10-22。本轮修复固定 HTTP 合成夹具在 READY 后失败时丢失启动计时的问题。它补齐诊断证据，不解决慢启动本身。正常Agent92成功、固定故障Agent93在20秒截止后仍保留READY计时，四角色API核对通过。真实验收结果以[本轮证据](evidence/http-ready-retention-20261022/README.md)为准。

## 原因与行为

Agent90 在 READY 等待 18,801ms 后于 READ_PROOF 触及 20 秒截止。原观察器只在最终成功摘要里写入 `httpStartup`，Go 在失败时不返回成功输出，因而无法知道该次启动内部各段耗时。历史数据无法补造。

观察器现在在 READY 消息里携带已完成的计时。Go 接收到完整、合法的 READY 后，立即将其放入 `diagnostic.startup`，随后发生控制写入失败、证明超时、异常退出、错误证明或导出未确认，也保留同一份数据。最终回执仍为原任务，UNKNOWN 保持，不增加重试。

父 Agent Span 同步提供 `eap.runtime.startup.version=1` 和下表中的十项整数毫秒属性。后端 `TraceMetadata` 写入与历史读取使用同一白名单。没有新增 Span、Schema、权限规则或前端界面；四角色通过现有 Trace API 读取。

| READY / 回执字段 | Span 后缀（前缀为 `eap.runtime.startup.`） |
| --- | --- |
| observerImportMs | observer_import_ms |
| identityReadyMs | identity_ready_ms |
| exporterStartMs | exporter_start_ms |
| serverSpawnMs | server_spawn_ms |
| serverReadyWaitMs | server_ready_wait_ms |
| observerBeforeReadyMs | observer_before_ready_ms |
| serverImportMs | server_import_ms |
| serverBindMs | server_bind_ms |
| serverFqdnMs | server_fqdn_ms |
| serverBeforeReadyMs | server_before_ready_ms |

## 数据边界

READY 仍受 4KiB 行长限制。固定字段、完整集合、版本、整数 0–60,000 及包含关系均须合法；拒绝重复键、额外字段、大小写别名、尾随 JSON、null、布尔、分数及越界数据。旧版 READY 省略 startup 仍可接受，缺失表示未知，不补零。非法或未完整收到的 READY 不输出启动细分。

后端仅允许固定的十个毫秒字段和文本版本 `1`；不会保存命令、错误正文、环境、凭据或任意子进程属性。字段是已完成阶段的墙钟计时，包含调度与 I/O 等等待；重叠区间不可相加，也不能解释为 CPU 时间。

原 20 秒任务截止、5 秒服务 READY 限制、身份配送与原任务回执复用保持。专用正常镜像继承已验证的缓存层、固定端口和 3 秒控制后等待；故障验收镜像只将这一固定等待改为 30 秒，触发原截止。故障不由任务参数选择，两种镜像均不推广为默认 Runner。

## 验证范围

测试及构建全部在远程 PoC 集群进行。旧版回归须在 READY 后超时保留测试上失败，后端旧投影须丢失新属性；新版验证失败路径、成功摘要一致、原 UNKNOWN 回执持久化、畸形输入和既有父子 Span 关系。

初次扩大 Go 测试选择，将普通模型用例放入禁止模型的 HTTP 夹具构建，产生 `MODEL_NOT_DISPATCHED` 失败。失败记录保留；在原版夹具重现配置不兼容后，改为 HTTP/stdio 的完整 MCP 与身份用例，普通模型任务在原生 Runner 完整竞态套件验证。未将这次错误记录为全通过。

真实验收使用 EAP 新建、部署、调度和观测两个独立样例，各只执行一次；随后通过 EAP 休眠、撤销连接。不得唤醒历史 Agent87/88/89/90/91，不得重派旧任务补证。普通容器或新样例成功不能证明历史慢启动已经解决。

## 后续定位慢启动

下一批独立实验应在固定镜像与资源配置下，将原任务时间窗口与 Worker CPU 使用、节流、节点 I/O 和镜像读取证据关联。先只读盘点可取得的指标，区分缺失与零值，再设计有界的新样本；不扩大任务截止、不放宽网络、不改写旧 UNKNOWN。READY 已收到的分段可用于缩小范围，READY 之前被终止仍可能没有内部计时。

缓存层继承并不证明新实例的缓存命中率。首个成功样例及故障注入各一次也不是性能基准，不能据此宣称稳定提速、gVisor 根因或通用多智能体无侵入观测已经完成。
