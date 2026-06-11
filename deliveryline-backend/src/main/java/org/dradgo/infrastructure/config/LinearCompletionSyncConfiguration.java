package org.dradgo.infrastructure.config;

import org.dradgo.application.workflow.LinearCompletionTemplate;
import org.dradgo.application.workflow.WorkflowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Story 3.16 (AC8 / Decision D4) — startup validation of the Linear completion-sync summary
 * template. When completion-sync is enabled ({@code deliveryline.workflow.linear-completion-sync
 * .enabled=true}), the configured template is validated against the documented placeholder grammar
 * ({@link LinearCompletionTemplate}); a malformed template throws {@code
 * DomainException(INVALID_COMPLETION_TEMPLATE)} from this {@code @Bean} factory method, failing
 * context startup so a broken merge-ready summary can never reach a real Linear post.
 *
 * <p>The validation lives here (a startup {@code @Bean}) and NOT in the {@link
 * WorkflowProperties.LinearCompletionSync} compact constructor on purpose (house rule, story 3.9
 * D8): the record's ctor must normalize-never-throw so the bean binds profile-neutrally in every
 * {@code @SpringBootTest} tier. When completion-sync is disabled the validator is a no-op, so the
 * shipped default profile + test tiers (which carry the valid default template) all boot.
 */
@Configuration
public class LinearCompletionSyncConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(LinearCompletionSyncConfiguration.class);

  /**
   * Validate the completion-sync template at startup and return a marker bean recording the
   * outcome. Throws {@code DomainException(INVALID_COMPLETION_TEMPLATE)} (failing boot) when {@code
   * enabled} and the template is invalid.
   */
  @Bean
  public LinearCompletionTemplateValidation linearCompletionTemplateValidation(
      WorkflowProperties workflowProperties) {
    WorkflowProperties.LinearCompletionSync config = workflowProperties.linearCompletionSync();
    if (!config.enabled()) {
      log.info(
          "linear completion-sync disabled (deliveryline.workflow.linear-completion-sync.enabled="
              + "false); template validation skipped");
      return new LinearCompletionTemplateValidation(false, true);
    }
    LinearCompletionTemplate.validate(config.template());
    log.info(
        "linear completion-sync enabled; template validated placeholders={}",
        LinearCompletionTemplate.placeholdersIn(config.template()));
    return new LinearCompletionTemplateValidation(true, true);
  }

  /** Marker recording that the completion-sync template was validated at startup. */
  public record LinearCompletionTemplateValidation(boolean enabled, boolean validated) {}
}
