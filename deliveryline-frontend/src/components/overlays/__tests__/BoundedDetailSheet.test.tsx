/**
 * Story 2.23 (AC1, AC6) — `<BoundedDetailSheet>`.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { BoundedDetailSheet } from '../BoundedDetailSheet';

afterEach(cleanup);

describe('BoundedDetailSheet', () => {
  it('AC1 — renders the title + bounded content (right slide-over by default)', () => {
    render(
      <BoundedDetailSheet open onOpenChange={() => undefined} title="Run context">
        <p>Bounded supporting detail.</p>
      </BoundedDetailSheet>,
    );
    const sheet = screen.getByTestId('bounded-detail-sheet');
    expect(sheet).toHaveAttribute('data-bounded-detail-sheet');
    expect(sheet).toHaveAttribute('data-side', 'right');
    expect(sheet).toHaveAttribute('data-full-height', 'false');
    expect(screen.getByText('Run context')).toBeInTheDocument();
    expect(screen.getByText('Bounded supporting detail.')).toBeInTheDocument();
  });

  it('AC6 — side="bottom" / fullHeightOnMobile renders the full-height bottom-sheet variant', () => {
    render(
      <BoundedDetailSheet
        open
        onOpenChange={() => undefined}
        title="Run context"
        side="bottom"
        fullHeightOnMobile
      >
        <p>Full-height body.</p>
      </BoundedDetailSheet>,
    );
    const sheet = screen.getByTestId('bounded-detail-sheet');
    expect(sheet).toHaveAttribute('data-side', 'bottom');
    expect(sheet).toHaveAttribute('data-full-height', 'true');
  });

  it('AC6 — fullHeightOnMobile alone defaults the side to bottom', () => {
    render(
      <BoundedDetailSheet
        open
        onOpenChange={() => undefined}
        title="Run context"
        fullHeightOnMobile
      >
        <p>Body.</p>
      </BoundedDetailSheet>,
    );
    const sheet = screen.getByTestId('bounded-detail-sheet');
    expect(sheet).toHaveAttribute('data-side', 'bottom');
    expect(sheet).toHaveAttribute('data-full-height', 'true');
  });
});
