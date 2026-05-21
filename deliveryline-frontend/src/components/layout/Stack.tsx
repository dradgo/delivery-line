import * as React from 'react';

import { cn } from '@/lib/utils';

import { GAP_CLASS, type GapToken } from './gap';

/**
 * Story 2.4 — `Stack`: vertical flex with a token-scale `gap` (AC5). App-level
 * layout helper (generic, no domain imports). Matches the shadcn primitive
 * ergonomics (`forwardRef` + `cn()` + `...props`) so composites can ref/extend.
 */
export interface StackProps extends React.HTMLAttributes<HTMLDivElement> {
  /** Vertical gap between children, from the closed 4px/8px token scale. */
  gap?: GapToken;
}

const Stack = React.forwardRef<HTMLDivElement, StackProps>(
  ({ className, gap = '4', ...props }, ref) => (
    <div ref={ref} className={cn('flex flex-col', GAP_CLASS[gap], className)} {...props} />
  ),
);
Stack.displayName = 'Stack';

export { Stack };
