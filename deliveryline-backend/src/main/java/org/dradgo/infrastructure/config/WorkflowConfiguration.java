package org.dradgo.infrastructure.config;

import org.dradgo.application.workflow.ArchiveProperties;
import org.dradgo.application.workflow.ComplexTicketFlowProperties;
import org.dradgo.application.workflow.RollupSweepProperties;
import org.dradgo.application.workflow.WorkflowProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Workflow subsystem wiring (story 3.9 Task 6). Binds {@link WorkflowProperties} from the {@code
 * deliveryline.workflow.*} namespace so {@code RepositoryWorkspaceService} (clone-depth / sparse /
 * bot identity) and the doctor git-bot-identity probe can read the configured values. The
 * {@code @EnableConfigurationProperties} annotation lives here (infrastructure) rather than on the
 * properties record so the application-must-not-depend-on-infrastructure architecture rule stays
 * clean, mirroring {@code GitHubConfiguration}/{@code RunnerConfiguration}.
 */
@Configuration
@EnableConfigurationProperties({
  WorkflowProperties.class,
  ArchiveProperties.class,
  ComplexTicketFlowProperties.class,
  RollupSweepProperties.class
})
public class WorkflowConfiguration {}
