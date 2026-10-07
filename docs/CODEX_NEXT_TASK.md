# 当前主线：调度、Agent性能调优、权限治理；原操作核验阶段已验收（2026-10-07）

### Codex 进展：工具能力合同、原操作只读核验与完整旧身份清单（2026-10-07）

**始终聚焦调度、Agent性能调优、权限治理三条主线；系统主线尚未全部完成。** 用户最新“继续完成”授权实施，随后强调持续聚焦主线。本阶段完成R1扩展清单、R2受控合同能力表和R3原操作查询边界，不等于旧身份迁移完成、全部业务工具适配或通用恢复。下一步继续持久步骤责任/执行侧隔离与工具适配、旧凭证消费者核对，再推进确定性公平/老化/阶段画像与独立任务上的真实Agent调优；分级观测和联合策略沿主线保留。

- 新工具能力表按名称和种类精确区分可信存储合同、不支持和尚未核实；MCP或策略白名单不能证明幂等、查询、取消、补偿及提交屏障。控制台查询原根/原操作，显示原效果、原根预算和观察时间，超出最近30条也可查；失败、记录缺失、PREPARED/UNKNOWN不变为未提交。查询不执行、不领取/退还预算、不续授权，也不自动改写工作流终态。权限仍来自PlatformPermission.VIEW_CONSOLE及/me，无新角色解析。合同docs/TOOL_CONTRACT_RECOVERY.md。
- 全局58模板与五作用域逐一枚举一致，39模板有旧凭证引用名（任务30/网关28/观测21/配对9，存在重叠）；9模板在当前EAP实例/发布记录无引用，不等于全球无消费者。61纳管盘点项/27部署/22旧部署；环境值只留节点RAM，输出名字、UID、公开镜像和配置摘要。命令、卷内容、golden快照、外部消费者和上游分页完整性未证明，因此共享凭证未退役、旧模板未迁移。
- 精确7源码/691摘要及实际Secret节点内存扫描PASS。远程定向59项/4类、完整540项/91类零失败/错误/跳过并打包；前端198项零失败/跳过、类型及生产构建通过。覆盖丢弃执行返回后查询原效果、撤销后历史查询、持久UNKNOWN不重试、跨范围拒绝、31条之后原操作查询、同名MCP无可信能力和四角色/匿名权限。Runner和原生未变，沿此前证据，不称本轮重测全部底层组件。
- 仅更新backend/frontend固定镜像，健康、实际imageID与公开版本PASS。backend 10.186.133.66:5001/eap-backend@sha256:fdd52528e0c8d1857167f551915713a59b2e22cc6539a0efae5f70fbe4649f4d，PodUID 86033343-97a6-4578-a617-d9edabc64542；frontend 10.186.133.66:5001/eap-frontend@sha256:49a8b4009af5827e9e44dea20e6ac65b079134b7ed863d577b076d40648ae0e8，PodUID 20307d83-eaf7-4dff-a69f-9c009fe46e82；buildId 2026-10-07T03:21:37.704Z。认证Pod、配送Secret/900秒、环境和原生/Runner镜像保持，不整份apply。
- 独立IMAGE Agent53，原UID cdce6f92-6d9b-499f-b94e-b09ed9775a19，原发布 dep-31001b83-63a9-4a28-9488-1c17e6d4c422；MODEL_CALLS/OUTPUT_TOKENS上限均0。验收客户端持实际专属机器凭证直接调用受控工具一次，主动丢弃响应体，再由四角色查询原O a6f96201-fb67-4aff-b97c-e69197374abe，确认同一effectId 9fcfd346-f121-448d-bb60-2471c3f4ea88、计数1。不是实际网络分区/控制器崩溃，也不是该原生程序自行发起业务。最后原UID仍休眠，身份和根撤销，查询原终态保持，根cap1/used1/reserved0，模型新增0。
- 原60账本账户（平台TOOL_CLAIMS合法+1及更新时间除外）、39预占、252关联项、39结算逐行保持；原27工作流/13任务/27部署、7范围授权/16工具操作/6计数、19连接身份元数据/58UID绑定的冻结公开元数据与摘要保持。两个UNKNOWN物理行12/20保持，未重放。原各Agent模型观测条数保持；总观测表60条与账本MODEL_CALLS已用33是不同统计，history-proof原字段originalCalls按观测行计数，不作为60次真实供应商调用证明。平台OUTPUT_TOKENS仍1904，没有清零原消费。
- 四角色1500/390px浏览器通过，runId 3a40f1e8-be6f-4899-b334-b0708522bba1，16截图、业务写入0、页面异常0、失败API0；实际查看admin桌面能力表和手机原操作，长编号换行可读。证据K8Substrate artifacts/eap-tool-contract-recovery-20261007/index.html，tar.gz3400870bytes、SHA25643e3b33dfb691d67bf2bbc1919e15a9b3ee9f2221a0e0b913a1489cdc4a6a1ff，247文件摘要/16截图本地核对PASS。仅按冻结UID清理4测试/构建Job和5测试Pod、剩余0，保护检查PASS；原归档不修改。节点completed=true，导出后691精确源已更新/root/eap-src；禁止重跑effect/publish或旧UNKNOWN。
- 盘点首轮env列表当map、授权冻结首轮MySQL批处理转义嵌套JSON，均只读失败、未写基线，修正后完成。起测脚本遗留t5d2-v1标签及RED措辞，仅执行两个绿色测试Job，无red回归；清理精确名称/UID避免误选，诊断随归档。全部依赖/测试/构建/部署/浏览器远程，本机只文本、摘要、脱敏文件和看图。旧视频未重录；没有稳定Agent调优收益，KEEP_BASELINE不变。

沿用户此前“别忘记提交gitee”授权正常工程commit/push。只选择本轮源码与工程文档；AGENTS、BP、私人原申请书和其他研究不提交。PROJECT_MEMORY工作区只追加，提交内容是原HEAD加本工程条目，保留其他线程所有未提交记忆。

---

# 当前任务：新镜像/源码身份入口已验收；核读项目方法，继续旧身份迁移与T6–T8（2026-10-07）

### Codex 进展：T5-D2新镜像/源码入口专属身份已部署、有界验收与导出通过（2026-10-07）

**主线尚未全部完成。** 新发布 RUNNER/IMAGE/SOURCE 统一专属实例身份；旧共享凭证与模板尚未迁移，不能称T5全部完成。继续T5旧身份逐批迁移、T6通用恢复/发布取消、T7分级观测、T8策略，以及公平/老化/阶段画像和真实Agent调优收益验证。用户最新要求继续核读字节、华为、联通申请书，改进建议按原文与当前代码/证据核对；研究不能自动触发业务实施。

- 合同docs/CUSTOM_RUNTIME_IDENTITY.md。新IMAGE/SOURCE冻结PER_INSTANCE_V1与公钥，源码先构建并固定摘要，原生Create回执UID确认后配送/确认才可运行；不注入共享机器凭证、不因失败回退。配送/派发配置缺失或自定义端口非80在产生新副作用前拒绝。同一旧发布请求优先读取原记录，旧端口/模板/身份不迁移，异参409。前端固定80、说明协议要求、区分旧身份待迁移，镜像6/源码7阶段；权限仍从PlatformPermission及/me读取。
- 只读盘点47个可见Agent、24部署、56登记、59原生实例，范围已连接/未截断；22旧发布为RUNNER13/SOURCE6/IMAGE3，包含失败历史，不等同在线实例。24可读取引用模板含至少一个旧任务/网关/观测共享凭证名；未涵盖其他配对凭证及无实例引用的遗留模板。盘点仅环境名/公开元数据/摘要，不保存凭证值。
- 精确7源码变更、691源摘要与实际Secret内存扫描PASS。远程旧实现20项产生3失败/2错误，定位5个新合同缺口；新增第6项禁用派发原已拒绝。修正版定向44项/5类、完整529项/91类零失败/错误/跳过并打包通过，前端192项零失败/跳过、类型检查/生产构建通过。未变Runner与原生服务沿前阶段验证，不称本次重新全部测试。
- 只更新后端/前端镜像并验证实际imageID。backend固定10.186.133.66:5001/eap-backend@sha256:1a8e7e2c95eff77eeac0cb416c98a6fa667f0e7ef9e59f6b6ab9b962d24dfd8f，PodUIDdf5b1200-c30f-4bc9-9ebe-938039d9ed67；frontend固定10.186.133.66:5001/eap-frontend@sha256:468689eab2b1a481e8bf1d209adad78602abf7f28111ba5543c206f338cdd8d9，PodUID2bf54081-8631-4713-952a-0b3d07fa13c0。buildId=2026-10-07T01:50:52.323Z，健康与身份/构建能力PASS。认证PodUID、不可变配送Secret及默认900秒、后端环境、任务Runner/通用Runner/原生镜像保持。公开PoC清单只同步两镜像，不整份apply。
- 独立IMAGE Agent51、SOURCE Agent52，模型/输出总预算均0。两个原工作流成功，各产生一个工具存储确认的受控计数效果，root TOOL_CLAIMS cap1/used1/reserved0；模型新增0。跨实例凭证401，同一发布/创建请求幂等，异参409，四角色能力对应200/403。最终两个原UID休眠、身份及根授权REVOKED、旧凭证401；原创建请求409/原生版本不变。旧24部署配置/状态/实例键、两个EXECUTION_UNKNOWN及原模型记录摘要保持。
- SOURCE首次错误CGI路径导致v1构建404/失败/无实例，显式更正仓库URL形成新v2；随后固定40位提交构建失败，因为独立夹具Git未允许精确Tip SHA获取。仅该新裸仓库开启uploadpack.allowTipSHA1InWant，保留两次原失败日志，再经既有API重试同v2/同提交，成功构建并接入原唯一实例。没有改全局Git配置、降低固定提交要求或另建v3。公开夹具commit35112d3570e91dd5c73d244d96e83fa5f62d907c；旧/新回执及诊断全部保留。
- 4角色1500/390px浏览器PASS，runId0d217814-2460-46f0-bf59-ae718b868d57，22截图、业务写入0、页面异常0、失败API0；已查看admin源码桌面/手机/表单，阶段和长编号换行可读。没有重录旧视频。
- 预算原始整体看板只保存摘要，不能声称全看板字节未变。两次新工具结算使平台TOOL_CLAIMS合法增加2及更新时间改变；MODEL_CALLS仍33/OUTPUT_TOKENS仍1904，无新增模型。old-budget-proof.json原false结果与budget-delta-proof.json语义PASS一并保留；未清零原消费。
- 脱敏证据K8Substrate artifacts/eap-custom-runtime-identity-20261007/index.html，同名tar.gz2461767bytes、SHA256b4f9092ecda6b40626bd568c4bf155a06fa16bd0b803c4c218fa1be86062dac6，260文件摘要/22截图本地核对PASS。导出后精确7变更镜像/root/eap-src，691源核对一致；节点eap-t5d2-evidence.json completed=true。禁止重跑prepare/publish/workflow或旧UNKNOWN原意图。仅清理本轮7测试Job/8测试Pod、按冻结UID前置条件，原实例与身份撤销保留；辅助清理首次误拒构建Pod后缀，冻结计划与诊断保留，修正后7/8全部不存在、保护检查PASS。
- 本地访问隧道复用批准桥接脚本，绑定127.0.0.1:5173/8180，前端/认证HTTP200；现隧道会话保持。偶发延迟仍需重试，不称跳板故障已彻底解决。全部编译/测试/构建/部署/浏览器远程，本地仅文本、摘要和脱敏证据。内部HTTP PoC不等于生产mTLS、原生分配栅栏或完整灾备。

用户“别忘记提交gitee”授权工程正常commit/push。提交只选本轮精确源码与工程文档；不含BP、其他研究、AGENTS或私有申请书。PROJECT_MEMORY工作区只追加，提交使用原HEAD加本工程条目，保护其他线程未提交记录。

---

# 当前任务：T5-D1已验收，继续T5-D2剩余入口与旧共享凭证迁移（2026-10-07）

### Codex 进展：T5-D1任务入口专属身份已部署、真实验收与证据导出通过（2026-10-07）

**主线尚未全部完成。** 本轮完成新任务入口的有界实例身份阶段；T5其他入口和旧共享凭证迁移、T6通用恢复/发布取消、T7分级观测、T8策略，以及调度公平/老化/阶段画像继续待办。没有确认Agent调优稳定收益。当前状态清单见docs/MAINLINE_STATUS.md，下一步T5-D2先盘点剩余入口与旧共享凭证，逐批设计迁移验收；不能改写已完成/UNKNOWN原意图或预算。

