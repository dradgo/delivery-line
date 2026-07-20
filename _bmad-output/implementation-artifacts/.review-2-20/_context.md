# Review context — Story 2.20 (Queue Shell States)

## Scope of the change (review ALL of these)

MODIFIED (see diff file `_bmad-output/implementation-artifacts/.review-2-20-current.diff`):
- deliveryline-frontend/src/routes/workflows/index.tsx

NEW files (review the WHOLE file as an addition):
- deliveryline-frontend/src/features/workflows/QueueShell.tsx
- deliveryline-frontend/src/features/workflows/queueState.ts
- deliveryline-frontend/src/features/workflows/queueErrorMessage.ts
- deliveryline-frontend/src/features/workflows/hooks/useWorkflowsList.ts
- deliveryline-frontend/src/components/ui/skeleton.tsx
- deliveryline-frontend/src/features/workflows/__tests__/QueueShell.test.tsx
- deliveryline-frontend/src/features/workflows/__tests__/queueState.test.ts
- deliveryline-frontend/src/features/workflows/__tests__/queueErrorMessage.test.ts
- deliveryline-frontend/src/features/workflows/hooks/useWorkflowsList.test.tsx

## Story spec (ACs + tasks + traps)
- _bmad-output/implementation-artifacts/2-20-queue-shell-states-loading-empty-filtered-empty-error.md

## Supporting context you may Read for contracts
- deliveryline-frontend/src/lib/queryKeys/workflowKeys.ts (WorkflowListFilters, workflowKeys.list)
- deliveryline-frontend/src/lib/api/queryOptions.ts (listQueryOptions, WorkflowSummary)
- deliveryline-frontend/src/lib/api/problemDetails.ts (isProblemDetailsError, DomainErrorCode)
- deliveryline-frontend/src/components/feedback/states/{EmptyState,ErrorState}.tsx (composed primitives; passive ErrorState => role="status"/aria-live="polite"; EmptyState/ErrorState expose data-testid + data-variant)
- deliveryline-frontend/src/styles/globals.css (Tailwind v4 `@import 'tailwindcss'` => preflight resets `ul { list-style:none }`)
- deliveryline-frontend/src/lib/sanitization (validateUrlScheme)

## CRITICAL environment note
This machine runs an RTK hook that CORRUPTS Bash-tool output (silently truncates/summarizes, e.g. `git diff` returns fabricated content). DO NOT use the Bash tool or git. Use ONLY native tools: Read, Grep, Glob. The diff file above was captured correctly via `rtk proxy` and is trustworthy.
