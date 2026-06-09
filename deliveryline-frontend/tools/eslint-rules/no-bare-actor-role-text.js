/**
 * Story 2.25 (Task 4 — AC8, D4) — `no-bare-actor-role-text`.
 *
 * Actor-role text (`product_reviewer`, `workflow_owner`) must be rendered through
 * the `<AuditRoleLabel>` wrapper, which carries the "recorded for audit only — not
 * an enforced permission" clarifier (architecture invariant: UI labels must make
 * clear that MVP roles are recorded audit labels, not enforced authorization).
 *
 * Flags a recognized actor-role string rendered as JSX text — either a `JSXText`
 * child or a string/template literal rendered as a JSX child — UNLESS it sits
 * inside an `<AuditRoleLabel>` element (the `actorRole` prop value lives inside
 * that element's tree, so it is allowed). Non-JSX strings (object fields,
 * comparisons, API params) are out of scope — `no-role-based-action-gating`
 * covers comparisons.
 *
 * Self-exempts `AuditRoleLabel.tsx` (the wrapper itself), mirroring the
 * `context.filename` trusted-boundary pattern of the other local rules.
 */

const ACTOR_ROLES = ['product_reviewer', 'workflow_owner'];
const ROLE_RE = new RegExp(`\\b(${ACTOR_ROLES.join('|')})\\b`);

// The wrapper itself renders the role text by design.
const TRUSTED_BOUNDARY = /(^|[\\/])AuditRoleLabel\.tsx$/;

/** Local JSX name: `Foo` for `<Foo>`, trailing member for `<Ns.Foo>`. */
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

/** True when `node` has a JSXElement ancestor named `<AuditRoleLabel>`. */
function insideAuditRoleLabel(node) {
  let current = node.parent;
  while (current) {
    if (
      current.type === 'JSXElement' &&
      localName(current.openingElement?.name) === 'AuditRoleLabel'
    ) {
      return true;
    }
    current = current.parent;
  }
  return false;
}

/** True when `node` is rendered as a JSX child (directly or via `{expr}`). */
function isJsxChild(node) {
  const parent = node.parent;
  if (!parent) {
    return false;
  }
  if (parent.type === 'JSXElement' || parent.type === 'JSXFragment') {
    return true;
  }
  if (parent.type === 'JSXExpressionContainer') {
    const grand = parent.parent;
    return grand?.type === 'JSXElement' || grand?.type === 'JSXFragment';
  }
  return false;
}

/** @type {import('eslint').Rule.RuleModule} */
const rule = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Render actor-role text only through <AuditRoleLabel> (recorded audit label, not enforced authorization) — story 2.25 AC8.',
    },
    schema: [],
    messages: {
      bareRole:
        'Actor-role text "{{role}}" must be rendered via <AuditRoleLabel actorRole="…"> (recorded audit label — not an enforced permission). Story 2.25 AC8.',
    },
  },
  create(context) {
    const filename = context.filename ?? context.getFilename();
    if (TRUSTED_BOUNDARY.test(filename)) {
      return {};
    }

    function check(node, text) {
      const match = ROLE_RE.exec(text);
      if (match === null) {
        return;
      }
      if (insideAuditRoleLabel(node)) {
        return;
      }
      context.report({ node, messageId: 'bareRole', data: { role: match[1] } });
    }

    return {
      JSXText(node) {
        check(node, node.value);
      },
      Literal(node) {
        if (typeof node.value === 'string' && isJsxChild(node)) {
          check(node, node.value);
        }
      },
      TemplateLiteral(node) {
        if (!isJsxChild(node)) {
          return;
        }
        const raw = node.quasis.map((q) => q.value.cooked ?? '').join(' ');
        check(node, raw);
      },
    };
  },
};

export default rule;
