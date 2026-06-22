package org.dradgo.adapters.rest;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.dradgo.application.workflow.ConsoleStreamSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Story 3d-6 (FR68) — adapts the application-side {@link ConsoleStreamSink} to an {@link
 * SseEmitter}, keeping the Spring streaming type OUT of the application layer (mirrors {@link
 * SseLogStreamSink}). Each callback writes one named SSE event; {@code onEnd}/{@code onError} are
 * terminal and complete the emitter. A send failure (client gone) marks the sink dead and completes
 * the emitter so no further writes are attempted.
 *
 * <p>The callback methods are invoked from multiple threads (the docker attach callback, the
 * controller's executor task, and the Tomcat emitter timeout/disconnect callbacks), so they are
 * {@code synchronized} on this sink: this makes the {@code dead} check-then-write atomic,
 * preventing a stray {@code console}/terminal event from slipping out after the session already
 * ended.
 *
 * <p><b>Read-only (DD-1).</b> There is NO input method here — the sink only writes container output
 * outward to the operator; nothing flows back toward the container. NEVER logs streamed content
 * (AC7): the {@code chunk} value is forwarded to the emitter but only a one-time first-byte marker
 * + lifecycle phases are logged.
 */
final class SseConsoleStreamSink implements ConsoleStreamSink {

  private static final Logger log = LoggerFactory.getLogger(SseConsoleStreamSink.class);

  private final SseEmitter emitter;
  private boolean dead;
  private boolean firstByteLogged;

  SseConsoleStreamSink(SseEmitter emitter) {
    this.emitter = emitter;
  }

  @Override
  public synchronized void onChunk(String stream, String redactedChunk, long seq) {
    if (dead) {
      return;
    }
    if (!firstByteLogged) {
      firstByteLogged = true;
      log.info("diagnostic console first-byte stream={} seq={}", stream, seq);
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("stream", stream);
    data.put("chunk", redactedChunk);
    data.put("seq", seq);
    send("console", data);
  }

  @Override
  public synchronized void onStatus(String phase, String runnerExecutionId) {
    if (dead) {
      return;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("phase", phase);
    data.put("rex", runnerExecutionId);
    send("status", data);
  }

  @Override
  public synchronized void onEnd(String reason) {
    if (!dead) {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("reason", reason);
      send("end", data);
    }
    complete();
  }

  @Override
  public synchronized void onError(String reason) {
    if (dead) {
      return;
    }
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("reason", reason);
    send("error", data);
  }

  private void send(String event, Map<String, Object> data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException sendFailure) {
      // Client disconnected (or the emitter already completed) — stop writing. The attach's
      // onError/onCompletion callback releases the console subscription (Trap T3).
      dead = true;
      log.warn("diagnostic console send failed event={} cause={}", event, sendFailure.toString());
    }
  }

  private void complete() {
    dead = true;
    try {
      emitter.complete();
    } catch (RuntimeException alreadyDone) {
      // Emitter may already be completed (timeout / disconnect) — benign.
      log.debug("diagnostic console emitter already completed cause={}", alreadyDone.toString());
    }
  }
}
