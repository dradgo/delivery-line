/**
 * Story 3f-3 (AC8) — the Run Dependency panel.
 *
 * Renders this run's position in the run-dependency DAG (read directly from the embedded
 * `WorkflowDetail.dependencies` read model — no extra fetch): the prerequisites it waits on, the
 * dependents waiting on it, and an explicit BLOCKED signifier listing the unfinished prerequisites.
 * Each referenced run shows its current state via the shared {@link WorkflowStateBadge}, so a run
 * parked in `WaitingForDependencies` reads as an explicit blocked state rather than an unknown one.
 *
 * Renders nothing when the run has neither prerequisites nor dependents (the common case), keeping
 * the detail view calm for ordinary single runs.
 */
import type { components } from '@/lib/api/schema';

import { WorkflowStateBadge } from './WorkflowStateBadge';

type RunDependencies = components['schemas']['RunDependencies'];
type RunDependencyRef = components['schemas']['RunDependencyRef'];

export interface RunDependencyPanelProps {
  dependencies: RunDependencies | undefined;
}

function DependencyList({
  label,
  refs,
  testId,
}: {
  label: string;
  refs: RunDependencyRef[];
  testId: string;
}) {
  return (
    <div data-testid={testId}>
      <h3 className="text-meta text-text-tertiary">{label}</h3>
      {refs.length === 0 ? (
        <p className="text-body text-text-secondary">None</p>
      ) : (
        <ul className="flex flex-col gap-1">
          {refs.map((ref) => (
            <li key={ref.runId} className="flex items-center gap-2">
              <code className="text-meta">{ref.runId}</code>
              {/* state is optional in the read model; surface the gap explicitly instead of
                  letting the badge's silent fallback render it as an ordinary state. */}
              {ref.state != null ? (
                <WorkflowStateBadge currentState={ref.state} />
              ) : (
                <span
                  className="text-meta text-text-tertiary"
                  data-testid="run-dependency-unknown-state"
                >
                  state unknown
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function RunDependencyPanel({ dependencies }: RunDependencyPanelProps) {
  const prerequisites = dependencies?.prerequisites ?? [];
  const dependents = dependencies?.dependents ?? [];
  const blockedOn = dependencies?.blockedOn ?? [];
  const blocked = dependencies?.blockedByDependencies ?? false;

  // Calm by default: ordinary runs with no dependency edges render nothing.
  if (prerequisites.length === 0 && dependents.length === 0) {
    return null;
  }

  return (
    <section
      aria-labelledby="run-dependencies-heading"
      data-testid="run-dependency-panel"
      className="flex flex-col gap-3 rounded-md border border-border p-4"
    >
      <h2 id="run-dependencies-heading" className="text-section-title">
        Run dependencies
      </h2>
      {blocked ? (
        <p data-testid="run-dependency-blocked" className="text-body text-text-secondary">
          Blocked — waiting on {blockedOn.length} prerequisite
          {blockedOn.length === 1 ? '' : 's'} to complete.
        </p>
      ) : null}
      <DependencyList
        label="Prerequisites (this run waits on)"
        refs={prerequisites}
        testId="run-dependency-prerequisites"
      />
      <DependencyList
        label="Dependents (waiting on this run)"
        refs={dependents}
        testId="run-dependency-dependents"
      />
    </section>
  );
}
