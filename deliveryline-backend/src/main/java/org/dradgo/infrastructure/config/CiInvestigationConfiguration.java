package org.dradgo.infrastructure.config;

import org.dradgo.application.workflow.ci.CiStatusPollingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Story 3h-5 (AC2/AC4, Decision 4) — infrastructure scheduler trigger for the CI-investigation
 * sweep.
 *
 * <p>Mirrors {@code SplitRollupSweepConfiguration} / {@code
 * IntegrationConflictDetectionConfiguration}: every {@code @Scheduled}/{@code @EnableScheduling}
 * lives in {@code infrastructure.config}, paired with {@code @ConditionalOnProperty} + {@code
 * fixedDelayString = "${…interval-ms:default}"}. The business logic stays in the
 * framework-trigger-free application-layer {@link CiStatusPollingService}; this class only wires
 * the Spring scheduler to it.
 *
 * <p>The {@code @ConditionalOnProperty} on {@code deliveryline.workflow.ci-investigation.enabled}
 * is the <strong>load-bearing parity gate</strong> (Decision 4): when the flag is {@code false} or
 * absent, this entire configuration (and therefore the scheduled bean) is never registered, so a
 * disabled sweep adds zero scheduled work and the delivery tail is byte-identical to pre-3h-5
 * (AC4). Unlike BUILD/LINT/delivery there is NO per-project CI flag — parity comes from this sweep
 * switch itself. {@code @EnableScheduling} is scoped to this conditional config so the scheduling
 * infrastructure only spins up when the sweep is enabled.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "deliveryline.workflow.ci-investigation.enabled")
public class CiInvestigationConfiguration {

  private final CiStatusPollingService ciStatusPollingService;

  public CiInvestigationConfiguration(CiStatusPollingService ciStatusPollingService) {
    this.ciStatusPollingService = ciStatusPollingService;
  }

  @Scheduled(fixedDelayString = "${deliveryline.workflow.ci-investigation.interval-ms:30000}")
  public void poll() {
    ciStatusPollingService.sweep();
  }
}
