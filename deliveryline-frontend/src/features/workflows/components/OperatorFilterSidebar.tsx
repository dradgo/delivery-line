/**
 * Story 4.2 (AC3/AC7) — the operator queue's filter sidebar.
 *
 * URL-owned (single source of truth): the sidebar holds NO state — it reads the current
 * `OperatorQueueFilters` and emits the FULL next filter object via `onChange`; the route serializes
 * it into the search params (every nav spreads all active filters, [[tanstack-validatesearch-strips-unparsed-param]]).
 *
 * Filter OPTIONS are typed against the generated OpenAPI enums (a `Record` over the union → a new
 * backend enum member is a COMPILE error until listed here, so the options never silently drift).
 * State tokens are the stable 4.1 `OperatorRunState` vocabulary (Reconciliation 8). Every checkbox
 * pairs with a visible `<label>` and the groups are `<fieldset>`s (AC7 keyboard + a11y).
 */
import { humanizeFailureCategory } from '../failureCategoryView';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import {
  FAILURE_CATEGORY_TOKENS,
  OPERATOR_STATE_TOKENS,
  RUNNER_KIND_TOKENS,
  type OperatorQueueFilters,
  type OperatorTimeWindow,
} from '@/lib/queryKeys/operatorKeys';

const TIME_WINDOW_OPTIONS: readonly { value: OperatorTimeWindow; label: string }[] = [
  { value: '1h', label: 'Last 1 hour' },
  { value: '24h', label: 'Last 24 hours' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
  { value: 'all', label: 'All time' },
];

function capitalize(token: string): string {
  return token.length === 0 ? token : token[0]!.toUpperCase() + token.slice(1);
}

function toggleToken(tokens: readonly string[], token: string): string[] {
  return tokens.includes(token) ? tokens.filter((t) => t !== token) : [...tokens, token];
}

export interface OperatorFilterSidebarProps {
  filters: OperatorQueueFilters;
  onChange: (next: OperatorQueueFilters) => void;
}

export function OperatorFilterSidebar({ filters, onChange }: OperatorFilterSidebarProps) {
  return (
    <aside
      className="flex w-full flex-col gap-5 md:w-64"
      aria-label="Operator queue filters"
      data-testid="operator-filter-sidebar"
    >
      <CheckboxGroup
        legend="State"
        testId="operator-filter-state"
        options={OPERATOR_STATE_TOKENS.map((t) => ({ value: t, label: capitalize(t) }))}
        selected={filters.states}
        onToggle={(token) => onChange({ ...filters, states: toggleToken(filters.states, token) })}
      />

      <CheckboxGroup
        legend="Failure category"
        testId="operator-filter-failure-category"
        options={FAILURE_CATEGORY_TOKENS.map((t) => ({
          value: t,
          label: humanizeFailureCategory(t) ?? t,
        }))}
        selected={filters.failureCategories}
        onToggle={(token) =>
          onChange({
            ...filters,
            failureCategories: toggleToken(filters.failureCategories, token),
          })
        }
      />

      {/*
       * Runner-kind filter ships DISABLED ("coming soon"). The read model sources runner kind from
       * the project-level `projects.runner_kind` override, which is null for any project on the
       * global default — so an active `runner_kind IN (...)` predicate silently excludes every
       * default-runner run and misleads the operator with empty results (code-review Decision 1,
       * OQ-RUNNERKIND). The AC2 per-row runner-kind DISPLAY chip is unaffected and still renders.
       */}
      <CheckboxGroup
        legend="Runner kind"
        testId="operator-filter-runner-kind"
        options={RUNNER_KIND_TOKENS.map((t) => ({ value: t, label: capitalize(t) }))}
        selected={filters.runnerKinds}
        onToggle={() => undefined}
        disabled
        note="Filtering by runner kind arrives in a future release."
      />

      <fieldset className="flex flex-col gap-2">
        <legend className="text-meta font-medium text-text-secondary">Time window</legend>
        <Select
          value={filters.timeWindow}
          onValueChange={(value) =>
            onChange({ ...filters, timeWindow: value as OperatorTimeWindow })
          }
        >
          <SelectTrigger data-testid="operator-filter-time-window" aria-label="Time window">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {TIME_WINDOW_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </fieldset>
    </aside>
  );
}

/** One multi-select checkbox group with a `<legend>` and labelled checkboxes (AC3/AC7). */
function CheckboxGroup({
  legend,
  testId,
  options,
  selected,
  onToggle,
  disabled = false,
  note,
}: {
  legend: string;
  testId: string;
  options: readonly { value: string; label: string }[];
  selected: readonly string[];
  onToggle: (token: string) => void;
  disabled?: boolean;
  note?: string;
}) {
  return (
    <fieldset className="flex flex-col gap-2" data-testid={testId} disabled={disabled}>
      <legend className="text-meta font-medium text-text-secondary">{legend}</legend>
      {options.map((option) => {
        const id = `${testId}-${option.value}`;
        return (
          <div key={option.value} className="flex items-center gap-2">
            <Checkbox
              id={id}
              checked={selected.includes(option.value)}
              disabled={disabled}
              onCheckedChange={() => onToggle(option.value)}
            />
            <label
              htmlFor={id}
              className={disabled ? 'text-sm text-text-secondary' : 'text-sm text-text-primary'}
            >
              {option.label}
            </label>
          </div>
        );
      })}
      {note !== undefined ? <p className="text-meta text-text-secondary">{note}</p> : null}
    </fieldset>
  );
}
