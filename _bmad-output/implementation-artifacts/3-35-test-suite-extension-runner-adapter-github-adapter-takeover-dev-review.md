# Story 3.35: Test Suite Extension (Runner Adapter, GitHub Adapter, Takeover, Dev Review)

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->
<!-- Created 2026-06-19 via bmad-create-story. This is the Epic-3 TEST-CAPSTONE story — the twin of
     story 2.27 (the Epic-2 capstone). Like 2.27, it is a CONSOLIDATE + AUDIT + CLOSE-NARROW-GAPS +
     WIRE-NIGHTLY story over an ALREADY-SUBSTANTIAL Epic-3 test suite, NOT a greenfield "stand up
     coverage" story. Read the "⚠️ READ FIRST" Scope Decisions block BEFORE writing a line of code:
     nearly everything the epic ACs describe ALREADY EXISTS (RealRunnerContractIT, the lifecycle ITs,
     the takeover ITs, the accept/reject contract tests, every Epic-3 frontend component test, the
     Playwright harness). The genuinely net-new deliverables are NARROW and enumerated in S1. -->

## Story

As a backend + frontend developer,
I want the test suite from story 2.27 (Vitest + Playwright + axe + MSW) and the backend integration tiers extended to cover Epic 3's new surfaces — `DockerRunnerAdapter` integration scenarios, real + mock GitHub adapter parity, takeover flow end-to-end, dev review accept/reject scenarios — under the **existing** CI tier structure,
so that Epic 3's new code paths have automated coverage and regressions are caught at the same CI gates as Epic 2's surfaces.

---

## ⚠️ READ FIRST — Scope Decisions & Central Reconciliations (the epic ACs describe an end-state; here is what is actually true in this repo today)

> The epic text for story 3.35 was written at planning time and describes the *target* coverage as if it were greenfield. **By the time this story enters the cycle, the overwhelming majority of that coverage already exists** — it was authored incrementally by the stories that shipped each surface (3.1/3.2/3.8 runner ITs, 3.13/3.14/3.33 GitHub parity, 3.20/3.21/3.22 service tests, 3.26–3.31 frontend component tests, 2.27 Playwright). The implementing dev MUST honor these reconciliations over the literal epic AC wording. Where an AC's literal wording conflicts with a reconciliation, the reconciliation wins. **Your job is to AUDIT, DOCUMENT the coverage map, CLOSE the narrow gaps, and WIRE the nightly + threshold machinery — NOT to re-author passing tests.**

### S1 — The net-new deliverables are NARROW. Everything else is audit + verify.

The genuinely net-new work, in priority order:

1. **`gh-real-tests` Maven profile + nightly CI job (AC3).** The `@Tag("gh-real-tests")` and `GitHubRealLiveIntegrationTest` already exist (gated by `@EnabledIfSystemProperty(deliveryline.github.live-tests=true)`); the parity *contract* runs every foundation-gate via `GitHubMockVsRealParityFoundationContract` (MockRestServiceServer-stubbed, no network). **Missing:** a Maven `-Pgh-real-tests` profile that selects the `gh-real-tests` tag, and a **scheduled (nightly, `push:main`/`schedule:`) CI job** that runs it against a documented test repository with `GITHUB_TOKEN` — skipped in PR CI by default. This is the single biggest net-new piece.
2. **Diff + plan-step XSS fixtures in the build-blocking loop (AC8).** The `xss-fixtures/` loop (`SafeMarkdownRenderer.test.tsx`, 22 fixtures, build-blocking) covers markdown. Diff XSS is covered *thinly* (`SafeUnifiedDiffRenderer.test.tsx` has basic `<script>`-in-before/after) and impl-plan step XSS exists only as an in-renderer fixture (`implementationPlanArtifactViewXss`). **Missing:** dedicated diff-content + impl-plan-step-text fixtures added to the **adversarial `xss-fixtures/` set** so they're in the `≥N`-floor build-blocking sweep (AC8 explicitly says "expand story 2.27's sanitization regression block").
3. **Epic-3 developer-journey Playwright specs (AC7).** The e2e suite has J1/J2 (queue → run → spec read), keyboard-only, and mobile variants — but **NO** developer accept-implementation, reject-implementation, takeover, or recovery-retry journeys. These are net-new specs under `e2e/`, reusing the `e2e/support/mockApi.ts` fixture-route layer.
4. **Per-package / per-surface coverage-threshold extension (AC11).** Backend JaCoCo is a single **BUNDLE** rule (75% line / 55% branch, exclusion-only — NO per-package rules today). Frontend has 3 path-glob thresholds; all Epic-3 components fall under the blanket `src/features/workflows/** ≥85%`. AC11 names specific packages at 80% line / 90% sanitization. **Decide OQ-1** (add `PACKAGE`-element JaCoCo rules vs. keep BUNDLE-only) before touching `pom.xml`.
5. **Coverage-map documentation + foundation-gate/flake wording (AC1, AC6/AC10, AC12, AC9).** Mostly verify-and-document: produce a coverage map (surface → test file → ACs covered → axe present), confirm the gate already enforces the Epic-3 tiers, confirm the no-blanket-retry flake policy holds, confirm every new component test runs an axe scan.

### S2 — What ALREADY EXISTS and you must NOT rebuild (verified by reading the source 2026-06-19)

| Epic AC | Already-shipped coverage (DO NOT re-author) | Source |
|---|---|---|
| AC2 runner-adapter lifecycle + mock/real parity | `RealRunnerContractIT` (happy × 3 artifact variants × 2 kinds; crash/timeout/contract_violation; **mock-vs-real parity** `mockBehaviourMapsToRealOutcome`; secret-scan; redaction) + 5 broker-driven lifecycle ITs (`DockerRunnerLifecycle{Timeout,Heartbeat,Recovery}IT`, `DockerRunnerTakeoverCancellationIT`, `DockerRunnerDanglingContainerCleanupIT`) extending `BrokerDrivenDockerLifecycleITSupport` + `DockerRunnerAdapterContainerLifecycleIT` + `Codex/ClaudeRunnerImageConformanceIT` | `integration/runners/`, `adapters/runner/lifecycle/`, `adapters/runner/` |
| AC3 GitHub mock/real parity | `GitHubMockVsRealParityFoundationContract` (`@Tag("foundation-gate")`, MockRestServiceServer; happy reads, classified-failure writes, permission-denied, rate-limited, branch-protected, PR-not-found) — **runs every foundation-gate**, both adapters implement `RepositoryHostAdapter` | `foundation/GitHubMockVsRealParityFoundationContract.java` |
| AC4 takeover end-to-end | `DeveloperTakeoverServiceIT` (`@Tag("integration")`, Testcontainers PG): WaitingForReview → TakenOver, queued+pending runners → `cancelled_for_takeover`, PR link preserved, recovery-action attribution, post-takeover only `VIEW_ONLY`, idempotent replay, all-non-terminal-states sweep, terminal-state rejection) + `DockerRunnerTakeoverCancellationIT` (running container `docker stop`) + `DeveloperTakeoverServiceTest` (unit, log pins) | `application/recovery/DeveloperTakeoverServiceIT.java`, `adapters/runner/lifecycle/DockerRunnerTakeoverCancellationIT.java` |
| AC5 dev review accept/reject | `TechnicalApprovalServiceContractTest` (accept plan → Executing; accept prOutput → Completed + Linear sync; FR21 separation; idempotent replay/conflict) + `TechnicalApprovalServiceRejectImplementationContractTest` (reject plan/prOutput → Executing; counter increment; **escalation.required emitted once at threshold**, lines ~155-179) + the two `*Test` unit twins + `ImplementationPlanOrchestrationIT` + `PrOutputOrchestrationIT` + `WaitingForReviewTwoDispatchOrchestrationIT` (full approve-spec → plan → accept → PR → accept → Completed walk) | `application/approval/`, `application/workflow/` |
| AC6 frontend component coverage (3.26–3.31) | `ImplementationPlanArtifactRenderer.test.tsx`, `PrOutputArtifactRenderer.test.tsx`, `WorkflowDecisionBar.test.tsx`, `ImplementationReviewDecisionBarContainer.test.tsx` (LIVE MSW, all 3 actions), `FailureEventSurface.test.tsx`, `RunContextStrip.test.tsx`, `RunReviewQueueItem.test.tsx`, `PrStateBadge.test.tsx`, `takeoverConsistency.integration.test.tsx` — **every one runs `expectNoA11yViolations`** | `deliveryline-frontend/src/features/workflows/components/**` |
| AC9 axe scans | `src/test/a11y/axe.ts` `expectNoA11yViolations` (wcag2a/2aa/21a/21aa) present in all Epic-3 component tests | `src/test/a11y/axe.ts` |
| AC10 foundation-gate | `foundation-gate.needs` already includes `frontend-build-tests`, `frontend-e2e`, `backend-contract-tests` (where the non-docker Epic-3 ITs run); `FoundationGateVerificationTest` already delegates Contracts #11/#14/#15 (GitHub parity, TicketSource, RepositoryHost) | `.github/workflows/ci.yml:1050-1089`, `foundation/FoundationGateVerificationTest.java` |
| AC12 flake policy | No-blanket-retry policy comments already in `ci.yml` for `backend-contract-tests` (:430), `runner-contract-real` (:1593), `frontend-e2e` (`retries:0` + `test.fixme` quarantine, :322) | `.github/workflows/ci.yml` |

