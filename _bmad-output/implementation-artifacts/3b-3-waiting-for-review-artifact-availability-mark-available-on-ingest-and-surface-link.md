# Story 3b.3: WaitingForReview Artifact Availability — Mark `implementationPlan`/`prOutput` `available` on Ingest + Surface the Implementation-Artifact Link

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a Workflow Owner / developer reviewer,
I want execution-produced artifacts (`implementationPlan`, `prOutput`) marked `available` on ingest and a state-aware "Open the implementation output →" link surfaced at `WaitingForReview`,
so that `acceptImplementation` (which requires `isApprovalEligible` = `AVAILABLE`) can fire and the reviewer can actually reach the implementation artifact — the execution-stage twin of 3a-9's spec-stage Gate 1.

## Context & the ONE non-obvious trap (read first — this changes what you build)

This is sub-project **#2b** of "Option X" (the full `WaitingForReview` review experience). 3a-9 (done) wired availability for `spec` ONLY and deliberately left `implementationPlan`/`prOutput` `pending` (`[[markavailable-has-no-production-caller]]`). 3b-3 generalizes that wiring to the execution-stage artifact types and surfaces a link to them.

**Two-part deliverable:** (1) backend — mark `IMPLEMENTATION_PLAN` and `PR_OUTPUT` `available` on ingest; (2) frontend — render an "Open the implementation output →" link at the detail route, then re-embed the SPA. Decision buttons firing (developer-role wiring) and the real `prOutput` PR/diff renderer are explicitly OUT (→ 3b-4 / 3b-5).

### ⚠️ THE TRAP: `prOutput` enrich creates a NEW version — mark the *enriched head*, not just v1

This is the single thing that will be implemented wrong if you skip this section. The naive change ("add `IMPLEMENTATION_PLAN`/`PR_OUTPUT` to the `if (artifactType == SPEC)` gate") is **correct for the plan path and for the no-push `prOutput` path, but WRONG for a real `prOutput` with a GitHub push** — and the frontend resolver will then point the reviewer at a `pending` artifact.

Traced mechanics (verified against current source):

1. `RunnerBroker.onResult`'s ingest loop (`RunnerBroker.java:1288-1353`) does `recordOperation(CREATE)` → a **v1** `prOutput` artifact (status `PENDING`), and captures `prOutputArtifactId` (`:1335-1337`). Adding the in-loop mark here promotes **v1** to `AVAILABLE`.
2. LATER, only in the `PR_OUTPUT` sub-stage and only when `captureAndPush` returned a push outcome, `validateAndEnrichPrOutput` (`:1701-1782`) calls `enrichPrOutputArtifact` (`:1948-2020`), which does `recordOperation(UPDATE)` (`:1979-1991`). **`UPDATE` → `createNextVersion` (`ArtifactOperationService.java:765-773`) → a brand-new v2 artifact row, new `publicId`, status `PENDING`.** It does NOT mutate v1.
3. The frontend `resolveImplementationArtifact` (`approvalDecisionView.ts:540-573`) picks the **highest-version** `prOutput`. After enrich that is **v2 (`PENDING`)**. `acceptImplementation` → `isApprovalEligible(v2)` → fails (`ARTIFACT_PAYLOAD_UNAVAILABLE`). So marking only v1 leaves the reviewer's target un-acceptable in production.
4. You CANNOT "mark v1, then re-mark v1": `markAvailable` on an **already-AVAILABLE** artifact is a no-op (`ArtifactOperationService.java:312-316` — returns `idempotent` without re-stamping). The enriched v2, however, carries a fresh `PENDING` UPDATE op, so `markAvailable(v2, …)` finds that pending op and completes it.

**Resolution (do BOTH):**
- **In the ingest loop**: mark `SPEC`, `IMPLEMENTATION_PLAN`, `PR_OUTPUT` available (covers the no-enrich cases — the plan phase, the no-push `prOutput`, and EVERY mock-runner test).
- **After a successful `enrichPrOutputArtifact`**: mark the **enriched head** version available (the `result.artifact()` v2 + `result.storageRef()` + checksum over the enriched bytes). The design spec frames this as "if enrich reverts status to pending, re-mark available" — the precise mechanism is a new *version*, not a status reversion, but the required outcome is identical: **the highest-version `prOutput` must be the AVAILABLE one.**

