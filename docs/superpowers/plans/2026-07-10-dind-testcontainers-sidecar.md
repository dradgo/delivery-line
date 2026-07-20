# Per-run DinD Testcontainers Sidecar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give an opted-in project's execution-stage runs a private, throwaway `dockerd` sidecar (privileged, on a per-run network) that Testcontainers can drive, so agent-authored `mvn verify` integration tests run — without ever exposing the host Docker socket.

**Architecture:** When an execution-stage run's project has `testcontainers-enabled = true`, `DockerRunnerAdapter` provisions a per-run user-defined bridge network + a privileged `docker:<pin>-dind` sidecar (aliased `dind`) via a new `DindSidecarService`, attaches the runner to that network, injects `DOCKER_HOST`/`TESTCONTAINERS_*` env, and tears both down. Flag off ⇒ byte-identical to today. A provision failure fails the run fast with `TESTCONTAINERS_INFRA_FAILED`.

**Tech Stack:** docker-java, Debian/Docker `docker:dind`, Spring Boot (`@ConfigurationProperties`, `@Value`, Flyway), JUnit5 + Mockito, Testcontainers `docker-runner-it` tier.

## Global Constraints

- **Gating:** per-project flag `testcontainers-enabled`, default **false**. Flag off ⇒ **byte-identical** to current behavior (no sidecar, no network, runner `networkMode` unchanged).
- **Stage:** execution-stage runs only (`RunnerStage.EXECUTION`).
- **Failure:** provision failure ⇒ record run **FAILED** with `FailureCategory.TESTCONTAINERS_INFRA_FAILED` (value `testcontainers_infra_failed`), emit `RUNNER_FAILED`, do **not** dispatch the agent. Retryable.
- **Network:** one per-run user-defined bridge `deliveryline-net-<rex>`; sidecar aliased `dind`; sidecar labeled `deliveryline.dind=<rex>`. Never mount the host Docker socket into any container.
- **Sidecar:** image `docker:<pin>-dind` (config, e.g. `docker:27-dind`), **privileged**, `DOCKER_TLS_CERTDIR=""` (plaintext daemon on 2375), anonymous volume at `/var/lib/docker`, configurable memory cap (default 2 GiB), Docker healthcheck `CMD-SHELL docker -H tcp://localhost:2375 version`. Readiness = poll `inspectContainer(...).healthStatus == "healthy"` up to `readiness-timeout` (default 60s).
- **Runner env when enabled:** `DOCKER_HOST=tcp://dind:2375`, `TESTCONTAINERS_HOST_OVERRIDE=dind`, `TESTCONTAINERS_RYUK_DISABLED=true`.
- **No commits.** The user's standing instruction is NO git commits (design doc, plan, and all code stay in the working tree). Skip every commit step; leave changes uncommitted. Reviews run on working-tree diffs.
- **Docker-java confinement (ArchUnit trap T8):** `com.github.dockerjava.*` types stay behind `DefaultDockerEngineGateway`. `application.*` must never import `adapters.*` (the `application-cannot-import-adapters` rule).
- **Architecture correction vs spec:** the spec said `DindSidecarService` lives in `application.runner`; it must build `CreateContainerSpec` (an `adapters.runner.docker` type) and call `DockerEngineGateway`, which `application.*` cannot import. It therefore lives in **`adapters.runner.docker`**. The application-side sweep reaches dind resources via the existing `DockerHostPort` application port (extended), never the gateway.
- **Spotless:** run `./mvnw -q -pl deliveryline-backend spotless:apply` before finishing any Java task.
- **RTK note:** `git`/`grep` via the Bash tool are corrupted by the RTK proxy in this environment — use the PowerShell tool for `git`, and the Grep tool (not shell grep) for searches.

---

### Task 1: Extend `CreateContainerSpec` + gateway with privileged / aliases / memory / healthcheck / networks

**Files:**
- Modify: `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/CreateContainerSpec.java`
- Modify: `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/ContainerState.java`
- Modify: `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DockerEngineGateway.java`
- Modify: `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java`
- Test: `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/docker/CreateContainerSpecTest.java` (create), `ContainerStateTest.java` (create if absent)

**Interfaces:**
- Produces:
  - `CreateContainerSpec` canonical component list becomes `(String image, List<BindMount> binds, String networkMode, Map<String,String> labels, Map<String,String> environment, List<String> securityOpts, boolean privileged, List<String> networkAliases, Long memoryBytes, Healthcheck healthcheck)` where `memoryBytes`/`healthcheck` are nullable. Nested `record Healthcheck(List<String> test, Duration interval, Duration timeout, int retries)`.
  - `ContainerState` gains a trailing nullable `String healthStatus` (values `"healthy"`/`"starting"`/`"unhealthy"`/`null`), with a back-compat constructor omitting it.
  - `DockerEngineGateway` gains `String createNetwork(String name, Map<String,String> labels)` and `void removeNetwork(String name)`.

- [ ] **Step 1: Write the failing spec test**

Create `CreateContainerSpecTest.java`:
```java
package org.dradgo.adapters.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CreateContainerSpecTest {

  @Test
  void defaultsForNewFieldsKeepLegacyConstructorsUnprivilegedWithNoAliases() {
    CreateContainerSpec legacy =
        new CreateContainerSpec("img", List.of(), "bridge", Map.of());
    assertThat(legacy.privileged()).isFalse();
    assertThat(legacy.networkAliases()).isEmpty();
    assertThat(legacy.memoryBytes()).isNull();
    assertThat(legacy.healthcheck()).isNull();
  }

  @Test
  void canonicalConstructorCarriesPrivilegedAliasesMemoryAndHealthcheck() {
    CreateContainerSpec.Healthcheck hc =
        new CreateContainerSpec.Healthcheck(
            List.of("CMD-SHELL", "docker -H tcp://localhost:2375 version"),
            Duration.ofSeconds(2),
            Duration.ofSeconds(3),
            30);
    CreateContainerSpec spec =
        new CreateContainerSpec(
            "docker:27-dind",
            List.of(),
            "deliveryline-net-rex_x",
            Map.of("deliveryline.dind", "rex_x"),
            Map.of("DOCKER_TLS_CERTDIR", ""),
            List.of(),
            true,
            List.of("dind"),
            2L * 1024 * 1024 * 1024,
            hc);
    assertThat(spec.privileged()).isTrue();
    assertThat(spec.networkAliases()).containsExactly("dind");
    assertThat(spec.memoryBytes()).isEqualTo(2147483648L);
    assertThat(spec.healthcheck().test()).contains("CMD-SHELL");
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q -pl deliveryline-backend test -Dtest=CreateContainerSpecTest`
Expected: FAIL to COMPILE — `privileged()`, `networkAliases()`, etc. do not exist.

- [ ] **Step 3: Add the new fields + Healthcheck to `CreateContainerSpec`**

