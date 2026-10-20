package dev.horizon.ganzir.database;

import dev.horizon.basepoint.Filter;
import dev.horizon.ganzir.Lien;
import dev.horizon.ganzir.database.schema.v1.Tables;
import io.vertx.core.Future;
import java.time.Instant;

final class DatabaseLien implements Lien {

  private final NodeBackend nodes;
  private final Tables.Lien row;

  DatabaseLien(NodeBackend nodes, Tables.Lien row) {
    this.nodes = nodes;
    this.row = row;
  }

  @Override
  public String id() {
    return row.getId();
  }

  @Override
  public String origin() {
    return row.getOrigin();
  }

  @Override
  public String reason() {
    return row.getReason();
  }

  @Override
  public Instant createTime() {
    return Instant.parse(row.getCreateTime());
  }

  @Override
  public Future<Void> remove() {
    return nodes.database().transaction(transaction -> nodes.lock(row.getNodeId())
        .compose(node -> nodes.liens().delete()
            .where(Filter.and(Filter.eq("node_id", row.getNodeId()), Filter.eq("id", row.getId())))
            .execute()
            .compose(removed -> removed == 0
                ? Future.<Void> succeededFuture()
                : nodes.save(node.touched(), node.revision()).mapEmpty())));
  }
}
