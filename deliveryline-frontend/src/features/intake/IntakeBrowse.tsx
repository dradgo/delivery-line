/**
 * Story 3i-2 (AC4/AC5/AC6) — the ticket-intake browse.
 *
 * Lists candidate tickets from the selected project's ticket source, filtered by assignee /
 * components / state, and lets the operator start a governed run per row.
 *
 * <p><b>Capability gating (AC3).</b> A project whose connector cannot be browsed answers the query
 * with a typed `TICKET_QUERY_NOT_SUPPORTED` 404. The view hides the browse on that code — it never
 * hardcodes `kind === 'jira'`, so a future browsable connector lights the surface up with no FE
 * change.
 *
 * <p><b>Independent submits (AC4).</b> Each row owns its OWN `useSubmitWorkflow` instance, so every
 * "start run" mints its own idempotency key and carries its own pending/error state. One row failing
 * neither aborts nor masks the others — the batch-submission posture. There is no bespoke create
 * seam: rows reuse the existing `POST /api/v1/workflows/submit-workflow`.
 */
import { useEffect, useState } from 'react';

import { ProjectSelector } from '@/features/projects/components/ProjectSelector';
import { useProjectsList } from '@/features/projects/hooks/useProjectsList';
import { useSubmitWorkflow } from '@/features/workflows/hooks/useSubmitWorkflow';
import { IntakeFilterSidebar } from './IntakeFilterSidebar';
import { intakeFilterSummary, resolveIntakeState } from './intakeView';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback';
import {
  intakeFilteredToTickets,
  intakeNotSupported,
  intakeRunStarted,
} from '@/lib/a11y/announcements';
import { useLiveAnnouncement } from '@/lib/a11y/useLiveAnnouncement';
import { candidateTicketsQueryOptions, type CandidateTicket } from '@/lib/api/queryOptions';
import { EMPTY_INTAKE_FILTERS, type IntakeFilters } from '@/lib/queryKeys/intakeKeys';
import { useQuery } from '@tanstack/react-query';

export interface IntakeBrowseProps {
  filters: IntakeFilters;
  onFiltersChange: (next: IntakeFilters) => void;
}

export function IntakeBrowse({ filters, onFiltersChange }: IntakeBrowseProps) {
  // Component state, NOT a search param: the actor identity is operator PII and must not ride the
  // URL into browser history / referrer headers / proxy access logs.
  const [actorIdentity, setActorIdentity] = useState('');
  const { data: projects } = useProjectsList();
  const query = useQuery(candidateTicketsQueryOptions(filters));
  const tickets = query.data?.tickets ?? [];
  // `total` is the source's match count; it exceeds the page when the browse was capped by `limit`
  // or a ticket could not be mapped. Either way the operator is not seeing everything.
  const total = query.data?.total ?? tickets.length;
  const truncated = query.data?.truncated ?? false;

  // Adopt the first project as the browse scope so the selector never displays a project we are not
  // actually querying (ProjectSelector defaults its own trigger to projects[0] when uncontrolled).
  const firstProjectId = projects?.[0]?.id ?? projects?.[0]?.slug ?? undefined;
  useEffect(() => {
    if (filters.projectId === undefined && firstProjectId !== undefined) {
      onFiltersChange({ ...filters, projectId: firstProjectId });
    }
  }, [filters, firstProjectId, onFiltersChange]);

  const state = resolveIntakeState({
    projectId: filters.projectId,
    isPending: query.isPending,
    error: query.error,
    ticketCount: tickets.length,
    filters,
  });

  const announcement = useLiveAnnouncement(
    state === 'not-supported'
      ? intakeNotSupported
      : state === 'populated' || state === 'filtered-empty'
        ? intakeFilteredToTickets(tickets.length, intakeFilterSummary(filters), total)
        : '',
  );

  const canSubmit = actorIdentity.trim() !== '' && filters.projectId !== undefined;

  return (
    <section className="flex flex-col gap-4" data-testid="intake-browse">
      <header className="flex flex-wrap items-end gap-4">
        <ProjectSelector
          value={filters.projectId}
          onChange={(projectId) => onFiltersChange({ ...filters, projectId })}
          collapseWhenSingle={false}
        />
        <div className="flex flex-col gap-1">
          <label htmlFor="intake-actor-identity" className="text-meta text-text-secondary">
            Your identity
          </label>
          <Input
            id="intake-actor-identity"
            data-testid="intake-actor-identity"
            className="w-64"
            value={actorIdentity}
            onChange={(event) => setActorIdentity(event.target.value)}
            placeholder="e.g. alex@example.com"
          />
        </div>
      </header>

      <div className="flex flex-col gap-6 md:flex-row">
        <IntakeFilterSidebar
          filters={filters}
          onChange={onFiltersChange}
          disabled={filters.projectId === undefined}
        />

        <div className="flex-1">
          {state === 'no-project' ? (
            <EmptyState
              variant="queue"
              title="No project selected"
              message="Select a project to browse its candidate tickets."
            />
          ) : null}

          {state === 'loading' ? <LoadingState variant="fetchingData" /> : null}

          {state === 'not-supported' ? (
            <EmptyState
              variant="queue"
              title="Browsing is not available for this project"
              message="This project’s ticket source does not support browsing candidate tickets. Start a run from the submit form instead."
            />
          ) : null}

          {state === 'error' ? (
            <ErrorState
              variant="failedRetrieval"
              nextAction={{ kind: 'Retry', onRetry: () => void query.refetch() }}
            />
          ) : null}

          {state === 'empty' ? (
            <EmptyState
              variant="queue"
              title="No candidate tickets"
              message="This project’s ticket source returned no tickets."
            />
          ) : null}

          {state === 'filtered-empty' ? (
            <EmptyState
              variant="filtered"
              action={
                <Button
                  variant="outline"
                  data-testid="intake-clear-filters"
                  onClick={() =>
                    onFiltersChange({ ...EMPTY_INTAKE_FILTERS, projectId: filters.projectId })
                  }
                >
                  Clear filters
                </Button>
              }
            />
          ) : null}

          {state === 'populated' ? (
            <>
              {truncated ? (
                <p
                  className="mb-2 text-meta text-text-secondary"
                  data-testid="intake-truncation-notice"
                >
                  Showing {tickets.length} of {total} matching tickets. Narrow the filters to see
                  the rest.
                </p>
              ) : null}
              <ul className="flex flex-col gap-2" data-testid="intake-ticket-list">
                {tickets.map((ticket) => (
                  <IntakeTicketRow
                    key={ticket.ticketRef}
                    ticket={ticket}
                    projectReference={filters.projectId}
                    actorIdentity={actorIdentity}
                    canSubmit={canSubmit}
                  />
                ))}
              </ul>
            </>
          ) : null}
        </div>
      </div>

      <div role="status" aria-live="polite" className="sr-only" data-testid="intake-announcer">
        {announcement}
      </div>
    </section>
  );
}

