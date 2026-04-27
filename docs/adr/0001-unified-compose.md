# ADR 0001: Unified Docker Compose

## Status

Accepted

## Context

DeliveryLine is a local-first Spring Boot application that depends on local PostgreSQL during Epic 1. The planning artifacts previously showed a separate `docker-compose.observability.yml`, but the active epic story requires one root `docker-compose.yml` so pilot installers only have one compose entry point and one `.env` contract to manage.

Later stories will extend the same compose file with optional services:

- PostgreSQL in Epic 1
- Runner image declarations in stories 3.3 and 3.4
- Observability services in story 3.7

## Decision

Use a single root `docker-compose.yml` as the only compose file for product services.

- PostgreSQL is in the default profile and starts with `docker compose up -d`.
- Heavy optional services are added later behind compose profiles.
- No `docker-compose.observability.yml` file will be created.
- No separate runner compose file will be created.

This decision supersedes the earlier two-file compose proposal and keeps the operator setup path centered on one compose file and one `.env` contract.

## Consequences

- Pilot installers have one startup command and one place to resolve port conflicts.
- Spring Boot local-profile service discovery can target a stable root compose file.
- Later stories must extend `docker-compose.yml` instead of introducing parallel compose definitions.
