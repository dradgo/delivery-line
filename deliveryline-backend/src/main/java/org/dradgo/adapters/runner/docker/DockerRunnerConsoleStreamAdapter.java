package org.dradgo.adapters.runner.docker;

import java.util.Optional;
import org.dradgo.application.runner.spi.RunnerConsoleStreamPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Story 3d-6 (FR68, ADR 0025) — Docker implementation of {@link RunnerConsoleStreamPort}. Resolves
 * the live container for a runner execution (label probe {@code
 * findContainerIdByRunnerExecutionId}) and attaches a READ-ONLY console to its stdio through the
 * gateway, keeping docker-java confined behind {@link DockerEngineGateway} (Trap T8). A dedicated
 * adapter class rather than widening {@code DockerRunnerAdapter}'s constructor avoids the ctor-dep
 * fan-out trap (Trap T7 / {@code docker-adapter-ctor-dep-fans-out}).
 *
 * <p><b>LIVE-ONLY (DD-3).</b> When no container survives for the rex the subscription reports
 * {@link RunnerConsoleStreamPort.ConsoleSubscription#isLive()} {@code false} so the application
 * orchestration rejects with {@code console-not-live} — there is NO finished-mode fallback (the
 * finished-state diagnostic surface is the story 3d-5 log viewer). An attach failure never throws
 * into the SSE caller thread.
 */
@Component
@Profile("runners.docker")
public class DockerRunnerConsoleStreamAdapter implements RunnerConsoleStreamPort {

  private static final Logger log = LoggerFactory.getLogger(DockerRunnerConsoleStreamAdapter.class);

  private final DockerEngineGateway gateway;

  public DockerRunnerConsoleStreamAdapter(DockerEngineGateway gateway) {
    this.gateway = gateway;
  }

  @Override
  public ConsoleSubscription attachConsole(
      String runnerExecutionId, RawConsoleSink onChunk, Runnable onEnd) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    try {
      Optional<String> containerId = gateway.findContainerIdByRunnerExecutionId(runnerExecutionId);
      if (containerId.isEmpty()) {
        log.info(
            "runner console no live container runnerExecutionId={} decision=not-live",
            runnerExecutionId);
        return ConsoleSubscription.notLive();
      }
      AutoCloseable handle = gateway.attachContainerConsole(containerId.get(), onChunk, onEnd);
      log.info(
          "runner console live attach started runnerExecutionId={} containerId={}",
          runnerExecutionId,
          containerId.get());
      return new ConsoleSubscription() {
        @Override
        public boolean isLive() {
          return true;
        }

        @Override
        public void close() {
          try {
            handle.close();
          } catch (Exception closeFailure) {
            log.warn(
                "runner console close failed runnerExecutionId={} cause={}",
                runnerExecutionId,
                closeFailure.toString());
          }
        }
      };
    } catch (RuntimeException attachFailure) {
      // Best-effort (Trap T3): an attach setup failure degrades to not-live (the application
      // rejects with console-not-live) rather than throwing into the streaming caller.
      log.warn(
          "runner console attach failed runnerExecutionId={} decision=not-live cause={}",
          runnerExecutionId,
          attachFailure.toString());
      return ConsoleSubscription.notLive();
    }
  }
}
