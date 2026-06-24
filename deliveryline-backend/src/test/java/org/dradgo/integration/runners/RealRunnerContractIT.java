package org.dradgo.integration.runners;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.runner.DockerRunnerAdapter;
import org.dradgo.adapters.runner.EnabledIfDockerAvailable;
import org.dradgo.adapters.runner.MockRunnerScenario;
import org.dradgo.adapters.runner.MockRunnerScenarioRegistry;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerSecretScanService;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.dradgo.application.runner.spi.WorkspaceLayout;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.FailureCategory;
import org.dradgo.domain.registry.RunnerExecutionStatus;
import org.dradgo.domain.registry.RunnerKind;
import org.dradgo.domain.registry.RunnerStage;
import org.dradgo.runnercontracts.RunnerContractValidator;
import org.dradgo.runnercontracts.RunnerContractValidator.ValidationTarget;
import org.dradgo.runnercontracts.ValidationContext;
import org.dradgo.runnercontracts.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;

/**
 * Story 3.8 — Real Docker Runner Contract Integration Test.
 *
 * <p>Broker-driven end-to-end contract test that runs the <b>real</b> {@code
 * deliveryline/codex-runner} + {@code deliveryline/claude-runner} images (built once in
 * {@code @BeforeAll} with the deterministic mock CLI, {@code --build-arg INSTALL_*_CLI=false})
 * against the production {@link RunnerBroker} → {@link DockerRunnerAdapter} → persistence /
 * log-capture / secret-scan pipeline + the {@link RunnerContractValidator} (story 1.6). Any drift
 * between {@code deliveryline-runner-contracts}, the adapter, and the actual runner image surfaces
 * here, before a pilot.
 *
 * <h2>Why direct-launch + broker-reconcile (Decision D7 / OQ-3 — no product seam)</h2>
 *
 * The runner entrypoint reads {@code --simulate-failure=} and {@code --stage=} ONLY from container
 * CLI args ({@code $@}); {@link org.dradgo.adapters.runner.docker.CreateContainerSpec} carries no
 * cmd/args field, so {@code DockerRunnerAdapter.dispatch} cannot pass them. Rather than widen the
 * production adapter with a test-only arg seam, this IT reuses the proven {@code
 * BrokerDrivenDockerLifecycleITSupport} (story 3.2a) pattern: it seeds the {@code workflow_runs} +
 * {@code runner_executions} rows, launches the real image as a labelled container (binding the
 * per-rex workspace + injecting the {@code --stage} / {@code --simulate-failure} args and the
 * {@code DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true} gate), primes the adapter's in-process
 * handle via the production label-probe {@code recoverHandle(rex)}, then drives the real broker
 * reconcile entry points ({@code pollActiveExecutions()} / {@code scanForTimeouts()}). The broker's
 * harvest → contract-validate → persist → emit-events path is exercised exactly as in production;
 * only the container-launch step (already a test concern in the 3.2a support) is test-owned. The
 * runner images, entrypoints, and mock CLIs are <b>consumed unchanged</b> (Decision D1).
 *
 * <h2>Tiering (Decision D2 — critical)</h2>
 *
 * Annotated {@code @Tag("docker-runner-it")} (keeps it out of the default Failsafe {@code verify}
 * tier — {@code deliveryline-backend/pom.xml} excludes only that tag, so a test tagged solely
 * {@code real-runner-contract} would run on every PR) <b>and</b>
 * {@code @Tag("real-runner-contract")} (the semantic tag the future story-3.34 {@code
 * runner-contract-real} CI job selects on) <b>and</b> {@code @EnabledIfDockerAvailable} (graceful
 * skip, not failure, on Docker-less machines). Run locally with {@code mvn -Pdocker-runner-it -pl
 * deliveryline-backend -am verify -Dit.test=RealRunnerContractIT} (the {@code docker-runner-it}
 * profile re-includes the tag; {@code -am} keeps the freshest {@code deliveryline-runner-contracts}
 * jar on the classpath).
 *
 * <h2>Deliberate scope boundaries</h2>
 *
 * <ul>
 *   <li>Decision D5 — only the real-image <b>timeout</b> lifecycle is re-proven here. Heartbeat
 *       staleness, JVM-restart recovery and dangling-container cleanup are image-agnostic and
 *       already covered against {@code alpine:3.20} by the 3.2a {@code DockerRunnerLifecycle*IT}
 *       suite; re-running them on the heavy real images adds cost without new signal.
 *   <li>Decisions D1/D8/D9/D10 — the {@code runner-image-build} + {@code runner-contract-real}
 *       GitHub Actions jobs and the foundation-gate widening are story 3.34's deliverables and are
 *       NOT wired here (see {@code _bmad-output/implementation-artifacts/deferred-work.md}).
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "runners.docker"})
@Tag("docker-runner-it")
@Tag("real-runner-contract")
@EnabledIfDockerAvailable
class RealRunnerContractIT {

  private static final Logger log = LoggerFactory.getLogger(RealRunnerContractIT.class);

  private static final String CODEX_IMAGE = "deliveryline/codex-runner:real-contract-it";
  private static final String CLAUDE_IMAGE = "deliveryline/claude-runner:real-contract-it";

  // Mock provider keys. They live in BOTH the Spring Environment (via @DynamicPropertySource, so
  // the
  // backend RunnerSecretScanService can re-resolve the value it scans the workspace for) AND, for
  // the dedicated secrets scenario, in the container env. The runner never writes them to the
  // workspace, so the post-execution scan must find zero leaks.
  private static final String CODEX_SECRET_VALUE = "sk-codex-REALCONTRACT-not-a-real-key-001";
  private static final String ANTHROPIC_SECRET_VALUE = "sk-ant-REALCONTRACT-not-a-real-key-002";
  private static final String CLAUDE_OAUTH_VALUE = "oauth-REALCONTRACT-not-a-real-token-003";

  private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile("^([A-Za-z]):[/\\\\](.*)$");

  @DynamicPropertySource
  static void runnerSecrets(DynamicPropertyRegistry registry) {
    // RunnerSecretsService resolves provider keys from the Spring Environment by name. Supplying
    // them here lets the broker's post-execution secret scan (RunnerSecretScanService, run inside
    // onResult on every happy completion) resolve a key to scan for instead of throwing
    // DOCTOR_RUNNER_SECRET_MISSING. kindForStage() routes both stages to the codex default kind
    // today, but we register every kind's primary key for forward-compat.
    registry.add("CODEX_API_KEY", () -> CODEX_SECRET_VALUE);
    registry.add("ANTHROPIC_API_KEY", () -> ANTHROPIC_SECRET_VALUE);
    registry.add("CLAUDE_CODE_OAUTH_TOKEN", () -> CLAUDE_OAUTH_VALUE);
  }

  @Autowired private RunnerBroker broker;
  @Autowired private DockerRunnerAdapter adapter;
  @Autowired private RunnerWorkspaceStore workspaceStore;
  @Autowired private RunnerContractValidator contractValidator;
  @Autowired private RunnerSecretScanService secretScanService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Value("${deliveryline.home}")
  private String deliverylineHome;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static DockerClient docker;
  private static Path repoRoot;
  private static Path bundleFixture;

  private final List<String> containersToCleanup = new ArrayList<>();
  private final List<String> workspacesToCleanup = new ArrayList<>();

  // ---------------------------------------------------------------------------
  // Image build (once) + per-test wiring
  // ---------------------------------------------------------------------------

  @BeforeAll
  static void buildRealImages() throws Exception {
    docker = DockerClientFactory.instance().client();
    repoRoot = locateRepoRoot();
    bundleFixture =
        repoRoot.resolve(
            "deliveryline-runner-contracts/src/test/resources/fixtures/valid/"
                + "context-bundle.v1.valid.json");
    assertThat(Files.exists(bundleFixture))
        .as("foundation context-bundle fixture present")
        .isTrue();

    // AC1/AC3 — build both real images once, baking the deterministic MOCK CLI (no network / no
    // real
    // agent API). Same ProcessBuilder invocation the conformance ITs use (docker-java's tarball
    // handling of an out-of-cwd Dockerfile is brittle; only the container RUNS go through
    // docker-java).
    buildImage("runners/codex/Dockerfile", "INSTALL_CODEX_CLI", CODEX_IMAGE);
    buildImage("runners/claude/Dockerfile", "INSTALL_CLAUDE_CLI", CLAUDE_IMAGE);
  }

  private static void buildImage(String dockerfile, String installArg, String tag)
      throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder(
            "docker",
            "build",
            "-f",
            dockerfile,
            "--build-arg",
            installArg + "=false",
            "--build-arg",
            "IMAGE_VERSION=real-contract-it",
            "-t",
            tag,
            ".");
    builder.directory(repoRoot.toFile());
    builder.redirectErrorStream(true);
    Process process = builder.start();
    String buildOutput =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    // Bound the build so a stalled daemon / hung base-layer pull fails the tier with a diagnostic
    // instead of blocking @BeforeAll indefinitely.
    if (!process.waitFor(10, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new IllegalStateException(
          String.format(
              Locale.ROOT,
              "docker build %s did not finish within 10m; output=%n%s",
              tag,
              buildOutput));
    }
    int buildExit = process.exitValue();
    assertThat(buildExit).as("docker build %s exit code; output=%n%s", tag, buildOutput).isZero();
  }

  @BeforeEach
  void setUp() {
    // Raw client against the same daemon the production DockerClient bean talks to — used only to
    // launch / inspect the test containers; the system-under-test reaches the engine via the
    // autowired adapter + gateway beans.
    docker = DockerClientFactory.instance().client();
  }

  @AfterEach
  void tearDown() {
    for (String id : containersToCleanup) {
      try {
        docker.removeContainerCmd(id).withForce(true).exec();
      } catch (RuntimeException e) {
        log.warn("teardown failed to remove container id={} cause={}", id, e.toString());
      }
    }
    for (String rex : workspacesToCleanup) {
      try {
        workspaceStore.deleteWorkspace(rex);
      } catch (RuntimeException e) {
        log.warn("teardown failed to delete workspace rex={} cause={}", rex, e.toString());
      }
    }
    containersToCleanup.clear();
    workspacesToCleanup.clear();
    // AC11 — FK-ordered cleanup (append-only children first). @SpringBootTest is not
    // @Transactional, so the broker's per-item commits are real and must be torn down explicitly.
    jdbcTemplate.update("delete from recovery_actions");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from artifact_operations");
    // Story 3e-1 — the spec-stage runner now emits a clarifications fence on the happy path (the
    // mock resolves the stage from the --stage arg the entrypoint passes), so a clarification row
    // referencing the spec artifact is created. Delete it before its artifacts/workflow_runs
    // parents (fk_clarifications_artifacts) or the artifacts delete below violates the FK.
    jdbcTemplate.update("delete from clarifications");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from workflow_runs");
  }

  // ===========================================================================
  // AC2/AC3/AC4 — happy-path × artifact-variant coverage (Codex + Claude)
  // ===========================================================================

  @ParameterizedTest(name = "spec happy-path — {0}")
  // Story 3d-3 — MANUAL launches no container (parks in WaitingForManualExecution), so it is
  // out of scope for the real-image container contract; exclude it from the launch-based cases.
  @EnumSource(value = RunnerKind.class, names = "MANUAL", mode = EnumSource.Mode.EXCLUDE)
  void happyPathProducesValidSpecResult(RunnerKind kind) throws Exception {
    Scenario s =
        runRealRunner(
            kind, RunnerStage.INVESTIGATION, "Investigating", "spec-investigation", null, false);
    assertThat(adapter.recoverHandle(s.rex()))
        .as(
            describe(s)
                + " — recoverHandle must prime the in-process handle from the container label")
        .isPresent();
    broker.pollActiveExecutions();

    assertTerminal(s, "completed", null, "runner.completed");
    assertResultArtifactType(s, "spec");
  }

  @ParameterizedTest(name = "implementationPlan happy-path — {0}")
  // Story 3d-3 — MANUAL launches no container (parks in WaitingForManualExecution), so it is
  // out of scope for the real-image container contract; exclude it from the launch-based cases.
  @EnumSource(value = RunnerKind.class, names = "MANUAL", mode = EnumSource.Mode.EXCLUDE)
  void happyPathProducesValidImplementationPlanResult(RunnerKind kind) throws Exception {
    Scenario s =
        runRealRunner(kind, RunnerStage.EXECUTION, "Executing", "implementation-plan", null, false);
    assertThat(adapter.recoverHandle(s.rex()))
        .as(
            describe(s)
                + " — recoverHandle must prime the in-process handle from the container label")
        .isPresent();
    broker.pollActiveExecutions();

    assertTerminal(s, "completed", null, "runner.completed");
    assertResultArtifactType(s, "implementationPlan");
  }

  @ParameterizedTest(name = "prOutput happy-path — {0}")
  // Story 3d-3 — MANUAL launches no container (parks in WaitingForManualExecution), so it is
  // out of scope for the real-image container contract; exclude it from the launch-based cases.
  @EnumSource(value = RunnerKind.class, names = "MANUAL", mode = EnumSource.Mode.EXCLUDE)
  void happyPathProducesValidPrOutputResult(RunnerKind kind) throws Exception {
    Scenario s = runRealRunner(kind, RunnerStage.EXECUTION, "Executing", "pr-output", null, false);
    assertThat(adapter.recoverHandle(s.rex()))
        .as(
            describe(s)
                + " — recoverHandle must prime the in-process handle from the container label")
        .isPresent();
    broker.pollActiveExecutions();

    assertTerminal(s, "completed", null, "runner.completed");
    assertResultArtifactType(s, "prOutput");
  }

  // ===========================================================================
  // AC2/AC4/AC8 — failure-injection coverage (Codex + Claude)
  // ===========================================================================

  @ParameterizedTest(name = "crash — {0}")
  // Story 3d-3 — MANUAL launches no container (parks in WaitingForManualExecution), so it is
  // out of scope for the real-image container contract; exclude it from the launch-based cases.
  @EnumSource(value = RunnerKind.class, names = "MANUAL", mode = EnumSource.Mode.EXCLUDE)
  void simulateCrashLandsRunnerCrash(RunnerKind kind) throws Exception {
    Scenario s = runRealRunner(kind, RunnerStage.EXECUTION, "Executing", null, "crash", false);
    assertThat(adapter.recoverHandle(s.rex()))
        .as(
            describe(s)
                + " — recoverHandle must prime the in-process handle from the container label")
        .isPresent();
    broker.pollActiveExecutions();

    assertTerminal(s, "failed", "runner_crash", "runner.failed");
    assertThat(adapter.tryReadResult(s.rex()))
        .as(describe(s) + " — crash must produce NO result file")
        .isEmpty();
  }

  @ParameterizedTest(name = "contract_violation — {0}")
  // Story 3d-3 — MANUAL launches no container (parks in WaitingForManualExecution), so it is
  // out of scope for the real-image container contract; exclude it from the launch-based cases.
  @EnumSource(value = RunnerKind.class, names = "MANUAL", mode = EnumSource.Mode.EXCLUDE)
  void simulateContractViolationLandsRunnerContractViolation(RunnerKind kind) throws Exception {
    Scenario s =
        runRealRunner(kind, RunnerStage.EXECUTION, "Executing", null, "contract_violation", false);
    assertThat(adapter.recoverHandle(s.rex()))
        .as(
            describe(s)
                + " — recoverHandle must prime the in-process handle from the container label")
        .isPresent();
    broker.pollActiveExecutions();

    assertTerminal(s, "failed", "runner_contract_violation", "runner.failed");
    // A result file IS produced (schemaVersion:2) but the RunnerContractValidator rejects it.
    byte[] result =
        adapter
            .tryReadResult(s.rex())
            .orElseGet(
                () -> {
                  throw new AssertionError(
                      describe(s) + " — contract_violation must still produce a (invalid) result");
                });
    ValidationResult validation =
        contractValidator.validate(
            ValidationTarget.RUNNER_RESULT, result, validationContext(s.rex()));
    assertThat(validation.valid())
        .as(describe(s) + " — the schemaVersion:2 result must be rejected by the validator")
        .isFalse();
  }

  @ParameterizedTest(name = "timeout — {0}")
  // Story 3d-3 — MANUAL launches no container (parks in WaitingForManualExecution), so it is
  // out of scope for the real-image container contract; exclude it from the launch-based cases.
  @EnumSource(value = RunnerKind.class, names = "MANUAL", mode = EnumSource.Mode.EXCLUDE)
  void simulateTimeoutLandsRunnerTimeoutAndKillsRealImage(RunnerKind kind) throws Exception {
    // Deadline already elapsed (timeoutSecondsFromNow = -5); the real entrypoint is sleeping
    // 86400s.
    Scenario s = runRealRunner(kind, RunnerStage.EXECUTION, "Executing", null, "timeout", false);
    assertThat(isRunning(s.containerId()))
        .as(describe(s) + " — simulate=timeout container must be running before the scan")
        .isTrue();

    // Prime the broker's in-process handle via the production recovery probe (label filter), then
    // run the timeout scan — exactly the 3.2a DockerRunnerLifecycleTimeoutIT sequence, against the
    // real image instead of alpine.
    assertThat(adapter.recoverHandle(s.rex())).isPresent();
    assertThat(adapter.findContainerIdFor(s.rex())).contains(s.containerId());

    int flipped = broker.scanForTimeouts();

    assertThat(flipped).as(describe(s) + " — the single past-deadline row must flip").isEqualTo(1);
    assertTerminal(s, "timed_out", "runner_timeout", "runner.timeout");
    awaitNotRunning(s.containerId(), Duration.ofSeconds(20));
    assertThat(isRunning(s.containerId()))
        .as(describe(s) + " — the real-image sleep container must be force-exited by the broker")
        .isFalse();
  }

  // ===========================================================================
  // AC5 — mock-vs-real parity
  //
  // The terminal status / failure_category / lifecycle events asserted by the real scenarios above
  // are produced by the SHARED broker + persistence pipeline — identical for either RunnerAdapter
  // by
  // construction. The only adapter-specific surface is the Behaviour→outcome mapping the adapter
  // declares. The MockRunnerAdapter stack is @Profile("!runners.docker") (absent from this docker
  // context) and driving its full dispatch→poll needs fixture/scratch wiring; but its source of
  // truth — the per-Behaviour FailureCategory declared by MockRunnerScenarioRegistry — is a plain
  // bean with a public no-arg ctor and no Docker dependency. We construct it directly and assert
  // the
  // mapping against PRODUCTION declarations (not test literals): this guard reds if the mock's
  // declared Behaviour / FailureCategory drifts from what the real scenarios above assert, which is
  // the machine-checked half of parity. (The real half is assertTerminal on the live row.)
  // ===========================================================================

  @ParameterizedTest(name = "parity mapping — {0}")
  @CsvSource({
    // mock Behaviour, registry scenario, terminal status, failure_category, lifecycle event_type
    "HAPPY,             happy-pr-output,    completed, ,                          runner.completed",
    "CRASH,             crash,              failed,    runner_crash,              runner.failed",
    "CONTRACT_VIOLATION,contract-violation, failed,    runner_contract_violation, runner.failed",
    "TIMEOUT,           timeout,            timed_out, runner_timeout,            runner.timeout",
  })
  void mockBehaviourMapsToRealOutcome(
      String behaviour,
      String scenarioName,
      String status,
      String failureCategory,
      String eventType) {
    MockRunnerScenario scenario = new MockRunnerScenarioRegistry().require(scenarioName);

    // The Behaviour declared by the production registry must match the one the real scenarios
    // drive.
    assertThat(scenario.behaviour().name())
        .as("production registry scenario '%s' Behaviour", scenarioName)
        .isEqualTo(behaviour);

    // The terminal status string must be a real RunnerExecutionStatus — couples the CSV to the
    // production enum rather than a free-text literal.
    assertThat(
            java.util.Arrays.stream(RunnerExecutionStatus.values())
                .map(RunnerExecutionStatus::value))
        .as("terminal status '%s' must be a production RunnerExecutionStatus", status)
        .contains(status);
    assertThat(eventType).as("%s lifecycle event", behaviour).startsWith("runner.");

    // The failure_category the real scenario asserts on the runner_executions row must equal the
    // category the production MockRunnerScenarioRegistry DECLARES for the matched Behaviour. Drift
    // on
    // either side now reds this test — the parity guarantee the previous self-referential check
    // lacked.
    FailureCategory declared = scenario.expectedFailureCategory();
    if ("HAPPY".equals(behaviour)) {
      assertThat(declared).as("HAPPY declares no failure_category in the registry").isNull();
      assertThat(failureCategory).as("HAPPY row carries no failure_category").isNullOrEmpty();
    } else {
      assertThat(declared)
          .as("registry must declare a failure_category for Behaviour %s", behaviour)
          .isNotNull();
      assertThat(declared.value())
          .as("mock-declared failure_category must equal the real outcome for %s", behaviour)
          .isEqualTo(failureCategory);
    }
  }

  // ===========================================================================
  // AC6 — secrets pipeline holds end-to-end
  // ===========================================================================

  @Test
  void injectedProviderSecretIsNotLeakedToTheWorkspace() throws Exception {
    // Inject the real codex provider key into the container (matches the Spring-Environment value
    // the scan re-resolves). The mock CLI consumes auth from env and never writes it to the
    // workspace, so the post-execution scan must find zero leaks and the run completes (NOT
    // RUNNER_SECRET_LEAK).
    Scenario s =
        runRealRunner(
            RunnerKind.CODEX,
            RunnerStage.EXECUTION,
            "Executing",
            "pr-output",
            null,
            /* injectCodexSecret= */ true);
    assertThat(adapter.recoverHandle(s.rex()))
        .as(
            describe(s)
                + " — recoverHandle must prime the in-process handle from the container label")
        .isPresent();
    broker.pollActiveExecutions();

    assertTerminal(s, "completed", null, "runner.completed");

    RunnerSecretScanService.ScanOutcome scan =
        secretScanService.scanWorkspace(
            s.rex(), RunnerKind.CODEX, RunnerStage.EXECUTION, s.runId());
    assertThat(scan.leakDetected())
        .as(describe(s) + " — post-execution workspace scan must find zero provider-key leaks")
        .isFalse();
  }

  // ===========================================================================
  // AC7 — logs captured + redacted
  // ===========================================================================

  @Test
  void runnerLogsAreCapturedAndAuthHeaderLeakIsRedactedBeforePersistence() throws Exception {
    // The genuinely-secret value is this unique random token; the surrounding "Authorization:
    // Bearer"
    // is a generic keyword the redactor may legitimately preserve. Assert the TOKEN is gone
    // (below),
    // not the keyword-prefixed string, so a partial-redaction that leaves the token can't slip
    // past.
    String secretToken = "redaction-sentinel-" + System.nanoTime();
    String authHeaderLine = "Authorization: Bearer " + secretToken;
    Scenario s =
        runRealRunner(
            RunnerKind.CODEX, RunnerStage.EXECUTION, "Executing", "pr-output", null, false);

    // Inject an auth-header leak into the runner's captured stdout BEFORE the broker harvests it.
    // The entrypoint's own diagnostics go to the CONTAINER stderr stream, not this mounted file
    // (memory: runner-entrypoint-logs-to-container-stderr), so seeding the mounted runner.stdout is
    // the sanctioned way to drive a known-leak through the capture+redaction second pass.
    Path runnerStdout = s.layout().logs().resolve("runner.stdout");
    Files.writeString(
        runnerStdout,
        "\n" + authHeaderLine + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);

    // recoverHandle's production poll classifies the exited container and runs the log-capture +
    // redaction second pass (RunnerLogCaptureService), recording redaction metadata onto the row.
    // We assert the capture/redaction pipeline directly here and deliberately STOP before the full
    // broker harvest: a leaked secret left in the raw workspace additionally trips the AC6
    // workspace secret-scan (-> runner_secret_leak), which is the correct fail-closed behaviour but
    // a separate concern from "logs are redacted before persistence".
    assertThat(adapter.recoverHandle(s.rex()))
        .as(describe(s) + " — recoverHandle must classify the exited container + capture its logs")
        .isPresent();

    Integer redactionCount =
        jdbcTemplate.queryForObject(
            "select redaction_count from runner_executions where public_id = ?",
            Integer.class,
            s.rex());
    assertThat(redactionCount)
        .as(describe(s) + " — the seeded auth-header leak must increment redaction_count")
        .isNotNull()
        .isGreaterThan(0);

    String classification =
        jdbcTemplate.queryForObject(
            "select raw_output_classification from runner_executions where public_id = ?",
            String.class,
            s.rex());
    assertThat(classification)
        .as(describe(s) + " — captured logs must carry a DataClassification")
        .isNotBlank();

    // The persisted, redacted log under {DELIVERYLINE_HOME}/runner-logs/{rex}/ must NOT echo the
    // sentinel.
    Path persistedStdout =
        Path.of(deliverylineHome).resolve("runner-logs").resolve(s.rex()).resolve("runner.stdout");
    assertThat(Files.exists(persistedStdout))
        .as(describe(s) + " — redacted runner.stdout must be persisted under runner-logs/{rex}/")
        .isTrue();
    assertThat(Files.readString(persistedStdout, StandardCharsets.UTF_8))
        .as(describe(s) + " — the auth-header secret token must be redacted before persistence")
        .doesNotContain(secretToken);
  }

  // ===========================================================================
  // Core scenario harness
  // ===========================================================================

  /** One launched real-image scenario: the seeded run/rex, the running container, its workspace. */
  private record Scenario(
      RunnerKind kind,
      RunnerStage stage,
      String runId,
      String rex,
      String containerId,
      WorkspaceLayout layout) {}

  /**
   * Seed a {@code workflow_runs} + running {@code runner_executions} row, prepare the per-rex
   * workspace with the foundation fixture (rebound to the seeded ids), then launch the real image
   * as a labelled container bound to that workspace. For {@code simulateMode == "timeout"} the
   * deadline is seeded in the past and the caller does NOT wait for exit; otherwise this blocks
   * until the container exits.
   */
  private Scenario runRealRunner(
      RunnerKind kind,
      RunnerStage stage,
      String workflowState,
      String stageArg,
      String simulateMode,
      boolean injectCodexSecret)
      throws Exception {
    // Exhaustive switch: a future RunnerKind constant is a compile error here, not a silent
    // fall-through to the Claude image with mismatched labels.
    String image =
        switch (kind) {
          case CODEX -> CODEX_IMAGE;
          case CLAUDE -> CLAUDE_IMAGE;
          case MANUAL ->
              throw new IllegalArgumentException("MANUAL runner kind launches no container image");
        };
    boolean isTimeout = "timeout".equals(simulateMode);

    String runId = seedWorkflowRun(workflowState);
    String rex =
        seedRunningRunner(
            runId, stage.value(), 30, /* timeoutSecondsFromNow= */ isTimeout ? -5 : 600);

    WorkspaceLayout layout = workspaceStore.prepare(rex);
    workspacesToCleanup.add(rex);
    // AC3 — reuse the foundation fixture as the input bundle, rebinding only the two ids to the
    // live run/rex so the broker's result-validation + artifact-ingestion (which key off the result
    // document's runnerExecutionId, sourced from the bundle) align with the seeded row.
    Files.write(layout.input().resolve("context-bundle.v1.json"), rebindBundle(runId, rex));
    makeRunnerReadable(layout.input());
    makeWorldWritable(layout.output());
    makeWorldWritable(layout.logs());

    List<String> env = new ArrayList<>();
    env.add("DELIVERYLINE_RUNNER_STAGE=" + stage.value());
    if (injectCodexSecret) {
      env.add("CODEX_API_KEY=" + CODEX_SECRET_VALUE);
    } else {
      // The mock CLI needs no real credential; skip-auth keeps non-secret scenarios independent of
      // the per-kind auth-var wiring.
      env.add("DELIVERYLINE_RUNNER_SKIP_AUTH=true");
    }
    if (simulateMode != null) {
      env.add("DELIVERYLINE_RUNNER_ALLOW_SIMULATE_FAILURE=true");
    }

    List<String> cmd = new ArrayList<>();
    if (stageArg != null) {
      cmd.add("--stage=" + stageArg);
    }
    if (simulateMode != null) {
      cmd.add("--simulate-failure=" + simulateMode);
    }

    var create =
        docker
            .createContainerCmd(image)
            .withHostConfig(
                HostConfig.newHostConfig()
                    .withBinds(
                        new Bind(
                            dockerHostPath(layout.input()),
                            new Volume("/workspace/input"),
                            AccessMode.ro),
                        new Bind(
                            dockerHostPath(layout.output()),
                            new Volume("/workspace/output"),
                            AccessMode.rw),
                        new Bind(
                            dockerHostPath(layout.logs()),
                            new Volume("/workspace/logs"),
                            AccessMode.rw))
                    .withNetworkMode("none"))
            .withEnv(env)
            .withLabels(containerLabels(rex, runId, kind, stage));
    if (!cmd.isEmpty()) {
      create.withCmd(cmd);
    }
    String containerId = create.exec().getId();
    containersToCleanup.add(containerId);
    docker.startContainerCmd(containerId).exec();

    if (!isTimeout) {
      docker
          .waitContainerCmd(containerId)
          .exec(new WaitContainerResultCallback())
          .awaitStatusCode();
    }
    return new Scenario(kind, stage, runId, rex, containerId, layout);
  }

  private byte[] rebindBundle(String runId, String rex) throws Exception {
    ObjectNode bundle = (ObjectNode) objectMapper.readTree(Files.readAllBytes(bundleFixture));
    bundle.put("workflowRunId", runId);
    bundle.put("runnerExecutionId", rex);
    return objectMapper.writeValueAsBytes(bundle);
  }

  private static Map<String, String> containerLabels(
      String rex, String runId, RunnerKind kind, RunnerStage stage) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("deliveryline.runnerExecutionId", rex);
    labels.put("deliveryline.workflowRunId", runId);
    labels.put("deliveryline.runnerKind", kind.value());
    labels.put("deliveryline.stage", stage.value());
    labels.put("deliveryline.dispatchedAt", OffsetDateTime.now().toString());
    return labels;
  }

  // ---------------------------------------------------------------------------
  // Assertions (AC12 — rich, CI-actionable descriptions)
  // ---------------------------------------------------------------------------

  private void assertTerminal(
      Scenario s, String expectedStatus, String expectedFailureCategory, String expectedEventType) {
    String description = describe(s);
    assertThat(runnerStatus(s.rex()))
        .as(description + " — terminal runner_executions.status")
        .isEqualTo(expectedStatus);
    assertThat(failureCategory(s.rex()))
        .as(description + " — runner_executions.failure_category")
        .isEqualTo(expectedFailureCategory);
    assertThat(eventCount(s.runId(), expectedEventType))
        .as(description + " — exactly one " + expectedEventType + " lifecycle event")
        .isEqualTo(1);
  }

  private void assertResultArtifactType(Scenario s, String expectedArtifactType) {
    byte[] result =
        adapter
            .tryReadResult(s.rex())
            .orElseGet(
                () -> {
                  throw new AssertionError(
                      describe(s) + " — happy path must produce a result file");
                });
    ValidationResult validation =
        contractValidator.validate(
            ValidationTarget.RUNNER_RESULT, result, validationContext(s.rex()));
    assertThat(validation.valid())
        .as(describe(s) + " — result must be schema-valid; errors=%s", validation.errors())
        .isTrue();
    assertThat(new String(result, StandardCharsets.UTF_8))
        .as(describe(s) + " — artifactType must match the dispatched stage")
        .contains("\"artifactType\": \"" + expectedArtifactType + "\"");
  }

  /** AC12 — a CI-log-only failure description: kind, stage, ids, status, category, events. */
  private String describe(Scenario s) {
    String events;
    try {
      events =
          jdbcTemplate
              .queryForList(
                  "select event_type from workflow_events"
                      + " where workflow_run_id = (select id from workflow_runs where public_id = ?)"
                      + " order by created_at",
                  String.class,
                  s.runId())
              .toString();
    } catch (RuntimeException e) {
      events = "<unavailable: " + e + ">";
    }
    Path logsDir = Path.of(deliverylineHome).resolve("runner-logs").resolve(s.rex());
    return String.format(
        Locale.ROOT,
        "[%s %s run=%s rex=%s status=%s failureCategory=%s events=%s redactedLogs=%s]",
        s.kind().value(),
        s.stage().value(),
        s.runId(),
        s.rex(),
        safeRunnerStatus(s.rex()),
        safeFailureCategory(s.rex()),
        events,
        logsDir);
  }

  private ValidationContext validationContext(String rex) {
    // Only the KNOWN set — adding rex to the observed set would trip the validator's
    // DUPLICATE_RUNNER_EXECUTION_ID semantic check (observed = ids already seen in other results).
    return ValidationContext.builder()
        .addKnownRunnerExecutionId(rex)
        .maxPayloadBytes(64 * 1024)
        .build();
  }

  // ---------------------------------------------------------------------------
  // DB seed / query helpers (mirrors BrokerDrivenDockerLifecycleITSupport)
  // ---------------------------------------------------------------------------

  private String seedWorkflowRun(String currentState) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", runId, currentState);
    return runId;
  }

  private String seedRunningRunner(
      String runId, String stageValue, long lastActivitySecondsAgo, long timeoutSecondsFromNow) {
    String rex = PublicIdPrefixes.RUNNER_EXECUTION.next();
    jdbcTemplate.update(
        """
        insert into runner_executions
          (public_id, workflow_run_id, stage, status, context_bundle_version,
           last_activity_at, timeout_at, created_at)
        values (?, (select id from workflow_runs where public_id = ?), ?, 'running', 1,
                now() - (interval '1 second' * ?),
                now() + (interval '1 second' * ?),
                now() - (interval '1 second' * ?))
        """,
        rex,
        runId,
        stageValue,
        lastActivitySecondsAgo,
        timeoutSecondsFromNow,
        lastActivitySecondsAgo);
    return rex;
  }

  private String runnerStatus(String rex) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where public_id = ?", String.class, rex);
  }

  private String failureCategory(String rex) {
    return jdbcTemplate.queryForObject(
        "select failure_category from runner_executions where public_id = ?", String.class, rex);
  }

  private String safeRunnerStatus(String rex) {
    try {
      return runnerStatus(rex);
    } catch (RuntimeException e) {
      return "<unavailable>";
    }
  }

  private String safeFailureCategory(String rex) {
    try {
      return failureCategory(rex);
    } catch (RuntimeException e) {
      return "<unavailable>";
    }
  }

  private int eventCount(String runId, String eventType) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from workflow_events"
                + " where event_type = ?"
                + " and workflow_run_id = (select id from workflow_runs where public_id = ?)",
            Integer.class,
            eventType,
            runId);
    return count == null ? 0 : count;
  }

  // ---------------------------------------------------------------------------
  // Docker helpers (mirrors CodexRunnerImageConformanceIT + the lifecycle support)
  // ---------------------------------------------------------------------------

  private boolean isRunning(String containerId) {
    return Boolean.TRUE.equals(
        docker.inspectContainerCmd(containerId).exec().getState().getRunning());
  }

  private void awaitNotRunning(String containerId, Duration timeout) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (!isRunning(containerId)) {
        return;
      }
      try {
        Thread.sleep(200L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    throw new AssertionError(
        "container " + containerId + " still running after " + timeout + " — awaitNotRunning");
  }

  private static void makeWorldWritable(Path dir) throws java.io.IOException {
    try {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem (Windows): Docker Desktop bind-mount uid mapping is permissive there.
      // A real IOException on a POSIX host (e.g. a chmod failure on Linux CI) is NOT swallowed — it
      // surfaces so a broken bind-mount setup fails loudly instead of as an opaque container
      // failure.
    }
  }

  private static void makeRunnerReadable(Path dir) throws java.io.IOException {
    try {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem (Windows): Docker Desktop bind-mount uid mapping is permissive there.
      // A real IOException on a POSIX host (e.g. a chmod failure on Linux CI) is NOT swallowed — it
      // surfaces so a broken bind-mount setup fails loudly instead of as an opaque container
      // failure.
    }
  }

  /** Format a host path for a docker-java {@link Bind} (Windows drive → {@code /c/...}). */
  private static String dockerHostPath(Path path) {
    String normalized = path.toAbsolutePath().normalize().toString();
    Matcher matcher = WINDOWS_DRIVE_PATH.matcher(normalized);
    if (!matcher.matches()) {
      return normalized;
    }
    return "/"
        + matcher.group(1).toLowerCase(Locale.ROOT)
        + "/"
        + matcher.group(2).replace('\\', '/');
  }

  private static Path locateRepoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      if (Files.exists(dir.resolve("runners/codex/Dockerfile"))) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "could not locate repo root (runners/codex/Dockerfile) from "
            + Path.of("").toAbsolutePath());
  }
}
