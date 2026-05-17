package org.dradgo.application.integration;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.dradgo.domain.registry.IntegrationSyncStatus;

/**
 * Pure-Java state-machine guard for {@code integration_links.sync_status}. Used by the persistence
 * adapter to reject illegal transitions before they hit the DB, mirroring the {@code
 * RunnerExecutionStateMachine} pattern from story 1.13.
 *
 * <p>Allowed transitions (story 1.14 Task 2):
 *
 * <pre>
 *   linked     → synced | stale  | failed
 *   synced     → stale  | failed | superseded
 *   stale      → synced | failed | superseded
 *   failed     → linked | superseded
 *   superseded → (terminal)
 * </pre>
 *
 * <p>Reuses {@link DomainErrorCode#ILLEGAL_TRANSITION} — the registry already covers this shape;
 * the rejection details disambiguate the integration-link caller from the runner-execution caller.
 */
public final class IntegrationLinkStateMachine {

  private static final Map<IntegrationSyncStatus, Set<IntegrationSyncStatus>> ALLOWED;
  private static final Set<IntegrationSyncStatus> TERMINAL =
      EnumSet.of(IntegrationSyncStatus.SUPERSEDED);

  static {
    Map<IntegrationSyncStatus, Set<IntegrationSyncStatus>> rules =
        new EnumMap<>(IntegrationSyncStatus.class);
    rules.put(
        IntegrationSyncStatus.LINKED,
        EnumSet.of(
            IntegrationSyncStatus.SYNCED,
            IntegrationSyncStatus.STALE,
            IntegrationSyncStatus.FAILED));
    rules.put(
        IntegrationSyncStatus.SYNCED,
        EnumSet.of(
            IntegrationSyncStatus.STALE,
            IntegrationSyncStatus.FAILED,
            IntegrationSyncStatus.SUPERSEDED));
    rules.put(
        IntegrationSyncStatus.STALE,
        EnumSet.of(
            IntegrationSyncStatus.SYNCED,
            IntegrationSyncStatus.FAILED,
            IntegrationSyncStatus.SUPERSEDED));
    rules.put(
        IntegrationSyncStatus.FAILED,
        EnumSet.of(IntegrationSyncStatus.LINKED, IntegrationSyncStatus.SUPERSEDED));
    rules.put(IntegrationSyncStatus.SUPERSEDED, EnumSet.noneOf(IntegrationSyncStatus.class));
    ALLOWED = Map.copyOf(rules);
  }

  private IntegrationLinkStateMachine() {}

  public static boolean isTerminal(IntegrationSyncStatus status) {
    Objects.requireNonNull(status, "status");
    return TERMINAL.contains(status);
  }

  public static Set<IntegrationSyncStatus> allowedTargets(IntegrationSyncStatus from) {
    Objects.requireNonNull(from, "from");
    return ALLOWED.getOrDefault(from, Set.of());
  }

  public static void assertCanTransition(
      String integrationLinkPublicId, IntegrationSyncStatus from, IntegrationSyncStatus to) {
    Objects.requireNonNull(integrationLinkPublicId, "integrationLinkPublicId");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Set<IntegrationSyncStatus> targets = ALLOWED.getOrDefault(from, Set.of());
    if (!targets.contains(to)) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("integrationLinkId", integrationLinkPublicId);
      details.put("fromStatus", from.value());
      details.put("toStatus", to.value());
      details.put("reason", from == to ? "no_op_transition" : "transition_not_allowed");
      throw new DomainException(
          DomainErrorCode.ILLEGAL_TRANSITION,
          "Illegal integration-link transition "
              + from.value()
              + " → "
              + to.value()
              + " for "
              + integrationLinkPublicId,
          details);
    }
  }
}
