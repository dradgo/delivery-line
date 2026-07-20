# Story 3i.3: Bitbucket Repository Host + Pipelines CI

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a pilot operator whose code lives in Bitbucket,
I want Bitbucket to be a real repository host — push target, pull requests, and a Bitbucket Pipelines CI reader — at parity with the GitHub adapter,
so that the full governed delivery tail (including the 3h-5 CI investigation loop) works for Bitbucket projects.

## Acceptance Criteria

1. **New `bitbucket` `RepositoryHostAdapter`.** Given the `RepositoryHostAdapter` port (story 3-33) and the `GitLabRepositoryHostStubAdapter` precedent, a new real implementation declares `connectorKind() == ConnectorKind.BITBUCKET` and implements `createPullRequest`, `updatePullRequest`, `commentOnPullRequest` (Bitbucket Cloud pull requests), `getRepositoryByRef` / `getPullRequestByRef` / `getBranchByRef`, `verifyConnectivity`, and `getCapabilities` — the GitHub-adapter shape promoted onto Bitbucket transport; resolved via `ProjectConnectorResolver` (3c-3) for `bitbucket`-kind projects. **Backend keeps git ownership** — push already works over plain git HTTPS through `GitCommandPort`/`RepositoryWorkspaceService`; only the host **metadata + PR + CI** adapter is new. A `bitbucket-mock` deterministic adapter is delivered alongside the real one (mirrors `GitHubMockAdapter`/`GitHubMockScenarioRegistry`).

2. **Bitbucket Pipelines CI reader (GATED on 3h-5 — see Dev Notes §0).** Given the **3h-5 CI-checks port** (`readCheckRuns(repo, ref) → CiStatus` + `RepositoryHostCapabilities.supportsCiStatusReads`), the Bitbucket adapter implements the CI read for **Bitbucket Pipelines** and reports `supportsCiStatusReads == true`; the same 3h-5 CI investigation/fix loop drives Bitbucket projects with **no new loop**. This AC consumes an interface that **does not exist on the branch today** (3h-5 is `backlog`): implement AC2 **only against the port 3h-5 actually shipped**, binding to its real method/type names and widening `RepositoryHostCapabilities` iff 3h-5 did not already add `supportsCiStatusReads`. If 3h-5 is not yet merged when this story is worked, deliver ACs 1/3/4/5/6/7 and split AC2 forward (do **not** invent the port here — it would collide with 3h-5 on merge).

3. **`ConnectorKind` registry widening.** Given the `ConnectorKind` registry, `bitbucket` is added (enum value + Flyway connector-kind CHECK widening at the **next-free Flyway head** — the GITLAB/V18 precedent); replay-safe; the `connectorKinds` API placeholder + `RegistryContractTest` drift gate stay aligned; `FlywaySchemaContractTest` stays green.

4. **Encrypted credentials + redaction.** Given the 3c-5 credential store, Bitbucket credentials (workspace + app password / access token) are stored write-only encrypted under the existing `ConnectorRole.REPO_HOST` role, never exposed on read, and pass the redaction posture (the **two-gates** trap: fixtures manifest **and** the hardcoded corpus `Set`); nothing secret is logged (ids/lengths only).

5. **Connectivity probe.** Given `verifyConnectivity` (3c-8), the adapter probes Bitbucket auth + repository reachability and returns a structured secret-free `ConnectivityResult` — a non-5xx "could not verify" on network/rate-limit/server error, `unauthenticated` on 401/403, `unreachable` on I/O fault; the probe **never throws a vendor exception across the port**.

6. **Doctor probe + `checksRun` fan-out.** Given the doctor probe pattern (3c-10), a `bitbucket` doctor probe is added and **every hardcoded `checksRun` count assertion is incremented** (the fan-out trap — exactly one literal today: `DoctorLoggingContractTest` `checksRun=18` → `19`), and every explicit per-probe mock stub block gains a `probeBitbucket()` stub.

7. **Capability parity + delivery-tail flow.** Given capability parity, the adapter's capability contract test asserts its real `RepositoryHostCapabilities` (PR support, `supportsCiStatusReads == true` once AC2 lands, any host-specific flags such as draft-PR support), and a `bitbucket`-kind project flows through the existing delivery tail (3h-4 push/PR gates) unchanged. A mock-vs-real parity contract (mirror `RepositoryHostAbstractionFoundationContract`, foundation-gate #15) asserts both Bitbucket adapters satisfy the port identically.

8. **Test coverage.** Given tests, coverage asserts: PR create/update/comment happy-paths; (AC2, gated) Pipelines status read (green proceeds / red feeds the 3h-5 loop); `ConnectorKind`/Flyway drift; connectivity probe (reachable + auth-fail + unreachable); credential redaction over the Bitbucket corpus; doctor `checksRun` fan-out; capability contract drift; `application.*` ≥80% coverage. Backend-only story — **no OpenAPI/`schema.d.ts` regen, no FE** (repo-host has no new REST surface).

## Tasks / Subtasks

- [x] **Task 0 — PREREQ GATE + branch hygiene** (blocks AC2 only)
  - [x] Confirm 3h-5 status. If 3h-5 (`3h-5-ci-build-error-investigation-github-actions`) is **not `done`/merged**, implement ACs 1/3/4/5/6/7/8 and **split AC2 (Pipelines CI) forward** — record the split in `deferred-work.md`. Do NOT define `readCheckRuns`/`CiStatus`/`supportsCiStatusReads` here.
  - [x] If 3h-5 IS merged, read its actual CI-checks port surface (method name, `CiStatus` shape, where `supportsCiStatusReads` lives) and bind AC2 to it verbatim.
  - [x] Re-confirm the **next-free Flyway head** at implementation time (V33 on disk today; 3h-2/3h-3/3h-4/3h-5/3h-6 + 3i-1/3i-2 all sequence before this story and each may consume a Vnn — do NOT assume V34).

