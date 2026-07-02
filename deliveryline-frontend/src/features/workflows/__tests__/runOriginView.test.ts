/**
 * Story 3g-2 (Task 5 / AC2, AC3, AC5) — unit tests for the pure `toRunOriginView` mapper.
 *
 * Router-free + render-free: exercises the present-gate (no `linkedTicket` / `null` title →
 * `undefined`), the `!= null` field coalescing, and the non-http url defense.
 */
import { describe, expect, it } from 'vitest';

import type { WorkflowDetail } from '@/lib/api/queryOptions';
import { toRunOriginView } from '../runOriginView';

/** A workflow-detail fixture carrying a fully-populated origin ticket. */
const DETAIL_WITH_ORIGIN: WorkflowDetail = {
  workflowRunId: 'run_abc123',
  linkedTicket: {
    externalRef: 'DEL-1234',
    integrationType: 'linear',
    title: 'Fix flaky checkout test',
    url: 'https://linear.app/acme/issue/DEL-1234',
  },
};

describe('toRunOriginView', () => {
  it('AC2/AC3 — maps title, ref, integrationType, and url from the linked ticket', () => {
    expect(toRunOriginView(DETAIL_WITH_ORIGIN)).toEqual({
      title: 'Fix flaky checkout test',
      ticketRef: 'DEL-1234',
      integrationType: 'linear',
      url: 'https://linear.app/acme/issue/DEL-1234',
    });
  });

  it('AC2 — returns undefined when there is no linked ticket', () => {
    expect(toRunOriginView({ workflowRunId: 'run_abc123' })).toBeUndefined();
  });

  it('AC2 — returns undefined when the linked ticket has a null/blank title', () => {
    expect(
      toRunOriginView({ linkedTicket: { externalRef: 'DEL-1234', title: null } }),
    ).toBeUndefined();
    expect(
      toRunOriginView({ linkedTicket: { externalRef: 'DEL-1234', title: '   ' } }),
    ).toBeUndefined();
  });

  it('AC5 — coalesces a null url (and absent ref/type) to undefined (wire null, not absent)', () => {
    const view = toRunOriginView({
      linkedTicket: {
        title: 'Titled ticket',
        // url is `string | null` on the wire — a null must collapse to undefined.
        url: null,
      },
    });
    expect(view).toEqual({
      title: 'Titled ticket',
      ticketRef: undefined,
      integrationType: undefined,
      url: undefined,
    });
  });

  it('hardening — drops a non-http(s) url scheme (never reaches an href)', () => {
    // Built by concatenation so the literal never trips eslint `no-script-url`.
    const unsafeScheme = 'java' + 'script:alert(1)';
    const view = toRunOriginView({
      linkedTicket: {
        externalRef: 'DEL-1234',
        title: 'Titled ticket',
        url: unsafeScheme,
      },
    });
    expect(view?.url).toBeUndefined();
  });

  it('accepts a plain http url as well as https', () => {
    expect(
      toRunOriginView({
        linkedTicket: { title: 'T', url: 'http://tickets.internal/DEL-1' },
      })?.url,
    ).toBe('http://tickets.internal/DEL-1');
  });

  it('review — accepts a case-varied scheme (RFC 3986 schemes are case-insensitive)', () => {
    expect(
      toRunOriginView({ linkedTicket: { title: 'T', url: 'HTTPS://linear.app/issue/x' } })?.url,
    ).toBe('HTTPS://linear.app/issue/x');
  });

  it('review — trims surrounding whitespace on title/ref and the url (present, not verbatim)', () => {
    const view = toRunOriginView({
      linkedTicket: {
        externalRef: '  DEL-1234  ',
        title: '  Titled ticket  ',
        url: '  https://linear.app/issue/x  ',
      },
    });
    expect(view).toEqual({
      title: 'Titled ticket',
      ticketRef: 'DEL-1234',
      integrationType: undefined,
      url: 'https://linear.app/issue/x',
    });
  });
});
