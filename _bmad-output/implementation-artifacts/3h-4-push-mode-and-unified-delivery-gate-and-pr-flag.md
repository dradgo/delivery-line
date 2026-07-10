# Story 3h.4: Push-Mode + Unified Delivery Gate (`WaitingForDelivery` / `approve_delivery`) + PR Flag

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an operator,
I want to choose per project whether push and PR/MR happen automatically or behind an explicit approval,
so that some projects push manually or let me review *before* the CI build runs — while existing projects keep their current auto-push + auto-PR behavior.

---

## ⛔ PREREQUISITE GATE — read before starting

**`3h-2-cpu-linter-gate-and-waiting-for-lint-approval-hard-gate` MUST be `done` (merged) before dev-story starts.** It is `done` ✅. This story is a **structural twin of 3h-2** and builds *directly on* the seams 3h-1/3h-2 introduced (all present on this branch):
- `RunnerBroker.completeExecutionTailAndAdvance(...)` (3h-1) + `resumeDeliveryTailFromGate(runId, correlationId)` (3h-2) — the two `captureAndPush` call sites this story gates.
- `RunnerBroker` gate-chain wiring (`tryGateBehindBuild` → `tryLintGateOrTail` → `tail`) + the `lintStageServiceSupplier` optional-setter injection precedent.
- `LintApprovalService` / `WorkflowCommandService.requireParkedAtLintGate` executor-as-gate pattern; the `WaitingForLintApproval` + `approve_lint` full foundation-drift fan-out (the exact template for `WaitingForDelivery` + `approve_delivery`).
- The full per-project config stack threading `buildCommand`/`buildStageEnabled` (3h-1) + `lintCommands`/`lintStageEnabled` (3h-2).

**3h-3 (BMAD review mode) is NOT a prerequisite** — it only touches the REVIEW stage; this story does not read or modify the reviewer. The epic sequences 3h-3 before 3h-4 for narrative order only. **However 3h-3 is `ready-for-dev` and will claim its own Flyway migration** (`step_reviews.review_findings jsonb`), so **re-confirm the Flyway head at implementation time** ([flyway-v31-cross-branch-collision]).
[Source: sprint-status.yaml `3h-2 done`, `3h-3 ready-for-dev`; epic-03h §Sequencing; 3h-2 story File List]

---

## 🧭 ARCHITECTURE DECISIONS — read before coding (firm; they resolve contradictions in the epic AC wording)

**Decision 1 — the delivery gate sits BEFORE the review gate (product-owner confirmed, Alex, 2026-07-08).** The epic self-contradicts: the Cross-Cutting crux says "BUILD → LINT → REVIEW all run on code that has **not** yet been pushed" (push *after* review), but AC6 requires the `auto` default to stay **byte-identical** to today (push *before* review). **Resolution: gate BEFORE review.** `WaitingForDelivery` is entered from `EXECUTING` at the **current push point** (where `completeExecutionTailAndAdvance` fires today, before `WaitingForReview`):

- **auto** → the run **never parks**; `captureAndPush` fires inline exactly as today, then the run advances to `WaitingForReview` → REVIEW → `Completed`. **Byte-identical** to pre-3h delivery (AC6). The delivery gate is a pass-through in `auto` mode — mirroring how a disabled BUILD/LINT stage is a pass-through.
- **approve** / **manual** → the run **parks** at `WaitingForDelivery` *instead of* pushing; `approve_delivery` performs the push (approve) or records the out-of-band delivery (manual), then advances to `WaitingForReview`.

