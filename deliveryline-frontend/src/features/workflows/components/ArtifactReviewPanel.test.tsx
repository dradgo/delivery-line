/**
 * Story 2.17 (AC1, AC3, AC4, AC6, AC7, AC9, AC11, AC12) — `ArtifactReviewPanel`.
 *
 * Two layers:
 *  • the PRESENTATIONAL panel is driven directly with fixtures (router/query-free):
 *    dispatch routing, every state + `data-artifact-panel-state`, XSS inertness,
 *    artifact-primacy (never auto-collapse).
 *  • the thin CONTAINER is driven by MOCKED hooks (T3 — the live hooks stay disabled):
 *    the `empty` mapping, allowed-actions-driven Compare enable/disable, and the
 *    field-only structured logging.
 *
 * `useReturnToRunContext` (consumed internally by `<ErrorState>`) is mocked at its own
 * module (T7); fixture-driven assertions (NOT `toMatchSnapshot`).
 */
import type { ReactNode } from 'react';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { expectNoA11yViolations } from '@/test/a11y/axe';
import { ProblemDetailsError, toProblemDetails } from '@/lib/api/problemDetails';
import { toArtifactView } from '@/lib/api/queryOptions';
import {
  implementationPlanArtifactView,
  prOutputArtifactView,
  specArtifactView,
  specArtifactViewXss,
} from '@/test/fixtures/artifact/artifactViewFixtures';
import type { ArtifactView } from '../artifactView';
import { isArtifactView } from '../artifactView';

vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => vi.fn(),
}));
vi.mock('../hooks/useArtifact', () => ({ useArtifact: vi.fn() }));
vi.mock('../hooks/useAllowedActions', () => ({ useAllowedActions: vi.fn() }));

import { useAllowedActions } from '../hooks/useAllowedActions';
import { useArtifact } from '../hooks/useArtifact';
import { ArtifactReviewPanel, ArtifactReviewPanelContainer } from './ArtifactReviewPanel';

const mockUseArtifact = vi.mocked(useArtifact);
const mockUseAllowedActions = vi.mocked(useAllowedActions);

/** A minimal fake query result shaped to the fields the container reads. */
function fakeArtifactQuery(overrides: Record<string, unknown> = {}) {
  return {
    data: undefined,
    isError: false,
    isFetching: false,
    error: null,
    refetch: vi.fn(),
    ...overrides,
  } as unknown as ReturnType<typeof useArtifact>;
}

function fakeAllowedActionsQuery(actions?: readonly string[]) {
  return {
    data: actions === undefined ? undefined : { actions },
  } as unknown as ReturnType<typeof useAllowedActions>;
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'info').mockImplementation(() => {});
  mockUseArtifact.mockReturnValue(fakeArtifactQuery());
  mockUseAllowedActions.mockReturnValue(fakeAllowedActionsQuery());
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function panel() {
  return screen.getByRole('region', { name: 'Artifact review' });
}

describe('ArtifactReviewPanel — presentational dispatch (AC1, AC3)', () => {
  function renderDefault(artifact: ArtifactView): ReactNode {
    render(<ArtifactReviewPanel state="default" artifact={artifact} />);
    return null;
  }

  it('routes spec → SpecArtifactRenderer', () => {
    renderDefault(specArtifactView);
    expect(screen.getByTestId('spec-artifact-renderer')).toBeInTheDocument();
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'default');
  });

  it('routes implementationPlan → variant renderer (story 3.26)', () => {
    renderDefault(implementationPlanArtifactView);
    expect(screen.getByTestId('implementation-plan-artifact-renderer')).toBeInTheDocument();
  });

  it('routes prOutput → PrOutputArtifactRenderer (story 3.27)', () => {
    renderDefault(prOutputArtifactView);
    expect(screen.getByTestId('pr-output-artifact-renderer')).toBeInTheDocument();
  });

  it('routes an unknown discriminant → safe fallback (never crashes)', () => {
    const unknown = { ...specArtifactView, artifactType: 'mysteryType' } as unknown as ArtifactView;
    render(<ArtifactReviewPanel state="default" artifact={unknown} />);
    expect(screen.getByTestId('unsupported-artifact')).toHaveTextContent('mysteryType');
  });
});