### S3 — `gh-real-tests` is a NIGHTLY, NON-PR-BLOCKING job (AC3). Do NOT add it to `foundation-gate.needs`.

Real-GitHub-API tests cost rate-limit budget and need a live token + test repo — they must be `schedule:`-driven on `main`, never on PRs. This mirrors 3.34's `runner-contract-real` posture (heavy, side-tier, NOT in `foundation-gate.needs`). The deterministic parity *contract* (`GitHubMockVsRealParityFoundationContract`, MockRestServiceServer, zero network) is what guards every PR; the nightly live job is a drift canary, not a merge gate. **The deferred-work.md GitHub-adapter entry explicitly names "story 3.35 AC3" as the owner of this nightly gating.**

### S4 — Backend coverage is BUNDLE-scoped, not per-package — AC11's per-package list is OQ-1.

`deliveryline-backend/pom.xml` JaCoCo `check` has ONE `<rule><element>BUNDLE</element>` (75% line / 55% branch) and an exclusion-only policy (only `DeliveryLineApplication.class` excluded — by design, AC5 of 2.27-era forbids blanket-excluding app code). The named Epic-3 packages (`application.runner.queue`, `application.runner.workspace`, `application.integration.ticketsource`, `application.integration.repohost`, `application.recovery`) are *already counted* in the BUNDLE — they are NOT uncovered, they just have no dedicated floor. AC11 wants per-package floors (80% line; sanitization-adjacent 90%). **OQ-1:** add `<element>PACKAGE</element>` rules for those five packages (additive, leaves BUNDLE intact) OR keep BUNDLE-only and document the named packages are covered transitively. Recommended default: **add the five PACKAGE rules** (cheap, makes the AC literally true, guards each new package against silent regression) — but confirm with Alex because the repo deliberately chose BUNDLE-only and per-package rules can be brittle on small packages.

### S5 — `DeveloperTakeoverFlowIT` (AC4): EXTEND/RENAME the existing IT, do not author a parallel duplicate.

The epic names `DeveloperTakeoverFlowIT`; the repo has `DeveloperTakeoverServiceIT` which already covers TakenOver + dual-runner cancellation + PR preservation + view_only + attribution. The ONLY gap vs. the epic's literal walk is the **pre-amble** (the existing IT seeds the run directly at `WaitingForReview`; the epic wants `spec approved → plan generated → developer takes over`). **OQ-2:** either (a) add ONE test method to the existing IT that drives the real orchestration from spec-approval through plan-generation *before* the takeover (reusing the `WaitingForReviewTwoDispatchOrchestrationIT` seam), or (b) rename `DeveloperTakeoverServiceIT` → `DeveloperTakeoverFlowIT` and fold the full walk in. Recommended: **(a)** — add the full-walk method, keep the existing focused methods, do not duplicate. Do NOT create a second IT class that re-asserts what `DeveloperTakeoverServiceIT` already asserts.

### S6 — NO new test infrastructure (AC1). Reuse every existing harness.

No new test runners, no new mocking libraries, no new a11y/coverage tools. Backend: JUnit 5 + Testcontainers + the `docker-runner-it`/`foundation-gate`/`gh-real-tests` tag taxonomy + the existing Failsafe/Surefire split. Frontend: Vitest + RTL + jest-dom + MSW (`src/test/handlers.ts` / `server.ts`) + `vitest-axe` + Playwright (`e2e/` + `e2e/support/mockApi.ts`). New fixtures join the existing fixture dirs; new e2e specs join `e2e/`; new component tests (only if a gap is found) join `src/features/workflows/components/`. If you reach for a new dependency, STOP — it's almost certainly already present.

---

## Acceptance Criteria

> Carried from epic-03 story 3.35 (lines 702-715), **reconciled** per the block above. Reconciliation notes are inline where the literal AC wording diverges from repo reality.

