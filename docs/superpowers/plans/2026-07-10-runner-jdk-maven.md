# Runner JDK 21 + Maven 3.9 Toolchain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a version-pinned JDK 21 + Maven 3.9 toolchain (and a shared Maven local-repo cache mount) to both runner images so agent-authored `java`/`mvn` build commands actually run instead of exiting 127.

**Architecture:** Two `COPY --from` builder stages (`eclipse-temurin:21-jdk`, `maven:3.9-eclipse-temurin-21`) drop a self-contained JDK + Maven into each `node:22-slim` runner image; `--self-test` asserts them so a broken image fails in CI, not at review. A shared host dir `{deliveryline.home}/maven-cache` bind-mounts at `/workspace/.m2` (via `MAVEN_OPTS=-Dmaven.repo.local=…`) so repeat runs don't re-download the full dependency tree. The cache wiring uses a `default` SPI method + a `@Value` setter on the adapter — deliberately avoiding the `RunnerProperties.Docker` record, whose constructor fans out to 8 sites.

**Tech Stack:** Docker multi-stage builds, Debian bookworm (`node:22-slim`), Eclipse Temurin 21, Apache Maven 3.9, POSIX `sh` entrypoints, Spring Boot (`@Value`), JUnit5 + Testcontainers docker-runner ITs, docker-java.

## Global Constraints

- **Java floor:** major version **≥ 21** (image ships Temurin `21.0.11`). Verified source path: `eclipse-temurin:21-jdk` → `/opt/java/openjdk` (295 MB, `JAVA_HOME` preset).
- **Maven floor:** **≥ 3.9.0** (image ships `3.9.16`). Verified source path: `maven:3.9-eclipse-temurin-21` → `/usr/share/maven` (11 MB). The target repo's enforcer requires both floors; bookworm's apt `maven` (3.8.7) and absent `openjdk-21` cannot satisfy them — apt is not an option.
- **Version-pinned via `ARG`**, following the existing `CODEX_CLI_VERSION` / `OPENSPEC_VERSION` convention. No floating tags beyond the pinned base images.
- **Present in BOTH builds:** the toolchain must be in the real build AND the `INSTALL_*_CLI=false` mock build, so the offline CI conformance gate exercises it.
- **Tooling-parity rule (RUNNER_CONTRACT.md AC10):** any change to `runners/codex/**` toolchain/mounts must be mirrored in `runners/claude/**` and the contract doc **in the same PR**. Tasks 1–3 land together.
- **Do NOT commit** `docs/superpowers/specs/2026-07-10-runner-jdk-maven-design.md` (the design doc) — the user asked to leave it uncommitted.
- **Spotless:** run `./mvnw -q -pl deliveryline-backend spotless:apply` before committing any hand-edited Java (Task 4).
- **Image size:** +487 MB per image before the `jmods` trim (−83 MB, folded into the existing RUN layer). Update the Dockerfile "single-stage rationale" comment rather than leave it contradicting the new `FROM` lines.
- **RTK note:** this environment's `grep`/`rg` via Bash is unreliable (RTK proxy). Use the Grep tool, not shell grep, when searching.

---

### Task 1: Codex runner image — JDK 21 + Maven 3.9 toolchain + self-test

**Files:**
- Modify: `runners/codex/Dockerfile`
- Modify: `runners/codex/entrypoint.sh:39-51` (var defs) and `:268-334` (`run_self_test`)
- Test: `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java:126-155`

**Interfaces:**
- Produces: an image whose `--self-test` exits 0 and prints summary lines `  java version:   …` and `  maven version:  …`; `java`/`mvn` resolve on `PATH` for uid 1001. Task 2 mirrors this for claude; Task 3 documents it.

- [ ] **Step 1: Extend the conformance IT self-test assertion (failing test)**

In `CodexRunnerImageConformanceIT.java`, inside `selfTestExitsZeroAndPrintsSummary()`, after the existing `superpowers:` assertion block (around line 154), add:

```java
    // JDK+Maven toolchain: the self-test must report both, and a missing binary would already
    // have failed the self-test (exit != 0). Pins the summary lines in CI.
    assertThat(selfTestOutput)
        .as("self-test summary must report the JDK toolchain")
        .contains("java version:");
    assertThat(selfTestOutput)
        .as("self-test summary must report the Maven toolchain")
        .contains("maven version:");
```

