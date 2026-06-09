/**
 * Story 2.23 (AC7) — `<ButtonGroup>`.
 */
import { render, screen, cleanup, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { expectTabReachesAll } from '@/test/a11y/keyboard';

import { ButtonGroup } from '../ButtonGroup';
import { GovernedButton } from '../GovernedButton';

afterEach(cleanup);

describe('ButtonGroup', () => {
  it('stamps data-button-group and renders its children', () => {
    render(
      <ButtonGroup>
        <GovernedButton priority="primary">Approve</GovernedButton>
        <GovernedButton priority="secondary">Compare</GovernedButton>
      </ButtonGroup>,
    );
    const group = screen.getByTestId('button-group');
    expect(group).toHaveAttribute('data-button-group');
    expect(within(group).getAllByRole('button')).toHaveLength(2);
  });

  it('exposes role="group" only when given an accessible label', () => {
    const { rerender } = render(
      <ButtonGroup ariaLabel="Specification actions">
        <GovernedButton priority="primary">Approve</GovernedButton>
      </ButtonGroup>,
    );
    expect(screen.getByRole('group', { name: 'Specification actions' })).toBeInTheDocument();

    rerender(
      <ButtonGroup>
        <GovernedButton priority="primary">Approve</GovernedButton>
      </ButtonGroup>,
    );
    expect(screen.queryByRole('group')).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Story 2.25 (AC1, AC2) — WCAG 2.1 AA audit: axe scans + keyboard operability
// ---------------------------------------------------------------------------
describe('ButtonGroup a11y (story 2.25)', () => {
  // AC2 — axe scans.
  it('AC2 — unlabelled ButtonGroup (no role) has no axe violations', async () => {
    const { container } = render(
      <ButtonGroup>
        <GovernedButton priority="primary">Approve</GovernedButton>
        <GovernedButton priority="secondary">Compare</GovernedButton>
      </ButtonGroup>,
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — labelled ButtonGroup (role="group") has no axe violations', async () => {
    const { container } = render(
      <ButtonGroup ariaLabel="Specification actions">
        <GovernedButton priority="primary">Approve</GovernedButton>
        <GovernedButton priority="secondary">Compare</GovernedButton>
        <GovernedButton priority="tertiary">Inspect</GovernedButton>
      </ButtonGroup>,
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — single-button group has no axe violations', async () => {
    const { container } = render(
      <ButtonGroup ariaLabel="Run actions">
        <GovernedButton priority="primary">Start run</GovernedButton>
      </ButtonGroup>,
    );
    await expectNoA11yViolations(container);
  });

  // AC1 — keyboard operability.
  it('AC1 — all member buttons are Tab-reachable in DOM order', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <ButtonGroup>
        <GovernedButton priority="primary">Approve</GovernedButton>
        <GovernedButton priority="secondary">Compare</GovernedButton>
        <GovernedButton priority="tertiary">Inspect</GovernedButton>
      </ButtonGroup>,
    );
    await expectTabReachesAll(user, container);
  });

  it('AC1 — each button in the group activates its onClick', async () => {
    const user = userEvent.setup();
    const onApprove = vi.fn();
    const onCompare = vi.fn();
    render(
      <ButtonGroup>
        <GovernedButton priority="primary" onClick={onApprove}>
          Approve
        </GovernedButton>
        <GovernedButton priority="secondary" onClick={onCompare}>
          Compare
        </GovernedButton>
      </ButtonGroup>,
    );
    // Tab to Approve, activate with Enter.
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Approve' }));
    await user.keyboard('{Enter}');
    expect(onApprove).toHaveBeenCalledTimes(1);

    // Tab to Compare, activate with Space.
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Compare' }));
    await user.keyboard(' ');
    expect(onCompare).toHaveBeenCalledTimes(1);
  });

  it('AC1 — disabled members are skipped by Tab', async () => {
    const user = userEvent.setup();
    render(
      <ButtonGroup>
        <GovernedButton priority="primary">Approve</GovernedButton>
        <GovernedButton priority="secondary" disabled>
          Compare
        </GovernedButton>
        <GovernedButton priority="tertiary">Inspect</GovernedButton>
      </ButtonGroup>,
    );
    await user.tab();
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Approve' }));
    await user.tab();
    // Disabled Compare should be skipped — focus should land on Inspect.
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Inspect' }));
  });
});
