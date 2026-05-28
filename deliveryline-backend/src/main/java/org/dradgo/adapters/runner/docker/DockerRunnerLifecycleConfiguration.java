package org.dradgo.adapters.runner.docker;

import java.time.Clock;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerWorkspaceCleanupJob;
import org.dradgo.application.runner.spi.DockerHostPort;
import org.dradgo.application.runner.spi.RunnerExecutionRecordPort;
import org.dradgo.application.runner.spi.RunnerWorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Story 3.2 — profile-gated wiring for the Docker runner lifecycle layer. The {@link
 * RunnerWorkspaceCleanupJob} bean + its scheduled trigger are activated ONLY under {@code
 * runners.docker}, so contributors without Docker (mock-runner default) do not load a cleanup
 * scheduler that has nothing to do.
 */
@Configuration
@Profile("runners.docker")
@EnableScheduling
public class DockerRunnerLifecycleConfiguration {

  @Bean
  public RunnerWorkspaceCleanupJob runnerWorkspaceCleanupJob(
      RunnerExecutionRecordPort recordPort,
      RunnerWorkspaceStore workspaceStore,
      ObjectProvider<DockerHostPort> dockerHostPortProvider,
      RunnerProperties runnerProperties) {
    return new RunnerWorkspaceCleanupJob(
        recordPort, workspaceStore, dockerHostPortProvider, runnerProperties, Clock.systemUTC());
  }

  @Bean
  public DockerWorkspaceCleanupScheduler dockerWorkspaceCleanupScheduler(
      RunnerWorkspaceCleanupJob cleanupJob, RunnerProperties runnerProperties) {
    return new DockerWorkspaceCleanupScheduler(cleanupJob, runnerProperties);
  }

  /**
   * Scheduled-only wrapper so the cleanup job stays a plain application class testable without
   * Spring. Scoped under {@code @Profile("runners.docker")} via this enclosing configuration.
   */
  public static class DockerWorkspaceCleanupScheduler {

    private final RunnerWorkspaceCleanupJob cleanupJob;
    private final RunnerProperties runnerProperties;

    public DockerWorkspaceCleanupScheduler(
        RunnerWorkspaceCleanupJob cleanupJob, RunnerProperties runnerProperties) {
      this.cleanupJob = cleanupJob;
      this.runnerProperties = runnerProperties;
    }

    @Scheduled(
        fixedDelayString = "${deliveryline.runner.docker.workspace-cleanup-interval-ms:3600000}")
    public void runScheduledCleanup() {
      if (!runnerProperties.scheduling().enabled()) {
        return;
      }
      cleanupJob.runCleanup();
    }
  }
}
