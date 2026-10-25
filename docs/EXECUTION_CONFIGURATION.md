# 执行配置溯源

任务工作台按原 workflowId/runKey 展示两种不同时间点的记录：提交时保存的节点配置版本，以及派发前选中的运行实例和部署关系。它不改变运行实例选择策略，不等于提交时固定全部运行版本，也不是对 Actor 内部实际加载内容的证明。

## 记录与读取

- 新 Runtime 步骤在写入 DISPATCHING 之前生成 `DISPATCH_BINDING_V1`，与原步骤一起持久化，之后的业务回执、未知结果和回收状态更新保留同一记录。
- 原平台创建意图必须同时匹配 Agent、实例、Actor UID、部署编号、atespace 和 ActorTemplate，才返回部署版本；不会按模板名称推算版本或读取最新部署补齐。
- 保存实例协议、绑定 UID/模板、原部署编号/版本/来源和不可变镜像 SHA-256 摘要。可变镜像标签不作为摘要，不返回镜像仓库地址、提示词、环境变量、任务上下文或结果正文。
- GET `/api/workflows/{workflowId}/executions/{runKey}/configuration` 只读取原执行的 snapshot_json/steps_json，返回 `EXECUTION_CONFIGURATION_V1`。读取不查询当前模板或实例，不写回、派发、回收或操作预算。
- 节点版本是工作流节点的配置版本，不是部署版本。返回所选路径的顺序、节点类型、Agent、执行方式与是否记录运行绑定；保留缺失版本。
- 校验步骤顺序、nodeKey、Agent、原 taskKey 和实例归属；无法核对时返回错误，不用当前配置或其他成功执行代替。

## 状态和权限

`DEPLOYMENT_RECORDED` 为原部署关系已记录；`RUNTIME_RECORDED` 仅有派发时实例，部署关系未确认；`NOT_RECORDED` 表示缺少该记录，可能尚未派发或属于旧执行；`AX_NOT_CAPTURED` 表示本期不采集 AX 沙箱版本；`NOT_APPLICABLE` 为控制节点。

是否已派发、是否成功仍以原步骤回执为准。记录绑定不能单独证明网络请求已发出或任务成功；UNKNOWN 保持未知，不触发重试或释放预占。

接口沿用 `PlatformPermission.VIEW_CONSOLE`；前端只按 `/me.viewConsole` 显示元数据，正文仍走原 `viewContent` 权限。切换任务、账号、权限或卸载会清除记录并拒绝迟到响应；刷新失败清除旧展示，重试使用同一原编号。无浏览器持久缓存。

## 本期边界

不改数据库 Schema、依赖、Runner 或权限定义。JSON 步骤增加可空字段，旧步骤可以继续读取，但不回填历史。可复用行为模板、完整运行策略配置、提交时固定执行版本、AX 沙箱版本、结构化结果契约、审批续跑和检查点仍需后续实现。没有安装 Kagent，也没有验证 Kagent 运行时兼容性。
