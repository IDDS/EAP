# EAP 0.2.0.0 部署记录

2026-09-08 20:41–20:44（Asia/Shanghai）已部署到腾讯云 `http://114.132.234.109:8091`，服务器目录 `/home/ubuntu/eap`。

## 发布来源

- 代码提交：`29b77a97ec23864ff9d74831fcdb2c3495d3ab53`。
- 已推送分支：`origin/feat/galileo-evaluation-foundation`（Gitee）；未合并 main。
- 版本：`0.2.0.0`，前端 `0.2.0`，后端 `0.2.0-SNAPSHOT`。
- 本地重新执行 `mvn -q clean package`：185 项测试通过；`npm test`：34 项通过；前端生产构建与 diff 检查通过。
- 部署包仅包含预构建 JAR、前端 dist、对应 Dockerfile/nginx 配置及发布清单；未覆盖服务器 `.env` 或原 Compose 配置。
- JAR SHA-256：`bdbb408bfcc13cca58e207016bc567d1b5bbc5f6f66475ebdd7f48eb8ea06e65`，远端解包后核验一致。

## 部署与回滚材料

- 发布产物目录：`/home/ubuntu/eap-releases/29b77a9`。
- 前端/后端镜像：`eap-frontend:29b77a9`、`eap-backend:29b77a9`；容器 revision 标签均为 `29b77a9`。
- 数据库和旧产物备份：`/home/ubuntu/eap-backups/20260908T123803Z`，目录仅拥有者可读写。
- 数据库备份 `eap.sql.gz`（816KB）通过 gzip 完整性检查；旧产物 `artifacts.tar.gz`（79MB）。旧 target/dist 另存于同一备份目录。
- 保留旧镜像标签 `eap-backend:rollback-20260908T123803Z`、`eap-frontend:rollback-20260908T123803Z`。
- 本次服务器新增 `docker-compose.release.yml`，为前后端固定镜像标签和 revision。以后应同时使用三个 Compose 文件，避免无意切回旧镜像配置：

```bash
docker compose -p eap -f docker-compose.tencent.yml -f docker-compose.tencent.prebuilt.yml -f docker-compose.release.yml up -d --no-deps backend frontend
```

只重建 EAP 前后端；MySQL 容器及 HelloBoss 容器未重启。备份保留用于人工回滚；未演练数据库恢复，不能将 gzip 检查视为恢复演练。后续数据库已可能产生新数据，不应直接覆盖恢复旧备份。

## 线上验证

| 检查 | 结果 |
|---|---|
| EAP 后端 / MySQL | healthy |
| 前端 | Running，公网首页 HTTP 200 |
| `/actuator/health` | UP |
| MySQL Flyway | V38 → V42，新增 4 项迁移成功，失败迁移数 0 |
| 节点编辑版本 | 现有工作流节点返回 version=0，数据库列已存在 |
| AgentOps 首页 | GET 成功，持久化同步时间存在，freshness=FRESH |
| 工作流执行状态 | enabled=false；保持默认关闭 |
| 浏览器首页 | 实际内容正常，控制台无错误；一次页面 load 约 2.55 秒（非性能基准） |
| 浏览器工作流页 | 已有流程加载，重新加载按钮可用，真实执行关闭，控制台无错误 |
| 浏览器评估页 | 8 个评估器正常显示，数据集/结果空态正常，控制台无错误 |
| 浏览器 Agent 详情 | 可打开模型与 Token 区域，六类历史分页/空态可见，控制台无错误 |
| 启动日志 | 未发现 ERROR/Exception；保留既有 Flyway 对 MySQL 8.4 的版本兼容提示 |

本地浏览器截图保留于 `/tmp/eap-deploy-{home,workflow,evaluation,agent-history}-29b77a9.png`，未将页面业务信息截图提交到仓库。

本轮是部署冒烟验证，没有调用真实业务 Runtime、运行生产任务、修改业务工作流或创建测试数据；不是全部业务按钮的端到端验收。现有 DEVELOPMENT/HTTP 演示环境配置保持原状，JWT/OIDC、HTTPS 与真实任务协议验收仍是独立后续工作。
