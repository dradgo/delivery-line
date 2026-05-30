/**
 * Story 2.22 (AC8.b) — error boundary that routes any thrown render error to the
 * catastrophic overlay. Wraps the `<RouterProvider>` in `App.tsx`. The fallback
 * renders nothing — the crashed subtree is cleared and the sibling
 * `<CatastrophicErrorOverlay>` (mounted OUTSIDE this boundary) surfaces the error
 * (Trap T11). Uses `react-error-boundary` (OQ-3).
 *
 * `resetKeys={[resetToken]}` ties the boundary's reset to the overlay's Dismiss:
 * `dismiss()` bumps `resetToken`, which re-mounts the crashed subtree (retry
 * render). Without it the boundary would stay latched and Dismiss would leave a
 * blank app. A deterministic error simply re-throws and re-raises the overlay.
 */
import type { ReactNode } from 'react';
import { ErrorBoundary } from 'react-error-boundary';

import { useCatastrophicError } from './useCatastrophicError';

export function CatastrophicErrorBoundary({ children }: { children: ReactNode }) {
  const { signalCatastrophic, resetToken } = useCatastrophicError();
  return (
    <ErrorBoundary
      fallbackRender={() => null}
      onError={(error) => signalCatastrophic(error)}
      resetKeys={[resetToken]}
    >
      {children}
    </ErrorBoundary>
  );
}
