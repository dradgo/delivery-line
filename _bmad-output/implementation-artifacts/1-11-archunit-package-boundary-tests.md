# Story 1.11: ArchUnit Package-Boundary Tests

Status: done

<!-- Note: Validation is optional. Run `bmad-create-story:validate` for a quality check before `bmad-dev-story`. -->

## Story

As a foundation developer,
I want an ArchUnit test suite enforcing adapter/application/domain/infrastructure boundaries and the hard-invariant rules from the architecture document,
so that no adapter can call persistence directly, no business rule can drift into an adapter, and no violation reaches CI undetected.

## Acceptance Criteria

1. **Given** `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java`, **Then** ArchUnit rules enforce layered dependency direction:
  - `domain` may not depend on Spring/JPA/Jackson types, on any `adapters.*` package, or on `infrastructure.*`.
  - `application` depends only on `domain` and on application-owned ports/SPIs; it must not depend on any `adapters.*` package.
  - `adapters.*` may depend on `application` + `domain` + Spring infrastructure but not on other `adapters.*` packages.
  - `infrastructure.*` may depend on `application` and `domain` but must not depend on any `adapters.*` package; nothing in `domain` may reference `infrastructure`.
2. **Given** `adapters.cli` and `adapters.rest` classes, **Then** ArchUnit asserts they never directly inject or reference `adapters.persistence` repository classes, `adapters.runner.*` adapters, or `adapters.files.*` storage implementations (must go through application services).
3. **Given** `WorkflowTransitionService` from story 1.5, **Then** ArchUnit asserts `workflow_runs.current_state` is mutated only by that service. The submit bootstrap path must be refactored so `WorkflowRunEntity.setCurrentState(...)` is no longer called outside the transition seam — initial state is set via factory or constructor at run creation. No documented exception is allowed.
4. **Given** `ArtifactOperationService` from story 1.12, **Then** ArchUnit asserts approval-eligible artifact file writes go only through that service; `adapters.rest`, `adapters.cli`, and other application services may not bypass it. The rule is scaffolded now using string-based class-name matching so it activates cleanly once story 1.12 lands.
5. **Given** `RedactionPolicyService` from story 1.10, **Then** ArchUnit asserts no adapter class implements its own credential-detection logic independent of the service. The fence is **narrow**: credential-regex catalogs and credential-detection predicates may only reside in `application.security`. Do **not** gate the rule on `DataClassification` enum references — that enum lives in `domain.registry` and must remain referenceable from anywhere.
6. **Given** forbidden dependencies in `domain`, **Then** rules reject: Jackson annotations/types, Spring annotations, JPA annotations, `HttpServletRequest`/`HttpServletResponse`, and Spring Shell command annotations.
7. **Given** naming conventions, **Then** ArchUnit asserts:
  - REST controller classes end in `Controller` and reside under `adapters.rest`.
  - Spring Shell adapter classes are **pluralized** (end in `Commands`) and reside under `adapters.cli`.
  - Application-layer command DTOs are **singular** (end in `Command`) and reside under `application`.
  - Persistence entities end in `Entity` and reside under `adapters.persistence.entity`.
  - Persistence mappers reside under `adapters.persistence.mapper` and end in `Mapper` or `Mappers`. The rule is **location-qualified**: classes ending in `Mapper` outside `adapters.persistence.mapper` (e.g., REST `ProblemDetailsMapper`) are not flagged.
  - Application services end in `Service`.
  - Application use-case results end in `Result` or `Outcome`.
