# Sprint Change Proposal — Per-Step Execution Control, Observability & Manual Execution (new Epic 3d)

- **Date:** 2026-06-21
- **Author:** Alex (via Correct Course workflow)
- **Trigger type:** New capability cluster + deliberate (narrow) scope-boundary reversal of the runner sandbox/governed-access posture
- **Affected epics:** New **Epic 3d** inserted between Epic 3c and Epic 4; ripple into Epics 4 and 5
- **Change scope classification:** **Major** (narrows the sandbox posture; adds a new workflow state + dispatch mode; new PRD FRs + architecture ADRs; new UI surfaces)
- **Mode:** Incremental (each planning-artifact edit reviewed + applied before moving on)

---

## Section 1 — Issue Summary

Five (now six) operator-facing capabilities are needed before the pilot that all concern **how an individual workflow step is executed, reviewed, observed, and retired** — a layer that sits on top of the per-project configuration Epic 3c just delivered, and partly overlaps the recovery/observability surfaces planned for Epic 4:

1. **Per-step review by a different LLM (project-configurable).** Each project can bind a *reviewer model* so a second LLM reviews a step's output. The reviewer's verdict is **advisory** (surfaced to the human reviewer in the Decision Bar) with the data model designed so **per-project gating** can be enabled later without rework.
2. **View docker execution logs in a step (live + finished).** Operators can follow a step's container logs while it runs and read them after it finishes.
3. **Delete/hide obsolete executions.** When a source ticket is removed, the operator can retire the related run + artifacts + executions. Implemented as **soft hide/archive** so append-only audit history (FR47) is preserved — never a hard delete here.
4. **Connect to console via SSH terminal.** A **read-only diagnostic console** attached to a *running* runner container, with every session recorded in governed history.
5. **Manual execution of steps.** Because Claude no longer supports an unattended prompt mode without an API key, the system needs a first-class **manual execution mode**: emit the step's context bundle, park the run in a new `WaitingForManualExecution` state, let the operator run the agent themselves, and submit the result back through the *same* validation + review pipeline.
6. **Post-execution provider limit status.** After a step runs, show the agent provider's usage/limit status (e.g. the 5-hour rolling window and weekly limits) where the provider exposes it — so an operator can decide between automated and manual execution.

**Why a deliberate scope-boundary reversal.** The runner sandbox is intentionally `--sandbox read-only`, no-leak, and *every action is appended to governed history* (`architecture.md` security posture). A console into a running runner is, by nature, interactive. This proposal **narrows but does not remove** that posture: the console is **read-only**, attaches **only** to a live runner, and **every session is recorded in governed history**. Write-capable shells, host shells, and ungoverned access remain firmly out of scope — the same shape of deliberate, bounded reversal Epic 3c used for app-level encryption.

**Decisions already taken (this session):**
- **D1 — Manual execution = a first-class `manual` runner kind**, selectable per project/stage (not merely a no-API-key fallback). Run parks in `WaitingForManualExecution`; operator-produced artifact re-enters the existing runner-contracts validation/review pipeline.
- **D2 — Reviewer model = advisory now, gating-capable later.** Verdict is surfaced to the human reviewer; the schema supports turning on per-project gating later.
- **D3 — Console = read-only diagnostic exec into a running runner only**, governed/audited. No write/host shells.
- **D4 — Obsolete-execution removal = soft hide/archive only** (`archived_at`), audit-preserving (FR47). Any true purge stays an Epic 5 retention concern.
- **D5 — Provider limit status is spike-gated** — ships only if the Claude CLI / Anthropic API and Codex expose the 5h/weekly windows programmatically in headless mode.
- **D6 — Numbering = "Epic 3d."** Reuses the established letter-suffix ordinal mechanic (3a/3b/3c) to slot between 3c and 4 without renumbering Epics 4–6 across the ~723 KB `sprint-status.yaml` and hundreds of `4.x`/`5.x` cross-references.

