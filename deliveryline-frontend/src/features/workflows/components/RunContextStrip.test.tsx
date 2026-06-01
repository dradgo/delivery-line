/**
 * Story 2.16 (Task 5 / AC3, AC4, AC5, AC6, AC7, AC10, AC11) — `RunContextStrip`.
 *
 * `useReturnToRunContext` (consumed internally by `<ErrorState>`) is mocked at its
 * own module — NOT the router — so this file stays isolated from
 * `@tanstack/react-router` internals (T6; mirrors `QueueShell.test.tsx` / memory
 * `vitest-cross-file-router-mock`).
 *
 * `Date.now` is pinned (NOT fake timers, which would stall react-query/MSW) so the
 * client-derived stale window (AC9) is deterministic against the ISO fixtures.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, cleanup, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { server } from '@/test/server';
import type { WorkflowDetail } from '@/lib/api/queryOptions';
import { specRejectAndResubmitDetail } from '@/test/fixtures/runContext/specRejectAndResubmit';
import { RUN_STALE_THRESHOLD_MS } from '../runContextView';

const { backSpy } = vi.hoisted(() => ({ backSpy: vi.fn() }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => backSpy,
}));

import { RunContextStrip, RUN_CONTEXT_STRIP_MAX_HEIGHT } from './RunContextStrip';

const DETAIL_URL = 'http://localhost/api/v1/workflows/:id';
const RUN_ID = 'run_fix_rej_001';

/** Pinned "now" — 5 minutes after the fixtures' activity timestamp (not stale). */
const NOW_MS = Date.parse('2026-05-30T12:05:00Z');

function freshClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderStrip(node: ReactNode) {
  return render(<QueryClientProvider client={freshClient()}>{node}</QueryClientProvider>);
}

function detailResponse(detail: WorkflowDetail) {
  return http.get(DETAIL_URL, () => HttpResponse.json(detail));
}

function problemResponse() {
  return new HttpResponse(
    JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', retryable: true, title: 'x' }),
    { status: 500, headers: { 'content-type': 'application/problem+json' } },
  );
}

