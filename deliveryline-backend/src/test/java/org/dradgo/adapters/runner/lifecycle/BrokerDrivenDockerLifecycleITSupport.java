package org.dradgo.adapters.runner.lifecycle;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.adapters.files.LocalRunnerWorkspaceStore;
import org.dradgo.adapters.runner.DockerRunnerAdapter;
import org.dradgo.adapters.runner.EnabledIfDockerAvailable;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;

/**
 * Story 3.2a review-hardening base for the <b>broker-driven</b> docker-runner lifecycle ITs
 * (timeout / heartbeat / recovery). Closes the three open {@code [Review][Patch]} findings that the
 * original {@link DockerLifecycleITSupport}-based scaffolds tested the {@link DockerRunnerAdapter}
 * in isolation and never exercised the {@link RunnerBroker}'s timeout-enforcement / heartbeat
 * activity-extension / restart-recovery paths against a real {@code runner_executions} row.
 *
 * <p>Unlike {@link DockerLifecycleITSupport} (a hand-wired adapter over a {@code @TempDir}), this
 * base boots the full application under {@code runners.docker} with Testcontainers Postgres so the
 * autowired {@link RunnerBroker}, {@link DockerRunnerAdapter}, and {@link
 * LocalRunnerWorkspaceStore} are the production beans. Tests seed a real DB row + launch a real
 * labeled container, then drive a broker entry point and assert the DB transition + the dedicated
 * lifecycle event.
 *
 * <p><b>WSL2 gate (Trap T15):</b> these ITs exercise real {@code docker stop}/{@code kill} signal
 * behavior, which differs between Windows Docker Desktop and Linux. They MUST be run on WSL2 Ubuntu
 * before pushing. The tier also needs {@code alpine:3.20} pre-pulled ({@code docker pull
 * alpine:3.20}) — the adapter uses a raw DockerClient that does not auto-pull.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock", "runners.docker"})
@Tag("docker-runner-it")
@EnabledIfDockerAvailable
abstract class BrokerDrivenDockerLifecycleITSupport {

  private static final Logger log =
      LoggerFactory.getLogger(BrokerDrivenDockerLifecycleITSupport.class);

  protected static final String TEST_IMAGE = "alpine:3.20";

  @Autowired protected RunnerBroker broker;
  @Autowired protected DockerRunnerAdapter adapter;
  @Autowired protected LocalRunnerWorkspaceStore workspaceStore;
  @Autowired protected RunnerScratchStore scratchStore;
  @Autowired protected JdbcTemplate jdbcTemplate;

  private DockerClient dockerClient;
  private final List<String> containersToCleanup = new ArrayList<>();
  private final List<String> workspacesToCleanup = new ArrayList<>();
  private final List<String> runIdsToCleanup = new ArrayList<>();

  @BeforeEach
  void brokerBaseSetUp() {
    // Raw client against the same daemon the production DockerClient bean talks to — used only to
    // launch / inspect the test containers; the system-under-test reaches the engine via the
    // autowired adapter + gateway beans.
    dockerClient = DockerClientFactory.instance().client();
  }

  @AfterEach
  void brokerBaseTearDown() {
    // Story 3.2a code-review (2026-05-29): surface cleanup failures at WARN instead of silently
    // swallowing them. A daemon that fails to remove a still-running labelled container would
    // otherwise leak it across the shared suite with no signal, weakening the dangling-container
    // IT's "untouched control container" guarantee. We do NOT rethrow — failing @AfterEach would
    // mask the actual test outcome — but the leak is now visible in the build log.
    for (String id : containersToCleanup) {
      try {
        dockerClient.removeContainerCmd(id).withForce(true).exec();
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
    // Append-only tables first (FK children), then parents. Mirrors
    // RecoveryServiceDockerRetryContractTest's recipe; @SpringBootTest is not @Transactional so the
    // broker's per-item commits are real and must be torn down explicitly.
    jdbcTemplate.update("delete from recovery_actions");
    jdbcTemplate.update("delete from runner_executions");
    jdbcTemplate.update("delete from artifact_operations");
    jdbcTemplate.update("delete from artifacts");
    jdbcTemplate.update("delete from workflow_events");
    jdbcTemplate.update("delete from idempotency_records");
    jdbcTemplate.update("delete from workflow_runs");
  }

  // ----- DB seed helpers -----

  /** Insert a workflow_run in the given (non-terminal) state and return its public id. */
  protected String seedWorkflowRun(String currentState) {
    String runId = PublicIdPrefixes.WORKFLOW_RUN.next();
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, ?)", runId, currentState);
    runIdsToCleanup.add(runId);
    return runId;
  }

  /**
   * Insert a {@code running} runner_executions row with explicit (relative-to-now) {@code
   * last_activity_at} / {@code timeout_at} intervals so the broker scans behave deterministically.
   * Negative seconds put the timestamp in the past.
   */
  protected String seedRunningRunner(
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

  // ----- container launch helpers -----

  /** Launch a started container running {@code cmd}, tagged with the deliveryline 5-label set. */
  protected String launchLabeledContainer(
      String rex, String runId, String stageValue, String... cmd) {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("deliveryline.runnerExecutionId", rex);
    labels.put("deliveryline.workflowRunId", runId);
    labels.put("deliveryline.runnerKind", "codex");
    labels.put("deliveryline.stage", stageValue);
    labels.put("deliveryline.dispatchedAt", OffsetDateTime.now().toString());
    String id =
        dockerClient.createContainerCmd(TEST_IMAGE).withCmd(cmd).withLabels(labels).exec().getId();
    containersToCleanup.add(id);
    dockerClient.startContainerCmd(id).exec();
    return id;
  }

  // ----- workspace / scratch helpers -----

  protected Path prepareWorkspace(String rex) {
    Path root = workspaceStore.prepare(rex).root();
    workspacesToCleanup.add(rex);
    return root;
  }

  protected Path logsDir(String rex) {
    return workspaceStore.workspaceRoot().resolve(rex).resolve("logs");
  }

  protected Path outputDir(String rex) {
    return workspaceStore.workspaceRoot().resolve(rex).resolve("output");
  }

  // ----- engine / DB assertion helpers -----

  protected boolean isRunning(String containerId) {
    InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
    return Boolean.TRUE.equals(inspect.getState().getRunning());
  }

  /** Poll the engine until the container is not running; fail if it is still running at timeout. */
  protected void awaitNotRunning(String containerId, Duration timeout) {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      if (!isRunning(containerId)) {
        return;
      }
      sleepQuietly(Duration.ofMillis(200));
    }
    // Story 3.2a code-review (2026-05-29): fail loudly instead of returning silently. A silent
    // return let a test proceed against a still-running container (recovery exercising the wrong
    // branch) and still pass for the wrong reason.
    throw new AssertionError(
        "container "
            + containerId
            + " was still running after "
            + timeout
            + " — awaitNotRunning timed out");
  }

  protected String runnerStatus(String rex) {
    return jdbcTemplate.queryForObject(
        "select status from runner_executions where public_id = ?", String.class, rex);
  }

  protected OffsetDateTime lastActivityAt(String rex) {
    return jdbcTemplate.queryForObject(
        "select last_activity_at from runner_executions where public_id = ?",
        OffsetDateTime.class,
        rex);
  }

  protected OffsetDateTime timeoutAt(String rex) {
    return jdbcTemplate.queryForObject(
        "select timeout_at from runner_executions where public_id = ?", OffsetDateTime.class, rex);
  }

  protected int eventCount(String runId, String eventType) {
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

  protected static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  protected static void writeFile(Path target, String content) throws Exception {
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
  }
}