Replace the canonical record header and compact constructor, and add back-compat delegating constructors so all existing call sites (`new CreateContainerSpec(image, binds, networkMode, labels)`, the 5-arg env form, and the 6-arg securityOpts form used by `DockerRunnerAdapter`) keep compiling:
```java
public record CreateContainerSpec(
    String image,
    List<BindMount> binds,
    String networkMode,
    Map<String, String> labels,
    Map<String, String> environment,
    List<String> securityOpts,
    boolean privileged,
    List<String> networkAliases,
    Long memoryBytes,
    Healthcheck healthcheck) {

  public CreateContainerSpec {
    if (image == null || image.isBlank()) {
      throw new IllegalArgumentException("image must not be blank");
    }
    binds = binds == null ? List.of() : List.copyOf(binds);
    Objects.requireNonNull(networkMode, "networkMode");
    labels = labels == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(labels));
    environment = environment == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(environment));
    securityOpts = securityOpts == null ? List.of() : List.copyOf(securityOpts);
    networkAliases = networkAliases == null ? List.of() : List.copyOf(networkAliases);
  }

  // Back-compat: label/lifecycle call sites (no env, no security, unprivileged).
  public CreateContainerSpec(
      String image, List<BindMount> binds, String networkMode, Map<String, String> labels) {
    this(image, binds, networkMode, labels, Map.of(), List.of(), false, List.of(), null, null);
  }

  // Back-compat: story-3.5 env call sites (no security options).
  public CreateContainerSpec(
      String image,
      List<BindMount> binds,
      String networkMode,
      Map<String, String> labels,
      Map<String, String> environment) {
    this(image, binds, networkMode, labels, environment, List.of(), false, List.of(), null, null);
  }

  // Back-compat: the DockerRunnerAdapter dispatch call site (env + securityOpts, unprivileged).
  public CreateContainerSpec(
      String image,
      List<BindMount> binds,
      String networkMode,
      Map<String, String> labels,
      Map<String, String> environment,
      List<String> securityOpts) {
    this(image, binds, networkMode, labels, environment, securityOpts, false, List.of(), null, null);
  }

  public record BindMount(Path hostPath, String containerPath, boolean readOnly) {
    public BindMount {
      Objects.requireNonNull(hostPath, "hostPath");
      if (containerPath == null || containerPath.isBlank()) {
        throw new IllegalArgumentException("containerPath must not be blank");
      }
    }
  }

  /** Docker container healthcheck (used only by the dind sidecar). */
  public record Healthcheck(
      List<String> test, Duration interval, Duration timeout, int retries) {
    public Healthcheck {
      test = test == null ? List.of() : List.copyOf(test);
    }
  }
}
```
Add `import java.time.Duration;`. Verify with the Grep tool that the ONLY `new CreateContainerSpec(` call sites in `src/main` match the four constructors above (image/binds/net/labels; +env; +securityOpts; the canonical 10-arg is new). If any other arity exists, add a matching delegating constructor.

- [ ] **Step 4: Add `healthStatus` to `ContainerState`**

Add a trailing `String healthStatus` component and a back-compat constructor:
```java
public record ContainerState(
    String status,
    Integer exitCode,
    String networkMode,
    List<CreateContainerSpec.BindMount> binds,
    Map<String, String> labels,
    OffsetDateTime startedAt,
    String healthStatus) {

  public ContainerState {
    Objects.requireNonNull(status, "status");
    binds = binds == null ? List.of() : List.copyOf(binds);
    labels = labels == null ? Map.of() : Map.copyOf(labels);
  }

  // Back-compat: the story-3.2 6-arg form (no health).
  public ContainerState(
      String status,
      Integer exitCode,
      String networkMode,
      List<CreateContainerSpec.BindMount> binds,
      Map<String, String> labels,
      OffsetDateTime startedAt) {
    this(status, exitCode, networkMode, binds, labels, startedAt, null);
  }

  // Back-compat: the story-3.1 5-arg form.
  public ContainerState(
      String status,
      Integer exitCode,
      String networkMode,
      List<CreateContainerSpec.BindMount> binds,
      Map<String, String> labels) {
    this(status, exitCode, networkMode, binds, labels, null, null);
  }

  public boolean isExited() {
    return "exited".equals(status) || "dead".equals(status);
  }
}
```

- [ ] **Step 5: Add gateway interface methods**

In `DockerEngineGateway.java` add:
```java
  /** Creates a user-defined bridge network with the given labels; returns the network id. */
  String createNetwork(String name, java.util.Map<String, String> labels);

  /** Removes a network by name/id. Idempotent — a missing network is a no-op. */
  void removeNetwork(String name);
```

- [ ] **Step 6: Implement in `DefaultDockerEngineGateway`**

In `createContainer`, after building `hostConfig` (binds + networkMode + securityOpts), add privileged + memory:
```java
    if (spec.privileged()) {
      hostConfig.withPrivileged(true);
    }
    if (spec.memoryBytes() != null) {
      hostConfig.withMemory(spec.memoryBytes());
    }
```
On the `cmd`, apply network aliases + healthcheck before `exec()`:
```java
      if (!spec.networkAliases().isEmpty()) {
        cmd.withAliases(spec.networkAliases());
      }
      if (spec.healthcheck() != null) {
        com.github.dockerjava.api.model.HealthCheck hc =
            new com.github.dockerjava.api.model.HealthCheck()
                .withTest(spec.healthcheck().test())
                .withInterval(spec.healthcheck().interval().toNanos())
                .withTimeout(spec.healthcheck().timeout().toNanos())
                .withRetries(spec.healthcheck().retries());
        cmd.withHealthcheck(hc);
      }
```
In `inspectContainer`, populate `healthStatus` from the state's health and pass it to the `ContainerState` canonical constructor:
```java
    String healthStatus =
        state != null && state.getHealth() != null ? state.getHealth().getStatus() : null;
    return new ContainerState(status, exitCode, networkMode, binds, labels, startedAt, healthStatus);
```
Add the two network methods:
```java
  @Override
  public String createNetwork(String name, Map<String, String> labels) {
    Objects.requireNonNull(name, "name");
    var response =
        client
            .createNetworkCmd()
            .withName(name)
            .withDriver("bridge")
            .withLabels(labels == null ? Map.of() : labels)
            .exec();
    log.info("docker network create name={} id={}", name, response.getId());
    return response.getId();
  }

  @Override
  public void removeNetwork(String name) {
    Objects.requireNonNull(name, "name");
    try {
      client.removeNetworkCmd(name).exec();
      log.info("docker network rm name={}", name);
    } catch (com.github.dockerjava.api.exception.NotFoundException missing) {
      log.info("docker network rm name={} reason=not_found (treated as no-op)", name);
    }
  }
```

- [ ] **Step 7: Run tests**

Run: `./mvnw -q -pl deliveryline-backend spotless:apply` then `./mvnw -q -pl deliveryline-backend test -Dtest=CreateContainerSpecTest,DockerRunnerAdapterUnitTest,DockerRunnerAdapterLoggingContractTest`
Expected: all green (the adapter tests prove the back-compat constructors still satisfy existing call sites). NOTE: do not run the full module test tier unless needed; run the named classes.

- [ ] **Step 8: Leave uncommitted (no commit per Global Constraints).**

---

### Task 2: `TESTCONTAINERS_INFRA_FAILED` failure category + transition table + recommendation

