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

## Epic 3 vocabulary (execution stage)

These terms are registered here per glossary discipline; Epic 6 story 6.2 normalizes wording.

### worker pool

The fixed-size pool of background workers that drains the runner [queue](#queue) and dispatches
agent jobs. Sized by `deliveryline.runner.worker-pool.size` (default `2`, clamped 1–32) with a
master switch `deliveryline.runner.worker-pool.enabled`. The pool size is how many runner jobs
can execute concurrently — DeliveryLine's parallelism model.

**See also:** [`execution-walkthrough.md`](execution-walkthrough.md) ("How parallel execution
works").

### queue

The FIFO queue that holds dispatched runner jobs until a [worker pool](#worker-pool) worker
picks one up. It has back-pressure at `deliveryline.runner.queue-max-depth` (default `100`):
enqueuing beyond the cap raises `RUNNER_QUEUE_FULL` (HTTP 503, retryable) and queues nothing,
protecting the host rather than failing silently. Inspect it with `deliveryline workers status`.

**See also:** [`execution-walkthrough.md`](execution-walkthrough.md) ("How parallel execution
works").

### takeover

The developer action that stops orchestrator dispatch, cancels the in-flight and queued runner
executions (moving them to a `cancelled_for_takeover` status), and transitions the run to the
`TakenOver` terminal state while preserving all prior context (artifacts, audit trail, PR link).
**Non-reversible in Epic 3** — the developer continues the work in the linked GitHub PR; Epic 4
adds takeover-revert and operator-side closure.

**See also:** [`execution-walkthrough.md`](execution-walkthrough.md) ("When and how to take
over").

### PR linkage

The DeliveryLine-owned record tying a run to its GitHub pull request — the PR reference
(`org/repo#42`), its lifecycle state (draft / open / merged / closed), and the last-sync time.
The PR reference + state are backend truth (authoritative); the branch/commit values are
runner-emitted and shown in the untrusted region of the PR/output artifact.

**See also:** [`execution-walkthrough.md`](execution-walkthrough.md) ("Reviewing the PR /
output").

### branch reference

The deterministic git branch a run's workspace is prepared on:
`deliveryline/{ticketSlug}/stage-{runIdShort}` — the ticket reference slugified plus the run
id's last 8 characters (e.g. `deliveryline/LIN-123/stage-abc12345`). It is the branch the
developer continues work on after a [takeover](#takeover).

**See also:** [`execution-walkthrough.md`](execution-walkthrough.md) ("What happens after spec
approval").

### implementation plan

The artifact a run produces after spec approval — an ordered list of steps for executing the
ticket, the first developer-review gate. Rendered as the implementation-plan variant of the
artifact review panel.

**See also:** [`execution-walkthrough.md`](execution-walkthrough.md) ("Reviewing the
implementation plan").

### PR/output

The artifact a run produces after the implementation plan is accepted — the changed-file diff
plus the [PR linkage](#pr-linkage) (branch, commit, PR reference + state), the second
developer-review gate.

**See also:** [`execution-walkthrough.md`](execution-walkthrough.md) ("Reviewing the PR /
output").

---

## Epic 3c vocabulary (multi-project credentials)

These terms are registered here per glossary discipline; Epic 6 story 6.2 normalizes wording.

### project

The first-class aggregate every governed [run](#run) is scoped to. A project binds a repository,
a pair of selectable [connector](#connector) kinds (a ticket source and a repository host), the
encrypted per-role [credentials](#credential) those connectors use, and run options (e.g. the
OpenSpec toggle). A project has a status — `active` or `disabled` — and advertises a
status-derived `allowedActions` list (no role dimension; no RBAC). A seeded **`default`** project
is migrated transparently from the prior single-host configuration and preserves single-project
parity; it can never be disabled. Runs are associated with a project at submission / intake.

**See also:** [`project-configuration-walkthrough.md`](project-configuration-walkthrough.md),
[connector](#connector), [credential](#credential).

### connector

The selectable, vendor-neutral adapter a [project](#project) binds for one of its two integration
roles — a **ticket source** or a **repository host** — chosen by `ConnectorKind`. The registered
kinds are `linear`, `github`, and a `gitlab` **proof-of-seam** stub (a documented demonstration of
per-project resolution, not a full vendor implementation). Each project resolves its connectors
**per project at run time**, so two projects can use different vendors. A connector authenticates
with the project's per-role [credential](#credential).

**See also:** [`project-configuration-walkthrough.md`](project-configuration-walkthrough.md),
[project](#project), [credential](#credential).

### credential

The **write-only**, envelope-encrypted per-role secret a [connector](#connector) uses at call
time, keyed by connector role (`ticket_source` / `repo_host`). A credential is set or replaced but
**never read back, never pre-filled into a form, never placed in the DOM, and never exported**;
the API returns only an id (`cred_…`) and a `configured` status, never the secret. At rest it is
protected by [credential encryption](#credential-encryption) under the host-supplied
[master key](#master-key), and it is redacted from every egress.

**See also:** [`project-configuration-walkthrough.md`](project-configuration-walkthrough.md),
[`adr/0013-credential-encryption.md`](adr/0013-credential-encryption.md),
[credential encryption](#credential-encryption), [master key](#master-key).

### master key

The single 256-bit **key-encryption key (KEK)** that wraps every per-credential
[data key](#credential-encryption) in DeliveryLine's credential subsystem. It is resolved at
startup from the `DELIVERYLINE_MASTER_KEY` host environment variable (Base64-encoded), is **never**
persisted to the database or any file, and is identified by a stable, non-secret `keyId`
(`mk_` + the first 12 hex of its SHA-256). Because the master key lives in the host environment, the
credential subsystem defends against an at-rest database compromise but **not** a host compromise —
an attacker who can read the host environment can read the master key. A startup guard refuses to
boot if the master key is missing while encrypted credentials exist.

**See also:** [`adr/0013-credential-encryption.md`](adr/0013-credential-encryption.md),
[credential encryption](#credential-encryption).

### credential encryption

The **envelope-encryption** scheme that protects per-project connector credentials at rest. Each
secret is encrypted under a fresh random 256-bit **data key (DEK)** with AES-256-GCM; the DEK is then
wrapped by the [master key](#master-key). The stored form records the ciphertext, the wrapping
`keyId`, and the cipher-suite tag (`algo` = `AES-256-GCM`) so both key and suite rotation are
non-breaking. GCM authentication makes tampering with a stored ciphertext detectable on decryption
rather than yielding silent partial plaintext.

**See also:** [`adr/0013-credential-encryption.md`](adr/0013-credential-encryption.md),
[master key](#master-key).

---

## Epic 3d vocabulary (per-step execution control)

These terms are registered here per glossary discipline; Epic 6 story 6.2 normalizes wording.

### reviewer model

A per-project (optionally per-stage) **second LLM**, resolved through the project's
[connector](#connector)/[credential](#credential) model, that reviews a step's output and
produces an [advisory verdict](#advisory-verdict). It is **strictly opt-in per project** — a
project with no reviewer binding behaves byte-identically to before — and **never gates
progression** in this epic (a gating-capable `reviewer_gating_enabled` flag exists in the data
model but is not consulted). The verdict records which model reviewed and which produced the
output, so a same-model self-review is detectable.

**See also:** [`per-step-execution-control-walkthrough.md`](per-step-execution-control-walkthrough.md),
[`adr/0026-per-step-advisory-reviewer-model.md`](adr/0026-per-step-advisory-reviewer-model.md),
[advisory verdict](#advisory-verdict).

### advisory verdict

The [reviewer model](#reviewer-model)'s structured outcome — `pass` / `concern` / `fail` plus a
redacted rationale and the reviewer/producer model identities — surfaced in the `WaitingForReview`
Decision Bar's **Reviewer Verdict Panel** as a second opinion. It is **presentational and
advisory only**: it carries no governed action, never auto-approves or auto-rejects, and the
**human approve/reject decision always governs**. A no-binding project yields an `unavailable`
verdict (reason `no_reviewer_configured`) and renders nothing; a failed reviewer run degrades to a
"review unavailable" reason without ever blocking the step.

**See also:** [`per-step-execution-control-walkthrough.md`](per-step-execution-control-walkthrough.md),
[`adr/0026-per-step-advisory-reviewer-model.md`](adr/0026-per-step-advisory-reviewer-model.md),
[reviewer model](#reviewer-model).

### manual execution

A first-class **`manual`** runner kind that, instead of launching a container, emits the step's
context bundle and parks the [run](#run) in [WaitingForManualExecution](#waitingformanualexecution)
so an operator can run the agent **by hand** and submit the resulting artifact. The submitted
artifact re-enters the **same** runner-contracts output validation and the **same** review
pipeline as an automated runner's output — manual mode changes the *producer*, not the contract,
and bypasses no validation or review. It exists because an agent's unattended/headless auth may be
unavailable.

**See also:** [`per-step-execution-control-walkthrough.md`](per-step-execution-control-walkthrough.md),
[`adr/0024-manual-execution-mode.md`](adr/0024-manual-execution-mode.md),
[WaitingForManualExecution](#waitingformanualexecution).

### WaitingForManualExecution

The workflow state a [run](#run) sits in while awaiting a manually-produced artifact (see
[manual execution](#manual-execution)). It is entered on a `manual`-kind dispatch (which appends a
`manual.executionRequested` event and emits the context bundle) and exited on a valid
manual-artifact submission (`manual.artifactSubmitted`) into the normal post-step state
(`WaitingForReview`). An invalid submission leaves the run parked and resubmittable.

**See also:** [`per-step-execution-control-walkthrough.md`](per-step-execution-control-walkthrough.md),
[manual execution](#manual-execution).

### diagnostic console

A **read-only, live-only, governed-history-recorded, localhost-only** console attached to a
*running* runner container for in-the-moment diagnosis. It **cannot mutate the run or the
workspace** (no input path exists end-to-end — no stdin at the docker layer, no input widget on
the UI; no host shell), records **only session metadata** (`console.opened` / `console.closed`
events; console I/O is **not** durably stored), and changes **nothing** persisted or exported. A
transient secret may flash to the single local operator before post-hoc redaction — an accepted,
documented residual bounded to the already-trusted localhost boundary.

**See also:** [`per-step-execution-control-walkthrough.md`](per-step-execution-control-walkthrough.md),
[`adr/0025-live-observability-and-readonly-console.md`](adr/0025-live-observability-and-readonly-console.md).

### archived execution

A [run](#run) soft-hidden from default operator views via an `archived_at` marker. Archiving is
**reversible** (un-hide), **never deletes rows and never touches `workflow_events`** (append-only
audit history, FR47), and leaves the default queue while remaining **audit-queryable** (an
"include archived" filter). The shipped trigger is a manual hide/un-hide (REST + CLI,
allowed-action-gated, audited); auto-archive on ticket removal is optional and default-off. It is
distinct from **true purge** — physical deletion / retention, an Epic 5 concern not available
here.

**See also:** [`per-step-execution-control-walkthrough.md`](per-step-execution-control-walkthrough.md),
[`adr/0027-obsolete-execution-soft-hide.md`](adr/0027-obsolete-execution-soft-hide.md).

---

## Epic 3h vocabulary (pre-review quality gates)

### build stage

A pre-review **build-validation** gate (`RunnerStage.BUILD`, FR75, story 3h-1). When a governed
project has a **build command** configured and the stage is enabled, the produced code is
compiled/built **before it is pushed or reviewed**, so review and delivery only ever see buildable
code. Per the [ADR 0030](adr/0030-governed-delivery-tail.md) amendment, BUILD executes
**backend-side** — a `ProcessBuilder` (behind the `BuildCommandPort` SPI) runs the command in the
already-materialized host workspace, **not** inside the runner container. It is still recorded as a
`runner_executions` row (`stage = 'build'`) reusing the story-3.6 raw-output capture + the 3d-5
per-step step/log view, but it runs **no LLM** and therefore records **zero token/provider usage**
(its token columns stay `NULL`). **Default disabled** — a project with no build config skips BUILD
entirely (byte-identical to pre-3h). A non-zero build exit drives a **bounded auto-fix loop**: the
implementation runner is re-dispatched with the redaction-policed build-error log attached as a
`build.failure` referenced feedback entry, capped by `build-fix-max-loops` (default 3); on the
attempt that reaches the cap the run transitions to `FAILED` with the `runner_build_failed`
`FailureCategory` and the shared per-run escalation marker is flipped once, leaving the run for
Epic-4 recovery. The code is **never** pushed past an unresolved build failure.

**See also:** [`adr/0030-governed-delivery-tail.md`](adr/0030-governed-delivery-tail.md),
[`adr/0032-replay-safe-aftercommit-helper.md`](adr/0032-replay-safe-aftercommit-helper.md).

---

## Linked from

This glossary is referenced from:

- [`quickstart.md`](quickstart.md) — "Concepts you just used" footer.
- [`pm-loop-walkthrough.md`](pm-loop-walkthrough.md) — "Concepts you just used" footer.
- [`execution-walkthrough.md`](execution-walkthrough.md) — "Concepts you just used" footer (Epic 3 vocabulary).
- [`project-configuration-walkthrough.md`](project-configuration-walkthrough.md) — "Concepts you just used" footer (Epic 3c vocabulary).
- [`per-step-execution-control-walkthrough.md`](per-step-execution-control-walkthrough.md) — "Concepts you just used" footer (Epic 3d vocabulary).
- [`setup-local.md`](setup-local.md) — "See also" footer.

Epic 6 stories (6.1 / 6.2) will wire cross-links from `failure-recovery-walkthrough.md`
and `cli/README.md` once the full documentation audit lands.
