package org.dradgo.application.integration.conflict;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import org.dradgo.application.integration.conflict.spi.IntegrationConflictReadPort;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.Test;

/** Story 4.17 (AC6, Reconciliation 11) — filter validation for the conflict read surface. */
class IntegrationConflictServiceTest {

  private final IntegrationConflictReadPort readPort = mock(IntegrationConflictReadPort.class);
  private final IntegrationConflictService service = new IntegrationConflictService(readPort);

  @Test
  void unfilteredQueryDelegatesToReadPort() {
    when(readPort.listUnresolved(any())).thenReturn(List.of());
    assertThat(service.listUnresolvedConflicts(ConflictFilter.unfiltered())).isEmpty();
    verify(readPort).listUnresolved(any());
  }

  @Test
  void validCategoryAndTypeFilterPasses() {
    when(readPort.listUnresolved(any())).thenReturn(List.of());
    ConflictFilter filter =
        new ConflictFilter("link_broken", "github_pr", Duration.ofHours(1), "run_x", "octo/repo#1");
    service.listUnresolvedConflicts(filter);
    verify(readPort).listUnresolved(any());
  }

  @Test
  void unknownCategoryIsRejectedWithInvalidCommandPayload() {
    ConflictFilter filter = new ConflictFilter("not_a_category", null, null, null, null);
    assertThatThrownBy(() -> service.listUnresolvedConflicts(filter))
        .isInstanceOf(DomainException.class)
        .satisfies(
            e ->
                assertThat(((DomainException) e).errorCode())
                    .isEqualTo(DomainErrorCode.INVALID_COMMAND_PAYLOAD));
    verify(readPort, never()).listUnresolved(any());
  }

  @Test
  void unknownIntegrationTypeIsRejected() {
    ConflictFilter filter = new ConflictFilter(null, "gitlab", null, null, null);
    assertThatThrownBy(() -> service.listUnresolvedConflicts(filter))
        .isInstanceOf(DomainException.class);
    verify(readPort, never()).listUnresolved(any());
  }

  @Test
  void negativeTimeSinceIsRejected() {
    ConflictFilter filter = new ConflictFilter(null, null, Duration.ofSeconds(-5), null, null);
    assertThatThrownBy(() -> service.listUnresolvedConflicts(filter))
        .isInstanceOf(DomainException.class);
  }
}