- [x] **Task 1 — Register the `bitbucket` connector kind** (AC3)
  - [x] Add `BITBUCKET("bitbucket")` to `ConnectorKind` (domain/registry/ConnectorKind.java) — extend the trailing constant + update the fan-out doc comment.
  - [x] New migration `V<next>__widen_connector_kind_to_bitbucket.sql` — copy `V18__widen_connector_kind_to_gitlab.sql`; drop-then-re-add BOTH `ck_projects_ticket_source_kind` and `ck_projects_repo_host_kind` with `('linear','github','gitlab','bitbucket')`.
  - [x] Add `"bitbucket"` to the `connectorKinds` array in `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`.
  - [x] Verify `RegistryContractTest.projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest` + `FlywaySchemaContractTest` (replay-safe) go green. `DomainRegistry`/`PersistedRegistryValues` are derived — no edit.

- [x] **Task 2 — Bitbucket properties + RestClient + profile wiring** (AC1/AC5)
  - [x] `BitbucketProperties` `@ConfigurationProperties("deliveryline.bitbucket")` in `application.integration.bitbucket` — mirror `GitHubProperties` (D6 normalize-never-throw compact ctor; `@JsonIgnore` + redacting `toString()` on the secret; `CREDENTIAL_OVERRIDE_ATTRIBUTE` constant; base-url default `https://api.bitbucket.org/2.0`; nested `Timeout`).
  - [x] `BitbucketConfiguration` (`infrastructure.config`) — `@Bean(name="bitbucketRestClient") @Profile("bitbucket-real")`; request-time auth interceptor preferring the per-request credential-override then host-env creds, setting `Authorization: Basic base64(workspace:app_password)` (or `Bearer <token>`); mutual-exclusivity guard mirroring `assertExclusiveGitHubProfile`.
  - [x] `RepositoryHostProperties`: add `KIND_BITBUCKET`/`isBitbucket()`; **relax `GitHubConfiguration.assertSupportedRepositoryHostKind`** so `kind=bitbucket` resolves when the adapter is on the classpath.
  - [x] `application.yml` — add `bitbucket-mock` to the `local`/`test`/`demo` profile groups + a `deliveryline.bitbucket.*` block; `src/test/resources/application.yml` parity.

- [x] **Task 3 — Bitbucket real + mock adapters** (AC1/AC5/AC7)
  - [x] `BitbucketRealAdapter implements RepositoryHostAdapter` in `adapters.integration.repohost.bitbucket` — `@Component @Primary @Profile("bitbucket-real")`, `@Qualifier("bitbucketRestClient") RestClient`, `RedactionPolicyService` (redact-on-egress for `title`/`body`, classification `shareable-redacted`); parse opaque `RepositoryRef`/`PullRequestRef` privately (Bitbucket shape: `workspace/repo`, `workspace/repo#id`); idempotent `createPullRequest` on `(repo, source, target)`; HTTP→`IntegrationFailureCategory` classification ladder (mirror `GitHubRealAdapter.classify`).
  - [x] `BitbucketMockAdapter` + `BitbucketMockScenarioRegistry` (`@Profile("bitbucket-mock")`) — deterministic (no wall-clock/randomness/network), happy fixtures under `src/main/resources/bitbucket-fixtures/`, adversarial refs mapping to failure categories, comment/PR idempotency mirroring the GitHub mock.
  - [x] Keep all Bitbucket transport (DTOs/RestClient) inside `adapters.integration.repohost.bitbucket`; **do not import the `...repohost.github` sub-package** and never leak transport types through the port (ArchUnit `REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT`).

- [x] **Task 4 — Failure categories + capabilities** (AC1/AC7)
  - [x] Add `BITBUCKET_*` values to `IntegrationFailureCategory` (recommended for observability parity: `BITBUCKET_REPO_NOT_FOUND`, `BITBUCKET_PR_NOT_FOUND`, `BITBUCKET_PERMISSION_DENIED`, `BITBUCKET_RATE_LIMITED`, `BITBUCKET_BRANCH_PROTECTED`, `BITBUCKET_AUTH_FAILED`, `BITBUCKET_NETWORK_FAILURE`). LOW fan-out — auto-cataloged via `.values()`; no CHECK / placeholder / OpenAPI. Never rename existing values.
  - [x] Add `RepositoryHostCapabilities.bitbucketDefaults()` factory (reflect real Bitbucket support: PR comments, branch protection; draft-PR support differs from GitHub — set honestly). `supportsCiStatusReads=true` is added **only when AC2/3h-5 lands**.

- [x] **Task 5 — Bitbucket credentials + redaction two-gates** (AC4)
  - [x] Store/resolve Bitbucket creds through the EXISTING store — `ProjectCredentialService.setCredential(projectId, ConnectorRole.REPO_HOST, secret)`; `resolveConnectorSecret(project, "repo_host")` feeds `verifyConnectivity(...credentialOverride)`. **No credential-store change.** Decide the single-secret encoding (e.g. `workspace:app_password` Basic string, or a bare access token).
  - [x] Redaction fixtures: add `bitbucket-app-password.txt` / `project-credential-bitbucket-token.json` under `src/test/resources/redaction-fixtures/`; add manifest entries (Gate A `fixtures-manifest.json`) **and** the hardcoded corpus `Set` in `RedactionPolicyServiceContractTest` (Gate B — the trap). `RedactionAdversarialFoundationContract` also enforces every dropped-in fixture is manifested.
  - [x] Bitbucket app passwords used as HTTP Basic are already scrubbed by the `AUTHORIZATION_HEADER` pattern. Add a distinct pattern/category **only** if you want a recognizable-prefix scrub for Atlassian API tokens (`ATATT…`/`ATCTT…`); if so, follow the T6 parity mirror chain (see Dev Notes §7).

