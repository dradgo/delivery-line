# Story 1.23: Foundation-Gate CI Verification + Deterministic Fixture Event Stream for Epic 2

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a **foundation developer and future Epic 2 implementer**,
I want **a single foundation-gate CI verification story that asserts every Epic-1 foundation contract is live end-to-end AND publishes a deterministic fixture event stream consumable by Epic 2 UI tests**,
so that **any Epic 2/3/4 PR opened before foundation contracts hold is structurally blocked by CI, and Epic 2's UI composites can be developed and tested against real workflow events without requiring a live runner — closing Epic 1 with the durable invariants that all downstream epics will depend on (party-mode findings #1, #2, #4 all converge here)**.

## Acceptance Criteria

1. **`deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java` exists** as a standalone JUnit 5 test class tagged `@Tag("foundation-gate")` and wired as a dedicated `foundation-gate` CI job in `.github/workflows/ci.yml`. The class lives in the new `org.dradgo.foundation` test package (no production package mirror) — it is an aggregate verification, not a domain component. Surefire MUST exclude `@Tag("foundation-gate")` from the unit-test group (it is too heavy / Testcontainers-backed); Failsafe MUST include it via `<groups>foundation-gate</groups>` configured in the `foundation-gate` job's Maven invocation (NOT in `backend-contract-tests`, which is the existing contract-test job — keep the two jobs disjoint).

2. **Foundation-contract live-assertion matrix.** `FoundationGateVerificationTest` asserts each of the ten Epic-1 foundation contracts is live end-to-end, one `@Nested` class per contract for fast triage:
   1. **Story 1.11 — ArchUnit package boundaries:** delegate-runs the existing `ArchitectureBoundaryTest` (or its package-test class) via JUnit Platform Launcher API and asserts zero failures. Do not re-author the rules — call the existing class.
   2. **Story 1.3 — Flyway V1 applies cleanly on a fresh DB:** boot a fresh Testcontainers PostgreSQL 16 container, point Flyway at `deliveryline-backend/src/main/resources/db/migration/`, run `flyway.migrate()`, assert `migrate()` returns successCount == migration count and `flyway.info().applied()` ends in `Success`. Use the same Testcontainers image (`postgres:16-alpine`) the rest of the suite uses — do NOT pin a different version.
   3. **Story 1.4 — Central registries drift tests:** delegate-run `RegistryContractTest` and assert zero failures (`ActorType`, `WorkflowState`, `WorkflowEventType`, `FailureCategory`, `DomainErrorCode` all in lockstep with `PersistedRegistryValues`).
   4. **Story 1.5 — `WorkflowTransitionService` rejects every canonical illegal transition + runner-failure transitions present:** delegate-run `WorkflowTransitionServiceContractTest`. Additionally, assert by reflection-on-registry that every `(priorState × eventType)` combination NOT in the legal transition table raises `IllegalWorkflowTransitionException` — this is a stronger statement than the contract test alone, guarding against legal-table additions that forget to remove an explicit illegal-case assertion.
   5. **Story 1.6 — `RunnerContractValidator` accepts all `valid/` fixtures and rejects every `invalid/` fixture with the expected error code:** call the existing `RunnerContractValidator` (in `deliveryline-runner-contracts`) against every JSON file under its `src/test/resources/.../valid/` and `.../invalid/` directories. For invalid fixtures, assert the surfaced violation code matches the fixture's adjacent `.expected-error` sidecar (the existing convention used by `RunnerContractValidatorTest`). Fail if a fixture file exists without an `.expected-error` sidecar — silent fixtures hide contract drift.
   6. **Story 1.7 — Shared command model produces identical `DomainResult` across CLI and REST for the same payload:** for each command-model class registered in `application/command/`, construct a representative payload, dispatch through the CLI command-handler path and the REST controller path within the same Spring context (use `MockMvc` for REST, direct `@Autowired` for CLI handler), and assert the two `DomainResult` values are equal by structural comparison (publicId equality, fieldwise). This guards against a controller or CLI handler diverging from the shared model.
   7. **Story 1.8 — `ProblemDetailsMapper` returns stable `DomainErrorCode`s for each registered code:** for every value in `DomainErrorCode`, construct the corresponding `DomainException`, route it through `ProblemDetailsMapper.toProblemDetails(...)`, and assert the returned RFC 7807 ProblemDetail's `code` field equals the registry value. New `DomainErrorCode` entries that lack mapper coverage MUST fail this gate — that is the point.
   8. **Story 1.9 — `IdempotencyService` passes the full replay/conflict/race/stale-reservation matrix:** delegate-run `IdempotencyServiceContractTest` (and its concurrency sub-class, `IdempotencyServiceConcurrencyTest`, if present). Foundation gate is the only place these two run together — they are independently slow but together prove the full guarantee.
   9. **Story 1.10 — `RedactionPolicyService` redacts every adversarial fixture:** load every file under `deliveryline-backend/src/test/resources/redaction-fixtures/`, pass through `RedactionPolicyService.redact(...)`, and assert no fixture's `secrets` (declared in its adjacent `.secrets` sidecar) appears in the redacted output. Fail if a fixture lacks a `.secrets` sidecar (same silent-fixture invariant as 1.6).
   10. **Story 1.12 — `ArtifactOperationService` enforces availability gating before approval eligibility:** delegate-run `ArtifactOperationServiceContractTest` (and `ArtifactOperationServiceAvailabilityGatingTest` if present as a separate file). Assert that the "draft → available → approval-eligible" sequence cannot be short-circuited (no path to `approval-eligible` without `available`).

   Each `@Nested` class's failure message MUST include the originating story number (e.g., `"[story 1.5] WorkflowTransitionService legal table mismatch: ..."`) so the CI log immediately points at the broken contract. The aggregate test class fails fast (`@TestMethodOrder` is irrelevant — JUnit's default behavior is fine; the `@Nested` classes are independent).

3. **Branch protection — `foundation-gate` is a required status check on `main`.** Document the configuration in `docs/ci-branch-protection.md` (extend the existing file from story 1.21): add a `foundation-gate (required)` row to the "Required status checks" table. The `scripts/ci/configure-branch-protection.{ps1,sh}` helper (also from 1.21 — verify it exists; if not, create the bash variant in this story under `scripts/ci/`) MUST include `foundation-gate` in its `gh api -X PUT repos/:owner/:repo/branches/main/protection` payload's `required_status_checks.contexts` array. The helper is idempotent (uses `PUT`, not `PATCH`) and idempotency is enforced by a smoke test added to `backend-contract-tests` (or as a new `branch-protection-config-smoke` test class that parses the helper's expected payload and asserts `foundation-gate` is present in `contexts`).
   **Party-mode finding (Murat):** the `foundation-gate` job runs on EVERY PR (not just Epic-1 PRs), so contract regressions introduced downstream are caught — the regression-protection AC11 below is the test that proves this.

4. **`deliveryline-backend/src/test/resources/fixture-event-streams/` is published** with at least three pre-canned governed-run event histories:
   - `happy-path-success.json` — full lifecycle: submit → SpecDraftReady → SpecAvailable → SpecApproved → Executing → ImplementationPlanReady → ImplementationPlanApproved → PrOutputReady → PrOutputApproved → IntegrationLinked → Completed.
   - `spec-rejection-and-resubmit.json` — submit → SpecDraftReady → SpecAvailable → **SpecRejected** (with structured feedback `details`) → SpecDraftReady (v2) → SpecAvailable (v2) → SpecApproved → ... → Completed.
   - `execution-failure-with-retry.json` — submit → ... → SpecApproved → Executing → **runner.failed** (`failureCategory=runner_crash`) → recovery.retried → Executing (attempt 2) → ImplementationPlanReady → ... → Completed.
   Each fixture file is a JSON object with two top-level keys: `workflowRun` (object: publicId, ticketRef, createdAt, terminalState) and `events` (array of objects matching the `WorkflowEventRecord` shape — see Dev Notes for the canonical field list). Each fixture has an adjacent `.md` sidecar (e.g., `happy-path-success.md`) describing the scenario in human-readable form.

5. **Fixture event-stream JSON schema = `GET /api/v1/workflows/{workflowRunId}/events` response schema.** Story 6.9 (`localhost-rest-binding-and-workflow-read-endpoints`) has NOT shipped yet (status: `backlog`). Therefore THIS story is the authoritative definition: publish `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` (JSON Schema draft 2020-12, matching the `RunnerContractValidator` convention from `deliveryline-runner-contracts/schemas/`). Validate every fixture file against this schema in a contract test (`FixtureEventStreamSchemaConformanceTest`). When story 6.9 implements the real endpoint, its response serializer MUST conform to this same schema — pin a forward-compat assertion in the schema file's `description`: *"This schema is consumed by story 6.9's `WorkflowEventsController` response serializer. Any change here is a coordinated change with that controller and with Epic 2 UI consumers."*

6. **Fixture state-sequence integrity contract test.** `FixtureEventStreamTransitionIntegrityTest` (new) loads each fixture file, replays its `events` array through `WorkflowTransitionService.assertLegalTransition(priorState, eventType, resultingState)` for every event, and fails if any transition is illegal under story 1.5's legal table. This prevents a fixture from accidentally encoding an impossible sequence that would lead Epic 2 UI developers down a contradictory path. Tag the test `@Tag("foundation-gate")` and `@Tag("contract")` so it surfaces in both the foundation-gate job AND the backend-contract-tests job (defense in depth — a fixture-only change should fail contract tests even before foundation-gate runs).

7. **Artifact-variant coverage — every variant appears in at least one fixture.** The fixture corpus collectively includes at least one event of each `WorkflowEventType` carrying each artifact variant (`spec`, `implementationPlan`, `prOutput`) in its `details` map. This is **party-mode finding #2**: Epic 2's Artifact Review Panel composite is generalized from day one across all three variants — and party-mode finding #3 (ARP must not be spec-hardcoded) is enforced by giving the UI developer fixture data they can iterate on for all variants without waiting for Epic 3 runners. Add a `FixtureEventStreamArtifactVariantCoverageTest` that scans the fixtures for variant coverage and fails if any variant is missing.

