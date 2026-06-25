/**
 * Story 3c-9 (Task 6/7, AC3/AC4/AC5/AC7) — pure view helpers for the Projects UI.
 *
 * Kept as a `.ts` (no JSX) so its non-component exports don't trip the
 * `react-refresh/only-export-components` rule a feature `.tsx` enforces (memory:
 * [[frontend-react-refresh-no-fn-exports]]). Holds the form validators, the
 * `Project` → form-field + allowed-actions view-model mappers, the connector/role/
 * check vocabularies, and the typed error-code → message maps. Mirrors
 * `submitRunView.ts`.
 */
import { isProblemDetailsError } from '@/lib/api/problemDetails';
import type { Project } from '@/lib/api/queryOptions';

// ---- Backend field ceilings (mirror the OpenAPI constraints) -------------------

/** `name`/`slug` `@Size(max=256)`. */
export const MAX_NAME_LENGTH = 256;
/** `repositoryUrl` `@Size(max=2048)`. */
export const MAX_REPO_URL_LENGTH = 2048;

// ---- Connector-kind vocabulary (Open Decision #3) ------------------------------

/**
 * Frontend constant mirroring the backend `ConnectorKind` registry (there is no
 * GET-kinds endpoint). DRIFT RISK: if the registry adds a kind, update this list —
 * no automated gate covers it. Source of truth:
 * `org.dradgo.domain.registry.ConnectorKind` (kinds: linear, github, gitlab).
 */
export const CONNECTOR_KINDS = ['linear', 'github', 'gitlab'] as const;
export type ConnectorKind = (typeof CONNECTOR_KINDS)[number];

/** Human labels for each connector kind (the wire value stays the lowercase id). */
export const CONNECTOR_KIND_LABELS: Record<ConnectorKind, string> = {
  linear: 'Linear',
  github: 'GitHub',
  gitlab: 'GitLab',
};

const CONNECTOR_KIND_SET = new Set<string>(CONNECTOR_KINDS);

/** A connector-kind `Select` option (the wire value + its display label). */
export interface ConnectorKindOption {
  value: string;
  label: string;
}

/**
 * The connector-kind options to render in a `Select`. Always the known kinds; plus,
 * when `current` is a non-empty value the frontend list does NOT know (a project
 * whose kind the backend registry added out-of-band — the documented drift risk),
 * the current value is appended verbatim so the edit form can display + preserve it
 * instead of showing a blank trigger and locking the project out of editing.
 */
export function connectorKindOptions(current: string): ConnectorKindOption[] {
  const base: ConnectorKindOption[] = CONNECTOR_KINDS.map((kind) => ({
    value: kind,
    label: CONNECTOR_KIND_LABELS[kind],
  }));
  if (current !== '' && !CONNECTOR_KIND_SET.has(current)) {
    return [...base, { value: current, label: current }];
  }
  return base;
}

// ---- Connector-role vocabulary (write-only credentials) ------------------------

/** The two connector roles (underscored wire form), in display order. */
export const CONNECTOR_ROLES = ['ticket_source', 'repo_host'] as const;
export type ConnectorRole = (typeof CONNECTOR_ROLES)[number];

/** Human labels for each credential role. */
export const CONNECTOR_ROLE_LABELS: Record<ConnectorRole, string> = {
  ticket_source: 'Ticket source',
  repo_host: 'Repository host',
};

// ---- Connection-check vocabulary ----------------------------------------------

/** The three connectivity checks `testProjectConnection` reports, in display order. */
export const CONNECTION_CHECKS = [
  'repository_reachable',
  'ticket_source_auth',
  'repository_host_auth',
] as const;
export type ConnectionCheck = (typeof CONNECTION_CHECKS)[number];

/** Human labels for each check (the wire value stays the underscored id). */
export const CONNECTION_CHECK_LABELS: Record<ConnectionCheck, string> = {
  repository_reachable: 'Repository reachable',
  ticket_source_auth: 'Ticket-source authentication',
  repository_host_auth: 'Repository-host authentication',
};

// ---- Runner-kind + per-step vocabulary (story 3e-4) ----------------------------

/**
 * Frontend constant mirroring the backend `RunnerKind` registry (codex/claude/manual).
 * DRIFT RISK: if the registry adds a kind, update this list — no automated gate covers
 * it. Source of truth: `org.dradgo.domain.registry.RunnerKind`.
 */
