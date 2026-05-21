import * as React from 'react';

import { cn } from '@/lib/utils';

import { GAP_CLASS, type GapToken } from './gap';

/**
 * Story 2.4 — `Grid`: a simple CSS grid with a closed `cols` count and a
 * token-scale `gap` (AC5). Literal class lookups keep Tailwind purge-safe.
 * Generic, no domain imports.
 */
type GridCols = 1 | 2 | 3 | 4 | 5 | 6 | 12;

const COLS_CLASS: Record<GridCols, string> = {
  1: 'grid-cols-1',
  2: 'grid-cols-2',
  3: 'grid-cols-3',
  4: 'grid-cols-4',
  5: 'grid-cols-5',
  6: 'grid-cols-6',
  12: 'grid-cols-12',
};

export interface GridProps extends React.HTMLAttributes<HTMLDivElement> {
  /** Number of equal columns. Defaults to 1. */
  cols?: GridCols;
  /** Gap between cells, from the closed 4px/8px token scale. */
  gap?: GapToken;
}

const Grid = React.forwardRef<HTMLDivElement, GridProps>(
  ({ className, cols = 1, gap = '4', ...props }, ref) => (
    <div ref={ref} className={cn('grid', COLS_CLASS[cols], GAP_CLASS[gap], className)} {...props} />
  ),
);
Grid.displayName = 'Grid';

export { Grid };
