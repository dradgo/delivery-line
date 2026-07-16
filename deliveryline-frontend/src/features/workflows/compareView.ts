/**
 * Story 4.20 (AC4, AC6, AC10) — the frontend-owned Compare-Mode read model.
 *
 * The story-4.19 `RevisionDelta` wire shape is DELIBERATELY loose — nearly every field is
 * optional/nullable and the `RevisionDeltaChange` block is a single FLATTENED polymorphic record
 * with a `blockType` discriminator (`markdown | planStep | file`), not a `oneOf`. Rendering that
 * shape directly would litter the composite with `?.`/`!= null` guards and risk a partial wire
 * shape crashing a renderer. So — mirroring the `isArtifactView`/normalize discipline in
 * `artifactView.ts` — `normalizeRevisionDelta` narrows the loose shape into a STRICT discriminated
 * union (`ChangeBlockView`) BEFORE anything renders, skipping malformed/unknown blocks.
 *
 * `resolveCompareState` is the pure state-precedence resolver (mirror `resolveArtifactPanelState`):
 * exactly one `CompareState` is reachable per render, exposed as `data-compare-state`. The error
 * branch is sub-classified by the LIVE story-4.19 ProblemDetails codes (404 → no baseline,
 * 503 → partial, 400/transport → unavailable) — never by human error text.
 *
 * Pure TypeScript (no JSX/React) so it lives in a `.ts` sibling per
 * `frontend-react-refresh-no-fn-exports` and is unit-tested independently of any renderer.
 */
import { isProblemDetailsError } from '@/lib/api/problemDetails';

import type { RevisionDelta } from './hooks/useRevisionDelta';

/** The three artifact-lineage types a compare can span (the runner-contracts schema-v1 values). */
export type CompareArtifactType = 'spec' | 'implementationPlan' | 'prOutput';

/**
 * The closed set of change kinds (story 4.19). `reordered` is planStep-only (same text, different
 * index). An unrecognized wire value degrades to `modified` (the safest visible signifier) rather
 * than crashing the renderer.
 */
export type ChangeKind = 'added' | 'removed' | 'modified' | 'reordered';

/** A spec section change (`blockType: 'markdown'`) — rendered via `SafeMarkdownRenderer`. */
export interface MarkdownChangeBlockView {
  readonly kind: 'markdown';
  readonly changeKind: ChangeKind;
  /** Heading trail (structural alignment key, redaction-exempt per 4.19 Reconciliation 11). */
  readonly sectionPath: string | null;
  readonly priorText: string | null;
  readonly currentText: string | null;
}

/** A plan-step change (`blockType: 'planStep'`) — rendered as React-escaped plain text. */
export interface PlanStepChangeBlockView {
  readonly kind: 'planStep';
  readonly changeKind: ChangeKind;
  readonly stepId: string | null;
  readonly priorStepText: string | null;
  readonly currentStepText: string | null;
  readonly priorStepOrder: number | null;
  readonly currentStepOrder: number | null;
}

/** A file-level change (`blockType: 'file'`) — path + counts; full diff lazy-loaded on expand. */
export interface FileChangeBlockView {
  readonly kind: 'file';
  readonly changeKind: ChangeKind;
  readonly filePath: string | null;
  readonly addedLines: number | null;
  readonly removedLines: number | null;
}

/** The strict discriminated union the per-block renderers dispatch on (by `kind`). */
export type ChangeBlockView =
  | MarkdownChangeBlockView
  | PlanStepChangeBlockView
  | FileChangeBlockView;

/** A/B revision identifiers (from `ArtifactRevisionSummary`) — all nullable on the wire. */
export interface CompareRevisionSummary {
  readonly version: number | null;
  readonly producedByActor: string | null;
  readonly createdAt: string | null;
  readonly checksum: string | null;
}