- [ ] **Step 2: Run the IT to verify it fails**

Run: `./mvnw -B -pl deliveryline-backend -Ddocker-runner-it verify -Dit.test=CodexRunnerImageConformanceIT#selfTestExitsZeroAndPrintsSummary`
Expected: FAIL — the rebuilt image's `--self-test` output does not contain `java version:` (the entrypoint does not print it yet). Requires a live Docker daemon; if Docker is unavailable the test is skipped (`@EnabledIfDockerAvailable`) — in that case do the manual build+run in Step 6 first and treat its output as the red/green signal.

- [ ] **Step 3: Add the two toolchain builder stages to the Dockerfile**

In `runners/codex/Dockerfile`, replace the header lines:

```dockerfile
ARG BASE_IMAGE=node:22-slim
FROM ${BASE_IMAGE}
```

with:

```dockerfile
ARG BASE_IMAGE=node:22-slim
# --- toolchain source stages (story: runner JDK+Maven) ------------------------
# Self-contained Temurin JDK 21 + Apache Maven 3.9 copied into the runtime image
# below. apt is NOT usable: bookworm has no openjdk-21 package and ships maven
# 3.8.7, which fails the target repo's enforcer (Java >= 21, Maven >= 3.9.0).
# Pinned via ARG; present in BOTH the real and INSTALL_CODEX_CLI=false builds so
# the offline conformance gate exercises the toolchain.
ARG JAVA_IMAGE=eclipse-temurin:21-jdk
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
FROM ${JAVA_IMAGE} AS jdk
FROM ${MAVEN_IMAGE} AS mvn

FROM ${BASE_IMAGE}
```

- [ ] **Step 4: Copy the toolchain in + set env; trim jmods in the existing RUN**

In `runners/codex/Dockerfile`, immediately AFTER the superpowers COPY line (`COPY --chown=1001:1001 runners/vendor/superpowers /opt/deliveryline/vendor/superpowers`) and BEFORE the big `RUN set -eu; \` block, insert:

```dockerfile
# --- JDK 21 + Maven 3.9 toolchain (story: runner JDK+Maven) -------------------
# Temurin's /opt/java/openjdk is a self-contained glibc build; it runs on
# node:22-slim (bookworm) unchanged. jmods (83 MB, jlink-only) is removed in the
# RUN below to stay near the AC9 layer budget. MAVEN_OPTS points the local repo
# at the /workspace/.m2 cache mount (Task 4) and enables file-lock sync so
# concurrent runs do not race on the same artifact download.
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
COPY --from=mvn /usr/share/maven  /usr/share/maven
ENV JAVA_HOME=/opt/java/openjdk \
    MAVEN_HOME=/usr/share/maven \
    MAVEN_OPTS="-Dmaven.repo.local=/workspace/.m2 -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav"
