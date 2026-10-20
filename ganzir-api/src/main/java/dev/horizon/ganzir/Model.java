package dev.horizon.ganzir;

import java.util.List;
import java.util.Optional;

public record Model(List<NodeType> types) {

  public Model {
    types = List.copyOf(types);
  }

  public Optional<NodeType> type(String name) {
    return types.stream().filter(type -> type.name().equals(name)).findFirst();
  }
}
