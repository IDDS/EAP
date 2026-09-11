# Changelog

## [0.3.1.0] - 2026-09-11

- 接入 AdminLTE Vue 指标/信息盒/面板与 D3 拓扑缩放、拖动和定位。
- 大屏突出业务解决状态与登记收益，保留潜在调用路径动画。
- Flyway V45 保存生产端 Span 起止时间，新增 Trace 时间诊断与请求竞态保护。
- 新增任务证据接口、任务诊断抽屉与 Trace 往返，隔离冲突关联。
- 后端208项、前端62项测试基线；OTLP任务映射与Runtime恢复仍待完成。

## [0.3.0.1] - 2026-09-11

- 运行指挥大屏提亮蓝色背景、面板、文字与拓扑连线，增加指标色条。
- 仅前端样式更新，无后端或数据库变更。

## [0.3.0.0] - 2026-09-11

- 新增运行指挥大屏、拓扑搜索/分页/关联聚焦/放大及可控轮播。
- 运营工作台默认展示待处理事项，手机事项卡片，真实环境与刷新状态。
- 持久评估扫描、租约和固定上界，完整已接收 Trace 证据与显式完成信号。
- 后台评估阶段/真实计数/PARTIAL，工作流执行过程与证据可视化。
- Flyway V44；后端 202 项与前端 54 项回归基线。

## [0.2.1.0] - 2026-09-08

- Automatically project captured model outputs into derived MODEL spans and metadata-only MODEL_CALL_STATUS evaluations (Flyway V43).
- Keep analysis independent from ingestion, serialize concurrent projection, and persist up to three failed attempts with aggregate status in the evaluation console.
- Skip scoring unknown outcomes and prevent content-derived spans from implying business task completion without explicit evidence.
- Copy only whitelisted numeric usage/status metadata into trace evidence; keep existing body access controls.

## [Discovery Agent 1.5.1 / instrumentation 1.2.2] - 2026-09-08

- Fixed missing LiteLLM input capture by implementing synchronous and asynchronous callbacks.
- Preserved Python loader resource APIs so LiteLLM offline model-cost fallback can read packaged resources.
- Added sanitized delivery-failure diagnostics and regression tests; verified all Go packages and four Python tests, including the deployed LiteLLM 1.80.0 environment.

## [0.2.0.0] - 2026-09-08

### Added

- Added immutable calibration history, independent Agent history pagination, and read-only AgentOps dashboards with persisted signal freshness.
- Added opt-in workflow HTTP execution with durable task evidence, deadlines, submission idempotency and node edit versions (Flyway V39–V42).

### Fixed

- Enforced authenticated approval identities and connector write permissions, isolated stale UI responses and permission failures, and lazy-loaded console routes.
- Corrected mixed-currency ROI, Runtime heartbeat expiration, concurrent enrollment token usage, bounded gateway response reads and terminal task transitions.
- Isolated evaluation transactions, recovered timed-out jobs without automatic retries, and prevented duplicate workflow edges and stale editor overwrites in the updated console.

### Validation

- 185 backend tests and 34 frontend tests; frontend production build and Flyway H2 V1–V42. Live deployment checks are recorded in the deployment report.

## [0.1.0.0] - 2026-09-02

### Added

- Added a versioned evaluation foundation for registering evaluators and datasets, running deterministic checks, and storing scores, confidence, rationale, and structured evidence.
- Added trace and RAG projection so normalized tool events, Trace spans, and knowledge interactions can be evaluated without coupling EAP to an external vendor platform.
- Added an asynchronous evaluation projection queue with worker scheduling, job status APIs, evaluation-window summaries, and AgentOps work items for failed evaluations.
- Added the “评估与可靠性” frontend workspace with evaluator catalog, manual runs, queued synchronization, result evidence, Trace links, dataset creation, and summary metrics.
- Added dataset sample APIs and a frontend SME annotation workflow with `PASS`/`FAIL`/`ABSTAIN` labels, rationale, score, and privacy-aware storage for regression-set feedback.
- Added evaluator-version persistence and request-fingerprint checks so historical results remain reproducible and conflicting `resultKey` reuse is rejected.
- Added dataset batch evaluation and calibration metrics, including TP/FP/TN/FN, Precision, Recall, F1, Accuracy, and a frontend confusion matrix; withheld observations are skipped safely.
- Added the Galileo research memo and project memory entry documenting the internalized capture-to-feedback evaluation lifecycle.

### Changed

- Extended security rules, observability accessors, and work-item synchronization to support the evaluation plane while keeping automatic projection opt-in.
- Restricted evaluation sample writes and annotations in JWT mode, deriving the annotation actor from the authenticated principal and recording an audit event.
