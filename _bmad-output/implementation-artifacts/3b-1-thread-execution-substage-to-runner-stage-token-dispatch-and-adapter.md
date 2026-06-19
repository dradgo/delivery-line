# Story 3b.1: Thread `ExecutionSubStage` → Runner Stage Token (Dispatch + Adapter)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer + workflow orchestrator,
I want the execution dispatch to carry the resolved `ExecutionSubStage` so `DockerRunnerAdapter` sets `DELIVERYLINE_RUNNER_STAGE` to `implementation-plan` / `pr-output` (the tokens `entrypoint.sh map_stage` already accepts) instead of the coarse `execution`,
so that an `IMPLEMENTATION_PLAN` dispatch runs the read-only plan phase (emits `implementationPlan`, no push) and a `PR_OUTPUT` dispatch implements + pushes + emits `prOutput` — fixing the root cause that every execution dispatch ran `prOutput` regardless of sub-stage.

## Context & Root Cause (read first)

This is the **execution-stage correctness fix** behind the `WaitingForReview` review-loop being unusable end-to-end (the execution-stage twin of the spec-stage gate that 3a-9 closed). Verified against live run `run_ae258aa42f524ba29db3c795732a21e6` (FIN-21).

The backend already has the sub-stage concept — `ExecutionSubStage ∈ {IMPLEMENTATION_PLAN, PR_OUTPUT}`, derived by `ContextBundleService.deriveExecutionSubStage(workflowRunId)` from "does an approved implementation plan exist?". **But that distinction never reaches the runner.** The dispatch carries only the coarse `RunnerStage ∈ {INVESTIGATION, EXECUTION}`:

- `DockerRunnerAdapter:243` → `containerEnv.put("DELIVERYLINE_RUNNER_STAGE", request.stage().value())` → `"execution"` for every execution dispatch.
- `runners/codex/entrypoint.sh` (and `runners/claude/entrypoint.sh`) `map_stage` maps `pr-output | execution | prOutput → prOutput` (line ~246) — so `execution` **always** selects `prOutput`: `danger-full-access`, implement, push.

