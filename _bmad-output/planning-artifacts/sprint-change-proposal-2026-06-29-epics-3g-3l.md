# Sprint Change Proposal — Epic Family 3g–3l (Pre-Epic-4 Scope Expansion)

**Date:** 2026-06-29
**Author:** Alex (via Correct Course workflow)
**Status:** IN PROGRESS — incremental discussion. Theme A (Epic 3g) finalized; 3h–3l in discussion.

---

## Section 1 — Issue Summary

Sixteen new capability requests were raised for the governed delivery-workflow product
(DeliveryLine), to be scheduled **between Epic 3f (Complex Ticket Flow) and Epic 4 (Recovery)**.
The raw list spans six distinct concerns — too much for one cohesive epic, and one item
(project memory) was explicitly flagged by the requester as an epic in its own right.

**Decision (structure):** organize the work as a **family of six themed, individually-shippable
epics `3g`–`3l`**, inserted before Epic 4 — mirroring the established `3c`–`3f` sub-letter pattern
so Epics 4–6 are not renumbered. New scope introduces new FRs starting at **FR73**.

## Section 2 — Family Map (16 items → 6 epics)

| Epic | Theme | Raw items | Status |
|------|-------|-----------|--------|
| **3g** | Run Provenance & Token Accounting | 1 (ticket title), 2 (token usage) | **FINALIZED** |
| **3h** | Pre-Review Quality Gates & Delivery-Tail Governance | 3 (build validation), 6 (CI build-error investigation), 9 (BMAD code review), 10 (push settings), 13 (Java/TS linters), 16 (PR/MR creation flag) | discussion pending |
| **3i** | Connector Expansion | 4 (JIRA), 5 (Bitbucket), 12 (Sentry) | discussion pending |
| **3j** | Ticket-Type Workflows | 7 (bug vs feature split workflow) | discussion pending |
| **3k** | Runner Platform / VM Execution | 8 (Kimi spike), 14 (runner-as-service in VM), 15 (codex CLI in VM) | discussion pending |
| **3l** | Project Memory & Artifact Lineage (the requester-flagged EPIC) | 11 (ticket relations + artifacts memory) | discussion pending |

> Sequencing note: 3g is the warm-up (small, additive read-model — pins DTO/persistence conventions
> the heavier epics reuse). 3h is the richest cluster. 3k is the deepest architectural shift
> (warrants an ADR up front). 3l is a standalone epic per the requester.

---

## Epic 3g — Run Provenance & Token Accounting  *(FINALIZED)*

**Net-new scope:** **FR73** (a governed run surfaces its originating ticket's human-readable title
and a link back to the source ticket) + **FR74** (per-step agent token consumption is captured and
displayed, with a run-level rollup).

**Why this epic exists:** Today a run is identified in the queue and detail views only by its
machine `ticketRef` ("DEL-1234") and `runId`. The originating ticket's **title** is already fetched
(`TicketSummary{ticketRef,title,summary}` via `TicketSummaryProvider`) for the runner context bundle
but is **discarded after bundling** — never persisted, never shown. Separately, there is **no
per-execution token accounting**: `RunnerExecutionSnapshot` carries no token fields, and 3d-7's
`ProviderUsageSnapshot` is rolling *quota* status, not per-step token counts. This epic surfaces
both — pure additive read-model work, no new state/action/event/error-code (light foundation-gate
footprint).

**Reused substrates:** `TicketSummaryProvider` (Linear adapter + offline stub, already fetches the
title); `integration_links.external_metadata` (home for external ticket info; `LinkedTicketView` is
built from it); the 3d-5 per-step log/step view (token display attaches here); the additive-runner-
contract-field precedent (3d-7) + `RunnerExecutionSnapshot` ctor-shim fan-out pattern.

**Decisions locked (this proposal):**
- **Origin depth:** detail page shows **title + ticket ref + link-out only** (minimal — not the
  full original body or initiating prompt).
- **Title source:** **snapshot at run creation** (offline-safe, immutable origin) — NOT live-resolve.
- **Token granularity:** **per-step + run-level rollup**.
- **Cost:** **token counts only** — estimated $ cost is a documented forward option (avoids a
  per-model pricing table to maintain).

### Story List (4 stories)

```
3g-1  Ticket-origin snapshot + read model (backend)
3g-2  FE — ticket title in queue + minimal Origin block on detail page
3g-3  Runner token-usage capture (contract + persistence)
3g-4  FE — per-step token display + run-level token rollup
```

### Story 3g-1 — Ticket-Origin Snapshot + Read Model

As an authorized user scanning the queue or opening a run,
I want each run to carry its originating ticket's title and a link back to the source ticket,
So that I can see *what* the run is and *where it came from* without decoding a bare `ticketRef`.

**Acceptance Criteria (draft):**
1. **Given** run creation, **Then** the already-fetched `TicketSummary.title` and a connector-built
   ticket URL are **snapshotted** onto the linked-ticket `integration_link` (`external_metadata`:
   `title`, `url`) at link time — immutable; no new `workflow_runs` column. Pre-3g rows keep `null`
   (parity).
2. **Given** the `TicketSourceAdapter`, **Then** it can produce a source-ticket URL for a `TicketRef`
   (Linear builds the issue URL; offline stub returns a deterministic stub URL); capability-gated —
   a connector that cannot produce a URL yields `null` (FE hides the link).
3. **Given** the read model, **Then** `WorkflowRunSummaryView` → `WorkflowSummaryResponse` gains a
   nullable `ticketTitle`; `LinkedTicketView` → `WorkflowDetailResponse.LinkedTicket` gains nullable
   `title` + `url`. OpenAPI + `schema.d.ts` regenerate.
4. **Given** the summary exact-field contract test (the `containsExactlyInAnyOrder` guard), **Then**
   it is updated for the new `ticketTitle` field (avoids the silent CI-only break).
5. **Given** redaction, **Then** the title passes the same content posture as the already-exposed
   `ticketRef`; ids/lengths only in logs.
6. **Given** tests, **Then** coverage asserts: snapshot persists at creation; summary + detail carry
   title/url; unlinked/pre-3g parity (null → no break); URL capability fallback; `application.*` ≥80%.

### Story 3g-2 — FE: Title in Queue + Origin Block

As an authorized user,
I want the ticket title in the queue and a small "Origin" block on the detail page,
So that runs are human-identifiable at a glance and I can click through to the source ticket.

