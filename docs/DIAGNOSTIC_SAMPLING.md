# 新实验的版本化诊断采样

日期：2026-10-09。阶段：T7-C。后端基础能力已部署并完成本批有界验收；跨入口覆盖、日志指标关联及真实新实验端到端采样仍待后续验证。

这项能力让平台冻结新实验的可选诊断选择，不改变实验执行、质量判定、费用或核心观测完整性。默认策略 v1 使用 `SHA256_TRACE_V1`、`sampleBasisPoints=10000`，保持全部可选诊断，不预设降低保留率或节省比例。

## 策略与登记

`GET /api/observability/diagnostics/policy` 返回当前不可变版本、算法、万分比和发布时间，沿用中央 `PlatformPermission.VIEW_CONSOLE`。`POST` 沿用中央 `GOVERNANCE`，只接受整数 `expectedVersion` 与 `sampleBasisPoints`，后者范围 0–10000；未知字段、正文、风险声明及非法类型返回 400。版本不匹配返回 409。发布与新实验登记通过数据库行锁串行化；旧版本不覆盖。发布者仅保存完整认证主体的摘要。

V69 只新建策略版本、策略头、实验诊断决定和诊断接收四表。不会给历史实验补登记，也不会修改原回执、UNKNOWN、费用、授权或旧实例。新的原实验提交在同一事务中冻结每个试验的策略版本、原 Trace、Agent、实例键、数据库实例编号、端点、Actor UID 与模板；参数相同的原请求直接返回原登记，不读取最新策略重做决定。

平台登记的实例身份与运行程序返回的 UID/模板一致时，按算法名、版本、Agent、实例编号、实例键、规范化 Trace 做 SHA256，取前 8 字节无符号整数模 10000，与万分比比较。原 Trace 由平台生成，调用方不能指定生效版本或选择结果。没有专属身份的旧实例记录 `IDENTITY_UNCONFIRMED`、`selected=null`；不推断为低风险。

## 接收边界

仅 `/api/runtime-access/v1/traces` 中**名称严格等于 `agent.diagnostic`** 的额外 Span 参加这项采样。先完成机器凭证、身份声明、Trace/Span 命名空间和单次大小/数量校验，再按原登记核对实例归属。任务、对话、AX 及共享 OTLP 通道尚未纳入本批策略登记；已有采集行为不改变，也不能称全入口采样已完成。

以下事实不参加降采样：`agent.experiment` 等核心 Agent Span，网关调用与 usage、费用预占/结算、原请求与回执、UNKNOWN、身份与责任变更，以及工具准入和原效果。可选诊断的客户端 `risk`、`approved`、状态与错误不参与风险判断。平台保存的试验状态为 `FAILED` 或 `UNKNOWN` 时保留新到达的必要诊断元数据，即使冻结的基础选择为 false；原选择与执行终态不改写。此前已过滤的原 Span 不因后来状态变化而重放或补造。

保留的可选 Span 只含规范化 ID、固定名称、合法的生产者起止边界及服务端原因码；调用方属性、资源属性、事件、链接、错误及正文均移除。额外诊断不采集内容，现有核心内容仍由 `OFF/HASH_ONLY/REDACTED` 与正文权限约束。诊断时间不作为模型生成时延或性能收益证据。

每个试验最多登记 16 个不同的可选 Span。原 Span ID 的首次接收决定保留或过滤，重复只累加重复计数，不覆盖原诊断；超限单列丢弃计数。按 Trace 排序加锁避免混合批次锁序反转。接收记录和对应 OTLP 写入共用事务，写入失败不登记为已收到。身份不匹配或无登记只返回固定警告，不创建替代原意图或任意 Trace 登记。

## 读取证据

`GET /api/agent-experiments/{id}/diagnostics` 沿用中央 `VIEW_CONSOLE` 及 Agent 可见性规则。每个原试验分别返回冻结版本/选择、当前身份确认、平台执行状态、首次决定时间，以及保留、过滤、重复和超限接收计数。决定与接收状态分开：选择保留但没有到达的证据仍为 `NOT_RECEIVED`；历史没有登记的试验显示 `POLICY_UNCONFIRMED` / `DENOMINATOR_UNCONFIRMED`。实例身份后来改变会显示 `UNCONFIRMED`，不把旧证据归给新 UID。

该接口仅返回元数据，不返回提示词、样本、输出或凭证；不会签发/续期身份、执行模型、使用预算、提交工具或补发 Span。核心完整性继续由独立 `/telemetry` 接口核对 Agent Span 与同身份 GATEWAY 事件。诊断选择或收到额外 Span 都不能把业务 UNKNOWN/FAILED 变成成功。

