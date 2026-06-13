/**
 * Story 2.16 (Task 1, Task 5 / AC1, AC7, AC9) — `toRunContextView` mapper +
 * `resolveRunContextState` precedence.
 */
import { describe, expect, it } from 'vitest';

import type { WorkflowDetail } from '@/lib/api/queryOptions';
import {
  RUN_STALE_THRESHOLD_MS,
  resolveRunContextState,
  toRunContextView,
  type RunContextView,
} from './runContextView';

const ACTIVITY = '2026-05-30T12:00:00Z';
const ACTIVITY_MS = Date.parse(ACTIVITY);

function detail(overrides: Partial<WorkflowDetail> = {}): WorkflowDetail {
  return {
    workflowRunId: 'run_abcd0001',
    currentState: 'Executing',
    currentActorIdentity: 'Codex Runner',
    currentActorType: 'agent',
    escalationMarker: false,
    lastEventAt: '2026-05-30T11:59:00Z',
    lastEventType: 'RUNNER_DISPATCHED',
    lastActivityTimestamp: ACTIVITY,
    latestArtifacts: [{ artifactType: 'spec', status: 'approved', version: 2 }],
    linkedTicket: { externalRef: 'DEL-1234', integrationType: 'linear' },
    ...overrides,
  };
}

describe('toRunContextView', () => {
  it('AC1/AC7 — maps each field from the live WorkflowDetail (transition = lastEventAt)', () => {
    const view = toRunContextView(detail(), ACTIVITY_MS);
    expect(view.runId).toBe('run_abcd0001');
    expect(view.currentState).toBe('Executing');
    expect(view.currentActorIdentity).toBe('Codex Runner');
    expect(view.currentActorType).toBe('agent');
    expect(view.latestArtifactType).toBe('spec');
    expect(view.latestArtifactVersion).toBe(2);
    // AC7 — transition is `lastEventAt`, NOT `lastActivityTimestamp`.
    expect(view.lastTransitionAt).toBe('2026-05-30T11:59:00Z');
    expect(view.lastTransitionEventType).toBe('RUNNER_DISPATCHED');
    expect(view.triggerReference).toBe('DEL-1234');
    expect(view.escalationMarker).toBe(false);
    expect(view.lastActivityAt).toBe(ACTIVITY);
  });

  it('AC7 — last transition uses lastEventAt even when it differs from lastActivityTimestamp', () => {
    const view = toRunContextView(
      detail({
        lastEventAt: '2026-05-30T10:00:00Z',
        lastActivityTimestamp: '2026-05-30T11:30:00Z',
      }),
      ACTIVITY_MS,
    );
    expect(view.lastTransitionAt).toBe('2026-05-30T10:00:00Z');
    // The activity timestamp feeds stale ONLY — never the transition pointer.
    expect(view.lastActivityAt).toBe('2026-05-30T11:30:00Z');
  });

  it('AC1 — branchOrCommitReference is always undefined in Epic 2 (no backend field)', () => {
    // The live `WorkflowDetail` exposes no branch/commit field; the mapper hardcodes
    // it to undefined so the strip always renders the partial-context placeholder.
    expect(toRunContextView(detail(), ACTIVITY_MS).branchOrCommitReference).toBeUndefined();
  });

  it('AC1 — selects the highest-version latest artifact', () => {
    const view = toRunContextView(
      detail({
        latestArtifacts: [
          { artifactType: 'spec', version: 1 },
          { artifactType: 'spec', version: 3 },
          { artifactType: 'spec', version: 2 },
        ],
      }),
      ACTIVITY_MS,
    );
    expect(view.latestArtifactType).toBe('spec');
    expect(view.latestArtifactVersion).toBe(3);
  });

  it('AC1 — an empty artifact list leaves the revision pointer undefined', () => {
    const view = toRunContextView(detail({ latestArtifacts: [] }), ACTIVITY_MS);
    expect(view.latestArtifactType).toBeUndefined();
    expect(view.latestArtifactVersion).toBeUndefined();
  });

  it('AC1 — coalesces blank/absent backend strings to undefined', () => {
    const view = toRunContextView({ workflowRunId: '   ', currentState: '' }, ACTIVITY_MS);
    expect(view.runId).toBeUndefined();
    expect(view.currentState).toBeUndefined();
    expect(view.escalationMarker).toBe(false);
  });

  it('AC1 — an all-undefined WorkflowDetail maps to an all-empty view (no throw)', () => {
    const view = toRunContextView({}, ACTIVITY_MS);
    expect(view).toMatchObject<Partial<RunContextView>>({
      runId: undefined,
      currentState: undefined,
      escalationMarker: false,
      staleIndicator: false,
      branchOrCommitReference: undefined,
    });
  });

  it('coalesces null wire fields to undefined without throwing (Investigating run)', () => {
    // The live wire serializes nullable fields as JSON `null`, not as absent
    // (the generated `WorkflowDetail` TS type understates this). An Investigating
    // run records `failedStage`/`runner.failed` but leaves `failureCategory` /
    // `nextSafeAction` null until it reaches `Failed` — the mapper must treat
    // `null` exactly like a missing field (`null.trim()` would otherwise throw).
    const wireDetail = {
      workflowRunId: 'run_a85a',
      currentState: 'Investigating',
      currentActorIdentity: 'system',
      currentActorType: 'system',
      lastEventType: 'runner.failed',
      failedStage: 'runner',
      failureCategory: null,
      nextSafeAction: null,
      lastSuccessfulStage: null,
      failureTimestamp: null,
    } as unknown as WorkflowDetail;

    const view = toRunContextView(wireDetail, ACTIVITY_MS);
    expect(view.currentState).toBe('Investigating');
    expect(view.failedStage).toBe('runner');
    expect(view.failureCategory).toBeUndefined();
    expect(view.nextSafeAction).toBeUndefined();
    expect(view.lastSuccessfulStage).toBeUndefined();
    expect(view.failureTimestamp).toBeUndefined();
  });

  describe('AC9 — staleIndicator boundary', () => {
    it('is false at exactly the threshold age', () => {
      const view = toRunContextView(detail(), ACTIVITY_MS + RUN_STALE_THRESHOLD_MS);
      expect(view.staleIndicator).toBe(false);
    });

    it('is true once age crosses the threshold', () => {
      const view = toRunContextView(detail(), ACTIVITY_MS + RUN_STALE_THRESHOLD_MS + 1);
      expect(view.staleIndicator).toBe(true);
    });

    it('is false when there is no activity timestamp', () => {
      const view = toRunContextView(
        { workflowRunId: 'run_x', currentState: 'Executing', lastEventAt: '2026-05-30T11:00:00Z' },
        ACTIVITY_MS + RUN_STALE_THRESHOLD_MS * 10,
      );
      expect(view.staleIndicator).toBe(false);
    });
  });
});

