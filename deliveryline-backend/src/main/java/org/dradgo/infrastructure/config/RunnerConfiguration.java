package org.dradgo.infrastructure.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerProperties;
import org.dradgo.application.runner.RunnerWorkerPoolProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runner subsystem wiring:
 *
 * <ul>
 *   <li>binds {@link RunnerProperties} from the {@code deliveryline.runner.*} namespace,
 *   <li>hosts the {@link Scheduled} bean that drives {@link RunnerBroker#scanForTimeouts()},
 *   <li>hosts the {@code ApplicationReadyEvent} listener that drives {@link
 *       RunnerBroker#recoverOnStartup()},
 *   <li>fails fast at startup if both {@code runners.mock} and {@code runners.docker} profiles are
 *       active.
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({RunnerProperties.class, RunnerWorkerPoolProperties.class})
@EnableScheduling
public class RunnerConfiguration {

  private static final String MOCK_PROFILE = "runners.mock";
  private static final String DOCKER_PROFILE = "runners.docker";

  private final RunnerBroker runnerBroker;
  private final RunnerProperties runnerProperties;

  public RunnerConfiguration(
      RunnerBroker runnerBroker, RunnerProperties runnerProperties, Environment environment) {
    this.runnerBroker = runnerBroker;
    this.runnerProperties = runnerProperties;
    assertExclusiveRunnerProfile(environment);
  }

  @Scheduled(fixedDelayString = "${deliveryline.runner.timeout-scan-interval-ms:10000}")
  public void scanRunnerTimeouts() {
    if (!runnerProperties.scheduling().enabled()) {
      return;
    }
    runnerBroker.scanForTimeouts();
  }

  @Scheduled(fixedDelayString = "${deliveryline.runner.poll-interval-ms:5000}")
  public void pollRunnerExecutions() {
    if (!runnerProperties.scheduling().enabled()) {
      return;
    }
    runnerBroker.pollActiveExecutions();
  }

  /**
   * Story 3.2 AC3: scheduled trigger for the heartbeat-stale + orphan-flip scan. Runs on its own
   * cadence (default 60s) because stale-detection is slower-changing than timeout enforcement
   * (OQ-7).
   */
  @Scheduled(fixedDelayString = "${deliveryline.runner.stale-scan-interval-ms:60000}")
  public void scanRunnerStaleExecutions() {
    if (!runnerProperties.scheduling().enabled()) {
      return;
    }
    runnerBroker.scanForStaleExecutions();
  }

  /**
   * Story 3h-1 (review fix) — the executor the {@code BuildStageService} offloads its (potentially
   * minutes-long) backend-side build onto, so the {@code afterCommit} hook — which fires
   * synchronously on the committing/poller thread — returns IMMEDIATELY and the single scheduled
   * poller thread is never blocked for the build's duration. A virtual-thread-per-task executor:
   * builds are few and mostly wait on a subprocess, so unbounded virtual threads are cheap and
   * never starve a platform pool. Spring closes it (it is an {@code AutoCloseable ExecutorService})
   * on context shutdown.
   */
  @Bean("buildStageExecutor")
  @Profile("!test")
  Executor buildStageExecutor() {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("build-stage-", 0).factory());
  }

  /**
   * Test-profile twin of {@link #buildStageExecutor()} — a SAME-THREAD executor so build-stage
   * integration tests that drive the gate inside a transaction observe the build synchronously on
   * commit (deterministic), instead of racing an async virtual thread. Same bean name, mutually
   * exclusive profile ⇒ exactly one is registered.
   */
  @Bean("buildStageExecutor")
  @Profile("test")
  Executor buildStageExecutorForTests() {
    return Runnable::run;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recoverRunnerExecutionsOnStartup() {
    if (!runnerProperties.scheduling().enabled()) {
      return;
    }
    runnerBroker.recoverOnStartup();
  }

  private static void assertExclusiveRunnerProfile(Environment environment) {
    boolean mockActive = isProfileActive(environment, MOCK_PROFILE);
    boolean dockerActive = isProfileActive(environment, DOCKER_PROFILE);
    if (mockActive && dockerActive) {
      throw new IllegalStateException(
          "Runner profile conflict: "
              + MOCK_PROFILE
              + " and "
              + DOCKER_PROFILE
              + " are mutually exclusive but both are active. Activate exactly one.");
    }
  }

  private static boolean isProfileActive(Environment environment, String profile) {
    for (String active : environment.getActiveProfiles()) {
      if (active.equals(profile)) {
        return true;
      }
    }
    return false;
  }
}
