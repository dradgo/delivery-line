# ADR 0008 — RepositoryHostAdapter Abstraction (Extract from GitHubAdapter)

**Status:** Accepted (2026-06-18)
**Driver:** Story 3.33 — extract a vendor-neutral `RepositoryHostAdapter` port from the GitHub-shaped `GitHubAdapter` (story 3.13) so a future repository host (Bitbucket, GitLab, Gitea, Azure DevOps Repos) is a one-interface, one-contract add rather than a GitHub-shaped-port refactor. Symmetric GitHub sibling of story 3.32 (`TicketSourceAdapter` from `LinearAdapter`, ADR 0007) and a blocker for Epic 3c per-project connector resolution (3c-3).

## Context

The Epic-3 repository integration was built GitHub-first: a single `GitHubAdapter` port with `GitHubRepository`/`GitHubPullRequest`/`GitHubBranch`/`GitHubAdapterException` types, two profile-activated implementations (`GitHubMockAdapter`/`GitHubRealAdapter`), and consumers (`RepositoryWorkspaceService`, `IntegrationLinkService`) typed against the GitHub port. Adding a second repository host would have meant refactoring a GitHub-shaped port in place — exactly the churn Epic 3c wants to avoid.

The acceptance criteria as written in `epic-03-agent-execution.md §Story 3.33` describe an *idealized* greenfield port. The **live contract is authoritative** and diverges in load-bearing ways that this ADR records as decisions.

## Decision

**1. The port becomes vendor-neutral.** `GitHubAdapter` → `application.integration.repohost.RepositoryHostAdapter`; `GitHubRepository`/`GitHubPullRequest`/`GitHubBranch` → `domain.integration.repohost.Repository`/`PullRequest`/`Branch`; `GitHubAdapterException` → `application.integration.repohost.RepositoryHostAdapterException`. New neutral types `RepositoryRef`, `PullRequestRef`, `CommentResult`, `RepositoryHostCapabilities` live in `domain.integration.repohost`. `GitHubMockAdapter`/`GitHubRealAdapter` (and the scenario/fixture support types) move to `adapters.integration.repohost.github` and implement the neutral port. `GitHubProperties` (the GitHub-impl config record) stays in `application.integration.github`. Behavior is preserved byte-for-byte (parity tests prove it).

