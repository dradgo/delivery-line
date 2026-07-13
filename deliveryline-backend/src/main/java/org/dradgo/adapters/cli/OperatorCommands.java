package org.dradgo.adapters.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.dradgo.application.artifact.ActorContext;
import org.dradgo.application.idempotency.IdempotencyKeyValidator;
import org.dradgo.application.idempotency.UuidV7Generator;
import org.dradgo.application.observability.MdcKeys;
import org.dradgo.application.recovery.ClassifyFailureResult;
import org.dradgo.application.recovery.PauseRecoveryResult;
import org.dradgo.application.recovery.ReconcileRecoveryResult;
import org.dradgo.application.recovery.RecoveryService;
import org.dradgo.application.recovery.RerunFromStepRecoveryResult;
import org.dradgo.application.recovery.ResumeRecoveryResult;
import org.dradgo.application.security.LocalActorIdentityResolver;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunFilter;
import org.dradgo.application.workflow.WorkflowInspectionService.OperatorRunSummary;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.ActorType;
import org.dradgo.domain.registry.DomainErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.core.command.annotation.Argument;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Story 4.1 (AC1/AC4/AC7/AC9) — {@code deliveryline operator status} CLI surface: a fleet view of
 * all runs in non-happy operator states across all workflows (the CLI analogue of {@code
 * deliveryline workers status}, extending {@code deliveryline status}'s per-run view). A thin
 * adapter — it only parses flags → builds an {@link OperatorRunFilter} of raw values → calls {@link
 * WorkflowInspectionService#getOperatorRunSummary} → renders via {@link WorkflowCommandOutputs} →
 * emits the AC7 completion log. ALL token/duration/limit resolution + histogram assembly lives in
 * the service (AC9).
 *
 * <p><b>Command name (Reconciliation 10, corrected).</b> Spring Shell 4.0.2 composes a command's
 * registered name as {@code groupPrefix + " " + @Command.name} — the {@code @CommandGroup.name} is
 * used ONLY for help categorization, NOT the path (see {@code CommandFactoryBean#getObject}). So
 * mirroring {@code WorkerCommands}' {@code prefix="deliveryline"} + {@code name="status"} would
 * register as {@code deliveryline status}, colliding with {@code WorkflowCommands.status}. To
 * register exactly {@code deliveryline operator status} (AC1) the group {@code prefix} carries
 * {@code "deliveryline operator"}. Pinned by {@code OperatorCliCommandRegistrationIT}.
 */
@Component
@CommandGroup(
    name = "operator",
    description = "Operator fleet inspection",
    prefix = "deliveryline operator")
public class OperatorCommands {

  private static final Logger log = LoggerFactory.getLogger(OperatorCommands.class);

  private static final String COMMAND_NAME = "operator status";
  private static final String COMMAND_NAME_DIAGNOSE = "operator diagnose";
  private static final String COMMAND_NAME_RESUME = "operator resume";
  private static final String COMMAND_NAME_RECONCILE = "operator reconcile";
  private static final String COMMAND_NAME_RERUN_FROM_STEP = "operator rerun-from-step";
  private static final String COMMAND_NAME_PAUSE = "operator pause";
  private static final String COMMAND_NAME_CLASSIFY_FAILURE = "operator classify-failure";
  private static final String OUTCOME_SUCCESS = "success";
  private static final String OUTCOME_FAILURE_PREFIX = "failure:";
  private static final String OUTCOME_UNKNOWN = "failure:unknown";
  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_JSON = "json";
  private static final String DEFAULT_STATE = "failed,stalled,orphaned";

  private final WorkflowInspectionService inspection;
  private final WorkflowCommandOutputs outputs;
  private final CliInteractivityDetector interactivity;
  private final Supplier<String> correlationIdSupplier;
  // Story 4.10 — the first MUTATING operator command (resume) needs the rich recovery service plus
  // the idempotency-key / actor-identity resolution seam that the workflow-command surface uses.
  // The
  // read-only status/diagnose commands ignore these.
  private final RecoveryService recoveryService;
  private final IdempotencyKeyValidator idempotencyKeyValidator;
  private final LocalActorIdentityResolver localActorIdentityResolver;
  private final Supplier<String> generatedIdempotencyKeySupplier;

  @Autowired
  public OperatorCommands(
      WorkflowInspectionService inspection,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity,
      RecoveryService recoveryService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      LocalActorIdentityResolver localActorIdentityResolver,
      UuidV7Generator uuidV7Generator) {
    this(
        inspection,
        outputs,
        interactivity,
        recoveryService,
        idempotencyKeyValidator,
        localActorIdentityResolver,
        uuidV7Generator::generate,
        uuidV7Generator::generate);
  }

  OperatorCommands(
      WorkflowInspectionService inspection,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity,
      RecoveryService recoveryService,
      IdempotencyKeyValidator idempotencyKeyValidator,
      LocalActorIdentityResolver localActorIdentityResolver,
      Supplier<String> correlationIdSupplier,
      Supplier<String> generatedIdempotencyKeySupplier) {
    this.inspection = Objects.requireNonNull(inspection, "inspection");
    this.outputs = Objects.requireNonNull(outputs, "outputs");
    this.interactivity = Objects.requireNonNull(interactivity, "interactivity");
    this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService");
    this.idempotencyKeyValidator =
        Objects.requireNonNull(idempotencyKeyValidator, "idempotencyKeyValidator");
    this.localActorIdentityResolver =
        Objects.requireNonNull(localActorIdentityResolver, "localActorIdentityResolver");
    this.correlationIdSupplier =
        Objects.requireNonNull(correlationIdSupplier, "correlationIdSupplier");
    this.generatedIdempotencyKeySupplier =
        Objects.requireNonNull(generatedIdempotencyKeySupplier, "generatedIdempotencyKeySupplier");
  }

  @Command(
      name = "status",
      description =
          "Show all runs in non-happy operator states across all workflows with diagnostic"
              + " summaries. --state selects failed|stalled|orphaned|takenover|overridden;"
              + " --since 1h|24h|7d windows recent activity; --format=json emits a stable-schema"
              + " document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String status(
      @Option(
              longName = "state",
              description =
                  "Comma-separated operator states: failed,stalled,orphaned,takenover,overridden",
              defaultValue = DEFAULT_STATE)
          String state,
      @Option(
              longName = "since",
              description = "Relative activity window: 1h, 24h, 7d, 2w (minutes/hours/days/weeks)")
          String since,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "limit",
              description = "Maximum runs listed (default 100, max 500)",
              defaultValue = "100")
          int limit,
      @Option(longName = "correlation-id", description = "Correlation ID") String correlationId,
      @Option(
              longName = "verbose",
              description = "Append the resolved correlation id",
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    int total = -1;
    try {
      // Parse INSIDE the try so an unsupported --format (isJson throws INVALID_COMMAND_PAYLOAD)
      // still emits the AC7 completion log AND runs the finally/endScope — otherwise the MDC
      // correlation-id scope pushed above would leak into the next shell command.
      boolean json = isJson(format);
      List<String> stateTokens = splitStateTokens(state);
      OperatorRunFilter filter = new OperatorRunFilter(stateTokens, since, limit);
      OperatorRunSummary view = inspection.getOperatorRunSummary(filter);
      total = view.total();
      // ANSI only for an interactive TTY in text mode; JSON + non-TTY pipes stay plain (AC4).
      boolean ansi = !json && interactivity.isInteractive();
      String rendered =
          json
              ? outputs.renderOperatorSummaryJson(view)
              : outputs.renderOperatorSummaryText(view, ansi);
      if (verbose) {
        rendered = rendered + System.lineSeparator() + "correlationId=" + resolvedCorrelation;
      }
      emitSuccess(resolvedCorrelation, state, since, limit, total, start);
      return rendered;
    } catch (DomainException de) {
      emitFailure(resolvedCorrelation, state, since, limit, total, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitFailure(resolvedCorrelation, state, since, limit, total, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  @Command(
      name = "diagnose",
      description =
          "Deep-dive failure diagnostics for a single run: the NFR7 five questions (what happened /"
              + " changed / who acted / what failed / what is next), correlation id, runner-log"
              + " reference, per-integration sync status, and recommended recovery actions ranked by"
              + " safety (green=safe, yellow=caution, red=risky). --format=json emits a"
              + " stable-schema document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String diagnose(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(longName = "correlation-id", description = "Correlation ID") String correlationId,
      @Option(
              longName = "verbose",
              description = "Append the resolved correlation id",
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      // Parse INSIDE the try so an unsupported --format (INVALID_COMMAND_PAYLOAD) still emits the
      // completion log AND runs the finally/endScope (no leaked MDC correlation-id scope).
      boolean json = isJson(format);
      WorkflowInspectionService.FailureDiagnostics view = inspection.getFailureDiagnostics(runId);
      boolean ansi = !json && interactivity.isInteractive();
      String rendered =
          json ? outputs.renderDiagnoseJson(view) : outputs.renderDiagnoseText(view, ansi);
      // Only append the verbose correlation-id footer to TEXT output — appending it to JSON would
      // glue a trailing "correlationId=..." line onto the document and break the stable
      // operator-diagnose.v1 schema for machine consumers.
      if (verbose && !json) {
        rendered = rendered + System.lineSeparator() + "correlationId=" + resolvedCorrelation;
      }
      emitDiagnose(resolvedCorrelation, runId, start, OUTCOME_SUCCESS);
      return rendered;
    } catch (DomainException de) {
      emitDiagnose(resolvedCorrelation, runId, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitDiagnose(resolvedCorrelation, runId, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  private static void emitDiagnose(String correlationId, String runId, long start, String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "operator command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME_DIAGNOSE,
        MdcKeys.sanitizeForLog(runId),
        outcome,
        elapsedMs);
  }

  // Story 4.10 (AC6) — the FIRST mutating operator command. Resumes a Paused run through the RICH
  // RecoveryService.resume (runner re-dispatch + recovery_actions row + recovery.resumed event +
  // PAUSED current-state guard), the CLI face of POST /{runId}/resume. Mirrors WorkflowCommands
  // .takeover (positional runId, optional --actor-identity resolved with local-operator fallback,
  // ActorType.HUMAN hard-coded, NO --actor-type/--reviewer-role — the service hard-codes
  // workflow_owner). Divergence from takeover: --reason is OPTIONAL (the service accepts a null
  // reason) and --format text|json is supported (the operator-command idiom, like status/diagnose).
  @Command(
      name = "resume",
      description =
          "Resume a Paused governed workflow run back to its prior executing state (story 4.10 —"
              + " CLI/REST equivalence). Re-dispatches the runner, records a recovery_actions row +"
              + " recovery.resumed audit event, and stamps the resolved actor onto the audit trail."
              + " Idempotent under --idempotency-key. A run that is not Paused ⇒"
              + " RESUME_NOT_APPLICABLE. --format=json emits a stable-schema document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String resume(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "reason",
              description = "Optional operator note (why the run is being resumed)",
              required = false)
          String reason,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      // Parse --format INSIDE the try (before the mutation) so an unsupported value raises
      // INVALID_COMMAND_PAYLOAD without performing the resume, and still emits the completion log +
      // runs the finally/endScope (no leaked MDC correlation-id scope).
      boolean json = isJson(format);
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      ActorContext actor = new ActorContext(resolvedActor, ActorType.HUMAN, resolvedCorrelation);
      ResumeRecoveryResult result =
          recoveryService.resume(runId, resolvedIdempotencyKey, actor, reason);
      String rendered =
          json
              ? outputs.renderOperatorResumeJson(runId, result)
              : renderResumeText(
                  result,
                  resolvedCorrelation,
                  resolvedIdempotencyKey,
                  idempotencyKey == null,
                  verbose);
      emitResume(resolvedCorrelation, runId, start, OUTCOME_SUCCESS);
      // Audit the reason length only — the free-form operator prose is never logged verbatim
      // (reason is optional for resume, so null-guard the length).
      log.info(
          "operator resume reason supplied correlationId={} workflowRunId={} reasonLength={}",
          resolvedCorrelation,
          MdcKeys.sanitizeForLog(runId),
          reason == null ? 0 : reason.length());
      return rendered;
    } catch (DomainException de) {
      emitResume(resolvedCorrelation, runId, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitResume(resolvedCorrelation, runId, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  // Text render for `operator resume` (JSON goes through
  // WorkflowCommandOutputs.renderOperatorResumeJson).
  // The verbose footer (correlation-id + generated-idempotency-key) is appended to TEXT output only
  // —
  // gluing it onto JSON would break the operator-resume.v1 schema for machine consumers.
  private static String renderResumeText(
      ResumeRecoveryResult result,
      String resolvedCorrelation,
      String resolvedIdempotencyKey,
      boolean idempotencyKeyGenerated,
      boolean verbose) {
    StringBuilder output =
        new StringBuilder()
            .append(result.recoveryActionPublicId())
            .append(" resume submitted (state: ")
            .append(result.resultingState() == null ? "unknown" : result.resultingState().value())
            .append(")");
    if (result.newRunnerExecutionPublicId() != null) {
      output.append(" [runner-execution: ").append(result.newRunnerExecutionPublicId()).append(']');
    }
    if (result.replayed()) {
      output.append(" [replayed]");
    }
    if (verbose) {
      output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      if (idempotencyKeyGenerated) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
    }
    return output.toString();
  }

  private static void emitResume(String correlationId, String runId, long start, String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "operator command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME_RESUME,
        MdcKeys.sanitizeForLog(runId),
        outcome,
        elapsedMs);
  }

  // Story 4.11 (AC6) — the CLI face of POST /{runId}/reconcile. Resolves an unresolved integration
  // conflict on a non-terminal run through the RICH RecoveryService.reconcile (per-run advisory
  // lock + recovery_actions row + recovery.reconciled event + integration_conflicts resolveConflict
  // + external-sync side-effects), NOT the transition-only
  // WorkflowCommandService.reconcileWorkflow.
  // Mirrors `operator resume` (positional runId, optional --actor-identity → local-operator,
  // ActorType.HUMAN hard-coded, --format text|json). Divergence from resume: --conflict +
  // --decision
  // are required domain inputs; --decision is passed as a raw String so the service surfaces the
  // typed MISSING_/INVALID_RECONCILIATION_DECISION codes (Reconciliation 6). --reason is passed
  // straight through (required=false) so the service's replay pre-check — which tolerates an
  // omitted
  // reason on an idempotent retry (RecoveryService.java:1568-1582) — stays reachable, matching the
  // REST DTO (code review 2026-07-13). A blank/omitted reason on a FRESH reconcile still surfaces
  // INVALID_COMMAND_PAYLOAD from the service.
  @Command(
      name = "reconcile",
      description =
          "Reconcile an unresolved integration conflict on a non-terminal governed run per an"
              + " explicit --decision (story 4.11 — CLI/REST equivalence). Records a recovery_actions"
              + " row + recovery.reconciled audit event, closes the integration_conflicts row,"
              + " applies the decision's external-sync side-effects, and stamps the resolved actor"
              + " onto the audit trail. Idempotent under --idempotency-key. A terminal run ⇒"
              + " RECONCILE_NOT_APPLICABLE. --format=json emits a stable-schema document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String reconcile(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "conflict",
              description = "Public id of the unresolved integration conflict (icf_...)",
              required = true)
          String conflictId,
      @Option(
              longName = "decision",
              description =
                  "Reconciliation decision: accept_external_state | accept_internal_state |"
                      + " mark_completed_externally | mark_failed_externally",
              required = true)
          String decision,
      @Option(
              longName = "reason",
              description =
                  "Operator note explaining the reconciliation decision (required on a fresh"
                      + " reconcile; validated by the service, not the CLI, so an idempotent replay"
                      + " that omits it still replays)",
              required = false)
          String reason,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      // Parse --format INSIDE the try (before the mutation) so an unsupported value raises
      // INVALID_COMMAND_PAYLOAD without performing the reconcile, and still emits the completion
      // log
      // + runs the finally/endScope (no leaked MDC correlation-id scope).
      boolean json = isJson(format);
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      ActorContext actor = new ActorContext(resolvedActor, ActorType.HUMAN, resolvedCorrelation);
      // conflict/decision/reason pass straight through: the service is the single source of
      // decision
      // + reason validation (typed MISSING_/INVALID_RECONCILIATION_DECISION +
      // INVALID_COMMAND_PAYLOAD
      // on a blank reason), so the CLI stays symmetric with the REST surface (Reconciliation 6/6b).
      ReconcileRecoveryResult result =
          recoveryService.reconcile(
              runId, conflictId, decision, resolvedIdempotencyKey, actor, reason);
      String rendered =
          json
              ? outputs.renderOperatorReconcileJson(runId, result)
              : renderReconcileText(
                  result,
                  resolvedCorrelation,
                  resolvedIdempotencyKey,
                  idempotencyKey == null,
                  verbose);
      emitReconcile(resolvedCorrelation, runId, start, OUTCOME_SUCCESS);
      // Audit the decision value + reason length only; the free-form operator prose is never logged
      // verbatim. `decision` is sanitized like the other identifiers: on a fresh reconcile the
      // service validated it to a governed enum, but on an idempotency replay parseDecision is
      // skipped (RecoveryService.java:1568-1582), so the raw client string can still reach here.
      log.info(
          "operator reconcile decision applied correlationId={} workflowRunId={} conflictId={} decision={} reasonLength={}",
          resolvedCorrelation,
          MdcKeys.sanitizeForLog(runId),
          MdcKeys.sanitizeForLog(conflictId),
          MdcKeys.sanitizeForLog(decision),
          reason == null ? 0 : reason.length());
      return rendered;
    } catch (DomainException de) {
      emitReconcile(resolvedCorrelation, runId, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitReconcile(resolvedCorrelation, runId, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  // Text render for `operator reconcile` (JSON goes through
  // WorkflowCommandOutputs.renderOperatorReconcileJson). The verbose footer (correlation-id +
  // generated-idempotency-key) is appended to TEXT output only — gluing it onto JSON would break
  // the
  // operator-reconcile.v1 schema for machine consumers.
  private static String renderReconcileText(
      ReconcileRecoveryResult result,
      String resolvedCorrelation,
      String resolvedIdempotencyKey,
      boolean idempotencyKeyGenerated,
      boolean verbose) {
    StringBuilder output =
        new StringBuilder()
            .append(result.recoveryActionPublicId())
            .append(" reconcile submitted (state: ")
            .append(result.resultingState() == null ? "unknown" : result.resultingState().value())
            .append(')');
    if (result.resolvedConflictId() != null) {
      output.append(" [conflict: ").append(result.resolvedConflictId()).append(']');
    }
    if (result.replayed()) {
      output.append(" [replayed]");
    }
    if (verbose) {
      output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      if (idempotencyKeyGenerated) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
    }
    return output.toString();
  }

  private static void emitReconcile(
      String correlationId, String runId, long start, String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "operator command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME_RECONCILE,
        MdcKeys.sanitizeForLog(runId),
        outcome,
        elapsedMs);
  }

  // Story 4.12 (AC6) — the CLI face of POST /{runId}/rerun-from-step. Reruns a Failed or
  // WaitingForReview run from a SafeRerunStep boundary through the RICH
  // RecoveryService.rerunFromStep
  // (RERUN_SOURCE_STATES gate + prior-approval invalidation + recovery_actions row +
  // recovery.rerunFromStep event + runner re-enqueue + best-effort Linear reopen), NOT the
  // transition-only WorkflowCommandService.rerunFromStepWorkflow. Mirrors `operator
  // resume`/`operator
  // reconcile` (positional runId, optional --actor-identity → local-operator, ActorType.HUMAN
  // hard-coded, --format text|json). Divergence: --target + --reason are required domain inputs but
  // both are passed as raw Strings with required=false so the SERVICE surfaces the typed
  // INVALID_RERUN_TARGET_STEP / MISSING_REASON_TEXT codes on both surfaces (Reconciliation 6/OQ-4)
  // —
  // keeping the CLI symmetric with the REST DTO, which does NOT @NotBlank them either. The
  // idempotency replay pre-check tolerates an omitted --reason on a genuine retry
  // (RecoveryService.java:1891-1902).
  @Command(
      name = "rerun-from-step",
      description =
          "Rerun a Failed or WaitingForReview governed run from a safe step boundary"
              + " (investigating|executing) (story 4.12 — CLI/REST equivalence). Invalidates the"
              + " prior approval at that boundary, records a recovery_actions row +"
              + " recovery.rerunFromStep audit event, re-enqueues the runner, and stamps the resolved"
              + " actor onto the audit trail. Idempotent under --idempotency-key. A run outside"
              + " {Failed, WaitingForReview} ⇒ ILLEGAL_TRANSITION. --format=json emits a"
              + " stable-schema document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String rerunFromStep(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "target",
              description =
                  "Safe step boundary to rerun from: investigating | executing (validated by the"
                      + " service, not the CLI, so the typed INVALID_RERUN_TARGET_STEP is reachable)",
              required = false)
          String target,
      @Option(
              longName = "reason",
              description =
                  "Operator note explaining the rerun (required on a fresh rerun; validated by the"
                      + " service, not the CLI, so an idempotent replay that omits it still replays)",
              required = false)
          String reason,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      // Parse --format INSIDE the try (before the mutation) so an unsupported value raises
      // INVALID_COMMAND_PAYLOAD without performing the rerun, and still emits the completion log +
      // runs the finally/endScope (no leaked MDC correlation-id scope).
      boolean json = isJson(format);
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      ActorContext actor = new ActorContext(resolvedActor, ActorType.HUMAN, resolvedCorrelation);
      // target/reason pass straight through: the service is the single source of target + reason
      // validation (typed INVALID_RERUN_TARGET_STEP + MISSING_REASON_TEXT), so the CLI stays
      // symmetric with the REST surface (Reconciliation 6/OQ-4). Note the arg order — targetStep is
      // SECOND, before idempotencyKey (Reconciliation 4).
      RerunFromStepRecoveryResult result =
          recoveryService.rerunFromStep(runId, target, resolvedIdempotencyKey, actor, reason);
      String rendered =
          json
              ? outputs.renderOperatorRerunFromStepJson(runId, result)
              : renderRerunFromStepText(
                  result,
                  resolvedCorrelation,
                  resolvedIdempotencyKey,
                  idempotencyKey == null,
                  verbose);
      emitRerunFromStep(resolvedCorrelation, runId, start, OUTCOME_SUCCESS);
      // Audit the target value + reason length only; the free-form operator prose is never logged
      // verbatim. `target` is sanitized like the other identifiers: on a fresh rerun the service
      // validated it to a governed enum, but on an idempotency replay resolveTargetState is still
      // called yet the raw client string reaches here first, so sanitize defensively.
      log.info(
          "operator rerun-from-step target applied correlationId={} workflowRunId={} targetStep={} reasonLength={}",
          resolvedCorrelation,
          MdcKeys.sanitizeForLog(runId),
          MdcKeys.sanitizeForLog(target),
          reason == null ? 0 : reason.length());
      return rendered;
    } catch (DomainException de) {
      emitRerunFromStep(resolvedCorrelation, runId, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitRerunFromStep(resolvedCorrelation, runId, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  // Text render for `operator rerun-from-step` (JSON goes through
  // WorkflowCommandOutputs.renderOperatorRerunFromStepJson). The verbose footer (correlation-id +
  // generated-idempotency-key) is appended to TEXT output only — gluing it onto JSON would break
  // the
  // operator-rerun-from-step.v1 schema for machine consumers.
  private static String renderRerunFromStepText(
      RerunFromStepRecoveryResult result,
      String resolvedCorrelation,
      String resolvedIdempotencyKey,
      boolean idempotencyKeyGenerated,
      boolean verbose) {
    StringBuilder output =
        new StringBuilder()
            .append(result.recoveryActionPublicId())
            .append(" rerun-from-step submitted (state: ")
            .append(result.resultingState() == null ? "unknown" : result.resultingState().value())
            .append(')');
    if (result.newRunnerExecutionPublicId() != null) {
      output.append(" [runner-execution: ").append(result.newRunnerExecutionPublicId()).append(']');
    }
    if (result.replayed()) {
      output.append(" [replayed]");
    }
    if (verbose) {
      output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      if (idempotencyKeyGenerated) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
    }
    return output.toString();
  }

  private static void emitRerunFromStep(
      String correlationId, String runId, long start, String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "operator command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME_RERUN_FROM_STEP,
        MdcKeys.sanitizeForLog(runId),
        outcome,
        elapsedMs);
  }

  // Story 4.13 (AC6) — the CLI face of POST /{runId}/pause. Manually pauses a mid-flight run
  // through the RICH RecoveryService.pause (PAUSABLE_SOURCE_STATES gate + runner cancel-flip to
  // cancelled_for_pause + best-effort docker stop + recovery_actions row + recovery.paused event),
  // NOT the transition-only WorkflowCommandService.pauseWorkflow. Closest sibling to `operator
  // resume`: the SAME 4-positional-arg service call (runId, idempotencyKey, actor, reason) with NO
  // domain field before idempotencyKey (unlike reconcile's --conflict/--decision and rerun's
  // --target). Mirrors resume (positional runId, optional --actor-identity → local-operator,
  // ActorType.HUMAN hard-coded, --format text|json). Divergence from resume: --reason is REQUIRED
  // on a fresh pause but passed as a raw String with required=false so the SERVICE surfaces the
  // typed MISSING_REASON_TEXT on both surfaces (Reconciliation 5/OQ-4) — keeping the CLI symmetric
  // with the REST DTO, which does NOT @NotBlank it either. MISSING_REASON_TEXT fires BEFORE the
  // idempotency replay pre-check (RecoveryService.java:1145 vs :1150) because reasonText composes
  // the fingerprint identity, so an omitted reason is a 400 even on a same-key retry.
  @Command(
      name = "pause",
      description =
          "Manually pause a mid-flight governed run for operator intervention (story 4.13 —"
              + " CLI/REST equivalence). Halts orchestrator dispatch by cancelling in-flight +"
              + " queued runner work, records a recovery_actions row + recovery.paused audit event"
              + " (preserving the prior state resume returns to), and stamps the resolved actor onto"
              + " the audit trail. Idempotent under --idempotency-key. A run outside the pausable"
              + " states (terminal, already Paused, or TakenOver) ⇒ PAUSE_NOT_APPLICABLE."
              + " --format=json emits a stable-schema document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String pause(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "reason",
              description =
                  "Operator note explaining the pause (required on a fresh pause; validated by the"
                      + " service, not the CLI, so the typed MISSING_REASON_TEXT is reachable and"
                      + " fires before the idempotency replay check)",
              required = false)
          String reason,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      // Parse --format INSIDE the try (before the mutation) so an unsupported value raises
      // INVALID_COMMAND_PAYLOAD without performing the pause, and still emits the completion log +
      // runs the finally/endScope (no leaked MDC correlation-id scope).
      boolean json = isJson(format);
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      ActorContext actor = new ActorContext(resolvedActor, ActorType.HUMAN, resolvedCorrelation);
      // reason passes straight through: the service is the single source of reason validation
      // (typed MISSING_REASON_TEXT), so the CLI stays symmetric with the REST surface
      // (Reconciliation
      // 5/OQ-4). Same 4-positional-arg shape as resume — reason is LAST, no domain field before
      // idempotencyKey (Reconciliation 4).
      PauseRecoveryResult result =
          recoveryService.pause(runId, resolvedIdempotencyKey, actor, reason);
      String rendered =
          json
              ? outputs.renderOperatorPauseJson(runId, result)
              : renderPauseText(
                  result,
                  resolvedCorrelation,
                  resolvedIdempotencyKey,
                  idempotencyKey == null,
                  verbose);
      emitPause(resolvedCorrelation, runId, start, OUTCOME_SUCCESS);
      // Audit the reason length only — the free-form operator prose is never logged verbatim
      // (null-guard the length; the service surfaces MISSING_REASON_TEXT on a blank reason).
      log.info(
          "operator pause reason supplied correlationId={} workflowRunId={} reasonLength={}",
          resolvedCorrelation,
          MdcKeys.sanitizeForLog(runId),
          reason == null ? 0 : reason.length());
      return rendered;
    } catch (DomainException de) {
      emitPause(resolvedCorrelation, runId, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitPause(resolvedCorrelation, runId, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  // Text render for `operator pause` (JSON goes through
  // WorkflowCommandOutputs.renderOperatorPauseJson). The verbose footer (correlation-id +
  // generated-idempotency-key) is appended to TEXT output only — gluing it onto JSON would break
  // the operator-pause.v1 schema for machine consumers. priorState is null-guarded (may be null on
  // a degenerate replay per the PauseRecoveryResult javadoc).
  private static String renderPauseText(
      PauseRecoveryResult result,
      String resolvedCorrelation,
      String resolvedIdempotencyKey,
      boolean idempotencyKeyGenerated,
      boolean verbose) {
    StringBuilder output =
        new StringBuilder()
            .append(result.recoveryActionPublicId())
            .append(" pause submitted (state: ")
            .append(result.resultingState() == null ? "unknown" : result.resultingState().value())
            .append(')');
    if (result.priorState() != null) {
      output.append(" [prior: ").append(result.priorState().value()).append(']');
    }
    output
        .append(" [cancelled: inFlight=")
        .append(result.cancelledInFlightCount())
        .append(" queued=")
        .append(result.cancelledQueuedCount())
        .append(']');
    if (result.replayed()) {
      output.append(" [replayed]");
    }
    if (verbose) {
      output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      if (idempotencyKeyGenerated) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
    }
    return output.toString();
  }

  private static void emitPause(String correlationId, String runId, long start, String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "operator command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME_PAUSE,
        MdcKeys.sanitizeForLog(runId),
        outcome,
        elapsedMs);
  }

  // Story 4.14 (AC6) — the CLI face of POST /{runId}/classify-failure. Applies a governed
  // FailureTaxonomyValue to a FAILED run through the RICH RecoveryService.classifyFailure
  // (idempotency
  // replay pre-check + taxonomy parse/deprecation guard + FAILED-only gate + failure_classification
  // column write + recovery_actions row + recovery.failureClassified event). There is NO
  // thin-service
  // twin — classify performs no transition, so it never joined the WorkflowCommand family (story
  // 4.9
  // R8). Mirrors `operator resume`/`reconcile`/`pause` (positional runId, optional --actor-identity
  // →
  // local-operator, ActorType.HUMAN hard-coded, --format text|json). Divergences from reconcile:
  // --taxonomy is a single required domain input passed as a raw String (required=false at the
  // shell)
  // so the service surfaces the typed MISSING_/INVALID_/DEPRECATED_TAXONOMY_VALUE codes on both
  // surfaces (Reconciliation 4 / OQ-4), and --reason is GENUINELY optional — the service stores a
  // blank/omitted reason as null with NO MISSING_REASON_TEXT (Reconciliation 5, the divergence from
  // reconcile/rerun/pause). The service call is FIVE positional args, taxonomy SECOND
  // (Reconciliation
  // 7).
  @Command(
      name = "classify-failure",
      description =
          "Classify a FAILED governed run with a governed failure taxonomy via --taxonomy (story"
              + " 4.14 — CLI/REST equivalence). Writes the workflow_runs.failure_classification"
              + " triple, records a recovery_actions row + recovery.failureClassified audit event"
              + " capturing any prior taxonomy value, and stamps the resolved actor onto the audit"
              + " trail. Pure metadata — no state transition. Idempotent under --idempotency-key. A"
              + " run not in the Failed state ⇒ CLASSIFY_NOT_APPLICABLE. --format=json emits a"
              + " stable-schema document.",
      exitStatusExceptionMapper = WorkflowCliExitStatusExceptionMapper.BEAN_NAME)
  public String classifyFailure(
      @Argument(index = 0, description = "Workflow run public id (run_...)") String runId,
      @Option(
              longName = "taxonomy",
              description =
                  "Failure-taxonomy wire value: specification_gap | context_gap |"
                      + " agent_execution_failure | review_rejection | integration_or_merge_failure"
                      + " | tooling_or_infrastructure_failure (validated by the service, not the"
                      + " CLI, so the typed MISSING_/INVALID_/DEPRECATED_TAXONOMY_VALUE codes stay"
                      + " reachable)",
              required = false)
          String taxonomy,
      @Option(
              longName = "reason",
              description =
                  "Optional operator note explaining the classification (genuinely optional — a"
                      + " blank/omitted reason is stored as null)",
              required = false)
          String reason,
      @Option(longName = "idempotency-key", description = "Idempotency key", required = false)
          String idempotencyKey,
      @Option(longName = "actor-identity", description = "Actor identity", required = false)
          String actorIdentity,
      @Option(longName = "correlation-id", description = "Correlation ID", required = false)
          String correlationId,
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "verbose",
              description = "Print additional command metadata",
              required = false,
              defaultValue = "false")
          boolean verbose) {
    long start = System.nanoTime();
    CorrelationScope scope = pushCorrelation(correlationId);
    String resolvedCorrelation = scope.resolved();
    try {
      // Parse --format INSIDE the try (before the mutation) so an unsupported value raises
      // INVALID_COMMAND_PAYLOAD without performing the classify, and still emits the completion log
      // + runs the finally/endScope (no leaked MDC correlation-id scope).
      boolean json = isJson(format);
      String resolvedIdempotencyKey =
          idempotencyKeyValidator.requireValid(resolveIdempotencyKey(idempotencyKey));
      String resolvedActor = resolveActorIdentity(actorIdentity);
      ActorContext actor = new ActorContext(resolvedActor, ActorType.HUMAN, resolvedCorrelation);
      // taxonomy/reason pass straight through: the service is the single source of taxonomy
      // validation (typed MISSING_/INVALID_/DEPRECATED_TAXONOMY_VALUE) and treats a blank/omitted
      // reason as null (no MISSING_REASON_TEXT — Reconciliation 5), so the CLI stays symmetric with
      // the REST surface. FIVE positional args, taxonomy SECOND (Reconciliation 7).
      ClassifyFailureResult result =
          recoveryService.classifyFailure(runId, taxonomy, resolvedIdempotencyKey, actor, reason);
      String rendered =
          json
              ? outputs.renderOperatorClassifyFailureJson(runId, result)
              : renderClassifyFailureText(
                  result,
                  resolvedCorrelation,
                  resolvedIdempotencyKey,
                  idempotencyKey == null,
                  verbose);
      emitClassifyFailure(resolvedCorrelation, runId, start, OUTCOME_SUCCESS);
      // Audit the applied taxonomy value + reason length only; the free-form operator prose is
      // never
      // logged verbatim. `taxonomy` is sanitized like the other identifiers: on a fresh classify
      // the
      // service validated it to a governed enum, but on an idempotency replay parseTaxonomyValue is
      // skipped (RecoveryService.java:1736 before :1751), so the raw client string can still reach
      // here. reasonLength is null-guarded (reason is genuinely optional).
      log.info(
          "operator classify-failure applied correlationId={} workflowRunId={} taxonomyValue={} reasonLength={}",
          resolvedCorrelation,
          MdcKeys.sanitizeForLog(runId),
          MdcKeys.sanitizeForLog(taxonomy),
          reason == null ? 0 : reason.length());
      return rendered;
    } catch (DomainException de) {
      emitClassifyFailure(resolvedCorrelation, runId, start, codeFor(de));
      throw de;
    } catch (RuntimeException re) {
      emitClassifyFailure(resolvedCorrelation, runId, start, OUTCOME_UNKNOWN);
      throw re;
    } finally {
      MdcKeys.endScope(MdcKeys.CORRELATION_ID, scope.prior());
    }
  }

  // Text render for `operator classify-failure` (JSON goes through
  // WorkflowCommandOutputs.renderOperatorClassifyFailureJson). The verbose footer (correlation-id +
  // generated-idempotency-key) is appended to TEXT output only — gluing it onto JSON would break
  // the
  // operator-classify-failure.v1 schema for machine consumers. There is NO state field (classify is
  // pure metadata — Reconciliation 6); priorTaxonomyValue is null-guarded (null on a first
  // classify).
  private static String renderClassifyFailureText(
      ClassifyFailureResult result,
      String resolvedCorrelation,
      String resolvedIdempotencyKey,
      boolean idempotencyKeyGenerated,
      boolean verbose) {
    StringBuilder output =
        new StringBuilder()
            .append(result.recoveryActionPublicId())
            .append(" classify submitted (taxonomy: ")
            .append(result.taxonomyValue())
            .append(')');
    if (result.priorTaxonomyValue() != null) {
      output.append(" [prior: ").append(result.priorTaxonomyValue()).append(']');
    }
    if (result.replayed()) {
      output.append(" [replayed]");
    }
    if (verbose) {
      output.append(" [correlation-id: ").append(resolvedCorrelation).append(']');
      if (idempotencyKeyGenerated) {
        output.append(" [generated-idempotency-key: ").append(resolvedIdempotencyKey).append(']');
      }
    }
    return output.toString();
  }

  private static void emitClassifyFailure(
      String correlationId, String runId, long start, String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "operator command completed correlationId={} commandName={} workflowRunId={} outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME_CLASSIFY_FAILURE,
        MdcKeys.sanitizeForLog(runId),
        outcome,
        elapsedMs);
  }

  // Story 4.10 — mirror WorkflowCommands.resolveActorIdentity: fail-closed on unsafe values, then
  // resolve with the local-operator property fallback for null/blank (never returns blank, so the
  // ActorContext non-blank invariant holds).
  private String resolveActorIdentity(String supplied) {
    localActorIdentityResolver.requireSafe(supplied);
    return localActorIdentityResolver.resolve(supplied);
  }

  // Story 4.10 — mirror WorkflowCommands.resolveIdempotencyKey: an explicit key passes through; an
  // omitted key auto-generates only for an interactive TTY, else surfaces MISSING_IDEMPOTENCY_KEY
  // so
  // a scripted caller must supply one (idempotency is the caller's contract).
  private String resolveIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey != null) {
      return idempotencyKey;
    }
    if (interactivity.isInteractive()) {
      return generatedIdempotencyKeySupplier.get();
    }
    throw idempotencyKeyValidator.missingKeyException();
  }

  // Format is a RENDER choice made by this adapter (mirrors WorkflowCommands/DoctorCommands); an
  // unsupported value raises INVALID_COMMAND_PAYLOAD so the exit-status mapper applies. Token /
  // since / limit resolution stays in the service (AC9).
  private boolean isJson(String format) {
    if (format == null || format.isBlank()) {
      return false;
    }
    String normalized = format.trim().toLowerCase(Locale.ROOT);
    if (FORMAT_TEXT.equals(normalized)) {
      return false;
    }
    if (FORMAT_JSON.equals(normalized)) {
      return true;
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("format", format);
    details.put("supportedFormats", List.of(FORMAT_TEXT, FORMAT_JSON));
    throw new DomainException(
        DomainErrorCode.INVALID_COMMAND_PAYLOAD, "Unsupported --format value: " + format, details);
  }

  private static List<String> splitStateTokens(String state) {
    List<String> tokens = new ArrayList<>();
    if (state == null || state.isBlank()) {
      return tokens;
    }
    for (String token : state.split(",")) {
      String trimmed = token.trim();
      if (!trimmed.isEmpty()) {
        tokens.add(trimmed);
      }
    }
    return tokens;
  }

  private CorrelationScope pushCorrelation(String supplied) {
    String resolved = supplied;
    if (resolved == null || resolved.isBlank()) {
      resolved = correlationIdSupplier.get();
    }
    resolved = MdcKeys.sanitizeForLog(resolved);
    String prior = MdcKeys.beginScope(MdcKeys.CORRELATION_ID, resolved);
    return new CorrelationScope(resolved, prior);
  }

  record CorrelationScope(String resolved, String prior) {}

  // Story 4.1 (AC7) — structured completion log. No runId here (multi-run read), so emit the
  // resolved filter (states/since/limit) + resultCount in place of workflowRunId. User-supplied
  // filter values are sanitized against CR/LF log injection.
  private static void emitSuccess(
      String correlationId, String states, String since, int limit, int resultCount, long start) {
    emit(correlationId, states, since, limit, resultCount, start, OUTCOME_SUCCESS);
  }

  private static void emitFailure(
      String correlationId,
      String states,
      String since,
      int limit,
      int resultCount,
      long start,
      String outcome) {
    emit(correlationId, states, since, limit, resultCount, start, outcome);
  }

  private static void emit(
      String correlationId,
      String states,
      String since,
      int limit,
      int resultCount,
      long start,
      String outcome) {
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    log.info(
        "operator command completed correlationId={} commandName={} states={} since={} limit={} resultCount={} outcome={} durationMs={}",
        correlationId,
        COMMAND_NAME,
        MdcKeys.sanitizeForLog(states),
        MdcKeys.sanitizeForLog(since),
        limit,
        resultCount,
        outcome,
        elapsedMs);
  }

  private static String codeFor(DomainException de) {
    return de.errorCode() == null
        ? OUTCOME_UNKNOWN
        : OUTCOME_FAILURE_PREFIX + de.errorCode().value();
  }
}