8. **Given** the test suite, **Then** ArchUnit tests run as part of `mvn test` today and fail with clear, actionable diagnostic output (offending class + rule name + remediation hint). Story 1-21 owns CI profile/group routing; this story does not. A meta-test must deliberately violate one named rule (excluded from the main scan) and assert the failure message format. Violations fail the build loudly; no freeze/baseline mode is allowed in this foundation slice.
9. **Given** the `adapters.persistence` module, **Then** ArchUnit asserts explicit mapper classes exist under `adapters.persistence.mapper` and JPA entity types do not leak into `application` or `domain` signatures. Current direct `entity`/`repository` imports from application services must be eliminated by routing through application-owned ports before the rule passes.
10. **Given** each central registry (from story 1.4), **Then** registry sync between domain enums, REST DTO enum references, and central registry values is enforced by including the existing `RegistryContractTest` in the same Maven Surefire group as the architecture suite. ArchUnit owns architectural shape; `RegistryContractTest` owns enum-value sync — neither calls the other from inside a rule.

## Tasks / Subtasks

- [x] **Task 1: Add ArchUnit to the backend architecture-test stack** (AC: 1, 8)
  - [x] Add `com.tngtech.archunit:archunit-junit5:1.4.2` as a literal version on the dependency in `deliveryline-backend/pom.xml` with `test` scope. Spring Boot BOM does not manage ArchUnit. Do not introduce a separate `<archunit.version>` property unless multiple ArchUnit artifacts are added.
  - [x] Create `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` using ArchUnit's JUnit 5 support (`@AnalyzeClasses`, `@ArchTest`).
  - [x] Exclude test classes from the imported production graph.
  - [x] Give each rule an explicit name and remediation hint.
  - [x] Add a meta-test (e.g., `ArchitectureDiagnosticMetaTest`) that deliberately violates one named rule, is excluded from the main scan, and asserts the failure message contains rule name, offending class, and remediation hint.

- [x] **Task 2: Codify the core package/layer rules against the real repo packages** (AC: 1, 2, 6, 7, 8)
  - [x] Enforce the reserved package layout: `org.dradgo.domain..`, `org.dradgo.application..`, `org.dradgo.adapters.cli..`, `org.dradgo.adapters.rest..`, `org.dradgo.adapters.persistence..`, `org.dradgo.adapters.files..`, `org.dradgo.adapters.runner..`, `org.dradgo.adapters.integration..`, `org.dradgo.infrastructure..`.
  - [x] Use `layeredArchitecture()` for layer direction including `infrastructure` (see AC1) and `slices().matching("org.dradgo.adapters.(*)..").beFreeOfCycles()` to forbid implicit adapter-to-adapter coupling.
  - [x] Add dedicated rules that `adapters.cli` and `adapters.rest` may not reference Spring Data repositories, JPA entities, filesystem adapters, or runner adapters directly.
  - [x] Encode the Spring Shell `Commands` (plural) rule scoped to `adapters.cli` only. Encode the `Command` (singular) DTO rule scoped to `application` only. Do not write a generic `*Command` predicate that fires across both.
  - [x] Keep all naming rules in the same architecture suite.

- [x] **Task 3: Refactor the current persistence seam so the rules can pass** (AC: 1, 2, 9)
  - [x] Introduce application-owned ports for workflow-run persistence, workflow-event persistence, and idempotency persistence. Interfaces live under `application` (e.g., `application.workflow.spi`, `application.idempotency.spi`); JPA implementations live under `adapters.persistence`.
  - [x] Add explicit mapper classes under `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/` to translate between JPA entities and application-facing records/views. `WorkflowRunEntity`, `WorkflowEventEntity`, and `IdempotencyRecordEntity` must not appear in `application` signatures after this story.
  - [x] Refactor `WorkflowTransitionService`, `WorkflowCommandService`, and `IdempotencyService` to depend on those ports instead of importing `adapters.persistence.entity.*` and `adapters.persistence.repository.*` directly.
  - [x] Refactor the submit bootstrap path so initial run state is set via factory/constructor at creation; remove the `setCurrentState(...)` call from `WorkflowCommandService.submitInternal(...)`.

