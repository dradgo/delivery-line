/**
 * Story 2.23 (AC7, AC11) — `<DecisionArea>`.
 *
 * A decision-area container that stamps `data-decision-area` (the second
 * ancestor marker the `single-primary-action` ESLint rule keys on) AND provides
 * the structural "primary never collapses" layout (UX-DR19 mobile rule):
 *   · `primary` renders in the always-visible region (`data-decision-primary`) —
 *     structurally EXCLUDED from collapse.
 *   · `secondary` (review / inspect / compare) renders in an overflow region
 *     (`data-decision-overflow`) eligible to move into an overflow affordance on
 *     narrow breakpoints (the breakpoint matrix itself is deferred to 2.26).
 *
 * Purely presentational; holds no state.
 */
import type { ReactNode } from 'react';

import { cn } from '@/lib/utils';

export interface DecisionAreaProps {
  /** The always-visible primary governed action — never collapses (AC11). */
  primary: ReactNode;
  /** Secondary / tertiary actions eligible for the overflow slot on narrow screens. */
  secondary?: ReactNode | undefined;
  /** Optional accessible label; when set the area is exposed as `role="group"`. */
  ariaLabel?: string | undefined;
  className?: string | undefined;
  testId?: string | undefined;
}

export function DecisionArea({
  primary,
  secondary,
  ariaLabel,
  className,
  testId = 'decision-area',
}: DecisionAreaProps) {
  return (
    <div
      data-decision-area=""
      data-testid={testId}
      role={ariaLabel !== undefined ? 'group' : undefined}
      aria-label={ariaLabel}
      className={cn('flex flex-wrap items-center gap-2', className)}
    >
      {/* Always-visible region — the primary is structurally excluded from collapse. */}
      <div data-decision-primary="" className="flex items-center gap-2">
        {primary}
      </div>
      {secondary !== undefined ? (
        // Overflow-eligible region — secondary/tertiary may collapse here (2.26).
        <div data-decision-overflow="" className="flex flex-wrap items-center gap-2">
          {secondary}
        </div>
      ) : null}
    </div>
  );
}