describe('ArtifactReviewPanel — states (AC4)', () => {
  it('loading → Skeleton rows, never a spinner', () => {
    const { container } = render(<ArtifactReviewPanel state="loading" />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'loading');
    expect(screen.getByTestId('artifact-panel-loading')).toBeInTheDocument();
    expect(container.querySelector('.animate-spin')).toBeNull();
    expect(container.querySelector('.animate-pulse')).not.toBeNull();
  });

  it('empty → "Specification not yet available" copy, not a spinner', () => {
    render(<ArtifactReviewPanel state="empty" />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'empty');
    expect(screen.getByTestId('empty-state')).toHaveTextContent('Specification not yet available');
  });

  it('error → ErrorState; Retry invokes the handler', () => {
    const onRetry = vi.fn();
    render(<ArtifactReviewPanel state="error" onRetry={onRetry} />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'error');
    expect(screen.getByTestId('error-state')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('stale → warning banner + View latest, with the artifact still rendered', () => {
    render(<ArtifactReviewPanel state="stale" artifact={{ ...specArtifactView, stale: true }} />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'stale');
    const banner = screen.getByTestId('artifact-stale-banner');
    expect(banner).toBeInTheDocument();
    expect(banner.querySelector('svg')).not.toBeNull();
    expect(screen.getByTestId('artifact-view-latest')).toBeDisabled();
    expect(screen.getByTestId('spec-artifact-renderer')).toBeInTheDocument();
  });

  it('superseded → stronger conflicting treatment + View latest', () => {
    render(
      <ArtifactReviewPanel
        state="superseded"
        artifact={{ ...specArtifactView, superseded: true }}
      />,
    );
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'superseded');
    const banner = screen.getByTestId('artifact-superseded-banner');
    expect(banner).toBeInTheDocument();
    expect(banner.querySelector('svg')).not.toBeNull();
    expect(screen.getByTestId('artifact-view-latest')).toBeDisabled();
  });

  it('incomplete → truncation banner', () => {
    render(
      <ArtifactReviewPanel
        state="incomplete"
        artifact={{ ...specArtifactView, truncated: true }}
      />,
    );
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'incomplete');
    expect(screen.getByTestId('artifact-incomplete-banner')).toBeInTheDocument();
  });
});

describe('ArtifactReviewPanel — untrusted runner output (AC7)', () => {
  it('routes an XSS body through the safe path — no active script/iframe, no javascript: link', () => {
    const { container } = render(
      <ArtifactReviewPanel state="default" artifact={specArtifactViewXss} />,
    );
    // No active execution vectors rendered into the DOM.
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('iframe')).toBeNull();
    // Any rendered anchor must not carry a javascript: scheme (queried by role, not strings).
    for (const link of screen.queryAllByRole('link')) {
      expect(link.getAttribute('href') ?? '').not.toMatch(/^javascript:/i);
    }
    // The probe never executed.
    expect((window as unknown as { __xss_executed?: boolean }).__xss_executed).toBeUndefined();
  });
});

describe('ArtifactReviewPanel — artifact primacy (AC6)', () => {
  it('never sets a min-width below the floor nor an auto-collapse class', () => {
    render(<ArtifactReviewPanel state="default" artifact={specArtifactView} />);
    const className = panel().className;
    expect(className).toBe('w-full');
    expect(className).not.toMatch(/collaps/i);
    expect(className).not.toMatch(/min-w-/);
  });
});

