package dev.horizon.ganzir.database;

import dev.horizon.ganzir.Children;
import dev.horizon.ganzir.Depth;
import dev.horizon.ganzir.Model;
import dev.horizon.ganzir.NodeType;
import dev.horizon.ganzir.Parents;
import dev.horizon.ganzir.Root;
import dev.horizon.ganzir.Rule;
import dev.horizon.ganzir.Scope;
import dev.horizon.ganzir.database.schema.v1.Tables;
import java.time.Duration;

final class ModelMapper implements ProtobufMapper<Model, Tables.Model, Tables.Model.Builder> {

  @Override
  public Model read(Tables.Model row) {
    return new Model(row.getDefinition().getTypesList().stream()
        .map(type -> new NodeType(type.getName(), Scope.valueOf(type.getScope()),
            Duration.ofMillis(type.getRetentionMillis()), type.getRulesList().stream().map(this::rule).toList()))
        .toList());
  }

  @Override
  public Tables.Model.Builder write(Tables.Model.Builder row, Model model) {
    Tables.ModelDefinition.Builder definition = Tables.ModelDefinition.newBuilder();
    for (NodeType type : model.types()) {
      definition.addTypes(Tables.TypeDefinition.newBuilder()
          .setName(type.name())
          .setScope(type.scope().name())
          .setRetentionMillis(type.retention().toMillis())
          .addAllRules(type.rules().stream().map(this::definition).toList()));
    }
    return row.setDefinition(definition);
  }

  private Rule rule(Tables.RuleDefinition definition) {
    return switch (definition.getRuleCase()) {
      case ROOT -> new Root();
      case PARENTS -> new Parents(definition.getParents().getTypesList());
      case CHILDREN -> new Children(definition.getChildren().getTypesList());
      case DEPTH -> new Depth(definition.getDepth());
      case RULE_NOT_SET -> throw new IllegalStateException("A rule of the model has a kind that this version of Ganzir "
          + "does not know");
    };
  }

  private Tables.RuleDefinition definition(Rule rule) {
    return switch (rule) {
      case Root root -> Tables.RuleDefinition.newBuilder().setRoot(true).build();
      case Parents parents -> Tables.RuleDefinition.newBuilder()
          .setParents(Tables.TypeList.newBuilder().addAllTypes(parents.types()))
          .build();
      case Children children -> Tables.RuleDefinition.newBuilder()
          .setChildren(Tables.TypeList.newBuilder().addAllTypes(children.types()))
          .build();
      case Depth depth -> Tables.RuleDefinition.newBuilder().setDepth(depth.levels()).build();
    };
  }
}