export const RUNNER_KINDS = ['codex', 'claude', 'manual'] as const;
export type RunnerKind = (typeof RUNNER_KINDS)[number];

/** Human labels for each runner kind (the wire value stays the lowercase id). */
export const RUNNER_KIND_LABELS: Record<RunnerKind, string> = {
  codex: 'Codex',
  claude: 'Claude',
  manual: 'Manual',
};

const RUNNER_KIND_SET = new Set<string>(RUNNER_KINDS);

/**
 * Sentinel `Select` value meaning "no explicit binding — fall through to the next-broader
 * default". Radix `Select` forbids an empty-string item value, so this stands in for the
 * cleared state in form state; the wire mappers translate it back to omitted/null.
 */
export const RUNNER_USE_DEFAULT = '__default__';

/**
 * The per-step runner mapping steps (story 3e-4), in display order. Wire values mirror the
 * backend `ProjectRunnerStep` registry (spec/implementationPlan/prOutput).
 */
export const RUNNER_STEPS = ['spec', 'implementationPlan', 'prOutput'] as const;
export type RunnerStep = (typeof RUNNER_STEPS)[number];

/** Human labels for each per-step runner control. */
export const RUNNER_STEP_LABELS: Record<RunnerStep, string> = {
  spec: 'Spec',
  implementationPlan: 'Implementation plan',
  prOutput: 'PR output',
};

/** A runner-kind `Select` option (the wire value + its display label). */
export interface RunnerKindOption {
  value: string;
  label: string;
}

/**
 * Options for a runner-kind `Select`: the "use default" sentinel first (its label differs
 * between the project-wide default control and the per-step controls), then the known
 * kinds. When `current` is a value the frontend list does NOT know (forward-registry
 * drift), it is appended verbatim so an edit form preserves rather than silently drops it.
 */
export function runnerKindOptions(defaultLabel: string, current?: string): RunnerKindOption[] {
  const base: RunnerKindOption[] = [
    { value: RUNNER_USE_DEFAULT, label: defaultLabel },
    ...RUNNER_KINDS.map((kind) => ({ value: kind, label: RUNNER_KIND_LABELS[kind] })),
  ];
  if (
    current !== undefined &&
    current !== '' &&
    current !== RUNNER_USE_DEFAULT &&
    !RUNNER_KIND_SET.has(current)
  ) {
    return [...base, { value: current, label: current }];
  }
  return base;
}

// ---- Form fields + validation -------------------------------------------------

/** The values the create/edit form holds (all strings + the OpenSpec boolean). */
export interface ProjectFormFields {
  name: string;
  slug: string;
  repositoryUrl: string;
  ticketSourceKind: string;
  repoHostKind: string;
  openspecEnabled: boolean;
  /** Project-wide runner default (the 3d-3 override); `RUNNER_USE_DEFAULT` = use global. */
  runnerKind: string;
  /** Per-step runner mapping; each `RUNNER_USE_DEFAULT` = use the project-wide default. */
  stepRunnerKinds: Record<RunnerStep, string>;
}

/** Per-field validation messages — a field is omitted when valid. */
export interface ProjectFormErrors {
  name?: string;
  slug?: string;
  repositoryUrl?: string;
  ticketSourceKind?: string;
  repoHostKind?: string;
}

/** A blank create form (defaults: first connector kind, OpenSpec off, runners use defaults). */
export function emptyProjectFormFields(): ProjectFormFields {
  return {
    name: '',
    slug: '',
    repositoryUrl: '',
    ticketSourceKind: 'linear',
    repoHostKind: 'github',
    openspecEnabled: false,
    runnerKind: RUNNER_USE_DEFAULT,
    stepRunnerKinds: {
      spec: RUNNER_USE_DEFAULT,
      implementationPlan: RUNNER_USE_DEFAULT,
      prOutput: RUNNER_USE_DEFAULT,
    },
  };
}

function validateRequiredText(
  value: string,
  { label, max }: { label: string; max: number },
): string | undefined {
  if (value.trim().length === 0) {
    return `${label} is required.`;
  }
  if (value.length > max) {
    return `${label} must be ${max} characters or fewer.`;
  }
  return undefined;
}

