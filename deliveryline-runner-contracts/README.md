# DeliveryLine Runner Contracts

This module is the source of truth for DeliveryLine runner file contracts.

## Scope

- JSON Schema Draft 2020-12 documents for context bundles and runner results
- Plain-Java validation entry points
- Shared valid and invalid fixture corpus for backend and runner implementations

## Current Version

- Current schema version: `1`
- The wire value is the literal number `1`
- Future versions should preserve backwards compatibility intentionally and document any breaking changes here before backend or runner implementations diverge

## Layout

- `src/main/resources/schemas/context-bundle.v1.schema.json`
- `src/main/resources/schemas/runner-result.v1.schema.json`
- `src/main/java/org/dradgo/runnercontracts/RunnerContractValidator.java`
- `src/test/resources/fixtures/valid/`
- `src/test/resources/fixtures/invalid/`

## Validation Model

Validation happens in two layers:

1. JSON Schema validation for structural rules
2. Semantic validation for corpus-level and safety rules such as duplicate runner execution IDs, stale metadata, oversized payloads, path traversal, and metadata spoofing

## Validator Entry Points

- `RunnerContractValidator.validateFixture(ValidationTarget, Path)`
- `RunnerContractValidator.validateFixture(ValidationTarget, Path, ValidationContext)`
- `RunnerContractValidator.validate(ValidationTarget, byte[], ValidationContext)`

The default semantic validation context uses a maximum payload size of `2048` bytes.

## Consumption Rule

Backend services and concrete runner implementations must consume these schemas and fixtures directly. They must not maintain divergent local copies.
