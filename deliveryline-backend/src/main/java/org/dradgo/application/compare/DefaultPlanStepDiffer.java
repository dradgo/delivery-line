package org.dradgo.application.compare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Story 4.19 (AC4, Reconciliation 6) — default LCS-based plan-step differ. Pure (java-only); no
 * Spring / persistence dependency.
 *
 * <p>Algorithm: (1) an exact-match LCS over the two string lists marks unchanged steps (same text,
 * same relative order) — these are NOT emitted. (2) Among the leftovers, a step whose text appears
 * on both the deleted and inserted side is a {@code reordered} step (moved to a new index). (3) The
 * still-remaining deleted/inserted steps are zipped in order into {@code modified} pairs (aligned
 * pair with differing text). (4) Any final leftovers are {@code removed} (only in A) or {@code
 * added} (only in B). {@code stepId}/order fields are the 0-based indices.
 */
final class DefaultPlanStepDiffer implements PlanStepDiffer {

  @Override
  public List<PlanStepChangeBlock> diff(List<String> priorSteps, List<String> currentSteps) {
    List<String> a = priorSteps == null ? List.of() : priorSteps;
    List<String> b = currentSteps == null ? List.of() : currentSteps;

    boolean[] matchedA = new boolean[a.size()];
    boolean[] matchedB = new boolean[b.size()];
    for (int[] pair : lcs(a, b)) {
      matchedA[pair[0]] = true;
      matchedB[pair[1]] = true;
    }

    List<Integer> deleted = new ArrayList<>();
    for (int i = 0; i < a.size(); i++) {
      if (!matchedA[i]) {
        deleted.add(i);
      }
    }
    List<Integer> inserted = new ArrayList<>();
    for (int j = 0; j < b.size(); j++) {
      if (!matchedB[j]) {
        inserted.add(j);
      }
    }

    List<PlanStepChangeBlock> blocks = new ArrayList<>();
    boolean[] insertedConsumed = new boolean[inserted.size()];
    List<Integer> remainingDeleted = new ArrayList<>();

    // (2) reorder pass — same text present on both leftover sides = a moved step.
    for (int di : deleted) {
      int matchedInsertSlot = -1;
      for (int k = 0; k < inserted.size(); k++) {
        if (!insertedConsumed[k] && a.get(di).equals(b.get(inserted.get(k)))) {
          matchedInsertSlot = k;
          break;
        }
      }
      if (matchedInsertSlot >= 0) {
        insertedConsumed[matchedInsertSlot] = true;
        int bi = inserted.get(matchedInsertSlot);
        blocks.add(
            new PlanStepChangeBlock(
                String.valueOf(bi), ChangeKind.REORDERED, a.get(di), b.get(bi), di, bi));
      } else {
        remainingDeleted.add(di);
      }
    }

    List<Integer> remainingInserted = new ArrayList<>();
    for (int k = 0; k < inserted.size(); k++) {
      if (!insertedConsumed[k]) {
        remainingInserted.add(inserted.get(k));
      }
    }

    // (3) modified pass — zip remaining deleted/inserted in order.
    int paired = Math.min(remainingDeleted.size(), remainingInserted.size());
    for (int p = 0; p < paired; p++) {
      int di = remainingDeleted.get(p);
      int bi = remainingInserted.get(p);
      blocks.add(
          new PlanStepChangeBlock(
              String.valueOf(bi), ChangeKind.MODIFIED, a.get(di), b.get(bi), di, bi));
    }
    // (4) leftovers.
    for (int p = paired; p < remainingDeleted.size(); p++) {
      int di = remainingDeleted.get(p);
      blocks.add(
          new PlanStepChangeBlock(
              String.valueOf(di), ChangeKind.REMOVED, a.get(di), null, di, null));
    }
    for (int p = paired; p < remainingInserted.size(); p++) {
      int bi = remainingInserted.get(p);
      blocks.add(
          new PlanStepChangeBlock(String.valueOf(bi), ChangeKind.ADDED, null, b.get(bi), null, bi));
    }

    blocks.sort(
        Comparator.comparingInt(DefaultPlanStepDiffer::orderKey)
            .thenComparingInt(x -> kindRank(x.changeKind())));
    return blocks;
  }

  private static int orderKey(PlanStepChangeBlock block) {
    return block.currentStepOrder() != null ? block.currentStepOrder() : block.priorStepOrder();
  }

  private static int kindRank(String kind) {
    return switch (kind) {
      case ChangeKind.REMOVED -> 0;
      case ChangeKind.MODIFIED -> 1;
      case ChangeKind.REORDERED -> 2;
      default -> 3; // added
    };
  }

  /** Order-preserving longest common subsequence as {@code (aIndex, bIndex)} pairs. */
  private static List<int[]> lcs(List<String> a, List<String> b) {
    int n = a.size();
    int m = b.size();
    int[][] dp = new int[n + 1][m + 1];
    for (int i = n - 1; i >= 0; i--) {
      for (int j = m - 1; j >= 0; j--) {
        dp[i][j] =
            a.get(i).equals(b.get(j)) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
      }
    }
    List<int[]> pairs = new ArrayList<>();
    int i = 0;
    int j = 0;
    while (i < n && j < m) {
      if (a.get(i).equals(b.get(j))) {
        pairs.add(new int[] {i, j});
        i++;
        j++;
      } else if (dp[i + 1][j] >= dp[i][j + 1]) {
        i++;
      } else {
        j++;
      }
    }
    return pairs;
  }
}
