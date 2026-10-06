# Substrate 运行监控

2026-09-28 已部署至 PoC。入口：运行与观测 → Substrate 监控（/substrate-monitor）。展示真实 Worker 容量、恢复/快照/路由 P95、内存与 CPU 趋势、崩溃/调度异常和部署版本；实例抽屉读取 Control API 实时状态，不唤醒 actor。

## 配置与接口

设置 `EAP_SUBSTRATE_MONITORING_PROMETHEUS_URL`；PoC 为 `http://prometheus.otel-system.svc:9090`，留空禁用指标查询。后端只允许 http/https，不接受浏览器提供的 PromQL 或数据源地址。GET `/api/substrate/monitoring?minutes=60` 使用 VIEW_CONSOLE，时间范围仅 15/60/360/1440 分钟。

固定查询按 backend=RUNTIME / AX 分别限定 deployAtespace（PoC eap-agents）或 AX atespace（PoC eap-ax）；不接受任意命名空间。每后端/窗口独立缓存 15 秒，前端可见时每 30 秒刷新；连接 1 秒、读取 2.5 秒超时，响应 512KB、200 序列、130 点上限。版本最多 100 条，超出返回截断标记，前端每页 6 条。

## 数据语义

Worker gauge 按池/状态取 max 去除 API 副本重复，再按状态汇总。内存、CPU、采样数量为模板聚合，不是单 actor 统计；休眠实例通常无资源样本。部署 READY 与实例 RUNNING 分开显示。

数据源状态 CONNECTED/PARTIAL/UNAVAILABLE/DISABLED；指标 OK/EMPTY/ERROR。空样本、NaN/Infinity 显示空缺，图表保持断线；最近值缺失不沿用历史值。刷新失败清除过期图表，切换窗口立即清空旧窗口数据。路由 P95 不是模型执行时长。

## 验收及边界

远程后端 279/279、前端 99/99 与构建通过。四角色真实 PKCE、指标/图表、实例下钻与只读检查通过；viewer 另验 390 像素布局和浏览器模拟 HTTP 503。未对真实 Prometheus 注入故障。修复抽屉顶栏遮挡后复验四角色 PASS；临时浏览器 Pod 已删除。

证据位于 K8Substrate `artifacts/eap-monitor-20260927/`（含截图、API、浏览器和测试报告）。未接入历史日志检索、基础设施 Trace 自动关联、自动告警或容量操作；这些不能算作已完成。AX 已接入；监控页可切换 AX 池，并展示最近 100 个任务的业务状态、沙箱状态与工作流归属，链接至受权限控制的任务详情。