- [x] **Task 4: Lock the state-mutation and redaction invariants with precise rules** (AC: 3, 4, 5, 8)
  - [x] Add a focused rule: only `WorkflowTransitionService` calls `WorkflowRunEntity.setCurrentState(...)` (or its replacement after Task 3). No documented exception.
  - [x] Scaffold the future `ArtifactOperationService` monopoly rule using package/class-name string matching that compiles before story 1.12 exists. Sketch (illustrative; commented in the test):
```java
    // ArchCondition<JavaClass> onlyArtifactOperationServiceMayWriteArtifacts = ...
    //   noClasses().that().resideOutsideOfPackage("..application.artifact..")
    //     .should().callMethodWhere(target ->
    //       target.getOwner().getName().endsWith("LocalArtifactStore")
    //       && target.getName().equals("write"));
```
  - [x] Add the redaction boundary rule narrowly: credential-regex patterns and credential-detection predicates may only reside in `application.security`. Do not gate on `DataClassification` enum references; legitimate non-secret regex usage in `domain.id.PublicIdPrefixes`, `application.idempotency.IdempotencyKeyValidator`, and `adapters.rest.ProblemDetailsMapper` must not be flagged.
  - [x] Do not use ArchUnit freeze support.

- [x] **Task 5: Preserve the thin-adapter contract and future extension points** (AC: 2, 7, 8, 10)
  - [x] Keep `WorkflowController` and `WorkflowCommands` transport-only.
  - [x] Add rule helpers later stories can reuse for "thin controller", "thin CLI command", and "only service X may touch field Y".
  - [x] For AC10: include `RegistryContractTest` in the same Maven Surefire group as the architecture suite (e.g., shared `@Tag("architecture")` or matching test-include pattern). Do not call it from inside an ArchUnit rule.
  - [x] Leave extension seams for later epics: vendor-specific integration leak rules, `RecoveryService` scope-protection, export-only composition rules, retention field-access rules, and queue/worker monopoly rules.

### Review Findings

*Code review run: 2026-05-03 - three-layer adversarial review (Blind Hunter, Edge Case Hunter, Acceptance Auditor) against ****\*******`git diff HEAD`****\* of story 1.11 working tree.*

*Apply log (2026-05-03, default-decision pass): applied the approved boundary-policy defaults, closed the decision-tagged review items that were blocking rereview, and reran the full backend suite to confirm the integrated codepath.*

**Decision set applied**

- [x] [Review][Decision] AC3 - narrowed `WorkflowRunEntity.setCurrentState(...)` to package-private and replaced the dependency-only fence with a direct ArchUnit method-call rule that allows only `WorkflowTransitionService`.
- [x] [Review][Decision] AC1 - kept cycle-freeness and added a strict no-cross-adapter-dependency rule between adapter slices.
- [x] [Review][Decision] AC2 - kept the broader thin-adapter fence: REST and CLI may not depend on persistence, files, runner, or integration adapters directly.
- [x] [Review][Decision] `ProblemDetailsMapper` now fails closed when `RedactionPolicyService` is unavailable or returns no field payload, using `[REDACTED]`.
- [x] [Review][Decision] `WorkflowRunSnapshot` remains intentionally lossy and now documents that boundary explicitly.
- [x] [Review][Decision] `WorkflowCommandService.submitInternal(...)` keeps the two-port transactional seam; the contract is documented and the INBOX bootstrap state is asserted explicitly.
- [x] [Review][Decision] `IdempotencyPersistenceAdapter.tryReserve(...)` now routes the reservation insert through `IdempotencyRecordRepository` instead of mixing `JdbcTemplate` writes with JPA reads.

**Required rereview fixes implemented**

