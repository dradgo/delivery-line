package org.dradgo.adapters.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.DeveloperTakeoverService;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.RetryRecoveryResult;
import org.dradgo.application.recovery.TakeoverResult;
import org.dradgo.application.runner.RunnerLogReference;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.BatchSubmissionResult;
import org.dradgo.application.workflow.ManualArtifactSubmissionService;
import org.dradgo.application.workflow.ManualArtifactSubmissionService.ManualArtifactSubmissionCommand;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.TicketBatchResult;
import org.dradgo.application.workflow.WorkflowArchiveResult;
import org.dradgo.application.workflow.WorkflowArchiveService;
import org.dradgo.application.workflow.WorkflowBatchSubmissionService;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ContextBundleLookupResult;
import org.dradgo.application.workflow.WorkflowInspectionService.ManualBundleLookupResult;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerLogReferenceResult;
import org.dradgo.application.workflow.WorkflowInspectionService.SpecHistoryEntry;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.application.workflow.WorkflowOrchestrationService;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.AcceptImplementationCommand;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.ArchiveRunCommand;
import org.dradgo.application.workflow.commands.RejectImplementationCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.SubmitBatchCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
import org.dradgo.application.workflow.commands.TakeoverWorkflowCommand;
import org.dradgo.application.workflow.commands.UnarchiveRunCommand;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.RejectionTaxonomy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

@Component
@CommandGroup(name = "workflow", description = "Workflow commands", prefix = "deliveryline")
public class WorkflowCommands {

  private static final Logger log = LoggerFactory.getLogger(WorkflowCommands.class);

  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_FAILURE_PREFIX = "failure:";
  private static final String OUTCOME_UNKNOWN = "failure:unknown";
  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_JSON = "json";
  private static final int STATUS_WITH_CONTEXT_BUNDLE_SCHEMA_VERSION = 2;

  private final WorkflowCommandService workflowCommandService;
  private final WorkflowInspectionService workflowInspectionService;
  private final WorkflowCommandOutputs outputs;
  private final BooleanSupplier interactivityDetector;
  private final Supplier<String> generatedKeySupplier;
  private final Supplier<String> correlationIdSupplier;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final RecoveryService recoveryService;
  private final ApprovalReviewerRoleResolver approvalReviewerRoleResolver;
  private final LocalActorIdentityResolver localActorIdentityResolver;
  // Story 3.16 (AC5) — manual completion-sync retry path. Nullable in the legacy/inspection-less
  // test constructors; the sync-completion command guards via requireOrchestrationWired().
  private final WorkflowOrchestrationService workflowOrchestrationService;
  // Story 3.18 — batch submission. Nullable in the legacy/inspection-less test constructors; the
  // submit-batch command guards via requireBatchWired().
  private final WorkflowBatchSubmissionService workflowBatchSubmissionService;
  // Story 3.25 — the RICH developer-takeover service (story 3.22). Nullable in the
  // legacy/inspection-less test constructors; the takeover command guards via
  // requireTakeoverWired().
  private final DeveloperTakeoverService developerTakeoverService;
  // Story 3d-8 — governed soft-hide / un-hide. Nullable in the legacy/inspection-less test
  // constructors; the archive/unarchive commands guard via requireArchiveWired().
  private final WorkflowArchiveService workflowArchiveService;
  // Story 3d-4 — operator manual-artifact submission. Nullable in the legacy/inspection-less test
  // constructors; the manual-artifact command guards via requireManualSubmissionWired().
  private final ManualArtifactSubmissionService manualArtifactSubmissionService;

  @Autowired
  public WorkflowCommands(
      WorkflowCommandService workflowCommandService,
      WorkflowInspectionService workflowInspectionService,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector cliInteractivityDetector,
      UuidV7Generator uuidV7Generator,
      IdempotencyKeyValidator idempotencyKeyValidator,
      RecoveryService recoveryService,
      ApprovalReviewerRoleResolver approvalReviewerRoleResolver,
      LocalActorIdentityResolver localActorIdentityResolver,
      WorkflowOrchestrationService workflowOrchestrationService,
      WorkflowBatchSubmissionService workflowBatchSubmissionService,
      DeveloperTakeoverService developerTakeoverService,
      WorkflowArchiveService workflowArchiveService,
      ManualArtifactSubmissionService manualArtifactSubmissionService) {
    this(
        workflowCommandService,
        workflowInspectionService,
        outputs,
        cliInteractivityDetector::isInteractive,
        uuidV7Generator::generate,
        uuidV7Generator::generate,
        idempotencyKeyValidator,
        recoveryService,
        approvalReviewerRoleResolver,
        localActorIdentityResolver,
        workflowOrchestrationService,
        workflowBatchSubmissionService,
        developerTakeoverService,
        workflowArchiveService,
        manualArtifactSubmissionService);
  }