- 修复原生任务登记被通用心跳清理误标OFFLINE：批量JPQL排除SUBSTRATE_TASK，缺失/陈旧心跳、普通HTTP过期与显式OFFLINE边界覆盖。正确新回归在旧实现3项/1失败，V8夹具自动补心跳失败保留；V9定向62项/7类、完整523项/91类零失败、错误、跳过并打包通过。源码691摘要与实际Secret内存扫描PASS，精确2文件安装。前端190/类型/构建、任务执行器race12顶层、原生68顶层/135含子项沿未变源码保持有效，不称本次重新全部测试。
- 当前backend固定10.186.133.66:5001/eap-backend@sha256:5a989a8bea5b7b2c91da0be6a34da0bf5d83c40369168400ebbdb001f8c42f00，实际PodUID a00021b9-048d-42a8-86d1-e7b018f24190；其余4镜像保持本阶段首次发布摘要，V63成功，新任务身份能力开启。认证持久Pod原UID、旧emptyDir replicas0、不可变配送Secret及默认900秒、旧任务Runner和通用Runner不变。公开清单仅同步当前摘要与身份配置，没有整份应用历史清单。
- 独立Agent50/模型总上限1/输出1024。回显原任务ax-82cb1041-e7f2-4fb3-aafd-ce2f6fa1df51、UID d7dd465c-873b-405f-a9bc-87cee1d79659；模型原任务ax-633e5361-a1f9-497c-a84f-992e8169b895、UID ded65903-7752-4cca-ac9a-0f6d2776a672。模型恰好1次，SETTLED、根used1/reserved0/cap1；实际网关、预算、任务回执和Agent Span的隔离Trace一致，runtimeTraceId保留原执行器编号，traceId已被后端隔离，不二次散列。
- 原回显真实空闲跨900秒自然到期、旧凭证401、UID/原生版本/身份周期不变。仅本轮确证误清理的一条登记按原UID/时戳/批准/租约CAS修ONLINE并同事务审计RECONCILE_TASK_LEASE_BUG，未重置凭证/预算，任意OFFLINE不恢复。修复后首次resume400产生原待配送c3163618-9f0f-453c-bd63-e1b37166db4d；客体readyz200后仅核对同一操作成功，首400原因没有栈证据，不猜测。周期不变、serial1→2，旧凭证401；原Task24h与旧时区错误首连接缩短16h期限均保持，不倒填延长。
- 原所有者CLI恢复同UID/原快照，当前凭证读取旧客体401；原任务重装同一当前租约后读取原SUCCEEDED回执200，再休眠。回执SHA保持、没有业务重派、没有模型消费。只证明本轮原实例快照重装，不表示EAP通用恢复/T6完成。
- Workflow18原运行wf-exec-a9c27901-59c7-488c-bd6b-f1a91efbde97成功；first UID7a1dec03-7d1e-4b8d-96f2-a623a5d19785，second UID9925941b-8b82-47da-b5c9-da04ef83df22，两个原任务实际串行传递第一步回执，模型新增0。缺Task头/跨任务/终态/不同调用标识/人工凭证/不支持入口被拒绝；原提交重复返回同Taskkey，改参数409；跨凭证401。共4独立UID且都跨180秒心跳超时仍ONLINE，最终SUSPENDED/REVOKED，原恢复409且原生version不变，模型1/预算不清零；旧Workflow15原EXECUTION_UNKNOWN保留。
- 四角色真实API和1500/390px浏览器通过，runId feb59e41-817f-42ff-9fef-e627f5b00d2e，20截图、业务写入0、页面异常0、失败API0。已查看admin桌面/手机/公开配置，长UID及公钥换行，无横裁。模板验收初次误排原生公开AX_TASK_YAML、卷名identity与实际eap-identity不同，按原源码合同修正，未改应用绕断言。收尾首门禁因浏览器脚本写旧状态覆盖工作流汇总标记被阻断，业务回执未变；只读核对原运行后恢复证据，非阻塞阶段锁和原子保存使写证据阶段串行。失败史留acceptance-diagnostics.json。
- 归档artifacts/eap-task-identity-20261006/index.html及同名tar.gz，7993584bytes，SHA2567229a231ac4b842b9b8396bf7d725cae8aa3dd73f1cf6637e0424422296376e5；本地1085文件摘要、25链接、20截图核验一致，实际Secret及原/续期机器凭证内存扫描PASS，合成日志凭证2处导出脱敏且原SHA保留。导出最初误估39变更被门禁挡住；固定基线676→V5变36，V7新增投影及原未变Repository共2，V9共2，最终精确40路径/691源码核对通过，不改测试源码凑数。通过后已镜像/root/eap-src691；节点eap-t5d-evidence.json completed=true及所有阶段键保留，禁止重跑prepare/submit/workflow或换意图重放。
- 已正常推送工程底座38300900ad93806bb8acf58a76ba6d0861bc419c及时区修复fcf3904ecc2fbf82b49dd8c77cc0b6e5fe5c320f；本轮另提交心跳修复和工程交接。用户“别忘记提交gitee”授权正常commit/push；BP、其他研究和AGENTS不带入，PROJECT_MEMORY工作区只追加，提交只用原HEAD加本工程条目。全部依赖/测试/构建/部署/浏览器在远程，本机仅文本/摘要/脱敏证据；旧演示视频未重录，内部PoC不等于生产mTLS/原生分配栅栏/完整灾备。

---

# Current task: T5-D1 timezone fix deployed; native task heartbeat recovery pending (2026-10-07)

### Codex 进展：T5-D1已提交并部署，时区修复上线、原实例恢复仍待验收（2026-10-07）

主线尚未全部完成。下一步继续当前任务身份阶段；T5其他入口和旧共享凭证迁移、T6通用恢复/发布取消、T7分级观测、T8策略及调度公平/老化/画像保持待办，没有Agent调优稳定收益证据。

- 用户“别忘记提交gitee”授权正常提交推送，原工程提交38300900ad93806bb8acf58a76ba6d0861bc419c已核对Gitee远端。提交690精确验证源码与工程文档，BP/其他研究/AGENTS未带入；PROJECT_MEMORY提交仅HEAD加工程条目，本地其他记录保留。
- 已部署后端/前端/任务执行器/原生任务服务及控制器五个固定digest，V63成功；新任务身份模式开启，补齐eap-ax可观察空间后真实capabilities enabled=true/instanceIdentity=true。持久认证原UID、不可变配送Secret及默认900秒、旧任务Runner和通用Runner保持。
- 独立Agent50、模型总预算1/输出1024；仅提交原回显ax-82cb1041-e7f2-4fb3-aafd-ce2f6fa1df51，UID d7dd465c-873b-405f-a9bc-87cee1d79659，SUCCEEDED/SUSPENDED、模型0。短凭证真实空闲跨900秒自然到期，原metadata/UID/版本不变、旧凭证401。
- 实际MySQL JDBC Timestamp读取较现有Hibernate Instant映射少8小时，导致任务UI提前EXPIRED，原连接授权首次缩短为16小时。首版UTC Calendar在H2三项回归失败、未部署；旧源码加H2非UTC会话14项PASS，不能称红测复现。实际MySQL只读驱动探测PASS、确认8小时偏移，原数据没有写。
- 最终时区修复使用AxTaskIdentity类型化JPA投影及现有连接实体映射，不改全库时区/历史时间。远程定向59/6及完整522/91零失败、错误、跳过，打包通过；691源码摘要与实际Secret内存扫描PASS，精确6文件安装。只更新后端至10.186.133.66:5001/eap-backend@sha256:c8fa8cb5cb46c355797a8791966e4aee32a94b33e46b59baabf93375940e3865，实际Pod UID3b1590cb-8c6c-476b-84b2-b931e8947b43，健康与能力通过；原任务和连接EXPIRED一致，原UID、Task24小时及连接原16小时期限保持。
- 原实例恢复两次400，没有生成新的待配送操作，未执行模型。只读诊断确认登记被旧心跳清理在16:01:20.988395误标OFFLINE；批量JPQL未排除SUBSTRATE_TASK。修复该排除项的V8新增回归有1失败，原因是夹具初次保存自动补心跳，HTTP“缺心跳”断言没有测到NULL；V9保存后明确清空，再做缺失/陈旧心跳、旧原生协议、普通HTTP和显式OFFLINE边界回归。V9测试进行中、尚未安装/部署；不把首版失败说成全通过。
- 为避免占用运行槽，仅对本轮已确认终态原实例由所有者CLI恢复原快照到休眠，UID和原externalSnapshot保持，原消费0。修复发布后需审计/CAS核对本轮唯一误清理登记，不恢复任意OFFLINE，不延长旧批准或换任务冒充续期；原实例恢复、真实模型一次、串行工作流、跨任务拒绝、四角色/手机浏览器及最终撤销仍未完成。
- 原690版本前端190/类型/构建、任务执行器race12顶层和原生完整68顶层/135含子项结果沿未变源码保持有效，不称本次重新全部测试。旧T4 Workflow15原EXECUTION_UNKNOWN、旧预算、模板及所有完成标记不重放。全部测试/构建/部署/浏览器远程；节点状态eap-t5d-evidence.json completed未置true，绿色/root/eap-src仍T5-C。

---

# Current task: T5-D1 remote regression passed; deployment and live acceptance pending (2026-10-06)


### Codex 进展：T5-D1任务实例身份远程回归通过，待部署验收与Gitee提交（2026-10-06）

用户明确要求“别忘记提交gitee”，本轮据此授权正常提交并推送当前工程分支，不再沿用此前未授权commit/push的限制。主线尚未全部完成。

- T5-D1新任务冻结原生Create回执UID/模板、固定镜像、公钥、网关和原创建请求；底层Redis在Create前保存不可重试意图，回执丢失或UID变化不按名称接管、不重建。旧任务保留LEGACY_SHARED，旧模板、意图、预算和快照不迁移。公开补丁与源摘要在runtime/ax-integration，合同docs/TASK_RUNTIME_IDENTITY.md。
- 新任务执行器仅持有专属短凭据，复用签名挑战、内存RSA加密配送和HMAC确认；UID复核及配送确认后才持久化RUNNING并POST一次，其后只查询原回执。普通登记/心跳、人工批准、通用控制及其他工具入口不能绕过任务身份。
- 模型与观测须绑定原RUNNING任务、Agent、实例UID和模板；缺任务头、跨任务、终态、变更调用标识及共享网关借用新任务均拒绝。预算继续使用服务端验证的Task头，Trace变化不产生新额度。按需重装/续期不重置身份周期、原授权、工具根或预算；默认900秒，原批准最多24小时，撤销永久取消原任务批准。
- 最终远程后端定向58项/6类、完整521项/91类，零失败/错误/跳过，打包通过；前端190项、类型检查和生产构建通过。任务执行器完整-race12顶层及生产编译通过；底层新合同-race12顶层/26含子项，完整68顶层/135含子项及server/controller编译通过。Go无测试包的skip事件不计作测试用例通过。
- 真实合成签名加密HTTP、独立Redis并发、实际MockMvc身份/预算拒绝及真实Go protobuf回执跨语言解析均有覆盖；不把模拟原生API回归当真实集群全链路。v2测试桩错误、v3完整回归主动中断、v4真实HTTP拒绝仍显示UNKNOWN的失败记录保留；v5将明确前置身份拒绝标为REJECTED并重新完整回归。
- 690候选源码摘要核对及集群实际Secret原文/编码内存扫描通过。本轮36文件精确补丁包含14新文件；Gitee提交同时包含此前T1–T5-C已经验证且新代码依赖的工程底座，按源码清单和工程文档白名单选择，保护其他线程BP及研究材料。PROJECT_MEMORY本地只追加；提交该文件只使用原HEAD记录加本工程条目，其他未提交记录留在工作区。
- 尚未部署新镜像、运行V63迁移或创建真实验收任务。下一步固定五个镜像digest、先发布兼容底层并冻结旧任务Runner，再部署后端并启用新任务模式；之后有界ECHO/单次模型/串行工作流、实际预算/Trace、到期/撤销/恢复及四角色桌面/手机验收。不能称T5-D1真实验收已完成。
- 只读预检首次失败因认证Pod匹配仍用旧app=keycloak标签，实际持久实例app=keycloak-persistent，原UID bfa818d4-6f6d-4d03-b974-7f6ac6179dcb且Ready。配送Secret实际immutable=true；没有变更密钥或认证资源来满足断言。
- T5其他入口/旧共享凭据迁移、T6通用恢复、T7分级观测、T8策略及调度公平/老化/画像仍待，没有Agent调优稳定收益证据，旧演示视频未重录。旧T4 Workflow15原UNKNOWN及所有已完成验收不重放。全部测试构建部署远程，本机只文本/摘要/接收证据。

---

# 当前任务：T5-C平台创建专属身份已验收，继续T5-D其他入口与旧凭证迁移（2026-10-06）

### Codex 进展：T5-C平台创建与专属身份已验收（2026-10-06）

