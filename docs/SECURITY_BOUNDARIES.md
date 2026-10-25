## 2026-10-25 停止调度增量

已部署V94停止请求协议：排队即停，运行中在已获准步骤结束后停止后续调度；原任务回执、外部效果、预算与未知责任保留。读取和停止仍从PlatformPermission统一授权，前端仅按/me能力控制。历史执行版本0拒绝，原请求幂等，变更键或调用者拒绝；不提供进程强杀、业务自动恢复或补偿。详见[停止边界](WORKFLOW_STOP.md)、[验收](evidence/workflow-stop-20261025/README.md)。当前Schema94；前文项目边界仍适用。

---

## 2026-10-25 项目边界增量

已部署 V93 数据库绑定、JWT 精确项目声明、原生/AX/源码构建专属空间拒绝及安装 V2。采用每项目独立部署与数据库，现有 PoC 继续共享范围；项目原生 RBAC/网络与真实业务、生产认证仍需独立验收。详见[设计与限制](PROJECT_ISOLATION.md)、[远程验证](evidence/project-boundary-20261025/README.md)。此项不提供同库行级多租户。

---

# 连接器凭据与观测正文边界

本轮对应 [系统审查](SYSTEM_REVIEW_20261018.md) 的 R1、R2、R6。测试和部署状态见 [验收记录](evidence/security-boundaries-20261018/README.md)，不能仅凭本文认定已经上线。

## 连接器凭据引用

连接器 API 保留已有 `authType`、`credentialEnvKey` 字段形状，避免数据库迁移；其中 `credentialEnvKey` **现在只能填写平台登记的引用名称**，不再直接读取同名环境变量。`BEARER_ENV` 和 `HEADER_ENV` 保留为兼容枚举。默认没有任何获准引用；旧的未登记认证连接器会拒绝同步，`NONE` 保持原行为。

平台运维通过服务端配置登记绑定。以下只有配置结构和合成名称，不包含密钥：

```yaml
eap:
  management:
    connectors:
      credentials:
        crm-readonly:
          env-key: EAP_CONNECTOR_CRM_READONLY
          origin: https://crm.example.invalid
          connector-keys: [crm-deals]
          auth-type: BEARER_ENV
          header: Authorization
          prefix: 'Bearer '
```

实际秘密由运维在集群 Secret 中提供。不要把秘密值写进 YAML、文档、表单、日志或命令历史。业务 API 无权新增这张服务端绑定表；控制台仍通过 `PlatformPermission.DEPLOY_AGENTS` 授权，前端按 `/me` 能力控制。

绑定同时限制连接器标识、HTTPS origin（主机及有效端口）、认证方式、header 和 prefix。默认 HTTPS 端口与显式 443 等价；不接受协议降级、userinfo、不同端口或不同主机。凭据仅在检查通过后读取；发送前再核对实际请求目标。目标的原有私网限制及禁止重定向继续有效。引用不匹配和秘密不可用均返回固定错误，不回显秘密、原始请求头或异常内容。

迁移旧连接器时，应先登记专用最小权限凭据及目标绑定，再通过正常更新接口将旧变量名改为引用名称。不能为兼容而允许任意后端环境变量，也不要把已有模型网关秘密作为业务系统连接器凭据。

## 观测元数据

`TraceMetadata` 统一投影 span/tool 属性：只允许已登记字段、标量类型、受限长度与格式。正文、任意 header、exception message 和自定义属性不进入公开元数据。模型 finish_reason、response_state、生成上限、思考模式及正常关联身份保留。增加字段时应同时补契约测试，不能直接放开所有 `eap.*` 或 `gen_ai.*`。

这项投影同时应用于新写入与分页/非分页读取，因此历史 `attributesJson` 也不能原样从公开接口返回。原数据库记录不被静默改写；物理擦除和保留期限属于后续独立治理工作。

OTLP RAG 的查询与文档正文只走已有正文采集路径，不再复制到知识摘要；知识引用以 SHA256 摘要表达，保留来源计数、token 计数和引用重复关系。历史 OTLP 派生知识的摘要与引用也在读取时过滤，避免从旁路读出原文。此变更不等同于对所有知识源完成全面内容治理。

正文采集模式保持原配置：OFF 不采集；HASH_ONLY 保留摘要；REDACTED 保留受控脱敏结果。公开元数据不因当前用户具有正文权限就放回任意原属性；正文仍从专用接口读取。

## 结构化脱敏

`SensitiveContentSanitizer` 对 JSON 对象和数组递归处理敏感字段，识别包含 JSON 的字符串；普通文本继续覆盖凭据、Bearer、邮箱和手机号。结构化数据无法解析或超过递归限制时隐藏内容，不退回宽松文本路径。安全 JSON 保持原形状与格式。

仍保留既有 `PUBLIC` 等启发式分类兼容性；“未命中规则”不能作为数据已经公开的证明。本轮不声称解决了全部秘密识别，也没有批量改写旧评估样本或既有正文。后续需要单独审计旧 REDACTED 数据和统一业务字段可见性。

## 部署配置正文

部署列表、创建和重试的通用响应只保留白名单元数据；历史记录在读取时同样投影。提示词按 Agent 与精确版本通过 `VIEW_CONTENT` 专用接口读取，权限继续与 `/me` 共用 `PlatformPermission`。原配置存储和执行参数不改写。详见[部署正文权限](DEPLOYMENT_CONTENT_PERMISSION.md)；该修复不等同项目/租户隔离。
