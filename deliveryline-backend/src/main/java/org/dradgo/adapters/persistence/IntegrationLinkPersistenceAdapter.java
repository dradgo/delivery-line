package org.dradgo.adapters.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.dradgo.adapters.persistence.entity.IntegrationLinkEntity;
import org.dradgo.adapters.persistence.entity.WorkflowRunEntity;
import org.dradgo.adapters.persistence.mapper.IntegrationLinkEntityMapper;
import org.dradgo.adapters.persistence.repository.IntegrationLinkRepository;
import org.dradgo.adapters.persistence.repository.WorkflowRunRepository;
import org.dradgo.application.integration.IntegrationLink;
import org.dradgo.application.integration.IntegrationLinkStateMachine;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort;
import org.dradgo.application.integration.spi.IntegrationLinkRecordPort.TicketSummaryProjection;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationSyncStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-adapter implementation of {@link IntegrationLinkRecordPort}. Wraps Spring Data JPA
 * with V1 / V6 constraint awareness — unique violations on {@code integration_links} are translated
 * to {@code INTEGRATION_LINK_CONFLICT} regardless of whether they originate from the V1 (same
 * ticket, same run) or V6 partial (same ticket, any active run) index.
 *
 * <p>State-machine guards ({@link IntegrationLinkStateMachine}) reject illegal {@code sync_status}
 * transitions before they reach the DB.
 *
 * <p>{@code external_metadata} is exposed as opaque bytes through the SPI; this adapter converts
 * to/from a Hibernate-managed {@code Map} via Jackson at the boundary. Application callers must
 * apply redaction before building the byte array (story 1.14 Task 5).
 */
@Component
public class IntegrationLinkPersistenceAdapter implements IntegrationLinkRecordPort {

  private static final Logger log =
      LoggerFactory.getLogger(IntegrationLinkPersistenceAdapter.class);

  private static final TypeReference<Map<String, Object>> METADATA_TYPE =
      new TypeReference<Map<String, Object>>() {};

  /** 64KB — mirrors the V1 {@code ck_integration_links_external_metadata_size} CHECK. */
  private static final int EXTERNAL_METADATA_MAX_BYTES = 65_536 - 1;

  private final IntegrationLinkRepository integrationLinkRepository;
  private final WorkflowRunRepository workflowRunRepository;
  private final IntegrationLinkEntityMapper mapper;
  private final ObjectMapper objectMapper;

  @Autowired
  public IntegrationLinkPersistenceAdapter(
      IntegrationLinkRepository integrationLinkRepository,
      WorkflowRunRepository workflowRunRepository,
      IntegrationLinkEntityMapper mapper) {
    this(integrationLinkRepository, workflowRunRepository, mapper, new ObjectMapper());
  }

  IntegrationLinkPersistenceAdapter(
      IntegrationLinkRepository integrationLinkRepository,
      WorkflowRunRepository workflowRunRepository,
      IntegrationLinkEntityMapper mapper,
      ObjectMapper objectMapper) {
    this.integrationLinkRepository =
        Objects.requireNonNull(integrationLinkRepository, "integrationLinkRepository");
    this.workflowRunRepository =
        Objects.requireNonNull(workflowRunRepository, "workflowRunRepository");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  public IntegrationLink insert(NewIntegrationLink request) {
    Objects.requireNonNull(request, "request");
    PublicIdPrefixes.require(request.publicId(), PublicIdPrefixes.INTEGRATION_LINK);
    PublicIdPrefixes.require(request.workflowRunPublicId(), PublicIdPrefixes.WORKFLOW_RUN);
    if (request.integrationType() == null || request.integrationType().isBlank()) {
      throw new IllegalArgumentException("integrationType must be non-blank");
    }
    if (request.externalRef() == null || request.externalRef().isBlank()) {
      throw new IllegalArgumentException("externalRef must be non-blank");
    }
    Objects.requireNonNull(request.createdAt(), "createdAt");

    byte[] metadataBytes =
        request.externalMetadata() == null ? new byte[0] : request.externalMetadata();
    if (metadataBytes.length > EXTERNAL_METADATA_MAX_BYTES) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("integrationLinkId", request.publicId());
      details.put("externalMetadataBytes", metadataBytes.length);
      details.put("maxBytes", EXTERNAL_METADATA_MAX_BYTES);
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "integration_links.external_metadata exceeds the V1 64KB ceiling",
          details);
    }

