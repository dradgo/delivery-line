/**
 * Story 4.2 (AC3/AC5) — the operator-queue data hook. A thin `useInfiniteQuery` wrapper over
 * `operatorRunsInfiniteQueryOptions` (mirrors `useWorkflowsList` over `listQueryOptions`), plus a
 * flattened `rows` view and the page-1 aggregate the UI keeps stable across pages.
 */
import { useInfiniteQuery } from '@tanstack/react-query';

import { operatorRunsInfiniteQueryOptions } from '@/lib/api/queryOptions';
import { EMPTY_OPERATOR_FILTERS, type OperatorQueueFilters } from '@/lib/queryKeys/operatorKeys';

import type { OperatorRunRowResponse } from '../operatorQueueRow';

export function useOperatorRunsList(filters: OperatorQueueFilters = EMPTY_OPERATOR_FILTERS) {
  const query = useInfiniteQuery(operatorRunsInfiniteQueryOptions(filters));
  const pages = query.data?.pages ?? [];
  // Flatten every fetched page's runs; the aggregate (total + histograms) comes from page 1 and is
  // stable across pages (the backend computes it over the full match set, cursor-independent).
  const rows: OperatorRunRowResponse[] = pages.flatMap((page) => page.runs ?? []);
  const aggregate = pages[0];
  return { query, rows, aggregate };
}
