/**
 * Story 3c-9 (Task 6, AC3) — the create / edit project form (Dialog, Open Decision #1).
 *
 * Mirrors `SubmitRunForm`: plain `useState`, a pure `projectFormView` validator, lazy
 * `showErrors`, and the `<Label htmlFor>` + `aria-invalid` + `aria-describedby` +
 * `role="alert"` `FieldError` triplet. `Select` drives the connector-kind pickers; a
 * checkbox drives OpenSpec. `slug` is disabled in edit mode (not in
 * `UpdateProjectRequest`). Create calls `useCreateProject`; edit calls
 * `useUpdateProject`. A `PROJECT_SLUG_CONFLICT` maps to the slug field; other typed
 * failures render an inline error region (never a toast).
 *
 * The parent (`ProjectsScreen`) remounts this with a fresh `key` per open, so the
 * `useState` initializers seed create-blank vs edit-prefill correctly.
 */
import { useRef, useState } from 'react';

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
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import type { Project } from '@/lib/api/queryOptions';

import { useCreateProject } from '../hooks/useCreateProject';
import { useUpdateProject } from '../hooks/useUpdateProject';
import {
  connectorKindOptions,
  emptyProjectFormFields,
  projectErrorCode,
  projectErrorMessage,
  runnerKindOptions,
  RUNNER_STEP_LABELS,
  RUNNER_STEPS,
  toProjectFormFields,
  toWireRunnerKind,
  toWireStepRunnerKinds,
  validateProjectForm,
  type ProjectFormErrors,
  type ProjectFormFields,
  type RunnerStep,
} from '../projectFormView';

export type ProjectFormMode = { kind: 'create' } | { kind: 'edit'; project: Project };

export interface ProjectFormProps {
  mode: ProjectFormMode;
  open: boolean;
  onClose: () => void;
}

/** A single labelled field-error message (rendered only after a submit attempt). */
function FieldError({ id, message }: { id: string; message: string | undefined }) {
  if (message === undefined) {
    return null;
  }
  return (
    <p id={id} className="text-sm text-state-error-foreground" role="alert">
      {message}
    </p>
  );
}