ENV PATH="${JAVA_HOME}/bin:${MAVEN_HOME}/bin:${PATH}"
```

Then inside the existing `RUN set -eu; \` block, on the line immediately before `rm -rf /var/lib/apt/lists/*`, add:

```dockerfile
    rm -rf /opt/java/openjdk/jmods; \
```

Finally, update the "Single-stage rationale" comment block near the top so it acknowledges the two new `COPY --from` toolchain stages (the runtime stage is still single-stage for the agent CLI; only file copies are added).

- [ ] **Step 5: Add the self-test assertions to the entrypoint**

In `runners/codex/entrypoint.sh`, after the `EXPECTED_SUPERPOWERS_PIN=...` line (~line 51), add:

```sh
# story: runner JDK+Maven — build toolchain the agent's plans rely on. --self-test
# asserts both are present at/above the expected floor so a broken image fails in
# CI, not after a full (token-expensive) execution.
JAVA_BIN="${JAVA_BIN:-java}"
MVN_BIN="${MVN_BIN:-mvn}"
EXPECTED_JAVA_MAJOR="${EXPECTED_JAVA_MAJOR:-21}"
EXPECTED_MAVEN_SERIES="${EXPECTED_MAVEN_SERIES:-3.9}"
```

In `run_self_test()`, after the superpowers skills-count block and BEFORE the final `echo "deliveryline/codex-runner self-test: OK"`, add:

```sh
  if ! command -v "$JAVA_BIN" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: java '$JAVA_BIN' not found on PATH"
    exit 1
  fi
  _java_version="$("$JAVA_BIN" -version 2>&1 | head -1)"
  case "$_java_version" in
    *"\"$EXPECTED_JAVA_MAJOR."*|*"\"$EXPECTED_JAVA_MAJOR\""*) ;;
    *)
      echo "SELF-TEST FAIL: java version '${_java_version:-<unknown>}' is not major $EXPECTED_JAVA_MAJOR"
      exit 1
      ;;
  esac
  if ! command -v "$MVN_BIN" >/dev/null 2>&1; then
    echo "SELF-TEST FAIL: maven '$MVN_BIN' not found on PATH"
    exit 1
  fi
  _maven_version="$("$MVN_BIN" -version 2>&1 | head -1)"
  case "$_maven_version" in
    *"Apache Maven $EXPECTED_MAVEN_SERIES"*) ;;
    *)
      echo "SELF-TEST FAIL: maven version '${_maven_version:-<unknown>}' is not series $EXPECTED_MAVEN_SERIES"
      exit 1
      ;;
  esac
```

Then in the summary echo block (after the `superpowers:` echo line), add:

```sh
  echo "  java version:   ${_java_version:-<unknown>} (JAVA_HOME=$JAVA_HOME)"
  echo "  maven version:  ${_maven_version:-<unknown>} (MAVEN_HOME=$MAVEN_HOME)"
```

- [ ] **Step 6: Manually build + self-test the mock image (fast local red→green)**

Run:
```bash
docker build -f runners/codex/Dockerfile --build-arg INSTALL_CODEX_CLI=false -t deliveryline/codex-runner:jdk-check .
docker run --rm deliveryline/codex-runner:jdk-check --self-test
```
Expected: exit 0; output includes `java version:   openjdk version "21.0.…` and `maven version:  Apache Maven 3.9.…`. Also spot-check the unprivileged user:
```bash
docker run --rm --entrypoint sh deliveryline/codex-runner:jdk-check -c 'id; java -version; mvn -version | head -1'
```
Expected: `uid=1001(codex)`, Java 21, Maven 3.9.x.

- [ ] **Step 7: Run the conformance IT to verify green**

Run: `./mvnw -B -pl deliveryline-backend -Ddocker-runner-it verify -Dit.test=CodexRunnerImageConformanceIT`
Expected: PASS (all methods, including the extended self-test assertion). If Docker is unavailable locally, record that Step 6 passed and note the IT must be run in the Docker CI tier.

- [ ] **Step 8: Commit**

```bash
git add runners/codex/Dockerfile runners/codex/entrypoint.sh \
  deliveryline-backend/src/test/java/org/dradgo/adapters/runner/CodexRunnerImageConformanceIT.java
git commit -m "feat(runner): add JDK 21 + Maven 3.9 toolchain to codex image + self-test"
```

---

### Task 2: Claude runner image — mirror the toolchain (tooling-parity)

**Files:**
- Modify: `runners/claude/Dockerfile`
- Modify: `runners/claude/entrypoint.sh` (var defs + `run_self_test`)
- Test: `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/ClaudeRunnerImageConformanceIT.java`

**Interfaces:**
- Consumes: the exact Dockerfile/entrypoint edits from Task 1, applied with claude-specific names (`INSTALL_CLAUDE_CLI`, user `claude`, `deliveryline/claude-runner`).
- Produces: claude image with identical toolchain + self-test behaviour. Required by the AC10 parity rule to ship in the same PR as Task 1.

- [ ] **Step 1: Extend the claude conformance IT self-test assertion (failing test)**

In `ClaudeRunnerImageConformanceIT.java`, find the `--self-test` summary assertion method (the claude twin of `selfTestExitsZeroAndPrintsSummary`; it asserts `openspec bin:` / `superpowers:`). After that block add the identical two assertions:

```java
    assertThat(selfTestOutput)
        .as("self-test summary must report the JDK toolchain")
        .contains("java version:");
    assertThat(selfTestOutput)
        .as("self-test summary must report the Maven toolchain")
        .contains("maven version:");
```

(If the variable holding the captured self-test output is named differently in this file, use that name — confirm by reading the method first.)

- [ ] **Step 2: Run the IT to verify it fails**

Run: `./mvnw -B -pl deliveryline-backend -Ddocker-runner-it verify -Dit.test=ClaudeRunnerImageConformanceIT#selfTestExitsZeroAndPrintsSummary`
Expected: FAIL — claude image self-test does not yet print `java version:`. (Same Docker-availability caveat as Task 1 Step 2; use Step 6 as the fallback signal.)

- [ ] **Step 3: Apply the Dockerfile stages to `runners/claude/Dockerfile`**

Apply the SAME edits as Task 1 Steps 3–4, verbatim, to `runners/claude/Dockerfile`:
- Insert the `ARG JAVA_IMAGE` / `ARG MAVEN_IMAGE` + `FROM … AS jdk` / `FROM … AS mvn` stages between `ARG BASE_IMAGE=node:22-slim` and `FROM ${BASE_IMAGE}`.
- Insert the two `COPY --from=` lines + the `ENV JAVA_HOME/MAVEN_HOME/MAVEN_OPTS` + `ENV PATH` block after the superpowers COPY and before the big `RUN set -eu; \` block.
- Add `rm -rf /opt/java/openjdk/jmods; \` before `rm -rf /var/lib/apt/lists/*` in that RUN.
- Update the "Single-stage rationale" comment.

The claude Dockerfile is structurally identical to codex (verified), so the insertion points match.

- [ ] **Step 4: Apply the entrypoint edits to `runners/claude/entrypoint.sh`**

Apply the SAME edits as Task 1 Step 5 to `runners/claude/entrypoint.sh`: the four var defaults (`JAVA_BIN`, `MVN_BIN`, `EXPECTED_JAVA_MAJOR`, `EXPECTED_MAVEN_SERIES`) after the superpowers pin var, the two `command -v` + version `case` guards in `run_self_test()` before the final OK echo, and the two summary echo lines. The claude entrypoint's summary echoes `deliveryline/claude-runner self-test: OK` — keep that line as-is; only add the java/maven echoes above it.

- [ ] **Step 5: Manually build + self-test the claude mock image**

Run:
```bash
docker build -f runners/claude/Dockerfile --build-arg INSTALL_CLAUDE_CLI=false -t deliveryline/claude-runner:jdk-check .
docker run --rm deliveryline/claude-runner:jdk-check --self-test
```
Expected: exit 0; output includes the `java version:` + `maven version:` lines.

- [ ] **Step 6: Run the claude conformance IT to verify green**

Run: `./mvnw -B -pl deliveryline-backend -Ddocker-runner-it verify -Dit.test=ClaudeRunnerImageConformanceIT`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add runners/claude/Dockerfile runners/claude/entrypoint.sh \
  deliveryline-backend/src/test/java/org/dradgo/adapters/runner/ClaudeRunnerImageConformanceIT.java
git commit -m "feat(runner): mirror JDK 21 + Maven 3.9 toolchain into claude image + self-test"
```

---

### Task 3: Runner contract + READMEs — document the toolchain and cache mount

**Files:**
- Modify: `runners/RUNNER_CONTRACT.md:31-37` (mount table + surrounding prose)
- Modify: `runners/codex/README.md`, `runners/claude/README.md` (toolchain + pin/upgrade note)

**Interfaces:**
- Consumes: the mount added in Task 4 (`/workspace/.m2`) and the toolchain from Tasks 1–2. This task documents both; it is the AC10 "contract doc updated first/same PR" obligation.

- [ ] **Step 1: Add the cache mount to the contract mount table**

In `runners/RUNNER_CONTRACT.md`, the mount table (lines 31–35) currently lists `/workspace/input`, `/workspace/output`, `/workspace/logs` (and `/workspace/repo` elsewhere). Add a row:

```markdown
| `/workspace/.m2`    | read-write | Shared Maven local repository (host `{deliveryline.home}/maven-cache`), mounted only when `deliveryline.runner.maven-cache-enabled` is true. Optional: absent → Maven falls back to a container-local (ephemeral) repo. |
```

- [ ] **Step 2: Add a toolchain section to the contract**

In `runners/RUNNER_CONTRACT.md`, near the existing "Agent-side tooling" section, add a short subsection stating: both images carry a pinned **JDK 21** (Temurin, `ARG JAVA_IMAGE`) and **Maven 3.9** (`ARG MAVEN_IMAGE`), present in production AND the offline `INSTALL_*_CLI=false` build; `--self-test` asserts both at/above the floor (Java ≥ 21, Maven ≥ 3.9.0). Note the change rule: bumping either pin edits BOTH Dockerfiles + the READMEs in the same PR.

- [ ] **Step 3: Add pin/upgrade notes to both READMEs**

In `runners/codex/README.md` and `runners/claude/README.md`, add a "JDK + Maven toolchain" subsection: the pinned images, how to bump (`docker pull eclipse-temurin:21-jdk` / `maven:3.9-eclipse-temurin-21`, confirm versions, update `ARG`), and that the toolchain exists so agent plans can run real `mvn` builds. Note the `/workspace/.m2` shared cache and that a cold run (no cache mount) re-downloads the dependency tree.

- [ ] **Step 4: Commit**

```bash
git add runners/RUNNER_CONTRACT.md runners/codex/README.md runners/claude/README.md
git commit -m "docs(runner): document JDK+Maven toolchain and /workspace/.m2 cache mount"
```

---

### Task 4: Shared Maven cache mount — SPI + store + adapter wiring

**Files:**
- Modify: `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java` (add `default` method)
- Modify: `deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java` (override + constant)
- Modify: `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java:68-70` (constant), `:341-344` (mount), + `@Value` setter
- Modify: `deliveryline-backend/src/main/resources/application.yml` (+ test `application.yml` if present)
- Test: `deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStoreTest.java`, `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java`

**Interfaces:**
- Consumes: `WorkspaceLayout` (unchanged), `CreateContainerSpec.BindMount(Path hostPath, String containerPath, boolean readOnly)`.
- Produces: `RunnerWorkspaceStore.prepareMavenCache() : Optional<Path>` (default `Optional.empty()`; `LocalRunnerWorkspaceStore` returns the created `{deliveryline.home}/maven-cache`). Adapter adds a rw bind `/workspace/.m2` when `deliveryline.runner.maven-cache-enabled` (default true) AND the store returns a path.

> **Design note (why not `RunnerProperties.Docker`):** the spec sketched a nested config record on `RunnerProperties.Docker`. That record's constructor fans out to 8 construction sites (`RunnerProperties`, plus 7 test files) — the [[runnerproperties-record-component-fanout]] trap. A `default` SPI method + a `@Value` setter on the adapter delivers the same configurability and graceful degradation with zero fan-out to those sites and zero fan-out to the mock/test `RunnerWorkspaceStore` implementers. This is the chosen realization.

- [ ] **Step 1: Add the store unit test (failing test)**

In `LocalRunnerWorkspaceStoreTest.java`, add:

```java
  @Test
  void prepareMavenCacheCreatesSharedDirUnderHome() throws Exception {
    Path home = Files.createTempDirectory("dl-home-");
    LocalRunnerWorkspaceStore store =
        new LocalRunnerWorkspaceStore(home.toString(), Path.of("runner-work"));

    Optional<Path> cache = store.prepareMavenCache();

    assertThat(cache).isPresent();
    assertThat(Files.isDirectory(cache.get())).isTrue();
    assertThat(cache.get().getFileName().toString()).isEqualTo("maven-cache");
    assertThat(cache.get().startsWith(home.toRealPath())).isTrue();
  }
```

Add any missing imports (`java.nio.file.Files`, `java.nio.file.Path`, `java.util.Optional`, `org.assertj.core.api.Assertions.assertThat`).

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -B -pl deliveryline-backend test -Dtest=LocalRunnerWorkspaceStoreTest#prepareMavenCacheCreatesSharedDirUnderHome`
Expected: FAIL to COMPILE — `prepareMavenCache()` does not exist yet.

- [ ] **Step 3: Add the `default` SPI method**

In `RunnerWorkspaceStore.java`, add (with `java.nio.file.Path` and `java.util.Optional` already imported):

```java
  /**
   * Resolve (creating if needed) the shared Maven local-repository cache directory at {@code
   * {deliveryline.home}/maven-cache}, a SIBLING of {@code runner-work/} so it survives per-run
   * workspace cleanup and is never walked by {@link #readFilesForSecretScan(String, boolean)}
   * (which only descends {@code rex_*/}). The Docker adapter bind-mounts the returned path at
   * {@code /workspace/.m2} (read-write) so repeat runs reuse downloaded artifacts. Default: {@link
   * Optional#empty()} — a store that has no notion of a shared cache (mocks, non-local
   * implementations) degrades to a per-container ephemeral repo, and the adapter simply adds no
   * mount.
   */
  default Optional<Path> prepareMavenCache() {
    return Optional.empty();
  }
