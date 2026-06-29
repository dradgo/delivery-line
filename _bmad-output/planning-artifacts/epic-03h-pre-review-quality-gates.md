## Epic 3h: Pre-Review Quality Gates & Delivery-Tail Governance

The governed delivery tail moves beyond the rigid *implementation → auto-push → auto-PR → advisory-review* shape that Epics 1–3f assume. Today a run's stages are exactly `{INVESTIGATION, EXECUTION, REVIEW}` with no build or lint signal, the advisory reviewer is the only quality gate, and the instant an implementation result lands the workspace is **auto-pushed and a PR is auto-created** — with no push-mode or create-PR control. This epic inserts cheap **CPU quality gates before expensive LLM review**: the produced code is **built** (a no-token command-only `BUILD` stage) and **linted** (a no-token `LINT` stage) first; a build failure drives a **bounded auto-fix loop** before escalation, and critical lint findings are a **hard gate** that parks the run in a new non-terminal `WaitingForLintApproval` state for operator `approve_lint` / `request_lint_fix`. The advisory reviewer gains a **BMAD multi-layer adversarial review mode** (Blind-Hunter / Edge-Case-Hunter / Acceptance-Auditor) selected via `reviewer_model_kind` — augmenting, not replacing, the single-pass 3d-2 channel. The whole push/PR tail becomes **governed and configurable**: per-project `pushMode ∈ {auto, manual, approve}` + `autoCreatePullRequest` feed a **unified delivery gate** — a new non-terminal `WaitingForDelivery` state + `approve_delivery` action that performs push and/or PR per the two flags. Finally, after a backend push the system **reads the branch's CI build result** (GitHub Actions check-runs) and drives a **post-push investigation/fix loop** on a red build. The structural crux: to run build → lint → review *before* the code is pushed, `captureAndPush` is lifted out of the `onResult` EXECUTION arm and relocated to the **end** of the tail.

**Why this epic exists (net-new capability):** Today the delivery tail is rigid and ungated. Stages are exactly `{INVESTIGATION, EXECUTION, REVIEW}` (no build/lint), the advisory reviewer is the only quality signal, and `RepositoryWorkspaceService.captureAndPush()` **auto-pushes + auto-creates a PR** the instant the implementation result lands (`RunnerBroker.onResult` EXECUTION arm) — self-gated only on "workspace exists + has uncommitted changes," with **no** push-mode or create-PR flag. There is **zero** CI awareness (greenfield — no checks/Actions/Pipelines reader anywhere). This epic adds those capabilities as **new product scope** (FR75/FR76/FR77/FR78/FR79): cheap CPU quality gates before expensive LLM review, a governed and configurable push/PR tail, and post-push CI failure investigation. It is **not** an activation of deferred work.

This is a **delivery-tail-governance** epic, not a complex-ticket-flow or per-step-execution feature — it does not fit Epic 3d, 3e, or 3f. It is **inserted between Epic 3g and Epic 3i** purely for sequencing. Source: the 2026-06-29 sprint-change-proposal (epics 3g–3l).