/**
 * Client validation mirroring `CreateProjectRequest` / `UpdateProjectRequest` (AC3).
 * In edit mode `slug` is read-only (not in `UpdateProjectRequest`), so it is not
 * validated. BLOCKS submit + renders per-field messages; the server still validates
 * and surfaces typed `INVALID_COMMAND_PAYLOAD` / `UNKNOWN_REGISTRY_VALUE`.
 */
export function validateProjectForm(
  fields: ProjectFormFields,
  options: { isEdit: boolean },
): ProjectFormErrors {
  const errors: ProjectFormErrors = {};

  const name = validateRequiredText(fields.name, { label: 'Name', max: MAX_NAME_LENGTH });
  if (name !== undefined) {
    errors.name = name;
  }

  if (!options.isEdit) {
    const slug = validateRequiredText(fields.slug, { label: 'Slug', max: MAX_NAME_LENGTH });
    if (slug !== undefined) {
      errors.slug = slug;
    }
  }

  // Repository URL is OPTIONAL — only the length ceiling applies when present.
  if (fields.repositoryUrl.length > MAX_REPO_URL_LENGTH) {
    errors.repositoryUrl = `Repository URL must be ${MAX_REPO_URL_LENGTH} characters or fewer.`;
  }

  // In create mode the kind must be one the frontend knows (the Select only offers
  // those). In edit mode the kind was prefilled from a server-validated project, so a
  // value the frontend list lacks (forward-registry drift) must NOT block the edit —
  // require only non-empty; the server still validates `UNKNOWN_REGISTRY_VALUE`.
  if (options.isEdit) {
    if (fields.ticketSourceKind.trim().length === 0) {
      errors.ticketSourceKind = 'Select a ticket-source kind.';
    }
    if (fields.repoHostKind.trim().length === 0) {
      errors.repoHostKind = 'Select a repository-host kind.';
    }
  } else {
    if (!CONNECTOR_KIND_SET.has(fields.ticketSourceKind)) {
      errors.ticketSourceKind = 'Select a ticket-source kind.';
    }
    if (!CONNECTOR_KIND_SET.has(fields.repoHostKind)) {
      errors.repoHostKind = 'Select a repository-host kind.';
    }
  }

  return errors;
}

/** True when the field-error map is empty — the form may fire. */
export function isProjectFormValid(errors: ProjectFormErrors): boolean {
  return Object.keys(errors).length === 0;
}

/**
 * Map a `Project` into edit-form field values (AC3 prefill). `slug` is carried for
 * display even though it is read-only on edit; nullable fields degrade to '' (guard
 * `!= null` — a nullable wire field serializes as JSON null,
 * [[workflowdetail-wire-sends-null-not-undefined]]).
 */
export function toProjectFormFields(project: Project): ProjectFormFields {
  const stepMap = project.stepRunnerKinds ?? {};
  return {
    name: project.name ?? '',
    slug: project.slug ?? '',
    repositoryUrl: project.repositoryUrl ?? '',
    ticketSourceKind: project.ticketSourceKind ?? 'linear',
    repoHostKind: project.repoHostKind ?? 'github',
    openspecEnabled: project.openspecEnabled ?? false,
    // A nullable wire field serializes as JSON null → coalesce to the "use default" sentinel.
    runnerKind: project.runnerKind ?? RUNNER_USE_DEFAULT,
    stepRunnerKinds: {
      spec: stepMap.spec ?? RUNNER_USE_DEFAULT,
      implementationPlan: stepMap.implementationPlan ?? RUNNER_USE_DEFAULT,
      prOutput: stepMap.prOutput ?? RUNNER_USE_DEFAULT,
    },
  };
}

// ---- Runner wire mappers (story 3e-4) ------------------------------------------

/** The project-wide runner default for the wire — `null` when "use global default". */
export function toWireRunnerKind(runnerKind: string): string | null {
  return runnerKind === RUNNER_USE_DEFAULT || runnerKind === '' ? null : runnerKind;
}

/**
 * The per-step map for the wire — a step set to "use default" (`RUNNER_USE_DEFAULT`) is
 * omitted, so it falls through to the project-wide default then the global per-stage kind.
 * Always returns an object (possibly empty); on update the empty object full-replaces (clears)
 * any existing per-step mapping.
 */
export function toWireStepRunnerKinds(
  stepRunnerKinds: Record<RunnerStep, string>,
): Record<string, string> {
  const wire: Record<string, string> = {};
  for (const step of RUNNER_STEPS) {
    const value = stepRunnerKinds[step];
    if (value !== RUNNER_USE_DEFAULT && value !== '') {
      wire[step] = value;
    }
  }
  return wire;
}