---

## Section 2 — Impact Analysis

### Epic Impact

| Epic | Impact |
|------|--------|
| **Epic 3c (Multi-Project)** | **Prerequisite.** Every Epic 3d item leans on the `Project` aggregate + per-project credentials + `ProjectConnectorResolver`. The reviewer-model binding extends the per-project config + credential model. |
| **Epic 3d (NEW)** | The new epic. ~10 stories (Section 4). |
| **Epic 4 (Recovery)** | **De-dup, not conflict.** Story 4.4's failure-diagnostics view consumes Epic 3d's **live-log viewer** instead of building a separate redacted log download; Epic 4's operator queue (4.2) honors the new **archived/hidden** run state. The `manual` runner kind composes with Epic 4's takeover/recovery actions. |
| **Epic 5 (Export/Retention)** | Soft hide/archive is the operator-UX half; any **true purge** of hidden runs remains an Epic 5 retention concern. Reviewer-model credentials join the per-project credential surface already covered by Epic 5 redaction/export deny-lists. |

### Artifact Conflicts

| Artifact | Impact | Status |
|----------|--------|--------|
| **PRD** | Add **FR64–FR69** under a new *Per-Step Execution Control, Observability & Manual Execution* subsection. | ✅ applied |
| **Architecture** | Append an **Epic 3d scope-amendment** bullet (narrowed sandbox posture) + extend the data-model line + add **ADRs 0024–0027**. | ✅ amendment + data-model applied; ADR files authored in handoff |
| **UX Design** | Add an **Epic 3d additions** component block (Reviewer Verdict Panel, Step Execution Log Viewer, Manual Execution Surface, Read-only Diagnostic Console, Provider Limit Status indicator; queue archived/hidden state + filter; reviewer-model setting in the Project Configuration Surface). | ✅ applied |
| **Epics** | New **Epic 3d** entry in the Epic List between Epic 3c and Epic 4. | ✅ applied |
| **New epic file** | `epic-03d-per-step-execution-control.md` with per-story AC blocks. | ⏭ handoff (`bmad-create-epics-and-stories`) |
| **Sprint status** | Epic 3d story block + `next-active` markers (after Epic 3c, before Epic 4). | ⏭ handoff (`bmad-create-epics-and-stories`) |
| **Foundation gate (story 1.23)** | Widened: `manual` runner kind + `WaitingForManualExecution` state + new event types drift-tested; live-log/console redaction + governed-history assertions. | ⏭ story 3d-9 |

### Technical Impact & Constraints (decisive ones)

1. **New workflow state `WaitingForManualExecution`** — added to the state registry + the `workflow_runs.current_state` CHECK; transitions in from a dispatching state and out on manual-artifact submission. New `WorkflowEventType`s for manual-execution lifecycle (and console-session + reviewer-verdict events) — each needs the registry + fixture mirroring (the "new WorkflowEventType → two fixture sites" rule).
2. **`manual` runner kind** added to the runner-kind registry; the dispatch path branches to *emit the context bundle + park* instead of launching a container. The operator-produced artifact re-enters the **same runner-contracts output validation** — manual is a producer, not a new contract.
3. **Reviewer model rides 3c.** A per-project (optionally per-stage) reviewer-model binding + its own credential, resolved via the `ProjectConnectorResolver` pattern; advisory verdicts persist in a new verdict record (designed gating-capable). No change to the Epic 3b human review loop's authority.
4. **Live-stream redaction is the sharp edge.** The secret-scan/redaction (story 3.6) is post-hoc; a live `docker logs -f` stream can surface secrets before redaction. Mitigated by single-operator localhost-only binding + a documented live-stream redaction posture (ADR 0025). Same constraint governs the read-only console.
5. **Console stays inside the posture.** Read-only attach to a *live* runner only; each session recorded as a governed event; allowed-action gated; no write/host shell. ADR 0025 carries the threat model.
6. **Soft-hide is additive + audit-safe.** `archived_at`/hide markers on runs (and a cascade view over artifacts/executions/links); append-only events untouched (FR47). Trigger = manual operator action and/or auto-detection of source-ticket removal via the ticket-source adapter.
7. **Provider-limit signal is unproven** — gated on a spike (D5); rides the runner output contract metadata if confirmed.

