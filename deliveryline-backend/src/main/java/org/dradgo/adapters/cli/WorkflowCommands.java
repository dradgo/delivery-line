package org.dradgo.adapters.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.RetryRecoveryResult;
import org.dradgo.application.runner.RunnerLogReference;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.ApprovalReviewerRoleResolver;
import org.dradgo.application.workflow.SubmitWorkflowResult;
import org.dradgo.application.workflow.WorkflowCommandService;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.ContextBundleLookupResult;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerLogReferenceResult;
import org.dradgo.application.workflow.WorkflowInspectionService.SpecHistoryEntry;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowHistoryView;
import org.dradgo.application.workflow.WorkflowInspectionService.WorkflowStatusView;
import org.dradgo.application.workflow.WorkflowStateChangeResult;
import org.dradgo.application.workflow.commands.ApproveSpecCommand;
import org.dradgo.application.workflow.commands.RejectSpecCommand;
import org.dradgo.application.workflow.commands.SubmitClarificationCommand;
import org.dradgo.application.workflow.commands.SubmitWorkflowCommand;
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
      LocalActorIdentityResolver localActorIdentityResolver) {
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
        localActorIdentityResolver);
  }

  /**
   * Three-arg constructor kept for backward compatibility with the existing {@link
   * org.dradgo.adapters.cli.WorkflowCommandsTest} unit tests. The {@code submit}-only path does not
   * need the inspection, recovery, or actor-identity-resolver services, so callers can pass nulls
   * as long as they only invoke {@link #submit}.
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
      LocalActorIdentityResolver localActorIdentityResolver) {
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
                  linearTicketReference));
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