**主线尚未全部完成。下一项 T5-D：任务网络及其他执行入口的统一实例身份、旧共享凭证迁移；随后保留 T6 通用恢复/发布取消、T7 分级观测、T8 知识/模型/工作流策略，以及调度公平/老化/阶段画像。没有确认 Agent 调优稳定收益，字节/联通/华为方法仍是设计依据。** 本轮完成新平台 RUNNER 创建链路及工作流、对话、提示词实验入口，不是全部 T5 迁移。

- 合同 docs/PLATFORM_RUNTIME_IDENTITY.md；V62 保存平台 UID/模板与固定创建请求。Create 回执 UID 才能绑定所有权，未知结果保存原 attempted 并拒绝凭名接管/再建；普通注册/心跳不能写身份。新 RUNNER 版本冻结 PER_INSTANCE_V1、公钥、固定镜像和只读 atespace/name/uid 目录，不含共享机器/供应商/控制账号凭证。旧模板/快照不改；旧 RUNNER/IMAGE/SOURCE 首次创建保留旧路径，额外会话需新专属身份版本，前端禁用并解释。
- 平台创建最多批准24小时接入授权、短凭证默认900秒，复用加密配送/HMAC确认；READY仅在确认后。相同创建请求固定原版本/名称/UID，撤销后永久取消；不完整身份/OFFLINE/UID或模板变化不回退共享凭证。工作流、对话、实验派发前按需复核/安装，空闲不唤醒、不续期，不延长授权或重置工具根/操作/效果/预算。
- 真实 Agent49，原主实例 eap-agents/a49-v1-fa986da51fdc UID2b569b00-eb20-4dff-9968-5cf042f65e9e、额外实例 eap-agents/a49-v1-f782253f75de UID70da5524-acc3-483a-9b62-758244a8da55。原两实例实际空闲跨900秒自然到期，原生版本/短凭证不变、旧凭证401；修复后使用两个原创建请求续期，UID及身份周期不变、恢复休眠，恰好两个实例，跨实例凭证401。不是换请求创建新实例冒充续期。
- Workflow17 原运行 wf-exec-a142628b-d8e1-4b95-8b5b-cb57062ab18f SUCCEEDED，受控计数1/效果1，存储效果 10261c2a-4bd9-424e-9870-bb297607c1af 与工作流回执一致，模型0；原v1一次对话和修正版v2公开单样本对照实验加上保留的首次预算未关联实验2次，共5次模型调用；原聊天网关/Agent Span关联，修复后实验两网关Trace与冻结试验及预算关联。实验只验证身份执行入口，不证明优化收益。
- 实验首轮原v1配置读取400；只读诊断确认Runner404/experiments unavailable，工具标志错误关闭独立实验端点，原一次对话/模型消费保留，未提交实验。修正版允许两入口共存，实验不读会话历史/不发工具定义，工具调用回执UNKNOWN、不执行、不重发；红测复现后远程-race通过。显式发布v2，一个新UID 94748c6a-9a26-41f1-a16c-7dc9d6830873，实例 eap-agents/a49-v2-8bcf967daa8e，冻结新Runner；原两v1模板和UID不变、发布v2后原额外请求仍返回v1，恰好三个实例。
- 最终远程后端完整498项/89类，零失败/错误/跳过；v12身份/追踪/预算相关61项、之前v11协议57项回归通过；前端189项、类型及生产构建通过。676源码摘要一致，相对固定T5-B 671文件变更43，Runner修受控任务工具与独立实验共存，本轮红测复现、完整-race23顶层/36含子用例及生产编译通过；随后v12追踪预算修复后完整后端498/89重跑通过，前端源码未再变化，原189/类型/构建保持有效。最终源 backend /root/eap-t5c-src-v12、frontend /root/eap-t5c-frontend-v6。
- 历史失败保留：v1编译/依赖、v2启动API版本/编译、v3–v5回归/编译与中断、v6完整权限测试空请求不符、v7完整485通过。真实额外创建重放暴露原生策略更新缺UID/version，原请求/UID保留；v8协议修复编译误用repository.status；v9原有工具效果回归一次REJECTED原因未记录，不反推原因。v10补非秘密诊断、17项通过，完整回归因确认的合法UUID数字片段PHONE/PII误判修复中断。v11仅对服务端验证的受控计数标识符按合同元数据检查，普通工具API仍检查全参数，无调用方豁免；原指纹/预算/效果与UNKNOWN保持。协议读取原策略UID/version、同规则不更新、改变带CAS、冲突不盲重试。
- v2首次实验14 COMPLETED、两调用实际SETTLED但experiment_id为空；加上原聊天共3条旧结算行32/33/34保留，不倒填、清零或重放。追踪校验误把仅有网关记录的实验入口要求为聊天AGENT Span；正确核对原聊天历史Span。实际发现实例Trace隔离转换断开预算归属，v12只对服务端已签发且Agent/实例UID/模板/端点匹配的实验编号保留原编号，其他继续实例隔离，Span ID仍隔离、跨链接仍剥除；同Agent其他实例/重建UID拒绝，已结束试验不能降为普通模型调用。四项新集成及61项相关回归通过。修复后新公开样本原实验 15 的两模型意图均有实验预算SETTLED、used2/reserved0，网关Trace与冻结试验一致，旧/新完成原意图409、跨实例401，新增模型0；总模型5。实验协议当前不发AGENT Span，T7继续保留，不伪造观测。额外原v1创建请求续期首400后留下DELIVERY_PENDING；再查询原创建请求409另一配送待核对，首次400原因未确定。管理员按状态中既有deliveryOperation核对原配送，周期/UID/24小时期限和消费5保持，再查询原创建请求返回原实例；未换批准或重放模型。独立RESUME命令192明确RESOURCE_EXHAUSTED，先仅休眠本轮已结束实验的v2实例释放槽位，再按原配送核对成功；通用自动恢复与公平调度仍待。
- 四角色真实API、1500/390px浏览器PASS，按/platform/me能力；人工凭证接口对授权人400、无权限403，控制台JWT机器入口401。20截图，无未捕获异常/失败API、浏览器业务写入0。初次手机关闭按钮定位器歧义修正后预检通过；看真实截图仍发现最后步骤竖排，v5修步骤宽度并独立重测，v6仅清理一处行尾空格并独立复检/构建，最终浏览器补单行检查。没有把首轮宽度PASS当作视觉无问题。
- 实际 backend=10.186.133.66:5001/eap-backend@sha256:9ad1ec397c40f0b2359f43e0a88d400ac07aa54a7f0469fc03919a5e230546d3；frontend=10.186.133.66:5001/eap-frontend@sha256:4f2a8f3b4837e6d69a9473b44aa5ef74f8dd1b93699d05dbf5c9e1e6deba8aa5；修正版默认Runner=10.186.133.66:5001/eap-runner@sha256:511647de34fc7e671b232a1d995696f9e0e4295549847cee1e10b0b7789985b6。实际imageID、健康UP、MySQLV62、前端buildId=2026-10-06T04:54:00.120Z；配送Secret仍不可变且引用/默认900秒保持。前后端镜像及修正版默认Runner定向更新，不整份应用历史清单或重启认证。首次只读健康连接Errno110超时，独立有界读取及补10秒超时后核验通过，失败记录保留、不归因；停止尝试时原读进程已自然退出，没有发送信号。v12发布后10秒直接健康读取再次超时、实验配置首预检400；独立核验健康UP及配置200后才提交新的有界实验，原消费当时仍3次，失败原因没有确定。
- 最终三个UID实例SUSPENDED、身份REVOKED，根撤销、策略停用，原额外创建请求409且原生version不变，旧凭证401；计数1/效果1、根used1/reserved0/cap2保留。旧T1–T5-B、T4 Workflow15原UNKNOWN及模板/快照/预算不重放；持久认证原PodUID及旧emptyDir replicas0保持。
- 操作 K8Substrate tools/eap/platform-identity，证据 artifacts/eap-platform-identity-20261006/index.html；导出/清理摘要另追加。节点 /root/eap-t5c-evidence.json completed=true，保留所有阶段键，禁止再prepare/workflow/model或换意图重放。门禁与导出扫描通过后才镜像/root/eap-src，历史/root/eap-poc.yaml不应用。
- 全部依赖/测试/构建/部署/浏览器远程，本机只文本/摘要/脱敏证据；无commit/push。PROJECT_MEMORY只追加，保护BP。内部可信HTTP PoC不等于生产mTLS/原生分配代次/完整灾备；任务网络独立执行器、自主/超长任务及全量旧模板迁移仍未完成，旧演示视频未重录。

---

# 当前任务：T5-B外部实例身份阶段已验收，继续T5创建链路与其他执行入口（2026-10-06）

### Codex 进展：T5-B外部实例安全配送、续期与旧快照重装已验收（2026-10-06）

**主线尚未全部完成。** 本轮完成已复核外部实例的EAP工作流入口阶段，不是整个T5统一身份迁移。下一项优先平台创建链路的专属身份、公钥和只读UID目录，再接任务网络/实验/自主及长任务入口、旧共享凭证迁移。业务工具逐项适配、T6通用恢复/发布取消、T7分级观测、T8知识/模型/工作流策略，以及调度公平/老化/阶段画像仍待；没有确认Agent调优稳定收益，字节/联通/华为方法仍是设计依据。

- 合同docs/RUNTIME_AUTOMATIC_IDENTITY.md。显式批准24小时实例接入授权，默认900秒短凭证，不自动延长身份/工具授权期限或额度。纳管记录与原生空间/名称/UID/模板核对；程序每次读取systemInfo.actorMetadata只读目录。generation为EAP身份周期，不是原生分配代次；credential_revision为短凭证版本，配送序号跨撤销单调递增。
- 独立Ed25519配送私钥只在不可变集群Secret；模板只含公钥与Agent/只读身份配置，无全局机器凭证、供应商密钥或控制API服务账号。签名45秒挑战、内存RSA2048/OAEP-SHA256密文、身份/nonce/序号/期限验签、HMAC确认回执；确认事务前候选在EAP不可用。网络配送不持有数据库行锁等待HTTP，撤销及UID复核优先；并发共享原pending，回执丢失/页面重开保持原请求，旧请求撤销后409。
- 工作流派发前按需续期，空闲不唤醒；有效版本也重装，修复旧快照内存。新旧版本切换不改身份周期、工具根、原操作/效果/预算。平台创建、任务网络、实验、自主出站/长任务及旧模板未迁移；内部可信HTTP路由PoC不等于生产mTLS/证明挑战公钥来源。原生MintActorJWT调用方授权有TODO，未配送控制账号或开放任意签发。运行快照仍包含受运行环境保护的短凭证内存。
- 最终远程后端完整471项/86类，零失败/错误/跳过；前端188项、类型/生产构建通过；Runner-race22顶层/33含子用例通过。8项配送回归包含回执丢失、撤销竞态、8并发续期、到期保留预算、旧快照重装、授权期及重建UID阻断。首轮短凭证错误约束工具授权、v2启动apiVersion错误、v3作用域并发断言DATA_REDACT_AND_RECHECK与期望预算耗尽不同均保留，不猜测v3原因；最终v5完整回归通过。镜像构建首次因打包尚未结束被门禁阻止，待EXIT0后成功。
- 真实独立Agent48/eap-agents/eap-t5b-identity，UID33f32366-4438-4929-a67f-908ab6f19c05。初次加密接入后一次计数+1、效果14d35858-88d8-4cbe-a73e-04cd343dfbb1；实际休眠跨120秒自然到期，版本不变/没有闲时续期。恢复900秒默认后Workflow16原运行wf-exec-8e05e56a-347a-45eb-8a55-0630d7435539成功续期并返回原效果，旧凭证401，身份周期不变。
- 快照初次EAP REVERT命令190/CTRL-46c37d10明确FAILED、未下发，因为外部纳管权限只允许休眠/恢复/检查；原记录保留，不放宽权限。通过原所有者原生CLI回退同一UID/原快照URI，HMAC在内存证明客体实际持有旧凭证；另一条原工具意图核对工作流成功，当前凭证重新安装且版本/期限不变。最终计数1/效果1、根used1/reserved0/cap2、模型0，实际Agent Span关联原工具Trace。
- 四角色真实API与1500/390px浏览器通过，权限按/platform/me，控制台JWT不能作机器身份；浏览器业务写入0，无页面异常/失败API。12张READY截图，另有最终撤销状态只读检查和公开配置截图；补拍首轮details dl选择器歧义失败保留，缩到automatic-connection后通过，不改应用或恢复凭证。
- 最终backend=10.186.133.66:5001/eap-backend@sha256:2fa697e8b004107db8eea3041241cc6856644c65a1621cb6c75c51f579258f88；frontend=10.186.133.66:5001/eap-frontend@sha256:281294980c601d0dc6dfb45f0cc890d7f0f89c06c8af71c5676d2b7fab881501；未来默认Runner=10.186.133.66:5001/eap-runner@sha256:87b20aa4d7459467ab51ffce86212076e13d9b1c920016e4e1fb4620e012f51e。实际imageID、MySQLV61、健康UP、前端buildId2026-10-06T01:55:07.377Z已核；默认900秒。现有模板/优化Worker/任务网络Runner不替换，不整份应用主清单或重启认证。
- 最终根撤销、策略停用、实例SUSPENDED、接入REVOKED/自动关闭，原凭证401、原接入请求409；保留所有授权、效果、快照/模板及旧T1/T2/T3/T4和UNKNOWN。认证持久化T5-A保持，原共享5173/8180隧道仍可达。/root/eap-t5b-evidence.json completed=true及所有阶段标记保留，禁止再prepare/workflow/snapshot或换意图重放。
- 操作K8Substrate tools/eap/identity-delivery；最终源码backend/Runner在/root/eap-t5b-src-v5，frontend/root/eap-t5b-src-v4，671源码摘要一致，比较基线/root/eap-t5b-green-t4固定。证据artifacts/eap-runtime-identity-20261006/index.html；导出摘要与定向清理确认另追加，门禁通过后才镜像/root/eap-src。已完成后不重跑依赖临时Job的构建/部署/导出旧入口。
- 全部依赖/编译/测试/构建/部署/浏览器远程，本机仅文本、摘要、脱敏图和公开入口元数据；无git commit/push。PROJECT_MEMORY只追加、保护BP内容。

