package org.dradgo.adapters.runner;

import org.dradgo.application.runner.spi.RunnerLogStreamPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Story 3d-5 — fallback {@link RunnerLogStreamPort} for the non-Docker (mock) profile. There is no
 * live container to follow, so it always reports a not-live subscription; the application
 * orchestration then serves finished-mode (replay of any story-3.6 persisted redacted log) — Task 2
 * "MockRunnerAdapter path". Mirrors {@link MockRunnerAdapter}'s {@code !runners.docker} profile so
 * exactly one {@code RunnerLogStreamPort} bean is active in every profile.
 */
@Component
@Profile("!runners.docker")
public class NoLiveRunnerLogStreamAdapter implements RunnerLogStreamPort {

  private static final Logger log = LoggerFactory.getLogger(NoLiveRunnerLogStreamAdapter.class);

  @Override
  public LiveLogSubscription followLiveLogs(
      String runnerExecutionId, RawLogLineSink onLine, Runnable onEnd) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    log.info(
        "runner log stream not-live (mock profile) runnerExecutionId={} fallback=finished",
        runnerExecutionId);
    return LiveLogSubscription.notLive();
  }
}
