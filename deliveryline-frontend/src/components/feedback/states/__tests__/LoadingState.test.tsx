/**
 * Story 2.22 AC11.l — `<LoadingState>` renders each of 4 variants as a polite
 * status region with a default message.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';

import { LoadingState, type LoadingVariant } from '../LoadingState';

afterEach(cleanup);

const VARIANTS: LoadingVariant[] = [
  'fetchingData',
  'generatingArtifact',
  'rebuildingAfterRejection',
  'retryingRecovery',
];

describe('LoadingState', () => {
  it.each(VARIANTS)(
    'AC11.l — variant "%s" is role=status, aria-live=polite, with a default label',
    (variant) => {
      render(<LoadingState variant={variant} />);
      const el = screen.getByRole('status');
      expect(el).toHaveAttribute('aria-live', 'polite');
      expect(el).toHaveAttribute('data-variant', variant);
      expect(el.textContent?.trim().length ?? 0).toBeGreaterThan(0);
    },
  );

  it('overrides the default label when message is supplied', () => {
    render(<LoadingState variant="fetchingData" message="Hang tight…" />);
    expect(screen.getByText('Hang tight…')).toBeInTheDocument();
  });

  // Story 2.25 (Task 2, AC2) — axe scan of every documented state. LoadingState
  // is non-interactive (a status region only), so there is no keyboard test.
  it.each(VARIANTS)('AC2 — variant "%s" has no axe violations', async (variant) => {
    const { container } = render(<LoadingState variant={variant} />);
    await expectNoA11yViolations(container);
  });
});
