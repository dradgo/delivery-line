# Architecture Decision Records

This directory holds the project's Architecture Decision Records (ADRs) — one numbered, flat Markdown
file per decision. Each ADR captures the context, the decision taken, the alternatives weighed, and the
consequences, so future contributors can understand *why* a structural choice was made.

New ADRs take the **next free number** on disk (do not reuse or backfill gaps — see the note below).
When you add one, append its row to the index table here.

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-unified-compose.md) | Unified Docker Compose | Accepted |
| [0002](0002-idempotency-stale-reservation-policy.md) | Idempotency Stale Reservation Policy | Accepted |
| [0003](0003-runner-secrets-mvp-posture.md) | Runner Secrets MVP Posture — Env-Var Injection Only | Accepted |
| [0004](0004-spec-stage-orchestration.md) | Spec-Stage Orchestration & Repo-Context Bundle | Accepted (2026-05-26) |
| [0006](0006-runner-queue-shared-pool.md) | Runner Execution Queue: Postgres `SKIP LOCKED`, One Shared Worker Pool | Accepted (2026-06-14) |
| [0007](0007-ticket-source-abstraction.md) | TicketSourceAdapter Abstraction (Extract from LinearAdapter) | Accepted (2026-06-17) |
| [0008](0008-repository-host-abstraction.md) | RepositoryHostAdapter Abstraction (Extract from GitHubAdapter) | Accepted (2026-06-18) |
| [0013](0013-credential-encryption.md) | Credential Encryption Primitive + Host-Env Master Key | Accepted (2026-06-20) |
| [0019](0019-structured-logging.md) | Structured Logging & Stable Correlation-ID Surface | Accepted |
| [0020](0020-github-rest-vs-graphql.md) | GitHub Integration: REST v3 + Spring RestClient (not the SDK, not GraphQL) | Accepted (2026-06-01) |
| [0021](0021-github-write-scope.md) | GitHub Write Scope & Egress Security Posture | Accepted (2026-06-01) |
| [0022](0022-git-cli-vs-jgit.md) | Repository Workspace Git: System `git` CLI behind an SPI vs embedded JGit | Accepted (2026-06-02) |
| [0023](0023-elk-replaces-loki.md) | ELK Stack for Centralized Log Capture (replacing the Loki proposal) | Accepted |
| [0024](0024-manual-execution-mode.md) | Manual Execution Mode (`manual` runner kind + `WaitingForManualExecution`) | Proposed (2026-06-21) |
| [0025](0025-live-observability-and-readonly-console.md) | Live Execution Observability & Read-Only Diagnostic Console | Accepted (2026-06-22) |
| [0026](0026-per-step-advisory-reviewer-model.md) | Per-Step Advisory Reviewer Model | Proposed (2026-06-21) |
| [0027](0027-obsolete-execution-soft-hide.md) | Obsolete-Execution Soft-Hide (Archive-Not-Delete) | Proposed (2026-06-21) |
| [0029](0029-complex-ticket-flow.md) | Complex Ticket Flow (Run Split, Parent→Child Lineage & Run Dependencies) | Proposed (2026-06-24) |
| [0030](0030-governed-delivery-tail.md) | Governed Delivery Tail (Build/Lint Gates, Review Modes, Push & PR/MR Governance, CI Investigation) | Proposed (2026-06-29); Decision 1 amended by 3h-1 |
| [0031](0031-remote-runner-architecture.md) | Remote Runner Architecture & Full-Access Execution Boundary | Proposed (2026-06-29) |
| [0032](0032-replay-safe-aftercommit-helper.md) | Shared Replay-Safe afterCommit Side-Effect Helper (B1) | Proposed (2026-07-04) |
| [0033](0033-recovery-service-scope-lift.md) | RecoveryService Scope Lift (Retire the Epic-1 Scope-Protection Tripwire) | Proposed (2026-07-08) |
| [0034](0034-rerun-safe-boundaries.md) | Rerun-from-Step Restricted to Safe Step Boundaries (Investigating/Executing) | Proposed (2026-07-11) |
| [0035](0035-failure-taxonomy-governance.md) | Failure Taxonomy Governance (Deprecate-Never-Remove) | Proposed (2026-07-12) |

## A note on numbering gaps

The sequence is **not** contiguous. Numbers 0005, 0009–0012, 0014–0018, and 0028 were never filed —
some were reserved notionally in early epic planning before later ADRs landed, and the reservations
were never converted into records. This is expected. **Always take the next free number on disk rather
than backfilling a gap**, and confirm the number is still free against the current branch at authoring
time (a sibling in-flight story could claim it).