---

## Section 3 — Recommended Approach

**Direct Adjustment with a narrow scope-boundary amendment** — insert **Epic 3d** between Epic 3c and Epic 4, sequenced **after Epic 3c** (it depends on per-project config + credentials).

**Rationale:** these six capabilities form a coherent "per-step execution control" layer that sits naturally above 3c and below Epic 4's recovery console — building it as its own epic keeps Epic 4 focused on failure/recovery while letting 4.4/4.2 *consume* the live-log viewer and archived state rather than re-deriving them. The console reversal is deliberately narrow (read-only, live-only, governed), matching the bounded-reversal pattern Epic 3c set for encryption.

- **Effort:** **High** (new workflow state + dispatch mode + new UI surfaces + a security-sensitive console + a spike).
- **Risk:** **Medium-High**, concentrated in (a) the live-stream/console redaction posture, and (b) the manual-execution state/dispatch changes touching the live run path. Mitigations: ADR 0025 threat model + single-operator localhost binding; the `manual` kind reuses the existing output-validation contract; the provider-limit feature is spike-gated so an unprovable signal can't block the epic.

---

## Section 4 — Detailed Change Proposals

> Story list is an **AC-shape sketch** to size + sequence the epic; each becomes a full context-engineered file via `bmad-create-story`. Sequenced config/domain → execution → observability → lifecycle → cross-cutting, matching the house ordering pattern.

**Prerequisite:** Epic 3c complete (Project aggregate + per-project credentials + connector resolver).

| Story | Title | Shape |
|-------|-------|-------|
| **3d-1** | Reviewer-model project config + verdict schema (Flyway) | Per-project (optionally per-stage) reviewer-model binding + reviewer credential role; advisory-verdict record (designed gating-capable); registry + drift tests; new domain error codes as needed. |
| **3d-2** | Reviewer execution + advisory verdict in Decision Bar | A reviewer runner invocation over a stage's output artifact via `ProjectConnectorResolver`; verdict persisted + surfaced in the WaitingForReview Decision Bar; never overrides the human decision. |
| **3d-3** | `WaitingForManualExecution` state + `manual` runner-kind dispatch | New state (registry + `current_state` CHECK) + new event types; runner-kind registry gains `manual`; dispatch emits the context bundle + parks instead of containerizing. |
| **3d-4** | Manual-artifact submission (UI + CLI) | Operator downloads/copies the bundle, runs the agent, submits the artifact back through the **same** runner-contracts output validation + review pipeline; transitions out of `WaitingForManualExecution`. |
| **3d-5** | Live + historical step log viewing | REST stream (SSE/websocket follow of container logs) + finished-log read; UI Step Execution Log Viewer; documented live-stream redaction posture (single-operator/localhost). |
| **3d-6** | Read-only diagnostic console into a running runner | Read-only exec attach + web terminal; each session recorded as a governed event; allowed-action gated; no write/host shell (ADR 0025). |
| **3d-7** | Post-execution provider usage/limit status — 5h/weekly **[spike-gated]** | Spike confirms the CLI/API exposes the windows in headless mode; if so, runner captures + emits a per-credential snapshot surfaced post-run in UI + CLI. |
| **3d-8** | Soft hide/archive obsolete executions | `archived_at`/hide markers + cascade view; manual hide action (REST + UI) and/or auto-on-ticket-removal via the ticket-source adapter; queue archived/hidden state + "include archived" filter; FR47 preserved. |
| **3d-9** | Foundation-gate widening + test suite extension | Gate asserts the new runner kind + state + event types (drift + fixtures), live-log/console redaction + governed-history, advisory-verdict contract; Vitest/Playwright/axe for the new surfaces; coverage thresholds for new packages. |
| **3d-10** | Epic 3d documentation increment | Walkthrough: configure a reviewer model, run a step manually, view live/finished logs, open the read-only console, read provider limit status, hide an obsolete execution; glossary + concept-vocabulary (NFR43) update. |

