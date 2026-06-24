# Story 3d.9: Foundation-Gate Widening + Test Suite Extension

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

> **⚠️ READ FIRST — THIS IS A CONSOLIDATION/AGGREGATION STORY, NOT BUILD-FROM-SCRATCH. The #1 disaster is re-implementing tests that already exist.** Stories 3d-1..3d-8 already shipped ~90% of the Epic 3d test surface (verified on the live codebase 2026-06-24). Your job is to make those existing source-of-truth tests **named, enforced members of the foundation gate**, resolve the coverage-floor decision, and confirm the CI wiring — **not** to re-author assertions. This is the exact twin of the already-done **story 3c-11** ([[story-3c-11-foundation-gate-widening-reconciliations]]); follow that playbook.
>
> Each AC below is tagged **[EXISTS]** (already shipped — verify, do not rebuild), **[GAP]** (the net-new work for this story), or **[DECISION]** (a judgment call with a default chosen).
>
> **THE PRIMARY NET-NEW DELIVERABLE (one file):** widen `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java` — currently at **Contract #20** (Epic 3c) — with Epic 3d `@Nested` **Contracts #21–#26** that **delegate-run** the existing Epic 3d source-of-truth tests via `FoundationGateAssertions.delegateRunAssertGreen("3d.N", "<FQN>")`. The Launcher API discovers by FQN regardless of Maven tag, so unit `*Test` and Testcontainers `*IT` classes aggregate without retagging. Update the class Javadoc (it says "Epic 3c (Contracts #16–#20)" today — extend to "Epic 3d (Contracts #21–#26, story 3d.9)").
>
> **DO NOT:** add new production code, new Flyway migrations, new error codes, new event types, new OpenAPI operations, or new CI YAML jobs. If you find yourself writing prod code, stop — you have misread the story.

## Story

As a backend + frontend developer,
I want the foundation gate and the CI test tiers to explicitly assert Epic 3d's contracts (reviewer model, manual execution, live logs/console, provider usage, soft-hide) and the new coverage floors,
so that per-step-execution-control regressions are caught at the same named CI gate as the rest of the system, and nothing 3d shipped can silently drift or be deleted.

## Acceptance Criteria

> These ACs are **reconciled** against the live codebase. The exhaustive 2026-06-24 audit found every 3d source-of-truth test, all three Epic 3d error codes (three-sites), the redaction fixtures, and the ADR 0025 security sign-off already present. The reconciled wording marks what to **aggregate** vs **build**.