Consequence on `run_ae258…`: orchestrator was in `IMPLEMENTATION_PLAN` (no approved plan), but the runner produced a `prOutput` (real `docs/` change, pushed, draft PR #2). The broker ingested it, treated it as the plan sub-stage, and **skipped `validateAndEnrichPrOutput`** (which only runs in the `PR_OUTPUT` sub-stage) — the sole place that persists the `github_pr` integration-link. Net: no plan to review, a `prOutput` mislabeled as the plan phase, no PR link despite a real PR.

**What already works — assert, do NOT build (verified in this repo):**
- The runner is fully stage-aware. `map_stage` already accepts the tokens this story emits: `implementation-plan | plan | implementationPlan → implementationPlan` (`entrypoint.sh:243-244`) and `pr-output | execution | prOutput → prOutput` (`:246-247`); `spec-investigation | investigation | spec → spec` (`:240-241`).
- `entrypoint.sh` branches sandbox + prompt by artifact type: `implementationPlan` → `read-only`, "produce a plan, do NOT modify files" (`:541`, `:558`); `prOutput` → `danger-full-access`, implement + push (`:537`, `:561`). `runner.mjs build --stage` emits both shapes.
- `CodexRunnerImageConformanceIT` **already parameterizes** `implementation-plan → implementationPlan` and `pr-output → prOutput` over `DELIVERYLINE_RUNNER_STAGE` (`:157-163`), and `openSpecReadOnlyStageNeverWritesRepo` (`:426-436`) already asserts a read-only stage leaves the repo untouched.

**Therefore the entire story is BACKEND:** thread the already-derived `ExecutionSubStage` into the dispatch envelope and pick the env token in the adapter. **No runner-image change is expected** (no `:latest` rebuild chain). The design spec's "Story C" (runner conformance) reduces to *confirming* the existing IT coverage, not adding it.

## Acceptance Criteria

1. **Dispatch carries the sub-stage.** `RunnerDispatchRequest` carries the resolved `ExecutionSubStage` (preferred design: add a **nullable** `ExecutionSubStage subStage` field, mirroring the nullable `repositoryRef` seam in story 3.9 — `null` for INVESTIGATION and for the legacy/recovery generic path). The broker threads `composed.executionSubStage()` (queue path) / `executionSubStage` (deprecated sync path) — **both already in scope; no new derivation call is added** (`deriveExecutionSubStage` is called once per dispatch already).
2. **Adapter emits the precise token.** `DockerRunnerAdapter` sets `DELIVERYLINE_RUNNER_STAGE` from the `(stage, subStage)` pair:
   - `INVESTIGATION` → `"investigation"` (unchanged: `request.stage().value()`).
   - `EXECUTION` + `IMPLEMENTATION_PLAN` → `"implementation-plan"`.
   - `EXECUTION` + `PR_OUTPUT` → `"pr-output"`.
   - `EXECUTION` + `subStage == null` → `"execution"` (legacy fallback → maps to `prOutput`; preserves byte-identical behavior for the `RecoveryService` retry path and all pre-3b tests).
3. **No second Docker record constructor on the wire-binding path.** Reach the sub-stage through the (already-injected) request/`RunnerDispatchRequest` field — do NOT add a second `@ConfigurationProperties`/Docker record constructor (breaks Spring binding — `[[runner-image-stale-causes-exit-20]]`, `[[docker-adapter-ctor-dep-fans-out]]`). `RunnerDispatchRequest` is a plain application record, so adding a nullable component there is safe — but keep at least one back-compat constructor so existing `new RunnerDispatchRequest(...)` test sites compile.
4. **Unit/IT: each sub-stage dispatches the correct token.** `DockerRunnerAdapterUnitTest` proves: an `EXECUTION`+`IMPLEMENTATION_PLAN` request yields `DELIVERYLINE_RUNNER_STAGE=implementation-plan`; `EXECUTION`+`PR_OUTPUT` yields `pr-output`; `INVESTIGATION` still yields `investigation`; `EXECUTION`+`null` still yields `execution`.
5. **Conformance ITs cover both execution sub-stages (folds in spec #1 "Story C").** Confirm (assert, do not add) that `CodexRunnerImageConformanceIT` / `ClaudeRunnerImageConformanceIT` exercise `implementation-plan → implementationPlan` with `read-only` sandbox (no repo writes) and `pr-output → prOutput`. If a gap exists, extend the existing parameterized test — do not create a parallel IT.
6. **Investigation/spec path unchanged (regression).** All INVESTIGATION dispatches still emit `"investigation"`; spec composition, secret-scan keying, and `kindForStage`/`kindForExecutionSubStage` resolution are untouched.
7. **ArchUnit boundary unchanged.** `DockerRunnerAdapter` (in `adapters.runner`) importing `application.runner.ExecutionSubStage` is allowed (adapter→application direction). No new `application → adapters` import is introduced.
8. **Artifact-type mismatch guard stays coarse-stage-scoped (no scope creep).** `allowedArtifactTypesForStage(EXECUTION)` continues to accept BOTH `IMPLEMENTATION_PLAN` and `PR_OUTPUT` (`RunnerBroker:1598-1603`); `RUNNER_ARTIFACT_TYPE_MISMATCH` still fires only when a runner emits a type outside the coarse stage set. A tighter *per-sub-stage* mismatch check is explicitly **OUT of scope** for this story.

## Tasks / Subtasks

- [x] Task 1 — Add the sub-stage to the dispatch envelope (AC: #1, #3, #7)
  - [x] Add a nullable `ExecutionSubStage subStage` field to `application/runner/RunnerDispatchRequest` (record component). It is in the same package as `ExecutionSubStage`, so no new import boundary. Document it as nullable (INVESTIGATION + legacy/recovery generic path).
  - [x] Keep / add a back-compat constructor so the existing `new RunnerDispatchRequest(...)` sites (mock + no-repo + test helpers, e.g. `DockerRunnerAdapterUnitTest.dispatchRequest()`) still compile with `subStage = null`. Validate in the canonical constructor that `subStage` is non-null only when `stage == EXECUTION` is NOT required — leave it permissive (null allowed for any stage) to match the recovery/legacy generic path.
- [x] Task 2 — Thread `executionSubStage` at both broker construction sites (AC: #1, #6)
  - [x] `RunnerBroker.executeQueuedDispatch` (queue/production path, request built ~`:910`): pass `composed.executionSubStage()` (already computed by `ComposedDispatch`).
  - [x] `RunnerBroker.dispatch` (deprecated sync path, request built ~`:691`): pass the in-scope `executionSubStage` local (already used for `kindForExecutionSubStage` at `:689`). Keep this path's behavior consistent even though it is test-only.
  - [x] Confirm INVESTIGATION composition leaves `executionSubStage == null` (it does — derived only in the `else` branch, `:1046` / sync path), so INVESTIGATION requests carry `subStage = null`.
- [x] Task 3 — Map `(stage, subStage)` → env token in the adapter (AC: #2, #4)
  - [x] In `DockerRunnerAdapter` replace `containerEnv.put("DELIVERYLINE_RUNNER_STAGE", request.stage().value())` (`:243`) with a small private helper `resolveRunnerStageToken(request)` returning: `investigation` for INVESTIGATION; `implementation-plan`/`pr-output` for EXECUTION by `subStage`; `execution` (= `request.stage().value()`) when `subStage == null`. Keep `deliveryline.stage` **label** (`:223`) as the coarse `request.stage().value()` (the label is observability-only; only the env var drives `map_stage`).
  - [x] Confirm the env token strings exactly match `map_stage` tokens (`implementation-plan`, `pr-output`, `investigation`, `execution`) — these are literal contract tokens, not registry `value()`s.
- [x] Task 4 — Tests (AC: #4, #5, #6)
  - [x] Extend `DockerRunnerAdapterUnitTest`: add cases asserting the env token for EXECUTION+IMPLEMENTATION_PLAN (`implementation-plan`), EXECUTION+PR_OUTPUT (`pr-output`), EXECUTION+null (`execution`); keep the existing INVESTIGATION assertion (`:241`) green. Use the existing `dispatchRequest(kind)` helper pattern + a new overload taking `(stage, subStage)`.
  - [x] Add/confirm broker coverage that an EXECUTION dispatch builds a request whose `subStage()` equals the derived sub-stage (extend `RunnerBrokerUnitTest`; it already drives both sub-stages — see `ContextBundleServiceExecutionStageTest` for the derivation fixtures).
  - [x] Confirm the two conformance ITs (`CodexRunnerImageConformanceIT:157-163`, `ClaudeRunnerImageConformanceIT`) already cover `implementation-plan`/`pr-output`; assert no image change is needed. Only extend if a sub-stage variant is genuinely uncovered.
  - [x] Regression: run the INVESTIGATION/spec orchestration path — no change in emitted token, secret-scan, or kind resolution.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] The broker already logs the derived sub-stage (`executeQueuedDispatch execution sub-stage derived ... subStage={}` `:1047`). Add an `INFO` in `DockerRunnerAdapter` at the token-resolution point: `docker dispatch stage token resolved runnerExecutionId={} stage={} subStage={} runnerStageToken={}` so the wire token is observable in a prod incident (this is the exact field that was wrong on `run_ae258…`).
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for the resolved token (normal lifecycle). No new WARN/ERROR branch is introduced by this story.
  - [x] Carry `runnerExecutionId` + `workflowRunId` (MDC scope is already open on the dispatch path). Never log payload bytes, secrets, or the provider key (existing Trap T5 / `secretVarCount`-only rule at `:236`).
  - [x] Add a focused test assertion (logback list-appender, as `dispatchLogsNeverContainTheProviderKeyValue` at `DockerRunnerAdapterUnitTest:247` already does) that the new token-resolution line is emitted at INFO with the correct `runnerStageToken` for an EXECUTION sub-stage.

## Dev Notes

### Exact touch points (verified against current source)

| File | Line(s) | Change |
|---|---|---|
| `application/runner/RunnerDispatchRequest.java` | record header `:24-34`, ctors `:36-100` | add nullable `ExecutionSubStage subStage` component + back-compat ctor |
| `adapters/runner/DockerRunnerAdapter.java` | `:243` (env put), keep `:223` label coarse | resolve token from `(stage, subStage)`; add INFO log |
| `application/runner/RunnerBroker.java` | sync `:691-701`, queue `:910-920` | pass `executionSubStage` / `composed.executionSubStage()` into the request |
| `test/.../adapters/runner/DockerRunnerAdapterUnitTest.java` | helper `:733-746`, env test `:225-245` | new sub-stage cases + helper overload |
| `test/.../application/runner/RunnerBrokerUnitTest.java` | — | assert request `subStage()` matches derived sub-stage |
| `test/.../adapters/runner/CodexRunnerImageConformanceIT.java` | `:157-163` | confirm coverage (assert, likely no change) |

### Design decision — nullable `subStage` field (preferred option, locked)

The design spec offered two options; they converge on the same shape. **Chosen:** add a nullable `ExecutionSubStage subStage` to `RunnerDispatchRequest` and keep `stage` as `RunnerStage`. Rationale:
- `RunnerStage` is a wire/registry enum (`domain.registry`, values `investigation`/`execution`) — it must NOT grow sub-stage members (would break the runner-contracts wire enum and `RunnerStage.fromValue` parsing). `ExecutionSubStage` is the project-owned semantic discriminator (`application.runner`, see its class javadoc) — exactly the right type to thread.
- `null` sub-stage = "legacy generic composition" (the `RecoveryService` retry path and pre-3.10 behavior, per `ExecutionSubStage` javadoc) → adapter falls back to `"execution"` → `prOutput`, byte-identical to today. This is what keeps the recovery/retry path and all existing tests green.

### Reconciliation against done work (verify, not rework)
- The artifact-type mismatch guard is **coarse-stage-scoped** (`allowedArtifactTypesForStage(EXECUTION)` = `{IMPLEMENTATION_PLAN, PR_OUTPUT}`, `:1598-1603`). It does NOT distinguish sub-stage and **must not** be tightened here — the broker's ingest-routing/availability is 3b-2/3b-3 territory. AC #8 pins this.
- `kindForExecutionSubStage` already exists and is used to pick the runner *kind* per sub-stage (`RunnerProperties:185`, `RunnerBroker:689`/`:908`); both plan and PR sub-stages currently resolve to the same (codex) kind so the story-3.5 secret-scan keying stays consistent. This story does NOT touch kind resolution — only the env *stage token*.
- `deliveryline.stage` container **label** (`:223`) stays coarse (`execution`) — it is `docker inspect` observability, not the `map_stage` input. Only `DELIVERYLINE_RUNNER_STAGE` (env, `:243`) drives the runner phase (`entrypoint.sh:420-421`, env beats bundle.stage; `--stage` flag beats both).

### Scope boundary (do NOT do here)
- Do NOT mark `implementationPlan`/`prOutput` artifacts `available` (that is **3b-3**).
- Do NOT add the plan-approval → PR-phase re-dispatch (that is **3b-2** — this story makes the *first* dispatch run the correct phase; the second-dispatch orchestration is its own story).
- Do NOT touch the review UI, allowed-actions role, or renderers (3b-4/3b-5/3b-6).
- Do NOT change the runner image or `entrypoint.sh` — tokens already exist. If you *think* an image change is needed, stop: re-read AC #5 (the conformance IT already proves the tokens work).

### Architecture / boundaries
- `application/...` may not import `org.dradgo.adapters..` (`[[application-cannot-import-adapters]]`). This story only adds an `application → application` field and an `adapters → application` import — both legal.
- Adding a `RunnerDispatchRequest` ctor dep / DockerRunnerAdapter ctor dep fans out to slice tests (`[[docker-adapter-ctor-dep-fans-out]]`) — we add a *record component*, not a ctor dependency, so DI wiring is unaffected. Keep one back-compat constructor so manual `new RunnerDispatchRequest(...)` sites compile.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **This story's minimum surface:** an `INFO` in `DockerRunnerAdapter` recording the resolved `runnerStageToken` alongside `stage`/`subStage` (the exact value that was wrong on the live run), keyed by `runnerExecutionId` + `workflowRunId`.
- **Required context keys:** `runnerExecutionId`, `workflowRunId` (MDC scope already open on the dispatch path).
- **Forbidden in log output:** provider key / secrets (existing `secretVarCount`-only rule, `:236`), payload bytes, raw refs.
- **Test contract:** the new INFO line is pinned by a logback list-appender assertion (pattern already used at `DockerRunnerAdapterUnitTest:247`).

### Testing standards
- Backend unit tier runs under Surefire (no Docker); `*IT` runs under Failsafe (Docker tier) — name any new IT `*IT` (`[[springboot-testcontainers-test-must-be-IT]]`). The conformance ITs are already `*IT` + Testcontainers; confirm, don't rename.
- Running a single test via the `surefire:test`/`failsafe:integration-test` *goal* crashes on unsubstituted `@{argLine}` — use the `test`/`integration-test` *lifecycle phase* with `-Djacoco.skip=true` (`[[maven-arglineation-goal-crash]]`).
- Conformance ITs build the runner image and are heavy — gated by `@Tag("docker-runner-it")` (`[[docker-it-needs-exact-docker-runner-it-tag]]`); they are not part of default `verify`.

### Project Structure Notes
- No new module, package, Flyway migration, `DomainErrorCode`, `WorkflowEventType`, or runner-contracts schema change. Net change: one record component, one adapter token-mapping helper + log line, two broker call-site edits, and test additions.

### References
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-06-16-waiting-for-review.md#Story 3b-1] — story stub, AC-shape, dependencies, sequencing.
- [Source: docs/superpowers/specs/2026-06-16-runner-two-phase-execution-contract-design.md] — authoritative design (sub-project #1, Story A + Story C); verified root cause; "what already works".
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java:223,243] — env/label injection seam.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java:691,910,1046] — both dispatch construction sites + sub-stage derivation.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerDispatchRequest.java] — dispatch envelope record + back-compat ctors.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/ExecutionSubStage.java] — project-owned discriminator semantics (null = legacy generic).
- [Source: runners/codex/entrypoint.sh:220-247,537-561] — `map_stage` tokens + sandbox/prompt branching by artifact type.
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java:157-163,426-436] — existing two-sub-stage conformance coverage.
- Dependencies: 3.1 (DockerRunnerAdapter), 3.11 (`dispatchPlanGeneration`/execution dispatch path), 3.12 (`validateAndEnrichPrOutput`), 1.13 (`deriveExecutionSubStage`), 1.6 (runner-contracts schema — no change), 3.3/3.4 (runner images — confirmed stage-aware, no change).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- `mvnw -pl deliveryline-backend test -Dtest=DockerRunnerAdapterUnitTest,RunnerBrokerUnitTest -Djacoco.skip=true` → green. `DockerRunnerAdapterUnitTest` 33/33 (28 + 5 new sub-stage/log cases), `RunnerBrokerUnitTest` 45/45 (existing tests + new `subStage()` assertions). Surefire reports confirm `CodexRunnerImageConformanceIT` 8/8 and `ClaudeRunnerImageConformanceIT` 9/9 still green.
- `mvnw -pl deliveryline-backend spotless:check` → exit 0 (after `spotless:apply` comment reflow).

### Completion Notes List

- **AC1** — Added a nullable `ExecutionSubStage subStage` record component to `RunnerDispatchRequest` (11th canonical component), mirroring the nullable `repositoryRef` seam. Permissive: null allowed for any stage (recovery/legacy generic path).
- **AC2** — `DockerRunnerAdapter.resolveRunnerStageToken(request)` maps `(stage, subStage)` → the literal `map_stage` token: INVESTIGATION→`investigation`; EXECUTION+IMPLEMENTATION_PLAN→`implementation-plan`; EXECUTION+PR_OUTPUT→`pr-output`; EXECUTION+null→`execution` (= `request.stage().value()`, byte-identical legacy fallback). The coarse `deliveryline.stage` label stays `request.stage().value()` (observability-only).
- **AC3** — Reached the sub-stage via the existing `RunnerDispatchRequest` record component; no second Docker `@ConfigurationProperties`/record constructor. Added one new 10-arg back-compat constructor (trailing `ExecutionSubStage`, distinct from the pre-3b `String linearTicketSummary` 10-arg by param type → no overload ambiguity); the 7/9/10-arg back-compat constructors default `subStage = null`.
- **AC4** — 5 new `DockerRunnerAdapterUnitTest` cases assert each token (implementation-plan / pr-output / execution / investigation) plus the INFO log line.
- **AC5** — Confirmed (no change): `CodexRunnerImageConformanceIT:157-163` `@CsvSource` already parameterizes `implementation-plan → implementationPlan` and `pr-output → prOutput`; `openSpecReadOnlyStageNeverWritesRepo` asserts the read-only plan stage leaves the repo untouched. No runner-image change.
- **AC6** — INVESTIGATION path verified: new `RunnerBrokerUnitTest` assertion that an INVESTIGATION dispatch request carries `subStage() == null`; token stays `investigation`. Secret-scan / `kindForExecutionSubStage` resolution untouched.
- **AC7** — Only `adapters.runner → application.runner` (`ExecutionSubStage`) import added (allowed direction); no `application → adapters` import.
- **AC8** — `allowedArtifactTypesForStage(EXECUTION)` and the `RUNNER_ARTIFACT_TYPE_MISMATCH` coarse-stage guard untouched (out of scope).
- **Logging** — New INFO `docker dispatch stage token resolved runnerExecutionId={} workflowRunId={} stage={} subStage={} runnerStageToken={}` (parameterized; keyed by runnerExecutionId + workflowRunId; no secrets/payload). Pinned by `dispatchLogsResolvedStageTokenAtInfoForExecutionSubStage`.

### File List

- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerDispatchRequest.java` (modified — nullable `subStage` component + back-compat/broker constructors)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` (modified — thread sub-stage at both dispatch construction sites)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java` (modified — `resolveRunnerStageToken` helper, env token, INFO log, imports)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java` (modified — 5 sub-stage/log tests + helpers)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java` (modified — sub-stage assertions on INVESTIGATION + EXECUTION dispatch requests)

### Change Log

| Date | Change |
|---|---|
| 2026-06-16 | Story 3b.1 implemented: threaded `ExecutionSubStage` into `RunnerDispatchRequest`; `DockerRunnerAdapter` now emits `implementation-plan`/`pr-output`/`investigation`/`execution` for `DELIVERYLINE_RUNNER_STAGE` per `(stage, subStage)`; both broker dispatch sites pass the derived sub-stage; added unit/broker tests + INFO token log. Status → review. |
