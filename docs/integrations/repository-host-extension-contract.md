# Repository-Host Extension Contract

This document specifies the contract a new **repository host** (Bitbucket, GitLab, Gitea, Azure DevOps Repos, …) must satisfy to plug into DeliveryLine. The GitHub integration is the reference implementation; a new host is a one-interface, one-contract add — implement `org.dradgo.application.integration.repohost.RepositoryHostAdapter` against this contract, add a `kind` selector value, and a Spring profile.

Background and the decisions behind the abstraction live in `../adr/0008-repository-host-abstraction.md`. The symmetric ticket-source contract is `ticket-source-extension-contract.md`. The existing GitHub ADRs cover REST-vs-GraphQL (`../adr/0020-github-rest-vs-graphql.md`), write-scope (`../adr/0021-github-write-scope.md`), and git CLI vs jgit (`../adr/0022-git-cli-vs-jgit.md`).

## The port

`RepositoryHostAdapter` is the vendor-neutral application-owned port. It carries only domain-shaped types from `org.dradgo.domain.integration.repohost` (`Repository`, `PullRequest`, `Branch`, `RepositoryRef`, `PullRequestRef`, `CommentResult`, `RepositoryHostCapabilities`) — vendor transport types (GitHub REST DTOs, the `org.kohsuke.github` SDK, HTTP clients) must **not** leak through. The `REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT` ArchUnit rule enforces this (verified in Failsafe), plus residence rules pinning the port to `application.integration.repohost`, implementations to `adapters.integration.repohost.{kind}`, and neutral types to `domain.integration.repohost`.

Only `RepositoryWorkspaceService` and `IntegrationLinkService` call the port directly. The runner broker / orchestration reach it via `IntegrationLinkService`; CLI / REST / persistence go through these services.

### Refs are opaque tokens

`RepositoryRef(String value)` and `PullRequestRef(String value)` are **vendor-uninterpreted opaque tokens**. The GitHub reference encodes a repo ref as `"owner/repo"` and a PR ref as `"owner/repo#number"`, but only the GitHub implementation parses that shape — neutral consumers treat them as keys and persist `.value()` to `integration_links.external_ref` (a `String`). A new host chooses its own internal token shape; do not assume the GitHub format anywhere outside the host adapter.

## Per-method expected behavior

### `Optional<Repository> getRepositoryByRef(RepositoryRef ref)`

- Look up a repository. Return `Optional.empty()` on ordinary absence (e.g. a real 404) — **do not throw on not-found** for reads. Throw a classified `RepositoryHostAdapterException` for deliberate failure conditions.
- Map the vendor response onto a neutral `Repository`: `repoRef`, `fullName` (`owner/name` slug), `defaultBranch`, `url` (the clone URL).

### `Optional<PullRequest> getPullRequestByRef(PullRequestRef ref)`

- Return `Optional.empty()` for ordinary absence; throw classified failures. Map onto a neutral `PullRequest`: `prRef`, `repoRef`, `number` (the generic PR/MR number), `sourceBranch`, `state`, `url`, `createdAt`.

### `Optional<Branch> getBranchByRef(RepositoryRef repo, String branchName)`

- Return `Optional.empty()` when the branch is not present. Map onto a neutral `Branch`: `repoRef`, `name`, `headSha`.

### `PullRequest createPullRequest(RepositoryRef repo, String sourceBranch, String targetBranch, String title, String body)`

- Create (or idempotently reuse) a PR from `sourceBranch` into `targetBranch`. `targetBranch` is the base branch; a **blank/`null`** value means "use the repository default branch" (the back-compat path the GitHub reference resolves internally).
- **Idempotent** on `(repo, sourceBranch, targetBranch)`: re-creating a PR for the same source branch returns the already-open record rather than stacking a duplicate (story 3.14 AC4). The GitHub reference probes for an open PR via `head=owner:branch&base=targetBranch&state=open`; emit a WARN on the idempotent-replay branch.

### `PullRequest updatePullRequest(PullRequestRef ref, String body)`

- Update an existing PR's body, returning the affected `PullRequest`.

### `CommentResult commentOnPullRequest(PullRequestRef ref, String body)`

- Post a comment on a PR. **Optional operation:** consumers must check `getCapabilities().supportsPullRequestComments()` before invoking and degrade gracefully when `false`.
- Return `CommentResult.POSTED` on a fresh write. A host with server-side dedup may return `CommentResult.SKIPPED_DUPLICATE` on an idempotency replay; the GitHub real adapter has **no server-side comment dedup** and always returns `POSTED` (the mock surfaces `SKIPPED_DUPLICATE` via a `(prRef, fingerprint)` key — a documented mock-vs-real asymmetry).

### `RepositoryHostCapabilities getCapabilities()`

- Declare which optional features the host supports: `supportsDraftPullRequests`, `supportsPullRequestComments`, `supportsBranchProtection`, `supportsForkPushes`, `supportsRequiredStatusChecks`. Consuming services gate optional calls on these flags. (GitHub returns `githubDefaults()` — all five `true`.)

## Error classification

Every failure surfaced through the port is a `RepositoryHostAdapterException` carrying an `org.dradgo.domain.registry.IntegrationFailureCategory` — never an HTTP status code or vendor error envelope. Map vendor failures onto the categories the reference GitHub adapter uses (the eight `GITHUB_*` values):

