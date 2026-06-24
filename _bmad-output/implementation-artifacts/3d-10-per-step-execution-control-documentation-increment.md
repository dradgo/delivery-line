# Story 3d.10: Per-Step Execution-Control Documentation Increment

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **⚠️ READ FIRST — this is a PURE-DOCUMENTATION story. ZERO production code, ZERO test logic, ZERO CI-workflow-logic changes.** It is the **fourth doc-increment story in an established family**: the twin of the `done` stories **3-36** (`docs/execution-walkthrough.md`), **3c-12** (`docs/project-configuration-walkthrough.md`), and **3c-13** (contributor docs). Your whole job is to write **one new operator walkthrough** + add **a new "Epic 3d vocabulary" glossary section (six terms)** + wire **two index/link surfaces**, all describing **features that already shipped** (stories 3d-2 … 3d-8, all `done`). The `git diff --stat` for this story must show **only files under `docs/` plus `README.md`**. Four things will bite you immediately:
>
> 1. **`docs/index.md` DOES NOT EXIST — and you must NOT create it.** Epic 3d AC7 says "the doc is visible from `docs/index.md`", but `docs/index.md` is an **Epic 6 deliverable** (story 6.1 "full documentation index" — see `docs/glossary.md` L8-11) that has never been built. The **established doc-index surface** every prior walkthrough uses is the **`README.md` "Quick links"** section (L10-25) + the **`docs/glossary.md` "Linked from"** section (L241-252). **Reconciliation: make the walkthrough visible from README "Quick links" (and glossary "Linked from"), NOT a net-new `docs/index.md`.** See R1.
> 2. **The provider-limit feature SHIPPED AS THE "not exposed" STATE for the real providers.** The 3d-7 spike (AC1) found that **neither the Claude CLI / Anthropic API nor Codex expose 5-hour/weekly window status programmatically in headless mode today** — so 3d-7 shipped a documented `"not_exposed"` provider state (never a fabricated number). The live 5-hour/weekly **numbers** are exercised only by the offline **mock** runner. **Reconciliation: describe what an operator actually sees today — a "not exposed by provider" indicator that explains the windows conceptually and how they'd inform the automated-vs-manual choice *when a provider starts exposing them* — do NOT promise live quota numbers from Claude/Codex.** See R2.
> 3. **The CI `docs-link-check` (lychee) HARD-FAILS on any broken internal link or anchor** (`.github/workflows/ci.yml` L147-205: the internal pass runs `--exclude ^https?://` over `docs/*.md docs/**/*.md README.md` with `fail: true`). Every relative link AND every `#anchor` you write must resolve to a real file / real heading slug. External `https://` URLs are WARN-only (non-blocking). See R3.
> 4. **The glossary has NO pre-seeded Epic 3d placeholder** (unlike 3c-12, which replaced an existing reservation note). You must **add a brand-new `## Epic 3d vocabulary (per-step execution control)` section** with six entries, in the same style as the existing `## Epic 3c vocabulary` section. See AC6 + R5.

## Story

As an operator joining the pilot,
I want a per-step execution-control walkthrough — configure a project reviewer model and read its advisory verdict, run a step manually (download the bundle → run the agent → submit the artifact), watch live and finished step logs, open the read-only diagnostic console, read provider limit status, and hide an obsolete execution — plus glossary entries for the new vocabulary (`reviewer model`, `advisory verdict`, `manual execution`, `WaitingForManualExecution`, `diagnostic console`, `archived execution`),
so that I can configure reviewers, run steps manually, observe executions, and retire obsolete runs unaided, in the browser, on any OS, with every concept I meet defined in the canonical glossary.

## Background / Why this story exists

Epic 3d shipped per-step execution control end-to-end: an advisory reviewer model (3d-2), manual execution as a first-class runner kind (3d-3/3d-4), live + historical step-log viewing (3d-5), a read-only diagnostic console (3d-6), post-execution provider usage/limit status (3d-7), and soft hide/archive of obsolete executions (3d-8). The **epic doc-increment rule** (epic-03d L7 + AC8 below) says Epic 3d **cannot close** without an operator-facing walkthrough merged alongside the feature stories. This story discharges that rule.

It is the **fourth documentation-increment story in the same family** and must match the format, scope discipline, and index conventions the prior three set:

- **3-36** wrote `docs/execution-walkthrough.md` (the developer execution-stage guide) — the **primary format template** for this story.
- **3c-12** wrote `docs/project-configuration-walkthrough.md` (the operator project-config guide) — the **closest structural sibling**: same operator audience, same reconciliation traps (no `docs/index.md`, lychee-gated links, capture-not-design discipline), same glossary-section pattern.
- **3c-13** wrote contributor docs + trimmed agent memory — the **scope-discipline + index-convention precedent**.

"Done" means **the walkthrough file exists in the repo**, the six glossary terms are registered in a new Epic 3d section, the README/glossary index surfaces point at it, and the gating `docs-link-check` stays green. No feature behavior is invented or changed — this is a **capture** story: describe what 3d-2 … 3d-8 already do, verified against live source / UI.

## Acceptance Criteria

