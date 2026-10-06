# 认证持久化与恢复合同（T5-A，2026-10-06）

本阶段解决 PoC 认证 Pod 替换后账号、主体 ID、客户端及签名键丢失的问题。不是整个 T5 或主线全部完成；实例身份自动配送、续期及旧模板迁移仍待。

当前正式入口仍是 `eap-auth/keycloak` Service，选择 `app=keycloak-persistent`。认证镜像固定原 26.7.4 digest，单副本 `Recreate`，`keycloak-data-1005` 为 local-path RWO PVC。关闭 realm 重新导入；实际启动前检查已有数据库，空卷拒绝启动。`keycloak-backups-1005` 为 NFS RWX PVC；两个 PV 均 `Retain`。

## 迁移和证据

所有依赖、测试、浏览器和部署均在远程集群执行。本机只编辑、打包和核验脱敏证据，不运行应用构建。

1. `prepare.py` 在源 Pod 所在节点准备工具和两类 PVC，未改变原服务。Secret 仅在集群生成；密钥不写运维文件或日志。
2. `store.py probe` 实测 Java/H2 独占记录锁与只读工具共享锁冲突。原 Pod UID 固定，仅正常关闭 Java，保留同一 Pod 的 emptyDir；工具取得共享锁后读取已关闭的数据库并立即 AES-256-GCM 加密、fsync。不复制运行中的 H2，不以 realm JSON export 代替数据库备份。
3. 先在零副本、无 writer 的独立数据卷恢复，原镜像启动独立预览实例；核对全部 eap realm 用户、角色和客户端 ID、公钥摘要、四角色原访问/刷新令牌与新 PKCE 登录。
4. 原服务冻结后生成新的关闭后加密备份，再恢复并切换。首次切换 `HTTP metadata 401`，已恢复原路由；旧备份及失败尝试保留。调整 EAP 验权到正式认证路由恢复后，新一轮 78.77 秒切换通过。首次没有栈证据，不把 401 归于账户丢失或令牌过期；300 秒访问有效期没有延长。后续精确阶段、有效期和安全栈已记录。
5. 真实替换持久化 Pod，核对新 UID、相同用户/角色/客户端/公钥、原刷新会话及新登录。四角色 1500/390px Chromium、刷新页面与手机导航通过，浏览器业务写入 0、无未捕获异常或失败 API。错误 audience 401、内容权限 200/403 保持一致。
6. `guard.py` 从实际 Deployment 读取 init 命令，在独立 emptyDir 上验证空数据库被拒绝，writer 不启动。两个旧运维入口按 Service selector 找唯一就绪 Pod，`--check-target` 仅检查目标。重启期间拒绝无就绪 writer，恢复后指向新 Pod；不重置密码或修改客户端。

完成标记保存在节点 `/root/eap-t5-auth-evidence.json`。已完成后不要重跑 `prepare/ready/preflight/cutover/restart`，不要删除旧备份或完成标记。`cutoverStarted` 但未完成时先核对 UID/路由/备份，不能再次发信号或覆盖目标数据库。`reconcile.py` 只处理确认已回滚、原 UID/路由及身份完整的失败尝试，原备份不可覆盖；不用于不确定的切换。

## 恢复边界

数据库原文件只位于受控应用卷和进程内存；恢复点以 `.enc` 保存在备份 PVC，AEAD 验证备份身份及内容，实际篡改和错误身份均拒绝。解密密钥只在不可变 `keycloak-backup-cipher` Secret；没有外部密钥托管或整集群灾备，不能把该 NFS 备份称为跨集群灾备。

恢复前必须停止唯一 writer、等待所有目标 Pod 消失、保留当前恢复点，验证密文摘要/AEAD，再解密写入目标 PVC。必须使用相同数据库及认证版本；不能将旧 emptyDir Deployment 直接重新扩到 1，也不能用空 PVC 或重新导入 realm 冒充恢复。正常 Pod 替换不需要重新设置密码或轮换 Secret。

本阶段是 PoC 单 writer H2 持久化与一次经验证的加密恢复流程；没有定期备份调度、外部关系数据库、生产 HA/TLS 或全量长期会话压力验收。后续备份需重新绑定当前 writer/PVC，不复用旧源 Pod hostPath。

原理与限制参见 [H2 备份文档](https://h2database.github.io/html/tutorial.html)、[Keycloak 导入/导出说明](https://www.keycloak.org/server/importExport)、[数据库配置](https://www.keycloak.org/server/db)。源码与证据不含数据库备份、Secret、令牌、浏览器存储或 HAR；输出前在节点内存按实际 Secret 值及其 base64 扫描。

EAP 部署清单 `deploy/substrate-poc/keycloak.yaml` 现在描述已恢复的正式状态；它不创建恢复点或密钥、不初始化空数据库。首次环境初始化与现有集群迁移不可混用。当前配置仍是 PoC，实例24h凭证的人工配置、安全自动配送/续期及旧模板迁移另属T5下一阶段。

证据复核发现首轮手机导航截图截于展开动画途中；最终增加抽屉完整显示的几何边界检查并重拍，四角色导航均在390px视口内。原39文件导出与首轮截图保存在first-export归档，不删除或冒充最终截图；没有重跑认证迁移或模型任务。
