package dev.horizon.ganzir.database;

import dev.horizon.basepoint.Filter;
import dev.horizon.ganzir.Finalizer;
import dev.horizon.ganzir.database.schema.v1.Tables;
import io.vertx.core.Future;

final class DatabaseFinalizer implements Finalizer {

  private final NodeBackend nodes;
  private final Tables.Finalizer row;

  DatabaseFinalizer(NodeBackend nodes, Tables.Finalizer row) {
    this.nodes = nodes;
    this.row = row;
  }

  @Override
  public String name() {
    return row.getName();
  }

  @Override
  public Future<Void> remove() {
    return nodes.database().transaction(transaction -> nodes.lock(row.getNodeId())
        .compose(node -> nodes.finalizers().delete()
            .where(Filter.and(Filter.eq("node_id", row.getNodeId()), Filter.eq("name", row.getName())))
            .execute()
            .compose(removed -> removed == 0
                ? Future.<Void> succeededFuture()
                : nodes.save(node.finalized(node.finalizers().size() > 1), node.revision()).mapEmpty())));
  }
}
