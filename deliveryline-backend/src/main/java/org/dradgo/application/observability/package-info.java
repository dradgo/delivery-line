/**
 * Application-layer observability primitives — currently just {@link
 * org.dradgo.application.observability.MdcKeys}, the stable MDC key surface and helper for
 * pushing / popping correlation-id, workflow-run-id, runner-execution-id, artifact-id, and
 * artifact-operation-id scopes.
 *
 * <p>Lives in the application layer (not {@code infrastructure.observability}) so that
 * application services can depend on it without violating the layered-architecture rule that
 * forbids {@code application → infrastructure} dependencies. Adapters and infrastructure
 * (Logback wiring, REST filter, JSON provider) freely depend on this package as the canonical
 * source of the MDC key strings.
 */
package org.dradgo.application.observability;
