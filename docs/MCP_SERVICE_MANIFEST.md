# MCP 镜像内服务清单

EAP Runner 支持 `EAP_MCP_SERVICES_V1`，将可信 MCP 服务的启动参数从 Go 业务代码移到镜像内的 `/opt/eap-mcp/services.json`。Runner 启动时读取一次并冻结，随后由可信宿主代码通过 `startMCPService(ctx, name, traceID, parentID)` 启动。它复用现有实例身份配送、匿名管道续期、子进程取消及观测回执。

这是可信镜像的配置机制。没有控制台编辑器、远程命令提交、模型自动选择服务或 Kagent MCP 工具适配；不会升级已有 Actor 或平台默认 Runner。服务代码仍须实现 stdio MCP，宿主仍负责初始化、工具调用及任务级幂等。

## 配置与镜像

示例见 [services.fixture.json](../runtime/mcp-observer/services.fixture.json)：

```json
{
  "version": "EAP_MCP_SERVICES_V1",
  "services": [
    {
      "name": "synthetic",
      "server": "eap-synthetic-mcp",
      "tools": ["observation_probe"],
      "command": ["/usr/local/bin/python3", "/opt/eap-mcp/fixture_server.py"]
    }
  ]
}
```

将清单复制到固定路径，并在镜像中提供 `/usr/local/bin/python3`、`/opt/eap-mcp/relay.py` 及其 `observer.py`、`runtime_export.py` 依赖。子服务可使用自己的绝对路径可执行文件；参数按 argv 原样传入，没有环境变量展开或由 Runner 拼接 shell 命令。相对参数按进程工作目录解释，Substrate 中应使用绝对文件路径。

发布镜像固定 digest，通过 EAP 的 IMAGE 部署入口创建新版本。凭证由 EAP 身份配送取得，不写入清单、镜像、命令参数或环境变量。镜像、宿主和服务均须可信；argv 配置可以执行程序，并不是安全沙箱。清单不支持凭证引用、环境注入或任意配置路径。

顶层 `allowInsecureRuntimeHttp` 可选，缺省为 false，仅控制观测导出到 Runtime 接收端是否允许 HTTP；不是 MCP HTTP transport 开关。本 PoC 合成清单显式设 true。生产网络应使用 HTTPS。

## 校验和冻结

- 文件最多 16 KiB，必须是普通文件，拒绝符号链接；不存在表示功能未启用，存在但非法则 Runner 启动失败。
- 版本必须准确匹配；1–8 个服务，名称和 server 标识各自唯一，每个服务 1–32 个不重复工具名、1–32 个命令参数。
- 可执行文件必须为规范绝对路径且最多 512 字节；每个 argv 最多 1024 字节，不允许控制字符。名称和工具名均有固定字符范围与长度限制。
- 拒绝重复字段、大小写变体、未知字段、null、尾随 JSON、无效 UTF-8 和过深嵌套。
- 启动时保存原文件字节的 SHA-256。后续修改文件不会替换已加载的命令；返回给调用方的数组是副本。重启后会重新读取，因此仍须固定镜像和控制挂载权限。

`tools` 是观测名称白名单：不在表中的工具在观测中匿名化；它**不拦截或授权**工具执行。实际执行许可、业务权限和副作用约束应由宿主及工具服务实现。

## 运行与查询

`GET /eap/mcp/profile` 使用现有 Runner 任务认证，返回版本、是否启用、清单摘要及服务名称/server/tools。返回值不包含命令、路径、参数或凭证；过期、旧版凭证不能读取。平台尚未提供该接口的控制台代理或配置表单。

`startMCPService` 仅接受已注册名称，随后检查原实例租约、Actor UID、Trace 和父 Span。并发会话上限沿用 16；每次任务独立启动/关闭，取消终止进程组。租约更新、导出失败和未知状态语义见 [MCP 观测](MCP_OBSERVATION.md)。清单摘要证明所加载配置的一致性，不证明进程行为、工具效果或镜像安全。

专用 `mcpfixture` 构建固定选择 `synthetic`，执行成功、失败、断连三种合成结果，回执附安全配置摘要。它禁用模型调用；相同原任务回执复用，未知意图不重执行。该构建是验收宿主，不是通用业务 MCP 执行器。

本轮代码和远程验收状态以 [验收记录](evidence/mcp-config-20261020/README.md) 为准。同版本替代实例及旧历史隔离已有[独立验收](MCP_INSTANCE_REPLACEMENT.md)，其同名UID漂移与进程恢复证明范围仍有限。生产服务接入、HTTP/SSE、跨任务共享连接、持久恢复及eBPF/gVisor对照仍待。
