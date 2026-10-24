# eBPF 连接采集器可靠性与证据契约

日期：2026-10-24。适用：`discovery-agent/ebpf/eap_ebpf_observer.py` 1.1.0/1.1.1，以及 EAP host-observation 接收契约。第一批可靠性改进之后，1.1.1 已补齐 PoC 真实内核启用；没有增加内核挂载点、OBI协议解析或沙箱内部观测。

## 最新状态：真实采集已启用

远程 PoC 节点 的常驻服务已启用并设置开机自启。修复 bpftrace 0.14 不支持 `bswap` 的兼容问题，以及 systemd 权限限制导致无法读取 `/proc/PID/exe` 的身份关联问题；IPv4/IPv6 共 12 条真实事件、服务重启后的另 12 条事件均关联到独立登记的合成资产并持久化。采集器 24 项远程测试通过，凭证从集群 Secret 经匿名管道交给采集器，未写入文件或日志。部署方式与权限范围见 [PoC 常驻服务](../deploy/substrate-poc/ebpf/README.md)。

未登记资产时，网络事件按现有规则不会创建 Agent 身份。后续回执修复已部署：`observationsInspected` 是实际检查的输入数，`observationsRecorded` 是身份关联后成功保存的观测行数；`agentsDiscovered` / `mcpServersDiscovered` 是按已保存资产类型区分的去重关联资产数，不表示本次新建了这些资产。未关联的候选返回数量警告，不再计为已保存。响应成功还要求外层事务正常提交；持久化失败不返回成功回执。字段结构保持兼容，不改历史记录。

真实 API 验收：未知身份检查 1 条、记录 0 条；已知/未知/已知混合批次检查 3 条、记录 2 条、关联 1 个资产，与数据库一致。独立合成资产上另有 2 条真实 IPv4/IPv6 内核连接证据入库；采集器未重启。服务端发现运行记录的 `observationsCount` 仍表示规范化输入数，不能与回执的实际保存数混用。见 [验证范围](VALIDATION.md)。

## 连接尝试与未知测量

现有BPF程序只监听IPv4/IPv6的 `sys_enter_connect`。事件证明进程尝试调用connect，不足以确认连接成功、TCP/UDP、TLS、流量字节或工具结果。

新适配器在每条观测中携带 `observationKind: CONNECT_ATTEMPT`，省略未测量的 `transport`、`protocolHint`、`bytesSent`、`bytesReceived`。不再把容器ID重复填入cgroupId。容器与进程提示仍可辅助发现资产，不构成可信Agent或任务身份。

host-observation服务接收到连接尝试后，输出 `EBPF_CONNECT_ATTEMPT`，证据包含：

| 字段 | 值 |
| --- | --- |
| observationKind | CONNECT_ATTEMPT |
| connectionOutcome | UNCONFIRMED |
| byteMeasurement | UNAVAILABLE |
| protocolMeasurement | UNAVAILABLE |
| identityBasis | PROCESS_OR_CONTAINER_HINT |

该路径即使附带字节、协议或TLS字段，也不将它们作为测量事实保存。已知旧版 `eap-ebpf-bpftrace/1.0.0` 使用同样规则，升级后新收到的旧版记录可准确标明边界，不回写历史记录。

host-observation JSON中缺失的字节值保持null、从证据省略；调用者提供的真实0与正值保留。`NETWORK_FLOW`及未声明类型的既有流量请求保持兼容，未知枚举拒绝。此更新适用于通用host入口和注册机器的v1 host入口；早期独立 `/connectors/ebpf/observations` 聚合协议未新增类型字段，其原有流量测量输入保持，回执计数通过共用Host服务获得上述修正。本轮没有改数据库、权限或前端。

## 队列、退避和确认

