package org.dradgo.application.workflow.spi;

import java.time.OffsetDateTime;

/**
 * Story 4.1 (AC2) — one intentionally-lossy fleet row returned by {@link
 * OperatorRunReadPort#listOperatorRuns}. The persistence adapter joins {@code workflow_runs} to its
 * latest / latest-Failed / earliest {@code workflow_events} and its active typed {@code
 * integration_links}; the service maps this SPI snapshot onto the CLI-facing {@code
 * WorkflowInspectionService.OperatorRunRow} (turning {@code currentState} into the {@code
 * WorkflowState} enum). Never imported by the CLI (story 4.1 Reconciliation 2).
 *
 * <p>Nullable reference fields ({@code failureCategory}, {@code lastTransitionAt}, {@code
 * actorIdentity}, {@code linkedTicketRef}, {@code linkedPrRef}, {@code oldestEventAt}) are plain
 * nullable references — a run with no events yet has {@code null} transition/actor/oldest fields.
 *
 * @param runId the run public id ({@code run_...})
 * @param currentState the run's current state wire string (e.g. {@code "Failed"})
 * @param failureCategory the latest Failed transition's failure category wire string, or {@code
 *     null}
 * @param lastTransitionAt the latest event's {@code created_at}, or {@code null} when the run has
 *     no events
 * @param actorIdentity the latest event's actor identity, or {@code null}
 * @param linkedTicketRef the active {@code linear} link's external ref, or {@code null}
 * @param linkedPrRef the active {@code github_pr} link's external ref, or {@code null}
 * @param escalationMarker the run's {@code escalation_marker_set} column
 * @param oldestEventAt {@code MIN(workflow_events.created_at)} for the run, or {@code null}
 * @param operatorSignifier the UPPERCASE display signifier derived server-side from the run's
 *     matched predicate ({@code ORPHANED} / {@code FAILED} / {@code TAKENOVER} / {@code STALLED} /
 *     {@code OVERRIDDEN} / else the uppercased current-state wire string). Derived in SQL so the
 *     renderer needs no matched-token and cannot mislabel an active {@code overridden} run as
 *     {@code STALLED} (story 4.1 AC4 / Reconciliation 9).
 */
public record OperatorRunRowSnapshot(
    String runId,
    String currentState,
    String failureCategory,
    OffsetDateTime lastTransitionAt,
    String actorIdentity,
    String linkedTicketRef,
    String linkedPrRef,
    boolean escalationMarker,
    OffsetDateTime oldestEventAt,
    String operatorSignifier) {}
