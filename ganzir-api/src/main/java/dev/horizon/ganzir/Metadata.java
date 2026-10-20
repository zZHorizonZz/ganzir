package dev.horizon.ganzir;

import java.util.Map;
import java.util.Objects;

public record Metadata(String displayName, Map<String, String> labels, Map<String, String> annotations) {

  public Metadata {
    Objects.requireNonNull(displayName, "displayName");
    labels = Map.copyOf(labels);
    annotations = Map.copyOf(annotations);
  }
}
