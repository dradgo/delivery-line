# Acceptance Auditor Review Prompt

Role: `Acceptance Auditor`

Review target:
- Repo: `C:\Users\pc\Documents\Personal\ai-hackaton-1`
- Diff source: uncommitted working tree
- Command: `git diff HEAD -- deliveryline-backend`

Spec and context:
- Story spec: `C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\implementation-artifacts\1-11-archunit-package-boundary-tests.md`
- Architecture context: `C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\architecture.md`
- Epics context: `C:\Users\pc\Documents\Personal\ai-hackaton-1\_bmad-output\planning-artifacts\epics.md`

Task:
Review this diff against the spec and context docs. Check for:
- violations of acceptance criteria
- deviations from spec intent
- missing implementation of specified behavior
- contradictions between spec constraints and actual code

Output findings as a Markdown list.

Each finding must include:
- one-line title
- which AC or constraint it violates
- evidence from the diff

If you find no issues, say: `No findings.`