**Acceptance Criteria (draft):**
1. Queue row renders `ticketTitle` (falls back to `ticketRef` when `null`).
2. Detail page shows a minimal **Origin** block: title, `ticketRef`, `integrationType`, and a
   link-out (rendered only when `url` present).
3. `schema.d.ts` regenerated first; Vitest covers title render + ref fallback + link presence/absence;
   axe-clean; honors the react-refresh no-fn-export + `useLiveAnnouncement` one-commit-lag traps.

### Story 3g-3 — Runner Token-Usage Capture

As the system,
I want each runner execution to record the agent's input/output/total token counts when the agent
reports them,
So that per-step token consumption is governed data, best-effort and nullable where unreported.

**Acceptance Criteria (draft):**
1. **Given** the runner result contract (`runner-contracts` schema), **Then** an additive optional
   `usage{inputTokens,outputTokens,totalTokens}` is added; both `runner.mjs` entrypoints emit it when
   the agent reports usage (absent/`null` when not reported — best-effort); both offline mocks emit
   deterministic token counts. (Heed the `runner-contracts` stale-in-`.m2` trap: install / `-am`.)
2. **Given** the next-free Flyway head, **Then** additive nullable `input_tokens` / `output_tokens` /
   `total_tokens` columns are added to `runner_executions`; replay-safe; in `FlywaySchemaContractTest`.
3. **Given** `RunnerExecutionSnapshot`, **Then** the three nullable token fields are appended at the
   END (+ctor shim per the fan-out pattern); populated by the persistence mapper on result ingest.
4. **Given** tests, **Then** coverage asserts: contract round-trip (present / absent / malformed
   non-fatal, both runners); columns persist + snapshot carries them; mock determinism; `application.*` ≥80%.

### Story 3g-4 — FE: Per-Step Tokens + Run Rollup

As an authorized user,
I want each step to show its token usage and the run to show a total,
So that I can see where tokens were spent and the run's overall consumption.

**Acceptance Criteria (draft):**
1. The 3d-5 per-step view renders input/output/total tokens, with a "not reported" indicator when
   the step's counts are `null` (mirrors 3d-7's not-exposed posture).
2. **Given** a backend run-level rollup, **Then** `WorkflowInspectionService` sums non-null step
   tokens into a nullable `totalTokens` on `WorkflowDetailResponse` (null when no step reported);
   OpenAPI + `schema.d.ts` regenerate.
3. Vitest covers per-step render + not-reported state + rollup; axe-clean.

**Epic 3g cross-cutting:** add FR73/FR74 to PRD §FR; two OpenAPI/`schema.d.ts` regen points (3g-1,
3g-4); update the summary exact-field contract test; `runner-contracts` install before backend-only
test. **No** new WorkflowState / AllowedAction / WorkflowEventType / DomainErrorCode — lighter
foundation-gate footprint than 3f. Forward option: estimated-cost display (per-model pricing config).

---

## Epic 3h — Pre-Review Quality Gates & Delivery-Tail Governance  *(FINALIZED)*

**Net-new scope:** **FR75** (governed local build validation with bounded auto-fix before review),
**FR76** (CPU-only static-analysis gate that halts for operator approval before LLM review),
**FR77** (BMAD-style multi-layer adversarial review mode), **FR78** (per-project push-mode +
PR/MR-creation governance with an explicit delivery-approval gate), **FR79** (post-push CI
build-error investigation).

**Why this epic exists:** Today the delivery tail is rigid and ungated. Stages are exactly
`{INVESTIGATION, EXECUTION, REVIEW}` (no build/lint), the advisory reviewer is the only quality
signal, and `RepositoryWorkspaceService.captureAndPush()` **auto-pushes + auto-creates a PR** the
instant the implementation result lands (`RunnerBroker.onResult` ~line 2097) — self-gated only on
"workspace exists + has uncommitted changes," with **no** push-mode or create-PR flag. There is
**zero** CI awareness (greenfield — no checks/Actions/Pipelines reader anywhere). This epic inserts
cheap **CPU quality gates before expensive LLM review**, makes the **push/PR tail governed and
configurable**, and adds **post-push CI failure investigation**.

**The structural crux — push relocation:** to run build → lint → review *before* the code is
pushed, `captureAndPush` is **lifted out of the `onResult` EXECUTION arm** and moved to the **end**
of the tail (the delivery gate). The backend keeps git ownership (`GitCommandPort.push`,
`RepositoryWorkspaceService`) — only the *trigger point* moves.

**Reused substrates:** the `priorFeedbackReferences` bundle + loop-counter + escalation-marker
pattern (spec-rejection / 3f split-repropose) powers all three fix loops (build, lint, CI); the
command-only runner-execution substrate + 3.6 raw-output capture + 3d-5 per-step view host the
no-token BUILD/LINT executions; the 3d-2 reviewer channel (`enqueueReviewerIfConfigured`,
`step_reviews`, `review-result`, verdict panel, `reviewer_model_kind`) is extended for BMAD mode;
per-project flags follow the `Project` aggregate + `ProjectRuntimeConfigResolver` precedent
(`auto-dispatch`, `openspecEnabled`, `reviewerModelKind`); `RepositoryHostAdapter` +
`RepositoryHostCapabilities` gain the new CI-checks port method.

**ADR (proposed):** `docs/adr/0030-governed-delivery-tail.md` — records the build→lint→review→deliver
ordering, push relocation, the new gate states, and the CI-checks port.

**Decisions locked (this proposal):**
- **Build failure (item 3):** bounded **auto-fix loop** — re-dispatch the implementation runner with
  build errors as referenced feedback (`build_fix_loop_count` + escalation marker), then escalate.
- **Lint gate (item 13):** **hard gate** — critical findings park the run in a new
  `WaitingForLintApproval` state before any LLM review runs.
- **BMAD review (item 9):** a **new review MODE augmenting** the 3d-2 reviewer (selected via
  `reviewer_model_kind`), not a replacement.
- **CI investigation (item 6):** **GitHub Actions in 3h**; Bitbucket Pipelines half deferred behind
  the Bitbucket adapter in Epic 3i.
- **Push modes (item 10):** per-project **auto / manual / approve-push**.
- **Delivery gate (items 10+16):** **unified** — one `WaitingForDelivery` state + `approve_delivery`
  action performs push and/or PR per the two per-project flags.
