package org.dradgo.domain.registry;

import java.util.List;

/**
 * Single source of truth for {@code workflow_events.details} JSON keys that flow across multiple
 * surfaces — producer ({@code RecoveryService}, {@code WorkflowCommandService}), allow-list ({@code
 * WorkflowInspectionService}), JSON schema ({@code workflow-history.v1.schema.json}), and the
 * PostgreSQL-native query in {@code WorkflowEventRepository}.
 *
 * <p>Previously these keys were duplicated as string literals across four files and a JPQL
 * annotation; a rename in any one place silently broke another. Story 1.18 review batch 3 (D1)
 * centralized them here and added a contract test that pins the holder against the JSON schema and
 * the allow-list. The SQL string literal in {@code WorkflowEventRepository} cannot reference a Java
 * constant inside an {@code @Query} annotation, so the contract test compares the JPQL source text
 * to {@link #CORRELATION_ID}.
 *
 * <p>Two groups are exposed:
 *
 * <ul>
 *   <li>{@link #ALLOW_LISTED_KEYS} — keys that flow into the CLI {@code history} render payload.
 *   <li>{@link #SERVER_ONLY_KEYS} — keys persisted in {@code workflow_events.details} but
 *       intentionally stripped before render (today: {@link #IDEMPOTENCY_KEY}).
 * </ul>
 */
public final class WorkflowEventDetailKeys {

  // Allow-listed (visible in `history`)
  public static final String LINEAR_TICKET_REFERENCE = "linearTicketReference";
  public static final String ARTIFACT_ID = "artifactId";
  public static final String ARTIFACT_VERSION = "artifactVersion";
  public static final String CONTEXT_VERSION = "contextVersion";
  public static final String CORRELATION_ID = "correlationId";
  public static final String CHILD_RUN_IDS = "childRunIds";

  // Recovery-event audit keys (added story 1.18 review F530)
  public static final String FAILED_STAGE = "failedStage";
  public static final String TRIGGERING_EVENT_ID = "triggeringEventId";
  public static final String REASON = "reason";
  public static final String ERROR_CODE = "errorCode";
  public static final String ERROR_CLASS = "errorClass";
  public static final String RECOVERY_ACTION_ID = "recoveryActionId";
  public static final String RECOVERY_RETRIED_EVENT_ID = "recoveryRetriedEventId";
  public static final String COMPENSATION_FAILED = "compensationFailed";
  public static final String REVIEWER_ROLE = "reviewerRole";
  public static final String TAGGED_FEEDBACK = "taggedFeedback";
  public static final String SPEC_REJECTION_LOOP_COUNT = "specRejectionLoopCount";
  public static final String ESCALATION_MARKER = "escalationMarker";

  // Clarification audit keys (added story 2.11). priorAnswerText / questionText / answerText are
  // NEVER allow-listed — they may carry product-specific free-form content; CLI history strips
  // them like IDEMPOTENCY_KEY. Trap T12.
  public static final String CLARIFICATION_ID = "clarificationId";
  public static final String QUESTION_ID = "questionId";
  public static final String PRIOR_ANSWER_TEXT = "priorAnswerText";

  // Clarification lifecycle keys (added story 2.12). All four are allow-listed: the IDs are
  // public references; noEffectReason is controlled-vocabulary token (Trap T2), NOT free-form
  // reviewer text — safe to log + surface in CLI history.
  public static final String INCORPORATED_INTO_ARTIFACT_ID = "incorporatedIntoArtifactId";
  public static final String INCORPORATION_EVENT_ID = "incorporationEventId";
  public static final String SUPERSEDED_BY_ARTIFACT_ID = "supersededByArtifactId";
  public static final String NO_EFFECT_REASON = "noEffectReason";

  // Runner lifecycle keys (added story 3.2). All allow-listed: container/runner ids and image
  // refs are non-secret operator forensics; timestamps and status fields are derived signals.
  // workspaceRoot is a filesystem path under DELIVERYLINE_HOME; redaction posture from story 1.10
  // covers absolute-path log lines so the value is safe to surface in CLI history.
  public static final String RUNNER_EXECUTION_ID = "runnerExecutionId";
  public static final String WORKFLOW_RUN_ID = "workflowRunId";
  public static final String CONTAINER_ID = "containerId";
  public static final String WORKSPACE_ROOT = "workspaceRoot";
  public static final String IMAGE = "image";
  public static final String RUNNER_KIND = "runnerKind";
  public static final String FAILURE_CATEGORY = "failureCategory";
  public static final String LAST_ACTIVITY_AT = "lastActivityAt";
  public static final String TIMEOUT_AT = "timeoutAt";
  public static final String DISPATCHED_AT = "dispatchedAt";
  public static final String EXIT_CODE = "exitCode";

