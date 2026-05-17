package org.dradgo.application.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import org.dradgo.TestcontainersConfiguration;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    properties = {
      "spring.jpa.properties.hibernate.generate_statistics=true",
      "spring.shell.interactive.enabled=false",
      "spring.shell.script.enabled=false"
    })
@ActiveProfiles({"test", "linear-mock"})
class DoctorServiceContractTest {

  @Autowired private DoctorService doctorService;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Test
  void runDiagnosticsDoesNotWriteManagedEntities() {
    SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
    Statistics statistics = sessionFactory.getStatistics();
    statistics.setStatisticsEnabled(true);
    statistics.clear();

    DiagnosticsReport report = doctorService.runDiagnostics(DoctorRunRequest.all());

    assertThat(report.checks()).isNotEmpty();
    assertThat(statistics.getEntityInsertCount()).isZero();
    assertThat(statistics.getEntityUpdateCount()).isZero();
    assertThat(statistics.getEntityDeleteCount()).isZero();
  }
}
