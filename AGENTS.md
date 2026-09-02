> **Important:** Before writing or updating this `AGENTS.md`, read the `cursor-agents-md` skill when it is available.

# DeepResearch Project Instructions

## Project Overview

This repository is EAP, an enterprise agent control plane. Research in this
workspace may evaluate external agent platforms and their fit with EAP.

## Research Guidelines

- Prefer primary sources: official product pages, documentation, API references,
  security/privacy pages, repositories, and dated company announcements.
- Record access dates and distinguish facts, judgments, and speculation.
- Use at least two independent source types for important technical or business
  claims where practical.
- Compare external capabilities with the current EAP implementation described in
  `docs/PROJECT_MEMORY.md` and the actual code.
- Treat vendor claims as claims, not independent validation.

## Boundaries

- Do not send source code, credentials, customer data, or private telemetry to
  external services.
- Do not modify EAP business code or deployment state as part of research unless
  the user explicitly requests an implementation or proof of concept.
- Do not infer undocumented integration guarantees; mark unknowns and propose a
  bounded validation test instead.

## Deliverables

- Provide a conclusion-first research memo with source links, evidence strength,
  uncertainty, integration options, risks, and a concrete PoC plan.
- Keep a clear separation between direct Galileo integration, EAP-native
  implementation, and capabilities that require both systems.
