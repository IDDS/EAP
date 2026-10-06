# 默认运行池迁移与快照兼容（2026-10-02）

两个节点组件、普通默认池（2 个工作进程）和隔离默认池（1 个工作进程）已完成升级。进程退出确认与安全回收、启动探测退避现已覆盖这两个默认池。其他命名空间与已缩容为零的历史验收池没有全量迁移；凭证续期和认证持久化等也未因此完成。

## 清理与维护边界

用户在上一轮旧 v4 定向清理确认后回复“继续”，本轮沿用该授权。仅删除 eap-agents/eap-a7-v4（UID 803ab1fe-747c-40f4-86d4-14c357d96550）与其无快照准备 actor；原生删除前重新确认没有业务引用、外部卷或快照，EAP v4 FAILED、v5 READY。EAP 发布历史和 v5 保留。

首次删除返回 Aborted，日志证明发生在模板租约获取之前，未进入清理。核对后 240 秒有限重试（89 次锁冲突）仍不成功；没有强杀 worker 或清除租约。新增精确模板维护排除，滚动更新 API 两副本，让旧准备协调请求按正常关闭取消，随后原生接口成功完成终止与容量释放。清理完移除临时排除参数。详细代码见 [维护补丁](../deploy/substrate-poc/patches/template-maintenance/README.md)。

维护开关不是 EAP 发布失败自动取消协议；一般失败发布的跨系统终结与用户可视化清理仍需后续产品化。

## 发布步骤及当前版本

清理完成后检查所有 actor 均没有 workerAssignment，默认池 allocated.actors 为零。先更新 node2 atelet，再逐个修改默认 WorkerPool.workerImage；保持资源配额、副本数、选择器及 OnDelete。每步核对 Pod Ready 与实际 imageID，不仅检查清单 image。升级期间 83 个既有 actor 元数据、状态和快照引用完全一致。

精确镜像与作用范围见 [镜像锁定记录](../deploy/substrate-poc/runtime-image-lock.json)。该文件是版本清单，不是可直接 apply 的 Kubernetes 对象。API 2/2、atelet 2/2、普通池 2/2、隔离池 1/1。worker 为此前通过灰度验收的 4fb1df0f...，含退出检测与就绪优化；API 为 a44359ce...，atelet 为 5bdb9868...。前后端保持 backend740ec318... / frontendaeb00889...，未改业务代码或认证库。

## 本轮验收

| 项目 | 结果 |
| --- | --- |
| 模板协调与维护开关 | 11 个顶层 / 63 个含子用例通过，0 失败/跳过 |
| 可复现补丁 | 原始固定基线依次应用三个补丁及源码摘要校验通过 |
| 两个普通旧快照 | Agent24 在 node2、Agent25 在 node1 恢复；Agent25 内存 snapshot-A 保留 |
| 隔离任务旧快照 | Agent13 既有 ECHO 任务在 node2 恢复，读取原回执摘要一致 |
| 正常状态 | 三个实例在 20 秒观测后 RUNNING，最终全部 SUSPENDED、分配清空 |
| 四角色真实接口 | 监控200；内容 viewer/operator403、governance/admin200；viewer/governance 控制403 |
| 状态与效果 | 其余80个 actor 状态/快照不变；模型计数24=0、25=0、13=2均未增加 |
| 原发布 | Agent7 v4 FAILED 历史与 v5 READY 保留 |

普通两个恢复操作观测2522.2/10217.5ms，隔离恢复9362.0ms；包含EAP请求和状态确认。该轮是一次旧快照兼容测试，不是稳定延迟或吞吐提升证明。启动失败请求减压753→16/秒为前轮合成实验，不能由本轮恢复耗时推导CPU收益。

没有重放旧业务、工具或故障夹具；三个旧实例仅在新的独立验收记录中恢复、只读检查、再休眠。未重复进程退出故障注入，未重跑前后端完整测试和浏览器；此前退出检测、工具权限和前端结果仍按原日期引用。未做真实断电/断网、长期压力、microVM或-race。

## 交接与证据

K8Substrate `artifacts/eap-default-rollout-20261002/README.md` 汇总脱敏证据；raw-evidence.tgz SHA256=b2b5c285a8ae4c590ff2fe19677f2f23609d3e5f3cb32d2bc3482c7248aa094c。源码补丁、11/63测试事件、清理记录、部署前后、角色和快照结果均已导出；不包含模板环境、Secret、原始API日志或完整任务内容。

节点 `/root/eap-failed-template-retirement.json`、`/root/eap-default-rollout.json`、`/root/eap-rollout-compatibility.json` 均 completed=true，必须保留。脚本 `tools/eap/runtime-rollout/` 位于 K8Substrate；不清 pending 或 completed 后重跑。临时 eap-maintenance-version-check 已删除，本地5173/8180均HTTP200，隧道保持。全部测试/构建远程；无git commit/push。

后续优先处理统一实例身份、安全配送/短期凭证续期及旧模板迁移设计，保留共享工具额度和撤销优先级；认证持久化、长任务重启恢复继续按 [后续条件](INTEGRATION_HARDENING_NEXT.md)。Keycloak仍为emptyDir，禁止直接换空PVC重启。