  /**
   * Three-arg constructor kept for backward compatibility with the existing {@link
   * org.dradgo.adapters.cli.WorkflowCommandsTest} unit tests. The {@code submit}-only path does not
   * need the inspection, recovery, actor-identity-resolver, or orchestration services, so callers
   * can pass nulls as long as they only invoke {@link #submit}.
   */
  public WorkflowCommands(
      WorkflowCommandService workflowCommandService,
      BooleanSupplier interactivityDetector,
      Supplier<String> generatedKeySupplier) {
    this(
        workflowCommandService,
        null,
        null,
        interactivityDetector,
        generatedKeySupplier,
        generatedKeySupplier,
        new IdempotencyKeyValidator(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public WorkflowCommands(
      WorkflowCommandService workflowCommandService,
      WorkflowInspectionService workflowInspectionService,
      WorkflowCommandOutputs outputs,
      BooleanSupplier interactivityDetector,
      Supplier<String> generatedKeySupplier,
      Supplier<String> correlationIdSupplier,
      IdempotencyKeyValidator idempotencyKeyValidator,
      RecoveryService recoveryService,
      ApprovalReviewerRoleResolver approvalReviewerRoleResolver,
      LocalActorIdentityResolver localActorIdentityResolver,
      WorkflowOrchestrationService workflowOrchestrationService,
      WorkflowBatchSubmissionService workflowBatchSubmissionService,
      DeveloperTakeoverService developerTakeoverService,
      WorkflowArchiveService workflowArchiveService,
      ManualArtifactSubmissionService manualArtifactSubmissionService) {
    this.workflowCommandService = workflowCommandService;
    this.workflowInspectionService = workflowInspectionService;
    this.outputs = outputs;
    this.interactivityDetector = interactivityDetector;
    this.generatedKeySupplier = generatedKeySupplier;
    this.correlationIdSupplier = correlationIdSupplier;
    this.idempotencyKeyValidator = idempotencyKeyValidator;
    this.recoveryService = recoveryService;
    this.approvalReviewerRoleResolver = approvalReviewerRoleResolver;
    this.localActorIdentityResolver = localActorIdentityResolver;
    this.workflowOrchestrationService = workflowOrchestrationService;
    this.workflowBatchSubmissionService = workflowBatchSubmissionService;
    this.developerTakeoverService = developerTakeoverService;
    this.workflowArchiveService = workflowArchiveService;
    this.manualArtifactSubmissionService = manualArtifactSubmissionService;
  }

  /**
   * Backward-compatible constructor (pre story 3d-8). Delegates with a {@code null}
   * WorkflowArchiveService; callers that exercise {@code archive}/{@code unarchive} must use the
   * full constructor (the commands guard via {@link #requireArchiveWired()}).
   */
  public WorkflowCommands(
      WorkflowCommandService workflowCommandService,
      WorkflowInspectionService workflowInspectionService,
      WorkflowCommandOutputs outputs,
      BooleanSupplier interactivityDetector,
      Supplier<String> generatedKeySupplier,
      Supplier<String> correlationIdSupplier,
      IdempotencyKeyValidator idempotencyKeyValidator,
      RecoveryService recoveryService,
      ApprovalReviewerRoleResolver approvalReviewerRoleResolver,
      LocalActorIdentityResolver localActorIdentityResolver,
      WorkflowOrchestrationService workflowOrchestrationService,
      WorkflowBatchSubmissionService workflowBatchSubmissionService,
      DeveloperTakeoverService developerTakeoverService) {
    this(
        workflowCommandService,
        workflowInspectionService,
        outputs,
        interactivityDetector,
        generatedKeySupplier,
        correlationIdSupplier,
        idempotencyKeyValidator,
        recoveryService,
        approvalReviewerRoleResolver,
        localActorIdentityResolver,
        workflowOrchestrationService,
        workflowBatchSubmissionService,
        developerTakeoverService,
        null,
        null);
  }

  @Command(
      name = "submit",
      description = "Submit a workflow ticket for governed execution",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String submit(
      @Option(longName = "ticket", description = "Linear ticket reference", required = true)
          String linearTicketReference,
      @Option(longName = "actor-identity", description = "Actor identity", required = true)
          String actorIdentity,
      @Option(longName = "actor-type", description = "Actor type", required = true)
          ActorType actorType,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "project",
              description =
                  "Project reference (slug or prj_ public id); default project if omitted",
              required = false)
          String projectReference,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    String runId = null;
    try {
      String resolvedIdempotencyKey = resolveIdempotencyKey(idempotencyKey);
      SubmitWorkflowResult result =
          workflowCommandService.submit(
              new SubmitWorkflowCommand(
                  actorIdentity,
                  actorType,
                  resolvedIdempotencyKey,
                  correlationId,
                  linearTicketReference,
                  projectReference));
      runId = result.workflowRunId();
      String output =
          result.workflowRunId() + " submitted (state: " + result.currentState().value() + ")";
      if (idempotencyKey == null) {
        output += " [generated-idempotency-key: " + resolvedIdempotencyKey + "]";
      }
      if (verbose) {
        output += " [correlation-id: " + resolvedCorrelation + "]";
      }
      emitSuccess("workflow submit", runId, resolvedCorrelation, start);
      return output;
    } catch (DomainException de) {
      emitFailure("workflow submit", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow submit", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "submit-batch",
      description =
          "Submit a batch of Linear tickets for governed execution (best-effort: one rejected"
              + " ticket does not fail the batch). Provide exactly one of --tickets / --from-file.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String submitBatch(
      @Option(longName = "tickets", description = "Comma-separated Linear ticket references")
          String tickets,
      @Option(longName = "from-file", description = "File of ticket references (one per line)")
          String fromFile,
      @Option(longName = "actor-identity", description = "Actor identity") String actorIdentity,
      @Option(longName = "actor-type", description = "Actor type", defaultValue = "HUMAN")
          ActorType actorType,
      @Option(longName = "idempotency-key", description = "Batch-level idempotency key")
          String idempotencyKey,
      @Option(longName = "correlation-id", description = "Batch correlation ID")
          String correlationId,
      @Option(
              longName = "project",
              description =
                  "Project reference (slug or prj_ public id) bound to every ticket in the batch;"
                      + " default project if omitted")
          String projectReference,
      @Option(
              longName = "exit-on-any-rejection",
              description = "Exit non-zero when any ticket is rejected",
              defaultValue = "false")
          boolean exitOnAnyRejection) {
    requireBatchWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    String batchId = null;
    try {
      List<String> ticketRefs = parseBatchTickets(tickets, fromFile);
      String resolvedActorIdentity = resolveActorIdentity(actorIdentity);
      String resolvedIdempotencyKey = resolveIdempotencyKey(idempotencyKey);
      BatchSubmissionResult result =
          workflowBatchSubmissionService.submitBatch(
              new SubmitBatchCommand(
                  ticketRefs,
                  resolvedActorIdentity,
                  actorType,
                  resolvedIdempotencyKey,
                  correlationId,
                  projectReference));
      batchId = result.batchId();
      String table = renderBatchTable(result);
      emitSuccess("workflow submit-batch", batchId, resolvedCorrelation, start);
      if (exitOnAnyRejection && result.rejectedCount() > 0) {
        // OQ-2: signal a non-zero exit in strict mode by throwing a CLI-band DomainException whose
        // message carries the full table (the exit mapper prints it). No new DomainErrorCode is
        // introduced (Decision D-NOCODE); the rejections are already itemized in the table above.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("batchId", batchId);
        details.put("rejectedCount", result.rejectedCount());
        details.put("queuedCount", result.queuedCount());
        throw new DomainException(
            DomainErrorCode.INVALID_COMMAND_PAYLOAD,
            "\n"
                + table
                + "\nbatch "
                + batchId
                + " completed with "
                + result.rejectedCount()
                + " rejected ticket(s); --exit-on-any-rejection set",
            details);
      }
      String output = table;
      if (idempotencyKey == null) {
        output += "\n[generated-idempotency-key: " + resolvedIdempotencyKey + "]";
      }
      return output;
    } catch (DomainException de) {
      emitFailure("workflow submit-batch", batchId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow submit-batch", batchId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  /**
   * Parse the ticket list from exactly one of {@code --tickets} (CSV) or {@code --from-file} (one
   * ref per line; blank lines and {@code #}-comment lines are ignored). Supplying neither or both
   * raises {@code INVALID_COMMAND_PAYLOAD}.
   */
  private List<String> parseBatchTickets(String tickets, String fromFile) {
    boolean hasTickets = tickets != null && !tickets.isBlank();
    boolean hasFile = fromFile != null && !fromFile.isBlank();
    if (hasTickets == hasFile) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("rule", "provide exactly one of --tickets / --from-file");
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD,
          "Provide exactly one of --tickets or --from-file",
          details);
    }
    List<String> refs = new ArrayList<>();
    if (hasTickets) {
      for (String token : tickets.split(",")) {
        String trimmed = token.trim();
        if (!trimmed.isEmpty()) {
          refs.add(trimmed);
        }
      }
    } else {
      for (String line : readTicketFile(fromFile)) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
          refs.add(trimmed);
        }
      }
    }
    return refs;
  }

  private List<String> readTicketFile(String fromFile) {
    try {
      return Files.readAllLines(Path.of(fromFile), StandardCharsets.UTF_8);
    } catch (IOException error) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("file", fromFile);
      throw new DomainException(
          DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Cannot read --from-file: " + fromFile, details);
    }
  }

  /** Render the {@code Ticket | Run ID | Outcome | Reason} batch result table. */
  private String renderBatchTable(BatchSubmissionResult result) {
    StringBuilder out = new StringBuilder();
    out.append("batch ")
        .append(result.batchId())
        .append(" (total ")
        .append(result.total())
        .append(", queued ")
        .append(result.queuedCount())
        .append(", rejected ")
        .append(result.rejectedCount())
        .append(")\n");
    out.append("Ticket | Run ID | Outcome | Reason\n");
    for (TicketBatchResult ticket : result.tickets()) {
      out.append(escapeCell(ticket.ticketRef()))
          .append(" | ")
          .append(ticket.runId() == null ? "-" : escapeCell(ticket.runId()))
          .append(" | ")
          .append(escapeCell(ticket.queueResult()))
          .append(" | ")
          .append(ticket.rejectionReason() == null ? "-" : escapeCell(ticket.rejectionReason()))
          .append('\n');
    }
    if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
      out.setLength(out.length() - 1);
    }
    return out.toString();
  }

  private static String escapeCell(String value) {
    if (value == null) {
      return "-";
    }
    return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
  }

  private void requireBatchWired() {
    if (workflowBatchSubmissionService == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_submit_batch_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed without WorkflowBatchSubmissionService; inject it to use"
              + " submit-batch",
          details);
    }
  }

  @Command(
      name = "sync-completion",
      description =
          "Re-post the merge-ready completion summary back to a governed run's source Linear ticket"
              + " (story 3.16). Idempotent — re-running posts at most once per canonical summary"
              + " (Linear comment-deduplication). Best-effort: a post failure is recorded as an"
              + " event and never fails the run.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String syncCompletion(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    requireOrchestrationWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      WorkflowOrchestrationService.SyncCompletionOutcome outcome =
          workflowOrchestrationService.syncCompletionToLinear(runId);
      if (outcome == WorkflowOrchestrationService.SyncCompletionOutcome.POST_FAILED) {
        // The post failure is already recorded as a linear.completionSyncFailed event; surface a
        // non-zero CLI exit so a manual-retry operator sees it failed. Best-effort applies to the
        // governed run (never rolled back) — not to the manual command's exit code (story 3.16
        // review 2026-06-11). Rethrown below → emitFailure + exit-status mapper.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "linear_completion_sync_post_failed");
        throw new DomainException(
            DomainErrorCode.INTERNAL_ERROR,
            "completion-sync post to Linear failed; recorded as a linear.completionSyncFailed event"
                + " — inspect the run history and retry",
            details);
      }
      StringBuilder output =
          new StringBuilder().append(runId).append(' ').append(describeSyncOutcome(outcome));
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow sync-completion", runId, resolvedCorrelation, start);
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow sync-completion", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow sync-completion", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "status",
      description = "Print the current state of a governed workflow run",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String status(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              required = false,
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose,
      @Option(
              longName = "include-context-bundle",
              description =
                  "Append the latest spec-stage context bundle to the output (FR55 inspection)",
              required = false,
              defaultValue = "false")
          boolean includeContextBundle,
      @Option(
              longName = "include-runner-logs",
              description =
                  "Append the latest runner execution's redacted log reference (story 3.6 inspection)",
              required = false,
              defaultValue = "false")
          boolean includeRunnerLogs) {
    requireInspectionWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      WorkflowStatusView view = workflowInspectionService.getStatus(runId);
      String rendered = renderStatus(view, format);
      if (includeContextBundle) {
        rendered = appendContextBundle(rendered, view, runId, format);
      }
      if (includeRunnerLogs) {
        rendered = appendRunnerLogs(rendered, view, runId, format);
      }
      if (verbose) {
        rendered = appendCorrelationSuffix(rendered, resolvedCorrelation);
      }
      emitSuccess("workflow status", runId, resolvedCorrelation, start);
      return rendered;
    } catch (DomainException de) {
      emitFailure("workflow status", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow status", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "retry",
      description =
          "Retry the last failed step of a Failed governed workflow run. Deeper recovery (reconcile, take over, rerun-from-arbitrary-step, failure-taxonomy classification, operator console) arrives in a later epic.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String retry(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(longName = "actor-identity", description = "Actor identity", required = true)
          String actorIdentity,
      @Option(longName = "actor-type", description = "Actor type", required = true)
          ActorType actorType,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "reason",
              description = "Operator-supplied reason text (optional)",
              required = false)
          String reason,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    requireRecoveryWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      ActorContext actor = new ActorContext(actorIdentity, actorType, resolvedCorrelation);
      RetryRecoveryResult result =
          recoveryService.retry(runId, resolvedIdempotencyKey, actor, reason);
      StringBuilder output = new StringBuilder();
      output.append(result.recoveryActionPublicId()).append(" retry submitted (state: Executing)");
      if (result.newRunnerExecutionPublicId() != null) {
        output
            .append(" [runner-execution: ")
            .append(result.newRunnerExecutionPublicId())
            .append(']');
      } else if (result.replayed()) {
        output.append(" [replayed]");
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
        if (result.recoveryRetriedEventPublicId() != null) {
          output
              .append(" [recovery-event: ")
              .append(result.recoveryRetriedEventPublicId())
              .append(']');
        }
        if (idempotencyKey == null) {
          output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
        }
      }
      emitSuccess("workflow retry", runId, resolvedCorrelation, start);
      if (reason != null && !reason.isBlank()) {
        log.info(
            "workflow retry operator reason supplied correlationId={} workflowRunId={} reasonLength={}",
            resolvedCorrelation,
            runId,
            reason.length());
      }
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow retry", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow retry", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "takeover",
      description =
          "Take over a governed workflow run for manual developer continuation (story 3.25 —"
              + " CLI/REST equivalence). Stops orchestrator dispatch, cancels in-flight + queued"
              + " runner executions, and transitions the run to the TakenOver terminal state while"
              + " preserving all prior context (artifacts, audit trail, GitHub PR link). This action"
              + " is non-reversible in E3.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String takeover(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "reason",
              description = "Why the takeover is needed (required, free-form)",
              required = true)
          String reason,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    // Story 3.25 R4: takeover is a developer/HUMAN action. The CLI mirrors accept-implementation's
    // HUMAN-only audit posture (optional --actor-identity resolved with local-operator fallback,
    // ActorType.HUMAN hard-coded). NO --actor-type, NO --reviewer-role (the service hard-codes the
    // developer reviewer role; reviewer_role is the takeover invariant, not a command field).
    // --reason is required because the rich service mandates a non-blank reasonText.
    requireTakeoverWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      TakeoverResult result =
          developerTakeoverService.takeoverWorkflow(
              new TakeoverWorkflowCommand(
                  runId,
                  resolvedActor,
                  ActorType.HUMAN,
                  resolvedIdempotencyKey,
                  resolvedCorrelation,
                  reason));
      StringBuilder output =
          new StringBuilder()
              .append(result.recoveryActionPublicId())
              .append(" takeover submitted (state: ")
              .append(result.resultingState().value())
              .append(")");
      if (result.cancelledInFlightCount() != null) {
        output
            .append(" [cancelled-in-flight: ")
            .append(result.cancelledInFlightCount())
            .append(']');
      }
      if (result.cancelledQueuedCount() != null) {
        output.append(" [cancelled-queued: ").append(result.cancelledQueuedCount()).append(']');
      }
      if (result.preservedPrReference() != null) {
        output.append(" [pr: ").append(result.preservedPrReference()).append(']');
      }
      if (result.replayed()) {
        output.append(" [replayed]");
      }
      if (idempotencyKey == null) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow takeover", runId, resolvedCorrelation, start);
      // Audit the reason length only — the free-form developer prose is never logged verbatim
      // (mirrors retry's reason-handling).
      log.info(
          "workflow takeover reason supplied correlationId={} workflowRunId={} reasonLength={}",
          resolvedCorrelation,
          runId,
          reason.length());
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow takeover", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow takeover", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  // Story 3d-4 (AC1) — read-only retrieval of a parked run's manual input bundle (CLI parity with
  // GET /manual-bundle). Writes the redacted bundle bytes to stdout (or --out file). No
  // Idempotency-Key (idempotent read).
  @Command(
      name = "manual-bundle",
      description =
          "Get the parked manual step's redacted runner-contracts input bundle (story 3d-4). Run"
              + " not parked ⇒ MANUAL_EXECUTION_NOT_APPLICABLE; scratch evicted ⇒ a typed"
              + " bundleNotPersisted state.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String manualBundle(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "out",
              description = "Write the bundle bytes to this file instead of stdout",
              required = false)
          String outFile,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId) {
    requireManualSubmissionWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      ManualBundleLookupResult result = workflowInspectionService.getManualBundle(runId);
      if (!result.available()) {
        emitSuccess("workflow manual-bundle", runId, resolvedCorrelation, start);
        return result.runnerExecutionId() + " bundle unavailable (" + result.reason() + ")";
      }
      byte[] bytes = result.bundle().redactedPayload();
      if (outFile != null && !outFile.isBlank()) {
        try {
          Files.write(Path.of(outFile), bytes);
        } catch (IOException error) {
          Map<String, Object> details = new LinkedHashMap<>();
          details.put("outFile", outFile);
          throw new DomainException(
              DomainErrorCode.INTERNAL_ERROR,
              "Unable to write bundle to file: " + outFile,
              details);
        }
        emitSuccess("workflow manual-bundle", runId, resolvedCorrelation, start);
        return result.runnerExecutionId()
            + " bundle written to "
            + outFile
            + " ("
            + bytes.length
            + " bytes)";
      }
      emitSuccess("workflow manual-bundle", runId, resolvedCorrelation, start);
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (DomainException de) {
      emitFailure("workflow manual-bundle", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow manual-bundle", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  // Story 3d-4 (AC2) — governed submission of a manually-produced artifact (CLI parity with POST
  // /manual-artifact). Reads the runner-result-shaped JSON from --file; runs the SAME validation +
  // ingest the REST endpoint does (both delegate to ManualArtifactSubmissionService).
  @Command(
      name = "manual-artifact",
      description =
          "Submit a manually-produced artifact for a parked run (story 3d-4). Reads the"
              + " runner-result-shaped JSON from --file and re-enters the same validation/review"
              + " pipeline as an automated runner.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String manualArtifact(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "file",
              description = "Path to the runner-result-shaped JSON artifact payload",
              required = true)
          String file,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    requireManualSubmissionWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      byte[] payloadBytes;
      try {
        payloadBytes = Files.readAllBytes(Path.of(file));
      } catch (IOException error) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("file", file);
        throw new DomainException(
            DomainErrorCode.INVALID_COMMAND_PAYLOAD,
            "Unable to read artifact file: " + file,
            details);
      }
      WorkflowStateChangeResult result =
          manualArtifactSubmissionService.submit(
              new ManualArtifactSubmissionCommand(
                  runId,
                  payloadBytes,
                  Map.of(),
                  resolvedIdempotencyKey,
                  resolvedActor,
                  ActorType.HUMAN,
                  resolvedCorrelation));
      StringBuilder output =
          new StringBuilder()
              .append(result.workflowRunId())
              .append(" manual artifact submitted (state: ")
              .append(result.currentState().value())
              .append(")");
      if (idempotencyKey == null) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow manual-artifact", runId, resolvedCorrelation, start);
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow manual-artifact", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow manual-artifact", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  private void requireManualSubmissionWired() {
    if (manualArtifactSubmissionService == null || workflowInspectionService == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_manual_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed without ManualArtifactSubmissionService; inject it to use manual-bundle/manual-artifact",
          details);
    }
  }

  @Command(
      name = "archive",
      description =
          "Soft-hide (archive) an obsolete governed workflow run (story 3d-8). Sets the archived_at"
              + " marker + appends a governed workflow.archived event; reversible via unarchive."
              + " Never deletes any row or mutates history (FR47).",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String archive(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "reason",
              description = "Why the run is being hidden (required, free-form)",
              required = true)
          String reason,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    requireArchiveWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      WorkflowArchiveResult result =
          workflowArchiveService.archiveRun(
              new ArchiveRunCommand(
                  runId,
                  resolvedActor,
                  ActorType.HUMAN,
                  resolvedIdempotencyKey,
                  resolvedCorrelation,
                  reason));
      StringBuilder output = new StringBuilder().append(runId).append(" archived");
      if (result.replay()) {
        output.append(" [replayed]");
      }
      if (idempotencyKey == null) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow archive", runId, resolvedCorrelation, start);
      log.info(
          "workflow archive reason supplied correlationId={} workflowRunId={} reasonLength={}",
          resolvedCorrelation,
          runId,
          reason.length());
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow archive", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow archive", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "unarchive",
      description =
          "Reverse a soft-hide (un-archive) of a governed workflow run (story 3d-8). Clears the"
              + " archived_at marker + appends a governed workflow.unarchived event, returning the"
              + " run to the default review queue.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String unarchive(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "reason",
              description = "Why the run is being un-hidden (optional, free-form)",
              required = false)
          String reason,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    requireArchiveWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      WorkflowArchiveResult result =
          workflowArchiveService.unarchiveRun(
              new UnarchiveRunCommand(
                  runId,
                  resolvedActor,
                  ActorType.HUMAN,
                  resolvedIdempotencyKey,
                  resolvedCorrelation,
                  reason));
      StringBuilder output = new StringBuilder().append(runId).append(" unarchived");
      if (result.replay()) {
        output.append(" [replayed]");
      }
      if (idempotencyKey == null) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow unarchive", runId, resolvedCorrelation, start);
      log.info(
          "workflow unarchive reason supplied correlationId={} workflowRunId={} reasonLength={}",
          resolvedCorrelation,
          runId,
          reason == null ? 0 : reason.length());
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow unarchive", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow unarchive", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "history",
      description = "Print the chronological event history of a governed workflow run",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String history(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              required = false,
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "since",
              description = "ISO-8601 timestamp; only show events with created_at >= since",
              required = false)
          String since,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    requireInspectionWired();
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      OffsetDateTime sinceInclusive = parseSince(since);
      WorkflowHistoryView view = workflowInspectionService.listHistory(runId, sinceInclusive);
      String rendered = renderHistory(view, format);
      if (verbose) {
        rendered = appendCorrelationSuffix(rendered, resolvedCorrelation);
      }
      emitSuccess("workflow history", runId, resolvedCorrelation, start);
      return rendered;
    } catch (DomainException de) {
      emitFailure("workflow history", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow history", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "approve-spec",
      description =
          "Approve a workflow run's specification (story 2.13 — CLI/REST equivalence for the spec-loop).",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String approveSpec(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(longName = "artifact-id", description = "Spec artifact public id", required = true)
          String artifactId,
      @Option(
              longName = "expected-artifact-version",
              description = "Spec artifact version reviewed",
              required = true)
          Integer expectedArtifactVersion,
      @Option(
              longName = "expected-context-bundle-version",
              description = "Context bundle version reviewed",
              required = true)
          Integer expectedContextBundleVersion,
      @Option(
              longName = "reviewer-role",
              description = "Reviewer role (optional)",
              required = false)
          String reviewerRole,
      @Option(longName = "reason", description = "Optional reviewer reason", required = false)
          String reason,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    // Story 2.13 review D2: CLI mirrors REST's HUMAN-only audit posture for the spec-loop
    // mutation commands. The pre-existing submit/retry/takeover CLI commands keep --actor-type.
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      String resolvedReviewer = approvalReviewerRoleResolver.resolveFor(reviewerRole);
      WorkflowStateChangeResult result =
          workflowCommandService.approveSpec(
              new ApproveSpecCommand(
                  runId,
                  artifactId,
                  expectedArtifactVersion,
                  expectedContextBundleVersion,
                  resolvedActor,
                  ActorType.HUMAN,
                  resolvedIdempotencyKey,
                  resolvedCorrelation,
                  resolvedReviewer,
                  reason));
      StringBuilder output =
          new StringBuilder()
              .append(result.workflowRunId())
              .append(" approve-spec accepted (state: ")
              .append(result.currentState().value())
              .append(")");
      if (idempotencyKey == null) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow approve-spec", runId, resolvedCorrelation, start);
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow approve-spec", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow approve-spec", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "accept-implementation",
      description =
          "Accept a workflow run's implementation (story 3.23 — CLI/REST equivalence for the technical-approval loop).",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String acceptImplementation(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "artifact-id",
              description = "Implementation artifact public id",
              required = true)
          String artifactId,
      @Option(
              longName = "expected-artifact-version",
              description = "Implementation artifact version reviewed",
              required = true)
          Integer expectedArtifactVersion,
      @Option(
              longName = "expected-context-bundle-version",
              description = "Context bundle version reviewed",
              required = true)
          Integer expectedContextBundleVersion,
      @Option(
              longName = "reviewer-role",
              description = "Reviewer role (must be 'developer')",
              required = true)
          String reviewerRole,
      @Option(longName = "reason", description = "Optional reviewer reason", required = false)
          String reason,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    // Story 3.23: CLI mirrors REST's HUMAN-only audit posture for the technical-approval mutation
    // (see approve-spec). reviewerRole is required and must be 'developer' (validated below).
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      // Story 3.23 R4: developer-only command. Validate the raw reviewer role with the same typed
      // INVALID_REVIEWER_ROLE_FOR_ENDPOINT idiom as the REST controller; do NOT route it through
      // approvalReviewerRoleResolver.resolveFor (its blank -> product_reviewer default masks it).
      String resolvedReviewer = requireDeveloperReviewerRole("accept-implementation", reviewerRole);
      WorkflowStateChangeResult result =
          workflowCommandService.acceptImplementation(
              new AcceptImplementationCommand(
                  runId,
                  artifactId,
                  expectedArtifactVersion,
                  expectedContextBundleVersion,
                  resolvedActor,
                  ActorType.HUMAN,
                  resolvedIdempotencyKey,
                  resolvedCorrelation,
                  resolvedReviewer,
                  reason));
      StringBuilder output =
          new StringBuilder()
              .append(result.workflowRunId())
              .append(" accept-implementation accepted (state: ")
              .append(result.currentState().value())
              .append(")");
      if (idempotencyKey == null) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow accept-implementation", runId, resolvedCorrelation, start);
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow accept-implementation", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure(
          "workflow accept-implementation", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "reject-implementation",
      description =
          "Reject a workflow run's implementation with structured technical feedback (story 3.24 — CLI/REST equivalence for the technical-approval loop).",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String rejectImplementation(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "artifact-id",
              description = "Implementation artifact public id",
              required = true)
          String artifactId,
      @Option(
              longName = "expected-artifact-version",
              description = "Implementation artifact version reviewed",
              required = true)
          Integer expectedArtifactVersion,
      @Option(
              longName = "expected-context-bundle-version",
              description = "Context bundle version reviewed",
              required = true)
          Integer expectedContextBundleVersion,
      @Option(
              longName = "tagged-feedback",
              description =
                  "Structured developer rework taxonomy (incorrect_approach | incomplete_implementation | quality_issue | breaks_existing_functionality | out_of_scope)",
              required = true)
          RejectionTaxonomy taggedFeedback,
      @Option(longName = "reason-text", description = "Reviewer reason text", required = true)
          String reasonText,
      @Option(
              longName = "reviewer-role",
              description = "Reviewer role (must be 'developer')",
              required = true)
          String reviewerRole,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    // Story 3.24: CLI mirrors REST's HUMAN-only audit posture for the technical-approval mutation
    // (see accept-implementation / reject-spec). reviewerRole is required and must be 'developer';
    // taggedFeedback must be a developer-subset value (both validated below for CLI/REST parity).
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      // Story 3.24 R4: developer-only command. Validate the raw reviewer role + the taxonomy subset
      // with the same typed idioms as the REST controller; do NOT route reviewerRole through
      // approvalReviewerRoleResolver.resolveFor (its blank -> product_reviewer default masks it).
      String resolvedReviewer = requireDeveloperReviewerRole("reject-implementation", reviewerRole);
      requireDeveloperRejectionTaxonomy(taggedFeedback);
      WorkflowStateChangeResult result =
          workflowCommandService.rejectImplementation(
              new RejectImplementationCommand(
                  runId,
                  artifactId,
                  expectedArtifactVersion,
                  expectedContextBundleVersion,
                  resolvedActor,
                  ActorType.HUMAN,
                  resolvedIdempotencyKey,
                  resolvedCorrelation,
                  resolvedReviewer,
                  taggedFeedback,
                  reasonText));
      StringBuilder output =
          new StringBuilder()
              .append(result.workflowRunId())
              .append(" reject-implementation accepted (state: ")
              .append(result.currentState().value())
              .append(")");
      if (idempotencyKey == null) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow reject-implementation", runId, resolvedCorrelation, start);
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow reject-implementation", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure(
          "workflow reject-implementation", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  /**
   * Story 3.24 R4: CLI mirror of {@code WorkflowController.requireDeveloperRejectionTaxonomy}. The
   * {@code reject-implementation} command accepts only developer-subset {@link RejectionTaxonomy}
   * values (story 3.21); a valid-but-product value is rejected with the same typed {@link
   * DomainErrorCode#INVALID_REJECTION_TAXONOMY} {@link DomainException} so REST and CLI reject
   * identical payloads identically (the CLI/REST equivalence pin). An unparseable {@code
   * --tagged-feedback} string never reaches here — Spring Shell's enum converter fails first.
   */
  private static void requireDeveloperRejectionTaxonomy(RejectionTaxonomy taggedFeedback) {
    if (!taggedFeedback.isDeveloperValue()) {
      // Story 3.24 logging task: audit the boundary rejection before throwing so the typed
      // INVALID_REJECTION_TAXONOMY path is observable on the CLI surface as it is on REST.
      log.warn(
          "CLI reject-implementation rejected: taggedFeedback must be a developer-subset value taggedFeedback={}",
          MdcKeys.sanitizeForLog(taggedFeedback.value()));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("field", "taggedFeedback");
      details.put("value", taggedFeedback.value());
      throw new DomainException(
          DomainErrorCode.INVALID_REJECTION_TAXONOMY,
          "Tagged feedback must be a developer-rejection taxonomy value",
          details);
    }
  }

  /**
   * Story 3.23 R4: CLI mirror of {@code WorkflowController.requireDeveloperReviewerRole}. The
   * accept-implementation (and, when it lands, the 3.24 reject-implementation) command is
   * developer-only; the {@code --reviewer-role} value must equal {@code developer} verbatim. Throws
   * the same typed {@link DomainErrorCode#INVALID_REVIEWER_ROLE_FOR_ENDPOINT} {@link
   * DomainException} so REST and CLI reject identical payloads identically (the CLI/REST
   * equivalence pin). Deliberately does NOT use {@code ApprovalReviewerRoleResolver} (whose
   * blank-fallback default {@code product_reviewer} would mask the mismatch).
   */
  private static String requireDeveloperReviewerRole(String action, String reviewerRole) {
    String trimmed = reviewerRole == null ? null : reviewerRole.trim();
    if (!"developer".equals(trimmed)) {
      // Story 3.23 logging task / review patch: audit the boundary rejection before throwing so the
      // typed INVALID_REVIEWER_ROLE_FOR_ENDPOINT path is observable on the CLI surface as it is on
      // REST (WorkflowController.requireDeveloperReviewerRole emits the symmetric WARN). The shared
      // helper serves both accept-implementation (3.23) and reject-implementation (3.24); the
      // {action} label keeps the audit line attributed to the correct command.
      log.warn(
          "CLI {} rejected: reviewerRole must be 'developer' actualReviewerRole={}",
          action,
          MdcKeys.sanitizeForLog(reviewerRole));
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("field", "reviewerRole");
      details.put("expected", "developer");
      details.put("actual", reviewerRole);
      throw new DomainException(
          DomainErrorCode.INVALID_REVIEWER_ROLE_FOR_ENDPOINT,
          "Reviewer role must be 'developer' for this endpoint",
          details);
    }
    return trimmed;
  }

  @Command(
      name = "reject-spec",
      description = "Reject a workflow run's specification with structured feedback (story 2.13).",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String rejectSpec(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(longName = "artifact-id", description = "Spec artifact public id", required = true)
          String artifactId,
      @Option(
              longName = "expected-artifact-version",
              description = "Spec artifact version reviewed",
              required = true)
          Integer expectedArtifactVersion,
      @Option(
              longName = "expected-context-bundle-version",
              description = "Context bundle version reviewed",
              required = true)
          Integer expectedContextBundleVersion,
      @Option(
              longName = "tagged-feedback",
              description =
                  "Structured rework taxonomy (missing_scope | unclear_specification | misunderstood_implementation)",
              required = true)
          RejectionTaxonomy taggedFeedback,
      @Option(longName = "reason-text", description = "Reviewer reason text", required = true)
          String reasonText,
      @Option(
              longName = "reviewer-role",
              description = "Reviewer role (optional)",
              required = false)
          String reviewerRole,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    // Story 2.13 review D2: CLI mirrors REST's HUMAN-only audit posture for the spec-loop
    // mutation commands.
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      String resolvedReviewer = approvalReviewerRoleResolver.resolveFor(reviewerRole);
      WorkflowStateChangeResult result =
          workflowCommandService.rejectSpec(
              new RejectSpecCommand(
                  runId,
                  artifactId,
                  expectedArtifactVersion,
                  expectedContextBundleVersion,
                  resolvedActor,
                  ActorType.HUMAN,
                  resolvedIdempotencyKey,
                  resolvedCorrelation,
                  resolvedReviewer,
                  taggedFeedback,
                  reasonText));
      StringBuilder output =
          new StringBuilder()
              .append(result.workflowRunId())
              .append(" reject-spec accepted (state: ")
              .append(result.currentState().value())
              .append(")");
      if (idempotencyKey == null) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow reject-spec", runId, resolvedCorrelation, start);
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow reject-spec", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure("workflow reject-spec", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "answer-clarification",
      description =
          "Answer an open clarification on a spec (story 2.13 — CLI/REST equivalence for the spec-loop).",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String answerClarification(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Argument(index = 1, description = "Clarification public id (clr_...)")
          String clarificationId,
      @Option(longName = "artifact-id", description = "Spec artifact public id", required = true)
          String artifactId,
      @Option(
              longName = "expected-artifact-version",
              description = "Spec artifact version reviewed",
              required = true)
          Integer expectedArtifactVersion,
      @Option(longName = "answer-text", description = "Reviewer answer text", required = true)
          String answerText,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    // Story 2.13 review D2: CLI mirrors REST's HUMAN-only audit posture for the spec-loop
    // mutation commands.
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      WorkflowStateChangeResult result =
          workflowCommandService.answerClarification(
              new SubmitClarificationCommand(
                  runId,
                  clarificationId,
                  artifactId,
                  expectedArtifactVersion,
                  answerText,
                  resolvedActor,
                  ActorType.HUMAN,
                  resolvedIdempotencyKey,
                  resolvedCorrelation));
      // Story 2.13 review P15: surface "unknown" rather than guessing "answered" — the underlying
      // row may have moved to incorporated/superseded/rejected_invalid since the original answer.
      String clarificationStatus =
          result.clarificationStatus() == null ? "unknown" : result.clarificationStatus();
      StringBuilder output =
          new StringBuilder()
              .append(clarificationId)
              .append(" answer accepted (clarificationStatus: ")
              .append(clarificationStatus)
              .append(", currentState: ")
              .append(result.currentState() == null ? "unknown" : result.currentState().value())
              .append(")");
      if (idempotencyKey == null) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
      if (verbose) {
        output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      }
      emitSuccess("workflow answer-clarification", runId, resolvedCorrelation, start);
      return output.toString();
    } catch (DomainException de) {
      emitFailure("workflow answer-clarification", runId, resolvedCorrelation, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure(
          "workflow answer-clarification", runId, resolvedCorrelation, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  /**
   * Story 2.13 round-3 review P5 — CLI delegates actor-identity resolution to the same {@link
   * LocalActorIdentityResolver} instance the REST side uses, so length / control-char / Unicode
   * FORMAT-category gates apply uniformly across both transports and a malformed {@code
   * --actor-identity} flag value can't bypass the audit-safe sanitisation that {@code
   * X-Actor-Identity} headers already pass through.
   */
  private String resolveActorIdentity(String supplied) {
    if (localActorIdentityResolver == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_mutation_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed without LocalActorIdentityResolver; "
              + "inject LocalActorIdentityResolver to use approve-spec/reject-spec/answer-clarification",
          details);
    }
    // Story 2.13 round-4 D-R4-1: mirror REST's fail-closed check so CLI and REST behave identically
    // for unsafe --actor-identity values (length/control-char/FORMAT-category/surrogate gates).
    // requireSafe is a no-op for null/blank — those land on the property fallback inside resolve.
    localActorIdentityResolver.requireSafe(supplied);
    return localActorIdentityResolver.resolve(supplied);
  }

  private OffsetDateTime parseSince(String since) {
    if (since == null || since.isBlank()) {
      return null;
    }
    OffsetDateTime parsed;
    try {
      parsed = OffsetDateTime.parse(since);
    } catch (DateTimeParseException error) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("since", since);
      details.put("rule", "ISO-8601 with explicit zone (e.g. 2026-05-13T09:00:00Z)");
      throw new DomainException(
          DomainErrorCode.INVALID_TIME_RANGE, "Invalid --since timestamp: " + since, details);
    }
    if (parsed.isAfter(OffsetDateTime.now())) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("since", since);
      details.put("rule", "must be at or before the current time");
      throw new DomainException(
          DomainErrorCode.INVALID_TIME_RANGE,
          "Invalid --since timestamp (in the future): " + since,
          details);
    }
    return parsed;
  }

  private String resolveIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey != null) {
      return idempotencyKey;
    }
    if (interactivityDetector.getAsBoolean()) {
      return generatedKeySupplier.get();
    }
    throw idempotencyKeyValidator.missingKeyException();
  }

  /**
   * Story 2.8 AC7 + OQ-3: append the latest spec-stage context bundle to the rendered status. Text
   * format → pretty-printed (2-space indent) for readability; JSON format → raw bytes so jq
   * consumers receive a single valid JSON document with the bundle nested as a key.
   *
   * <p>The bundle is sourced from {@link
   * WorkflowInspectionService#getContextBundleLookupForArtifact}. JSON mode upgrades the outer
   * status document to a dedicated v2 contract with a structured {@code contextBundle} object so
   * the field shape is stable across available and unavailable states.
   */
  private String appendContextBundle(
      String rendered, WorkflowStatusView view, String runId, String format) {
    List<SpecHistoryEntry> history = workflowInspectionService.getSpecHistory(runId);
    String normalizedFormat = normalizeFormat(format);
    if (history.isEmpty()) {
      return appendBundleUnavailable(rendered, view, normalizedFormat, null, "noSpecArtifactYet");
    }
    String latestSpecArtifactId = history.get(history.size() - 1).spec().id();
    ContextBundleLookupResult lookup =
        workflowInspectionService.getContextBundleLookupForArtifact(latestSpecArtifactId);
    if (!lookup.available()) {
      return appendBundleUnavailable(
          rendered, view, normalizedFormat, latestSpecArtifactId, lookup.reason());
    }
    byte[] redactedBytes = lookup.bundle().redactedPayload();
    String bundleText = renderBundleBytes(redactedBytes, normalizedFormat);
    if (FORMAT_JSON.equals(normalizedFormat)) {
      return appendBundleJsonField(rendered, view, latestSpecArtifactId, bundleText);
    }
    StringBuilder out = new StringBuilder(rendered);
    if (!rendered.isEmpty() && !rendered.endsWith("\n")) {
      out.append('\n');
    }
    out.append("# context-bundle (artifact ").append(latestSpecArtifactId).append("):\n");
    out.append(bundleText);
    if (!bundleText.endsWith("\n")) {
      out.append('\n');
    }
    return out.toString();
  }

  private String appendBundleUnavailable(
      String rendered,
      WorkflowStatusView view,
      String normalizedFormat,
      String artifactId,
      String reasonCode) {
    if (FORMAT_JSON.equals(normalizedFormat)) {
      return appendBundleJsonUnavailable(rendered, view, artifactId, reasonCode);
    }
    StringBuilder out = new StringBuilder(rendered);
    if (!rendered.isEmpty() && !rendered.endsWith("\n")) {
      out.append('\n');
    }
    out.append("# context-bundle: none (").append(textBundleReason(reasonCode)).append(")\n");
    return out.toString();
  }

  /**
   * Splice the bundle as a structured sibling key on the JSON status document. We re-parse the
   * already-rendered status JSON, upgrade the top-level schemaVersion, attach the structured {@code
   * contextBundle} field, and re-serialize so downstream {@code jq} consumers receive a single
   * well-formed document with a stable wire shape.
   */
  private String appendBundleJsonField(
      String renderedStatusJson,
      WorkflowStatusView view,
      String artifactId,
      String bundleJsonLiteral) {
    return spliceContextBundleJson(renderedStatusJson, view, artifactId, bundleJsonLiteral, null);
  }

  private String appendBundleJsonUnavailable(
      String renderedStatusJson, WorkflowStatusView view, String artifactId, String reasonCode) {
    return spliceContextBundleJson(renderedStatusJson, view, artifactId, null, reasonCode);
  }

  private String spliceContextBundleJson(
      String renderedStatusJson,
      WorkflowStatusView view,
      String artifactId,
      String bundleJsonLiteral,
      String reasonCode) {
    try {
      ObjectMapper mapper = jsonMapper();
      JsonNode parsed = mapper.readTree(renderedStatusJson);
      if (!parsed.isObject()) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "context_bundle_json_splice_failed");
        details.put("cause", "rendered_status_not_object");
        throw new DomainException(
            DomainErrorCode.INTERNAL_ERROR,
            "Rendered status JSON is not a JSON object — cannot splice context bundle",
            details);
      }
      com.fasterxml.jackson.databind.node.ObjectNode root =
          (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
      root.put("schemaVersion", STATUS_WITH_CONTEXT_BUNDLE_SCHEMA_VERSION);
      root.put("specRejectionLoopCount", view.specRejectionLoopCount());
      root.put("escalationMarker", view.escalationMarker());
      com.fasterxml.jackson.databind.node.ObjectNode contextBundle =
          root.putObject("contextBundle");
      if (bundleJsonLiteral != null) {
        JsonNode bundleNode = mapper.readTree(bundleJsonLiteral);
        contextBundle.put("status", "available");
        if (artifactId != null) {
          contextBundle.put("artifactId", artifactId);
        } else {
          contextBundle.putNull("artifactId");
        }
        contextBundle.putNull("reason");
        contextBundle.set("bundle", bundleNode);
      } else {
        contextBundle.put("status", "unavailable");
        if (artifactId != null) {
          contextBundle.put("artifactId", artifactId);
        } else {
          contextBundle.putNull("artifactId");
        }
        contextBundle.put("reason", reasonCode);
        contextBundle.putNull("bundle");
      }
      return mapper.writeValueAsString(root);
    } catch (IOException error) {
      // Status JSON came from our own outputs helper — a parse failure here is a programming
      // bug, not a runtime user error. Surface as an internal error so the CLI exits non-zero.
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "context_bundle_json_splice_failed");
      details.put("cause", error.getClass().getSimpleName());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Failed to splice context bundle into JSON status output",
          details);
    }
  }

  /**
   * Story 3.6 AC7 — append the latest runner execution's redacted-log reference to the status
   * output. NEVER renders log <em>content</em> — only the typed reference (path + byteSize +
   * classification + redactionCount). Text mode appends a {@code # runner-logs} section; JSON mode
   * upgrades to the v2 status document and attaches a structured {@code runnerLogs} object whose
   * shape is stable across available / unavailable states. The {@code rex} is resolved as the
   * latest runner execution for the run (OQ-4); {@code getRunnerLogReference} stays rex-scoped.
   */
  private String appendRunnerLogs(
      String rendered, WorkflowStatusView view, String runId, String format) {
    String normalizedFormat = normalizeFormat(format);
    Optional<String> rexId = workflowInspectionService.findLatestRunnerExecutionId(runId);
    if (rexId.isEmpty()) {
      return appendRunnerLogsResult(
          rendered,
          view,
          normalizedFormat,
          RunnerLogReferenceResult.unavailable(null, "noRunnerExecutionYet"));
    }
    RunnerLogReferenceResult result = workflowInspectionService.getRunnerLogReference(rexId.get());
    return appendRunnerLogsResult(rendered, view, normalizedFormat, result);
  }

  private String appendRunnerLogsResult(
      String rendered,
      WorkflowStatusView view,
      String normalizedFormat,
      RunnerLogReferenceResult result) {
    if (FORMAT_JSON.equals(normalizedFormat)) {
      return spliceRunnerLogsJson(rendered, view, result);
    }
    StringBuilder out = new StringBuilder(rendered);
    if (!rendered.isEmpty() && !rendered.endsWith("\n")) {
      out.append('\n');
    }
    if (result.available()) {
      RunnerLogReference ref = result.reference();
      out.append("# runner-logs (")
          .append(result.runnerExecutionId())
          .append("):\n")
          .append("  reference: ")
          .append(ref.referencePath())
          .append('\n')
          .append("  classification: ")
          .append(ref.classification().value())
          .append('\n')
          .append("  byteSize: ")
          .append(ref.byteSize())
          .append('\n')
          .append("  redactionCount: ")
          .append(ref.redactionCount())
          .append('\n');
    } else {
      out.append("# runner-logs: none (").append(result.reason()).append(")\n");
    }
    return out.toString();
  }

  private String spliceRunnerLogsJson(
      String renderedStatusJson, WorkflowStatusView view, RunnerLogReferenceResult result) {
    try {
      ObjectMapper mapper = jsonMapper();
      JsonNode parsed = mapper.readTree(renderedStatusJson);
      if (!parsed.isObject()) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", "runner_logs_json_splice_failed");
        details.put("cause", "rendered_status_not_object");
        throw new DomainException(
            DomainErrorCode.INTERNAL_ERROR,
            "Rendered status JSON is not a JSON object — cannot splice runner logs",
            details);
      }
      com.fasterxml.jackson.databind.node.ObjectNode root =
          (com.fasterxml.jackson.databind.node.ObjectNode) parsed;
      root.put("schemaVersion", STATUS_WITH_CONTEXT_BUNDLE_SCHEMA_VERSION);
      root.put("specRejectionLoopCount", view.specRejectionLoopCount());
      root.put("escalationMarker", view.escalationMarker());
      com.fasterxml.jackson.databind.node.ObjectNode runnerLogs = root.putObject("runnerLogs");
      if (result.runnerExecutionId() == null) {
        runnerLogs.putNull("runnerExecutionId");
      } else {
        runnerLogs.put("runnerExecutionId", result.runnerExecutionId());
      }
      if (result.available()) {
        RunnerLogReference ref = result.reference();
        runnerLogs.put("status", "available");
        runnerLogs.putNull("reason");
        com.fasterxml.jackson.databind.node.ObjectNode logs = runnerLogs.putObject("logs");
        logs.put("reference", ref.referencePath());
        logs.put("byteSize", ref.byteSize());
        logs.put("classification", ref.classification().value());
        logs.put("redactionCount", ref.redactionCount());
      } else {
        runnerLogs.put("status", "unavailable");
        runnerLogs.put("reason", result.reason());
        runnerLogs.putNull("logs");
      }
      return mapper.writeValueAsString(root);
    } catch (IOException error) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "runner_logs_json_splice_failed");
      details.put("cause", error.getClass().getSimpleName());
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "Failed to splice runner logs into JSON status output",
          details);
    }
  }

