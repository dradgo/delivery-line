package org.dradgo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

  // Story 1.21 — pinned to postgres:17.2 (1.17 deferral). Note the tag is mutable
  // on Docker Hub; for stricter reproducibility on a CI cold cache, pin by digest
  // (postgres:17.2@sha256:…) once Docker Hub publishes a stable manifest for the
  // image. Tracked in deferred-work.md.
  //
  // Singleton container — shared across all @SpringBootTest ApplicationContexts
  // and lifecycle-managed by Spring Boot's @ServiceConnection ContextCustomizer
  // (calls start() on first bean access, stop() on context shutdown).
  // `.withReuse(true)` lets developers reuse the container across `./mvnw test`
  // invocations locally (requires `testcontainers.reuse.enable=true` in
  // ~/.testcontainers.properties; CI containers are always fresh per workflow run).
  //
  // No `static { CONTAINER.start(); }` block — eager class-init start crashes the
  // Windows backend-unit-tests CI job (no Docker) when Spring auto-discovers this
  // @TestConfiguration via flywayContainerConnectionDetailsForPostgresContainer.
  // Spring's @ServiceConnection wiring starts the container lazily on first use,
  // which is what we want on every OS.
  private static final PostgreSQLContainer<?> CONTAINER =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.2")).withReuse(true);

  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return CONTAINER;
  }
}
