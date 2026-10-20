package dev.horizon.ganzir;

import java.util.List;

public record Children(List<String> types) implements Rule {

  public Children {
    types = List.copyOf(types);
  }

  public Children(String... types) {
    this(List.of(types));
  }
}
