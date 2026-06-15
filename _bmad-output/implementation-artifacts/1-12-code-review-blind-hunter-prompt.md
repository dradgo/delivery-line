Review target: current tracked uncommitted diff for story `1-12-artifact-operations-skeleton`.

Use only the diff from this workspace:

```powershell
git diff HEAD
```

Rules:
- Do not read the story/spec.
- Do not inspect the project outside the diff.
- Review adversarially for bugs, regressions, incorrect assumptions, dangerous patterns, and hidden coupling.

Output:
- Markdown list only.
- Each finding must include:
  - short title
  - severity
  - why it is a problem
  - concrete evidence with file/line references from the diff

Use the `bmad-review-adversarial-general` skill.
