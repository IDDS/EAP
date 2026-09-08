# 通信自动分析

EAP 0.2.1.0 将已经采集的模型输出自动投影为 MODEL 时间线记录，并在有明确调用终态时执行 `MODEL_CALL_STATUS` 规则。分析在 EAP 内运行，不调用外部模型，不把调用成功解释为业务任务完成。

## 数据与证据

- 数据源为 `business_content_records` 的 OUTPUT；INPUT 不生成独立模型调用，避免双计数。
- 保留原 Trace ID，派生 Span ID 为 `eap-content-<record id>`，名称标明“内容采集投影”。该 Span 不代表完整 SDK Trace，也不推断父子调用关系。
- 时间取输出观测时间，耗时取上报 latencyMs（非负，最多一天）；没有耗时时为 0，`eap.duration.reported` 标记是否提供整型耗时。不能据此推断完整业务耗时。
- 仅复制白名单内的调用终态、HTTP 状态和整型 Token 用量；不复制正文、原始错误信息及任意自定义 metadata。既有正文读取权限不变。
- `FAILED` 回调或 HTTP 400–599 判为调用失败，优先于成功信号；`SUCCEEDED` 回调或 HTTP 200–299 判为调用成功。没有这些信号时保留 UNKNOWN Span，不生成评分。Token 用量本身不是成功证据。
- 每个确定终态产生一份 v1 规则结果，明确说明“业务任务完成度和回答质量未知”。已有 Trace 任务完成度同步遇到含内容投影的 Trace，必须有显式任务完成信号才评分。

## 运行与恢复

V43 增加持久化处理状态并注册第 9 个评估器。默认启动 15 秒后开始，每 5 秒最多处理 50 条 OUTPUT；历史待处理输出也会补时间线。

| 配置 | 默认值 | 作用 |
| --- | --- | --- |
| `EAP_CONTENT_ANALYSIS_ENABLED` | `true` | 开关元数据自动分析，关闭后不影响内容采集 |
| `EAP_CONTENT_ANALYSIS_INITIAL_DELAY_MS` | `15000` | 启动延迟 |
| `EAP_CONTENT_ANALYSIS_INTERVAL_MS` | `5000` | 批次间隔 |

每条记录在独立事务内加行锁，Span、评估和 DONE 状态原子提交；事务失败不影响先前已落库的内容。最多尝试 3 次，随后标记 FAILED，日志与记录只保存异常类型。`SKIPPED/MISSING_TRACE_ID` 表示缺少关联标识；`DONE/UNKNOWN_OUTCOME_NO_SCORE` 表示已有时间线但无终态评分。

`GET /api/observability/content-analysis` 返回开关和 OUTPUT 状态计数；评估页显示积压、已生成时间线、缺少标识及失败数。该统计按内容记录计算，不等于任务数或完整会话数。刷新页面可取得最新状态。

管理员确认并修复失败原因后，可定向重置对应记录的 projection_status=PENDING、projection_attempts=0、projection_error=NULL；必须限定已核实的 FAILED 记录 ID，不应重置已完成记录。已完成记录重新处理会与现有 Span 身份冲突。

## 验证与边界

后端 192 项测试、前端 36 项测试通过。新增集成测试覆盖并发幂等、错误 HTTP 优先级、未知或无效元数据、无 Trace、INPUT 排除、事务回滚、敏感信息隔离和三次重试；另有混合 Trace 不误判任务完成的回归测试。V1–V43 在 H2 MySQL 模式完整迁移通过。最终补充原 Span ID 关联匹配后，重跑 5 项内容集成测试以及前端全量测试/构建，确保旧备用模型记录不会与派生 Span 重复展示。

这一步实现模型调用的结构化分析。多智能体 A2A 消息、工具调用父子关系需要对应协议或 SDK 上报；回答质量、指令遵循和任务语义完成度尚未接入通用语义评估。已有固定测试的 ACTION_COMPLETION 结果可与自动调用状态并列展示：调用成功与测试任务失败可以同时成立。

## 腾讯云验收（2026-09-08 22:57–23:00，北京时间）

- 0.2.1.0 / `b79b7fd` 已推送 Gitee `feat/galileo-evaluation-foundation`，EAP 前后端镜像均为 `b79b7fd`，未合并 main。
- 备份 `/home/ubuntu/eap-backups/20260908T145015Z` 保留数据库、旧产物和回滚镜像；数据库压缩备份验证通过。发布目录 `/home/ubuntu/eap-releases/b79b7fd`。
- 后端 JAR SHA-256：`620f0e8e60bbf58a620d58dcf2e4f541750e4b44c257c4f80de3700844695b2a`，替换前已校验；发布清单记录代码、版本、测试及 schema。
- 目标 MySQL V43 success=1，失败迁移 0，health=UP。仅更新 EAP 前后端；HelloBoss 后端启动时间仍为 `2026-09-08T13:03:49.322230645Z`，探针未改动。
- 自动处理从 PENDING=33 变为 DONE=33，失败与重试次数均 0；自动生成 33 份模型调用状态评估。此数是历史输出记录数，不是完整会话数。
- 固定 Trace `eap-capture-check-82cb93247f4248fe9b1cfaab036da32b` 现有 1 个派生 MODEL Span（id=33，spanId=eap-content-35，耗时 1593 ms），与 33 条批量处理完成后的数据库查询一致。
- 固定测试保留原 ACTION_COMPLETION id=1（score=0），自动新增 MODEL_CALL_STATUS id=34（score=1，taskCompletion=UNKNOWN）。没有把调用成功覆盖为业务任务成功。
- 浏览器已验证自动分析计数、评估详情关联 Span，以及“打开 Trace 工作台”钻取；固定 Trace 只显示一个模型节点，页面无控制台错误。
- 固定测试的 2 条输入/输出通过公共 API 查询时 contentJson 均被隐藏。未读取历史业务正文，未新增付费模型调用，未新增演示采集数据。
