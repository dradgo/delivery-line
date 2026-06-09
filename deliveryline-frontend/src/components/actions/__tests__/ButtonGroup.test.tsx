/**
 * Story 2.23 (AC7) — `<ButtonGroup>`.
 */
import { render, screen, cleanup, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

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
