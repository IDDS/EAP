# 固定 HTTP 夹具的启动分段计时

2026-10-22。专用 HTTP 合成夹具在成功任务的 `output.observation.httpStartup` 中提供固定毫秒计时。它用于解释 READY 等待；不是通用 Agent 性能监控，也不新增 Span 或改变任务结果。

## 本次结论

EAP 新样例 Agent89 / Workflow43 成功，READY 为 7,106ms。观察器导入 3,170ms、服务进程导入 3,203ms，两段不重叠，合计占 READY 约 90%。身份管道就绪 5ms，服务 `getfqdn` 调用 113ms。此次成功样本支持优先调查 Python 导入阶段；不能据此补写 Agent87 的历史失败原因。

同一专用镜像、同一工作节点、名义 500m CPU / 256Mi 内存的普通容器对照中，三次服务导入为 202/193/194ms，服务 READY 总耗时为 238/270/221ms。它们只测服务进程启动，无 HTTP、模型或真实凭据。三次顺序样本并未控制缓存、CPU 调度及隔离实现差异，不是性能基准，不能把差异全部归因于 gVisor。

## 字段与计时边界

`version` 固定为整数 1，其余字段是 0–60,000 的整数。Go 接收端只接受完整的固定字段集合；旧摘要无 `httpStartup` 时仍兼容。Python 服务端 READY 中的四个服务计时也按固定键、类型、范围和包含关系校验，不接收任意字符串或私有字段。

| 字段 | 边界 | 本次 gVisor 样例（ms） |
| --- | --- | ---: |
| observerImportMs | 观察器首次计时点之后的模块导入 | 3170 |
| identityReadyMs | 创建凭据管道读取器、等待及检查身份快照 | 5 |
| exporterStartMs | 创建异步导出器 | 0 |
| serverSpawnMs | 创建服务子进程 | 60 |
| serverReadyWaitMs | 等待服务 READY，包含解释器、导入和绑定等 | 3527 |
| observerBeforeReadyMs | 观察器首次计时点至输出 READY 前 | 6902 |
| serverImportMs | 服务首次计时点之后的模块导入 | 3203 |
| serverBindMs | 原 ThreadingHTTPServer 构造函数 | 114 |
| serverFqdnMs | 构造期间原 socket.getfqdn 调用 | 113 |
| serverBeforeReadyMs | 服务首次计时点至输出 READY 前 | 3318 |

这些区间有包含关系，不能全部相加。`serverBindMs` 包含 `serverFqdnMs`，`serverReadyWaitMs` 包含服务启动各阶段。`getfqdn` 时间包括名称解析调用，不证明发生了实际 DNS 网络请求。

首次计时点在 `import time` 之后。外层 READY 7,106ms 与观察器内部 6,902ms 的差额为 204ms，包含未计入的解释器启动、调度和管道传递等，**不是精确的解释器耗时**。导入计时是墙钟时间，同样包含调度及 I/O 等等待，不是纯 Python 执行 CPU 时间。

## 行为和数据边界

- 保留 20 秒任务截止、5 秒服务 READY 等待、同 taskKey 回执复用及 UNKNOWN 不重放规则。
- 专用镜像保留既有 28761 端口和控制后 3 秒等待；后者属于 READ_PROOF，不属于 READY。
- 计时包装只在独立服务进程、启动服务线程前调用原构造函数；`socket.getfqdn` 无论成功或异常均恢复。没有跳过 DNS、改绑定语义、延长截止或开放网络。
- 不输出命令参数、错误正文、环境、凭据或业务内容。不改变协议成功/失败/UNKNOWN、效果 UNVERIFIED 分类。
- 新细分字段随成功任务输出保存；原有父 Span 固定阶段诊断保持。子进程失败、截止或最终导出失败时，**不保证保留本次细分计时**；不能把缺失解释为零。
- 没有新增前端细分计时界面、后端权限、数据库迁移或默认 Runner 发布。新服务和观察器成对打入专用镜像，旧镜像不改写。

## 验证与后续

远程 Python 63 项及镜像变体 5 项通过；HTTP、原生、stdio 的相关 Go 竞态测试和 HTTP vet 通过。旧版的真实服务 READY 缺少 startup 字段，对照按预期失败；新版测试注入解析延迟，校验归因、异常后恢复、字段范围和私有字段拒绝。真实 EAP 样例只执行一次：8 合成 HTTP 请求、6 MCP Span/工具记录、1 Agent Span、模型 0，四角色观测一致，随后显式休眠和撤销连接。

下一轮先给导入路径做固定模块或系统调用级的有界剖析，再设计缓存/预编译等单变量实验。需测冷启动、取消与隐私边界，不能仅靠普通容器对照宣称沙箱根因或优化收益。禁止唤醒 Agent87/88/89 或重派原任务补证。

[本轮证据](evidence/http-startup-timing-20261022/README.md)。

## 2026-10-22 缓存实验后续

已增加构建期缓存工具并验证普通容器中的两入口源码编译降为0。新Agent90原镜像对照在READY等待18801ms后，于READ_PROOF触及20秒截止；候选Agent91成功但两段导入仍合计8.316秒。不能宣称超时已修复；失败路径细分计时留存仍是下一优先项。[缓存实验](HTTP_BYTECODE_CACHE.md)、[原始证据](evidence/http-bytecode-20261022/README.md)。


## 2026-10-22 READY 失败留存后续

[READY 留存修复](HTTP_READY_RETENTION.md)已完成：新增Agent92正常样例和Agent93固定超时样例，失败路径仍保留原READY细分，并进入原父Span的白名单属性。原20秒截止和UNKNOWN保持；历史Agent87/90没有回填、重派或改判，慢启动根因尚未解决。[验收证据](evidence/http-ready-retention-20261022/README.md)。
