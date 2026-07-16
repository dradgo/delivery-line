/**
 * Story 4.24 (AC1, R6) — the LIVE governed failure-taxonomy registry query.
 *
 * GETs `/api/v1/registries/failure-taxonomy` (operationId `getFailureTaxonomyRegistry`) → the
 * generated `FailureTaxonomyRegistryResponse`, returning `TaxonomyValue[]` — one radio card per entry
 * in the classification dialog. Each entry carries `{ value, humanReadableName, description, examples,
 * deprecated, replacementValue? }`.
 *
 * GLOBAL + long-lived: the registry is not run-scoped and changes only under ADR-0035 governance, so
 * it is keyed under the dedicated `registryKeys.failureTaxonomy()` (NOT `workflowKeys.detail`) with a
 * long `staleTime` — the classify mutation must NOT invalidate it. Read-only + idempotent → no
 * Idempotency-Key.
 */
import { useQuery } from '@tanstack/react-query';

import { apiClient, unwrap } from '@/lib/api/client';
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import { STALE_TIME } from '@/lib/api/queryOptions';
import type { components } from '@/lib/api/schema';
import { registryKeys } from '@/lib/queryKeys/registryKeys';

export type TaxonomyValue = components['schemas']['TaxonomyValue'];

async function fetchFailureTaxonomy(): Promise<TaxonomyValue[]> {
  try {
    const response = unwrap(await apiClient.GET('/api/v1/registries/failure-taxonomy'));
    return response.values;
  } catch (error) {
    // Field-only structured log (no PII) — a stable code + transport flag only.
    console.warn('recovery.taxonomyLoadError', {
      code: isProblemDetailsError(error) ? error.code : 'UNKNOWN',
      transport: !isProblemDetailsError(error),
    });
    throw error;
  }
}

/** Read the governed failure-taxonomy registry (global, long-lived). */
export function useFailureTaxonomy(options?: { enabled?: boolean }) {
  return useQuery({
    queryKey: registryKeys.failureTaxonomy(),
    queryFn: fetchFailureTaxonomy,
    enabled: options?.enabled ?? true,
    // The registry rarely changes (ADR 0035) — hold it far longer than a run's detail.
    staleTime: STALE_TIME.registry,
  });
}