**Prerequisites & reused substrates (all done):**
- **Command-only runner-execution + raw-output capture + per-step view** (story 3.6 raw-output, 3d-5 step log view) — the no-token `BUILD` and `LINT` stages are command-only (no-LLM, no-token) executions that run a configured shell command in the materialized workspace and capture raw output through the existing seams.
- **Advisory-reviewer channel** (3d-2 `RunnerStage.REVIEW`, `enqueueReviewerIfConfigured`, `step_reviews`, `review-result`, Verdict Panel, `reviewer_model_kind`; advisory-only / degrade-not-5xx) — the BMAD multi-layer review is a **new mode** of this same channel, selected via `reviewer_model_kind`, emitting a richer additive `review-result`.
- **Referenced-feedback loop machinery** (`priorFeedbackReferences` bundle + loop-counter + escalation-marker, from spec-rejection / 3e-2 incorporation / 3f split-repropose) — powers all three fix loops (build, lint, CI): the failure log is materialized as a redaction-policed referenced input (never inlined past the 2KB cap), with a distinct loop-counter + escalation safety valve per loop.
- **Per-project runtime config** (`Project` aggregate + `ProjectRuntimeConfigResolver`; `auto-dispatch` / `openspecEnabled` / `reviewerModelKind` precedents) — the new per-project flags (`buildCommand`/`build-stage.enabled`, `lintCommands`/`lint-stage.enabled`, `pushMode`, `autoCreatePullRequest`, BMAD reviewer mode) follow this precedent: seeded from `application.yml`, resolved per run, **default off** for parity.
- **Repository-host abstraction** (3-33 `RepositoryHostAdapter` + `RepositoryHostCapabilities`; `createPullRequest`, `createOrUpdatePullRequest`, the static `supportsRequiredStatusChecks` flag) — gains a CI-checks port method + a `supportsCiStatusReads` capability; the GitHub adapter implements the live check-runs read.
- **Workspace + git ownership** (`RepositoryWorkspaceService.captureAndPush()`, `GitCommandPort.push`, currently auto-fired from `RunnerBroker.onResult` EXECUTION arm) — the backend keeps git ownership; only the *trigger point* relocates to the end-of-tail delivery gate.
- **Foundation-gate drift harness** (`FlywaySchemaContractTest`, `WorkflowState`/`AllowedAction`/`RunnerStage` registries + CHECK + state-machine + API-schema drift tests, `runner-contracts` install trap) — every new stage / state / action / error code / contract field is drift-tested here.

**ADR (proposed):** `docs/adr/0030-governed-delivery-tail.md` — records (a) the **build → lint → review → deliver** ordering and why CPU gates precede LLM review; (b) the **push relocation** (`captureAndPush` lifted out of `onResult` EXECUTION arm to the end-of-tail delivery gate; backend retains git ownership); (c) the two new non-terminal gate states (`WaitingForLintApproval` hard gate, `WaitingForDelivery` unified push/PR gate) and their actions; (d) the three referenced-feedback fix loops (build / lint / CI) sharing the `priorFeedbackReferences` + loop-counter + escalation-marker pattern; (e) the BMAD review mode augmenting 3d-2; (f) the CI-checks port + `supportsCiStatusReads` capability and the GitHub-Actions-now / Bitbucket-deferred-to-3i scope line. Author alongside story 3h-1 (which introduces BUILD + the push relocation).

### Story List (6 stories)

```
Pre-review CPU gates
3h-1   Build-validation stage (RunnerStage.BUILD, no-token) + bounded auto-fix loop      [item 3]
3h-2   CPU linter gate (RunnerStage.LINT) + WaitingForLintApproval hard gate             [item 13]

Enriched review
3h-3   BMAD-style multi-layer review mode (augments 3d-2)                                [item 9]

Governed delivery tail
3h-4   Push-mode + unified delivery gate (WaitingForDelivery/approve_delivery) + PR flag [items 10+16]
3h-5   CI build-error investigation — GitHub Actions checks reader + fix loop            [item 6]

Front-end
3h-6   FE — delivery-tail surfaces (build status, lint panel, BMAD verdict, delivery bar, CI panel)
```

> Sequencing: **3h-1** introduces the `BUILD` stage **and the structural push relocation** (lifting `captureAndPush` out of the `onResult` EXECUTION arm) — it is the integration crux every later story builds on. **3h-2** inserts the `LINT` hard gate before REVIEW. **3h-3** enriches REVIEW with the BMAD mode. **3h-4** gates the now-end-of-tail push/PR behind the unified delivery gate. **3h-5** closes the loop after the push with CI investigation. **3h-6** is the FE twin (or fold each surface into its backend story, 3f-style). 3h-3 is largely independent of 3h-1/3h-2 (it only touches the REVIEW stage). Detailed, reconciled implementation stories live at `{implementation_artifacts}/3h-1..3h-6-...md`.

---

