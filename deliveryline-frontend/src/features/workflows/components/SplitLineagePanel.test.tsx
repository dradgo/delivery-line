/**
 * Story 3f-7 (AC6) — `SplitLineagePanel`.
 */
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';

import { SplitLineagePanel } from './SplitLineagePanel';

afterEach(cleanup);

describe('SplitLineagePanel', () => {
  it('renders nothing for a non-Split run (decompositionStatus null)', () => {
    const { container } = render(<SplitLineagePanel decompositionStatus={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing when decompositionStatus is undefined', () => {
    const { container } = render(<SplitLineagePanel decompositionStatus={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('AC6 — surfaces the decomposition progress for a Split parent', () => {
    render(<SplitLineagePanel decompositionStatus="decomposed — 1 of 2 descendants complete" />);
    expect(screen.getByTestId('split-lineage-panel')).toBeInTheDocument();
    expect(screen.getByTestId('split-lineage-status')).toHaveTextContent(
      'decomposed — 1 of 2 descendants complete',
    );
  });

  it('AC6 — flips to nothing once the parent rolls up (decompositionStatus cleared)', () => {
    const { container, rerender } = render(
      <SplitLineagePanel decompositionStatus="decomposed — 1 of 2 descendants complete" />,
    );
    expect(screen.getByTestId('split-lineage-panel')).toBeInTheDocument();
    // On rollup the backend returns currentState=Completed and a null decompositionStatus.
    rerender(<SplitLineagePanel decompositionStatus={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('has no axe violations', async () => {
    const { container } = render(
      <SplitLineagePanel decompositionStatus="decomposed — 0 of 3 descendants complete" />,
    );
    await expectNoA11yViolations(container);
  });
});
