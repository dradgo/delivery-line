Review target: current tracked uncommitted diff for story `1-12-artifact-operations-skeleton`.

Primary diff:

```powershell
git diff HEAD
```

Rules:
- You may inspect the repository for context.
- Focus on edge cases, state-machine holes, race conditions, transaction boundaries, replay/idempotency mistakes, path-handling bugs, and contract mismatches.

Output:
- Markdown list only.
- Each finding must include:
  - short title
  - severity
  - triggering edge case
  - concrete evidence with file/line references

Use the `bmad-review-edge-case-hunter` skill.
