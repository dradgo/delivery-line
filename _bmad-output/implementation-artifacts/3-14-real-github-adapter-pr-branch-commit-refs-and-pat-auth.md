# Story 3.14: Real GitHub Adapter — PR/Branch/Commit Refs + PAT Auth

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer + pilot installer using real GitHub repositories,
I want a real `GitHubRealAdapter` implementing the already-shipped `GitHubAdapter` port via the GitHub REST API v3 with personal-access-token (PAT) authentication, idempotent PR creation/update by repo+branch, redaction-on-egress, and rate-limit awareness,
so that pilots running real implementation work can link governed runs to actual GitHub PRs (FR40, NFR17), AR18 is satisfied for production-style usage, and the mock/real seam created in story 3.13 is finally closed by a passing parity test.

## Context & Why This Story Exists

This is the **GitHub twin of the already-shipped Real Linear Adapter (story 1.14)** and the **real-implementation half of story 3.13's mock**. The port (`GitHubAdapter`), the six domain-shaped methods, the domain records (`GitHubRepository`, `GitHubPullRequest`, `GitHubBranch`), the typed exception (`GitHubAdapterException`), the full GitHub `IntegrationFailureCategory` set, the `github-real` profile constant, and the profile-exclusivity guard **already exist** — story 3.13 built them deliberately so 3.14 needs to touch **zero** of the port surface. Your job is narrow and well-scaffolded:

1. **`GitHubRealAdapter`** (`@Component @Profile("github-real")`) implementing the 6 port methods against GitHub's REST API.
2. **`GitHubProperties`** + a **real `RestClient` bean** in the existing `GitHubConfiguration`.
3. **Redaction-on-egress** on the three write methods (`createPullRequest`, `updatePullRequest`, `commentOnPullRequest`).
4. **Idempotent PR creation**, **rate-limit header awareness**, **HTTP→`IntegrationFailureCategory` mapping**.
5. **A doctor `github-real` auth probe** (AC9) — the one piece with a non-trivial blast radius (see Decision D4).
6. **Two ADRs** (REST-vs-GraphQL, write-scope) — but **NOT at the epic's stale numbers** (see Decision D1).
7. **Enable the parity test** that 3.13 left as a `@Disabled` stub (AC8).

**Build it by mirroring `LinearRealAdapter` almost line-for-line** — same `RestClient` + `SimpleClientHttpRequestFactory` setup, same request-time-token interceptor, same `catch (HttpClientErrorException.* | HttpServerErrorException | ResourceAccessException …)` classification ladder, same `@Profile("…-real")` wiring, same `MockRestServiceServer.bindTo(builder)` unit-test harness. The Linear real adapter is your single most important reference.

## Acceptance Criteria

> Criteria are the epic's verbatim ACs (epic-03-agent-execution.md §"Story 3.14", lines 277–295) with implementation-binding clarifications added inline. Where the epic wording is loose or stale relative to what story 3.13 actually shipped, the **bold parenthetical** is the binding interpretation for this repo.

