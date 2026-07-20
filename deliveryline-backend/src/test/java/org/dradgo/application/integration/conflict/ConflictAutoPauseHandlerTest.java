package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationConflictCategory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** Story 4.18 (AC4/AC5) — the conflict-driven auto-pause handler. */
class ConflictAutoPauseHandlerTest {

  private static final String RUN = "run_abc123";
  private static final String CONFLICT = "icf_abc123";

  @SuppressWarnings("unchecked")
  private static ObjectProvider<RecoveryService> providerOf(RecoveryService recoveryService) {
    ObjectProvider<RecoveryService> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(recoveryService);
    return provider;
  }

  private static IntegrationConflictDetectionProperties withCategories(List<String> categories) {
    return new IntegrationConflictDetectionProperties(false, 0L, 0, categories);
  }

  @Test
  void highSeverityConfiguredCategoryPausesWithSystemActorAndDeterministicKey() {
    RecoveryService recoveryService = mock(RecoveryService.class);
    ConflictAutoPauseHandler handler =
        new ConflictAutoPauseHandler(
            providerOf(recoveryService),
            withCategories(List.of("external_state_advanced", "external_state_reverted")));

    handler.maybeAutoPause(
        RUN, CONFLICT, IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED, "corr-1");

    ArgumentCaptor<ActorContext> actor = ArgumentCaptor.forClass(ActorContext.class);
    verify(recoveryService)
        .pause(
            eq(RUN),
            // conflict id's '_' separator is sanitized to '-' so the key matches the opaque-key
            // pattern [A-Za-z0-9-]{16,128}.
            eq("autopause-conflict-icf-abc123"),
            actor.capture(),
            eq("auto_paused_on_state_conflict"));
    assertThat(actor.getValue().actorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(actor.getValue().actorIdentity()).isEqualTo("system");
    assertThat(actor.getValue().correlationId()).isEqualTo("corr-1");
  }

  @Test
  void excludedCategoryDoesNotPause() {
    RecoveryService recoveryService = mock(RecoveryService.class);
    ConflictAutoPauseHandler handler =
        new ConflictAutoPauseHandler(
            providerOf(recoveryService),
            withCategories(List.of("external_state_advanced", "external_state_reverted")));

    // metadata_drift is not in the configured set.
    handler.maybeAutoPause(RUN, CONFLICT, IntegrationConflictCategory.METADATA_DRIFT, null);

    verify(recoveryService, never()).pause(anyString(), anyString(), any(), anyString());
  }

  @Test
  void emptyConfigOptsOutOfAutoPauseEntirely() {
    RecoveryService recoveryService = mock(RecoveryService.class);
    ConflictAutoPauseHandler handler =
        new ConflictAutoPauseHandler(providerOf(recoveryService), withCategories(List.of()));

    handler.maybeAutoPause(
        RUN, CONFLICT, IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED, null);

    verify(recoveryService, never()).pause(anyString(), anyString(), any(), anyString());
  }

  @Test
  void pauseNotApplicableIsSwallowed() {
    RecoveryService recoveryService = mock(RecoveryService.class);
    when(recoveryService.pause(anyString(), anyString(), any(), anyString()))
        .thenThrow(
            new DomainException(
                DomainErrorCode.PAUSE_NOT_APPLICABLE, "run is terminal", java.util.Map.of()));
    ConflictAutoPauseHandler handler =
        new ConflictAutoPauseHandler(
            providerOf(recoveryService), withCategories(List.of("external_state_advanced")));

    assertThatCode(
            () ->
                handler.maybeAutoPause(
                    RUN, CONFLICT, IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED, null))
        .doesNotThrowAnyException();
    verify(recoveryService).pause(anyString(), anyString(), any(), anyString());
  }

  @Test
  void unexpectedDomainErrorIsSwallowed() {
    RecoveryService recoveryService = mock(RecoveryService.class);
    when(recoveryService.pause(anyString(), anyString(), any(), anyString()))
        .thenThrow(new DomainException(DomainErrorCode.INTERNAL_ERROR, "boom", java.util.Map.of()));
    ConflictAutoPauseHandler handler =
        new ConflictAutoPauseHandler(
            providerOf(recoveryService), withCategories(List.of("external_state_advanced")));

    assertThatCode(
            () ->
                handler.maybeAutoPause(
                    RUN, CONFLICT, IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED, null))
        .doesNotThrowAnyException();
  }

  @Test
  void unknownConfiguredTokenIsSkippedAtConstructionAndNeverPausesForIt() {
    RecoveryService recoveryService = mock(RecoveryService.class);
    // "not_a_category" is skipped; only external_state_advanced remains configured.
    ConflictAutoPauseHandler handler =
        new ConflictAutoPauseHandler(
            providerOf(recoveryService),
            withCategories(List.of("not_a_category", "external_state_advanced")));

    handler.maybeAutoPause(
        RUN, CONFLICT, IntegrationConflictCategory.EXTERNAL_STATE_ADVANCED, null);
    verify(recoveryService).pause(anyString(), anyString(), any(), anyString());
  }
}