- [x] [Review][Patch] Added artifact-write caller exclusion and a diagnostic violating fixture/meta-test so the scaffolded monopoly rule is provably live before story 1.12 lands.
- [x] [Review][Patch] Added positive non-empty architecture sentinels for `domain`, `application`, and `adapters`, while keeping the persistence-mapper rule non-empty and location-qualified.
- [x] [Review][Patch] `INTEGRATION_ADAPTER_PACKAGE` is now consumed by the transport-adapter boundary rule.
- [x] [Review][Patch] `ProblemDetailsMapper` now prefers the injected Jackson mapper, safely handles `field == null`, and redacts instead of leaking values when the redaction service is absent.
- [x] [Review][Patch] `ProblemDetailsContractTest` keeps meaningful redaction assertions after the mapper change.
- [x] [Review][Patch] `IdempotencyPersistenceAdapter.markCompleted(...)` now translates missing rows to `DomainException(IDEMPOTENCY_RECORD_LOST, ...)`.
- [x] [Review][Patch] `WorkflowEventPersistenceAdapter.append(...)` now translates missing runs to `DomainException(RUN_NOT_FOUND, ...)`.
- [x] [Review][Patch] `WorkflowRunPersistenceAdapter.updateCurrentState(...)` now distinguishes missing-run vs optimistic-lock-conflict behavior.
- [x] [Review][Patch] `WorkflowRunSnapshot.requiredVersion()` guards null optimistic-lock versions with a domain-level failure.
- [x] [Review][Patch] `WorkflowEventEntityMapper.toEntity(...)` now guards null `details()`.
- [x] [Review][Patch] `IdempotencyRecordPort.isReservationStale(...)` now uses `Duration` instead of a unit-encoded `long`.
- [x] [Review][Patch] `IdempotencyService` now throws stable `IDEMPOTENCY_RESERVATION_EXHAUSTED` after retry exhaustion and no longer carries the outdated DB-conflict comment.
- [x] [Review][Patch] `WorkflowCommandService.submitInternal(...)` now asserts the created snapshot is `INBOX` and returns the explicit `WorkflowState.INBOX` literal.
- [x] [Review][Patch] Added the supporting unit/contract/meta tests plus schema placeholder updates needed to keep the new governed error codes observable in the test suite.

**Non-blocking follow-up candidates**

- [ ] [Review][Follow-up] SPI nullability/Javadoc polish across `application/*/spi`.
- [ ] [Review][Follow-up] The redaction-boundary rule still relies on a small name list for sensitive helper detection; if this area grows, replace the name-list heuristic with an explicit marker/annotation design.

**Deferred**

- [x] [Review][Defer] `WorkflowEventPersistenceAdapter.append` re-fetches `WorkflowRunEntity` per event call (N+1 risk) [src/main/java/org/dradgo/adapters/persistence/WorkflowEventPersistenceAdapter.java] - deferred until batch event paths exist.
- [x] [Review][Defer] JPQL `:currentState` String coercion in `WorkflowRunRepository.updateCurrentState` [src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java] - deferred after runtime verification against the Postgres enum column.
- [x] [Review][Defer] `ArchitectureDiagnosticMetaTest` validates one rule's diagnostic format [src/test/java/org/dradgo/architecture/ArchitectureDiagnosticMetaTest.java] - AC8 is satisfied as written.
- [x] [Review][Defer] `ImportOption.DoNotIncludeTests` excludes test packages from the main architecture scan [src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java] - intentional test design choice.
- [x] [Review][Defer] AC10 Surefire routing beyond the shared `@Tag("architecture")` remains owned by story 1.21 [deliveryline-backend/pom.xml].
- [x] [Review][Defer] `IdempotencyService.complete` still lacks explicit contention-path tests [src/test/java/org/dradgo/application/idempotency/] - deferred test follow-up.
- [x] [Review][Defer] `IdempotencyRecordPort.tryReserve` still takes six strings in sequence [src/main/java/org/dradgo/application/idempotency/spi/IdempotencyRecordPort.java] - ergonomic refactor only.
- [x] [Review][Defer] `InvalidShellAdapter` still lives in a production-style package path under tests [src/test/java/org/dradgo/adapters/cli/InvalidShellAdapter.java] - cosmetic cleanup only.
- [x] [Review][Defer] `WorkflowEventRecord.details` still exposes the map directly [src/main/java/org/dradgo/application/workflow/spi/WorkflowEventRecord.java] - broader API hardening pass.

