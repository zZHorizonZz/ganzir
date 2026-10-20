package dev.horizon.ganzir;

import dev.horizon.pyrite.common.InvalidArgumentException;
import dev.horizon.pyrite.common.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public record Metadata(String displayName, Map<String, String> labels, Map<String, String> annotations) {

  public static final Metadata NONE = new Metadata("", Map.of(), Map.of());

  private static final int DISPLAY_NAME = 255;
  private static final int LABELS = 64;
  private static final Pattern LABEL_KEY = Pattern.compile("[a-z][a-z0-9_-]{0,62}");
  private static final Pattern LABEL_VALUE = Pattern.compile("[a-z0-9_-]{0,63}");
  private static final int ANNOTATIONS = 256 * 1024;

  public Metadata {
    Objects.requireNonNull(displayName, "displayName");
    labels = Map.copyOf(labels);
    annotations = Map.copyOf(annotations);
    List<Violation> violations = new ArrayList<>();
    if (displayName.length() > DISPLAY_NAME) {
      violations.add(new Violation("displayName", "at most " + DISPLAY_NAME + " characters"));
    }
    if (labels.size() > LABELS) {
      violations.add(new Violation("labels", "at most " + LABELS + " labels"));
    }
    for (Map.Entry<String, String> label : labels.entrySet()) {
      if (!LABEL_KEY.matcher(label.getKey()).matches() || !LABEL_VALUE.matcher(label.getValue()).matches()) {
        violations.add(new Violation("labels", "a key of 1 to 63 lowercase letters, digits, '_' and '-' that starts "
            + "with a letter, and a value of up to 63 of them; " + label.getKey() + " is not"));
      }
    }
    int size = 0;
    for (Map.Entry<String, String> annotation : annotations.entrySet()) {
      if (annotation.getKey().isEmpty()) {
        violations.add(new Violation("annotations", "keys that are not empty"));
      }
      size += annotation.getKey().length() + annotation.getValue().length();
    }
    if (size > ANNOTATIONS) {
      violations.add(new Violation("annotations", "at most " + ANNOTATIONS + " characters of keys and values"));
    }
    if (!violations.isEmpty()) {
      throw new InvalidArgumentException("The metadata is not valid", violations);
    }
  }
}
