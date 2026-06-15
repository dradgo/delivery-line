Review target: current tracked uncommitted diff for story `1-12-artifact-operations-skeleton`.

Primary diff:

```powershell
git diff HEAD
```

Spec/context:
- Story file: `C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\implementation-artifacts\1-12-artifact-operations-skeleton.md`

Instructions:
- Review the diff against the story acceptance criteria and constraints.
- Check for violations of acceptance criteria, deviations from spec intent, missing required behavior, and contradictions between the spec and code.

Output:
- Markdown list only.
- Each finding must include:
  - short title
  - which AC or constraint is violated
  - concrete evidence with file/line references from the diff

Prompt:
You are an Acceptance Auditor. Review this diff against the spec and context docs. Check for: violations of acceptance criteria, deviations from spec intent, missing implementation of specified behavior, contradictions between spec constraints and actual code. Output findings as a Markdown list. Each finding: one-line title, which AC/constraint it violates, and evidence from the diff.
