# Story 3c.11: Foundation-Gate Widening + Test Suite Extension

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend + frontend developer,
I want the foundation gate and test suites extended to cover Epic 3c,
so that multi-project regressions are caught at the same CI gates as the rest of the system.

## ⚠️ READ THIS FIRST — This is a CONSOLIDATION + AGGREGATION story, not a build-from-scratch story

**The single biggest mistake you can make on this story is re-implementing tests that already exist.** Stories 3c-1 through 3c-10 each paid down their own slice of Epic 3c's cross-cutting test debt *as they shipped*. The drift tests, cipher tests, config-inversion ITs, per-project dispatch ITs, redaction fixtures, JaCoCo coverage floors, frontend Vitest+axe suites, the Playwright spec, and the security-review sign-off **already exist and are green**. The `deliveryline-backend/pom.xml` JaCoCo block literally contains comments citing "3c-11 AC5".

Your job is to:
1. **Widen the foundation-gate aggregator** (`FoundationGateVerificationTest`) with new `@Nested` contracts that *delegate-run* the existing Epic 3c source-of-truth tests — so Epic 3c becomes a **named, enforced** set of contracts in the gate, exactly like Epic 1 (Contracts #1–10) and Epic 3 (Contracts #11–15). **This is the primary net-new deliverable.**
2. **Fill the few genuine gaps** identified below (per-project connector resolution + config-inversion parity are NOT yet reached *through the gate aggregator*; AC5 coverage for project *adapter* packages needs a decision).
3. **Verify + record** that the full Epic 3c suite is green under `-Pfoundation-gate verify` and the CI gate already requires it, and cite the existing security sign-off.

When an AC says "a test asserts X", **first go find the existing test** (the Dev Notes give you exact paths + line numbers). If it exists and is green, your work for that AC is to *route it through the aggregator and verify*, not to author a duplicate.

## Acceptance Criteria

> Each AC below is annotated **[EXISTS]** (already shipped by 3c-1..3c-10 — verify + wire into the gate), **[GAP]** (genuine net-new work), or **[DECISION]** (a reconciliation you must resolve and document). The wording mirrors `epic-03c-multi-project-configuration.md` §Story 3c-11.

1. **[EXISTS — wire into aggregator]** The foundation gate (story 1.23 / `FoundationGateVerificationTest`) asserts, **as named contracts reached through the aggregator**: the `project_status` / `connector_kind` registries are authoritative + drift-tested, `prj_`/`cred_` prefixes are registered, credential redaction holds, the project entity drift test passes, and per-project connector resolution works. The underlying assertions already live in `RegistryContractTest`, `FlywaySchemaContractTest`, `RedactionAdversarialFoundationContract`, and `ProjectConnectorResolverTest`; this AC adds the `@Nested` aggregator contracts that delegate-run them so a regression in any of them fails `-Pfoundation-gate`.

2. **[EXISTS — wire into aggregator]** The config-inversion seam (story 3c-6) has an integration test asserting single-project behavioral parity (default-project run byte-identical to pre-3c) **and** a per-project dispatch test asserting a non-default project uses its own repo/connector/OpenSpec settings. Both already exist in `RunProjectAssociationIT` (+ `DefaultProjectSeederIT`); this AC routes them through the aggregator as named contracts.

3. **[EXISTS — verify]** A test asserts credentials never appear in logs, events, artifacts, or exports (extends the AR10 redaction regression). The project-credential fixtures (`project-credential-linear-token.json`, `project-credential-github-token.json`, `project-credential-opaque-under-key.json`) are already in the redaction manifest and swept by `RedactionAdversarialFoundationContract` (gate Contract #9), `RedactionPolicyServiceContractTest`, and `LoggingRedactionContractTest`. This AC verifies they remain present + green and (per AC1) are explicitly named in the Epic 3c gate grouping.

4. **[EXISTS — verify; one optional GAP]** The frontend (story 3c-9) Vitest + Playwright + axe coverage is extended for the Projects area (list, form, credential write-only, connection test, selector) under the existing CI tiers. The Vitest component/hook suite (8 test files, axe-scanned per state) and the `e2e/projects-management.spec.ts` Playwright journey already exist and run in `frontend-build-tests` / `frontend-e2e` (both already `needs:` of `foundation-gate`). Verify coverage holds; see **[DECISION D2]** on whether to add a focused Projects edge-case e2e.

5. **[EXISTS for app/crypto; DECISION for adapters]** Coverage thresholds (story 2.27 AC10 pattern) are extended to the new packages — `application.project` (≥80% line, already in pom), `infrastructure.crypto` (≥90% line, credential/sanitization code, already in pom). For **project adapter packages** the floor must be resolved per **[DECISION D1]** (project adapters share `org.dradgo.adapters.persistence` / `org.dradgo.adapters.rest` with the rest of the surface, so an isolated per-package floor is not directly expressible). Record the measured coverage and the rationale.

6. **[EXISTS — cite]** The test suite includes the cipher tamper/round-trip/fail-fast assertions (`EnvelopeCredentialCipherTest`, `CredentialMasterKeyGuardTest`) **and** the security-review sign-off is recorded. Both exist: the cipher tests are green, and the sign-off is recorded in `docs/adr/0013-credential-encryption.md` (Status line: "security review signed off (AC6: APPROVED, no HIGH/MEDIUM findings)"). This AC routes the cipher tests through the aggregator and cites the recorded sign-off in Completion Notes.

7. **[EXISTS — verify]** "Epic 3c backend + frontend suites green" is required for foundation-gate PRs. The `foundation-gate` CI job already `needs:` `backend-contract-tests` (runs the full `mvn verify` incl. all Epic 3c ITs/contracts), `frontend-build-tests` (Vitest), and `frontend-e2e` (Playwright). Adding the Epic 3c `@Nested` contracts (AC1/AC2/AC6) makes Epic 3c a *named* part of the `FoundationGateVerificationTest` run that executes inside the gate job. Verify the gate is red if any Epic 3c contract is broken (regression proof, per the 1.23 pattern).

## Tasks / Subtasks

- [x] **Task 1 — Confirm the prerequisite is merged (AC1–AC7)** (blocker check)
  - [x] Verified story **3c-10 is `done`** in sprint-status.yaml (the prereq held — the 3c-10 Doctor/DomainErrorCode/ProblemDetailsCatalog work is merged). No STOP needed.
  - [x] Ran `./mvnw -pl deliveryline-backend -am -Pfoundation-gate verify -Dfrontend-maven-plugin.skip=true` via the lifecycle `verify` phase (not a `failsafe:` goal — see [[maven-arglineation-goal-crash]]). NOTE: the "15-contract before baseline" run was contaminated by the concurrent aggregator edit (a Spotless format violation in the in-flight file). This is moot: the **widened 20-contract** gate then ran GREEN (45 tests, 0 failures, 1 deliberately-skipped self-test), which subsumes the 15 prior contracts (all #1–#15 pass inside it).

- [x] **Task 2 — Widen `FoundationGateVerificationTest` with Epic 3c contracts (AC1, AC2, AC3, AC6, AC7)** ← PRIMARY DELIVERABLE
  - [x] Added `@Nested` `@Tag("foundation-gate")` contract classes #16–#20 following the exact existing pattern (`FoundationGateAssertions.delegateRunAssertGreen("3c.N", "<FQN>")`, `[story 3c.N]` prefix), appended after Contract #15:
    - **Contract #16 — Epic 3c registries + prefixes + V17 schema drift (3c.2)**: delegate-runs `RegistryContractTest` (re-run for legibility, mirroring Contract #11) + `FlywaySchemaContractTest`.
    - **Contract #17 — Per-project connector resolution (3c.3)**: delegate-runs `ProjectConnectorResolverTest` (Surefire unit test discovered by FQN, no retag).
    - **Contract #18 — Credential cipher tamper/round-trip/fail-fast (3c.4, AC6)**: delegate-runs `EnvelopeCredentialCipherTest` + `CredentialMasterKeyGuardTest`.
    - **Contract #19 — Credential redaction across egress (3c.5, AC3)**: delegate-runs `RedactionAdversarialFoundationContract` (re-run as a named Epic 3c contract) + a thin direct guard asserting the three `project-credential-*.json` fixtures are present in `fixtures-manifest.json` (mirrors the Contract #10 direct short-circuit pattern).
    - **Contract #20 — Config-inversion parity + per-project dispatch (3c.6/3c.7, AC2)**: delegate-runs `RunProjectAssociationIT` (both byte-parity + non-default-dispatch tests) + `DefaultProjectSeederIT` (Testcontainers ITs; gate tier has Docker, as Contract #13 already proves).
  - [x] Each nested `@DisplayName` follows the house style "Contract #N — <subject> (story 3c.N)". No assertion re-authored beyond the cheap Contract #19 manifest drift guard.
  - [x] Updated the class-level Javadoc header to note Epic 3 (#11–#15) and Epic 3c (#16–#20) were added and that the Launcher discovers unit `*Test`s and `*IT`s by FQN regardless of tag.

- [x] **Task 3 — Resolve + apply the AC5 coverage decision for project adapters (AC5)** (see **[DECISION D1]**)
  - [x] Confirmed the JaCoCo per-package floors are present + unchanged: `org.dradgo.application.project` @ 0.80 LINE and `org.dradgo.infrastructure.crypto` @ 0.90 LINE in `deliveryline-backend/pom.xml`. Did NOT lower them. Full `verify` reported "All coverage checks have been met."
  - [x] Measured project **adapter** line coverage from the fresh merged unit+IT JaCoCo report (2026-06-21): `ProjectController` 86.9% (133/153), `ProjectPersistenceAdapter` 80.0% (40/50), `ProjectCredentialPersistenceAdapter` 85.1% (57/67), `ProjectEntityMapper` 100% (33/33). Package rollups: `application.project` 94.2%, `infrastructure.crypto` 94.5%, shared `adapters.rest` 87.2%, shared `adapters.persistence` **74.7%**.
  - [x] Applied D1 (no isolated adapter floor): the shared `adapters.persistence` package measures 74.7% LINE overall (**below 0.80**), so an isolated 0.80 PACKAGE floor for the project adapters would red the whole persistence surface — proving a project-only floor is not expressible without a package split (out of scope). Recorded the rationale + measured numbers in a `deliveryline-backend/pom.xml` `<rules>` comment; project adapters stay guarded by the BUNDLE floor (0.75 LINE / 0.55 BRANCH) + their dedicated `ProjectControllerContractTest` / `ProjectPersistenceAdapterIT` / `ProjectCredentialPersistenceAdapterIT`. No silent cap.

- [x] **Task 4 — Verify the frontend Projects suite + resolve the e2e decision (AC4)** (see **[DECISION D2]**)
  - [x] Ran the Vitest Projects suite (8 files under `src/features/projects/__tests__/`): **8 files / 51 tests passed**, axe-scanned per state (`expectNoA11yViolations`). The `src/features/projects/** ≥85%` line threshold in `vitest.config.ts` is present and unchanged (suite measures above it — verified green in 3c-9).
  - [x] Confirmed `e2e/projects-management.spec.ts` is present and runs in `frontend-e2e`: it covers the AC10 create→credential→test-connection journey + the AC8 keyboard-reachability pass (nav link + "New project").
  - [x] Applied D2 option (a): existing Vitest-axe + the e2e journey are sufficient for AC4. Confirmed (via grep) **no Playwright spec uses axe** — the a11y gate is Vitest-axe + `check:contrast`, so adding an e2e axe harness would diverge from the repo norm. No new e2e added.

- [x] **Task 5 — Regression proof (AC7)**
  - [x] Proved the widened gate is **red** on an Epic 3c regression: temporarily flipped an expected value in `ProjectConnectorResolverTest` and ran the aggregator under `-Pfoundation-gate verify` (scoping Surefire out so the aggregator does the catching). Only **Contract #17** failed, with `[story 3c.3] delegate-run reported 1 failure(s) in org.dradgo.application.project.ProjectConnectorResolverTest: resolvesEachRegisteredKindToItsDeclaringAdapter()`; all other contracts stayed green. Reverted the break (git diff confirms the test is back to HEAD); the break was never committed.
  - [x] Confirmed the CI `foundation-gate` job already requires the Epic 3c suites (`needs:` `backend-contract-tests` + `frontend-build-tests` + `frontend-e2e`). No CI YAML change needed — adding Contracts #16–#20 plugs Epic 3c into the `FoundationGateVerificationTest` run that already executes inside that job.

- [x] **Task 6 — Record evidence (AC6)**
  - [x] Cited the recorded security-review sign-off: `docs/adr/0013-credential-encryption.md` Status line "security review signed off (AC6: APPROVED, no HIGH/MEDIUM findings)" + Consequences §Neutral. No new sign-off performed (discharged by 3c-4).

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] This story added **no net-new production code** — it is test/gate/coverage consolidation. The minimum logging surface is satisfied by the already-instrumented services this story only *tests* (`ProjectConnectorResolver` resolution/WARN logs; the cipher deliberately silent on the secret hot path).
  - [x] No task forced a net-new production code path, so no new logging surface was added. The existing instrumented surfaces remain pinned by their tests (delegate-run green in Contracts #17/#18).
  - [x] Did NOT add logging to the cipher secret hot path (tested invariant — Contract #18 / `cipherEmitsNoLogsOnTheSecretHotPath`).

## Dev Notes

### What already exists (DO NOT REBUILD) — exhaustive map with file:line

**Foundation-gate aggregator + Maven routing**
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java` — the aggregator. Contracts #1–#15 today (Epic 1 + stories 3.13/3.14/3.15/3.19/3.32/3.33). Pattern to copy: `FoundationGateAssertions.delegateRunAssertGreen("X.Y", "<FQN>")`, `@Tag("foundation-gate")` on class + each `@Nested`, `[story X.Y]`-prefixed failure messages. Contract #11 (lines 223–267) shows re-running an already-aggregated test for legibility; Contract #10 (lines 170–220) shows a cheap *direct* assertion alongside a delegate-run. Class header (lines 8–28) says "Epic-1" — update it.
- `FoundationGateAssertions` (same package) — the `delegateRunAssertGreen` / `tagged` helpers.
- Maven `foundation-gate` profile: `deliveryline-backend/pom.xml` ~lines 675–711 (`<groups>foundation-gate</groups>`, `jacoco.check.skip=true` in this profile). Both Surefire + Failsafe list `foundation-gate` under `<excludedGroups>` in the default build; the profile clears it. Run with `-Pfoundation-gate verify`.

**Registries / prefixes / drift (AC1) — already covered**
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java` (`@Tag("architecture")`, gate Contract #3):
  - L88–121 `registryCatalogExposesTheAuthoritativeFoundationValueSets()` — already includes `ProjectStatus`, `ConnectorKind`, `PublicIdPrefixes`.
  - L186–205 `projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest()` — drift vs `ck_projects_status`, `ck_projects_ticket_source_kind`, `ck_projects_repo_host_kind` + API placeholder.
  - L208–214 `connectorRoleStaysAlignedWithProjectCredentialsCheck()` — `ConnectorRole` vs `ck_project_credentials_connector_role`.
  - L450–473 — fail-fast persistence-boundary parsing for `projects.*` + `project_credentials.connector_role` (`UNKNOWN_REGISTRY_VALUE`).
- `deliveryline-backend/src/test/java/org/dradgo/contract/FlywaySchemaContractTest.java` — V17 `projects` + `project_credentials` schema shape (the ADR 0013 note at line 20 references its `AES_GCM` placeholder fixture row).
- All five Epic 3c `DomainErrorCode`s (`PROJECT_NOT_FOUND`, `PROJECT_SLUG_CONFLICT`, `UNSUPPORTED_CONNECTOR_KIND`, `CREDENTIAL_MASTER_KEY_UNCONFIGURED`, `DOCTOR_PROJECT_CONFIG_INCOMPLETE`) are **auto-covered** by `ProblemDetailsCoverageFoundationContract` (gate Contract #7) because it iterates `DomainErrorCode.values()` (L53–164). No new error-code work in this story. See [[new-domainerrorcode-three-sites]].

**Per-project connector resolution (AC1) — already covered**
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectConnectorResolverTest.java` — exhaustive: kind→adapter (L52–69), `UNSUPPORTED_CONNECTOR_KIND` (L96–125), `findTicketSource` returns empty NOT throw (L82–92, preserves `SKIPPED_NO_LINEAR_PROFILE` — see [[story-3c-7-run-project-association-reconciliations]]), capability degradation parity (L159–179), project-scoped `LINEAR_GITHUB_REPO_MISMATCH` (L184–240), credential-seam no-log (L244–278), no-plaintext-field hygiene (L280–301), structured logging (L305–350). Plus `ProjectConnectorResolverWiringIT.java` (Spring `@Primary` ambiguity resolution).

**Cipher (AC6) — already covered**
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/crypto/EnvelopeCredentialCipherTest.java` — round-trip ASCII/unicode/empty/4KB (L36–47), non-determinism (L50–59), GCM tamper rejection w/o partial plaintext (L61–70), wrong-keyId (L72–78), unsupported-algo (L80–86), malformed ciphertext (L88–96), construction fail-fast on bad KEK (L99–112), keyless refusal (L122–129), deterministic keyId (L114–119), **no-logs-on-secret-hot-path (L137–150)**.
- `CredentialMasterKeyGuardTest.java` (same package) — `CREDENTIAL_MASTER_KEY_UNCONFIGURED` fail-fast-only-when-credentials-present.

**Credential redaction across egress (AC3) — already covered**
- `deliveryline-backend/src/test/java/org/dradgo/foundation/RedactionAdversarialFoundationContract.java` (gate Contract #9, L54–167) — sweeps ALL fixtures + silent-fixture manifest invariant (L141–154). See [[redaction-fixture-two-gates]].
- `deliveryline-backend/src/test/resources/redaction-fixtures/fixtures-manifest.json` — already lists `project-credential-linear-token.json` (~L171–175), `project-credential-github-token.json` (~L176–183), `project-credential-opaque-under-key.json` (~L185–189).
- `RedactionPolicyServiceContractTest.java` (L32–96), `LoggingRedactionContractTest.java` (L80–127), `LoggingForbiddenPayloadContractTest.java` (L77–111, four egress surfaces incl. `DoctorProbeAdapter` env probe). Project-credential redaction was shipped by 3c-5; see [[story-3c-5-credential-store-redaction-reconciliations]].

**Config-inversion parity + per-project dispatch (AC2) — already covered**
- `deliveryline-backend/src/test/java/org/dradgo/application/workflow/RunProjectAssociationIT.java`:
  - L135–161 `defaultProjectRunStaysByteIdenticalToPre3c()` — drives a real dispatch, asserts the global repo ref + global OpenSpec flag + no `DELIVERYLINE_RUNNER_OPENSPEC` env (pre-3c parity).
  - L81–132 `nonDefaultProjectRunDispatchesWithThatProjectsRepoOpenSpecAndConnectorSelection()` — non-default project (`acme/widgets`, LINEAR+GITLAB, OpenSpec=true) → real dispatch carries `request.repositoryRef()=="acme/widgets"` + `request.openspecEnabled()==true`.
- `DefaultProjectSeederIT.java` (L49–86) — seed-from-global byte-parity + no-duplicate-on-restart. See [[story-3c-6-default-project-config-inversion-reconciliations]].

**JaCoCo coverage (AC5) — partially exists**
- `deliveryline-backend/pom.xml` `<rules>` (~L405–555):
  - BUNDLE: 0.75 LINE / 0.55 BRANCH (~L488–502).
  - PACKAGE 0.80 LINE block (~L511–531) already includes `org.dradgo.application.project` (comment cites story 3c-3 AC9).
  - PACKAGE 0.90 LINE block (~L532–550) for `org.dradgo.infrastructure.crypto` (comment literally cites "3c-11 AC5" + "credential/crypto code").
  - PACKAGE element names MUST be **DOTTED** form (slash form silently matches nothing — verified in the existing comment). Only exclusion is `DeliveryLineApplication.class`.
- Frontend `deliveryline-frontend/vitest.config.ts` thresholds (~L51–62): `src/features/projects/**` @ 85 lines (comment cites "Story 3c-9 Task 9"), `src/lib/sanitization/**` @ 86.

**Frontend Projects suite (AC4) — already covered**
- Vitest: `deliveryline-frontend/src/features/projects/__tests__/` — `ProjectList.test.tsx`, `ProjectForm.test.tsx`, `ProjectSelector.test.tsx`, `ConnectionTestControl.test.tsx`, `CredentialControl.test.tsx`, `ProjectsScreen.test.tsx`, `projectHooks.test.tsx`, `projectFormView.test.ts` — each UI state axe-scanned via `src/test/a11y/axe.ts` (`expectNoA11yViolations`, WCAG 2.1 AA tags). See [[story-3c-9-projects-management-ui-reconciliations]].
- Playwright: `deliveryline-frontend/e2e/projects-management.spec.ts` (~79 lines) — AC10 create→credential→test journey + AC8 keyboard-reachability. NOTE: **no axe in any Playwright spec** — that is the repo norm (a11y gate = Vitest-axe + `check:contrast`), not a gap.
- CI scripts (`deliveryline-frontend/package.json`): `test` (vitest run), `test:coverage`, `test:e2e` (playwright), `check:a11y`, `check:api`, `lint`, `format:check`, `build`. The frontend lockfile is cross-platform-sensitive — see [[frontend-lockfile-cross-platform]] and [[frontend-ts6-legacy-peer-deps]] before touching deps (you should not need to).

**Security sign-off (AC6) — already recorded**
- `docs/adr/0013-credential-encryption.md` — Status line (L3): "security review signed off (AC6: APPROVED, no HIGH/MEDIUM findings)"; Consequences §Neutral (L69): "the sign-off is recorded in the story Completion Notes and the PR description (no CI job exists)." Cite this; do not re-run a review.

**CI gate (AC7) — already enforces Epic 3c**
- `.github/workflows/ci.yml` `foundation-gate` job (L1041–end): `needs:` includes `backend-contract-tests` (full `mvn verify` → all Epic 3c ITs + contract tests, L437–507), `frontend-build-tests` (Vitest, L253–309), `frontend-e2e` (Playwright, L329–384), plus runs `FoundationGateVerificationTest` under `-Pfoundation-gate`. The job converts skipped/failed needs into an explicit fail (L1074–1089). Adding Contracts #16–#20 plugs Epic 3c into the named gate run that already executes here.

### Open Decisions to resolve during implementation

- **[D1] AC5 project-adapter coverage floor.** Project adapter classes (`ProjectController`, `ProjectPersistenceAdapter`, `ProjectCredentialPersistenceAdapter`) live in the shared `org.dradgo.adapters.rest` / `org.dradgo.adapters.persistence` packages, so a per-package 0.80 floor would apply to the *entire* REST/persistence surface, not just project adapters. **Recommended resolution:** keep `application.project` (0.80) + `infrastructure.crypto` (0.90) floors as the AC5 coverage commitment; document that project adapters are guarded by the BUNDLE floor + their dedicated `ProjectControllerContractTest` / `ProjectPersistenceAdapterIT` / `ProjectCredentialPersistenceAdapterIT`, and that an isolated adapter floor is intentionally not added (would require a package split, out of scope). Record measured numbers. Decide + write it down — do not silently skip.
- **[D2] AC4 Playwright depth.** Existing Vitest-axe + the e2e journey appear sufficient. **Recommended resolution:** accept as-is, documenting that axe-in-Playwright is not a repo pattern; only add a focused Projects edge-case e2e if Task 4 surfaces a concrete uncovered path. Do not build a new e2e axe harness.
- **[D3] Aggregator numbering / grouping.** Contracts #16–#20 above are a suggestion. You may merge or split (e.g. one combined "Epic 3c registries" contract vs. separate registry + Flyway). Keep each delegate-run legible and `[story 3c.N]`-tagged; do not exceed what the source-of-truth tests assert.

### Project Structure Notes

- Backend foundation/contract tests: `deliveryline-backend/src/test/java/org/dradgo/{foundation,contract,architecture}/` and per-feature dirs (`application/project/`, `infrastructure/crypto/`, `application/workflow/`).
- `*FoundationContract` classes are deliberately named so Maven discovery skips them (not `*Test.java` / not in Failsafe includes) — they are reached **only** via the Launcher API from the aggregator. If you author any new contract class (not expected — delegate to existing tests instead), follow that naming.
- Adding a `@Tag("foundation-gate")` test is gated behind the profile — it will NOT run in `mvn test` or default `mvn verify`. Always verify with `-Pfoundation-gate verify`. See [[transition-table-change-fans-to-contracts]] for the "only the gate catches it" failure mode.
- Reproduce CI shape in a clean env before claiming green — see [[verify-ci-fixes-in-clean-env]] and [[wsl-linux-ci-reproduction]]. Local green ≠ CI green, especially for Testcontainers ITs delegated into the gate.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above. **For this story specifically**, no net-new production service is expected; the requirement is satisfied by verifying the existing instrumented Epic 3c surfaces stay pinned by their tests (and that the cipher secret hot path stays silent).

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Required context keys** (MDC or structured params): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus any entity public ids (`projectId` as `prj_`…, never the slug alone).
- **Forbidden in log output:** payload bytes, secrets/tokens (esp. credential plaintext/ciphertext), raw PII, classification-restricted fields. Route through the existing redaction path. The cipher MUST remain silent on the secret hot path.
- **Test contract:** any new logging surface must be pinned by a list-appender / `OutputCaptureExtension` test.

### References

- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-11] — the 7 ACs this story reconciles.
- [Source: _bmad-output/planning-artifacts/epic-03c-multi-project-configuration.md#Story-3c-2..3c-10] — the upstream stories that already shipped the underlying tests.
- [Source: deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java] — aggregator to widen (Contracts #1–#15 today).
- [Source: deliveryline-backend/pom.xml] — `<rules>` JaCoCo block (~L405–555), `foundation-gate` profile (~L675–711).
- [Source: docs/adr/0013-credential-encryption.md] — recorded security sign-off (AC6) + threat model.
- [Source: docs/patterns/registry-recipe.md] — registry mirror-site recipes + which tier catches each (architecture vs foundation-gate).
- [Source: docs/testing/frontend-test-patterns.md] — Vitest/axe patterns for the frontend suite.
- [Source: docs/testing/snapshots-vs-assertions.md] — OpenAPI snapshot flow (not expected to change here).
- [Source: .github/workflows/ci.yml] — `foundation-gate` job + tier chain (AC7).

## Dev Agent Record

### Agent Model Used

Claude Opus 4.8 (1M context) — claude-opus-4-8[1m]

### Debug Log References

- `gate-widened.log` — `-Pfoundation-gate verify` of the widened aggregator: 45 tests, 0 failures, 1 skip (the deliberately-disabled `FoundationGateRegressionDetectionTest` self-test). All 20 contracts (#1–#20) green; BUILD SUCCESS.
- `full-verify.log` — first full `verify` (with `-Dfrontend-maven-plugin.skip=true`): 1196 Surefire + 733 Failsafe, "All coverage checks have been met", but `BundledJarPackagingContractTest` failed because the shared `frontend-maven-plugin.skip` toggle also skips `copy-frontend-dist` (the SPA shell never lands in the jar). Environmental, not a regression.
- `full-verify2.log` — clean full backend `verify` (no skip; existing `deliveryline-frontend/target/dist` copied in): Surefire **1196/0/0** (12 skip), Failsafe **733/0/0** (1 skip), `BundledJarPackagingContractTest` PASS, "All coverage checks have been met", BUILD SUCCESS.
- `regression-proof2.log` — Task 5 proof: with `ProjectConnectorResolverTest` temporarily broken, only Contract #17 failed with `[story 3c.3] delegate-run reported 1 failure(s)`; break reverted.

### Completion Notes List

**Story type:** CONSOLIDATION/AGGREGATION — verify-don't-rebuild. Stories 3c-1..3c-10 had already shipped ~90% of Epic 3c's cross-cutting test debt; the net-new deliverable was wiring those existing source-of-truth tests into the named foundation gate.

- **Primary deliverable (Task 2):** widened `FoundationGateVerificationTest` with Epic 3c Contracts **#16–#20**, delegate-running the existing tests (`RegistryContractTest`, `FlywaySchemaContractTest`, `ProjectConnectorResolverTest`, `EnvelopeCredentialCipherTest`, `CredentialMasterKeyGuardTest`, `RedactionAdversarialFoundationContract`, `RunProjectAssociationIT`, `DefaultProjectSeederIT`) plus one cheap direct manifest drift-guard in Contract #19. No assertion re-authoring; no source-of-truth test retagged (Launcher discovers by FQN). Class Javadoc header updated.
- **AC1** (registries/prefixes/drift + per-project resolution as named gate contracts) → Contracts #16, #17. **AC2** (config-inversion parity + per-project dispatch) → Contract #20. **AC3** (credential redaction across egress) → Contract #19 (+ direct fixture guard). **AC6** (cipher tamper/round-trip/fail-fast) → Contract #18; security sign-off cited from `docs/adr/0013-credential-encryption.md` (AC6: APPROVED, no HIGH/MEDIUM). **AC7** (gate red on Epic 3c regression) → Task 5 proof + CI `foundation-gate` already `needs:` the backend/frontend suites.
- **AC5 / D1:** JaCoCo floors `application.project` 0.80 + `infrastructure.crypto` 0.90 confirmed present and met ("All coverage checks have been met"). No isolated project-adapter floor added — the shared `org.dradgo.adapters.persistence` package measures **74.7%** LINE overall (below 0.80), so a per-package floor would red unrelated adapters; decision + measured per-class numbers (ProjectController 86.9%, ProjectPersistenceAdapter 80.0%, ProjectCredentialPersistenceAdapter 85.1%, ProjectEntityMapper 100%) recorded in a `pom.xml <rules>` comment. Project adapters are guarded by the BUNDLE floor + dedicated `*ContractTest`/`*IT`.
- **AC4 / D2:** Projects Vitest suite green (8 files / 51 tests, axe-per-state); `e2e/projects-management.spec.ts` covers AC10 journey + AC8 keyboard reachability. D2 resolved to option (a): Vitest-axe + e2e journey are sufficient; axe-in-Playwright is not a repo pattern (a11y gate = Vitest-axe + `check:contrast`). No e2e added.
- **No net-new production code.** Test/gate/coverage consolidation only. No new `DomainErrorCode`, Flyway migration, OpenAPI change, or CI YAML change. Logging requirement satisfied by the already-instrumented (and gate-pinned) Epic 3c surfaces.
- **Verification:** widened gate green (20 contracts); clean full backend `verify` green (1196 + 733, coverage met, packaging passes); regression proof confirmed the gate is `[story 3c.N]`-red on an Epic 3c break. The temporary break was reverted and never committed.

### File List

- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java` — MODIFIED: added Epic 3c Contracts #16–#20 (`@Nested` delegate-runs + Contract #19 manifest drift guard); updated imports + class-level Javadoc header.
- `deliveryline-backend/pom.xml` — MODIFIED: added a `<rules>` comment recording the AC5/D1 decision (no isolated project-adapter floor) + measured coverage numbers. No floor values changed.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — MODIFIED: 3c-11 status `ready-for-dev → in-progress → review`; `last_updated` entries.

### Change Log

| Date       | Change                                                                                          |
| ---------- | ----------------------------------------------------------------------------------------------- |
| 2026-06-21 | Widened `FoundationGateVerificationTest` with Epic 3c Contracts #16–#20 (delegate-run existing source-of-truth tests + Contract #19 manifest drift guard); recorded AC5/D1 adapter-coverage decision as a pom comment. Widened gate green (20 contracts); clean full backend verify green; regression proof confirms `[story 3c.N]`-red on Epic 3c break. Status → review. |
| 2026-06-21 | bmad-code-review (3-layer adversarial: Blind Hunter + Edge Case Hunter + Acceptance Auditor) → **CLEAN**. 0 decision-needed, 0 patch, 0 defer, 11 dismissed. Edge Hunter (file access) refuted the Blind Hunter's diff-only concerns: `delegateRunAssertGreen` fails on zero-found/zero-succeeded (`FoundationGateAssertions.java:81-97`), all 8 FQNs resolve, manifest key `file`/path/fixtures all verified, ITs delegate cleanly. Auditor mapped all 7 ACs → SATISFIED. Status → done. |

### Review Findings

✅ Clean review — all three adversarial layers passed (2026-06-21). No decision-needed, patch, or deferred findings. 11 Blind-Hunter items dismissed as noise/false-positive/intended-pattern after the file-access layers verified the delegate-run helper's fail-loud semantics, FQN resolution, manifest-key correctness, CWD-safe path, and Testcontainers IT delegation.
