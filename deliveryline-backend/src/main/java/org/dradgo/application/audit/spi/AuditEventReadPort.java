package org.dradgo.application.audit.spi;

/**
 * Story 4.3 (AC1/AC3/AC8) — the read seam backing {@code AuditQueryService}. A NEW dedicated port
 * (not a bend of the operator {@code OperatorRunReadPort} or the per-run {@code
 * WorkflowEventReadPort}): the by-ticket query joins events across ALL runs linked to a ticket via
 * {@code integration_links} — a cross-run read no existing finder performs (story 4.3
 * Reconciliation 7) — and both surfaces need keyset pagination + a full-set count, which the
 * existing readers do not provide.
 *
 * <p>Both list methods return an intentionally-lossy {@link AuditEventPageSnapshot} (raw {@code
 * reason} + {@code details}; the service redacts). Default no-op implementations so the lean
 * unit-test constructors of {@code AuditQueryService} need not implement this port; production
 * Spring wires the {@code adapters.persistence} implementation.
 */
public interface AuditEventReadPort {

  /**
   * The events for a single run ({@code query.scopeRef()} = run {@code public_id}), newest-first,
   * keyset-paginated, with the full filtered count.
   *
   * @param query resolved predicate parameters
   * @return the page + full-set count; {@link AuditEventPageSnapshot#empty()} when nothing matches
   */
  default AuditEventPageSnapshot listByRun(AuditEventQuery query) {
    return AuditEventPageSnapshot.empty();
  }

  /**
   * The events across ALL runs linked to a ticket ({@code query.scopeRef()} = ticket external ref),
   * newest-first, keyset-paginated, with the full filtered count. Includes superseded/archived
   * links (story 4.3 Reconciliation 7) so retried runs of the ticket are covered.
   *
   * @param query resolved predicate parameters
   * @return the page + full-set count; {@link AuditEventPageSnapshot#empty()} when nothing matches
   */
  default AuditEventPageSnapshot listByTicket(AuditEventQuery query) {
    return AuditEventPageSnapshot.empty();
  }

  /**
   * True when a {@code workflow_runs} row with this public id exists. Backs the by-run {@code
   * RUN_NOT_FOUND} (404) precondition (story 4.3 AC6) so an unknown run is a typed 404, not a
   * silent empty page (a by-ticket miss is a valid empty result, so no equivalent check there).
   *
   * @param workflowRunPublicId the {@code run_...} id
   * @return whether the run exists
   */
  default boolean runExists(String workflowRunPublicId) {
    return false;
  }
}
