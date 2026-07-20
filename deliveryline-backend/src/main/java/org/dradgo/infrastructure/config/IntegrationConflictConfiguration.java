package org.dradgo.infrastructure.config;

import org.dradgo.application.integration.conflict.IntegrationConflictDetectionProperties;
import org.dradgo.application.integration.conflict.IntegrationConflictTerminalSweepProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Story 4.17 (AC1) — UNCONDITIONAL binding of {@link IntegrationConflictDetectionProperties} from
 * the {@code deliveryline.integration.conflict-detection.*} namespace. Separate from the
 * {@code @ConditionalOnProperty}-gated {@link IntegrationConflictDetectionConfiguration} trigger so
 * the properties bean always exists — the unconditional {@code IntegrationConflictDetectionService}
 * (which reads {@code batchLimit}) must wire in every {@code @SpringBootTest} tier even when the
 * scheduled sweep is disabled. The {@code @EnableConfigurationProperties} lives here
 * (infrastructure) rather than on the record so the application-must-not-depend-on-infrastructure
 * rule stays clean, mirroring {@code WorkflowConfiguration}.
 *
 * <p>Story 4.30 (AC4) — same posture for {@link IntegrationConflictTerminalSweepProperties} ({@code
 * deliveryline.integration-conflict.terminal-sweep.*}): the properties bean is bound
 * UNCONDITIONALLY here so the always-registered {@code
 * IntegrationConflictTerminalRunReconciliationSweepService} (which reads {@code batchLimit}) wires
 * in every tier, while the {@code @Scheduled} trigger stays {@code @ConditionalOnProperty}-gated in
 * {@link IntegrationConflictTerminalSweepConfiguration}.
 */
@Configuration
@EnableConfigurationProperties({
  IntegrationConflictDetectionProperties.class,
  IntegrationConflictTerminalSweepProperties.class
})
public class IntegrationConflictConfiguration {}
