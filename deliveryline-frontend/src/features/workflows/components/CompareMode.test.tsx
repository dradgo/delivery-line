/**
 * Story 4.20 (AC11) — presentational coverage for the `CompareMode` composite. Fixture-driven +
 * router/query-free (no `workflowRunId`/`artifactIdB` → the prOutput lazy diff never fires a query).
 */
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';

// ErrorState consumes `useReturnToRunContext` (breadcrumb-backed) for its NavigateBack action;
// stub it so the presentational compare states render router/query-free.
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));

import { CompareMode } from './CompareMode';
import type { CompareView } from '../compareView';

afterEach(cleanup);

function specView(overrides: Partial<CompareView> = {}): CompareView {
  return {
    artifactType: 'spec',
    revisionA: {
      version: 1,
      producedByActor: 'runner',
      createdAt: '2026-07-01T00:00:00Z',
      checksum: null,
    },
    revisionB: {
      version: 2,
      producedByActor: null,
      createdAt: '2026-07-02T00:00:00Z',
      checksum: null,
    },
    summary: { changedRegionCount: 2, addedCount: 1, removedCount: 0, modifiedCount: 1 },
    noMeaningfulDiff: false,
    blocks: [
      {
        kind: 'markdown',
        changeKind: 'modified',
        sectionPath: 'Overview',
        priorText: 'old text',
        currentText: 'new text',
      },
      {
        kind: 'markdown',
        changeKind: 'added',
        sectionPath: 'Scope',
        priorText: null,
        currentText: 'added section',
      },
    ],
    linkedDiffReferences: null,
    ...overrides,
  };
}

function prOutputView(): CompareView {
  return {
    artifactType: 'prOutput',
    revisionA: { version: 1, producedByActor: 'runner', createdAt: null, checksum: null },
    revisionB: { version: 2, producedByActor: 'runner', createdAt: null, checksum: null },
    summary: { changedRegionCount: 1, addedCount: 0, removedCount: 0, modifiedCount: 1 },
    noMeaningfulDiff: false,
    blocks: [
      {
        kind: 'file',
        changeKind: 'modified',
        filePath: 'src/app.ts',
        addedLines: 3,
        removedLines: 1,
      },
    ],
    linkedDiffReferences: ['art_a', 'art_b'],
  };
}