**Files:**
- Modify: `deliveryline-backend/src/main/java/org/dradgo/domain/registry/FailureCategory.java`
- Modify: `deliveryline-backend/src/main/java/org/dradgo/application/workflow/WorkflowTransitionTable.java`
- Modify: `deliveryline-backend/src/test/java/org/dradgo/foundation/TransitionTableCrossProductFoundationContract.java`
- Modify: `deliveryline-backend/src/main/java/org/dradgo/application/recovery/RecommendationService.java`
- Modify: `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerBroker.java` (exhaustive `switch` over `FailureCategory`)
- Test: `deliveryline-backend/src/test/java/org/dradgo/application/workflow/WorkflowTransitionTableTest.java`

**Interfaces:**
- Produces: `FailureCategory.TESTCONTAINERS_INFRA_FAILED` (value `"testcontainers_infra_failed"`), allowed on `EXECUTING → FAILED` and `INVESTIGATING → FAILED`.

- [ ] **Step 1: Write the failing transition test**

In `WorkflowTransitionTableTest.java`, inside `executingToFailedRequiresAnAllowedRunnerFailureCategory()`, after the existing `RUNNER_SECRET_LEAK` allowed-transition assertion, add:
```java
    // A pre-dispatch testcontainers-infra failure fails the run from EXECUTING like any runner
    // failure (the sidecar could not be provisioned for an opted-in run).
    table.assertTransitionAllowed(
        "run_demo1234",
        WorkflowState.EXECUTING,
        WorkflowState.FAILED,
        FailureCategory.TESTCONTAINERS_INFRA_FAILED,
        "testcontainers sidecar unavailable");
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q -pl deliveryline-backend test -Dtest=WorkflowTransitionTableTest#executingToFailedRequiresAnAllowedRunnerFailureCategory`
Expected: FAIL to COMPILE — `TESTCONTAINERS_INFRA_FAILED` does not exist.

- [ ] **Step 3: Add the enum value**

In `FailureCategory.java`, add before `ORPHAN`:
```java
  // Per-run testcontainers dockerd sidecar could not be provisioned (network/sidecar create or
  // readiness timeout) for an opted-in execution run. Carried on the terminal FAILED transition for
  // Epic-4 recovery; retryable. NOT DomainErrorCode-shaped.
  TESTCONTAINERS_INFRA_FAILED("testcontainers_infra_failed"),
```

- [ ] **Step 4: Allow it on the FAILED transition (both sites)**

In `WorkflowTransitionTable.java`, add to `ALLOWED_RUNNER_FAILURE_CATEGORIES`:
```java
          // A pre-dispatch testcontainers-infra failure fails the run from EXECUTING/INVESTIGATING.
          FailureCategory.TESTCONTAINERS_INFRA_FAILED);
```
(insert before the closing `)` of the `Set.of(...)`; move the current trailing `);` accordingly.)

In `TransitionTableCrossProductFoundationContract.java`, mirror it in that file's `ALLOWED_RUNNER_FAILURE_CATEGORIES`:
```java
          // Mirror of the WorkflowTransitionTable allow-list addition.
          FailureCategory.TESTCONTAINERS_INFRA_FAILED);
```

- [ ] **Step 5: Classify as retryable in `RecommendationService`**

Find `isRiskyRetryCategory` (it lists `RUNNER_CONTRACT_VIOLATION`, `RUNNER_MALFORMED_OUTPUT`, `RUNNER_SECRET_LEAK`). Add `TESTCONTAINERS_INFRA_FAILED` to the **cautious** (not risky) retry set — locate `isCautionRetryCategory` (currently `RUNNER_BUILD_FAILED`, `ORPHAN`) and add:
```java
        || FailureCategory.TESTCONTAINERS_INFRA_FAILED.value().equals(failureCategory);
```
(An infra failure is a safe/cautious retry — retrying re-provisions the sidecar.)

- [ ] **Step 6: Audit the exhaustive `switch` in `RunnerBroker`**

In `RunnerBroker.java` find the `switch` over `FailureCategory` in the duplicate-result classifier (`case RUNNER_CRASH, RUNNER_TIMEOUT, ORPHAN -> false;` arm). A new enum constant makes the switch non-exhaustive → compile error. Add `TESTCONTAINERS_INFRA_FAILED` to the `-> false` arm (a testcontainers-infra failure is a pre-dispatch failure: no result was harvested, so a later arrival is NOT a duplicate):
```java
      case RUNNER_CRASH, RUNNER_TIMEOUT, ORPHAN, TESTCONTAINERS_INFRA_FAILED -> false;
```
Use the Grep tool for `case RUNNER_CRASH` in `RunnerBroker.java` to locate it; if any OTHER exhaustive `switch (…FailureCategory…)` exists in `src/main`, add the constant there too (Grep `switch` near `FailureCategory`).

- [ ] **Step 7: Run tests**

Run: `./mvnw -q -pl deliveryline-backend spotless:apply` then `./mvnw -q -pl deliveryline-backend test -Dtest=WorkflowTransitionTableTest,RecommendationServiceTest` (include `RecommendationServiceTest` only if it exists — Grep for it; otherwise just `WorkflowTransitionTableTest`).
Expected: green. Also confirm the module still compiles: `./mvnw -q -pl deliveryline-backend compile`.

- [ ] **Step 8: Leave uncommitted.**

---

### Task 3: `TestcontainersProperties` config

**Files:**
- Create: `deliveryline-backend/src/main/java/org/dradgo/application/runner/TestcontainersProperties.java`
- Modify: `deliveryline-backend/src/main/resources/application.yml`
- Modify: `deliveryline-backend/src/test/resources/application.yml`
- Test: `deliveryline-backend/src/test/java/org/dradgo/application/runner/TestcontainersPropertiesTest.java` (create)

**Interfaces:**
- Produces: `TestcontainersProperties(String dindImage, long memoryBytes, Duration readinessTimeout)` bound from `deliveryline.runner.testcontainers.*`, with a `defaults()` factory (`docker:27-dind`, 2 GiB, 60s). Consumed by `DindSidecarService` (Task 5).

- [ ] **Step 1: Write the failing test**

Create `TestcontainersPropertiesTest.java`:
```java
package org.dradgo.application.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TestcontainersPropertiesTest {

  @Test
  void defaultsMatchTheSpec() {
    TestcontainersProperties p = TestcontainersProperties.defaults();
    assertThat(p.dindImage()).isEqualTo("docker:27-dind");
    assertThat(p.memoryBytes()).isEqualTo(2L * 1024 * 1024 * 1024);
    assertThat(p.readinessTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void rejectsBlankImageAndNonPositiveMemory() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new TestcontainersProperties(" ", 1L, Duration.ofSeconds(60)));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new TestcontainersProperties("docker:27-dind", 0L, Duration.ofSeconds(60)));
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q -pl deliveryline-backend test -Dtest=TestcontainersPropertiesTest`
Expected: FAIL to COMPILE — class does not exist.

- [ ] **Step 3: Create the properties record**

