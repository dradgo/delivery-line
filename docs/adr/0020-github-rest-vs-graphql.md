# ADR 0020 — GitHub Integration: REST v3 + Spring RestClient (not the SDK, not GraphQL)

**Status:** Accepted (2026-06-01)
**Driver:** Story 3.14 — Real GitHub Adapter (PR/Branch/Commit Refs + PAT Auth). AC1 requires the real `GitHubAdapter` implementation to choose an HTTP strategy.

> **Numbering note (story 3.14 Decision D1):** the epic text references `docs/adr/0004-github-rest-vs-graphql.md` and `0005-github-write-scope.md`. Those numbers are **stale** — `0004` is already `0004-spec-stage-orchestration.md`. This ADR is authored at the next sequential number after the highest existing (`0019`); the companion write-scope decision is `0021`.

## Context

`GitHubRealAdapter` (`@Profile("github-real")`) must implement the six already-shipped `GitHubAdapter` port methods (story 3.13) against real GitHub: `getRepositoryByRef`, `getPullRequestByRef`, `getBranchByRef`, `createPullRequest`, `updatePullRequest`, `commentOnPullRequest`. Only ~6 endpoints are needed:

- `GET /repos/{owner}/{repo}`
- `GET /repos/{owner}/{repo}/pulls/{number}`
- `GET /repos/{owner}/{repo}/branches/{branch}`
- `GET /repos/{owner}/{repo}/pulls?head=…&base=…&state=open` (idempotency probe)
- `POST /repos/{owner}/{repo}/pulls` (draft PR create)
- `PATCH /repos/{owner}/{repo}/pulls/{number}` + `POST /repos/{owner}/{repo}/issues/{number}/comments`

Three implementation strategies were available: the `org.kohsuke:github-api` SDK, GitHub's GraphQL v4 API, or raw GitHub REST v3 over an HTTP client already on the classpath.

## Decision

**Use GitHub REST API v3 over Spring `RestClient`** (from `spring-web`, already present via `spring-boot-starter-webmvc`). The adapter pins the API version with `Accept: application/vnd.github+json` and `X-GitHub-Api-Version: 2022-11-28`, and authenticates with a request-time `Authorization: Bearer <PAT>` interceptor.

### Why not the `org.kohsuke.github` SDK

1. **Zero new dependency** — `RestClient` is already on the classpath; the SDK would add a transitive tree for ~6 endpoints.
2. **The port firewall would reject it anyway** — the `GITHUB_TYPES_MUST_NOT_LEAK_THROUGH_PORT` ArchUnit rule (story 3.13) forbids `org.kohsuke.github..`/`com.github..` and HTTP-client types in `application.integration.github..`. SDK types would have to be mapped to the domain records inside the adapter regardless, so the SDK buys nothing over raw JSON mapping.
3. **One consistent HTTP idiom** — `LinearRealAdapter` already proves the `RestClient` + `SimpleClientHttpRequestFactory` + request-time-token-interceptor pattern in this repo. Reusing it keeps a single, well-understood integration shape.

### Why not GraphQL v4

GraphQL would mean hand-maintaining query documents (as Linear does) for a handful of simple resource reads/writes that REST expresses directly as URLs. REST v3 is simpler for this surface, needs no query-document resources, and its per-endpoint rate-limit headers (`X-RateLimit-Remaining`/`X-RateLimit-Reset`) are exactly what AC5 inspects.

## Consequences

- The adapter maps GitHub REST JSON to the domain records (`GitHubRepository`/`GitHubPullRequest`/`GitHubBranch`) with Jackson `JsonNode`, never exposing transport types through the port.
- HTTP failures map to the existing GitHub `IntegrationFailureCategory` values (story 3.14 AC7) via a catch-cascade mirroring `LinearRealAdapter`.
- No `org.kohsuke:github-api` entry is added to `pom.xml`. Java 21, Spring Boot 4.
- If a future need (e.g. cross-resource batch reads) makes REST chatty, GraphQL can be revisited per-method without changing the port.
