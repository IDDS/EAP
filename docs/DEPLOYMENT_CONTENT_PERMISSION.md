# 部署配置与提示词的访问边界

部署列表、提交回执和失败步骤重试的完整响应，原先会把保存的部署配置原样返回，包括 `systemPrompt`、环境配置和命令。现在通用响应的 `config` 只返回界面所需元数据；提示词通过单独的正文接口读取。

## 元数据响应

`config` 保留对象形状，仅允许以下标量字段：

`sourceType`、`runnerFramework`、`frameworkVersion`、`runnerToolMode`、`model`、`maxTokens`、`thinkingMode`、`port`、`sandboxClass`、`size`、`identityMode`。

提示词、`env`、`command`、`readinessPath`、源码地址、身份公钥及未列入白名单的新字段不再随通用响应返回。所有角色采用相同投影；拥有正文权限也不会让完整配置重新出现在列表中。当前控制台使用的版本、身份和模型标记仍然可读。

投影在读取时执行，因此历史部署也受到保护。数据库中的原始配置、首次请求指纹和内部执行参数保持不变；重放原请求或重试原失败步骤不会把正文重新带回响应，也不会为了隐藏内容生成新部署版本。

## 正文接口

`GET /api/agents/{agentId}/substrate/deployments/{version}/content`

响应仅有 `deploymentKey`、`agentId`、`version`、`systemPrompt`。没有保存字符串提示词时，`systemPrompt` 为 `null`；没有该 Agent 的指定版本时返回 `400`，不退回最新版本或其他 Agent 的配置。

接口使用 `PlatformPermission.VIEW_CONTENT`，与 `/api/platform/me` 的 `capabilities.viewContent` 来自同一权限定义。在 JWT 模式下：

| 身份 | 部署元数据 | 提示词正文 |
|---|---|---|
| viewer / operator | 可读 | `403` |
| governance / admin | 可读 | 可读 |
| 未认证 | `401` | `401` |

本次未新增前端正文按钮。后续界面如需展示，应按 `/me` 返回的能力请求此接口，不解析 JWT、不按角色名自行授权。原有依赖 `config.systemPrompt` 的 API 客户端需要迁移到该接口。

## 范围

这是部署配置的正文权限修复，不是项目或租户隔离。在默认共享部署中，有正文权限的主体仍处于共同信任域；需要项目边界时，另配置[项目独立部署](PROJECT_ISOLATION.md)，并完成目标环境权限与负向验收。此项也不对历史数据库内容作擦除、加密或保留期限承诺。

实际测试和发布状态见[验证记录](VALIDATION.md)。原始测试材料只使用合成提示词；真实部署验收仅在内存比较正文摘要，不导出正文或令牌。
