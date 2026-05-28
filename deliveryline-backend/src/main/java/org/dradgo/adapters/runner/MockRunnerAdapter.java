package org.dradgo.adapters.runner;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.dradgo.application.runner.RunnerDispatchAck;
import org.dradgo.application.runner.RunnerDispatchRequest;
import org.dradgo.application.runner.RunnerPollStatus;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.spi.RecoverableRunnerAdapter;
import org.dradgo.application.runner.spi.RunnerScratchStore;
import org.dradgo.domain.registry.FailureCategory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic runner-adapter implementation that reads pre-baked scenario fixtures and writes
 * runner-result bytes (or simulates the corresponding failure) without spawning a real container.
 *
 * <p>Active only under the {@code runners.mock} Spring profile. The Epic 3 {@code
 * DockerRunnerAdapter} activates under a future {@code runners.docker} profile (kept undefined in
 * Epic 1).
 */
@Component
@Profile("!runners.docker")
public class MockRunnerAdapter implements RecoverableRunnerAdapter {

  private static final Logger log = LoggerFactory.getLogger(MockRunnerAdapter.class);

  private final RunnerScratchStore scratchStore;
  private final MockRunnerScenarioRegistry scenarioRegistry;
  private final RunnerProperties runnerProperties;
  private final Clock clock;
  private final ConcurrentMap<String, MockRunnerScenario> dispatched = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, OffsetDateTime> dispatchedAt = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, String> scenarioOverrides = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, byte[]> deferredPayloads = new ConcurrentHashMap<>();

  @org.springframework.beans.factory.annotation.Autowired
  public MockRunnerAdapter(
      RunnerScratchStore scratchStore,
      MockRunnerScenarioRegistry scenarioRegistry,
      RunnerProperties runnerProperties) {
    this(scratchStore, scenarioRegistry, runnerProperties, Clock.systemUTC());
  }

