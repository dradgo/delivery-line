# Redaction Fixture Corpus

This directory is the governed adversarial fixture corpus for story 1.10.

- Add one fixture file per discovered leak shape.
- Register every fixture in `fixtures-manifest.json`.
- Include explicit `forbiddenSnippets` so the contract test can assert the raw secret never survives redaction.
- Keep placeholders stable and category-specific.
- A fixture added without a manifest entry is a contract failure.
