# Edge Case Hunter Review Prompt

Role: `Edge Case Hunter`
Skill to use: `bmad-review-edge-case-hunter`

Review target:
- Repo: `C:\Users\pc\Documents\Personal\ai-hackaton-1`
- Diff source: uncommitted working tree
- Command: `git diff HEAD -- deliveryline-backend`

Available context:
- Full read access to the project is allowed.

Focus:
- Edge cases
- boundary conditions
- missing state handling
- concurrency or locking failures
- nullability and empty-input behavior
- contract mismatches between ports, adapters, repositories, and tests

Output format:
- Markdown list only.
- Each finding must include:
  - short title
  - severity
  - affected file(s)
  - scenario that breaks
  - evidence

If you find no issues, say: `No findings.`
