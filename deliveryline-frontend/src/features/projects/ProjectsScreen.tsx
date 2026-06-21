/**
 * Story 3c-9 (Task 6, AC1/AC6/AC9) — the Projects management screen.
 *
 * The settings/configuration surface (distinct from the queue/run views) that
 * consumes the governed `/api/v1/projects` REST contract. Composes the project
 * selector (collapse-to-label seam, R3), a single primary "New project" action, the
 * project list, and the create/edit form Dialog (Open Decision #1). Holds the
 * session-scoped connectivity-test results (R4 — the backend persists no test history).
 */
import { useState } from 'react';

import { Button } from '@/components/ui/button';
import { Container, Stack } from '@/components/layout';
import type { Project } from '@/lib/api/queryOptions';

import { ProjectForm, type ProjectFormMode } from './components/ProjectForm';
import { ProjectList, type ProjectTestResult } from './components/ProjectList';
import { ProjectSelector } from './components/ProjectSelector';
import type { TestConnection } from './hooks/useTestProjectConnection';

export function ProjectsScreen() {
  const [formMode, setFormMode] = useState<ProjectFormMode | null>(null);
  const [testResults, setTestResults] = useState<Record<string, ProjectTestResult>>({});

  const recordTestResult = (projectId: string, result: TestConnection) => {
    setTestResults((prev) => ({
      ...prev,
      [projectId]: { result, testedAt: new Date().toISOString() },
    }));
  };

  // A fresh key per open re-seeds the form's useState (create-blank vs edit-prefill).
  const formKey =
    formMode === null
      ? 'none'
      : formMode.kind === 'edit'
        ? `edit-${formMode.project.id ?? ''}`
        : 'create';

  return (
    <Container className="px-0">
      <Stack gap="6">
        <div className="flex flex-col gap-1">
          <h1 className="text-page-title">Projects</h1>
          <p className="text-sm text-text-secondary">
            Configure projects, credential their connectors, and test connectivity. Available
            actions reflect each project’s current status.
          </p>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3">
          <ProjectSelector />
          <Button
            type="button"
            onClick={() => setFormMode({ kind: 'create' })}
            data-testid="project-new-button"
          >
            New project
          </Button>
        </div>

        <ProjectList
          onEdit={(project: Project) => setFormMode({ kind: 'edit', project })}
          testResults={testResults}
          onTestResult={recordTestResult}
        />
      </Stack>

      {formMode !== null ? (
        <ProjectForm key={formKey} mode={formMode} open onClose={() => setFormMode(null)} />
      ) : null}
    </Container>
  );
}