#### T5-B 导出与收尾确认（2026-10-06）

归档3327941bytes，SHA256=01f8efca7f3b03ff8d2918165edde3b38159041fd9348aaba6b2123a0c19eeae；本地459项文件摘要和20个index链接一致，实际Secret/双层编码与3个历史机器凭证扫描PASS。入口K8Substrate artifacts/eap-runtime-identity-20261006/index.html及同名tar.gz，15截图；已查看admin桌面/手机/最终撤销公开配置，完整可见、无横裁。原READY浏览器runId=fcce6fc0-9740-4365-b1a8-3e29fd865a5b，最终撤销只读runId=6eb46055-28aa-4fe5-b8a9-afdfaea489a2，首轮补拍定位器失败独立保留。

仅定向删除本轮7个Job和8个独立Pod，确认剩余临时资源0。Agent48/模板/快照/配送Secret及全部原意图、效果、授权与历史保留；T4实例46/47仍休眠/撤销，Workflow15原wf-exec-46611fa5-8722-4fef-a771-971969c5f2c0仍EXECUTION_UNKNOWN。认证持久化PodUIDbfa818d4-6f6d-4d03-b974-7f6ac6179dcb保持，旧emptyDir Deployment replicas0；5173新版buildId及8180原issuer仍可达。

671源码门禁及17文件固定T4补丁通过后已镜像/root/eap-src，交接与合同定向同步。历史/root/eap-poc.yaml不含当前镜像摘要/身份配置，旧副本校验失败保留，没有整份应用或伪装成当前清单；实际固定版本定向配置/root/eap-t5b-release-patch.json保存且未再次应用，K8Substrate tools/eap-poc.yaml仅同步三个摘要与配送Secret引用/900秒。导出后新建的同步/收尾脚本及最终状态留在tools/eap/identity-delivery与节点，原归档SHA不再更换；不重跑已删除Job依赖的旧build/deploy/export。

主线仍未完成，下一项T5平台创建与其他执行入口的统一实例身份/旧共享凭证迁移；T6/T7/T8及调度公平/老化/阶段画像继续保留，无确认Agent调优收益。旧演示视频未重录。本机仅文本/摘要/脱敏证据，无commit/push；PROJECT_MEMORY只追加、BP保持。

---

# 当前任务：T5-A认证持久化已验收，继续T5统一实例身份（2026-10-06）

### Codex 进展：T5-A认证持久化已部署与验收（2026-10-06）

**主线尚未全部完成。** T1/T2/T3/T4有界阶段及本轮T5-A认证持久化已交付。T5实例身份安全配送/续期与旧模板迁移、业务工具逐项适配、T6通用恢复/发布取消、T7分级观测、T8知识/模型/工作流策略及调度公平/老化/阶段画像仍待；没有确认Agent调优稳定收益。字节/联通/华为报告方法仍作为设计依据，不当作已证明收益。

- 正式认证为eap-auth/keycloak-persistent单副本Recreate，Service keycloak selector=app:keycloak-persistent，镜像保持Keycloak26.7.4固定sha256:3d911baa186f352563854039b95f21a7e2c01c76b527fdc64f24a0885b927bdf。原公开issuer/localhost5173回调不变；keycloak-data-1005为local-path RWO，keycloak-backups-1005为NFS RWX，两个PV Retain。不重新导入realm，init必须看到已恢复H2数据库，空卷拒绝启动。旧emptyDir Deployment keycloak最终replicas0，原Pod已退役，保留配置；不能扩旧Deployment到1冒充恢复。
- 先实测Java/H2独占记录锁与只读共享锁冲突，绑定原UID，正常关闭PID1、同Pod容器自动重启保留emptyDir；共享锁持有期间读取已关闭数据库，AES-256-GCM加密/fsync。原文件只在应用卷与内存；三个加密恢复点和不可变keycloak-backup-cipher Secret留在集群，不导出密文或密钥。不用在线文件copy/JSON export当事务一致备份。实际解密、篡改、错误备份身份拒绝通过。
- 独立恢复与最终切换核对eap realm全部4用户/7客户端/8角色ID及JWKS摘要；四角色原未过期访问令牌、原刷新会话、新PKCE登录和原权限200/403、错误audience401通过。首次切换HTTPmetadata401、已回滚原路由，保留失败尝试及其备份；原访问有效期300秒未改变。调整EAP验权至正式路由恢复后，新一轮78.77秒切换通过。首次没有栈证据，不将401归于账号丢失或令牌过期；后续阶段/安全栈与有效期记录补齐。
- 实际持久化Pod由158d1d58-1e7a-4d03-ab88-bf2d793877ee替换为bfa818d4-6f6d-4d03-b974-7f6ac6179dcb，原访问/刷新会话、账号ID、客户端/角色、签名公钥均保留；实际UID见证据，不能以容器重启冒充Pod替换。独立空卷负测init exit1、writer未启动；当前manifest远程server dry-run通过。原密码/客户端运维脚本按Service selector选唯一Ready Pod：重启中拒绝未就绪writer，就绪后选择新Pod，仅check-target、密码与scope写入0。
- 远程Chromium四角色1500/390px登录、原能力、刷新页面/手机导航通过，12截图、无未捕获异常或失败API、浏览器业务写入0。旧实例停用后本地5173/version.json与8180 issuer仍可达，原共享SSH隧道保持。未重跑历史业务、调用模型或修改T4效果/UNKNOWN、预算、旧模板/快照；后台及前端镜像保持T4固定digest，backend健康UP、frontend buildId2026-10-05T09:30:54.993Z。
- 合同docs/AUTH_PERSISTENCE.md；EAP deploy/substrate-poc/keycloak.yaml已改为恢复态PVC配置，旧首次初始化说明注明历史；AX主体运维入口及K8Substrate密码入口适配新目标。没有业务代码变更或新应用构建；本阶段使用远程真实迁移/恢复/权限/浏览器/空卷及清单验证，不将T4完整回归数字说成本轮重测。
- 证据K8Substrate artifacts/eap-auth-persistence-20261006/index.html，归档1126313bytes，SHA256=fbd13b5719aefc6c12da6fee7ae33fe047ce4fc1cf0193dd92de3bd547e43ea2；39文件摘要/index链接/传输及节点实际Secret值与base64扫描PASS。节点/root/eap-t5-auth-evidence.json completed=true，保留所有阶段标记与失败尝试；已完成后不重跑prepare/ready/preflight/cutover/restart。原应用卷/备份PVC/Secret不清理，仅本轮临时测试资源在导出后定向清理。
- 边界：单writer H2 PoC、一次已验证的加密备份恢复流程，不是外部数据库/生产HA/TLS、定期备份调度、外部密钥托管或整集群灾备。未来备份重新绑定当前writer/PVC，不复用退役Pod hostPath。实例24h人工凭证、自动安全配送/续期及旧模板全局token迁移仍是T5下一阶段。所有依赖/测试/浏览器/部署远程；本机只文本/摘要/脱敏证据与入口公开元数据读取，无commit/push；PROJECT_MEMORY只追加、保护BP。

---

# 当前任务：T4受控阶段已验收，继续T5身份与认证持久化（2026-10-05）

### Codex 进展：T4受控工具作用域、委托与效果核验已部署（2026-10-05）

**主线尚未全部完成。** T1/T2/T3有界阶段已交付；本轮完成T4第一个受控工具适配。下一项T5统一实例身份、安全配送/续期及认证持久化；业务工具逐个适配、T6通用恢复/发布取消、T7分级观测、T8知识/模型/工作流策略，以及调度公平/老化/阶段画像继续保留。字节/联通/华为报告方法是设计依据，不当作已证明收益。

- 合同docs/SCOPED_TOOL_CONTRACTS.md：controlled_counter_increment/poc-counter-v1，明确测试对象/接收实例/增量/期限、最多4级收窄委托、兄弟共享不可变根额度；提交事务复核整条祖先策略/身份/撤销及预算，可信工具存储原子提交计数和效果UUID。普通执行方回执仍RUNTIME_REPORTED；不是全部外部工具或客户系统的效果/撤销保证。未知操作保留原标识，不自动重发。
- 远程完整backend459/85 suites零失败、错误、跳过；真实集成修复后八类相关回归121通过，不声称修复后再跑完整462。frontend186、类型检查/生产构建，Runner-race18顶层/29含子用例通过。首轮安全通道/事务mock、迁移版本、前端类型、Runner Trace、测试构造器失败，以及旧同JVM运行挂起日志保留。最终完整回归采用逐类JVM，不抹掉首轮失败。
- 真实Agent46/47，两个子委托共用根cap2：首次工作流提交+3，八并发争最后额度仅一次+1、七次TOOL_BUDGET_EXHAUSTED/已确认无提交；最终计数4/效果2，根used2/reserved0/cap2，两个不同授权Agent本地策略各消费2，模型0。原操作3次回放同效果UUID，同根换父/实例409。祖先策略版本变化/撤销阻断新操作；子额度耗尽不再遮住祖先失效原因。
- 首次工作流按旧格式将已确认的工具效果记录EXECUTION_UNKNOWN，原运行保留。修复WorkflowTaskClient独立核验持有实例、参数、根/操作/effectId/计数/来源；另一条核对运行使用相同工具意图返回原UUID，SUCCEEDED且0新增效果。不是原UNKNOWN工作流的通用恢复；T6仍待。首轮策略拒绝因子额度先耗尽而显示预算原因，原REJECTED不改；已确认无提交后用明确新拒绝操作检验修复，不换根或清零。
- 四角色API及1500/390px真实浏览器PASS，浏览器业务写入0、无未捕获异常/失败API；控制台JWT不能替代实例机器身份，实际401。页面区分存储确认提交/未提交/未知与普通回执，子委托显示祖先撤销，列表限高/限量，工作流工具配置冻结稳定意图。实际任务Span与工具Trace关联已核。
- 最终backend=10.186.133.66:5001/eap-backend@sha256:1cddf5a1f3eff987c51a6e54b4dd1adf0bbb26f4510dcfdf1273c166a1371dfc；frontend=10.186.133.66:5001/eap-frontend@sha256:ce49a8acffca1cd03115e83ca00867146135c2ab9b88d6d16036e331fae59316；未来实例Runner=10.186.133.66:5001/eap-runner@sha256:150d537c3af42866e5e245fda654eabe08ae0da14610d687e1d703f1c41c9c3a。实际imageID/两验收模板镜像、健康UP、MySQLV60、前端buildId2026-10-05T09:30:54.993Z均核；优化Worker/任务网络Runner及旧模板不改。主清单只同步固定digest，不整份应用。
- 两实例最终SUSPENDED、工具策略停用、专属凭证撤销；根已撤销，子行历史ACTIVE不代表可执行。节点/root/eap-t4-evidence.json completed/browserCompleted/rolesCompleted=true，保留原workflow/runKey/confirmationRunKey、grant/operation/effect、模板/快照及所有旧T1/T2/T3/FAILED/UNKNOWN；不删除标记或重跑roots/workflow/race。最终backend源码/root/eap-t4-backend-v8、frontend/root/eap-t4-frontend-v5、Runner/root/eap-t4-go-v5；666源码摘要一致，绿色T3基线/root/eap-t4-green-t3固定。证据导出门禁通过后再镜像/root/eap-src。
- 操作脚本K8Substrate tools/eap/scoped-tools；证据入口artifacts/eap-scoped-tools-20261005/index.html，导出摘要另追加。所有依赖/编译/测试/构建/浏览器/部署远程，本机只文本/摘要/脱敏证据查看；无commit/push。保护另一线程BP内容，PROJECT_MEMORY只追加。Keycloak仍emptyDir、不空PVC覆盖重启；本地5173/8180隧道保持。

