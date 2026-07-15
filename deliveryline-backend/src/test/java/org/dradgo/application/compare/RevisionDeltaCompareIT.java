package org.dradgo.application.compare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.dradgo.TestcontainersConfiguration;
import org.dradgo.application.artifact.ArtifactChecksum;
import org.dradgo.application.artifact.spi.ArtifactPayloadStore;
import org.dradgo.domain.DomainException;
import org.dradgo.domain.registry.DomainErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Story 4.19 (AC10) — real-Postgres coverage of the compare read across all three artifact types:
 * spec section diff, implementation-plan step diff, prOutput file-level summary, plus the
 * parent-chain lineage-mismatch rejection and the AC7 performance target. Seeds real lineages
 * (parent_artifact_id chain + real payloads/checksums via the payload store) directly and drives
 * {@link RevisionDeltaService#computeDelta}. Named {@code *IT} so it runs under Failsafe
 * (Testcontainers), never the no-Docker Surefire tier.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles({"test", "linear-mock"})
@Tag("integration")
class RevisionDeltaCompareIT {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ArtifactPayloadStore payloadStore;
  @Autowired private RevisionDeltaService revisionDeltaService;

  private final List<String> seededRuns = new java.util.ArrayList<>();

  @AfterEach
  void cleanUp() {
    for (String run : seededRuns) {
      Long runId =
          jdbcTemplate.queryForObject(
              "select id from workflow_runs where public_id = ?", Long.class, run);
      jdbcTemplate.update("delete from artifacts where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_events where workflow_run_id = ?", runId);
      jdbcTemplate.update("delete from workflow_runs where public_id = ?", run);
    }
    seededRuns.clear();
  }

  @Test
  void computesSpecSectionDeltaAcrossRealLineage() {
    Seed seed = seedRun("spec");
    Artifact v1 =
        seedArtifact(seed, "spec", 1, null, "# Intro\n\nold intro\n\n# Body\n\nsame", "author-1");
    Artifact v2 =
        seedArtifact(
            seed,
            "spec",
            2,
            v1.dbId,
            "# Intro\n\nnew intro\n\n# Body\n\nsame\n\n# Extra\n\nadded",
            "author-2");

    RevisionDelta delta = revisionDeltaService.computeDelta(v1.publicId, v2.publicId);

    assertThat(delta.artifactType()).isEqualTo("spec");
    assertThat(delta.noMeaningfulDiff()).isFalse();
    assertThat(delta.revisionA().producedByActor()).isEqualTo("author-1");
    assertThat(delta.revisionB().producedByActor()).isEqualTo("author-2");
    assertThat(delta.changes())
        .extracting(ChangeBlock::changeKind)
        .containsExactlyInAnyOrder(ChangeKind.MODIFIED, ChangeKind.ADDED);
  }

  @Test
  void computesPlanStepDeltaAcrossRealLineage() {
    Seed seed = seedRun("plan");
    Artifact v1 =
        seedArtifact(seed, "implementationPlan", 1, null, "{\"steps\":[\"build\",\"test\"]}", "a1");
    Artifact v2 =
        seedArtifact(
            seed,
            "implementationPlan",
            2,
            v1.dbId,
            "{\"steps\":[\"build\",\"test\",\"deploy\"]}",
            "a2");

    RevisionDelta delta = revisionDeltaService.computeDelta(v1.publicId, v2.publicId);

    assertThat(delta.artifactType()).isEqualTo("implementationPlan");
    assertThat(delta.changes()).hasSize(1);
    PlanStepChangeBlock block = (PlanStepChangeBlock) delta.changes().get(0);
    assertThat(block.changeKind()).isEqualTo(ChangeKind.ADDED);
    assertThat(block.currentStepText()).isEqualTo("deploy");
  }

  @Test
  void computesPrOutputFileSummaryAndLinkedReferences() {
    Seed seed = seedRun("pr");
    String diffV1 = "{\"diff\":\"\"}";
    String diffV2 =
        "{\"diff\":\"diff --git a/src/A.java b/src/A.java\\n"
            + "--- a/src/A.java\\n+++ b/src/A.java\\n@@ -1 +1,2 @@\\n context\\n+added line\\n\"}";
    Artifact v1 = seedArtifact(seed, "prOutput", 1, null, diffV1, "a1");
    Artifact v2 = seedArtifact(seed, "prOutput", 2, v1.dbId, diffV2, "a2");

    RevisionDelta delta = revisionDeltaService.computeDelta(v1.publicId, v2.publicId);

    assertThat(delta.artifactType()).isEqualTo("prOutput");
    assertThat(delta.linkedDiffReferences()).containsExactly(v1.publicId, v2.publicId);
    assertThat(delta.changes()).hasSize(1);
    FileChangeBlock file = (FileChangeBlock) delta.changes().get(0);
    assertThat(file.filePath()).isEqualTo("src/A.java");
    assertThat(file.addedLines()).isEqualTo(1);
  }

  @Test
  void byteEqualArtifactsReturnNoMeaningfulDiff() {
    Seed seed = seedRun("equal");
    String body = "# Intro\n\nidentical content";
    Artifact v1 = seedArtifact(seed, "spec", 1, null, body, "a1");
    Artifact v2 = seedArtifact(seed, "spec", 2, v1.dbId, body, "a2");

    RevisionDelta delta = revisionDeltaService.computeDelta(v1.publicId, v2.publicId);

    assertThat(delta.noMeaningfulDiff()).isTrue();
    assertThat(delta.changes()).isEmpty();
  }

  @Test
  void disjointLineageUnderSameRunAndTypeIsRejected() {
    Seed seed = seedRun("disjoint");
    Artifact v1 = seedArtifact(seed, "spec", 1, null, "# A\n\none", "a1");
    seedArtifact(seed, "spec", 2, v1.dbId, "# A\n\ntwo", "a2");
    // A fresh draft after a failed leaf (lineage_recovery) — version 3, root (parent null).
    Artifact disjoint = seedArtifact(seed, "spec", 3, null, "# B\n\nother lineage", "a3");

    assertThatThrownBy(() -> revisionDeltaService.computeDelta(v1.publicId, disjoint.publicId))
        .isInstanceOfSatisfying(
            DomainException.class,
            ex -> {
              assertThat(ex.errorCode()).isEqualTo(DomainErrorCode.ARTIFACT_LINEAGE_MISMATCH);
              assertThat(ex.details()).containsEntry("reason", "not_on_parent_chain");
            });
  }

  @Test
  void specDeltaOfTypicalPilotSizeCompletesUnderTarget() {
    Seed seed = seedRun("perf");
    StringBuilder base = new StringBuilder();
    for (int i = 0; i < 150; i++) {
      base.append("# Section ")
          .append(i)
          .append("\n\nBody line for section ")
          .append(i)
          .append(" with some representative content spanning a couple of sentences.\n\n");
    }
    String v1Body = base.toString();
    String v2Body =
        v1Body.replace("Body line for section 10 with", "CHANGED body line for section 10 with");
    Artifact v1 = seedArtifact(seed, "spec", 1, null, v1Body, "a1");
    Artifact v2 = seedArtifact(seed, "spec", 2, v1.dbId, v2Body, "a2");

    long start = System.nanoTime();
    RevisionDelta delta = revisionDeltaService.computeDelta(v1.publicId, v2.publicId);
    long durationMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(delta.changes()).hasSize(1);
    assertThat(delta.changes().get(0).changeKind()).isEqualTo(ChangeKind.MODIFIED);
    assertThat(durationMs).as("spec delta must complete under the AC7 5s target").isLessThan(5000L);
  }

  @Test
  void planStepDeltaOfTypicalPilotSizeCompletesUnderTarget() {
    Seed seed = seedRun("planperf");
    StringBuilder v1 = new StringBuilder("{\"steps\":[");
    StringBuilder v2 = new StringBuilder("{\"steps\":[");
    for (int i = 0; i < 200; i++) {
      if (i > 0) {
        v1.append(',');
        v2.append(',');
      }
      v1.append("\"step ").append(i).append(" do the representative work item\"");
      // v2 mutates one step and appends nothing else — a typical small edit.
      v2.append("\"step ")
          .append(i)
          .append(i == 100 ? " CHANGED work item" : " do the representative work item")
          .append('\"');
    }
    v1.append("]}");
    v2.append("]}");
    Artifact a = seedArtifact(seed, "implementationPlan", 1, null, v1.toString(), "a1");
    Artifact b = seedArtifact(seed, "implementationPlan", 2, a.dbId, v2.toString(), "a2");

    long start = System.nanoTime();
    RevisionDelta delta = revisionDeltaService.computeDelta(a.publicId, b.publicId);
    long durationMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(delta.artifactType()).isEqualTo("implementationPlan");
    assertThat(delta.changes()).isNotEmpty();
    assertThat(durationMs).as("plan delta must complete under the AC7 5s target").isLessThan(5000L);
  }

  @Test
  void prOutputDeltaOfTypicalPilotSizeCompletesUnderTarget() {
    Seed seed = seedRun("prperf");
    Artifact a = seedArtifact(seed, "prOutput", 1, null, prDiffPayload(120, 1), "a1");
    Artifact b = seedArtifact(seed, "prOutput", 2, a.dbId, prDiffPayload(120, 4), "a2");

    long start = System.nanoTime();
    RevisionDelta delta = revisionDeltaService.computeDelta(a.publicId, b.publicId);
    long durationMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(delta.artifactType()).isEqualTo("prOutput");
    assertThat(delta.changes()).isNotEmpty();
    assertThat(durationMs)
        .as("prOutput delta must complete under the AC7 10s target")
        .isLessThan(10_000L);
  }

  /** A JSON {@code {"diff": "..."}} payload spanning {@code files} synthetic file hunks. */
  private static String prDiffPayload(int files, int addsPerFile) {
    StringBuilder diff = new StringBuilder();
    for (int f = 0; f < files; f++) {
      String path = "src/module/File" + f + ".java";
      diff.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
      diff.append("--- a/").append(path).append('\n');
      diff.append("+++ b/").append(path).append('\n');
      diff.append("@@ -1,1 +1,").append(1 + addsPerFile).append(" @@\n");
      diff.append(" context line\n");
      for (int i = 0; i < addsPerFile; i++) {
        diff.append("+added line ").append(i).append('\n');
      }
    }
    return "{\"diff\":"
        + "\""
        + diff.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        + "\"}";
  }

  // ---- seeding helpers --------------------------------------------------------------------------

  private record Seed(String runPublicId, long runId, String suffix) {}

  private record Artifact(String publicId, long dbId) {}

  private Seed seedRun(String label) {
    String suffix = label + Integer.toHexString(System.identityHashCode(label)) + seededRuns.size();
    String run = "run_rdcit" + suffix;
    jdbcTemplate.update(
        "insert into workflow_runs (public_id, current_state) values (?, 'Executing')", run);
    seededRuns.add(run);
    Long runId =
        jdbcTemplate.queryForObject(
            "select id from workflow_runs where public_id = ?", Long.class, run);
    return new Seed(run, runId, suffix);
  }

  private Artifact seedArtifact(
      Seed seed, String type, int version, Long parentDbId, String payload, String actor) {
    String artifactPublicId = "art_rdcit" + seed.suffix + type.substring(0, 2) + "v" + version;
    String evt = "evt_rdcit" + seed.suffix + type.substring(0, 2) + "v" + version;
    jdbcTemplate.update(
        "insert into workflow_events (public_id, workflow_run_id, event_type, actor_identity,"
            + " actor_type) values (?, ?, 'artifact.draftCreated', ?, 'human')",
        evt,
        seed.runId,
        actor);
    long eventId =
        jdbcTemplate.queryForObject(
            "select id from workflow_events where public_id = ?", Long.class, evt);

    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    String storageRef =
        payloadStore.write(seed.runPublicId, artifactPublicId, version, ext(type), bytes);
    String checksum = ArtifactChecksum.digestHex("SHA-256", bytes).orElseThrow();

    jdbcTemplate.update(
        "insert into artifacts (public_id, workflow_run_id, artifact_type, version,"
            + " parent_artifact_id, classification, storage_ref, checksum_algorithm, checksum_value,"
            + " status, linked_event_id, created_at) values (?, ?, ?, ?, ?, 'shareable-redacted',"
            + " ?, 'SHA-256', ?, 'available', ?, now())",
        artifactPublicId,
        seed.runId,
        type,
        version,
        parentDbId,
        storageRef,
        checksum,
        eventId);
    long dbId =
        jdbcTemplate.queryForObject(
            "select id from artifacts where public_id = ?", Long.class, artifactPublicId);
    return new Artifact(artifactPublicId, dbId);
  }

  private static String ext(String type) {
    return "spec".equals(type) ? "spec.md" : type + ".json";
  }
}
