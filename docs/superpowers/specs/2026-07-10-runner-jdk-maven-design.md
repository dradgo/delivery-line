# Runner images: add JDK 21 + Maven 3.9 toolchain

**Date:** 2026-07-10
**Status:** design approved, pending spec review
**Scope:** `runners/codex/Dockerfile`, `runners/claude/Dockerfile`, both entrypoints, both READMEs, `runners/RUNNER_CONTRACT.md`, `DockerRunnerAdapter`, `RunnerProperties`, `application.yml`, conformance ITs.

## Problem

The execution runner images are `node:22-slim` (Debian bookworm) with only `ca-certificates git ripgrep jq` installed — no JDK, no Maven, no database, no servlet container. Verified against the real image:

```
$ docker run --rm --entrypoint sh deliveryline/codex-runner:latest -c 'java -version'
sh: 1: java: not found   (exit 127)
$ ... 'mvn -version' -> mvn: not found (exit 127)
```

On run `run_009f4595…` (ticket FIN-41 "Build And Runtime Verification"), the planning stage authored required tasks `java -version`, `mvn -version`, `mvn -B test/package/dependency:tree/verify`, `mvn jetty:run`. The execution transcript shows `java: command not found` ×1 + `mvn: command not found` ×6 — the seven planned commands. The agent honestly recorded every one as exit 127 / `Blocked`; the reviewer then correctly rejected the artifact with three blocking findings. ~1.2M tokens were spent producing a verification document that verifies nothing.

This spec makes `java` and `mvn` **present and asserted** in both runner images. It is one of three separable pieces (see Non-goals); it does not, by itself, make FIN-41 fully pass.

## Goals

- Java 21 (LTS) and Maven ≥ 3.9.0 on `PATH` in both runner images, for the unprivileged runtime user.
- Version-pinned and deterministic; present in the offline `INSTALL_*_CLI=false` build so CI exercises it.
- `--self-test` asserts the toolchain, so a broken image fails in CI, not at review.
- A shared Maven local-repo cache so repeat runs don't re-download the full dependency tree every time.
- No regression to the runner ↔ backend contract for mock/no-repo dispatches.

## Non-goals (each a separate future spec)

- **Testcontainers / DinD sidecar.** Testcontainers starts arbitrary containers programmatically and needs a Docker daemon reachable from inside the runner. The agreed posture is a **per-run privileged `dockerd` sidecar** on a per-run network (never the host socket) — that is a backend orchestration feature (sidecar lifecycle, per-run network, `DOCKER_HOST` + `TESTCONTAINERS_HOST_OVERRIDE`, cleanup, config, failure modes), not an image change. It depends on this spec (needs Maven to run the ITs).
- **Planner toolchain-awareness.** Nothing stops the planner authoring `mvn jetty:run` (or MySQL/browser smoke tests) against an image that can't run them. That is the true root cause and its own spec. Without it, this spec only moves the goalposts: FIN-41 Tasks 4–5 still require a servlet container + MySQL + browser the runner does not have.

## Approach: multi-stage `COPY --from` pinned official images

Rejected alternatives:
- **apt** — bookworm has **no** `openjdk-21` package and ships `maven 3.8.7`, which fails the target repo's enforcer `requireMavenVersion [21,)` / `>= 3.9.0`. Fails on both counts.
- **Invert base to `eclipse-temurin:21-jdk` + install Node** — inverts the image rationale (the agent CLIs are npm-globals needing the base `node`/`npm` symlinks). Large blast radius, no gain.

Chosen: add two build stages that contribute only file copies, identically to both Dockerfiles.

### Feasibility — verified end-to-end (2026-07-10)

A probe image (`node:22-slim` + the two `COPY --from`, unprivileged uid 1001) was built and run:

```
uid=1001(runner) ...
openjdk version "21.0.11" 2026-04-21 LTS
Apache Maven 3.9.16
# Maven resolved: local (/workspace/.m2)  -> MAVEN_OPTS repo-local override works
```

Source paths confirmed: `eclipse-temurin:21-jdk` → `/opt/java/openjdk` (295 MB, `JAVA_HOME` preset); `maven:3.9-eclipse-temurin-21` → `/usr/share/maven` (11 MB, 3.9.16). Both clear the enforcer.

### Section 1 — Image changes

