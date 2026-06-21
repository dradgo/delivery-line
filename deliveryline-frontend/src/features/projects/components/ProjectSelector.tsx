/**
 * Story 3c-9 (Task 6, AC6 — reconciled R3) — the project selector seam.
 *
 * Reads `useProjectsList`. When ≤1 project exists (the live single-project pilot) it
 * COLLAPSES to a static label; with ≥2 projects it presents a selection control.
 *
 * RECONCILED (R3 / Open Decision #2): the backend run-read surface carries no project
 * field and `GET /api/v1/workflows` has no `projectId` filter, so the queue-filter
 * wire-through + per-run attribution badge are OUT of scope. This selector is the
 * consumable seam only — it holds its own selection state and is NOT wired to mutate
 * the queue query. The optional `onSelect` prop is forward-compat for that follow-up.
 */
import { useState } from 'react';

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useProjectsList } from '../hooks/useProjectsList';

export interface ProjectSelectorProps {
  /** Forward-compat (R3): notified on selection; ProjectsScreen passes nothing today. */
  onSelect?: (projectId: string) => void;
}

export function ProjectSelector({ onSelect }: ProjectSelectorProps) {
  const { data } = useProjectsList();
  const projects = data ?? [];
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);

  if (projects.length === 0) {
    return null;
  }

  // Collapse-to-label: the live single-project state today.
  if (projects.length === 1) {
    const only = projects[0];
    return (
      <span
        className="inline-flex items-center gap-1.5 text-sm"
        data-testid="project-selector"
        data-selector-mode="collapsed"
      >
        <span className="text-text-secondary">Project:</span>
        <span className="font-medium text-text-primary">{only?.name ?? only?.slug ?? '—'}</span>
      </span>
    );
  }

  const current = selectedId ?? projects[0]?.id ?? '';
  return (
    <div
      className="flex items-center gap-2"
      data-testid="project-selector"
      data-selector-mode="expanded"
    >
      <span className="text-sm text-text-secondary" id="project-selector-label">
        Project:
      </span>
      <Select
        value={current}
        onValueChange={(value) => {
          setSelectedId(value);
          onSelect?.(value);
        }}
      >
        <SelectTrigger
          aria-labelledby="project-selector-label"
          className="w-56"
          data-testid="project-selector-trigger"
        >
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {projects.map((project) => (
            <SelectItem key={project.id ?? project.slug} value={project.id ?? ''}>
              {project.name ?? project.slug ?? project.id}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
