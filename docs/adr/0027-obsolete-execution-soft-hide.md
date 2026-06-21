# ADR 0027 — Obsolete-Execution Soft-Hide (Archive-Not-Delete)

**Status:** Proposed (2026-06-21) — to be confirmed during Epic 3d story creation (3d-8)
**Driver:** Epic 3d (PRD FR67). When a source ticket is removed, the operator wants to retire the related run + artifacts + executions so the queue isn't cluttered with obsolete work. The constraint is that the system is **append-only** (FR47 / NFR4): history must never be erased or mutated. This ADR records that "remove" here means **soft hide/archive**, not hard delete.

## Context

FR47 and NFR4 make audit history append-only: retry, rerun, takeover, and reconciliation actions append history and must never erase or mutate it. Many tables already carry `archived_at` columns under the retention-readiness rule (e.g. story 3c-1's schema). Separately, Epic 5 owns retention windows and a `repoRemovedFromSource` job — the *true purge / data-lifecycle* concern.

The operator need here is a **UX/triage** one: "this run's ticket is gone; stop showing it to me." That is distinct from "erase this run's data," which would conflict with append-only history and overlaps Epic 5's retention mandate.

## Decision

**1. Soft hide/archive only — no hard delete in Epic 3d.** Retiring an obsolete execution sets `archived_at`/hide markers on the run (with a cascade *view* over its artifacts/executions/integration_links); it never deletes rows and never touches `workflow_events`. Append-only history (FR47) is fully preserved.

**2. Archived runs leave the default queue but stay inspectable.** The Run/Review Queue Item gains an archived/hidden state; the queue defaults to hiding archived runs and offers an "include archived" filter. Audit queries and direct inspection still reach archived runs — they are hidden, not gone.

**3. Trigger is operator-initiated and/or auto-on-ticket-removal.** An operator can hide a run explicitly (REST + UI, allowed-action-gated, audited as a governed event). Optionally, detection of source-ticket removal via the ticket-source adapter can flag/auto-archive related runs. Either way the archive action is itself an appended governed event (who, when, why).

**4. True purge stays an Epic 5 retention concern.** Any physical deletion of archived runs (data-lifecycle, retention windows, the `repoRemovedFromSource` job) is explicitly **out of scope** for Epic 3d and remains owned by Epic 5, where the FR47 implications of actual deletion are decided deliberately.

**5. Reversible.** Because it is a marker, un-hiding (clearing `archived_at`) is supported and audited — hiding is not a destructive one-way action.

## Alternatives Considered

### Alt 1 — Hard delete the run + artifacts + executions
**Rejected for Epic 3d.** Conflicts with append-only audit history (FR47/NFR4) and overlaps Epic 5's retention mandate; would need a deliberate FR47 carve-out + its own ADR. Soft-hide meets the operator's triage need without the reversal.

### Alt 2 — No hide at all; rely solely on queue filters by ticket state
**Rejected.** Operators explicitly want to retire obsolete work; depending only on implicit filtering leaves orphaned runs cluttering the queue with no governed "retire" action.

### Alt 3 — Implement hide inside Epic 5 retention
**Rejected (split instead).** The operator triage affordance is needed in Epic 3d alongside the other execution-control surfaces; the *purge* half legitimately belongs to Epic 5. This ADR draws that line.

## Consequences

### Positive
- Operators can declutter the queue of obsolete runs immediately, with a governed + reversible action.
- Append-only history (FR47) is untouched — archived runs remain fully auditable.
- Reuses existing `archived_at` columns; minimal additive schema.

### Negative
- Hidden-but-retained runs still consume storage until Epic 5 retention purges them — soft-hide is not space reclamation.
- An "include archived" path must exist everywhere obsolete runs might need to be found (audit query, inspection), or hidden runs feel lost.

### Neutral
- Auto-on-ticket-removal vs operator-only triggering is a 3d-8 detail; this ADR permits either or both, each as an appended governed event.
- The cascade over artifacts/executions/links is a *view/scope* concern, not a row-deletion cascade.

## References
- [Source: `_bmad-output/planning-artifacts/sprint-change-proposal-2026-06-21.md`] — Epic 3d proposal (D4, Risk #5); FR67.
- `_bmad-output/planning-artifacts/epic-05-export.md` — retention / `repoRemovedFromSource` (the true-purge owner).
- `docs/adr/0004-spec-stage-orchestration.md` — ADR format.
- `docs/glossary.md` — `archived execution` / `soft-hide` entries to be introduced (3d-10).
