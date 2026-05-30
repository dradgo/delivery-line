# 3. Runner Secrets MVP Posture — Env-Var Injection Only

Date: 2026-05-30

## Status

Accepted

## Context

Runner containers (Codex/Claude — stories 3.3/3.4) must authenticate to their agent provider with
an API key. Story 3.5 must deliver that key into the container with strict no-leak guarantees
(NFR8/NFR9/NFR14): the key must never be committed, baked into an image, read from `application.yml`
defaults, written into the workspace, serialized into the context bundle, logged at any level, or
shipped to ELK (story 3.7).

The host-side credentials (`GITHUB_TOKEN`, `LINEAR_API_KEY`) are explicitly out of this decision:
they are resolved on the host and MUST NEVER enter a runner container (story 3.14 AC10 / 3.9 AC10).
This ADR is about the **agent-provider key** that legitimately needs to reach the container.

We considered three delivery mechanisms:

1. **Runtime env-var injection** via the docker-java API (`CreateContainerCmd.withEnv`) — the value
   lands in the container's `Config.Env`.
2. **OS keychain integration** (macOS Keychain / Windows Credential Manager / libsecret) — resolve at
   dispatch and inject.
3. **A Docker secrets daemon / swarm secrets** — mount the secret as a tmpfs file.

## Decision

For the Phase-1 MVP we use **runtime env-var injection only** (option 1):

- `RunnerSecretsService` resolves the per-kind provider key by NAME from the Spring `Environment`
  (system env + an optional `./.env` imported via `spring.config.import`) at dispatch time, with no
  caching (rotation = edit `.env` + restart → next dispatch uses the new value).
- The value is passed straight into `CreateContainerSpec.environment` and applied by
  `DefaultDockerEngineGateway` via docker-java `withEnv(...)`. Because we use the docker-java API and
  not the `docker` CLI, the value never appears in any process `argv` / `docker ps` column.
- We do NOT integrate an OS keychain and do NOT run a Docker secrets daemon.

We **explicitly accept** that the provider key is visible to a local operator who can run
`docker inspect <container>` and read `Config.Env`. This is acceptable for the MVP because the
deployment is single-operator, single-host, loopback-only (story 6.9): anyone who can `docker inspect`
already has host-level access equivalent to reading the `.env` file itself. `ContainerState`
deliberately carries no env field, so reading container state back through the gateway never
re-exposes the secret; only a direct `docker inspect` does.

Defense-in-depth that ships alongside this posture:

- A **post-execution workspace secret scan** (AC4) reads every regular file under `input/`/`output/`/
  `logs/` and runs two detectors: `RedactionPolicyService` for the known secret shapes, and a
  mandatory literal-substring check against the value(s) this dispatch injected (the provider keys
  match no redaction pattern). A hit records the execution `runner_secret_leak`, emits a
  `RUNNER_FAILED` event (file path + category names only, never a value), and **quarantines** the
  workspace (a `.quarantine` marker the cleanup job preserves past normal retention).
- Logs carry env-var **counts** and **names of required vars** only — never values.

### Limitations carried forward (not fixed here)

- `JSON_SECRET_FIELD_PATTERN` text-mode does not match a secret held as a non-string JSON value; the
  workspace scan reads each file as text, so a secret in a JSON *string* value is caught, but a
  secret as a numeric value is a known pre-existing gap.
- Binary workspace files (those that fail strict UTF-8 decode) are skipped by the scan with a WARN.

## Consequences

**Positive:** zero new infrastructure; works identically for `mvn`/IDE dev runs and Docker Compose;
rotation is a file edit + restart; the no-leak contract is enforced by tests + ArchUnit boundaries.

**Negative / accepted risk:** the provider key is readable via `docker inspect` by a local operator.

### Upgrade triggers — re-evaluate this decision when ANY of the following becomes true

- The runner stack becomes **multi-user** (more than one human can reach the host/Docker socket).
- DeliveryLine is **hosted** / runs on shared or remote infrastructure rather than a single operator
  laptop/server.
- **Cross-repo or cross-tenant** runners share a host (one tenant's operator could inspect another's
  container env).
- Raw runner **logs, context bundles, or outputs are retained beyond MVP retention** (longer-lived
  artifacts widen the leak surface the AC4 scan guards).

At any of those triggers, revisit OS-keychain or Docker/swarm-secrets delivery (tmpfs-mounted secret
files rather than `Config.Env`) and tighten retention.
