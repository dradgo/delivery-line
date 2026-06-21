/**
 * Story 3c-9 (Task 6, AC2/AC7/AC9) — the project list.
 *
 * Owns the list query (mirrors `QueueShell` owning `useWorkflowsList`) and resolves
 * exactly one of loading / empty / error / populated via the typed feedback states.
 * The populated table shows per project: name, status (icon+text, non-color), the
 * connector kinds, repository URL (em-dash empty state), per-role credential presence
 * + set/replace (`CredentialControl`), a session-scoped last-test summary (R4), and a
 * connection-test control — every action gated on `Project.allowedActions` (AC7).
 *
 * Each row is its own `ProjectRow` component so the per-row mutation hooks
 * (disable/enable) sit at a stable hook position.
 */
import { Ban, CircleCheck, CircleDashed } from 'lucide-react';

import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { EmptyState, ErrorState, LoadingState } from '@/components/feedback';
import type { Project } from '@/lib/api/queryOptions';

import { useDisableProject } from '../hooks/useDisableProject';
import { useEnableProject } from '../hooks/useEnableProject';
import { useProjectsList } from '../hooks/useProjectsList';
import type { TestConnection } from '../hooks/useTestProjectConnection';
import {
  CONNECTOR_KIND_LABELS,
  CONNECTOR_KINDS,
  CONNECTOR_ROLES,
  projectErrorMessage,
  resolveProjectActions,
  type ConnectorKind,
} from '../projectFormView';
import { ConnectionTestControl } from './ConnectionTestControl';
import { CredentialControl } from './CredentialControl';

/** A connectivity-test result the operator ran this session (R4 — not backend-persisted). */
export interface ProjectTestResult {
  result: TestConnection;
  testedAt: string;
}

export interface ProjectListProps {
  /** Open the create/edit form for a project. */
  onEdit: (project: Project) => void;
  /** Session-scoped last-test results keyed by project id (R4). */
  testResults: Record<string, ProjectTestResult>;
  /** Record a freshly-run connectivity result for a project (R4). */
  onTestResult: (projectId: string, result: TestConnection) => void;
}

function kindLabel(kind: string | undefined): string {
  if (kind === undefined || kind === '') {
    return '—';
  }
  // Only index the label map for a KNOWN kind; an unknown kind renders verbatim
  // (forward-compat if the backend registry adds a kind the frontend list lacks).
  return (CONNECTOR_KINDS as readonly string[]).includes(kind)
    ? CONNECTOR_KIND_LABELS[kind as ConnectorKind]
    : kind;
}

function StatusBadge({ status }: { status: Project['status'] }) {
  // Only the two known wire values map to a definite state; anything else (a missing
  // or future status) renders neutral "Unknown" rather than silently reading as Active.
  const known = status === 'active' ? 'active' : status === 'disabled' ? 'disabled' : 'unknown';
  const icon =
    known === 'active' ? (
      <CircleCheck className="size-4 shrink-0 text-state-success-foreground" aria-hidden />
    ) : known === 'disabled' ? (
      <Ban className="size-4 shrink-0 text-state-blocker-foreground" aria-hidden />
    ) : (
      <CircleDashed className="size-4 shrink-0 text-state-empty-foreground" aria-hidden />
    );
  const label = known === 'active' ? 'Active' : known === 'disabled' ? 'Disabled' : 'Unknown';
  return (
    <span className="inline-flex items-center gap-1.5 text-sm" data-project-status={known}>
      {icon}
      <span className="font-medium text-text-primary">{label}</span>
    </span>
  );
}

function LastTestCell({ entry }: { entry: ProjectTestResult | undefined }) {
  if (entry === undefined) {
    return <span className="text-text-tertiary">Not tested</span>;
  }
  const checks = entry.result.checks ?? [];
  const pass = checks.filter((check) => check.status === 'pass').length;
  const fail = checks.filter((check) => check.status === 'fail').length;
  const skip = checks.filter((check) => check.status === 'skipped').length;
  // `Date.toLocaleString()` does not throw on a bad input — it returns "Invalid Date".
  // Guard explicitly so a malformed timestamp falls back to the raw value.
  const parsed = new Date(entry.testedAt);
  const tested = Number.isNaN(parsed.getTime()) ? entry.testedAt : parsed.toLocaleString();
  return (
    <span className="flex flex-col text-meta text-text-secondary" data-testid="project-last-test">
      <span className="text-text-primary">
        {pass} passed, {fail} failed, {skip} skipped
      </span>
      <span>Tested {tested}</span>
    </span>
  );
}

