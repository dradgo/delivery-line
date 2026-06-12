/**
 * Story 2.19 (Task 1) — unit tests for the `approvalDecisionView` pure helpers.
 *
 * Covers: primary-action selection (AC7), state-resolution precedence (AC3/AC6),
 * reason-code mapping incl. fallback (AC5c), version derivation incl. null-stamp →
 * blocked (AC6/T-VERSIONSTAMP), unknown-action coercion (UX-DR6), and stale detection.
 */
import { describe, expect, it } from 'vitest';

import {
  ARTIFACT_UNAVAILABLE_REASON,
  GENERIC_DISABLED_REASON,
  buildRecoveryContextLabel,
  canRetry,
  coerceAction,
  deriveExpectedVersions,
  isStaleAgainst,
  mapDisabledReason,
  normalizeActions,
  pendingClarificationsMessage,
  resolveApprovalBarState,
  resolveConsequenceHint,
  resolvePrimaryAction,
  resolveSpecArtifactId,
  type ApprovalDecisionView,
} from './approvalDecisionView';
import type { WorkflowDetail } from '@/lib/api/queryOptions';
import {
  blockedNoArtifactView,
  blockedNoPrimaryView,
  blockedNullVersionsView,
  implementationReviewView,
  lockedView,
  readyView,
  recoveryOperatorView,
  recoveryReadyView,
  versionStampAdvanced,
  versionStampFull,
} from '@/test/fixtures/approval/approvalDecisionFixtures';

describe('normalizeActions / coerceAction (UX-DR6 forward-compat)', () => {
  it('coerces a recognized action to itself', () => {
    expect(coerceAction('approve_spec')).toBe('approve_spec');
  });

  it('coerces an unrecognized action to the `unknown` sentinel', () => {
    expect(coerceAction('teleport_run')).toBe('unknown');
  });

  it('drops unknown wire-values and keeps known ones', () => {
    expect(normalizeActions(['approve_spec', 'teleport_run', 'reject_spec'])).toEqual([
      'approve_spec',
      'reject_spec',
    ]);
  });

  it('maps undefined to an empty list', () => {
    expect(normalizeActions(undefined)).toEqual([]);
  });
});

describe('resolvePrimaryAction (AC7 — one primary)', () => {
  it('selects approve_spec as the only affirmative primary', () => {
    expect(resolvePrimaryAction(['approve_spec', 'reject_spec'])).toBe('approve_spec');
  });

  it('never promotes reject_spec to primary (it is always secondary — AC2)', () => {
    expect(resolvePrimaryAction(['reject_spec'])).toBeNull();
  });

  it('returns null when no affirmative primary is available → blocked', () => {
    expect(resolvePrimaryAction(['answer_clarification'])).toBeNull();
    expect(resolvePrimaryAction([])).toBeNull();
  });
});

describe('deriveExpectedVersions (AC6 / T-VERSIONSTAMP — two ints, not one stamp)', () => {
  it('pulls both version ints from the stamp', () => {
    expect(deriveExpectedVersions(versionStampFull)).toEqual({
      expectedArtifactVersion: 3,
      expectedContextBundleVersion: 1,
    });
  });

  it('returns null when either version is null (cannot build a request)', () => {
    expect(
      deriveExpectedVersions({
        currentSpecArtifactVersion: null,
        currentContextBundleVersion: 1,
        workflowState: 'WaitingForSpecApproval',
      }),
    ).toBeNull();
    expect(
      deriveExpectedVersions({
        currentSpecArtifactVersion: 4,
        currentContextBundleVersion: null,
        workflowState: 'WaitingForSpecApproval',
      }),
    ).toBeNull();
  });

  it('returns null for an absent stamp', () => {
    expect(deriveExpectedVersions(undefined)).toBeNull();
  });
});

describe('isStaleAgainst (AC6 — UI-side stale detection)', () => {
  it('is stale when lastEventId moved', () => {
    expect(isStaleAgainst(readyView, versionStampAdvanced)).toBe(true);
  });

  it('is stale when the spec artifact version advanced', () => {
    expect(
      isStaleAgainst(readyView, {
        ...versionStampFull,
        currentSpecArtifactVersion: 9,
      }),
    ).toBe(true);
  });

  it('is stale when ONLY the context bundle version advanced', () => {
    expect(
      isStaleAgainst(readyView, {
        ...versionStampFull,
        currentContextBundleVersion: 2,
      }),
    ).toBe(true);
  });

  it('is not stale against an identical stamp', () => {
    expect(isStaleAgainst(readyView, versionStampFull)).toBe(false);
  });
});

describe('resolveSpecArtifactId (T-ARTIFACTID — reads the forward-compat id, never fabricates)', () => {
  it('returns undefined when no spec artifact carries an id (the dormant boundary)', () => {
    const detail = {
      latestArtifacts: [{ artifactType: 'spec', status: 'pending', version: 3 }],
    } as unknown as WorkflowDetail;
    expect(resolveSpecArtifactId(detail)).toBeUndefined();
  });

  it('reads the id off the spec artifact when present', () => {
    const detail = {
      latestArtifacts: [
        { artifactType: 'spec', status: 'pending', version: 3, artifactId: 'art_1' },
      ],
    } as unknown as WorkflowDetail;
    expect(resolveSpecArtifactId(detail)).toBe('art_1');
  });

  it('picks the HIGHEST-version spec id when multiple spec entries exist (not array order)', () => {
    const detail = {
      latestArtifacts: [
        { artifactType: 'spec', status: 'superseded', version: 2, artifactId: 'art_old' },
        { artifactType: 'spec', status: 'pending', version: 4, artifactId: 'art_new' },
      ],
    } as unknown as WorkflowDetail;
    expect(resolveSpecArtifactId(detail)).toBe('art_new');
  });
});

