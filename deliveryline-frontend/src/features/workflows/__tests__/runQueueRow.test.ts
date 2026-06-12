/**
 * Story 2.15 (Task 1 / AC1, AC3, AC5) — unit tests for the pure view-model helpers.
 *
 * Router-free + render-free: exercises `toRunQueueRow` (each live field + the
 * absent-field → `undefined` reconciliation), `resolvePrimaryAttentionIndicator`
 * (the full priority ladder + the multi-signal "exactly one primary" proof), and
 * `resolveQueueItemState` (every precedence branch).
 */
import { describe, expect, it } from 'vitest';

import type { WorkflowSummary } from '@/lib/api/queryOptions';
import {
  resolvePrimaryAttentionIndicator,
  resolveQueueItemState,
  toRunQueueRow,
  type RunQueueRow,
} from '../runQueueRow';

const LIVE_SUMMARY: WorkflowSummary = {
  workflowRunId: 'run_abc123',
  ticketRef: 'DEL-1234',
  currentState: 'WaitingForSpecApproval',
  lastEventAt: '2026-05-31T14:00:00Z',
  lastEventType: 'workflow.stateChanged',
  escalationMarker: true,
  specRejectionLoopCount: 2,
};

describe('toRunQueueRow', () => {
  it('maps each of the 7 live fields per the reconciliation table', () => {
    const row = toRunQueueRow(LIVE_SUMMARY);
    expect(row.runId).toBe('run_abc123');
    expect(row.linearTicketReference).toBe('DEL-1234');
    expect(row.currentState).toBe('WaitingForSpecApproval');
    // Trap T2 — lastTransitionAt comes from lastEventAt, never lastActivityTimestamp.
    expect(row.lastTransitionAt).toBe('2026-05-31T14:00:00Z');
    expect(row.escalationMarker).toBe(true);
    expect(row.specRejectionLoopCount).toBe(2);
  });

  it('leaves every dormant (non-live) field undefined', () => {
    const row = toRunQueueRow(LIVE_SUMMARY);
    expect(row.summary).toBeUndefined();
    expect(row.currentArtifactType).toBeUndefined();
    expect(row.assigneeHint).toBeUndefined();
    expect(row.blockerCount).toBeUndefined();
    expect(row.openQuestionCount).toBeUndefined();
    expect(row.staleIndicator).toBeUndefined();
  });

  it('passes through absent live fields as undefined (never fabricated)', () => {
    const row = toRunQueueRow({});
    expect(row.runId).toBeUndefined();
    expect(row.linearTicketReference).toBeUndefined();
    expect(row.currentState).toBeUndefined();
    expect(row.lastTransitionAt).toBeUndefined();
    expect(row.escalationMarker).toBeUndefined();
    expect(row.specRejectionLoopCount).toBeUndefined();
  });
});

describe('resolvePrimaryAttentionIndicator', () => {
  it('returns null when no signal is active', () => {
    expect(resolvePrimaryAttentionIndicator({})).toBeNull();
  });

  it('returns escalation for a live escalation marker (the only live-reachable signal)', () => {
    expect(resolvePrimaryAttentionIndicator({ escalationMarker: true })).toBe('escalation');
  });

  it('returns blocker / openQuestion / stale when each is the sole active signal', () => {
    expect(resolvePrimaryAttentionIndicator({ blockerCount: 1 })).toBe('blocker');
    expect(resolvePrimaryAttentionIndicator({ openQuestionCount: 3 })).toBe('openQuestion');
    expect(resolvePrimaryAttentionIndicator({ staleIndicator: true })).toBe('stale');
  });

  it('honors the priority order blocker > escalation > openQuestion > stale (exactly one primary)', () => {
    // A fixture with EVERY signal active simultaneously — only the top wins.
    const allActive: RunQueueRow = {
      blockerCount: 2,
      escalationMarker: true,
      openQuestionCount: 4,
      staleIndicator: true,
    };
    expect(resolvePrimaryAttentionIndicator(allActive)).toBe('blocker');
    // Peel off the winner each step to prove the full ladder.
    expect(resolvePrimaryAttentionIndicator({ ...allActive, blockerCount: 0 })).toBe('escalation');
    expect(
      resolvePrimaryAttentionIndicator({
        ...allActive,
        blockerCount: 0,
        escalationMarker: false,
      }),
    ).toBe('openQuestion');
    expect(
      resolvePrimaryAttentionIndicator({
        ...allActive,
        blockerCount: 0,
        escalationMarker: false,
        openQuestionCount: 0,
      }),
    ).toBe('stale');
  });

  it('treats a zero / undefined count as inactive', () => {
    expect(resolvePrimaryAttentionIndicator({ blockerCount: 0, openQuestionCount: 0 })).toBeNull();
  });
});

describe('resolveQueueItemState', () => {
  it('defaults to "default" for an empty row', () => {
    expect(resolveQueueItemState({ row: {} })).toBe('default');
  });

  it('honors precedence disabled > blocked > stale > selected > unread > default', () => {
    const row: RunQueueRow = { blockerCount: 1, staleIndicator: true };
    // disabled beats everything.
    expect(resolveQueueItemState({ row, selected: true, unread: true, disabled: true })).toBe(
      'disabled',
    );
    // blocked beats stale/selected/unread.
    expect(resolveQueueItemState({ row, selected: true, unread: true })).toBe('blocked');
    // stale beats selected/unread.
    expect(
      resolveQueueItemState({ row: { staleIndicator: true }, selected: true, unread: true }),
    ).toBe('stale');
    // selected beats unread.
    expect(resolveQueueItemState({ row: {}, selected: true, unread: true })).toBe('selected');
    // unread beats default.
    expect(resolveQueueItemState({ row: {}, unread: true })).toBe('unread');
  });

  it('treats a zero blocker count as not blocked', () => {
    expect(resolveQueueItemState({ row: { blockerCount: 0 } })).toBe('default');
  });

  it('story 3.30 (AC8) — a Failed run resolves to the failed state (above blocked)', () => {
    expect(resolveQueueItemState({ row: { currentState: 'Failed', blockerCount: 5 } })).toBe(
      'failed',
    );
    // disabled still beats failed.
    expect(resolveQueueItemState({ row: { currentState: 'Failed' }, disabled: true })).toBe(
      'disabled',
    );
  });
});

describe('story 3.30 (AC8) — failed attention indicator', () => {
  it('a Failed run yields the failed primary indicator, outranking every other signal', () => {
    expect(
      resolvePrimaryAttentionIndicator({
        currentState: 'Failed',
        blockerCount: 3,
        escalationMarker: true,
      }),
    ).toBe('failed');
  });

  it('a non-Failed run is unaffected (failed never fabricated)', () => {
    expect(resolvePrimaryAttentionIndicator({ currentState: 'Executing' })).toBeNull();
  });

  it('toRunQueueRow leaves the compact failureCategory dormant (no live source)', () => {
    expect(toRunQueueRow(LIVE_SUMMARY).failureCategory).toBeUndefined();
  });
});
