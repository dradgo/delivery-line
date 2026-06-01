/**
 * Story 2.16 (AC1, AC7, AC9) — the `RunContextStrip` view-model seam.
 *
 * `toRunContextView` is the SINGLE pure mapper from the live backend read model
 * (`WorkflowDetail`, story 2.6) to the typed `RunContextView` the strip renders.
 * Centralizing the mapping here means the strip and the nav-rail `RunIdentityRegion`
 * cannot drift on the fields that must match the CLI `deliveryline status --format=json`
 * (AC7): the "last meaningful transition" is `lastEventAt` (NOT `lastActivityTimestamp`),
 * the actor is `currentActorIdentity` (+ `currentActorType`), exactly as
 * `RunIdentityRegion` reads them.
 *
 * Data reconciliation (live `WorkflowDetail` is authoritative; the epic prose
 * predates it):
 *   • `latestArtifactId` does NOT exist — `latestArtifacts[]` carries only
 *     `{ artifactType, status, version }`; the revision pointer is type + version.
 *   • `branchOrCommitReference` has NO backend field in Epic 2 — always `undefined`
 *     here (the strip renders a partial-context placeholder; Epic 3 GitHub linkage
 *     populates it).
 *   • `staleIndicator` has NO dedicated backend flag yet (story 1.13 AC7 heartbeat
 *     read-model has not surfaced one). E2 MVP DERIVES it client-side from
 *     `lastActivityTimestamp` age vs {@link RUN_STALE_THRESHOLD_MS} — isolated to
 *     this mapper (AC9 / OQ-3) so a future backend flag swaps in by changing ONE
 *     line, without touching the component.
 */
import type { WorkflowDetail } from '@/lib/api/queryOptions';

/**
 * Age (ms) past which a run with no fresh activity is treated as stale (OQ-3,
 * 10 minutes). This is the single stale-derivation seam — when the backend
 * surfaces a heartbeat flag (story 1.13 AC7), {@link isStale} swaps to read it.
 */
export const RUN_STALE_THRESHOLD_MS = 10 * 60 * 1000;

/** A single `latestArtifacts[]` entry, derived from the generated schema. */
type LatestArtifact = NonNullable<WorkflowDetail['latestArtifacts']>[number];

/**
 * The typed view model the `RunContextStrip` renders. Every field is independently
 * optional (each backend field is nullable); absent slots render a per-field
 * "Not reported" placeholder, never a collapsed/empty strip (AC3).
 */
export interface RunContextView {
  readonly runId: string | undefined;
  readonly currentState: string | undefined;
  readonly currentActorIdentity: string | undefined;
  readonly currentActorType: string | undefined;
  readonly latestArtifactType: string | undefined;
  readonly latestArtifactVersion: number | undefined;
  /** Last MEANINGFUL transition — `lastEventAt`, NOT `lastActivityTimestamp` (AC7). */
  readonly lastTransitionAt: string | undefined;
  readonly lastTransitionEventType: string | undefined;
  /** Linear ticket reference (`linkedTicket.externalRef`). */
  readonly triggerReference: string | undefined;
  /** Always `undefined` in Epic 2 — no backend field yet (populated in Epic 3). */
  readonly branchOrCommitReference: string | undefined;
  readonly escalationMarker: boolean;
  /** Derived client-side from `lastActivityAt` age (AC9 seam). */
  readonly staleIndicator: boolean;
  /** `lastActivityTimestamp` — drives the stale tooltip + `staleForMs` log. */
  readonly lastActivityAt: string | undefined;
}

/** Coalesce an optional/blank backend string to a trimmed value or `undefined`. */
function presentOrUndefined(value: string | undefined): string | undefined {
  return value !== undefined && value.trim() !== '' ? value : undefined;
}

