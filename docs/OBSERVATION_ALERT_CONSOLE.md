# 原操作告警与审计页面

2026-10-11，T7-K 已部署并完成有界只读验收。

在“运行与观测 → 证据覆盖与定位”的原操作证据抽屉中，按原登记读取 T7-J 告警与审计 API。显示当前事实是否已协调、事实版本、原告警编号与生命周期、审计事件和主体摘要。尚未协调、事实已变化以及服务端截断分别说明，不能把空列表解释为业务成功或没有问题。

页面仅 GET，刷新不提交协调、确认或业务请求。四角色沿用 `/me` 的 `viewConsole` 能力；确认文字为“已确认阅读”，原 UNKNOWN、费用和 Prometheus 窗口告警继续保持。后端治理写接口的权限仍只由 PlatformPermission 定义。

切换原操作、账号或能力时清空旧数据；抽屉关闭卸载组件，迟到的读取不会重新显示旧记录。刷新失败立即清空旧告警与审计，返回另一个原操作的数据会拒绝展示。没有自动轮询或后台扫描。

以下远程检查均已通过。新模型/工具支出为 0；不能称后台协调、全文日志、长期规模、真实模型成功或全部主线已完成。


远程前端全量 232 项、类型检查和生产构建通过，失败/跳过/取消/TODO 均为 0。八项新增合同验证操作切换、读取竞态、账号/能力变化、卸载、失败清理、跨操作响应拒绝和确认/未知语义；后台/Runner 源未变，未重复其历史绿色测试。

四角色真实 PKCE 浏览器均看到原 CHAT 的两条已确认告警/四条审计，原失败 RUNTIME_TASK 的一条告警/两条审计；原 Trace 保留，确认没有变成业务成功。1500px 桌面与 390px 手机均可读且无页面横向溢出。关闭抽屉卸载旧私有事实；viewer 模拟 503 后旧告警/审计清空且展示读取失败。浏览器拦截非 GET/OPTIONS 的业务 API，四角色均写请求 0、未捕获异常和非预期 API 失败 0。全部浏览器进程与依赖运行在远程 Pod。

固定前端镜像：10.186.133.66:5001/eap-frontend@sha256:0a374eab109bd430a45d19a86e19f3f24c2bbb7e355e3cf6654a3d00c551df84。测试 dist 与镜像 manifest/config/末层逐文件精确核对，实际 Pod/version.json 通过。仅 CAS 前端镜像；后端 T7-J/Flyway78、Runner/optimizer、认证、Collector/Prometheus 和两份规则保持。56 表 3140 原行逐摘要完全一致，原两操作/三告警/六审计视图、覆盖指标/最近明细、183 账户和两个关闭团队保持，未新增协调/确认/业务/模型/工具请求。

证据：[测试](evidence/eap-operation-alert-console-20261011/tests-proof.json)、[四角色浏览器](evidence/eap-operation-alert-console-20261011/browser-proof.json)、[原事实保护](evidence/eap-operation-alert-console-20261011/live-proof.json)、[清单](evidence/eap-operation-alert-console-20261011/manifest.json)、[最终回执](evidence/eap-operation-alert-console-20261011-final.json)、[接收核对](evidence/eap-operation-alert-console-20261011-reception.json)。归档 1225695 bytes，SHA256 66206039da0e61548e92daf1f8615d720201ef309b9ac3faebaf5efd6d2a8dcc，64 摘要/769 源/四副本和 5186 旧证据核对，Memory 原 517363 bytes 与 AGENTS 摘要保持。节点密钥和编码仅 RAM 扫描，不导出令牌、密码、浏览器存储或 profile。2 Job/3 Pod 按冻结 UID 清理剩余 0；接收完成后才推进四路径，766→769；原导出 false 与推进后 true 回执分别保存。

首次发布保护门因验收脚本使用旧镜像替代原前端摘要占位值而失败；只有frontend摘要不一致。沿原函数确认 EXPECTED_APPLICATION_IMAGE_CHANGE 占位规则，并证明当前spec在原算法下与冻结基线一致后修正验证脚本；失败诊断和旧脚本保留，应用源、测试dist和发布均未重跑。见 [根因核对](evidence/eap-operation-alert-console-20261011/frontend-digest-normalization-fix.json)。

公开仅[前端固定镜像补丁](../deploy/substrate-poc/observation-alert-console-frontend.json)。只读页面不代表后台协调、可写告警治理流程、全文日志、长期规模或新真实模型实验已完成，全部主线仍未完成。阶段已关闭，不重跑原发布/验收/清理意图；下阶段重新冻结当前 769 源与实际状态。


后续 T7-L 已完成[后台有界循环协调](OBSERVATION_ALERT_RECONCILIATION.md)：持久游标、逐条事务、失败隔离与真实 Pod 恢复通过；现有确认/UNKNOWN/审计保持。页面仍只读，全文日志与长期规模待办。本篇未自动扫描的描述为此前阶段范围。


## T7-M 页面确认交互（2026-10-12）

[确认页面](OBSERVATION_ALERT_ACTIONS.md)已完成远程有界验收。245 项前端测试、类型/构建及四角色浏览器通过；按 /me governance 能力提供事实核对与两步阅读确认，绑定所见版本/摘要。409/断线不自动重试，原 UNKNOWN、费用和窗口告警保留。原三条告警均已 ACK；新增 ACTIVE 的六个浏览器场景是隔离响应夹具，线上只对既有 CHAT 做两次同事实零写协调，不能当作新的真实业务成功实验。13 原登记/13 头/三告警/六审计、预算与运行环境保持。
