# AX 与 EAP 的集成判断

核查日期：2026-09-27。结论：值得作为可选任务执行后端做独立 PoC，尤其适合 Git 工作区、MCP/技能环境与任务沙箱的准备。当前尚未安装或集成 AX，以下是文档事实与架构建议，不是验收结果。

## 现有能力与边界

- AX 运行在 Agent Substrate 上，Task/Workspace/Model 分别承载执行单元、工作环境和模型配置；Workspace 支持 Git、MCP 与技能。见 [README](https://github.com/google/ax)、[概念](https://github.com/google/ax/blob/main/docs/concepts.md)。README 的大规模能力描述属于项目目标/声明，本环境未做规模验证。
- 控制面提供 Create/Get/List/Watch/Suspend/Resume/Delete Task 的 gRPC API，使用 Redis 和 Streams 保存状态并驱动控制器。它的 YAML 类型不是直接存在 etcd 的 Kubernetes CRD。见 [设计与 API](https://github.com/google/ax/blob/main/DESIGN.md)。
- 关键缺口：Runner 文档明确控制面目前不读取命令退出状态，Runner 在命令结束后继续存活；Ready/Running 是环境或沙箱状态，不能当成业务成功。自定义 Runner 可以使用 OnCommandExit 做完成回执与产物上传。见 [Runner 合同](https://github.com/google/ax/blob/main/docs/runner.md)。
- 同一文档描述 AX 的 suspend/resume 中进程重启、工作区恢复，因此不能不加验证就套用 EAP 当前 full snapshot 会话保持的语义。
- 项目提示稳定版前可能破坏性变更；自动空闲休眠、身份治理、自动轨迹采集等仍在 [路线图](https://github.com/google/ax/blob/main/docs/roadmap.md)。需固定提交与镜像，逐项验收，不能把路线图算成已完成。

## 建议的适配边界

EAP 管理业务计划、审批、角色能力、成本、结果与审计；通过 AxExecutionAdapter 映射 Task/Workspace/Model；AX 管理声明式任务环境与控制循环；Substrate 提供隔离、Actor 与调度。AX 管理的 actor 由 AX 执行生命周期操作，EAP 通过适配器发命令，避免两个控制循环互相覆盖。

EAP runKey/taskKey 与 AX atespace/task name 建立持久一一关联。Create 成功或 Ready 仅映射为已受理/可运行；成功必须来自匹配任务的完成回执和结果。超时保留 UNKNOWN，先查询实际执行，不自动换任务名重建。提交、状态监听断线重连、回执幂等、产物权限与 Trace 关联都需单独实现。

模型流量继续经过 EAP 网关。AX 默认模型配置/工作区引导能否完全使用该路径及现有非 Gemini 模型，需要验证；不能把模型 Secret 直接复制到每个工作区，不能绕过 EAP 治理和计费。调试入口按独立能力控制，生产默认关闭。

## 最小 PoC

独立 atespace、固定版本，使用合成公开工作区：EAP 提交 → AX 准备工作区 → Substrate 执行 → 自定义 Runner 回传退出码/结果/Trace。至少验收正常完成、失败、重复提交、watch 断线重连、挂起/恢复语义、模型网关归属及权限拒绝。现有 EAP V1 路径继续可用；PoC 通过后再扩大范围。

监控可视化继续从 Prometheus 与 Substrate Control API 接入。AX 任务状态未来可作为额外一层关联，AX 不提供本轮 EAP 可视化页面的替代品。
