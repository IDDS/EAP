# 单节点 PoC eBPF 常驻采集

本目录提供采集器 1.1.1 的节点级 systemd 启动方式。它是一个依赖现有 Kubernetes 节点管理配置的 PoC 示例，不是跨节点 DaemonSet 或生产安装器。源码不包含机器凭证、主机配置或集群连接信息。

## 启动契约

`launch.py` 读取 `/etc/eap-discovery-agent/ebpf.json` 中的非敏感配置，通过节点现有 `/etc/kubernetes/admin.conf`，只执行固定的 `eap/eap-ebpf-observer` Secret 查询。Secret 的 `credential` 字段应由可信管理员单独供给。凭证通过匿名管道传给采集器的 `--credential-fd`，随后关闭描述符，不写入状态文件、环境变量、命令行或日志。源码不会自动登记端点或生成凭证。

配置需要采集器配置字段，以及已登记端点对应的 `heartbeat` 请求对象；服务每 60 秒调用同源端点心跳接口。该心跳只表示端点进程可达，采集队列、上传失败和丢弃仍以本机 `observer_health` 日志为准。重启不会自动轮换身份或重新登记。

systemd 单元假定 `launch.py`、`eap_ebpf_observer.py` 与 `eap-connect.bt` 安装在 `/usr/local/lib/eap-discovery-agent/`。失败时重启整个服务组，停止时终止子进程。不要在尚未准备独立端点身份、配置和凭证的节点直接启用。

## 权限与环境

已验证环境为 Ubuntu 22.04、Linux 6.8、BTF 和 bpftrace 0.14。端口字节序采用显式位运算，避免依赖该 bpftrace 版本不支持的 `bswap`。

服务需要特权采集能力，并包含 `CAP_SYS_PTRACE`，用于在该环境读取 `/proc/PID/exe`、关联已经独立登记的资产。这一 capability 也授予更广的进程跟踪能力，并非只读权限。当前启动器依赖管理 kubeconfig，专用最小权限凭证供应尚待实现；应将它按特权节点组件管理，不能直接视为生产部署方案。

## 已验证范围和限制

单节点真实 IPv4/IPv6 回环事件和服务重启恢复已验证，另验证了回执按实际保存行数计数。网络事件不能单独创建 Agent 身份，资产须先通过进程、容器或清单等独立路径建立。本轮未安装完整 Go Discovery Agent，未验证第二节点、节点重启或容量长稳。

探针仅证明连接尝试；连接结果为 `UNCONFIRMED`，字节和协议为 `UNAVAILABLE`。不能据此恢复 MCP 方法、工具结果、同进程子 Agent 或 gVisor 内部流量。生产 TLS、HA 和最小权限供应仍待独立验证。

只读运维检查在远程节点执行：

```sh
systemctl is-active eap-ebpf-observer
systemctl is-enabled eap-ebpf-observer
systemctl show eap-ebpf-observer -p MainPID -p NRestarts
journalctl -u eap-ebpf-observer --since '10 minutes ago' -o cat
```

不要导出 Secret、kubeconfig 或原始主机事件。完整语义见[采集器契约](../../../docs/EBPF_OBSERVER_RELIABILITY.md)，实测范围见[验证说明](../../../docs/VALIDATION.md)。