### Story 3h-1: Build-Validation Stage + Bounded Auto-Fix Loop

As an operator,
I want the produced code compiled/built in the workspace before it reaches review, and build failures auto-fixed first,
So that review and push only ever see buildable code — and a broken build is corrected by the implementation runner (bounded) rather than silently shipped.

**Acceptance Criteria:**

1. **Given** the `RunnerStage` enum (today `{INVESTIGATION, EXECUTION, REVIEW}`), **Then** a new `RunnerStage.BUILD("build")` is added as a **command-only** (no-LLM, no-token) stage and **every** `switch(stage)` consumer gains a BUILD arm; the un-CHECKed `runner_executions.stage` text column accepts `build` (additive, replay-safe, in `FlywaySchemaContractTest`). The stage runs the governed project's configured build command in the materialized workspace, reusing the 3.6 raw-output capture + the 3d-5 per-step log view (zero new persistence for the execution record).
2. **Given** per-project config, **Then** `Project` + `ProjectRuntimeConfigResolver` gain `buildCommand` (string) + `build-stage.enabled` (boolean, **default disabled**) seeded from `application.yml` (the `auto-dispatch` precedent); a project with no build config **skips** BUILD entirely (byte-identical to pre-3h parity).
3. **Given** tail ordering, **Then** when enabled, BUILD is dispatched **after** PR-output ingest and **before** REVIEW and before `captureAndPush` (the latter relocated to the delivery gate by 3h-4); on BUILD success the tail proceeds to the next gate.
4. **Given** a BUILD failure, **Then** a bounded **auto-fix loop** re-dispatches the EXECUTION/PR_OUTPUT runner with the build error log materialized as a redaction-policed `priorFeedbackReferences` input (never inlined past the 2KB cap); a `build_fix_loop_count` is tracked with distinct idempotency keys + an escalation marker, capped by `build-stage.max-fix-loops` (default 3).
5. **Given** the cap is exceeded, **Then** the run **fails** with a new build `FailureCategory` (three-sites: registry/enum + ProblemDetails/mapping + drift) plus the escalation marker, leaving it for Epic-4 recovery — the code is **never** silently pushed past an unresolved build failure.
6. **Given** the no-token guarantee, **Then** a BUILD execution records **zero** token/provider usage (command-only; ties to 3g provider-usage accounting) — asserted, so a misconfigured BUILD can never be billed as an LLM call.
7. **Given** redaction, **Then** the build command output and the feedback reference pass the same redaction/secret-fixture posture as any outbound artifact (ids/lengths only logged; nothing secret persisted in the feedback bundle).
8. **Given** tests, **Then** coverage asserts: BUILD stage registry/`switch`/stage-column drift; build pass proceeds to REVIEW; build fail loops + bumps `build_fix_loop_count` + honors cap → escalate with build `FailureCategory`; disabled-project parity (BUILD skipped, tail byte-identical); command-only emits no token usage; new `FailureCategory` three-sites drift; `application.*` ≥80% coverage.

### Story 3h-2: CPU Linter Gate + `WaitingForLintApproval` Hard Gate

As an operator,
I want CPU-only Java/TypeScript linters to run before the LLM review, and critical findings to halt for my approval or a fix,
So that I never spend review tokens on code that fails static analysis — and critical findings are a governed gate, not an advisory note.

**Acceptance Criteria:**