*Dismissed: ****\*******`@ArchTest`****\* field declarations are the intended ArchUnit/JUnit Jupiter mechanism; single-method SPIs remain intentional ISP; style-only ****\*******`var`****\* remarks were ignored; ****\*******`WorkflowRunEntity.create(...)`****\* is the intended factory fence; and the inline ArchUnit dependency version remains mandated by Task 1.*

*Code review run: 2026-05-04 - triaged parallel review layers against the current ****`git diff HEAD -- deliveryline-backend`**** working tree. Edge Case Hunter returned markdown instead of its expected structured JSON, so findings were parsed best-effort before triage.*

- [x] [Review][Patch] Workflow state monopoly rule does not cover the new `WorkflowRunStatePort` mutation seam [deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:224]
- [x] [Review][Patch] Artifact-write monopoly rule exempts the whole `application.artifact` package instead of only `ArtifactOperationService` [deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java:230]
- [x] [Review][Patch] `ProblemDetailsMapper` can throw while redacting rejected values on the exception path [deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java:363]

## Dev Notes

### Hard prohibitions (consolidated)

- Do **not** add the JUnit 4 ArchUnit artifact, the Maven plugin, or a second test framework.
- Do **not** use `FreezingArchRules` / baseline files in this slice.
- Do **not** swap ArchUnit for a custom reflection scanner, annotation processor, or static-analysis stack.
- Do **not** implement `ArtifactOperationService`, `LocalArtifactStore`, runner adapters, export services, retention jobs, or `RecoveryService` deep actions in this story.
- Do **not** introduce a generic "application may set current state" loophole.
- Do **not** broaden the redaction rule into a generic "no regex outside `application.security`".
- Do **not** introduce CI profile/group routing (story 1-21 owns that).

### Sequencing constraint

Land Task 3 (seam refactor) and Task 2 (layer rules) in the **same PR**, or land Task 3 first. Never push layer rules ahead of the refactor — `mvn test` must stay green at every commit on `main`.

### Code state to be aware of

- `deliveryline-backend/pom.xml` — no ArchUnit dependency yet.
- `org.dradgo.architecture/` test package — does not exist.
- `org.dradgo.adapters.persistence.mapper/` — does not exist.
- `org.dradgo.application.workflow.WorkflowTransitionService` — imports JPA entities and Spring Data repositories directly.
- `org.dradgo.application.workflow.WorkflowCommandService` — imports JPA entities and Spring Data repositories directly; calls `setCurrentState(...)` in the submit bootstrap path.
- `org.dradgo.application.idempotency.IdempotencyService` — depends on persistence-layer types.
- `org.dradgo.application.security/` (story 1.10) — `RedactionPolicyService`, `DataClassificationService`, etc.
- `org.dradgo.domain.registry.DataClassification` — enum lives here; legitimate references from `application.security` and elsewhere must continue to compile.
- `org.dradgo.adapters.rest.ProblemDetailsMapper` and `org.dradgo.adapters.rest.WorkflowController`, `org.dradgo.adapters.cli.WorkflowCommands` — already thin; preserve.
- `org.dradgo.infrastructure/` — package exists; AC1 rule must include it.

### Implementation guardrails

- Prefer ArchUnit's built-in JUnit 5 support with `@AnalyzeClasses(packages = "org.dradgo")` and `@ArchTest` fields/methods.
- Use `layeredArchitecture()` for top-level direction and `slices().matching(...).should().beFreeOfCycles()` for cycle rules.
- Keep future-facing rules string-based so the suite compiles before future classes exist.
- Name rules after the business invariant they protect (e.g., "workflow state changes must go through WorkflowTransitionService"), not after the API call.
- The redaction boundary rule must be narrow enough to avoid false positives from legitimate non-secret regex usage.

