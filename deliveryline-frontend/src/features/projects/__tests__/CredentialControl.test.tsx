/**
 * Story 3c-9 (Task 9, AC4/AC10) — `CredentialControl` write-only behavior.
 *
 * Asserts the secret-hostility contract: the secret never lands in the DOM after
 * submit, the input is `type="password"` + cleared on success, an `Idempotency-Key`
 * header is sent, and `CREDENTIAL_MASTER_KEY_UNCONFIGURED` renders a friendly 503
 * message. Plus a wcag2aa axe scan.
 */
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PROBLEM_JSON_CONTENT_TYPE } from '@/lib/api/problemDetails';
import { server } from '@/test/server';
import { defaultProjectFixture } from '@/test/handlers';
import { expectNoA11yViolations } from '@/test/a11y/axe';

import { CredentialControl } from '../components/CredentialControl';

const CRED_URL = 'http://localhost/api/v1/projects/prj_default/credentials/repo_host';
const SECRET = 'super-secret-token-value';

function freshClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
}

function renderControl(node: ReactNode) {
  return render(<QueryClientProvider client={freshClient()}>{node}</QueryClientProvider>);
}

beforeEach(() => {
  vi.spyOn(console, 'info').mockImplementation(() => {});
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('CredentialControl', () => {
  it('AC4 — set succeeds → input cleared, "Configured" shown, secret never in DOM, Idempotency-Key sent', async () => {
    let idempotencyKey: string | null = null;
    let bodySecret: string | undefined;
    server.use(
      http.put(CRED_URL, async ({ request }) => {
        idempotencyKey = request.headers.get('Idempotency-Key');
        bodySecret = ((await request.json()) as { secret?: string }).secret;
        return HttpResponse.json({
          role: 'repo_host',
          status: 'configured',
          credentialId: 'cred_abc123',
        });
      }),
    );

    renderControl(
      <CredentialControl project={defaultProjectFixture} connectorRole="repo_host" canSet />,
    );

    fireEvent.click(screen.getByTestId('project-credential-set-button-repo_host'));
    const input = screen.getByTestId('project-credential-input-repo_host');
    expect(input).toHaveAttribute('type', 'password');
    fireEvent.change(input, { target: { value: SECRET } });
    fireEvent.click(screen.getByTestId('project-credential-submit-repo_host'));

    // After success the dialog closes and the presence flips to Configured.
    await waitFor(() =>
      expect(screen.getByTestId('project-credential-status-repo_host')).toHaveAttribute(
        'data-credential-status',
        'configured',
      ),
    );
    expect(screen.getByTestId('project-credential-status-repo_host')).toHaveTextContent(
      'Configured',
    );

    // The request carried the required idempotency key + the plaintext body.
    expect(idempotencyKey).not.toBeNull();
    expect(bodySecret).toBe(SECRET);

    // SECRET HOSTILITY — the secret must not appear anywhere in the document.
    expect(document.body.innerHTML).not.toContain(SECRET);
  });

  it('AC4 — CREDENTIAL_MASTER_KEY_UNCONFIGURED renders a friendly 503 message', async () => {
    server.use(
      http.put(CRED_URL, () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'No master key',
            status: 503,
            detail: 'master key unconfigured',
            instance: CRED_URL,
            code: 'CREDENTIAL_MASTER_KEY_UNCONFIGURED',
            retryable: false,
          },
          { status: 503, headers: { 'content-type': PROBLEM_JSON_CONTENT_TYPE } },
        ),
      ),
    );

    renderControl(
      <CredentialControl project={defaultProjectFixture} connectorRole="repo_host" canSet />,
    );
    fireEvent.click(screen.getByTestId('project-credential-set-button-repo_host'));
    fireEvent.change(screen.getByTestId('project-credential-input-repo_host'), {
      target: { value: SECRET },
    });
    fireEvent.click(screen.getByTestId('project-credential-submit-repo_host'));

    const error = await screen.findByRole('alert');
    expect(error).toHaveAttribute('data-error-code', 'CREDENTIAL_MASTER_KEY_UNCONFIGURED');
    expect(error).toHaveTextContent('no encryption master key');
    // The dialog stays OPEN on error so the user can retry; the error response itself
    // carries no secret (the secret-not-in-DOM-after-success contract is asserted above).
    expect(error.textContent).not.toContain(SECRET);
  });

  it('AC7 — no set/replace affordance when set_credential is not allowed', () => {
    renderControl(
      <CredentialControl
        project={defaultProjectFixture}
        connectorRole="repo_host"
        canSet={false}
      />,
    );
    expect(screen.queryByTestId('project-credential-set-button-repo_host')).toBeNull();
  });

  it('AC10 — the credential dialog passes a wcag2aa axe scan', async () => {
    renderControl(
      <CredentialControl project={defaultProjectFixture} connectorRole="repo_host" canSet />,
    );
    fireEvent.click(screen.getByTestId('project-credential-set-button-repo_host'));
    await screen.findByTestId('project-credential-dialog-repo_host');
    await expectNoA11yViolations(document.body);
  });
});
