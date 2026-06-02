# ADR 0022 — Repository Workspace Git: System `git` CLI behind an SPI vs embedded JGit

**Status:** Accepted (2026-06-02)
**Driver:** Story 3.9 — Repository Workspace Service. AC1/AC2 (clone + branch), AC6/AC7 (commit + push + fail-and-defer), AC10 (credential posture), AC13 (boundary), AC15 (`git --version` doctor probe).

> **Numbering note:** authored at `0022` (next sequential after `0021-github-write-scope.md`). The epic's `0005`-era references are stale (see ADR 0020/0021).

## Context

`RepositoryWorkspaceService` must clone the linked GitHub repository into a per-execution workspace, check out a deterministic feature branch, commit runner-produced changes, and push — failing-and-deferring-to-recovery on push rejection. There is **no existing git machinery in the codebase** (no JGit dependency, no git wrapper; the only `ProcessBuilder` use is `DoctorProbeAdapter`'s `docker version` probe). Two implementation strategies were considered:

1. **System `git` CLI** invoked via `ProcessBuilder`, hidden behind an application-owned SPI port.
2. **Embedded JGit** (`org.eclipse.jgit`), a pure-Java git implementation, also behind an SPI port.

## Decision

Use the **system `git` binary behind a new `GitCommandPort` SPI** (`application.runner.workspace.spi`), implemented by `adapters.git.CliGitAdapter`. JGit is **not** added.

Three forces converge on the CLI:

1. **AC15 makes the system `git` binary a runtime prerequisite.** The doctor command probes `git --version` (PASS / `DOCTOR_GIT_MISSING` FAIL) so pilots discover a missing git before a real run. If we embedded JGit, system git would not be needed and that doctor check would be meaningless — the design intent is the CLI.
2. **Zero new dependency.** JGit would be a new dependency (with its own transitive surface and the cross-platform native-binding caution recorded in memory `frontend-lockfile-cross-platform`). The repo already shells out to `docker` via `ProcessBuilder`; reusing that idiom keeps the dependency graph flat.
3. **`application` must not import `org.dradgo.adapters..`** (ArchUnit `LAYERED_BOUNDARIES` + the story's `REPOSITORY_WORKSPACE_SERVICE_SCOPE` rule, memory `application-cannot-import-adapters`). Even a JGit call would need an SPI port to keep git-library types out of the application layer — so the SPI seam is required either way, and the CLI behind it costs nothing extra architecturally.

The SPI (`GitCommandPort`) speaks only repo paths + refs + domain-shaped records (`CloneSpec`, `CommitSpec`, `PushResult`, `BranchOutcome`) and raises a typed `GitCommandException(IntegrationFailureCategory)` — it never exposes `Process`, exit codes, or any git-library type to the application (Trap T1). This mirrors the `RunnerWorkspaceStore` SPI + `LocalRunnerWorkspaceStore` shape.

### Credential posture (Decision D2 / AC10)

The GitHub PAT (`RunnerSecretsService.resolveHostSecret("GITHUB_TOKEN")`) is injected into the git child process **only** via a per-invocation transient credential helper:

```
git -c credential.helper= \
    -c credential.helper='!f(){ echo username=x-access-token; echo "password=$GIT_PAT"; };f' <op>
```

with `GIT_PAT` set in the child env and `GIT_TERMINAL_PROMPT=0` (anti-hang). The token is **never** placed in the remote URL, **never** persisted to `.git/config`, **never** logged. The leading empty `credential.helper=` clears any inherited system/global helper. Every git stdout/stderr line is routed through `RedactionPolicyService` before any log write. The runner container mounts `/workspace/repo` and therefore must never see a credential file — the `-c` config is command-scoped and is not written to the cloned repo's config.

### Bot identity (Decision D8)

`user.name` / `user.email` are set per-repo from `deliveryline.workflow.bot.*` (default `DeliveryLine Bot` / `deliveryline-bot@dradgo.org`) via `git config` on the workspace repo and `git -c …` on the commit, so the host's global git identity is never mutated. The doctor `probeGitBotIdentity` WARNs (not FAILs) when the operator has not explicitly configured an identity — a bot identity is desirable but a real run proceeds with the built-in default.

## Cross-platform note

`file://` test remotes and path normalization differ on Windows vs Linux. The bare-repo integration tests (`git init --bare` in a `@TempDir`, `file://` remote) need `git` on PATH but no network/Docker/gitea. The `!`-shell credential helper is only invoked for authenticated (`https`) transports — token-less `file://` test remotes never trigger it, so the shell dependency is exercised only in real runs (Git for Windows ships `sh`). A WSL2 Linux smoke of the bare-repo IT plus a Windows local run is the recommended pre-merge gate (memory `wsl-linux-ci-reproduction`).

## Alternatives considered

- **Embedded JGit** — programmatic auth (no PATH dependency), no `sh` requirement for the credential helper, and richer in-process error types. Rejected because it contradicts AC15's `git --version` prerequisite, adds a dependency, and still needs the SPI seam. JGit remains the documented fallback if the system-git PATH dependency ever proves untenable in a target environment.

## Consequences

- A missing/old system `git` is a deployment prerequisite surfaced by `doctor` (`DOCTOR_GIT_MISSING`).
- Push failures are classified from `git` stderr signals into the four additive `IntegrationFailureCategory` git values (`GIT_PUSH_REJECTED`, `GIT_BRANCH_PROTECTION_VIOLATION`, `GIT_NETWORK_FAILURE`, `GIT_AUTH_FAILED`) — never auto-rebased, force-pushed, or retried (AC7/T8).
- The `adapters.git` slice is added to the ArchUnit `ADAPTER_PACKAGE_LAYOUT` allow-list; a `GIT_TYPES_MUST_NOT_LEAK` rule is N/A for the CLI-behind-SPI choice (no git library on the classpath) but would be required if JGit were ever added.
