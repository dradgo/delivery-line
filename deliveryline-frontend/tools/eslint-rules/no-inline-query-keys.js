/**
 * Story 2.7 AC4 / Story 2.31 AC5 — `no-inline-query-keys`.
 *
 * TanStack Query keys must be created through query-key factory functions
 * (e.g. `workflowKeys.detail(id)` from `src/lib/queryKeys/*`), never as ad-hoc
 * inline array literals inside components. This is an architecture hard
 * invariant (architecture.md:766, 830, 851).
 *
 * The rule flags `useQuery` / `useMutation` / `useInfiniteQuery` calls whose
 * first-argument options object has a `queryKey` (or `mutationKey`) property
 * whose value is an inline `ArrayExpression`. A factory CALL (e.g.
 * `workflowKeys.list()`) or any identifier reference is allowed.
 *
 * Forward-looking: TanStack Query and src/lib/queryKeys/ arrive in story 2.6,
 * so this rule has no real call sites yet — it is authored now (referenced by
 * story 2.7 AC4) and proven by RuleTester fixtures.
 */

const QUERY_HOOKS = new Set(['useQuery', 'useMutation', 'useInfiniteQuery']);
const KEY_PROPS = new Set(['queryKey', 'mutationKey']);

/** @type {import('eslint').Rule.RuleModule} */
const rule = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Disallow inline array literals as TanStack Query keys; require a query-key factory (workflowKeys.*) from src/lib/queryKeys.',
    },
    schema: [],
    messages: {
      inlineKey:
        "Inline array '{{prop}}' is forbidden. Use a query-key factory (e.g. workflowKeys.*()) imported from src/lib/queryKeys — not an ad-hoc array literal.",
    },
  },
  create(context) {
    return {
      CallExpression(node) {
        const callee = node.callee;
        const name =
          callee.type === 'Identifier'
            ? callee.name
            : callee.type === 'MemberExpression' && callee.property.type === 'Identifier'
              ? callee.property.name
              : undefined;
        if (!name || !QUERY_HOOKS.has(name)) {
          return;
        }
        const options = node.arguments[0];
        if (!options || options.type !== 'ObjectExpression') {
          return;
        }
        for (const prop of options.properties) {
          if (
            prop.type === 'Property' &&
            prop.key.type === 'Identifier' &&
            KEY_PROPS.has(prop.key.name) &&
            prop.value.type === 'ArrayExpression'
          ) {
            context.report({
              node: prop.value,
              messageId: 'inlineKey',
              data: { prop: prop.key.name },
            });
          }
        }
      },
    };
  },
};

export default rule;