**Why the mock tests won't catch this for you:** `RepositoryWorkspaceService` is `@Profile({"github-mock","github-real"})` and absent under `{test, linear-mock}`; `captureAndPush` returns `Optional.empty()` for the mock runner, so `pushOutcome.isPresent()` is false → enrich is **skipped** → only v1 exists → in-loop marking is sufficient *in the ITs*. The enriched-head path must be covered by a **`RunnerBrokerUnitTest`** case that stubs a present `RepositoryPushOutcome` (the unit test mocks `ArtifactOperationService`, so you assert the `markAvailable` call on the enriched artifact id, not a DB row). See Dev Notes "Design Decision DD1".

### Scope guardrails (do NOT do here)

- Do NOT make decision buttons fire / wire the developer role — that is **3b-4**.
- Do NOT build the `prOutput` PR/diff renderer — that is **3b-5**. The generic viewer may render a raw `prOutput` JSON as `error`; that is acceptable for this story.
- Do NOT change runner behavior, the dispatch envelope, the sub-stage token mapping, or fix any sub-stage mismatch — that is 3b-1/3b-2 (done).
- Do NOT add a new `DomainErrorCode`, `WorkflowEventType` (the `ARTIFACT_AVAILABLE` event already exists and `markAvailable` emits it), Flyway migration, runner-contracts schema, config property, REST endpoint, ArchUnit rule, or transition-table edge.
- Do NOT touch the auto-advance (`onPlanStageSucceeded` / `onPrOutputStageSucceeded`) — it fires on ingest regardless of availability, exactly as the spec path already does.

## Acceptance Criteria

1. **Backend — generalize availability to all runner-produced artifact types.** Rename/generalize `RunnerBroker.markSpecArtifactAvailable` → `markArtifactAvailable` (drop spec-specific naming; `SPEC_CHECKSUM_ALGORITHM` → a neutral name) and call it in the `onResult` ingest loop (`RunnerBroker.java:~1350`) for `SPEC`, `IMPLEMENTATION_PLAN`, **and** `PR_OUTPUT` (today only `SPEC`). Checksum is computed over the SAME payload bytes handed to `recordOperation`; `storageRef` is `opResult.storageRef()`. **Idempotent-replay safe**: when `opResult.storageRef() == null` (a duplicate `onResult` that did not re-write the payload) the method no-ops, and `markAvailable` is itself idempotent on already-AVAILABLE.

2. **Backend — the enriched `prOutput` head is the AVAILABLE one (THE trap, AC-critical).** After a successful `enrichPrOutputArtifact` (push outcome present, `PR_OUTPUT` sub-stage), the **highest-version** `prOutput` artifact ends `AVAILABLE` — because enrich `createNextVersion`s a new `PENDING` v2 that the frontend resolver will select. Mark the enriched head available from the enrich result (`result.artifact().publicId()` + `result.storageRef()` + checksum over the enriched bytes). Keep this **best-effort** (mirror the existing enrich try/catch at `:2008-2019`): an availability-marking failure must NOT unwind the committed runner outcome or block the `WaitingForReview` advance.

3. **Backend — no spec regression, auto-advance unchanged.** The `spec` path stays byte-identical in behavior (still `available` on ingest, approvable). `onPlanStageSucceeded` / `onPrOutputStageSucceeded` still fire on ingest regardless of availability. No new failure branch; artifact-ingestion-failure handling (`:1355-1372`) is untouched.

4. **Frontend — surface the implementation-artifact link.** In `routes/workflows/$workflowRunId/index.tsx`, in addition to the existing "Open the specification →" link, render an **"Open the implementation output →"** link when `resolveImplementationArtifact(data)` resolves an `artifactId` (it prefers highest-version `prOutput`, falls back to `implementationPlan`). The link targets the existing `/workflows/$workflowRunId/artifacts/$artifactId` route and is gated on `artifactId !== undefined` (it naturally appears once the execution stage has produced an implementation artifact). No decision-bar change.

5. **Rebuild + re-embed the SPA.** `mvn package` rebuilds the SPA into backend `static/` (`[[embedded-frontend-at-package-phase]]`), activating both the new link and the already-built `implementation_review` decision bar (3.28) on the running app. Verify on a live `WaitingForReview` run.

