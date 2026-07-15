package org.dradgo.application.compare;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Story 4.19 (AC4, AC10) — unit coverage for the LCS-based implementation-plan step differ. */
class PlanStepDifferTest {

  private final PlanStepDiffer differ = new DefaultPlanStepDiffer();

  @Test
  void identicalStepsYieldNoChanges() {
    List<String> steps = List.of("build", "test", "deploy");
    assertThat(differ.diff(steps, steps)).isEmpty();
  }

  @Test
  void appendedStepIsReportedAddedAtItsIndex() {
    List<PlanStepChangeBlock> blocks =
        differ.diff(List.of("build", "test"), List.of("build", "test", "deploy"));

    assertThat(blocks).hasSize(1);
    PlanStepChangeBlock block = blocks.get(0);
    assertThat(block.changeKind()).isEqualTo(ChangeKind.ADDED);
    assertThat(block.currentStepText()).isEqualTo("deploy");
    assertThat(block.priorStepText()).isNull();
    assertThat(block.currentStepOrder()).isEqualTo(2);
    assertThat(block.priorStepOrder()).isNull();
    assertThat(block.stepId()).isEqualTo("2");
  }

  @Test
  void droppedStepIsReportedRemoved() {
    List<PlanStepChangeBlock> blocks =
        differ.diff(List.of("build", "test", "deploy"), List.of("build", "test"));

    assertThat(blocks).hasSize(1);
    assertThat(blocks.get(0).changeKind()).isEqualTo(ChangeKind.REMOVED);
    assertThat(blocks.get(0).priorStepText()).isEqualTo("deploy");
    assertThat(blocks.get(0).currentStepText()).isNull();
    assertThat(blocks.get(0).priorStepOrder()).isEqualTo(2);
    assertThat(blocks.get(0).currentStepOrder()).isNull();
  }

  @Test
  void inPlaceTextChangeIsReportedModified() {
    List<PlanStepChangeBlock> blocks =
        differ.diff(List.of("build", "run tests"), List.of("build", "run all tests"));

    assertThat(blocks).hasSize(1);
    PlanStepChangeBlock block = blocks.get(0);
    assertThat(block.changeKind()).isEqualTo(ChangeKind.MODIFIED);
    assertThat(block.priorStepText()).isEqualTo("run tests");
    assertThat(block.currentStepText()).isEqualTo("run all tests");
    assertThat(block.priorStepOrder()).isEqualTo(1);
    assertThat(block.currentStepOrder()).isEqualTo(1);
  }

  @Test
  void movedStepIsReportedReordered() {
    List<PlanStepChangeBlock> blocks =
        differ.diff(List.of("build", "test", "deploy"), List.of("test", "deploy", "build"));

    List<PlanStepChangeBlock> reordered =
        blocks.stream().filter(b -> b.changeKind().equals(ChangeKind.REORDERED)).toList();
    assertThat(reordered).hasSize(1);
    PlanStepChangeBlock block = reordered.get(0);
    assertThat(block.priorStepText()).isEqualTo("build");
    assertThat(block.currentStepText()).isEqualTo("build");
    assertThat(block.priorStepOrder()).isEqualTo(0);
    assertThat(block.currentStepOrder()).isEqualTo(2);
  }

  @Test
  void nullListsAreTreatedAsEmpty() {
    assertThat(differ.diff(null, null)).isEmpty();

    List<PlanStepChangeBlock> added = differ.diff(null, List.of("only"));
    assertThat(added).hasSize(1);
    assertThat(added.get(0).changeKind()).isEqualTo(ChangeKind.ADDED);
  }
}
