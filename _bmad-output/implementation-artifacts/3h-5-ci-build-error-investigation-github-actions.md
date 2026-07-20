# Story 3h.5: CI Build-Error Investigation — GitHub Actions Checks Reader + Bounded Fix Loop

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator,
I want the system to read the pushed branch's CI build result and investigate/fix failures,
so that a red CI run is triaged and fixed automatically (bounded) rather than waiting on me to notice it.

---

## ⛔ PREREQUISITE GATE — read before starting

**`3h-4-push-mode-and-unified-delivery-gate-and-pr-flag` MUST be `done` (merged).** It is `done` ✅ (commit `0d0ec3f`). This story consumes seams that only exist post-3h-4:

- `RepositoryWorkspaceService.captureAndPush()` → returns `RepositoryPushOutcome(commitSha, branchRef, prRef, committed, diff)` — **the CI reader's `ref` comes from here.** [`RepositoryWorkspaceService.java:444-445,952-953`]
- The **two and only two** backend-push call sites: `RunnerBroker.completeExecutionTailAndAdvance` (`:2619`, auto/inline) and `RunnerBroker.resumeDeliveryTailFromGate` (`:2409`, approve-mode / lint-approve resume).
- `PushMode.MANUAL` never reaches either site → **manual mode is skipped for free** (AC4) with zero extra code.
- The gate chain `build → lint → delivery → deliverInline` (`RunnerBroker.java:2245-2300`).

**3h-3 (BMAD review mode) is NOT a prerequisite** — it only touches the REVIEW stage. **However 3h-3 is `ready-for-dev` and will claim a Flyway version** (`step_reviews.review_findings jsonb`), and Epic-4 stories (4-7) also claim migrations. Highest on disk is **V38**. **Re-confirm the Flyway head at implementation time.** [flyway-v31-cross-branch-collision]

**3h-6 (FE) is the twin** — this story is **BACKEND-ONLY** plus the read-model field the FE CI panel consumes. No React components here.

**3i-3 (Bitbucket repository host) AC2 is gated on this story** — it needs the CI-checks port method to exist before it can implement a Bitbucket Pipelines reader.

[Source: sprint-status.yaml `3h-4 done`, `3h-3 ready-for-dev`; epic-03h §Sequencing; memory `story-3i-3-bitbucket-repository-host`]

---

## 🧭 ARCHITECTURE DECISIONS — read before coding (firm; they resolve gaps in the epic AC wording)

**Decision 1 — Async sweep-driven poll; the run proceeds to review (product-owner confirmed, Alex, 2026-07-10).**
The epic's AC2 ("the run polls CI status") was written assuming the original crux ordering — push *after* review. **3h-4 resolved the opposite way**: the delivery gate sits *before* the review gate, so the push (and therefore the CI build) now fires **before** the advisory reviewer runs. Resolution:

- Push fires → the run advances to `WaitingForReview` exactly as today → the REVIEW runner is enqueued and runs **concurrently** with the CI build. Reviewer latency is unchanged.
- A `@Scheduled`, `@ConditionalOnProperty`-gated **sweep** polls check-runs for runs stamped `ci_status='pending'`.
- **Green** → record `ci_status='success'`, done.
- **Red** → transition `WaitingForReview → Executing` + `WorkflowOrchestrationService.retryImplementation(...)`, bumping `ci_fix_loop_count`.
- **Cap exhausted** → flip the shared escalation marker once, leave the run parked at `WaitingForReview`.

This yields **zero new `WorkflowState`, zero new `AllowedAction`, zero new transition edges, zero new `FailureCategory`**. The `WAITING_FOR_REVIEW → EXECUTING` edge **already exists** (`WorkflowTransitionTable.java:101-108`) and the exact re-dispatch shape is already in production: `TechnicalApprovalService.rejectImplementation` (`:482-514`) transitions `WaitingForReview → Executing` then calls `retryImplementation`. **Clone that method's shape.**

The rejected alternative (hold the tail at `Executing` until CI resolves) would wedge the run in `Executing` for the CI duration, need a new `resumeTailAfterCi` broker seam, and force `approve_delivery` — which transitions synchronously to `WaitingForReview` inside the command tx (`DeliveryApprovalService.java:133-139`) — to reroute through the CI gate. Documented forward option.

> **CAS requirement (load-bearing).** Because REVIEW and a human `accept_implementation` run concurrently with the poll, the red-CI branch **must** re-read `current_state` under a per-run advisory lock and **skip unless it is still `WAITING_FOR_REVIEW`**. `Completed` is terminal (`WorkflowTransitionTable.java:159` — zero targets); a re-dispatch attempt there throws `ILLEGAL_TRANSITION`.

[Source: AskUserQuestion 2026-07-10; epic-03h Story 3h-5 AC2 vs docs/adr/0030 "Amendment — story 3h-4"; WorkflowTransitionTable.java:101-108,159; TechnicalApprovalService.java:482-514]

**Decision 2 — CI status is informational; it NEVER blocks `accept_implementation` (product-owner confirmed, Alex, 2026-07-10).**
CI status is surfaced on the run read model (AC3) and the 3h-6 CI panel. `TechnicalApprovalService.acceptImplementation` is **untouched**. This matches epic AC6's explicit *"no cascade policy on repeated CI failure in this epic"* and keeps the story free of a new `DomainErrorCode`, a precondition guard, and the wedge risk of a run whose CI never reports. An operator accepting a red-CI run is making an informed choice. Hard-gating remains a documented forward option.
[Source: AskUserQuestion 2026-07-10; epic-03h Story 3h-5 AC6]

**Decision 3 — `RunnerStage.CI("ci")` is the carrier for the CI failure log; it needs NO Flyway migration.**
Epic AC2 requires the CI failure log to become a *"redaction-policed `priorFeedbackReferences` input"*. The **only** mechanism for that in this codebase is a **FAILED `runner_executions` row referenced by public id** — `ContextBundleService.collectExecutionFeedbackReferences` (`:1307-1362`) walks `findByWorkflowRunPublicIdAndStatusIn(runId, [FAILED])` and emits `PriorFeedbackReference(execution.publicId(), kind)` for `stage == BUILD` / `stage == LINT`. So CI needs a stage.

`RunnerStage` is a **CODE-ONLY** enum: `runner_executions.stage` is an **un-CHECKed text column**, so a new stage is **NOT** a Flyway change, **NOT** a `RegistryContractTest` entry, and **NOT** a `FlywaySchemaContractTest` entry (this is stated verbatim in `RunnerStage.java:8-40`). The cost is the exhaustive `switch (stage)` fan-out — **no silent `default`**.

The epic's Cross-Cutting Notes name only BUILD and LINT as new stages; this is a **deliberate, documented divergence** required by AC2. It buys, for free: story-3.6 raw-output capture + redaction + secret-scan, the 3d-5 per-step log view, and the reference-by-id feedback bundle. Like BUILD/LINT, CI is **backend-side** (an HTTP read) — it is **never** runner-kind dispatched.
[Source: RunnerStage.java:8-40; ContextBundleService.java:1307-1362; epic-03h Cross-Cutting Notes; docs/adr/0030 "Amendment (story 3h-1)" — backend-side stage precedent]

**Decision 4 — Parity is the GLOBAL `@ConditionalOnProperty` sweep switch; there is NO per-project CI flag.**
Unlike BUILD/LINT/delivery, the epic's prerequisite list of new per-project flags is explicit and **does not include CI**: *"(`buildCommand`/`build-stage.enabled`, `lintCommands`/`lint-stage.enabled`, `pushMode`, `autoCreatePullRequest`, BMAD reviewer mode)"*. Parity therefore comes from the **sweep configuration** itself, exactly as `SplitRollupSweepConfiguration` (3f-8) and `IntegrationConflictDetectionConfiguration` (4-17) do: when `deliveryline.workflow.ci-investigation.enabled` is `false` or absent, **the entire `@Configuration` — and therefore the scheduled bean — is never registered**, so a disabled sweep adds zero scheduled work and the tail is byte-identical to pre-3h-5.

This is a large, deliberate scope reduction: **NO `projects` column, NO Flyway on `projects`, NO 21st `Project` component, NO `ProjectRuntimeConfigResolver` accessor, NO Create/Update DTO + command + mapper + `ProjectResponse` + `createFingerprint` + `DefaultProjectSeeder` fan-out, NO project-field OpenAPI churn.** Do not add one.

Three further runtime gates, all AC4: (a) the repo host reports `supportsCiStatusReads=false` → never stamped, never polled; (b) `pushMode=manual` → the backend pushed nothing, so `captureAndPush` never ran, so nothing is ever stamped; (c) the run has left `WaitingForReview` → status recorded, no re-dispatch.
[Source: epic-03h "Prerequisites & reused substrates" per-project flag list; SplitRollupSweepConfiguration.java:19-30; IntegrationConflictDetectionConfiguration.java:28]

**Decision 5 — cap exhaustion flips the escalation marker and NEVER fails the run. No new `FailureCategory`.**
Two independent reasons, both binding:
1. Epic AC6 is explicit: *"an exhausted `ci_fix_loop_count` leaves the run **escalated** (escalation marker) for Epic-4 recovery — **no** cascade policy on repeated CI failure in this epic."*
2. Structurally impossible otherwise: `WAITING_FOR_REVIEW` has **no `→ FAILED` edge** (`WorkflowTransitionTable.java:101-108`), and `assertTransitionAllowed` only admits a `FailureCategory` on `EXECUTING|INVESTIGATING → FAILED` (`:211-234`).

So CI follows **lint semantics** (`LintApprovalService.requestLintFix:188` — `markEscalationOnce` for visibility, never fails), **not** build semantics (`BuildStageService.failRun:367-382` — `RUNNER_BUILD_FAILED` + transition to `FAILED`). Do **not** add `RUNNER_CI_FAILED`. Reuse the shared `workflow_runs.escalation_marker_set` column (V7) — every fix loop shares it; only the per-stage counter is new.
[Source: epic-03h Story 3h-5 AC6; WorkflowTransitionTable.java:101-108,211-234; V33 header comment lines 19-21; LintApprovalService.java:187-198]

**Decision 6 — CI columns are written by raw SQL `UPDATE`, NEVER through `WorkflowRunEntity`.**
`WorkflowRunEntity` has **no `@DynamicUpdate`** (`WorkflowRunEntity.java:15-17`) and carries a `version` optimistic-lock column (`V1_1`). A `REQUIRES_NEW` side-effect that saves a stale managed entity issues a **full-row UPDATE** and clobbers concurrent writes — the exact 3g token-usage bug ([token-usage-clobbered-by-terminal-transition]; `AfterCommitSideEffectRunner.java:44-49` documents the rule). Write every `ci_*` column through a targeted `update workflow_runs set … where public_id = :publicId` in `WorkflowRunPersistenceAdapter`, cloning `INCREMENT_BUILD_FIX_LOOP_COUNT_SQL` (`:62-68`). This bypasses both the entity and the version bump. The `transition()` call still goes through `WorkflowTransitionService` normally.
[Source: WorkflowRunPersistenceAdapter.java:62-68,289-309; RunnerExecutionEntity.java:24-33; memory token-usage-clobbered-by-terminal-transition]

**Decision 7 — the CI failure body is the check-run `output` + failure annotations, NOT Actions job-log archives.**
`GET /repos/{owner}/{repo}/commits/{ref}/check-runs` returns each run's `output.{title, summary, text, annotations_count, annotations_url}`. Compose the failure text from those plus `GET /repos/{owner}/{repo}/check-runs/{id}/annotations` filtered to `annotation_level == "failure"`. Do **not** fetch `/actions/jobs/{id}/logs` — it 302-redirects to a binary zip, needs the `actions:read` scope, and cannot pass through the `RestClient` + redaction path.
[Source: GitHub REST v3 checks API, verified 2026-07-10 — see §Latest Technical Information]

---

## Context — why this story exists (read before coding)

There is **zero** CI awareness anywhere in the backend today — greenfield. `RepositoryHostCapabilities.supportsRequiredStatusChecks` exists but is a **statically declared flag with no production reader** (its only reference outside the record is an assertion in `RepositoryHostCapabilitiesTest:53`). Once 3h-4 landed, the backend pushes a branch and creates a PR, then walks away: nobody reads whether the branch's CI build went red.

This story adds the **CI-checks port method + `supportsCiStatusReads` capability**, a **bounded best-effort polling sweep**, and a **third referenced-feedback fix loop** (`ci_fix_loop_count`) that re-dispatches the EXECUTION runner with the CI failure log — mirroring 3h-1's build loop and 3h-2's lint loop but with a **distinct** loop source and counter. It delivers **FR79** and unblocks 3i-3 (Bitbucket Pipelines) and 3h-6 (the FE CI panel).

[Source: epic-03h Story 3h-5 + Cross-Cutting Notes; docs/adr/0030 Decision 7; PRD FR79]

---

