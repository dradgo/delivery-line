/**
 * Story 2.21 (AC1, AC3, AC8) — `<ActionLifecycleIndicator>`.
 *
 * Renders an ordered lifecycle chain (default `submitted → accepted →
 * incorporated`) with the current stage highlighted, generalizing 2.18's
 * region-local per-question indicator. Each stage is a DISTINCT node carrying
 * `data-stage={name}` + `data-stage-state="done|current|pending"`; an off-chain
 * terminal (`failed`/`superseded`) renders as an extra distinctly-marked node
 * (`data-stage-state="offchain"`).
 *
 * AC8 / T-RECEIVED-NE-INCORPORATED: the canonical default chain always renders
 * `submitted` and `incorporated` as separate `<li>` nodes. Generic custom chains
 * remain supported by AC1; duplicate names are rejected because they cannot
 * express an unambiguous current position.
 *
 * AC6 (non-color signifier): each stage pairs a step icon (done → check,
 * current → dot, pending → hollow circle, off-chain → error octagon) with its
 * text label — never color alone.
 */
import { Check, Circle, CircleDot, OctagonX } from 'lucide-react';

import { cn } from '@/lib/utils';

import {
  DEFAULT_LIFECYCLE_STAGES,
  lifecycleStageState,
  resolveLifecyclePosition,
  type LifecycleStageState,
} from './actionLifecycle';

export interface ActionLifecycleIndicatorProps {
  /** The ordered lifecycle chain. Names must be unique. */
  stages?: readonly string[] | undefined;
  /**
   * The stage currently reached. A value NOT in `stages` (e.g. `failed`) renders
   * as an off-chain terminal node; `''` reads as not-yet-started.
   */
  currentStage: string;
  /** Optional accessible label override (defaults to a description of progress). */
  ariaLabel?: string | undefined;
  className?: string | undefined;
  testId?: string | undefined;
}

const STEP_ICON: Record<LifecycleStageState, typeof Check> = {
  done: Check,
  current: CircleDot,
  pending: Circle,
  offchain: OctagonX,
};

function StageNode({ name, state }: { name: string; state: LifecycleStageState }) {
  const Icon = STEP_ICON[state];
  const isOffChain = state === 'offchain';
  return (
    <li
      data-stage={name}
      data-stage-state={state}
      className={cn(
        'inline-flex items-center gap-1.5 text-xs font-medium',
        state === 'pending' && 'text-text-tertiary',
        state === 'current' && 'text-text-primary',
        state === 'done' && 'text-state-success-foreground',
        isOffChain && 'text-state-error-foreground',
      )}
    >
      <Icon className="size-3.5 shrink-0" aria-hidden />
      <span>{name}</span>
    </li>
  );
}

export function ActionLifecycleIndicator({
  stages = DEFAULT_LIFECYCLE_STAGES,
  currentStage,
  ariaLabel,
  className,
  testId = 'action-lifecycle-indicator',
}: ActionLifecycleIndicatorProps) {
  const position = resolveLifecyclePosition(stages, currentStage);

  return (
    <ol
      data-testid={testId}
      data-current-stage={currentStage}
      data-off-chain={position.isOffChain ? 'true' : 'false'}
      aria-label={ariaLabel ?? `Lifecycle progress: ${currentStage || 'not started'}`}
      className={cn('flex flex-wrap items-center gap-x-3 gap-y-1', className)}
    >
      {stages.map((name, index) => (
        <StageNode key={name} name={name} state={lifecycleStageState(index, position)} />
      ))}
      {position.isOffChain ? <StageNode name={currentStage} state="offchain" /> : null}
    </ol>
  );
}
