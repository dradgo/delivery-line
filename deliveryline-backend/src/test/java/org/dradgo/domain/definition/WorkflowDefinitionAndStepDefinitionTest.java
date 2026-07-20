package org.dradgo.domain.definition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.OffsetDateTime;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ArtifactKind;
import org.dradgo.domain.registry.DefinitionKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Story 3m-2 (AC9) code-review patch — the compact-constructor invariants on the two new domain
 * records were entirely unexercised (both records had zero references and zero tests, yet Task 3
 * was marked done). These guards are the domain half of the V48 CHECK constraints: the DB rejects
 * blank / negative rows so they are never persistable, and these records reject them so a
 * hand-built value fails synchronously and attributably rather than at flush time.
 */
class WorkflowDefinitionAndStepDefinitionTest {

  private static final String DEF_ID = "wfd_abcd1234";
  private static final String STEP_ID = "wfs_abcd1234";

  // --- WorkflowDefinition -------------------------------------------------

  @Test
  void workflowDefinitionAcceptsAValidValue() {
    WorkflowDefinition definition =
        new WorkflowDefinition(DEF_ID, "bmad-method", "BMAD Method", DefinitionKind.BUILTIN, null);

    assertThat(definition.key()).isEqualTo("bmad-method");
    assertThat(definition.kind()).isEqualTo(DefinitionKind.BUILTIN);
    assertThat(definition.archivedAt()).isNull();
  }

  @Test
  void workflowDefinitionAcceptsAnArchivedTimestamp() {
    OffsetDateTime archivedAt = OffsetDateTime.parse("2026-07-20T00:00:00Z");

    assertThatCode(
            () ->
                new WorkflowDefinition(
                    DEF_ID, "retired", "Retired", DefinitionKind.CUSTOM, archivedAt))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", "   "})
  void workflowDefinitionRejectsBlankKey(String blank) {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new WorkflowDefinition(DEF_ID, blank, "Name", DefinitionKind.BUILTIN, null))
        .withMessageContaining("key");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", "   "})
  void workflowDefinitionRejectsBlankName(String blank) {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new WorkflowDefinition(DEF_ID, "key", blank, DefinitionKind.BUILTIN, null))
        .withMessageContaining("name");
  }

  @Test
  void workflowDefinitionRejectsNullKind() {
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> new WorkflowDefinition(DEF_ID, "key", "Name", null, null));
  }

  @Test
  void workflowDefinitionRejectsAForeignPublicIdPrefix() {
    // The publicId guard routes through PublicIdPrefixes.require, which raises a typed
    // DomainException(INVALID_ID_PREFIX) rather than an IllegalArgumentException.
    assertThatExceptionOfType(DomainException.class)
        .isThrownBy(
            () ->
                new WorkflowDefinition(
                    "wfs_abcd1234", "key", "Name", DefinitionKind.BUILTIN, null));
  }

  // --- StepDefinition -----------------------------------------------------

  @Test
  void stepDefinitionAcceptsAValidValue() {
    StepDefinition step =
        new StepDefinition(
            STEP_ID, 1L, 0, "analyst", "claude", "analyst", true, ArtifactKind.BRIEF);

    assertThat(step.stepIndex()).isZero();
    assertThat(step.humanGated()).isTrue();
    assertThat(step.producesArtifactKind()).isEqualTo(ArtifactKind.BRIEF);
  }

  @Test
  void stepDefinitionAcceptsNullOptionalBindingsMeaningNoExecutorAndNoTypedArtifact() {
    assertThatCode(() -> new StepDefinition(STEP_ID, 1L, 3, "custom-key", null, null, false, null))
        .doesNotThrowAnyException();
  }

  @Test
  void stepDefinitionRejectsNullDefinitionId() {
    // definition_id is `not null` in V48 — guard it here so a missing parent fails synchronously
    // rather than as an opaque DataIntegrityViolationException at flush time.
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> new StepDefinition(STEP_ID, null, 0, "analyst", null, null, false, null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t", "   "})
  void stepDefinitionRejectsBlankStepKey(String blank) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StepDefinition(STEP_ID, 1L, 0, blank, null, null, false, null))
        .withMessageContaining("stepKey");
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, -42, Integer.MIN_VALUE})
  void stepDefinitionRejectsNegativeStepIndex(int negative) {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new StepDefinition(STEP_ID, 1L, negative, "analyst", null, null, false, null))
        .withMessageContaining("stepIndex");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  void stepDefinitionRejectsBlankRunnerKindWhenSet(String blank) {
    // DD-1 leaves runner_kind free text with no DB CHECK, so a blank would otherwise persist and
    // then make RunnerKind.fromValue("") throw at 3m-4 bind time instead of reading as "no
    // binding".
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StepDefinition(STEP_ID, 1L, 0, "analyst", blank, null, false, null))
        .withMessageContaining("runnerKind");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  void stepDefinitionRejectsBlankBmadRoleWhenSet(String blank) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new StepDefinition(STEP_ID, 1L, 0, "analyst", null, blank, false, null))
        .withMessageContaining("bmadRole");
  }

  @Test
  void stepDefinitionRejectsAForeignPublicIdPrefix() {
    assertThatExceptionOfType(DomainException.class)
        .isThrownBy(() -> new StepDefinition(DEF_ID, 1L, 0, "analyst", null, null, false, null));
  }
}
