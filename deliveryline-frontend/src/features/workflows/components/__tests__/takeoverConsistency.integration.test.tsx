/**
 * Story 3.29 (Task 6/Task 7, AC2/AC7/AC9/AC10) — cross-surface takeover consistency
 * + regression pins for the 3.28-delivered takeover machinery.
 *
 * AC7: a taken-over run, loaded fresh, must read as taken-over on EVERY surface. This
 * mounts the `RunContextStrip` and `FailureEventSurface` for the SAME run id sharing
 * ONE `QueryClient` (so they read the same warmed cache, exactly like the live route),
 * and asserts both derive the takeover attribution from the same live events stream
 * (R2) — no surface shows the run as in-flight.
 *
 * R7 (regression pin): `useWorkflowMutation.onSuccess` invalidates `workflowKeys.detail(id)`,
 * a STRUCTURAL PREFIX of `events(id)` / `allowedActions(id)`, so one takeover invalidation
 * cascades to every surface together (AC2). Pinned structurally here; the mutation wiring
 * itself is covered by story 3.28 / `useWorkflowMutation.test`.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, cleanup, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { server } from '@/test/server';
import { expectNoA11yViolations } from '@/test/a11y/axe';
import { workflowKeys } from '@/lib/queryKeys/workflowKeys';
import { workflowTakenOver } from '@/lib/a11y/announcements';
import type { WorkflowDetail } from '@/lib/api/queryOptions';

const { backSpy } = vi.hoisted(() => ({ backSpy: vi.fn() }));
vi.mock('@/lib/navigation/useReturnToRunContext', () => ({
  useReturnToRunContext: () => backSpy,
}));

import { RunContextStrip } from '../RunContextStrip';
import { FailureEventSurface } from '../FailureEventSurface';

const RUN_ID = 'run_taken_over_42';
const DETAIL_URL = `http://localhost/api/v1/workflows/${RUN_ID}`;
const EVENTS_URL = `http://localhost/api/v1/workflows/${RUN_ID}/events`;

const takenOverDetail: WorkflowDetail = {
  workflowRunId: RUN_ID,
  currentState: 'TakenOver',
  currentActorIdentity: 'dev@acme.example',
  currentActorType: 'human',
  escalationMarker: false,
  lastEventAt: '2026-06-17T12:00:00Z',
  lastActivityTimestamp: '2026-06-17T12:00:00Z',
  latestArtifacts: [{ artifactType: 'prOutput', status: 'ready', version: 2 }],
  linkedTicket: { externalRef: 'DEL-9002' },
};

const takenOverEvents = {
  workflowRun: {
    publicId: RUN_ID,
    ticketRef: 'DEL-9002',
    createdAt: '2026-06-17T11:00:00Z',
    terminalState: 'TakenOver',
  },
  events: [
    {
      publicId: 'evt_to_42',
      workflowRunPublicId: RUN_ID,
      eventType: 'workflow.stateChanged',
      priorState: 'WaitingForReview',
      resultingState: 'TakenOver',
      actorIdentity: 'dev@acme.example',
      actorType: 'human',
      reason: 'Continuing the implementation by hand',
      failureCategory: null,
      interventionMarker: true,
      createdAt: '2026-06-17T12:00:00Z',
      details: { reviewerRole: 'developer', correlationId: 'corr_to_42' },
    },
  ],
};

function sharedClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderSurfaces(node: ReactNode) {
  return render(<QueryClientProvider client={sharedClient()}>{node}</QueryClientProvider>);
}

beforeEach(() => {
  backSpy.mockClear();
  vi.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-06-17T12:05:00Z'));
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('takeover consistency across surfaces (story 3.29, AC7)', () => {
  function serveTakenOver() {
    server.use(
      http.get(DETAIL_URL, () => HttpResponse.json(takenOverDetail)),
      http.get(EVENTS_URL, () => HttpResponse.json(takenOverEvents)),
    );
  }

  it('AC2b/AC5/AC7 — strip attribution + event-surface row both reflect the takeover', async () => {
    serveTakenOver();
    renderSurfaces(
      <>
        <RunContextStrip workflowRunId={RUN_ID} />
        <FailureEventSurface workflowRunId={RUN_ID} />
      </>,
    );

    // Run Context Strip — state badge flips to TakenOver + attribution block appears.
    const strip = await screen.findByTestId('run-takeover-attribution');
    expect(within(strip).getByTestId('run-takeover-actor')).toHaveTextContent('dev@acme.example');
    expect(within(strip).getByTestId('run-takeover-reason')).toHaveTextContent(
      'Continuing the implementation by hand',
    );
    expect(screen.getByTestId('workflow-state-badge')).toHaveTextContent('TakenOver');

    // Run-event surface — the takeover row renders prominently, same source.
    const eventRow = await screen.findByTestId('failure-event-row');
    expect(eventRow).toHaveAttribute('data-event-type', 'workflow.stateChanged');
    expect(eventRow).toHaveTextContent('dev@acme.example');
    expect(eventRow).toHaveAttribute('id', 'evt_to_42');

    // No surface shows the run as in-flight / actionable (no failure/Failed treatment).
    expect(screen.queryByTestId('run-recovery-baseline')).toBeNull();
  });

  it('AC10 — the combined taken-over surfaces have zero axe violations', async () => {
    serveTakenOver();
    const { container } = renderSurfaces(
      <>
        <RunContextStrip workflowRunId={RUN_ID} />
        <FailureEventSurface workflowRunId={RUN_ID} />
      </>,
    );
    await screen.findByTestId('run-takeover-attribution');
    await screen.findByTestId('failure-event-row');
    await expectNoA11yViolations(container);
  });
});

describe('takeover regression pins (story 3.29, R1/R7/R8)', () => {
  it('R7 — detail(id) is a structural PREFIX of events(id) + allowedActions(id) (invalidation cascade)', () => {
    const detail = workflowKeys.detail(RUN_ID);
    const events = workflowKeys.events(RUN_ID);
    const allowedActions = workflowKeys.allowedActions(RUN_ID);
    // A detail(id) invalidation cascades to events/allowed-actions because each begins
    // with the full detail(id) key — so one takeover invalidation refreshes every surface.
    expect(events.slice(0, detail.length)).toEqual([...detail]);
    expect(allowedActions.slice(0, detail.length)).toEqual([...detail]);
  });

  it('R8/AC9 — the takeover success announcement vocabulary is stable + non-empty', () => {
    // 3.28 fires this on takeover success via the single polite live region; 3.29 pins it.
    expect(workflowTakenOver).toBe('Run taken over for developer continuation. Decision recorded.');
  });
});