- **PR creation default (item 16):** **auto-create ON** — existing projects keep current behavior;
  flag opts *out* to approval-gated PR.

### Story List (6 stories)

```
3h-1  Build-validation stage (RunnerStage.BUILD, no-token) + bounded auto-fix loop        [item 3]
3h-2  CPU linter gate (RunnerStage.LINT) + WaitingForLintApproval hard gate               [item 13]
3h-3  BMAD-style multi-layer review mode (augments 3d-2)                                   [item 9]
3h-4  Push-mode + unified delivery gate (WaitingForDelivery/approve_delivery) + PR flag    [items 10+16]
3h-5  CI build-error investigation — GitHub Actions checks reader + fix loop               [item 6]
3h-6  FE — delivery-tail surfaces (build status, lint panel, BMAD verdict, delivery bar, CI panel)
```

> Sequencing: 3h-1 introduces BUILD + the push relocation; 3h-2 adds the LINT gate; 3h-3 enriches
> REVIEW; 3h-4 gates the (now end-of-tail) push/PR; 3h-5 closes the loop post-push. 3h-6 is the FE
> twin (or fold per-surface into each backend story, 3f-style).

### Story 3h-1 — Build-Validation Stage + Bounded Auto-Fix Loop  *(item 3)*

As an operator, I want the produced code compiled/built before it reaches review, and build failures
auto-fixed first, so review and push only ever see buildable code.

**Acceptance Criteria (draft):**
1. New `RunnerStage.BUILD("build")` — a **command-only** (no-LLM, no-token) execution that runs the
   governed project's configured build command in the materialized workspace; reuses the 3.6
   raw-output capture + 3d-5 step view; every `switch(stage)` consumer gains a BUILD arm.
2. Per-project `buildCommand` + `build-stage.enabled` on `Project` + `ProjectRuntimeConfigResolver`;
   **default disabled** → projects with no build config skip BUILD (byte-identical to pre-3h parity).
3. BUILD is dispatched after PR-output ingest and **before** `captureAndPush` (which 3h-4 relocates)
   and before REVIEW; on success the tail proceeds.
4. On BUILD failure, a bounded **auto-fix loop**: re-dispatch the EXECUTION/PR_OUTPUT runner with the
   build error log as a redaction-policed `priorFeedbackReferences` input; `build_fix_loop_count`
   tracked (distinct idempotency keys) with an escalation marker; cap `build-stage.max-fix-loops`
   (default 3). Cap exceeded → run fails with a build `FailureCategory` (+ escalation marker) for
   Epic-4 recovery — never silently pushed.
5. Tests: BUILD pass proceeds; fail loops + bumps count + honors cap → escalate; disabled-parity;
   command-only emits no token usage (ties to 3g-3); `application.*` ≥80%.

### Story 3h-2 — CPU Linter Gate + `WaitingForLintApproval`  *(item 13)*

As an operator, I want CPU-only Java/TS linters to run before the LLM review, and critical findings
to halt for my approval or fix, so I don't spend review tokens on code that fails static analysis.

**Acceptance Criteria (draft):**
1. New `RunnerStage.LINT("lint")` — command-only/no-token; runs the governed project's **own**
   configured linters (Java + TypeScript) in the workspace; per-project `lintCommands` +
   `lint-stage.enabled` on `Project` + resolver; default disabled (parity).
2. Findings parsed to a severity-classified result; **critical** (linter `error` severity) →
   transition to new non-terminal `WaitingForLintApproval` (registry + CHECK + state-machine +
   API-schema drift); non-critical → advisory, attached, proceed.
3. New `AllowedAction`s `approve_lint("approve_lint")` (proceed to REVIEW) and
   `request_lint_fix("request_lint_fix")` (re-dispatch EXECUTION with findings as referenced
   feedback, loop-counted) for the gate role; foundation-gate drift (registry + placeholder + pin).
4. Findings persisted (extend `step_reviews` payload or a lint-findings store) + served for the FE
   panel; redaction posture honored.
5. Tests: critical parks + gates before REVIEW; approve proceeds; request-fix loops; non-critical
   advisory; disabled-parity; new state + 2 actions drift; `application.*` ≥80%.

### Story 3h-3 — BMAD-Style Multi-Layer Review Mode  *(item 9)*

As an operator, I want a deeper adversarial review option, so high-risk changes get Blind-Hunter /
Edge-Case-Hunter / Acceptance-Auditor scrutiny instead of a single pass.

**Acceptance Criteria (draft):**
1. `reviewer_model_kind` (or an adjacent reviewer-mode flag) gains a **`bmad`** mode; when selected,
   the REVIEW stage runs the multi-layer adversarial review and emits a **richer** verdict
   (categorized findings + triage) via an additive `review-result` field/version — single-pass mode
   stays available (augment, not replace).
2. Both `runner.mjs` review entrypoints + both offline mocks support the bmad payload (deterministic
   mock verdict); advisory-only / degrade-not-5xx posture preserved (the 3d-2 discipline).
3. Verdict panel (FE, 3h-6) renders the multi-layer findings; `step_reviews` / harvester widened for
   the richer payload without breaking single-pass.
4. Tests: bmad mode emits multi-layer verdict + harvests; single-pass parity; degrade path; OpenAPI/
   `schema.d.ts` regen; `application.*` ≥80%.

### Story 3h-4 — Push-Mode + Unified Delivery Gate + PR Flag  *(items 10 + 16)*

As an operator, I want to choose per project whether push and PR/MR happen automatically or behind an
explicit approval, so some projects push manually / review before the CI build runs.

**Acceptance Criteria (draft):**
1. Per-project `pushMode ∈ {auto, manual, approve}` (default `auto`) + `autoCreatePullRequest`
   (default `true`) on `Project` + `ProjectRuntimeConfigResolver`; seeded from `application.yml`
   defaults (the `auto-dispatch` precedent).
2. `captureAndPush` is **relocated** out of the `onResult` EXECUTION arm to the end of the tail
   (after BUILD/LINT/REVIEW gates pass); backend keeps git ownership.
3. New non-terminal `WaitingForDelivery` state + `AllowedAction approve_delivery("approve_delivery")`
   (gate role); drift at all foundation sites.
