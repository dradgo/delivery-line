package org.dradgo.infrastructure.config;

import org.dradgo.application.workflow.SplitRollupReconciliationSweepService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Story 3f-8 (AC4) — infrastructure scheduler trigger for the split-rollup reconciliation sweep.
 *
 * <p>Mirrors the codebase convention that every {@code @Scheduled}/{@code @EnableScheduling} lives
 * in {@code infrastructure.config} ({@code RunnerConfiguration}, {@code LinearPollingHost}, {@code
 * DockerRunnerLifecycleConfiguration}), always paired with {@code @ConditionalOnProperty} and a
 * {@code fixedDelayString = "${…interval-ms:default}"}. The business logic stays in the framework-
 * trigger-free application-layer {@link SplitRollupReconciliationSweepService}; this class only
 * wires the Spring scheduler to it.
 *
 * <p>The {@code @ConditionalOnProperty} on {@code
 * deliveryline.complex-ticket-flow.rollup-sweep.enabled} is the load-bearing gate: when the flag is
 * {@code false} or absent, <strong>this entire configuration (and therefore the scheduled bean) is
 * never registered</strong>, so a disabled sweep adds zero scheduled work and the 3f-7 hook path is
 * byte-identical to before this story (AC4). {@code @EnableScheduling} is scoped to this
 * conditional config so the scheduling infrastructure itself only spins up when the sweep is
 * enabled.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "deliveryline.complex-ticket-flow.rollup-sweep.enabled")
public class SplitRollupSweepConfiguration {

  private final SplitRollupReconciliationSweepService splitRollupReconciliationSweep;

  public SplitRollupSweepConfiguration(
      SplitRollupReconciliationSweepService splitRollupReconciliationSweep) {
    this.splitRollupReconciliationSweep = splitRollupReconciliationSweep;
  }

  @Scheduled(
      fixedDelayString = "${deliveryline.complex-ticket-flow.rollup-sweep.interval-ms:60000}")
  public void runSweep() {
    splitRollupReconciliationSweep.sweep();
  }
}
