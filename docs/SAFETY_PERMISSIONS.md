# 安全响应权限与控制台评估

JWT 模式下，安全记录读取使用 `PlatformPermission.VIEW_CONSOLE`，三个控制台写入口使用新增的 `PlatformPermission.MANAGE_SAFETY`。`/api/platform/me` 自动从同一枚举发布 `manageSafety` 能力；前端不解释角色名称或 JWT。

| 入口 | 方法 | 能力 |
| --- | --- | --- |
| `/api/safety/summary` | GET | `viewConsole` |
| `/api/safety/sessions` | GET | `viewConsole` |
| `/api/safety/sessions/{sessionKey}/events` | GET | `viewConsole` |
| `/api/safety/sessions/{sessionKey}/artifacts` | GET | `viewConsole` |
| `/api/safety/sessions` | POST | `manageSafety` |
| `/api/safety/events/evaluate` | POST | `manageSafety` |
| `/api/safety/artifacts` | POST | `manageSafety` |

`manageSafety` 允许治理管理员和平台管理员。查看者、操作员、数据所有者和无角色身份不允许上述写操作。未映射的 `/api/safety` 路径或方法默认拒绝，不再落入通用 authenticated 规则。DEVELOPMENT 模式仍沿用既有开发配置，不代表 JWT 鉴权。

运行时工具检查、工作流与部署流程直接调用安全服务，继续受各自入口的授权和运行时身份保护。本次没有把控制台角色检查放入共享服务方法，以免改变这些内部调用。

新写入的会话、事件和制品要求使用已登记的精确会话标识。数据库排序规则可能把大小写或重音不同的标识查成同一条记录，服务端会再次按原字符串核对；不一致时返回 400，不更名或写入。精确同名会话继续沿用既有的元数据更新行为。

制品登记还要求事件存在，且事件记录的会话标识与指定会话完全一致。缺失会话、缺失事件或跨会话关联均在写入前拒绝；合法制品保存已核对的会话标识和事件 ID。普通评估及运行时元数据评估也使用精确会话校验。没有新增数据库迁移，也不会自动修复历史错误关联。此约束不证明制品正文、URI、哈希或上报者身份可信。

控制台手动评估需要选择已登记安全会话，事件沿用该会话的 rootAgentId；未关联智能体的会话继续不关联。取消固定 Agent 3、固定演示会话以及评估前自动创建/覆盖会话。会话登记 API 保留，由 `manageSafety` 保护。页面清楚说明评估只记录本地策略判断，不执行目标操作；现有 sidecar 标志不等于已实现远程委托。

页面同时检查能力、当前身份、请求代次和忙碌状态。身份或能力变化时清空表单和旧数据；迟到读取/写回不能恢复旧内容或解锁新请求。读取失败清除旧记录，写回执丢失后保留提示并禁用再次提交，用户核对后可明确允许下一次评估。

本次范围不包括服务端幂等、跨硬刷新恢复、会话实体级权限、上报者身份认证、正文溯源、历史关联修复或 sidecar 实际委托。会话关联是记录元数据，不应被当作智能体身份的证明。

权限与页面改造的测试、原始失败及部署状态见 [权限修复证据](evidence/safety-permissions-20261017/README.md)。真实角色验收只发送空对象验证请求：低权限应在鉴权阶段返回 403，管理角色通过鉴权后由必填字段校验返回 400，不进入业务服务；成功写入路径在远程隔离测试中验证。

后续会话／事件／制品关联校验的测试与发布状态见 [关联一致性证据](evidence/safety-association-20261018/README.md)。
