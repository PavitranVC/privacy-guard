# Privacy Guard: Context & Project Memory

> **Read this first.**

This repository contains **Privacy Guard**, an on-device, privacy-first Android security and privacy management utility targeting modern Android (Android 15+).

## Project Documents
- [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md) — Target hardware (Moto G54 5G), Android 15 environment, Shizuku privilege configuration, host setup, and external evidence locations.
- [docs/DECISIONS.md](docs/DECISIONS.md) — Chronological log of owner decisions, architectural choices, and rationale.
- [docs/STATUS.md](docs/STATUS.md) — Proven vs unproven capabilities, current milestone status, open items, and backlog.
- [docs/WORKING_RULES.md](docs/WORKING_RULES.md) — Mandatory developer rules, safety invariants, and verification protocols.
- [docs/architecture.md](docs/architecture.md) — Technical architecture specification across scanning, shield telemetry, OSINT, and Shizuku state machine.

## Roles & Workflow
- **Builder (Antigravity):** Implements code, tests, and documentation strictly within approved scope and rules.
- **Independent Auditor (Claude):** Audits commit ranges directly against repository contents and git history, not against builder summaries.
- **Architect Chat (Gemini):** High-level design guidance; cannot directly read or interact with the repository.

## Milestone Update Protocol
After completing every milestone:
1. Append new architectural/product decisions to [docs/DECISIONS.md](docs/DECISIONS.md) with ID, date, decision, source tag, and reason.
2. Update [docs/STATUS.md](docs/STATUS.md) with verified measurements, newly proven/unproven facts, updated open items, and backlog.
3. Update [walkthrough.md](walkthrough.md) documenting only what is provable from git history or captured raw evidence.