```dockerfile
ARG JAVA_IMAGE=eclipse-temurin:21-jdk         # 21.0.11 LTS
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21  # 3.9.16
FROM ${JAVA_IMAGE} AS jdk
FROM ${MAVEN_IMAGE} AS mvn

# ... existing single-stage runtime (base node:22-slim, agent CLI, superpowers) ...

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY --from=mvn /usr/share/maven  /usr/share/maven
# jmods (83 MB) is only needed for jlink; drop it in the copy layer unless jlink is foreseen.
RUN rm -rf /opt/java/openjdk/jmods
ENV JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/usr/share/maven \
    MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2 -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav"
ENV PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}"
```

- Pinned via `ARG`, following the existing `CLAUDE_CLI_VERSION` / `OPENSPEC_VERSION` convention.
- Present in **both** the real and `INSTALL_*_CLI=false` builds (no build-time network beyond the pinned base images), so the offline CI conformance gate exercises the toolchain.
- **Size:** measured `+487 MB` per image (mock/CI image `330 MB → ~817 MB`; real image `~821 MB → ~1.3 GB`), before the `jmods` trim (−83 MB).
- The two new `FROM` lines are a deviation from the Dockerfile's "single-stage rationale" comment and bump the AC9 layer budget — update that comment in the same PR rather than leave it contradicting the code.

### Section 2 — Maven cache mount + backend wiring

- New shared host dir `${deliveryline.home}/maven-cache`, bind-mounted **rw** at `/workspace/.m2`. `MAVEN_OPTS` (above) points Maven's local repo there; the concurrency flags enable file-lock sync so simultaneous executions don't race on the same download.
- **Contract change (AC10):** a new mount path touches `RUNNER_CONTRACT.md` (mount table), both Dockerfiles, both entrypoints, both READMEs, and both conformance ITs — same PR.
- **Deliberately outside the secret-scan roots.** `SECRET_SCAN_RUNNER_SUBDIRS = {input, output, logs}` (+`repo` for EXECUTION) resolved under `runner-work/rex_*/`. A sibling `maven-cache/` is never walked — correct, because scanning a multi-hundred-MB dependency repo every run would be a performance sink and a false-positive farm. The workspace cleanup job (deletes `runner-work/rex_*`) also leaves the cache intact.
- **`RunnerProperties.Docker` gains config** (`mavenCacheEnabled`, path) as a **nested record with `defaults()`** — the `RunnerProperties` constructor fans out to ~13 call sites, so a flat new component is a known trap. `application.yml` **and** the test `application.yml` both updated, or validated-config startup fails.
- **Degradation:** if the mount is absent (older backend, mock tier), the entrypoint `mkdir -p /workspace/.m2` leaves it container-local — ephemeral but functional. No hard dependency on the mount.

### Section 3 — Self-test, testing, rollout

**`--self-test` (both entrypoints' `run_self_test()`):** three new `OK`/`FAIL` lines matching the existing openspec/superpowers style —
1. `java -version` resolves and reports major ≥ 21,
2. `mvn -version` resolves and reports ≥ 3.9,
3. `$JAVA_HOME` and `$MAVEN_HOME` directories exist.

A red `--self-test` means "this image can't build Java" — the regression guard this whole change exists to add.

**Testing:**
- **Conformance ITs** (`CodexRunnerImageConformanceIT`, `ClaudeRunnerImageConformanceIT`) build with `INSTALL_*_CLI=false` and assert the three new `--self-test` lines. Toolchain is in the mock build, so these run offline in CI — the real regression lock.
- **Contract fixtures:** `/workspace/.m2` added to the mount table; runner-contracts schema validation re-runs.
- **Live smoke (documented, not CI-gated — needs network):** in the built real image, run `mvn -version` + a trivial `mvn help:evaluate`, proving egress-backed resolution against the shared cache. Manual gate before rollout.

**Rollout:** build both images → conformance ITs → live smoke → rebuild the running backend (also picks up the two uncommitted 3h-4 test-compile fixes still pending) → re-run FIN-41. Its build-half tasks (`java -version`, `mvn test/package/dependency:tree/verify`) should now yield real exit-0 evidence; Tasks 4–5 remain blocked pending the Non-goal specs.

## Success criteria

- `docker run --rm --entrypoint sh <image> -c 'java -version && mvn -version'` reports Java 21 + Maven 3.9 as the unprivileged user, both images.
- `<entrypoint> --self-test` passes and includes the three toolchain assertions; fails if either binary is missing.
- Conformance ITs green offline.
- A second FIN-41 execution records real exit-0 output for the build commands (dependency tree resolved via the shared `/workspace/.m2`), with no `command not found`.
- Mock/no-repo dispatches unchanged; no new secret-scan false positives.