/** Aggregate counts (from `RevisionDeltaSummary`) — absent counts default to 0. */
export interface CompareSummaryCounts {
  readonly changedRegionCount: number;
  readonly addedCount: number;
  readonly removedCount: number;
  readonly modifiedCount: number;
}

/** The strict, render-ready model narrowed from the loose `RevisionDelta` wire shape. */
export interface CompareView {
  readonly artifactType: CompareArtifactType;
  readonly revisionA: CompareRevisionSummary;
  readonly revisionB: CompareRevisionSummary;
  readonly summary: CompareSummaryCounts;
  readonly noMeaningfulDiff: boolean;
  readonly blocks: readonly ChangeBlockView[];
  /** prOutput only: `[artifactIdA, artifactIdB]` for the lazy full-diff read; null otherwise. */
  readonly linkedDiffReferences: readonly string[] | null;
}

/** The layout a compare surface uses — driven by `artifactType`, NOT `blockType` (AC4). */
export type CompareLayout = 'side-by-side' | 'stacked';

/** spec + implementationPlan → side-by-side (synced scroll); prOutput → stacked (file accordions). */
export function compareLayout(artifactType: CompareArtifactType): CompareLayout {
  return artifactType === 'prOutput' ? 'stacked' : 'side-by-side';
}

function narrowArtifactType(raw: string | undefined): CompareArtifactType {
  if (raw === 'implementationPlan' || raw === 'prOutput') {
    return raw;
  }
  // Default unknown/absent to `spec` (the side-by-side default). The 4.19 endpoint only ever emits
  // the three known values; this is a defensive floor, not a live path.
  return 'spec';
}

function narrowChangeKind(raw: string | null | undefined): ChangeKind {
  switch (raw) {
    case 'added':
    case 'removed':
    case 'modified':
    case 'reordered':
      return raw;
    default:
      return 'modified';
  }
}