## Acceptance Criteria

1. **CI-checks port method + `supportsCiStatusReads` capability.** `RepositoryHostAdapter` gains `CiStatus readCheckRuns(RepositoryRef repo, String ref)`. `RepositoryHostCapabilities` gains a **6th record component** `supportsCiStatusReads` (`githubDefaults()` → `true`; GitLab stub → `false`). `GitHubRealAdapter` implements the live GitHub Actions / check-runs read; `GitHubMockAdapter` returns a deterministic fixture verdict (mock↔real capability parity is asserted); `GitLabRepositoryHostStubAdapter` reports `false` and throws its typed not-implemented `RepositoryHostAdapterException(SYNC_FAILURE, …)`. Bitbucket lands in Epic 3i. `CiStatus`/`CiCheck`/`CiConclusion` are **vendor-neutral domain records** in `domain/integration/repohost/` — no host types leak through the port (`REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT`).

2. **Backend push stamps a pending CI poll; a red build drives a bounded investigation/fix loop.** After a successful backend push (auto **or** approve mode — the two `captureAndPush` call sites), the run is stamped `ci_status='pending'`, `ci_head_sha=pushOutcome.commitSha()`, `ci_poll_attempts=0` — **only** when the resolved repo host reports `supportsCiStatusReads=true`. A `@Scheduled` sweep polls those runs **bounded + best-effort** (transient `RepositoryHostAdapterException` is swallowed + WARN-logged, `ci_poll_attempts` bumped, retried next tick; exceeding `max-poll-attempts` records `ci_status='unavailable'` and stops). A **failed** CI build re-dispatches the EXECUTION runner with the CI failure log materialized as a redaction-policed `priorFeedbackReferences` input (kind `ci.failure`, **never inlined** past the 256 KB reference-by-id cap), tracked by `ci_fix_loop_count` + the shared escalation marker + a cap — mirroring 3h-1's loop shape with a **distinct** loop source, counter, and idempotency key (`ci-fix:<runId>:<count>`).

3. **`supportsRequiredStatusChecks` becomes meaningfully backed; CI status is on the run read model.** The sweep is the **first production reader** of `supportsRequiredStatusChecks`, recording it alongside the live verdict. `ciStatus`, `ciHeadSha`, `ciFixLoopCount`, and `ciChecksEnforced` are appended as **trailing nullable fields on `WorkflowDetailResponse` only** — `WorkflowSummaryResponse` is **NOT** touched (its exact-field contract block would break).

4. **Manual-push and capability-`false` skip polling (parity).** A `pushMode=manual` project never reaches `captureAndPush`, so it is never stamped and never polled — the backend pushed nothing, so there is nothing of ours to read. A host reporting `supportsCiStatusReads=false` is likewise never stamped. With `deliveryline.workflow.ci-investigation.enabled` absent/false the sweep bean is **never registered** and the tail is byte-identical to pre-3h-5. All three asserted.

5. **Redaction.** CI check-run output and annotations pulled into the feedback reference pass the same redaction + secret-fixture posture as any other captured raw output (routed through `RunnerLogCaptureService.captureLogs` → `recordRawOutput`); ids/lengths only in logs; nothing secret persisted in the feedback bundle.

6. **Cap → escalation, never failure.** An exhausted `ci_fix_loop_count` flips the shared `escalation_marker_set` **once** and leaves the run parked at `WaitingForReview` for Epic-4 recovery. **No** transition to `Failed`, **no** new `FailureCategory`, **no** cascade policy (documented forward option). A red CI on a run that has already left `WaitingForReview` (Completed / TakenOver / Failed / already Executing) records the status and skips the re-dispatch with a WARN.

7. **Tests.** Coverage asserts: green CI proceeds (no re-dispatch); red CI loops + bumps the counter + honors the cap → escalation marker, state unchanged; capability-`false` / manual-push / sweep-disabled skip polling (parity); redaction over CI output; `ciStatus` on the detail read model (+ null case); the new port method + `supportsCiStatusReads` capability drift (mock↔real parity + foundation contract); `RunnerStage.CI` exhaustive-`switch` fan-out; transient read failure is swallowed and retried; a red CI on a non-`WaitingForReview` run never re-dispatches. `application.*` ≥ 80% line coverage. ArchUnit via **Failsafe**.

---

## Tasks / Subtasks

