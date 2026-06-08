/**
 * Story 2.21 (AC2) — `no-workflow-toast-success`.
 *
 * UX-DR15: a workflow-significant outcome is NEVER communicated by toast alone —
 * "answer incorporated", "decision recorded", "run blocked/failed" must surface
 * in-region via `<InlineFeedback>`/`<PersistentStateBadge>`, not a transient
 * toast that auto-dismisses. This rule enforces that boundary mechanically.
 *
 * MATCHER (OQ-4, conservative — false-negatives over false-positives):
 *  - A file is a "workflow-mutation file" if it imports a mutation hook by name
 *    (`useApproveSpec` / `useRejectSpec` / `useSubmitClarification` /
 *    `useWorkflowMutation`) OR from an import path matching `hooks/use*Spec` or
 *    `hooks/use*Clarification`.
 *  - Inside such a file, a `toast.success` / `toast.error` / `toast.warning` /
 *    bare `toast(...)` (from `sonner`) or a `feedbackToast.success/error/warning`
 *    is an ERROR.
 *  - ALLOWED everywhere: `toast.info` / `toast.message` / `toast.loading` /
 *    `feedbackToast.info` (lightweight ancillary confirmations).
 *  - ALLOWED in any NON-mutation file: any toast call.
 *
 * ESCAPE HATCH for a rare sanctioned ancillary case in a mutation file:
 *   // eslint-disable-next-line local-rules/no-workflow-toast-success -- <rationale>
 */

const MUTATION_HOOK_NAMES = new Set([
  'useApproveSpec',
  'useRejectSpec',
  'useSubmitClarification',
  'useWorkflowMutation',
]);

/** Import-path matcher for `hooks/use*Spec` / `hooks/use*Clarification`. */
const MUTATION_HOOK_PATH = /hooks\/use\w*(Spec|Clarification)$/;

/** The toast levels that signal an outcome (forbidden in a mutation file). */
const FORBIDDEN_LEVELS = new Set(['success', 'error', 'warning']);

const FEEDBACK_MODULE_SOURCES = new Set([
  '@/components/feedback',
  '@/components/feedback/primitives',
  '@/components/feedback/primitives/feedbackToast',
]);

/** @type {import('eslint').Rule.RuleModule} */
const rule = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Disallow signalling a workflow-significant outcome by toast alone (UX-DR15); use <InlineFeedback>/<PersistentStateBadge> for workflow state and reserve toast.info/feedbackToast.info for lightweight ancillary confirmations.',
    },
    schema: [],
    messages: {
      workflowToast:
        "Workflow-significant outcomes must not be signalled by toast alone (UX-DR15). Use <InlineFeedback>/<PersistentStateBadge> in the region where the user acted; reserve toast.info / feedbackToast.info for lightweight ancillary confirmations. If this is a sanctioned ancillary case, add '// eslint-disable-next-line local-rules/no-workflow-toast-success -- <rationale>'.",
    },
  },
  create(context) {
    let importsMutationHook = false;
    /** Local identifiers bound to sonner's `toast`. */
    const sonnerToastLocals = new Set();
    /** Local identifiers bound to the `feedbackToast` wrapper. */
    const feedbackToastLocals = new Set();
    /** Local identifiers bound to the wrapper's low-level dispatcher. */
    const emitFeedbackToastLocals = new Set();
    /** @type {import('estree').Node[]} */
    const flagged = [];

    /**
     * @param {import('estree').Node} node
     * @returns {string | undefined}
     */
    function calleePropertyName(node) {
      if (
        node.type === 'MemberExpression' &&
        !node.computed &&
        node.property.type === 'Identifier'
      ) {
        return node.property.name;
      }
      return undefined;
    }

    return {
      ImportDeclaration(node) {
        const source = typeof node.source.value === 'string' ? node.source.value : '';
        const declarationIsTypeOnly = node.importKind === 'type';
        const hasValueImport =
          !declarationIsTypeOnly &&
          node.specifiers.some((spec) => !('importKind' in spec) || spec.importKind !== 'type');
        const pathIsMutationHook = hasValueImport && MUTATION_HOOK_PATH.test(source);
        for (const spec of node.specifiers) {
          if (declarationIsTypeOnly || ('importKind' in spec && spec.importKind === 'type')) {
            continue;
          }
          if (spec.type === 'ImportSpecifier' && spec.imported.type === 'Identifier') {
            const importedName = spec.imported.name;
            if (MUTATION_HOOK_NAMES.has(importedName)) {
              importsMutationHook = true;
            }
            if (importedName === 'toast' && source === 'sonner') {
              sonnerToastLocals.add(spec.local.name);
            }
            if (importedName === 'feedbackToast' && FEEDBACK_MODULE_SOURCES.has(source)) {
              feedbackToastLocals.add(spec.local.name);
            }
            if (importedName === 'emitFeedbackToast' && FEEDBACK_MODULE_SOURCES.has(source)) {
              emitFeedbackToastLocals.add(spec.local.name);
            }
          }
        }
        if (pathIsMutationHook) {
          importsMutationHook = true;
        }
      },

      CallExpression(node) {
        const callee = node.callee;

        // Bare `toast(...)` where `toast` is sonner's import.
        if (callee.type === 'Identifier' && sonnerToastLocals.has(callee.name)) {
          flagged.push(node);
          return;
        }
        if (callee.type === 'Identifier' && emitFeedbackToastLocals.has(callee.name)) {
          const variant = node.arguments[0];
          if (variant === undefined || variant.type !== 'Literal' || variant.value !== 'info') {
            flagged.push(node);
          }
          return;
        }

        if (callee.type !== 'MemberExpression' || callee.object.type !== 'Identifier') {
          return;
        }
        const objectName = callee.object.name;
        const property = calleePropertyName(callee);
        if (property === undefined) {
          return;
        }

        // `toast.success|error|warning` (sonner) or `feedbackToast.success|error|warning`.
        const isSonnerToast = sonnerToastLocals.has(objectName);
        const isFeedbackToast = feedbackToastLocals.has(objectName);
        if ((isSonnerToast || isFeedbackToast) && FORBIDDEN_LEVELS.has(property)) {
          flagged.push(node);
        }
      },

      'Program:exit'() {
        if (!importsMutationHook) {
          return;
        }
        for (const node of flagged) {
          context.report({ node, messageId: 'workflowToast' });
        }
      },
    };
  },
};

export default rule;
