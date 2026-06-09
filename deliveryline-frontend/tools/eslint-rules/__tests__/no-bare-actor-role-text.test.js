// Story 2.25 (Task 4 — AC8) — RuleTester for `no-bare-actor-role-text`.
// Models no-untyped-loading-state.test.js (node --test + RuleTester).
import test from 'node:test';
import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import rule from '../no-bare-actor-role-text.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: 2023,
    sourceType: 'module',
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

test('no-bare-actor-role-text', () => {
  ruleTester.run('no-bare-actor-role-text', rule, {
    valid: [
      {
        // The sanctioned wrapper — role passed as a prop, not bare text.
        code: 'function V() { return <AuditRoleLabel actorRole="product_reviewer" />; }',
        filename: 'src/features/workflows/V.tsx',
      },
      {
        // Role text inside the wrapper's tree is fine.
        code: 'function V() { return <AuditRoleLabel actorRole="product_reviewer">product_reviewer</AuditRoleLabel>; }',
        filename: 'src/features/workflows/V.tsx',
      },
      {
        // Non-role JSX text.
        code: 'function V() { return <span>Reviewer notes</span>; }',
        filename: 'src/features/workflows/V.tsx',
      },
      {
        // Non-JSX string (object field / param) is out of scope.
        code: "const fixture = { actor: 'Alex (product_reviewer)' };",
        filename: 'src/test/fixtures/x.ts',
      },
      {
        // The wrapper file itself self-exempts.
        code: 'function W() { return <span>product_reviewer</span>; }',
        filename: 'src/components/feedback/AuditRoleLabel.tsx',
      },
    ],
    invalid: [
      {
        code: 'function Bad() { return <span>product_reviewer</span>; }',
        filename: 'src/features/workflows/Bad.tsx',
        errors: [{ messageId: 'bareRole' }],
      },
      {
        code: "function Bad2() { return <div>{'workflow_owner'}</div>; }",
        filename: 'src/features/workflows/Bad2.tsx',
        errors: [{ messageId: 'bareRole' }],
      },
      {
        code: 'function Bad3() { return <p>Acting as product_reviewer for this run</p>; }',
        filename: 'src/components/actions/Bad3.tsx',
        errors: [{ messageId: 'bareRole' }],
      },
    ],
  });
});
