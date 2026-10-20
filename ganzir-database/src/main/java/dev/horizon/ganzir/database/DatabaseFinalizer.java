package dev.horizon.ganzir.database;

import dev.horizon.basepoint.Filter;
import dev.horizon.ganzir.Finalizer;
import dev.horizon.ganzir.database.schema.v1.Tables;
import io.vertx.core.Future;

final class DatabaseFinalizer implements Finalizer {

  private final NodeBackend backend;
  private final Tables.Finalizer row;

  DatabaseFinalizer(NodeBackend backend, Tables.Finalizer row) {
    this.backend = backend;
    this.row = row;
  }

  @Override
  public String name() {
    return row.getName();
  }

  @Override
  public Future<Void> remove() {
    return backend.database().transaction(_ -> backend.lock(row.getNodeId()).compose(node -> {
      if (!node.removeFinalizer(row.getName())) {
        return Future.succeededFuture();
      }
      return backend.finalizers().delete()
          .where(Filter.and(Filter.eq("node_id", row.getNodeId()), Filter.eq("name", row.getName())))
          .execute()
          .compose(_ -> backend.save(node))
          .mapEmpty();
    }));
  }
}