1. **Given** the `RunnerStage` enum, **Then** a new `RunnerStage.LINT("lint")` is added — **command-only / no-token**, running the governed project's **own** configured linters (Java + TypeScript) in the materialized workspace — and every `switch(stage)` consumer gains a LINT arm; `runner_executions.stage` accepts `lint` (additive, replay-safe, in `FlywaySchemaContractTest`). Reuses the 3.6 raw-output capture + 3d-5 step view.
2. **Given** per-project config, **Then** `Project` + `ProjectRuntimeConfigResolver` gain `lintCommands` + `lint-stage.enabled` (**default disabled**, parity) seeded from `application.yml`; a project with no lint config **skips** LINT (byte-identical to pre-3h).
3. **Given** tail ordering, **Then** when enabled, LINT runs **after** BUILD (3h-1) and **before** REVIEW; its output is parsed into a **severity-classified** result (critical = linter `error` severity vs. non-critical = `warning`/`info`).
4. **Given** a **critical** finding, **Then** the run transitions to a new **non-terminal** `WorkflowState WAITING_FOR_LINT_APPROVAL("waiting_for_lint_approval")` (registry + `current_state` CHECK widening via next-free Flyway head + state-machine entry + API-schema drift) **before any LLM REVIEW runs**; **non-critical** findings are attached as advisory and the tail proceeds to REVIEW without parking.
5. **Given** the gate, **Then** two new `AllowedAction`s are added (registry + placeholder + pin drift; foundation-gate, three-sites discipline): `APPROVE_LINT("approve_lint")` — dismisses the gate and proceeds to REVIEW — and `REQUEST_LINT_FIX("request_lint_fix")` — re-dispatches the EXECUTION runner with the lint findings as a redaction-policed `priorFeedbackReferences` input, loop-counted (`lint_fix_loop_count` + escalation marker), then re-runs LINT. Surfaced for the gate role only.
6. **Given** persistence + serving, **Then** the severity-classified findings are persisted (extend the `step_reviews` payload or a lint-findings store — chosen in the story to avoid a new table where the verdict store suffices) and served for the FE lint panel (3h-6); redaction posture honored (no secret in findings).
7. **Given** parity, **Then** a run whose project has LINT disabled, or whose lint produced only non-critical findings, **never** enters `WaitingForLintApproval` and proceeds exactly as pre-3h-2.
8. **Given** tests, **Then** coverage asserts: LINT stage + `WaitingForLintApproval` state + 2 actions drift (registry/CHECK/state-machine/API-schema/pin); critical finding parks **before** REVIEW; `approve_lint` proceeds; `request_lint_fix` loops + bumps count + re-runs LINT; non-critical stays advisory; disabled-project parity; `application.*` ≥80% coverage.

### Story 3h-3: BMAD-Style Multi-Layer Review Mode

As an operator,
I want a deeper adversarial review option I can select per run/project,
So that high-risk changes get Blind-Hunter / Edge-Case-Hunter / Acceptance-Auditor scrutiny instead of a single review pass — without losing the cheap single-pass mode.

**Acceptance Criteria:**

1. **Given** the 3d-2 reviewer channel, **Then** `reviewer_model_kind` (or an adjacent reviewer-mode flag on the same resolver path) gains a **`bmad`** mode; when selected, the REVIEW stage runs the **multi-layer adversarial review** (Blind-Hunter / Edge-Case-Hunter / Acceptance-Auditor layers) instead of a single pass — single-pass mode stays fully available (augment, **not** replace).
2. **Given** the verdict, **Then** BMAD mode emits a **richer** `review-result` (categorized findings + triage) via an **additive** field/version on the runner contract (the `runner-contracts` install trap applies — install/`-am`, never a stale `.m2` jar); single-pass mode's `review-result` is byte-identical to pre-3h-3.
3. **Given** the runner entrypoints, **Then** both `runner.mjs` review entrypoints (byte-identical) + both offline mocks support the bmad payload, the mocks emitting a deterministic multi-layer verdict; nothing in the bmad path bypasses the existing redaction posture.
4. **Given** the advisory-only discipline, **Then** the BMAD review is **advisory-only / degrade-not-5xx** (the 3d-2 invariant): a failed or malformed bmad review degrades to a recorded non-fatal verdict and the tail proceeds — it never strands a RUNNING execution and never returns a 5xx.
5. **Given** the harvester + store, **Then** `step_reviews` and the verdict harvester are widened to persist the richer multi-layer payload **without breaking** the single-pass payload (the harvester stays total — `onResult` has no uncaught path that strands RUNNING).
6. **Given** provenance, **Then** the proposing/reviewing model identity is recorded and the self-vs-producer flag (3d-2) is preserved for each layer; the FE verdict panel (3h-6) renders the categorized multi-layer findings.
7. **Given** OpenAPI/client drift, **Then** the additive `review-result` field/version regenerates OpenAPI + `schema.d.ts` (NOT byte-identical) via `npm run generate-api` (avoids the OpenAPI-regen FE-client drift cascade).
8. **Given** tests, **Then** coverage asserts: bmad mode emits the multi-layer verdict + harvests it; single-pass parity (unchanged verdict); degrade-not-5xx path; runner fence/payload over both entrypoints + mocks; OpenAPI/`schema.d.ts` regen drift; `application.*` ≥80% coverage.

