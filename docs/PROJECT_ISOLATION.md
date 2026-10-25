# 项目独立部署

EAP 支持以独立部署作为项目边界：每个项目使用自己的数据库、数据库账号、控制台 origin、OIDC audience/client、机器凭据、运行空间和构建命名空间。项目内继续沿用 `PlatformPermission` 的角色权限，控制台只读取 `/api/platform/me` 返回的能力。

此方式不提供同一数据库内的行级项目隔离，也不提供跨项目切换与全局汇总。当前共享 PoC 数据不会自动划分或移动到新项目。独立部署需要更多资源；共用集群的管理员仍属于所有项目共同的信任边界。

## 身份与数据库

部署配置 `EAP_SECURITY_PROJECT_KEY` 是固定项目标识，格式为小写字母开头、最多48位小写字母、数字或短横线。启用时必须同时配置 JWT、issuer 与 audience。每个登录令牌必须包含精确的标量字符串 `eap_project`，与部署标识一致；数组、对象、缺失声明、大小写变化均拒绝。管理员角色也不能越过项目边界。请求头、查询参数、浏览器存储不能选择数据库或项目。

V93 增加数据库绑定记录。默认未绑定，保留旧单信任域的行为。第一次绑定必须由运维明确设置 `EAP_SECURITY_INITIALIZE_PROJECT_BINDING=true`；绑定成功后应关闭该初始化开关。绑定操作在数据库事务和行锁内完成，两个不同项目不能同时抢绑。后续启动只接受同一项目；换项目、关闭项目配置或删除绑定记录都拒绝启动，不会自动改写归属。

初始化开关会把整个数据库归入指定项目，因此只能由运维在独占安装窗口启用。新安装器先核实空库；已有数据库转换必须先核对全部历史数据归属、备份并停止所有旧进程，不可在另一个旧版后端仍运行时绑定。同一数据库的多个进程不得混用新旧边界配置。数据库超级用户仍可修改绑定表，这不是对数据库管理员的隔离。

`/me.project` 返回 `isolationMode` 和 `projectKey`。`DEDICATED_PROJECT` 表示本进程执行固定项目认证和数据库绑定；`SHARED_TRUST_DOMAIN` 表示原共享范围，不代表项目隔离。控制台从此响应显示项目名称，不解析 JWT 推断归属。

## 运行与构建

项目 `alpha` 的 Substrate 范围固定为 `eap-alpha-agents` 和 `eap-alpha-ax`。原生控制客户端在创建、删除、查询、位置查询、分页、模板、休眠、恢复、回退和出站策略操作之前检查范围。AX 客户端也在打开传输前检查配置范围。后缀避免 `alpha` 与 `alpha-ax` 两个项目发生空间重叠。

源码构建仅访问 `eap-alpha-builds`，生成仓库名如 `eap-alpha-src-a1`，避免不同数据库里的相同 Agent 编号产生同名镜像仓库。范围检查不能替代 Kubernetes/Substrate 原生权限、镜像仓库授权和网络策略。部署服务账号必须只能控制本项目空间；构建账号、registry 路径、持久卷与快照前缀均须专属。AX 服务本身也需要受控网络入口。

每个项目须独立生成运行身份签名/配送密钥、采集令牌、网关凭据、正文加密密钥和连接器凭据；不要复制另一项目的 Secret。机器接口使用已有实例/代际或专用凭据校验，不接受用户填写的项目字段作为身份证明。此版本未提供跨项目代理或联邦身份。

## 安装

采用 [project.example.json](../deploy/delivery/install/project.example.json) 的 `EAP_INSTALL_V2 / PROJECT_CONTROL_PLANE_BOOTSTRAP` 配置，沿用 [首次安装流程](../deploy/delivery/install/README.md)。项目模式要求 Schema93 以上的配套发布、独立 `eap-alpha` namespace、`eap_alpha` 数据库、`eap-alpha-api` audience 与 `eap-alpha-console` clientId。命名空间除安装 UUID 外还须有 `eap.io/project=alpha` 标签。

在 IdP 为相应客户端配置受信任的 `eap_project=alpha` 声明；只给获准进入该项目的用户分配客户端角色/访问权。不能把所有已登录用户自动映射到全部项目。PKCE、issuer、签名、时效、subject、audience 与现有角色检查继续生效。

安装包只开启控制台，运行、模型与异步派发保持关闭。它准备项目范围名称，但不会替代企业 IdP 配置、原生权限、TLS、NetworkPolicy、数据库备份或真实业务接入。上线前须分别完成正确项目登录、跨项目拒绝、数据库重启持久化、专属运行身份和业务验收。

各批次验证状态与部署摘要记录在 `VALIDATION.md` 与对应证据目录；实现文档本身不是生产验收证明。


## 本轮验收

已部署 private-pilot-20261025-project-boundary-r1 / Schema93。后端1449、MySQL专项23、前端538、安装器39和线上8角色场景通过；另2项目名展示夹具与真实项目认证明确区分。PoC数据库保持未绑定，未声称原生RBAC/网络或两个真实项目部署已完成。[验证范围](VALIDATION.md)。
