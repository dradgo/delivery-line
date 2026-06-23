package org.dradgo.application.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.IdempotencyService;
import org.dradgo.application.idempotency.IdempotencyService.ReservationDecision;
import org.dradgo.application.idempotency.IdempotencyService.ReservationOutcome;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.workflow.commands.ArchiveRunCommand;
import org.dradgo.application.workflow.commands.UnarchiveRunCommand;
import org.dradgo.application.workflow.spi.WorkflowEventRecord;
import org.dradgo.application.workflow.spi.WorkflowEventWritePort;
import org.dradgo.application.workflow.spi.WorkflowRunArchivePort;
import org.dradgo.application.workflow.spi.WorkflowRunReadPort;
import org.dradgo.application.workflow.spi.WorkflowRunSnapshot;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.id.PublicIdPrefixes;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IdempotencyRecordStatus;
import org.dradgo.domain.registry.WorkflowEventDetailKeys;
import org.dradgo.domain.registry.WorkflowEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Story 3d-8 (FR67, ADR 0027) — governed, reversible soft-hide of an obsolete workflow run.
 *
 * <p>Hiding a run sets {@code workflow_runs.archived_at} and appends a {@code workflow.archived}
 * audit event; un-hiding clears the marker and appends {@code workflow.unarchived}. Neither
 * operation deletes any row or mutates {@code workflow_events} (FR47 append-only), and neither
 * changes {@code current_state} — archiving is orthogonal to the lifecycle (event {@code priorState
 * == resultingState}, {@code interventionMarker = true}). The child rows
 * (artifacts/runner_executions/integration_links) are a view/scope concern: hiding the run scopes
 * them out of the default queue for free, so this service never touches their own {@code
 * archived_at} (ADR 0027 D1).
 *
 * <p>This is a dedicated service rather than a method on {@code WorkflowCommandService} because the
 * archive commands are not sealed {@code WorkflowCommand} variants and never route through the
 * state-transition idempotency engine. Idempotency uses the generic {@link IdempotencyService}
 * directly: the fingerprint is a SHA-256 over {@code commandType + actorIdentity +
 * workflowRunPublicId} (reason excluded, so a same-key replay is stable), and a replay re-reads the
 * run to return the prior result without appending a second event.
 */
@Service
public class WorkflowArchiveService {

  private static final Logger log = LoggerFactory.getLogger(WorkflowArchiveService.class);

  static final String COMMAND_ARCHIVE_RUN = "archive_run";
  static final String COMMAND_UNARCHIVE_RUN = "unarchive_run";