/**
 * One candidate ticket. Owns its own submit mutation so its pending/success/error state — and its
 * idempotency key — are independent of every sibling row (AC4).
 */
function IntakeTicketRow({
  ticket,
  projectReference,
  actorIdentity,
  canSubmit,
}: {
  ticket: CandidateTicket;
  projectReference: string | undefined;
  actorIdentity: string;
  canSubmit: boolean;
}) {
  const submit = useSubmitWorkflow();
  // `ticketRef` and `title` are REQUIRED on the wire (the OpenAPI schema marks them so), which is
  // why there is no `?? ''` fallback here — the guarantee lives in the contract, not in each caller.
  const ticketRef = ticket.ticketRef;
  const started = submit.status === 'success';

  const announcement = useLiveAnnouncement(started ? intakeRunStarted(ticketRef) : '');

  return (
    <li
      className="flex items-start justify-between gap-4 rounded-md border border-input p-3"
      data-testid={`intake-ticket-${ticketRef}`}
    >
      <div className="min-w-0">
        <p className="text-sm font-medium text-text-primary">
          <span className="text-text-secondary">{ticketRef}</span> {ticket.title}
        </p>
        {/* `summary` is nullable on the wire — guard with `!= null`, never a truthy check that
            would also swallow a legitimately empty-string body. */}
        {ticket.summary != null ? (
          <p className="mt-1 line-clamp-2 text-meta text-text-secondary">{ticket.summary}</p>
        ) : null}
        {submit.error != null ? (
          <p className="mt-1 text-meta text-state-error-fg" data-testid="intake-row-error">
            Could not start a run for {ticketRef}.
          </p>
        ) : null}
      </div>

      <Button
        data-testid="intake-start-run"
        disabled={!canSubmit || submit.isPending || started}
        onClick={() =>
          submit.submit({
            linearTicketReference: ticketRef,
            actorIdentity: actorIdentity.trim(),
            actorType: 'HUMAN',
            ...(projectReference !== undefined ? { projectReference } : {}),
          })
        }
      >
        {started ? 'Run started' : submit.isPending ? 'Starting…' : 'Start run'}
      </Button>

      <span className="sr-only" role="status" aria-live="polite">
        {announcement}
      </span>
    </li>
  );
}
