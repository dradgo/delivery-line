/**
 * Story 2.16 (Task 2 / AC2.b, AC6) — `WorkflowStateBadge`.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { WorkflowStateBadge, StateSignifierChip } from './WorkflowStateBadge';
import { backendStateToStateName } from './workflowStateMapping';
import type { StateName } from '@/lib/state-signifiers';

afterEach(cleanup);

describe('backendStateToStateName', () => {
  it('maps representative backend states to the expected StateName', () => {
    expect(backendStateToStateName('Completed')).toBe('success');
    expect(backendStateToStateName('Failed')).toBe('error');
    expect(backendStateToStateName('WaitingForReview')).toBe('warning');
    expect(backendStateToStateName('Investigating')).toBe('draft');
    expect(backendStateToStateName('TakenOver')).toBe('recovery');
    expect(backendStateToStateName('Inbox')).toBe('informational');
  });

  it('falls back to neutral informational for unknown / undefined states', () => {
    expect(backendStateToStateName('SomeFutureState')).toBe('informational');
    expect(backendStateToStateName(undefined)).toBe('informational');
  });
});

describe('WorkflowStateBadge', () => {
  it('AC6 — renders the state label AND an icon (never color alone)', () => {
    const { container } = render(<WorkflowStateBadge currentState="Completed" />);
    const badge = screen.getByTestId('workflow-state-badge');
    expect(badge).toHaveTextContent('Completed');
    expect(badge).toHaveAttribute('data-state-name', 'success');
    // The non-color signifier: a lucide icon renders as an <svg> alongside the label.
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('shows "Unknown" on the neutral palette for an absent state', () => {
    render(<WorkflowStateBadge />);
    const badge = screen.getByTestId('workflow-state-badge');
    expect(badge).toHaveTextContent('Unknown');
    expect(badge).toHaveAttribute('data-state-name', 'informational');
  });
});

// ---------------------------------------------------------------------------
// Story 2.25 a11y (axe-core scans for every documented badge state)
// Non-interactive widgets — axe-only (no keyboard tests required).
// ---------------------------------------------------------------------------
describe('WorkflowStateBadge a11y (story 2.25)', () => {
  const KNOWN_BACKEND_STATES: Array<[string, StateName]> = [
    ['Inbox', 'informational'],
    ['Planned', 'informational'],
    ['Investigating', 'draft'],
    ['Executing', 'informational'],
    ['WaitingForSpecApproval', 'warning'],
    ['WaitingForReview', 'warning'],
    ['Completed', 'success'],
    ['Failed', 'error'],
    ['Paused', 'warning'],
    ['TakenOver', 'recovery'],
    ['Reconciled', 'recovery'],
  ];

  it.each(KNOWN_BACKEND_STATES)(
    'AC2 — WorkflowStateBadge("%s") has no axe violations',
    async (state) => {
      const { container } = render(<WorkflowStateBadge currentState={state} />);
      await expectNoA11yViolations(container);
    },
  );

  it('AC2 — WorkflowStateBadge(undefined) "Unknown" state has no axe violations', async () => {
    const { container } = render(<WorkflowStateBadge />);
    await expectNoA11yViolations(container);
  });

  // StateSignifierChip covers the extended stateName palette (stale, blocker, etc.)
  const CHIP_STATE_NAMES: StateName[] = [
    'informational',
    'success',
    'warning',
    'error',
    'draft',
    'stale',
    'blocker',
    'recovery',
    'loading',
    'selected',
    'empty',
    'permission-restricted',
  ];

  it.each(CHIP_STATE_NAMES)(
    'AC2 — StateSignifierChip(stateName="%s") has no axe violations',
    async (stateName) => {
      const { container } = render(<StateSignifierChip stateName={stateName} label={stateName} />);
      await expectNoA11yViolations(container);
    },
  );

  it('AC2 — StateSignifierChip with optional title has no axe violations', async () => {
    const { container } = render(
      <StateSignifierChip stateName="stale" label="Stale" title="Last activity 1 hour ago" />,
    );
    await expectNoA11yViolations(container);
  });
});
