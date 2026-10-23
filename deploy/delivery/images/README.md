# 从固定基础镜像构建交付镜像

所有命令在授权的远程 Linux 构建节点的 `deploy/delivery` 目录执行。使用已经测试并保留摘要的 JAR / dist，不从上一版 EAP 应用镜像继续叠加。`runtime-bases.json` 固定受测 linux/amd64 JRE 与 nginx 镜像，仓库名需替换为经核验的目标镜像仓库；新仓库必须先镜像复制、校验 digest，再使用该环境的锁文件。这里不提供新集群安装或跨架构保证。

## 产物输入

每个组件使用独立目录：

```text
backend-artifacts/
  app.jar
frontend-artifacts/
  nginx.conf
  dist/
    index.html
    version.json
    assets/...
```

同时提供独立 JSON 产物清单，键为相对上述目录的路径，值为完整 SHA256。例如后端清单包含 `app.jar` 一项，前端包含 `nginx.conf` 和所有 `dist/` 文件。清单必须绑定测试报告及受测产物，不能在发布时对未知产物临时计算摘要后宣称已经测试。

工具核对完整文件集合、每个文件摘要、路径、符号链接及容量限制（最多 4096 个文件、512 MiB）；它不读取 Kubernetes Secret，也不执行编译、构建、推送或部署。生成上下文会复制产物，原产物目录保持。输出目录必须不存在；失败时留下的目录不能继续使用。

```bash
python3 image_build.py prepare backend \
  --artifacts /release/backend-artifacts \
  --artifact-manifest /release/backend-artifacts.json \
  --output /release/backend-context

python3 image_build.py prepare frontend \
  --artifacts /release/frontend-artifacts \
  --artifact-manifest /release/frontend-artifacts.json \
  --output /release/frontend-context
```

把成功输出的 `contextSha256` 保存到该批次独立的发布回执中。构建前重新核对，使用该外部固定摘要，不能从待检查的上下文重新计算替代：

```bash
python3 image_build.py verify \
  --context /release/backend-context \
  --expected-context-sha256 RECORDED_CONTEXT_SHA256
```

`--runtime-bases /release/runtime-bases.json` 是全局参数，放在 `prepare` / `verify` 前面。产物与上下文核验只证明字节身份，返回的 `testsVerified` 和 `deployed` 均为 false。

## 构建及发布门槛

1. 构建节点预检 `nodefs` / `imagefs` 的实际驱逐阈值和剩余空间，保留本轮测试、构建及镜像解包的余量。节点没有 `DiskPressure` 不能单独证明余量足够。
2. 远程构建器使用生成的 `Dockerfile` 和固定版本自身镜像；本 PoC 使用 Kaniko `--single-snapshot`，最终仅在原始基础层后增加一层。不要添加 `--reproducible`：本轮观察到它改写基础层 blob digest，因此无法通过原层摘要核验。这里仅保证上下文确定性，没有声称重复构建必得相同镜像摘要。
3. 校验输出 manifest/config 的 digest、linux/amd64、基础层完整前缀及一层增量；校验新增层中的 JAR 或完整静态树与受测产物一致，同时核对 nginx 配置。保留入口、用户、环境、端口、停止信号等运行配置。本轮没有升级基础系统或依赖版本，也没有完成漏洞扫描。
4. 后端独立探针检查 UID 10001、JRE、curl、目录权限及 JAR；前端独立实例检查全部静态资源、版本、深链接、缓存头、缺失资源 404 和后端代理。
5. 通过后按[交付升级次序](../README.md#升级与回退次序)切换。后端使用 Recreate，确认旧 Pod 消失后恢复原策略；核对 Schema、健康、真实登录与原任务账本。前端核对现网 HTTP 载荷。镜像精简不等于业务代码重新全量回归。
6. 保存构建日志与失败证据，按登记的 UID 及时清理临时 Pod。不要为清理磁盘删除现网镜像、回退镜像或历史业务记录；镜像体积下降不等于同量磁盘已经释放。

模板中的 `@RUNTIME_IMAGE@` 由工具替换为锁定引用，不能直接交给构建器使用。后端基础镜像须已提供 curl，模板不会在线运行 apt/npm/maven；前端清空基础镜像自带页面后复制本次完整 dist，避免遗留旧资源。现有 `backend/Dockerfile`、`frontend/Dockerfile` 保留为开发构建路径，这个目录是受测产物的交付构建路径。

远程测试入口：`python3 -m unittest -v test_eapctl test_image_build`。完整 CI/CD、签名与 SBOM、独立新环境安装验收另行完成。
