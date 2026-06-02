# ADR 0021 — GitHub Write Scope & Egress Security Posture

**Status:** Accepted (2026-06-01)
**Driver:** Story 3.14 — Real GitHub Adapter. AC10 (minimum PAT scopes) + AC6 (redaction-on-egress policy) + the token-handling security posture (AC2).

> **Numbering note (story 3.14 Decision D1):** the epic references a stale `0005-github-write-scope.md`; this decision is authored at `0021` (next sequential after `0020-github-rest-vs-graphql.md`). See ADR 0020 for the numbering rationale.

## Context

The real GitHub adapter authenticates with a personal access token (PAT) supplied by the pilot installer via the `GITHUB_TOKEN` env var, and performs both reads (repo/PR/branch lookup) and writes (draft PR create, PR body update, PR comment). Two policy questions need a recorded decision so installers grant the right scopes and so secret material never leaks on egress.

## Decision

### 1. Minimum PAT scopes (AC10)

The pilot installer grants the **least privilege** needed:

| Capability | Classic PAT scope | Fine-grained equivalent |
|---|---|---|
| Read + link **private** repos | `repo` | Repository **Contents: Read**, **Metadata: Read** |
| Read + link **public-only** repos | `public_repo` | (public repositories, Metadata: Read) |
| Create / update / comment on PRs | covered by `repo` / `public_repo`; fine-grained needs **Pull requests: Read & write** | **Pull requests: Read & write** |
| Push branches (downstream, **story 3.9** `captureAndPush`) | `repo` (write) | **Contents: Read & write** |

- For a private-repo pilot, a single classic `repo` scope (or the fine-grained Contents R/W + Pull requests R/W + Metadata R) covers every capability this adapter and story 3.9 need.
- For a public-only pilot, `public_repo` + Pull requests R/W is sufficient.
- **Do not** grant `admin:*`, `delete_repo`, org-admin, or workflow scopes — none are used. The branch-push capability is noted here because story 3.9's `RepositoryWorkspaceService.captureAndPush` (which builds **after** 3.14) requires it; granting it now avoids a re-issue.

### 2. Redaction-on-egress: redact-and-send (AC6, story 3.14 Decision D3)

All three write methods (`createPullRequest`, `updatePullRequest`, `commentOnPullRequest`) pass `title`/`body` through `RedactionPolicyService.redact(payload, "shareable-redacted")` **before** the request is sent, and transmit `result.sanitizedText()`. The default policy is **redact-and-send**: detected secret patterns are replaced with their `[REDACTED_*]` placeholders and the (now safe) content is still posted, so the governed-run write-back is not silently dropped.

**Alternative considered — refuse-with-`EGRESS_SECRET_DETECTED`.** Refusing the write when a secret is detected would require a new `DomainErrorCode` (and the three-sites manifest rule). It is rejected as the default because (a) it adds a domain code for a path that redact-and-send already makes safe, and (b) dropping a PR comment is worse for AC-level "best-effort write-back" than posting a redacted one. The refuse path remains a documented future option if a payload's redaction would materially alter semantics; it is not implemented in 3.14.

### 3. Token-handling posture (AC2)

- The PAT is read at **request time** inside the `gitHubRestClient` interceptor so rotation is observed without a context refresh.
- The token is **never** logged, **never** embedded in a URL, and **never** persisted to the DB or artifacts. `GitHubProperties.token()` is `@JsonIgnore` and `toString()` redacts it.
- The doctor `github-real` auth probe (AC9) reports token **presence** only and never echoes the value; `GET /user` is the cheap auth check.
- Outbound bodies are redacted on egress but are **still never logged** — the adapter logs refs, counts, categories, statuses, branch names, and rate-limit numbers only.

## Consequences

- Installers have an explicit least-privilege checklist; the doctor probe's FAIL remediation points here.
- A single egress policy (`shareable-redacted`) governs all GitHub write bodies, consistent with the runner-log and Linear-comment egress paths.
- No new `DomainErrorCode` is added for egress (the two new codes in 3.14 are doctor-probe codes, not egress codes).
