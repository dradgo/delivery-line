# Per-Step Execution-Control Walkthrough (Epic 3d)

> **Per-step execution-control walkthrough validator:** `_____________________________` (to be named before Epic 3d close)

This walkthrough is the **operator's end-to-end guide to per-step execution control** in the
DeliveryLine web app: configuring a project **reviewer model** and reading its **advisory
verdict**, running a step **manually** (download the context bundle → run the agent by hand →
submit the artifact), watching **live and finished step logs**, opening the **read-only
diagnostic console**, reading **provider limit status**, and **hiding** an obsolete execution
(and un-hiding it) — all in the browser, on your first pilot run, unaided.

It extends [`execution-walkthrough.md`](execution-walkthrough.md): that doc walks the
execution stage's review loop (plan → PR/output → accept / reject / take over); this one adds
the per-step controls that sit *around* those gates — a second-LLM opinion before you decide,
a way to run a step by hand when an agent can't run headlessly, the observability surfaces for
a live step, and the affordance to retire a run whose ticket is gone. It pairs with
[`project-configuration-walkthrough.md`](project-configuration-walkthrough.md) (where you bind
a project's connectors and credentials — the reviewer model rides that same per-project model).
This one is **entirely browser-based**: every surface lives in the UI, and the few CLI commands
shown are OS-neutral and work identically on Windows, macOS, and Linux (see
[`supported-environments.md`](supported-environments.md)).

**Target time:** ~15 minutes to exercise all six controls on a run.

---

## The one thing to remember

> **Every one of these capabilities is advisory, read-only, or reversible.** The reviewer
> verdict is a *second opinion* — it never decides for you and never gates the run. The
> diagnostic console is *read-only* — it cannot change the run or the workspace. Hiding a run is
> *reversible* and **never erases its history**. Nothing in this walkthrough takes an
> irreversible or authoritative action on a run — the human approve/reject decision in the
> [`execution-walkthrough.md`](execution-walkthrough.md) Decision Bar is still the only thing
> that advances or rejects the work.

---

## Before you start (prerequisites)

You need two things in place:

1. **DeliveryLine running locally.** Follow [`quickstart.md`](quickstart.md) end-to-end first.
   When the app is up, open it in your browser — the root URL redirects to the review queue at
   `/workflows`.
2. **A governed run in the execution stage.** These controls attach to a run that is executing
   (or has executed) a step. The reviewer verdict and the logs/console surfaces are most useful
   while a run sits in `WaitingForReview` or `Executing`; manual execution applies to a run
   dispatched under the `manual` runner kind; hide/un-hide applies to any run. See the
   [`execution-walkthrough.md`](execution-walkthrough.md) for how a run reaches those states.

You do **not** need any OS-specific setup, and **none of these controls is required** to run a
governed pipeline — they are opt-in aids layered on top of the standard execution loop.

---

## The per-step controls at a glance

The six controls, and what each one is for:

```text
 reviewer model + advisory verdict   →  a second-LLM opinion shown beside the Decision Bar
 run a step manually                 →  run the agent by hand when it can't run headlessly
 live + finished step logs           →  follow a running step's logs; replay them after
 read-only diagnostic console        →  look inside a live runner to diagnose a stuck step
 provider limit status               →  see a provider's usage windows (when it exposes them)
 hide / un-hide an obsolete run      →  declutter the queue without erasing history
```

Each is governed the same way every DeliveryLine action is: the backend reports, per run, the
**allowed actions** you may take, and the UI shows only the controls in that list. The rest of
this doc walks each control in order.

---

## Step 1 — Configure a project reviewer model (and read its advisory verdict)

A **reviewer model** is a **per-project (optionally per-stage) second LLM** that reviews a
step's output and produces a verdict — a second opinion before you, the human, decide. It is
**strictly opt-in per project**: a project with no reviewer binding behaves **exactly as it did
before** this feature existed (byte-identical), and the reviewer **never** auto-approves or
auto-rejects anything.

### Binding a reviewer model

The reviewer model rides the **same per-project connector/credential model** you configure in
the [`project-configuration-walkthrough.md`](project-configuration-walkthrough.md) — it resolves
through the project's connector resolution and its encrypted per-project credential, not a new
credentials subsystem. You bind a reviewer model (and its credential) on the project; a null
binding means "no reviewer."

> A note on **self-review.** If the reviewer model is the *same* model that produced the step
> output, the verdict panel shows a **warning** ("reviewed by the same model that produced the
> output") — it is flagged, never refused. The point of a reviewer is a *different* opinion; the
> panel tells you when that isn't the case.

### Reading the advisory verdict

When a run reaches `WaitingForReview`, the reviewer verdict is surfaced **beside the Decision
Bar** in the **Reviewer Verdict Panel** (`ReviewerVerdictPanel.tsx`), fed by
`GET /api/v1/workflows/{workflowRunId}/reviewer-verdict`:

```text
┌─────────────────────────────────────────────────────────────┐
│  Reviewer verdict   [ advisory — does not decide ]            │
│                                                               │
│  Outcome     ◇ concern                                        │  ← pass / concern / fail
│  Rationale   "Approach is sound but the error path on the     │
│               CSV writer isn't covered by a test."            │
│                                                               │
│  Reviewer    claude  (reviewed the output)                    │  ← model provenance
│  Producer    codex   (produced the output)                    │
└─────────────────────────────────────────────────────────────┘
```

What the panel tells you:

- **State.** The verdict has a state of **`pending`** (the reviewer is still running),
  **`available`** (a verdict exists), or **`unavailable`** (no verdict — with a reason). The
  panel renders only when there is something to show.
- **Outcome.** When `available`, the advisory outcome is one of **`pass`**, **`concern`**, or
  **`fail`**, with a redacted **rationale**.
- **Provenance.** The panel names the **reviewer model identity** (which model reviewed) and the
  **producer model identity** (which model produced the output), so "reviewed by a different
  LLM" is verifiable — and a same-model **self-review** is flagged as a warning.
- **It carries no action.** The panel is **presentational only** — it adds **no** governed
  action and never changes the Decision Bar. You still accept, reject, or take over exactly as
  in the [`execution-walkthrough.md`](execution-walkthrough.md). **The human decision always
  governs.**

Two failure modes shipped as graceful states, not errors:

- **No reviewer configured.** A project with no reviewer binding produces an `unavailable`
  verdict whose reason is `no_reviewer_configured`, and the panel **renders nothing** — the run
  looks exactly as it did before reviewer models existed.
- **A reviewer run that fails degrades gracefully.** If the reviewer model crashes or times out,
  the verdict is `unavailable` with a **"review unavailable"** reason (a failure category) — the
  **step is never blocked**. A missing second opinion never holds up the human review.

> **Advisory only — not a gate.** A `fail` verdict does **not** stop the run. The data model can
> support gating a project later (a per-project `reviewer_gating_enabled` flag exists), but that
> flag is **not consulted in this epic** and is **not** an operator control here — do not expect
> a failing verdict to block progression. See
> [What is NOT in this walkthrough](#what-is-not-in-this-walkthrough).

The canonical posture — advisory-now, gating-capable-later, explicit provenance — is recorded in
[`adr/0026-per-step-advisory-reviewer-model.md`](adr/0026-per-step-advisory-reviewer-model.md).

---

## Step 2 — Run a step manually

Sometimes an agent cannot run unattended: **headless / unattended auth may be unavailable** (for
example, a subscription-only Claude account with no API key cannot dispatch a step headlessly).
**Manual execution** exists for exactly this case — instead of launching a container,
DeliveryLine emits the step's context bundle and **parks the run** so *you* run the agent by
hand and submit the result back into the **same** governed pipeline.

> **Manual mode bypasses nothing.** A manually-produced artifact re-enters the **same
> runner-contracts output validation** and the **same review pipeline** as an automated runner's
> output. It is indistinguishable downstream from an automated artifact except in its recorded
> provenance — no validation is skipped, no review gate is bypassed.

### How a run gets parked

When a step is dispatched under the **`manual`** runner kind (a per-project / per-stage runner
choice), the dispatch path emits the context bundle and transitions the run into the
**`WaitingForManualExecution`** workflow state (the underlying runner-execution status is
`awaiting_manual`), appending a **`manual.executionRequested`** governed event. The run now
waits on you.

### The Manual Execution Surface

A parked run shows the **Manual Execution Surface** (`ManualExecutionSurface.tsx`), which walks
you through three moves:

```text
┌─────────────────────────────────────────────────────────────┐
│  Manual execution required   state: WaitingForManualExecution │
│                                                               │
│  1. Download the context bundle   [ Get bundle ]             │  ← redacted bundle
│  2. Run the agent yourself with that bundle                  │
│  3. Submit the resulting artifact [ Choose file ] / paste    │
│                                                               │
│     [ Submit artifact ]                                       │
└─────────────────────────────────────────────────────────────┘
```

1. **Obtain the (redacted) context bundle.** Download it from the surface, or via
   `GET /api/v1/workflows/{workflowRunId}/manual-bundle`. From the CLI:
   `deliveryline manual-bundle <runId> --out <file>`. The bundle is the same input bundle an
   automated runner would receive, with secrets redacted.
2. **Run the agent by hand** against that bundle, producing the step's output artifact.
3. **Submit the artifact** — by file upload or paste in the surface, or via
   `POST /api/v1/workflows/{workflowRunId}/manual-artifact` (CLI:
   `deliveryline manual-artifact <runId> --file <path>`). On a valid submission the run appends a
   **`manual.artifactSubmitted`** event and transitions into the normal post-step state
   (`WaitingForReview`), where it re-enters the standard review loop.

The governed actions involved are **`obtain_manual_bundle`** and **`submit_manual_artifact`**
(you'll see the corresponding buttons only when the run actually allows them).

> **An invalid artifact does not lose your work.** If the submitted artifact fails
> runner-contracts validation, the run **stays parked** in `WaitingForManualExecution` and is
> **resubmittable** — fix the artifact and submit again. Submitting against a run that isn't
> awaiting manual execution returns `MANUAL_EXECUTION_NOT_APPLICABLE`.

The full rationale (why `manual` is a runner *kind* and not a side-channel) is in
[`adr/0024-manual-execution-mode.md`](adr/0024-manual-execution-mode.md).

---

## Step 3 — View live and finished step logs

While a step runs, you can **follow its container logs live**; after it finishes, the same
viewer **replays the persisted log**. Both are served by one endpoint —
`GET /api/v1/workflows/{workflowRunId}/runner-logs/stream` (a Server-Sent Events stream) —
rendered by the **Step Execution Log Viewer** (`StepExecutionLogViewer.tsx`). The governed
action is **`view_runner_logs`**.

```text
┌─────────────────────────────────────────────────────────────┐
│  Step logs   ● live (following)            [ localhost-only ] │
│  ───────────────────────────────────────────────────────────  │
│  12:01:03  preparing workspace…                              │
│  12:01:07  running step 2 of 3…                              │
│  12:01:12  ▌ (live — new lines append as the runner emits)    │
└─────────────────────────────────────────────────────────────┘
```

- **Live mode** follows the running runner's container logs as they are emitted; a live-region
  announcement keeps it accessible.
- **Finished mode** serves the **already-persisted, post-hoc-redacted** log once the step ends —
  no separate raw-log store is introduced.

The safety posture, in operator terms:

- **Localhost-only.** The stream is served only over DeliveryLine's existing localhost binding to
  the single local operator — the same trust boundary as every other surface.
- **The authoritative redaction guarantee is the persisted post-hoc scan.** The *persisted* log
  is the one that has been fully secret-scanned (story 3.6) and is what ever enters
  shareable/export channels. **Live streaming redaction is best-effort** — a secret could
  momentarily flash in the *live* stream before the post-hoc scan would have masked it. That
  residual risk is accepted and bounded to the already-trusted localhost/local-operator boundary;
  it never changes what is persisted or exported.

This posture is recorded in
[`adr/0025-live-observability-and-readonly-console.md`](adr/0025-live-observability-and-readonly-console.md).

---

## Step 4 — Open the read-only diagnostic console

When a live step is stuck and the logs aren't enough, you can open a **read-only diagnostic
console** into the running runner container to look around in the moment. It is served by
`GET /api/v1/workflows/{workflowRunId}/diagnostic-console/stream` (an **input-disabled** SSE
attach) and rendered by the **Read-Only Diagnostic Console** (`ReadOnlyDiagnosticConsole.tsx`),
clearly badged read-only. The governed action is **`open_diagnostic_console`**, offered only
while a run is **`Executing`** (a live runner must exist).

```text
┌─────────────────────────────────────────────────────────────┐
│  Diagnostic console   [ READ-ONLY ]   [ live runner only ]    │
│  ───────────────────────────────────────────────────────────  │
│  (attached to run_a1c8f550 — receive-only, no input)         │
│  $ (container output streams here; there is no prompt to type) │
└─────────────────────────────────────────────────────────────┘
```

### Console safety (what it can and cannot do)

The console is deliberately constrained — read it before you open one:

- **Read-only.** There is **no input path end-to-end**: the docker attach is opened without
  stdin, and the UI is a receive-only stream with **no input widget**. Non-mutation is
  *provable* (no write channel exists), not merely policy. It **cannot mutate the run or the
  workspace**, and there is **no host shell**.
- **Live-only.** It attaches only to a *running* container. A finished or absent runner is
  rejected (`console-not-live`); no session is opened.
- **Governed history, recorded.** Opening and closing a console append **`console.opened`** /
  **`console.closed`** events (operator identity, runner-execution id, timestamps). **Only
  session metadata is recorded — console I/O is not durably stored.**
- **Localhost-only.** Served only over the existing localhost binding to the single local
  operator.
- **Changes nothing persisted or exported.** Nothing the console shows alters the persisted log,
  the artifacts, or any export — the post-hoc-redacted log remains the authoritative durable
  record (the same best-effort-live / authoritative-persisted distinction as the logs in Step 3).

The full threat model and the recorded security sign-off (this is a security-gated capability)
live in
[`adr/0025-live-observability-and-readonly-console.md`](adr/0025-live-observability-and-readonly-console.md) —
this walkthrough links it rather than re-deriving it.

---

## Step 5 — Read provider limit status

An agent provider (Claude, Codex) typically enforces usage windows — a rolling **5-hour** window
and a **weekly** window. Knowing where a provider sits in those windows would help you decide
**whether to run the next step automatically or fall back to manual execution** (Step 2). The
**Provider Limit Status indicator** (`ProviderLimitStatus.tsx`), fed by
`GET /api/v1/workflows/{workflowRunId}/provider-usage` (CLI:
`deliveryline provider-usage <runId>`), surfaces that signal. The governed action is
**`view_provider_usage_status`**.

> **Be honest about what you'll see today.** A spike during this epic found that **neither the
> Claude CLI / Anthropic API nor Codex exposes 5-hour or weekly window status programmatically in
> headless mode today.** So against the real providers, the indicator shows a **"not exposed by
> provider"** state — **never a fabricated number.** The plumbing is fully built and would
> surface real numbers *automatically* if and when a provider begins exposing the signal; the
> offline mock runner is what exercises the live-numbers path today.

What the indicator carries:

```text
┌─────────────────────────────────────────────────────────────┐
│  Provider usage                                              │
│   signal     not exposed by provider                        │  ← signalState
│   account    codex-pilot                                     │  ← accountReference (non-secret)
│   as of      —                                               │  ← provider-reported timestamp
│                                                               │
│  (When a provider exposes its windows, this shows the         │
│   5-hour and weekly usage instead of "not exposed".)         │
└─────────────────────────────────────────────────────────────┘
```

- **`signalState`** is **`available`** (a provider reported its windows) or **`not_exposed`**
  (the real providers, today).
- The status is **provider-reported and as-of a timestamp** (`asOf`) — it is a snapshot, not a
  live meter.
- It carries a **non-secret** `accountReference` label (which account the usage belongs to) and,
  when `available`, the `fiveHour` and `weekly` windows. The per-credential snapshots persisted
  behind it (`provider_usage_snapshots`) hold **no secret**.

So: read this section to understand the windows **conceptually** and how they'd inform the
automated-vs-manual choice — but expect **"not exposed by provider"** against real Claude/Codex
until a provider starts exposing the signal. The spike outcome and the not-fabricated rule are in
the 3d-7 record (see [References](#references-and-further-reading)).

---

## Step 6 — Hide (and un-hide) an obsolete execution

When a run's source ticket is gone (or the work is otherwise obsolete), you can **hide
(archive)** the run so it leaves the default queue — without erasing anything.

> **Hiding never deletes.** Archiving sets an `archived_at` marker on the run; it **never deletes
> rows and never touches `workflow_events`** — append-only audit history (FR47) is fully
> preserved. Hiding is **reversible**, and hidden runs **remain audit-queryable**.

How it works:

- **Hide.** `POST /api/v1/workflows/{workflowRunId}/archive` (CLI:
  `deliveryline archive <runId> --reason "<why>"`), governed by the **`archive_run`** action,
  appends a **`workflow.archived`** governed event. The run's workflow state is **unchanged** —
  only its visibility changes.
- **Un-hide.** `POST /api/v1/workflows/{workflowRunId}/unarchive` (CLI:
  `deliveryline unarchive <runId>`), governed by **`unarchive_run`**, appends
  **`workflow.unarchived`** and returns the run to the default queue.
- **Finding hidden runs.** The review queue **defaults to hiding archived runs**; pass
  `GET /api/v1/workflows?includeArchived=true` (or the equivalent "include archived" filter) to
  see them. Each summary carries an `archivedAt` marker so hidden runs are visibly distinct, not
  lost.
- **Archiving when it doesn't apply** (e.g. archiving an already-archived run) returns
  `ARCHIVE_NOT_APPLICABLE`.

Two boundaries to keep in mind:

- **Auto-archive on ticket removal is optional and default-off.** Detection of a removed
  source ticket *can* flag/auto-archive related runs, but that behavior is **opt-in** and **off
  by default** — the shipped, always-available trigger is the **manual** hide/un-hide above.
- **True purge is a separate Epic 5 concern.** Hiding is triage, not space reclamation; physical
  deletion / retention windows are **not** available here and belong to Epic 5. See
  [What is NOT in this walkthrough](#what-is-not-in-this-walkthrough).

The archive-not-delete decision is recorded in
[`adr/0027-obsolete-execution-soft-hide.md`](adr/0027-obsolete-execution-soft-hide.md).

---

## Concepts you just used

This walkthrough stays within DeliveryLine's established vocabulary — see
[`glossary.md`](glossary.md) for the canonical definitions, including the new Epic-3d entries it
registers:

- **[reviewer model](glossary.md#reviewer-model)** — the per-project second LLM that reviews a
  step's output.
- **[advisory verdict](glossary.md#advisory-verdict)** — its structured, non-gating outcome
  surfaced beside the Decision Bar.
- **[manual execution](glossary.md#manual-execution)** — the `manual` runner kind that parks a
  run for a human to run the agent and submit the artifact.
- **[WaitingForManualExecution](glossary.md#waitingformanualexecution)** — the state a run sits
  in while awaiting a manually-produced artifact.
- **[diagnostic console](glossary.md#diagnostic-console)** — the read-only, live-only,
  localhost-only console into a running runner.
- **[archived execution](glossary.md#archived-execution)** — a run soft-hidden from default views
  by an `archived_at` marker, reversible and never deleted.

It builds on the Epic-3 execution vocabulary (**[run](glossary.md#run)**,
**[review](glossary.md#review)**, **[queue](glossary.md#queue)**) and the Epic-3c project model
(**[project](glossary.md#project)**, **[credential](glossary.md#credential)**) — both covered in
their own walkthroughs.

---

## What is NOT in this walkthrough

This doc covers the **per-step execution controls** that surround the execution-stage review
loop. The following live elsewhere or have not shipped yet:

- **The execution-stage review loop itself** (plan / PR-output review, accept / reject / take
  over) — covered by [`execution-walkthrough.md`](execution-walkthrough.md), which this doc
  extends.
- **Live provider quota numbers from real providers** — **not exposed today.** The Claude /
  Codex CLIs do not surface 5-hour/weekly windows headlessly, so the indicator shows "not exposed
  by provider"; it would light up automatically if a provider begins exposing the signal (Step 5).
- **Reviewer gating** — the verdict is **advisory only** in this epic. The
  `reviewer_gating_enabled` flag exists in the data model but is **not consulted** and is **not**
  an operator control here; a failing verdict never blocks a run (Step 1).
- **Auto-archive on ticket removal** — an **optional, default-off** capability; the shipped
  always-on trigger is the manual hide/un-hide path (Step 6).
- **True purge / retention** — physical deletion of archived runs and retention windows are an
  **Epic 5** concern, not available here. Hiding is reversible triage, not deletion (Step 6).
- **Interactive (write-capable) console** — the diagnostic console is read-only by design; any
  move toward input forwarding requires a fresh security review (deferred).
- **Multi-user access control (RBAC) and remote access** — out of scope. All these surfaces are
  localhost-only and single-operator; allowed actions are status/role-recorded for the audit
  trail, not enforced as authorization in this release.

If you reach a state this walkthrough doesn't describe, that's a signal the UI has moved ahead of
the doc — flag it to the Per-step execution-control walkthrough validator named at the top.

---

## References and further reading

- [`execution-walkthrough.md`](execution-walkthrough.md) — the execution-stage review loop this
  doc extends.
- [`project-configuration-walkthrough.md`](project-configuration-walkthrough.md) — the
  per-project connector/credential model the reviewer binding rides.
- [`adr/0024-manual-execution-mode.md`](adr/0024-manual-execution-mode.md) — manual execution
  (`manual` kind + `WaitingForManualExecution`).
- [`adr/0025-live-observability-and-readonly-console.md`](adr/0025-live-observability-and-readonly-console.md) —
  live logs + read-only console posture and security sign-off.
- [`adr/0026-per-step-advisory-reviewer-model.md`](adr/0026-per-step-advisory-reviewer-model.md) —
  the advisory-now, gating-capable-later reviewer decision.
- [`adr/0027-obsolete-execution-soft-hide.md`](adr/0027-obsolete-execution-soft-hide.md) —
  archive-not-delete soft-hide.
- [`glossary.md`](glossary.md) — the canonical vocabulary (Epic 3d section).
