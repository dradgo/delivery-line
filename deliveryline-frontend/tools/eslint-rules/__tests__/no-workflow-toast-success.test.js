// Story 2.21 (AC2) — fixture tests proving `no-workflow-toast-success` flags a
// workflow-significant toast in a mutation-hook-importing file and leaves
// ancillary toasts (and any toast in a non-mutation file) alone.
// RuleTester + node --test (mirrors no-inline-query-keys.test.js).
import test from 'node:test';
import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import rule from '../no-workflow-toast-success.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: 2023,
    sourceType: 'module',
  },
});

test('no-workflow-toast-success', () => {
  ruleTester.run('no-workflow-toast-success', rule, {
    valid: [
      // Ancillary toast.info in a mutation file — allowed.
      {
        code: [
          "import { toast } from 'sonner';",
          "import { useApproveSpec } from '@/features/workflows/hooks/useApproveSpec';",
          'export function C() {',
          '  useApproveSpec();',
          "  toast.info('Copied to clipboard');",
          '  return null;',
          '}',
        ].join('\n'),
      },
      // A type-only hook import cannot execute a mutation and must not classify the file.
      {
        code: [
          "import { toast } from 'sonner';",
          "import type { useApproveSpec } from '@/features/workflows/hooks/useApproveSpec';",
          "toast.success('Saved');",
        ].join('\n'),
      },
      // An unrelated symbol with the same name is not the shared feedback wrapper.
      {
        code: [
          "import { feedbackToast } from 'unrelated-package';",
          "import { useApproveSpec } from '@/features/workflows/hooks/useApproveSpec';",
          "feedbackToast.success('Package-specific signal');",
        ].join('\n'),
      },
      // feedbackToast.info in a mutation file — allowed.
      {
        code: [
          "import { feedbackToast } from '@/components/feedback';",
          "import { useApproveSpec } from '@/features/workflows/hooks/useApproveSpec';",
          'export function C() {',
          '  useApproveSpec();',
          "  feedbackToast.info('Link copied');",
          '  return null;',
          '}',
        ].join('\n'),
      },
      // toast.loading / toast.message in a mutation file — ancillary, allowed.
      {
        code: [
          "import { toast } from 'sonner';",
          "import { useWorkflowMutation } from '@/features/workflows/hooks/useWorkflowMutation';",
          'export function C() {',
          '  useWorkflowMutation();',
          "  toast.loading('Working…');",
          "  toast.message('FYI');",
          '  return null;',
          '}',
        ].join('\n'),
      },
      // Any toast.success in a NON-mutation file — allowed (no mutation hook imported).
      {
        code: [
          "import { toast } from 'sonner';",
          'export function C() {',
          "  toast.success('Saved');",
          '  return null;',
          '}',
        ].join('\n'),
      },
      // feedbackToast.success in a NON-mutation file — allowed.
      {
        code: [
          "import { feedbackToast } from '@/components/feedback';",
          'export function C() {',
          "  feedbackToast.success('Done');",
          '  return null;',
          '}',
        ].join('\n'),
      },
    ],
    invalid: [
      // toast.success in a mutation file (imported by name) — forbidden.
      {
        code: [
          "import { toast } from 'sonner';",
          "import { useApproveSpec } from '@/features/workflows/hooks/useApproveSpec';",
          'export function C() {',
          '  useApproveSpec();',
          "  toast.success('Approved');",
          '  return null;',
          '}',
        ].join('\n'),
        errors: [{ messageId: 'workflowToast' }],
      },
      // The public dispatcher must not bypass the same forbidden-level policy.
      {
        code: [
          "import { emitFeedbackToast } from '@/components/feedback';",
          "import { useApproveSpec } from '@/features/workflows/hooks/useApproveSpec';",
          "emitFeedbackToast('success', 'Approved');",
        ].join('\n'),
        errors: [{ messageId: 'workflowToast' }],
      },
      // Bare toast(...) in a mutation file — forbidden.
      {
        code: [
          "import { toast } from 'sonner';",
          "import { useRejectSpec } from '@/features/workflows/hooks/useRejectSpec';",
          'export function C() {',
          '  useRejectSpec();',
          "  toast('Rejected');",
          '  return null;',
          '}',
        ].join('\n'),
        errors: [{ messageId: 'workflowToast' }],
      },
      // feedbackToast.success in a mutation file — forbidden.
      {
        code: [
          "import { feedbackToast } from '@/components/feedback';",
          "import { useSubmitClarification } from '@/features/workflows/hooks/useSubmitClarification';",
          'export function C() {',
          '  useSubmitClarification();',
          "  feedbackToast.success('Submitted');",
          '  return null;',
          '}',
        ].join('\n'),
        errors: [{ messageId: 'workflowToast' }],
      },
      // toast.error matched by the import-PATH matcher (default-imported hook name
      // not in the set, but the path is hooks/use*Clarification) — forbidden.
      {
        code: [
          "import { toast } from 'sonner';",
          "import { useDraftClarification } from '@/features/workflows/hooks/useDraftClarification';",
          'export function C() {',
          '  useDraftClarification();',
          "  toast.error('Failed');",
          '  return null;',
          '}',
        ].join('\n'),
        errors: [{ messageId: 'workflowToast' }],
      },
    ],
  });
});