- [x] **Task 6 — Bitbucket doctor probe + `checksRun` fan-out** (AC6)
  - [x] `DoctorProbePort.probeBitbucket()` + `DoctorProbeAdapter.probeBitbucket()` (mirror `probeGitHubAuth`, profile-gated).
  - [x] `DoctorService`: add `CHECK_BITBUCKET` constant, add to `STATIC_ORDER`, add the `switch` arm.
  - [x] `DoctorLoggingContractTest` line ~90: `checksRun=18` → `19`; add `probeBitbucket()` stub to its inline mock block. Add `probeBitbucket()` stubs to all 5 explicit stub blocks in `DoctorServiceTest` (`stubAllProbesPass()` + 4 others). `DoctorServiceTest` size assertion is dynamic (derives from `STATIC_ORDER`) — self-updates.

- [ ] **Task 7 — Pipelines CI reader** (AC2 — **SPLIT FORWARD**, hard-gated on 3h-5 which is `backlog`; see Task 0 + Dev Notes §0 + `deferred-work.md` entry dated 2026-07-10)
  - [ ] Implement the 3h-5 CI-checks port method for Bitbucket Pipelines (`readCheckRuns`-equivalent) against `GET /2.0/repositories/{workspace}/{repo}/commit/{sha}/statuses` (or the pipeline endpoint 3h-5 expects), mapping Bitbucket build states → the `CiStatus` type 3h-5 defined. **DEFERRED** — grep for `readCheckRuns`/`CiStatus`/`supportsCiStatusReads` returned 0 matches; defining the port here would collide with 3h-5 on merge.
  - [ ] Flip `supportsCiStatusReads=true` in `bitbucketDefaults()`; assert a green status advances and a red status feeds the 3h-5 investigation loop unchanged. **DEFERRED** — `bitbucketDefaults()` deliberately ships without the CI flag until 3h-5 lands.

- [x] **Task 8 — Tests + parity contract** (AC7/AC8)
  - [x] Bitbucket mock-vs-real parity contract (mirror `RepositoryHostAbstractionFoundationContract`, `@Tag("foundation-gate")`) using `MockRestServiceServer` for the real arm; capability contract drift test; `verifyConnectivity` reachable/auth-fail/unreachable; PR create(idempotent)/update/comment happy paths; profile-wiring contract (mirror `GitHubProfileWiringContractTest`).
  - [x] `application.integration.repohost` / adapter coverage ≥80%.

- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at every adapter public method entry/exit (`bitbucket_real <operation> ...` / `bitbucket_mock <operation> ...` prefixes, mirror the GitHub markers), every `RepositoryHostAdapterException` raise site, the connectivity probe outcome branches, and the doctor probe resolution branch.
  - [x] Parameterized logging only (`log.info("...", arg)`), never concatenation.
  - [x] Levels: `INFO` normal lifecycle (read hit/not_found, PR created/updated/idempotent, probe ok), `WARN` recoverable anomalies (rate-limited, permission denied, unreachable, doctor misconfigured), `ERROR` only for unhandled failures. `DEBUG` for network-fault stack traces.
  - [x] Carry the relevant context: `repoRef`/`prRef` (opaque token, safe), `durationMs`, resolution outcome, `connectorKind`, project id where present. **Never log** the app password / access token / `credentialOverride` / `Authorization` header — ids/lengths only.
  - [x] Pin ≥1 log assertion per new branch (ListAppender / `OutputCaptureExtension`) — especially the "secret never appears" invariant in credential/doctor paths.

## Dev Notes

### §0 — CRITICAL: AC2 depends on 3h-5, which is `backlog` (read first)

`readCheckRuns`, `CiStatus`, and `RepositoryHostCapabilities.supportsCiStatusReads` **do not exist anywhere on the branch today** — verified by grep (zero matches). They are **3h-5's** deliverable (`3h-5-ci-build-error-investigation-github-actions`, currently `backlog`). The epic sequences 3i-3 *after* all of Epic 3h precisely so 3h-5's CI-checks port is in place. Therefore:

- This story's **repo-host foundation (ACs 1, 3, 4, 5, 6, 7-sans-CI, 8) is fully buildable today** on stable ports (3-33 `RepositoryHostAdapter`, 3c-3 resolver, 3c-5 credentials, 3c-8 connectivity, 3c-10 doctor).
- **AC2 (Pipelines CI reader) is hard-gated on 3h-5.** Do NOT define the CI-checks port in this story — 3h-5 owns `readCheckRuns` + `CiStatus` + `supportsCiStatusReads`, and duplicating it here guarantees a merge collision. If 3h-5 is merged, bind AC2 to its exact surface; if not, split AC2 forward (record in `deferred-work.md`) and ship the rest. See Task 0.
- `RepositoryHostCapabilities` is a **5-field record today** (`domain/integration/repohost/RepositoryHostCapabilities.java`) with **no `supportsCiStatusReads`**. Expect 3h-5 to have widened it; if it didn't, AC2 widens it (and every `githubDefaults()`/existing-caller site fans out — mirror the 6-field constructor change everywhere `new RepositoryHostCapabilities(...)` appears).