### Predecessor-story contracts being enforced

- **Story 1.5** — `WorkflowTransitionService` is the only state-transition path. Story 1.11 is where that promise becomes enforceable; the submit-bootstrap exception is closed by refactor (Task 3).
- **Story 1.7** — CLI and REST adapters stay thin and translate to the shared command model. Story 1.11 is the enforcement point.
- **Story 1.10** — `application.security` owns credential-detection logic; `DataClassification` enum stays in `domain.registry`. Story 1.11's fence is on credential-regex ownership only.

### Latest technical guidance

- The official ArchUnit user guide currently documents version `1.4.2` and the JUnit 5 convenience artifact `com.tngtech.archunit:archunit-junit5:1.4.2`.
- Use `@AnalyzeClasses` + `@ArchTest`; avoid handwritten `ClassFileImporter` boilerplate per test method.
- Use `layeredArchitecture()` and `slices().matching(...).should().beFreeOfCycles()` library APIs over homegrown dependency walks.

### Project Structure Notes

- Architecture tests belong under `deliveryline-backend/src/test/java/org/dradgo/architecture/`.
- Application-owned persistence ports live under tightly scoped `application.*.spi` / `application.*.port` packages — in `application`, not in `domain`.
- JPA entities, Spring Data repositories, and mapper implementations stay under `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/`.
- No `project-context.md` exists in the repo today.

### References

**Source documents**
- `_bmad-output/planning-artifacts/epics.md` — Story 1.11 ACs; AR11; foundation-gate sequencing; downstream stories in Epics 3, 4, 5
- `_bmad-output/planning-artifacts/architecture.md` — package-boundary enforcement, service boundaries, data guardrails, quality gates
- `_bmad-output/implementation-artifacts/1-5-workflow-state-transition-table-and-workflow-transition-service.md`
- `_bmad-output/implementation-artifacts/1-7-shared-application-command-model-pattern.md`
- `_bmad-output/implementation-artifacts/1-10-redaction-classification-policy-and-adversarial-secret-fixture-set.md`
- `_bmad-output/implementation-artifacts/deferred-work.md`

**Code under inspection / refactor**
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/idempotency/IdempotencyService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/WorkflowController.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/cli/WorkflowCommands.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowRunEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java`

**External docs**
- ArchUnit user guide — https://www.archunit.org/userguide/html/000_Index.html

## Dev Agent Record

### Agent Model Used

GPT-5 Codex

### Debug Log References

- Selected story `1-11-archunit-package-boundary-tests` from `_bmad-output/implementation-artifacts/sprint-status.yaml`
- Verified the initial red phase with `mvn -pl deliveryline-backend "-Dtest=ArchitectureBoundaryTest,ArchitectureDiagnosticMetaTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- Verified the non-Docker suite with `mvn -pl deliveryline-backend "-Dtest=ArchitectureBoundaryTest,ArchitectureDiagnosticMetaTest,ProblemDetailsContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- Verified the full backend regression suite with `mvn -pl deliveryline-backend test`

### Completion Notes List

