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
 * whose value is not backed by a factory call (e.g. `workflowKeys.list()`).
 * Simple indirection through `const key = workflowKeys.list()` or
 * `const options = { queryKey: workflowKeys.list() }` is allowed, but
 * ad-hoc arrays or unresolved non-call expressions are rejected.
 *
 * Forward-looking: TanStack Query and src/lib/queryKeys/ arrive in story 2.6,
 * so this rule has no real call sites yet — it is authored now (referenced by
 * story 2.7 AC4) and proven by RuleTester fixtures.
 */

const QUERY_HOOKS = new Set(['useQuery', 'useMutation', 'useInfiniteQuery']);
const KEY_PROPS = new Set(['queryKey', 'mutationKey']);

const WRAPPER_NODE_TYPES = new Set([
  'TSAsExpression',
  'TSSatisfiesExpression',
  'TSNonNullExpression',
  'TSTypeAssertion',
  'ChainExpression',
]);

/**
 * @param {import('estree').Node | null | undefined} node
 * @returns {import('estree').Node | null | undefined}
 */
function unwrap(node) {
  let current = node;
  while (current && WRAPPER_NODE_TYPES.has(current.type)) {
    current = current.expression;
  }
  return current;
}

/**
 * @param {import('eslint').Rule.RuleContext} context
 * @param {import('estree').Identifier} identifier
 * @returns {import('eslint').Scope.Variable | undefined}
 */
function findVariable(context, identifier) {
  const sourceCode = context.sourceCode ?? context.getSourceCode();
  let scope = sourceCode.getScope(identifier);
  while (scope) {
    const variable = scope.set.get(identifier.name);
    if (variable) {
      return variable;
    }
    scope = scope.upper;
  }
  return undefined;
}

/**
 * @param {import('eslint').Rule.RuleContext} context
 * @param {import('estree').Expression | import('estree').Identifier | null | undefined} node
 * @param {Set<unknown>} seen
 * @returns {import('estree').Node | null | undefined}
 */
function resolveNode(context, node, seen) {
  const current = unwrap(node);
  if (!current || seen.has(current)) {
    return current;
  }
  seen.add(current);
  if (current.type !== 'Identifier') {
    return current;
  }

  const variable = findVariable(context, current);
  const definition = variable?.defs[0];
  if (!definition) {
    return current;
  }

  if (definition.type === 'Variable' && definition.node.type === 'VariableDeclarator') {
    return resolveNode(context, definition.node.init, seen);
  }

  return current;
}

/**
 * @param {import('estree').Property['key']} key
 * @returns {string | undefined}
 */
function getPropertyName(key) {
  if (key.type === 'Identifier') {
    return key.name;
  }
  if (key.type === 'Literal' && typeof key.value === 'string') {
    return key.value;
  }
  if (key.type === 'TemplateLiteral' && key.expressions.length === 0) {
    return key.quasis[0]?.value.cooked;
  }
  return undefined;
}

/**
 * @param {import('eslint').Rule.RuleContext} context
 * @param {import('estree').Expression | import('estree').Identifier | null | undefined} node
 * @returns {import('estree').ObjectExpression | undefined}
 */
function resolveOptionsObject(context, node) {
  const resolved = resolveNode(context, node, new Set());
  return resolved?.type === 'ObjectExpression' ? resolved : undefined;
}

/**
 * @param {import('eslint').Rule.RuleContext} context
 * @param {import('estree').Expression | import('estree').Identifier | null | undefined} node
 * @returns {boolean}
 */
function isFactoryBackedValue(context, node) {
  const resolved = resolveNode(context, node, new Set());
  return resolved?.type === 'CallExpression';
}

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
      nonFactoryKey:
        "Property '{{prop}}' must come from a query-key factory call (e.g. workflowKeys.*()) imported from src/lib/queryKeys — not an ad-hoc array or other non-call expression.",
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
        const options = resolveOptionsObject(context, node.arguments[0]);
        if (!options) {
          return;
        }
        for (const prop of options.properties) {
          if (
            prop.type === 'Property' &&
            getPropertyName(prop.key) &&
            KEY_PROPS.has(getPropertyName(prop.key)) &&
            !isFactoryBackedValue(context, prop.value)
          ) {
            const propName = getPropertyName(prop.key);
            context.report({
              node: prop.value,
              messageId: 'nonFactoryKey',
              data: { prop: propName },
            });
          }
        }
      },
    };
  },
};

export default rule;
