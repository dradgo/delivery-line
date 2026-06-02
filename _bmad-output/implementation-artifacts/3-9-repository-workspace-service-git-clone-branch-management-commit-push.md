# Story 3.9: Repository Workspace Service — Git Clone, Branch Management, Commit/Push

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a backend developer + workflow orchestrator,
I want a `RepositoryWorkspaceService` that clones the linked GitHub repository into a per-execution workspace, checks out a deterministic feature branch (`deliveryline/{ticketRef}/stage-{runIdShort}`), exposes the working tree as a `/workspace/repo` runner mount, captures runner-produced changes after exit, commits + direct-pushes to the target repo, and opens or updates the GitHub PR — **failing-and-deferring-to-recovery on push rejection** (never auto-rebasing, force-pushing, or silently retrying),
so that runners (Codex/Claude per stories 3.3/3.4) have an actual code repository to read, edit, and produce a PR against — closing the gap between runner infrastructure (3.1–3.8) and orchestration (3a-1 / 3.10+).

## Context & Why This Story Exists

This story builds the **git half** of agent execution: the piece that turns "a runner container" into "a runner working on a real cloned repository that produces a real PR." It is a **net-new backend service** — there is **no existing git machinery anywhere in the codebase** (no JGit dependency, no git CLI wrapper; the only `ProcessBuilder` usage today is `DoctorProbeAdapter`'s `docker version` probe). Everything you build here is greenfield, scaffolded against well-established neighbours:

- The **runner workspace store** (`RunnerWorkspaceStore` SPI + `LocalRunnerWorkspaceStore`, story 3.1/3.2) already roots per-execution dirs at `{DELIVERYLINE_HOME}/runner-work/{rex}/` with `input/ output/ logs/` and recursively reaps them via `RunnerWorkspaceCleanupJob`.
- The **GitHub adapter** (`GitHubAdapter` port story 3.13, `GitHubRealAdapter` story 3.14 — just merged) gives you `getRepositoryByRef`, `getBranchByRef`, idempotent `createPullRequest`, `updatePullRequest` — **read-only for this story; do not change the port.**
- The **secrets service** (`RunnerSecretsService`, story 3.5) exposes `resolveHostSecret("GITHUB_TOKEN")` — the **host-side** PAT for clone/push that is explicitly NOT injected into the runner container.
- The **doctor** subsystem (story 3.14 just added `probeGitHubAuth`) is the exact template for this story's two new git probes (AC15).

> **🚨 READ THIS FIRST — the central scope reconciliation.** The epic ACs describe a fully wired end-to-end flow (DockerRunnerAdapter calls `prepareWorkspace`, runner edits the mount, broker calls `captureAndPush`, push failure transitions the run to `Failed` via `WorkflowOrchestrationService`). **But two upstream pieces do not exist yet:** (1) there is **no Linear↔GitHub repository mapping** and **no `repositoryRef` field** anywhere on the workflow run / dispatch path — nothing today knows which repo a run targets; (2) **`WorkflowOrchestrationService` does not exist** (it lands in story 3a-1 / 3.11, which build *after* this one). Therefore this story delivers the **complete, fully-functional, directly-tested `RepositoryWorkspaceService`** (clone / branch / capture / commit / push / PR) plus its git SPI, doctor probes, ArchUnit rule, and three-sites error codes — and wires a **nullable, gated seam** into the dispatch/result path so existing mock and no-repo dispatches are byte-for-byte unchanged. The *population* of `repositoryRef` and the orchestration-owned `Failed` transition are 3a-1's job. See **Decision D0** for the binding contract.

## Stages Served

(Per `sprint-change-proposal-2026-05-26.md` §2.2 — story 3.9 scope-clarification.)

`RepositoryWorkspaceService` serves **all three** runner stages with an **identical workspace lifecycle** — there are no per-stage code branches in clone/branch/capture/push:

| Stage token | `RunnerStage` enum (wire) | Repo role |
|---|---|---|
| `spec-investigation` | `INVESTIGATION` (`investigation`) | **Primary consumer (added by the pivot).** Spec runner reads the cloned tree for real codebase context — consumed by story **3a-2** (spec-stage repo-context bundle) and triggered by story **3a-1** (`dispatchSpecGeneration`). |
| `implementation-plan` | `EXECUTION` (`execution`) | Plan runner reads the tree (story 3.11, deferred). |
| `pr-output` | `EXECUTION` (`execution`) | Implementation runner **edits** the tree; `captureAndPush` commits + pushes + opens the PR (story 3.11/3.12, deferred). |

> The `RunnerStage` enum has only `INVESTIGATION` + `EXECUTION` today (no separate `pr-output` constant). The branch name uses `runIdShort` not the stage, so the deterministic-branch convention is stage-agnostic; the stage only influences the commit trailer and whether `captureAndPush` expects edits.

## Acceptance Criteria

> Criteria are the epic's verbatim ACs (`epic-03-agent-execution.md` §"Story 3.9", lines 179–195) with **binding clarifications** added inline in **bold parentheticals** where the epic wording predates the live code or references not-yet-built upstream pieces.

1. **Given** the `application.runner.workspace` package, **Then** `RepositoryWorkspaceService` exists with methods: `prepareWorkspace(workflowRunId, stage, runnerExecutionId, linearTicketRef, repositoryRef)`, `captureAndPush(runnerExecutionId)`, `cleanupWorkspace(runnerExecutionId)`. **(All three are `application.runner.workspace` methods; git operations are delegated to a NEW `GitCommandPort` SPI — see Decision D1.)**

2. **Given** `prepareWorkspace(...)` invocation **before container launch** **(by `DockerRunnerAdapter` when `repositoryRef` is present on the dispatch request — see Decision D0/D3)**, **Then** the service: (a) reads the linked repository via `GitHubAdapter.getRepositoryByRef(repositoryRef)` (story 3.13) — resolving the `defaultBranch` for later PR base; (b) clones into `{DELIVERYLINE_HOME}/runner-work/{runnerExecutionId}/repo/` over **HTTPS authenticated by the `GITHUB_TOKEN` PAT** (`RunnerSecretsService.resolveHostSecret("GITHUB_TOKEN")`, story 3.5) using a **transient `git -c credential.helper=…` / env-var helper — never an auth URL, never persisted to `.git/config`** (Decision D2); (c) creates and checks out the deterministic branch `deliveryline/{linearTicketRef}/stage-{runIdShort}` where `runIdShort` = last 8 chars of `workflowRunId` **(ticketRef sanitized to a branch-safe slug — Trap T6)**; (d) configures `user.email` + `user.name` to the documented `deliveryline-bot` service-account identity **(from config, default `deliveryline-bot@dradgo.org` / `DeliveryLine Bot` — Decision D8, set per-repo with `git -c` so the global/user config is untouched)**.

3. **Given** branch-creation idempotency across retries (story 3.11/3a-1 retry path), **When** the deterministic branch already exists locally or remotely, **Then**: (a) remote branch with prior commits → fetch + reset local to remote tip (preserving prior runner work for retry context); (b) only-local from a partial prior attempt → reuse; (c) **clean state always — no half-merged/conflicting state ever reaches the runner.**

4. **Given** the runner container's mount layout (story 3.1), **Then** `prepareWorkspace` returns a mount reference that `DockerRunnerAdapter` adds to the `docker run` invocation: **`/workspace/repo` (read-write)**, alongside the existing `/workspace/input` (ro), `/workspace/output` (rw), `/workspace/logs` (rw). **(The `repo/` dir lives under the SAME `{rex}/` root as the other three so the existing `RunnerWorkspaceCleanupJob` recursive delete already reaps it — Decision D3.)**

5. **Given** sparse-checkout / shallow-clone configuration for performance, **Then** per-repository config (`deliveryline.workflow.repos.{repo-key}.clone-depth`, default `1`; `deliveryline.workflow.repos.{repo-key}.sparse-paths`, optional list) lets pilots tune for large repos — defaults work for typical pilot-size repos without configuration. **(New `deliveryline.workflow.*` validated config → add to BOTH `application.yml` files, Trap T2.)**

6. **Given** `captureAndPush(runnerExecutionId)` after the runner exits successfully, **Then** the service: (a) inspects the workspace repo's git state; (b) if uncommitted changes exist → stages all + commits with a documented message template referencing ticket + run + stage; (c) pushes the branch to the target remote; (d) on success returns the pushed **commit SHA + branch reference** for inclusion in the runner's `prOutput` artifact (story 3.11 AC2). **(Branch + origin remote are read from the on-disk repo HEAD/`origin` — self-describing, restart-robust — not an in-memory map, Decision D6.)**

7. **Given** **fail-and-defer-to-recovery on push rejection** ("pause when state uncertain"), **When** `git push` is rejected (target advanced, force-push policy, branch protection, network failure mid-push), **Then** the service: (a) classifies per `IntegrationFailureCategory` — **NEW values** `GIT_PUSH_REJECTED`, `GIT_BRANCH_PROTECTION_VIOLATION`, `GIT_NETWORK_FAILURE`, `GIT_AUTH_FAILED` (Decision D4); (b) appends a `git.pushFailed` event with details; (c) raises the typed failure to its caller, which transitions the run to `Failed` with the category — **never auto-rebases, never force-pushes, never silently retries** (Trap T8). **(`WorkflowOrchestrationService` does not exist yet → for this story the broker maps the typed push failure onto its EXISTING runner-failure transition path; re-homing to orchestration is 3a-1 — OQ-2.)**

8. **Given** PR creation/update via `GitHubAdapter` (story 3.13/3.14), **When** push succeeds, **Then** the service: (a) **relies on `GitHubAdapter.createPullRequest`'s built-in idempotency** (story 3.14 AC4 already does the `head/base&state=open` existing-PR probe before POST — Decision D7); (b) if no open PR → `createPullRequest` creates a **draft** PR titled `[{ticketRef}] {ticketSummary}` with a body referencing the governed run; (c) if a `prRef` is already linked to the run → `updatePullRequest` refreshes the description with the latest run + commit refs; (d) returns the canonical PR reference for the `prOutput` artifact.

9. **Given** wrong-ticket / wrong-repo prevention (NFR20 + story 3.14 AC4), **When** the requested `repositoryRef` cannot be reconciled with the run's existing repository linkage, **Then** `prepareWorkspace` raises **`LINEAR_GITHUB_REPO_MISMATCH`** (NEW `DomainErrorCode`, three-sites — Decision D5) **before any clone**. **(No persisted Linear↔GitHub mapping exists yet — OQ-1. Binding guard for this story: `repositoryRef` MUST resolve via `GitHubAdapter.getRepositoryByRef` (else mismatch), AND if the run already carries a `github_pr` `IntegrationLink` whose repo differs from `repositoryRef` → mismatch.)**

10. **Given** secrets handling (story 3.5), **Then** the `GITHUB_TOKEN` used for clone + push is **never logged** (stdout/stderr/structured logs all redacted), **never written to git config files persisted in the workspace**, and **never exposed to the runner container** — auth handled at the host process level before mounting; the runner sees only the cloned working tree, not the credential-helper config. **(Pinned by a no-token-in-logs + no-token-in-`.git/config` test — Trap T5; git child-process stdout/stderr routed through `RedactionPolicyService` before any log write.)**

11. **Given** `cleanupWorkspace(runnerExecutionId)` invocation by `RunnerWorkspaceCleanupJob` (story 3.2 AC5), **Then** the entire workspace including the cloned repo is deleted after the retention threshold (`deliveryline.runner.docker.workspace-retention-hours`, default 24) — same retention rules as story 3.1 AC7. **(Because `repo/` is under the `{rex}/` root, the existing `LocalRunnerWorkspaceStore.deleteWorkspace` recursive walk already reaps it; `cleanupWorkspace` delegates to that store — minimal new deletion code, Decision D3.)**

12. **Given** append-only history (NFR4 + NFR32), **Then** the commit message includes stable trailers: `Deliveryline-Run: {runId}`, `Deliveryline-Stage: {stage}`, `Deliveryline-RunnerExecution: {rexId}` — git history carries durable governance traceability (Trap T9).

13. **Given** ArchUnit boundary, **Then** `RepositoryWorkspaceService` lives in `application.runner.workspace`; its only collaborators are `GitHubAdapter` (story 3.13), `RunnerSecretsService` (story 3.5), the **`GitCommandPort` SPI**, and the workspace store — **no direct dependency on `DockerRunnerAdapter`** (which calls into this service, not the other way), **no leak of git-library / `org.dradgo.adapters..` types into application or domain** (Trap T1).

14. **Given** the test suite, **Then** integration tests (using a **local bare git repo** as the GitHub stand-in — `git init --bare` in a `@TempDir`, `file://` remote; no gitea/Docker/network, cross-platform) cover: clean clone + branch checkout, idempotent branch reuse on retry, runner-produced changes captured + committed, successful push + PR creation (mock `GitHubAdapter`), push-rejected → `GIT_PUSH_REJECTED` + transition to `Failed` (no auto-retry), `LINEAR_GITHUB_REPO_MISMATCH` on incompatible request, secret-leak scan asserts **no `GITHUB_TOKEN` in workspace files / `.git/config` / logs**, cleanup respects retention.

15. **Given** the doctor command (story 1.16), **Then** when the GitHub real adapter is active, doctor probes `git --version` (PASS / FAIL `DOCTOR_GIT_MISSING`) and validates the configured `deliveryline-bot` identity (PASS / WARN `DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED`) — pilots see git prerequisite issues before a real run. **(Two NEW `DomainErrorCode`s via three-sites; mirror story 3.14's `probeGitHubAuth` wiring exactly — Decision D5.)**

## Tasks / Subtasks

- [x] **Task 1 — `GitCommandPort` SPI + `CliGitAdapter` (system-git-CLI) impl** (AC: 1, 2, 3, 6, 7, 13; Decisions D1, D2)
  - [x] Create `org.dradgo.application.runner.workspace.spi.GitCommandPort` — a project-owned interface with **domain-shaped records** (e.g. `CloneSpec(remoteUrl, targetDir, branch, depth, sparsePaths, botName, botEmail)`, `CommitSpec`, `PushResult`, `GitOutcome`/typed `GitCommandException(IntegrationFailureCategory, …)`). The port speaks **repo paths + refs**, never exposes `Process`/JGit types (Trap T1). Mirror the `RunnerWorkspaceStore` SPI shape (`application.runner.spi`).
  - [x] Implement `org.dradgo.adapters.git.CliGitAdapter` (`@Component`) wrapping the **system `git` binary** via `ProcessBuilder` (copy the timeout / `destroyForcibly` / `redirectErrorStream` pattern from `DoctorProbeAdapter.probeDockerAvailability`, lines ~491–539). Operations: `clone` (with `--depth`, optional sparse-checkout), `checkoutOrReuseBranch` (fetch+reset on remote-exists, AC3), `status`/`add -A`/`commit` (with trailers, AC12), `push`. **Auth:** inject `GITHUB_TOKEN` only via a transient per-invocation `-c credential.helper='!f(){ echo username=x-access-token; echo "password=$GIT_PAT"; };f'` with `GIT_PAT` in the child env — **never** `git remote set-url` with token-in-URL, **never** `.git/config` persistence (AC10, D2).
  - [x] Route every git child-process `stdout`/`stderr` through `RedactionPolicyService.redact(text, "shareable-redacted")` before logging; classify non-zero/error exits to the NEW `IntegrationFailureCategory` git values (push-rejected vs branch-protection vs network vs auth — parse stderr signals).
  - [x] Author **ADR `docs/adr/0022-git-cli-vs-jgit.md`** (Decision D1/D5): system git CLI behind an SPI vs embedded JGit; why CLI (no new dep, doctor `git --version` is the runtime prerequisite, application-cannot-import-adapters forces an SPI either way); cross-platform note; bot-identity posture.
- [x] **Task 2 — `RepositoryWorkspaceService`** (AC: 1, 2, 3, 6, 8, 9, 12, 13)
  - [x] `@Service` in `org.dradgo.application.runner.workspace`. Ctor deps: `GitCommandPort`, `GitHubAdapter`, `RunnerSecretsService`, `RunnerWorkspaceStore` (or a small filesystem helper), `WorkflowEventPort` (for `git.pushFailed`/lifecycle events), `IntegrationLink` read port (for AC9 guard), `WorkflowProperties`/`RunnerProperties` (clone-depth/sparse/bot identity), `Clock`.
  - [x] `prepareWorkspace(...)`: AC9 guard FIRST (resolve `repositoryRef` via `GitHubAdapter.getRepositoryByRef` → empty/throw ⇒ `LINEAR_GITHUB_REPO_MISMATCH`; cross-check existing run `github_pr` `IntegrationLink` repo) → resolve host PAT → compute `repo/` dir under `{rex}` root + branch slug → `GitCommandPort.clone` + `checkoutOrReuseBranch` + bot identity → return the `/workspace/repo` mount reference + resolved `defaultBranch`.
  - [x] `captureAndPush(runnerExecutionId)`: read branch + origin from the on-disk repo (D6) → status/commit-if-dirty (trailers, AC12) → push → on success create/update PR via `GitHubAdapter` (D7) → return `commitSha + branchRef + prRef`; on push rejection raise typed failure (AC7).
  - [x] `cleanupWorkspace(runnerExecutionId)`: delegate to `RunnerWorkspaceStore.deleteWorkspace` (D3).
- [x] **Task 3 — `IntegrationFailureCategory` git values** (AC: 7; Decision D4)
  - [x] Add `GIT_PUSH_REJECTED`, `GIT_BRANCH_PROTECTION_VIOLATION`, `GIT_NETWORK_FAILURE`, `GIT_AUTH_FAILED` to `domain.registry.IntegrationFailureCategory`. **These are ADDITIVE/safe** — the enum is auto-derived into the registry, is NOT in the api-schema-placeholder manifest, and has NO SQL CHECK (confirmed by story 3.13). Do **NOT** treat as three-sites. (Contrast Task 5.)
- [x] **Task 4 — Nullable dispatch seam + adapter mount + broker `captureAndPush` hook** (AC: 2, 4, 7; Decisions D0, D3; Trap T4)
  - [x] Add **nullable** `repositoryRef` + `linearTicketRef` (+ resolved `repoMountPath` after prepare, if threaded) to `RunnerDispatchRequest` — both default-null; the compact ctor keeps the existing required fields unchanged so all current dispatches (mock + no-repo) construct identically.
  - [x] In `DockerRunnerAdapter.dispatch` (around the `CreateContainerSpec` BindMount list, lines ~185–194): when `request.repositoryRef() != null` → call `RepositoryWorkspaceService.prepareWorkspace(...)` and add a 4th `BindMount(repoPath, "/workspace/repo", false)`; when null → unchanged. Inject `RepositoryWorkspaceService` via **`ObjectProvider<RepositoryWorkspaceService>`** to keep the public test ctor signature stable and dodge the `DockerRunnerAdapter` ctor-fan-out trap (Trap T4 / memory `docker-adapter-ctor-dep-fans-out`).
  - [x] In `RunnerBroker.handleSuccess` (after the secret scan ~line 559, **before** the `recordCompleted`/COMPLETED transition ~line 566): when the row had a repo workspace → call `captureAndPush`; on its typed push-failure map to the broker's existing failure transition path + append `git.pushFailed` (OQ-2). Gate so non-repo runs are untouched.
- [x] **Task 5 — Doctor git probes** (AC: 15; Decision D5) — **mirror story 3.14 `probeGitHubAuth` exactly**
  - [x] Add `probeGitAvailability()` + `probeGitBotIdentity()` to `application.diagnostics.spi.DoctorProbePort`; implement in `adapters.diagnostics.DoctorProbeAdapter` (`git --version` via `ProcessLauncher`; bot-identity read from config/`git config --get`, presence-only, **token never logged**). Inactive-github-real ⇒ PASS-not-applicable with **no process call** (mirror the github probe's not-applicable shape).
  - [x] Wire `DoctorService`: `CHECK_GIT_AVAILABLE` + `CHECK_GIT_BOT_IDENTITY` constants + `STATIC_ORDER` entries (after `CHECK_GITHUB_AUTH`) + switch arms + remediation map entries.
  - [x] Add **two `DomainErrorCode`s** `DOCTOR_GIT_MISSING`, `DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED` **AND** `LINEAR_GITHUB_REPO_MISMATCH` (Task 2) via the **three-sites rule**: `DomainErrorCode` enum + `ProblemDetailsCatalog.register(...)` + `src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` `problemTypeUris`. Verify with `-Pfoundation-gate` (memory `new-domainerrorcode-three-sites`). Update any `DoctorProbePort` test mocks + `DoctorServiceTest`/`DoctorLoggingContractTest` `checksRun` count (13 → 15) + `DoctorProbeAdapterTest`.
- [x] **Task 6 — Config + ArchUnit + .env** (AC: 5, 13; Traps T1, T2)
  - [x] Add `deliveryline.workflow.repos.{repo-key}.{clone-depth,sparse-paths}` + `deliveryline.workflow.bot.{email,name}` validated config (new `WorkflowProperties` record in `application`, or extend the existing `deliveryline.workflow` block). **Add the new keys to BOTH `src/main/resources/application.yml` AND `src/test/resources/application.yml`** (test yaml shadows-not-merges — memory `validated-config-needs-test-yaml`, Trap T2). Add `DELIVERYLINE_BOT_EMAIL`/`DELIVERYLINE_BOT_NAME` to `.env.example` (host-side only, never injected into a runner container).
  - [x] Add ArchUnit rule `REPOSITORY_WORKSPACE_SERVICE_SCOPE` to `ArchitectureRuleCatalog` (+ `@ArchTest` in `ArchitectureBoundaryTest`): `RepositoryWorkspaceService` stays in `application.runner.workspace..`, collaborators limited to `application.integration.github..` (port), `application.runner..` (secrets/SPI), `application.security..` (redaction), `java..`, `org.dradgo.domain..`; **must not depend on `org.dradgo.adapters..`** (Trap T1). If JGit were ever added, also a `GIT_TYPES_MUST_NOT_LEAK` rule — N/A for the CLI-behind-SPI choice.
- [x] **Task 7 — Tests** (AC: 3, 6, 7, 9, 10, 14, 15)
  - [x] `RepositoryWorkspaceServiceIT` (or unit+IT) against a **local bare repo** (`git init --bare` `@TempDir`, `file://` remote): clean clone + branch checkout; idempotent branch reuse on retry (remote-exists fetch+reset; local-only reuse); capture + commit (assert trailers, AC12); successful push + `createPullRequest` (mock/`github-mock` `GitHubAdapter`, repos GH-101/102/103); push-rejected (push to a remote with an advanced/protected ref) ⇒ `GIT_PUSH_REJECTED` + `Failed` + no auto-retry (AC7); `LINEAR_GITHUB_REPO_MISMATCH` on incompatible `repositoryRef` (AC9); **secret-leak scan**: assert `GITHUB_TOKEN` value absent from every workspace file, `.git/config`, and captured logs (AC10); cleanup respects retention (AC11/AC14). Tag for git-on-PATH (CI runners have git; gate with an availability check if needed).
  - [x] `DoctorGitProbeTest` (AC15): `git --version` PASS / missing-binary ⇒ `DOCTOR_GIT_MISSING`; bot identity present ⇒ PASS / missing ⇒ WARN `DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED`; github-real inactive ⇒ PASS-not-applicable no call.
  - [x] Foundation gate green (`-Pfoundation-gate`): three-sites manifest/catalog/enum alignment (Contract #3 + #7) for the 3 new `DomainErrorCode`s; ArchUnit (Contract #1) for the new boundary rule.
  - [x] Dispatch-seam regression: existing mock + no-repo dispatch tests stay green (RunnerBroker/DockerRunnerAdapter slice + profile-wiring); add a focused test that a `repositoryRef`-bearing docker dispatch adds the `/workspace/repo` mount and triggers `captureAndPush` on success.
- [x] **Logging instrumentation** (cross-cutting; required on every story)
  - [x] SLF4J structured logs at every `RepositoryWorkspaceService` public method (entry `INFO` with `workflowRunId`+`runnerExecutionId`+`stage`+`repoRef`+`branch`, exit `INFO` with `resolution`+`commitSha?`+`prRef?`), the `GitCommandPort`/`CliGitAdapter` git invocations (`INFO` op start/finish, `WARN` on retry/replay/branch-reuse, `ERROR` only on unexpected failure), every typed failure raise (`WARN` carrying `op`+`category`), and both doctor probes.
  - [x] Parameterized logging only (`log.warn("git push rejected runnerExecutionId={} category={}", rex, category)`) — never concatenation.
  - [x] Levels: `INFO` lifecycle (clone/branch/commit/push/PR), `WARN` recoverable anomalies (branch reuse/fetch-reset, push rejected, idempotent PR replay, bot-identity WARN), `ERROR` only for unhandled failure. `DEBUG` for git stdout/stderr detail — **only after redaction**.
  - [x] Required context keys: `correlationId` (thread from the dispatch request where available), `workflowRunId`, `runnerExecutionId`, `repoRef`, `branch`, `op`, `category`, `commitSha`/`prRef`. Use MDC where the broker already scopes it (`MdcKeys.WORKFLOW_RUN_ID`/`RUNNER_EXECUTION_ID`).
  - [x] **Forbidden in log output:** the `GITHUB_TOKEN`/PAT, any `Authorization`/credential-helper value, auth URLs, raw runner file contents, any secret pattern. Refs / counts / categories / branch names / SHAs only.
  - [x] Pin with a list-appender / `OutputCaptureExtension`: the push-rejected `WARN`, the branch-reuse `WARN`, and a **"token never appears in any log line"** assertion for at least one clone+push cycle.

### Review Findings

- [x] [Review][Patch] PR title lacks the required ticket summary template [deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java:361] — extend the story 3.9 seam so `ticketSummary` can be supplied and the draft PR title follows `[{ticketRef}] {ticketSummary}`.
- [x] [Review][Patch] Repo files are not included in the pre-push secret scan [deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java:79]
- [x] [Review][Patch] Git command timeout is ineffective because output is read before `waitFor(timeout)` [deliveryline-backend/src/main/java/org/dradgo/adapters/git/CliGitAdapter.java:337]
- [x] [Review][Patch] `prepareWorkspace` always reclones, making local-only retry branch reuse unreachable [deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java:160]
- [x] [Review][Patch] Local branch reuse does not force a clean working tree before handing repo to the runner [deliveryline-backend/src/main/java/org/dradgo/adapters/git/CliGitAdapter.java:144]
- [x] [Review][Patch] Repository-backed dispatch can silently run without `/workspace/repo` when the service bean is absent [deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java:254]
- [x] [Review][Patch] Clean/no-change repo worktrees still push and open or update a PR [deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java:226]
- [x] [Review][Patch] GitHub PR create/update failures after a successful push bypass the broker failure mapping [deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java:239]
- [x] [Review][Patch] Existing `github_pr` links that resolve empty are allowed through reconciliation [deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java:294]
- [x] [Review][Patch] `getLocalConfig` treats all git config failures as missing keys [deliveryline-backend/src/main/java/org/dradgo/adapters/git/CliGitAdapter.java:197]
- [x] [Review][Patch] Branch slug generation does not reject invalid git ref components like `..` or `.lock` [deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java:389]
- [x] [Review][Patch] Push-rejection coverage does not assert the broker/run transition to Failed [deliveryline-backend/src/test/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceServiceIT.java:225]

## Dev Notes

### THE two references that matter most

| Concern | File to mirror | Why |
|---|---|---|
| **External-process probe shape** (timeout, `destroyForcibly`, `redirectErrorStream`, IOException = binary-missing) | `adapters/diagnostics/DoctorProbeAdapter.java` `probeDockerAvailability()` (~491–539) | the exact `ProcessBuilder`/`ProcessLauncher` idiom for `CliGitAdapter` + the git doctor probes. |
| **Doctor probe → three-sites `DomainErrorCode` wiring** | story 3.14's `probeGitHubAuth` across `DoctorProbePort` + `DoctorProbeAdapter` + `DoctorService` (`CHECK_GITHUB_AUTH`/`STATIC_ORDER`/switch/remediation) + `DomainErrorCode` + `ProblemDetailsCatalog` + `registry-api-schema-placeholders.json` | AC15 is a near-clone of AC9 from 3.14 — see `3-14-real-github-adapter-…md`. |
| **SPI-port + project-owned-records pattern** (application must not import adapters) | `application/runner/spi/RunnerWorkspaceStore.java` + impl `adapters/files/LocalRunnerWorkspaceStore.java` | the template for `GitCommandPort` + `CliGitAdapter`. |
| **The GitHub port + records (read-only here)** | `application/integration/github/GitHubAdapter.java`, `GitHubRepository/GitHubPullRequest/GitHubBranch/GitHubAdapterException.java` | shipped by 3.13/3.14; call `getRepositoryByRef`/`createPullRequest`/`updatePullRequest`. |
| **Host-side secret resolution** | `application/runner/RunnerSecretsService.java` `resolveHostSecret(String)` (~124–132) | obtain `GITHUB_TOKEN` for clone/push — host-only, never container-injected. |
| **Dispatch/result hook points** | `application/runner/RunnerBroker.java` `dispatch` (~283–292 builds `RunnerDispatchRequest`) + `handleSuccess` (~559–566) and `adapters/runner/DockerRunnerAdapter.java` `dispatch` (BindMount list ~185–194) | where the nullable repo seam + `captureAndPush` hook attach. |

### Decision D0 — scope contract: deliver the full service + a NULLABLE gated seam; do NOT depend on what builds later

`repositoryRef` has **no source today** (no Linear↔GitHub mapping, no field on the run/dispatch path) and **`WorkflowOrchestrationService` does not exist** (3a-1/3.11 build after this). Binding contract:

- **Deliver fully & test directly:** `RepositoryWorkspaceService` + `GitCommandPort`/`CliGitAdapter` + doctor probes + ArchUnit + the 3 three-sites codes + the 4 additive git failure categories. The service is proven by **service-level integration tests against a local bare repo** (AC14) — these do not need the dispatch path.
- **Wire a gated seam:** nullable `repositoryRef`/`linearTicketRef` on `RunnerDispatchRequest`; `DockerRunnerAdapter` mounts `/workspace/repo` + calls `prepareWorkspace` only when present; broker calls `captureAndPush` only when a repo was prepared. **All existing mock + no-repo dispatches stay byte-for-byte identical → the ~700-test fast tier stays green.**
- **Do NOT build:** the resolution of *which repo a run targets* (3a-1 + a mapping/config), nor an orchestration-owned `Failed` transition (3a-1). The broker's existing runner-failure path carries the push-failure transition for now (OQ-2). Mirror story 3.14 Decision D7 ("build self-contained; don't import what doesn't exist").

### Decision D1 — git via SYSTEM git CLI behind a NEW SPI port — NOT JGit, NOT direct ProcessBuilder in application

Three forces converge on this:
1. **AC15 makes the system `git` binary a runtime prerequisite** (`git --version` PASS / `DOCTOR_GIT_MISSING` FAIL). If we embedded JGit, system git wouldn't be needed and the doctor check would be meaningless — so the design intent is the CLI.
2. **No new dependency** (JGit would be one; cross-platform native-binding caution per memory `frontend-lockfile-cross-platform`). The repo already shells out to `docker` via `ProcessBuilder`.
3. **`application` must not import `org.dradgo.adapters..`** (memory `application-cannot-import-adapters`) — so even a JGit call would need an SPI port. Use the same `RunnerWorkspaceStore`-style SPI: `GitCommandPort` (application) + `CliGitAdapter` (adapters.git).

JGit remains the documented alternative in ADR 0022 (programmatic auth, no PATH dependency) — but CLI is the binding choice. **Cross-platform:** `file://` test remotes + path normalization differ on Windows; do a WSL2 Linux smoke (memory `wsl-linux-ci-reproduction`) and a Windows local run before merge.

### Decision D2 — credentials: transient `-c credential.helper`, never token-in-URL, never persisted

`GITHUB_TOKEN` from `RunnerSecretsService.resolveHostSecret("GITHUB_TOKEN")`. Inject per-invocation via a `git -c credential.helper='!f(){ echo username=x-access-token; echo "password=$GIT_PAT"; };f'` with `GIT_PAT` in the **child process env only**. **Never** `https://x-access-token:{token}@github.com/...` in the remote URL (persists to `.git/config`), **never** `git remote set-url` with a token, **never** a credential file inside the workspace (the runner mounts `/workspace/repo` → it must not see credentials, AC10). The `-c` config is command-scoped and never written to the cloned repo's config. Pin the no-token-in-`.git/config` invariant in tests.

### Decision D3 — clone under the `{rex}/` root so existing cleanup reaps it for free

Clone into `{DELIVERYLINE_HOME}/runner-work/{rex}/repo/` (sibling of `input/ output/ logs/`). `LocalRunnerWorkspaceStore.deleteWorkspace` already `walkAndDelete`s the whole `{rex}` root, so `RunnerWorkspaceCleanupJob` reaps the clone with **zero new deletion logic** (AC11). `cleanupWorkspace(rex)` just delegates to `workspaceStore.deleteWorkspace(rex)` (exists for explicit/early cleanup + lifecycle symmetry). Add the `repo/` dir with runner-writable perms mirroring the `output/` perms in `LocalRunnerWorkspaceStore.prepare` (extend `WorkspaceLayout`/`prepare`, or create the dir from the service — prefer extending the store so perms stay centralized).

### Decision D4 — git `IntegrationFailureCategory` values are ADDITIVE (NOT three-sites)

`GIT_PUSH_REJECTED`, `GIT_BRANCH_PROTECTION_VIOLATION`, `GIT_NETWORK_FAILURE`, `GIT_AUTH_FAILED` are added to `IntegrationFailureCategory` only. Story 3.13 confirmed this enum is auto-derived into `DomainRegistry`, is **NOT** in the api-schema-placeholder manifest, and has **NO** SQL CHECK constraint — so additions are safe and require no openapi/schema.d.ts regen. (`GITHUB_BRANCH_PROTECTED` already exists for the *adapter's* 422 on PR-create; the new `GIT_BRANCH_PROTECTION_VIOLATION` is the *push-time* git rejection — distinct surface, keep both.)

### Decision D5 — the 3 new `DomainErrorCode`s ARE three-sites

`LINEAR_GITHUB_REPO_MISMATCH` (AC9, HTTP 409 CONFLICT), `DOCTOR_GIT_MISSING` (AC15, 503), `DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED` (AC15, 503). Per memory `new-domainerrorcode-three-sites` + story 3.14's proven path, each needs: (1) `domain.registry.DomainErrorCode` enum constant; (2) `adapters.rest.ProblemDetailsCatalog.register(...)`; (3) `registry-api-schema-placeholders.json` `problemTypeUris` entry (slug = lower-kebab of the name). The `ProblemDetailsCatalog` static init throws if any code is unmapped; `RegistryContractTest.domainErrorCodesStayAlignedWithProblemTypeOwnershipManifest` (foundation gate Contract #3) asserts all three sets are equal. `INTEGRATION_LINK_CONFLICT` already exists but is semantically wrong for a repo-mismatch — do not reuse it.

### Decision D6 — `captureAndPush` reads state from the on-disk repo (restart-robust)

`captureAndPush(rex)` takes only the rex id. Rather than an in-memory `rex → (repoRef, branch)` map (lost on restart), derive everything from the on-disk `{rex}/repo`: current branch from `HEAD`, remote from `origin`. The working tree is self-describing. (Deep restart/orphan recovery of in-flight pushes is Epic 4 — OQ-4.)

### Decision D7 — lean on `GitHubAdapter.createPullRequest` idempotency; don't reimplement the existing-PR probe

Story 3.14 `createPullRequest` already does `GET …/pulls?head={owner}:{branch}&base={default_branch}&state=open` and returns the existing PR with no POST. The mock dedupes on `(repoRef, sourceBranch)`. So AC8(a)'s "check if a PR exists" is satisfied by *calling* `createPullRequest` — do not add a separate `getPullRequestByRef`-by-branch pre-check (that method takes a `prRef`, not a branch). Use `updatePullRequest(prRef, body)` only when a `prRef` is already linked to the run.

### Decision D8 — `deliveryline-bot` identity is config-driven, set per-repo with `git -c`

Default `user.name = "DeliveryLine Bot"`, `user.email = "deliveryline-bot@dradgo.org"` from `deliveryline.workflow.bot.*` (overridable via `DELIVERYLINE_BOT_NAME`/`DELIVERYLINE_BOT_EMAIL` in `.env`). Set via `git -c user.name=… -c user.email=…` on the commit (or per-clone `git config`) so the host's global git identity is never mutated. The doctor `probeGitBotIdentity` (AC15) WARNs (not FAILs) when unconfigured — a bot identity is desirable but a real run can proceed with whatever git identity resolves.

### Key open questions (all carry a recommendation — proceed unless the architect objects)

- **OQ-1 — `repositoryRef` source / Linear↔GitHub mapping.** None exists. **Recommend:** caller-supplied `repositoryRef`; for the pilot single-repo active-slice, resolve from `deliveryline.workflow.repos` config (or a per-run field added by 3a-1). Full Linear→repo resolution is story 3.32/3.33 (`RepositoryHostAdapter`/ticketsource). AC9 guard for this story = "resolves via `getRepositoryByRef` AND doesn't conflict with an existing run `github_pr` link."
- **OQ-2 — `Failed`-transition ownership on push rejection.** `WorkflowOrchestrationService` absent. **Recommend:** the broker maps the typed push failure onto its existing runner-failure transition (`WorkflowTransitionService`) + `git.pushFailed` event now; re-home to `WorkflowOrchestrationService.onRunnerOutcome` in 3a-1 (note the coupling in a comment, à la story 3.5's review fix).
- **OQ-3 — `prepareWorkspace` caller: adapter vs broker.** Epic AC2/AC13 say `DockerRunnerAdapter` calls it. **Recommend:** keep it adapter-called via `ObjectProvider<RepositoryWorkspaceService>` (gated on `repositoryRef`) to honor AC13 and dodge the ctor-fan-out trap (T4); `captureAndPush` stays broker-called (adapter-agnostic, post-success). Document the cross-layer lifecycle.
- **OQ-4 — restart recovery of an interrupted push.** Out of scope here (Epic 4). D6's on-disk derivation makes a same-process retry safe; cross-restart reconciliation is `RecoveryService`'s job.
- **OQ-5 — sparse-checkout default.** AC5 lists `sparse-paths` optional. **Recommend:** shallow `--depth 1` default ON, sparse OFF by default (full tree) — most pilot repos are small; sparse only when configured.
- **OQ-6 — `WorkflowProperties` placement.** **Recommend:** new `application.WorkflowProperties` `@ConfigurationProperties("deliveryline.workflow")` record (the `deliveryline.workflow` block currently has only `spec-rejection-escalation-threshold` inline) — non-throwing compact ctor with defaults (Decision D6 of story 3.14 dodges the validated-config test-yaml trap), keys mirrored into both yamls.

### Traps (verified against live code)

- **T1 (memory `application-cannot-import-adapters`):** `RepositoryWorkspaceService` (application) must reach git via the `GitCommandPort` SPI, not import `CliGitAdapter` or any `org.dradgo.adapters..` / git-library type. New ArchUnit rule pins it (Task 6).
- **T2 (memory `validated-config-needs-test-yaml`):** new `deliveryline.workflow.repos.*` + `deliveryline.workflow.bot.*` keys MUST be added to **both** `src/main/resources/application.yml` and `src/test/resources/application.yml` (the test yaml shadows, not merges) or the whole `@SpringBootTest` tier fails at startup.
- **T3 (memory `new-domainerrorcode-three-sites`):** the 3 new `DomainErrorCode`s need enum + `ProblemDetailsCatalog` + manifest; verify `-Pfoundation-gate`.
- **T4 (memory `docker-adapter-ctor-dep-fans-out`):** adding a ctor dep to `DockerRunnerAdapter` breaks both profile-wiring slice tests (need `@Bean` mocks) + every `new DockerRunnerAdapter(...)` site. Inject `RepositoryWorkspaceService` via `ObjectProvider` to keep the existing public ctor stable.
- **T5:** `GITHUB_TOKEN` never in logs, never in `.git/config`, never visible to the runner — git child-process output through `RedactionPolicyService` before logging; pin with a no-token assertion.
- **T6:** Linear ticket refs can contain `/`, spaces, uppercase — sanitize to a branch-safe slug for `deliveryline/{ticketRef}/stage-{runIdShort}`; `runIdShort` = last 8 chars of `workflowRunId`.
- **T7 (AC3):** idempotent branch reuse — remote-exists ⇒ fetch+reset; local-only ⇒ reuse; never a half-merged tree to the runner.
- **T8 (AC7):** push failure ⇒ classify + event + defer; **never** auto-rebase / force-push / silent retry.
- **T9 (AC12):** commit trailers `Deliveryline-Run` / `Deliveryline-Stage` / `Deliveryline-RunnerExecution`.

### Logging Requirements (project-wide standard)

- **Framework:** SLF4J + Logback (Spring Boot default). No `System.out`, no `printStackTrace()`. ADR `0019-structured-logging` governs format.
- **Surface = `RepositoryWorkspaceService` + `CliGitAdapter` + the two doctor probes.** INFO lifecycle (clone/branch/commit/push/PR + probe PASS), WARN recoverable (branch reuse/fetch-reset, push rejected, idempotent PR replay, bot-identity WARN), ERROR only for unhandled failure; DEBUG git output only after redaction.
- **Required context keys:** `correlationId` (where threaded), `workflowRunId`, `runnerExecutionId`, `repoRef`, `branch`, `op`, `category`, `commitSha`/`prRef` — via MDC where the broker scopes it.
- **Forbidden:** PAT/`Authorization`/credential-helper values, auth URLs, raw runner file content, any secret pattern.
- **Test contract:** pin push-rejected WARN, branch-reuse WARN, and a token-never-logged assertion (list-appender / `OutputCaptureExtension`).

### Project Structure Notes

- Backend module is **`deliveryline-backend/`** (planning docs sometimes say `backend/`). Base package `org.dradgo`. Java 21, Spring Boot 4.0.6, no JGit (CLI-behind-SPI choice).
- Placement: service → `application.runner.workspace`; git SPI → `application.runner.workspace.spi.GitCommandPort` + project-owned records; git adapter → `adapters.git.CliGitAdapter`; new `IntegrationFailureCategory` values → `domain.registry`; new `DomainErrorCode`s → `domain.registry` (+ `ProblemDetailsCatalog` + manifest); doctor probes → `DoctorProbePort` + `DoctorProbeAdapter` + `DoctorService`; config → `application.WorkflowProperties` + both yamls; ADR → `docs/adr/0022-git-cli-vs-jgit.md`.
- **No Flyway migration:** the commit SHA + branch + PR refs are **transient return values** for the `prOutput` artifact (story 3.11) — nothing is persisted to `runner_executions` by this story (max migration is V11; do not add V12 here).
- **Verification commands** (PowerShell, per memory `rtk-hook-only-matches-bash` — native file tools + PowerShell, not Bash-routed grep):
  - Focused slice: `mvnw -pl deliveryline-backend test -Dtest=RepositoryWorkspaceServiceTest,DoctorGitProbeTest,DoctorServiceTest`
  - Local-bare-repo IT: `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=RepositoryWorkspaceServiceIT` (needs `git` on PATH)
  - ArchUnit: `mvnw -pl deliveryline-backend failsafe:integration-test -Dit.test=ArchitectureBoundaryTest`
  - **Foundation gate (REQUIRED — 3 new `DomainErrorCode`s are manifest-gated):** `mvnw -pl deliveryline-backend -Pfoundation-gate verify -Dtest=ZzzNone -Dsurefire.failIfNoSpecifiedTests=false` (run through the `verify` lifecycle, not the direct `failsafe:integration-test` goal — see story 3.14 invocation note).
  - Static + full fast tier: `mvnw -pl deliveryline-backend spotless:apply checkstyle:check` then `mvnw -pl deliveryline-backend test`.
  - **WSL2 Linux smoke** (memory `wsl-linux-ci-reproduction`): re-run the bare-repo IT natively in WSL2 — git `file://` + path behavior differs from Windows; do a Windows local run too. No new Maven dep / lockfile / frontend / runner-image change, so no other cross-platform smoke needed.

### References

- [Source: _bmad-output/planning-artifacts/epic-03-agent-execution.md#Story-3.9] — ACs 1–15 (lines 179–195); stages-served extension (lines 7, 10); downstream consumers 3.10 AC2 (line 206), 3a-1 (lines 742–761), 3a-2 (lines 763–781).
- [Source: _bmad-output/planning-artifacts/sprint-change-proposal-2026-05-26.md#2.2] — story 3.9 scope-clarification: now also serves `spec-investigation` (lines 124–131).
- [Source: _bmad-output/implementation-artifacts/3-14-real-github-adapter-pr-branch-commit-refs-and-pat-auth.md] — the doctor-probe three-sites template (AC9/Decision D4), `GitHubAdapter` write methods + idempotency, GitHub `IntegrationFailureCategory` set, application-package `@ConfigurationProperties` registration, foundation-gate invocation note.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/integration/github/GitHubAdapter.java + GitHubRepository/GitHubPullRequest/GitHubBranch/GitHubAdapterException.java] — port + records (read-only; 3.13/3.14).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerSecretsService.java#L124] — `resolveHostSecret("GITHUB_TOKEN")` host-side PAT (story 3.5).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java + adapters/files/LocalRunnerWorkspaceStore.java] — SPI + filesystem store template; `prepare`/`deleteWorkspace`/`WorkspaceLayout`; `{rex}/` root + perms.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerWorkspaceCleanupJob.java#L82] — retention sweep that already reaps `{rex}/` (AC11).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java#L185] — BindMount list to extend with `/workspace/repo` (AC4).
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java#L283,#L559] — dispatch-request construction + `handleSuccess` `captureAndPush` hook point.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerDispatchRequest.java] — record to extend with nullable `repositoryRef`/`linearTicketRef` (Task 4).
- [Source: deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java#L491] — `ProcessBuilder`/`ProcessLauncher` `probeDockerAvailability` idiom for `CliGitAdapter` + git probes.
- [Source: deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java + DoctorService.java] — probe SPI + `CHECK_*`/`STATIC_ORDER`/switch/remediation to extend (AC15).
- [Source: deliveryline-backend/src/main/java/org/dradgo/domain/registry/IntegrationFailureCategory.java] — add 4 git values (additive, Decision D4); [DomainErrorCode.java] + [adapters/rest/ProblemDetailsCatalog.java] + [src/test/resources/contracts/openapi/registry-api-schema-placeholders.json] — three-sites for the 3 new codes (Decision D5).
- [Source: deliveryline-backend/src/main/resources/application.yml + src/test/resources/application.yml] — `deliveryline.workflow`/`deliveryline.runner.docker` blocks; mirror new keys into both (Trap T2).
- [Source: deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java + ArchitectureBoundaryTest.java] — `namedRule` pattern for the new boundary rule (AC13).
- [Source: docs/adr/] — existing `0001`–`0004`, `0019`–`0021`; author `0022-git-cli-vs-jgit.md` (next sequential).
- [Source: .env.example] — existing `GITHUB_TOKEN` entry; add `DELIVERYLINE_BOT_EMAIL`/`DELIVERYLINE_BOT_NAME`.

## Dev Agent Record

### Agent Model Used

Opus 4.8 (1M context) — `claude-opus-4-8[1m]`

### Debug Log References

- Full fast Surefire tier: **723 tests / 0 failures / 0 errors / 11 skipped** (via PowerShell, memory `rtk-hook-only-matches-bash`).
- `RepositoryWorkspaceServiceIT` (real local bare-repo, AC14): **7/7** through the `verify` lifecycle (the direct `failsafe:integration-test` goal hits the known `@{argLine}` jacoco issue — run via `verify`, per story 3.14's note).
- Foundation gate (`-Pfoundation-gate verify`): **11/11 contracts green** — Contract #3 (registry/three-sites drift), #1 (ArchUnit boundaries incl. the new `REPOSITORY_WORKSPACE_SERVICE_SCOPE` + `adapters.git` slice), #7 (ProblemDetails coverage), #10, #11.
- `spotless:check` clean (17 files reformatted via `spotless:apply`); `checkstyle:check` 0 violations.
- Full `verify` (all failsafe contracts) surfaced only the documented order-dependent `WorkflowReadEndpointsContractTest` isolation flake (memory `workflow-read-endpoints-test-isolation-flake`) — confirmed **8/8 in isolation**; not a regression.

### Completion Notes List

Implemented the full git half of agent execution + a nullable, profile-gated dispatch seam (Decision D0). All 15 ACs + Logging instrumentation delivered and directly tested.

- **Task 1 — `GitCommandPort` SPI + `CliGitAdapter` (Decision D1/D2/D4).** New `application.runner.workspace.spi.GitCommandPort` (project-owned records `CloneSpec`/`CommitSpec`/`PushResult`/`BranchOutcome` + typed `GitCommandException(IntegrationFailureCategory)`) implemented by `adapters.git.CliGitAdapter` wrapping the system `git` binary via `ProcessBuilder` (mirrors `DoctorProbeAdapter`'s timeout/`destroyForcibly`/`redirectErrorStream` idiom). Transient `-c credential.helper` x-access-token auth (`GIT_PAT` in child env, `GIT_TERMINAL_PROMPT=0`); never token-in-URL / `.git/config` / logs. Every stdout/stderr routed through `RedactionPolicyService` before logging; push stderr classified to the 4 new git categories. ADR `docs/adr/0022-git-cli-vs-jgit.md` authored.
- **Task 2 — `RepositoryWorkspaceService`.** `prepareWorkspace` (AC9 guard FIRST → clone → bot identity → deterministic-branch checkout → stamp self-describing markers, Decision D6), `captureAndPush(rex)` (on-disk-derived commit-with-trailers → push → create/update PR), `cleanupWorkspace` (delegates to the store). Bean-gated `@Profile({"github-mock","github-real"})` so it exists exactly when a `GitHubAdapter` bean does.
- **Task 3 — `IntegrationFailureCategory`** +4 git values (additive/safe, not three-sites).
- **Task 4 — dispatch seam.** Nullable `repositoryRef`/`linearTicketRef` on `RunnerDispatchRequest` (back-compat 7-arg ctor preserved); `DockerRunnerAdapter` mounts `/workspace/repo` (rw) + calls `prepareWorkspace` only when `repositoryRef` present (injected via `ObjectProvider`, Trap T4); `RunnerBroker.handleSuccess` calls `captureAndPush` before completion and maps a push rejection onto the existing `RUNNER_FAILED` + `driveWorkflowFailed` path (OQ-2). All existing mock/no-repo dispatches are byte-for-byte unchanged (service is null in the fast-tier ctors).
- **Task 5 — doctor git probes + three-sites codes.** `probeGitAvailability` (`git --version`, `DOCTOR_GIT_MISSING`) + `probeGitBotIdentity` (`DOCTOR_GIT_BOT_IDENTITY_UNCONFIGURED` WARN) mirror `probeGitHubAuth` (PASS-not-applicable + no process call when github-real inactive). 3 new `DomainErrorCode`s (`LINEAR_GITHUB_REPO_MISMATCH` 409 + the two 503 doctor codes) via three-sites (enum + `ProblemDetailsCatalog` + manifest); `checksRun` 13 → 15.
- **Task 6 — config + ArchUnit + .env.** New `application.workflow.WorkflowProperties` (`@ConfigurationProperties("deliveryline.workflow")`, normalize-never-throw) registered by `WorkflowConfiguration`; `deliveryline.workflow.bot.*` mirrored into BOTH yamls (Trap T2); `DELIVERYLINE_BOT_*` in `.env.example`; `REPOSITORY_WORKSPACE_SERVICE_SCOPE` ArchUnit rule + `adapters.git` added to `ADAPTER_PACKAGE_LAYOUT`.
- **Task 7 — tests.** `RepositoryWorkspaceServiceIT` (real bare repo: clone+branch, idempotent reset-to-remote, capture+commit-trailers, push+PR, push-rejected→`GIT_PUSH_REJECTED` no-retry, repo-mismatch, token-never-in-files/`.git/config`/logs, cleanup), `RepositoryWorkspaceServiceTest`, `CliGitAdapterTest`, `DoctorGitProbeTest`, + `DockerRunnerAdapterUnitTest` repo-mount regression.

**Deviations / decisions worth review:**
1. **`git.pushFailed` event (AC7 / OQ-2):** emitted via the existing `RUNNER_FAILED` `WorkflowEventType` (reason `git_push_rejected`; precise git `IntegrationFailureCategory` + `event: git.pushFailed` in `details`) rather than a new `WorkflowEventType` — a dedicated type is schema-gated (`workflow-event-types.fixture.json` + response schema), which story 3.6 Trap T6 deliberately avoided. Run-state transition uses `FailureCategory.RUNNER_CONTRACT_VIOLATION` (no new `FailureCategory`, Decision D4 scopes new values to `IntegrationFailureCategory` only).
2. **Cross-platform cleanup hardening:** `LocalRunnerWorkspaceStore.walkAndDelete` now clears the read-only bit before `Files.delete` — git writes pack/object files read-only, which `Files.delete` cannot remove on Windows (`AccessDeniedException`). No-op on POSIX; keeps AC11's recursive reap cross-platform. (Surfaced by the IT on Windows.)
3. **Context-load gate:** `RepositoryWorkspaceService` is `@Profile({"github-mock","github-real"})` — an unconditional `@Service` requiring `GitHubAdapter` broke every `@SpringBootTest` context lacking a github profile (caught by the foundation gate, fixed, re-verified green).
4. **OQ-1 (no Linear↔GitHub mapping):** `repositoryRef`/`ticketRef` are stamped into the repo's local git config in `prepareWorkspace` and re-derived in `captureAndPush` (Decision D6); when no `repoRef` is present the PR step is skipped (deferred to 3a-1/3.32), commit+push still occur.

**Recommended follow-ups:** code-review with a different LLM; CI/clean-env run of the foundation gate + a WSL2 Linux smoke of `RepositoryWorkspaceServiceIT` (memory `wsl-linux-ci-reproduction` — git `file://`/path behavior + the read-only-delete path differ from Windows). No new Maven dep / lockfile / frontend / runner-image change.

### File List

**New (main):**
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceService.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/spi/GitCommandPort.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/workspace/spi/GitCommandException.java`
- `deliveryline-backend/src/main/java/org/dradgo/adapters/git/CliGitAdapter.java`
- `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowProperties.java`
- `deliveryline-backend/src/main/java/org/dradgo/infrastructure/config/WorkflowConfiguration.java`
- `docs/adr/0022-git-cli-vs-jgit.md`

**New (test):**
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceServiceTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/application/runner/workspace/RepositoryWorkspaceServiceIT.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/git/CliGitAdapterTest.java`
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorGitProbeTest.java`

**Modified (main):**
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/IntegrationFailureCategory.java` (+4 git values)
- `deliveryline-backend/src/main/java/org/dradgo/domain/registry/DomainErrorCode.java` (+3 three-sites codes)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/rest/ProblemDetailsCatalog.java` (+3 registrations)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java` (+`prepareRepositoryDir`/`resolveRepositoryDir`)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java` (repo dir + read-only-safe delete)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerDispatchRequest.java` (nullable repo seam + back-compat ctor)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java` (repo mount + ObjectProvider seam)
- `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` (captureAndPush hook + `appendGitPushFailedEvent`)
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/spi/DoctorProbePort.java` (+2 probes)
- `deliveryline-backend/src/main/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapter.java` (+2 probes + WorkflowProperties)
- `deliveryline-backend/src/main/java/org/dradgo/application/diagnostics/DoctorService.java` (+2 checks, STATIC_ORDER, switch, remediation)
- `deliveryline-backend/src/main/resources/application.yml` (`deliveryline.workflow.bot/repos`)
- `.env.example` (`DELIVERYLINE_BOT_NAME`/`DELIVERYLINE_BOT_EMAIL`)

**Modified (test):**
- `deliveryline-backend/src/test/resources/contracts/openapi/registry-api-schema-placeholders.json` (+3 problemTypeUris)
- `deliveryline-backend/src/test/resources/application.yml` (`deliveryline.workflow.bot`)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureRuleCatalog.java` (+rule, +`adapters.git` in layout)
- `deliveryline-backend/src/test/java/org/dradgo/architecture/ArchitectureBoundaryTest.java` (+@ArchTest)
- `deliveryline-backend/src/test/java/org/dradgo/application/diagnostics/DoctorServiceTest.java` (git probe stubs)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/cli/DoctorLoggingContractTest.java` (git probe stubs + checksRun 15)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/diagnostics/DoctorProbeAdapterTest.java` (ctor arg)
- `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java` (repo-mount test)

### Change Log

| Date | Change |
|---|---|
| 2026-06-02 | Story 3.9 implemented (`ready-for-dev → in-progress → review`): RepositoryWorkspaceService + GitCommandPort SPI/CliGitAdapter + doctor git probes + 3 three-sites DomainErrorCodes + 4 additive git IntegrationFailureCategory values + nullable dispatch seam + WorkflowProperties + ArchUnit rule + ADR 0022. All 15 ACs + Logging instrumentation. Verified: fast tier 723/0, RepositoryWorkspaceServiceIT 7/7, foundation gate 11/11, spotless + checkstyle clean. |
