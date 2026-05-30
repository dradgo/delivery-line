/**
 * Story 2.22 AC11.j (exhaustiveness) + AC11.k (5 variants render).
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import {
  EmptyState,
  assertNeverEmptyVariant,
  type EmptyVariant,
  type EmptyStateProps,
} from '../EmptyState';

afterEach(cleanup);

const VARIANTS: EmptyVariant[] = [
  'queue',
  'filtered',
  'artifactNotGenerated',
  'noOpenQuestions',
  'noMeaningfulDiff',
];

describe('EmptyState', () => {
  it.each(VARIANTS)('AC11.k — renders variant "%s" with a title, default message, and no live region', (variant) => {
    render(<EmptyState variant={variant} />);
    const el = screen.getByTestId('empty-state');
    expect(el).toHaveAttribute('data-variant', variant);
    expect(el.querySelector('h2')?.textContent?.length ?? 0).toBeGreaterThan(0);
    expect(el.textContent?.length ?? 0).toBeGreaterThan(0);
    // AC9.c — empty states are benign: no aria-live region.
    expect(el.querySelector('[aria-live]')).toBeNull();
  });

  it('AC11.k — renders an optional action when provided', () => {
    render(<EmptyState variant="queue" action={<button type="button">Go</button>} />);
    expect(screen.getByRole('button', { name: 'Go' })).toBeInTheDocument();
  });

  it('overrides the default message when message is supplied', () => {
    render(<EmptyState variant="queue" message="Custom copy here" />);
    expect(screen.getByText('Custom copy here')).toBeInTheDocument();
  });

  it('AC11.j — the exhaustiveness guard throws on an off-union value at runtime', () => {
    expect(() => assertNeverEmptyVariant('bogus' as never)).toThrow(/Unhandled EmptyState variant/);
  });

  it('AC11.j — an off-union variant is a COMPILE error (type-level contract)', () => {
    // The `@ts-expect-error` makes the build fail if `variant` ever stops being a
    // closed union (e.g. widened to `string`) — `tsc -b` enforces this at the gate.
    const props: EmptyStateProps = {
      // @ts-expect-error — 'bogus' is not an EmptyVariant.
      variant: 'bogus',
    };
    expect(props.variant).toBe('bogus');
  });
});
