// Story 2.31 AC11 — fixture tests proving `no-workflow-domain-in-ui-primitives`
// catches violations. Uses ESLint RuleTester with in-memory fixtures (NOT a
// committed failing .tsx file, which would make `npm run lint` permanently
// red). Run via `npm run lint:rules-test` (node --test). Vitest arrives in 2.27.
import test from 'node:test';
import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import rule from '../no-workflow-domain-in-ui-primitives.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: 2023,
    sourceType: 'module',
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

test('no-workflow-domain-in-ui-primitives', () => {
  ruleTester.run('no-workflow-domain-in-ui-primitives', rule, {
    valid: [
      // primitive importing only generic utils — fine
      {
        code: "import { cn } from '@/lib/utils';\nexport const x = 1;",
        filename: 'src/components/ui/button.tsx',
      },
      // workflow import OUTSIDE a primitive file — not this rule's concern
      {
        code: "import { WorkflowRun } from '@/features/workflows/types';\nexport const x = 1;",
        filename: 'src/features/workflows/WorkflowList.tsx',
      },
    ],
    invalid: [
      // alias import of workflow domain inside a primitive
      {
        code: "import { WorkflowRun } from '@/features/workflows/types';\nexport const x = 1;",
        filename: 'src/components/ui/button.tsx',
        errors: [{ messageId: 'workflowImport' }],
      },
      // relative type-only import of workflow domain inside a primitive
      {
        code: "import type { WorkflowRun } from '../../features/workflows/types';\nexport const x = 1;",
        filename: 'src/components/ui/badge.tsx',
        errors: [{ messageId: 'workflowImport' }],
      },
    ],
  });
});
