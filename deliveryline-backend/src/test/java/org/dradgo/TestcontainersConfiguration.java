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
  // Per-context container — NOT a shared static singleton. Each @SpringBootTest
  // ApplicationContext gets (and Spring Boot's @ServiceConnection lifecycle-manages)
  // its OWN Postgres instance, started on first use and stopped when that context
  // closes.
  //
  // Why not a shared static singleton (the previous design): @ServiceConnection calls
  // stop() on the container when ANY context closes (context-cache eviction once the
  // tier exceeds the 32-context cache, a @MockitoBean/@DirtiesContext class, etc.). A
  // shared singleton therefore gets torn down mid-suite while OTHER still-cached
  // contexts keep using it → HikariPool "Connection refused" → 30s connect-timeout
  // retries → the backend-contract-tests tier hangs to its CI timeout. Making stop()
  // inert instead (so the singleton survives) breaks the other direction: the DB is
  // never reset, so rows accumulate across every test class and FK-constrained
  // cleanups fail. Per-context containers resolve both: no sharing means no
  // stop-mid-suite race, and each context starts from a fresh, migrated, empty schema
  // so tests are isolated without depending on a shared container being restarted.
  //
  // No `static { … .start(); }` block and no eager start here — constructing the
  // container does not touch Docker (only start() does), so the Docker-less Windows
  // backend-unit-tests CI job stays green even though Spring auto-discovers this
  // @TestConfiguration; @ServiceConnection starts the container lazily, only when a
  // context actually needs the datasource.
  //
  // withReuse(true) is intentionally NOT set: reuse shares one container across
  // contexts (when testcontainers.reuse.enable=true), which would reintroduce the
  // shared-container teardown race for any developer who enables reuse locally.
  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.2"));
  }
}
