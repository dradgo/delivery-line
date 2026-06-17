/**
 * Story 3.29 (Task 1, AC2/AC5/AC7) — `selectTakeoverAttribution` unit tests.
 *
 * The selector mirrors the backend `getRunSummary` takeover fallback (R2): it derives
 * who/when/why from the LATEST `workflow.stateChanged → TakenOver` event in the
 * append-only stream. Asserts: picks the takeover event; ignores non-takeover state
 * changes; returns `undefined` when absent; tolerates out-of-order arrival; passes
 * `reviewerRole` through and coalesces a `null` reason to `undefined`.
 */
import { describe, expect, it } from 'vitest';

import type { WorkflowEventsResponse } from '@/lib/api/queryOptions';

import { selectTakeoverAttribution } from './takeoverView';

type WorkflowEvent = WorkflowEventsResponse['events'][number];

/** A minimal valid `WorkflowEvent`, overridable per case. */
function event(overrides: Partial<WorkflowEvent>): WorkflowEvent {
  return {
    publicId: 'evt_x',
    workflowRunPublicId: 'run_x',
    eventType: 'workflow.stateChanged',
    actorIdentity: 'system',
    actorType: 'system',
    interventionMarker: false,
    createdAt: '2026-06-17T10:00:00Z',
    details: {},
    ...overrides,
  } as WorkflowEvent;
}

const takeover = (overrides: Partial<WorkflowEvent> = {}): WorkflowEvent =>
  event({
    publicId: 'evt_takeover',
    eventType: 'workflow.stateChanged',
    priorState: 'WaitingForReview',
    resultingState: 'TakenOver',
    actorIdentity: 'dev@example.com',
    actorType: 'human',
    reason: 'Edge case the agent could not resolve',
    details: { reviewerRole: 'developer', correlationId: 'corr_to_1' },
    createdAt: '2026-06-17T11:00:00Z',
    ...overrides,
  });

describe('selectTakeoverAttribution', () => {
  it('derives attribution from the takeover state-change event', () => {
    const view = selectTakeoverAttribution([
      event({ resultingState: 'WaitingForReview' }),
      takeover(),
    ]);

    expect(view).toEqual({
      takenOverBy: 'dev@example.com',
      actorType: 'human',
      reviewerRole: 'developer',
      takenOverAt: '2026-06-17T11:00:00Z',
      takenOverReason: 'Edge case the agent could not resolve',
      eventId: 'evt_takeover',
    });
  });

  it('ignores non-takeover state changes and other event types', () => {
    const view = selectTakeoverAttribution([
      event({ eventType: 'runner.failed', resultingState: 'Failed' }),
      event({ eventType: 'workflow.stateChanged', resultingState: 'Completed' }),
      event({ eventType: 'approval.approved' }),
    ]);
    expect(view).toBeUndefined();
  });

  it('returns undefined for an empty stream', () => {
    expect(selectTakeoverAttribution([])).toBeUndefined();
  });

  it('selects the LATEST takeover by createdAt, not array position (out-of-order safe)', () => {
    const earlier = takeover({
      publicId: 'evt_old',
      actorIdentity: 'old@example.com',
      createdAt: '2026-06-17T09:00:00Z',
    });
    const later = takeover({
      publicId: 'evt_new',
      actorIdentity: 'new@example.com',
      createdAt: '2026-06-17T13:00:00Z',
    });
    // Newest appears FIRST in the array — position must not win over createdAt.
    const view = selectTakeoverAttribution([later, earlier]);
    expect(view?.eventId).toBe('evt_new');
    expect(view?.takenOverBy).toBe('new@example.com');
  });

  it('coalesces a null reason and an absent reviewerRole to undefined', () => {
    const view = selectTakeoverAttribution([
      takeover({ reason: null, details: { correlationId: 'c' } }),
    ]);
    expect(view?.takenOverReason).toBeUndefined();
    expect(view?.reviewerRole).toBeUndefined();
  });
});
