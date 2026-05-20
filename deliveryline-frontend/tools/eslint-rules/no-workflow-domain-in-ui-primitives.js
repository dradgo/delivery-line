/**
 * Story 2.2 AC7 / Story 2.31 AC4 — `no-workflow-domain-in-ui-primitives`.
 *
 * shadcn/ui primitives under `src/components/ui/` must remain generic and
 * reusable: they may NOT import DeliveryLine workflow-domain code. This rule
 * flags any static, lazy, or type-only import whose source resolves into
 * `src/features/workflows/` (alias `@/features/workflows`, or a relative path
 * ending in `features/workflows[/...]`) from a file located under
 * `src/components/ui/`.
 *
 * Scoping note: the ESLint flat config restricts this rule to
 * `src/components/ui/**`, but the rule ALSO guards on the filename so it is
 * self-contained and testable via RuleTester (which sets `filename`).
 */

/** Matches a file path inside `src/components/ui/`. */
const UI_PRIMITIVE_FILE = /(^|[\\/])src[\\/]components[\\/]ui[\\/]/;

/**
 * Matches an import source pointing at the workflow-domain feature folder:
 * `@/features/workflows`, `src/features/workflows`, or any relative path
 * containing a `features/workflows` segment.
 */
const WORKFLOW_DOMAIN_SOURCE =
  /(^@\/features\/workflows)|((^|[\\/])features[\\/]workflows([\\/]|$))/;

/** @type {import('eslint').Rule.RuleModule} */
const rule = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Disallow importing workflow-domain code (src/features/workflows) inside shadcn/ui primitives (src/components/ui).',
    },
    schema: [],
    messages: {
      workflowImport:
        "UI primitive '{{file}}' must stay generic — it must not import workflow-domain code ('{{source}}'). Move domain logic into a composite under src/features/workflows or src/components, and keep src/components/ui/* reusable.",
    },
  },
  create(context) {
    const filename = context.filename ?? context.getFilename();
    if (!UI_PRIMITIVE_FILE.test(filename)) {
      return {};
    }
    /** @param {{ source: { value: unknown }, importKind?: string }} node */
    const check = (node) => {
      const source = node.source && node.source.value;
      if (typeof source === 'string' && WORKFLOW_DOMAIN_SOURCE.test(source)) {
        context.report({
          node: node.source,
          messageId: 'workflowImport',
          data: { file: filename.replace(/.*[\\/]src[\\/]/, 'src/'), source },
        });
      }
    };
    return {
      ImportDeclaration: check,
      // also catch `export ... from 'src/features/workflows/...'` re-exports
      ExportNamedDeclaration: (node) => node.source && check(node),
      ExportAllDeclaration: check,
      ImportExpression: (node) => check({ source: node.source }),
      TSImportType: (node) => check({ source: node.source }),
    };
  },
};

export default rule;
