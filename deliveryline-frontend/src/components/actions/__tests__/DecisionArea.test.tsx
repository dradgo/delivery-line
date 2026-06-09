/**
 * Story 2.23 (AC11) — `<DecisionArea>` (primary never collapses).
 */
import { render, screen, cleanup, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

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
