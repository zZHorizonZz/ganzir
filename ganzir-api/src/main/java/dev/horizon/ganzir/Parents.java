package dev.horizon.ganzir;

import java.util.List;

public record Parents(List<String> types) implements Rule {

  public Parents {
    types = List.copyOf(types);
  }

  public Parents(String... types) {
    this(List.of(types));
  }
}