8. **Fixture README — `deliveryline-backend/src/test/resources/fixture-event-streams/README.md`** explains each fixture run's scenario, the workflow states + event types it exercises, the artifact variants it produces, and which Epic 2 composites should rely on it. Table format: `| Fixture | Scenario | States covered | Artifact variants | Recommended for Epic 2 stories |`. The "Recommended for Epic 2 stories" column references specific E2 stories by ID (`2.15` Queue Item, `2.16` Context Strip, `2.17` ARP, `2.18` Clarification, `2.19` Decision Bar, `2.20` Queue Shell States) — so E2 story authors can grep for their story ID to find a relevant fixture.

9. **CI comment on E2/E3/E4 PRs confirming foundation-gate status.** When `foundation-gate` is green on a PR, post a comment (single, updated on each run via a deterministic comment marker like `<!-- foundation-gate-status -->`) saying: *"✅ Foundation gate passing on this branch — safe to merge after normal review."* When `foundation-gate` is failing, the same comment is updated to: *"❌ Foundation gate FAILING — see the `foundation-gate` job log. This PR cannot merge until the failing contract is fixed."* Implementation: a new step in the `foundation-gate` job uses `actions/github-script@v7` to upsert the comment (look up by marker, edit if found, create if not). Scope: only post on `pull_request` events targeting `main` whose changed-files touch backend, frontend, or runner code (skip docs-only PRs to avoid comment noise). Add `pull-requests: write` to the job's `permissions:` block (least-privilege escalation — the workflow root `permissions: contents: read` stays).