/** A finite number or `null` (a nullable wire number that is NaN/absent degrades to null). */
function nullableNumber(value: number | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** A non-null count with an absent/NaN default of 0. */
function countOr0(value: number | null | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function nullableString(value: string | null | undefined): string | null {
  return typeof value === 'string' ? value : null;
}

function toRevisionSummary(
  raw: NonNullable<RevisionDelta['revisionA']> | undefined,
): CompareRevisionSummary {
  return {
    version: nullableNumber(raw?.version),
    producedByActor: nullableString(raw?.producedByActor),
    createdAt: nullableString(raw?.createdAt),
    checksum: nullableString(raw?.checksum),
  };
}

/**
 * Narrow ONE loose wire change into a strict `ChangeBlockView`, or `null` when the block is
 * malformed / carries an unrecognized `blockType` (skip it — never trust a partial wire shape).
 */
function toChangeBlockView(
  raw: NonNullable<RevisionDelta['changes']>[number],
): ChangeBlockView | null {
  const changeKind = narrowChangeKind(raw.changeKind);
  switch (raw.blockType) {
    case 'markdown':
      return {
        kind: 'markdown',
        changeKind,
        sectionPath: nullableString(raw.sectionPath),
        priorText: nullableString(raw.priorText),
        currentText: nullableString(raw.currentText),
      };
    case 'planStep':
      return {
        kind: 'planStep',
        changeKind,
        stepId: nullableString(raw.stepId),
        priorStepText: nullableString(raw.priorStepText),
        currentStepText: nullableString(raw.currentStepText),
        priorStepOrder: nullableNumber(raw.priorStepOrder),
        currentStepOrder: nullableNumber(raw.currentStepOrder),
      };
    case 'file':
      return {
        kind: 'file',
        changeKind,
        filePath: nullableString(raw.filePath),
        addedLines: nullableNumber(raw.addedLines),
        removedLines: nullableNumber(raw.removedLines),
      };
    default:
      // Unknown/absent blockType (a future build's variant) — skip, never crash (mirror the
      // `isArtifactView` unknown-discriminant discipline).
      return null;
  }
}

/** Narrow the loose `RevisionDelta` wire shape into the strict, render-ready `CompareView`. */
export function normalizeRevisionDelta(raw: RevisionDelta): CompareView {
  const rawChanges = Array.isArray(raw.changes) ? raw.changes : [];
  const blocks = rawChanges
    .map(toChangeBlockView)
    .filter((block): block is ChangeBlockView => block !== null);
  const summary = raw.summary;
  return {
    artifactType: narrowArtifactType(raw.artifactType),
    revisionA: toRevisionSummary(raw.revisionA),
    revisionB: toRevisionSummary(raw.revisionB),
    summary: {
      changedRegionCount: countOr0(summary?.changedRegionCount),
      addedCount: countOr0(summary?.addedCount),
      removedCount: countOr0(summary?.removedCount),
      modifiedCount: countOr0(summary?.modifiedCount),
    },
    noMeaningfulDiff: raw.noMeaningfulDiff === true,
    blocks,
    linkedDiffReferences: Array.isArray(raw.linkedDiffReferences) ? raw.linkedDiffReferences : null,
  };
}

/**
 * Story 4.20 (AC3) — the single state the `CompareMode` composite renders. Exactly one branch is
 * reachable per resolve, exposed as `data-compare-state` (mirror `data-artifact-panel-state`). The
 * three error sub-states map the LIVE story-4.19 ProblemDetails codes.
 */
export type CompareState =
  | 'loading'
  | 'no-meaningful-diff'
  | 'default'
  | 'no-baseline'
  | 'partial'
  | 'unavailable';

export interface ResolveCompareStateInput {
  /**
   * Whether a baseline (prior-version) artifact id is resolvable (OQ-2). When false the compare
   * cannot run — the query is disabled — so the surface shows "no baseline available" up front
   * rather than a spinner that never resolves.
   */
  readonly hasBaseline: boolean;
  readonly isError: boolean;
  readonly isLoading: boolean;
  readonly delta: RevisionDelta | undefined;
  readonly error: unknown;
}

/**
 * Sub-classify an error into the compare error sub-state by its LIVE 4.19 ProblemDetails code:
 * 404 `ARTIFACT_RECORD_NOT_FOUND` → no baseline; 503 `ARTIFACT_PAYLOAD_UNAVAILABLE` → partial
 * (retryable); anything else (400 `ARTIFACT_LINEAGE_MISMATCH` / `INVALID_ID_PREFIX` / transport)
 * → unavailable. Branches on the stable `code`/`status`, never human text.
 */
function classifyCompareError(error: unknown): 'no-baseline' | 'partial' | 'unavailable' {
  if (isProblemDetailsError(error)) {
    if (error.status === 404 || error.code === 'ARTIFACT_RECORD_NOT_FOUND') {
      return 'no-baseline';
    }
    if (error.status === 503 || error.code === 'ARTIFACT_PAYLOAD_UNAVAILABLE') {
      return 'partial';
    }
  }
  return 'unavailable';
}

/**
 * Pure state precedence (AC3): no-baseline (unresolvable A) → error (sub-classified) → loading →
 * no-meaningful-diff → default. The `hasBaseline` gate comes first because without a prior-version
 * id the query never fires (OQ-2), so `isLoading`/`delta` would otherwise leave it stuck.
 */
export function resolveCompareState({
  hasBaseline,
  isError,
  isLoading,
  delta,
  error,
}: ResolveCompareStateInput): CompareState {
  if (!hasBaseline) {
    return 'no-baseline';
  }
  if (isError) {
    return classifyCompareError(error);
  }
  if (isLoading) {
    return 'loading';
  }
  if (delta === undefined) {
    // Enabled but not yet resolved (e.g. idle between mount and first fetch) — treat as loading.
    return 'loading';
  }
  if (delta.noMeaningfulDiff === true) {
    return 'no-meaningful-diff';
  }
  return 'default';
}