```

- [ ] **Step 4: Override it in `LocalRunnerWorkspaceStore`**

In `LocalRunnerWorkspaceStore.java`, add the constant near the other `*_DIRNAME`/subdir constants:

```java
  private static final String MAVEN_CACHE_DIRNAME = "maven-cache";
```

And add the override (near `prepareRepositoryDir`), reusing the existing `RUNNER_WRITABLE_DIR_PERMS` + `createWithPerms` helpers:

```java
  @Override
  public Optional<Path> prepareMavenCache() {
    Path cache = deliverylineHome.resolve(MAVEN_CACHE_DIRNAME).normalize();
    // Containment: the cache must stay under deliveryline.home (mirrors the workspace guards).
    if (!cache.startsWith(deliverylineHome)) {
      throw new IllegalStateException("maven cache path escapes deliveryline.home");
    }
    try {
      createWithPerms(cache, RUNNER_WRITABLE_DIR_PERMS);
      Path real = cache.toRealPath();
      log.info("maven cache dir prepared path={}", real);
      return Optional.of(real);
    } catch (IOException error) {
      // Degrade to a per-container ephemeral repo rather than failing the dispatch.
      log.warn("maven cache dir prepare failed reason=io_error — using per-container cache");
      return Optional.empty();
    }
  }
