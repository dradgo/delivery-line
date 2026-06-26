package org.dradgo.application.integration.ticketsource;

import java.util.Objects;
import org.dradgo.application.project.ProjectConnectorResolver;
import org.dradgo.domain.integration.ticketsource.CreateSubticketResult;
import org.dradgo.domain.integration.ticketsource.SubticketDraft;
import org.dradgo.domain.integration.ticketsource.TicketRef;
import org.dradgo.domain.project.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Capability-gated application seam for source sub-ticket creation. */
@Service
public class TicketSourceSubticketService {

  private static final Logger log = LoggerFactory.getLogger(TicketSourceSubticketService.class);

  private final ProjectConnectorResolver projectConnectorResolver;

  public TicketSourceSubticketService(ProjectConnectorResolver projectConnectorResolver) {
    this.projectConnectorResolver =
        Objects.requireNonNull(projectConnectorResolver, "projectConnectorResolver");
  }

  public TicketSourceSubticketOutcome createSubticketIfSupported(
      Project project, TicketRef parentRef, SubticketDraft draft) {
    Objects.requireNonNull(project, "project");
    Objects.requireNonNull(parentRef, "parentRef");
    Objects.requireNonNull(draft, "draft");
    log.info(
        "ticket_source_subticket start projectId={} workflowRunId={} parentTicketRef={} subtaskOrdinal={} idempotencyKey={}",
        project.publicId(),
        draft.parentRunId(),
        parentRef.value(),
        draft.ordinal(),
        draft.idempotencyKey());
    return projectConnectorResolver
        .findTicketSource(project)
        .map(adapter -> createIfCapable(adapter, parentRef, draft))
        .orElseGet(
            () -> {
              log.info(
                  "ticket_source_subticket skipped projectId={} workflowRunId={} parentTicketRef={} subtaskOrdinal={} reason=no_ticket_source",
                  project.publicId(),
                  draft.parentRunId(),
                  parentRef.value(),
                  draft.ordinal());
              return TicketSourceSubticketOutcome.noTicketSource();
            });
  }

  private TicketSourceSubticketOutcome createIfCapable(
      TicketSourceAdapter adapter, TicketRef parentRef, SubticketDraft draft) {
    boolean supported = adapter.getCapabilities().supportsTicketCreation();
    if (!supported) {
      log.info(
          "ticket_source_subticket skipped workflowRunId={} parentTicketRef={} subtaskOrdinal={} idempotencyKey={} reason=creation_not_supported",
          draft.parentRunId(),
          parentRef.value(),
          draft.ordinal(),
          draft.idempotencyKey());
      return TicketSourceSubticketOutcome.internalOnlySkipped();
    }
    CreateSubticketResult result = adapter.createSubticket(parentRef, draft);
    log.info(
        "ticket_source_subticket created workflowRunId={} parentTicketRef={} childTicketRef={} subtaskOrdinal={} idempotencyKey={} replay={}",
        draft.parentRunId(),
        parentRef.value(),
        result.childRef().value(),
        draft.ordinal(),
        draft.idempotencyKey(),
        result.replay());
    return TicketSourceSubticketOutcome.created(result);
  }
}
