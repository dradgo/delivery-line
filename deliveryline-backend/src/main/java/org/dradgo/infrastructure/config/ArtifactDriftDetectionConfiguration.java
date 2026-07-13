package org.dradgo.infrastructure.config;

import org.dradgo.application.artifact.reconciliation.ArtifactDriftDetectionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Story 4.15 (AC1) — infrastructure scheduler trigger for the artifact-drift-detection sweep.
 *
 * <p>Mirrors {@code SplitRollupSweepConfiguration} / {@code
 * IntegrationConflictDetectionConfiguration}: every {@code @Scheduled}/{@code @EnableScheduling}
 * lives in {@code infrastructure.config}, paired with {@code @ConditionalOnProperty} + {@code
 * fixedDelayString = "${…interval-ms:default}"}. The business logic stays in the
 * framework-trigger-free application-layer {@link ArtifactDriftDetectionService}; this class only
 * wires the Spring scheduler to it.
 *
 * <p>The {@code @ConditionalOnProperty} on {@code deliveryline.artifact.drift-detection.enabled} is
 * the load-bearing gate: when the flag is {@code false} or absent, this entire configuration (and
 * the scheduled bean) is never registered, so the test tiers (where the flag is {@code false}) run
 * no scheduled sweep and drive {@code detectDrift()} directly. {@code @EnableScheduling} is scoped
 * to this conditional config so the scheduler only spins up when detection is enabled. Property key
 * is {@code deliveryline.artifact.drift-detection.interval-ms} (default 900000 = 15 min).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "deliveryline.artifact.drift-detection.enabled")
public class ArtifactDriftDetectionConfiguration {

  private final ArtifactDriftDetectionService detectionService;

  public ArtifactDriftDetectionConfiguration(ArtifactDriftDetectionService detectionService) {
    this.detectionService = detectionService;
  }

  @Scheduled(fixedDelayString = "${deliveryline.artifact.drift-detection.interval-ms:900000}")
  public void detect() {
    detectionService.detectDrift();
  }
}