4. Mode behavior: **auto** → gates pass → `captureAndPush` runs automatically (PR created iff
   `autoCreatePullRequest`); **approve** → park `WaitingForDelivery`, `approve_delivery` runs
   `captureAndPush` (push + PR per flag); **manual** → park `WaitingForDelivery`, system never calls
   `git.push`/`createPullRequest`, `approve_delivery` records the out-of-band delivery and advances.
5. `createOrUpdatePullRequest` is gated by `autoCreatePullRequest` (deferred to `approve_delivery`
   when false); existing repoRef-presence gate preserved.
6. Parity: a project left at `auto` + `autoCreatePullRequest=true` is **byte-identical** to pre-3h
   (push + PR on result) — except push now fires after the gates rather than at `onResult`.
7. Tests: each mode (auto push+PR / approve gate→delivery / manual no-git); PR flag off defers PR;
   relocation parity; new state + action drift; idempotent `approve_delivery`; `application.*` ≥80%.

### Story 3h-5 — CI Build-Error Investigation (GitHub Actions)  *(item 6)*

As an operator, I want the system to read the pushed branch's CI build result and investigate/fix
failures, so a red CI run is triaged automatically rather than waiting on me.

**Acceptance Criteria (draft):**
1. New `RepositoryHostAdapter` port method (e.g. `readCheckRuns(repo, ref) → CiStatus`) +
   `RepositoryHostCapabilities.supportsCiStatusReads` (default false); **GitHub** adapter implements
   it (Actions/check-runs API); GitLab/Bitbucket report false for now (Bitbucket lands in 3i).
2. After a push (auto or approve mode), the run polls CI status (bounded, best-effort); a **failed**
   CI build → an investigation/fix loop re-dispatching EXECUTION with the CI failure log as a
   referenced input (`ci_fix_loop_count` + escalation marker + cap) — mirrors 3h-1's loop, distinct
   source.
3. CI status is surfaced on the read model (run view); `supportsRequiredStatusChecks` becomes
   meaningfully backed by a live read where the new capability is true.
4. Manual-push projects (no backend push) do not poll (nothing was pushed by us) — documented.
5. Tests: green CI proceeds; red CI loops + bounded; capability-false skips (parity); redaction over
   CI logs; `application.*` ≥80%.

### Story 3h-6 — FE: Delivery-Tail Surfaces

Per-surface FE for: build status + auto-fix loop indicator; lint-gate panel with approve / request-fix
(the `WaitingForLintApproval` Decision Bar); BMAD multi-layer verdict in the panel; the
`WaitingForDelivery` Decision Bar with `approve_delivery`; CI investigation status. Regen
`schema.d.ts` first; Vitest + axe; honor the react-refresh-no-fn-export + `useLiveAnnouncement` traps.

**Epic 3h cross-cutting:** add FR75–FR79 to PRD; **two new `RunnerStage`s** (BUILD, LINT — every
`switch(stage)` consumer + the `runner_executions.stage` text column) ; **two new `WorkflowState`s**
(`WaitingForLintApproval`, `WaitingForDelivery` — CHECK + state-machine + API-schema drift); **three
new `AllowedAction`s** (`approve_lint`, `request_lint_fix`, `approve_delivery`) + foundation-gate
drift; likely a new `FailureCategory` (build) — three-sites; a new `RepositoryHostAdapter` port
method + capability; additive `review-result` field (runner-contracts install trap); per-project
config on `Project` (build/lint commands + enable flags, `pushMode`, `autoCreatePullRequest`,
`reviewer` bmad mode); OpenAPI/`schema.d.ts` regen. ADR-0030. Forward options: PMD/SpotBugs-specific
parsers, Bitbucket Pipelines CI (3i), cascade policies on repeated CI failure.

---
## Epic 3i — Connector Expansion: JIRA, Bitbucket, Sentry  *(FINALIZED)*

**Net-new scope:** **FR80** (JIRA as a first-class ticket source), **FR81** (filtered ticket intake
by assignee + components), **FR82** (Bitbucket as a repository host incl. Pipelines CI), **FR83**
(Sentry error ingestion → operator-promoted governed bug tickets).

**Why this epic exists:** The connector abstractions (3-32 `TicketSourceAdapter`, 3-33
`RepositoryHostAdapter`, 3c connector resolution + credential store) were built vendor-neutral but
only **Linear** (ticket) and **GitHub** (repo) are real; GitLab is a stub. Pilot teams run on
JIRA + Bitbucket, and Sentry is the source of the bugs they most want governed. This epic adds those
three connectors on the existing seams, plus the one genuinely new capability the substrate lacks: a
**filtered ticket query** (`supportsPolling` is "updated-since," not "by assignee/component").

**Reused substrates:** `connectorKind()` + `@Primary` resolution (3c-3); encrypted credential store
+ redaction (3c-5); `verifyConnectivity` connectivity probe (3c-8); the `GitLabRepositoryHostStub`
precedent for a new repo host; `createSubticket` / ticket creation (3f-1) for Sentry→bug; the new
CI-checks port + `supportsCiStatusReads` capability (3h-5) for Bitbucket Pipelines; the doctor probe
pattern (3c-10 — **heed the checksRun fan-out trap**); the 3g-1 ticket-URL capability.

**Decisions locked (this proposal):**
- **JIRA filter (item 4):** an **interactive ticket-intake browse** — list candidate JIRA tickets by
  assignee + components; operator picks which to start governed runs on.
- **Bitbucket (item 5):** **repo-host only** (push / pull requests / Pipelines); ticket sourcing
  stays with JIRA/Linear.
- **Bitbucket Pipelines (item 6):** the Pipelines CI reader **lands in 3i-3** (completes item 6 for
  both CI providers).
- **Sentry (item 12):** **operator-gated promotion** — surface issues, operator promotes selected
  ones to governed bug tickets; no auto-flood.

### Story List (4 stories)

```
3i-1  JIRA TicketSourceAdapter (kind=jira) — Linear-parity (fetch/comment/createSubticket/state/URL)  [item 4]
3i-2  Filtered ticket-intake browse — queryTickets by assignee + components (supportsTicketQuery)     [item 4]
3i-3  Bitbucket RepositoryHostAdapter (kind=bitbucket) + Bitbucket Pipelines CI reader                [items 5+6]
3i-4  Sentry error-source connector — issue ingest → operator-gated promotion to bug tickets          [item 12]
```

### Story 3i-1 — JIRA Ticket Source  *(item 4)*

