/**
 * Story 2.22 (AC4.d) — feedback state-primitive barrel.
 */
export { EmptyState, assertNeverEmptyVariant, type EmptyVariant, type EmptyStateProps } from './EmptyState';
export { LoadingState, type LoadingVariant, type LoadingStateProps } from './LoadingState';
export { ErrorState, type ErrorVariant, type ErrorStateProps } from './ErrorState';
export type { NextAction } from '@/lib/navigation/types';
