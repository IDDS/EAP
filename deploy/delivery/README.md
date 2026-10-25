# EAP 私有化试点交付

当前档位为 **有实施支持的企业私有化试点**。本目录提供发布合同、首次安装、版本检查、源码校验、受测产物镜像构建与升级计划工具。

公开仓库中的 `release.json` 和 `runtime-bases.json` 使用 `registry.example.com` 作为镜像仓库占位符。它们保留受测镜像摘要，但不提供公共镜像下载。部署前在目标环境准备并核验相同内容的镜像，建立该环境的发布清单。

所有命令在远程 Linux 运维或构建节点执行。配置文件只保存 Secret 名称及键名，不填写凭据值。

## 选择入口

| 场景 | 工具或文档 |
| --- | --- |
| 首次安装 JWT 控制台 | [安装说明](install/README.md)、`bootstrap.py` |
| 核对发布合同 | `python3 deploy/delivery/eapctl.py check-release` |
| 汇总只读运维诊断 | `eapctl.py diagnose --namespace eap --output /path/to/diagnostics/new-check`，见[诊断说明](../../docs/OPERATIONS_DIAGNOSTICS.md) |
| 审阅诊断包保留计划 | `eapctl.py plan-diagnostic-retention --root /path/to/diagnostics --days 14 --keep 3`；只生成候选，不执行删除 |
| 检查已有环境 | `python3 deploy/delivery/eapctl.py inspect --namespace eap` |
| 校验受测源码 | `eapctl.py verify-source --root ... --source-manifest ...` |
| 从受测 JAR / dist 构建镜像 | [构建说明](images/README.md)、`image_build.py` |
| 生成应用升级计划 | `eapctl.py plan-upgrade backend --target REGISTRY/IMAGE@sha256:DIGEST`，前端同理 |
| 接入运行系统并完成样例 | [运行接入](../../docs/GETTING_STARTED.md) |

`inspect` 只读取 Deployment；Secret 引用存在不代表凭据正确。`plan-upgrade` 输出带原 UID、resourceVersion 和镜像前置条件的 CAS 补丁，工具本身不执行补丁，也不自动重试。

## 升级与回退次序

1. 固定源码、测试报告、镜像摘要、Schema 和兼容性要求，验证数据库与身份系统的恢复点。
2. 核对磁盘、驱逐阈值、内存、活跃派发与维护窗口。没有 `DiskPressure` 不代表有足够构建空间。
3. 审阅升级计划。后端使用 `Recreate`，避免两个版本同时承担任务写入责任；需接受短暂停机。
4. 单次执行经核对的补丁。结果不明时查询原 Deployment，不无条件重放。
5. 确认旧 Pod 消失、新 imageID、Schema、健康与真实登录；前端核对 `/index.html`、`/version.json` 和静态资源。
6. 验证任务结果、权限、预算与观测，确认原历史和 UNKNOWN 没有被改写。
7. 按登记的 UID 清理本批次临时资源。回退前确认旧应用与当前 Schema 兼容，不以回退镜像代替数据恢复。

默认 Runner 镜像升级不会自动迁移已发布的旧模板和实例。它们的身份、任务意图、缓存、预算及持久目录需按单独合同处理。

## 首次试点验收

至少验证企业登录、持久化、通过 EAP 创建与执行一个合成团队计划、原结果和预算归属、异常停止、观测关联以及实例收尾。安装器完成资源创建后仍保留 `installationComplete=false`，后续验收必须提供独立证据。

跨项目隔离、生产身份 HA / TLS、定期备份、整体灾备、容量与长稳属于后续生产验收范围。详见[验证记录](../../docs/VALIDATION.md)。