### §1 — This is a "second real repository host" — the GitHub adapter is the template

Story 3-33 extracted the vendor-neutral `RepositoryHostAdapter` port (ADR 0008) from the GitHub-shaped adapter; `GitLabRepositoryHostStubAdapter` proved the second-kind seam with a *degraded stub*. Bitbucket **promotes that stub shape to a real impl**. Read these before writing code:
- `docs/adr/0008-repository-host-abstraction.md` + `docs/integrations/repository-host-extension-contract.md` — the canonical "add a new host" contract.
- Real template: `adapters/integration/repohost/github/GitHubRealAdapter.java` (redaction-on-egress, rate-limit inspection, HTTP→category `classify` ladder, opaque-ref parsing, idempotent PR create).
- Mock template: `adapters/integration/repohost/github/GitHubMockAdapter.java` + `GitHubMockScenarioRegistry.java` (deterministic, scenario-keyed, `SYNTHETIC_CREATED_AT`, adversarial injection).
- Stub precedent: `adapters/integration/repohost/gitlab/GitLabRepositoryHostStubAdapter.java`.
- Port: `application/integration/repohost/RepositoryHostAdapter.java` — 9 methods: `connectorKind`, `getRepositoryByRef`, `getPullRequestByRef`, `getBranchByRef`, `createPullRequest(repo, sourceBranch, targetBranch, title, body)`, `updatePullRequest(ref, body)`, `commentOnPullRequest(ref, body)`, `getCapabilities`, `verifyConnectivity(repo, credentialOverride)`.
- Domain types reused verbatim (all in `domain/integration/repohost/`): `Repository`, `PullRequest`, `Branch`, `RepositoryRef`, `PullRequestRef`, `CommentResult`, `RepositoryHostCapabilities`. Failures throw `RepositoryHostAdapterException(IntegrationFailureCategory, msg)`.

**Push is already solved.** `RepositoryWorkspaceService` clones/pushes over plain git HTTPS via `GitCommandPort` — Bitbucket branch naming `deliveryline/{slug}/stage-{runId}` is already `:`-free/`/`-separated and Bitbucket-safe. The adapter only resolves **metadata + PRs + CI**, never git transport (AC1). Bitbucket refs are opaque `RepositoryRef`/`PullRequestRef` tokens — only the Bitbucket impl parses their internal shape.

### §2 — Two independent selectors (do not confuse them)

1. **Spring profile** (`bitbucket-mock` / `bitbucket-real`) gates which *bean* is active — this is the load-bearing activation, exactly like GitHub. A boot-time exclusivity guard rejects both-active.
2. **Per-project `ConnectorKind`** (`repo_host_kind` column → `ProjectConnectorResolver.resolveRepositoryHost(project)`) selects the adapter *at request time* by its `connectorKind()`. This is why AC3's enum widening is mandatory — the resolver indexes `List<RepositoryHostAdapter>` into `Map<ConnectorKind, …>`.
3. The global `deliveryline.integration.repo-host.kind` (`RepositoryHostProperties`) only drives the classpath fail-fast in `GitHubConfiguration.assertSupportedRepositoryHostKind` — relax it for `bitbucket`. Per-vendor keys stay at `deliveryline.bitbucket.*` (the `deliveryline.integration.repo-host.<kind>.*` rename is out of scope per ADR 0008).

### §3 — `ConnectorKind` widening fan-out (AC3) — exact files

Enforced by `RegistryContractTest.projectStatusAndConnectorKindStayAlignedWithSqlChecksAndApiManifest` (compares enum ↔ both DB CHECKs ↔ placeholder JSON). Edit exactly:
1. `domain/registry/ConnectorKind.java` — add `BITBUCKET("bitbucket")`.
2. New `V<next>__widen_connector_kind_to_bitbucket.sql` — mirror `V18` (drop+re-add both CHECKs with `bitbucket`).
3. `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` — add `"bitbucket"` to `connectorKinds`.
Derived / self-enforcing (NO edit): `DomainRegistry` (`valuesOf(ConnectorKind.values())`), `PersistedRegistryValues` (delegates to `fromValue`), `FlywaySchemaContractTest` (structural — accepts known, rejects `bogus`; still passes). Flyway replay-safety covered by `flywayMigrateIsReplaySafeAndChecksumStable`.

### §4 — Credential store (AC4): reuse, don't extend

`ProjectCredentialService.setCredential(projectId, ConnectorRole.REPO_HOST, plaintext)` already stores any repo-host secret encrypted (V17 `project_credentials`, one active row per `(project, role)`, archived-on-rotate). Bitbucket reuses `ConnectorRole.REPO_HOST` verbatim — **no store/schema change**. At use time: `ProjectConnectorResolver.resolveConnectorSecret(project, "repo_host")` → passed as `credentialOverride` into `verifyConnectivity`; the request interceptor prefers the override over host-env, reads it at request time, never logs it. REST entry point is the existing `POST /{projectId}/credentials/{role}` (`ProjectController`) — write-only, secret never returned.

### §5 — Redaction two-gates (AC4) — the trap that reds CI silently

