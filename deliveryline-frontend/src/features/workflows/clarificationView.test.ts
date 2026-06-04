/**
 * Story 2.18 (Task 1) — pure view-model helper tests for `clarificationView.ts`.
 *
 * Grouping precedence, pending-count (incl. the T8 superseded rule), lifecycle
 * position, item-state resolution (incl. local draft/validation/error precedence),
 * and the future-live runtime guard.
 */
import { describe, expect, it } from 'vitest';

import {
  clarificationItemSignifier,
  coerceStatus,
  countPendingIncorporation,
  groupClarificationsByStatus,
  isClarificationsView,
  normalizeClarificationsView,
  resolveClarificationItemState,
  resolveLifecyclePosition,
  type ClarificationsView,
} from './clarificationView';
import {
  acceptedClarification,
  answeredClarification,
  incorporatedClarification,
  multiQuestionView,
  openClarification,
  rejectedInvalidClarification,
  supersededClarification,
  unknownClarification,
} from '@/test/fixtures/clarification/clarificationViewFixtures';

describe('groupClarificationsByStatus (AC2)', () => {
  it('partitions into open → pending → terminal → unknown buckets', () => {
    const grouped = groupClarificationsByStatus(multiQuestionView);

    expect(grouped.open.map((c) => c.status)).toEqual(['open']);
    expect(grouped.pending.map((c) => c.status)).toEqual(['answered', 'accepted']);
    expect(grouped.terminal.map((c) => c.status)).toEqual([
      'incorporated',
      'superseded',
      'rejected_invalid',
    ]);
    expect(grouped.unknown.map((c) => c.status)).toEqual(['unknown']);
  });

  it('preserves input order within a group', () => {
    const view: ClarificationsView = {
      clarifications: [
        { ...acceptedClarification, clarificationId: 'cla_accept0002' },
        answeredClarification,
      ],
    };
    // pending bucket keeps input order (accepted-before-answered here), it does NOT
    // re-sort within the group.
    expect(groupClarificationsByStatus(view).pending.map((c) => c.clarificationId)).toEqual([
      'cla_accept0002',
      answeredClarification.clarificationId,
    ]);
  });
});

describe('countPendingIncorporation (AC10 / T8 / Decision-①)', () => {
  it('excludes incorporated + rejected_invalid + the unknown sentinel (superseded still counts)', () => {
    // multiQuestion has: incorporated, open, superseded, answered, rejected_invalid,
    // accepted, unknown → pending = all EXCEPT incorporated + rejected_invalid + the
    // unknown sentinel = 4 (open, superseded, answered, accepted) — Decision-①.
    expect(countPendingIncorporation(multiQuestionView)).toBe(4);
  });

  it('superseded counts as pending (T8)', () => {
    expect(countPendingIncorporation({ clarifications: [supersededClarification] })).toBe(1);
  });

  it('incorporated + rejected_invalid do not block', () => {
    expect(
      countPendingIncorporation({
        clarifications: [incorporatedClarification, rejectedInvalidClarification],
      }),
    ).toBe(0);
  });

  it('the unknown sentinel does not block approval (Decision-①)', () => {
    expect(countPendingIncorporation({ clarifications: [unknownClarification] })).toBe(0);
  });
});

describe('resolveLifecyclePosition (AC2/AC5)', () => {
  it('advances along submitted → accepted → incorporated', () => {
    expect(resolveLifecyclePosition('answered').currentIndex).toBe(0);
    expect(resolveLifecyclePosition('accepted').currentIndex).toBe(1);
    expect(resolveLifecyclePosition('incorporated').currentIndex).toBe(2);
  });

  it('open/unknown have not entered the chain', () => {
    expect(resolveLifecyclePosition('open').currentIndex).toBe(-1);
    expect(resolveLifecyclePosition('unknown').currentIndex).toBe(-1);
  });

  it('superseded/rejected_invalid are off-chain (was submitted, then set aside)', () => {
    expect(resolveLifecyclePosition('superseded')).toMatchObject({
      currentIndex: 0,
      offChain: true,
    });
    expect(resolveLifecyclePosition('rejected_invalid')).toMatchObject({
      currentIndex: 0,
      offChain: true,
    });
  });
});