  private String renderBundleBytes(byte[] redactedBytes, String normalizedFormat) {
    String raw = new String(redactedBytes, StandardCharsets.UTF_8);
    if (FORMAT_JSON.equals(normalizedFormat)) {
      // Raw — caller will splice this as a JSON sub-document.
      return raw;
    }
    try {
      ObjectMapper mapper = jsonMapper();
      JsonNode tree = mapper.readTree(raw);
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    } catch (IOException error) {
      // The bundle is already a validated runner-contracts v1 JSON document; a parse failure
      // here would indicate corrupted scratch storage. Fall back to raw text so the operator
      // still sees something usable.
      log.warn(
          "context-bundle pretty-print failed; falling back to raw bytes cause={}",
          error.getClass().getSimpleName());
      return raw;
    }
  }

  private String textBundleReason(String reasonCode) {
    return switch (reasonCode) {
      case null -> "context bundle unavailable (unknown reason)";
      case "noSpecArtifactYet" -> "no spec artifact yet";
      default -> "context bundle unavailable (" + reasonCode + ")";
    };
  }

  private ObjectMapper jsonMapper() {
    return new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  private static String appendCorrelationSuffix(String rendered, String correlationId) {
    String suffix = "[correlation-id: " + correlationId + "]";
    if (rendered == null || rendered.isEmpty()) {
      return suffix;
    }
    if (rendered.endsWith("\n")) {
      return rendered + suffix;
    }
    return rendered + " " + suffix;
  }

  /**
   * Resolve the caller-supplied correlation id (or mint a fresh UUIDv7), sanitise for log
   * injection, and stamp it on MDC via {@link MdcKeys#beginScope} so the prior value can be
   * restored in a finally block. Callers must invoke {@code
   * MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior())} in their finally — this preserves any
   * outer scope's correlation id when the CLI is used as a library, instead of the previous blanket
   * {@code MDC.remove} which destroyed nested scopes. See P5 of the story 1.19 review.
   */
  private CorrelationScope pushCorrelation(String supplied) {
    String resolved = supplied;
    if (resolved == null || resolved.isBlank()) {
      resolved = correlationIdSupplier.get();
    }
    // Strip CR/LF/TAB to prevent log injection through MDC and SLF4J interpolation —
    // a value like `abc\nworkflow command completed correlationId=fake outcome=success`
    // would otherwise forge a synthetic completion line in the structured log stream.
    resolved = MdcKeys.sanitizeForLog(resolved);
    String prior = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, resolved);
    return new CorrelationScope(resolved, prior);
  }

