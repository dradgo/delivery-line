/** Story 3i-2 — pure view-model helpers for the intake browse. */
import { describe, expect, it } from 'vitest';

import { ProblemDetailsError } from '@/lib/api/problemDetails';
import { EMPTY_INTAKE_FILTERS, type IntakeFilters } from '@/lib/queryKeys/intakeKeys';
import {
  addComponent,
  dedupeTokens,
  intakeFilterSummary,
  isTicketQueryUnsupported,
  resolveIntakeState,
  toggleToken,
} from './intakeView';

const PROJECT = 'prj_acme0001';

function notSupportedError() {
  return new ProblemDetailsError({
    type: 'https://deliveryline.local/problems/ticket-query-not-supported',
    title: 'Ticket query not supported',
    status: 404,
    detail: 'Ticket source for project prj_acme0001 does not support a filtered ticket query',
    instance: `/api/v1/projects/${PROJECT}/ticket-query`,
    code: 'TICKET_QUERY_NOT_SUPPORTED',
    retryable: false,
  });
}

function otherError() {
  return new ProblemDetailsError({
    type: 'https://deliveryline.local/problems/internal-error',
    title: 'Internal error',
    status: 500,
    detail: 'Unexpected failure',
    instance: `/api/v1/projects/${PROJECT}/ticket-query`,
    code: 'INTERNAL_ERROR',
    retryable: true,
  });
}

function baseArgs(overrides: Partial<Parameters<typeof resolveIntakeState>[0]> = {}) {
  return {
    projectId: PROJECT as string | undefined,
    isPending: false,
    error: null as unknown,
    ticketCount: 0,
    filters: EMPTY_INTAKE_FILTERS,
    ...overrides,
  };
}

describe('isTicketQueryUnsupported', () => {
  it('matches only the typed capability-gate code', () => {
    expect(isTicketQueryUnsupported(notSupportedError())).toBe(true);
    expect(isTicketQueryUnsupported(otherError())).toBe(false);
    expect(isTicketQueryUnsupported(new Error('boom'))).toBe(false);
    expect(isTicketQueryUnsupported(null)).toBe(false);
  });
});

describe('resolveIntakeState', () => {
  it('reports no-project before a project is adopted', () => {
    expect(resolveIntakeState(baseArgs({ projectId: undefined }))).toBe('no-project');
  });

  it('prefers not-supported over the generic error surface', () => {
    expect(resolveIntakeState(baseArgs({ error: notSupportedError() }))).toBe('not-supported');
    expect(resolveIntakeState(baseArgs({ error: otherError() }))).toBe('error');
  });

  it('reports an error even while the query is still pending', () => {
    // A failed query is never "loading" — a stuck spinner would hide the failure.
    expect(resolveIntakeState(baseArgs({ isPending: true, error: otherError() }))).toBe('error');
  });

  it('distinguishes an empty backlog from a filtered-empty result', () => {
    expect(resolveIntakeState(baseArgs())).toBe('empty');
    const filtered: IntakeFilters = { ...EMPTY_INTAKE_FILTERS, assignee: 'nobody' };
    expect(resolveIntakeState(baseArgs({ filters: filtered }))).toBe('filtered-empty');
  });

  it('does not treat the project scope alone as an active filter', () => {
    const scoped: IntakeFilters = { ...EMPTY_INTAKE_FILTERS, projectId: PROJECT };
    expect(resolveIntakeState(baseArgs({ filters: scoped }))).toBe('empty');
  });

  it('reports populated when tickets came back', () => {
    expect(resolveIntakeState(baseArgs({ ticketCount: 2 }))).toBe('populated');
  });

  it('reports loading only while pending with no error', () => {
    expect(resolveIntakeState(baseArgs({ isPending: true }))).toBe('loading');
  });
});

describe('intakeFilterSummary', () => {
  it('is empty when nothing narrows the browse', () => {
    expect(intakeFilterSummary(EMPTY_INTAKE_FILTERS)).toBe('');
  });

  it('joins only the active filters', () => {
    expect(
      intakeFilterSummary({
        projectId: PROJECT,
        assignee: 'acct-1',
        components: ['billing', 'api'],
        state: 'To Do',
      }),
    ).toBe('assignee acct-1; components billing, api; state To Do');
  });
});

describe('toggleToken / addComponent', () => {
  it('toggles membership immutably', () => {
    const tokens = ['a', 'b'];
    expect(toggleToken(tokens, 'b')).toEqual(['a']);
    expect(toggleToken(tokens, 'c')).toEqual(['a', 'b', 'c']);
    expect(tokens).toEqual(['a', 'b']);
  });

  it('appends a trimmed component, ignoring blanks and duplicates', () => {
    expect(addComponent([], '  billing ')).toEqual(['billing']);
    expect(addComponent(['billing'], 'billing')).toEqual(['billing']);
    expect(addComponent(['billing'], '   ')).toEqual(['billing']);
    expect(addComponent(['billing'], 'api')).toEqual(['billing', 'api']);
  });
});

describe('dedupeTokens', () => {
  it('drops repeated tokens so the sidebar cannot emit duplicate keys or DOM ids', () => {
    // The URL is the only way in: `?components=billing,billing`.
    expect(dedupeTokens(['billing', 'billing'])).toEqual(['billing']);
  });

  it('preserves insertion order rather than sorting', () => {
    expect(dedupeTokens(['zeta', 'alpha', 'zeta'])).toEqual(['zeta', 'alpha']);
  });

  it('trims and drops blank tokens', () => {
    expect(dedupeTokens([' billing ', '', '   ', 'api'])).toEqual(['billing', 'api']);
  });

  it('trims before comparing, so padded repeats collapse', () => {
    expect(dedupeTokens(['billing', ' billing'])).toEqual(['billing']);
  });

  it('treats case as significant — distinct components yield distinct DOM ids', () => {
    expect(dedupeTokens(['billing', 'BILLING'])).toEqual(['billing', 'BILLING']);
  });
});
