package dev.horizon.ganzir.database;

import java.util.Arrays;
import java.util.List;

record NodePath(String value) {

  static final NodePath TOP = new NodePath("/");

  NodePath child(String id) {
    return new NodePath(value + id + "/");
  }

  List<String> ids() {
    return Arrays.stream(value.split("/")).filter(id -> !id.isEmpty()).toList();
  }

  boolean contains(NodePath other) {
    return other.value.startsWith(value);
  }

  NodePath moved(NodePath from, NodePath to) {
    return new NodePath(to.value + value.substring(from.value.length()));
  }
}