#### T4 导出与收尾确认（2026-10-05）

666源码摘要、相对绿色T3的28文件patch、实际Secret字节/base64扫描、624证据文件摘要及归档传输均PASS，已镜像/root/eap-src。证据本地K8Substrate artifacts/eap-scoped-tools-20261005/index.html；原归档SHA256=a378f950c48281c5580f435d6c3440ca1eb19c811241b29389706d81921e35e5，6820460bytes。已查看admin桌面及手机效果图，无横裁/重叠；浏览器runId=75eb6c2e-231c-465e-ba40-e4e2fb0e7ea7。仅定向清理本轮9个Job和9个独立Pod，确认无剩余T4临时资源；业务实例46/47、模板/快照、Secret、全部原UNKNOWN/REJECTED/COMMITTED及旧T1/T2/T3不动。保留节点eap-t4-evidence.json completed=true，不重复roots/workflow/race，也不重跑已删除构建Job依赖的旧部署/导出入口。源patch固定绿色T3，不以新/root/eap-src当旧基线。下一项继续T5，主线未全部完成；无commit/push。

---

# 当前任务：T3已完成，继续主线T4权限与工具合同（2026-10-05）


### Codex 进展：T3受限提示词优化已部署并完成有界验收（2026-10-05）

**主线未全部完成。** T1真实反馈/对照、T2共享预算、T3受限提示词搜索/独立留出有界阶段已交付。下一项优先T4工具合同、收窄委托和独立效果证据；保留T5身份/续期与认证持久化、T6通用恢复/发布取消、T7分级风险观测、T8知识/模型路由/工作流策略，以及调度公平/老化/阶段画像。报告及字节/联通方法是设计依据，不当成已验证的创新收益。不能将一项PASS说成系统生产整合或Agent整体性能已提升。

T3有任务API、持久协调器/单次900秒claim、根+子实验+反思共同预算、反馈实际证据核对、候选只改system_prompt、服务端封存留出、统一能力与桌面/手机流程页。Worker固定GEPA0.1.4，训练/验证关闭包内索引混淆缓存，未知锁存防提案回退重发，无供应商/全局密钥/SA token，UID65532且只读根。新根对明确支持的deepseek-flash/pro仅反思冻结thinking disabled、512上限不增；运行评价程序/模型/选项不改。金额和输入Token硬预算、自动采用、结构搜索不在本项。

远程完整backend431/84 suites零失败零跳过；真实模型截断修复涉及优化/实验/预算67通过（不说修复后重跑全量433）；frontend175、类型/生产构建、真实GEPA/Worker23通过。真实四角色API与1500/390px浏览器、正文权限、异步清除、页面/表单无横裁通过，浏览器业务写入0且无未捕获异常/失败API。实际控制台JWT/假claim不能代替机器身份：403；三imageID和前端buildId已核。

Agent45/eap-agents/a45-v1-6aecb6：根1 FAILED/REFLECTION_TRUNCATED，7调用/794输出，反思512全reasoning，0未知/预留；没有留出、没有重放或清零。不同公开样本的新根2 COMPLETED，16调用含2反思，输入1203/输出603；合计23/32调用、输入1752/输出1397=3149Token，全部SETTLED。留出人工/候选各1/1、两次新基线各1/1；人工115 vs基线81Token，选定候选93 vs另一基线92，建议KEEP_BASELINE。没有确认算法收益。原根请求回放新增0，实际运行基线不改；最终实例SUSPENDED。保留两根、所有原意图/模板/快照/旧T1/T2及UNKNOWN标记。

最终backend=10.186.133.66:5001/eap-backend@sha256:eb706f6dbe443f2408c94486e534aa5850a3f359eeedd4ade1436aec9d408f2b；frontend=10.186.133.66:5001/eap-frontend@sha256:85b15ac66d55707caf4e58e0987f0f60deea461a5fdda97c0f7057655d37d454；optimizer=10.186.133.66:5001/eap-prompt-optimizer@sha256:2c05a3aa110662b5c83e242c1231eb31f5e7e2c4e945eac4dc10b9475da5add4。Runner仍T2固定版本。MySQLV59，前端buildId2026-10-05T01:56:00.860Z。主清单已固定digest/优化开关，内部服务清单tools/eap/optimization/poc.yaml；不整份重应用或重启Keycloak。

641源码摘要一致、相对绿色T2的31文件精确patch、Secret内存扫描和导出236文件摘要通过后，已镜像/root/eap-src。证据K8Substrate artifacts/eap-prompt-optimization-20261005/index.html；归档SHA256=37b71ca51035f2a2904effd39e690b8b02efd9d47a2e2b097e4cbe2d52c2ab51，2998656bytes，首次反思失败及浏览器定位歧义均保留。节点/root/eap-t3-evidence.json保留根1失败，/root/eap-t3-reflection-v2-evidence.json completed=true；不重放模型只为截图。Root2原提交保存ID查询，不能恢复时误选另一根。

所有依赖/测试/构建/浏览器/部署远程，本机只文本编辑/哈希/证据查看；无commit/push。保护另一线程BP内容，PROJECT_MEMORY只追加，不改融资稿。Keycloak仍emptyDir、旧模板全局凭据及实例身份迁移仍待；不换空PVC重启。现有本地5173/8180隧道保持。后续从T4开始，不再重复本项真实调用。

---

# 当前任务：T3 已部署，真实截断修复待验收（2026-10-05）


### Codex 进展：T3 已部署、真实反思截断修复在测（2026-10-05）

主线仍未全部完成：Agent业务调优、共享预算调度、权限/身份/效果证据、恢复及产品闭环。T1/T2有界完成，不替代T4–T8。T3已接任务API、单次claim/持久协调器、反思实际证据核对、候选封存与服务端留出、能力权限和流程页面；远程完整backend431/84 suites零失败零跳过、frontend174+类型/构建、Worker23通过。三镜像已部署并rollout，/version.json=2026-10-04T14:32:28.269Z，实际digest见/root/eap-t3-images.json；绿色/root/eap-src尚保留T2，未被覆盖。

真实Agent45 Root1 FAILED/REFLECTION_TRUNCATED，严格7次实际调用、输出794、0在途0未知；反思输出512全部reasoningTokens，未进入留出/未采用。保留/root/eap-t3-evidence.json与所有原键/已完成T1/T2/UNKNOWN/旧夹具。官方DeepSeek文档确认默认thinking启用；修复为新根冻结_reflectionThinking，对明确支持的deepseek-flash/pro仅反思调用显式disabled，输出上限仍512，评价Runner/model/options不改。补截断付费失败/模式冻结、轮询响应竞态回归；remote/root/eap-t3-reflection-v2-src与测试Job/Pod正在跑，修复尚未重新部署。

下一步：修复验证/镜像digest上线，再用独立新根、不同公开样本进行有界验收；新证据/root/eap-t3-reflection-v2-evidence.json，复用Agent45保留原7次消耗，智能体32次总上限不增。旧FAILED不重放，UNKNOWN不能新建意图。roles/browser/finish和脱敏证据导出尚待；真实成功优化不能说已验收。Keycloak仍emptyDir、认证/旧模板迁移/统一身份续期/工具独立效果/公平调度/长任务恢复仍待。不本机构建、不commit/push。

---

# 当前任务：T3受限提示词优化开发中（2026-10-04）

用户补充华为交流报告，方法已映射到Agent调优、任务调度、权限/独立效果与恢复主线。报告核读见research/agent-system-methods-20261002/huawei-report-methods.md，合同PROMPT_OPTIMIZATION.md。实际GEPA 0.1.4受限adapter+简单搜索远程19项通过；修复默认列表缓存训练/验证索引冲突和提案异常回退重新反思两项真实包问题。仅关闭GEPA内存缓存，用根/候选/切分/样本稳定意图；未知反思锁存，不伪装成功。

后端隔离完整回归411/411、83 suites、0skip（范围/用途检查已测）；随后补旧T1提交指纹兼容回归，尚待并入完整验证。V59及根预算/子实验桥接仅开发源码，未应用生产。**用户问部署了吗，已明确回复本轮未部署，继续完成任务接口、持久协调器/单次claim、反思与封存留出流程、页面，然后部署和真实验收。不能到此停止并把T3说成完成。**

当前/root/eap-src和线上仍绿色T2，保留Agent44/T2所有完成标记与旧夹具。远程方法测试目录/root/eap-t3-method-final-src、输出/root/eap-t3-test-out；Python探测Pod仍在。不得覆盖绿色目录直到最终通过；全部编译测试部署远程，不commit/push。权限/身份配送续期/认证持久化/通用恢复/T4–T8继续保留；Keycloak仍emptyDir，不空PVC覆盖。

---

## 主任务与优先级（用户再次强调，2026-10-02）

主线是持续完善EAP智能体系统，不因单项收尾、BP或页面美化而偏离：

1. Agent调优：质量、成功率、延迟、Token与工具策略；不以底层CPU/启动探测优化替代Agent业务调优。参考论文/开源方法，先可复现对照与共享预算，再受限提示词优化。
2. 调度优化：任务/工作流/实验的共享预算、已用+在途预留、确定性准入、并发与恢复边界，结合联通阶段画像/预算、生效确认和恢复核验方法。
3. 权限与治理：统一实例身份、凭证配送/续期、收窄委托、执行前授权、工具效果证据，结合字节任务作用域/委托/授权/分级证据方法。
4. 集成闭环：EAP持续纳管运行底座与任务网络创建/运行的智能体，完善创建→调度→执行→观测→治理→恢复链路；不能把局部PASS说成全部生产整合完成。
5. 产品交付：清晰导航和可视化、前后端权限一致、真实演示与远程完整验证；前端尽量不展示AX/Substrate产品名。所有构建测试部署远程，不commit/push，不导出密钥。

T1及T2有界实现已完成，服务于以上主线；下一项T3受限GEPA与独立留出集。保留身份/权限/恢复加固任务，单项完成不删除总目标；旧夹具和完成/未知标记不重放。

---


# 当前任务：T2共享预算与准入已上线并有界验收（2026-10-04）

主线仍是Agent调优、调度与权限治理。合同见 [SHARED_BUDGET_ADMISSION.md](SHARED_BUDGET_ADMISSION.md)。部署共享/智能体/已验证任务/运行实验同时准入，MODEL_CALLS、OUTPUT_TOKENS、TOOL_CLAIMS持久化预留与唯一结算；未知保留占用，不跨日重置，供应商超上界如实记录并冻结。稳定意图防重；实验身份由服务端Trace确定，不能换调用键重置；工具额度与原策略消费同事务回滚。权限manageBudgets来自PlatformPermission，配置CAS主体审计、可视化进度/限高列表及手机表单。

远程后端399/399（83 suites），前端166/166及类型/生产构建，Runner-race16顶层/21含子用例、任务网络Runner5/5；四角色1500/390px、双激活单PUT通过。627源码摘要一致，28文件相对T1精确patch已存，再镜像/root/eap-src。真实Agent44：工作流1、配对实验2、任务网络1，严格4个实际网关模型调用、全部SETTLED；耗尽实验FAILED(BUDGET_EXHAUSTED)，原键重放0新增。最终实例休眠、任务网络已确认休眠。前端buildId2026-10-04T01:20:56.122Z。

**下一项T3受限GEPA与独立留出集；保留身份配送/续期、旧模板迁移、工具效果合同、认证持久化和长任务恢复。** 本项没有输入Token/金额硬上限、预算公平/老化队列、多租户实体、未知人工对账或自动提示词优化；新默认运行程序仅影响未来发布，旧模板无任务键时只扣部署/智能体。不能声称全部生产整合完成或Agent性能已提升。

保留/root/eap-t2-evidence.json completed及所有阶段键、Agent44模板/快照、实验/网络任务与全部旧夹具/UNKNOWN/恢复标记，不重建或重放业务。证据K8Substrate artifacts/eap-budget-20261004，原归档SHA256=227b04cf1a3d510c51e9baf4f016e4154bf62d964faf84636c9fa3a9623064dc；临时测试资源只定向清理本轮。Keycloak仍emptyDir，不换空PVC直接重启；本地5173/8180隧道保持。全程远程构建/测试/浏览器，不commit/push。

---

# 当前任务：T1真实反馈与版本化Agent对照已上线（2026-10-02）

