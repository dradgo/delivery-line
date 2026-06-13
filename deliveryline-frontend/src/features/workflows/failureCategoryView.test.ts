/**
 * Story 3.30 (Task 6) — unit tests for the failure-category + next-safe-action
 * humanizers. Covers the known wire enums, graceful title-casing of unknown tokens,
 * and the blank/undefined → undefined contract.
 */
import { describe, expect, it } from 'vitest';

import { humanizeFailureCategory, humanizeNextSafeAction } from './failureCategoryView';

describe('humanizeFailureCategory', () => {
  it('maps every known FailureCategory wire value', () => {
    expect(humanizeFailureCategory('runner_timeout')).toBe('Runner Timeout');
    expect(humanizeFailureCategory('runner_crash')).toBe('Runner Crash');
    expect(humanizeFailureCategory('runner_contract_violation')).toBe('Contract Violation');
    expect(humanizeFailureCategory('runner_non_zero_exit')).toBe('Non-Zero Exit');
    expect(humanizeFailureCategory('runner_secret_leak')).toBe('Secret Leak');
    expect(humanizeFailureCategory('orphan')).toBe('Orphaned');
  });

  it('title-cases an unknown token (forward-compat, UX-DR6)', () => {
    expect(humanizeFailureCategory('runner_future_mode')).toBe('Runner Future Mode');
  });

  it('returns undefined for blank/undefined/null', () => {
    expect(humanizeFailureCategory(undefined)).toBeUndefined();
    expect(humanizeFailureCategory('')).toBeUndefined();
    expect(humanizeFailureCategory('   ')).toBeUndefined();
    // The wire sends `null` (not absent) for an uncategorized failure; treat it
    // like a missing value rather than calling `.trim()` on it.
    expect(humanizeFailureCategory(null as unknown as undefined)).toBeUndefined();
  });
});

describe('humanizeNextSafeAction', () => {
  it('maps the known nextSafeAction wire values (NOT the stale await_operator_action label)', () => {
    expect(humanizeNextSafeAction('retry')).toBe('Retry');
    expect(humanizeNextSafeAction('await_outcome')).toBe('Await outcome');
    expect(humanizeNextSafeAction('view_only')).toBe('View only');
    expect(humanizeNextSafeAction('await_manual_reconciliation')).toBe(
      'Await manual reconciliation',
    );
  });

  it('title-cases an unknown token and returns undefined for blank', () => {
    expect(humanizeNextSafeAction('do_something_new')).toBe('Do Something New');
    expect(humanizeNextSafeAction(undefined)).toBeUndefined();
    expect(humanizeNextSafeAction('')).toBeUndefined();
    expect(humanizeNextSafeAction(null as unknown as undefined)).toBeUndefined();
  });
});
