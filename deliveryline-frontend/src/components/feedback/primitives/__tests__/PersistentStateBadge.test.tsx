/**
 * Story 2.21 (AC1, AC4, AC6) — `<PersistentStateBadge>`.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

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
