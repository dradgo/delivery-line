/**
 * Story 2.22 (AC3.c) — renders nothing; runs `useBreadcrumbAutoTrack` from INSIDE
 * the router (mounted at the top of `__root.tsx`'s `RootLayout`) so the auto-track
 * hook always runs while the provider stays OUTSIDE the router (Trap T3).
 */
import { useBreadcrumbAutoTrack } from './useBreadcrumbAutoTrack';

export function NavigationBreadcrumbTracker() {
  useBreadcrumbAutoTrack();
  return null;
}
