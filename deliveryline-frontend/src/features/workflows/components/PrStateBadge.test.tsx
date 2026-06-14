/**
 * Story 3.27 (Task 7 / AC2, AC10) — PrStateBadge tests.
 *
 * All four PR states render with a text label AND an icon (the non-colour signifiers per
 * story 2.3 AC5) + a stable `data-pr-state` hook.
 */
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import type { PrState } from '../artifactView';
import { PrStateBadge } from './PrStateBadge';

afterEach(() => cleanup());

const CASES: ReadonlyArray<[PrState, string]> = [
  ['draft', 'Draft'],
  ['open', 'Open'],
  ['merged', 'Merged'],
  ['closed', 'Closed'],
];

describe('PrStateBadge', () => {
  it.each(CASES)(
    'renders the %s state with a text label + icon (non-colour signifier)',
    (state, label) => {
      const { container } = render(<PrStateBadge state={state} />);
      const badge = screen.getByTestId('pr-state-badge');
      expect(badge).toHaveAttribute('data-pr-state', state);
      expect(badge).toHaveTextContent(label);
      // The icon (lucide renders an <svg>) is the non-colour signifier alongside the label.
      expect(container.querySelector('svg')).not.toBeNull();
    },
  );
});
