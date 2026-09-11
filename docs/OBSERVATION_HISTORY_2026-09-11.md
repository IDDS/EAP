# 物理观测与预检决策历史分页

新增服务端分页接口：

- `/api/telemetry/model-invocations/page`
- `/api/observability/content/page`
- `/api/observability/otlp/spans/page`
- `/api/observability/tool-invocations/page`
- `/api/governance/preflight-decisions/page`

统一page/size/keyword/from/to/order合同，按事件时间observedAt或决策时间decidedAt及id稳定排序，数据库计数。status分别对应模型/工具/Span状态、内容方向、预检审批状态。精确agentId、endpointKey、traceId筛选；模型和内容支持eventKey；预检通过数据库关联治理事件筛选，不依赖最近100条事件。缺少eventKey字段的Span/工具接口明确拒绝该参数。

内容分页保持原GET角色限制，开发模式继续剔除正文，单页不得超过配置的内容查看上限。JWT集成测试验证匿名401、VIEWER403、治理管理员200。未更改采集授权或审批写入语义。

前端四类物理明细和预检决策接入HistoryTable，页内Trace钻取和审批入口保留；审批后刷新，路由变动取消旧结果。Span历史表只列持久化记录；上方诊断图继续保留模型补充节点和500条观测范围说明，未把单页当作完整Trace。搜索只检索标识/名称等元数据，不扩展到内容正文。历史查询时间范围独立于上方摘要时间窗。

V47为内容与工具增加全局事件时间索引；其他现有时间/身份索引沿用。仍为offset分页，不保证实时写入期间跨页快照；大规模负载、关键词子串扫描和旧摘要计算仍需独立验证。

验证：216后端测试、62前端测试及构建通过。新增事务测试每类105条记录，覆盖五类查询跨页与总数、Agent/Trace/事件上下文、审批状态、业务发生时间、开发正文脱敏；测试数据只在本地事务中生成并回滚。

## 部署验收

前后端d887b9c已部署腾讯云，前端buildId 2026-09-11T10:10:53.638Z。后端UP；V47先在备份副本验证，再由正式Flyway迁移，失败记录0。备份路径 /home/ubuntu/eap-backups/observation-d887b9c-20260911T131147Z；旧3c4ae7e镜像及Compose保留。应用回退恢复该目录release配置并用原三份Compose启动后端/前端，保留增量索引，不覆盖数据库。

公网只读验收：模型38条、内容45条、Span38条、工具0条、决策0条；五类接口的分页、跨页不重复、Trace精确筛选和无效参数400通过。开发内容正文隐藏。浏览器物理明细每页10条，翻至第2页后Trace按钮正确带入上下文，390px无溢出；预检页显示新分页控件。线上没有执行真实审批操作；多页审批状态与角色权限通过本地测试验证，未向线上插入测试记录。既有Physical隐藏图表尺寸问题仍在待办中。
