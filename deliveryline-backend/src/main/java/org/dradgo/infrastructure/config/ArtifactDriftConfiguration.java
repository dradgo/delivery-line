package org.dradgo.infrastructure.config;

import org.dradgo.application.artifact.reconciliation.ArtifactDriftDetectionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Story 4.15 (AC1) — UNCONDITIONAL binding of {@link ArtifactDriftDetectionProperties} from the
 * {@code deliveryline.artifact.drift-detection.*} namespace. Separate from the
 * {@code @ConditionalOnProperty}-gated {@link ArtifactDriftDetectionConfiguration} trigger so the
 * properties bean always exists — the unconditional {@code ArtifactDriftDetectionService} (which
 * reads {@code batchLimit}/{@code minAgeMinutes}) must wire in every {@code @SpringBootTest} tier
 * even when the scheduled sweep is disabled. The {@code @EnableConfigurationProperties} lives here
 * (infrastructure) rather than on the record so the application-must-not-depend-on-infrastructure
 * rule stays clean, mirroring {@code IntegrationConflictConfiguration}.
 */
@Configuration
@EnableConfigurationProperties(ArtifactDriftDetectionProperties.class)
public class ArtifactDriftConfiguration {}