  // Runner log-capture keys (added story 3.6 AC6). Metadata only — NEVER log content or secret
  // values. redactionCount is a non-negative integer; rawOutputByteSize a non-negative long;
  // rawOutputClassification a DataClassification wire value. All allow-listed so CLI history
  // surfaces the capture metrics on the enriched RUNNER_COMPLETED / RUNNER_FAILED events.
  public static final String REDACTION_COUNT = "redactionCount";
  public static final String RAW_OUTPUT_BYTE_SIZE = "rawOutputByteSize";
  public static final String RAW_OUTPUT_CLASSIFICATION = "rawOutputClassification";

  // GitHub PR linkage keys (added story 3.15 AC2, emitted on INTEGRATION_LINKED). All allow-listed:
  // the canonical PR reference (org/repo#n), repository full name, branch, commit SHA, PR number,
  // and PR state are non-secret git/GitHub identifiers — the same shareable-redacted posture as the
  // external_metadata they mirror. NEVER carries the GitHub token, PR body, or any redacted field.
  public static final String GITHUB_PR_REFERENCE = "githubPrReference";
  public static final String REPOSITORY_FULL_NAME = "repositoryFullName";
  public static final String BRANCH = "branch";
  public static final String COMMIT_SHA = "commitSha";
  public static final String PR_NUMBER = "prNumber";
  public static final String PR_STATE = "prState";

  // Server-only (stripped from CLI history; visible only on the originating stdout)
  public static final String IDEMPOTENCY_KEY = "idempotencyKey";

  /** Keys allowed through {@code WorkflowInspectionService.ALLOWED_DETAIL_KEYS}. */
  public static final List<String> ALLOW_LISTED_KEYS =
      List.of(
          LINEAR_TICKET_REFERENCE,
          ARTIFACT_ID,
          ARTIFACT_VERSION,
          CONTEXT_VERSION,
          CORRELATION_ID,
          CHILD_RUN_IDS,
          FAILED_STAGE,
          TRIGGERING_EVENT_ID,
          REASON,
          ERROR_CODE,
          ERROR_CLASS,
          RECOVERY_ACTION_ID,
          RECOVERY_RETRIED_EVENT_ID,
          COMPENSATION_FAILED,
          REVIEWER_ROLE,
          TAGGED_FEEDBACK,
          SPEC_REJECTION_LOOP_COUNT,
          ESCALATION_MARKER,
          CLARIFICATION_ID,
          QUESTION_ID,
          INCORPORATED_INTO_ARTIFACT_ID,
          INCORPORATION_EVENT_ID,
          SUPERSEDED_BY_ARTIFACT_ID,
          NO_EFFECT_REASON,
          WORKFLOW_RUN_ID,
          RUNNER_EXECUTION_ID,
          CONTAINER_ID,
          WORKSPACE_ROOT,
          IMAGE,
          RUNNER_KIND,
          FAILURE_CATEGORY,
          LAST_ACTIVITY_AT,
          TIMEOUT_AT,
          DISPATCHED_AT,
          EXIT_CODE,
          REDACTION_COUNT,
          RAW_OUTPUT_BYTE_SIZE,
          RAW_OUTPUT_CLASSIFICATION,
          GITHUB_PR_REFERENCE,
          REPOSITORY_FULL_NAME,
          BRANCH,
          COMMIT_SHA,
          PR_NUMBER,
          PR_STATE);

  /**
   * Keys persisted in {@code workflow_events.details} but intentionally stripped from render.
   * {@link #PRIOR_ANSWER_TEXT} joins {@link #IDEMPOTENCY_KEY} here so the CLI never echoes the
   * free-form reviewer-supplied answer text from the event log (story 2.11 trap T12 — same
   * redaction posture as story 2.10's {@code reasonText}).
   */
  public static final List<String> SERVER_ONLY_KEYS = List.of(IDEMPOTENCY_KEY, PRIOR_ANSWER_TEXT);

  private WorkflowEventDetailKeys() {}
}
