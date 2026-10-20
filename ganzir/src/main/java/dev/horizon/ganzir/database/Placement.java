package dev.horizon.ganzir.database;

import dev.horizon.ganzir.Children;
import dev.horizon.ganzir.Depth;
import dev.horizon.ganzir.Model;
import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Parents;
import dev.horizon.ganzir.Reason;
import dev.horizon.ganzir.Root;
import dev.horizon.ganzir.Rule;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.FailedPreconditionException;
import dev.horizon.pyrite.common.InvalidArgumentException;
import dev.horizon.pyrite.common.Violation;
import java.util.List;
import java.util.Optional;

record Placement(Model model, String type, Optional<Tables.Node> parent, int depth) {

  static final int MAX_DEPTH = 24;

  NodeType check() {
    NodeType declared = declared();
    boolean root = false;
    boolean placeable = false;
    for (Rule rule : declared.rules()) {
      switch (rule) {
        case Root ignored -> root = true;
        case Parents parents -> placeable = parent.isPresent() && parents.types().contains(parent.get().getType());
        case Children ignored -> {
        }
        case Depth ignored -> {
        }
      }
    }
    if (parent.isEmpty() && !root) {
      throw refused("A node of the type " + type + " cannot be a root");
    }
    if (parent.isPresent() && !placeable) {
      throw refused("A node of the type " + type + " cannot be placed under a node of the type "
          + parent.get().getType());
    }
    if (parent.isPresent()) {
      for (Rule rule : model.type(parent.get().getType()).map(NodeType::rules).orElse(List.of())) {
        if (rule instanceof Children children && !children.types().contains(type)) {
          throw refused("A node of the type " + parent.get().getType() + " cannot contain a node of the type " + type);
        }
      }
    }
    checkDepth(declared);
    return declared;
  }

  void checkDepth() {
    checkDepth(declared());
  }

  private void checkDepth(NodeType declared) {
    if (depth > MAX_DEPTH) {
      throw refused("A node cannot be more than " + MAX_DEPTH + " levels below its root");
    }
    for (Rule rule : declared.rules()) {
      if (rule instanceof Depth limit && depth > limit.levels()) {
        throw refused("A node of the type " + type + " cannot be more than " + limit.levels()
            + " levels below its root");
      }
    }
  }

  private NodeType declared() {
    return model.type(type).orElseThrow(() -> new InvalidArgumentException("The model declares no type " + type,
        List.of(new Violation("type", "a type that the model declares"))));
  }

  private FailedPreconditionException refused(String detail) {
    return new FailedPreconditionException(Reason.RULE.name(), detail);
  }
}
