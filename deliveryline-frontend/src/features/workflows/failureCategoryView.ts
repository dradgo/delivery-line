/**
 * Story 3.30 (Task 6, AC1/AC2/AC8) — the failure-category + next-safe-action
 * humanizers.
 *
 * Pure mapping helpers (NON-component `.ts`, react-refresh rule
 * [[frontend-react-refresh-no-fn-exports]]) shared by the Run Context Strip recovery
 * baseline (AC2), the failure-event surface (AC1), and the queue item's compact
 * failure-category chip (AC8). Centralizing the wire-enum → human-label mapping means
 * those three surfaces cannot drift on how a `FailureCategory` reads.
 *
 * The known categories mirror the live `FailureCategory` wire enum exactly
 * (`schema.d.ts` `WorkflowEvent.failureCategory`); an UNKNOWN value degrades
 * gracefully by title-casing the raw token (forward-compat, UX-DR6) rather than
 * rendering a bare snake-case string.
 */

/** The known `FailureCategory` wire enum → human label (live `schema.d.ts`). */
const FAILURE_CATEGORY_LABELS: Readonly<Record<string, string>> = {
  runner_timeout: 'Runner Timeout',
  runner_crash: 'Runner Crash',
  runner_contract_violation: 'Contract Violation',
  runner_non_zero_exit: 'Non-Zero Exit',
  runner_late_result: 'Late Result',
  runner_duplicate_result: 'Duplicate Result',
  runner_malformed_output: 'Malformed Output',
  runner_secret_leak: 'Secret Leak',
  orphan: 'Orphaned',
};

/** The known `nextSafeAction` wire enum → human label (live `RecoveryService`). */
const NEXT_SAFE_ACTION_LABELS: Readonly<Record<string, string>> = {
  retry: 'Retry',
  await_outcome: 'Await outcome',
  view_only: 'View only',
  await_manual_reconciliation: 'Await manual reconciliation',
};

/** Title-case a raw snake/kebab token (`runner_x_y` → "Runner X Y") for unknown values. */
function titleCaseToken(raw: string): string {
  return raw
    .split(/[_\s-]+/)
    .filter((part) => part.length > 0)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1).toLowerCase())
    .join(' ');
}

/**
 * Humanize a `FailureCategory` wire value (e.g. `runner_timeout` → "Runner Timeout").
 * An unknown/absent value degrades gracefully: a known token is mapped, any other
 * non-blank token is title-cased, and `undefined`/blank yields `undefined` so the
 * caller can render a "Not reported" placeholder rather than empty text.
 */
export function humanizeFailureCategory(category: string | undefined): string | undefined {
  if (category === undefined || category.trim() === '') {
    return undefined;
  }
  return FAILURE_CATEGORY_LABELS[category] ?? titleCaseToken(category);
}

/**
 * Humanize a `nextSafeAction` wire value (e.g. `view_only` → "View only"). Mirrors the
 * failure-category degradation: known token mapped, unknown title-cased, blank →
 * `undefined`. Render the result VERBATIM — never hardcode `await_operator_action`
 * (AC2's example label predates the live enum).
 */
export function humanizeNextSafeAction(action: string | undefined): string | undefined {
  if (action === undefined || action.trim() === '') {
    return undefined;
  }
  return NEXT_SAFE_ACTION_LABELS[action] ?? titleCaseToken(action);
}
