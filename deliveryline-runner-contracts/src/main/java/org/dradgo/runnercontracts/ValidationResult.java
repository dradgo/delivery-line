package org.dradgo.runnercontracts;

import java.util.List;

public record ValidationResult(boolean valid, List<ValidationError> errors) {
  public ValidationResult {
    errors = errors == null ? List.of() : List.copyOf(errors);
  }
}