用户“继续”已落实T1；合同见[AGENT_PROMPT_EXPERIMENTS.md](AGENT_PROMPT_EXPERIMENTS.md)。真实反馈替换示例，JWT主体/证据归属/CAS评审；冻结实际运行基线、候选与样本，真实新输出、Token覆盖、Trace、独立内容权限及保守UNKNOWN防重。新配置式发布使用固定新版Runner，用户systemPrompt/model已验证准确映射；旧模板不自动升级。

**下一项T2共享预算预留与准入，随后T3受限GEPA和留出集。** 当前全局一实验、1–3样本/最多6calls/输出≤256只是受限底座，尚无跨任务/工作流的Token或金额硬预算，无自动优化器/自动采用/UNKNOWN人工解除。不能宣称Agent已经性能提升。

本轮远程后端377/377、前端162/162及类型/构建、Runner-race13顶层/18含子用例、四角色JWT/浏览器及390pxPASS。Agent43实验1真实6calls，两版3/3；基线270Token，候选355Token，平均Runner901/693ms，不能推导调优收益。真实界面反馈1次，治理评审采纳不改运行配置；配置式v2额外模型0。两个actor均休眠。证据K8Substrate artifacts/eap-agent-tuning-20261002已核摘要；当前镜像/完成标记见PROJECT_MEMORY最新条目。禁止重放实验或删除/root/eap-t1-evidence.json及日志，保留旧夹具/快照/标记。临时测试资源定向清理，本地自动重连隧道54906保持。

集成加固仍保留身份配送/续期、旧模板迁移、工具效果合同、Keycloak持久化和长任务恢复；Keycloak仍emptyDir，不直接换空PVC重启。不本机编译、不commit/push。下方“反馈固定示例/T1未完成”是历史状态，已被本条更新。

---

# 当前任务：Agent 调优及系统能力方法调研已完成，按工程合同继续（2026-10-02）

用户澄清性能优化以Agent调优为重点，并要求其他功能参考论文/开源工具及联通、字节原始项目深入调研。本轮只做研究和代码核读，未改业务或部署；报告见 [系统能力深化调研](research/agent-system-methods-20261002/README.md)，具体下一步见 [T1–T8工程合同](research/agent-system-methods-20261002/implementation-plan.md)。

**优先T1真实反馈表单和版本化Agent基线/候选实验，T2共享预算与准入，再T3受限GEPA试点。** 当前数据集运行是评已有actual的校准，不是Agent重跑；反馈页仍固定示例输入。不要把普通运行底座优化代替Agent调优，也不要声称优化器已接入。候选参数以外的实验条件固定、留出集隔离、正文用途授权、重试/恢复不重置消耗，UNKNOWN保留。

权限/工具效果合同、统一身份/续期、旧模板迁移、认证持久化和长任务恢复仍待继续，可按独立工程线推进。联通/字节方案均未完成实验，不能当已部署能力。默认运行池及旧快照兼容已经完成，保留全部完成标记和夹具，不重复清理/故障验收；Keycloak仍emptyDir，不可换空PVC直接重启。所有测试构建部署远程，无commit/push。下方为先前工程状态。

---

# 最新状态：两个默认运行池已升级，旧快照兼容通过（2026-10-02）

旧失败v4已按用户确认定向清理，EAP失败历史/v5保留。模板锁冲突经精确UID维护开关处理；当前维护参数已移除。API、两节点组件和普通/隔离默认池均Ready，实际imageID核对。维护测试11顶层/63含子用例、三个补丁叠加校验、三个旧快照跨两节点恢复、原回执及四角色接口通过，模型新增0，最终都休眠。详情RUNTIME_DEFAULT_POOL_ROLLOUT.md及PROJECT_MEMORY最新条目。

**下一步是统一实例身份、安全配送/凭证续期及旧模板迁移协议，随后推进认证持久化和长任务恢复。** 不再重复旧v4清理/默认池升级，也不重放工具、容量或退出故障。EAP发布失败自动取消产品链路仍未完成。Keycloak仍emptyDir，不得直接换空PVC重启。

节点retirement/default-rollout/rollout-compatibility记录均completed，保留完成和pending标记。K8Substrate artifacts/eap-default-rollout-20261002已导出；版本检查Pod已清理，隧道保持。前后端未改未重测；所有构建测试远程，无commit/push。以下旧“等待确认/默认池未升级”为历史状态，已被本条更新。

---

# 最新状态：就绪探测优化灰度通过，默认池迁移待清理确认（2026-10-01）

历史node2任务已定位为Agent7失败的v4模板准备任务，探测/端口3000持续404，原1ms轮询每120秒约4.7万次；v5 READY，v4无业务引用/快照。已向用户询问是否清理仅该失败v4并继续默认池升级，**尚未获准，不能默认同意**。retire-failed-template.py已只读预检通过，不传审批参数不会删除。节点/root/eap-failed-template-retirement.json无retirementRequested。

独立完成启动探测退避、重定向限制、在途总超时、4KiB响应体和取消优先修复。远程4包190顶层/503含子用例通过、3环境跳过；5项红测均复现。相同1秒合成404请求753→16；真实Agent42正常3轮、8次503后200恢复通过，最终休眠/独立池缩容0，模型0。新工作镜像4fb1df0f...仅灰度池，默认池未升级。合同RUNTIME_READINESS_PERFORMANCE.md，patch在bounded-readiness；证据artifacts/eap-readiness-20261001已导出。

用户确认清理后才执行指定UID审批参数；检查无活动分配，再升级node2和默认池并补兼容验收。保留/root/eap-probe-evidence.json completed标记以及全部旧夹具，不重跑业务。EAP前后端未改未重测；不本机编译、不commit/push，隧道70891保持。具体版本和操作边界见PROJECT_MEMORY最新条目。

---

# 最新状态：工具权限已完整验收，进程退出检测灰度通过（2026-10-01）

工具共享授权已部署、354后端/156前端及四角色浏览器通过，测试授权停用/凭证撤销/实例休眠、证据导出完毕。进程退出底层4包360个顶层通过（含子用例1660，3个既有root环境跳过）；独立Agent40/41真实退出检测约8.4秒、安全槽位复用、显式快照恢复通过，最终池eap-exit-6c56c536缩容0、模型0。详情PROJECT_MEMORY最新条目与RUNTIME_PROCESS_EXIT_HEALTH.md。

**下一步是默认运行池迁移前的历史任务调查，不是重复当前验收。** API与node1已升级；node2及默认eap-agents/eap-ax工作池未升级，旧worker仍不具备退出健康RPC。node2历史ate-golden/803ab1fe-747c-40f4-86d4-14c357d96550停留RESUMING，不能为升级直接终止。atelet DaemonSet刻意保持OnDelete，切回RollingUpdate会替换node2。先确认任务归属和安全处置，再做分批迁移及新验收；不能称已全量覆盖。

最终镜像、代码与版本摘要见补丁manifest和证据。K8Substrate artifacts/eap-tool-policy-20260930、eap-process-exit-20261001均已导出。四个本轮临时Pod清理；保留模板/快照、旧夹具与本地隧道。节点/root/eap-process-exit-evidence.json completed=true，禁止清crashTriggered或重放crash；工具policy evidence也已completed，禁止重放run。全部测试/构建远程，不commit/push。凭证安全配送/续期、旧模板迁移、认证持久化与长任务恢复仍待继续，Keycloak不得换空PVC直接重启。

---

# 最新状态：工具权限已发布，真实额度通过；底层退出检测在测试（2026-09-30）

工具权限代码/前后端/迁移已发布，backend740ec318...、frontendaeb00889...；后端354/前端156通过。真实双实例8并发只发2许可、额度持久化及actor求和42通过，模型0。严禁重放/root/eap-tool-policy-evidence.json的run；下一步完成四角色浏览器、测试授权停用/凭证撤销、证据导出。

底层退出检测补丁仅在/private/tmp/eap-runtime-exit与远程/root/substrate源码，尚未部署；先核对/root/eap-exit-test-out生成/测试结果，完成隔离验证并打包EAP补丁，再考虑canary部署。临时浏览器eap-tool-policy-qa、隔离数据库eap-exit-test-db待结束清理。细节见PROJECT_MEMORY最新条目。保护全部旧容量夹具/快照，不重放crash；不本机构建、不commit/push。

---

# 最新状态：真实容量补测与实例列表竞态修复（2026-09-29）

本轮容量、隔离和显式快照恢复通过：12真实实例/2槽位/3轮，槽位满时失败并在释放后同键重试成功；原19实例不变，模型0，新增实例全部休眠，独立池缩容0。

**优先下一项：进程退出检测缺口。** 实际进程stopped/HTTP502，但底层和EAP仍RUNNING，RUN_HEALTH_CHECK只查询控制状态。显式REVERT恢复计数6通过，自动识别未通过。不要将本轮170个正向断言或监控CONNECTED当成系统全通过。具体根因、实现约束见RUNTIME_CAPACITY_ACCEPTANCE.md和INTEGRATION_HARDENING_NEXT.md。

另修复RuntimeInventoryPanel身份加载竞态。远程前端150/150、类型/构建、最终四角色延迟/me复验、390px、42节点全图和消息限高PASS；最终frontend4ced8bc2...，buildId2026-09-29T06:43:35.597Z。backend9b57031f...未改未重跑，348为前轮基线。未commit/push。

保留/root/eap-capacity-evidence.json、Agent27–38、独立模板/快照，不重放crash或清除crashTriggered/crashRecoveryDone；eap-capacity-qa、eap-capacity-fe-test已清理。证据K8Substrate artifacts/eap-capacity-20260929/，已导出核对；本地隧道保持。全部测试/构建仍远程，Keycloak临时库不能直接换空PVC重启。

---

# 最新状态：控制深度补测与大拓扑加固已完成（2026-09-29）

用户要求继续补测。已复现并修复并发重试重复派发、退役重试、审计操作者错误、大图适配裁切、长虚线重复绘制造成卡顿及手机标题竖排六项问题。远程后端348/348、前端148/148、类型/生产构建；最终四角色、390px、断网恢复、消息列表和合成100/500/1000节点浏览器均PASS。1000节点更新/展开/适配观测约8.8秒；只读5并发120请求全部200，P95 199.1ms，非生产容量保证。

真实Agent25快照A→改B→REVERT→恢复A，最终休眠；Agent26安全阻断后的真实UI重试、并发上限、当前操作者审计和退役拦截通过，最终退役/离线。模型0。保留 /root/eap-control-deep-evidence.json、/root/eap-control-deep-http.json，不重建/重放或清除 uiRetryStarted。详细版本、阶段验收边界和失败记录见PROJECT_MEMORY.md最新条目；证据K8Substrate artifacts/eap-control-deep-20260929/。

最终frontend f646a062...（buildId 2026-09-29T02:50:53.925Z）、backend9b57031f...；Runner/DB不变，未commit/push。HTTP配置/限流是隔离真HTTP接收端协议验证；1000节点是展示合成测试，未做1000真实实例、长期压力或真实节点故障。集成剩余继续按INTEGRATION_HARDENING_NEXT.md；Keycloak临时卷不能直接换空PVC重启。保持本地隧道，继续远程构建测试。

临时eap-control-deep-qa已清理；证据与截图索引已导出，本地5173/8180隧道可达。

---

# 最新状态：拓扑与控制修复已部署并验收（2026-09-29）

用户报告的图不能展开、连线混乱、控制派发误提示及消息列表过长已修复。画布支持展开/缩放/平移/全图适配/节点聚焦和搜索、六列共享主干走线，消息330px内滚动、每页4条、详情折叠。控制先选实际实例并按服务端能力显示命令，失败不会再报成功；无实例/无权限不能提交。合同NETWORK_CONTROL.md。

远程前端144/144、类型检查/生产构建，后端340/340；最终四角色浏览器、390px和全图适配PASS。独立Agent24状态检查/恢复/休眠各1次全部ACKED，最终休眠，模型0。保留/root/eap-topology-control-evidence.json及所有旧夹具，不为截图重放。镜像frontend edeee0ad...（buildId2026-09-28T23:45:52.746Z）、backend7f3f4ba2...，细节见PROJECT_MEMORY.md最新拓扑条目。

证据K8Substrate artifacts/eap-topology-control-20260929/；临时浏览器Pod已清理，本地5173/8180隧道以自动重连方式运行。未commit/push，未重录视频。系统整合剩余仍见INTEGRATION_HARDENING_NEXT.md，Keycloak仍临时卷，禁止换空PVC重启。继续远程构建测试。

---

# 最新状态：控制台视觉更新已部署并验收（2026-09-29）

用户要求增加科技感、参考 AdminLTE，已完成深色导航/浅色工作区、蓝青色强调、深色标题区、表格指标统一和监控 SVG 图标。主题集中 frontend/src/styles/console.css；设计约定 CONSOLE_VISUAL_DESIGN.md。无需新增前端依赖，不是全站深色模式。