describe('CompareMode — states', () => {
  it('AC4 — spec renders the side-by-side layout with a single (synced) scrollport + summary first', () => {
    render(<CompareMode state="default" view={specView()} onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'default');
    // Summary header is always first (AC7).
    expect(screen.getByTestId('compare-summary')).toBeInTheDocument();
    expect(screen.getByTestId('compare-count-regions')).toHaveTextContent('2 changed regions');
    const surface = screen.getByTestId('compare-surface');
    expect(surface).toHaveAttribute('data-compare-layout', 'side-by-side');
    expect(screen.getByTestId('compare-synced-scroll')).toBeInTheDocument();
    // A/B are prior/current (never swapped).
    expect(screen.getByTestId('compare-revision-a')).toHaveTextContent('v1');
    expect(screen.getByTestId('compare-revision-b')).toHaveTextContent('v2');
    // producedByActor is null on B → "unknown".
    expect(screen.getByTestId('compare-revision-b')).toHaveTextContent('unknown');
  });

  it('AC4 — prOutput renders the stacked file accordions (not the synced-scroll grid)', () => {
    render(<CompareMode state="default" view={prOutputView()} onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-surface')).toHaveAttribute('data-compare-layout', 'stacked');
    expect(screen.queryByTestId('compare-synced-scroll')).toBeNull();
    expect(screen.getByTestId('compare-region-0')).toHaveAttribute('data-block-kind', 'file');
  });

  it('AC3 — no-meaningful-diff renders the identical empty state', () => {
    render(
      <CompareMode
        state="no-meaningful-diff"
        view={specView({ noMeaningfulDiff: true })}
        onExit={vi.fn()}
      />,
    );
    expect(screen.getByTestId('compare-mode')).toHaveAttribute(
      'data-compare-state',
      'no-meaningful-diff',
    );
    expect(screen.getByTestId('compare-no-meaningful-diff')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: /identical/i })).toBeInTheDocument();
  });

  it('AC3 — no-baseline renders the unavailable-baseline error with a back action', () => {
    render(<CompareMode state="no-baseline" onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'no-baseline');
    expect(screen.getByTestId('error-state')).toHaveAttribute(
      'data-variant',
      'unavailableDiffBaseline',
    );
  });

  it('AC3 — partial (503) renders the error state with a Retry next action', async () => {
    const onRetry = vi.fn();
    render(<CompareMode state="partial" onExit={vi.fn()} onRetry={onRetry} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'partial');
    await userEvent.click(screen.getByRole('button', { name: /try again/i }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('AC3 — unavailable renders the generic comparison-unavailable error', () => {
    render(<CompareMode state="unavailable" onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveAttribute('data-compare-state', 'unavailable');
    expect(screen.getByTestId('error-state')).toBeInTheDocument();
  });

  it('AC3 — loading renders a skeleton matching the layout', () => {
    render(<CompareMode state="loading" onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-loading')).toBeInTheDocument();
  });
});

describe('CompareMode — controls, jump & a11y', () => {
  it('AC2 — the changed-region gutter markers are non-color (shape symbol + text label)', () => {
    render(<CompareMode state="default" view={specView()} onExit={vi.fn()} />);
    const markers = screen.getAllByLabelText(/Modified:|Added:/);
    expect(markers.length).toBeGreaterThanOrEqual(2);
    // The marker carries a text label (never color alone) + the change kind.
    expect(screen.getByLabelText('Modified: Overview')).toBeInTheDocument();
    expect(screen.getByLabelText('Added: Scope')).toBeInTheDocument();
  });

  it('AC2 — the "Show only changes" filter defaults ON and toggles to "Show all"', async () => {
    render(<CompareMode state="default" view={specView()} onExit={vi.fn()} />);
    const filter = screen.getByTestId('compare-filter-toggle');
    expect(filter).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByTestId('compare-all-note')).toBeNull();
    await userEvent.click(filter);
    expect(filter).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('compare-all-note')).toBeInTheDocument();
  });

  it('AC4 — summary-first collapses the detail; a region toggle re-expands it', async () => {
    render(<CompareMode state="default" view={specView()} onExit={vi.fn()} />);
    // spec defaults to expanded (OQ-4) — detail visible.
    expect(screen.getByTestId('compare-region-detail-0')).toBeInTheDocument();
    await userEvent.click(screen.getByTestId('compare-summary-first-toggle'));
    expect(screen.queryByTestId('compare-region-detail-0')).toBeNull();
    await userEvent.click(screen.getByTestId('compare-region-toggle-0'));
    expect(screen.getByTestId('compare-region-detail-0')).toBeInTheDocument();
  });

  it('AC6 — J/K jump announces the landed region through the single live region', async () => {
    render(<CompareMode state="default" view={specView()} onExit={vi.fn()} />);
    const section = screen.getByTestId('compare-mode');
    fireEvent.keyDown(section, { key: 'j' });
    await waitFor(() =>
      expect(screen.getByTestId('compare-announcer')).toHaveTextContent('Changed region 1 of 2'),
    );
    fireEvent.keyDown(section, { key: 'j' });
    await waitFor(() =>
      expect(screen.getByTestId('compare-announcer')).toHaveTextContent('Changed region 2 of 2'),
    );
  });

  it('AC6 — Esc exits compare mode (calls onExit)', () => {
    const onExit = vi.fn();
    render(<CompareMode state="default" view={specView()} onExit={onExit} />);
    fireEvent.keyDown(screen.getByTestId('compare-mode'), { key: 'Escape' });
    expect(onExit).toHaveBeenCalledTimes(1);
  });

  it('AC6 — focuses the section on entry so the native keydown accelerators receive events', () => {
    // Regression (code review): the J/K/Esc listener is bound natively on the section, and the
    // overlay unmounts the Compare button that held focus — without a focus-on-mount the section
    // never receives keydowns (focus falls to document.body). Assert focus lands on the section so
    // a real (non-synthetic-dispatch) keypress reaches the accelerator.
    render(<CompareMode state="default" view={specView()} onExit={vi.fn()} />);
    expect(screen.getByTestId('compare-mode')).toHaveFocus();
  });

  it('AC6 — the exit control calls onExit from any state', async () => {
    const onExit = vi.fn();
    render(<CompareMode state="no-baseline" onExit={onExit} />);
    await userEvent.click(screen.getByTestId('compare-exit'));
    expect(onExit).toHaveBeenCalledTimes(1);
  });

  it('AC11 — axe-core: default / no-meaningful-diff / error renders have zero violations', async () => {
    const { container: c1 } = render(
      <CompareMode state="default" view={specView()} onExit={vi.fn()} />,
    );
    await expectNoA11yViolations(c1);
    cleanup();
    const { container: c2 } = render(
      <CompareMode
        state="no-meaningful-diff"
        view={specView({ noMeaningfulDiff: true })}
        onExit={vi.fn()}
      />,
    );
    await expectNoA11yViolations(c2);
    cleanup();
    const { container: c3 } = render(
      <CompareMode state="partial" onExit={vi.fn()} onRetry={vi.fn()} />,
    );
    await expectNoA11yViolations(c3);
  });
});

describe('CompareMode — sanitization (AC5)', () => {
  it('routes scriptable delta text through the sanitizers (no executable HTML renders)', () => {
    const scriptable = '<script>alert(1)</script><img src=x onerror=alert(2)>';
    const { container } = render(
      <CompareMode
        state="default"
        view={specView({
          blocks: [
            {
              kind: 'markdown',
              changeKind: 'modified',
              sectionPath: scriptable,
              priorText: scriptable,
              currentText: `javascript:alert(3) ${scriptable}`,
            },
            {
              kind: 'planStep',
              changeKind: 'added',
              stepId: 's1',
              priorStepText: null,
              currentStepText: scriptable,
              priorStepOrder: null,
              currentStepOrder: 1,
            },
          ],
        })}
        onExit={vi.fn()}
      />,
    );
    // No script element is ever emitted; no img carries a live onerror handler.
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img[onerror]')).toBeNull();
  });

  it('renders a prOutput file path through the redaction path without a live handler', () => {
    const { container } = render(
      <CompareMode
        state="default"
        view={{
          ...prOutputView(),
          blocks: [
            {
              kind: 'file',
              changeKind: 'modified',
              filePath: 'src/<img src=x onerror=alert(1)>.ts',
              addedLines: 1,
              removedLines: 0,
            },
          ],
        }}
        onExit={vi.fn()}
      />,
    );
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img[onerror]')).toBeNull();
  });
});
