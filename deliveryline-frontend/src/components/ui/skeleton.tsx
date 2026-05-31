/**
 * Story 2.20 (AC2) — generic skeleton placeholder primitive.
 *
 * A STABLE, non-spinning shimmer (`animate-pulse`, NEVER `animate-spin`) used to
 * reserve layout while content loads — the antidote to a spinner-on-blank-page.
 * Lives under `src/components/ui/**`, a `no-untyped-loading-state`-exempt boundary,
 * so the shimmer utility is allowed here while forbidden in feature code.
 *
 * Generic by design (no workflow-domain coupling — `no-workflow-domain-in-ui-primitives`):
 * callers size it via `className` / `style`. `aria-hidden` because a skeleton is a
 * visual affordance only — the surrounding `aria-live` region carries the state to
 * assistive tech (story 2.20 AC10).
 */
import type { HTMLAttributes } from 'react';

import { cn } from '@/lib/utils';

export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      aria-hidden
      className={cn('animate-pulse rounded-md bg-surface-elevated', className)}
      {...props}
    />
  );
}
