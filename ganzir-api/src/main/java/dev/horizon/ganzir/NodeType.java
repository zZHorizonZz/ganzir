package dev.horizon.ganzir;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record NodeType(String name, Scope scope, Duration retention, List<Rule> rules) {

  public NodeType {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(retention, "retention");
    rules = List.copyOf(rules);
  }
}