- [x] **Task 1 — `RunnerStage.CI` + the exhaustive `switch (stage)` fan-out** (AC: #2, Decision 3)
  - [x] `domain/registry/RunnerStage.java`: add `CI("ci")` after `LINT` (`:32`), mirroring the BUILD/LINT javadoc block that states **code-only, no Flyway, no `RegistryContractTest`/`FlywaySchemaContractTest` entry**.
  - [x] Add an explicit arm to **every** exhaustive `switch (stage)` — the compiler finds them; the three known production sites are:
    - `application/runner/RunnerProperties.java:194-210` `kindForStage` → **fail loud** (`throw new IllegalStateException`), copying the BUILD arm verbatim: CI is a backend-side HTTP read, never runner-kind dispatched. Reaching here is a routing bug.
    - `application/runner/RunnerBroker.java:3072` `allowedArtifactTypesForStage` → `EnumSet.noneOf(ArtifactType.class)` (CI emits no artifact; belt-and-braces, CI never flows through `onResult`).
    - `application/project/ProjectRunnerSteps.java:37` `of(stage, subStage)` → `Optional.empty()` (no per-project runner step).
  - [x] `grep -rn "switch (stage)\|switch (row.stage())\|switch (execution.stage())"` across `src/main` **and** `src/test` and add every remaining arm. **Sanity-grep `DomainRegistry`/`RegistryContractTest` for a `runnerStages` leg — per `RunnerStage.java:36` there is none; do NOT invent one.**

- [x] **Task 2 — vendor-neutral `CiStatus` domain records + the `readCheckRuns` port method** (AC: #1)
  - [x] New `domain/integration/repohost/CiConclusion.java` — enum `PENDING`, `SUCCESS`, `FAILURE`, `NEUTRAL`, `UNAVAILABLE` (implement `RegistryValue` **only if** a registry leg is genuinely required; it is not — this is a projection type, not a foundation registry).
  - [x] New `domain/integration/repohost/CiCheck.java` — `record CiCheck(String name, String conclusion, String detailsUrl, String summary, String failureText)`.
  - [x] New `domain/integration/repohost/CiStatus.java` — `record CiStatus(CiConclusion conclusion, String headSha, List<CiCheck> checks)` + static factories `pending(headSha)`, `unavailable(headSha)`. Non-null guards mirroring `Branch.java:16-23`.
  - [x] `application/integration/repohost/RepositoryHostAdapter.java`: add `CiStatus readCheckRuns(RepositoryRef repo, String ref);` with javadoc stating it is **capability-gated** (`supportsCiStatusReads`) and that `ref` is a commit SHA. Port stays in `application` (`REPOSITORY_HOST_ADAPTER_PORT_RESIDES_IN_APPLICATION`, `ArchitectureRuleCatalog.java:910-920`).
  - [x] `domain/integration/repohost/RepositoryHostCapabilities.java`: add **6th component** `boolean supportsCiStatusReads` (`:22-27`). Update `githubDefaults()` (`:33-35`) → `new RepositoryHostCapabilities(true, true, true, true, true, true)`. **Positional record — every `new RepositoryHostCapabilities(...)` site breaks.** Known sites: `GitLabRepositoryHostStubAdapter.java:96` (→ 6× `false`), `ProjectConnectivityServiceTest` stubs, `ProjectConnectorResolverTest:179-181`, `GitLabStubAdaptersTest:66-67`.

- [x] **Task 3 — `GitHubRealAdapter.readCheckRuns` + mock twin + GitLab stub** (AC: #1, #5, Decision 7)
  - [x] `GitHubRealAdapter`: implement `readCheckRuns` mirroring `getRepositoryByRef`'s HTTP shape — `parseRepoRef` (`REPO_REF_PATTERN`, `:98-99`), then `getOrEmptyOnNotFound(uri, "readCheckRuns", GITHUB_REPO_NOT_FOUND)` (`:420-432`) against `GET /repos/{owner}/{repo}/commits/{ref}/check-runs?filter=latest&per_page=100`. Reuse `inspectRateLimit` (`:494-525`) + the `classify` ladder (`:531-624`) verbatim — **do not add a retry**; the adapter never retries, the sweep does.
  - [x] **Conclusion mapping** (put it in the adapter, not the sweep — the sweep must never see GitHub vocabulary):
    - `total_count == 0` → `NEUTRAL` (no CI configured on this ref — stop polling, never loop).
    - any check `status != "completed"` (`queued`/`in_progress`/`waiting`/`requested`/`pending`) → `PENDING`.
    - any completed `conclusion ∈ {failure, timed_out, action_required}` → `FAILURE`.
    - `conclusion ∈ {cancelled, stale}` (and nothing failed) → `NEUTRAL`.
    - all completed and `conclusion ∈ {success, neutral, skipped}` → `SUCCESS`.
  - [x] For each **failed** check run, fetch `GET /repos/{owner}/{repo}/check-runs/{id}/annotations` and keep only `annotation_level == "failure"`; compose `CiCheck.failureText` from `output.title` + `output.summary` + `output.text` + the failure annotations (`path:start_line — message`). **Bound it** (e.g. first 50 annotations, ≤ 64 KB) and **log the byte count, never the bytes**. Note the API's 1000-check-suite ceiling and `per_page` max of 100 in a comment.
  - [x] `GitHubMockAdapter`: deterministic verdict driven by `GitHubMockScenarioRegistry` (green by default; a `ci-red` scenario returning a `FAILURE` with one fixture annotation). Capabilities must stay **equal to `githubDefaults()`** — `RepositoryHostCapabilitiesTest` and `GitHubMockVsRealParityFoundationContract` assert mock == real.
  - [x] `GitLabRepositoryHostStubAdapter`: `supportsCiStatusReads=false`; `readCheckRuns` throws `RepositoryHostAdapterException(IntegrationFailureCategory.SYNC_FAILURE, …)` following `:110-116`. Callers gate on the capability first, so this is never reached in practice.

- [x] **Task 4 — Flyway (next-free head, re-confirm) + persistence port/adapter** (AC: #2, #3, Decision 6)
  - [x] **Re-confirm the Flyway head.** Highest on disk is **V38**; 3h-3 (`ready-for-dev`) and Epic-4 stories will claim versions. [flyway-v31-cross-branch-collision]
  - [x] `V<next>__add_ci_investigation_columns.sql` (mirror the V33 header comment style):
    ```sql
    alter table workflow_runs add column ci_status text null;
    alter table workflow_runs add column ci_head_sha text null;
    alter table workflow_runs add column ci_last_polled_at timestamptz null;
    alter table workflow_runs add column ci_poll_attempts integer not null default 0;
    alter table workflow_runs add column ci_fix_loop_count integer not null default 0;

    alter table workflow_runs add constraint ck_workflow_runs_ci_status
        check (ci_status is null or ci_status in
               ('pending','success','failure','neutral','unavailable'));
    alter table workflow_runs add constraint ck_workflow_runs_ci_fix_loop_count_nonneg
        check (ci_fix_loop_count >= 0);
    alter table workflow_runs add constraint ck_workflow_runs_ci_poll_attempts_nonneg
        check (ci_poll_attempts >= 0);

    -- Partial index: the sweep only ever scans pending rows.
    create index ix_workflow_runs_ci_pending on workflow_runs (id) where ci_status = 'pending';
    ```
    `ci_status` gets a **CHECK** (closed, self-owned set — the 3h-4 `push_mode` Decision-2 precedent), unlike free-text `reviewer_model_kind`. **Reuses `escalation_marker_set` (V7) — do NOT add a marker column.**
  - [x] `application/workflow/spi/WorkflowRunRejectionLoopPort.java`: add `int incrementAndReadCiFixLoopCount(String workflowRunPublicId);` as the **sixth** twin (after `incrementAndReadLintFixLoopCount` `:88`).
  - [x] `adapters/persistence/WorkflowRunPersistenceAdapter.java`: add `INCREMENT_CI_FIX_LOOP_COUNT_SQL` + method, cloning `INCREMENT_BUILD_FIX_LOOP_COUNT_SQL` (`:62-68`) / `incrementAndReadBuildFixLoopCount` (`:289-309`) — `update … returning ci_fix_loop_count`, `DomainException(RUN_NOT_FOUND)` on no row.
  - [x] New CI state writes on a port (e.g. `CiStatusWritePort`, or extend `WorkflowRunReadPort`/a new `CiStatusPort`) — **all raw SQL, never `WorkflowRunEntity`** (Decision 6):
    - `markCiPollPending(runId, headSha)` → `set ci_status='pending', ci_head_sha=:sha, ci_poll_attempts=0, ci_last_polled_at=null`.
    - `recordCiPollAttempt(runId)` → `set ci_poll_attempts = ci_poll_attempts + 1, ci_last_polled_at = now() returning ci_poll_attempts`.
    - `recordCiStatus(runId, status)` → `set ci_status=:status, ci_last_polled_at=now()`.
    - `findRunsAwaitingCiStatus(afterSeq, batchLimit)` → keyset-paginated on the raw monotonic `workflow_runs.id` (**not** a bare `LIMIT` — no tail starvation), `where ci_status='pending' and archived_at is null and id > :afterSeq order by id asc limit :batchLimit`. Clone `SCAN_ACTIVE_LINKS_SQL` (`IntegrationConflictPersistenceAdapter.java:66-84`).
    - `acquireCiSweepLock()` → `select pg_advisory_xact_lock(:lockKey)` with a **new** key `0x43495354` (`"CIST"`); **not `@Transactional`** so it joins the caller's tx (mirror `IntegrationConflictPersistenceAdapter.java:175-185`). Per-run lock: `select pg_advisory_xact_lock(:classifier, hashtext(:runId))` with classifier `0x4349` (`"CI"`).
  - [x] `contract/FlywaySchemaContractTest.java`: assert the five new columns, the three constraints, and the partial index.

- [x] **Task 5 — stamp `ci_status='pending'` at the two backend-push sites** (AC: #2, #4)
  - [x] `RunnerBroker.completeExecutionTailAndAdvance` (after `captureAndPush` at `:2619`) and `RunnerBroker.resumeDeliveryTailFromGate` (after `:2409`): when `pushOutcome.isPresent() && pushOutcome.get().committed()`, resolve the run's repo host via `ProjectConnectorResolver.resolveRepositoryHost(project)` and, **only if** `getCapabilities().supportsCiStatusReads()`, call `ciStatusPort.markCiPollPending(runId, pushOutcome.get().commitSha())`.
  - [x] Guard the capability read with the **defensive** pattern (`WorkflowOrchestrationService.java:1474-1492`): try/catch around `getCapabilities()`, null-check the result, WARN + skip — never let a capability probe strand the delivery tail.
  - [x] Inject via **optional setter injection** (`ObjectProvider`, mirroring `deliveryGateServiceSupplier` `RunnerBroker.java:143-144,518-524`) — **no `RunnerBroker` constructor fan-out** (it would break `RunnerBrokerUnitTest` and the Docker slice tests).
  - [x] **`pushMode=manual` needs zero code**: `DeliveryApprovalService`'s manual branch calls `recordManualDeliveryAndEnqueueReviewer` and never `captureAndPush`, so it never reaches either stamp site (AC4). Assert this, don't code it.

- [x] **Task 6 — `CiStatusPollingService` (the bounded best-effort sweep)** (AC: #2, #4, #6, Decision 1)
  - [x] New `application/workflow/ci/CiStatusPollingService.java` (`@Service`, **framework-trigger-free** — no Spring scheduling annotations in `application.*`). Clone the two-phase, no-I/O-under-lock discipline of `IntegrationConflictDetectionService` (`:157-205`):
    - **Phase 1** (one short `TransactionTemplate` tx): `acquireCiSweepLock()` → `findRunsAwaitingCiStatus(cursor, batchLimit)`. **No external I/O inside the lock.** Process-local `AtomicLong` keyset cursor (single-threaded scheduler + advisory lock serialize it); wrap to 0 when a tick returns fewer than `batchLimit`. Fetch `batchLimit + 1` to distinguish full-vs-truncated and **WARN on batch-limit-hit** — no silent truncation.
    - **Phase 2** (lock-free, per run): `readCheckRuns(repoRef, ciHeadSha)`. `repoRef` from `ProjectRuntimeConfigResolver.resolveRepositoryRef(runId)` (`:99-102`).
  - [x] Per-run outcome handling, each inside `afterCommit.runInNewTransaction("ci-poll", runId, …)` (**Layer B alone** — ADR 0032's consume-note binds this loop; the helper never transitions, the caller's `Runnable` does):
    - **`RepositoryHostAdapterException` / any `RuntimeException`** → swallow + WARN (`reason=ci_read_failed`), `recordCiPollAttempt(runId)`, leave `ci_status='pending'`, retry next tick. **One bad run never aborts the sweep.**
    - **`PENDING`** → `attempts = recordCiPollAttempt(runId)`; if `attempts > maxPollAttempts` → `recordCiStatus(runId, "unavailable")` + WARN, stop polling (bounded).
    - **`SUCCESS`** → `recordCiStatus(runId, "success")`. Done — "green CI proceeds" (the run is already at/past review).
    - **`NEUTRAL`** → `recordCiStatus(runId, "neutral")`. Done (no CI configured / cancelled — never loop).
    - **`FAILURE`** → `handleCiFailure(runId, ciStatus, correlationId)` (Task 7).
  - [x] `CiSweepResult` record (`scanned, green, red, pending, unavailable, readFailures, batchLimitHit`) mirroring `SweepResult` (`IntegrationConflictDetectionService.java:591-602`).
  - [x] New `infrastructure/config/CiInvestigationConfiguration.java` — clone `SplitRollupSweepConfiguration.java` **verbatim**: `@Configuration @EnableScheduling @ConditionalOnProperty(name = "deliveryline.workflow.ci-investigation.enabled")` + `@Scheduled(fixedDelayString = "${deliveryline.workflow.ci-investigation.interval-ms:30000}")`. **The `@ConditionalOnProperty` is the load-bearing parity gate** (Decision 4). This is the only place a Spring scheduling annotation may live.
  - [x] Config (keep **OPTIONAL + UNVALIDATED** so no `src/test/resources/application.yml` mirror is needed — [validated-config-needs-test-yaml]): a standalone `CiInvestigationProperties` record `@ConfigurationProperties("deliveryline.workflow.ci-investigation")` (`enabled`, `intervalMs`, `batchLimit:20`, `maxPollAttempts:60`). **Do NOT add a nested record to `RunnerProperties`** — a new component there fans out to **17** positional constructor sites. [runnerproperties-record-component-fanout]
  - [x] `application.yml`: commented-out `ci-investigation:` block under the existing `deliveryline.workflow` block (next to `build-fix-max-loops` `:142` / `lint-fix-max-loops` `:150`).

- [x] **Task 7 — the bounded CI fix loop (`handleCiFailure`)** (AC: #2, #5, #6, Decisions 1/5)
  - [x] Take the **per-run advisory lock** (`0x4349` + `hashtext(runId)`) **first**, then **re-read `current_state`** (the CAS of Decision 1):
    - not `WAITING_FOR_REVIEW` → `recordCiStatus(runId, "failure")` + WARN (`reason=ci_red_but_run_not_reviewable currentState={}`) + **return**. No transition, no re-dispatch. Covers `Completed` (terminal), `TakenOver`, `Failed`, and a run already looping in `Executing`.
  - [x] Materialize the failure log as a **CI `runner_executions` row** (Decision 3), mirroring `BuildStageService.runBuild:158-166,224-235`:
    - `String ciRex = PublicIdPrefixes.RUNNER_EXECUTION.next();`
    - `int v = recordPort.nextContextBundleVersion(runId, RunnerStage.CI);`
    - `recordPort.insertPending(ciRex, runId, RunnerStage.CI, v, new ExecutionConstraints(Duration.ofMinutes(1), true));`
    - `CapturedLogs captured = runnerLogCaptureService.captureLogs(ciRex, runId, composedFailureText, "");` — `RunnerLogCaptureService.captureLogs(...)` (`:70`) returns `CapturedLogs` (`application/runner/CapturedLogs.java:11`); it runs the redaction + secret-scan + store path (AC5).
    - `executionService.recordRawOutput(ciRex, captured);`
    - `executionService.recordFailed(ciRex, FailureCategory.RUNNER_NON_ZERO_EXIT);` ← the `FAILED` status is what makes `ContextBundleService` pick it up. **Reuse `RUNNER_NON_ZERO_EXIT` exactly as LINT does (`LintStageService.java:273-274`) — no new category (Decision 5).**
  - [x] `int loop = rejectionLoopPort.incrementAndReadCiFixLoopCount(runId);` and `int cap = ciFixEscalationThresholdProvider.get();`
    - **`loop <= cap`** → `workflowTransitionService.transition(runId, WorkflowState.EXECUTING, new TransitionActor("system", ActorType.SYSTEM), "ci build failed — bounded investigation loop", "ci-fix:" + runId + ":" + loop, details)` **then** `workflowOrchestrationService.retryImplementation(runId, correlationId)`. Pure re-dispatch **after** the transition (Trap T1 — never a second transition). Clone `TechnicalApprovalService.rejectImplementation:482-514`. Also `recordCiStatus(runId, "failure")` — the next push re-stamps `pending`.
    - **`loop > cap`** → `boolean flipped = rejectionLoopPort.markEscalationOnce(runId) == 1;` (flips at most once; the shared V7 column) + `recordCiStatus(runId, "failure")` + WARN. **No transition. No re-dispatch.** Run stays at `WaitingForReview` (Decision 5). Emit the existing `ESCALATION_REQUIRED` event on the `flipped` edge only, mirroring `TechnicalApprovalService.java:455-478` (including the concurrent-flip race branch). **No new `WorkflowEventType`.**
  - [x] New `application/workflow/CiFixEscalationThresholdProvider.java` — copy `BuildFixEscalationThresholdProvider` verbatim: `@Value("${deliveryline.workflow.ci-fix-max-loops:3}")`, clamp `< 1` to `3`. Add `ci-fix-max-loops: 3` to `application.yml` beside `:142`/`:150`.
  - [x] **`retryImplementation` is a no-op when `deliveryline.runner.implementation-stage.auto-dispatch=false`** (`RunnerProperties.java:254`). The IT must set it `true` — this is exactly why `BuildFixLoopRedispatchIT:65-69` does.

- [x] **Task 8 — `ci.failure` referenced feedback** (AC: #2, #5)
  - [x] `application/runner/ContextBundleService.java:1330-1352`: add the third arm to the existing loop —
    `else if (execution.stage() == RunnerStage.CI) { references.add(new PriorFeedbackReference(execution.publicId(), "ci.failure")); }`
  - [x] The body is **never inlined**: the referenced CI execution's already-captured, redacted raw output *is* the body. The 256 KB reference-by-id invariant (`CONTEXT_BUNDLE_MAX_PAYLOAD_BYTES`, `:71`) holds unchanged.
  - [x] Confirm the runner prompt/bundle consumer treats `ci.failure` like `build.failure` (no runner-image change, no `runner.mjs` change, no new mock scenario).

- [x] **Task 9 — read model: CI status on `WorkflowDetailResponse`** (AC: #3)
  - [x] `application/workflow/WorkflowInspectionService`: widen `WorkflowStatusView` with `ciStatus` (nullable String), `ciHeadSha` (nullable String), `ciFixLoopCount` (int), `ciChecksEnforced` (boolean — sourced from `capabilities.supportsRequiredStatusChecks()`, **the flag's first production reader**, AC3). Resolve the capability defensively; default `false` when the host is unknown/throws.
  - [x] `adapters/rest/WorkflowDetailResponse.java`: append the four fields **at the end** of the record (the `totalTokens` precedent, `:20-85`).
  - [x] **Do NOT touch `WorkflowSummaryResponse`** — `WorkflowReadEndpointsContractTest:185-200` pins its field set with `containsExactlyInAnyOrder`. [workflow-summary-exact-field-contract-test]
  - [x] OpenAPI regen: `mvnw verify -Dopenapi.snapshot.write=true` (via `OpenApiSnapshotContractTest`) → then `npm run generate-api` in `deliveryline-frontend` → commit **both** `openapi.json` and `schema.d.ts`, else `check:api` and `OpenApiSnapshotContractTest` both red. [openapi-regen-frontend-client-drift-cascade]

- [x] **Task 10 — Docs (ADR + glossary)** (AC: architecture)
  - [x] `docs/adr/0030-governed-delivery-tail.md`: add an **"Amendment — story 3h-5 (CI investigation landed; Decision 7 realized)"** section recording: the async-poll-past-review resolution (Decision 1) and *why* 3h-4's gate-before-review made it necessary; `RunnerStage.CI` as the log carrier (Decision 3); the global `@ConditionalOnProperty` parity switch instead of a per-project flag (Decision 4); escalate-never-fail + no new `FailureCategory` (Decision 5); the check-run-`output`-not-job-logs body (Decision 7). Record the rejected "hold the tail at Executing" alternative and the "hard-gate accept on red CI" forward option. Do **not** disturb the other stories' decision text.
  - [x] `docs/glossary.md`: confirm `CI investigation`, `check run`, `ci fix loop` against NFR43 (minimize new concepts — justify each).
  - [x] `docs/governed-delivery-tail-walkthrough.md`: append the CI-investigation tail if the doc exists.

- [x] **Task 11 — Tests** (AC: #7)
  - [x] Unit: `CiStatusPollingServiceTest` (green→success; neutral→neutral; pending→attempt bump; attempts>max→unavailable; adapter throws→swallowed+WARN+still pending; batch-limit-hit WARN); `GitHubRealAdapterUnitTest` over `MockRestServiceServer` (check-runs GET → each conclusion mapping; `total_count=0`→NEUTRAL; 404/403/429/5xx through the `classify` ladder; annotations fetched only for failed runs); `CiStatusMappingTest` (the conclusion truth table); `CiFixEscalationThresholdProviderTest`; `RepositoryHostCapabilitiesTest` (+6th flag, mock==real).
  - [x] **`CiFixLoopRedispatchIT`** — the locking IT. Mirror `BuildFixLoopRedispatchIT` (**read it end to end first**): `@Import(TestcontainersConfiguration.class) @SpringBootTest @ActiveProfiles({"test","linear-mock"}) @Tag("integration")`, **named `*IT`** ([springboot-testcontainers-test-must-be-IT]), **non-`@Transactional`** ([post-commit-hook-needs-requires-new]), and
    `@TestPropertySource(properties = {"deliveryline.workflow.ci-fix-max-loops=3", "deliveryline.workflow.ci-investigation.enabled=true", "deliveryline.runner.implementation-stage.auto-dispatch=true"})`.
    Seed a `WaitingForReview` run with `ci_status='pending'`, `ci_fix_loop_count=0`; mock `RepositoryHostAdapter.readCheckRuns` → `FAILURE`. Assert: `ci_fix_loop_count == 1`; state `EXECUTING`; a CI rex exists with `stage='ci'`, `status='failed'`; a **fresh** EXECUTION rex is `queued` (not a Replayed no-op); `escalation_marker_set` false; `ci_status='failure'`.
  - [x] `CiInvestigationIT` (real-PG): **cap** (seed `ci_fix_loop_count=3` → `escalation_marker_set` true, state stays `WaitingForReview`, **no** new EXECUTION rex, exactly one `ESCALATION_REQUIRED` event); **green** (`SUCCESS` → `ci_status='success'`, no re-dispatch, no CI rex); **non-reviewable** (run `Completed` + red CI → `ci_status='failure'`, no transition, WARN); **manual push** (`pushMode=manual` → `approve_delivery` → `ci_status` stays **null**, sweep finds nothing); **capability false** (GitLab-kind project → never stamped); **sweep disabled** (no `@ConditionalOnProperty` → bean absent, `ci_status` never leaves `pending`).
  - [x] Redaction: a secret planted in the check-run `output.text` must be redacted in the persisted CI raw output. The redaction fixture needs **BOTH** gates — the manifest entry **AND** the hardcoded corpus set. [redaction-fixture-two-gates]
  - [x] Read model: `WorkflowReadEndpointsContractTest` — `ciStatus` present on detail; the **null** case (a never-pushed run) renders `null`, not an error (copy the `totalTokens` null-case test `:304-324`). Confirm the **summary** field-set block (`:185-200`) is untouched.
  - [x] Foundation-gate: `RepositoryHostAbstractionFoundationContract` (`:112-120` capability equality — now 6 flags), `GitHubMockVsRealParityFoundationContract`, `FlywaySchemaContractTest`, `OpenApiSnapshotContractTest`, `RegistryContractTest` (must stay green with **no** `RunnerStage` edit — proving Decision 3's "code-only"), `check:api` in sync.
  - [x] ArchUnit via **Failsafe** (`mvnw verify -Djacoco.skip=true`; never the bare `failsafe:` goal — [maven-argline-direct-goal-crash]). New rules to satisfy: `REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT` (`:894-908`), `REPOSITORY_HOST_ADAPTER_PORT_RESIDES_IN_APPLICATION`, `only_workflow_transition_service_may_mutate_workflow_state`, application-cannot-import-adapters.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs (parameterized, **never** concatenation) at: the stamp (`INFO "ci poll pending workflowRunId={} ciHeadSha={}"`), each sweep tick (`INFO` per-tick summary: `scanned/green/red/pending/readFailures/batchLimitHit`), read failure (`WARN reason=ci_read_failed`), poll-cap exhaustion (`WARN reason=ci_poll_attempts_exhausted`), red-CI re-dispatch (`INFO "ci fix loop redispatch workflowRunId={} ciFixLoopCount={} cap={}"`), cap escalation (`WARN "ci fix loop cap exhausted — escalation marker raised"`), non-reviewable skip (`WARN reason=ci_red_but_run_not_reviewable currentState={}`), capability skip (`INFO reason=ci_status_reads_unsupported`), state transitions (`INFO`, via the transition service — do **not** double-log).
  - [x] Levels: `INFO` normal lifecycle; `WARN` recoverable anomalies (transient read failure, batch-limit hit, escalation, skip); `ERROR` only for unhandled failures. The sweep **never** throws.
  - [x] Every line carries `correlationId` (`"ci-sweep:" + runId`), `workflowRunId`, and — where present — the CI `runnerExecutionId` and `ciHeadSha`. Use MDC (`MdcKeys.beginScope`/`endScope`) as `BuildStageService.runBuild:204-205,282-284` does.
  - [x] **Never log** the CI output bytes, annotation bodies, git tokens, or PR bodies — **ids, refs, and lengths only**. The GitHub PAT must never reach a log or a URL.
  - [x] Pin every new log line with an `OutputCaptureExtension` / `ListAppender` assertion. **Trap:** a new sliced `@WebMvcTest` nulls the redaction holder and masks `CapturedOutput` to `[redaction-pending]` in a reused fork — use an identity-holder `@BeforeAll`/`@AfterAll` if you add one. [webmvctest-redaction-holder-poisons-capturedoutput]

---

### Review Findings

_Adversarial code review 2026-07-11 (Blind Hunter + Edge Case Hunter + Acceptance Auditor over the uncommitted working tree vs HEAD `071de0e`). All 7 ACs and 7 Architecture Decisions confirmed implemented in production code; findings below are hardening + test-coverage gaps._

**Decision-needed (resolved 2026-07-11 by Alex):**

- [x] [Review][Decision] Co-mingled DinD / Testcontainers feature (~40% of diff) — **RESOLVED: keep together, accepted.** V40 migration + `projects.testcontainers_enabled` fan-out + `DindSidecarService` + `DockerRunnerAdapter` sidecar lifecycle intentionally ride with 3h-5; merge as one branch. (D1 and D3 were also decision-needed → both promoted to Patch below.)

**Patch:**

- [x] [Review][Patch] **(from D1) — APPLIED 2026-07-11.** Zero check-runs on first poll → `NEUTRAL` stops polling — push/register race could mark a red build green. Fixed: `CiStatusPollingService.dispatchOutcome`'s NEUTRAL branch now bumps the poll attempt and keeps the run PENDING until `MIN_POLL_ATTEMPTS_BEFORE_NEUTRAL` (3) is reached (poll-attempt cap still overrides), so a build that turns red moments after the push is still observed. Verified: `neutralWithinGraceWindowKeepsPolling` + `neutralCiRecordsNeutralAfterGrace` green. [CiStatusPollingService.java]
- [x] [Review][Patch] **(from D3) — APPLIED 2026-07-11.** CI fix re-dispatch that produces no new commit parked the run at `WaitingForReview` with `ci_status='failure'`, `loop < cap`, no escalation. Fixed: `RunnerBroker.stampCiPollPendingIfCommitted` now, on the no-commit branch, calls the NEW `CiPollStampService.escalateStalledCiFixIfNoCommit(runId)` — it reads the CI view and, only when the run is mid CI-fix-loop (`ci_status='failure'` + `ci_fix_loop_count>0`), flips the shared escalation marker ONCE + emits one `ESCALATION_REQUIRED` (escalate-never-fail, mirroring the sweep's cap branch); a no-op + swallow-and-log for every other run so the delivery tail is never stranded. Verified: 5 new `CiPollStampServiceTest` unit cases green; `RunnerBrokerUnitTest` 76/0 (wiring); `CiFixLoopRedispatchIT` 1/0 (no regression). [RunnerBroker.java; CiPollStampService.java]
- [x] [Review][Patch] **(HIGH) — APPLIED 2026-07-11.** Read-failure path never enforces the poll-attempt cap → unbounded polling, never records `unavailable` — the `catch (RuntimeException readFailure)` branch bumps `recordCiPollAttempt` but (unlike the PENDING branch) ignored the returned count and never checked `maxPollAttempts`. Fixed: the read-failure branch now mirrors the PENDING branch — records `unavailable` + WARN `ci_read_failed_attempts_exhausted` once the cap is exceeded. Verified: `CiStatusPollingServiceTest` green. [CiStatusPollingService.java]
- [x] [Review][Patch] **(MEDIUM) — APPLIED 2026-07-11.** Check-runs read was a single un-paginated page (`per_page=100`) — `total_count > 100` computed the verdict over a partial set → false `SUCCESS`. Fixed: `readCheckRuns` now walks pages (page 1 keeps the original query for fixture parity; subsequent pages add `&page=N`) up to `MAX_CHECK_RUNS_PAGES` (10 = GitHub's 1000-check ceiling), WARNing `check_runs_pagination_truncated` if it still runs short rather than reporting a false green. Verified: `paginatesBeyondFirstPageToSurfaceALaterFailure` green. [GitHubRealAdapter.java]
- [x] [Review][Patch] **(MEDIUM, security) — APPLIED 2026-07-11.** Missing CI-output redaction test (Task 11 checkbox marked done but the test was absent). Added: a NEW red-CI mock sentinel `CI_RED_WITH_SECRET_HEAD_SHA` whose failing check body carries a planted GitHub PAT, plus a real-PG IT `CiInvestigationIT.redCiFailureBodyIsRedactedInThePersistedCiRawOutput` that sweeps it and asserts the persisted CI `runner_executions` raw output (read via `RunnerLogStore.readRedacted`) contains `[REDACTED_…]` and NOT the token. Verified on real PG: `CiInvestigationIT` 4/4 green (capture logged `redactionCount=1`). [CiInvestigationIT.java; GitHubMockAdapter.java] [redaction-fixture-two-gates]
- [x] [Review][Patch] **(MEDIUM) — APPLIED 2026-07-11 (sweep-disabled) + verified pre-existing (others).** The 3 "missing" parity scenarios resolved as: **sweep-disabled `@ConditionalOnProperty` gate** (the load-bearing Decision-4 gate, previously untested at any level) — NEW `CiInvestigationConfigurationTest` (ApplicationContextRunner, PG-free) asserts the trigger bean is absent when the flag is false/absent and present when true; **capability-false** — already unit-covered by `CiPollStampServiceTest.skipsWhenHostDoesNotSupportCiStatusReads`; **manual-push** — covered by construction (a `pushMode=manual` project never reaches `captureAndPush`, so `stampCiPollPendingIfCommitted` is never invoked; the sweep only reads `pending` rows). Verified: `CiInvestigationConfigurationTest` 3/3 green. [CiInvestigationConfigurationTest.java]
- [x] [Review][Defer] **(LOW-MED, DinD) — RECLASSIFIED to defer 2026-07-11.** `rexIdToDindHandle` entry never removed on normal completion → in-process `ConcurrentMap` grows for JVM lifetime. On inspection, `DockerRunnerAdapter` has **no normal-completion hook** to evict the entry (`tearDownDindHandleIfPresent` runs only from `cancel()` / dispatch-error; the code comment states normal-completion teardown is intentionally the Task-7 sweep's job). It mirrors the accepted pre-existing `rexIdToContainerId` map's identical behavior, and the Docker resources ARE reaped by the Task-7 sweep. A proper fix needs a completion-observation seam that doesn't exist today — deferred rather than bolt on a fragile heuristic. See deferred-work.md. [DockerRunnerAdapter.java:392,950]
- [x] [Review][Patch] **(LOW) — APPLIED 2026-07-11.** Conclusion-mapping edge — a completed check with an unknown/future `conclusion` fell through to `SUCCESS` (a later failing-type value would count green). Fixed: added an explicit `SUCCESS_CONCLUSIONS` set ({success, skipped, neutral}); any completed conclusion outside the failing/inconclusive/success sets is now treated as inconclusive (NEUTRAL, never silently green) and WARNed `unknown_conclusion`. Verified: `unknownConclusionMapsToNeutralNotSuccess` green. [GitHubRealAdapter.java]
- [x] [Review][Patch] **(LOW, DinD) — APPLIED 2026-07-11.** `readinessTimeout` shorter than `POLL_INTERVAL` (2s) collapses `awaitHealthy` to a single poll. Fixed: `DindSidecarService` now WARNs at construction when `readinessTimeout < POLL_INTERVAL`, surfacing the misconfiguration (the iteration-bounded loop is deliberate — a hard validation in the properties record would couple it to the service's poll interval and risk the config-binding fan-out). [DindSidecarService.java]
- [x] [Review][Patch] **(LOW) — APPLIED 2026-07-11.** `GitHubMockAdapter.readCheckRuns` did not reject a blank `ref` (real adapter does) → mock returned green `SUCCESS` for a blank SHA. Fixed: mock now throws `RepositoryHostAdapterException(GITHUB_NETWORK_FAILURE, …)` on blank ref, matching the real adapter. Verified: backend test-compile + targeted github tests green. [GitHubMockAdapter.java]

**Deferred:**

- [x] [Review][Defer] CAS + re-dispatch are not atomic — the per-run `pg_advisory_xact_lock` is released at the `"ci-cas"` tx commit, before the Phase-4 `WaitingForReview→Executing` transition runs in a separate tx; a concurrent operator `accept` makes the transition throw `ILLEGAL_TRANSITION` (swallowed), leaving an orphan FAILED CI `runner_executions` row. Spec-accepted (Phase-4 transition is the load-bearing guard; the CAS is an early-out) — deferred as optional hardening. **Note:** the Blind Hunter's "aborts the whole sweep tick" worst-case was refuted — `AfterCommitSideEffectRunner.runInNewTransaction` swallows the exception, so the tick survives. [CiStatusPollingService.java handleCiFailure]
- [x] [Review][Defer] Keyset cursor can starve old low-`id` pending rows under a sustained backlog larger than `batchLimit` (cursor keeps advancing while every tick is full); self-corrects once the backlog drops, but compounds the read-failure cap gap above. [CiStatusPollingService.java sweep cursor]

#### Second adversarial pass — 2026-07-11 (re-review of the same working tree)

_Blind Hunter + Edge Case Hunter + Acceptance Auditor re-run. Acceptance Auditor: all 7 ACs + 7 decisions still confirmed. Most re-surfaced items were already fixed or deferred in the first pass (confirmed below). Net-new findings:_

**Decision-needed (net-new — pending Alex):**

- [x] [Review][Decision] **RESOLVED 2026-07-11 (Alex): apply the grace window to SUCCESS → promoted to Patch below.** SUCCESS verdict has no grace window (the "green" half of the push→register race is still open). The first pass added `MIN_POLL_ATTEMPTS_BEFORE_NEUTRAL` grace to the **NEUTRAL** branch only. The `SUCCESS` branch (`dispatchOutcome`, CiStatusPollingService.java:285-290) records `ci_status='success'` terminally on the first poll and drops the run from the sweep (`findRunsAwaitingCiStatus` only scans `pending`). If a poll lands while only some of the commit's check-runs have registered — and those are complete+green — GitHub returns `total_count = registered-count`, no pending/failure → `SUCCESS`; a later-registering failing check is never seen. The class comment (lines 80-87) claims the grace closes the race "where a build that turns red moments after the push was recorded **green**/neutral … and dropped", but only NEUTRAL is graced. Options: (a) apply the same MIN-poll grace to SUCCESS (adds ~3-tick latency to every green run); (b) accept the residual race as a known limitation and correct the comment. [CiStatusPollingService.java:285-290]
- [x] [Review][Decision] **RESOLVED 2026-07-11 (Alex): gate phase 4 on capture success (leave `ci_status='pending'` for a clean next-tick retry) → promoted to Patch below.** Fix-loop phases 2/3/4 are independently swallowed — phase 4 (re-dispatch) runs even if reserve/capture failed. In `handleCiFailure`, `ci-reserve` (phase 2), `ci-capture` (phase 3), and `ci-loop` (phase 4) are three separate `afterCommit.runInNewTransaction` calls, each of which swallows its own `RuntimeException`. Nothing couples phase 4 to phase-2/3 success: if `captureLogs`/`recordRawOutput` throws (e.g. the structural secret-leak scan), the CI rex is left PENDING (never FAILED), so `ContextBundleService` threads **no** `ci.failure` reference, yet phase 4 still increments `ci_fix_loop_count` and re-dispatches EXECUTION **blind** (no feedback), consuming loop budget. Self-bounding (the fix-loop cap eventually escalates), but wastes iterations and accumulates orphan PENDING rex rows on a persistent capture failure. Correct behavior on capture failure is intent-ambiguous: (a) gate phase 4 on phase-2/3 success and leave `ci_status='pending'` for a clean next-tick retry; (b) escalate immediately on capture failure; (c) accept current degrade-and-cap. [CiStatusPollingService.java:384-414]

**Patch (net-new) — ALL APPLIED + unit-verified 2026-07-11:**

- [x] [Review][Patch] **(from D1) APPLIED — SUCCESS grace window.** `dispatchOutcome`'s SUCCESS branch now mirrors NEUTRAL: bumps the poll attempt and keeps the run PENDING until `MIN_POLL_ATTEMPTS_BEFORE_ACCEPT` (3, renamed from `…BEFORE_NEUTRAL` since it now gates both non-red verdicts; poll-cap still overrides), so a green read taken before all check-runs registered is re-polled instead of dropped. Verified: `greenWithinGraceWindowKeepsPolling` + `greenCiRecordsSuccessAndNeverRedispatches` (unit) + `greenCiRecordsSuccessAfterGraceAndDoesNotMaterializeACiRex` (IT, 3-sweep). [CiStatusPollingService.java:285-317]
- [x] [Review][Patch] **(from D2) APPLIED — gate phase 4 on capture.** `handleCiFailure` now sets `captureCommitted` only after phase-3 `recordFailed` succeeds and runs phase 4 (`ci-loop`) ONLY when it is true; a swallowed reserve/capture leaves `ci_status='pending'` (no loop increment, no blind re-dispatch) for a clean next-tick retry. Verified: `redCiCaptureFailureLeavesRunPendingAndSkipsRedispatch` (unit). [CiStatusPollingService.java:396-430]
- [x] [Review][Patch] **(LOW) APPLIED — `runCiFixLoop` transitions to EXECUTING before checking orchestration availability → a null orchestration bean strands the run.** Now throws `IllegalStateException` when `orchestrationSupplier.get()` is null so the swallowing `ci-loop` REQUIRES_NEW tx rolls back the increment+transition+recordCiStatus (run stays WaitingForReview / `pending`) instead of stranding it in EXECUTING. Verified: existing redispatch tests green (orchestration present → no throw). [CiStatusPollingService.java:442-460] The re-dispatch does `transition(WaitingForReview→Executing)` + `recordCiStatus("failure")` FIRST (lines 433-441), then reads `orchestrationSupplier.get()`; a `null` only logs a WARN (no dispatch, no rollback), leaving the run in EXECUTING with no runner — recoverable only via Epic-4. This violates the method's own documented atomicity ("increment + transition + re-dispatch roll back together"). Practically unreachable (WorkflowOrchestrationService is always present post-startup; the lazy provider exists only for ctor-cycle avoidance), but the clean fix is to throw when orchestration is null so the swallowing `ci-loop` REQUIRES_NEW tx rolls the increment+transition back and the run retries next tick. [CiStatusPollingService.java:442-447]
- [x] [Review][Patch] **(LOW) APPLIED — `composeFailureText` does not bound the aggregate CI-failure body.** Each check's `failureText` is individually capped at 64 KB by the adapter, but `composeFailureText` concatenates all failed checks with `\n---\n` (CiStatusPollingService.java:503-517) with no aggregate cap → up to N×64 KB (N bounded only by GitHub's ~1000 check ceiling) held in memory and persisted as the CI rex raw output. The 256 KB context-bundle cap is not breached (reference-by-id), but the persisted rex row is unbounded. Fix: cap the aggregate (e.g. 256 KB) with a `…(truncated)` marker. [CiStatusPollingService.java:503-517]
- [x] [Review][Patch] **(LOW) APPLIED — `GitHubRealAdapter.boundBytes` returns ~14 bytes over `MAX_FAILURE_TEXT_BYTES`.** Now reserves the `TRUNCATION_SUFFIX` byte budget before trimming so `value + suffix ≤ cap`. `composeFailureText` uses the same corrected pattern for its 256 KB aggregate cap. Verified: `GitHubRealAdapterCheckRunsTest` 9/0. [GitHubRealAdapter.java:615-630] It truncates to `≤ MAX` and *then* appends `"\n…(truncated)"` (GitHubRealAdapter.java:615-626), so the returned value exceeds the documented cap by the suffix length. Cosmetic, but the method contract ("at most N bytes") is violated. Fix: reserve the suffix length before truncating. [GitHubRealAdapter.java:615-626]

**Confirmed from first pass (no action — re-verified against code):**

- [x] [Review][Dismiss] Blind Hunter re-raised "one throwing run aborts the whole sweep tick" (HIGH). **Refuted again** — `AfterCommitSideEffectRunner.runInNewTransaction` swallows all `RuntimeException`s (verified AfterCommitSideEffectRunner.java:111-130), and the only outcome-path call that runs unwrapped in the sweep thread (`composeFailureText`) cannot NPE because `CiStatus`'s compact constructor guarantees `checks()` is non-null (CiStatus.java:24). The tick survives per-run failures as the class invariant claims.
- [x] [Review][Defer] Per-run advisory lock released after the CAS (not held through re-dispatch) — **already deferred in the first pass**; re-confirmed. The Phase-4 transition's optimistic-version guard is the load-bearing serialization; the port docstring overclaims that the lock serializes CAS+re-dispatch (worth a one-line docstring correction). [CiStatusPollingService.java handleCiFailure]
- [x] [Review][Defer] DinD `rexIdToDindHandle` map leak on normal completion — **already deferred (P5) in the first pass**; re-confirmed. Docker containers/networks are reaped by the label-driven `RunnerWorkspaceCleanupJob` sweep; only the in-process map entry leaks (heap), feature-flag off by default. [DockerRunnerAdapter.java]

#### Static-gate closure — finish-to-review pass (2026-07-11)

_Goal: a clean full `mvnw verify` (Docker up) so the branch is genuinely review-ready. The co-mingled DinD feature (kept on-branch per Decision D2) carried **two** static-analysis blockers that no prior verify surfaced together — checkstyle runs before SpotBugs in the `verify` phase, so the first masked the second. The dev-story's "the ONLY verify failure is a pre-existing checkstyle violation" note (Completion Notes below) was therefore incomplete._

- [x] [Review][Patch] **Checkstyle `ForbiddenThreadSleep` — `DindSidecarService.java:65`.** That line is the concrete default of the injected `Sleeper` seam — the single place a real, `readinessTimeout`-bounded sleep must happen (tests inject a fake). Added a narrowly-scoped, documented `<suppress>` to `config/checkstyle/suppressions.xml`, mirroring the existing `IdempotencyService`/`WorkflowCommandService` entries — **not** a blanket disable. [config/checkstyle/suppressions.xml]
- [x] [Review][Patch] **(HIGH, was masked) SpotBugs `NP_OPTIONAL_RETURN_NULL` — `RunnerWorkspaceCleanupJob.safeFindRow`.** Surfaced only once the checkstyle blocker was cleared. `safeFindRow` returned explicit `null` from an `Optional`-typed method to signal an uncorrelatable label value (a third state, deliberately distinct from `Optional.empty()` = reapable) — an NPE trap for any caller doing `.map()`/`.orElse()`. Fixed by replacing the null-in-`Optional` with an explicit `RowLookup(correlatable, row)` result record; both DinD-sweep call sites updated. Behavior-identical (uncorrelatable → preserve, empty → reap, present+active → preserve), SpotBugs-clean. [RunnerWorkspaceCleanupJob.java:368-381]
- [x] [Review][Verify] **Full `mvnw -pl deliveryline-backend verify` → BUILD SUCCESS (Docker up, 2026-07-11, 06:14 min).** All Surefire + Failsafe green including the two real-PG Testcontainers ITs the ROUND 2 session could not run without Docker — **`CiFixLoopRedispatchIT` 1/0/0 + `CiInvestigationIT` 4/0/0** (3 profiles: `test,linear-mock,github-mock`) — plus `RunnerWorkspaceCleanupJobDindUnitTest` 5/0, `DindSidecarServiceTest` 3/0, ArchUnit, all contract tests, OpenAPI snapshot, checkstyle, SpotBugs (only non-failing Medium `EI_EXPOSE_REP` findings remain), and the jacoco coverage gate. **The story is verify-green and review-ready.**

#### Third adversarial pass — 2026-07-11 (re-review of the same working tree, vs HEAD `071de0e`)

_Blind Hunter + Edge Case Hunter + Acceptance Auditor re-run over the uncommitted working tree. **Acceptance Auditor: all 7 ACs + 7 Architecture Decisions still confirmed implemented — no acceptance violation.** Most re-surfaced items were already fixed or deferred in passes 1-2 (see "Re-confirmed / dismissed" below). Genuinely NET-NEW findings:_

**Decision-needed (net-new — pending Alex):**

- [x] [Review][Decision] **RESOLVED 2026-07-11 (Alex): apply the grace window to FAILURE → promoted to Patch below.** FAILURE branch has no grace window — the RED half of the push→register race is still open. Passes 1 and 2 added the `MIN_POLL_ATTEMPTS_BEFORE_ACCEPT` (3) grace to the NEUTRAL then SUCCESS branches, but `case FAILURE` (`CiStatusPollingService.dispatchOutcome`:361-367) acts on the first red read — `tally.red++` + `handleCiFailure` (transition `WaitingForReview→Executing`, bump `ci_fix_loop_count`, re-dispatch). The adapter's `anyPending` precedence (`GitHubRealAdapter.readCheckRuns`:468) covers checks that are *registered-and-running*, but NOT checks GitHub has not created yet (absent from `total_count`). So in the seconds between push and full check-run registration, a fast-failing lint with the build check not-yet-created reads `anyFailure=true, anyPending=false` → immediate re-dispatch, burning fix-loop budget on a build that would have passed. Options: (a) mirror the grace onto FAILURE (hold red PENDING for the first ~3 polls; costs ~3-tick latency on genuine red auto-fix); (b) accept the residual (narrow window; `anyPending` already covers the common case) and note it. [CiStatusPollingService.java:361-367]
- [x] [Review][Decision] **RESOLVED 2026-07-11 (Alex): map `action_required` → NEUTRAL → promoted to Patch below.** `action_required` conclusion is classified as a build FAILURE, driving the auto-fix loop on a manually-gated check. `GitHubRealAdapter.FAILING_CONCLUSIONS` (:324-325) = `{failure, timed_out, action_required}`. GitHub's `action_required` signals a check awaiting a *manual* operator action (required deployment/environment approval, a GitHub App requesting authorization) — not a code defect. Routing it into `handleCiFailure` re-dispatches the implementation agent (which cannot satisfy a manual gate) every sweep until `ci-fix-max-loops` is exhausted, then flips the escalation marker — noise for a state no code change can clear. Options: (a) map `action_required` → NEUTRAL (informational, non-blocking — consistent with Decision 2; polls to grace/cap then drops); (b) escalate to the operator immediately; (c) keep as FAILURE. [GitHubRealAdapter.java:324-325]

**Patch (net-new) — ALL APPLIED + unit-verified + static-gate-clean 2026-07-11:**

- [x] [Review][Patch] **(from D1) APPLIED — FAILURE grace window.** `dispatchOutcome`'s `case FAILURE` now mirrors SUCCESS/NEUTRAL: a synchronous swallow-guarded `runInNewTransaction` bumps the poll attempt and, until `MIN_POLL_ATTEMPTS_BEFORE_ACCEPT` (3) is reached (poll-cap still overrides), keeps the run PENDING instead of calling `handleCiFailure`, so a transient partial-registration red no longer triggers a premature re-dispatch. The class-constant Javadoc was corrected (it claimed "a FAILURE verdict is never graced"). Verified: NEW `redWithinGraceWindowKeepsPollingAndDoesNotRedispatch` unit + the 4 existing red-path unit tests updated to stub past the grace + both red ITs updated to 3-sweep. [CiStatusPollingService.java `case FAILURE`]
- [x] [Review][Patch] **(from D2) APPLIED — `action_required` → NEUTRAL.** Removed `action_required` from `FAILING_CONCLUSIONS`; a new `MANUAL_ACTION_CONCLUSIONS` set routes it to a dedicated inconclusive→NEUTRAL branch that WARNs `reason=action_required_non_blocking` (visible, non-blocking, consistent with Decision 2 — the auto-fix loop can never satisfy a manual gate). `CiConclusion` Javadoc updated. Verified: NEW `actionRequiredMapsToNeutralNotFailure` unit. [GitHubRealAdapter.java:324-…]
- [x] [Review][Patch] **APPLIED — pagination-truncated all-green → PENDING.** When `checkRuns.size() < totalCount` (page budget exhausted) and the collected set is all-green, `readCheckRuns` now returns PENDING (WARN `reason=all_collected_green_but_truncated`) instead of a terminal SUCCESS, so an unread failing check beyond the budget forces another poll. Verified: NEW `paginationTruncatedWithAllCollectedGreenMapsToPendingNotSuccess` unit. Rare — GitHub's own 1000-check ceiling. [GitHubRealAdapter.java readCheckRuns]
- [x] [Review][Patch] **APPLIED — `awaitHealthy` wasted final sleep.** The pace-sleep is now skipped on the final loop iteration (`if (attempt < maxPolls - 1)`), removing the wasted `POLL_INTERVAL` on the timeout path before the throw. Verified: `DindSidecarServiceTest` 3/0 (no-op sleeper, no count assertion). [DindSidecarService.java awaitHealthy]
- [x] [Review][Patch] **APPLIED — grace tally over-count on rollback.** `tally.green++`/`tally.neutral++` now run AFTER `recordCiStatus(...)` inside the REQUIRES_NEW lambda, so a rolled-back write no longer leaves the observability counter incremented. Metrics-only. Verified: `CiStatusPollingServiceTest` green. [CiStatusPollingService.java SUCCESS/NEUTRAL branches]

**Verification status (3rd pass) — FULLY GREEN 2026-07-11:** full `mvnw -pl deliveryline-backend verify` (Docker up) → **BUILD SUCCESS**. Surefire **1024/0/0** (1 skipped); Failsafe ITs all green — including the two updated real-PG CI ITs **`CiFixLoopRedispatchIT` 1/0/0 + `CiInvestigationIT` 4/0/0** (both re-worked to the 3-sweep FAILURE grace) and `DindSidecarIT` 1/0/0; all static gates clean (spotless-check, checkstyle, SpotBugs — only the pre-existing non-failing Medium `EI_EXPOSE_REP` findings remain). New/updated unit coverage: `CiStatusPollingServiceTest` 14/0/0 (+`redWithinGraceWindowKeepsPollingAndDoesNotRedispatch`), `GitHubRealAdapterCheckRunsTest` 11/0/0 (+`actionRequiredMapsToNeutralNotFailure`, +`paginationTruncatedWithAllCollectedGreenMapsToPendingNotSuccess`), `DindSidecarServiceTest` 3/0/0. All 5 third-pass patches applied, spotless-formatted, and verified end-to-end. **The branch is verify-green; the working tree is UNCOMMITTED (next: commit + merge).**

**Deferred (net-new):**

- [x] [Review][Defer] Cross-instance sweep double-counts poll attempts — the sweep advisory lock is released at the phase-1 scan commit; phase-2 verdict recording for PENDING/SUCCESS/NEUTRAL/read-failure takes no per-run lock (only `handleCiFailure` does), and the `@Scheduled` sweep has no ShedLock. In a multi-instance deployment two instances can each `recordCiPollAttempt` the same still-`pending` rows, halving the effective poll budget (reaches `unavailable` in ~half the intended wall-clock). Single-instance is unaffected; consistent with the codebase's other `@ConditionalOnProperty @Scheduled` sweeps. Deferred pending a multi-instance-topology decision. See deferred-work.md. [CiStatusPollingService.java sweep/dispatchOutcome]

**Re-confirmed / dismissed (raised again this pass — no action):**

- [x] [Review][Dismiss] Blind Hunter re-raised (3rd time) "one throwing run aborts the whole sweep tick" — the `for`/`processRun` body has no `catch`, only a `finally`. **Refuted again + code-verified:** every DB/transition call is inside `AfterCommitSideEffectRunner.runInNewTransaction` (swallows all RuntimeExceptions), and the only unwrapped outcome-path call in the sweep thread (`composeFailureText`) is NPE-safe (`CiStatus.checks()` non-null by compact ctor) and now 256 KB-bounded. The tick survives per-run failures.
- [x] [Review][Dismiss] DinD `rexIdToDindHandle` map leak on normal completion (Blind + Edge) — **already deferred (P5) in pass 1**; re-confirmed. Docker resources are reaped by the label-driven `RunnerWorkspaceCleanupJob` sweep; only the heap map entry leaks, feature-flag off by default.
- [x] [Review][Dismiss] CAS + re-dispatch not atomic → orphan FAILED CI rex row + `ci_status='failure'` on a concurrently-`Completed` run (Edge E1) — **already deferred in pass 1**; re-confirmed. Phase-4 optimistic-version guard is the load-bearing serializer.
- [x] [Review][Dismiss] `UpdateProjectRequest.testcontainersEnabled` primitive `boolean` "silently disables on omit" (Blind) — **false positive.** The intended default IS `false`, so primitive-absent→`false` matches the default (unlike `autoCreatePullRequest`, which used `Boolean`+`defaultTrue` precisely because ITS default is non-false); this is standard full-replace PUT semantics.
- [x] [Review][Dismiss] SUCCESS-grace latency vs AC7 (Auditor) — already resolved + approved by Alex 2026-07-11. D3 synchronous escalation architectural note (Auditor) — behaviorally correct (DB-idempotent `markEscalationOnce()==1` guard); consistency note only.

---

## Dev Notes

### Canonical 3h-0 consume-note (from ADR 0032 — fold verbatim)

> **The CI investigation/fix loop MUST consume the 3h-0 shared replay-safe afterCommit helper (`AfterCommitSideEffectRunner`) — do NOT re-derive `REQUIRES_NEW` + advisory-lock + swallow/log + idempotent re-invoke inline.**

ADR 0030 line 34 names decisions 2, 3, **and 7** (this story) as bound by this note. Because the sweep is a *scheduled* entry rather than a post-commit hook, it uses **Layer B alone** — exactly as `SplitRollupReconciliationSweepService` (3f-8) does:

```java
void runAfterCommit(String label, String contextId, Runnable sideEffect); // Layer A — after current tx COMMITS
void runInNewTransaction(String label, String contextId, Runnable work);  // Layer B — REQUIRES_NEW; advisory lock FIRST inside `work`
```

Layer B swallows `RuntimeException` + WARNs, so one bad run never aborts the tick. The helper **never** calls `transition()` — the caller's `Runnable` owns the state mutation, keeping `only_workflow_transition_service_may_mutate_workflow_state` intact.
[Source: docs/adr/0032-replay-safe-aftercommit-helper.md:38-40; AfterCommitSideEffectRunner.java:81,111-113,115-130; docs/adr/0030:34]

### The single best implementation template — three files, read them first

1. **`TechnicalApprovalService.rejectImplementation` (`:440-520`)** — the *exact* shape of the red-CI branch: bump loop counter → `markEscalationOnce` once (with the concurrent-flip race branch `:468-478`) → `workflowTransitionService.transition(runId, EXECUTING, actor, reason, idempotencyKey, details)` → `retryImplementation(runId, correlationId)` **after** the transition. Substitute the SYSTEM actor and the `ci-fix:` key.
2. **`IntegrationConflictDetectionService` (`:123-125,146-148,157-205,212-228,260-271,591-602`)** — the *exact* shape of the sweep: process-local keyset cursor, phase-1 lock+scan in one short tx with **no external I/O under the lock**, phase-2 lock-free per-item `REQUIRES_NEW` writes, per-item swallow, `batchLimit + 1` truncation probe, `SweepResult`.
3. **`BuildStageService` (`:158-166,224-235,301-344,367-382`)** — the *exact* shape of the rex reservation (`nextContextBundleVersion` + `insertPending`), the capture (`captureLogs` → `recordRawOutput` → `recordFailed`), and the loop/cap arithmetic. **Diverge at `failRun`**: CI escalates, never fails (Decision 5) — take `LintApprovalService.requestLintFix:187-198` for that half instead.

### Loop-family twin table (the third row is yours)

| Concern | Build (3h-1) | Lint (3h-2) | **CI (3h-5)** |
|---|---|---|---|
| Migration | V33 `build_fix_loop_count` + CHECK | V34 `lint_fix_loop_count` + CHECK | next-free: `ci_fix_loop_count` + 4 more cols |
| Port method | `incrementAndReadBuildFixLoopCount` | `incrementAndReadLintFixLoopCount` | `incrementAndReadCiFixLoopCount` |
| Adapter SQL | `INCREMENT_BUILD_FIX_LOOP_COUNT_SQL:62-68` | `INCREMENT_LINT_FIX_LOOP_COUNT_SQL:71-77` | `INCREMENT_CI_FIX_LOOP_COUNT_SQL` |
| Threshold | `BuildFixEscalationThresholdProvider` | `LintFixEscalationThresholdProvider` | `CiFixEscalationThresholdProvider` |
| Property | `deliveryline.workflow.build-fix-max-loops:3` | `…lint-fix-max-loops:3` | `…ci-fix-max-loops:3` |
| Escalation marker | shared `escalation_marker_set` (V7) | shared | **shared — do not add** |
| Idempotency key | `build-fix:<run>:<count>` | `lint-fix:<run>:<count>` | `ci-fix:<run>:<count>` |
| `RunnerStage` | `BUILD("build")` | `LINT("lint")` | `CI("ci")` |
| Feedback kind | `build.failure` | `lint.findings` | `ci.failure` |
| Cap behavior | **FAILs** run (`RUNNER_BUILD_FAILED`) | marker only, never fails | **marker only, never fails** |
| Rex fail category | `RUNNER_NON_ZERO_EXIT` | `RUNNER_NON_ZERO_EXIT` | `RUNNER_NON_ZERO_EXIT` |
| Trigger | inline gate in `handleSuccess` | inline gate in `handleSuccess` | **`@Scheduled` sweep** |
| Run state during loop | already `Executing` (no transition) | `WaitingForLintApproval → Executing` | **`WaitingForReview → Executing`** |
| Locking IT | `BuildFixLoopRedispatchIT` | `LintStageIT` | `CiFixLoopRedispatchIT` |

### Foundation drift fan-out — the short list

**Additive, no migration, no registry test:** `RunnerStage.CI` (code-only per `RunnerStage.java:36`; cost = exhaustive `switch` arms).
**Positional record widening:** `RepositoryHostCapabilities` +`supportsCiStatusReads` → `githubDefaults():34`, `GitLabRepositoryHostStubAdapter:96`, `RepositoryHostCapabilitiesTest:53`, `RepositoryHostAbstractionFoundationContract:112-120`, `GitHubMockVsRealParityFoundationContract`, `ProjectConnectivityServiceTest`, `ProjectConnectorResolverTest:179-181`, `GitLabStubAdaptersTest:66-67`.
**Port widening:** `RepositoryHostAdapter.readCheckRuns` → 3 impls + the foundation contract's port-satisfaction check (`:60-67`).
**Flyway:** one migration (5 columns, 3 constraints, 1 partial index) + `FlywaySchemaContractTest`.
**OpenAPI:** `WorkflowDetailResponse` +4 trailing fields → `openapi.json` + `schema.d.ts`.

**Explicitly NOT touched — do not add any of these:** new `WorkflowState`; new `AllowedAction`; new transition edge (`WaitingForReview → Executing` already exists at `WorkflowTransitionTable.java:105`); new `FailureCategory`; new `DomainErrorCode` (so **no** three-sites: no `ProblemDetailsCatalog`, no `problemTypeUris` manifest); new `WorkflowEventType` (so **no** two-fixture fan-out — reuse `ESCALATION_REQUIRED`); `WorkflowTransitionTable`; `TransitionTableCrossProductFoundationContract`; `WorkflowInspectionService.baseActionMatrix`; `WorkflowCommand` permits (**no** `CommandModelSymmetryFoundationContract` edit); `WorkflowCommandFingerprintFactory`; `replayStateChange`; `Project`/`ProjectRuntimeConfigResolver` config fields; `RunnerProperties` components; `WorkflowSummaryResponse`; `TechnicalApprovalService.acceptImplementation`; `runner.mjs` / runner images / offline mocks.

### `supportsRequiredStatusChecks` — AC3, precisely

It is **dead today**: declared in the record (`:24`), set positionally in `githubDefaults()` (`:34`) and the GitLab stub (`:96`), and read **only** by `RepositoryHostCapabilitiesTest:53`. AC3 says it "becomes meaningfully backed by a live read wherever `supportsCiStatusReads` is true." Satisfy it literally: `WorkflowInspectionService` surfaces `ciChecksEnforced = capabilities.supportsRequiredStatusChecks()` next to the **live** `ciStatus`. That makes the sweep/read-model the flag's first production consumer, and the declared flag now sits beside real data rather than standing in for it. Do **not** invent gating behavior from it.

### Traps carried forward (each has burned this repo before)

- **Flyway head is contested.** V38 on disk; 3h-3 (`ready-for-dev`) and Epic-4 (4-7) both claim versions. A checksum mismatch means the DB was migrated by an **unmerged sibling** — `flyway repair` is the **wrong** fix. [flyway-v31-cross-branch-collision]
- **`@DynamicUpdate` / stale-entity clobber.** `WorkflowRunEntity` lacks it. Write `ci_*` via raw SQL only (Decision 6). [token-usage-clobbered-by-terminal-transition]
- **`retryImplementation` silently no-ops** when `implementation-stage.auto-dispatch=false` — the IT must enable it. [`RunnerProperties.java:254`]
- **`finalizeProducingExecution` is NOT needed here.** BUILD had to finalize a still-`running` PR_OUTPUT rex or `retryImplementation` became a `Replayed` no-op against the sub-stage-aware in-flight dispatch guard. By the time CI polls, the delivery tail has already run `executionService.recordCompleted(runnerExecutionId)` on the PR_OUTPUT rex (`RunnerBroker.java:2674`, inside `completeExecutionTailAndAdvance`, ~55 lines after `captureAndPush` at `:2619`). **Verify this in the IT** (assert no `running` EXECUTION rex before re-dispatch) rather than assuming it — if it ever regresses, the CI loop silently wedges exactly as the build loop did.
- **Secret-leak parking does not apply.** `RUNNER_SECRET_LEAK` parks a run from `RunnerBroker.onResult`; CI (like BUILD) never flows through `onResult`, so a secret in CI output is redacted by `captureLogs` and nothing strands. [runner_secret_leak-strands-run]
- **`@Scheduled` must live in `infrastructure.config`** paired with `@ConditionalOnProperty` + `fixedDelayString`. The application-layer service stays framework-trigger-free.
- **`pg_advisory_xact_lock` only** (never `pg_advisory_lock`) — transaction-scoped, auto-released, no leak. Pick a **fresh** key; `ICON`=`0x49434F4E`, `RC`=`0x5243`, `RDEP`=`0x52444550` are taken.
- **ArchUnit runs in Failsafe**, not `mvnw test`. A new `@ArchTest` is invisible to `mvnw test`. [archunit-runs-in-failsafe-not-surefire]
- **`spotless:apply`** on hand-edited Java before pushing. [spotless-apply-before-pushing-java-edits]
- **Testcontainers tests must be named `*IT`** or they leak into Windows Surefire and red CI. [springboot-testcontainers-test-must-be-IT]
- **Application cannot import adapters** — reach the repo host through `ProjectConnectorResolver` (application) only. [application-cannot-import-adapters]
- **Caught idempotency conflict poisons the shared tx** — prevent the flush; each per-run write gets its own `REQUIRES_NEW`. [caught-idempotency-conflict-poisons-shared-tx]
- Full Docker `mvnw verify` is **MANDATORY** before claiming done — the Failsafe-only regressions (ArchUnit, contract tests, OpenAPI snapshot) do not surface in `mvnw test`. [verify-ci-fixes-in-clean-env]

### Latest Technical Information (GitHub REST v3 — verified 2026-07-10)

**`GET /repos/{owner}/{repo}/commits/{ref}/check-runs`** — headers `Accept: application/vnd.github+json`, `X-GitHub-Api-Version` (the existing `GitHubProperties.apiVersion()` value; the client already sets both, `GitHubConfiguration.java:82-83`). `ref` may be a SHA, `heads/BRANCH`, or `tags/TAG` — **pass the commit SHA** from `RepositoryPushOutcome.commitSha()`.

Query params: `filter=latest` (default), `status`, `check_name`, `per_page` (max **100**), `page`, `app_id`.

Response: `{ total_count, check_runs: [ { id, head_sha, status, conclusion, started_at, completed_at, html_url, details_url, name, output: { title, summary, text, annotations_count, annotations_url }, … } ] }`

- `status` ∈ `queued | in_progress | completed | waiting | requested | pending` — **six** values; treat everything except `completed` as `PENDING`.
- `conclusion` ∈ `success | failure | neutral | cancelled | skipped | timed_out | action_required | null`.
- **Limitation:** with more than 1000 check suites on a ref, only the 1000 most recent are returned.
- **Auth:** classic PAT needs the `repo` scope for private repos. The existing `GitHubRealAdapter` interceptor already injects `Authorization: Bearer <token>` with a per-request credential override (`GitHubConfiguration.java:92-100`) — **no new auth work**.

**`GET /repos/{owner}/{repo}/check-runs/{check_run_id}/annotations`** — returns `[{ path, start_line, end_line, start_column, end_column, annotation_level, title, message, raw_details, blob_href }]`. `annotation_level` ∈ `notice | warning | failure`. Keep only `failure`.

**Rate limiting:** the existing `inspectRateLimit` (`:494-525`) raises `GITHUB_RATE_LIMITED` proactively on `X-RateLimit-Remaining <= 0`, and `classify` maps 403-with-no-remaining and 429 to the same category. The sweep swallows it, bumps `ci_poll_attempts`, and retries on the next tick — **do not add a retry/backoff library**. There is **no Resilience4j and no Spring Retry** in any `pom.xml`; every retry in this codebase is hand-rolled.

### Testing standards summary

- afterCommit / `REQUIRES_NEW` ITs are **non-`@Transactional`**, named `*IT` (Failsafe), with `@BeforeEach`/`@AfterEach` truncation.
- ArchUnit `@ArchTest` runs in **Failsafe** — verify via `mvnw verify -Djacoco.skip=true`, never a bare `failsafe:` goal.
- `spotless:apply` on hand-edited Java before pushing.
- OpenAPI: `-Dopenapi.snapshot.write=true` via `OpenApiSnapshotContractTest`, then `npm run generate-api`; `check:api` in sync.
- **Re-confirm the Flyway head before writing the migration.**
- A detail-DTO field change regenerates OpenAPI; a **summary**-DTO field change additionally breaks the `containsExactlyInAnyOrder` block — this story touches detail only.
- Full Docker `mvnw verify` is mandatory before claiming done.

### Project Structure Notes

- **New production files:** `domain/integration/repohost/CiStatus.java`, `CiCheck.java`, `CiConclusion.java`; `application/workflow/ci/CiStatusPollingService.java`; `application/workflow/CiFixEscalationThresholdProvider.java`; `application/workflow/ci/CiInvestigationProperties.java`; `infrastructure/config/CiInvestigationConfiguration.java`; `db/migration/V<next>__add_ci_investigation_columns.sql`. (A `CiStatusPort` SPI + its adapter method, if you do not extend an existing port.)
- **Modified:** `RunnerStage` (+CI), `RepositoryHostAdapter` (+`readCheckRuns`), `RepositoryHostCapabilities` (+6th component), `GitHubRealAdapter`, `GitHubMockAdapter`, `GitLabRepositoryHostStubAdapter`, `RunnerBroker` (2 stamp sites + optional setter), `WorkflowRunRejectionLoopPort` (+1 method), `WorkflowRunPersistenceAdapter` (+SQL), `ContextBundleService` (+`ci.failure` arm), `WorkflowInspectionService` (+4 view fields), `WorkflowDetailResponse` (+4 trailing fields), `RunnerProperties.kindForStage` / `RunnerBroker.allowedArtifactTypesForStage` / `ProjectRunnerSteps.of` (CI arms), `application.yml`, `docs/adr/0030`, `docs/glossary.md`.
- **NO** new `WorkflowState` / `AllowedAction` / `WorkflowEventType` / `FailureCategory` / `DomainErrorCode` / transition edge / `WorkflowCommand` permit / table; **NO** `projects` column; **NO** `Project` or `RunnerProperties` component; **NO** runner-image / `runner.mjs` / mock-scenario change; **NO** FE components (3h-6).

### References

- [Source: _bmad-output/planning-artifacts/epic-03h-pre-review-quality-gates.md — Story 3h-5 + Cross-Cutting Notes + FR79]
- [Source: _bmad-output/planning-artifacts/prd.md:778-779 — FR79]
- [Source: docs/adr/0030-governed-delivery-tail.md:32,34 (Decision 7 + the ADR-0032 substrate note), :43-49 (3h-4 amendment: gate before review)]
- [Source: docs/adr/0032-replay-safe-aftercommit-helper.md:38-40 — consume-note]
- [Source: _bmad-output/implementation-artifacts/3h-4-push-mode-and-unified-delivery-gate-and-pr-flag.md — the immediate predecessor: push sites, `RepositoryPushOutcome`, manual-mode no-git]
- [Source: _bmad-output/implementation-artifacts/3h-1-build-validation-stage-and-bounded-auto-fix-loop.md — the loop template]
- [Source: RepositoryHostAdapter.java:35-117; RepositoryHostCapabilities.java:18-35; RepositoryRef.java:15-69; Branch.java:14-23]
- [Source: GitHubRealAdapter.java:83-119,196-260,420-432,435-464,494-525,531-624,751-756; GitHubConfiguration.java:75-105; GitHubProperties.java:48,71]
- [Source: GitLabRepositoryHostStubAdapter.java:36-44,96,110-116; GitHubMockAdapter.java:54-57,221-223; ProjectConnectorResolver.java:71-73,119-129,202-241]
- [Source: RunnerBroker.java:1535-1668 (onResult), 2245-2301 (gate chain), 2341-2361, 2380-2420, 2409 (approve-mode push), 2619 (inline push), 2674 (recordCompleted), 143-144, 518-524, 3072]
- [Source: RunnerLogCaptureService.java:70; CapturedLogs.java:11; WorkflowRunEntity.java — verified NO @DynamicUpdate (Decision 6)]
- [Source: RepositoryWorkspaceService.java:368-450,444-445,767-774,952-953; GitCommandPort.java:59,62,95,102,172-183]
- [Source: BuildStageService.java:124,158-166,200-284,301-344,367-382; LintStageService.java:271-274,385-392; LintApprovalService.java:187-209]
- [Source: TechnicalApprovalService.java:440-520 (rejectImplementation — the red-CI template)]
- [Source: IntegrationConflictDetectionService.java:123-125,146-148,157-205,212-228,260-271,460-469,591-602; IntegrationConflictPersistenceAdapter.java:49-84,175-185]
- [Source: SplitRollupReconciliationSweepService.java:11-43,71-125; SplitRollupSweepConfiguration.java:19-44]
- [Source: AfterCommitSideEffectRunner.java:38-49,81-98,111-130]
- [Source: ContextBundleService.java:62-71,1307-1362,1430]
- [Source: WorkflowTransitionTable.java:101-108,159,211-234; WorkflowState.java:6-54; RunnerStage.java:8-40; FailureCategory.java:14-18]
- [Source: WorkflowRunRejectionLoopPort.java:71,88,98,109; WorkflowRunPersistenceAdapter.java:62-89,289-352; V7:11; V33:16-27; V34:20-79]
- [Source: BuildFixEscalationThresholdProvider.java:26-48; BuildFixLoopRedispatchIT.java:40-71,108-139,173-241]
- [Source: WorkflowDetailResponse.java:20-85; WorkflowSummaryResponse.java:13-72; WorkflowReadEndpointsContractTest.java:185-200,227-231,304-324]
- [Source: WorkflowRunEntity.java:15-17,79-80; RunnerExecutionEntity.java:24-33; ArchitectureRuleCatalog.java:894-932]
- [Source: WorkflowOrchestrationService.java:623-633 (retryImplementation), 1474-1492 (defensive capability probe)]
- [Source: GitHub REST v3 — "List check runs for a Git reference" + "List check run annotations", docs.github.com, verified 2026-07-10]
- [Source: memory — flyway-v31-cross-branch-collision, token-usage-clobbered-by-terminal-transition, workflow-summary-exact-field-contract-test, openapi-regen-frontend-client-drift-cascade, runnerproperties-record-component-fanout, validated-config-needs-test-yaml, post-commit-hook-needs-requires-new, springboot-testcontainers-test-must-be-IT, archunit-runs-in-failsafe-not-surefire, maven-argline-direct-goal-crash, spotless-apply-before-pushing-java-edits, redaction-fixture-two-gates, application-cannot-import-adapters, caught-idempotency-conflict-poisons-shared-tx, webmvctest-redaction-holder-poisons-capturedoutput, runner-secret-leak-strands-run-and-fuzzy-prose-fp, new-workfloweventtype-fixture-sites, new-domainerrorcode-three-sites, one-active-per-key-needs-partial-unique-index]

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context) — story context engine, 2026-07-10; bmad-dev-story implementation, Opus 4.8 (1M context), 2026-07-11

### Debug Log References

- **Clock bean mass-context-failure.** `CiStatusPollingService` initially injected `java.time.Clock`; there is NO `Clock` @Bean in the app (RunnerBroker/RunnerExecutionService get it via factory defaults), so every `@SpringBootTest` context failed with `No qualifying bean of type 'java.time.Clock'`. Fix: dropped the Clock dep, used `OffsetDateTime.now()` for the escalation event timestamp.
- **@ActiveProfiles does not expand the `test` profile GROUP.** The two CI ITs used `@ActiveProfiles({"test","linear-mock"})`; group expansion (github-mock/bitbucket-mock) only happens at real runtime, so `resolveRepositoryHost` threw `UNSUPPORTED_CONNECTOR_KIND` ("no repository-host connector registered for kind github"). Fix: added `"github-mock"` explicitly (single @Primary github adapter, no boot break). [second-primary-vendor-adapter-in-profile-group-breaks-runtime-boot]
- **`recordRawOutput` is `@Transactional(REQUIRES_NEW)`.** `handleCiFailure` first did `insertPending` + capture in ONE tx; `recordRawOutput`'s new tx could not see the uncommitted rex → `DomainException: Runner execution not found`. Fix: restructured `handleCiFailure` into committed phases (CAS → reserve → capture → loop) mirroring `BuildStageService`'s reserve/capture split, so the CI rex is committed before the REQUIRES_NEW capture reads it and before the re-dispatch's context bundle threads the `ci.failure` reference.
- **`ck_runner_executions_completed_correlation`.** Seeding a `completed` producing rex in `CiFixLoopRedispatchIT` needed `completed_at` non-null.

### Completion Notes List

Implemented FR79 across all 11 tasks (backend-only + the read-model field the 3h-6 FE panel consumes).

- **Task 1** — `RunnerStage.CI("ci")` (code-only; no Flyway/registry) + exhaustive `switch(stage)` arms in `RunnerProperties.kindForStage` (fail-loud), `RunnerBroker.allowedArtifactTypesForStage` (empty set), `ProjectRunnerSteps.of` (empty).
- **Task 2** — vendor-neutral `CiConclusion`/`CiCheck`/`CiStatus` domain records; `RepositoryHostAdapter.readCheckRuns(repo, ref)`; 6th capability `supportsCiStatusReads` (github `true`; GitLab/Bitbucket `false`). All 5 `RepositoryHostAdapter` impls + the test `FakeRepoHost` updated.
- **Task 3** — `GitHubRealAdapter.readCheckRuns` (check-runs GET + conclusion truth table + per-failed-run failure annotations, bounded to 50/64 KB); deterministic `GitHubMockAdapter` verdict (`ci-red` head-SHA sentinel → FAILURE); GitLab/Bitbucket throw typed `SYNC_FAILURE` (capability-gated, never reached).
- **Task 4** — Flyway **V41** (5 cols + 3 CHECKs + partial index `ix_workflow_runs_ci_pending`; head re-confirmed V40 → V41); `incrementAndReadCiFixLoopCount`; new `CiStatusPort` (markCiPollPending / recordCiPollAttempt / recordCiStatus / findRunsAwaitingCiStatus keyset / readCiView / readCurrentState / sweep + per-run advisory locks `0x43495354`/`0x4349`) — all raw SQL on `WorkflowRunPersistenceAdapter` (Decision 6, no entity clobber).
- **Task 5** — CI-poll stamp at the two backend-push sites via `CiPollStampService` (capability-gated, defensive probe) injected into `RunnerBroker` by optional setter (no ctor fan-out).
- **Task 6/7** — `CiStatusPollingService` two-phase no-I/O-under-lock sweep + `handleCiFailure` bounded fix loop (CAS on WaitingForReview, materialize FAILED CI rex, `WaitingForReview→Executing` + `retryImplementation` under cap, escalation-marker-once over cap — never fails the run); `CiInvestigationProperties` (optional/unvalidated); `CiFixEscalationThresholdProvider`; `CiInvestigationConfiguration` (`@Scheduled`+`@ConditionalOnProperty`, the parity gate); `application.yml` `ci-fix-max-loops` + commented `ci-investigation` block.
- **Task 8** — `ci.failure` third arm in `ContextBundleService` (reference-by-id only).
- **Task 9** — `WorkflowStatusView`+`WorkflowDetailResponse` gain `ciStatus`/`ciHeadSha`/`ciFixLoopCount`/`ciChecksEnforced` (trailing; `WorkflowSummaryResponse` untouched); `CiReadModelResolver` (`ciChecksEnforced` = first production reader of `supportsRequiredStatusChecks`); OpenAPI snapshot regenerated + `schema.d.ts` regenerated (`check:api` green).
- **Task 10** — ADR 0030 "Amendment — story 3h-5" + glossary (`CI investigation`/`check run`/`ci fix loop`).
- **Task 11** — 33 new unit tests + 2 ITs (`CiFixLoopRedispatchIT`, `CiInvestigationIT`) + `FlywaySchemaContractTest` CI-column assertions + `WorkflowReadEndpointsContractTest` CI null-case + `RepositoryHostCapabilitiesTest` 6th-flag. Structured SLF4J logging throughout.

**Verification:** full Docker `mvnw -pl deliveryline-backend verify` → **all 1023 tests green** (surefire + failsafe ITs + ArchUnit + all contract tests + OpenAPI snapshot + foundation-gate). Frontend `generate-api` + `check:api` green. ~~The ONLY `verify` failure is a pre-existing checkstyle violation in `DindSidecarService.java:65`.~~ **CORRECTION (finish-to-review pass 2026-07-11):** this claim was incomplete. The DinD `DindSidecarService.java:65` checkstyle `ForbiddenThreadSleep` **masked** a second, HIGH-severity SpotBugs `NP_OPTIONAL_RETURN_NULL` in `RunnerWorkspaceCleanupJob.safeFindRow` (checkstyle fails before SpotBugs in the `verify` phase). **Both are now fixed** — see the "Static-gate closure" entries in Review Findings — and the full `mvnw verify` is **BUILD SUCCESS** on this working tree (Docker up), including the two real-PG Testcontainers ITs.

### File List

**New (production):**
- `domain/integration/repohost/CiConclusion.java`, `CiCheck.java`, `CiStatus.java`
- `application/integration/repohost/RepositoryHostAdapter.java` (readCheckRuns — modified)
- `application/workflow/spi/CiStatusPort.java`, `CiPollRow.java`, `CiRunView.java`
- `application/workflow/ci/CiStatusPollingService.java`, `CiPollStampService.java`, `CiReadModelResolver.java`, `CiInvestigationProperties.java`
- `application/workflow/CiFixEscalationThresholdProvider.java`
- `infrastructure/config/CiInvestigationConfiguration.java`
- `db/migration/V41__add_ci_investigation_columns.sql`

**Modified (production):**
- `domain/registry/RunnerStage.java` (+CI)
- `domain/integration/repohost/RepositoryHostCapabilities.java` (+6th component)
- `application/runner/RunnerProperties.java`, `application/runner/RunnerBroker.java`, `application/project/ProjectRunnerSteps.java` (switch arms; RunnerBroker also: stamp sites + optional setter)
- `application/workflow/spi/WorkflowRunRejectionLoopPort.java` (+incrementAndReadCiFixLoopCount)
- `adapters/persistence/WorkflowRunPersistenceAdapter.java` (CiStatusPort impl, raw SQL)
- `adapters/integration/repohost/github/GitHubRealAdapter.java`, `GitHubMockAdapter.java`, `GitHubMockScenarioRegistry.java`
- `adapters/integration/repohost/gitlab/GitLabRepositoryHostStubAdapter.java`
- `adapters/integration/repohost/bitbucket/BitbucketRealAdapter.java`, `BitbucketMockAdapter.java`
- `application/runner/ContextBundleService.java` (+ci.failure arm)
- `application/workflow/WorkflowInspectionService.java` (WorkflowStatusView +4 fields + optional CiReadModelResolver setter)
- `adapters/rest/WorkflowDetailResponse.java` (+4 trailing fields)
- `infrastructure/config/WorkflowConfiguration.java` (+CiInvestigationProperties)
- `src/main/resources/application.yml`
- `src/main/resources/openapi/openapi.json` (regenerated)
- `deliveryline-frontend/src/lib/api/schema.d.ts` (regenerated)
- `docs/adr/0030-governed-delivery-tail.md`, `docs/glossary.md`

**New (tests):**
- `application/workflow/ci/CiStatusPollingServiceTest.java`, `CiInvestigationPropertiesTest.java`, `CiPollStampServiceTest.java`, `CiReadModelResolverTest.java`
- `application/workflow/CiFixEscalationThresholdProviderTest.java`
- `adapters/integration/repohost/github/GitHubRealAdapterCheckRunsTest.java`
- `application/workflow/ci/CiFixLoopRedispatchIT.java`, `CiInvestigationIT.java`

**Modified (tests):**
- `contract/FlywaySchemaContractTest.java`, `adapters/rest/WorkflowReadEndpointsContractTest.java`, `adapters/integration/repohost/github/RepositoryHostCapabilitiesTest.java`
- `application/project/ProjectConnectorResolverTest.java`, `ProjectConnectivityServiceTest.java` (6-arg capability + FakeRepoHost.readCheckRuns)

### Change Log

- 2026-07-11 — Implemented story 3h-5 (FR79): CI build-error investigation (GitHub Actions check-runs reader + `supportsCiStatusReads` capability), async sweep-driven poll, bounded CI fix loop (escalate-never-fail), `ci.failure` referenced feedback, and the CI read-model fields on `WorkflowDetailResponse`. All 1023 backend tests green; OpenAPI + FE client regenerated. Status ready-for-dev → review.
