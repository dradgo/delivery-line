/**
 * Story 3c-9 (Task 9) — pure `projectFormView` helpers (validators, mappers, action
 * flags, error-message maps). Exhaustive because these are the credential/connector
 * adjacent helpers and the feature carries a coverage floor.
 */
import { describe, expect, it } from 'vitest';

import type { Project } from '@/lib/api/queryOptions';
import { ProblemDetailsError, toProblemDetails } from '@/lib/api/problemDetails';

import {
  connectionTestErrorMessage,
  credentialErrorMessage,
  credentialStatusFor,
  emptyProjectFormFields,
  isProjectFormValid,
  projectErrorCode,
  projectErrorMessage,
  resolveProjectActions,
  runnerKindOptions,
  RUNNER_USE_DEFAULT,
  toProjectFormFields,
  toWireRunnerKind,
  toWireStepRunnerKinds,
  validateProjectForm,
  type ProjectFormFields,
} from '../projectFormView';

const defaultRunnerFields = {
  runnerKind: RUNNER_USE_DEFAULT,
  stepRunnerKinds: {
    spec: RUNNER_USE_DEFAULT,
    implementationPlan: RUNNER_USE_DEFAULT,
    prOutput: RUNNER_USE_DEFAULT,
  },
};

function problem(code: string, status: number): ProblemDetailsError {
  return new ProblemDetailsError(
    toProblemDetails({ code, status, title: code, retryable: false }, status),
  );
}

const validFields: ProjectFormFields = {
  name: 'Acme',
  slug: 'acme',
  repositoryUrl: 'https://github.com/acme/widgets',
  ticketSourceKind: 'linear',
  repoHostKind: 'github',
  openspecEnabled: false,
  ...defaultRunnerFields,
};

describe('validateProjectForm', () => {
  it('accepts a fully valid create form', () => {
    const errors = validateProjectForm(validFields, { isEdit: false });
    expect(isProjectFormValid(errors)).toBe(true);
  });

  it('requires name and slug on create', () => {
    const errors = validateProjectForm({ ...validFields, name: ' ', slug: '' }, { isEdit: false });
    expect(errors.name).toMatch(/required/);
    expect(errors.slug).toMatch(/required/);
  });

  it('does not validate slug on edit (slug is immutable)', () => {
    const errors = validateProjectForm({ ...validFields, slug: '' }, { isEdit: true });
    expect(errors.slug).toBeUndefined();
  });

  it('enforces the name + repo URL length ceilings', () => {
    const errors = validateProjectForm(
      { ...validFields, name: 'a'.repeat(257), repositoryUrl: 'b'.repeat(2049) },
      { isEdit: false },
    );
    expect(errors.name).toMatch(/256/);
    expect(errors.repositoryUrl).toMatch(/2048/);
  });

  it('rejects unknown connector kinds', () => {
    const errors = validateProjectForm(
      { ...validFields, ticketSourceKind: 'jira', repoHostKind: 'bitbucket' },
      { isEdit: false },
    );
    expect(errors.ticketSourceKind).toBeDefined();
    expect(errors.repoHostKind).toBeDefined();
  });
});

describe('emptyProjectFormFields', () => {
  it('defaults to the first connector kinds and OpenSpec off', () => {
    expect(emptyProjectFormFields()).toEqual({
      name: '',
      slug: '',
      repositoryUrl: '',
      ticketSourceKind: 'linear',
      repoHostKind: 'github',
      openspecEnabled: false,
      ...defaultRunnerFields,
    });
  });
});

describe('toProjectFormFields', () => {
  it('maps a full project', () => {
    const project: Project = {
      name: 'Acme',
      slug: 'acme',
      repositoryUrl: 'https://x',
      ticketSourceKind: 'github',
      repoHostKind: 'gitlab',
      openspecEnabled: true,
    };
    expect(toProjectFormFields(project)).toEqual({
      name: 'Acme',
      slug: 'acme',
      repositoryUrl: 'https://x',
      ticketSourceKind: 'github',
      repoHostKind: 'gitlab',
      openspecEnabled: true,
      ...defaultRunnerFields,
    });
  });

  it('prefills runnerKind + per-step mapping from the project', () => {
    const fields = toProjectFormFields({
      name: 'X',
      runnerKind: 'claude',
      stepRunnerKinds: { spec: 'codex', prOutput: 'manual' },
    });
    expect(fields.runnerKind).toBe('claude');
    expect(fields.stepRunnerKinds.spec).toBe('codex');
    expect(fields.stepRunnerKinds.prOutput).toBe('manual');
    // An unmapped step degrades to the "use default" sentinel.
    expect(fields.stepRunnerKinds.implementationPlan).toBe(RUNNER_USE_DEFAULT);
  });

  it('degrades nullable/absent fields to defaults', () => {
    const fields = toProjectFormFields({ name: 'X', repositoryUrl: null });
    expect(fields.repositoryUrl).toBe('');
    expect(fields.slug).toBe('');
    expect(fields.ticketSourceKind).toBe('linear');
    expect(fields.repoHostKind).toBe('github');
    expect(fields.openspecEnabled).toBe(false);
  });
});