1. **No new infrastructure (S6).** This story extends the existing test infra (story 2.27 frontend + story 1.21 CI tiers + the backend Testcontainers/tag tiers) with new test methods/fixtures/specs/profiles — it introduces **no** new test runners and **no** new mocking libraries. A written **coverage map** (surface → test file → epic-AC covered → axe present) is produced (in this story's Dev Agent Record or a `docs/`/`deferred-work.md` note) so the audit is auditable.

2. **`RunnerAdapter` integration coverage — VERIFY + document (S2).** Confirm `RealRunnerContractIT` + the broker-driven lifecycle ITs (`integration/runners/` + `adapters/runner/lifecycle/`) cover the story-3.1 AC10 + story-3.2 AC10 lifecycle scenarios against **both** `MockRunnerAdapter` and `DockerRunnerAdapter` (Testcontainers), with mock-vs-real parity asserted (`mockBehaviourMapsToRealOutcome`, story 3.8 AC5). Close any *named* lifecycle-scenario gap found in the audit; do NOT re-author the existing happy/crash/timeout/contract_violation coverage.

3. **GitHub adapter parity — VERIFY deterministic + WIRE nightly real (S1.1, S3).** The deterministic parity contract `GitHubMockVsRealParityFoundationContract` runs the same fixture scenario sequence against both adapters every foundation-gate (VERIFY it does). **Net-new:** add a Maven `-Pgh-real-tests` profile selecting the existing `@Tag("gh-real-tests")` (`GitHubRealLiveIntegrationTest`), and a **scheduled CI job** (`schedule:` cron on `main`, NOT in PR CI, NOT in `foundation-gate.needs` — S3) that runs the real adapter against a documented test repository using `GITHUB_TOKEN` + `deliveryline.github.live.repo`. PR CI default behavior (skipped) is unchanged.

4. **Developer takeover flow end-to-end — EXTEND the existing IT (S5).** A takeover IT (`DeveloperTakeoverFlowIT` or the extended `DeveloperTakeoverServiceIT` per OQ-2) exercises the full walk: spec approved → plan generated → developer takes over → run `TakenOver` → in-flight runner cancelled → queued executions cancelled → artifacts + GitHub PR linkage preserved → post-takeover allowed actions reduced to `view_only`. Asserts FR18 + FR19 + FR33. The container-stop half (`DockerRunnerTakeoverCancellationIT`) stays as the docker-tier proof. Do NOT duplicate the already-asserted cancellation/preservation/view_only checks.

5. **Developer review (accept/reject) flow — VERIFY (S2).** Confirm the existing contract tests cover: accept plan → `Executing` (PR runner enqueued via story 3.17); accept `prOutput` → `Completed` + Linear sync; reject plan → `Executing` (re-dispatch) + counter incremented; reject `prOutput` → `Executing` (re-dispatch); escalation threshold → `escalation.required` event emitted once. **Reconciliation:** the epic AC says plan rejection lands at `Investigating` — repo reality is `Executing` (the developer plan-rejection re-entry stage; see `TechnicalApprovalServiceRejectImplementationContractTest`). The reconciliation wins. Close any *named* scenario gap; do NOT re-author the passing matrix.

6. **Frontend component coverage (3.26–3.31) — VERIFY + close gaps (S2).** Confirm Vitest + Testing Library tests cover stories 3.26 (impl-plan ARP variant), 3.27 (PR/output ARP variant + diff), 3.28 (Decision Bar `implementation_review` mode), 3.29 (takeover UI), 3.30 (recovery baseline), 3.31 (PR linkage) per each story's "component test coverage" AC. All exist today with axe scans; fill ONLY documented-state gaps found in the audit.

7. **Playwright developer-journey extension — NET-NEW specs (S1.3).** Add keyboard-only journey specs under `e2e/` for: developer **accept-implementation** (queue → run → review plan → accept → see state advance), developer **reject-implementation** (with rationale/taxonomy dialog), developer **takeover** (with confirmation dialog + post-takeover navigation), and **recovery retry**. Drive them against the `e2e/support/mockApi.ts` fixture-route layer (story-1.23 streams) — never a live backend. They join the existing chromium/firefox/webkit/msedge/mobile matrix and obey the `retries:0` + `test.fixme`-quarantine policy.

8. **Sanitization regression extension — NET-NEW fixtures (S1.2).** Add XSS fixtures for **diff content** (story 3.27) and **implementation-plan step text** (story 3.26) to the adversarial `xss-fixtures/` set so they run inside the build-blocking sweep (`SafeMarkdownRenderer.test.tsx` / `SafeUnifiedDiffRenderer.test.tsx` loop). Bump the documented fixture floor accordingly. Story 2.27's sanitization regression block is *expanded*, not duplicated.

9. **axe-core a11y scan (S2).** Every new component test (if any gap-fill component test is added) runs an `expectNoA11yViolations` scan; zero `wcag2aa` violations is the bar. (Existing Epic-3 component tests already comply — verify.)

10. **Foundation-gate relationship — VERIFY, mostly already satisfied (S2, S3).** Confirm "Epic 3 frontend test suite + Epic 3 backend integration tests green" is enforced at the gate: `frontend-build-tests` + `frontend-e2e` (Vitest + Playwright) and `backend-contract-tests` (non-docker Epic-3 ITs) are already in `foundation-gate.needs`; the heavy docker `runner-contract-real` stays a 3.34 side-tier required check (NOT in the gate), and `gh-real-tests` is nightly (NOT in the gate — S3). Do NOT add new heavy tiers to `foundation-gate.needs`. If the audit finds an Epic-3 integration test that runs in NO gate-reachable tier, wire its tier in (that's the only structural change AC10 may require).

11. **Coverage thresholds extended to Epic-3 packages (S4 — OQ-1).** Per OQ-1, extend coverage floors to cover Epic-3's new packages — `application.runner.queue` (3.17), `application.runner.workspace` (3.9), `application.integration.ticketsource` (3.32), `application.integration.repohost` (3.33), `application.recovery` (3.22) at ≥80% line; sanitization-adjacent code ≥90% — via additive JaCoCo `PACKAGE` rules (default) or a documented BUNDLE-only justification. Frontend: confirm Epic-3 components stay within the `src/features/workflows/** ≥85%` floor (raise the floor only if measured coverage comfortably exceeds it; never red the build on day one — the 2.27 floor-just-under-measured discipline).

12. **Flake metrics (S2).** Epic-3 tests are surfaced in CI artifacts/reports (Surefire/Failsafe reports, Playwright report-on-failure) the same way Epic-2's are; the no-blanket-retry policy (story 1.21 AC5 / story 3.34 AC5) holds — any narrowly-scoped retry (e.g. container cold-start / base-layer pull) is on the pull/build step ONLY, inline-documented, with a `deferred-work.md` breadcrumb. Quarantined frontend specs use `test.fixme` + justification; quarantined backend tests use `@Tag("known-failure")` + justification — never a silent retry.

---

## Tasks / Subtasks

- [x] **Task 1 — Coverage-map audit + AC1/AC2/AC5/AC6/AC9 verification (AC1, AC2, AC5, AC6, AC9, AC12; S1.5, S2)**
  - [x] Produce the coverage map: for each epic surface (runner lifecycle, mock/real parity, GitHub parity, takeover, accept/reject, 3.26–3.31 frontend) record `surface → test file(s) → epic-AC covered → axe present?`. Put it in the Dev Agent Record (or a short `docs/` note). This is the AC1 audit artifact and the source of truth for which gaps (if any) are real.
  - [x] Verify AC2 by running the runner-adapter ITs locally where Docker is available (`-Pdocker-runner-it -Dit.test=RealRunnerContractIT` etc.) OR confirm-by-reading they cover the story-3.1/3.2 AC10 scenarios + the `mockBehaviourMapsToRealOutcome` parity. Note any *named* missing lifecycle scenario; close only that.
  - [x] Verify AC5 against `TechnicalApprovalServiceContractTest` + `TechnicalApprovalServiceRejectImplementationContractTest` — confirm the accept→Executing/Completed, reject→Executing+counter, and escalation-once-at-threshold assertions exist. Honor the AC-vs-repo state reconciliation (plan rejection = `Executing`, NOT `Investigating`).
  - [x] Verify AC6/AC9: every 3.26–3.31 component test exists and runs `expectNoA11yViolations`. Fill ONLY a documented-state gap (assert roles/text/`data-*`/classes — NEVER `toMatchSnapshot`; helpers in `.ts` not `.tsx` per [[frontend-react-refresh-no-fn-exports]]; consolidate same-module mocks per [[vitest-cross-file-router-mock]]).

- [x] **Task 2 — `gh-real-tests` Maven profile + nightly CI job (AC3; S1.1, S3) — NET-NEW**
  - [x] Add a `-Pgh-real-tests` profile to `deliveryline-backend/pom.xml` (mirror the `docker-runner-it` profile shape at ~:665) that clears the default exclusion and selects `<groups>gh-real-tests</groups>` (the `@Tag("gh-real-tests")` on `GitHubRealLiveIntegrationTest`). Pass through `-Ddeliveryline.github.live-tests=true` so the `@EnabledIfSystemProperty` gate opens.
  - [x] Add a **scheduled** CI job (a new `.github/workflows/nightly-gh-real.yml` OR a `schedule:`-gated job — prefer a separate workflow so `ci.yml`'s PR tier order is untouched): `on: schedule: - cron: '<nightly>'` + `workflow_dispatch`; `ubuntu-latest`; runs `./mvnw -Pgh-real-tests -pl deliveryline-backend -am verify -Dit.test=GitHubRealLiveIntegrationTest -Ddeliveryline.github.live-tests=true -Ddeliveryline.github.live.repo=<test-repo>` with `GITHUB_TOKEN` from secrets. **Do NOT add this job to `foundation-gate.needs` and do NOT run it on `pull_request` (S3).**
  - [x] Document the test repository + token requirement (OQ-3) and that PR CI is unaffected. Upload Failsafe reports as an artifact. No blanket retry (AC12).

- [x] **Task 3 — `DeveloperTakeoverFlowIT` full-walk (AC4; S5 — OQ-2) — NET-NEW METHOD (not a new class)**
  - [x] Resolve OQ-2 first. Default: add ONE test method to `DeveloperTakeoverServiceIT` (or rename it `DeveloperTakeoverFlowIT`) driving the full walk: real `spec approved → plan generated` (reuse the `WaitingForReviewTwoDispatchOrchestrationIT` orchestration seam / `ImplementationPlanOrchestrationIT` dispatch) → takeover → assert `TakenOver` + queued+in-flight runners `cancelled_for_takeover` + artifacts + `github_pr` link preserved + `view_only` allowed actions. Assert the FR18/FR19/FR33 surface.
  - [x] Do NOT re-assert what the existing focused methods already cover (the dual-runner cancellation, idempotent replay, all-states sweep stay as-is). The container-stop docker proof stays in `DockerRunnerTakeoverCancellationIT` — leave it untouched.
  - [x] Keep the test in the gate-reachable tier (`@Tag("integration")`, NOT `docker-runner-it`) so AC10 enforces it via `backend-contract-tests` ∈ `foundation-gate.needs`.

- [x] **Task 4 — Playwright developer-journey specs (AC7; S1.3) — NET-NEW**
  - [x] Add keyboard-only journey specs under `e2e/` for: developer accept-implementation, reject-implementation (rationale/taxonomy dialog), takeover (confirmation dialog + post-takeover nav), recovery retry. Use ONLY `keyboard.press('Tab'/'Shift+Tab'/'Enter'/'Space'/'Escape')` — zero `.click()`/`.tap()` (the story-2.25 AC3 contract; test fails if an action is unreachable without a pointer).
  - [x] Drive against `e2e/support/mockApi.ts` (extend its fixture-route model for the developer-review/takeover/recovery endpoints — `allowed-actions` with `developer` role, accept/reject/takeover/retry mutations, the impl-plan + pr-output artifact reads). Reuse the story-1.23 streams; respect the 501-tripwire pattern for unmodelled endpoints. Add a mobile-viewport variant of at least the accept journey (Galaxy S23+ project).
  - [x] These run in the existing `frontend-e2e` matrix job (`needs: frontend-build-tests`) — NO new CI job. `retries:0`; any quarantine is `test.fixme` + one-line justification (AC12).

- [x] **Task 5 — Diff + impl-plan-step XSS fixtures in the build-blocking loop (AC8; S1.2) — NET-NEW**
  - [x] Add adversarial **diff-content** fixtures (story 3.27): `<script>`/`<img onerror>`/`javascript:` URLs/entity-encoded payloads embedded in diff hunks, file paths, and PR body — into the `SafeUnifiedDiffRenderer` fixture sweep so each is asserted inert + build-blocking.
  - [x] Add adversarial **implementation-plan step-text** fixtures (story 3.26): scriptable step summary/detail + `javascript:` context-ref hrefs — into the markdown/`xss-fixtures/` build-blocking loop (or a dedicated step-text loop that mirrors `SafeMarkdownRenderer.test.tsx`'s `.expected.json` contract + floor assertion).
  - [x] Bump the documented fixture-floor count and update the 2.27 sanitization-regression note so the new fixtures are inside the build-block (AC8: one failing fixture reds the build).

- [x] **Task 6 — Coverage-threshold extension (AC11; S4 — OQ-1)**
  - [x] Resolve OQ-1. Default: add additive JaCoCo `<rule><element>PACKAGE</element>` limits to `deliveryline-backend/pom.xml`'s `jacoco:check` for `org.dradgo.application.runner.queue`, `…runner.workspace`, `…integration.ticketsource`, `…integration.repohost`, `…recovery` at ≥80% line; leave the BUNDLE rule (75/55) intact. Measure actual coverage FIRST and set the floor just under measured (never red on day one — the 2.27 discipline; [[verify-ci-fixes-in-clean-env]]).
  - [x] Frontend: confirm Epic-3 components sit within `src/features/workflows/** ≥85%`. Only raise the floor if measured comfortably exceeds it. Document any change in `frontend/README.md`'s coverage section.
  - [x] Verify the JaCoCo check still passes in a clean Linux-shaped env (local ≠ CI). A too-tight per-package floor that reds the build is an AC11 failure — back it off to just-under-measured.

- [x] **Task 7 — Foundation-gate + flake verification (AC10, AC12; S2, S3)**
  - [x] Confirm `foundation-gate.needs` already enforces `frontend-build-tests` + `frontend-e2e` + `backend-contract-tests` (where the new `DeveloperTakeoverFlowIT` walk + accept/reject contract tests run). Only wire a tier in if the audit finds an Epic-3 test that runs in NO gate-reachable tier. Do NOT add `runner-contract-real` (3.34 side-tier) or `gh-real-tests` (nightly) to the gate (S3).
  - [x] Confirm the no-blanket-retry policy holds for every new test; add a `deferred-work.md` breadcrumb for any narrowly-scoped pull/build-step retry. Surface Epic-3 tests in the existing report artifacts (no new flake-report job — the repo has none; flakes surface via Surefire/Failsafe + Playwright artifacts + manual triage).

- [x] **Task 8 — Gate verification (all ACs)**
  - [x] Backend: run the affected tiers — unit (`./mvnw -pl deliveryline-backend test`), the contract/integration tier carrying the takeover + accept/reject ITs, and the JaCoCo check — via the `test`/`verify` lifecycle phases (NOT direct surefire/failsafe goals — [[maven-arglineation-goal-crash]]; use `-am` so the freshest `deliveryline-runner-contracts` jar is on the classpath — [[runner-contracts-schema-stale-in-m2]]).
  - [x] Frontend: run the FULL gate via PowerShell (RTK corrupts only Bash — [[rtk-hook-only-matches-bash]]): `tsc -b`, `eslint . --max-warnings=0`, `vitest run --coverage` (thresholds pass), `prettier --check .` (run `prettier --write` first — [[prettier-gate-cascades-ci]]), `npm run build`, and `npm run test:e2e` across chromium + one of firefox/webkit to prove the new developer journeys + keyboard-only + mobile variants pass.
  - [x] If a dep were added (it should NOT be — S6), regenerate the lockfile with a full `npm install` and verify on Linux ([[frontend-lockfile-cross-platform]]). Verify the gh-real nightly job's YAML parses (`node --check` the github-script blocks; `bash -n` the run blocks) — the live run itself is operator/CI-only (no token locally). Verify in a clean Linux-shaped env before claiming green ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]).

- [x] **Logging instrumentation** (cross-cutting standard) — **N/A for this story.** Story 3.35 is test-tooling + CI YAML + test fixtures + Maven profile only; it touches no Spring `@Service`, SPI, or persistence surface, so the SLF4J/MDC logging contract does not apply (same posture as story 2.27). The new takeover full-walk IT *asserts* existing log lines (the `DeveloperTakeoverService` log pins from story 3.22) where relevant, but adds no new production log surface. If implementation unexpectedly adds a backend production touch (it must not), apply the full logging task from the project standard.

---

## Dev Notes

### Current-state inventory (what ALREADY EXISTS — do NOT rebuild; verified by reading source 2026-06-19)

**Backend — runner-adapter ITs (AC2):**
- `deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java` — `@Tag("docker-runner-it")`+`@Tag("real-runner-contract")`+`@EnabledIfDockerAvailable`, `@ActiveProfiles({"test","linear-mock","runners.docker"})`. Happy × {spec, implementationPlan, prOutput} × {codex, claude}; crash / contract_violation / timeout; **mock-vs-real parity** (`mockBehaviourMapsToRealOutcome`, ~415-463, against `MockRunnerScenarioRegistry`); secret-non-leak; log redaction. Builds its own offline mock images in `@BeforeAll`. **Story 3.8.**
- `adapters/runner/lifecycle/BrokerDrivenDockerLifecycleITSupport.java` + 5 concrete ITs: `DockerRunnerLifecycleTimeoutIT` (past-deadline → `timed_out` + `runner.timeout` + container kill), `DockerRunnerLifecycleHeartbeatIT` (log-growth heartbeat → lease renewal), `DockerRunnerLifecycleRecoveryIT` (restart lease re-arm / exited-with-result → `completed`), `DockerRunnerTakeoverCancellationIT` (takeover → `docker stop` + `cancelled_for_takeover`), `DockerRunnerDanglingContainerCleanupIT` (rowless labeled container sweep). **Stories 3.2a / 3.22.**
- `adapters/runner/DockerRunnerAdapterContainerLifecycleIT.java` (adapter-only dispatch/poll/harvest, Alpine image) + `Codex/ClaudeRunnerImageConformanceIT` (image-shape contract). **Stories 3.1 / 3.3 / 3.4.**
- Adapters: `adapters/runner/MockRunnerAdapter.java` (`@Profile("!runners.docker")`), `adapters/runner/DockerRunnerAdapter.java` (`@Profile("runners.docker")`), `MockRunnerScenarioRegistry.java` (happy-spec/plan/pr + timeout/crash/contract-violation/non-zero-exit/late-result/duplicate-result/malformed-output).

**Backend — GitHub adapter (AC3):**
- `adapters/integration/repohost/github/GitHubMockAdapter.java` (`@Profile("github-mock")`), `GitHubRealAdapter.java` (`@Profile("github-real")`), `application/integration/repohost/RepositoryHostAdapter.java` (port, story 3.33).
- `foundation/GitHubMockVsRealParityFoundationContract.java` — `@Tag("foundation-gate")`, FoundationGate Contract #11, MockRestServiceServer-stubbed parity (happy reads, classified-failure writes, permission-denied, rate-limited, branch-protected, PR-not-found). **Runs every foundation-gate.**
- `adapters/integration/repohost/github/GitHubRealLiveIntegrationTest.java` — `@Tag("gh-real-tests")` + `@EnabledIfSystemProperty(named="deliveryline.github.live-tests", matches="true")`. **The tag exists; the `-Pgh-real-tests` Maven profile + nightly job do NOT (Task 2 net-new).**

**Backend — takeover + dev review (AC4/AC5):**
- `application/recovery/DeveloperTakeoverService.java` (story 3.22, sibling of `RecoveryService.retry`) + `DeveloperTakeoverServiceTest.java` (unit, log pins) + `DeveloperTakeoverServiceIT.java` (`@Tag("integration")`, Testcontainers PG; WaitingForReview→TakenOver, dual-runner `cancelled_for_takeover`, PR preserved, attribution, `VIEW_ONLY`, idempotent replay, all-non-terminal-states sweep, terminal-state rejection). **Gap = the full spec→plan pre-amble (Task 3).**
- `application/approval/TechnicalApprovalService.java` (`acceptImplementation`/`rejectImplementation`, `Propagation.MANDATORY`) + `TechnicalApprovalServiceAcceptImplementationTest` / `…RejectImplementationTest` (unit) + `TechnicalApprovalServiceContractTest` (accept plan→Executing; accept prOutput→Completed + Linear sync; FR21 separation; idempotency) + `TechnicalApprovalServiceRejectImplementationContractTest` (reject plan/prOutput→Executing; counter++; **escalation.required once at threshold ~155-179**; idempotency).
- `application/workflow/`: `ImplementationPlanOrchestrationIT`, `PrOutputOrchestrationIT`, `WaitingForReviewTwoDispatchOrchestrationIT` (full approve-spec → dispatch#1 plan → accept → dispatch#2 PR → accept → Completed). The runner-enqueue seam: `WorkflowOrchestrationService.dispatchImplementation(runId[, correlationId])` (~523-560) → `RunnerBroker.dispatch(EXECUTION)`.

**Frontend — Epic-3 component tests (AC6/AC9) — all under `deliveryline-frontend/src/features/workflows/components/**`, all with `expectNoA11yViolations`:**
- 3.26: `ImplementationPlanArtifactRenderer.test.tsx` (steps expand/collapse kbd, context-refs, `implementationPlanArtifactViewXss`, 3 axe variants).
- 3.27: `PrOutputArtifactRenderer.test.tsx` (branch/commit/PR refs, state badge, file-by-file diff, `prOutputArtifactViewXss`, large-diff pagination, stale-GitHub, kbd accordion, 3 axe variants) + `PrStateBadge.test.tsx` + `SafeUnifiedDiffRenderer.test.tsx`.
- 3.28: `WorkflowDecisionBar.test.tsx` + `ImplementationReviewDecisionBarContainer.test.tsx` (LIVE MSW; accept w/ versions, reject w/ taxonomy, takeover reasonText-only + PR affordance, APPROVAL_VERSION_MISMATCH, `developer`-role allowed-actions).
- 3.29: `FailureEventSurface.test.tsx` (takeover row, recovery color, escaped reason) + `RunContextStrip.test.tsx` (takeover attribution block) + `__tests__/RunReviewQueueItem.test.tsx` (recovery badge) + `__tests__/takeoverConsistency.integration.test.tsx` (cross-surface coherence).
- 3.30: `FailureEventSurface.test.tsx` (failure + recovery markers) + `WorkflowDecisionBar.test.tsx` (recovery bar) + `RunContextStrip.test.tsx` (failed-stage baseline) + `RunReviewQueueItem.test.tsx` (failed state).
- 3.31: `RunContextStrip.test.tsx` + `RunReviewQueueItem.test.tsx` PR-linkage clusters + `PrStateBadge.test.tsx`.

**Frontend — e2e + sanitization (AC7/AC8):**
- `deliveryline-frontend/playwright.config.ts` + `e2e/{critical-journey,keyboard-only,mobile-viewport}.spec.ts` + `e2e/support/mockApi.ts` (fixture-route layer, 5 story-1.23 streams, 501-tripwire). **NO developer-review/takeover/recovery journeys (Task 4 net-new).**
- `src/lib/sanitization/__tests__/xss-fixtures/` (22 fixtures) + `SafeMarkdownRenderer.test.tsx` (build-blocking floor) + `SafeUnifiedDiffRenderer.test.tsx` (thin diff XSS). **NO dedicated diff-content or impl-plan-step-text fixtures in the loop (Task 5 net-new).**
- `src/test/a11y/axe.ts` (`expectNoA11yViolations`), `src/test/handlers.ts` (shared MSW), `src/test/server.ts`.

### CI / gate facts (stories 1.21 / 1.23 / 2.27 / 3.34)

- **Tier order** (`.github/workflows/ci.yml`): `format-static-checks → runner-contract-fixtures → frontend-build-tests → backend-unit-tests → backend-contract-tests → runner-image-compat → jar-packaging → {bundled-jar-smoke[main], export-redaction-verify}`; `frontend-e2e` (`needs: frontend-build-tests`, matrix chromium/firefox/webkit/msedge/mobile-galaxy-s23, `retries:0`); the 3.34 side-tier `detect-changes → runner-image-build → {runner-image-self-test, runner-contract-real}` (path-triggered) + `runner-contract-real-gate`.
- **`foundation-gate`** (`if: always() && !cancelled()`, `.github/workflows/ci.yml:1041-1089`): `needs:` includes `frontend-build-tests`, `frontend-e2e`, `backend-contract-tests`, etc.; the "Assert all required tiers succeeded" step converts any non-`success` need into an explicit fail. **AC10's Epic-3 frontend + non-docker-backend coverage is therefore ALREADY enforced** (S2).
- **`FoundationGateVerificationTest`** delegates 15 contracts (incl. #11 GitHub parity, #14 TicketSource, #15 RepositoryHost) to source-of-truth tests; it does NOT re-author assertions. Do NOT add the heavy docker/gh-real ITs here (they're not foundation-gate-tagged).
- **Backend JaCoCo** (`deliveryline-backend/pom.xml`): merges surefire+failsafe exec → `jacoco:check` BUNDLE rule (75% line / 55% branch), exclusion-only (`DeliveryLineApplication.class`). NO per-package rules today (Task 6 / OQ-1).
- **Frontend coverage** (`deliveryline-frontend/vitest.config.ts`): `provider:v8`, thresholds `src/lib/sanitization/** ≥86`, `src/lib/queryKeys/** ≥90`, `src/features/workflows/** ≥85` (floors just-under-measured). `deferred-work.md` notes "no global coverage floor" as a future hardening item.
- **Tag taxonomy** (`pom.xml`): Surefire (unit) excludes `architecture,integration,contract,known-failure,foundation-gate`; Failsafe (default) excludes `known-failure,docker-runner-it`; `-Pdocker-runner-it` clears the docker exclusion; `-Pfoundation-gate` runs only `foundation-gate`-tagged. **New `-Pgh-real-tests` (Task 2) mirrors the `docker-runner-it` profile shape.**
- **Flake policy** — no-blanket-retry comments at `:31`, `:430` (backend-contract-tests), `:1593` (runner-contract-real); `frontend-e2e` `retries:0` + `test.fixme` quarantine (`:322`). No dedicated flake-report job; flakes surface via report artifacts + manual triage.

### Architecture compliance (hard invariants relevant here)

- **No new test infra (AC1/S6).** Backend: JUnit 5 + Testcontainers + the existing tag tiers. Frontend: Vitest + RTL + jest-dom + MSW + vitest-axe + Playwright. No Jest, no Cypress, no second mock/a11y/coverage lib.
- **No production code change.** This story is tests + fixtures + a Maven profile + CI YAML. If a backend `@Service`/SPI/Flyway/OpenAPI or `schema.d.ts` would change, STOP — that's out of scope (the surfaces are all DONE).
- **gh-real is nightly + non-blocking (S3).** Never on `pull_request`, never in `foundation-gate.needs`. The deterministic `GitHubMockVsRealParityFoundationContract` is the PR guard.
- **Tests assert structure, never pixels/snapshots in jsdom** (roles/text/`data-*`/classes); computed-pixel + cross-browser belong to Playwright. Helpers in `.ts` not `.tsx` ([[frontend-react-refresh-no-fn-exports]]); consolidate same-module `vi.mock`s ([[vitest-cross-file-router-mock]]).
- **Backend integration ITs that must run in the gate** use `@Tag("integration")` (runs in `backend-contract-tests`), NOT `@Tag("docker-runner-it")` (excluded by default; only the 3.34 side-tier runs it). The takeover full-walk (Task 3) stays `integration`-tagged so AC10 enforces it.

### Project Structure Notes

- **New files (backend):** `deliveryline-backend/pom.xml` profile `gh-real-tests` (modify); possibly rename `application/recovery/DeveloperTakeoverServiceIT.java` → `DeveloperTakeoverFlowIT.java` (OQ-2) or add a method.
- **New files (CI):** `.github/workflows/nightly-gh-real.yml` (preferred) OR a `schedule:`-gated job in `ci.yml`.
- **New files (frontend):** `e2e/developer-review.spec.ts` / `e2e/takeover.spec.ts` / `e2e/recovery.spec.ts` (or one `e2e/developer-journeys.spec.ts`); diff + impl-plan-step XSS fixtures under `src/lib/sanitization/__tests__/xss-fixtures/` (+ `.expected.json`); extensions to `e2e/support/mockApi.ts`.
- **Modified (frontend):** `vitest.config.ts` (only if a floor is raised — measure first); `frontend/README.md` (coverage/flake note if changed); the diff/plan sanitization test loop files.
- **Modified (CI):** `deliveryline-backend/pom.xml` JaCoCo per-package rules (OQ-1).
- **Tracking:** `_bmad-output/implementation-artifacts/sprint-status.yaml` — `3-35` `ready-for-dev → in-progress → review`; `deferred-work.md` — close the gh-real-nightly + coverage-floor breadcrumbs.

### Testing standards

- This story's "tests" ARE the deliverable. Validate the audit by RUNNING the existing tiers (don't just read): the frontend gate via PowerShell, the affected backend tiers via the `verify`/`test` lifecycle phases ([[maven-arglineation-goal-crash]], `-am` for the fresh runner-contracts jar — [[runner-contracts-schema-stale-in-m2]]), the new Playwright journeys across ≥2 engines.
- **Verify in a clean Linux-shaped env, not just locally** — local green ≠ CI green ([[verify-ci-fixes-in-clean-env]], [[wsl-linux-ci-reproduction]]). The gh-real nightly live run is operator/CI-only (no token locally) — validate its YAML statically (`node --check`, `bash -n`) + confirm the profile selects the tag.
- **Coverage floors must not red on day one** — measure actual coverage, set the floor just under measured (the 2.27 discipline). A too-tight per-package JaCoCo rule that reds an existing-green build is a bug, not the intended AC11 outcome.
- Memory tripwires for this slice: [[micrometer-gauge-weak-ref-nan-flake]] / [[prometheus-actuator-disabled-in-springboottest]] (if the audit touches the runner-queue observability tests), [[livesnnouncement-defers-one-commit-test-flake]] (the queue-announcer text lags one render — assert via `waitFor`), [[workflowdetail-wire-sends-null-not-undefined]] (guard `!= null` in any new frontend fixture mapping), [[new-workfloweventtype-fixture-sites]] (if a takeover/recovery event type needs a fixture, mirror it into both fixture sites), [[playwright-e2e-harness-wiring]] (JSON imports need `with { type: 'json' }`; e2e eslint node+browser globals), [[runner-tool-self-test-needs-offline-mock]] (don't trip the conformance ITs).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.35] — the 12 ACs (lines 696-715).
- [Source: _bmad-output/implementation-artifacts/2-27-frontend-test-suite-...md] — the Epic-2 capstone twin; the "audit + consolidate + close-gaps, don't rebuild" house pattern + the Playwright/coverage/MSW conventions this story extends.
- [Source: deliveryline-backend/src/test/java/org/dradgo/integration/runners/RealRunnerContractIT.java] — runner-adapter lifecycle + mock/real parity (AC2).
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/runner/lifecycle/] — the 5 broker-driven lifecycle ITs (AC2/AC4 container half).
- [Source: deliveryline-backend/src/test/java/org/dradgo/foundation/GitHubMockVsRealParityFoundationContract.java] — deterministic GitHub parity (AC3 PR guard).
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/integration/repohost/github/GitHubRealLiveIntegrationTest.java] — the `gh-real-tests`-tagged live test (AC3 nightly target).
- [Source: deliveryline-backend/src/test/java/org/dradgo/application/recovery/DeveloperTakeoverServiceIT.java] — the takeover IT to extend (AC4 / OQ-2).
- [Source: deliveryline-backend/src/test/java/org/dradgo/application/approval/TechnicalApprovalServiceContractTest.java + …RejectImplementationContractTest.java] — accept/reject + escalation coverage (AC5).
- [Source: deliveryline-frontend/src/features/workflows/components/**/*.test.tsx] — the 3.26–3.31 component tests (AC6/AC9).
- [Source: deliveryline-frontend/e2e/ + e2e/support/mockApi.ts + playwright.config.ts] — the e2e harness to extend (AC7).
- [Source: deliveryline-frontend/src/lib/sanitization/__tests__/] — the XSS fixture loop to expand (AC8).
- [Source: .github/workflows/ci.yml:1041-1089] — foundation-gate needs + skip→fail aggregator (AC10).
- [Source: deliveryline-backend/pom.xml] — JaCoCo BUNDLE rule + tag tiers + profile shapes (AC11, Task 2).
- [Source: deliveryline-frontend/vitest.config.ts] — frontend coverage thresholds (AC11).
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] — the GitHub-adapter entry naming "story 3.35 AC3" for gh-real nightly + the "no global coverage floor" hardening note.

## Open Questions (raise with Alex before finalizing)

- **OQ-1 (AC11 — blocking the coverage-threshold change):** Add per-package JaCoCo `PACKAGE`-element rules for the five named Epic-3 packages at ≥80% line (additive, BUNDLE rule untouched) — OR keep the repo's deliberate BUNDLE-only (75/55) policy and document that the named packages are covered transitively? The repo chose BUNDLE-only on purpose; per-package floors can be brittle on small packages. **Default this story ships:** add the five PACKAGE rules with floors measured-just-under-actual.
- **OQ-2 (AC4 — the takeover IT shape):** Extend `DeveloperTakeoverServiceIT` with one full-walk method (spec→plan→takeover), OR rename it `DeveloperTakeoverFlowIT` and fold the walk in, OR author a new `DeveloperTakeoverFlowIT` class? **Default:** extend the existing IT (no duplicate class; the focused methods stay). Confirm the epic's literal `DeveloperTakeoverFlowIT` name isn't a hard requirement.
- **OQ-3 (AC3 — gh-real nightly infra):** What is the documented test repository + token source for the nightly real-GitHub job? (`deliveryline.github.live.repo=owner/repo` + a CI `GITHUB_TOKEN`/PAT secret with write scope to a throwaway repo.) Confirm CI may reach the GitHub API on the nightly schedule and that the repo + token exist (or must be created) before wiring the job. Separate `nightly-gh-real.yml` workflow vs. a `schedule:`-gated job in `ci.yml`? **Default:** separate workflow (keeps `ci.yml` PR tier order untouched).

## Dev Agent Record

### Agent Model Used

Claude Opus 4.8 (claude-opus-4-8[1m]) — bmad-dev-story, 2026-06-19.

### Debug Log References

- Backend full `verify` (Docker on, frontend skipped): 664 tests, only `BundledJarPackagingContractTest` failed — self-inflicted (SPA not built under `-Dfrontend-maven-plugin.skip=true`); re-run with the SPA present passes. New `DeveloperTakeoverServiceIT` ran 14/14 green.
- `jacoco-check@jacoco-check` against the merged exec: PASS at 0.80; probe at 0.999 FAILED for `recovery`/`runner.queue`/`runner.workspace` (proving the PACKAGE rule matches — slash-form includes were a silent no-op, dotted form is correct).
- Frontend gate (PowerShell): `tsc -b` 0, `eslint . --max-warnings=0` 0, `vitest run --coverage` 1058/1058 (thresholds met), `prettier --check .` clean. Playwright developer-journeys 4/4 on chromium + firefox + mobile-galaxy-s23.

### Completion Notes List

**OQ resolutions (Alex):** OQ-1 = add the five PACKAGE JaCoCo floors. OQ-2 = extend `DeveloperTakeoverServiceIT` (no duplicate class). OQ-3 = ship the `-Pgh-real-tests` profile + a `workflow_dispatch`-ONLY workflow (no nightly cron; default repo `octocat/Hello-World`).

**Net-new deliverables:**
- **Task 2 (AC3):** `-Pgh-real-tests` profile (selects `@Tag("gh-real-tests")`, opens the `@EnabledIfSystemProperty` gate via Failsafe `systemPropertyVariables`, widens includes to `*IntegrationTest`, skips the jacoco check) + `.github/workflows/nightly-gh-real.yml` (manual trigger only; NOT in `foundation-gate.needs`, never on `pull_request`). YAML parses; the bash run block passes `bash -n`.
- **Task 3 (AC4):** `DeveloperTakeoverServiceIT.fullWalkSpecApprovedToPlanGeneratedThenTakeoverCancelsInFlightAndPreservesPr()` — real `approve spec → dispatch #1 plan → accept plan → dispatch #2 PR queued → takeover`. Asserts `TakenOver`, the genuinely-orchestrated **queued** PR execution flips to `cancelled_for_takeover` while the **completed** plan execution is untouched, plan artifact + `github_pr` link preserved, developer-attributed recovery row `succeeded`, post-takeover `VIEW_ONLY`. `@Tag("integration")` → enforced via `backend-contract-tests` ∈ `foundation-gate.needs`. The focused methods + the docker-tier `DockerRunnerTakeoverCancellationIT` are untouched.
- **Task 4 (AC7):** `e2e/developer-journeys.spec.ts` — keyboard-only accept / reject (rationale + taxonomy dialog) / takeover (confirmation dialog + post-takeover PR affordance) / recovery-retry journeys, driven against two new synthetic execution-stage runs in `e2e/support/mockApi.ts` (a `WaitingForReview` impl-review run + a `Failed` recovery run) + the accept/reject/takeover/retry mutations. Zero `.click()`/`.tap()`. Green on chromium + firefox + the Galaxy-S23 mobile project (the mobile accept-variant per AC7).
- **Task 5 (AC8):** diff-content XSS sweep — `diff-xss-fixtures/` (5 fixtures: script-in-line, img-onerror, javascript: URL, entity-encoded, polyglot header+line) + a build-blocking loop in `SafeUnifiedDiffRenderer.test.tsx` (floor ≥5). Plan-step-detail XSS — 3 `plan-step-detail-*` fixtures added to the markdown `xss-fixtures/` loop (step.detail renders through `SafeMarkdownRenderer`); the documented floor bumped 11 → 14.
- **Task 6 (AC11):** five additive PACKAGE line floors at 0.80 (measured: queue 0.90, workspace 0.84, ticketsource 1.0, repohost 1.0, recovery 0.98); BUNDLE rule (75/55) intact. **DOTTED** include form (slash form silently matched nothing — verified via the 0.999 probe). Frontend `src/features/workflows/** ≥85` floor unchanged (Epic-3 components comfortably within it).

**Verify-only (Task 1/5/6/7):**
- **AC1 coverage map** (surface → test file(s) → AC → axe present?):

  | Surface | Test file(s) | AC | axe |
  |---|---|---|---|
  | Runner lifecycle + mock/real parity | `integration/runners/RealRunnerContractIT`, `adapters/runner/lifecycle/*IT`, `adapters/runner/DockerRunnerAdapterContainerLifecycleIT`, `Codex/ClaudeRunnerImageConformanceIT` | AC2 | n/a (backend) |
  | GitHub mock/real parity (PR guard) | `foundation/GitHubMockVsRealParityFoundationContract` | AC3 | n/a |
  | GitHub real live (nightly canary) | `adapters/integration/repohost/github/GitHubRealLiveIntegrationTest` | AC3 | n/a |
  | Developer takeover end-to-end | `application/recovery/DeveloperTakeoverServiceIT` (+ full-walk method), `adapters/runner/lifecycle/DockerRunnerTakeoverCancellationIT` | AC4 | n/a |
  | Dev review accept/reject + escalation | `application/approval/TechnicalApprovalServiceContractTest`, `…RejectImplementationContractTest`, `application/workflow/{ImplementationPlan,PrOutput,WaitingForReviewTwoDispatch}OrchestrationIT` | AC5 | n/a |
  | 3.26 impl-plan renderer | `ImplementationPlanArtifactRenderer.test.tsx` | AC6 | ✅ |
  | 3.27 PR/output renderer + diff | `PrOutputArtifactRenderer.test.tsx`, `PrStateBadge.test.tsx`, `SafeUnifiedDiffRenderer.test.tsx` | AC6 | ✅ (renderer) |
  | 3.28 decision bar (impl_review) | `WorkflowDecisionBar.test.tsx`, `ImplementationReviewDecisionBarContainer.test.tsx` | AC6 | ✅ |
  | 3.29 takeover UI | `FailureEventSurface.test.tsx`, `RunContextStrip.test.tsx`, `RunReviewQueueItem.test.tsx`, `takeoverConsistency.integration.test.tsx` | AC6 | ✅ |
  | 3.30 recovery / 3.31 PR linkage | `FailureEventSurface`, `WorkflowDecisionBar`, `RunContextStrip`, `RunReviewQueueItem`, `PrStateBadge` | AC6 | ✅ |
  | Sanitization (markdown + diff + plan-step) | `SafeMarkdownRenderer.test.tsx` (14-floor), `SafeUnifiedDiffRenderer.test.tsx` (5-floor) | AC8/AC9 | n/a |
  | Developer journeys (e2e) | `e2e/developer-journeys.spec.ts` | AC7 | (Playwright) |

  Audit found **no missing named scenario** — every Epic-3 surface was already covered (the genuinely net-new work was the four items above). No gap-fill component test was needed (AC6/AC9 already comply).
- **AC10/AC12 (Task 7):** `foundation-gate.needs` already enforces `frontend-build-tests` + `frontend-e2e` + `backend-contract-tests`; the new IT (`@Tag("integration")`) and accept/reject contract tests run there, the new e2e spec joins `frontend-e2e`, the new component fixtures join `frontend-build-tests` — no new tier wired, no tier added to the gate. No blanket retry introduced (the gh-real job is a manual operator canary). `runner-contract-real` (3.34 side-tier) and `gh-real` (manual) deliberately NOT in the gate (S3).

**No production code touched** — tests + fixtures + a Maven profile + CI YAML only (AC1/S6).

### File List

- `deliveryline-backend/pom.xml` — `gh-real-tests` profile + five additive PACKAGE JaCoCo line floors (modified)
- `.github/workflows/nightly-gh-real.yml` — workflow_dispatch-only live-GitHub drift canary (new)
- `deliveryline-backend/src/test/java/org/dradgo/application/recovery/DeveloperTakeoverServiceIT.java` — full-walk takeover method + orchestration helpers (modified)
- `deliveryline-frontend/e2e/developer-journeys.spec.ts` — keyboard-only developer journeys (new)
- `deliveryline-frontend/e2e/support/mockApi.ts` — synthetic execution-stage runs + developer mutations (modified)
- `deliveryline-frontend/src/lib/sanitization/__tests__/SafeUnifiedDiffRenderer.test.tsx` — diff-content XSS fixture loop (modified)
- `deliveryline-frontend/src/lib/sanitization/__tests__/diff-xss-fixtures/` — 5 `.diff` + 5 `.expected.json` fixtures (new)
- `deliveryline-frontend/src/lib/sanitization/__tests__/SafeMarkdownRenderer.test.tsx` — fixture floor 11 → 14 (modified)
- `deliveryline-frontend/src/lib/sanitization/__tests__/xss-fixtures/plan-step-detail-{script,img-onerror,context-ref-js-link}.{md,expected.json}` — 3 plan-step-detail XSS fixtures (new)
- `_bmad-output/implementation-artifacts/deferred-work.md` — gh-real-nightly-cron + global-coverage-floor breadcrumbs (modified)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — 3-35 status (modified)

### Review Findings

_bmad-code-review 2026-06-19 — 3 adversarial layers (Blind Hunter + Edge Case Hunter + Acceptance Auditor) over the uncommitted diff scoped to the File List (11 files: pom.xml profile + 5 PACKAGE floors, nightly-gh-real.yml, DeveloperTakeoverServiceIT full-walk method, developer-journeys.spec.ts + mockApi.ts, 2 sanitization test files + 5 diff + 3 plan-step XSS fixtures, 2 tracking docs). Acceptance Auditor verdict: **ACCEPT** — all 12 ACs MET or faithfully verified; the only AC-text deviations (AC3 manual-trigger vs nightly cron; AC7 mobile-via-matrix) are explicitly sanctioned by the OQ resolutions. No production code, no scope creep, Dev Agent Record claims match the diff + on-disk fixtures. Triage: 0 decision-needed / 0 patch / 2 defer / 8 dismissed._

- [x] [Review][Defer] XSS fixture `expected.json` keys over-claim renderer-structural protections (no security gap) [`deliveryline-frontend/src/lib/sanitization/__tests__/diff-xss-fixtures/*.expected.json`, `xss-fixtures/plan-step-detail-img-onerror.expected.json`] — deferred, test-quality nit. The diff-renderer fixtures assert `noScriptElements`/`noIframeElements`/`noStyleElements`, which are structurally-always-true for `SafeUnifiedDiffRenderer` (it routes text through `renderTextWithRedactions` → text nodes, never parses HTML), so those keys can never fail; likewise `plan-step-detail-img-onerror`'s `noDataUriImages` is a no-op (the raw-HTML `<img>` is dropped by `skipHtml`, so `querySelectorAll('img')` is always empty). The **load-bearing** assertions (`renderedTextContains` literal-preservation, `noAnchorElements`, `noActiveElements`, `renderedTextDoesNotContain`) DO carry weight, so the inert-payload protection is genuinely proven — the extra keys merely overstate coverage. Defense-in-depth; tidy when hardening sanitization tests.
- [x] [Review][Defer] `tabUntilFocused` off-by-one / no focus-loop-wrap detection reused in the new keyboard journeys [`deliveryline-frontend/e2e/developer-journeys.spec.ts`] — deferred, pre-existing pattern. The new spec reuses the same Tab-budget helper already flagged for `keyboard-only.spec.ts` in deferred-work.md (a control reachable exactly at the budget edge, or a focus-loop wrap, can false-fail). Inherited, not a new defect; fix both helpers together.

**Dismissed (8, verified false positives / non-defects):** (1) Blind "`-Pgh-real-tests` runs all `*IntegrationTest`, `-Dit.test` inert" — only `GitHubRealLiveIntegrationTest` carries `@Tag("gh-real-tests")`, the profile `<groups>` intersects to exactly it (verified); (2) entity-encoded diff fixture "vacuous" — its load-bearing `renderedTextContains` literal-entity assertion is the point, suite green; (3) mock `cancelledInFlightCount:1` vs IT asserts `0` — independent fixtures, E2E asserts no counts, mock is a generic takeover shape; (4) `currentVersions` `coalesce(...,1)` "hard-coding" — defensive fallback, the real orchestrated run carries `runnerExecutionId` so the join resolves; (5) `drainQueue` fixed worker id — JUnit runs methods serially, no parallel config; (6) `mvnw` exec bit — committed `+x`, repo convention; (7) `LIVE_REPO` env "never consumed" — false, it is interpolated into `-Ddeliveryline.github.live.repo="${LIVE_REPO}"`; (8) `developerMutation` `?? DEV_REVIEW_RUN_ID` "dead code" — harmless unreachable fallback. Edge Case Hunter also verified (with project access) that every Playwright testid/role/button-name exists in real source, the auto-dispatch property names are real (`application.yml:236-247`), `pinScenarioForWorkflowRun`/`executeQueuedDispatch`/`happy-pr-output` resolve, and the IT teardown order is FK-safe.

### Change Log

- 2026-06-19 — bmad-code-review `review → done`. 3 adversarial layers over the File-List diff; Acceptance Auditor ACCEPT (12/12 ACs). Triage 0 decision / 0 patch / 2 defer / 8 dismissed; both adversarial High/Medium leads disproven against real source (gh-real `<groups>` pins the single tagged test; markdown loop genuinely reads the `noJavascriptHrefAnchors`/`noDataUriImages` keys). 2 defers → deferred-work.md (XSS fixture over-claim keys; reused `tabUntilFocused` budget pattern), both Low/test-quality. No production code touched. Status review → done.
- 2026-06-19 — Story 3.35 implemented (bmad-dev-story). Test-capstone audit + 4 net-new deliverables (gh-real profile + manual workflow, takeover full-walk IT method, Playwright developer journeys, diff + plan-step XSS fixtures) + 5 per-package JaCoCo floors. No production code changed. Status ready-for-dev → in-progress → review.