6. **Tests (failing-first).**
   - `RunnerBrokerUnitTest`: an ingested `prOutput` and an ingested `implementationPlan` each end with a `markAvailable` call on the freshly-created artifact id (mock `ArtifactOperationService`, verify the call + captured checksum/storageRef). **Plus** the enriched-head case: with a stubbed present `RepositoryPushOutcome`, the enriched (v2) `prOutput` id is the one passed to `markAvailable`.
   - `PrOutputOrchestrationIT` / `ImplementationPlanOrchestrationIT`: after a successful execution result + `drainQueue()` + `pollActiveExecutions()`, the artifact row is `status=available` (flip the existing deliberate-`pending` assertions/comments — these are the Decision-D1 `pending`-on-ingest pins from 3a-9 that this story supersedes) and the run is `WaitingForReview`.
   - `SpecStageOrchestrationIT`: spec still `available` + approvable (regression baseline, unchanged).
   - Frontend: a route/page test (or extend `approvalDecisionView.test.ts`) asserting the implementation-artifact link renders when `latestArtifacts` carries a `prOutput` with an `artifactId`, and does NOT render when only a `spec` exists.

7. **No new production surface.** No new `DomainErrorCode`, `WorkflowEventType`, Flyway migration, runner-contracts schema, config property, REST endpoint, OpenAPI change, ArchUnit rule, or transition-table edge. Net production change: the generalized `RunnerBroker` marking (≈ a widened conditional + one post-enrich mark) and the one frontend link.

## Tasks / Subtasks