T7-C阶段没有新增前端管理页面或可选Span生产者。2026-10-09后续T7-D已为新Runner增加显式协议声明；平台只对冻结profile声明支持的新实验发送emitDiagnostic，最多1条最小agent.diagnostic与核心Span同一有界请求发送。旧请求省略字段保持，旧实验不重放。跨入口分母与关联见[OBSERVATION_COVERAGE.md](OBSERVATION_COVERAGE.md)；告警、全入口与规模对照仍待。

## 验收记录

后续T7-D真实新实验已验证：Agent63的experiment47(v4/10000)两诊断保留，experiment48(v5/0)两诊断过滤，四试验的核心Agent/GATEWAY及原SETTLED费用均齐全。随后发布v6/10000，原v4/v5决定不追改，旧46仍UNREGISTERED。以下为T7-C当时的历史记录，其零新增、无生产者及v4状态不代表当前T7-D状态；T7-D四角色浏览器、清理、归档接收和725绿色源推进已闭环，最终证据见新合同；仍不声称全入口、独立指标采集或规模收益。

原独立红测保留在 [HTTP 合同回执](evidence/eap-diagnostic-policy-red-20261009.json)：7 项、6 个预期失败、0 错误/跳过。原合同逐字加入绿色测试，未重跑红测或旧业务。最终候选从 709 项源码增至 715 项，共 16 个变更路径；全部依赖、测试、打包、镜像构建及部署在远程集群执行。

最终 v2 远程定向 144 项/9 类、完整 662 项/97 类，失败、错误和跳过均为 0，打包退出 0。v1 定向 142 项通过，补充两项实际机器认证接收链路测试后由 v2 替代；原日志与辅助失败记录保留。冻结策略、确定性、0% 下核心保留、可信 UNKNOWN/FAILED、重复/限额、事务回滚、并发发布、跨实例拒绝及全批验证先于写入均有隔离回归证据。

后端固定镜像为 `10.186.133.66:5001/eap-backend@sha256:cb8201c3ccfc8213f5d286e9ed8cfddb85d6f8dd21387eae322efe4ef45b5161`。manifest/config/末层摘要及层内 jar 与测试打包逐字核对；实际 Pod UID `1f1f26b8-0cf1-452a-9bd7-68352d33e6eb`、imageID、健康 UP 和 MySQL 迁移 69 均确认。仅 CAS 更新后端镜像；两个 Runner 未来默认、其他环境、前端/optimizer、旧 UID/模板及认证配送配置保持。最小镜像补丁见 [diagnostic-policy-backend-image.json](../deploy/substrate-poc/diagnostic-policy-backend-image.json)。

真实现有账号 PKCE 登录的权限结果如下，授权仍全部来自中央能力定义：

| 角色 | `/me`、策略和旧实验诊断元数据 | 原内容读取 | 策略发布 | `GOVERNANCE` 能力 |
| --- | --- | --- | --- | --- |
| viewer / operator | 200 | 403 | 403 | false |
| governance / admin | 200 | 200 | 200 | true |

陈旧版本 409、非法正文 400、匿名 401，并发发布恰好一成功一冲突。实测只发布 10000 万分比，当前 v4/10000；旧实验 46 的两个试验仍未登记，版本/选择为 null，生产诊断决定和接收表均为 0 行。没有新增真实实验、Actor、模型/工具调用、可选 Span 生产者或前端页面，也没有重复浏览器验收；隔离接收测试不能称为真实模型端到端采样实测。

33 表 2703 行全字段摘要及费用、两个 atespace 的旧 UID/模板/状态、认证 Pod、配送 Secret 与其他环境保护通过。实际 9 个 Secret 原文/编码仅在节点 RAM 扫描，结果 PASS；模型调用和工具效果新增 0。4 个本批测试/构建 Job 和 4 个 Pod 按冻结 UID 清理，剩余 0，日志已保存扫描，清理后保护 PASS。

[验收索引](evidence/eap-diagnostic-policy-20261009/index.html)与[最终接收回执](evidence/eap-diagnostic-policy-20261009-final.json)：归档 809132 bytes，SHA256 `f38fc1be29eb394675305fb77c5b503f50a15c20bb22d036a0712301a9af90c7`；本地接收核对 264 文件摘要、263 索引链接、16 源码副本及完整 715 项源码。不可变归档的 `receipt.json` 记录导出时源码推进待完成状态，推进结果单列最终回执，不回写原归档。

[辅助历史](evidence/eap-diagnostic-policy-20261009/auxiliary-history.json)保留过早构建被打包门禁拒绝、Registry 仅接受 Docker 类型而读不到 OCI manifest，以及可选 Metrics API 不可用。发布后首次健康读取 503，后续同 Pod/同镜像只读复核 200/UP；没有额外部署，原 503 原因未证明。未测资源消耗、生产性能或采样节省；默认仍 100%，全主线尚未完成，未 commit/push。