describe('ArtifactReviewPanelContainer — data seam (AC4, AC9, logging)', () => {
  it('no resolved artifact (idle, no data) → empty state', () => {
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_1" />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'empty');
  });

  it('AC7/D1 — the live post-adapter ArtifactView renders default (not error)', () => {
    // Pin the contract trap: the EXACT shape `fetchArtifact` produces (via toArtifactView)
    // MUST satisfy isArtifactView, or the panel silently renders `error`. A future DTO/adapter
    // drift that drops artifactId/title fails this loudly.
    const live = toArtifactView(
      {
        artifactId: 'art_live0001',
        artifactType: 'spec',
        version: 3,
        status: 'available',
        classification: 'shareable-redacted',
        createdAt: '2026-06-14T09:00:00Z',
        checksum: 'sha-256:0123456789ab',
        body: '# Specification\n\nThe redacted spec body.\n',
      },
      'art_live0001',
    );
    mockUseArtifact.mockReturnValue(fakeArtifactQuery({ data: live }));
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_live0001" />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'default');
    expect(screen.getByTestId('spec-artifact-renderer')).toBeInTheDocument();
  });

  // Story 3b-5 — the prOutput arm now consumes the live structured wire fields.
  const prOutputWireBase = {
    artifactId: 'art_pr0001',
    artifactType: 'prOutput' as const,
    version: 2,
    status: 'available',
    classification: 'shareable-redacted',
    createdAt: '2026-06-17T10:00:00Z',
    body: '',
  };

  it('3b-5 — maps live prOutput wire fields → a PrOutputArtifactView satisfying isArtifactView', () => {
    const view = toArtifactView(
      {
        ...prOutputWireBase,
        branch: 'feature/x',
        commitSha: 'abcdef1234567890abcdef1234567890abcdef12',
        diff: 'diff --git a/x b/x\n+hello\n',
        prReference: 'acme/app#42',
        prState: 'open',
      },
      'art_pr0001',
    );
    expect(isArtifactView(view)).toBe(true);
    if (view.artifactType !== 'prOutput') throw new Error('expected prOutput');
    expect(view.branch).toBe('feature/x');
    expect(view.diff).toContain('diff --git');
    expect(view.prLinkage).toEqual({
      prReference: 'acme/app#42',
      prState: 'open',
    });
  });

  it('3b-5 — null prReference/prState → prLinkage null (no linked PR)', () => {
    const view = toArtifactView(
      {
        ...prOutputWireBase,
        branch: 'feature/x',
        commitSha: 'abcdef1',
        diff: 'd',
        prReference: null,
        prState: null,
      },
      'art_pr0001',
    );
    if (view.artifactType !== 'prOutput') throw new Error('expected prOutput');
    expect(view.prLinkage).toBeNull();
  });

  it('3b-5 — null branch/commit/diff map to empty strings (graceful empty-state)', () => {
    const view = toArtifactView(
      {
        ...prOutputWireBase,
        branch: null,
        commitSha: null,
        diff: null,
        prReference: null,
        prState: null,
      },
      'art_pr0001',
    );
    expect(isArtifactView(view)).toBe(true);
    if (view.artifactType !== 'prOutput') throw new Error('expected prOutput');
    expect(view.branch).toBe('');
    expect(view.commitSha).toBe('');
    expect(view.diff).toBe('');
  });

  it('3b-5/AC9 — a live prOutput (post-mapper) renders the PR link + badge + parsed diff', () => {
    const live = toArtifactView(
      {
        ...prOutputWireBase,
        artifactId: 'art_prlive',
        branch: 'feature/x',
        commitSha: 'abcdef1234567890abcdef1234567890abcdef12',
        diff: 'diff --git a/x b/x\nindex 000..111 100644\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n',
        prReference: 'acme/app#42',
        prState: 'open',
      },
      'art_prlive',
    );
    render(<ArtifactReviewPanel state="default" artifact={live} />);
    expect(screen.getByTestId('pr-output-artifact-renderer')).toBeInTheDocument();
    // Linkage rendered (NOT the "No linked pull request" empty-state).
    expect(screen.getByTestId('pr-reference-link')).toBeInTheDocument();
    expect(screen.queryByTestId('pr-reference-unlinked')).toBeNull();
    // The unified diff rendered (NOT the "No diff content was produced." empty-state).
    expect(screen.getByTestId('pr-diff')).toBeInTheDocument();
    expect(screen.queryByTestId('pr-diff-empty')).toBeNull();
  });

  // Story 3b-6 — the implementationPlan arm now consumes the live typed `steps` wire field.
  const implPlanWireBase = {
    artifactId: 'art_plan0001',
    artifactType: 'implementationPlan' as const,
    version: 2,
    status: 'available',
    classification: 'shareable-redacted',
    createdAt: '2026-06-18T10:00:00Z',
    body: '',
  };

  it('3b-6 — maps live implementationPlan wire steps → an ImplementationPlanArtifactView satisfying isArtifactView', () => {
    const view = toArtifactView(
      {
        ...implPlanWireBase,
        steps: ['Add the read DTO field', 'Populate getArtifactDetail', 'Map the wire steps'],
      },
      'art_plan0001',
    );
    expect(isArtifactView(view)).toBe(true);
    if (view.artifactType !== 'implementationPlan') throw new Error('expected implementationPlan');
    expect(view.steps).toEqual([
      { summary: 'Add the read DTO field' },
      { summary: 'Populate getArtifactDetail' },
      { summary: 'Map the wire steps' },
    ]);
    // The view never carries raw JSON in body (backend blanks it for an implementationPlan).
    expect(view.body).toBe('');
  });

  it('3b-6 — null wire steps → steps absent (renders the empty-state), body never raw JSON', () => {
    const view = toArtifactView({ ...implPlanWireBase, steps: null }, 'art_plan0001');
    expect(isArtifactView(view)).toBe(true);
    if (view.artifactType !== 'implementationPlan') throw new Error('expected implementationPlan');
    expect(view.steps).toBeUndefined();
    expect(view.body).toBe('');
  });

  it('3b-6 — blank/whitespace wire steps are filtered; an all-blank array → steps absent', () => {
    const view = toArtifactView(
      { ...implPlanWireBase, steps: ['  ', '', 'Real step'] },
      'art_plan0001',
    );
    if (view.artifactType !== 'implementationPlan') throw new Error('expected implementationPlan');
    expect(view.steps).toEqual([{ summary: 'Real step' }]);

    const allBlank = toArtifactView({ ...implPlanWireBase, steps: ['  ', ''] }, 'art_plan0001');
    if (allBlank.artifactType !== 'implementationPlan')
      throw new Error('expected implementationPlan');
    expect(allBlank.steps).toBeUndefined();
  });

  it('3b-6/AC6 — a live implementationPlan (post-mapper) renders the ordered step accordion, not raw JSON or an empty-steps shell', () => {
    const live = toArtifactView(
      {
        ...implPlanWireBase,
        artifactId: 'art_planlive',
        steps: ['First step', 'Second step'],
      },
      'art_planlive',
    );
    render(<ArtifactReviewPanel state="default" artifact={live} />);
    expect(screen.getByTestId('implementation-plan-artifact-renderer')).toBeInTheDocument();
    // The numbered step accordion rendered (NOT the "no steps" empty-state).
    expect(screen.getByTestId('artifact-plan-step-1')).toBeInTheDocument();
    expect(screen.getByTestId('artifact-plan-step-2')).toBeInTheDocument();
    expect(screen.queryByTestId('artifact-plan-steps-empty')).toBeNull();
    // body="" → no empty "Generated content" prose shell.
    expect(screen.queryByText('Generated content')).toBeNull();
  });

  it('3b-6/AC6 — a live implementationPlan with no steps renders the empty-state, no raw-JSON body shell', () => {
    const live = toArtifactView(
      { ...implPlanWireBase, artifactId: 'art_planempty', steps: null },
      'art_planempty',
    );
    render(<ArtifactReviewPanel state="default" artifact={live} />);
    expect(screen.getByTestId('artifact-plan-steps-empty')).toBeInTheDocument();
    expect(screen.queryByText('Generated content')).toBeNull();
  });

  it('AC9 / 4.20 — Compare enables when allowed-actions reports enter_compare_mode AND a comparable revision exists', () => {
    mockUseArtifact.mockReturnValue(fakeArtifactQuery({ data: specArtifactView })); // spec v2
    mockUseAllowedActions.mockReturnValue(fakeAllowedActionsQuery(['enter_compare_mode']));
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_1" />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'default');
    expect(screen.getByTestId('artifact-compare-entry')).toBeEnabled();
  });

  it('4.20 AC9 — clicking the enabled Compare control invokes onEnterCompare', async () => {
    mockUseArtifact.mockReturnValue(fakeArtifactQuery({ data: specArtifactView })); // spec v2
    mockUseAllowedActions.mockReturnValue(fakeAllowedActionsQuery(['enter_compare_mode']));
    const onEnterCompare = vi.fn();
    render(
      <ArtifactReviewPanelContainer
        workflowRunId="run_1"
        artifactId="art_1"
        onEnterCompare={onEnterCompare}
      />,
    );
    await userEvent.click(screen.getByTestId('artifact-compare-entry'));
    expect(onEnterCompare).toHaveBeenCalledTimes(1);
  });

  it('preserves rendered artifact content during a background refetch', () => {
    mockUseArtifact.mockReturnValue(
      fakeArtifactQuery({ data: specArtifactView, isFetching: true }),
    );
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_1" />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'default');
    expect(screen.getByTestId('spec-artifact-renderer')).toBeInTheDocument();
    expect(screen.queryByTestId('artifact-panel-loading')).toBeNull();
  });

  it('invalid future artifact data maps to error instead of crashing renderers', () => {
    mockUseArtifact.mockReturnValue(
      fakeArtifactQuery({ data: { artifactType: 'spec', version: 2, status: 'available' } }),
    );
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_1" />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'error');
    expect(screen.getByTestId('error-state')).toBeInTheDocument();
  });

  it('AC9 - Compare stays disabled when the action is absent (the live safe default)', () => {
    mockUseArtifact.mockReturnValue(fakeArtifactQuery({ data: specArtifactView }));
    mockUseAllowedActions.mockReturnValue(fakeAllowedActionsQuery([])); // no compare action
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_1" />);
    expect(screen.getByTestId('artifact-compare-entry')).toBeDisabled();
  });

  it('AC9 — Compare disabled when no comparable revision exists even if the action is present', () => {
    mockUseArtifact.mockReturnValue(
      fakeArtifactQuery({ data: { ...specArtifactView, version: 1 } }),
    );
    mockUseAllowedActions.mockReturnValue(fakeAllowedActionsQuery(['enter_compare_mode']));
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_1" />);
    expect(screen.getByTestId('artifact-compare-entry')).toBeDisabled();
  });

  it('error → logs artifactPanel.loadError field-only (never the raw message); Retry logs + refetches', () => {
    const refetch = vi.fn();
    const error = new ProblemDetailsError(
      toProblemDetails(
        { code: 'INTERNAL_ERROR', status: 500, title: 'boom', retryable: true },
        500,
      ),
    );
    mockUseArtifact.mockReturnValue(fakeArtifactQuery({ isError: true, error, refetch }));
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_1" />);

    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'error');
    const warnMock = vi.mocked(console.warn);
    const loadErrorCall = warnMock.mock.calls.find(
      ([arg]) => (arg as { event?: string } | undefined)?.event === 'artifactPanel.loadError',
    );
    expect(loadErrorCall).toBeDefined();
    const payload = loadErrorCall![0] as Record<string, unknown>;
    expect(payload).not.toHaveProperty('message');
    expect(payload).toMatchObject({
      event: 'artifactPanel.loadError',
      code: 'INTERNAL_ERROR',
      transport: false,
    });
    expect(Object.keys(payload).sort()).toEqual(['code', 'event', 'transport']);

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(console.info).toHaveBeenCalledWith(
      expect.objectContaining({ event: 'artifactPanel.retry' }),
    );
    expect(refetch).toHaveBeenCalled();
  });

  it('transport error (non-ProblemDetails) → code "transport", transport true', () => {
    mockUseArtifact.mockReturnValue(
      fakeArtifactQuery({ isError: true, error: new Error('network down') }),
    );
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_1" />);
    const warnMock = vi.mocked(console.warn);
    const call = warnMock.mock.calls.find(
      ([arg]) => (arg as { event?: string } | undefined)?.event === 'artifactPanel.loadError',
    );
    expect(call![0]).toMatchObject({ code: 'transport', transport: true });
  });

  it('stale → logs artifactPanel.stale (field-only)', async () => {
    mockUseArtifact.mockReturnValue(
      fakeArtifactQuery({ data: { ...specArtifactView, stale: true } }),
    );
    render(<ArtifactReviewPanelContainer workflowRunId="run_1" artifactId="art_1" />);
    expect(panel()).toHaveAttribute('data-artifact-panel-state', 'stale');
    await waitFor(() =>
      expect(console.warn).toHaveBeenCalledWith(
        expect.objectContaining({ event: 'artifactPanel.stale' }),
      ),
    );
    const staleCall = vi
      .mocked(console.warn)
      .mock.calls.find(([a]) => (a as { event?: string }).event === 'artifactPanel.stale');
    expect(Object.keys(staleCall![0] as object)).toEqual(['event']);
  });
});