**Downstream amendments (handled by widening existing stories, NOT new Epic 3d stories):**
- **Epic 4:** story 4.4 consumes the 3d live-log viewer (no separate redacted-log download); story 4.2 operator queue honors the archived/hidden state.
- **Epic 5:** true purge of hidden runs + reviewer-model credential redaction join the existing retention/export deny-list work.

**ADRs (authored in handoff):** 0024 manual execution mode · 0025 live execution observability & read-only console (threat model) · 0026 per-step advisory reviewer model · 0027 obsolete-execution soft-hide.

**Planning-artifact edits (this proposal):** `prd.md` FR64–FR69 ✅ · `architecture.md` scope amendment + data model ✅ · `ux-design-specification.md` Epic 3d additions ✅ · `epics.md` Epic 3d list entry ✅ · new `epic-03d-...md` + `sprint-status.yaml` block ⏭ handoff.

---

## Section 5 — Implementation Handoff

- **Scope classification: Major** — narrows the sandbox posture + adds a new workflow state/dispatch mode; needs Architect (ADRs) + PO/Dev involvement before story creation.
- **Handoff sequence:**
  1. **Approve this proposal** (Step 5 of Correct Course).
  2. **Architect** — author ADRs 0024–0027 (manual execution, console threat model, reviewer model, soft-hide).
  3. **PO/Dev (`bmad-create-epics-and-stories` then `bmad-create-story 3d-1`…)** — write `epic-03d-per-step-execution-control.md` + per-story files; add the Epic 3d block to `sprint-status.yaml` with `next-active` markers.
  4. **Dev (`bmad-dev-story`)** — implement after Epic 3c, in story order; **3d-7 (provider limits) gated on its spike**; **3d-6 (console) gated on ADR 0025 sign-off**. Pull **3d-3/3d-4 (manual execution) forward** if the automated Claude/Codex headless path is unavailable for the pilot.
- **Success criteria:** a step can be reviewed by a project-configured second LLM (advisory); a step can be run manually and its artifact accepted through the normal pipeline; live + finished container logs are viewable per step; a read-only console attaches to a running runner with the session recorded; obsolete executions can be hidden without losing audit history; provider limit status shows after a run (if the spike confirms the signal); foundation gate + Epic 3d suites green.

---

## Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| **Live-stream / console leaks secrets before post-hoc redaction** | Single-operator localhost-only binding; read-only console; documented live-stream redaction posture + threat model (ADR 0025); governed-history recording of console sessions. |
| **Manual-execution state/dispatch changes touch the live run path** | `manual` is a new runner *kind* reusing the existing output-validation contract; new state is additive (registry + CHECK); covered by foundation-gate drift + fixtures (3d-9). |
| **Provider-limit signal may not exist in headless mode** | Spike-gated (3d-7 / D5); the epic does not block on it — feature ships only if the signal is confirmed. |
| **Reviewer model creeps into auto-gating prematurely** | Advisory-only now; data model designed gating-capable but gating stays off until a deliberate per-project decision (D2). |
| **Hard-delete expectation vs append-only history** | Soft hide/archive only (D4); FR47 preserved; true purge explicitly deferred to Epic 5 retention. |
| **Double-building log/console surfaces with Epic 4** | Epic 4 (4.4/4.2) consumes the 3d live-log viewer + archived state; de-dup enumerated in Section 4. |
| **Renumber churn** | Avoided via the "Epic 3d" non-colliding label (D6). |