export function ProjectForm({ mode, open, onClose }: ProjectFormProps) {
  const isEdit = mode.kind === 'edit';
  const projectId = mode.kind === 'edit' ? (mode.project.id ?? '') : '';

  const [fields, setFields] = useState<ProjectFormFields>(() =>
    mode.kind === 'edit' ? toProjectFormFields(mode.project) : emptyProjectFormFields(),
  );
  const [showErrors, setShowErrors] = useState(false);
  // Guards against a pre-commit double-submit (a second fire before `status` flips to
  // `pending`), which would otherwise mint a SECOND idempotency key — two distinct
  // attempts the backend can't dedupe (the second would 409 PROJECT_SLUG_CONFLICT).
  const submittingRef = useRef(false);

  // Connector-kind options include the project's current value when the frontend list
  // doesn't know it (forward-registry drift), so an edit form never shows a blank kind.
  const ticketSourceKindOptions = connectorKindOptions(fields.ticketSourceKind);
  const repoHostKindOptions = connectorKindOptions(fields.repoHostKind);

  const createProject = useCreateProject();
  const updateProject = useUpdateProject(projectId);
  const active = isEdit ? updateProject : createProject;
  const { status, error } = active;

  const errors: ProjectFormErrors = validateProjectForm(fields, { isEdit });
  // A slug conflict is surfaced on the slug field (create only).
  const serverSlugConflict =
    !isEdit && status === 'error' && projectErrorCode(error) === 'PROJECT_SLUG_CONFLICT';
  const visibleErrors: ProjectFormErrors = showErrors ? { ...errors } : {};
  if (serverSlugConflict) {
    visibleErrors.slug = 'That slug is already in use. Choose a different slug.';
  }
  // A non-slug typed failure surfaces in the general error region.
  const showGeneralError = status === 'error' && !serverSlugConflict;

  const update = <K extends keyof ProjectFormFields>(key: K, value: ProjectFormFields[K]) => {
    // Clear a server failure on the next edit. But a slug conflict is a SLUG-field
    // error — only editing the slug should clear it (editing the name must not hide a
    // still-unresolved slug conflict).
    if (status === 'error' && (!serverSlugConflict || key === 'slug')) {
      active.reset();
    }
    setFields((prev) => ({ ...prev, [key]: value }));
  };

  // Story 3e-4 — update one per-step runner mapping (nested record). A non-slug server failure is
  // cleared on the next edit, mirroring `update`.
  const updateStepRunner = (step: RunnerStep, value: string) => {
    if (status === 'error' && !serverSlugConflict) {
      active.reset();
    }
    setFields((prev) => ({
      ...prev,
      stepRunnerKinds: { ...prev.stepRunnerKinds, [step]: value },
    }));
  };

  const handleSubmit = (formEvent: React.FormEvent<HTMLFormElement>) => {
    formEvent.preventDefault();
    setShowErrors(true);
    if (Object.keys(validateProjectForm(fields, { isEdit })).length > 0) {
      return;
    }
    if (submittingRef.current || status === 'pending') {
      return;
    }
    submittingRef.current = true;
    const onSettled = () => {
      submittingRef.current = false;
    };
    if (isEdit) {
      console.info({ event: 'project.updateAttempt' });
      updateProject.mutate(
        {
          name: fields.name,
          ticketSourceKind: fields.ticketSourceKind,
          repoHostKind: fields.repoHostKind,
          repositoryUrl: fields.repositoryUrl.trim().length > 0 ? fields.repositoryUrl : undefined,
          openspecEnabled: fields.openspecEnabled,
          // Full-replace runner config (story 3e-4): always send so an edit preserves/clears both.
          runnerKind: toWireRunnerKind(fields.runnerKind),
          stepRunnerKinds: toWireStepRunnerKinds(fields.stepRunnerKinds),
        },
        { onSuccess: onClose, onSettled },
      );
    } else {
      console.info({ event: 'project.createAttempt' });
      createProject.mutate(
        {
          name: fields.name,
          slug: fields.slug,
          ticketSourceKind: fields.ticketSourceKind,
          repoHostKind: fields.repoHostKind,
          repositoryUrl: fields.repositoryUrl.trim().length > 0 ? fields.repositoryUrl : undefined,
          openspecEnabled: fields.openspecEnabled,
          // Story 3e-4 — per-project runner default (null = use global) + per-step mapping.
          runnerKind: toWireRunnerKind(fields.runnerKind),
          stepRunnerKinds: toWireStepRunnerKinds(fields.stepRunnerKinds),
        },
        { onSuccess: onClose, onSettled },
      );
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) {
          onClose();
        }
      }}
    >
      <DialogContent data-testid="project-form-dialog">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit project' : 'New project'}</DialogTitle>
          <DialogDescription>
            {isEdit
              ? 'Update this project’s configuration. The slug is fixed after creation.'
              : 'Configure a new project. The slug is permanent once created.'}
          </DialogDescription>
        </DialogHeader>

        <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="project-name">Display name</Label>
            <Input
              id="project-name"
              name="name"
              value={fields.name}
              maxLength={256}
              aria-required="true"
              aria-invalid={visibleErrors.name !== undefined}
              aria-describedby={visibleErrors.name !== undefined ? 'project-name-error' : undefined}
              onChange={(event) => update('name', event.target.value)}
              data-testid="project-name-input"
            />
            <FieldError id="project-name-error" message={visibleErrors.name} />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="project-slug">Slug</Label>
            <Input
              id="project-slug"
              name="slug"
              value={fields.slug}
              maxLength={256}
              disabled={isEdit}
              aria-required={!isEdit}
              aria-invalid={visibleErrors.slug !== undefined}
              aria-describedby={visibleErrors.slug !== undefined ? 'project-slug-error' : undefined}
              onChange={(event) => update('slug', event.target.value)}
              data-testid="project-slug-input"
            />
            {isEdit ? (
              <p className="text-meta text-text-tertiary">
                The slug cannot be changed after creation.
              </p>
            ) : null}
            <FieldError id="project-slug-error" message={visibleErrors.slug} />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="project-repo-url">Repository URL (optional)</Label>
            <Input
              id="project-repo-url"
              name="repositoryUrl"
              value={fields.repositoryUrl}
              maxLength={2048}
              aria-invalid={visibleErrors.repositoryUrl !== undefined}
              aria-describedby={
                visibleErrors.repositoryUrl !== undefined ? 'project-repo-url-error' : undefined
              }
              onChange={(event) => update('repositoryUrl', event.target.value)}
              data-testid="project-repo-url-input"
            />
            <FieldError id="project-repo-url-error" message={visibleErrors.repositoryUrl} />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="project-ticket-source-kind">Ticket source</Label>
            <Select
              value={fields.ticketSourceKind}
              onValueChange={(value) => update('ticketSourceKind', value)}
            >
              <SelectTrigger
                id="project-ticket-source-kind"
                aria-label="Ticket source"
                data-testid="project-ticket-source-kind"
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {ticketSourceKindOptions.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <FieldError
              id="project-ticket-source-kind-error"
              message={visibleErrors.ticketSourceKind}
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="project-repo-host-kind">Repository host</Label>
            <Select
              value={fields.repoHostKind}
              onValueChange={(value) => update('repoHostKind', value)}
            >
              <SelectTrigger
                id="project-repo-host-kind"
                aria-label="Repository host"
                data-testid="project-repo-host-kind"
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {repoHostKindOptions.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <FieldError id="project-repo-host-kind-error" message={visibleErrors.repoHostKind} />
          </div>

          <div className="flex items-center gap-2">
            <input
              id="project-openspec"
              name="openspecEnabled"
              type="checkbox"
              className="size-4 rounded border-border"
              checked={fields.openspecEnabled}
              onChange={(event) => update('openspecEnabled', event.target.checked)}
              data-testid="project-openspec-toggle"
            />
            <Label htmlFor="project-openspec">Enable OpenSpec</Label>
          </div>

          {/* Story 3e-4 — runner configuration: a project-wide default plus an optional per-step
              override for each workflow step. "Use default" clears a binding (falls through to the
              next-broader default). */}
          <fieldset className="flex flex-col gap-3 rounded-md border border-border p-3">
            <legend className="px-1 text-sm font-medium">Runner configuration</legend>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="project-runner-kind">Project-wide runner default</Label>
              <Select
                value={fields.runnerKind}
                onValueChange={(value) => update('runnerKind', value)}
              >
                <SelectTrigger
                  id="project-runner-kind"
                  aria-label="Project-wide runner default"
                  data-testid="project-runner-kind"
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {runnerKindOptions('Use global default', fields.runnerKind).map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <p className="text-meta text-text-tertiary">
                Applies to every step unless a per-step runner below overrides it.
              </p>
            </div>

            {RUNNER_STEPS.map((step) => {
              const fieldId = `project-step-runner-${step}`;
              const label = `${RUNNER_STEP_LABELS[step]} runner`;
              return (
                <div key={step} className="flex flex-col gap-1.5">
                  <Label htmlFor={fieldId}>{label}</Label>
                  <Select
                    value={fields.stepRunnerKinds[step]}
                    onValueChange={(value) => updateStepRunner(step, value)}
                  >
                    <SelectTrigger id={fieldId} aria-label={label} data-testid={fieldId}>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {runnerKindOptions('Use project default', fields.stepRunnerKinds[step]).map(
                        (option) => (
                          <SelectItem key={option.value} value={option.value}>
                            {option.label}
                          </SelectItem>
                        ),
                      )}
                    </SelectContent>
                  </Select>
                </div>
              );
            })}
          </fieldset>

          {showGeneralError ? (
            <p
              className="text-sm text-state-error-foreground"
              role="alert"
              data-testid="project-form-error"
              data-error-code={projectErrorCode(error)}
            >
              {projectErrorMessage(error)}
            </p>
          ) : null}

          <DialogFooter>
            <Button type="button" variant="outline" size="sm" onClick={onClose}>
              Cancel
            </Button>
            <Button
              type="submit"
              size="sm"
              disabled={status === 'pending'}
              data-testid="project-form-submit"
            >
              {status === 'pending' ? 'Saving…' : isEdit ? 'Save changes' : 'Create project'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
