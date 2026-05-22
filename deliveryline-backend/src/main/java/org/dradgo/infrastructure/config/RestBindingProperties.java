package org.dradgo.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Localhost-binding configuration for the REST surface (story 6.9 Task 3).
 *
 * <p>MVP is local-only and unauthenticated (architecture.md "localhost-only / safe-by-default"); a
 * loopback-only bind is the Phase-1 substitute for auth. Binding to a non-loopback interface
 * therefore requires an explicit, deliberate opt-out via both fields below.
 *
 * @param bindAddress optional override for {@code server.address}. When set, it (not {@code
 *     server.address}) is the effective bind address the startup guard evaluates. Leave unset to
 *     inherit {@code server.address} (committed default {@code 127.0.0.1}).
 * @param unsafeNetworkBind when {@code false} (default) the app refuses to start if the effective
 *     bind address is not loopback ({@code DOCTOR_REST_BIND_UNAVAILABLE}). Set {@code true} to
 *     deliberately bind a non-loopback address (development only); the app then starts but logs a
 *     {@code WARN} naming the security implication. See {@code RestBindingGuard}.
 */
@ConfigurationProperties("deliveryline.rest")
public record RestBindingProperties(String bindAddress, boolean unsafeNetworkBind) {}
