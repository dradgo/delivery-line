/**
 * Story 2.21 (AC1, AC3, AC8) — the generic action-lifecycle helper.
 *
 * Generalizes 2.18's region-local `clarificationView.ts:resolveLifecyclePosition`
 * (which hard-coded the `submitted → accepted → incorporated` chain and a fixed
 * status→index switch) into a shape-agnostic helper over an arbitrary ordered
 * `stages` list. `<ActionLifecycleIndicator>` drives its rendering from this.
 *
 * T-RECEIVED-NE-INCORPORATED (AC8): the canonical chain keeps `submitted` and
 * `incorporated` as DISTINCT stages — the helper never merges them, and the
 * indicator renders each as a separate node.
 *
 * Pure `.ts` (T-REFRESH) — no React, importable by tests/tooling.
 */

/**
 * The canonical clarification/decision lifecycle chain (UX-DR11 / story 2.12).
 * `submitted` ("answer received") and `incorporated` ("answer applied to the
 * workflow") are deliberately separate stages — UX-DR15 forbids collapsing them.
 */
export const DEFAULT_LIFECYCLE_STAGES = ['submitted', 'accepted', 'incorporated'] as const;

/** The resolved position of a `currentStage` within an ordered `stages` chain. */
export interface LifecyclePosition {
  /** The ordered stage chain, echoed back for the renderer. */
  readonly stages: readonly string[];
  /**
   * The index of `currentStage` within `stages`, or `-1` when `currentStage` is
   * NOT on the chain (an off-chain terminal such as `failed`/`superseded`, or an
   * empty/unstarted value).
   */
  readonly currentIndex: number;
  /**
   * `true` when `currentStage` is a non-empty value that does not appear in
   * `stages` — i.e. the action left the happy chain (`failed`/`superseded`). The
   * indicator marks this distinctly rather than showing a green "incorporated".
   */
  readonly isOffChain: boolean;
}

/**
 * Resolve where `currentStage` sits within an ordered `stages` chain.
 *
 * Branches:
 *  - `currentStage` is in `stages` → `{ currentIndex: idx, isOffChain: false }`.
 *  - `currentStage` is a non-empty value NOT in `stages` (e.g. `failed`,
 *    `superseded`) → `{ currentIndex: -1, isOffChain: true }`.
 *  - `currentStage` is empty (`''`) → `{ currentIndex: -1, isOffChain: false }`
 *    (not started; not an off-chain terminal).
 */
export function resolveLifecyclePosition(
  stages: readonly string[],
  currentStage: string,
): LifecyclePosition {
  const seen = new Set<string>();
  for (const stage of stages) {
    if (seen.has(stage)) {
      throw new Error(`Lifecycle stages must be unique: ${stage}`);
    }
    seen.add(stage);
  }

  const idx = stages.indexOf(currentStage);
  if (idx !== -1) {
    return { stages, currentIndex: idx, isOffChain: false };
  }
  return { stages, currentIndex: -1, isOffChain: currentStage !== '' };
}

/** Per-stage render state, exposed as `data-stage-state` for state-detection tests. */
export type LifecycleStageState = 'done' | 'current' | 'pending' | 'offchain';

/** Resolve a stage's render state from its index relative to the current position. */
export function lifecycleStageState(
  stageIndex: number,
  position: LifecyclePosition,
): LifecycleStageState {
  if (position.currentIndex === -1) {
    // Not started OR off-chain: no on-chain stage is "current"; all stay pending.
    return 'pending';
  }
  if (stageIndex < position.currentIndex) {
    return 'done';
  }
  if (stageIndex === position.currentIndex) {
    return 'current';
  }
  return 'pending';
}
