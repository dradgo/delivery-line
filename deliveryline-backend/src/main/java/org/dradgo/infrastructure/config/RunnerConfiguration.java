package org.dradgo.infrastructure.config;

import org.dradgo.application.runner.RunnerBroker;
import org.dradgo.application.runner.RunnerProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
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
@EnableConfigurationProperties(RunnerProperties.class)
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
