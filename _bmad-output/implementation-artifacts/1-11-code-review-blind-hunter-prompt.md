# Blind Hunter Review Prompt

Role: `Blind Hunter`
Skill to use: `bmad-review-adversarial-general`

Review target:
- Repo: `C:\Users\pc\Documents\Personal\ai-hackaton-1`
- Diff source: uncommitted working tree
- Command: `git diff HEAD -- deliveryline-backend`

Constraints:
- You receive the diff only.
- Do not use project docs, story specs, or additional repo context.
- Focus on bugs, regressions, broken assumptions, missing error handling, and correctness risks visible from the diff alone.

Output format:
- Markdown list only.
- Each finding must include:
  - short title
  - severity
  - evidence from the diff
  - why it is a bug or risk

If you find no issues, say: `No findings.`