```java
package org.dradgo.application.runner;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Per-run DinD Testcontainers sidecar tunables, bound from {@code
 * deliveryline.runner.testcontainers.*}. Kept as a SEPARATE @ConfigurationProperties (not a
 * component on the RunnerProperties.Docker record) to avoid that record's constructor fan-out.
 */
@ConfigurationProperties(prefix = "deliveryline.runner.testcontainers")
public record TestcontainersProperties(
    @DefaultValue("docker:27-dind") String dindImage,
    @DefaultValue("2147483648") long memoryBytes,
    @DefaultValue("60s") Duration readinessTimeout) {

  public TestcontainersProperties {
    if (dindImage == null || dindImage.isBlank()) {
      throw new IllegalArgumentException("deliveryline.runner.testcontainers.dind-image must be set");
    }
    if (memoryBytes <= 0L) {
      throw new IllegalArgumentException(
          "deliveryline.runner.testcontainers.memory-bytes must be positive: " + memoryBytes);
    }
    Objects.requireNonNull(readinessTimeout, "readinessTimeout");
    if (readinessTimeout.isZero() || readinessTimeout.isNegative()) {
      throw new IllegalArgumentException(
          "deliveryline.runner.testcontainers.readiness-timeout must be positive");
    }
  }

  public static TestcontainersProperties defaults() {
    return new TestcontainersProperties("docker:27-dind", 2L * 1024 * 1024 * 1024, Duration.ofSeconds(60));
  }
}
```
Register it: find where `RunnerProperties` is enabled via `@EnableConfigurationProperties` or `@ConfigurationPropertiesScan` (Grep `@EnableConfigurationProperties` in `src/main`); add `TestcontainersProperties.class` to that annotation. If the app uses `@ConfigurationPropertiesScan`, no edit is needed — verify.

- [ ] **Step 4: Add config keys**

In `deliveryline-backend/src/main/resources/application.yml`, under `deliveryline.runner:`, add:
```yaml
    # Per-run DinD Testcontainers sidecar (only used when a project has testcontainers-enabled=true).
    testcontainers:
      dind-image: docker:27-dind
      memory-bytes: 2147483648   # 2 GiB cap on the privileged sidecar
      readiness-timeout: 60s
```
Mirror the same block into `deliveryline-backend/src/test/resources/application.yml` (a validated @ConfigurationProperties needs the test yaml, or context-load tests can fail — known trap).

- [ ] **Step 5: Run test**

Run: `./mvnw -q -pl deliveryline-backend spotless:apply` then `./mvnw -q -pl deliveryline-backend test -Dtest=TestcontainersPropertiesTest`
Expected: green.

- [ ] **Step 6: Leave uncommitted.**

---

### Task 4: Per-project `testcontainersEnabled` flag (mirror the `openspecEnabled` fan-out)

**Files (mirror each `openspecEnabled` site — the flag added by story 3a; add a parallel `testcontainersEnabled`):**
- `deliveryline-backend/src/main/resources/db/migration/V40__add_testcontainers_enabled.sql` (create)
- `domain/project/Project.java`, `application/project/CreateProjectCommand.java`, `application/project/UpdateProjectCommand.java`, `application/project/ProjectManagementService.java`, `application/project/DefaultProjectSeeder.java`, `application/project/ProjectRuntimeConfigResolver.java`
- `adapters/persistence/entity/ProjectEntity.java`, `adapters/persistence/mapper/ProjectEntityMapper.java`
- `adapters/rest/CreateProjectRequest.java`, `adapters/rest/UpdateProjectRequest.java`, `adapters/rest/ProjectResponse.java`, `adapters/rest/ProjectController.java`
- Frontend (mirror openspec): `features/projects/projectFormView.ts`, `components/ProjectForm.tsx`, `hooks/useCreateProject.ts`, `hooks/useUpdateProject.ts`, `lib/api/schema.d.ts` (regenerate), `test/handlers.ts`, plus the two `__tests__` files.
- Test: `deliveryline-backend/src/test/java/org/dradgo/application/project/ProjectRuntimeConfigResolverTest.java` (add a method)

**Interfaces:**
- Produces: `Project.testcontainersEnabled()` (`boolean`, default false, LAST record component) and `ProjectRuntimeConfigResolver.resolveTestcontainersEnabled(String workflowRunId)` → `boolean`. Consumed by `DockerRunnerAdapter` (Task 6).

> **Pattern:** this is a mechanical mirror of the existing `openspecEnabled` boolean flag. For each file above, make the SAME shape of change `openspecEnabled` has, for a new `testcontainersEnabled` (default `false`). Use the Grep tool for `openspecEnabled` (backend) / `openspecEnabled`/`openspec_enabled` (frontend) in each file to find the exact lines to parallel. Add `testcontainersEnabled` as the LAST field wherever position matters (record components, entity columns, DTOs) so existing positional call sites shift predictably.

- [ ] **Step 1: Write the failing resolver test**

In `ProjectRuntimeConfigResolverTest.java` (Grep to confirm the file + its existing `resolveOpenSpecEnabled` test for the mock/setup pattern), add:
```java
  @Test
  void resolveTestcontainersEnabledReadsTheResolvedProjectFlag() {
    // Mirror the existing resolveOpenSpecEnabled test: stub the store to return a project whose
    // testcontainersEnabled is true, assert the resolver returns true; default project false.
    // (Use the same project-builder/mocking helpers this test class already uses.)
  }
```
Fill the body by copying the class's existing `resolveOpenSpecEnabled` test and swapping the field to `testcontainersEnabled`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q -pl deliveryline-backend test -Dtest=ProjectRuntimeConfigResolverTest`
Expected: FAIL to COMPILE — `testcontainersEnabled`/`resolveTestcontainersEnabled` do not exist.

- [ ] **Step 3: Flyway migration V40**

Create `V40__add_testcontainers_enabled.sql`:
```sql
alter table projects
    add column testcontainers_enabled boolean not null default false;
```

- [ ] **Step 4: Domain + resolver**

In `Project.java` add `boolean testcontainersEnabled` as the LAST record component (mirror `openspecEnabled` through the compact constructor / builder). In `ProjectRuntimeConfigResolver.java` add:
```java
  /**
   * The effective Testcontainers opt-in for a run — the resolved project's {@code
   * testcontainersEnabled} (seeded false). Read on the worker thread (detached POJO, no lazy proxy).
   */
  public boolean resolveTestcontainersEnabled(String workflowRunId) {
    return resolveForRun(workflowRunId).testcontainersEnabled();
  }
```

- [ ] **Step 5: Persistence + commands + seeder + REST**

Mirror `openspecEnabled` in each of: `ProjectEntity` (add `testcontainers_enabled` column field), `ProjectEntityMapper` (both directions), `CreateProjectCommand`/`UpdateProjectCommand` (new field), `ProjectManagementService` (thread it through create/update + the Project constructor call), `DefaultProjectSeeder` (seed `false` unless a global default is desired — mirror how openspec seeds), `CreateProjectRequest`/`UpdateProjectRequest`/`ProjectResponse` DTOs, `ProjectController` (map request↔command↔response). Grep `openspecEnabled` in each file to see the exact lines.

- [ ] **Step 6: Frontend mirror + regen API types**

