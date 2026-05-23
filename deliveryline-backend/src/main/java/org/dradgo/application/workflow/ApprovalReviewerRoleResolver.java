package org.dradgo.application.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MVP-fallback resolver for the {@code reviewerRole} carried by spec approval / rejection commands.
 *
 * <p>The {@link org.dradgo.adapters.rest.WorkflowController} REST surface does not yet parse an
 * actor-identity header (story 2.13 rebuilds that surface). Until then the controller calls this
 * resolver: if the request body supplies a non-blank role, it is used verbatim; otherwise the
 * configured default {@code deliveryline.approval.default-reviewer-role} (or {@code
 * product_reviewer} as a safe ultimate fallback) is returned. The application-layer command ({@link
 * org.dradgo.application.workflow.commands.ApproveSpecCommand}) requires a non-blank role, so the
 * resolver guarantees one is always supplied.
 *
 * <p>Review batch 1 D2: observability signal. A one-shot {@code WARN} is emitted at construction
 * when no explicit {@code deliveryline.approval.default-reviewer-role} property is set (the @Value
 * default {@code product_reviewer} fired). A {@code DEBUG} is emitted per resolve when the request
 * supplied blank/null. The signal is investigation-time only — it does not block approvals.
 */
@Component
public class ApprovalReviewerRoleResolver {

  /** Hard-coded ultimate fallback so a misconfigured environment never blocks an approval. */
  static final String ULTIMATE_FALLBACK = "product_reviewer";

  private static final Logger log = LoggerFactory.getLogger(ApprovalReviewerRoleResolver.class);

  private final String configuredDefault;

  public ApprovalReviewerRoleResolver(
      @Value("${deliveryline.approval.default-reviewer-role:product_reviewer}")
          String configuredDefault) {
    this.configuredDefault = normalize(configuredDefault);
    if (this.configuredDefault == null) {
      log.warn(
          "approval reviewer-role resolver configured-default unset or blank — relying on ULTIMATE_FALLBACK={} for every blank request. Configure 'deliveryline.approval.default-reviewer-role' (story 2.13 will replace this MVP fallback with header-based attribution).",
          ULTIMATE_FALLBACK);
    } else if (ULTIMATE_FALLBACK.equals(this.configuredDefault)) {
      log.warn(
          "approval reviewer-role resolver configured-default is the @Value fallback '{}' — likely missing 'deliveryline.approval.default-reviewer-role' in application.yml; story 2.13 will replace this MVP fallback with header-based attribution.",
          ULTIMATE_FALLBACK);
    }
  }

  public String resolveFor(String requested) {
    String trimmed = normalize(requested);
    if (trimmed != null) {
      return trimmed;
    }
    if (configuredDefault != null) {
      log.debug(
          "approval reviewer-role resolver request was blank; applying configured default '{}'",
          configuredDefault);
      return configuredDefault;
    }
    log.debug(
        "approval reviewer-role resolver request was blank AND configured-default unset; applying ULTIMATE_FALLBACK '{}'",
        ULTIMATE_FALLBACK);
    return ULTIMATE_FALLBACK;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
