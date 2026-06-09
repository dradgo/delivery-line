/**
 * Story 2.21 (AC1, AC4, AC6) — `<PersistentStateBadge>`.
 * Story 2.25 (Task 2, AC2) — axe WCAG-2.1-AA scans across all StateName values.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { STATE_NAMES } from '@/lib/state-signifiers';

import { PersistentStateBadge } from '../PersistentStateBadge';
import { PERSISTENT_BADGE_FIXTURES } from '@/test/fixtures/feedback/feedbackFixtures';

afterEach(cleanup);

describe('PersistentStateBadge', () => {
  it('AC1/AC6 — renders the canonical icon + label for a state (non-color signifier)', () => {
    render(<PersistentStateBadge state="success" />);
    const el = screen.getByTestId('persistent-state-badge');
    expect(el).toHaveAttribute('data-state-name', 'success');
    // Icon present.
    expect(el.querySelector('svg')).not.toBeNull();
    // Default label from the STATE_SIGNIFIERS contract.
    expect(el.textContent).toContain('Success');
  });

  it('AC1 — accepts a label override while keeping the state icon', () => {
    render(<PersistentStateBadge state="error" label="Rejected" />);
    const el = screen.getByTestId('persistent-state-badge');
    expect(el.textContent).toContain('Rejected');
    expect(el.querySelector('svg')).not.toBeNull();
    expect(el).toHaveAttribute('data-state-name', 'error');
  });

  it('falls back to the canonical label for an empty override', () => {
    render(<PersistentStateBadge state="error" label="" />);
    expect(screen.getByTestId('persistent-state-badge')).toHaveTextContent('Error');
  });

  it('AC6 — exposes a polite status live region', () => {
    render(<PersistentStateBadge state="stale" />);
    const el = screen.getByTestId('persistent-state-badge');
    expect(el).toHaveAttribute('role', 'status');
    expect(el).toHaveAttribute('aria-live', 'polite');
  });

  it.each(['warning', 'blocker', 'error'] as const)(
    'AC6 — urgent state "%s" exposes an assertive alert',
    (state) => {
      render(<PersistentStateBadge state={state} />);
      const el = screen.getByTestId('persistent-state-badge');
      expect(el).toHaveAttribute('role', 'alert');
      expect(el).toHaveAttribute('aria-live', 'assertive');
    },
  );

  it.each(PERSISTENT_BADGE_FIXTURES)(
    'AC7 — fixture "$id" renders its configured persistent outcome',
    (fixture) => {
      render(
        <PersistentStateBadge
          state={fixture.state}
          label={fixture.badgeLabel}
          title={fixture.title}
        />,
      );
      const el = screen.getByTestId('persistent-state-badge');
      expect(el).toHaveAttribute('data-state-name', fixture.state);
      expect(el.querySelector('svg')).not.toBeNull();
      expect(el).toHaveTextContent(fixture.badgeLabel ?? fixture.state);
    },
  );

  it('AC4 — renders IN its mounting region (no portal escape) and never auto-dismisses', () => {
    render(
      <div data-testid="host">
        <PersistentStateBadge state="success" label="Approved" title="Decision recorded" />
      </div>,
    );
    const host = screen.getByTestId('host');
    const badge = host.querySelector('[data-persistent-state-badge]');
    expect(badge).not.toBeNull();
    expect(badge).toHaveAttribute('title', 'Decision recorded');
    // It is a static element — present immediately and not portaled to <body>.
    expect(document.body.querySelector(':scope > [data-persistent-state-badge]')).toBeNull();
  });
});

// Story 2.25 (Task 2, AC2) — axe WCAG-2.1-AA scan of every documented StateName.
// PersistentStateBadge is purely presentational (a status/alert live region with
// no interactive controls), so there is no keyboard test — the badge cannot
// receive Tab focus and exposes no activatable affordance.
describe('PersistentStateBadge a11y (story 2.25)', () => {
  it.each(STATE_NAMES)('AC2 — state "%s" (default label) has no axe violations', async (state) => {
    const { container } = render(<PersistentStateBadge state={state} />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — state "success" with label override has no axe violations', async () => {
    const { container } = render(<PersistentStateBadge state="success" label="Approved" />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — state "error" with empty label (fallback to canonical) has no axe violations', async () => {
    const { container } = render(<PersistentStateBadge state="error" label="" />);
    await expectNoA11yViolations(container);
  });

  it('AC2 — with tooltip title attribute has no axe violations', async () => {
    const { container } = render(
      <PersistentStateBadge state="stale" title="Superseded by a newer run" />,
    );
    await expectNoA11yViolations(container);
  });
});