```

- [ ] **Step 5: Run the store test to verify it passes**

Run: `./mvnw -B -pl deliveryline-backend test -Dtest=LocalRunnerWorkspaceStoreTest#prepareMavenCacheCreatesSharedDirUnderHome`
Expected: PASS.

- [ ] **Step 6: Add the adapter unit test for the mount (failing test)**

In `DockerRunnerAdapterUnitTest.java`, add a test mirroring the existing dispatch test that captures `CreateContainerSpec` (see the `specCaptor` pattern around line 118). It stubs the cache path, enables the flag via the setter, and asserts a 4th rw bind at `/workspace/.m2`:

```java
  @Test
  void dispatchAddsMavenCacheMountWhenEnabled() {
    java.nio.file.Path cacheDir = java.nio.file.Path.of("/srv/dl/maven-cache");
    when(workspaceStore.prepareMavenCache()).thenReturn(java.util.Optional.of(cacheDir));
    when(gateway.createContainer(any())).thenReturn(CONTAINER_ID);
    adapter.setMavenCacheEnabled(true);

    // (reuse whatever dispatch invocation the sibling test uses, e.g. adapter.dispatch(request);)
    adapter.dispatch(REQUEST);

    ArgumentCaptor<CreateContainerSpec> specCaptor =
        ArgumentCaptor.forClass(CreateContainerSpec.class);
    verify(gateway).createContainer(specCaptor.capture());
    CreateContainerSpec spec = specCaptor.getValue();
    assertThat(spec.binds()).hasSize(4);
    CreateContainerSpec.BindMount m2 =
        spec.binds().stream()
            .filter(b -> b.containerPath().equals("/workspace/.m2"))
            .findFirst()
            .orElseThrow();
    assertThat(m2.readOnly()).isFalse();
    assertThat(m2.hostPath()).isEqualTo(cacheDir);
  }
```

