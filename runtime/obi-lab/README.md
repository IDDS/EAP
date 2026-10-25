# OBI 有界观测实验

这是实验与计数工具，不是 EAP 生产采集器。最新[同节点实验](../../docs/evidence/obi-node-20261021/README.md)在 ate-node2 的任务前后对照各采集80/80个SERVER Span，中间gVisor任务0/8；仅证明固定版本和进程/端口发现配置下的覆盖缺口。首次结果见 [OBI 评估](../../docs/OBI_OBSERVATION_ASSESSMENT.md)和[首轮证据](../../docs/evidence/obi-20261020/README.md)，旧位置未知结论保持。

- `scenario.py`：在专用普通容器内启动合成 MCP 服务，固定回环端口 `28761`；基线、开启 OBI 两阶段各 20 批、160 次 HTTP 请求、120 次工具调用。仅测试固定协议子集，不连接生产服务或模型。
- `summarize.py`：统计唯一 Span、SERVER/INTERNAL 类型、协议元数据和延迟。重复 Span、非法分母、无效延迟拒绝统计；缺少有效普通容器对照或同节点证据时不输出沙箱可见率。
- `test_summarize.py`：10 项远程测试，覆盖上述计数与结论边界。
- `bracket_control.py`：在同节点普通容器内执行任务前后两组阳性对照，各10批/80请求；只使用合成回环服务。
- `bracketed.py`：按Span开始/结束时间划分三个非重叠窗口；要求前后阳性对照、连续存活的观察器及一致位置边界。未知、跨窗或重复证据不生成有效可见率。
- `test_bracketed.py`：12项新增远程测试；与原10项合计22项通过。不能把位置边界核对解释为连续位置证明。

`flatten()` 只输出允许的属性名，不保留原 URL、请求 ID、头、正文或消息。这是针对受控合成输入的统计视图，不是可直接接收不可信生产遥测的脱敏网关。HTTP 200、OTLP UNSET、同一端口或时间接近都不能证明工具成功、业务效果或 EAP 任务身份。

## 运行边界

应用测试、镜像构建与实验只在远程 PoC 集群运行，通过项目约定的 `n1.sh` 入口。远程已隔离的实验目录内，统计测试命令是：

```sh
python3 -m unittest -v test_summarize test_bracketed
```

场景还依赖专用镜像中的 `/opt/eap-mcp`、固定端口服务变体及 `/out` 阶段门禁（新双对照使用 `/work`），不能直接在本机或生产实例执行。场景不负责安装 OBI，也不负责创建 EAP Agent。历史批次中的脚本是审计证据；批次关闭后禁止重放。

下一批必须在派发前取得 EAP Actor 的权威 Worker/节点分配，在该节点启动精确限定的 OBI，并在样例前后运行普通容器对照。未知位置返回 `INCONCLUSIVE_PLACEMENT`；不能因为采集到零条就判定 gVisor 不可见。

原始合成 OTLP 保留在证据归档；没有向 EAP 导入 OBI Span。未来导入需另行设计身份、协议结果、重复和敏感属性合同。
