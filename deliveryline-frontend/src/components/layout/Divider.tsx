import * as React from 'react';

import { cn } from '@/lib/utils';

/**
 * Story 2.4 — `Divider`: a semantic section break (AC5) painted with the 2.3
 * `--border` token. Exposes `role="separator"` + `aria-orientation` so it reads
 * correctly to assistive tech in both orientations. Generic, no domain imports.
 */
export interface DividerProps extends React.HTMLAttributes<HTMLDivElement> {
  /** Visual + ARIA orientation. Defaults to `horizontal`. */
  orientation?: 'horizontal' | 'vertical';
}

const Divider = React.forwardRef<HTMLDivElement, DividerProps>(
  ({ className, orientation = 'horizontal', ...props }, ref) => (
    <div
      ref={ref}
      role="separator"
      aria-orientation={orientation}
      className={cn(
        'shrink-0 bg-border',
        orientation === 'horizontal' ? 'h-px w-full' : 'w-px self-stretch',
        className,
      )}
      {...props}
    />
  ),
);
Divider.displayName = 'Divider';

export { Divider };
