/**
 * Story 4.2 (AC1/Reconciliation 6/OQ-GATE) — the operator-queue access-gating SEAM.
 *
 * The operator queue is FLEET-level; story 2.14's allowed-actions is PER-RUN (keyed on a single run
 * id), so it cannot gate a fleet view, and 4.2 deliberately does NOT add a governed
 * `view_operator_queue` AllowedAction (that would trip the 4-site registry lockstep for zero E4
 * benefit — E5 RBAC owns it). E4 is deferred-RBAC: any local user may view the queue.
 *
 * This hook is the forward-looking seam: it returns `true` for every user in E4. E5 replaces the
 * body with real role-based logic; the not-allowed branch (an `ErrorState variant="permissionRestricted"`
 * stub in the route) is a currently-unreachable placeholder so the contract is wired ahead of time.
 */
export function useCanViewOperatorQueue(): boolean {
  return true;
}
