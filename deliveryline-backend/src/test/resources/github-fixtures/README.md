# GitHub adversarial / conflict test fixtures

Per story 3.13 Task 5 / Dev Notes, these adversarial scenario markers live under
`src/test/resources/github-fixtures/` so they never leak into a `demo` or `local` runtime classpath
(same rule the `linear-fixtures/README.md` states).

Each marker JSON documents a scenario that tests register against
`GitHubMockScenarioRegistry` via `register(new GitHubMockScenario(...))`. The markers themselves
are **not loaded** by the registry — the registry only loads `HAPPY` fixtures from
`src/main/resources/github-fixtures/`. The markers exist to give human readers a single index of
the adversarial / conflict test surface, and to pin the expected `IntegrationFailureCategory` for
each injection ref.

## Happy fixtures (loaded from `src/main/resources/github-fixtures/`, AC3)

| File | repoRef | prRef | source branch | use case |
| ---- | ------- | ----- | ------------- | -------- |
| `github-feature-low-risk.json` | `GH-101` | `PR-101` | `feature/healthz-endpoint` | bounded low-risk feature (mirrors Linear `LIN-101`) |
| `github-bug-fix.json` | `GH-102` | `PR-102` | `fix/pagination-off-by-one` | bug fix (mirrors Linear `LIN-102`) |
| `github-docs.json` | `GH-103` | `PR-103` | `docs/recovery-runbook` | documentation (mirrors Linear `LIN-103`) |

## Adversarial / conflict markers (registered by tests, never auto-loaded)

| File | ref | operation | Behaviour | Expected failure category |
| ---- | --- | --------- | --------- | ------------------------- |
| `repo-not-found-simulation.json` | `repo-not-found` | `getRepositoryByRef` | `REPO_NOT_FOUND` | `github_repo_not_found` |
| `permission-denied-simulation.json` | `pr-403` | `getPullRequestByRef` | `PERMISSION_DENIED` | `github_permission_denied` |
| `rate-limited-simulation.json` | `pr-rate-limited` | `commentOnPullRequest` | `RATE_LIMITED` | `github_rate_limited` |
| `branch-protected-simulation.json` | `protected-branch` | `createPullRequest` (branch arg) | `BRANCH_PROTECTED` | `github_branch_protected` |
| `conflict-simulation.json` | `PR-conflict` | `getPullRequestByRef` | `CONFLICT` | _none — returns a PR whose `repoRef` is `GH-999-unrelated` (AC4); consumed by story 3.12 `PR_REF_CONTEXT_MISMATCH`_ |
