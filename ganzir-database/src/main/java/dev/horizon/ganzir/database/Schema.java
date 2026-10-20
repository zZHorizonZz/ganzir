package dev.horizon.ganzir.database;

import dev.horizon.ganzir.Children;
import dev.horizon.ganzir.Depth;
import dev.horizon.ganzir.Model;
import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Parents;
import dev.horizon.ganzir.Reason;
import dev.horizon.ganzir.Root;
import dev.horizon.ganzir.Rule;
import dev.horizon.ganzir.Scope;
import dev.horizon.pyrite.common.FailedPreconditionException;
import dev.horizon.pyrite.common.InvalidArgumentException;
import dev.horizon.pyrite.common.Violation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class Schema {

  static final int MAX_DEPTH = 24;

  private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]{0,62}");
  private static final int NAME = 255;

  private final Model model;
  private final Map<String, NodeType> types = new LinkedHashMap<>();

  Schema(Model model) {
    this.model = model;
    List<Violation> violations = new ArrayList<>();
    for (NodeType type : model.types()) {
      if (!TYPE.matcher(type.name()).matches()) {
        violations.add(new Violation("types", "names of 1 to 63 lowercase letters, digits and '_' that start with a "
            + "letter; " + type.name() + " is not"));
      }
      if (types.putIfAbsent(type.name(), type) != null) {
        violations.add(new Violation("types", "each type once; " + type.name() + " is declared twice"));
      }
    }
    for (NodeType type : model.types()) {
      violations.addAll(violations(type));
    }
    if (!violations.isEmpty()) {
      throw new InvalidArgumentException("The model is not valid", violations);
    }
  }

  Model model() {
    return model;
  }

  NodeType root(String type, String name) {
    NodeType declared = declared(type);
    checkName(name);
    if (rule(declared, Root.class).isEmpty()) {
      throw refused("A node of the type " + type + " cannot be a root");
    }
    return declared;
  }

  NodeType child(String type, String name, String parentType, int depth) {
    NodeType declared = declared(type);
    checkName(name);
    if (!rule(declared, Parents.class).map(parents -> parents.types().contains(parentType)).orElse(false)) {
      throw refused("A node of the type " + type + " cannot be placed under a node of the type " + parentType);
    }
    Optional<Children> children = Optional.ofNullable(types.get(parentType))
        .flatMap(parent -> rule(parent, Children.class));
    if (children.isPresent() && !children.get().types().contains(type)) {
      throw refused("A node of the type " + parentType + " cannot contain a node of the type " + type);
    }
    checkDepth(declared, depth);
    return declared;
  }

  void checkDepth(String type, int depth) {
    checkDepth(declared(type), depth);
  }

  void checkName(String name) {
    if (name.isEmpty() || name.length() > NAME) {
      throw new InvalidArgumentException("The name is not valid", List.of(new Violation("name", "1 to " + NAME
          + " characters")));
    }
  }

  NodeType global(String type) {
    NodeType declared = declared(type);
    if (declared.scope() != Scope.GLOBAL) {
      throw new InvalidArgumentException("The names of the type " + type + " are unique among siblings only; ask the "
          + "parent for its child", List.of(new Violation("type", "a type whose names are unique globally")));
    }
    return declared;
  }

  Duration retention(String type) {
    return Optional.ofNullable(types.get(type)).map(NodeType::retention).orElse(Duration.ZERO);
  }

  private void checkDepth(NodeType type, int depth) {
    if (depth > MAX_DEPTH) {
      throw refused("A node cannot be more than " + MAX_DEPTH + " levels below its root");
    }
    Optional<Depth> limit = rule(type, Depth.class);
    if (limit.isPresent() && depth > limit.get().levels()) {
      throw refused("A node of the type " + type.name() + " cannot be more than " + limit.get().levels()
          + " levels below its root");
    }
  }

  private NodeType declared(String type) {
    NodeType declared = types.get(type);
    if (declared == null) {
      throw new InvalidArgumentException("The model declares no type " + type, List.of(new Violation("type",
          "a type that the model declares")));
    }
    return declared;
  }

  private <R extends Rule> Optional<R> rule(NodeType type, Class<R> kind) {
    return type.rules().stream().filter(kind::isInstance).map(kind::cast).findFirst();
  }

  private List<Violation> violations(NodeType type) {
    List<Violation> violations = new ArrayList<>();
    String field = "types." + type.name();
    if (type.retention().isNegative()) {
      violations.add(new Violation(field + ".retention", "zero or a positive duration"));
    }
    Set<Class<?>> kinds = new HashSet<>();
    for (Rule rule : type.rules()) {
      if (!kinds.add(rule.getClass())) {
        violations.add(new Violation(field + ".rules", "each kind of rule once; " + rule.getClass().getSimpleName()
            + " is there twice"));
      }
      List<String> named = switch (rule) {
        case Root root -> List.of();
        case Parents parents -> parents.types();
        case Children children -> children.types();
        case Depth depth -> {
          if (depth.levels() < 0 || depth.levels() > MAX_DEPTH) {
            violations.add(new Violation(field + ".rules", "a depth from 0 to " + MAX_DEPTH));
          }
          yield List.of();
        }
      };
      for (String other : named) {
        if (!types.containsKey(other)) {
          violations.add(new Violation(field + ".rules", "types that the model declares; " + other + " is not"));
        }
      }
    }
    boolean root = rule(type, Root.class).isPresent();
    boolean placeable = rule(type, Parents.class).map(parents -> !parents.types().isEmpty()).orElse(false);
    if (!root && !placeable) {
      violations.add(new Violation(field + ".rules", "Root, or Parents with at least one type"));
    }
    return violations;
  }

  private FailedPreconditionException refused(String detail) {
    return new FailedPreconditionException(Reason.RULE.name(), detail);
  }
}
