# EAP 工具执行前检查客户端

将 eap_tool_guard.py 作为模块放入原程序可导入的目录，仅依赖 Python 标准库。合同和接口见 ../../docs/RUNTIME_TOOL_GUARD.md。

- 先为外部实例完成目录身份、复核纳管、实例凭证配置与签名握手，身份保持 ACTIVE。
- 从运行环境的安全配置渠道取得地址、实例键、实例凭证，不硬编码或打印。默认HTTPS；PoC HTTP需显式选择 allow_insecure_http=True。
- 每次业务操作先保存一个 UUID call_id，再调用 ToolGuard.execute；同一操作沿用原 ID，不因超时创建新 ID。
- 回调仅在检查通过且首次领取成功时执行。参数固定为已检查的 JSON 副本，调用结果保留在原程序内。
- ToolStopped 表示阻断/复核/过期/已领取等停止状态；GuardUnavailable 表示许可未确认。两种情况都不能自动运行工具。
- 回执发送不确定时，返回的 ToolResult 保留 value，receipt_confirmed=False；人工核对，不能据此重试工具。

此客户端不安装或执行任意MCP工具，不绕过调用方自己的权限检查，也不能阻止原程序主动绕过该客户端。已领取调用的取消、补偿和人工批准后继续执行不在本轮。

测试只在远程运行：在 ate-node1 的 /root/eap-src/runtime/tool-guard 执行 python3 -m unittest -v。本机不安装依赖或运行测试。