| Condition | `IntegrationFailureCategory` |
| --- | --- |
| auth (401) | `GITHUB_AUTH_FAILED` |
| permission denied (403, not rate-limit) | `GITHUB_PERMISSION_DENIED` |
| rate-limit (429, or 403 with exhausted remaining) | `GITHUB_RATE_LIMITED` |
| repository not found (404 on a repo path) | `GITHUB_REPO_NOT_FOUND` |
| pull request not found (404 on a PR path) | `GITHUB_PR_NOT_FOUND` |
| protected branch / 422 on PR create | `GITHUB_BRANCH_PROTECTED` |
| unsupported API version (415) | `GITHUB_API_VERSION_INCOMPATIBLE` |
| 5xx, network I/O, malformed/empty response, serialization | `GITHUB_NETWORK_FAILURE` |

The adapter does **not** retry; it surfaces typed failures for the recovery layer to decide policy. A future host may add host-specific categories, but should reuse the existing semantics where they fit.

## Idempotency guarantees

- `createPullRequest` is idempotent on `(repo, sourceBranch, targetBranch)` (with `targetBranch = repository default branch` when blank). Re-create returns the existing open PR.
- `commentOnPullRequest` is idempotent by `(prRef, fingerprint(body))` **only on hosts that support server-side dedup** (the mock does; GitHub does not — it stacks duplicate issue comments, so the real adapter always `POSTED`).

## Redaction on egress

Any text sent to the host must pass through `org.dradgo.application.security.RedactionPolicyService` on egress (classification `shareable-redacted`). The GitHub real adapter redacts every write method's `title`/`body` before the request is sent (redact-and-send, never refuse). Never log secrets, raw tokens (the `GITHUB_TOKEN` PAT), PR/comment body bytes, or repository free-text; sanitize references with `MdcKeys.sanitizeForLog(...)` and keep the bearer-token-never-logged posture.

## Auth model (per-vendor)

- GitHub uses a Personal Access Token (`GITHUB_TOKEN`) sent as `Authorization: Bearer <token>`, read at request time inside the host's dedicated `RestClient` interceptor so rotation is observed without a context refresh. The token is never logged, never embedded in a URL, never persisted.
- A new host documents its own auth model (token / OAuth / basic auth) and supplies it the same way — via the host config record + a profile-gated client bean, with a blank credential startup-safe and surfaced by the doctor probe rather than fatal at bind time.

## Branch-naming compatibility

The deterministic branch convention from story 3.9 (`RepositoryWorkspaceService.branchName`) produces `deliveryline/{ticketSlug}/stage-{runIdShort}` using `/` separators and sanitizes the slug to `[A-Za-z0-9._-]`. It therefore contains **no `:`** — satisfying the stricter Bitbucket constraint (Bitbucket forbids `:` in branch names) as well as GitHub's git-ref rules. A new host must accept this convention; `BranchNamingCompatibilityTest` pins the GitHub-valid + Bitbucket-compatible (`:`-free) invariants.

## Git-protocol independence

The host adapter handles only API-layer concerns (PR creation, branch metadata via API, comment posting). Git operations (clone, push) go through the vendor-agnostic `GitCommandPort` SPI using `Repository.url()` — independent of which `RepositoryHostAdapter` is active (story 3.33 AC9). A new host does not implement cloning.

## Configuration-key conventions

- `deliveryline.integration.repo-host.kind` selects the active kind (default `github`). `GitHubConfiguration` (or the new host's configuration) fail-fasts at boot if `kind` names a host with no implementation on the classpath.
- Vendor-specific config lives under `deliveryline.integration.repo-host.{kind}.*` for new hosts. The existing GitHub keys remain under `deliveryline.github.*` (renaming them is an ops-breaking change out of scope — see ADR 0008).
- Bean gating is by Spring profile (the GitHub reference uses mutually-exclusive `github-mock`/`github-real`). Keep vendor config records framework-light and out of the layered-boundary path (`application.integration.github`, not `infrastructure`).

## Testing requirements

A new host must:

- Implement a **parity test** that drives the mock and real implementations through the same fixture scenarios used for GitHub (happy read returns a neutral `Repository`/`PullRequest`; a classified failure surfaces the same `IntegrationFailureCategory` in both; `getCapabilities()` returns the declared set in both). Mirror `RepositoryHostAbstractionFoundationContract` (foundation Contract #15).
- Cover capability declaration (and capability-driven degradation once a consumer of an optional feature exists).
- Cover config-driven selection: the configured `kind` (+ profile) activates the right implementation, and a `kind` with no implementation fails fast at boot.
- Cover deterministic-branch-naming compatibility (GitHub-valid AND `:`-free Bitbucket-compatible).
- Honor the test-naming conventions: `@SpringBootTest`+Testcontainers tests are `*IT` (Failsafe); ArchUnit `@ArchTest`s run in Failsafe, not Surefire; `*FoundationContract` is Launcher-API-discovered (must not match `*Test`).

## References

- `../adr/0008-repository-host-abstraction.md` — the abstraction decision record.
- `../adr/0020-github-rest-vs-graphql.md`, `../adr/0021-github-write-scope.md`, `../adr/0022-git-cli-vs-jgit.md` — existing GitHub ADRs.
- `ticket-source-extension-contract.md` — the symmetric ticket-source contract.