- [x] Task 1 — Backend: generalize the ingest-loop availability marking (AC: #1, #3)
  - [x] Rename `markSpecArtifactAvailable` → `markArtifactAvailable` in `RunnerBroker.java` (`:1921-1939`); rename the `SPEC_CHECKSUM_ALGORITHM` constant (`:81`) to a neutral name (e.g. `ARTIFACT_CHECKSUM_ALGORITHM`); update the javadoc to drop spec-specific wording. Behavior unchanged (checksum over `payloadBytes`, `storageRef` from `opResult`, no-op on `null` storageRef, SYSTEM actor).
  - [x] Widen the call-site gate (`:1350-1352`) from `if (artifactType == ArtifactType.SPEC)` to also include `IMPLEMENTATION_PLAN` and `PR_OUTPUT`. Update the inline comment to reflect that plan/pr-output are now made approval-eligible on ingest (3b-3 supersedes 3a-9's Decision-D1 `pending` posture for these two types).
  - [x] Confirm the auto-advance delegation (`:1560-1597`) is unchanged and still fires on ingest.
- [x] Task 2 — Backend: mark the enriched `prOutput` head available (AC: #2) — THE trap
  - [x] After `enrichPrOutputArtifact`'s `recordOperation(UPDATE)` succeeds (`:1991-1998`), mark the enriched head available: `markArtifactAvailable(result, enrichedBytes, correlationId)` (the `result.artifact()` is the new v2; `result.storageRef()` is the enriched store ref; the checksum is over `enrichedBytes`). Keep it inside the existing best-effort try/catch (`:2008-2019`) so a marking failure is logged and swallowed — it must not unwind the committed outcome or block `WaitingForReview`.
  - [x] Verify (read `createOrAdvanceArtifact` `REPLACE, UPDATE` branch, `ArtifactOperationService.java:736-773`) that `UPDATE` = `createNextVersion` (new `PENDING` row), confirming the in-loop v1 mark does NOT make the enriched head available on its own.
  - [x] Confirm `markAvailable` on the enriched v2 finds its fresh `PENDING` UPDATE op (`ArtifactOperationService.java:308-380`) and transitions it `AVAILABLE` (not the already-AVAILABLE no-op path).
- [x] Task 3 — Backend tests (AC: #6)
  - [x] `RunnerBrokerUnitTest`: add cases for `implementationPlan` and `prOutput` ingest ending `available` (verify `markAvailable` called on the created artifact id; assert the captured `ArtifactChecksum` + `storageRef`). Mirror the existing spec ingest test's mock-`recordOperation` `thenAnswer` shape (`RunnerBrokerUnitTest.java:577-646`).
  - [x] `RunnerBrokerUnitTest`: add the enriched-head case — stub a present `RepositoryPushOutcome` so `validateAndEnrichPrOutput` enriches; assert `markAvailable` is invoked with the **enriched (v2)** artifact id, not the v1 created in the loop.
  - [x] `ImplementationPlanOrchestrationIT` / `PrOutputOrchestrationIT`: change the deliberate-`pending` assertions (the 3a-9 Decision-D1 comments at `ImplementationPlanOrchestrationIT.java:108-115` and `PrOutputOrchestrationIT.java:99-104`) to assert `status=available` for the ingested artifact; keep the run-state assertion (`WaitingForReview`). Use the existing status-query helper (`select status from artifacts …`).
  - [x] `SpecStageOrchestrationIT.specArtifactIsMarkedAvailableAndApprovableAfterPoll`: run unchanged — green regression baseline.
- [x] Task 4 — Frontend: render the implementation-output link (AC: #4)
  - [x] In `routes/workflows/$workflowRunId/index.tsx`, import `resolveImplementationArtifact` from `@/features/workflows/approvalDecisionView` (already a `.ts` sibling — no react-refresh issue, `[[frontend-react-refresh-no-fn-exports]]`). Call it on `data` next to the existing `resolveSpecArtifactId(data)` (`:121`), extract `.artifactId`.
  - [x] Render a `<Link to="/workflows/$workflowRunId/artifacts/$artifactId" params={{ workflowRunId, artifactId }}>Open the implementation output &rarr;</Link>` mirroring the spec link block (`:168-176`), gated on `implArtifactId !== undefined`. Keep the spec link as-is.
  - [x] No decision-bar change (it already routes `WaitingForReview` → `ImplementationReviewDecisionBarContainer` via `WorkflowDecisionBar.tsx:54`).
- [x] Task 5 — Frontend test (AC: #6)
  - [x] Add a route/page test (new `index.test.tsx` — none exists today) OR extend `approvalDecisionView.test.ts`: assert the impl-output link renders when `latestArtifacts` has a `prOutput` with `artifactId`, and is absent when only a `spec` exists. Mirror the MSW/`QueryClient` render pattern from `ImplementationReviewDecisionBarContainer.test.tsx:30-104` (mock `DETAIL_URL` returning `latestArtifacts`).
  - [x] Run `prettier --write` on touched frontend files before pushing (`[[prettier-gate-cascades-ci]]`).
- [x] Task 6 — Rebuild + manual verify (AC: #5)
  - [x] `mvn package` to re-embed the SPA into backend `static/`. Open a live `WaitingForReview` run (or `run_ae258…`); confirm the screen offers the implementation-output link and the artifact reads `available` in the read model.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] No NEW production log branch is required — `markAvailable` already logs INFO `markAvailable start/success` with `artifactId`/`storageRef`/`checksumAlgorithm`/`correlationId` (`ArtifactOperationService.java:295-309, 388-394`), and `recordOperation`/`enrichPrOutputArtifact` already log INFO on success (`RunnerBroker.java:1999-2007`). These now fire for `implementationPlan`/`prOutput` too — verify they carry `workflowRunId` + the artifact public id and do not log payload bytes/secrets.
  - [x] Pin the new observable surface with at least one focused assertion: in `RunnerBrokerUnitTest` (or via `OutputCaptureExtension`/list-appender) assert that a `prOutput` ingest emits the `markAvailable`-driven availability for the artifact id (the unit test's `verify(artifactOperationService).markAvailable(...)` is the pin; add a log-line assertion only if a new branch is introduced — none is expected).
  - [x] Use parameterized logging; never change production log levels; introduce no new WARN/ERROR branch.

## Dev Notes

### Why this is mostly a generalize-and-widen story (and the one place it isn't)

The availability mechanism is fully built by 3a-9 for `spec`; 3b-3 widens it to the execution-stage types. The ONLY genuinely new design decision is the `prOutput` × enrich version interaction (Context → "THE TRAP"). Everything else is renaming, a widened conditional, one frontend link, and a re-embed.

| Concern | Where it already lives | This story |
|---|---|---|
| `markAvailable` service (validate checksum, PENDING→AVAILABLE, idempotent, emits `ARTIFACT_AVAILABLE`) | 1.12 `ArtifactOperationService.markAvailable` (`:283-416`) | reuse unchanged |
| Spec-stage in-loop availability marking | 3a-9 `RunnerBroker.markSpecArtifactAvailable` (`:1350-1352, 1921-1939`) | **generalize** to all three types |
| `prOutput` enrich (actual refs) | 3.12 `enrichPrOutputArtifact` (`:1948-2020`) | **mark the enriched head available** after it |
| `resolveImplementationArtifact` (prefers highest-version `prOutput`) | 3.28 `approvalDecisionView.ts:540-573` | reuse; wire into the detail route |
| `implementation_review` decision bar + routing | 3.28 `WorkflowDecisionBar.tsx:54` | reuse; activated by re-embed |
| SPA embed at `mvn package` | 2.1 | rebuild |

### Design Decision DD1 — testing the enriched-head path under the mock runner

The mock runner (`{test, linear-mock}`) yields NO `RepositoryPushOutcome` (`RepositoryWorkspaceService` is `@Profile({"github-mock","github-real"})`, absent here), so enrich is **skipped** in the orchestration ITs — they exercise only the in-loop v1 mark. The enriched-head logic (AC2) therefore needs a **`RunnerBrokerUnitTest`** case that stubs a present push outcome. The unit test mocks `ArtifactOperationService`, so assert the `markAvailable(...)` invocation targets the enriched (v2) artifact id (the one returned by the enrich `recordOperation(UPDATE)` `thenAnswer`), not the v1 created in the ingest loop. Do not stand up a real repo workspace just for this — disproportionate, and `RunnerBrokerUnitTest` already mocks the enrich collaborators.

### Design Decision DD2 — superseding 3a-9's deliberate `pending` pins

`ImplementationPlanOrchestrationIT` and `PrOutputOrchestrationIT` currently **assert (with Decision-D1 comments) that the artifact stays `pending`** because "markAvailable has no production caller." 3b-3 makes that caller exist, so those assertions/comments are now wrong and must be **flipped to `available`** (failing-first: write the `available` assertion, watch it fail on the unmodified broker, then implement). This is the intended supersession, not a regression. Update the memory note `[[markavailable-has-no-production-caller]]` mentally: as of 3b-3, `IMPLEMENTATION_PLAN`/`PR_OUTPUT` ARE marked available on ingest.

### Regression note — 3b-2's IT defends against exactly this

`WaitingForReviewTwoDispatchOrchestrationIT` (3b-2, done) marks ingested artifacts available **in-test** with a helper that "skips when already available." Once 3b-3 lands, the broker marks them available first, so 3b-2's helper short-circuits — the IT stays green. Confirm it still passes; do not delete its helper (it remains correct as a defensive no-op and documents the parallel-story seam).

### Exact touch points (verified against current source)

| File | Line(s) | Relevance |
|---|---|---|
| `application/runner/RunnerBroker.java` | `1288-1353` (ingest loop), `1350-1352` (SPEC-only gate → widen), `1921-1939` (`markSpecArtifactAvailable` → `markArtifactAvailable`), `81` (checksum-algo constant), `1701-1782` (`validateAndEnrichPrOutput`), `1948-2020` (`enrichPrOutputArtifact` → add post-enrich mark), `1560-1597` (auto-advance, untouched) | the backend change |
| `application/artifact/ArtifactOperationService.java` | `283-416` (`markAvailable`: idempotent on already-AVAILABLE `:312-316`, completes pending op `:375`), `729-773` (`createOrAdvanceArtifact` — `UPDATE`=`createNextVersion`=new PENDING version) | reuse; the version semantics behind THE TRAP |
| `domain/registry/ArtifactType.java` | `5-8` | `SPEC`/`IMPLEMENTATION_PLAN`/`PR_OUTPUT` |
| `domain/registry/ArtifactStatus.java` | `5-9` | `pending`/`available`/`failed`/`late_or_stale` |
| `routes/workflows/$workflowRunId/index.tsx` | `1` & `12` (imports), `121` (`resolveSpecArtifactId` call), `168-176` (spec link block to mirror) | the frontend change |
| `features/workflows/approvalDecisionView.ts` | `493-511` (`resolveSpecArtifactId`), `540-573` (`resolveImplementationArtifact`) | resolvers (`.ts` sibling — react-refresh safe) |
| `routes/workflows/$workflowRunId/artifacts/$artifactId.tsx` | route def | the link target (exists) |
| `features/workflows/components/WorkflowDecisionBar.tsx` | `54` | `WaitingForReview` → impl-review bar (no change) |
| test: `RunnerBrokerUnitTest.java` | `577-646` (spec ingest pattern to mirror) | new plan/prOutput/enriched-head cases |
| test: `ImplementationPlanOrchestrationIT.java` | `108-115` (pending pin → available), `234-262` (`seedAvailableSpecArtifact`) | flip the assertion |
| test: `PrOutputOrchestrationIT.java` | `99-104` (pending pin → available), `195-202` (count helper) | flip the assertion |
| test: `SpecStageOrchestrationIT.java` | `151-211` | regression baseline (unchanged) |
| test: `ImplementationReviewDecisionBarContainer.test.tsx` | `30-104` | MSW/QueryClient render pattern to mirror for the route test |

### Architecture / boundaries

- The backend change is confined to `application/runner/RunnerBroker.java` (reusing the injected `artifactOperationService`) — no new `application → adapters` import (`[[application-cannot-import-adapters]]`).
- Orchestration ITs are `@SpringBootTest` + Testcontainers → already named `*IT` (Failsafe tier), Postgres-Testcontainers (not `docker-runner-it`) (`[[springboot-testcontainers-test-must-be-IT]]`, `[[docker-it-needs-exact-docker-runner-it-tag]]`).
- Frontend helpers stay in the `.ts` sibling; the route `.tsx` imports them (`[[frontend-react-refresh-no-fn-exports]]`).

### Logging Requirements (project-wide standard)

Every story leaves the touched services observable enough to debug a production incident without re-deploying.

- **Framework:** SLF4J + Logback. No `System.out`, no `printStackTrace()`.
- **This story:** no new production log branch. The existing `markAvailable` INFO `start`/`success` and `enrichPrOutputArtifact` INFO `enriched` lines now fire for `implementationPlan`/`prOutput`; verify they carry `workflowRunId` + the artifact public id and never log payload bytes/refs/secrets. The `prOutput` enrich + mark must stay best-effort (`WARN` + swallow on failure) so an availability error never unwinds the committed runner outcome.
- **Required context keys:** `workflowRunId`, `artifactId`, `correlationId`, `runnerExecutionId` (already present on these lines).
- **Forbidden:** payload bytes, secrets/tokens, raw refs.
- **Test contract:** the `verify(artifactOperationService).markAvailable(...)` unit assertions are the pins; add a list-appender/`OutputCaptureExtension` assertion only if a new branch is introduced (none is expected).

### Testing standards

- Backend unit tier = Surefire (no Docker); `*IT` = Failsafe (Docker/Testcontainers). Run a single IT via the `integration-test` *lifecycle phase* (not the `failsafe:integration-test` goal — `@{argLine}` crash, `[[maven-arglineation-goal-crash]]`) with `-Djacoco.skip=true`; skip the unit tier with `-Dtest=Zzz`.
- Drive async runner results with `runnerBroker.pollActiveExecutions()` after `drainQueue()` — never sleep on the 5s scheduler. The worker pool is OFF in the test profile (`[[story-3-17b-queue-activation-seams]]`).
- Frontend: vitest + MSW; run `prettier --write` before pushing (`[[prettier-gate-cascades-ci]]`); verify the lockfile/CI shape on Linux (`[[frontend-lockfile-cross-platform]]`, `[[verify-ci-fixes-in-clean-env]]`).
- Testcontainers needs a real Docker env — verify on Linux/Docker CI before merge (`[[wsl-linux-ci-reproduction]]`).

### Project Structure Notes

- No new module, package, Flyway migration, `DomainErrorCode`, `WorkflowEventType`, runner-contracts schema, config property, or OpenAPI change. Net production change: the generalized `RunnerBroker` marking + the one frontend link + the SPA re-embed.

### References

- [Source: docs/superpowers/specs/2026-06-16-waiting-for-review-availability-design.md] — authoritative design (sub-project #2b); the enrich/`available` interaction and scope boundaries.
- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3b-3] — stub, AC-shape, dependencies, sequencing `((3b-1 → 3b-2) and 3b-3 parallel → 3b-4 → 3b-5/3b-6)`.
- [Source: _bmad-output/implementation-artifacts/3a-9-spec-artifact-live-review-and-approval-readmodel-artifactid-and-artifact-read-endpoint.md] — the spec-stage availability pattern this story generalizes (Gate 1, `markSpecArtifactAvailable`).
- [Source: _bmad-output/implementation-artifacts/3b-2-two-dispatch-execution-orchestration-plan-approval-redispatches-pr-phase.md] — parallel story; DD1 documents the in-test availability seam this story makes a production caller for.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java:1350,1921,1948,1991] — the SPEC-only gate, the marking method, enrich, the enrich UPDATE call.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/artifact/ArtifactOperationService.java:283,312,736,765] — `markAvailable` (idempotent), `createOrAdvanceArtifact` (UPDATE=createNextVersion).
- [Source: deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx:121,168] and [.../features/workflows/approvalDecisionView.ts:493,540] — the spec link to mirror + the resolvers.
- Dependencies: 3a-9 (spec availability pattern — generalize), 3.12 (enrich UPDATE interaction), 1.12 (`markAvailable`, `[[markavailable-has-no-production-caller]]`), 2.1 (SPA embed), 3.28 (`implementation_review` bar — re-embed activates it; `resolveImplementationArtifact` live). Parallel: 3b-2 (in-test availability — now superseded by production wiring).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Backend unit: `RunnerBrokerUnitTest` — 48/48 (was 45; +3 new cases for plan/prOutput ingest availability + the enriched-head trap). The enriched-head log line confirms `artifactId=art_prv2_000002` is enriched and marked available (not the v1 `art_prv1_000001`).
- Backend ITs (Testcontainers/Failsafe `integration-test` phase, `-Djacoco.skip=true`): `ImplementationPlanOrchestrationIT` 3/3, `PrOutputOrchestrationIT` 3/3, `SpecStageOrchestrationIT` 4/4 (regression baseline), `WaitingForReviewTwoDispatchOrchestrationIT` 2/2 (3b-2 — its in-test mark-available helper short-circuits as predicted) = 12/12. IT logs show `eventType=artifact.available` for plan + prOutput runs.
- Frontend: new route test `routes/workflows/$workflowRunId/index.test.tsx` 2/2; full suite 1009/1009 (92 files); `tsc -b` clean; `eslint --max-warnings=0` clean; prettier clean.
- `mvn package` (root, `-DskipTests`): BUILD SUCCESS — SPA rebuilt and re-embedded into backend `static/` (`Copying 14 resources from deliveryline-frontend/target/dist to target/classes/static`).
- Backend `spotless:apply` cleaned the 3 touched Java files.

### Completion Notes List

- **AC1 — generalized ingest-loop marking.** Renamed `markSpecArtifactAvailable` → `markArtifactAvailable` and `SPEC_CHECKSUM_ALGORITHM` → `ARTIFACT_CHECKSUM_ALGORITHM` (behavior unchanged: checksum over `payloadBytes`, `storageRef` from `opResult`, no-op on `null` storageRef, SYSTEM actor). Widened the call-site gate from `SPEC`-only to `SPEC || IMPLEMENTATION_PLAN || PR_OUTPUT`.
- **AC2 — THE TRAP (enriched head).** Added a second `markArtifactAvailable(result, enrichedBytes, correlationId)` inside `enrichPrOutputArtifact`, after the successful `recordOperation(UPDATE)` and inside the existing best-effort try/catch. Verified `createOrAdvanceArtifact` UPDATE = `createNextVersion` (new PENDING v2) and that `markAvailable` finds the v2's fresh pending op (`ArtifactOperationService.java:299-327`), so the highest-version `prOutput` (the resolver's target) ends AVAILABLE. The v1 in-loop mark also fires (harmless; the resolver picks the highest version).
- **AC3 — no spec regression / auto-advance unchanged.** Spec stays byte-identical (`SpecStageOrchestrationIT` green). Auto-advance delegation untouched; no new failure branch; artifact-ingestion-failure handling untouched.
- **AC4 — frontend link.** `routes/workflows/$workflowRunId/index.tsx` now imports `resolveImplementationArtifact`, derives `implArtifactId`, and renders an "Open the implementation output →" link to the existing artifact-viewer route, gated on `implArtifactId !== undefined`. Spec link unchanged; no decision-bar change.
- **AC5 — re-embed.** `mvn package` re-embedded the SPA. NOTE: the live manual verification on a running `WaitingForReview` run (e.g. `run_ae258…`) was NOT performed headlessly — left for the reviewer to confirm on the running app.
- **AC6 — tests.** New unit cases + enriched-head case; flipped the deliberate-`pending` pins in the two execution-stage orchestration ITs to assert `status=available` (superseding 3a-9's Decision-D1 posture, DD2); spec regression baseline unchanged; new frontend route test asserts the link renders with a `prOutput` artifactId and is absent with only a `spec`.
- **AC7 — no new production surface.** No new `DomainErrorCode`, `WorkflowEventType`, Flyway migration, runner-contracts schema, config property, REST endpoint, OpenAPI change, ArchUnit rule, or transition-table edge. Net production change = the generalized `RunnerBroker` marking (widened conditional + one post-enrich mark) + the one frontend link.
- **Logging.** No new production log branch; the existing `markAvailable` start/success + `enrichPrOutputArtifact` INFO lines now fire for `implementationPlan`/`prOutput` (verified they carry `workflowRunId` + artifact public id, no payload/secret). The `verify(artifactOperationService).markAvailable(...)` unit assertions are the observability pins.

### File List

- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` (modified — constant rename, method rename + generalized javadoc, widened ingest-loop gate, post-enrich mark)
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerBrokerUnitTest.java` (modified — +3 tests + 2 stub helpers + `recordResultWithStorageRef` helper)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/ImplementationPlanOrchestrationIT.java` (modified — flipped pending→available assertion + corrected stale class javadoc)
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/PrOutputOrchestrationIT.java` (modified — flipped pending→available assertion)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.tsx` (modified — import + `implArtifactId` + implementation-output link)
- `deliveryline-frontend/src/routes/workflows/$workflowRunId/index.test.tsx` (new — route test for the link)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status: ready-for-dev → in-progress → review)

### Change Log

| Date | Change |
|---|---|
| 2026-06-16 | Story 3b.3 drafted: generalize spec-only ingest availability to `implementationPlan`/`prOutput` + surface the implementation-output link + re-embed SPA. Central trap identified by code trace — the `prOutput` enrich `createNextVersion`s a new PENDING head, so the enriched head (not just v1) must be marked available, else the frontend resolver points the reviewer at a `pending` artifact. |
| 2026-06-17 | Story 3b.3 implemented (Status → review): backend `markArtifactAvailable` generalized to SPEC/IMPLEMENTATION_PLAN/PR_OUTPUT in the ingest loop + the enriched `prOutput` head marked available post-enrich (THE TRAP, AC2); frontend "Open the implementation output →" link surfaced at the detail route; SPA re-embedded via `mvn package`. Tests: RunnerBrokerUnitTest 48/48, orchestration ITs 12/12, frontend 1009/1009, tsc/lint/spotless clean. Live manual run-verification deferred to reviewer. |

### Review Findings (code review 2026-06-17)

- [x] [Review][Decision→Patch FIXED] In-loop `markArtifactAvailable` (now widened to IMPLEMENTATION_PLAN/PR_OUTPUT) was UNGUARDED, unlike its deliberately best-effort enrich twin — `RunnerBroker.java:1358` calls `markArtifactAvailable` inside `handleSuccess` (invoked at `:1222` within an outer try/**finally that has no catch**, `:1224`) and BEFORE `recordCompleted` (`:1539`). If the mark threw (e.g. a `DomainException` from `markAvailable`'s checksum re-read/state-transition, or the `IllegalStateException` digest-missing path `:1940-1944`), the exception escaped `onResult`, `completionOutcome` stayed null, `recordCompletion` was skipped, and the execution was stranded RUNNING (re-harvested). The enrich twin (`:2027`) is wrapped in exactly such a best-effort try/catch (`:2028-2039`). **RESOLUTION (Alex chose option 1):** wrapped the in-loop mark in a best-effort `try { … } catch (RuntimeException error) { log.warn(…) }` mirroring the enrich twin, so a marking failure logs + swallows instead of unwinding a successfully-executed run (the artifact stays `pending` until a re-harvest re-marks it). RunnerBrokerUnitTest 48/48 green; Spotless clean. (sources: blind+edge, verified against source)
- [x] [Review][Patch FIXED] Unit ingest tests asserted checksum *algorithm* only via an `any()` matcher — didn't pin "checksum over the (enriched) bytes" [`RunnerBrokerUnitTest.java` plan + enriched-head cases] — `markAvailable` is mocked and the captured `ArtifactChecksum` was asserted only for `.algorithm() == "SHA-256"`; the hex digest was `any()`. A regression marking v2 with v1's checksum (wrong bytes) would still pass. **RESOLUTION:** strengthened `implementationPlanIngestMarksArtifactAvailable` (capture the `recordOperation` command, assert the marked checksum `.value()` == `digestHex("SHA-256", command.payloadContent())`) and `prOutputEnrichedHeadIsTheArtifactMarkedAvailable` (capture both `recordOperation` commands, pin the v2 checksum to the digest of the UPDATE/enriched command's `payloadContent()` — proving v2 is not marked with v1's checksum). 48/48 green. (source: blind)
- [x] [Review][Defer] AC5 live `WaitingForReview` run verification not performed headlessly [story Dev Notes] — deferred, self-disclosed; reviewer must confirm the impl-output link + `available` read model on a running app. (source: auditor)
- [x] [Review][Defer] Frontend impl-output link gated on `artifactId !== undefined`, not on AVAILABLE status [`index.tsx:402`] — deferred, minor UX & partly out of scope (3b-5 builds the real `prOutput` renderer). If the best-effort enrich mark fails, the resolver returns the highest-version (PENDING) `prOutput` id and the link renders to a non-acceptable artifact. (source: edge)
