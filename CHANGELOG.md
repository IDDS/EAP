# Changelog

## [0.1.0.0] - 2026-09-02

### Added

- Added a versioned evaluation foundation for registering evaluators and datasets, running deterministic checks, and storing scores, confidence, rationale, and structured evidence.
- Added trace and RAG projection so normalized tool events, Trace spans, and knowledge interactions can be evaluated without coupling EAP to an external vendor platform.
- Added an asynchronous evaluation projection queue with worker scheduling, job status APIs, evaluation-window summaries, and AgentOps work items for failed evaluations.
- Added the “评估与可靠性” frontend workspace with evaluator catalog, manual runs, queued synchronization, result evidence, Trace links, dataset creation, and summary metrics.
- Added dataset sample APIs and a frontend SME annotation workflow with `PASS`/`FAIL`/`ABSTAIN` labels, rationale, score, and privacy-aware storage for regression-set feedback.
- Added evaluator-version persistence and request-fingerprint checks so historical results remain reproducible and conflicting `resultKey` reuse is rejected.
- Added the Galileo research memo and project memory entry documenting the internalized capture-to-feedback evaluation lifecycle.

### Changed

- Extended security rules, observability accessors, and work-item synchronization to support the evaluation plane while keeping automatic projection opt-in.
- Restricted evaluation sample writes and annotations in JWT mode, deriving the annotation actor from the authenticated principal and recording an audit event.
