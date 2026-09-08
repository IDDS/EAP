# Changelog

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