beforeEach(() => {
  backSpy.mockClear();
  vi.spyOn(Date, 'now').mockReturnValue(NOW_MS);
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

function region() {
  return screen.getByRole('region', { name: 'Run context' });
}

describe('RunContextStrip', () => {
  it('AC3/AC4 — loading renders skeleton rows (never a spinner), capped + labeled region', async () => {
    server.use(
      http.get(DETAIL_URL, async () => {
        await delay(20);
        return HttpResponse.json(specRejectAndResubmitDetail);
      }),
    );

    const { container } = renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    // Synchronous first paint is loading (no cached data).
    expect(region()).toHaveAttribute('data-run-context-state', 'loading');
    expect(screen.getByTestId('run-context-loading')).toBeInTheDocument();
    expect(container.querySelector('.animate-spin')).toBeNull();
    expect(container.querySelector('.animate-pulse')).not.toBeNull();
    // AC4 — the documented max-height cap is present in the loading state.
    expect(region()).toHaveStyle({ maxHeight: RUN_CONTEXT_STRIP_MAX_HEIGHT });

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'default'));
  });

  it('AC11 — renders the spec-rejection-and-resubmit terminal state correctly', async () => {
    server.use(detailResponse(specRejectAndResubmitDetail));

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'default'));
    // Run id, Completed badge, actor, latest revision spec v2 (advanced past v1),
    // and the Linear trigger DEL-9002 (AC11).
    expect(region()).toHaveTextContent(RUN_ID);
    const badge = screen.getByTestId('workflow-state-badge');
    expect(badge).toHaveTextContent('Completed');
    expect(badge).toHaveAttribute('data-state-name', 'success');
    expect(region()).toHaveTextContent('Alex (human)');
    expect(screen.getByTestId('run-context-revision')).toHaveTextContent('spec v2');
    expect(screen.getByTestId('run-context-trigger')).toHaveTextContent('DEL-9002');
  });

  it('AC4/AC5/AC6 — capped region, no lineage control, badge pairs color with icon + label', async () => {
    server.use(detailResponse(specRejectAndResubmitDetail));

    const { container } = renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'default'));
    // AC4 — cap applied; single inline row, no multi-row <dl> expansion shape.
    expect(region()).toHaveStyle({ maxHeight: RUN_CONTEXT_STRIP_MAX_HEIGHT });
    expect(container.querySelector('dl')).toBeNull();
    // AC5 — no lineage/provenance control renders (no buttons / links in default).
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
    // AC6 — the badge color is paired with an icon (svg) + a text label.
    const badge = screen.getByTestId('workflow-state-badge');
    expect(badge.querySelector('svg')).not.toBeNull();
    expect(badge).toHaveTextContent('Completed');
  });

  it('AC3 — partial-context shows per-field "Not reported", not an EmptyState', async () => {
    server.use(
      detailResponse({
        workflowRunId: RUN_ID,
        currentState: 'Executing',
        currentActorIdentity: 'Codex Runner',
        currentActorType: 'agent',
        escalationMarker: false,
        lastEventAt: '2026-05-30T12:00:00Z',
        lastActivityTimestamp: '2026-05-30T12:00:00Z',
        // No latestArtifacts, no linkedTicket → partial-context.
      }),
    );

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() =>
      expect(region()).toHaveAttribute('data-run-context-state', 'partial-context'),
    );
    expect(screen.getByTestId('run-context-revision')).toHaveTextContent('Not reported');
    expect(screen.getByTestId('run-context-trigger')).toHaveTextContent('Not reported');
    // branchOrCommitReference is always a placeholder in Epic 2.
    expect(screen.getByTestId('run-context-branch')).toHaveTextContent('Not reported');
    // T4 — never the queue EmptyState.
    expect(screen.queryByTestId('empty-state')).toBeNull();
    // The populated fields still render.
    expect(region()).toHaveTextContent('Codex Runner (agent)');
  });

  it('AC3 — missing latest artifact version keeps the revision slot partial', async () => {
    server.use(
      detailResponse({
        ...specRejectAndResubmitDetail,
        latestArtifacts: [{ artifactType: 'implementationPlan', status: 'draft' }],
      }),
    );

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() =>
      expect(region()).toHaveAttribute('data-run-context-state', 'partial-context'),
    );
    expect(screen.getByTestId('run-context-revision')).toHaveTextContent('Not reported');
    expect(region()).toHaveStyle({ maxHeight: RUN_CONTEXT_STRIP_MAX_HEIGHT });
  });

  it('AC2 — renders display labels for non-spec artifact types', async () => {
    server.use(
      detailResponse({
        ...specRejectAndResubmitDetail,
        latestArtifacts: [{ artifactType: 'prOutput', status: 'ready', version: 2 }],
      }),
    );

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'default'));
    expect(screen.getByTestId('run-context-revision')).toHaveTextContent('pr-output v2');
  });

  it('AC9 — stale state renders the Stale badge + tooltip and logs runContext.stale', async () => {
    // Pin "now" an hour past the fixture activity → stale.
    vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-05-30T13:00:00Z'));
    server.use(detailResponse(specRejectAndResubmitDetail));

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'stale'));
    const stale = screen.getByTestId('run-context-stale-badge');
    expect(stale).toHaveTextContent('Stale');
    expect(stale).toHaveAttribute('data-state-name', 'stale');
    expect(stale.getAttribute('title')).toMatch(/runner may be unresponsive/);
    expect(console.warn).toHaveBeenCalledWith(
      expect.objectContaining({ event: 'runContext.stale' }),
    );
    expect(region()).toHaveStyle({ maxHeight: RUN_CONTEXT_STRIP_MAX_HEIGHT });
  });

  it('AC9 — recomputes stale state when the threshold is crossed while mounted', async () => {
    let now = NOW_MS;
    vi.mocked(Date.now).mockImplementation(() => now);
    const msUntilStale = 10;
    const activityMs = NOW_MS - RUN_STALE_THRESHOLD_MS + msUntilStale;
    server.use(
      detailResponse({
        ...specRejectAndResubmitDetail,
        lastActivityTimestamp: new Date(activityMs).toISOString(),
      }),
    );

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'default'));
    now += msUntilStale + 1;
    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'stale'));
  });

  it('renders the escalation marker when escalationMarker is true', async () => {
    server.use(detailResponse({ ...specRejectAndResubmitDetail, escalationMarker: true }));

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'default'));
    expect(screen.getByTestId('run-context-escalation')).toHaveTextContent('Escalated');
  });

  it('AC3 — error composes ErrorState; Retry refetches and logs runContext.retry', async () => {
    let calls = 0;
    server.use(
      http.get(DETAIL_URL, () => {
        calls += 1;
        return problemResponse();
      }),
    );

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'error'));
    expect(screen.getByTestId('error-state')).toBeInTheDocument();
    expect(region()).toHaveStyle({
      maxHeight: RUN_CONTEXT_STRIP_MAX_HEIGHT,
      overflow: 'auto',
    });
    expect(calls).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(calls).toBe(2));
    expect(console.info).toHaveBeenCalledWith(
      expect.objectContaining({ event: 'runContext.retry' }),
    );
  });

  it('Task 6 — runContext.loadError logs only the field-only contract, never the raw message', async () => {
    server.use(http.get(DETAIL_URL, () => problemResponse()));

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'error'));
    const warnMock = vi.mocked(console.warn);
    const loadErrorCall = warnMock.mock.calls.find(
      ([arg]) => (arg as { event?: string } | undefined)?.event === 'runContext.loadError',
    );
    expect(loadErrorCall).toBeDefined();
    const payload = loadErrorCall![0] as Record<string, unknown>;
    expect(payload).not.toHaveProperty('message');
    expect(payload).toMatchObject({ event: 'runContext.loadError', code: 'INTERNAL_ERROR' });
    expect(Object.keys(payload).sort()).toEqual(['code', 'event', 'transport']);
  });

  it('AC6 — loading exposes a busy signal while skeletons are visible', async () => {
    server.use(
      http.get(DETAIL_URL, async () => {
        await delay(20);
        return HttpResponse.json(specRejectAndResubmitDetail);
      }),
    );

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    expect(region()).toHaveAttribute('aria-busy', 'true');
    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'default'));
    expect(region()).not.toHaveAttribute('aria-busy');
  });

  it('keeps long backend values inspectable instead of silently clipping them', async () => {
    const longActor = 'A very long automated runner identity that would otherwise disappear';
    server.use(detailResponse({ ...specRejectAndResubmitDetail, currentActorIdentity: longActor }));

    renderStrip(<RunContextStrip workflowRunId={RUN_ID} />);

    await waitFor(() => expect(region()).toHaveAttribute('data-run-context-state', 'default'));
    expect(screen.getByTestId('run-context-actor')).toHaveAttribute(
      'title',
      `${longActor} (human)`,
    );
  });
});