Both gates live around `RedactionPolicyServiceContractTest`:
- **Gate A (manifest):** `src/test/resources/redaction-fixtures/fixtures-manifest.json` — add fixture entries with `placeholder` + `minimumClassification: shareable-redacted` + `forbiddenSnippets` (GitHub precedent: `github-token.txt`, `github-pat.txt`, `project-credential-github-token.json`).
- **Gate B (hardcoded corpus `Set`):** `RedactionPolicyServiceContractTest.fixtureManifestMustCoverEveryCorpusFile()` has a hardcoded `Set<String> expected` — add the new fixture filenames or `assertEquals(expected, declared)` reds. **This is the trap — edit both gates.**
- Third guard: `RedactionAdversarialFoundationContract` fails any fixture file physically present but not manifested with non-empty `forbiddenSnippets`.

Bitbucket app passwords used as HTTP Basic are already scrubbed by the existing `AUTHORIZATION_HEADER` pattern in `SensitivePayloadAnalyzer` — a fixture proving that suffices for AC4. A dedicated `BITBUCKET_TOKEN` category/placeholder is optional (only for Atlassian `ATATT…`/`ATCTT…` prefix tokens).

### §6 — Doctor `checksRun` fan-out (AC6) — exact literals

- Production: `DoctorService` (`CHECK_BITBUCKET` constant + `STATIC_ORDER` list [18 entries today] + `switch` arm), `DoctorProbePort.probeBitbucket()`, `DoctorProbeAdapter.probeBitbucket()` (mirror `probeGitHubAuth`).
- **The ONE hardcoded count:** `DoctorLoggingContractTest` `checksRun=18` → `19` (~line 90). The `checksRun=1`/`checksRun=2` lines there assert hand-built reports — leave them.
- **Hidden trap:** every explicit `when(probes.probe*())` stub block must add `probeBitbucket()` or the switch arm returns `null` → NPE. Sites: `DoctorLoggingContractTest` inline block; `DoctorServiceTest` `stubAllProbesPass()` + 4 other stub blocks. `DoctorServiceTest`'s size assertion is dynamic (`STATIC_ORDER.size()`) — self-updates.

### §7 — Failure categories, capabilities, ArchUnit