- `max_events` 范围1–1000，默认1000，限制待发不同键的数量。键由identityKey、PID、远端地址和端口组成。
- 一个上传线程最多携带一个同样大小的快照；待发集合与快照合计最多容纳两倍容量的对象引用/记录，不会无限创建上传线程。
- 同键合并并计数，队列满后丢弃新键并计数。若上传期间同键收到新事件，旧批次确认不会清除较新的待发记录。
- 初次退避默认5秒，指数增长，上限默认300秒。退出前的flush也遵守退避，不制造立即重试风暴。
- 上传与事件读取分离。HTTP单次操作配置15秒超时，但不宣称这是覆盖DNS与全部慢速响应的绝对总期限；卡住的一个上传仍可能阻塞后续交付，队列保持有界并显露在途状态。
- 回执必须为成功结构、检查数量与批次相同，记录数量合法。错误、部分或过大的回执不能清空队列。服务器可能过滤不符合发现规则的记录，因此“确认数量”不等于“实际入库数量”。
- 上传禁用环境代理、拒绝重定向，机器凭证仅发送到配置的固定EAP地址。日志不输出HTTP错误正文、服务端额外字段或任意异常文本。

重试对象是观测批次，不会执行模型或工具。请求可能已经到达服务器而响应丢失，后续重送可能重复摄入；此入口没有新增批次幂等键，不能宣称恰好一次。

## 管道与采集器状态

旧TextIO读取会预读多行，而selector只看到操作系统管道是否可读，可能使后续行留在Python缓冲中。新实现直接读取最多64 KiB的管道片段，按换行解析；不完整行最多4 KiB，超长输入丢弃至下一换行并计数，非法UTF-8也计数。真实管道测试验证写端不关闭时6条突发事件都能被处理。

状态日志 `observer_health` 默认每30秒输出一次，仅保留计数、状态与固定错误类别。主要字段：eventsSeen、eventsFiltered、invalidLines、observationsCoalesced、observationsDropped、observationsAcknowledged、queuedObservations、inFlightObservations、uploadFailures和retryInSeconds。dry-run打印量与服务器确认量分开。

`UNAVAILABLE/BPFTRACE_MISSING`或`BTF_MISSING`明确表示条件不足；`captureStatus: NO_EVENTS`不证明内核探针已经正常挂载或目标没有流量。用户态丢弃计数不覆盖内核ring/perf-buffer丢失，`kernelLossAccounting`明确为UNAVAILABLE。状态目前写本机日志，未接入平台统一心跳与告警。

队列未落盘。正常退出只进行一次受退避约束的最终发送并最多等待2秒，仍未确认的数量保留在最终状态中；进程退出后不能保证补投。元数据刷新、PID复用、容器重建、持续高负载、事件时间与队列老化仍需后续专项完善。

## 交付与验证边界

先升级支持该契约的后端，再向已经登记、具备Linux BTF和bpftrace的目标宿主部署1.1.0适配器。远程生成的覆盖包只含受测Python适配器、原BPF脚本、配置示例和摘要清单，不含凭证，也不自动修改宿主配置或启用服务。原systemd配置与BPF挂载点保持。

第一批1.1.0交付时，PoC节点有BTF、没有bpftrace，验证了UNAVAILABLE后尚未启用。用户随后明确要求启用，1.1.1已在远程 PoC 节点安装依赖并启动服务，以上最新状态取代此前未启用状态。其他节点和客户宿主未替换；不能把单节点验收扩展为所有环境已启用。

远程验证包含旧实现负向复现、实际回环HTTP错误/恢复/慢响应/重定向、真实管道及后端全量回归；最终数目、部署状态与回执见[验证记录](VALIDATION.md)。没有重跑前端、浏览器、MySQL专项、Go全栈、OBI/gVisor或长稳容量。

后续优先：集中采集器健康与丢失计数；containerd/Kubernetes及进程代际身份；失败连接/RTT/字节测量；有界OBI协议实验；gVisor原生运行监控和协议适配器的覆盖组合。网络证据不能替代工具结果、可信任务归属或业务效果确认。同进程子Agent、跨框架委派与临时代码工具另见[统一执行观测设计](MULTI_AGENT_EXECUTION_OBSERVABILITY.md)；事件接收与显式 SDK 已实现，LangGraph 与 AutoGen Core 的固定版本有界观测适配已验证，创建模板、跨实例关联及其他框架仍待。