**2. Refs are opaque `RepositoryRef`/`PullRequestRef` value tokens (OQ-1, the #1 decision).** The live refs are GitHub-shaped strings: a repo ref is `"owner/repo"` and a PR ref is `"owner/repo#number"` (regex-parsed inside `GitHubRealAdapter`, emitted as `prRef`). AC4 forbids "GitHub-specific URL formats" on the neutral types. The neutral `RepositoryRef(String value)` / `PullRequestRef(String value)` carry these as **vendor-uninterpreted opaque tokens** — only the GitHub implementation parses their internal shape; neutral consumers treat them as keys and map `.value()` at the persistence boundary (`integration_links.external_ref` stays a `String`). This is the exact analog of ADR 0007's `sourceStatusId` opaque-token decision and a defensible reading of "no GitHub-specific URL formats" (an opaque string is not a typed GitHub DTO). The format-aware validator (`IntegrationLinkService.assertArtifactPrLinkMatches`) is unchanged — the rename does not tighten or loosen the accepted ref set.

**3. `createPullRequest` gains an explicit `targetBranch` param (OQ-2).** The live method is 4-param and resolves the base/target branch internally to the repository default branch. AC1/AC6 call for an explicit target. The port becomes `createPullRequest(RepositoryRef, sourceBranch, targetBranch, title, body)`. The sole caller (`RepositoryWorkspaceService`) threads the prepare-time-resolved default branch (stamped into git local-config as `deliveryline.defaultBranch` and read back at capture time — the D6 restart-robust pattern), so behavior is preserved (target = default branch). A blank/`null` `targetBranch` is the back-compat path: the real adapter resolves the repository default branch internally exactly as before, so the existing HTTP sequence and parity stubs are unchanged. The idempotency key becomes `(repo, sourceBranch, targetBranch)` (mock) / the open-PR `head=owner:branch&base=targetBranch&state=open` search (real).

**4. The comment method returns `CommentResult` (OQ-3).** The live `commentOnPullRequest` returns `void`. Unlike Linear's *governed* comment (which carries a fingerprint + classification and must keep its shape — ADR 0007), GitHub's comment is already a plain body, so AC1's `CommentResult` return is a clean fit. The port becomes `CommentResult commentOnPullRequest(PullRequestRef, String body)`. The mock dedups on `(prRef, fingerprint(body))` and surfaces a replay as `SKIPPED_DUPLICATE`; the **real adapter has no server-side comment dedup** and always returns `POSTED` (documented asymmetry). No production code calls this method today — it is a dormant port method, so the return-type change is low blast-radius.

**5. Selection adds a `kind` key but keeps Spring profiles (OQ-4).** A new `deliveryline.integration.repo-host.kind` selector (default `github`) is the *documented* selector; `RepositoryHostProperties` normalizes it and `GitHubConfiguration` fail-fasts at boot when `kind` names a host with no implementation on the classpath. The load-bearing bean gating remains the mutually-exclusive `github-mock`/`github-real` Spring profiles, and the `deliveryline.github.*` config keys are unchanged. Renaming those keys to `deliveryline.integration.repo-host.github.*` is an ops-breaking `.env`/deploy change and is intentionally **out of scope** (a future cosmetic migration).

**6. Capabilities are declared, not all consumed (OQ-5).** `RepositoryHostCapabilities.githubDefaults()` declares all five flags `true` (GitHub supports draft PRs, PR comments, branch protection, fork pushes, required status checks). GitHub's optional features have **no live unconditional consumer**: `createPullRequest` has no `draft` parameter and `commentOnPullRequest` has no production caller. The AC10 draft clause is therefore **vacuously satisfied** — no `draft` param is added (YAGNI; preserve behavior). `supportsDraftPullRequests`/`supportsPullRequestComments` are *declared-for-future-consumers*. The foundation contract leans on parity + capability declaration, not behavioral degradation; a forward-looking comment-capability guard is deferred until a real consumer of `commentOnPullRequest` exists (no consumer to host it today).

## Alternatives Considered

### Alt 1 — Fully-structured neutral refs (owner/name/number) instead of opaque tokens

Model `RepositoryRef`/`PullRequestRef` with typed `owner`/`name`/`number` fields and reconstruct vendor strings inside the adapter.

**Rejected.** Larger blast radius and real behavior-drift risk on the existing `prRef` string contract and the format-aware `assertArtifactPrLinkMatches` validator, for no neutrality win an opaque token does not already provide. The opaque token keeps the refactor mechanical and the parity tests honest.

### Alt 2 — Keep the 4-param `createPullRequest` (no explicit `targetBranch`)

Document `targetBranch = default branch` and leave the signature alone.

**Rejected (chose to add the param).** AC1/AC6 call for the explicit target; the sole caller already has the default branch available (stamped at prepare time), so threading it preserves behavior while making the port honest for hosts where base ≠ default. The blank-target back-compat path keeps the change zero-risk.

### Alt 3 — Replace Spring profiles with the `kind` property as the only selector

Rip out `@Profile("github-mock")`/`@Profile("github-real")` and rename `deliveryline.github.*` → `deliveryline.integration.repo-host.github.*`.

**Rejected.** The profile mechanism is load-bearing (bean gating + `assertExclusiveGitHubProfile` + the `gitHubRestClient` bean), and renaming the config keys is an ops-breaking `.env`/deploy change out of scope for an internal refactor. The `kind` key is added as a validated selector alongside profiles.

### Alt 4 — Add a `draft` parameter to `createPullRequest` to satisfy AC10's draft example

**Rejected.** No consumer needs a draft PR; adding the parameter is YAGNI and would change the PR-create payload. `supportsDraftPullRequests` is declared for future consumers; the draft clause is vacuously satisfied.

## Consequences

### Positive

- A future repository host is a one-interface, one-contract add: implement `RepositoryHostAdapter` against `docs/integrations/repository-host-extension-contract.md`, add a `kind` + profile.
- GitHub behavior is preserved byte-for-byte; the parity foundation contract (Contract #15) drives the mock + real adapters through equivalent scenarios.
- Symmetric with story 3.32's `TicketSourceAdapter` (ADR 0007) — a future reader sees one abstraction pattern, not two.
- AC9 (git-protocol independence) holds unchanged: `RepositoryWorkspaceService` clones via the `GitCommandPort` SPI using `repository.url()`; the host adapter handles only API-layer metadata.

### Negative

- The package-rename is the highest-churn part of the story (~25 files across main + test). It is mechanical, but the ArchUnit `REPOSITORY_HOST_TYPES_MUST_NOT_LEAK_THROUGH_PORT` rule + residence rules and the parity tests are the guardrails that keep it honest.
- Refs being opaque means a future strictly-typed ref model is still possible but deferred — neutral consumers must not interpret the token.

### Neutral

- `deliveryline.github.*` config keys and the `github-mock`/`github-real` profiles are unchanged; the only net-new config is the optional `deliveryline.integration.repo-host.kind` selector (default `github`).
- The `IntegrationLinkService` GitHub-flavored helper names (`syncGitHubPr`, `findActiveGitHubPrLink`, `assertArtifactPrLinkMatches`) and the internal `linkGitHubPr:*` idempotency-key prefix / log markers keep their names — a cosmetic rename is a follow-up, not this story. Only the public `linkGitHubPr` method was renamed to `linkPullRequest` (AC8).

## References

- [Source: `_bmad-output/planning-artifacts/epic-03-agent-execution.md#Story 3.33`] — ACs 1–11 (and §3.32 for the symmetric Linear sibling).
- `docs/integrations/repository-host-extension-contract.md` — the documented extension contract for new repository hosts.
- `docs/adr/0007-ticket-source-abstraction.md` — the symmetric ticket-source ADR.
- `docs/adr/0020-github-rest-vs-graphql.md`, `docs/adr/0021-github-write-scope.md`, `docs/adr/0022-git-cli-vs-jgit.md` — existing GitHub ADRs.
- `docs/adr/0013-credential-encryption.md` — Epic-3c credential-encryption primitive that builds on this per-project connector abstraction (story 3c-4).
- `docs/adr/0004-spec-stage-orchestration.md` — ADR format followed here.
