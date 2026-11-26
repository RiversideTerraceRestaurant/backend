package com.menumaestro.backend.exception;

import java.util.List;
import java.util.stream.Collectors;

/** Request validation failure; the message matches the original "path: message; ..." format. */
public class ValidationException extends RuntimeException {
  public record Issue(List<Object> path, String message) {
    public String format() {
      return path.stream().map(String::valueOf).collect(Collectors.joining(".")) + ": " + message;
    }
  }

  private final List<Issue> issues;

  public ValidationException(List<Issue> issues) {
    super(issues.stream().map(Issue::format).collect(Collectors.joining("; ")));
    this.issues = List.copyOf(issues);
  }

  public List<Issue> issues() { return issues; }
}