> These ACs are **reconciled** against the actually-shipped Epic 3d surfaces (stories 3d-2 … 3d-8, all `done`) and the established documentation conventions. Where the epic wording (epic-03d L194-209) assumes an artifact that does not exist (`docs/index.md`) or a capability that shipped degraded (live provider numbers), the reconciled wording below is authoritative; the rationale is in Dev Notes → "Why these ACs are reconciled (R1-R5)".

1. **`docs/per-step-execution-control-walkthrough.md` exists** and follows a single linear sequence an operator can complete in the browser: **configure a project reviewer model** (and read its advisory verdict in the `WaitingForReview` Decision Bar) → **run a step manually** (download the context bundle → run the agent by hand → submit the resulting artifact) → **view live + finished step logs** → **open the read-only diagnostic console** → **read provider limit status** → **hide an obsolete execution** (and un-hide it). The doc names the real UI surfaces, REST/CLI commands, states, allowed-actions, and event types **exactly as they shipped** (see the "shipped surfaces" table in Dev Notes — verify against live source).

2. **The reviewer-model section explains the advisory contract correctly.** It states the reviewer model is a **per-project (optionally per-stage) second LLM** that reviews a step's output, that its verdict is **advisory only** — surfaced in the `WaitingForReview` Decision Bar via the Reviewer Verdict Panel, never auto-approving or auto-rejecting, with **the human decision always governing** — and that a same-model self-review is flagged as a warning. It explains that **a project with no reviewer binding behaves exactly as before** (opt-in per project) and that **a reviewer-run failure degrades gracefully** (the step is never blocked; the panel shows a "review unavailable" reason). It must NOT imply the verdict gates progression (the `reviewer_gating_enabled` flag exists but is **not** consulted in this epic — do not document it as an operator control).

3. **The manual-execution section explains *why* it exists and that it is governed.** It states that manual mode exists because **an agent's unattended/headless auth may be unavailable**, and that a manually-produced artifact **re-enters the same runner-contracts validation and the same review pipeline** as an automated runner's output (no validation bypass). It walks the operator linearly: a run parked in `WaitingForManualExecution` → obtain the (redacted) context bundle → run the agent by hand → submit the artifact (file or paste) → the run transitions into the normal post-step state (`WaitingForReview`) with a governed event appended. It names both the UI surface and the REST + CLI paths, and notes that an invalid artifact is rejected with the run left parked and resubmittable.

4. **The observability section (logs + console) states the safety posture in operator-readable terms.** For **step logs**: the viewer follows a running step's container logs live and serves the already-persisted post-hoc-redacted log after it finishes; it is localhost-only; the **authoritative redaction guarantee is the persisted post-hoc scan**, live streaming is best-effort. For the **console-safety section**: the diagnostic console is **read-only, live-only (a live runner only), governed-history-recorded, and localhost-only**, that it **cannot mutate the run or the workspace** (no input that changes state; no host shell), that **only session metadata is recorded — console I/O is not durably stored**, and that **nothing it shows changes persisted/exported content** (ADR 0025 posture). It links ADR 0025 rather than re-deriving the threat model.

5. **The provider-limit section is honest about the shipped state (R2).** It explains the 5-hour and weekly windows **conceptually** and how they would inform the automated-vs-manual choice, states that the status is **provider-reported and as-of a timestamp**, and **describes the shipped reality**: the Claude / Codex CLIs **do not currently expose these windows headlessly**, so the indicator shows a **"not exposed by provider"** state today rather than a fabricated number — and would surface real numbers automatically if/when a provider begins exposing them. It must NOT present live 5h/weekly quota numbers as something a real provider returns today.

6. **The soft-hide section states the audit guarantees.** It explains that hiding (archiving) an obsolete execution **never erases audit history** (`workflow_events` is append-only and untouched — FR47), is **reversible** (un-hide), that hidden runs **leave the default queue but remain audit-queryable** (an "include archived" filter / path), and that **true purge / retention is a separate Epic 5 concern**, not available here. It describes the manual hide/un-hide REST + CLI path as the shipped trigger and notes auto-archive-on-ticket-removal as an **optional, default-off** capability (R4).

7. **Glossary entries are added in a new Epic 3d section (glossary discipline + NFR43).** `docs/glossary.md` gains a new **`## Epic 3d vocabulary (per-step execution control)`** section (mirroring the existing `## Epic 3c vocabulary` section style + the "Epic 6 6.2 normalizes wording" note) with canonical entries for **`reviewer model`**, **`advisory verdict`**, **`manual execution`**, **`WaitingForManualExecution`**, **`diagnostic console`**, and **`archived execution`**. Each entry follows the existing glossary style (definition + bold **See also:** cross-links to the new walkthrough + the relevant ADR + related entries). No concept used in the walkthrough is left without a glossary entry.

8. **Index/link surfaces are wired and all internal links resolve (R1 + R3).** The walkthrough is **visible from `README.md` "Quick links"** (a new operator-keyed entry, mirroring the existing operator lines) **and** registered in the `docs/glossary.md` "Linked from" list — this is the project's real doc-index convention (`docs/index.md` is an Epic-6 deliverable that does not exist; do **not** create it). Every internal relative link and `#anchor` in the new doc + edited files resolves; the CI `docs-link-check` (lychee internal pass, `fail: true`) stays green.

