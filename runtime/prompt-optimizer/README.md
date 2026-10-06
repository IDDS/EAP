# 受限提示词优化适配器

已部署有界组件，2026-10-05。合同：[PROMPT_OPTIMIZATION.md](../../docs/PROMPT_OPTIMIZATION.md)；方法依据：[华为交流报告映射](../../docs/research/agent-system-methods-20261002/huawei-report-methods.md)。

`search(context, backend)` 实际使用固定 GEPA 0.1.4 包，或者确定性简单搜索；所有评价/反思交给受管 `Backend`。无供应商客户端、权重训练、生成代码、留出正文、自动采用、启动即重放任务。`server.py` 是内部单任务服务，配套后端持久协调器、根预算、单次 claim、评价/反思接口、候选封存、服务端留出及统一权限页面已部署。适用范围是固定运行程序的无工具单轮文本任务；主线的结构优化、身份续期及恢复加固继续保留。

依赖只安装基础发行版，使用 `requirements.txt` 的精确 wheel hash，不安装 `full`/模型/RAG/云跟踪 extras。

- 官方发行版：[v0.1.4](https://github.com/gepa-ai/gepa/releases/tag/v0.1.4)
- 固定 commit：`8b0ce6cd99a234f6b74daf37558a2ac0ce18f975`
- wheel：`gepa-0.1.4-py3-none-any.whl`，SHA256 `12b971039599625c156d2231f6d72a29c31a22e9c237689459b5f1a3c353f532`
- 接口：[发行版源码](https://github.com/gepa-ai/gepa/tree/v0.1.4/src/gepa)

## 接口边界

Context 使用精确字段允许表，仅包含冻结版本、预算计数、基线、训练/搜索验证样本。只允许 `system_prompt` 字符串候选，按 UTF-8 字节限制。样本 ID、任务组和规范化输入查重，评价结果按 ID 对齐；缺失/重复/外来结果标 UNKNOWN，不选部分验证候选。

模型调用数不等于 GEPA metric_calls：一次 T1 配对样本需要基线与候选两次调用，反思另计。适配器计数只作本地保护，不能代替 EAP 数据库共享预算。`HttpBackend` 使用固定内部目标和内存中的单运行 claim；POST 结果未知只查询原意图，不能据 404 判定没有效果后重发。

**不要启用 GEPA 的 `cache_evaluation=True`。** 本次真实包测试发现，默认列表 DataLoader 的训练和验证 ID 均从 0 开始，缓存可复用相同候选的训练结果而未调用真实验证。当前关闭该缓存，仅使用 EAP 的根任务+候选+切分+样本 ID 稳定意图复用。反例测试把训练成功与验证失败分开，核验真实验证回调。

**GEPA 的 `raise_on_exception=True` 不足以保证反思错误不重试。** 固定版本的提案路径可能先捕获异常、再逐项回退；当前首次未知/合同错误被锁存，后续回退不能发出新反思。停止回调与最终检查会保留错误，不能以成功搜索掩盖 UNKNOWN。

## 验证方式

全部在远程容器执行，不本机安装/运行测试。

```sh
pip install --no-deps --require-hashes -r requirements.txt
python -m unittest -v test_adapter test_server
```

23 项远程测试（19 适配器、4 Worker 协议）包括固定真实 GEPA 包调用、简单搜索、训练/验证缓存隔离、反思未知防重、候选字段、正文用途接口、结果对齐、全部样本分母和不完整证据。测试 `FakeBackend` 为合成协议对端，没有调用真实模型，也没有证明实际 EAP callback、短期 claim、发布或算法收益；真实后端共享账本由独立 Java 集成测试覆盖。

真实 Agent45 已有两条独立任务：首条反思截断而失败，7 次调用、794 输出 Token，费用不清零；修复后第二条完成，16 次调用（2 次反思）、603 输出 Token。合计 23/32 次，全部结算，实例最终休眠。留出建议 KEEP_BASELINE，没有证明普遍收益；旧完成/未知意图不重放。后端完整 431、修复涉及回归 67、前端 175+类型/构建及四角色桌面/390px通过。详细证据见 PROJECT_MEMORY 和 K8Substrate artifacts/eap-prompt-optimization-20261005。

反思策略在根任务创建时冻结。对于明确支持的 `deepseek-flash` / `deepseek-pro`，仅反思调用显式 `thinking.type=disabled`，输出上限仍512；其他模型保留供应商默认，不猜测支持扩展参数。官方说明默认启用思考，首轮实测512输出全为推理，截断时仍按付费失败停止。[官方参数](https://api-docs.deepseek.com/guides/thinking_mode/)。评价模型、选项及运行基线均未被优化器修改。
