package com.menumaestro.backend.common.validation;

import java.util.ArrayList;
import java.util.List;

/** Collects issues with their property paths while a {@link Schema} parses a value. */
final class ParseContext {
  private final List<ValidationException.Issue> issues;
  private final List<Object> path;

  ParseContext() { this(new ArrayList<>(), new ArrayList<>()); }

  private ParseContext(List<ValidationException.Issue> issues, List<Object> path) {
    this.issues = issues;
    this.path = path;
  }

  List<ValidationException.Issue> issues() { return issues; }

  ParseContext isolated() { return new ParseContext(new ArrayList<>(), path); }

  void push(Object segment) { path.add(segment); }

  void pop() { path.remove(path.size() - 1); }

  void issue(String message, boolean fatal) { issues.add(new ValidationException.Issue(List.copyOf(path), message)); }

  void addAll(List<ValidationException.Issue> more) { issues.addAll(more); }
}