    WorkflowRunEntity workflowRun =
        workflowRunRepository
            .findByPublicId(request.workflowRunPublicId())
            .orElseThrow(() -> runNotFound(request.workflowRunPublicId()));

    IntegrationLinkEntity entity = new IntegrationLinkEntity();
    entity.setPublicId(request.publicId());
    entity.setWorkflowRun(workflowRun);
    entity.setIntegrationType(request.integrationType());
    entity.setExternalRef(request.externalRef());
    entity.setExternalMetadata(decodeMetadata(metadataBytes));
    entity.setLastSyncAt(
        request.lastSyncAt() == null
            ? null
            : OffsetDateTime.ofInstant(request.lastSyncAt(), ZoneOffset.UTC));
    entity.setSyncStatus(IntegrationSyncStatus.LINKED);

    IntegrationLinkEntity persisted;
    try {
      persisted = integrationLinkRepository.saveAndFlush(entity);
    } catch (DataIntegrityViolationException collision) {
      // F19 from story 1.15 review: externalRef is permitted in this log line because
      // CLI render also surfaces it (allow-list parity); the redaction layout (story
      // 1.19 AC5) scrubs any embedded secret-shaped substring. P3 (story 1.19 review):
      // sanitize at the call site so CR/LF/TAB in attacker-supplied ticket refs cannot
      // forge a synthetic log line.
      log.warn(
          "insert conflict integrationType={} externalRef={} workflowRunId={} cause={}",
          request.integrationType(),
          MdcKeys.sanitizeForLog(request.externalRef()),
          request.workflowRunPublicId(),
          collision.getMostSpecificCause().getClass().getSimpleName());
      throw conflict(request, collision);
    }
    log.info(
        "persisting integration_link publicId={} integrationType={} workflowRunId={}",
        persisted.getPublicId(),
        persisted.getIntegrationType(),
        persisted.getWorkflowRun().getPublicId());
    return mapper.toDomain(persisted);
  }

  @Override
  public Optional<IntegrationLink> findByPublicId(String publicId) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.INTEGRATION_LINK);
    return integrationLinkRepository.findByPublicId(publicId).map(mapper::toDomain);
  }

  @Override
  public Optional<IntegrationLink> findActiveByTypeAndExternalRef(
      String integrationType, String externalRef) {
    validateLookupArgs(integrationType, externalRef);
    return integrationLinkRepository
        .findActiveByTypeAndExternalRef(integrationType, externalRef)
        .map(mapper::toDomain);
  }

  @Override
  public Optional<IntegrationLink> findActiveByTypeAndExternalRefForUpdate(
      String integrationType, String externalRef) {
    validateLookupArgs(integrationType, externalRef);
    return integrationLinkRepository
        .findActiveByTypeAndExternalRefForUpdate(integrationType, externalRef)
        .map(mapper::toDomain);
  }

  @Override
  public Optional<IntegrationLink> findActiveByWorkflowRun(String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    return integrationLinkRepository
        .findFirstActiveByWorkflowRunPublicId(workflowRunPublicId)
        .map(mapper::toDomain);
  }

  @Override
  public Optional<IntegrationLink> findActiveByTypeAndWorkflowRunForUpdate(
      String integrationType, String workflowRunPublicId) {
    if (integrationType == null || integrationType.isBlank()) {
      throw new IllegalArgumentException("integrationType must be non-blank");
    }
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    return integrationLinkRepository
        .findActiveByTypeAndWorkflowRunForUpdate(integrationType, workflowRunPublicId)
        .map(mapper::toDomain);
  }

  @Override
  public Optional<TicketSummaryProjection> findActiveTicketSummaryByWorkflowRun(
      String workflowRunPublicId) {
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    return integrationLinkRepository
        .findFirstActiveByWorkflowRunPublicId(workflowRunPublicId)
        .map(
            entity ->
                new TicketSummaryProjection(
                    entity.getExternalRef(), encodeMetadata(entity.getExternalMetadata())));
  }

  @Override
  public Optional<TicketSummaryProjection> findActiveTicketSummaryByTypeAndWorkflowRun(
      String integrationType, String workflowRunPublicId) {
    if (integrationType == null || integrationType.isBlank()) {
      throw new IllegalArgumentException("integrationType must be non-blank");
    }
    PublicIdPrefixes.require(workflowRunPublicId, PublicIdPrefixes.WORKFLOW_RUN);
    return integrationLinkRepository
        .findActiveByTypeAndWorkflowRunPublicId(integrationType, workflowRunPublicId)
        .stream()
        .findFirst()
        .map(
            entity ->
                new TicketSummaryProjection(
                    entity.getExternalRef(), encodeMetadata(entity.getExternalMetadata())));
  }

  private byte[] encodeMetadata(Map<String, Object> metadata) {
    if (metadata == null || metadata.isEmpty()) {
      return new byte[0];
    }
    try {
      return objectMapper.writeValueAsBytes(metadata);
    } catch (java.io.IOException error) {
      // Shouldn't happen for a Hibernate-managed Map; treat as recoverable empty.
      log.warn(
          "findActiveTicketSummaryByWorkflowRun metadata_serialize_failed workflowRunId={}",
          workflowRunPublicId(metadata));
      return new byte[0];
    }
  }

  private static String workflowRunPublicId(Map<String, Object> metadata) {
    Object value = metadata.get("workflowRunPublicId");
    return value == null ? "unknown" : value.toString();
  }

  @Override
  public IntegrationLink updateSyncStatus(
      String publicId, IntegrationSyncStatus newStatus, Instant lastSyncAt) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.INTEGRATION_LINK);
    Objects.requireNonNull(newStatus, "newStatus");
    IntegrationLinkEntity entity =
        integrationLinkRepository
            .findByPublicId(publicId)
            .orElseThrow(() -> integrationLinkNotFound(publicId));
    IntegrationSyncStatus current = entity.getSyncStatus();
    IntegrationLinkStateMachine.assertCanTransition(publicId, current, newStatus);
    entity.setSyncStatus(newStatus);
    if (lastSyncAt != null) {
      entity.setLastSyncAt(OffsetDateTime.ofInstant(lastSyncAt, ZoneOffset.UTC));
    }
    IntegrationLinkEntity persisted = integrationLinkRepository.saveAndFlush(entity);
    log.info(
        "integration_link transitioned publicId={} from={} to={}",
        publicId,
        current.value(),
        newStatus.value());
    return mapper.toDomain(persisted);
  }

  @Override
  public IntegrationLink updateExternalMetadataAndSync(
      String publicId,
      byte[] externalMetadata,
      IntegrationSyncStatus newStatus,
      Instant lastSyncAt) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.INTEGRATION_LINK);
    Objects.requireNonNull(newStatus, "newStatus");
    byte[] metadataBytes = externalMetadata == null ? new byte[0] : externalMetadata;
    if (metadataBytes.length > EXTERNAL_METADATA_MAX_BYTES) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("integrationLinkId", publicId);
      details.put("externalMetadataBytes", metadataBytes.length);
      details.put("maxBytes", EXTERNAL_METADATA_MAX_BYTES);
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "integration_links.external_metadata exceeds the V1 64KB ceiling",
          details);
    }
    IntegrationLinkEntity entity =
        integrationLinkRepository
            .findByPublicId(publicId)
            .orElseThrow(() -> integrationLinkNotFound(publicId));
    IntegrationSyncStatus current = entity.getSyncStatus();
    IntegrationLinkStateMachine.assertCanTransition(publicId, current, newStatus);
    entity.setExternalMetadata(decodeMetadata(metadataBytes));
    entity.setSyncStatus(newStatus);
    if (lastSyncAt != null) {
      entity.setLastSyncAt(OffsetDateTime.ofInstant(lastSyncAt, ZoneOffset.UTC));
    }
    IntegrationLinkEntity persisted = integrationLinkRepository.saveAndFlush(entity);
    log.info(
        "integration_link metadata refreshed publicId={} from={} to={}",
        publicId,
        current.value(),
        newStatus.value());
    return mapper.toDomain(persisted);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Instant> findMaxLastSyncAtForType(String integrationType) {
    if (integrationType == null || integrationType.isBlank()) {
      throw new IllegalArgumentException("integrationType must be non-blank");
    }
    return integrationLinkRepository
        .findMaxLastSyncAtForType(integrationType)
        .map(OffsetDateTime::toInstant);
  }

  @Override
  @Transactional
  public boolean touchLastSyncAtByTypeAndExternalRef(
      String integrationType, String externalRef, Instant lastSyncAt) {
    validateLookupArgs(integrationType, externalRef);
    Objects.requireNonNull(lastSyncAt, "lastSyncAt");
    int updated =
        integrationLinkRepository.touchLastSyncAtByTypeAndExternalRef(
            integrationType, externalRef, OffsetDateTime.ofInstant(lastSyncAt, ZoneOffset.UTC));
    return updated > 0;
  }

  @Override
  public IntegrationLink markArchived(String publicId, Instant archivedAt) {
    PublicIdPrefixes.require(publicId, PublicIdPrefixes.INTEGRATION_LINK);
    Objects.requireNonNull(archivedAt, "archivedAt");
    IntegrationLinkEntity entity =
        integrationLinkRepository
            .findByPublicId(publicId)
            .orElseThrow(() -> integrationLinkNotFound(publicId));
    entity.setArchivedAt(OffsetDateTime.ofInstant(archivedAt, ZoneOffset.UTC));
    IntegrationLinkEntity persisted = integrationLinkRepository.saveAndFlush(entity);
    log.info(
        "integration_link archived publicId={} archivedAt={}", publicId, persisted.getArchivedAt());
    return mapper.toDomain(persisted);
  }

  private static void validateLookupArgs(String integrationType, String externalRef) {
    if (integrationType == null || integrationType.isBlank()) {
      throw new IllegalArgumentException("integrationType must be non-blank");
    }
    if (externalRef == null || externalRef.isBlank()) {
      throw new IllegalArgumentException("externalRef must be non-blank");
    }
  }

  private Map<String, Object> decodeMetadata(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(bytes, METADATA_TYPE);
    } catch (java.io.IOException cause) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "external_metadata_unparseable");
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "integration_links.external_metadata is not valid JSON",
          details);
    }
  }

  private static DomainException conflict(
      NewIntegrationLink request, DataIntegrityViolationException cause) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("integrationLinkId", request.publicId());
    details.put("integrationType", request.integrationType());
    details.put("externalRef", request.externalRef());
    details.put("workflowRunId", request.workflowRunPublicId());
    String message =
        cause.getMostSpecificCause() == null ? null : cause.getMostSpecificCause().getMessage();
    if (message != null) {
      if (message.contains("uq_integration_links_active_linear_ref")) {
        details.put("constraint", "uq_integration_links_active_linear_ref");
        details.put("reason", "cross_run_active_linear_link_exists");
      } else if (message.contains("uq_integration_links_type_external_ref_run_id")) {
        details.put("constraint", "uq_integration_links_type_external_ref_run_id");
        details.put("reason", "same_run_link_already_exists");
      } else if (message.contains("uq_integration_links_public_id")) {
        details.put("constraint", "uq_integration_links_public_id");
        details.put("reason", "public_id_collision");
      } else {
        details.put("reason", "integration_link_constraint_violation");
      }
    } else {
      details.put("reason", "integration_link_constraint_violation");
    }
    return new DomainException(
        DomainErrorCode.INTEGRATION_LINK_CONFLICT,
        "Integration link conflict for " + request.integrationType() + ":" + request.externalRef(),
        details);
  }

  private static DomainException integrationLinkNotFound(String publicId) {
    // Reuses INTERNAL_ERROR because the SPI's mutator methods are only called after the service
    // layer has already located the row — a missing row at this point indicates an invariant
    // break, not a normal "not found" surface for callers. Story 1.14 deliberately does not add
    // an INTEGRATION_LINK_NOT_FOUND code (the externally-facing not-found is
    // LINEAR_TICKET_NOT_FOUND
    // at the Linear-adapter boundary, AC7).
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("integrationLinkId", publicId);
    details.put("reason", "integration_link_record_missing");
    return new DomainException(
        DomainErrorCode.INTERNAL_ERROR, "Integration link not found: " + publicId, details);
  }

  private static DomainException runNotFound(String workflowRunPublicId) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunPublicId);
    return new DomainException(
        DomainErrorCode.RUN_NOT_FOUND, "Workflow run not found: " + workflowRunPublicId, details);
  }
}
