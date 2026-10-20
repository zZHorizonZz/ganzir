package dev.horizon.ganzir.database;

import dev.horizon.basepoint.Filter;
import dev.horizon.ganzir.Hold;
import dev.horizon.ganzir.database.schema.v1.Tables;
import io.vertx.core.Future;
import java.time.Instant;

final class DatabaseHold implements Hold {

  private final DatabaseHierarchy hierarchy;
  private final Tables.Hold row;

  DatabaseHold(DatabaseHierarchy hierarchy, Tables.Hold row) {
    this.hierarchy = hierarchy;
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
  public Future<Void> release() {
    return hierarchy.database.transaction(transaction -> hierarchy.holds.delete()
        .where(Filter.and(Filter.eq("node_id", row.getNodeId()), Filter.eq("id", row.getId())))
        .execute()
        .compose(released -> released == 0 ? Future.<Void> succeededFuture() : hierarchy.touch(row.getNodeId())));
  }
}
