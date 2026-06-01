# Story 3.13: Mock GitHub Adapter

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer + foundation/contract test suite,
I want a deterministic `GitHubMockAdapter` implementing a domain-shaped `GitHubAdapter` port — backed by file-seeded fixture repos/PRs/branches and configurable error injection,
so that the full execution loop (stories 3.9 / 3.12) can be demonstrated and contract-tested without real GitHub API access (parallel to the story 1.14 mock Linear adapter), satisfying AR35.

## Context & Why This Story Exists

This is the **GitHub twin of the already-shipped Mock Linear Adapter (story 1.14)**. Build it by mirroring that proven pattern almost line-for-line — same package split, same `@Profile` mock/real wiring, same fixture-registry shape, same exception-carries-a-failure-category convention. The real adapter (story 3.14) implements the *same port* against GitHub's REST API; this story makes the port + a zero-network mock exist first so callers (`RepositoryWorkspaceService` 3.9, PR-output flow 3.12) and contract tests can be written against a deterministic seam.

**No external network, no Docker, no Flyway migration, no REST surface.** It is a pure backend port + test-fixture story.

## Acceptance Criteria

> Criteria are the epic's verbatim ACs (epic-03-agent-execution.md §"Story 3.13", lines 263–275) with implementation-binding clarifications added inline. Where the epic wording is loose, the **bold parenthetical** is the binding interpretation for this repo.