1. **[EXISTS→aggregate] Registry / state / event / error-code drift is gate-asserted.** The `manual` runner kind, `WaitingForManualExecution` state, manual-lifecycle `WorkflowEventType`s, `review_outcome`, the `reviewer` connector role, the `rev_` prefix, and `step_reviews` are authoritative and drift-tested (registry ↔ DB CHECK ↔ API schema ↔ fixtures), and the new domain error codes (`REVIEWER_MODEL_NOT_CONFIGURED`, `MANUAL_EXECUTION_NOT_APPLICABLE`, `ARCHIVE_NOT_APPLICABLE`) are registered three-sites. **These assertions already live in `RegistryContractTest` / `FlywaySchemaContractTest` / `ProblemDetailsCoverageFoundationContract` (already gate members).** Net-new: add **named Epic-3d `@Nested` gate contracts** that delegate to them for traceability (the 3c-11 pattern — Contract #16 delegated to the same broad classes for Epic-3c visibility).

2. **[EXISTS→aggregate] Advisory-verdict contract is gate-asserted.** A test asserts the reviewer verdict is advisory-only (human decision unaffected; `reviewer_gating_enabled` never consulted in this epic) and a no-binding project is byte-identical to pre-3d. **Already covered by `WorkflowInspectionServiceReviewerVerdictTest` + `StepReviewPersistenceAdapterContractTest` + `DockerRunnerAdapterReviewerCredentialTest`.** Net-new: aggregate them under a named contract.

3. **[EXISTS→aggregate] Live-stream/console posture is gate-asserted.** Tests assert persisted/exported log content is unchanged by the live view, the console is read-only + live-only + governed-history-recorded, and both surfaces are localhost-only. **Already covered by `RunnerLogStreamControllerTest`, `StepLogStreamServiceTest`, `LocalRunnerLogStoreTest`, `DefaultDockerEngineGatewayTest`, `DiagnosticConsoleServiceTest`, `RunnerDiagnosticConsoleControllerTest`, `RunnerConsoleStreamProfileWiringContractTest`.** Net-new: aggregate them under a named contract.

4. **[EXISTS→aggregate] Soft-hide append-only invariant is gate-asserted.** A test asserts hiding/un-hiding never mutates or deletes `workflow_events` (FR47) and archived runs remain audit-queryable. **Already covered by `WorkflowArchiveServiceTest` + `WorkflowArchiveServiceAppendOnlyIT` + `ArchiveRunEndpointContractTest`.** Net-new: aggregate under a named contract. Also aggregate provider-usage (`ProviderUsageSnapshotPersistenceAdapterIT`, `ProviderUsageStatusControllerTest`) and add a **direct manifest guard** for `provider-usage-snapshot.json` (mirror the Contract #19 direct guard for project-credential fixtures so the 3d-7 fixture can't silently drop from the AR10 sweep).

5. **[EXISTS→confirm] Frontend Vitest + Playwright + axe coverage is extended for the 3d surfaces and runs in the CI tiers.** The Reviewer Verdict Panel, Manual Execution Surface, Step Execution Log Viewer, Read-only Console, Provider Limit Status indicator, and queue archived state already have Vitest (`ReviewerVerdictPanel.test.tsx`, `ReadOnlyDiagnosticConsole.test.tsx`, …) + Playwright (`manual-execution.spec.ts`, `runner-log-viewer.spec.ts`, `diagnostic-console.spec.ts`, `queue-include-archived.spec.ts`) + axe, run by the existing `frontend-build-tests` / `frontend-e2e` tiers that the `foundation-gate` job already `needs:`. Net-new: **confirm** each 3d surface has a Vitest + axe test and an e2e path; add a focused test **only** where the audit finds a concrete gap.

6. **[DECISION] Coverage thresholds for the new 3d code.** The standing floors are BUNDLE 0.75 LINE / 0.55 BRANCH plus per-PACKAGE 0.80 floors for net-new **dedicated** Epic-3 packages (and `infrastructure.crypto` 0.90). The new 3d application code lives predominantly in the **pre-existing shared roots** `org.dradgo.application.runner` (manual dispatch/submission, log stream, reviewer harvest) and `org.dradgo.application.workflow` (diagnostic console, archive) — the **same un-isolatable situation as 3c-11 D1**: a per-PACKAGE floor on those roots would govern the whole large package, not just 3d code. **Default (mirrors 3c-11 D1):** do **not** add an isolated floor on the shared roots; rely on the BUNDLE floor + the dedicated `*Test`/`*IT` suites (all of which exist and measure ≥0.80), and **record measured per-class LINE numbers in a pom comment** as the AC6 commitment. **Add a per-PACKAGE 0.80 floor only if a genuinely net-new dedicated sub-package exists** (verify with a package scan — none was found in the audit; `application.project` already has its 0.80 floor from 3c-3). Document the decision + measured numbers; **no silent skip**.

7. **[EXISTS→cite] Security-review sign-off is a recorded gate artifact.** The ADR 0025 (live-stream + read-only console) security review is signed off — `docs/adr/0025-live-observability-and-readonly-console.md` Status line + sign-off section ("Signed off 2026-06-22 by Alex, workflow owner / security reviewer; story 3d-6"), mirroring the ADR 0013 gate. Net-new: **cite it** in this story's Completion Notes as the gate artifact (no re-review).

8. **[EXISTS→confirm + GAP-guard] "Epic 3d suites green" is required for foundation-gate PRs.** The `.github/workflows/ci.yml` `foundation-gate` job already `needs:` the backend + frontend tiers and runs `mvn verify -Pfoundation-gate -Dit.test='*FoundationGateVerificationTest*'`. Once the new Contracts #21–#26 are added, those Epic-3d source-of-truth tests become **gate-blocking by construction** (the aggregator fails the gate if any is red). Net-new: confirm the job needs the right tiers; **do not** add a new CI job.

9. **[GAP] Drift stays green end-to-end.** After widening, `mvn verify -Pfoundation-gate` is green; breaking any aggregated Epic-3d source-of-truth test (regression proof) makes the gate fail with a `[story 3d.N]`-tagged message; `spotless:check` / `RegistryContractTest` / `OpenApiSnapshotContractTest` remain byte-identical green (this story changes test + pom-comment only, so no OpenAPI/registry drift is expected).

## Tasks / Subtasks

- [ ] **Task 1 — Verify-don't-rebuild sweep (do this FIRST)** (AC: 1–5)
  - [ ] Open each source-of-truth class listed in Dev Notes → "Epic 3d source-of-truth tests" and confirm it exists and is green on the current tree (`mvn -pl deliveryline-backend test -Dtest=RegistryContractTest,FlywaySchemaContractTest` etc., and the `*IT`/`*ContractTest` under the `integration-test` phase). If any is **missing** (not just renamed), THAT is the only place you author a test — everything else is aggregation.
  - [ ] Confirm the three Epic-3d `DomainErrorCode`s are three-sites (`DomainErrorCode` + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json`) and that `ProblemDetailsCoverageFoundationContract` (already gate Contract #7) round-trips them — it iterates ALL codes, so 3d codes are already covered; no per-code gate contract needed.
- [ ] **Task 2 — Widen `FoundationGateVerificationTest` with Epic 3d Contracts #21–#26** (AC: 1, 2, 3, 4) — THE PRIMARY DELIVERABLE
  - [ ] Insert after `Contract20ConfigInversionParity` (file currently ends at line ~560), following the exact `@Nested @Tag("foundation-gate") @DisplayName("Contract #N — … (story 3d.M)")` + `@Test` + `FoundationGateAssertions.delegateRunAssertGreen("3d.M", "<FQN>")` idiom. Add per-contract Javadoc citing the story + what each delegate proves (mirror #20's Javadoc).
  - [ ] **Contract #21 — Reviewer registries + schema (story 3d.1):** delegate-run `org.dradgo.contract.RegistryContractTest` (review_outcome / reviewer role / rev_ prefix) + `org.dradgo.contract.FlywaySchemaContractTest` (step_reviews table + rev_ prefix). (These are broad classes already implicitly green; the named contract is for Epic-3d traceability, exactly as Contract #16 did for Epic 3c.)
  - [ ] **Contract #22 — Reviewer execution advisory-only + persistence (story 3d.2):** delegate-run `org.dradgo.application.workflow.WorkflowInspectionServiceReviewerVerdictTest`, `org.dradgo.adapters.persistence.StepReviewPersistenceAdapterContractTest`, `org.dradgo.adapters.runner.DockerRunnerAdapterReviewerCredentialTest`.
  - [ ] **Contract #23 — Manual execution park + submission (story 3d.3/3d.4):** delegate-run `org.dradgo.application.workflow.ManualExecutionParkIT`, `org.dradgo.adapters.rest.ManualArtifactEndpointContractTest`, `org.dradgo.adapters.rest.ManualBundleEndpointContractTest`, `org.dradgo.application.runner.ManualArtifactSubmissionIT` (+ the unit `ManualExecutionDispatcherTest` / `ManualArtifactSubmissionServiceTest` if you want unit-level coverage in the gate; ITs are the stronger proof).
  - [ ] **Contract #24 — Live logs + read-only console posture (story 3d.5/3d.6):** delegate-run `org.dradgo.adapters.rest.RunnerLogStreamControllerTest`, `org.dradgo.application.workflow.DiagnosticConsoleServiceTest`, `org.dradgo.adapters.rest.RunnerDiagnosticConsoleControllerTest`, `org.dradgo.adapters.runner.docker.DefaultDockerEngineGatewayTest`, `org.dradgo.adapters.runner.RunnerConsoleStreamProfileWiringContractTest` (read-only / live-only / localhost / persisted-log-unchanged).
  - [ ] **Contract #25 — Provider usage + redaction (story 3d.7):** delegate-run `org.dradgo.adapters.persistence.ProviderUsageSnapshotPersistenceAdapterIT`, `org.dradgo.adapters.rest.ProviderUsageStatusControllerTest`. **Plus a direct manifest guard** (a `@Test` in this nested class, not a delegate) asserting `provider-usage-snapshot.json` is enumerated in `redaction-fixtures/fixtures-manifest.json` — mirror the Contract #19 direct guard (lines ~505–525) so the 3d-7 fixture can't silently drop from the AR10 sweep. (The redaction *sweep* itself is already Contract #9.)
  - [ ] **Contract #26 — Soft-hide append-only invariant (story 3d.8):** delegate-run `org.dradgo.application.workflow.WorkflowArchiveServiceAppendOnlyIT`, `org.dradgo.application.workflow.WorkflowArchiveServiceTest`, `org.dradgo.adapters.rest.ArchiveRunEndpointContractTest`.
  - [ ] Update the class-level Javadoc (lines 19–43): extend "…Epic 3c (Contracts #16–#20, story 3c.11)" to add "…and Epic 3d (Contracts #21–#26, story 3d.9)".
- [ ] **Task 3 — Coverage-floor decision (AC6)** (AC: 6)
  - [ ] Run a package scan of the Epic-3d production code (`org.dradgo.application.runner.*`, `org.dradgo.application.workflow.*`) and confirm whether any **net-new dedicated sub-package** exists (e.g. `application.runner.review`, `application.observability`). The audit found none — 3d code is in the shared `application.runner` / `application.workflow` roots.
  - [ ] **If a dedicated sub-package exists:** add it to the existing per-PACKAGE 0.80 `<rule>` in `pom.xml` (DOTTED form — slash form silently matches nothing) with a `Story 3d-9 (AC6)` comment.
  - [ ] **Else (default):** add a `Story 3d-9 (AC6)` comment block to the pom `<rules>` (next to the 3c-11 D1 comment) recording: the 3d app code lives in shared roots with no isolatable floor; it is guarded by BUNDLE (0.75/0.55) + the dedicated suites; and the measured per-class/per-suite LINE coverage for the key 3d classes (run the merged jacoco report and paste real numbers — `WorkflowArchiveService`, `ManualArtifactSubmissionService`, `ManualExecutionDispatcher`, `DiagnosticConsoleService`, `StepLogStreamService`, the reviewer harvest path). **No silent skip** — the decision + numbers are the AC6 commitment.
- [ ] **Task 4 — Frontend tier confirmation (AC5)** (AC: 5)
  - [ ] Confirm each 3d surface has a Vitest + axe test and an e2e path (list in Dev Notes). The 3d FE code is under `src/features/workflows/**` (85% floor already). Add a focused Vitest/axe test ONLY if the audit surfaces a concrete missing surface (e.g. Provider Limit Status indicator if it lacks its own component test) — otherwise confirm-only.
  - [ ] Confirm `vitest.config.ts` floors cover the 3d folders (they fall under `src/features/workflows/** @ 85%`); per the 3c-11 D2 decision an isolated 3d-folder floor is **not** required — record that choice.
- [ ] **Task 5 — Run the gate + regression proof** (AC: 8, 9)
  - [ ] Green run: `./mvnw -pl deliveryline-backend verify -Pfoundation-gate -Dit.test='*FoundationGateVerificationTest*' -Dtest='*FoundationGateVerificationTest*' -DfailIfNoTests=false` (lifecycle `verify` phase, **never** the `failsafe:`/`surefire:` direct goal — [[maven-arglineation-goal-crash]]). Expect all Contracts #1–#26 green.
  - [ ] Regression proof: temporarily break one aggregated 3d source-of-truth assertion (e.g. flip an expected value in `WorkflowArchiveServiceAppendOnlyIT`) and confirm the gate fails with a `[story 3d.N] …` tagged message. **Scope Surefire OUT** so only the Failsafe aggregator runs and does the catching (the 3c-11 trap): `-Dtest=FoundationGateVerificationTest -Dit.test=FoundationGateVerificationTest -DfailIfNoTests=false` — otherwise a broken *unit* test dies in Surefire (test phase) before the aggregator runs and you never see the `[story 3d.N]` message. Revert the deliberate break.
  - [ ] Confirm `spotless:check` is green (run `spotless:apply` first if the gate baseline shows format violations — the 3c-11 baseline-contamination trap).
- [ ] **Logging instrumentation** (cross-cutting; required on every story)
  - [ ] **N/A for this story — no new production service/REST/CLI surface.** This is a test-aggregator + pom-comment story; all logging on the 3d surfaces was instrumented in 3d-1..3d-8. Record "N/A — consolidation story, no prod code" in Completion Notes (do not fabricate log lines for tests).

## Dev Notes

### Why this is a consolidation story (the central guardrail)

Identical in shape to story 3c-11 ([[story-3c-11-foundation-gate-widening-reconciliations]]), which shipped as **pure aggregation**: its entire footprint was `FoundationGateVerificationTest.java` (new delegate-run contracts) + a `pom.xml <rules>` comment + sprint-status. Expect the same here: **zero net-new prod code, Flyway, error-code, OpenAPI, or CI-YAML.** The `FoundationGateAssertions.delegateRunAssertGreen(storyRef, FQN)` helper (`deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateAssertions.java`, lines ~35–99) uses the JUnit Platform Launcher to run any class by FQN and re-tags Docker/Testcontainers env failures as `[env]` (not `[story X.Y]`), so ITs aggregate safely.

### Epic 3d source-of-truth tests (verify, do NOT rebuild) — audited 2026-06-24

| 3d story | Source-of-truth test(s) (FQN) | Proves |
|---|---|---|
| 3d-1 | `org.dradgo.contract.RegistryContractTest`, `org.dradgo.contract.FlywaySchemaContractTest` | review_outcome / reviewer role / rev_ prefix drift; step_reviews schema |
| 3d-2 | `application.workflow.WorkflowInspectionServiceReviewerVerdictTest`, `adapters.persistence.StepReviewPersistenceAdapterContractTest`, `adapters.runner.DockerRunnerAdapterReviewerCredentialTest` | advisory-only verdict, no-binding parity, self-review flag, per-project reviewer credential |
| 3d-3/3d-4 | `application.workflow.ManualExecutionParkIT`, `adapters.rest.ManualArtifactEndpointContractTest`, `adapters.rest.ManualBundleEndpointContractTest`, `application.runner.ManualArtifactSubmissionIT` (+ unit `ManualExecutionDispatcherTest`, `ManualArtifactSubmissionServiceTest`) | manual park (no container/queue), bundle retrieval, submission re-enters validation, idempotency, MANUAL_EXECUTION_NOT_APPLICABLE |
| 3d-5/3d-6 | `adapters.rest.RunnerLogStreamControllerTest`, `application.runner.StepLogStreamServiceTest`, `adapters.files.LocalRunnerLogStoreTest`, `adapters.runner.docker.DefaultDockerEngineGatewayTest`, `application.workflow.DiagnosticConsoleServiceTest`, `adapters.rest.RunnerDiagnosticConsoleControllerTest`, `adapters.runner.RunnerConsoleStreamProfileWiringContractTest` | live+finished log, server-side allowed-action gating, localhost-only, read-only (no stdin), live-only console, governed console.opened/closed |
| 3d-7 | `adapters.persistence.ProviderUsageSnapshotPersistenceAdapterIT`, `adapters.rest.ProviderUsageStatusControllerTest` | usage snapshot persist + surface, per-credential attribution, gate denial; redaction via Contract #9 + manifest guard |
| 3d-8 | `application.workflow.WorkflowArchiveServiceTest`, `application.workflow.WorkflowArchiveServiceAppendOnlyIT`, `adapters.rest.ArchiveRunEndpointContractTest` | append-only invariant (workflow_events untouched), archived runs audit-queryable, ARCHIVE_NOT_APPLICABLE |

(Confirm exact FQNs/paths at implementation time; the audit located all of them. If `ManualArtifactSubmissionServiceTest`/`ManualExecutionDispatcherTest` package paths differ, the IT-level delegates are the load-bearing proof.)

### Net-new vs existing (the GAP list)

- **GAP:** Contracts #21–#26 in `FoundationGateVerificationTest` + Javadoc update (Task 2). This is ~90% of the work.
- **GAP:** the provider-usage-snapshot.json direct manifest guard (Task 2, Contract #25) — mirrors the Contract #19 project-credential guard.
- **DECISION:** the AC6 coverage-floor comment / optional sub-package floor (Task 3).
- **CONFIRM-ONLY:** FE tiers (Task 4), security sign-off citation (AC7), CI job needs (AC8).
- **EXISTS (do nothing but verify):** all error codes three-sites (`ProblemDetailsCoverageFoundationContract` already iterates them), `RedactionAdversarialFoundationContract` (Contract #9) already sweeps the provider-usage + project-credential fixtures, ADR 0025 sign-off recorded.

### Implementation traps inherited from 3c-11 (apply directly)

- **Regression-proof requires scoping Surefire out.** Breaking a source-of-truth *unit* test fails it in Surefire (test phase, full suite) BEFORE the Failsafe aggregator runs — the build dies early and you never see the `[story 3d.N]` aggregator message. Run with `-Dtest=FoundationGateVerificationTest -Dit.test=FoundationGateVerificationTest -DfailIfNoTests=false` so ONLY the aggregator runs and catches.
- **Don't skip the frontend on a full `verify`.** `-Dfrontend-maven-plugin.skip=true` on a full `verify` reds `BundledJarPackagingContractTest` (the shared skip also skips `copy-frontend-dist` → no index.html in the jar). Rely on the existing `deliveryline-frontend/target/dist`; `-pl deliveryline-backend` resolves the frontend pom from `.m2` without `-am`.
- **Spotless baseline.** Run `spotless:apply` before the gate verify so a pre-existing format violation doesn't contaminate the run; then `spotless:check` green.
- **Maven phase, not goal** ([[maven-arglineation-goal-crash]]): always the `verify`/`integration-test` lifecycle phase for `*IT`/`*ContractTest`, never `failsafe:integration-test` / `surefire:test` direct goals (the `@{argLine}` fork crash).

### Architecture / boundary guardrails

- This story touches **test + build only**: `FoundationGateVerificationTest.java` (+ optional `pom.xml` comment/floor + sprint-status). No `application..`/`adapters..`/`domain..`/`infrastructure..` production change. If a prod file changes, you have left the story's scope.
- New `@Nested` contracts must carry `@Tag("foundation-gate")` (both the class and the inner classes are tagged in the existing file) so Maven routing keeps them in the dedicated profile tier only.
- Use the DOTTED package form in any JaCoCo `<include>` (slash form silently matches nothing — verified in the 3.35 comment).

### Project Structure Notes

- **Modified (backend test):** `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java` (Contracts #21–#26 + Javadoc).
- **Modified (build, optional/decision):** `deliveryline-backend/pom.xml` (`<rules>` AC6 comment, + a per-PACKAGE 0.80 include ONLY if a dedicated 3d sub-package exists).
- **Possibly modified (frontend test):** a focused Vitest/axe test ONLY if Task 4 finds a concrete gap (default: none).
- **Modified:** `_bmad-output/implementation-artifacts/sprint-status.yaml` (3d-9 → ready-for-dev / done by the dev/review flow).
- **NO** prod code, **NO** Flyway, **NO** OpenAPI regen, **NO** new CI job, **NO** new error code/event type/allowed-action.

### Logging Requirements (project-wide standard)

**N/A for this story** — it is a test-aggregator + build-config story with no new production service, REST, CLI, or scheduled surface. Logging on every 3d feature surface was instrumented in its own story (3d-2..3d-8). Record the N/A rationale in Completion Notes; do not add log assertions for the aggregator test.

### References

- [Source: _bmad-output/planning-artifacts/epic-03d-per-step-execution-control.md#Story-3d-9] — authoritative ACs (L177–192); epic context (gate widening + test-suite extension).
- [Source: deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java] — the aggregator; ends at Contract #20 (L528–560); class Javadoc L19–43 to extend; the `@Nested`/`delegateRunAssertGreen` idiom (Contract #20 L531–559 is the template; Contract #19 L505–525 is the direct-manifest-guard template for the provider-usage guard).
- [Source: deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateAssertions.java] — `delegateRunAssertGreen(storyRef, FQN)` (L35–99) + `tagged(storyRef, detail)` (L31–33); env-failure re-tagging.
- [Source: deliveryline-backend/pom.xml] — `foundation-gate` profile (L693–729); JaCoCo `<rules>` (L487–569) — BUNDLE 0.75/0.55 (L488–501), per-PACKAGE 0.80 includes (L511–531, DOTTED), infra.crypto 0.90 (L538–550), the 3c-11 D1 no-isolatable-floor comment (L551–568) to mirror for AC6.
- [Source: .github/workflows/ci.yml] — `foundation-gate` job (L1041–1149); `needs:` tiers incl. `backend-contract-tests`, `frontend-build-tests`, `frontend-e2e` (L1050–1065); run cmd `mvn verify -Pfoundation-gate -Dit.test='*FoundationGateVerificationTest*'` (L1114–1139).
- [Source: deliveryline-backend/src/test/java/org/dradgo/foundation/ProblemDetailsCoverageFoundationContract.java] — already round-trips EVERY DomainErrorCode (incl. the 3 Epic-3d codes); reached via gate Contract #7 — no per-3d-code gate contract needed.
- [Source: deliveryline-backend/src/test/java/org/dradgo/foundation/RedactionAdversarialFoundationContract.java] — AR10 sweep over all fixtures incl. provider-usage + project-credential; gate Contract #9.
- [Source: deliveryline-backend/src/test/resources/redaction-fixtures/fixtures-manifest.json] — `provider-usage-snapshot.json` (3d-7) + the 3 `project-credential-*.json` fixtures (the manifest the Contract #25 direct guard asserts).
- [Source: docs/adr/0025-live-observability-and-readonly-console.md] — security sign-off (Status line + L32–34, "Signed off 2026-06-22 by Alex"); the AC7 gate artifact to cite.
- [Source: docs/adr/0013-credential-encryption.md] — the sign-off-as-recorded-artifact precedent (mirrored by 0025).
- Memory: [[story-3c-11-foundation-gate-widening-reconciliations]] (the twin — consolidation playbook, Surefire-scoping + frontend-skip + spotless-baseline traps, D1 floor decision, D2 axe-gate decision); [[maven-arglineation-goal-crash]] (phase-not-goal); [[story-3d-7-provider-usage-implementation]] + [[story-3d-4-manual-artifact-submission-implementation]] (what 3d-7/3d-4 actually shipped — the tests to aggregate).

### Open Decisions surfaced for review (defaults chosen; do not block implementation)

1. **AC6 coverage floor** — default: **no isolated per-PACKAGE floor** on `application.runner`/`application.workflow` (shared roots; 3c-11 D1 precedent); rely on BUNDLE + dedicated suites; record measured per-class numbers in a pom comment. Alternative: split out a dedicated 3d sub-package and floor it (out of scope — no such package exists). Proceeding with the default.
2. **AC5 frontend isolated floor** — default: rely on the existing `src/features/workflows/** @ 85%` floor (3c-11 D2 precedent: axe-in-Vitest + e2e journey is sufficient; an isolated 3d-folder floor is not a repo pattern). Proceeding with the default.
3. **Contract granularity** — default: 6 named contracts #21–#26 (one per 3d theme, grouping related stories) so CI failures point at a theme. Alternative: one contract per 3d story (#21–#28). Proceeding with 6 grouped contracts (matches the 5-contract Epic-3c grouping density).

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
