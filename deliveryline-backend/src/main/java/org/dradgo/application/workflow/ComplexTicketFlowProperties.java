package org.dradgo.application.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validated configuration for the {@code deliveryline.complex-ticket-flow.*} namespace (story 3f-7,
 * AC5).
 *
 * <p>Binds the recursive-split depth cap consumed by {@code SplitProposalService.request()}: a run
 * whose split depth (distance from the lineage root, walking {@code parentRunId}; root = depth 0)
 * is already {@code >= maxSplitDepth} is refused with {@code SPLIT_DEPTH_LIMIT_EXCEEDED} unless the
 * request carries the {@code allowDeepSplit} override.
 *
 * <p>Like {@link WorkflowProperties}/{@link ArchiveProperties}, the compact constructor
 * <strong>normalizes-with-defaults and never throws</strong> so the bean binds profile-neutrally in
 * every {@code @SpringBootTest} tier even when no {@code deliveryline.complex-ticket-flow.*} keys
 * are present (memory: {@code validated-config-needs-test-yaml} — normalize-never-throw, NOT
 * {@code @Validated}, dodges the test-yaml-mirror trap, and the key is still mirrored into both
 * {@code application.yml} files for discoverability). A non-positive {@code max-split-depth} clamps
 * to {@link #DEFAULT_MAX_SPLIT_DEPTH}. Registered via {@code @EnableConfigurationProperties} on the
 * infrastructure {@code WorkflowConfiguration} so the application layer never depends on
 * infrastructure.
 */
@ConfigurationProperties("deliveryline.complex-ticket-flow")
public record ComplexTicketFlowProperties(int maxSplitDepth) {

  /**
   * Default recursive-split depth cap: a depth-3 run is refused (its children would be depth 4).
   */
  public static final int DEFAULT_MAX_SPLIT_DEPTH = 3;

  public ComplexTicketFlowProperties {
    maxSplitDepth = maxSplitDepth <= 0 ? DEFAULT_MAX_SPLIT_DEPTH : maxSplitDepth;
  }

  public static ComplexTicketFlowProperties defaults() {
    return new ComplexTicketFlowProperties(DEFAULT_MAX_SPLIT_DEPTH);
  }
}
