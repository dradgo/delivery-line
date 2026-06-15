package org.dradgo.adapters.cli;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.dradgo.application.workflow.WorkflowInspectionService;
import org.dradgo.application.workflow.WorkflowInspectionService.RunnerQueueStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

/**
 * Story 3.19 (AC2/AC3) — {@code deliveryline workers status} CLI surface for runner-queue +
 * worker-pool inspection. A dedicated {@code @CommandGroup} (not another method on {@code
 * WorkflowCommands}) keeps the queue-inspection concern isolated and the dependency surface minimal
 * (only {@link WorkflowInspectionService} + the pure {@link WorkflowCommandOutputs} renderer).
 *
 * <p>Reads go through {@link WorkflowInspectionService#getRunnerQueueStatus(String)} — the single
 * mandated seam (AC10). {@code --format=json} emits the stable-schema JSON; {@code --watch}
 * refreshes the text view on an interval. ANSI color (yellow/red on stale counts + queue depth) is
 * emitted only for an interactive TTY in text mode; a non-TTY pipe and JSON output stay plain
 * (AC3).
 */
@Component
@CommandGroup(
    name = "workers",
    description = "Runner worker-pool + queue inspection",
    prefix = "deliveryline")
public class WorkerCommands {

  private static final Logger log = LoggerFactory.getLogger(WorkerCommands.class);
  private static final String FORMAT_TEXT = "text";
  private static final String FORMAT_JSON = "json";

  /** Test seam for the {@code --watch} delay so unit tests need no real wall-clock sleep. */
  @FunctionalInterface
  interface Sleeper {
    void sleep(long millis) throws InterruptedException;
  }

  private final WorkflowInspectionService workflowInspectionService;
  private final WorkflowCommandOutputs outputs;
  private final CliInteractivityDetector interactivity;
  // --watch loop seams: the gate decides whether to loop again (always true in production →
  // refresh until Ctrl-C; finite in tests), the sleeper performs the inter-refresh delay.
  private final BooleanSupplier watchGate;
  private final Sleeper sleeper;

  // @Autowired so Spring picks this ctor over the package-private test ctor below — two
  // constructors without it fail context startup with "No default constructor found"
  // (memory: two-public-constructors-need-autowired).
  @org.springframework.beans.factory.annotation.Autowired
  public WorkerCommands(
      WorkflowInspectionService workflowInspectionService,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity) {
    this(workflowInspectionService, outputs, interactivity, () -> true, Thread::sleep);
  }

  WorkerCommands(
      WorkflowInspectionService workflowInspectionService,
      WorkflowCommandOutputs outputs,
      CliInteractivityDetector interactivity,
      BooleanSupplier watchGate,
      Sleeper sleeper) {
    this.workflowInspectionService =
        Objects.requireNonNull(workflowInspectionService, "workflowInspectionService");
    this.outputs = Objects.requireNonNull(outputs, "outputs");
    this.interactivity = Objects.requireNonNull(interactivity, "interactivity");
    this.watchGate = Objects.requireNonNull(watchGate, "watchGate");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  @Command(
      name = "status",
      description =
          "Show runner worker-pool state, queue depth, oldest-queued age, stale counts, and"
              + " per-worker current work. --watch refreshes on an interval; --format=json emits a"
              + " stable-schema document.")
  public String status(
      @Option(
              longName = "format",
              description = "Output format: text or json",
              defaultValue = FORMAT_TEXT)
          String format,
      @Option(
              longName = "watch",
              description = "Continuously refresh the text view on an interval",
              defaultValue = "false")
          boolean watch,
      @Option(
              longName = "interval-ms",
              description = "Refresh interval for --watch, in milliseconds",
              defaultValue = "5000")
          long intervalMs,
      @Option(
              longName = "batch-id",
              description = "Scope queue depth + workers to one batch (bat_...) — AC9")
          String batchId) {
    boolean json = FORMAT_JSON.equalsIgnoreCase(format);
    // ANSI only for an interactive TTY in text mode; JSON + non-TTY pipes stay plain (AC3).
    boolean ansi = !json && interactivity.isInteractive();

    if (!watch) {
      log.info("workers status entry batchId={} format={}", batchId, json ? "json" : "text");
      String rendered = renderTick(batchId, json, ansi);
      log.info("workers status success batchId={}", batchId);
      return rendered;
    }

    // --watch: refresh until the gate stops the loop (Ctrl-C in production). Logs once at start,
    // not per tick (Logging Requirements). JSON ignores --watch color codes.
    log.info("workers status --watch started batchId={} intervalMs={}", batchId, intervalMs);
    long effectiveInterval = intervalMs <= 0 ? 5000L : intervalMs;
    try {
      while (true) {
        try {
          emit(renderTick(batchId, json, ansi));
        } catch (RuntimeException tickError) {
          // A transient read failure (DB blip, lock timeout) must not end the monitor — log it and
          // keep refreshing, mirroring the metrics binder's serve-last-snapshot resilience. The
          // one-shot (non-watch) path above still propagates so a single call surfaces the error.
          log.warn(
              "workers status --watch tick failed batchId={}: {}", batchId, tickError.toString());
          emit("workers status: refresh failed (" + tickError.getClass().getSimpleName() + ")");
        }
        if (!watchGate.getAsBoolean()) {
          break;
        }
        sleeper.sleep(effectiveInterval);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      log.info("workers status --watch interrupted batchId={}", batchId);
    }
    return "";
  }

  private String renderTick(String batchId, boolean json, boolean ansi) {
    RunnerQueueStatus view = workflowInspectionService.getRunnerQueueStatus(emptyToNull(batchId));
    return json ? outputs.renderQueueStatusJson(view) : outputs.renderQueueStatusText(view, ansi);
  }

  // System.out is the Spring Shell product-output channel for the --watch refresh (mirrors
  // DoctorCommands), not diagnostic logging — line-anchored checkstyle suppression in
  // config/checkstyle/suppressions.xml.
  private static void emit(String rendered) {
    System.out.println(rendered);
  }

  private static String emptyToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
