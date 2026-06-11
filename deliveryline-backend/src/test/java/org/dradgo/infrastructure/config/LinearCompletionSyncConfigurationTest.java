package org.dradgo.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.dradgo.application.workflow.WorkflowProperties;
import org.dradgo.application.workflow.WorkflowProperties.Bot;
import org.dradgo.application.workflow.WorkflowProperties.LinearCompletionSync;
import org.dradgo.application.workflow.WorkflowProperties.RepoConfig;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;

/** Story 3.16 (AC8) — the startup completion-sync template validator bean. */
class LinearCompletionSyncConfigurationTest {

  private final LinearCompletionSyncConfiguration configuration =
      new LinearCompletionSyncConfiguration();

  @Test
  void enabledWithValidDefaultTemplatePassesValidation() {
    var result = configuration.linearCompletionTemplateValidation(WorkflowProperties.defaults());
    assertThat(result.enabled()).isTrue();
    assertThat(result.validated()).isTrue();
  }

  @Test
  void disabledSkipsTemplateValidationEvenForAnInvalidTemplate() {
    WorkflowProperties props =
        new WorkflowProperties(
            Bot.empty(), RepoConfig.empty(), new LinearCompletionSync(false, "no placeholders"));
    // Disabled: an invalid template must NOT fail startup.
    var result = configuration.linearCompletionTemplateValidation(props);
    assertThat(result.enabled()).isFalse();
  }

  @Test
  void enabledWithInvalidTemplateFailsStartupWithInvalidCompletionTemplate() {
    WorkflowProperties props =
        new WorkflowProperties(
            Bot.empty(),
            RepoConfig.empty(),
            new LinearCompletionSync(true, "no placeholders here"));
    assertThatThrownBy(() -> configuration.linearCompletionTemplateValidation(props))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).errorCode())
        .isEqualTo(DomainErrorCode.INVALID_COMPLETION_TEMPLATE);
  }
}
