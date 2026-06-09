/**
 * Story 2.23 (AC7, AC11) — `<ButtonGroup>`.
 *
 * A layout container that stamps `data-button-group` — the ancestor marker the
 * `single-primary-action` ESLint rule keys on (one literal `priority="primary"`
 * per group). Purely presentational; holds no state.
 */
import type { ReactNode } from 'react';

import { cn } from '@/lib/utils';

export interface ButtonGroupProps {
  children: ReactNode;
  /** Optional accessible label; when set the group is exposed as `role="group"`. */
  ariaLabel?: string | undefined;
  className?: string | undefined;
  testId?: string | undefined;
}

export function ButtonGroup({
  children,
  ariaLabel,
  className,
  testId = 'button-group',
}: ButtonGroupProps) {
  return (
    <div
      data-button-group=""
      data-testid={testId}
      role={ariaLabel !== undefined ? 'group' : undefined}
      aria-label={ariaLabel}
      className={cn('flex flex-wrap items-center gap-2', className)}
    >
      {children}
    </div>
  );
}
