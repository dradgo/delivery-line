// Story 2.23 (AC7.t) — RuleTester for `single-primary-action`. Mirrors
// no-untyped-loading-state.test.js (node --test + RuleTester).
import test from 'node:test';
import { RuleTester } from 'eslint';
import tseslint from 'typescript-eslint';
import rule from '../single-primary-action.js';

const ruleTester = new RuleTester({
  languageOptions: {
    parser: tseslint.parser,
    ecmaVersion: 2023,
    sourceType: 'module',
    parserOptions: { ecmaFeatures: { jsx: true } },
  },
});

test('single-primary-action', () => {
  ruleTester.run('single-primary-action', rule, {
    valid: [
      {
        // One primary in a group — fine.
        code: 'function C() { return <ButtonGroup><GovernedButton priority="primary" /><GovernedButton priority="secondary" /></ButtonGroup>; }',
        filename: 'src/features/workflows/C.tsx',
      },
      {
        // Two primaries in DIFFERENT containers — each its own decision area.
        code: 'function C() { return <div><ButtonGroup><GovernedButton priority="primary" /></ButtonGroup><DecisionArea><GovernedButton priority="primary" /></DecisionArea></div>; }',
        filename: 'src/features/workflows/C.tsx',
      },
      {
        // Dynamic priority cannot be proven — skipped (no false positive).
        code: 'function C(p, q) { return <ButtonGroup><GovernedButton priority={p} /><GovernedButton priority={q} /></ButtonGroup>; }',
        filename: 'src/features/workflows/C.tsx',
      },
      {
        // Two literal primaries but NOT inside a container — out of scope.
        code: 'function C() { return <div><GovernedButton priority="primary" /><GovernedButton priority="primary" /></div>; }',
        filename: 'src/features/workflows/C.tsx',
      },
    ],
    invalid: [
      {
        // Two literal primaries in one <ButtonGroup>.
        code: 'function C() { return <ButtonGroup><GovernedButton priority="primary" /><GovernedButton priority="primary" /></ButtonGroup>; }',
        filename: 'src/features/workflows/C.tsx',
        errors: [{ messageId: 'multiplePrimary' }],
      },
      {
        // Two literal primaries in one <DecisionArea> (via children).
        code: 'function C() { return <DecisionArea><GovernedButton priority="primary" /><GovernedButton priority="primary" /></DecisionArea>; }',
        filename: 'src/features/workflows/C.tsx',
        errors: [{ messageId: 'multiplePrimary' }],
      },
    ],
  });
});