- `IntegrationFailureCategory`: add `BITBUCKET_*` (recommended). **LOW fan-out** — auto-cataloged via `DomainRegistry.valuesOf(IntegrationFailureCategory.values())`; **no** SQL CHECK, **no** placeholder manifest, **no** OpenAPI/DomainErrorCode three-site fan-out. Never rename existing values.
- `RepositoryHostCapabilities`: add `bitbucketDefaults()` (declare Bitbucket's real support honestly — draft-PR support differs from GitHub). Only add `supportsCiStatusReads` field/flag when AC2/3h-5 lands (§0).
- **ArchUnit needs zero catalog edits.** `REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT`, `REPOSITORY_HOST_ADAPTER_PORT_RESIDES_IN_APPLICATION`, and `REPOSITORY_HOST_IMPLS_RESIDE_IN_ADAPTERS_REPOHOST` all use recursive `..` wildcards — `adapters.integration.repohost.bitbucket` passes automatically **iff** (a) the port stays vendor-neutral (keep Bitbucket DTOs/RestClient in the adapter package) and (b) you don't import the `...repohost.github` sub-package (intra-slice cycle risk). No hardcoded ArchUnit rule count exists. Note: ArchUnit runs in **Failsafe**, not Surefire — verify via the failsafe lifecycle, not `mvnw test` alone.
- If a `BITBUCKET_TOKEN` redaction category is added, it fans out through the **T6 parity mirror chain**: `deliveryline-runner-contracts/.../redaction-policy.json` + `deliveryline-frontend/.../redaction-policy.generated.json` (regen script) + `RedactionPolicyParityContractTest` + runner-contracts `RedactionPolicyContractTest` + FE `redaction-policy-drift.test.ts`. Avoid unless the recognizable-prefix scrub is genuinely wanted.

### §8 — Scope boundaries & non-goals

- **Backend-only. No REST endpoint, no OpenAPI/`schema.d.ts` regen, no FE.** (Contrast 3i-2/3i-4 which add surfaces.) The doctor probe is CLI/diagnostics, not a new HTTP contract.
- Bitbucket **Issues** as a *ticket source* is out of scope — this story is repo-host only (a Bitbucket `TicketSourceAdapter` is a forward option).
- Do not touch the GitHub/GitLab adapters except the shared `RepositoryHostCapabilities` factory and (if 3h-5 widened it) the capabilities record constructor fan-out.
- Runner-contracts jar: if a new redaction category touches `deliveryline-runner-contracts`, `install` it (or `-am`) before a backend-only `mvnw test` — a stale `.m2` jar silently uses the old schema.

### Project Structure Notes

- New packages: `application.integration.bitbucket` (`BitbucketProperties`), `adapters.integration.repohost.bitbucket` (`BitbucketRealAdapter`, `BitbucketMockAdapter`, `BitbucketMockScenarioRegistry`, fixture types), `infrastructure.config.BitbucketConfiguration`. Fixtures under `src/main/resources/bitbucket-fixtures/`.
- Reused unchanged: `domain/integration/repohost/*`, `application/integration/repohost/RepositoryHostAdapter` + `RepositoryHostAdapterException` + `RepositoryHostProperties`, `ProjectConnectorResolver`, `ProjectCredentialService`/`ConnectorRole`, `ConnectivityResult`, `RedactionPolicyService`.
- Aligns with the ADR-0008 "one-interface, one-contract add" model — no orchestration/`RunnerBroker`/`WorkflowTransition` change.

### References

- Epic: [Source: _bmad-output/planning-artifacts/epic-03i-connector-expansion.md#Story 3i-3] and #Cross-Cutting Notes (FR82; 3h-5 CI-port dependency; three-doctor-probe checksRun trap; two-gates redaction).
- Port + ADR: [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/repohost/RepositoryHostAdapter.java], [Source: docs/adr/0008-repository-host-abstraction.md], [Source: docs/integrations/repository-host-extension-contract.md].
- GitHub template: [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/github/GitHubRealAdapter.java], [Source: .../github/GitHubMockAdapter.java].
- Registry fan-out: [Source: .../domain/registry/ConnectorKind.java], [Source: .../db/migration/V18__widen_connector_kind_to_gitlab.sql], [Source: .../test/resources/contracts/openapi/registry-api-schema-placeholders.json], [Source: .../test/.../contract/RegistryContractTest.java].
- Credentials + redaction: [Source: .../application/project/ProjectCredentialService.java], [Source: .../application/project/ProjectConnectivityService.java], [Source: .../test/resources/redaction-fixtures/fixtures-manifest.json], [Source: .../test/.../security/RedactionPolicyServiceContractTest.java].
- Doctor: [Source: .../application/diagnostics/DoctorService.java], [Source: .../adapters/diagnostics/DoctorProbeAdapter.java], [Source: .../test/.../cli/DoctorLoggingContractTest.java], [Source: .../test/.../diagnostics/DoctorServiceTest.java].
- Wiring: [Source: .../infrastructure/config/GitHubConfiguration.java], [Source: .../application/integration/github/GitHubProperties.java], [Source: .../application/integration/repohost/RepositoryHostProperties.java], [Source: .../main/resources/application.yml].
- Parity contract: [Source: .../test/.../foundation/RepositoryHostAbstractionFoundationContract.java], [Source: .../test/.../repohost/github/GitHubProfileWiringContractTest.java].

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context)

### Debug Log References

- **Task 0 gate:** `grep -rE "readCheckRuns|supportsCiStatusReads|\bCiStatus\b"` over `src/main/java` = **0 matches**; `3h-5-ci-build-error-investigation-github-actions` = `backlog` in sprint-status → AC2/Task 7 **split forward** (recorded in `deferred-work.md`).
- **Flyway head:** on-disk head is `V38__add_delivery_gate_and_push_mode.sql` (not the story's stale "V33") → new migration is **V39**.
- **Base-URL bug caught by tests:** initial adapter used a `/2.0`-suffixed base URL with absolute-path request URIs, which drops `/2.0` (Spring resolves an absolute path against the base host). Fixed by making the base URL host-only and carrying `/2.0` explicitly in every request path + the doctor/connectivity `.uri(...)` calls (mirrors the GitHub host-only base convention). This was a real production bug, not just a test artifact.
- **Two "unknown-kind" fixtures regressed:** `RepositoryHostConfigurationTest.unsupportedKindFailsFastAtBoot` and `ProjectManagementServiceTest.createProjectUnknownKindThrows` both used `bitbucket` as their intentionally-invalid kind — now valid; switched both to `gitea` (still unimplemented).

### Completion Notes List

Delivered the second real `RepositoryHostAdapter` kind (Bitbucket Cloud), at parity with the GitHub adapter, on the vendor-neutral 3-33 port (ADR 0008). **ACs 1/3/4/5/6/7/8 implemented; AC2 (Pipelines CI reader) split forward** — hard-gated on story 3h-5's CI-checks port which does not exist on the branch (Task 0 / Dev Notes §0).

- **AC1** — `BitbucketRealAdapter` (`@Primary @Profile("bitbucket-real")`, `@Qualifier("bitbucketRestClient")`) + `BitbucketMockAdapter`/`BitbucketMockScenarioRegistry` (`@Profile("bitbucket-mock")`, deterministic, 3 happy fixtures under `src/main/resources/bitbucket-fixtures/`). Opaque `workspace/repo` + `workspace/repo#id` ref parsing; redact-on-egress; idempotent PR create on `(source, destination)`; HTTP→`IntegrationFailureCategory` classify ladder (Bitbucket 429 rate-limit, 400-on-create → branch-protected). **Bitbucket Cloud has no draft-PR concept**, so — unlike GitHub — no `draft` flag is sent.
- **AC3** — `ConnectorKind.BITBUCKET` + `V39__widen_connector_kind_to_bitbucket.sql` (drop/re-add BOTH `ck_projects_*_kind` CHECKs) + `connectorKinds` placeholder; `RegistryContractTest` + `FlywaySchemaContractTest` green.
- **AC4** — reuses `ConnectorRole.REPO_HOST` (no store change). Redaction **two-gates** satisfied: `bitbucket-app-password.txt` (Basic header, scrubbed by the existing `AUTHORIZATION_HEADER` pattern) + `project-credential-bitbucket-token.json` (SECRET_FIELD key) added to BOTH the fixtures manifest (Gate A) AND the hardcoded corpus `Set` in `RedactionPolicyServiceContractTest` (Gate B); `RedactionAdversarialFoundationContract` auto-covers.
- **AC5** — `verifyConnectivity` returns a secret-free `ConnectivityResult`: reachable+authenticated (200), `unauthenticated` (401/403), repo-absent (404), could-not-verify (429/5xx), `unreachable` (I/O). Never throws a vendor exception across the port.
- **AC6** — `DoctorProbePort.probeBitbucket()` + `DoctorProbeAdapter.probeBitbucket()` (mirrors `probeGitHubAuth`, `GET /2.0/user`, profile-gated) + `DoctorService` `CHECK_BITBUCKET`/`STATIC_ORDER`/switch/remediation. `checksRun=19 → 20` in `DoctorLoggingContractTest` (19 was 3i-1's JIRA count, not the story's stale "18"); `probeBitbucket()` stubs added to the inline mock block + all 5 `DoctorServiceTest` stub blocks. New `DOCTOR_BITBUCKET_AUTH_FAILED` / `DOCTOR_BITBUCKET_TOKEN_MISSING` codes fanned out to all 3 sites (enum + `ProblemDetailsCatalog` + `problemTypeUris` placeholder) — these codes are NOT in `openapi.json`, so **no OpenAPI regen** (`OpenApiSnapshotContractTest` green).
- **AC7** — `RepositoryHostCapabilities.bitbucketDefaults()` (`supportsDraftPullRequests=false`, others true; `supportsCiStatusReads` intentionally absent until AC2/3h-5). Mock-vs-real parity contract `BitbucketRepositoryHostParityFoundationContract` mirrors Contract #15, **registered as Contract #28** in `FoundationGateVerificationTest` (the `*FoundationContract`-is-inert-unless-registered trap — verified it runs green via the foundation-gate delegate: `Tests run: 1, Failures: 0`). Wiring: `BitbucketConfiguration` (self-describing single-secret Basic/Bearer interceptor + mock/real exclusivity guard) + `RepositoryHostProperties.isBitbucket()/isSupported()` + relaxed `GitHubConfiguration.assertSupportedRepositoryHostKind`.
- **AC8** — unit tests for real (classification ladder, redaction-on-egress, idempotent create, connectivity) + mock (determinism, idempotency, adversarial) + profile-wiring + doctor probe + config; `BitbucketProfileWiringContractTest` pins "no network under bitbucket-mock". **Backend-only: no OpenAPI/`schema.d.ts` regen, no FE.**

**Verification:** `mvn -pl deliveryline-backend -Pfoundation-gate verify` = **BUILD SUCCESS** (full unit suite + all foundation-gate contracts incl. Contract #28, ArchUnit, OpenAPI snapshot, ProblemDetails coverage, Registry/Flyway/Redaction-adversarial). Plain `mvn -pl deliveryline-backend verify` = **BUILD SUCCESS**, "All coverage checks have been met." (JaCoCo floors), plus spotbugs (High-threshold; the 2 `CT_CONSTRUCTOR_THROW` MEDIUM findings match the GitHub twins and are non-failing), checkstyle, spotless. `spotless:apply` run; verified no mojibake introduced.

### File List

**New (production):**
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/bitbucket/BitbucketProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/BitbucketConfiguration.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/bitbucket/BitbucketRealAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/bitbucket/BitbucketMockAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/bitbucket/BitbucketMockScenarioRegistry.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/bitbucket/BitbucketMockScenario.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/bitbucket/BitbucketFixture.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/repohost/bitbucket/BitbucketFixtureDocument.java`
- `deliveryline-backend/src/main/resources/db/migration/V39__widen_connector_kind_to_bitbucket.sql`
- `deliveryline-backend/src/main/resources/bitbucket-fixtures/bitbucket-feature-low-risk.json`
- `deliveryline-backend/src/main/resources/bitbucket-fixtures/bitbucket-bug-fix.json`
- `deliveryline-backend/src/main/resources/bitbucket-fixtures/bitbucket-docs.json`

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/repohost/bitbucket/BitbucketRealAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/repohost/bitbucket/BitbucketMockAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/repohost/bitbucket/BitbucketProfileWiringContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorBitbucketProbeTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/config/BitbucketConfigurationTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/BitbucketRepositoryHostParityFoundationContract.java`
- `deliveryline-backend/src/test/resources/redaction-fixtures/bitbucket-app-password.txt`
- `deliveryline-backend/src/test/resources/redaction-fixtures/project-credential-bitbucket-token.json`

**Modified (production):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/ConnectorKind.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/IntegrationFailureCategory.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/integration/repohost/RepositoryHostCapabilities.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/repohost/RepositoryHostProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/GitHubConfiguration.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java`
- `deliveryline-backend/src/main/resources/application.yml`

**Modified (test):**
- `deliveryline-backend/src/test/resources/application.yml`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`
- `deliveryline-backend/src/test/resources/redaction-fixtures/fixtures-manifest.json`
- `deliveryline-backend/src/test/java/org/dradgo/application/security/RedactionPolicyServiceContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/infrastructure/config/RepositoryHostConfigurationTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectManagementServiceTest.java`

**Modified (artifacts):**
- `_bmad-output/implementation-artifacts/deferred-work.md` (AC2/Task 7 split-forward entry)
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

## Change Log

- 2026-07-10 — dev-story (Opus 4.8 [1m]): Implemented FR82 Bitbucket repository host at GitHub parity (ACs 1/3/4/5/6/7/8). AC2 (Pipelines CI reader) split forward — hard-gated on 3h-5 (`backlog`), recorded in `deferred-work.md`. Full `mvn verify` green (unit + foundation-gate + coverage). Status: in-progress → review.

## Review Findings

Adversarial code review (bmad-code-review, 2026-07-10, Opus 4.8 [1m]) — 3 parallel layers (Blind Hunter / Edge Case Hunter / Acceptance Auditor). Two findings were confirmed by live reproduction (`mvn verify` + a throwaway probe IT); false positives from diff-construction artifacts were dismissed. AC-conformance otherwise verified correct (ACs 1/3/4/5/6/7/8; AC2 split-forward clean).

- [x] [Review][Patch] **FIXED** — `rateLimitWarnThreshold` is dead config with a false doc contract — bound + documented ("WARN when `X-RateLimit-Remaining` drops below this; raise `BITBUCKET_RATE_LIMITED` at 0") in both `BitbucketProperties` and `application.yml`, but `BitbucketRealAdapter` never inspects rate-limit headers (only reacts to hard HTTP 429). **Decision (2026-07-10): remove the knob + the misleading comments** — keep behavior honest (429-only); proactive detection is a forward option. [`BitbucketProperties.java`, `application.yml`]
- [x] [Review][Patch] **CRITICAL — FIXED** (dropped `@Primary` from `BitbucketMockAdapter`; verified by a both-mocks-active boot IT: context now starts and resolves to `GitHubMockAdapter`) — Two `@Primary RepositoryHostAdapter` beans co-activate in the `local`/`test`/`demo` profile groups → app context fails to start under those runtime profiles. `application.yml` adds `bitbucket-mock` to the same default group that already carries `github-mock`, and `BitbucketMockAdapter` copies GitHub's `@Primary`. Reproduced: `NoUniqueBeanDefinitionException: more than one 'primary' bean found among candidates: [bitbucketMockAdapter, gitHubMockAdapter, gitLabRepositoryHostStubAdapter]` failing `repositoryWorkspaceService` ctor param 1. CI stayed green only because tests activate profiles explicitly (`@ActiveProfiles({"test","linear-mock","github-mock"})`) and never co-activate both mocks — the profile *group* does not expand under `@ActiveProfiles`, so the suite structurally cannot catch it; a real `spring.profiles.active=local/demo` launch does expand it and breaks. Fix: drop `@Primary` from `BitbucketMockAdapter` (github-mock stays the sole default primary; bitbucket-kind projects resolve per-project via `ProjectConnectorResolver`). [`BitbucketMockAdapter.java:54` + `application.yml:31-33`]
- [x] [Review][Patch] **MEDIUM — FIXED** (replaced raw NUL with the ` ` escape; file is text again, compiles) — Raw NUL byte embedded in `BitbucketMockScenarioRegistry.java` source. `BRANCH_KEY_SEPARATOR = "<0x00>"` is written as a literal NUL byte instead of the `" "` escape the GitHub twin uses (`GitHubMockScenarioRegistry.java:43`). This makes the `.java` file binary to git (unreviewable diffs) and is the exact mojibake/encoding hazard this repo has been bitten by. It compiled by luck. Fix: replace the raw NUL with `" "`. [`BitbucketMockScenarioRegistry.java:44`]
- [x] [Review][Patch] **LOW — FIXED** (added `Objects.requireNonNull(title)` guard) — `createPullRequest` does not guard a null `title`: `redact(null)` returns null → `payload.put("title", null)` serializes `"title":null` → Bitbucket 400 → the create-path 400 branch maps it to `BITBUCKET_BRANCH_PROTECTED`, a misleading category for a missing-title bug. Add a null/blank title guard (or classify create-400 more precisely). [`BitbucketRealAdapter.java` ~1570-1617]
- [x] [Review][Patch] **LOW — FIXED** (added `bbqlQuote()` backslash-escaping of branch names) — Idempotency pre-check builds a BBQL `q` filter by interpolating raw branch names inside double-quotes with no BBQL escaping (only URL-encoding). A branch name containing `"` yields a malformed query → 400 → misclassified `BITBUCKET_BRANCH_PROTECTED`, failing the whole create. Escape the quotes or fall back on parse failure. [`BitbucketRealAdapter.java` ~1832-1837]
- [x] [Review][Defer] **LOW, non-blocking** — `createPullRequest` with a blank `targetBranch` against a repo with a null `mainbranch` (empty/fresh repo) throws `BITBUCKET_NETWORK_FAILURE` via `requireText(...)`, a misleading category for a repo-state precondition. Deferred: rare edge (can't PR an empty repo anyway) and no more-accurate existing `IntegrationFailureCategory`. [`BitbucketRealAdapter.java` `createPullRequest`]
- [x] [Review][Defer] **LOW, non-blocking** — `verifyConnectivity` folds an uncaught 4xx (e.g. 400, which proves the credential WAS accepted) into the generic branch with `authenticated=false`. Deferred: defensible conservative default — the message ("unexpected response") does not assert an auth failure, and the adapter cannot positively confirm auth from an unexpected 4xx. [`BitbucketRealAdapter.java` `verifyConnectivity` final catch]
- [x] [Review][Defer] **LOW, non-blocking** — Doctor probe over-broad classification (non-401/403 → `DOCTOR_BITBUCKET_AUTH_FAILED`; client-unavailable → `DOCTOR_BITBUCKET_TOKEN_MISSING`). Deferred: the dev documented these as deliberate operator-facing choices; refining the remediation copy is a UX judgment call, not a correctness defect. [`DoctorProbeAdapter.java` `probeBitbucket`]

**Dismissed as noise (4):** (1) "all new files under wrong source root `src/` vs `deliveryline-backend/src/`" — false positive, an artifact of how the review diff was assembled (untracked-file hunks generated from inside the module dir); the files are correctly located and compiled/ran. (2) `bitbucketDefaults()` field-order — verified correct (`false` = `supportsDraftPullRequests`). (3) Javadoc says "GET /user" vs code `/2.0/user` — trivial doc nit, behavior correct. (4) Mock-vs-real idempotency-key destination semantics (`""` vs resolved `main`) — mock-internal, no assertion/behavior depends on it.