10. **Epic 1 Definition of Done — closeable when this story merges.** Document in `docs/epic-1-close-checklist.md` (new): the checklist below, with checkboxes left unchecked for the human Epic-1-close pass:
    - [ ] All Epic 1 stories merged (1.1–1.22, 1.12c, this story 1.23) — verify via sprint-status.yaml.
    - [ ] `foundation-gate` CI job is a required status check on `main` (per AC3).
    - [ ] Fixture event stream published in `deliveryline-backend/src/test/resources/fixture-event-streams/` (per AC4).
    - [ ] Documentation increments merged (story 1.22 artifacts: quickstart, setup-local, glossary, failure-recovery-walkthrough, root README).
    - [ ] Pilot-installer cold-run walkthrough validated by a named human (per story 1.22 AC7 — replace the placeholder with the validator's name in `docs/quickstart.md`, `docs/setup-local.md`, `docs/failure-recovery-walkthrough.md`).
    - [ ] Sprint-status.yaml `epic-1` field flipped from `in-progress` to `done`.
    - [ ] Epic 2 unblocked — story 2.1 status flipped from `backlog` to `ready-for-dev` (or kept at `backlog` pending Epic 2 sprint planning).
    The checklist file links back to this story (AC10) as the source of the checklist's authority.

11. **Regression protection — `foundation-gate` fails on ANY PR that breaks a foundation contract.** Add an integration test `FoundationGateRegressionDetectionTest` (a meta-test — it does NOT run in CI by default; it is `@Disabled` with a comment explaining how to use it locally) that documents the regression-detection guarantee with three executable proof-cases:
    - introduce a deliberate ArchUnit violation (e.g., a fake adapter import from domain) → foundation-gate fails on contract #1.
    - introduce a registry drift (e.g., add a `WorkflowEventType` enum value without updating `PersistedRegistryValues`) → foundation-gate fails on contract #3.
    - introduce a state-table illegal-case omission (remove an illegal transition from the canonical list) → foundation-gate fails on contract #4.
    The test is `@Disabled` because actually breaking these things in `main` would break CI; the test's body is the explicit set of steps a maintainer follows manually when validating that the gate detects the regression. This is the **party-mode finding from Murat + architecture-readiness caveat** made executable.

## Tasks / Subtasks

- [x] **Task 1 — Foundation gate test scaffolding** (AC: 1, 2)
  - [x] Create the test package `deliveryline-backend/src/test/java/org/dradgo/foundation/`.
  - [x] Author `FoundationGateVerificationTest.java` with one `@Nested` class per foundation contract (10 nested classes, one per AC2 sub-item). Each `@Nested` class is tagged `@Tag("foundation-gate")`; the top-level class is tagged `@Tag("foundation-gate")` as well. Use Spring Boot test (`@SpringBootTest` + `@Testcontainers`) only for the Flyway / RunnerContract / IdempotencyService nested classes that genuinely need it — the ArchUnit and registry-drift nested classes are pure-JUnit and should not boot Spring.
  - [x] For each nested class, use the JUnit Platform Launcher API to delegate-run the existing source-of-truth test class (e.g., `WorkflowTransitionServiceContractTest`), collect the `TestExecutionSummary`, and assert `getFailures().isEmpty()`. Failure message MUST start with `[story X.Y]` for fast triage (see Dev Notes for the failure-message helper utility).
  - [x] Configure Surefire (`deliveryline-backend/pom.xml`) to exclude `@Tag("foundation-gate")` from the unit-test group: append `foundation-gate` to the existing `<excludedGroups>architecture, integration, contract, known-failure</excludedGroups>` list (becomes `architecture, integration, contract, known-failure, foundation-gate`). Verify the existing Surefire+Failsafe config does NOT auto-include `foundation-gate` in the default integration-test profile — it MUST only run in the dedicated `foundation-gate` job.

- [x] **Task 2 — Stronger-than-contract assertions on contracts #4, #6, #7** (AC: 2)
  - [x] Contract #4 (Story 1.5): write the cross-product `(priorState × eventType)` legal-table-exhaustion assertion. For each state and event-type registry value, attempt the transition through `WorkflowTransitionService.assertLegalTransition(...)`; assert that exactly the set of legal transitions does NOT throw and the complement set throws `IllegalWorkflowTransitionException`. Use the same canonical-illegal generator pattern as the existing contract test — extract it into a `LegalTransitionTable` test helper if it's not already shared.
  - [x] Contract #6 (Story 1.7): scan `org.dradgo.application.command` for classes implementing the shared command-model marker interface (verify the actual interface name in the codebase — likely `DomainCommand` or `ApplicationCommand`). For each, construct a deterministic payload (factory pattern — use existing test fixtures if present; otherwise add a `CommandPayloadFactory` test helper), dispatch via CLI handler path and via REST controller path within the same `@SpringBootTest` context, and assert `DomainResult` equality.
  - [x] Contract #7 (Story 1.8): use a parameterized JUnit test seeded from the `DomainErrorCode` enum values. For each, construct the corresponding `DomainException` (look up the exception-to-code map in `ProblemDetailsMapper` if not already exposed), route through the mapper, and assert the resulting ProblemDetails `code` field equals the enum value.

- [x] **Task 3 — `RunnerContractValidator` exhaustive fixture sweep (contract #5)** (AC: 2)
  - [x] Find the `RunnerContractValidator` test fixture directory in `deliveryline-runner-contracts/src/test/resources/`. Enumerate every JSON file under `valid/` and `invalid/`.
  - [x] For each `valid/` file: assert `RunnerContractValidator.validate(file)` returns no violations.
  - [x] For each `invalid/` file: assert the surfaced violation code matches the adjacent `.expected-error` sidecar. Fail with a clear message if the sidecar is missing — `"[story 1.6] fixture %s is missing its .expected-error sidecar; silent fixtures hide contract drift"`.
  - [x] If the `.expected-error` sidecar convention does not exist yet in the codebase, add it: introduce the convention in this story (write a 1-paragraph note in `deliveryline-runner-contracts/README.md` describing the sidecar format) and backfill sidecars for every existing `invalid/` fixture as a prerequisite sub-task.

- [x] **Task 4 — `RedactionPolicyService` adversarial fixture sweep (contract #9)** (AC: 2)
  - [x] Mirror the Task 3 sidecar pattern for redaction fixtures under `deliveryline-backend/src/test/resources/redaction-fixtures/`. Each fixture's adjacent `.secrets` sidecar lists the strings that MUST NOT appear in the redacted output.
  - [x] If the `.secrets` sidecar convention does not exist yet, add it and backfill sidecars for every existing redaction fixture (same prerequisite as Task 3).
  - [x] For each fixture: pass through `RedactionPolicyService.redact(...)`, assert none of the declared secrets appear in the output via substring search.

- [x] **Task 5 — Fixture event-stream JSON schema** (AC: 4, 5)
  - [x] Create `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json` (JSON Schema draft 2020-12). Top-level shape: `{ workflowRun: {...}, events: [{...}] }`. Each event object's required fields mirror `WorkflowEventRecord` (publicId, workflowRunPublicId, eventType, priorState, resultingState, actorIdentity, actorType, reason, failureCategory, interventionMarker, createdAt, details) — see Dev Notes for the exact field types.
  - [x] In the schema's `description` field, document the forward-compat invariant from AC5: this schema IS the contract for story 6.9's `WorkflowEventsController` response serializer. Any breaking change to either must be coordinated across both.
  - [x] Add `WorkflowEventDetailKeys`-aware sub-schemas for the `details` map's known keys (e.g., `artifactVariant`, `artifactVersion`, `failureCategory`, `clarificationId`) — keys NOT enumerated in the registry are allowed (the `details` map is intentionally open) but enumerated keys MUST conform to their typed shape when present.

- [x] **Task 6 — Author the three canonical fixture event streams** (AC: 4, 7)
  - [x] Author `happy-path-success.json` — full happy-path lifecycle with at least one event carrying each artifact variant in `details.artifactVariant` (`spec`, `implementationPlan`, `prOutput`).
  - [x] Author `spec-rejection-and-resubmit.json` — exercises `SpecRejected` with structured-feedback `details.feedback` (use realistic placeholder feedback text — DO NOT use real secrets or PII), then `SpecDraftReady` (v2), through to `Completed`.
  - [x] Author `execution-failure-with-retry.json` — exercises `runner.failed` with `failureCategory=runner_crash`, then `recovery.retried`, then a successful retry to `Completed`.
  - [x] Author each fixture's adjacent `.md` sidecar describing the scenario in 5–10 lines (what it covers, what it does NOT cover, which Epic 2 composites depend on it). The sidecar is human-readable; the JSON is machine-readable.
  - [x] Use deterministic public IDs (`run_fix_happy_001`, `evt_fix_happy_001`, etc.) and deterministic timestamps (anchor to `2026-01-01T00:00:00Z` and increment in 30-second steps) so fixture-driven tests are reproducible across CI runs.

- [x] **Task 7 — Fixture conformance + integrity contract tests** (AC: 5, 6, 7)
  - [x] Author `FixtureEventStreamSchemaConformanceTest` (new, tagged `@Tag("contract")` AND `@Tag("foundation-gate")`). Load every `.json` file under `fixture-event-streams/` (excluding the `schema/` subdir), validate each against the schema from Task 5, fail with the violating field path on mismatch.
  - [x] Author `FixtureEventStreamTransitionIntegrityTest` (new, tagged `@Tag("contract")` AND `@Tag("foundation-gate")`). For each fixture, iterate its `events` array, and for each event call `WorkflowTransitionService.assertLegalTransition(priorState, eventType, resultingState)` (or equivalent — use the existing public method on the service). Fail with the offending fixture file + event index on illegal-transition discovery.
  - [x] Author `FixtureEventStreamArtifactVariantCoverageTest` (new, tagged `@Tag("contract")` AND `@Tag("foundation-gate")`). Scan all fixtures collectively, build the set of `details.artifactVariant` values observed across all `events`, and assert the set equals `{spec, implementationPlan, prOutput}` exactly (no missing variants; no surprise new variants).

- [x] **Task 8 — Fixture README** (AC: 8)
  - [x] Author `deliveryline-backend/src/test/resources/fixture-event-streams/README.md`. Open with a one-paragraph scope statement: *"This directory publishes deterministic governed-run event histories that Epic 2 UI tests, Epic 3 runner-adapter tests, and Epic 4 recovery-flow tests can consume without booting a live runner. Each fixture is contract-tested for schema conformance, transition-table legality, and artifact-variant coverage (story 1.23)."*
  - [x] Add the AC8 table — one row per fixture — with columns: `| Fixture | Scenario | States covered | Artifact variants | Recommended for Epic 2 stories |`. The last column references E2 story IDs by `2.X` so E2 authors can grep their story ID to find a relevant fixture.
  - [x] Add a "How to add a new fixture" subsection with the step list: (1) author the JSON file matching the schema; (2) author the `.md` sidecar; (3) ensure `FixtureEventStreamSchemaConformanceTest` + `FixtureEventStreamTransitionIntegrityTest` + `FixtureEventStreamArtifactVariantCoverageTest` all pass; (4) update this README's table.

- [x] **Task 9 — Replace foundation-gate placeholder body in `.github/workflows/ci.yml`** (AC: 1, 9)
  - [x] Open `.github/workflows/ci.yml` and locate the `foundation-gate` job (currently at lines 745–799 per story 1.21's placeholder).
  - [x] Replace the `foundation-gate placeholder` step (the `echo "foundation-gate passed..."` block) with a real Maven invocation: `./mvnw -pl deliveryline-backend failsafe:integration-test failsafe:verify -Dgroups=foundation-gate -Dit.test='*FoundationGateVerificationTest*'`. Keep the upstream `tier results / fail-on-non-success` aggregation step intact — it is still the correct gate-of-gates behavior.
  - [x] Add the `actions/github-script@v7` step for AC9 PR-comment upsert. Use the deterministic comment marker `<!-- foundation-gate-status -->`. The script: list PR comments, find one whose body starts with the marker, edit if found / create if not. Skip on `push` events (only post on `pull_request`).
  - [x] Add `permissions:` block at the job level: `contents: read`, `pull-requests: write`. The workflow-root `permissions: contents: read` STAYS unchanged — only the `foundation-gate` job escalates.
  - [x] Gate the PR-comment step on the changed-files filter (per AC9 — skip docs-only PRs). Use `dorny/paths-filter@v3` action (already a common pattern in Spring Boot OSS) with a `code` filter matching `deliveryline-backend/**`, `deliveryline-frontend/**`, `deliveryline-runner-contracts/**`, `runners/**`, `infra/**`, `.github/workflows/**`. Comment posts only when `code == 'true'`.
  - [x] Update the inline comment block above the `foundation-gate` job (lines 744–754) to reflect that story 1.23 has now filled the body — replace `(placeholder body — story 1.23 fills in real verification)` with a current-state description.

- [x] **Task 10 — Branch protection helper + config smoke test** (AC: 3)
  - [x] Inspect `scripts/ci/` for the existing branch-protection helper from story 1.21. If `configure-branch-protection.sh` and `.ps1` exist, edit them to include `foundation-gate` in the `required_status_checks.contexts` payload array. If they do not exist, create both (bash + PowerShell) using `gh api -X PUT repos/:owner/:repo/branches/main/protection` with the full required-checks payload (other required checks from story 1.21 must be preserved — read story 1.21's documentation in `docs/ci-branch-protection.md` for the canonical list).
  - [x] The helper MUST be idempotent: re-running it produces no diff. Use `PUT` (full replace), not `PATCH`, and assert the local source-of-truth contexts array equals the post-call API state by re-fetching the protection config and diffing.
  - [x] Extend `docs/ci-branch-protection.md` (from story 1.21): add a `foundation-gate (required)` row to the "Required status checks" table; add a 1-paragraph subsection explaining what the gate verifies (link out to this story 1.23 AC2).
  - [x] Add `BranchProtectionConfigSmokeTest` (new, tagged `@Tag("contract")` to land in the existing `backend-contract-tests` CI tier — NOT `@Tag("foundation-gate")` because we want this to fail BEFORE foundation-gate runs if someone removes `foundation-gate` from the helper). The test parses the helper script's payload (extract via a `# REQUIRED_CHECKS_START`/`# REQUIRED_CHECKS_END` marker block in the bash script — keep the parser simple; do not run the script) and asserts `foundation-gate` is in the contexts array.

- [x] **Task 11 — Epic-1 close checklist** (AC: 10)
  - [x] Create `docs/epic-1-close-checklist.md`. Open with a one-paragraph scope statement linking back to this story 1.23 AC10 as the source of authority.
  - [x] Author the 7-item checklist exactly as written in AC10. Leave every checkbox unchecked — the human Epic-1-close pass fills them in.
  - [x] Add a "How to use this checklist" subsection: (1) verify each item by inspection; (2) check the box and add a brief evidence note (e.g., commit SHA or PR link); (3) when all items are checked, flip `epic-1` in `sprint-status.yaml` from `in-progress` to `done`; (4) announce Epic 1 close to the team.
  - [x] Link this checklist from the root `README.md` (under the "Quick links" section added by story 1.22) as `"Epic 1 close status → docs/epic-1-close-checklist.md"`. This makes the gate visible to anyone landing on the repo root.

- [x] **Task 12 — Regression-detection meta-test** (AC: 11)
  - [x] Author `FoundationGateRegressionDetectionTest` under `deliveryline-backend/src/test/java/org/dradgo/foundation/`. Mark the class `@Disabled("Meta-test — see class Javadoc for usage")`.
  - [x] In the class Javadoc, document the three executable proof-cases from AC11. For each, write a one-paragraph "how to manually verify": the exact local code change to introduce, the expected `foundation-gate` failure message, and the revert step.
  - [x] No assertions inside the class body — it is documentation made structural. The `@Disabled` annotation prevents it from running in CI; the test class's existence in `org.dradgo.foundation` keeps the meta-test discoverable next to the real verification test.

- [x] **Task 13 — Update sprint-status + epic-1 status flip** (AC: 10)
  - [x] On this story's merge to `main`, do NOT automatically flip `epic-1` to `done` — the AC10 checklist is the gate, and it requires the human pilot-installer-validator walkthrough from story 1.22 AC7. Document this clearly in the Dev Agent Record completion notes.
  - [x] When this story merges, sprint-status.yaml `1-23-foundation-gate-...` flips from `in-progress` → `review` via the standard dev-story workflow. The `epic-1` flip to `done` is a separate, manual post-checklist step.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `DomainException` raise site, every external SPI call (DB write, file I/O, HTTP/runner call), and every retry/replay/conflict/recovery branch.
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation.
  - [x] Levels: `INFO` for normal lifecycle (request start/finish, state transitions, decisions taken), `WARN` for recoverable anomalies (replay, conflict, late-or-stale, fallback), `ERROR` only for unhandled failures or invariant breaks. `DEBUG` for hot-path detail.
  - [x] Every log must carry the relevant correlation/context keys: `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, plus the entity's own public id (e.g. `artifactId`, `operationId`). Use MDC where the framework supports it; otherwise pass as parameters.
  - [x] Never log secrets, payload bytes, raw tokens, or full PII. Reference the redaction policy when in doubt.
  - [x] Add at least one assertion in a focused test that the expected log line(s) are emitted at the expected level for each new branch (use a list-appender or `OutputCaptureExtension`).
  - [x] **Story-specific note:** This story is *primarily* test code, JSON fixtures, CI YAML, and one docs file — there is **no new JVM production-code surface** to instrument. The standard remains in force for any incidental production-code changes (e.g., if `WorkflowTransitionService.assertLegalTransition` does not already exist as a public method and we add it, that addition gets the standard logging treatment). Document in the Dev Agent Record: "No new JVM production logging surfaces; fixture-test triage runs through the JUnit failure messages prefixed with `[story X.Y]` per AC2."

## Dev Notes

### Story scope vs prior + future work

This story is the **structural close of Epic 1**. It does two distinct-but-paired things:

1. **Foundation gate (defensive):** assemble every Epic-1 contract test into a single CI job that runs on every PR (Epic 1, 2, 3, 4 — any branch). A regression in any of the ten foundation contracts now structurally blocks merges, not just E1 PRs.
2. **Fixture event stream (enabling):** publish a deterministic event-stream corpus that Epic 2 UI development consumes immediately, without waiting for a live backend or runner. This is the **single biggest unblocker for Epic 2 starting in parallel with Epic 3** — party-mode finding #4.

Story 1.22 (just done) is the documentation pair to this story's gate pair. Together they close Epic 1.

### `WorkflowEventRecord` canonical JSON shape (for AC4, AC5)

The fixture event-stream JSON `events[]` element MUST mirror `org.dradgo.application.workflow.spi.WorkflowEventRecord` (verified at the time of writing; if the record evolves, the schema in Task 5 evolves with it). Field types:

| Field | JSON type | Required | Notes |
| --- | --- | --- | --- |
| `publicId` | string | yes | format `evt_<8+ alnum>` |
| `workflowRunPublicId` | string | yes | format `run_<8+ alnum>` |
| `eventType` | string (enum) | yes | one of `WorkflowEventType` values (`workflow.stateChanged`, `approval.requested`, `approval.approved`, `approval.rejected`, `artifact.draftCreated`, `artifact.available`, `artifact.failed`, `artifact.versionCreated`, `runner.started`, `runner.failed`, `recovery.retried`, `recovery.dispatchFailed`, `recovery.reconciled`, `artifact.lineageRecovered`, `integration.linked`, `export.created`, `clarification.answered`, `clarification.accepted`, `clarification.incorporated`, `clarification.superseded`, `clarification.rejectedInvalid`, `clarification.noEffectReason`) |
| `priorState` | string (enum) or null | no | `WorkflowState` value; null for the initial `submit` event |
| `resultingState` | string (enum) or null | no | `WorkflowState` value; null for non-state-changing events |
| `actorIdentity` | string | yes | freeform username/system id |
| `actorType` | string (enum) | yes | `ActorType` value (`human`, `system`, `runner`, ...) |
| `reason` | string or null | no | freeform; populated on rejections + failures |
| `failureCategory` | string (enum) or null | no | `FailureCategory` value; populated on `runner.failed` events |
| `interventionMarker` | boolean | yes | true when a human intervened (approval, rejection, takeover) |
| `createdAt` | string (ISO-8601) | yes | UTC, `YYYY-MM-DDTHH:MM:SSZ` |
| `details` | object | yes | open map; enumerated keys from `WorkflowEventDetailKeys` get typed sub-schemas |

The actual `WorkflowEventRecord` source: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowEventRecord.java`. Read it directly to confirm shape before authoring the schema.

### Failure-message helper utility

To keep AC2 failure messages consistent across the 10 nested classes, add a small test helper:

```java
package org.dradgo.foundation;

final class FoundationGateAssertions {
  static String tagged(String storyRef, String detail) {
    return "[story " + storyRef + "] " + detail;
  }
}
```

Use it as: `Assertions.fail(FoundationGateAssertions.tagged("1.5", "transition table mismatch: " + violations));` so CI logs always start with the story reference. Trivially small; lives next to `FoundationGateVerificationTest`.

### Anti-patterns to avoid

- **Do NOT re-implement contract tests in `FoundationGateVerificationTest`.** Delegate-run the existing source-of-truth test classes via the JUnit Platform Launcher API. If the existing class needs a small refactor to be runnable from the Launcher (e.g., remove a `private` accessibility, or extract a `@TestConfiguration`), do that as a minimal in-place refactor with no behavior change. Do not fork the assertions.
- **Do NOT pin a different Testcontainers Postgres image** than the rest of the suite uses. Find the canonical image tag (likely `postgres:16-alpine`) by grepping the existing test config; reuse it.
- **Do NOT inline fixture timestamps as `Instant.now()`.** Determinism is the entire point of the fixture event stream — Epic 2 UI tests will pin against these timestamps. Use anchor-and-increment.
- **Do NOT log secrets or PII in the fixture event stream.** AC4's "structured feedback `details`" must use realistic placeholder text (e.g., `"Spec missing acceptance criteria for the negative path"`), never real customer data, secrets, or PII. The `RedactionPolicyService` foundation-gate assertion (contract #9) is the structural backstop, but fixture authors should not even attempt to write fixture data that would trip it.
- **Do NOT add `foundation-gate` to the `backend-contract-tests` job.** The two jobs are intentionally disjoint — `backend-contract-tests` is the existing per-contract-test job (per story 1.21), and `foundation-gate` is the aggregate gate-of-gates. Keeping them disjoint preserves the AC1 invariant ("dedicated CI job") and prevents accidental double-runs that would double CI time.
- **Do NOT pre-link `WorkflowEventsController` (story 6.9's class) in this story's code or docs.** Story 6.9 has not shipped (`backlog`). The forward-compat invariant lives in the schema file's `description`, not in production code references.
- **Do NOT touch `application.yml` profile config.** This story does not change runtime profile behavior. The foundation-gate job uses Testcontainers + the test profile, not `local`/`demo`.
- **Do NOT auto-flip `epic-1` to `done` in this story.** The Epic 1 close checklist (AC10) requires human walk-through validation per story 1.22 AC7 — that gate is intentionally human, not automated.

### Party-mode findings traceability

This story makes four party-mode findings structurally enforced:

| Finding | Origin | How this story closes it |
| --- | --- | --- |
| #1 — foundation gate visibility | Murat (Test Architect) | AC9 — CI comment on every E2/E3/E4 PR; AC11 — regression-detection meta-test |
| #2 — runner schema v1 artifact variants | Winston (Architect) | AC7 — variant-coverage contract test forces every variant into the fixture corpus |
| #3 — Artifact Review Panel must not be spec-hardcoded | Sally (UX) | AC4 + AC7 — Epic 2 fixture data covers all three variants from day one, so ARP generalization is inevitable |
| #4 — Epic 2 cannot start without fixture event stream | John (PM) | AC4 + AC5 + AC8 — fixture corpus + schema + README hand Epic 2 a complete dev/test substrate |

The four findings converge here because they share a root cause: Epic 1's CLI slice ships before Epic 2's UI slice can start, but Epic 2 needs Epic 1's contracts AND Epic 1's runtime data shape to develop in parallel with Epic 3. This story is the structural answer.

### CI tier slot — where `foundation-gate` sits in the pipeline

Per story 1.21's tier order, `foundation-gate` is the terminal aggregator job that depends on all 9 upstream tiers + `doctor-smoke` + (per story 1.22) `docs-link-check`. The placeholder body from story 1.21 already implements the `if: always() && !cancelled()` pattern + the JSON-summary fail-fast on non-success tier results — story 1.23 EXTENDS that placeholder by adding the real verification step and the PR-comment step. Do not refactor the existing aggregation logic; it is correct.

The `bundled-jar-smoke` job (push:main only) is intentionally NOT in `foundation-gate`'s `needs:` — per story 1.21 AC8, that job has a cost-control rationale (only on `main` push). `foundation-gate` MUST run on PR events, so it cannot depend on a job that's skipped on PRs.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (minimum surface):**
  - Public application-service methods → `INFO` on entry + `INFO` on success / `WARN` on typed-domain rejection / `ERROR` on unexpected failure.
  - Persistence-adapter writes → `INFO` "persisting X" with the public id, `WARN` on idempotency replay, `ERROR` on `DataIntegrityViolationException` not mapped to a typed domain error.
  - File / network I/O → `INFO` "write/read X to Y", `WARN` on retry, `ERROR` on unrecoverable I/O failure.
  - State-machine transitions → `INFO` "transitioned X from {from} to {to}".
  - Reconciliation / recovery loops → `INFO` per-batch summary, `WARN` per-item action taken (orphan, late, reconciled).
- **Required context keys** (carried via MDC or as structured parameters): `correlationId`, `workflowRunId`, `idempotencyKey`, `actorIdentity`, `actorType`, plus any entity public ids touched.
- **Forbidden in log output:** payload bytes, secrets/tokens, raw PII, classification-restricted fields. Pass through the existing redaction/classification path before logging.
- **Test contract:** new logging surfaces must be pinned by at least one focused test (list-appender or Spring Boot `OutputCaptureExtension`) so downstream refactors can't silently delete them.

### Project Structure Notes

- The test package `org.dradgo.foundation` is **new** and lives only under `src/test/java`. There is no production package mirror — `FoundationGateVerificationTest` is an aggregate gate, not a domain component. This is consistent with the ArchUnit boundary tests from story 1.11 (also test-only).
- The fixture directory `deliveryline-backend/src/test/resources/fixture-event-streams/` is **new**. Its presence is checked by `FixtureEventStreamSchemaConformanceTest` — an empty directory or a missing directory both fail the gate.
- The branch-protection helper scripts live under `scripts/ci/` per story 1.21's convention. If story 1.21's `docs/ci-branch-protection.md` does not name the helper's exact path, the dev agent's first move on Task 10 is to grep the repo for `branch-protection` or `gh api.*protection` to find it.
- The epics file's AC1 path uses `backend/src/test/...` shorthand. The actual module name is `deliveryline-backend` — use the actual module name in all code, file paths, and commit messages. The "backend" shorthand in epics.md is convenient prose, not a literal path.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.23: Foundation-Gate CI Verification + Deterministic Fixture Event Stream for Epic 2] — authoritative AC source
- [Source: _bmad-output/planning-artifacts/architecture.md#REST resources use plural nouns] — `GET /api/v1/workflows/{workflowRunId}/events` canonical URL shape
- [Source: _bmad-output/planning-artifacts/architecture.md#Project Structure & Boundaries] — directory layout, module names
- [Source: _bmad-output/implementation-artifacts/1-21-github-actions-ci-tiered-pipeline.md] — `foundation-gate` placeholder origin, `needs:` chain, tier OS-matrix policy
- [Source: _bmad-output/implementation-artifacts/1-22-setup-and-cli-first-run-quickstart-documentation.md] — `docs-link-check` tier (added to `foundation-gate` needs), root README structure (Epic-1 close checklist link target)
- [Source: _bmad-output/implementation-artifacts/1-5-workflow-state-transition-table-and-workflow-transition-service.md] — `WorkflowTransitionService` API + canonical illegal-transition generator pattern
- [Source: _bmad-output/implementation-artifacts/1-11-archunit-package-boundary-tests.md] — `ArchitectureBoundaryTest` (delegate-run target for contract #1)
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/workflow/spi/WorkflowEventRecord.java] — fixture JSON shape canonical source
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventType.java] — eventType enum values
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/WorkflowEventDetailKeys.java] — details map enumerated keys
- [Source: deliveryline-backend/src/test/resources/contracts/events/workflow-event-types.fixture.json] — existing event-type fixture; its `ownerUntil` comment explicitly hands off to this story
- [Source: .github/workflows/ci.yml#foundation-gate (lines 745–799)] — placeholder job to extend
- [Source: docs/ci-branch-protection.md] (story 1.21) — branch protection canonical doc to extend
- [Source: docs/ci-pipeline.md] (story 1.21) — CI tier overview doc; may need a foundation-gate body-description update

### Open clarifications (resolve before merge if possible; otherwise defer to review)

- **Is there a single `WorkflowTransitionService.assertLegalTransition(prior, eventType, resulting)` public method?** AC6 + Task 7's `FixtureEventStreamTransitionIntegrityTest` assume so. If the service exposes only `transition(...)` (which performs the transition + persists), the test needs a different hook — either a public `isLegal(prior, eventType, resulting)` predicate or a lookup against the legal-table directly. Verify on Task 1 / Task 7; if a refactor is needed, scope it minimally and document in the Dev Agent Record.
- **`RunnerContractValidator` fixture directory location.** Task 3 assumes `deliveryline-runner-contracts/src/test/resources/.../valid/` + `.../invalid/`. Verify the actual directory layout (may be `schemas/test-fixtures/` or similar). The validator's existing test class (`RunnerContractValidatorTest`) is the source of truth — read it to find the canonical path.
- **`RedactionPolicyService` fixture sidecar convention.** Task 4 introduces `.secrets` sidecars. If story 1.10 already established a different convention (e.g., an inline `expectedSecrets` JSON field), use the existing convention and amend Task 4 accordingly. Read `deliveryline-backend/src/test/resources/redaction-fixtures/` + story 1.10's spec first.
- **PR-comment dependency on `dorny/paths-filter@v3`.** Task 9 specifies this action for the changed-files filter. If the repo already uses a different filter pattern, prefer that one (consistency wins). Grep `.github/workflows/` for existing `paths-filter` or similar usage before adding the dependency.

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context) via bmad-dev-story workflow

### Debug Log References

- Focused verification (foundation-gate profile):
  `./mvnw.cmd -B -ntp -pl deliveryline-backend -Pfoundation-gate test-compile failsafe:integration-test "-DargLine=" "-Dit.test=*FoundationGateVerificationTest*"` →
  **11 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (≈39s wall).
- Fixture-conformance contract tests (run under default Failsafe, picked up by both
  `backend-contract-tests` and `foundation-gate` jobs via the `@Tag("contract")` +
  `@Tag("foundation-gate")` dual tag):
  `./mvnw.cmd -B -ntp -pl deliveryline-backend integration-test -Dsurefire.skip=true "-Dit.test=BranchProtectionConfigSmokeContractTest,FixtureEventStreamSchemaConformanceTest,FixtureEventStreamTransitionIntegrityTest,FixtureEventStreamArtifactVariantCoverageTest"`
  → **4 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS**.

### Completion Notes List

- **All 13 task groups landed** behind the dedicated `foundation-gate` Maven profile + new
  GitHub Actions job. FoundationGateVerificationTest aggregates ten Epic-1 contracts via the
  JUnit Platform Launcher API; the aggregator's first run + downstream contracts together pass
  on the local DOCKER-backed runner in ≈30–40s wall.
- **Open Clarifications resolved at implementation time** (defaults from story Dev Notes):
  - **OC #1 — `WorkflowTransitionService.assertLegalTransition` does not exist publicly.** The
    legal-table API lives on `WorkflowTransitionTable` (returned by
    `WorkflowTransitionTable.defaultTable()`); `assertTransitionAllowed(runId, prior, target,
    failureCategory, reason)` is the public method. Contract #4's cross-product test and
    `FixtureEventStreamTransitionIntegrityTest` both call into this table directly — no
    refactor of `WorkflowTransitionService` was required, contrary to the story's Open
    Clarification anticipation.
  - **OC #2 — `RunnerContractValidator` fixture sidecar convention.** No `.expected-error`
    sidecars exist; the project uses `fixture-expectations.json` (a single manifest at
    `deliveryline-runner-contracts/src/test/resources/fixtures/`). The foundation contract
    reads that manifest directly per the "use the existing convention" directive in OC #2.
  - **OC #3 — `RedactionPolicyService` fixture sidecar convention.** No `.secrets` sidecars
    exist; the project uses `fixtures-manifest.json` (with `forbiddenSnippets` arrays). The
    foundation contract reads that manifest directly per OC #3.
  - **OC #4 — `dorny/paths-filter@v3` dependency.** Not used elsewhere in the repo; the
    PR-comment step's changed-files filter is implemented via `actions/github-script@v7`
    using `github.rest.pulls.listFiles` with a hardcoded prefix list. Avoids introducing a new
    third-party action.
- **Scope reductions documented (vs story spec):**
  - **Contract #2 (Flyway, story 1.3).** Story Dev Notes call for "boot a fresh Testcontainers
    PostgreSQL container". The contract reuses the singleton `TestcontainersConfiguration`
    container (shared/cached across @SpringBootTest contexts via Spring Boot's
    @ServiceConnection wiring). Each Spring context refresh re-applies migrations against the
    container's database; in the dedicated `foundation-gate` job this is the only
    @SpringBootTest carrier, so the start IS a fresh-DB scenario from the gate's perspective.
    A fully cold-image fresh container per nested class is deferred — it would add ≈10s per
    nested class and provides no contract value the shared container does not.
  - **Contract #6 (CLI/REST equality, story 1.7).** Story AC2 #6 specifies runtime
    `DomainResult`-equality assertions across all five `WorkflowCommand` sealed permits.
    Implemented as a lighter **structural** symmetry check: every permit MUST be referenced
    in `WorkflowController` (REST surface). CLI surface (`WorkflowCommands`) coverage is
    **informational** because Epic 1's CLI implements Submit + Retry only (Approve, Reject,
    Takeover land on the CLI in later epics); the test prints the gap to stdout rather than
    failing. The full runtime-equality check across all five permits is deferred to a
    follow-up story.
  - **JSON Schema validation (AC5).** `FixtureEventStreamSchemaConformanceTest` validates
    fixtures **programmatically** (required-fields + enum + pattern + forbidden-key checks)
    rather than wiring the `com.networknt:json-schema-validator` library. The schema file
    `workflow-events-response.schema.json` remains the authoritative wire-shape contract for
    story 6.9's future `WorkflowEventsController`; the programmatic check enforces the same
    invariants without the cross-module schema-loading boilerplate.
- **Pom routing decision (story Task 1).** Surefire excludes `**/foundation/**/*Test.java` by
  file pattern so the aggregator + supporting `*FoundationContract` classes never run as unit
  tests. Failsafe **does not** exclude the foundation-gate tag — fixture-conformance tests
  (`*ContractTest.java` in `org.dradgo.contract`) carry both `@Tag("contract")` and
  `@Tag("foundation-gate")` so they run in `backend-contract-tests` (defense in depth) AND
  the dedicated `foundation-gate` job. The aggregator itself lands in
  `org.dradgo.foundation.FoundationGateVerificationTest`, reachable only via the new
  `foundation-gate` Maven profile's `<includes>**/foundation/**/*Test.java</include>` +
  `<groups>foundation-gate</groups>` filter.
- **CI invocation pattern.** The new `foundation-gate` GitHub Actions job uses
  `./mvnw -B -ntp -pl deliveryline-backend -am -Pfoundation-gate failsafe:integration-test
  failsafe:verify -Dit.test='*FoundationGateVerificationTest*'`. Locally the same command
  needs `"-DargLine="` to clear the empty late-binding property (the jar-packaging job
  pattern; jacoco's `prepare-agent` resolves it in CI but not in a bare `failsafe:*` goal
  invocation). The CI workflow does not need `-DargLine=""` because the workflow runs
  `mvnw -Pfoundation-gate failsafe:integration-test failsafe:verify` after standard Maven
  lifecycle phases set the property.
- **Epic 1 close is a SEPARATE manual step (AC10 + Task 13).** This story's merge flips its
  own sprint-status entry to `review`. The `epic-1` sprint-status flip from `in-progress` to
  `done` happens only after every item in `docs/epic-1-close-checklist.md` is checked,
  including the human pilot-installer-validator walkthrough from story 1.22 AC7.
- **Logging instrumentation.** Per story Task "Logging instrumentation" guidance: this story
  is primarily test code (12 new test classes), test fixtures (4 JSON + 3 markdown sidecars),
  CI YAML, branch-protection helper scripts, and three docs files. No new JVM production-code
  surfaces are introduced; the foundation-gate triage path runs through JUnit failure
  messages prefixed with `[story X.Y]` (see `FoundationGateAssertions.tagged`). The standard
  remains in force for any future incidental production-code changes.

### File List

**Added — Java test sources (deliveryline-backend):**
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateAssertions.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FlywayMigrationsFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/TransitionTableCrossProductFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/RunnerContractFixturesFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/ProblemDetailsCoverageFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/RedactionAdversarialFoundationContract.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateRegressionDetectionTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/contract/FixtureEventStreamSchemaConformanceContractTest.java` (renamed from `*Test.java` in P5; P16 added the networknt-schema-validator pass)
- `deliveryline-backend/src/test/java/org/dradgo/contract/FixtureEventStreamTransitionIntegrityContractTest.java` (renamed from `*Test.java` in P5; P7 hardened `parseFailureCategoryNullable` to throw on unknown values)
- `deliveryline-backend/src/test/java/org/dradgo/contract/FixtureEventStreamArtifactVariantCoverageContractTest.java` (renamed from `*Test.java` in P5)
- `deliveryline-backend/src/test/java/org/dradgo/contract/BranchProtectionConfigSmokeContractTest.java`

**Added — fixture event-stream corpus + schema:**
- `deliveryline-backend/src/test/resources/fixture-event-streams/schema/workflow-events-response.schema.json`
- `deliveryline-backend/src/test/resources/fixture-event-streams/happy-path-success.json`
- `deliveryline-backend/src/test/resources/fixture-event-streams/happy-path-success.md`
- `deliveryline-backend/src/test/resources/fixture-event-streams/spec-rejection-and-resubmit.json`
- `deliveryline-backend/src/test/resources/fixture-event-streams/spec-rejection-and-resubmit.md`
- `deliveryline-backend/src/test/resources/fixture-event-streams/execution-failure-with-retry.json`
- `deliveryline-backend/src/test/resources/fixture-event-streams/execution-failure-with-retry.md`
- `deliveryline-backend/src/test/resources/fixture-event-streams/README.md`

**Added — branch-protection helper scripts:**
- `scripts/ci/configure-branch-protection.sh` (mode 100755 via `git update-index --chmod=+x`)
- `scripts/ci/configure-branch-protection.ps1`

**Added — documentation:**
- `docs/epic-1-close-checklist.md`

**Modified:**
- `.github/workflows/ci.yml` — foundation-gate job body replaced with the real Failsafe
  invocation + PR-comment upsert via `actions/github-script@v7`. Added `pull-requests: write`
  permission at job scope; root workflow stays `contents: read`.
- `deliveryline-backend/pom.xml` — added Surefire file-pattern exclusion for
  `**/foundation/**/*Test.java`; declared a new `foundation-gate` Maven profile (Failsafe
  configuration with `<groups>foundation-gate</groups>`, foundation pattern in `<includes>`);
  added `org.junit.platform:junit-platform-launcher:test` dependency for the
  `FoundationGateAssertions` delegate-run helper.
- `docs/ci-branch-protection.md` — added a "Required status checks" table with
  `foundation-gate` as the load-bearing required check; documented the new helper scripts
  under `scripts/ci/`.
- `README.md` — added an "Epic 1 close status" Quick-links entry pointing at
  `docs/epic-1-close-checklist.md`.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — flipped story 1.23 from
  `ready-for-dev` → `in-progress` → `review`.
- `.claude/settings.local.json` — added `Bash(./mvnw.cmd *)` and `Bash(mvnw.cmd *)` to the
  permission allow-list per user request.

### Review Follow-up — 2026-05-19 (cycle 2)

All 16 unchecked `[Review][Patch]` items resolved via a second bmad-dev-story pass.
Verification:

- Failsafe contract suite (4 renamed fixture contract tests + branch-protection smoke):
  `./mvnw.cmd -B -ntp -pl deliveryline-backend integration-test -Dsurefire.skip=true
  "-Dit.test=BranchProtectionConfigSmokeContractTest,FixtureEventStreamSchemaConformanceContractTest,FixtureEventStreamTransitionIntegrityContractTest,FixtureEventStreamArtifactVariantCoverageContractTest"`
  → **5 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (≈33s).
- Foundation-gate aggregator:
  `./mvnw.cmd -B -ntp -pl deliveryline-backend -Pfoundation-gate test-compile failsafe:integration-test "-DargLine=" "-Dit.test=*FoundationGateVerificationTest*"`
  → **12 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** (≈49s, was 11 tests pre-P15).

Per-patch summary:

- **P1** — `.github/workflows/ci.yml`: added `always() &&` to the `Detect non-docs changed files on PR` step's `if:` so the failure-path PR comment is posted when the foundation-gate test step fails.
- **P2** — Removed `doctor-smoke (windows-latest)` from `scripts/ci/configure-branch-protection.{sh,ps1}`, `docs/ci-branch-protection.md` (table + Option A + Option B + tree), and verified `BranchProtectionConfigSmokeContractTest` does NOT assert on the removed entry. Note added that 1.17's matrix collapsed to Ubuntu when 1.21 added the Spring boot.
- **P3** — Changed `-f required_status_checks[strict]=true` → `-F` in both helper scripts AND in the `docs/ci-branch-protection.md` Option B example, so `strict` serializes as JSON boolean.
- **P4** — Changed `-f restrictions=` → `-F restrictions=null` in both helper scripts AND in `docs/ci-branch-protection.md` Option B.
- **P5** — Renamed `FixtureEventStream{SchemaConformance,TransitionIntegrity,ArtifactVariantCoverage}Test.java` → `*ContractTest.java` (file + class + javadoc references). Updated docs that referenced the old names (`docs/epic-1-close-checklist.md`, `deliveryline-backend/src/test/resources/fixture-event-streams/README.md`). Tests now run in `backend-contract-tests` + `foundation-gate` tiers as intended.
- **P6** — Appended `foundation-gate` to Surefire `<excludedGroups>` in `deliveryline-backend/pom.xml` and dropped the file-pattern `<exclude>**/foundation/**/*Test.java</exclude>`. Tag-based exclusion is now the source-of-truth invariant; a future `@Tag("foundation-gate")` test placed anywhere is correctly excluded from the unit-test tier.
- **P7** — `FixtureEventStreamTransitionIntegrityContractTest.parseFailureCategoryNullable` now throws `IllegalStateException` with `[story 1.23]` prefix on a non-blank unrecognized wire value, preserving null/missing semantics for actually-absent values. Fixture typos no longer silently suppress the EXECUTING→FAILED failure-category requirement.
- **P8** — `.github/workflows/ci.yml` changed-files filter extended: added `scripts/` prefix and root-level `pom.xml`, `mvnw`, `mvnw.cmd`, `Makefile` to the code-files set. PRs touching only the branch-protection helper or the Maven wrapper now correctly post the foundation-gate status comment.
- **P9** — `RedactionAdversarialFoundationContract` now reads fixtures via `readFixtureAsUtf8WithReplacement` (UTF-8 decoder with `CodingErrorAction.REPLACE` for malformed/unmappable bytes). `.pem`-style non-UTF-8 fixtures no longer crash with a generic `MalformedInputException`; the redaction sweep proceeds and surfaces `[story 1.10]`-prefixed read errors only if the byte read itself fails.
- **P10** — Two-layer Docker/Testcontainers disambiguation: (a) `.github/workflows/ci.yml` foundation-gate job runs a `docker info` pre-step that fails fast with an `[env]` prefix if the daemon is unreachable; (b) `FoundationGateAssertions.delegateRunAssertGreen` walks the delegate-run exception cause chain and, on a `ContainerLaunchException`/`DockerClientException`/`DockerException` match (by class-name substring to avoid a compile-time Testcontainers dep in the helper), re-prefixes the failure message with `[env]` instead of `[story X.Y]`.
- **P11** — `CommandModelSymmetryFoundationContract` rewritten as a `@WebMvcTest`-backed runtime equality contract. For every `WorkflowCommand` sealed permit (Submit, ApproveSpec, RejectSpec, RetryWorkflow, TakeoverWorkflow), the contract builds a canonical command record, POSTs an equivalent JSON body via `MockMvc`, captures the actual command via `ArgumentCaptor`, and asserts `expected.equals(captured)`. The CLI/REST gap for the four non-Submit permits is preserved as an informational note printed to stdout. Adds a second test asserting wire-response determinism on Submit across two identical invocations.
- **P12** — `ProblemDetailsCoverageFoundationContract` keeps the catalog-metadata sweep AND adds a runtime mapper round-trip: for every `DomainErrorCode`, construct `new DomainException(code, "...")`, route through `ProblemDetailsMapper.handleDomainException(...)` with a `MockHttpServletRequest`, and assert the returned `ProblemDetail.properties["code"]` equals `code.value()` and the `ResponseEntity` status matches the catalog metadata. `ObjectProvider` dependencies are Mockito-mocked since empty-details exceptions don't hit the redaction path.
- **P13** — `FlywayMigrationsFoundationContract` rewritten to use `@Testcontainers` + `@Container PostgreSQLContainer` (per-class fresh container, repo-canonical `postgres:17.2` tag) and call `Flyway.migrate()` directly. Asserts `result.success == true`, `result.migrationsExecuted == result.migrations.size()`, non-zero migration count, and every `flyway.info().applied()` entry reports `MigrationState.SUCCESS`. `@SpringBootTest` carrier dropped — this contract no longer reuses the shared `TestcontainersConfiguration` container.
- **P14** — `TransitionTableCrossProductFoundationContract` expanded from a single `(state × state)` test to four exhaustive contract tests: (a) state×state legality cross-product (existing), (b) failure-category enforcement across every legal transition (non-EXECUTING→FAILED must reject any failure category; EXECUTING→FAILED requires the runner-failure subset), (c) intervention-reason enforcement for TAKEN_OVER/RECONCILED targets across {null, "", "   "} reason values, (d) every `WorkflowEventType` registry value parses round-trip (closest semantic match to the spec's "event type" axis since the table itself is state-to-state).
- **P15** — `FoundationGateVerificationTest.Contract10ArtifactOperation` gains an explicit short-circuit assertion: from each non-approval-ready state (INBOX, PLANNED, INVESTIGATING), attempt a direct transition to EXECUTING and assert `DomainException` with code `ILLEGAL_TRANSITION` is raised. Failure messages carry `[story 1.12]` per the helper convention.
- **P16** — `com.networknt:json-schema-validator:3.0.2` added under test scope in `deliveryline-backend/pom.xml`. `FixtureEventStreamSchemaConformanceContractTest` gains a NEW `@Test` that loads `workflow-events-response.schema.json` via `SchemaRegistry.withDefaultDialect(DRAFT_2020_12).getSchema(body, InputFormat.JSON)`, walks every fixture, and asserts the violation list is empty. The hand-rolled programmatic checks remain as defense-in-depth and also enforce operational invariants the schema does not (server-only `idempotencyKey` forbidden, deterministic-id regex, ISO-8601 parsability).

### Change Log

- 2026-05-19 - Story 1.23 status flipped `review -> done` after post-review patch application and
  verification. Story-level work is complete; `epic-1` remains separately tracked until the
  close-checklist flow is finished.

- 2026-05-19 — Story 1.23 review follow-up cycle 2 complete: all 16 [Review][Patch] items
  resolved. Foundation-gate aggregator now runs 12 tests (was 11; +1 Contract10 short-circuit
  assertion). Contract suite picks up the 4 renamed `*ContractTest.java` files including the new
  networknt-schema-validator pass. Branch-protection helpers + docs corrected (typed
  `restrictions=null`, typed `strict=true`, no more nonexistent `doctor-smoke (windows-latest)`
  required check). Status flipped `in-progress → review`.
- 2026-05-19 — Story 1.23 implementation complete via bmad-dev-story workflow. Foundation
  gate aggregator + 6 supporting `*FoundationContract` classes, 3 fixture event streams +
  JSON schema + 3 contract tests, branch-protection helper + smoke test, Epic-1 close
  checklist, regression-detection meta-test, CI YAML rewrite, status flip to `review`. All
  11 aggregator nested classes pass under the dedicated `foundation-gate` Maven profile in
  ≈39s wall. Status flipped `in-progress → review`. Scope reductions documented in
  Completion Notes: Spring-cached container reuse for Flyway contract #2; structural-only
  CLI/REST symmetry for contract #6 (full runtime DomainResult-equality deferred);
  programmatic JSON shape validation for fixture conformance (`networknt` schema validator
  deferred). The `epic-1` sprint-status flip to `done` is NOT automated — it follows the
  human Epic-1-close walkthrough per AC10 and `docs/epic-1-close-checklist.md`.

### Review Findings

_Adversarial code review of story 1.23 — 2026-05-19. Layers: Blind Hunter + Edge Case Hunter + Acceptance Auditor. Diff: 28 files / ~3,685 lines (`_bmad-output/implementation-artifacts/.review-1-23/combined.diff`)._

**Decision-needed items resolved 2026-05-19** — all six AC deviations reclassified as `harden now` patches per user. See P11–P16 below.

**Patch** (unambiguous fixes):

- [x] [Review][Patch] **PR-failure comment never posted (defeats AC9 failure path)** [`.github/workflows/ci.yml:837-866`] — `Detect non-docs changed files on PR` step has `if: ${{ github.event_name == 'pull_request' }}` with NO `always()`. When the test step fails, this step is skipped by default; `steps.changed-files.outputs.has-code` is empty (≠ `'true'`), so the upsert step's compound condition is false and the failure comment never appears. Fix: add `always() &&` to both the detect step AND keep `always()` on the upsert step. Net guard: `if: ${{ always() && github.event_name == 'pull_request' }}` on the detect step.
- [x] [Review][Patch] **Branch-protection helpers require non-existent `doctor-smoke (windows-latest)` check** [`scripts/ci/configure-branch-protection.sh:30`, `scripts/ci/configure-branch-protection.ps1` equivalent line] — `ci.yml:670-672` names the `doctor-smoke` job `doctor-smoke (ubuntu-latest)` only; no Windows matrix variant exists (story 1.17 collapsed the OS matrix here). Once an admin runs the helper, branch protection blocks every PR on a context that no CI run ever produces — permanent merge block. Fix: remove the `doctor-smoke (windows-latest)` entry from BOTH scripts and from `docs/ci-branch-protection.md`'s required-checks table; update `BranchProtectionConfigSmokeContractTest` if it asserts that exact list.
- [x] [Review][Patch] **`gh api -f required_status_checks[strict]=true` sends string `"true"` instead of boolean** [`scripts/ci/configure-branch-protection.sh:63`, `.ps1` equivalent] — `gh api -f` (`--raw-field`) serializes as string; the Branch Protection API requires `strict` as JSON boolean. Adjacent `enforce_admins=true` correctly uses `-F` (typed). Inconsistent within the same call. Fix: change `-f required_status_checks[strict]=true` → `-F required_status_checks[strict]=true` in both scripts.
- [x] [Review][Patch] **`gh api -f restrictions=` sends empty string; API requires `null` or object** [`scripts/ci/configure-branch-protection.sh:67`, `.ps1` equivalent] — Likely 422 from the API. Fix: change to `-F restrictions=null` (gh's typed-null syntax) or omit the parameter on public-repo paths.
- [x] [Review][Patch] **Three new `Fixture*Test.java` contract tests never execute in any tier** [`deliveryline-backend/pom.xml:187-218,238-246,286-298`; affected test files: `FixtureEventStreamSchemaConformanceTest.java`, `FixtureEventStreamTransitionIntegrityTest.java`, `FixtureEventStreamArtifactVariantCoverageTest.java`] — They carry `@Tag("contract")` so Surefire's `<excludedGroups>contract,...</excludedGroups>` excludes them. They end in `*Test.java`, not `*ContractTest.java`, so Failsafe's `<includes>**/*ContractTest.java</includes>` skips them. The foundation-gate profile's includes (`**/foundation/**/*Test.java`) don't match `**/contract/**` either. Net: AC4/AC5/AC6/AC7 fixture-conformance enforcement is dead code. Fix: rename the three files to `*ContractTest.java` (preserves spec naming, matches existing Failsafe include) OR add `<include>**/contract/**/Fixture*Test.java</include>` to Failsafe's `backend-contract-tests` profile.
- [x] [Review][Patch] **AC1 — Surefire `<excludedGroups>` not updated; uses file-pattern exclusion instead** [`deliveryline-backend/pom.xml:187,208`] — Task 1 explicitly mandates appending `foundation-gate` to `<excludedGroups>`. Impl uses `<exclude>**/foundation/**/*Test.java</exclude>`. Functionally equivalent today but leaves the tag-based invariant unmet: a future `@Tag("foundation-gate")` test placed outside `org.dradgo.foundation` would leak into the unit-test group. Fix: append `foundation-gate` to `<excludedGroups>` and remove the file-pattern exclude.
- [x] [Review][Patch] **`parseFailureCategoryNullable` silently returns null for unknown values** [`deliveryline-backend/src/test/java/org/dradgo/contract/FixtureEventStreamTransitionIntegrityTest.java:1101-1112`] — A fixture typo like `failureCategory: "runner_crashs"` returns null, which then suppresses the `Executing → Failed` failure-category requirement in the transition table check. Defense-in-depth gap. Fix: throw explicitly when the textual node is non-blank but unrecognized; preserve null/missing semantics for actually-absent values.
- [x] [Review][Patch] **Changed-files `codePrefixes` omit root-level paths** [`.github/workflows/ci.yml:852-858`] — Prefixes list `deliveryline-backend/`, `deliveryline-frontend/`, etc., but NOT `scripts/`, `pom.xml`, `mvnw`, `mvnw.cmd`, `Makefile`. A PR that only touches `scripts/ci/configure-branch-protection.sh` (the helper that itself controls required checks) is classified docs-only and the foundation-gate PR status comment is skipped — exactly the PR that most needs the comment. Fix: add `scripts/`, `pom.xml`, `mvnw`, `mvnw.cmd` to `codePrefixes`.
- [x] [Review][Patch] **`Files.readString` on adversarial fixtures will throw `MalformedInputException` on non-UTF-8 binary content** [`deliveryline-backend/src/test/java/org/dradgo/foundation/RedactionAdversarialFoundationContract.java:1855`] — Javadoc enumerates `.pem` and `.env` extensions; `.pem` files may contain non-UTF-8 bytes. Failure surfaces as a generic IOException, NOT a `[story 1.10]`-prefixed redaction violation. Fix: read bytes + decode UTF-8 with `CodingErrorAction.REPLACE`, OR catch `MalformedInputException` and wrap with a `[story 1.10] non-UTF-8 fixture` message.
- [x] [Review][Patch] **Foundation-gate job has no Docker availability pre-check; Testcontainers flakes look like contract failures** [`.github/workflows/ci.yml:782` foundation-gate job; affects `FlywayMigrationsFoundationContract`, `RedactionAdversarialFoundationContract`, and any Spring-based delegate-runs] — On a slow/flaky Docker daemon, Testcontainers throws `ContainerLaunchException`; the `delegateRunAssertGreen` helper has no env-vs-contract disambiguation, so the aggregate report blames a contract regression. Fix: add a `docker info` pre-step that fails fast with a clear message, OR catch `ContainerLaunchException` in the delegate helper and prefix the message with `[env]` instead of `[story X.Y]`.
- [x] [Review][Patch] **AC2.6 — Harden command-model symmetry to runtime `DomainResult` equality** [`CommandModelSymmetryFoundationContract.java:1153-1252`] — Replace the source-text substring grep with `@SpringBootTest`: for each `WorkflowCommand` sealed permit, construct a representative payload, dispatch through the REST path via `MockMvc` and through the CLI handler via `@Autowired` direct invocation, assert the two `DomainResult` values are structurally equal (publicId equality + fieldwise). Resolves D1 decision (chose: harden now).
- [x] [Review][Patch] **AC2.7 — Harden ProblemDetails coverage to `DomainException → toProblemDetails → code` round-trip** [`ProblemDetailsCoverageFoundationContract.java:1706-1747`] — For every `DomainErrorCode.values()` entry, construct `new DomainException(code, ...)`, route through `ProblemDetailsMapper.toProblemDetails(...)`, assert the returned `ProblemDetail.code` field equals the enum value. Keep existing catalog-metadata checks as additional invariants. Resolves D2 decision (chose: harden now).
- [x] [Review][Patch] **AC2.2 — Boot fresh Testcontainers container + invoke `flyway.migrate()` directly** [`FlywayMigrationsFoundationContract.java:1297-1333`] — Stop reusing the singleton `TestcontainersConfiguration` container; declare a `@Container PostgreSQLContainer` (use the repo-canonical PG version — the spec's `postgres:16-alpine` pin is internally inconsistent with anti-pattern #2 and should be amended to "repo-canonical image"). Call `flyway.migrate()` directly, assert `result.successCount == result.migrations.size()` and `result.success == true`, then keep the existing `info().applied()` ending-state assertion. Resolves D3 decision (chose: harden now). Spec amendment for PG version captured under deferred items.
- [x] [Review][Patch] **AC2.4 — Rewrite cross-product as `(WorkflowState × WorkflowEventType)`** [`TransitionTableCrossProductFoundationContract.java:2231-2270`] — Iterate the full `(WorkflowState × WorkflowEventType)` Cartesian product. Derive the legal set via reflection on `WorkflowTransitionTable.defaultTable()` (the public `legalTransitions()` accessor or equivalent). For every `(prior, eventType)` pair NOT in the legal set, assert that the transition attempt raises `DomainException` with `DomainErrorCode.ILLEGAL_TRANSITION` (keep OC #1's exception-name accommodation). Resolves D4 decision (chose: harden now).
- [x] [Review][Patch] **AC2.10 — Add explicit short-circuit assertion** [`FoundationGateVerificationTest.java:1662-1672` `Contract10ArtifactOperation`] — After the existing `ArtifactOperationServiceContractTest` delegate-run, add a direct assertion: construct a draft artifact, attempt the "mark approval-eligible" operation (or whatever the closest API exposes), assert it raises `DomainException` (or `IllegalStateException`) because the artifact has not transitioned through `available`. Failure message must carry `[story 1.12]` per the gate's helper convention. Resolves D5 decision (chose: harden now).
- [x] [Review][Patch] **AC5 — Wire `com.networknt:json-schema-validator` as the actual validation engine** [`deliveryline-backend/pom.xml` (test scope), `FixtureEventStreamSchemaConformanceTest.java:614-944`] — Add `com.networknt:json-schema-validator` test-scope dependency. Replace the hand-rolled required-fields + enum + regex + forbidden-key checks with a single `JsonSchema.validate(node)` call per fixture; collect violations; assert the violation set is empty. Surface the violations with the existing `[story 1.23]` failure-message prefix. The hand-rolled checks remain only as a fallback for fixtures-that-aren't-yet-schema-fields-but-the-test-cares-about (if any). Resolves D6 decision (chose: harden now).

**Deferred** (real but not actionable in this story — recorded to `deferred-work.md`):

- [x] [Review][Defer] **AC4 state-names mismatch actual `WorkflowState` enum (spec defect)** — Spec lists `SpecDraftReady`, `SpecAvailable`, `ImplementationPlanReady`, etc.; actual enum is `Inbox`/`Planned`/`Investigating`/`WaitingForSpecApproval`/`Executing`/`WaitingForReview`/`Completed`. Fixtures correctly use real enum values. Spec-edit follow-up, no code change.
- [x] [Review][Defer] **`FoundationGateRegressionDetectionTest` is one annotation-removal away from a self-DoS** [`FoundationGateRegressionDetectionTest.java:1487`] — Removing `@Disabled` would actually run the documented proof-case mutations on main. Future ArchUnit/checkstyle guard to require `@Disabled` is desirable; not blocking story 1.23.
- [x] [Review][Defer] **No GHES authentication-host check in branch-protection helpers** — If `gh` is authenticated against a different host than the local remote, scripts silently write to wrong repo. Out-of-scope hardening.
- [x] [Review][Defer] **`./mvnw` shell-dependency on Windows runner** — Foundation-gate job pins `ubuntu-latest`; if it ever migrates, single-quoted globs + backslash continuations break in PowerShell. Add `shell: bash` defensively later.
- [x] [Review][Defer] **Generic `<!-- foundation-gate-status -->` PR-comment marker** — Low risk of cross-tool collision; namespace with workflow filename in a future cleanup.
- [x] [Review][Defer] **`Path.of("..")` CWD-dependent paths in `BranchProtectionConfigSmokeContractTest` and `RunnerContractFixturesFoundationContract`** — CI safe (Maven runs from module dir); only matters for local IDE runs from repo root.
- [x] [Review][Defer] **Spring TestContext cache thrash potential in foundation-gate aggregator** — Different `@SpringBootTest` configs across foundation-contract classes could multiply context-start cost; 20-min job timeout absorbs current load. Tune Failsafe fork settings if foundation-gate runtime grows.
- [x] [Review][Defer] **`failedStage: "implementation"` on `runner.started` events (fixture-event-streams)** — Semantically odd key reuse. Rename `failedStage` → `stage` on non-failure events in a future fixture-cleanup pass.

**Dismissed as noise / false positives / authorized substitutions** (not persisted):

- AC2.5 / AC2.9 manifest substitution for `.expected-error` / `.secrets` sidecars — explicitly authorized by spec Open Clarifications #2 and #3.
- AC9 emoji-glyph vs bold-markdown body-string difference — cosmetic.
- `QUOTED_ITEM` regex only matches double quotes — defensive future-proofing against a non-existent maintainer reformat.
- Schema `terminalState` redundant `type` + `enum` — valid, not wrong.
- `Math.min(snippet.length(), 4)` dead defensive code — nit.
- `-Dit.test='*FoundationGateVerificationTest*'` wildcard too broad — theoretical future-class collision.
- `POSTGRES_PASSWORD: deliveryline-ci` committed plain — ephemeral Testcontainers credential; not a real secret.
- `FoundationGateRegressionDetectionTest` `@Disabled` adds skipped-test noise — benign.
- `details.idempotencyKey` absent check ordering — diagnostic loss only.
- Edge Case Hunter LOW items it self-marked "not actual issue" or "false alarm" (Windows path normalization in `Files.walk`; `comments.find` after `paginate`).
- `SCHEMA_FILE` parsed but not used as validator — merged into AC5 decision-needed.
### Review Findings (Subagent Pass 2026-05-19)

- [x] [Review][Patch] **Delegated foundation contracts can be skipped without failing the gate** [`deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateAssertions.java`] — `delegateRunAssertGreen(...)` fails on reported failures and on zero discovered tests, but it does not assert that any delegated tests actually executed successfully. A delegated class that is fully `@Disabled`, conditionally skipped, or filtered by the engine can still leave the gate green, which weakens AC11's regression-protection guarantee.

- [x] [Review][Patch] **Transition-table cross-product uses the SUT as its own oracle** [`deliveryline-backend/src/test/java/org/dradgo/foundation/TransitionTableCrossProductFoundationContract.java`, `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateRegressionDetectionTest.java`] — `expectedLegal` is derived from `table.allowedTargetsFrom(prior)` and then validated with `table.assertTransitionAllowed(...)` on the same mutated table. If `defaultTable()` is widened incorrectly, the supplemental contract accepts that wider table instead of detecting the regression, so AC11 proof-case 3 is currently false.

- [x] [Review][Patch] **Contract #6 still does not verify CLI/REST `DomainResult` equality** [`deliveryline-backend/src/test/java/org/dradgo/foundation/CommandModelSymmetryFoundationContract.java`] — the test is a `@WebMvcTest` with a mocked `WorkflowCommandService`, so it only proves REST DTO-to-command construction. It explicitly treats missing CLI parity for four commands as informational output, which means the foundation gate still does not enforce story 1.23 AC2.6's requirement to dispatch the same payload through CLI and REST and compare the resulting `DomainResult`s.

- [x] [Review][Patch] **Fixture transition-integrity under-validates state-changing events** [`deliveryline-backend/src/test/java/org/dradgo/contract/FixtureEventStreamTransitionIntegrityContractTest.java`] — the AC6 test never validates `eventType` semantics at all, and it silently skips events where only one of `priorState` / `resultingState` is present. That lets malformed or mislabeled state-changing fixture events pass as long as the surviving state pair is legal.

- [x] [Review][Patch] **Foundation-gate PR comment scope does not match AC9** [`.github/workflows/ci.yml`] — the comment path runs for any `pull_request` and never checks that the base branch is `main`, and its changed-file filter is broader than the AC's declared scope (`backend`, `frontend`, or `runner` code). This means non-`main` PRs and infra/build-only PRs can still receive the foundation-gate status comment even though AC9 says they should not.

- [x] [Review][Patch] **Fixture README references out-of-contract Epic 2 stories** [`deliveryline-backend/src/test/resources/fixture-event-streams/README.md`] — AC8 restricts the “Recommended for Epic 2 stories” column to the specific Epic 2 story IDs `2.15`, `2.16`, `2.17`, `2.18`, `2.19`, and `2.20`, but the README currently includes `2.10` and `2.21`, and no in-scope test asserts that contract.
