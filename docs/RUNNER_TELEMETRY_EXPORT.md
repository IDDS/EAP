# Runner 观测导出边界

日期：2026-10-09。本批为T7-B第一批，已闭环：两个Runner远程普通、竞态与编译，未来默认固定镜像、健康/镜像内容/历史保护、证据接收和4Job/4Pod清理均通过；绿色源码精确四文件推进至709摘要。后续诊断采样与跨入口覆盖仍为方案，见[OBSERVABILITY_ROLLOUT_PLAN.md](OBSERVABILITY_ROLLOUT_PLAN.md)。

## 行为

- 通用任务、工具任务、对话和AX任务的导出最多等待2秒，禁止跟随HTTP重定向，响应体最多读取4096字节。模型HTTP客户端仍保留原110秒执行期限，复制客户端后的导出期限不会修改它。
- 失败Span只保留固定失败状态，不发送原始异常。导出失败日志只有固定文字或HTTP状态数字，不记录URL、响应正文或原始运输错误。原操作键、Trace、时间与已有允许的属性保留。
- 对话在业务执行返回时捕获结束时间，后台导出使用该时间。后台排队与导出耗时不进入执行时长。同步任务和AX任务也在发起导出前固定时间。
- 通用Runner从同一加锁快照读取实例键和凭据；已配置实例身份却缺少实例键/凭据时停止导出，不能降级到旧观测入口。AX保留既有只读Actor UID、期限与专属任务身份校验。真实旧协议继续沿原边界，新版本不自动迁移旧消费者。
- 导出失败保留观测缺口，不重试模型、工具或导出，不修改原UNKNOWN、费用、预算、意图及回执顺序。实验入口继续沿[实验观测合同](EXPERIMENT_TELEMETRY.md)，本批未改动它。

本批没有修改权限定义、后端/前端源码或数据库结构；现有服务端实例认证与归属校验保持。采样、全入口登记分母、日志指标和统一问题入口仍待实施，不能用已收到Span的数量推断对话完整率。

## 远程验证

原实现隔离测试：两个Runner各4项合成HTTP合同均按预期失败，分别覆盖错误文本进入Span、跟随跳转、超过2秒导出边界和原始URL进入日志。原负向包和结果保留；没有使用真实凭证、业务正文或供应商，不能据此声称发生生产泄露。首归档根目录校验在解包与起测前拒绝，辅助修正记录保留。

最终候选共四文件：两个`main.go`和两个新增`telemetry_export_contract_test.go`；远程gofmt后，709项候选源码与本地工作区逐项摘要一致。每个Runner新增6项合同，另两项覆盖后台导出的固定执行结束时间与实例身份缺失禁止降级。远程通用Runner普通/竞态各33项顶层、59项含子项，AX普通/竞态各18项，两个编译均退出0。原意图、参数、回执、UNKNOWN与身份回归一并通过。

后端与前端源码没有改变，分别沿此前634项/95类和220项/类型/构建证据，本批没有重复它们的构建或浏览器验收。测试使用假HTTP；模型和真实工具效果新增均0。本批没有创建新的Substrate Actor或完成真实业务模型验收。

当前构建必须包含身份和实验观测文件及全部测试。远程Pod内进入对应Runner目录，对整个包执行：

```sh
CGO_ENABLED=0 GO111MODULE=off go test -count=1 -timeout 90s -v
CGO_ENABLED=1 GO111MODULE=off go test -race -count=1 -timeout 90s -v
CGO_ENABLED=0 GO111MODULE=off go build -trimpath -o /out/runner
```

竞态测试在远程Pod配置C编译器。旧README和K8Substrate历史脚本中的显式两/三文件列表只对应当时版本，不能用于当前构建。

## 发布边界

仅更新后端Deployment中的两个未来默认值：

| 配置 | 固定镜像 |
| --- | --- |
| `EAP_SUBSTRATE_RUNNER_IMAGE` | `10.186.133.66:5001/eap-runner@sha256:a69969ea4f41c7e1c58624dd242c5b4a29d0533f9e45ab4745f75c0ba7c63df2` |
| `EAP_AX_RUNNER_IMAGE` | `10.186.133.66:5001/eap-ax-runner@sha256:3a7f0d9d0604bd194a93eb828bdb320b48d0a3c04e14d07b6e9019108e88b10e` |

resourceVersion和原字段值的CAS限制修改范围；更新前确认工作流、优化、实验和AX入口均没有活动任务。后端镜像仍为`7275f8e98ba515d1867b2faf79f4f52e3a85ac3a9ad8a8e3379153861fab9008`，加载新环境会更换后端Pod。旧任务冻结的镜像、已发布模板、Actor UID/状态、旧共享凭据、认证与配送Secret不被默认值更新迁移。

仓库中的[两个默认值补丁](../deploy/substrate-poc/runner-export-defaults.json)记录同一固定摘要，供维护现有部署时核对。实际发布使用远程脚本中的原值/resourceVersion CAS，没有重应用整份清单。补丁不是旧模板迁移脚本；后续部署应核对这些值，避免历史全量清单覆盖当前默认配置。

独立保护基线冻结33张表2703行的全字段摘要，并核对两个atespace中的原Actor/模板、认证及配送Secret、应用镜像和其他环境。旧UNKNOWN审计、FAILED及KEEP_BASELINE保持。实际Secret原文和编码只在节点内存扫描，不导出秘密材料；清理必须使用本批固定Job/Pod UID前置条件。本批不commit/push。

实际更新后的后端PodUID为`7a6fcfbd-e37f-46b6-9ef5-a45b7dc8a244`，健康UP、原镜像imageID及MySQL68核对通过。两个仓库manifest/config/layer摘要与层内二进制逐字节核对，证明镜像来自测试构建；没有将此称为新原生Actor的业务验收。

[验收索引](evidence/eap-runner-export-boundary-20261009/index.html)、[最终回执](evidence/eap-runner-export-boundary-20261009-final.json)：173257bytes/SHA256`773f85055a913bb4523cd36a1b4d2e4af8ca5fda60f24d96750c94c2aaa1ce36`，43文件摘要/42索引链接/四源码副本/709源码本地核对。4Job/4Pod按冻结UID清理、剩余0，最后33表2703行及身份/费用保护PASS。归档中绿色基线推进为待办的是导出时检查点，最终回执单独确认已完成，原归档不覆盖。节点`/root/eap-t7b-out-v1/final-completion.json` completed=true/closed=true；不要重跑清理后的Job、原模型/工具或覆盖旧证据。
