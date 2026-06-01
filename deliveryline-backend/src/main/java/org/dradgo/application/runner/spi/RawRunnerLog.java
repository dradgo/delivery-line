package org.dradgo.application.runner.spi;

/** Raw runner log text read from the workspace plus whether the source stream was truncated. */
public record RawRunnerLog(String text, boolean truncated) {

  public RawRunnerLog {
    text = text == null ? "" : text;
  }

  public static RawRunnerLog empty() {
    return new RawRunnerLog("", false);
  }
}
