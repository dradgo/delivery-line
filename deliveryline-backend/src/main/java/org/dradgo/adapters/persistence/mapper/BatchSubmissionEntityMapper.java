package org.dradgo.adapters.persistence.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.dradgo.adapters.persistence.entity.BatchSubmissionEntity;
import org.dradgo.application.workflow.BatchSubmissionResult;
import org.dradgo.application.workflow.TicketBatchResult;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Maps {@link BatchSubmissionEntity} ↔ application types for story 3.18. The per-ticket outcome
 * list is (de)serialized to/from the {@code result_json} column here so the persistence adapter
 * stays focused on row I/O and constraint mapping.
 */
@Component
public class BatchSubmissionEntityMapper {

  private final ObjectMapper objectMapper;

  // Mirror WorkflowCommandOutputs: this app does not expose a directly-injectable ObjectMapper
  // bean,
  // so resolve via ObjectProvider with a self-built fallback.
  public BatchSubmissionEntityMapper(ObjectProvider<ObjectMapper> objectMapperProvider) {
    this.objectMapper =
        objectMapperProvider.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
  }

  public String serializeTickets(List<TicketBatchResult> tickets) {
    try {
      return objectMapper.writeValueAsString(tickets);
    } catch (JsonProcessingException error) {
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR, "Failed to serialize batch ticket outcomes", error);
    }
  }

  public BatchSubmissionResult toResult(BatchSubmissionEntity entity) {
    return new BatchSubmissionResult(
        entity.getPublicId(),
        entity.getCreatedAt(),
        entity.getActorIdentity(),
        entity.getTotal(),
        entity.getQueuedCount(),
        entity.getRejectedCount(),
        deserializeTickets(entity.getResultJson()));
  }

  private List<TicketBatchResult> deserializeTickets(String resultJson) {
    try {
      return List.of(objectMapper.readValue(resultJson, TicketBatchResult[].class));
    } catch (JsonProcessingException error) {
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR, "Failed to deserialize batch ticket outcomes", error);
    }
  }
}