Match `REQUEST` / `adapter.dispatch(...)` to the exact names the sibling dispatch test in this file uses (read the first dispatch test to confirm the request constant + method name before writing).

- [ ] **Step 7: Run it to verify it fails**

Run: `./mvnw -B -pl deliveryline-backend test -Dtest=DockerRunnerAdapterUnitTest#dispatchAddsMavenCacheMountWhenEnabled`
Expected: FAIL to COMPILE — `setMavenCacheEnabled` does not exist yet.

- [ ] **Step 8: Add the mount constant, `@Value` setter, and bind to the adapter**

In `DockerRunnerAdapter.java`, add the constant next to the other mount constants (lines 68–70):

```java
  private static final String CONTAINER_MAVEN_CACHE_MOUNT = "/workspace/.m2";
```

Add a field + `@Value` setter (field injection avoids the [[docker-adapter-ctor-dep-fans-out]] ctor fan-out; the setter lets the unit test toggle it without reflection):

```java
  // Story: runner JDK+Maven — mount the shared Maven local repo so repeat build runs reuse
  // downloaded artifacts. Default ON in production; the unit test toggles via the setter, and a
  // bare `new DockerRunnerAdapter(...)` leaves it false (existing 3-mount tests unaffected).
  private boolean mavenCacheEnabled;

  @org.springframework.beans.factory.annotation.Value(
      "${deliveryline.runner.maven-cache-enabled:true}")
  void setMavenCacheEnabled(boolean mavenCacheEnabled) {
    this.mavenCacheEnabled = mavenCacheEnabled;
  }
```

