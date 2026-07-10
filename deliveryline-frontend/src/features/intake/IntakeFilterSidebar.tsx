/**
 * Story 3i-2 (AC5/AC6) — the intake browse's filter sidebar.
 *
 * URL-owned (single source of truth): the sidebar holds no committed state — it reads the current
 * `IntakeFilters` and emits the FULL next filter object via `onChange`; the route serializes it into
 * the search params (every nav spreads all active filters,
 * [[tanstack-validatesearch-strips-unparsed-param]]).
 *
 * <p><b>Why the component filter is not a fixed `CheckboxGroup`.</b> The operator queue's checkbox
 * groups enumerate a CLOSED vocabulary from the generated OpenAPI enums. Ticket-source components
 * have no such vocabulary — they are per-project strings defined in JIRA, and the `CandidateTicket`
 * DTO does not carry them, so there is nothing to enumerate. The operator therefore names a
 * component to add it; each active component then renders as a labelled checkbox inside the same
 * `<fieldset>`/`<legend>` pattern, and unchecking removes it. Accessible multi-select, open
 * vocabulary.
 *
 * The scalar inputs (assignee, state) commit on form submit rather than per keystroke — each commit
 * is a route navigation plus a refetch, so debouncing every character would thrash both.
 */
import { useEffect, useState } from 'react';

import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { EMPTY_INTAKE_FILTERS, type IntakeFilters } from '@/lib/queryKeys/intakeKeys';
import { addComponent, toggleToken } from './intakeView';

export interface IntakeFilterSidebarProps {
  filters: IntakeFilters;
  onChange: (next: IntakeFilters) => void;
  /** Disabled while no project is selected — there is nothing to filter yet. */
  disabled?: boolean;
}

export function IntakeFilterSidebar({
  filters,
  onChange,
  disabled = false,
}: IntakeFilterSidebarProps) {
  const [assignee, setAssignee] = useState(filters.assignee ?? '');
  const [state, setState] = useState(filters.state ?? '');
  const [pendingComponent, setPendingComponent] = useState('');

  // Re-sync the uncommitted inputs when the URL changes underneath us (back/forward, deep link).
  useEffect(() => {
    setAssignee(filters.assignee ?? '');
  }, [filters.assignee]);
  useEffect(() => {
    setState(filters.state ?? '');
  }, [filters.state]);

  const commit = (event: React.FormEvent) => {
    event.preventDefault();
    onChange({
      ...filters,
      assignee: assignee.trim() === '' ? undefined : assignee.trim(),
      state: state.trim() === '' ? undefined : state.trim(),
      components: addComponent(filters.components, pendingComponent),
    });
    setPendingComponent('');
  };

  const clear = () => {
    setAssignee('');
    setState('');
    setPendingComponent('');
    onChange({ ...EMPTY_INTAKE_FILTERS, projectId: filters.projectId });
  };

  return (
    <aside
      className="flex w-full flex-col gap-5 md:w-64"
      aria-label="Ticket intake filters"
      data-testid="intake-filter-sidebar"
    >
      <form className="flex flex-col gap-5" onSubmit={commit}>
        <fieldset className="flex flex-col gap-2" disabled={disabled}>
          <legend className="text-meta font-medium text-text-secondary">Assignee</legend>
          <label htmlFor="intake-filter-assignee" className="text-sm text-text-primary">
            Source assignee id or email
          </label>
          <Input
            id="intake-filter-assignee"
            data-testid="intake-filter-assignee"
            value={assignee}
            onChange={(event) => setAssignee(event.target.value)}
            placeholder="e.g. 5f8a1b2c… or dev@example.com"
          />
        </fieldset>

        <fieldset
          className="flex flex-col gap-2"
          data-testid="intake-filter-components"
          disabled={disabled}
        >
          <legend className="text-meta font-medium text-text-secondary">Components</legend>
          {filters.components.length === 0 ? (
            <p className="text-meta text-text-secondary">No component filter — showing all.</p>
          ) : (
            filters.components.map((component) => {
              const id = `intake-filter-component-${component}`;
              return (
                <div key={component} className="flex items-center gap-2">
                  <Checkbox
                    id={id}
                    checked
                    disabled={disabled}
                    onCheckedChange={() =>
                      onChange({
                        ...filters,
                        components: toggleToken(filters.components, component),
                      })
                    }
                  />
                  <label htmlFor={id} className="text-sm text-text-primary">
                    {component}
                  </label>
                </div>
              );
            })
          )}
          <label htmlFor="intake-filter-component-add" className="text-sm text-text-primary">
            Add a component
          </label>
          <Input
            id="intake-filter-component-add"
            data-testid="intake-filter-component-add"
            value={pendingComponent}
            onChange={(event) => setPendingComponent(event.target.value)}
            placeholder="e.g. billing"
          />
        </fieldset>

        <fieldset className="flex flex-col gap-2" disabled={disabled}>
          <legend className="text-meta font-medium text-text-secondary">State</legend>
          <label htmlFor="intake-filter-state" className="text-sm text-text-primary">
            Source workflow state
          </label>
          <Input
            id="intake-filter-state"
            data-testid="intake-filter-state"
            value={state}
            onChange={(event) => setState(event.target.value)}
            placeholder="e.g. To Do"
          />
        </fieldset>

        <div className="flex gap-2">
          <Button type="submit" data-testid="intake-filter-apply" disabled={disabled}>
            Apply filters
          </Button>
          <Button
            type="button"
            variant="outline"
            data-testid="intake-filter-clear"
            disabled={disabled}
            onClick={clear}
          >
            Clear
          </Button>
        </div>
      </form>
    </aside>
  );
}