远程前端131/131、类型检查/生产构建通过；最终镜像四角色、18入口、1500/1024/390px与权限/搜索/菜单通过，页面/API错误及业务写入0。深色标题小字对比度9.88:1，截图已导出，K8Substrate artifacts/eap-console-design-20260929/index.html 提供前后对照。最终镜像d006c38e...，buildId=2026-09-28T16:39:46.467Z，详细结果见PROJECT_MEMORY.md最新条目及产物status.json。临时浏览器Pod已删除并确认不存在。

后端与Runner未改未重跑，后端337/337是上轮基线。未执行新模型/工具任务；保留/root/eap-tool-ui-evidence.json及旧夹具，不重新签发或重放。未commit/push，旧视频未重录。

集成仍有凭证安全配送/续期、旧模板迁移、认证持久化和长任务恢复/生产韧性边界；按INTEGRATION_HARDENING_NEXT.md继续。Keycloak仍临时卷，不能直接换空PVC重启。用户如果没有新方向，下一步回到这些集成加固项，不再重复本轮视觉设计/截图业务验收。继续远程构建测试。

---

# 最新状态：工具节点图形配置与结果查看已上线（2026-09-28）

已补齐节点工具类型/数字表单、持久化与冻结派发、实例工具能力检查和受内容权限保护的结果查看。当前仅calculate_sum，工具需已验证的实例专属接入；隔离沙箱工具、平台旧模板仍不支持。新工作流执行请求拒绝全局context.eapTool，改为节点configJson.eapTool；旧requestKey重放读取不变。合同WORKFLOW_TOOL_NODES.md。

远程后端337/337，前端131/131、类型检查/生产构建；真实界面Agent23/Workflow13保存刷新执行1次，结果42，工具观测1，模型0，自动休眠。匿名加载401异常已修复，最终四角色只读浏览器/390px/结果权限PASS，34张截图。首次后端用例超时和首次浏览器FAIL过程证据均保留，未隐去或重放业务任务。

最终实例凭证撤销、实例休眠。保留/root/eap-tool-ui-evidence.json，browserExit=1为首次过程记录，finalBrowser=PASS为修复后的最终验收；不得重建任务或重复模型/工具执行。证据K8Substrate artifacts/eap-tool-ui-20260928/。镜像/版本见PROJECT_MEMORY.md最新条目及status.json，Runner沿用15309f2f...未改默认模板。

临时浏览器Pod已清理。下一阶段仍是凭证安全配送/自动续期、旧凭证迁移和认证数据持久化；实施条件INTEGRATION_HARDENING_NEXT.md。Keycloak仍emptyDir，不得换空PVC直接重启。继续全部远程编译测试，不自行commit/push；旧视频未重录。

---

# 最新状态：实例内工具执行和Workflow闭环已验收（2026-09-28）

参考Runner已内置显式启用的受控calculate_sum，工具在真实actor内执行。Workflow12 / Agent22 / external-actor-tools2-f6ee366b运行成功返回42并自动休眠；恢复后四并发重放仍只计算1次；敏感参数和专属策略阻断、改参拒绝、工具/Trace归属与最终撤销通过，模型0次。不是任意工具/MCP或模型自主选工具。

远程Runner9项顶层测试及5个场景子测试PASS，-race因节点CGO/C编译器不可用未运行。新镜像15309f2f2d04b1408523c777380616072c7c4cf782abc915e8e042555dcc7828仅用于独立验收模板；平台默认与旧模板未升级。后端323/前端125为原有基线，本轮未改未重跑。

实例最终休眠、凭证撤销、测试策略停用。保留/root/eap-actor-tools-v2-evidence.json，禁止重建callId或重跑；旧Agent19/20夹具也保留。证据K8Substrate artifacts/eap-actor-tools-20260928/，最终REVOKED四角色浏览器及390px页面PASS，工具总行数1，页面/API错误和业务写入均0，截图已导出；临时浏览器Pod清理。

首轮Agent21/Workflow11存在Span误分类导致的重复工具观测，已修复并用独立Agent22复验；旧记录/旧夹具保留在legacy过程证据。

下一阶段仍需凭证安全配送/自动续期、旧凭证迁移和认证数据持久化。具体条件INTEGRATION_HARDENING_NEXT.md，当前认证仍临时卷，不能直接换空PVC重启。工具参数暂从Workflow执行API的context.eapTool传入，图形界面工具配置尚未完成，普通界面执行不会复用此前API上下文。继续远程构建、不自行commit/push；新Runner须同时编译main.go与tools.go。

---

# 最新状态：外部工具执行前治理已部署并验收（2026-09-28）

已增加实例凭证工具检查→一次性领取→执行方回执、页面历史与Trace归属，并提供Python标准库参考客户端。原程序须适配；纳管不自动插入工具拦截，执行方报告不等于独立验证外部副作用。合同RUNTIME_TOOL_GUARD.md，完整进展见PROJECT_MEMORY.md最新条目。

远程后端323/323、前端125/125与类型检查/生产构建、Python客户端5/5，V54与四角色API/浏览器通过。Agent20 / eap-agents/external-tools-eb38c827：节点参考客户端合成回调执行1次、模型0次；3阻断、1待复核、2已领取未知、1执行方成功。两个未知行用于并发/领取丢响应测试，其回调未执行。不是在外部actor内执行工具。

最终凭证已撤销，工具三个接口均401；实例休眠，Agent范围测试策略停用，临时浏览器Pod清理。保留/root/eap-tool-guard-evidence.json，不重建callId或重放；旧Agent19/Workflow10夹具也继续保留。截图为READY阶段、最终撤销由API证据确认。证据K8Substrate artifacts/eap-tool-guard-20260928/。

后续可推进实际工具适配器/受控执行服务、凭证安全配送与续期、旧模板迁移、异步长任务恢复或生产设施；本轮不代表这些已完成。继续远程测试构建，不自行commit/push，不重复已完成的业务验收，旧视频未重录。

---

# 最新状态：外部智能体受控接入已部署并验收（2026-09-28）

已补齐外部实例专属凭证、签名握手、Workflow任务派发、受管模型与关联OTLP、换发与撤销，以及四项接入状态页面。原程序需适配协议并安装凭证，纳管本身不会自动开放执行。详见RUNTIME_CONNECTION.md和PROJECT_MEMORY.md最新条目。

远程后端316/316、前端123/123与生产构建、Runner5/5、四角色API与READY/REVOKED浏览器PASS。Agent19 / Workflow10保留两次执行：首次发现端点记账缺陷导致FAILED，修复后独立新任务SUCCEEDED；成功记录1次模型/641 tokens，首次用量不完整。验收脚本休眠选项曾放错位置，本次通过显式PAUSE清理，不计为自动休眠验收。最终凭证撤销、实例休眠；撤销请求未入队。

保留/root/eap-runtime-connection-evidence.json；不要重复prepare、重放业务任务或为截图重新签发。证据K8Substrate artifacts/eap-runtime-connection-20260928/。继续禁止本机编译和自行commit/push。后续可按用户指示推进外部工具前置治理、凭证安全配送/自动续期、旧模板凭证迁移、异步长任务恢复与生产设施；这些不因本轮接入成功而视为完成。旧视频未重录。

---

# 最新状态：运行实例纳管与智能体创建链路已验证（2026-09-28）

已补齐运行环境发现、外部实例复核纳管、身份绑定与状态/休眠/恢复；已补创建身份后的部署导航和接入进度。平台创建主链路经真实界面与模型调用验证。外部实例明确标记治理待验证，不能直接作为任务执行目标，不等同于内容/模型全面治理。详见 RUNTIME_ENROLLMENT.md、AGENT_CREATION_CHAIN.md、PROJECT_MEMORY.md 最新条目。

验收 Agent17、外部纳管 Agent18、阻断验证 Workflow9 均已留存；两实例休眠。保存 /root/eap-onboarding-evidence.json，严禁无意重复创建/模型试运行。最终远程构建、镜像、四角色权限/浏览器与截图见 K8Substrate artifacts/eap-onboarding-20260928/。本轮继续禁止本机构建与自行 commit/push。旧视频未重录；生产凭证隔离、外部程序协议/治理适配、无人值守认领仍有边界。

---

# 最新状态：导航、图标与业务命名已上线（2026-09-28）

用户要求按系统能力重排导航与页面、加图标，并尽量避免底层产品名。已完成六组业务导航、共享目录/标题/搜索/面包屑、工作台能力导航、计划与任务页面顺序、手机文字抽屉；主要名称使用“异步任务”“基础设施监控”“常规执行/隔离沙箱”。内部接口与历史数据保持原值。

远程前端115/115、类型检查和生产构建、四角色浏览器PASS；18入口可达、390px手机和1024px布局通过，无页面异常、无业务写入。已修复旧样式造成的桌面无法变窄和手机分组文字消失。前端镜像及证据见 PROJECT_MEMORY.md 最新条目，设计说明 NAVIGATION_INFORMATION_ARCHITECTURE.md。此前视频未重录。

不要重复创建验收任务或模型调用；后端统一串行编排状态保持下方记录。继续远程构建、不自行 commit/push。后续按用户具体指示推进。

---

# 最新状态：EAP / AX / Substrate 统一编排已上线并验收（2026-09-28）

本轮完成 Workflow/Team/Plan 的 RUNTIME/AX 后端选择、串行混合执行、结果交接、AX 任务归属与时间线下钻，以及原生/AX 两池统一监控。远程后端296/296、计划布局专项9/9、前端111/111与生产构建通过；四角色真实API及浏览器、390px手机验证PASS。

真实 Agent 14 → AX Agent 15 → Agent 16，Team 3 / Plan 4 / Workflow 8 成功；三次模型调用、四并发仅一次执行，三个沙箱均休眠。保留节点 `/root/eap-unified-evidence.json`。证据 K8Substrate artifacts/eap-unified-20260928/；合同 AX_WORKFLOW_INTEGRATION.md；详细镜像和验收见 PROJECT_MEMORY.md 最新条目。

本轮不需要重复执行夹具或录制视频。后续等待用户具体指示；并行、取消/补偿、长任务重启续跑、生产身份/TLS/灾备等尚未完成。继续远程测试构建，不自行 commit/push。下方为历史记录，之前“AX 仍为独立入口”的限制已被本轮串行统一编排取代。

---

# 最新状态：AX 接入与全功能演示视频均已完成（2026-09-28）

用户要求的全功能视频已交付：37 分 37 秒、1080p、中文字幕，无旁白；37 章节覆盖 19 个功能页面和主要子功能，含 imagegen 架构图、AX 实际提交、模型与团队结果、评估与治理。成片按业务流程编排，并区分实测、示例、配置入口和当前限制。交付目录为 K8Substrate `artifacts/eap-demo-video-20260928/`，打开 `index.html` 可按章节播放。

AX ECHO 实操已成功并休眠；COST_BUDGET 合成规则通过。数据集校准因 HASH_ONLY 跳过样本，视频如实说明，没有放宽保护策略。桌面与手机播放、章节跳转、无音轨、文件完整性已验证。详情见 PROJECT_MEMORY.md 最新条目。

本轮无需重复录制、重复创建数据集或重新运行既有 AX 验收。后续开发等待用户具体指示；AX 工作流节点、生产设施等仍属于后续范围。不要自行 commit/push。临时录屏 Pod 清理后，原始素材仍保留在 ate-node1 `/root/eap-video-20260928/`。

---

# 当前任务：AX 接入与无旁白演示视频（2026-09-28）

用户已明确授权“AX需要接入”，覆盖下方历史“仅评估、未授权”状态。AX 控制面、持久化 Redis 和独立 Worker 已部署；EAP 异步任务 API/UI、Runner、真实任务与四角色浏览器验收已完成；后端 288/288、前端 107/107、Runner 4/4。已修复 Keycloak 缺少 sub 与 AX 抽屉遮挡。参见 AX_EXECUTION.md。

后续保留用户视频任务：全面演示 EAP 各功能，实际操作与效果，中文文字说明，无旁白；系统架构图用 imagegen。先以 AX 最终验证结果更新架构图再录制。临时 eap-video-recorder Pod 已就绪，产物持久目录 /root/eap-video-20260928。

不自行 commit/push，不本机编译。不要重复先前已完成的前端修复。

---

# 最新状态：前端巡检问题已修复并上线（2026-09-28）

观测页内容权限导致的加载失败、工作流操作权限入口、三个页面手机裁切均修复；追加修复折叠菜单在路由/视口切换时的 open() 竞态。远程前端 104/104、类型检查、生产构建及四角色浏览器验收通过。证据 K8Substrate artifacts/eap-frontend-fix-20260928/，最终镜像和边界见 PROJECT_MEMORY.md 最新条目。无模型调用、无业务写验收，临时浏览器 Pod 已清理，未 commit/push。