1. **Given** the GitHub adapter slice, **Then** `GitHubRealAdapter` (`@Component @Profile("github-real")` in **`org.dradgo.adapters.integration.github`** — alongside the mock) implements the existing `GitHubAdapter` port (story 3.13) using GitHub's **REST API v3** via Spring **`RestClient`** (NOT the `org.kohsuke.github` SDK — see Decision D2; choice documented in the REST-vs-GraphQL ADR per Decision D1) with a **version-pinned** HTTP path (`Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`).
2. **Given** authentication, **Then** the adapter authenticates via PAT read from the **`GITHUB_TOKEN`** env var (bound through `GitHubProperties`, mirror `LinearProperties.apiToken` from `LINEAR_API_TOKEN`) — **never logged, never embedded in URLs, never persisted in DB or artifacts**; the token is set as `Authorization: Bearer <token>` **at request time inside a request interceptor** (mirror `linearRestClient`) so rotation is observed without a context refresh. Auth failures (401) classify as **`GITHUB_AUTH_FAILED`**.
3. **Given** the 6 `GitHubAdapter` port methods, **Then** each is implemented against the real API: read methods map straightforwardly (`getRepositoryByRef` → `GET /repos/{owner}/{repo}`; `getPullRequestByRef` → `GET /repos/{owner}/{repo}/pulls/{number}`; `getBranchByRef` → `GET /repos/{owner}/{repo}/branches/{branch}`); **`createPullRequest`** → `POST /repos/{owner}/{repo}/pulls` with **`draft: true`** (PRs land as drafts pending governed review approval); **`updatePullRequest`** → `PATCH /repos/{owner}/{repo}/pulls/{number}`; **`commentOnPullRequest`** → `POST /repos/{owner}/{repo}/issues/{number}/comments`. **All three write methods pass `body`/`title` through `RedactionPolicyService` (story 1.10) before sending** (see AC6 + Decision D3).
4. **Given** idempotent PR creation per AR18 + NFR18, **Then** before creating, the adapter checks whether a PR for the same `(repo, sourceBranch, targetBranch)` already exists via `GET /repos/{owner}/{repo}/pulls?head={owner}:{sourceBranch}&base={targetBranch}&state=open` — **if yes, returns the existing PR ref (no duplicate POST); if no, creates and returns the new PR ref.** Retry-after-failure therefore naturally finds and reuses the existing PR. (The deterministic branch convention that *guarantees* a consistent `sourceBranch` per run lands in story 3.9 — **do NOT import or depend on `RepositoryWorkspaceService` here**; 3.9 builds after 3.14. The adapter only needs the `(repo, sourceBranch, targetBranch)` it is handed.)
5. **Given** rate-limit awareness, **Then** the adapter inspects GitHub's **`X-RateLimit-Remaining`** + **`X-RateLimit-Reset`** response headers and: (a) logs **WARN** when remaining drops below a configurable threshold (`deliveryline.github.rate-limit-warn-threshold`, default **100**), (b) raises **`GITHUB_RATE_LIMITED`** when remaining=0 (or on a `403`/`429` carrying `X-RateLimit-Remaining: 0`) with a **`details.resetAtSeconds`** field sourced from `X-RateLimit-Reset`, (c) classification only — orchestration's "treat as retryable / pause not crash" behavior is its own concern and is **not** wired here.
6. **Given** redaction-on-egress for write methods, **Then** `title`/`body` pass through `RedactionPolicyService` before sending — a contract test verifies that posting a comment containing a fixture secret pattern (e.g. an API-key-shaped string) results in a **redacted** comment in the outbound HTTP request body (asserted via `MockRestServiceServer` request matcher). The default policy is **redact-and-send**; the **refuse-with-`EGRESS_SECRET_DETECTED`** alternative is documented in the write-scope/security ADR but only taken if redaction would alter semantics significantly (see Decision D3 — verify whether an `EGRESS_SECRET_DETECTED` code already exists before referencing it; prefer redact-and-send to avoid adding a new `DomainErrorCode`).
7. **Given** failure classification, **Then** GitHub HTTP failures map to the **already-existing** `IntegrationFailureCategory` GitHub values (story 3.13 added all 8): `GITHUB_AUTH_FAILED` (401), `GITHUB_PERMISSION_DENIED` (403 non-rate-limit), `GITHUB_REPO_NOT_FOUND` / `GITHUB_PR_NOT_FOUND` (404, by operation), `GITHUB_RATE_LIMITED` (remaining=0 / 429), `GITHUB_NETWORK_FAILURE` (`ResourceAccessException` / 5xx), `GITHUB_API_VERSION_INCOMPATIBLE` (415 / version-rejection), `GITHUB_BRANCH_PROTECTED` (422 on protected-branch PR create) — **never a generic unclassified error**. (Do **NOT** re-add enum values — they exist. See Decision D5.)
8. **Given** mock-vs-real parity (story 3.13 AC10 reciprocal), **Then** the **`@Disabled` parity stub left in `FoundationGateVerificationTest` Contract #11 is now implemented and enabled**: a parity test runs the same scenario sequence against `GitHubMockAdapter` and `GitHubRealAdapter` (real against `MockRestServiceServer`-stubbed HTTP responses matching the fixture data) and asserts **domain-result equivalence** — DTO content differs, but typed shape + `IntegrationFailureCategory` outcomes match. The **live-repo** variant is gated behind a **`gh-real-tests`** profile (skipped in PR CI; runs nightly per story 3.35 AC3).
9. **Given** doctor (story 1.16) integration, **Then** when the `github-real` profile is active, doctor probes **`GET /user`** (cheap auth check) and reports **PASS** / **`DOCTOR_GITHUB_AUTH_FAILED`** / **`DOCTOR_GITHUB_TOKEN_MISSING`** accordingly. When `github-real` is **inactive** (the default — mock profile), the probe reports a **PASS "not-applicable"** result and makes **no network call**. (This is the highest-blast-radius AC — adding a probe extends the `DoctorProbePort` interface + `DoctorProbeAdapter` + `DoctorService` switch/order + **two new `DomainErrorCode`s**. See Decision D4 — the `DomainErrorCode` "three-sites" rule applies.)
10. **Given** the write-scope ADR (Decision D1 number, **not** the epic's `0005`), **Then** it documents the minimum PAT scopes a pilot installer must grant — e.g. `repo` (private-repo linkage) or `public_repo` (public only); `pull_requests:write` for create/comment; branch-push capability noted as required by story 3.9's `captureAndPush` (downstream) — so installers grant exactly what's needed, no over-scoping.
11. **Given** the test suite, **Then**: (a) **unit tests with a mocked HTTP layer** (`MockRestServiceServer.bindTo(RestClient.builder())`, mirror `LinearRealAdapterUnitTest`) cover failure classification for every category in AC7, rate-limit threshold WARN + remaining=0 raise, redaction-on-egress (AC6), idempotent PR creation (existing-PR-found ⇒ no POST), and the request-time `Authorization` header; (b) the **parity test** (AC8); (c) a **`GitHubConfiguration` real-RestClient bean test** (bean present + token-missing fail-fast under `github-real`); (d) the **doctor probe test** (AC9 PASS / token-missing / auth-failed / not-applicable); (e) **live-repo integration** against a documented test repository gated behind `gh-real-tests` (skipped in PR CI to avoid rate-limit cost).

## Tasks / Subtasks

- [x] **Task 1 — `GitHubProperties` + real `RestClient` bean** (AC: 1, 2)
  - [x] Create `org.dradgo.application.integration.github.GitHubProperties` as a constructor-bound `@ConfigurationProperties("deliveryline.github")` record. **Mirror `application/integration/linear/LinearProperties.java` exactly** (application package — NOT `infrastructure.config` — so the "application must not depend on infrastructure" ArchUnit rule stays clean). Fields: `token` (from `${GITHUB_TOKEN}`), `baseUrl` (default `https://api.github.com`), `apiVersion` (default `2022-11-28`), `rateLimitWarnThreshold` (default `100`), `Timeout(connectMs, readMs)`. Add `@JsonIgnore` on `token()` and a `toString()` that prints `token=<redacted>` (copy the Linear redaction pattern, lines 63–84).
  - [x] In the **existing** `org.dradgo.infrastructure.config.GitHubConfiguration` (already holds `MOCK_PROFILE`/`REAL_PROFILE` constants + `assertExclusiveGitHubProfile`), add `@Bean(name = "gitHubRestClient") @Profile(REAL_PROFILE)` building a `RestClient` via `SimpleClientHttpRequestFactory` (connect/read timeouts from `GitHubProperties.timeout()`), default headers `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: {apiVersion}`, `User-Agent: DeliveryLine/1.0 (github-real)`, and a **request interceptor** that sets `Authorization: Bearer {token}` read at request time. **Fail fast** with `IllegalStateException` if `token` is blank when the `github-real` profile activates (mirror `linearRestClient` lines, which throw when `apiToken` is blank). Register `GitHubProperties` for binding the **same way `LinearProperties` is registered** (find and mirror its `@EnableConfigurationProperties`/`@ConfigurationPropertiesScan` site) — see Decision D6 (test-yaml trap avoidance).
- [x] **Task 2 — `GitHubRealAdapter` read methods** (AC: 1, 3, 7)
  - [x] `@Component @Profile("github-real")` in `org.dradgo.adapters.integration.github`; constructor injects `@Qualifier("gitHubRestClient") RestClient`, `GitHubProperties`, and `RedactionPolicyService`. Mirror `LinearRealAdapter`'s header/ctor shape.
  - [x] Parse a `repoRef` into `(owner, repo)` and a `prRef` into `(owner, repo, number)` with a small private parser (mirror Linear's private `parseTicketRef` / `ParsedTicketRef` — keep parsing private to the adapter, never on the port). Document the canonical ref formats you accept (e.g. `owner/repo` and `owner/repo#number`) in the class Javadoc.
  - [x] Implement `getRepositoryByRef`, `getPullRequestByRef`, `getBranchByRef` → map JSON (`JsonNode`/`ObjectMapper`, as Linear does) to the domain records. **404 ⇒ `Optional.empty()` for genuine absence is the port contract for reads** — BUT note the epic couples some 404s to categories: a 404 on a *repo lookup that the caller asserted exists* is an `Optional.empty()`; the **failure-category** path is for write/validation contexts. Match story 3.13's Decision D2 split: ordinary "not seeded/found" ⇒ `Optional.empty()`; only the explicit fatal conditions throw. Keep read-method 404 ⇒ `empty()`.
- [x] **Task 3 — Write methods + redaction-on-egress + idempotent PR create** (AC: 3, 4, 6)
  - [x] `createPullRequest(repoRef, branch, title, body)`: (1) **idempotency probe** `GET …/pulls?head={owner}:{branch}&base={targetBranch}&state=open` — if a PR exists, map + return it (no POST), log `WARN … resolution=idempotent_existing`; (2) else **redact** `title` + `body` via `RedactionPolicyService`, `POST …/pulls` with `{title, head, base, body, draft:true}`, return the created PR. Decide `targetBranch`: default to the repo's `default_branch` (from `GET /repos`) unless a target is configured — document the choice. 422 with a protected-branch message ⇒ `GITHUB_BRANCH_PROTECTED`.
  - [x] `updatePullRequest(prRef, body)`: redact `body`, `PATCH …/pulls/{number}`, return updated PR.
  - [x] `commentOnPullRequest(prRef, body)`: redact `body`, `POST …/issues/{number}/comments`. (Real GitHub does not dedupe comments — the mock does, for parity; the real adapter's idempotency story is the **PR**, not comments. If you want comment idempotency, embed a stable HTML-comment marker `<!-- dl-fingerprint:{hash} -->` and skip posting if an existing comment already carries it — optional, document if implemented.)
  - [x] **Redaction call shape:** use `redactionPolicyService.redact(body, "shareable-redacted")` and send `result.sanitizedText()`. Verify the exact classification value string the service expects against `RedactionPolicyService` + the classification registry before hardcoding (see Decision D3).
- [x] **Task 4 — Rate-limit awareness + HTTP failure classification ladder** (AC: 5, 7)
  - [x] Use `RestClient … .retrieve().toEntity(String.class)` (or an exchange) so you can read **response headers**. After every call, inspect `X-RateLimit-Remaining`/`X-RateLimit-Reset`: WARN below threshold; if `0`, raise `GITHUB_RATE_LIMITED` with `details.resetAtSeconds`.
  - [x] Classification ladder (mirror `LinearRealAdapter.executeGraphQL` catch-cascade, lines ~314–358): `HttpClientErrorException.Unauthorized` ⇒ `GITHUB_AUTH_FAILED`; `Forbidden` ⇒ inspect rate-limit headers (remaining=0 ⇒ `GITHUB_RATE_LIMITED`, else `GITHUB_PERMISSION_DENIED`); `TooManyRequests`(429) ⇒ `GITHUB_RATE_LIMITED`; `NotFound`(404) ⇒ `GITHUB_REPO_NOT_FOUND` or `GITHUB_PR_NOT_FOUND` **by operation**; `UnsupportedMediaType`(415)/version rejection ⇒ `GITHUB_API_VERSION_INCOMPATIBLE`; `UnprocessableEntity`(422) protected-branch ⇒ `GITHUB_BRANCH_PROTECTED`; `HttpServerErrorException`(5xx) + `ResourceAccessException`(I/O) ⇒ `GITHUB_NETWORK_FAILURE`. Everything throws `GitHubAdapterException(category, msg, cause)`.
- [x] **Task 5 — Doctor `github-real` auth probe** (AC: 9) — **highest blast radius; see Decision D4**
  - [x] Add `ProbeResult probeGitHubAuth();` to `org.dradgo.application.diagnostics.spi.DoctorProbePort`.
  - [x] Implement in `org.dradgo.adapters.diagnostics.DoctorProbeAdapter`: if `github-real` profile **inactive** ⇒ `ProbeResult.pass("github-real profile inactive; GitHub auth check not applicable", …)` with **no network call**; if active + token blank ⇒ `ProbeResult.fail(…, DomainErrorCode.DOCTOR_GITHUB_TOKEN_MISSING.value(), …)`; if active + token present ⇒ `GET /user` via the `gitHubRestClient`, PASS on 200, `DOCTOR_GITHUB_AUTH_FAILED` on 401/403. **Never log the token.** Mirror `probeRunnerSecrets()` presence-only detail shape.
  - [x] Wire into `DoctorService`: add a `CHECK_GITHUB_AUTH` constant, append it to `STATIC_ORDER`, add the `case CHECK_GITHUB_AUTH -> probes.probeGitHubAuth();` switch arm (lines ~188–202).
  - [x] Add **two `DomainErrorCode`s** `DOCTOR_GITHUB_AUTH_FAILED`, `DOCTOR_GITHUB_TOKEN_MISSING` — and follow the **three-sites rule** (Decision D4): `DomainErrorCode` enum + `ProblemDetailsCatalog` + the registry/api-schema-placeholder manifest. Verify with `-Pfoundation-gate`.
- [x] **Task 6 — ADRs** (AC: 1, 10) — **use Decision D1 numbers, NOT the epic's stale `0004`/`0005`**
  - [x] `docs/adr/0020-github-rest-vs-graphql.md` — decision: REST v3 + Spring `RestClient`, why not `org.kohsuke.github` SDK, why not GraphQL.
  - [x] `docs/adr/0021-github-write-scope.md` — minimum PAT scopes (AC10) + the redact-and-send vs refuse-`EGRESS_SECRET_DETECTED` egress policy (AC6) + security posture (token never logged/persisted/in-URL).
- [x] **Task 7 — Profile wiring (do NOT add `github-real` to any group)** (AC: 1)
  - [x] **Do NOT** add `github-real` to the `test`/`local`/`demo` profile groups in `application.yml` (lines 15–17) — exactly like `linear-real`, which is opt-in only and absent from all groups. The mock stays the default. The `assertExclusiveGitHubProfile` guard (already in `GitHubConfiguration`) keeps mock+real mutually exclusive.
- [x] **Task 8 — Tests** (AC: 6, 8, 9, 11)
  - [x] `GitHubRealAdapterUnitTest` (mirror `LinearRealAdapterUnitTest` — `MockRestServiceServer.bindTo(RestClient.builder().baseUrl(BASE))`): every AC7 category from a stubbed status/headers; rate-limit WARN-threshold + remaining=0 raise (AC5); redaction-on-egress request-body assertion (AC6); idempotent create (stub the GET-existing-PR ⇒ assert no POST expectation) (AC4); request-time `Authorization: Bearer` header set (AC2).
  - [x] `GitHubRealConfigurationTest` / extend `GitHubProfileWiringContractTest`: under `github-real`, `gitHubRestClient` bean present; blank token ⇒ fail-fast; mock+real both active ⇒ `assertExclusiveGitHubProfile` fail-fast.
  - [x] `DoctorGitHubProbeTest` (AC9): inactive ⇒ PASS-not-applicable, no call; token-missing ⇒ `DOCTOR_GITHUB_TOKEN_MISSING`; 200 ⇒ PASS; 401 ⇒ `DOCTOR_GITHUB_AUTH_FAILED`.
  - [x] **Implement + enable** the `FoundationGateVerificationTest` Contract #11 **parity test** that 3.13 left `@Disabled` (AC8): same scenario sequence vs mock + (MockRestServiceServer-stubbed) real, assert typed-shape + category equivalence. Tag the **live-repo** variant `@Tag("gh-real-tests")` / `@EnabledIfSystemProperty` so PR CI skips it.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] Add SLF4J structured logs at every `GitHubRealAdapter` public method (entry `INFO` with `op`+`repoRef`/`prRef`/`branch`, exit `INFO` with `resolution`+`durationMs`), every `GitHubAdapterException` raise site (`WARN` carrying `op`+`status`+`category`), every rate-limit branch (`WARN remaining=… resetAtSeconds=…`), idempotent-existing-PR branch (`WARN resolution=idempotent_existing`), and the doctor probe.
  - [x] Parameterized logging only (`log.warn("github_real {} failed status={} category={}", op, status, category)`) — copy the `LinearRealAdapter` log style. Never string concatenation.
  - [x] Levels: `INFO` normal lifecycle (call start/finish, PR created/updated, comment posted), `WARN` recoverable/anomalous (rate-limit below threshold, rate-limited, permission-denied, branch-protected, idempotent replay), `ERROR` only for genuinely unexpected internal failure. `DEBUG` for header/detail dumps (header values only — **never** the token).
  - [x] **Forbidden in log output:** the PAT/`Authorization` value, raw `title`/`body` payload bytes, any secret pattern. Log refs, counts, categories, status codes, branch names, rate-limit numbers only. Bodies are redacted on egress but **still not logged**.
  - [x] Pin with a list-appender / `OutputCaptureExtension`: the rate-limited WARN, the idempotent-existing-PR WARN, and an assertion that **no log line contains the token** for at least one auth-carrying call.

## Dev Notes

### The two references that matter most

| Concern | File to mirror | Why |
|---|---|---|
| **Real HTTP adapter shape** | `adapters/integration/linear/LinearRealAdapter.java` | ctor, `RestClient` use, request-time-token interceptor, the catch-cascade classification ladder (~314–358), `JsonNode` mapping, one-full-method example (`fetchTicketByReference` ~123–139). |
| **Real RestClient bean + fail-fast** | `infrastructure/config/LinearConfiguration.java` (the `@Bean("linearRestClient") @Profile(REAL_PROFILE)`) | timeout factory, default headers, request interceptor reading token at request time, blank-token `IllegalStateException`. |
| **Validated config record + token redaction** | `application/integration/linear/LinearProperties.java` | `@ConfigurationProperties` in the **application** package, `@JsonIgnore` token, redacting `toString()`, default-value normalization in the compact ctor. |
| **Mocked-HTTP unit test** | `test/.../adapters/integration/linear/LinearRealAdapterUnitTest.java` | `MockRestServiceServer.bindTo(RestClient.builder())` harness — your exact AC11 pattern. |
| **The port + records you implement against (do not change)** | `application/integration/github/GitHubAdapter.java`, `GitHubRepository.java`, `GitHubPullRequest.java`, `GitHubBranch.java`, `GitHubAdapterException.java` | shipped by 3.13; **read-only** for this story. |
| **The mock you achieve parity with** | `adapters/integration/github/GitHubMockAdapter.java` | the parity test (AC8) runs both against matched fixtures. |

### What story 3.13 ALREADY shipped — do NOT rebuild these

- **The `GitHubAdapter` port + 3 domain records + `GitHubAdapterException`** — `org.dradgo.application.integration.github`. Implement against them; changing them breaks the mock.
- **All 8 GitHub `IntegrationFailureCategory` values** — `GITHUB_REPO_NOT_FOUND`, `GITHUB_PR_NOT_FOUND`, `GITHUB_PERMISSION_DENIED`, `GITHUB_RATE_LIMITED`, `GITHUB_BRANCH_PROTECTED`, `GITHUB_AUTH_FAILED`, `GITHUB_NETWORK_FAILURE`, `GITHUB_API_VERSION_INCOMPATIBLE`. **AC7 is an enum no-op** — just map HTTP to them. (Decision D5.)
- **`GitHubConfiguration`** with `MOCK_PROFILE`/`REAL_PROFILE` constants + `assertExclusiveGitHubProfile(Environment)` guard — **extend** it with the real RestClient bean; don't recreate it.
- **`GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT`** ArchUnit rule — already forbids `org.kohsuke.github..`, `com.github..`, and spring web/http client packages from leaking through `application.integration..`. This is **why** `RestClient`/DTOs must stay inside `GitHubRealAdapter`; keep all HTTP types out of the port and records. The rule will fail your build if you leak.
- **`FoundationGateVerificationTest` Contract #11** with a `@Disabled` parity stub explicitly TODO'd for "once 3.14 lands" — **enable + implement it** (AC8), don't add a parallel one.

### Decision D1 — ADR numbers: the epic's `0004`/`0005` are STALE; use `0020`/`0021`

🚨 **Critical.** Epic AC1/AC10 reference `docs/adr/0004-github-rest-vs-graphql.md` and `docs/adr/0005-github-write-scope.md`. **`docs/adr/0004` is already taken** by `0004-spec-stage-orchestration.md`. Existing ADRs: `0001`, `0002`, `0003`, `0004-spec-stage-orchestration`, `0019-structured-logging`. **Do NOT overwrite `0004`.** Author **`0020-github-rest-vs-graphql.md`** and **`0021-github-write-scope.md`** (next sequential after the highest existing, `0019`). The intervening `0005`–`0018` gap appears to be reserved/unused but using gap numbers risks colliding with another in-flight story — append after the max instead.

### Decision D2 — REST v3 + Spring `RestClient`, NOT the `org.kohsuke.github` SDK

The epic offers "REST API v3 (or GraphQL)" and the ArchUnit rule names `org.kohsuke.github..` as a forbidden leak — implying an SDK was *considered*. **Choose Spring `RestClient` (already on the classpath via `spring-boot-starter-webmvc`/`spring-web`) and raw REST v3.** Rationale (record in ADR 0020): (1) zero new dependency, (2) `LinearRealAdapter` already proves the `RestClient` pattern in this repo — one consistent HTTP idiom, (3) the SDK's types would have to be firewalled out of the port anyway, (4) only ~6 endpoints are needed. **Do NOT add `org.kohsuke:github-api` to `pom.xml`.** Java 21, Spring Boot 4.

### Decision D3 — redaction-on-egress: redact-and-send (avoid adding a DomainErrorCode)

AC3/AC6 require write bodies through `RedactionPolicyService` (`org.dradgo.application.security.RedactionPolicyService`) before sending. Methods: `redact(String payload, String classificationValue) → RedactionResult` with `sanitizedText()`, `effectiveClassification()`, `redacted()`, `detectedCategories()`. **Default policy: redact and send `result.sanitizedText()`.** The epic's alternative — refusing with `EGRESS_SECRET_DETECTED` — would require a new `DomainErrorCode` (and thus the three-sites rule, Decision D4). **Prefer redact-and-send** so AC6's test asserts a redacted outbound body, and document the refuse-path as a future option in ADR 0021. **Before hardcoding the classification string**, confirm the exact value the service expects (`"shareable-redacted"` is the likely registry value for GitHub egress per story 3.15 AC7, but verify against the classification registry / `RedactionPolicyService` signature on your branch).

### Decision D4 — AC9 doctor probe: the one place with real blast radius (DomainErrorCode three-sites)

Adding the `github-real` auth probe is **not** a one-file change:
1. `DoctorProbePort` (SPI interface, `application.diagnostics.spi`) — **+1 method** `probeGitHubAuth()`. This interface has a **fixed method set** dispatched by a `switch` in `DoctorService`; every impl must implement it.
2. `DoctorProbeAdapter` (`adapters.diagnostics`) — implement it (mirror `probeRunnerSecrets()` presence-only detail shape).
3. `DoctorService` — `CHECK_GITHUB_AUTH` constant + `STATIC_ORDER` entry + `switch` arm (lines ~188–202).
4. **Two new `DomainErrorCode`s** (`DOCTOR_GITHUB_AUTH_FAILED`, `DOCTOR_GITHUB_TOKEN_MISSING`). Existing doctor codes (`DOCTOR_POSTGRES_UNREACHABLE`, `DOCTOR_FLYWAY_FAILED`, `DOCTOR_RUNNER_SECRET_MISSING`, …) confirm the naming convention — there is **no** `DOCTOR_GITHUB_*` yet.

⚠️ **Per memory `new-domainerrorcode-three-sites`: adding a `DomainErrorCode` requires `DomainErrorCode` enum + `ProblemDetailsCatalog` + the registry-api-schema-placeholders manifest all updated, verified with `-Pfoundation-gate`.** (This is the OPPOSITE of `IntegrationFailureCategory`, which story 3.13 confirmed is NOT in the manifest — but `DomainErrorCode` IS.) Budget for this. If any test mock of `DoctorProbePort` exists, the new interface method will break it until stubbed.

**Probe must not call the network when `github-real` is inactive** — under the default mock profile it returns PASS-not-applicable. Otherwise every `@SpringBootTest` doctor run would try to reach `api.github.com`.

### Decision D5 — AC7 is an enum no-op; the work is the mapping ladder

All 8 GitHub `IntegrationFailureCategory` values already exist (3.13 added the full 3.14 set deliberately). **Do not touch `IntegrationFailureCategory.java`.** AC7 is satisfied by the catch-cascade in Task 4 mapping HTTP outcomes to the existing values + raising `GitHubAdapterException(category, …)`.

### Decision D6 — avoid the validated-config test-yaml trap

Per memory `validated-config-needs-test-yaml`: a validated `@ConfigurationProperties` bean that instantiates during `@SpringBootTest` forces matching keys in `src/test/resources/application.yml` or the whole tier fails at startup. **Mitigation:** `LinearProperties` already coexists with the test tiers without breaking them — **register `GitHubProperties` exactly the way `LinearProperties` is registered** (same `@EnableConfigurationProperties`/`@ConfigurationPropertiesScan` mechanism, and the bean's hard validation only bites under `github-real`, which is never in a default test group). Mirror it precisely; if the `RestClient` bean is `@Profile(REAL_PROFILE)` and `GitHubProperties` binding is profile-neutral with safe defaults (blank token allowed at bind time, only fatal when the real bean activates), no test-yaml change is needed. **Verify** the `@SpringBootTest` tier still boots after adding the properties record.

### Decision D7 — do NOT depend on story 3.9 (build-order)

Sprint build order is `… 3-13, 3-14, 3-9 …` — **3.9 (`RepositoryWorkspaceService`, deterministic branch convention) builds AFTER this story.** AC4 *references* 3.9's branch convention as the upstream guarantee of consistent `sourceBranch`, but the adapter must be **self-contained**: it accepts `(repoRef, branch, …)` and does its idempotency probe against GitHub directly. **Do not import `RepositoryWorkspaceService` or any `application.runner.workspace` type** — it does not exist yet, and the `ADAPTER_SLICES_MUST_NOT_DEPEND_ON_EACH_OTHER` / layering rules would flag it.

### Logging Requirements (project-wide standard)

Every story is expected to leave the touched services observable enough to debug a production incident without re-deploying. Enforced via the "Logging instrumentation" task above.

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. ADR `0019-structured-logging` governs format.
- **Where to log (this story's surface = `GitHubRealAdapter` + the doctor probe + the RestClient bean):**
  - `GitHubRealAdapter` public methods → `INFO` on entry/success with `op`+ref+`durationMs`; `WARN` on every classified `GitHubAdapterException` (carry `op`, `status`, `category`) + rate-limit + idempotent-replay; `ERROR` only for unexpected internal failure.
  - Doctor probe → `INFO` PASS / `WARN` token-missing / `WARN` auth-failed (status only).
- **Required context keys:** entity public refs the method receives (`repoRef`, `prRef`, `branchName`), `op`, `status`, `category`, rate-limit numbers. The adapter has no `correlationId`/`workflowRunId` in scope (callers carry those) — log what the method receives; do not fabricate.
- **Forbidden in log output:** the PAT / `Authorization` value, raw `title`/`body` payload bytes, any secret pattern, raw API JSON that might echo a token. Refs, counts, categories, statuses, branch names, rate-limit numbers only.
- **Test contract:** pin the rate-limited WARN, idempotent-existing-PR WARN, and a **"token never appears in any log line"** assertion with a list-appender / `OutputCaptureExtension`.

### Project Structure Notes

- Backend module is **`deliveryline-backend/`** (planning docs sometimes say `backend/` — the real path is `deliveryline-backend/`). Base package `org.dradgo`. Java 21, Spring Boot 4, `RestClient` from `spring-web`.
- Placement: real adapter + any GitHub DTOs/parser → `adapters.integration.github`; `GitHubProperties` → `application.integration.github` (mirror `LinearProperties`); RestClient bean → `infrastructure.config.GitHubConfiguration`; doctor probe method → `application.diagnostics.spi.DoctorProbePort` + impl in `adapters.diagnostics.DoctorProbeAdapter`; new `DomainErrorCode`s → `domain.registry` (+ catalog + manifest).
- `github-real` is **opt-in only** — never in a profile group (mirror `linear-real`).
- **Verification commands** (PowerShell, per memory `rtk-hook-only-matches-bash` — use native file tools + PowerShell, not the Bash-routed grep):
  - Focused unit slice: `mvnw -pl deliveryline-backend test -Dtest=GitHubRealAdapterUnitTest,DoctorGitHubProbeTest,GitHubProfileWiringContractTest`
  - ArchUnit boundary tier (confirm no GitHub HTTP types leak): `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=ArchitectureBoundaryTest`
  - **Foundation gate (REQUIRED — the `DomainErrorCode` additions of AC9 are manifest-gated):** `mvnw -pl deliveryline-backend -Pfoundation-gate failsafe:integration-test failsafe:verify`
  - Parity contract: `mvnw -pl deliveryline-backend -Pfoundation-gate failsafe:integration-test -Dit.test='FoundationGateVerificationTest$Contract11GitHubMockAdapter'` (parity stub now enabled)
  - Static gates: `mvnw -pl deliveryline-backend spotless:apply checkstyle:check` then full fast tier `mvnw -pl deliveryline-backend test`.
  - The `gh-real-tests` live-repo variant is **not** run locally/PR-CI — it needs a real `GITHUB_TOKEN` + test repo and runs nightly (story 3.35 AC3).

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.14] — ACs 1–11 (lines 277–295); reciprocal parity from 3.13 (lines 274, 285, 292); 3.15 link/sync consumers (lines 300–311); 3.9 branch convention upstream (line 288); 3.35 AC3 `gh-real-tests` gating (line 704).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/integration/linear/LinearRealAdapter.java] — **primary template**: ctor, `RestClient` use, classification catch-cascade (~314–358), one full method (~123–139).
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/LinearConfiguration.java] — `@Bean("linearRestClient") @Profile(REAL_PROFILE)`: timeout factory, default headers, request-time-token interceptor, blank-token fail-fast.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/linear/LinearProperties.java] — `@ConfigurationProperties` record to mirror for `GitHubProperties` (`@JsonIgnore` token, redacting `toString`, default normalization).
- [Source: deliveryline-backend/src/test/java/org/dradgo/adapters/integration/linear/LinearRealAdapterUnitTest.java] — `MockRestServiceServer.bindTo(RestClient.builder())` harness (AC11).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/github/GitHubAdapter.java + GitHubRepository/GitHubPullRequest/GitHubBranch/GitHubAdapterException.java] — the port + records to implement against (read-only; 3.13).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubMockAdapter.java] — parity counterpart (AC8).
- [Source: deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/GitHubConfiguration.java] — existing profile constants + `assertExclusiveGitHubProfile`; extend with the real RestClient bean.
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/IntegrationFailureCategory.java] — all 8 GitHub values already present (3.13); do not edit (Decision D5).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/security/RedactionPolicyService.java] — `redact(String, String classificationValue) → RedactionResult` for egress (AC3/AC6, Decision D3).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java + DoctorService.java + adapters/diagnostics/DoctorProbeAdapter.java] — probe SPI + `STATIC_ORDER`/switch dispatch (~188–202) + `probeRunnerSecrets()` presence-only template (AC9, Decision D4).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java#L49-L56] — existing `DOCTOR_*` codes; add `DOCTOR_GITHUB_AUTH_FAILED`/`DOCTOR_GITHUB_TOKEN_MISSING` via the three-sites rule (Decision D4).
- [Source: deliveryline-backend/src/main/resources/application.yml#L11-L17] — profile groups; `github-real` must NOT be added (Decision/Task 7).
- [Source: deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java] — Contract #11 `@Disabled` parity stub to enable (AC8).
- [Source: docs/adr/] — existing `0001`–`0004`,`0019`; author `0020`/`0021` (Decision D1).
- [Source: _bmad-output/implementation-artifacts/3-13-mock-github-adapter.md] — the predecessor mock story (Decisions D1–D3 there, fixture refs, idempotency model).

### Review Findings

- [x] [Review][Patch] Make missing `GITHUB_TOKEN` doctor-reportable under `github-real` instead of failing Spring startup before doctor can emit `DOCTOR_GITHUB_TOKEN_MISSING` [deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/GitHubConfiguration.java:51]
- [x] [Review][Patch] `GITHUB_TOKEN` is documented but not actually bound to `deliveryline.github.token` [deliveryline-backend/src/main/resources/application.yml:85]
- [x] [Review][Patch] GitHub branch/ref URI construction does not encode slash-containing branch names or reject URI metacharacters in owner/repo refs [deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubRealAdapter.java:158]
- [x] [Review][Patch] `GITHUB_RATE_LIMITED` does not expose `details.resetAtSeconds` as required by AC5; it only logs and embeds it in the exception message [deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubRealAdapter.java:360]
- [x] [Review][Patch] The gated `gh-real-tests` live-repo integration variant required by AC8/AC11(e) is only documented in comments, not implemented [deliveryline-backend/src/test/java/org/dradgo/foundation/GitHubMockVsRealParityFoundationContract.java:44]
- [x] [Review][Patch] Oversized PR numbers escape the adapter's classified-failure contract via `Integer.parseInt` [deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubRealAdapter.java:616]
- [x] [Review][Patch] All 422 responses are classified as `GITHUB_BRANCH_PROTECTED`, including update/comment validation failures unrelated to protected branches [deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubRealAdapter.java:444]
- [x] [Review][Patch] Redaction-on-egress tests cover comments only, leaving create PR title/body and update PR body unpinned [deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubRealAdapterUnitTest.java:286]
- [x] [Review][Patch] GitHub timeout configuration can overflow when `connectMs` is cast from `long` to `int` [deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/GitHubConfiguration.java:59]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (1M context) — bmad-dev-story workflow

### Debug Log References

- Focused slice (PowerShell, per memory `rtk-hook-only-matches-bash`): `mvnw test -Dtest=GitHubRealAdapterUnitTest,GitHubRealConfigurationTest,DoctorGitHubProbeTest,GitHubRealAdapterLoggingContractTest,DoctorServiceTest` → **48 tests, 0 failures**.
- Full fast unit tier: `mvnw -pl deliveryline-backend test` → **692 tests, 0 failures, 0 errors, 10 skipped** (was 663; +29 new).
- Foundation gate (Docker up, Testcontainers): `mvnw -pl deliveryline-backend -Pfoundation-gate verify -Dtest=ZzzNone -Dsurefire.failIfNoSpecifiedTests=false` → **16 tests, 0 failures**; XML confirms `gitHubMockVsRealParity` executed. This green run also covers Contract #3 (RegistryContractTest three-sites manifest alignment), Contract #7 (ProblemDetails coverage), and the `GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT` ArchUnit rule (delegated by Contracts #1 + #11).
- Static gates: `mvnw spotless:apply checkstyle:check` → 9 files reformatted, **0 Checkstyle violations**.
- Invocation note: the direct `failsafe:integration-test` CLI goal does not pick up the pom's bound-execution `<includes>` (runs 0 tests); the gate must be run through the `verify` lifecycle phase under `-Pfoundation-gate`.
- NOT run locally (by design): the `gh-real-tests` live-repo variant (nightly, story 3.35 AC3) and the WSL2 Linux CI parity — no new Maven dependency, lockfile, frontend, or runner-image change, so no cross-platform smoke needed.

### Completion Notes List

- **Task 1 — `GitHubProperties` + real `RestClient` bean.** `application.integration.github.GitHubProperties` mirrors `LinearProperties` (`@JsonIgnore` token + redacting `toString`) but its compact constructor **normalizes-with-defaults and never throws** (Decision D6) so the profile-neutral `@EnableConfigurationProperties(GitHubProperties.class)` binding is safe in every `@SpringBootTest` tier with no required keys. `gitHubRestClient` `@Bean(@Profile("github-real"))` added to the existing `GitHubConfiguration`: `SimpleClientHttpRequestFactory` timeouts, version-pinned default headers (`Accept: application/vnd.github+json`, `X-GitHub-Api-Version`), and request-time `Authorization: Bearer` interceptor. A blank token no longer fails startup; the doctor probe reports `DOCTOR_GITHUB_TOKEN_MISSING`.
- **Tasks 2–4 — `GitHubRealAdapter`** (`@Profile("github-real")`). Refs: `owner/repo` and `owner/repo#number` (private parser). Reads map JSON→records with 404⇒`Optional.empty()`; writes redact `title`/`body` via `RedactionPolicyService.redact(_, "shareable-redacted")` before send (redact-and-send, D3). Idempotent create probes `GET …/pulls?head={owner}:{branch}&base={default_branch}&state=open` and reuses an existing open PR (no POST) before `POST …/pulls {draft:true}`. Rate-limit headers inspected on every response (WARN below `rate-limit-warn-threshold`, raise `GITHUB_RATE_LIMITED` at remaining≤0). Classification ladder maps 401→`GITHUB_AUTH_FAILED`, 403→rate-limit-or-`GITHUB_PERMISSION_DENIED`, 429→`GITHUB_RATE_LIMITED`, 404→repo/PR-not-found by op, 415→`GITHUB_API_VERSION_INCOMPATIBLE`, 422→`GITHUB_BRANCH_PROTECTED`, 5xx/IO→`GITHUB_NETWORK_FAILURE`, never generic (AC7, enum no-op per D5).
  - **Reconciliation (AC5 `details.resetAtSeconds`):** `GitHubAdapterException` is read-only (3.13) and carries no detail map, so `resetAtSeconds` (from `X-RateLimit-Reset`) is surfaced in the WARN log + the exception message rather than a typed field.
- **Task 5 — Doctor `github-real` auth probe (highest blast radius, D4).** `probeGitHubAuth()` added to `DoctorProbePort` + `DoctorProbeAdapter` (inactive⇒PASS-not-applicable with **no network call**; token-blank⇒`DOCTOR_GITHUB_TOKEN_MISSING`; `GET /user` 2xx⇒PASS, 401/403⇒`DOCTOR_GITHUB_AUTH_FAILED`, presence-only details, token never logged). `DoctorService` gained `CHECK_GITHUB_AUTH` constant + `STATIC_ORDER` entry + switch arm + FAIL remediation. **Two new `DomainErrorCode`s** added via the three-sites rule: enum + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json` `problemTypeUris` — verified green by the foundation gate (Contract #3 + #7). `DoctorProbeAdapter` ctor extended via `@Qualifier("gitHubRestClient") ObjectProvider<RestClient>` + `ObjectProvider<GitHubProperties>` (resolved lazily; the 9-arg public test ctor signature was preserved by delegating with github-disabled defaults, limiting blast radius to one test helper + the `DoctorProbePort` mock stubs in `DoctorServiceTest`/`DoctorLoggingContractTest`).
- **Tasks 6–7 — ADRs + profile wiring.** `docs/adr/0020-github-rest-vs-graphql.md` + `0021-github-write-scope.md` authored at the next-sequential numbers (epic's `0004`/`0005` are stale, D1). Non-secret `deliveryline.github.*` block added to main + test `application.yml`. `github-real` is **never** added to any profile group (opt-in only, like `linear-real`).
- **Task 8 — Tests.** `GitHubRealAdapterUnitTest` (MockRestServiceServer, AC11a — every AC7 category, rate-limit WARN + remaining=0 raise, redaction-on-egress, idempotent create, request-time `Authorization` header). `GitHubRealConfigurationTest` (bean present under github-real + blank token remains startup-safe and doctor-reportable + absent under github-mock). `DoctorGitHubProbeTest` (AC9 four paths). `GitHubRealAdapterLoggingContractTest` (rate-limited WARN, idempotent-existing WARN, token-never-logged). `GitHubMockVsRealParityFoundationContract` implements + the `FoundationGateVerificationTest` Contract #11 `@Disabled` stub is now enabled and delegates to it (AC8). Updated `DoctorServiceTest`, `DoctorLoggingContractTest` (`checksRun` 12→13), and `DoctorProbeAdapterTest` for the new probe/ctor.
- **No new dependency** — used the on-classpath Spring `RestClient`; did **not** add `org.kohsuke:github-api` (D2). No migration, no REST/OpenAPI DTO change, no schema-d.ts drift.

### File List

**New (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/integration/github/GitHubProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/integration/github/GitHubRealAdapter.java`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/GitHubConfiguration.java`
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java`
- `deliveryline-backend/src/main/resources/application.yml`

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubRealAdapterUnitTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubRealConfigurationTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/integration/github/GitHubRealAdapterLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorGitHubProbeTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/foundation/GitHubMockVsRealParityFoundationContract.java`

**Modified (test):**
- `deliveryline-backend/src/test/java/org/dradgo/foundation/FoundationGateVerificationTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapterTest.java`
- `deliveryline-backend/src/test/resources/application.yml`
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json`

**New (docs):**
- `docs/adr/0020-github-rest-vs-graphql.md`
- `docs/adr/0021-github-write-scope.md`

### Change Log

| Date | Change |
|---|---|
| 2026-06-01 | Implemented story 3.14 — real `GitHubRealAdapter` (REST v3 + PAT, redaction-on-egress, idempotent PR create, rate-limit awareness, classification ladder), `GitHubProperties` + `gitHubRestClient` bean, doctor `github-real` auth probe (+2 `DomainErrorCode`s via three-sites), ADRs 0020/0021, and enabled the FoundationGate Contract #11 mock-vs-real parity test. Status `ready-for-dev → in-progress → review`. |
