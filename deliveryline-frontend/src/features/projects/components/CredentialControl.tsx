/**
 * Story 3c-9 (Task 6, AC4) — write-only credential control for one connector role.
 *
 * Renders the role's presence as a "Configured" / "Not configured" badge (icon+text,
 * non-color signifier) plus a "Set" / "Replace" affordance that opens a Dialog with a
 * single `type="password"` input. Submitting calls `useSetProjectCredential` (required
 * Idempotency-Key). On success the input is cleared, the dialog closes, and an inline
 * (non-toast) confirmation shows the new state.
 *
 * SECRET HOSTILITY (AC4): the stored value is NEVER displayed, pre-filled, or seeded
 * from any response; the input has no `value`/`defaultValue` from the wire; the secret
 * is cleared on success; no response field carries a secret; no breadcrumb logs it.
 */
import { useState } from 'react';
import { CircleCheck, CircleDashed } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import type { Project } from '@/lib/api/queryOptions';

import { useSetProjectCredential } from '../hooks/useSetProjectCredential';
import {
  CONNECTOR_ROLE_LABELS,
  credentialErrorMessage,
  credentialStatusFor,
  projectErrorCode,
  type ConnectorRole,
} from '../projectFormView';

export interface CredentialControlProps {
  project: Project;
  /** The connector role this control credentials (named `connectorRole`, not `role`,
   * so jsx-a11y does not mistake it for a DOM ARIA role). */
  connectorRole: ConnectorRole;
  /** Whether the project's allowedActions permit `set_credential` (AC7). */
  canSet: boolean;
}

export function CredentialControl({
  project,
  connectorRole: role,
  canSet,
}: CredentialControlProps) {
  const projectId = project.id ?? '';
  const status = credentialStatusFor(project, role);
  const configured = status === 'configured';
  const roleLabel = CONNECTOR_ROLE_LABELS[role];

  const [open, setOpen] = useState(false);
  // The secret lives ONLY here, for the duration of one set. Never seeded from a response.
  const [secret, setSecret] = useState('');
  const setCredential = useSetProjectCredential(projectId);
  const { status: mutationStatus, error } = setCredential;

  // Reflect a just-completed set immediately (before the list refetch lands), so the
  // presence reads "Configured" right after a successful save (AC4). The response is
  // id-only — it NEVER carries a secret.
  const showConfigured = configured || mutationStatus === 'success';

  const inputId = `credential-secret-${projectId}-${role}`;
  const errorId = `${inputId}-error`;

  const closeDialog = () => {
    setSecret('');
    setCredential.reset();
    setOpen(false);
  };

  const handleSubmit = (formEvent: React.FormEvent<HTMLFormElement>) => {
    formEvent.preventDefault();
    // Trim surrounding whitespace: a pasted token often carries a trailing newline,
    // which would otherwise be stored verbatim and fail later auth probes with no
    // diagnosable cause (the secret is write-only).
    const trimmedSecret = secret.trim();
    if (trimmedSecret.length === 0) {
      return;
    }
    // Field-only breadcrumb — role + status only, NEVER the secret (AC4 / logging).
    console.info({ event: 'project.credentialSetAttempt', role });
    setCredential.mutate(
      { role, secret: trimmedSecret },
      {
        onSuccess: () => {
          // Clear the secret the instant it is no longer needed (secret hostility).
          setSecret('');
          setOpen(false);
        },
      },
    );
  };

  return (
    <div className="flex flex-col gap-1" data-testid={`project-credential-${role}`}>
      <span
        className="inline-flex items-center gap-1.5 text-sm"
        data-credential-status={showConfigured ? 'configured' : 'not_configured'}
        data-testid={`project-credential-status-${role}`}
      >
        {showConfigured ? (
          <CircleCheck className="size-4 shrink-0 text-state-success-foreground" aria-hidden />
        ) : (
          <CircleDashed className="size-4 shrink-0 text-state-empty-foreground" aria-hidden />
        )}
        <span className="text-text-secondary">{roleLabel}:</span>
        <span className="font-medium text-text-primary">
          {showConfigured ? 'Configured' : 'Not configured'}
        </span>
      </span>

      {canSet ? (
        <div>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setOpen(true)}
            data-testid={`project-credential-set-button-${role}`}
          >
            {showConfigured ? 'Replace' : 'Set'}
          </Button>
        </div>
      ) : null}

      <Dialog
        open={open}
        onOpenChange={(next) => {
          if (!next) {
            closeDialog();
          } else {
            setOpen(true);
          }
        }}
      >
        <DialogContent data-testid={`project-credential-dialog-${role}`}>
          <DialogHeader>
            <DialogTitle>
              {showConfigured ? 'Replace' : 'Set'} {roleLabel.toLowerCase()} credential
            </DialogTitle>
            <DialogDescription>
              The secret is encrypted and stored write-only. It is never displayed again.
            </DialogDescription>
          </DialogHeader>
          <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={inputId}>Secret</Label>
              <Input
                id={inputId}
                name="secret"
                type="password"
                autoComplete="off"
                value={secret}
                aria-required="true"
                aria-invalid={mutationStatus === 'error'}
                aria-describedby={mutationStatus === 'error' ? errorId : undefined}
                onChange={(event) => {
                  if (mutationStatus === 'error') {
                    setCredential.reset();
                  }
                  setSecret(event.target.value);
                }}
                data-testid={`project-credential-input-${role}`}
              />
              {mutationStatus === 'error' ? (
                <p
                  id={errorId}
                  className="text-sm text-state-error-foreground"
                  role="alert"
                  data-error-code={projectErrorCode(error)}
                >
                  {credentialErrorMessage(error)}
                </p>
              ) : null}
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" size="sm" onClick={closeDialog}>
                Cancel
              </Button>
              <Button
                type="submit"
                size="sm"
                disabled={mutationStatus === 'pending' || secret.trim().length === 0}
                data-testid={`project-credential-submit-${role}`}
              >
                {mutationStatus === 'pending' ? 'Saving…' : 'Save credential'}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
