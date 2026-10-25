# 前端 HTTP 就绪与发布验收

2026-10-21 已在远程 PoC 部署。此次仅调整前端 Deployment 的 HTTP 就绪探针、稳定时间和滚动更新设置；前后端镜像及应用源码保持原值。

## 问题与证据

上轮前端发布曾在 Pod Ready 后读取 `index.html` 遇到连接拒绝。此次只读检查确认线上没有 `readinessProbe`，容器运行即可成为 Ready；这不能保证 Nginx 正在提供页面。该历史瞬时错误缺少更细事件记录，不能排除 Service 端点传播参与其中。

本轮在同节点创建两个独立 Pod，运行相同的合成 HTTP 服务：无探针的 Pod 在响应 503 时仍为 Ready；加上生产同款探针的 Pod 为未就绪，Service 无可用端点。切换为 200 后可经 Service 访问，再切回 503 会被移出可用端点；恢复 200 后重新可用，重启次数为 0。实验不修改或唤醒任何 Agent。

## 部署配置

唯一配置来源为 [frontend_readiness.py](../deploy/substrate-poc/frontend_readiness.py) 的常量，由 `configure` 生成 JSON Patch：

- `readinessProbe` 请求 Pod 的 `HTTP :80/index.html`，每 2 秒一次、单次超时 1 秒，连续成功 2 次就绪、连续失败 2 次取消就绪。
- `minReadySeconds=5`，新 Pod 稳定就绪后再计入可用副本。
- `RollingUpdate`、`maxSurge=1`、`maxUnavailable=0`，升级时保留旧副本，直到新副本满足可用条件。

脚本限定当前 PoC 的单副本、单 `frontend` 容器 Deployment。目标必须给出 UID 和不可变镜像 digest；变更时使用 UID、resourceVersion、镜像原值 CAS。只改变上述三个字段；配置已满足时返回空补丁。没有加入自动重启策略。

禁止整份应用历史 `eap-poc.yaml` 覆盖当前环境。此前已关闭批次的发布脚本只作历史证据，不再执行。

## 发布门槛

`verify` 只执行读取，要求连续三轮同时满足：

1. Deployment UID、generation、不可变镜像和就绪配置均未漂移，控制器已观察当前 generation，副本更新并可用。
2. 仅剩目标 Pod，容器 Ready，实际 `imageID` 对应预期 digest。
3. Service 选择器一致，未启用 `publishNotReadyAddresses`；可用 EndpointSlice 端点均绑定目标 Pod UID，没有旧端点或终止中的端点。
4. 经 Service 读取 `index.html`、`version.json`，均返回 200，内容 SHA-256 与**受测构建产物**一致。

连接拒绝、超时、断连、408/429 和 5xx 可以在同一总时限内重读。任何成功计数都会在未就绪或 HTTP 瞬时失败后重置。重定向、401/404、错误内容摘要及部署身份漂移立即失败。HTTP 请求由 curl 和子进程超时共同约束，响应体、头及 curl 错误输出不写日志。

默认时限 120 秒，CLI 最长 600 秒。本轮真实验收设为 180 秒。发布写入只执行一次；确认丢失或门槛失败后先检查当前状态，不能因为 HTTP 重试而再次部署或重派业务任务。

## 后续发布使用

只在远程 PoC 运维节点执行。先同步脚本并核对 SHA-256；变量值来自本次发布前核实的 Deployment UID、目标镜像及受测构建产物，不能从待验证服务反向生成预期摘要。

```bash
python3 -B frontend_readiness.py configure \
  --uid "$EAP_FRONTEND_DEPLOYMENT_UID" --image "$EAP_FRONTEND_EXPECTED_IMAGE"

python3 -B frontend_readiness.py verify \
  --uid "$EAP_FRONTEND_DEPLOYMENT_UID" --image "$EAP_FRONTEND_EXPECTED_IMAGE" \
  --index-sha256 "$EAP_FRONTEND_TESTED_INDEX_SHA256" \
  --version-sha256 "$EAP_FRONTEND_TESTED_VERSION_SHA256" --timeout 180
```

镜像替换仍由具体发布流程以 CAS 执行；本脚本的 `configure` 不替换镜像。后续发布应保留探针配置，并把 `verify` 纳入验收。

## 本轮验证与边界

远程 `python3 -B -m unittest -v test_frontend_readiness` 共 19 项通过，覆盖连接拒绝后恢复、总时限、慢响应、503、重定向/404、内容摘要不符、旧端点、Pod/部署漂移和幂等补丁。真实隔离 Pod 和线上验收另行留证。详见[批次证据](evidence/frontend-readiness-20261021/README.md)。

该门槛确认页面服务、镜像和版本，不等同于所有业务功能正常。探针具有检测延迟，单次发布的离散 HTTP 采样不能证明零中断或长期高可用。本轮未重跑未改应用的后端/前端完整套件、浏览器、模型和 eBPF 实验。
