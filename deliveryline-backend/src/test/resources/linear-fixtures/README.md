# Linear adversarial test fixtures

Per story 1.14 Task 3 / Dev Notes, these adversarial scenario markers live under
`src/test/resources/linear-fixtures/` so they never leak into a `demo` or `local` runtime classpath.

Each marker JSON documents a scenario that tests register against
`LinearMockScenarioRegistry` via `register(new LinearMockScenario(...))`. The markers themselves
are **not loaded** by the registry — the registry only loads `HAPPY` scenarios from
`src/main/resources/linear-fixtures/`. The markers exist to give human readers a single index
of the adversarial test surface.

| File | ticketRef | Behaviour | Expected failure category |
| ---- | --------- | --------- | ------------------------- |
| `not-found-simulation.json` | `LIN-NOT-FOUND` | `NOT_FOUND` | _none — AC7 routes to LINEAR_TICKET_NOT_FOUND_ |
| `rate-limit-simulation.json` | `LIN-RATE-LIMITED` | `RATE_LIMITED` | `network_api_failure` |
| `network-failure-simulation.json` | `LIN-NETWORK-FAILURE` | `NETWORK_FAILURE` | `network_api_failure` |
| `auth-failure-simulation.json` | `LIN-AUTH-FAILURE` | `AUTH_FAILURE` | `link_failure` |
| `malformed-response-simulation.json` | `LIN-MALFORMED` | `MALFORMED_RESPONSE` | `sync_failure` |
