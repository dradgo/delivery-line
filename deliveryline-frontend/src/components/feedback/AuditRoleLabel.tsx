/**
 * Story 2.25 (Task 4 — AC8, D4) — `<AuditRoleLabel>`.
 *
 * Renders an actor role (e.g. `product_reviewer`) as an HONEST "recorded audit
 * label", never as an implied enforced permission. The clarifier — "recorded for
 * audit only — not an enforced permission" — is:
 *   • present in the ACCESSIBLE NAME (`aria-label`) so screen-reader users hear it;
 *   • shown as a `title` tooltip on hover/focus;
 *   • marked visibly with a `<small>` "(audit label)" cue so sighted users never
 *     read the role as authorization.
 *
 * Architecture invariant (architecture.md Frontend Quality Gates): "UI labels
 * must make clear that MVP roles are recorded audit labels, not enforced
 * authorization." This is the single sanctioned surface for rendering actor-role
 * text — the `no-bare-actor-role-text` ESLint rule forbids rendering role text
 * outside this wrapper. No component renders actor roles today (D4); this is
 * pre-positioned for the Epic-3a dev-review / operator surfaces.
 *
 * Presentational + query-free. `role` is TRUSTED, composite-authored text.
 */
import { cn } from '@/lib/utils';

/** The honest clarifier — the MVP defers RBAC; roles are audit metadata only. */
export const AUDIT_ROLE_CLARIFIER = 'recorded for audit only — not an enforced permission';

export interface AuditRoleLabelProps {
  /**
   * The recorded actor role, e.g. `product_reviewer` (TRUSTED). Named `actorRole`
   * NOT `role`: a `role="…"` prop trips `jsx-a11y/aria-role` at every call site
   * (it reads as the DOM ARIA role attribute).
   */
  actorRole: string;
  className?: string | undefined;
  testId?: string | undefined;
}

export function AuditRoleLabel({
  actorRole,
  className,
  testId = 'audit-role-label',
}: AuditRoleLabelProps) {
  return (
    <span
      data-testid={testId}
      data-audit-role={actorRole}
      className={cn('inline-flex items-baseline gap-1', className)}
      // The clarifier rides the accessible name so AT users never hear the role
      // as an enforced permission; the same text is the hover/focus tooltip.
      aria-label={`${actorRole} — ${AUDIT_ROLE_CLARIFIER}`}
      title={AUDIT_ROLE_CLARIFIER}
    >
      <span>{actorRole}</span>
      <small className="text-text-tertiary">(audit label)</small>
    </span>
  );
}
