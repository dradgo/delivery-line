package org.dradgo.application.integration.ticketsource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.integration.ticketsource.TicketSourceCapabilities;
import org.dradgo.domain.project.Project;
import org.dradgo.domain.registry.ConnectorKind;
import org.dradgo.domain.registry.ProjectStatus;
import org.junit.jupiter.api.Test;

class TicketSourceSubticketServiceTest {

  @Test
  void falseCapabilityAdapterReturnsInternalOnlyAndDoesNotCallCreate() {
    ProjectConnectorResolver resolver = mock(ProjectConnectorResolver.class);
    TicketSourceAdapter adapter = mock(TicketSourceAdapter.class);
    when(adapter.getCapabilities())
        .thenReturn(TicketSourceCapabilities.noCreation(true, true, true));
    Project project = project();
    when(resolver.findTicketSource(project)).thenReturn(Optional.of(adapter));
    TicketSourceSubticketService service = new TicketSourceSubticketService(resolver);

    TicketSourceSubticketOutcome outcome =
        service.createSubticketIfSupported(
            project,
            TicketRef.of("LIN-10"),
            new SubticketDraft(
                "run_parent01",
                "proposal_01",
                "subtask_01",
                1,
                "Safe redacted title",
                "Safe redacted scope",
                "split:run_parent01:proposal_01:1"));

    assertEquals(TicketSourceSubticketOutcome.Status.INTERNAL_ONLY_SKIPPED, outcome.status());
    verify(adapter, never()).createSubticket(any(), any());
  }

  @Test
  void supportedAdapterDelegatesAndWrapsCreatedResult() {
    ProjectConnectorResolver resolver = mock(ProjectConnectorResolver.class);
    TicketSourceAdapter adapter = mock(TicketSourceAdapter.class);
    when(adapter.getCapabilities()).thenReturn(TicketSourceCapabilities.linearDefaults());
    Project project = project();
    SubticketDraft draft =
        new SubticketDraft(
            "run_parent01",
            "proposal_01",
            "subtask_01",
            1,
            "Safe redacted title",
            "Safe redacted scope",
            "split:run_parent01:proposal_01:1");
    CreateSubticketResult result =
        new CreateSubticketResult(
            TicketRef.of("LIN-11"),
            draft.idempotencyKey(),
            "fp-subticket-1",
            false,
            java.util.Map.of());
    when(resolver.findTicketSource(project)).thenReturn(Optional.of(adapter));
    when(adapter.createSubticket(TicketRef.of("LIN-10"), draft)).thenReturn(result);
    TicketSourceSubticketService service = new TicketSourceSubticketService(resolver);

    TicketSourceSubticketOutcome outcome =
        service.createSubticketIfSupported(project, TicketRef.of("LIN-10"), draft);

    assertEquals(TicketSourceSubticketOutcome.Status.CREATED, outcome.status());
    assertEquals(Optional.of(result), outcome.result());
    verify(adapter).createSubticket(TicketRef.of("LIN-10"), draft);
  }

  private static Project project() {
    return new Project(
        "prj_default0001",
        "Default",
        "default",
        ProjectStatus.ACTIVE,
        null,
        ConnectorKind.LINEAR,
        ConnectorKind.GITHUB,
        false,
        null,
        false,
        null,
        OffsetDateTime.parse("2026-06-20T00:00:00Z"),
        null);
  }
}
