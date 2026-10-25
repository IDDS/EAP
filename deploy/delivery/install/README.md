# 首次安装 JWT 控制台

`bootstrap.py` 在已有 Kubernetes、独立空 MySQL 数据库和企业 IdP 的环境中，创建固定版本的 EAP 前后端与两个内部 Service。当前阶段为 `CONTROL_PLANE_BOOTSTRAP`：可以登录、检查权限和管理平台记录；Substrate、模型网关、派发及异步执行尚未接入。完成本步骤后还需按交付入口完成运行系统接入及业务验收。

所有命令仅在授权的远程 Linux 运维节点执行，需要 Python 3 标准库与可访问目标集群的 `kubectl`。Python 安装器只接收 Secret 键名，不接收或输出凭据值，也不保存令牌；不要将密码填写到配置文件、命令参数或 shell 历史中。`kubectl` 子进程仍需 `get Secret` 权限，Kubernetes 会把完整 Secret 对象返回给它，再由模板只输出键名；这不是服务端字段权限隔离。

## 准备环境

1. 从发布清单核对前后端固定镜像摘要与 Schema，确保节点能拉取镜像。公开 `release.json` 使用示例仓库；须由实施人员准备并核验相同镜像内容，形成该环境的发布清单。
2. 创建本次专用命名空间。每次生成新的 `installId`（规范 UUID），设置命名空间标签 `eap.io/bootstrap-id` 为该值，并独立记录实际 Namespace UID。工具不创建命名空间，不采用已有同名应用。
3. 创建独立的空 MySQL 8 数据库与受限数据库账号；账号需能完成 EAP Flyway 迁移。数据库需持久存储及备份。通过环境的凭据管理流程创建 `eap-database` Secret，仅包含引用所需的 `username`、`password` 键。工具不安装数据库，也不配置备份。MySQL 检查客户端必须使用核验过的固定摘要。
4. 配置企业 IdP 的 Authorization Code + PKCE S256 公共客户端，redirect URI 为控制台 origin 加 `/auth/callback`，允许相应 Web origin，配置 `openid profile`、`eap-api` audience 与顶层 `roles` claim。角色映射沿用 `PlatformPermission`：`VIEWER`、`OPERATOR`、`GOVERNANCE_ADMIN`、`PLATFORM_ADMIN`。不要给浏览器配置客户端密钥。
5. 公共控制台和 issuer 使用 HTTPS。`jwkSetUri` 可使用明确的集群内 HTTP 后通道，但必须属于同一受信任 IdP。只有本地回环验证允许公开 HTTP。Ingress、TLS 证书、DNS、网络策略由实施人员配置；工具只创建 ClusterIP。
6. 从 [配置示例](config.example.json) 创建环境配置，替换名称、UUID、地址和镜像。只填写 Secret 名称和键名，不填写凭据。默认数据库 `sslMode=REQUIRED` 要求加密但不校验服务器身份；证书身份校验及其配置暂未支持。`DISABLED` 只用于已明确接受明文传输的隔离 PoC，MySQL 8 公钥获取随之启用，不属于生产安全方案。

安装期间独占该命名空间和数据库，阻止其他写者初始化或使用它们。空库检查与应用创建并非跨资源事务；Namespace UID 检查也不是 Kubernetes 的原子命名空间前置条件。工具不能消除并发管理者删除重建命名空间或在检查之后写库的竞争。

## 生成、核对与安装

下列 `<...>` 是实施人员核对后替换的非凭据占位符。安装包目录和回执文件必须不存在；回执必须放在安装包目录之外。

```sh
python3 deploy/delivery/bootstrap.py render \
  --config /secure-ops/eap-install.json \
  --release deploy/delivery/release.json \
  --output /secure-ops/eap-bundle

python3 deploy/delivery/bootstrap.py preflight \
  --bundle /secure-ops/eap-bundle \
  --expected-bundle-sha256 <独立记录并核对的包摘要> \
  --namespace-uid <实际命名空间UID>

python3 deploy/delivery/bootstrap.py install \
  --bundle /secure-ops/eap-bundle \
  --expected-bundle-sha256 <独立记录并核对的包摘要> \
  --namespace-uid <实际命名空间UID> \
  --receipt /secure-ops/eap-install-receipt.json

python3 deploy/delivery/bootstrap.py status \
  --bundle /secure-ops/eap-bundle \
  --expected-bundle-sha256 <独立记录并核对的包摘要> \
  --namespace-uid <实际命名空间UID>
```

`render` 固定配置、发布清单和所有资源，并输出外部摘要。该摘要防止包内容漂移，不是发布者签名。`preflight` 核对 Namespace UID/标签、同名对象不存在、Secret 键名，以及服务器 dry-run；它不证明数据库可达、为空或登录可用。

`install` 首先持久记录写入意图，再创建一次性数据库检查 Job。连接失败或非空就停止，尚不创建应用；检查成功才创建 ServiceAccount、两个 Service 和两个 Deployment。全部使用 `create`，不执行 `apply`、覆盖、收养或自动重试。数据库检查只在首次安装执行，不作为应用 initContainer，因此已有数据不会阻止正常重启。

`APPLICATIONS_CREATED` 和 `applicationChecksPassed=true` 均不等于交付完成。安装器始终保留 `installationComplete=false`，把后续验收留给独立证据。

## 验收与失败处置

验证前后端实际 imageID、HTTP `/actuator/health`、`/index.html` 与 `/version.json`，确认 Flyway 无失败记录且版本与发布清单一致。通过真实企业登录测试四角色的 `/api/platform/me` 能力和接口拒绝；前端按钮应与能力一致。确认新环境没有旧任务和开发种子泄漏。在合成数据范围内经 EAP 创建一个草稿 Agent，再分别替换应用和数据库 Pod，确认相同持久卷上的记录不变。此测试不替代数据库故障恢复、备份恢复或 HA 验收。

发生超时、连接中断或部分创建时，保留原安装包、回执与资源。回执中 `INTENT_RECORDED` 表示结果尚不确定，需按原 namespace/名称查询实际对象 UID、标签与状态；不要重新执行安装或改回执以绕过保护。数据库检查 Job 仅输出 `DATABASE_EMPTY`、`DATABASE_NOT_EMPTY` 或 `DATABASE_UNAVAILABLE`，退出码分别为 0、21、20。Job 超时不能视为空库。

工具尚无自动续装、卸载或数据库回退。需要清理时，由实施人员对照原回执核验归属和 UID，再执行有前置条件的删除；数据库、PVC、IdP 与业务记录不由安装器删除。已有应用的升级使用 `eapctl.py`，不使用首次安装入口。历史验收批次和旧任务不重放。

首次安装包的远程验收记录见 [验证说明](../../../docs/VALIDATION.md)。该记录覆盖同一 PoC 集群的新命名空间与空库；未参与开发的实施人员、独立客户集群、外部生产 IdP/TLS、完整运行系统接入仍需单独验收。

## 项目独立部署

需要固定项目边界时，采用 [project.example.json](project.example.json) 的 `EAP_INSTALL_V2 / PROJECT_CONTROL_PLANE_BOOTSTRAP` 配置。每个项目使用独立数据库、命名空间、OIDC audience/client 和凭据，JWT 必须携带精确的 `eap_project` 声明；数据库绑定采用 Schema93 以上的配套发布。安装器只创建控制平面，原生权限、网络、登录及业务接入仍需验收。配置与迁移边界见[项目独立部署](../../../docs/PROJECT_ISOLATION.md)。
