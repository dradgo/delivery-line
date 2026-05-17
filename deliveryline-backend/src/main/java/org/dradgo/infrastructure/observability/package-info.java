/**
 * Logging, metrics, and observability primitives.
 *
 * <p>Owns the stable MDC key surface ({@link org.dradgo.application.observability.MdcKeys}), the
 * redacting log layer ({@link org.dradgo.infrastructure.observability.RedactingMessageConverter},
 * {@link org.dradgo.infrastructure.observability.RedactingJsonProvider}), the request-scoped {@link
 * org.dradgo.infrastructure.observability.CorrelationIdFilter}, and the runtime wiring that pushes
 * {@link org.dradgo.application.security.RedactionPolicyService} into the static holder consulted
 * by the Logback layout.
 */
package org.dradgo.infrastructure.observability;
