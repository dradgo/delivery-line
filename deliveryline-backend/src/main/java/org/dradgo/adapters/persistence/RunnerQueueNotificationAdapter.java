package org.dradgo.adapters.persistence;

import java.util.Objects;
import org.dradgo.application.runner.queue.spi.RunnerQueueNotificationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Story 3.17b (AC2) — issues {@code NOTIFY runner_queue_updated} so the dedicated {@link
 * RunnerQueueListener} connection wakes an idle worker within the AC2 latency budget. Called from
 * {@code RunnerExecutionQueue.enqueue}'s post-commit synchronization (the {@code queued} row is
 * durable before the wake).
 *
 * <p>The channel name is a fixed identifier ({@value #CHANNEL}) — never interpolated user input —
 * so a plain {@code NOTIFY} statement is injection-safe. {@code NOTIFY} auto-commits on its own
 * pooled connection (it runs after the enqueue tx already committed).
 */
@Component
public class RunnerQueueNotificationAdapter implements RunnerQueueNotificationPort {

  static final String CHANNEL = "runner_queue_updated";

  private final JdbcTemplate jdbcTemplate;

  public RunnerQueueNotificationAdapter(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
  }

  @Override
  public void notifyQueued() {
    jdbcTemplate.execute("NOTIFY " + CHANNEL);
  }
}
