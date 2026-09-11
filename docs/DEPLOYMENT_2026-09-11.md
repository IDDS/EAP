# EAP 0.3.0.0 部署记录

2026-09-11 12:07–12:12（Asia/Shanghai）已上线腾讯云现有实例。公网工作台 http://114.132.234.109:8091/ ，运行大屏 http://114.132.234.109:8091/command-center 。

## 发布来源

- 发布提交：`6996472`，本地分支 `feat/galileo-evaluation-foundation`；未合并主分支、未推送仓库。
- 版本 `0.3.0.0`；前端 `0.3.0`，后端 `0.3.0-SNAPSHOT`。
- 重新执行后端 clean package：202 项全部通过；前端54项全部通过，生产构建与diff检查通过。
- 前端 buildId：`2026-09-11T03:58:48.259Z`，公网 `/version.json` 与本地产物一致。
- JAR SHA-256：`40b2c00b663aedeeb7af20a1c9abe3375552106354b3ede1307c79189960e557`。
- 上传内容仅为预构建JAR、静态前端、Dockerfile/nginx配置、V44 SQL与发布清单，远端全文件SHA256校验通过；没有覆盖服务器.env与原基础Compose。

## 备份、迁移与切换

- 备份：`/home/ubuntu/eap-backups/20260911T035930Z`，数据库gzip约912KB，旧产物约79MB；gzip完整性通过。
- 旧镜像：`eap-backend:rollback-20260911T035930Z`、`eap-frontend:rollback-20260911T035930Z`，原发布版本b79b7fd。
- 在隔离数据库 `eap_release_check_6996472` 恢复数据库备份后执行V44，确认单例扫描状态行、3个进度字段和复合索引均存在。验证库已清理，备份保留。
- 首次切换已成功迁移V44，但前端刚启动时探测遇到connection reset（curl56），脚本自动恢复旧应用镜像。给探测增加 `--retry-all-errors` 后重新发布成功。没有回滚数据库；V44为增量表/字段/索引迁移，旧应用在回退期间启动正常。
- 当前发布产物：`/home/ubuntu/eap-releases/6996472`，镜像 `eap-backend:6996472`、`eap-frontend:6996472`。
- `/home/ubuntu/eap/docker-compose.release.yml` 固定镜像、revision标签与本次产物build context。后续继续同时使用三个Compose文件：

```bash
cd /home/ubuntu/eap
docker compose -p eap -f docker-compose.tencent.yml -f docker-compose.tencent.prebuilt.yml -f docker-compose.release.yml up -d --no-deps --no-build backend frontend
```

只重建EAP前后端，MySQL与其他业务容器未重启。回退应用时可恢复备份的release.yml并重建前后端；不要直接覆盖恢复旧数据库备份，否则可能丢失发布后的新数据。未演练整库灾难恢复。

## 上线验收

- 后端healthy，`/actuator/health`为UP，MySQL healthy；Flyway V44，失败迁移0，扫描状态单例存在。
- 公网首页、大屏与version.json均200；AgentOps、拓扑、调用遥测、评估摘要、业务价值、平台状态、评估任务七个接口均成功。
- 浏览器：大屏10个数据状态正常更新、无失败提示，1920×1080无页面溢出；首页默认运营事项队列；评估页面可加载。
- 新后端启动日志未见ERROR/Exception；保留既有Flyway关于MySQL8.4高于验证版本的警告。
- 原DEVELOPMENT、HTTP与控制派发关闭配置保持原状，未开启真实Runtime执行；没有创建测试事项或提交生产评估/认领/控制命令。
- 线上截图仅放本地临时目录，未提交业务页面数据。此次为上线冒烟检查，不是长期压力测试。
