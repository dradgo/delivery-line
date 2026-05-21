import * as React from 'react';

import { cn } from '@/lib/utils';

/**
 * Story 2.4 — `Container`: centered max-width wrapper with responsive horizontal
 * padding (AC5). Bounds long-form content to a comfortable measure; the tri-pane
 * shell (story 2.7) composes it. Generic, no domain imports.
 */
export type ContainerProps = React.HTMLAttributes<HTMLDivElement>;

const Container = React.forwardRef<HTMLDivElement, ContainerProps>(
  ({ className, ...props }, ref) => (
    <div
      ref={ref}
      className={cn('mx-auto w-full max-w-screen-xl px-4 sm:px-6 lg:px-8', className)}
      {...props}
    />
  ),
);
Container.displayName = 'Container';

export { Container };
