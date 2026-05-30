/**
 * Story 2.22 (AC3) — typed accessor for the breadcrumb-stack context.
 *
 * Throws when used outside `<NavigationBreadcrumbProvider>` so a consumer
 * mounted off-provider fails loudly instead of silently no-op-ing — mirrors the
 * story-2.7 `useAppShellContext` contract.
 */
import { useContext } from 'react';

import {
  NavigationBreadcrumbContext,
  type NavigationBreadcrumbContextValue,
} from './NavigationBreadcrumbContext';

export function useNavigationBreadcrumb(): NavigationBreadcrumbContextValue {
  const value = useContext(NavigationBreadcrumbContext);
  if (value === null) {
    throw new Error('useNavigationBreadcrumb must be used within <NavigationBreadcrumbProvider>.');
  }
  return value;
}
