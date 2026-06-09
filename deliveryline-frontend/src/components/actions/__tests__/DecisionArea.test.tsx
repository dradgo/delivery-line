/**
 * Story 2.23 (AC11) — `<DecisionArea>` (primary never collapses).
 */
import { render, screen, cleanup, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { expectTabReachesAll } from '@/test/a11y/keyboard';

import { DecisionArea } from '../DecisionArea';
import { ButtonGroup } from '../ButtonGroup';
import { GovernedButton } from '../GovernedButton';

afterEach(cleanup);

describe('DecisionArea', () => {
  it('AC11 — primary stays in the non-overflow region; secondary/tertiary are overflow-eligible', () => {
    render(
      <DecisionArea
        primary={<GovernedButton priority="primary">Approve</GovernedButton>}
        secondary={
          <ButtonGroup>
            <GovernedButton priority="secondary">Compare</GovernedButton>
            <GovernedButton priority="tertiary">Inspect</GovernedButton>
          </ButtonGroup>
        }
      />,
    );
    const area = screen.getByTestId('decision-area');
    const primaryRegion = area.querySelector('[data-decision-primary]') as HTMLElement;
    const overflowRegion = area.querySelector('[data-decision-overflow]') as HTMLElement;
    expect(primaryRegion).not.toBeNull();
    expect(overflowRegion).not.toBeNull();

    // Primary lives in the always-visible region…
    expect(within(primaryRegion).getByRole('button', { name: 'Approve' })).toBeInTheDocument();
    // …and is NOT inside the overflow region.
    expect(within(overflowRegion).queryByRole('button', { name: 'Approve' })).toBeNull();

    // Secondary + tertiary are eligible for the overflow slot.
    expect(within(overflowRegion).getByRole('button', { name: 'Compare' })).toBeInTheDocument();
    expect(within(overflowRegion).getByRole('button', { name: 'Inspect' })).toBeInTheDocument();
  });

  it('omits the overflow region when no secondary actions are supplied', () => {
    render(<DecisionArea primary={<GovernedButton priority="primary">Approve</GovernedButton>} />);
    const area = screen.getByTestId('decision-area');
    expect(area.querySelector('[data-decision-overflow]')).toBeNull();
    expect(area.querySelector('[data-decision-primary]')).not.toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Story 2.25 (AC1, AC2) — WCAG 2.1 AA audit: axe scans + keyboard operability
// ---------------------------------------------------------------------------
describe('DecisionArea a11y (story 2.25)', () => {
  // AC2 — axe scans for each documented layout state.
  it('AC2 — primary-only (no secondary) has no axe violations', async () => {
    const { container } = render(
      <DecisionArea primary={<GovernedButton priority="primary">Approve</GovernedButton>} />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — primary + secondary actions has no axe violations', async () => {
    const { container } = render(
      <DecisionArea
        primary={<GovernedButton priority="primary">Approve</GovernedButton>}
        secondary={
          <ButtonGroup>
            <GovernedButton priority="secondary">Compare</GovernedButton>
            <GovernedButton priority="tertiary">Inspect</GovernedButton>
          </ButtonGroup>
        }
      />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — labelled DecisionArea (role="group") has no axe violations', async () => {
    const { container } = render(
      <DecisionArea
        ariaLabel="Specification actions"
        primary={<GovernedButton priority="primary">Approve</GovernedButton>}
        secondary={
          <ButtonGroup>
            <GovernedButton priority="secondary">Compare</GovernedButton>
          </ButtonGroup>
        }
      />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — primary-only labelled DecisionArea has no axe violations', async () => {
    const { container } = render(
      <DecisionArea
        ariaLabel="Run actions"
        primary={<GovernedButton priority="primary">Start run</GovernedButton>}
      />,
    );
    await expectNoA11yViolations(container);
  });

  // AC1 — keyboard operability.
  it('AC1 — primary-only: the primary button is Tab-reachable', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <DecisionArea primary={<GovernedButton priority="primary">Approve</GovernedButton>} />,
    );
    await expectTabReachesAll(user, container);
  });

  it('AC1 — primary + secondary: all buttons reachable in DOM order (primary first)', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <DecisionArea
        primary={<GovernedButton priority="primary">Approve</GovernedButton>}
        secondary={
          <ButtonGroup>
            <GovernedButton priority="secondary">Compare</GovernedButton>
            <GovernedButton priority="tertiary">Inspect</GovernedButton>
          </ButtonGroup>
        }
      />,
    );
    await expectTabReachesAll(user, container);
  });

  it('AC1 — primary button activates on Enter', async () => {
    const user = userEvent.setup();
    const onApprove = vi.fn();
    render(
      <DecisionArea
        primary={
          <GovernedButton priority="primary" onClick={onApprove}>
            Approve
          </GovernedButton>
        }
      />,
    );
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Approve' }));
    await user.keyboard('{Enter}');
    expect(onApprove).toHaveBeenCalledTimes(1);
  });

  it('AC1 — secondary buttons activate on Space', async () => {
    const user = userEvent.setup();
    const onCompare = vi.fn();
    const onInspect = vi.fn();
    render(
      <DecisionArea
        primary={<GovernedButton priority="primary">Approve</GovernedButton>}
        secondary={
          <ButtonGroup>
            <GovernedButton priority="secondary" onClick={onCompare}>
              Compare
            </GovernedButton>
            <GovernedButton priority="tertiary" onClick={onInspect}>
              Inspect
            </GovernedButton>
          </ButtonGroup>
        }
      />,
    );
    // Tab past primary (Approve) → Compare → Space → activate.
    await user.tab();
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Compare' }));
    await user.keyboard(' ');
    expect(onCompare).toHaveBeenCalledTimes(1);

    // Tab to Inspect → Space → activate.
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Inspect' }));
    await user.keyboard(' ');
    expect(onInspect).toHaveBeenCalledTimes(1);
  });
});
