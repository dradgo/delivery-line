package org.dradgo.adapters.runner.docker;

import java.util.Optional;
import org.dradgo.application.runner.spi.RunnerLogStreamPort;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Story 3d-5 (FR65, ADR 0025) — Docker implementation of {@link RunnerLogStreamPort}. Resolves the
 * live container for a runner execution (label probe {@code findContainerIdByRunnerExecutionId})
 * and follows its logs through the gateway, keeping docker-java confined behind {@link
 * DockerEngineGateway} (Trap T7/T8). A dedicated adapter class rather than widening {@code
 * DockerRunnerAdapter}'s constructor avoids the ctor-dep fan-out trap ({@code
 * docker-adapter-ctor-dep-fans-out}).
 *
 * <p>Best-effort: when no container survives for the rex the subscription reports {@link
 * RunnerLogStreamPort.LiveLogSubscription#isLive()} {@code false} so the application orchestration
 * falls back to finished-mode (replay of the story-3.6 persisted redacted log). A follow failure
 * never throws into the SSE caller thread.
 */
@Component
@Profile("runners.docker")
public class DockerRunnerLogStreamAdapter implements RunnerLogStreamPort {

  private static final Logger log = LoggerFactory.getLogger(DockerRunnerLogStreamAdapter.class);

  private final DockerEngineGateway gateway;

  public DockerRunnerLogStreamAdapter(DockerEngineGateway gateway) {
    this.gateway = gateway;
  }

  @Override
  public LiveLogSubscription followLiveLogs(
      String runnerExecutionId, RawLogLineSink onLine, Runnable onEnd) {
    PublicIdPrefixes.require(runnerExecutionId, PublicIdPrefixes.RUNNER_EXECUTION);
    try {
      Optional<String> containerId = gateway.findContainerIdByRunnerExecutionId(runnerExecutionId);
      if (containerId.isEmpty()) {
        log.info(
            "runner log stream no live container runnerExecutionId={} fallback=finished",
            runnerExecutionId);
        return LiveLogSubscription.notLive();
      }
      AutoCloseable handle = gateway.followContainerLogs(containerId.get(), onLine, onEnd);
      log.info(
          "runner log stream live follow started runnerExecutionId={} containerId={}",
          runnerExecutionId,
          containerId.get());
      return new LiveLogSubscription() {
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
                "runner log stream close failed runnerExecutionId={} cause={}",
                runnerExecutionId,
                closeFailure.toString());
          }
        }
      };
    } catch (RuntimeException followFailure) {
      // Best-effort (Trap T3): a follow setup failure degrades to not-live (finished fallback)
      // rather than throwing into the streaming caller.
      log.warn(
          "runner log stream follow failed runnerExecutionId={} fallback=finished cause={}",
          runnerExecutionId,
          followFailure.toString());
      return LiveLogSubscription.notLive();
    }
  }
}
