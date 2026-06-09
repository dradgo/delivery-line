/**
 * Story 2.25 (Task 4 — AC9, D4) — `no-role-based-action-gating`.
 *
 * HARD architecture invariant (architecture.md Hard Invariants): "Frontend code
 * must not gate actions based on audit role labels." All action gating must route
 * through `useAllowedActions` (story 2.14) — the backend-reported allowed actions
 * are authoritative; the frontend must never infer workflow permissions locally.
 *
 * Flags any equality comparison (`===`/`!==`/`==`/`!=`) against a recognized
 * actor-role string literal — e.g. `if (actorRole === 'product_reviewer')`,
 * `actorRole === 'workflow_owner' ? … : …`, `role !== 'product_reviewer' && …`.
 * Comparing to a role literal is the signature of a role-keyed gate; legitimate
 * uses (rendering the role, passing it as an API param, defaulting it) are
 * assignments / JSX / `??` defaults, none of which are equality comparisons.
 */

const ACTOR_ROLES = new Set(['product_reviewer', 'workflow_owner']);
const COMPARISON_OPERATORS = new Set(['===', '!==', '==', '!=']);

/** A string-literal node whose value is a recognized actor role. */
function isActorRoleLiteral(node) {
  return node?.type === 'Literal' && typeof node.value === 'string' && ACTOR_ROLES.has(node.value);
}

/** @type {import('eslint').Rule.RuleModule} */
const rule = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Forbid gating actions on actor-role labels; route all gating through useAllowedActions — story 2.25 AC9.',
    },
    schema: [],
    messages: {
      roleGate:
        'Do not gate on the actor role "{{role}}". Audit roles are NOT enforced authorization — route action gating through `useAllowedActions` (story 2.25 AC9).',
    },
  },
  create(context) {
    return {
      BinaryExpression(node) {
        if (!COMPARISON_OPERATORS.has(node.operator)) {
          return;
        }
        const roleLiteral = isActorRoleLiteral(node.left)
          ? node.left
          : isActorRoleLiteral(node.right)
            ? node.right
            : null;
        if (roleLiteral === null) {
          return;
        }
        context.report({ node, messageId: 'roleGate', data: { role: roleLiteral.value } });
      },
    };
  },
};

export default rule;
