/**
 * Story 2.22 AC11.s — `withRunContext` HOC passes children/props through and
 * wraps the component in a `<RunContextBoundary>` (snapshot published).
 */
import type { ReactNode } from 'react';
import { render, screen, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { RunContextSnapshotProvider } from '../RunContextSnapshotProvider';
import { useRunContextSnapshot } from '../useRunContextSnapshot';
import { withRunContext } from '../withRunContext';

afterEach(cleanup);

function wrapper({ children }: { children: ReactNode }) {
  return <RunContextSnapshotProvider>{children}</RunContextSnapshotProvider>;
}

function CompareStub({ label }: { label: string }) {
  const snapshot = useRunContextSnapshot();
  return (
    <div>
      <span>{label}</span>
      <span data-testid="captured-run">{snapshot?.runId ?? 'none'}</span>
    </div>
  );
}

describe('withRunContext', () => {
  it('AC11.s — renders the wrapped component inside a boundary that captures the run id', () => {
    const Wrapped = withRunContext(CompareStub);
    render(<Wrapped runId="run_aaaa" label="compare-mode" />, { wrapper });
    expect(screen.getByText('compare-mode')).toBeInTheDocument();
    expect(screen.getByTestId('captured-run')).toHaveTextContent('run_aaaa');
  });
});