  private final WorkflowRunReadPort workflowRunReadPort;
  private final WorkflowRunArchivePort workflowRunArchivePort;
  private final WorkflowEventWritePort workflowEventWritePort;
  private final IdempotencyService idempotencyService;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public WorkflowArchiveService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowRunArchivePort workflowRunArchivePort,
      WorkflowEventWritePort workflowEventWritePort,
      IdempotencyService idempotencyService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      PlatformTransactionManager transactionManager) {
    this(
        workflowRunReadPort,
        workflowRunArchivePort,
        workflowEventWritePort,
        idempotencyService,
        idempotencyKeyValidator,
        new TransactionTemplate(transactionManager),
        Clock.systemUTC());
  }

  WorkflowArchiveService(
      WorkflowRunReadPort workflowRunReadPort,
      WorkflowRunArchivePort workflowRunArchivePort,
      WorkflowEventWritePort workflowEventWritePort,
      IdempotencyService idempotencyService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.workflowRunReadPort = Objects.requireNonNull(workflowRunReadPort, "workflowRunReadPort");
    this.workflowRunArchivePort =
        Objects.requireNonNull(workflowRunArchivePort, "workflowRunArchivePort");
    this.workflowEventWritePort =
        Objects.requireNonNull(workflowEventWritePort, "workflowEventWritePort");
    this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService");
    this.idempotencyKeyValidator =
        Objects.requireNonNull(idempotencyKeyValidator, "idempotencyKeyValidator");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public WorkflowArchiveResult archiveRun(ArchiveRunCommand command) {
    Objects.requireNonNull(command, "command");
    PublicIdPrefixes.require(command.workflowRunPublicId(), PublicIdPrefixes.WORKFLOW_RUN);
    String key = idempotencyKeyValidator.requireValid(command.idempotencyKey());
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunPublicId());
    try {
      String fingerprint =
          fingerprint(COMMAND_ARCHIVE_RUN, command.actorIdentity(), command.workflowRunPublicId());
      ReservationOutcome outcome =
          idempotencyService.checkAndReserve(
              key, COMMAND_ARCHIVE_RUN, command.actorIdentity(), fingerprint);
      if (outcome.decision() == ReservationDecision.REPLAY) {
        log.info(
            "workflow archive idempotent replay workflowRunId={} idempotencyKey={}",
            command.workflowRunPublicId(),
            key);
        return loadReplay(command.workflowRunPublicId(), command.correlationId());
      }
      try {
        WorkflowArchiveResult result = transactionTemplate.execute(status -> doArchive(command));
        idempotencyService.complete(
            key, command.workflowRunPublicId(), IdempotencyRecordStatus.COMPLETED);
        return result;
      } catch (RuntimeException error) {
        completeFailed(key, error);
        throw error;
      }
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  public WorkflowArchiveResult unarchiveRun(UnarchiveRunCommand command) {
    Objects.requireNonNull(command, "command");
    PublicIdPrefixes.require(command.workflowRunPublicId(), PublicIdPrefixes.WORKFLOW_RUN);
    String key = idempotencyKeyValidator.requireValid(command.idempotencyKey());
    String priorRunMdc = MdcKeys.beginScope(MdcKeys.WORKFLOW_RUN_ID, command.workflowRunPublicId());
    try {
      String fingerprint =
          fingerprint(
              COMMAND_UNARCHIVE_RUN, command.actorIdentity(), command.workflowRunPublicId());
      ReservationOutcome outcome =
          idempotencyService.checkAndReserve(
              key, COMMAND_UNARCHIVE_RUN, command.actorIdentity(), fingerprint);
      if (outcome.decision() == ReservationDecision.REPLAY) {
        log.info(
            "workflow unarchive idempotent replay workflowRunId={} idempotencyKey={}",
            command.workflowRunPublicId(),
            key);
        return loadReplay(command.workflowRunPublicId(), command.correlationId());
      }
      try {
        WorkflowArchiveResult result = transactionTemplate.execute(status -> doUnarchive(command));
        idempotencyService.complete(
            key, command.workflowRunPublicId(), IdempotencyRecordStatus.COMPLETED);
        return result;
      } catch (RuntimeException error) {
        completeFailed(key, error);
        throw error;
      }
    } finally {
      MdcKeys.endScope(MdcKeys.WORKFLOW_RUN_ID, priorRunMdc);
    }
  }

  private WorkflowArchiveResult doArchive(ArchiveRunCommand command) {
    WorkflowRunSnapshot run = requireRun(command.workflowRunPublicId(), "archive");
    if (run.archivedAt() != null) {
      log.warn(
          "workflow archive rejected workflowRunId={} reason=already_archived",
          command.workflowRunPublicId());
      throw archiveNotApplicable(
          command.workflowRunPublicId(),
          "already_archived",
          "Workflow run is already archived: " + command.workflowRunPublicId());
    }
    Instant archivedAt = clock.instant();
    workflowRunArchivePort.markArchived(command.workflowRunPublicId(), archivedAt);
    OffsetDateTime archivedAtOffset = archivedAt.atOffset(ZoneOffset.UTC);
    appendArchiveEvent(
        WorkflowEventType.WORKFLOW_ARCHIVED,
        run,
        command.actorIdentity(),
        command.actorType(),
        command.idempotencyKey(),
        command.correlationId(),
        command.reason(),
        archivedAtOffset);
    log.info(
        "workflow archived workflowRunId={} actor={} reason={}",
        command.workflowRunPublicId(),
        MdcKeys.sanitizeForLog(command.actorIdentity()),
        MdcKeys.sanitizeForLog(command.reason()));
    return new WorkflowArchiveResult(
        run.publicId(),
        run.currentState(),
        archivedAtOffset,
        normalize(command.correlationId()),
        false);
  }

  private WorkflowArchiveResult doUnarchive(UnarchiveRunCommand command) {
    WorkflowRunSnapshot run = requireRun(command.workflowRunPublicId(), "unarchive");
    if (run.archivedAt() == null) {
      log.warn(
          "workflow unarchive rejected workflowRunId={} reason=not_archived",
          command.workflowRunPublicId());
      throw archiveNotApplicable(
          command.workflowRunPublicId(),
          "not_archived",
          "Workflow run is not archived: " + command.workflowRunPublicId());
    }
    workflowRunArchivePort.clearArchived(command.workflowRunPublicId());
    appendArchiveEvent(
        WorkflowEventType.WORKFLOW_UNARCHIVED,
        run,
        command.actorIdentity(),
        command.actorType(),
        command.idempotencyKey(),
        command.correlationId(),
        command.reason(),
        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
    log.info(
        "workflow unarchived workflowRunId={} actor={} reason={}",
        command.workflowRunPublicId(),
        MdcKeys.sanitizeForLog(command.actorIdentity()),
        MdcKeys.sanitizeForLog(command.reason()));
    return new WorkflowArchiveResult(
        run.publicId(), run.currentState(), null, normalize(command.correlationId()), false);
  }

  private void appendArchiveEvent(
      WorkflowEventType eventType,
      WorkflowRunSnapshot run,
      String actorIdentity,
      org.dradgo.domain.registry.ActorType actorType,
      String idempotencyKey,
      String correlationId,
      String reason,
      OffsetDateTime createdAt) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put(WorkflowEventDetailKeys.IDEMPOTENCY_KEY, idempotencyKey);
    String normalizedCorrelation = normalize(correlationId);
    if (normalizedCorrelation != null) {
      details.put(WorkflowEventDetailKeys.CORRELATION_ID, normalizedCorrelation);
    }
    String normalizedReason = normalize(reason);
    if (normalizedReason != null) {
      details.put(WorkflowEventDetailKeys.REASON, normalizedReason);
    }
    // Archiving does NOT change current_state: priorState == resultingState == the run's current
    // state, interventionMarker = true (a governed human/system triage action). No FailureCategory.
    workflowEventWritePort.append(
        new WorkflowEventRecord(
            PublicIdPrefixes.WORKFLOW_EVENT.next(),
            run.publicId(),
            eventType,
            run.currentState(),
            run.currentState(),
            actorIdentity,
            actorType,
            normalizedReason,
            null,
            true,
            createdAt,
            details));
  }

  private WorkflowArchiveResult loadReplay(String workflowRunPublicId, String correlationId) {
    WorkflowRunSnapshot run = requireRun(workflowRunPublicId, "archive");
    return new WorkflowArchiveResult(
        run.publicId(), run.currentState(), run.archivedAt(), normalize(correlationId), true);
  }

  private WorkflowRunSnapshot requireRun(String workflowRunPublicId, String operation) {
    return workflowRunReadPort
        .findByPublicId(workflowRunPublicId)
        .orElseThrow(
            () -> {
              log.warn(
                  "workflow {} rejected workflowRunId={} reason=run_not_found",
                  operation,
                  workflowRunPublicId);
              return new DomainException(
                  DomainErrorCode.RUN_NOT_FOUND,
                  "Workflow run not found: " + workflowRunPublicId,
                  Map.of("runId", workflowRunPublicId));
            });
  }

  private void completeFailed(String key, RuntimeException original) {
    try {
      idempotencyService.complete(key, null, IdempotencyRecordStatus.FAILED);
    } catch (RuntimeException completionError) {
      original.addSuppressed(completionError);
    }
  }

  private static DomainException archiveNotApplicable(
      String workflowRunPublicId, String reason, String message) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("runId", workflowRunPublicId);
    details.put("reason", reason);
    return new DomainException(DomainErrorCode.ARCHIVE_NOT_APPLICABLE, message, details);
  }

  // ASCII Unit Separator (U+001F) is disallowed in actor identities + run public ids, so the
  // joined canonical string is unambiguous (mirrors WorkflowCommandService's replay-ref separator).
  private static final String FINGERPRINT_SEPARATOR = "\u001f";

  private static String fingerprint(
      String commandType, String actorIdentity, String workflowRunPublicId) {
    String canonical =
        String.join(
            FINGERPRINT_SEPARATOR,
            commandType,
            nullSafe(actorIdentity),
            nullSafe(workflowRunPublicId));
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 digest is unavailable", error);
    }
  }

  private static String nullSafe(String value) {
    return value == null ? "" : value;
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