**Acceptance Criteria (draft):**
1. New `TicketSourceAdapter` impl `connectorKind()=jira` implementing `fetchTicketByReference`,
   `postGovernedRunComment`, `createSubticket`, ticket-state read, and the 3g-1 ticket-URL builder;
   `getCapabilities` reports the real JIRA capability set (`supportsCommentOnTicket`,
   `supportsTicketCreation`, `supportsTicketStateUpdates`, polling as available).
2. JIRA connector kind added to the `ConnectorKind` registry (+ Flyway connector-kind widening if
   CHECK-constrained, the GITLAB-V18 precedent); credentials via the 3c-5 encrypted store (API
   token / email), never exposed; redaction posture honored.
3. `verifyConnectivity` (3c-8) probes JIRA auth + project reachability; a `jira` doctor probe added
   (checksRun count incremented at all hardcoded sites — fan-out trap).
4. Tests: capability contract; fetch/comment/createSubticket happy-paths; connectivity probe;
   credential redaction; `application.*` ≥80%.

### Story 3i-2 — Filtered Ticket-Intake Browse  *(item 4)*

**Acceptance Criteria (draft):**
1. New port method `queryTickets(TicketQuery{assignee?, components[], state?, limit}) → List<TicketSummary>`
   + `TicketSourceCapabilities.supportsTicketQuery` (default false); JIRA implements it (JQL-backed),
   Linear reports false for now (additive later).
2. A REST + CLI intake surface lists candidate tickets for a project filtered by assignee +
   components; the operator selects one (or several) to submit as governed run(s) — reusing the
   existing submit path (`WorkflowCommandService.submit`). OpenAPI + `schema.d.ts` regen.
3. FE: an intake/browse view with assignee + component filter controls (reuse the 3c-9 project
   selector pattern); axe-clean; honors the FE traps.
4. Tests: query maps filters → JQL; capability-false connectors omit the surface; selection submits a
   run; `application.*` ≥80%.

### Story 3i-3 — Bitbucket Repository Host + Pipelines CI  *(items 5 + 6)*

**Acceptance Criteria (draft):**
1. New `RepositoryHostAdapter` impl `connectorKind()=bitbucket` (push target, `createPullRequest` /
   `updatePullRequest` / `commentOnPullRequest`, `verifyConnectivity`, `getCapabilities`) — the
   GitLab-stub promoted to a real impl shape; Bitbucket Cloud pull requests.
2. Implements the 3h-5 `readCheckRuns`/CI-status port for **Bitbucket Pipelines**, reporting
   `supportsCiStatusReads=true`; feeds the same CI investigation loop (3h-5) for Bitbucket projects.
3. Bitbucket connector kind + credentials (3c-5) + `bitbucket` doctor probe (checksRun fan-out);
   capability contract test.
4. Tests: PR create/update/comment; Pipelines status read (green/red); connectivity; capability
   drift; `application.*` ≥80%.

### Story 3i-4 — Sentry Error-Source Connector  *(item 12)*

**Acceptance Criteria (draft):**
1. A new **error-source** adapter category (`SentryAdapter` / `ErrorSourceAdapter` port) that lists
   Sentry issues for a project (filtered by environment/level/unresolved), credentials via 3c-5.
2. **Operator-gated promotion:** a REST + CLI + FE surface lists Sentry issues; promoting one creates
   a governed **bug ticket** in the project's ticket source (via 3f-1 `createSubticket` / ticket
   creation) carrying the Sentry issue context (title, culprit, permalink), then submits a governed
   run on the bug workflow (Epic 3j). Dedup on Sentry issue id (idempotent — no duplicate bug per
   issue).
3. No auto-creation: nothing enters the queue without an explicit promote action.
4. Tests: issue list; promote → bug ticket + run with Sentry context; dedup replay; redaction over
   Sentry payloads; `application.*` ≥80%.

**Epic 3i cross-cutting:** add FR80–FR83 to PRD; new `ConnectorKind` registry values (jira,
bitbucket) + Flyway widening; new `supportsTicketQuery` capability; new error-source adapter category;
three new doctor probes (checksRun count fan-out — update every hardcoded assertion); credential-store
entries + redaction-corpus gates (the two-gates trap); OpenAPI/`schema.d.ts` regen (3i-2, 3i-4).
**Cross-epic deps:** 3i-3 Pipelines depends on 3h-5's CI port; 3i-4 Sentry depends on 3f-1 (done) and
feeds 3j (bug workflow). Forward options: Linear/GitHub `queryTickets`; Bitbucket Issues as a ticket
source; GitLab promotion from stub; auto-create Sentry bugs behind a per-project flag.

---
## Epic 3j — Ticket-Type Workflows: Bug vs Feature  *(FINALIZED)*

**Net-new scope:** **FR84** (a governed run follows a workflow profile selected by its ticket type)
+ **FR85** (a distinct bug path: reproduction/triage + lightweight fix-plan).

**Why this epic exists:** Orchestration runs a single hardcoded spine (spec → plan → implementation
→ review → deliver), with the next stage chosen by `(stage, subStage)` in `RunnerBroker.onResult`.
Bugs and features want different shapes — a bug should be reproduced and root-caused before a heavy
spec is written. This epic introduces a bounded **workflow-profile** concept the orchestration
consults, so the *spine itself* branches by type. (This is the one epic that touches the core
stage-selection logic — sequence it carefully against the in-flight 3f work on the same broker path.)

