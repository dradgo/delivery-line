/**
 * Story 2.16 (Task 2 / AC2.b, AC6) — `WorkflowStateBadge`.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { WorkflowStateBadge } from './WorkflowStateBadge';
import { backendStateToStateName } from './workflowStateMapping';

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