1. **Given** the integration layer, **Then** a `GitHubAdapter` **port interface lives in `org.dradgo.application.integration.github`** (NOT in `adapters.*` — the epic's "in adapters.integration.github" wording is loose; mirror the Linear precedent where the *port* is application-owned and only the *impls* live under `adapters`). It carries **domain-shaped methods only**: `getRepositoryByRef(String repoRef)`, `getPullRequestByRef(String prRef)`, `getBranchByRef(String repoRef, String branchName)`, `createPullRequest(String repoRef, String branch, String title, String body)`, `updatePullRequest(String prRef, String body)`, `commentOnPullRequest(String prRef, String body)`. GitHub-specific types (REST DTOs, SDK types, auth tokens, HTTP-client types) **must not leak through the port** — enforced by a new ArchUnit rule (AC11) mirroring `LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT`.
2. **Given** Spring profile `github-mock` (added to the `test` profile group as default; opt-in for `local`/`demo`), **Then** `GitHubMockAdapter` (`@Component @Profile("github-mock")` in `org.dradgo.adapters.integration.github`) implements `GitHubAdapter` backed by a file-seeded fixture set whose happy-path fixtures load from `deliveryline-backend/src/main/resources/github-fixtures/`.
3. **Given** the fixture set, **Then** at minimum it includes **3 fixture repositories** (one per story-1.14 Linear fixture ticket: `LIN-101` feature, `LIN-102` bug, `LIN-103` docs), **1 open PR per repo**, and **1 branch per repo** (the PR's source branch) — each with stable deterministic IDs and metadata.
4. **Given** wrong-ticket/wrong-repo prevention (NFR20), **Then** the mock supports a deliberate-conflict fixture: `getPullRequestByRef('PR-conflict')` returns a PR whose repository deliberately conflicts with the repo the workflow has linked — consumed by story 3.12 AC6 `PR_REF_CONTEXT_MISMATCH` test. (Net-new vs Linear, which had no conflict mode.)
5. **Given** failure simulation, **Then** the mock supports configurable error injection by special ref values: `getRepositoryByRef('repo-not-found')` → `GITHUB_REPO_NOT_FOUND` (404), `getPullRequestByRef('pr-403')` → `GITHUB_PERMISSION_DENIED`, `commentOnPullRequest('pr-rate-limited', …)` → `GITHUB_RATE_LIMITED`, `createPullRequest(repoRef, 'protected-branch', …)` → `GITHUB_BRANCH_PROTECTED`. **Each is signalled by throwing `GitHubAdapterException` carrying the matching `IntegrationFailureCategory`** (see Dev Notes "Decision D1" for adding these enum values).
6. **Given** the `github-mock` profile, **When** the foundation slice runs, **Then** no GitHub network calls are possible — proven by a wiring-contract test asserting the mock bean loads and **no GitHub HTTP-client bean exists** under `github-mock` (parallel to story 1.14 AC10 `…LinearRestClientIsAbsent`).
7. **Given** the ports-and-adapters boundary, **Then** the mock shares the **exact same `GitHubAdapter` interface** the real adapter (3.14) will implement — switching profile to `github-real` activates the real impl with zero orchestration-code change.
8. **Given** idempotency at the mock layer, **Then** repeated `commentOnPullRequest` / `createPullRequest` calls with the same content + same target **do not stack duplicate fixture records** (mirrors the real adapter's 3.14 idempotency). NOTE: this DIFFERS from the Linear mock, which records every call — the GitHub mock must dedupe.
9. **Given** seed data, **Then** it is documented in `deliveryline-backend/src/test/resources/github-fixtures/README.md` (mirror the existing `linear-fixtures/README.md` table format) listing each fixture's intended use case and which test scenarios consume it.
10. **Given** the foundation gate (story 1.23), **Then** this story extends it to assert: the `GitHubAdapter` port exists and the mock implements it. **Mock-vs-real interface parity is explicitly deferred** to story 3.14 ("once story 3.14 lands") — do NOT author a parity test against a non-existent real adapter now; leave a documented placeholder/`@Disabled` stub or a TODO referencing 3.14.
11. **Given** the test suite, **Then** tests cover: each fixture repo/PR/branch lookup, each error-injection ref produces the correct `IntegrationFailureCategory`, the deliberate-conflict fixture, idempotent `commentOnPullRequest` + `createPullRequest`, no-GitHub-client-bean execution under `github-mock`, and an ArchUnit assertion that no GitHub-specific types leak through the port.

## Tasks / Subtasks

- [x] **Task 1 — Define the `GitHubAdapter` port + domain records** (AC: 1, 7)
  - [x] Create package `org.dradgo.application.integration.github`.
  - [x] `GitHubAdapter` interface with the 6 methods from AC1. Use **`String` refs** (`repoRef`, `prRef`, `branchName`) to match Linear's `String ticketRef` house style and the string-literal error-injection refs in AC5 — do NOT introduce `RepositoryRef`/`PullRequestRef` wrapper records (Linear keeps its `ParsedTicketRef` private to the adapter).
  - [x] Read lookups return `Optional<T>` for genuine absence; write methods return the domain record of the affected entity. Suggested signatures: `Optional<GitHubRepository> getRepositoryByRef(String)`, `Optional<GitHubPullRequest> getPullRequestByRef(String)`, `Optional<GitHubBranch> getBranchByRef(String, String)`, `GitHubPullRequest createPullRequest(String, String, String, String)`, `GitHubPullRequest updatePullRequest(String, String)`, `void commentOnPullRequest(String, String)`.
  - [x] Domain records in the same package, GitHub-SDK-free: `GitHubRepository(String repoRef, String fullName, String defaultBranch, …)`, `GitHubPullRequest(String prRef, String repoRef, int number, String sourceBranch, String state, String url, …)`, `GitHubBranch(String repoRef, String name, String headSha, …)`. Keep fields minimal but stable; deterministic.
  - [x] `GitHubAdapterException extends RuntimeException` carrying a `final IntegrationFailureCategory failureCategory` — copy `LinearAdapterException` exactly (`application.integration.linear.LinearAdapterException`).
  - [x] Javadoc the two-impl/profile-activated contract (copy the `LinearAdapter` Javadoc shape, lines 13–25 of `LinearAdapter.java`).
- [x] **Task 2 — Add GitHub `IntegrationFailureCategory` values** (AC: 5) — see Decision D1
  - [x] Add to `org.dradgo.domain.registry.IntegrationFailureCategory`: at minimum the 4 this story injects — `GITHUB_REPO_NOT_FOUND("github_repo_not_found")`, `GITHUB_PERMISSION_DENIED("github_permission_denied")`, `GITHUB_RATE_LIMITED("github_rate_limited")`, `GITHUB_BRANCH_PROTECTED("github_branch_protected")`. **Recommended: add the full story-3.14 AC7 set now** (also `GITHUB_AUTH_FAILED`, `GITHUB_PR_NOT_FOUND`, `GITHUB_NETWORK_FAILURE`, `GITHUB_API_VERSION_INCOMPATIBLE`) so 3.14 needn't touch the enum. Snake_case wire values.
  - [x] Verify `RegistryContractTest.registryCatalogExposesTheAuthoritativeFoundationValueSets` stays green (it auto-derives via `DomainRegistry.integrationFailureCategories()` → `valuesOf(IntegrationFailureCategory.values())`, so no manual registry edit is needed — confirm, don't assume).
- [x] **Task 3 — Mock adapter, scenario, registry** (AC: 2, 4, 5, 8)
  - [x] `org.dradgo.adapters.integration.github` package.
  - [x] `GitHubMockScenario` record + `Behaviour` enum mirroring `LinearMockScenario`. Behaviours: `HAPPY`, `NOT_FOUND`, `REPO_NOT_FOUND`, `PERMISSION_DENIED`, `RATE_LIMITED`, `BRANCH_PROTECTED`, `CONFLICT` (deliberate-conflict, AC4).
  - [x] `GitHubMockScenarioRegistry` (`@Component @Profile("github-mock")`) seeding the 3 happy repos/PRs/branches from `src/main/resources/github-fixtures/` in its constructor; public `String` constants for every deterministic ref (repo refs, PR refs, branch names, plus the adversarial refs `repo-not-found`, `pr-403`, `pr-rate-limited`, `protected-branch`, `PR-conflict`). Mirror `LinearMockScenarioRegistry` (`register`, `find`, `all`, `loadHappyFixture`, `clearTestScenarios`).
  - [x] `GitHubMockAdapter` (`@Component @Profile("github-mock")`) switching on scenario `Behaviour`: HAPPY → return fixture record; absent → `Optional.empty()`; the adversarial behaviours → `throw failure(scenario, op)` building a `GitHubAdapterException(category, msg)`. Copy the `failure(...)` builder shape from `LinearMockAdapter` (lines 121–140).
  - [x] **Idempotency (AC8):** back created-PR + posted-comment state with a keyed in-memory store, NOT an append-only list. Dedupe `createPullRequest` on `(repoRef, sourceBranch)`; dedupe `commentOnPullRequest` on `(prRef, contentFingerprint)`. Re-call returns the existing record / is a no-op. Provide test-only accessors (`createdPullRequests()`, `postedComments()`, `clear…()`) that are NOT on the port interface.
  - [x] Fixture DTO + `toDomain()` mapper mirroring `LinearTicketFixtureDocument` (`@JsonCreator`, ISO-8601 parsing). Optional JSON schema under `src/main/resources/schemas/github-*.v1.schema.json` mirroring `linear-ticket-mock.v1.schema.json` (nice-to-have; not blocking).
- [x] **Task 4 — Profile wiring + config** (AC: 2, 6, 7)
  - [x] Add `github-mock` to the `test`/`local`/`demo` profile groups in `deliveryline-backend/src/main/resources/application.yml` (lines 12–15): `test: [runners.mock, linear-mock, github-mock]`, same for `local`/`demo`.
  - [x] Add `GitHubConfiguration` (mirror `org.dradgo.infrastructure.config.LinearConfiguration`): constants `MOCK_PROFILE="github-mock"` / `REAL_PROFILE="github-real"` and an `assertExclusiveGitHubProfile(Environment)` fail-fast guard (the guard works now even though `github-real` doesn't exist yet — it only reads active profiles). Defer any `GitHubProperties` / real RestClient bean to story 3.14.
  - [x] Confirm the foundation-gate-tier and `@SpringBootTest` tier still boot (per memory `validated-config-needs-test-yaml`: if `GitHubProperties` with validated fields were added, `src/test/resources/application.yml` would need matching keys — but this story should add NO validated config, so verify nothing new is required).
- [x] **Task 5 — Fixtures + README** (AC: 3, 9)
  - [x] Happy fixtures in `src/main/resources/github-fixtures/` (3 repos + their PR + branch). Stable IDs aligned to LIN-101/102/103.
  - [x] Adversarial marker JSONs + `README.md` table in `src/test/resources/github-fixtures/` mirroring `src/test/resources/linear-fixtures/README.md` (markers are human-readable index, registered by tests via `register(...)`, NOT auto-loaded). Document `PR-conflict` and all error-injection refs with their expected `IntegrationFailureCategory`.
- [x] **Task 6 — ArchUnit boundary rule** (AC: 1, 11)
  - [x] Add `GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT` to `org.dradgo.architecture.ArchitectureRuleCatalog` mirroring `LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT` (lines 484–498): `noClasses().that().resideInAPackage("org.dradgo.application.integration..").should().dependOnClassesThat().resideInAnyPackage("org.kohsuke.github..", "com.github..", "org.springframework.web.client..", "org.springframework.http.client..")`. Register it as an `@ArchTest` field in `ArchitectureBoundaryTest`.
- [x] **Task 7 — Tests** (AC: 6, 10, 11)
  - [x] `GitHubMockAdapterUnitTest` (mirror `LinearMockAdapterUnitTest`): happy lookups for all 3 repos/PRs/branches; each adversarial ref throws `GitHubAdapterException` with the correct category; deliberate-conflict returns conflicting repo; idempotent createPullRequest + commentOnPullRequest (call twice → one record); `Optional.empty()` for unregistered refs.
  - [x] `GitHubProfileWiringContractTest` (mirror `IntegrationProfileWiringContractTest`): under `github-mock` the mock bean loads and no GitHub HTTP-client bean exists (AC6); both-profiles-active context refresh fails fast with "GitHub profile conflict" (uses `GitHubConfiguration` guard); no-profile leaves the slice inactive.
  - [x] `GitHubScenarioContractTest` (mirror `LinearScenarioContractTest`): every default scenario has a loadable classpath fixture; every fixture parses to a valid domain record; the 3 repos use stable deterministic refs.
  - [x] Extend `FoundationGateVerificationTest` with a `Contract #N` nested `@Tag("foundation-gate")` class delegating to the GitHub mock/scenario/ArchUnit tests (AC10 port-exists + mock-implements). Add a TODO/`@Disabled` placeholder for the 3.14 mock-vs-real parity test.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J-backed structured logs at every public service entry/exit, every typed `GitHubAdapterException` raise site, and every fixture-load/idempotency-replay branch. (No external SPI / DB writes in this story — the surface is the mock adapter methods + registry loading.)
  - [x] Use parameterized logging (`log.info("...", arg1, arg2)`) — never string concatenation. Copy the `LinearMockAdapter` log style (e.g. `log.info("github_mock comment_recorded prRef={} fingerprint={}", prRef, fp)`).
  - [x] Levels: `INFO` for normal lifecycle (lookup hit, PR created, comment recorded), `WARN` for the simulated recoverable anomalies (rate-limited, conflict, idempotency replay-no-op), `ERROR` only for genuinely unexpected failures. `DEBUG` for fixture-load detail.
  - [x] Carry the relevant context keys where available: the entity public ref (`repoRef`, `prRef`, `branchName`). No `correlationId`/`workflowRunId` flows through the mock adapter directly (it has no run context) — log what the method receives.
  - [x] Never log secrets, tokens, or full PR body bytes — log refs and counts, not `title`/`body` content (the real adapter 3.14 redacts; the mock simply must not log payloads).
- [x] Add at least one assertion (list-appender or `OutputCaptureExtension`) that the expected log line(s) are emitted at the expected level for the rate-limited / conflict / idempotency-replay branches.

### Review Findings

- [x] [Review][Patch] AC4/AC5 special refs are not active in the default `github-mock` runtime [`deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubMockScenarioRegistry.java`:87]
- [x] [Review][Patch] Scenario contract enforces only three happy defaults, preserving the missing adversarial/conflict default refs [`deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubScenarioContractTest.java`:25]
- [x] [Review][Patch] Source file contains a raw NUL byte as the branch-key separator, causing binary-file handling in tooling [`deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubMockScenarioRegistry.java`:41]
- [x] [Review][Patch] Created PRs cannot be read back through `getPullRequestByRef` even though `updatePullRequest` can find them [`deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubMockAdapter.java`:88]
- [x] [Review][Patch] Comment idempotency uses 32-bit `String.hashCode()`, so different bodies with the same hash are silently deduped [`deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubMockAdapter.java`:282]
- [x] [Review][Patch] Network-isolation wiring test only excludes bean name `gitHubRestClient`, not HTTP-client bean types or profile-group activation [`deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubProfileWiringContractTest.java`:41]
- [x] [Review][Patch] Typed `GitHubAdapterException` and missing-fixture failure paths are not covered by the required structured logs [`deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubMockAdapter.java`:142]

## Dev Notes

### The one reference that matters: Mock Linear Adapter (story 1.14)

This story is a near-mechanical port of the Linear mock. Read these files first and mirror them:

| Concern | Linear file to mirror | GitHub target |
|---|---|---|
| Port interface | `application/integration/linear/LinearAdapter.java` (lines 27–55) | `application/integration/github/GitHubAdapter.java` |
| Domain records | `application/integration/linear/LinearTicket.java`, `GovernedRunComment.java` | `GitHubRepository`, `GitHubPullRequest`, `GitHubBranch` |
| Exception | `application/integration/linear/LinearAdapterException.java` | `application/integration/github/GitHubAdapterException.java` |
| Mock impl | `adapters/integration/linear/LinearMockAdapter.java` (esp. `failure(...)` 121–140) | `adapters/integration/github/GitHubMockAdapter.java` |
| Scenario + Behaviour | `adapters/integration/linear/LinearMockScenario.java` | `GitHubMockScenario.java` |
| Registry | `adapters/integration/linear/LinearMockScenarioRegistry.java` (constants + `registerDefault`) | `GitHubMockScenarioRegistry.java` |
| Fixture DTO | `adapters/integration/linear/LinearTicketFixtureDocument.java` | `GitHubFixtureDocument.java` |
| Profile config + exclusivity guard | `infrastructure/config/LinearConfiguration.java` (29–39, 76–86) | `GitHubConfiguration.java` |
| Unit test | `test/.../adapters/integration/linear/LinearMockAdapterUnitTest.java` | `GitHubMockAdapterUnitTest.java` |
| Wiring test | `test/.../adapters/integration/linear/IntegrationProfileWiringContractTest.java` | `GitHubProfileWiringContractTest.java` |
| Scenario/fixture exit-gate | `test/.../adapters/integration/linear/LinearScenarioContractTest.java` | `GitHubScenarioContractTest.java` |
| Fixture README | `test/resources/linear-fixtures/README.md` | `test/resources/github-fixtures/README.md` |

### Package placement — DO NOT follow the epic's loose wording

Epic AC1 says the port "exists in `adapters.integration.github`". **That is wrong for this codebase.** The Linear precedent + the ArchUnit rules require:
- **Port + domain records + exception → `org.dradgo.application.integration.github`** (application-owned, framework/SDK-free).
- **Mock adapter + scenario + registry + fixture DTO → `org.dradgo.adapters.integration.github`** (the adapter slice).

`ArchitectureRuleCatalog.LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT` (lines 484–498) targets `org.dradgo.application.integration..` — so the port living there is what the boundary rule protects. The `ADAPTER_SLICES_MUST_NOT_DEPEND_ON_EACH_OTHER` rule means the GitHub adapter slice must NOT import the Linear slice — copy the patterns, don't reference them.

### Decision D1 — add GitHub values to `IntegrationFailureCategory` (LOW RISK)

`org.dradgo.domain.registry.IntegrationFailureCategory` currently has only 4 generic values (`SYNC_FAILURE`, `LINK_FAILURE`, `STATE_CONFLICT`, `NETWORK_API_FAILURE`). The Linear mock *reused* these generics (RATE_LIMITED→`NETWORK_API_FAILURE`, AUTH→`LINK_FAILURE`). **GitHub is different**: story 3.14 AC7 explicitly enumerates GitHub-specific `IntegrationFailureCategory` members, and this story's AC5 names them. So add them to the enum.

Why this is safe and additive (verified, not assumed — but re-confirm on your branch):
- `DomainRegistry.integrationFailureCategories()` (line 93) auto-derives from `valuesOf(IntegrationFailureCategory.values())` (line 34) → `RegistryContractTest.registryCatalogExposes…` (line 106) stays green automatically. No manual `DomainRegistry` edit.
- `IntegrationFailureCategory` is **NOT** surfaced in the API schema-placeholder manifest (`RegistryContractTest` aligns workflowStates/actorTypes/artifactStatuses with SQL CHECKs + the API placeholder, but **not** integration failure categories). So **no `openapi.json` / `schema.d.ts` drift, no SQL CHECK constraint** references this enum. Contrast with memory `new-domainerrorcode-three-sites` — that three-sites rule is for `DomainErrorCode`, which IS in the manifest; `IntegrationFailureCategory` is not, so the blast radius is just the enum + your new tests.
- Wire form is snake_case `value()`; renaming a constant later is wire-breaking, so name them right the first time.

### Decision D2 — error injection signals via thrown exception, not empty Optional

Linear's `NOT_FOUND` returned `Optional.empty()` and let `IntegrationLinkService` route to `LINEAR_TICKET_NOT_FOUND`. For GitHub, AC5 couples each adversarial ref to a *specific failure category*, and these are fatal-to-the-stage conditions (repo missing during clone, permission denied, rate limited, branch protected). So the adversarial refs **throw `GitHubAdapterException(category, msg)`**. Reserve `Optional.empty()` for ordinary "this ref isn't seeded" lookups (parallel to Linear's `fetchReturnsEmptyForUnregisteredRef`).

### Decision D3 — idempotency is real in this mock (unlike Linear)

The Linear mock recorded every `postGovernedRunComment` call (no dedupe) and tests asserted the recording. **AC8 requires the GitHub mock to actually dedupe** so it matches the real adapter's 3.14 idempotency behavior. Back created PRs and posted comments with keyed maps, not append lists. Idempotency keys: PR on `(repoRef, sourceBranch)`; comment on `(prRef, fingerprint(body))`. A `fingerprint` can be a simple stable hash of the trimmed body — the real adapter (3.14) uses an embedded marker; the mock just needs deterministic dedupe.

### What this story explicitly does NOT do

- **No real GitHub adapter** — that's story 3.14 (`GitHubRealAdapter`, PAT auth, REST v3, rate-limit headers, `RedactionPolicyService` on egress). Do not add HTTP clients, `GitHubProperties` validated config, or `org.kohsuke.github` dependency.
- **No `RedactionPolicyService` call** — the mock stores bodies in-memory and never sends them anywhere; redaction-on-egress is 3.14's job. Just don't *log* bodies.
- **No Flyway migration** — `V1__create_workflow_core_tables.sql:251` already has `ck_integration_links_integration_type check (integration_type in ('linear', 'github_pr'))`. Current max migration is `V10`. Touch nothing in `db/migration`.
- **No `IntegrationLinkService.syncGitHubPr` / `linkGitHubPr`** — those are stories 3.15. This story only delivers the port + mock + fixtures + contract tests.
- **No mock-vs-real parity test** — deferred to 3.14 (AC10). Leave a documented stub.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. This is enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`.
- **Where to log (this story's surface = the mock adapter + registry):**
  - `GitHubMockAdapter` public methods → `INFO` on lookup hit / PR create / comment record; `WARN` on simulated rate-limit, permission-denied, branch-protected, deliberate-conflict, and idempotency replay-no-op; `ERROR` only for an unexpected internal failure.
  - `GitHubMockScenarioRegistry` fixture load → `DEBUG` per fixture, `WARN` if a default scenario's fixture resource is missing.
- **Required context keys:** the entity public refs the method receives (`repoRef`, `prRef`, `branchName`). The mock has no run/correlation context — do not fabricate `correlationId`/`workflowRunId`.
- **Forbidden in log output:** PR `title`/`body` content, any token-like strings. Log refs, counts, categories, and branch names only.
- **Test contract:** pin the rate-limited / conflict / idempotency-replay log lines with a list-appender or `OutputCaptureExtension` so downstream refactors can't silently delete them.

### Project Structure Notes

- Backend module is **`deliveryline-backend/`** (the planning docs sometimes say `backend/` — the real path is `deliveryline-backend/`). Base package `org.dradgo`.
- Standard layout: ports/services under `application.*`, impls under `adapters.*`, registries/enums under `domain.registry`, Spring config under `infrastructure.config`. New code follows the Linear slice's exact placement (see mirror table above).
- Profile groups live in `src/main/resources/application.yml` lines 12–15 (`runners.mock`, `linear-mock` today → add `github-mock`).
- Adversarial fixtures go under `src/test/resources/` (never `src/main/resources/`) so they never reach a `demo`/`local` runtime classpath — same rule the Linear README states.
- Verify against the foundation gate: `mvn -Pfoundation-gate failsafe:integration-test failsafe:verify` (runs `@Tag("foundation-gate")` only). Also run the fast unit tier and `spotless:apply` + `checkstyle:check` (the runner stories' close-outs show these are the gating static checks).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.13] — ACs 1–11 (lines 257–275), epic active-slice note (line 7), cross-references from 3.9 (line 182), 3.12 (lines 188, 193), 3.14 reciprocal parity (lines 285, 292), 3.15 sync (line 310).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearAdapter.java#L13-L55] — port + Javadoc contract to mirror.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearMockAdapter.java#L40-L144] — mock impl + `failure(...)` builder.
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearMockScenarioRegistry.java#L31-L129] — registry/constants/`registerDefault` pattern.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/IntegrationFailureCategory.java] — enum to extend (Decision D1).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainRegistry.java#L34,L93] — auto-derivation proving the registry stays green.
- [Source: deliveryline-backend/src/test/java/org/dradgo/contract/RegistryContractTest.java#L106] — the assertion that auto-covers new enum values.
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java#L484-L498] — `LINEAR_TYPES_MUST_NOT_LEAK_THROUGH_PORT` to clone (AC11).
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java] — `@ArchTest` registration site.
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearConfiguration.java#L29-L86] — profile constants + exclusivity guard to mirror.
- [Source: deliveryline-backend/src/main/resources/application.yml#L11-L15] — profile groups to extend.
- [Source: deliveryline-backend/src/test/resources/linear-fixtures/README.md] — fixture README format to mirror (AC9).
- [Source: deliveryline-backend/src/main/resources/db/migration/V1__create_workflow_core_tables.sql#L240-L260] — `integration_type` CHECK already allows `github_pr` (no migration needed).
- [Source: deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java] — `Contract #N` nested-class delegation pattern (AC10).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Claude Opus 4.8, 1M context) — bmad-dev-story workflow

### Debug Log References

- Focused slice (PowerShell, per memory `rtk-hook-only-matches-bash`): `mvnw -pl deliveryline-backend test -Dtest=GitHubMockAdapterUnitTest,GitHubScenarioContractTest,GitHubProfileWiringContractTest,GitHubMockAdapterLoggingContractTest` → **24/0/0** (15+3+3+3).
- ArchUnit boundary tier: `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=ArchitectureBoundaryTest` → **38/0/0** (was 37; +`github_types_must_not_leak_through_port`).
- Foundation gate AC10: `mvnw -pl deliveryline-backend -Pfoundation-gate failsafe:integration-test -Dit.test='FoundationGateVerificationTest$Contract11GitHubMockAdapter'` → **4 run, 1 skipped (the @Disabled 3.14 parity stub), 0 failures**.
- Static gates: `spotless:check` + `checkstyle:check` → BUILD SUCCESS (after one `spotless:apply` Javadoc reflow).
- Full fast Surefire regression tier: `mvnw -pl deliveryline-backend test` → **663 tests, 0 failures, 0 errors, 10 skipped**.

### Completion Notes List

Implemented the Mock GitHub Adapter as a near-mechanical mirror of the story-1.14 Mock Linear Adapter. Highlights and decisions realized in code:

- **Task 1 — Port + records + exception (AC1, AC7):** `GitHubAdapter` port with the 6 `String`-ref methods lives in `application.integration.github` (NOT `adapters.*` — per Dev Notes, the epic's wording is loose). Domain records `GitHubRepository`, `GitHubPullRequest` (carries an ISO-8601 `createdAt` to exercise the fixture parser), `GitHubBranch`, all SDK-free. `GitHubAdapterException` copies `LinearAdapterException` (carries `IntegrationFailureCategory`). Read methods return `Optional<T>`; write methods return the affected record.
- **Task 2 — Enum (AC5, Decision D1):** Added the 4 injected GitHub categories **plus** the full story-3.14 AC7 set (`GITHUB_PR_NOT_FOUND`, `GITHUB_AUTH_FAILED`, `GITHUB_NETWORK_FAILURE`, `GITHUB_API_VERSION_INCOMPATIBLE`) so 3.14 needn't touch the enum. Verified (not assumed) that `IntegrationFailureCategory` is auto-derived in `DomainRegistry` and is referenced in `RegistryContractTest` only by the tautological auto-derive equality (line 106-107) — no SQL CHECK / API-placeholder-manifest tie, so the additions are safe & additive (contrast memory `new-domainerrorcode-three-sites`).
- **Task 3 — Mock slice (AC2, AC4, AC5, AC8, Decisions D2/D3):** `GitHubMockScenario`+`Behaviour`, `GitHubMockScenarioRegistry` (`@Profile("github-mock")`, seeds 3 happy fixtures into per-entity indexes at construction, public ref constants, `register`/`find`/`all`/`loadHappyFixture`/`clearTestScenarios`), `GitHubMockAdapter` (`@Profile("github-mock")`). D2: adversarial refs THROW a classified `GitHubAdapterException`; ordinary absence returns `Optional.empty()`. D3: **real idempotency** — `createPullRequest` deduped on `(repoRef, sourceBranch)`, `commentOnPullRequest` deduped on `(prRef, fingerprint(body))` via keyed maps (NOT append-lists); replays return the existing record / are no-ops. Test-only accessors `createdPullRequests()`/`postedComments()` are off-port. Synthesized PRs use a fixed `SYNTHETIC_CREATED_AT` + a stable hash of inputs (no wall-clock, no randomness). `GitHubFixtureDocument` mirrors `LinearTicketFixtureDocument` (`@JsonCreator`, ISO-8601 parsing) bundling repo+PR+branch into a `GitHubFixture`.
- **Task 4 — Wiring (AC2, AC6, AC7):** Added `github-mock` to the `test`/`local`/`demo` profile groups in the main `application.yml`. `GitHubConfiguration` mirrors `LinearConfiguration`'s `assertExclusiveGitHubProfile` fail-fast guard (works now on profile-names alone; no `GitHubProperties`/RestClient bean — deferred to 3.14, so per memory `validated-config-needs-test-yaml` the test `application.yml` needs no new keys).
- **Task 5 — Fixtures (AC3, AC9):** 3 happy fixtures in `src/main/resources/github-fixtures/` (repos `GH-101/102/103` aligned to LIN-101/102/103, one open PR + its source branch each). Adversarial/conflict marker JSONs + a README table in `src/test/resources/github-fixtures/` (never auto-loaded; registered by tests).
- **Task 6 — ArchUnit (AC1, AC11):** `GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT` added to `ArchitectureRuleCatalog` (forbids `application.integration..` depending on `org.kohsuke.github..`, `com.github..`, spring web/http client) + registered as an `@ArchTest` field in `ArchitectureBoundaryTest`.
- **Task 7 — Tests + foundation gate (AC6, AC10, AC11):** `GitHubMockAdapterUnitTest` (happy lookups for all 3 repos/PRs/branches, each adversarial ref → correct category, conflict returns conflicting-repo PR, idempotent create+comment, `Optional.empty()` for unseeded refs, `updatePullRequest` happy + not-found, `clearTestScenarios`). `GitHubProfileWiringContractTest` (ApplicationContextRunner: mock loads + no GitHub HTTP-client bean under `github-mock`; both-profiles fail-fast; no-profile inactive). `GitHubScenarioContractTest` (every default scenario loads a classpath fixture, parses to a valid bundle, stable refs). `GitHubMockAdapterLoggingContractTest` (WARN pinned for rate-limited / conflict / idempotency-replay; asserts PR body content is never logged). `FoundationGateVerificationTest` extended with **Contract #11** delegating to the GitHub mock/scenario/ArchUnit tests + a `@Disabled` TODO stub for the story-3.14 mock-vs-real parity test (AC10 explicitly defers parity).
- **Logging:** SLF4J parameterized logs at every adapter method (INFO lifecycle, WARN for simulated anomalies + idempotency replays, DEBUG fixture-load). Only refs/counts/categories/fingerprints are logged — never `title`/`body` payloads.

**Deferred / out of scope (as the story directs):** no real GitHub adapter, no HTTP clients / `GitHubProperties` / `org.kohsuke.github` dep, no `RedactionPolicyService` call, no Flyway migration (V1 already allows `github_pr`), no `IntegrationLinkService.syncGitHubPr`, no mock-vs-real parity test (deferred to 3.14 via the `@Disabled` stub).

**Recommended next step:** run `code-review` with a different LLM. No Testcontainers/WSL2 parity run is required for this story — every new test is pure JUnit / ArchUnit / `ApplicationContextRunner` (no Docker, no DB, no Flyway, no lockfile change). The Docker-backed `RegistryContractTest` (Contract #3) was reasoned about, not run, in the fast tier; its `IntegrationFailureCategory` assertion is auto-derived and tautologically green with the new enum values.

### File List

**New — main:**
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/github/GitHubAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/github/GitHubRepository.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/github/GitHubPullRequest.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/github/GitHubBranch.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/github/GitHubAdapterException.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubMockAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubMockScenario.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubMockScenarioRegistry.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubFixture.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubFixtureDocument.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/GitHubConfiguration.java`
- `deliveryline-backend/src/main/resources/github-fixtures/github-feature-low-risk.json`
- `deliveryline-backend/src/main/resources/github-fixtures/github-bug-fix.json`
- `deliveryline-backend/src/main/resources/github-fixtures/github-docs.json`

**New — test:**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubMockAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubProfileWiringContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubScenarioContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubMockAdapterLoggingContractTest.java`
- `deliveryline-backend/src/test/resources/github-fixtures/README.md`
- `deliveryline-backend/src/test/resources/github-fixtures/repo-not-found-simulation.json`
- `deliveryline-backend/src/test/resources/github-fixtures/permission-denied-simulation.json`
- `deliveryline-backend/src/test/resources/github-fixtures/rate-limited-simulation.json`
- `deliveryline-backend/src/test/resources/github-fixtures/branch-protected-simulation.json`
- `deliveryline-backend/src/test/resources/github-fixtures/conflict-simulation.json`

**Modified:**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/IntegrationFailureCategory.java` (Task 2 — added 8 GitHub enum values)
- `deliveryline-backend/src/main/resources/application.yml` (Task 4 — `github-mock` added to test/local/demo profile groups)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` (Task 6 — `GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT`)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` (Task 6 — `@ArchTest` registration)
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java` (Task 7 — Contract #11 + @Disabled 3.14 parity stub)

### Change Log

| Date | Change |
| ---- | ------ |
| 2026-06-01 | Story 3.13 implemented: GitHub adapter port + domain records + exception, deterministic `GitHubMockAdapter` (`@Profile("github-mock")`) with file-seeded fixtures, configurable error injection, real idempotency, conflict fixture; 8 GitHub `IntegrationFailureCategory` values; `GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT` ArchUnit rule; `github-mock` profile wiring + `GitHubConfiguration` exclusivity guard; fixtures + READMEs; full test surface + foundation-gate Contract #11 (3.14 parity deferred via @Disabled stub). All 7 tasks + Logging-instrumentation complete; all 11 ACs satisfied. Status `ready-for-dev → in-progress → review`. |