describe('resolveRunContextState', () => {
  const baseView = (overrides: Partial<RunContextView> = {}): RunContextView => ({
    runId: 'run_abcd0001',
    currentState: 'Executing',
    currentActorIdentity: 'Alex',
    currentActorType: 'human',
    latestArtifactType: 'spec',
    latestArtifactVersion: 2,
    lastTransitionAt: ACTIVITY,
    lastTransitionEventType: 'RUNNER_DISPATCHED',
    triggerReference: 'DEL-1234',
    branchOrCommitReference: undefined,
    escalationMarker: false,
    staleIndicator: false,
    lastActivityAt: ACTIVITY,
    failedStage: undefined,
    lastSuccessfulStage: undefined,
    failureTimestamp: undefined,
    failureCategory: undefined,
    nextSafeAction: undefined,
    ...overrides,
  });

  it('error wins over everything', () => {
    expect(resolveRunContextState({ isError: true, isPending: true, view: undefined })).toBe(
      'error',
    );
  });

  it('loading when pending or no view', () => {
    expect(resolveRunContextState({ isError: false, isPending: true, view: undefined })).toBe(
      'loading',
    );
    expect(resolveRunContextState({ isError: false, isPending: false, view: undefined })).toBe(
      'loading',
    );
  });

  it('stale beats partial-context/default', () => {
    expect(
      resolveRunContextState({
        isError: false,
        isPending: false,
        view: baseView({ staleIndicator: true, triggerReference: undefined }),
      }),
    ).toBe('stale');
  });

  it('partial-context when a business field is unreported', () => {
    expect(
      resolveRunContextState({
        isError: false,
        isPending: false,
        view: baseView({ triggerReference: undefined }),
      }),
    ).toBe('partial-context');
    expect(
      resolveRunContextState({
        isError: false,
        isPending: false,
        view: baseView({ latestArtifactType: undefined }),
      }),
    ).toBe('partial-context');
  });

  it('default when every business field is present (branch placeholder does not flip it)', () => {
    expect(resolveRunContextState({ isError: false, isPending: false, view: baseView() })).toBe(
      'default',
    );
  });
});