9. **Browser-based, OS-neutral, and validator-placeholder present (AC7/AC8 of the epic).** The walkthrough is entirely browser-based with **no OS-specific instructions** (any CLI command shown is OS-neutral, consistent with `execution-walkthrough.md`). A **named human-validator placeholder** is included at the very top: a blockquote `> **Per-step execution-control walkthrough validator:** \`_____________________________\` (to be named before Epic 3d close)` — mirror the existing pattern exactly (cf. `execution-walkthrough.md` L3, `project-configuration-walkthrough.md`).

10. **Scope discipline: docs-only.** `git diff --stat` for this story shows **only** files under `docs/` plus `README.md` — no backend, frontend, test, Flyway, OpenAPI, error-code, or CI-workflow change. `format-static-checks` is **N/A** (docs are outside the backend Spotless + frontend Prettier scope, consistent with 3-36 / 3c-12 / 3c-13). No new glossary term is introduced anywhere else in the same change without its entry (AC7).

## Tasks / Subtasks

- [x] **Task 0 — Verify the shipped surfaces before writing (AC: 1-6)**
  - [x] This is a **capture** story: confirm each behavior you describe still exists. Re-read the authoritative `done` story files and ADRs (see the "shipped surfaces to describe" table + References below): 3d-2 (Reviewer Verdict Panel + `GET …/reviewer-verdict` + advisory-only contract + self-review flag + graceful-degrade), 3d-3/3d-4 (`WaitingForManualExecution`, `manual` runner kind, `GET …/manual-bundle`, `POST …/manual-artifact`, CLI parity, the Manual Execution Surface, `manual.executionRequested` / `manual.artifactSubmitted` events), 3d-5 (`GET …/runner-logs/stream` SSE, Step Execution Log Viewer, `view_runner_logs`), 3d-6 (`GET …/diagnostic-console/stream`, Read-only Diagnostic Console, `open_diagnostic_console`, `console.opened` / `console.closed`, ADR 0025 sign-off), 3d-7 (`GET …/provider-usage`, CLI, Provider Limit Status indicator, `view_provider_usage_status`, the `not_exposed` spike outcome), 3d-8 (`POST …/archive` + `…/unarchive`, CLI, `archive_run` / `unarchive_run`, `workflow.archived` / `workflow.unarchived`, `includeArchived` queue filter, `archived_at` marker). Also read ADRs 0024 / 0025 / 0026 / 0027.
  - [x] **Confirm the exact strings against live source — do NOT trust this story's table blindly.** Surface names below were captured from the story files; verify REST paths, CLI command spellings, allowed-action registry values, event-type strings, and frontend component/surface labels against the actual code/OpenAPI/registries before committing them to operator-facing prose. Where a captured name and the live code differ, **write what the code says** and note the correction in the Dev Agent Record.
  - [x] Where the epic AC text and the shipped reality differ, **write the shipped reality** and capture the deferral in the doc's "What is NOT in this walkthrough" section (live provider numbers = not exposed today; queue project-scoping = a separate concern; reviewer gating = not in this epic; true purge = Epic 5; auto-archive = optional/default-off). Do **not** "fix" any code you read.
- [x] **Task 1 — Write `docs/per-step-execution-control-walkthrough.md` (AC: 1, 2, 3, 4, 5, 6, 9)**
  - [x] Open with the title + the **validator-placeholder blockquote** (AC9) + a one-paragraph framing that pairs it with `execution-walkthrough.md` (the execution stage it extends) and states it is browser-based + OS-neutral, mirroring `execution-walkthrough.md` L1-20 and `project-configuration-walkthrough.md`.
  - [x] Add a **"The one thing to remember"** callout in the house style — recommend: *"every one of these capabilities is advisory or read-only or reversible — the reviewer verdict never decides for you, the console can't change anything, and hiding a run never erases its history."*
  - [x] Linear step sequence (AC1) with the six sections in order: **reviewer model + advisory verdict** (AC2) → **manual execution** (AC3) → **live + finished step logs** (AC4, logs half) → **read-only diagnostic console** (AC4, console half) → **provider limit status** (AC5) → **hide / un-hide an obsolete execution** (AC6). Use ASCII panel sketches in the `execution-walkthrough.md` house style where they clarify a surface; keep them illustrative, not pixel-exact.
  - [x] Close with a **"Concepts you just used"** footer linking `glossary.md` (the six new Epic 3d entries) and a **"What is NOT in this walkthrough"** section recording the deferrals (live provider numbers not exposed today; reviewer gating not in this epic; auto-archive optional/default-off; true purge = Epic 5; multi-user/RBAC + remote access out of scope) — mirror `execution-walkthrough.md` L519-547 / `project-configuration-walkthrough.md`.