Then, immediately AFTER the three existing `mounts.add(...)` lines (input/output/logs, ~line 344) and BEFORE the repositoryRef block, add:

```java
    // Shared Maven local-repo cache (rw) at /workspace/.m2 — only when enabled AND the store
    // provides a path. Absent → Maven uses a container-local ephemeral repo (cold download).
    if (mavenCacheEnabled) {
      workspaceStore
          .prepareMavenCache()
          .ifPresent(
              cache ->
                  mounts.add(
                      new CreateContainerSpec.BindMount(
                          cache, CONTAINER_MAVEN_CACHE_MOUNT, false)));
    }
```

- [ ] **Step 9: Run the adapter test + the existing dispatch tests**

Run: `./mvnw -B -pl deliveryline-backend test -Dtest=DockerRunnerAdapterUnitTest`
Expected: PASS — the new test (4 mounts) and the existing tests (which never call the setter, so `mavenCacheEnabled` is false → still 3 mounts) both green.

- [ ] **Step 10: Add the config key to application.yml**

In `deliveryline-backend/src/main/resources/application.yml`, under `deliveryline.runner:` (the parent of the `docker:` block), add:

```yaml
    # Story: runner JDK+Maven — mount {deliveryline.home}/maven-cache at /workspace/.m2 (rw) so
    # repeat Maven builds reuse the downloaded dependency tree. false → cold download each run.
    maven-cache-enabled: true
```

If a test `application.yml` exists under `deliveryline-backend/src/test/resources` that mirrors runner config, add the same key (a `@Value` default of `:true` means it is not strictly required, but mirror it if the file already pins runner keys). Read the test resources dir to confirm before editing.

- [ ] **Step 11: Spotless + full runner-adapter test slice**

Run:
```bash
./mvnw -q -pl deliveryline-backend spotless:apply
./mvnw -B -pl deliveryline-backend test -Dtest=DockerRunnerAdapterUnitTest,LocalRunnerWorkspaceStoreTest
```
Expected: BUILD SUCCESS, both classes green.

- [ ] **Step 12: Commit**

```bash
git add deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/RunnerWorkspaceStore.java \
  deliveryline-backend/src/main/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStore.java \
  deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java \
  deliveryline-backend/src/main/resources/application.yml \
  deliveryline-backend/src/test/java/org/dradgo/adapters/files/LocalRunnerWorkspaceStoreTest.java \
  deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java
git commit -m "feat(runner): mount shared Maven cache at /workspace/.m2"
```

---

### Task 5: Live smoke verification + rollout

**Files:**
- Modify: `runners/codex/README.md` (append a "Live build smoke" runbook subsection)

