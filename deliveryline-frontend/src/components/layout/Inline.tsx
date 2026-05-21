import * as React from 'react';

import { cn } from '@/lib/utils';

import { GAP_CLASS, type GapToken } from './gap';

/**
 * Story 2.4 — `Inline`: horizontal flex with a token-scale `gap` plus optional
 * `wrap` / `align` / `justify` (AC5). Defaults to `items-center` (the common
 * label-+-control / icon-+-text alignment). Literal class lookups keep Tailwind
 * purge-safe. Generic, no domain imports.
 */
type Align = 'start' | 'center' | 'end' | 'baseline' | 'stretch';
type Justify = 'start' | 'center' | 'end' | 'between' | 'around';

const ALIGN_CLASS: Record<Align, string> = {
  start: 'items-start',
  center: 'items-center',
  end: 'items-end',
  baseline: 'items-baseline',
  stretch: 'items-stretch',
};

const JUSTIFY_CLASS: Record<Justify, string> = {
  start: 'justify-start',
  center: 'justify-center',
  end: 'justify-end',
  between: 'justify-between',
  around: 'justify-around',
};

export interface InlineProps extends React.HTMLAttributes<HTMLDivElement> {
  /** Horizontal gap between children, from the closed 4px/8px token scale. */
  gap?: GapToken;
  /** Allow children to wrap onto multiple lines. */
  wrap?: boolean;
  /** Cross-axis alignment (`items-*`). Defaults to `center`. */
  align?: Align;
  /** Main-axis distribution (`justify-*`). Defaults to `start`. */
  justify?: Justify;
}

const Inline = React.forwardRef<HTMLDivElement, InlineProps>(
  ({ className, gap = '2', wrap = false, align = 'center', justify = 'start', ...props }, ref) => (
    <div
      ref={ref}
      className={cn(
        'flex flex-row',
        wrap && 'flex-wrap',
        GAP_CLASS[gap],
        ALIGN_CLASS[align],
        JUSTIFY_CLASS[justify],
        className,
      )}
      {...props}
    />
  ),
);
Inline.displayName = 'Inline';

export { Inline };