**Decisions locked (this proposal):**
- **Branching model:** **two built-in profiles** (`bug`, `feature`) + `feature` default — not a
  general template engine (that's a forward option / its own epic).
- **Bug path:** **repro/triage + lighter spec** — a reproduction + root-cause phase up front, then a
  lightweight fix-plan gate, then implement → review → deliver (keeps a governance gate).
- **Type source:** **connector type + per-project map, intake override** — derive from JIRA issue
  type / Linear label via a per-project `ticketType → profile` map; operator can override at intake.

**Reused substrates:** the `Ticket` domain type (carries the connector ticket type); the per-project
child-table config precedent (`project_runner_kinds`, 3e-4) for the type→profile map; the existing
INVESTIGATION stage + sub-stage discriminator pattern (`ExecutionSubStage`) so the bug **triage**
phase can ride INVESTIGATION with a bug sub-stage rather than a brand-new `RunnerStage` (lighter
`switch(stage)` footprint — recommended); 3i-4 Sentry promotes onto the `bug` profile.

### Story List (3 stories)

```
3j-1  Workflow-profile concept + type resolution (registry, per-project map, intake override, run.profile)  [item 7]
3j-2  Bug workflow path — triage/repro phase + lightweight fix-plan; orchestration branches by profile        [item 7]
3j-3  FE — profile/type visibility on queue + detail; intake type override control                            [item 7]
```

### Story 3j-1 — Workflow-Profile Concept + Type Resolution

**Acceptance Criteria (draft):**
1. New `WorkflowProfile` registry (`bug`, `feature`); `feature` is the default. `Ticket` exposes its
   connector ticket type (additive nullable field at END if not already present).
2. Per-project `ticketType → WorkflowProfile` map (child table, the `project_runner_kinds` precedent)
   + resolver method on `ProjectRuntimeConfigResolver`; intake resolves the profile from the ticket
   type, with an explicit operator override accepted at submit.
3. The resolved profile is persisted on the run (additive nullable `workflow_profile` column,
   Flyway, replay-safe, `FlywaySchemaContractTest`); default/legacy runs resolve to `feature`
   (parity).
4. Tests: type→profile mapping; override at intake; default parity; Flyway drift; `application.*` ≥80%.

### Story 3j-2 — Bug Workflow Path

**Acceptance Criteria (draft):**
1. For a `bug`-profile run, orchestration runs a **triage/repro** phase first (recommended: an
   INVESTIGATION bug sub-stage producing a reproduction + root-cause artifact), then a **lightweight
   fix-plan** gate (a lighter spec-approval), then implement → review → deliver. The next-stage
   selection in `RunnerBroker.onResult` / orchestration consults `run.workflow_profile`.
2. `feature`-profile runs are **byte-identical** to the pre-3j spine (parity hot path).
3. Both `runner.mjs` entrypoints + offline mocks emit the bug-triage artifact deterministically; the
   bug prompts/gate copy are profile-specific.
4. Tests: bug run takes the triage path + lighter gate; feature parity; mock determinism;
   `application.*` ≥80%.

### Story 3j-3 — FE: Profile/Type Visibility + Intake Override

**Acceptance Criteria (draft):**
1. Queue + detail show the run's ticket type and resolved profile (a badge); the intake/browse view
   (3i-2) offers a profile override control.
2. `schema.d.ts` regen; Vitest + axe; FE traps honored.

**Epic 3j cross-cutting:** add FR84/FR85 to PRD; new `WorkflowProfile` registry; per-project map child
table (Flyway) + additive `workflow_profile` run column; orchestration stage-selection branch
(**sequence against in-flight 3f broker work — same `onResult` path**); profile-specific prompts/gate
copy; OpenAPI/`schema.d.ts` regen. Forward options: a general workflow-template engine; more profiles
(spike/chore/hotfix); per-profile reviewer-mode defaults (compose with 3h-3 BMAD mode).

---
## Epic 3k — Runner Platform / VM Execution  *(FINALIZED)*

**Net-new scope:** **FR86** (governed runner work executes on a remote, operator-provisioned runner
service), **FR87** (a full-access agent runner is permitted only on an isolated remote runner) +
**spike** (Kimi agent feasibility).

**Why this epic exists:** Today `DockerRunnerAdapter` runs every runner as a **local Docker container
on the orchestrator host**, and the codex runner is **read-only sandboxed** (bwrap/seccomp). Pilot
work needs agents with **full filesystem/network access**, which is unsafe on the orchestrator host —
so execution must move **off-host into an isolated VM**. The `runner_executions` queue (3.17a/b)
already has the **dequeue-lease + reserve-at-worker + heartbeat** substrate this needs; the epic
extends it across a network boundary. **Highest-risk epic — ADR-0031 up front.**

**Decisions locked (this proposal):**
- **Protocol (item 14):** **remote queue-pull** — the runner service runs a worker that dequeues
  `runner_executions` over the network, reusing the existing dequeue-lease + reserve-at-worker
  substrate (backpressure + retry for free). Not a new RPC.
- **VM provisioning (item 14/15):** **externally provisioned** — the operator stands up the VM + runs
  the runner service; the backend connects + dispatches. VM lifecycle automation = forward option.
- **Full-access gating (item 15):** **VM-only** — the full-access (unsandboxed) runner kind is
  dispatchable ONLY to a remote VM runner; attempting it locally is refused. The VM is the security
  boundary.
- **Kimi (item 8):** **spike only** — a feasibility ADR + recommendation; a real Kimi runner kind is
  deferred.

**Reused substrates:** the 3.17a/b queue (V12/V14 dequeue lease, `workerId`, `queueAttemptCount`,
`heartbeatStaleEmittedAt`, reserve-at-worker, correlation/idempotency carriage); `runner-contracts`
(transported unchanged to the remote worker — install trap); `DockerRunnerAdapter` + `runner.mjs`
(packaged into the standalone runner service); the codex sandbox config (`bwrap`/`seccomp` HostConfig)
that the full-access kind deliberately omits; the 3c-5 credential store + 3c-8 connectivity probe
(for the runner-service endpoint registration).

### Story List (4 stories)

```
3k-1  Remote runner-service extraction — network dequeue protocol + standalone runner-service boundary  [item 14]
3k-2  Runner-service registration + dispatch routing (local vs remote, per project/step)                [item 14]
3k-3  Full-access codex VM runner kind — unsandboxed, remote-only, refused on host                       [item 15]
3k-4  Kimi agent feasibility spike (ADR + recommendation)                                                [item 8]
```

### Story 3k-1 — Remote Runner-Service Extraction

**Acceptance Criteria (draft):**
1. The `runner_executions` dequeue-lease path is exposed over a **network transport** (authenticated;
   transport-secured) so a remote worker can `dequeue` → run → report result, preserving the
   reserve-at-worker, lease/heartbeat (`heartbeatStaleEmittedAt`), `queueAttemptCount`, and
   correlation/idempotency carriage semantics (no double-execution under lease expiry).
2. `runner.mjs` + the Docker execution shim are packaged into a **standalone runner service**
   deployable in a VM; it carries `runner-contracts` unchanged (install/`-am` trap).
3. Result/raw-output ingest (3.6) + harvest are unchanged on the backend side — the remote worker
   reports through the same contract.
4. Tests: remote dequeue→run→report round-trip (IT); lease expiry re-dispatch (no double-run);
   transport auth rejected without credential; `application.*` ≥80%.

### Story 3k-2 — Runner-Service Registration + Dispatch Routing

**Acceptance Criteria (draft):**
1. An operator registers a remote runner-service endpoint (URL + credential via 3c-5) +
   `verifyConnectivity` probe (3c-8) + a `runner-service` doctor probe (checksRun fan-out).
2. Per-project / per-step routing decides whether an execution runs **local** (current
   `DockerRunnerAdapter`) or is left on the queue for a **remote** worker — resolved via
   `ProjectRuntimeConfigResolver` (the runner-kind 3-layer precedent); default = local (parity).
3. A remote runner reports its capabilities (which runner kinds it supports, incl. full-access);
   routing refuses a kind no registered runner supports.
4. Tests: local-default parity; remote routing; capability mismatch refused; connectivity + doctor;
   `application.*` ≥80%.

### Story 3k-3 — Full-Access Codex VM Runner Kind

**Acceptance Criteria (draft):**
1. A new runner kind (e.g. `codex-full`) runs the codex CLI with **full filesystem/network access**
   — the `bwrap`/`seccomp` read-only sandbox HostConfig is deliberately omitted.
2. **Remote-only gate:** dispatching `codex-full` to the **local host** is refused with a new
   `FULL_ACCESS_REQUIRES_REMOTE_RUNNER` error (registry + ProblemDetails + drift — three sites); it
   is only dispatchable to a registered remote VM runner that advertises the capability.
3. The override + the elevated-access disposition are recorded in the governed history (auditable).
4. Tests: full-access kind runs on a remote runner; local dispatch refused; capability gating; audit
   record; `application.*` ≥80%.

### Story 3k-4 — Kimi Agent Feasibility Spike

**Acceptance Criteria (draft):**
1. A time-boxed investigation of running **Kimi** agents as a runner kind: API/CLI surface, auth,
   `runner-contracts` fit, sandbox/full-access posture, cost/limits, offline-mock feasibility.
2. Output: an ADR + go/no-go recommendation + a sketched story breakdown for a real Kimi runner kind
   (deferred). No production runner kind shipped in this story.

**Epic 3k cross-cutting:** add FR86/FR87 to PRD; **ADR-0031** (remote runner architecture + security
boundary); network transport + auth (new infra); new runner kind(s) + capability advertisement; new
`FULL_ACCESS_REQUIRES_REMOTE_RUNNER` error (three sites); runner-service registration + credential +
doctor probe (checksRun fan-out); `DockerRunnerAdapter` ctor fan-out trap when threading routing.
**Security review required** (full-access execution). Forward options: VM lifecycle automation; real
Kimi runner kind; remote-runner autoscaling; per-run ephemeral VMs.

---
## Epic 3l — Project Memory & Artifact Lineage  *(FINALIZED)*

**Net-new scope:** **FR88** (a project-scoped memory graph of ticket relations + artifacts, queryable
+ visualized), **FR89** (operator-assertable relations between tickets/runs), **FR90** (related prior
memory is retrieved into agent context bundles).

**Why this epic exists (the requester-flagged EPIC):** The raw relational data already exists but is
**scattered and unorganized** — `parent_run_id` lineage (3f-2), `run_dependencies` (3f-3),
`integration_links` (ticket↔run↔PR), per-run artifacts, Sentry→bug links (3i-4), bug↔feature profiles
(3j). There is no project-scoped layer that **organizes it into a navigable memory** of how tickets
relate and what artifacts they produced — and no mechanism to **feed that history back to the agents**
so they leverage prior work. This epic builds both. It **complements** (does not duplicate) Epic 4's
planned audit-by-ticket query (story 4.3): audit is the *event stream*; project memory is the
*relationship + artifact graph* and the *agent-context feed*.

**Decisions locked (this proposal):**
- **Purpose:** **both** — the human-facing relationship/artifact **graph** AND **agent-context**
  retrieval. (Large epic → sub-sequenced Part A then Part B.)
- **Relation data:** **project existing + a few operator-assertable relations** (`duplicate-of`,
  `caused-by`, `relates-to`) — not a general knowledge store.
- **Surface:** **query API (REST + CLI) + an FE graph/timeline view**.

**Reused substrates:** 3f-2 lineage, 3f-3 `run_dependencies`, `integration_links`, the artifacts
store, 3i-4 Sentry links, 3j profiles (the graph projects all of these); the context-bundle builder +
`priorFeedbackReferences` by-reference mechanism + the 256KB payload cap (memory retrieval injects
**by reference**, never inlined past the cap — the context-bundle-2KB→256KB trap); the redaction /
data-classification posture (injected memory must carry classification); context-bundle inspection
(provenance of what memory was injected).

### Story List (5 stories — Part A: graph; Part B: agent context)

```
Part A — relationship & artifact graph
3l-1  Project-memory read model — projection over lineage/deps/links/artifacts into a graph
3l-2  Operator-assertable relations (duplicate-of / caused-by / relates-to) typed-edge store
3l-3  Query API (REST+CLI: "everything related to ticket X + artifacts") + FE graph/timeline view

Part B — agent context
3l-4  Memory retrieval into context bundles (graph-neighborhood related tickets/specs/artifacts, by reference)
3l-5  Injected-memory provenance + relevance ranking + redaction posture (+ FE "memory used" inspection)
```

### Story 3l-1 — Project-Memory Read Model

**Acceptance Criteria (draft):** a `ProjectMemoryService` projects, for a project, a graph of nodes
(tickets, runs, artifacts) and edges (split lineage, run dependency, integration link, produced-by,
Sentry→bug, bug↔feature) from **existing** persistence — no new write-path for these; scoped by
`projectId` (3c-7); performance within read-path budgets (indexed). Tests: graph assembles all edge
types; project scoping; parity; `application.*` ≥80%.

### Story 3l-2 — Operator-Assertable Relations

**Acceptance Criteria (draft):** an additive typed-edge store (`project_relations`: from, to,
`relationType ∈ {duplicate_of, caused_by, relates_to}`, actor, reason) — Flyway, replay-safe; a
service + REST/CLI to assert/retract a relation (idempotent, audited); folded into the 3l-1 graph.
Tests: assert/retract; relation-type registry drift; graph inclusion; `application.*` ≥80%.

### Story 3l-3 — Query API + Graph/Timeline View

**Acceptance Criteria (draft):** REST + CLI "memory query" returning everything related to a ticket/run
(transitive relations + artifacts) with bounded depth + redaction; an FE relationship **graph /
timeline** view; OpenAPI + `schema.d.ts` regen; Vitest + axe; FE traps honored. Tests: query depth +
scoping + redaction; FE renders graph; `application.*` ≥80%.

### Story 3l-4 — Memory Retrieval into Context Bundles  *(agent context)*

**Acceptance Criteria (draft):** the context-bundle builder for spec/implementation stages retrieves
**related prior memory** (graph-neighborhood related tickets, their approved specs/artifacts) and
includes it **by reference** (the `priorFeedbackReferences` mechanism — never inlined past the 256KB
cap); retrieval is relation-based (graph neighborhood), embeddings a forward option; per-project
toggle (default off → parity). Tests: related memory injected by reference; cap respected; toggle-off
parity; `application.*` ≥80%.

### Story 3l-5 — Injected-Memory Provenance + Relevance + Redaction

**Acceptance Criteria (draft):** injected memory carries data-classification + passes the redaction /
secret-fixture gate; a relevance ranking bounds what is injected; the context-bundle inspection
surface shows **which memory was used** (provenance); FE shows it on the run/context view. Tests:
redaction over injected memory; ranking bounds set; provenance recorded + surfaced; `application.*` ≥80%.

**Epic 3l cross-cutting:** add FR88–FR90 to PRD; new `project_relations` table + `RelationType`
registry; **complements Epic 4.3** audit (cross-reference both ways, avoid duplicate query surfaces);
context-bundle path change (by-reference injection — the 256KB-cap + classification traps);
OpenAPI/`schema.d.ts` regen (3l-3); FE graph view. **Sub-sequence Part A before Part B.** Forward
options: embedding/vector retrieval; cross-project memory; auto-suggested relations; memory in the
recovery/diagnostics views (Epic 4).

---

## Section 3 — Recommended Approach & Sequencing

**Approach: Direct Adjustment (additive scope expansion).** All six epics are **net-new product
scope** layered on existing substrates — none rolls back or rewrites delivered work. They slot
**between Epic 3f and Epic 4** as sub-lettered epics `3g`–`3l` (the `3c`–`3f` precedent), so Epics
4–6 are **not renumbered**. New FRs **FR73–FR90** are appended to the PRD.

**Recommended build order** (dependency-driven):

1. **3g — Provenance & Token Accounting** — smallest, additive read-model; pins DTO/persistence
   conventions. No new state/action/event. *Start here.*
2. **3h — Pre-Review Quality Gates & Delivery-Tail Governance** — richest; restructures the
   delivery tail (push relocation). Establishes the BUILD/LINT stages + delivery gate + CI port that
   3i and 3j build on.
3. **3i — Connector Expansion (JIRA/Bitbucket/Sentry)** — consumes 3h's CI port (Bitbucket
   Pipelines) and feeds 3j (Sentry→bug); independent of 3g/3j otherwise.
