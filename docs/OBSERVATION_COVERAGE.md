# 跨入口可信分母与证据关联

日期：2026-10-09，阶段T7-D。实现、远程回归、部署、新实验/AX/控制节点、四角色浏览器及收尾均通过；归档已接收、绿色源码已推进，completed/closed=true。主线尚未全部完成。

新AX任务在原提交事务中冻结原Trace、执行模式及预期Agent/模型事件数。原创建回执、专属运行登记与派发准入确认后，在原派发事务冻结实例编号、端点、UID和模板；不改写旧任务。ECHO/FAIL预期1条ax.task、0模型事件，LLM预期1条ax.task和1条同身份GATEWAY事件。新的Runner接受平台Trace；旧请求省略字段的规范化JSON保持，旧started/UNKNOWN不重执行。观测Trace不匹配单列，不改写原业务回执。

新实验复用原试验Trace与诊断策略的冻结身份，每试验预期1条agent.experiment及1条GATEWAY事件；不把OTLP MODEL当网关事实。Runner配置在原profileDigest之外声明EAP_OPTIONAL_DIAGNOSTIC_V1；只有冻结配置声明支持时平台才发送emitDiagnostic，新生产者在原持久回执之后与核心Span同一有界请求发送最多1条最小agent.diagnostic，不含正文、资源详情或风险声明。省略标志的旧实验输入保持原规范化JSON，不重发旧Span。

新工作流在原提交事务冻结已解析控制路径和每个START/DECISION/END的0Agent/0模型预期；控制节点完成日志仅在原owner/epoch进度CAS成功的同一事务记录。没有到达的后续节点保留分母，显示未执行或待执行。已收证据的完整性与业务SUCCEEDED/FAILED/UNKNOWN分开，失败和未知不能从统计中排除。

V70仅新建observation_operations及observation_operation_events，不回填旧事实。登记与阶段日志仅固定事件/原因码和最小责任版本；事务提交后输出结构化服务端日志，以原Trace关联。窗口指标从持久原登记和同身份证据派生，标签只有固定入口；不把Trace、任务键、完整主体放入聚合标签。不保存异常、供应商响应、提示词、样本、输出、凭证。费用只关联原预占状态与可用usage；缺失不补零，不退款或重新领取预算。

只读GET /api/observability/coverage?minutes=1440与/api/observability/operations/{id}复用中央PlatformPermission.VIEW_CONSOLE及生产Agent可见性。窗口1–10080分钟，最多10000个登记，超出要求缩短窗口；指标统计窗口全部登记，最近明细最多100条并标明截断。新页面“证据覆盖与定位”分别显示登记、缺口、未确认、原业务状态，详情关联Trace、结构化事件、费用与原入口，不发起任何业务写入。

通用任务、对话和旧外部入口尚无派发前可信登记，分母为null，不能用已收Span数推定覆盖率。这里完成的是上述新入口的基础关联，不代表平台日志全文搜索、全入口登记、告警或生产规模验收。没有存储/时延对照前不声称采样节省或性能收益。

## 本轮真实验收边界

先核对原计划128次中已消费123次及实时共享账本；最多新4次、每次输出上限128，总新输出上限512，不扩旧cap/revision、不复活旧UID或重放旧意图。新独立公开样例两臂：100%策略一次新实验2次，0%策略另一次新实验2次；失败/UNKNOWN即停止，不换键补发。结束后只发布新版本恢复100%，原版本/决定不覆盖。校验两实验原冻结版本、核心Agent/GATEWAY及原费用、额外诊断保留/过滤和新策略不追改旧决定。若共享预算或准入不满足，真实模型验证保持未完成，独立0模型AX ECHO/FAIL与控制节点验证仍继续。

使用新的独立Agent/专属Actor，所有模型及工具能力受原准入约束。AX回显/失败和控制工作流不调用模型/工具；真实登录四角色只读页面桌面/手机，并核对无业务写入。最终只清理本轮测试Job/Pod，新实例原UID休眠并撤销专属身份，保留全部原实验/任务/费用与失败记录。全部依赖、测试、构建、部署和浏览器远程，通过原n1.sh；不commit/push。

## 验收结果

候选725项源码、相对715基线19个路径。最终v2后端专项130项/9类、全量675项/99类、打包均通过；前端224项/类型/构建通过；通用Runner普通和竞态各35顶层/63含子项、AX各21项及两个编译通过。首版专项130项/4错误原样保留，根因是未确认计数的Java条件表达式隐式拆箱null；显式保留可空计数后只重跑后端，前端/Runner逐项源码一致并复用原绿证据。

四个镜像的manifest/config/末层及测试jar、前端dist、Runner二进制逐字核对通过；CAS更新两个应用镜像与两个未来Runner默认，健康UP、实际imageID、MySQL70、前端版本确认。两个Runner最初打包因不存在的Alpine标签MANIFEST_UNKNOWN失败，改用原固定Runner镜像基础层、保留两失败Job和日志后完成打包；没有改测试源码或重跑应用测试。

