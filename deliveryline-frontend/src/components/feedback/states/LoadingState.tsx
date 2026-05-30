/**
 * Story 2.22 (AC4.b, AC9.a) — `<LoadingState>`.
 *
 * `variant` is REQUIRED and enforces the 4-meaning split (fetching / generating /
 * rebuilding / retrying — UX-DR17). Renders `<output role="status"
 * aria-live="polite">` so screen readers announce when loading materially
 * affects interaction (AC9.a). The inner spinner uses `Loader2 + animate-spin`;
 * the `no-untyped-loading-state` ESLint rule self-exempts this directory
 * (Trap T16), so this is the ONE sanctioned place a raw spinner lives.
 *
 * Sized to fit its parent (AC8.a — no fixed/absolute positioning).
 */
import type { ReactNode } from 'react';
import { Loader2 } from 'lucide-react';

import { cn } from '@/lib/utils';

export type LoadingVariant =
  | 'fetchingData'
  | 'generatingArtifact'
  | 'rebuildingAfterRejection'
  | 'retryingRecovery';

export interface LoadingStateProps {
  variant: LoadingVariant;
  /** Overrides the per-variant default label. */
  message?: ReactNode;
  className?: string;
}

const LOADING_DEFAULTS: Record<LoadingVariant, string> = {
  fetchingData: 'Loading workflow data…',
  generatingArtifact: 'Generating artifact…',
  rebuildingAfterRejection: 'Rebuilding after rejection…',
  retryingRecovery: 'Retrying recovery…',
};

export function LoadingState({ variant, message, className }: LoadingStateProps) {
  return (
    <output
      aria-live="polite"
      className={cn('flex items-center gap-2 text-meta', className)}
      data-testid="loading-state"
      data-variant={variant}
    >
      <Loader2 className="size-4 shrink-0 animate-spin text-state-loading-foreground" aria-hidden />
      <span>{message ?? LOADING_DEFAULTS[variant]}</span>
    </output>
  );
}