describe('ArtifactReviewPanel — fixture-stream dispatch (AC12)', () => {
  it('renders every artifact variant from fixtures (spec, implementation-plan, prOutput)', () => {
    const { rerender } = render(
      <ArtifactReviewPanel state="default" artifact={specArtifactView} />,
    );
    expect(screen.getByTestId('spec-artifact-renderer')).toBeInTheDocument();

    rerender(<ArtifactReviewPanel state="default" artifact={implementationPlanArtifactView} />);
    expect(screen.getByTestId('implementation-plan-artifact-renderer')).toBeInTheDocument();

    rerender(<ArtifactReviewPanel state="default" artifact={prOutputArtifactView} />);
    expect(screen.getByTestId('pr-output-artifact-renderer')).toBeInTheDocument();
  });
});

/**
 * Story 2.25 (Task 2 — AC1/AC2). The panel is a labeled `<section>` ("Artifact
 * review") that dispatches one of its documented states. Every state is scanned for
 * WCAG 2.1 AA violations; the interactive states (error Retry, default spec content)
 * are exercised for keyboard operability.
 */
describe('ArtifactReviewPanel a11y (story 2.25)', () => {
  // AC2 — non-content states (no resolved artifact needed).
  it.each(['loading', 'empty'] as const)(
    'AC2 — state "%s" has no axe violations',
    async (state) => {
      const { container } = render(<ArtifactReviewPanel state={state} />);
      await expectNoA11yViolations(container);
    },
  );

  it('AC2 — the error state has no axe violations', async () => {
    const { container } = render(<ArtifactReviewPanel state="error" onRetry={vi.fn()} />);
    await expectNoA11yViolations(container);
  });

  // AC2 — content + banner states render a resolved spec artifact.
  it.each([
    ['default', specArtifactView] as const,
    ['stale', { ...specArtifactView, stale: true }] as const,
    ['superseded', { ...specArtifactView, superseded: true }] as const,
    ['incomplete', { ...specArtifactView, truncated: true }] as const,
  ])('AC2 — content state "%s" has no axe violations', async (state, artifact) => {
    const { container } = render(
      <ArtifactReviewPanel state={state} artifact={artifact as ArtifactView} />,
    );
    await expectNoA11yViolations(container);
  });

  it('AC2 — the unsupported-artifact fallback has no axe violations', async () => {
    const unknown = { ...specArtifactView, artifactType: 'mysteryType' } as unknown as ArtifactView;
    const { container } = render(<ArtifactReviewPanel state="default" artifact={unknown} />);
    await expectNoA11yViolations(container);
  });

  it('AC1 — the error-state Retry is Tab-reachable and activates on Enter', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();
    render(<ArtifactReviewPanel state="error" onRetry={onRetry} />);
    const retry = screen.getByRole('button', { name: 'Try again' });
    await user.tab();
    expect(document.activeElement).toBe(retry);
    await user.keyboard('{Enter}');
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('AC1 — the disabled "View latest" affordance on a stale banner is NOT in the Tab order', async () => {
    const user = userEvent.setup();
    render(<ArtifactReviewPanel state="stale" artifact={{ ...specArtifactView, stale: true }} />);
    // First Tab lands on the first enabled control (a spec section anchor / region
    // entry-point), never the disabled "View latest" button (which is excluded).
    await user.tab();
    expect(document.activeElement).not.toBe(screen.getByTestId('artifact-view-latest'));
    expect(screen.getByTestId('artifact-view-latest')).toBeDisabled();
  });
});
