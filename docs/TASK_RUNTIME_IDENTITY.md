# 任务运行实例的专属身份（T5-D1）

状态：2026-10-06远程回归已通过，尚未部署和真实全链路验收，不能称全部集成完成。

## 创建与冻结

新任务显式使用 PER_INSTANCE_V1。V63记录原生空间、固定镜像、网关地址与出站域名、公钥、固定配送请求、24小时授权期限、CreateActor回执UID和模板。相同提交键先返回原任务；配置变更、请求参数变化或撤销不能换任务意图、覆盖原UID或复活原批准。旧任务保留 LEGACY_SHARED，不回填UID、不升级旧模板。

底层使用官方 Task Condition 字段传递 EAPActorIdentity/CreateReceiptBound 证据；TaskStatus.id不是UID。创建接口丢弃调用方Status。控制器在原生Create前持久化不可重试的创建意图，只能记录成功Create回执UID。Redis记录无TTL、普通状态覆盖和任务删除不能清除。回执丢失、重建UID、模板不符均阻断，不能按名称接管或重建。

只读 /run/eap-identity 目录包含 atespace/name/uid。任务执行器配置 EAP_IDENTITY_DIR；通用执行器既有 EAP_IDENTITY_DIRECTORY 合同保持。新任务环境只允许身份模式、Agent编号、原任务键、网关地址、平台公钥及目录，不含共享机器凭证、供应商密钥或控制账号。

## 配送与执行

复用平台签名挑战、内存RSA加密短凭据、HMAC确认及撤销栅栏。新实例登记为 SUBSTRATE_TASK，仅可信任务工厂能写UID/模板。READY以配送确认和原生身份复核为前提；在HTTP POST前持久化RUNNING意图，随后只轮询原回执，进程重启、超时或结果未知不重新POST。

任务/结果HTTP、模型和观测都复核客体只读UID与短凭据。空闲不唤醒续期；派发和原回执查询前按需重装当前版本或续期，修复DATA休眠/GOLDEN恢复导致的内存凭据缺失。身份周期、原UID、授权期、工具根、预算和既有未知意图不能因此重置。默认短期900秒，接入授权最多24小时；续期不延长原授权。

机器模型入口要求原任务键、相同Agent/实例/UID/模板、RUNNING状态且未撤销。缺少任务头、其他任务、终态或更换调用标识均明确拒绝，旧共享网关不能关联专属任务。共享额度账本继续读取经验证的原Task头，模型意图固定 task:<taskKey>，不得因Trace变化或换调用键重复消费。任务回执Trace转换到实例命名空间，与网关和Agent Span对应；原始Trace保留为runtimeTraceId，不伪造Span。

任务实例不能走普通登记/心跳、人工批准、通用任务握手或通用工作流选择；本阶段只支持单任务模型与观测通道，不开放其他工具执行入口。终态沙箱操作仍只允许SUCCEEDED/FAILED，并核对原UID；在途或UNKNOWN不能被一般控制页中断。任务凭据撤销同时永久取消原任务授权，旧请求和新人工批准均不能恢复。

## 发布与验收边界

先完成底层、执行器和后端完整回归及前端测试/类型/生产构建，再构建固定digest。先发布兼容底层组件，冻结旧任务Runner配置并部署后端，再启用新任务模式；不能让排队旧任务切到新镜像或凭据，不整份应用历史集群清单。原生Redis/PVC、认证、配送密钥Secret、通用Runner、默认900秒、旧模板/快照和所有业务意图保持。

远程mock原生API、独立Redis及合成HTTP回归不等于真实集群全链路。上线后还需有界ECHO/模型/工作流任务、观测/预算归属、跨实例/到期/撤销拒绝、DATA/GOLDEN恢复、四角色桌面/手机及源码/镜像证据核验。正式状态和失败记录以CODEX_NEXT_TASK与PROJECT_MEMORY最新条目为准。

底层补丁及固定源摘要在runtime/ax-integration。内部可信HTTP与UID前后检查不是生产mTLS、原生分配代次的原子栅栏或完整Redis灾备。整个T5其他入口和旧凭据迁移、T6通用恢复、T7分级观测、T8策略以及调度公平/老化/画像仍待；没有Agent调优稳定收益证据。

## 时间读取与实际恢复边界（2026-10-07）

任务授权和短期凭证统一经JPA实体/类型化投影读取Instant，避免MySQL JDBC Timestamp与现有实体映射混用的8小时时差；不全局改变数据库时区、不延长旧授权。真实首回显的连接授权已缩短为16小时，保持该原批准；后续新任务按原24小时期限。

时区修复完整回归522/91与定向59/6通过并上线。真实自然到期和旧凭证401已核验；原实例恢复被后台心跳清理误标OFFLINE阻断，SUBSTRATE_TASK排除修复及有界真实恢复仍在验证。不能称任务身份真实全链路验收完成；普通HTTP心跳清理与显式OFFLINE边界继续保留。


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

#### T5-D1收尾确认

归档接收与本地1085摘要/25链接核对通过、admin桌面/手机/公钥配置已查看。按冻结UID和DeleteOptions UID前置条件仅清理本轮20个测试Job及26个测试Pod，剩余0；保护状态、4原生实例休眠/撤销及旧Workflow15 UNKNOWN再次确认通过。清理脚本与最终状态另留K8Substrate tools/eap/task-identity和节点，原归档SHA不再更换。