- 2026-05-02: Added the ArchUnit JUnit 5 dependency, the architecture rule catalog, the main `ArchitectureBoundaryTest`, and a diagnostic meta-test with an excluded violating fixture.
- 2026-05-02: Refactored workflow and idempotency persistence access behind application-owned SPI ports plus explicit persistence mappers and adapters, removing JPA entity/repository types from `application` signatures.
- 2026-05-02: Closed the submit bootstrap loophole by creating workflow runs through a factory-backed create path and routing runtime state mutations through a dedicated workflow state port used by `WorkflowTransitionService`.
- 2026-05-02: Moved adapter-side rejected-value masking behind `RedactionPolicyService` ownership while keeping Web MVC slice tests working through an optional provider fallback.
- 2026-05-02: Tagged `RegistryContractTest` with `@Tag("architecture")` so the registry drift gate travels with the architecture suite.
- 2026-05-02: Full verification passed with `mvn -pl deliveryline-backend test` (137 tests, 0 failures, 0 errors).
- 2026-05-03: Follow-up patch batch applied for review findings around architecture coverage, persistence-adapter missing-run handling, and `ProblemDetails` redaction verification.
- 2026-05-03: Focused non-Docker verification passed with `mvn -pl deliveryline-backend "-Dtest=ArchitectureBoundaryTest,ArchitectureDiagnosticMetaTest,ProblemDetailsContractTest,WorkflowEventPersistenceAdapterTest,WorkflowRunPersistenceAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`.
- 2026-05-03: Full backend verification passed with `mvn -pl deliveryline-backend test` (146 tests, 0 failures, 0 errors).
- 2026-05-03: Applied the approved decision set for AC1/AC2/AC3, fail-closed redaction, documented lossy workflow snapshots, and repository-owned idempotency reservation writes.
- 2026-05-03: Full backend verification passed again with mvn -pl deliveryline-backend test (157 tests, 0 failures, 0 errors) after the decision pass and new regression coverage landed.

### Review Follow-up Log

- 2026-05-03: Added `APPLICATION_MUST_NOT_DEPEND_ON_INFRASTRUCTURE` to the ArchUnit suite and kept the story in `in-progress` because review decision items remain open.
- 2026-05-03: Added `WorkflowEventPersistenceAdapterTest` and `WorkflowRunPersistenceAdapterTest` to lock in stable missing-run behavior and deleted-vs-conflict separation.
- 2026-05-03: Updated `ProblemDetailsMapper` to prefer an injected Jackson mapper when available and added a contract test that proves rejected-value redaction still delegates through `RedactionPolicyService`.
- 2026-05-03: Applied the approved review defaults, added direct setter-call enforcement plus idempotency exhaustion/missing-record guards, and moved the story back to 
eview.

### File List

- `_bmad-output/implementation-artifacts/1-11-archunit-package-boundary-tests.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `deliveryline-backend/pom.xml`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/IdempotencyPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowEventPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/WorkflowRunPersistenceAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/entity/WorkflowRunEntity.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/IdempotencyRecordEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/WorkflowEventEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/mapper/WorkflowRunEntityMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/persistence/repository/WorkflowRunRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsMapper.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/idempotency/IdempotencyService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/idempotency/spi/IdempotencyRecordPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/idempotency/spi/IdempotencyRecordSnapshot.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowCommandService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowEventRecord.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowEventWritePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunCreatePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunReadPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunSnapshot.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowRunStatePort.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/InvalidShellAdapter.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureDiagnosticMetaTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/ProblemDetailsContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java`

### Change Log

- 2026-05-02: Implemented the architecture enforcement suite, persistence seam refactor, and redaction-boundary cleanup; verified with the full backend Maven test suite and moved the story to `review`.
- 2026-05-03: Applied the approved review-decision batch, reran the full backend suite, and kept only non-blocking follow-ups open before re-review.

## Story Creation Audit

- 2026-05-02: Created comprehensive story context for story 1.11 and marked it `ready-for-dev`.
- 2026-05-02: Surfaced that the current `application` layer still imports JPA entities/repositories directly, so boundary refactoring is part of this story's real scope.
- 2026-05-02: Pinned the ArchUnit stack to the official JUnit 5 convenience artifact and library APIs (`@AnalyzeClasses`, `@ArchTest`, `layeredArchitecture`, `slices`).
- 2026-05-02: Added explicit guidance for narrow redaction-fence detection and future-ready artifact/export/recovery rule scaffolding.
- 2026-05-02: Validated against `bmad-create-story:validate` checklist; applied 16 improvements (6 critical, 5 enhancements, 3 optimizations, 2 LLM-readability) — see sibling `.validation-report.md`.
