/**
 * Story 4.24 review (D2b/D3) — the per-row classification container for the operator queue.
 *
 * `RunReviewQueueItem` is PURE-PRESENTATIONAL (holds no query/server state), so this thin container
 * owns the row-scoped `useFailureClassification` read and passes the derived props down. It is
 * rendered once per virtualized row, so the query fan-out is bounded to the mounted rows (~10-20),
 * and each classification is cached under `detail(id)` (the classify mutation refreshes it for free).
 *
 * The read is gated to Failed rows only (`enabled`) — a non-failed operator row never fetches. From
 * the result it derives the two AC surfaces:
 *  • AC8a — Classify is offered ONLY on a not-yet-classified Failed row (`onClassify` is passed only
 *    then; a classified row shows the chip instead of the action). While the read is loading/errored
 *    `currentTaxonomyValue` is undefined → treated as not-yet-classified, so the action still appears
 *    (never hidden by a slow/failed read).
 *  • AC9 — the applied classification's human name is passed as `classificationLabel` for the row's
 *    attention-slot chip, resolved through the registry (never the raw snake_case wire value).
 */
import { useFailureClassification } from '../hooks/useFailureClassification';
import { useFailureTaxonomy } from '../hooks/useFailureTaxonomy';
import type { RunQueueRow } from '../runQueueRow';
import { humanNameForTaxonomy } from './failureClassificationDialogView';
import { RunReviewQueueItem } from './RunReviewQueueItem';

export interface OperatorClassifiedRowProps {
  run: RunQueueRow;
  /** Opens the lifted classify dialog for the row's run id (AC8a). */
  onClassify: (runId: string) => void;
}

export function OperatorClassifiedRow({ run, onClassify }: OperatorClassifiedRowProps) {
  const isFailed = run.currentState === 'Failed';
  const gateEnabled = isFailed && run.runId != null;
  const classificationQuery = useFailureClassification(run.runId ?? '', { enabled: gateEnabled });
  const taxonomyQuery = useFailureTaxonomy({ enabled: gateEnabled });

  const currentValue = classificationQuery.data?.currentTaxonomyValue ?? undefined;
  const notYetClassified = currentValue == null;
  const classificationLabel = humanNameForTaxonomy(taxonomyQuery.data, currentValue);

  return (
    <RunReviewQueueItem
      run={run}
      variant="operator"
      // AC8a — Classify only on a not-yet-classified Failed row; a classified row shows the chip.
      onClassify={isFailed && notYetClassified ? onClassify : undefined}
      classificationLabel={classificationLabel}
    />
  );
}
