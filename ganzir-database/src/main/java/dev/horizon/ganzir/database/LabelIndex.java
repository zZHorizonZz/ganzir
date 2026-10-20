package dev.horizon.ganzir.database;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class LabelIndex {

  private static final HexFormat HEX = HexFormat.of();

  String of(Map<String, String> labels) {
    StringBuilder index = new StringBuilder(",");
    new TreeMap<>(labels).forEach((key, value) -> index.append(hex(key)).append('=').append(hex(value)).append(','));
    return index.toString();
  }

  String pattern(String key, String value) {
    return "%," + hex(key) + "=" + hex(value) + ",%";
  }

  String pattern(String key) {
    return "%," + hex(key) + "=%";
  }

  private String hex(String text) {
    return HEX.formatHex(text.getBytes(StandardCharsets.UTF_8));
  }
}
