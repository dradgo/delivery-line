/**
 * Story 2.23 (AC7) — `single-primary-action`.
 *
 * Enforces UX-DR19 "one primary action per decision area": within a single
 * `<ButtonGroup>` or `<DecisionArea>` ancestor, no more than ONE element may
 * carry a LITERAL `priority="primary"` attribute. The second (and later) literal
 * primary inside the same container is reported.
 *
 * CONSERVATIVE (OQ-3): only statically-literal `priority="primary"` attributes
 * are counted — a dynamic `priority={expr}` cannot be proven and is SKIPPED. The
 * rule is element-name-agnostic (it keys on the `priority="primary"` attribute,
 * not on `<GovernedButton>`), so it holds regardless of the button component's
 * name. Scoped to the nearest `<ButtonGroup>`/`<DecisionArea>` so a component
 * with several decision areas — each with its own primary — does not false-flag.
 *
 * ESCAPE HATCH (rare sanctioned case):
 *   // eslint-disable-next-line local-rules/single-primary-action -- <rationale>
 *
 * Modeled on `no-untyped-loading-state.js` (JSX-AST: `JSXOpeningElement`,
 * `localName`, attribute reading).
 */

const CONTAINER_NAMES = new Set(['ButtonGroup', 'DecisionArea']);

/** @type {import('eslint').Rule.RuleModule} */
const rule = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Allow at most one literal priority="primary" within a single <ButtonGroup>/<DecisionArea> (UX-DR19 one primary per decision area).',
    },
    schema: [],
    messages: {
      multiplePrimary:
        'More than one literal `priority="primary"` within the same <ButtonGroup>/<DecisionArea> (UX-DR19: one primary action per decision area). Demote the others to secondary/tertiary, split into separate decision areas, or — if genuinely sanctioned — add `// eslint-disable-next-line local-rules/single-primary-action -- <rationale>`.',
    },
  },
  create(context) {
    // The element's local JSX name: `Foo` for `<Foo>` and the trailing member
    // for `<Ns.Foo>`.
    function localName(name) {
      if (!name) {
        return null;
      }
      if (name.type === 'JSXIdentifier') {
        return name.name;
      }
      if (name.type === 'JSXMemberExpression' && name.property?.type === 'JSXIdentifier') {
        return name.property.name;
      }
      return null;
    }

    // True when the opening element carries a LITERAL `priority="primary"`.
    function hasLiteralPrimary(node) {
      return node.attributes.some(
        (attr) =>
          attr.type === 'JSXAttribute' &&
          attr.name &&
          attr.name.name === 'priority' &&
          attr.value &&
          attr.value.type === 'Literal' &&
          attr.value.value === 'primary',
      );
    }

    // Walk ancestors to the nearest <ButtonGroup>/<DecisionArea> JSXElement.
    function nearestContainer(node) {
      let current = node.parent;
      while (current) {
        if (
          current.type === 'JSXElement' &&
          CONTAINER_NAMES.has(localName(current.openingElement.name))
        ) {
          return current;
        }
        current = current.parent;
      }
      return null;
    }

    /** @type {Map<object, number>} container node → count of literal primaries seen. */
    const primaryCounts = new Map();
    /** @type {object[]} the offending 2nd+ primary opening elements. */
    const violations = [];

    return {
      JSXOpeningElement(node) {
        if (!hasLiteralPrimary(node)) {
          return;
        }
        const container = nearestContainer(node);
        if (!container) {
          return;
        }
        const count = (primaryCounts.get(container) ?? 0) + 1;
        primaryCounts.set(container, count);
        if (count > 1) {
          violations.push(node);
        }
      },

      'Program:exit'() {
        for (const node of violations) {
          context.report({ node, messageId: 'multiplePrimary' });
        }
      },
    };
  },
};

export default rule;