4. **3j — Ticket-Type Workflows** — touches the orchestration spine; sequence **after** in-flight
   3f broker work and ideally after 3h (shares the `onResult` path); consumes 3i (Sentry→bug
   profile).
5. **3k — Runner Platform / VM Execution** — highest-risk, ADR-0031 + security review; mostly
   independent (extends the 3.17 queue). Can run in parallel with 3i/3j by a separate track.
6. **3l — Project Memory & Artifact Lineage** — depends on the relational data the earlier epics
   enrich (3i links, 3j profiles); **last**. Part A (graph) before Part B (agent context).

**Cross-epic dependency edges:** 3h-5 CI port → 3i-3 Bitbucket Pipelines; 3f-1 ticket creation +
3i-4 Sentry → 3j bug profile; 3h delivery-tail + 3j spine both touch `RunnerBroker.onResult` (serialize
them); 3i links + 3j profiles → 3l graph.

## Section 4 — Impact Analysis

- **PRD:** append FR73–FR90 (six FR blocks). No existing FR changed.
- **Epics index (`epics.md`) + new epic files:** add `epic-03g`…`epic-03l-*.md`; update the master
  story list. Epic 4–6 unchanged.
- **Architecture / ADRs:** ADR-0030 (governed delivery tail, 3h), ADR-0031 (remote runner
  architecture + security boundary, 3k); touch ADR-0008 (repo-host) for Bitbucket and the
  connector-resolution ADR for JIRA.