### Story 3h-4: Push-Mode + Unified Delivery Gate + PR Flag

As an operator,
I want to choose per project whether push and PR/MR happen automatically or behind an explicit approval,
So that some projects push manually or let me review *before* the CI build runs — while existing projects keep their current auto-push + auto-PR behavior.

**Acceptance Criteria:**

1. **Given** per-project config, **Then** `Project` + `ProjectRuntimeConfigResolver` gain `pushMode ∈ {auto, manual, approve}` (**default `auto`**) + `autoCreatePullRequest` (**default `true`**) seeded from `application.yml` defaults (the `auto-dispatch` precedent); both are resolved per run.
2. **Given** the structural crux, **Then** `RepositoryWorkspaceService.captureAndPush()` is **relocated** out of the `RunnerBroker.onResult` EXECUTION arm to the **end of the tail** (after BUILD/LINT/REVIEW gates pass); the backend keeps git ownership (`GitCommandPort.push`, `RepositoryWorkspaceService`) — only the *trigger point* moves.
3. **Given** the delivery gate, **Then** a new **non-terminal** `WorkflowState WAITING_FOR_DELIVERY("waiting_for_delivery")` + a new `AllowedAction APPROVE_DELIVERY("approve_delivery")` (gate role) are added with drift at **all** foundation sites (registry + `current_state` CHECK via next-free Flyway head + state-machine + API-schema + placeholder + pin).
4. **Given** mode behavior, **Then**: **auto** → gates pass → `captureAndPush` runs automatically (PR created iff `autoCreatePullRequest`); **approve** → the run parks in `WaitingForDelivery` and `approve_delivery` runs `captureAndPush` (push + PR per `autoCreatePullRequest`); **manual** → the run parks in `WaitingForDelivery`, the system **never** calls `git.push`/`createPullRequest`, and `approve_delivery` records the out-of-band delivery and advances.
5. **Given** the PR flag, **Then** `createOrUpdatePullRequest` is gated by `autoCreatePullRequest` (deferred to `approve_delivery` when `false`); the existing repoRef-presence gate is preserved (no PR attempted when the run has no repository ref).
6. **Given** parity, **Then** a project left at `auto` + `autoCreatePullRequest=true` is **byte-identical** to pre-3h delivery (push + PR on result) — **except** the push now fires after the BUILD/LINT/REVIEW gates rather than at `onResult`.
7. **Given** idempotency, **Then** `approve_delivery` is keyed (run + delivery) so a replayed approve neither double-pushes nor double-creates the PR; the relocation preserves the "workspace exists + has uncommitted changes" self-gate.
8. **Given** tests, **Then** coverage asserts: each mode (auto push+PR / approve gate→delivery / manual no-git); PR flag off defers PR to `approve_delivery`; relocation parity (auto default byte-identical except trigger point); `WaitingForDelivery` + `approve_delivery` drift; idempotent `approve_delivery`; `application.*` ≥80% coverage.

### Story 3h-5: CI Build-Error Investigation (GitHub Actions)

As an operator,
I want the system to read the pushed branch's CI build result and investigate/fix failures,
So that a red CI run is triaged and fixed automatically (bounded) rather than waiting on me to notice it.

**Acceptance Criteria:**