  MockRunnerAdapter(
      RunnerScratchStore scratchStore,
      MockRunnerScenarioRegistry scenarioRegistry,
      RunnerProperties runnerProperties,
      Clock clock) {
    this.scratchStore = Objects.requireNonNull(scratchStore, "scratchStore");
    this.scenarioRegistry = Objects.requireNonNull(scenarioRegistry, "scenarioRegistry");
    this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Pin the scenario that the next {@link #dispatch} call for the given workflow-run will use.
   * Intended for tests that need to drive a specific failure mode end-to-end.
   */
  public void pinScenarioForWorkflowRun(String workflowRunId, String scenarioName) {
    scenarioOverrides.put(workflowRunId, scenarioName);
  }

  @Override
  public RunnerDispatchAck dispatch(RunnerDispatchRequest request) {
    Objects.requireNonNull(request, "request");
    String workflowRunId = request.workflowRunId();
    String overrideName = scenarioOverrides.remove(workflowRunId);
    String scenarioName =
        overrideName != null ? overrideName : runnerProperties.mock().scenarioFor(request.stage());
    MockRunnerScenario scenario = scenarioRegistry.require(scenarioName);

    dispatched.put(request.runnerExecutionId(), scenario);
    dispatchedAt.put(request.runnerExecutionId(), OffsetDateTime.now(clock));

    executeScenarioSideEffect(request, scenario);

    log.info(
        "mock dispatched runnerExecutionId={} workflowRunId={} stage={} scenario={} behaviour={}",
        request.runnerExecutionId(),
        workflowRunId,
        request.stage().value(),
        scenario.name(),
        scenario.behaviour());
    return new RunnerDispatchAck("mock:" + scenario.name() + ":" + request.runnerExecutionId());
  }

  @Override
  public RunnerPollStatus poll(String runnerExecutionId) {
    MockRunnerScenario scenario = dispatched.get(runnerExecutionId);
    if (scenario == null) {
      return new RunnerPollStatus.Unknown();
    }
    return switch (scenario.behaviour()) {
      case TIMEOUT -> new RunnerPollStatus.Running();
      case CRASH -> new RunnerPollStatus.Failed(FailureCategory.RUNNER_CRASH);
      // LATE_RESULT: the result file is not written until releaseDeferredResult(...) is called,
      // so poll keeps returning Running. The broker's scanForTimeouts (or an operator-driven
      // step) marks the row TIMED_OUT before the result is released.
      case LATE_RESULT -> new RunnerPollStatus.Running();
      case HAPPY, CONTRACT_VIOLATION, NON_ZERO_EXIT, DUPLICATE_RESULT, MALFORMED_OUTPUT ->
          new RunnerPollStatus.Completed();
    };
  }

  /**
   * Test-only hook: write a previously-deferred result file to the scratch directory. Used by
   * deterministic scenario tests to simulate the "result arrives after deadline" branch
   * (LATE_RESULT) and the "second result for the same runnerExecutionId" branch (DUPLICATE_RESULT)
   * without depending on wall-clock or thread scheduling.
   */
  public boolean releaseDeferredResult(String runnerExecutionId) {
    byte[] payload = deferredPayloads.remove(runnerExecutionId);
    if (payload == null) {
      return false;
    }
    scratchStore.writeRunnerResult(runnerExecutionId, payload);
    log.info(
        "mock released deferred result runnerExecutionId={} bytes={}",
        runnerExecutionId,
        payload.length);
    return true;
  }

  @Override
  public Optional<byte[]> tryReadResult(String runnerExecutionId) {
    return scratchStore.tryReadRunnerResult(runnerExecutionId);
  }

  @Override
  public void cancel(String runnerExecutionId) {
    dispatched.remove(runnerExecutionId);
    dispatchedAt.remove(runnerExecutionId);
  }

  @Override
  public Optional<RunnerPollStatus> recoverHandle(String runnerExecutionId) {
    // The mock adapter has no external state to probe — the broker's existing scratch-replay
    // branch (Trap T6) handles JVM-restart recovery. Returning empty preserves that posture.
    return Optional.empty();
  }

  @Override
  public org.dradgo.application.runner.spi.RecoverableRunnerAdapter.TerminationOutcome terminate(
      String runnerExecutionId, java.time.Duration graceful) {
    // Mock has no external process. cancel() drops the registration; report STOPPED_GRACEFULLY
    // when the row was known, UNKNOWN otherwise — matches the docker contract semantics.
    boolean known = dispatched.containsKey(runnerExecutionId);
    cancel(runnerExecutionId);
    return known
        ? org.dradgo.application.runner.spi.RecoverableRunnerAdapter.TerminationOutcome
            .STOPPED_GRACEFULLY
        : org.dradgo.application.runner.spi.RecoverableRunnerAdapter.TerminationOutcome.UNKNOWN;
  }

  private void executeScenarioSideEffect(
      RunnerDispatchRequest request, MockRunnerScenario scenario) {
    switch (scenario.behaviour()) {
      case HAPPY, CONTRACT_VIOLATION, NON_ZERO_EXIT -> {
        byte[] fixture = scenarioRegistry.loadFixtureBytes(scenario);
        if (fixture.length == 0) {
          return;
        }
        byte[] tailored = tailorFixture(fixture, request);
        scratchStore.writeRunnerResult(request.runnerExecutionId(), tailored);
      }
      case DUPLICATE_RESULT -> {
        // First result written at dispatch (broker poll harvests it, marks COMPLETED).
        // A second copy is queued via deferredPayloads so the test can call
        // releaseDeferredResult(...) to overwrite the scratch file and re-trigger
        // broker.onResult — the validator's observedRunnerExecutionIds check then fires.
        byte[] fixture = scenarioRegistry.loadFixtureBytes(scenario);
        if (fixture.length == 0) {
          return;
        }
        byte[] tailored = tailorFixture(fixture, request);
        scratchStore.writeRunnerResult(request.runnerExecutionId(), tailored);
        deferredPayloads.put(request.runnerExecutionId(), tailored);
      }
      case LATE_RESULT -> {
        // No result file at dispatch — the row is allowed to time out first. The fixture is
        // queued so a test can release it after the row transitions to TIMED_OUT and observe
        // the AC5 late-result branch in the broker.
        byte[] fixture = scenarioRegistry.loadFixtureBytes(scenario);
        if (fixture.length == 0) {
          return;
        }
        byte[] tailored = tailorFixture(fixture, request);
        deferredPayloads.put(request.runnerExecutionId(), tailored);
      }
      case MALFORMED_OUTPUT -> {
        byte[] fixture = scenarioRegistry.loadFixtureBytes(scenario);
        byte[] tailored =
            fixture.length > 0
                ? tailorFixture(fixture, request)
                : "{\"schemaVersion\":1".getBytes(StandardCharsets.UTF_8);
        byte[] truncated = truncate(tailored);
        scratchStore.writeRunnerResult(request.runnerExecutionId(), truncated);
      }
      case TIMEOUT, CRASH -> {
        // No result file — the broker's scheduled scan or poll path classifies the failure.
      }
    }
  }

  private byte[] tailorFixture(byte[] fixtureBytes, RunnerDispatchRequest request) {
    String fixture = new String(fixtureBytes, StandardCharsets.UTF_8);
    String tailored =
        fixture
            .replace("{{workflowRunId}}", request.workflowRunId())
            .replace("{{runnerExecutionId}}", request.runnerExecutionId());
    return tailored.getBytes(StandardCharsets.UTF_8);
  }

  private byte[] truncate(byte[] payload) {
    int cut = Math.max(8, payload.length / 2);
    byte[] truncated = new byte[Math.min(cut, payload.length)];
    System.arraycopy(payload, 0, truncated, 0, truncated.length);
    return truncated;
  }
}
