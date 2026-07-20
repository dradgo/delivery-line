package org.dradgo.infrastructure.config;

import org.dradgo.application.integration.conflict.IntegrationConflictDetectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Story 4.17 (AC1) — infrastructure scheduler trigger for the integration-conflict-detection sweep.
 *
 * <p>Mirrors {@code SplitRollupSweepConfiguration}: every
 * {@code @Scheduled}/{@code @EnableScheduling} lives in {@code infrastructure.config}, paired with
 * {@code @ConditionalOnProperty} + {@code fixedDelayString = "${…interval-ms:default}"}. The
 * business logic stays in the framework-trigger-free application-layer {@link
 * IntegrationConflictDetectionService}; this class only wires the Spring scheduler to it.
 *
 * <p>The {@code @ConditionalOnProperty} on {@code
 * deliveryline.integration.conflict-detection.enabled} is the load-bearing gate: when the flag is
 * {@code false} or absent, this entire configuration (and the scheduled bean) is never registered,
 * so the test tiers (where the flag is {@code false}) run no scheduled sweep and drive {@code
 * sweep()} directly. {@code @EnableScheduling} is scoped to this conditional config so the
 * scheduler only spins up when detection is enabled. Property key is {@code
 * deliveryline.integration.conflict-detection.interval-ms} (default 300000 = 5 min).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "deliveryline.integration.conflict-detection.enabled")
public class IntegrationConflictDetectionConfiguration {

  private final IntegrationConflictDetectionService detectionService;

  public IntegrationConflictDetectionConfiguration(
      IntegrationConflictDetectionService detectionService) {
    this.detectionService = detectionService;
  }

  @Scheduled(fixedDelayString = "${deliveryline.integration.conflict-detection.interval-ms:300000}")
  public void detect() {
    detectionService.sweep();
  }
}