1. **Given** the repository-host abstraction (3-33), **Then** `RepositoryHostAdapter` gains a CI-checks port method (e.g. `readCheckRuns(repoRef, ref) → CiStatus`) and `RepositoryHostCapabilities` gains `supportsCiStatusReads` (**default `false`**); the **GitHub** adapter implements it against the Actions / check-runs API, while GitLab/Bitbucket report `false` for now (Bitbucket lands in Epic 3i).
2. **Given** a backend push (auto or approve mode), **Then** the run polls CI status **bounded + best-effort** (swallows + logs transient read failures); a **failed** CI build drives an **investigation/fix loop** re-dispatching the EXECUTION runner with the CI failure log as a redaction-policed `priorFeedbackReferences` input, tracked by a `ci_fix_loop_count` + escalation marker + cap — mirroring 3h-1's loop shape but a **distinct** loop source/counter.
3. **Given** the static flag today, **Then** `supportsRequiredStatusChecks` becomes **meaningfully backed** by a live read wherever the new `supportsCiStatusReads` capability is `true`; CI status is surfaced on the run read model (run view) for the FE CI panel (3h-6).
4. **Given** a manual-push project, **Then** the run does **not** poll CI (the backend pushed nothing, so there is nothing of ours to read) — documented and asserted; a capability-`false` host likewise skips polling (parity).
5. **Given** redaction, **Then** CI logs pulled into the feedback reference pass the redaction/secret-fixture gate (ids/lengths only logged; nothing secret persisted).
6. **Given** the cap, **Then** an exhausted `ci_fix_loop_count` leaves the run escalated (escalation marker) for Epic-4 recovery — **no** cascade policy on repeated CI failure in this epic (documented forward option).
7. **Given** tests, **Then** coverage asserts: green CI proceeds; red CI loops + bounded by cap → escalate; capability-`false` / manual-push skips polling (parity); redaction over CI logs; CI status on the read model; new port method + `supportsCiStatusReads` capability drift; `application.*` ≥80% coverage.

### Story 3h-6: FE — Delivery-Tail Surfaces

As an authorized user,
I want the build status, lint gate, BMAD verdict, delivery gate, and CI investigation surfaced in the run view,
So that I can see each new quality gate's state and act on the two new Decision Bars without leaving the run.

**Acceptance Criteria:**

1. **Given** the run view, **Then** a **build-status surface** renders the `BUILD` stage outcome (pass/fail) + an auto-fix-loop indicator (`build_fix_loop_count` / escalation), reading the 3d-5 step-view + the 3h-1 read model.
2. **Given** the `WaitingForLintApproval` state, **Then** a **lint-gate panel** renders the severity-classified findings + a Decision Bar with `approve_lint` and `request_lint_fix` (gate-role-gated allowed-actions, sourced from the 3h-2 read surface); the panel is hidden when the run is not at the lint gate.
3. **Given** BMAD review mode, **Then** the **verdict panel** (3d-2) renders the multi-layer categorized findings + triage from the richer `review-result`, while single-pass verdicts render unchanged.
4. **Given** the `WaitingForDelivery` state, **Then** a **delivery Decision Bar** renders `approve_delivery` (gate role), reflecting the project's `pushMode` + `autoCreatePullRequest` (e.g. "approve to push + create PR" vs. "record manual delivery"); hidden when not at the delivery gate.
5. **Given** the CI surface, **Then** a **CI investigation panel** renders the pushed branch's CI status + the `ci_fix_loop_count` indicator from the 3h-5 read model; absent/`supportsCiStatusReads=false` renders a clear "no CI status" state, not an error.
6. **Given** client generation, **Then** `schema.d.ts` is **regenerated first** (`npm run generate-api`) so the new states/actions/fields are typed before the components are written (avoids the OpenAPI-regen FE-client drift cascade); helper functions are not exported from `.tsx` (react-refresh-no-fn-export trap).
7. **Given** accessibility, **Then** every new surface meets WCAG 2.1 AA and is **axe-clean**; the announcer reflects gate/state transitions (the `useLiveAnnouncement` one-commit-lag trap is honored — assert via `waitFor`).
8. **Given** tests, **Then** Vitest coverage asserts each surface renders for its state and is hidden otherwise, the two Decision Bars dispatch their actions, the BMAD verdict renders multi-layer findings, and **axe** is clean on each new surface (consolidate same-module router mocks into one file).