  record CorrelationScope(String resolved, String prior) {}

  private void requireInspectionWired() {
    if (workflowInspectionService == null || outputs == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_inspection_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed with the legacy submit-only constructor; "
              + "inject WorkflowInspectionService and WorkflowCommandOutputs to use status/history",
          details);
    }
  }

  private void requireRecoveryWired() {
    if (recoveryService == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_retry_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed without RecoveryService; inject RecoveryService to use retry",
          details);
    }
  }

  private void requireTakeoverWired() {
    if (developerTakeoverService == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_takeover_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed without DeveloperTakeoverService; inject DeveloperTakeoverService to use takeover",
          details);
    }
  }

  private void requireArchiveWired() {
    if (workflowArchiveService == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_archive_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed without WorkflowArchiveService; inject WorkflowArchiveService to use archive/unarchive",
          details);
    }
  }

  private static String describeSyncOutcome(
      WorkflowOrchestrationService.SyncCompletionOutcome outcome) {
    return switch (outcome) {
      case POSTED -> "completion summary posted to Linear";
      case SKIPPED_DISABLED -> "completion-sync skipped (linear-completion-sync disabled)";
      case SKIPPED_NO_TICKET_LINK -> "completion-sync skipped (no linked Linear ticket)";
      case SKIPPED_NO_LINEAR_PROFILE -> "completion-sync skipped (no Linear profile active)";
      case SKIPPED_NO_COMMENT_CAPABILITY ->
          "completion-sync skipped (ticket source does not support comments)";
      case POST_FAILED -> "completion-sync failed (recorded as an event)";
    };
  }

  private void requireOrchestrationWired() {
    if (workflowOrchestrationService == null) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("reason", "legacy_constructor_invoked_for_sync_completion_command");
      throw new DomainException(
          DomainErrorCode.INTERNAL_ERROR,
          "WorkflowCommands was constructed without WorkflowOrchestrationService; inject it to use"
              + " sync-completion",
          details);
    }
  }

  private String renderStatus(WorkflowStatusView view, String format) {
    return switch (normalizeFormat(format)) {
      case FORMAT_JSON -> outputs.renderStatusJson(view);
      case FORMAT_TEXT -> outputs.renderStatusText(view);
      default -> throw unsupportedFormat(format);
    };
  }

  private String renderHistory(WorkflowHistoryView view, String format) {
    return switch (normalizeFormat(format)) {
      case FORMAT_JSON -> outputs.renderHistoryJson(view);
      case FORMAT_TEXT -> outputs.renderHistoryText(view);
      default -> throw unsupportedFormat(format);
    };
  }

  private String normalizeFormat(String format) {
    if (format == null || format.isBlank()) {
      return FORMAT_TEXT;
    }
    String normalized = format.trim().toLowerCase(Locale.ROOT);
    if (FORMAT_TEXT.equals(normalized) || FORMAT_JSON.equals(normalized)) {
      return normalized;
    }
    throw unsupportedFormat(format);
  }

  private DomainException unsupportedFormat(String format) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("format", format);
    details.put("supportedFormats", java.util.List.of(FORMAT_TEXT, FORMAT_JSON));
    return new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Unsupported --format value: " + format, details);
  }

  private static void emitSuccess(
      String commandName, String runId, String correlationId, long startNanos) {
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    log.info(
        "workflow command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        commandName,
        runId,
        OUTCOME_SUCCESS,
        elapsedMs);
  }

  private static void emitFailure(
      String commandName, String runId, String correlationId, long startNanos, String code) {
    long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
    log.info(
        "workflow command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        commandName,
        runId,
        code,
        elapsedMs);
  }

  private static String codeFor(DomainException de) {
    return de.errorCode() == null
        ? OUTCOME_UNKNOWN
        : OUTCOME_FAILURE_PREFIX + de.errorCode().value();
  }
}