- [x] **Task 2 — Add the Epic 3d glossary section (AC: 7)**
  - [x] Add a new `## Epic 3d vocabulary (per-step execution control)` section to `docs/glossary.md` after the existing `## Epic 3c vocabulary` section (and before the `## Linked from` section), with the same "registered here per glossary discipline; Epic 6 story 6.2 normalizes wording" lead-in sentence the other epic sections use.
  - [x] Six entries (definition + bold **See also:** cross-links), suggested canonical wording:
    - **`reviewer model`** = a per-project (optionally per-stage) second LLM, resolved through the project's connector/credential model, that reviews a step's output and produces an [advisory verdict](#advisory-verdict); strictly opt-in per project, never gates progression in this epic.
    - **`advisory verdict`** = the reviewer model's structured outcome (`pass` / `concern` / `fail`) + rationale + reviewer/producer model identities, surfaced in the `WaitingForReview` Decision Bar's Reviewer Verdict Panel; the human approve/reject decision always governs.
    - **`manual execution`** = a first-class `manual` runner kind that, instead of launching a container, emits the step's context bundle and parks the run in [WaitingForManualExecution](#waitingformanualexecution) so an operator can run the agent by hand and submit the artifact back into the same validation/review pipeline.
    - **`WaitingForManualExecution`** = the workflow state a run sits in while awaiting a manually-produced artifact; entered on `manual`-kind dispatch, exited on a valid manual-artifact submission into the normal post-step state.
    - **`diagnostic console`** = a read-only, live-only, governed-history-recorded, localhost-only console attached to a running runner container for in-the-moment diagnosis; cannot mutate the run or workspace, stores only session metadata, and changes nothing persisted/exported.
    - **`archived execution`** = a run soft-hidden from default operator views via an `archived_at` marker; reversible (un-hide), never deletes rows or `workflow_events` (FR47), remains audit-queryable, and is distinct from true purge (an Epic 5 retention concern).
- [x] **Task 3 — Wire the index/link surfaces (AC: 8)**
  - [x] Add a `README.md` "Quick links" entry pointing operators at the walkthrough (e.g. `**Reviewing, running manually, or observing a step?** → docs/per-step-execution-control-walkthrough.md`), placed near the existing operator-facing entries (after the project-configuration line).
  - [x] Add the walkthrough to the `docs/glossary.md` "Linked from" list (it links to the glossary in its "Concepts you just used" footer, so the back-reference is accurate — mirror the `project-configuration-walkthrough.md` line at glossary L248).
  - [x] **Do NOT create `docs/index.md`** (R1).
- [x] **Task 4 — Verify links + diff scope (AC: 8, 9, 10)**
  - [x] Hand-verify every relative link and every `#anchor` in the new doc + the README/glossary edits resolves (broken internal links HARD-FAIL the gating lychee pass). For each `#anchor`, open the target doc and confirm the heading exists and the slug matches (GitHub-style: lowercased, spaces→`-`, punctuation dropped, em-dash `—` dropped → double hyphen). Self-anchors in the new walkthrough's "Concepts you just used" / cross-section links must match its own headings.
  - [x] If `lychee` is available locally, run the internal pass (`lychee --exclude '^https?://' docs/*.md docs/**/*.md README.md`); otherwise audit by hand. Confirm `git diff --stat` shows only `docs/` + `README.md`.
- [x] **Logging instrumentation** — **N/A for this story** (pure documentation; no services, no frontend code touched). Recorded explicitly per the project-wide logging task, consistent with 3-36 / 3c-12 / 3c-13.

## Dev Notes

### Scope discipline (read this twice)
- **Docs-only.** Mirror 3-36 / 3c-12 / 3c-13 exactly. Do **not** "improve" any code, test, or CI file you read while sourcing the doc. If you find drift between a memory/epic claim and live source, **correct the doc text** to match reality and (only if it's a genuine bug) note it as a follow-up — do not fix it here.
- **Capture, not design.** The walkthrough describes **existing** Epic-3d behavior. Invent no new conventions, toggles, endpoints, or flows. Every surface / status / endpoint / event / allowed-action name must match the shipped UI + REST contract + registries.
- The story's `git diff` is **repo-only** and must be `docs/` + `README.md`. No `format-static-checks` impact (docs are outside Spotless/Prettier scope).

### Why these ACs are reconciled (R1-R5) — epic wording vs. the live codebase
The dev agent only has this file. These are the traps where the epic 3d-10 ACs (epic-03d L194-209) collide with the real repo state; follow the reconciled ACs above.

| # | Epic assumption | Reality | Reconciliation |
|---|---|---|---|
| **R1** | "the doc is visible from `docs/index.md`" (epic AC7) | **`docs/index.md` does not exist.** It is an Epic 6 deliverable (story 6.1 "full documentation index"; see `docs/glossary.md` L8-11). The real doc-index surface every walkthrough uses is **`README.md` "Quick links"** (L10-25) + **`docs/glossary.md` "Linked from"** (L241-252). 3-36, 3c-12, and 3c-13 all indexed via README, **not** an `index.md`. | **Index via README "Quick links" + glossary "Linked from". Do NOT create `docs/index.md`.** Creating a stub would be out-of-convention and risks lychee anchor breakage. |
| **R2** | "the 5-hour/weekly status … informs the automated-vs-manual choice" (epic AC5) implies live provider numbers | The 3d-7 **spike (AC1) found neither Claude nor Codex exposes 5h/weekly windows headlessly today**, so the feature shipped the documented **`not_exposed`** state; only the offline **mock** runner emits live `available` numbers. The `provider_usage_snapshots` table + `GET …/provider-usage` + the Provider Limit Status indicator all exist and work — they just render "not exposed by provider" against the real CLIs. | **Describe the shipped reality:** explain the windows conceptually + the automated-vs-manual value, but state the indicator shows **"not exposed by provider" today** and would light up automatically if a provider starts exposing the signal. Do **not** present live quota numbers as real-provider output. Record under "What is NOT in this walkthrough". |
| **R3** | "the link-check CI step; all internal links resolve" (epic AC7) | The CI job is **`docs-link-check`** using **lychee** (`.github/workflows/ci.yml` L147-205). The **internal pass** (`--exclude ^https?://`, `fail: true`) hard-fails on any broken file/anchor over `docs/*.md docs/**/*.md README.md`. The **external pass** is WARN-only; `.lycheeignore` silences known-flaky external URLs. | **Every relative link + `#anchor` must resolve** (open the target, confirm the heading slug). Example `https://` URLs are safe (WARN-only). This is the one gate this story can actually break. |
| **R4** | Reviewer verdict / console / soft-hide capabilities are operator controls | Per ADRs 0026 / 0025 / 0027 and the shipped stories: the verdict is **advisory only** (`reviewer_gating_enabled` exists but is **never consulted** this epic); the console is **read-only / live-only / governed / localhost-only / I/O-not-stored**; soft-hide is **reversible** and **never touches `workflow_events`** (FR47), with **true purge deferred to Epic 5** and **auto-archive-on-ticket-removal optional + default-off**. | **Document each as advisory / read-only / reversible — never as a gate, a write surface, or a delete.** Do not document `reviewer_gating_enabled` as an operator toggle; do not imply the console can act; do not imply hiding deletes. |
| **R5** | Glossary "new concepts … are added to `docs/glossary.md`" (epic AC6) | The glossary has **no pre-seeded Epic 3d placeholder** (unlike 3c-12, which replaced an existing reservation note at the old L172-174). The existing sections are `## Epic 3 vocabulary`, `## Epic 3c vocabulary`. | **Add a brand-new `## Epic 3d vocabulary (per-step execution control)` section** in the same style, with the six AC6/AC7 terms. Keep the "Epic 6 6.2 normalizes wording" lead-in for consistency. |

### The shipped surfaces to describe (captured from the `done` story files — VERIFY against live source before writing)
> ⚠️ These strings were captured from the 3d-2 … 3d-8 story files. Treat them as a **starting map, not gospel** — confirm each REST path / CLI spelling / registry value / component name against the actual code, OpenAPI snapshot, and registries (Task 0). Write what the code says.

- **Reviewer model + advisory verdict (3d-2 / ADR 0026):** `GET /api/v1/workflows/{workflowRunId}/reviewer-verdict` returns a verdict with a state (`pending` / `available` / `unavailable`); the **Reviewer Verdict Panel** (`ReviewerVerdictPanel.tsx`) renders it beside the `WaitingForReview` Decision Bar. Outcomes are `pass` / `concern` / `fail` + rationale + reviewer/producer model identities; **same-model self-review surfaces a warning**; **no-binding project = byte-identical to pre-3d**; **reviewer-run failure ⇒ "review unavailable" reason, step not blocked**. The panel adds **no governed action** (presentational); human approve/reject is unchanged.
- **Manual execution (3d-3 / 3d-4 / ADR 0024):** runner kind `manual`; state `WaitingForManualExecution`; runner-execution status `AWAITING_MANUAL`. `GET /api/v1/workflows/{workflowRunId}/manual-bundle` (redacted bundle) + `POST /api/v1/workflows/{workflowRunId}/manual-artifact` (same runner-contracts output validation). CLI: a `manual-bundle get <runId>` + `manual-artifact submit <runId> --file <path>` pair (verify exact spelling). Allowed-actions `obtain_manual_bundle` + `submit_manual_artifact`. Events `manual.executionRequested` (dispatch) + `manual.artifactSubmitted` (submission). UI: **Manual Execution Surface** (`ManualExecutionSurface.tsx`). Errors include `MANUAL_EXECUTION_NOT_APPLICABLE` (wrong state) + idempotency conflict; invalid artifact ⇒ run stays parked + resubmittable.
- **Live + finished step logs (3d-5 / ADR 0025):** `GET /api/v1/workflows/{workflowRunId}/runner-logs/stream` (SSE; follows a live container, serves the persisted post-hoc-redacted log when finished). UI: **Step Execution Log Viewer** (`StepExecutionLogViewer.tsx`) with live-follow + finished/static mode + a live-region announcement. Allowed-action `view_runner_logs`. **Localhost-only**; authoritative redaction = the persisted post-hoc scan (story 3.6); live streaming is best-effort. No new raw-log store.
- **Read-only diagnostic console (3d-6 / ADR 0025 — security-gated, signed off):** `GET /api/v1/workflows/{workflowRunId}/diagnostic-console/stream` (SSE, **input-disabled** — receive-only attach to a live container only). UI: **Read-only Diagnostic Console** (`ReadOnlyDiagnosticConsole.tsx`), clearly badged read-only. Allowed-action `open_diagnostic_console` (live `EXECUTING` only). Events `console.opened` / `console.closed` (metadata only — I/O **not** stored). **Read-only / live-only / governed / localhost-only.** Nothing it shows changes persisted/exported content.
- **Provider usage/limit status (3d-7 — spike said NOT exposed; ADR/proposal D5):** `GET /api/v1/workflows/{workflowRunId}/provider-usage` + a CLI (`workflow provider-usage <runId>`, verify). UI: **Provider Limit Status indicator** (`ProviderLimitStatus.tsx`). Runner emits optional `normalizedOutput.providerUsage` (`signalState` ∈ `available` / `not_exposed`, `accountLabel`, `fiveHour` / `weekly` windows, `asOf`) — **additive, no schema-version bump**. Backend persists per-credential snapshots (`provider_usage_snapshots`, `pul_` prefix, **no secret column**). Allowed-action `view_provider_usage_status`. **Real Claude/Codex ⇒ `not_exposed` today; the mock exercises `available`** (R2).
- **Soft hide/archive (3d-8 / ADR 0027):** `POST /api/v1/workflows/{workflowRunId}/archive` + `…/unarchive` + CLI (`archive <runId> --reason …` / `unarchive <runId>`, verify). Allowed-actions `archive_run` / `unarchive_run`. Events `workflow.archived` / `workflow.unarchived` (state unchanged; `interventionMarker=true`). Marker = `workflow_runs.archived_at` (already existed; 3d-8 adds a partial index, no new column). Queue: `GET /api/v1/workflows?includeArchived=true` (default hides archived); `WorkflowSummaryResponse.archivedAt` field. Error `ARCHIVE_NOT_APPLICABLE`. **`workflow_events` never deleted/mutated (FR47); reversible; archived runs stay audit-queryable; auto-archive-on-ticket-removal is optional + default-off; true purge = Epic 5.**

### Format template + conventions (do NOT reinvent)
- **Primary template:** `docs/execution-walkthrough.md` — match its tone, structure, ASCII-panel style, "The one thing to remember" / "Before you start" / numbered steps / "Concepts you just used" / "What is NOT in this walkthrough" sections, and the validator-placeholder blockquote at the very top.
- **Closest sibling:** `docs/project-configuration-walkthrough.md` (3c-12) — same operator audience + same reconciliation discipline; copy its section rhythm and its "What is NOT in this walkthrough" framing for deferrals.
- **Validator placeholder (AC9):** exact pattern from `execution-walkthrough.md` L3 / `project-configuration-walkthrough.md` — `> **<Role> walkthrough validator:** \`_____________________________\` (to be named before Epic <X> close)`. For this story: **Per-step execution-control walkthrough validator**, "before Epic 3d close".
- **Index convention:** `README.md` "Quick links" (L10-25) is the operator-facing index; `docs/glossary.md` "Linked from" (L241-252) lists docs that link *to* the glossary. `docs/index.md` is NOT used (R1).
- **Glossary discipline:** `docs/glossary.md` L3-5 — any doc introducing a new term must add its entry **in the same PR**; tracked against NFR43. Add the new Epic 3d section in the established style (R5).

### Project Structure Notes
- **New:** `docs/per-step-execution-control-walkthrough.md` (top-level `docs/`, alongside the other `*-walkthrough.md` files — `execution-walkthrough.md`, `project-configuration-walkthrough.md`, `pm-loop-walkthrough.md`, `failure-recovery-walkthrough.md`).
- **Modified:** `docs/glossary.md` (+1 new `## Epic 3d vocabulary` section with 6 entries; +1 "Linked from" line); `README.md` (+1 "Quick links" entry).
- **NOT touched:** any backend/frontend module; `openapi.json`; any test; any Flyway migration; any CI workflow; and **no `docs/index.md`** (R1).

### Logging Requirements (project-wide standard)
**N/A for this story** — pure documentation, no services or frontend code touched. Recorded explicitly per the project-wide logging task, consistent with 3-36 / 3c-12 / 3c-13.

### References
- [Source: _bmad-output/planning-artifacts/epic-03d-per-step-execution-control.md#Story-3d-10] — authoritative ACs (L194-209); the epic doc-increment rule (L7) + "browser-based, no OS-specific instructions" + "named human validator placeholder" requirements; the ADR list (L11).
- [Source: _bmad-output/implementation-artifacts/3d-2-reviewer-execution-and-advisory-verdict-in-decision-bar.md] — the Reviewer Verdict Panel + `GET …/reviewer-verdict` + advisory-only contract + self-review flag + no-binding parity + graceful-degrade (AC2).
- [Source: _bmad-output/implementation-artifacts/3d-3-waiting-for-manual-execution-state-and-manual-runner-kind-dispatch.md] + [3d-4-manual-artifact-submission-ui-and-cli.md] — `WaitingForManualExecution`, the `manual` kind, bundle GET + artifact POST + CLI, Manual Execution Surface, the manual.* events, `MANUAL_EXECUTION_NOT_APPLICABLE` (AC3).
- [Source: _bmad-output/implementation-artifacts/3d-5-live-and-historical-step-log-viewing.md] — the SSE log stream, Step Execution Log Viewer, `view_runner_logs`, localhost-only + persisted-redaction-authoritative posture (AC4 logs).
- [Source: _bmad-output/implementation-artifacts/3d-6-read-only-diagnostic-console-into-running-runner.md] — the read-only/live-only/governed/localhost console, `open_diagnostic_console`, `console.opened`/`console.closed`, the ADR 0025 sign-off gate (AC4 console).
- [Source: _bmad-output/implementation-artifacts/3d-7-post-execution-provider-usage-limit-status.md] — the spike outcome (`not_exposed` for real providers), `GET …/provider-usage` + CLI + Provider Limit Status indicator, `provider_usage_snapshots` (no-secret), `view_provider_usage_status` (R2 / AC5).
- [Source: _bmad-output/implementation-artifacts/3d-8-soft-hide-archive-obsolete-executions.md] — archive/unarchive REST + CLI, `archive_run`/`unarchive_run`, `workflow.archived`/`workflow.unarchived`, `includeArchived` filter, `archived_at` marker, FR47 invariant + Epic-5 purge boundary (AC6).
- [Source: docs/adr/0024-manual-execution-mode.md] / [0025-live-observability-and-readonly-console.md] / [0026-per-step-advisory-reviewer-model.md] / [0027-obsolete-execution-soft-hide.md] — the canonical posture detail to link from AC2/AC4/AC6 rather than re-deriving.
- [Source: docs/execution-walkthrough.md] — the **format/structure template** (3-36) + the validator-placeholder pattern (L3) + "Concepts you just used" / "What is NOT in this walkthrough" footers (L519-547).
- [Source: docs/project-configuration-walkthrough.md] + [_bmad-output/implementation-artifacts/3c-12-project-configuration-documentation-increment.md] — the **closest structural sibling** + its proven reconciliations (no `docs/index.md`; lychee-gated links; capture-not-design; the glossary-section + README/glossary index pattern).
- [Source: docs/glossary.md] — the file to extend (AC7): glossary discipline (L3-5), the Epic-6-owned index note (L8-11), the existing `## Epic 3 vocabulary` / `## Epic 3c vocabulary` section style, and the "Linked from" list (L241-252) to append.
- [Source: README.md#Quick-links] — the operator-facing doc index to extend (L10-25); the convention 3-36 / 3c-12 / 3c-13 followed.
- [Source: .github/workflows/ci.yml#docs-link-check] — the gating lychee job (L147-205): internal pass `fail: true` over `docs/*.md docs/**/*.md README.md`; external pass WARN-only; `.lycheeignore` for flaky external URLs (R3).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context) — bmad-dev-story workflow.

### Debug Log References

- **Surface verification (Task 0).** Dispatched a read-only Explore sweep over backend
  (`org.dradgo.*`), frontend, CLI (`WorkflowCommands.java`), runner-contracts, `openapi.json`,
  and registries to confirm every REST path / CLI command / allowed-action / event-type /
  workflow-state / error-code / component name before writing. All captured strings matched live
  source **except** one correction (see Completion Notes). Reviewer-verdict state/outcome enum
  confirmed directly in `ReviewerVerdictResponse.java` (`pending`/`available`/`unavailable`;
  `pass`/`concern`/`fail`; `no_reviewer_configured` no-binding reason).
- **Link audit (Task 4).** `lychee` is not installed locally (`which lychee` → not found), so the
  CI `docs-link-check` internal pass was reproduced by hand: extracted every relative file link
  and every `#anchor` from the new doc + edited files, generated GitHub-style heading slugs for
  `glossary.md` and the walkthrough, and cross-checked each reference. All file targets exist; all
  anchors resolve; **zero external `https://` links** introduced (no external-pass risk).
- **Scope (Task 4 / AC10).** `git status --porcelain` confirms the production diff is exactly
  `README.md` (M), `docs/glossary.md` (M), `docs/per-step-execution-control-walkthrough.md` (new)
  — docs/ + README.md only; no backend/frontend/test/Flyway/OpenAPI/error-code/CI-workflow change.

### Completion Notes List

Pure-documentation story (4th doc-increment in the 3-36 / 3c-12 / 3c-13 family). Captured the
already-shipped Epic 3d surfaces (stories 3d-2 … 3d-8, all `done`) — invented no behavior.

- **AC1-AC6, AC9** — Wrote `docs/per-step-execution-control-walkthrough.md`: validator-placeholder
  blockquote + "one thing to remember" + a single linear six-section sequence (reviewer model +
  advisory verdict → manual execution → live/finished logs → read-only console → provider limits →
  hide/un-hide) + "Concepts you just used" + "What is NOT in this walkthrough" + a References
  footer. Browser-based, OS-neutral (CLI commands shown OS-neutral, mirroring
  `execution-walkthrough.md`).
- **AC2** — Reviewer section states the advisory-only contract: per-project second LLM, verdict
  surfaced in the `WaitingForReview` Reviewer Verdict Panel, never auto-approves/rejects, human
  decision governs; no-binding = byte-identical/renders-nothing; reviewer failure degrades to
  "review unavailable" (step never blocked); self-review flagged as a warning. Explicitly records
  that `reviewer_gating_enabled` is **not** an operator control this epic (R4).
- **AC3** — Manual section explains *why* (headless/unattended auth may be unavailable) and that a
  manual artifact re-enters the **same** validation + review pipeline (no bypass); names UI
  surface + REST (`GET /manual-bundle`, `POST /manual-artifact`) + CLI; invalid artifact ⇒ stays
  parked + resubmittable (`MANUAL_EXECUTION_NOT_APPLICABLE`).
- **AC4** — Logs: live-follow + finished-replay over one SSE endpoint; localhost-only; persisted
  post-hoc scan is the **authoritative** redaction guarantee, live is best-effort. Console:
  read-only / live-only / governed-history / localhost-only, cannot mutate run or workspace, only
  session metadata recorded (I/O not stored), changes nothing persisted/exported; links ADR 0025.
- **AC5 (R2)** — Provider-limit section is honest: explains 5h/weekly windows conceptually + the
  automated-vs-manual value, states provider-reported + as-of, and describes the shipped reality —
  real Claude/Codex show **"not exposed by provider"** today (3d-7 spike), never a fabricated
  number; would surface real numbers automatically if a provider begins exposing the signal.
- **AC6 (R4)** — Soft-hide section states the audit guarantees: archiving never erases
  `workflow_events` (FR47), reversible un-hide, hidden runs leave default queue but stay
  audit-queryable (`includeArchived`), true purge = Epic 5, auto-archive-on-ticket-removal =
  optional/default-off; names REST + CLI; `ARCHIVE_NOT_APPLICABLE`.
- **AC7 (R5)** — Added a brand-new `## Epic 3d vocabulary (per-step execution control)` section to
  `docs/glossary.md` (no pre-seeded placeholder existed) with the six terms (`reviewer model`,
  `advisory verdict`, `manual execution`, `WaitingForManualExecution`, `diagnostic console`,
  `archived execution`), each with definition + bold **See also:** cross-links, mirroring the
  existing Epic 3c style + the "Epic 6 6.2 normalizes wording" lead-in.
- **AC8 (R1 + R3)** — Indexed via `README.md` "Quick links" (new operator-keyed entry) +
  `docs/glossary.md` "Linked from" list. **Did NOT create `docs/index.md`** (Epic-6 deliverable,
  R1). All internal links + anchors hand-verified to resolve (R3).
- **AC10** — Scope is docs/ + README.md only; `format-static-checks` N/A (docs outside
  Spotless/Prettier).

**Correction captured during Task 0 (capture-not-design):** the story's shipped-surface map
listed the provider-usage attribution field as `accountLabel`; live source
(`ProviderUsageStatusResponse.java`) names it **`accountReference`**. The walkthrough uses the
live name. All other captured strings (REST paths, CLI commands, allowed-actions, event types,
`WaitingForManualExecution` / `awaiting_manual`, error codes, component filenames, signal states
`available`/`not_exposed`, verdict states/outcomes) matched live source exactly.

**No code drift found requiring a follow-up bug** — the only mismatch was the doc-map field-name
slip above, corrected in the doc text per scope discipline (no code touched).

### File List

- `docs/per-step-execution-control-walkthrough.md` (new) — the operator walkthrough.
- `docs/glossary.md` (modified) — new `## Epic 3d vocabulary` section (6 entries) + 1 "Linked
  from" line.
- `README.md` (modified) — 1 new "Quick links" entry.

## Change Log

| Date | Change |
|---|---|
| 2026-06-24 | Story implemented (docs-only): wrote `docs/per-step-execution-control-walkthrough.md`, added the Epic 3d glossary section (6 terms), wired README "Quick links" + glossary "Linked from". Status → review. |

## Review Findings

> Code review 2026-06-24 (bmad-code-review, 3-layer adversarial). Edge Case Hunter + Acceptance Auditor verified every link/anchor/REST-path/CLI/allowed-action/event-type/state/error-code/component name against live source — all clean; all 10 ACs PASS; no scope violation; `accountLabel` not reintroduced. Below are the only items raised (all Low-severity polish in the new walkthrough; the gating lychee links + all shipped strings are correct).

- [x] [Review][Patch] Workflow-"running" state token cased two ways — FIXED: harmonized L259 `` `EXECUTING` `` → `` `Executing` `` (the `WorkflowState.EXECUTING` enum's wire value is `"Executing"`, matching L48 and every other PascalCase state name in the doc) [docs/per-step-execution-control-walkthrough.md:259].
- [x] [Review][Patch] ASCII sketch truncated a canonical state name — FIXED: Step 2 diagram now shows full `WaitingForManualExecution`; reclaimed 5 padding spaces so the box right-border stays aligned [docs/per-step-execution-control-walkthrough.md:179].
- [x] [Review][Patch] "post-hoc redacted" applied to the manual *input* bundle — FIXED: changed to "with secrets redacted" so it no longer collides with the doc's "post-hoc redaction" term for the persisted-output scan [docs/per-step-execution-control-walkthrough.md:192].

> Dismissed as noise (by-design / AC-mandated, recorded for traceability): blank validator placeholder (AC9 — filled before Epic 3d close, matches all sibling walkthroughs); "Concepts you just used" footer heading (AC1 mandates mirroring `execution-walkthrough.md` house style); provider "not exposed today" phrasing (AC5/R2 explicitly requires this claim; `currently`/`today` already signals volatility); README "Quick links" tagline terseness (AC8 — mirrors the existing terse operator lines).