用户最新关注：前端是否需要专业化设计和美化。已解释仍需要，建议下一轮围绕工作台、Agent 详情、工作流和监控统一信息层级、组件、状态和响应式体验；尚未开展全站视觉重设计。不要将这轮缺陷修复称为前端完全无问题，也不要未经验证把手机布局通过等同于触摸编辑器完成。

AX 仅完成可行性评估，尚未部署；计划保存后修改/删除、异步任务、并行与生产设施仍待推进。继续远程构建测试，保护现有 actor/凭证，不自行提交。

---

# 最新状态：长任务边界与 Substrate 可视化监控完成（2026-09-28）

30 秒/300 秒工作流成功；900 秒于总期限进入 EXECUTION_UNKNOWN，实际 actor 后续完成，直接路由 900 秒返回 200。同键重放未重复执行，独立 Agent 12 最终 SUSPENDED。详见 LONG_TASK_TIMEOUT.md。

监控已上线：运行与观测 → Substrate 监控。远程后端 279/279、前端 99/99 与构建通过，四角色 API/浏览器 PASS，viewer 移动布局及模拟故障 PASS。截图证据 K8Substrate artifacts/eap-monitor-20260927/；临时浏览器 Pod 已删除。最终镜像与配置见 PROJECT_MEMORY.md / SUBSTRATE_MONITORING.md。未 commit/push。

用户询问 google/ax 是否有用/可集成，已完成 AX_INTEGRATION_ASSESSMENT.md，尚未安装或接入；该询问不是完整部署授权。建议下一项为有界 AX 执行适配 PoC：固定版本、独立 atespace、合成工作区，验证完成/失败回执、幂等、watch 重连、挂起恢复、模型网关和权限归属。AX Ready/Running 不等于业务成功，需自定义 Runner 完成回执。

历史日志与基础设施 Trace 自动关联、异步长任务合同、并行编排、后台容量/空闲休眠及生产身份/TLS/持久化仍待推进。遵守远程构建测试、保护凭证与既有 actor、不自行 commit/push。

---

# 最新状态：计划表单与受权限保护的结果查看已上线（2026-09-27）

已替换示例式计划/步骤输入，并加入主动加载、纯文本呈现、权限与选择变化即清除的结果查看器。远程前端 95/95 与生产构建通过，四角色真实浏览器验收 PASS；仅新增一个未执行合成计划，未新增模型调用。后端未改，基线仍 272/272。证据：K8Substrate artifacts/eap-plan-editor-20260927/。详细镜像、边界见 PROJECT_MEMORY.md 最新进展。未 commit/push。

**下一项建议：长任务超时实测。** 按 SUBSTRATE_RUNTIME_INTEGRATION.md 的 P1 专项，在独立验收 actor 上测试 30 秒、5 分钟、15 分钟同步任务，记录客户端/路由器/Runner/执行日志的实际边界与 UNKNOWN 行为，避免自动重试产生重复外部效果。先检查现有超时配置和集群配额；使用合成无业务副作用任务，不占用演示 actor。基于测量再决定是否需要 V2 异步协议。

已保存计划文本尚不能修改/删除；并行、后台容量与空闲休眠、身份注入及生产认证设施仍未完成。继续遵守远程构建测试、保护既有实例和凭证、不自行 commit/push。

---

# 最新状态：Team/Plan 串行自动执行已上线（2026-09-27）

计划已复用 WorkflowExecutionService 完成冻结、原子入队、幂等提交、步骤结果交接和后台状态回写。远程后端 272/272、前端 89/89 与构建通过；真实 Team 2 / Plan 2 / Workflow 6 验收 PASS，四次并发只有一次执行，Agent 10/11 已自动休眠，V49 已应用。viewer/operator 远程浏览器验收也已通过；修复匿名加载 401 未捕获异常。详见 PROJECT_MEMORY.md 最新进展、PLAN_EXECUTION.md 和 K8Substrate artifacts/eap-plan-20260927/。未 commit/push。

**下一项建议：可用的计划编辑与结果查看。** 将团队页面现有示例式“新增计划/步骤”改为真实输入表单；按 VIEW_CONTENT 能力展示持久化步骤输出，维持普通时间线元数据脱敏，并补齐权限与选择切换竞态回归、远程浏览器验证。保留一 Plan 一执行语义，不借 UI 重试自动重复模型调用。并行编排与 P3 运营能力另行推进。

不要重新创建或轮换 Secret；保留节点夹具状态，避免重复 prepare 创建 Agent。所有编译/测试/构建/部署继续在远程执行，不自行提交或推送。

---

# 最新状态：Substrate 串行多 Agent 真实验收完成（2026-09-27）

已补齐 inputFrom 结果传递、输出留存/内容权限、成功后休眠，以及 JWT 下 Runner 的 OTLP 机器凭证注入和上报顺序。后端 264/264、Runner 2/2；真实 Agent 8→9 工作流、自动休眠、四角色输出权限、taskKey→Span→网关调用与幂等重放均 PASS。证据在 K8Substrate artifacts/eap-multi-agent-20260927/；详细镜像、实例与边界见 PROJECT_MEMORY.md 最新“Codex 串行多 Agent”进展。未 commit/push。

**下一项：Team/Plan 自动执行。** 当前 OrchestrationService 仅 CRUD/分配步骤，没有执行入口。应复用已有 WorkflowExecutionService，冻结有序步骤和 Agent 映射，建立 Plan 与执行日志的持久关联和幂等提交，再回写状态；不能只演示两个独立聊天就标记完成。inputFrom 仅支持串行前序结果，分支仍由初始 context 决定。

测试 Agent 8/9 v2 均休眠；原有 demo 未操作。专用观测 Secret 已配置，新模板会注入凭证；既有旧模板未升级。不要重复创建或轮换 Secret。Runner race 检查因节点缺 gcc 未能启动；未新增结果 UI，也未做本轮浏览器测试。

---

# 最新状态：JWT 与真实浏览器验收已闭环（2026-09-27）

已完成四角色真实浏览器登录、回调、刷新、按钮权限、退出，以及真实令牌过期后的 401 清理和重登。期间修复 Agent 登记/生命周期按钮权限遗漏及首次 callback 被认证弹窗遮挡的问题。前端 85/85 与构建通过，修复已部署；后端沿用上一轮 254/254 基线。

详情见 PROJECT_MEMORY.md 最后的“Codex 真实浏览器验收与修复”；证据在 K8Substrate artifacts/eap-jwt-browser-20260927/。未 commit/push。原交接中的“浏览器待验收”已完成；后续生产持久化、TLS、身份注入等仍按集成计划单独推进。

---

# 当前任务状态：已完成远程 JWT 上线与验收（2026-09-27）

下方原任务第 1–5 项已执行。后端 254/254，顺序专项 11/11，迁移专项 5/5，前端 82/82 与生产构建通过；Keycloak 已部署，EAP 已切换 JWT，四角色真实 PKCE 登录及 200/403 对照通过。详情与角色表见 PROJECT_MEMORY.md 最后的“Codex 集群验收与 JWT 上线”。

下一步建议：真实浏览器逐角色 UI 验收（按钮显隐、callback、401 重登）。本轮已验证节点 HTTP 授权码流程与前端回归，尚未完成该浏览器交互验收。不要重复创建或轮换现有 Secret；Keycloak Pod 替换后可用 K8Substrate tools/eap/keycloak-users.py 从现有 Secret 恢复密码。

未提交或推送。以下保留原任务说明作为验收依据。

---

You now have access to the PoC Kubernetes cluster that runs EAP on Agent Substrate. The user explicitly authorised giving you these connection details.

Connection (all from this Mac, through a jump host):
- Helper: `/Users/cpf/Documents/项目/联通/软件研究院/2026/K8Substrate/tools/n1.sh '<command>'` runs a command as root on ate-node1 (10.186.133.66); `n1.sh < script.sh` runs a script. It retries when the jump host drops the connection (exit 255). kubectl works on ate-node1 (context kubernetes-admin@ate).
- The SSH config and credentials are in that tools directory and in `../cluster`. Use them only through n1.sh; never copy them into the EAP repository, docs, commits or logs.
- Remote build/test/deploy procedure: read `/Users/cpf/Documents/项目/联通/软件研究院/2026/K8Substrate/tools/eap/README.md` first and follow it exactly. EAP source on the node is /root/eap-src (sync with tar as documented). Deployment manifest: `../tools/eap-poc.yaml`, live copy /root/eap-poc.yaml.
- Cluster context and known pitfalls: docs/SUBSTRATE_RUNTIME_INTEGRATION.md section 9 and docs/PROJECT_MEMORY.md (Substrate sections). Notably: Docker Hub/gcr/GitHub are unreliable — use the documented mirrors and localhost:5001; verify pod imageIDs after rollouts because failed image builds leave old images running.

Task — finish steps 3 and 4 of the handoff on the real cluster:
1. Remote-test your step-4 fix and any code changes: full backend suite and frontend tests+build via the README scripts. Report exact numbers.
2. Deploy Keycloak from deploy/substrate-poc/keycloak.yaml. Create Secret `keycloak-admin` with a random password generated ON THE NODE (openssl rand); set random passwords for the realm users with kcadm on the node and store them only in a Kubernetes Secret `eap-test-users` in namespace eap-auth (never print them in full, never write them to files outside the cluster).
3. Switch the EAP backend to JWT mode by editing tools/eap-poc.yaml (issuer, JWK URI, client id, allowed origins as in deploy/substrate-poc/README.md), rebuild images, apply, and roll out.
4. Verify for real: for each user obtain a token from Keycloak on the node (direct grant is NOT enabled for the console client; if you need tokens for verification, enable a separate confidential/test mechanism only inside the cluster, or use the authorization-code flow with curl), then call /api/platform/me and a representative allowed and forbidden endpoint for each role (e.g. POST /api/agents/{id}/substrate/deployments). Confirm /me capabilities match the real 2xx/403 results.
5. Extend tools/ate-ui-tunnel.sh so the browser can reach Keycloak at http://localhost:8180.

Rules:
- Never print, log or commit secrets: cluster passwords, the DeepSeek key (Secret eap-gateway), Keycloak admin/user passwords, tokens.
- Do not delete or modify the existing demo agents, Substrate system components or other namespaces beyond what this task needs. Do not reboot nodes.
- Do not git commit or push; Claude will review and commit.
- Update the "Codex 进展" note in docs/PROJECT_MEMORY.md (Chinese) with what was deployed and verified, including the per-role verification table. Print a summary at the end.


#### T5-C 导出与收尾确认（2026-10-06）

归档6230497bytes，SHA256=af6ca4c7b7108d2fed4024a207418690a9a6f3299f543b351fa0279834f35be5；本地1268项文件摘要、index链接、实际Secret/编码/历史机器凭证及嵌套源码包扫描PASS。入口K8Substrate artifacts/eap-platform-identity-20261006/index.html及同名tar.gz；20张四角色桌面/手机截图，手机最终步骤横排已目视复核。初次创建重放、回归和手机预检失败历史保留。

676源码摘要和相对固定T5-B的43文件补丁通过后才镜像/root/eap-src；合同与交接定向同步，PROJECT_MEMORY只追加保护BP。仅定向清理本轮20个Job和7个独立Pod，最终T5-C临时资源0。保留Agent49两个原v1 UID实例与显式v2实例/模板/快照及全部原意图、效果、授权、预算和旧T1–T5-B；原T4 Workflow15仍UNKNOWN，T5B保持关闭。持久认证原PodUID/旧emptyDir replicas0保持，5173新版buildId及8180原issuer仍可达。

tools/eap-poc.yaml只同步前后端及未来默认Runner三个摘要，旧模板和Secret引用/900秒保持；没有整份应用历史/root/eap-poc.yaml。导出后收尾脚本/状态独立保存，不重跑依赖已删除Job的build/deploy/export。节点eap-t5c-evidence.json completed=true及所有阶段键保留，禁止重新prepare/workflow/model/budget_model/new_version或换意图重放。

主线及整个T5仍未完成，继续T5-D其他执行入口/旧共享凭证迁移，再T6/T7/T8及调度公平/老化/阶段画像；没有确认Agent调优收益，旧演示视频未重录。全部测试构建部署浏览器远程，本机仅文本/摘要/脱敏证据，无commit/push。

#### T5-D1收尾确认

归档接收与本地1085摘要/25链接核对通过、admin桌面/手机/公钥配置已查看。按冻结UID和DeleteOptions UID前置条件仅清理本轮20个测试Job及26个测试Pod，剩余0；保护状态、4原生实例休眠/撤销及旧Workflow15 UNKNOWN再次确认通过。清理脚本与最终状态另留K8Substrate tools/eap/task-identity和节点，原归档SHA不再更换。
