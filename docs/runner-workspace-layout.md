# Runner Workspace Layout

Story 3.1 introduces a per-execution workspace tree used by the real Docker runner adapter
(`DockerRunnerAdapter`, profile-gated to `runners.docker`). This page documents the host-side
layout, the container-side mount paths, the bind-mount permissions, the network posture, the
label set, and the retention policy stub that story 3.2's cleanup job enforces.

> **Scope.** This story owns the *creation* of the workspace tree and the *file-based
> handoff* between the broker and the runner container. Timeout enforcement, cleanup, secret
> injection, log capture, and the Codex/Claude entrypoints are explicit follow-ups (stories
> 3.2, 3.5, 3.6, 3.3/3.4).

## Host-side layout

```
${deliveryline.home}/
├── runner-scratch/          # story 1.13 — broker-owned bundle + result leaf files
│   └── {rex_id}/
│       ├── context-bundle.v1.json
│       └── runner-result.v1.json
└── runner-work/             # story 3.1 — adapter-owned bind-mount tree
    └── {rex_id}/
        ├── input/           # ro mount, holds the redacted context bundle
        │   └── context-bundle.v1.json
        ├── output/          # rw mount, runner writes runner-result.v1.json + artifact refs
        │   └── runner-result.v1.json (written by the runner)
        └── logs/             # rw mount, runner writes its own log files (story 3.6 captures)
```

`runner-scratch/` and `runner-work/` are deliberately separate directory trees:

- **Lifetime.** Scratch survives the broker (it is the broker's own correlation surface across
  process restarts). Workspace survives the runner exit *plus* the
  `deliveryline.runner.docker.workspace-retention-hours` window (default `24`, declared on the
  `RunnerProperties.Docker` record so story 3.2's cleanup job has a property to read).
- **Ownership.** Scratch is owned by the broker (`RunnerScratchStore`). Workspace is owned by
  the runner adapter (`RunnerWorkspaceStore`).
- **Permissions.** Both trees are created with POSIX `0700` on Linux/macOS; on Windows the
  permission step is skipped and the host ACL applies.

## Container-side mount paths

The Docker adapter binds the three host-side subdirectories at fixed container paths:

| Host path                                | Container path        | Access |
|------------------------------------------|-----------------------|--------|
| `…/runner-work/{rex_id}/input/`          | `/workspace/input`    | `ro`   |
| `…/runner-work/{rex_id}/output/`         | `/workspace/output`   | `rw`   |
| `…/runner-work/{rex_id}/logs/`           | `/workspace/logs`     | `rw`   |

Runner-image entrypoint authors (stories 3.3 / 3.4) reach for
`/workspace/input/context-bundle.v1.json` and write `/workspace/output/runner-result.v1.json`
plus any referenced artifact-content files. **No other host mounts exist** — no
`docker.sock`, no host secrets, no repo workspace (story 3.9 adds a `/workspace/repo` mount via
the same port).

## Network posture

Every container is launched with `--network=none` (AC8). Runner ↔ backend communication is
strictly file-based: the runner cannot reach `127.0.0.1:8080` or any other backend port. The
posture is set at container *create* time (trap T7); the Docker engine rejects attempts to
patch `NetworkMode` on a running container, and the adapter never tries.

## Label set (operator forensics)

`docker inspect` reports the following labels on every dispatched container, allowing
operators to filter via `docker ps --filter label=deliveryline.runnerExecutionId=rex_xyz`:

| Label                            | Value                                  |
|----------------------------------|----------------------------------------|
| `deliveryline.runnerExecutionId` | `rex_…` public id                      |
| `deliveryline.workflowRunId`     | `run_…` public id                      |
| `deliveryline.runnerKind`        | `codex` \| `claude`                    |
| `deliveryline.dispatchedAt`      | ISO-8601 timestamp from injected Clock |

Story 3.2 adds `deliveryline.stage` and consumes `deliveryline.dispatchedAt` for orphan
recovery.

## Retention policy

This story **declares** `deliveryline.runner.docker.workspace-retention-hours` (default `24`)
and **forbids immediate deletion**: dispatch + poll → Completed + tryReadResult leaves the
workspace intact on disk for diagnostic inspection. Story 3.2 ships
`RunnerWorkspaceCleanupJob` that walks `runner-work/` and prunes workspaces older than the
retention window.

## Filename convention

Story 3.1 OQ-4: the input bundle inside the container is `input/context-bundle.v1.json`. The
`.v1` segment aligns with the scratch leaf filename and the JSON schema `$id`. Runner-image
entrypoint authors should read **exactly** that filename (and write
`output/runner-result.v1.json`).

## ArchUnit guardrails

Two ArchUnit rules in `ArchitectureRuleCatalog` pin story 3.1's boundary invariants:

- `DOCKER_RUNNER_ADAPTER_MUST_NOT_DEPEND_ON_ARTIFACT_APPLICATION` (AC6) — keeps the adapter a
  pure transport; artifact ingestion belongs to the broker via `ArtifactOperationService`.
- `ADAPTERS_RUNNER_DOCKER_TYPES_STAY_BEHIND_GATEWAY` (trap T8) — confines
  `com.github.dockerjava.*` imports to `adapters.runner.docker..` (the `DockerEngineGateway`
  wrapper); upgrades to the docker-java version stay local to that subpackage.

## Local prerequisites

Activating the `runners.docker` profile requires a running Docker daemon. Use
`/doctor` (story 1.16) to probe the local environment, or run
`docker info` directly. Without Docker, stick to the default `runners.mock` profile and the
fast tier — the `DockerClient` Spring bean is profile-gated (trap T10) so the absence of a
daemon never breaks the boot.