function ProjectRow({
  project,
  onEdit,
  lastTest,
  onTestResult,
}: {
  project: Project;
  onEdit: (project: Project) => void;
  lastTest: ProjectTestResult | undefined;
  onTestResult: (projectId: string, result: TestConnection) => void;
}) {
  const projectId = project.id ?? '';
  const flags = resolveProjectActions(project.allowedActions);
  const disableProject = useDisableProject(projectId);
  const enableProject = useEnableProject(projectId);
  const repositoryUrl = project.repositoryUrl ?? '';

  return (
    <TableRow data-testid="project-row" data-project-id={projectId}>
      <TableCell className="align-top font-medium text-text-primary">
        <div className="flex flex-col">
          <span>{project.name ?? project.slug ?? projectId}</span>
          <span className="text-meta text-text-tertiary">{project.slug}</span>
        </div>
      </TableCell>
      <TableCell className="align-top">
        <StatusBadge status={project.status} />
      </TableCell>
      <TableCell className="align-top text-text-secondary">
        {kindLabel(project.ticketSourceKind)}
      </TableCell>
      <TableCell className="align-top text-text-secondary">
        {kindLabel(project.repoHostKind)}
      </TableCell>
      <TableCell className="align-top text-text-secondary">
        {repositoryUrl !== '' ? (
          <span className="break-all">{repositoryUrl}</span>
        ) : (
          <span className="text-text-tertiary" aria-label="No repository URL">
            —
          </span>
        )}
      </TableCell>
      <TableCell className="align-top">
        <div className="flex flex-col gap-2">
          {CONNECTOR_ROLES.map((role) => (
            <CredentialControl
              key={role}
              project={project}
              connectorRole={role}
              canSet={flags.canSetCredential}
            />
          ))}
        </div>
      </TableCell>
      <TableCell className="align-top">
        <div className="flex flex-col gap-2">
          <LastTestCell entry={lastTest} />
          <ConnectionTestControl
            project={project}
            canTest={flags.canTest}
            onResult={(result) => onTestResult(projectId, result)}
          />
        </div>
      </TableCell>
      <TableCell className="align-top">
        <div className="flex flex-col gap-2">
          {flags.canEdit ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => onEdit(project)}
              data-testid="project-edit-button"
            >
              Edit
            </Button>
          ) : null}
          {flags.canDisable ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={disableProject.status === 'pending'}
              onClick={() => disableProject.mutate({})}
              data-testid="project-disable-button"
            >
              {disableProject.status === 'pending' ? 'Disabling…' : 'Disable'}
            </Button>
          ) : null}
          {flags.canEnable ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={enableProject.status === 'pending'}
              onClick={() => enableProject.mutate({})}
              data-testid="project-enable-button"
            >
              {enableProject.status === 'pending' ? 'Enabling…' : 'Enable'}
            </Button>
          ) : null}
          {disableProject.isError || enableProject.isError ? (
            <p
              className="text-meta text-state-error-foreground"
              role="alert"
              data-testid="project-action-error"
            >
              {projectErrorMessage(
                disableProject.isError ? disableProject.error : enableProject.error,
              )}
            </p>
          ) : null}
        </div>
      </TableCell>
    </TableRow>
  );
}

export function ProjectList({ onEdit, testResults, onTestResult }: ProjectListProps) {
  const query = useProjectsList();
  const projects = query.data ?? [];

  if (query.isPending) {
    return (
      <div data-testid="project-list-loading">
        <LoadingState variant="fetchingData" message="Loading projects…" />
      </div>
    );
  }

  if (query.isError) {
    return (
      <ErrorState
        variant="failedRetrieval"
        urgency="passive"
        title="Couldn't load projects"
        message="Something went wrong loading the project list. If this keeps happening, report it to your administrator."
        nextAction={{ kind: 'Retry', onRetry: () => void query.refetch() }}
      />
    );
  }

  if (projects.length === 0) {
    return (
      <EmptyState
        variant="queue"
        title="No projects yet"
        message="No projects are configured yet. Create one to connect a ticket source and repository host."
      />
    );
  }

  return (
    <div data-testid="project-list">
      <Table data-testid="project-list-table">
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Ticket source</TableHead>
            <TableHead>Repository host</TableHead>
            <TableHead>Repository URL</TableHead>
            <TableHead>Credentials</TableHead>
            <TableHead>Connection</TableHead>
            <TableHead>Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {projects.map((project) => (
            <ProjectRow
              key={project.id ?? project.slug}
              project={project}
              onEdit={onEdit}
              lastTest={project.id !== undefined ? testResults[project.id] : undefined}
              onTestResult={onTestResult}
            />
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