describe('mapDisabledReason (AC5c — reason-code table + generic fallback)', () => {
  it('maps a known reason code to its localized text', () => {
    expect(mapDisabledReason('CLARIFICATIONS_PENDING')).toContain('Clarifications are pending');
  });

  it('falls back to the generic explanation for an unknown code', () => {
    expect(mapDisabledReason('SOME_FUTURE_CODE')).toBe(GENERIC_DISABLED_REASON);
  });

  it('falls back to the generic explanation when the code is absent (live posture)', () => {
    expect(mapDisabledReason(undefined)).toBe(GENERIC_DISABLED_REASON);
  });
});

describe('pendingClarificationsMessage (AC13)', () => {
  it('pluralizes correctly', () => {
    expect(pendingClarificationsMessage(1)).toBe(
      '1 clarification pending incorporation — approval blocked',
    );
    expect(pendingClarificationsMessage(3)).toBe(
      '3 clarifications pending incorporation — approval blocked',
    );
  });
});

describe('resolveConsequenceHint (AC2)', () => {
  it('returns the static approve consequence for spec_approval', () => {
    expect(resolveConsequenceHint('spec_approval', 'approve_spec')).toContain('Executing');
  });

  it('returns undefined for a stub mode (no live action)', () => {
    expect(resolveConsequenceHint('implementation_review', 'approve_spec')).toBeUndefined();
  });
});

describe('resolveApprovalBarState (AC3/AC6 — precedence locked > error > stale > submitting > disabled(stub) > blocked > success > ready)', () => {
  it('ready when actions enabled, artifact resolvable, versions present', () => {
    expect(resolveApprovalBarState(readyView, { status: 'idle' })).toBe('ready');
  });

  it('submitting while a mutation is in flight', () => {
    expect(resolveApprovalBarState(readyView, { status: 'pending' })).toBe('submitting');
  });

  it('success after a decision lands', () => {
    expect(resolveApprovalBarState(readyView, { status: 'success' })).toBe('success');
  });

  it('error on a generic mutation failure', () => {
    expect(
      resolveApprovalBarState(readyView, { status: 'error', errorCode: 'ILLEGAL_TRANSITION' }),
    ).toBe('error');
  });

  it('stale (not error) on APPROVAL_VERSION_MISMATCH (AC6)', () => {
    expect(
      resolveApprovalBarState(readyView, {
        status: 'error',
        errorCode: 'APPROVAL_VERSION_MISMATCH',
      }),
    ).toBe('stale');
  });

  it('stale via UI-side local detection', () => {
    expect(resolveApprovalBarState(readyView, { status: 'idle' }, { stale: true })).toBe('stale');
  });

  it('locked takes precedence over everything when a prior decision exists', () => {
    expect(
      resolveApprovalBarState(lockedView, { status: 'error', errorCode: 'X' }, { locked: true }),
    ).toBe('locked');
  });

  it('blocked when no affirmative primary action (AC7)', () => {
    expect(resolveApprovalBarState(blockedNoPrimaryView, { status: 'idle' })).toBe('blocked');
  });

  it('blocked when artifactId is unresolved — the dormancy boundary (T-ARTIFACTID)', () => {
    expect(resolveApprovalBarState(blockedNoArtifactView, { status: 'idle' })).toBe('blocked');
  });

  it('blocked when the version stamp cannot produce both ints (T-VERSIONSTAMP)', () => {
    expect(resolveApprovalBarState(blockedNullVersionsView, { status: 'idle' })).toBe('blocked');
  });

  it('disabled for a stub mode (mode-specific control restriction)', () => {
    expect(resolveApprovalBarState(implementationReviewView, { status: 'idle' })).toBe('disabled');
  });

  it('exposes a never-bare artifact-unavailable reason constant', () => {
    expect(ARTIFACT_UNAVAILABLE_REASON.length).toBeGreaterThan(0);
  });

  it('blocked wins over success when the action set no longer permits a decision', () => {
    const view: ApprovalDecisionView = { ...readyView, actions: [] };
    expect(resolveApprovalBarState(view, { status: 'success' })).toBe('blocked');
  });
});

describe('story 3.30 — recovery_operator resolution + retry recognition', () => {
  it('recognizes the `retry` wire action (no longer coerced to unknown)', () => {
    expect(coerceAction('retry')).toBe('retry');
    expect(normalizeActions(['retry', 'reconcile'])).toEqual(['retry']);
  });

  it('canRetry requires BOTH Failed state AND a live `retry` action', () => {
    expect(canRetry(recoveryReadyView)).toBe(true);
    // Failed but no retry action → not eligible (View only).
    expect(canRetry(recoveryOperatorView)).toBe(false);
    // retry action but not Failed → not eligible.
    expect(canRetry({ ...recoveryReadyView, currentState: 'Executing' })).toBe(false);
  });

  it('resolves recovery_operator to ready / disabled / submitting / success', () => {
    expect(resolveApprovalBarState(recoveryReadyView, { status: 'idle' })).toBe('ready');
    expect(resolveApprovalBarState(recoveryOperatorView, { status: 'idle' })).toBe('disabled');
    expect(resolveApprovalBarState(recoveryReadyView, { status: 'pending' })).toBe('submitting');
    expect(resolveApprovalBarState(recoveryReadyView, { status: 'success' })).toBe('success');
    expect(resolveApprovalBarState(recoveryReadyView, { status: 'error', errorCode: 'X' })).toBe(
      'error',
    );
  });

  it('buildRecoveryContextLabel names the failed stage when present', () => {
    expect(buildRecoveryContextLabel({ failedStage: 'implementation' })).toMatch(
      /implementation stage/,
    );
    expect(buildRecoveryContextLabel(undefined)).toBe('Recover the failed run');
  });
});