**Interfaces:**
- Consumes: the real images from Tasks 1–2 and the mount from Task 4. This task is the documented manual gate before rollout; it needs network egress (real Maven resolution) so it is NOT a CI gate.

- [ ] **Step 1: Document the live smoke runbook**

In `runners/codex/README.md`, add a "Live build smoke (manual, needs egress)" subsection with:

```bash
# Build the REAL image (installs the agent CLI; needs network).
docker build -f runners/codex/Dockerfile -t deliveryline/codex-runner:latest .

# Prove Maven resolves against the shared cache with real egress.
mkdir -p /tmp/dl-m2
docker run --rm -v /tmp/dl-m2:/workspace/.m2 --entrypoint sh \
  deliveryline/codex-runner:latest -c \
  'mvn -version && mvn -q -Dplugin=help help:describe -Dfull=false || true; ls /workspace/.m2 | head'
```
Expected: Maven 3.9.x banner; `/workspace/.m2` populated with downloaded plugin artifacts on the first run and reused on the second.

- [ ] **Step 2: Run the live smoke**

Run the commands from Step 1. Expected: Java 21 + Maven 3.9.x resolve; the cache dir fills. If egress is unavailable in this environment, record that and defer the smoke to a networked host — do NOT block the commit on it.

- [ ] **Step 3: Rollout checklist (record outcomes, do not automate)**

- [ ] Build both real images (`docker compose --profile runners build`).
- [ ] Run both conformance ITs in the Docker CI tier (`-Ddocker-runner-it`).
- [ ] Rebuild the running backend so the new mount config + the pending uncommitted 3h-4 test-compile fixes (`RunnerPropertiesTest:37`, `DeliveryApprovalServiceTest:84`) are picked up — note: those two must compile before the backend module builds.
- [ ] Re-run FIN-41 (`run_009f4595…` lineage): the build-half tasks (`java -version`, `mvn -B test/package/dependency:tree/verify`) must now produce real exit-0 evidence with the dependency tree resolved via `/workspace/.m2`. Tasks 4–5 (MySQL, servlet container, browser) remain blocked pending the separate planner-awareness / DinD specs.

- [ ] **Step 4: Commit**

```bash
git add runners/codex/README.md
git commit -m "docs(runner): live Maven build smoke runbook + rollout checklist"
```

---

## Self-Review

**Spec coverage:**
- Image changes (JDK+Maven stages, pins, both builds, jmods trim, single-stage comment) → Task 1 (codex) + Task 2 (claude). ✓
- Maven cache mount + backend wiring + secret-scan exclusion + degradation → Task 4 (+ contract row in Task 3). ✓
- `--self-test` assertions → Task 1 Step 5 / Task 2 Step 4. ✓
- Conformance ITs (offline, mock build) → Task 1 Step 7 / Task 2 Step 6. ✓
- Contract mount table + READMEs → Task 3. ✓
- Live smoke + rollout → Task 5. ✓
- Non-goals (DinD, planner-awareness) → stated in header + Task 5 Step 3, no tasks. ✓
- Concurrent-repo file-lock flags → in `MAVEN_OPTS` (Task 1 Step 4). ✓

**Deviation from spec (intentional, documented in Task 4 note):** cache config is a `default` SPI method + `@Value` setter, NOT a `RunnerProperties.Docker` nested record — avoids the 8-site constructor fan-out. Same behaviour (configurable, default-on, degrades gracefully).

**Placeholder scan:** two spots require reading the target file first (Task 2 Step 1 self-test var name; Task 4 Step 6 `REQUEST`/`dispatch` names) — these are explicit "confirm by reading" instructions, not blanks; the surrounding code is fully specified. No `TBD`/`add error handling`/`write tests for the above`.

**Type consistency:** `prepareMavenCache() : Optional<Path>` is identical in the SPI default (Step 3), the override (Step 4), and the adapter call (Step 8). `BindMount(Path, String, boolean readOnly)` matches the record. `setMavenCacheEnabled(boolean)` defined in Step 8, called in Step 6. Container path `/workspace/.m2` consistent across Dockerfile `MAVEN_OPTS`, adapter constant, contract row, tests.