describe('runner mapping helpers (story 3e-4)', () => {
  it('runnerKindOptions leads with the sentinel + the three known kinds', () => {
    const options = runnerKindOptions('Use global default');
    expect(options[0]).toEqual({ value: RUNNER_USE_DEFAULT, label: 'Use global default' });
    expect(options.map((option) => option.value)).toEqual([
      RUNNER_USE_DEFAULT,
      'codex',
      'claude',
      'manual',
    ]);
  });

  it('runnerKindOptions appends an unknown current value (forward drift)', () => {
    const options = runnerKindOptions('Use project default', 'gpt-9000');
    expect(options.at(-1)).toEqual({ value: 'gpt-9000', label: 'gpt-9000' });
  });

  it('toWireRunnerKind nulls the sentinel and passes a real kind through', () => {
    expect(toWireRunnerKind(RUNNER_USE_DEFAULT)).toBeNull();
    expect(toWireRunnerKind('')).toBeNull();
    expect(toWireRunnerKind('manual')).toBe('manual');
  });

  it('toWireStepRunnerKinds omits "use default" steps and keeps bound ones', () => {
    expect(
      toWireStepRunnerKinds({
        spec: 'codex',
        implementationPlan: RUNNER_USE_DEFAULT,
        prOutput: 'manual',
      }),
    ).toEqual({ spec: 'codex', prOutput: 'manual' });
    expect(
      toWireStepRunnerKinds({
        spec: RUNNER_USE_DEFAULT,
        implementationPlan: RUNNER_USE_DEFAULT,
        prOutput: RUNNER_USE_DEFAULT,
      }),
    ).toEqual({});
  });
});

describe('resolveProjectActions', () => {
  it('maps known action strings to flags', () => {
    expect(resolveProjectActions(['edit', 'disable', 'set_credential', 'test_connection'])).toEqual(
      {
        canEdit: true,
        canDisable: true,
        canEnable: false,
        canSetCredential: true,
        canTest: true,
      },
    );
  });

  it('ignores unknown actions and tolerates undefined', () => {
    expect(resolveProjectActions(['enable', 'frobnicate'])).toMatchObject({
      canEnable: true,
      canDisable: false,
    });
    expect(resolveProjectActions(undefined)).toEqual({
      canEdit: false,
      canDisable: false,
      canEnable: false,
      canSetCredential: false,
      canTest: false,
    });
  });
});

describe('credentialStatusFor', () => {
  const project: Project = {
    credentials: [
      { role: 'ticket_source', status: 'configured' },
      { role: 'repo_host', status: 'not_configured' },
    ],
  };
  it('reads a configured role', () => {
    expect(credentialStatusFor(project, 'ticket_source')).toBe('configured');
  });
  it('reads a not-configured role', () => {
    expect(credentialStatusFor(project, 'repo_host')).toBe('not_configured');
  });
  it('defaults a missing role to not-configured', () => {
    expect(credentialStatusFor({}, 'ticket_source')).toBe('not_configured');
  });
});

describe('error mapping', () => {
  it('projectErrorCode distinguishes problem vs transport', () => {
    expect(projectErrorCode(problem('PROJECT_NOT_FOUND', 404))).toBe('PROJECT_NOT_FOUND');
    expect(projectErrorCode(new Error('boom'))).toBe('transport');
  });

  it('projectErrorMessage maps each known code + falls back', () => {
    expect(projectErrorMessage(problem('PROJECT_SLUG_CONFLICT', 409))).toMatch(/slug/i);
    expect(projectErrorMessage(problem('UNKNOWN_REGISTRY_VALUE', 400))).toMatch(/connector/i);
    expect(projectErrorMessage(problem('INVALID_COMMAND_PAYLOAD', 400))).toMatch(/fields/i);
    expect(projectErrorMessage(problem('PROJECT_NOT_FOUND', 404))).toMatch(/not be found/i);
    expect(projectErrorMessage(problem('IDEMPOTENCY_KEY_CONFLICT', 409))).toMatch(/already/i);
    expect(projectErrorMessage(problem('SOMETHING_ELSE', 500))).toMatch(/went wrong/i);
    expect(projectErrorMessage(new Error('x'))).toMatch(/reach the server/i);
  });

  it('credentialErrorMessage adds the master-key + role cases', () => {
    expect(credentialErrorMessage(problem('CREDENTIAL_MASTER_KEY_UNCONFIGURED', 503))).toMatch(
      /master key/i,
    );
    expect(credentialErrorMessage(problem('UNKNOWN_REGISTRY_VALUE', 400))).toMatch(/role/i);
    expect(credentialErrorMessage(problem('MISSING_IDEMPOTENCY_KEY', 400))).toMatch(/idempotency/i);
    // Falls through to the shared mapping for other codes.
    expect(credentialErrorMessage(problem('PROJECT_NOT_FOUND', 404))).toMatch(/not be found/i);
  });

  it('connectionTestErrorMessage maps the two surfacing codes + falls back', () => {
    expect(connectionTestErrorMessage(problem('UNSUPPORTED_CONNECTOR_KIND', 400))).toMatch(
      /adapter/i,
    );
    expect(connectionTestErrorMessage(problem('PROJECT_NOT_FOUND', 404))).toMatch(/not be found/i);
    expect(connectionTestErrorMessage(problem('INTERNAL_ERROR', 500))).toMatch(/went wrong/i);
  });
});
