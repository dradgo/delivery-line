package org.dradgo.infrastructure.config;

import org.dradgo.application.integration.conflict.IntegrationConflictTerminalRunReconciliationSweepService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Story 4.30 (AC4) — infrastructure scheduler trigger for the terminal-run integration-conflict
 * reconciliation sweep.
 *
 * <p>Mirrors {@code SplitRollupSweepConfiguration} / {@code
 * IntegrationConflictDetectionConfiguration}: every {@code @Scheduled}/{@code @EnableScheduling}
 * lives in {@code infrastructure.config}, paired with {@code @ConditionalOnProperty} + {@code
 * fixedDelayString = "${…interval-ms:default}"}. The business logic stays in the framework-
 * trigger-free application-layer {@link IntegrationConflictTerminalRunReconciliationSweepService};
 * this class only wires the Spring scheduler to it.
 *
 * <p>The {@code @ConditionalOnProperty} on {@code
 * deliveryline.integration-conflict.terminal-sweep.enabled} is the load-bearing gate: when the flag
 * is {@code false} or absent, <strong>this entire configuration (and therefore the scheduled bean)
 * is never registered</strong>, so a disabled sweep adds zero scheduled work and is byte-identical
 * to pre-story behavior (AC4). {@code @EnableScheduling} is scoped to this conditional config so
 * the scheduling infrastructure only spins up when the sweep is enabled.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "deliveryline.integration-conflict.terminal-sweep.enabled")
public class IntegrationConflictTerminalSweepConfiguration {

  private final IntegrationConflictTerminalRunReconciliationSweepService terminalRunSweep;

  public IntegrationConflictTerminalSweepConfiguration(
      IntegrationConflictTerminalRunReconciliationSweepService terminalRunSweep) {
    this.terminalRunSweep = terminalRunSweep;
  }

  @Scheduled(
      fixedDelayString = "${deliveryline.integration-conflict.terminal-sweep.interval-ms:60000}")
  public void runSweep() {
    terminalRunSweep.sweep();
  }
}