// ---- Allowed-actions view-model (status-derived, NO role gating) ----------------

/** The control-enablement flags every Projects surface gates on (AC7). */
export interface ProjectActionFlags {
  canEdit: boolean;
  canDisable: boolean;
  canEnable: boolean;
  canSetCredential: boolean;
  canTest: boolean;
}

/**
 * Resolve `Project.allowedActions` (backend-derived from status; NO role dimension)
 * into per-control flags (AC7). Unknown action strings are ignored (forward-compat).
 * Backend action vocabulary: `edit`, `disable`, `enable`, `set_credential`,
 * `test_connection`.
 */
export function resolveProjectActions(allowedActions: string[] | undefined): ProjectActionFlags {
  const actions = new Set(allowedActions ?? []);
  return {
    canEdit: actions.has('edit'),
    canDisable: actions.has('disable'),
    canEnable: actions.has('enable'),
    canSetCredential: actions.has('set_credential'),
    canTest: actions.has('test_connection'),
  };
}

// ---- Credential presence -------------------------------------------------------

/** The presence status of a credential for a role, defaulting to not-configured. */
export function credentialStatusFor(
  project: Project,
  role: ConnectorRole,
): 'configured' | 'not_configured' {
  const entry = (project.credentials ?? []).find((credential) => credential.role === role);
  return entry?.status === 'configured' ? 'configured' : 'not_configured';
}

// ---- Typed error mapping (branch on code, never status text) --------------------

/** The stable error code the failure surface branches on — `'transport'` for non-problem+json. */
export function projectErrorCode(error: unknown): string {
  return isProblemDetailsError(error) ? error.code : 'transport';
}

/**
 * Map a project create/edit/disable/enable failure to a human message (AC3/AC7).
 * Branches ONLY on the typed `code` — never `error.message` or status text.
 */
export function projectErrorMessage(error: unknown): string {
  if (!isProblemDetailsError(error)) {
    return 'Could not reach the server. Check your connection and try again.';
  }
  switch (error.code) {
    case 'PROJECT_SLUG_CONFLICT':
      return 'That slug is already in use. Choose a different slug.';
    case 'UNKNOWN_REGISTRY_VALUE':
      return 'One of the connector kinds is not recognised. Pick a supported option and try again.';
    case 'INVALID_COMMAND_PAYLOAD':
      return 'The server rejected one or more fields. Review your entries and try again.';
    case 'PROJECT_NOT_FOUND':
      return 'This project could not be found. It may have been removed — refresh the list.';
    case 'IDEMPOTENCY_KEY_CONFLICT':
      return 'This change was already submitted. Refresh the list to see the current state.';
    default:
      return 'Something went wrong. Try again, and report it if it persists.';
  }
}

/**
 * Map a set-credential failure to a human message (AC4). Adds the credential-specific
 * 503 master-key case on top of the shared mapping.
 */
export function credentialErrorMessage(error: unknown): string {
  if (isProblemDetailsError(error)) {
    switch (error.code) {
      case 'CREDENTIAL_MASTER_KEY_UNCONFIGURED':
        return 'Credentials cannot be stored: the server has no encryption master key configured. Contact your administrator.';
      case 'UNKNOWN_REGISTRY_VALUE':
        return 'That connector role is not recognised.';
      case 'MISSING_IDEMPOTENCY_KEY':
        return 'The request was missing its idempotency key. Try again.';
      default:
        break;
    }
  }
  return projectErrorMessage(error);
}

/**
 * Map a connection-test failure (only `PROJECT_NOT_FOUND` / `UNSUPPORTED_CONNECTOR_KIND`
 * surface as errors — per-check fail/skip is in-band data) (AC5).
 */
export function connectionTestErrorMessage(error: unknown): string {
  if (isProblemDetailsError(error)) {
    switch (error.code) {
      case 'UNSUPPORTED_CONNECTOR_KIND':
        return 'This project uses a connector kind with no registered adapter, so it cannot be tested.';
      case 'PROJECT_NOT_FOUND':
        return 'This project could not be found. It may have been removed — refresh the list.';
      default:
        break;
    }
  }
  return projectErrorMessage(error);
}
