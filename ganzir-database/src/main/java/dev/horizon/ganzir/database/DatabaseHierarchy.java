package dev.horizon.ganzir.database;

import dev.horizon.aquery.aip158.Page;
import dev.horizon.aquery.aip158.PageRequest;
import dev.horizon.basepoint.Filter;
import dev.horizon.cinder.Database;
import dev.horizon.ganzir.Hierarchy;
import dev.horizon.ganzir.Metadata;
import dev.horizon.ganzir.Model;
import dev.horizon.ganzir.Node;
import dev.horizon.ganzir.database.schema.v1.Tables;
import dev.horizon.pyrite.common.NotFoundException;
import io.vertx.core.Future;
import java.time.Clock;
import java.time.Duration;

public final class DatabaseHierarchy implements Hierarchy {

  private final ModelBackend models;
  private final NodeBackend nodes;

  public DatabaseHierarchy(Database database) {
    this(database, Clock.systemUTC());
  }

  public DatabaseHierarchy(Database database, Clock clock) {
    try {
      database.model(Tables.Node.class);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("The database cannot store Ganzir's tables, which are protobuf messages; build "
          + "it with .extension(new ProtobufExtension())", unknown);
    }
    this.models = new ModelBackend(database);
    this.nodes = new NodeBackend(database, models, new LienBackend(database), new FinalizerBackend(database),
        Clock.tick(clock, Duration.ofMillis(1)));
  }

  @Override
  public Future<Void> declare(Model model) {
    return Future.succeededFuture(model).map(Schema::new)
        .compose(schema -> models.declare(schema, nodes.clock().instant()));
  }

  @Override
  public Future<Model> model() {
    return models.latest().map(found -> found.orElseThrow(() -> new NotFoundException("model", "latest")));
  }

  @Override
  public Future<Node> create(String type, String name) {
    return create(type, name, Metadata.NONE);
  }

  @Override
  public Future<Node> create(String type, String name, Metadata metadata) {
    return models.schema().compose(schema -> nodes.insert(schema.root(type, name), name, metadata, NodePath.TOP));
  }

  @Override
  public Future<Node> node(String id) {
    return nodes.node(id);
  }

  @Override
  public Future<Node> node(String type, String name) {
    return models.schema().compose(schema -> {
      schema.global(type);
      return nodes.first(Filter.and(Filter.eq("type", type), Filter.eq("scope", ""), Filter.eq("name", name)))
          .map(found -> found.orElseThrow(() -> new NotFoundException("node", type + "/" + name)));
    });
  }

  @Override
  public Future<Page<Node>> roots(PageRequest request) {
    return nodes.page(Filter.isNull("parent_id"), request);
  }

  @Override
  public Future<Long> purge() {
    return nodes.purge();
  }
}
