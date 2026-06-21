package org.dradgo.application.runner;

/**
 * Story 3d-5 (FR65, AC1) — finished-mode payload for the Step Execution Log Viewer: the capped,
 * already-redacted stdout + stderr TEXT of a runner execution, read from the story-3.6 durable
 * redacted store ({@code runner-logs/{rex}/}).
 *
 * <p><b>Already redacted (Trap T4).</b> The bytes were post-hoc scanned + redacted by {@code
 * RunnerLogCaptureService} at container exit (story 3.6) — this is the authoritative redaction
 * guarantee. The viewer replays this text verbatim; it MUST NOT be re-redacted and the RAW
 * workspace store is never read here.
 *
 * @param stdout the redacted stdout text (possibly capped — see {@code truncated})
 * @param stderr the redacted stderr text (possibly capped — see {@code truncated})
 * @param truncated {@code true} when either stream exceeded the read cap and carries a truncation
 *     marker
 */
public record RedactedRunnerLog(String stdout, String stderr, boolean truncated) {}