新Agent63/实例eap-agents/a63-v1-172e155d84de，UID6c12e81a-c4e1-4b6c-a482-72e41a7f771d。两次原提交：experiment47在v4/10000下两条核心Agent与GATEWAY齐全、诊断2保留；experiment48在v5/0下核心仍齐全、诊断2过滤；随后发布v6/10000恢复默认。新AX ECHO/FAIL各1个，START/END控制节点各1个，四个零模型登记证据齐全；FAIL业务仍FAILED。8个新登记全部COMPLETE，旧实验46不回填，原版本和决定不追改。

实际新4次/218输入/256输出，全部SETTLED、预留0、工具新增0，低于512输出预登记上限。PLATFORM仅按实际新增4次/256输出，旧/shared cap和revision不变；原总计划累计127次/11371输出，128次与32768输出上限不扩大，差额不作为新授权额度。三个新实例原UID已休眠、专属身份已撤销，历史覆盖保持。33表2703旧行逐行核对，只有PLATFORM真实费用/时间及新工作流调度序号按合同允许变化，其他旧列保持；4个原策略版本、两atespace旧UID/模板/状态、认证和配送配置保护通过。

四角色真实PKCE API权限一致，正文仍200/403。桌面1500px和手机390px共8截图：原Trace、提交事件、SETTLED费用关联，业务FAILED与观测COMPLETE分列，未登记入口未确认；打开/关闭/刷新正常，页面错误、失败API及业务写入均0。12个测试/构建Job及14个Pod按冻结UID清理、剩余0，全部可取得日志已保存扫描。

浏览器首安装180秒超时，退出后的重定向日志未取得，保留失败Pod状态及空stdout；换USTC包源后就绪。首页面脚本匹配两个抽屉，改为语义名称定位后确认真实UI问题：顶部栏遮挡证据抽屉标题/关闭按钮。只在新页面增加append-to-body和z-index=4000，与既有AX详情一致；原截图及两失败报告保留，前端v3的224项/类型/构建和四角色浏览器复验通过，后端/两个Runner源码不变，不重跑模型。过早构建在原测试未结束时被门禁拒绝，未创建镜像Job；待同Job完成后接续构建，没有重测。

最终镜像：后端1911386d…、前端250841bf…、未来通用Runner2146291f…、AX Runner3e4e0307…，均按digest固定。完整值见[后端及默认值补丁](../deploy/substrate-poc/observation-coverage-backend.json)与[前端补丁](../deploy/substrate-poc/observation-coverage-frontend.json)。仅CAS这四项；旧Actor模板、optimizer及其他环境保持。

[验收索引](evidence/eap-observation-coverage-20261009/index.html)、[最终接收回执](evidence/eap-observation-coverage-20261009-final.json)及[原节点最终回执](evidence/eap-observation-coverage-20261009-remote-final.json)：归档3540680bytes/SHA256 ac2537e58921584875a50bc03cd160640efc0f005b167d0cd86d2ed0d27c9ffd，439文件摘要、438索引链接、19源副本和725完整源核对通过。接收后才精确19路径推进/root/eap-src至725；节点/root/eap-t7d-out-v2/final-completion.json completed=true/closed=true，原回执3284bytes/SHA256 9007f26b3dd718c2eb9072c62c0bb0f85d23045485aa30ff1870dc99ee3db191。最终源manifest SHA256 9b4b5d6238422f726627f84a4cc154333b9e6cebdf74d542e4e56103e10e707d，与归档及本地一致。归档保留导出时推进待完成状态，最终回执独立保存，不回写原归档。

全部依赖、格式化、测试、编译、构建、部署和浏览器远程；本机只做文本、摘要与脱敏接收。实际9Secret原文/编码仅节点RAM扫描，源/产物/可取得日志/归档扫描PASS；不commit/push，AGENTS原摘要保持。不要重跑已删除Job、实验/AX提交、策略发布或export，不重放旧意图补观测。通用任务/对话/旧外部入口、AX LLM真实端到端、独立Prometheus采集、全文日志搜索、告警、生产负载对照及整条主线仍未完成。


## 2026-10-11 T7-E续接

[工作流模型任务与对话原登记](RUNTIME_OBSERVATION_COVERAGE.md)已完成隔离验证及归档/749绿色源推进。远程后端专项110项/8类、全量850项/103类及打包通过；前端224项及类型/生产构建通过；Runner普通/竞态各37顶层/73含子项及编译通过。全部0失败/错误/跳过。隔离MySQL8.4.11/0900ai_ci/RR真实Spring20项/2类通过，包括原团队HOLDOUT/费用闭环10项和新任务/对话实际HTTP客户端10项。后者Runner为loopback夹具、身份为Mock，Go测试也不是实际Actor/真实模型或Pod崩溃验收。 默认关闭、未部署，生产仍为本文T7-D范围；历史入口不回填。实际团队任务/新预算及业务工具/旧消费者范围尚待答复，整条主线未完成。
