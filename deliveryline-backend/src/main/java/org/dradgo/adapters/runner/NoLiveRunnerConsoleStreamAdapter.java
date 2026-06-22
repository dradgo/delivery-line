package org.dradgo.adapters.runner;

import org.dradgo.application.runner.spi.RunnerConsoleStreamPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Story 3d-6 (FR68, ADR 0025) — fallback {@link RunnerConsoleStreamPort} for the non-Docker (mock)
 * profile. There is no live container to attach, so it always reports a not-live subscription; the
 * application orchestration then rejects with {@code console-not-live} (LIVE-ONLY, DD-3). Mirrors
 * {@link NoLiveRunnerLogStreamAdapter}'s {@code !runners.docker} profile so exactly one {@code
 * RunnerConsoleStreamPort} bean is active in every profile.
 */
@Component
@Profile("!runners.docker")
public class NoLiveRunnerConsoleStreamAdapter implements RunnerConsoleStreamPort {

  private static final Logger log = LoggerFactory.getLogger(NoLiveRunnerConsoleStreamAdapter.class);

  @Override
  public ConsoleSubscription attachConsole(
      String runnerExecutionId, RawConsoleSink onChunk, Runnable onEnd) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    log.info(
        "runner console not-live (mock profile) runnerExecutionId={} decision=not-live",
        runnerExecutionId);
    return ConsoleSubscription.notLive();
  }
}
