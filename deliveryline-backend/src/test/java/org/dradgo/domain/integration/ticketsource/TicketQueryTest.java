package org.dradgo.domain.integration.ticketsource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Story 3i-2 (AC1) — normalization contract of the neutral browse filter. */
class TicketQueryTest {

  @Test
  void blankScalarFieldsNormalizeToAbsent() {
    TicketQuery query = new TicketQuery("   ", List.of(), "\t", 10);

    assertThat(query.assignee()).isNull();
    assertThat(query.state()).isNull();
    assertThat(query.hasAssignee()).isFalse();
    assertThat(query.hasState()).isFalse();
  }

  @Test
  void scalarFieldsAreTrimmed() {
    TicketQuery query = new TicketQuery("  acct-1  ", List.of(), "  To Do  ", 10);

    assertThat(query.assignee()).isEqualTo("acct-1");
    assertThat(query.state()).isEqualTo("To Do");
  }

  @Test
  void nullComponentsNormalizeToAnEmptyList() {
    TicketQuery query = new TicketQuery(null, null, null, 10);

    assertThat(query.components()).isEmpty();
    assertThat(query.hasComponents()).isFalse();
  }

  @Test
  void componentsAreTrimmedBlankDroppedAndOrderPreserved() {
    TicketQuery query =
        new TicketQuery(null, Arrays.asList(" billing ", "", null, "api", "   "), null, 10);

    assertThat(query.components()).containsExactly("billing", "api");
    assertThat(query.hasComponents()).isTrue();
  }

  @Test
  void componentsAreDefensivelyCopiedAndUnmodifiable() {
    List<String> mutable = new ArrayList<>(List.of("billing"));
    TicketQuery query = new TicketQuery(null, mutable, null, 10);

    mutable.add("sneaked-in");

    assertThat(query.components()).containsExactly("billing");
    assertThatThrownBy(() -> query.components().add("nope"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void limitIsClampedDownToTheMaximum() {
    assertThat(new TicketQuery(null, List.of(), null, TicketQuery.MAX_LIMIT + 500).limit())
        .isEqualTo(TicketQuery.MAX_LIMIT);
    assertThat(new TicketQuery(null, List.of(), null, 25).limit()).isEqualTo(25);
  }

  @Test
  void nonPositiveLimitIsRejected() {
    assertThatThrownBy(() -> new TicketQuery(null, List.of(), null, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> new TicketQuery(null, List.of(), null, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void componentsAtTheMaximumAreAccepted() {
    List<String> atCeiling = new ArrayList<>();
    for (int i = 0; i < TicketQuery.MAX_COMPONENTS; i++) {
      atCeiling.add("component-" + i);
    }

    assertThat(new TicketQuery(null, atCeiling, null, 10).components())
        .hasSize(TicketQuery.MAX_COMPONENTS);
  }

  /**
   * Over-large component sets THROW rather than clamp (unlike {@code limit}). Clamping would
   * silently drop tokens from the source's {@code component in (…)} clause, narrowing the match set
   * — the browse would hide tickets the operator explicitly asked for.
   */
  @Test
  void componentsBeyondTheMaximumAreRejectedRatherThanClamped() {
    List<String> overCeiling = new ArrayList<>();
    for (int i = 0; i <= TicketQuery.MAX_COMPONENTS; i++) {
      overCeiling.add("component-" + i);
    }

    assertThatThrownBy(() -> new TicketQuery(null, overCeiling, null, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not exceed " + TicketQuery.MAX_COMPONENTS);
  }

  /** Blank tokens are dropped BEFORE the ceiling is applied, so padding cannot trip the guard. */
  @Test
  void blankComponentsAreDroppedBeforeTheCeilingIsChecked() {
    List<String> padded = new ArrayList<>();
    for (int i = 0; i < TicketQuery.MAX_COMPONENTS; i++) {
      padded.add("component-" + i);
      padded.add("   ");
    }

    assertThat(new TicketQuery(null, padded, null, 10).components())
        .hasSize(TicketQuery.MAX_COMPONENTS);
  }

  @Test
  void unfilteredCarriesNoConstraintsAndTheDefaultLimit() {
    TicketQuery query = TicketQuery.unfiltered();

    assertThat(query.hasAssignee()).isFalse();
    assertThat(query.hasComponents()).isFalse();
    assertThat(query.hasState()).isFalse();
    assertThat(query.limit()).isEqualTo(TicketQuery.DEFAULT_LIMIT);
  }
}