- **Registries / foundation gates (drift fan-out):** new `RunnerStage`s (BUILD, LINT — `switch(stage)`
  consumers); new `WorkflowState`s (`WaitingForLintApproval`, `WaitingForDelivery`); new
  `AllowedAction`s (`approve_lint`, `request_lint_fix`, `approve_delivery`); new `WorkflowProfile`,
  `ConnectorKind` (jira, bitbucket), `RelationType`, error codes
  (`FULL_ACCESS_REQUIRES_REMOTE_RUNNER`, build failure category); new capabilities
  (`supportsTicketQuery`, `supportsCiStatusReads`). Each carries the documented three-sites / fixture
  / placeholder / pin drift work.
- **Read-model / OpenAPI:** multiple `WorkflowSummaryResponse`/`WorkflowDetailResponse` widenings
  (update the exact-field contract test each time); several OpenAPI + `schema.d.ts` regens.
- **Security:** 3k full-access execution requires a security review; 3i credential-store + redaction
  gates per connector.
- **Test/CI:** new ITs (remote runner, CI investigation, delivery gate); `runner-contracts` install
  trap recurs (3g-3, 3h-3, 3k-1).

## Section 5 — Implementation Handoff

**Scope classification: MAJOR** — six new epics, new FRs, two ADRs, core-path changes (delivery tail,
orchestration spine, runner platform). Route to **PM / Architect** for epic-file authoring + ADRs,
then standard per-story create-story → dev-story execution.

**Next steps:**
1. Approve this proposal.
2. Author the six epic files (`epic-03g`…`epic-03l`) from these finalized sections; append FR73–FR90
   to the PRD; draft ADR-0030 + ADR-0031.
3. Update `epics.md` master list + sprint planning.
4. Begin with Epic 3g (warm-up), then 3h.

**Deliverable:** this document — `sprint-change-proposal-2026-06-29-epics-3g-3l.md`.