/**
 * Selection rule (AC1): the latest revision pointer is the `latestArtifacts[]`
 * entry with the GREATEST numeric `version` (the most-revised artifact — e.g. a
 * spec re-submitted past a rejection advances to v2). Entries without a version
 * sort last; if no entry carries a version, fall back to the first so the artifact
 * TYPE can still render. Returns `undefined` for an empty/absent list.
 */
function selectLatestArtifact(artifacts: LatestArtifact[] | undefined): LatestArtifact | undefined {
  if (artifacts === undefined || artifacts.length === 0) {
    return undefined;
  }
  let best: LatestArtifact | undefined;
  for (const artifact of artifacts) {
    if (artifact.version === undefined) {
      continue;
    }
    if (best === undefined || (best.version ?? Number.NEGATIVE_INFINITY) < artifact.version) {
      best = artifact;
    }
  }
  return best ?? artifacts[0];
}

/** Whether the run is stale: activity older than {@link RUN_STALE_THRESHOLD_MS}. */
function isStale(lastActivityAt: string | undefined, now: number): boolean {
  if (lastActivityAt === undefined) {
    return false;
  }
  const ts = Date.parse(lastActivityAt);
  if (Number.isNaN(ts)) {
    return false;
  }
  return now - ts > RUN_STALE_THRESHOLD_MS;
}

/**
 * Pure mapper `WorkflowDetail → RunContextView`. `now` is injectable for
 * deterministic stale-boundary tests; production passes `Date.now()`.
 */
export function toRunContextView(detail: WorkflowDetail, now: number = Date.now()): RunContextView {
  const latest = selectLatestArtifact(detail.latestArtifacts);
  const lastActivityAt = presentOrUndefined(detail.lastActivityTimestamp);
  return {
    runId: presentOrUndefined(detail.workflowRunId),
    currentState: presentOrUndefined(detail.currentState),
    currentActorIdentity: presentOrUndefined(detail.currentActorIdentity),
    currentActorType: presentOrUndefined(detail.currentActorType),
    latestArtifactType: presentOrUndefined(latest?.artifactType),
    latestArtifactVersion: latest?.version,
    // AC7 — last transition is `lastEventAt`; `lastActivityTimestamp` feeds stale ONLY.
    lastTransitionAt: presentOrUndefined(detail.lastEventAt),
    lastTransitionEventType: presentOrUndefined(detail.lastEventType),
    triggerReference: presentOrUndefined(detail.linkedTicket?.externalRef),
    // AC1 reconciliation — no backend field in E2; always a placeholder (Epic 3 fills it).
    branchOrCommitReference: undefined,
    escalationMarker: detail.escalationMarker ?? false,
    staleIndicator: isStale(lastActivityAt, now),
    lastActivityAt,
  };
}

/** The single state the strip renders (mirrors `resolveQueueState`, story 2.20). */
export type RunContextState = 'error' | 'loading' | 'stale' | 'partial-context' | 'default';

export interface ResolveRunContextStateInput {
  readonly isError: boolean;
  readonly isPending: boolean;
  readonly view: RunContextView | undefined;
}

/**
 * Pure state precedence (AC3 / Task 3): error → loading → stale →
 * partial-context → default. Exactly one branch is reachable per call.
 *
 * `partial-context` fires when a meaningful business field (latest artifact or
 * trigger reference) is unreported. `branchOrCommitReference` is a KNOWN Epic-3
 * deferral (always `undefined` in E2) — it renders as a placeholder but does NOT
 * by itself flip the state, otherwise `default` would be unreachable.
 */
export function resolveRunContextState({
  isError,
  isPending,
  view,
}: ResolveRunContextStateInput): RunContextState {
  if (isError) {
    return 'error';
  }
  if (isPending || view === undefined) {
    return 'loading';
  }
  if (view.staleIndicator) {
    return 'stale';
  }
  if (
    view.latestArtifactType === undefined ||
    view.latestArtifactVersion === undefined ||
    view.triggerReference === undefined
  ) {
    return 'partial-context';
  }
  return 'default';
}