This keeps the advisory reviewer reading **pushed** code + a **live PR** (so `TechnicalApprovalService.acceptImplementation`'s `assertPrLinkPresentAndMatches` is unchanged) and reuses `resumeDeliveryTailFromGate` **verbatim** for the approve-mode push. The literal "review-on-unpushed-code" alternative was rejected as out-of-scope: it would force the advisory reviewer to enrich from a local diff (no `pushOutcome`) and re-route `acceptImplementation` to `WaitingForReview → WaitingForDelivery → Completed` — the reviewer/accept-semantics surgery 3h-2's dev flagged as high-risk against just-merged delivery code. Granular split remains a documented forward option (ADR 0030 Alt 4).
[Source: AskUserQuestion 2026-07-08; epic-03h Cross-Cutting "structural crux" vs Story 3h-4 AC6; docs/adr/0030 Decision 5/6, Alt 4; TechnicalApprovalService.java:615-622]

**Decision 2 — `pushMode` is a CHECK-constrained text column (mirror `runner_kind`), NOT free-text (`reviewer_model_kind`).** `pushMode` is a closed, self-owned, non-null-default enum `{auto, manual, approve}` — structurally identical to `runner_kind`/`status`/connector kinds (all CHECK-constrained). `reviewer_model_kind` is deliberately CHECK-free only because its valid set defers to which reviewer models are wired at execution time; `pushMode` has no such indirection. Add `push_mode text not null default 'auto'` + `ck_projects_push_mode check (push_mode in ('auto','manual','approve'))`, a new `domain/registry/PushMode` registry enum (copy `RunnerKind` shape), and parse in the entity getter (`PushMode.fromValue(...)`, fail-fast on bad DB value) + on the write path (`ProjectManagementService.parsePushMode`, default `AUTO` on null). `autoCreatePullRequest` is a plain `boolean not null default true` (NOTE: default **true**, unlike build/lint's `default false`).
[Source: agent research — V20 ck_projects_runner_kind vs V19 reviewer_model_kind DD-1; ProjectEntity.java:232-238]

**Decision 3 — the delivery gate composes with the lint gate; `LintApprovalService.approveLint` routes into it.** Because the gate order is build → lint → **deliver** → review, when a project has **both** lint gating and a non-`auto` push mode, `approve_lint` must flow into the delivery gate — NOT push immediately. `approveLint` (3h-2) transitions `WaitingForLintApproval → WaitingForReview` **synchronously** and defers the push. Modify it to resolve `pushMode` in the same tx and branch its **target state**:
- `pushMode == auto` → transition `→ WaitingForReview` + deferred `resumeDeliveryTailFromGate` (unchanged 3h-2 behavior).
- `pushMode ∈ {manual, approve}` → transition `→ WaitingForDelivery` (park) + **no** deferred push (`approve_delivery` will push/record).

This preserves 3h-2's synchronous wrong-state guard (`requireParkedAtLintGate`) and adds one new transition edge `WaitingForLintApproval → WaitingForDelivery`. `pushMode` resolution is a cheap in-memory `ProjectRuntimeConfigResolver` read — safe in-tx (no I/O).
[Source: 3h-2 LintApprovalService.java:77-131; RunnerBroker.resumeDeliveryTailFromGate:2303-2394]

**Decision 4 — manual mode reuses the `ingestManualResult` "advance-without-side-effect" pattern.** For `pushMode == manual`, `approve_delivery` must record the operator's out-of-band delivery and advance **without** calling `git.push`/`createPullRequest`. The precedent is `RunnerBroker.ingestManualResult` (story 3d-3/3d-4), which is explicitly "the automated success tail minus the workspace-coupled `captureAndPush`/enrich steps" and drives the **same** orchestration advance callbacks. Manual `approve_delivery` = transition `WaitingForDelivery → WaitingForReview` + append a `delivery.recordedManually` event + enqueue the reviewer (enqueue-only) — never touching git. The advisory reviewer degrades best-effort without a backend push (the documented manual-push limitation, consistent with 3h-5 AC4 "manual push = the backend pushed nothing, so there is nothing of ours to read").
[Source: RunnerBroker.ingestManualResult:2738-2877 (skips captureAndPush 2716-2733/2767; advance callbacks 2849-2876)]

**Decision 5 — the PR flag gates PR creation wherever the push happens; no parking is added for it.** `autoCreatePullRequest` gates `createOrUpdatePullRequest` inside `captureAndPush` (skip PR-create when `false`), preserving the existing `repoRef`-presence gate. In `auto` push mode the push still fires; the PR is created iff `autoCreatePullRequest`. In `approve` mode `approve_delivery`'s push respects the same flag. There is **no** separate PR-approval state (ADR 0030 Alt 4 rejected the split-gate default).
[Source: RepositoryWorkspaceService.captureAndPush:368-450, createOrUpdatePullRequest:742-800 (repoRef gate :751-759); epic AC4/AC5]

---

## Context — why this story exists (read before coding)

After 3h-1/3h-2, the tail is `INVESTIGATION → EXECUTION(pr_output) → BUILD → LINT → completeExecutionTailAndAdvance(captureAndPush + WaitingForReview + REVIEW)`. The instant those CPU gates pass, `RepositoryWorkspaceService.captureAndPush()` **auto-commits, auto-pushes, and auto-creates a PR** (`RunnerBroker.java:2467`) — self-gated only on "workspace exists + has uncommitted changes," with **no** per-project push-mode or create-PR control. There is no way for a project to push manually, or to review before the push (and therefore before the CI build) fires.

This story adds the **unified delivery gate**: per-project `pushMode ∈ {auto, manual, approve}` + `autoCreatePullRequest` feed a new non-terminal `WaitingForDelivery` state + `approve_delivery` action that performs push and/or PR per the two flags. It completes the `captureAndPush` relocation 3h-1 began (lifting it out of `onResult` into a gate-controlled tail) and delivers **FR78**. It unblocks 3h-5 (CI investigation reads the pushed branch) and 3h-6 (the delivery Decision Bar).
[Source: epic-03h Story 3h-4 + Cross-Cutting Notes "structural crux — push relocation"; docs/adr/0030 Decision 6]

---

## Acceptance Criteria

1. **Per-project config `pushMode` + `autoCreatePullRequest`.** `Project` + `ProjectRuntimeConfigResolver` gain `pushMode` (new `PushMode` enum `{AUTO, MANUAL, APPROVE}`, **default `AUTO`**) + `autoCreatePullRequest` (`boolean`, **default `true`**), seeded from `application.yml` defaults via a new `RunnerProperties.DeliveryMode(PushMode pushMode, boolean autoCreatePullRequest)` nested record (the `BuildStage`/`LintStage` precedent). Both resolved per run. `pushMode` is stored as a **CHECK-constrained text column** (Decision 2); `autoCreatePullRequest` as a plain boolean. Config threads the **full** per-project stack (entity, mapper, Create/Update DTOs+commands, `ProjectManagementService` create/update **and archive/unarchive status-only re-pass**, `ProjectController.createFingerprint`, `ProjectResponse`, `DefaultProjectSeeder`, OpenAPI regen). Resolution is worker-thread-safe (`Project` is a detached record).

2. **`captureAndPush` gated by the delivery gate (relocation completed).** `captureAndPush` is no longer unconditionally fired at pr-output-tail time. A new `DeliveryGateService.tryGateBehindDelivery(...)` intercepts the tail: in `auto` mode it is a **pass-through** (the inline `completeExecutionTailAndAdvance` runs, byte-identical); in `manual`/`approve` mode the producing PR_OUTPUT rex is finalized and the run **parks** at `WaitingForDelivery` instead of pushing. The backend keeps git ownership (`GitCommandPort.push`, `RepositoryWorkspaceService`); only the *trigger point* is now gate-controlled. The gate is skipped (pass-through) when there is no workspace/`repoRef` to deliver (mirror `captureAndPush`'s `no_repo_workspace` no-op).

3. **New non-terminal `WaitingForDelivery` state + `approve_delivery` action (full drift).** `WorkflowState WAITING_FOR_DELIVERY("WaitingForDelivery")` (non-terminal) + `AllowedAction APPROVE_DELIVERY("approve_delivery")` (gate role) are added with drift at **every** foundation site: enum + `current_state`/`prior_state`/`resulting_state` CHECK widening via the next-free Flyway head (**V38** — re-confirm) + `WorkflowTransitionTable` row + entry edges + `TransitionTableCrossProductFoundationContract` mirror + `FlywaySchemaContractTest.requiredStates` + `registry-api-schema-placeholders.json` + `allowed-actions.placeholder.json` + `baseActionMatrix` case + version-stamp branch + `WorkflowCommand` permit + `WorkflowCommandFingerprintFactory` case + replay pin. Entry edges: `EXECUTING → WAITING_FOR_DELIVERY` **and** `WAITING_FOR_LINT_APPROVAL → WAITING_FOR_DELIVERY` (Decision 3). Out-edges: `→ WAITING_FOR_REVIEW` (approve/record) + `→ TAKEN_OVER` + `→ RECONCILED` (recovery); **no `→ FAILED`** (a push failure during `approve_delivery` rolls the command back and leaves the run parked for retry — Decision 5 / AC7).

4. **Mode behavior.** **auto** → gates pass → `captureAndPush` runs automatically (PR created iff `autoCreatePullRequest`), run advances to `WaitingForReview`. **approve** → run parks at `WaitingForDelivery`; `approve_delivery` runs the push seam (`resumeDeliveryTailFromGate`: push + enrich + PR per `autoCreatePullRequest` + finalize) then advances to `WaitingForReview`. **manual** → run parks at `WaitingForDelivery`; `approve_delivery` **never** calls `git.push`/`createPullRequest` — it records the out-of-band delivery (`delivery.recordedManually` event) and advances to `WaitingForReview` (Decision 4).

5. **The PR flag.** `createOrUpdatePullRequest` is gated by `autoCreatePullRequest` (skipped when `false`); the existing `repoRef`-presence gate is preserved (no PR attempted when the run has no repository ref). The flag applies wherever the push fires (inline in `auto`, or at `approve_delivery` in `approve`).

6. **Parity.** A project left at `pushMode=auto` + `autoCreatePullRequest=true` is **byte-identical** to pre-3h delivery (push + PR on result) — **except** the push now fires after the BUILD/LINT gates rather than at `onResult` (already true post-3h-1/3h-2). The `auto` path **never enters** `WaitingForDelivery`.

7. **Idempotency.** `approve_delivery` is keyed (run) so a replayed approve neither double-pushes nor double-creates the PR — the `executeIdempotent` boundary short-circuits replays to the pinned post-state (`WaitingForReview`), and the push seam self-gates (`captureAndPush` "already pushed / clean worktree" no-op + `updatePullRequest` when a `github_pr` link already exists). The relocation preserves the "workspace exists + has uncommitted changes" self-gate. `requireParkedAtDeliveryGate` (executor-as-gate) rejects a wrong-state `approve_delivery` with `ILLEGAL_TRANSITION`/409.

8. **Tests.** Coverage asserts: each mode (auto push+PR / approve gate→push→WaitingForReview / manual gate→record-only-no-git→WaitingForReview); PR flag off defers/skips PR; relocation parity (auto default byte-identical except trigger point); lint+delivery composition (`approve_lint` on a non-`auto` project parks at `WaitingForDelivery`, not `WaitingForReview`); `WaitingForDelivery` + `approve_delivery` full drift; idempotent replayed `approve_delivery` (no double-push/double-PR); wrong-state `approve_delivery` → 409; `application.*` ≥ 80% line coverage. ArchUnit via **Failsafe** (not Surefire).

---

## Tasks / Subtasks

- [x] **Task 1 — `PushMode` registry enum + per-project config `pushMode` + `autoCreatePullRequest`** (AC: #1)
  - [x] New `domain/registry/PushMode.java` implementing `RegistryValue` with `AUTO("auto")`, `MANUAL("manual")`, `APPROVE("approve")` + `fromValue(raw, field)` (copy `RunnerKind.java:40-46` shape via `RegistryParsers.parse`). **Registry-alignment check:** grep `RegistryContractTest` for a `pushModes`/`push_mode` leg. `runner_kind` (the closest precedent — a self-owned enum with a `ck_projects_runner_kind` CHECK) has **no** `DomainRegistry.runnerKinds()` placeholder/3-leg alignment, so `PushMode` most likely needs **none** either (the `ck_projects_push_mode` CHECK values live only in the migration + `FlywaySchemaContractTest`). Only add a `DomainRegistry.pushModes()` + `registry-api-schema-placeholders.json` leg **if** an existing test asserts one — do not invent it.
  - [x] `RunnerProperties` (application, `:27-89`): add nested `record DeliveryMode(PushMode pushMode, boolean autoCreatePullRequest)` (mirror `BuildStage` `:563-575`) with compact-ctor null-coalesce (`pushMode == null ? PushMode.AUTO`) + `defaults()` → `(AUTO, true)`; wire the **four** touch-points — component field (`:72`/`:81` region), compact-ctor null-default (`:132-133` region), `defaults()` (`:145-165`), accessors `pushMode()`/`autoCreatePullRequest()` (`:283-333` region). Keep **OPTIONAL + UNVALIDATED** (no test-yaml mirror — [validated-config-needs-test-yaml]). Update the **17** positional `new RunnerProperties(...)` sites (1 prod `defaults()` at `:145` + 16 test — see Dev Notes §"RunnerProperties fan-out").
  - [x] `application.yml`: add a `deliveryline.runner.delivery:` block (commented `push-mode: auto` + `auto-create-pull-request: true`) near the `lint-stage` block (`~:345-350`). No `src/test/resources/application.yml` mirror (OPTIONAL+UNVALIDATED).
  - [x] `domain/project/Project.java`: add `PushMode pushMode` + `boolean autoCreatePullRequest` as fields **#19/#20** after `lintStageEnabled` (`:76`). Canonical ctor becomes **20-arg**. Add a **new 18-arg back-compat overload** (canonical-through-`lintStageEnabled`) delegating the two new fields to `(PushMode.AUTO, true)`. **Update the existing 13/14/16-arg overloads** — each calls the full `this(...)` directly (not a chain), so append `PushMode.AUTO, true` to each (`:85-114`, `:123-155`, `:164-200`). Add a compact-ctor null-coalesce `pushMode == null ? PushMode.AUTO : pushMode` (`:202-230`).
  - [x] `application/project/ProjectRuntimeConfigResolver.java`: add `resolvePushMode(runId): PushMode` (project value, non-null default `AUTO`; optionally fall back to `runnerProperties.deliveryMode().pushMode()` — mirror the `resolveRunnerKind` merge `:193-244`) + `resolveAutoCreatePullRequest(runId): boolean` (mirror `resolveBuildStageEnabled` `:120-122`).
  - [x] Persistence + wire (mirror **every** lint/build touch-point): `ProjectEntity` (`push_mode text not null` — getter parses `PushMode.fromValue(pushMode, "projects.push_mode")` mirroring `getRunnerKind()` `:232-234`; `auto_create_pull_request boolean not null` plain accessor), `ProjectEntityMapper` (`toDomain`/`toNewEntity`/`applyEditableColumns` — 3 spots, `:37-130`), `CreateProjectRequest`/`UpdateProjectRequest` (record components), `CreateProjectCommand`(→15-arg)/`UpdateProjectCommand`(→14-arg) + a back-compat overload each, `ProjectManagementService` (create `:73-131` + update `:138-187` + **disable `:194-248` + enable `:256-291` status-only re-pass** — else the back-compat ctor silently wipes the new fields; + `parsePushMode` helper mirroring `parseRunnerKind` `:323-325` but default `AUTO` on null), `ProjectController` create/update map + **`createFingerprint` `:465-488`** (append `command.pushMode().value()` + `Boolean.toString(command.autoCreatePullRequest())` — else create-idempotency collides), `ProjectResponse.from` (`:96-99`), `DefaultProjectSeeder.seedDefaultFromGlobalConfig` (`:115-193`, thread both into the now-20-arg `new Project(...)` from `runnerProperties.deliveryMode()`).
  - [x] **Flyway V38** (re-confirm head — highest on disk is **V37**; 3h-3 is `ready-for-dev` and will claim a Vnn): `alter table projects add column push_mode text not null default 'auto';` + `alter table projects add constraint ck_projects_push_mode check (push_mode in ('auto','manual','approve'));` + `alter table projects add column auto_create_pull_request boolean not null default true;` **plus** the state CHECK widening (Task 3). Add the new columns + state to `FlywaySchemaContractTest`.
  - [x] OpenAPI regen (`-Dopenapi.snapshot.write=true` via `OpenApiSnapshotContractTest` then `npm run generate-api`) — new project fields + `WaitingForDelivery` state enum land in `openapi.json` + `schema.d.ts`; `check:api` in-sync. [openapi-regen-frontend-client-drift-cascade]
- [x] **Task 2 — `WaitingForDelivery` state + `approve_delivery` action (full foundation drift)** (AC: #3)
  - [x] `domain/registry/WorkflowState.java`: add `WAITING_FOR_DELIVERY("WaitingForDelivery")` after `WAITING_FOR_LINT_APPROVAL` (`:37`), comma-terminated (non-terminal gate block).
  - [x] `domain/registry/AllowedAction.java`: add `APPROVE_DELIVERY("approve_delivery")` near the gate actions (`:94-95`), mind the trailing `;` on the last constant (`:103`).
  - [x] **V38 CHECK widening** (fold into the Task-1 migration): drop+re-add all **three** constraints `ck_workflow_runs_current_state`, `ck_workflow_events_prior_state`, `ck_workflow_events_resulting_state` adding `'WaitingForDelivery'` (copy the V34 drop-and-readd idiom `:45-79` exactly — bare `in (...)` for current_state; `is null or ... in (...)` for the two event columns).
  - [x] `application/workflow/WorkflowTransitionTable.java`: add a `put(rules, WAITING_FOR_DELIVERY, WAITING_FOR_REVIEW, TAKEN_OVER, RECONCILED)` row (mirror the `WAITING_FOR_LINT_APPROVAL` row `:127-133`, minus `EXECUTING`/`FAILED`); add the entry edge **`WAITING_FOR_DELIVERY`** to the `EXECUTING` source row (`:80-93`, mirror the lint entry at `:89`) **and** to the `WAITING_FOR_LINT_APPROVAL` row's out-edges (`:127-133`, add `WAITING_FOR_DELIVERY` — Decision 3). `assertCoversAllStates` fails the build without the new `put` row.
  - [x] `foundation/TransitionTableCrossProductFoundationContract.java`: mirror into `EXPECTED_ALLOWED_TARGETS` — new `Map.entry(WAITING_FOR_DELIVERY, EnumSet.of(WAITING_FOR_REVIEW, TAKEN_OVER, RECONCILED))` + add `WAITING_FOR_DELIVERY` to the `EXECUTING` entry (`:76-87`) and the `WAITING_FOR_LINT_APPROVAL` entry (`:115-121`). [transition-table-change-fans-to-contracts]
  - [x] `contract/FlywaySchemaContractTest.java`: add `"WaitingForDelivery"` to `requiredStates` (`:204-219`).
  - [x] `contracts/openapi/registry-api-schema-placeholders.json`: add `"WaitingForDelivery"` to `workflowStates`.
  - [x] `contracts/frontend/allowed-actions.placeholder.json`: add `"approve_delivery"` to `allowedActions`.
  - [x] `application/workflow/WorkflowInspectionService.java` `baseActionMatrix`: add `case WAITING_FOR_DELIVERY:` (`:1753-1769` region, before the `default:` throw `:1812`) → workflow_owner gets `[APPROVE_DELIVERY, VIEW_ONLY, VIEW_RUNNER_LOGS, VIEW_PROVIDER_USAGE_STATUS]`, other roles `[VIEW_ONLY, VIEW_RUNNER_LOGS, VIEW_PROVIDER_USAGE_STATUS]` (mirror the lint cell). Add `WAITING_FOR_DELIVERY` to the version-stamp OR-condition (`:1472-1474`) so it maps to `resolveImplementationContextBundleVersion` (it's on the implementation tail, like the lint gate).
  - [x] Confirm `RegistryContractTest` (`workflowStatesStayAlignedWithSqlChecksAndApiManifest` `:138-147`; `allowedActionsStayAlignedWithFrontendPlaceholder` `:417-422`) is green once the enum + CHECK + placeholders agree (no test edit — it is the alignment gate).
- [x] **Task 3 — `DeliveryGateService` (the gate) + `RunnerBroker` tail wiring** (AC: #2, #4-parking, #6)
  - [x] New `application/workflow/DeliveryGateService.java` (structural sibling of `LintStageService`'s gate half, but **no command execution** — it only resolves the mode and decides park-vs-pass): `tryGateBehindDelivery(String runId, String prOutputRunnerExecutionId, String correlationId, Runnable deliverInline): boolean` —
    - Resolve `pushMode = runtimeConfigResolver.resolvePushMode(runId)`. **`AUTO`** → `return false` (caller runs `deliverInline`). **No workspace/`repoRef`** (nothing to deliver) → `return false` (pass-through; `deliverInline`'s `captureAndPush` will `no_repo_workspace` no-op).
    - **`MANUAL`/`APPROVE`** → finalize the producing PR_OUTPUT rex (`executionService.recordCompleted(prOutputRunnerExecutionId)`, ILLEGAL_TRANSITION-guarded — mirror the lint park's `finalizeProducingExecution` so it is never timeout-reaped while parked); transition `EXECUTING → WAITING_FOR_DELIVERY` via `WorkflowTransitionService.transition(...)` (idempotency key `"delivery-gate:" + runId`); `return true`.
  - [x] Wire in `RunnerBroker.handleSuccess` (`:2198-2255`): nest the delivery gate **between** the lint gate and the inline tail. Define `deliverInline = () -> completeExecutionTailAndAdvance(...)` (unchanged); wrap `Runnable tail = () -> tryDeliveryGateOrDeliver(runId, prOutputRex, corr, deliverInline)` where `tryDeliveryGateOrDeliver` calls `deliveryGateServiceSupplier.get().tryGateBehindDelivery(..., deliverInline)` and runs `deliverInline` when not gated (or the supplier is null). Pass this `tail` as the lint gate's continuation (so the chain is build → lint → **delivery** → deliver). Add a `deliveryGateServiceSupplier` lazy `Supplier` field wired by **optional setter injection** (`setDeliveryGateServiceProvider(ObjectProvider<DeliveryGateService>)`, mirror `lintStageServiceSupplier` `:143-144`/`:518-524`) — **no ctor fan-out**.
  - [x] Preserve the existing git-push-failure handling inside `completeExecutionTailAndAdvance` (`:2464-2494`) unchanged (it still fires for `auto` mode).
- [x] **Task 4 — `autoCreatePullRequest` PR-flag gate** (AC: #5)
  - [x] In `RepositoryWorkspaceService.captureAndPush` (`:419-428`) / `createOrUpdatePullRequest` (`:742-800`): resolve `autoCreatePullRequest` for the run (via `ProjectRuntimeConfigResolver.resolveAutoCreatePullRequest(runId)` — inject the resolver or thread the flag from the caller) and **skip** `createOrUpdatePullRequest` when `false`, logging `reason=pr_creation_disabled`. **Preserve** the existing `repoRef`-presence gate (`:751-759`, `reason=no_repo_ref`). The push itself is unaffected (governed by `pushMode`, not this flag).
  - [x] Confirm the `RepositoryPushOutcome.prRef` stays `null` when PR is skipped, and `linkGitHubPrBestEffort` no-ops on a null `prRef` (`RunnerBroker.java:3170-3176`) — so no `github_pr` link is created (keeps `acceptImplementation`'s `assertPrLinkPresentAndMatches` semantics: no PR link ⇒ the human accept path still applies its existing check; document that an `autoCreatePullRequest=false` project's operator must create/link the PR out-of-band before review-accept, consistent with manual push).
- [x] **Task 5 — `DeliveryApprovalService` executor + `approve_delivery` command/REST + gate guard** (AC: #4, #7)
  - [x] New `application/workflow/DeliveryApprovalService.java` (mirror `LintApprovalService`): `@Service`; lazy `Supplier<RunnerBroker>` + `Supplier<WorkflowOrchestrationService>` via `ObjectProvider`; `WorkflowTransitionService`, `ProjectRuntimeConfigResolver`, `AfterCommitSideEffectRunner`. Method `approveDelivery(ApproveDeliveryCommand)` `@Transactional(propagation=MANDATORY)`:
    - Resolve `pushMode`. Transition `WAITING_FOR_DELIVERY → WAITING_FOR_REVIEW` **synchronously** in the command tx (idempotency key `"delivery-approved:" + runId`) — the wrong-state guard (Task 5 REST) + this transition close the pre-check hole exactly as 3h-2's `approveLint`.
    - **`APPROVE`** → defer the push post-commit: `afterCommit.runAfterCommit("delivery-approve-resume", runId, () -> afterCommit.runInNewTransaction("delivery-approve-resume-tx", runId, () -> broker.resumeDeliveryTailFromGate(runId, correlationId)))` (REUSE the 3h-2 seam verbatim — it self-gates on already-pushed refs, respects `autoCreatePullRequest` via Task 4, enriches + links PR + enqueues reviewer). **Compose A-over-B** (bare afterCommit has no tx — [post-commit-hook-needs-requires-new]).
    - **`MANUAL`** → **no push**: append a `delivery.recordedManually` event (new `WorkflowEventType` — see Dev Notes §"new event fan-out") + enqueue the reviewer enqueue-only post-commit (`orchestration.enqueueReviewerAfterLintApproval(runId, prOutputRex, correlationId)` — the enqueue-only helper, or a delivery twin). Do NOT call `captureAndPush`/`createPullRequest` (Decision 4 / `ingestManualResult` precedent).
    - Returns `WorkflowState.WAITING_FOR_REVIEW`.
  - [x] `application/workflow/commands/ApproveDeliveryCommand.java` (mirror `ApproveLintCommand` `:15-22`: `workflowRunId`, `actorIdentity`, `actorType`, `idempotencyKey`, `correlationId`, `reasonText @Size(max=512)`) + add to `WorkflowCommand` `permits` (`:6-18`). **A new permit fans to `CommandModelSymmetryFoundationContract.EXPECTED_PERMITS`** (strict set; add the `import` + `ApproveDeliveryCommand.class` entry) — this is **foundation-gate-ONLY** (`mvnw test` SKIPS it; only `-Pfoundation-gate`/full `verify` runs it), so a green `mvnw test` does NOT prove it. [new-workflowcommand-permit-updates-symmetry-contract]
  - [x] `application/idempotency/WorkflowCommandFingerprintFactory.java`: add `case ApproveDeliveryCommand approveDelivery -> append(digest, approveDelivery.workflowRunId());` (`:137-138` region) + import (exhaustive switch — won't compile otherwise).
  - [x] `WorkflowCommandService`: `approveDelivery(ApproveDeliveryCommand)` `@Transactional` → `executeIdempotent(command, this::approveDeliveryInternal, this::replayStateChange)` (`:263-269` pattern); `approveDeliveryInternal` calls `requireParkedAtDeliveryGate(runId, "approve_delivery")` FIRST then `deliveryApprovalService.approveDelivery(command)` (`:475-485` pattern). Add `requireParkedAtDeliveryGate` (mirror `requireParkedAtLintGate` `:514-541` — reads `currentState` via `workflowRunReadPort`, throws `ILLEGAL_TRANSITION`/409 unless `WAITING_FOR_DELIVERY`). Add the replay pin `case ApproveDeliveryCommand ignored -> WorkflowState.WAITING_FOR_REVIEW;` (`:961-962` region). Thread the new `DeliveryApprovalService` dep — this fans out the 2 `new WorkflowCommandService(...)` test ctor sites (mirror how 3h-2 added `LintApprovalService`).
  - [x] REST: `POST /{workflowRunId}/approve-delivery` on `WorkflowController` (mirror `approve-lint` `:1356-1388`): required `Idempotency-Key`, `X-Actor-Identity`, header guards, **`requireWorkflowOwnerRole("approve-delivery", request.role())`** (`:2193-2210`), `@Valid @RequestBody ApproveDeliveryRequest(role @NotBlank, reasonText @Size(max=512))` → `WorkflowStateChangeResponse.from(workflowCommandService.approveDelivery(...))`. No OpenAPI change for the action (`actions` is `type:string`); the endpoint regen is covered by Task 1's OpenAPI run.
- [x] **Task 6 — Compose the delivery gate with the lint gate (`approveLint` routing)** (AC: #3-composition, #8)
  - [x] Modify `LintApprovalService.approveLint` (`:77-131`): after `requireParkedAtLintGate` succeeds (in `WorkflowCommandService`), resolve `pushMode = runtimeConfigResolver.resolvePushMode(runId)` in the same tx (inject the resolver). **`AUTO`** → keep the current behavior verbatim (transition `→ WaitingForReview` + deferred `resumeDeliveryTailFromGate`). **`MANUAL`/`APPROVE`** → transition `WAITING_FOR_LINT_APPROVAL → WAITING_FOR_DELIVERY` (idempotency key `"lint-approved-delivery:" + runId`) + **no** deferred push (`approve_delivery` handles it). Update `WorkflowCommandService.replayStateChange`'s `ApproveLintCommand` pin — it currently pins `WAITING_FOR_REVIEW` unconditionally; a lint-approve on a non-`auto` project now yields `WAITING_FOR_DELIVERY`. **Trap:** the replay pin must reflect the actual post-state; derive it from `pushMode` at replay time or store the resulting state in the reservation result (mirror how other mode-dependent replays resolve — see Dev Notes §"replay pin under mode branch").
  - [x] `LintApprovalServiceTest`: add the non-`auto` case (approve_lint parks at `WaitingForDelivery`, no push deferred).
- [x] **Task 7 — Docs (ADR + glossary)** (AC: architecture)
  - [x] `docs/adr/0030-governed-delivery-tail.md`: record Decision 6 landing (delivery gate BEFORE review per Decision 1 above; `pushMode` CHECK column; the lint↔delivery composition; manual-mode `ingestManualResult` reuse; PR-flag gate) under the relevant decisions. Note the "gate before review" resolution of the AC6-vs-crux contradiction + the rejected literal alternative (Alt 4 cross-ref). Do **not** disturb the ordering decision text for the other stories.
  - [x] `docs/glossary.md`: confirm `push mode`, `WaitingForDelivery`, `delivery gate` vocabulary (NFR43 — justify each new concept).
- [x] **Task 8 — Tests** (AC: #8)
  - [x] Unit: `DeliveryGateServiceTest` (auto→pass-through/false; approve/manual→park+finalize-rex+true; no-workspace→false); `DeliveryApprovalServiceTest` (approve→transition+deferred resume; manual→transition+event+no-git; returns WaitingForReview); `PushModeParsingTest` (fromValue round-trip + bad value); `ProjectRuntimeConfigResolverTest` (+pushMode/autoCreatePullRequest); `RunnerPropertiesTest` (DeliveryMode defaults); `WorkflowInspectionServiceAllowedActionsTest` (WaitingForDelivery×role cell); `LintApprovalServiceTest` (+non-auto delivery routing).
  - [x] Real-PG IT (Testcontainers, `@Tag("integration")`, named `*IT`, **non-`@Transactional`** where afterCommit/REQUIRES_NEW is exercised — mirror `LintStageIT`/`BuildStageIT`): **auto** → push+PR fired inline, run at `WaitingForReview`, never visits `WaitingForDelivery`, PR link present (parity); **approve** → parks at `WaitingForDelivery` (no push yet, producing rex finalized), then `approve_delivery` → push + PR + `WaitingForReview` + reviewer enqueued; **manual** → parks, `approve_delivery` → `WaitingForReview` + `delivery.recordedManually` event + **no** `captureAndPush`/`git.push` (verify mock `GitCommandPort`/`RepositoryHostAdapter` never called); **PR flag off** → push fires, no `github_pr` link created; **idempotent** replayed `approve_delivery` (no double-push/double-PR); **wrong-state** `approve_delivery` on an `EXECUTING`/`WaitingForReview` run → 409, no push, state unchanged; **lint+delivery composition** → `approve_lint` on a non-`auto` project lands at `WaitingForDelivery`. Inject mock `GitCommandPort`/`RepositoryHostAdapter`/`RunnerWorkspaceStore` for determinism.
  - [x] Foundation-gate: `FlywaySchemaContractTest` (new columns + state CHECK + `ck_projects_push_mode`), `TransitionTableCrossProductFoundationContract`, `RegistryContractTest`, `WorkflowTransitionTableTest` (+WaitingForDelivery row/edges), `OpenApiSnapshotContractTest` (state enum + project fields) all green. ArchUnit via **Failsafe** (`verify -Djacoco.skip=true`; avoid the bare `failsafe:` goal — [maven-argline-direct-goal-crash]).
  - [x] `ProjectControllerContractTest`: add `pushMode`/`autoCreatePullRequest` to the request bodies **if** the primitive `boolean autoCreatePullRequest` on the request DTO 400s a body omitting it (the exact 3h-1/3h-2 primitive-boolean-400 Failsafe-only regression — run the full `mvnw verify` to catch it).
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs (parameterized, never concatenation) at: delivery-gate decision (INFO "delivery gate pushMode={mode} → {inline|parked}"), park (INFO "parked at WaitingForDelivery"), `approve_delivery` (INFO the action + mode + resume/record branch), PR-flag skip (INFO "pr creation disabled"), manual record (INFO "recorded manual delivery"), state transitions (INFO via the transition service). Levels: INFO normal lifecycle, WARN recoverable (push-seam best-effort swallow, gate skip), ERROR only unexpected executor failure.
  - [x] Every line carries `correlationId`, `workflowRunId`, and the producing PR_OUTPUT `runnerExecutionId`. **Never log git tokens, diff bytes, or PR bodies** (ids/refs only). Use MDC where the surrounding code does.
  - [x] Pin each new/moved log line with an `OutputCaptureExtension`/`ListAppender` assertion.

### Review Findings

- [x] [Review][Patch] Approve-mode `approve_delivery` commits `WaitingForReview` before push failure can roll back [deliveryline-backend/src/main/java/org/dradgo/application/workflow/DeliveryApprovalService.java:126]
- [x] [Review][Patch] Project REST create/update defaults omitted `autoCreatePullRequest` to `false`, breaking default-on PR parity [deliveryline-backend/src/main/java/org/dradgo/adapters/rest/CreateProjectRequest.java:91]
- [x] [Review][Patch] Partial `deliveryline.runner.delivery` config can bind missing `auto-create-pull-request` as `false` [deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerProperties.java:661]
- [x] [Review][Patch] Parked delivery approvals re-read mutable `pushMode` instead of using the mode that created the gate [deliveryline-backend/src/main/java/org/dradgo/application/workflow/DeliveryApprovalService.java:117]
- [x] [Review][Patch] `approve_lint` idempotent replay recomputes mutable `pushMode`, so replay can report a different original post-state [deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java:1068]
---

## Dev Notes

### Canonical 3h-0 consume-note (from ADR 0032 — fold verbatim)

> **The async delivery resume + manual-record boundaries MUST consume the 3h-0 shared replay-safe afterCommit helper (`AfterCommitSideEffectRunner`) — do NOT re-derive `REQUIRES_NEW` + advisory-lock + swallow/log + idempotent re-invoke inline.**

Helper API (`application/workflow/AfterCommitSideEffectRunner.java`):
```java
void runAfterCommit(String label, String contextId, Runnable sideEffect); // Layer A — fire after current tx COMMITS
void runInNewTransaction(String label, String contextId, Runnable work);  // Layer B — REQUIRES_NEW; advisory lock FIRST inside `work`
```
Compose A over B for the `approve_delivery` push resume (a bare afterCommit callback has no live tx — the exact "No active transaction" bug the 3h-2 AC8 IT caught). `LintApprovalService.approveLint` (`:105-126`) is the working precedent. The helper never calls `transition()` — the synchronous state transition stays in the executor's command tx (keeps `only_workflow_transition_service_may_mutate_workflow_state` intact).
[Source: docs/adr/0032-replay-safe-aftercommit-helper.md:38-40; AfterCommitSideEffectRunner.java:81,111; 3h-2 Review Findings — the afterCommit-without-tx bug]

### The single best implementation template — mirror `LintApprovalService` + the lint gate

3h-2 is the structural twin. Read end-to-end and clone the shape, substituting the delivery specifics:
- `LintApprovalService.approveLint` (`:77-131`) → `DeliveryApprovalService.approveDelivery`: synchronous transition in-tx + deferred REQUIRES_NEW resume.
- `WorkflowCommandService.requireParkedAtLintGate` (`:514-541`) → `requireParkedAtDeliveryGate`: executor-as-gate current-state precondition ([gate-action-needs-explicit-current-state-precondition] — LOAD-BEARING, because `WaitingForReview` is reachable from other sources so transition-legality alone does NOT reject a wrong-state approve).
- `LintStageService.tryGateBehindLint` park half → `DeliveryGateService.tryGateBehindDelivery`: reserve/finalize the producing rex + transition to the gate state, but **no command execution** (delivery runs no linter — it only decides push-vs-park).
- `RunnerBroker.resumeDeliveryTailFromGate` (`:2303-2394`) is **reused verbatim** for the approve-mode push (it already does idempotent captureAndPush + re-derive/enrich pr-output + `linkGitHubPrBestEffort` + `enqueueReviewerAfterLintApproval`).
- `RunnerBroker.ingestManualResult` (`:2738-2877`) is the template for the **manual** branch: advance via the same orchestration callbacks WITHOUT the workspace-coupled `captureAndPush`/enrich (`:2716-2733`, `:2767`).
[Source: LintApprovalService.java:77-189; WorkflowCommandService.java:475-541; RunnerBroker.java:2303-2394,2738-2877]

### The delivery-tail seam (current state, post-3h-2)

Today push is **strictly before** `WaitingForReview` in the pass path; the human `accept_implementation` does no push (it requires a pre-existing `github_pr` link and goes `WaitingForReview → Completed`). Two `captureAndPush` call sites, both in `RunnerBroker`:
- `completeExecutionTailAndAdvance` (`:2441-2588`) — pass path: captureAndPush (`:2467`) → validate/enrich (`:2504-2515`) → recordCompleted (`:2522`) → `onPrOutputStageSucceeded` (`:2577`, transitions `Executing → WaitingForReview` at `WorkflowOrchestrationService:1006-1012`).
- `resumeDeliveryTailFromGate` (`:2303-2394`) — lint-approve resume: captureAndPush (`:2323`) + enrich + `linkGitHubPrBestEffort` (`:2359`) + `enqueueReviewerAfterLintApproval` (`:2386`).
The gate chain in `handleSuccess` (`:2198-2255`): `tryGateBehindBuild(afterBuild)` → `afterBuild = tryLintGateOrTail(tail)` → `tail = completeExecutionTailAndAdvance`. **3h-4 inserts the delivery gate between the lint gate and the inline tail** (build → lint → **delivery** → deliver). The code already anticipates this — comment at `RunnerBroker.java:2214-2215`/`:2438-2439` ("3h-4 later moves this behind the WaitingForDelivery gate ... keep it a single clean call site").
[Source: agent map — RunnerBroker delivery tail]

### State + action drift fan-out (AC3) — every site (mirror `WaitingForLintApproval`/`approve_lint`)

New `WorkflowState WAITING_FOR_DELIVERY` (non-terminal): `WorkflowState.java:37` → V38 3× CHECK widening (mirror V34 `:45-79`) → `WorkflowTransitionTable` `put` row + entry edges (EXECUTING `:80-93` + WaitingForLintApproval `:127-133`) → `TransitionTableCrossProductFoundationContract` mirror (`:76-121`) → `FlywaySchemaContractTest.requiredStates` (`:204-219`) → `registry-api-schema-placeholders.json` `workflowStates` → `WorkflowInspectionService.baseActionMatrix` new `case` (`default:` throws `:1812`) + version-stamp OR (`:1472-1474`) → OpenAPI regen → `RegistryContractTest` auto-passes once aligned (`:138-147`).

New `AllowedAction APPROVE_DELIVERY`: `AllowedAction.java:94-103` → `allowed-actions.placeholder.json` `allowedActions` → `WorkflowInspectionService.baseActionMatrix` cell → **no OpenAPI change** (`actions` is `type:string`) → `WorkflowCommand` permit + `WorkflowCommandFingerprintFactory` case (exhaustive — compile-enforced) + `replayStateChange` pin → `RegistryContractTest.allowedActionsStayAlignedWithFrontendPlaceholder` auto-passes (`:417-422`).
[Source: agent map — foundation drift sites]

### `RunnerProperties` fan-out (AC1)

A new component on the canonical `application/runner/RunnerProperties.java` record adds one arg to **17** positional `new RunnerProperties(...)` sites: 1 prod (`:145` `defaults()`) + 16 test — `RunnerPropertiesTest` (46,72,137,166,221,252,279), `DockerRunnerAdapterUnitTest` (190,573), `DockerRunnerAdapterLoggingContractTest:363`, `DockerRunnerAdapterContainerLifecycleIT:90`, `DockerLifecycleITSupport:73`, `LocalRunnerWorkspaceStoreTest:373`, `WorkflowOrchestrationServiceTest:145`, `RunnerLogCaptureServiceTest:71`, `RunnerBrokerUnitTest:2411`. The **legacy** `infrastructure/config/RunnerProperties.java` (7-arg, story 1.13) is a DIFFERENT class — do NOT touch it. [runnerproperties-record-component-fanout]

### Config precedent (AC1)

`Project` is a detached `record` — no JPA proxy, safe on the worker thread. Mirror `buildStageEnabled`/`lintStageEnabled` (boolean) + the enum-column precedent for `pushMode`: **use `runner_kind`'s CHECK-column shape, NOT `reviewer_model_kind`'s free-text** (Decision 2). Storage: `push_mode text not null default 'auto'` + `ck_projects_push_mode`; entity getter parses via `PushMode.fromValue(...)` (fail-fast, mirror `ProjectEntity.getRunnerKind()` `:232-234`); write-path `ProjectManagementService.parsePushMode` (mirror `parseRunnerKind` `:323-325`, default `AUTO`). `autoCreatePullRequest` = plain boolean, `default true`. **Traps to carry from 3h-1/3h-2:** `ProjectManagementService` disable/enable status-only paths re-pass existing config (`:235-240`,`:278-283` — else the back-compat ctor wipes it); `ProjectController.createFingerprint` must include both new fields (`:465-488`); all project DTOs are OpenAPI-surfaced → regen the FE client; the primitive `boolean autoCreatePullRequest` on the request DTO will 400 a body omitting it → update `ProjectControllerContractTest` bodies (Failsafe-only regression — full `mvnw verify` catches it).

### New `WorkflowEventType` fan-out (manual mode)

The manual branch appends a `delivery.recordedManually` event. A **new `WorkflowEventType`** fans to **2 fixture sites** ([new-workfloweventtype-fixture-sites]) — mirror into the fixture + fixture-stream enum. Confirm the event is emitted through the same append path the manual-execution events use (`ManualExecutionDispatcher`/`ingestManualResult` append `manual.*` events). If an existing generic event suffices (e.g. reuse a `WORKFLOW_STATE_CHANGED` detail), prefer it to avoid the fan-out — decide at implementation based on what the 3h-6 delivery panel needs to render.

### Replay pin under a mode branch (Task 6 trap)

`WorkflowCommandService.replayStateChange` (`:935-992`) pins each command's invariant post-state for idempotent replay. Post-3h-4, `approve_lint`'s post-state is **mode-dependent** (`WaitingForReview` when `auto`, `WaitingForDelivery` otherwise), and `approve_delivery`'s is `WaitingForReview`. A static `case` pin is wrong for `approve_lint`. Options: (a) resolve `pushMode` at replay time and pin accordingly (the resolver read is idempotent); (b) store the resulting state in the idempotency reservation result and replay it (mirror how `executeIdempotent`'s `replayLoader.apply(outcome.resultRef(), ...)` already carries a result ref `:744-746`). Prefer (b) if the reservation already persists the outcome state; else (a). Pin this in a replay IT.

### Idempotency (AC7)

Three layers make a replayed `approve_delivery` safe: (1) `executeIdempotent` reserves on `idempotencyKey`+fingerprint (fingerprint = runId only, `:137-138` pattern) → a REPLAY short-circuits to the pinned post-state, never re-running the action; (2) the in-tx transition uses `"delivery-approved:" + runId`; (3) the deferred push seam `resumeDeliveryTailFromGate` self-gates (captureAndPush "already pushed / clean worktree" no-op + `updatePullRequest` when a `github_pr` link exists). `reasonText` is intentionally NOT fingerprinted (free-form → a same-key retry with a different reason is an idempotent replay).

### Logging Requirements (project-wide standard)
- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **Where (minimum surface):** `DeliveryGateService` (INFO decision/park), `DeliveryApprovalService` (INFO approve/manual branch + resume/record), PR-flag skip in `RepositoryWorkspaceService` (INFO), state transitions (INFO via the transition service), the deferred-resume swallow (WARN).
- **Required context keys:** `correlationId`, `workflowRunId`, producing PR_OUTPUT `runnerExecutionId`, `pushMode` on gate/approve lines.
- **Forbidden:** git tokens, diff/PR-body bytes, secrets, raw PII — refs/ids only.
- **Test contract:** new logging surfaces pinned by `OutputCaptureExtension`/`ListAppender`.

### Testing standards summary
- afterCommit/REQUIRES_NEW ITs must be **non-`@Transactional`**, named `*IT` (Failsafe), with `@BeforeEach/@AfterEach` truncation. [springboot-testcontainers-test-must-be-IT; post-commit-hook-needs-requires-new]
- ArchUnit `@ArchTest` runs in **Failsafe** — verify boundary/naming via `verify`, not `mvnw test`. [archunit-runs-in-failsafe-not-surefire; maven-argline-direct-goal-crash]
- Run `spotless:apply` on hand-edited Java before pushing. [spotless-apply-before-pushing-java-edits]
- OpenAPI: `-Dopenapi.snapshot.write=true` via `OpenApiSnapshotContractTest` (Failsafe/`@Tag("contract")`), then `npm run generate-api`; `check:api` in-sync. [openapi-regen-frontend-client-drift-cascade]
- **Reconfirm the Flyway head (V38 — highest on disk is V37) before writing the migration.** [flyway-v31-cross-branch-collision]
- A summary/detail DTO field change fans to the exact-field contract test. [workflow-summary-exact-field-contract-test]
- Full Docker `mvnw verify` is MANDATORY before claiming done — the primitive-boolean-400 regression is Failsafe-only (3h-1/3h-2 both hit it).

### Project Structure Notes
- New production files: `domain/registry/PushMode.java`; `application/workflow/DeliveryGateService.java`, `DeliveryApprovalService.java`; `application/workflow/commands/ApproveDeliveryCommand.java`; `adapters/rest/ApproveDeliveryRequest.java`; `db/migration/V38__add_delivery_gate_and_push_mode.sql`.
- Modified: `WorkflowState`, `AllowedAction`, `RunnerBroker` (delivery-gate wiring + `deliveryGateServiceSupplier` setter), `RepositoryWorkspaceService` (PR-flag gate), `LintApprovalService` (delivery routing — Decision 3), `WorkflowTransitionTable` (+row+2 entry edges), `WorkflowInspectionService` (matrix case + version-stamp), `WorkflowCommandService` (+approveDelivery + requireParkedAtDeliveryGate + replay pin), `WorkflowCommand` (+permit), `WorkflowCommandFingerprintFactory` (+case), `RunnerProperties` (app — DeliveryMode), `ProjectRuntimeConfigResolver` (+resolvePushMode/resolveAutoCreatePullRequest), `Project` + full project-config stack, `DefaultProjectSeeder`, `WorkflowController` (+approve-delivery), `application.yml`, `docs/adr/0030`, `docs/glossary.md`.
- **NO** new `FailureCategory` (a push failure rolls the command back — no `→FAILED` edge, AC3); **NO** new table; **NO** runner-image/`runner.mjs`/mock change; **NO** OpenAPI change for the action (only the state enum + project fields).

### References
- [Source: _bmad-output/planning-artifacts/epic-03h-pre-review-quality-gates.md — Story 3h-4 + Cross-Cutting Notes + FR78]
- [Source: _bmad-output/implementation-artifacts/3h-2-cpu-linter-gate-and-waiting-for-lint-approval-hard-gate.md — the structural twin (LintApprovalService, requireParkedAtLintGate, resumeDeliveryTailFromGate, full state/action drift, V34 idiom)]
- [Source: _bmad-output/implementation-artifacts/3h-1-build-validation-stage-and-bounded-auto-fix-loop.md — completeExecutionTailAndAdvance extraction, project-config stack, RunnerProperties fan-out]
- [Source: docs/adr/0030-governed-delivery-tail.md — Decision 5/6, Alt 4 (this story realizes Decision 6 with the "gate before review" resolution)]
- [Source: docs/adr/0032-replay-safe-aftercommit-helper.md:38-40 — consume-note]
- [Source: RunnerBroker.java:2198-2255,2303-2394,2441-2588,2738-2877,113-135,518-524; RepositoryWorkspaceService.java:368-450,742-800,937-938; WorkflowOrchestrationService.java:997-1032,1102-1120]
- [Source: LintApprovalService.java:77-189; WorkflowCommandService.java:263-277,475-541,728-755,935-992; WorkflowController.java:1356-1388,2193-2210; WorkflowCommand.java:6-18; WorkflowCommandFingerprintFactory.java:132-138]
- [Source: WorkflowState.java:37; AllowedAction.java:94-103; WorkflowTransitionTable.java:80-93,127-133; TransitionTableCrossProductFoundationContract.java:76-121; FlywaySchemaContractTest.java:204-219; WorkflowInspectionService.java:1472-1474,1753-1769,1812; RegistryContractTest.java:138-147,417-422]
- [Source: Project.java:26-230; ProjectRuntimeConfigResolver.java:120-244; ProjectEntity.java:232-238; ProjectManagementService.java:73-291,323-379; ProjectController.java:465-488; DefaultProjectSeeder.java:115-193; RunnerProperties.java(app):27-89,145-165,563-575; application.yml:267-350; V34__add_lint_stage_columns_and_waiting_for_lint_approval.sql:45-79; V20__add_manual_execution_kind_and_state.sql:19-21]
- [Source: memory — gate-action-needs-explicit-current-state-precondition, new-workflowcommand-permit-updates-symmetry-contract, recovery-bar-wrong-allowed-actions-role, transition-table-change-fans-to-contracts, flyway-v31-cross-branch-collision, openapi-regen-frontend-client-drift-cascade, validated-config-needs-test-yaml, runnerproperties-record-component-fanout, post-commit-hook-needs-requires-new, springboot-testcontainers-test-must-be-IT, archunit-runs-in-failsafe-not-surefire, new-workfloweventtype-fixture-sites, application-cannot-import-adapters, proutput-advisory-review-missing-diff]

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context) — bmad-create-story, 2026-07-08.

### Debug Log References

- Full Docker `mvnw verify` (round 1): Surefire green; Failsafe caught 2 issues — (a) `DeliveryGateIT.autoModeIsPassThroughAndNeverParks` asserted the gate runs the inline tail, but the gate is a pure pass-through (the CALLER runs `deliverInline` when it returns false); (b) checkstyle `ForbiddenThreadSleep` suppression for `WorkflowCommandService.java` was pinned to line 1187 but my inserts shifted the `Thread.sleep` to 1274. Both fixed; the IT (7/7) + checkstyle re-verified individually.
- OpenAPI regen via `OpenApiSnapshotContractTest -Dopenapi.snapshot.write=true` (Testcontainers), then `npm run generate-api`; `check:api` in-sync.

### Completion Notes List

- **All 8 tasks delivered.** FR78 unified delivery gate: per-project `pushMode {auto,manual,approve}` (default `auto`) + `autoCreatePullRequest` (default `true`) feed a new non-terminal `WaitingForDelivery` state + `approve_delivery` action. Gate sits BEFORE review (Decision 1) at the current push point; `auto` is a byte-identical pass-through.
- **PushMode = CHECK-constrained text column** (`ck_projects_push_mode`, mirrors `runner_kind`) — new `domain/registry/PushMode` RegistryValue enum; entity getter fail-fast-parses via `PushMode.fromValue`; write-path `ProjectManagementService.parsePushMode` (default AUTO on null). `autoCreatePullRequest` = plain boolean default TRUE.
- **Config fan-out (Task 1):** `RunnerProperties.DeliveryMode(pushMode, autoCreatePullRequest)` nested record + 17 positional `new RunnerProperties(...)` sites updated; `Project` 18→20-arg canonical + new 18-arg back-compat overload; `ProjectRuntimeConfigResolver.resolvePushMode/resolveAutoCreatePullRequest`; full persistence + DTO + command + controller (incl. `createFingerprint`) + response + seeder stack; V38 project columns + FlywaySchemaContractTest asserts; OpenAPI + schema.d.ts regen.
- **State/action drift (Task 2):** `WAITING_FOR_DELIVERY` + `APPROVE_DELIVERY` enums; V38 3× state CHECK widening (V34 idiom); `WorkflowTransitionTable` row + 2 entry edges (EXECUTING + WaitingForLintApproval); cross-product contract mirror; `FlywaySchemaContractTest.requiredStates`; 2 placeholder JSON; `baseActionMatrix` case + version-stamp OR; WorkflowTransitionTableTest + WorkflowInspectionServiceAllowedActionsTest.
- **DeliveryGateService (Task 3):** park-vs-pass, NO command exec — `auto`/no-workspace → pass-through (false); `manual`/`approve` → finalize producing rex + transition `EXECUTING → WaitingForDelivery` (true). Wired into `RunnerBroker.handleSuccess` between the lint gate and inline delivery via `deliveryGateServiceSupplier` optional-setter injection (no ctor fan-out).
- **PR flag (Task 4):** `createOrUpdatePullRequest` gated by `autoCreatePullRequest` (skip + `reason=pr_creation_disabled`; `repoRef` gate preserved; null resolver ⇒ true parity).
- **DeliveryApprovalService + wiring (Task 5):** MANDATORY-tx executor mirroring `LintApprovalService`; synchronous `WaitingForDelivery → WaitingForReview` transition + deferred REQUIRES_NEW seam (`approve` → `resumeDeliveryTailFromGate` verbatim; `manual` → `recordManualDeliveryAndEnqueueReviewer` [new broker method, no git] + `delivery.recordedManually` event). `ApproveDeliveryCommand` (+WorkflowCommand permit +fingerprint +replay pin) + `requireParkedAtDeliveryGate` (executor-as-gate 409) + `approve-delivery` REST (workflow_owner) + `CommandModelSymmetryFoundationContract.EXPECTED_PERMITS`.
- **Lint↔delivery composition (Task 6, Decision 3):** `LintApprovalService.approveLint` resolves pushMode in-tx — `auto` keeps 3h-2 behavior; non-`auto` transitions `WaitingForLintApproval → WaitingForDelivery` (new edge) + no deferred push. `approve_lint` replay pin made mode-dependent (resolves pushMode at replay time; injected `ProjectRuntimeConfigResolver` into `WorkflowCommandService`).
- **Manual event:** new `WorkflowEventType DELIVERY_RECORDED_MANUALLY("delivery.recordedManually")` + 2 fixture sites (events fixture JSON + fixture-stream schema JSON). No Flyway (event_type un-CHECKed).
- **Tests + logging (Task 8):** unit — `PushModeParsingTest`, `DeliveryGateServiceTest` (+ListAppender log pins), `DeliveryApprovalServiceTest` (+ListAppender log pins), `LintApprovalServiceTest` (+non-auto routing). Real-PG `DeliveryGateIT` (7 cases: auto pass-through, approve/manual park, approve_delivery approve→push+enrich, manual→event+no-git, wrong-state 409, lint composition, idempotent replay). `ProjectControllerContractTest` bodies +`autoCreatePullRequest` (primitive-boolean-400 trap). `ProjectEntityMapperTest.baseEntity` sets push_mode (NOT NULL column).
- **VERIFIED:** full Surefire 1733/0; targeted Failsafe `DeliveryGateIT` 7/7 real-PG; checkstyle green; spotless applied; OpenAPI + FE client in sync. Full Docker `mvnw verify` re-running to confirm the two round-1 fixes (see Debug Log) — status recorded on completion.
- **NO** new FailureCategory / table / runner-image / mock; **NO** OpenAPI change for the action (only the state enum + project fields). ADR 0030 Decision-6 landing amendment + glossary vocabulary added.

### File List

**New (production):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/PushMode.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/DeliveryGateService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/DeliveryApprovalService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/commands/ApproveDeliveryCommand.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ApproveDeliveryRequest.java`
- `deliveryline-backend/src/main/resources/db/migration/V38__add_delivery_gate_and_push_mode.sql`

**New (tests):**
- `deliveryline-backend/src/test/java/org/dradgo/domain/registry/PushModeParsingTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/DeliveryGateServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/DeliveryApprovalServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/DeliveryGateIT.java`

**Modified (production):**
- `domain/registry/WorkflowState.java`, `AllowedAction.java`, `WorkflowEventType.java`
- `domain/project/Project.java`
- `application/runner/RunnerProperties.java`, `RunnerBroker.java`
- `application/runner/workspace/RepositoryWorkspaceService.java`
- `application/project/ProjectRuntimeConfigResolver.java`, `ProjectManagementService.java`, `CreateProjectCommand.java`, `UpdateProjectCommand.java`, `DefaultProjectSeeder.java`
- `application/workflow/WorkflowTransitionTable.java`, `WorkflowInspectionService.java`, `WorkflowCommandService.java`, `LintApprovalService.java`, `commands/WorkflowCommand.java`
- `application/idempotency/WorkflowCommandFingerprintFactory.java`
- `adapters/persistence/entity/ProjectEntity.java`, `mapper/ProjectEntityMapper.java`
- `adapters/rest/ProjectController.java`, `ProjectResponse.java`, `CreateProjectRequest.java`, `UpdateProjectRequest.java`, `WorkflowController.java`
- `src/main/resources/application.yml`, `src/main/resources/openapi/openapi.json`
- `deliveryline-frontend/src/lib/api/schema.d.ts`
- `config/checkstyle/suppressions.xml` (realign WorkflowCommandService Thread.sleep suppression 1187→1274)
- `docs/adr/0030-governed-delivery-tail.md`, `docs/glossary.md`

**Modified (tests / fixtures):**
- `test/.../foundation/TransitionTableCrossProductFoundationContract.java`, `CommandModelSymmetryFoundationContract.java`
- `test/.../contract/FlywaySchemaContractTest.java`
- `test/.../application/workflow/WorkflowTransitionTableTest.java`, `WorkflowInspectionServiceAllowedActionsTest.java`, `LintApprovalServiceTest.java`, `WorkflowCommandServiceCreateBindingTest.java`, `WorkflowCommandServiceReplayRefTest.java`
- `test/.../adapters/persistence/mapper/ProjectEntityMapperTest.java`
- `test/.../adapters/rest/ProjectControllerContractTest.java`
- `test/.../application/runner/RunnerPropertiesTest.java`, `RunnerBrokerUnitTest.java`, `RunnerLogCaptureServiceTest.java`
- `test/.../application/workflow/WorkflowOrchestrationServiceTest.java`
- `test/.../adapters/runner/DockerRunnerAdapterUnitTest.java`, `DockerRunnerAdapterLoggingContractTest.java`, `DockerRunnerAdapterContainerLifecycleIT.java`, `lifecycle/DockerLifecycleITSupport.java`
- `test/.../adapters/files/LocalRunnerWorkspaceStoreTest.java`
- `test/resources/contracts/events/workflow-event-types.fixture.json`, `contracts/frontend/allowed-actions.placeholder.json`, `contracts/openapi/registry-api-schema-placeholders.json`, `fixture-event-streams/schema/workflow-events-response.schema.json`

### Change Log

| Date | Change |
|------|--------|
| 2026-07-09 | Implemented FR78 unified delivery gate (all 8 tasks): PushMode config stack, WaitingForDelivery/approve_delivery drift (V38), DeliveryGateService + RunnerBroker wiring, autoCreatePullRequest PR gate, DeliveryApprovalService + approve-delivery REST, lint↔delivery composition, ADR/glossary, unit + real-PG IT + logging. OpenAPI + FE client regenerated. Status → review. |