describe('resolveClarificationItemState (AC3 / OQ-3)', () => {
  it('maps each backend status to its render state', () => {
    expect(resolveClarificationItemState(openClarification)).toBe('unanswered');
    expect(resolveClarificationItemState(answeredClarification)).toBe('answered');
    expect(resolveClarificationItemState(acceptedClarification)).toBe('accepted');
    expect(resolveClarificationItemState(incorporatedClarification)).toBe('incorporated');
    expect(resolveClarificationItemState(supersededClarification)).toBe('superseded');
    expect(resolveClarificationItemState(rejectedInvalidClarification)).toBe('rejected_invalid');
    expect(resolveClarificationItemState(unknownClarification)).toBe('unknown');
  });

  it('a non-empty unsubmitted draft on an open question reads as in_progress', () => {
    expect(resolveClarificationItemState(openClarification, { text: 'a partial answer' })).toBe(
      'in_progress',
    );
    // whitespace-only draft is NOT in progress
    expect(resolveClarificationItemState(openClarification, { text: '   ' })).toBe('unanswered');
  });

  it('local validation error and submit error take precedence over the backend status', () => {
    expect(
      resolveClarificationItemState(answeredClarification, { validationError: 'Answer required' }),
    ).toBe('blocked_invalid');
    expect(
      resolveClarificationItemState(openClarification, { errorCode: 'CLARIFICATION_NOT_FOUND' }),
    ).toBe('error');
    // errorCode wins over validationError
    expect(
      resolveClarificationItemState(openClarification, {
        validationError: 'x',
        errorCode: 'INTERNAL_ERROR',
      }),
    ).toBe('error');
  });
});

describe('clarificationItemSignifier (story 2.3 AC5 — never color alone)', () => {
  it('pairs every item state with a label', () => {
    expect(clarificationItemSignifier('incorporated')).toEqual({
      stateName: 'success',
      label: 'Incorporated',
    });
    expect(clarificationItemSignifier('superseded').stateName).toBe('stale');
    expect(clarificationItemSignifier('unanswered').label).toBe('Open');
  });
});

describe('isClarificationsView (future-live runtime guard)', () => {
  it('accepts a well-formed view', () => {
    expect(isClarificationsView(multiQuestionView)).toBe(true);
    expect(isClarificationsView({ clarifications: [] })).toBe(true);
  });

  it('rejects malformed / partial shapes', () => {
    expect(isClarificationsView(undefined)).toBe(false);
    expect(isClarificationsView({})).toBe(false);
    expect(isClarificationsView({ clarifications: [{ clarificationId: 'cla_x' }] })).toBe(false);
    // STRICT guard still rejects an out-of-union status (graceful degradation is the
    // normalize path's job below, not this strict predicate's).
    expect(
      isClarificationsView({ clarifications: [{ ...openClarification, status: 'bogus' }] }),
    ).toBe(false);
  });
});

describe('normalizeClarificationsView + coerceStatus (Decision-②)', () => {
  it('coerces an unrecognized status to `unknown` instead of dropping the row or blanking the view', () => {
    const normalized = normalizeClarificationsView({
      clarifications: [{ ...openClarification, status: 'a-future-server-status' }],
    });
    expect(normalized.clarifications).toHaveLength(1);
    expect(normalized.clarifications[0]!.status).toBe('unknown');
  });

  it('drops only the structurally-malformed row, keeping valid siblings', () => {
    const normalized = normalizeClarificationsView({
      clarifications: [openClarification, { clarificationId: 'cla_broken' }],
    });
    expect(normalized.clarifications.map((c) => c.clarificationId)).toEqual([
      openClarification.clarificationId,
    ]);
  });

  it('returns an empty view for a non-view value (never throws)', () => {
    expect(normalizeClarificationsView(undefined).clarifications).toEqual([]);
    expect(normalizeClarificationsView({}).clarifications).toEqual([]);
  });

  it('coerceStatus passes known statuses through and maps the rest to `unknown`', () => {
    expect(coerceStatus('answered')).toBe('answered');
    expect(coerceStatus('nonsense')).toBe('unknown');
  });
});