Mirror `openspecEnabled` in `projectFormView.ts`, `ProjectForm.tsx`, `useCreateProject.ts`, `useUpdateProject.ts`, `test/handlers.ts`, and the two `__tests__`. Regenerate the OpenAPI client so `schema.d.ts` carries the new field: `cd deliveryline-frontend && npm run generate-api` (this needs the backend OpenAPI regenerated first — run the backend's openapi generation the same way the openspec flag did; Grep the repo for the generate script). Do NOT hand-edit `schema.d.ts`.

- [ ] **Step 7: Run tests**

Run: `./mvnw -q -pl deliveryline-backend spotless:apply` then `./mvnw -q -pl deliveryline-backend test -Dtest=ProjectRuntimeConfigResolverTest,ProjectManagementServiceTest,ProjectControllerTest` (include those that exist — Grep). Frontend: `cd deliveryline-frontend && npm run build && npx vitest run src/features/projects`.
Expected: green backend + frontend.

- [ ] **Step 8: Leave uncommitted.**

---

### Task 5: `DindSidecarService` — provision / readiness / teardown

**Files:**
- Create: `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DindSidecarService.java`
- Test: `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/docker/DindSidecarServiceTest.java` (create)

**Interfaces:**
- Consumes: `DockerEngineGateway` (Task 1 methods), `TestcontainersProperties` (Task 3), `Clock`.
- Produces:
  - `DindHandle(String networkName, String sidecarContainerId, Map<String,String> runnerEnv)`.
  - `DindSidecarService.provision(String rexId) → DindHandle` (throws `DindProvisionException` on failure, after cleaning up partial resources).
  - `DindSidecarService.teardown(String rexId, String networkName, String sidecarContainerId)` — idempotent, best-effort.
  - Constant `NETWORK_NAME_PREFIX = "deliveryline-net-"`, label key `DIND_LABEL_KEY = "deliveryline.dind"`, alias `dind`.

- [ ] **Step 1: Write the failing tests (mocked gateway)**

Create `DindSidecarServiceTest.java`:
```java
package org.dradgo.adapters.runner.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.dradgo.application.runner.TestcontainersProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DindSidecarServiceTest {

  private static final String REX = "rex_dind0000000001";
  private static final String NET = "deliveryline-net-rex_dind0000000001";

  private DockerEngineGateway gateway;
  private DindSidecarService service;

  @BeforeEach
  void setUp() {
    gateway = mock(DockerEngineGateway.class);
    service =
        new DindSidecarService(
            gateway,
            new TestcontainersProperties("docker:27-dind", 2147483648L, Duration.ofSeconds(60)),
            Clock.fixed(Instant.parse("2026-07-10T00:00:00Z"), ZoneOffset.UTC));
  }

  private ContainerState health(String status) {
    return new ContainerState("running", null, NET, List.of(), Map.of(), null, status);
  }

  @Test
  void provisionCreatesNetworkAndHealthySidecarAndReturnsRunnerEnv() {
    when(gateway.createNetwork(eq(NET), any())).thenReturn("net123");
    when(gateway.createContainer(any())).thenReturn("dind123");
    when(gateway.inspectContainer("dind123")).thenReturn(health("healthy"));

    DindSidecarService.DindHandle handle = service.provision(REX);

    assertThat(handle.networkName()).isEqualTo(NET);
    assertThat(handle.sidecarContainerId()).isEqualTo("dind123");
    assertThat(handle.runnerEnv())
        .containsEntry("DOCKER_HOST", "tcp://dind:2375")
        .containsEntry("TESTCONTAINERS_HOST_OVERRIDE", "dind")
        .containsEntry("TESTCONTAINERS_RYUK_DISABLED", "true");
    // sidecar spec asserted: privileged, alias dind, image, healthcheck, TLS-off env.
    org.mockito.ArgumentCaptor<CreateContainerSpec> spec =
        org.mockito.ArgumentCaptor.forClass(CreateContainerSpec.class);
    verify(gateway).createContainer(spec.capture());
    assertThat(spec.getValue().privileged()).isTrue();
    assertThat(spec.getValue().networkAliases()).containsExactly("dind");
    assertThat(spec.getValue().networkMode()).isEqualTo(NET);
    assertThat(spec.getValue().image()).isEqualTo("docker:27-dind");
    assertThat(spec.getValue().environment()).containsEntry("DOCKER_TLS_CERTDIR", "");
    assertThat(spec.getValue().healthcheck()).isNotNull();
    verify(gateway).startContainer("dind123");
  }

  @Test
  void provisionThrowsAndCleansUpWhenSidecarNeverBecomesHealthy() {
    when(gateway.createNetwork(eq(NET), any())).thenReturn("net123");
    when(gateway.createContainer(any())).thenReturn("dind123");
    when(gateway.inspectContainer("dind123")).thenReturn(health("starting")); // never healthy

    assertThatThrownBy(() -> service.provision(REX))
        .isInstanceOf(DindSidecarService.DindProvisionException.class);

    // partial cleanup: sidecar removed, network removed.
    verify(gateway).removeContainer(eq("dind123"), anyBoolean());
    verify(gateway).removeNetwork(NET);
  }

  @Test
  void teardownRemovesSidecarThenNetworkIdempotently() {
    service.teardown(REX, NET, "dind123");
    verify(gateway).removeContainer("dind123", true);
    verify(gateway).removeNetwork(NET);
    // idempotent: a second call with nulls does not throw
    service.teardown(REX, NET, null);
    verify(gateway, times(1)).removeContainer("dind123", true);
  }
}
```
(The readiness poll must not sleep in the test — see Step 3: make the poll interval/clock injectable or the readiness loop bounded by an injected iteration cap so a "never healthy" case returns fast. Use a small fixed poll count derived from `readinessTimeout` with a `Sleeper` seam that the test replaces with a no-op.)

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q -pl deliveryline-backend test -Dtest=DindSidecarServiceTest`
Expected: FAIL to COMPILE — class does not exist.

- [ ] **Step 3: Implement `DindSidecarService`**

```java
package org.dradgo.adapters.runner.docker;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.runner.TestcontainersProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Owns the per-run DinD sidecar lifecycle (adapters.runner.docker so it can build CreateContainerSpec
 * and call the gateway directly — it cannot live in application.* under the ArchUnit boundary). One
 * per-run user-defined bridge network + one privileged docker:dind sidecar aliased "dind"; the
 * runner joins the network and drives the sidecar's daemon via DOCKER_HOST.
 */
@Component
public class DindSidecarService {

  private static final Logger log = LoggerFactory.getLogger(DindSidecarService.class);

  static final String NETWORK_NAME_PREFIX = "deliveryline-net-";
  static final String DIND_LABEL_KEY = "deliveryline.dind";
  static final String DIND_ALIAS = "dind";
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

  private final DockerEngineGateway gateway;
  private final TestcontainersProperties properties;
  private final Clock clock;
  private final Sleeper sleeper;

  public DindSidecarService(
      DockerEngineGateway gateway, TestcontainersProperties properties, Clock clock) {
    this(gateway, properties, clock, millis -> {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
  }

  DindSidecarService(
      DockerEngineGateway gateway,
      TestcontainersProperties properties,
      Clock clock,
      Sleeper sleeper) {
    this.gateway = Objects.requireNonNull(gateway, "gateway");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  public record DindHandle(String networkName, String sidecarContainerId, Map<String, String> runnerEnv) {}

  public static final class DindProvisionException extends RuntimeException {
    public DindProvisionException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  interface Sleeper {
    void sleep(long millis);
  }

  public DindHandle provision(String rexId) {
    Objects.requireNonNull(rexId, "rexId");
    String networkName = NETWORK_NAME_PREFIX + rexId;
    String sidecarId = null;
    try {
      gateway.createNetwork(networkName, Map.of(DIND_LABEL_KEY, rexId));
      sidecarId = gateway.createContainer(sidecarSpec(rexId, networkName));
      gateway.startContainer(sidecarId);
      awaitHealthy(sidecarId);
      Map<String, String> runnerEnv =
          Map.of(
              "DOCKER_HOST", "tcp://" + DIND_ALIAS + ":2375",
              "TESTCONTAINERS_HOST_OVERRIDE", DIND_ALIAS,
              "TESTCONTAINERS_RYUK_DISABLED", "true");
      log.info("dind provisioned rex={} network={} sidecar={}", rexId, networkName, sidecarId);
      return new DindHandle(networkName, sidecarId, runnerEnv);
    } catch (RuntimeException failure) {
      teardown(rexId, networkName, sidecarId);
      throw new DindProvisionException(
          "failed to provision dind sidecar for " + rexId, failure);
    }
  }

  private CreateContainerSpec sidecarSpec(String rexId, String networkName) {
    CreateContainerSpec.Healthcheck hc =
        new CreateContainerSpec.Healthcheck(
            List.of("CMD-SHELL", "docker -H tcp://localhost:2375 version"),
            POLL_INTERVAL,
            Duration.ofSeconds(3),
            30);
    return new CreateContainerSpec(
        properties.dindImage(),
        List.of(),
        networkName,
        Map.of(DIND_LABEL_KEY, rexId),
        Map.of("DOCKER_TLS_CERTDIR", ""),
        List.of(),
        true,
        List.of(DIND_ALIAS),
        properties.memoryBytes(),
        hc);
  }

  private void awaitHealthy(String sidecarId) {
    long deadlineNanos =
        clock.instant().plus(properties.readinessTimeout()).toEpochMilli();
    while (clock.millis() < deadlineNanos) {
      String health = gateway.inspectContainer(sidecarId).healthStatus();
      if ("healthy".equals(health)) {
        return;
      }
      if ("unhealthy".equals(health)) {
        throw new IllegalStateException("dind sidecar reported unhealthy: " + sidecarId);
      }
      sleeper.sleep(POLL_INTERVAL.toMillis());
    }
    throw new IllegalStateException(
        "dind sidecar not healthy within " + properties.readinessTimeout() + ": " + sidecarId);
  }

  public void teardown(String rexId, String networkName, String sidecarContainerId) {
    if (sidecarContainerId != null) {
      try {
        gateway.removeContainer(sidecarContainerId, true);
      } catch (RuntimeException e) {
        log.warn("dind teardown sidecar rm best-effort failure rex={} cause={}", rexId, e.toString());
      }
    }
    if (networkName != null) {
      try {
        gateway.removeNetwork(networkName);
      } catch (RuntimeException e) {
        log.warn("dind teardown network rm best-effort failure rex={} cause={}", rexId, e.toString());
      }
    }
  }
}
```
Note the test's "never healthy" case: with the injected no-op `Sleeper` and a `Clock.fixed`, the `while (clock.millis() < deadline)` loop would spin forever because a fixed clock never advances. **Fix the readiness loop to be iteration-bounded**: compute `maxPolls = max(1, readinessTimeout / POLL_INTERVAL)` and loop that many times, calling `sleeper.sleep` between polls. Rewrite `awaitHealthy` as a bounded `for` loop over `maxPolls`; the production `Sleeper` really sleeps, the test's is a no-op. Update the code accordingly so the test returns promptly.

- [ ] **Step 4: Run to verify green**

Run: `./mvnw -q -pl deliveryline-backend spotless:apply` then `./mvnw -q -pl deliveryline-backend test -Dtest=DindSidecarServiceTest`
Expected: 3 tests green, and the "never healthy" test returns in well under a second (no real sleeping).

- [ ] **Step 5: Leave uncommitted.**

---

### Task 6: Wire `DockerRunnerAdapter` — gated provision, env, network, fail-fast, teardown

**Files:**
- Modify: `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/DockerRunnerAdapter.java`
- Test: `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DockerRunnerAdapterUnitTest.java`

**Interfaces:**
- Consumes: `DindSidecarService` (Task 5), `ProjectRuntimeConfigResolver.resolveTestcontainersEnabled` (Task 4), `request.stage()` / `request.workflowRunId()`.
- Produces: gated dind provisioning + runner env/network wiring + teardown; fail-fast throwing the failure that the broker records as `TESTCONTAINERS_INFRA_FAILED`.

- [ ] **Step 1: Write the failing adapter tests**

In `DockerRunnerAdapterUnitTest.java` add three tests (reuse the file's existing dispatch-test scaffolding — the request constant, the `specCaptor` pattern, `gateway`/`workspaceStore` mocks; and add a `DindSidecarService` mock + `ProjectRuntimeConfigResolver` mock to the adapter constructor per the wiring you add in Step 3):
```java
  @Test
  void executionStageWithTestcontainersOnProvisionsSidecarAttachesNetworkAndInjectsEnv() {
    // stage=EXECUTION, resolver.resolveTestcontainersEnabled(runId)=true,
    // dind.provision(rex) returns handle(network="deliveryline-net-"+REX, "dind1",
    //   env{DOCKER_HOST,TESTCONTAINERS_HOST_OVERRIDE,TESTCONTAINERS_RYUK_DISABLED}).
    // Assert the runner CreateContainerSpec.networkMode == the per-run network AND its environment
    // contains the three injected keys. Assert dind.provision called once.
  }

  @Test
  void flagOffIsByteIdenticalNoSidecarNoNetworkChange() {
    // resolver.resolveTestcontainersEnabled=false → dind.provision NEVER called; runner networkMode
    // stays runnerProperties.docker().networkMode(); env has none of the three keys.
  }

  @Test
  void provisionFailureFailsFastWithoutDispatch() {
    // dind.provision throws DindProvisionException → adapter does NOT createContainer the runner,
    // and surfaces a failure the broker maps to TESTCONTAINERS_INFRA_FAILED (assert the adapter
    // throws / signals failure per the mechanism you choose in Step 3; verify gateway.createContainer
    // is never called for the runner).
  }
```
Fill the bodies using the file's existing helpers; assert via the `specCaptor` already used by the sibling dispatch test.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl deliveryline-backend test -Dtest=DockerRunnerAdapterUnitTest`
Expected: FAIL to COMPILE (new ctor deps / methods) or FAIL assertions.

- [ ] **Step 3: Implement the wiring**

Add `DindSidecarService dindSidecarService` and `ProjectRuntimeConfigResolver projectRuntimeConfigResolver` as adapter dependencies. To avoid the `docker-adapter-ctor-dep-fans-out` trap (new ctor params break every `new DockerRunnerAdapter(...)` test site), inject them via SETTER injection (the file already uses setter injection for some deps — mirror that): `@Autowired(required=false) void setDindSidecarService(...)` / `setProjectRuntimeConfigResolver(...)`, with null-guards so mock-profile/no-dind dispatch is unaffected.

In the dispatch method, AFTER resolving `runnerStageToken` and BEFORE building the runner `CreateContainerSpec`:
```java
    DindSidecarService.DindHandle dindHandle = null;
    boolean testcontainersOn =
        dindSidecarService != null
            && projectRuntimeConfigResolver != null
            && request.stage() == org.dradgo.domain.registry.RunnerStage.EXECUTION
            && projectRuntimeConfigResolver.resolveTestcontainersEnabled(request.workflowRunId());
    if (testcontainersOn) {
      try {
        dindHandle = dindSidecarService.provision(rexId);
      } catch (DindSidecarService.DindProvisionException failure) {
        // Fail-fast: the opted-in run needs Docker; do not dispatch the agent. Surface a failure the
        // broker records as TESTCONTAINERS_INFRA_FAILED (mirror how the adapter reports a create/start
        // failure today — see the RuntimeException path around container create). The broker's dispatch
        // caller maps this to driveWorkflowFailed(..., TESTCONTAINERS_INFRA_FAILED, ...).
        throw new RunnerDispatchException(
            org.dradgo.domain.registry.FailureCategory.TESTCONTAINERS_INFRA_FAILED,
            "testcontainers sidecar unavailable",
            failure);
      }
    }
```
(If the codebase has no `RunnerDispatchException` carrying a FailureCategory, use the SAME failure-signalling mechanism the adapter already uses to fail a dispatch — Grep the broker↔adapter dispatch path for how a create/start failure becomes a `driveWorkflowFailed`. Match it; do NOT invent a new error channel. The key behavioral requirement pinned by the test: on provision failure the runner container is never created and the run ends FAILED with `TESTCONTAINERS_INFRA_FAILED`.)

When building the runner spec, override networkMode + merge env when `dindHandle != null`:
```java
    String runnerNetworkMode =
        dindHandle != null ? dindHandle.networkName() : runnerProperties.docker().networkMode();
    if (dindHandle != null) {
      containerEnv.putAll(dindHandle.runnerEnv());
    }
```
Pass `runnerNetworkMode` into the runner `CreateContainerSpec` instead of `networkMode`.

Teardown: on the dispatch error paths (the existing `catch (RuntimeException)` cleanup around container create/start, ~lines 398-426) AND in `cancel(...)`, call `dindSidecarService.teardown(rex, handle.networkName(), handle.sidecarContainerId())` if a handle was provisioned. Track the handle per-rex in a `ConcurrentMap<String, DindHandle>` mirroring `rexIdToContainerId`, populate on provision, remove+teardown in `cancel`. Add a dind sub-sweep as the backstop for normal completion (Task 7) — the map-based teardown covers cancel/timeout + dispatch failure; the sweep covers completion/crash.

- [ ] **Step 4: Run to verify green**

Run: `./mvnw -q -pl deliveryline-backend spotless:apply` then `./mvnw -q -pl deliveryline-backend test -Dtest=DockerRunnerAdapterUnitTest`
Expected: all green, including the three new tests and every pre-existing dispatch test (flag-off byte-identical).

- [ ] **Step 5: Leave uncommitted.**

---

### Task 7: Dangling-sweep reaping of orphan dind sidecars + networks

**Files:**
- Modify: `deliveryline-backend/src/main/java/org/dradgo/application/runner/spi/DockerHostPort.java` (add network list/remove)
- Modify: `deliveryline-backend/src/main/java/org/dradgo/adapters/runner/docker/DefaultDockerEngineGateway.java` (implement them)
- Modify: `deliveryline-backend/src/main/java/org/dradgo/application/runner/RunnerWorkspaceCleanupJob.java` (add `sweepDanglingDind`)
- Test: `deliveryline-backend/src/test/java/org/dradgo/application/runner/RunnerWorkspaceCleanupJobDindUnitTest.java` (create) — mirror the existing dangling-container sweep unit test

**Interfaces:**
- Consumes: `DockerHostPort.listContainersByLabel` (exists), new `listNetworksByLabel(String labelKey) → List<NetworkInfo(id, name, label rex)>` and `removeNetwork(String name)`.
- Produces: `RunnerWorkspaceCleanupJob.sweepDanglingDind()` reaping `deliveryline.dind=rex_*` sidecars + `deliveryline-net-rex_*` networks whose run row is terminal/absent, container-before-network.

- [ ] **Step 1: Write the failing sweep test**

Create `RunnerWorkspaceCleanupJobDindUnitTest.java` mirroring the existing dangling-container sweep test: a `DockerHostPort` mock returns one dind sidecar (label `deliveryline.dind=rex_gone`, state `running`) and one network `deliveryline-net-rex_gone`; `recordPort.findByPublicId("rex_gone")` returns empty (row gone). Assert `sweepDanglingDind()` stops+removes the sidecar then removes the network; and that a sidecar whose row is still PENDING/RUNNING is preserved.
```java
// Structure: mock recordPort, workspaceStore, DockerHostPort provider, runnerProperties, clock;
// stub docker.listContainersByLabel("deliveryline.dind","rex_") -> [sidecar rex_gone running];
// stub docker.listNetworksByLabel("deliveryline.dind") -> [net deliveryline-net-rex_gone rex_gone];
// recordPort.findByPublicId("rex_gone") -> empty;
// assertThat(job.sweepDanglingDind()).isEqualTo(1);
// verify docker.stopContainer(sidecarId, ...); verify docker.removeContainer(sidecarId, true);
// verify docker.removeNetwork("deliveryline-net-rex_gone");
```

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw -q -pl deliveryline-backend test -Dtest=RunnerWorkspaceCleanupJobDindUnitTest`
Expected: FAIL to COMPILE — `sweepDanglingDind` / new port methods do not exist.

- [ ] **Step 3: Extend `DockerHostPort`**

Add to the interface:
```java
  /** Networks labeled with the given key; used to reap orphan per-run dind networks. */
  List<NetworkInfo> listNetworksByLabel(String labelKey);

  /** Remove a network by name/id (idempotent). */
  void removeNetwork(String name);

  record NetworkInfo(String id, String name, String labelValue) {}
```

- [ ] **Step 4: Implement in `DefaultDockerEngineGateway`**

```java
  @Override
  public List<DockerHostPort.NetworkInfo> listNetworksByLabel(String labelKey) {
    Objects.requireNonNull(labelKey, "labelKey");
    var networks =
        client.listNetworksCmd().exec().stream()
            .filter(n -> n.getLabels() != null && n.getLabels().containsKey(labelKey))
            .map(
                n ->
                    new DockerHostPort.NetworkInfo(
                        n.getId(), n.getName(), n.getLabels().get(labelKey)))
            .toList();
    log.info("docker network ls --filter label={} matches={}", labelKey, networks.size());
    return networks;
  }
```
(`removeNetwork` already added in Task 1 for `DockerEngineGateway`; the class implements both interfaces, so the same method satisfies `DockerHostPort.removeNetwork` too — verify one implementation covers both, or add the `@Override` for the port.)

- [ ] **Step 5: Add `sweepDanglingDind` to the cleanup job**

Add a fourth sweep, called from `runCleanup()` after `sweepDanglingContainers()`:
```java
  public int sweepDanglingDind() {
    DockerHostPort docker = dockerHostPortProvider.getIfAvailable();
    if (docker == null) {
      return 0;
    }
    int removed = 0;
    // sidecars first (a network can't be removed while a container is attached)
    for (DockerHostPort.DanglingContainerInfo c :
        docker.listContainersByLabel("deliveryline.dind", "rex_")) {
      try {
        var row = safeFindRow(c.runnerExecutionId());
        if (row.isPresent() && isStillActive(row.get())) {
          continue;
        }
        if ("running".equals(c.status()) || "paused".equals(c.status())
            || "restarting".equals(c.status()) || "unknown".equals(c.status())) {
          docker.stopContainer(c.containerId(), Duration.ofSeconds(10L));
          docker.removeContainer(c.containerId(), true);
        } else {
          docker.removeContainer(c.containerId(), false);
        }
        removed++;
      } catch (RuntimeException e) {
        log.warn("dind sidecar sweep best-effort failure rex={} cause={}", c.runnerExecutionId(), e.toString());
      }
    }
    // then orphan networks whose run row is gone/terminal
    for (DockerHostPort.NetworkInfo net : docker.listNetworksByLabel("deliveryline.dind")) {
      try {
        var row = safeFindRow(net.labelValue());
        if (row.isPresent() && isStillActive(row.get())) {
          continue;
        }
        docker.removeNetwork(net.name());
        removed++;
      } catch (RuntimeException e) {
        log.warn("dind network sweep best-effort failure network={} cause={}", net.name(), e.toString());
      }
    }
    return removed;
  }

  private Optional<RunnerExecutionSnapshot> safeFindRow(String rexId) {
    try {
      return recordPort.findByPublicId(rexId);
    } catch (DomainException invalidId) {
      return Optional.empty();
    }
  }
```
Wire it into `runCleanup()` and add its count to the total + the log line.

- [ ] **Step 6: Run to verify green**

Run: `./mvnw -q -pl deliveryline-backend spotless:apply` then `./mvnw -q -pl deliveryline-backend test -Dtest=RunnerWorkspaceCleanupJobDindUnitTest,RunnerWorkspaceCleanupJobDanglingUnitTest`
Expected: green (new sweep + the existing dangling test unaffected).

- [ ] **Step 7: Leave uncommitted.**

---

### Task 8: End-to-end `docker-runner-it` — real sidecar + teardown

**Files:**
- Create: `deliveryline-backend/src/test/java/org/dradgo/adapters/runner/DindSidecarIT.java`

**Interfaces:**
- Consumes: `DindSidecarService` + a real `DefaultDockerEngineGateway` (real Docker daemon on the CI Docker tier).

- [ ] **Step 1: Write the IT**

Create `DindSidecarIT.java`, tagged `@Tag("docker-runner-it")` + `@EnabledIfDockerAvailable` (mirror `CodexRunnerImageConformanceIT`'s gating). It: builds a `DefaultDockerEngineGateway` from `DockerClientFactory.instance().client()`; constructs `DindSidecarService` with `TestcontainersProperties.defaults()` and `Clock.systemUTC()`; calls `provision("rex_it0000000001")`; asserts the returned handle env has the three keys; then starts a tiny **client** container (`docker:27-cli` or `docker:27`) attached to `handle.networkName()` with env `DOCKER_HOST=tcp://dind:2375` running `docker version`, and asserts it exits 0 (proving the runner can reach the sidecar daemon over the per-run network by the `dind` alias). Finally calls `teardown(...)` and asserts (via the gateway/`DockerHostPort`) that no `deliveryline.dind=rex_it0000000001` container and no `deliveryline-net-rex_it0000000001` network remain. Use try/finally so teardown always runs even on assertion failure.

- [ ] **Step 2: Run it (requires Docker)**

Run: `./mvnw -B -pl deliveryline-backend -Ddocker-runner-it verify -Dit.test=DindSidecarIT`
Expected: PASS on a host with a Docker daemon (the sidecar boots, the client container reaches it, teardown leaves nothing). If Docker is unavailable locally, the test is skipped by `@EnabledIfDockerAvailable`; record that it must run in the Docker CI tier.

- [ ] **Step 3: Leave uncommitted.**

---

## Self-Review

**Spec coverage:**
- `CreateContainerSpec`/gateway extensions (privileged, aliases, memory, healthcheck, `ContainerState.healthStatus`, createNetwork/removeNetwork) → Task 1. ✓
- `FailureCategory.TESTCONTAINERS_INFRA_FAILED` + transition table two-site + RecommendationService + exhaustive-switch audit → Task 2. ✓
- Sidecar tunables as a separate `@ConfigurationProperties` (fan-out avoidance) → Task 3. ✓
- Per-project `testcontainers-enabled` flag + Flyway + resolver (openspec mirror) → Task 4. ✓
- `DindSidecarService` provision/readiness(health poll)/teardown, plaintext dind, alias `dind`, per-run network, injected env → Task 5. ✓
- Adapter integration: gated provision, network attach, env inject, fail-fast, teardown; flag-off byte-identical → Task 6. ✓
- Dangling-sweep reaping of orphan sidecars + networks, container-before-network → Task 7. ✓
- End-to-end + teardown IT → Task 8. ✓
- Non-goals (no runner-image change, no docker CLI in runner, CI/build stage reuse later) → respected (no task touches the runner image). ✓

**Deviations from spec (intentional, flagged in Global Constraints):**
1. `DindSidecarService` lives in `adapters.runner.docker`, not `application.runner` — it must build `CreateContainerSpec` and call the gateway, which `application.*` cannot import. Same-behavior, correct-layer.
2. Adapter deps injected via setter (not constructor) to avoid the `docker-adapter-ctor-dep-fans-out` trap.
3. The fail-fast failure channel (Task 6 Step 3) is specified against the codebase's existing dispatch-failure mechanism rather than inventing a new one — the implementer must Grep the broker↔adapter dispatch-failure path and match it; the pinned behavior is "runner never created + run FAILED with TESTCONTAINERS_INFRA_FAILED."

**Placeholder scan:** Task 4 (flag fan-out) and Task 6 (fail-fast channel) contain explicit "Grep the existing pattern / match the existing mechanism" instructions rather than full code for every one of ~24 mirror sites — this is deliberate: they mirror an existing, concrete codebase pattern (`openspecEnabled`) and an existing dispatch-failure path, and enumerate every exact file to touch. Not vague TODOs. All novel logic (gateway, DindSidecarService, sweep) has complete code.

**Type consistency:** `DindHandle(networkName, sidecarContainerId, runnerEnv)` is identical across Task 5 (defined) and Task 6 (consumed). `ContainerState.healthStatus()` defined in Task 1, read in Task 5. `createNetwork`/`removeNetwork`/`listNetworksByLabel` signatures consistent across Tasks 1/7. Network name `deliveryline-net-<rex>`, label `deliveryline.dind=<rex>`, alias `dind` consistent across Tasks 5/6/7/8. `TESTCONTAINERS_INFRA_FAILED` value `testcontainers_infra_failed` consistent across Tasks 2/6.