---

### Cross-Cutting Notes

- **Foundation-gate widening:** the two new `RunnerStage`s (`BUILD`, `LINT` — every `switch(stage)` consumer **and** the un-CHECKed `runner_executions.stage` text column), the two new `WorkflowState`s (`WaitingForLintApproval`, `WaitingForDelivery`, both non-terminal — registry + `current_state` CHECK widening at the next-free Flyway head + state-machine + API-schema drift), the three new `AllowedAction`s (`approve_lint`, `request_lint_fix`, `approve_delivery` — registry + placeholder + pin), the new build `FailureCategory` (three-sites), the new `RepositoryHostAdapter` CI-checks port method + `supportsCiStatusReads` capability, the additive `review-result` BMAD field/version (the `runner-contracts` install trap — install or `-am`, never a stale `.m2` jar), and the per-project `Project` config (`buildCommand`/`build-stage.enabled`, `lintCommands`/`lint-stage.enabled`, `pushMode`, `autoCreatePullRequest`, reviewer `bmad` mode) are drift-tested at the existing gates — folded into each story, no separate gate story.
- **The structural crux — push relocation:** `captureAndPush` is lifted out of the `RunnerBroker.onResult` EXECUTION arm and moved to the **end** of the tail (the 3h-4 delivery gate) so that BUILD → LINT → REVIEW all run on code that has **not** yet been pushed. The backend keeps git ownership (`GitCommandPort.push`, `RepositoryWorkspaceService`) — only the trigger point moves; the `auto` + `autoCreatePullRequest=true` default stays byte-identical except for *when* the push fires. This relocation is owned by 3h-1 (which first needs BUILD to precede the push) and completed by 3h-4.
- **Three referenced-feedback fix loops:** build (3h-1), lint (3h-2), and CI (3h-5) each reuse the `priorFeedbackReferences` bundle + a **distinct** loop-counter (`build_fix_loop_count` / `lint_fix_loop_count` / `ci_fix_loop_count`) + escalation marker, never inlining the failure log past the 2KB cap, and each re-dispatches the EXECUTION runner. Build and CI failures escalate (not loop forever) on cap; the lint loop re-runs LINT after each fix.
- **Documentation:** a `docs/governed-delivery-tail-walkthrough.md` (build → auto-fix → lint gate → approve/request-fix → BMAD review → delivery gate → push/PR → CI investigation); new vocabulary (`build stage`, `lint gate`, `WaitingForLintApproval`, `BMAD review`, `push mode`, `WaitingForDelivery`, `delivery gate`, `CI investigation`) confirmed in `docs/glossary.md` against NFR43 (minimize new concepts — justify each).
- **FRs covered:** **3h-1** delivers **FR75** (governed local build validation + bounded auto-fix); **3h-2** delivers **FR76** (CPU-only static-analysis gate halting for operator approval before LLM review); **3h-3** delivers **FR77** (BMAD-style multi-layer adversarial review mode); **3h-4** delivers **FR78** (per-project push-mode + PR/MR-creation governance + explicit delivery-approval gate); **3h-5** delivers **FR79** (post-push CI build-error investigation). This epic introduces **new PRD scope** (FR75–FR79) — it is not an activation of deferred work.
- **Forward options (out of scope):** PMD/SpotBugs-specific finding parsers; Bitbucket Pipelines CI (Epic 3i, behind the Bitbucket adapter); cascade policies on repeated CI failure (cancel/fail rather than escalate); GitLab CI status reads; per-finding (vs. whole-gate) lint actions in the panel.
