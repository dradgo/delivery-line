# Glossary (Epic 1 seed)

> **Glossary discipline:** Any doc that introduces a new term beyond this canonical set must
> add an entry here in the same PR. Concept sprawl is tracked against NFR43.

This file is the **Epic 1 seed** — the seven PRD-canonical concepts the foundation slice
exposes through the CLI. Epic 2–5 vocabulary additions (spec lifecycle terms, runner pool,
takeover, export bundle, classification, etc.) are owned by Epic 6 stories **6.1** (full
documentation index) and **6.2** (full glossary audit). When you add a doc in Epic 2 or later
that uses a new term, register it here in the same PR — the Epic 6 audit will normalize
wording, not invent the vocabulary from scratch.

---

### ticket

An external work item — typically a Linear issue keyed by `LIN-XXX` — that an operator submits
into DeliveryLine for governed execution. The ticket reference (`<integrationType>:<externalRef>`,
e.g. `linear:LIN-101`) lives on the workflow run as a foreign anchor; the actual ticket content
stays in Linear.

**See also:** [`cli/workflow-commands.md`](cli/workflow-commands.md) (the `submit` command),
[`quickstart.md`](quickstart.md) (step 5).

### spec

The first artifact a governed run produces — a written, reviewable plan for how a ticket will
be executed. In Epic 1 the spec exists as a runner-output artifact only; the spec-review
lifecycle (approval / rejection / clarification) is owned by Epic 2.

**See also:** Epic 2 stories `2-8` through `2-19` (backend spec model + review surfaces).

### run

One governed execution of a ticket. A run owns a workflow state (`Inbox`, `Planned`, …,
`Completed`, `Failed`, etc.), an append-only event stream, and zero-or-more artifacts. Every
`deliveryline status` / `history` / `retry` invocation is keyed by a run ID (`run_XXXXXXXX`).

**See also:** [`cli/workflow-commands.md`](cli/workflow-commands.md),
[`failure-recovery-walkthrough.md`](failure-recovery-walkthrough.md).

### artifact

A durable output of a workflow stage — spec, implementation plan, PR reference, export bundle.
Artifacts are written through a controlled `artifact_operations` log so a partial write is
detectable; the lineage is the operator's anchor when a run fails mid-stage. Epic 1 ships the
skeleton; Epic 2+ stories add per-type content models.

**See also:** [`cli/workflow-commands.md`](cli/workflow-commands.md) (the `latest artifact`
line on `status` output).

### review

The operator (or stakeholder) decision step that gates a stage advancing. In Epic 1 review is
runner-driven only; the human-review surfaces (spec approval, rejection, clarification) land
in Epic 2 stories `2-8` through `2-19`.

**See also:** Epic 2 backend stories.

### clarification

A question a run raises before its spec can be finalised, and the answer a reviewer gives back.
Each clarification moves through an **incorporation lifecycle**: `open` (awaiting an answer) →
`answered` (answered, but the spec is not yet rebuilt — shown as "Answered · pending
incorporation") → `accepted` → `incorporated` (the spec has been rebuilt with the answer). Two
off-path outcomes exist: `superseded` (a newer answer/version set it aside) and `rejected_invalid`
(the answer was judged invalid). The make-or-break distinction the PM loop hinges on is
**answered ≠ incorporated** — approval should wait until a clarification reads `incorporated`.

**See also:** [`pm-loop-walkthrough.md`](pm-loop-walkthrough.md) (Step 3), Epic 2 stories `2-11`
(clarification model) and `2-12` (incorporation lifecycle).

### failure

A run state where execution cannot make forward progress without operator intervention. A
`Failed` run carries a `failure category` (from the `FailureCategory` registry) and a
`next safe action` (the recommended operator response — `retry`, `await_outcome`,
`await_manual_reconciliation`, `view_only`).

**See also:** [`failure-recovery-walkthrough.md`](failure-recovery-walkthrough.md),
[`cli/workflow-commands.md`](cli/workflow-commands.md#next-safe-action-matrix).

### recovery action

An operator-initiated action that moves a run out of `Failed`. Epic 1 ships exactly one:
`retry` (re-dispatch the last failed stage with a fresh context). Epic 4 adds `reconcile`,
`takeover`, `resume`, `rerun`, and `pause`. Each recovery action is recorded in the
`recovery_actions` table and emits an `intervention_marker=true` event so the audit trail
distinguishes operator-driven recovery from runner-driven progress.

**See also:** [`failure-recovery-walkthrough.md`](failure-recovery-walkthrough.md) (step 3),
[`cli/workflow-commands.md`](cli/workflow-commands.md) (the `retry` command).

---

## Linked from

This glossary is referenced from:

- [`quickstart.md`](quickstart.md) — "Concepts you just used" footer.
- [`pm-loop-walkthrough.md`](pm-loop-walkthrough.md) — "Concepts you just used" footer.
- [`setup-local.md`](setup-local.md) — "See also" footer.

Epic 6 stories (6.1 / 6.2) will wire cross-links from `failure-recovery-walkthrough.md`
and `cli/README.md` once the full documentation audit lands.
