// Story 2.25 (Task 4 — AC9) — RuleTester for `no-role-based-action-gating`.
// Models no-untyped-loading-state.test.js (node --test + RuleTester).
import test from 'node:test';
import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import rule from '../no-role-based-action-gating.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: 2023,
    sourceType: 'module',
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

test('no-role-based-action-gating', () => {
  ruleTester.run('no-role-based-action-gating', rule, {
    valid: [
      {
        // Gating routes through the allowed-actions hook — no role comparison.
        code: 'function f(id) { const a = useAllowedActions(id); return a.canApprove; }',
        filename: 'src/features/workflows/f.ts',
      },
      {
        // Comparing to a non-role value is fine.
        code: "function f(state) { return state === 'spec_approval'; }",
        filename: 'src/features/workflows/f.ts',
      },
      {
        // Defaulting the role (not gating on it) is allowed.
        code: "function f(actorRole) { return actorRole ?? 'product_reviewer'; }",
        filename: 'src/features/workflows/f.ts',
      },
      {
        // Passing the role as data to an API is allowed.
        code: "function f(submit) { return submit({ actorRole: 'product_reviewer' }); }",
        filename: 'src/features/workflows/f.ts',
      },
    ],
    invalid: [
      {
        code: "function f(actorRole) { if (actorRole === 'product_reviewer') { return true; } return false; }",
        filename: 'src/features/workflows/f.ts',
        errors: [{ messageId: 'roleGate' }],
      },
      {
        code: "function f(role) { return role !== 'workflow_owner'; }",
        filename: 'src/components/actions/f.ts',
        errors: [{ messageId: 'roleGate' }],
      },
      {
        code: "function f(actorRole) { return actorRole === 'product_reviewer' ? 'a' : 'b'; }",
        filename: 'src/features/workflows/f.ts',
        errors: [{ messageId: 'roleGate' }],
      },
      {
        // Literal on the left-hand side is caught too.
        code: "function f(view) { return 'workflow_owner' === view.actorRole; }",
        filename: 'src/features/workflows/f.tsx',
        errors: [{ messageId: 'roleGate' }],
      },
    ],
  });
});
