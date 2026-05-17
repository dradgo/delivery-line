package org.dradgo.adapters.cli;

import org.springframework.stereotype.Component;

@Component
public class CliInteractivityDetector {

  public boolean isInteractive() {
    return System.console() != null;
  }
}
