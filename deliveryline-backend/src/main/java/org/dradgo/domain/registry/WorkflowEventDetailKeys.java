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
          ESCALATION_MARKER);

  /** Keys persisted in {@code workflow_events.details} but intentionally stripped from render. */
  public static final List<String> SERVER_ONLY_KEYS = List.of(IDEMPOTENCY_KEY);

  private WorkflowEventDetailKeys() {}
}
