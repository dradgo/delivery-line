/**
 * Project selector seam.
 *
 * Existing project settings screens use it uncontrolled: one project collapses to a label and
 * multiple projects default to the first configured project. The workflow queue uses it in
 * controlled mode with an explicit "All projects" option so the URL remains the filter source.
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

const ALL_PROJECTS_VALUE = '__all_projects__';

export interface ProjectSelectorProps {
  /** Legacy notification for uncontrolled project selection. */
  onSelect?: (projectId: string) => void;
  /** Controlled selected project id. Undefined means all projects when includeAllOption is true. */
  value?: string | undefined;
  /** Controlled change notification. Undefined means all projects / no filter. */
  onChange?: (projectId: string | undefined) => void;
  /** Include an explicit all-projects option; used by the review queue filter. */
  includeAllOption?: boolean | undefined;
  /** Visible label for the selector. */
  label?: string | undefined;
  /** Preserve the existing single-project collapsed label unless explicitly disabled. */
  collapseWhenSingle?: boolean | undefined;
}

export function ProjectSelector({
  onSelect,
  value,
  onChange,
  includeAllOption = false,
  label = 'Project:',
  collapseWhenSingle = true,
}: ProjectSelectorProps) {
  const { data } = useProjectsList();
  const projects = data ?? [];
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);

  // Only projects with a usable identity can back a <SelectItem>; an empty value is rejected by
  // Radix at runtime, so drop those rather than emit `value=""` (story 3f-6 review finding).
  const selectableProjects = projects.filter((project) => (project.id ?? project.slug) != null);

  // The all-projects filter mode keeps a clear path even while the project list is loading/empty,
  // so a URL `projectId` is never stranded without a control to reset it (story 3f-6).
  if (selectableProjects.length === 0 && !includeAllOption) {
    return null;
  }

  if (selectableProjects.length === 1 && collapseWhenSingle && !includeAllOption) {
    const only = selectableProjects[0];
    return (
      <span
        className="inline-flex items-center gap-1.5 text-sm"
        data-testid="project-selector"
        data-selector-mode="collapsed"
      >
        <span className="text-text-secondary">{label}</span>
        <span className="font-medium text-text-primary">{only?.name ?? only?.slug ?? '-'}</span>
      </span>
    );
  }

  const itemValueFor = (project: (typeof selectableProjects)[number]) =>
    (project.id ?? project.slug) as string;

  // A blank or stale controlled value (e.g. a slug-valued deep link or a deleted project that no
  // longer appears in the list) must not render a blank trigger. Reconcile to the explicit
  // all-projects option instead of leaving Radix on its empty sentinel (story 3f-6 review finding).
  const trimmedValue = typeof value === 'string' ? value.trim() : '';
  const matchesKnownProject = selectableProjects.some(
    (project) => itemValueFor(project) === trimmedValue,
  );
  const current = includeAllOption
    ? trimmedValue !== '' && matchesKnownProject
      ? trimmedValue
      : ALL_PROJECTS_VALUE
    : (value ?? selectedId ?? selectableProjects[0]?.id ?? selectableProjects[0]?.slug ?? '');
  return (
    <div
      className="flex items-center gap-2"
      data-testid="project-selector"
      data-selector-mode="expanded"
    >
      <span className="text-sm text-text-secondary" id="project-selector-label">
        {label}
      </span>
      <Select
        value={current}
        onValueChange={(nextValue) => {
          const next = nextValue === ALL_PROJECTS_VALUE ? undefined : nextValue;
          setSelectedId(next);
          onChange?.(next);
          if (next !== undefined) {
            onSelect?.(next);
          }
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
          {includeAllOption ? (
            <SelectItem value={ALL_PROJECTS_VALUE}>All projects</SelectItem>
          ) : null}
          {selectableProjects.map((project) => (
            <SelectItem key={project.id ?? project.slug} value={itemValueFor(project)}>
              {project.name ?? project.slug ?? project.id}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
