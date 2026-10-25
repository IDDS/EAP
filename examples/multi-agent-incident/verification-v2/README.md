# 完整输出验收

本配置已完成一次独立验收：Team5 / Plan6 / Workflow30，Agent72/73/74；实际3调用、979输出Token、工具0，最终休眠。不要重跑已结束入口。[报告与原始输出](../../../docs/evidence/multi-agent-sample-20261018-v2/README.md)。

这是一份新的独立验收配置。原 Team 4 / Plan 5 / Agent 69–71 及其已用额度保留，不执行其旧任务。

新配置仍为分析、复核、报告三个独立 Agent，全部通过 EAP 创建、部署、调度、交接、观测和反馈。每名 Agent 限 1 次模型调用、1024 输出 Token、0 次工具；总量最多 3 次调用、3072 输出。平台总额度不提高，已有 Agent 额度不修改。这不是待单独授权的 28 次优化验收。

部署配置显式冻结 `maxTokens=1024`、`thinkingMode=DISABLED`。运行前核对 EAP 返回的实际 profile、镜像摘要、实例身份和剩余预算。只提交一次新计划，任何失败或未知都保留，不换键补跑。

新运行目录固定为 `/root/eap-sample-incident-20261018-r2`。测试和部署通过后，先将本轮前后端摘要写入 `sample.json.expectedApplicationImages`，再冻结配置及执行意图。此字段只作版本核对，不传入模型。不要复制旧 `state.json`，也不要删除已存在的新目录重建。

`review.py` 核对真实请求摘要、上游原结果、正常结束元数据及正文结构；仍需逐份阅读实际输出，确认事实、假设和人工建议分离。只有人工核对通过才将 `humanReviewPending=false`、`liveRunVerified=true`，随后登记 EAP 反馈和导出。一次通过不代表稳定业务质量。
